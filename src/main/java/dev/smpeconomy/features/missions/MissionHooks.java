package dev.smpeconomy.features.missions;

import java.util.Collection;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.ItemStack;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.Bots;
import dev.smpeconomy.core.test.TestPlayers;

/** Entry points for this feature's mixins; public because the mixins live in another package. */
public final class MissionHooks {
	private MissionHooks() {}

	/** Errors must never disturb fishing. */
	public static void onFishingLoot(ServerPlayer player, Collection<ItemStack> loot) {
		if (player == null || Bots.isBot(player) || loot == null || loot.isEmpty()) return;
		try {
			MissionService.onFishingLoot(player, loot);
		} catch (RuntimeException e) {
			SmpEconomy.LOG.error("[SMPEco] missions: could not count fishing catch", e);
		}
	}

	/**
	 * {@code ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY}, server thread; fires exactly where vanilla awards
	 * {@code ENTITY_KILLED}. Real players only: FakePlayers from other mods can carry their owner's UUID. Selftest
	 * players are the exception. Errors must never disturb the death.
	 */
	public static void onKill(Entity killer, LivingEntity killed) {
		if (!(killer instanceof ServerPlayer player) || killed == null) return;
		if (Bots.isBot(player) && !TestPlayers.isTestPlayer(player.getUUID())) return;
		try {
			MissionService.onMobKilled(player, killed);
		} catch (RuntimeException e) {
			SmpEconomy.LOG.error("[SMPEco] missions: could not count kill", e);
		}
		try {
			EliteService.onMobKilled(player, killed, System.currentTimeMillis());
		} catch (RuntimeException e) {
			SmpEconomy.LOG.error("[SMPEco] missions: could not count kill for the Daily Elite", e);
		}
	}

	/**
	 * First wave of a raid is about to spawn (mixin on {@code Raid#spawnGroup}): remember the start level for the Daily
	 * Elite.
	 */
	public static void onRaidFirstWave(ServerLevel level, Raid raid) {
		if (level == null || raid == null) return;
		try {
			if (!EliteConfig.enabled()) return;
			EliteRaids.firstWave(EliteRaids.key(level, raid), raid.getRaidOmenLevel(), System.currentTimeMillis());
		} catch (RuntimeException e) {
			SmpEconomy.LOG.error("[SMPEco] missions: could not record raid start", e);
		}
	}

	/**
	 * A raider dies (mixin on {@code Raider#die}, still in the raid): counts a real player's kill (projectiles too, not
	 * creative or spectator) of a natural raider toward the Daily Elite contribution.
	 */
	public static void onRaiderDied(Raider raider, DamageSource source) {
		if (raider == null || source == null || !(raider.level() instanceof ServerLevel level)) return;
		Raid raid = raider.getCurrentRaid();
		if (raid == null || !(source.getEntity() instanceof ServerPlayer player)) return;
		if (Bots.isBot(player) && !TestPlayers.isTestPlayer(player.getUUID())) return;
		if (player.isCreative() || player.isSpectator()) return;
		try {
			if (!EliteConfig.enabled() || !EliteService.naturalKill(raider.getTags())) return;
			EliteRaids.kill(EliteRaids.key(level, raid), player.getUUID(), System.currentTimeMillis());
		} catch (RuntimeException e) {
			SmpEconomy.LOG.error("[SMPEco] missions: could not count raider kill", e);
		}
	}

	/**
	 * Raid won ({@code Raid#tick} is awarding {@code RAID_WIN} to this Hero of the Village, mixin). Real players or
	 * test players only. Errors must never disturb the raid.
	 */
	public static void onRaidWon(ServerPlayer player, ServerLevel level, Raid raid) {
		if (player == null || raid == null || (Bots.isBot(player) && !TestPlayers.isTestPlayer(player.getUUID()))) return;
		try {
			long now = System.currentTimeMillis();
			String key = EliteRaids.key(level, raid);
			MissionStore.RaidTrack track = EliteRaids.get(key);
			EliteRaids.finish(key, now);
			boolean inArea = player.level() == level && raid.getCenter().distSqr(player.blockPosition()) <= Raid.VALID_RAID_RADIUS_SQR;
			EliteService.onRaidWon(player, new EliteRaids.Outcome(track == null ? 0 : track.startLevel, raid.getRaidOmenLevel(),
				track == null ? 0 : track.killsOf(player.getUUID()), track == null ? 0 : track.totalKills, inArea), now);
		} catch (RuntimeException e) {
			SmpEconomy.LOG.error("[SMPEco] missions: could not count raid win", e);
		}
	}
}
