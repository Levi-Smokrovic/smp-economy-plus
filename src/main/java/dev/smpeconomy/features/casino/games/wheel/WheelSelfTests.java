package dev.smpeconomy.features.casino.games.wheel;

import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.entity.FakePlayer;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Treasury;
import dev.smpeconomy.core.test.Assert;
import dev.smpeconomy.core.test.SelfTests;
import dev.smpeconomy.core.test.TestContext;
import dev.smpeconomy.features.casino.Casino;
import dev.smpeconomy.features.casino.CasinoConfig;
import dev.smpeconomy.features.casino.CasinoData;
import dev.smpeconomy.features.casino.CasinoData.GameStats;
import dev.smpeconomy.features.casino.CasinoData.PlayerStats;
import dev.smpeconomy.features.casino.CasinoData.Round;

/**
 * Selftests of the wheel (part of {@code /smpeco selftest casino}): odds/RTP incl. a 1,000,000-spin simulation, animation
 * math, decided-before-animation + exactly-once settlement, restart recovery, admission guards, English refusals and money
 * conservation.
 */
final class WheelSelfTests {
	private static final String F = "casino-wheel";
	private static final int SIM_SPINS = 1_000_000;
	/** Player messages are plain English: no accented letters. */
	private static final Pattern NON_ENGLISH = Pattern.compile("[\\u00C0-\\u00D6\\u00D8-\\u00F6\\u00F8-\\u00FF]");

	private WheelSelfTests() {}

	static void register() {
		SelfTests.register(F, "wheel-rtp", WheelSelfTests::rtp);
		SelfTests.register(F, "wheel-animation", WheelSelfTests::animation);
		SelfTests.register(F, "wheel-exactly-once", WheelSelfTests::exactlyOnce);
		SelfTests.register(F, "wheel-crash-recovery", WheelSelfTests::crashRecovery);
		SelfTests.register(F, "wheel-guards", WheelSelfTests::guards);
		SelfTests.register(F, "wheel-english", WheelSelfTests::english);
		SelfTests.register(F, "wheel-conservation", WheelSelfTests::conservation);
	}

	/** RNG whose nextInt(bound) always returns {@code roll} (clamped), so it lands on a chosen segment. */
	static RandomGenerator fixed(int roll) {
		return new RandomGenerator() {
			@Override
			public long nextLong() {
				return roll;
			}

			@Override
			public int nextInt(int bound) {
				return Math.min(Math.max(0, roll), bound - 1);
			}
		};
	}

	static RandomGenerator landOn(int segment) {
		return fixed(WheelMath.firstRoll(segment));
	}

	/** Casino config fields the wheel reads, set to defaults and restored after the test (plus the Treasury). */
	private static CasinoConfig setup(TestContext ctx) {
		ctx.preserveTreasury();
		CasinoConfig live = Casino.cfg();
		CasinoConfig saved = new CasinoConfig();
		copy(live, saved);
		ctx.cleanup(() -> copy(saved, live));
		copy(new CasinoConfig(), live);
		return live;
	}

	private static void copy(CasinoConfig from, CasinoConfig to) {
		to.enabled = from.enabled;
		to.minBet = from.minBet;
		to.maxBet = from.maxBet;
		to.maxBetTreasuryPercent = from.maxBetTreasuryPercent;
		to.maxWinTreasuryPercent = from.maxWinTreasuryPercent;
		to.dailyLossLimit = from.dailyLossLimit;
	}

	/** Treasury so that exactly {@code free} is left after other open rounds' reservations. */
	private static void setTreasuryFree(long free) {
		EconomyService.set(Treasury.ID, Math.addExact(free, Casino.reservedTotal(null)));
	}

	private static FakePlayer funded(TestContext ctx, String name, long cents) {
		FakePlayer p = ctx.player(name);
		EconomyService.set(p.getUUID(), cents);
		return p;
	}

	private static String name(FakePlayer p) {
		return p.getGameProfile().name();
	}

	private static String fmt(double d) {
		return String.format(java.util.Locale.ROOT, "%.4f", d);
	}

	private static void rtp(TestContext ctx) {
		Assert.eq(20, WheelMath.TENTHS.length, "20 segments");
		Assert.eq(WheelMath.TOTAL_WEIGHT, WheelMath.totalWeight(), "weights add up to 10,000");
		Assert.eq(500, WheelMath.maxTenths(), "jackpot 50×");
		int[] tiers = WheelMath.TIERS;
		long[] tierWeight = {20, 100, 200, 400, 750, 1_000, 2_000, 5_530}; // 50×, 10×, 5×, 3×, 2×, 1.5×, 1.2×, 0×
		for (int i = 0; i < tiers.length; i++) {
			Assert.eq(tierWeight[i], Math.round(WheelMath.chance(tiers[i]) * WheelMath.TOTAL_WEIGHT), "chance of " + WheelMath.label(tiers[i]));
		}
		for (int s = 0; s < WheelMath.SEGMENTS; s++) {
			Assert.eq(WheelMath.weightPerSegment(WheelMath.tenths(s)), WheelMath.weight(s), "segment " + s + ": same weight as its multiplier");
			int first = WheelMath.firstRoll(s);
			Assert.eq(s, WheelMath.spin(fixed(first)), "first roll of segment " + s);
			Assert.eq(s, WheelMath.spin(fixed(first + WheelMath.weight(s) - 1)), "last roll of segment " + s);
		}
		Assert.eq(96_000L, WheelMath.rtpNumerator(), "RTP exactly 96 %");
		double theo = WheelMath.theoreticalRtp();
		Assert.check(theo >= 0.95 && theo <= 0.97, "theoretical RTP " + fmt(theo) + " in [0.95, 0.97] (house edge 3–5 %)");

		// simulation through the live draw + payout
		SplittableRandom rng = new SplittableRandom(20261010L);
		long stake = 10_000;
		double sum = 0;
		double sumSq = 0;
		long[] hits = new long[tiers.length];
		for (int i = 0; i < SIM_SPINS; i++) {
			int seg = WheelMath.spin(rng);
			double x = WheelMath.payout(stake, seg) / (double) stake;
			sum += x;
			sumSq += x * x;
			int t = WheelMath.tenths(seg);
			for (int k = 0; k < tiers.length; k++) if (tiers[k] == t) hits[k]++;
		}
		double mean = sum / SIM_SPINS;
		double var = Math.max(0, sumSq / SIM_SPINS - mean * mean);
		double tol = 4 * Math.sqrt(var / SIM_SPINS) + 0.0005;
		Assert.check(Math.abs(mean - theo) <= tol, "simulated RTP " + fmt(mean) + " vs " + fmt(theo) + " (tolerance " + fmt(tol) + ")");
		for (int k = 0; k < tiers.length; k++) {
			double p = WheelMath.chance(tiers[k]);
			double expected = SIM_SPINS * p;
			double sd = Math.sqrt(SIM_SPINS * p * (1 - p));
			Assert.check(Math.abs(hits[k] - expected) <= 4.5 * sd + 1,
				WheelMath.label(tiers[k]) + ": " + hits[k] + " hits, expected " + Math.round(expected) + " ± " + Math.round(4.5 * sd));
		}
		// payouts round down to the cent; potential = 50× stake
		Assert.eq(6L, WheelMath.payoutTenths(5, 12), "1.2× of 5¢ = 6¢");
		Assert.eq(4L, WheelMath.payoutTenths(3, 15), "1.5× of 3¢ = 4¢ (rounded down)");
		Assert.eq(50L, WheelMath.payoutTenths(1, 500), "50× of 1¢");
		Assert.eq(0L, WheelMath.payoutTenths(1_000, 0), "0× = nothing");
		Assert.eq(500_000L, WheelRounds.potential(10_000), "possible win = 50× the bet");
		Assert.eq(Long.MAX_VALUE, WheelRounds.potential(Long.MAX_VALUE), "overflow → never covered");
		ctx.detail("theoretical " + fmt(theo) + ", simulated " + fmt(mean) + " (" + SIM_SPINS + " spins, tolerance " + fmt(tol) + ")");
	}

	private static void animation(TestContext ctx) {
		int maxPerTick = 0;
		for (int from = 0; from < WheelMath.SEGMENTS; from++) {
			for (int target = 0; target < WheelMath.SEGMENTS; target++) {
				int steps = WheelGui.spinSteps(from, target);
				Assert.check(steps >= 2 * WheelMath.SEGMENTS && steps < 3 * WheelMath.SEGMENTS, "2–3 turns (" + steps + ")");
				Assert.eq(target, Math.floorMod(from + steps, WheelMath.SEGMENTS), "stops on the drawn segment " + from + "→" + target);
				int prev = 0;
				for (int t = 0; t <= WheelGui.SPIN_TICKS; t++) {
					int d = WheelGui.stepsDone(steps, t);
					Assert.check(d >= prev && d <= steps, "monotonic at tick " + t);
					maxPerTick = Math.max(maxPerTick, d - prev);
					prev = d;
				}
				Assert.eq(steps, WheelGui.stepsDone(steps, WheelGui.SPIN_TICKS), "complete at the end");
			}
		}
		Assert.check(maxPerTick <= 3, "never more than 3 segments per tick (" + maxPerTick + ")");
		for (int off = 0; off < WheelMath.SEGMENTS; off++) {
			Assert.eq(off, WheelGui.segmentAt(off, 0), "pointer shows the offset segment");
			for (int pos = 0; pos < WheelMath.SEGMENTS - 1; pos++) {
				Assert.eq(WheelGui.segmentAt(off, pos), WheelGui.segmentAt(off + 1, pos + 1), "turning moves segments clockwise");
			}
		}
		Set<Integer> used = new HashSet<>();
		for (int slot : WheelGui.RING) {
			int row = slot / 9;
			int col = slot % 9;
			Assert.check(row <= 4 && col >= 1 && col <= 7 && (row == 0 || row == 4 || col == 1 || col == 7), "ring slot " + slot + " on the rim");
			Assert.isTrue(used.add(slot), "ring slot " + slot + " once");
		}
		Assert.eq(WheelMath.SEGMENTS, used.size(), "one ring slot per segment");
		Assert.eq(4, WheelGui.RING[0], "pointer position top middle");
		for (int slot : new int[] {WheelGui.POINTER, WheelGui.PAYTABLE, WheelGui.HELP, WheelGui.CENTER}) Assert.isFalse(used.contains(slot), "slot " + slot + " free");
		for (int slot : WheelGui.HISTORY) Assert.isFalse(used.contains(slot), "history slot " + slot + " free");
		Assert.check(WheelGui.SPIN_TICKS * 50L <= WheelGui.SPIN_MAX_MS && WheelGui.SPIN_MAX_MS < 5_000,
			"spin ends before the casino watchdog's 5 s grace");
		ctx.detail("20×20 start/target pairs, ≤ " + maxPerTick + " segments/tick, ring on the rim");
	}

	private static void exactlyOnce(TestContext ctx) {
		setup(ctx);
		FakePlayer a = funded(ctx, "WheelOnce", 10_000_000);
		setTreasuryFree(100_000_000);
		UUID id = a.getUUID();
		long b0 = EconomyService.balance(id);
		long t0 = Treasury.balance();
		// jackpot: decided and stored before anything is shown
		WheelRounds.Start s = WheelRounds.start(id, name(a), 1_000, landOn(0));
		Assert.isTrue(s.ok(), "spin started: " + s.error());
		Round r = s.round();
		Assert.eq(CasinoData.DECIDED, r.state, "decided before the wheel turns");
		Assert.eq(WheelGame.ID, r.game, "game id");
		Assert.eq(0, r.card, "landing segment stored");
		Assert.eq(50_000L, r.payout, "payout stored (50×)");
		Assert.eq(50_000L, r.reserved, "reserves exactly its payout");
		Assert.isTrue(Casino.openRound(id) == r, "round lives in the casino's store");
		Assert.eq(b0 - 1_000, EconomyService.balance(id), "bet collected");
		Assert.eq(t0 + 1_000, Treasury.balance(), "bet went to the Treasury");
		// one round per player (also across casino games)
		WheelRounds.Start second = WheelRounds.start(id, name(a), 1_000, landOn(0));
		Assert.isFalse(second.ok(), "second spin while one is open rejected");
		Assert.eq(b0 - 1_000, EconomyService.balance(id), "rejected: nothing collected");
		// animation end settles; window close / watchdog / restart afterwards do nothing
		Casino.Settled first = Casino.settle(r.id, null);
		Assert.notNull(first, "settled");
		Assert.eq(50_000L, first.payout(), "jackpot paid");
		Assert.isTrue(first.paid() && !first.refund(), "paid, not a refund");
		Assert.isTrue(Casino.settle(r.id, Casino.CLOSED_REASON) == null, "second settle has no effect");
		Assert.isTrue(Casino.openRound(id) == null, "nothing open");
		Assert.eq(b0 - 1_000 + 50_000, EconomyService.balance(id), "paid exactly once");
		Assert.eq(t0 + 1_000 - 50_000, Treasury.balance(), "Treasury paid exactly once");
		// a loss
		WheelRounds.Start l = WheelRounds.start(id, name(a), 2_000, landOn(1));
		Assert.isTrue(l.ok(), "second spin: " + l.error());
		Assert.eq(0L, l.round().payout, "0× decided");
		Assert.eq(0L, l.round().reserved, "a loss reserves nothing");
		Assert.eq(0L, Casino.settle(l.round().id, null).payout(), "0× pays nothing");
		Assert.isTrue(Casino.settle(l.round().id, null) == null, "second settle has no effect");
		Assert.eq(b0 - 3_000 + 50_000, EconomyService.balance(id), "balance after both spins");
		// stats count each spin once, under "wheel"
		PlayerStats st = Casino.peekStats(id);
		Assert.notNull(st, "stats");
		Assert.eq(2L, st.rounds, "2 rounds");
		Assert.eq(3_000L, st.wagered, "wagered");
		Assert.eq(50_000L, st.paidOut, "paid out");
		GameStats g = st.games.get(WheelGame.ID);
		Assert.notNull(g, "per-game stats");
		Assert.eq(2L, g.rounds, "wheel rounds");
		Assert.eq(1L, g.wins, "wheel wins");
		Assert.eq(2_000L, (long) st.lastStake.get(WheelGame.ID), "last bet remembered");
		Assert.eq(3_000L - 50_000L, Casino.netLossToday(id), "daily loss counts the wheel");
		ctx.detail("jackpot + loss each settled once, stats and Treasury exact");
	}

	/** Restart after a crash: the saved (decided) round comes back from JSON and is settled once to its stored payout. */
	private static void crashRecovery(TestContext ctx) {
		setup(ctx);
		FakePlayer a = funded(ctx, "WheelCrash", 10_000_000);
		setTreasuryFree(100_000_000);
		UUID id = a.getUUID();
		Round r = WheelRounds.start(id, name(a), 10_000, landOn(14)).round();
		Assert.notNull(r, "spin started");
		Assert.eq(100, WheelMath.tenths(14), "segment 14 = 10×");
		Assert.eq(100_000L, r.payout, "10× decided");
		// as on disk: the whole store through Gson and back
		CasinoData disk = new CasinoData();
		disk.rounds.put(r.id, r);
		CasinoData back = DataStore.gson().fromJson(DataStore.gson().toJson(disk), CasinoData.class);
		Round loaded = back.rounds.get(r.id);
		Assert.notNull(loaded, "round survives the round trip");
		Assert.eq(CasinoData.DECIDED, loaded.state, "still decided");
		Assert.eq(14, loaded.card, "segment kept");
		Assert.eq(100_000L, Casino.cashOutValue(loaded), "casino pays the stored payout for a wheel round");
		Assert.isFalse(Casino.isOpen(loaded), "no further move possible");
		// the live round is what the server has after loading casino.json
		Assert.eq(DataStore.gson().toJson(loaded), DataStore.gson().toJson(Casino.round(r.id)), "loaded round = stored round");
		long bal = EconomyService.balance(id);
		Casino.Settled s = Casino.settle(r.id, "server restart");
		Assert.notNull(s, "settled at start-up");
		Assert.eq(100_000L, s.payout(), "stored payout");
		Assert.isTrue(Casino.settle(r.id, "server restart") == null, "not twice");
		Assert.eq(bal + 100_000, EconomyService.balance(id), "paid once after the restart");
		Assert.eq(1L, Casino.peekStats(id).rounds, "counted once");
		ctx.detail("decided round restored from JSON, settled once (10×)");
	}

	private static void guards(TestContext ctx) {
		CasinoConfig c = setup(ctx);
		FakePlayer a = funded(ctx, "WheelGuard", 100_000_000);
		UUID id = a.getUUID();
		String n = name(a);
		setTreasuryFree(1_000_000); // $10,000 free
		long maxBet = Casino.maxBet();
		long cover = Casino.coverLimit(null);
		long maxStake = WheelRounds.maxStake(id);
		Assert.eq(Math.min(maxBet, cover / 50), maxStake, "wheel max bet = min(casino max bet, coverage / 50)");
		Assert.check(maxStake >= c.minBet && maxStake < maxBet, "precondition: the 50× jackpot limits the bet (" + maxStake + " < " + maxBet + ")");
		long before = EconomyService.balance(id);
		// above the casino's max bet (2 % of the Treasury): the casino's own message
		WheelRounds.Start over = WheelRounds.start(id, n, maxBet + 1, landOn(0));
		Assert.isFalse(over.ok(), "above the casino max bet rejected");
		Assert.check(over.error().contains(Money.format(maxBet)), "message names the max bet: " + over.error());
		// within the max bet, but the Treasury could not pay the jackpot
		WheelRounds.Start uncovered = WheelRounds.start(id, n, maxStake + 1, landOn(0));
		Assert.isFalse(uncovered.ok(), "jackpot not covered → rejected");
		Assert.check(uncovered.error().contains(Money.format(maxStake)), "message names the wheel's max bet: " + uncovered.error());
		Assert.eq(before, EconomyService.balance(id), "rejected: nothing collected");
		Assert.isTrue(Casino.openRound(id) == null, "rejected: no round");
		// exactly the max: admitted, the jackpot is within the max win
		WheelRounds.Start ok = WheelRounds.start(id, n, maxStake, landOn(0));
		Assert.isTrue(ok.ok(), "max bet admitted: " + ok.error());
		Assert.eq(WheelRounds.potential(maxStake), ok.round().payout, "jackpot = potential");
		Assert.check(ok.round().payout <= Casino.maxWin(), "jackpot ≤ max win per round");
		Casino.settle(ok.round().id, null);
		// minimum bet, balance
		setTreasuryFree(100_000_000);
		Assert.isFalse(WheelRounds.start(id, n, c.minBet - 1, landOn(0)).ok(), "below the min bet rejected");
		EconomyService.set(id, 500);
		WheelRounds.Start poor = WheelRounds.start(id, n, 1_000, landOn(0));
		Assert.isFalse(poor.ok(), "not enough money rejected");
		Assert.check(poor.error().contains("Not enough money"), "message: " + poor.error());
		Assert.eq(500L, EconomyService.balance(id), "nothing collected");
		EconomyService.set(id, 100_000_000);
		// daily loss limit
		Casino.resetLimits(id);
		c.dailyLossLimit = 50_000; // $500
		Round l1 = WheelRounds.start(id, n, 30_000, landOn(1)).round();
		Assert.notNull(l1, "first losing spin");
		Casino.settle(l1.id, null);
		Assert.eq(20_000L, Casino.lossRoom(id), "$200 loss room left");
		Assert.eq(20_000L, WheelRounds.maxStake(id), "max bet = remaining loss room");
		long b1 = EconomyService.balance(id);
		WheelRounds.Start overLimit = WheelRounds.start(id, n, 30_000, landOn(1));
		Assert.isFalse(overLimit.ok(), "above the remaining loss room rejected");
		Assert.check(overLimit.error().contains(Money.format(20_000)), "message names the room left: " + overLimit.error());
		Assert.eq(b1, EconomyService.balance(id), "nothing collected");
		Round l2 = WheelRounds.start(id, n, 20_000, landOn(1)).round();
		Assert.notNull(l2, "exactly the room left admitted");
		Casino.settle(l2.id, null);
		Assert.eq(0L, WheelRounds.maxStake(id), "limit reached → locked");
		Assert.isFalse(WheelRounds.start(id, n, c.minBet, landOn(1)).ok(), "limit reached → rejected");
		Casino.resetLimits(id);
		c.dailyLossLimit = new CasinoConfig().dailyLossLimit;
		// casino closed, empty Treasury
		c.enabled = false;
		Assert.isFalse(WheelRounds.start(id, n, 1_000, landOn(0)).ok(), "casino closed → rejected");
		c.enabled = true;
		EconomyService.set(Treasury.ID, 0);
		Assert.eq(0L, WheelRounds.maxStake(id), "empty Treasury → locked");
		Assert.isFalse(WheelRounds.start(id, n, 1_000, landOn(0)).ok(), "empty Treasury → rejected");
		Assert.isTrue(Casino.openRound(id) == null, "no round left open");
		ctx.detail("casino max bet, jackpot coverage (max " + Money.format(maxStake) + "), min bet, balance, loss limit, closed, empty Treasury");
	}

	private static void assertEnglish(String message, String what) {
		Assert.notNull(message, what + ": message");
		String plain = message.replaceAll("§.", "");
		Assert.isFalse(NON_ENGLISH.matcher(plain).find(), what + ": English (" + plain + ")");
	}

	/** A refused spin whose message is English and starts with {@code expected}. */
	private static void refusedInEnglish(WheelRounds.Start s, String expected, String what) {
		Assert.isFalse(s.ok(), what + ": refused");
		assertEnglish(s.error(), what);
		Assert.check(s.error().startsWith(expected), what + ": \"" + s.error() + "\" starts with \"" + expected + "\"");
	}

	/** Every refusal the casino core can give a wheel spin (shared casino interface) reaches the player in English. */
	private static void english(TestContext ctx) {
		CasinoConfig c = setup(ctx);
		FakePlayer a = funded(ctx, "WheelEnglish", 100_000_000);
		UUID id = a.getUUID();
		String n = name(a);
		setTreasuryFree(100_000_000);
		refusedInEnglish(WheelRounds.start(id, n, c.minBet - 1, landOn(0)), "Minimum bet: " + Msg.moneyStr(c.minBet), "below the min bet");
		long maxBet = Casino.maxBet();
		refusedInEnglish(WheelRounds.start(id, n, maxBet + 1, landOn(0)), "Maximum bet: " + Msg.moneyStr(maxBet) + " §c(at most ", "above the max bet");
		Round open = WheelRounds.start(id, n, c.minBet, landOn(1)).round();
		Assert.notNull(open, "first spin");
		refusedInEnglish(WheelRounds.start(id, n, c.minBet, landOn(1)), "You're already playing", "round already open");
		Casino.settle(open.id, null);
		// coverage: the 50× jackpot of a bet within the max bet is not covered
		setTreasuryFree(1_000_000); // $10,000 free
		maxBet = Casino.maxBet();
		long wheelMax = WheelRounds.maxStake(id);
		Assert.check(wheelMax >= c.minBet && wheelMax < maxBet, "precondition: the jackpot limits the bet (" + wheelMax + " < " + maxBet + ")");
		WheelRounds.Start uncovered = WheelRounds.start(id, n, wheelMax + 1, landOn(0));
		refusedInEnglish(uncovered, "The Treasury can't cover a possible win of ", "coverage refusal");
		Assert.check(uncovered.error().contains("Max bet: "), "coverage refusal names the max bet: " + uncovered.error());
		// coverage, game locked: even the min bet's jackpot is not covered
		long minBet = c.minBet;
		c.minBet = maxBet;
		WheelRounds.Start locked = WheelRounds.start(id, n, maxBet, landOn(0));
		refusedInEnglish(locked, "The Treasury can't cover a possible win of ", "coverage refusal, game locked");
		Assert.check(locked.error().endsWith("Game locked."), "coverage refusal, game locked: " + locked.error());
		c.minBet = minBet;
		setTreasuryFree(100_000_000);
		// daily loss limit: room left, then none
		Casino.resetLimits(id);
		c.dailyLossLimit = 50_000;
		Round l1 = WheelRounds.start(id, n, 30_000, landOn(1)).round();
		Assert.notNull(l1, "losing spin");
		Casino.settle(l1.id, null);
		refusedInEnglish(WheelRounds.start(id, n, 30_000, landOn(1)), "Daily loss limit reached (" + Msg.moneyStr(50_000) + "§c). You can bet up to "
			+ Msg.moneyStr(20_000), "above the loss room");
		Round l2 = WheelRounds.start(id, n, 20_000, landOn(1)).round();
		Assert.notNull(l2, "spin up to the limit");
		Casino.settle(l2.id, null);
		refusedInEnglish(WheelRounds.start(id, n, c.minBet, landOn(1)), "Daily loss limit reached (", "limit used up");
		Assert.check(WheelRounds.start(id, n, c.minBet, landOn(1)).error().endsWith("Come back tomorrow."), "limit used up: come back tomorrow");
		Casino.resetLimits(id);
		c.dailyLossLimit = new CasinoConfig().dailyLossLimit;
		// closed, Treasury too small
		c.enabled = false;
		refusedInEnglish(WheelRounds.start(id, n, 1_000, landOn(0)), "The casino is closed right now.", "casino closed");
		c.enabled = true;
		EconomyService.set(Treasury.ID, 0);
		refusedInEnglish(WheelRounds.start(id, n, 1_000, landOn(0)), "The casino is locked", "empty Treasury");
		Assert.isTrue(Casino.openRound(id) == null, "no round left open");
		Assert.eq("§6Wheel of Fortune", Casino.gameLabel(WheelGame.ID), "result line and announcement name the wheel");
		ctx.detail("min/max bet, open round, coverage (2 forms), loss limit (2 forms), closed, locked: English; label Wheel of Fortune");
	}

	/** Many random spins: player + Treasury stay constant, the Treasury moves by bets − payouts, nothing stays reserved. */
	private static void conservation(TestContext ctx) {
		CasinoConfig c = setup(ctx);
		c.dailyLossLimit = Money.MAX;
		FakePlayer a = funded(ctx, "WheelCons", 1_000_000_000L);
		setTreasuryFree(1_000_000_000L);
		UUID id = a.getUUID();
		long t0 = Treasury.balance();
		long p0 = EconomyService.balance(id);
		long reserved0 = Casino.reservedTotal(null);
		SplittableRandom rng = new SplittableRandom(4711);
		long staked = 0;
		long paid = 0;
		int spins = 3_000;
		for (int i = 0; i < spins; i++) {
			long stake = c.minBet + rng.nextInt(50_000);
			WheelRounds.Start s = WheelRounds.start(id, name(a), stake, rng);
			Assert.isTrue(s.ok(), "spin " + i + ": " + s.error());
			Round r = s.round();
			Assert.eq(p0 - staked + paid - stake, EconomyService.balance(id), "bet collected (spin " + i + ")");
			Assert.eq(WheelMath.payout(stake, r.card), r.payout, "payout = bet × segment");
			Casino.Settled st = Casino.settle(r.id, null);
			Assert.notNull(st, "settled");
			Assert.isTrue(st.paid() && !st.refund(), "paid");
			Assert.eq(r.payout, st.payout(), "decided payout paid");
			staked += stake;
			paid += st.payout();
			Assert.eq(t0 + p0, Treasury.balance() + EconomyService.balance(id), "player + Treasury constant after spin " + i);
		}
		Assert.eq(t0 + staked - paid, Treasury.balance(), "Treasury = start + bets − payouts");
		Assert.eq(reserved0, Casino.reservedTotal(null), "nothing left reserved");
		Assert.isTrue(Casino.openRound(id) == null, "no open round");
		PlayerStats ps = Casino.peekStats(id);
		Assert.eq((long) spins, ps.rounds, "stats: rounds");
		Assert.eq(staked, ps.wagered, "stats: wagered");
		Assert.eq(paid, ps.paidOut, "stats: paid out");
		ctx.detail(spins + " spins, Treasury " + (staked - paid >= 0 ? "+" : "") + Money.format(staked - paid)
			+ " (RTP here " + fmt(paid / (double) staked) + ")");
	}
}
