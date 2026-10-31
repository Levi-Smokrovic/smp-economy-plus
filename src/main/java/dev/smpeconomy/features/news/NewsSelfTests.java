package dev.smpeconomy.features.news;

import static dev.smpeconomy.core.test.Assert.eq;
import static dev.smpeconomy.core.test.Assert.fail;
import static dev.smpeconomy.core.test.Assert.isFalse;
import static dev.smpeconomy.core.test.Assert.isTrue;

import com.google.gson.Gson;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongPredicate;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.world.item.Item;
import dev.smpeconomy.core.BasePrices;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.Hooks;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.MarketGoods;
import dev.smpeconomy.core.PriceGuard;
import dev.smpeconomy.core.PriceModifiers;
import dev.smpeconomy.core.Services;
import dev.smpeconomy.core.Settings;
import dev.smpeconomy.core.TimeUtil;
import dev.smpeconomy.core.Treasury;
import dev.smpeconomy.core.test.SelfTests;
import dev.smpeconomy.core.test.TestContext;
import dev.smpeconomy.features.news.NewsData.NewsEntry;

/**
 * Selftests. All tests use their own engine instance (source "news.selftest", own state, own flash hook), so real running events
 * are never touched. Scheduling uses an injected clock.
 */
final class NewsSelfTests {
	static final String F = "news";
	static final String SRC = "news.selftest";
	static final String FLASH = "news.selftest.flashsale";
	static final ZoneId ZONE = dev.smpeconomy.core.test.SelfTests.TEST_ZONE;
	static final long MIN = 60_000L;
	static final long HOUR = 60 * MIN;
	static final long DAY = 24 * HOUR;

	private NewsSelfTests() {}

	// 26.x checked against 1.21.11 (line diff of the Snapshot.write files): differences come only from 26.x vanilla data (costs capped
	// at stack size: book and quill 1 instead of 2, name tag at the wandering trader instead of the librarian, cake 3 emeralds, new
	// items)
	static final Map<String, String> TRADES_SNAPSHOT = Map.of(
		"1.21.11", "n=82 h=6b529186",
		"26.1.2", "n=84 h=7a48071f",
		"26.2", "n=84 h=7a48071f",
		"26.3", "n=84 h=7a48071f");

	private static void tradesSnapshot(TestContext ctx) {
		List<String> lines = new ArrayList<>();
		for (NewsVillagerRoutes.Trade t : NewsVillagerRoutes.readTrades(ctx.overworld())) {
			lines.add(Items.id(t.input()) + "|" + t.count() + "|" + t.emeralds());
		}
		String actual = dev.smpeconomy.core.test.Snapshot.of(lines);
		dev.smpeconomy.core.test.Snapshot.write("news-trades", lines);
		dev.smpeconomy.core.test.Snapshot.check("news: trades", TRADES_SNAPSHOT, actual);
		ctx.detail(actual);
	}

	static void register() {
		SelfTests.register(F, "trade-snapshot", NewsSelfTests::tradesSnapshot);
		SelfTests.register(F, "pacing-10-days", NewsSelfTests::cadenceDays);
		SelfTests.register(F, "pacing-players-only", NewsSelfTests::cadencePlayers);
		SelfTests.register(F, "pacing-restart", NewsSelfTests::cadenceRestart);
		SelfTests.register(F, "pacing-max-active", NewsSelfTests::cadenceMaxActive);
		SelfTests.register(F, "pacing-quiet-hours-dst", NewsSelfTests::cadenceQuiet);
		SelfTests.register(F, "config-migration", NewsSelfTests::configMigration);
		SelfTests.register(F, "shock-set-and-clear", NewsSelfTests::shockApplyClear);
		SelfTests.register(F, "shock-replace", NewsSelfTests::shockReplace);
		SelfTests.register(F, "hype-shop-capped", NewsSelfTests::hypeShopCapped);
		SelfTests.register(F, "hype-shop-family-capped", NewsSelfTests::hypeShopFamily);
		SelfTests.register(F, "guard-during-shocks", NewsSelfTests::guardMatrix);
		SelfTests.register(F, "time-arbitrage", NewsSelfTests::timingAttack);
		SelfTests.register(F, "flash-sale", NewsSelfTests::flashSale);
		SelfTests.register(F, "flash-sale-combo", NewsSelfTests::flashCombos);
		SelfTests.register(F, "auction-fever", NewsSelfTests::fever);
		SelfTests.register(F, "bulk-order", NewsSelfTests::bulkOrder);
		SelfTests.register(F, "bulk-order-close", NewsSelfTests::bulkOrderClosed);
		SelfTests.register(F, "curated-selection", NewsSelfTests::selection);
		SelfTests.register(F, "config-families", NewsSelfTests::configFamilies);
		SelfTests.register(F, "market-goods-never-shocked", NewsSelfTests::marketSold);
		SelfTests.register(F, "market-goods-from-market", NewsSelfTests::marketGoods);
		SelfTests.register(F, "villager-paths-core", NewsSelfTests::villagerCore);
		SelfTests.register(F, "villager-emerald", NewsSelfTests::villagerRoutes);
		SelfTests.register(F, "auto-pause", NewsSelfTests::autoPause);
		SelfTests.register(F, "persistence-sync", NewsSelfTests::persistence);
		SelfTests.register(F, "end-and-history", NewsSelfTests::endAndHistory);
		SelfTests.register(F, "texts-and-parser", NewsSelfTests::texts);
		SelfTests.register(F, "announce-setting", NewsSelfTests::announceSetting);
		SelfTests.register(F, "production-hooks", NewsSelfTests::productionHooks);
	}

	static final class Rig implements NewsEngine.Announcer {
		NewsData data = new NewsData();
		final NewsConfig config = new NewsConfig();
		long offset;
		final List<List<NewsEntry>> started = new ArrayList<>();
		final List<NewsEntry> ended = new ArrayList<>();
		final List<String> bulkCalls = new ArrayList<>();
		boolean bulkResult = true;
		boolean ordersAvailable = true;
		final Set<Long> openOrders = new HashSet<>();
		long nextOrderId = 500;
		int bulkGranted;
		boolean isOpenThrows;
		boolean market = true;
		List<UUID> players = new ArrayList<>();
		final NewsEngine engine;

		Rig(TestContext ctx, long seed) {
			config.validate();
			Services.NpcOrders orders = new Services.NpcOrders() {
				@Override
				public BulkOrder startBulkOrder(Item item, int amount, long durationMs, String label) {
					bulkCalls.add(Items.id(item) + "|" + amount + "|" + durationMs + "|" + label);
					if (!bulkResult) return null;
					long id = nextOrderId++;
					openOrders.add(id);
					return new BulkOrder(id, bulkGranted > 0 ? Math.min(amount, bulkGranted) : amount);
				}

				@Override
				public boolean isOpen(long orderId) {
					if (isOpenThrows) throw new IllegalStateException("Test: isOpen throws");
					return openOrders.contains(orderId);
				}
			};
			engine = new NewsEngine(SRC, FLASH, () -> data, () -> {}, () -> config, () -> System.currentTimeMillis() + offset, new Random(seed),
				this, () -> ordersAvailable ? Optional.of(orders) : Optional.empty(), () -> market, () -> players);
			ctx.cleanup(() -> PriceModifiers.removeSource(SRC));
		}

		@Override
		public void started(List<NewsEntry> group) {
			started.add(group);
		}

		@Override
		public void ended(NewsEntry entry) {
			ended.add(entry);
		}
	}

	static Item item(String id) {
		return Items.byId(id).orElseThrow(() -> new AssertionError("Item missing: " + id));
	}

	static void close(double expected, double actual, String msg) {
		if (Math.abs(expected - actual) > 1e-6) fail(msg + " (expected " + expected + ", was " + actual + ")");
	}

	static long at(int y, int m, int d, int h, int min) {
		return ZonedDateTime.of(y, m, d, h, min, 0, 0, ZONE).toInstant().toEpochMilli();
	}

	/**
	 * Minute-by-minute simulation of the schedule. {@code online} is players online (wait as in NewsFeature); each event runs
	 * {@code eventMs} and takes one of {@code maxActive} slots. Returns start times.
	 */
	static List<Long> simulate(NewsData d, NewsPlanner.Rules r, long from, long to, LongPredicate online, Random rnd, long eventMs) {
		List<Long> fired = new ArrayList<>();
		List<Long> runningUntil = new ArrayList<>();
		long onlineSince = 0;
		for (long t = from; t < to; t += MIN) {
			if (online.test(t)) {
				if (onlineSince == 0) onlineSince = t;
			} else {
				onlineSince = 0;
			}
			final long now = t;
			runningUntil.removeIf(end -> end <= now);
			boolean ready = onlineSince > 0 && t - onlineSince >= r.graceMs();
			NewsPlanner.Result res = NewsPlanner.tick(d, t, MIN, ready, runningUntil.size(), r, rnd);
			if (res.fire()) {
				isTrue(res.slots() >= 1 && res.slots() <= r.maxActive(), "free slots " + res.slots());
				fired.add(t);
				d.lastEventAt = t;
				runningUntil.add(t + eventMs);
			}
		}
		return fired;
	}

	static void checkQuiet(List<Long> fired, NewsPlanner.Rules r) {
		for (long t : fired) {
			isFalse(TimeUtil.isHourBetween(NewsPlanner.hourOf(t, r), r.quietFrom(), r.quietTo()), "event during quiet hours: " + NewsText.when(t));
		}
	}

	private static void cadenceDays(TestContext ctx) {
		NewsPlanner.Rules r = NewsPlanner.Rules.of(new NewsConfig(), ZONE);
		long from = at(2026, 3, 25, 0, 0);
		List<Long> fired = simulate(new NewsData(), r, from, from + 10 * DAY, t -> true, new Random(42), 15 * MIN);
		checkQuiet(fired, r);
		long sum = 0;
		int gaps = 0;
		Map<LocalDate, Integer> perDay = new HashMap<>();
		for (int i = 0; i < fired.size(); i++) {
			long t = fired.get(i);
			perDay.merge(java.time.Instant.ofEpochMilli(t - 8 * HOUR).atZone(ZONE).toLocalDate(), 1, Integer::sum);
			if (i == 0) continue;
			long gap = t - fired.get(i - 1);
			boolean overNight = NewsPlanner.isQuiet(fired.get(i - 1) + gap / 2, r) || gap > 7 * HOUR;
			if (overNight) continue;
			isTrue(gap >= 15 * MIN && gap <= 25 * MIN, "gap " + gap / MIN + " min (expected 15-25): " + NewsText.when(t));
			sum += gap;
			gaps++;
		}
		double avg = (double) sum / Math.max(1, gaps) / MIN;
		isTrue(avg >= 18.5 && avg <= 21.5, "avg gap " + avg + " min (expected ≈ 20 = one Minecraft day)");
		for (Map.Entry<LocalDate, Integer> e : perDay.entrySet()) {
			if (e.getKey().isAfter(LocalDate.of(2026, 3, 25)) && e.getKey().isBefore(LocalDate.of(2026, 4, 3))) {
				isTrue(e.getValue() >= 40 && e.getValue() <= 70, "news day " + e.getKey() + ": " + e.getValue() + " events (17 h ≈ 51)");
			}
		}
		ctx.detail(fired.size() + " events in 10 days, avg gap " + String.format(Locale.ROOT, "%.1f", avg) + " min, never 01-08");
	}

	private static void cadencePlayers(TestContext ctx) {
		NewsPlanner.Rules r = NewsPlanner.Rules.of(new NewsConfig(), ZONE);
		long from = at(2026, 5, 1, 0, 0);
		LongPredicate online = t -> t >= from + 2 * DAY && NewsPlanner.hourOf(t, r) >= 19 && NewsPlanner.hourOf(t, r) < 23;
		NewsData d = new NewsData();
		List<Long> fired = simulate(d, r, from, from + 7 * DAY, online, new Random(7), 15 * MIN);
		checkQuiet(fired, r);
		Map<LocalDate, List<Long>> evenings = new HashMap<>();
		for (long t : fired) {
			isTrue(online.test(t), "event while nobody is online: " + NewsText.when(t));
			ZonedDateTime z = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(t), ZONE);
			isTrue(z.getHour() > 19 || z.getMinute() >= 3, "event before the wait after join ended: " + NewsText.when(t));
			evenings.computeIfAbsent(z.toLocalDate(), k -> new ArrayList<>()).add(t);
		}
		for (Map.Entry<LocalDate, List<Long>> e : evenings.entrySet()) {
			int n = e.getValue().size();
			isTrue(n >= 9 && n <= 16, "evening " + e.getKey() + ": " + n + " events (4 h ≈ 12)");
			long first = e.getValue().get(0);
			long eveningStart = at(e.getKey().getYear(), e.getKey().getMonthValue(), e.getKey().getDayOfMonth(), 19, 3);
			isTrue(first - eveningStart <= 25 * MIN, "first event of the evening too late: " + NewsText.when(first));
		}
		isTrue(evenings.size() >= 4, "online in the evening -> events (" + evenings.size() + " evenings)");
		isTrue(d.progressMs <= d.targetMs, "counter ≤ interval");
		eq(0, simulate(new NewsData(), r, from, from + 3 * DAY, t -> false, new Random(3), 15 * MIN).size(), "no event without players");
		ctx.detail(fired.size() + " events on " + evenings.size() + " evenings, 0 without players");
	}

	private static void cadenceRestart(TestContext ctx) {
		NewsPlanner.Rules r = NewsPlanner.Rules.of(new NewsConfig(), ZONE);
		Gson gson = DataStore.gson();
		int checked = 0;
		for (int seed = 0; seed < 30; seed++) {
			long start = at(2026, 6, 10, 9, 0);
			long cut = start + 37 * MIN + seed * 7 * MIN;
			NewsData a = new NewsData();
			List<Long> straight = simulate(a, r, start, start + 4 * HOUR, t -> true, new Random(seed), 15 * MIN);
			NewsData b = new NewsData();
			List<Long> before = simulate(b, r, start, cut, t -> true, new Random(seed), 15 * MIN);
			long progress = b.progressMs;
			long target = b.targetMs;
			b = gson.fromJson(gson.toJson(b), NewsData.class);
			b.repair();
			eq(progress, b.progressMs, "counter survives restart");
			eq(target, b.targetMs, "interval survives restart");
			List<Long> after = simulate(b, r, cut + 2 * HOUR, start + 6 * HOUR, t -> true, new Random(seed + 1000), 15 * MIN);
			long nextStraight = straight.stream().filter(t -> t >= cut).findFirst().orElse(-1L);
			if (nextStraight < 0 || after.isEmpty()) continue;
			long expected = nextStraight - cut + 2 * HOUR + r.graceMs();
			isTrue(after.get(0) - cut >= Math.min(expected, 2 * HOUR + r.graceMs()), "no extra event right after restart (seed " + seed + ")");
			isTrue(after.get(0) - cut <= expected + MIN, "next event after restart same as without (+ downtime + wait), seed " + seed);
			eq(straight.stream().filter(t -> t < cut).count(), (long) before.size(), "same history until the restart");
			checked++;
		}
		isTrue(checked >= 20, "enough restarts checked (" + checked + ")");
		ctx.detail(checked + " restarts: counter/interval from news.json, no extra event");
	}

	private static void cadenceMaxActive(TestContext ctx) {
		NewsConfig c = new NewsConfig();
		c.validate();
		NewsPlanner.Rules r = NewsPlanner.Rules.of(c, ZONE);
		eq(3, r.maxActive(), "default at most 3 at once");
		long noon = at(2026, 7, 1, 12, 0);
		NewsData d = new NewsData();
		d.targetMs = 20 * MIN;
		d.progressMs = 20 * MIN;
		Random rnd = new Random(5);
		NewsPlanner.Result full = NewsPlanner.tick(d, noon, MIN, true, 3, r, rnd);
		isFalse(full.fire(), "3 running -> wait");
		eq(20 * MIN, d.progressMs, "counter stays due");
		NewsPlanner.Result one = NewsPlanner.tick(d, noon + MIN, MIN, true, 2, r, rnd);
		isTrue(one.fire() && one.slots() == 1, "one slot free -> start with 1 slot");
		eq(0L, d.progressMs, "counter reset");
		isTrue(d.targetMs >= 15 * MIN && d.targetMs <= 25 * MIN, "new interval 15-25 min: " + d.targetMs / MIN);
		isFalse(NewsPlanner.tick(d, noon + 2 * MIN, MIN, false, 0, r, rnd).fire(), "nothing without players");
		eq(0L, d.progressMs, "nothing counts without players");
		for (long stale : new long[] {MIN, 3 * HOUR}) {
			NewsData changed = new NewsData();
			changed.targetMs = stale;
			changed.progressMs = 30_000;
			isFalse(NewsPlanner.tick(changed, noon, 1_000, true, 0, r, rnd).fire(), "no immediate start after reload");
			isTrue(changed.targetMs >= 15 * MIN && changed.targetMs <= 25 * MIN, "interval " + stale / MIN + " min redrawn: " + changed.targetMs / MIN);
			eq(31_000L, changed.progressMs, "counter stays after reload");
		}
		Rig rig = new Rig(ctx, 31);
		for (int i = 0; i < 80; i++) {
			NewsEngine.Start s = rig.engine.startRandom(false, 1);
			isTrue(s.ok(), "start with 1 slot: " + s.error());
			eq(1, s.entries().size(), "only 1 entry with 1 free slot");
			rig.engine.clearAll();
			rig.openOrders.clear();
			rig.engine.tick();
		}
		for (int i = 0; i < 40; i++) {
			NewsEngine.Start s = rig.engine.startRandomShock(false, 2);
			isTrue(s.ok() && s.entries().size() <= 2, "market shock with 2 slots: " + s.entries().size());
			rig.engine.clearAll();
		}
		rig.data = new NewsData();
		isTrue(rig.engine.triggerShock(net.minecraft.world.item.Items.COAL, 0.5, 10, true).ok(), "crash coal");
		isTrue(rig.engine.triggerShock(net.minecraft.world.item.Items.WHEAT, 0.5, 10, true).ok(), "crash wheat");
		eq(2, rig.engine.runningCount(rig.engine.now()), "2 running");
		rig.offset = 10 * MIN + 1_000;
		eq(0, rig.engine.runningCount(rig.engine.now()), "no longer counted during fade-out");
		rig.engine.clearAll();
		ctx.detail("limit 3, wait when full, market shock only with free slots");
	}

	private static void cadenceQuiet(TestContext ctx) {
		NewsPlanner.Rules r = NewsPlanner.Rules.of(new NewsConfig(), ZONE);
		for (LocalDate day : List.of(LocalDate.of(2026, 3, 29), LocalDate.of(2026, 10, 25), LocalDate.of(2026, 7, 1))) {
			int y = day.getYear();
			int m = day.getMonthValue();
			int dd = day.getDayOfMonth();
			isFalse(NewsPlanner.isQuiet(at(y, m, dd, 0, 59), r), "00:59 allowed " + day);
			isTrue(NewsPlanner.isQuiet(at(y, m, dd, 1, 0), r), "01:00 quiet " + day);
			isTrue(NewsPlanner.isQuiet(at(y, m, dd, 7, 59), r), "07:59 quiet " + day);
			isFalse(NewsPlanner.isQuiet(at(y, m, dd, 8, 0), r), "08:00 allowed " + day);
		}
		NewsData d = new NewsData();
		d.targetMs = 20 * MIN;
		d.progressMs = 19 * MIN;
		isFalse(NewsPlanner.tick(d, at(2026, 7, 1, 3, 0), MIN, true, 0, r, new Random(1)).fire(), "quiet hours: no event");
		eq(19 * MIN, d.progressMs, "quiet hours: counter paused");
		isTrue(NewsPlanner.tick(d, at(2026, 7, 1, 8, 0), MIN, true, 0, r, new Random(1)).fire(), "continues at 08:00");
		NewsConfig noQuiet = new NewsConfig();
		noQuiet.quietFromHour = 5;
		noQuiet.quietToHour = 5;
		NewsPlanner.Rules nq = NewsPlanner.Rules.of(noQuiet, ZONE);
		for (int h = 0; h < 24; h++) isFalse(NewsPlanner.isQuiet(at(2026, 7, 1, h, 0), nq), "never quiet without quiet hours (" + h + " :00)");
		NewsConfig evening = new NewsConfig();
		evening.quietFromHour = 23;
		evening.quietToHour = 7;
		NewsPlanner.Rules ev = NewsPlanner.Rules.of(evening, ZONE);
		isTrue(NewsPlanner.isQuiet(at(2026, 7, 1, 23, 30), ev), "23:30 quiet (23-7)");
		isFalse(NewsPlanner.isQuiet(at(2026, 7, 1, 7, 0), ev), "07:00 allowed (23-7)");
		ctx.detail("quiet hours 01-08 also on DST days, counter paused at night");
	}

	private static void configMigration(TestContext ctx) {
		Gson gson = DataStore.gson();
		String old = "{\"enabled\":true,\"minPerDay\":1,\"maxPerDay\":2,\"minGapHours\":3.0,\"minDurationMinutes\":20,\"maxDurationMinutes\":60,"
			+ "\"fadeOutMinutes\":5,\"hypeMinFactor\":2.0,\"hypeMaxFactor\":3.0,\"minShopHypeEffect\":0.5,\"itemCountWeights\":[50,35,15],"
			+ "\"bulkOrderMinutes\":30,\"auctionFeverMinutes\":30,\"historySize\":20,\"crashFactor\":0.5}";
		NewsConfig c = gson.fromJson(old, NewsConfig.class);
		eq(0, c.configVersion, "old file without configVersion");
		List<String> changes = c.migrate();
		eq(9, changes.size(), "migrated values: " + changes);
		c.validate();
		eq(NewsConfig.CONFIG_VERSION, c.configVersion, "version set");
		eq(10, c.minDurationMinutes, "duration min 20 -> 10");
		eq(20, c.maxDurationMinutes, "duration max 60 -> 20");
		eq(3, c.fadeOutMinutes, "fade-out 5 -> 3");
		close(1.25, c.hypeMinFactor, "hype min 2.0 -> 1.25");
		close(2.0, c.hypeMaxFactor, "hype max 3.0 -> 2.0");
		close(0.25, c.minShopHypeEffect, "shop hype effect 0.5 -> 0.25");
		eq(List.of(70, 25, 5), c.itemCountWeights, "size weights");
		eq(20, c.bulkOrderMinutes, "bulk order 30 -> 20");
		eq(15, c.auctionFeverMinutes, "fever 30 -> 15");
		eq(100, c.historySize, "history 20 -> 100");
		eq(15, c.minIntervalMinutes, "new key: interval min");
		eq(25, c.maxIntervalMinutes, "new key: interval max");
		eq(3, c.maxActive, "new key: maxActive");
		isFalse(gson.toJson(c).contains("minPerDay"), "removed keys vanish on write-back");
		NewsConfig custom = gson.fromJson("{\"maxDurationMinutes\":45,\"historySize\":15,\"hypeMinFactor\":1.5,\"hypeMaxFactor\":3.0}", NewsConfig.class);
		custom.validate();
		eq(45, custom.maxDurationMinutes, "custom duration stays");
		eq(15, custom.historySize, "custom history stays");
		close(1.5, custom.hypeMinFactor, "custom hype range stays");
		close(3.0, custom.hypeMaxFactor, "custom hype range stays (max)");
		isTrue(c.migrate().isEmpty(), "one-time");
		NewsConfig fresh = new NewsConfig();
		isTrue(fresh.migrate().isEmpty(), "new file: nothing to migrate");
		eq(NewsConfig.CONFIG_VERSION, fresh.configVersion, "new file: current version");
		ctx.detail(changes.size() + " values migrated, custom values stay, one-time");
	}

	private static void shockApplyClear(TestContext ctx) {
		Rig rig = new Rig(ctx, 1);
		FakePlayer p = ctx.player("NewsTestA");
		UUID u = p.getUUID();
		Item diamond = net.minecraft.world.item.Items.DIAMOND;
		Item block = net.minecraft.world.item.Items.DIAMOND_BLOCK;
		Item emerald = net.minecraft.world.item.Items.EMERALD;
		OptionalLong base = BasePrices.sellPrice(diamond);
		if (base.isEmpty() || BasePrices.sellPrice(emerald).isEmpty()) {
			ctx.detail("skipped (diamond/emerald without sell price)");
			return;
		}
		long pivot = PriceGuard.pivotPrice(diamond);
		double mod0 = PriceModifiers.get(diamond);
		long s0 = PriceGuard.effectiveSellPrice(u, diamond).orElse(0);
		long expectBefore = PriceGuard.computeSell(base.getAsLong(), NewsPrices.otherModifier(SRC, diamond), 1.0, pivot);
		long expectAfter = PriceGuard.computeSell(base.getAsLong(), NewsPrices.otherModifier(SRC, diamond) * 0.5, 1.0, pivot);

		NewsEngine.Start s = rig.engine.triggerShock(diamond, 0.5, 30, true);
		isTrue(s.ok(), "crash started: " + s.error());
		NewsEntry e = s.entries().get(0);
		eq("CRASH", e.type, "type");
		isTrue(e.members.contains("minecraft:diamond_block") && e.members.contains("minecraft:diamond_ore"), "family: " + e.members);
		close(0.5, PriceModifiers.get(SRC, diamond), "diamond factor");
		close(0.5, PriceModifiers.get(SRC, block), "diamond block factor");
		close(mod0 * 0.5, PriceModifiers.get(diamond), "total factor");
		long s1 = PriceGuard.effectiveSellPrice(u, diamond).orElse(0);
		eq(PriceGuard.computeSell(base.getAsLong(), PriceModifiers.get(diamond), Hooks.sellMultiplier(u, diamond), pivot), s1, "/sell price during crash");
		isTrue(s1 < s0 || s0 == 0, "crash lowers the sell price");
		eq(new NewsPrices.Change(expectBefore, expectAfter).percent(), e.shownPercent, "shown percent = effective");
		boolean fadeOk = false;
		for (PriceModifiers.Active a : PriceModifiers.active(diamond)) {
			if (a.source().equals(SRC)) fadeOk = Math.abs(a.endsAtMs() - e.endMs) < 2_000 && a.target() == 0.5;
		}
		isTrue(fadeOk, "PriceModifiers entry with end = event end (then 5 min fade-out)");
		eq(1, rig.started.size(), "announced");

		NewsEngine.Start h = rig.engine.triggerShock(emerald, 2.5, 30, true);
		isTrue(h.ok(), "hype started");
		NewsEntry he = h.entries().get(0);
		eq("HYPE", he.type, "type hype");
		NewsPrices.Change ch = NewsPrices.sell(SRC, emerald, 2.5);
		eq(ch.percent(), he.shownPercent, "hype percent effective");
		isTrue(he.shownPercent > 0 && he.shownPercent <= 150, "hype +150 % (never more)");
		eq(2, rig.data.active.size(), "2 active");

		eq(2, rig.engine.clearAll(), "clear ends both");
		close(1.0, PriceModifiers.get(SRC, diamond), "factor back");
		close(1.0, PriceModifiers.get(SRC, block), "family back");
		close(1.0, PriceModifiers.get(SRC, emerald), "hype back");
		eq(s0, PriceGuard.effectiveSellPrice(u, diamond).orElse(0), "sell price as before");
		isTrue(rig.data.active.isEmpty(), "no active events");
		isTrue(rig.data.history.stream().allMatch(x -> x.cancelled), "history: cancelled");
		isFalse(rig.engine.busy(rig.engine.now()), "no longer busy");
		ctx.detail("Crash " + e.shownPercent + "%, Hype +" + he.shownPercent + "%, clear ok");
	}

	private static void shockReplace(TestContext ctx) {
		Rig rig = new Rig(ctx, 2);
		Item diamond = net.minecraft.world.item.Items.DIAMOND;
		Item block = net.minecraft.world.item.Items.DIAMOND_BLOCK;
		if (BasePrices.sellPrice(diamond).isEmpty() || BasePrices.sellPrice(block).isEmpty()) {
			ctx.detail("skipped");
			return;
		}
		isTrue(rig.engine.triggerShock(diamond, 0.5, 30, true).ok(), "Crash");
		NewsEngine.Start s = rig.engine.triggerShock(block, 2.0, 30, true);
		isTrue(s.ok(), "hype on a family member replaces");
		eq(1, rig.data.active.size(), "only one event left");
		close(2.0, PriceModifiers.get(SRC, diamond), "family has the new factor");
		close(2.0, PriceModifiers.get(SRC, block), "block has the new factor");
		isTrue(rig.data.history.get(1).cancelled, "old event in history as cancelled");
		isFalse(rig.engine.triggerShock(diamond, 1.0, 10, true).ok(), "factor 1.0 rejected");
		isFalse(rig.engine.triggerShock(diamond, 50, 10, true).ok(), "factor 50 rejected");
		isFalse(rig.engine.triggerShock(net.minecraft.world.item.Items.BEDROCK, 0.5, 10, true).ok(), "item without price rejected");
		ctx.detail("replace + validation ok");
	}

	private static void hypeShopCapped(TestContext ctx) {
		Rig rig = new Rig(ctx, 3);
		FakePlayer p = ctx.player("NewsTestA");
		UUID u = p.getUUID();
		Item bone = net.minecraft.world.item.Items.BONE;
		OptionalLong base = BasePrices.sellPrice(bone);
		long pivot = PriceGuard.pivotPrice(bone);
		if (base.isEmpty() || pivot < 0) {
			ctx.detail("skipped (bone not in the shop)");
			return;
		}
		NewsEngine.Start s = rig.engine.triggerShock(bone, 3.0, 20, true);
		isTrue(s.ok(), "hype on shop item");
		NewsEntry e = s.entries().get(0);
		NewsPrices.Change ch = NewsPrices.sell(SRC, bone, 3.0);
		isTrue(ch.after() <= pivot, "neutral sell ≤ pivot");
		eq(ch.percent(), e.shownPercent, "display = effective value");
		long eff = PriceGuard.effectiveSellPrice(u, bone).orElse(0);
		isTrue(eff <= pivot, "sell ≤ pivot (" + eff + " ≤ " + pivot + ")");
		if (base.getAsLong() * 3 * NewsPrices.otherModifier(SRC, bone) > pivot) {
			isTrue(e.shownPercent < 200, "did not promise +200 %, but " + e.shownPercent + "%");
			isTrue(PriceGuard.breakdown(u, bone).sellCapped(), "breakdown: sell capped");
		}
		long buy = PriceGuard.effectiveShopBuyPrice(u, bone).orElse(0);
		isTrue(buy >= pivot + 1 && buy > eff, "shop ≥ pivot + 1 and > sell");
		rig.config.hypePreferNonShop = 0.0;
		rig.config.minShopHypeEffect = 0.6;
		Set<String> none = new HashSet<>();
		for (int i = 0; i < 200; i++) {
			Optional<Item> pick = rig.engine.pickHype(none, 2.0);
			if (pick.isEmpty() || BasePrices.shopBuyPrice(pick.get()).isEmpty()) continue;
			NewsPrices.Change c = NewsPrices.sell(SRC, pick.get(), 2.0);
			isTrue((double) c.after() / c.before() >= 1.6 - 1e-9, "shop hype with too little effect picked: " + Items.id(pick.get()));
		}
		ctx.detail("bone ×3 -> effective " + NewsPrices.pct(e.shownPercent) + " (pivot " + pivot + ")");
	}

	/**
	 * A hype on a shop item only sets family members with a pivot, otherwise a nether wart hype would take the nether wart block
	 * (not a shop item, from fertilized crimson fungi) uncapped to ×2-3. Crashes and non-shop hypes still hit the whole family;
	 * admin replacement and reconcile after restart leave no old factor on skipped members.
	 */
	private static void hypeShopFamily(TestContext ctx) {
		Rig rig = new Rig(ctx, 13);
		rig.config.fadeOutMinutes = 0;
		FakePlayer p = ctx.player("NewsTestA");
		UUID u = p.getUUID();
		NewsConfig c = rig.config;
		List<String> ids = new ArrayList<>(c.hypeShopItems);
		for (String extra : List.of("minecraft:nether_wart", "minecraft:redstone", "minecraft:bone", "minecraft:bamboo")) {
			if (!ids.contains(extra)) ids.add(extra);
		}
		int hypes = 0;
		int skipped = 0;
		for (String id : ids) {
			Optional<Item> it = Items.byId(id);
			if (it.isEmpty() || PriceGuard.pivotPrice(it.get()) < 0 || BasePrices.sellPrice(it.get()).isEmpty()) continue;
			Item item = it.get();
			NewsEngine.Start s = rig.engine.triggerShock(item, c.hypeMaxFactor, 20, false);
			isTrue(s.ok(), "hype on " + id + ": " + s.error());
			NewsEntry e = s.entries().get(0);
			eq("HYPE", e.type, "type " + id);
			isTrue(e.members.contains(id), "hype hits the item itself: " + id);
			for (Item m : rig.engine.family(item)) {
				String mid = Items.id(m);
				long pivot = PriceGuard.pivotPrice(m);
				if (e.members.contains(mid)) {
					isTrue(pivot >= 0, "shop hype on " + id + " sets member without pivot: " + mid);
					long sell = PriceGuard.effectiveSellPrice(u, m).orElse(0);
					isTrue(sell <= pivot, mid + ": sell " + sell + " > pivot " + pivot + " during hype on " + id);
				} else {
					isTrue(pivot < 0, "member with pivot skipped: " + mid + " (" + id + ")");
					close(1.0, PriceModifiers.get(SRC, m), "no hype factor on " + mid + " (" + id + ")");
					skipped++;
				}
			}
			rig.engine.clearAll();
			hypes++;
		}
		isTrue(hypes > 0, "shop hype items checked");

		Item wart = net.minecraft.world.item.Items.NETHER_WART;
		Item wartBlock = net.minecraft.world.item.Items.NETHER_WART_BLOCK;
		String wartBlockId = Items.id(wartBlock);
		boolean wartCase = PriceGuard.pivotPrice(wart) >= 0 && PriceGuard.pivotPrice(wartBlock) < 0
			&& BasePrices.sellPrice(wart).isPresent() && rig.engine.family(wart).contains(wartBlock);
		if (wartCase) {
			NewsEngine.Start crash = rig.engine.triggerShock(wart, 0.5, 20, false);
			isTrue(crash.ok() && crash.entries().get(0).members.contains(wartBlockId), "crash still hits the whole family");
			close(0.5, PriceModifiers.get(SRC, wartBlock), "crash factor on the block");
			NewsEngine.Start hype = rig.engine.triggerShock(wart, 3.0, 20, false);
			isTrue(hype.ok(), "hype replaces crash");
			isFalse(hype.entries().get(0).members.contains(wartBlockId), "nether wart hype skips the block");
			if (NewsFeature.config().familyIds(Items.id(wart)).contains(wartBlockId)) {
				isTrue(NewsCommands.warnings(hype.entries().get(0)).stream().anyMatch(w -> w.contains("Not in the hype") && w.contains(wartBlockId)),
					"admin note: block not in the hype");
			}
			close(3.0, PriceModifiers.get(SRC, wart), "nether wart ×3");
			close(1.0, PriceModifiers.get(SRC, wartBlock), "block: old crash factor faded, no hype");
			eq(1, rig.data.active.size(), "one event");
			rig.engine.clearAll();

			long now = rig.engine.now();
			NewsEntry legacy = entry(rig.data.nextId++, "HYPE", Items.id(wart), List.of(Items.id(wart), wartBlockId), 3.0, now - MIN,
				now + 20 * MIN, 5 * MIN);
			rig.data.active.add(legacy);
			rig.data.history.add(0, legacy.copy());
			PriceModifiers.set(SRC, wart, 3.0, 0, 20 * MIN, 5 * MIN, NewsEngine.LABEL);
			PriceModifiers.set(SRC, wartBlock, 3.0, 0, 20 * MIN, 5 * MIN, NewsEngine.LABEL);
			rig.engine.reconcile();
			isFalse(legacy.members.contains(wartBlockId), "reconcile removes the block from the hype");
			isFalse(rig.data.history.get(0).members.contains(wartBlockId), "history without block");
			close(1.0, PriceModifiers.get(SRC, wartBlock), "block factor immediately 1.0");
			close(3.0, PriceModifiers.get(SRC, wart), "nether wart stays in the hype");
			rig.engine.clearAll();
		}

		boolean nonShopChecked = false;
		for (String id : c.hypeItems) {
			Optional<Item> it = Items.byId(id);
			if (it.isEmpty() || BasePrices.sellPrice(it.get()).isEmpty() || PriceGuard.pivotPrice(it.get()) >= 0) continue;
			List<Item> fam = rig.engine.family(it.get());
			if (fam.size() < 2) continue;
			NewsEngine.Start s = rig.engine.triggerShock(it.get(), 2.0, 20, false);
			isTrue(s.ok(), "non-shop hype on " + id);
			for (Item m : fam) isTrue(s.entries().get(0).members.contains(Items.id(m)), "non-shop hype " + id + " hits " + Items.id(m));
			rig.engine.clearAll();
			nonShopChecked = true;
			break;
		}
		isTrue(nonShopChecked, "non-shop hype with family checked");

		rig.config.hypePreferNonShop = 0.0;
		rig.config.minShopHypeEffect = 0.0;
		int auto = 0;
		for (int i = 0; i < 60; i++) {
			NewsEngine.Start s = rig.engine.startRandomShock(false);
			for (NewsEntry e : s.entries()) {
				Optional<Item> main = Items.byId(e.item);
				if (e.kind() != NewsEntry.Type.HYPE || main.isEmpty() || PriceGuard.pivotPrice(main.get()) < 0) continue;
				auto++;
				for (String m : e.members) isTrue(PriceGuard.pivotPrice(item(m)) >= 0, "automatic shop hype " + e.item + " sets " + m);
			}
			rig.engine.clearAll();
		}
		ctx.detail(hypes + " shop hypes, " + skipped + " members without pivot skipped" + (wartCase ? ", nether wart block ok" : "")
			+ ", " + auto + " automatic shop hypes");
	}

	private static void guardMatrix(TestContext ctx) {
		Rig rig = new Rig(ctx, 4);
		FakePlayer p = ctx.player("NewsTestA");
		UUID u = p.getUUID();
		Set<Item> items = new LinkedHashSet<>();
		NewsConfig c = rig.config;
		for (List<String> list : List.of(c.crashItems, c.hypeItems, c.hypeShopItems, c.bulkOrderItems)) {
			for (String id : list) Items.byId(id).ifPresent(items::add);
		}
		c.families.forEach((k, v) -> {
			Items.byId(k).ifPresent(items::add);
			v.forEach(id -> Items.byId(id).ifPresent(items::add));
		});
		for (BasePrices.ShopCategory cat : BasePrices.shopCategories()) cat.items().forEach(si -> items.add(si.item()));
		double[] discount = {1.0};
		Hooks.Registration reg = Hooks.addShopDiscount(FLASH, "Flash Sale (Test)", item -> discount[0]);
		ctx.cleanup(reg::remove);
		double[][] phases = {{0.5, 1.0}, {c.hypeMaxFactor, 1.0}, {0.5, 0.6}, {c.hypeMaxFactor, 0.6}, {c.hypeMinFactor, 0.6}, {1.0, 0.6}, {1.0, 1.0}};
		Map<Item, Long> minBuy = new HashMap<>();
		Map<Item, Long> maxSell = new HashMap<>();
		Map<Item, Long> minSale = new HashMap<>();
		Map<Item, Long> maxPayout = new HashMap<>();
		int checks = 0;
		for (double[] phase : phases) {
			PriceModifiers.removeSource(SRC);
			if (phase[0] != 1.0) {
				for (Item item : items) PriceModifiers.set(SRC, item, phase[0], 0, 10 * MIN, 0, "Test");
			}
			discount[0] = phase[1];
			for (Item item : items) {
				checks += checkGuard(u, item, minBuy, maxSell, minSale, maxPayout);
			}
		}
		int overTime = 0;
		for (Map.Entry<Item, Long> e : minBuy.entrySet()) {
			Long sell = maxSell.get(e.getKey());
			if (sell != null) isTrue(e.getValue() > sell, Items.id(e.getKey()) + ": crash purchase " + e.getValue() + " ≤ hype sale " + sell);
			Long sale = minSale.get(e.getKey());
			Long pay = maxPayout.get(e.getKey());
			if (pay != null) {
				long cheapest = sale == null ? e.getValue() : Math.min(sale, e.getValue());
				isTrue(cheapest > pay, Items.id(e.getKey()) + ": cheapest system sale " + cheapest + " ≤ highest system payment " + pay
					+ " (over time)");
				overTime++;
			}
		}
		ctx.detail(items.size() + " items × " + phases.length + " phases, " + checks + " checks, " + overTime + " shop items over time");
	}

	private static int checkGuard(UUID u, Item item, Map<Item, Long> minBuy, Map<Item, Long> maxSell, Map<Item, Long> minSale,
			Map<Item, Long> maxPayout) {
		String id = Items.id(item);
		long sell = PriceGuard.effectiveSellPrice(u, item).orElse(0);
		long pivot = PriceGuard.pivotPrice(item);
		OptionalLong buy = PriceGuard.effectiveShopBuyPrice(u, item);
		if (pivot >= 0) {
			isTrue(sell <= pivot, id + ": sell " + sell + " > pivot " + pivot);
			isTrue(buy.isPresent() && buy.getAsLong() >= pivot + 1, id + ": shop " + buy + " < pivot + 1");
		}
		if (buy.isPresent()) {
			isTrue(buy.getAsLong() > sell, id + ": shop " + buy.getAsLong() + " ≤ sell " + sell);
			isTrue(buy.getAsLong() * 4 >= sell * 5, id + ": shop < 1.25 × sell");
			minBuy.merge(item, buy.getAsLong(), Math::min);
		}
		if (sell > 0) maxSell.merge(item, sell, Math::max);
		OptionalLong mv = Services.marketValue(item);
		if (mv.isPresent()) {
			long npcBuy = PriceGuard.maxNpcBuyPrice(u, item, mv.getAsLong(), PriceGuard.NPC_BUY_MARKET_FACTOR_MAX);
			long npcOrder = PriceGuard.maxNpcOrderPrice(u, item, mv.getAsLong());
			long minSaleNow = PriceGuard.minSystemSalePrice(u, item, mv.getAsLong());
			isTrue(minSaleNow > npcBuy && minSaleNow > npcOrder && minSaleNow > sell, id + ": NPC offer " + minSaleNow + " not above payments ("
				+ npcBuy + "/" + npcOrder + "/" + sell + ")");
			if (buy.isPresent()) {
				isTrue(npcBuy * 10 <= buy.getAsLong() * 9, id + ": NPC buyer > 0.9 × shop");
				isTrue(npcOrder * 5 <= buy.getAsLong() * 4, id + ": NPC order > 0.8 × shop");
			}
			if (pivot >= 0) {
				isTrue(npcBuy <= pivot && npcOrder <= pivot, id + ": NPC payment > pivot");
				minSale.merge(item, minSaleNow, Math::min);
				maxPayout.merge(item, Math.max(sell, Math.max(npcBuy, npcOrder)), Math::max);
			}
		} else if (pivot >= 0) {
			maxPayout.merge(item, sell, Math::max);
		}
		return 1;
	}

	static final double MAX_SELL_BOOST = 1.5 * 1.12;

	/**
	 * Time arbitrage under the frequent schedule: buying from the system at time A during a crash (all strengths, whole family)
	 * and/or flash sale (strongest config discount × daily deal -25 %, admin down to -90 %), with or without a sell boost, and
	 * selling at time B with Tycoon × market day (×1.68), auction fever and the strongest possible hype (/sell, NPC buyer, NPC
	 * order) never profits per unit. Checked for every shop item (buying in the shop and as an NPC offer; hype up to ×20, capped
	 * by the pivot) and every market NPC good (NPC offer at the market value without news, as market passes in HIGH mode; never
	 * hyped there, also checked). Also: a crash lowers no system sale price.
	 */
	private static void timingAttack(TestContext ctx) {
		Rig rig = new Rig(ctx, 30);
		NewsConfig c = rig.config;
		UUID buyer = ctx.player("NewsTestA").getUUID();
		UUID seller = ctx.player("NewsTestB").getUUID();
		double[] sellerBoost = {1.0};
		double[] buyerBoost = {1.0};
		double[] disc = {1.0};
		List<Hooks.Registration> regs = new ArrayList<>();
		ctx.cleanup(() -> regs.forEach(Hooks.Registration::remove));
		regs.add(Hooks.addSellMultiplier(SRC + ".boost", "Test: Tycoon × market day",
			(pl, it) -> pl.equals(seller) ? sellerBoost[0] : pl.equals(buyer) ? buyerBoost[0] : 1.0));
		regs.add(Hooks.addShopDiscount(SRC + ".deal", "Test: flash sale × daily deal", it -> disc[0]));
		Set<Item> hypeReach = new HashSet<>();
		for (List<String> list : List.of(c.hypeItems, c.hypeShopItems)) {
			for (String id : list) {
				Optional<Item> it = Items.byId(id);
				if (it.isPresent() && rig.engine.hypeAllowed(it.get())) hypeReach.addAll(rig.engine.shockMembers(it.get(), c.hypeMaxFactor));
			}
		}
		// "Buy ingredients in a flash sale, wait for the hype, craft and sell": no hype target without a pivot may be craftable from shop
		// items, also through intermediate steps, furnaces or stonecutters
		Set<Item> craftable = new HashSet<>(BasePrices.shopBuyPrices().keySet());
		List<NewsVillagerRoutes.Recipe> recipes = NewsVillagerRoutes.recipes();
		isTrue(recipes.size() >= 500, "recipes read: " + recipes.size());
		for (boolean grew = true; grew; ) {
			grew = false;
			for (NewsVillagerRoutes.Recipe rc : recipes) {
				if (craftable.contains(rc.out())) continue;
				if (rc.ingredients().stream().allMatch(slot -> slot.stream().anyMatch(craftable::contains))) {
					craftable.add(rc.out());
					grew = true;
				}
			}
		}
		for (Item h : hypeReach) {
			if (PriceGuard.pivotPrice(h) < 0) isFalse(craftable.contains(h), Items.id(h) + ": hype target without pivot is craftable from shop items");
		}
		Set<Item> items = new LinkedHashSet<>(BasePrices.shopBuyPrices().keySet());
		for (String id : c.marketSoldIds()) Items.byId(id).ifPresent(items::add);
		double[] crashes = {c.crashFactor, c.crashMaxFactor, PriceModifiers.MIN_FACTOR};
		double[] discounts = {1.0, (1 - c.flashSaleMaxPercent / 100.0) * 0.75, 0.1};
		int shopItems = 0;
		int marketItems = 0;
		long tightest = Long.MAX_VALUE;
		String tightestItem = "-";
		for (Item item : items) {
			String id = Items.id(item);
			long pivot = PriceGuard.pivotPrice(item);
			List<Item> fam = rig.engine.family(item);
			if (!fam.contains(item)) fam = new ArrayList<>(List.of(item));
			// Reference without news: the market value market passes at least in HIGH mode (crash and downward drift count as 1)
			PriceModifiers.removeSource(SRC);
			disc[0] = 1.0;
			buyerBoost[0] = 1.0;
			OptionalLong shopNormal = PriceGuard.effectiveShopBuyPrice(buyer, item);
			long mvRef = Math.max(Services.marketValue(item).orElse(0), Services.defaultMarketValue(item).orElse(0));
			long saleNormal = mvRef > 0 ? PriceGuard.minSystemSalePrice(buyer, item, mvRef) : -1;
			if (shopNormal.isEmpty() && saleNormal < 0) continue;
			if (pivot < 0) isFalse(hypeReach.contains(item), id + ": market NPC goods without pivot reachable by a hype");
			long minBuy = Long.MAX_VALUE;
			for (double crash : crashes) {
				for (Item m : fam) PriceModifiers.set(SRC, m, crash, 0, 10 * MIN, 0, "Test-Crash");
				for (double d : discounts) {
					disc[0] = d;
					for (double bb : new double[] {1.0, MAX_SELL_BOOST}) {
						buyerBoost[0] = bb;
						OptionalLong shop = PriceGuard.effectiveShopBuyPrice(buyer, item);
						if (shop.isPresent()) {
							minBuy = Math.min(minBuy, shop.getAsLong());
							if (d == 1.0 && bb == 1.0) eq(shopNormal.getAsLong(), shop.getAsLong(), id + ": crash ×" + crash + " must not lower the shop price");
							if (pivot >= 0) isTrue(shop.getAsLong() >= pivot + 1, id + ": shop ≥ pivot + 1");
						}
						if (mvRef > 0) {
							long sale = PriceGuard.minSystemSalePrice(buyer, item, mvRef);
							if (d == 1.0 && bb == 1.0) isTrue(sale >= saleNormal, id + ": crash ×" + crash + " lowers the NPC offer floor (" + saleNormal + " → " + sale + ")");
							minBuy = Math.min(minBuy, sale);
						}
					}
				}
			}
			buyerBoost[0] = 1.0;
			disc[0] = 1.0;
			PriceModifiers.removeSource(SRC);
			sellerBoost[0] = MAX_SELL_BOOST;
			long maxPayout = 0;
			double[] hypes = pivot >= 0 ? new double[] {1.0, c.hypeMaxFactor, PriceModifiers.MAX_FACTOR}
				: hypeReach.contains(item) ? new double[] {1.0, c.hypeMaxFactor} : new double[] {1.0};
			for (double hype : hypes) {
				if (hype != 1.0) {
					for (Item m : fam) PriceModifiers.set(SRC, m, hype, 0, 10 * MIN, 0, "Test-Hype");
				}
				long mv = Math.max(Services.marketValue(item).orElse(0), (long) Math.floor(mvRef * hype));
				long payout = Math.max(PriceGuard.effectiveSellPrice(seller, item).orElse(0),
					Math.max(PriceGuard.maxNpcBuyPrice(seller, item, mv, PriceGuard.NPC_BUY_MARKET_FACTOR_MAX), PriceGuard.maxNpcOrderPrice(seller, item, mv)));
				maxPayout = Math.max(maxPayout, payout);
				PriceModifiers.removeSource(SRC);
			}
			sellerBoost[0] = 1.0;
			isTrue(minBuy > maxPayout, id + ": buying during crash/flash sale (" + minBuy + ") and selling afterwards (" + maxPayout + ") is profit");
			if (minBuy - maxPayout < tightest) {
				tightest = minBuy - maxPayout;
				tightestItem = id;
			}
			if (pivot >= 0) shopItems++;
			else marketItems++;
		}
		isTrue(shopItems >= 50, "enough shop items checked (" + shopItems + ")");
		isTrue(marketItems >= 3, "market NPC goods checked (" + marketItems + ")");
		ctx.detail(shopItems + " shop items + " + marketItems + " market items × " + crashes.length + " crashes × " + discounts.length
			+ " discounts × bonus/no bonus -> never profit (closest gap " + tightest + " ¢: " + tightestItem + ")");
	}

	/**
	 * Flash sales cannot be combined into profit: two sales on one item do not stack, a crash does not make a sale item cheaper,
	 * a daily deal stays above pivot + 1, and neither a bulk or NPC order at the same moment nor selling later (Tycoon × market
	 * day, hype, fever) brings more than the buy price.
	 */
	private static void flashCombos(TestContext ctx) {
		Rig rig = new Rig(ctx, 32);
		UUID buyer = ctx.player("NewsTestA").getUUID();
		UUID seller = ctx.player("NewsTestB").getUUID();
		double[] boost = {1.0};
		double[] deal = {1.0};
		List<Hooks.Registration> regs = new ArrayList<>();
		ctx.cleanup(() -> regs.forEach(Hooks.Registration::remove));
		regs.add(Hooks.addShopDiscount(FLASH, "Flash Sale (Test)", rig.engine::flashDiscount));
		regs.add(Hooks.addShopDiscount(SRC + ".deal", "Test: daily deal", it -> deal[0]));
		regs.add(Hooks.addSellMultiplier(SRC + ".boost", "Test: Tycoon × market day", (pl, it) -> pl.equals(seller) ? boost[0] : 1.0));
		BasePrices.ShopCategory cat = null;
		for (BasePrices.ShopCategory x : BasePrices.shopCategories()) {
			long discounted = x.items().stream().filter(si -> !rig.engine.flashExcluded(si.item())).count();
			if (discounted >= 3) {
				cat = x;
				break;
			}
		}
		if (cat == null) {
			ctx.detail("skipped (no shop category with discountable items)");
			return;
		}
		NewsEngine.Start s = rig.engine.startFlashSale(cat.id(), 40, 15, true);
		isTrue(s.ok(), "Flash Sale: " + s.error());
		NewsEntry second = s.entries().get(0).copy();
		second.id = rig.data.nextId++;
		second.factor = 0.5;
		rig.data.active.add(second);
		int checked = 0;
		for (BasePrices.ShopItem si : cat.items()) {
			Item item = si.item();
			if (rig.engine.flashExcluded(item)) continue;
			String id = Items.id(item);
			close(0.5, rig.engine.flashDiscount(item), id + ": strongest sale applies (0.5), not 0.6 × 0.5");
			long pivot = PriceGuard.pivotPrice(item);
			deal[0] = 0.75;
			long flashDeal = PriceGuard.effectiveShopBuyPrice(buyer, item).orElseThrow();
			isTrue(flashDeal >= pivot + 1, id + ": flash sale + daily deal ≥ pivot + 1");
			for (Item m : rig.engine.family(item)) PriceModifiers.set(SRC, m, 0.5, 0, 10 * MIN, 0, "Test-Crash");
			eq(flashDeal, PriceGuard.effectiveShopBuyPrice(buyer, item).orElseThrow(), id + ": crash does not make the flash sale cheaper");
			long mv = Services.marketValue(item).orElse(0);
			isTrue(PriceGuard.maxNpcOrderPrice(seller, item, mv) < flashDeal, id + ": NPC order during sale < buy price");
			isTrue(PriceGuard.maxNpcBuyPrice(seller, item, mv, PriceGuard.NPC_BUY_MARKET_FACTOR_MAX) < flashDeal, id + ": NPC buyer during sale < buy price");
			PriceModifiers.removeSource(SRC);
			boost[0] = MAX_SELL_BOOST;
			for (Item m : rig.engine.family(item)) PriceModifiers.set(SRC, m, PriceModifiers.MAX_FACTOR, 0, 10 * MIN, 0, "Test-Hype");
			long mvH = Services.marketValue(item).orElse(0);
			long payout = Math.max(PriceGuard.effectiveSellPrice(seller, item).orElse(0),
				Math.max(PriceGuard.maxNpcBuyPrice(seller, item, mvH, PriceGuard.NPC_BUY_MARKET_FACTOR_MAX), PriceGuard.maxNpcOrderPrice(seller, item, mvH)));
			isTrue(payout <= pivot && payout < flashDeal, id + ": selling after the sale (" + payout + ") < sale price (" + flashDeal + ")");
			PriceModifiers.removeSource(SRC);
			boost[0] = 1.0;
			deal[0] = 1.0;
			checked++;
		}
		rig.data.active.remove(second);
		rig.engine.clearAll();
		isTrue(checked >= 3, "items checked: " + checked);
		ctx.detail(cat.id() + ": " + checked + " items: no stacking, crash + sale = sale, daily deal ≥ pivot + 1, later sell < buy price");
	}

	private static void flashSale(TestContext ctx) {
		Rig rig = new Rig(ctx, 5);
		FakePlayer p = ctx.player("NewsTestA");
		UUID u = p.getUUID();
		Hooks.Registration reg = Hooks.addShopDiscount(FLASH, "Flash Sale (Test)", rig.engine::flashDiscount);
		ctx.cleanup(reg::remove);
		BasePrices.ShopCategory cat = BasePrices.shopCategories().stream().filter(x -> x.items().size() >= 3).findFirst().orElse(null);
		if (cat == null) {
			ctx.detail("skipped (no shop category)");
			return;
		}
		Map<Item, Long> before = new HashMap<>();
		for (BasePrices.ShopItem si : cat.items()) before.put(si.item(), PriceGuard.effectiveShopBuyPrice(u, si.item()).orElse(0));
		NewsEngine.Start s = rig.engine.startFlashSale(cat.id(), 40, 15, true);
		isTrue(s.ok(), "flash sale started: " + s.error());
		NewsEntry e = s.entries().get(0);
		isTrue(e.shownPercent < 0 && e.shownPercent >= -40, "shown discount " + e.shownPercent + "% (never more than −40 %)");
		int cheaper = 0;
		int maxEffect = 0;
		for (BasePrices.ShopItem si : cat.items()) {
			Item item = si.item();
			close(rig.engine.flashExcluded(item) ? 1.0 : 0.6, rig.engine.flashDiscount(item), "discount factor " + Items.id(item));
			long after = PriceGuard.effectiveShopBuyPrice(u, item).orElse(0);
			long pivot = PriceGuard.pivotPrice(item);
			isTrue(after <= before.get(item), Items.id(item) + ": flash sale does not raise the price");
			isTrue(after >= pivot + 1, Items.id(item) + ": never below pivot + 1");
			if (after < before.get(item)) cheaper++;
			maxEffect = Math.min(maxEffect, new NewsPrices.Change(before.get(item), after).percent());
		}
		isTrue(cheaper > 0, "at least one item cheaper");
		int neutral = 0;
		for (BasePrices.ShopItem si : cat.items()) {
			if (!rig.engine.flashExcluded(si.item())) neutral = Math.min(neutral, NewsPrices.flashPercent(FLASH, si.item(), 0.6, List.of()));
		}
		eq(neutral, e.shownPercent, "display = largest effective discount");
		int personal = 0;
		for (BasePrices.ShopItem si : cat.items()) {
			if (!rig.engine.flashExcluded(si.item())) personal = Math.min(personal, NewsPrices.flashPercent(FLASH, si.item(), 0.6, List.of(u)));
		}
		isTrue(personal >= maxEffect, "with player: display " + personal + "% ≤ real discount " + maxEffect + "%");
		Item outside = BasePrices.shopCategories().stream().filter(x -> !x.id().equals(cat.id())).flatMap(x -> x.items().stream())
			.map(BasePrices.ShopItem::item).filter(i -> !rig.engine.itemsOf(cat.id()).contains(i)).findFirst().orElse(null);
		if (outside != null) close(1.0, rig.engine.flashDiscount(outside), "other category untouched");
		isFalse(rig.engine.startFlashSale(cat.id(), 30, 15, true).ok(), "same category not twice");
		rig.offset = 15 * MIN + 1_000;
		close(1.0, rig.engine.flashDiscount(cat.items().get(0).item()), "over after 15 min");
		rig.engine.tick();
		eq(1, rig.ended.size(), "end announced");
		isTrue(rig.data.active.isEmpty(), "flash sale removed (no fade-out)");
		String excluded = flashExclusions(ctx, u, FLASH);
		ctx.detail(cat.id() + ": " + cheaper + "/" + cat.items().size() + " cheaper, display " + e.shownPercent + "%; " + excluded);
	}

	/**
	 * Bone meal, bones and saplings are never discounted, otherwise "fertilize cheaply, sell logs or crops" pays, even if their
	 * category is on sale. The rest of the category is.
	 */
	private static String flashExclusions(TestContext ctx, UUID u, String hookId) {
		Rig rig = new Rig(ctx, 15);
		Hooks.Registration reg = Hooks.addShopDiscount(hookId, "Flash Sale (Test)", rig.engine::flashDiscount);
		ctx.cleanup(reg::remove);
		for (String id : NewsConfig.FLASH_ALWAYS_EXCLUDED) isTrue(rig.config.flashSaleExcludedItems.contains(id), "excluded: " + id);
		int checkedCats = 0;
		int kept = 0;
		for (BasePrices.ShopCategory cat : BasePrices.shopCategories()) {
			List<Item> excluded = rig.engine.flashExcludedIn(cat.id());
			if (excluded.isEmpty()) continue;
			Map<Item, Long> before = new HashMap<>();
			for (BasePrices.ShopItem si : cat.items()) before.put(si.item(), PriceGuard.effectiveShopBuyPrice(u, si.item()).orElse(0));
			NewsEngine.Start s = rig.engine.startFlashSale(cat.id(), 40, 15, true);
			if (!s.ok()) {
				isTrue(s.error().contains("no effect"), "Flash Sale " + cat.id() + ": " + s.error());
				continue;
			}
			checkedCats++;
			for (Item item : excluded) {
				close(1.0, rig.engine.flashDiscount(item), "no flash sale factor on " + Items.id(item));
				eq((long) before.get(item), PriceGuard.effectiveShopBuyPrice(u, item).orElse(0), "shop price unchanged: " + Items.id(item));
				isFalse(Hooks.shopParts(item).stream().anyMatch(pt -> pt.id().equals(hookId)), "no discount line: " + Items.id(item));
				kept++;
			}
			for (BasePrices.ShopItem si : cat.items()) {
				if (!excluded.contains(si.item())) close(0.6, rig.engine.flashDiscount(si.item()), "rest of the category on sale: " + Items.id(si.item()));
			}
			isTrue(NewsText.details(rig.engine, rig.engine.active().get(0)).getString().contains("No discount"), "\"No discount\" note in the details");
			rig.engine.clearAll();
		}
		Item boneMeal = net.minecraft.world.item.Items.BONE_MEAL;
		if (BasePrices.shopBuyPrice(boneMeal).isPresent()) isTrue(checkedCats > 0 && kept > 0, "category with bone meal checked");
		return checkedCats + " categories with " + kept + " excluded items";
	}

	private static void fever(TestContext ctx) {
		Rig rig = new Rig(ctx, 6);
		isFalse(rig.engine.feverActive(), "off initially");
		isTrue(rig.engine.startAuctionFever(30, true).ok(), "fever started");
		isTrue(rig.engine.feverActive(), "fever on");
		isFalse(rig.engine.startAuctionFever(30, true).ok(), "not twice");
		rig.offset = 30 * MIN + 1_000;
		isFalse(rig.engine.feverActive(), "off after 30 min");
		Rig r2 = new Rig(ctx, 7);
		r2.market = false;
		r2.ordersAvailable = false;
		for (int i = 0; i < 20; i++) {
			NewsEngine.Start s = r2.engine.startRandomAction(true);
			if (s.ok()) eq("FLASH_SALE", s.entries().get(0).type, "without market/orders only flash sale");
			r2.engine.clearAll();
		}
		Rig r3 = new Rig(ctx, 8);
		r3.ordersAvailable = false;
		r3.config.flashSaleWeight = 0;
		NewsEngine.Start f = r3.engine.startRandomAction(true);
		isTrue(f.ok() && f.entries().get(0).type.equals("AUCTION_FEVER"), "with market: fever selectable");
		ctx.detail("fever 30 min, selection by availability");
	}

	private static void bulkOrder(TestContext ctx) {
		Rig rig = new Rig(ctx, 9);
		Item wheat = net.minecraft.world.item.Items.WHEAT;
		OptionalLong mv = Services.marketValue(wheat);
		int amount = rig.engine.bulkAmount(wheat);
		if (mv.isEmpty() || amount == 0) {
			ctx.detail("skipped (no market value or treasury budget too small: " + Treasury.maxSpendPerHour() + ")");
			return;
		}
		long perItem = (long) Math.ceil(mv.getAsLong() * PriceGuard.NPC_ORDER_MARKET_FACTOR);
		isTrue(amount >= rig.config.bulkOrderMinAmount && amount <= rig.config.bulkOrderMaxAmount, "amount in range: " + amount);
		isTrue((long) amount * perItem <= Treasury.maxSpendPerHour() * rig.config.bulkOrderBudgetShare + 1e-6, "amount fits the treasury budget");
		NewsEngine.Start s = rig.engine.startBulkOrder(wheat, 0, true);
		isTrue(s.ok(), "bulk order started: " + s.error());
		eq(1, rig.bulkCalls.size(), "orders called once");
		eq("minecraft:wheat|" + amount + "|" + rig.config.bulkOrderMinutes * MIN + "|" + NewsEngine.BULK_LABEL, rig.bulkCalls.get(0), "parameters to orders");
		eq(amount, s.entries().get(0).amount, "amount in the event");
		NewsEngine.Start twice = rig.engine.startBulkOrder(wheat, 0, true);
		isTrue(!twice.ok() && twice.error().contains("already running"), "no second bulk order: " + twice.error());
		eq(1, rig.bulkCalls.size(), "orders not called again");
		isTrue(rig.engine.triggerShock(net.minecraft.world.item.Items.DIAMOND, 0.5, 30, true).ok(), "crash alongside");
		eq(1, rig.engine.clearAll(), "clear ends only the crash");
		eq(1, rig.engine.runningBulkOrders().size(), "bulk order keeps running");
		NewsEntry kept = rig.data.active.get(0);
		eq("BULK_ORDER", kept.type, "only the bulk order is still active");
		isFalse(kept.cancelled, "bulk order not marked cancelled");
		isTrue(rig.engine.busy(rig.engine.now()) && rig.engine.runningCount(rig.engine.now()) == 1, "still takes a slot (maxActive)");
		isFalse(rig.engine.startBulkOrder(null, 0, true).ok(), "no second bulk order after clear either");
		isFalse(rig.engine.startRandomAction(true).entries().stream().anyMatch(x -> x.kind() == NewsEntry.Type.BULK_ORDER),
			"random action does not pick a second bulk order");
		eq(1, rig.bulkCalls.size(), "orders still called only once");
		rig.engine.clearAll();
		rig.offset = rig.config.bulkOrderMinutes * MIN + 1_000;
		rig.engine.tick();
		isTrue(rig.engine.runningBulkOrders().isEmpty() && rig.data.active.isEmpty(), "bulk order after " + rig.config.bulkOrderMinutes + " min ended normally");
		rig.bulkResult = false;
		int hist = rig.data.history.size();
		isFalse(rig.engine.startBulkOrder(wheat, 0, true).ok(), "orders rejects -> nothing started");
		eq(hist, rig.data.history.size(), "no history entry on rejection");
		rig.ordersAvailable = false;
		NewsEngine.Start none = rig.engine.startBulkOrder(null, 0, true);
		isTrue(!none.ok() && none.error().contains("orders"), "without orders service: error message");
		ctx.detail(amount + "× wheat via NpcOrders");
	}

	private static void bulkOrderClosed(TestContext ctx) {
		Rig rig = new Rig(ctx, 14);
		Item wheat = net.minecraft.world.item.Items.WHEAT;
		int amount = rig.engine.bulkAmount(wheat);
		if (Services.marketValue(wheat).isEmpty() || amount < 2) {
			ctx.detail("skipped (no market value or treasury budget too small: " + Treasury.maxSpendPerHour() + ")");
			return;
		}
		rig.bulkGranted = amount / 2;
		NewsEngine.Start s = rig.engine.startBulkOrder(wheat, 0, true);
		isTrue(s.ok(), "bulk order started: " + s.error());
		NewsEntry e = s.entries().get(0);
		eq(amount / 2, e.amount, "amount as created by orders");
		isTrue(e.orderId > 0 && rig.openOrders.contains(e.orderId), "order id stored: " + e.orderId);
		eq(e.orderId, rig.data.history.get(0).orderId, "order id in the history");
		rig.engine.tick();
		eq(1, rig.engine.runningBulkOrders().size(), "open order -> event running");
		isTrue(rig.ended.isEmpty(), "no end yet");

		long closedAt = rig.engine.now();
		rig.openOrders.remove(e.orderId);
		rig.engine.tick();
		isTrue(rig.engine.runningBulkOrders().isEmpty() && rig.data.active.isEmpty(), "event removed after the tick");
		isTrue(NewsBossBar.displayable(rig.engine, rig.engine.now()).isEmpty(), "nothing left for boss bar/GUI");
		eq(1, rig.ended.size(), "end announced");
		eq(e.id, rig.ended.get(0).id, "end of the right event");
		isFalse(rig.engine.busy(rig.engine.now()), "no longer blocks planning");
		NewsEntry h = rig.data.history.get(0);
		eq(e.id, h.id, "history entry");
		isFalse(h.cancelled, "history: not \"cancelled\" (completed normally)");
		isTrue(h.endMs >= closedAt && h.endMs <= rig.engine.now(), "history: end = time of closing");

		rig.bulkGranted = 0;
		NewsEngine.Start again = rig.engine.startBulkOrder(wheat, 0, true);
		isTrue(again.ok(), "new bulk order right after: " + again.error());
		NewsEntry e2 = again.entries().get(0);
		isTrue(e2.orderId > 0 && e2.orderId != e.orderId, "new order id");
		eq(amount, e2.amount, "full amount");

		rig.openOrders.remove(e2.orderId);
		NewsEngine.Start third = rig.engine.startBulkOrder(wheat, 0, true);
		isTrue(third.ok(), "start right after cancellation (before the tick): " + third.error());
		rig.engine.tick();
		eq(2, rig.ended.size(), "cancellation: end announced");
		eq(1, rig.data.active.size(), "only the new bulk order active");
		NewsEntry e3 = third.entries().get(0);

		rig.isOpenThrows = true;
		rig.openOrders.remove(e3.orderId);
		rig.engine.tick();
		eq(1, rig.engine.runningBulkOrders().size(), "isOpen throws -> event stays");
		rig.isOpenThrows = false;

		long id3 = e3.orderId;
		e3.orderId = 0;
		rig.engine.tick();
		eq(1, rig.engine.runningBulkOrders().size(), "no order id -> expires by time");
		e3.orderId = id3;

		rig.openOrders.add(id3);
		eq(0, rig.engine.clearAll(), "clear does not end an open bulk order");
		eq(1, rig.engine.runningBulkOrders().size(), "open bulk order stays after clear");
		rig.openOrders.remove(id3);
		rig.engine.clearAll();
		isTrue(rig.engine.runningBulkOrders().isEmpty(), "closed bulk order gone after clear");
		rig.engine.tick();
		eq(3, rig.ended.size(), "end announced (not cancelled)");
		isFalse(rig.data.history.get(0).cancelled, "history: not cancelled");

		NewsEngine.Start fourth = rig.engine.startBulkOrder(wheat, 0, true);
		isTrue(fourth.ok(), "fourth bulk order: " + fourth.error());
		rig.ordersAvailable = false;
		rig.engine.tick();
		isTrue(rig.engine.runningBulkOrders().isEmpty() && rig.data.active.isEmpty(), "ended without orders service");
		ctx.detail(amount / 2 + "/" + amount + "× wheat, ends when done/cancelled/no service");
	}

	private static void selection(TestContext ctx) {
		Rig rig = new Rig(ctx, 10);
		int total = 0;
		int nonShop = 0;
		for (int i = 0; i < 600; i++) {
			Optional<Item> h = rig.engine.pickHype(Set.of(), rig.engine.randomHypeFactor());
			if (h.isEmpty()) continue;
			total++;
			if (BasePrices.shopBuyPrice(h.get()).isEmpty()) nonShop++;
		}
		isTrue(total > 0, "hype candidates exist");
		isTrue(nonShop >= total * 0.75, "hypes prefer non-shop items (" + nonShop + "/" + total + ")");
		for (int i = 0; i < 300; i++) {
			Optional<Item> c = rig.engine.pickCrash(Set.of("minecraft:diamond_block", "minecraft:raw_iron"));
			isTrue(c.isEmpty() || (c.get() != net.minecraft.world.item.Items.DIAMOND && c.get() != net.minecraft.world.item.Items.IRON_INGOT),
				"family of an affected item is not picked");
			c.ifPresent(it -> isTrue(BasePrices.sellPrice(it).orElse(0) >= rig.config.minBaseSellCents, "crash only with minimum price"));
			c.ifPresent(it -> isFalse(NewsConfig.NEVER_CRASH.contains(Items.id(it)), "never crash on " + Items.id(it)));
			rig.engine.pickHype(Set.of(), rig.engine.randomHypeFactor())
				.ifPresent(it -> isFalse(NewsConfig.NEVER_HYPE.contains(Items.id(it)) || rig.config.isMarketSold(Items.id(it)), "never hype on " + Items.id(it)));
			double f = rig.engine.randomHypeFactor();
			isTrue(f >= rig.config.hypeMinFactor && f <= rig.config.hypeMaxFactor, "hype factor in range: " + f);
			double cf = rig.engine.randomCrashFactor();
			isTrue(cf >= rig.config.crashFactor - 1e-9 && cf <= rig.config.crashMaxFactor + 1e-9, "crash factor in range: " + cf);
		}
		int focusCrash = 0;
		int crashTotal = 0;
		Set<String> seenFocus = new HashSet<>();
		for (int i = 0; i < 1500; i++) {
			Optional<Item> c = rig.engine.pickCrash(Set.of());
			if (c.isEmpty()) continue;
			crashTotal++;
			if (rig.engine.isFocus(c.get())) {
				focusCrash++;
				seenFocus.add(Items.id(c.get()));
			}
		}
		int focusPool = 0;
		int pool = 0;
		Set<String> ids = new LinkedHashSet<>(rig.config.crashItems);
		ids.addAll(rig.config.focusItems);
		for (String id : ids) {
			Optional<Item> it = Items.byId(id);
			if (it.isEmpty() || !rig.engine.crashAllowed(it.get()) || BasePrices.sellPrice(it.get()).orElse(0) < rig.config.minBaseSellCents) continue;
			pool++;
			if (rig.engine.isFocus(it.get())) focusPool++;
		}
		double share = (double) focusCrash / Math.max(1, crashTotal);
		double poolShare = (double) focusPool / Math.max(1, pool);
		isTrue(share > poolShare + 0.1, "focus items preferred: " + Math.round(share * 100) + "% of crashes at " + Math.round(poolShare * 100) + "% of the pool");
		for (String must : List.of("minecraft:coal", "minecraft:iron_ingot", "minecraft:wheat", "minecraft:diamond")) {
			Optional<Item> it = Items.byId(must);
			if (it.isPresent() && BasePrices.sellPrice(it.get()).orElse(0) >= rig.config.minBaseSellCents && rig.engine.crashAllowed(it.get())) {
				isTrue(seenFocus.contains(must), "crash on " + must + " occurs");
			}
		}
		int[] counts = new int[4];
		for (int i = 0; i < 60; i++) {
			NewsEngine.Start s = rig.engine.startRandomShock(false);
			isTrue(s.ok(), "market shock: " + s.error());
			isTrue(s.entries().size() >= 1 && s.entries().size() <= 3, "1-3 items");
			counts[s.entries().size()]++;
			Set<String> seen = new HashSet<>();
			for (NewsEntry e : s.entries()) {
				for (String m : e.members) isTrue(seen.add(m), "item twice in one shock: " + m);
				isTrue(e.kind() == NewsEntry.Type.CRASH ? e.shownPercent < 0 : e.shownPercent > 0, "sign matches: " + e.shownPercent);
				isTrue(e.endMs - e.startMs >= rig.config.minDurationMinutes * MIN && e.endMs - e.startMs <= rig.config.maxDurationMinutes * MIN,
					"duration " + rig.config.minDurationMinutes + "–" + rig.config.maxDurationMinutes + " min");
				eq(s.entries().get(0).group, e.group, "one group");
			}
			rig.engine.clearAll();
		}
		ctx.detail("non-shop hypes " + nonShop + "/" + total + ", sizes 1/2/3: " + counts[1] + "/" + counts[2] + "/" + counts[3]
			+ ", focus " + Math.round(share * 100) + "% of crashes");
	}

	private static void configFamilies(TestContext ctx) {
		NewsConfig c = new NewsConfig();
		c.validate();
		for (List<String> list : List.of(c.crashItems, c.hypeItems, c.hypeShopItems, c.bulkOrderItems)) {
			for (String id : list) isTrue(Items.byId(id).isPresent(), "item exists: " + id);
		}
		c.families.forEach((k, v) -> {
			isTrue(Items.byId(k).isPresent(), "family exists: " + k);
			for (String m : v) isTrue(Items.byId(m).isPresent(), "family member exists: " + m);
		});
		int checked = 0;
		for (String id : c.hypeItems) {
			Item item = item(id);
			isTrue(BasePrices.sellPrice(item).isPresent(), "hype item has sell price: " + id);
			for (String m : familyIds(c, id)) isTrue(BasePrices.shopBuyPrice(item(m)).isEmpty(), "hype family contains shop item: " + m);
			checked++;
		}
		for (String id : c.hypeShopItems) isTrue(BasePrices.shopBuyPrice(item(id)).isPresent(), "shop hype item is in the shop: " + id);
		for (String id : c.crashItems) isTrue(BasePrices.sellPrice(item(id)).isPresent(), "crash item has sell price: " + id);
		String[][] pairs = {{"minecraft:diamond", "minecraft:diamond_block"}, {"minecraft:emerald", "minecraft:emerald_block"},
			{"minecraft:iron_ingot", "minecraft:iron_block"}, {"minecraft:iron_ingot", "minecraft:iron_nugget"},
			{"minecraft:iron_ingot", "minecraft:raw_iron"}, {"minecraft:gold_ingot", "minecraft:gold_block"},
			{"minecraft:gold_ingot", "minecraft:gold_nugget"}, {"minecraft:gold_ingot", "minecraft:raw_gold"},
			{"minecraft:copper_ingot", "minecraft:copper_block"}, {"minecraft:copper_ingot", "minecraft:raw_copper"},
			{"minecraft:coal", "minecraft:coal_block"}, {"minecraft:lapis_lazuli", "minecraft:lapis_block"},
			{"minecraft:redstone", "minecraft:redstone_block"}, {"minecraft:netherite_scrap", "minecraft:netherite_ingot"},
			{"minecraft:wheat", "minecraft:hay_block"}, {"minecraft:melon_slice", "minecraft:melon"}};
		for (String[] pair : pairs) isTrue(familyIds(c, pair[0]).contains(pair[1]), "family " + pair[0] + " contains " + pair[1]);
		List<String> mustNeverHype = List.of("minecraft:wheat", "minecraft:beetroot", "minecraft:flint", "minecraft:glowstone_dust",
			"minecraft:cocoa_beans", "minecraft:carrot", "minecraft:potato", "minecraft:charcoal", "minecraft:nether_brick",
			"minecraft:emerald", "minecraft:nautilus_shell", "minecraft:chicken", "minecraft:rabbit", "minecraft:armadillo_scute");
		for (String banned : mustNeverHype) {
			isTrue(NewsConfig.NEVER_HYPE.contains(banned), "hype blocklist contains " + banned);
			isFalse(c.hypeItems.contains(banned) || c.hypeShopItems.contains(banned), "not hypeable (craftable/farmable): " + banned);
		}
		for (String id : List.of("minecraft:bone", "minecraft:bone_block", "minecraft:bone_meal")) {
			isTrue(NewsConfig.NEVER_CRASH.contains(id), "crash blocklist contains " + id);
			isFalse(c.crashItems.contains(id), "no crash on " + id);
		}
		for (String id : List.of("minecraft:bone_meal", "minecraft:bone", "minecraft:oak_sapling", "minecraft:birch_sapling",
			"minecraft:mangrove_propagule")) {
			isTrue(c.flashSaleExcludedItems.contains(id), "never in a flash sale: " + id);
		}
		NewsConfig stale = new NewsConfig();
		stale.hypeItems.add("minecraft:armadillo_scute");
		stale.hypeShopItems.add("minecraft:carrot");
		stale.crashItems.add("minecraft:bone");
		stale.flashSaleExcludedItems = new ArrayList<>(List.of("minecraft:dirt"));
		stale.validate();
		isFalse(stale.hypeItems.contains("minecraft:armadillo_scute") || stale.hypeShopItems.contains("minecraft:carrot"), "validate removes blocked hype items");
		isFalse(stale.crashItems.contains("minecraft:bone"), "validate removes blocked crash items");
		isTrue(stale.flashSaleExcludedItems.contains("minecraft:dirt") && stale.flashSaleExcludedItems.containsAll(NewsConfig.FLASH_ALWAYS_EXCLUDED),
			"validate adds flash exclusions, custom ones stay");
		for (List<String> list : List.of(c.hypeItems, c.hypeShopItems)) {
			for (String id : list) isFalse(c.isMarketSold(id), "hype list contains market NPC goods: " + id);
		}
		for (String id : c.focusItems) isTrue(Items.byId(id).isPresent() && BasePrices.sellPrice(item(id)).isPresent(), "focus item with sell price: " + id);
		for (String id : List.of("minecraft:coal", "minecraft:iron_ingot", "minecraft:wheat", "minecraft:diamond")) {
			isTrue(c.focusItems.contains(id), "focus contains " + id);
		}
		ctx.detail(checked + " hype items, " + c.families.size() + " families checked");
	}

	/**
	 * What market NPCs sell (non-shop items without pivot) never gets an automatic hype, also not from an old news.json (buy from
	 * the NPC before the hype, sell via /sell during it). Crashes are allowed ({@code crashMarketSold}): they only lower the sell
	 * side, not the NPC offer floor. With {@code crashMarketSold=false} crashes are blocked too.
	 */
	private static void marketSold(TestContext ctx) {
		NewsConfig c = new NewsConfig();
		c.validate();
		Set<String> sold = c.marketSoldIds();
		for (String id : List.of("minecraft:diamond", "minecraft:diamond_block", "minecraft:emerald", "minecraft:iron_ingot",
			"minecraft:iron_block", "minecraft:gold_ingot", "minecraft:gold_block", "minecraft:golden_apple", "minecraft:netherite_scrap",
			"minecraft:netherite_ingot")) {
			isTrue(sold.contains(id) && c.isMarketSold(id), "market NPC goods with family: " + id);
		}
		NewsConfig stale = new NewsConfig();
		stale.crashItems.addAll(List.of("minecraft:diamond", "minecraft:iron_block"));
		stale.hypeItems.addAll(List.of("minecraft:gold_ingot", "minecraft:netherite_scrap", "minecraft:raw_iron"));
		stale.validate();
		for (List<String> list : List.of(stale.hypeItems, stale.hypeShopItems)) {
			for (String id : list) isFalse(stale.isMarketSold(id), "validate leaves market NPC goods in hype list: " + id);
		}
		isTrue(stale.crashItems.contains("minecraft:diamond"), "crash on market goods allowed (crashMarketSold)");
		NewsConfig strict = new NewsConfig();
		strict.crashMarketSold = false;
		strict.crashItems.addAll(List.of("minecraft:diamond", "minecraft:iron_block"));
		strict.validate();
		for (String id : strict.crashItems) isFalse(strict.isMarketSold(id), "crashMarketSold=false: validate removes " + id);
		isTrue(stale.hypeItems.contains("minecraft:coal"), "validate keeps the rest");
		Rig rig = new Rig(ctx, 20);
		rig.config.crashItems = new ArrayList<>(List.of("minecraft:diamond", "minecraft:gold_block", "minecraft:coal"));
		rig.config.focusItems = new ArrayList<>();
		rig.config.hypeItems = new ArrayList<>(List.of("minecraft:iron_ingot", "minecraft:netherite_ingot", "minecraft:coal"));
		rig.config.hypeShopItems = new ArrayList<>();
		rig.config.hypePreferNonShop = 1.0;
		int marketCrashes = 0;
		int hypes = 0;
		for (int i = 0; i < 200; i++) {
			Optional<Item> cr = rig.engine.pickCrash(Set.of());
			if (cr.isPresent() && c.isMarketSold(Items.id(cr.get()))) marketCrashes++;
			Optional<Item> hy = rig.engine.pickHype(Set.of(), 2.0);
			isTrue(hy.isEmpty() || !c.isMarketSold(Items.id(hy.get())), "hype on market NPC goods: " + hy.map(Items::id).orElse("-"));
			if (hy.isPresent()) hypes++;
		}
		isTrue(marketCrashes > 0 && hypes > 0, "crash on market goods selectable, hype only coal");
		rig.config.crashMarketSold = false;
		for (int i = 0; i < 100; i++) {
			Optional<Item> cr = rig.engine.pickCrash(Set.of());
			isTrue(cr.isEmpty() || !c.isMarketSold(Items.id(cr.get())), "crashMarketSold=false: crash on " + cr.map(Items::id).orElse("-"));
		}
		Rig def = new Rig(ctx, 21);
		for (int i = 0; i < 80; i++) {
			NewsEngine.Start st = def.engine.startRandomShock(false);
			isTrue(st.ok(), "market shock: " + st.error());
			for (NewsEntry e : st.entries()) {
				if (e.kind() != NewsEntry.Type.HYPE) continue;
				for (String m : e.members) isFalse(sold.contains(m), "hype hits market NPC goods: " + m + " (" + e.item + ")");
			}
			def.engine.clearAll();
		}
		NewsEngine.Start manual = def.engine.triggerShock(net.minecraft.world.item.Items.DIAMOND, 2.0, 5, true);
		isTrue(manual.ok(), "admin hype on diamond possible");
		isTrue(NewsCommands.warnings(manual.entries().get(0)).stream().anyMatch(w -> w.contains("Trader goods")), "admin gets a warning");
		NewsEngine.Start crash = def.engine.triggerShock(net.minecraft.world.item.Items.DIAMOND, 0.5, 5, true);
		isTrue(crash.ok() && NewsCommands.warnings(crash.entries().get(0)).stream().noneMatch(w -> w.contains("Trader goods")), "crash: no warning");
		def.engine.clearAll();
		ctx.detail(sold.size() + " market items never hyped, crash allowed (" + marketCrashes + "/200), admin warning ok");
	}

	/**
	 * What market really sells through NPC sellers it reports through {@link MarketGoods}: news never hypes it, with family,
	 * automatically, also goods outside {@link NewsConfig#MARKET_SOLD}. Reported goods are not in the default selection,
	 * otherwise those news would silently vanish. Without market only the fixed list applies; if the service throws, everything
	 * counts as market goods.
	 */
	private static void marketGoods(TestContext ctx) {
		Optional<MarketGoods> live = Services.find(MarketGoods.class);
		ctx.cleanup(() -> live.ifPresent(s -> Services.register(MarketGoods.class, s)));
		NewsConfig c = new NewsConfig();
		c.validate();
		int liveChecked = 0;
		if (live.isPresent()) {
			Rig rig = new Rig(ctx, 30);
			boolean crashMarket = crashesMarketGoods(rig);
			Set<String> ids = live.get().shockSensitiveIds();
			isFalse(ids.isEmpty(), "market reports its goods");
			for (String id : ids) {
				isTrue(c.isMarketSold(id), "market goods blocked: " + id);
				Optional<Item> it = Items.byId(id);
				if (it.isEmpty()) continue;
				isFalse(rig.engine.hypeAllowed(it.get()), "no hype on market goods: " + id);
				if (!crashMarket) isFalse(rig.engine.crashAllowed(it.get()), "no crash on market goods (like the fixed list): " + id);
				liveChecked++;
			}
		}
		Set<String> goods = Set.of("minecraft:copper_block", "minecraft:quartz");
		Hooks.Registration fake = Services.register(MarketGoods.class, () -> goods);
		ctx.cleanup(fake::remove);
		isTrue(c.isMarketSold("minecraft:copper_ingot") && c.isMarketSold("minecraft:raw_copper") && c.isMarketSold("minecraft:copper_block"),
			"copper family blocked");
		isTrue(c.isMarketSold("minecraft:quartz") && c.isMarketSold("minecraft:nether_quartz_ore"), "quartz and ore blocked");
		isFalse(c.isMarketSold("minecraft:coal") || c.isMarketSold("minecraft:lapis_lazuli"), "coal and lapis stay free");
		isTrue(c.isMarketSold("minecraft:diamond") && c.marketSoldIds().contains("minecraft:diamond")
			&& !c.marketSoldIds().contains("minecraft:copper_ingot"), "fixed list still applies, marketSoldIds stays the fixed list");
		Rig rig = new Rig(ctx, 31);
		boolean crashMarket = crashesMarketGoods(rig);
		List<String> candidates = List.of("minecraft:copper_ingot", "minecraft:quartz", "minecraft:coal", "minecraft:lapis_lazuli");
		rig.config.crashItems = new ArrayList<>(candidates);
		rig.config.hypeItems = new ArrayList<>(candidates);
		rig.config.hypeShopItems = new ArrayList<>();
		rig.config.hypePreferNonShop = 1.0;
		Set<String> picked = new HashSet<>();
		for (int i = 0; i < 200; i++) {
			rig.engine.pickCrash(Set.of()).ifPresent(it -> picked.add("crash:" + Items.id(it)));
			rig.engine.pickHype(Set.of(), 2.0).ifPresent(it -> picked.add("hype:" + Items.id(it)));
		}
		for (String p : picked) {
			if (crashMarket && p.startsWith("crash:")) continue;
			isFalse(p.endsWith("copper_ingot") || p.endsWith("quartz"), "shock on reported market goods: " + p);
		}
		isTrue(picked.contains("crash:minecraft:coal") && picked.contains("hype:minecraft:lapis_lazuli"), "free items stay selectable " + picked);
		for (int i = 0; i < 40; i++) {
			NewsEngine.Start st = rig.engine.startRandomShock(false);
			isTrue(st.ok(), "market shock: " + st.error());
			for (NewsEntry e : st.entries()) {
				if (crashMarket && e.kind() != NewsEntry.Type.HYPE) continue;
				for (String m : e.members) isFalse(c.isMarketSold(m), e.kind() + " hits market goods: " + m + " (" + e.item + ")");
			}
			rig.engine.clearAll();
		}
		NewsEngine.Start manual = rig.engine.triggerShock(net.minecraft.world.item.Items.COPPER_INGOT, 2.0, 5, true);
		isTrue(manual.ok(), "admin hype on copper possible");
		isTrue(NewsCommands.warnings(manual.entries().get(0)).stream().anyMatch(w -> w.contains("Trader goods")), "admin gets a warning");
		rig.engine.clearAll();
		fake.remove();
		Hooks.Registration broken = Services.register(MarketGoods.class, () -> {
			throw new IllegalStateException("Test: MarketGoods throws");
		});
		ctx.cleanup(broken::remove);
		isTrue(c.isMarketSold("minecraft:coal"), "service throws -> everything blocked (fail-closed)");
		isTrue(rig.engine.pickHype(Set.of(), 2.0).isEmpty(), "no hype while the service throws");
		if (!crashMarket) isTrue(rig.engine.pickCrash(Set.of()).isEmpty(), "no crash while the service throws (market goods do not crash)");
		broken.remove();
		isFalse(c.isMarketSold("minecraft:copper_ingot"), "without market: copper free");
		isTrue(c.isMarketSold("minecraft:diamond") && c.isMarketSold("minecraft:gold_block"), "without market: fixed list still applies");
		ctx.detail((live.isPresent() ? liveChecked + " items blocked by market" : "market not active") + ", market crash "
			+ (crashMarket ? "allowed" : "blocked") + ", test service/error/no market ok");
	}

	/** Read from diamond in the fixed list {@link NewsConfig#MARKET_SOLD}: goods market reports must behave the same on a crash. */
	private static boolean crashesMarketGoods(Rig rig) {
		return rig.engine.crashAllowed(net.minecraft.world.item.Items.DIAMOND);
	}

	private static void villagerCore(TestContext ctx) {
		Item log = net.minecraft.world.item.Items.OAK_LOG;
		Item planks = net.minecraft.world.item.Items.OAK_PLANKS;
		Item stick = net.minecraft.world.item.Items.STICK;
		Item string = net.minecraft.world.item.Items.STRING;
		Item wool = net.minecraft.world.item.Items.WHITE_WOOL;
		Item bucket = net.minecraft.world.item.Items.BUCKET;
		Item water = net.minecraft.world.item.Items.WATER_BUCKET;
		Item dirt = net.minecraft.world.item.Items.DIRT;
		List<NewsVillagerRoutes.Recipe> recipes = new ArrayList<>(List.of(
			new NewsVillagerRoutes.Recipe(planks, 4, List.of(List.of(log))),
			new NewsVillagerRoutes.Recipe(stick, 4, List.of(List.of(planks), List.of(planks))),
			new NewsVillagerRoutes.Recipe(wool, 1, List.of(List.of(string), List.of(string), List.of(string), List.of(string)))));
		recipes.addAll(NewsVillagerRoutes.fillRecipes());
		List<NewsVillagerRoutes.Trade> trades = List.of(new NewsVillagerRoutes.Trade(stick, 32, 1),
			new NewsVillagerRoutes.Trade(wool, 18, 1), new NewsVillagerRoutes.Trade(water, 1, 2));
		Map<Item, Long> shop = new HashMap<>(Map.of(log, 300L, planks, 80L, stick, 40L, string, 120L, bucket, 2000L, dirt, 25L));
		NewsVillagerRoutes.Result r = NewsVillagerRoutes.compute(trades, recipes, shop, 2500, Set.of());
		isTrue(r.complete(), "computation complete");
		eq(Set.of(log, planks, stick, bucket), r.items(), "excluded: log, planks, sticks (32 sticks -> emerald), bucket (water bucket)");
		isFalse(r.items().contains(string) || r.items().contains(wool), "string -> wool never pays (18 × 4 × pivot + 1 > emerald)");
		isTrue(NewsVillagerRoutes.criticalTrades(trades, recipes, shop, 2500, r.items()).isEmpty(), "no critical trade with the exclusions");
		eq(1, NewsVillagerRoutes.criticalTrades(trades, recipes, shop, 2500, Set.of(log, planks, bucket)).size(), "without sticks: sticks directly critical");
		isTrue(r.reasons().get(log).contains("minecraft:stick"), "reason: " + r.reasons().get(log));
		NewsVillagerRoutes.Result cheap = NewsVillagerRoutes.compute(trades, recipes, shop, 500, Set.of());
		isTrue(cheap.complete() && cheap.items().isEmpty(), "emerald $5: nothing excluded, was " + cheap.items());
		isTrue(NewsVillagerRoutes.compute(trades, recipes, shop, 0, Set.of()).items().isEmpty(), "emerald unsellable: nothing");
		NewsVillagerRoutes.Result withStart = NewsVillagerRoutes.compute(trades, recipes, shop, 2500, Set.of(stick));
		isFalse(withStart.items().contains(stick), "start not in the result");
		isTrue(withStart.items().containsAll(Set.of(log, planks)), "log/planks still excluded");
		ctx.detail("log->planks->sticks, bucket->water bucket, no wool, cheap emerald -> nothing");
	}

	/**
	 * Real game data: no villager route ("shop, villager, emerald, /sell") gets cheaper through a crash or flash sale while it
	 * would pay. Crash: the shop price never falls (PriceGuard), so it may hit villager goods. Flash sale: skips them. Directly
	 * traded shop items are also checked with the real PriceGuard.
	 */
	private static void villagerRoutes(TestContext ctx) {
		Set<Item> set = NewsVillagerRoutes.items();
		if (set == null) fail("villager list not computed (startup/error), crash/flash sale then skip all shop items");
		List<NewsVillagerRoutes.Trade> trades = NewsVillagerRoutes.trades();
		isTrue(trades.size() >= 40, "trades read: " + trades.size());
		isTrue(NewsVillagerRoutes.recipes().size() >= 500, "recipes read: " + NewsVillagerRoutes.recipes().size());
		Item stick = net.minecraft.world.item.Items.STICK;
		isTrue(trades.stream().anyMatch(t -> t.input() == stick && t.count() == 32 && t.emeralds() == 1), "trade 32 sticks -> 1 emerald found");
		long emerald = NewsVillagerRoutes.emeraldValue();
		isTrue(emerald >= BasePrices.sellPrice(net.minecraft.world.item.Items.EMERALD).orElse(0), "emerald value >= sell price");
		Map<Item, Long> shop = BasePrices.shopBuyPrices();
		List<NewsVillagerRoutes.Trade> open = NewsVillagerRoutes.criticalTrades(trades, NewsVillagerRoutes.recipes(), shop, emerald, set);
		isTrue(open.isEmpty(), "critical trades despite exclusions: " + open);
		Map<String, Long> defaults = new java.util.LinkedHashMap<>();
		defaults.put("minecraft:stick", 40L);
		defaults.put("minecraft:oak_log", 300L);
		defaults.put("minecraft:carrot", 150L);
		defaults.put("minecraft:potato", 150L);
		defaults.put("minecraft:sugar_cane", 100L);
		defaults.put("minecraft:nether_wart", 150L);
		defaults.put("minecraft:cactus", 80L);
		defaults.put("minecraft:string", 120L);
		defaults.put("minecraft:leather", 250L);
		defaults.put("minecraft:bucket", 2000L);
		int known = 0;
		if (emerald == 2500) {
			for (Map.Entry<String, Long> d : defaults.entrySet()) {
				Item it = item(d.getKey());
				if (BasePrices.shopBuyPrice(it).orElse(-1) != d.getValue()) continue;
				isTrue(set.contains(it), "villager goods not excluded: " + d.getKey());
				known++;
			}
		}
		Rig rig = new Rig(ctx, 22);
		FakePlayer p = ctx.player("NewsTestA");
		UUID u = p.getUUID();
		Map<Item, Long> before = new HashMap<>();
		for (NewsVillagerRoutes.Trade t : trades) {
			if (shop.containsKey(t.input())) before.put(t.input(), PriceGuard.effectiveShopBuyPrice(u, t.input()).orElse(0));
		}
		for (Item it : shop.keySet()) PriceModifiers.set(SRC, it, 0.5, 0, 10 * MIN, 0, "Test");
		for (Map.Entry<Item, Long> e : before.entrySet()) {
			eq((long) e.getValue(), PriceGuard.effectiveShopBuyPrice(u, e.getKey()).orElse(0), "crash does not lower villager goods: " + Items.id(e.getKey()));
		}
		Hooks.Registration reg = Hooks.addShopDiscount(FLASH, "Flash Sale (Test)", it -> rig.engine.flashExcluded(it) ? 1.0 : 0.05);
		ctx.cleanup(reg::remove);
		// Compared with the cheapest normal route (e.g. glass bottles from sand are already cheaper than the flash sale price)
		NewsVillagerRoutes.Costs normal = NewsVillagerRoutes.solve(NewsVillagerRoutes.recipes(), shop, Set.of(), false);
		int direct = 0;
		for (NewsVillagerRoutes.Trade t : trades) {
			Long b = before.get(t.input());
			if (b == null) continue;
			long now = PriceGuard.effectiveShopBuyPrice(u, t.input()).orElse(0);
			double cheapestNormal = Math.min(b, normal.of(t.input()));
			boolean worse = now < cheapestNormal - 1e-9 && (double) t.count() * now < (double) t.emeralds() * emerald;
			isFalse(worse, Items.id(t.input()) + ": crash/flash sale makes " + t.count() + "× → " + t.emeralds() + " emerald profitable (" + b + " → " + now
				+ " cents/unit)");
			direct++;
		}
		for (Item it : set) isTrue(rig.engine.flashExcluded(it), "flash sale skips: " + Items.id(it));
		ctx.detail(set.size() + " shop items excluded (" + trades.size() + " trades), " + direct + " checked directly with PriceGuard, "
			+ known + " known cases");
	}

	private static void autoPause(TestContext ctx) {
		Rig rig = new Rig(ctx, 23);
		rig.data.targetMs = 20 * MIN;
		rig.data.progressMs = 7 * MIN;
		isTrue(NewsFeature.autoActive(rig.config, rig.data), "default: automatic on");
		rig.engine.setAutoPaused(true);
		isTrue(rig.engine.autoPaused(), "paused");
		isFalse(NewsFeature.autoActive(rig.config, rig.data), "paused -> no scheduling");
		eq(7 * MIN, rig.data.progressMs, "pause drops nothing");
		Gson gson = DataStore.gson();
		NewsData back = gson.fromJson(gson.toJson(rig.data), NewsData.class);
		isTrue(back.autoPaused, "pause survives restart (news.json)");
		eq(0, rig.engine.setAutoPaused(false), "resume: nothing due");
		eq(7 * MIN, rig.data.progressMs, "counter stays");
		rig.engine.setAutoPaused(true);
		rig.data.progressMs = rig.data.targetMs;
		eq(1, rig.engine.setAutoPaused(false), "resume drops the due event");
		eq(0L, rig.data.progressMs, "counter reset");
		isTrue(NewsFeature.autoActive(rig.config, rig.data), "on again");
		rig.config.enabled = false;
		isFalse(NewsFeature.autoActive(rig.config, rig.data), "news.json enabled=false stays off");
		eq(Optional.of(false), NewsCommands.parseSwitch("off"), "off");
		eq(Optional.of(true), NewsCommands.parseSwitch("on"), "on");
		eq(Optional.of(true), NewsCommands.parseSwitch("ON"), "ON");
		isTrue(NewsCommands.parseSwitch("maybe").isEmpty(), "invalid");
		NewsData legacy = gson.fromJson("{\"history\":[],\"planDay\":\"2026-07-01\",\"slots\":[1,2],\"firedOnPlanDay\":1}", NewsData.class);
		legacy.repair();
		isFalse(legacy.autoPaused, "default not paused");
		eq(0L, legacy.progressMs, "old file: counter starts at 0");
		isFalse(gson.toJson(legacy).contains("slots"), "old planning fields vanish on save");
		ctx.detail("pause persisted, resume without catch-up event, old news.json readable");
	}

	static Set<String> familyIds(NewsConfig c, String id) {
		return c.familyIds(id);
	}

	private static void persistence(TestContext ctx) {
		Rig rig = new Rig(ctx, 11);
		Item diamond = net.minecraft.world.item.Items.DIAMOND;
		Item emerald = net.minecraft.world.item.Items.EMERALD;
		Item iron = net.minecraft.world.item.Items.IRON_INGOT;
		long now = System.currentTimeMillis();
		NewsEntry running = entry(1, "CRASH", "minecraft:diamond", List.of("minecraft:diamond", "minecraft:diamond_block"), 0.5,
			now - 5 * MIN, now + 10 * MIN, 5 * MIN);
		NewsEntry fading = entry(2, "HYPE", "minecraft:iron_ingot", List.of("minecraft:iron_ingot"), 3.0, now - 30 * MIN, now - 2 * MIN, 5 * MIN);
		NewsEntry expired = entry(3, "CRASH", "minecraft:coal", List.of("minecraft:coal"), 0.5, now - 60 * MIN, now - 20 * MIN, 5 * MIN);
		NewsData d = new NewsData();
		d.active.addAll(List.of(running, fading, expired));
		d.history.add(running.copy());
		d.lastEventAt = now - 5 * MIN;
		d.progressMs = 11 * MIN;
		d.targetMs = 22 * MIN;
		Gson gson = DataStore.gson();
		rig.data = gson.fromJson(gson.toJson(d), NewsData.class);
		eq(3, rig.data.active.size(), "entries survive JSON");
		eq(0.5, rig.data.active.get(0).factor, "factor survives JSON");
		eq(11 * MIN, rig.data.progressMs, "schedule counter survives JSON");
		eq(22 * MIN, rig.data.targetMs, "schedule interval survives JSON");
		PriceModifiers.set(SRC, emerald, 2.0, 0, 10 * MIN, 5 * MIN, "Test orphan");
		rig.engine.reconcile();
		eq(2, rig.data.active.size(), "expired removed");
		close(0.5, PriceModifiers.get(SRC, diamond), "missing factor restored");
		close(0.5, PriceModifiers.get(SRC, net.minecraft.world.item.Items.DIAMOND_BLOCK), "family restored");
		double ironNow = PriceModifiers.get(SRC, iron);
		isTrue(ironNow > 1.0 && ironNow < 3.0, "fading shock restored with intermediate value: " + ironNow);
		boolean orphanFading = false;
		for (PriceModifiers.Active a : PriceModifiers.active(emerald)) {
			if (a.source().equals(SRC)) orphanFading = a.endsAtMs() > 0 && a.endsAtMs() <= System.currentTimeMillis() + 1_000;
		}
		isTrue(orphanFading, "orphaned factor is faded out");
		eq(1, rig.data.nextId > 3 ? 1 : 0, "nextId past the ids");
		rig.engine.reconcile();
		eq(2, rig.data.active.size(), "stable");
		ctx.detail("JSON round trip, restore, orphans, expiry ok");
	}

	static NewsEntry entry(long id, String type, String item, List<String> members, double factor, long start, long end, long fade) {
		NewsEntry e = new NewsEntry();
		e.id = id;
		e.group = id;
		e.type = type;
		e.item = item;
		e.members = new ArrayList<>(members);
		e.factor = factor;
		e.startMs = start;
		e.endMs = end;
		e.fadeOutMs = fade;
		return e;
	}

	private static void endAndHistory(TestContext ctx) {
		Rig rig = new Rig(ctx, 12);
		Item diamond = net.minecraft.world.item.Items.DIAMOND;
		Item emerald = net.minecraft.world.item.Items.EMERALD;
		if (BasePrices.sellPrice(diamond).isEmpty() || BasePrices.sellPrice(emerald).isEmpty()) {
			ctx.detail("skipped");
			return;
		}
		eq(100, rig.config.historySize, "default history 100 (≈ 2 days at ~50 events)");
		for (int i = 0; i < 120; i++) isTrue(rig.engine.triggerShock(i % 2 == 0 ? diamond : emerald, i % 3 == 0 ? 2.0 : 0.5, 30, true).ok(), "Start " + i);
		eq(100, rig.data.history.size(), "history limited to 100");
		rig.engine.clearAll();
		rig.config.historySize = 20;
		for (int i = 0; i < 25; i++) isTrue(rig.engine.triggerShock(i % 2 == 0 ? diamond : emerald, i % 3 == 0 ? 2.0 : 0.5, 30, true).ok(), "Start " + i);
		eq(20, rig.data.history.size(), "history limited to historySize");
		eq(20L, rig.data.history.stream().filter(NewsGui.Filter.ALL::matches).count(), "filter all");
		isTrue(rig.data.history.stream().filter(NewsGui.Filter.CRASH::matches).allMatch(x -> x.kind() == NewsEntry.Type.CRASH), "filter crashes");
		isTrue(rig.data.history.stream().filter(NewsGui.Filter.HYPE::matches).count() > 0, "filter hypes");
		eq(0L, rig.data.history.stream().filter(NewsGui.Filter.ACTION::matches).count(), "filter actions");
		eq(NewsGui.Filter.CRASH, NewsGui.Filter.ALL.next(), "filter next");
		eq(NewsGui.Filter.ALL, NewsGui.Filter.ACTION.next(), "filter wraps");
		for (int i = 1; i < rig.data.history.size(); i++) {
			isTrue(rig.data.history.get(i - 1).id > rig.data.history.get(i).id, "newest first");
		}
		eq(2, rig.data.active.size(), "only one active shock per family");
		rig.engine.clearAll();
		NewsEngine.Start s = rig.engine.triggerShock(diamond, 0.5, 1, true);
		NewsEntry e = s.entries().get(0);
		rig.offset = MIN + 1_000;
		rig.engine.tick();
		eq(1, rig.ended.size(), "end announced");
		eq(1, rig.data.active.size(), "still visible during fade-out");
		isFalse(rig.engine.busy(rig.engine.now()), "fade-out does not block planning");
		rig.engine.tick();
		eq(1, rig.ended.size(), "announced only once");
		rig.offset = MIN + e.fadeOutMs + 1_000;
		rig.engine.tick();
		isTrue(rig.data.active.isEmpty(), "removed after fade-out");
		isFalse(rig.data.history.get(0).cancelled, "ended normally = not cancelled");
		ctx.detail("history 100/20, filter, one-time end, fade-out " + rig.config.fadeOutMinutes + " min");
	}

	private static void texts(TestContext ctx) {
		eq(80, NewsPrices.truncPercent(1.8), "1.8 → +80");
		eq(-50, NewsPrices.truncPercent(0.5), "0.5 → −50");
		eq(135, NewsPrices.truncPercent(2.35), "2.35 → +135");
		eq(79, NewsPrices.truncPercent(1.7999), "truncated, never rounded up");
		eq(0, NewsPrices.truncPercent(0.999), "0.999 → 0");
		eq("+80%", NewsPrices.pct(80), "pct +");
		eq("-50%", NewsPrices.pct(-50), "pct −");
		eq(Optional.of(2.5), NewsCommands.parseFactor("+150"), "+150");
		eq(Optional.of(2.5), NewsCommands.parseFactor("150"), "150");
		eq(Optional.of(0.5), NewsCommands.parseFactor("-50"), "-50");
		eq(Optional.of(0.5), NewsCommands.parseFactor("-50%"), "-50%");
		for (String bad : List.of("0", "abc", "-96", "1001", "", "++5", "1.5")) isTrue(NewsCommands.parseFactor(bad).isEmpty(), "invalid: '" + bad + "'");
		eq(0, NewsBossBar.tickerIndex(0, 2, 3), "Index 0");
		eq(1, NewsBossBar.tickerIndex(2_000, 2, 3), "next event after 2 s");
		eq(0, NewsBossBar.tickerIndex(6_000, 2, 3), "wraps");
		eq("§a" + "|".repeat(10) + "§8" + "|".repeat(10), NewsGui.progressBar(0.5f), "progress bar");
		Rig rig = new Rig(ctx, 13);
		long now = System.currentTimeMillis();
		NewsEntry crash = entry(1, "CRASH", "minecraft:diamond", List.of("minecraft:diamond", "minecraft:diamond_block"), 0.5, now - MIN, now + 9 * MIN, 5 * MIN);
		crash.shownPercent = -50;
		eq(0.9f, NewsText.progress(crash, now), "progress = time left");
		eq(0f, NewsText.progress(crash, now + 10 * MIN), "0 after end");
		eq("RED", NewsText.color(crash).name(), "crash red");
		NewsEntry hype = entry(2, "HYPE", "minecraft:emerald", List.of("minecraft:emerald"), 2.5, now, now + 10 * MIN, 5 * MIN);
		eq("GREEN", NewsText.color(hype).name(), "hype green");
		NewsEntry flash = entry(3, "FLASH_SALE", "minecraft:oak_log", List.of(), 0.7, now, now + 10 * MIN, 0);
		flash.categoryName = "Wood";
		flash.shownPercent = -30;
		NewsEntry bulk = entry(4, "BULK_ORDER", "minecraft:wheat", List.of(), 1.0, now, now + 10 * MIN, 0);
		bulk.amount = 1024;
		NewsEntry fever = entry(5, "AUCTION_FEVER", "minecraft:gold_ingot", List.of(), 1.0, now, now + 10 * MIN, 0);
		String boss = NewsText.bossLine(rig.engine, crash, now, 0, 2).getString();
		isTrue(boss.contains("CRASH") && boss.contains("[1/2]") && boss.contains("left"), "boss bar text crash: " + boss);
		isTrue(NewsText.bossLine(rig.engine, flash, now, 1, 2).getString().contains("up to -30%"), "boss bar flash sale");
		isTrue(NewsText.bossLine(rig.engine, bulk, now, 0, 1).getString().contains("1,024×"), "boss bar bulk order");
		isTrue(NewsText.bossLine(rig.engine, fever, now, 0, 1).getString().contains("AUCTION FEVER"), "boss bar fever");
		isTrue(NewsText.bossLine(rig.engine, crash, now + 11 * MIN, 0, 1).getString().contains("returning to normal"), "boss bar during fade-out");
		String sub = NewsText.subtitle(List.of(crash, hype)).getString();
		isTrue(sub.contains("-50%!") && sub.contains("·"), "subtitle: " + sub);
		for (NewsEntry e : List.of(crash, hype, flash, bulk, fever)) {
			isFalse(NewsText.chatLine(rig.engine, List.of(e)).getString().isEmpty(), "chat line " + e.type);
			isFalse(NewsText.endLine(e).getString().isEmpty(), "end line " + e.type);
			isFalse(NewsText.details(rig.engine, e).getString().isEmpty(), "Details " + e.type);
		}
		isTrue(NewsText.familyLine(crash).getString().startsWith("Also applies to"), "family line");
		ctx.detail("percent, parser, ticker, texts ok");
	}

	private static void announceSetting(TestContext ctx) {
		FakePlayer p = ctx.player("NewsTestB");
		isTrue(NewsBroadcast.wantsAnnouncements(p), "default: title/sound on");
		Settings.set(p.getUUID(), Settings.ANNOUNCEMENTS, false);
		isFalse(NewsBroadcast.wantsAnnouncements(p), "setting off -> no title/sound");
		Settings.set(p.getUUID(), Settings.ANNOUNCEMENTS, true);
		isTrue(NewsBroadcast.wantsAnnouncements(p), "on again");
		ctx.detail("Settings.ANNOUNCEMENTS respected");
	}

	private static void productionHooks(TestContext ctx) {
		NewsEngine prod = NewsFeature.engine();
		isTrue(prod != null, "live engine exists");
		eq(NewsFeature.SOURCE, prod.source, "source news");
		eq(prod.feverActive(), Hooks.flag(Hooks.FLAG_AUCTION_FEVER), "auction fever flag = state");
		int checked = 0;
		for (BasePrices.ShopCategory cat : BasePrices.shopCategories()) {
			for (BasePrices.ShopItem si : cat.items()) {
				boolean part = Hooks.shopParts(si.item()).stream().anyMatch(pt -> pt.id().equals(NewsFeature.FLASH_HOOK));
				eq(prod.flashDiscount(si.item()) != 1.0, part, "flash sale hook " + Items.id(si.item()));
				checked++;
			}
		}
		for (PriceModifiers.Active a : PriceModifiers.active()) isFalse(a.source().equals(SRC) && a.endsAtMs() == 0, "no unlimited test factor");
		ctx.detail(checked + " shop items, flag ok");
	}
}
