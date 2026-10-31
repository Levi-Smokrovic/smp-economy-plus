package dev.smpeconomy.features.market;

import static dev.smpeconomy.core.test.Assert.assertThrows;
import static dev.smpeconomy.core.test.Assert.check;
import static dev.smpeconomy.core.test.Assert.eq;
import static dev.smpeconomy.core.test.Assert.isFalse;
import static dev.smpeconomy.core.test.Assert.isTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.enchantment.Enchantments;
import dev.smpeconomy.core.BasePrices;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Hooks;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.MarketGoods;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.PriceGuard;
import dev.smpeconomy.core.PriceModifiers;
import dev.smpeconomy.core.Services;
import dev.smpeconomy.core.Treasury;
import dev.smpeconomy.core.test.SelfTests;
import dev.smpeconomy.core.test.TestContext;

/**
 * Selftests against a {@link FakeBridge}. market.json and the treasury are saved before each test and restored after; price
 * modifiers use their own source {@value #SOURCE}.
 */
final class MarketSelfTests {
	private static final String F = MarketFeature.ID;
	private static final String SOURCE = "market.selftest";
	private static final double EPS = 1e-6;

	private MarketSelfTests() {}

	// 26.x checked against 1.21.11 (line diff of the Snapshot.write files): differences come only from 26.x vanilla data (costs capped
	// at stack size: book and quill 1 instead of 2, name tag at the wandering trader instead of the librarian, cake 3 emeralds, new
	// items) and the rebalance experiment (on 26.x only with its data pack)
	static final Map<String, String> TRADES_SNAPSHOT = Map.of(
		"1.21.11", "n=336 h=d3ed0f32",
		"26.1.2", "n=339 h=e998bd47",
		"26.2", "n=340 h=70aa628b",
		"26.3", "n=343 h=47262426");

	private static void tradesSnapshot(TestContext ctx) {
		if (ctx.overworld().enabledFeatures().contains(net.minecraft.world.flag.FeatureFlags.TRADE_REBALANCE)) {
			ctx.detail("skipped: world has trade_rebalance");
			return;
		}
		List<String> lines = new ArrayList<>();
		for (ValueEngine.RawRecipe r : ValueEngine.extractTrades(ctx.overworld())) {
			StringBuilder b = new StringBuilder(Items.id(r.out()) + "x" + r.count() + "<");
			for (List<Item> slot : r.ingredients()) {
				b.append('[');
				for (Item i : slot) b.append(Items.id(i)).append(' ');
				b.append(']');
			}
			lines.add(b.toString());
		}
		String actual = dev.smpeconomy.core.test.Snapshot.of(lines);
		dev.smpeconomy.core.test.Snapshot.write("market-trades", lines);
		dev.smpeconomy.core.test.Snapshot.check("market: trades", TRADES_SNAPSHOT, actual);
		ctx.detail(actual);
	}

	static void register() {
		SelfTests.register(F, "trade-snapshot", MarketSelfTests::tradesSnapshot);
		SelfTests.register(F, "value-sell-price", MarketSelfTests::valueFromPrice);
		SelfTests.register(F, "value-recipe", MarketSelfTests::valueFromRecipe);
		SelfTests.register(F, "value-recipe-model", ctx -> recipeModel());
		SelfTests.register(F, "value-stack", MarketSelfTests::stackValues);
		SelfTests.register(F, "guard-craft-cap", MarketSelfTests::craftCapGuard);
		SelfTests.register(F, "guard-crash-time", MarketSelfTests::crashTimeArbitrage);
		SelfTests.register(F, "guard-conversion", MarketSelfTests::conversionGuard);
		SelfTests.register(F, "guard-villager", MarketSelfTests::villagerGuard);
		SelfTests.register(F, "guard-decision", ctx -> decisions());
		SelfTests.register(F, "trader-price", ctx -> offerPrices());
		SelfTests.register(F, "drift-up-down-decay", MarketSelfTests::drift);
		SelfTests.register(F, "drift-persistence", ctx -> persistence());
		SelfTests.register(F, "npc-buy-pays-seller-minus-tax", MarketSelfTests::npcBuyPaysSeller);
		SelfTests.register(F, "treasury-cap", MarketSelfTests::treasuryCap);
		SelfTests.register(F, "buyer-modeled-values-only", MarketSelfTests::buyerModeledValuesOnly);
		SelfTests.register(F, "buyer-budget-per-player", MarketSelfTests::sellerBudget);
		SelfTests.register(F, "npc-buy-recheck", MarketSelfTests::recheckOnExecute);
		SelfTests.register(F, "trader-rotation", MarketSelfTests::sellerRotation);
		SelfTests.register(F, "floor-on-buy", MarketSelfTests::floorOnPurchase);
		SelfTests.register(F, "fever", MarketSelfTests::fever);
		SelfTests.register(F, "sync", MarketSelfTests::sweep);
		SelfTests.register(F, "service-and-config", MarketSelfTests::serviceAndConfig);
		SelfTests.register(F, "test-players-ignored", MarketSelfTests::liveIgnoresTestPlayers);
		SelfTests.register(F, "ah-sell-total-price", ctx -> sellHintAmounts());
		SelfTests.register(F, "bridge-connected", MarketSelfTests::bridgeConnected);
		SelfTests.register(F, "bargain-never-below-sell", MarketSelfTests::bargainNeverBelowSell);
		SelfTests.register(F, "bargain-flip-budget", MarketSelfTests::bargainFlipBudget);
		SelfTests.register(F, "normal-offers-floor", MarketSelfTests::normalOffersKeepFloor);
		SelfTests.register(F, "flip-allowed", MarketSelfTests::flipAllowed);
		SelfTests.register(F, "config-migration", ctx -> configMigration());
		SelfTests.register(F, "news-guard-market-goods", MarketSelfTests::shockGuard);
		SelfTests.register(F, "gui-bargain-off", MarketSelfTests::guiBargainLine);
	}

	private static void sandbox(TestContext ctx) {
		String json = DataStore.gson().toJson(MarketData.get());
		ctx.cleanup(() -> {
			MarketData restored = DataStore.gson().fromJson(json, MarketData.class);
			restored.fixNulls();
			MarketData.STORE.set(restored);
			Drift.bumpVersion();
			ValueEngine.invalidate();
		});
		ctx.cleanup(() -> PriceModifiers.removeSource(SOURCE));
		MarketData.get().drift.clear();
		MarketData.get().bargains.clear();
		Drift.bumpVersion();
		ValueEngine.invalidate();
	}

	private static void treasury(TestContext ctx, long balance) {
		ctx.preserveTreasury();
		EconomyService.set(Treasury.ID, balance);
	}

	private static FakeBridge fake(TestContext ctx) {
		FakeBridge b = new FakeBridge();
		b.maxNpcListings = 500;
		Hooks.Registration r = b.install(MarketFeature.EVENTS, MarketFeature.SALE_FLOOR);
		ctx.cleanup(r::remove);
		return b;
	}

	private static FakePlayer seller(TestContext ctx, String name) {
		FakePlayer p = ctx.player(name);
		EconomyService.ensureAccount(p.getUUID(), name);
		return p;
	}

	private static Holder<Enchantment> enchantment(TestContext ctx, ResourceKey<Enchantment> key) {
		return ctx.server().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key);
	}

	private static long expectedPriced(Item item) {
		long sell = BasePrices.sellPrice(item).orElseThrow();
		double v = sell * MarketConfig.get().marketFactor * PriceModifiers.get(item);
		long cents = (long) Math.floor(v + 1e-9);
		Long cap = ValueEngine.model().craftCaps().get(item);
		return cap == null ? cents : Math.min(cents, cap);
	}

	private static long cheapPrice(ItemStack stack) {
		long unit = ValueEngine.unitValue(stack).orElseThrow();
		return Math.max(1, (long) Math.floor(unit * 0.8)) * stack.getCount();
	}

	private static MarketData.BuyPlan plan(long listingId) {
		MarketData.BuyPlan p = MarketData.get().plans.get(listingId);
		check(p != null, "Plan for #" + listingId + " missing (rejected: "
			+ (MarketData.get().rejected.containsKey(listingId) ? MarketData.get().rejected.get(listingId).reason : "-") + ")");
		return p;
	}

	private static void valueFromPrice(TestContext ctx) {
		sandbox(ctx);
		Item diamond = net.minecraft.world.item.Items.DIAMOND;
		eq(expectedPriced(diamond), ValueEngine.value(diamond).orElse(-1), "diamond = sell price × 1.6 × modifier");
		eq(ValueEngine.value(diamond).orElse(-1), Services.marketValue(diamond).orElse(-2), "Services.marketValue = market value (single source)");
		long before = ValueEngine.value(diamond).orElseThrow();
		PriceModifiers.set(SOURCE, diamond, 0.5, 0, 60_000, 0, "Selftest");
		long crashed = ValueEngine.value(diamond).orElseThrow();
		eq(expectedPriced(diamond), crashed, "crash affects the market value");
		check(crashed < before, "crash lowers the market value (" + before + " → " + crashed + ")");
		PriceModifiers.removeSource(SOURCE);
		eq(before, ValueEngine.value(diamond).orElse(-1), "back to normal after the crash");
		Item none = net.minecraft.world.item.Items.COMMAND_BLOCK;
		isTrue(ValueEngine.value(none).isEmpty(), "command block has no market value");
		isTrue(Services.marketValue(none).isEmpty(), "... not through Services either");
		ctx.detail("Diamond " + Money.format(before) + ", Crash " + Money.format(crashed));
	}

	private static void valueFromRecipe(TestContext ctx) {
		sandbox(ctx);
		ValueEngine.Model m = ValueEngine.model();
		check(m.recipeCount() > 500, "recipes cached at startup (" + m.recipeCount() + ")");
		long estimated = m.sources().values().stream().filter(s -> s == ValueEngine.Source.RECIPE).count();
		check(estimated > 50, "many recipe estimates (" + estimated + ")");
		Item apple = net.minecraft.world.item.Items.GOLDEN_APPLE;
		if (BasePrices.sellPrice(apple).isPresent() || MarketConfig.get().valueOverrides.containsKey(Items.id(apple))) {
			ctx.detail("golden apple has a price/override, recipe part skipped");
			return;
		}
		eq(ValueEngine.Source.RECIPE, m.source(apple), "golden apple is estimated from its recipe");
		MarketConfig c = MarketConfig.get();
		long gold = ValueEngine.value(net.minecraft.world.item.Items.GOLD_INGOT).orElseThrow();
		long red = ValueEngine.value(net.minecraft.world.item.Items.APPLE).orElseThrow();
		double expected = c.recipeMarkup / c.marketFactor * (8.0 * gold + red) * PriceModifiers.get(apple);
		long v = ValueEngine.value(apple).orElseThrow();
		check(Math.abs(v - expected) <= 1.0, "golden apple = 1.3 × ingredient sell values (expected ~" + expected + ", was " + v + ")");
		Drift.set(Drift.key(net.minecraft.world.item.Items.GOLD_INGOT), 0.6, System.currentTimeMillis());
		ValueEngine.invalidate();
		long cheaper = ValueEngine.value(apple).orElseThrow();
		check(cheaper < v, "gold drift lowers the golden apple (" + v + " → " + cheaper + ")");
		ctx.detail(m.recipeCount() + " recipes, " + estimated + " estimates, golden apple " + Money.format(v));
	}

	private static void recipeModel() {
		Item x = net.minecraft.world.item.Items.COBBLESTONE;
		Item y = net.minecraft.world.item.Items.STICK;
		Item z = net.minecraft.world.item.Items.TORCH;
		Item w = net.minecraft.world.item.Items.LADDER;
		Item q = net.minecraft.world.item.Items.OAK_BUTTON;
		Item o = net.minecraft.world.item.Items.NAME_TAG;
		Item r = net.minecraft.world.item.Items.TNT;
		Item u = net.minecraft.world.item.Items.BEDROCK;
		Item s = net.minecraft.world.item.Items.SAND;
		Item t = net.minecraft.world.item.Items.GLASS;
		Item v = net.minecraft.world.item.Items.LEAD;
		Item k = net.minecraft.world.item.Items.FLINT;
		Item k2 = net.minecraft.world.item.Items.CLAY_BALL;
		List<ValueEngine.RawRecipe> recipes = List.of(
			new ValueEngine.RawRecipe(y, 1, List.of(List.of(x), List.of(x))),
			new ValueEngine.RawRecipe(z, 1, List.of(List.of(y), List.of(x))),
			new ValueEngine.RawRecipe(w, 1, List.of(List.of(y))),
			new ValueEngine.RawRecipe(y, 1, List.of(List.of(w))),
			new ValueEngine.RawRecipe(q, 4, List.of(List.of(x))),
			new ValueEngine.RawRecipe(o, 1, List.of(List.of(x))),
			new ValueEngine.RawRecipe(r, 1, List.of(List.of(u))),
			new ValueEngine.RawRecipe(t, 1, List.of(List.of(s), List.of(s, x))),
			new ValueEngine.RawRecipe(v, 1, List.of(List.of(o), List.of(x))),
			new ValueEngine.RawRecipe(k, 1, List.of(List.of(o, x))),
			new ValueEngine.RawRecipe(k2, 1, List.of(List.of(v))));
		ValueEngine.Model m = ValueEngine.compute(recipes, Map.of(x, 100L), Map.of(o, 1000L), Map.of(s, 50.0), 1.6, 1.3);
		double ratio = 1.3 / 1.6;
		approx(160, m.neutral().get(x), "X = sell × 1.6");
		approx(ratio * 320, m.neutral().get(y), "Y = 1.3 × (2 × 100)");
		approx(ratio * (ratio * 320 + 160), m.neutral().get(z), "Z from Y + X");
		approx(ratio * ratio * 320, m.neutral().get(w), "W from Y");
		approx(ratio * 320, m.neutral().get(y), "cycle Y ↔ W does not lower Y");
		approx(ratio * 160 / 4, m.neutral().get(q), "4 per recipe");
		approx(ratio * 160, m.neutral().get(o), "override at most the recipe estimate");
		eq(ValueEngine.Source.OVERRIDE, m.source(o), "override stays override");
		eq(ValueEngine.Source.RECIPE, m.source(y), "Y is estimated");
		eq(ValueEngine.Source.NONE, m.source(r), "ingredient without value -> no value");
		eq(ValueEngine.Source.NONE, m.source(t), "glass from shop sand, no value of its own");
		approx(100, m.craftCosts().get(t), "crafting cost from shop purchases (2 × pivot+1)");
		eq((Object) 79L, m.craftCaps().get(t), "crafting cap: ceil(100 / 1.25) − 1");
		isFalse(m.craftCaps().containsKey(z), "no cap without shop ingredients");
		isTrue(m.uses(x).contains(new ValueEngine.Use(y, 1, 2, List.of())), "Y from 2 X: pure conversion, k = 2");
		isTrue(m.uses(x).contains(new ValueEngine.Use(q, 4, 1, List.of())), "4 Q from 1 X");
		isTrue(m.uses(y).contains(new ValueEngine.Use(z, 1, 1, List.of(List.of(x)))), "Z from Y + other ingredient X");
		isTrue(m.uses(s).contains(new ValueEngine.Use(t, 1, 1, List.of(List.of(s, x)))), "S-only slot = k, S-or-X slot = other ingredient");
		isTrue(m.uses(x).contains(new ValueEngine.Use(t, 1, 1, List.of(List.of(s)))), "X only in the flexible slot: k = 1, rest left over");
		isTrue(m.uses(u).contains(new ValueEngine.Use(r, 1, 1, List.of())), "recipes without value are indexed too");
		isTrue(m.uses(z).isEmpty(), "Z is not consumed anywhere");
		isTrue(m.fixed().contains(o), "override item has a fixed value");
		isTrue(m.fixed().contains(v), "recipe requiring an override item -> fixed value");
		isTrue(m.fixed().contains(k2), "also across several recipe levels");
		isFalse(m.fixed().contains(k), "slot also allows X -> capped by X, no fixed value");
		isFalse(m.fixed().contains(x) || m.fixed().contains(y) || m.fixed().contains(z), "sell price and recipes from it: no fixed value");
	}

	private static void approx(double expected, Double actual, String msg) {
		check(actual != null && Math.abs(expected - actual) < EPS, msg + " (expected " + expected + ", was " + actual + ")");
	}

	private static void stackValues(TestContext ctx) {
		sandbox(ctx);
		MarketConfig c = MarketConfig.get();
		Item bookItem = net.minecraft.world.item.Items.ENCHANTED_BOOK;
		ItemStack mending = EnchantmentHelper.createBook(new EnchantmentInstance(enchantment(ctx, Enchantments.MENDING), 1));
		long bonus = ValueEngine.levelValue(c.enchantmentValues.get("minecraft:mending"), 1);
		long base = ValueEngine.value(bookItem).orElse(0);
		long expected = (long) Math.floor(base + bonus * PriceModifiers.get(bookItem) + 1e-9);
		eq(expected, ValueEngine.unitValue(mending).orElse(-1), "mending book = table bonus");
		ItemStack named = mending.copy();
		named.set(DataComponents.CUSTOM_NAME, Component.literal("My Book"));
		eq(expected, ValueEngine.unitValue(named).orElse(-1), "name does not change the value");
		eq(ValueEngine.levelValue(c.enchantmentValues.get("minecraft:efficiency"), 5),
			ValueEngine.levelValue(c.enchantmentValues.get("minecraft:efficiency"), 9), "level above the table -> last value");

		Item swordItem = net.minecraft.world.item.Items.DIAMOND_SWORD;
		ItemStack sword = new ItemStack(swordItem);
		sword.enchant(enchantment(ctx, Enchantments.SHARPNESS), 5);
		sword.setDamageValue(sword.getMaxDamage() / 2);
		double f = (double) (sword.getMaxDamage() - sword.getDamageValue()) / sword.getMaxDamage();
		long swordBase = ValueEngine.value(swordItem).orElse(0);
		long sharp = ValueEngine.levelValue(c.enchantmentValues.get("minecraft:sharpness"), 5);
		long expectedSword = (long) Math.floor((swordBase + sharp * PriceModifiers.get(swordItem)) * f + 1e-9);
		eq(expectedSword, ValueEngine.unitValue(sword).orElse(-1), "sword: (value + bonus) × durability");
		ItemStack fresh = sword.copy();
		fresh.setDamageValue(0);
		check(ValueEngine.unitValue(fresh).orElse(0) > ValueEngine.unitValue(sword).orElse(0), "deduction for durability loss");
		ItemStack broken = sword.copy();
		broken.setDamageValue(sword.getMaxDamage() - 1);
		check(ValueEngine.unitValue(broken).orElse(0) >= (long) Math.floor((swordBase + sharp * PriceModifiers.get(swordItem)) * c.minDurabilityFactor),
			"almost broken: at least minDurabilityFactor");

		ItemStack lore = new ItemStack(net.minecraft.world.item.Items.DIAMOND);
		lore.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("x"))));
		isTrue(ValueEngine.unitValue(lore).isEmpty(), "foreign component (lore) -> no value");
		ItemStack curse = EnchantmentHelper.createBook(new EnchantmentInstance(enchantment(ctx, Enchantments.BINDING_CURSE), 1));
		isTrue(ValueEngine.unitValue(curse).isEmpty(), "curse only -> no value");
		ItemStack plain = new ItemStack(net.minecraft.world.item.Items.DIAMOND, 7);
		eq(ValueEngine.value(net.minecraft.world.item.Items.DIAMOND).orElse(-1), ValueEngine.unitValue(plain).orElse(-2), "pristine stack = item value per unit");
		long floor = ValueEngine.saleFloor(null, mending);
		long raw = PriceGuard.minSystemSalePrice(ValueEngine.NEUTRAL, bookItem, expected);
		check(floor >= raw && floor <= raw + raw / 200 + 2, "floor = minSystemSalePrice with stack value, rounded up to 0.5 % (" + raw + " → " + floor + ")");
		eq(100L, ValueEngine.roundUpToBand(100), "small amounts: 1 cent grid");
		eq(200_200L, ValueEngine.roundUpToBand(200_001), "0.5 % grid (step 1001)");
		eq(Money.MAX, ValueEngine.roundUpToBand(Money.MAX), "upper limit");
		check(floor > (long) Math.floor(expected * PriceGuard.NPC_BUY_MARKET_FACTOR_MAX), "floor above every NPC buy price");
		ctx.detail("Mending " + Money.format(expected) + ", sword " + Money.format(expectedSword));
	}

	private static void craftCapGuard(TestContext ctx) {
		sandbox(ctx);
		FakePlayer p = ctx.player("MarketGuard");
		UUID id = p.getUUID();
		ValueEngine.Model m = ValueEngine.model();
		int capped = 0;
		for (Map.Entry<Item, Double> e : m.craftCosts().entrySet()) {
			OptionalLong v = ValueEngine.value(e.getKey());
			if (v.isEmpty()) continue;
			capped++;
			long maxPay = (long) Math.floor(v.getAsLong() * PriceGuard.NPC_BUY_MARKET_FACTOR_MAX + 1e-9);
			check(maxPay < e.getValue(), Items.id(e.getKey()) + ": NPC payment " + maxPay + " ≥ crafting cost from the shop " + e.getValue());
		}
		int checked = 0;
		for (Item item : m.neutral().keySet()) {
			OptionalLong v = ValueEngine.value(item);
			if (v.isEmpty()) continue;
			checked++;
			long sale = PriceGuard.minSystemSalePrice(id, item, v.getAsLong());
			long buy = PriceGuard.maxNpcBuyPrice(id, item, v.getAsLong(), PriceGuard.NPC_BUY_MARKET_FACTOR_MAX);
			long order = PriceGuard.maxNpcOrderPrice(id, item, v.getAsLong());
			check(buy < sale && order < sale, Items.id(item) + ": NPC offer " + sale + " not above NPC buy " + buy + "/order " + order);
		}
		check(checked > 300, "enough items checked (" + checked + ")");
		ctx.detail(checked + " items, " + capped + " with crafting cap");
	}

	private static final double BONI = 1.12 * 1.5;

	private static UUID boosted(TestContext ctx, String name) {
		UUID id = ctx.player(name).getUUID();
		Hooks.Registration r = Hooks.addSellMultiplier(SOURCE, "Selftest boosts", (player, item) -> id.equals(player) ? BONI : 1.0);
		ctx.cleanup(r::remove);
		return id;
	}

	private static long maxPayout(UUID who, Item item) {
		long sell = PriceGuard.effectiveSellPrice(who, item).orElse(0);
		OptionalLong v = ValueEngine.value(item);
		if (v.isEmpty()) return sell;
		long npc = Math.max(PriceGuard.maxNpcBuyPrice(who, item, v.getAsLong(), PriceGuard.NPC_BUY_MARKET_FACTOR_MAX),
			PriceGuard.maxNpcOrderPrice(who, item, v.getAsLong()));
		return Math.max(sell, npc);
	}

	/**
	 * NPC offers created during a news crash must not be cheaper than without it. Otherwise "buy from the NPC seller during the
	 * crash, sell to NPC buyers/orders or via /sell after it" is a sure profit. Also covers recipe values whose ingredient
	 * crashes (golden apple during a gold crash), enchantments, and crash plus downward drift.
	 */
	private static void crashTimeArbitrage(TestContext ctx) {
		sandbox(ctx);
		UUID boosted = boosted(ctx, "MarketCrash");
		long t = System.currentTimeMillis();
		int checked = 0;
		List<Item> items = List.of(net.minecraft.world.item.Items.DIAMOND, net.minecraft.world.item.Items.EMERALD,
			net.minecraft.world.item.Items.NETHERITE_SCRAP, net.minecraft.world.item.Items.IRON_BLOCK, net.minecraft.world.item.Items.GOLD_BLOCK,
			net.minecraft.world.item.Items.BLAZE_ROD, net.minecraft.world.item.Items.ENDER_PEARL);
		for (Item item : items) {
			OptionalLong neutral = ValueEngine.value(item);
			if (neutral.isEmpty()) continue;
			for (UUID who : List.of(ValueEngine.NEUTRAL, boosted)) {
				for (boolean lowDrift : new boolean[] {false, true}) {
					ItemStack one = new ItemStack(item);
					long before = ValueEngine.saleFloor(who, one);
					if (lowDrift) Drift.set(Drift.key(item), MarketConfig.get().driftMin, t);
					PriceModifiers.set(SOURCE, item, 0.5, 0, 60_000, 0, "Selftest-Crash");
					ValueEngine.invalidate();
					String what = Items.id(item) + (who == boosted ? " (with boosts)" : "") + (lowDrift ? " + Drift" : "");
					check(ValueEngine.value(item).orElse(0) < neutral.getAsLong(), what + ": crash lowers the market value");
					long during = ValueEngine.saleFloor(who, one);
					check(during >= before, what + ": offer not cheaper during a crash (" + before + " → " + during + ")");
					PriceModifiers.removeSource(SOURCE);
					Drift.set(Drift.key(item), 1.0, t);
					ValueEngine.invalidate();
					long after = maxPayout(who, item);
					check(after < during, what + ": after the crash the system pays " + after + " < paid during the crash " + during);
					checked++;
				}
			}
		}
		Item apple = net.minecraft.world.item.Items.GOLDEN_APPLE;
		Item gold = net.minecraft.world.item.Items.GOLD_INGOT;
		if (ValueEngine.model().source(apple) == ValueEngine.Source.RECIPE) {
			ItemStack one = new ItemStack(apple);
			long value = ValueEngine.value(apple).orElseThrow();
			long before = ValueEngine.saleFloor(boosted, one);
			PriceModifiers.set(SOURCE, gold, 0.5, 0, 60_000, 0, "Selftest-Crash");
			check(ValueEngine.value(apple).orElseThrow() < value, "gold crash lowers the golden apple market value");
			long during = ValueEngine.saleFloor(boosted, one);
			check(during >= before, "golden apple offer not cheaper during a gold crash (" + before + " → " + during + ")");
			PriceModifiers.removeSource(SOURCE);
			check(maxPayout(boosted, apple) < during, "no golden apple profit after the gold crash");
			checked++;
		}
		ItemStack book = EnchantmentHelper.createBook(new EnchantmentInstance(enchantment(ctx, Enchantments.MENDING), 1));
		long bookBefore = ValueEngine.saleFloor(boosted, book);
		PriceModifiers.set(SOURCE, net.minecraft.world.item.Items.ENCHANTED_BOOK, 0.5, 0, 60_000, 0, "Selftest-Crash");
		long bookDuring = ValueEngine.saleFloor(boosted, book);
		check(bookDuring >= bookBefore, "mending book not cheaper during a crash (" + bookBefore + " → " + bookDuring + ")");
		PriceModifiers.removeSource(SOURCE);
		check(ValueEngine.saleUnitValue(book).orElse(0) >= ValueEngine.unitValue(book).orElse(0), "sale value ≥ current value");
		ctx.detail(checked + " crash cases, book " + Money.format(bookBefore) + " → " + Money.format(bookDuring));
	}

	/**
	 * NPC offers must be safe through recipes too: "buy iron block, split it, sell ingots during hype", "9 diamonds to block
	 * during block hype", "4 netherite scrap + 4 gold to netherite ingot, sold to NPC buyers" (no event). Plus all pure
	 * conversions of trader goods with random crash/hype combinations, at the same moment and after the events end.
	 */
	private static void conversionGuard(TestContext ctx) {
		sandbox(ctx);
		UUID boosted = boosted(ctx, "MarketConvert");
		List<UUID> buyers = List.of(ValueEngine.NEUTRAL, boosted);
		Item ironBlock = net.minecraft.world.item.Items.IRON_BLOCK;
		Item iron = net.minecraft.world.item.Items.IRON_INGOT;
		Item goldBlock = net.minecraft.world.item.Items.GOLD_BLOCK;
		Item gold = net.minecraft.world.item.Items.GOLD_INGOT;
		Item scrap = net.minecraft.world.item.Items.NETHERITE_SCRAP;
		Item netherite = net.minecraft.world.item.Items.NETHERITE_INGOT;
		for (Item[] pair : new Item[][] {{ironBlock, iron}, {goldBlock, gold}}) {
			if (ValueEngine.value(pair[0]).isEmpty() || ValueEngine.value(pair[1]).isEmpty()) continue;
			PriceModifiers.set(SOURCE, pair[1], 3.0, 0, 60_000, 0, "Selftest-Hype");
			for (UUID who : buyers) {
				long floor = ValueEngine.saleFloor(who, new ItemStack(pair[0], 8));
				long payout = 8L * 9 * maxPayout(who, pair[1]);
				check(floor > payout, Items.id(pair[0]) + ": 8 blocks " + floor + " > 72 ingots during hype " + payout);
			}
			PriceModifiers.removeSource(SOURCE);
		}
		if (ValueEngine.value(scrap).isPresent() && ValueEngine.value(netherite).isPresent()) {
			for (UUID who : buyers) {
				long floor = ValueEngine.saleFloor(who, new ItemStack(scrap, 4));
				long goldValue = ValueEngine.value(gold).orElse(0);
				long goldPay = Math.max(PriceGuard.effectiveSellPrice(who, gold).orElse(0), goldValue <= 0 ? 0
					: Math.max(PriceGuard.maxNpcBuyPrice(who, gold, goldValue), PriceGuard.maxNpcOrderPrice(who, gold, goldValue)));
				long payout = maxPayout(who, netherite);
				check(floor + 4 * goldPay > payout, "4 scrap " + floor + " + 4 Gold " + 4 * goldPay + " > netherite ingot " + payout);
			}
		}
		ValueEngine.Model m = ValueEngine.model();
		Set<Item> pool = new HashSet<>();
		for (MarketConfig.PoolEntry e : MarketConfig.get().pool) {
			if (e.enchantment == null || e.enchantment.isBlank()) e.items.forEach(id -> Items.byId(id).ifPresent(pool::add));
		}
		double[] factors = {0.5, 1.0, 2.0, 3.0};
		Random rng = new Random(20261010L);
		int checked = 0;
		for (Item p : pool) {
			if (ValueEngine.value(p).isEmpty()) continue;
			for (ValueEngine.Use u : m.uses(p)) {
				if (!u.pure() || u.k() > p.getDefaultMaxStackSize()) continue;
				for (int round = 0; round < 4; round++) {
					PriceModifiers.set(SOURCE, p, factors[rng.nextInt(factors.length)], 0, 60_000, 0, "Selftest");
					if (u.out() != p) PriceModifiers.set(SOURCE, u.out(), factors[rng.nextInt(factors.length)], 0, 60_000, 0, "Selftest");
					String what = u.k() + "× " + Items.id(p) + " → " + u.count() + "× " + Items.id(u.out()) + " (Mod " + PriceModifiers.get(p)
						+ "/" + PriceModifiers.get(u.out()) + ")";
					long[] floors = new long[buyers.size()];
					for (int i = 0; i < buyers.size(); i++) {
						UUID who = buyers.get(i);
						floors[i] = ValueEngine.saleFloor(who, new ItemStack(p, u.k()));
						long now = ValueEngine.saturatingMul(maxPayout(who, u.out()), u.count());
						check(floors[i] > now, what + ": offer " + floors[i] + " > payment now " + now);
					}
					PriceModifiers.removeSource(SOURCE);
					for (int i = 0; i < buyers.size(); i++) {
						long later = ValueEngine.saturatingMul(maxPayout(buyers.get(i), u.out()), u.count());
						check(floors[i] > later, what + ": offer " + floors[i] + " > payment after the events " + later);
					}
					checked++;
				}
			}
		}
		check(checked > 0, "pure conversions checked");
		String sanity = "skipped (active price modifiers)";
		if (PriceModifiers.active().isEmpty()) {
			for (Item p : List.of(net.minecraft.world.item.Items.BLAZE_ROD, goldBlock, ironBlock, net.minecraft.world.item.Items.ENDER_PEARL,
					net.minecraft.world.item.Items.DIAMOND)) {
				OptionalLong v = ValueEngine.value(p);
				if (v.isEmpty()) continue;
				long own = PriceGuard.minSystemSalePrice(ValueEngine.NEUTRAL, p, v.getAsLong());
				long floor = ValueEngine.saleFloor(null, new ItemStack(p));
				check(floor <= own + own / 10 + 2, Items.id(p) + ": floor " + floor + " not far above " + own);
			}
			sanity = "floors without events plausible";
		}
		ctx.detail(checked + " conversion cases, " + sanity);
	}

	/**
	 * Emeralds at about $0.51 each from NPC sellers would pay off, since a farmer makes 6 bread ($9 via /sell) from 1 emerald.
	 * Market never sells emerald, emerald block or ores (default pool, old market.json, runtime pool, existing offers), and the
	 * floor counts villager trades at base prices as conversions.
	 */
	private static void villagerGuard(TestContext ctx) {
		sandbox(ctx);
		Item emerald = net.minecraft.world.item.Items.EMERALD;
		Item bread = net.minecraft.world.item.Items.BREAD;
		Item diamond = net.minecraft.world.item.Items.DIAMOND;
		for (MarketConfig.PoolEntry e : new MarketConfig().pool) {
			for (String id : e.items) isFalse(MarketConfig.neverSold(id), "default pool without " + id);
		}
		MarketConfig old = new MarketConfig();
		old.pool.add(new MarketConfig.PoolEntry(4, 8, 32, "minecraft:emerald"));
		MarketConfig.PoolEntry mixed = new MarketConfig.PoolEntry(2, 1, 4, "minecraft:emerald_block", "minecraft:diamond");
		old.pool.add(mixed);
		int entries = old.pool.size();
		MarketConfig.validate(old);
		eq(entries - 1, old.pool.size(), "emerald-only entry removed");
		for (MarketConfig.PoolEntry e : old.pool) {
			for (String id : e.items) isFalse(MarketConfig.neverSold(id), "cleaned pool without " + id);
		}
		eq(List.of("minecraft:diamond"), mixed.items, "mixed entry keeps the other items");
		MarketConfig.PoolEntry raw = new MarketConfig.PoolEntry();
		raw.items = List.of("minecraft:emerald");
		raw.min = 8;
		raw.max = 32;
		isTrue(NpcSellers.buildStack(raw, new Random(1), ctx.server().registryAccess()).isEmpty(), "no emerald stack, even without validate");
		ValueEngine.Model m = ValueEngine.model();
		check(m.tradeCount() > 0, "villager trades read (" + m.tradeCount() + ")");
		isTrue(m.uses(emerald).contains(new ValueEngine.Use(bread, 6, 1, List.of())), "farmer: 1 emerald -> 6 bread");
		isTrue(m.uses(net.minecraft.world.item.Items.IRON_INGOT).contains(new ValueEngine.Use(emerald, 1, 4, List.of())),
			"smith: 4 iron ingots -> 1 emerald");
		UUID boosted = boosted(ctx, "MarketVillager");
		List<UUID> buyers = List.of(ValueEngine.NEUTRAL, boosted);
		long floor32 = ValueEngine.saleFloor(null, new ItemStack(emerald, 32));
		long bread192 = ValueEngine.saturatingMul(maxPayout(ValueEngine.NEUTRAL, bread), 192);
		check(floor32 > bread192, "32 emeralds " + floor32 + " > 192 bread " + bread192 + " (flip scenario)");
		int checked = 0;
		for (ValueEngine.Use u : m.uses(emerald)) {
			if (!u.pure() || u.k() > emerald.getDefaultMaxStackSize()) continue;
			for (UUID who : buyers) {
				long floor = ValueEngine.saleFloor(who, new ItemStack(emerald, u.k()));
				long pay = ValueEngine.saturatingMul(maxPayout(who, u.out()), u.count());
				check(floor > pay, u.k() + "× emerald -> " + u.count() + "× " + Items.id(u.out()) + ": floor " + floor + " > payment " + pay);
			}
			checked++;
		}
		check(checked > 0, "emerald conversions checked");
		PriceModifiers.set(SOURCE, bread, 3.0, 0, 60_000, 0, "Selftest-Hype");
		for (UUID who : buyers) {
			long floor = ValueEngine.saleFloor(who, new ItemStack(emerald));
			long pay = ValueEngine.saturatingMul(maxPayout(who, bread), 6);
			check(floor > pay, "bread hype ×3: 1 emerald " + floor + " > 6 bread " + pay);
		}
		PriceModifiers.removeSource(SOURCE);
		FakeBridge bridge = fake(ctx);
		long day = 6 * 3_600_000L;
		MarketBridge.Created e1 = bridge.createNpcListing(new ItemStack(emerald, 32), 1_700, day, NpcSellers.NAME_PREFIX + "Alt", MarketBridge.SOURCE);
		MarketBridge.Created e2 = bridge.createNpcListing(new ItemStack(net.minecraft.world.item.Items.EMERALD_BLOCK, 2), 1, day,
			NpcSellers.NAME_PREFIX + "Alt", MarketBridge.SOURCE);
		MarketBridge.Created d1 = bridge.createNpcListing(new ItemStack(diamond, 4), 1, day, NpcSellers.NAME_PREFIX + "Alt", MarketBridge.SOURCE);
		isTrue(e1.ok() && e2.ok() && d1.ok(), "test offers created");
		check(e1.listing().price() > bread192, "emerald offer is above the new floor (" + e1.listing().price() + ")");
		eq(2, NpcSellers.pruneNeverSold(bridge), "emerald offers removed");
		List<MarketBridge.Listing> left = bridge.npcListings(MarketBridge.SOURCE);
		eq(1, left.size(), "only the diamond offer remains");
		isTrue(left.getFirst().item().is(diamond), "diamond offer remains");
		eq(0, NpcSellers.pruneNeverSold(bridge), "second pass: nothing left to do");
		ctx.detail(m.tradeCount() + " trades, " + checked + " emerald conversions, 1 emerald from "
			+ Money.format(ValueEngine.saleFloor(null, new ItemStack(emerald))));
	}

	private static void decisions() {
		MarketConfig c = MarketConfig.get();
		long v = 10_000;
		long guard = 11_500;
		NpcBuyers.Decision d = NpcBuyers.decide(50_000, 4, v, guard, 1_000_000, false, 1.15, 0.5, c);
		isFalse(d.buy(), "above the PriceGuard cap");
		isTrue(d.priceRelated(), "cap is price-related");
		isTrue(NpcBuyers.decide(46_000, 4, v, guard, 1_000_000, false, 1.15, 0.5, c).buy(), "exactly at the random price");
		d = NpcBuyers.decide(46_000, 4, v, guard, 1_000_000, false, 0.85, 0.5, c);
		isFalse(d.buy(), "above market value × random");
		eq("too expensive", d.reason(), "reason");
		d = NpcBuyers.decide(30_001, 4, v, guard, 30_000, false, 1.15, 0.5, c);
		isFalse(d.buy(), "never plan above Treasury.maxSpendPerHour");
		isFalse(d.priceRelated(), "treasury budget is not price-related");
		isFalse(NpcBuyers.decide(100, 1, 0, 0, 1_000_000, false, 1.0, 0.5, c).buy(), "no purchase without market value");
		long minMs = c.buyerMinDelayMinutes * 60_000L;
		long maxMs = c.buyerMaxDelayMinutes * 60_000L;
		NpcBuyers.Decision cheap = NpcBuyers.decide(10_000, 4, v, guard, 1_000_000, false, 1.0, 0.5, c);
		NpcBuyers.Decision dear = NpcBuyers.decide(39_000, 4, v, guard, 1_000_000, false, 1.0, 0.5, c);
		isTrue(cheap.buy() && dear.buy(), "both bought");
		check(cheap.delayMs() < dear.delayMs(), "the cheaper, the faster (" + cheap.delayMs() + " < " + dear.delayMs() + ")");
		for (double jitter : new double[] {0, 0.5, 0.999}) {
			for (long price : new long[] {1, 20_000, 40_000}) {
				NpcBuyers.Decision x = NpcBuyers.decide(price, 4, v, guard, 1_000_000, false, 1.0, jitter, c);
				check(x.delayMs() >= minMs && x.delayMs() <= maxMs, "delay within 5-90 min (" + x.delayMs() + ")");
			}
		}
		NpcBuyers.Decision fever = NpcBuyers.decide(50_000, 4, v, 12_500, 1_000_000, true, 1.15, 0.5, c);
		isTrue(fever.buy(), "fever: up to market value × 1.25");
		NpcBuyers.Decision normal = NpcBuyers.decide(39_000, 4, v, guard, 1_000_000, false, 1.0, 0.5, c);
		NpcBuyers.Decision fast = NpcBuyers.decide(39_000, 4, v, 12_500, 1_000_000, true, 1.0 / NpcBuyers.FEVER_SCALE, 0.5, c);
		eq(normal.delayMs() / 2, fast.delayMs(), "fever halves the delay");
		check(fever.delayMs() <= maxMs / 2, "fever delay ≤ 45 min");
	}

	private static void offerPrices() {
		MarketConfig c = MarketConfig.get();
		Random rng = new Random(42);
		long unit = 10_000;
		int count = 4;
		long floor = 12_501L * count;
		int nearFloor = 0;
		for (int i = 0; i < 5000; i++) {
			long p = NpcSellers.offerPrice(unit, count, floor, rng, c);
			check(p >= floor, "never below the floor (" + p + " < " + floor + ")");
			check(p <= Math.max((long) Math.ceil(unit * count * c.offerPriceMax), (long) Math.ceil(floor * 1.05)), "not absurdly expensive (" + p + ")");
			if (p <= Math.ceil(floor * 1.05)) nearFloor++;
		}
		check(nearFloor > 0, "normal offers are often just above the floor (mostly overpriced)");
		check(NpcSellers.offerPrice(1, 1, 0, new Random(1), c) >= 1, "at least 1 cent");
		check(NpcSellers.offerPrice(1_000_000, 64, Money.MAX, new Random(1), c) == Money.MAX, "overflow clamped");
		long normal = 60_000;
		long min = 30_000;
		int below = 0;
		for (int i = 0; i < 5000; i++) {
			long p = NpcSellers.bargainPrice(normal, min, rng, c);
			check(p >= min, "bargain never below the floor (" + p + ")");
			check(p <= (long) Math.ceil(normal * c.bargainPriceMax), "bargain at most " + c.bargainPriceMax + " × normal (" + p + ")");
			if (p > min) below++;
		}
		check(below > 0, "bargains spread between floor and " + c.bargainPriceMax + " × normal");
		isTrue(NpcSellers.isRealBargain(40_000, 50_000, c), "20 % below the normal floor = real bargain");
		isFalse(NpcSellers.isRealBargain(48_000, 50_000, c), "only 4 % below = no real bargain");
		eq(Money.MAX, NpcSellers.bargainPrice(Money.MAX, Money.MAX, new Random(1), c), "overflow clamped (bargain)");
	}

	private static void drift(TestContext ctx) {
		sandbox(ctx);
		MarketConfig c = MarketConfig.get();
		String key = "minecraft:diamond";
		long t = 1_000_000_000L;
		double hl = c.driftHalfLifeHours * 3_600_000.0;
		approx(1.2, Drift.apply(key, 0.2, t), "buying from NPC -> factor rises");
		approx(1 + 0.2 * Math.pow(0.5, 12 * 3_600_000.0 / hl), Drift.current(key, t + 12 * 3_600_000L), "decay toward 1.0 after 12 h");
		approx(1.0 + 0.2 * 0.5, Drift.current(key, t + (long) hl), "half as far after one half-life");
		approx(0.7, Drift.apply(key, -0.5, t), "NPC buys -> factor falls");
		approx(1 - 0.3 * 0.5, Drift.current(key, t + (long) hl), "back toward 1.0 from below too");
		approx(c.driftMax, Drift.apply(key, 10, t), "clamped at the top");
		approx(c.driftMin, Drift.apply(key, -10, t), "clamped at the bottom");
		approx(0.8, Drift.decayed(0.6, 0, (long) hl, hl, 0.6, 1.6), "pure core");
		approx(0.04 * 0.25, Drift.impact(16, 64, 0.04, 0.06), "16 of 64 = a quarter step");
		approx(0.06, Drift.impact(640, 64, 0.04, 0.06), "at most maxStep per trade");
		approx(0.04, Drift.impact(1, 1, 0.04, 0.06), "elytra (stack 1) = one step");
		eq(0.0, Drift.impact(0, 64, 0.04, 0.06), "0 items = no change");
		Item diamond = net.minecraft.world.item.Items.DIAMOND;
		long base = ValueEngine.value(diamond).orElseThrow();
		Drift.set(key, 1.5, System.currentTimeMillis());
		long up = ValueEngine.value(diamond).orElseThrow();
		check(Math.abs(up - Math.floor(base * 1.5)) <= 2, "market value × drift (" + base + " → " + up + ")");
		Drift.set(key, 0.6, System.currentTimeMillis());
		long low = ValueEngine.value(diamond).orElseThrow();
		check(low < base, "drift 0.6 lowers the market value (" + low + ")");
		long sale = ValueEngine.saleUnitValue(new ItemStack(diamond)).orElseThrow();
		check(Math.abs(sale - base) <= 2, "sale value without downward drift (" + sale + " ~ " + base + ")");
		check(ValueEngine.saleFloor(null, new ItemStack(diamond, 4)) >= 4 * PriceGuard.minSystemSalePrice(ValueEngine.NEUTRAL, diamond, base) - 8,
			"floor with neutral value");
		Drift.set(key, 1.0, System.currentTimeMillis());
		isFalse(MarketData.get().drift.containsKey(key), "neutral factor is not stored");
		Drift.apply("minecraft:emerald", 0.0005, t);
		Drift.prune(t);
		isFalse(MarketData.get().drift.containsKey("minecraft:emerald"), "nearly neutral entries are removed");
	}

	private static void persistence() {
		MarketData d = new MarketData();
		d.drift.put("minecraft:diamond", new MarketData.DriftEntry(1.3, 123L));
		d.drift.put("enchant:minecraft:mending", new MarketData.DriftEntry(0.7, 456L));
		MarketData.BuyPlan p = new MarketData.BuyPlan();
		p.listingId = 77;
		p.seller = UUID.randomUUID();
		p.itemId = "minecraft:diamond";
		p.count = 4;
		p.price = 12345;
		p.random = 0.97;
		p.dueAt = 999;
		d.plans.put(77L, p);
		MarketData.Rejection r = new MarketData.Rejection();
		r.reason = "too expensive";
		r.priceRelated = true;
		d.rejected.put(78L, r);
		MarketData.Bargain bg = new MarketData.Bargain();
		bg.createdAt = 100L;
		bg.potential = 4_200L;
		bg.price = 9_000L;
		bg.itemId = "minecraft:diamond";
		bg.count = 3;
		bg.soldAt = 200L;
		d.bargains.put(79L, bg);
		d.bargainsCreated = 5;
		d.targetOffers = 11;
		MarketData back = DataStore.gson().fromJson(DataStore.gson().toJson(d), MarketData.class);
		back.fixNulls();
		approx(1.3, back.drift.get("minecraft:diamond").factor, "drift saved");
		eq(456L, back.drift.get("enchant:minecraft:mending").at, "timestamp saved");
		eq(12345L, back.plans.get(77L).price, "plan saved");
		eq(p.seller, back.plans.get(77L).seller, "seller saved");
		approx(0.97, back.plans.get(77L).random, "random factor saved");
		isTrue(back.rejected.get(78L).priceRelated, "rejection saved");
		eq(4_200L, back.bargains.get(79L).potential, "bargain budget saved");
		eq(200L, back.bargains.get(79L).soldAt, "sale time saved");
		eq(5L, back.bargainsCreated, "bargain stats saved");
		eq(11, back.targetOffers, "target saved");
		MarketData broken = DataStore.gson().fromJson("{\"drift\":null,\"plans\":{\"5\":null},\"rejected\":null}", MarketData.class);
		broken.fixNulls();
		isTrue(broken.drift.isEmpty() && broken.plans.isEmpty() && broken.rejected.isEmpty(), "broken entries are repaired");
	}

	private static void npcBuyPaysSeller(TestContext ctx) {
		sandbox(ctx);
		treasury(ctx, 100_000_000L);
		FakeBridge bridge = fake(ctx);
		FakePlayer seller = seller(ctx, "MarketSeller");
		ItemStack stack = new ItemStack(net.minecraft.world.item.Items.DIAMOND, 4);
		long price = cheapPrice(stack);
		long now = System.currentTimeMillis();
		MarketBridge.Listing l = bridge.addPlayerListing(seller.getUUID(), stack, price, now);
		MarketData.BuyPlan p = plan(l.id());
		long delay = p.dueAt - p.createdAt;
		long min = MarketConfig.get().buyerMinDelayMinutes * 60_000L / (p.fever ? 2 : 1);
		check(delay >= min && delay <= MarketConfig.get().buyerMaxDelayMinutes * 60_000L, "purchase planned within 5-90 min (" + delay + " ms)");
		long sellerBefore = EconomyService.balance(seller.getUUID());
		long treasuryBefore = Treasury.balance();
		long buysBefore = MarketData.get().npcBuys;
		eq(NpcBuyers.Outcome.BOUGHT, NpcBuyers.execute(bridge, p, p.dueAt), "NPC buys");
		long tax = bridge.taxFor(price);
		eq(Money.percentOf(price, FakeBridge.TAX_PERCENT), tax, "3 % tax");
		eq(sellerBefore + price - tax, EconomyService.balance(seller.getUUID()), "seller gets price minus tax");
		eq(treasuryBefore - price + tax, Treasury.balance(), "treasury pays the price, receives the tax");
		isTrue(bridge.listing(l.id()).isEmpty(), "listing gone (item deleted)");
		isFalse(MarketData.get().plans.containsKey(l.id()), "plan done");
		eq(buysBefore + 1, MarketData.get().npcBuys, "stats");
		check(Drift.current(Drift.key(stack.getItem()), System.currentTimeMillis()) < 1.0, "selling to an NPC lowers the drift");
		eq(NpcBuyers.Outcome.DROPPED, NpcBuyers.execute(bridge, p, p.dueAt), "second attempt: nothing left");
		eq(sellerBefore + price - tax, EconomyService.balance(seller.getUUID()), "never paid twice");
		ctx.detail("Price " + Money.format(price) + ", tax " + Money.format(tax));
	}

	private static void treasuryCap(TestContext ctx) {
		sandbox(ctx);
		FakeBridge bridge = fake(ctx);
		FakePlayer seller = seller(ctx, "MarketCapSeller");
		long now = System.currentTimeMillis();
		treasury(ctx, 10_000L);
		ItemStack one = new ItemStack(net.minecraft.world.item.Items.DIAMOND, 1);
		long unit = ValueEngine.buyerUnitValue(one).orElseThrow();
		long budget = Treasury.maxSpendPerHour();
		long price = budget + 1;
		if (price <= (long) Math.floor(unit * 0.85)) {
			MarketBridge.Listing l = bridge.addPlayerListing(seller.getUUID(), one, price, now);
			isFalse(MarketData.get().plans.containsKey(l.id()), "not planned above maxSpendPerHour");
			eq("above the Treasury's hourly budget", MarketData.get().rejected.get(l.id()).reason, "reason");
		}
		long sellerBefore = EconomyService.balance(seller.getUUID());
		EconomyService.set(Treasury.ID, 100_000_000L);
		ItemStack diamonds = new ItemStack(net.minecraft.world.item.Items.DIAMOND, 2);
		MarketBridge.Listing l2 = bridge.addPlayerListing(seller.getUUID(), diamonds, cheapPrice(diamonds), now);
		MarketData.BuyPlan p2 = plan(l2.id());
		EconomyService.set(Treasury.ID, 100L);
		boolean shrunk = Treasury.maxSpendPerHour() < l2.price();
		if (shrunk) eq(NpcBuyers.Outcome.DROPPED, NpcBuyers.execute(bridge, p2, p2.dueAt), "price > maxSpendPerHour -> dropped");
		eq(sellerBefore, EconomyService.balance(seller.getUUID()), "nothing paid");
		EconomyService.set(Treasury.ID, 100_000_000L);
		MarketBridge.Listing l = bridge.addPlayerListing(seller.getUUID(), diamonds, cheapPrice(diamonds), now);
		MarketData.BuyPlan p = plan(l.id());
		check(Treasury.tryWithdraw(Treasury.allowance(), "market_selftest"), "exhaust the budget");
		eq(0L, Treasury.allowance(), "no budget left");
		int retries = MarketConfig.get().buyerMaxRetries;
		for (int i = 0; i < retries; i++) {
			long due = p.dueAt;
			eq(NpcBuyers.Outcome.RETRY, NpcBuyers.execute(bridge, p, due), "attempt " + (i + 1) + ": retry later");
			check(p.dueAt >= due + MarketConfig.get().buyerRetryMinutes * 60_000L, "rescheduled after buyerRetryMinutes");
		}
		eq(NpcBuyers.Outcome.DROPPED, NpcBuyers.execute(bridge, p, p.dueAt), "dropped after buyerMaxRetries");
		eq(sellerBefore, EconomyService.balance(seller.getUUID()), "nothing paid");
		isTrue(bridge.listing(l.id()).isPresent(), "listing stays in the AH for players");
		eq(sellerBefore, EconomyService.balance(seller.getUUID()), "still nothing paid");
		ctx.detail("Budget " + Money.format(budget) + ", " + retries + " retries" + (shrunk ? ", treasury shrank -> dropped" : ""));
	}

	private static void buyerModeledValuesOnly(TestContext ctx) {
		sandbox(ctx);
		treasury(ctx, 100_000_000L);
		FakeBridge bridge = fake(ctx);
		FakePlayer seller = seller(ctx, "MarketFarmer");
		MarketConfig c = MarketConfig.get();
		boolean ench = c.buyerPaysEnchantments;
		boolean fixed = c.buyerPaysFixedValues;
		ctx.cleanup(() -> {
			c.buyerPaysEnchantments = ench;
			c.buyerPaysFixedValues = fixed;
		});
		c.buyerPaysEnchantments = false;
		c.buyerPaysFixedValues = false;
		long now = System.currentTimeMillis();
		long sellerBefore = EconomyService.balance(seller.getUUID());
		ItemStack book = EnchantmentHelper.createBook(new EnchantmentInstance(enchantment(ctx, Enchantments.EFFICIENCY), 4));
		long bookValue = ValueEngine.unitValue(book).orElse(0);
		check(bookValue > 0, "efficiency IV book has a market value (" + bookValue + ")");
		isTrue(ValueEngine.buyerUnitValue(book).isEmpty(), "NPC buyers pay nothing for the enchantment bonus");
		MarketBridge.Listing lb = bridge.addPlayerListing(seller.getUUID(), book, Math.max(1, bookValue / 2), now);
		isFalse(MarketData.get().plans.containsKey(lb.id()), "book at half market value: no NPC purchase planned");
		eq(NpcBuyers.REASON_PLAYERS_ONLY, MarketData.get().rejected.get(lb.id()).reason, "reason");
		isFalse(MarketData.get().rejected.get(lb.id()).priceRelated, "not price-related (fever does not recheck)");
		Item disc = net.minecraft.world.item.Items.MUSIC_DISC_CAT;
		if (ValueEngine.model().source(disc) == ValueEngine.Source.OVERRIDE) {
			isTrue(ValueEngine.fixedValue(disc), "music disc has a fixed value");
			isTrue(ValueEngine.buyerUnitValue(new ItemStack(disc)).isEmpty(), "NPC buyers do not pay fixed values");
			long discValue = ValueEngine.value(disc).orElse(0);
			MarketBridge.Listing ld = bridge.addPlayerListing(seller.getUUID(), new ItemStack(disc), Math.max(1, discValue / 2), now);
			isFalse(MarketData.get().plans.containsKey(ld.id()), "music disc at half market value: no NPC purchase");
			eq(NpcBuyers.REASON_PLAYERS_ONLY, MarketData.get().rejected.get(ld.id()).reason, "reason (music disc)");
		} else {
			ctx.detail("music disc without override, part skipped");
		}
		isFalse(ValueEngine.fixedValue(net.minecraft.world.item.Items.DIAMOND), "diamond: sell price, no fixed value");
		Item beacon = net.minecraft.world.item.Items.BEACON;
		if (ValueEngine.model().source(beacon) == ValueEngine.Source.RECIPE && ValueEngine.fixedValue(net.minecraft.world.item.Items.NETHER_STAR)) {
			isTrue(ValueEngine.fixedValue(beacon), "beacon depends on the nether star -> fixed value");
			isTrue(ValueEngine.buyerUnitValue(new ItemStack(beacon)).isEmpty(), "NPC buyers do not buy beacons");
		}
		Item swordItem = net.minecraft.world.item.Items.DIAMOND_SWORD;
		long plainSword = ValueEngine.buyerUnitValue(new ItemStack(swordItem)).orElse(0);
		check(plainSword > 0, "diamond sword has a value for NPC buyers");
		ItemStack sharp = new ItemStack(swordItem);
		sharp.enchant(enchantment(ctx, Enchantments.SHARPNESS), 5);
		check(ValueEngine.unitValue(sharp).orElse(0) > plainSword, "market value higher with sharpness V");
		eq(plainSword, ValueEngine.buyerUnitValue(sharp).orElse(-1), "NPC buyers: sharpness V does not count");
		ItemStack cursed = new ItemStack(swordItem);
		cursed.enchant(enchantment(ctx, Enchantments.VANISHING_CURSE), 1);
		check(ValueEngine.buyerUnitValue(cursed).orElse(0) < plainSword, "curse deducts for NPC buyers too");
		ItemStack half = new ItemStack(swordItem);
		half.enchant(enchantment(ctx, Enchantments.SHARPNESS), 5);
		half.setDamageValue(half.getMaxDamage() / 2);
		check(ValueEngine.buyerUnitValue(half).orElse(0) < plainSword, "durability still counts");
		long price = Math.max(1, (long) Math.floor(plainSword * 0.8));
		MarketBridge.Listing ls = bridge.addPlayerListing(seller.getUUID(), sharp, price, now);
		MarketData.BuyPlan ps = plan(ls.id());
		eq(NpcBuyers.Outcome.BOUGHT, NpcBuyers.execute(bridge, ps, now), "NPC buys the sword at base value");
		check(Math.abs(Drift.current(Drift.enchantKey("minecraft:sharpness"), System.currentTimeMillis()) - 1.0) < EPS,
			"enchantment drift unchanged (not paid)");
		long dear = (long) Math.floor(plainSword * 1.3);
		if (dear < ValueEngine.unitValue(sharp).orElse(0)) {
			MarketBridge.Listing ld = bridge.addPlayerListing(seller.getUUID(), sharp, dear, now);
			isFalse(MarketData.get().plans.containsKey(ld.id()), "sword above base value × 1.15: no NPC purchase");
		}
		MarketBridge.Listing old = bridge.addPlayerListing(seller.getUUID(), book, 1, now);
		MarketData.get().rejected.remove(old.id());
		MarketData.BuyPlan legacy = new MarketData.BuyPlan();
		legacy.listingId = old.id();
		legacy.seller = seller.getUUID();
		legacy.itemId = Items.id(book.getItem());
		legacy.count = 1;
		legacy.price = 1;
		legacy.createdAt = now;
		legacy.dueAt = now;
		MarketData.get().plans.put(old.id(), legacy);
		eq(NpcBuyers.Outcome.DROPPED, NpcBuyers.execute(bridge, legacy, now), "old book plan is dropped");
		isTrue(bridge.listing(old.id()).isPresent(), "book stays in the AH for players");
		eq(sellerBefore + price - bridge.taxFor(price), EconomyService.balance(seller.getUUID()), "only the sword paid");
		c.buyerPaysEnchantments = true;
		eq(bookValue, ValueEngine.buyerUnitValue(book).orElse(-1), "buyerPaysEnchantments: full book value");
		c.buyerPaysFixedValues = true;
		if (ValueEngine.value(disc).isPresent()) {
			eq(ValueEngine.value(disc).orElse(-1), ValueEngine.buyerUnitValue(new ItemStack(disc)).orElse(-2), "buyerPaysFixedValues: fixed value");
		}
		ctx.detail("Book " + Money.format(bookValue) + " -> NPC 0, sword " + Money.format(ValueEngine.unitValue(sharp).orElse(0)) + " -> NPC "
			+ Money.format(plainSword));
	}

	private static void sellerBudget(TestContext ctx) {
		sandbox(ctx);
		treasury(ctx, 100_000_000L);
		FakeBridge bridge = fake(ctx);
		FakePlayer a = seller(ctx, "MarketBudgetA");
		FakePlayer b = seller(ctx, "MarketBudgetB");
		MarketConfig c = MarketConfig.get();
		double hourShare = c.buyerSellerHourShare;
		double dayShare = c.buyerSellerDayShare;
		ctx.cleanup(() -> {
			c.buyerSellerHourShare = hourShare;
			c.buyerSellerDayShare = dayShare;
		});
		c.buyerSellerHourShare = 0.5;
		c.buyerSellerDayShare = 3.0;
		eq(5_000L, NpcBuyers.sellerHourCap(10_000, c), "hour: 50 % of the treasury budget");
		eq(30_000L, NpcBuyers.sellerDayCap(10_000, c), "day: 3 hourly budgets");
		eq(5_000L, NpcBuyers.sellerMaxPrice(10_000, c), "largest listing = hourly share");
		eq(0L, NpcBuyers.sellerHourCap(0, c), "empty treasury -> 0");
		long now = System.currentTimeMillis();
		ItemStack one = new ItemStack(net.minecraft.world.item.Items.DIAMOND, 1);
		long p1 = cheapPrice(one);
		long budget = Treasury.maxSpendPerHour();
		check(budget > 10 * p1, "treasury budget large enough (" + budget + ")");
		c.buyerSellerHourShare = (2.5 * p1) / budget;
		c.buyerSellerDayShare = (3.5 * p1) / budget;
		long hourCap = NpcBuyers.sellerHourCap(budget, c);
		check(hourCap >= 2 * p1 && hourCap < 3 * p1, "hourly share between 2 and 3 diamonds (" + hourCap + ")");
		ItemStack three = new ItemStack(net.minecraft.world.item.Items.DIAMOND, 3);
		MarketBridge.Listing la = bridge.addPlayerListing(a.getUUID(), three, cheapPrice(three), now);
		isFalse(MarketData.get().plans.containsKey(la.id()), "not planned above the per-player budget");
		eq(NpcBuyers.REASON_SELLER_CAP, MarketData.get().rejected.get(la.id()).reason, "reason");
		ItemStack two = new ItemStack(net.minecraft.world.item.Items.DIAMOND, 2);
		long p2 = cheapPrice(two);
		MarketBridge.Listing lb = bridge.addPlayerListing(a.getUUID(), two, p2, now);
		eq(NpcBuyers.Outcome.BOUGHT, NpcBuyers.execute(bridge, plan(lb.id()), now), "2 diamonds bought");
		eq(p2, NpcBuyers.sellerPaid(a.getUUID(), now, 60), "budget: hour counts the price");
		long balanceA = EconomyService.balance(a.getUUID());
		MarketBridge.Listing lc = bridge.addPlayerListing(a.getUUID(), one, p1, now);
		MarketData.BuyPlan pc = plan(lc.id());
		eq(NpcBuyers.Outcome.RETRY, NpcBuyers.execute(bridge, pc, now + 30 * 60_000L), "seller budget exhausted -> later");
		eq(balanceA, EconomyService.balance(a.getUUID()), "nothing paid");
		MarketBridge.Listing ld = bridge.addPlayerListing(b.getUUID(), one, p1, now);
		eq(NpcBuyers.Outcome.BOUGHT, NpcBuyers.execute(bridge, plan(ld.id()), now), "other player: own budget");
		long later = now + 61 * 60_000L;
		eq(NpcBuyers.Outcome.BOUGHT, NpcBuyers.execute(bridge, pc, later), "bought after one hour");
		eq(p2 + p1, NpcBuyers.sellerPaid(a.getUUID(), later, 24 * 60), "day counts both purchases");
		MarketBridge.Listing lf = bridge.addPlayerListing(a.getUUID(), one, p1, now);
		MarketData.BuyPlan pf = plan(lf.id());
		long t = now + 3 * 3_600_000L;
		for (int i = 0; i < c.buyerMaxRetries; i++) eq(NpcBuyers.Outcome.RETRY, NpcBuyers.execute(bridge, pf, t + i), "daily budget exhausted -> later");
		eq(NpcBuyers.Outcome.DROPPED, NpcBuyers.execute(bridge, pf, t + 99), "eventually dropped, never endless");
		isTrue(bridge.listing(lf.id()).isPresent(), "listing stays in the AH for players");
		long dayLater = later + 24 * 3_600_000L + 60_000L;
		eq(Math.min(hourCap, NpcBuyers.sellerDayCap(budget, c)), NpcBuyers.sellerRoom(a.getUUID(), budget, dayLater, c), "full budget again after 24 h");
		NpcBuyers.sweep(bridge, dayLater, new Random(3));
		isFalse(MarketData.get().sellerPayouts.containsKey(a.getUUID()), "old entries removed");
		NpcBuyers.recordSellerPayout(a.getUUID(), 123, now);
		isTrue(NpcBuyers.resetSellerBudget(a.getUUID()), "Reset");
		eq(0L, NpcBuyers.sellerPaid(a.getUUID(), now, 24 * 60), "budget reset");
		MarketData d = MarketData.get();
		NpcBuyers.recordSellerPayout(a.getUUID(), 77, now);
		MarketData back = DataStore.gson().fromJson(DataStore.gson().toJson(d), MarketData.class);
		back.fixNulls();
		eq(77L, back.sellerPayouts.get(a.getUUID()).values().stream().mapToLong(Long::longValue).sum(), "budget saved");
		ctx.detail("Diamond " + Money.format(p1) + ", hour " + Money.format(hourCap) + ", day " + Money.format(NpcBuyers.sellerDayCap(budget, c)));
	}

	private static void recheckOnExecute(TestContext ctx) {
		sandbox(ctx);
		treasury(ctx, 100_000_000L);
		FakeBridge bridge = fake(ctx);
		FakePlayer seller = seller(ctx, "MarketRecheck");
		long now = System.currentTimeMillis();
		long sellerBefore = EconomyService.balance(seller.getUUID());
		ItemStack diamonds = new ItemStack(net.minecraft.world.item.Items.DIAMOND, 3);
		MarketBridge.Listing a = bridge.addPlayerListing(seller.getUUID(), diamonds, cheapPrice(diamonds), now);
		MarketData.BuyPlan pa = plan(a.id());
		bridge.cancel(a.id());
		isFalse(MarketData.get().plans.containsKey(a.id()), "cancel deletes the plan");
		eq(NpcBuyers.Outcome.DROPPED, NpcBuyers.execute(bridge, pa, pa.dueAt), "listing gone -> no purchase");
		long price = cheapPrice(diamonds);
		MarketBridge.Listing b = bridge.addPlayerListing(seller.getUUID(), diamonds, price, now);
		MarketData.BuyPlan pb = plan(b.id());
		PriceModifiers.set(SOURCE, net.minecraft.world.item.Items.DIAMOND, 0.3, 0, 60_000, 0, "Selftest");
		eq(NpcBuyers.Outcome.DROPPED, NpcBuyers.execute(bridge, pb, pb.dueAt), "too expensive after crash -> dropped");
		isTrue(bridge.listing(b.id()).isPresent(), "listing stays");
		PriceModifiers.removeSource(SOURCE);
		NpcBuyers.sweep(bridge, now, new Random(7));
		isFalse(MarketData.get().plans.containsKey(b.id()), "dropped stays dropped");
		MarketBridge.Listing c = bridge.addPlayerListing(seller.getUUID(), diamonds, cheapPrice(diamonds), now);
		isTrue(MarketData.get().plans.containsKey(c.id()), "cheap listing planned (no buy-back lock)");
		bridge.cancel(c.id());
		MarketBridge.Created npc = bridge.createNpcListing(diamonds, 1, 3_600_000L, "§e[Market] Test", MarketBridge.SOURCE);
		isTrue(npc.ok(), "NPC offer created");
		isFalse(MarketData.get().plans.containsKey(npc.listing().id()) || MarketData.get().rejected.containsKey(npc.listing().id()),
			"NPC offers are not evaluated");
		eq(sellerBefore, EconomyService.balance(seller.getUUID()), "never paid");
	}

	private static void sellerRotation(TestContext ctx) {
		sandbox(ctx);
		treasury(ctx, 100_000_000L);
		FakeBridge bridge = fake(ctx);
		MarketConfig c = MarketConfig.get();
		long now = System.currentTimeMillis();
		MarketData.get().targetOffers = 0;
		int created = NpcSellers.fill(bridge, now, new Random(1234));
		int target = MarketData.get().targetOffers;
		check(target >= c.sellerMinOffers && target <= c.sellerMaxOffers, "target " + c.sellerMinOffers + "–" + c.sellerMaxOffers + " (" + target + ")");
		check(c.sellerMinOffers > 45, "default: at least two AH pages of NPC offers (" + c.sellerMinOffers + ")");
		List<MarketBridge.Listing> offers = bridge.npcListings(MarketBridge.SOURCE);
		eq(target, offers.size(), "filled up to the target");
		eq(target, created, "count created");
		Set<String> keys = new HashSet<>();
		Set<String> poolItems = new HashSet<>();
		for (MarketConfig.PoolEntry e : c.pool) poolItems.addAll(e.items);
		for (MarketBridge.Listing l : offers) {
			isTrue(l.npc() && MarketBridge.SOURCE.equals(l.source()), "NPC offer from market");
			isTrue(l.sellerName().startsWith(NpcSellers.NAME_PREFIX), "trader name '" + l.sellerName() + "'");
			isTrue(poolItems.contains(Items.id(l.item().getItem())), "item from the pool");
			long dur = l.expiresAt() - l.createdAt();
			if (l.bargain()) {
				check(l.price() >= ValueEngine.bargainFloor(null, l.item()), "bargain ≥ bargain floor (" + l.price() + ")");
				check(dur >= c.bargainMinHours * 3_600_000L && dur <= c.bargainMaxHours * 3_600_000L, "bargain duration");
			} else {
				check(l.price() >= ValueEngine.saleFloor(null, l.item()), "price ≥ floor (" + l.price() + ")");
				check(dur >= c.offerMinHours * 3_600_000L && dur <= c.offerMaxHours * 3_600_000L, "duration 6-24 h");
			}
			keys.add(NpcSellers.offerKey(l.item()));
		}
		check(keys.size() >= target / 2, "varied (" + keys.size() + " different)");
		eq(0, NpcSellers.fill(bridge, now, new Random(5)), "full -> nothing new");
		FakePlayer buyer = seller(ctx, "MarketBuyer");
		MarketBridge.Listing pick = offers.stream().filter(l -> !l.bargain() && Items.isPristine(l.item()) && ValueEngine.buyerUnitValue(l.item()).isPresent())
			.findFirst().orElse(offers.getFirst());
		EconomyService.deposit(buyer.getUUID(), pick.price() + 100);
		long treasuryBefore = Treasury.balance();
		long salesBefore = MarketData.get().npcSales;
		eq(FakeBridge.PlayerBuy.OK, bridge.buyNpcAsPlayer(buyer, pick.id()), "player buys NPC offer");
		eq(treasuryBefore + pick.price(), Treasury.balance(), "full proceeds to the treasury");
		eq(100L, EconomyService.balance(buyer.getUUID()), "buyer pays the price");
		eq(pick.count(), Items.count(buyer, s -> ItemStack.isSameItemSameComponents(s, pick.item())) + countDrops(ctx, buyer, pick.item()), "item received");
		eq(salesBefore + 1, MarketData.get().npcSales, "stats");
		String driftKey = ValueEngine.value(pick.item().getItem()).isPresent() ? Drift.key(pick.item().getItem())
			: Drift.enchantKey(ValueEngine.enchantmentIds(pick.item()).getFirst());
		check(Drift.current(driftKey, System.currentTimeMillis()) > 1.0, "demand raises the drift (" + driftKey + ")");
		if (Items.isPristine(pick.item()) && ValueEngine.buyerUnitValue(pick.item()).isPresent()) {
			MarketBridge.Listing resale = bridge.addPlayerListing(buyer.getUUID(), pick.item(), 1, System.currentTimeMillis());
			isTrue(MarketData.get().plans.containsKey(resale.id()), "immediate resale to NPC buyers allowed (" + NpcSellers.offerKey(pick.item())
				+ ", " + (MarketData.get().rejected.containsKey(resale.id()) ? MarketData.get().rejected.get(resale.id()).reason : "-") + ")");
			bridge.cancel(resale.id());
		}
		eq(offers.size() - 1, NpcSellers.clear(bridge), "all NPC offers removed");
		eq(0, NpcSellers.activeBargains(), "removed bargains release their budget");
		eq(0, bridge.npcListings(MarketBridge.SOURCE).size(), "no NPC offers left");
		ctx.detail(target + " offers, " + keys.size() + " different");
	}

	private static int countDrops(TestContext ctx, FakePlayer p, ItemStack like) {
		int n = 0;
		for (ItemStack s : ctx.drops(p)) {
			if (ItemStack.isSameItemSameComponents(s, like)) n += s.getCount();
		}
		return n;
	}

	private static void floorOnPurchase(TestContext ctx) {
		sandbox(ctx);
		treasury(ctx, 100_000_000L);
		FakeBridge bridge = fake(ctx);
		FakePlayer buyer = seller(ctx, "MarketFloorBuyer");
		ItemStack book = EnchantmentHelper.createBook(new EnchantmentInstance(enchantment(ctx, Enchantments.MENDING), 1));
		MarketBridge.Created created = bridge.createNpcListing(book, 1, 6 * 3_600_000L, NpcSellers.NAME_PREFIX + "Test", MarketBridge.SOURCE);
		isTrue(created.ok(), "created");
		long floor = ValueEngine.saleFloor(null, book);
		eq(floor, created.listing().price(), "1 cent is raised to the floor on listing");
		long unit = ValueEngine.unitValue(book).orElseThrow();
		long raw = (long) Math.floor(unit * PriceGuard.NPC_BUY_MARKET_FACTOR_MAX + 1e-9) + 1;
		check(floor >= raw && floor <= raw + raw / 200 + 2, "≈ market value × 1.25 + 1 cent, rounded up (" + raw + " → " + floor + ")");
		Drift.set(Drift.enchantKey("minecraft:mending"), 1.3, System.currentTimeMillis());
		ValueEngine.invalidate();
		long higher = ValueEngine.saleFloor(buyer.getUUID(), book);
		check(higher > floor, "new floor higher");
		EconomyService.deposit(buyer.getUUID(), higher * 2);
		eq(FakeBridge.PlayerBuy.PRICE_CHANGED, bridge.buyNpcAsPlayer(buyer, created.listing().id()), "too cheap -> purchase rejected");
		long raised = bridge.listing(created.listing().id()).orElseThrow().price();
		// Drift decays in real time, so two computations can differ by a few cents
		check(raised > floor && Math.abs(raised - higher) <= 5, "price raised to the new floor (" + raised + " ~ " + higher + ")");
		long treasuryBefore = Treasury.balance();
		eq(FakeBridge.PlayerBuy.OK, bridge.buyNpcAsPlayer(buyer, created.listing().id()), "bought at the new price");
		eq(treasuryBefore + raised, Treasury.balance(), "proceeds to the treasury");
		ctx.detail("floor " + Money.format(floor) + " → " + Money.format(higher));
	}

	private static void fever(TestContext ctx) {
		sandbox(ctx);
		treasury(ctx, 100_000_000L);
		FakeBridge bridge = fake(ctx);
		FakePlayer seller = seller(ctx, "MarketFever");
		long now = System.currentTimeMillis();
		ItemStack diamonds = new ItemStack(net.minecraft.world.item.Items.DIAMOND, 2);
		MarketBridge.Listing l = bridge.addPlayerListing(seller.getUUID(), diamonds, cheapPrice(diamonds), now);
		MarketData.BuyPlan p = plan(l.id());
		boolean already = p.fever;
		long remaining = p.dueAt - now;
		NpcBuyers.onFeverStart(bridge, now, new Random(3));
		if (!already) eq(now + remaining / 2, p.dueAt, "running plan twice as fast");
		long due = p.dueAt;
		NpcBuyers.onFeverStart(bridge, now, new Random(3));
		eq(due, p.dueAt, "halved only once");
		long unit = ValueEngine.unitValue(diamonds).orElseThrow();
		long price = (long) Math.floor(unit * 1.2) * diamonds.getCount();
		long seed = 0;
		while (new Random(seed).nextDouble() < 0.97) seed++;
		MarketBridge.Listing dear = bridge.addPlayerListing(seller.getUUID(), diamonds, price, now);
		MarketData.get().plans.remove(dear.id());
		MarketData.get().rejected.remove(dear.id());
		NpcBuyers.consider(dear, now, new Random(seed), false);
		MarketData.Rejection r = MarketData.get().rejected.get(dear.id());
		check(r != null && r.priceRelated, "too expensive without fever (price-related)");
		MarketData.get().rejected.remove(dear.id());
		NpcBuyers.consider(dear, now, new Random(seed), true);
		MarketData.BuyPlan fp = plan(dear.id());
		isTrue(fp.fever, "planned during fever");
		check(fp.dueAt - now <= MarketConfig.get().buyerMaxDelayMinutes * 60_000L / 2, "at most 45 min during fever");
	}

	private static void sweep(TestContext ctx) {
		sandbox(ctx);
		treasury(ctx, 100_000_000L);
		FakeBridge bridge = new FakeBridge();
		FakePlayer seller = seller(ctx, "MarketSweep");
		long now = System.currentTimeMillis();
		ItemStack diamonds = new ItemStack(net.minecraft.world.item.Items.DIAMOND, 2);
		MarketBridge.Listing l = bridge.addPlayerListing(seller.getUUID(), diamonds, cheapPrice(diamonds), now);
		isFalse(MarketData.get().plans.containsKey(l.id()), "not evaluated yet without event");
		NpcBuyers.sweep(bridge, now, new Random(9));
		isTrue(MarketData.get().plans.containsKey(l.id()), "sweep plans afterwards");
		bridge.cancel(l.id());
		NpcBuyers.sweep(bridge, now, new Random(9));
		isFalse(MarketData.get().plans.containsKey(l.id()), "sweep removes vanished listings");
	}

	private static void liveIgnoresTestPlayers(TestContext ctx) {
		sandbox(ctx);
		treasury(ctx, 100_000_000L);
		FakeBridge bridge = new FakeBridge();
		Hooks.Registration reg = bridge.install(MarketFeature.LIVE_EVENTS, ValueEngine::saleFloor);
		ctx.cleanup(reg::remove);
		FakePlayer p = seller(ctx, "MarketLiveTest");
		long now = System.currentTimeMillis();
		ItemStack diamonds = new ItemStack(net.minecraft.world.item.Items.DIAMOND, 2);
		MarketBridge.Listing l = bridge.addPlayerListing(p.getUUID(), diamonds, cheapPrice(diamonds), now);
		isFalse(MarketData.get().plans.containsKey(l.id()) || MarketData.get().rejected.containsKey(l.id()),
			"test player's listing is not evaluated (no NPC purchase from the treasury)");
		NpcBuyers.sweep(bridge, now, new Random(9), true);
		isFalse(MarketData.get().plans.containsKey(l.id()), "not during the sweep either");
		NpcBuyers.sweep(bridge, now, new Random(9), false);
		isTrue(MarketData.get().plans.containsKey(l.id()), "control: without the filter it would be planned");
		bridge.cancel(l.id());
		isFalse(MarketData.get().plans.containsKey(l.id()), "cancel cleans up");
		MarketBridge.Created npc = bridge.createNpcListing(diamonds, 1, 3_600_000L, NpcSellers.NAME_PREFIX + "Test", MarketBridge.SOURCE);
		isTrue(npc.ok(), "NPC offer created");
		long id = npc.listing().id();
		EconomyService.deposit(p.getUUID(), Math.min(Money.MAX / 2, npc.listing().price() * 2 + 100));
		long sales = MarketData.get().npcSales;
		long saleCents = MarketData.get().npcSaleCents;
		FakeBridge.PlayerBuy res = bridge.buyNpcAsPlayer(p, id);
		if (res == FakeBridge.PlayerBuy.PRICE_CHANGED) res = bridge.buyNpcAsPlayer(p, id);
		eq(FakeBridge.PlayerBuy.OK, res, "test player buys the NPC offer");
		eq(2, Items.count(p, s -> s.is(net.minecraft.world.item.Items.DIAMOND)) + countDrops(ctx, p, diamonds), "diamonds received");
		eq(sales, MarketData.get().npcSales, "stats unchanged");
		eq(saleCents, MarketData.get().npcSaleCents, "revenue unchanged");
		check(Math.abs(Drift.current(Drift.key(net.minecraft.world.item.Items.DIAMOND), System.currentTimeMillis()) - 1.0) < EPS,
			"drift unchanged");
		eq(0, NpcSellers.activeBargains(), "no bargain booked");
	}

	private static void sellHintAmounts() {
		for (long c : new long[] {1, 99, 100, 101, 588_800, 588_840, 588_805, 123_456_789, Money.MAX}) {
			String s = Money.commandAmount(c);
			eq(c, Money.parse(s).orElse(-1), "amount " + c + " → '" + s + "'");
		}
		eq("5888", Money.commandAmount(588_800), "whole dollars without decimals");
		eq("5888.40", Money.commandAmount(588_840), "two decimals");
	}

	/** If the real auction house (/ah) runs, market must be connected, otherwise NPC buyers and sellers are silently off. */
	private static void bridgeConnected(TestContext ctx) {
		boolean auction = MarketFeature.auctionActive(ctx.server());
		MarketBridge b = MarketFeature.bridge();
		if (auction) {
			check(b != null, "auction house (/ah) is running but market is not connected, NPC buyers and sellers are OFF. Integration: "
				+ "MarketFeature#createBridge() must return new AuctionMarketAdapter()");
		}
		if (b != null) {
			isTrue(b.activeListings() != null && b.npcListings(MarketBridge.SOURCE) != null, "auction house responds");
			ctx.detail("connected, " + b.npcListings(MarketBridge.SOURCE).size() + " NPC offers");
		} else {
			ctx.detail("no auction house active (auction stub), market values only");
		}
	}

	private static void serviceAndConfig(TestContext ctx) {
		sandbox(ctx);
		isTrue(Services.find(Services.MarketValues.class).isPresent(), "Services.MarketValues registered");
		MarketConfig.validate(new MarketConfig());
		MarketConfig bad = new MarketConfig();
		bad.driftMin = 2;
		assertThrows(IllegalArgumentException.class, () -> MarketConfig.validate(bad), "driftMin > 1 rejected");
		MarketConfig bad2 = new MarketConfig();
		bad2.buyerMinDelayMinutes = 100;
		assertThrows(IllegalArgumentException.class, () -> MarketConfig.validate(bad2), "min > max rejected");
		MarketConfig bad3 = new MarketConfig();
		bad3.buyerSellerDayShare = bad3.buyerSellerHourShare / 2;
		assertThrows(IllegalArgumentException.class, () -> MarketConfig.validate(bad3), "day share < hour share rejected");
		MarketConfig bad4 = new MarketConfig();
		bad4.buyerSellerHourShare = 0;
		assertThrows(IllegalArgumentException.class, () -> MarketConfig.validate(bad4), "hour share 0 rejected");
		MarketConfig defaults = new MarketConfig();
		isFalse(defaults.buyerPaysEnchantments || defaults.buyerPaysFixedValues, "default: NPC buyers do not pay for enchantments/fixed values");
		var registries = ctx.server().registryAccess();
		int n = 0;
		for (MarketConfig.PoolEntry e : MarketConfig.get().pool) {
			for (String id : e.items) {
				MarketConfig.PoolEntry single = new MarketConfig.PoolEntry();
				single.items = List.of(id);
				single.enchantment = e.enchantment;
				single.level = e.level;
				ItemStack s = NpcSellers.buildStack(single, new Random(1), registries).orElseThrow();
				isTrue(ValueEngine.unitValue(s).isPresent(), "trader good " + NpcSellers.offerKey(s) + " has a market value");
				n++;
			}
		}
		ctx.detail(n + " trader goods valued");
	}

	private static Set<Item> bargainItems() {
		Set<Item> items = new java.util.LinkedHashSet<>();
		for (MarketConfig.PoolEntry e : MarketConfig.get().pool) {
			if (e.enchantment == null || e.enchantment.isBlank()) e.items.forEach(id -> Items.byId(id).ifPresent(items::add));
		}
		items.addAll(List.of(net.minecraft.world.item.Items.IRON_INGOT, net.minecraft.world.item.Items.GOLD_INGOT,
			net.minecraft.world.item.Items.COPPER_INGOT, net.minecraft.world.item.Items.COAL, net.minecraft.world.item.Items.REDSTONE,
			net.minecraft.world.item.Items.LAPIS_LAZULI, net.minecraft.world.item.Items.NETHERITE_INGOT, net.minecraft.world.item.Items.WHEAT,
			net.minecraft.world.item.Items.SLIME_BALL, net.minecraft.world.item.Items.BONE));
		return items;
	}

	/**
	 * What /sell brings for the stack at most, computed independently of ValueEngine: directly, or through one recipe or
	 * conversion step. A bargain cheaper than this is a profit.
	 */
	private static long sellPayout(UUID who, ItemStack stack) {
		Item item = stack.getItem();
		double best = PriceGuard.effectiveSellPrice(who, item).orElse(0);
		double own = maxPayout(ValueEngine.NEUTRAL, item);
		for (ValueEngine.Use u : ValueEngine.model().uses(item)) {
			long out = PriceGuard.effectiveSellPrice(who, u.out()).orElse(0);
			if (out <= 0) continue;
			double others = 0;
			double othersValue = 0;
			for (List<Item> slot : u.others()) {
				long min = Long.MAX_VALUE;
				long minValue = Long.MAX_VALUE;
				for (Item i : slot) {
					min = Math.min(min, PriceGuard.effectiveSellPrice(who, i).orElse(0));
					minValue = Math.min(minValue, maxPayout(ValueEngine.NEUTRAL, i));
				}
				others += min == Long.MAX_VALUE ? 0 : min;
				othersValue += minValue == Long.MAX_VALUE ? 0 : minValue;
			}
			// Side ingredient (< 50 % of the value, e.g. gold in a netherite ingot): the recipe's added value is available to anyone with the
			// other ingredients, mined gold as much as bargain gold at /sell price (see ValueEngine.Payouts#sellConversion)
			if (u.k() * own < 0.5 * (u.k() * own + othersValue)) continue;
			best = Math.max(best, (u.count() * (double) out - others) / u.k());
		}
		double total = best * stack.getCount();
		return total >= Money.MAX ? Money.MAX : (long) Math.floor(total + 1e-9);
	}

	private static MarketConfig tweak(TestContext ctx) {
		MarketConfig c = MarketConfig.get();
		String json = DataStore.gson().toJson(c);
		ctx.cleanup(() -> {
			MarketConfig back = DataStore.gson().fromJson(json, MarketConfig.class);
			c.bargainChance = back.bargainChance;
			c.bargainDailyBudget = back.bargainDailyBudget;
			c.bargainMaxActive = back.bargainMaxActive;
			c.bargainHiddenChance = back.bargainHiddenChance;
			c.sellerMinOffers = back.sellerMinOffers;
			c.sellerMaxOffers = back.sellerMaxOffers;
		});
		return c;
	}

	/**
	 * Bargains may be far below the normal NPC price, but /sell must never be a risk-free instant profit, directly or through
	 * recipes. Checked for all unmodified trader goods and more raw materials, neutral and with the highest sell boosts (Tycoon ×
	 * market day), with random crash/hype combinations, now and after the events end, plus real bargains from {@code
	 * NpcSellers.createOffer} bought by a boosted player.
	 */
	private static void bargainNeverBelowSell(TestContext ctx) {
		sandbox(ctx);
		treasury(ctx, 100_000_000L);
		UUID boosted = boosted(ctx, "MarketBargainSell");
		List<UUID> buyers = List.of(ValueEngine.NEUTRAL, boosted);
		double[] factors = {0.5, 1.0, 2.0, 3.0};
		Random rng = new Random(20261010L);
		int checked = 0;
		for (Item item : bargainItems()) {
			if (ValueEngine.value(item).isEmpty() && BasePrices.sellPrice(item).isEmpty()) continue;
			int n = Math.min(16, item.getDefaultMaxStackSize());
			ItemStack stack = new ItemStack(item, n);
			for (int round = 0; round < 3; round++) {
				PriceModifiers.set(SOURCE, item, factors[rng.nextInt(factors.length)], 0, 60_000, 0, "Selftest");
				for (ValueEngine.Use u : ValueEngine.model().uses(item)) {
					if (u.out() != item && rng.nextBoolean()) PriceModifiers.set(SOURCE, u.out(), factors[rng.nextInt(factors.length)], 0, 60_000, 0, "Selftest");
				}
				ValueEngine.invalidate();
				long listing = ValueEngine.bargainFloor(null, stack);
				long[] floors = new long[buyers.size()];
				for (int i = 0; i < buyers.size(); i++) {
					UUID who = buyers.get(i);
					floors[i] = ValueEngine.bargainFloor(who, stack);
					long sell = sellPayout(who, stack);
					check(floors[i] > sell, Items.id(item) + (who == boosted ? " (boosts)" : "") + ": bargain floor " + floors[i]
						+ " > /sell now " + sell);
				}
				check(listing > sellPayout(ValueEngine.NEUTRAL, stack), Items.id(item) + ": floor on listing above /sell");
				check(listing >= floors[0], Items.id(item) + ": on listing with margin (" + listing + " ≥ " + floors[0] + ")");
				PriceModifiers.removeSource(SOURCE);
				ValueEngine.invalidate();
				for (int i = 0; i < buyers.size(); i++) {
					long later = sellPayout(buyers.get(i), stack);
					check(floors[i] > later, Items.id(item) + ": after the events end /sell " + later + " < " + floors[i]);
				}
				checked++;
			}
		}
		check(checked > 50, "enough cases (" + checked + ")");
		ItemStack blocks = new ItemStack(net.minecraft.world.item.Items.IRON_BLOCK, 8);
		long nine = 8L * 9 * PriceGuard.effectiveSellPrice(boosted, net.minecraft.world.item.Items.IRON_INGOT).orElse(0);
		check(ValueEngine.bargainFloor(boosted, blocks) > nine, "8 iron blocks as bargain > 72 ingots via /sell (" + nine + ")");
		MarketConfig c = tweak(ctx);
		c.bargainDailyBudget = Money.MAX / 4;
		c.bargainMaxActive = 200;
		FakeBridge bridge = fake(ctx);
		FakePlayer buyer = ctx.player("MarketBargainSell");
		EconomyService.ensureAccount(buyer.getUUID(), "MarketBargainSell");
		Random r2 = new Random(77);
		int bargains = 0;
		int bought = 0;
		long now = System.currentTimeMillis();
		for (int i = 0; i < 400 && bargains < 60; i++) {
			var created = NpcSellers.createOffer(bridge, now, r2, bridge.npcListings(MarketBridge.SOURCE), true);
			if (created.isEmpty() || !created.get().bargain()) continue;
			MarketBridge.Listing l = created.get();
			bargains++;
			ItemStack st = l.item();
			check(l.price() >= ValueEngine.bargainFloor(null, st), "bargain ≥ floor on listing");
			check(l.price() < ValueEngine.saleFloor(null, st), "bargain below the normal floor (" + l.price() + ")");
			if (Items.isPristine(st)) check(l.price() > sellPayout(ValueEngine.NEUTRAL, st), NpcSellers.offerKey(st) + ": above /sell");
			if (bought < 25 && Items.isPristine(st)) {
				EconomyService.set(buyer.getUUID(), Money.MAX / 8);
				FakeBridge.PlayerBuy res = bridge.buyNpcAsPlayer(buyer, l.id());
				if (res == FakeBridge.PlayerBuy.PRICE_CHANGED) res = bridge.buyNpcAsPlayer(buyer, l.id());
				eq(FakeBridge.PlayerBuy.OK, res, "player with boosts buys " + NpcSellers.offerKey(st));
				long paid = Money.MAX / 8 - EconomyService.balance(buyer.getUUID());
				long sell = sellPayout(boosted, st);
				check(paid > sell, NpcSellers.offerKey(st) + ": paid " + paid + " > /sell with boosts " + sell);
				bought++;
			}
		}
		check(bargains >= 20, "enough real bargains created (" + bargains + ")");
		check(bought >= 10, "enough bought (" + bought + ")");
		ctx.detail(checked + " floor cases, " + bargains + " bargains, " + bought + " bought with boosts");
	}

	/**
	 * Flip profit is bounded: a perfect player buys every bargain for 3 days while the seller attempts a bargain with every offer
	 * (worst case). The highest achievable profit stays below {@code bargainDailyBudget} in every 24 h window, never more than
	 * {@code bargainMaxActive} at once, and expired bargains return their budget.
	 */
	private static void bargainFlipBudget(TestContext ctx) {
		sandbox(ctx);
		treasury(ctx, 100_000_000L);
		MarketConfig c = MarketConfig.get();
		FakeBridge bridge = fake(ctx);
		long start = System.currentTimeMillis();
		long[] clock = {start};
		bridge.clock = () -> clock[0];
		MarketData.get().bargains.clear();
		Random rng = new Random(424242L);
		UUID flipper = boosted(ctx, "MarketFlipper");
		List<long[]> sold = new java.util.ArrayList<>();
		long step = 10 * 60_000L;
		int maxActive = 0;
		long maxWindow = 0;
		long total = 0;
		for (long t = start; t < start + 3 * NpcSellers.DAY_MS; t += step) {
			clock[0] = t;
			for (MarketBridge.Listing l : bridge.npcListings(MarketBridge.SOURCE)) {
				if (l.expiresAt() <= t) bridge.cancel(l.id());
			}
			NpcSellers.sweepBargains(bridge, t);
			var created = NpcSellers.createOffer(bridge, t, rng, bridge.npcListings(MarketBridge.SOURCE), true);
			if (created.isPresent() && !created.get().bargain()) bridge.cancel(created.get().id());
			maxActive = Math.max(maxActive, NpcSellers.activeBargains());
			check(NpcSellers.activeBargains() <= c.bargainMaxActive, "at most " + c.bargainMaxActive + " bargains at once");
			for (MarketBridge.Listing l : bridge.npcListings(MarketBridge.SOURCE)) {
				if (!l.bargain() || (l.id() % 2 == 0 && t - l.createdAt() < 3_600_000L)) continue;
				// Computed independently: highest system payment right now for a boosted player (/sell, NPC buyer during fever, NPC order, with the
				// drift caused by earlier purchases) minus price.
				// Fixed collector values: no /sell price, NPC buyers do not pay them, NPC orders and missions only take items with a /sell price, so 0.
				// Enchanted books: NPC buyers pay no enchantment bonus, so 0.
				Item it = l.item().getItem();
				boolean fixed = ValueEngine.fixedValue(it) && !c.buyerPaysFixedValues;
				long pay = Items.isPristine(l.item()) && !fixed ? ValueEngine.saturatingMul(maxPayout(flipper, it), l.count()) : 0;
				long profit = Math.max(0, pay - l.price());
				check(profit <= ValueEngine.bargainPotential(l.item(), l.price()) + 1, NpcSellers.offerKey(l.item()) + ": profit " + profit
					+ " ≤ booked potential");
				NpcSellers.onSold(l, flipper, l.price(), t);
				bridge.removeSilently(l.id());
				sold.add(new long[] {t, profit});
				total += profit;
			}
			long window = 0;
			for (long[] e : sold) {
				if (t - e[0] < NpcSellers.DAY_MS) window += e[1];
			}
			maxWindow = Math.max(maxWindow, window);
			check(window <= c.bargainDailyBudget, "flip profit of the last 24 h " + window + " ≤ budget " + c.bargainDailyBudget);
			check(NpcSellers.budgetUsed(t) <= c.bargainDailyBudget, "booked budget ≤ cap");
		}
		check(sold.size() >= 5, "bargains occur (" + sold.size() + " in 3 days)");
		check(total <= 3 * c.bargainDailyBudget, "3 days at most 3 × budget (" + total + ")");
		long t = start + 10 * NpcSellers.DAY_MS;
		clock[0] = t;
		NpcSellers.sweepBargains(bridge, t);
		eq(0L, NpcSellers.budgetUsed(t), "budget free again after 24 h without a sale");
		var one = NpcSellers.createOffer(bridge, t, new Random(5), bridge.npcListings(MarketBridge.SOURCE), true);
		int tries = 0;
		while ((one.isEmpty() || !one.get().bargain()) && tries++ < 50) one = NpcSellers.createOffer(bridge, t, new Random(tries), List.of(), true);
		if (one.isPresent() && one.get().bargain()) {
			check(NpcSellers.budgetUsed(t) > 0 || ValueEngine.bargainPotential(one.get().item(), one.get().price()) == 0, "active bargain holds budget");
			bridge.cancel(one.get().id());
			eq(0L, NpcSellers.budgetUsed(t), "expired/removed -> budget back");
		}
		ctx.detail(sold.size() + " bargains in 3 days, flip profit max. " + Money.format(maxWindow) + "/24 h (cap "
			+ Money.format(c.bargainDailyBudget) + "), total " + Money.format(total) + ", max. " + maxActive + " at once");
	}

	/**
	 * Normal offers keep their floor against every money loop: above every system payment (/sell with boosts, NPC buyers during
	 * fever, NPC orders).
	 */
	private static void normalOffersKeepFloor(TestContext ctx) {
		sandbox(ctx);
		treasury(ctx, 100_000_000L);
		UUID boosted = boosted(ctx, "MarketNormalFloor");
		FakeBridge bridge = fake(ctx);
		Random rng = new Random(9090);
		int checked = 0;
		long now = System.currentTimeMillis();
		for (int i = 0; i < 300; i++) {
			var created = NpcSellers.createOffer(bridge, now, rng, List.of(), false);
			if (created.isEmpty()) continue;
			MarketBridge.Listing l = created.get();
			isFalse(l.bargain(), "no bargain unless requested");
			ItemStack st = l.item();
			check(l.price() >= ValueEngine.saleFloor(null, st), NpcSellers.offerKey(st) + ": ≥ normal floor");
			if (Items.isPristine(st)) {
				long payNeutral = ValueEngine.saturatingMul(maxPayout(ValueEngine.NEUTRAL, st.getItem()), st.getCount());
				check(l.price() > payNeutral, NpcSellers.offerKey(st) + ": offer " + l.price() + " > highest system payment " + payNeutral);
				long paid = Math.max(l.price(), bridge.npcSaleFloor(boosted, st));
				long payBoosted = ValueEngine.saturatingMul(maxPayout(boosted, st.getItem()), st.getCount());
				check(paid > payBoosted, NpcSellers.offerKey(st) + ": with boosts " + paid + " > highest system payment " + payBoosted);
			}
			bridge.removeSilently(l.id());
			checked++;
		}
		check(checked > 200, "enough normal offers (" + checked + ")");
		ctx.detail(checked + " normal offers above the floor");
	}

	/**
	 * Items bought from NPC sellers, bargains included, are ordinary items: no marker or lock, sellable via /sell (without
	 * profit) and to NPC buyers.
	 */
	private static void flipAllowed(TestContext ctx) {
		sandbox(ctx);
		treasury(ctx, 100_000_000L);
		FakeBridge bridge = fake(ctx);
		FakePlayer p = seller(ctx, "MarketFlip");
		Item diamond = net.minecraft.world.item.Items.DIAMOND;
		MarketBridge.Listing[] out = new MarketBridge.Listing[1];
		String error = NpcSellers.createBargainNow(bridge, new ItemStack(diamond, 8), System.currentTimeMillis(), out);
		check(error == null, "bargain created (" + error + ")");
		MarketBridge.Listing l = out[0];
		isTrue(l.bargain() && !l.note().isEmpty(), "marked as bargain (" + l.note() + ")");
		check(l.price() < ValueEngine.saleFloor(null, l.item()), "below the normal NPC price");
		EconomyService.set(p.getUUID(), l.price() * 2);
		long soldBefore = MarketData.get().bargainsSold;
		FakeBridge.PlayerBuy res = bridge.buyNpcAsPlayer(p, l.id());
		if (res == FakeBridge.PlayerBuy.PRICE_CHANGED) res = bridge.buyNpcAsPlayer(p, l.id());
		eq(FakeBridge.PlayerBuy.OK, res, "bought");
		long paid = l.price() * 2 - EconomyService.balance(p.getUUID());
		eq(soldBefore + 1, MarketData.get().bargainsSold, "stats: bargain sold");
		isTrue(MarketData.get().bargains.get(l.id()).soldAt > 0, "counts toward the flip budget for 24 h");
		int got = Items.count(p, s -> s.is(diamond)) + countDrops(ctx, p, new ItemStack(diamond));
		eq(8, got, "8 diamonds received");
		isTrue(Items.count(p, s -> s.is(diamond) && Items.isPristine(s)) == Items.count(p, s -> s.is(diamond)), "unmodified (pristine)");
		isFalse(Items.count(p, s -> s.is(diamond) && dev.smpeconomy.core.TraderGoods.isMarked(s)) > 0, "no trader goods marker");
		long sell = 8L * PriceGuard.effectiveSellPrice(p.getUUID(), diamond).orElse(0);
		check(sell > 0 && sell < paid, "/sell possible, but without profit (" + sell + " < " + paid + ")");
		ItemStack resale = new ItemStack(diamond, 8);
		long price = cheapPrice(resale);
		MarketBridge.Listing ah = bridge.addPlayerListing(p.getUUID(), resale, price, System.currentTimeMillis());
		MarketData.BuyPlan plan = plan(ah.id());
		long before = EconomyService.balance(p.getUUID());
		eq(NpcBuyers.Outcome.BOUGHT, NpcBuyers.execute(bridge, plan, plan.dueAt), "NPC buyer buys the resale");
		eq(before + price - bridge.taxFor(price), EconomyService.balance(p.getUUID()), "seller gets price minus tax");
		ctx.detail("bargain " + Money.format(paid) + " -> /sell " + Money.format(sell) + ", NPC buyer " + Money.format(price));
	}

	private static void configMigration() {
		var gson = DataStore.gson();
		com.google.gson.JsonObject old = gson.toJsonTree(new MarketConfig()).getAsJsonObject();
		old.remove("defaultsVersion");
		old.addProperty("sellerMinOffers", 8);
		old.addProperty("sellerMaxOffers", 15);
		old.addProperty("buyBackLockHours", 24);
		old.addProperty("bargainChance", 0.12);
		old.add("pool", gson.toJsonTree(MarketConfig.legacyPoolR3()));
		MarketConfig a = gson.fromJson(old, MarketConfig.class);
		eq(0, a.defaultsVersion, "old file: version 0");
		MarketConfig.validate(a);
		eq(MarketConfig.DEFAULTS_VERSION, a.defaultsVersion, "version set");
		eq(new MarketConfig().sellerMinOffers, a.sellerMinOffers, "8 -> new default");
		eq(new MarketConfig().sellerMaxOffers, a.sellerMaxOffers, "15 -> new default");
		isTrue(MarketConfig.samePool(a.pool, new MarketConfig().pool), "old pool -> new pool");
		eq(new MarketConfig().bargainChance, a.bargainChance, "bargain chance 0.12 -> new default");
		isFalse(gson.toJsonTree(a).getAsJsonObject().has("buyBackLockHours"), "buy-back lock is gone");
		com.google.gson.JsonObject custom = old.deepCopy();
		custom.addProperty("sellerMinOffers", 10);
		custom.addProperty("sellerMaxOffers", 20);
		com.google.gson.JsonArray pool = gson.toJsonTree(MarketConfig.legacyPoolR3()).getAsJsonArray();
		pool.remove(0);
		custom.add("pool", pool);
		MarketConfig b = gson.fromJson(custom, MarketConfig.class);
		MarketConfig.validate(b);
		eq(10, b.sellerMinOffers, "custom value stays (min)");
		eq(20, b.sellerMaxOffers, "custom value stays (max)");
		eq(MarketConfig.legacyPoolR3().size() - 1, b.pool.size(), "custom pool stays");
		com.google.gson.JsonObject older = old.deepCopy();
		older.add("pool", gson.toJsonTree(MarketConfig.legacyPoolR2()));
		MarketConfig o = gson.fromJson(older, MarketConfig.class);
		MarketConfig.validate(o);
		isTrue(MarketConfig.samePool(o.pool, new MarketConfig().pool), "pool with emeralds -> new pool");
		MarketConfig cfg = gson.fromJson(old, MarketConfig.class);
		cfg.defaultsVersion = MarketConfig.DEFAULTS_VERSION;
		MarketConfig.validate(cfg);
		eq(8, cfg.sellerMinOffers, "values set after the migration stay");
		MarketConfig fresh = new MarketConfig();
		MarketConfig.validate(fresh);
		eq(MarketConfig.DEFAULTS_VERSION, fresh.defaultsVersion, "new file gets the current version");
	}

	/**
	 * Goods without a pivot that news may hype would allow "buy NPC offer, sell via /sell or to NPC orders during the hype". So
	 * market reports its goods through {@link MarketGoods} ({@link ShockGuard}): goods without a pivot, their recipe estimate
	 * ingredients and recipe products, but not shop items (the pivot caps every payment). An active offer outside the pool counts
	 * too. The default pool avoids news materials, otherwise those news would disappear.
	 */
	private static void shockGuard(TestContext ctx) {
		sandbox(ctx);
		ctx.cleanup(ShockGuard::invalidate);
		Item copperBlock = net.minecraft.world.item.Items.COPPER_BLOCK;
		Item diamondBlock = net.minecraft.world.item.Items.DIAMOND_BLOCK;
		Item blazeRod = net.minecraft.world.item.Items.BLAZE_ROD;
		Item goldenApple = net.minecraft.world.item.Items.GOLDEN_APPLE;
		Item enderEye = net.minecraft.world.item.Items.ENDER_EYE;
		isTrue(PriceGuard.pivotPrice(blazeRod) >= 0 && PriceGuard.pivotPrice(copperBlock) < 0, "test assumption: blaze rod in the shop, copper block not");
		Set<String> ids = ShockGuard.compute(List.of(copperBlock, diamondBlock, blazeRod, goldenApple, enderEye));
		isTrue(ids.contains("minecraft:copper_block") && ids.contains("minecraft:copper_ingot"), "copper block and ingot (recipe) reported");
		isTrue(ids.contains("minecraft:diamond_block") && ids.contains("minecraft:diamond"), "diamond block and diamonds reported");
		isFalse(ids.contains("minecraft:blaze_rod"), "shop item (pivot) not reported");
		int estimates = 0;
		if (ValueEngine.model().source(goldenApple) == ValueEngine.Source.RECIPE) {
			isTrue(ids.contains("minecraft:golden_apple") && ids.contains("minecraft:gold_ingot") && ids.contains("minecraft:apple"),
				"golden apple and its recipe estimate ingredients");
			estimates++;
		}
		if (ValueEngine.model().source(enderEye) == ValueEngine.Source.RECIPE) {
			isTrue(ids.contains("minecraft:ender_pearl") && ids.contains("minecraft:blaze_powder"), "ender eye <- ender pearl + blaze powder");
			estimates++;
		}
		isTrue(estimates > 0, "at least one recipe estimate checked");
		Set<String> live = Services.find(MarketGoods.class).orElseThrow(() -> new AssertionError("MarketGoods service missing")).shockSensitiveIds();
		int pool = 0;
		for (Item g : ShockGuard.poolGoods(MarketConfig.get())) {
			if (PriceGuard.pivotPrice(g) >= 0) continue;
			isTrue(live.contains(Items.id(g)), "pool good reported: " + Items.id(g));
			pool++;
		}
		isTrue(pool > 10, "pool goods without pivot checked (" + pool + ")");
		// An active NPC offer outside the pool counts (goods removed from the pool stay protected until expiry). Checked through the
		// registered service, the way news asks, with a test AH and clock. The TTL marker starts at Long.MIN_VALUE, so "now - listedAt"
		// must not overflow and skip the read.
		FakeBridge bridge = fake(ctx);
		Item shell = net.minecraft.world.item.Items.SHULKER_SHELL;
		String shellId = Items.id(shell);
		isTrue(PriceGuard.pivotPrice(shell) < 0 && !ShockGuard.poolGoods(MarketConfig.get()).contains(shell),
			"test assumption: shulker shell neither in the shop nor in the trader pool");
		MarketGoods service = Services.find(MarketGoods.class).orElseThrow();
		isTrue(service == ShockGuard.SERVICE, "registered service is ShockGuard");
		long[] now = {1_000_000_000L};
		ctx.cleanup(ShockGuard.testHooks(bridge, () -> now[0]));
		isFalse(service.shockSensitiveIds().contains(shellId), "test assumption: no shulker shell reported without a listing");
		MarketBridge.Created created = bridge.createNpcListing(new ItemStack(shell, 2), 1_000_000L, 3_600_000L, NpcSellers.NAME_PREFIX + "Test",
			MarketBridge.SOURCE, false, "");
		isTrue(created.ok(), "test listing created (" + created.error() + ")");
		isTrue(ShockGuard.listedGoods(bridge).contains(shell), "active NPC offer counts as goods");
		now[0] += ShockGuard.LISTINGS_TTL_MS - 1;
		isFalse(service.shockSensitiveIds().contains(shellId), "within the TTL the last read applies");
		now[0] += 1;
		isTrue(service.shockSensitiveIds().contains(shellId), "reread after the TTL: listing outside the pool reported");
		isTrue(bridge.removeNpcListing(created.listing().id()), "test listing removed");
		ShockGuard.invalidate();
		isFalse(service.shockSensitiveIds().contains(shellId), "reread right after invalidate: listing gone");
		MarketBridge.Created again = bridge.createNpcListing(new ItemStack(shell, 1), 1_000_000L, 3_600_000L, NpcSellers.NAME_PREFIX + "Test",
			MarketBridge.SOURCE, false, "");
		isTrue(again.ok(), "second test listing created (" + again.error() + ")");
		ShockGuard.invalidate();
		isTrue(service.shockSensitiveIds().contains(shellId), "first call after invalidate reads the listings");
		isTrue(bridge.removeNpcListing(again.listing().id()), "second test listing removed");
		now[0] -= 60_000L;
		isFalse(service.shockSensitiveIds().contains(shellId), "clock runs backwards -> reread");
		Set<String> defaults = ShockGuard.compute(ShockGuard.poolGoods(new MarketConfig()));
		for (String id : List.of("minecraft:copper_ingot", "minecraft:copper_block", "minecraft:raw_copper", "minecraft:lapis_lazuli",
			"minecraft:lapis_block", "minecraft:quartz", "minecraft:amethyst_shard", "minecraft:shulker_shell", "minecraft:breeze_rod",
			"minecraft:ender_pearl", "minecraft:coal", "minecraft:redstone", "minecraft:slime_ball", "minecraft:pumpkin")) {
			isFalse(defaults.contains(id), "default pool blocks news material: " + id);
		}
		ctx.detail(live.size() + " items reported to news (" + pool + " pool goods without pivot), default pool leaves news materials free");
	}

	/**
	 * {@code /market} shows the lowest price a seller really lists at: lowest normal price × {@code bargainPriceMin}, never below
	 * the floor, otherwise no line. The bare bargain floor would read $0.01 for books and collector items without a /sell price.
	 */
	private static void guiBargainLine(TestContext ctx) {
		sandbox(ctx);
		MarketConfig c = MarketConfig.get();
		FakePlayer p = seller(ctx, "MarketGuiBargain");
		var registries = ctx.server().registryAccess();
		java.util.regex.Pattern line = java.util.regex.Pattern.compile("Bargains \\(rare\\) from (\\$[\\d,.]+) each");
		int shown = 0;
		int checked = 0;
		for (MarketConfig.PoolEntry e : c.pool) {
			for (String id : e.items) {
				MarketConfig.PoolEntry single = new MarketConfig.PoolEntry();
				single.items = List.of(id);
				single.enchantment = e.enchantment;
				single.level = e.level;
				ItemStack stack = NpcSellers.buildStack(single, new Random(7), registries).orElseThrow();
				OptionalLong unit = ValueEngine.unitValue(stack);
				if (unit.isEmpty()) continue;
				long floor = ValueEngine.saleFloor(null, stack);
				long lowest = NpcSellers.lowestBargainPrice(stack, unit.getAsLong(), floor, c);
				long normalMin = Math.max(floor, (long) Math.ceil(unit.getAsLong() * c.offerPriceMin - 1e-9));
				String key = NpcSellers.offerKey(stack);
				check(lowest >= (long) Math.ceil(normalMin * c.bargainPriceMin - 1e-9), key + ": from-price " + lowest + " ≥ lowest normal price × "
					+ c.bargainPriceMin);
				check(lowest >= ValueEngine.bargainFloor(null, stack), key + ": from-price ≥ bargain floor");
				String lore = loreText(MarketGui.render(p, stack, Map.of()).asStack());
				java.util.regex.Matcher m = line.matcher(lore);
				if (NpcSellers.isRealBargain(lowest, floor, c)) {
					check(m.find() && m.group(1).equals(Money.format(lowest)), key + ": line shows " + Money.format(lowest) + " (" + lore.replace('\n', '|') + ")");
					shown++;
				} else {
					isFalse(m.find(), key + ": no real bargain -> no line");
				}
				isFalse(lore.contains("from $0.01"), key + ": never \"from $0.01\"");
				checked++;
			}
		}
		isTrue(shown > 10 && checked > 50, "enough goods checked (" + shown + " with bargain line, " + checked + " total)");
		ctx.detail(checked + " goods in /market, " + shown + " with real bargain line");
	}

	private static String loreText(ItemStack stack) {
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore == null) return "";
		StringBuilder sb = new StringBuilder();
		for (Component c : lore.lines()) sb.append(c.getString()).append('\n');
		return sb.toString();
	}
}
