package dev.smpeconomy.features.orders;

import static dev.smpeconomy.core.test.Assert.check;
import static dev.smpeconomy.core.test.Assert.eq;
import static dev.smpeconomy.core.test.Assert.isFalse;
import static dev.smpeconomy.core.test.Assert.isTrue;
import static dev.smpeconomy.core.test.Assert.notNull;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.Random;
import java.util.UUID;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import dev.smpeconomy.core.BasePrices;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Hooks;
import dev.smpeconomy.core.ItemCodec;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.PriceGuard;
import dev.smpeconomy.core.PriceModifiers;
import dev.smpeconomy.core.Services;
import dev.smpeconomy.core.Settings;
import dev.smpeconomy.core.TimeUtil;
import dev.smpeconomy.core.Treasury;
import dev.smpeconomy.core.test.SelfTests;
import dev.smpeconomy.core.test.TestContext;
import dev.smpeconomy.features.orders.OrderBook.CancelResult;
import dev.smpeconomy.features.orders.OrderBook.ClaimResult;
import dev.smpeconomy.features.orders.OrderBook.CreateResult;
import dev.smpeconomy.features.orders.OrderBook.DeliverResult;
import dev.smpeconomy.features.orders.OrderBook.Status;
import dev.smpeconomy.features.orders.OrderBook.Terms;
import dev.smpeconomy.features.orders.OrderData.Claim;
import dev.smpeconomy.features.orders.OrderData.Order;

/**
 * Selftests for orders. Each test works on its own {@link OrderBook} with its own data and clock, so real orders stay
 * untouched. Treasury: {@link FakeFunding}, or the real one with {@code ctx.preserveTreasury()}.
 */
final class OrdersSelfTests {
	private static final String F = OrdersFeature.ID;
	private static final String MOD_SOURCE = "orders.selftest";
	private static final Item DIAMOND = net.minecraft.world.item.Items.DIAMOND;
	private static final Item IRON = net.minecraft.world.item.Items.IRON_INGOT;
	private static final Item WHEAT = net.minecraft.world.item.Items.WHEAT;
	private static final Item COBBLE = net.minecraft.world.item.Items.COBBLESTONE;
	private static final Item SWORD = net.minecraft.world.item.Items.DIAMOND_SWORD;
	private static final Item DIRT = net.minecraft.world.item.Items.DIRT;
	private static final Item BEDROCK = net.minecraft.world.item.Items.BEDROCK;
	private static final Item BOOK = net.minecraft.world.item.Items.ENCHANTED_BOOK;
	private static final Item POTION = net.minecraft.world.item.Items.POTION;
	private static final Item IRON_BLOCK = net.minecraft.world.item.Items.IRON_BLOCK;
	private static final Item IRON_NUGGET = net.minecraft.world.item.Items.IRON_NUGGET;
	private static final Item HAY = net.minecraft.world.item.Items.HAY_BLOCK;
	private static final long T0 = 1_800_000_000_000L;

	private OrdersSelfTests() {}

	static void register() {
		SelfTests.register(F, "create-validation", OrdersSelfTests::createValidation);
		SelfTests.register(F, "escrow-create-cancel", OrdersSelfTests::escrowCreateCancel);
		SelfTests.register(F, "partial-delivery", OrdersSelfTests::partialDelivery);
		SelfTests.register(F, "over-delivery-clamp", OrdersSelfTests::overDeliveryClamp);
		SelfTests.register(F, "fee-split-invariant", OrdersSelfTests::feeSplitInvariant);
		SelfTests.register(F, "pristine-rule", OrdersSelfTests::pristineRule);
		SelfTests.register(F, "enchanted-allowed", OrdersSelfTests::enchantedAllowed);
		SelfTests.register(F, "claim-box", OrdersSelfTests::claimBox);
		SelfTests.register(F, "expiry-refund", OrdersSelfTests::expiryRefund);
		SelfTests.register(F, "notifications-setting", OrdersSelfTests::notificationsSetting);
		SelfTests.register(F, "npc-delivery-pays-deletes", OrdersSelfTests::npcDelivery);
		SelfTests.register(F, "npc-guard-recheck", OrdersSelfTests::npcGuardRecheck);
		SelfTests.register(F, "deliver-price-changed", OrdersSelfTests::deliverPriceChanged);
		SelfTests.register(F, "deliver-creative-blocked", OrdersSelfTests::deliverCreativeBlocked);
		SelfTests.register(F, "npc-bulk-order", OrdersSelfTests::npcBulk);
		SelfTests.register(F, "treasury-live", OrdersSelfTests::treasuryLive);
		SelfTests.register(F, "conservation-random", OrdersSelfTests::conservationRandom);
		SelfTests.register(F, "persistence-roundtrip", OrdersSelfTests::persistence);
		SelfTests.register(F, "nice-amount", ctx -> niceAmount());
		SelfTests.register(F, "template-enchanted-book", OrdersSelfTests::templateEnchantedBook);
		SelfTests.register(F, "template-potion-tool", OrdersSelfTests::templatePotionTool);
		SelfTests.register(F, "npc-conversion-arbitrage", OrdersSelfTests::npcConversionArbitrage);
		SelfTests.register(F, "npc-conversion-sweep", OrdersSelfTests::npcConversionSweep);
		SelfTests.register(F, "board-fill", OrdersSelfTests::boardFill);
		SelfTests.register(F, "board-price-dynamics", OrdersSelfTests::boardPriceDynamics);
		SelfTests.register(F, "board-news-multiplier", OrdersSelfTests::boardNewsMultiplier);
		SelfTests.register(F, "board-players-first", OrdersSelfTests::boardPlayersFirst);
		SelfTests.register(F, "board-money-bounds-10k", OrdersSelfTests::boardMoneyBounds);
		SelfTests.register(F, "board-flip-bound", OrdersSelfTests::boardFlipBound);
		SelfTests.register(F, "board-daily-bonus", OrdersSelfTests::boardDailyBonus);
		SelfTests.register(F, "board-sell-boost", OrdersSelfTests::boardSellBoost);
		SelfTests.register(F, "board-pristine-flip", OrdersSelfTests::boardPristineFlip);
		SelfTests.register(F, "board-terms", OrdersSelfTests::boardTerms);
		SelfTests.register(F, "board-legacy-migration", OrdersSelfTests::boardLegacyMigration);
		SelfTests.register(F, "board-lifecycle", OrdersSelfTests::boardLifecycle);
		SelfTests.register(F, "board-no-arbitrage", OrdersSelfTests::boardNoArbitrage);
		SelfTests.register(F, "board-persistence", OrdersSelfTests::boardPersistence);
	}

	static final class FakeFunding implements OrderFunding {
		long till;
		long budget;
		long hourCap;
		long fees;
		long refunds;
		long withdrawn;

		FakeFunding(long till, long budget, long hourCap) {
			this.till = till;
			this.budget = budget;
			this.hourCap = hourCap;
		}

		@Override
		public long allowance() {
			return Math.max(0, Math.min(budget, till));
		}

		@Override
		public long maxSpendPerHour() {
			return hourCap;
		}

		@Override
		public boolean withdrawEscrow(long cents) {
			if (cents <= 0 || cents > allowance()) return false;
			till -= cents;
			budget -= cents;
			withdrawn += cents;
			return true;
		}

		@Override
		public void refundEscrow(long cents) {
			till += cents;
			refunds += cents;
		}

		@Override
		public void collectFee(long cents) {
			till += cents;
			fees += cents;
		}
	}

	private static OrderBook book(OrderData data, long[] clock, OrderFunding funding, OrdersConfig cfg) {
		return new OrderBook(() -> data, () -> {}, () -> clock[0], funding, new Random(42), () -> cfg, () -> true);
	}

	private static OrderBook book(long[] clock, OrderFunding funding) {
		return book(new OrderData(), clock, funding, new OrdersConfig());
	}

	private static FakePlayer player(TestContext ctx, String name, long balance) {
		FakePlayer p = ctx.player(name);
		if (p.gameMode() != GameType.SURVIVAL) p.setGameMode(GameType.SURVIVAL);
		p.getAbilities().instabuild = false;
		EconomyService.ensureAccount(p.getUUID(), name);
		EconomyService.set(p.getUUID(), balance);
		return p;
	}

	private static int count(FakePlayer p, Item item) {
		return Items.count(p, s -> s.is(item));
	}

	private static void give(FakePlayer p, ItemStack stack) {
		eq(0, Items.giveOrDrop(p, stack), "test items fit in the inventory");
	}

	private static long bal(FakePlayer p) {
		return EconomyService.balance(p.getUUID());
	}

	private static Order create(OrderBook book, FakePlayer owner, Item item, int amount, long price) {
		CreateResult r = book.createPlayerOrder(owner.getUUID(), item, amount, price, false);
		eq(Status.OK, r.status(), "create order " + Items.id(item));
		return r.order();
	}

	private static long limit(OrderBook book, UUID deliverer, Item item) {
		return Math.min(npcCap(deliverer, item), book.conversionCap(deliverer, item));
	}

	private static long npcCap(UUID deliverer, Item item) {
		OptionalLong market = Services.marketValue(item);
		check(market.isPresent(), "market value for " + Items.id(item));
		return PriceGuard.maxNpcOrderPrice(deliverer, item, market.getAsLong());
	}

	private static void createValidation(TestContext ctx) {
		FakePlayer a = player(ctx, "OrdA", 100_000);
		OrdersConfig cfg = new OrdersConfig();
		cfg.maxOrdersPerPlayer = 2;
		cfg.maxAmountPerOrder = 1000;
		OrderBook book = book(new OrderData(), new long[] {T0}, new FakeFunding(0, 0, 0), cfg);
		UUID id = a.getUUID();
		eq(Status.INVALID_AMOUNT, book.createPlayerOrder(id, DIAMOND, 0, 100, false).status(), "amount 0");
		eq(Status.INVALID_AMOUNT, book.createPlayerOrder(id, DIAMOND, 1001, 1, false).status(), "amount above maximum");
		eq(Status.INVALID_PRICE, book.createPlayerOrder(id, DIAMOND, 1, 0, false).status(), "price 0");
		eq(Status.INVALID_PRICE, book.createPlayerOrder(id, DIAMOND, 1, -5, false).status(), "price negative");
		eq(Status.INVALID_PRICE, book.createPlayerOrder(id, DIAMOND, 1, Money.MAX + 1, false).status(), "price above MAX");
		eq(Status.TOO_EXPENSIVE, book.createPlayerOrder(id, DIAMOND, 1000, Money.MAX, false).status(), "total above MAX");
		eq(Status.INVALID_ITEM, book.createPlayerOrder(id, net.minecraft.world.item.Items.AIR, 1, 100, false).status(), "room");
		eq(Status.INSUFFICIENT_FUNDS, book.createPlayerOrder(id, DIAMOND, 10, 10_001, false).status(), "not enough money");
		eq(Status.ERROR, book.createPlayerOrder(Treasury.ID, DIAMOND, 1, 1, false).status(), "system account");
		eq(100_000L, bal(a), "nothing charged");
		eq(0, book.data().orders.size(), "no order");
		create(book, a, DIAMOND, 1, 100);
		create(book, a, DIAMOND, 1, 100);
		eq(Status.LIMIT, book.createPlayerOrder(id, DIAMOND, 1, 100, false).status(), "limit per player");
		eq(99_800L, bal(a), "exactly 2 × $1 reserved");
		cfg.maxOrdersPerPlayer = 5;
		eq(Status.OK, book.createPlayerOrder(id, IRON, 998, 100, false).status(), "entire balance");
		eq(0L, bal(a), "account empty");
		eq(100_000L, book.escrowOf(id), "everything in escrow");
		ctx.detail("11 edge cases ok");
	}

	private static void escrowCreateCancel(TestContext ctx) {
		FakePlayer a = player(ctx, "OrdA", 100_000);
		FakePlayer b = player(ctx, "OrdB", 0);
		OrderBook book = book(new long[] {T0}, new FakeFunding(0, 0, 0));
		Order o = create(book, a, DIAMOND, 10, 5_000);
		eq(50_000L, bal(a), "escrow charged");
		eq(50_000L, o.escrow, "escrow in the order");
		eq(100_000L, bal(a) + book.totalEscrow(), "conservation after create");
		eq(T0 + 7 * OrderBook.DAY_MS, o.expiresAt, "runs 7 days");
		eq(Status.NOT_OWNER, book.cancel(b.getUUID(), o.id, false).status(), "other player may not cancel");
		CancelResult r = book.cancel(a.getUUID(), o.id, false);
		eq(Status.OK, r.status(), "cancel");
		eq(50_000L, r.refunded(), "rest refunded");
		eq(100_000L, bal(a), "balance full again");
		eq(0L, book.totalEscrow(), "no escrow left");
		isTrue(book.get(o.id).isEmpty(), "order gone");
		eq(Status.NOT_FOUND, book.cancel(a.getUUID(), o.id, false).status(), "second cancel");
		eq(100_000L, bal(a), "no double refund");
		ctx.detail("create/cancel preserves money");
	}

	private static void partialDelivery(TestContext ctx) {
		FakePlayer a = player(ctx, "OrdA", 100_000);
		FakePlayer b = player(ctx, "OrdB", 0);
		FakeFunding fund = new FakeFunding(0, 0, 0);
		OrderBook book = book(new long[] {T0}, fund);
		Order o = create(book, a, DIAMOND, 10, 5_000);
		give(b, new ItemStack(DIAMOND, 4));
		DeliverResult r = book.deliver(b, o.id, 64);
		eq(Status.OK, r.status(), "deliver");
		eq(4, r.count(), "4 delivered");
		eq(20_000L, r.gross(), "gross");
		long fee = Money.percentOf(20_000, book.config().deliveryFeePercent);
		eq(fee, r.fee(), "fee");
		eq(20_000L - fee, bal(b), "deliverer paid (minus fee)");
		eq(fee, fund.fees, "fee to the treasury");
		eq(0, count(b, DIAMOND), "diamonds gone");
		eq(4, o.delivered, "progress");
		eq(30_000L, o.escrow, "rest escrow = 6 × price");
		isFalse(r.completed(), "still open");
		isTrue(book.get(o.id).isPresent(), "order active");
		eq(4L, book.claimTotal(a.getUUID()), "4 in the claim chest");
		eq(100_000L, bal(a) + bal(b) + book.totalEscrow() + fund.fees, "money preserved");
		give(a, new ItemStack(DIAMOND, 1));
		eq(Status.OWN_ORDER, book.deliver(a, o.id, 1).status(), "own order");
		eq(1, count(a, DIAMOND), "item stays");
		eq(Status.NO_ITEMS, book.deliver(b, o.id, 5).status(), "no items");
		eq(Status.INVALID_AMOUNT, book.deliver(b, o.id, 0).status(), "amount 0");
		eq(Status.NOT_FOUND, book.deliver(b, 999_999, 1).status(), "unknown order");
		ctx.detail("4/10 delivered, fee " + Money.format(fee));
	}

	private static void overDeliveryClamp(TestContext ctx) {
		FakePlayer a = player(ctx, "OrdA", 100_000);
		FakePlayer b = player(ctx, "OrdB", 0);
		FakeFunding fund = new FakeFunding(0, 0, 0);
		OrderBook book = book(new long[] {T0}, fund);
		Order o = create(book, a, IRON, 5, 1_000);
		give(b, new ItemStack(IRON, 20));
		DeliverResult r = book.deliver(b, o.id, 20);
		eq(Status.OK, r.status(), "deliver");
		eq(5, r.count(), "only the open amount");
		eq(15, count(b, IRON), "rest stays in the inventory");
		isTrue(r.completed(), "done");
		isTrue(book.get(o.id).isEmpty(), "order removed");
		eq(0L, book.totalEscrow(), "escrow used up");
		eq(5L, book.claimTotal(a.getUUID()), "5 in the claim chest");
		eq(Status.NOT_FOUND, book.deliver(b, o.id, 1).status(), "nothing further afterwards");
		eq(15, count(b, IRON), "nothing further taken");
		eq(100_000L, bal(a) + bal(b) + book.totalEscrow() + fund.fees, "money preserved");
		ctx.detail("20 offered, 5 taken");
	}

	/** Delivering one at a time saves no fee: total fees = floor(total value × rate). */
	private static void feeSplitInvariant(TestContext ctx) {
		FakePlayer a = player(ctx, "OrdA", 100_000);
		FakePlayer b = player(ctx, "OrdB", 0);
		FakeFunding fund = new FakeFunding(0, 0, 0);
		OrderBook book = book(new long[] {T0}, fund);
		Order o = create(book, a, COBBLE, 100, 7);
		give(b, new ItemStack(COBBLE, 100));
		for (int i = 0; i < 100; i++) eq(Status.OK, book.deliver(b, o.id, 1).status(), "single delivery " + i);
		long expectedFee = Money.percentOf(700, book.config().deliveryFeePercent);
		eq(expectedFee, fund.fees, "total fees as with one delivery");
		eq(700L - expectedFee, bal(b), "deliverer net");
		eq(100_000L, bal(a) + bal(b) + book.totalEscrow() + fund.fees, "money preserved");
		Order o2 = create(book, a, COBBLE, 100, 7);
		give(b, new ItemStack(COBBLE, 100));
		long feesBefore = fund.fees;
		eq(100, book.deliver(b, o2.id, 100).count(), "all at once");
		eq(expectedFee, fund.fees - feesBefore, "same fee");
		ctx.detail("100 × single = 1 × 100: " + Money.format(expectedFee));
	}

	private static void pristineRule(TestContext ctx) {
		FakePlayer a = player(ctx, "OrdA", 100_000);
		FakePlayer b = player(ctx, "OrdB", 0);
		OrderBook book = book(new long[] {T0}, new FakeFunding(0, 0, 0));
		var registries = ctx.server().registryAccess();
		var sharpness = registries.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS);
		Order o = create(book, a, SWORD, 3, 1_000);
		ItemStack damaged = new ItemStack(SWORD);
		damaged.setDamageValue(10);
		ItemStack enchanted = new ItemStack(SWORD);
		enchanted.enchant(sharpness, 2);
		ItemStack named = new ItemStack(SWORD);
		named.set(DataComponents.CUSTOM_NAME, Component.literal("Special"));
		give(b, damaged);
		give(b, enchanted);
		give(b, named);
		give(b, new ItemStack(DIAMOND, 5));
		eq(0, book.countDeliverable(b, o), "nothing matches");
		eq(Status.NO_ITEMS, book.deliver(b, o.id, 3).status(), "modified items rejected");
		eq(3, count(b, SWORD), "all swords still there");
		give(b, new ItemStack(SWORD));
		eq(1, book.countDeliverable(b, o), "one unmodified matches");
		DeliverResult r = book.deliver(b, o.id, 3);
		eq(1, r.count(), "only the unmodified one delivered");
		eq(3, count(b, SWORD), "modified ones stay");
		isTrue(Items.count(b, s -> s.is(SWORD) && Items.isPristine(s)) == 0, "no unmodified left");
		Order npc = new Order();
		npc.npc = true;
		npc.allowEnchanted = true;
		isFalse(OrderBook.accepts(npc, SWORD, enchanted), "NPC: no enchantments");
		isTrue(OrderBook.accepts(npc, SWORD, new ItemStack(SWORD)), "NPC: unmodified ok");
		ctx.detail("damage/enchantment/name/other item rejected");
	}

	private static void enchantedAllowed(TestContext ctx) {
		FakePlayer a = player(ctx, "OrdA", 100_000);
		FakePlayer b = player(ctx, "OrdB", 0);
		OrderBook book = book(new long[] {T0}, new FakeFunding(0, 0, 0));
		var registries = ctx.server().registryAccess();
		var sharpness = registries.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS);
		CreateResult cr = book.createPlayerOrder(a.getUUID(), SWORD, 4, 1_000, true);
		eq(Status.OK, cr.status(), "order with enchantments");
		Order o = cr.order();
		ItemStack ench = new ItemStack(SWORD);
		ench.enchant(sharpness, 3);
		ItemStack anvil = ench.copy();
		anvil.set(DataComponents.REPAIR_COST, 1);
		ItemStack named = ench.copy();
		named.set(DataComponents.CUSTOM_NAME, Component.literal("Excalibur"));
		ItemStack damaged = ench.copy();
		damaged.setDamageValue(3);
		isTrue(OrderBook.accepts(o, SWORD, ench), "enchanted ok");
		isTrue(OrderBook.accepts(o, SWORD, anvil), "repair cost ok");
		isFalse(OrderBook.accepts(o, SWORD, named), "name rejected");
		isFalse(OrderBook.accepts(o, SWORD, damaged), "damage rejected");
		give(b, ench.copy());
		give(b, named.copy());
		give(b, new ItemStack(SWORD));
		DeliverResult r = book.deliver(b, o.id, 4);
		eq(2, r.count(), "enchanted + unmodified delivered");
		eq(1, count(b, SWORD), "named one stays");
		eq(2L, book.claimTotal(a.getUUID()), "2 in the chest");
		List<Claim> claims = book.claims(a.getUUID());
		eq(2, claims.size(), "two different entries");
		isTrue(claims.stream().anyMatch(c -> c.nbt != null), "enchanted saved with components");
		ClaimResult cl = book.claimAll(a);
		eq(2, cl.given(), "claimed");
		isTrue(Items.count(a, s -> ItemStack.isSameItemSameComponents(s, ench)) == 1, "enchantment preserved on claim");
		isTrue(Items.count(a, s -> s.is(SWORD) && Items.isPristine(s)) == 1, "unmodified claimed");
		isTrue(ctx.drops(a).isEmpty(), "nothing dropped");
		ctx.detail("enchantment preserved, name/damage rejected");
	}

	private static void claimBox(TestContext ctx) {
		FakePlayer a = player(ctx, "OrdA", 100_000);
		FakePlayer b = player(ctx, "OrdB", 0);
		OrderBook book = book(new long[] {T0}, new FakeFunding(0, 0, 0));
		Order o = create(book, a, COBBLE, 200, 1);
		give(b, new ItemStack(COBBLE, 200));
		eq(200, book.deliver(b, o.id, 200).count(), "200 delivered");
		eq(200L, book.claimTotal(a.getUUID()), "200 in the chest");
		for (int i = 0; i < Items.MAIN_SIZE - 2; i++) a.getInventory().setItem(i, new ItemStack(DIRT, 64));
		ClaimResult r = book.claimAll(a);
		eq(Status.OK, r.status(), "partly claimed");
		eq(128, r.given(), "only what fits (2 slots)");
		eq(72L, r.left(), "rest stays");
		eq(128, count(a, COBBLE), "128 in the inventory");
		isTrue(ctx.drops(a).isEmpty(), "nothing dropped");
		ClaimResult full = book.claimAll(a);
		eq(Status.NO_SPACE, full.status(), "full");
		eq(72L, book.claimTotal(a.getUUID()), "unchanged");
		for (int i = 0; i < Items.MAIN_SIZE; i++) {
			if (a.getInventory().getItem(i).is(DIRT)) a.getInventory().setItem(i, ItemStack.EMPTY);
		}
		ClaimResult partial = book.claim(a, Items.id(COBBLE), null, 10);
		eq(10, partial.given(), "partial amount");
		eq(62L, partial.left(), "rest");
		ClaimResult rest = book.claimAll(a);
		eq(62, rest.given(), "rest claimed");
		eq(0L, book.claimTotal(a.getUUID()), "chest empty");
		isTrue(book.data().claims.get(a.getUUID()) == null, "entry removed");
		eq(200, count(a, COBBLE), "all 200 arrived");
		eq(Status.NOT_FOUND, book.claimAll(a).status(), "empty chest");
		isTrue(ctx.drops(a).isEmpty(), "nothing dropped");
		book.data().claims.put(a.getUUID(), new ArrayList<>(List.of(new Claim("minecraft:stone", "broken!!", 3))));
		eq(Status.BROKEN, book.claim(a, "minecraft:stone", "broken!!", 3).status(), "broken entry");
		eq(3L, book.claimTotal(a.getUUID()), "broken entry not deleted");
		ctx.detail("128 + 10 + 62, no drop");
	}

	private static void expiryRefund(TestContext ctx) {
		FakePlayer a = player(ctx, "OrdA", 100_000);
		FakePlayer b = player(ctx, "OrdB", 0);
		long[] clock = {T0};
		FakeFunding fund = new FakeFunding(0, 0, 0);
		OrderBook book = book(clock, fund);
		Order o = create(book, a, WHEAT, 10, 100);
		give(b, new ItemStack(WHEAT, 3));
		eq(3, book.deliver(b, o.id, 3).count(), "3 delivered");
		long afterDelivery = bal(a);
		clock[0] = o.expiresAt - 1;
		eq(0, book.expireDue(), "not expired yet");
		clock[0] = o.expiresAt;
		give(b, new ItemStack(WHEAT, 2));
		eq(Status.EXPIRED, book.deliver(b, o.id, 2).status(), "expired: no delivery");
		eq(2, count(b, WHEAT), "items stay");
		int pendingBefore = PlayerDataService.getPendingMessages(a.getUUID()).size();
		eq(1, book.expireDue(), "expired");
		eq(afterDelivery + 700, bal(a), "7 × $1 refunded");
		isTrue(book.get(o.id).isEmpty(), "order removed");
		eq(3L, book.claimTotal(a.getUUID()), "delivered items stay in the chest");
		List<String> pending = PlayerDataService.getPendingMessages(a.getUUID());
		eq(pendingBefore + 1, pending.size(), "offline message queued");
		isTrue(pending.get(pending.size() - 1).contains("#" + o.id), "message names the order");
		eq(0, book.expireDue(), "not doubled");
		eq(100_000L, bal(a) + bal(b) + book.totalEscrow() + fund.fees, "money preserved");
		ctx.detail("7/10 refunded, chest stays");
	}

	private static void notificationsSetting(TestContext ctx) {
		FakePlayer a = player(ctx, "OrdA", 100_000);
		FakePlayer b = player(ctx, "OrdB", 0);
		long[] clock = {T0};
		OrderBook book = book(clock, new FakeFunding(0, 0, 0));
		Order o = create(book, a, WHEAT, 10, 100);
		give(b, new ItemStack(WHEAT, 10));
		Settings.set(a.getUUID(), Settings.NOTIFICATIONS, false);
		book.deliver(b, o.id, 2);
		isTrue(book.data().away.get(a.getUUID()) == null, "off: no note");
		Settings.set(a.getUUID(), Settings.NOTIFICATIONS, true);
		book.deliver(b, o.id, 3);
		book.deliver(b, o.id, 1);
		var notes = book.data().away.get(a.getUUID());
		notNull(notes, "on: note for offline player");
		eq(1, notes.size(), "one note per order");
		eq(4L, notes.get(0).count, "merged");
		eq(1, book.takeAwayNotes(a.getUUID()).size(), "claim");
		isTrue(book.data().away.get(a.getUUID()) == null, "empty afterwards");
		Settings.set(a.getUUID(), Settings.NOTIFICATIONS, false);
		int pendingBefore = PlayerDataService.getPendingMessages(a.getUUID()).size();
		clock[0] = o.expiresAt;
		eq(1, book.expireDue(), "expired");
		eq(pendingBefore, PlayerDataService.getPendingMessages(a.getUUID()).size(), "off: no expiry message");
		ctx.detail("notifications toggle respected");
	}

	private static void npcDelivery(TestContext ctx) {
		FakePlayer b = player(ctx, "OrdB", 0);
		FakeFunding fund = new FakeFunding(10_000_000, 1_000_000, 1_000_000);
		long[] clock = {T0};
		OrderBook book = book(clock, fund);
		long cap = limit(book, b.getUUID(), WHEAT);
		check(cap > 0, "wheat price cap > 0");
		Order o = book.createNpcOrder(WHEAT, 32, cap, OrderBook.HOUR_MS, OrderBook.NPC_LABEL, false);
		notNull(o, "NPC order created");
		eq(32 * cap, o.escrow, "escrow from the treasury");
		eq(10_000_000L - 32 * cap, fund.till, "treasury charged");
		give(b, new ItemStack(WHEAT, 40));
		DeliverResult r = book.deliver(b, o.id, 40);
		eq(Status.OK, r.status(), "deliver");
		eq(32, r.count(), "only the open amount");
		eq(0L, r.fee(), "no fee for NPC");
		eq(32 * cap, bal(b), "deliverer paid");
		eq(8, count(b, WHEAT), "rest stays");
		isTrue(r.completed(), "done");
		isTrue(book.data().claims.isEmpty(), "items vanish (no claim chest)");
		eq(10_000_000L, fund.till + bal(b), "money preserved");
		ctx.detail("32 × " + Money.format(cap));
	}

	/**
	 * Creative (unlimited items) and spectator must not deliver, otherwise conjured items get swapped for escrow or
	 * treasury money.
	 */
	private static void deliverCreativeBlocked(TestContext ctx) {
		FakePlayer a = player(ctx, "OrdA", 100_000);
		FakePlayer b = player(ctx, "OrdB", 0);
		FakeFunding fund = new FakeFunding(10_000_000, 1_000_000, 1_000_000);
		OrdersConfig cfg = new OrdersConfig();
		OrderBook book = book(new OrderData(), new long[] {T0}, fund, cfg);
		Order po = create(book, a, DIAMOND, 10, 5_000);
		long cap = limit(book, b.getUUID(), WHEAT);
		check(cap > 0, "wheat price cap > 0");
		Order npc = book.createNpcOrder(WHEAT, 16, cap, OrderBook.HOUR_MS, OrderBook.NPC_LABEL, false);
		notNull(npc, "NPC order created");
		long till = fund.till;
		give(b, new ItemStack(DIAMOND, 4));
		give(b, new ItemStack(WHEAT, 16));
		isFalse(cfg.allowCreative, "default: allowCreative off");

		b.setGameMode(GameType.CREATIVE);
		try {
			isTrue(book.deliveryBlocked(b), "creative blocked");
			eq(Status.CREATIVE, book.deliver(b, po.id, 4).status(), "player order: creative rejected");
			eq(Status.CREATIVE, book.deliver(b, npc.id, 16).status(), "NPC order: creative rejected");
		} finally {
			b.setGameMode(GameType.SURVIVAL);
		}
		b.getAbilities().instabuild = true;
		try {
			eq(Status.CREATIVE, book.deliver(b, npc.id, 16).status(), "unlimited items rejected");
		} finally {
			b.getAbilities().instabuild = false;
		}
		b.setGameMode(GameType.SPECTATOR);
		try {
			isTrue(book.deliveryBlocked(b), "spectator blocked");
			eq(Status.CREATIVE, book.deliver(b, po.id, 4).status(), "player order: spectator rejected");
			eq(Status.CREATIVE, book.deliver(b, npc.id, 16).status(), "NPC order: spectator rejected");
		} finally {
			b.setGameMode(GameType.SURVIVAL);
		}
		eq(4, count(b, DIAMOND), "diamonds stay");
		eq(16, count(b, WHEAT), "wheat stays");
		eq(0L, bal(b), "no money");
		eq(50_000L, po.escrow, "player escrow untouched");
		eq(16 * cap, npc.escrow, "NPC escrow untouched");
		eq(till, fund.till, "treasury untouched");
		eq(0, po.delivered + npc.delivered, "nothing delivered");
		eq(0L, book.claimTotal(a.getUUID()), "claim chest empty");

		isFalse(book.deliveryBlocked(b), "survival allowed");
		DeliverResult r = book.deliver(b, po.id, 4);
		eq(Status.OK, r.status(), "survival delivers");
		eq(4, r.count(), "4 diamonds delivered");

		cfg.allowCreative = true;
		b.setGameMode(GameType.CREATIVE);
		try {
			isFalse(book.deliveryBlocked(b), "allowCreative allows creative");
			DeliverResult r2 = book.deliver(b, npc.id, 16);
			eq(Status.OK, r2.status(), "delivered with allowCreative");
			eq(16, r2.count(), "16 wheat delivered");
		} finally {
			b.setGameMode(GameType.SURVIVAL);
		}
		ctx.detail("creative/spectator: nothing moved; survival + allowCreative deliver");
	}

	private static void npcGuardRecheck(TestContext ctx) {
		FakePlayer b = player(ctx, "OrdB", 0);
		FakeFunding fund = new FakeFunding(100_000_000, 10_000_000, 10_000_000);
		OrderBook book = book(new long[] {T0}, fund);
		ctx.cleanup(() -> PriceModifiers.removeSource(MOD_SOURCE));
		UUID who = b.getUUID();
		long cap = limit(book, who, WHEAT);
		// order price deliberately far above the cap: only the cap is paid
		Order o = book.createNpcOrder(WHEAT, 20, cap * 10, OrderBook.HOUR_MS, OrderBook.NPC_LABEL, false);
		notNull(o, "NPC order");
		eq(cap, book.unitPrice(who, o), "price capped at price cap");
		isTrue(cap <= npcCap(who, WHEAT), "never above PriceGuard");
		give(b, new ItemStack(WHEAT, 20));
		DeliverResult r1 = book.deliver(b, o.id, 5);
		eq(5 * cap, r1.net(), "5 × price cap");
		long guard1 = npcCap(who, WHEAT);
		PriceModifiers.set(MOD_SOURCE, WHEAT, 0.5, 0, 60_000, 0, "Selftest");
		long guard2 = npcCap(who, WHEAT);
		long cap2 = limit(book, who, WHEAT);
		isTrue(guard2 < guard1, "cap falls in crash (" + guard1 + " → " + guard2 + ")");
		eq(cap2, book.unitPrice(who, o), "rechecked on delivery");
		DeliverResult r2 = book.deliver(b, o.id, 5);
		eq(5 * cap2, r2.net(), "5 × new cap");
		PriceModifiers.set(MOD_SOURCE, WHEAT, 20, 0, 60_000, 0, "Selftest");
		long cap3 = npcCap(who, WHEAT);
		long conv3 = book.conversionCap(who, WHEAT);
		eq(Math.min(Math.min(cap3, o.price), conv3), book.unitPrice(who, o), "hype: min(cap, order price, hay bale/9)");
		isTrue(book.unitPrice(who, o) * 9 < systemFloor(who, HAY), "hype: buying hay bale + delivering yields no profit");
		PriceModifiers.removeSource(MOD_SOURCE);
		long spentBefore = bal(b);
		long cap4 = limit(book, who, WHEAT);
		DeliverResult r3 = book.deliver(b, o.id, 10);
		eq(10, r3.count(), "rest delivered");
		eq(spentBefore + 10 * cap4, bal(b), "normal price again");
		isTrue(r3.completed(), "done");
		long paid = bal(b);
		eq(o.amount * o.price - paid, fund.refunds, "rest escrow back to the treasury");
		eq(100_000_000L, fund.till + bal(b), "money preserved");
		Order none = book.createNpcOrder(BEDROCK, 4, 100, OrderBook.HOUR_MS, OrderBook.NPC_LABEL, false);
		notNull(none, "NPC order without market value");
		give(b, new ItemStack(BEDROCK, 4));
		if (Services.marketValue(BEDROCK).isEmpty()) {
			eq(0L, book.unitPrice(b.getUUID(), none), "price 0 without market value");
			eq(Status.NOT_ACCEPTING, book.deliver(b, none.id, 4).status(), "accepts nothing");
			eq(4, count(b, BEDROCK), "items stay");
		}
		ctx.detail("cap " + Money.format(cap) + " → Crash " + Money.format(cap2));
	}

	private static void deliverPriceChanged(TestContext ctx) {
		FakePlayer a = player(ctx, "OrdA", 100_000);
		FakePlayer b = player(ctx, "OrdB", 0);
		FakeFunding fund = new FakeFunding(100_000_000, 10_000_000, 10_000_000);
		OrdersConfig cfg = new OrdersConfig();
		cfg.deliveryFeePercent = 3.0;
		OrderBook book = book(new OrderData(), new long[] {T0}, fund, cfg);
		ctx.cleanup(() -> PriceModifiers.removeSource(MOD_SOURCE));
		UUID who = b.getUUID();
		long total = 100_000L + 100_000_000L;

		long cap = limit(book, who, WHEAT);
		Order npc = book.createNpcOrder(WHEAT, 20, cap * 10, OrderBook.HOUR_MS, OrderBook.NPC_LABEL, false);
		notNull(npc, "NPC order");
		long escrow0 = npc.escrow;
		give(b, new ItemStack(WHEAT, 20));
		Terms shown = book.terms(who, npc);
		eq(cap, shown.unit(), "displayed unit price = price cap");
		isTrue(shown.feePercent() == 0, "NPC order without fee");
		// hard crash (-80%), so the price cap falls below the conversion cap (hay bale / 9)
		PriceModifiers.set(MOD_SOURCE, WHEAT, 0.2, 0, 60_000, 0, "Selftest");
		long crashed = book.unitPrice(who, npc);
		isTrue(crashed > 0 && crashed < shown.unit(), "crash lowers the price (" + shown.unit() + " → " + crashed + ")");
		DeliverResult r1 = book.deliver(b, npc.id, 10, shown);
		eq(Status.PRICE_CHANGED, r1.status(), "click at old price: rejected");
		eq(20, count(b, WHEAT), "items stay");
		eq(0L, bal(b), "nothing paid");
		eq(0, npc.delivered, "nothing delivered");
		eq(escrow0, npc.escrow, "escrow unchanged");
		eq(0L, ctx.drops(b).size(), "nothing dropped");
		Terms again = book.terms(who, npc);
		eq(crashed, again.unit(), "new display = crash price");
		DeliverResult r2 = book.deliver(b, npc.id, 10, again);
		eq(Status.OK, r2.status(), "delivered with new display");
		eq(10 * crashed, r2.net(), "10 × crash price");
		eq(10, count(b, WHEAT), "10 wheat taken");
		PriceModifiers.removeSource(MOD_SOURCE);
		long normal = book.unitPrice(who, npc);
		isTrue(normal > crashed, "higher again after the crash");
		DeliverResult r3 = book.deliver(b, npc.id, 5, again);
		eq(Status.OK, r3.status(), "higher price than displayed: delivered");
		eq(5 * normal, r3.net(), "5 × current price");
		eq(total, bal(a) + bal(b) + book.totalEscrow() + fund.till, "money preserved (NPC)");

		Order po = create(book, a, IRON, 10, 1_000);
		give(b, new ItemStack(IRON, 10));
		Terms poShown = book.terms(who, po);
		eq(1_000L, poShown.unit(), "player order: order price");
		isTrue(poShown.feePercent() == 3.0, "fee 3% displayed");
		long balB = bal(b);
		long fees0 = fund.fees;
		cfg.deliveryFeePercent = 10.0;
		eq(Status.PRICE_CHANGED, book.deliver(b, po.id, 4, poShown).status(), "fee raised: rejected");
		eq(10, count(b, IRON), "ingots stay");
		eq(balB, bal(b), "nothing paid");
		eq(0, po.delivered, "nothing delivered");
		eq(10_000L, po.escrow, "escrow unchanged");
		eq(0L, book.claimTotal(a.getUUID()), "claim chest empty");
		eq(fees0, fund.fees, "no fee booked");
		cfg.deliveryFeePercent = 1.0;
		DeliverResult r4 = book.deliver(b, po.id, 4, poShown);
		eq(Status.OK, r4.status(), "fee lowered: delivered");
		eq(Money.percentOf(4_000, 1.0), r4.fee(), "1% fee");
		eq(balB + 4_000 - r4.fee(), bal(b), "paid net");
		eq(4L, book.claimTotal(a.getUUID()), "4 in the claim chest");
		cfg.deliveryFeePercent = 10.0;
		DeliverResult r5 = book.deliver(b, po.id, 2, Terms.ANY);
		eq(Status.OK, r5.status(), "Terms.ANY delivers at the current price");
		eq(Money.percentOf(6_000, 10.0) - Money.percentOf(4_000, 10.0), r5.fee(), "cumulative fee 10%");
		// rounding: another deliverer gets in between, the cumulative fee for my item rises by 1 cent, so it's
		// rejected
		FakePlayer c = player(ctx, "OrdC", 0);
		cfg.deliveryFeePercent = 5.0;
		Order small = create(book, a, COBBLE, 10, 33);
		give(b, new ItemStack(COBBLE, 1));
		give(c, new ItemStack(COBBLE, 1));
		Terms smallShown = book.terms(who, small);
		long feeShown = book.feeFor(small, 1, 33);
		eq(Status.OK, book.deliver(c, small.id, 1).status(), "another deliverer in between");
		long feeNow = book.feeFor(small, 1, 33);
		isTrue(feeNow > feeShown, "fee for 1 item now higher (" + feeShown + " → " + feeNow + ")");
		long balB2 = bal(b);
		eq(Status.PRICE_CHANGED, book.deliver(b, small.id, 1, smallShown).status(), "less than displayed: rejected");
		eq(1, count(b, COBBLE), "cobblestone stays");
		eq(balB2, bal(b), "nothing paid");
		DeliverResult r6 = book.deliver(b, small.id, 1, book.terms(who, small));
		eq(Status.OK, r6.status(), "displayed anew: delivered");
		eq(feeNow, r6.fee(), "fee as newly displayed");
		eq(balB2 + 33 - feeNow, bal(b), "net as newly displayed");
		eq(total, bal(a) + bal(b) + bal(c) + book.totalEscrow() + fund.till, "money preserved (total)");
		ctx.detail("Crash " + Money.format(shown.unit()) + " → " + Money.format(crashed) + " rejected; fee 3% to 10% rejected; "
			+ "rounding +1 cent rejected");
	}

	private static void npcBulk(TestContext ctx) {
		long[] clock = {T0};
		FakeFunding fund = new FakeFunding(100_000_000, 100_000, 100_000);
		OrdersConfig cfg = new OrdersConfig();
		cfg.maxBulkOrders = 1;
		OrderBook book = book(new OrderData(), clock, fund, cfg);
		long price = book.npcOfferCap(WHEAT);
		isTrue(price > 0 && price <= npcCap(OrderBook.NEUTRAL, WHEAT), "max price <= PriceGuard");
		Order o = book.startBulk(WHEAT, 100_000, 30 * OrderBook.MINUTE_MS, null);
		notNull(o, "bulk order created");
		isTrue(o.bulk && o.npc, "marked as bulk order");
		eq(OrderBook.BULK_LABEL, o.label, "default name");
		eq(price, o.price, "price = price cap (PriceGuard + conversion)");
		eq(40_000L / price, (long) o.amount, "amount limited by 40% of the hourly budget");
		isTrue(o.escrow <= 40_000, "escrow <= 40% of hourly budget");
		eq(T0 + 30 * OrderBook.MINUTE_MS, o.expiresAt, "duration");
		isTrue(book.isOpen(o.id), "isOpen: newly created");
		isFalse(book.isOpen(o.id + 999), "isOpen: unknown id");
		clock[0] = o.expiresAt;
		isFalse(book.isOpen(o.id), "isOpen: expired (even before the refund)");
		clock[0] = T0;
		isTrue(book.startBulk(WHEAT, 100, OrderBook.HOUR_MS, "Test") == null, "maxBulkOrders");
		cfg.maxBulkOrders = 3;
		fund.budget = price * 10;
		isTrue(book.startBulk(WHEAT, 100, OrderBook.HOUR_MS, "Test") == null, "budget below minimum amount gives false");
		Order small = book.startBulk(WHEAT, 5, OrderBook.HOUR_MS, "Mini");
		notNull(small, "small request fits the budget");
		eq(5, small.amount, "requested amount");
		eq("Mini", small.label, "custom name");
		isTrue(book.isOpen(small.id), "isOpen: small bulk order open");
		isTrue(book.cancel(null, small.id, true).status().ok(), "admin cancel of the bulk order");
		isFalse(book.isOpen(small.id), "isOpen: cancelled");
		isTrue(book.startBulk(BEDROCK, 10, OrderBook.HOUR_MS, null) == null || Services.marketValue(BEDROCK).isPresent(), "no bulk order without market value");
		isTrue(book.startBulk(WHEAT, 0, OrderBook.HOUR_MS, null) == null, "amount 0");
		isTrue(book.startBulk(WHEAT, 10, 0, null) == null, "duration 0");
		isTrue(Services.find(Services.NpcOrders.class).isPresent(), "Services.NpcOrders registered");
		eq(fund.till + book.totalEscrow(), 100_000_000L, "money preserved");
		ctx.detail(o.amount + "x wheat at " + Money.format(price));
	}

	private static void treasuryLive(TestContext ctx) {
		ctx.preserveTreasury();
		FakePlayer a = player(ctx, "OrdA", 100_000);
		FakePlayer b = player(ctx, "OrdB", 0);
		EconomyService.set(Treasury.ID, 100_000_000);
		OrderBook book = book(new long[] {System.currentTimeMillis()}, OrderFunding.treasury());
		long t0 = Treasury.balance();
		Order po = create(book, a, IRON, 10, 1_000);
		give(b, new ItemStack(IRON, 10));
		DeliverResult r = book.deliver(b, po.id, 10);
		eq(Status.OK, r.status(), "player order filled");
		eq(t0 + r.fee(), Treasury.balance(), "fee in the treasury");
		isTrue(r.fee() > 0, "fee > 0");
		long cap = limit(book, b.getUUID(), WHEAT);
		long spent = Treasury.spentLastHour();
		long t1 = Treasury.balance();
		check(8 * cap <= Treasury.allowance(), "budget sufficient for the test");
		Order npc = book.createNpcOrder(WHEAT, 8, cap, OrderBook.HOUR_MS, null, false);
		notNull(npc, "NPC order with the real treasury");
		eq(t1 - 8 * cap, Treasury.balance(), "escrow from the treasury");
		eq(spent + 8 * cap, Treasury.spentLastHour(), "counts toward the hourly cap");
		long bBefore = bal(b);
		give(b, new ItemStack(WHEAT, 3));
		eq(3, book.deliver(b, npc.id, 3).count(), "3 delivered");
		eq(bBefore + 3 * cap, bal(b), "paid from escrow");
		eq(t1 - 8 * cap, Treasury.balance(), "delivery doesn't change the treasury");
		CancelResult c = book.cancel(null, npc.id, true);
		eq(Status.OK, c.status(), "admin cancel");
		eq(t1 - 3 * cap, Treasury.balance(), "rest back to the treasury");
		isTrue(Treasury.stats24h().containsKey(Treasury.R_NPC_ORDER), "stats npc_order");
		isTrue(Treasury.stats24h().containsKey(Treasury.R_ORDER_FEE), "stats order_fee");
		long t2 = Treasury.balance();
		isTrue(book.createNpcOrder(WHEAT, 1, Treasury.maxSpendPerHour() + 1, OrderBook.HOUR_MS, null, false) == null, "above maxSpendPerHour");
		eq(t2, Treasury.balance(), "treasury unchanged");
		ctx.detail("fee " + Money.format(r.fee()) + ", NPC escrow 8 × " + Money.format(cap));
	}

	private static void conservationRandom(TestContext ctx) {
		FakePlayer[] ps = {player(ctx, "OrdA", 500_000), player(ctx, "OrdB", 500_000), player(ctx, "OrdC", 500_000)};
		for (FakePlayer p : ps) {
			give(p, new ItemStack(WHEAT, 64 * 4));
			give(p, new ItemStack(IRON, 64 * 2));
		}
		long[] clock = {T0};
		FakeFunding fund = new FakeFunding(0, 0, 0);
		OrderBook book = book(clock, fund);
		Random rnd = new Random(7);
		Item[] items = {WHEAT, IRON};
		long money = 1_500_000;
		int itemsTotal = 3 * (64 * 6);
		int ops = 0;
		for (int step = 0; step < 300; step++) {
			FakePlayer p = ps[rnd.nextInt(3)];
			int op = rnd.nextInt(10);
			if (op < 3) {
				book.createPlayerOrder(p.getUUID(), items[rnd.nextInt(2)], 1 + rnd.nextInt(80), 1 + rnd.nextInt(500), false);
			} else if (op < 7) {
				List<Order> all = book.all();
				if (!all.isEmpty()) book.deliver(p, all.get(rnd.nextInt(all.size())).id, 1 + rnd.nextInt(100));
			} else if (op < 8) {
				List<Order> mine = book.ownedBy(p.getUUID());
				if (!mine.isEmpty()) book.cancel(p.getUUID(), mine.get(rnd.nextInt(mine.size())).id, false);
			} else if (op < 9) {
				clock[0] += rnd.nextInt(3) * OrderBook.DAY_MS;
				book.expireDue();
			} else {
				book.claimAll(p);
			}
			ops++;
			long sum = fund.till + book.totalEscrow();
			for (FakePlayer x : ps) sum += bal(x);
			eq(money, sum, "money preserved after step " + step);
			int inWorld = 0;
			for (FakePlayer x : ps) inWorld += count(x, WHEAT) + count(x, IRON);
			for (FakePlayer x : ps) inWorld += (int) book.claimTotal(x.getUUID());
			eq(itemsTotal, inWorld, "items preserved after step " + step);
			for (Order o : book.all()) eq(o.open() * o.price, o.escrow, "escrow = open × price (#" + o.id + ")");
		}
		for (FakePlayer x : ps) isTrue(ctx.drops(x).isEmpty(), "nothing dropped");
		ctx.detail(ops + " steps, money + items preserved");
	}

	private static void persistence(TestContext ctx) {
		FakePlayer a = player(ctx, "OrdA", 100_000);
		FakePlayer b = player(ctx, "OrdB", 0);
		OrderData data = new OrderData();
		OrderBook book = book(data, new long[] {T0}, new FakeFunding(10_000_000, 1_000_000, 1_000_000), new OrdersConfig());
		var sharpness = ctx.server().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS);
		Order o = book.createPlayerOrder(a.getUUID(), SWORD, 5, 2_000, true).order();
		ItemStack ench = new ItemStack(SWORD);
		ench.enchant(sharpness, 4);
		give(b, ench.copy());
		give(b, new ItemStack(SWORD));
		Settings.set(a.getUUID(), Settings.NOTIFICATIONS, true);
		eq(2, book.deliver(b, o.id, 2).count(), "2 delivered");
		Order npc = book.createNpcOrder(WHEAT, 16, 50, OrderBook.HOUR_MS, OrderBook.NPC_LABEL, false);
		notNull(npc, "NPC order");
		String json = DataStore.gson().toJson(data);
		OrderData back = DataStore.gson().fromJson(json, OrderData.class);
		back.fixNulls();
		eq(data.nextId, back.nextId, "nextId");
		eq(2, back.orders.size(), "2 orders");
		Order o2 = back.orders.get(o.id);
		notNull(o2, "player order");
		eq(a.getUUID(), o2.owner, "owner");
		eq(o.escrow, o2.escrow, "Escrow");
		eq(2, o2.delivered, "delivered");
		isTrue(o2.allowEnchanted, "allowEnchanted");
		Order n2 = back.orders.get(npc.id);
		isTrue(n2.npc && n2.owner == null, "NPC order");
		eq(npc.escrow, n2.escrow, "NPC escrow");
		List<Claim> claims = back.claimsOf(a.getUUID());
		eq(2, claims.size(), "chest entries");
		Claim withNbt = claims.stream().filter(c -> c.nbt != null).findFirst().orElseThrow();
		ItemStack decoded = OrderBook.prototype(withNbt).orElseThrow();
		isTrue(ItemStack.isSameItemSameComponents(decoded, ench), "enchantment after round trip");
		eq(1, back.away.get(a.getUUID()).size(), "away note");
		OrderData broken = DataStore.gson().fromJson("{\"nextId\":1,\"orders\":{\"7\":{\"item\":\"minecraft:wheat\",\"amount\":3,\"escrow\":-5,\"npc\":true},\"8\":null},"
			+ "\"claims\":null,\"away\":null}", OrderData.class);
		broken.fixNulls();
		eq(8L, broken.nextId, "nextId past highest id");
		eq(0L, broken.orders.get(7L).escrow, "negative escrow gives 0");
		eq(7L, broken.orders.get(7L).id, "id from the key");
		isFalse(broken.orders.containsKey(8L), "null order removed");
		notNull(broken.claims, "claims repaired");
		notNull(broken.away, "away repaired");
		isTrue(ItemCodec.tryDecode("broken").isEmpty(), "broken data doesn't throw");
		ctx.detail("JSON round trip + repair ok");
	}

	private static Holder<Enchantment> ench(TestContext ctx, ResourceKey<Enchantment> key) {
		return ctx.server().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key);
	}

	private static ItemStack book(Holder<Enchantment> e, int level) {
		ItemStack b = new ItemStack(BOOK);
		b.enchant(e, level);
		return b;
	}

	private static void templateEnchantedBook(TestContext ctx) {
		FakePlayer a = player(ctx, "OrdA", 100_000);
		FakePlayer b = player(ctx, "OrdB", 0);
		FakeFunding fund = new FakeFunding(0, 0, 0);
		OrderData data = new OrderData();
		OrderBook book = book(data, new long[] {T0}, fund, new OrdersConfig());
		UUID id = a.getUUID();
		Holder<Enchantment> mending = ench(ctx, Enchantments.MENDING);
		Holder<Enchantment> bane = ench(ctx, Enchantments.BANE_OF_ARTHROPODS);
		Holder<Enchantment> unbreaking = ench(ctx, Enchantments.UNBREAKING);
		ItemStack mendingBook = book(mending, 1);
		isFalse(mendingBook.isEnchanted(), "isEnchanted() doesn't see stored enchantments");
		// without a template: not orderable, it could never be filled or with "any enchantment" would take any book
		eq(Status.NEEDS_TEMPLATE, book.createPlayerOrder(id, BOOK, 1, 3_000, false).status(), "book without template");
		eq(Status.NEEDS_TEMPLATE, book.createPlayerOrder(id, BOOK, 1, 3_000, true).status(), "book without template, any enchantment");
		eq(Status.NEEDS_TEMPLATE, book.createPlayerOrder(id, BOOK, new ItemStack(BOOK), 1, 3_000, false).status(), "empty book as template");
		eq(Status.NOT_ORDERABLE, book.createPlayerOrder(id, net.minecraft.world.item.Items.WRITTEN_BOOK, 1, 100, false).status(), "written book");
		eq(Status.NOT_ORDERABLE, book.createPlayerOrder(id, net.minecraft.world.item.Items.FILLED_MAP, 1, 100, false).status(), "Map");
		eq(Status.INVALID_ITEM, book.createPlayerOrder(id, SWORD, mendingBook, 1, 100, false).status(), "template of another item");
		eq(100_000L, bal(a), "nothing charged");
		ItemStack handBook = mendingBook.copy();
		handBook.set(DataComponents.CUSTOM_NAME, Component.literal("My Book"));
		handBook.set(DataComponents.REPAIR_COST, 3);
		CreateResult cr = book.createPlayerOrder(id, BOOK, handBook, 2, 3_000, true);
		eq(Status.OK, cr.status(), "order with template");
		Order o = cr.order();
		notNull(o.spec, "template saved");
		isFalse(o.allowEnchanted, "no 'any enchantment' with a template");
		ItemStack spec = o.specStack().orElseThrow();
		isTrue(ItemStack.isSameItemSameComponents(spec, mendingBook), "template = plain Mending book");
		ItemStack anvil = mendingBook.copy();
		anvil.set(DataComponents.REPAIR_COST, 1);
		ItemStack named = mendingBook.copy();
		named.set(DataComponents.CUSTOM_NAME, Component.literal("X"));
		ItemStack both = book(mending, 1);
		both.enchant(unbreaking, 3);
		isTrue(OrderBook.accepts(o, BOOK, mendingBook), "Mending matches");
		isTrue(OrderBook.accepts(o, BOOK, anvil), "Mending with repair cost matches");
		isFalse(OrderBook.accepts(o, BOOK, book(bane, 1)), "Bane of Arthropods rejected");
		isFalse(OrderBook.accepts(o, BOOK, both), "Mending + Unbreaking rejected (exact)");
		isFalse(OrderBook.accepts(o, BOOK, named), "named one rejected");
		isFalse(OrderBook.accepts(o, BOOK, new ItemStack(BOOK)), "empty book rejected");
		give(b, book(bane, 1));
		eq(Status.NO_ITEMS, book.deliver(b, o.id, 1).status(), "only wrong book: nothing delivered");
		give(b, mendingBook.copy());
		DeliverResult r = book.deliver(b, o.id, 2);
		eq(Status.OK, r.status(), "Mending delivered");
		eq(1, r.count(), "exactly 1");
		eq(3_000L - r.fee(), bal(b), "full price");
		eq(1, Items.count(b, s -> s.is(BOOK) && ItemStack.isSameItemSameComponents(s, book(bane, 1))), "wrong book stays");
		eq(0, Items.count(b, s -> ItemStack.isSameItemSameComponents(s, mendingBook)), "Mending gone");
		ClaimResult cl = book.claimAll(a);
		eq(1, cl.given(), "claimed");
		eq(1, Items.count(a, s -> ItemStack.isSameItemSameComponents(s, mendingBook)), "Mending with the buyer");
		isTrue(ctx.drops(a).isEmpty(), "nothing dropped");
		OrderData back = DataStore.gson().fromJson(DataStore.gson().toJson(data), OrderData.class);
		back.fixNulls();
		Order o2 = back.orders.get(o.id);
		notNull(o2, "order after round trip");
		isTrue(OrderBook.accepts(o2, BOOK, mendingBook), "Mending matches after round trip");
		isFalse(OrderBook.accepts(o2, BOOK, book(bane, 1)), "Bane rejected after round trip");
		Order broken = new Order();
		broken.item = Items.id(BOOK);
		broken.spec = "broken";
		isFalse(OrderBook.accepts(broken, BOOK, mendingBook), "broken template: nothing");
		Order legacy = new Order();
		legacy.item = Items.id(BOOK);
		legacy.allowEnchanted = true;
		isFalse(OrderBook.accepts(legacy, BOOK, book(bane, 1)), "legacy order without template: nothing");
		CancelResult c = book.cancel(id, o.id, false);
		eq(3_000L, c.refunded(), "rest refunded");
		eq(100_000L, bal(a) + bal(b) + book.totalEscrow() + fund.fees, "money preserved");
		ctx.detail("Mending exact, Bane/empty/named rejected, round trip ok");
	}

	private static void templatePotionTool(TestContext ctx) {
		FakePlayer a = player(ctx, "OrdA", 100_000);
		OrderBook book = book(new long[] {T0}, new FakeFunding(0, 0, 0));
		UUID id = a.getUUID();
		ItemStack swift = PotionContents.createItemStack(POTION, Potions.SWIFTNESS);
		ItemStack water = PotionContents.createItemStack(POTION, Potions.WATER);
		ItemStack longSwift = PotionContents.createItemStack(POTION, Potions.LONG_SWIFTNESS);
		isFalse(Items.isPristine(water), "even water bottles are never unmodified");
		eq(Status.NEEDS_TEMPLATE, book.createPlayerOrder(id, POTION, 1, 100, false).status(), "potion without template");
		eq(Status.NEEDS_TEMPLATE, book.createPlayerOrder(id, net.minecraft.world.item.Items.TIPPED_ARROW, 1, 100, false).status(), "arrow without template");
		Order po = book.createPlayerOrder(id, POTION, swift, 3, 100, false).order();
		notNull(po, "potion order with template");
		isTrue(OrderBook.accepts(po, POTION, swift.copy()), "Swiftness matches");
		isFalse(OrderBook.accepts(po, POTION, water), "water rejected");
		isFalse(OrderBook.accepts(po, POTION, longSwift), "long swiftness rejected");
		Holder<Enchantment> sharp = ench(ctx, Enchantments.SHARPNESS);
		Holder<Enchantment> unbreaking = ench(ctx, Enchantments.UNBREAKING);
		ItemStack s5 = new ItemStack(SWORD);
		s5.enchant(sharp, 5);
		ItemStack hand = s5.copy();
		hand.setDamageValue(40);
		hand.set(DataComponents.CUSTOM_NAME, Component.literal("Blade"));
		ItemStack tpl = OrderBook.templateOf(hand);
		notNull(tpl, "template from enchanted sword");
		isTrue(ItemStack.isSameItemSameComponents(tpl, s5), "template without name/damage");
		isTrue(OrderBook.templateOf(new ItemStack(SWORD)) == null, "unmodified item: no template");
		ItemStack namedDamaged = new ItemStack(SWORD);
		namedDamaged.setDamageValue(3);
		namedDamaged.set(DataComponents.CUSTOM_NAME, Component.literal("Y"));
		isTrue(OrderBook.templateOf(namedDamaged) == null, "only name/damage: no template");
		Order so = book.createPlayerOrder(id, SWORD, hand, 2, 1_000, false).order();
		notNull(so, "sword order with template");
		ItemStack s1 = new ItemStack(SWORD);
		s1.enchant(sharp, 1);
		ItemStack s5u = s5.copy();
		s5u.enchant(unbreaking, 3);
		ItemStack s5d = s5.copy();
		s5d.setDamageValue(1);
		isTrue(OrderBook.accepts(so, SWORD, s5.copy()), "Sharpness V matches");
		isFalse(OrderBook.accepts(so, SWORD, s1), "Sharpness I rejected");
		isFalse(OrderBook.accepts(so, SWORD, s5u), "additional enchantment rejected");
		isFalse(OrderBook.accepts(so, SWORD, s5d), "damaged rejected");
		isFalse(OrderBook.accepts(so, SWORD, new ItemStack(SWORD)), "unenchanted rejected");
		eq(Status.INVALID_ITEM, OrderBook.checkTemplate(net.minecraft.world.item.Items.DIAMOND, OrderBook.templateOf(swift)), "template doesn't match the item");
		isTrue(book.startBulk(POTION, 10, OrderBook.HOUR_MS, null) == null, "no bulk order for potions");
		ctx.detail("potion/sword exact, type orders for potions rejected");
	}

	private static final Item COAL = net.minecraft.world.item.Items.COAL;
	private static final Item BONE = net.minecraft.world.item.Items.BONE;

	private static OrderBook boardBook(OrderData data, long[] clock, OrdersConfig cfg) {
		return book(data, clock, new FakeFunding(0, 0, 0), cfg);
	}

	private static Order boardOrder(OrderBook book, Item item, double demand, int amount) {
		Order o = book.board().spawn(item, false, false);
		notNull(o, "board order " + Items.id(item));
		o.demand = demand;
		o.amount = amount;
		o.expiresAt = book.now() + 30 * OrderBook.DAY_MS;
		return o;
	}

	private static long expectedPayout(long ref, long cap, double demand, double p0, int n, OrdersConfig cfg) {
		long sum = 0;
		double u = cfg.boardSupplyDropPerStack / 64.0;
		for (int k = 0; k < n; k++) {
			double p = Math.min(cfg.boardMaxPressure, p0 + k * u);
			double f = Math.max(cfg.boardDemandFloor, Math.min(demand, demand - p));
			sum += Math.min((long) Math.floor(ref * f + 1e-9), cap);
		}
		return sum;
	}

	private static void boardFill(TestContext ctx) {
		FakePlayer viewer = player(ctx, "OrdA", 0);
		long[] clock = {T0};
		OrdersConfig cfg = new OrdersConfig();
		OrderData data = new OrderData();
		OrderBook book = boardBook(data, clock, cfg);
		long t0 = System.nanoTime();
		book.tick();
		long fillMs = (System.nanoTime() - t0) / 1_000_000;
		NpcBoard board = book.board();
		List<Item> eligible = board.eligible(true);
		java.util.Set<Item> common = board.commonItems();
		long commonEligible = eligible.stream().filter(common::contains).count();
		int expected = (int) Math.min(cfg.boardSize, eligible.size() + commonEligible);
		eq(expected, board.size(), "board filled (target " + cfg.boardSize + ", tradable " + eligible.size() + " + " + commonEligible + " common)");
		isTrue(board.size() >= 360, "at least 8 full pages of NPC orders (" + board.size() + ")");
		isTrue(eligible.size() >= 300, "most /sell items tradable (" + eligible.size() + ")");
		java.util.Map<String, Integer> counts = board.counts();
		for (Order o : data.orders.values()) {
			isTrue(o.board && o.npc && !o.bulk && o.owner == null, "board order #" + o.id);
			eq(0L, o.escrow, "no escrow #" + o.id);
			isTrue(o.demand >= cfg.boardDemandMin - 1e-9 && o.demand <= cfg.boardDemandMax + 1e-9, "demand in range #" + o.id + " " + o.demand);
			isTrue(o.amount >= 1 && o.amount <= cfg.boardMaxAmount, "amount in range #" + o.id + " " + o.amount);
			isTrue(o.expiresAt > T0 && o.expiresAt <= T0 + (cfg.boardMaxHours + 1L) * OrderBook.HOUR_MS, "duration #" + o.id);
			isTrue(o.label != null && !o.label.isBlank(), "NPC name #" + o.id);
			Item item = o.itemType().orElseThrow();
			int n = counts.get(o.item);
			isTrue(n == 1 || (n == 2 && common.contains(item)), "at most 2 buyers, only common doubled: " + o.item);
			isFalse(OrderBook.needsTemplate(item) || OrderBook.isUnique(item), "only items deliverable unmodified");
		}
		long big = data.orders.values().stream().filter(o -> o.amount >= 64).count();
		isTrue(big * 2 > data.orders.size(), "mostly sensible amounts (≥ 64): " + big + "/" + data.orders.size());
		int before = board.size();
		book.tick();
		eq(before, board.size(), "full stays full");
		OrdersGui.View view = new OrdersGui.View();
		List<OrdersGui.Row> rows = OrdersGui.rows(book, viewer, view);
		eq(before + 2, rows.size(), "player header + NPC header + orders");
		int pages = (rows.size() + 44) / 45;
		isTrue(pages >= 9, "at least 9 pages (" + pages + ")");
		long t1 = System.nanoTime();
		for (OrdersGui.Row r : rows) {
			if (r instanceof OrdersGui.Entry e) {
				int quick = OrdersGui.quickCount(book, viewer, e.order(), e.have());
				OrdersGui.orderIcon(book, viewer, e.order(), true, e.have(), quick, null);
			}
		}
		long renderMs = (System.nanoTime() - t1) / 1_000_000;
		isTrue(renderMs < 3_000, "all " + pages + " pages in " + renderMs + " ms rendered");
		long firstExpiry = data.orders.values().stream().mapToLong(o -> o.expiresAt).min().orElseThrow();
		long lastExpiry = data.orders.values().stream().mapToLong(o -> o.expiresAt).max().orElseThrow();
		isTrue(lastExpiry - firstExpiry > 6 * OrderBook.HOUR_MS, "durations staggered");
		clock[0] = firstExpiry;
		int expired = book.expireDue();
		isTrue(expired >= 1, "expired: " + expired);
		book.tick();
		isTrue(board.size() >= before - expired + Math.min(expired, cfg.boardRefillPerTick) || board.size() == before, "refilled");
		ctx.detail(before + " NPC orders, " + pages + " pages, fill " + fillMs + " ms, all pages " + renderMs + " ms");
	}

	/**
	 * Price = min(floor(reference × (demand - pressure)), cap), item by item; each delivery raises pressure, shared
	 * by all buyers of the item, and it halves per half-life. Floor 0.95.
	 */
	private static void boardPriceDynamics(TestContext ctx) {
		FakePlayer b = player(ctx, "OrdB", 0);
		UUID who = b.getUUID();
		ctx.cleanup(() -> PriceModifiers.removeSource(MOD_SOURCE));
		PriceModifiers.removeSource(MOD_SOURCE);
		long[] clock = {T0};
		OrdersConfig cfg = new OrdersConfig();
		cfg.boardDailyBonusPerPlayer = 1_000_000_000L;
		OrderBook book = boardBook(new OrderData(), clock, cfg);
		NpcBoard board = book.board();
		Order o = boardOrder(book, COAL, 1.30, 100_000);
		Order o2 = boardOrder(book, COAL, 1.10, 2304);
		long ref = NpcBoard.referencePrice(COAL);
		long cap = board.cap(who, COAL);
		isTrue(ref > 0 && cap > 0, "coal: reference " + ref + ", cap " + cap);
		eq(BasePrices.sellPrice(COAL).orElseThrow(), ref, "reference = /sell base price without modifier");
		eq(Math.min((long) Math.floor(ref * 1.30 + 1e-9), cap), book.unitPrice(who, o), "first item = reference × 1.30");
		long expected64 = expectedPayout(ref, cap, 1.30, 0, 64, cfg);
		give(b, new ItemStack(COAL, 64));
		DeliverResult r = book.deliver(b, o.id, 64);
		eq(Status.OK, r.status(), "64 coal delivered");
		eq(expected64, r.net(), "proceeds item by item");
		eq(expected64, bal(b), "deliverer paid (new money)");
		eq(0, count(b, COAL), "coal gone (NPC)");
		eq(0L, book.claimTotal(who), "no claim chest");
		double u = cfg.boardSupplyDropPerStack;
		isTrue(Math.abs(board.pressure(o.item) - u) < 1e-9, "pressure +" + u + " per stack");
		eq(Math.min((long) Math.floor(ref * (1.30 - u) + 1e-9), cap), book.unitPrice(who, o), "cheaper afterwards");
		eq(Math.min((long) Math.floor(ref * (1.10 - u) + 1e-9), cap), book.unitPrice(who, o2), "other buyer of the same item too");
		clock[0] += (long) (cfg.boardRecoveryHalfLifeHours * OrderBook.HOUR_MS);
		isTrue(Math.abs(board.pressure(o.item) - u / 2) < 1e-9, "pressure halved after " + cfg.boardRecoveryHalfLifeHours + " h");
		for (int i = 0; i < 3; i++) {
			give(b, new ItemStack(COAL, 64 * 30));
			long balBefore = bal(b);
			double p0 = board.pressure(o.item);
			long expectedBig = expectedPayout(ref, cap, 1.30, p0, 64 * 30, cfg);
			DeliverResult big = book.deliver(b, o.id, 64 * 30);
			eq(64 * 30, big.count(), "1920 delivered (" + i + ")");
			eq(expectedBig, big.net(), "proceeds item by item down to the floor (" + i + ")");
			eq(balBefore + expectedBig, bal(b), "paid (" + i + ")");
		}
		eq(Math.min((long) Math.floor(ref * cfg.boardDemandFloor + 1e-9), cap), book.unitPrice(who, o), "floor 0.95 × reference");
		eq(book.unitPrice(who, o), book.unitPrice(who, o2), "both buyers at the floor");
		isTrue(board.pressure(o.item) <= cfg.boardMaxPressure + 1e-9, "pressure capped");
		NpcBoard.Trend t = board.trend(o);
		isTrue(t == NpcBoard.Trend.FLAT, "no smoothed value: stable");
		board.updateTrends();
		eq(NpcBoard.Trend.FLAT, board.trend(o), "just smoothed: stable");
		clock[0] += 3 * (long) (cfg.boardRecoveryHalfLifeHours * OrderBook.HOUR_MS);
		isTrue(board.pressure(o.item) < (cfg.boardDemandMin + cfg.boardDemandMax) / 2 - cfg.boardDemandFloor, "pressure recovered");
		eq(NpcBoard.Trend.UP, board.trend(o), "recovery: trend rising");
		ctx.detail("64 coal: " + Money.format(expected64) + ", floor " + Money.format(book.unitPrice(who, o)));
	}

	private static void boardNewsMultiplier(TestContext ctx) {
		FakePlayer b = player(ctx, "OrdB", 0);
		UUID who = b.getUUID();
		ctx.cleanup(() -> PriceModifiers.removeSource(MOD_SOURCE));
		PriceModifiers.removeSource(MOD_SOURCE);
		long[] clock = {T0};
		OrdersConfig cfg = new OrdersConfig();
		cfg.boardDailyBonusPerPlayer = 1_000_000_000L;
		OrderBook book = boardBook(new OrderData(), clock, cfg);
		Order o = boardOrder(book, COAL, 1.20, 2304);
		long before = book.unitPrice(who, o);
		PriceModifiers.set(MOD_SOURCE, COAL, 1.5, 0, 60_000, 0, "Breaking News");
		long hype = book.unitPrice(who, o);
		long ref = NpcBoard.referencePrice(COAL);
		long cap = book.board().cap(who, COAL);
		eq(Math.min((long) Math.floor(ref * 1.20 + 1e-9), cap), hype, "hype: reference × demand");
		isTrue(ref >= (long) Math.floor(BasePrices.sellPrice(COAL).orElseThrow() * 1.5), "reference +50%");
		if (hype < cap) isTrue(Math.abs(hype - before * 1.5) <= 2, "coal +50% gives order +50% (" + before + " → " + hype + ")");
		isTrue(hype > before, "hype raises the price");
		eq(NpcBoard.Trend.FLAT, book.board().trend(o), "trend without smoothing stable");
		PriceModifiers.removeSource(MOD_SOURCE);
		book.board().updateTrends();
		PriceModifiers.set(MOD_SOURCE, COAL, 1.5, 0, 60_000, 0, "Breaking News");
		eq(NpcBoard.Trend.UP, book.board().trend(o), "news hype: trend rising");
		PriceModifiers.set(MOD_SOURCE, COAL, 0.5, 0, 60_000, 0, "Breaking News");
		long crash = book.unitPrice(who, o);
		isTrue(crash < before && Math.abs(crash - before * 0.5) <= 2, "crash halves (" + before + " → " + crash + ")");
		eq(NpcBoard.Trend.DOWN, book.board().trend(o), "crash: trend falling");
		Order bone = boardOrder(book, BONE, cfg.boardDemandMax, 640);
		PriceModifiers.set(MOD_SOURCE, BONE, 3.0, 0, 60_000, 0, "Breaking News");
		long pivot = PriceGuard.pivotPrice(BONE);
		isTrue(pivot > 0, "bone is a shop item");
		long boneUnit = book.unitPrice(who, bone);
		isTrue(boneUnit <= pivot, "hype on shop item: at most pivot (" + boneUnit + " <= " + pivot + ")");
		long mv = Services.marketValue(BONE).orElseThrow();
		isTrue(boneUnit <= PriceGuard.maxNpcOrderPrice(who, BONE, mv), "never above PriceGuard.maxNpcOrderPrice");
		ctx.detail("coal " + Money.format(before) + " → Hype " + Money.format(hype) + " → Crash " + Money.format(crash) + "; bone ≤ pivot "
			+ Money.format(pivot));
	}

	private static void boardPlayersFirst(TestContext ctx) {
		FakePlayer a = player(ctx, "OrdA", 1_000_000);
		FakePlayer viewer = player(ctx, "OrdB", 0);
		long[] clock = {T0};
		OrdersConfig cfg = new OrdersConfig();
		FakeFunding fund = new FakeFunding(100_000_000, 10_000_000, 10_000_000);
		OrderBook book = book(new OrderData(), clock, fund, cfg);
		book.tick();
		Order cheap = create(book, a, DIAMOND, 1, 100);
		Order dear = create(book, a, IRON, 1, 500);
		Order mid = create(book, a, WHEAT, 1, 300);
		clock[0] += 1;
		Order bulk = book.startBulk(WHEAT, 64, OrderBook.HOUR_MS, null);
		notNull(bulk, "bulk order");
		List<Order> all = book.all();
		eq(List.of(dear.id, mid.id, cheap.id), all.subList(0, 3).stream().map(o -> o.id).toList(), "players first, most expensive first");
		eq(bulk.id, all.get(3).id, "then the bulk order");
		double last = Double.MAX_VALUE;
		for (Order o : all.subList(4, all.size())) {
			isTrue(o.board, "then only board orders");
			double f = book.board().factor(o);
			isTrue(f <= last + 1e-12, "board by demand descending");
			last = f;
		}
		OrdersGui.View view = new OrdersGui.View();
		List<OrdersGui.Row> rows = OrdersGui.rows(book, viewer, view);
		isTrue(rows.get(0) instanceof OrdersGui.Header h && h.players() && h.count() == 3, "page 1 starts with the player header");
		eq(dear.id, ((OrdersGui.Entry) rows.get(1)).order().id, "then the player orders");
		isTrue(rows.get(4) instanceof OrdersGui.Header h2 && !h2.players(), "then the NPC header");
		view.filter = OrdersGui.Filter.PLAYERS;
		eq(4, OrdersGui.rows(book, viewer, view).size(), "filter players: header + 3");
		view.filter = OrdersGui.Filter.NPC;
		List<OrdersGui.Row> npcRows = OrdersGui.rows(book, viewer, view);
		isTrue(npcRows.stream().skip(1).allMatch(r -> r instanceof OrdersGui.Entry e && e.order().npc), "filter NPC: NPC only");
		view.filter = OrdersGui.Filter.DELIVERABLE;
		eq(2, OrdersGui.rows(book, viewer, view).size(), "deliverable without items: only the two headers");
		give(viewer, new ItemStack(COAL, 10));
		List<OrdersGui.Row> deliverable = OrdersGui.rows(book, viewer, view);
		isTrue(deliverable.size() > 2 && deliverable.stream().allMatch(r -> r instanceof OrdersGui.Header
			|| ((OrdersGui.Entry) r).order().item.equals(Items.id(COAL))), "deliverable with coal: only coal orders");
		view.filter = OrdersGui.Filter.ALL;
		view.query = "coal";
		List<OrdersGui.Row> found = OrdersGui.rows(book, viewer, view);
		isTrue(found.size() > 2, "search 'coal' finds something");
		isTrue(found.stream().allMatch(r -> r instanceof OrdersGui.Header || ((OrdersGui.Entry) r).order().item.equals(Items.id(COAL))
			|| ((OrdersGui.Entry) r).order().item.contains("coal")), "search: only coal items");
		view.query = "iron ingot";
		isTrue(OrdersGui.rows(book, viewer, view).stream().anyMatch(r -> r instanceof OrdersGui.Entry e && e.order().id == dear.id),
			"English search finds the player order");
		eq(OrdersGui.Filter.PLAYERS, OrdersGui.Filter.ALL.next(false), "filter forward");
		eq(OrdersGui.Filter.DELIVERABLE, OrdersGui.Filter.ALL.next(true), "filter back");
		ctx.detail(all.size() + " orders, players first, filter/search ok");
	}

	/**
	 * 10,000 random deliveries by two players over simulated days: each item ≤ cap and ≤ reference × max demand,
	 * bonus per player and day ≤ daily cap, payments = account changes = stats, floor never crossed.
	 */
	private static void boardMoneyBounds(TestContext ctx) {
		FakePlayer[] ps = {player(ctx, "OrdA", 0), player(ctx, "OrdB", 0)};
		ctx.cleanup(() -> PriceModifiers.removeSource(MOD_SOURCE));
		PriceModifiers.removeSource(MOD_SOURCE);
		long[] clock = {T0};
		OrdersConfig cfg = new OrdersConfig();
		OrderData data = new OrderData();
		OrderBook book = boardBook(data, clock, cfg);
		book.tick();
		NpcBoard board = book.board();
		Random rnd = new Random(2026);
		java.util.Map<String, Long> bonusPerDay = new java.util.HashMap<>();
		long paid = 0;
		long bonusTotal = 0;
		long units = 0;
		long refValue = 0;
		int ok = 0;
		int bonusCapped = 0;
		Item[] hyped = {COAL, IRON, DIAMOND, WHEAT};
		for (int step = 0; step < 10_000; step++) {
			if (step % 250 == 0) {
				clock[0] += rnd.nextInt(4) * OrderBook.HOUR_MS + rnd.nextInt(60) * OrderBook.MINUTE_MS;
				PriceModifiers.removeSource(MOD_SOURCE);
				Item h = hyped[rnd.nextInt(hyped.length)];
				PriceModifiers.set(MOD_SOURCE, h, rnd.nextBoolean() ? 1.5 + rnd.nextDouble() * 1.5 : 0.5, 0, 3_600_000, 0, "Selftest");
				book.tick();
			}
			List<Order> board1 = data.orders.values().stream().filter(o -> o.board && o.open() > 0 && o.expiresAt > clock[0]).toList();
			if (board1.isEmpty()) {
				book.tick();
				continue;
			}
			Order o = rnd.nextInt(3) == 0 ? board1.stream().filter(x -> x.item.equals(Items.id(COAL))).findFirst().orElse(board1.get(0))
				: board1.get(rnd.nextInt(board1.size()));
			FakePlayer p = ps[rnd.nextInt(ps.length)];
			Item item = o.itemType().orElseThrow();
			int n = 1 + rnd.nextInt(Math.min(192, new ItemStack(item).getMaxStackSize() * 3));
			p.getInventory().clearContent();
			give(p, new ItemStack(item, n));
			UUID who = p.getUUID();
			long ref = NpcBoard.referencePrice(item);
			long sell = NpcBoard.sellPrice(who, item); // deliverer's /sell price with their bonuses, the basis of the bonus
			long cap = board.cap(who, item);
			long perUnitMax = Math.min(cap, (long) Math.floor(ref * cfg.boardDemandMax + 1e-9));
			long perUnitMin = Math.min(cap, (long) Math.floor(ref * cfg.boardDemandFloor + 1e-9));
			long balBefore = bal(p);
			String day = TimeUtil.dateOf(clock[0]).toString();
			DeliverResult r = book.deliver(p, o.id, n);
			if (!r.status().ok()) continue;
			ok++;
			eq(balBefore + r.net(), bal(p), "account = proceeds (step " + step + ")");
			check(r.net() <= safe(r.count(), perUnitMax), "≤ cap × amount (step " + step + ": " + r.net() + " for " + r.count() + "× " + o.item + ")");
			check(r.net() >= safe(r.count(), Math.min(perUnitMin, ref)) - r.count(), "≥ floor (step " + step + ")");
			check(r.net() <= safe(r.count(), sell) + r.bonus(), "above /sell only the bonus (step " + step + ")");
			long dayBonus = bonusPerDay.merge(day + "/" + who, r.bonus(), Long::sum);
			check(dayBonus <= cfg.boardDailyBonusPerPlayer, "daily bonus " + dayBonus + " <= " + cfg.boardDailyBonusPerPlayer);
			if (dayBonus == cfg.boardDailyBonusPerPlayer) bonusCapped++;
			paid += r.net();
			bonusTotal += r.bonus();
			units += r.count();
			refValue += safe(r.count(), sell);
		}
		for (FakePlayer p : ps) p.getInventory().clearContent();
		isTrue(ok >= 9_000, "enough real deliveries (" + ok + ")");
		eq(paid, bal(ps[0]) + bal(ps[1]), "all payments on the accounts");
		eq(paid, data.boardStats.paidTotal, "stats = payments");
		eq(bonusTotal, data.boardStats.bonusTotal, "stats bonus");
		eq(units, data.boardStats.unitsTotal, "stats items");
		long days = bonusPerDay.keySet().stream().map(k -> k.substring(0, 10)).distinct().count();
		isTrue(bonusTotal <= days * ps.length * cfg.boardDailyBonusPerPlayer, "total bonus ≤ days × players × cap");
		isTrue(paid <= refValue + bonusTotal, "payments ≤ /sell value + bonus");
		ctx.detail(ok + " deliveries, " + Msg.number(units) + " items, " + Money.format(paid) + " (of which bonus " + Money.format(bonusTotal) + ", "
			+ days + " days, cap " + bonusCapped + "× reached)");
	}

	private static long safe(long a, long b) {
		try {
			return Math.multiplyExact(a, b);
		} catch (ArithmeticException e) {
			return Long.MAX_VALUE;
		}
	}

	/**
	 * Flipping into the board (buy bargains ≥ /sell value, deliver to NPC orders): even with no daily cap, supply
	 * pressure limits the profit over /sell to about reference × (demand - 1)² / (2 × pressure per item) per item and
	 * recovery. With the daily cap, at most the cap.
	 */
	private static void boardFlipBound(TestContext ctx) {
		FakePlayer b = player(ctx, "OrdB", 0);
		UUID who = b.getUUID();
		ctx.cleanup(() -> PriceModifiers.removeSource(MOD_SOURCE));
		PriceModifiers.removeSource(MOD_SOURCE);
		long[] clock = {T0};
		OrdersConfig cfg = new OrdersConfig();
		cfg.boardDailyBonusPerPlayer = 1_000_000_000_000L;
		OrderBook book = boardBook(new OrderData(), clock, cfg);
		Order o = boardOrder(book, DIAMOND, cfg.boardDemandMax, 100_000);
		Order o2 = boardOrder(book, DIAMOND, cfg.boardDemandMax, 100_000);
		long ref = NpcBoard.referencePrice(DIAMOND);
		double u = cfg.boardSupplyDropPerStack / 64.0;
		double d = cfg.boardDemandMax;
		long bound = (long) Math.ceil(ref * ((d - 1) * (d - 1) / (2 * u) + (d - 1)));
		long bonus = 0;
		long paid = 0;
		int delivered = 0;
		for (int i = 0; i < 80; i++) {
			give(b, new ItemStack(DIAMOND, 64));
			DeliverResult r = book.deliver(b, (i % 2 == 0 ? o : o2).id, 64);
			eq(Status.OK, r.status(), "delivery " + i);
			bonus += r.bonus();
			paid += r.net();
			delivered += r.count();
		}
		isTrue(bonus <= bound, "Bonus " + bonus + " ≤ limit from supply pressure " + bound);
		long unit = book.unitPrice(who, o);
		isTrue(unit <= ref, "then at most /sell value (" + unit + " ≤ " + ref + ")");
		eq(unit, book.unitPrice(who, o2), "second buyer saturated the same");
		OrdersConfig cfg2 = new OrdersConfig();
		FakePlayer c = player(ctx, "OrdC", 0);
		OrderBook book2 = boardBook(new OrderData(), new long[] {T0}, cfg2);
		long totalBonus = 0;
		for (Item item : List.of(DIAMOND, IRON, net.minecraft.world.item.Items.GOLD_INGOT, net.minecraft.world.item.Items.EMERALD, COAL)) {
			Order x = boardOrder(book2, item, cfg2.boardDemandMax, 100_000);
			for (int i = 0; i < 10; i++) {
				give(c, new ItemStack(item, 64));
				totalBonus += book2.deliver(c, x.id, 64).bonus();
			}
		}
		c.getInventory().clearContent();
		isTrue(totalBonus <= cfg2.boardDailyBonusPerPlayer, "with daily cap: bonus " + totalBonus + " ≤ " + cfg2.boardDailyBonusPerPlayer);
		eq(cfg2.boardDailyBonusPerPlayer - totalBonus, book2.board().bonusLeft(c.getUUID()), "bonus left");
		ctx.detail(delivered + " diamonds: bonus " + Money.format(bonus) + " ≤ " + Money.format(bound) + "; daily cap "
			+ Money.format(totalBonus) + "/" + Money.format(cfg2.boardDailyBonusPerPlayer));
	}

	private static void boardDailyBonus(TestContext ctx) {
		FakePlayer b = player(ctx, "OrdB", 0);
		UUID who = b.getUUID();
		ctx.cleanup(() -> PriceModifiers.removeSource(MOD_SOURCE));
		PriceModifiers.removeSource(MOD_SOURCE);
		long[] clock = {T0};
		OrdersConfig cfg = new OrdersConfig();
		cfg.boardDailyBonusPerPlayer = 1_000;
		OrderBook book = boardBook(new OrderData(), clock, cfg);
		Order o = boardOrder(book, DIAMOND, 1.30, 10_000);
		long ref = NpcBoard.sellPrice(who, DIAMOND);
		give(b, new ItemStack(DIAMOND, 64));
		DeliverResult r = book.deliver(b, o.id, 64);
		eq(Status.OK, r.status(), "delivered");
		eq(1_000L, r.bonus(), "bonus exactly up to the cap");
		eq(0L, book.board().bonusLeft(who), "used up");
		isTrue(r.net() <= 64 * ref + 1_000, "proceeds ≤ 64 × /sell + cap");
		isTrue(book.unitPrice(who, o) <= ref, "then at most /sell");
		clock[0] += OrderBook.DAY_MS;
		eq(1_000L, book.board().bonusLeft(who), "new day: bonus full again");
		cfg.boardDailyBonusPerPlayer = 0;
		isTrue(book.unitPrice(who, o) <= ref, "cap 0: never above /sell");
		ctx.detail("bonus $10 reached, then /sell value " + Money.format(ref));
	}

	private static long expectedBonus(long ref, long cap, long sell, double demand, double p0, int n, OrdersConfig cfg) {
		long sum = 0;
		double u = cfg.boardSupplyDropPerStack / 64.0;
		for (int k = 0; k < n; k++) {
			double p = Math.min(cfg.boardMaxPressure, p0 + k * u);
			double f = Math.max(cfg.boardDemandFloor, Math.min(demand, demand - p));
			sum += Math.max(0, Math.min((long) Math.floor(ref * f + 1e-9), cap) - sell);
		}
		return sum;
	}

	private static String loreText(GuiElementBuilder b) {
		ItemLore lore = b.asStack().get(DataComponents.LORE);
		if (lore == null) return "";
		StringBuilder sb = new StringBuilder();
		for (Component c : lore.lines()) sb.append(c.getString()).append('\n');
		return sb.toString();
	}

	/**
	 * Personal /sell bonuses (trader rank +12%): the daily bonus only counts what exceeds THIS player's /sell price.
	 * A delivery below it uses no bonus. Once used up the board pays their /sell price, not the lower reference.
	 * Money limit: proceeds ≤ items × /sell price + bonus.
	 */
	private static void boardSellBoost(TestContext ctx) {
		FakePlayer b = player(ctx, "OrdB", 0);
		FakePlayer c = player(ctx, "OrdC", 0);
		UUID who = b.getUUID();
		UUID other = c.getUUID();
		ctx.cleanup(() -> PriceModifiers.removeSource(MOD_SOURCE));
		PriceModifiers.removeSource(MOD_SOURCE);
		isTrue(Math.abs(Hooks.sellMultiplier(other, COAL) - 1.0) < 1e-12, "precondition: no market day/rank active on coal");
		Hooks.Registration rank = Hooks.addSellMultiplier(MOD_SOURCE + ".rank", "Selftest-Rank", (p, item) -> who.equals(p) ? 1.12 : 1.0);
		ctx.cleanup(rank::remove);
		long[] clock = {T0};
		OrdersConfig cfg = new OrdersConfig();
		cfg.boardDailyBonusPerPlayer = 1_000_000_000L;
		OrderBook book = boardBook(new OrderData(), clock, cfg);
		NpcBoard board = book.board();
		long ref = NpcBoard.referencePrice(COAL);
		long sellB = PriceGuard.effectiveSellPrice(who, COAL).orElseThrow();
		long sellC = PriceGuard.effectiveSellPrice(other, COAL).orElseThrow();
		long cap = board.cap(who, COAL);
		eq(sellB, NpcBoard.sellPrice(who, COAL), "sellPrice = PriceGuard.effectiveSellPrice");
		isTrue(sellB > sellC && sellB > ref, "rank +12%: /sell " + sellB + " > " + sellC + " (without rank), reference " + ref);
		Order low = boardOrder(book, COAL, cfg.boardDemandMin, 100_000);
		NpcBoard.Payout pl = board.payout(who, low, 64);
		isTrue(pl.first() < sellB, "demand " + cfg.boardDemandMin + ": " + pl.first() + " < /sell " + sellB);
		eq(sellB, pl.sell(), "payout knows their /sell price");
		eq(0L, pl.bonus(), "below /sell: no bonus");
		isTrue(pl.belowSell(), "payout: below /sell");
		give(b, new ItemStack(COAL, 64));
		String loreLow = loreText(OrdersGui.orderIcon(book, b, low, true, 64, 64, book.terms(who, low, 64)));
		isTrue(loreLow.contains("Below your /sell price"), "lore warns below /sell: " + loreLow);
		isTrue(loreLow.contains("vs. your /sell price " + Money.format(sellB)), "lore compares with their /sell price");
		isTrue(loreLow.contains("(/sell: " + Money.format(64 * sellB) + ")"), "shift-click line names the /sell proceeds");
		DeliverResult r1 = book.deliver(b, low.id, 64);
		eq(Status.OK, r1.status(), "delivered");
		eq(0L, r1.bonus(), "delivery below /sell: no bonus booked");
		eq(0L, board.bonusUsed(who), "daily bonus untouched");
		isTrue(r1.net() < 64 * sellB, "less than /sell (" + r1.net() + " < " + 64 * sellB + ")");
		Order high = boardOrder(book, COAL, cfg.boardDemandMax, 100_000);
		double p0 = board.pressure(high.item);
		long expB = expectedBonus(ref, cap, sellB, cfg.boardDemandMax, p0, 64, cfg);
		long expC = expectedBonus(ref, board.cap(other, COAL), sellC, cfg.boardDemandMax, p0, 64, cfg);
		eq(expC, board.payout(other, high, 64).bonus(), "without rank: bonus above their /sell price");
		isTrue(expB > 0 && expB < expC, "with rank smaller bonus share (" + expB + " < " + expC + ")");
		String loreHigh = loreText(OrdersGui.orderIcon(book, b, high, true, 0, 0, null));
		isFalse(loreHigh.contains("Below your /sell price"), "high demand: no warning");
		give(b, new ItemStack(COAL, 64));
		DeliverResult r2 = book.deliver(b, high.id, 64);
		eq(Status.OK, r2.status(), "delivered (high)");
		eq(expB, r2.bonus(), "bonus = Σ max(0, unit price - their /sell price)");
		eq(expB, board.bonusUsed(who), "daily bonus booked");
		isTrue(r2.net() <= 64 * sellB + r2.bonus(), "proceeds ≤ 64 × /sell + bonus");
		cfg.boardDailyBonusPerPlayer = board.bonusUsed(who) + 3;
		NpcBoard.Payout pc = board.payout(who, high, 64);
		isTrue(pc.bonusLimited(), "daily bonus caps");
		eq(3L, pc.bonus(), "only the rest");
		eq(sellB + 3, pc.first(), "first item: /sell + rest");
		eq(64 * sellB + 3, pc.total(), "rest at their /sell price");
		cfg.boardDailyBonusPerPlayer = board.bonusUsed(who);
		eq(sellB, book.unitPrice(who, high), "bonus used up: exactly their /sell price");
		isTrue(loreText(OrdersGui.orderIcon(book, b, high, true, 0, 0, null)).contains("Daily bonus used up"), "lore: used up");
		long lowUnit = Math.min((long) Math.floor(ref * NpcBoard.factorAt(low.demand, board.pressure(low.item), cfg.boardDemandFloor) + 1e-9), cap);
		isTrue(lowUnit < sellB, "low demand still below /sell");
		eq(lowUnit, book.unitPrice(who, low), "below /sell: used-up bonus changes nothing");
		b.getInventory().clearContent();
		ctx.detail("coal /sell " + Money.format(sellB) + " (rank) vs. reference " + Money.format(ref) + ": below /sell no bonus, bonus "
			+ Money.format(expB) + " instead of " + Money.format(expC));
	}

	/**
	 * Bought NPC goods are normal goods (no marker): unmodified items are accepted; renamed, damaged and villager
	 * trade goods are not, same rule as /sell.
	 */
	private static void boardPristineFlip(TestContext ctx) {
		FakePlayer b = player(ctx, "OrdB", 0);
		OrderBook book = boardBook(new OrderData(), new long[] {T0}, new OrdersConfig());
		Order o = boardOrder(book, DIAMOND, 1.2, 100);
		ItemStack named = new ItemStack(DIAMOND, 3);
		named.set(DataComponents.CUSTOM_NAME, Component.literal("Fake"));
		ItemStack trader = new ItemStack(DIAMOND, 2);
		dev.smpeconomy.core.TraderGoods.markTradeResult(trader);
		isTrue(dev.smpeconomy.core.TraderGoods.isMarked(trader), "trader goods marked");
		give(b, named);
		give(b, trader);
		eq(Status.NO_ITEMS, book.deliver(b, o.id, 5).status(), "renamed/trader goods rejected");
		eq(5, count(b, DIAMOND), "items stay");
		ItemStack fromMarket = new ItemStack(DIAMOND, 4);
		isTrue(Items.isPristine(fromMarket), "NPC goods are unmodified (no marker)");
		give(b, fromMarket);
		DeliverResult r = book.deliver(b, o.id, 10);
		eq(4, r.count(), "only the 4 unmodified delivered");
		eq(5, count(b, DIAMOND), "renamed + trader goods stay");
		ctx.detail("4 delivered, 5 rejected");
	}

	private static void boardTerms(TestContext ctx) {
		FakePlayer b = player(ctx, "OrdB", 0);
		FakePlayer c = player(ctx, "OrdC", 0);
		UUID who = b.getUUID();
		ctx.cleanup(() -> PriceModifiers.removeSource(MOD_SOURCE));
		PriceModifiers.removeSource(MOD_SOURCE);
		long[] clock = {T0};
		OrdersConfig cfg = new OrdersConfig();
		cfg.boardDailyBonusPerPlayer = 1_000_000_000L;
		OrderBook book = boardBook(new OrderData(), clock, cfg);
		Order o = boardOrder(book, COAL, 1.3, 2304);
		give(b, new ItemStack(COAL, 64));
		give(c, new ItemStack(COAL, 640));
		Terms shown = book.terms(who, o, 64);
		NpcBoard.Payout p = book.boardPayout(who, o, 64);
		eq(p.total(), shown.gross(), "button shows the proceeds for 64");
		eq(Status.OK, book.deliver(c, o.id, 640).status(), "other player delivers 10 stacks");
		DeliverResult r1 = book.deliver(b, o.id, 64, shown);
		eq(Status.PRICE_CHANGED, r1.status(), "less than displayed: rejected");
		eq(64, count(b, COAL), "coal stays");
		eq(0L, bal(b), "nothing paid");
		Terms again = book.terms(who, o, 64);
		isTrue(again.gross() < shown.gross(), "displayed anew: less");
		clock[0] += 3 * (long) (cfg.boardRecoveryHalfLifeHours * OrderBook.HOUR_MS);
		DeliverResult r2 = book.deliver(b, o.id, 64, again);
		eq(Status.OK, r2.status(), "recovered: more than displayed, delivered");
		isTrue(r2.net() >= again.gross(), "at least the displayed proceeds");
		give(b, new ItemStack(COAL, 10));
		Terms for64 = book.terms(who, o, 64);
		DeliverResult r3 = book.deliver(b, o.id, 64, for64);
		eq(Status.OK, r3.status(), "10 instead of 64: delivered");
		eq(10, r3.count(), "10 items");
		ctx.detail("price change rejected, recovery + partial amount delivered");
	}

	private static void boardLegacyMigration(TestContext ctx) {
		long[] clock = {T0};
		FakeFunding fund = new FakeFunding(10_000_000, 1_000_000, 1_000_000);
		OrderBook book = book(new OrderData(), clock, fund, new OrdersConfig());
		Order legacy = book.createNpcOrder(WHEAT, 10, 50, OrderBook.HOUR_MS, OrderBook.NPC_LABEL, false);
		notNull(legacy, "old NPC order");
		Order bulk = book.startBulk(WHEAT, 16, OrderBook.HOUR_MS, null);
		notNull(bulk, "bulk order");
		Order board = boardOrder(book, COAL, 1.2, 64);
		long till = fund.till;
		eq(1, book.migrateLegacy(), "one migrated");
		eq(till + 500, fund.till, "escrow back to the treasury");
		isTrue(book.get(legacy.id).isEmpty(), "old order gone");
		isTrue(book.get(bulk.id).isPresent() && book.get(board.id).isPresent(), "bulk order and board stay");
		eq(1L, book.data().boardStats.migrated, "stats");
		eq(500L, book.data().boardStats.migratedEscrow, "stats escrow");
		eq(0, book.migrateLegacy(), "only once");
		eq(10_000_000L, fund.till + book.totalEscrow(), "money preserved");
		ctx.detail("escrow $5 back");
	}

	private static void boardLifecycle(TestContext ctx) {
		FakePlayer b = player(ctx, "OrdB", 0);
		long[] clock = {T0};
		OrdersConfig cfg = new OrdersConfig();
		OrderData data = new OrderData();
		OrderBook book = boardBook(data, clock, cfg);
		Order o = boardOrder(book, net.minecraft.world.item.Items.NETHERITE_SCRAP, 1.1, 2);
		give(b, new ItemStack(net.minecraft.world.item.Items.NETHERITE_SCRAP, 2));
		DeliverResult r = book.deliver(b, o.id, 2);
		isTrue(r.completed(), "fully delivered");
		isTrue(book.get(o.id).isEmpty(), "order gone");
		isTrue(data.supply.get(o.item).cooldownUntil > clock[0], "cooldown set");
		book.tick();
		isTrue(data.orders.values().stream().noneMatch(x -> x.item.equals(o.item)), "not back on the board at once");
		clock[0] += (cfg.boardCooldownMinutes + 1) * OrderBook.MINUTE_MS;
		data.orders.values().removeIf(x -> x.board && x.item.startsWith("minecraft:a"));
		cfg.boardRefillPerTick = 2000;
		book.tick();
		isTrue(data.orders.values().stream().anyMatch(x -> x.item.equals(o.item)), "back after the cooldown");
		int size = book.board().size();
		isTrue(size > 300, "board full");
		cfg.boardEnabled = false;
		book.tick();
		eq(0, book.board().size(), "board off: orders gone");
		give(b, new ItemStack(COAL, 1));
		cfg.boardEnabled = true;
		Order again = boardOrder(book, COAL, 1.2, 10);
		cfg.npcEnabled = false;
		eq(Status.NOT_ACCEPTING, book.deliver(b, again.id, 1).status(), "NPC off: accepts nothing");
		book.tick();
		eq(0, book.board().size(), "NPC off: orders gone");
		eq(1, count(b, COAL), "coal stays");
		ctx.detail("cooldown, refill, disable ok");
	}

	/**
	 * No loop through the board: for each tradable item the highest possible board price (max demand, no pressure) is
	 * below the shop price and below the NPC offer floor, also with random news factors.
	 */
	private static void boardNoArbitrage(TestContext ctx) {
		FakePlayer b = player(ctx, "OrdB", 0);
		UUID who = b.getUUID();
		ctx.cleanup(() -> PriceModifiers.removeSource(MOD_SOURCE));
		OrdersConfig cfg = new OrdersConfig();
		OrderBook book = boardBook(new OrderData(), new long[] {T0}, cfg);
		Random rnd = new Random(5);
		double[] factors = {0.3, 0.5, 1.0, 1.5, 2.0, 3.0};
		int checks = 0;
		for (Item item : book.board().eligible(true)) {
			PriceModifiers.removeSource(MOD_SOURCE);
			if (rnd.nextBoolean()) PriceModifiers.set(MOD_SOURCE, item, factors[rnd.nextInt(factors.length)], 0, 60_000, 0, "Selftest");
			Order o = new Order();
			o.npc = true;
			o.board = true;
			o.item = Items.id(item);
			o.demand = NpcBoard.HARD_MAX_DEMAND;
			o.amount = 1;
			long unit = book.board().payout(who, o, 1).first();
			OptionalLong shop = PriceGuard.effectiveShopBuyPrice(who, item);
			if (shop.isPresent()) check(unit < shop.getAsLong(), Items.id(item) + ": board " + unit + " < Shop " + shop.getAsLong());
			OptionalLong mv = Services.marketValue(item);
			if (mv.isPresent()) {
				check(unit < PriceGuard.minSystemSalePrice(who, item, mv.getAsLong()), Items.id(item) + ": board < NPC offer");
				check(unit <= PriceGuard.maxNpcOrderPrice(who, item, mv.getAsLong()), Items.id(item) + ": board ≤ maxNpcOrderPrice");
			}
			long pivot = PriceGuard.pivotPrice(item);
			if (pivot >= 0) check(unit <= pivot, Items.id(item) + ": ≤ Pivot");
			checks++;
		}
		isTrue(checks >= 300, "all tradable items checked (" + checks + ")");
		isTrue(NpcBoard.HARD_MAX_DEMAND < 1.68, "max demand below the shop's recipe guard (1.68)");
		ctx.detail(checks + " items without a loop");
	}

	private static void boardPersistence(TestContext ctx) {
		FakePlayer b = player(ctx, "OrdB", 0);
		OrderData data = new OrderData();
		long[] clock = {T0};
		OrderBook book = boardBook(data, clock, new OrdersConfig());
		Order o = boardOrder(book, COAL, 1.234, 640);
		give(b, new ItemStack(COAL, 64));
		eq(Status.OK, book.deliver(b, o.id, 64).status(), "delivered");
		String json = DataStore.gson().toJson(data);
		OrderData back = DataStore.gson().fromJson(json, OrderData.class);
		back.fixNulls();
		Order o2 = back.orders.get(o.id);
		notNull(o2, "board order");
		isTrue(o2.board && o2.npc && !o2.bulk, "board mark");
		isTrue(Math.abs(o2.demand - 1.234) < 1e-12, "demand");
		eq(o.label, o2.label, "NPC name");
		eq(64, o2.delivered, "delivered");
		notNull(back.supply.get(o.item), "supply saved");
		isTrue(Math.abs(back.supply.get(o.item).pressure - data.supply.get(o.item).pressure) < 1e-12, "pressure");
		eq(data.boardStats.paidTotal, back.boardStats.paidTotal, "stats");
		if (data.bonus.containsKey(b.getUUID())) eq(data.bonus.get(b.getUUID()).cents, back.bonus.get(b.getUUID()).cents, "Bonus");
		OrderData broken = DataStore.gson().fromJson("{\"orders\":{\"1\":{\"item\":\"minecraft:coal\",\"amount\":3,\"board\":true,\"npc\":false,"
			+ "\"owner\":\"" + b.getUUID() + "\"},\"2\":{\"item\":\"minecraft:coal\",\"amount\":3,\"board\":true,\"npc\":true,\"escrow\":50},"
			+ "\"3\":{\"item\":\"minecraft:coal\",\"amount\":3,\"board\":true,\"npc\":true,\"demand\":99}},\"supply\":{\"minecraft:coal\":null,"
			+ "\"minecraft:iron_ingot\":{\"pressure\":-3}},\"bonus\":null,\"boardStats\":null}", OrderData.class);
		broken.fixNulls();
		isFalse(broken.orders.get(1L).board, "player order never board");
		isFalse(broken.orders.get(2L).board, "board with escrow becomes escrow order (gets migrated)");
		eq(1.0, broken.orders.get(3L).demand, "invalid demand gives 1.0");
		isFalse(broken.supply.containsKey("minecraft:coal"), "null supply removed");
		eq(0.0, broken.supply.get("minecraft:iron_ingot").pressure, "negative pressure gives 0");
		notNull(broken.bonus, "bonus repaired");
		notNull(broken.boardStats, "stats repaired");
		ctx.detail("round trip + repair ok");
	}

	private static long systemFloor(UUID who, Item item) {
		long f = Long.MAX_VALUE;
		OptionalLong mv = Services.marketValue(item);
		if (mv.isPresent()) f = PriceGuard.minSystemSalePrice(who, item, mv.getAsLong());
		OptionalLong shop = PriceGuard.effectiveShopBuyPrice(who, item);
		if (shop.isPresent()) f = Math.min(f, shop.getAsLong());
		return f;
	}

	private static void npcConversionArbitrage(TestContext ctx) {
		FakePlayer b = player(ctx, "OrdB", 0);
		UUID who = b.getUUID();
		ctx.cleanup(() -> PriceModifiers.removeSource(MOD_SOURCE));
		PriceModifiers.removeSource(MOD_SOURCE);
		FakeFunding fund = new FakeFunding(100_000_000, 10_000_000, 10_000_000);
		OrdersConfig cfg = new OrdersConfig();
		// nuggets are no default (the system sells none); added here to test chains
		List<OrdersConfig.Conversion> conv = new ArrayList<>(cfg.npcConversions);
		OrdersConfig.Conversion n2i = new OrdersConfig.Conversion();
		n2i.from = Items.id(IRON_NUGGET);
		n2i.in = 9;
		n2i.to = Items.id(IRON);
		n2i.out = 1;
		conv.add(n2i);
		cfg.npcConversions = conv;
		cfg.validate();
		OrderBook book = book(new OrderData(), new long[] {T0}, fund, cfg);
		check(Services.marketValue(IRON).isPresent() && Services.marketValue(IRON_BLOCK).isPresent(), "market values iron/iron block");
		Order o = book.createNpcOrder(IRON, 64, 100_000, OrderBook.HOUR_MS, OrderBook.NPC_LABEL, false);
		notNull(o, "NPC order iron ingot");
		long base = book.unitPrice(who, o);
		isTrue(base <= npcCap(who, IRON), "never above PriceGuard");
		isTrue(base * 9 < systemFloor(who, IRON_BLOCK), "neutral: 9 × price < block price");
		// iron blocks cheaper (drift or crash), ingots unchanged: without the conversion cap, 1.8k profit per block
		PriceModifiers.set(MOD_SOURCE, IRON_BLOCK, 0.8, 0, 60_000, 0, "Selftest");
		long blockFloor = systemFloor(who, IRON_BLOCK);
		long unit = book.unitPrice(who, o);
		isTrue(unit < npcCap(who, IRON), "conversion cap applies (" + unit + " < " + npcCap(who, IRON) + ")");
		isTrue(unit * 9 < blockFloor, "9 × " + unit + " < block price " + blockFloor);
		eq(unit, book.quote(who, o).conversion(), "quote names the conversion cap");
		give(b, new ItemStack(IRON, 9));
		DeliverResult r = book.deliver(b, o.id, 9);
		eq(9, r.count(), "9 delivered");
		isTrue(r.net() < blockFloor, "proceeds " + r.net() + " < block purchase price " + blockFloor);
		PriceModifiers.set(MOD_SOURCE, IRON, 2.0, 0, 60_000, 0, "Selftest");
		isTrue(book.unitPrice(who, o) * 9 < systemFloor(who, IRON_BLOCK), "hype on ingots: still no profit");
		Order bulk = book.startBulk(IRON, 64, OrderBook.HOUR_MS, null);
		notNull(bulk, "bulk order iron");
		isTrue(bulk.price * 9 < systemFloor(OrderBook.NEUTRAL, IRON_BLOCK), "bulk price × 9 < block price");
		eq(bulk.price, book.npcOfferCap(IRON), "bulk order at the capped max price");
		PriceModifiers.removeSource(MOD_SOURCE);
		PriceModifiers.set(MOD_SOURCE, IRON, 0.5, 0, 60_000, 0, "Selftest");
		Order blockBulk = book.startBulk(IRON_BLOCK, 8, OrderBook.HOUR_MS, null);
		notNull(blockBulk, "bulk order iron block");
		isTrue(blockBulk.price < 9 * systemFloor(OrderBook.NEUTRAL, IRON), "block price < 9 × ingot price");
		isTrue(book.unitPrice(who, blockBulk) < 9 * systemFloor(who, IRON), "on delivery: < 9 × ingot price");
		PriceModifiers.set(MOD_SOURCE, IRON_NUGGET, 0.05, 0, 60_000, 0, "Selftest");
		isTrue(book.unitPrice(who, blockBulk) < 81 * systemFloor(who, IRON_NUGGET), "chain nugget, ingot, block capped");
		isTrue(book.unitPrice(who, o) < 9 * systemFloor(who, IRON_NUGGET), "9 nuggets to ingot capped");
		eq(new OrderBook.Quote(777, -1, -1), book.quote(who, playerOrder(777)), "player order: own price");
		eq(100_000_000L, fund.till + book.totalEscrow() + bal(b), "money preserved");
		ctx.detail("block -20%: " + Money.format(base) + " → " + Money.format(unit) + " per ingot");
	}

	private static Order playerOrder(long price) {
		Order o = new Order();
		o.item = Items.id(IRON);
		o.price = price;
		return o;
	}

	private static void npcConversionSweep(TestContext ctx) {
		FakePlayer b = player(ctx, "OrdB", 0);
		UUID who = b.getUUID();
		ctx.cleanup(() -> PriceModifiers.removeSource(MOD_SOURCE));
		OrdersConfig cfg = new OrdersConfig();
		OrderBook book = book(new OrderData(), new long[] {T0}, new FakeFunding(Money.MAX / 2, Money.MAX / 4, Money.MAX / 4), cfg);
		double[] factors = {0.05, 0.3, 0.5, 0.8, 0.95, 1.0, 1.05, 1.3, 2.0, 3.0, 20.0};
		Random rnd = new Random(11);
		int checks = 0;
		int binding = 0;
		for (OrdersConfig.Conversion c : cfg.npcConversions) {
			Item from = Items.byId(c.from).orElseThrow();
			Item to = Items.byId(c.to).orElseThrow();
			for (int i = 0; i < 12; i++) {
				PriceModifiers.removeSource(MOD_SOURCE);
				PriceModifiers.set(MOD_SOURCE, from, factors[rnd.nextInt(factors.length)], 0, 60_000, 0, "Selftest");
				PriceModifiers.set(MOD_SOURCE, to, factors[rnd.nextInt(factors.length)], 0, 60_000, 0, "Selftest");
				long floor = systemFloor(who, from);
				if (floor == Long.MAX_VALUE) continue;
				Order o = new Order();
				o.npc = true;
				o.item = c.to;
				o.price = Money.MAX / 1_000;
				long unit = book.unitPrice(who, o);
				check(unit * c.out < floor * c.in, c.in + "× " + c.from + " → " + c.out + "× " + c.to + ": " + unit + " × " + c.out + " < " + floor + " × " + c.in);
				long offer = book.npcOfferCap(to);
				long neutralFloor = systemFloor(OrderBook.NEUTRAL, from);
				check(offer * c.out < neutralFloor * c.in, "create: " + c.to + " " + offer + " × " + c.out + " < " + neutralFloor + " × " + c.in);
				OptionalLong mv = Services.marketValue(to);
				if (mv.isPresent() && unit < PriceGuard.maxNpcOrderPrice(who, to, mv.getAsLong())) binding++;
				checks++;
			}
		}
		isTrue(checks > 100, "enough cases (" + checks + ")");
		isTrue(binding > 0, "cap applies in some cases");
		ctx.detail(checks + " cases, " + binding + "x capped");
	}

	private static void niceAmount() {
		eq(5, OrderBook.niceAmount(5), "small");
		eq(16, OrderBook.niceAmount(23), "8er");
		eq(64, OrderBook.niceAmount(79), "16er");
		eq(256, OrderBook.niceAmount(300), "64er");
		eq(0, OrderBook.niceAmount(-3), "negative");
	}
}
