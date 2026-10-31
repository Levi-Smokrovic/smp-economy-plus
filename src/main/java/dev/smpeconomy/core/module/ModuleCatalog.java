package dev.smpeconomy.core.module;

import static dev.smpeconomy.core.module.ModuleGroup.CORE;
import static dev.smpeconomy.core.module.ModuleGroup.EXTRA;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * All modules in fixed order, which is registration order; stopping runs in reverse. Plain data with no Minecraft
 * classes because the mixin plugin reads it before the game starts. Factories are kept apart in
 * {@code dev.smpeconomy.ModuleRegistry} so no feature class gets loaded here.
 *
 * <p>{@code requires} lists real code dependencies only. economy is always on (locked) and so is never listed.
 */
public final class ModuleCatalog {
	public static final String ECONOMY = "economy";

	public static final List<ModuleInfo> ALL = List.of(
		m("economy", "Economy", "Balances, /pay, /bal, /baltop, /eco and the server treasury.", "minecraft:gold_ingot", CORE, true,
			List.of(), List.of("bank"), List.of("bal", "balance", "money", "baltop", "eco", "pay", "paytoggle", "treasury")),
		m("starter", "Starter", "Start money and a starter kit for new players.", "minecraft:chest", CORE, false,
			List.of(), List.of(), List.of()),
		m("sell", "Sell", "/sell GUI, /sell hand|all, /worth and the price list.", "minecraft:hopper", CORE, false,
			List.of(), List.of(), List.of("sell", "worth")),
		m("shop", "Shop", "Server shop with categories, daily deals and search.", "minecraft:emerald", CORE, false,
			List.of(), List.of(), List.of("shop")),
		m("auction", "Auction house", "Player auction house (/ah).", "minecraft:anvil", CORE, false,
			List.of(), List.of(), List.of("ah", "auction")),
		m("jobs", "Jobs", "Jobs with levels and pay for mining, farming, fishing, hunting …", "minecraft:iron_pickaxe", CORE, false,
			List.of(), List.of(), List.of("jobs", "job")),
		m("tasks", "Daily tasks", "Three daily tasks plus a bonus.", "minecraft:writable_book", CORE, false,
			List.of(), List.of("crates"), List.of("tasks")),
		m("minigames", "Minigames", "Chat games, MEGA challenge, treasure hunt and PvP coinflips.", "minecraft:firework_rocket", EXTRA, false,
			List.of(), List.of("crates", "casino"), List.of("coinflip", "cf", "minigames", "treasure", "chatgame")),
		m("hud", "Sidebar", "Sidebar with balance, job, tasks and more (/sidebar).", "minecraft:oak_sign", CORE, false,
			List.of(), List.of("jobs", "missions", "tasks", "stats", "settings", "news"), List.of("sidebar")),
		m("market", "Market", "Market values, price drift and NPC buyers/sellers in the auction house.", "minecraft:bell", EXTRA, false,
			List.of("auction"), List.of(), List.of("market")),
		m("orders", "Orders", "Buy-order board for players and NPCs.", "minecraft:paper", CORE, false,
			List.of(), List.of("market"), List.of("orders")),
		m("settings", "Player settings", "/settings: personal toggles, mob spawning near you, phantoms, death coordinates.", "minecraft:comparator", CORE, false,
			List.of(), List.of(), List.of("settings")),
		m("daily", "Daily rewards", "Daily login reward with streaks.", "minecraft:clock", EXTRA, false,
			List.of(), List.of("crates"), List.of("daily")),
		m("stats", "Stats", "/stats and leaderboards.", "minecraft:book", CORE, false,
			List.of(), List.of("bank", "events"), List.of("stats", "playtime", "leaderboard")),
		m("bounty", "Bounties", "Players put bounties on other players.", "minecraft:iron_sword", EXTRA, false,
			List.of(), List.of(), List.of("bounty")),
		m("crates", "Crates", "Virtual-key crates with a spin animation.", "minecraft:ender_chest", EXTRA, false,
			List.of(), List.of(), List.of("crates")),
		m("afk", "AFK zone", "Rewards for players standing in an AFK zone.", "minecraft:red_bed", EXTRA, false,
			List.of(), List.of("crates"), List.of("afkzone")),
		m("events", "Lottery & events", "Lottery and server events (market day, gold rush, lucky hour).", "minecraft:firework_star", EXTRA, false,
			List.of(), List.of(), List.of("event", "lottery", "rank")),
		m("news", "Breaking news", "Random market events that move prices for a while.", "minecraft:filled_map", EXTRA, false,
			List.of(), List.of("market", "orders"), List.of("news")),
		m("holo", "Holograms", "Admin-placed holograms and leaderboard holograms.", "minecraft:glow_item_frame", CORE, false,
			List.of(), List.of("stats", "bank"), List.of("holo")),
		m("missions", "Missions", "Missions board with tiers and the daily elite mission.", "minecraft:target", CORE, false,
			List.of(), List.of("crates"), List.of("missions")),
		m("casino", "Casino", "House games: blackjack, roulette, slots, crash, plinko, dice, wheel and more.", "minecraft:gold_block", EXTRA, false,
			List.of(), List.of(), List.of("casino", "blackjack", "bj", "roulette", "slots", "crash", "plinko", "dice", "wheel", "scratch", "hilo", "mines", "horserace")),
		m("bank", "Bank", "Bank deposits with interest.", "minecraft:gold_nugget", CORE, false,
			List.of(), List.of(), List.of("bank")),
		m("clans", "Clans", "Clans with clan chat, clan bank and teleport.", "minecraft:white_banner", EXTRA, false,
			List.of(), List.of("bank"), List.of("clan", "cc")),
		m("menu", "Menu", "/menu main menu and /commands help.", "minecraft:nether_star", CORE, false,
			List.of(), List.of(), List.of("menu", "commands")),
		m("npc", "NPCs", "Admin-placed NPCs that open shops and menus.", "minecraft:villager_spawn_egg", CORE, false,
			List.of(), List.of(), List.of()));

	private static final Map<String, ModuleInfo> BY_ID = new LinkedHashMap<>();

	static {
		for (ModuleInfo info : ALL) {
			if (BY_ID.put(info.id(), info) != null) throw new IllegalStateException("Duplicate module id " + info.id());
		}
		for (ModuleInfo info : ALL) {
			for (String r : info.requires()) {
				if (!BY_ID.containsKey(r)) throw new IllegalStateException("Module " + info.id() + " requires unknown module " + r);
			}
		}
	}

	private ModuleCatalog() {}

	private static ModuleInfo m(String id, String name, String description, String icon, ModuleGroup group, boolean locked,
			List<String> requires, List<String> uses, List<String> commands) {
		return new ModuleInfo(id, name, description, icon, group, locked, requires, uses, commands);
	}

	public static Optional<ModuleInfo> get(String id) {
		return Optional.ofNullable(id == null ? null : BY_ID.get(id));
	}

	public static boolean exists(String id) {
		return id != null && BY_ID.containsKey(id);
	}

	public static List<ModuleInfo> dependents(String id) {
		return ALL.stream().filter(m -> m.requires().contains(id)).toList();
	}
}
