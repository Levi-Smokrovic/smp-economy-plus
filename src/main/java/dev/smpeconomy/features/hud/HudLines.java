package dev.smpeconomy.features.hud;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Services;

/**
 * Pure text building for the HUD, with no server access so selftests can call it directly.
 *
 * <p>Each possible sidebar line has a fixed score holder and score (enum order). A missing line leaves its gap empty,
 * so showing or hiding one changes exactly that line on the client and the others don't shift.
 */
final class HudLines {
	/** The client shows at most 15 sidebar entries, and there are exactly that many {@link Line}s. */
	static final int MAX_LINES = 15;
	/** Short form ($1.2M) from here on, full amount with cents below. */
	static final long SHORT_MONEY_FROM = 100_000_000L; // $1,000,000.00
	/** Segments of the job progress bar: it changes in 10% steps, the percentage per percent. */
	static final int BAR_SEGMENTS = 10;

	/** Top to bottom; {@code configKey} is the switch in hud.json "lines". */
	enum Line {
		SPACER_TOP("spacers"),
		MONEY("money"),
		JOB("job"),
		JOB_PROGRESS("jobProgress"),
		MISSIONS("missions"),
		MISSION_PROGRESS("missionProgress"),
		ELITE("elite"),
		TASKS("tasks"),
		KILLS_DEATHS("killsDeaths"),
		PLAYTIME("playtime"),
		ONLINE("online"),
		SPACER_BOTTOM("spacers"),
		MARKET_SHOCK("marketShock"),
		AUCTION_FEVER("auctionFever"),
		ADDRESS("address");

		final String configKey;

		Line(String configKey) {
			this.configKey = configKey;
		}

		/** Must not start with '#', which the client hides, and must not be a valid player name. */
		String holder() {
			return "smpeco.hud." + name().toLowerCase(Locale.ROOT);
		}

		/** Higher is further up. */
		int score() {
			return MAX_LINES - ordinal();
		}

		static Set<String> configKeys() {
			Set<String> keys = new LinkedHashSet<>();
			for (Line l : values()) keys.add(l.configKey);
			return Collections.unmodifiableSet(keys);
		}
	}

	/**
	 * {@code id} null means no job. {@code xpForNext} &lt; 0 means progress unknown (no jobs service).
	 * {@code maxLevel} &gt; 0 and {@code level >= maxLevel} means max level.
	 */
	record JobInfo(String id, int level, long xp, long xpForNext, int maxLevel) {
		static final JobInfo NONE = new JobInfo(null, 0, 0, -1, 0);

		boolean isMax() {
			return maxLevel > 0 && level >= maxLevel;
		}
	}

	/** Null in {@link Data} means no missions service, so no mission or elite lines. */
	record MissionInfo(int active, int max, Services.Missions.MissionProgress nearest, Services.Missions.EliteStatus elite) {}

	/**
	 * @param job        never null ({@link JobInfo#NONE})
	 * @param tasksDone  -1 means unknown
	 * @param tasksTotal at least 1
	 * @param missions   null without the missions feature
	 */
	record Data(String playerName, long balance, JobInfo job, int tasksDone, int tasksTotal, MissionInfo missions, long kills,
		long deaths, long playtimeTicks, int online, int maxPlayers, boolean marketShock, boolean auctionFever) {}

	private HudLines() {}

	static Map<Line, String> sidebar(Data d, HudConfig cfg) {
		EnumMap<Line, String> out = new EnumMap<>(Line.class);
		put(out, cfg, Line.SPACER_TOP, "");
		put(out, cfg, Line.MONEY, "§7Money: §a" + money(d.balance()));
		JobInfo job = d.job() == null ? JobInfo.NONE : d.job();
		put(out, cfg, Line.JOB, "§7Job: " + job(job.id(), job.level(), cfg.jobNames));
		put(out, cfg, Line.JOB_PROGRESS, jobProgress(job));
		MissionInfo m = d.missions();
		if (m != null) {
			put(out, cfg, Line.MISSIONS, "§7Missions: " + missions(m.active(), m.max()));
			put(out, cfg, Line.MISSION_PROGRESS, missionProgress(m.nearest(), cfg.missionNameMaxLength));
			put(out, cfg, Line.ELITE, elite(m.elite()));
		}
		put(out, cfg, Line.TASKS, "§7Tasks: " + tasks(d.tasksDone(), d.tasksTotal()));
		put(out, cfg, Line.KILLS_DEATHS, "§7Kills: §f" + Msg.number(Math.max(0, d.kills())) + " §8| §7Deaths: §f" + Msg.number(Math.max(0, d.deaths())));
		put(out, cfg, Line.PLAYTIME, "§7Playtime: §f" + playtime(d.playtimeTicks()));
		put(out, cfg, Line.ONLINE, "§7Online: §f" + Math.max(0, d.online()));
		if (d.marketShock() && !cfg.marketShockLine.isEmpty()) put(out, cfg, Line.MARKET_SHOCK, cfg.marketShockLine);
		if (d.auctionFever() && !cfg.auctionFeverLine.isEmpty()) put(out, cfg, Line.AUCTION_FEVER, cfg.auctionFeverLine);
		if (!cfg.address.isEmpty()) put(out, cfg, Line.ADDRESS, cfg.address);
		// Lower spacer only as a separator before notices or the address, never as the last line.
		boolean below = out.containsKey(Line.MARKET_SHOCK) || out.containsKey(Line.AUCTION_FEVER) || out.containsKey(Line.ADDRESS);
		if (below) put(out, cfg, Line.SPACER_BOTTOM, "");
		return Collections.unmodifiableMap(out);
	}

	private static void put(Map<Line, String> out, HudConfig cfg, Line line, String text) {
		if (text != null && cfg.shows(line)) out.put(line, text);
	}

	static List<String> texts(Map<Line, String> lines) {
		EnumMap<Line, String> sorted = new EnumMap<>(Line.class);
		if (lines != null) sorted.putAll(lines);
		return List.copyOf(sorted.values());
	}

	/** Below $1M in full ({@code $1,234.56}), above that short and rounded down ({@code $1.5M}). */
	static String money(long cents) {
		long c = Math.max(0, cents);
		return c < SHORT_MONEY_FROM ? Money.format(c) : Money.formatShort(c);
	}

	static String job(String jobId, int level, Map<String, String> names) {
		if (jobId == null || jobId.isBlank()) return "§8none";
		return "§e" + jobName(jobId, names) + " §8(Lv. " + Math.max(1, level) + ")";
	}

	/** From config, otherwise derived from the id ("stone_mason" gives "Stone mason"). */
	static String jobName(String jobId, Map<String, String> names) {
		String id = jobId.trim();
		String configured = names == null ? null : names.get(id.toLowerCase(Locale.ROOT));
		if (configured != null && !configured.isBlank()) return configured;
		String plain = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
		plain = plain.replace('_', ' ').trim();
		if (plain.isEmpty()) return id;
		return plain.substring(0, 1).toUpperCase(Locale.ROOT) + plain.substring(1);
	}

	/**
	 * {@code ▸ ■■■■■■□□□□ 62%}, rounded down so it never shows 100% before the level-up; {@code ▸ Max Level} at the
	 * cap; null without a job or a known XP threshold.
	 */
	static String jobProgress(JobInfo j) {
		if (j == null || j.id() == null || j.id().isBlank()) return null;
		if (j.isMax()) return "§8 ▸ §6Max Level";
		if (j.xpForNext() <= 0) return null;
		long need = j.xpForNext();
		long xp = Math.max(0, Math.min(need - 1, j.xp()));
		int percent = (int) (xp * 100 / need); // xp < need <= 1e15, so no overflow
		int filled = (int) (xp * BAR_SEGMENTS / need);
		StringBuilder sb = new StringBuilder("§8 ▸ ");
		if (filled > 0) sb.append("§a").append("■".repeat(filled));
		if (filled < BAR_SEGMENTS) sb.append("§7").append("■".repeat(BAR_SEGMENTS - filled));
		return sb.append(" §f").append(percent).append('%').toString();
	}

	static String missions(int active, int max) {
		int a = Math.max(0, active);
		return max > 0 ? "§f" + a + "§7/§f" + max : "§f" + a;
	}

	/** {@code ▸ Smith Supplies 20/32}; null without an active mission. */
	static String missionProgress(Services.Missions.MissionProgress p, int maxNameLength) {
		if (p == null) return null;
		long amount = Math.max(1, p.amount());
		long done = Math.max(0, Math.min(amount, p.progress()));
		return "§8 ▸ §f" + shortName(p.name(), maxNameLength) + " §e" + Msg.number(done) + "§7/§e" + Msg.number(amount);
	}

	/** No § codes, trimmed, at most {@code max} characters (with "…" if cut); empty gives "Mission". */
	static String shortName(String name, int max) {
		String plain = name == null ? "" : name.replaceAll("§.?", "").trim();
		if (plain.isEmpty()) return "Mission";
		int limit = Math.max(2, max);
		if (plain.length() <= limit) return plain;
		return plain.substring(0, limit - 1).trim() + "…";
	}

	/** {@code Elite: ✔} or {@code Elite: open}; null without a daily elite. */
	static String elite(Services.Missions.EliteStatus status) {
		if (status == null) return null;
		return switch (status) {
			case NONE -> null;
			case OPEN -> "§7Elite: §eopen";
			case DONE -> "§7Elite: §a✔";
		};
	}

	static String tasks(int done, int total) {
		if (done < 0 || total <= 0) return "§8–";
		int d = Math.min(done, total);
		if (d >= total) return "§a" + d + "/" + total + " ✔";
		return "§f" + d + "§7/§f" + total;
	}

	/** Minute resolution, so it changes at most once a minute: {@code 45m}, {@code 3h 07m}, {@code 2d 4h}. */
	static String playtime(long ticks) {
		long minutes = Math.max(0, ticks) / 1200;
		long days = minutes / 1440;
		long hours = minutes % 1440 / 60;
		long mins = minutes % 60;
		if (days > 0) return days + "d " + hours + "h";
		if (hours > 0) return hours + "h " + (mins < 10 ? "0" : "") + mins + "m";
		return mins + "m";
	}

	static String header(Data d, HudConfig cfg) {
		return fill(cfg.tabHeader, d, cfg);
	}

	static String footer(Data d, HudConfig cfg) {
		return fill(cfg.tabFooter, d, cfg);
	}

	/** Market shock and/or auction fever, otherwise empty. */
	static String news(Data d, HudConfig cfg) {
		List<String> parts = new ArrayList<>();
		if (d.marketShock() && !cfg.marketShockLine.isEmpty()) parts.add(cfg.marketShockLine);
		if (d.auctionFever() && !cfg.auctionFeverLine.isEmpty()) parts.add(cfg.auctionFeverLine);
		return String.join(" §8| ", parts);
	}

	/** Lines that held only an empty placeholder are dropped. */
	static String fill(List<String> template, Data d, HudConfig cfg) {
		if (template == null || template.isEmpty()) return "";
		String news = news(d, cfg);
		List<String> out = new ArrayList<>(template.size());
		for (String raw : template) {
			String line = raw == null ? "" : raw;
			String replaced = line
				.replace("{player}", d.playerName() == null ? "" : d.playerName())
				.replace("{money}", Money.format(Math.max(0, d.balance())))
				.replace("{money_short}", Money.formatShort(Math.max(0, d.balance())))
				.replace("{online}", Integer.toString(Math.max(0, d.online())))
				.replace("{max}", Integer.toString(Math.max(0, d.maxPlayers())))
				.replace(HudConfig.TITLE_PLACEHOLDER, HudConfig.title())
				.replace("{news}", news);
			if (!line.isEmpty() && replaced.isEmpty()) continue;
			out.add(replaced);
		}
		return String.join("\n", out);
	}
}
