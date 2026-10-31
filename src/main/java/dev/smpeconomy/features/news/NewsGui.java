package dev.smpeconomy.features.news;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.PriceGuard;
import dev.smpeconomy.core.TimeUtil;
import dev.smpeconomy.features.news.NewsData.NewsEntry;
import dev.smpeconomy.features.news.NewsData.NewsEntry.Type;

/**
 * {@code /news}: display-only GUIs. Main view shows running events with time left and the player's personal prices
 * (PriceGuard.breakdown); history shows the last {@code historySize} events, filterable. Refreshes while open.
 */
final class NewsGui {
	static final int ROWS = 5;
	static final int HISTORY_ROWS = 6;
	static final int HISTORY_NAV = HISTORY_ROWS * 9 - 9;
	static final int SLOT_H_BACK = HISTORY_NAV;
	static final int SLOT_H_FILTER = HISTORY_NAV + 1;
	static final int SLOT_H_INFO = HISTORY_NAV + 7;
	static final int SLOT_H_CLOSE = HISTORY_NAV + 8;

	enum Filter {
		ALL("All"), CRASH("Crashes"), HYPE("Hypes"), ACTION("Events");

		final String label;

		Filter(String label) {
			this.label = label;
		}

		boolean matches(NewsEntry e) {
			return switch (this) {
				case ALL -> true;
				case CRASH -> e.kind() == Type.CRASH;
				case HYPE -> e.kind() == Type.HYPE;
				case ACTION -> !e.kind().isShock();
			};
		}

		Filter next() {
			return values()[(ordinal() + 1) % values().length];
		}
	}
	static final int SLOT_INFO = 4;
	static final int SLOT_EMPTY = 22;
	static final int SLOT_BACK = 36;
	static final int SLOT_MENU = 36;
	static final int SLOT_HISTORY = 40;
	static final int SLOT_CLOSE = 44;

	private NewsGui() {}

	static void open(ServerPlayer player, NewsEngine engine) {
		if (!Gui.canOpen(player)) return;
		new Main(player, engine).open();
	}

	static void openHistory(ServerPlayer player, NewsEngine engine) {
		if (!Gui.canOpen(player)) return;
		new History(player, engine).open();
	}

	private abstract static class View extends SimpleGui {
		final NewsEngine engine;
		private long lastRender;

		View(ServerPlayer player, NewsEngine engine, String title) {
			super(Gui.menuType(ROWS), player, false);
			this.engine = engine;
			setTitle(Msg.legacy(title));
		}

		@Override
		public boolean open() {
			render();
			return super.open();
		}

		@Override
		public void onTick() {
			long now = System.currentTimeMillis();
			if (now - lastRender >= 1000) render();
		}

		final void render() {
			lastRender = System.currentTimeMillis();
			for (int i = 0; i < ROWS * 9; i++) clearSlot(i);
			fill(engine.now());
			Gui.frame(this, true);
			Gui.fillEmpty(this);
		}

		abstract void fill(long now);
	}

	private static final class Main extends View {
		Main(ServerPlayer player, NewsEngine engine) {
			super(player, engine, "§c§lBreaking News");
		}

		@Override
		void fill(long now) {
			List<NewsEntry> list = NewsBossBar.displayable(engine, now);
			NewsConfig c = NewsFeature.config();
			setSlot(SLOT_INFO, Gui.item(net.minecraft.world.item.Items.BELL, "§c§lBreaking News",
				"§7Breaking News changes what the server", "§7pays: §f/sell§7, NPC orders and", "§7NPC buyers. §cCrash§7: only selling drops,",
				"§7the §f/shop §7stays. §aHype§7: both go up.", "§7Player listings in the §f/ah §7stay.", "",
				"§7About every §f" + c.minIntervalMinutes + "–" + c.maxIntervalMinutes + " min §7(with players online,",
				"§7not " + c.quietFromHour + ":00–" + c.quietToHour + ":00), max §f" + c.maxActive + " §7at once.", "",
				"§7Running now: §f" + list.size()));
			int[] inner = Gui.innerSlots(ROWS);
			if (list.isEmpty()) {
				setSlot(SLOT_EMPTY, Gui.item(net.minecraft.world.item.Items.GRAY_DYE, "§7No news right now",
					"§8Breaking News comes unannounced –", "§8the boss bar at the top shows it instantly."));
			}
			for (int i = 0; i < list.size() && i < inner.length; i++) setSlot(inner[i], activeElement(engine, list.get(i), getPlayer(), now));
			setSlot(SLOT_HISTORY, Gui.button(net.minecraft.world.item.Items.BOOK, "§eHistory",
				() -> NewsGui.openHistory(getPlayer(), engine), "§7The last " + engine.data().history.size() + " stories"));
			Gui.menuButton(this, SLOT_MENU);
			setSlot(SLOT_CLOSE, Gui.closeButton(this));
		}
	}

	private static final class History extends SimpleGui {
		private final NewsEngine engine;
		private final Gui.Pager<NewsEntry> pager;
		private Filter filter = Filter.ALL;
		private long lastRender;

		History(ServerPlayer player, NewsEngine engine) {
			super(Gui.menuType(HISTORY_ROWS), player, false);
			this.engine = engine;
			setTitle(Msg.legacy("§8Breaking News §7» §fHistory"));
			pager = new Gui.Pager<>(this, this::entries, e -> historyElement(engine, e, engine.now()));
		}

		List<NewsEntry> entries() {
			List<NewsEntry> list = new ArrayList<>();
			for (NewsEntry e : engine.data().history) {
				if (filter.matches(e)) list.add(e);
			}
			return list;
		}

		@Override
		public boolean open() {
			render(0);
			return super.open();
		}

		@Override
		public void onTick() {
			if (System.currentTimeMillis() - lastRender >= 2000) render(pager.page());
		}

		void render(int page) {
			lastRender = System.currentTimeMillis();
			setSlot(SLOT_H_BACK, Gui.backButton(() -> NewsGui.open(getPlayer(), engine)));
			setSlot(SLOT_H_FILTER, Gui.button(net.minecraft.world.item.Items.HOPPER, "§eFilter: §f" + filter.label, () -> {
				filter = filter.next();
				render(0);
			}, filterLore(filter)));
			setSlot(SLOT_H_INFO, historyInfo(engine, engine.now()));
			setSlot(SLOT_H_CLOSE, Gui.closeButton(this));
			pager.show(page);
			if (pager.pages() == 1 && entries().isEmpty()) {
				setSlot(22, Gui.item(net.minecraft.world.item.Items.GRAY_DYE, filter == Filter.ALL ? "§7No stories yet" : "§7No stories: " + filter.label));
			}
		}
	}

	static String[] filterLore(Filter current) {
		List<String> lines = new ArrayList<>();
		for (Filter f : Filter.values()) lines.add((f == current ? "§a» " : "§8  ") + f.label);
		lines.add("");
		lines.add("§eClick: §7next filter");
		return lines.toArray(String[]::new);
	}

	static GuiElementBuilder historyInfo(NewsEngine engine, long now) {
		List<NewsEntry> history = List.copyOf(engine.data().history);
		int crash = 0;
		int hype = 0;
		int action = 0;
		for (NewsEntry e : history) {
			if (now - e.startMs > 24 * 3_600_000L) continue;
			switch (e.kind()) {
				case CRASH -> crash++;
				case HYPE -> hype++;
				default -> action++;
			}
		}
		return Gui.item(net.minecraft.world.item.Items.PAPER, "§eHistory", "§7Newest first, at most §f" + NewsFeature.config().historySize + "§7.",
			"§7Entries: §f" + history.size(), "", "§7Last 24 h: §c" + crash + " crash" + (crash == 1 ? "" : "es") + "§7, §a" + hype + " hype"
				+ (hype == 1 ? "" : "s") + "§7, §6" + action + " event" + (action == 1 ? "" : "s"));
	}

	static Item icon(NewsEntry e) {
		return Items.byId(e.item).orElse(net.minecraft.world.item.Items.PAPER);
	}

	static MutableComponent title(NewsEntry e, int percent) {
		return switch (e.kind()) {
			case CRASH -> Msg.legacy("§c§lCRASH ").append(NewsText.item(e.item)).append(Msg.legacy(" §c" + NewsText.pct(percent)));
			case HYPE -> Msg.legacy("§a§lHYPE ").append(NewsText.item(e.item)).append(Msg.legacy(" §a" + NewsText.pct(percent)));
			case FLASH_SALE -> Msg.legacy("§a§lFLASH SALE §f" + NewsText.categoryName(e) + " §aup to " + NewsText.pct(percent));
			case BULK_ORDER -> Msg.legacy("§6§lBULK ORDER §f" + Msg.number(e.amount) + "× ").append(NewsText.item(e.item));
			case AUCTION_FEVER -> Msg.legacy("§e§lAUCTION FEVER");
		};
	}

	static GuiElementBuilder activeElement(NewsEngine engine, NewsEntry e, ServerPlayer player, long now) {
		GuiElementBuilder b = new GuiElementBuilder(icon(e)).hideDefaultTooltip().setName(title(e, engine.livePercent(e)));
		UUID id = player.getUUID();
		switch (e.kind()) {
			case CRASH, HYPE -> {
				Optional<Item> item = Items.byId(e.item);
				if (item.isPresent()) {
					PriceGuard.Breakdown bd = PriceGuard.breakdown(id, item.get());
					long normalSell = NewsPrices.personalSellWithout(engine.source, id, item.get());
					Gui.lore(b, "§7Your sell price: " + (bd.effectiveSell() > 0 ? Msg.moneyStr(bd.effectiveSell()) : "§ccan't be sold")
						+ (normalSell > 0 ? " §8(normally " + Money.format(normalSell) + ")" : ""));
					if (bd.effectiveBuy() > 0) {
						long normalBuy = NewsPrices.personalShopWithout(engine.source, id, item.get());
						Gui.lore(b, "§7Shop price: §e" + Money.format(bd.effectiveBuy()) + (normalBuy == bd.effectiveBuy() ? " §8(unchanged)"
							: normalBuy > 0 ? " §8(normally " + Money.format(normalBuy) + ")" : ""));
					}
					if (e.kind() == Type.CRASH) Gui.lore(b, "§8Crash: only sell prices drop,", "§8the shop doesn't get cheaper.");
					List<String> mods = bd.loreLines();
					if (!mods.isEmpty()) {
						Gui.lore(b, "");
						mods.forEach(line -> Gui.lore(b, line));
					}
				}
				MutableComponent fam = NewsText.familyLine(e);
				if (fam != null) b.addLoreLine(fam);
				if (e.kind() == Type.HYPE) b.glow();
			}
			case FLASH_SALE -> {
				Gui.lore(b, "§7Discount in the shop category", "§f" + NewsText.categoryName(e) + "§7: §aup to " + NewsText.pct(e.shownPercent),
					"§8Minimum prices still apply.");
				MutableComponent excluded = NewsText.excludedLine(engine, e);
				if (excluded != null) b.addLoreLine(excluded);
				Gui.lore(b, "§7Open the §f/shop§7 while it lasts!");
			}
			case BULK_ORDER -> {
				Gui.lore(b, "§7NPCs are buying §f" + Msg.number(e.amount) + "×");
				b.addLoreLine(NewsText.item(e.item));
				Gui.lore(b, "§7Deliver via §f/orders§7.");
			}
			case AUCTION_FEVER -> Gui.lore(b, "§7NPC buyers buy player listings", "§7in the §f/ah §7twice as fast and",
				"§7pay up to market value §f×1.25§7.", "§8Price caps still apply.");
		}
		Gui.lore(b, "");
		if (e.running(now)) {
			Gui.lore(b, "§7Time left: §f" + TimeUtil.formatDuration(e.endMs - now), progressBar(NewsText.progress(e, now)));
		} else {
			Gui.lore(b, "§7Back to normal in: §f" + TimeUtil.formatDuration(e.endMs + e.fadeOutMs - now));
		}
		return b;
	}

	static GuiElementBuilder historyElement(NewsEngine engine, NewsEntry e, long now) {
		GuiElementBuilder b = new GuiElementBuilder(icon(e)).hideDefaultTooltip().setName(title(e, e.shownPercent));
		Gui.lore(b, "§7Type: §f" + NewsText.typeLabel(e), "§7Started: §f" + NewsText.when(e.startMs) + " §8(" + TimeUtil.formatDuration(now - e.startMs)
			+ " ago)", "§7Duration: §f" + NewsText.minutes(Math.max(0, e.endMs - e.startMs)));
		switch (e.kind()) {
			case CRASH -> Gui.lore(b, "§7Sell: §c" + NewsText.pct(e.shownPercent) + " §8(shop unchanged)");
			case HYPE -> Gui.lore(b, "§7Sell: §a" + NewsText.pct(e.shownPercent));
			case FLASH_SALE -> Gui.lore(b, "§7Shop " + "§f" + NewsText.categoryName(e) + "§7: §aup to " + NewsText.pct(e.shownPercent));
			case BULK_ORDER -> Gui.lore(b, "§7NPCs bought up to §f" + Msg.number(e.amount) + "×");
			case AUCTION_FEVER -> Gui.lore(b, "§7NPC buyers bought twice as fast");
		}
		if (e.kind().isShock()) {
			MutableComponent fam = NewsText.familyLine(e);
			if (fam != null) b.addLoreLine(fam);
		}
		boolean active = engine.data().active.stream().anyMatch(a -> a.id == e.id);
		String status = e.cancelled ? "§ccancelled" : active && e.running(now) ? "§arunning" : active ? "§eending" : "§8ended";
		Gui.lore(b, "§7Status: " + status);
		if (e.manual) Gui.lore(b, "§8(triggered by staff)");
		return b;
	}

	static String progressBar(float progress) {
		int n = Math.max(0, Math.min(20, Math.round(progress * 20)));
		return "§a" + "|".repeat(n) + "§8" + "|".repeat(20 - n);
	}
}
