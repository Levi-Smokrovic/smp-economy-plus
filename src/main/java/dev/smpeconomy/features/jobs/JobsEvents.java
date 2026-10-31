package dev.smpeconomy.features.jobs;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.Bots;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.MobOrigin;
import dev.smpeconomy.core.PlacedBlockTracker;

/**
 * Connects game events to {@link JobsService}: block breaks (Fabric {@code PlayerBlockBreakEvents.AFTER}; Veinminer up
 * to 2.12 per block via {@code mixin.VeinminerCompatMixin}, from 2.13 through the same event), right-click harvest
 * ({@link RightClickHarvestCompat}), mob deaths ({@code ServerLivingEntityEvents.AFTER_DEATH}), fishing ({@code
 * mixin.FishingHookMixin}) and bone meal growth ({@code mixin.BoneMealItemMixin}). All on the server thread; errors are
 * logged and never break vanilla flow.
 */
public final class JobsEvents {
	/** Entity tag: mob came from a spawner (pay × {@code spawnerMobFactor}). */
	public static final String TAG_SPAWNER = MobOrigin.SPAWNER;
	/** Entity tag: mob came from a spawn egg (pay × {@code spawnEggMobFactor}). */
	public static final String TAG_SPAWN_EGG = MobOrigin.SPAWN_EGG;
	/**
	 * Entity tag: bred or multiplied mob ({@code EntitySpawnReason.BREEDING}: hoglin offspring, shulker splitting,
	 * villager offspring; pay × {@code bredMobFactor}). Conversions (villager to zombie villager or witch, hoglin to
	 * zoglin, slime splitting) copy the tags (vanilla {@code ConversionType#convertCommon}).
	 */
	public static final String TAG_BRED = MobOrigin.BRED;
	/**
	 * Entity tag: mob summoned by another mob ({@code MOB_SUMMONED}: evoker vexes; pay × {@code summonedMobFactor}).
	 */
	public static final String TAG_SUMMONED = MobOrigin.SUMMONED;
	/**
	 * Entity tag: ender dragon respawned with end crystals ({@code core.mixin.EndDragonFightOriginMixin}; pay × {@code
	 * respawnedDragonFactor}, also capped below the crystals' minimum shop cost, see {@link SummonCosts}).
	 */
	public static final String TAG_RESPAWNED = MobOrigin.RESPAWNED;
	/**
	 * All origin tags that reduce hunter pay. Zombie reinforcements inherit them from the caller. Names in {@link
	 * MobOrigin}.
	 */
	public static final List<String> ORIGIN_TAGS = MobOrigin.ALL;

	/**
	 * Last BEFORE break per player, because RightClickHarvest reports only player and block after a harvest, no
	 * position.
	 */
	private record PendingHarvest(ServerLevel level, BlockPos pos, BlockState state, int tick) {}

	private static final Map<UUID, PendingHarvest> PENDING_HARVEST = new HashMap<>();
	private static boolean errorLogged;

	private JobsEvents() {}

	public static void register() {
		PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
			PENDING_HARVEST.remove(player.getUUID());
			if (player instanceof ServerPlayer sp) onBlockBroken(sp, world, pos, state);
		});
		PlayerBlockBreakEvents.CANCELED.register((world, player, pos, state, blockEntity) -> PENDING_HARVEST.remove(player.getUUID()));
		ServerLivingEntityEvents.AFTER_DEATH.register(JobsEvents::onDeath);
		ServerTickEvents.END_SERVER_TICK.register(server -> BoostedCrops.endTick());
	}

	static void registerHarvestTracking() {
		PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
			if (world instanceof ServerLevel level && player instanceof ServerPlayer && JobsConfig.get().growthKind(state) != 0) {
				PENDING_HARVEST.put(player.getUUID(), new PendingHarvest(level, pos.immutable(), state, level.getServer().getTickCount()));
			}
			return true;
		});
	}

	/** No machines from other mods, no creative or spectator. Carpet bots only with {@code botsEarn}. */
	public static boolean eligible(Player player) {
		if (!(player instanceof ServerPlayer sp) || sp instanceof FakePlayer || sp.isCreative() || sp.isSpectator()) return false;
		return !Bots.isCarpetBot(sp) || botMayEarn(sp);
	}

	private static boolean botMayEarn(ServerPlayer bot) {
		if (!JobsConfig.raw().botsEarn) return false;
		// Bots get no account on join, and a deposit without one would be lost.
		if (!EconomyService.hasAccount(bot.getUUID())) EconomyService.ensureAccount(bot.getUUID(), bot.getGameProfile().name());
		return true;
	}

	public static void onBlockBroken(ServerPlayer player, Level level, BlockPos pos, BlockState state) {
		if (!eligible(player) || state.isAir()) return;
		try {
			JobsService.onBlock(player.getUUID(), level, pos, state);
		} catch (RuntimeException e) {
			logOnce("block break", e);
		}
	}

	/**
	 * Veinminer up to 2.12 broke another block of the vein (mixin at the end of {@code VeinMinerEvent#destroyBlock}).
	 */
	public static void onVeinmined(Player player, Level level, BlockPos pos, BlockState state) {
		if (!(player instanceof ServerPlayer sp) || level.isClientSide() || !sp.level().getServer().isSameThread()) return;
		onBlockBroken(sp, level, pos, state);
	}

	public static void onRightClickHarvest(Player player, Block block) {
		if (!(player instanceof ServerPlayer sp)) return;
		PendingHarvest h = PENDING_HARVEST.remove(sp.getUUID());
		if (h == null || h.state().getBlock() != block || h.tick() != h.level().getServer().getTickCount()) return;
		try {
			if (eligible(sp)) JobsService.onBlock(sp.getUUID(), h.level(), h.pos(), h.state());
		} catch (RuntimeException e) {
			logOnce("right-click harvest", e);
		} finally {
			// the crop was reset, not replaced by air, so the bone meal marker has to be removed here
			BoostedCrops.remove(h.level(), h.pos());
		}
	}

	private static void onDeath(LivingEntity entity, net.minecraft.world.damagesource.DamageSource source) {
		if (entity.level().isClientSide() || entity instanceof Player) return;
		if (!(source.getEntity() instanceof ServerPlayer killer) || !eligible(killer)) return;
		try {
			JobsService.onMobKilled(killer.getUUID(), entity.getType(), entity instanceof Enemy, entity.getTags());
		} catch (RuntimeException e) {
			logOnce("mob death", e);
		}
	}

	public static void onFishCaught(Player player, List<ItemStack> loot) {
		if (!(player instanceof ServerPlayer sp) || !eligible(sp) || loot == null || loot.isEmpty()) return;
		try {
			JobsService.onFish(sp.getUUID(), loot);
		} catch (RuntimeException e) {
			logOnce("fishing", e);
		}
	}

	/**
	 * After bone meal: mark the paid blocks set during it. Crops with a ripeness check count as boosted ({@link
	 * BoostedCrops}), everything else (logs from saplings, pumpkin, melon, mushroom stems) as placed in {@link
	 * PlacedBlockTracker}. Natural growth from random ticks still pays.
	 */
	public static void onBonemealGrowth(ServerLevel level, List<BlockPos> changed) {
		if (changed.isEmpty()) return;
		try {
			JobsConfig.Compiled cfg = JobsConfig.get();
			for (BlockPos pos : changed) {
				BlockState state = level.getBlockState(pos);
				if (state.isAir()) continue;
				int kind = cfg.growthKind(state);
				if ((kind & 1) != 0) BoostedCrops.add(level, pos);
				if ((kind & 2) != 0) PlacedBlockTracker.markPlaced(level, pos);
			}
		} catch (RuntimeException e) {
			logOnce("bone meal", e);
		}
	}

	static void clear() {
		PENDING_HARVEST.clear();
	}

	private static void logOnce(String what, RuntimeException e) {
		if (errorLogged) {
			SmpEconomy.LOG.warn("[SMPEco] jobs: error in {}: {}", what, e.toString());
			return;
		}
		errorLogged = true;
		SmpEconomy.LOG.error("[SMPEco] jobs: error in {}", what, e);
	}

	static Set<String> tagsOf(Entity e) {
		return Set.copyOf(e.getTags());
	}
}
