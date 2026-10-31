package dev.smpeconomy.features.casino.games.dice;

import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.entity.FakePlayer;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.Treasury;
import dev.smpeconomy.core.test.Assert;
import dev.smpeconomy.core.test.SelfTests;
import dev.smpeconomy.core.test.TestContext;
import dev.smpeconomy.features.casino.Casino;
import dev.smpeconomy.features.casino.CasinoConfig;
import dev.smpeconomy.features.casino.CasinoData;
import dev.smpeconomy.features.casino.CasinoData.Round;
import dev.smpeconomy.features.casino.games.dice.DiceGame.Bet;

/**
 * Dice selftests ({@code /smpeco selftest casino-dice}, no GUI): math, RTP simulation (2 × 200,000 rolls), RTP through
 * the real casino logic with money conservation, admission (max bet, coverage, daily limit, one round) and exactly-once
 * settlement including recovery after a restart.
 */
final class DiceSelfTests {
	static final String F = "casino-dice";
	static final int SIM_ROUNDS = 200_000;

	private DiceSelfTests() {}

	static void register() {
		SelfTests.register(F, "math", DiceSelfTests::math);
		SelfTests.register(F, "rtp-simulation", DiceSelfTests::rtpSimulation);
		SelfTests.register(F, "engine-rtp-conservation", DiceSelfTests::engineRtp);
		SelfTests.register(F, "exactly-once", DiceSelfTests::exactlyOnce);
		SelfTests.register(F, "restart-recovery", DiceSelfTests::restartRecovery);
		SelfTests.register(F, "guards", DiceSelfTests::guards);
		SelfTests.register(F, "loss-limit", DiceSelfTests::lossLimit);
		SelfTests.register(F, "outcome-failure-refund", DiceSelfTests::outcomeFailure);
		SelfTests.register(F, "english-messages", DiceSelfTests::englishMessages);
	}

	/** Random source with preset rolls (1–100) for {@link DiceMath#roll}, then a fixed seed. */
	static RandomGenerator rolls(int... rolls) {
		SplittableRandom fallback = new SplittableRandom(7);
		return new RandomGenerator() {
			private int index;

			@Override
			public long nextLong() {
				return fallback.nextLong();
			}

			@Override
			public int nextInt(int bound) {
				if (index < rolls.length) return Math.floorMod(rolls[index++] - 1, bound);
				return fallback.nextInt(bound);
			}
		};
	}

	/**
	 * Sets casino and dice config to defaults for the test (changed fields are restored afterwards), saves the
	 * Treasury.
	 */
	private static CasinoConfig setup(TestContext ctx) {
		ctx.preserveTreasury();
		CasinoConfig live = Casino.cfg();
		CasinoConfig def = new CasinoConfig();
		boolean enabled = live.enabled;
		long minBet = live.minBet;
		long maxBet = live.maxBet;
		double maxBetPct = live.maxBetTreasuryPercent;
		double maxWinPct = live.maxWinTreasuryPercent;
		long loss = live.dailyLossLimit;
		DiceConfig dice = DiceGame.cfg();
		boolean diceEnabled = dice.enabled;
		double edge = dice.edgePercent;
		ctx.cleanup(() -> {
			live.enabled = enabled;
			live.minBet = minBet;
			live.maxBet = maxBet;
			live.maxBetTreasuryPercent = maxBetPct;
			live.maxWinTreasuryPercent = maxWinPct;
			live.dailyLossLimit = loss;
			dice.enabled = diceEnabled;
			dice.edgePercent = edge;
		});
		live.enabled = def.enabled;
		live.minBet = def.minBet;
		live.maxBet = def.maxBet;
		live.maxBetTreasuryPercent = def.maxBetTreasuryPercent;
		live.maxWinTreasuryPercent = def.maxWinTreasuryPercent;
		live.dailyLossLimit = def.dailyLossLimit;
		DiceConfig d = new DiceConfig();
		dice.enabled = d.enabled;
		dice.edgePercent = d.edgePercent;
		return live;
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

	private static String fmt(double d) {
		return String.format(java.util.Locale.ROOT, "%.4f", d);
	}

	/** Mean against expectation, tolerance 4 standard errors + 0.0005. */
	private static void checkRtp(String what, double sum, double sumSq, double expectedSum, int n) {
		double mean = sum / n;
		double expected = expectedSum / n;
		double var = Math.max(0, sumSq / n - mean * mean);
		double tol = 4 * Math.sqrt(var / n) + 0.0005;
		Assert.check(expected >= 0.95 && expected <= 0.97, what + ": expected RTP " + fmt(expected) + " not in [0.95, 0.97]");
		Assert.check(Math.abs(mean - expected) <= tol, what + ": RTP " + fmt(mean) + " deviates from " + fmt(expected) + " (tolerance " + fmt(tol) + ")");
	}

	private static void math(TestContext ctx) {
		Assert.eq(5, DiceMath.minTarget(true), "over from 5 (95 %)");
		Assert.eq(99, DiceMath.maxTarget(true), "over up to 99 (1 %)");
		Assert.eq(2, DiceMath.minTarget(false), "under from 2 (1 %)");
		Assert.eq(96, DiceMath.maxTarget(false), "under up to 96 (95 %)");
		Assert.isFalse(DiceMath.valid(true, 100), "over 100 impossible");
		Assert.isFalse(DiceMath.valid(true, 4), "over 4 (96 %) not allowed");
		Assert.isFalse(DiceMath.valid(false, 1), "under 1 impossible");
		Assert.isFalse(DiceMath.valid(false, 97), "under 97 (96 %) not allowed");
		Assert.isTrue(DiceMath.wins(51, true, 50) && !DiceMath.wins(50, true, 50), "over 50: 51 wins, 50 loses");
		Assert.isTrue(DiceMath.wins(49, false, 50) && !DiceMath.wins(50, false, 50), "under 50: 49 wins, 50 loses");
		// chance = number of winning rolls; RTP per target in [0.95, 0.97], multiplier > 1
		int edgeBp = new DiceConfig().edgeBp();
		Assert.eq(300, edgeBp, "default edge 3 %");
		int checked = 0;
		for (boolean over : new boolean[] {true, false}) {
			for (int t = DiceMath.MIN_ROLL; t <= DiceMath.MAX_ROLL; t++) {
				if (!DiceMath.valid(over, t)) continue;
				int wins = 0;
				for (int r = DiceMath.MIN_ROLL; r <= DiceMath.MAX_ROLL; r++) if (DiceMath.wins(r, over, t)) wins++;
				Assert.eq(DiceMath.chance(over, t), wins, (over ? "Over " : "Under ") + t + ": chance = winning rolls");
				Assert.eq(t, DiceMath.targetFor(over, wins), "targetFor inverts chance");
				double rtp = DiceMath.exactRtp(10_000, wins, edgeBp);
				Assert.check(rtp >= 0.95 && rtp <= 0.97, "RTP " + fmt(rtp) + " at chance " + wins + " %");
				Assert.check(rtp <= DiceMath.rtp(edgeBp) + 1e-12, "rounding never favours the player");
				checked++;
			}
		}
		Assert.eq(2 * (DiceMath.MAX_CHANCE - DiceMath.MIN_CHANCE + 1), checked, "all targets checked");
		// examples (exact, rounded down)
		Assert.eq(19_206L, DiceMath.payout(10_000, 50, 300), "$100 Over 50: 99/50 × 0.97 = ×1.9206");
		Assert.eq(960_300L, DiceMath.payout(10_000, 1, 300), "$100 at 1 %: ×96.03");
		Assert.eq(10_108L, DiceMath.payout(10_000, 95, 300), "$100 at 95 %: ×1.0108");
		Assert.eq(1L, DiceMath.payout(1, 95, 300), "1 cent at 95 %: 1 cent (rounded down)");
		Assert.eq("×1.92", DiceMath.formatMultiplier(50, 300), "display rounded down");
		Assert.isTrue(DiceMath.payout(Money.MAX, 1, 300) > Money.MAX, "no overflow at Money.MAX");
		Assert.assertThrows(IllegalArgumentException.class, () -> DiceMath.payout(100, 0, 300), "chance 0 throws");
		Assert.assertThrows(IllegalArgumentException.class, () -> DiceMath.payout(100, 96, 300), "chance 96 throws");
		// config limits: house edge 3–5 % (RTP 95–97 %), multiplier at 95 % stays > 1
		for (double e : new double[] {DiceConfig.MIN_EDGE_PERCENT, 3.0, DiceConfig.MAX_EDGE_PERCENT}) {
			DiceConfig c = new DiceConfig();
			c.edgePercent = e;
			c.validate();
			Assert.check(DiceMath.multiplier(DiceMath.MAX_CHANCE, c.edgeBp()) > 1, "multiplier > 1 at 95 % (edge " + e + ")");
			double house = 1 - DiceMath.rtp(c.edgeBp());
			Assert.check(house >= 0.03 && house <= 0.05, "house edge " + fmt(house) + " in [3, 5] %");
		}
		for (double bad : new double[] {1.0, 2.0, 4.5, 5.0, Double.NaN}) {
			DiceConfig c = new DiceConfig();
			c.edgePercent = bad;
			Assert.assertThrows(IllegalArgumentException.class, c::validate, "edge " + bad + " refused");
		}
		// rolls uniform 1–100
		SplittableRandom rng = new SplittableRandom(100);
		int[] hits = new int[DiceMath.MAX_ROLL + 1];
		int n = 200_000;
		for (int i = 0; i < n; i++) {
			int r = DiceMath.roll(rng);
			Assert.check(r >= 1 && r <= 100, "roll in range: " + r);
			hits[r]++;
		}
		for (int r = 1; r <= 100; r++) Assert.check(Math.abs(hits[r] - n / 100.0) < n / 100.0 * 0.1, "Roll " + r + " uniform (" + hits[r] + ")");
		ctx.detail(checked + " targets, examples, config limits, uniformity ok");
	}

	/** 200,000 rolls "Over 50" and 200,000 rolls with random target and direction (pure math). */
	private static void rtpSimulation(TestContext ctx) {
		int edgeBp = new DiceConfig().edgeBp();
		long stake = 10_000;
		SplittableRandom rng = new SplittableRandom(20261010L);
		// (1) typical bet: Over 50
		double sum = 0;
		double sumSq = 0;
		double expected = 0;
		int wins = 0;
		for (int i = 0; i < SIM_ROUNDS; i++) {
			int roll = DiceMath.roll(rng);
			boolean win = DiceMath.wins(roll, true, 50);
			double x = win ? DiceMath.payout(stake, 50, edgeBp) / (double) stake : 0;
			if (win) wins++;
			sum += x;
			sumSq += x * x;
			expected += DiceMath.exactRtp(stake, 50, edgeBp);
		}
		checkRtp("Over 50", sum, sumSq, expected, SIM_ROUNDS);
		Assert.check(Math.abs(wins / (double) SIM_ROUNDS - 0.5) < 0.005, "over 50 wins in ~50 % (" + wins + ")");
		double meanFixed = sum / SIM_ROUNDS;
		// (2) random targets across the whole range (1–95 %), random stakes
		sum = 0;
		sumSq = 0;
		expected = 0;
		for (int i = 0; i < SIM_ROUNDS; i++) {
			boolean over = rng.nextBoolean();
			int chance = DiceMath.MIN_CHANCE + rng.nextInt(DiceMath.MAX_CHANCE - DiceMath.MIN_CHANCE + 1);
			int target = DiceMath.targetFor(over, chance);
			long s = 100 + rng.nextInt(1_000_000);
			int roll = DiceMath.roll(rng);
			double x = DiceMath.wins(roll, over, target) ? DiceMath.payout(s, chance, edgeBp) / (double) s : 0;
			sum += x;
			sumSq += x * x;
			expected += DiceMath.exactRtp(s, chance, edgeBp);
		}
		checkRtp("random targets", sum, sumSq, expected, SIM_ROUNDS);
		ctx.detail("Over 50: " + fmt(meanFixed) + ", random targets: " + fmt(sum / SIM_ROUNDS) + " (target " + fmt(DiceMath.rtp(edgeBp)) + ", each "
			+ SIM_ROUNDS + " rolls)");
	}

	/**
	 * Through the real casino logic (stake to Treasury, win from Treasury): player + Treasury stays constant after each
	 * round, Treasury = start + stakes − payouts, stats count each round once, RTP within tolerance.
	 */
	private static void engineRtp(TestContext ctx) {
		CasinoConfig c = setup(ctx);
		c.dailyLossLimit = Money.MAX;
		FakePlayer a = funded(ctx, "DiceEngine", 10_000_000_000L);
		setTreasuryFree(10_000_000_000L);
		UUID id = a.getUUID();
		int edgeBp = DiceGame.cfg().edgeBp();
		long t0 = Treasury.balance();
		long p0 = EconomyService.balance(id);
		long reserved0 = Casino.reservedTotal(null);
		SplittableRandom rng = new SplittableRandom(4711);
		int n = 25_000;
		long staked = 0;
		long paid = 0;
		double sum = 0;
		double sumSq = 0;
		double expected = 0;
		for (int i = 0; i < n; i++) {
			boolean over = rng.nextBoolean();
			int chance = i % 2 == 0 ? 50 : DiceMath.MIN_CHANCE + rng.nextInt(DiceMath.MAX_CHANCE);
			Bet bet = new Bet(over, DiceMath.targetFor(over, chance));
			long stake = 1_000 + rng.nextInt(20_000);
			Casino.Start s = DiceGame.roll(id, name(a), stake, bet, rng);
			Assert.isTrue(s.ok(), "Round " + i + " admitted: " + s.error());
			Round r = s.round();
			Assert.eq(CasinoData.DECIDED, r.state, "result decided at once");
			Assert.eq(p0 - staked + paid - stake, EconomyService.balance(id), "stake taken (round " + i + ")");
			int roll = r.reels[DiceGame.R_ROLL];
			long want = DiceMath.wins(roll, bet.over(), bet.target()) ? DiceMath.payout(stake, chance, edgeBp) : 0;
			Assert.eq(want, r.payout, "payout matches roll " + roll + " (" + bet.text() + ")");
			Casino.Settled st = Casino.settle(r.id, null);
			Assert.notNull(st, "settled");
			Assert.isTrue(st.paid() && !st.refund(), "booked, no refund");
			staked += stake;
			paid += st.payout();
			double x = st.payout() / (double) stake;
			sum += x;
			sumSq += x * x;
			expected += DiceMath.exactRtp(stake, chance, edgeBp);
			Assert.eq(t0 + p0, Treasury.balance() + EconomyService.balance(id), "player + Treasury constant after round " + i);
		}
		Assert.eq(t0 + staked - paid, Treasury.balance(), "Treasury = start + stakes − payouts");
		Assert.eq(reserved0, Casino.reservedTotal(null), "no reservation left");
		Assert.isTrue(Casino.openRound(id) == null, "no open round");
		CasinoData.PlayerStats ps = Casino.peekStats(id);
		Assert.notNull(ps, "stats created");
		CasinoData.GameStats gs = ps.games.get(DiceGame.GAME_ID);
		Assert.notNull(gs, "stats under 'dice'");
		Assert.eq((long) n, gs.rounds, "each round counted exactly once");
		Assert.eq(staked, gs.wagered, "stakes in the stats");
		Assert.eq(paid, gs.paidOut, "payouts in the stats");
		checkRtp("Engine", sum, sumSq, expected, n);
		ctx.detail(n + " rounds, RTP " + fmt(sum / n) + " (target " + fmt(expected / n) + "), Treasury " + (staked - paid >= 0 ? "+" : "") + Money.format(staked - paid));
	}

	private static void exactlyOnce(TestContext ctx) {
		setup(ctx);
		FakePlayer a = funded(ctx, "DiceOnce", 10_000_000);
		setTreasuryFree(100_000_000);
		UUID id = a.getUUID();
		int edgeBp = DiceGame.cfg().edgeBp();
		long b0 = EconomyService.balance(id);
		long t0 = Treasury.balance();
		// win: Over 50, roll 77
		Casino.Start s = DiceGame.roll(id, name(a), 10_000, new Bet(true, 50), rolls(77));
		Assert.isTrue(s.ok(), "roll started: " + s.error());
		Round r = s.round();
		long win = DiceMath.payout(10_000, 50, edgeBp);
		Assert.eq(CasinoData.DECIDED, r.state, "result decided before the animation");
		Assert.eq(win, r.payout, "payout stored");
		Assert.eq(win, r.reserved, "Treasury reserves exactly the payout");
		Assert.eq(77, r.reels[DiceGame.R_ROLL], "roll stored");
		Assert.eq(50, r.reels[DiceGame.R_TARGET], "target stored");
		Assert.eq(1, r.reels[DiceGame.R_OVER], "direction stored");
		Assert.eq(DiceGame.GAME_ID, r.game, "game id dice");
		Assert.eq(b0 - 10_000, EconomyService.balance(id), "stake taken, win not yet");
		Assert.isTrue(Casino.openRound(id) == r, "round open (animation running)");
		Casino.Settled first = Casino.settle(r.id, null);
		Assert.eq(win, first.payout(), "payout");
		Assert.isTrue(Casino.settle(r.id, null) == null, "second settlement (GUI after watchdog or stop) has no effect");
		Assert.isTrue(Casino.settle(r.id, "window closed") == null, "third settlement has no effect");
		Assert.eq(b0 - 10_000 + win, EconomyService.balance(id), "paid exactly once");
		// loss: Under 30, roll 30 (the boundary loses)
		Round lose = DiceGame.roll(id, name(a), 5_000, new Bet(false, 30), rolls(30)).round();
		Assert.notNull(lose, "second roll");
		Assert.eq(0L, lose.payout, "roll = target loses");
		Assert.eq(0L, Casino.settle(lose.id, null).payout(), "loss = 0");
		Assert.isTrue(Casino.settle(lose.id, null) == null, "second settlement has no effect");
		Assert.eq(b0 - 15_000 + win, EconomyService.balance(id), "balance correct");
		Assert.eq(t0 + 15_000 - win, Treasury.balance(), "Treasury = stakes − payout");
		CasinoData.GameStats gs = Casino.peekStats(id).games.get(DiceGame.GAME_ID);
		Assert.eq(2L, gs.rounds, "2 rounds in the stats");
		Assert.eq(15_000L, gs.wagered, "stakes");
		Assert.eq(win, gs.paidOut, "payouts");
		Assert.eq(1L, gs.wins, "1 win");
		ctx.detail("win and loss each exactly once, result stored before the animation");
	}

	/**
	 * Crash or stop during the animation: the round is stored decided in casino.json (JSON round trip) and paid once
	 * with the stored result when the restart or stop settles it ({@link Casino#settleAll} → settle).
	 */
	private static void restartRecovery(TestContext ctx) {
		setup(ctx);
		FakePlayer a = funded(ctx, "DiceCrash", 10_000_000);
		setTreasuryFree(100_000_000);
		UUID id = a.getUUID();
		int edgeBp = DiceGame.cfg().edgeBp();
		Round r = DiceGame.roll(id, name(a), 2_000, new Bet(false, 11), rolls(3)).round();
		Assert.notNull(r, "roll started");
		long win = DiceMath.payout(2_000, 10, edgeBp);
		Assert.eq(win, r.payout, "under 11, roll 3: win ×9.60");
		// how the round looks in casino.json
		String json = DataStore.gson().toJson(r);
		Round loaded = DataStore.gson().fromJson(json, Round.class);
		Assert.eq(CasinoData.DECIDED, loaded.state, "stored: decided");
		Assert.eq(win, loaded.payout, "stored: payout");
		Assert.eq(3, loaded.reels[DiceGame.R_ROLL], "stored: roll");
		Assert.eq(win, Casino.cashOutValue(loaded), "settlement after a restart pays the stored result");
		Assert.isFalse(loaded.refund, "no refund");
		long bal = EconomyService.balance(id);
		Casino.Settled st = Casino.settle(r.id, "server restart");
		Assert.eq(win, st.payout(), "restart or stop pays");
		Assert.isFalse(st.refund(), "counted as a played round");
		Assert.eq(bal + win, EconomyService.balance(id), "credited once");
		Assert.isTrue(Casino.settle(r.id, null) == null, "GUI end afterwards has no effect");
		Assert.eq(bal + win, EconomyService.balance(id), "not twice");
		// lost roll after a restart: pays nothing, stake stays in the Treasury
		Round lost = DiceGame.roll(id, name(a), 2_000, new Bet(true, 90), rolls(90)).round();
		long bal2 = EconomyService.balance(id);
		Casino.Settled sl = Casino.settle(lost.id, "server stop");
		Assert.eq(0L, sl.payout(), "lost stays lost");
		Assert.isFalse(sl.refund(), "no refund after a decided loss");
		Assert.eq(bal2, EconomyService.balance(id), "nothing paid");
		ctx.detail("decided rolls settled exactly once after restart or stop");
	}

	private static void guards(TestContext ctx) {
		CasinoConfig c = setup(ctx);
		FakePlayer a = funded(ctx, "DiceGuardA", 100_000_000);
		setTreasuryFree(1_000_000); // $10,000 free
		UUID id = a.getUUID();
		int edgeBp = DiceGame.cfg().edgeBp();
		long before = EconomyService.balance(id);
		// max bet ≤ 2 % of the Treasury
		long maxBet = Casino.maxBet();
		Assert.check(maxBet > 0 && maxBet <= Treasury.balance() / 50, "max bet ≤ 2 % of the Treasury");
		Casino.Start over = DiceGame.roll(id, name(a), maxBet + 1, new Bet(true, 50), rolls(99));
		Assert.isFalse(over.ok(), "above the max bet refused");
		Assert.eq(before, EconomyService.balance(id), "refused: nothing taken");
		Assert.isTrue(Casino.openRound(id) == null, "refused: no round");
		Casino.Start ok = DiceGame.roll(id, name(a), maxBet, new Bet(true, 50), rolls(1));
		Assert.isTrue(ok.ok(), "max bet on over 50 admitted: " + ok.error());
		// one round per player (the first is still running = animation)
		Casino.Start second = DiceGame.roll(id, name(a), c.minBet, new Bet(true, 50), rolls(99));
		Assert.isFalse(second.ok(), "second roll during the animation refused");
		Assert.eq(before - maxBet, EconomyService.balance(id), "only one stake taken");
		Casino.settle(ok.round().id, null);
		// coverage: Under 2 (×96) at max bet exceeds the maximum win
		Bet longshot = new Bet(false, 2);
		long potential = DiceMath.payout(maxBet, 1, edgeBp);
		Assert.check(potential > Casino.coverLimit(null), "precondition: ×96 not covered");
		long b1 = EconomyService.balance(id);
		Casino.Start uncovered = DiceGame.roll(id, name(a), maxBet, longshot, rolls(1));
		Assert.isFalse(uncovered.ok(), "uncovered win refused");
		Assert.check(uncovered.error().contains("cover"), "message names coverage: " + uncovered.error());
		Assert.eq(b1, EconomyService.balance(id), "refused: nothing taken");
		long maxLong = DiceGame.maxStake(id, longshot);
		Assert.check(maxLong > 0 && maxLong < maxBet, "max stake for ×96 smaller (" + maxLong + ")");
		Assert.check(DiceMath.payout(maxLong, 1, edgeBp) <= Casino.coverLimit(null), "max stake covered");
		Assert.check(DiceMath.payout(maxLong + 1, 1, edgeBp) > Casino.coverLimit(null), "one cent more not covered");
		Casino.Start edge = DiceGame.roll(id, name(a), maxLong, longshot, rolls(1));
		Assert.isTrue(edge.ok(), "max stake admitted: " + edge.error());
		long bigWin = DiceMath.payout(maxLong, 1, edgeBp);
		Assert.eq(bigWin, edge.round().payout, "roll 1 on under 2 wins ×96");
		Assert.eq(bigWin, Casino.settle(edge.round().id, null).payout(), "paid");
		Assert.eq(b1 - maxLong + bigWin, EconomyService.balance(id), "balance after ×96");
		// minimum bet, invalid targets, dice or casino off
		setTreasuryFree(100_000_000);
		long b2 = EconomyService.balance(id);
		Assert.isFalse(DiceGame.roll(id, name(a), c.minBet - 1, new Bet(true, 50), rolls(99)).ok(), "below the minimum bet refused");
		Assert.isFalse(DiceGame.roll(id, name(a), 1_000, new Bet(true, 100), rolls(99)).ok(), "over 100 refused");
		Assert.isFalse(DiceGame.roll(id, name(a), 1_000, new Bet(true, 3), rolls(99)).ok(), "over 3 (97 %) refused");
		Assert.isFalse(DiceGame.roll(id, name(a), 1_000, new Bet(false, 1), rolls(99)).ok(), "under 1 refused");
		DiceGame.cfg().enabled = false;
		Assert.isFalse(DiceGame.roll(id, name(a), 1_000, new Bet(true, 50), rolls(99)).ok(), "dice off: refused");
		DiceGame.cfg().enabled = true;
		c.enabled = false;
		Assert.isFalse(DiceGame.roll(id, name(a), 1_000, new Bet(true, 50), rolls(99)).ok(), "casino off: refused");
		c.enabled = true;
		EconomyService.set(Treasury.ID, 0);
		Assert.isFalse(DiceGame.roll(id, name(a), 1_000, new Bet(true, 50), rolls(99)).ok(), "empty Treasury: locked");
		Assert.eq(0L, DiceGame.maxStake(id, new Bet(true, 50)), "empty Treasury: max stake 0");
		Assert.eq(b2, EconomyService.balance(id), "refusals book nothing");
		// not enough money
		FakePlayer poor = funded(ctx, "DiceGuardB", 500);
		setTreasuryFree(100_000_000);
		Casino.Start broke = DiceGame.roll(poor.getUUID(), name(poor), 1_000, new Bet(true, 50), rolls(99));
		Assert.isFalse(broke.ok(), "not enough money refused");
		Assert.eq(500L, EconomyService.balance(poor.getUUID()), "nothing taken");
		ctx.detail("max bet, one round, coverage ×96 (max " + Money.format(maxLong) + "), minimum, targets, off, empty Treasury, balance");
	}

	private static void lossLimit(TestContext ctx) {
		CasinoConfig c = setup(ctx);
		c.dailyLossLimit = 100_000; // $1,000
		FakePlayer a = funded(ctx, "DiceLoss", 10_000_000);
		setTreasuryFree(100_000_000);
		UUID id = a.getUUID();
		for (int i = 0; i < 2; i++) {
			Round r = DiceGame.roll(id, name(a), 40_000, new Bet(true, 50), rolls(10)).round();
			Assert.notNull(r, "Round " + i);
			Casino.settle(r.id, null);
		}
		Assert.eq(80_000L, Casino.netLossToday(id), "dice losses count towards the daily limit");
		Casino.Start over = DiceGame.roll(id, name(a), 40_000, new Bet(true, 50), rolls(10));
		Assert.isFalse(over.ok(), "above the daily limit refused");
		Assert.check(over.error().toLowerCase(java.util.Locale.ROOT).contains("limit"), "daily limit message: " + over.error());
		Assert.eq(20_000L, DiceGame.maxStake(id, new Bet(true, 50)), "max stake = rest of the limit");
		Round last = DiceGame.roll(id, name(a), 20_000, new Bet(true, 50), rolls(60)).round();
		Assert.notNull(last, "remaining stake allowed");
		long win = last.payout;
		Assert.check(win > 20_000, "won");
		Casino.settle(last.id, null);
		Assert.eq(100_000L - win, Casino.netLossToday(id), "win lowers the daily loss");
		ctx.detail("daily limit applies to dice, wins offset it");
	}

	/**
	 * If the game throws while determining the result (or returns nonsense), the player gets the stake back at once.
	 */
	private static void outcomeFailure(TestContext ctx) {
		setup(ctx);
		FakePlayer a = funded(ctx, "DiceFail", 10_000_000);
		setTreasuryFree(100_000_000);
		UUID id = a.getUUID();
		long b0 = EconomyService.balance(id);
		long t0 = Treasury.balance();
		long reserved0 = Casino.reservedTotal(null);
		Casino.Start thrown = Casino.startInstant(id, name(a), DiceGame.GAME, 1_000, s -> s * 2, r -> {
			throw new IllegalStateException("selftest");
		});
		Assert.isFalse(thrown.ok(), "error reported");
		Assert.eq(b0, EconomyService.balance(id), "stake refunded");
		Assert.eq(t0, Treasury.balance(), "Treasury unchanged");
		Assert.isTrue(Casino.openRound(id) == null, "no open round");
		Casino.Start tooMuch = Casino.startInstant(id, name(a), DiceGame.GAME, 1_000, s -> s * 2, r -> r.stake * 3);
		Assert.isFalse(tooMuch.ok(), "payout above the maximum refused");
		Assert.eq(b0, EconomyService.balance(id), "refunded");
		Assert.eq(reserved0, Casino.reservedTotal(null), "no reservation left");
		CasinoData.PlayerStats ps = Casino.peekStats(id);
		Assert.isTrue(ps == null || !ps.games.containsKey(DiceGame.GAME_ID), "refunds do not count as rounds");
		Casino.InstantGame builtin = new Casino.InstantGame("slots", "x", net.minecraft.world.item.Items.PAPER, s -> s);
		Assert.assertThrows(IllegalArgumentException.class, () -> Casino.registerGame(builtin), "built-in game id refused");
		Assert.assertThrows(IllegalArgumentException.class, () -> Casino.startInstant(id, name(a), builtin, 1_000, s -> s, r -> 0L),
			"unregistered game refused");
		Assert.eq(b0, EconomyService.balance(id), "nothing taken");
		ctx.detail("error in the game: stake back, nothing reserved");
	}

	/** Player messages are plain English: no accented letters. */
	private static final Pattern NON_ENGLISH = Pattern.compile("[\\u00C0-\\u00D6\\u00D8-\\u00F6\\u00F8-\\u00FF]");

	private static void english(String text, String expected, String what) {
		Assert.notNull(text, what + ": message present");
		Assert.check(text.contains(expected), what + ": \"" + expected + "\" missing in: " + text);
		Assert.isFalse(NON_ENGLISH.matcher(text).find(), what + ": non-English text: " + text);
	}

	/** Last message to the offline test player; the casino delivers it as soon as they join. */
	private static String lastMessage(UUID id, int sizeBefore) {
		List<String> all = PlayerDataService.getPendingMessages(id);
		Assert.eq(sizeBefore + 1, all.size(), "exactly one new message");
		return all.get(all.size() - 1);
	}

	/**
	 * Dice players get all casino messages in English and with the game name "Dice" (not "Casino"): admission (maximum,
	 * minimum, one round, coverage, daily limit, balance) and settlement (win, loss, refund, reason).
	 */
	private static void englishMessages(TestContext ctx) {
		CasinoConfig c = setup(ctx);
		FakePlayer a = funded(ctx, "DiceEnglish", 100_000_000);
		setTreasuryFree(1_000_000); // $10,000 free
		UUID id = a.getUUID();
		Assert.eq(DiceGame.LABEL, Casino.gameLabel(DiceGame.GAME_ID), "dice registered (label §eDice)");
		Assert.eq("Casino", Casino.gameLabel("not-registered"), "unknown game: Casino");
		Assert.assertThrows(IllegalArgumentException.class,
			() -> Casino.registerGame(new Casino.InstantGame("slots", "x", net.minecraft.world.item.Items.PAPER, s -> s)), "built-in id refused");
		// admission
		long maxBet = Casino.maxBet();
		english(DiceGame.roll(id, name(a), maxBet + 1, new Bet(true, 50), rolls(99)).error(), "Maximum bet", "above the maximum");
		english(DiceGame.roll(id, name(a), c.minBet - 1, new Bet(true, 50), rolls(99)).error(), "Minimum bet", "below the minimum");
		english(DiceGame.roll(id, name(a), maxBet, new Bet(false, 2), rolls(99)).error(), "can't cover", "coverage");
		int n = PlayerDataService.getPendingMessages(id).size();
		Casino.Start running = DiceGame.roll(id, name(a), 10_000, new Bet(true, 50), rolls(77));
		Assert.isTrue(running.ok(), "roll: " + running.error());
		english(DiceGame.roll(id, name(a), 10_000, new Bet(true, 50), rolls(99)).error(), "already playing", "second round");
		// win with a reason (GUI closed)
		long win = running.round().payout;
		Casino.settle(running.round().id, Casino.CLOSED_REASON);
		String won = lastMessage(id, n++);
		english(won, "§aWon " + Msg.moneyStr(win), "win message");
		Assert.check(won.startsWith(DiceGame.LABEL + "§7: "), "win message starts with Dice: " + won);
		Assert.check(won.contains("bet " + Money.format(10_000)) && won.endsWith("(window closed)"), "stake + reason: " + won);
		// loss
		Round lose = DiceGame.roll(id, name(a), 10_000, new Bet(true, 50), rolls(10)).round();
		Assert.notNull(lose, "losing roll");
		Casino.settle(lose.id, null);
		String lost = lastMessage(id, n++);
		english(lost, "§cLost", "loss message");
		Assert.check(lost.startsWith(DiceGame.LABEL + "§7: "), "loss message starts with Dice: " + lost);
		// refund (error while determining the result)
		Casino.Start failed = Casino.startInstant(id, name(a), DiceGame.GAME, 1_000, s -> s * 2, r -> {
			throw new IllegalStateException("selftest");
		});
		english(failed.error(), "refunded", "error message");
		String refund = lastMessage(id, n++);
		english(refund, "Round cancelled", "refund message");
		Assert.check(refund.startsWith(DiceGame.LABEL + "§7: "), "refund starts with Dice: " + refund);
		// daily limit and balance
		c.dailyLossLimit = 1_000;
		english(DiceGame.roll(id, name(a), 2_000, new Bet(true, 50), rolls(99)).error(), "Daily loss limit", "daily limit");
		c.dailyLossLimit = new CasinoConfig().dailyLossLimit;
		FakePlayer poor = funded(ctx, "DiceEnglishB", 500);
		english(DiceGame.roll(poor.getUUID(), name(poor), 1_000, new Bet(true, 50), rolls(99)).error(), "Not enough money", "not enough money");
		c.enabled = false;
		english(DiceGame.roll(id, name(a), 1_000, new Bet(true, 50), rolls(99)).error(), "closed", "casino off");
		c.enabled = true;
		EconomyService.set(Treasury.ID, 0);
		english(DiceGame.roll(id, name(a), 1_000, new Bet(true, 50), rolls(99)).error(), "locked", "empty Treasury");
		ctx.detail("admission, win, loss and refund with label Dice, all English");
	}
}
