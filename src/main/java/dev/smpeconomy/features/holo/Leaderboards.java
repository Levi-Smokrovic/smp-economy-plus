package dev.smpeconomy.features.holo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.ToLongFunction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stats;
import net.minecraft.world.level.storage.LevelResource;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.Bots;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.Services;
import dev.smpeconomy.core.TimeUtil;
import dev.smpeconomy.core.test.TestPlayers;
import dev.smpeconomy.features.holo.HoloText.Score;

/** Read-only data sources for the leaderboards. Test and system accounts never appear. */
final class Leaderboards {
	/**
	 * Vanilla kills of offline players: stats file to {modified ms, value}. Nothing changes while offline, so the
	 * mtime is enough.
	 */
	private static final Map<UUID, long[]> OFFLINE_KILLS = new HashMap<>();
	private static boolean warnedBank;
	private static boolean warnedStats;

	private Leaderboards() {}

	/** Service failures are logged at most once per call. */
	static List<Score> collect(MinecraftServer server, HoloType type) {
		warnedBank = false;
		warnedStats = false;
		return switch (type) {
			case MONEY -> money(EconomyService.top(), bankSource(HoloConfig.get().includeBank));
			case KILLS -> counts(candidates(server), killsSource(server, Services.find(Services.PlayerStats.class)), Leaderboards::nameOf, Leaderboards::killsText);
			case PLAYTIME -> counts(candidates(server), PlayerDataService::getPlaytimeTicks, Leaderboards::nameOf, TimeUtil::formatTicks);
			case JOBS -> jobs(candidates(server), Leaderboards::nameOf);
		};
	}

	/** Balance plus bank capped at {@link Money#MAX}; only amounts above 0. */
	static List<Score> money(List<EconomyService.Entry> top, ToLongFunction<UUID> bank) {
		List<Score> list = new ArrayList<>();
		if (top == null) return list;
		for (EconomyService.Entry e : top) {
			if (e == null || e.id() == null) continue;
			long total = addCapped(e.balance(), bank == null ? 0 : bank.applyAsLong(e.id()));
			if (total > 0) list.add(new Score(e.id(), e.name(), total, 0, Money.formatShort(total)));
		}
		return list;
	}

	/** Negative parts count as 0; the result is capped at {@link Money#MAX}. */
	static long addCapped(long a, long b) {
		long x = Math.max(0, a);
		long y = Math.max(0, b);
		return x > Money.MAX - Math.min(y, Money.MAX) ? Money.MAX : Math.min(Money.MAX, x + y);
	}

	/** 0 if the bank service is missing or throws. */
	static ToLongFunction<UUID> bankSource(boolean includeBank) {
		if (!includeBank) return id -> 0;
		Optional<Services.Bank> bank = Services.find(Services.Bank.class);
		if (bank.isEmpty()) return id -> 0;
		Services.Bank b = bank.get();
		return id -> {
			try {
				return Math.max(0, b.balance(id));
			} catch (RuntimeException e) {
				if (!warnedBank) {
					warnedBank = true;
					SmpEconomy.LOG.warn("[SMPEco] holo: bank service threw ({}), ignoring bank balances", e.toString());
				}
				return 0;
			}
		};
	}

	static List<Score> counts(Collection<UUID> ids, ToLongFunction<UUID> value, Function<UUID, String> names, LongFunction<String> format) {
		List<Score> list = new ArrayList<>();
		for (UUID id : ids) {
			long v = Math.max(0, value.applyAsLong(id));
			if (v > 0) list.add(new Score(id, names.apply(id), v, 0, format.apply(v)));
		}
		return list;
	}

	static String killsText(long kills) {
		return Msg.number(kills) + (kills == 1 ? " Kill" : " Kills");
	}

	/** Falls back to the vanilla {@code player_kills} stat if the service is missing or throws. */
	static ToLongFunction<UUID> killsSource(MinecraftServer server, Optional<Services.PlayerStats> stats) {
		if (stats.isEmpty()) return id -> vanillaKills(server, id);
		Services.PlayerStats s = stats.get();
		return id -> {
			try {
				return Math.max(0, s.playerKills(id));
			} catch (RuntimeException e) {
				if (!warnedStats) {
					warnedStats = true;
					SmpEconomy.LOG.warn("[SMPEco] holo: PlayerStats service threw ({}), using vanilla stats", e.toString());
				}
				return vanillaKills(server, id);
			}
		};
	}

	/** Online from the player, offline from {@code <world>/stats/<uuid>.json}. */
	static long vanillaKills(MinecraftServer server, UUID id) {
		ServerPlayer online = server.getPlayerList().getPlayer(id);
		if (online != null) return Math.max(0, online.getStats().getValue(Stats.CUSTOM.get(Stats.PLAYER_KILLS)));
		return offlineKills(server, server.getWorldPath(LevelResource.PLAYER_STATS_DIR).resolve(id + ".json"), id);
	}

	/** Never writes the file. Cached by mtime; a missing file counts as 0. */
	static long offlineKills(MinecraftServer server, Path file, UUID id) {
		long modified;
		try {
			if (!Files.isRegularFile(file)) {
				OFFLINE_KILLS.remove(id);
				return 0;
			}
			modified = Files.getLastModifiedTime(file).toMillis();
		} catch (IOException | RuntimeException e) {
			return 0;
		}
		long[] cached = OFFLINE_KILLS.get(id);
		if (cached != null && cached[0] == modified) return cached[1];
		long value;
		try {
			value = Math.max(0, new ServerStatsCounter(server, file).getValue(Stats.CUSTOM.get(Stats.PLAYER_KILLS)));
		} catch (RuntimeException e) {
			SmpEconomy.LOG.warn("[SMPEco] holo: cannot read stats file {}: {}", file, e.toString());
			value = 0;
		}
		OFFLINE_KILLS.put(id, new long[] {modified, value});
		return value;
	}

	static void forgetCached(UUID id) {
		OFFLINE_KILLS.remove(id);
	}

	record BestJob(String job, int level, long xp) {}

	static List<Score> jobs(Collection<UUID> ids, Function<UUID, String> names) {
		List<Score> list = new ArrayList<>();
		for (UUID id : ids) {
			PlayerDataService.PlayerData d = PlayerDataService.peek(id).orElse(null);
			if (d == null) continue;
			BestJob best = bestJob(d.jobLevel, d.jobXp);
			if (best != null) list.add(new Score(id, names.apply(id), best.level(), best.xp(), jobLabel(best.job()) + " Lvl " + best.level()));
		}
		return list;
	}

	/**
	 * Highest level across all jobs with a level or XP entry; ties go to more XP, then job id. A missing level counts
	 * as 1, matching {@link PlayerDataService#getJobLevel}. Null if nothing was achieved (level 1, no XP).
	 */
	static BestJob bestJob(Map<String, Integer> levels, Map<String, Long> xp) {
		Set<String> jobs = new LinkedHashSet<>();
		if (levels != null) jobs.addAll(levels.keySet());
		if (xp != null) jobs.addAll(xp.keySet());
		BestJob best = null;
		for (String job : jobs) {
			if (job == null || job.isBlank()) continue;
			Integer lvObj = levels == null ? null : levels.get(job);
			Long xpObj = xp == null ? null : xp.get(job);
			int level = Math.max(1, lvObj == null ? 1 : lvObj);
			long x = Math.max(0, xpObj == null ? 0 : xpObj);
			if (level <= 1 && x <= 0) continue;
			if (best == null || level > best.level() || (level == best.level() && (x > best.xp() || (x == best.xp() && job.compareTo(best.job()) < 0)))) {
				best = new BestJob(job, level, x);
			}
		}
		return best;
	}

	/**
	 * jobs.json belongs to the jobs feature, so known ids are mapped here and others get a capitalized first letter.
	 */
	static String jobLabel(String id) {
		String key = HoloText.clean(id).toLowerCase(Locale.ROOT);
		return switch (key) {
			case "miner" -> "Miner";
			case "lumberjack", "woodcutter" -> "Lumberjack";
			case "farmer" -> "Farmer";
			case "hunter" -> "Hunter";
			case "fisher", "fisherman", "angler" -> "Fisher";
			case "digger", "excavator" -> "Digger";
			default -> {
				String s = key.replace('_', ' ').trim();
				yield s.isEmpty() ? "Job" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
			}
		};
	}

	/** Known from players.json or online, excluding test and system accounts and FakePlayers. */
	static Set<UUID> candidates(MinecraftServer server) {
		Set<UUID> ids = new LinkedHashSet<>(PlayerDataService.knownPlayers());
		if (server != null) {
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				if (!Bots.isBot(p)) ids.add(p.getUUID());
			}
		}
		ids.removeIf(id -> TestPlayers.isTestPlayer(id) || EconomyService.isSystemAccount(id));
		return ids;
	}

	/** Name cache first, then players.json, then {@link HoloText#UNKNOWN}. */
	static String nameOf(UUID id) {
		Optional<String> n = EconomyService.nameOf(id);
		if (n.isPresent()) return n.get();
		String fromData = PlayerDataService.peek(id).map(d -> d.name).orElse("");
		return fromData == null || fromData.isEmpty() ? HoloText.UNKNOWN : fromData;
	}
}
