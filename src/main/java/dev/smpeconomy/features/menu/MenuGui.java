package dev.smpeconomy.features.menu;

import com.mojang.brigadier.tree.CommandNode;
import eu.pb4.sgui.api.gui.SimpleGui;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.CommandRegistry;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Services;

/** Entries only call commands, on the server thread, as the clicking player. */
final class MenuGui {
	static final List<String> MENU_COMMANDS = List.of("menu");
	static final String HELP_COMMAND = "commands";
	static final List<String> HELP_ALIASES = List.of();
	static final String TITLE = "§6§lServer §8» §0Main Menu";
	static final int ROWS = 5;
	static final int INFO_SLOT = 4;
	static final int HELP_SLOT = 39;
	static final int CLOSE_SLOT = 41;

	/** {@code command} without "/", first word is the root command. */
	record Entry(int slot, String command, Item icon, String name, String... lore) {
		String root() {
			int sp = command.indexOf(' ');
			return sp < 0 ? command : command.substring(0, sp);
		}
	}

	/**
	 * {@code args}: needs arguments, so a click only fills the chat line. {@code path} (optional, command words
	 * separated by spaces): shown only if the path exists and the source may use every level; for subcommands with
	 * their own permission under a root (e.g. {@code /chatgame mega}). Chat then shows the full {@code usage} rather
	 * than just the root.
	 */
	record HelpCmd(String usage, boolean args, String description, String path) {
		HelpCmd(String usage, boolean args, String description) {
			this(usage, args, description, null);
		}

		String root() {
			int sp = usage.indexOf(' ');
			return sp < 0 ? usage : usage.substring(0, sp);
		}

		/** Without {@code path} just the root. */
		List<String> checkPath() {
			return path == null ? List.of(root()) : List.of(path.trim().split(" +"));
		}
	}

	record HelpGroup(String title, List<HelpCmd> commands) {}

	static final List<Entry> ENTRIES = List.of(
		// Row 1: trade and money
		new Entry(10, "shop", Items.EMERALD, "§aShop", "§7Buy items from the server", "§7(right-click in the shop: sell)"),
		new Entry(11, "sell", Items.HOPPER, "§6Sell", "§7Sell items to the server", "§8also /sell hand, /sell all"),
		new Entry(12, "ah", Items.GOLD_INGOT, "§eAuction House", "§7Trade with players and NPC traders", "§8to sell: hold the item, open /ah", "§8and click \"Sell item\"", "§8also /auction"),
		new Entry(13, "orders", Items.WRITABLE_BOOK, "§bOrders", "§7View and fill buy orders", "§7or place your own"),
		new Entry(14, "bank", Items.GOLD_BLOCK, "§6Bank", "§7Deposit money – earn daily interest"),
		new Entry(15, "lottery", Items.PAPER, "§dLottery", "§7Buy tickets – daily draw"),
		new Entry(16, "casino", Items.NETHER_STAR, "§5Casino", "§7Play against the house: Blackjack,", "§7Roulette, Slots, Crash, Plinko and more"),
		// Row 2: earning
		new Entry(19, "jobs", Items.IRON_PICKAXE, "§eJobs", "§7Pick a job and earn while you play"),
		new Entry(20, "missions", Items.MAP, "§bMissions", "§7Bigger tasks – way more money"),
		new Entry(21, "tasks", Items.BOOK, "§aDaily Tasks", "§73 tasks per day + bonus"),
		new Entry(22, "daily", Items.CLOCK, "§6Daily Reward", "§7Claim it every day – streak up to day 7"),
		new Entry(23, "crates", Items.CHEST, "§dCrates", "§7Redeem your keys"),
		new Entry(24, "bounty", Items.SKELETON_SKULL, "§cBounty", "§7View and place bounties"),
		new Entry(25, "clan", Items.WHITE_BANNER, "§9Clan", "§7Create, join and manage a clan"),
		// Row 3 left: market, player games, events
		new Entry(28, "market", Items.EMERALD_BLOCK, "§aMarket", "§7Market values with trends,", "§7NPC buyers and traders", "§8Value of your held item: /market value"),
		new Entry(29, "coinflip", Items.SUNFLOWER, "§eCoinflip", "§7Flip a coin against other players", "§8create one: /coinflip <amount>"),
		new Entry(30, "event", Items.FIREWORK_ROCKET, "§bEvents & Trader Rank", "§7Current event (e.g. Market Day),", "§7your Trader Rank, Lottery status"),
		// Row 3 right: info and settings
		new Entry(32, "stats", Items.PLAYER_HEAD, "§eStats", "§7Your stats and ranks"),
		new Entry(33, "news", Items.BELL, "§cNews", "§7Breaking News and announcements"),
		new Entry(34, "settings", Items.COMPARATOR, "§7Settings", "§7Sidebar, mob spawning, notifications …"));

	static final List<HelpGroup> HELP = List.of(
		new HelpGroup("Menu", List.of(
			new HelpCmd("menu", false, "Main Menu with all features"),
			new HelpCmd("commands", false, "This command list"))),
		new HelpGroup("Money", List.of(
			new HelpCmd("balance", false, "Your balance (also /bal, /money)"),
			new HelpCmd("pay <player> <amount>", true, "Send money"),
			new HelpCmd("baltop", false, "Richest players"),
			new HelpCmd("bank", false, "Bank account with interest"),
			new HelpCmd("treasury", false, "Treasury balance"),
			new HelpCmd("paytoggle", false, "Turn incoming payments on/off"))),
		new HelpGroup("Trading", List.of(
			new HelpCmd("shop", false, "Buy from the server"),
			new HelpCmd("sell", false, "Sell to the server (also hand/all)"),
			new HelpCmd("worth", false, "Value of the item in your hand"),
			new HelpCmd("ah", false, "Auction House (also /auction; to sell: hold the item, open /ah and click \"Sell item\", or /ah sell <total price> for the whole stack)"),
			new HelpCmd("orders", false, "Buy orders"),
			new HelpCmd("market", false, "Market values and NPC traders"))),
		new HelpGroup("Earning", List.of(
			new HelpCmd("jobs", false, "Jobs"),
			new HelpCmd("tasks", false, "Daily Tasks"),
			new HelpCmd("missions", false, "Mission board"),
			new HelpCmd("daily", false, "Daily reward"),
			new HelpCmd("crates", false, "Redeem crate keys"),
			new HelpCmd("afkzone", false, "AFK zone: your status"))),
		new HelpGroup("Games", List.of(
			new HelpCmd("coinflip", false, "Coinflip against players (also /cf)"),
			new HelpCmd("casino", false, "Casino menu"),
			new HelpCmd("slots", false, "Slots"),
			new HelpCmd("mines", false, "Mines"),
			new HelpCmd("hilo", false, "Higher/Lower"),
			new HelpCmd("blackjack", false, "Blackjack against the house (also /bj)"),
			new HelpCmd("roulette", false, "Roulette (European, single zero)"),
			new HelpCmd("dice", false, "Dice: roll over or under your number"),
			new HelpCmd("crash", false, "Crash: cash out before the rocket crashes"),
			new HelpCmd("plinko", false, "Plinko: drop a ball onto a multiplier"),
			new HelpCmd("wheel", false, "Wheel of Fortune, up to 50×"),
			new HelpCmd("scratch", false, "Scratch cards: Bronze, Silver, Gold"),
			new HelpCmd("horserace", false, "Horse race: one race for everyone"),
			new HelpCmd("lottery", false, "Lottery"),
			new HelpCmd("treasure", false, "Treasure Hunt"),
			new HelpCmd("chatgame", false, "Chat games: current game, play on/off"),
			new HelpCmd("chatgame mega", false, "MEGA-CHALLENGE: status and your progress", "chatgame mega"))),
		new HelpGroup("Players", List.of(
			new HelpCmd("clan", false, "Clans"),
			new HelpCmd("cc <message>", true, "Clan chat"),
			new HelpCmd("bounty", false, "Bounties"),
			new HelpCmd("stats", false, "Stats"),
			new HelpCmd("leaderboard <category>", true, "Leaderboards: money, kills, playtime, deaths"),
			new HelpCmd("playtime", false, "Playtime"),
			new HelpCmd("rank", false, "Your Trader Rank"))),
		new HelpGroup("Server", List.of(
			new HelpCmd("news", false, "Breaking News"),
			new HelpCmd("event", false, "Server events, Trader Rank, Lottery"),
			new HelpCmd("settings", false, "Settings"),
			new HelpCmd("sidebar", false, "Sidebar on/off"))));

	private MenuGui() {}

	static void open(ServerPlayer player) {
		SimpleGui gui = Gui.chest(player, ROWS, TITLE);
		gui.setSlot(INFO_SLOT, infoItem(player));
		for (Entry e : ENTRIES) {
			String command = e.command();
			// Modules the server disabled don't appear at all, rather than grayed out.
			if (dev.smpeconomy.core.module.Modules.disabledOwnerOf(e.root()).isPresent()) continue;
			if (available(player, command.split(" ", 2)[0])) {
				List<String> lore = new ArrayList<>(List.of(e.lore()));
				lore.add("");
				lore.add("§eClick to open §8(/" + command + ")");
				gui.setSlot(e.slot(), Gui.button(e.icon(), e.name(), () -> run(player, command), lore.toArray(String[]::new)));
			} else {
				gui.setSlot(e.slot(), Gui.item(Items.GRAY_DYE, "§8" + strip(e.name()), "§8Not available right now."));
			}
		}
		gui.setSlot(HELP_SLOT, Gui.button(Items.KNOWLEDGE_BOOK, "§eAll Commands", () -> {
			gui.close();
			sendHelp(player.createCommandSourceStack());
		}, "§7List of all commands in chat", "§8/" + HELP_COMMAND));
		gui.setSlot(CLOSE_SLOT, Gui.closeButton(gui));
		Gui.frame(gui);
		Gui.fillEmpty(gui);
		gui.open();
	}

	private static eu.pb4.sgui.api.elements.GuiElementBuilder infoItem(ServerPlayer player) {
		List<String> lore = new ArrayList<>();
		lore.add("§7Balance: §a" + Money.format(EconomyService.balance(player.getUUID())));
		Optional<Services.Bank> bank = Services.find(Services.Bank.class);
		if (bank.isPresent()) {
			try {
				lore.add("§7Bank: §a" + Money.format(bank.get().balance(player.getUUID())));
			} catch (RuntimeException e) {
				SmpEconomy.LOG.error("[SMPEco] menu: bank service threw", e);
			}
		}
		return Gui.item(Items.NETHER_STAR, "§6§l" + player.getGameProfile().name(), lore);
	}

	/** Opens e.g. the feature's GUI, which replaces this menu. */
	static void run(ServerPlayer player, String command) {
		MinecraftServer server = player.level().getServer();
		if (server == null || !Gui.canOpen(player)) return;
		if (!available(player, command.split(" ", 2)[0])) {
			Gui.failSound(player);
			Msg.error(player, "That isn't available right now.");
			return;
		}
		CommandRegistry.run(player, command);
	}

	static boolean available(ServerPlayer player, String root) {
		return available(player.level().getServer(), player.createCommandSourceStack(), root);
	}

	static boolean available(MinecraftServer server, CommandSourceStack src, String root) {
		return available(server, src, List.of(root));
	}

	static boolean available(MinecraftServer server, CommandSourceStack src, List<String> path) {
		if (server == null || path.isEmpty()) return false;
		CommandNode<CommandSourceStack> node = CommandRegistry.root();
		for (String word : path) {
			node = node.getChild(word);
			if (node == null || !node.canUse(src)) return false;
		}
		return true;
	}

	private static String strip(String legacy) {
		return legacy.replaceAll("§.", "");
	}

	static void sendHelp(CommandSourceStack src) {
		MinecraftServer server = src.getServer();
		Msg.send(src, Msg.prefixed("§eCommands §8(click one – hover for a description)"));
		for (HelpGroup g : HELP) {
			MutableComponent line = Msg.legacy("§6" + g.title() + "§8: ");
			int shown = 0;
			for (HelpCmd c : g.commands()) {
				String usage = c.usage();
				String root = usage.split(" ", 2)[0];
				if (!available(server, src, c.path() == null ? List.of(root) : c.checkPath())) continue;
				if (shown++ > 0) line.append(Msg.legacy(" "));
				String text = "§e/" + (c.path() == null ? root : usage);
				String hover = "§f/" + usage + "\n§7" + c.description() + "\n§8" + (c.args() ? "Click to type it in chat" : "Click to run");
				line.append(c.args() ? Msg.clickSuggest(text, "/" + root + " ", hover) : Msg.clickRun(text, "/" + root, hover));
			}
			if (shown > 0) Msg.send(src, line);
		}
	}

	static List<String> helpRoots() {
		List<String> roots = new ArrayList<>();
		for (HelpGroup g : HELP) for (HelpCmd c : g.commands()) roots.add(c.root());
		return roots;
	}

	static Component title() {
		return Msg.legacy(TITLE);
	}
}
