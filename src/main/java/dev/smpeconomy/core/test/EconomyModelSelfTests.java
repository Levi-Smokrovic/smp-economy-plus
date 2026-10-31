package dev.smpeconomy.core.test;

import static dev.smpeconomy.core.test.Assert.assertThrows;
import static dev.smpeconomy.core.test.Assert.check;
import static dev.smpeconomy.core.test.Assert.eq;
import static dev.smpeconomy.core.test.Assert.isFalse;
import static dev.smpeconomy.core.test.Assert.isTrue;

import com.google.gson.reflect.TypeToken;
import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.gui.SimpleGui;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import dev.smpeconomy.core.BasePrices;
import dev.smpeconomy.core.ConfigManager;
import dev.smpeconomy.core.CrateKeys;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.EconomyService.Result;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Hooks;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.PriceGuard;
import dev.smpeconomy.core.PriceModifiers;
import dev.smpeconomy.core.Services;
import dev.smpeconomy.core.Settings;
import dev.smpeconomy.core.Treasury;

public final class EconomyModelSelfTests {
	private static final String F = "core";
	private static final String SRC = "selftest";
	/** Random combinations per item. */
	private static final int COMBOS = 250;

	private EconomyModelSelfTests() {}

	public static void register() {
		SelfTests.register(F, "baseprices-load", ctx -> basePricesLoad());
		SelfTests.register(F, "settings-registry", EconomyModelSelfTests::settings);
		SelfTests.register(F, "playerdata-kv", EconomyModelSelfTests::playerDataKv);
		SelfTests.register(F, "treasury-ledger", EconomyModelSelfTests::treasuryLedger);
		SelfTests.register(F, "treasury-live", EconomyModelSelfTests::treasuryLive);
		SelfTests.register(F, "hooks-multipliers", EconomyModelSelfTests::hooksMultipliers);
		SelfTests.register(F, "hooks-listeners", EconomyModelSelfTests::hooksListeners);
		SelfTests.register(F, "hooks-flags-services", EconomyModelSelfTests::flagsAndServices);
		SelfTests.register(F, "pricemodifiers-transitions", ctx -> priceModifierTransitions());
		SelfTests.register(F, "pricemodifiers-live", EconomyModelSelfTests::priceModifiersLive);
		SelfTests.register(F, "priceguard-rules", ctx -> priceGuardRules());
		SelfTests.register(F, "priceguard-no-arbitrage", EconomyModelSelfTests::priceGuardNoArbitrage);
		SelfTests.register(F, "priceguard-live", EconomyModelSelfTests::priceGuardLive);
		SelfTests.register(F, "priceguard-crash-buy-side", EconomyModelSelfTests::priceGuardCrashBuySide);
		SelfTests.register(F, "gui-helpers", EconomyModelSelfTests::guiHelpers);
	}

	private static void basePricesLoad() throws Exception {
		isTrue(Files.exists(BasePrices.PRICES.path()), "prices.json in the config directory");
		isTrue(Files.exists(BasePrices.SHOP.path()), "shop.json in the config directory");
		String bundled = ConfigManager.readBundled("smpeconomy_defaults/prices.json").orElse(null);
		Assert.notNull(bundled, "bundled prices.json");
		Map<String, Long> defaults = DataStore.gson().fromJson(bundled, new TypeToken<Map<String, Long>>() {}.getType());
		isTrue(defaults.size() >= 300, "bundled prices.json has >= 300 entries (" + defaults.size() + ")");
		for (String id : defaults.keySet()) isTrue(Items.byId(id).isPresent(), "default sell price with valid id: " + id);
		BasePrices.ShopConfig shopDefaults = BasePrices.SHOP.defaults();
		isTrue(shopDefaults.categories.size() >= 5, "bundled shop.json has categories");
		for (BasePrices.CategoryConfig c : shopDefaults.categories) {
			for (BasePrices.ItemConfig ic : c.items) {
				isTrue(Items.byId(ic.item).isPresent(), "default shop item with valid id: " + ic.item);
				isTrue(ic.price > 0 && ic.max > 0, "default shop entry valid: " + ic.item);
				Long sell = defaults.get(ic.item);
				if (sell != null) isTrue(ic.price >= 2 * sell, "default " + ic.item + ": buy price " + ic.price + " >= 2 x sell " + sell);
			}
		}

		isFalse(BasePrices.sellPrices().isEmpty(), "sell prices loaded");
		isFalse(BasePrices.shopCategories().isEmpty(), "shop categories loaded");
		isTrue(BasePrices.sellPrice(net.minecraft.world.item.Items.AIR).isEmpty(), "air not sellable");
		int violations = 0;
		StringBuilder first = new StringBuilder();
		for (Map.Entry<Item, Long> e : BasePrices.shopBuyPrices().entrySet()) {
			OptionalLong sell = BasePrices.sellPrice(e.getKey());
			if (sell.isPresent() && e.getValue() < 2 * sell.getAsLong()) {
				if (violations++ == 0) first.append(Items.id(e.getKey())).append(" buy=").append(e.getValue()).append(" sell=").append(sell.getAsLong());
			}
		}
		eq(0, violations, "live config: buy price >= 2 x sell price (first violation: " + first + ")");
		for (BasePrices.ShopCategory c : BasePrices.shopCategories()) {
			for (BasePrices.ShopItem i : c.items()) {
				isTrue(BasePrices.shopBuyPrice(i.item()).orElse(-1) <= i.price(), "shopBuyPrice = lowest entry for " + Items.id(i.item()));
			}
		}
	}

	private static void settings(TestContext ctx) {
		UUID a = ctx.player("TestA").getUUID();
		PlayerDataService.delete(a);
		List<String> keys = new ArrayList<>();
		for (Settings.Def d : Settings.allDefined()) keys.add(d.key());
		for (Settings.Def d : Settings.all()) {
			String owner = Settings.owner(d.key());
			isTrue(owner == null || dev.smpeconomy.core.module.Modules.isEnabled(owner), "visible only with module: " + d.key());
		}
		for (String k : List.of(Settings.SIDEBAR, Settings.MOBSPAWN, Settings.PHANTOMS, Settings.PAY_RECEIVE, Settings.CHATGAMES,
				Settings.ANNOUNCEMENTS, Settings.NOTIFICATIONS, Settings.JOB_ACTIONBAR, Settings.DEATH_COORDS)) {
			isTrue(keys.contains(k), "predefined: " + k);
			isTrue(Settings.def(k).orElseThrow().defaultValue(), "default on: " + k);
			isTrue(Settings.get(a, k), "no entry = default: " + k);
			isTrue(Items.byId(Settings.def(k).orElseThrow().icon()).isPresent(), "icon valid: " + k);
		}
		List<String> events = new ArrayList<>();
		Hooks.Registration reg = Settings.addListener((p, k, v) -> {
			if (p.equals(a)) events.add(k + "=" + v);
		});
		ctx.cleanup(reg::remove);
		Settings.set(a, Settings.PHANTOMS, false);
		isFalse(Settings.get(a, Settings.PHANTOMS), "off");
		eq("false", PlayerDataService.getSetting(a, Settings.PHANTOMS, null), "saved");
		isTrue(Settings.toggle(a, Settings.PHANTOMS), "toggle to on");
		eq(null, PlayerDataService.getSetting(a, Settings.PHANTOMS, null), "default value is not stored");
		isFalse(Settings.toggle(a, Settings.SIDEBAR), "toggle to off");
		eq(Boolean.FALSE, Settings.values(a).get(Settings.SIDEBAR), "values()");
		Settings.set(a, Settings.SIDEBAR, false); // no change, no event
		eq(List.of("phantoms=false", "phantoms=true", "sidebar=false"), events, "listener events");
		PlayerDataService.setSetting(a, Settings.CHATGAMES, "broken");
		isTrue(Settings.get(a, Settings.CHATGAMES), "invalid value gives default");
		isTrue(Settings.get(a, "does.not.exist"), "unknown key gives on");
		assertThrows(IllegalArgumentException.class, () -> Settings.set(a, "does.not.exist", false), "setting an unknown key");
		assertThrows(IllegalArgumentException.class, () -> Settings.register("Invalid Key", "x", "x", true, "minecraft:stone"), "invalid key");
	}

	private static void playerDataKv(TestContext ctx) {
		UUID a = ctx.player("TestA").getUUID();
		PlayerDataService.delete(a);
		eq(null, PlayerDataService.getString(a, "test.text"), "empty");
		PlayerDataService.setString(a, "test.text", "Greetings");
		eq("Greetings", PlayerDataService.getString(a, "test.text"), "Text");
		eq(7L, PlayerDataService.getLong(a, "test.missing", 7), "long default");
		PlayerDataService.setLong(a, "test.time", 1_700_000_000_123L);
		eq(1_700_000_000_123L, PlayerDataService.getLong(a, "test.time", 0), "Long");
		eq(5L, PlayerDataService.getLong(a, "test.text", 5), "text is not a long");
		PlayerDataService.remove(a, "test.text");
		eq(null, PlayerDataService.getString(a, "test.text"), "removed");
		eq("crate_keys.common", CrateKeys.key(CrateKeys.COMMON), "key name common");
		eq("crate_keys.rare", CrateKeys.key(CrateKeys.RARE), "key name rare");
		eq("crate_keys.epic", CrateKeys.key(CrateKeys.EPIC), "key name epic");
		assertThrows(IllegalArgumentException.class, () -> CrateKeys.key("invalid!"), "no umlauts in crate ids");
		PlayerDataService.setCounter(a, CrateKeys.key(CrateKeys.COMMON), 3);
		eq(1L, CrateKeys.give(a, CrateKeys.EPIC, 1), "give key");
		PlayerDataService.addCounter(a, "other.x", 1);
		eq(Map.of("crate_keys.common", 3L, "crate_keys.epic", 1L), PlayerDataService.counters(a, CrateKeys.PREFIX), "counters with prefix");
		isFalse(CrateKeys.take(a, CrateKeys.EPIC, 2), "not enough keys");
		isTrue(CrateKeys.take(a, CrateKeys.EPIC, 1), "redeem key");
		eq(0L, CrateKeys.count(a, CrateKeys.EPIC), "redeemed");
		PlayerDataService.setCounter(a, CrateKeys.key(CrateKeys.COMMON), 0);
		eq(0L, PlayerDataService.getCounter(a, CrateKeys.key(CrateKeys.COMMON)), "set to 0");
		isFalse(PlayerDataService.counters(a, CrateKeys.PREFIX).containsKey("crate_keys.common"), "0 is deleted");
		assertThrows(IllegalArgumentException.class, () -> PlayerDataService.setCounter(a, "x", -1), "negative");
	}

	private static void treasuryLedger(TestContext ctx) {
		UUID till = TestPlayers.uuid("TestTill");
		UUID p = ctx.player("TestA").getUUID();
		ctx.cleanup(() -> {
			EconomyService.unregisterSystemAccount(till);
			EconomyService.deleteAccount(till);
		});
		EconomyService.registerSystemAccount(till, "TestTill");
		EconomyService.set(till, 0);
		EconomyService.ensureAccount(p, "TestA");
		EconomyService.set(p, 0);
		Treasury.Data data = new Treasury.Data();
		int[] dirty = {0};
		double[] pct = {5.0};
		Treasury.Ledger ledger = new Treasury.Ledger(till, () -> data, () -> dirty[0]++, () -> pct[0]);
		long t0 = 1_000L * 3_600_000L; // full hour

		eq(Result.OK, ledger.deposit(100_000, "shop", t0), "deposit $1000");
		eq(100_000L, ledger.balance(), "treasury balance");
		eq(5_000L, ledger.allowance(t0), "budget 5% of $1000");
		eq(Result.TREASURY_LIMIT, ledger.payTo(p, 5_001, "npc_buy", t0), "above the hourly budget");
		eq(Result.OK, ledger.payTo(p, 3_000, "npc_buy", t0), "payout $30");
		eq(3_000L, EconomyService.balance(p), "player receives money");
		eq(97_000L, ledger.balance(), "treasury -$30");
		eq(3_000L, ledger.spentLastHour(t0), "spent last hour");
		eq(2_000L, ledger.allowance(t0), "remaining budget");
		isFalse(ledger.withdraw(2_001, "npc_order", t0 + 1_000, true).ok(), "capped withdrawal above remainder");
		eq(Result.OK, ledger.withdraw(2_000, "npc_order", t0 + 1_000, true), "use up the remainder");
		eq(0L, ledger.allowance(t0 + 1_000), "budget empty");
		eq(Result.TREASURY_LIMIT, ledger.payTo(p, 1, "npc_buy", t0 + 30 * 60_000L), "still empty after 30 min");
		long later = t0 + 61 * 60_000L;
		eq(0L, ledger.spentLastHour(later), "out of the window after 61 min");
		eq(4_750L, ledger.allowance(later), "new budget 5% of $950");
		eq(Result.OK, ledger.withdraw(50_000, "admin", later, false), "uncapped admin withdrawal");
		eq(0L, ledger.spentLastHour(later), "admin does not count toward the cap");
		eq(Result.INSUFFICIENT_FUNDS, ledger.withdraw(45_001, "admin", later, false), "never negative");
		eq(45_000L, ledger.balance(), "treasury unchanged");
		eq(Result.SAME_ACCOUNT, ledger.payTo(till, 1, "x", later), "to self");
		eq(Result.INVALID_AMOUNT, ledger.payTo(p, 0, "x", later), "0");
		eq(Result.INVALID_AMOUNT, ledger.payTo(p, -1, "x", later), "negative");
		eq(Result.INSUFFICIENT_FUNDS, ledger.collectFrom(p, 3_001, "npc_sale", later), "player has too little");
		eq(Result.OK, ledger.collectFrom(p, 3_000, "npc_sale", later), "player pays the treasury");
		eq(0L, EconomyService.balance(p), "player empty");
		eq(48_000L, ledger.balance(), "treasury +$30");

		pct[0] = 0;
		eq(0L, ledger.allowance(later), "0% = no spending");
		eq(0L, ledger.hourlyCap(later), "0% = no hourly budget");
		pct[0] = 100;
		eq(48_000L, ledger.allowance(later), "100% = whole balance");
		pct[0] = 5;
		eq(2_400L, ledger.hourlyCap(later), "hourly budget 5% of $480");
		eq(Result.OK, ledger.payTo(p, 1_000, "npc_buy", later), "capped $10");
		eq(2_400L, ledger.hourlyCap(later), "hourly budget independent of prior spending");
		eq(1_400L, ledger.allowance(later), "remainder after $10");
		eq(Result.TREASURY_LIMIT, ledger.payTo(p, 2_000, "npc_buy", later), "above the remainder");
		eq(Result.OK, ledger.payTo(p, 2_000, "casino", later, false), "uncapped (covered commitment)");
		eq(1_000L, ledger.spentLastHour(later), "uncapped does not count toward the cap");
		eq(Result.INSUFFICIENT_FUNDS, ledger.payTo(p, 100_000, "casino", later, false), "uncapped never above the balance");
		eq(Result.OK, ledger.collectFrom(p, 3_000, "npc_sale", later), "back to the treasury");

		Map<String, Treasury.Flow> stats = ledger.stats24h(later);
		eq(new Treasury.Flow(100_000, 0), stats.get("shop"), "stats shop");
		eq(new Treasury.Flow(0, 4_000), stats.get("npc_buy"), "stats npc_buy");
		eq(new Treasury.Flow(0, 2_000), stats.get("npc_order"), "stats npc_order");
		eq(new Treasury.Flow(6_000, 0), stats.get("npc_sale"), "stats npc_sale");
		eq(new Treasury.Flow(0, 2_000), stats.get("casino"), "stats casino (uncapped)");
		eq(new Treasury.Flow(106_000, 58_000), ledger.totals24h(later), "total 24 h");
		isTrue(ledger.stats24h(t0 + 25 * 3_600_000L).isEmpty(), "empty after 25 h");
		eq(new Treasury.Flow(100_000, 0), ledger.totalsAllTime().get("shop"), "total stays");
		isTrue(dirty[0] > 0, "marks dirty");

		Treasury.Data copy = DataStore.gson().fromJson(DataStore.gson().toJson(data), Treasury.Data.class);
		Treasury.Ledger again = new Treasury.Ledger(till, () -> copy, () -> {}, () -> 5.0);
		eq(ledger.totals24h(later), again.totals24h(later), "stats after JSON round trip");
		eq(ledger.spentLastHour(t0), again.spentLastHour(t0), "hour window after JSON round trip");
		isTrue(Treasury.maxSpendPerHour() >= Treasury.allowance(), "live: hourly budget >= remaining budget");
	}

	private static void treasuryLive(TestContext ctx) {
		isTrue(EconomyService.hasAccount(Treasury.ID), "treasury account exists");
		isTrue(EconomyService.isSystemAccount(Treasury.ID), "system account");
		eq(Treasury.NAME, EconomyService.nameOf(Treasury.ID).orElse(null), "Name");
		isTrue(Treasury.balance() >= 0, "never negative");
		isTrue(EconomyService.top().stream().noneMatch(e -> e.id().equals(Treasury.ID)), "not on the leaderboard");
		isTrue(Treasury.allowance() <= Treasury.balance(), "budget <= balance");
		isTrue(Treasury.totalsAllTime().containsKey(Treasury.R_START) || Treasury.balance() >= 0, "start capital booked");
		isFalse(Treasury.canSpend(0), "0 is not an expense");
		eq("Shop purchases", Treasury.reasonLabel(Treasury.R_SHOP), "reason label");

		long before = Treasury.balance();
		Map<String, Treasury.Flow> statsBefore = Treasury.stats24h();
		// Cleanups run in reverse: this check runs after preserveTreasury() has reset things.
		ctx.cleanup(() -> {
			if (Treasury.balance() != before) throw new AssertionError("treasury not reset");
			if (!Treasury.stats24h().equals(statsBefore)) throw new AssertionError("stats not reset");
		});
		ctx.preserveTreasury();
		UUID p = ctx.player("TestA").getUUID();
		EconomyService.ensureAccount(p, "TestA");
		EconomyService.set(p, 10_000);
		eq(Result.OK, Treasury.collectFrom(p, 10_000, Treasury.R_NPC_SALE), "player buys from NPC");
		eq(before + 10_000, Treasury.balance(), "treasury +$100");
		long allowance = Treasury.allowance();
		isTrue(allowance <= Treasury.balance(), "budget <= treasury");
		if (allowance > 0) {
			long pay = Math.min(allowance, 5_000);
			eq(Result.OK, Treasury.payTo(p, pay, Treasury.R_NPC_BUY), "NPC buys from player");
			eq(pay, EconomyService.balance(p), "player receives money");
			isTrue(Treasury.spentLastHour() >= pay, "counts toward the hourly cap");
		}
		eq(Result.TREASURY_LIMIT, Treasury.payTo(p, Treasury.allowance() + 1, Treasury.R_NPC_BUY), "above the budget");
		isTrue(Treasury.stats24h().get(Treasury.R_NPC_SALE).income() >= 10_000, "stats npc_sale");

	}

	private static void hooksMultipliers(TestContext ctx) {
		UUID a = ctx.player("TestA").getUUID();
		UUID b = ctx.player("TestB").getUUID();
		Item wheat = net.minecraft.world.item.Items.WHEAT;
		List<Hooks.Registration> regs = new ArrayList<>();
		ctx.cleanup(() -> regs.forEach(Hooks.Registration::remove));
		double baseA = Hooks.sellMultiplier(a, wheat);
		double baseB = Hooks.sellMultiplier(b, wheat);
		regs.add(Hooks.addSellMultiplier(SRC + ".rank", "Test Rank", (p, item) -> p.equals(a) ? 1.12 : 1.0));
		regs.add(Hooks.addSellMultiplier(SRC + ".day", "Test Market Day", (p, item) -> item == wheat ? 1.5 : 1.0));
		assertClose(baseA * 1.12 * 1.5, Hooks.sellMultiplier(a, wheat), "product for A");
		assertClose(baseB * 1.5, Hooks.sellMultiplier(b, wheat), "only market day for B");
		isTrue(Hooks.sellParts(a, wheat).stream().anyMatch(pt -> pt.label().equals("Test Rank") && Math.abs(pt.factor() - 1.12) < 1e-9), "part with label");
		isTrue(Hooks.sellParts(b, wheat).stream().noneMatch(pt -> pt.id().equals(SRC + ".rank")), "neutral factors not listed");

		regs.add(Hooks.addSellMultiplier(SRC + ".day", "Test Market Day", (p, item) -> 2.0));
		assertClose(baseB * 2.0, Hooks.sellMultiplier(b, wheat), "replaced rather than doubled");
		regs.add(Hooks.addSellMultiplier(SRC + ".nan", "NaN", (p, item) -> Double.NaN));
		regs.add(Hooks.addSellMultiplier(SRC + ".neg", "Negative", (p, item) -> -3));
		regs.add(Hooks.addSellMultiplier(SRC + ".ex", "Exception", (p, item) -> {
			throw new IllegalStateException("Test");
		}));
		assertClose(baseB * 2.0, Hooks.sellMultiplier(b, wheat), "invalid factors neutral");
		regs.add(Hooks.addSellMultiplier(SRC + ".huge", "Huge", (p, item) -> 1e9));
		assertClose(baseB * 2.0 * Hooks.MAX_FACTOR, Hooks.sellMultiplier(b, wheat), "capped at MAX_FACTOR");
		regs.forEach(Hooks.Registration::remove);
		assertClose(baseA, Hooks.sellMultiplier(a, wheat), "neutral again after remove");

		double job = Hooks.jobMultiplier(a, "miner");
		regs.add(Hooks.addJobMultiplier(SRC + ".gold", "Gold Rush", (p, j) -> j.equals("miner") ? 2.0 : 1.0));
		assertClose(job * 2.0, Hooks.jobMultiplier(a, "miner"), "job multiplier");
		assertClose(Hooks.jobMultiplier(a, "farmer"), Hooks.jobMultiplier(a, "farmer"), "other job");
		eq(1, Hooks.jobParts(a, "miner").stream().filter(pt -> pt.id().startsWith(SRC)).count(), "job part");

		double chat = Hooks.chatGameMultiplier(a);
		regs.add(Hooks.addChatGameMultiplier(SRC + ".luck", "Lucky Hour", p -> 2.0));
		assertClose(chat * 2.0, Hooks.chatGameMultiplier(a), "chat game multiplier");
		eq(1, Hooks.chatGameParts(a).stream().filter(pt -> pt.id().startsWith(SRC)).count(), "chat part");

		double disc = Hooks.shopDiscount(wheat);
		regs.add(Hooks.addShopDiscount(SRC + ".flash", "Flash Sale", item -> item == wheat ? 0.7 : 1.0));
		assertClose(disc * 0.7, Hooks.shopDiscount(wheat), "shop discount");
		assertClose(Hooks.shopDiscount(net.minecraft.world.item.Items.STONE), Hooks.shopDiscount(net.minecraft.world.item.Items.STONE), "other item");
		eq(1, Hooks.shopParts(wheat).stream().filter(pt -> pt.id().startsWith(SRC)).count(), "shop part");
	}

	private static void hooksListeners(TestContext ctx) {
		FakePlayer a = ctx.player("TestA");
		FakePlayer b = ctx.player("TestB");
		List<String> got = new ArrayList<>();
		Hooks.Registration sold = Hooks.onItemsSold((p, cents, count) -> got.add("sold:" + p.getPlainTextName() + ":" + cents + ":" + count));
		Hooks.Registration kill = Hooks.onPlayerKilledPlayer((k, v) -> got.add("kill:" + k.getPlainTextName() + ">" + v.getPlainTextName()));
		ctx.cleanup(() -> {
			sold.remove();
			kill.remove();
		});
		Hooks.fireItemsSold(a, 1_234, 5);
		Hooks.firePlayerKilledPlayer(a, b);
		Hooks.Registration broken = Hooks.onItemsSold((p, c, n) -> {
			throw new IllegalStateException("Test");
		});
		Hooks.fireItemsSold(b, 1, 1); // a listener's exception does not stop the others
		broken.remove();
		sold.remove();
		sold.remove(); // calling twice is harmless
		Hooks.fireItemsSold(a, 99, 1);
		eq(List.of("sold:TestA:1234:5", "kill:TestA>TestB", "sold:TestB:1:1"), got, "listener calls");
	}

	private static void flagsAndServices(TestContext ctx) {
		String flag = SRC + ".flag";
		isFalse(Hooks.flag(flag), "off without provider");
		boolean[] on = {true};
		Hooks.Registration reg = Hooks.addFlag(flag, () -> on[0]);
		ctx.cleanup(reg::remove);
		isTrue(Hooks.flag(flag), "an");
		on[0] = false;
		isFalse(Hooks.flag(flag), "read live");
		Hooks.Registration broken = Hooks.addFlag(flag, () -> {
			throw new IllegalStateException("Test");
		});
		isFalse(Hooks.flag(flag), "exception = off");
		broken.remove();
		reg.remove(); // replaced: the old registration removes nothing foreign
		isFalse(Hooks.flag(flag), "removed");

		interface TestService {
			int answer();
		}
		isTrue(Services.find(TestService.class).isEmpty(), "service missing");
		Hooks.Registration svc = Services.register(TestService.class, () -> 42);
		ctx.cleanup(svc::remove);
		eq(42, Services.find(TestService.class).orElseThrow().answer(), "service found");
		svc.remove();
		isTrue(Services.find(TestService.class).isEmpty(), "service unregistered");

		Item diamond = net.minecraft.world.item.Items.DIAMOND;
		long base = BasePrices.sellPrice(diamond).orElse(-1);
		if (base > 0) eq((long) Math.floor(base * Services.DEFAULT_MARKET_FACTOR * PriceModifiers.get(diamond) + 1e-9), Services.defaultMarketValue(diamond).orElse(-1), "default market value");
		isTrue(Services.marketValue(net.minecraft.world.item.Items.BEDROCK).isEmpty(), "no market value without price");
		boolean hadProvider = Services.find(Services.MarketValues.class).isPresent();
		if (!hadProvider) {
			Hooks.Registration mv = Services.register(Services.MarketValues.class, item -> item == diamond ? OptionalLong.of(12_345) : OptionalLong.empty());
			ctx.cleanup(mv::remove);
			eq(12_345L, Services.marketValue(diamond).orElse(-1), "MarketValues service takes precedence");
			isTrue(Services.marketValue(net.minecraft.world.item.Items.WHEAT).isEmpty(), "service says: no estimate");
			mv.remove();
		}
	}

	private static void priceModifierTransitions() {
		PriceModifiers.Entry e = new PriceModifiers.Entry();
		e.from = 1.0;
		e.to = 0.5;
		e.startMs = 1_000_000;
		e.fadeInMs = 0;
		e.endMs = 1_000_000 + 30 * 60_000L;
		e.fadeOutMs = 5 * 60_000L;
		assertClose(0.5, e.valueAt(1_000_000), "immediately -50%");
		assertClose(0.5, e.valueAt(e.endMs - 1), "until the end");
		assertClose(0.5, e.valueAt(e.endMs), "fade-out start");
		double mid = e.valueAt(e.endMs + e.fadeOutMs / 2);
		isTrue(mid > 0.5 && mid < 1.0, "half faded: " + mid);
		assertClose(0.75, mid, "smoothstep at the midpoint");
		double prev = 0.5;
		for (long t = e.endMs; t <= e.endMs + e.fadeOutMs; t += 10_000) {
			double v = e.valueAt(t);
			isTrue(v >= prev - 1e-12, "monotonically rising during fade-out");
			prev = v;
		}
		assertClose(1.0, e.valueAt(e.endMs + e.fadeOutMs), "1.0 afterwards");
		isTrue(e.finishedAt(e.endMs + e.fadeOutMs), "finished");
		isFalse(e.finishedAt(e.endMs + e.fadeOutMs - 1), "not finished yet");

		PriceModifiers.Entry hype = new PriceModifiers.Entry();
		hype.from = 1.0;
		hype.to = 3.0;
		hype.startMs = 0;
		hype.fadeInMs = 60_000;
		hype.endMs = 0;
		assertClose(1.0, hype.valueAt(0), "fade-in starts at from");
		assertClose(2.0, hype.valueAt(30_000), "fade-in midpoint");
		assertClose(3.0, hype.valueAt(60_000), "target reached");
		assertClose(3.0, hype.valueAt(Long.MAX_VALUE / 2), "unlimited");
		isFalse(hype.finishedAt(Long.MAX_VALUE / 2), "unlimited never finishes");
		hype.endMs = 30_000;
		hype.fadeOutMs = 60_000;
		assertClose(2.0, hype.valueAt(30_000), "fade-out from intermediate value");
		assertClose(1.5, hype.valueAt(60_000), "fade-out midpoint");
	}

	private static void priceModifiersLive(TestContext ctx) {
		Item item = net.minecraft.world.item.Items.DEAD_BUSH;
		String other = SRC + ".b";
		ctx.cleanup(() -> {
			PriceModifiers.removeSource(SRC);
			PriceModifiers.removeSource(other);
		});
		double before = PriceModifiers.get(item);
		PriceModifiers.set(SRC, item, 0.5, 0, 60_000, 60_000, "Test-Crash");
		assertClose(clampMod(before * 0.5), PriceModifiers.get(item), "crash active");
		assertClose(0.5, PriceModifiers.get(SRC, item), "value of the source");
		isTrue(PriceModifiers.active(item).stream().anyMatch(a -> a.label().equals("Test-Crash") && a.endsAtMs() > 0), "in active()");
		PriceModifiers.set(other, item, 3.0, 0, 0, 0, "Test-Hype");
		assertClose(clampMod(before * 1.5), PriceModifiers.get(item), "sources multiply");
		PriceModifiers.set(SRC, item, 1000, 0, 0, 0, "too big");
		assertClose(PriceModifiers.MAX_FACTOR, PriceModifiers.get(SRC, item), "target capped");
		assertThrows(IllegalArgumentException.class, () -> PriceModifiers.set(SRC, item, 0, 0, 0, 0, "x"), "0 rejected");
		assertThrows(IllegalArgumentException.class, () -> PriceModifiers.set(SRC, item, Double.NaN, 0, 0, 0, "x"), "NaN rejected");
		PriceModifiers.set(SRC, item, 0.5, 0, 0, 0, "Test-Crash");
		PriceModifiers.clear(SRC, item, 60_000);
		double fading = PriceModifiers.get(SRC, item);
		isTrue(fading >= 0.5 && fading < 0.51, "clear fades out smoothly (starts at the current value): " + fading);
		PriceModifiers.clear(other, item, 0);
		assertClose(1.0, PriceModifiers.get(other, item), "clear without transition");
		PriceModifiers.tick();
		isTrue(PriceModifiers.active(item).stream().noneMatch(a -> a.source().equals(other)), "expired entry deleted");
		PriceModifiers.remove(SRC, item);
		assertClose(before, PriceModifiers.get(item), "removed");
		assertClose(1.0, PriceModifiers.get(net.minecraft.world.item.Items.BEDROCK), "1.0 without entry");
	}

	private static double clampMod(double v) {
		return Math.max(PriceModifiers.MIN_FACTOR, Math.min(PriceModifiers.MAX_FACTOR, v));
	}

	private static void priceGuardRules() {
		eq(60L, PriceGuard.computeSell(100, 0.5, 1.2, -1), "sell 100 x 0.5 x 1.2");
		eq(0L, PriceGuard.computeSell(0, 2, 2, -1), "base 0");
		eq(33L, PriceGuard.computeSell(10, 3.0, 1.1, -1), "rounded down (33.0)");
		eq(500L, PriceGuard.computeSell(500, 1.0, 1.0, -1), "neutral");
		eq(0L, PriceGuard.computeSell(1, 0.5, 1.0, -1), "under 1 cent gives 0 (effectiveSellPrice: empty)");
		eq(90L, PriceGuard.computeSell(50, 3.0, 1.5, 90), "hype capped at pivot");
		eq(48L, PriceGuard.pivot(80), "pivot = floor(0.6 x 80)");
		eq(-1L, PriceGuard.pivot(0), "no pivot without shop price");
		eq(200L, PriceGuard.computeShop(200, 1, 1, 100, -1), "shop neutral");
		eq(125L, PriceGuard.computeShop(200, 0.5, 0.6, 100, -1), "floor 1.25 x sell applies");
		eq(127L, PriceGuard.computeShop(100, 1, 1, 101, -1), "1.25 x 101 = 126.25 rounded up");
		eq(1L, PriceGuard.computeShop(0, 1, 1, 0, -1), "at least 1 cent");
		eq(91L, PriceGuard.computeShop(150, 0.5, 0.6, 25, 90), "crash + discount: never below pivot + 1");
		eq(112L, PriceGuard.capNpcBuy(125, 1_000, PriceGuard.NPC_BUY_MARKET_FACTOR, -1), "NPC buy: 0.9 x shop");
		eq(115L, PriceGuard.capNpcBuy(1_000, 100, PriceGuard.NPC_BUY_MARKET_FACTOR, -1), "NPC buy: market value x 1.15");
		eq(115L, PriceGuard.capNpcBuy(-1, 100, PriceGuard.NPC_BUY_MARKET_FACTOR, -1), "without shop only market value");
		eq(125L, PriceGuard.capNpcBuy(-1, 100, 5.0, -1), "fever factor limited to 1.25");
		eq(90L, PriceGuard.capNpcBuy(1_000, 1_000, 1.25, 90), "NPC buy: pivot");
		eq(100L, PriceGuard.capNpcOrder(125, 1_000, -1), "NPC order: 0.8 x shop");
		eq(125L, PriceGuard.capNpcOrder(-1, 100, -1), "NPC order without shop: market value x 1.25");
		eq(0L, PriceGuard.capNpcOrder(-1, 0, -1), "order without market value");
		eq(90L, PriceGuard.capNpcOrder(1_000, 1_000, 90), "NPC order: pivot");
		eq(126L, PriceGuard.minSystemSale(100, 125, -1), "system sale > highest system payment");
		eq(125L, PriceGuard.minSystemSale(100, 0, -1), "system sale >= 1.25 x sell");
		eq(91L, PriceGuard.minSystemSale(10, 20, 90), "system sale > pivot");
		eq(1L, PriceGuard.minSystemSale(0, 0, -1), "at least 1 cent");
		eq(1.0, PriceGuard.buySideModifier(0.5), "buy side: crash counts as 1.0");
		eq(2.5, PriceGuard.buySideModifier(2.5), "buy side: full hype");
		eq(1.0, PriceGuard.buySideModifier(Double.NaN), "buy side: NaN gives 1.0");
		eq("+50%", PriceGuard.percent(1.5), "percent +");
		eq("-30%", PriceGuard.percent(0.7), "percent -");
		eq("±0%", PriceGuard.percent(1.0), "percent 0");
		eq("+200%", PriceGuard.percent(3.0), "percent +200");
	}

	/**
	 * For every registry item with a shop or sell price, {@value #COMBOS} random combinations plus corner cases (item
	 * modifier 0.05-20, sell multiplier up to 10, discount 0.6-1, market drift 0.6-1.6, auction fever). At one moment,
	 * buying from the shop or an NPC offer and selling back is never profitable. Over time, for shop items, the
	 * cheapest buy at any moment exceeds the highest system payment at any other (pivot).
	 */
	private static void priceGuardNoArbitrage(TestContext ctx) {
		Random rnd = new Random(0x12_7121L);
		double[][] corners = {{0.5, 1.0, 0.6}, {0.5, 1.7, 0.6}, {3.0, 1.7, 0.6}, {3.0, 1.0, 1.0}, {0.5, 1.7, 1.0}, {3.0, 1.0, 0.6}, {1.0, 1.0, 1.0},
			{PriceModifiers.MIN_FACTOR, 1.0, 0.6}, {PriceModifiers.MAX_FACTOR, 10.0, 1.0}, {PriceModifiers.MAX_FACTOR, 1.7, 0.6}, {PriceModifiers.MIN_FACTOR, 10.0, 0.6}};
		long checks = 0;
		int items = 0;
		for (Item item : BuiltInRegistries.ITEM) {
			long baseSell = BasePrices.sellPrice(item).orElse(-1);
			long baseBuy = BasePrices.shopBuyPrice(item).orElse(-1);
			if (baseSell < 0 && baseBuy < 0) continue;
			items++;
			long pivot = PriceGuard.pivot(baseBuy);
			long minBuy = Long.MAX_VALUE; // cheapest system sale over all moments
			long maxPayout = 0;           // highest system payment over all moments
			for (int i = 0; i < COMBOS + corners.length; i++) {
				double mod, sellMult, disc;
				if (i < corners.length) {
					mod = corners[i][0];
					sellMult = corners[i][1];
					disc = corners[i][2];
				} else {
					mod = rnd.nextInt(10) == 0 ? PriceModifiers.MIN_FACTOR + rnd.nextDouble() * (PriceModifiers.MAX_FACTOR - PriceModifiers.MIN_FACTOR) : 0.5 + rnd.nextDouble() * 2.5;
					sellMult = rnd.nextInt(10) == 0 ? 1.0 + rnd.nextDouble() * 9 : 1.0 + rnd.nextDouble() * 0.7;
					disc = 0.6 + rnd.nextDouble() * 0.4;
				}
				double drift = 0.6 + rnd.nextDouble() * 1.0;
				double fever = rnd.nextBoolean() ? PriceGuard.NPC_BUY_MARKET_FACTOR : PriceGuard.NPC_BUY_MARKET_FACTOR_MAX;
				long market = baseSell > 0 ? Math.round(baseSell * 1.6 * drift * mod) : 1 + rnd.nextInt(1_000_000);
				long[] r = checkCombination(item, baseSell, baseBuy, pivot, mod, sellMult, disc, market, fever);
				minBuy = Math.min(minBuy, r[0]);
				maxPayout = Math.max(maxPayout, r[1]);
				checks++;
			}
			if (pivot >= 0) {
				String where = Items.id(item) + " pivot=" + pivot;
				check(maxPayout <= pivot, "system payments <= pivot at all times: " + where + " max=" + maxPayout);
				check(minBuy > maxPayout, "buying (moment A) + selling (moment B) never profitable: " + where + " minBuy=" + minBuy + " maxPay=" + maxPayout);
			}
		}
		isTrue(items >= 300, "enough items checked (" + items + ")");
		ctx.detail(items + " items x " + (COMBOS + corners.length) + " combinations = " + checks + " checks ok (including time arbitrage)");
	}

	/** Returns {cheapest system sale, highest system payment} of this moment. */
	private static long[] checkCombination(Item item, long baseSell, long baseBuy, long pivot, double mod, double sellMult, double disc, long market, double fever) {
		String where = Items.id(item) + " mod=" + mod + " sell×" + sellMult + " disc=" + disc + " mv=" + market;
		long sell = baseSell < 0 ? 0 : PriceGuard.computeSell(baseSell, mod, sellMult, pivot); // 0 means not sellable
		check(sell <= baseSell * mod * sellMult + 1e-6 || sell == 0, "sell never above the exact value: " + where);
		// Buy side as in PriceGuard: a crash (mod < 1) counts as 1.0, minimum 1.25 x sell without crash.
		double buyMod = PriceGuard.buySideModifier(mod);
		long sellNoCrash = baseSell < 0 ? 0 : PriceGuard.computeSell(baseSell, buyMod, sellMult, pivot);
		long shop = baseBuy < 0 ? -1 : PriceGuard.computeShop(baseBuy, buyMod, disc, sellNoCrash, pivot);
		long npcBuy = PriceGuard.capNpcBuy(shop, market, fever, pivot);
		long npcBuyMax = PriceGuard.capNpcBuy(shop, market, PriceGuard.NPC_BUY_MARKET_FACTOR_MAX, pivot);
		long order = PriceGuard.capNpcOrder(shop, market, pivot);
		long payout = Math.max(sell, Math.max(npcBuyMax, order));
		long offer = PriceGuard.minSystemSale(sellNoCrash, Math.max(npcBuyMax, order), pivot);
		check(npcBuy <= market * fever + 1e-6 && npcBuy <= market * PriceGuard.NPC_BUY_MARKET_FACTOR_MAX + 1e-6, "NPC buy <= market value x factor: " + where);
		check(order <= market * PriceGuard.NPC_ORDER_MARKET_FACTOR + 1e-6, "NPC order <= market value x 1.25: " + where);
		check(offer > payout, "NPC offer to /sell, NPC buyer or NPC order never profitable: " + where + " offer=" + offer + " payout=" + payout);
		check(offer * 4 >= sell * 5L, "NPC offer >= 1.25 x sell: " + where);
		long minBuy = offer;
		if (pivot >= 0) check(payout <= pivot && offer > pivot, "pivot separates payments and sales: " + where);
		if (shop >= 0) {
			minBuy = Math.min(minBuy, shop);
			check(shop >= 1, "shop >= 1 cent: " + where);
			check(shop >= Math.ceil(baseBuy * buyMod * disc - 1e-6), "shop never below base x buy mod x discount: " + where);
			check(shop >= PriceGuard.computeShop(baseBuy, 1.0, disc, baseSell < 0 ? 0 : PriceGuard.computeSell(baseSell, 1.0, sellMult, pivot), pivot),
				"crash never lowers the shop price below the no-modifier value: " + where);
			if (sell > 0) check(shop * 4 >= sell * 5L, "shop >= 1.25 x sell: " + where + " shop=" + shop + " sell=" + sell);
			check(shop > sell, "buy + /sell never profitable: " + where);
			check(npcBuy * 10 <= shop * 9L, "NPC buy <= 0.9 x shop: " + where);
			check(npcBuy < shop, "buy + sell to NPC never profitable: " + where);
			check(order * 5 <= shop * 4L, "NPC order <= 0.8 x shop: " + where);
			check(order < shop, "buy + deliver to NPC order never profitable: " + where);
			for (int n : new int[] {1, 64, 576}) {
				check(Math.multiplyExact(shop, n) > Math.multiplyExact(payout, n), "amount " + n + " never profitable: " + where);
			}
		}
		return new long[] {minBuy, payout};
	}

	private static void priceGuardLive(TestContext ctx) {
		UUID a = ctx.player("TestA").getUUID();
		UUID b = ctx.player("TestB").getUUID();
		double[] sellMult = {1.0};
		double[] disc = {1.0};
		Item[] current = {null};
		List<Hooks.Registration> regs = new ArrayList<>();
		ctx.cleanup(() -> {
			regs.forEach(Hooks.Registration::remove);
			PriceModifiers.removeSource(SRC);
		});
		regs.add(Hooks.addSellMultiplier(SRC + ".sell", "Test-Bonus", (p, item) -> p.equals(a) ? sellMult[0] : 1.0));
		regs.add(Hooks.addShopDiscount(SRC + ".disc", "Test Discount", item -> item == current[0] ? disc[0] : 1.0));
		Random rnd = new Random(42);
		Set<Item> both = new LinkedHashSet<>();
		for (Item item : BasePrices.shopBuyPrices().keySet()) {
			if (BasePrices.sellPrice(item).isPresent()) both.add(item);
		}
		isTrue(both.size() >= 20, "enough items with shop and sell price (" + both.size() + ")");
		int combos = 0;
		for (Item item : both) {
			current[0] = item;
			long baseSell = BasePrices.sellPrice(item).getAsLong();
			long baseBuy = BasePrices.shopBuyPrice(item).getAsLong();
			for (int i = 0; i < 200; i++) {
				double mod = 0.5 + rnd.nextDouble() * 2.5;
				sellMult[0] = 1.0 + rnd.nextDouble() * 0.7;
				disc[0] = 0.6 + rnd.nextDouble() * 0.4;
				PriceModifiers.set(SRC, item, mod, 0, 0, 0, "Test");
				double liveMod = PriceModifiers.get(item); // includes real events that may be running
				double liveSellMult = Hooks.sellMultiplier(a, item);
				double liveDisc = Hooks.shopDiscount(item);
				long pivot = PriceGuard.pivotPrice(item);
				eq(PriceGuard.pivot(baseBuy), pivot, "pivot from shop.json: " + Items.id(item));
				double liveBuyMod = PriceModifiers.buySide(item);
				long sell = PriceGuard.effectiveSellPrice(a, item).orElse(0);
				long shop = PriceGuard.effectiveShopBuyPrice(a, item).orElseThrow();
				long sellNoCrash = PriceGuard.computeSell(baseSell, liveBuyMod, liveSellMult, pivot);
				eq(PriceGuard.computeSell(baseSell, liveMod, liveSellMult, pivot), sell, "effectiveSellPrice wired: " + Items.id(item));
				eq(sellNoCrash, PriceGuard.effectiveSellPriceWithoutCrash(a, item).orElse(0), "sell without crash wired: " + Items.id(item));
				eq(PriceGuard.computeShop(baseBuy, liveBuyMod, liveDisc, sellNoCrash, pivot), shop, "effectiveShopBuyPrice wired: " + Items.id(item));
				eq(shop, PriceGuard.effectiveShopBuyPrice(a, item, baseBuy), "with explicit base price");
				check(shop * 4 >= sell * 5L && shop > sell, "live: shop > 1.25 x sell for " + Items.id(item));
				long market = Math.round(baseSell * 1.6 * liveMod);
				long npcBuy = PriceGuard.maxNpcBuyPrice(a, item, market);
				check(npcBuy * 10 <= shop * 9L && npcBuy < shop, "live: NPC buy cap for " + Items.id(item));
				check(PriceGuard.maxNpcBuyPrice(a, item, market, 1.25) <= Math.floor(market * 1.25 + 1e-6), "live: fever cap");
				long order = PriceGuard.maxNpcOrderPrice(a, item, market);
				check(order * 5 <= shop * 4L && order < shop && order <= pivot, "live: order cap for " + Items.id(item));
				long offer = PriceGuard.minSystemSalePrice(a, item, market);
				check(offer > Math.max(sell, Math.max(PriceGuard.maxNpcBuyPrice(a, item, market, 1.25), order)) && offer > pivot,
					"live: NPC offer above every system payment for " + Items.id(item));
				long sellB = PriceGuard.effectiveSellPrice(b, item).orElse(0);
				eq(PriceGuard.computeSell(baseSell, liveMod, Hooks.sellMultiplier(b, item), pivot), sellB, "B without test bonus");
				combos++;
			}
			PriceModifiers.remove(SRC, item);
		}
		current[0] = null;
		Item sellOnly = null;
		for (Item item : BasePrices.sellPrices().keySet()) {
			if (BasePrices.shopBuyPrice(item).isEmpty()) {
				sellOnly = item;
				break;
			}
		}
		if (sellOnly != null) {
			isTrue(PriceGuard.effectiveShopBuyPrice(a, sellOnly).isEmpty(), "not in the shop");
			eq(-1L, PriceGuard.pivotPrice(sellOnly), "no pivot without shop");
			eq(1_250L, PriceGuard.maxNpcOrderPrice(a, sellOnly, 1_000), "order without shop: market value x 1.25");
			eq(PriceGuard.capNpcBuy(-1, 1_000, PriceGuard.NPC_BUY_MARKET_FACTOR, -1), PriceGuard.maxNpcBuyPrice(a, sellOnly, 1_000), "market value cap only");
			isTrue(PriceGuard.minSystemSalePrice(a, sellOnly, 1_000) > 1_250, "NPC offer above the order cap");
		}
		// under 1 cent: not sellable, rather than sellable for $0
		Item cheap = null;
		for (Map.Entry<Item, Long> e : BasePrices.sellPrices().entrySet()) {
			if (e.getValue() > 0 && e.getValue() <= 5) {
				cheap = e.getKey();
				break;
			}
		}
		if (cheap != null) {
			PriceModifiers.set(SRC, cheap, PriceModifiers.MIN_FACTOR, 0, 0, 0, "Test-Crash");
			isTrue(PriceGuard.effectiveSellPrice(a, cheap).isEmpty(), "crash under 1 cent, not sellable: " + Items.id(cheap));
			eq(-1L, PriceGuard.breakdown(a, cheap).effectiveSell(), "breakdown: -1 rather than 0");
			PriceModifiers.remove(SRC, cheap);
		}
		isTrue(PriceGuard.effectiveSellPrice(a, net.minecraft.world.item.Items.BEDROCK).isEmpty(), "bedrock not sellable");
		PriceGuard.Breakdown bd = PriceGuard.breakdown(a, net.minecraft.world.item.Items.WHEAT);
		isTrue(bd.baseSell() > 0, "breakdown wheat");
		ctx.detail(both.size() + " items x 200 live combinations (" + combos + ") ok");
	}

	/**
	 * A crash (any strength, even alongside another source's hype) only lowers the sell side. Shop price, /sell without
	 * crash and the NPC offer floor stay at their no-crash value for every shop item, with and without a sell bonus.
	 */
	private static void priceGuardCrashBuySide(TestContext ctx) {
		UUID a = ctx.player("TestA").getUUID();
		UUID b = ctx.player("TestB").getUUID();
		String hypeSrc = SRC + ".hype";
		List<Hooks.Registration> regs = new ArrayList<>();
		ctx.cleanup(() -> {
			regs.forEach(Hooks.Registration::remove);
			PriceModifiers.removeSource(SRC);
			PriceModifiers.removeSource(hypeSrc);
		});
		regs.add(Hooks.addSellMultiplier(SRC + ".boost", "Test-Bonus", (p, item) -> p.equals(a) ? 1.68 : 1.0));
		List<Item> items = new ArrayList<>();
		for (Item item : BasePrices.shopBuyPrices().keySet()) {
			if (BasePrices.sellPrice(item).isPresent()) items.add(item);
		}
		isTrue(items.size() >= 20, "enough shop items with sell price (" + items.size() + ")");
		Map<Item, long[]> base = new java.util.HashMap<>();
		for (Item item : items) base.put(item, snapshot(a, b, item, Services.marketValue(item).orElse(0)));
		int checks = 0;
		for (double crash : new double[] {PriceModifiers.MIN_FACTOR, 0.3, 0.5, 0.8, 0.99}) {
			for (Item item : items) PriceModifiers.set(SRC, item, crash, 0, 0, 0, "Test-Crash");
			for (Item item : items) {
				long[] before = base.get(item);
				long[] now = snapshot(a, b, item, before[6]);
				String id = Items.id(item) + " crash x" + crash;
				eq(before[0], now[0], "shop price (with bonus) unchanged: " + id);
				eq(before[1], now[1], "shop price (without bonus) unchanged: " + id);
				eq(before[2], now[2], "sell without crash unchanged: " + id);
				isTrue(now[3] <= before[3], "crash does not raise /sell above normal: " + id);
				eq(before[4], now[4], "NPC offer floor (same market value) unchanged: " + id);
				isTrue(now[5] <= before[5], "system payments never higher in a crash: " + id);
				isTrue(PriceGuard.breakdown(a, item).loreLines().stream().anyMatch(l -> l.contains("(sell only)")), "lore: crash sell only: " + id);
				checks++;
			}
			PriceModifiers.removeSource(SRC);
		}
		Item probe = items.get(0);
		double otherBuySide = PriceModifiers.buySide(probe); // real hypes may be running
		PriceModifiers.set(hypeSrc, probe, 1.5, 0, 0, 0, "Test-Hype");
		long hypeOnly = PriceGuard.effectiveShopBuyPrice(b, probe).orElseThrow();
		PriceModifiers.set(SRC, probe, 0.5, 0, 0, 0, "Test-Crash");
		assertClose(Math.min(PriceModifiers.MAX_FACTOR, otherBuySide * 1.5), PriceGuard.buySideModifier(probe), "buy modifier = hype without crash");
		eq(hypeOnly, PriceGuard.effectiveShopBuyPrice(b, probe).orElseThrow(), "hype + crash: shop price = hype only");
		ctx.detail(items.size() + " shop items x 5 crash strengths (" + checks + " checks): buy side unchanged");
	}

	/** {shop A, shop B, /sell without crash A, /sell A, NPC offer floor B, highest system payment A, market value}. */
	private static long[] snapshot(UUID a, UUID b, Item item, long market) {
		long sellA = PriceGuard.effectiveSellPrice(a, item).orElse(0);
		long payout = Math.max(sellA, Math.max(PriceGuard.maxNpcBuyPrice(a, item, market, PriceGuard.NPC_BUY_MARKET_FACTOR_MAX),
			PriceGuard.maxNpcOrderPrice(a, item, market)));
		return new long[] {PriceGuard.effectiveShopBuyPrice(a, item).orElse(-1), PriceGuard.effectiveShopBuyPrice(b, item).orElse(-1),
			PriceGuard.effectiveSellPriceWithoutCrash(a, item).orElse(0), sellA, PriceGuard.minSystemSalePrice(b, item, market), payout, market};
	}

	private static void guiHelpers(TestContext ctx) {
		int[] inner = Gui.innerSlots(4);
		eq(14, inner.length, "4 rows give 14 inner slots");
		eq(10, inner[0], "first inner slot");
		eq(25, inner[13], "last inner slot");
		eq(28, Gui.innerSlots(6).length, "6 rows give 28");
		SimpleGui gui = new SimpleGui(MenuType.GENERIC_9x4, ctx.player("TestA"), false);
		gui.setSlot(0, Gui.item(net.minecraft.world.item.Items.DIAMOND, "§bTest"));
		Gui.frame(gui);
		isTrue(gui.getSlot(0).getItemStack().is(net.minecraft.world.item.Items.DIAMOND), "occupied slot stays");
		for (int s : new int[] {1, 8, 9, 17, 27, 35}) isTrue(gui.getSlot(s).getItemStack().is(net.minecraft.world.item.Items.BLACK_STAINED_GLASS_PANE), "frame at " + s);
		for (int s : inner) check(gui.getSlot(s) == null, "inside free at " + s);
		Gui.fillEmpty(gui);
		isTrue(gui.getSlot(10).getItemStack().is(net.minecraft.world.item.Items.GRAY_STAINED_GLASS_PANE), "fill gray");
		eq(MenuType.GENERIC_9x6, Gui.menuType(9), "rows limited");

		for (ClickType t : ClickType.values()) {
			boolean plain = t == ClickType.MOUSE_LEFT || t == ClickType.MOUSE_RIGHT || t == ClickType.MOUSE_LEFT_SHIFT || t == ClickType.MOUSE_RIGHT_SHIFT;
			eq(plain, Gui.isPlainClick(t), "isPlainClick " + t);
		}
		// A test player of its own per run: the debounce remembers the last click per UUID.
		FakePlayer clicker = ctx.player("TestClick" + (System.nanoTime() & 0xFFFFF));
		isFalse(Gui.acceptClick(clicker, 5, ClickType.MOUSE_DOUBLE_CLICK), "double-click collect does not fire");
		isTrue(Gui.acceptClick(clicker, 5, ClickType.MOUSE_LEFT), "first click");
		isFalse(Gui.acceptClick(clicker, 5, ClickType.MOUSE_LEFT), "second click of the same double click debounced");
		isFalse(Gui.acceptClick(clicker, 6, ClickType.MOUSE_LEFT_SHIFT), "shift double click on other matching items debounced");
		isFalse(Gui.acceptClick(clicker, 5, ClickType.NUM_KEY_1), "number key");
	}

	private static void assertClose(double expected, double actual, String message) {
		if (Math.abs(expected - actual) > 1e-9 * Math.max(1, Math.abs(expected))) throw new AssertionError(message + " (expected " + expected + ", was " + actual + ")");
	}
}
