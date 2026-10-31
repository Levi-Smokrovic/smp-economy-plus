package dev.smpeconomy.features.minigames.treasure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Util;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.CrateKeys;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.PlacedBlockTracker;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.TimeUtil;
import dev.smpeconomy.core.Treasury;
import dev.smpeconomy.features.minigames.MinigamesConfig;
import dev.smpeconomy.features.minigames.TreasuryRewards;

/**
 * Hides a chest at a safe surface spot in the pregenerated ring around spawn. Staggered hints; the first click
 * wins. The reward is fixed when hiding and granted once. The chest is empty, indestructible, and removed
 * on timeout, server stop and next start after a crash.
 * <p>Server thread only, except reading chunk data from disk asynchronously, whose result returns via {@code
 * server.execute}.
 */
public final class TreasureHunt {
	public static final String C_FOUND = "minigames.treasures_found";
	private static final String[] DIRECTIONS = {"north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest"};

	public interface Announcer {
		void broadcast(Component message);

		void send(UUID player, Component message);

		void startTitle();

		void proximity(TreasureHunt hunt, Active active);
	}

	public static final class RewardItem {
		public String item;
		public int count;

		public RewardItem() {}

		public RewardItem(String item, int count) {
			this.item = item;
			this.count = count;
		}
	}

	/** Persisted so a restart cleans up the chest. */
	public static final class Active {
		public String id = "";
		public String dimension = "minecraft:overworld";
		public int x, y, z;
		public int spawnX, spawnZ;
		public long startedAt, endsAt;
		public long money;
		public List<RewardItem> items = new ArrayList<>();
		public int commonKeys, rareKeys;
		public String biome = "";
		public int hintX, hintZ;
		/** Fixed when hiding; the amount is then capped to the hourly budget. */
		public boolean fromTreasury;
		/** 0 = start, 1 = distance, 2 = coordinates. */
		public int stage;

		public BlockPos pos() {
			return new BlockPos(x, y, z);
		}
	}

	public static final class Data {
		public Active active;
		public long nextAt = -1;
		public String lastWinner = "";
		public long lastFoundAt;

		public void fixNulls() {
			if (active != null) {
				if (active.items == null) active.items = new ArrayList<>();
				active.items.removeIf(r -> r == null || r.item == null || r.count <= 0);
				if (active.dimension == null) active.dimension = "minecraft:overworld";
				if (active.biome == null) active.biome = "";
				if (active.id == null) active.id = "";
			}
			if (lastWinner == null) lastWinner = "";
		}
	}

	public record Claim(Active treasure, long money, long pendingMoney, String payError, List<ItemStack> items, int commonKeys, int rareKeys, int dropped) {}

	private final Supplier<Data> data;
	private final Runnable markDirty;
	private final Supplier<MinigamesConfig.Treasure> config;
	private final LongSupplier clock;
	private final Random random;
	private final Announcer announcer;
	private boolean searching;
	private long searchStartedAt;
	private int searchToken;

	public TreasureHunt(Supplier<Data> data, Runnable markDirty, Supplier<MinigamesConfig.Treasure> config, LongSupplier clock, Random random, Announcer announcer) {
		this.data = data;
		this.markDirty = markDirty;
		this.config = config;
		this.clock = clock;
		this.random = random;
		this.announcer = announcer;
	}

	public Optional<Active> active() {
		return Optional.ofNullable(data.get().active);
	}

	public boolean isSearching() {
		return searching;
	}

	public long nextAt() {
		return data.get().nextAt;
	}

	public boolean isTreasure(Level level, BlockPos pos) {
		Active a = data.get().active;
		return a != null && a.x == pos.getX() && a.y == pos.getY() && a.z == pos.getZ()
			&& level.dimension().identifier().toString().equals(a.dimension);
	}

	/**
	 * Like vanilla: block reach + 1 from eye height, plus {@code mayInteract} (spawn protection, world border).
	 * Needed because Fabric's {@code AttackBlockCallback} (left click) runs before vanilla's reach check; otherwise
	 * a modified client could click the chest from any distance.
	 */
	public static boolean canReach(ServerPlayer player, Level level, BlockPos pos) {
		return player.level() == level && player.isWithinBlockInteractionRange(pos, 1.0) && level.mayInteract(player, pos);
	}

	/**
	 * Hides the chest at exactly {@code pos}, which must be free or replaceable and without liquid. A running hunt
	 * ends and is cleaned up first. Returns null if the spot is occupied.
	 */
	public Active place(ServerLevel level, BlockPos pos, boolean announce) {
		BlockState at = level.getBlockState(pos);
		if (!(at.isAir() || at.canBeReplaced()) || !at.getFluidState().isEmpty()) return null;
		if (data.get().active != null) cleanup(level.getServer(), "§7The previous treasure hunt has ended.");
		MinigamesConfig.Treasure cfg = config.get();
		BlockPos spawn = spawnOf(level);
		Active a = new Active();
		a.id = Long.toString(clock.getAsLong(), 36);
		a.dimension = level.dimension().identifier().toString();
		a.x = pos.getX();
		a.y = pos.getY();
		a.z = pos.getZ();
		a.spawnX = spawn.getX();
		a.spawnZ = spawn.getZ();
		a.startedAt = clock.getAsLong();
		a.endsAt = a.startedAt + cfg.durationMinutes * 60_000L;
		rollRewards(a, cfg);
		a.fromTreasury = cfg.rewardsFromTreasury;
		if (a.fromTreasury) {
			// the treasury pays capped: never promise more than its hourly budget, or the payout could never
			// succeed
			long budget = Treasury.maxSpendPerHour();
			long capped = capMoney(a.money, budget);
			if (capped < a.money) {
				SmpEconomy.LOG.info("[SMPEco] Treasure hunt: money reward {} capped to the treasury's hourly budget ({})", Money.format(a.money), Money.format(capped));
				a.money = capped;
			}
		}
		a.biome = level.getBiome(pos).unwrapKey().map(k -> k.identifier().toString()).orElse("");
		int fuzz = Math.max(0, cfg.coordinateFuzz);
		a.hintX = a.x + (fuzz > 0 ? random.nextInt(2 * fuzz + 1) - fuzz : 0);
		a.hintZ = a.z + (fuzz > 0 ? random.nextInt(2 * fuzz + 1) - fuzz : 0);
		a.stage = 0;
		Direction facing = Direction.from2DDataValue(random.nextInt(4));
		level.setBlock(pos, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing), 3);
		data.get().active = a;
		markDirty.run();
		SmpEconomy.LOG.info("[SMPEco] Treasure hunt: chest at {} {} {} ({}), reward {}", a.x, a.y, a.z, a.dimension, Money.format(a.money));
		if (announce) {
			announcer.startTitle();
			announcer.broadcast(Msg.prefixed("§6§lTREASURE HUNT §8» §7A treasure has been hidden! The first to open the chest wins. §8("
				+ cfg.durationMinutes + " min, §e/treasure§8)"));
			announcer.broadcast(Msg.prefixed(hintLine(a, 0)));
		}
		return a;
	}

	/** Fixed before hiding so the find grants exactly this reward. */
	public void rollRewards(Active a, MinigamesConfig.Treasure cfg) {
		long span = cfg.maxMoney - cfg.minMoney;
		long money = cfg.minMoney + (span > 0 ? (long) (random.nextDouble() * (span + 1)) : 0);
		if (money >= 1000) money = money / 1000 * 1000;
		a.money = Math.max(cfg.minMoney, Math.min(money, cfg.maxMoney));
		a.items = new ArrayList<>();
		List<MinigamesConfig.Loot> pool = new ArrayList<>();
		int total = 0;
		for (MinigamesConfig.Loot l : cfg.loot) {
			if (l != null && l.weight > 0 && Items.byId(l.item).isPresent()) {
				pool.add(l);
				total += l.weight;
			}
		}
		for (int i = 0; i < cfg.itemRolls && total > 0; i++) {
			int roll = random.nextInt(total);
			for (MinigamesConfig.Loot l : pool) {
				roll -= l.weight;
				if (roll < 0) {
					int count = l.min + (l.max > l.min ? random.nextInt(l.max - l.min + 1) : 0);
					mergeReward(a.items, l.item, count);
					break;
				}
			}
		}
		a.commonKeys = random.nextDouble() < cfg.commonKeyChance ? 1 : 0;
		a.rareKeys = random.nextDouble() < cfg.rareKeyChance ? 1 : 0;
	}

	public static long capMoney(long money, long budget) {
		long m = Math.max(0, Math.min(money, Math.max(0, budget)));
		return m >= 1000 ? m / 1000 * 1000 : m;
	}

	private static void mergeReward(List<RewardItem> items, String id, int count) {
		for (RewardItem r : items) {
			if (r.item.equals(id)) {
				r.count += count;
				return;
			}
		}
		items.add(new RewardItem(id, count));
	}

	/**
	 * Ends the hunt immediately, removes the chest, grants the reward. Empty if no hunt is active,
	 * e.g. on a second click.
	 */
	public Optional<Claim> claim(ServerPlayer player) {
		Active a = data.get().active;
		if (a == null) return Optional.empty();
		// end and save first: any further click, even same tick or other hand, finds nothing
		data.get().active = null;
		data.get().lastWinner = player.getGameProfile().name();
		data.get().lastFoundAt = clock.getAsLong();
		markDirty.run();
		try {
			removeChest(player.level().getServer(), a);
		} catch (RuntimeException e) {
			SmpEconomy.LOG.error("[SMPEco] Could not remove treasure chest at {} {} {}", a.x, a.y, a.z, e);
		}

		UUID id = player.getUUID();
		String payError = null;
		long paid = 0;
		long pending = 0;
		if (a.money > 0) {
			if (a.fromTreasury) {
				// capped from the treasury; what can't be paid now is paid later (the chest is gone, nothing may be
				// lost)
				TreasuryRewards.Payout p = TreasuryRewards.pay(id, a.money);
				paid = p.paid();
				pending = p.pending();
			} else {
				EconomyService.Result r = EconomyService.deposit(id, a.money);
				if (r.ok()) paid = a.money;
				else payError = r.message();
			}
		}
		List<ItemStack> given = new ArrayList<>();
		int dropped = 0;
		for (RewardItem r : a.items) {
			Optional<Item> item = Items.byId(r.item);
			if (item.isEmpty() || r.count <= 0) {
				SmpEconomy.LOG.warn("[SMPEco] Treasure reward {} x{} unknown, skipped", r.item, r.count);
				continue;
			}
			ItemStack stack = new ItemStack(item.get(), r.count);
			dropped += Items.giveOrDrop(player, stack);
			given.add(stack);
		}
		// keys need the crates module; otherwise neither credit nor show them
		int commonKeys = CrateKeys.enabled() ? a.commonKeys : 0;
		int rareKeys = CrateKeys.enabled() ? a.rareKeys : 0;
		if (commonKeys > 0) CrateKeys.give(id, CrateKeys.COMMON, commonKeys);
		if (rareKeys > 0) CrateKeys.give(id, CrateKeys.RARE, rareKeys);
		PlayerDataService.addCounter(id, C_FOUND, 1);
		if (!given.isEmpty()) DataStore.requestPlayerSave(player);

		long seconds = Math.max(0, (clock.getAsLong() - a.startedAt) / 1000);
		announcer.broadcast(Msg.prefixed("§6§lTREASURE HUNT §8» §e" + player.getGameProfile().name() + " §7found the treasure §8(after "
			+ TimeUtil.formatDuration(seconds * 1000) + ")§7!"));
		if (payError != null) announcer.send(id, Msg.prefixed("§cThe money prize couldn't be paid out: " + payError));
		if (pending > 0) announcer.send(id, Msg.prefixed("§7The Treasury is out of budget right now – " + Msg.moneyStr(pending)
			+ " §7will be paid to you automatically once there's enough again (while you're online)."));
		if (dropped > 0) announcer.send(id, Msg.prefixed("§eInventory full – " + dropped + " items were dropped at your feet."));
		return Optional.of(new Claim(a, paid, pending, payError, given, commonKeys, rareKeys, dropped));
	}

	public void tick(MinecraftServer server, int onlinePlayers, long tickCount) {
		long now = clock.getAsLong();
		MinigamesConfig.Treasure cfg = config.get();
		Data d = data.get();
		Active a = d.active;
		if (a != null) {
			ServerLevel level = levelOf(server, a);
			if (level != null && level.isLoaded(a.pos()) && !level.getBlockState(a.pos()).is(Blocks.CHEST)) {
				d.active = null;
				markDirty.run();
				announcer.broadcast(Msg.prefixed("§6§lTREASURE HUNT §8» §7The treasure has vanished – the hunt is over."));
				SmpEconomy.LOG.warn("[SMPEco] Treasure chest at {} {} {} missing, hunt ended", a.x, a.y, a.z);
				return;
			}
			if (now >= a.endsAt) {
				cleanup(server, "§6§lTREASURE HUNT §8» §7Nobody found the treasure. It was at §f" + a.x + " " + a.y + " " + a.z + "§7.");
				return;
			}
			int stage = stageAt(a, now, cfg);
			while (a.stage < stage) {
				a.stage++;
				markDirty.run();
				announcer.broadcast(Msg.prefixed(hintLine(a, a.stage)));
			}
			if (tickCount % 2 == 0) announcer.proximity(this, a);
			return;
		}
		if (searching && now - searchStartedAt > 120_000L) {
			// read futures are stuck (shouldn't happen): release the search
			searching = false;
			searchToken++;
		}
		if (!cfg.enabled || searching) return;
		if (d.nextAt < 0) {
			scheduleNext(now);
			return;
		}
		if (now < d.nextAt) return;
		if (onlinePlayers < Math.max(1, cfg.minPlayers) || TimeUtil.isHourBetween(cfg.nightFromHour, cfg.nightToHour)) {
			// look again later, but not right at the next join
			d.nextAt = now + 10 * 60_000L;
			markDirty.run();
			return;
		}
		scheduleNext(now);
		startSearch(server, msg -> SmpEconomy.LOG.info("[SMPEco] Treasure hunt: {}", msg));
	}

	public void scheduleNext(long now) {
		MinigamesConfig.Treasure cfg = config.get();
		long min = cfg.minIntervalMinutes * 60_000L;
		long max = Math.max(min, cfg.maxIntervalMinutes * 60_000L);
		data.get().nextAt = now + min + (max > min ? (long) (random.nextDouble() * (max - min)) : 0);
		markDirty.run();
	}

	public static int stageAt(Active a, long now, MinigamesConfig.Treasure cfg) {
		long elapsed = now - a.startedAt;
		if (elapsed >= cfg.hint3AfterMinutes * 60_000L) return 2;
		if (elapsed >= cfg.hint2AfterMinutes * 60_000L) return 1;
		return 0;
	}

	public void cleanup(MinecraftServer server, String message) {
		Active a = data.get().active;
		if (a == null) return;
		// remove first, then forget: if removal fails, the entry stays for the next attempt at start
		removeChest(server, a);
		data.get().active = null;
		markDirty.run();
		if (message != null) announcer.broadcast(Msg.prefixed(message));
	}

	public void abortSearch() {
		searching = false;
		searchToken++;
	}

	/** Loads the chunk if needed. Anything someone put inside drops. */
	static void removeChest(MinecraftServer server, Active a) {
		if (server == null) return;
		ServerLevel level = levelOf(server, a);
		if (level == null) {
			SmpEconomy.LOG.warn("[SMPEco] Treasure chest: dimension {} missing, not removed", a.dimension);
			return;
		}
		BlockPos pos = a.pos();
		level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
		if (level.getBlockState(pos).is(Blocks.CHEST)) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
	}

	static ServerLevel levelOf(MinecraftServer server, Active a) {
		Identifier id = Identifier.tryParse(a.dimension);
		return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
	}

	/** Respawn data from another dimension falls back to 0/0. */
	public static BlockPos spawnOf(ServerLevel level) {
		var data = level.getRespawnData();
		return data.dimension().equals(level.dimension()) ? data.pos() : BlockPos.ZERO;
	}

	/** Biome as translatable text, hence a Component. */
	public Component hintLine(Active a, int stage) {
		int dx = a.x - a.spawnX;
		int dz = a.z - a.spawnZ;
		String dir = direction(dx, dz);
		MutableComponent line = Msg.legacy("§e§lHint " + (stage + 1) + " §8» §7");
		switch (stage) {
			case 0 -> line.append(Msg.legacy("§7The treasure is in the biome ")).append(biomeName(a.biome))
				.append(Msg.legacy("§7, " + fromSpawn(dir) + "."));
			case 1 -> {
				int[] range = distanceRange(Math.sqrt((double) dx * dx + (double) dz * dz));
				line.append(Msg.legacy("§7It is about §e" + range[0] + "–" + range[1] + " blocks §7" + fromSpawn(dir) + " §8(biome ")).append(biomeName(a.biome))
					.append(Msg.legacy("§8)§7."));
			}
			default -> line.append(Msg.legacy("§7Coordinates: §eX ≈ " + a.hintX + "§7, §eZ ≈ " + a.hintZ + " §8(±" + config.get().coordinateFuzz
				+ " blocks, on the surface)"));
		}
		return line;
	}

	public List<Component> status(Active a) {
		List<Component> lines = new ArrayList<>();
		long left = a.endsAt - clock.getAsLong();
		lines.add(Msg.prefixed("§6§lTREASURE HUNT §8» §f" + TimeUtil.formatDuration(left) + " §7left. Click the chest to open it."));
		for (int s = 0; s <= Math.min(2, a.stage); s++) lines.add(Msg.prefixed(hintLine(a, s)));
		return lines;
	}

	static String fromSpawn(String dir) {
		return "here".equals(dir) ? "right at spawn" : dir + " of spawn";
	}

	/** North is -Z, east is +X. */
	public static String direction(int dx, int dz) {
		if (dx == 0 && dz == 0) return "here";
		double angle = Math.toDegrees(Math.atan2(dx, -dz));
		if (angle < 0) angle += 360;
		int idx = (int) Math.round(angle / 45.0) % 8;
		return DIRECTIONS[idx];
	}

	public static int[] distanceRange(double dist) {
		int lo = (int) (Math.floor(dist / 100.0) * 100);
		return new int[] {lo, lo + 100};
	}

	public static MutableComponent biomeName(String biomeId) {
		Identifier id = biomeId == null ? null : Identifier.tryParse(biomeId);
		if (id == null) return Msg.legacy("§eunknown");
		return Component.translatable(Util.makeDescriptionId("biome", id)).withStyle(ChatFormatting.YELLOW);
	}

	/** Horizontal radius around the spot where nothing built may stand (checked from y-2 to y+3). */
	public static final int CLEAR_RADIUS = 3;

	/** Blocks mostly placed only by players, on top of {@link #BUILD_TAGS}, block entities and light sources. */
	private static final List<Block> BUILD_BLOCKS = List.of(Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE, Blocks.COBBLED_DEEPSLATE,
		Blocks.SMOOTH_STONE, Blocks.BRICKS, Blocks.MUD_BRICKS, Blocks.GLASS_PANE, Blocks.LADDER, Blocks.SCAFFOLDING, Blocks.FARMLAND,
		Blocks.DIRT_PATH, Blocks.HAY_BLOCK, Blocks.CRAFTING_TABLE, Blocks.REDSTONE_WIRE, Blocks.QUARTZ_BLOCK, Blocks.OBSIDIAN,
		Blocks.CUT_SANDSTONE, Blocks.CHISELED_SANDSTONE, Blocks.POLISHED_ANDESITE, Blocks.POLISHED_DIORITE, Blocks.POLISHED_GRANITE);
	/** Beds are listed explicitly: from MC 26.2 they no longer have a block entity. */
	private static final List<TagKey<Block>> BUILD_TAGS = List.of(BlockTags.PLANKS, BlockTags.STONE_BRICKS, BlockTags.STAIRS, BlockTags.SLABS,
		BlockTags.WALLS, BlockTags.FENCES, BlockTags.FENCE_GATES, BlockTags.DOORS, BlockTags.TRAPDOORS, BlockTags.WOOL, BlockTags.WOOL_CARPETS,
		BlockTags.IMPERMEABLE, BlockTags.BARS, BlockTags.CHAINS, BlockTags.LANTERNS, BlockTags.RAILS, BlockTags.BUTTONS,
		BlockTags.PRESSURE_PLATES, BlockTags.CANDLES, BlockTags.CONCRETE_POWDER, BlockTags.COPPER, BlockTags.BEDS);

	/**
	 * The tracker only knows blocks since the mod was installed. Older builds are caught by the natural ground rule
	 * and the surroundings check (roofs, floors, paths of building blocks; torches, fences, chests nearby).
	 */
	public static boolean isSafeSpot(ServerLevel level, BlockPos pos) {
		if (pos.getY() <= level.getMinY() + 1 || pos.getY() >= level.getMaxY() - 2) return false;
		if (!level.getWorldBorder().isWithinBounds(pos)) return false;
		BlockPos below = pos.below();
		BlockState ground = level.getBlockState(below);
		BlockState at = level.getBlockState(pos);
		BlockState above = level.getBlockState(pos.above());
		boolean sturdy = ground.isFaceSturdy(level, below, Direction.UP);
		return safeStates(ground, sturdy, at, above) && !hasBuildNearby(level, pos);
	}

	public static boolean safeStates(BlockState ground, boolean groundSturdy, BlockState at, BlockState above) {
		if (!groundSturdy || !ground.getFluidState().isEmpty() || !isNaturalGround(ground)) return false;
		if (!(at.isAir() || at.canBeReplaced()) || !at.getFluidState().isEmpty()) return false;
		return (above.isAir() || above.canBeReplaced()) && above.getFluidState().isEmpty();
	}

	/**
	 * Natural surface ground only. Everything else (bricks, planks, cobblestone, glass, dyed terracotta, powder
	 * snow, ice, leaves, magma) is rejected.
	 */
	public static boolean isNaturalGround(BlockState ground) {
		if (ground.hasBlockEntity()) return false; // e.g. suspicious sand in ruins
		return ground.is(BlockTags.DIRT) || ground.is(BlockTags.SAND) || ground.is(BlockTags.BASE_STONE_OVERWORLD)
			|| ground.is(BlockTags.BADLANDS_TERRACOTTA) || ground.is(Blocks.GRAVEL) || ground.is(Blocks.CLAY) || ground.is(Blocks.SNOW_BLOCK)
			|| ground.is(Blocks.CALCITE) || ground.is(Blocks.SANDSTONE) || ground.is(Blocks.RED_SANDSTONE);
	}

	/**
	 * Block entities, light sources from level 10 (torches, lanterns, glowstone, and lava or fire, which would be
	 * dangerous anyway) and typical building blocks. Natural blocks (grass, flowers, logs, leaves, stone) don't
	 * count.
	 */
	public static boolean looksBuilt(BlockState s) {
		if (s.isAir()) return false;
		if (s.hasBlockEntity() || s.getLightEmission() >= 10) return true;
		for (TagKey<Block> tag : BUILD_TAGS) {
			if (s.is(tag)) return true;
		}
		for (Block b : BUILD_BLOCKS) {
			if (s.is(b)) return true;
		}
		return false;
	}

	public static boolean hasBuildNearby(ServerLevel level, BlockPos pos) {
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int dx = -CLEAR_RADIUS; dx <= CLEAR_RADIUS; dx++) {
			for (int dz = -CLEAR_RADIUS; dz <= CLEAR_RADIUS; dz++) {
				for (int dy = -2; dy <= 3; dy++) {
					m.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
					BlockState s = level.getBlockState(m);
					if (s.isAir()) continue;
					if (looksBuilt(s) || PlacedBlockTracker.isPlayerPlaced(level, m)) return true;
				}
			}
		}
		return false;
	}

	/**
	 * Only chunks already fully generated with their neighbours (status FULL on disk or loaded); new terrain is
	 * never generated. {@code feedback} gets the result on the server thread.
	 */
	public boolean startSearch(MinecraftServer server, Consumer<String> feedback) {
		if (searching) {
			feedback.accept("Already looking for a hiding spot.");
			return false;
		}
		if (data.get().active != null) {
			feedback.accept("A treasure hunt is already running.");
			return false;
		}
		ServerLevel level = server.overworld();
		searching = true;
		searchStartedAt = clock.getAsLong();
		int token = ++searchToken;
		tryCandidate(server, level, spawnOf(level), 0, token, feedback);
		return true;
	}

	private void tryCandidate(MinecraftServer server, ServerLevel level, BlockPos spawn, int attempt, int token, Consumer<String> feedback) {
		if (token != searchToken || SmpEconomy.isStopping()) return;
		if (data.get().active != null) { // placed by hand in the meantime (/treasure place): never replace
			searching = false;
			return;
		}
		MinigamesConfig.Treasure cfg = config.get();
		if (attempt >= cfg.searchAttempts) {
			searching = false;
			data.get().nextAt = clock.getAsLong() + 15 * 60_000L;
			markDirty.run();
			SmpEconomy.LOG.info("[SMPEco] Treasure hunt: no safe spot found in pregenerated terrain ({} attempts), retrying in 15 min", attempt);
			feedback.accept("No safe spot found in the pregenerated area (" + attempt + " attempts).");
			return;
		}
		double angle = random.nextDouble() * Math.PI * 2;
		double r1 = cfg.minRadius, r2 = cfg.maxRadius;
		double r = Math.sqrt(random.nextDouble() * (r2 * r2 - r1 * r1) + r1 * r1);
		int x = spawn.getX() + (int) Math.round(Math.cos(angle) * r);
		int z = spawn.getZ() + (int) Math.round(Math.sin(angle) * r);
		if (!level.getWorldBorder().isWithinBounds(new BlockPos(x, level.getSeaLevel(), z))) {
			dev.smpeconomy.core.Scheduler.runLater(1, () -> tryCandidate(server, level, spawn, attempt + 1, token, feedback));
			return;
		}
		int cx = x >> 4, cz = z >> 4;
		List<CompletableFuture<Boolean>> checks = new ArrayList<>();
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) checks.add(isGenerated(level, cx + dx, cz + dz));
		}
		CompletableFuture.allOf(checks.toArray(CompletableFuture[]::new)).orTimeout(15, TimeUnit.SECONDS).whenComplete((v, err) -> {
			boolean ok = err == null && checks.stream().allMatch(f -> f.getNow(false));
			server.execute(() -> {
				if (token != searchToken || SmpEconomy.isStopping()) return;
				if (data.get().active != null) {
					searching = false;
					return;
				}
				BlockPos spot = null;
				if (ok) {
					try {
						spot = findSpot(level, x, z);
					} catch (RuntimeException e) {
						SmpEconomy.LOG.error("[SMPEco] Treasure hunt: location check at {} {} failed", x, z, e);
					}
				}
				if (spot != null && place(level, spot, true) != null) {
					searching = false;
					feedback.accept("Treasure hidden at " + spot.getX() + " " + spot.getY() + " " + spot.getZ() + " (attempt " + (attempt + 1) + ").");
					return;
				}
				dev.smpeconomy.core.Scheduler.runLater(2, () -> tryCandidate(server, level, spawn, attempt + 1, token, feedback));
			});
		});
	}

	/**
	 * Loaded means yes; a missing region file means no, without reading, because reading through the ChunkMap would
	 * create empty region files. Otherwise reads the status from chunk data on disk, asynchronously on the IO
	 * thread.
	 */
	private static CompletableFuture<Boolean> isGenerated(ServerLevel level, int cx, int cz) {
		if (level.getChunkSource().getChunkNow(cx, cz) != null) return CompletableFuture.completedFuture(true);
		try {
			ChunkPos chunk = new ChunkPos(cx, cz);
			Path region = DimensionType.getStorageFolder(level.dimension(), level.getServer().getWorldPath(LevelResource.ROOT).normalize())
				.resolve("region").resolve("r." + chunk.getRegionX() + "." + chunk.getRegionZ() + ".mca");
			if (!Files.isRegularFile(region) || Files.size(region) <= 8192) return CompletableFuture.completedFuture(false);
		} catch (IOException | RuntimeException e) {
			return CompletableFuture.completedFuture(false);
		}
		try {
			return level.getChunkSource().chunkMap.read(new ChunkPos(cx, cz))
				.thenApply(tag -> tag.map(t -> SerializableChunkData.getChunkStatusFromTag(t) == ChunkStatus.FULL).orElse(false))
				.exceptionally(t -> false);
		} catch (RuntimeException e) {
			return CompletableFuture.completedFuture(false);
		}
	}

	/**
	 * Loads the finished chunk and tries the target column plus some random columns in the same chunk. Columns stay
	 * {@link #CLEAR_RADIUS} blocks from the chunk edge so the surroundings check doesn't load neighbours
	 * synchronously.
	 */
	private BlockPos findSpot(ServerLevel level, int x, int z) {
		LevelChunk chunk = level.getChunk(x >> 4, z >> 4);
		int span = 16 - 2 * CLEAR_RADIUS;
		for (int i = 0; i < 10; i++) {
			int bx = (x & ~15) + (i == 0 ? Math.max(CLEAR_RADIUS, Math.min(15 - CLEAR_RADIUS, x & 15)) : CLEAR_RADIUS + random.nextInt(span));
			int bz = (z & ~15) + (i == 0 ? Math.max(CLEAR_RADIUS, Math.min(15 - CLEAR_RADIUS, z & 15)) : CLEAR_RADIUS + random.nextInt(span));
			int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx & 15, bz & 15) + 1;
			BlockPos pos = new BlockPos(bx, y, bz);
			if (isSafeSpot(level, pos)) return pos;
		}
		return null;
	}
}
