package dev.smpeconomy.features.daily;

import static dev.smpeconomy.core.test.Assert.assertThrows;
import static dev.smpeconomy.core.test.Assert.check;
import static dev.smpeconomy.core.test.Assert.eq;
import static dev.smpeconomy.core.test.Assert.isFalse;
import static dev.smpeconomy.core.test.Assert.isTrue;

import com.google.gson.JsonObject;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.entity.FakePlayer;
import dev.smpeconomy.core.CrateKeys;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.Settings;
import dev.smpeconomy.core.TimeUtil;
import dev.smpeconomy.core.test.SelfTests;
import dev.smpeconomy.core.test.TestContext;
import dev.smpeconomy.features.daily.DailyLogic.DayState;
import dev.smpeconomy.features.daily.DailyLogic.Status;
import dev.smpeconomy.features.daily.DailyService.Claim;
import dev.smpeconomy.features.daily.DailyService.Outcome;

/**
 * Logic without GUI clicks; e2e/daily.mjs tests the GUI. Times are in the fixed test zone (SelfTests.TEST_ZONE).
 */
final class DailySelfTests {
	private static final String F = DailyFeature.ID;
	private static final long H = DailyLogic.HOUR_MS;
	private static final long DAY = 24 * H;

	private DailySelfTests() {}

	static void register() {
		SelfTests.register(F, "config-defaults", ctx -> configDefaults(ctx));
		SelfTests.register(F, "config-validation", ctx -> configValidation(ctx));
		SelfTests.register(F, "cycle-day", ctx -> cycleDay(ctx));
		SelfTests.register(F, "day-states", ctx -> dayStates(ctx));
		SelfTests.register(F, "overflow-guards", ctx -> overflowGuards(ctx));
		SelfTests.register(F, "first-claim-once-per-day", DailySelfTests::firstClaim);
		SelfTests.register(F, "midnight-boundary", DailySelfTests::midnightBoundary);
		SelfTests.register(F, "streak-48h", DailySelfTests::streak48h);
		SelfTests.register(F, "full-week-key-and-cycle", DailySelfTests::fullWeek);
		SelfTests.register(F, "streak-break-resets", DailySelfTests::streakBreak);
		SelfTests.register(F, "dst-transitions", DailySelfTests::dst);
		SelfTests.register(F, "clock-backwards", DailySelfTests::clockBackwards);
		SelfTests.register(F, "money-limit-no-change", DailySelfTests::moneyLimit);
		SelfTests.register(F, "zero-reward-day", DailySelfTests::zeroReward);
		SelfTests.register(F, "persistence-sanitize", DailySelfTests::persistence);
		SelfTests.register(F, "injectable-clock", DailySelfTests::injectableClock);
		SelfTests.register(F, "reminder-text", DailySelfTests::reminder);
		SelfTests.register(F, "admin-shift-setstreak-reset", DailySelfTests::admin);
	}

	/** Local Zurich time, e.g. {@code "2026-06-10T12:00"}. */
	private static long at(String localDateTime) {
		return LocalDateTime.parse(localDateTime).atZone(TimeUtil.zone()).toInstant().toEpochMilli();
	}

	private static UUID player(TestContext ctx, String name) {
		FakePlayer p = ctx.player(name);
		UUID id = p.getUUID();
		EconomyService.ensureAccount(id, name);
		DailyService.reset(id);
		return id;
	}

	private static Claim claimOk(UUID id, long t, DailyConfig cfg, int expectedDay, String msg) {
		Claim c = DailyService.claim(id, t, cfg);
		eq(Outcome.OK, c.outcome(), msg + ": result");
		eq(expectedDay, c.day(), msg + ": day");
		return c;
	}

	private static void configValid(DailyConfig c, String msg) {
		DailyConfig.validate(c);
		check(true, msg);
	}

	private static void configInvalid(Consumer<DailyConfig> change, String msg) {
		DailyConfig c = new DailyConfig();
		change.accept(c);
		assertThrows(IllegalArgumentException.class, () -> DailyConfig.validate(c), msg);
	}

	private static void configDefaults(TestContext ctx) {
		DailyConfig c = new DailyConfig();
		configValid(c, "defaults valid");
		eq(7, c.rewardsCents.length, "7 days");
		eq(25_000L, c.reward(1), "day 1 = $250");
		eq(250_000L, c.reward(7), "day 7 = $2,500");
		for (int d = 2; d <= 7; d++) isTrue(c.reward(d) > c.reward(d - 1), "reward rises on day " + d);
		eq(0L, c.reward(0), "there is no day 0");
		eq(0L, c.reward(8), "there is no day 8");
		eq(CrateKeys.RARE, c.day7KeyTier, "day 7 = rare key");
		eq(1, c.keysFor(7), "1 key on day 7");
		for (int d = 1; d <= 6; d++) eq(0, c.keysFor(d), "no key on day " + d);
		eq(48 * H, c.breakMs(), "streak breaks after 48 h");
		DailyConfig.validate(DailyConfig.get());
		ctx.detail("defaults $250 to $2,500, day 7 +1 rare, 48 h; active daily.json valid");
	}

	private static void configValidation(TestContext ctx) {
		configInvalid(c -> c.rewardsCents = new long[] {1, 2, 3, 4, 5, 6}, "6 rewards");
		configInvalid(c -> c.rewardsCents = new long[] {1, 2, 3, 4, 5, 6, 7, 8}, "8 rewards");
		configInvalid(c -> c.rewardsCents = null, "no rewards");
		configInvalid(c -> c.rewardsCents[0] = -1, "negative reward");
		configInvalid(c -> c.rewardsCents[6] = Money.MAX + 1, "reward above MAX");
		configInvalid(c -> c.rewardsCents[3] = 1, "falling reward");
		configInvalid(c -> c.day7KeyTier = "legendary", "unknown crate");
		configInvalid(c -> c.day7KeyTier = null, "crate null");
		configInvalid(c -> c.day7Keys = -1, "negative keys");
		configInvalid(c -> c.day7Keys = 65, "too many keys");
		configInvalid(c -> c.streakBreakHours = 24, "streak limit 24 h (too short)");
		configInvalid(c -> c.streakBreakHours = 721, "streak limit too long");
		configInvalid(c -> c.reminderDelaySeconds = -1, "negative delay");
		configInvalid(c -> {
			c.rewardsCents = new long[7];
			c.day7Keys = 0;
		}, "no reward at all");
		DailyConfig ok = new DailyConfig();
		ok.rewardsCents = new long[] {100, 100, 100, 100, 100, 100, 100};
		ok.day7KeyTier = CrateKeys.EPIC;
		ok.streakBreakHours = 25;
		configValid(ok, "constant rewards + epic + 25 h");
		ctx.detail("14 invalid configs rejected");
	}

	private static void cycleDay(TestContext ctx) {
		eq(0, DailyLogic.cycleDay(0), "streak 0");
		eq(0, DailyLogic.cycleDay(-3), "streak negative");
		int[][] cases = {{1, 1}, {2, 2}, {7, 7}, {8, 1}, {13, 6}, {14, 7}, {15, 1}, {700, 7}, {701, 1}};
		for (int[] c : cases) eq(c[1], DailyLogic.cycleDay(c[0]), "streak " + c[0]);
		int big = DailyLogic.cycleDay(Integer.MAX_VALUE);
		isTrue(big >= 1 && big <= 7, "streak MAX in range 1-7");
		ctx.detail("days 1-7 cycle");
	}

	private static void dayStates(TestContext ctx) {
		long t = at("2026-06-10T12:00");
		long breakMs = 48 * H;
		Status fresh = DailyLogic.status(new DailyState(), t, breakMs);
		eq(DayState.AVAILABLE, fresh.dayState(1), "new: day 1 claimable");
		for (int d = 2; d <= 7; d++) eq(DayState.LOCKED, fresh.dayState(d), "new: day " + d + " locked");

		DailyState s = new DailyState();
		s.lastClaimMs = t - H;
		s.streak = 3;
		Status today = DailyLogic.status(s, t, breakMs);
		isFalse(today.claimable(), "day 3 claimed today");
		for (int d = 1; d <= 3; d++) eq(DayState.CLAIMED, today.dayState(d), "day " + d + " ticked");
		eq(DayState.TOMORROW, today.dayState(4), "day 4 tomorrow");
		eq(DayState.LOCKED, today.dayState(5), "day 5 locked");

		s.lastClaimMs = t - DAY;
		Status next = DailyLogic.status(s, t, breakMs);
		eq(DayState.AVAILABLE, next.dayState(4), "next day: day 4 claimable");
		eq(DayState.CLAIMED, next.dayState(3), "next day: day 3 ticked");

		s.streak = 7;
		s.lastClaimMs = t - H;
		Status full = DailyLogic.status(s, t, breakMs);
		for (int d = 1; d <= 7; d++) eq(DayState.CLAIMED, full.dayState(d), "week full: day " + d);
		s.lastClaimMs = t - DAY;
		Status newWeek = DailyLogic.status(s, t, breakMs);
		eq(1, newWeek.nextDay(), "after day 7 back to day 1");
		eq(8, newWeek.nextStreak(), "streak keeps running");
		eq(DayState.AVAILABLE, newWeek.dayState(1), "new week: day 1 claimable");
		eq(DayState.LOCKED, newWeek.dayState(7), "new week: day 7 locked");

		s.streak = 5;
		s.lastClaimMs = t - 3 * DAY;
		Status broken = DailyLogic.status(s, t, breakMs);
		isTrue(broken.broken() && broken.hadStreak(), "broken");
		eq(0, broken.streak(), "broken streak counts 0");
		eq(DayState.AVAILABLE, broken.dayState(1), "broken: day 1 claimable");
		eq(DayState.LOCKED, broken.dayState(6), "broken: day 6 locked");
		eq(0L, broken.streakDeadlineMs(), "broken: no deadline");
		ctx.detail("new, today, tomorrow, full week, new week, broken");
	}

	private static void overflowGuards(TestContext ctx) {
		long t = at("2026-06-10T12:00");
		DailyState s = new DailyState();
		s.lastClaimMs = t - DAY;
		s.streak = Integer.MAX_VALUE;
		s.totalClaims = Long.MAX_VALUE;
		s.totalEarned = Long.MAX_VALUE - 5;
		Status st = DailyLogic.status(s, t, 48 * H);
		eq(Integer.MAX_VALUE, st.nextStreak(), "streak does not overflow");
		isTrue(st.nextDay() >= 1 && st.nextDay() <= 7, "day in range");
		DailyState n = DailyLogic.applyClaim(s, st, t, 1_000);
		eq(Long.MAX_VALUE, n.totalClaims, "claims saturated");
		eq(Long.MAX_VALUE, n.totalEarned, "earnings saturated");
		eq(Integer.MAX_VALUE, n.bestStreak, "best streak");
		eq(Long.MAX_VALUE, Money.saturatedAdd(Long.MAX_VALUE, 1), "saturatedAdd");
		eq(t - DAY, s.lastClaimMs, "applyClaim leaves the original alone");
		ctx.detail("no overflows");
	}

	private static void firstClaim(TestContext ctx) {
		DailyConfig cfg = new DailyConfig();
		UUID id = player(ctx, "DailyA");
		long t = at("2026-06-10T12:00");
		Status s0 = DailyService.status(id, t, cfg);
		isTrue(s0.claimable() && !s0.hadStreak(), "new: claimable");
		eq(1, s0.nextDay(), "new: day 1");
		long bal0 = EconomyService.balance(id);

		Claim c = claimOk(id, t, cfg, 1, "first claim");
		eq(1, c.streak(), "streak 1");
		eq(25_000L, c.money(), "money day 1");
		eq(0, c.keys(), "no key");
		isFalse(c.streakLost(), "no lost streak");
		eq(bal0 + 25_000, EconomyService.balance(id), "balance +$250");

		eq(Outcome.ALREADY_CLAIMED, DailyService.claim(id, t + 1_000, cfg).outcome(), "second claim right after");
		eq(Outcome.ALREADY_CLAIMED, DailyService.claim(id, at("2026-06-10T23:59:59.999"), cfg).outcome(), "second claim 23:59:59");
		eq(bal0 + 25_000, EconomyService.balance(id), "no second money");

		Status s1 = DailyService.status(id, t + H, cfg);
		isFalse(s1.claimable(), "not claimable");
		eq(at("2026-06-11T00:00"), s1.nextClaimAtMs(), "again from midnight");
		eq(t + 48 * H, s1.streakDeadlineMs(), "deadline 48 h");
		DailyState st = DailyService.state(id);
		eq(t, st.lastClaimMs, "timestamp saved");
		eq(1L, st.totalClaims, "1 claim");
		eq(25_000L, st.totalEarned, "earnings saved");
		ctx.detail("once per day, +$250, deadline until " + DailyService.formatDeadline(s1.streakDeadlineMs()));
	}

	private static void midnightBoundary(TestContext ctx) {
		DailyConfig cfg = new DailyConfig();
		UUID id = player(ctx, "DailyB");
		claimOk(id, at("2026-06-10T23:59:59.999"), cfg, 1, "23:59:59.999");
		Claim c = claimOk(id, at("2026-06-11T00:00"), cfg, 2, "00:00 the next day");
		eq(2, c.streak(), "streak 2 after 1 ms");
		eq(Outcome.ALREADY_CLAIMED, DailyService.claim(id, at("2026-06-11T23:59:59.999"), cfg).outcome(), "same day 23:59:59 not again");
		claimOk(id, at("2026-06-12T00:00:00.001"), cfg, 3, "next day");
		ctx.detail("day rolls over exactly at midnight (server time zone)");
	}

	private static void streak48h(TestContext ctx) {
		DailyConfig cfg = new DailyConfig();
		UUID id = player(ctx, "DailyC");
		long t = at("2026-06-08T10:00");
		claimOk(id, t, cfg, 1, "Mon 10:00");
		Claim c = claimOk(id, t + 48 * H, cfg, 2, "Wed 10:00 (exactly 48 h, Tue skipped)");
		isFalse(c.streakLost(), "exactly 48 h holds");
		eq(2, c.streak(), "streak 2");
		Claim lost = claimOk(id, t + 96 * H + 1, cfg, 1, "Fri 10:00:00.001 (48 h + 1 ms)");
		isTrue(lost.streakLost(), "48 h + 1 ms breaks");
		eq(1, lost.streak(), "restart at 1");
		eq(2, DailyService.state(id).bestStreak, "best streak stays 2");
		ctx.detail("48 h holds, 48 h + 1 ms breaks");
	}

	private static void fullWeek(TestContext ctx) {
		DailyConfig cfg = new DailyConfig();
		UUID id = player(ctx, "DailyD");
		long bal0 = EconomyService.balance(id);
		long t0 = at("2026-06-01T09:00");
		long sum = 0;
		for (int i = 0; i < 7; i++) {
			int day = i + 1;
			if (day == 7) eq(0L, CrateKeys.count(id, CrateKeys.RARE), "no key before day 7");
			Claim c = claimOk(id, t0 + i * DAY + i * 60_000L, cfg, day, "week 1 day " + day);
			eq(day, c.streak(), "streak " + day);
			eq(cfg.reward(day), c.money(), "money day " + day);
			eq(day == 7 ? 1 : 0, c.keys(), "key day " + day);
			sum += cfg.reward(day);
		}
		eq(bal0 + sum, EconomyService.balance(id), "sum of week 1");
		eq(850_000L, sum, "week = $8,500");
		eq(1L, CrateKeys.count(id, CrateKeys.RARE), "1 rare key");
		Status full = DailyService.status(id, t0 + 6 * DAY + H, cfg);
		eq(7, full.claimedInCycle(), "all 7 ticked");
		isFalse(full.claimable(), "day 7 claimed today");

		Claim w2 = claimOk(id, t0 + 7 * DAY, cfg, 1, "week 2 starts at day 1");
		eq(8, w2.streak(), "streak 8");
		eq(cfg.reward(1), w2.money(), "day 1 reward again");
		for (int i = 8; i < 14; i++) claimOk(id, t0 + i * DAY, cfg, i - 6, "week 2 day " + (i - 6));
		eq(2L, CrateKeys.count(id, CrateKeys.RARE), "2 rare keys after 14 days");
		eq(0L, CrateKeys.count(id, CrateKeys.COMMON), "no common keys");
		eq(0L, CrateKeys.count(id, CrateKeys.EPIC), "no epic keys");
		eq(14, DailyService.state(id).streak, "streak 14");
		ctx.detail("2 weeks: " + Money.format(EconomyService.balance(id) - bal0) + ", 2 keys");
	}

	private static void streakBreak(TestContext ctx) {
		DailyConfig cfg = new DailyConfig();
		UUID id = player(ctx, "DailyE");
		long t = at("2026-06-01T20:00");
		for (int i = 0; i < 3; i++) claimOk(id, t + i * DAY, cfg, i + 1, "day " + (i + 1));
		long last = t + 2 * DAY;
		Status st = DailyService.status(id, last + 49 * H, cfg);
		isTrue(st.broken() && st.claimable(), "broken after 49 h");
		eq(1, st.nextDay(), "next day 1");
		long bal = EconomyService.balance(id);
		Claim c = claimOk(id, last + 49 * H, cfg, 1, "restart");
		isTrue(c.streakLost(), "streakLost reported");
		eq(cfg.reward(1), c.money(), "reward day 1");
		eq(bal + cfg.reward(1), EconomyService.balance(id), "only day 1 money");
		DailyState s = DailyService.state(id);
		eq(1, s.streak, "streak 1");
		eq(3, s.bestStreak, "best streak 3");
		eq(4L, s.totalClaims, "4 claims");
		ctx.detail("day 3, 49 h pause, day 1");
	}

	private static void dst(TestContext ctx) {
		DailyConfig cfg = new DailyConfig();
		UUID id = player(ctx, "DailyF");
		// DST start 2026-03-29 (02:00 to 03:00): 23-hour day
		long a = at("2026-03-29T00:30");
		claimOk(id, a, cfg, 1, "29.03. 00:30");
		eq(22 * H + 30 * 60_000L, DailyService.status(id, a, cfg).nextClaimAtMs() - a, "until midnight 22.5 h (23-hour day)");
		eq(Outcome.ALREADY_CLAIMED, DailyService.claim(id, at("2026-03-29T23:59"), cfg).outcome(), "03-29 23:59 same day");
		claimOk(id, at("2026-03-30T00:00"), cfg, 2, "30.03. 00:00");

		DailyService.reset(id);
		claimOk(id, at("2026-03-28T23:30"), cfg, 1, "28.03. 23:30");
		Claim c = claimOk(id, at("2026-03-29T23:30"), cfg, 2, "03-29 23:30 (23 h later)");
		eq(2, c.streak(), "streak across the change");

		// DST end 2026-10-25 (03:00 to 02:00): 25-hour day
		DailyService.reset(id);
		long b = at("2026-10-25T00:30");
		claimOk(id, b, cfg, 1, "25.10. 00:30");
		eq(24 * H + 30 * 60_000L, DailyService.status(id, b, cfg).nextClaimAtMs() - b, "until midnight 24.5 h (25-hour day)");
		eq(Outcome.ALREADY_CLAIMED, DailyService.claim(id, b + 24 * H, cfg).outcome(), "24 h later still the same day (23:30)");
		claimOk(id, b + 24 * H + 30 * 60_000L, cfg, 2, "26.10. 00:00");

		// consecutive days across the 25-hour day: 10-24 00:10 to 10-25 23:50 is 48 h 40 min
		DailyService.reset(id);
		claimOk(id, at("2026-10-24T00:10"), cfg, 1, "24.10. 00:10");
		Claim late = claimOk(id, at("2026-10-25T23:50"), cfg, 2, "10-25 23:50 (48 h 40 min later)");
		isFalse(late.streakLost(), "streak holds across the 25-hour day");
		ctx.detail("23-hour and 25-hour day correct");
	}

	private static void clockBackwards(TestContext ctx) {
		DailyConfig cfg = new DailyConfig();
		UUID id = player(ctx, "DailyG");
		long t = at("2026-06-15T12:00");
		claimOk(id, t, cfg, 1, "claim");
		long bal = EconomyService.balance(id);
		Status past = DailyService.status(id, t - 5 * DAY, cfg);
		isFalse(past.claimable(), "clock 5 days back: not claimable");
		isFalse(past.broken(), "clock back: not broken");
		eq(Outcome.ALREADY_CLAIMED, DailyService.claim(id, t - 5 * DAY, cfg).outcome(), "no claim in the past");
		eq(Outcome.ALREADY_CLAIMED, DailyService.claim(id, t - DAY, cfg).outcome(), "no claim on the previous day");
		eq(bal, EconomyService.balance(id), "no money");
		claimOk(id, t + DAY, cfg, 2, "next day normal");
		ctx.detail("clock set back gives no extra claim");
	}

	private static void moneyLimit(TestContext ctx) {
		DailyConfig cfg = new DailyConfig();
		UUID id = player(ctx, "DailyH");
		long t = at("2026-06-20T12:00");
		EconomyService.set(id, Money.MAX);
		Claim c = DailyService.claim(id, t, cfg);
		eq(Outcome.MONEY_FAILED, c.outcome(), "balance limit");
		eq(EconomyService.Result.LIMIT_EXCEEDED, c.error(), "error LIMIT_EXCEEDED");
		eq(Money.MAX, EconomyService.balance(id), "balance unchanged");
		eq(0L, DailyService.state(id).lastClaimMs, "no claim record");
		isTrue(DailyService.status(id, t, cfg).claimable(), "still claimable");

		DailyService.setStreak(id, 6, t, cfg);
		eq(7, DailyService.status(id, t, cfg).nextDay(), "day 7 next");
		eq(Outcome.MONEY_FAILED, DailyService.claim(id, t, cfg).outcome(), "day 7 at the limit");
		eq(0L, CrateKeys.count(id, CrateKeys.RARE), "no key without money");
		eq(6, DailyService.state(id).streak, "streak unchanged at 6");

		EconomyService.set(id, 0);
		Claim ok = claimOk(id, t, cfg, 7, "after room in the account");
		eq(1, ok.keys(), "key now");
		eq(1L, CrateKeys.count(id, CrateKeys.RARE), "1 key");
		eq(cfg.reward(7), EconomyService.balance(id), "money day 7");
		ctx.detail("limit: no money, no record, no key");
	}

	private static void zeroReward(TestContext ctx) {
		DailyConfig cfg = new DailyConfig();
		cfg.rewardsCents = new long[] {0, 0, 100, 200, 300, 400, 500};
		DailyConfig.validate(cfg);
		UUID id = player(ctx, "DailyI");
		long bal = EconomyService.balance(id);
		long t = at("2026-06-20T12:00");
		Claim c = claimOk(id, t, cfg, 1, "day 1 without money");
		eq(0L, c.money(), "0 money");
		eq(bal, EconomyService.balance(id), "balance same");
		eq(1, DailyService.state(id).streak, "streak counts anyway");
		claimOk(id, t + DAY, cfg, 2, "day 2");
		claimOk(id, t + 2 * DAY, cfg, 3, "day 3");
		eq(bal + 100, EconomyService.balance(id), "day 3 pays $1");
		ctx.detail("days without money count for the streak");
	}

	private static void persistence(TestContext ctx) {
		UUID id = player(ctx, "DailyJ");
		DailyState s = new DailyState();
		s.lastClaimMs = 1_700_000_000_000L;
		s.streak = 4;
		s.bestStreak = 9;
		s.totalClaims = 12;
		s.totalEarned = 99;
		DailyService.save(id, s);
		DailyState r = DailyService.state(id);
		eq(s.lastClaimMs, r.lastClaimMs, "lastClaimMs");
		eq(4, r.streak, "streak");
		eq(9, r.bestStreak, "bestStreak");
		eq(12L, r.totalClaims, "totalClaims");
		eq(99L, r.totalEarned, "totalEarned");

		JsonObject bad = new JsonObject();
		bad.addProperty("lastClaimMs", -5);
		bad.addProperty("streak", -3);
		bad.addProperty("bestStreak", 2);
		bad.addProperty("totalEarned", -1);
		PlayerDataService.setBlob(id, DailyService.STATE_KEY, bad);
		DailyState fixed = DailyService.state(id);
		eq(0L, fixed.lastClaimMs, "negative timestamp: 0");
		eq(0, fixed.streak, "negative streak: 0");
		eq(2, fixed.bestStreak, "best streak stays");
		eq(0L, fixed.totalEarned, "negative earnings: 0");
		isTrue(DailyService.status(id, System.currentTimeMillis(), new DailyConfig()).claimable(), "repaired state claimable");

		PlayerDataService.setString(id, DailyService.STATE_KEY, "broken");
		DailyState junk = DailyService.state(id);
		eq(0L, junk.lastClaimMs, "unreadable blob: empty");

		DailyService.reset(id);
		check(PlayerDataService.getBlob(id, DailyService.STATE_KEY, JsonObject.class) == null, "reset removes the blob");
		DailyService.save(id, new DailyState());
		check(PlayerDataService.getBlob(id, DailyService.STATE_KEY, JsonObject.class) == null, "empty state is not saved");
		ctx.detail("round trip, repair, broken blob, reset");
	}

	private static void injectableClock(TestContext ctx) {
		UUID id = player(ctx, "DailyK");
		long fixed = at("2026-07-01T08:00");
		long[] now = {fixed};
		ctx.cleanup(DailyService.useClock(() -> now[0]));
		eq(fixed, DailyService.now(), "clock replaced");
		DailyConfig live = DailyConfig.get();
		long bal = EconomyService.balance(id);
		Claim c = DailyService.claim(id);
		eq(Outcome.OK, c.outcome(), "claim with injected clock");
		eq(fixed, DailyService.state(id).lastClaimMs, "timestamp from the clock");
		eq(bal + live.reward(1), EconomyService.balance(id), "money per active config");
		isFalse(DailyService.status(id).claimable(), "same day");
		now[0] = fixed + DAY;
		isTrue(DailyService.status(id).claimable(), "clock +1 day: claimable");
		eq(2, DailyService.status(id).nextDay(), "day 2");
		ctx.detail("clock replaceable, return value restores it");
	}

	private static void reminder(TestContext ctx) {
		DailyConfig cfg = new DailyConfig();
		UUID id = player(ctx, "DailyL");
		long t = at("2026-06-10T09:00");
		String fresh = DailyService.reminderText(id, t, cfg).orElse(null);
		check(fresh != null && fresh.contains("Day 1") && fresh.contains("$250.00"), "reminder new: " + fresh);
		eq("Wed 10 Jun, 09:00", DailyService.formatDeadline(t), "deadline in English");
		claimOk(id, t, cfg, 1, "claim");
		isFalse(DailyService.reminderText(id, t + H, cfg).isPresent(), "no reminder after claiming");
		String next = DailyService.reminderText(id, t + DAY, cfg).orElse("");
		isTrue(next.contains("Day 2") && next.contains("expires in"), "reminder day 2 with deadline: " + next);
		String lost = DailyService.reminderText(id, t + 49 * H, cfg).orElse("");
		isTrue(lost.contains("Day 1") && lost.contains("streak was lost"), "reminder after break: " + lost);
		DailyService.reset(id);
		DailyService.setStreak(id, 6, t, cfg);
		String keyDay = DailyService.reminderText(id, t, cfg).orElse("");
		isTrue(keyDay.contains("Day 7") && keyDay.contains(CrateKeys.label(CrateKeys.RARE) + " Key") && keyDay.contains("$2,500.00"), "reminder day 7: " + keyDay);
		isTrue(Settings.def(DailyFeature.SETTING_REMINDER).map(Settings.Def::defaultValue).orElse(false), "switch daily.reminder registered, default on");
		isTrue(Settings.get(id, DailyFeature.SETTING_REMINDER), "switch on without entry");
		Settings.set(id, DailyFeature.SETTING_REMINDER, false);
		isFalse(Settings.get(id, DailyFeature.SETTING_REMINDER), "switch off saved");
		ctx.detail("text only when claimable; deadline, break, key; switch daily.reminder");
	}

	private static void admin(TestContext ctx) {
		DailyConfig cfg = new DailyConfig();
		UUID id = player(ctx, "DailyM");
		long t = at("2026-06-10T15:00");
		isFalse(DailyService.shift(id, 24), "shift without a claim");
		claimOk(id, t, cfg, 1, "claim");
		isTrue(DailyService.shift(id, 24), "shift 24 h");
		eq(t - DAY, DailyService.state(id).lastClaimMs, "timestamp shifted");
		Status st = DailyService.status(id, t, cfg);
		isTrue(st.claimable() && !st.broken(), "claimable after shift 24 h, streak holds");
		eq(2, st.nextDay(), "day 2");
		DailyService.shift(id, 25);
		isTrue(DailyService.status(id, t, cfg).broken(), "broken after 49 h");
		long bal = EconomyService.balance(id);
		DailyService.setStreak(id, 10, t, cfg);
		eq(bal, EconomyService.balance(id), "setstreak creates no money");
		Status s10 = DailyService.status(id, t, cfg);
		isTrue(s10.claimable() && !s10.broken(), "setstreak: claimable, streak running");
		eq(11, s10.nextStreak(), "next streak 11");
		eq(4, s10.nextDay(), "day 4");
		claimOk(id, t, cfg, 4, "claim after setstreak");
		DailyService.setStreak(id, 3, t, cfg);
		Status s3 = DailyService.status(id, t, cfg);
		isFalse(s3.claimable(), "setstreak on claim day: stays claimed");
		eq(3, s3.streak(), "streak 3");
		DailyService.reset(id);
		Status r = DailyService.status(id, t, cfg);
		isTrue(r.claimable() && !r.hadStreak(), "reset: as new");
		ctx.detail("shift, setstreak, reset");
	}
}
