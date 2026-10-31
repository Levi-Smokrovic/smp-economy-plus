package dev.smpeconomy.features.stats;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import net.minecraft.stats.StatsCounter;
import net.minecraft.world.level.block.Block;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.Bots;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.test.TestPlayers;

/**
 * {@code <world>/smpeconomy/stats.json}: per player the PvP kills and deaths counted through
 * {@code Hooks.onPlayerKilledPlayer}, kill streaks, and a cache of vanilla stats so values are available offline
 * (leaderboards, sidebar, holograms). Online players are read live ({@link StatsService}); the cache is refreshed on
 * join and leave, after deaths, periodically and on stop. Server thread only.
 */
final class StatsStore {
	record Vanilla(long playerKills, long deaths, long mobKills, long blocksMined, long walkedCm) {
		static final Vanilla ZERO = new Vanilla(0, 0, 0, 0, 0);

		/** Walking, sprinting, sneaking, swimming, walking on and under water (cm). */
		private static final Identifier[] WALK_STATS = {
			Stats.WALK_ONE_CM, Stats.SPRINT_ONE_CM, Stats.CROUCH_ONE_CM, Stats.SWIM_ONE_CM, Stats.WALK_ON_WATER_ONE_CM,
			Stats.WALK_UNDER_WATER_ONE_CM};

		static Vanilla read(StatsCounter counter) {
			long blocks = 0;
			for (Stat<Block> stat : Stats.BLOCK_MINED) blocks += Math.max(0, counter.getValue(stat));
			long walked = 0;
			for (Identifier id : WALK_STATS) walked += Math.max(0, custom(counter, id));
			return new Vanilla(custom(counter, Stats.PLAYER_KILLS), custom(counter, Stats.DEATHS), custom(counter, Stats.MOB_KILLS), blocks, walked);
		}

		private static long custom(StatsCounter counter, Identifier id) {
			return Math.max(0, counter.getValue(Stats.CUSTOM.get(id)));
		}
	}

	static final class Rec {
		String name = "";
		/** Counted since install through {@code Hooks.onPlayerKilledPlayer}. */
		long pvpKills;
		/** Vanilla {@code player_kills} at first capture (kills from before the install). */
		long pvpBaseline;
		/** Deaths by other players, counted through the hook. */
		long pvpDeaths;
		long deaths;
		long mobKills;
		/** Player kills since the last death. */
		long streak;
		long bestStreak;
		long blocksMined;
		long walkedCm;
		/** Since install, epoch ms; 0 means unknown, e.g. imported. */
		long firstSeen;
		/** Epoch ms; 0 means unknown. */
		long lastSeen;

		void fix() {
			if (name == null) name = "";
			pvpKills = Math.max(0, pvpKills);
			pvpBaseline = Math.max(0, pvpBaseline);
			pvpDeaths = Math.max(0, pvpDeaths);
			deaths = Math.max(0, deaths);
			mobKills = Math.max(0, mobKills);
			streak = Math.max(0, streak);
			bestStreak = Math.max(streak, Math.max(0, bestStreak));
			blocksMined = Math.max(0, blocksMined);
			walkedCm = Math.max(0, walkedCm);
			firstSeen = Math.max(0, firstSeen);
			lastSeen = Math.max(0, lastSeen);
		}
	}

	static final class Data {
		Map<UUID, Rec> players = new HashMap<>();
	}

	static final DataStore<Data> STORE = new DataStore<>("stats.json", Data.class, Data::new);

	static {
		STORE.onLoad(StatsStore::repair);
	}

	/** Repairs missing or broken entries after load (null map, null entries, negative values). */
	static void repair(Data data) {
		if (data.players == null) data.players = new HashMap<>();
		data.players.keySet().removeIf(Objects::isNull);
		data.players.values().removeIf(Objects::isNull);
		data.players.values().forEach(Rec::fix);
	}

	private StatsStore() {}

	/** Registers the store before SERVER_STARTED through class initialization. */
	static void init() {
		Objects.requireNonNull(STORE);
	}

	static Optional<Rec> peek(UUID id) {
		return id == null ? Optional.empty() : Optional.ofNullable(players().get(id));
	}

	static Map<UUID, Rec> all() {
		return Collections.unmodifiableMap(players());
	}

	/** Test player cleanup, admin. */
	static void remove(UUID id) {
		if (id != null && players().remove(id) != null) STORE.markDirty();
	}

	/** Real players yes; FakePlayers only if they are selftest players, never those of other mods. */
	static boolean trackable(ServerPlayer player) {
		return player != null && (!Bots.isBot(player) || TestPlayers.isTestPlayer(player.getUUID()));
	}

	/** Null for FakePlayers, which don't award statistics. */
	static Vanilla live(ServerPlayer player) {
		return player == null || Bots.isBot(player) ? null : Vanilla.read(player.getStats());
	}

	/** Cheap enough for the sidebar every second; -1 means not available. */
	static long liveCustom(ServerPlayer player, Identifier stat) {
		return player == null || Bots.isBot(player) ? -1 : Vanilla.custom(player.getStats(), stat);
	}

	/**
	 * {@code alreadyCounted}: kills already in {@code vanilla.playerKills} that are about to be counted through the
	 * hook too, because vanilla awards {@code player_kills} before {@code AFTER_DEATH}.
	 */
	static Rec newRecord(String name, Vanilla vanilla, long alreadyCounted, long now) {
		Rec r = new Rec();
		r.name = name == null ? "" : name;
		Vanilla v = vanilla == null ? Vanilla.ZERO : vanilla;
		r.pvpBaseline = Math.max(0, v.playerKills() - Math.max(0, alreadyCounted));
		apply(r, v);
		r.firstSeen = now;
		r.lastSeen = now;
		return r;
	}

	/** Never negative. Values counted through the hook stay untouched. */
	static void apply(Rec r, Vanilla v) {
		if (v == null) return;
		r.deaths = Math.max(0, v.deaths());
		r.mobKills = Math.max(0, v.mobKills());
		r.blocksMined = Math.max(0, v.blocksMined());
		r.walkedCm = Math.max(0, v.walkedCm());
	}

	/** Created on demand with base kills from vanilla stats. */
	static Rec ensure(ServerPlayer player, long alreadyCounted) {
		UUID id = player.getUUID();
		Rec r = players().get(id);
		if (r == null) {
			r = newRecord(player.getGameProfile().name(), live(player), alreadyCounted, System.currentTimeMillis());
			players().put(id, r);
			STORE.markDirty();
		}
		return r;
	}

	static void snapshot(ServerPlayer player) {
		if (!trackable(player)) return;
		Rec r = ensure(player, 0);
		apply(r, live(player));
		String name = player.getGameProfile().name();
		if (name != null && !name.isEmpty()) r.name = name;
		r.lastSeen = System.currentTimeMillis();
		if (r.firstSeen <= 0) r.firstSeen = r.lastSeen; // Imported player: first join after the install.
		STORE.markDirty();
	}

	/** Only called from the {@code Hooks.onPlayerKilledPlayer} listener; core fires it, stats never does. */
	static void recordKill(ServerPlayer killer, ServerPlayer victim) {
		if (killer == null || victim == null || killer.getUUID().equals(victim.getUUID())) return;
		if (!trackable(killer) || !trackable(victim)) return;
		// Vanilla has already raised the killer's player_kills, so a new record must not count it again as an earlier kill.
		Rec k = ensure(killer, 1);
		Rec v = ensure(victim, 0);
		k.pvpKills = plus(k.pvpKills, 1);
		k.streak = plus(k.streak, 1);
		k.bestStreak = Math.max(k.bestStreak, k.streak);
		v.pvpDeaths = plus(v.pvpDeaths, 1);
		v.streak = 0; // AFTER_DEATH resets the streak as well.
		STORE.markDirty();
	}

	/** Any cause of death, from {@code ServerLivingEntityEvents.AFTER_DEATH}. */
	static void recordDeath(ServerPlayer victim) {
		if (!trackable(victim)) return;
		Rec r = ensure(victim, 0);
		r.streak = 0;
		Vanilla v = live(victim);
		if (v != null) {
			apply(r, v);
			r.lastSeen = System.currentTimeMillis();
		}
		STORE.markDirty();
	}

	/**
	 * Imports a player without a record once from their vanilla stats file, read-only.
	 *
	 * @return true if a record was created
	 */
	static boolean importFile(UUID id, String name, Path file, MinecraftServer server) {
		if (id == null || players().containsKey(id) || !Files.isRegularFile(file)) return false;
		Vanilla v = Vanilla.read(new ServerStatsCounter(server, file));
		long modified = 0;
		try {
			modified = Files.getLastModifiedTime(file).toMillis();
		} catch (IOException e) {
			SmpEconomy.LOG.warn("[SMPEco] stats: cannot read mtime of {}", file, e);
		}
		Rec r = newRecord(name, v, 0, 0);
		r.lastSeen = Math.max(0, modified);
		players().put(id, r);
		STORE.markDirty();
		return true;
	}

	static void put(UUID id, Rec r) {
		players().put(id, r);
		STORE.markDirty();
	}

	static long plus(long a, long b) {
		return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b;
	}

	private static Map<UUID, Rec> players() {
		return STORE.get().players;
	}
}
