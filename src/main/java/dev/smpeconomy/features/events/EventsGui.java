package dev.smpeconomy.features.events;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Perms;
import dev.smpeconomy.core.PriceGuard;
import dev.smpeconomy.core.TimeUtil;

/**
 * Display-only GUIs without real slots; purchases go through {@link Gui#button} (click filter and debounce).
 */
public final class EventsGui {
	static final int HUB_EVENT = 11, HUB_RANK = 13, HUB_LOTTO = 15, HUB_MENU = 18, HUB_CLOSE = 22;
	/**
	 * Lottery GUI slots (5 rows): jackpot on top, below it prizes, sold and own chance, buy 1/5/10/max, own
	 * tickets, rules, history (up to 7 draws), back or main menu, close.
	 */
	static final int LOTTO_ROWS = 5;
	static final int[] LOTTO_BUY_SLOTS = {19, 20, 21, 22};
	static final int[] LOTTO_BUY_COUNTS = {1, 5, 10, -1};
	static final int LOTTO_JACKPOT = 4, LOTTO_PRIZES = 11, LOTTO_SOLD = 13, LOTTO_CHANCE = 15, LOTTO_MINE = 24, LOTTO_RULES = 25,
		LOTTO_BACK = 36, LOTTO_CLOSE = 40;
	static final int[] LOTTO_HISTORY_SLOTS = {28, 29, 30, 31, 32, 33, 34};

	private static final Item[] RANK_ICONS = {
		net.minecraft.world.item.Items.IRON_NUGGET, net.minecraft.world.item.Items.IRON_INGOT, net.minecraft.world.item.Items.GOLD_INGOT,
		net.minecraft.world.item.Items.DIAMOND, net.minecraft.world.item.Items.NETHERITE_INGOT};
	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("MMM d, HH:mm", java.util.Locale.ENGLISH);
	static final String LOTTO_DENIED = "The lottery isn't unlocked for you.";

	private EventsGui() {}

	/**
	 * {@code /lotto} requires {@code smpeconomy.lottery}; the {@code /event} overview (only {@code
	 * smpeconomy.events}) and a still open lottery GUI must not bypass that.
	 */
	static boolean mayLotto(ServerPlayer player) {
		return Perms.check(player, EventsCommands.NODE_LOTTO);
	}

	public static void openHub(ServerPlayer player) {
		if (!Gui.canOpen(player)) return;
		SimpleGui gui = Gui.chest(player, 3, "§8Server Events");
		Gui.frame(gui);
		long now = System.currentTimeMillis();
		gui.setSlot(HUB_EVENT, eventItem(player, now));
		gui.setSlot(HUB_RANK, rankItem(player.getUUID(), true).setCallback((i, t, a, g) -> {
			if (!Gui.acceptClick(player, i, t)) return;
			Gui.click(player);
			openRanks(player, () -> openHub(player));
		}));
		if (mayLotto(player)) {
			gui.setSlot(HUB_LOTTO, lottoSummary(player.getUUID(), now, true).setCallback((i, t, a, g) -> {
				if (!Gui.acceptClick(player, i, t)) return;
				Gui.click(player);
				openLotto(player, () -> openHub(player));
			}));
		} else {
			// Without smpeconomy.lottery display only: no click, no lottery GUI
			gui.setSlot(HUB_LOTTO, lottoSummary(player.getUUID(), now, false));
		}
		Gui.menuButton(gui, HUB_MENU);
		gui.setSlot(HUB_CLOSE, Gui.closeButton(gui));
		Gui.fillEmpty(gui);
		gui.open();
	}

	private static GuiElementBuilder eventItem(ServerPlayer player, long now) {
		Optional<EventManager.Active> active = EventsFeature.EVENTS.active(now);
		EventsConfig cfg = EventsFeature.CONFIG.get();
		if (active.isEmpty()) {
			List<String> lore = new ArrayList<>();
			long next = EventsFeature.EVENTS.nextEventAt();
			if (cfg.randomEvents && next > now) lore.add("§7Next event in about §e" + TimeUtil.formatDuration(roundUp(next - now)));
			else if (cfg.randomEvents) lore.add("§7Next event: §esoon");
			lore.add("");
			lore.add("§bMarket Day §8– §7one category sells for more");
			lore.add("§eGold Rush §8– §7one job earns §a×" + EventsRuntime.factor(cfg.goldRushMultiplier));
			lore.add("§dLucky Hour §8– §7chat games " + EventsRuntime.frequencyText(cfg) + " §7& §a×" + EventsRuntime.factor(cfg.luckyHourMultiplier));
			return Gui.item(net.minecraft.world.item.Items.CLOCK, "§7No event right now", lore);
		}
		EventManager.Active a = active.get();
		List<String> lore = new ArrayList<>();
		switch (a.type()) {
			case MARKET_DAY -> {
				lore.add("§7Category: §f" + EventsRuntime.categoryName(a.param()));
				lore.add("§7Bonus: §a×" + EventsRuntime.factor(cfg.marketDayMultiplier) + " §7when selling (/sell)");
				lore.add("§7Items: §f" + cfg.categoryItems(a.param()).size());
			}
			case GOLD_RUSH -> {
				lore.add("§7Job: §f" + EventsRuntime.jobName(a.param()));
				lore.add("§7Bonus: §a×" + EventsRuntime.factor(cfg.goldRushMultiplier) + " §7on job payouts");
			}
			case LUCKY_HOUR -> {
				lore.add("§7Chat games come " + EventsRuntime.frequencyText(cfg));
				lore.add("§7Rewards: §a×" + EventsRuntime.factor(cfg.luckyHourMultiplier));
			}
		}
		lore.add("§7Ends in: §e" + TimeUtil.formatDuration(a.remaining(now)));
		if (a.type() == EventType.MARKET_DAY) {
			lore.add("");
			lore.add("§eClick: show items & prices");
			return Gui.button(a.type().icon(), a.type().color() + "§l" + a.type().label(), () -> openMarketDay(player, () -> openHub(player)),
				lore.toArray(String[]::new)).glow();
		}
		return Gui.item(a.type().icon(), a.type().color() + "§l" + a.type().label(), lore).glow();
	}

	private static long roundUp(long ms) {
		return (ms + 59_999) / 60_000 * 60_000;
	}

	static GuiElementBuilder rankItem(UUID player, boolean clickHint) {
		long sales = TraderRank.lifetimeSales(player);
		TraderRank.Rank rank = TraderRank.rank(player);
		Optional<TraderRank.Rank> next = TraderRank.next(rank);
		List<String> lore = new ArrayList<>();
		lore.add("§7Sell bonus: §a" + rank.bonusLabel());
		lore.add("§7Rank sales: " + Msg.moneyStr(sales));
		if (next.isPresent()) {
			double p = TraderRank.progress(sales, rank, next);
			lore.add(progressBar(p) + " §7" + Math.round(p * 100) + "%");
			lore.add("§7Next rank: " + next.get().display() + " §7at " + Msg.moneyStr(next.get().minSales()));
			lore.add("§7To go: " + Msg.moneyStr(next.get().minSales() - sales));
		} else {
			lore.add("§6Top rank reached!");
		}
		lore.add("§8Counts: sales to the server; shop items");
		lore.add("§8near the price cap only count partly.");
		if (clickHint) {
			lore.add("");
			lore.add("§eClick: all ranks");
		}
		return Gui.item(rankIcon(rank.index()), "§6Trader Rank: " + rank.display(), lore);
	}

	static Item rankIcon(int index) {
		return index >= 0 && index < RANK_ICONS.length ? RANK_ICONS[index] : net.minecraft.world.item.Items.EMERALD;
	}

	static String progressBar(double p) {
		int filled = (int) Math.round(Math.max(0, Math.min(1, p)) * 10);
		return "§a" + "■".repeat(filled) + "§8" + "■".repeat(10 - filled);
	}

	public static void openRanks(ServerPlayer player, Runnable back) {
		if (!Gui.canOpen(player)) return;
		SimpleGui gui = Gui.chest(player, 3, "§8Trader Ranks");
		Gui.frame(gui);
		List<TraderRank.Rank> ranks = TraderRank.ranks();
		TraderRank.Rank mine = TraderRank.rank(player.getUUID());
		long sales = TraderRank.lifetimeSales(player.getUUID());
		int start = 13 - Math.min(7, ranks.size()) / 2;
		for (int i = 0; i < ranks.size() && i < 7; i++) {
			TraderRank.Rank r = ranks.get(i);
			List<String> lore = new ArrayList<>();
			lore.add("§7Required sales: " + Msg.moneyStr(r.minSales()));
			lore.add("§7Sell bonus: §a" + r.bonusLabel());
			lore.add("");
			if (r.index() == mine.index()) lore.add("§a» Your rank");
			else if (sales >= r.minSales()) lore.add("§2✔ Reached");
			else lore.add("§7" + Msg.moneyStr(r.minSales() - sales) + " to go");
			GuiElementBuilder b = Gui.item(rankIcon(r.index()), r.display(), lore);
			if (r.index() == mine.index()) b.glow();
			gui.setSlot(start + i, b);
		}
		gui.setSlot(4, rankItem(player.getUUID(), false));
		if (back != null) gui.setSlot(18, Gui.backButton(back));
		gui.setSlot(22, Gui.closeButton(gui));
		Gui.fillEmpty(gui);
		gui.open();
	}

	public static void openMarketDay(ServerPlayer player, Runnable back) {
		if (!Gui.canOpen(player)) return;
		long now = System.currentTimeMillis();
		Optional<EventManager.Active> active = EventsFeature.EVENTS.active(now);
		if (active.isEmpty() || active.get().type() != EventType.MARKET_DAY) {
			Msg.error(player, "There's no Market Day running right now.");
			return;
		}
		String category = active.get().param();
		List<Item> items = new ArrayList<>(EventsFeature.CONFIG.get().categoryItems(category));
		items.sort(Comparator.comparing(Items::id));
		SimpleGui gui = Gui.chest(player, 6, "§8Market Day: " + EventsRuntime.categoryName(category));
		Gui.Pager<Item> pager = new Gui.Pager<>(gui, () -> items, item -> marketItem(player.getUUID(), item));
		if (back != null) gui.setSlot(45, Gui.backButton(back));
		pager.show(0);
		gui.open();
	}

	private static GuiElementBuilder marketItem(UUID player, Item item) {
		PriceGuard.Breakdown b = PriceGuard.breakdown(player, item);
		GuiElementBuilder e = new GuiElementBuilder(item).setName(Msg.itemName(item).withStyle(ChatFormatting.WHITE)).hideDefaultTooltip();
		List<String> lore = new ArrayList<>();
		if (b.effectiveSell() > 0) lore.add("§7Sells now for: " + Msg.moneyStr(b.effectiveSell()) + " §8each");
		else lore.add("§cCan't be sold right now");
		if (b.baseSell() > 0) lore.add("§8Base price: " + Money.format(b.baseSell()));
		lore.addAll(b.loreLines());
		return Gui.lore(e, lore.toArray(String[]::new));
	}

	static GuiElementBuilder lottoSummary(UUID player, long now, boolean clickable) {
		Lottery lotto = EventsFeature.LOTTO;
		EventsConfig cfg = EventsFeature.CONFIG.get();
		List<String> lore = new ArrayList<>();
		lore.add("§7Jackpot: §6§l" + Money.format(lotto.jackpot()));
		lore.add("§7Sold: §f" + lotto.soldCount() + "§7/§f" + lotto.ticketCount());
		lore.add("§7Your tickets: §f" + lotto.tickets(player) + "§7/§f" + cfg.lottoMaxTicketsPerPlayer);
		lore.add("§7Draw in: §e" + TimeUtil.formatDuration(Math.max(0, lotto.nextDrawAt() - now)) + " §8(" + cfg.lottoDrawTime + ")");
		if (!cfg.lottoEnabled) lore.add("§cCurrently disabled");
		else if (lotto.isReady() && !lotto.covered()) lore.add("§cTicket sales paused §8(Treasury)");
		lore.add("");
		lore.add(clickable ? "§eClick: buy tickets" : "§8Not unlocked for you.");
		return Gui.item(net.minecraft.world.item.Items.SUNFLOWER, "§6§lLottery", lore);
	}

	public static void openLotto(ServerPlayer player, Runnable back) {
		if (!Gui.canOpen(player)) return;
		if (!mayLotto(player)) {
			fail(player, LOTTO_DENIED);
			return;
		}
		SimpleGui gui = new SimpleGui(Gui.menuType(LOTTO_ROWS), player, false) {
			private int ticks;
			private String shown = lottoSignature(player);

			@Override
			public void onTick() {
				// Keep countdown, jackpot and sold live (others buy, the draw runs); redraw only when
				// something changed
				if (++ticks % 20 != 0) return;
				String now = lottoSignature(player);
				if (now.equals(shown)) return;
				shown = now;
				renderLotto(this, player, back);
			}
		};
		gui.setTitle(Msg.legacy("§8Lottery"));
		renderLotto(gui, player, back);
		gui.open();
	}

	/** Everything the lottery GUI shows (countdown at display precision, permission). */
	private static String lottoSignature(ServerPlayer player) {
		Lottery lotto = EventsFeature.LOTTO;
		EventsConfig cfg = EventsFeature.CONFIG.get();
		List<Lottery.Draw> draws = lotto.draws();
		return lotto.drawId() + "|" + lotto.jackpot() + "|" + lotto.soldCount() + "|" + lotto.participants() + "|" + lotto.ticketCount() + "|"
			+ lotto.numbers(player.getUUID()) + "|" + lotto.owedTo(player.getUUID()) + "|" + lotto.prizes() + "|"
			+ TimeUtil.formatDuration(Math.max(0, lotto.nextDrawAt() - System.currentTimeMillis())) + "|" + cfg.lottoEnabled + "|"
			+ cfg.lottoTicketPrice + "|" + cfg.lottoMaxTicketsPerPlayer + "|" + cfg.lottoJackpotBase + "|" + cfg.lottoJackpotMax + "|"
			+ (draws.isEmpty() ? 0 : draws.get(0).id) + "|" + mayLotto(player) + "|" + lotto.covered();
	}

	private static void renderLotto(SimpleGui gui, ServerPlayer player, Runnable back) {
		Lottery lotto = EventsFeature.LOTTO;
		EventsConfig cfg = EventsFeature.CONFIG.get();
		UUID id = player.getUUID();
		long now = System.currentTimeMillis();
		Gui.frame(gui, false);
		int size = lotto.ticketCount();
		int sold = lotto.soldCount();
		int left = Math.max(0, size - sold);

		List<String> jp = new ArrayList<>();
		jp.add("§7Draw §f#" + lotto.drawId() + " §7– daily at §f" + cfg.lottoDrawTime);
		jp.add("§7Next draw in: §e" + TimeUtil.formatDuration(Math.max(0, lotto.nextDrawAt() - now)));
		jp.add("§7Tickets sold: §f" + sold + "§7/§f" + size);
		jp.add("");
		jp.add("§7Not won? It grows by " + Msg.moneyStr(cfg.lottoJackpotBase) + " §7every draw");
		jp.add("§7until someone hits it §8(next: " + Money.format(lotto.nextJackpotIfNotWon()) + ")");
		if (cfg.lottoJackpotMax > 0) jp.add("§8Jackpot limit: " + Money.format(cfg.lottoJackpotMax));
		if (!cfg.lottoEnabled) jp.add("§cThe lottery is disabled right now.");
		else if (lotto.isReady() && !lotto.covered()) {
			jp.add("§cTicket sales are paused until the Treasury");
			jp.add("§ccan cover all prizes of this draw.");
		}
		gui.setSlot(LOTTO_JACKPOT, Gui.item(net.minecraft.world.item.Items.GOLD_BLOCK, "§6§lJACKPOT: §e§l" + Money.format(lotto.jackpot()), jp).glow());

		List<String> pz = new ArrayList<>();
		for (int rank = 0; rank < lotto.winningCount(); rank++) {
			pz.add((rank == Lottery.JACKPOT ? "§6§lJackpot" : "§e" + ordinal(rank + 1) + " number") + "§7: " + Msg.moneyStr(lotto.prizeAmount(rank)));
		}
		pz.add("");
		pz.add("§7" + lotto.winningCount() + " different numbers are drawn from all");
		pz.add("§f" + size + " §7tickets. A prize only counts if its");
		pz.add("§7ticket was sold. Extra prizes don't roll over.");
		gui.setSlot(LOTTO_PRIZES, Gui.item(net.minecraft.world.item.Items.EMERALD, "§aPrizes §8– draw #" + lotto.drawId(), pz));

		gui.setSlot(LOTTO_SOLD, Gui.item(net.minecraft.world.item.Items.MAP, "§fSold: §e" + sold + "§7/§f" + size,
			progressBar(size <= 0 ? 0 : sold / (double) size) + " §7" + (size <= 0 ? 0 : Math.round(sold * 100.0 / size)) + "%",
			"§7Players: §f" + lotto.participants(),
			"§7Still available: §f" + left,
			"§7Price: " + Msg.moneyStr(cfg.lottoTicketPrice) + " §7per ticket"));

		int have = lotto.tickets(id);
		List<String> ch = new ArrayList<>();
		if (have == 0) {
			ch.add("§7You have no tickets yet.");
			ch.add("§7Each ticket: §f" + percent(size <= 0 ? 0 : 1.0 / size) + " §7per prize.");
		} else {
			double c = lotto.chance(id);
			for (int rank = 0; rank < lotto.winningCount(); rank++) {
				String label = rank == Lottery.JACKPOT ? "§6Jackpot" : "§e" + Money.format(lotto.prizeAmount(rank));
				ch.add(label + "§7: §f" + percent(c) + (rank == Lottery.JACKPOT ? " §8(1 in " + Msg.number(Math.round(1 / c)) + ")" : ""));
			}
			ch.add("§7Any prize: §f" + percent(lotto.chanceAny(id)));
		}
		gui.setSlot(LOTTO_CHANCE, Gui.item(net.minecraft.world.item.Items.ENDER_EYE, "§bYour chance", ch));

		int room = Math.max(0, cfg.lottoMaxTicketsPerPlayer - have);
		boolean allowed = mayLotto(player);
		for (int i = 0; i < LOTTO_BUY_SLOTS.length; i++) {
			int n = LOTTO_BUY_COUNTS[i] < 0 ? Math.min(room, left) : LOTTO_BUY_COUNTS[i];
			int slot = LOTTO_BUY_SLOTS[i];
			if (!allowed) {
				gui.setSlot(slot, Gui.item(net.minecraft.world.item.Items.BARRIER, "§cNot unlocked", "§7" + LOTTO_DENIED));
				continue;
			}
			if (!cfg.lottoEnabled) {
				gui.setSlot(slot, Gui.item(net.minecraft.world.item.Items.BARRIER, "§cLottery disabled"));
				continue;
			}
			if (!lotto.isReady()) {
				gui.setSlot(slot, Gui.item(net.minecraft.world.item.Items.BARRIER, "§cNot open yet"));
				continue;
			}
			if (!lotto.covered() && room > 0 && left > 0) {
				gui.setSlot(slot, Gui.item(net.minecraft.world.item.Items.BARRIER, "§cSales paused", "§7The Treasury can't cover all prizes", "§7of this draw right now. Try again later."));
				continue;
			}
			if (room <= 0) {
				gui.setSlot(slot, Gui.item(net.minecraft.world.item.Items.GRAY_DYE, "§7Maximum reached", "§7You already have §f" + have + "§7/§f"
					+ cfg.lottoMaxTicketsPerPlayer + " §7tickets."));
				continue;
			}
			if (left <= 0) {
				gui.setSlot(slot, Gui.item(net.minecraft.world.item.Items.GRAY_DYE, "§cSold out", "§7All §f" + size + " §7tickets are sold.",
					"§7Next draw: §f" + EventsRuntime.nextDrawText()));
				continue;
			}
			int count = n;
			String name = LOTTO_BUY_COUNTS[i] < 0 ? "§a§lMax: " + count + (count == 1 ? " ticket" : " tickets") : "§aBuy " + count + (count == 1 ? " ticket" : " tickets");
			long cost = (long) count * cfg.lottoTicketPrice;
			List<String> lore = new ArrayList<>();
			lore.add("§7Cost: " + Msg.moneyStr(cost));
			if (count > Math.min(room, left)) lore.add("§8(only " + Math.min(room, left) + " possible)");
			lore.add("§7Your tickets: §f" + have + "§7/§f" + cfg.lottoMaxTicketsPerPlayer);
			lore.add("§7Numbers are picked at random.");
			lore.add("");
			lore.add("§eClick to buy");
			gui.setSlot(slot, Gui.button(net.minecraft.world.item.Items.PAPER, name, () -> {
				buy(player, count);
				if (gui.isOpen()) renderLotto(gui, player, back);
			}, lore.toArray(String[]::new)).setCount(Math.max(1, Math.min(64, count))));
		}

		List<String> mine = new ArrayList<>();
		List<Integer> numbers = lotto.numbers(id);
		if (numbers.isEmpty()) mine.add("§7No tickets for this draw yet.");
		else mine.addAll(numberLines(numbers, 8));
		mine.add("§7Spent: " + Msg.moneyStr((long) have * cfg.lottoTicketPrice));
		long owed = lotto.owedTo(id);
		if (owed > 0) {
			mine.add("");
			mine.add("§eOutstanding winnings: " + Msg.moneyStr(owed));
			mine.add("§8Paid as soon as the Treasury can cover it.");
		}
		gui.setSlot(LOTTO_MINE, Gui.item(net.minecraft.world.item.Items.NAME_TAG, "§eYour tickets: §f" + have + "§7/§f" + cfg.lottoMaxTicketsPerPlayer, mine));

		List<String> rules = new ArrayList<>(List.of(
			"§7Each draw has §f" + size + " §7numbered tickets, " + Msg.moneyStr(cfg.lottoTicketPrice) + " §7each,",
			"§7max §f" + cfg.lottoMaxTicketsPerPlayer + " §7per player. Your numbers are random.",
			"§7Every day at §f" + cfg.lottoDrawTime + " §f" + lotto.winningCount() + " §7different winning numbers",
			"§7are drawn: the jackpot first, then the other prizes.",
			"§7A prize only counts if its ticket was sold –",
			"§7playing alone doesn't win automatically.",
			"§7Jackpot not won? Next draw it's " + Msg.moneyStr(cfg.lottoJackpotBase) + " §7bigger."));
		if (!cfg.lottoRolloverWithoutTickets) rules.add("§7No tickets sold? No draw – the jackpot stays.");
		rules.add("§7Ticket money goes to the Treasury, which pays the");
		rules.add("§7prizes – tickets are sold while it can cover them all.");
		rules.add("§7Offline? You'll find out on your next login.");
		rules.add("§8/lottery tickets · /lottery history");
		gui.setSlot(LOTTO_RULES, Gui.item(net.minecraft.world.item.Items.OAK_SIGN, "§fHow it works", rules));

		List<Lottery.Draw> draws = lotto.draws();
		for (int i = 0; i < LOTTO_HISTORY_SLOTS.length; i++) {
			if (i >= draws.size()) {
				// Redrawing replaces every slot, so no clearing first and no flicker
				gui.setSlot(LOTTO_HISTORY_SLOTS[i], draws.isEmpty() && i == 3
					? Gui.item(net.minecraft.world.item.Items.BOOK, "§fRecent draws", "§7No draws yet.") : Gui.filler());
				continue;
			}
			Lottery.Draw d = draws.get(i);
			List<String> lore = new ArrayList<>(prizeLines(d));
			lore.add("§7Sold: §f" + d.sold + "/" + d.ticketCount + " §8(" + d.players + (d.players == 1 ? " player)" : " players)"));
			if (d.forced) lore.add("§cAdmin test draw: numbers set by an admin");
			else if (d.admin) lore.add("§8Drawn by an admin");
			lore.add(d.jackpotWon() ? "§aJackpot won – next one started at " + Money.format(d.nextJackpot) : "§6Rolled over to " + Money.format(d.nextJackpot));
			GuiElementBuilder b = Gui.item(d.jackpotWon() ? net.minecraft.world.item.Items.GOLD_INGOT : net.minecraft.world.item.Items.PAPER,
				"§fDraw #" + d.id + " §8– §7" + when(d.at), lore);
			if (d.jackpotWon()) b.glow();
			gui.setSlot(LOTTO_HISTORY_SLOTS[i], b);
		}

		if (back != null) gui.setSlot(LOTTO_BACK, Gui.backButton(back));
		else Gui.menuButton(gui, LOTTO_BACK); // /lottery directly: way to the main menu instead of back
		gui.setSlot(LOTTO_CLOSE, Gui.closeButton(gui));
		Gui.fillEmpty(gui);
	}

	/** Marks admin draws in history: given numbers clearly, otherwise subtly. */
	static String adminMark(Lottery.Draw d) {
		return d.forced ? " §c[admin test draw: numbers set by an admin]" : d.admin ? " §8[drawn by an admin]" : "";
	}

	/** One line per winning number: "§6Jackpot $72,000 §8» §e#123 §8» §aName" or "§7not sold". */
	static List<String> prizeLines(Lottery.Draw d) {
		List<String> lines = new ArrayList<>();
		for (Lottery.Prize p : d.prizes) {
			String label = p.rank == Lottery.JACKPOT ? "§6Jackpot " + Money.format(p.amount) : "§e" + Money.format(p.amount);
			String who = p.winner == null ? "§7not sold" : "§a" + p.winnerName + (p.owed > 0 ? " §8(" + Money.format(p.owed) + " outstanding)" : "");
			lines.add(label + " §8» §e#" + p.number + " §8» " + who);
		}
		return lines;
	}

	/** Ticket numbers as lines of {@code perLine} numbers ("§f#12 #77 ..."). */
	static List<String> numberLines(List<Integer> numbers, int perLine) {
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		int inLine = 0;
		for (int n : numbers) {
			if (inLine == perLine) {
				lines.add("§f" + line);
				line.setLength(0);
				inLine = 0;
			}
			if (inLine > 0) line.append(' ');
			line.append('#').append(n);
			inLine++;
		}
		if (inLine > 0) lines.add("§f" + line);
		return lines;
	}

	/** One decimal, small values two ("5.0%", "0.10%"). */
	static String percent(double p) {
		double v = Math.max(0, Math.min(1, p)) * 100;
		return String.format(java.util.Locale.ROOT, v > 0 && v < 1 ? "%.2f%%" : "%.1f%%", v);
	}

	static String when(long at) {
		return WHEN.format(Instant.ofEpochMilli(at).atZone(TimeUtil.zone()));
	}

	static String ordinal(int n) {
		int mod100 = n % 100;
		String suffix = mod100 >= 11 && mod100 <= 13 ? "th" : switch (n % 10) {
			case 1 -> "st";
			case 2 -> "nd";
			case 3 -> "rd";
			default -> "th";
		};
		return n + suffix;
	}

	/** Checks {@code smpeconomy.lottery} itself, since the GUI path bypasses {@code requires()}. */
	static Lottery.Purchase buy(ServerPlayer player, int count) {
		EventsConfig cfg = EventsFeature.CONFIG.get();
		Lottery lotto = EventsFeature.LOTTO;
		Lottery.Purchase p = mayLotto(player)
			? lotto.buy(player.getUUID(), count)
			: new Lottery.Purchase(Lottery.BuyStatus.DENIED, count, 0, lotto.tickets(player.getUUID()), 0, List.of());
		switch (p.status()) {
			case OK -> {
				String extra = p.bought() < p.wanted() ? " §8(only " + p.bought() + " possible)" : "";
				List<Integer> nums = p.numbers();
				StringBuilder shown = new StringBuilder();
				for (int i = 0; i < Math.min(10, nums.size()); i++) shown.append(i == 0 ? "" : " ").append('#').append(nums.get(i));
				if (nums.size() > 10) shown.append(" §8… +").append(nums.size() - 10).append(" more (/lottery tickets)");
				Msg.success(player, "You bought §f" + p.bought() + (p.bought() == 1 ? " ticket" : " tickets") + " §7(−" + Money.format(p.cost())
					+ ")" + extra + "§7: §e" + shown + "§7. Your tickets: §f" + p.total() + "§7/§f" + cfg.lottoMaxTicketsPerPlayer + " §8– draw #"
					+ lotto.drawId() + " " + EventsRuntime.nextDrawText() + ", jackpot " + Money.format(lotto.jackpot()));
				Gui.successSound(player);
			}
			case MAX_REACHED -> fail(player, "You already have the maximum of " + cfg.lottoMaxTicketsPerPlayer + " tickets for this draw.");
			case SOLD_OUT -> fail(player, "All " + lotto.ticketCount() + " tickets for draw #" + lotto.drawId() + " are sold out – next draw "
				+ EventsRuntime.nextDrawText() + ".");
			case INSUFFICIENT_FUNDS -> fail(player, "Not enough money – that costs " + Money.format(p.cost()) + ".");
			case NOT_COVERED -> fail(player, "Ticket sales are paused – the Treasury can't cover all prizes of draw #" + lotto.drawId() + " right now. Try again later.");
			case DISABLED -> fail(player, "The lottery is disabled right now.");
			case INVALID_COUNT -> fail(player, "Invalid amount.");
			case ERROR -> fail(player, "Purchase failed – please try again later.");
			case DENIED -> fail(player, LOTTO_DENIED);
		}
		return p;
	}

	private static void fail(ServerPlayer player, String text) {
		Msg.error(player, text);
		Gui.failSound(player);
	}
}
