package dev.smpeconomy.features.events;

import static dev.smpeconomy.core.test.Assert.assertThrows;
import static dev.smpeconomy.core.test.Assert.check;
import static dev.smpeconomy.core.test.Assert.eq;
import static dev.smpeconomy.core.test.Assert.isFalse;
import static dev.smpeconomy.core.test.Assert.isTrue;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.tree.CommandNode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Random;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.item.Item;
import dev.smpeconomy.core.AdminLog;
import dev.smpeconomy.core.BasePrices;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Hooks;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.PriceGuard;
import dev.smpeconomy.core.PriceModifiers;
import dev.smpeconomy.core.TimeUtil;
import dev.smpeconomy.core.Treasury;
import dev.smpeconomy.core.test.SelfTests;
import dev.smpeconomy.core.test.TestContext;
import dev.smpeconomy.core.test.TestPlayers;

/** Logic without GUI; e2e/events.mjs covers the GUIs. */
final class EventsSelfTests {
	private static final String F = "events";
	private static final long MIN = 60_000L;
	private static final long T0 = 1_800_000_000_000L; // Fixed point in time (Jan 2027)
	/** The selftests' own PriceModifiers source, removed in cleanup. */
	private static final String SELFTEST_SOURCE = "events.selftest";

	private EventsSelfTests() {}

	static void register() {
		SelfTests.register(F, "config-defaults", EventsSelfTests::configDefaults);
		SelfTests.register(F, "config-validation", ctx -> configValidation());
		SelfTests.register(F, "multipliers-only-during-events", ctx -> multipliersOnlyDuring());
		SelfTests.register(F, "goldrush-job-matching", ctx -> goldRushMatching());
		SelfTests.register(F, "schedule-tick", EventsSelfTests::scheduleTick);
		SelfTests.register(F, "start-stop-history", ctx -> startStopHistory());
		SelfTests.register(F, "hooks-live-integration", EventsSelfTests::hooksLive);
		SelfTests.register(F, "rank-thresholds", ctx -> rankThresholds());
		SelfTests.register(F, "rank-from-items-sold", EventsSelfTests::rankFromItemsSold);
		SelfTests.register(F, "rank-net-sales-no-roundtrip", EventsSelfTests::rankNetSales);
		SelfTests.register(F, "lotto-buy-limits", EventsSelfTests::lottoBuyLimits);
		SelfTests.register(F, "lotto-sold-out", EventsSelfTests::lottoSoldOut);
		SelfTests.register(F, "lotto-uniform-distinct-draws", EventsSelfTests::lottoUniform);
		SelfTests.register(F, "lotto-no-auto-win-alone", EventsSelfTests::lottoNoAutoWin);
		SelfTests.register(F, "lotto-jackpot-carryover", EventsSelfTests::lottoCarryover);
		SelfTests.register(F, "lotto-treasury-conservation", EventsSelfTests::lottoConservation);
		SelfTests.register(F, "lotto-exactly-once-restart", EventsSelfTests::lottoExactlyOnce);
		SelfTests.register(F, "lotto-migration-old-pot", EventsSelfTests::lottoMigration);
		SelfTests.register(F, "lotto-forced-numbers", EventsSelfTests::lottoForced);
		SelfTests.register(F, "lotto-config-refresh", EventsSelfTests::lottoConfigRefresh);
		SelfTests.register(F, "lotto-schedule", ctx -> lottoSchedule());
		SelfTests.register(F, "lotto-early-draw-one-per-slot", EventsSelfTests::lottoEarlyDraw);
		SelfTests.register(F, "lotto-jackpot-max", EventsSelfTests::lottoJackpotMax);
		SelfTests.register(F, "lotto-pot-account", ctx -> lottoPotAccount());
		SelfTests.register(F, "command-names", EventsSelfTests::commandNames);
	}

	private static EventsConfig cfg() {
		EventsConfig c = new EventsConfig();
		EventsConfig.validate(c);
		return c;
	}

	private static EventManager manager(EventManager.Data data, EventsConfig cfg, long seed) {
		return new EventManager(() -> data, () -> {}, () -> cfg, new Random(seed));
	}

	private static void close(double expected, double actual, String msg) {
		check(Math.abs(expected - actual) < 1e-9, msg + " (expected " + expected + ", was " + actual + ")");
	}

	private static Item item(String id) {
		return dev.smpeconomy.core.Items.byId(id).orElseThrow(() -> new AssertionError("item missing: " + id));
	}

	private static void configDefaults(TestContext ctx) {
		EventsConfig c = cfg();
		eq(EventType.values().length, c.weights.size(), "weights for all types");
		check(c.marketDayCategories.size() >= 3, "at least 3 Market Day categories");
		int items = 0;
		double maxAllowedRank = 1.0 + EventsConfig.MAX_RANK_BONUS_PERCENT / 100.0;
		for (EventsConfig.Category cat : c.marketDayCategories) {
			eq(cat.items.size(), c.categoryItems(cat.id).size(), "all items of category " + cat.id + " exist and are eligible");
			for (Item it : c.categoryItems(cat.id)) {
				String id = dev.smpeconomy.core.Items.id(it);
				OptionalLong base = BasePrices.sellPrice(it);
				check(base.isPresent(), "Market Day item without sell price: " + id);
				// Shop items only: the pivot guards "buy from the system, sell on Market Day" over time too
				long pivot = PriceGuard.pivotPrice(it);
				check(pivot >= 0, "Market Day item without pivot (no shop item): " + id);
				// The "×1.5" promise holds with the highest rank too: the pivot does not cap Market Day ×
				// rank
				long raw = PriceGuard.computeSell(base.getAsLong(), 1.0, c.marketDayMultiplier * c.maxRankMultiplier(), -1);
				check(raw <= pivot, "Market Day × rank on " + id + " (" + raw + ") ≤ pivot " + pivot);
				// Defaults stay valid even if an admin raises the rank bonus to the maximum (20 %)
				check(PriceGuard.computeSell(base.getAsLong(), 1.0, c.marketDayMultiplier * maxAllowedRank, -1) <= pivot,
					"Market Day × 20 % rank on " + id + " ≤ pivot");
				items++;
			}
		}
		// Items without a pivot are never eligible for Market Day
		for (String id : List.of("minecraft:diamond", "minecraft:emerald", "minecraft:iron_ingot", "minecraft:gold_ingot",
			"minecraft:raw_iron", "minecraft:quartz", "minecraft:shulker_shell", "minecraft:breeze_rod", "minecraft:nautilus_shell")) {
			Item it = item(id);
			isTrue(EventsConfig.marketDayProblem(it, c.marketDayMultiplier, c.maxRankMultiplier()).isPresent(), id + " not eligible for Market Day");
			for (EventsConfig.Category cat : c.marketDayCategories) isFalse(c.categoryItems(cat.id).contains(it), id + " not in " + cat.id);
		}
		check(c.goldRushJobs.size() >= 6, "6 Gold Rush jobs");
		eq(5, c.traderRanks.size(), "5 trader ranks");
		eq(10_000L, c.lottoTicketPrice, "ticket $100");
		eq(50L, c.lottoMaxTicketsPerPlayer, "max 50 tickets");
		eq("20:00", c.lottoDrawTime, "draw 20:00");
		eq(1000L, c.lottoTicketsPerDraw, "1000 tickets per draw");
		eq(7_200_000L, c.lottoJackpotBase, "Jackpot $72,000");
		eq(List.of(1_000_000L, 500_000L, 100_000L), c.lottoPrizes, "extra prizes $10,000/$5,000/$1,000");
		eq(4L, c.lottoWinningNumbers(), "4 winning numbers");
		isFalse(c.lottoRolloverWithoutTickets, "no tickets, no rollover (draw cancelled)");
		eq(0L, c.lottoJackpotMax, "no jackpot limit");
		eq(2_500_000L, c.lottoTreasuryReserve, "reserve $25,000");
		isFalse(c.lottoForcedDraws, "given winning numbers off (test servers only)");
		ctx.detail(items + " Market Day items checked");
	}

	private static void configValidation() {
		EventsConfig c = cfg();
		c.traderRanks.get(1).minSales = 0;
		EventsConfig bad1 = c;
		assertThrows(IllegalArgumentException.class, () -> EventsConfig.validate(bad1), "ranks not ascending");
		EventsConfig bad2 = cfg();
		bad2.traderRanks.get(4).bonusPercent = 30;
		assertThrows(IllegalArgumentException.class, () -> EventsConfig.validate(bad2), "bonus > 20 %");
		EventsConfig bad3 = cfg();
		bad3.traderRanks.get(0).minSales = 5;
		assertThrows(IllegalArgumentException.class, () -> EventsConfig.validate(bad3), "first rank not at 0");
		EventsConfig bad4 = cfg();
		bad4.minIntervalMinutes = 300;
		assertThrows(IllegalArgumentException.class, () -> EventsConfig.validate(bad4), "min > max interval");
		EventsConfig bad5 = cfg();
		bad5.marketDayMultiplier = 0.5;
		assertThrows(IllegalArgumentException.class, () -> EventsConfig.validate(bad5), "multiplier < 1");
		EventsConfig bad6 = cfg();
		bad6.lottoDrawTime = "25:99";
		assertThrows(IllegalArgumentException.class, () -> EventsConfig.validate(bad6), "invalid time");
		EventsConfig bad7 = cfg();
		bad7.lottoTicketPrice = 0;
		assertThrows(IllegalArgumentException.class, () -> EventsConfig.validate(bad7), "ticket price 0");
		EventsConfig bad14 = cfg();
		bad14.lottoTicketsPerDraw = 3;
		assertThrows(IllegalArgumentException.class, () -> EventsConfig.validate(bad14), "fewer tickets than winning numbers");
		EventsConfig bad15 = cfg();
		bad15.lottoMaxTicketsPerPlayer = 1001;
		assertThrows(IllegalArgumentException.class, () -> EventsConfig.validate(bad15), "maximum per player > tickets per draw");
		EventsConfig bad16 = cfg();
		bad16.lottoJackpotBase = 0;
		assertThrows(IllegalArgumentException.class, () -> EventsConfig.validate(bad16), "jackpot 0");
		EventsConfig bad17 = cfg();
		bad17.lottoPrizes = new ArrayList<>(List.of(1_000_000L, -5L));
		assertThrows(IllegalArgumentException.class, () -> EventsConfig.validate(bad17), "negative extra prize");
		EventsConfig bad18 = cfg();
		bad18.lottoJackpotMax = bad18.lottoJackpotBase - 1;
		assertThrows(IllegalArgumentException.class, () -> EventsConfig.validate(bad18), "jackpot limit below the base jackpot");
		EventsConfig bad19 = cfg();
		bad19.lottoJackpotMax = -1;
		assertThrows(IllegalArgumentException.class, () -> EventsConfig.validate(bad19), "negative jackpot limit");
		EventsConfig bad20 = cfg();
		bad20.lottoTreasuryReserve = -1;
		assertThrows(IllegalArgumentException.class, () -> EventsConfig.validate(bad20), "negative reserve");
		EventsConfig maxOk = cfg();
		maxOk.lottoJackpotMax = maxOk.lottoJackpotBase;
		maxOk.lottoTreasuryReserve = 0;
		EventsConfig.validate(maxOk);
		// Legacy key lottoFeePercent is ignored (even an invalid value does not tip the config)
		EventsConfig oldFee = cfg();
		oldFee.lottoFeePercent = 99;
		EventsConfig.validate(oldFee);
		EventsConfig noPrizes = cfg();
		noPrizes.lottoPrizes = null;
		EventsConfig.validate(noPrizes);
		eq(List.of(1_000_000L, 500_000L, 100_000L), noPrizes.lottoPrizes, "missing extra prizes → defaults");
		EventsConfig bad8 = cfg();
		bad8.marketDayCategories.get(0).items = new ArrayList<>(List.of("minecraft:nonexistent"));
		assertThrows(IllegalArgumentException.class, () -> EventsConfig.validate(bad8), "category without valid items");
		EventsConfig bad9 = cfg();
		bad9.goldRushJobs.add(bad9.goldRushJobs.get(0));
		assertThrows(IllegalArgumentException.class, () -> EventsConfig.validate(bad9), "duplicate job id");
		EventsConfig bad12 = cfg();
		bad12.marketDayCategories.add(bad12.marketDayCategories.get(0));
		assertThrows(IllegalArgumentException.class, () -> EventsConfig.validate(bad12), "duplicate category id");
		EventsConfig bad13 = cfg();
		bad13.marketDayCategories.get(0).items = new ArrayList<>();
		assertThrows(IllegalArgumentException.class, () -> EventsConfig.validate(bad13), "empty category");
		// Market Day eligibility depends on prices.json and shop.json, so it is no load error (that would
		// reset all other settings to code defaults); the item is skipped with a warning
		EventsConfig noPivot = cfg();
		noPivot.lottoEnabled = false;
		noPivot.lottoTicketPrice = 12_345;
		String firstCat = noPivot.marketDayCategories.get(0).id;
		noPivot.marketDayCategories.get(0).items.add("minecraft:diamond");
		EventsConfig.validate(noPivot);
		isFalse(noPivot.categoryItems(firstCat).contains(item("minecraft:diamond")), "Market Day item without pivot (diamond) is skipped");
		eq(noPivot.marketDayCategories.get(0).items.size() - 1, noPivot.categoryItems(firstCat).size(), "remaining items of the category stay");
		isFalse(noPivot.lottoEnabled, "remaining settings stay (lottery off)");
		eq(12_345L, noPivot.lottoTicketPrice, "remaining settings stay (ticket price)");
		EventsConfig tooHigh = cfg();
		tooHigh.marketDayMultiplier = 5.0; // Maximum: Market Day × rank above the pivot for all default items (at 3.0 bamboo and kelp still fit)
		tooHigh.randomEvents = false;
		EventsConfig.validate(tooHigh);
		for (EventsConfig.Category cat : tooHigh.marketDayCategories) isTrue(tooHigh.categoryItems(cat.id).isEmpty(), "Market Day × rank above the pivot: " + cat.id + " skipped");
		isFalse(tooHigh.randomEvents, "remaining settings stay (random events off)");
		EventManager noMarket = manager(new EventManager.Data(), tooHigh, 7);
		assertThrows(IllegalArgumentException.class, () -> noMarket.start(EventType.MARKET_DAY, null, 60_000, System.currentTimeMillis(), "test"),
			"Market Day without an eligible category does not start");
		assertThrows(IllegalArgumentException.class, () -> noMarket.start(EventType.MARKET_DAY, firstCat, 60_000, System.currentTimeMillis(), "test"),
			"empty category does not start");
		EventsConfig ok = cfg();
		ok.traderRanks.get(ok.traderRanks.size() - 1).bonusPercent = EventsConfig.MAX_RANK_BONUS_PERCENT;
		EventsConfig.validate(ok);
		for (EventsConfig.Category cat : ok.marketDayCategories) {
			eq(cat.items.size(), ok.categoryItems(cat.id).size(), "default category " + cat.id + " stays complete with the highest allowed rank bonus");
		}
		// Runtime filter: an unchecked config (e.g. stale state after a shop.json reload) boosts no non-shop
		// items
		EventsConfig unchecked = new EventsConfig();
		unchecked.marketDayCategories.get(0).items.add("minecraft:diamond");
		isFalse(unchecked.categoryItems(unchecked.marketDayCategories.get(0).id).contains(item("minecraft:diamond")),
			"ineligible item ignored at runtime");
		isFalse(unchecked.categoryItems(unchecked.marketDayCategories.get(0).id).isEmpty(), "eligible items stay");
	}

	private static void multipliersOnlyDuring() {
		EventsConfig c = cfg();
		EventManager.Data d = new EventManager.Data();
		EventManager m = manager(d, c, 1);
		Item iron = item("minecraft:cobblestone"); // Category "mining"
		Item stone = item("minecraft:oak_log"); // Category "wood"
		close(1.0, m.sellFactor(iron, T0), "neutral without event (sell)");
		close(1.0, m.jobFactor("miner", T0), "neutral without event (job)");
		close(1.0, m.chatFactor(T0), "neutral without event (chat)");

		EventManager.Active a = m.start(EventType.MARKET_DAY, "mining", 20 * MIN, T0, "test");
		eq("mining", a.param(), "category");
		close(1.5, m.sellFactor(iron, T0), "Market Day: cobblestone ×1.5");
		close(1.0, m.sellFactor(item("minecraft:diamond"), T0), "diamond (no shop item) never in Market Day");
		close(1.5, m.sellFactor(iron, T0 + 20 * MIN - 1), "still active shortly before the end");
		close(1.0, m.sellFactor(stone, T0), "oak log is not in mining");
		close(1.0, m.sellFactor(null, T0), "null item neutral");
		close(1.0, m.jobFactor("miner", T0), "Market Day changes no jobs");
		close(1.0, m.chatFactor(T0), "Market Day changes no chat games");
		close(1.0, m.sellFactor(iron, T0 + 20 * MIN), "neutral at the end (even without a tick)");
		close(1.0, m.sellFactor(iron, T0 + 60 * MIN), "neutral afterwards");
		isTrue(m.active(T0 + 20 * MIN).isEmpty(), "no active event after expiry");

		m.start(EventType.GOLD_RUSH, "miner", 25 * MIN, T0, "test");
		close(2.0, m.jobFactor("miner", T0 + MIN), "Gold Rush miner ×2");
		close(1.0, m.jobFactor("farmer", T0 + MIN), "other job neutral");
		close(1.0, m.sellFactor(iron, T0 + MIN), "Gold Rush changes no sell prices");
		close(1.0, m.chatFactor(T0 + MIN), "Gold Rush changes no chat games");
		m.stop(T0 + 2 * MIN);
		close(1.0, m.jobFactor("miner", T0 + 2 * MIN), "neutral after stop");

		m.start(EventType.LUCKY_HOUR, "whatever", 20 * MIN, T0, "test");
		isTrue(d.param == null, "Lucky Hour without parameter");
		close(2.0, m.chatFactor(T0), "Lucky Hour chat ×2");
		close(1.0, m.jobFactor("miner", T0), "Lucky Hour changes no jobs");
		close(1.0, m.sellFactor(iron, T0), "Lucky Hour changes no prices");
		close(1.0, m.chatFactor(T0 + 20 * MIN), "Lucky Hour over");
	}

	private static void goldRushMatching() {
		EventsConfig c = cfg();
		EventManager m = manager(new EventManager.Data(), c, 2);
		m.start(EventType.GOLD_RUSH, "lumberjack", 20 * MIN, T0, "test");
		for (String id : List.of("lumberjack", "Lumberjack", "LUMBERJACK", "woodcutter", " woodcutting ")) {
			close(2.0, m.jobFactor(id, T0), "Alias " + id);
		}
		for (String id : new String[] {"miner", "farmer", "", null, "lumberjackx"}) close(1.0, m.jobFactor(id, T0), "no match " + id);
		assertThrows(IllegalArgumentException.class, () -> m.start(EventType.GOLD_RUSH, "astronaut", MIN, T0, "x"), "unknown job");
		assertThrows(IllegalArgumentException.class, () -> m.start(EventType.MARKET_DAY, "nonexistent", MIN, T0, "x"), "unknown category");
		assertThrows(IllegalArgumentException.class, () -> m.start(EventType.MARKET_DAY, "mining", 0, T0, "x"), "duration 0");
		for (int i = 0; i < 50; i++) {
			EventManager.Active a = m.start(EventType.MARKET_DAY, null, MIN, T0, "x");
			isTrue(c.category(a.param()).isPresent(), "random category valid");
			EventManager.Active b = m.start(EventType.GOLD_RUSH, null, MIN, T0, "x");
			isTrue(c.job(b.param()).isPresent(), "random job valid");
		}
	}

	private static void scheduleTick(TestContext ctx) {
		EventsConfig c = cfg();
		EventManager.Data d = new EventManager.Data();
		EventManager m = manager(d, c, 3);
		long now = T0;
		EventManager.TickResult r = m.tick(now, 1, 12);
		isTrue(r.started() == null && r.ended() == null, "first tick only plans");
		long gap = d.nextEventAt - now;
		check(gap >= c.minIntervalMinutes * MIN && gap <= c.maxIntervalMinutes * MIN, "gap 2-4 h (" + gap / MIN + " min)");

		isTrue(m.tick(d.nextEventAt - 1, 5, 12).started() == null, "no start before due");
		now = d.nextEventAt;
		isTrue(m.tick(now, 0, 12).started() == null, "no start without players");
		isTrue(m.active(now).isEmpty(), "waits for players");
		c.randomEvents = false;
		isTrue(m.tick(now, 3, 12).started() == null, "randomEvents=false starts nothing");
		c.randomEvents = true;
		c.quietFromHour = 1;
		c.quietToHour = 8;
		isTrue(m.tick(now, 3, 3).started() == null, "quiet time 1-8 h");
		r = m.tick(now, 3, 12);
		isTrue(r.started() != null, "due + players online → start");
		EventManager.Active a = r.started();
		long dur = a.endsAt() - a.startedAt();
		check(dur >= c.minDurationMinutes * MIN && dur <= c.maxDurationMinutes * MIN, "duration 20-30 min (" + dur / MIN + ")");
		eq("auto", a.startedBy(), "started automatically");
		eq(0L, d.nextEventAt, "nothing planned during the event");

		isFalse(m.tick(a.endsAt() - 2 * MIN, 3, 12).warnEnding(), "no warning yet 2 min before");
		isTrue(m.tick(a.endsAt() - 30_000, 3, 12).warnEnding(), "warning 1 min before");
		isFalse(m.tick(a.endsAt() - 20_000, 3, 12).warnEnding(), "warning only once");

		r = m.tick(a.endsAt(), 3, 12);
		isTrue(r.ended() != null && r.started() == null, "end detected, no instant restart");
		eq(a.type(), r.ended().type(), "ended type");
		long gap2 = d.nextEventAt - a.endsAt();
		check(gap2 >= c.minIntervalMinutes * MIN && gap2 <= c.maxIntervalMinutes * MIN, "next event 2-4 h after the end");
		eq(1, d.history.size(), "history");

		Map<EventType, Integer> seen = new HashMap<>();
		for (int i = 0; i < 300; i++) m.randomType().ifPresent(t -> seen.merge(t, 1, Integer::sum));
		eq(3, seen.size(), "all types get picked");
		c.weights.put(EventType.GOLD_RUSH.id(), 0);
		for (int i = 0; i < 200; i++) isTrue(m.randomType().orElseThrow() != EventType.GOLD_RUSH, "weight 0 = never");
		ctx.detail("types " + seen);
	}

	private static void startStopHistory() {
		EventsConfig c = cfg();
		EventManager.Data d = new EventManager.Data();
		EventManager m = manager(d, c, 4);
		isTrue(m.stop(T0).isEmpty(), "stop without event");
		m.start(EventType.MARKET_DAY, "wood", 20 * MIN, T0, "admin");
		m.start(EventType.LUCKY_HOUR, null, 20 * MIN, T0 + MIN, "admin");
		eq(1, d.history.size(), "replaced event in history");
		eq("marketday", d.history.get(0).type, "history: Market Day");
		isTrue(m.active(T0 + MIN).orElseThrow().type() == EventType.LUCKY_HOUR, "new event active");
		isTrue(m.stop(T0 + 2 * MIN).isPresent(), "stop returns the event");
		isTrue(d.nextEventAt >= T0 + 2 * MIN + c.minIntervalMinutes * MIN, "replanned after stop");
		for (int i = 0; i < 30; i++) {
			m.start(EventType.LUCKY_HOUR, null, MIN, T0 + i, "x");
			m.stop(T0 + i);
		}
		eq(EventManager.HISTORY, d.history.size(), "history capped");
		// Expired event not ticked yet: stop() still ends it
		m.start(EventType.LUCKY_HOUR, null, MIN, T0, "x");
		isTrue(m.stop(T0 + 5 * MIN).isPresent(), "expired event gets archived");
		isTrue(d.type == null, "no event afterwards");
	}

	/** Real hooks and PriceGuard with the live state, restored exactly afterwards, no announcement. */
	private static void hooksLive(TestContext ctx) {
		String snapshot = DataStore.gson().toJson(EventsFeature.EVENT_STORE.get());
		ctx.cleanup(() -> {
			EventManager.Data restored = DataStore.gson().fromJson(snapshot, EventManager.Data.class);
			restored.fixNulls();
			EventsFeature.EVENT_STORE.set(restored);
		});
		FakePlayer p = ctx.player("EventsA");
		UUID id = p.getUUID();
		Item iron = item("minecraft:cobblestone");
		long now = System.currentTimeMillis();
		EventsFeature.EVENTS.stop(now);
		double before = Hooks.sellMultiplier(id, iron);
		OptionalLong sellBefore = PriceGuard.effectiveSellPrice(id, iron);
		close(1.0, Events.sellMultiplier(iron), "API without event 1.0");
		isFalse(Hooks.sellParts(id, iron).stream().anyMatch(x -> x.id().equals(Events.HOOK_MARKET_DAY)), "no Market Day part without event");

		EventsFeature.EVENTS.start(EventType.MARKET_DAY, "mining", 10 * MIN, now, "selftest");
		double mult = EventsFeature.CONFIG.get().marketDayMultiplier;
		close(before * mult, Hooks.sellMultiplier(id, iron), "Hooks: Market Day included");
		close(mult, Events.sellMultiplier(iron), "API Events.sellMultiplier");
		isTrue(Hooks.sellParts(id, iron).stream().anyMatch(x -> x.id().equals(Events.HOOK_MARKET_DAY) && x.label().equals("Market Day")), "Part 'Market Day'");
		OptionalLong sellDuring = PriceGuard.effectiveSellPrice(id, iron);
		long base = BasePrices.sellPrice(iron).orElseThrow();
		eq(PriceGuard.computeSell(base, PriceModifiers.get(iron), before * mult, PriceGuard.pivotPrice(iron)), sellDuring.orElse(-1),
			"PriceGuard includes Market Day");
		check(sellDuring.orElse(0) >= sellBefore.orElse(0), "price rises");
		isTrue(PriceGuard.breakdown(id, iron).loreLines().stream().anyMatch(l -> l.contains("Market Day")), "lore shows Market Day");

		EventsFeature.EVENTS.start(EventType.GOLD_RUSH, "miner", 10 * MIN, now, "selftest");
		close(before, Hooks.sellMultiplier(id, iron), "Market Day replaced → sell neutral again");
		double jobOther = Hooks.jobMultiplier(id, "farmer");
		close(jobOther * EventsFeature.CONFIG.get().goldRushMultiplier, Hooks.jobMultiplier(id, "miner"), "Hooks: Gold Rush miner");
		close(EventsFeature.CONFIG.get().goldRushMultiplier, Events.jobMultiplier("miner"), "API Events.jobMultiplier");
		isFalse(Hooks.flag(Events.FLAG_LUCKY_HOUR), "Lucky Hour flag off");

		double chatBefore = Hooks.chatGameMultiplier(id);
		EventsFeature.EVENTS.start(EventType.LUCKY_HOUR, null, 10 * MIN, now, "selftest");
		close(chatBefore * EventsFeature.CONFIG.get().luckyHourMultiplier, Hooks.chatGameMultiplier(id), "Hooks: Lucky Hour");
		close(EventsFeature.CONFIG.get().luckyHourMultiplier, Events.chatGameMultiplier(), "API Events.chatGameMultiplier");
		isTrue(Hooks.flag(Events.FLAG_LUCKY_HOUR), "Lucky Hour flag on");
		isTrue(Events.luckyHour(), "Events.luckyHour()");

		EventsFeature.EVENTS.stop(now);
		close(chatBefore, Hooks.chatGameMultiplier(id), "neutral after stop (chat)");
		close(before, Hooks.sellMultiplier(id, iron), "neutral after stop (sell)");
		isFalse(Hooks.flag(Events.FLAG_LUCKY_HOUR), "flag off after stop");
		eq(sellBefore.orElse(-1), PriceGuard.effectiveSellPrice(id, iron).orElse(-1), "price as before");
	}

	private static void rankThresholds() {
		List<TraderRank.Rank> ranks = TraderRank.ranksOf(cfg());
		long[][] cases = {
			{0, 0}, {1, 0}, {999_999, 0}, {1_000_000, 1}, {4_999_999, 1}, {5_000_000, 2}, {24_999_999, 2}, {25_000_000, 3},
			{99_999_999, 3}, {100_000_000, 4}, {Money.MAX, 4}, {-5, 0}};
		for (long[] c : cases) eq(c[1], TraderRank.rankFor(c[0], ranks).index(), "rank at " + c[0]);
		double[] mult = {1.0, 1.02, 1.05, 1.08, 1.12};
		String[] names = {"Apprentice", "Trader", "Merchant", "Magnate", "Tycoon"};
		for (int i = 0; i < 5; i++) {
			close(mult[i], ranks.get(i).multiplier(), "Bonus " + names[i]);
			eq(names[i], ranks.get(i).name(), "Name " + i);
			check(ranks.get(i).multiplier() < PriceGuard.SHOP_FLOOR_FACTOR, "bonus < 25 % (resale guard)");
		}
		close(0.5, TraderRank.progress(3_000_000, ranks.get(1), java.util.Optional.of(ranks.get(2))), "progress 50 %");
		close(1.0, TraderRank.progress(Money.MAX, ranks.get(4), java.util.Optional.empty()), "highest rank = 100 %");
		eq("+12%", ranks.get(4).bonusLabel(), "Label");
		eq("±0%", ranks.get(0).bonusLabel(), "label apprentice");
		eq(0, TraderRank.rankFor(123, List.of()).index(), "empty rank list neutral");
	}

	private static void rankFromItemsSold(TestContext ctx) {
		FakePlayer a = ctx.player("EventsRankA");
		FakePlayer b = ctx.player("EventsRankB");
		UUID ia = a.getUUID();
		Item diamond = item("minecraft:diamond");
		eq(0L, TraderRank.lifetimeSales(ia), "new: no sales");
		close(1.0, TraderRank.multiplier(ia), "new: apprentice ×1.0");
		close(1.0, TraderRank.multiplier(null), "null player neutral");
		close(1.0, TraderRank.multiplier(TestPlayers.uuid("EventsNobody")), "unknown player neutral");
		isTrue(PlayerDataService.peek(TestPlayers.uuid("EventsNobody")).isEmpty(), "query creates no data");

		List<TraderRank.Rank> ranks = TraderRank.ranks();
		TraderRank.Rank second = ranks.size() > 1 ? ranks.get(1) : ranks.get(0);
		long half = Math.max(1, second.minSales() / 2 + 1);
		soldNonShop(a, half);
		eq(half, TraderRank.lifetimeSales(ia), "sales counted (hook)");
		Hooks.fireItemsSold(a, 0, 5, List.of(line(diamond, 5, 0)));
		Hooks.fireItemsSold(a, -100, 5);
		eq(half, TraderRank.lifetimeSales(ia), "0 or negative does not count");
		soldNonShop(a, half);
		eq(2 * half, TraderRank.lifetimeSales(ia), "sales summed");
		eq(TraderRank.rankFor(2 * half, ranks).index(), TraderRank.rank(ia).index(), "rank from sales");
		check(TraderRank.rank(ia).index() >= second.index(), "promotion reached");

		// Sell bonus through Hooks and PriceGuard, B (no sales) for comparison
		double ma = Hooks.sellMultiplier(ia, diamond);
		double mb = Hooks.sellMultiplier(b.getUUID(), diamond);
		close(mb * TraderRank.multiplier(ia), ma, "Hooks: rank factor included");
		isTrue(TraderRank.multiplier(ia) == 1.0 || Hooks.sellParts(ia, diamond).stream()
			.anyMatch(x -> x.id().equals(TraderRank.HOOK_ID) && x.label().equals(TraderRank.HOOK_LABEL)), "part 'Trader Rank'");
		check(PriceGuard.effectiveSellPrice(ia, diamond).orElse(0) >= PriceGuard.effectiveSellPrice(b.getUUID(), diamond).orElse(0), "rank pays more");

		TraderRank.setSales(ia, Money.MAX - 5);
		soldNonShop(a, 1_000);
		eq(Money.MAX, TraderRank.lifetimeSales(ia), "capped at MAX");
		eq(ranks.size() - 1, TraderRank.rank(ia).index(), "highest rank");
		TraderRank.setSales(ia, 0);
		eq(0L, TraderRank.lifetimeSales(ia), "admin reset");
	}

	/**
	 * Sells a non-shop item (1 diamond for {@code cents}) like /sell: reports revenue and line through the
	 * hook.
	 */
	private static void soldNonShop(FakePlayer p, long cents) {
		sold(p, line(item("minecraft:diamond"), 1, cents));
	}

	private static Hooks.SoldLine line(Item item, long count, long unitPrice) {
		return new Hooks.SoldLine(item, count, unitPrice);
	}

	/**
	 * Sale like /sell or shop right-click: revenue = sum of count × price; reports count and lines through
	 * the hook.
	 */
	private static void sold(FakePlayer p, Hooks.SoldLine... lines) {
		long cents = 0;
		int count = 0;
		for (Hooks.SoldLine l : lines) {
			cents += l.count() * l.unitPrice();
			count += (int) l.count();
		}
		Hooks.fireItemsSold(p, cents, count, List.of(lines));
	}

	/**
	 * Gross sales would be cheap to buy: bones bought in a crash or flash sale at pivot + 1 and sold during
	 * hype at pivot lose 1 cent each yet credit the full revenue. So a shop item counts at most K × buyback
	 * loss; at normal prices and for non-shop items the full revenue counts.
	 */
	private static void rankNetSales(TestContext ctx) {
		FakePlayer a = ctx.player("EventsNetA");
		UUID ia = a.getUUID();
		ctx.cleanup(() -> PriceModifiers.removeSource(SELFTEST_SOURCE));
		Item bone = item("minecraft:bone");
		Item diamond = item("minecraft:diamond");
		long pivot = PriceGuard.pivotPrice(bone);
		check(pivot > 10, "bone is a shop item with pivot (" + pivot + ")");
		eq(-1L, PriceGuard.pivotPrice(diamond), "diamond is no shop item");
		int k = TraderRank.NET_CREDIT_FACTOR;

		check(EventsConfig.MAX_RANK_BONUS_PERCENT / 100.0 < 1.0 / k, "buyback loss (≥ 1/K of credited) > highest rank bonus");
		for (long pv : new long[] {0, 1, 7, 90, 1799}) {
			for (long sp = 1; sp <= pv + 2; sp++) {
				long unit = sp - TraderRank.deduction(sp, pv);
				check(unit >= 0 && unit <= sp, "0 ≤ credited ≤ price (s=" + sp + ", p=" + pv + ")");
				if (sp > pv) {
					eq(0L, unit, "above the pivot counts nothing (s=" + sp + ", p=" + pv + ")");
				} else {
					check(unit <= k * (pv + 1 - sp), "credited ≤ K × buyback loss (s=" + sp + ", p=" + pv + ")");
					if (5 * sp <= 4 * (pv + 1)) eq(sp, unit, "price ≤ 80 % of pivot counts fully (s=" + sp + ", p=" + pv + ")");
				}
			}
		}
		eq(0L, TraderRank.deduction(5_000, -1), "non-shop item: no deduction");
		eq(1_000L, TraderRank.credit(1_000, 10, TraderRank.Bound.NONE), "full revenue without deduction");
		eq(900L, TraderRank.credit(1_000, 10, new TraderRank.Bound(10, 1, 2)), "deduction per piece (smaller than by share)");
		eq(800L, TraderRank.credit(1_000, 100, new TraderRank.Bound(10, 1, 5)), "deduction by revenue share (smaller than per piece)");
		eq(6L, TraderRank.credit(10, 5, new TraderRank.Bound(1_000, 1, 3)), "share rounds up");
		eq(500L, TraderRank.credit(1_000, 0, new TraderRank.Bound(1, 1, 2)), "unknown count: only the share");
		eq(0L, TraderRank.credit(1_000, 10, new TraderRank.Bound(500, 1, 1)), "never negative");
		long big = Long.MAX_VALUE / 2;
		eq(1L, TraderRank.credit(big, Integer.MAX_VALUE, new TraderRank.Bound(big, big - 1, big)), "no overflow");
		// Wood on Market Day (150 at pivot 180) + 27 stacks of cobblestone at 10: nearly full credit
		TraderRank.Bound logAndCobble = TraderRank.Bound.of(150, TraderRank.deduction(150, 180)).max(TraderRank.Bound.of(10, TraderRank.deduction(10, 30)));
		eq(26L, logAndCobble.perItem(), "wood deduction per piece");
		eq(26_880L - 4_660L, TraderRank.credit(26_880, 1_792, logAndCobble), "mixed: deduction by share 26/150");
		netCreditProperty();
		netCreditExactProperty();

		// Live: hype up to the pivot (own source); the shop never sells below pivot + 1
		PriceModifiers.set(SELFTEST_SOURCE, bone, PriceModifiers.MAX_FACTOR, 0, 0, 0, "Selftest-Hype");
		long atPivot = PriceGuard.effectiveSellPrice(ia, bone).orElseThrow();
		eq(pivot, atPivot, "hype: sale capped at pivot");
		check(PriceGuard.effectiveShopBuyPrice(ia, bone).orElseThrow() >= pivot + 1, "shop buy ≥ pivot + 1");

		// Buyback round: 64 bones bought at pivot + 1, sold at pivot, rank stays
		List<TraderRank.Rank> ranks = TraderRank.ranks();
		long start = ranks.size() > 1 ? ranks.get(1).minSales() - 1_000 : 0;
		TraderRank.setSales(ia, start);
		int rankBefore = TraderRank.rank(ia).index();
		long unit = atPivot;
		sold(a, line(bone, 64, unit));
		long credited = TraderRank.lifetimeSales(ia) - start;
		eq(64L * k * (pivot + 1 - unit), credited, "at pivot only K × 1 cent per piece counts");
		check(64 * unit > 1_000, "gross would have been enough for promotion (" + Money.format(64 * unit) + ")");
		eq(rankBefore, TraderRank.rank(ia).index(), "buy and sell round brings no rank promotion");

		// Mixed (diamonds + bones at pivot): exact per line
		TraderRank.setSales(ia, 0);
		long dia = PriceGuard.effectiveSellPrice(ia, diamond).orElseThrow();
		long bonePrice = atPivot;
		long boneDeduction = TraderRank.deduction(bonePrice, pivot);
		long mixed = 2 * dia + 10 * bonePrice;
		long mixedExact = 2 * dia + 10 * (bonePrice - boneDeduction);
		sold(a, line(diamond, 2, dia), line(bone, 10, bonePrice));
		eq(mixedExact, TraderRank.lifetimeSales(ia), "mixed: exact per line");

		// Whatever else is priced for the player at the same moment (GUI lore, NPC buyer, /smpeco price) does
		// not change the credit; an item priced alongside at the pivot must not deduct from everything
		TraderRank.setSales(ia, 0);
		PriceGuard.effectiveSellPrice(ia, bone);
		PriceGuard.breakdown(ia, bone);
		sold(a, line(diamond, 3, dia));
		eq(3 * dia, TraderRank.lifetimeSales(ia), "diamonds count fully although bones were priced at pivot alongside");

		// Lines must match the sale, otherwise the cautious estimate counts (never more than exact)
		List<Hooks.SoldLine> lines = List.of(line(diamond, 2, dia), line(bone, 10, bonePrice));
		eq(mixedExact, TraderRank.netCredit(mixed, 12, lines).orElse(-1), "netCredit: exact");
		isTrue(TraderRank.netCredit(mixed + 1, 12, lines).isEmpty(), "netCredit: total ≠ revenue → unknown");
		isTrue(TraderRank.netCredit(mixed, 11, lines).isEmpty(), "netCredit: pieces ≠ count → unknown");
		isTrue(TraderRank.netCredit(mixed, 12, List.of()).isEmpty(), "netCredit: no lines → unknown");
		isTrue(TraderRank.netCredit(0, 0, List.of(line(diamond, 0, dia))).isEmpty(), "netCredit: nothing sold → unknown");
		isTrue(TraderRank.netCredit(dia, 1, List.of(line(diamond, 1, dia), line(bone, 1, 0))).isEmpty(), "netCredit: price 0 → unknown");
		isTrue(TraderRank.netCredit(Long.MAX_VALUE, 2, List.of(line(diamond, 2, Long.MAX_VALUE / 2 + 1))).isEmpty(), "netCredit: overflow → unknown");
		TraderRank.Bound boneBound = TraderRank.Bound.of(bonePrice, boneDeduction);
		TraderRank.setSales(ia, 0);
		Hooks.fireItemsSold(a, mixed + 1, 12, lines);
		check(TraderRank.lifetimeSales(ia) <= TraderRank.credit(mixed + 1, 12, boneBound), "inconsistent lines: safe deduction");

		// No lines (caller without a breakdown): deduction across all shop items (at least the bone's)
		TraderRank.setSales(ia, 0);
		Hooks.fireItemsSold(a, mixed, 12);
		check(TraderRank.lifetimeSales(ia) <= TraderRank.credit(mixed, 12, boneBound), "unknown items: safe deduction");
		PriceModifiers.remove(SELFTEST_SOURCE, bone);

		// Many cheap items + a few boosted like Market Day (wood ×1.5): exact per line
		Item log = item("minecraft:oak_log");
		Item cobble = item("minecraft:cobblestone");
		long logPivot = PriceGuard.pivotPrice(log);
		long cobblePivot = PriceGuard.pivotPrice(cobble);
		check(logPivot > 0 && cobblePivot > 0, "wood and cobblestone are shop items");
		PriceModifiers.set(SELFTEST_SOURCE, log, 1.5, 0, 0, 0, "Selftest-MarketDay");
		TraderRank.setSales(ia, 0);
		long logPrice = PriceGuard.effectiveSellPrice(ia, log).orElseThrow();
		long cobblePrice = PriceGuard.effectiveSellPrice(ia, cobble).orElseThrow();
		long logDeduction = TraderRank.deduction(logPrice, logPivot);
		long cobbleDeduction = TraderRank.deduction(cobblePrice, cobblePivot);
		sold(a, line(log, 64, logPrice), line(cobble, 1_728, cobblePrice));
		long creditedMixed = TraderRank.lifetimeSales(ia);
		eq(64 * (logPrice - logDeduction) + 1_728 * (cobblePrice - cobbleDeduction), creditedMixed, "wood + cobblestone: exact per line");
		if (cobbleDeduction == 0) check(creditedMixed >= 1_728 * cobblePrice, "wood + cobblestone: cobblestone at normal price counts fully");
		PriceModifiers.remove(SELFTEST_SOURCE, log);

		// At normal prices a shop item counts fully, a non-shop item always
		TraderRank.setSales(ia, 0);
		long normal = PriceGuard.effectiveSellPrice(ia, bone).orElseThrow();
		sold(a, line(bone, 64, normal));
		eq(64 * (normal - TraderRank.deduction(normal, pivot)), TraderRank.lifetimeSales(ia), "shop item at current price");
		if (PriceModifiers.get(bone) == 1.0 && Hooks.sellMultiplier(ia, bone) == 1.0) {
			eq(64 * normal, TraderRank.lifetimeSales(ia), "without hype or Market Day full revenue counts");
		}
		TraderRank.setSales(ia, 0);
		dia = PriceGuard.effectiveSellPrice(ia, diamond).orElseThrow();
		sold(a, line(diamond, 3, dia));
		eq(3 * dia, TraderRank.lifetimeSales(ia), "non-shop item counts fully");
		TraderRank.setSales(ia, 0);
		ctx.detail("at pivot: " + credited + " of " + 64 * unit + " cents credited");
	}

	/**
	 * Random mixed sales (prices around the pivot, non-shop items too): credited sales are never above exact
	 * (sum of count × (price - deduction)) and never below using only one of the two bounds.
	 */
	private static void netCreditProperty() {
		Random random = new Random(42);
		for (int round = 0; round < 2_000; round++) {
			int kinds = 1 + random.nextInt(5);
			long cents = 0, exact = 0;
			int count = 0;
			TraderRank.Bound bound = TraderRank.Bound.NONE;
			long maxDeduction = 0;
			long[] maxShare = {0, 1};
			for (int i = 0; i < kinds; i++) {
				long pivot = random.nextInt(4) == 0 ? -1 : 1 + random.nextInt(random.nextBoolean() ? 50 : 50_000);
				long top = pivot < 0 ? 100_000 : pivot;
				long price = 1 + (long) (top * (0.3 + 0.7 * random.nextDouble()));
				if (pivot >= 0) price = Math.min(price, pivot);
				int n = 1 + random.nextInt(random.nextBoolean() ? 64 : 2_304);
				long d = TraderRank.deduction(price, pivot);
				cents += n * price;
				exact += n * (price - d);
				count += n;
				bound = bound.max(TraderRank.Bound.of(price, d));
				maxDeduction = Math.max(maxDeduction, d);
				if (d * maxShare[1] > maxShare[0] * price) maxShare = new long[] {d, price};
			}
			long credit = TraderRank.credit(cents, count, bound);
			check(credit >= 0 && credit <= exact, "never more than exact (round " + round + ": " + credit + " > " + exact + ")");
			long perItemOnly = Math.max(0, cents - Math.min(cents, (long) count * maxDeduction));
			long shareOnly = cents - (cents * maxShare[0] + maxShare[1] - 1) / maxShare[1];
			check(credit >= perItemOnly && credit >= shareOnly, "at least as much as each bound alone (round " + round + ")");
			eq(maxDeduction, bound.perItem(), "largest per-piece deduction (round " + round + ")");
		}
	}

	/**
	 * Random lines from real shop items and diamonds: {@link TraderRank#netCredit} is exactly the sum of
	 * count × (price - deduction), never more than revenue and never less than the cautious estimate over the
	 * same items.
	 */
	private static void netCreditExactProperty() {
		Random random = new Random(7);
		List<Item> shopItems = new ArrayList<>(BasePrices.shopBuyPrices().keySet());
		Item diamond = item("minecraft:diamond");
		for (int round = 0; round < 1_000; round++) {
			int kinds = 1 + random.nextInt(4);
			List<Hooks.SoldLine> lines = new ArrayList<>();
			long cents = 0, expected = 0;
			int count = 0;
			TraderRank.Bound bound = TraderRank.Bound.NONE;
			for (int i = 0; i < kinds; i++) {
				Item it = shopItems.isEmpty() || random.nextInt(4) == 0 ? diamond : shopItems.get(random.nextInt(shopItems.size()));
				long pivot = PriceGuard.pivotPrice(it);
				long top = pivot < 0 ? 100_000 : Math.max(1, pivot);
				long price = Math.min(top, 1 + (long) (top * (0.3 + 0.7 * random.nextDouble())));
				int n = 1 + random.nextInt(2_304);
				long d = TraderRank.deduction(price, pivot);
				lines.add(line(it, n, price));
				cents += n * price;
				expected += n * (price - d);
				count += n;
				bound = bound.max(TraderRank.Bound.of(price, d));
			}
			long exact = TraderRank.netCredit(cents, count, lines).orElse(-1);
			eq(expected, exact, "exact (round " + round + ")");
			check(exact <= cents && exact >= TraderRank.credit(cents, count, bound), "estimate ≤ exact ≤ revenue (round " + round + ")");
		}
	}

	/**
	 * Test lottery with its own treasury ({@link Treasury.Ledger} on a test account), own legacy pot and
	 * default config (changeable before opening); the real draw and treasury stay untouched.
	 */
	private record TestLotto(Lottery lotto, Lottery.Data data, EventsConfig cfg, UUID pot, UUID bank, Treasury.Ledger ledger) {
		long flowIn(String reason) {
			return ledger.totalsAllTime().getOrDefault(reason, Treasury.Flow.ZERO).income();
		}

		long flowOut(String reason) {
			return ledger.totalsAllTime().getOrDefault(reason, Treasury.Flow.ZERO).expense();
		}
	}

	private static TestLotto testLotto(TestContext ctx, String name, long bankCents, java.util.function.Consumer<EventsConfig> tweak) {
		return testLotto(ctx, name, bankCents, tweak, new Lottery.Data());
	}

	private static TestLotto testLotto(TestContext ctx, String name, long bankCents, java.util.function.Consumer<EventsConfig> tweak, Lottery.Data d) {
		UUID pot = TestPlayers.uuid(name + "Pot");
		UUID bank = TestPlayers.uuid(name + "Bank");
		for (UUID id : List.of(pot, bank)) {
			EconomyService.deleteAccount(id);
			EconomyService.set(id, 0);
			ctx.cleanup(() -> EconomyService.deleteAccount(id));
		}
		if (bankCents > 0) EconomyService.set(bank, bankCents);
		EventsConfig c = cfg();
		if (tweak != null) {
			tweak.accept(c);
			EventsConfig.validate(c);
		}
		Treasury.Data td = new Treasury.Data();
		Treasury.Ledger ledger = new Treasury.Ledger(bank, () -> td, () -> {}, () -> 5.0);
		Lottery l = new Lottery(pot, () -> d, () -> {}, () -> c, Lottery.ledger(ledger), new Random(name.hashCode()));
		return new TestLotto(l, d, c, pot, bank, ledger);
	}

	/** Already opened (jackpot, ticket count, seed) and scheduled. */
	private static TestLotto openLotto(TestContext ctx, String name, long bankCents, java.util.function.Consumer<EventsConfig> tweak) {
		TestLotto t = testLotto(ctx, name, bankCents, tweak);
		Lottery.Migration m = t.lotto().migrate(T0);
		isTrue(m.migrated(), "new lottery opened");
		t.lotto().ensurePlanned(T0);
		return t;
	}

	private static UUID funded(TestContext ctx, String name, long cents) {
		UUID id = ctx.player(name).getUUID();
		EconomyService.ensureAccount(id, name);
		EconomyService.set(id, cents);
		return id;
	}

	private static void distinctInRange(List<Integer> numbers, int max, String msg) {
		eq((long) numbers.size(), new java.util.HashSet<>(numbers).size(), msg + ": distinct");
		for (int n : numbers) check(n >= 1 && n <= max, msg + ": #" + n + " in 1.." + max);
	}

	/** Treasury balance that covers the default draw ($72,000 + $16,000 + $25,000 reserve, so $200,000). */
	private static final long FUND = 20_000_000L;

	private static void lottoBuyLimits(TestContext ctx) {
		TestLotto t = openLotto(ctx, "EventsLotto1", FUND, null);
		Lottery l = t.lotto();
		UUID a = funded(ctx, "EventsLottoA", 600_000); // $6,000
		eq(1000L, l.ticketCount(), "1000 tickets");
		eq(7_200_000L, l.jackpot(), "Jackpot $72,000");
		eq(Lottery.BuyStatus.INVALID_COUNT, l.buy(a, 0).status(), "0 tickets");
		eq(Lottery.BuyStatus.INVALID_COUNT, l.buy(a, -3).status(), "negative count");
		Lottery.Purchase p = l.buy(a, 5);
		isTrue(p.ok(), "5 tickets");
		eq(50_000L, p.cost(), "5 × $100");
		eq(550_000L, EconomyService.balance(a), "charged");
		eq(FUND + 50_000L, EconomyService.balance(t.bank()), "money in the treasury");
		eq(50_000L, t.flowIn(Lottery.R_TICKETS), "treasury reason tickets");
		eq(5L, l.tickets(a), "5 tickets counted");
		eq(5L, p.numbers().size(), "5 numbers");
		eq(p.numbers(), l.numbers(a), "numbers saved");
		distinctInRange(p.numbers(), 1000, "numbers");

		// More than the maximum: limited to the remainder, but not enough money, nothing bought
		EconomyService.set(a, 100_000); // $1,000 = 10 tickets
		Lottery.Purchase tooMuch = l.buy(a, 100);
		eq(Lottery.BuyStatus.INSUFFICIENT_FUNDS, tooMuch.status(), "not enough money for 45 tickets");
		eq(450_000L, tooMuch.cost(), "cost of the limited amount");
		eq(100_000L, EconomyService.balance(a), "nothing charged");
		eq(5L, l.tickets(a), "no tickets added");
		eq(FUND + 50_000L, EconomyService.balance(t.bank()), "treasury unchanged");
		EconomyService.set(a, 10_000_000);
		Lottery.Purchase rest = l.buy(a, 100);
		isTrue(rest.ok(), "rest bought");
		eq(45L, rest.bought(), "limited to 50");
		eq(50L, rest.total(), "now 50");
		eq(Lottery.BuyStatus.MAX_REACHED, l.buy(a, 1).status(), "maximum reached");
		eq(FUND + 500_000L, EconomyService.balance(t.bank()), "treasury = 50 × $100");
		eq(10_000_000L - 450_000L, EconomyService.balance(a), "exactly 45 tickets paid");
		distinctInRange(l.numbers(a), 1000, "all 50 numbers");
		eq(50L, l.soldCount(), "50 sold");

		t.cfg().lottoEnabled = false;
		UUID b = funded(ctx, "EventsLottoB", 1_000_000);
		eq(Lottery.BuyStatus.DISABLED, l.buy(b, 1).status(), "disabled");
		t.cfg().lottoEnabled = true;
		eq(Lottery.BuyStatus.ERROR, l.buy(t.pot(), 1).status(), "legacy pot cannot buy tickets");
		eq(Lottery.BuyStatus.ERROR, l.buy(Treasury.ID, 1).status(), "treasury cannot buy tickets");
		UUID broke = funded(ctx, "EventsLottoC", 9_999);
		eq(Lottery.BuyStatus.INSUFFICIENT_FUNDS, l.buy(broke, 1).status(), "$99.99 is not enough");
		eq(9_999L, EconomyService.balance(broke), "broke player unchanged");
		eq(1L, l.participants(), "1 participant");
		// No sales before migration (legacy state)
		TestLotto old = testLotto(ctx, "EventsLotto1b", 0, null);
		eq(Lottery.BuyStatus.ERROR, old.lotto().buy(b, 1).status(), "no purchase before migration");
		eq(1_000_000L, EconomyService.balance(b), "nothing charged");
	}

	private static void lottoSoldOut(TestContext ctx) {
		TestLotto t = openLotto(ctx, "EventsLotto2", FUND, c -> c.lottoTicketsPerDraw = 60);
		Lottery l = t.lotto();
		UUID a = funded(ctx, "EventsLottoA", 10_000_000);
		UUID b = funded(ctx, "EventsLottoB", 10_000_000);
		UUID c = funded(ctx, "EventsLottoC", 10_000_000);
		eq(60L, l.ticketCount(), "60 tickets (config)");
		eq(50L, l.buy(a, 50).bought(), "A: 50");
		Lottery.Purchase pb = l.buy(b, 50);
		isTrue(pb.ok(), "B buys the rest");
		eq(10L, pb.bought(), "B: only 10 left");
		eq(50L, pb.wanted(), "B wanted 50");
		eq(10_000_000L - 100_000L, EconomyService.balance(b), "B pays only 10 tickets");
		Lottery.Purchase pc = l.buy(c, 1);
		eq(Lottery.BuyStatus.SOLD_OUT, pc.status(), "sold out");
		eq(10_000_000L, EconomyService.balance(c), "C pays nothing");
		Map<Integer, UUID> owners = l.owners();
		eq(60L, owners.size(), "every number assigned");
		for (int n = 1; n <= 60; n++) isTrue(owners.containsKey(n), "#" + n + " sold");
		eq(FUND + 60L * 10_000L, EconomyService.balance(t.bank()), "treasury = 60 × $100");
		ctx.detail("60/60 sold, B got 10 instead of 50, C sold out");
	}

	private static void lottoUniform(TestContext ctx) {
		java.util.SplittableRandom seeds = new java.util.SplittableRandom(20261010L);
		// Small draw (10 tickets, 4 winning numbers): each position uniform, numbers distinct
		int n = 10;
		int k = 4;
		int samples = 200_000;
		long[][] counts = new long[k][n + 1];
		for (int s = 0; s < samples; s++) {
			int[] w = Lottery.winningNumbers(seeds.nextLong(), n, k);
			eq((long) k, java.util.Arrays.stream(w).distinct().count(), "4 distinct numbers");
			for (int pos = 0; pos < k; pos++) {
				check(w[pos] >= 1 && w[pos] <= n, "in range");
				counts[pos][w[pos]]++;
			}
		}
		double expected = samples / (double) n;
		double worst = 0;
		for (int pos = 0; pos < k; pos++) {
			double chi = 0;
			for (int x = 1; x <= n; x++) chi += Math.pow(counts[pos][x] - expected, 2) / expected;
			worst = Math.max(worst, chi);
			check(chi < 40, "position " + pos + " uniform (χ²=" + chi + ", 9 df)");
		}
		// Real size (1000 tickets): each number equally often among winners, jackpot number averages 500.5
		int big = 1000;
		int samples2 = 100_000;
		long[] hits = new long[big + 1];
		double jackpotSum = 0;
		for (int s = 0; s < samples2; s++) {
			int[] w = Lottery.winningNumbers(seeds.nextLong(), big, k);
			jackpotSum += w[0];
			for (int x : w) hits[x]++;
		}
		double exp2 = samples2 * k / (double) big;
		double chi2 = 0;
		for (int x = 1; x <= big; x++) chi2 += Math.pow(hits[x] - exp2, 2) / exp2;
		check(chi2 > 750 && chi2 < 1250, "1000 numbers equally often (χ²=" + chi2 + ", 999 df)");
		double mean = jackpotSum / samples2;
		check(Math.abs(mean - 500.5) < 4, "jackpot number averages 500.5 (" + mean + ")");
		eq(java.util.Arrays.toString(Lottery.winningNumbers(42, big, k)), java.util.Arrays.toString(Lottery.winningNumbers(42, big, k)), "deterministic");
		assertThrows(IllegalArgumentException.class, () -> Lottery.winningNumbers(1, 3, 4), "more winning numbers than tickets");
		// Ticket assignment: only free numbers, uniform
		java.util.Set<Integer> sold = new java.util.HashSet<>();
		for (int x = 1; x <= 10; x++) sold.add(x);
		long[] picks = new long[21];
		Random r = new Random(7);
		for (int s = 0; s < 50_000; s++) {
			int x = Lottery.pickUnsold(20, sold, 1, r).get(0);
			check(x > 10 && x <= 20, "only free numbers");
			picks[x]++;
		}
		double chi3 = 0;
		for (int x = 11; x <= 20; x++) chi3 += Math.pow(picks[x] - 5_000, 2) / 5_000;
		check(chi3 < 40, "free numbers uniform (χ²=" + chi3 + ")");
		List<Integer> all = Lottery.pickUnsold(20, sold, 10, r);
		eq(10L, new java.util.HashSet<>(all).size(), "10 distinct free numbers");
		assertThrows(IllegalArgumentException.class, () -> Lottery.pickUnsold(20, sold, 11, r), "not more than free");
		ctx.detail(String.format(java.util.Locale.ROOT, "χ² small ≤ %.1f, large %.0f, jackpot mean %.1f", worst, chi2, mean));
	}

	private static void lottoNoAutoWin(TestContext ctx) {
		TestLotto t = openLotto(ctx, "EventsLotto3", 100_000_000, null);
		Lottery l = t.lotto();
		UUID a = funded(ctx, "EventsLottoA", 10_000_000);
		isTrue(l.buy(a, 50).ok(), "only player buys 50 tickets");
		java.util.Set<Integer> mine = new java.util.HashSet<>(l.numbers(a));
		close(0.05, l.chance(a), "chance per prize 50/1000");
		close(Lottery.chanceAny(1000, 50, 4), l.chanceAny(a), "chance of any prize");
		close(1 - (950.0 / 1000) * (949.0 / 999) * (948.0 / 998) * (947.0 / 997), l.chanceAny(a), "1 − C(950,4)/C(1000,4)");
		// Simulation over the real number draw: playing alone is not an automatic win
		java.util.SplittableRandom seeds = new java.util.SplittableRandom(5L);
		int runs = 40_000;
		int jackpots = 0;
		int any = 0;
		long noHitSeed = 0;
		long hitSeed = 0;
		for (int s = 0; s < runs; s++) {
			long seed = seeds.nextLong();
			int[] w = Lottery.winningNumbers(seed, 1000, 4);
			boolean jp = mine.contains(w[0]);
			boolean hit = false;
			for (int x : w) hit |= mine.contains(x);
			if (jp) {
				jackpots++;
				if (hitSeed == 0) hitSeed = seed;
			}
			if (hit) any++;
			else if (noHitSeed == 0) noHitSeed = seed;
		}
		double pj = jackpots / (double) runs;
		double pa = any / (double) runs;
		double expectAny = l.chanceAny(a);
		check(Math.abs(pj - 0.05) < 0.006, "jackpot rate alone with 50 tickets ≈ 5 % (" + pj + ")");
		check(Math.abs(pa - expectAny) < 0.01, "any prize ≈ " + expectAny + " (" + pa + ")");

		// Real draw without a hit: no prize, jackpot rolls over
		long before = EconomyService.balance(a);
		t.data().seed = noHitSeed;
		Lottery.Draw d = l.draw(T0, null);
		isFalse(d.jackpotWon(), "alone, jackpot number not drawn → not won");
		for (Lottery.Prize p : d.prizes) check(p.winner == null && p.paid == 0, "no prize for #" + p.number);
		eq(before, EconomyService.balance(a), "nothing paid");
		eq(14_400_000L, l.jackpot(), "Jackpot $144,000");
		isTrue(l.buy(a, 50).ok(), "next draw: 50 tickets again");
		mine = new java.util.HashSet<>(l.numbers(a));
		// Find a seed that hits the jackpot for the new numbers
		long seed = 0;
		for (long s = 1; s < 1_000_000; s++) {
			if (mine.contains(Lottery.winningNumbers(s, 1000, 4)[0])) {
				seed = s;
				break;
			}
		}
		check(seed != 0, "seed with a hit found");
		t.data().seed = seed;
		before = EconomyService.balance(a);
		long bankBefore = EconomyService.balance(t.bank());
		Lottery.Draw win = l.draw(T0 + 86_400_000L, null);
		isTrue(win.jackpotWon(), "jackpot number belongs to the player");
		long won = win.paid();
		check(won >= 14_400_000L, "at least the jackpot paid (" + won + ")");
		eq(before + won, EconomyService.balance(a), "paid out");
		eq(bankBefore - won, EconomyService.balance(t.bank()), "from the treasury");
		eq(7_200_000L, l.jackpot(), "$72,000 again afterwards");
		ctx.detail(String.format(java.util.Locale.ROOT, "Jackpot %.2f%%, any win %.2f%% (expected 5.00/%.2f)", pj * 100, pa * 100, expectAny * 100));
	}

	private static void lottoCarryover(TestContext ctx) {
		TestLotto t = openLotto(ctx, "EventsLotto4", 100_000_000, null);
		Lottery l = t.lotto();
		UUID a = funded(ctx, "EventsLottoA", 10_000_000);
		long base = 7_200_000L;
		eq(base, l.jackpot(), "start: base jackpot");
		Lottery.Draw d1 = l.draw(T0, null);
		isFalse(d1.jackpotWon(), "no winner without tickets");
		eq(base, d1.jackpot, "draw 1: $72,000");
		eq(2 * base, d1.nextJackpot, "→ $144,000");
		eq(2 * base, l.jackpot(), "running jackpot $144,000");
		eq(4L, d1.prizes.size(), "4 winning numbers");
		eq(0L, d1.paid() + d1.owed(), "nothing paid");
		Lottery.Draw d2 = l.draw(T0 + 1, null);
		eq(3 * base, d2.nextJackpot, "→ $216,000");
		eq(3L, l.drawId(), "draw #3");
		eq(2L, l.draws().size(), "two draws in history");

		// Extra prize won, jackpot not: the extra prize does not roll, the jackpot grows
		isTrue(l.buy(a, 2).ok(), "2 tickets");
		List<Integer> mine = l.numbers(a);
		int other = firstUnsold(l);
		long before = EconomyService.balance(a);
		Lottery.Draw d3 = l.draw(T0 + 2, new int[] {other, mine.get(0), otherThan(l, other, mine), otherThan2(l, other, mine)});
		isFalse(d3.jackpotWon(), "jackpot number not sold");
		eq(1_000_000L, d3.prizes.get(1).paid, "$10,000 for the hit");
		eq(before + 1_000_000L, EconomyService.balance(a), "paid out");
		eq(4 * base, l.jackpot(), "Jackpot → $288,000");
		eq(1_000_000L, l.prizeAmount(1), "extra prize stays $10,000 (no rollover)");
		eq(List.of(1_000_000L, 500_000L, 100_000L), l.prizes(), "extra prizes unchanged");

		isTrue(l.buy(a, 1).ok(), "1 ticket");
		int mineNow = l.numbers(a).get(0);
		before = EconomyService.balance(a);
		Lottery.Draw d4 = l.draw(T0 + 3, new int[] {mineNow, firstUnsold(l), otherThan(l, firstUnsold(l), List.of(mineNow)),
			otherThan2(l, firstUnsold(l), List.of(mineNow))});
		isTrue(d4.jackpotWon(), "jackpot won");
		eq(4 * base, d4.prizes.get(0).paid, "$288,000 paid");
		eq(before + 4 * base, EconomyService.balance(a), "at the winner");
		eq(base, d4.nextJackpot, "$72,000 afterwards");
		eq(base, l.jackpot(), "running jackpot $72,000");

		// No tickets and lottoRolloverWithoutTickets=false: draw cancelled, jackpot stays
		t.cfg().lottoRolloverWithoutTickets = false;
		isTrue(l.shouldSkip(), "no tickets + no rollover → cancel");
		long id = l.drawId();
		l.skip(T0 + 4);
		eq(id, l.drawId(), "same draw");
		eq(base, l.jackpot(), "jackpot unchanged");
		check(l.nextDrawAt() > T0 + 4, "new slot");
		isTrue(l.buy(a, 1).ok(), "1 ticket");
		isFalse(l.shouldSkip(), "with tickets it draws");
		t.cfg().lottoRolloverWithoutTickets = true;
		t.cfg().lottoEnabled = false;
		isFalse(l.shouldSkip(), "lottery off but tickets sold → draws");
		l.removePlayer(a);
		isTrue(l.shouldSkip(), "lottery off and no tickets → cancelled (jackpot does not grow)");
		t.cfg().lottoEnabled = true;
		isFalse(l.shouldSkip(), "on, no tickets → rollover");
		eq(Money.MAX, Lottery.saturatingAdd(Money.MAX - 5, base), "jackpot saturates at Money.MAX");
		eq(Money.MAX, Lottery.saturatingAdd(Long.MAX_VALUE - 5, base), "no overflow");
	}

	private static int firstUnsold(Lottery l) {
		Map<Integer, UUID> owners = l.owners();
		for (int n = 1; n <= l.ticketCount(); n++) if (!owners.containsKey(n)) return n;
		throw new AssertionError("all sold");
	}

	private static int otherThan(Lottery l, int a, List<Integer> mine) {
		Map<Integer, UUID> owners = l.owners();
		for (int n = l.ticketCount(); n >= 1; n--) if (n != a && !owners.containsKey(n) && !mine.contains(n)) return n;
		throw new AssertionError("no free number");
	}

	private static int otherThan2(Lottery l, int a, List<Integer> mine) {
		int b = otherThan(l, a, mine);
		Map<Integer, UUID> owners = l.owners();
		for (int n = l.ticketCount(); n >= 1; n--) if (n != a && n != b && !owners.containsKey(n) && !mine.contains(n)) return n;
		throw new AssertionError("no free number");
	}

	/**
	 * Treasury and lottery: sales only while the treasury covers jackpot + extras + owed + reserve; payouts
	 * smallest prize first and never below the reserve; settling refunds first, then smallest owed amount;
	 * the money supply stays constant.
	 */
	private static void lottoConservation(TestContext ctx) {
		long reserve = 2_500_000L;
		long need = 7_200_000L + 1_600_000L + reserve; // Jackpot + extras + reserve = $113,000
		TestLotto t = openLotto(ctx, "EventsLotto5", need - 1, null);
		Lottery l = t.lotto();
		UUID a = funded(ctx, "EventsLottoA", 1_000_000);
		UUID b = funded(ctx, "EventsLottoB", 1_000_000);
		UUID c = funded(ctx, "EventsLottoC", 1_000_000);
		java.util.function.LongSupplier total = () -> EconomyService.balance(a) + EconomyService.balance(b) + EconomyService.balance(c)
			+ EconomyService.balance(t.bank()) + EconomyService.balance(t.pot());
		long start = total.getAsLong();
		eq(reserve, t.cfg().lottoTreasuryReserve, "default reserve $25,000");
		eq(need, l.coverageNeeded(), "coverage = jackpot + all extra prizes + reserve");
		isFalse(l.covered(), "1 cent short → sales paused");
		eq(Lottery.BuyStatus.NOT_COVERED, l.buy(a, 1).status(), "purchase rejected");
		eq(1_000_000L, EconomyService.balance(a), "nothing charged");
		eq(need - 1, EconomyService.balance(t.bank()), "treasury unchanged");
		eq(0L, l.soldCount(), "no tickets");
		isTrue(l.shouldSkip(), "sales paused and no tickets → draw cancelled");
		t.cfg().lottoRolloverWithoutTickets = true;
		isTrue(l.shouldSkip(), "also with rollover without tickets: nobody could buy, jackpot does not grow");
		t.cfg().lottoRolloverWithoutTickets = false;
		isTrue(EconomyService.transfer(c, t.bank(), 1).ok(), "income 1 cent");
		isTrue(l.covered(), "covered now");
		isTrue(l.buy(a, 10).ok() && l.buy(b, 20).ok(), "tickets bought");
		eq(need + 300_000L, EconomyService.balance(t.bank()), "tickets → treasury");
		eq(start, total.getAsLong(), "purchase preserves the money supply");

		// Until the draw the treasury spends elsewhere (here on C): only $500 above the reserve
		isTrue(EconomyService.transfer(t.bank(), c, EconomyService.balance(t.bank()) - reserve - 50_000L).ok(), "treasury spends money");
		eq(50_000L, l.spendable(), "$500 above the reserve");
		List<Integer> na = l.numbers(a);
		List<Integer> nb = l.numbers(b);
		int unsold = firstUnsold(l);
		// Jackpot to A, $10,000 to B, $5,000 unsold, $1,000 to A
		Lottery.Draw d = l.draw(T0, new int[] {na.get(0), nb.get(0), unsold, na.get(1)});
		isTrue(d.forced && d.admin, "given numbers, marked as admin draw");
		isTrue(d.jackpotWon(), "A wins the jackpot");
		eq(a, d.prizes.get(0).winner, "jackpot winner A");
		eq(50_000L, d.prizes.get(3).paid, "smallest prize first: A gets $500 of the $1,000 right away");
		eq(50_000L, d.prizes.get(3).owed, "A: $500 owed");
		eq(0L, d.prizes.get(1).paid, "B: nothing left above the reserve");
		eq(1_000_000L, d.prizes.get(1).owed, "B: $10,000 owed");
		eq(0L, d.prizes.get(0).paid, "jackpot last: nothing right away");
		eq(7_200_000L, d.prizes.get(0).owed, "jackpot fully owed");
		isTrue(d.prizes.get(2).winner == null, "$5,000: not sold");
		for (Lottery.Prize p : d.prizes) eq(p.winner == null ? 0 : p.amount, p.paid + p.owed, "paid + owed = prize (#" + p.number + ")");
		eq(reserve, EconomyService.balance(t.bank()), "treasury stays exactly at the reserve");
		eq(start, total.getAsLong(), "draw preserves the money supply (nothing created)");
		eq(3L, l.owed().size(), "3 owed payouts");
		eq(50_000L + 1_000_000L + 7_200_000L, l.owedTotal(), "owed total");
		eq(50_000L + 7_200_000L, l.owedTo(a), "owed to A");
		eq(50_000L, t.flowOut(Lottery.R_PRIZES), "treasury reason prizes");
		eq(300_000L, t.flowIn(Lottery.R_TICKETS), "treasury reason tickets");
		isTrue(l.settleOwed().isEmpty(), "treasury at the reserve settles nothing");
		eq(reserve, EconomyService.balance(t.bank()), "reserve stays");
		isFalse(l.covered(), "owed prizes: next draw paused until the treasury covers them");
		eq(Lottery.BuyStatus.NOT_COVERED, l.buy(c, 1).status(), "no sales while owed prizes are not covered");

		// Notification (test players are offline, so pending messages)
		int pa0 = PlayerDataService.getPendingMessages(a).size();
		int pb0 = PlayerDataService.getPendingMessages(b).size();
		java.util.Set<UUID> winners = EventsRuntime.notifyWinners(d);
		eq(java.util.Set.of(a, b), winners, "winners A and B");
		eq(pa0 + 2L, PlayerDataService.getPendingMessages(a).size(), "A: two prize messages");
		eq(pb0 + 1L, PlayerDataService.getPendingMessages(b).size(), "B: one prize message");
		isTrue(PlayerDataService.getPendingMessages(b).get(pb0).contains("Lottery win!"), "pending message text");
		isTrue(PlayerDataService.getPendingMessages(b).get(pb0).contains("follows as soon as the Treasury"), "note about owed amount");

		// Income $6,000: smallest owed first, A's remainder ($500) in full, then $5,500 to B, the jackpot
		// waits
		isTrue(EconomyService.transfer(c, t.bank(), 600_000).ok(), "income $6,000");
		List<Lottery.Owed> first = l.settleOwed();
		eq(1L, first.size(), "one entry now paid in full");
		isTrue(first.get(0).player.equals(a) && first.get(0).rank == 3, "first A's $1,000 prize (smallest owed amount)");
		eq(reserve, EconomyService.balance(t.bank()), "exactly at the reserve again");
		eq(2L, l.owed().size(), "2 still owed");
		eq(450_000L, l.owedTo(b), "B: $4,500 still owed");
		eq(7_200_000L, l.owedTo(a), "jackpot still fully owed");
		// Refund of legacy tickets (the player's own money) first and also from the reserve
		Lottery.Owed refund = new Lottery.Owed();
		refund.player = c;
		refund.name = "EventsLottoC";
		refund.rank = Lottery.REFUND;
		refund.tickets = 1;
		refund.amount = 10_000;
		refund.total = 10_000;
		refund.since = T0;
		t.data().owed.add(refund);
		List<Lottery.Owed> refunded = l.settleOwed();
		isTrue(refunded.size() == 1 && refunded.get(0).rank == Lottery.REFUND, "refund paid, prizes wait");
		eq(reserve - 10_000L, EconomyService.balance(t.bank()), "refund also from the reserve");
		eq(10_000L, t.flowOut(Lottery.R_REFUND), "treasury reason refund");
		// Plenty of money: the rest in full, smaller first
		EconomyService.deposit(t.bank(), 20_000_000);
		start += 20_000_000;
		List<Lottery.Owed> done = l.settleOwed();
		eq(2L, done.size(), "both paid now");
		isTrue(done.get(0).player.equals(b) && done.get(1).rank == Lottery.JACKPOT, "B before the jackpot");
		isFalse(l.hasOwed(), "nothing owed anymore");
		eq(1_000_000L - 100_000L + 7_200_000L + 100_000L, EconomyService.balance(a), "A: exactly jackpot + $1,000");
		eq(1_000_000L - 200_000L + 1_000_000L, EconomyService.balance(b), "B: exactly $10,000");
		eq(start, total.getAsLong(), "money supply preserved");
		eq(7_200_000L + 1_000_000L + 100_000L, t.flowOut(Lottery.R_PRIZES), "treasury paid exactly the prizes");
		check(EconomyService.balance(t.bank()) >= reserve, "treasury above the reserve again");
		isTrue(l.covered(), "covered → sales open again");
		int pb1 = PlayerDataService.getPendingMessages(b).size();
		EventsRuntime.notifySettled(done);
		eq(pb1 + 1L, PlayerDataService.getPendingMessages(b).size(), "B: message about the settled payment");
		isTrue(PlayerDataService.getPendingMessages(b).get(pb1).contains("paid out in full"), "settled payment text");
		ctx.detail("sales only from $113,000 treasury; jackpot $72,000 at $500 above the reserve: smallest first, reserve stays, money supply constant");
	}

	private static void lottoExactlyOnce(TestContext ctx) {
		TestLotto t = openLotto(ctx, "EventsLotto6", 100_000_000, null);
		Lottery l = t.lotto();
		UUID a = funded(ctx, "EventsLottoA", 10_000_000);
		UUID b = funded(ctx, "EventsLottoB", 10_000_000);
		isTrue(l.buy(a, 30).ok() && l.buy(b, 20).ok(), "tickets");
		// Find a seed with a winner so the draw surely pays
		Map<Integer, UUID> owners = l.owners();
		long seed = 0;
		for (long s = 1; s < 1_000_000 && seed == 0; s++) {
			for (int x : Lottery.winningNumbers(s, 1000, 4)) if (owners.containsKey(x)) seed = s;
		}
		t.data().seed = seed;
		t.data().nextDrawAt = T0 + 1000;
		String saved = DataStore.gson().toJson(t.data());

		// "Restart" before the draw: everything loaded the same
		TestLotto r1 = reload(ctx, t, saved);
		eq(l.numbers(a), r1.lotto().numbers(a), "A's numbers after the restart");
		eq(l.numbers(b), r1.lotto().numbers(b), "B's numbers after the restart");
		eq(l.jackpot(), r1.lotto().jackpot(), "jackpot after the restart");
		eq(l.drawId(), r1.lotto().drawId(), "draw number after the restart");
		eq(java.util.Arrays.toString(l.plannedNumbers()), java.util.Arrays.toString(r1.lotto().plannedNumbers()), "same winning numbers planned");
		isTrue(r1.lotto().isDue(T0 + 1000), "draw due");

		long a0 = EconomyService.balance(a);
		long b0 = EconomyService.balance(b);
		long bank0 = EconomyService.balance(t.bank());
		Lottery.Draw first = r1.lotto().draw(T0 + 1000, null);
		check(first.paid() > 0, "draw pays out (" + first.paid() + ")");
		long a1 = EconomyService.balance(a);
		long b1 = EconomyService.balance(b);
		eq(a0 + b0 + first.paid(), a1 + b1, "prizes at A and B");
		eq(bank0 - first.paid(), EconomyService.balance(t.bank()), "from the treasury");

		// Saved, then restart: no longer due, not paid twice
		String after = DataStore.gson().toJson(r1.data());
		TestLotto r2 = reload(ctx, t, after);
		isFalse(r2.lotto().isDue(T0 + 1000), "not due again after the restart");
		isFalse(r2.lotto().isDue(T0 + 60_000), "not a minute later either");
		eq(first.id + 1, r2.lotto().drawId(), "next draw opened");
		eq(0L, r2.lotto().soldCount(), "tickets cleared");
		eq(first.id, r2.lotto().draws().get(0).id, "result in history");
		eq(first.numbers(), r2.lotto().draws().get(0).numbers(), "numbers in history");
		r2.lotto().ensurePlanned(T0 + 2000);
		r2.lotto().settleOwed();
		eq(a1, EconomyService.balance(a), "A unchanged after the restart");
		eq(b1, EconomyService.balance(b), "B unchanged after the restart");

		// Crash before saving: previous state (lotto.json and balances), so the catch-up draw draws the same
		// numbers
		EconomyService.set(a, a0);
		EconomyService.set(b, b0);
		EconomyService.set(t.bank(), bank0);
		TestLotto r3 = reload(ctx, t, saved);
		isTrue(r3.lotto().isDue(T0 + 5000), "state before the draw: due (catch-up)");
		Lottery.Draw replay = r3.lotto().draw(T0 + 5000, null);
		eq(first.numbers(), replay.numbers(), "same winning numbers (seed saved)");
		eq(first.paid(), replay.paid(), "same payout");
		eq(a1, EconomyService.balance(a), "A as after the first draw");
		eq(b1, EconomyService.balance(b), "B as after the first draw");
		ctx.detail("numbers " + first.numbers() + ", paid " + Money.format(first.paid()) + " exactly once");
	}

	/**
	 * "Restart": re-reads the lotto.json contents (Gson + fixNulls) and builds a new lottery on them (same
	 * treasury and config).
	 */
	private static TestLotto reload(TestContext ctx, TestLotto t, String json) {
		Lottery.Data d = DataStore.gson().fromJson(json, Lottery.Data.class);
		d.fixNulls();
		Lottery l = new Lottery(t.pot(), () -> d, () -> {}, t::cfg, Lottery.ledger(t.ledger()), new Random(99));
		return new TestLotto(l, d, t.cfg(), t.pot(), t.bank(), t.ledger());
	}

	private static void lottoMigration(TestContext ctx) {
		UUID a = funded(ctx, "EventsLottoA", 100_000);
		UUID b = funded(ctx, "EventsLottoB", 100_000);
		// lotto.json of an existing install (pot model)
		String old = "{\"drawId\": 44, \"tickets\": {\"" + a + "\": 3, \"" + b + "\": 2}, \"nextDrawAt\": " + (T0 + 3_600_000L)
			+ ", \"remindedDrawId\": 43, \"plannedFor\": \"20:00\", \"history\": [{\"id\": 43, \"at\": " + T0 + ", \"winner\": \"" + a
			+ "\", \"winnerName\": \"EventsLottoA\", \"pot\": 10000, \"prize\": 9000, \"fee\": 1000, \"winnerTickets\": 1, \"tickets\": 1, \"players\": 1},"
			+ " {\"id\": 42, \"at\": " + (T0 - 1) + ", \"pot\": 0, \"prize\": 0, \"fee\": 0, \"winnerTickets\": 0, \"tickets\": 0, \"players\": 0}]}";
		Lottery.Data d = DataStore.gson().fromJson(old, Lottery.Data.class);
		d.fixNulls();
		eq(0L, d.model, "legacy state: model 0");
		eq(5L, d.tickets.values().stream().mapToInt(Integer::intValue).sum(), "5 legacy tickets read");
		TestLotto t = testLotto(ctx, "EventsLotto7", 0, null, d);
		Lottery l = t.lotto();
		EconomyService.set(t.pot(), 50_000 + 12_345); // 5 tickets + remainder from an earlier rollover
		isFalse(l.isReady(), "not ready before migration");
		long total = EconomyService.balance(a) + EconomyService.balance(b) + EconomyService.balance(t.pot()) + EconomyService.balance(t.bank());

		int pa0 = PlayerDataService.getPendingMessages(a).size();
		Lottery.Migration m = l.migrate(T0);
		isTrue(m.migrated(), "migrated");
		eq(62_345L, m.potSwept(), "whole legacy pot to the treasury");
		eq(2L, m.refundedPlayers(), "2 players refunded");
		eq(50_000L, m.refunded(), "5 × $100 refunded");
		eq(2L, m.settled().size(), "refunds paid right away");
		eq(130_000L, EconomyService.balance(a), "A +$300");
		eq(120_000L, EconomyService.balance(b), "B +$200");
		eq(0L, EconomyService.balance(t.pot()), "legacy pot empty");
		eq(12_345L, EconomyService.balance(t.bank()), "rest in the treasury");
		eq(total, EconomyService.balance(a) + EconomyService.balance(b) + EconomyService.balance(t.pot()) + EconomyService.balance(t.bank()),
			"money supply preserved");
		eq(62_345L, t.flowIn(Lottery.R_OLD_POT), "treasury reason legacy pot");
		eq(50_000L, t.flowOut(Lottery.R_REFUND), "treasury reason refund");
		isTrue(l.isReady(), "ready");
		eq((long) Lottery.MODEL, d.model, "model 2");
		eq(7_200_000L, l.jackpot(), "Jackpot $72,000");
		eq(44L, l.drawId(), "draw number stays");
		eq(T0 + 3_600_000L, l.nextDrawAt(), "slot stays");
		eq(1000L, l.ticketCount(), "1000 tickets");
		eq(0L, l.soldCount(), "no new tickets");
		isTrue(d.tickets.isEmpty(), "legacy tickets gone");
		eq(2L, d.history.size(), "legacy history kept");
		eq(a, d.history.get(0).winner, "legacy history readable");
		isFalse(l.hasOwed(), "nothing owed");
		EventsRuntime.notifySettled(m.settled());
		List<String> pending = PlayerDataService.getPendingMessages(a);
		eq(pa0 + 1L, pending.size(), "A: message about the refund");
		isTrue(pending.get(pending.size() - 1).contains("new rules") && pending.get(pending.size() - 1).contains("3 old tickets"), "Text: " + pending.get(pending.size() - 1));

		// Second start: nothing left to migrate
		long seed = d.seed;
		Lottery.Migration again = l.migrate(T0 + 1);
		isFalse(again.migrated(), "second run migrates nothing");
		eq(0L, again.potSwept(), "nothing transferred");
		eq(seed, d.seed, "seed stays");
		eq(7_200_000L, l.jackpot(), "jackpot stays");
		// Later leftover on the legacy pot account goes to the treasury on next start
		EconomyService.set(t.pot(), 500);
		eq(500L, l.migrate(T0 + 2).potSwept(), "legacy pot leftover to the treasury");
		eq(0L, EconomyService.balance(t.pot()), "legacy pot empty again");

		// Newly saved file loads again (new model, old fields empty)
		String json = DataStore.gson().toJson(d);
		isTrue(json.contains("\"model\": 2") && json.contains("\"jackpot\": 7200000"), "lotto.json in the new format");
		Lottery.Data back = DataStore.gson().fromJson(json, Lottery.Data.class);
		back.fixNulls();
		eq(44L, back.drawId, "reloaded: draw");
		eq(2L, back.history.size(), "reloaded: legacy history");

		// Brand new world (no lotto.json): base jackpot straight away
		TestLotto fresh = testLotto(ctx, "EventsLotto7b", 0, null);
		Lottery.Migration mf = fresh.lotto().migrate(T0);
		isTrue(mf.migrated() && mf.refundedPlayers() == 0 && mf.potSwept() == 0, "new world: only opened");
		eq(7_200_000L, fresh.lotto().jackpot(), "new world: $72,000");
		eq(1L, fresh.lotto().drawId(), "new world: draw #1");
		ctx.detail("5 legacy tickets refunded, pot $623.45 → treasury, jackpot $72,000");
	}

	private static void lottoForced(TestContext ctx) {
		TestLotto t = openLotto(ctx, "EventsLotto8", 100_000_000, null);
		Lottery l = t.lotto();
		check(l.checkNumbers(new int[] {1, 2, 3, 4}) == null, "4 distinct numbers valid");
		check(l.checkNumbers(new int[] {1, 2, 3}) != null, "too few numbers");
		check(l.checkNumbers(new int[] {1, 2, 3, 4, 5}) != null, "too many numbers");
		check(l.checkNumbers(new int[] {1, 2, 2, 4}) != null, "duplicate number");
		check(l.checkNumbers(new int[] {0, 2, 3, 4}) != null, "0 invalid");
		check(l.checkNumbers(new int[] {1, 2, 3, 1001}) != null, "above 1000 invalid");
		long id = l.drawId();
		assertThrows(IllegalArgumentException.class, () -> l.draw(T0, new int[] {5, 5, 6, 7}), "invalid numbers → no draw");
		eq(id, l.drawId(), "draw unchanged");
		eq(0L, l.draws().size(), "nothing in history");
		TestLotto old = testLotto(ctx, "EventsLotto8b", 0, null);
		assertThrows(IllegalStateException.class, () -> old.lotto().draw(T0, null), "no draw before migration");
		Lottery.Draw d = l.draw(T0, new int[] {10, 20, 30, 40});
		eq(List.of(10, 20, 30, 40), d.numbers(), "given numbers in rank order");
		isTrue(d.forced, "marked as given");
		isTrue(d.admin, "given numbers = admin draw");
		Lottery.Draw byAdmin = l.draw(T0 + 1, null, true);
		isTrue(byAdmin.admin && !byAdmin.forced, "admin draw with numbers from the seed: admin, not given");
		Lottery.Draw scheduled = l.draw(T0 + 2, null);
		isFalse(scheduled.admin || scheduled.forced, "scheduled draw without mark");
		// The mark survives save and load (history, GUI)
		Lottery.Data back = DataStore.gson().fromJson(DataStore.gson().toJson(t.data()), Lottery.Data.class);
		back.fixNulls();
		isTrue(back.draws.get(2).forced && back.draws.get(2).admin, "saved: given");
		isTrue(back.draws.get(1).admin && !back.draws.get(1).forced, "saved: admin");
		isTrue(EventsGui.adminMark(back.draws.get(2)).contains("admin test draw"), "history marks given numbers");
		eq("", EventsGui.adminMark(back.draws.get(0)), "scheduled draw without mark in history");
	}

	private static void lottoSchedule() {
		EventsConfig c = cfg();
		Lottery.Data d = new Lottery.Data();
		Lottery l = new Lottery(Lottery.POT_ID, () -> d, () -> {}, () -> c, Lottery.TREASURY, new Random(1));
		long before = zurich(2026, 10, 10, 19, 59);
		eq(zurich(2026, 10, 10, 20, 0), l.nextDrawAfter(before), "19:59 → today 20:00");
		eq(zurich(2026, 10, 11, 20, 0), l.nextDrawAfter(zurich(2026, 10, 10, 20, 0)), "exactly 20:00 → tomorrow");
		eq(zurich(2026, 10, 11, 20, 0), l.nextDrawAfter(zurich(2026, 10, 10, 20, 1)), "20:01 → tomorrow");
		// DST change (25.10.2026, end of summer time): 25 h between two draws
		long dst = l.nextDrawAfter(zurich(2026, 10, 24, 20, 0));
		eq(zurich(2026, 10, 25, 20, 0), dst, "across the DST change");
		eq(25 * 3_600_000L, dst - zurich(2026, 10, 24, 20, 0), "25 h");

		l.ensurePlanned(before);
		eq(zurich(2026, 10, 10, 20, 0), d.nextDrawAt, "first plan");
		isFalse(l.isDue(before), "not due yet");
		isFalse(l.takeReminder(before - 30 * MIN), "too early for reminder");
		isFalse(l.inReminderWindow(d.nextDrawAt - 11 * MIN), "11 min before: no reminder window");
		isTrue(l.inReminderWindow(d.nextDrawAt - 5 * MIN), "5 min before: reminder window");
		isTrue(l.takeReminder(d.nextDrawAt - 5 * MIN), "reminder 10 min before");
		isFalse(l.takeReminder(d.nextDrawAt - 4 * MIN), "reminder only once");
		isTrue(l.isDue(d.nextDrawAt), "due at 20:00");
		// Missed draw (server was down) stays due; ensurePlanned does not move it
		l.ensurePlanned(d.nextDrawAt + 3 * 86_400_000L);
		isTrue(l.isDue(d.nextDrawAt + 3 * 86_400_000L), "missed draw stays due");
		// Cancelled draw (no tickets): the new slot gets a reminder again
		l.skip(d.nextDrawAt);
		isTrue(l.takeReminder(d.nextDrawAt - 5 * MIN), "after cancellation: new reminder");
		// Changed time in the config: replan
		c.lottoDrawTime = "18:30";
		EventsConfig.validate(c);
		long now = zurich(2026, 10, 12, 10, 0);
		d.nextDrawAt = zurich(2026, 10, 12, 20, 0);
		l.ensurePlanned(now);
		eq(zurich(2026, 10, 12, 18, 30), d.nextDrawAt, "new time taken over");
	}

	/**
	 * One draw per slot: an admin drawing before the slot uses it up (next = following slot, no second draw
	 * the same day); a catch-up or on-time draw moves to the next slot after now. Text "today/tomorrow/on
	 * ...".
	 */
	private static void lottoEarlyDraw(TestContext ctx) {
		TestLotto t = openLotto(ctx, "EventsLotto10", FUND, null);
		Lottery l = t.lotto();
		t.data().nextDrawAt = zurich(2026, 10, 12, 20, 0);
		long early = zurich(2026, 10, 12, 15, 0);
		isTrue(l.isEarly(early), "15:00 is before the slot");
		isFalse(l.isEarly(zurich(2026, 10, 12, 20, 0)), "not early at 20:00");
		Lottery.Draw d1 = l.draw(early, null, true);
		isTrue(d1.admin && !d1.forced, "admin draw marked");
		eq(zurich(2026, 10, 13, 20, 0), l.nextDrawAt(), "early: today's slot used up, next tomorrow 20:00");
		isFalse(l.isDue(zurich(2026, 10, 12, 20, 0)), "no second draw today 20:00");
		Lottery.Draw d2 = l.draw(zurich(2026, 10, 13, 20, 0), null);
		isFalse(d2.admin, "scheduled draw");
		eq(zurich(2026, 10, 14, 20, 0), l.nextDrawAt(), "on time: next tomorrow");
		// Caught up (server was down two days): next slot after now, not all missed ones
		long late = zurich(2026, 10, 16, 9, 0);
		isTrue(l.isDue(late), "missed → due");
		l.draw(late, null);
		eq(zurich(2026, 10, 16, 20, 0), l.nextDrawAt(), "caught up: today 20:00");
		eq(4L, l.drawId(), "three draws");
		long now = zurich(2026, 10, 16, 9, 0);
		eq("today at 20:00", EventsRuntime.drawText(zurich(2026, 10, 16, 20, 0), now), "today");
		eq("tomorrow at 20:00", EventsRuntime.drawText(zurich(2026, 10, 17, 20, 0), now), "tomorrow");
		eq("on Oct 19 at 20:00", EventsRuntime.drawText(zurich(2026, 10, 19, 20, 0), now), "later: date");
		ctx.detail("drawn 15:00 → next Oct 13 20:00, caught up → Oct 16 20:00");
	}

	/**
	 * {@code lottoJackpotMax}: rollover at most up to the limit; a limit lowered later does not cut a
	 * jackpot.
	 */
	private static void lottoJackpotMax(TestContext ctx) {
		long base = 7_200_000L;
		TestLotto t = openLotto(ctx, "EventsLotto11", 100_000_000, c -> c.lottoJackpotMax = 18_000_000L);
		Lottery l = t.lotto();
		eq(2 * base, l.nextJackpotIfNotWon(), "preview: $144,000");
		eq(2 * base, l.draw(T0, null).nextJackpot, "$72,000 → $144,000");
		eq(18_000_000L, l.nextJackpotIfNotWon(), "preview capped: $180,000");
		eq(18_000_000L, l.draw(T0 + 1, null).nextJackpot, "$144,000 → $180,000 (limit)");
		eq(18_000_000L, l.draw(T0 + 2, null).nextJackpot, "stays at the limit");
		t.cfg().lottoJackpotMax = 2 * base;
		EventsConfig.validate(t.cfg());
		eq(18_000_000L, l.draw(T0 + 3, null).nextJackpot, "limit lowered: jackpot stays $180,000 (no cut)");
		t.cfg().lottoJackpotMax = 0;
		EventsConfig.validate(t.cfg());
		eq(18_000_000L + base, l.draw(T0 + 4, null).nextJackpot, "without limit + $72,000 again");
		ctx.detail("limit $180,000 holds, lowered limit does not cut");
	}

	private static long zurich(int y, int mo, int day, int h, int min) {
		return LocalDateTime.of(y, mo, day, h, min).atZone(TimeUtil.zone()).toInstant().toEpochMilli();
	}

	private static void lottoPotAccount() {
		isTrue(EconomyService.isSystemAccount(Lottery.POT_ID), "legacy pot is a system account");
		eq(Lottery.POT_NAME, EconomyService.nameOrUnknown(Lottery.POT_ID), "Name");
		isFalse(EconomyService.top().stream().anyMatch(e -> e.id().equals(Lottery.POT_ID)), "not in the leaderboard");
		isFalse(EconomyService.knownNames().contains(Lottery.POT_NAME), "not in player suggestions");
		eq(EventsFeature.LOTTO.legacyPotBalance(), EconomyService.balance(Lottery.POT_ID), "leftover = balance");
		isTrue(EventsFeature.LOTTO.isReady(), "real lottery migrated at start");
		check(EventsFeature.LOTTO.jackpot() >= EventsFeature.CONFIG.get().lottoJackpotBase || EventsFeature.LOTTO.jackpot() > 0, "real jackpot set");
		isTrue(EventsFeature.LOTTO.bank() == Lottery.TREASURY, "real lottery books through the treasury");
	}

	/**
	 * Changed config (ticket count, extras): right away without sold tickets, with tickets only from the next
	 * draw.
	 */
	private static void lottoConfigRefresh(TestContext ctx) {
		TestLotto t = openLotto(ctx, "EventsLotto9", 100_000_000, null);
		Lottery l = t.lotto();
		UUID a = funded(ctx, "EventsLottoA", 10_000_000);
		t.cfg().lottoTicketsPerDraw = 500;
		t.cfg().lottoPrizes = new ArrayList<>(List.of(2_000_000L));
		EventsConfig.validate(t.cfg());
		l.ensurePlanned(T0 + 1);
		eq(500L, l.ticketCount(), "no tickets: new ticket count right away");
		eq(2L, l.winningCount(), "no tickets: new winning numbers right away");
		isTrue(l.buy(a, 3).ok(), "3 tickets");
		long jackpot = l.jackpot();
		t.cfg().lottoTicketsPerDraw = 800;
		t.cfg().lottoPrizes = new ArrayList<>(List.of(1_000_000L, 500_000L, 100_000L));
		EventsConfig.validate(t.cfg());
		l.ensurePlanned(T0 + 2);
		eq(500L, l.ticketCount(), "with tickets: draw stays at 500");
		eq(2L, l.winningCount(), "with tickets: prizes stay");
		eq(jackpot, l.jackpot(), "jackpot unchanged");
		distinctInRange(l.numbers(a), 500, "numbers in the old range");
		Lottery.Draw d = l.draw(T0 + 3, null);
		eq(2L, d.prizes.size(), "drawn with the draw's prizes");
		for (int x : d.numbers()) check(x >= 1 && x <= 500, "winning number in the draw's range");
		eq(800L, l.ticketCount(), "next draw: 800 tickets");
		eq(4L, l.winningCount(), "next draw: 4 winning numbers");
	}

	private static void commandNames(TestContext ctx) {
		CommandDispatcher<CommandSourceStack> dispatcher = ctx.server().getCommands().getDispatcher();
		CommandSourceStack console = ctx.server().createCommandSourceStack();
				for (String cmd : List.of("rank list", "event setrank Someone 0", "event start marketday random 3", "event start goldrush miner",
				"event start luckyhour 2")) {
			ParseResults<CommandSourceStack> p = dispatcher.parse(cmd, console);
			isTrue(!p.getReader().canRead() && p.getExceptions().isEmpty() && p.getContext().getCommand() != null, "/" + cmd + " parses fully");
		}
		isTrue(AdminLog.lineFor(dispatcher.parse("event setrank Someone 0", console), "event setrank Someone 0") != null, "/event setrank in the admin log");
		// Test hook with given winning numbers: admin node, in the admin log
		for (String cmd : List.of("lottery draw 1 2 3 4", "lottery draw 5,6,7,8")) {
			ParseResults<CommandSourceStack> p = dispatcher.parse(cmd, console);
			isTrue(!p.getReader().canRead() && p.getExceptions().isEmpty() && p.getContext().getCommand() != null, "/" + cmd + " parses fully");
			String line = AdminLog.lineFor(p, cmd);
			isTrue(line != null && line.contains(EventsCommands.NODE_ADMIN), "/" + cmd + " in the admin log: " + line);
		}
		for (String cmd : List.of("lottery tickets", "lottery history", "lottery buy 5")) {
			ParseResults<CommandSourceStack> p = dispatcher.parse(cmd, console);
			isTrue(!p.getReader().canRead() && p.getExceptions().isEmpty() && p.getContext().getCommand() != null, "/" + cmd + " parses fully");
			isTrue(AdminLog.lineFor(p, cmd) == null, "/" + cmd + " is no admin command");
		}
	}
}
