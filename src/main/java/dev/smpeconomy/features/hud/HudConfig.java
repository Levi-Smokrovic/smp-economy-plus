package dev.smpeconomy.features.hud;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code config/smpeconomy/hud.json}. Texts use § codes. Tablist placeholders: {@code {player}}, {@code {money}},
 * {@code {money_short}}, {@code {online}}, {@code {max}}, {@code {title}} (sidebar title from core.json),
 * {@code {news}} (market shock or fever notice, may be empty; a line holding only an empty placeholder is dropped).
 */
public final class HudConfig {
	public static final int MIN_INTERVAL = 5;
	public static final int MAX_INTERVAL = 200;
	/** Main menu and command list come first, otherwise nobody finds /menu and /commands. */
	public static final String FOOTER_COMMANDS = "§8/menu §7· §8/commands §7· §8/settings";
	static final String HEADER_WELCOME = "§7Welcome, §f{player}§7!";
	static final String FOOTER_BALANCE = "§7Balance: §a{money} §8| §7Online: §f{online}§8/§f{max}";
	static final String MARKET_SHOCK_LINE = "§c⚡ Breaking News";
	static final String AUCTION_FEVER_LINE = "§6⚡ Auction Fever";
	public static final String TITLE_PLACEHOLDER = "{title}";

	/** Global sidebar switch; players also toggle it themselves with {@code /sidebar} or the "sidebar" setting. */
	public boolean sidebar = true;
	public boolean tablist = true;
	/**
	 * Styled Player List resends its header and footer every second, so ours would only flicker. Off leaves the tab list to
	 * it; show our data there with the {@code %smpeconomy:...%} placeholders instead.
	 */
	public boolean tablistWithStyledPlayerList = false;
	/** Full update of all players every N ticks; money and setting changes also apply on the next tick. */
	public int updateIntervalTicks = 20;
	/** Last sidebar line, e.g. the server address; empty means none. */
	public String address = "";
	public String marketShockLine = MARKET_SHOCK_LINE;
	public String auctionFeverLine = AUCTION_FEVER_LINE;
	public List<String> tabHeader = new ArrayList<>(List.of("", TITLE_PLACEHOLDER, HEADER_WELCOME, ""));
	public List<String> tabFooter = new ArrayList<>(List.of(
		"",
		FOOTER_BALANCE,
		"{news}",
		FOOTER_COMMANDS,
		""));
	/** Job id from jobs.json to display name; unknown ids are derived from the id. */
	public Map<String, String> jobNames = defaultJobNames();
	/**
	 * Sidebar lines on or off; a missing key means on. Keys: money, job, jobProgress, missions, missionProgress,
	 * elite, tasks, killsDeaths, playtime, online, spacers (both blank lines), marketShock, auctionFever, address.
	 */
	public Map<String, Boolean> lines = defaultLines();
	/** Longer mission names are shortened with "…" ({@value #MIN_NAME_LENGTH}-{@value #MAX_NAME_LENGTH}). */
	public int missionNameMaxLength = 20;

	public static final int MIN_NAME_LENGTH = 6;
	public static final int MAX_NAME_LENGTH = 32;

	static Map<String, Boolean> defaultLines() {
		Map<String, Boolean> m = new LinkedHashMap<>();
		for (String key : HudLines.Line.configKeys()) m.put(key, true);
		return m;
	}

	boolean shows(HudLines.Line line) {
		Boolean on = lines == null ? null : lines.get(line.configKey);
		return on == null || on;
	}

	static Map<String, String> defaultJobNames() {
		Map<String, String> m = new LinkedHashMap<>();
		m.put("miner", "Miner");
		m.put("lumberjack", "Lumberjack");
		m.put("woodcutter", "Lumberjack");
		m.put("farmer", "Farmer");
		m.put("hunter", "Hunter");
		m.put("fisher", "Fisher");
		m.put("fisherman", "Fisher");
		m.put("digger", "Digger");
		return m;
	}

	/** core.json {@code branding.sidebarTitle}: one place for all branding text. */
	public static String title() {
		return dev.smpeconomy.core.CoreConfig.get().branding.sidebarTitle;
	}

	/** Throws on invalid values, which keeps the old state active. Repairs missing lists and maps. */
	static void validate(HudConfig c) {
		if (c.updateIntervalTicks < MIN_INTERVAL || c.updateIntervalTicks > MAX_INTERVAL) {
			throw new IllegalArgumentException("updateIntervalTicks must be between " + MIN_INTERVAL + " and " + MAX_INTERVAL);
		}
		if (c.address == null) c.address = "";
		if (c.marketShockLine == null) c.marketShockLine = "";
		if (c.auctionFeverLine == null) c.auctionFeverLine = "";
		if (c.tabHeader == null) c.tabHeader = new ArrayList<>();
		if (c.tabFooter == null) c.tabFooter = new ArrayList<>();
		c.tabHeader.replaceAll(s -> s == null ? "" : s);
		c.tabFooter.replaceAll(s -> s == null ? "" : s);
		if (c.jobNames == null) c.jobNames = defaultJobNames();
		java.util.Map<String, String> names = new java.util.LinkedHashMap<>();
		c.jobNames.forEach((k, v) -> {
			if (k != null && !k.isBlank() && v != null && !v.isBlank()) names.put(k.trim().toLowerCase(java.util.Locale.ROOT), v);
		});
		c.jobNames = names;
		Map<String, Boolean> lines = defaultLines();
		if (c.lines != null) {
			java.util.Set<String> known = HudLines.Line.configKeys();
			c.lines.forEach((k, v) -> {
				if (k == null || v == null) return;
				if (known.contains(k)) lines.put(k, v);
				else dev.smpeconomy.SmpEconomy.LOG.warn("[SMPEco] hud.json: ignored unknown sidebar line '{}' (known: {})", k, known);
			});
		}
		c.lines = lines;
		c.missionNameMaxLength = Math.max(MIN_NAME_LENGTH, Math.min(MAX_NAME_LENGTH, c.missionNameMaxLength));
	}
}
