package dev.smpeconomy.features.missions;

import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.raid.Raid;
import dev.smpeconomy.features.missions.MissionStore.RaidTrack;

/**
 * Raids for the Daily Elite (server thread only). Vanilla makes every player who killed any raider a Hero of the
 * Village, wherever they are at the win, and the level can stack up to V mid-raid with Bad Omen. So the Daily Elite
 * tracks each raid itself:
 * <ul>
 *   <li>the level when the first wave spawns (mixin on {@code Raid#spawnGroup}); scored as {@code min(start level,
 *   level at win)}, an unknown start doesn't count;</li>
 *   <li>self-killed natural raiders per player (mixin on {@code Raider#die}; projectiles count, spawn eggs etc. don't);
 *   only players with at least {@code minRaidKills} and {@code minRaidKillShare} of all player kills count.</li>
 * </ul>
 * Key is {@code dimension#raidId} (vanilla ID from {@code Raids}, saved with the world). Entries live in {@code
 * missions_state.json} so a restart mid-raid loses nothing, and get cleaned up shortly after the win or after {@link
 * #STALE_MS} without change (once a minute).
 */
final class EliteRaids {
	/** Kept this long after the win; all heroes are processed in the same tick. */
	static final long FINISHED_KEEP_MS = 60_000L;
	/**
	 * Kept this long without change: raids in unloaded chunks rest, and the vanilla time limit is 40 min of active
	 * time.
	 */
	static final long STALE_MS = 12 * 3_600_000L;
	/** Raid farms; the oldest go first. */
	static final int MAX_TRACKED = 512;

	private EliteRaids() {}

	record Outcome(int startLevel, int endLevel, int kills, int totalKills, boolean inArea) {
		/**
		 * The scored level is the one at the start, Bad Omen mid-raid doesn't raise it. 0 means the start wasn't
		 * recorded.
		 */
		int level() {
			return startLevel <= 0 ? 0 : Math.min(startLevel, endLevel);
		}
	}

	/** {@code dimension#raidId}, or null if the raid isn't in the level's raid list. */
	static String key(ServerLevel level, Raid raid) {
		if (level == null || raid == null) return null;
		OptionalInt id = level.getRaids().getId(raid);
		return id.isPresent() ? key(level.dimension().identifier().toString(), id.getAsInt()) : null;
	}

	static String key(String dimension, int raidId) {
		return dimension + "#" + raidId;
	}

	/** Null if the dimension is unknown or the raid ended and got removed. For admin and e2e. */
	static Raid vanilla(MinecraftServer server, String key) {
		int hash = key == null ? -1 : key.lastIndexOf('#');
		if (server == null || hash <= 0) return null;
		Identifier dim = Identifier.tryParse(key.substring(0, hash));
		if (dim == null) return null;
		ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dim));
		if (level == null) return null;
		try {
			return level.getRaids().get(Integer.parseInt(key.substring(hash + 1)));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/**
	 * The first wave is about to spawn. New entry with the start level; an old entry under the same ID gets replaced.
	 */
	static void firstWave(String key, int level, long now) {
		if (key == null) return;
		RaidTrack t = new RaidTrack();
		t.startLevel = Math.max(0, Math.min(EliteConfig.MAX_RAID_LEVEL, level));
		t.updatedAt = now;
		MissionStore.raids().put(key, t);
		MissionStore.markDirty();
	}

	/** Origin already checked. Without an entry (start not recorded) this does nothing. */
	static void kill(String key, UUID player, long now) {
		if (key == null || player == null) return;
		RaidTrack t = MissionStore.raids().get(key);
		if (t == null || t.finishedAt != 0) return;
		t.kills.merge(player, 1, Integer::sum);
		t.totalKills++;
		t.updatedAt = now;
		MissionStore.markDirty();
	}

	static RaidTrack get(String key) {
		return key == null ? null : MissionStore.raids().get(key);
	}

	/** Win processed: the entry stays {@link #FINISHED_KEEP_MS} for the other heroes of the same tick. */
	static void finish(String key, long now) {
		RaidTrack t = get(key);
		if (t != null && t.finishedAt == 0) {
			t.finishedAt = now;
			MissionStore.markDirty();
		}
	}

	/** Once a minute from {@link EliteService#tick}, real time. */
	static void prune(long now) {
		if (prune(MissionStore.raids(), now)) MissionStore.markDirty();
	}

	/** As above on any map (selftests); keeps at most {@link #MAX_TRACKED}. True if anything was removed. */
	static boolean prune(Map<String, RaidTrack> raids, long now) {
		if (raids.isEmpty()) return false;
		boolean changed = raids.values().removeIf(t -> (t.finishedAt != 0 && now - t.finishedAt > FINISHED_KEEP_MS)
			|| now - t.updatedAt > STALE_MS || t.updatedAt - now > STALE_MS);
		while (raids.size() > MAX_TRACKED) {
			String oldest = null;
			long at = Long.MAX_VALUE;
			for (Map.Entry<String, RaidTrack> e : raids.entrySet()) {
				if (e.getValue().updatedAt < at) {
					at = e.getValue().updatedAt;
					oldest = e.getKey();
				}
			}
			raids.remove(oldest);
			changed = true;
		}
		return changed;
	}

	/** {@code max(minRaidKills, ⌈minRaidKillShare × player kills⌉)}. */
	static int requiredKills(EliteConfig.EliteDef d, int totalKills) {
		int min = Math.max(1, d.minRaidKills);
		double share = Math.max(0, Math.min(1, d.minRaidKillShare));
		long byShare = (long) Math.ceil(share * Math.max(0, totalKills) - 1e-9);
		return (int) Math.max(min, Math.min(Integer.MAX_VALUE, byShare));
	}
}
