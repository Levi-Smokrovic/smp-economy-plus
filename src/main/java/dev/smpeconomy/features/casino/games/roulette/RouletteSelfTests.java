package dev.smpeconomy.features.casino.games.roulette;

import java.util.SplittableRandom;
import java.util.UUID;
import java.util.random.RandomGenerator;
import net.fabricmc.fabric.api.entity.FakePlayer;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Treasury;
import dev.smpeconomy.core.test.Assert;
import dev.smpeconomy.core.test.SelfTests;
import dev.smpeconomy.core.test.TestContext;
import dev.smpeconomy.features.casino.Casino;
import dev.smpeconomy.features.casino.CasinoConfig;
import dev.smpeconomy.features.casino.CasinoData;
import dev.smpeconomy.features.casino.CasinoData.Round;

/**
 * Roulette selftests ({@code /smpeco selftest casino-roulette}, no GUI): wheel, exact payout rate 36/37 × 0.99 (1 %
 * house fee, RTP ≈ 96.3 %) per spot and combination, RTP simulation (200,000 rounds), RTP and money conservation
 * through the real casino logic, exactly-once settlement, admission (max bet, coverage, daily limit, one round,
 * balance, casino off, voided round) and the GUI pre-check.
 */
final class RouletteSelfTests {
	static final String F = "casino-roulette";
	private static final int SIM_ROUNDS = 200_000;
	private static final int ENGINE_ROUNDS = 20_000;

	private RouletteSelfTests() {}

	static void register() {
		SelfTests.register(F, "wheel", RouletteSelfTests::wheel);
		SelfTests.register(F, "payout-exact", RouletteSelfTests::payoutExact);
		SelfTests.register(F, "rtp-simulation", RouletteSelfTests::rtpSimulation);
		SelfTests.register(F, "engine-rtp-conservation", RouletteSelfTests::engineRtp);
		SelfTests.register(F, "exactly-once", RouletteSelfTests::exactlyOnce);
		SelfTests.register(F, "admission-guards", RouletteSelfTests::guards);
		SelfTests.register(F, "voided-round", RouletteSelfTests::voided);
		SelfTests.register(F, "table-precheck", RouletteSelfTests::precheck);
	}

	/** Random source with preset nextInt values, then a fixed seed. */
	static RandomGenerator scripted(int... values) {
		SplittableRandom fallback = new SplittableRandom(37);
		return new RandomGenerator() {
			private int index;

			@Override
			public long nextLong() {
				return fallback.nextLong();
			}

			@Override
			public int nextInt(int bound) {
				if (index < values.length) return Math.floorMod(values[index++], bound);
				return fallback.nextInt(bound);
			}
		};
	}

	/**
	 * Sets casino limits to defaults (only the public fields roulette uses) and restores them afterwards; saves the
	 * Treasury.
	 */
	private static CasinoConfig setup(TestContext ctx) {
		ctx.preserveTreasury();
		CasinoConfig c = Casino.cfg();
		boolean enabled = c.enabled;
		long minBet = c.minBet;
		long maxBet = c.maxBet;
		double maxBetPct = c.maxBetTreasuryPercent;
		double maxWinPct = c.maxWinTreasuryPercent;
		long loss = c.dailyLossLimit;
		long announceProfit = c.announceMinProfit;
		long announcePayout = c.announceMinPayoutForMultiplier;
		ctx.cleanup(() -> {
			c.enabled = enabled;
			c.minBet = minBet;
			c.maxBet = maxBet;
			c.maxBetTreasuryPercent = maxBetPct;
			c.maxWinTreasuryPercent = maxWinPct;
			c.dailyLossLimit = loss;
			c.announceMinProfit = announceProfit;
			c.announceMinPayoutForMultiplier = announcePayout;
		});
		CasinoConfig d = new CasinoConfig();
		c.enabled = d.enabled;
		c.minBet = d.minBet;
		c.maxBet = d.maxBet;
		c.maxBetTreasuryPercent = d.maxBetTreasuryPercent;
		c.maxWinTreasuryPercent = d.maxWinTreasuryPercent;
		c.dailyLossLimit = d.dailyLossLimit;
		c.announceMinProfit = d.announceMinProfit;
		c.announceMinPayoutForMultiplier = d.announceMinPayoutForMultiplier;
		return c;
	}

	/** Sets the Treasury so that exactly {@code free} is left after other reservations. */
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

	private static long[] table(Object... spotAmount) {
		long[] t = new long[RouletteMath.SPOTS];
		for (int i = 0; i < spotAmount.length; i += 2) t[(Integer) spotAmount[i]] += ((Number) spotAmount[i + 1]).longValue();
		return t;
	}

	/** Random table: 1–5 chips on random spots (numbers and outside spots equally often). */
	private static long[] randomTable(SplittableRandom rng, long unit) {
		long[] t = new long[RouletteMath.SPOTS];
		int chips = 1 + rng.nextInt(5);
		for (int i = 0; i < chips; i++) {
			int spot = rng.nextBoolean() ? rng.nextInt(RouletteMath.POCKETS) : RouletteMath.POCKETS + rng.nextInt(RouletteMath.SPOTS - RouletteMath.POCKETS);
			t[spot] += unit * (1 + rng.nextInt(10));
		}
		return t;
	}

	private static void checkRtp(String what, double sum, double sumSq, int n, double expected) {
		double mean = sum / n;
		double var = Math.max(0, sumSq / n - mean * mean);
		double tol = 4 * Math.sqrt(var / n) + 0.0005;
		Assert.check(Math.abs(mean - expected) <= tol, what + ": RTP " + fmt(mean) + " deviates from " + fmt(expected) + " (tolerance " + fmt(tol) + ")");
	}

	private static String fmt(double d) {
		return String.format(java.util.Locale.ROOT, "%.4f", d);
	}

	private static void wheel(TestContext ctx) {
		boolean[] seen = new boolean[RouletteMath.POCKETS];
		for (int n : RouletteMath.WHEEL) {
			Assert.check(n >= 0 && n < RouletteMath.POCKETS && !seen[n], "wheel: " + n + " exactly once");
			seen[n] = true;
		}
		Assert.eq(RouletteMath.POCKETS, RouletteMath.WHEEL.length, "37 pockets");
		int red = 0;
		for (int n = 1; n <= 36; n++) {
			Assert.check(RouletteMath.isRed(n) != RouletteMath.isBlack(n), n + ": red or black");
			if (RouletteMath.isRed(n)) red++;
			Assert.eq(n, RouletteMath.WHEEL[RouletteMath.wheelIndex(n)], "wheelIndex(" + n + ")");
		}
		Assert.eq(18, red, "18 red numbers");
		Assert.isFalse(RouletteMath.isRed(0) || RouletteMath.isBlack(0), "zero is green");
		Assert.eq(0, RouletteMath.WHEEL[0], "wheel starts at 0");
		for (int i = 1; i < RouletteMath.WHEEL.length - 1; i++) {
			Assert.check(RouletteMath.isRed(RouletteMath.WHEEL[i]) != RouletteMath.isRed(RouletteMath.WHEEL[i + 1]), "colours alternate on the wheel (pocket " + i + ")");
		}
		for (int spot = 0; spot < RouletteMath.SPOTS; spot++) {
			int covered = 0;
			for (int n = 0; n < RouletteMath.POCKETS; n++) if (RouletteMath.covers(spot, n)) covered++;
			Assert.eq(RouletteMath.coverCount(spot), covered, RouletteMath.spotName(spot) + ": covered numbers");
			if (spot > 0) Assert.isFalse(RouletteMath.covers(spot, 0), RouletteMath.spotName(spot) + " loses on 0");
		}
		Assert.isTrue(RouletteMath.covers(RouletteMath.COLUMN_1, 34) && RouletteMath.covers(RouletteMath.COLUMN_1 + 2, 36), "columns 1 (…34) and 3 (…36)");
		Assert.isTrue(RouletteMath.covers(RouletteMath.DOZEN_1 + 1, 13) && RouletteMath.covers(RouletteMath.DOZEN_1 + 1, 24), "2nd dozen 13–24");
		Assert.eq("35:1", RouletteMath.odds(17), "straight 35:1");
		Assert.eq("2:1", RouletteMath.odds(RouletteMath.DOZEN_1), "dozen 2:1");
		Assert.eq("1:1", RouletteMath.odds(RouletteMath.RED_SPOT), "red 1:1");
		// uniform ball
		SplittableRandom rng = new SplittableRandom(1937);
		int draws = 370_000;
		int[] hits = new int[RouletteMath.POCKETS];
		for (int i = 0; i < draws; i++) hits[RouletteMath.spin(rng)]++;
		double expected = draws / 37.0;
		for (int n = 0; n < RouletteMath.POCKETS; n++) Assert.check(Math.abs(hits[n] - expected) < expected * 0.05, "Number " + n + " uniform (" + hits[n] + ")");
		ctx.detail("37 pockets, colours, spots, odds, uniformity over " + draws + " rolls");
	}

	/**
	 * Exact: over all 37 numbers each spot returns 36 × stake minus 1 %, so RTP 36/37 × 0.99 for every spot and
	 * combination (whole-dollar stakes, so no rounding); house edge within the 3–5 % band.
	 */
	private static void payoutExact(TestContext ctx) {
		// fee: 1 %, rounded down in the house's favour, no overflow
		Assert.eq(0L, RouletteMath.afterFee(0), "fee: 0 → 0");
		Assert.eq(0L, RouletteMath.afterFee(1), "fee: 1 cent → 0");
		Assert.eq(99L, RouletteMath.afterFee(100), "fee: $1 → $0.99");
		Assert.eq(148L, RouletteMath.afterFee(150), "fee: $1.50 → $1.48 (rounded down)");
		Assert.eq(35_640L, RouletteMath.afterFee(36_000), "fee: $360 → $356.40");
		Assert.eq(Long.MAX_VALUE - Long.MAX_VALUE / 100 - 1, RouletteMath.afterFee(Long.MAX_VALUE), "fee: no overflow");
		for (long g = 0; g < 5_000; g++) {
			Assert.eq(g * 99 / 100, RouletteMath.afterFee(g), "fee = floor(g × 99 / 100) for " + g);
		}
		for (int spot = 0; spot < RouletteMath.SPOTS; spot++) {
			long[] t = table(spot, 100);
			long sum = 0;
			for (int n = 0; n < RouletteMath.POCKETS; n++) sum += RouletteMath.payout(t, n);
			Assert.eq(3_564L, sum, RouletteMath.spotName(spot) + ": Σ payout over 37 numbers = 36 × 0.99 × stake");
			Assert.eq(RouletteMath.payout(t, firstCovered(spot)), RouletteMath.spotWin(100, spot), RouletteMath.spotName(spot) + ": display = payout");
		}
		SplittableRandom rng = new SplittableRandom(2024);
		for (int i = 0; i < 2_000; i++) {
			long[] t = randomTable(rng, 100);
			long sum = 0;
			long max = 0;
			for (int n = 0; n < RouletteMath.POCKETS; n++) {
				long p = RouletteMath.payout(t, n);
				sum += p;
				max = Math.max(max, p);
			}
			Assert.eq(36 * RouletteMath.total(t) * 99 / 100, sum, "Combination " + i + ": Σ = 36 × 0.99 × total stake");
			Assert.eq(max, RouletteMath.maxPayout(t), "Combination " + i + ": maximum win");
		}
		// example: $10 on 19, $10 red, $10 2nd dozen (19 is red and in the 2nd dozen); gross − 1 %
		long[] ex = table(19, 1_000, RouletteMath.RED_SPOT, 1_000, RouletteMath.DOZEN_1 + 1, 1_000);
		Assert.eq(40_590L, RouletteMath.payout(ex, 19), "19: (36×10 + 2×10 + 3×10) × 0,99");
		Assert.eq(0L, RouletteMath.payout(ex, 0), "0: all lost");
		Assert.eq(2_970L, RouletteMath.payout(ex, 13), "13 (black, 2nd dozen): only the dozen");
		Assert.eq(1_980L, RouletteMath.payout(ex, 1), "1 (red, 1st dozen): only red");
		Assert.eq(0L, RouletteMath.payout(ex, 26), "26 (black, 3rd dozen): nothing");
		Assert.eq(40_590L, RouletteMath.maxPayout(ex), "maximum win on 19");
		long[] black17 = table(17, 1_000, RouletteMath.RED_SPOT, 1_000, RouletteMath.DOZEN_1 + 1, 1_000);
		Assert.eq(38_610L, RouletteMath.payout(black17, 17), "17 is black: straight + dozen, red loses");
		Assert.eq(35_640L, RouletteMath.payout(table(0, 1_000), 0), "straight on 0: 35:1 − 1 %");
		Assert.check(Math.abs(RouletteMath.RTP - 36.0 / 37.0 * 0.99) < 1e-12, "RTP = 36/37 × 0.99");
		Assert.check(RouletteMath.RTP >= 0.95 && RouletteMath.RTP <= 0.97, "RTP in the 95–97 % band (house edge 3–5 %): " + fmt(RouletteMath.RTP));
		Assert.eq(String.format(java.util.Locale.ROOT, "%.1f %%", RouletteMath.RTP * 100), RouletteMath.RTP_TEXT, "RTP text matches");
		ctx.detail("49 spots + 2,000 combinations exactly 36/37 × 0.99 (RTP " + fmt(RouletteMath.RTP) + ", house edge " + fmt(1 - RouletteMath.RTP) + ")");
	}

	/** First number on which the spot wins. */
	private static int firstCovered(int spot) {
		for (int n = 0; n < RouletteMath.POCKETS; n++) if (RouletteMath.covers(spot, n)) return n;
		throw new IllegalArgumentException("Spot " + spot);
	}

	/**
	 * RTP simulation: 200,000 rounds with mixed tables (heavy on straights, hence a wider tolerance) and 1,000,000
	 * rounds of even-money only (tolerance about ±0.5 %). Tolerance = 4 standard errors + 0.0005.
	 */
	private static void rtpSimulation(TestContext ctx) {
		SplittableRandom rng = new SplittableRandom(20261010L);
		double sum = 0;
		double sumSq = 0;
		long staked = 0;
		long paid = 0;
		for (int i = 0; i < SIM_ROUNDS; i++) {
			long[] t = randomTable(rng, 100);
			long total = RouletteMath.total(t);
			long p = RouletteMath.payout(t, RouletteMath.spin(rng));
			double x = p / (double) total;
			sum += x;
			sumSq += x * x;
			staked += total;
			paid += p;
		}
		checkRtp("Roulette (mixed tables)", sum, sumSq, SIM_ROUNDS, RouletteMath.RTP);
		double even = 0;
		double evenSq = 0;
		int n = 5 * SIM_ROUNDS;
		long[][] single = new long[6][];
		for (int k = 0; k < 6; k++) single[k] = table(RouletteMath.RED_SPOT + k, 100);
		for (int i = 0; i < n; i++) {
			double x = RouletteMath.payout(single[rng.nextInt(6)], RouletteMath.spin(rng)) / 100.0;
			even += x;
			evenSq += x * x;
		}
		checkRtp("Roulette (even money)", even, evenSq, n, RouletteMath.RTP);
		double ratio = paid / (double) staked;
		ctx.detail("theoretical " + fmt(RouletteMath.RTP) + ", simulated " + fmt(sum / SIM_ROUNDS) + " (mixed tables, " + SIM_ROUNDS + " rounds), "
			+ fmt(even / n) + " (even money, " + n + " rounds), money-weighted " + fmt(ratio));
	}

	/** RTP and conservation through the real logic (Treasury, stats): 20,000 rounds, player + Treasury constant. */
	private static void engineRtp(TestContext ctx) {
		CasinoConfig c = setup(ctx);
		c.dailyLossLimit = Money.MAX;
		c.announceMinProfit = 0; // no announcements from selftests (test players are skipped anyway)
		FakePlayer a = funded(ctx, "RouletteRtp", 10_000_000_000L);
		setTreasuryFree(10_000_000_000L);
		UUID id = a.getUUID();
		long t0 = Treasury.balance();
		long p0 = EconomyService.balance(id);
		long reserved0 = Casino.reservedTotal(null);
		SplittableRandom rng = new SplittableRandom(4711);
		double sum = 0;
		double sumSq = 0;
		long staked = 0;
		long paid = 0;
		for (int i = 0; i < ENGINE_ROUNDS; i++) {
			long[] t = randomTable(rng, 1_000);
			long total = RouletteMath.total(t);
			RouletteGame.Spin s = RouletteGame.play(id, name(a), t, rng);
			Assert.isTrue(s.ok(), "Round " + i + ": " + s.error());
			Round r = s.start().round();
			Assert.eq(CasinoData.DECIDED, r.state, "Round " + i + " decided before settlement");
			Assert.eq(RouletteMath.payout(t, s.pocket()), r.payout, "Round " + i + ": payout matches number " + s.pocket());
			Casino.Settled st = Casino.settle(r.id, null);
			Assert.notNull(st, "settled");
			Assert.isTrue(st.paid() && !st.refund(), "paid, no refund");
			staked += total;
			paid += st.payout();
			double x = st.payout() / (double) total;
			sum += x;
			sumSq += x * x;
			if (i % 1000 == 0) Assert.eq(t0 + p0, Treasury.balance() + EconomyService.balance(id), "player + Treasury constant (round " + i + ")");
		}
		checkRtp("Roulette (engine)", sum, sumSq, ENGINE_ROUNDS, RouletteMath.RTP);
		Assert.eq(t0 + staked - paid, Treasury.balance(), "Treasury = start + stakes − payouts");
		Assert.eq(p0 - staked + paid, EconomyService.balance(id), "player = start − stakes + payouts");
		Assert.eq(reserved0, Casino.reservedTotal(null), "no reservation left");
		Assert.isTrue(Casino.openRound(id) == null, "no open round");
		CasinoData.PlayerStats ps = Casino.peekStats(id);
		Assert.notNull(ps, "stats created");
		CasinoData.GameStats gs = ps.games.get(RouletteGame.GAME_ID);
		Assert.notNull(gs, "per-game stats 'roulette'");
		Assert.eq((long) ENGINE_ROUNDS, gs.rounds, "each round exactly once in the stats");
		Assert.eq(staked, gs.wagered, "stats: stakes");
		Assert.eq(paid, gs.paidOut, "stats: payouts");
		ctx.detail(fmt(sum / ENGINE_ROUNDS) + " over " + ENGINE_ROUNDS + " engine rounds, treasury " + (staked - paid >= 0 ? "+" : "") + Money.format(staked - paid));
	}

	private static void exactlyOnce(TestContext ctx) {
		setup(ctx);
		FakePlayer a = funded(ctx, "RouletteOnce", 1_000_000);
		setTreasuryFree(100_000_000);
		UUID id = a.getUUID();
		long b0 = EconomyService.balance(id);
		long t0 = Treasury.balance();
		long[] t = table(19, 1_000, RouletteMath.RED_SPOT, 1_000, RouletteMath.DOZEN_1 + 1, 1_000);
		RouletteGame.Spin s = RouletteGame.play(id, name(a), t, scripted(19));
		Assert.isTrue(s.ok(), "round started: " + s.error());
		Assert.eq(19, s.pocket(), "ball on 19 (scripted)");
		Round r = s.start().round();
		// result is fixed before any animation and stored in casino.json (state + payout)
		Assert.eq(CasinoData.DECIDED, r.state, "decided before the animation");
		Assert.eq(40_590L, r.payout, "payout stored ($410 − 1 %)");
		Assert.eq(40_590L, r.reserved, "Treasury reserves exactly the payout");
		Assert.eq(RouletteGame.GAME_ID, r.game, "game id");
		Assert.eq(3_000L, r.stake, "one stake for the whole table");
		Assert.eq(b0 - 3_000, EconomyService.balance(id), "stake taken");
		Assert.eq(t0 + 3_000, Treasury.balance(), "stake in the Treasury");
		Assert.isTrue(Casino.openRound(id) == r, "round open until the end of the animation");
		// restart after a crash: the stored round (JSON round trip) gives the same value
		Round loaded = DataStore.gson().fromJson(DataStore.gson().toJson(r), Round.class);
		Assert.eq(CasinoData.DECIDED, loaded.state, "JSON: decided");
		Assert.eq(40_590L, loaded.payout, "JSON: payout");
		Assert.eq(40_590L, Casino.cashOutValue(loaded), "JSON: settlement pays the fixed payout");
		// settlement (end of animation, or close/disconnect/stop/restart), exactly once
		Casino.Settled first = Casino.settle(r.id, "19 Red, window closed");
		Assert.notNull(first, "settled");
		Assert.eq(40_590L, first.payout(), "payout");
		Assert.isTrue(first.paid() && !first.refund(), "booked, no refund");
		Assert.isTrue(Casino.settle(r.id, null) == null, "second settlement has no effect");
		Assert.isTrue(Casino.settle(r.id, "server restart") == null, "third settlement has no effect");
		Assert.eq(b0 - 3_000 + 40_590, EconomyService.balance(id), "paid exactly once");
		Assert.eq(t0 + 3_000 - 40_590, Treasury.balance(), "Treasury charged exactly once");
		Assert.isTrue(Casino.openRound(id) == null, "nothing left open");
		// loss: zero with outside spots only
		long[] outside = table(RouletteMath.RED_SPOT, 2_000, RouletteMath.ODD, 1_000);
		RouletteGame.Spin z = RouletteGame.play(id, name(a), outside, scripted(0));
		Assert.isTrue(z.ok() && z.pocket() == 0, "ball on 0");
		Assert.eq(0L, z.start().round().payout, "0: outside spots lose");
		Assert.eq(0L, Casino.settle(z.start().round().id, null).payout(), "loss = 0");
		Assert.isTrue(Casino.settle(z.start().round().id, null) == null, "not twice");
		Assert.eq(b0 - 3_000 + 40_590 - 3_000, EconomyService.balance(id), "balance after a loss");
		CasinoData.GameStats gs = Casino.peekStats(id).games.get(RouletteGame.GAME_ID);
		Assert.eq(2L, gs.rounds, "2 rounds in the stats");
		Assert.eq(6_000L, gs.wagered, "stakes");
		Assert.eq(40_590L, gs.paidOut, "payouts");
		Assert.eq(-34_590L, Casino.netLossToday(id), "day balance: in profit");
		ctx.detail("win on 19 and loss on 0 each exactly once; JSON round trip keeps the result");
	}

	private static void guards(TestContext ctx) {
		CasinoConfig c = setup(ctx);
		FakePlayer a = funded(ctx, "RouletteGuardA", 100_000_000);
		setTreasuryFree(1_000_000); // $10,000 free: max bet $200, maximum win $5,000
		UUID id = a.getUUID();
		long b0 = EconomyService.balance(id);
		long maxBet = Casino.maxBet();
		Assert.eq(Treasury.balance() * 2 / 100, maxBet, "max bet = 2 % of the Treasury");
		// above the max bet (the sum of all chips counts)
		RouletteGame.Spin over = RouletteGame.play(id, name(a), table(RouletteMath.RED_SPOT, maxBet / 2 + 1, RouletteMath.BLACK_SPOT, maxBet / 2 + 1), scripted(1));
		Assert.isFalse(over.ok(), "total stake above 2 % refused");
		Assert.eq(b0, EconomyService.balance(id), "refused: nothing taken");
		Assert.isTrue(Casino.openRound(id) == null, "refused: no round");
		// coverage: a straight at max bet pays ×36 (− 1 %) > maximum win
		long[] straightUp = table(7, maxBet);
		Assert.check(RouletteMath.maxPayout(straightUp) > Casino.coverLimit(null), "precondition: ×36 − 1 % not covered");
		RouletteGame.Spin uncovered = RouletteGame.play(id, name(a), straightUp, scripted(7));
		Assert.isFalse(uncovered.ok(), "uncovered maximum win refused");
		Assert.check(uncovered.error().toLowerCase(java.util.Locale.ROOT).contains("treasury"), "message names the Treasury: " + uncovered.error());
		Assert.eq(b0, EconomyService.balance(id), "nothing taken");
		// the same sum on red is covered
		RouletteGame.Spin red = RouletteGame.play(id, name(a), table(RouletteMath.RED_SPOT, maxBet), scripted(1));
		Assert.isTrue(red.ok(), "max bet on red admitted: " + red.error());
		Assert.eq(RouletteMath.afterFee(2 * maxBet), red.start().round().payout, "red wins on 1 (×2 − 1 %)");
		Assert.eq(RouletteMath.afterFee(2 * maxBet), red.start().round().reserved, "reservation = payout after fee");
		Casino.settle(red.start().round().id, null);
		// one round per player (also across games)
		Casino.Start slots = Casino.startSlots(id, name(a), 1_000, scripted(0, 1, 2));
		Assert.isTrue(slots.ok(), "slots round open: " + slots.error());
		long b1 = EconomyService.balance(id);
		Assert.isFalse(RouletteGame.play(id, name(a), table(RouletteMath.EVEN, 1_000), scripted(2)).ok(), "second simultaneous round refused");
		Assert.eq(b1, EconomyService.balance(id), "nothing taken");
		Casino.settle(slots.round().id, null);
		long settled = EconomyService.balance(id);
		// balance
		FakePlayer poor = funded(ctx, "RouletteGuardB", 500);
		Assert.isFalse(RouletteGame.play(poor.getUUID(), name(poor), table(RouletteMath.LOW, 1_000), scripted(1)).ok(), "not enough money");
		Assert.eq(500L, EconomyService.balance(poor.getUUID()), "nothing taken");
		// empty or invalid table, minimum bet, casino closed
		Assert.isFalse(RouletteGame.play(id, name(a), new long[RouletteMath.SPOTS], scripted(1)).ok(), "empty table");
		Assert.isFalse(RouletteGame.play(id, name(a), table(3, -100), scripted(1)).ok(), "negative stake");
		Assert.isFalse(RouletteGame.play(id, name(a), new long[5], scripted(1)).ok(), "wrong table size");
		c.minBet = 1_000;
		Assert.isFalse(RouletteGame.play(id, name(a), table(RouletteMath.EVEN, 500), scripted(2)).ok(), "below the minimum bet");
		c.minBet = new CasinoConfig().minBet;
		c.enabled = false;
		Assert.isFalse(RouletteGame.play(id, name(a), table(RouletteMath.EVEN, 1_000), scripted(2)).ok(), "casino closed");
		c.enabled = true;
		Assert.eq(settled, EconomyService.balance(id), "refused rounds do not change the balance");
		// daily loss limit ($50): a fresh player may lose $50, then nothing more
		c.dailyLossLimit = 5_000;
		FakePlayer l = funded(ctx, "RouletteGuardC", 100_000);
		UUID lid = l.getUUID();
		Assert.isFalse(RouletteGame.play(lid, name(l), table(RouletteMath.ODD, 6_000), scripted(2)).ok(), "above the daily limit refused");
		RouletteGame.Spin lose = RouletteGame.play(lid, name(l), table(RouletteMath.ODD, 5_000), scripted(2));
		Assert.isTrue(lose.ok(), "admitted up to the daily limit: " + lose.error());
		Assert.eq(0L, Casino.settle(lose.start().round().id, null).payout(), "2 is even: odd loses");
		Assert.eq(0L, Casino.lossRoom(lid), "daily limit used up");
		Assert.isFalse(RouletteGame.play(lid, name(l), table(RouletteMath.ODD, 100), scripted(1)).ok(), "limit reached: nothing more");
		Assert.isTrue(RouletteGame.tableProblem(lid, table(RouletteMath.ODD, 100)) != null, "GUI pre-check reports the limit");
		Assert.eq(95_000L, EconomyService.balance(lid), "only the admitted round booked");
		c.dailyLossLimit = new CasinoConfig().dailyLossLimit;
		// empty Treasury: locked
		EconomyService.set(Treasury.ID, Casino.reservedTotal(null));
		Assert.isFalse(RouletteGame.play(id, name(a), table(RouletteMath.EVEN, 100), scripted(2)).ok(), "empty Treasury: locked");
		Assert.eq(0L, RouletteGame.maxTotal(id), "empty Treasury: max stake 0");
		Assert.eq(settled, EconomyService.balance(id), "balance unchanged");
		ctx.detail("max bet, coverage ×36, one round, balance, table, minimum, closed, daily limit, empty Treasury");
	}

	/** A game returns an invalid result or throws: round voided, stake back, nothing twice. */
	private static void voided(TestContext ctx) {
		setup(ctx);
		FakePlayer a = funded(ctx, "RouletteVoid", 1_000_000);
		setTreasuryFree(100_000_000);
		UUID id = a.getUUID();
		long b0 = EconomyService.balance(id);
		long t0 = Treasury.balance();
		Casino.Start tooMuch = Casino.startInstant(id, name(a), RouletteGame.GAME, 1_000, s -> 2_000, r -> 2_001);
		Assert.isFalse(tooMuch.ok(), "payout above the covered maximum: voided");
		Casino.Start thrown = Casino.startInstant(id, name(a), RouletteGame.GAME, 1_000, s -> 2_000, r -> {
			throw new IllegalStateException("selftest");
		});
		Assert.isFalse(thrown.ok(), "exception: voided");
		Assert.eq(b0, EconomyService.balance(id), "stakes back");
		Assert.eq(t0, Treasury.balance(), "Treasury unchanged");
		Assert.isTrue(Casino.openRound(id) == null, "no open round");
		CasinoData.PlayerStats ps = Casino.peekStats(id);
		Assert.eq(0L, ps == null ? 0 : ps.rounds, "voided rounds do not count");
		Assert.eq(0L, Casino.netLossToday(id), "no daily loss");
		Casino.InstantGame unknown = new Casino.InstantGame("nonexistent", "x", net.minecraft.world.item.Items.PAPER, s -> s);
		Assert.assertThrows(IllegalArgumentException.class, () -> Casino.startInstant(id, name(a), unknown, 1_000, r -> 0), "unknown game");
		Casino.InstantGame builtin = new Casino.InstantGame("slots", "x", net.minecraft.world.item.Items.PAPER, s -> s);
		Assert.assertThrows(IllegalArgumentException.class, () -> Casino.registerGame(builtin), "built-in id taken");
		ctx.detail("invalid payout and exception: stake back");
	}

	/** The GUI pre-check agrees with admission (random tables at the boundary). */
	private static void precheck(TestContext ctx) {
		setup(ctx);
		FakePlayer a = funded(ctx, "RoulettePre", 50_000);
		setTreasuryFree(1_000_000);
		UUID id = a.getUUID();
		SplittableRandom rng = new SplittableRandom(99);
		int ok = 0;
		int rejected = 0;
		for (int i = 0; i < 400; i++) {
			long[] t = randomTable(rng, 100 + rng.nextInt(2_000));
			String problem = RouletteGame.tableProblem(id, t);
			RouletteGame.Spin s = RouletteGame.play(id, name(a), t, rng);
			Assert.eq(problem == null, s.ok(), "Table " + i + ": pre-check (" + problem + ") = admission (" + s.error() + ")");
			if (s.ok()) {
				ok++;
				Casino.settle(s.start().round().id, null);
				EconomyService.set(id, 50_000);
			} else {
				rejected++;
			}
		}
		Assert.check(ok > 20 && rejected > 20, "both cases tested (admitted " + ok + ", refused " + rejected + ")");
		Assert.isTrue(RouletteGame.tableProblem(id, table(RouletteMath.RED_SPOT, 100)) == null, "$1 on red allowed");
		ctx.detail(ok + " admitted, " + rejected + " refused, pre-check = admission");
	}
}
