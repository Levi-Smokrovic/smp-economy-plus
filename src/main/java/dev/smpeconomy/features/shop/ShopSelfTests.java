package dev.smpeconomy.features.shop;

import static dev.smpeconomy.core.test.Assert.check;
import static dev.smpeconomy.core.test.Assert.eq;
import static dev.smpeconomy.core.test.Assert.isFalse;
import static dev.smpeconomy.core.test.Assert.isTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SingleItemRecipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.GameType;
import dev.smpeconomy.core.compat.Mc;
import dev.smpeconomy.core.BasePrices;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Hooks;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.PriceGuard;
import dev.smpeconomy.core.PriceModifiers;
import dev.smpeconomy.core.Treasury;
import dev.smpeconomy.core.test.SelfTests;
import dev.smpeconomy.core.test.TestContext;
import dev.smpeconomy.features.shop.ShopService.BuyResult;
import dev.smpeconomy.features.shop.ShopService.SellResult;
import dev.smpeconomy.features.shop.ShopService.Status;

/** GUIs are covered by {@code e2e/shop.mjs}. */
final class ShopSelfTests {
	private static final String F = "shop";
	/** Removed in cleanup. */
	private static final String SRC = "shop.selftest";

	private ShopSelfTests() {}

	static void register() {
		SelfTests.register(F, "buy-math", ShopSelfTests::buyMath);
		SelfTests.register(F, "buy-not-enough-money", ShopSelfTests::buyInsufficientFunds);
		SelfTests.register(F, "buy-inventory-full", ShopSelfTests::buyInventoryFull);
		SelfTests.register(F, "buy-edge-cases", ShopSelfTests::buyEdgeCases);
		SelfTests.register(F, "price-guard-offers-flashsale-news", ShopSelfTests::guardWithDeals);
		SelfTests.register(F, "daily-offers", ShopSelfTests::dailyDeals);
		SelfTests.register(F, "sell-like-sell-command", ShopSelfTests::sellEqualsSell);
		SelfTests.register(F, "sell-edge-cases", ShopSelfTests::sellEdgeCases);
		SelfTests.register(F, "sell-creative-blocked", ShopSelfTests::sellBlockedInCreative);
		SelfTests.register(F, "favorites-persistence", ShopSelfTests::favorites);
		SelfTests.register(F, "recently-bought", ShopSelfTests::recent);
		SelfTests.register(F, "search", ctx -> search());
		SelfTests.register(F, "amounts-and-trend", ctx -> amountsAndTrend());
		SelfTests.register(F, "recipes-without-arbitrage", ShopSelfTests::recipes);
		SelfTests.register(F, "recipes-live-hype", ShopSelfTests::recipesLiveHype);
		SelfTests.register(F, "recipe-guard-math", ctx -> recipeGuardMath());
		SelfTests.register(F, "config-validation", ctx -> configValidation());
		SelfTests.register(F, "category-names-english", ctx -> categoryNamesEnglish());
	}

	/** Stone, otherwise the first shop item with a sell price and stack size 64. */
	private static Item tradeItem() {
		Item stone = net.minecraft.world.item.Items.STONE;
		if (BasePrices.shopBuyPrice(stone).isPresent() && BasePrices.sellPrice(stone).isPresent()) return stone;
		for (Item item : BasePrices.shopBuyPrices().keySet()) {
			if (BasePrices.sellPrice(item).isPresent() && item.getDefaultMaxStackSize() == 64) return item;
		}
		throw new AssertionError("no shop item with sell price in shop.json");
	}

	/** Neither in the shop nor sellable, used as filler. */
	private static Item filler() {
		Item bedrock = net.minecraft.world.item.Items.BEDROCK;
		isTrue(BasePrices.shopBuyPrice(bedrock).isEmpty() && BasePrices.sellPrice(bedrock).isEmpty(), "bedrock neither in shop nor sellable");
		return bedrock;
	}

	private static int count(FakePlayer p, Item item) {
		return Items.count(p, s -> s.is(item));
	}

	private static void fillInventory(FakePlayer p, Item item) {
		Inventory inv = p.getInventory();
		for (int i = 0; i < Items.MAIN_SIZE; i++) inv.setItem(i, new ItemStack(item, item.getDefaultMaxStackSize()));
	}

	private static void buyMath(TestContext ctx) {
		ctx.preserveTreasury();
		FakePlayer a = ctx.player("ShopBuyA");
		UUID id = a.getUUID();
		Item item = tradeItem();
		EconomyService.set(id, 100_000);
		long unit = Math.max(PriceGuard.effectiveShopBuyPrice(id, item).orElseThrow(), RecipeGuard.floor(item));
		eq(unit, ShopService.unitBuyPrice(id, item).orElseThrow(), "shop price = max(PriceGuard.effectiveShopBuyPrice, recipe floor)");
		long treasuryBefore = Treasury.balance();

		BuyResult r = ShopService.buy(a, item, 10, -1);
		eq(Status.OK, r.status(), "buy ok");
		eq(10, r.delivered(), "10 delivered");
		eq(unit, r.unitPrice(), "unit price");
		eq(unit * 10, r.total(), "total = 10 x unit price");
		eq(100_000 - unit * 10, EconomyService.balance(id), "balance");
		eq(r.balanceAfter(), EconomyService.balance(id), "balanceAfter");
		eq(10, count(a, item), "items in inventory");
		long share = Money.percentOf(unit * 10, ShopConfig.get().treasurySharePercent);
		eq(share, r.treasuryShare(), "treasury share");
		eq(treasuryBefore + share, Treasury.balance(), "treasury receives its share");
		eq(item, ShopPlayerData.recent(id).get(0).item(), "recently bought");
		eq(10, ShopPlayerData.recent(id).get(0).amount(), "recently bought amount");

		int limit = ShopService.maxPerPurchase(item);
		isTrue(limit > 0, "purchase limit present");
		EconomyService.set(id, Money.MAX / 2);
		a.getInventory().clearContent();
		BuyResult big = ShopService.buy(a, item, limit + 1000, -1);
		eq(Status.OK, big.status(), "large buy ok");
		eq(limit, big.requested(), "capped at purchase limit");
		eq(Math.min(limit, 36 * item.getDefaultMaxStackSize()), big.delivered(), "delivered = min(limit, space)");
		eq(big.delivered(), count(a, item), "inventory matches delivery");
		isTrue(ctx.drops(a).isEmpty(), "nothing dropped");
		ctx.detail("unit price " + Money.format(unit) + ", share " + Money.format(share) + ", limit " + limit);
	}

	private static void buyInsufficientFunds(TestContext ctx) {
		ctx.preserveTreasury();
		FakePlayer a = ctx.player("ShopBuyB");
		UUID id = a.getUUID();
		Item item = tradeItem();
		long unit = ShopService.unitBuyPrice(id, item).orElseThrow();
		long treasury = Treasury.balance();

		EconomyService.set(id, unit * 5 - 1);
		BuyResult r = ShopService.buy(a, item, 5, -1);
		eq(Status.INSUFFICIENT_FUNDS, r.status(), "1 cent short");
		eq(unit * 5 - 1, EconomyService.balance(id), "balance unchanged");
		eq(0, count(a, item), "no items");
		eq(treasury, Treasury.balance(), "treasury unchanged");
		isTrue(ShopPlayerData.recent(id).isEmpty(), "no recent entry without a purchase");

		EconomyService.set(id, unit * 5);
		eq(Status.OK, ShopService.buy(a, item, 5, -1).status(), "exact fit");
		eq(0, EconomyService.balance(id), "balance 0");
		eq(5, count(a, item), "5 Items");

		eq(Status.INSUFFICIENT_FUNDS, ShopService.buy(a, item, 1, -1).status(), "without money");
		EconomyService.deleteAccount(id);
		eq(Status.INSUFFICIENT_FUNDS, ShopService.buy(a, item, 1, -1).status(), "without account");
		eq(5, count(a, item), "nothing added");
		isFalse(EconomyService.hasAccount(id), "no account created");
	}

	private static void buyInventoryFull(TestContext ctx) {
		ctx.preserveTreasury();
		FakePlayer a = ctx.player("ShopBuyC");
		UUID id = a.getUUID();
		Item item = tradeItem();
		Item filler = filler();
		long unit = ShopService.unitBuyPrice(id, item).orElseThrow();
		EconomyService.set(id, 10_000_000);
		long treasury = Treasury.balance();

		fillInventory(a, filler);
		BuyResult none = ShopService.buy(a, item, 10, -1);
		eq(Status.NO_SPACE, none.status(), "inventory full: nothing");
		eq(10_000_000, EconomyService.balance(id), "nothing charged");
		eq(treasury, Treasury.balance(), "treasury unchanged");

		a.getInventory().setItem(5, ItemStack.EMPTY);
		int stack = item.getDefaultMaxStackSize();
		BuyResult part = ShopService.buy(a, item, stack + 36, -1);
		eq(Status.OK, part.status(), "partial buy ok");
		isTrue(part.partial(), "marked as partial");
		eq(stack + 36, part.requested(), "requested");
		eq(stack, part.delivered(), "only one stack delivered");
		eq(unit * stack, part.total(), "paid only for delivered");
		eq(10_000_000 - unit * stack, EconomyService.balance(id), "balance");
		eq(stack, count(a, item), "stack in inventory");

		a.getInventory().setItem(5, new ItemStack(item, stack - 4));
		BuyResult rest = ShopService.buy(a, item, 10, -1);
		eq(4, rest.delivered(), "rest up to a full stack");
		eq(unit * 4, rest.total(), "4 paid");
		eq(stack, count(a, item), "stack full again");
		eq(Status.NO_SPACE, ShopService.buy(a, item, 1, -1).status(), "now full again");
		isTrue(ctx.drops(a).isEmpty(), "nothing dropped at feet");
	}

	private static void buyEdgeCases(TestContext ctx) {
		ctx.preserveTreasury();
		FakePlayer a = ctx.player("ShopBuyD");
		UUID id = a.getUUID();
		Item item = tradeItem();
		EconomyService.set(id, 1_000_000);
		long unit = ShopService.unitBuyPrice(id, item).orElseThrow();

		eq(Status.INVALID_AMOUNT, ShopService.buy(a, item, 0, -1).status(), "amount 0");
		eq(Status.INVALID_AMOUNT, ShopService.buy(a, item, -5, -1).status(), "negative amount");
		eq(Status.NOT_IN_SHOP, ShopService.buy(a, filler(), 1, -1).status(), "not in shop");
		eq(0, ShopService.maxPerPurchase(filler()), "no purchase limit for non-shop items");

		BuyResult changed = ShopService.buy(a, item, 3, unit - 1);
		eq(Status.PRICE_CHANGED, changed.status(), "price above display");
		eq(unit, changed.unitPrice(), "current price reported");
		eq(1_000_000, EconomyService.balance(id), "nothing charged");
		eq(0, count(a, item), "nothing delivered");
		eq(Status.OK, ShopService.buy(a, item, 3, unit).status(), "displayed price = current price");
		eq(Status.OK, ShopService.buy(a, item, 1, unit + 100).status(), "cheaper than displayed is ok");

		eq(-1, ShopService.total(Money.MAX, 2), "overflow above MAX");
		eq(-1, ShopService.total(Long.MAX_VALUE, 2), "long overflow");
		eq(-1, ShopService.total(-1, 2), "negative price");
		eq(600, ShopService.total(200, 3), "total");
		eq(0, ShopService.affordable(99, 100), "not affordable");
		eq(3, ShopService.affordable(399, 100), "affordable rounded down");
		eq(0, ShopService.affordable(1000, -1), "without price");
		eq(500, ShopService.treasuryShare(1000, 50.0), "50 %");
		eq(499, ShopService.treasuryShare(999, 50.0), "rounded down");
		eq(0, ShopService.treasuryShare(1000, 0), "0 %");
		eq(1000, ShopService.treasuryShare(1000, 100), "100 %");
	}

	private static void guardWithDeals(TestContext ctx) {
		ctx.preserveTreasury();
		FakePlayer a = ctx.player("ShopGuard");
		UUID id = a.getUUID();
		double[] flash = {1.0};
		double[] sellBoost = {1.0};
		List<Hooks.Registration> regs = new ArrayList<>();
		ctx.cleanup(() -> {
			regs.forEach(Hooks.Registration::remove);
			PriceModifiers.removeSource(SRC);
			DailyDeals.clearTestOverrides();
		});
		regs.add(Hooks.addShopDiscount(SRC + ".flash", "Test-Flash-Sale", i -> flash[0]));
		regs.add(Hooks.addSellMultiplier(SRC + ".sell", "Test-Market-Day", (p, i) -> sellBoost[0]));
		int maxDeal = ShopConfig.get().dailyDealMaxPercent;
		int checked = 0;
		for (Map.Entry<Item, Long> e : BasePrices.shopBuyPrices().entrySet()) {
			Item item = e.getKey();
			long pivot = PriceGuard.pivotPrice(item);
			eq(PriceGuard.pivot(e.getValue()), pivot, "pivot from shop.json: " + Items.id(item));
			// Lowest point: crash -50 %, flash sale -40 %, daily deal
			DailyDeals.forceForTest(item, maxDeal);
			flash[0] = 0.6;
			sellBoost[0] = 1.0;
			PriceModifiers.set(SRC, item, 0.5, 0, 0, 0, "Test-Crash");
			long lowBuy = ShopService.unitBuyPrice(id, item).orElseThrow();
			long sellNow = PriceGuard.effectiveSellPrice(id, item).orElse(0);
			eq(Math.max(PriceGuard.effectiveShopBuyPrice(id, item).orElseThrow(), RecipeGuard.floor(item)), lowBuy,
				"shop = max(PriceGuard, recipe floor): " + Items.id(item));
			check(lowBuy >= pivot + 1, "buy >= pivot + 1 despite discounts: " + Items.id(item) + " " + lowBuy + " pivot " + pivot);
			check(lowBuy >= RecipeGuard.lowestPrice(item), "buy >= recipe floor despite discounts: " + Items.id(item));
			check(lowBuy * 4 >= sellNow * 5, "buy >= 1.25 x sell at the same moment: " + Items.id(item));
			// Highest point: hype x3, sell bonus x1.5, with all discounts active
			PriceModifiers.set(SRC, item, 3.0, 0, 0, 0, "Test-Hype");
			sellBoost[0] = 1.5;
			long highSell = PriceGuard.effectiveSellPrice(id, item).orElse(0);
			long buyNow = ShopService.unitBuyPrice(id, item).orElseThrow();
			check(highSell <= pivot, "sell <= pivot during hype: " + Items.id(item));
			check(highSell < lowBuy, "buy low then sell high never profitable: " + Items.id(item));
			check(buyNow * 4 >= highSell * 5 && buyNow > highSell, "hype: buy > sell: " + Items.id(item));
			PriceModifiers.remove(SRC, item);
			DailyDeals.clearTestOverrides();
			checked++;
		}

		Item item = tradeItem();
		EconomyService.set(id, 1_000_000);
		DailyDeals.forceForTest(item, maxDeal);
		flash[0] = 0.6;
		sellBoost[0] = 1.0;
		PriceModifiers.set(SRC, item, 0.5, 0, 0, 0, "Test-Crash");
		BuyResult bought = ShopService.buy(a, item, 64, -1);
		eq(Status.OK, bought.status(), "buy at low");
		DailyDeals.clearTestOverrides();
		flash[0] = 1.0;
		PriceModifiers.set(SRC, item, 3.0, 0, 0, 0, "Test-Hype");
		sellBoost[0] = 1.5;
		SellResult sold = ShopService.sell(a, item, 64, -1);
		eq(Status.OK, sold.status(), "sell at high");
		eq(64, sold.sold(), "everything sold");
		check(sold.total() < bought.total(), "loss instead of profit (" + Money.format(bought.total()) + " → " + Money.format(sold.total()) + ")");
		check(EconomyService.balance(id) < 1_000_000, "balance dropped");
		ctx.detail(checked + " shop items checked under crash+flash sale+daily deal and hype+bonus");
	}

	private static void dailyDeals(TestContext ctx) {
		ctx.cleanup(DailyDeals::clearTestOverrides);
		ShopConfig cfg = new ShopConfig();
		Map<Item, Long> shop = BasePrices.shopBuyPrices();
		LocalDate day = LocalDate.of(2026, 3, 14);
		List<DailyDeals.Deal> first = DailyDeals.dealsFor(day, shop, cfg);
		eq(first, DailyDeals.dealsFor(day, shop, cfg), "deterministic per date");
		long candidates = shop.values().stream().filter(p -> p >= cfg.dailyDealMinBasePrice).count();
		eq((long) Math.min(cfg.dailyDealCount, candidates), first.size(), "deal count");
		Set<Item> seen = new HashSet<>();
		for (DailyDeals.Deal d : first) {
			isTrue(seen.add(d.item()), "no duplicate deals");
			isTrue(shop.containsKey(d.item()), "only items from shop.json: " + Items.id(d.item()));
			isTrue(shop.get(d.item()) >= cfg.dailyDealMinBasePrice, "minimum base price");
			isTrue(d.percent() >= cfg.dailyDealMinPercent && d.percent() <= cfg.dailyDealMaxPercent, "discount in range: " + d.percent());
			eq(0, (d.percent() - cfg.dailyDealMinPercent) % 5, "steps of 5");
		}
		Set<List<DailyDeals.Deal>> variants = new HashSet<>();
		for (int i = 0; i < 14; i++) variants.add(DailyDeals.dealsFor(day.plusDays(i), shop, cfg));
		isTrue(variants.size() >= 7, "deals change daily (" + variants.size() + "/14 different)");

		ShopConfig excl = new ShopConfig();
		for (DailyDeals.Deal d : first) excl.dailyDealExcluded.add(Items.id(d.item()));
		for (DailyDeals.Deal d : DailyDeals.dealsFor(day, shop, excl)) isFalse(first.stream().anyMatch(f -> f.item() == d.item()), "excluded items");
		ShopConfig off = new ShopConfig();
		off.dailyDealCount = 0;
		isTrue(DailyDeals.dealsFor(day, shop, off).isEmpty(), "0 means off");

		List<DailyDeals.Deal> today = DailyDeals.today();
		for (DailyDeals.Deal d : today) {
			eq(1.0 - d.percent() / 100.0, DailyDeals.factor(d.item()), "factor " + Items.id(d.item()));
			isTrue(Hooks.shopParts(d.item()).stream().anyMatch(p -> p.id().equals(DailyDeals.HOOK_ID)), "hook active for " + Items.id(d.item()));
			long base = BasePrices.shopBuyPrice(d.item()).orElseThrow();
			// Buy side as in PriceGuard: a running crash does not count
			long sell = PriceGuard.effectiveSellPriceWithoutCrash(new UUID(0, 0), d.item()).orElse(0);
			eq(PriceGuard.computeShop(base, PriceGuard.buySideModifier(d.item()), Hooks.shopDiscount(d.item()), sell, PriceGuard.pivotPrice(d.item())),
				PriceGuard.effectiveShopBuyPrice(new UUID(0, 0), d.item()).orElseThrow(), "deal price via PriceGuard");
		}
		for (Item item : shop.keySet()) {
			if (today.stream().noneMatch(d -> d.item() == item)) {
				eq(1.0, DailyDeals.factor(item), "no discount without deal: " + Items.id(item));
				break;
			}
		}
		Item notInShop = filler();
		DailyDeals.forceForTest(notInShop, 25);
		eq(1.0, DailyDeals.factor(notInShop), "no discount for items outside shop.json");
		isTrue(DailyDeals.millisUntilReset() > 0 && DailyDeals.millisUntilReset() <= 25 * 3_600_000L, "reset by midnight at the latest");
		ctx.detail(first.size() + " deals, " + variants.size() + "/14 days different, today " + today.size());
	}

	private static void sellEqualsSell(TestContext ctx) {
		ctx.preserveTreasury();
		FakePlayer a = ctx.player("ShopSellA");
		UUID id = a.getUUID();
		Item item = tradeItem();
		Item filler = filler();
		double[] boost = {1.5};
		List<Hooks.Registration> regs = new ArrayList<>();
		ctx.cleanup(() -> regs.forEach(Hooks.Registration::remove));
		regs.add(Hooks.addSellMultiplier(SRC + ".marketday", "Test-Market-Day", (p, i) -> p.equals(id) ? boost[0] : 1.0));
		long[] fired = {0, 0};
		regs.add(Hooks.onItemsSold((p, cents, n) -> {
			if (p.getUUID().equals(id)) {
				fired[0] += cents;
				fired[1] += n;
			}
		}));
		EconomyService.set(id, 1_000);
		Inventory inv = a.getInventory();
		inv.setItem(0, new ItemStack(item, 40));
		inv.setItem(7, new ItemStack(item, 10));
		ItemStack named = new ItemStack(item, 1);
		named.set(DataComponents.CUSTOM_NAME, Component.literal("My Stone"));
		inv.setItem(3, named);
		inv.setItem(4, new ItemStack(filler, 5));
		inv.setItem(Inventory.SLOT_OFFHAND, new ItemStack(item, 3));

		// /sell pays PriceGuard.effectiveSellPrice per item for all pristine stacks in the main inventory
		long unit = PriceGuard.effectiveSellPrice(id, item).orElseThrow();
		long expected = unit * 50;
		eq(unit, ShopService.unitSellPrice(id, item).orElseThrow(), "shop sell price = /sell price");
		SellResult r = ShopService.sell(a, item, Integer.MAX_VALUE, -1);
		eq(Status.OK, r.status(), "sell ok");
		eq(50, r.sold(), "only pristine items from main inventory");
		eq(expected, r.total(), "revenue = /sell result");
		eq(1_000 + expected, EconomyService.balance(id), "credit");
		eq(expected, fired[0], "onItemsSold: amount");
		eq(50, fired[1], "onItemsSold: count");
		eq(1, Items.count(a, s -> s.is(item)), "named stone stays");
		isTrue(inv.getItem(3).has(DataComponents.CUSTOM_NAME), "named stone unchanged");
		eq(3, inv.getItem(Inventory.SLOT_OFFHAND).getCount(), "offhand stays");
		eq(5, inv.getItem(4).getCount(), "other items stay");

		inv.setItem(0, new ItemStack(item, 30));
		SellResult part = ShopService.sell(a, item, 10, -1);
		eq(10, part.sold(), "10 sold");
		eq(unit * 10, part.total(), "10 x unit price");
		eq(21, Items.count(a, s -> s.is(item)), "rest stays (20 + named)");
		ctx.detail("item " + Money.format(unit) + " (with test bonus x1.5), 50 items = " + Money.format(expected));
	}

	private static void sellEdgeCases(TestContext ctx) {
		ctx.preserveTreasury();
		FakePlayer a = ctx.player("ShopSellB");
		UUID id = a.getUUID();
		Item item = tradeItem();
		EconomyService.set(id, 0);
		eq(Status.NOTHING_TO_SELL, ShopService.sell(a, item, 5, -1).status(), "nothing in inventory");
		ItemStack named = new ItemStack(item, 5);
		named.set(DataComponents.CUSTOM_NAME, Component.literal("x"));
		a.getInventory().setItem(0, named);
		eq(Status.NOTHING_TO_SELL, ShopService.sell(a, item, 5, -1).status(), "only modified items");
		a.getInventory().setItem(1, new ItemStack(item, 20));
		eq(Status.INVALID_AMOUNT, ShopService.sell(a, item, 0, -1).status(), "amount 0");
		long unit = ShopService.unitSellPrice(id, item).orElseThrow();
		SellResult changed = ShopService.sell(a, item, 5, unit + 1);
		eq(Status.PRICE_CHANGED, changed.status(), "price fell since display");
		eq(25, count(a, item), "nothing removed");
		eq(0, EconomyService.balance(id), "nothing credited");

		EconomyService.set(id, Money.MAX - 1);
		eq(Status.LIMIT_EXCEEDED, ShopService.sell(a, item, 20, -1).status(), "account limit");
		eq(25, count(a, item), "items stay at account limit");
		eq(Money.MAX - 1, EconomyService.balance(id), "balance unchanged");

		Item unsellable = null;
		for (Item i : BasePrices.shopBuyPrices().keySet()) {
			if (PriceGuard.effectiveSellPrice(id, i).isEmpty()) {
				unsellable = i;
				break;
			}
		}
		if (unsellable != null) {
			a.getInventory().setItem(2, new ItemStack(unsellable, 1));
			eq(Status.NOT_SELLABLE, ShopService.sell(a, unsellable, 1, -1).status(), "shop item without sell price");
			eq(1, count(a, unsellable), "stays in inventory");
		}
	}

	/**
	 * Items from the creative inventory would print money, so nothing is removed, paid or reported; survival mode
	 * works again.
	 */
	private static void sellBlockedInCreative(TestContext ctx) {
		ctx.preserveTreasury();
		FakePlayer a = ctx.player("ShopCreative");
		UUID id = a.getUUID();
		Item item = tradeItem();
		if (a.gameMode() != GameType.SURVIVAL) a.setGameMode(GameType.SURVIVAL);
		ctx.cleanup(() -> a.setGameMode(GameType.SURVIVAL));
		isFalse(new ShopConfig().allowCreativeSell, "default: creative selling off");
		if (ShopConfig.get().allowCreativeSell) {
			ctx.detail("allowCreativeSell=true in shop_settings.json: block deliberately off, rest skipped");
			return;
		}
		long[] fired = {0};
		Hooks.Registration reg = Hooks.onItemsSold((p, cents, n) -> {
			if (p.getUUID().equals(id)) fired[0] += n;
		});
		ctx.cleanup(reg::remove);
		EconomyService.set(id, 1_000);
		a.getInventory().setItem(0, new ItemStack(item, 64));
		for (GameType mode : new GameType[] {GameType.CREATIVE, GameType.SPECTATOR}) {
			a.setGameMode(mode);
			isTrue(ShopService.sellBlocked(a), "blocked: " + mode.getName());
			SellResult r = ShopService.sell(a, item, 64, -1);
			eq(Status.BLOCKED, r.status(), "sale rejected: " + mode.getName());
			eq(0, r.sold(), "nothing sold: " + mode.getName());
			eq(64, count(a, item), "items stay: " + mode.getName());
			eq(1_000L, EconomyService.balance(id), "no money: " + mode.getName());
		}
		eq(0L, fired[0], "no onItemsSold");
		a.setGameMode(GameType.SURVIVAL);
		isFalse(ShopService.sellBlocked(a), "survival: not blocked");
		SellResult ok = ShopService.sell(a, item, 64, -1);
		eq(Status.OK, ok.status(), "survival: sell ok");
		eq(64, ok.sold(), "64 sold");
		eq(64L, fired[0], "onItemsSold in survival");
	}

	private static void favorites(TestContext ctx) {
		UUID a = ctx.player("ShopFavA").getUUID();
		UUID b = ctx.player("ShopFavB").getUUID();
		Item stone = net.minecraft.world.item.Items.STONE;
		Item log = net.minecraft.world.item.Items.OAK_LOG;
		Item bread = net.minecraft.world.item.Items.BREAD;
		eq(ShopPlayerData.Toggle.ADDED, ShopPlayerData.toggleFavorite(a, stone, 45), "stone added");
		eq(ShopPlayerData.Toggle.ADDED, ShopPlayerData.toggleFavorite(a, log, 45), "log added");
		eq(ShopPlayerData.Toggle.ADDED, ShopPlayerData.toggleFavorite(a, bread, 45), "bread added");
		eq(ShopPlayerData.Toggle.REMOVED, ShopPlayerData.toggleFavorite(a, log, 45), "log removed");
		eq(List.of(stone, bread), ShopPlayerData.favorites(a), "order of adding");
		isTrue(ShopPlayerData.isFavorite(a, bread), "bread is favorite");
		isFalse(ShopPlayerData.isFavorite(a, log), "log no longer");
		isTrue(ShopPlayerData.favorites(b).isEmpty(), "separate per player");

		PlayerDataService.PlayerData data = PlayerDataService.get(a);
		JsonElement raw = data.blobs.get(ShopPlayerData.FAVORITES_KEY);
		Assert.notNullJson(raw);
		String text = DataStore.gson().toJson(raw);
		isTrue(text.contains("minecraft:stone") && text.contains("minecraft:bread"), "IDs stored in blob");
		data.blobs.put(ShopPlayerData.FAVORITES_KEY, JsonParser.parseString(text));
		PlayerDataService.markDirty();
		eq(List.of(stone, bread), ShopPlayerData.favorites(a), "equal after round trip");

		ShopPlayerData.Favorites f = new ShopPlayerData.Favorites();
		f.items.add("minecraft:does_not_exist");
		f.items.add("minecraft:stone");
		PlayerDataService.setBlob(a, ShopPlayerData.FAVORITES_KEY, f);
		eq(List.of(stone), ShopPlayerData.favorites(a), "unknown ID skipped");
		eq(List.of("minecraft:does_not_exist", "minecraft:stone"), ShopPlayerData.rawFavorites(a), "unknown ID stays stored");

		eq(ShopPlayerData.Toggle.LIMIT, ShopPlayerData.toggleFavorite(a, bread, 2), "limit reached");
		eq(ShopPlayerData.Toggle.REMOVED, ShopPlayerData.toggleFavorite(a, stone, 2), "removing always works");
		eq(ShopPlayerData.Toggle.ADDED, ShopPlayerData.toggleFavorite(a, bread, 2), "room again afterwards");
		ShopPlayerData.toggleFavorite(a, bread, 2);
		isTrue(ShopPlayerData.favorites(a).isEmpty(), "empty");
	}

	private static void recent(TestContext ctx) {
		UUID a = ctx.player("ShopRecentA").getUUID();
		List<Item> items = new ArrayList<>(BasePrices.shopBuyPrices().keySet());
		isTrue(items.size() >= 12, "enough shop items");
		for (int i = 0; i < 12; i++) ShopPlayerData.recordPurchase(a, items.get(i), i + 1);
		List<ShopPlayerData.RecentPurchase> r = ShopPlayerData.recent(a);
		eq(ShopPlayerData.RECENT_MAX, r.size(), "at most 9");
		eq(items.get(11), r.get(0).item(), "newest first");
		eq(12, r.get(0).amount(), "amount remembered");
		eq(items.get(3), r.get(8).item(), "oldest left");
		ShopPlayerData.recordPurchase(a, items.get(5), 64);
		r = ShopPlayerData.recent(a);
		eq(items.get(5), r.get(0).item(), "rebuy moves to front");
		eq(64, r.get(0).amount(), "new amount");
		eq(ShopPlayerData.RECENT_MAX, r.size(), "no duplicates");
		eq(1L, r.stream().filter(x -> x.item() == items.get(5)).count(), "contained only once");
		ShopPlayerData.recordPurchase(a, items.get(0), 0);
		eq(1, ShopPlayerData.recent(a).get(0).amount(), "amount at least 1");
	}

	private static void search() {
		eq("pumpkin seeds", ShopSearch.normalize("  Pumpkin_Seeds! "), "normalization");
		isTrue(ShopSearch.search("x").isEmpty(), "too short");
		isTrue(ShopSearch.search("   ").isEmpty(), "empty");
		isTrue(ShopSearch.search("§§!!").isEmpty(), "only special characters");
		isTrue(ShopSearch.search("zzqqxxyy").isEmpty(), "nonsense");
		isFalse(ShopSearch.isValidQuery("a".repeat(ShopSearch.MAX_LENGTH + 1)), "too long");
		for (Item i : ShopSearch.search("stone")) isTrue(BasePrices.shopBuyPrice(i).isPresent(), "only shop items");
		Item stone = net.minecraft.world.item.Items.STONE;
		Item log = net.minecraft.world.item.Items.OAK_LOG;
		if (BasePrices.shopBuyPrice(stone).isPresent()) {
			eq(stone, ShopSearch.search("Stone").get(0), "exact English name first");
			isTrue(ShopSearch.search("STONE").contains(stone), "English, case-insensitive");
		}
		if (BasePrices.shopBuyPrice(log).isPresent()) {
			isTrue(ShopSearch.search("oak").contains(log), "partial word English");
			isTrue(ShopSearch.search("oak log").contains(log), "several words");
			isTrue(ShopSearch.search("oak_log").contains(log), "Item-ID");
			isFalse(ShopSearch.search("oak sand").contains(log), "all words must match");
		}
		Item seeds = net.minecraft.world.item.Items.PUMPKIN_SEEDS;
		if (BasePrices.shopBuyPrice(seeds).isPresent()) {
			isTrue(ShopSearch.search("pumpkin").contains(seeds), "English");
		}
		eq("Stone", ShopSearch.englishName(stone), "English name");
	}

	private static void amountsAndTrend() {
		eq(5, TradeDialog.maxBuyable(576, 2304, 5), "maximum limited by money");
		eq(64, TradeDialog.maxBuyable(576, 64, 1000), "maximum limited by space");
		eq(576, TradeDialog.maxBuyable(576, 2304, 100_000), "maximum limited by purchase limit");
		eq(0, TradeDialog.maxBuyable(576, 0, 100), "no space");
		eq(0, TradeDialog.maxBuyable(576, 10, -3), "never negative");
		eq(1, TradeDialog.clampSelection(0, 576), "at least 1");
		eq(576, TradeDialog.clampSelection(10_000, 576), "at most limit");
		eq(1, TradeDialog.clampSelection(5, 0), "limit 0 gives 1");
		eq(1, TradeDialog.clampSelection(Long.MIN_VALUE, 576), "no underflow");
		eq(576, TradeDialog.clampSelection(Long.MAX_VALUE, 576), "no overflow");
		eq(128, TradeDialog.stackAmount(2, 64), "2 stacks of 64");
		eq(32, TradeDialog.stackAmount(2, 16), "2 stacks of 16");
		eq(36, TradeDialog.stackAmount(36, 1), "36 stacks of 1");

		Item d = net.minecraft.world.item.Items.DIAMOND;
		long now = 1_000_000;
		eq(ShopLore.Trend.STABLE, ShopLore.trend(List.of(), now), "stable without modifier");
		eq(ShopLore.Trend.RISING, ShopLore.trend(List.of(new PriceModifiers.Active("n", d, "Hype", 1.5, 2.0, now + 60_000)), now), "fade in upward");
		eq(ShopLore.Trend.FALLING, ShopLore.trend(List.of(new PriceModifiers.Active("n", d, "Crash", 0.8, 0.5, 0)), now), "fade in downward");
		eq(ShopLore.Trend.CHANGED, ShopLore.trend(List.of(new PriceModifiers.Active("n", d, "Crash", 0.5, 0.5, now + 60_000)), now), "holds the value");
		eq(ShopLore.Trend.RISING, ShopLore.trend(List.of(new PriceModifiers.Active("n", d, "Crash", 0.7, 0.5, now - 1)), now), "fade out after crash: rises");
		eq(ShopLore.Trend.FALLING, ShopLore.trend(List.of(new PriceModifiers.Active("n", d, "Hype", 2.5, 3.0, now - 1)), now), "fade out after hype: falls");
		eq(ShopLore.Trend.MIXED, ShopLore.trend(List.of(new PriceModifiers.Active("a", d, "x", 1.5, 2.0, 0),
			new PriceModifiers.Active("b", d, "y", 0.9, 0.5, 0)), now), "mixed");
		eq(now + 60_000, ShopLore.nextChange(List.of(new PriceModifiers.Active("n", d, "x", 0.5, 0.5, now + 60_000),
			new PriceModifiers.Active("m", d, "y", 0.5, 0.5, now + 120_000)), now), "next end");
	}

	/** Own recipe search, independent of {@link RecipeGuard#collect}. */
	private record TestRecipe(String id, Item output, int count, boolean crafting, List<List<Item>> slots) {}

	/**
	 * Ingredient candidates are items from {@code universe} passing {@link Ingredient#test}, without {@code
	 * Ingredient#items}, unlike {@link RecipeGuard}.
	 */
	private static List<TestRecipe> testRecipes(TestContext ctx, Set<Item> universe) {
		RegistryAccess access = ctx.server().registryAccess();
		List<Item> sortedUniverse = new ArrayList<>(universe);
		sortedUniverse.sort(java.util.Comparator.comparing(Items::id));
		Map<Item, ItemStack> probes = new java.util.HashMap<>();
		for (Item item : sortedUniverse) probes.put(item, new ItemStack(item));
		Map<Ingredient, List<Item>> cache = new java.util.IdentityHashMap<>();
		List<TestRecipe> list = new ArrayList<>();
		for (RecipeHolder<?> holder : ctx.server().getRecipeManager().getRecipes()) {
			Recipe<?> recipe = holder.value();
			ItemStack out;
			if (recipe instanceof ShapedRecipe r) out = Mc.assemble(r, CraftingInput.EMPTY, access);
			else if (recipe instanceof ShapelessRecipe r) out = Mc.assemble(r, CraftingInput.EMPTY, access);
			else if (recipe instanceof SingleItemRecipe r) out = Mc.assemble(r, new SingleRecipeInput(ItemStack.EMPTY), access);
			else continue;
			if (out.isEmpty()) continue;
			List<Ingredient> ingredients = recipe.placementInfo().ingredients();
			if (ingredients.isEmpty()) continue;
			List<List<Item>> slots = new ArrayList<>();
			for (Ingredient ing : ingredients) {
				slots.add(cache.computeIfAbsent(ing, k -> {
					List<Item> m = new ArrayList<>();
					for (Item item : sortedUniverse) {
						if (k.test(probes.get(item))) m.add(item);
					}
					return m;
				}));
			}
			list.add(new TestRecipe(holder.id().identifier().toString(), out.getItem(), out.getCount(), !(recipe instanceof SingleItemRecipe), slots));
		}
		return list;
	}

	/** Only these can have production costs. */
	private static Set<Item> recipeUniverse(TestContext ctx) {
		RegistryAccess access = ctx.server().registryAccess();
		Set<Item> universe = new HashSet<>(BasePrices.shopBuyPrices().keySet());
		for (RecipeHolder<?> holder : ctx.server().getRecipeManager().getRecipes()) {
			Recipe<?> recipe = holder.value();
			ItemStack out;
			if (recipe instanceof ShapedRecipe r) out = Mc.assemble(r, CraftingInput.EMPTY, access);
			else if (recipe instanceof ShapelessRecipe r) out = Mc.assemble(r, CraftingInput.EMPTY, access);
			else if (recipe instanceof SingleItemRecipe r) out = Mc.assemble(r, new SingleRecipeInput(ItemStack.EMPTY), access);
			else continue;
			if (!out.isEmpty()) universe.add(out.getItem());
		}
		return universe;
	}

	private static long remainderPayout(Item item, java.util.function.ToLongFunction<Item> payout) {
		ItemStack rem = Mc.craftingRemainder(item);
		if (rem == null || rem.isEmpty()) return 0;
		return payout.applyAsLong(rem.getItem()) * rem.getCount();
	}

	/** Own Bellman-Ford over all recipes, without base ingredient tracking. */
	private static Map<Item, Double> testCosts(List<TestRecipe> recipes, Map<Item, Long> prices, java.util.function.ToLongFunction<Item> payout) {
		Map<Item, Double> cost = new java.util.HashMap<>();
		prices.forEach((k, v) -> cost.put(k, (double) v));
		for (int pass = 0; pass < 100; pass++) {
			boolean changed = false;
			for (TestRecipe r : recipes) {
				Double c = testNetCost(r, cost, payout);
				if (c == null) continue;
				double unit = c / r.count();
				Double old = cost.get(r.output());
				if (old == null || unit < old - 1e-6) {
					cost.put(r.output(), unit);
					changed = true;
				}
			}
			if (!changed) break;
		}
		return cost;
	}

	/** Null if it cannot be made. */
	private static Double testNetCost(TestRecipe r, Map<Item, Double> cost, java.util.function.ToLongFunction<Item> payout) {
		double sum = 0;
		for (List<Item> slot : r.slots()) {
			Double best = null;
			for (Item item : slot) {
				Double c = cost.get(item);
				if (c == null) continue;
				double net = c - (r.crafting() ? remainderPayout(item, payout) : 0);
				if (best == null || net < best) best = net;
			}
			if (best == null) return null;
			sum += best;
		}
		return sum;
	}

	/**
	 * Worst-case system payout per item over time: shop items at the pivot, others at base x modifier x sell
	 * bonuses. 0 means not sellable.
	 */
	private static long worstPayout(Item item, double sellFactor, double modifierFactor) {
		OptionalLong base = BasePrices.sellPrice(item);
		if (base.isEmpty()) return 0;
		long pivot = PriceGuard.pivotPrice(item);
		if (pivot >= 0) return pivot;
		return PriceGuard.computeSell(base.getAsLong(), modifierFactor, sellFactor, -1);
	}

	/**
	 * Over time, transitively: ingredients bought at their low, results sold with all bonuses. Value >= cost is a
	 * failure. Uses its own recipe search and cost math, independent of {@link RecipeGuard}. Fixed cases:
	 * cobblestone to stone to smooth stone, and to stone bricks to cracked stone bricks.
	 */
	private static void recipes(TestContext ctx) {
		ShopConfig cfg = ShopConfig.get();
		double sellFactor = cfg.recipeGuardSellFactor;
		double modFactor = cfg.recipeGuardModifierFactor;
		java.util.function.ToLongFunction<Item> payout = item -> worstPayout(item, sellFactor, modFactor);
		Map<Item, Long> shopPrices = BasePrices.shopBuyPrices();
		Map<Item, Long> prices = new java.util.HashMap<>();
		for (Item item : shopPrices.keySet()) prices.put(item, Math.max(Math.max(1, PriceGuard.pivotPrice(item) + 1), RecipeGuard.staticFloor(item)));
		List<TestRecipe> all = testRecipes(ctx, recipeUniverse(ctx));
		Map<Item, Double> cost = testCosts(all, prices, payout);
		int checked = 0;
		double worst = 0;
		String worstId = "-";
		List<String> violations = new ArrayList<>();
		for (TestRecipe r : all) {
			Double net = testNetCost(r, cost, payout);
			if (net == null) continue;
			long value = payout.applyAsLong(r.output()) * r.count();
			if (value <= 0 && net > 0) continue;
			checked++;
			double ratio = net > 0 ? value / net : Double.POSITIVE_INFINITY;
			if (ratio > worst) {
				worst = ratio;
				worstId = r.id();
			}
			if (value + 1e-6 >= net) violations.add(r.id() + " (value " + value + " >= cost " + String.format(java.util.Locale.ROOT, "%.2f", net) + ")");
		}
		isTrue(checked > 0, "recipes from shop items found");
		isTrue(violations.isEmpty(), violations.size() + " recipes/chains with worst-case profit (sell x" + sellFactor + ", modifier x"
			+ modFactor + "): " + String.join(", ", violations.subList(0, Math.min(5, violations.size()))));

		// Fixed cases: smelting twice, and stonecutter plus furnace, starting from cobblestone
		Item cobble = net.minecraft.world.item.Items.COBBLESTONE;
		if (prices.containsKey(cobble)) {
			long cobblePrice = prices.get(cobble);
			for (Item end : List.of(net.minecraft.world.item.Items.SMOOTH_STONE, net.minecraft.world.item.Items.CRACKED_STONE_BRICKS)) {
				long v = payout.applyAsLong(end);
				if (v > 0) check(cobblePrice > v, "chain cobblestone (" + cobblePrice + ") → " + Items.id(end) + " (" + v + ") loses");
			}
		}

		UUID viewer = new UUID(0, 0);
		List<String> active = new ArrayList<>();
		List<Item> floored = new ArrayList<>(RecipeGuard.floors().keySet());
		floored.sort(java.util.Comparator.comparing(Items::id));
		for (Item item : floored) {
			long floor = RecipeGuard.floor(item);
			check(floor > PriceGuard.pivotPrice(item) + 1, "floor above pivot + 1: " + Items.id(item));
			check(ShopService.unitBuyPrice(viewer, item).orElseThrow() >= floor, "shop charges the floor: " + Items.id(item));
			if (floor > shopPrices.get(item)) active.add(Items.id(item).replace("minecraft:", "") + " " + Money.format(floor) + " (base " + Money.format(shopPrices.get(item)) + ")");
		}
		isTrue(RecipeGuard.lastCheckCount() > 0, "recipe guard knows recipes from shop items");
		ctx.detail(checked + " recipes/chains checked in the worst case (recipe guard: " + RecipeGuard.lastCheckCount()
			+ " craftable recipes; ingredients from pivot+1, result x" + sellFactor + " x modifier x" + modFactor
			+ ", shop results up to pivot); tightest " + worstId + " (value/cost " + String.format(java.util.Locale.ROOT, "%.3f", worst) + "); "
			+ floored.size() + " floors active"
			+ (active.isEmpty() ? ", none above base price"
				: "; above base price (adjust shop.json or prices.json): " + String.join(", ", active)));
	}

	/**
	 * A running hype on a non-shop result (news x3, admin pricemod x20): buying at the lowest price, crafting and
	 * selling with all bonuses must never pay at the real shop and /sell prices. Afterwards floors return to their
	 * earlier state.
	 */
	private static void recipesLiveHype(TestContext ctx) {
		FakePlayer a = ctx.player("ShopRecipeHype");
		UUID id = a.getUUID();
		double sellFactor = ShopConfig.get().recipeGuardSellFactor;
		Map<Item, Long> before = new java.util.HashMap<>(RecipeGuard.floors());
		List<Hooks.Registration> regs = new ArrayList<>();
		ctx.cleanup(() -> {
			regs.forEach(Hooks.Registration::remove);
			PriceModifiers.removeSource(SRC);
		});
		Set<Item> shop = BasePrices.shopBuyPrices().keySet();
		List<TestRecipe> single = new ArrayList<>();
		for (TestRecipe r : testRecipes(ctx, shop)) {
			if (shop.contains(r.output()) || BasePrices.sellPrice(r.output()).isEmpty()) continue;
			if (r.slots().stream().anyMatch(List::isEmpty)) continue;
			single.add(r);
		}
		isTrue(!single.isEmpty(), "single-step recipes from shop items with non-shop result found");
		List<Item> outputs = new ArrayList<>();
		for (Item preferred : List.of(net.minecraft.world.item.Items.QUARTZ_BRICKS, net.minecraft.world.item.Items.BLAZE_POWDER)) {
			if (single.stream().anyMatch(r -> r.output() == preferred)) outputs.add(preferred);
		}
		single.stream().map(TestRecipe::output).distinct().sorted(java.util.Comparator.comparing(Items::id))
			.filter(o -> !outputs.contains(o)).limit(10).forEach(outputs::add);
		// Brings this player's sell bonus to exactly recipeGuardSellFactor, counting real market days and ranks
		Map<Item, Double> boost = new java.util.HashMap<>();
		for (Item o : outputs) boost.put(o, sellFactor / Hooks.sellMultiplier(id, o));
		regs.add(Hooks.addShopDiscount(SRC + ".flash", "Test-Flash-Sale", i -> 0.6));
		regs.add(Hooks.addSellMultiplier(SRC + ".sell", "Test-Market-Day", (p, i) -> p.equals(id) ? boost.getOrDefault(i, 1.0) : 1.0));
		for (int i = 0; i < outputs.size(); i++) {
			PriceModifiers.set(SRC, outputs.get(i), i == 0 ? PriceModifiers.MAX_FACTOR : 3.0, 0, 60_000, 0, i == 0 ? "Test-Admin" : "Test-Hype");
		}
		int checked = 0;
		int raised = 0;
		for (TestRecipe r : single) {
			if (!outputs.contains(r.output())) continue;
			long cost = 0;
			for (List<Item> slot : r.slots()) {
				long best = Long.MAX_VALUE;
				for (Item item : slot) {
					long rem = r.crafting() ? remainderPayout(item, x -> PriceGuard.effectiveSellPrice(id, x).orElse(0)) : 0;
					best = Math.min(best, ShopService.unitBuyPrice(id, item).orElseThrow() - rem);
				}
				cost += best;
			}
			long value = PriceGuard.effectiveSellPrice(id, r.output()).orElse(0) * r.count();
			check(value < cost, "hype on " + Items.id(r.output()) + ": " + r.id() + " costs " + cost + ", yields " + value);
			for (List<Item> slot : r.slots()) {
				for (Item item : slot) {
					if (RecipeGuard.floor(item) > RecipeGuard.staticFloor(item)) raised++;
				}
			}
			checked++;
		}
		isTrue(checked > 0, "hyped recipes checked");
		isTrue(raised > 0, "hype raises ingredient prices live");
		PriceModifiers.removeSource(SRC);
		eq(before, RecipeGuard.floors(), "after the hype the earlier floors return");
		ctx.detail(checked + " recipes with hype x3/x20 on " + outputs.size() + " results checked (among them "
			+ outputs.stream().limit(3).map(o -> Items.id(o).replace("minecraft:", "")).toList() + ")");
	}

	/** Pure math of the recipe guard with made-up recipes and prices. */
	private static void recipeGuardMath() {
		Item netherrack = net.minecraft.world.item.Items.NETHERRACK;
		Item netherBrick = net.minecraft.world.item.Items.NETHER_BRICK;
		Item cobble = net.minecraft.world.item.Items.COBBLESTONE;
		Item sand = net.minecraft.world.item.Items.SAND;
		Item glass = net.minecraft.world.item.Items.GLASS;
		Item oak = net.minecraft.world.item.Items.OAK_LOG;
		Item birch = net.minecraft.world.item.Items.BIRCH_LOG;
		Item planks = net.minecraft.world.item.Items.OAK_PLANKS;
		Item table = net.minecraft.world.item.Items.CRAFTING_TABLE;
		Item stone = net.minecraft.world.item.Items.STONE;
		Item smooth = net.minecraft.world.item.Items.SMOOTH_STONE;
		Item milk = net.minecraft.world.item.Items.MILK_BUCKET;
		Item cake = net.minecraft.world.item.Items.CAKE;
		Map<Item, Long> lowest = Map.of(netherrack, 10L, cobble, 31L, sand, 31L, oak, 181L, birch, 190L, stone, 49L, milk, 120L);
		Map<Item, Long> none = Map.of();

		// Netherrack (pivot + 1 = $0.10) to nether bricks at $0.16 gives a floor of $0.17
		List<RecipeGuard.Edge> single = List.of(edge("t:single", netherBrick, 1, false, List.of(netherrack)));
		eq(Map.of(netherrack, 17L), RecipeGuard.computeFloors(single, lowest, values(netherBrick, 16), none), "1 ingredient: value + 1");
		// A tie is already too much: no free turnover farming
		eq(Map.of(netherrack, 11L), RecipeGuard.computeFloors(single, lowest, values(netherBrick, 10), none), "tie gets raised");
		isTrue(RecipeGuard.computeFloors(single, lowest, values(netherBrick, 9), none).isEmpty(), "no floor without profit");
		// Both raised proportionally: 31 x 71/62 = 35.5, so 36, then 72 > 70
		List<RecipeGuard.Edge> two = List.of(edge("t:two", glass, 1, true, List.of(cobble), List.of(sand)));
		eq(Map.of(cobble, 36L, sand, 36L), RecipeGuard.computeFloors(two, lowest, values(glass, 70), none), "2 ingredients proportional");
		// The next cheapest alternative in the slot (#logs) is raised too
		List<RecipeGuard.Edge> alt = List.of(edge("t:alt", planks, 1, true, List.of(oak, birch)));
		eq(Map.of(oak, 201L, birch, 201L), RecipeGuard.computeFloors(alt, lowest, values(planks, 200), none), "all alternatives in slot");
		// Returned buckets count toward value: 100 + 50 >= 120 gives 151
		List<RecipeGuard.Edge> rem = List.of(edge("t:rem", cake, 1, true, List.of(milk)));
		eq(Map.of(milk, 151L), RecipeGuard.computeFloors(rem, lowest, values(cake, 100), Map.of(milk, 50L)), "return counts toward value");

		// Chain: cobblestone 31, stone 33, smooth stone 50. Checked alone, 34 would be safe for cobblestone, but
		// through stone (cost 31, below its shop price 49) smooth stone flips, so cobblestone and stone become 51.
		List<RecipeGuard.Edge> chain = List.of(edge("t:cobble_stone", stone, 1, false, List.of(cobble)), edge("t:stone_smooth", smooth, 1, false, List.of(stone)));
		java.util.function.ToLongFunction<Item> chainValues = i -> i == stone ? 33 : i == smooth ? 50 : 0;
		Map<Item, Long> chainFloors = RecipeGuard.computeFloors(chain, lowest, chainValues, none);
		eq(Map.of(cobble, 51L, stone, 51L), chainFloors, "chain through two furnaces");
		// Intermediate neither sellable nor in the shop still counts
		List<RecipeGuard.Edge> hidden = List.of(edge("t:a", planks, 1, false, List.of(cobble)), edge("t:b", table, 1, false, List.of(planks)));
		eq(Map.of(cobble, 41L), RecipeGuard.computeFloors(hidden, lowest, values(table, 40), none), "chain through non-shop intermediate");
		// Amounts: 1 log to 4 planks, 4 planks to a crafting table worth 200 >= 181, so log becomes 201
		List<RecipeGuard.Edge> split = List.of(edge("t:planks", planks, 4, true, List.of(oak)),
			edge("t:table", table, 1, true, List.of(planks), List.of(planks), List.of(planks), List.of(planks)));
		eq(Map.of(oak, 201L), RecipeGuard.computeFloors(split, lowest, values(table, 200), none), "amounts through the chain");
		// A lossless cycle (ingot and block) changes nothing
		List<RecipeGuard.Edge> loop = List.of(edge("t:block", stone, 1, true, List.of(cobble)), edge("t:unblock", cobble, 1, true, List.of(stone)));
		isTrue(RecipeGuard.computeFloors(loop, lowest, i -> 0, none).isEmpty(), "cycle without sell value");

		Map<Item, Long> chainPrices = new java.util.HashMap<>(lowest);
		chainPrices.putAll(chainFloors);
		isTrue(RecipeGuard.violations(chain, chainPrices, chainValues, none).isEmpty(), "chain without profit after raising");
		isFalse(RecipeGuard.violations(chain, lowest, chainValues, none).isEmpty(), "chain with profit before raising");

		eq(17L, RecipeGuard.raiseBy(10, 1.7), "rounded up");
		eq(11L, RecipeGuard.raiseBy(10, 1.0 + 1e-15), "at least +1 cent");
		eq(Money.MAX, RecipeGuard.raiseBy(Long.MAX_VALUE / 2, 4), "overflow saturated");
		eq(10L, RecipeGuard.lowestGuardPrice(15), "pivot + 1 at $0.15");
		eq(1L, RecipeGuard.lowestGuardPrice(1), "at least 1 cent");

		long end = System.currentTimeMillis() + 60_000;
		eq(1.0, RecipeGuard.potential(List.of()), "1.0 without modifier");
		eq(1.0, RecipeGuard.potential(List.of(new PriceModifiers.Active("n", stone, "c", 0.6, 0.5, end))), "crash counts as 1.0");
		eq(3.0, RecipeGuard.potential(List.of(new PriceModifiers.Active("n", stone, "h", 1.2, 3.0, end))), "fade in: target counts");
		eq(3.0, RecipeGuard.potential(List.of(new PriceModifiers.Active("n", stone, "h", 1.5, 3.0, 1))), "fade out: target counts");
		eq(6.0, RecipeGuard.potential(List.of(new PriceModifiers.Active("n", stone, "h", 3.0, 3.0, end),
			new PriceModifiers.Active("a", stone, "h", 2.0, 2.0, end))), "sources multiply");
		eq(PriceModifiers.MAX_FACTOR, RecipeGuard.potential(List.of(new PriceModifiers.Active("n", stone, "h", 10.0, 10.0, end),
			new PriceModifiers.Active("a", stone, "h", 5.0, 5.0, end))), "at most MAX_FACTOR");

		eq(64, TradeDialog.actionAmount(576, 64), "selected 9 stacks, displayed 64: 64");
		eq(5, TradeDialog.actionAmount(5, 64), "selection below displayed");
		eq(0, TradeDialog.actionAmount(10, 0), "nothing displayed: nothing");
	}

	@SafeVarargs
	private static RecipeGuard.Edge edge(String id, Item output, int count, boolean crafting, List<Item>... slots) {
		return new RecipeGuard.Edge(id, output, count, List.of(slots), crafting);
	}

	private static java.util.function.ToLongFunction<Item> values(Item item, long value) {
		return i -> i == item ? value : 0;
	}

	private static void configValidation() {
		ShopConfig ok = new ShopConfig();
		ShopConfig.validate(ok);
		ShopConfig bad = new ShopConfig();
		bad.treasurySharePercent = 150;
		dev.smpeconomy.core.test.Assert.assertThrows(IllegalArgumentException.class, () -> ShopConfig.validate(bad), "share > 100 %");
		ShopConfig bad2 = new ShopConfig();
		bad2.dailyDealMinPercent = 30;
		bad2.dailyDealMaxPercent = 20;
		dev.smpeconomy.core.test.Assert.assertThrows(IllegalArgumentException.class, () -> ShopConfig.validate(bad2), "min > max");
		ShopConfig bad3 = new ShopConfig();
		bad3.dailyDealMaxPercent = 60;
		dev.smpeconomy.core.test.Assert.assertThrows(IllegalArgumentException.class, () -> ShopConfig.validate(bad3), "discount > 40 %");
		ShopConfig bad4 = new ShopConfig();
		bad4.recipeGuardSellFactor = 0.5;
		dev.smpeconomy.core.test.Assert.assertThrows(IllegalArgumentException.class, () -> ShopConfig.validate(bad4), "recipe factor < 1");
		ShopConfig bad5 = new ShopConfig();
		bad5.recipeGuardSellFactor = Double.NaN;
		dev.smpeconomy.core.test.Assert.assertThrows(IllegalArgumentException.class, () -> ShopConfig.validate(bad5), "recipe factor NaN");
		ShopConfig bad6 = new ShopConfig();
		bad6.recipeGuardModifierFactor = 0.5;
		dev.smpeconomy.core.test.Assert.assertThrows(IllegalArgumentException.class, () -> ShopConfig.validate(bad6), "modifier factor < 1");
		ShopConfig bad7 = new ShopConfig();
		bad7.recipeGuardModifierFactor = PriceModifiers.MAX_FACTOR + 1;
		dev.smpeconomy.core.test.Assert.assertThrows(IllegalArgumentException.class, () -> ShopConfig.validate(bad7), "modifier factor > MAX_FACTOR");
		ShopConfig bad8 = new ShopConfig();
		bad8.recipeGuardModifierFactor = Double.NaN;
		dev.smpeconomy.core.test.Assert.assertThrows(IllegalArgumentException.class, () -> ShopConfig.validate(bad8), "modifier factor NaN");
		ShopConfig hype = new ShopConfig();
		hype.recipeGuardModifierFactor = 3.0;
		ShopConfig.validate(hype);
		ShopConfig nulls = new ShopConfig();
		nulls.dailyDealExcluded = null;
		ShopConfig.validate(nulls);
		isTrue(nulls.dailyDealExcluded != null, "null list repaired");
		ShopConfig live = ShopConfig.get();
		isTrue(live.treasurySharePercent >= 0 && live.treasurySharePercent <= 100, "live config valid");
	}

	/** Bundled shop.json: English category IDs and names; every command alias points at a default category. */
	private static void categoryNamesEnglish() {
		BasePrices.ShopConfig defaults = BasePrices.SHOP.defaults();
		java.util.Set<String> ids = new java.util.HashSet<>();
		for (BasePrices.CategoryConfig c : defaults.categories) {
			check(c.id != null && c.id.matches("[a-z_]+"), "category ID only a-z and _: " + c.id);
			check(c.name != null && c.name.matches("[A-Za-z &]+"), "category name English: " + c.name);
			ids.add(c.id);
		}
		for (Map.Entry<String, String> e : ShopCommand.CATEGORY_ALIASES.entrySet()) {
			check(ids.contains(e.getValue()), "Alias " + e.getKey() + " → " + e.getValue());
			check(!ids.contains(e.getKey()), "Alias " + e.getKey() + " is not its own category ID");
		}
	}

	private static final class Assert {
		static void notNullJson(JsonElement e) {
			if (e == null || e.isJsonNull()) throw new AssertionError("favorites blob missing");
		}
	}
}
