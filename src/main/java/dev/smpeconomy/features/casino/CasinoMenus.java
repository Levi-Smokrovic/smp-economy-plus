package dev.smpeconomy.features.casino;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Treasury;
import dev.smpeconomy.features.casino.CasinoData.GameStats;
import dev.smpeconomy.features.casino.CasinoData.PlayerStats;
import dev.smpeconomy.features.casino.games.blackjack.BlackjackGame;
import dev.smpeconomy.features.casino.games.crash.CrashGame;
import dev.smpeconomy.features.casino.games.dice.DiceGame;
import dev.smpeconomy.features.casino.games.horserace.HorseRace;
import dev.smpeconomy.features.casino.games.plinko.Plinko;
import dev.smpeconomy.features.casino.games.roulette.RouletteGame;
import dev.smpeconomy.features.casino.games.scratch.ScratchGame;
import dev.smpeconomy.features.casino.games.wheel.WheelGame;

/** {@code /casino} (main menu) and {@code /casino stats}. Display-only GUIs without real slots. */
final class CasinoMenus {
	static final String MENU_TITLE = "§8Casino";
	static final String STATS_TITLE = "§8Casino Stats";
	// Hub (5 rows): one fixed spot per game. Row 2: the built-in games on 10/12/14/16, with blackjack, roulette and
	// dice between them; row 3 centred: the other games; row 4: limits, stats, daily limit, house.
	static final int SLOT_COINFLIP = 10;
	static final int SLOT_BLACKJACK = 11;
	static final int SLOT_SLOTS = 12;
	static final int SLOT_ROULETTE = 13;
	static final int SLOT_MINES = 14;
	static final int SLOT_DICE = 15;
	static final int SLOT_HILO = 16;
	static final int SLOT_CRASH = 20;
	static final int SLOT_PLINKO = 21;
	static final int SLOT_WHEEL = 22;
	static final int SLOT_SCRATCH = 23;
	static final int SLOT_HORSERACE = 24;
	static final int SLOT_LIMITS = 28;
	static final int SLOT_STATS = 30;
	static final int SLOT_DAILY = 32;
	static final int SLOT_HOUSE = 34;
	static final int SLOT_MENU = 36;

	private CasinoMenus() {}

	static void openMenu(ServerPlayer player) {
		if (!Gui.canOpen(player)) return;
		UUID id = player.getUUID();
		SimpleGui gui = Gui.chest(player, 5, MENU_TITLE);
		Gui.frame(gui);
		CasinoConfig c = Casino.cfg();
		gui.setSlot(SLOT_COINFLIP, gameButton(player, Game.COINFLIP, () -> CoinflipGui.open(player, null),
			"§7Flip a coin against the house: §f48% §7for ×2", "§7Then §6Double or Nothing§7.", "§8/coinflip house <amount>"));
		gui.setSlot(SLOT_SLOTS, gameButton(player, Game.SLOTS, () -> SlotsGui.open(player, null),
			"§73 reels, up to §a×25§7.", "§8Payout rate: ~" + String.format(java.util.Locale.ROOT, "%.0f", CasinoMath.slotsTheoreticalRtp() * 100) + "%",
			"§8/slots <bet>"));
		gui.setSlot(SLOT_MINES, gameButton(player, Game.MINES, () -> MinesGui.open(player, null, null),
			"§75×5 tiles, avoid the bombs,", "§7the multiplier rises, cash out anytime.", "§8/mines <bet> [bombs]"));
		gui.setSlot(SLOT_HILO, gameButton(player, Game.HILO, () -> HiloGui.open(player, null),
			"§7Is the next card higher or lower?", "§7Chain guesses for a rising multiplier.", "§8/hilo <bet>"));
		gui.setSlot(SLOT_BLACKJACK, BlackjackGame.hubButton(player));
		gui.setSlot(SLOT_ROULETTE, RouletteGame.hubButton(player));
		gui.setSlot(SLOT_PLINKO, Plinko.hubButton(player));
		gui.setSlot(SLOT_DICE, DiceGame.hubButton(player));
		gui.setSlot(SLOT_WHEEL, WheelGame.hubButton(player));
		gui.setSlot(SLOT_SCRATCH, ScratchGame.hubButton(player));
		gui.setSlot(SLOT_CRASH, CrashGame.hubButton(player));
		gui.setSlot(SLOT_HORSERACE, HorseRace.hubButton(player));

		long maxBet = Casino.maxBet();
		gui.setSlot(SLOT_LIMITS, Gui.item(Items.GOLD_INGOT, "§eLimits",
			"§7Min bet: " + Msg.moneyStr(c.minBet),
			"§7Max bet: " + (maxBet >= c.minBet ? Msg.moneyStr(maxBet) : "§clocked") + " §8(≤ " + Casino.fmtPercent(c.effectiveMaxBetPercent()) + " of the Treasury)",
			"§7Max win per round: " + Msg.moneyStr(Casino.coverLimit(null)),
			"§7Treasury: " + Msg.moneyStr(Treasury.balance()),
			c.enabled ? "" : "§cThe casino is closed."));
		gui.setSlot(SLOT_STATS, Gui.button(Items.BOOK, "§eYour Stats", () -> openStats(player, id, true), "§7Rounds, bets, wins"));
		gui.setSlot(SLOT_DAILY, Gui.item(Items.CLOCK, "§eDaily Limit",
			"§7Loss limit today: " + Msg.moneyStr(Casino.lossLimit(id)),
			"§7Lost so far: " + Msg.moneyStr(Math.max(0, Casino.netLossToday(id))),
			"§7Left today: " + Msg.moneyStr(Casino.lossRoom(id)),
			"",
			"§8Your own limit: /casino limit <amount>",
			"§8Lowering is instant, raising starts tomorrow."));
		Treasury.Flow flow = Treasury.totalsAllTime().getOrDefault(Casino.REASON, Treasury.Flow.ZERO);
		gui.setSlot(SLOT_HOUSE, Gui.item(Items.GOLD_BLOCK, "§6The House",
			"§7Total bets: " + Msg.moneyStr(flow.income()),
			"§7Total payouts: " + Msg.moneyStr(flow.expense()),
			"§7House profit: " + (flow.net() >= 0 ? "§a" : "§c") + Money.format(flow.net())));
		Gui.menuButton(gui, SLOT_MENU);
		gui.setSlot(40, Gui.closeButton(gui));
		Gui.fillEmpty(gui);
		gui.open();
	}

	private static GuiElementBuilder gameButton(ServerPlayer player, Game game, Runnable open, String... lore) {
		List<String> lines = new ArrayList<>(List.of(lore));
		long max = Casino.maxStake(player.getUUID(), game, game == Game.MINES ? Casino.cfg().minesDefaultBombs : 0);
		lines.add(max > 0 ? "§7Max bet: " + Msg.moneyStr(max) : "§cLocked right now (Treasury/limit)");
		lines.add("");
		lines.add("§eClick to play");
		return Gui.button(game.icon(), game.colored(), open, lines.toArray(String[]::new));
	}

	/**
	 * A player's stats ({@code back} = back to the menu instead of closing). Built-in games in the second row,
	 * sub-package games ({@link Casino.ExtGame}) below, 7 per row (the GUI grows to 6 rows). Crash and the horse race
	 * keep their stats themselves, in their own window.
	 */
	static void openStats(ServerPlayer viewer, UUID target, boolean back) {
		if (!Gui.canOpen(viewer)) return;
		List<Casino.ExtGame> ext = Casino.extGames();
		int rows = Math.min(6, 3 + Math.max(1, (ext.size() + 6) / 7));
		SimpleGui gui = Gui.chest(viewer, rows, STATS_TITLE);
		Gui.frame(gui);
		PlayerStats s = Casino.peekStats(target);
		String name = EconomyService.nameOrUnknown(target);
		if (s == null) s = new PlayerStats();
		long net = s.paidOut - s.wagered;
		gui.setSlot(10, Gui.item(Items.BOOK, "§eOverall §7(" + name + ")",
			"§7Rounds: §f" + Msg.number(s.rounds),
			"§7Won: §f" + Msg.number(s.wins),
			"§7Wagered: " + Msg.moneyStr(s.wagered),
			"§7Paid out: " + Msg.moneyStr(s.paidOut),
			"§7Net: " + (net >= 0 ? "§a+" : "§c") + Money.format(net),
			"§7Biggest win: " + Msg.moneyStr(s.biggestWin) + (s.biggestWinGame.isEmpty() ? "" : " §8(" + label(s.biggestWinGame) + ")")));
		int slot = 12;
		for (Game g : Game.values()) gui.setSlot(slot++, gameStats(s, g.id(), g.icon(), g.colored()));
		int maxExt = (rows - 3) * 7;
		for (int i = 0; i < ext.size() && i < maxExt; i++) {
			Casino.ExtGame x = ext.get(i);
			gui.setSlot(19 + (i / 7) * 9 + i % 7, gameStats(s, x.id(), x.icon(), x.colored()));
		}
		gui.setSlot(16, Gui.item(Items.CLOCK, "§eToday",
			"§7Lost today: " + Msg.moneyStr(Math.max(0, Casino.netLossToday(target))),
			"§7Daily limit: " + Msg.moneyStr(Casino.lossLimit(target)),
			"§7Left today: " + Msg.moneyStr(Casino.lossRoom(target))));
		int bottom = (rows - 1) * 9;
		if (back) gui.setSlot(bottom, Gui.backButton(() -> openMenu(viewer)));
		gui.setSlot(bottom + 4, Gui.closeButton(gui));
		Gui.fillEmpty(gui);
		gui.open();
	}

	private static GuiElementBuilder gameStats(PlayerStats s, String gameId, Item icon, String colored) {
		GameStats gs = s.games.getOrDefault(gameId, new GameStats());
		long gNet = gs.paidOut - gs.wagered;
		return Gui.item(icon, colored,
			"§7Rounds: §f" + Msg.number(gs.rounds) + " §8· §7Won: §f" + Msg.number(gs.wins),
			"§7Wagered: " + Msg.moneyStr(gs.wagered),
			"§7Paid out: " + Msg.moneyStr(gs.paidOut),
			"§7Net: " + (gNet >= 0 ? "§a+" : "§c") + Money.format(gNet),
			"§7Best multiplier: §e" + CasinoMath.formatMultiplier(gs.bestMultiplier));
	}

	private static String label(String gameId) {
		Game g = Game.byId(gameId);
		if (g != null) return g.label();
		Casino.ExtGame x = Casino.extGame(gameId);
		return x == null ? gameId : x.label();
	}
}
