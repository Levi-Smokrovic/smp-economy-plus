package dev.smpeconomy.features.sell;

import static dev.smpeconomy.core.test.Assert.check;
import static dev.smpeconomy.core.test.Assert.eq;
import static dev.smpeconomy.core.test.Assert.isFalse;
import static dev.smpeconomy.core.test.Assert.isTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantments;
import dev.smpeconomy.core.BasePrices;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Hooks;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.PriceGuard;
import dev.smpeconomy.core.PriceModifiers;
import dev.smpeconomy.core.test.SelfTests;
import dev.smpeconomy.core.test.TestContext;

/** Sell logic selftests without GUI clicks; e2e/sell.mjs covers the GUI. */
final class SellSelfTests {
	private static final String F = "sell";
	private static final String SRC = "sell.selftest";

	private static final Item WHEAT = net.minecraft.world.item.Items.WHEAT;
	private static final Item DIAMOND = net.minecraft.world.item.Items.DIAMOND;
	private static final Item COBBLE = net.minecraft.world.item.Items.COBBLESTONE;
	private static final Item BEDROCK = net.minecraft.world.item.Items.BEDROCK;
	private static final Item SHIELD = net.minecraft.world.item.Items.SHIELD;

	private SellSelfTests() {}

	static void register() {
		SelfTests.register(F, "pristine-rule", SellSelfTests::pristineRule);
		SelfTests.register(F, "multipliers-applied", SellSelfTests::multipliersApplied);
		SelfTests.register(F, "pivot-and-crash", SellSelfTests::pivotAndCrash);
		SelfTests.register(F, "sell-hand", SellSelfTests::sellHand);
		SelfTests.register(F, "sell-all-excludes-armor-offhand", SellSelfTests::sellAllExcludesArmorOffhand);
		SelfTests.register(F, "sell-all-confirms-only-preview", SellSelfTests::sellAllConfirmsOnlyPreview);
		SelfTests.register(F, "unsellables-returned", SellSelfTests::unsellablesReturned);
		SelfTests.register(F, "no-loss-disconnect-path", SellSelfTests::noLossDisconnectPath);
		SelfTests.register(F, "no-loss-death-and-failures", SellSelfTests::noLossDeathAndFailures);
		SelfTests.register(F, "death-returns-into-inventory", SellSelfTests::deathReturnsIntoInventory);
		SelfTests.register(F, "full-inventory-pending-return", SellSelfTests::fullInventoryPendingReturn);
		SelfTests.register(F, "dimension-change-deferred", SellSelfTests::dimensionChangeDeferred);
		SelfTests.register(F, "worth-lines", SellSelfTests::worthLines);
		SelfTests.register(F, "crash-journal", SellSelfTests::crashJournal);
	}

	private static FakePlayer player(TestContext ctx, String name) {
		FakePlayer p = ctx.player(name);
		EconomyService.ensureAccount(p.getUUID(), name);
		return p;
	}

	private static ItemStack named(Item item, int count) {
		ItemStack s = new ItemStack(item, count);
		s.set(DataComponents.CUSTOM_NAME, Component.literal("Special"));
		return s;
	}

	private static ItemStack enchanted(TestContext ctx, Item item) {
		ItemStack s = new ItemStack(item);
		s.enchant(ctx.server().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS), 1);
		return s;
	}

	private static long price(FakePlayer p, Item item) {
		OptionalLong v = PriceGuard.effectiveSellPrice(p.getUUID(), item);
		check(v.isPresent(), Items.id(item) + " must be sellable");
		return v.getAsLong();
	}

	private static int countMain(FakePlayer p, Item item, boolean pristineOnly) {
		return Items.count(p, s -> s.is(item) && (!pristineOnly || Items.isPristine(s)));
	}

	private static int countDrops(TestContext ctx, FakePlayer p) {
		int n = 0;
		for (ItemStack s : ctx.drops(p)) n += s.getCount();
		return n;
	}

	private static String text(List<Component> lines) {
		StringBuilder sb = new StringBuilder();
		for (Component c : lines) sb.append(c.getString()).append('\n');
		return sb.toString();
	}

	private static SellSession session(FakePlayer p, ItemStack... stacks) {
		SellSession s = new SellSession(p);
		for (int i = 0; i < stacks.length; i++) s.container().setItem(i, stacks[i]);
		return s;
	}

	private static void pristineRule(TestContext ctx) {
		FakePlayer p = player(ctx, "SellPristine");
		UUID id = p.getUUID();
		check(SellService.unitPrice(id, new ItemStack(DIAMOND)).isPresent(), "unmodified diamond sellable");
		eq(price(p, DIAMOND), SellService.unitPrice(id, new ItemStack(DIAMOND, 64)).getAsLong(), "unit price = PriceGuard");
		eq(null, SellService.notSellableReason(id, new ItemStack(DIAMOND, 3)), "no reason for unmodified diamond");

		ItemStack damaged = new ItemStack(DIAMOND);
		damaged.set(DataComponents.DAMAGE, 3);
		ItemStack filled = new ItemStack(DIAMOND);
		filled.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(DIAMOND, 5))));
		ItemStack lore = new ItemStack(DIAMOND);
		lore.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Signed"))));
		Object[][] cases = {
			{named(DIAMOND, 1), "renamed"}, {enchanted(ctx, DIAMOND), "enchanted"}, {damaged, "damaged"}, {filled, "has contents"},
			{lore, "modified"},
		};
		for (Object[] c : cases) {
			ItemStack s = (ItemStack) c[0];
			isFalse(Items.isPristine(s), c[1] + ": not pristine");
			isTrue(SellService.unitPrice(id, s).isEmpty(), c[1] + ": no price");
			eq(c[1], SellService.modifiedReason(s), "reason " + c[1]);
			String why = SellService.notSellableReason(id, s);
			check(why != null && why.contains((String) c[1]), c[1] + ": message names the reason (" + why + ")");
		}
		isTrue(BasePrices.sellPrice(BEDROCK).isEmpty(), "bedrock has no price");
		isTrue(SellService.unitPrice(id, new ItemStack(BEDROCK)).isEmpty(), "bedrock not sellable");
		check(SellService.notSellableReason(id, new ItemStack(BEDROCK)).contains("can't be sold"), "reason: no price");
		isTrue(SellService.unitPrice(id, ItemStack.EMPTY).isEmpty(), "empty stack");

		SellService.Quote q = SellService.quote(id, List.of(new ItemStack(DIAMOND, 2), named(DIAMOND, 5), enchanted(ctx, DIAMOND),
			new ItemStack(BEDROCK, 7), new ItemStack(DIAMOND, 3)));
		eq(5, q.count(), "only 5 unmodified diamonds on offer");
		eq(1, q.lines().size(), "one line item");
		eq(5 * price(p, DIAMOND), q.total(), "sum");
		ctx.detail(cases.length + " kinds of modification rejected");
	}

	private static void multipliersApplied(TestContext ctx) {
		FakePlayer p = player(ctx, "SellMult");
		UUID id = p.getUUID();
		isTrue(BasePrices.shopBuyPrice(DIAMOND).isEmpty(), "diamond is no shop item (no pivot)");
		long base = BasePrices.sellPrice(DIAMOND).orElseThrow();
		long before = price(p, DIAMOND);

		Hooks.Registration mult = Hooks.addSellMultiplier(SRC, "Selftest-Rank", (u, item) -> id.equals(u) && item == DIAMOND ? 1.5 : 1.0);
		ctx.cleanup(mult::remove);
		ctx.cleanup(() -> PriceModifiers.removeSource(SRC));
		PriceModifiers.set(SRC, DIAMOND, 2.0, 0, 60_000, 0, "Selftest-News");
		List<long[]> sold = new ArrayList<>();
		Hooks.Registration listener = Hooks.onItemsSold((pl, cents, count) -> {
			if (pl.getUUID().equals(id)) sold.add(new long[] {cents, count});
		});
		ctx.cleanup(listener::remove);

		check(Math.abs(PriceModifiers.get(SRC, DIAMOND) - 2.0) < 1e-9, "news factor active at once");
		check(Hooks.sellParts(id, DIAMOND).stream().anyMatch(part -> part.id().equals(SRC) && part.factor() == 1.5), "sell multiplier registered");
		long after = price(p, DIAMOND);
		eq(PriceGuard.computeSell(base, PriceModifiers.get(DIAMOND), Hooks.sellMultiplier(id, DIAMOND), -1), after, "PriceGuard formula");
		check(after >= 3 * before && after < 3 * before + 3, "×2 news × 1.5 rank ≈ ×3 (" + before + " → " + after + ")");
		long otherPlayer = PriceGuard.effectiveSellPrice(UUID.randomUUID(), DIAMOND).orElse(-1);
		check(otherPlayer < after, "rank bonus only for this player");

		Inventory inv = p.getInventory();
		inv.setSelectedSlot(0);
		inv.setItem(0, new ItemStack(DIAMOND, 3));
		long bal0 = EconomyService.balance(id);
		SellService.Attempt a = SellService.sellHand(p);
		eq(null, a.error(), "no error");
		SellService.Outcome o = a.outcome();
		eq(SellService.Status.SOLD, o.status(), "sold");
		eq(3 * after, o.cents(), "proceeds with multipliers");
		eq(bal0 + 3 * after, EconomyService.balance(id), "balance");
		eq(1, sold.size(), "onItemsSold exactly once");
		eq(3 * after, sold.get(0)[0], "onItemsSold cents");
		eq(3L, sold.get(0)[1], "onItemsSold count");
		check(o.bonuses().stream().anyMatch(b -> b.contains("Selftest-Rank") && b.contains("+50%")), "rank bonus in the summary " + o.bonuses());
		check(o.bonuses().stream().anyMatch(b -> b.contains("Selftest-News") && b.contains("+100%")), "news in the summary " + o.bonuses());
		String summary = SellService.summaryText(o);
		check(summary.contains("Selftest-Rank") && summary.contains("3 items"), "chat line names bonuses and count: " + summary);
		eq(3L, PlayerDataService.getCounter(id, SellService.COUNTER_ITEMS), "counter items");
		eq(3 * after, PlayerDataService.getCounter(id, SellService.COUNTER_EARNED), "counter proceeds");
		ctx.detail(Money.format(before) + " → " + Money.format(after) + " per diamond");
	}

	private static void pivotAndCrash(TestContext ctx) {
		FakePlayer p = player(ctx, "SellPivot");
		UUID id = p.getUUID();
		ctx.cleanup(() -> PriceModifiers.removeSource(SRC));
		Item shopItem = null;
		for (Map.Entry<Item, Long> e : BasePrices.shopBuyPrices().entrySet()) {
			OptionalLong sell = BasePrices.sellPrice(e.getKey());
			if (sell.isPresent() && sell.getAsLong() * 20 > PriceGuard.pivotPrice(e.getKey())) {
				shopItem = e.getKey();
				break;
			}
		}
		check(shopItem != null, "shop item with sell price found");
		long pivot = PriceGuard.pivotPrice(shopItem);
		PriceModifiers.set(SRC, shopItem, 20.0, 0, 60_000, 0, "Selftest-Hype");
		long eff = price(p, shopItem);
		check(eff <= pivot, "hype ×20 stays at the pivot (" + eff + " ≤ " + pivot + ")");
		eq(pivot, eff, "exactly at the pivot");
		List<String> bonuses = SellService.bonuses(id, List.of(new SellService.Line(shopItem, 1, eff, eff)));
		check(bonuses.contains("§8Price cap"), "price cap displayed " + bonuses);

		Item cheap = null;
		for (Map.Entry<Item, Long> e : BasePrices.sellPrices().entrySet()) {
			if (e.getValue() * PriceModifiers.MIN_FACTOR * Hooks.sellMultiplier(id, e.getKey()) < 1.0) {
				cheap = e.getKey();
				break;
			}
		}
		check(cheap != null, "cheap item found");
		PriceModifiers.set(SRC, cheap, PriceModifiers.MIN_FACTOR, 0, 60_000, 0, "Selftest-Crash");
		isTrue(SellService.unitPrice(id, new ItemStack(cheap)).isEmpty(), Items.id(cheap) + " below 1 cent not sellable");
		check(SellService.notSellableReason(id, new ItemStack(cheap)).contains("worth nothing"), "reason: below 1 cent");
		Inventory inv = p.getInventory();
		inv.setSelectedSlot(0);
		inv.setItem(0, new ItemStack(cheap, 10));
		long bal0 = EconomyService.balance(id);
		check(SellService.sellHand(p).error() != null, "/sell hand rejects");
		eq(10, inv.getItem(0).getCount(), "stack unchanged");
		eq(bal0, EconomyService.balance(id), "no money");
		ctx.detail(Items.id(shopItem) + " capped at " + Money.format(pivot) + ", " + Items.id(cheap) + " unsellable in the crash");
	}

	private static void sellHand(TestContext ctx) {
		FakePlayer p = player(ctx, "SellHand");
		UUID id = p.getUUID();
		Inventory inv = p.getInventory();
		inv.setSelectedSlot(2);
		long bal0 = EconomyService.balance(id);
		check(SellService.sellHand(p).error().contains("not holding anything"), "empty hand");

		inv.setItem(2, named(DIAMOND, 4));
		check(SellService.sellHand(p).error().contains("renamed"), "renamed diamond rejected");
		eq(4, inv.getItem(2).getCount(), "renamed stack stays");
		check(inv.getItem(2).has(DataComponents.CUSTOM_NAME), "name stays");

		inv.setItem(2, new ItemStack(BEDROCK, 2));
		check(SellService.sellHand(p).error().contains("can't be sold"), "item without price rejected");
		eq(2, inv.getItem(2).getCount(), "bedrock stays");
		eq(bal0, EconomyService.balance(id), "no money so far");

		long pW = price(p, WHEAT);
		inv.setItem(2, new ItemStack(WHEAT, 40));
		inv.setItem(3, new ItemStack(WHEAT, 64));
		inv.setItem(Inventory.SLOT_OFFHAND, new ItemStack(WHEAT, 5));
		SellService.Attempt a = SellService.sellHand(p);
		eq(null, a.error(), "no error");
		eq(40, a.outcome().soldCount(), "only the hand stack");
		eq(bal0 + 40 * pW, EconomyService.balance(id), "proceeds");
		isTrue(inv.getItem(2).isEmpty(), "hand empty");
		eq(64, inv.getItem(3).getCount(), "other slot untouched");
		eq(5, inv.getItem(Inventory.SLOT_OFFHAND).getCount(), "offhand untouched");

		inv.setItem(2, new ItemStack(WHEAT, 10));
		p.getAbilities().instabuild = true;
		try {
			check(SellService.sellHand(p).error().contains("Creative"), "creative rejected");
		} finally {
			p.getAbilities().instabuild = false;
		}
		eq(10, inv.getItem(2).getCount(), "stack stays in creative");
		eq(bal0 + 40 * pW, EconomyService.balance(id), "no money in creative");
	}

	private static void sellAllExcludesArmorOffhand(TestContext ctx) {
		FakePlayer p = player(ctx, "SellAll");
		UUID id = p.getUUID();
		ctx.cleanup(() -> SellService.clearPending(id));
		Item skull = net.minecraft.world.item.Items.WITHER_SKELETON_SKULL;
		check(BasePrices.sellPrice(skull).isPresent(), "skull is sellable (for the armor test)");
		Inventory inv = p.getInventory();
		inv.setItem(0, new ItemStack(WHEAT, 64));
		inv.setItem(9, new ItemStack(COBBLE, 32));
		inv.setItem(10, named(DIAMOND, 1));
		inv.setItem(35, new ItemStack(BEDROCK, 1));
		inv.setItem(EquipmentSlot.HEAD.getIndex(Inventory.INVENTORY_SIZE), new ItemStack(skull));
		inv.setItem(Inventory.SLOT_OFFHAND, new ItemStack(WHEAT, 10));
		long pW = price(p, WHEAT);
		long pC = price(p, COBBLE);
		long expected = 64 * pW + 32 * pC;
		long bal0 = EconomyService.balance(id);
		long confirmMs = SellConfig.get().confirmSeconds * 1000L;

		SellService.Attempt first = SellService.sellAll(p, 1_000);
		check(first.preview() != null, "preview first");
		eq(96, first.preview().count(), "preview: 96 items");
		eq(expected, first.preview().total(), "preview total");
		eq(64, inv.getItem(0).getCount(), "preview changes nothing");
		eq(bal0, EconomyService.balance(id), "preview pays nothing");

		long late = 1_000 + confirmMs + 1;
		SellService.Attempt expired = SellService.sellAll(p, late);
		check(expired.preview() != null, "preview again after expiry, no sale");
		eq(bal0, EconomyService.balance(id), "expired: nothing sold");

		SellService.Attempt done = SellService.sellAll(p, late + confirmMs);
		check(done.outcome() != null, "repeat within the window sells");
		eq(96, done.outcome().soldCount(), "96 items sold");
		eq(bal0 + expected, EconomyService.balance(id), "proceeds");
		isTrue(inv.getItem(0).isEmpty() && inv.getItem(9).isEmpty(), "wheat and cobblestone gone");
		eq(1, inv.getItem(10).getCount(), "renamed diamond stays");
		eq(1, inv.getItem(35).getCount(), "bedrock stays");
		check(p.getItemBySlot(EquipmentSlot.HEAD).is(skull), "armor (head) stays");
		eq(10, inv.getItem(Inventory.SLOT_OFFHAND).getCount(), "offhand stays");
		eq(0, countDrops(ctx, p), "nothing dropped");

		check(SellService.sellAll(p, late + confirmMs + 10).error().contains("nothing sellable"), "nothing sellable afterwards");

		inv.setItem(0, new ItemStack(WHEAT, 5));
		check(SellService.sellAll(p, 50_000).preview() != null, "preview");
		p.getAbilities().instabuild = true;
		try {
			check(SellService.sellAll(p, 50_001).error().contains("Creative"), "creative rejected");
		} finally {
			p.getAbilities().instabuild = false;
		}
		eq(5, inv.getItem(0).getCount(), "creative: nothing sold");
		check(SellService.sellAll(p, 50_002).preview() != null, "confirmation discarded");
	}

	private static void sellAllConfirmsOnlyPreview(TestContext ctx) {
		FakePlayer p = player(ctx, "SellAllChange");
		UUID id = p.getUUID();
		ctx.cleanup(() -> SellService.clearPending(id));
		ctx.cleanup(() -> PriceModifiers.removeSource(SRC));
		Inventory inv = p.getInventory();
		long pC = price(p, COBBLE);
		long pD = price(p, DIAMOND);
		long bal0 = EconomyService.balance(id);

		inv.setItem(0, new ItemStack(COBBLE, 64));
		SellService.Attempt a = SellService.sellAll(p, 1_000);
		check(a.preview() != null && !a.changed(), "preview first");
		eq(64 * pC, a.preview().total(), "preview: only cobblestone");
		inv.setItem(5, new ItemStack(DIAMOND, 5));
		SellService.Attempt b = SellService.sellAll(p, 2_000);
		check(b.preview() != null && b.changed(), "new items: new preview with notice, no sale");
		eq(64 * pC + 5 * pD, b.preview().total(), "new preview shows the diamonds");
		eq(bal0, EconomyService.balance(id), "nothing sold");
		eq(5, inv.getItem(5).getCount(), "diamonds stay");
		eq(64, inv.getItem(0).getCount(), "cobblestone stays");

		inv.setItem(0, new ItemStack(COBBLE, 32));
		SellService.Attempt c = SellService.sellAll(p, 3_000);
		check(c.preview() != null && c.changed(), "fewer items: new preview");
		eq(bal0, EconomyService.balance(id), "still nothing sold");
		SellService.Attempt d = SellService.sellAll(p, 4_000);
		check(d.outcome() != null, "unchanged repeat sells");
		eq(32 * pC + 5 * pD, d.outcome().cents(), "proceeds = last shown preview");
		long bal1 = bal0 + 32 * pC + 5 * pD;
		eq(bal1, EconomyService.balance(id), "balance");

		inv.setItem(1, new ItemStack(DIAMOND, 4));
		SellService.Attempt e = SellService.sellAll(p, 10_000);
		check(e.preview() != null && !e.changed(), "preview before the crash");
		eq(4 * pD, e.preview().total(), "preview at the old price");
		PriceModifiers.set(SRC, DIAMOND, 0.5, 0, 60_000, 0, "Selftest-Crash");
		long crashed = price(p, DIAMOND);
		check(crashed < pD, "crash lowers the price (" + pD + " → " + crashed + ")");
		SellService.Attempt f = SellService.sellAll(p, 11_000);
		check(f.preview() != null && f.changed(), "crash: new preview, no sale");
		eq(4 * crashed, f.preview().total(), "new preview at the crash price");
		eq(4, inv.getItem(1).getCount(), "crash: diamonds stay");
		eq(bal1, EconomyService.balance(id), "crash: nothing sold");
		SellService.Attempt g = SellService.sellAll(p, 12_000);
		check(g.outcome() != null, "confirmed crash preview sold");
		eq(4 * crashed, g.outcome().cents(), "proceeds = confirmed amount");
		long bal2 = bal1 + 4 * crashed;
		eq(bal2, EconomyService.balance(id), "balance after crash sale");

		PriceModifiers.remove(SRC, DIAMOND);
		inv.setItem(2, new ItemStack(DIAMOND, 3));
		SellService.Attempt h = SellService.sellAll(p, 20_000);
		check(h.preview() != null, "preview before the hype");
		eq(3 * pD, h.preview().total(), "preview at the normal price");
		PriceModifiers.set(SRC, DIAMOND, 2.0, 0, 60_000, 0, "Selftest-Hype");
		long hyped = price(p, DIAMOND);
		check(hyped > pD, "hype raises the price");
		SellService.Attempt i = SellService.sellAll(p, 21_000);
		check(i.outcome() != null, "higher price: confirmation holds");
		eq(3 * hyped, i.outcome().cents(), "proceeds at current price");
		eq(bal2 + 3 * hyped, EconomyService.balance(id), "balance after hype sale");
		eq(0, countDrops(ctx, p), "nothing dropped");
		ctx.detail("amount and price changes force a new confirmation");
	}

	private static void unsellablesReturned(TestContext ctx) {
		FakePlayer p = player(ctx, "SellReturn");
		UUID id = p.getUUID();
		long pW = price(p, WHEAT);
		long bal0 = EconomyService.balance(id);
		SellSession s = session(p, new ItemStack(WHEAT, 32), named(DIAMOND, 1), new ItemStack(BEDROCK, 5), enchanted(ctx, DIAMOND),
			new ItemStack(WHEAT, 16));
		SellService.Outcome o = s.finish();
		eq(SellService.Status.SOLD, o.status(), "sold");
		eq(48, o.soldCount(), "48 wheat sold");
		eq(48 * pW, o.cents(), "proceeds");
		eq(bal0 + 48 * pW, EconomyService.balance(id), "balance");
		eq(7, o.returnedCount(), "7 items back");
		eq(0, o.droppedCount(), "nothing dropped");
		eq(0, countMain(p, WHEAT, false), "no wheat in the inventory");
		eq(1, Items.count(p, st -> st.is(DIAMOND) && st.has(DataComponents.CUSTOM_NAME)), "renamed diamond back");
		eq(1, Items.count(p, st -> st.is(DIAMOND) && st.isEnchanted()), "enchanted diamond back (with enchantment)");
		eq(5, countMain(p, BEDROCK, false), "bedrock back");
		isTrue(s.container().isEmpty(), "container empty");

		FakePlayer full = player(ctx, "SellFull");
		UUID fid = full.getUUID();
		ctx.cleanup(() -> SellJournal.removeAllOf(fid));
		Inventory inv = full.getInventory();
		for (int i = 0; i < Items.MAIN_SIZE; i++) inv.setItem(i, new ItemStack(BEDROCK, 64));
		long balFull = EconomyService.balance(fid);
		SellSession s2 = session(full, new ItemStack(WHEAT, 10), named(DIAMOND, 1), new ItemStack(BEDROCK, 3));
		SellService.Outcome o2 = s2.finish();
		eq(10, o2.soldCount(), "wheat sold despite full inventory");
		eq(balFull + 10 * pW, EconomyService.balance(fid), "proceeds despite full inventory");
		eq(4, o2.returnedCount(), "4 items back");
		eq(0, o2.droppedCount(), "nothing dropped");
		eq(0, countDrops(ctx, full), "ctx.drops empty");
		isTrue(inv.getItem(Inventory.SLOT_OFFHAND).is(DIAMOND) && inv.getItem(Inventory.SLOT_OFFHAND).has(DataComponents.CUSTOM_NAME),
			"renamed diamond in the empty offhand");
		eq(3, o2.pendingCount(), "3 bedrock in the return store");
		eq(1, SellJournal.returnsOf(fid), "one return entry");
		eq(36 * 64, countMain(full, BEDROCK, false), "inventory still full");

		FakePlayer q = player(ctx, "SellNothing");
		long balQ = EconomyService.balance(q.getUUID());
		SellService.Outcome o3 = session(q, new ItemStack(BEDROCK, 2), named(WHEAT, 3)).finish();
		eq(SellService.Status.NOTHING_SELLABLE, o3.status(), "nothing sellable");
		eq(5, o3.returnedCount(), "everything back");
		SellSession cancel = session(q, new ItemStack(WHEAT, 20));
		cancel.cancelOnFinish();
		SellService.Outcome o4 = cancel.finish();
		eq(SellService.Status.CANCELLED, o4.status(), "cancelled");
		eq(20, countMain(q, WHEAT, true), "wheat back");
		eq(balQ, EconomyService.balance(q.getUUID()), "no money");
		eq(0, countDrops(ctx, q), "nothing dropped");
		ctx.detail("return to inventory, when full offhand + return store");
	}

	private static void noLossDisconnectPath(TestContext ctx) {
		FakePlayer p = player(ctx, "SellQuit");
		UUID id = p.getUUID();
		long pW = price(p, WHEAT);
		long bal0 = EconomyService.balance(id);
		int pending0 = PlayerDataService.getPendingMessages(id).size();
		SellSession s = session(p, new ItemStack(WHEAT, 64), new ItemStack(WHEAT, 64), named(DIAMOND, 2), new ItemStack(BEDROCK, 9));
		isFalse(SellService.canReceiveChat(p), "test player counts as unable to receive (as on leaving)");

		SellService.Outcome o = s.finish();
		eq(128, o.soldCount(), "128 wheat sold");
		eq(bal0 + 128 * pW, EconomyService.balance(id), "proceeds booked");
		eq(2, Items.count(p, st -> st.is(DIAMOND)), "diamonds back in the inventory");
		eq(9, countMain(p, BEDROCK, false), "bedrock back in the inventory");
		eq(0, countDrops(ctx, p), "nothing dropped");
		isTrue(s.isFinished() && s.container().isEmpty(), "session ended, container empty");
		List<String> pending = PlayerDataService.getPendingMessages(id);
		check(pending.size() > pending0 && pending.stream().anyMatch(m -> m.contains("Sold") && m.contains("128")),
			"summary as pending message " + pending);

		check(s.finish() == o, "repeated finish returns the same result");
		eq(bal0 + 128 * pW, EconomyService.balance(id), "no double proceeds");

		var slot = s.slot(0);
		isFalse(slot.mayPlace(new ItemStack(WHEAT)), "slot accepts nothing after finish");
		isFalse(slot.mayPickup(p), "slot gives nothing after finish");
		var open = new SellSession(p).slot(0);
		isTrue(open.mayPlace(new ItemStack(WHEAT)) && open.mayPickup(p), "open session accepts");
		ctx.detail("proceeds + return on leaving, idempotent");
	}

	/**
	 * Fallback path on death, where sgui closes only after the death drops (normally the die HEAD mixin has settled
	 * by then): unsellable items wait in the return store until respawn and are never dropped. Also account limit and
	 * creative: no loss, no money printing.
	 */
	private static void noLossDeathAndFailures(TestContext ctx) {
		FakePlayer p = player(ctx, "SellDeath");
		UUID id = p.getUUID();
		ctx.cleanup(() -> SellJournal.removeAllOf(id));
		long pW = price(p, WHEAT);
		long bal0 = EconomyService.balance(id);
		SellSession dead = session(p, new ItemStack(WHEAT, 30), named(DIAMOND, 1), new ItemStack(BEDROCK, 4));
		p.setHealth(0);
		SellService.Outcome o;
		try {
			o = dead.finish();
			eq(0, SellJournal.deliverNow(p), "dead: return store waits until respawn");
		} finally {
			p.setHealth(p.getMaxHealth());
		}
		eq(30, o.soldCount(), "dead: wheat sold");
		eq(bal0 + 30 * pW, EconomyService.balance(id), "dead: proceeds");
		eq(0, o.droppedCount(), "dead: nothing dropped");
		eq(5, o.pendingCount(), "dead: unsellable in the return store");
		eq(0, countDrops(ctx, p), "dead: ctx.drops empty");
		eq(0, countMain(p, BEDROCK, false), "dead: nothing in the inventory");
		eq(1, SellJournal.returnsOf(id), "dead: one return entry");
		eq(5, SellJournal.deliverNow(p), "after respawn: 5 items back");
		eq(4, countMain(p, BEDROCK, false), "bedrock back");
		eq(1, Items.count(p, st -> st.is(DIAMOND) && st.has(DataComponents.CUSTOM_NAME)), "renamed diamond back");
		eq(0, SellJournal.entriesOf(id), "return store empty");

		FakePlayer rich = player(ctx, "SellRich");
		UUID rid = rich.getUUID();
		check(EconomyService.set(rid, Money.MAX - 1).ok(), "account near the limit");
		SellService.Outcome fail = session(rich, new ItemStack(WHEAT, 64), new ItemStack(BEDROCK, 1)).finish();
		eq(SellService.Status.FAILED, fail.status(), "limit: failed");
		eq(Money.MAX - 1, EconomyService.balance(rid), "limit: balance unchanged");
		eq(64, countMain(rich, WHEAT, true), "limit: wheat back");
		eq(1, countMain(rich, BEDROCK, false), "limit: bedrock back");
		eq(65, fail.returnedCount(), "limit: everything back");

		FakePlayer creative = player(ctx, "SellCreative");
		long balC = EconomyService.balance(creative.getUUID());
		creative.getAbilities().instabuild = true;
		SellService.Outcome blocked;
		try {
			blocked = session(creative, new ItemStack(WHEAT, 64)).finish();
		} finally {
			creative.getAbilities().instabuild = false;
		}
		eq(SellService.Status.BLOCKED, blocked.status(), "creative: blocked");
		eq(64, countMain(creative, WHEAT, true), "creative: wheat back");
		eq(balC, EconomyService.balance(creative.getUUID()), "creative: no money");
		ctx.detail("death, account limit, creative without loss");
	}

	/**
	 * Death with an open GUI (mixin on {@code ServerPlayer#die} HEAD with {@code ReturnMode.INVENTORY}): the player
	 * is already dead at health 0, yet unsellable items land in the inventory, where graves, keep_inventory and
	 * vanilla drops take over.
	 */
	private static void deathReturnsIntoInventory(TestContext ctx) {
		FakePlayer p = player(ctx, "SellDeathInv");
		UUID id = p.getUUID();
		long pW = price(p, WHEAT);
		long bal0 = EconomyService.balance(id);
		SellSession s = session(p, new ItemStack(WHEAT, 30), named(DIAMOND, 1), new ItemStack(BEDROCK, 4), enchanted(ctx, DIAMOND));
		SellService.Outcome o;
		p.setHealth(0);
		try {
			isFalse(Items.canReceive(p), "dead: canReceive is false (as at the start of ServerPlayer#die)");
			o = s.finish(p, SellService.ReturnMode.INVENTORY);
		} finally {
			p.setHealth(p.getMaxHealth());
		}
		eq(SellService.Status.SOLD, o.status(), "dead: sold");
		eq(30, o.soldCount(), "dead: wheat sold");
		eq(bal0 + 30 * pW, EconomyService.balance(id), "dead: proceeds");
		eq(6, o.returnedCount(), "dead: 6 items back");
		eq(0, o.droppedCount(), "dead: nothing dropped");
		eq(0, countDrops(ctx, p), "dead: ctx.drops empty");
		eq(1, Items.count(p, st -> st.is(DIAMOND) && st.has(DataComponents.CUSTOM_NAME)), "renamed diamond in the inventory");
		eq(1, Items.count(p, st -> st.is(DIAMOND) && st.isEnchanted()), "enchanted diamond in the inventory");
		eq(4, countMain(p, BEDROCK, false), "bedrock in the inventory");
		eq(0, countMain(p, WHEAT, false), "sold wheat not in the inventory");
		isTrue(s.container().isEmpty(), "container empty");
		check(s.finish() == o, "sgui close afterwards (die TAIL) is a no-op");
		eq(bal0 + 30 * pW, EconomyService.balance(id), "no double proceeds");

		FakePlayer full = player(ctx, "SellDeathFull");
		UUID fid = full.getUUID();
		ctx.cleanup(() -> SellJournal.removeAllOf(fid));
		Inventory inv = full.getInventory();
		for (int i = 0; i < Items.MAIN_SIZE; i++) inv.setItem(i, new ItemStack(BEDROCK, 64));
		inv.setItem(7, new ItemStack(BEDROCK, 62));
		SellSession s2 = session(full, new ItemStack(BEDROCK, 5), named(DIAMOND, 1));
		SellService.Outcome o2;
		full.setHealth(0);
		try {
			o2 = s2.finish(full, SellService.ReturnMode.INVENTORY);
		} finally {
			full.setHealth(full.getMaxHealth());
		}
		eq(64, inv.getItem(7).getCount(), "full: partial stack topped up");
		eq(3, inv.getItem(Inventory.SLOT_OFFHAND).is(BEDROCK) ? inv.getItem(Inventory.SLOT_OFFHAND).getCount() : 0, "full: 3 bedrock in the offhand");
		eq(0, o2.droppedCount(), "full: nothing dropped");
		eq(0, countDrops(ctx, full), "full: ctx.drops empty");
		eq(1, o2.pendingCount(), "full: diamond in the return store (comes after respawn)");
		eq(1, SellJournal.returnsOf(fid), "full: one return entry");
		for (int i = 0; i < inv.getContainerSize(); i++) inv.setItem(i, ItemStack.EMPTY);
		eq(1, SellJournal.deliverNow(full), "back after respawn");
		eq(1, Items.count(full, st -> st.is(DIAMOND) && st.has(DataComponents.CUSTOM_NAME)), "renamed diamond back");
		eq(0, SellJournal.entriesOf(fid), "return store empty");
		ctx.detail("return to inventory on death (before grave/keep_inventory), overflow after respawn");
	}

	/**
	 * Returns that no longer fit (offhand swapped into the GUI with F, items picked up while the GUI was open): main
	 * inventory, then the empty offhand, then the return store with one entry per player. Never dropped. Only whole
	 * stacks come back, once they fit.
	 */
	private static void fullInventoryPendingReturn(TestContext ctx) {
		FakePlayer p = player(ctx, "SellReturnFull");
		UUID id = p.getUUID();
		ctx.cleanup(() -> SellJournal.removeAllOf(id));
		long pW = price(p, WHEAT);
		long bal0 = EconomyService.balance(id);
		Inventory inv = p.getInventory();
		for (int i = 0; i < Items.MAIN_SIZE; i++) inv.setItem(i, new ItemStack(BEDROCK, 64));

		SellSession a = session(p, new ItemStack(SHIELD));
		a.cancelOnFinish();
		SellService.Outcome oa = a.finish();
		eq(SellService.Status.CANCELLED, oa.status(), "a: cancelled");
		eq(1, oa.returnedCount(), "a: 1 item back");
		eq(0, oa.pendingCount() + oa.droppedCount(), "a: nothing waiting, nothing dropped");
		isTrue(inv.getItem(Inventory.SLOT_OFFHAND).is(SHIELD), "a: shield in the offhand");
		eq(0, SellJournal.returnsOf(id), "a: no return entry");

		int pending0 = PlayerDataService.getPendingMessages(id).size();
		SellSession b = session(p, named(DIAMOND, 1), new ItemStack(WHEAT, 20));
		b.cancelOnFinish();
		SellService.Outcome ob = b.finish();
		eq(SellService.Status.CANCELLED, ob.status(), "b: cancelled");
		eq(21, ob.returnedCount(), "b: 21 items returned");
		eq(21, ob.pendingCount(), "b: 21 items in the return store");
		eq(0, ob.droppedCount(), "b: nothing dropped");
		eq(0, countDrops(ctx, p), "b: ctx.drops empty");
		eq(1, SellJournal.returnsOf(id), "b: exactly 1 return entry");
		eq(21, SellJournal.waitingItemsOf(id), "b: 21 items waiting");
		eq(bal0, EconomyService.balance(id), "b: no money");
		List<String> msgs = PlayerDataService.getPendingMessages(id);
		check(msgs.size() > pending0 && msgs.stream().anyMatch(m -> m.contains("kept for you")), "b: notice about the return store " + msgs);
		eq(0, SellJournal.deliverNow(p), "b: no room, nothing back");
		eq(0, SellJournal.restore(p), "b: join without room, nothing back, nothing dropped");
		eq(0, countDrops(ctx, p), "b: nothing dropped on join either");

		SellService.Outcome oc = session(p, new ItemStack(WHEAT, 5), enchanted(ctx, DIAMOND)).finish();
		eq(SellService.Status.SOLD, oc.status(), "c: sold");
		eq(5, oc.soldCount(), "c: 5 wheat sold");
		eq(1, oc.pendingCount(), "c: enchanted diamond waiting");
		eq(1, SellJournal.returnsOf(id), "c: still 1 return entry");
		eq(22, SellJournal.waitingItemsOf(id), "c: 22 items waiting");

		inv.setItem(0, ItemStack.EMPTY);
		eq(1, SellJournal.deliverNow(p), "d: renamed diamond back");
		eq(1, Items.count(p, st -> st.is(DIAMOND) && st.has(DataComponents.CUSTOM_NAME)), "d: renamed diamond in the inventory");
		eq(21, SellJournal.waitingItemsOf(id), "d: 21 items still waiting");
		eq(1, SellJournal.returnsOf(id), "d: entry stays");
		msgs = PlayerDataService.getPendingMessages(id);
		check(msgs.stream().anyMatch(m -> m.contains("now in your inventory")), "d: notice about the return " + msgs);

		inv.setItem(1, ItemStack.EMPTY);
		inv.setItem(2, ItemStack.EMPTY);
		eq(21, SellJournal.deliverNow(p), "e: rest back");
		eq(20, countMain(p, WHEAT, true), "e: 20 wheat back");
		eq(1, Items.count(p, st -> st.is(DIAMOND) && st.isEnchanted()), "e: enchanted diamond back");
		eq(0, SellJournal.entriesOf(id), "e: return store empty");
		eq(0, SellJournal.deliverNow(p), "e: no second time");
		eq(bal0 + 5 * pW, EconomyService.balance(id), "e: only the sale from c paid");

		for (int i = 0; i < Items.MAIN_SIZE; i++) inv.setItem(i, new ItemStack(BEDROCK, 64));
		SellSession crashed = session(p, new ItemStack(WHEAT, 7));
		crashed.saveJournal();
		crashed.container().clearContent();
		SellJournal.forgetLive(crashed.id());
		eq(0, SellJournal.restore(p), "f: join without room, nothing back");
		eq(7, SellJournal.waitingItemsOf(id), "f: 7 wheat waiting");
		inv.setItem(3, ItemStack.EMPTY);
		eq(7, SellJournal.deliverNow(p), "f: room, 7 wheat back");
		eq(7, countMain(p, WHEAT, true), "f: wheat in the inventory");
		eq(0, SellJournal.entriesOf(id), "f: journal empty");
		eq(0, countDrops(ctx, p), "nothing ever dropped");
		ctx.detail("offhand, return store instead of drop, whole stacks, crash leftover");
	}

	private static void dimensionChangeDeferred(TestContext ctx) {
		isTrue(SellGui.isDimensionChange(true, Entity.RemovalReason.CHANGED_DIMENSION, false), "removed for dimension change");
		isFalse(SellGui.isDimensionChange(true, Entity.RemovalReason.KILLED, false), "death/respawn is no dimension change");
		isFalse(SellGui.isDimensionChange(true, Entity.RemovalReason.UNLOADED_WITH_PLAYER, false), "leaving is no dimension change");
		isFalse(SellGui.isDimensionChange(true, Entity.RemovalReason.CHANGED_DIMENSION, true), "disconnected: settle at once");
		isFalse(SellGui.isDimensionChange(false, null, false), "not removed");

		FakePlayer old = player(ctx, "SellDimOld");
		FakePlayer now = player(ctx, "SellDimNew");
		isFalse(SellGui.isDimensionChange(old), "test player in the world");
		long pW = price(old, WHEAT);
		long balOld = EconomyService.balance(old.getUUID());
		long balNew = EconomyService.balance(now.getUUID());
		SellSession s = session(old, new ItemStack(WHEAT, 20), named(DIAMOND, 1));
		s.lock();
		var slot = s.slot(0);
		isFalse(slot.mayPlace(new ItemStack(WHEAT)), "deferred: slot accepts nothing");
		isFalse(slot.mayPickup(old), "deferred: slot gives nothing");
		isFalse(s.isFinished(), "deferred: not settled yet");
		eq(21, s.snapshot().stream().mapToInt(ItemStack::getCount).sum(), "contents unchanged");

		SellService.Outcome o = s.finish(now, SellService.ReturnMode.NORMAL);
		eq(20, o.soldCount(), "settled for the current player object");
		eq(balNew + 20 * pW, EconomyService.balance(now.getUUID()), "proceeds on the current player object");
		eq(balOld, EconomyService.balance(old.getUUID()), "old object gets nothing");
		eq(1, Items.count(now, st -> st.is(DIAMOND) && st.has(DataComponents.CUSTOM_NAME)), "unsellable in the current inventory");
		eq(0, Items.count(old, st -> st.is(DIAMOND)), "nothing in the old inventory");
		eq(0, countDrops(ctx, now) + countDrops(ctx, old), "nothing dropped");

		FakePlayer stop = player(ctx, "SellDimStop");
		UUID sid = stop.getUUID();
		ctx.cleanup(() -> SellJournal.removeAllOf(sid));
		long balStop = EconomyService.balance(sid);
		SellSession st = session(stop, new ItemStack(WHEAT, 10), named(DIAMOND, 1));
		st.lock();
		isTrue(st.postponeToJournal(), "journal takes over");
		isTrue(st.isFinished() && st.container().isEmpty(), "session ended, container empty");
		eq(SellService.Status.POSTPONED, st.outcome().status(), "Status POSTPONED");
		check(st.finish() == st.outcome(), "finish afterwards changes nothing");
		eq(1, SellJournal.entriesOf(sid), "entry in the journal");
		eq(balStop, EconomyService.balance(sid), "nothing sold");
		eq(0, countMain(stop, WHEAT, false), "nothing back yet");
		eq(11, SellJournal.restore(stop), "join: 11 items back");
		eq(10, countMain(stop, WHEAT, true), "wheat back");
		eq(1, Items.count(stop, it -> it.is(DIAMOND) && it.has(DataComponents.CUSTOM_NAME)), "renamed diamond back");
		eq(0, SellJournal.entriesOf(sid), "journal empty");
		eq(balStop, EconomyService.balance(sid), "return pays nothing");
		ctx.detail("deferral, target player object and journal on stop");
	}

	private static void worthLines(TestContext ctx) {
		FakePlayer p = player(ctx, "SellWorth");
		UUID id = p.getUUID();
		ctx.cleanup(() -> PriceModifiers.removeSource(SRC));
		long base = BasePrices.sellPrice(DIAMOND).orElseThrow();
		String plain = text(SellService.worthLines(id, new ItemStack(DIAMOND, 5), 5));
		check(plain.contains("Base price: " + Money.format(base)), "base price: " + plain);
		long eff = price(p, DIAMOND);
		check(plain.contains("Effective: " + Money.format(eff) + " each"), "effective: " + plain);
		check(plain.contains("Stack (5): " + Money.format(5 * eff)), "stack value: " + plain);

		PriceModifiers.set(SRC, DIAMOND, 2.0, 0, 60_000, 0, "Selftest-News");
		long eff2 = price(p, DIAMOND);
		String hyped = text(SellService.worthLines(id, new ItemStack(DIAMOND, 1), 1));
		check(hyped.contains("Selftest-News") && hyped.contains("+100%"), "modifier source: " + hyped);
		check(hyped.contains("Modifier: " + PriceGuard.percent((double) eff2 / base)), "current modifier: " + hyped);
		check(hyped.contains("Effective: " + Money.format(eff2)), "effective price: " + hyped);

		String none = text(SellService.worthLines(id, new ItemStack(BEDROCK), 1));
		check(none.contains("Can't be sold"), "without price: " + none);
		String renamed = text(SellService.worthLines(id, named(DIAMOND, 1), 1));
		check(renamed.contains("can't be sold: renamed"), "renamed: " + renamed);
	}
	private static void crashJournal(TestContext ctx) {
		FakePlayer p = player(ctx, "SellCrash");
		UUID id = p.getUUID();
		ctx.cleanup(() -> SellJournal.removeAllOf(id));
		long pW = price(p, WHEAT);
		long bal0 = EconomyService.balance(id);

		SellSession s = session(p, new ItemStack(WHEAT, 32), named(DIAMOND, 1));
		s.saveJournal();
		eq(1, SellJournal.entriesOf(id), "open session in the journal");
		s.container().setItem(0, ItemStack.EMPTY);
		s.container().setItem(1, ItemStack.EMPTY);
		s.saveJournal();
		eq(0, SellJournal.entriesOf(id), "empty session: no entry");
		s.container().setItem(3, new ItemStack(WHEAT, 32));
		s.saveJournal();
		eq(1, SellJournal.entriesOf(id), "filled again");
		eq(0, SellJournal.restore(p), "running sessions aren't returned");
		eq(32, s.finish().soldCount(), "regular end sells");
		eq(0, SellJournal.entriesOf(id), "regular end removes the entry");
		s.saveJournal();
		eq(0, SellJournal.entriesOf(id), "no new entry after the end");
		eq(bal0 + 32 * pW, EconomyService.balance(id), "proceeds");

		SellSession crashed = session(p, new ItemStack(WHEAT, 10), named(DIAMOND, 1));
		crashed.saveJournal();
		crashed.container().clearContent();
		SellJournal.forgetLive(crashed.id());
		int pending0 = PlayerDataService.getPendingMessages(id).size();
		eq(11, SellJournal.restore(p), "11 items back");
		eq(10, countMain(p, WHEAT, true), "wheat back in the inventory");
		eq(1, Items.count(p, st -> st.is(DIAMOND) && st.has(DataComponents.CUSTOM_NAME)), "renamed diamond back with name");
		eq(0, SellJournal.entriesOf(id), "entry removed");
		eq(0, SellJournal.restore(p), "no second time");
		eq(bal0 + 32 * pW, EconomyService.balance(id), "return pays nothing");
		check(PlayerDataService.getPendingMessages(id).size() > pending0, "notice on join");

		SellJournal.putRaw(SRC + "-broken", id, "SellCrash", List.of("broken"));
		eq(0, SellJournal.restore(p), "undecodable: nothing back");
		eq(1, SellJournal.entriesOf(id), "undecodable entry stays");
		eq(0, countDrops(ctx, p), "nothing dropped");
		ctx.detail("write/remove journal, return after crash");
	}
}
