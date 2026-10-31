package dev.smpeconomy.features.stats;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.Services;
import dev.smpeconomy.core.TimeUtil;

/**
 * Read access to statistics: online players live from vanilla stats, offline players from {@link StatsStore}. Player
 * kills always come from the hook counter (plus earlier kills, see {@link StatsConfig#countFormerKills}), playtime
 * from {@link PlayerDataService}, the same source as sidebar and holograms.
 */
public final class StatsService implements Services.PlayerStats {
	static final StatsService INSTANCE = new StatsService();
	static final String UNKNOWN = "Unknown";

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy"); // Zone applied only when formatting: core.json timezone can change on reload.
	/** Join times of the current session, in memory only. */
	private static final Map<UUID, Long> SESSIONS = new HashMap<>();

	record View(UUID id, String name, boolean online, long kills, long pvpKills, long baseline, long pvpDeaths, long deaths,
		long mobKills, long streak, long bestStreak, long playtimeTicks, long blocksMined, long walkedCm, long firstSeen,
		long lastSeen, long sessionMs) {}

	private StatsService() {}

	@Override
	public long playerKills(UUID player) {
		return StatsStore.peek(player).map(StatsService::kills).orElse(0L);
	}

	@Override
	public long deaths(UUID player) {
		long live = StatsStore.liveCustom(online(player), Stats.DEATHS);
		if (live >= 0) return live;
		return StatsStore.peek(player).map(r -> r.deaths).orElse(0L);
	}

	@Override
	public long mobKills(UUID player) {
		long live = StatsStore.liveCustom(online(player), Stats.MOB_KILLS);
		if (live >= 0) return live;
		return StatsStore.peek(player).map(r -> r.mobKills).orElse(0L);
	}

	static long kills(StatsStore.Rec r) {
		return displayKills(r, StatsFeature.config().countFormerKills);
	}

	static long displayKills(StatsStore.Rec r, boolean includeBaseline) {
		return includeBaseline ? StatsStore.plus(r.pvpKills, r.pvpBaseline) : r.pvpKills;
	}

	/** Core's counter, taken over from vanilla on first join. */
	static long playtimeTicks(UUID id) {
		return PlayerDataService.getPlaytimeTicks(id);
	}

	static View view(UUID id) {
		ServerPlayer player = online(id);
		StatsStore.Rec r = StatsStore.peek(id).orElseGet(StatsStore.Rec::new);
		StatsStore.Vanilla live = StatsStore.live(player);
		long deaths = live != null ? live.deaths() : r.deaths;
		long mobKills = live != null ? live.mobKills() : r.mobKills;
		long blocks = live != null ? live.blocksMined() : r.blocksMined;
		long walked = live != null ? live.walkedCm() : r.walkedCm;
		return new View(id, name(id), player != null, kills(r), r.pvpKills, r.pvpBaseline, r.pvpDeaths, deaths, mobKills, r.streak,
			r.bestStreak, playtimeTicks(id), blocks, walked, r.firstSeen, r.lastSeen, sessionMs(id));
	}

	/** FakePlayers are never in the PlayerList. */
	static ServerPlayer online(UUID id) {
		MinecraftServer server = SmpEconomy.server();
		return server == null || id == null ? null : server.getPlayerList().getPlayer(id);
	}

	/** Online, name cache, player data, stats.json, then {@link #UNKNOWN}. */
	static String name(UUID id) {
		ServerPlayer p = online(id);
		if (p != null) return p.getGameProfile().name();
		String n = EconomyService.nameOf(id).orElse(null);
		if (n != null) return n;
		n = PlayerDataService.peek(id).map(d -> d.name).orElse("");
		if (n != null && !n.isEmpty()) return n;
		n = StatsStore.peek(id).map(r -> r.name).orElse("");
		return n == null || n.isEmpty() ? UNKNOWN : n;
	}

	static void markJoined(UUID id) {
		SESSIONS.put(id, System.currentTimeMillis());
	}

	static void markLeft(UUID id) {
		SESSIONS.remove(id);
	}

	static void clearSessions() {
		SESSIONS.clear();
	}

	/** 0 means offline or unknown. */
	static long sessionMs(UUID id) {
		Long since = SESSIONS.get(id);
		return since == null || online(id) == null ? 0 : Math.max(0, System.currentTimeMillis() - since);
	}

	/** Two decimals; with no deaths this is the kill count. */
	static String kd(long kills, long deaths) {
		return String.format(Locale.ROOT, "%.2f", kills / (double) Math.max(1, deaths));
	}

	/** Meters below 1 km, otherwise km with one decimal. */
	static String distance(long cm) {
		long c = Math.max(0, cm);
		if (c < 100_000) return Msg.number(c / 100) + " m";
		return String.format(Locale.ROOT, "%,.1f km", c / 100_000.0);
	}

	static String date(long epochMs) {
		return DATE.withZone(TimeUtil.zone()).format(Instant.ofEpochMilli(epochMs));
	}
}
