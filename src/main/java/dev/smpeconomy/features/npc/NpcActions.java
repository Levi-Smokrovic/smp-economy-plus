package dev.smpeconomy.features.npc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.server.MinecraftServer;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.CommandRegistry;
import dev.smpeconomy.core.Feature;

/**
 * Menu targets for NPC actions ({@code "type": "open"}): module, command run as the player, and a template for name,
 * description, skin and equipment. A target is only available if its module runs and its command exists; otherwise
 * the player gets "That isn't available on this server." and nothing runs.
 */
final class NpcActions {
	static final String UNAVAILABLE = "That isn't available on this server.";

	/** {@code equipment}: slot to item, same as in npc.json. */
	record Preset(String target, String module, String command, String name, String description, String skin, Map<String, String> equipment) {}

	private static final Map<String, Preset> PRESETS = new LinkedHashMap<>();

	static {
		add("shop", "shop", "shop", "§aShop", "§7Buy items", "wide/steve", "mainhand", "emerald");
		add("sell", "sell", "sell", "§6Sell", "§7Sell your items", "wide/kai", "mainhand", "hopper");
		add("ah", "auction", "ah", "§eAuction House", "§7Trade with other players", "slim/alex", "mainhand", "gold_ingot");
		add("orders", "orders", "orders", "§bOrders", "§7Buy orders · fill them for money", "wide/noor", "mainhand", "writable_book");
		add("bank", "bank", "bank", "§6Banker", "§7Deposit · earn interest", "slim/makena", "mainhand", "gold_block");
		add("jobs", "jobs", "jobs", "§eJobs", "§7Pick a job · earn while you play", "wide/efe", "mainhand", "iron_pickaxe");
		add("missions", "missions", "missions", "§bMissions", "§7Bigger tasks · more money", "slim/zuri", "mainhand", "map");
		add("tasks", "tasks", "tasks", "§aDaily Tasks", "§73 tasks per day", "wide/sunny", "mainhand", "book");
		add("daily", "daily", "daily", "§6Daily Reward", "§7Claim it every day", "slim/ari", "mainhand", "clock");
		add("crates", "crates", "crates", "§dCrates", "§7Redeem your keys", "wide/kai", "mainhand", "tripwire_hook");
		add("casino", "casino", "casino", "§5Casino", "§7Try your luck", "wide/noor", "mainhand", "nether_star");
		add("lottery", "events", "lottery", "§dLottery", "§7Buy tickets · daily draw", "slim/alex", "mainhand", "paper");
		add("event", "events", "event", "§bEvents", "§7Current event · trader rank", "wide/efe", "mainhand", "firework_rocket");
		add("bounty", "bounty", "bounty", "§cBounty Board", "§7Place and claim bounties", "wide/steve", "mainhand", "iron_sword");
		add("clan", "clans", "clan", "§9Clans", "§7Create or join a clan", "slim/makena", "mainhand", "white_banner");
		add("market", "market", "market", "§aMarket", "§7Market values and trends", "wide/sunny", "mainhand", "emerald_block");
		add("coinflip", "minigames", "coinflip", "§eCoinflip", "§7Flip a coin against players", "slim/zuri", "mainhand", "sunflower");
		add("stats", "stats", "stats", "§eStats", "§7Your stats and ranks", "slim/ari", "mainhand", "book");
		add("news", "news", "news", "§cNews", "§7Breaking news", "wide/kai", "mainhand", "bell");
		add("settings", "settings", "settings", "§7Settings", "§7Sidebar, notifications …", "wide/noor", "mainhand", "comparator");
		add("menu", "menu", "menu", "§6Guide", "§7Open the main menu", "wide/steve", "mainhand", "compass");
	}

	private NpcActions() {}

	private static void add(String target, String module, String command, String name, String description, String skin, String... equipment) {
		Map<String, String> eq = new LinkedHashMap<>();
		for (int i = 0; i + 1 < equipment.length; i += 2) eq.put(equipment[i], equipment[i + 1]);
		PRESETS.put(target, new Preset(target, module, command, name, description, skin, Map.copyOf(eq)));
	}

	static Preset preset(String target) {
		return target == null ? null : PRESETS.get(target);
	}

	static List<String> targets() {
		return List.copyOf(PRESETS.keySet());
	}

	static List<Preset> presets() {
		return List.copyOf(PRESETS.values());
	}

	static List<String> availableTargets(MinecraftServer server) {
		List<String> out = new ArrayList<>();
		for (Preset p : PRESETS.values()) if (available(server, p)) out.add(p.target());
		return out;
	}

	/** With a null server only the module is checked. */
	static boolean available(MinecraftServer server, Preset p) {
		return p != null && moduleRunning(p.module()) && (server == null || CommandRegistry.root().getChild(p.command()) != null);
	}

	static boolean moduleRunning(String module) {
		for (Feature f : SmpEconomy.features()) if (f.id().equals(module)) return true;
		return false;
	}

	/** {@code command} runs as the player (empty means none); {@code error} is a message instead. */
	record Resolved(String command, String error) {}

	/**
	 * {@code available} decides whether a menu target is available right now:
	 * {@link #available(MinecraftServer, Preset)} in game, custom rules in selftests.
	 */
	static Resolved resolve(NpcConfig.Action a, String playerName, Predicate<Preset> available) {
		if (a == null) return new Resolved("", null);
		return switch (a.type) {
			case NpcConfig.Action.OPEN -> {
				Preset p = preset(a.target);
				if (p == null || !available.test(p)) yield new Resolved("", UNAVAILABLE);
				yield new Resolved(a.arg.isEmpty() ? p.command() : p.command() + " " + a.arg, null);
			}
			case NpcConfig.Action.COMMAND -> new Resolved(a.command.replace("{player}", playerName), null);
			default -> new Resolved("", null);
		};
	}
}
