package dev.smpeconomy.features.orders;

import static dev.smpeconomy.core.test.Assert.eq;
import static dev.smpeconomy.core.test.Assert.fail;
import static dev.smpeconomy.core.test.Assert.isFalse;
import static dev.smpeconomy.core.test.Assert.isTrue;
import static dev.smpeconomy.core.test.Assert.notNull;

import com.google.gson.JsonElement;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.GameType;
import dev.smpeconomy.core.BasePrices;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.PriceModifiers;
import dev.smpeconomy.core.test.SelfTests;
import dev.smpeconomy.core.test.TestContext;
import dev.smpeconomy.features.orders.OrderBook.CreateResult;
import dev.smpeconomy.features.orders.OrderBook.DeliverResult;
import dev.smpeconomy.features.orders.OrderBook.Status;
import dev.smpeconomy.features.orders.OrderData.Order;
import dev.smpeconomy.features.orders.OrderSort.Sort;
import dev.smpeconomy.features.orders.OrdersGui.Entry;
import dev.smpeconomy.features.orders.OrdersGui.Header;
import dev.smpeconomy.features.orders.OrdersGui.Row;
import dev.smpeconomy.features.orders.OrdersGui.View;

/** Selftests for {@link OrderSort}. Each runs on its own order book, so real orders stay untouched. */
final class OrderSortSelfTests {
	private static final String F = OrdersFeature.ID;
	private static final String MOD_SOURCE = "orders.sort.selftest";
	private static final long T0 = 1_800_000_000_000L;
	private static final Item COBBLE = net.minecraft.world.item.Items.COBBLESTONE;
	private static final Item DIAMOND = net.minecraft.world.item.Items.DIAMOND;
	private static final Item WHEAT = net.minecraft.world.item.Items.WHEAT;
	private static final Item COAL = net.minecraft.world.item.Items.COAL;
	private static final Item IRON = net.minecraft.world.item.Items.IRON_INGOT;
	private static final Item BONE = net.minecraft.world.item.Items.BONE;
	private static final Item EMERALD = net.minecraft.world.item.Items.EMERALD;

	private OrderSortSelfTests() {}

	static void register() {
		SelfTests.register(F, "sort-each-order", OrderSortSelfTests::eachOrder);
		SelfTests.register(F, "sort-players-first-toggle", OrderSortSelfTests::playersFirstToggle);
		SelfTests.register(F, "sort-effective-prices", OrderSortSelfTests::effectivePrices);
		SelfTests.register(F, "sort-demand-paid", OrderSortSelfTests::demandPaid);
		SelfTests.register(F, "sort-persistence", OrderSortSelfTests::persistence);
		SelfTests.register(F, "sort-cache-450", OrderSortSelfTests::cache450);
	}

	private static OrderBook book(long[] clock, OrdersConfig cfg) {
		OrderData data = new OrderData();
		OrdersSelfTests.FakeFunding fund = new OrdersSelfTests.FakeFunding(100_000_000, 10_000_000, 10_000_000);
		return new OrderBook(() -> data, () -> {}, () -> clock[0], fund, new Random(42), () -> cfg, () -> true);
	}

	private static FakePlayer player(TestContext ctx, String name, long balance) {
		FakePlayer p = ctx.player(name);
		if (p.gameMode() != GameType.SURVIVAL) p.setGameMode(GameType.SURVIVAL);
		p.getAbilities().instabuild = false;
		EconomyService.ensureAccount(p.getUUID(), name);
		EconomyService.set(p.getUUID(), balance);
		return p;
	}

	private static Order create(OrderBook book, long[] clock, FakePlayer owner, Item item, int amount, long price) {
		clock[0] += 1_000;
		CreateResult r = book.createPlayerOrder(owner.getUUID(), item, amount, price, false);
		eq(Status.OK, r.status(), "create order " + Items.id(item));
		return r.order();
	}

	private static Order boardOrder(OrderBook book, long[] clock, Item item, double demand, int amount) {
		clock[0] += 1_000;
		Order o = book.board().spawn(item, false, false);
		notNull(o, "board order " + Items.id(item));
		o.demand = demand;
		o.amount = amount;
		o.expiresAt = book.now() + 30 * OrderBook.DAY_MS;
		return o;
	}

	private static View view(Sort sort, boolean playersFirst) {
		View v = new View();
		v.sort = sort;
		v.playersFirst = playersFirst;
		return v;
	}

	private static List<Order> orders(List<Row> rows) {
		List<Order> list = new ArrayList<>();
		for (Row r : rows) {
			if (r instanceof Entry e) list.add(e.order());
		}
		return list;
	}

	private static List<Long> ids(List<Order> list) {
		return list.stream().map(o -> o.id).toList();
	}

	private static String loreText(GuiElementBuilder b) {
		ItemLore lore = b.asStack().get(DataComponents.LORE);
		if (lore == null) return "";
		StringBuilder sb = new StringBuilder();
		for (Component c : lore.lines()) sb.append(c.getString()).append('\n');
		return sb.toString();
	}

	/** Independent comparison of two orders: negative means a before b, 0 means a tie is allowed. */
	private static int expectedCompare(OrderBook book, UUID who, Sort sort, Order a, Order b) {
		return switch (sort) {
			case PRICE_DESC, PRICE_ASC -> {
				long ua = book.unitPrice(who, a);
				long ub = book.unitPrice(who, b);
				if (ua > 0 != ub > 0) yield ua > 0 ? -1 : 1;
				yield sort == Sort.PRICE_DESC ? Long.compare(ub, ua) : Long.compare(ua, ub);
			}
			case TOTAL_DESC -> Long.compare(OrdersGui.safeMul(b.open(), book.unitPrice(who, b)), OrdersGui.safeMul(a.open(), book.unitPrice(who, a)));
			case DEMAND_DESC -> Double.compare(expectedDemand(book, who, b), expectedDemand(book, who, a));
			case NEWEST -> a.createdAt != b.createdAt ? Long.compare(b.createdAt, a.createdAt) : Long.compare(b.id, a.id);
			case NAME_AZ -> nameKey(a).compareTo(nameKey(b));
		};
	}

	/**
	 * Computed independently of {@link OrderSort}: the lore price divided by the /sell reference, never the raw board
	 * factor. 0 means it accepts nothing, -1 means no /sell value.
	 */
	private static double expectedDemand(OrderBook book, UUID who, Order o) {
		long unit = book.unitPrice(who, o);
		if (unit <= 0) return 0;
		long ref = NpcBoard.referencePrice(o.itemType().orElseThrow());
		return ref <= 0 ? -1 : (double) unit / ref;
	}

	private static String nameKey(Order o) {
		return OrderSearch.normalize(net.minecraft.locale.Language.getInstance().getOrDefault(o.itemType().orElseThrow().getDescriptionId()));
	}

	private static int checkSorted(OrderBook book, UUID who, Sort sort, boolean playersFirst, List<Order> list, String what) {
		int pairs = 0;
		for (int i = 1; i < list.size(); i++) {
			Order a = list.get(i - 1);
			Order b = list.get(i);
			int ga = OrderSort.group(a, playersFirst);
			int gb = OrderSort.group(b, playersFirst);
			if (ga != gb) {
				isTrue(ga < gb, what + ": groups in order (#" + a.id + " before #" + b.id + ")");
				continue;
			}
			if (expectedCompare(book, who, sort, a, b) > 0) {
				fail(what + " " + sort.id + ": #" + a.id + " (" + a.item + ") before #" + b.id + " (" + b.item + ")");
			}
			pairs++;
		}
		return pairs;
	}

	private record Setup(OrderBook book, long[] clock, FakePlayer owner, FakePlayer viewer, Order pA, Order pB, Order pC, Order bulk,
			Order coalHigh, Order coalLow, Order iron, Order bone, Order diamond, Order dead) {}

	private static Setup setup(TestContext ctx) {
		FakePlayer owner = player(ctx, "OrdA", 100_000_000);
		FakePlayer viewer = player(ctx, "OrdB", 0);
		long[] clock = {T0};
		OrdersConfig cfg = new OrdersConfig();
		cfg.boardDailyBonusPerPlayer = 1_000_000_000L;
		OrderBook book = book(clock, cfg);
		Order pA = create(book, clock, owner, COBBLE, 1000, 5);
		Order pB = create(book, clock, owner, DIAMOND, 2, 8000);
		Order pC = create(book, clock, owner, WHEAT, 300, 100);
		clock[0] += 1_000;
		Order bulk = book.startBulk(WHEAT, 64, OrderBook.HOUR_MS, null);
		notNull(bulk, "bulk order wheat");
		Order coalHigh = boardOrder(book, clock, COAL, 1.30, 640);
		Order coalLow = boardOrder(book, clock, COAL, 1.05, 2304);
		Order iron = boardOrder(book, clock, IRON, 1.20, 200);
		Order bone = boardOrder(book, clock, BONE, 1.10, 640);
		Order diamond = boardOrder(book, clock, DIAMOND, 1.25, 40);
		Order dead = boardOrder(book, clock, COAL, 1.20, 64);
		dead.item = Items.id(net.minecraft.world.item.Items.BEDROCK);
		eq(0L, book.unitPrice(viewer.getUUID(), dead), "bedrock order accepts nothing");
		return new Setup(book, clock, owner, viewer, pA, pB, pC, bulk, coalHigh, coalLow, iron, bone, diamond, dead);
	}

	private static void eachOrder(TestContext ctx) {
		Setup s = setup(ctx);
		UUID who = s.viewer().getUUID();
		OrderBook book = s.book();
		long a = s.pA().id, b = s.pB().id, c = s.pC().id;
		java.util.Map<Sort, List<Long>> expectedPlayers = new java.util.EnumMap<>(Sort.class);
		expectedPlayers.put(Sort.PRICE_DESC, List.of(b, c, a));
		expectedPlayers.put(Sort.PRICE_ASC, List.of(a, c, b));
		expectedPlayers.put(Sort.TOTAL_DESC, List.of(c, b, a));
		expectedPlayers.put(Sort.DEMAND_DESC, List.of(c, b, a));
		expectedPlayers.put(Sort.NEWEST, List.of(c, b, a));
		expectedPlayers.put(Sort.NAME_AZ, List.of(a, b, c));
		eq(Sort.values().length, expectedPlayers.size(), "all sorts checked");
		// player order demand depends on the /sell reference, which breaking news running on the server can shift
		boolean calm = PriceModifiers.get(COBBLE) == 1.0 && PriceModifiers.get(DIAMOND) == 1.0 && PriceModifiers.get(WHEAT) == 1.0;
		int pairs = 0;
		for (Sort sort : Sort.values()) {
			List<Row> rows = OrdersGui.rows(book, s.viewer(), view(sort, true));
			isTrue(rows.get(0) instanceof Header h && h.players() && h.count() == 3, sort.id + ": player header first");
			if (sort != Sort.DEMAND_DESC || calm) eq(expectedPlayers.get(sort), ids(orders(rows.subList(1, 4))), sort.id + ": player orders");
			isTrue(rows.get(4) instanceof Header h && !h.players() && h.count() == 7, sort.id + ": then the NPC header");
			eq(s.bulk().id, ((Entry) rows.get(5)).order().id, sort.id + ": bulk order first in the NPC section");
			List<Order> board = orders(rows.subList(6, rows.size()));
			eq(6, board.size(), sort.id + ": 6 board orders");
			if (sort != Sort.NAME_AZ && sort != Sort.NEWEST) eq(s.dead().id, board.get(board.size() - 1).id, sort.id + ": accepts nothing, very last");
			pairs += checkSorted(book, who, sort, true, orders(rows), "players first");
			for (Order o : orders(rows)) eq(book.unitPrice(who, o), OrderSort.unit(book, who, o), "sort price = lore price #" + o.id);
		}
		List<Order> byDemand = orders(OrdersGui.rows(book, s.viewer(), view(Sort.DEMAND_DESC, true)));
		boolean uncapped = java.util.stream.Stream.of(s.coalHigh(), s.diamond(), s.iron(), s.bone(), s.coalLow())
			.noneMatch(o -> book.board().payout(who, o, 1).capped());
		if (uncapped) {
			eq(List.of(s.coalHigh().id, s.diamond().id, s.iron().id, s.bone().id, s.coalLow().id, s.dead().id), ids(byDemand.subList(4, 10)),
				"board by demand 1.30 / 1.25 / 1.20 / 1.10 / 1.05 / accepts nothing");
		}
		List<Order> newest = orders(OrdersGui.rows(book, s.viewer(), view(Sort.NEWEST, true)));
		eq(List.of(s.dead().id, s.diamond().id, s.bone().id, s.iron().id, s.coalLow().id, s.coalHigh().id), ids(newest.subList(4, 10)),
			"board: newest first");
		List<Order> byName = orders(OrdersGui.rows(book, s.viewer(), view(Sort.NAME_AZ, true)));
		List<Order> boardByName = byName.subList(4, 10);
		for (int i = 1; i < boardByName.size(); i++) {
			isTrue(nameKey(boardByName.get(i - 1)).compareTo(nameKey(boardByName.get(i))) <= 0, "board by (English) name");
		}
		isTrue(byName.subList(4, 10).stream().anyMatch(o -> o.id == s.iron().id) && nameKey(s.iron()).startsWith("iron ingot"),
			"English name (Iron Ingot)");
		for (Sort sort : Sort.values()) {
			List<Row> mixed = OrdersGui.rows(book, s.viewer(), view(sort, false));
			isFalse(mixed.stream().anyMatch(r -> r instanceof Header), sort.id + " mixed: no section headers");
			eq(10, mixed.size(), sort.id + " mixed: all 10 orders");
			pairs += checkSorted(book, who, sort, false, orders(mixed), "mixed");
		}
		List<Order> mixedDesc = orders(OrdersGui.rows(book, s.viewer(), view(Sort.PRICE_DESC, false)));
		isTrue(mixedDesc.indexOf(s.pB()) < mixedDesc.indexOf(s.coalHigh()), "mixed ↓: player diamond $80 before coal");
		isTrue(mixedDesc.indexOf(s.pA()) > mixedDesc.indexOf(s.coalHigh()), "mixed ↓: player cobblestone $0.05 behind NPC orders");
		if (calm) eq(List.of(s.pB().id, s.diamond().id), ids(mixedDesc.subList(0, 2)), "mixed ↓: player diamond $80, then NPC diamond $62.50");
		List<Order> mixedAsc = orders(OrdersGui.rows(book, s.viewer(), view(Sort.PRICE_ASC, false)));
		eq(s.pA().id, mixedAsc.get(0).id, "mixed ↑: cobblestone $0.05 first");
		eq(s.dead().id, mixedAsc.get(mixedAsc.size() - 1).id, "mixed ↑: accepts-nothing very last (not first)");
		List<Order> mixedTotal = orders(OrdersGui.rows(book, s.viewer(), view(Sort.TOTAL_DESC, false)));
		isTrue(mixedTotal.indexOf(s.pC()) < mixedTotal.indexOf(s.pB()) && mixedTotal.indexOf(s.pB()) < mixedTotal.indexOf(s.pA()), "mixed total: wheat, diamond, cobblestone");
		ctx.detail(Sort.values().length + " sorts × on/off, " + pairs + " pairs checked");
	}

	private static void playersFirstToggle(TestContext ctx) {
		Setup s = setup(ctx);
		OrderBook book = s.book();
		FakePlayer v = s.viewer();
		for (boolean pf : new boolean[] {true, false}) {
			View view = view(Sort.PRICE_DESC, pf);
			view.filter = OrdersGui.Filter.PLAYERS;
			List<Row> players = OrdersGui.rows(book, v, view);
			eq(pf ? 4 : 3, players.size(), "filter players (" + (pf ? "an" : "off") + ")");
			eq(List.of(s.pB().id, s.pC().id, s.pA().id), ids(orders(players)), "filter players: sorted");
			view.filter = OrdersGui.Filter.NPC;
			List<Row> npc = OrdersGui.rows(book, v, view);
			eq(pf ? 8 : 7, npc.size(), "Filter NPC (" + (pf ? "an" : "off") + ")");
			isTrue(orders(npc).stream().allMatch(o -> o.npc), "filter NPC: NPC only");
			if (!pf) isFalse(npc.get(0) instanceof Header, "off: no header");
			checkSorted(book, v.getUUID(), Sort.PRICE_DESC, pf, orders(npc), "Filter NPC");
			view.filter = OrdersGui.Filter.DELIVERABLE;
			eq(pf ? 2 : 0, OrdersGui.rows(book, v, view).size(), "deliverable without items");
		}
		dev.smpeconomy.core.Items.giveOrDrop(v, new ItemStack(COAL, 10));
		View view = view(Sort.PRICE_DESC, true);
		view.filter = OrdersGui.Filter.DELIVERABLE;
		eq(List.of(s.coalHigh().id, s.coalLow().id), ids(orders(OrdersGui.rows(book, v, view))), "deliverable with coal: both coal buyers, most expensive first");
		view.sort = Sort.PRICE_ASC;
		// same price (both at the cap, e.g. coal hype in the news): tie goes to the older number
		boolean distinct = book.unitPrice(v.getUUID(), s.coalHigh()) > book.unitPrice(v.getUUID(), s.coalLow());
		eq(distinct ? List.of(s.coalLow().id, s.coalHigh().id) : List.of(s.coalHigh().id, s.coalLow().id), ids(orders(OrdersGui.rows(book, v, view))),
			"deliverable ↑: cheapest first");
		view.filter = OrdersGui.Filter.ALL;
		view.query = "wheat";
		view.sort = Sort.NEWEST;
		view.playersFirst = false;
		eq(List.of(s.bulk().id, s.pC().id), ids(orders(OrdersGui.rows(book, v, view))), "search 'wheat' mixed, newest: bulk order, then player");
		view.playersFirst = true;
		List<Row> searched = OrdersGui.rows(book, v, view);
		isTrue(searched.get(0) instanceof Header h && h.count() == 1, "search on: player header (1)");
		eq(List.of(s.pC().id, s.bulk().id), ids(orders(searched)), "search 'wheat' players first: player, then bulk order");
		eq(Sort.PRICE_ASC, Sort.PRICE_DESC.next(false), "left: forward");
		eq(Sort.NAME_AZ, Sort.PRICE_DESC.next(true), "right: back (wraps around)");
		eq(Sort.PRICE_DESC, Sort.NAME_AZ.next(false), "left: wraps around");
		java.util.Set<Sort> seen = java.util.EnumSet.noneOf(Sort.class);
		Sort cur = Sort.DEFAULT;
		for (int i = 0; i < Sort.values().length; i++) {
			seen.add(cur);
			eq(cur, cur.next(false).next(true), "forward + back = same");
			cur = cur.next(false);
		}
		eq(Sort.values().length, seen.size(), "left clicks cycle through all sorts");
		eq(Sort.DEFAULT, cur, "and return to the start");
		ctx.detail("on/off × filter, deliverable, search ok");
	}

	private static void effectivePrices(TestContext ctx) {
		ctx.cleanup(() -> PriceModifiers.removeSource(MOD_SOURCE));
		PriceModifiers.removeSource(MOD_SOURCE);
		Setup s = setup(ctx);
		OrderBook book = s.book();
		FakePlayer v = s.viewer();
		UUID who = v.getUUID();
		long before = book.unitPrice(who, s.coalHigh());
		PriceModifiers.set(MOD_SOURCE, COAL, 3.0, 0, 60_000, 0, "Breaking News");
		long hype = book.unitPrice(who, s.coalHigh());
		PriceModifiers.removeSource(MOD_SOURCE);
		isTrue(hype > before + 2, "coal ×3 raises the board price (" + before + " → " + hype + ")");
		Order emerald = create(book, s.clock(), s.owner(), EMERALD, 10, (before + hype) / 2);
		View view = view(Sort.PRICE_DESC, false);
		List<Order> calm = orders(OrdersGui.rows(book, v, view));
		isTrue(calm.indexOf(emerald) < calm.indexOf(s.coalHigh()), "without news: emerald order " + Money.format(emerald.price) + " before coal " + Money.format(before));
		int buildsBefore = view.cache.builds;
		PriceModifiers.set(MOD_SOURCE, COAL, 3.0, 0, 60_000, 0, "Breaking News");
		List<Order> hyped = orders(OrdersGui.rows(book, v, view));
		isTrue(view.cache.builds > buildsBefore, "news drops the sorted list at once");
		isTrue(hyped.indexOf(s.coalHigh()) < hyped.indexOf(emerald), "with news: coal " + Money.format(book.unitPrice(who, s.coalHigh())) + " before the emerald order");
		checkSorted(book, who, Sort.PRICE_DESC, false, hyped, "News");
		int checked = 0;
		for (Order o : hyped) {
			if (o.id == s.dead().id) continue;
			long unit = OrderSort.unit(book, who, o);
			String lore = loreText(OrdersGui.orderIcon(book, v, o, true, 0, 0, null));
			String priceLine = o.bulk && unit < o.price ? "For you: " + Money.format(unit) : "Price per item: " + Money.format(o.bulk ? o.price : unit);
			isTrue(lore.contains(priceLine), "Lore #" + o.id + " shows " + priceLine + " (" + lore.replace('\n', '|') + ")");
			isTrue(lore.contains(Money.format(OrdersGui.safeMul(o.open(), unit)) + ")"), "Lore #" + o.id + " shows the total payout");
			checked++;
		}
		PriceModifiers.removeSource(MOD_SOURCE);
		ctx.detail("coal " + Money.format(before) + " → " + Money.format(hype) + ", " + checked + " lore lines = sort value");
	}

	/**
	 * Demand follows what an order really pays, for the board after the daily bonus and price cap. With a daily bonus
	 * of 1/5 of an iron /sell value, iron at factor 1.35 pays at most 1.20 × /sell and ranks behind a player coal
	 * order at 1.25 × /sell, coal at factor 1.30 ahead of it. Once the bonus is used up, coal only pays /sell and
	 * also ranks behind the player order.
	 */
	private static void demandPaid(TestContext ctx) {
		FakePlayer owner = player(ctx, "OrdA", 100_000_000);
		FakePlayer viewer = player(ctx, "OrdB", 0);
		UUID who = viewer.getUUID();
		viewer.getInventory().clearContent();
		ctx.cleanup(() -> viewer.getInventory().clearContent());
		long coalRef = NpcBoard.referencePrice(COAL);
		long ironRef = NpcBoard.referencePrice(IRON);
		isTrue(coalRef >= 20 && ironRef >= 20, "coal/iron with /sell value (" + coalRef + ", " + ironRef + ")");
		long[] clock = {T0};
		OrdersConfig cfg = new OrdersConfig();
		cfg.boardDailyBonusPerPlayer = ironRef / 5;
		OrderBook book = book(clock, cfg);
		NpcBoard board = book.board();
		Order coal = boardOrder(book, clock, COAL, 1.30, 640);
		Order ironHigh = boardOrder(book, clock, IRON, 1.35, 200);
		Order ironBurn = boardOrder(book, clock, IRON, 1.20, 200);
		Order player = create(book, clock, owner, COAL, 64, coalRef * 5 / 4);
		double playerDemand = (double) player.price / coalRef;
		// without news and personal bonuses the player's own /sell price is the reference; price cap above /sell
		boolean calm = PriceModifiers.get(COAL) == 1.0 && PriceModifiers.get(IRON) == 1.0 && NpcBoard.sellPrice(who, COAL) == coalRef
			&& NpcBoard.sellPrice(who, IRON) == ironRef && board.cap(who, COAL) >= coalRef && board.cap(who, IRON) > ironRef;
		int pairs = 0;
		if (calm) {
			isTrue(board.factor(ironHigh) > playerDemand, "iron: factor 1.35 above 1.25");
			double ironDemand = OrderSort.demand(book, who, ironHigh);
			isTrue(ironDemand <= 1.2 + 1e-9, "iron pays at most /sell + daily bonus = 1.20 × /sell (" + ironDemand + ")");
			List<Order> mixed = orders(OrdersGui.rows(book, viewer, view(Sort.DEMAND_DESC, false)));
			isTrue(mixed.indexOf(player) < mixed.indexOf(ironHigh), "mixed: player 1.25 × /sell before iron (factor 1.35, pays ≤ 1.20)");
			if (board.cap(who, COAL) >= coalRef * 13 / 10 && cfg.boardDailyBonusPerPlayer >= coalRef * 3 / 10) {
				isTrue(mixed.indexOf(coal) < mixed.indexOf(player), "mixed: coal (pays 1.30 × /sell) before the player order");
				List<Order> sections = orders(OrdersGui.rows(book, viewer, view(Sort.DEMAND_DESC, true)));
				isTrue(sections.indexOf(coal) < sections.indexOf(ironHigh), "board section: coal 1.30 before iron (factor 1.35, pays ≤ 1.20)");
			}
		}
		for (boolean pf : new boolean[] {true, false}) {
			pairs += checkSorted(book, who, Sort.DEMAND_DESC, pf, orders(OrdersGui.rows(book, viewer, view(Sort.DEMAND_DESC, pf))), "with bonus");
		}
		dev.smpeconomy.core.Items.giveOrDrop(viewer, new ItemStack(IRON, 200));
		for (int i = 0; i < 200 && board.bonusLeft(who) > 0; i++) {
			eq(Status.OK, book.deliver(viewer, ironBurn.id, 1).status(), "deliver iron");
		}
		viewer.getInventory().clearContent();
		if (calm) {
			eq(0L, board.bonusLeft(who), "daily bonus used up");
			isTrue(board.factor(coal) >= 1.30 - 1e-9, "coal: factor still 1.30 (" + board.factor(coal) + ")");
			eq(coalRef, book.unitPrice(who, coal), "coal only pays /sell now");
			isTrue(OrderSort.demand(book, who, coal) == 1.0, "coal: demand 1.0 × /sell, not the factor 1.30 (" + OrderSort.demand(book, who, coal) + ")");
			isTrue(OrderSort.demand(book, who, player) == playerDemand, "player: " + playerDemand + " × /sell");
			List<Order> mixed = orders(OrdersGui.rows(book, viewer, view(Sort.DEMAND_DESC, false)));
			eq(player.id, mixed.get(0).id, "mixed: player order 1.25 × /sell first, coal (factor 1.30, pays /sell) behind it");
			String lore = loreText(OrdersGui.orderIcon(book, viewer, coal, true, 0, 0, null));
			isTrue(lore.contains("±0% vs. your /sell price") && lore.contains("Daily bonus used up"),
				"lore coal: ±0% vs your /sell price, daily bonus used up (" + lore.replace('\n', '|') + ")");
		}
		for (boolean pf : new boolean[] {true, false}) {
			pairs += checkSorted(book, who, Sort.DEMAND_DESC, pf, orders(OrdersGui.rows(book, viewer, view(Sort.DEMAND_DESC, pf))), "without bonus");
		}
		ctx.detail(calm ? "bonus full and used up, " + pairs + " pairs" : "news/market day/price cap active: only checked pairwise (" + pairs + " pairs)");
	}

	private static void persistence(TestContext ctx) {
		UUID a = player(ctx, "OrdA", 0).getUUID();
		UUID b = player(ctx, "OrdB", 0).getUUID();
		View fresh = OrderSort.load(new View(), a);
		eq(Sort.PRICE_DESC, fresh.sort, "default: price ↓ (most expensive first)");
		isTrue(fresh.playersFirst, "default: players first on");
		OrderSort.save(a, Sort.NAME_AZ, false);
		OrderSort.save(b, Sort.NEWEST, true);
		View relog = OrderSort.load(new View(), a);
		eq(Sort.NAME_AZ, relog.sort, "after relog: Name A–Z");
		isFalse(relog.playersFirst, "after relog: players first off");
		View other = OrderSort.load(new View(), b);
		eq(Sort.NEWEST, other.sort, "separate per player (B: newest)");
		isTrue(other.playersFirst, "B: players first on");
		eq("name_az", PlayerDataService.getString(a, OrderSort.KEY_SORT), "saved as stable id");
		// the way players.json writes and reads it (Gson)
		PlayerDataService.PlayerData data = PlayerDataService.peek(a).orElseThrow();
		PlayerDataService.PlayerData copy = DataStore.gson().fromJson(DataStore.gson().toJson(data), PlayerDataService.PlayerData.class);
		JsonElement sort = copy.blobs.get(OrderSort.KEY_SORT);
		JsonElement pf = copy.blobs.get(OrderSort.KEY_PLAYERS_FIRST);
		isTrue(sort != null && "name_az".equals(sort.getAsString()), "sort survives save/load");
		isTrue(pf != null && !pf.getAsBoolean(), "toggle survives save/load");
		OrderSort.save(a, Sort.DEFAULT, true);
		isFalse(PlayerDataService.peek(a).orElseThrow().blobs.containsKey(OrderSort.KEY_SORT), "default sort not saved");
		isFalse(PlayerDataService.peek(a).orElseThrow().blobs.containsKey(OrderSort.KEY_PLAYERS_FIRST), "default toggle not saved");
		eq(Sort.DEFAULT, OrderSort.load(new View(), a).sort, "default again");
		for (Sort s : Sort.values()) {
			for (boolean on : new boolean[] {false, true}) {
				OrderSort.save(a, s, on);
				View back = OrderSort.load(new View(), a);
				eq(s, back.sort, "saved/read " + s.id);
				eq(on, back.playersFirst, "saved/read toggle " + s.id);
				eq(s, Sort.byId(s.id).orElseThrow(), "ID " + s.id);
			}
		}
		PlayerDataService.setString(a, OrderSort.KEY_SORT, "broken");
		PlayerDataService.setString(a, OrderSort.KEY_PLAYERS_FIRST, "maybe");
		View broken = OrderSort.load(new View(), a);
		eq(Sort.DEFAULT, broken.sort, "unknown sort gives default");
		isTrue(broken.playersFirst, "unreadable toggle gives on");
		OrderSort.save(a, Sort.DEFAULT, false);
		isFalse(OrderSort.load(new View(), a).playersFirst, "overwrites unreadable toggle");
		ctx.detail("2 players separate, Gson round trip, " + (Sort.values().length * 2) + " combinations");
	}

	private static void cache450(TestContext ctx) {
		ctx.cleanup(() -> PriceModifiers.removeSource(MOD_SOURCE));
		PriceModifiers.removeSource(MOD_SOURCE);
		FakePlayer owner = player(ctx, "OrdA", 1_000_000_000L);
		FakePlayer viewer = player(ctx, "OrdB", 0);
		UUID who = viewer.getUUID();
		long[] clock = {T0};
		OrdersConfig cfg = new OrdersConfig();
		cfg.maxOrdersPerPlayer = 100;
		OrderBook book = book(clock, cfg);
		book.tick();
		Random rnd = new Random(7);
		List<Item> items = new ArrayList<>(BasePrices.sellPrices().keySet());
		items.sort((x, y) -> Items.id(x).compareTo(Items.id(y)));
		int made = 0;
		for (Item item : items) {
			if (made >= 40) break;
			if (OrderBook.needsTemplate(item) || OrderBook.isUnique(item)) continue;
			create(book, clock, owner, item, 1 + rnd.nextInt(500), 1 + rnd.nextInt(10_000));
			made++;
		}
		int total = book.data().orders.size();
		isTrue(total >= 400, "about 450 orders (" + total + ")");
		View view = new View();
		long coldMax = 0;
		long coldSum = 0;
		int pairs = 0;
		for (Sort sort : Sort.values()) {
			for (boolean pf : new boolean[] {true, false}) {
				view.sort = sort;
				view.playersFirst = pf;
				long t = System.nanoTime();
				List<Row> rows = OrdersGui.rows(book, viewer, view);
				long us = (System.nanoTime() - t) / 1_000;
				coldMax = Math.max(coldMax, us);
				coldSum += us;
				eq(total + (pf ? 2 : 0), rows.size(), sort.id + (pf ? " an" : " off") + ": all orders (+ headers)");
				pairs += checkSorted(book, who, sort, pf, orders(rows), "450");
			}
		}
		eq(Sort.values().length * 2, view.cache.builds, "each state sorted exactly once");
		long t = System.nanoTime();
		int flips = 0;
		for (Sort sort : Sort.values()) {
			for (boolean pf : new boolean[] {true, false}) {
				view.sort = sort;
				view.playersFirst = pf;
				for (int i = 0; i < 10; i++, flips++) OrdersGui.rows(book, viewer, view);
			}
		}
		long warmAvgUs = (System.nanoTime() - t) / 1_000 / flips;
		eq(Sort.values().length * 2, view.cache.builds, "paging doesn't resort");
		isTrue(warmAvgUs < 20_000, "page build from cache " + warmAvgUs + " µs");
		isTrue(coldMax < 1_000_000, "sorting ≤ 1 s (" + coldMax / 1000 + " ms)");
		view.sort = Sort.PRICE_DESC;
		view.playersFirst = true;
		int builds = view.cache.builds;
		clock[0] += 1;
		Order top = create(book, clock, owner, DIAMOND, 1, 5_000_000);
		List<Row> rows = OrdersGui.rows(book, viewer, view);
		eq(builds + 1, view.cache.builds, "create drops the cache");
		eq(top.id, ((Entry) rows.get(1)).order().id, "new most expensive player order on top at once");
		List<Order> board = orders(rows).stream().filter(o -> o.board).toList();
		Order first = board.get(0);
		Item firstItem = first.itemType().orElseThrow();
		int n = Math.min(first.open(), 64 * 8);
		dev.smpeconomy.core.Items.giveOrDrop(viewer, new ItemStack(firstItem, n));
		long unitBefore = book.unitPrice(who, first);
		builds = view.cache.builds;
		DeliverResult r = book.deliver(viewer, first.id, n);
		eq(Status.OK, r.status(), "delivered to the most expensive board order (" + first.item + ")");
		viewer.getInventory().clearContent();
		List<Order> after = orders(OrdersGui.rows(book, viewer, view));
		eq(builds + 1, view.cache.builds, "delivery drops the cache");
		checkSorted(book, who, Sort.PRICE_DESC, true, after, "after delivery");
		if (first.open() > 0) isTrue(book.unitPrice(who, first) <= unitBefore, "price not higher after delivery");
		else isFalse(after.contains(first), "filled order disappears");
		builds = view.cache.builds;
		OrdersGui.rows(book, viewer, view);
		eq(builds, view.cache.builds, "no change: cache");
		PriceModifiers.set(MOD_SOURCE, COAL, 2.0, 0, 60_000, 0, "Breaking News");
		OrdersGui.rows(book, viewer, view);
		eq(builds + 1, view.cache.builds, "news drops the cache");
		PriceModifiers.removeSource(MOD_SOURCE);
		OrdersGui.rows(book, viewer, view);
		eq(builds + 2, view.cache.builds, "news ending drops the cache");
		builds = view.cache.builds;
		clock[0] += OrderSort.CACHE_TTL_MS - 1;
		OrdersGui.rows(book, viewer, view);
		eq(builds, view.cache.builds, "within validity: cache");
		clock[0] += 1;
		OrdersGui.rows(book, viewer, view);
		eq(builds + 1, view.cache.builds, "after " + OrderSort.CACHE_TTL_MS / 1000 + " s resorted");
		builds = view.cache.builds;
		view.filter = OrdersGui.Filter.NPC;
		OrdersGui.rows(book, viewer, view);
		view.query = "coal";
		OrdersGui.rows(book, viewer, view);
		view.filter = OrdersGui.Filter.DELIVERABLE;
		OrdersGui.rows(book, viewer, view);
		eq(builds, view.cache.builds, "filter and search use the sorted list");
		ctx.detail(total + " orders, " + pairs + " pairs, sort avg " + coldSum / (Sort.values().length * 2) / 1000.0 + " ms (max "
			+ coldMax / 1000.0 + " ms), page from cache avg " + warmAvgUs / 1000.0 + " ms");
	}
}
