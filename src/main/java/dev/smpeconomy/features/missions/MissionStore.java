package dev.smpeconomy.features.missions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.DataStore;

/** Mission state per player in {@code <world>/smpeconomy/missions_state.json}. Server thread only. */
final class MissionStore {
	static final class Data {
		Map<UUID, PlayerState> players = new HashMap<>();
		/** The same for all players, pinned: a reload doesn't change the running day. */
		EliteDay elite = new EliteDay();
		/**
		 * Running raids ({@link EliteRaids#key}: {@code dimension#raidId}) with start level and raider kills per
		 * player. Persisted so a restart mid-raid loses nothing; {@link EliteRaids#prune} cleans up ended and old
		 * entries.
		 */
		Map<String, RaidTrack> raids = new HashMap<>();
	}

	static final class RaidTrack {
		/** {@code raidOmenLevel} when the first wave spawned; Bad Omen mid-raid doesn't change it. */
		int startLevel;
		/** Self-killed natural raiders of this raid per player; projectiles count. */
		Map<UUID, Integer> kills = new HashMap<>();
		/** Denominator of the minimum share. */
		int totalKills;
		/** Epoch ms. */
		long updatedAt;
		/** Epoch ms, 0 while running; cleaned up shortly after. */
		long finishedAt;

		int killsOf(UUID player) {
			Integer k = kills == null ? null : kills.get(player);
			return k == null ? 0 : Math.max(0, k);
		}
	}

	/**
	 * Pinned Daily Elite: day (epochDay, server time zone) + snapshot of the definition. Picked deterministically on
	 * the first access of a new day ({@link EliteConfig#pick}) or replaced by an admin ({@code /smpeco missions elite
	 * set}).
	 */
	static final class EliteDay {
		long day = Long.MIN_VALUE;
		EliteConfig.EliteDef def;
		/** Set by an admin for today instead of the deterministic pick. */
		boolean override;

		transient EliteConfig.Elite compiled;
		transient boolean compileFailed;

		/** Null if e.g. the item was removed by an update. */
		EliteConfig.Elite elite() {
			if (def == null) return null;
			if (compiled == null && !compileFailed) {
				try {
					compiled = EliteConfig.compileDef(def.copy());
				} catch (RuntimeException e) {
					compileFailed = true;
					SmpEconomy.LOG.error("[SMPEco] missions: Daily Elite '{}' is invalid: {}", def.id, e.getMessage());
				}
			}
			return compiled;
		}

		void set(long day, EliteConfig.Elite elite, boolean override) {
			this.day = day;
			this.def = elite.def().copy();
			this.override = override;
			this.compiled = null;
			this.compileFailed = false;
		}
	}

	/** Valid only for {@link #day}; a new day starts at 0. */
	static final class EliteProgress {
		/** EpochDay, server time zone. */
		long day = Long.MIN_VALUE;
		/** An admin change on the same day means progress 0; {@link #done} stays. */
		String missionId;
		/** Hunt: counted natural kills in thousandths; spawner, bred, spawn egg, summoned and respawned count 0. */
		long killMilli;
		/** Won raids or handed-in items (delivery: all at once). */
		long progress;
		/** Done and paid today. */
		boolean done;
		long doneAt;
		/** Cents. */
		long paid;
		/** Midnight or first join. */
		long announcedDay = Long.MIN_VALUE;
	}

	static final class PlayerState {
		long period = Long.MIN_VALUE;
		/** Mission IDs accepted this rotation, also if abandoned again; each only once per rotation. */
		List<String> taken = new ArrayList<>();
		List<ActiveMission> active = new ArrayList<>();
		/** After abandoning: no new mission before this time (epoch ms). */
		long cooldownUntil;
		long completed;
		/** Rewards + deliveries, statistics only. */
		long earnedCents;
		long nextInstance = 1;
		/**
		 * Explored targets ({@link Mission#exploreKey} to epoch ms): each pays only once per player and stops being
		 * offered from the next round, otherwise /home, /tpa or a portal could cash in at will.
		 */
		Map<String, Long> explored = new HashMap<>();
		/** Paid structure instances ({@code structure@dimension:chunkX,chunkZ}), across different targets too. */
		List<String> exploredStructures = new ArrayList<>();
		EliteProgress elite = new EliteProgress();
		long eliteCompleted;
		long eliteEarned;

		void fixNulls() {
			if (taken == null) taken = new ArrayList<>();
			if (active == null) active = new ArrayList<>();
			if (explored == null) explored = new HashMap<>();
			explored.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null);
			if (exploredStructures == null) exploredStructures = new ArrayList<>();
			exploredStructures.removeIf(x -> x == null);
			if (elite == null) elite = new EliteProgress();
			active.removeIf(a -> a == null || a.def == null || a.missionId == null);
			if (nextInstance < 1) nextInstance = 1;
			for (ActiveMission a : active) {
				if (a.instance >= nextInstance) nextInstance = a.instance + 1;
			}
		}

		ActiveMission find(long instance) {
			for (ActiveMission a : active) {
				if (a.instance == instance) return a;
			}
			return null;
		}

		boolean hasActive(String missionId) {
			for (ActiveMission a : active) {
				if (a.missionId.equals(missionId)) return true;
			}
			return false;
		}
	}

	static final class ActiveMission {
		long instance;
		String missionId;
		MissionConfig.MissionDef def;
		long acceptedAt;
		long expiresAt;
		/** In cents; deliveries 0 since they pay per piece. */
		long reward;
		String keyTier = "";
		int keys;
		/**
		 * STAT: sum of the stats WITHOUT mining and hunting stats at accepting ({@link Mission#statSum}). For old saves
		 * see {@link #minedEvents} and {@link #killEvents}.
		 */
		long baseline;
		/**
		 * STAT: individually mined natural matching blocks since accepting, only from {@code
		 * PlayerBlockBreakEvents.AFTER}, never from {@code BLOCK_MINED}, which Veinminer raises for whole chains.
		 */
		long mined;
		/**
		 * STAT: true means {@link #baseline} has no mining stats and mining counts through {@link #mined}. False means
		 * accepted before this rule (baseline with mining stat); {@link MissionService#migrateMined} converts on first
		 * contact and existing progress stays.
		 */
		boolean minedEvents;
		/**
		 * STAT: counted kills of matching mobs since accepting, in thousandths, only from {@link
		 * MissionService#onMobKilled}, never from {@code ENTITY_KILLED}: natural 1000, spawner × {@code
		 * spawnerKillFactor}, bred, summoned, spawn egg and respawned 0.
		 */
		long killMilli;
		/**
		 * STAT: true means {@link #baseline} has no hunting stats and hunting counts through {@link #killMilli}. False
		 * means accepted before this rule (baseline with hunting stat); {@link MissionService#migrateKills} converts on
		 * first contact and existing progress stays.
		 */
		boolean killEvents;
		/** Delivered items, stat difference, treasures, or 1 when the target is reached. */
		long progress;
		/** DELIVER: paid so far, in cents. */
		long paid;
		/** EXPLORE: the player was NOT at the target at least once since accepting; only then does entering count. */
		boolean armed;

		transient Mission compiled;
		transient boolean compileFailed;

		/** Null if e.g. the item was removed by an update. */
		Mission mission() {
			if (compiled == null && !compileFailed) {
				try {
					compiled = Mission.compile(def.copy());
				} catch (RuntimeException e) {
					compileFailed = true;
					SmpEconomy.LOG.error("[SMPEco] missions: active mission '{}' (#{}) is invalid: {}", missionId, instance, e.getMessage());
				}
			}
			return compiled;
		}

		int amount() {
			return Math.max(1, def.amount);
		}

		String name() {
			return def.name == null ? missionId : def.name;
		}
	}

	static final DataStore<Data> STORE = new DataStore<>("missions_state.json", Data.class, Data::new);

	static {
		STORE.onLoad(data -> {
			if (data.players == null) data.players = new HashMap<>();
			if (data.elite == null) data.elite = new EliteDay();
			if (data.raids == null) data.raids = new HashMap<>();
			data.raids.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null);
			data.raids.values().forEach(r -> {
				if (r.kills == null) r.kills = new HashMap<>();
				r.kills.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null);
			});
			data.players.values().removeIf(s -> s == null);
			data.players.values().forEach(PlayerState::fixNulls);
		});
	}

	private MissionStore() {}

	/** Loads the class at mod init, so the store loads with all the others in SERVER_STARTED. */
	static void bootstrap() {
	}

	static Map<UUID, PlayerState> players() {
		return STORE.get().players;
	}

	static EliteDay eliteDay() {
		Data d = STORE.get();
		if (d.elite == null) d.elite = new EliteDay();
		return d.elite;
	}

	static Map<String, RaidTrack> raids() {
		Data d = STORE.get();
		if (d.raids == null) d.raids = new HashMap<>();
		return d.raids;
	}

	static PlayerState state(UUID player) {
		return players().computeIfAbsent(player, k -> {
			STORE.markDirty();
			return new PlayerState();
		});
	}

	static PlayerState peek(UUID player) {
		return players().get(player);
	}

	static void remove(UUID player) {
		if (players().remove(player) != null) STORE.markDirty();
	}

	static void markDirty() {
		STORE.markDirty();
	}
}
