package dev.smpeconomy.features.casino.games.crash;

import java.time.LocalDate;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
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
import dev.smpeconomy.features.casino.Game;
import dev.smpeconomy.features.casino.games.crash.CrashData.Round;

/**
 * Self-tests of crash ({@code /smpeco selftest casino-crash}, no GUI): RTP exactly and by simulation (200k rounds through
 * the tick engine), curve/phases, casino guards (max bet, coverage, daily loss limit), crash point stored before the start,
 * exactly-once settlement incl. restart and pending payouts, money conservation over many rounds.
 */
final class CrashSelfTests {
	private static final String F = CrashGame.SELFTEST_FEATURE;
	private static final int SIM_ROUNDS = 100_000;
	private static final long[] SIM_TARGETS = {101, 110, 125, 150, 200, 300, 500, 1_000};

	private CrashSelfTests() {}

	static void register() {
		SelfTests.register(F, "rtp-exact", CrashSelfTests::rtpExact);
		SelfTests.register(F, "rtp-simulation", CrashSelfTests::rtpSimulation);
		SelfTests.register(F, "curve-and-phases", CrashSelfTests::curveAndPhases);
		SelfTests.register(F, "config-validation", CrashSelfTests::configValidation);
		SelfTests.register(F, "max-bet-guard", CrashSelfTests::maxBetGuard);
		SelfTests.register(F, "coverage-guard", CrashSelfTests::coverageGuard);
		SelfTests.register(F, "loss-limit", CrashSelfTests::lossLimit);
		SelfTests.register(F, "crash-point-before-start", CrashSelfTests::crashPointBeforeStart);
		SelfTests.register(F, "exactly-once", CrashSelfTests::exactlyOnce);
		SelfTests.register(F, "restart-recovery", CrashSelfTests::restartRecovery);
		SelfTests.register(F, "pending-payout", CrashSelfTests::pendingPayout);
		SelfTests.register(F, "conservation", CrashSelfTests::conservation);
		SelfTests.register(F, "one-round-per-player", CrashSelfTests::oneRound);
		SelfTests.register(F, "big-win-threshold", CrashSelfTests::bigWin);
	}

	/** Random source that makes the next round crash exactly at {@code crashH} (default edge 4 %). */
	static RandomGenerator crashAt(long crashH) {
		long num = (10_000L - 400) << 48;
		long k = crashH <= CrashMath.ONE ? (1L << 48) - 1 : num / (100L * crashH) - 1;
		Assert.eq(crashH, CrashMath.crashPointFor(k, 400, 10_000), "scripted crash point " + crashH);
		long raw = k << 16;
		return new RandomGenerator() {
			@Override
			public long nextLong() {
				return raw;
			}
		};
	}

	/** Always wins a casino coin flip. */
	private static RandomGenerator coinflipWins() {
		return new RandomGenerator() {
			@Override
			public long nextLong() {
				return 0;
			}

			@Override
			public int nextInt(int bound) {
				return 0;
			}
		};
	}

	/** Always loses a casino coin flip. */
	private static RandomGenerator coinflipLoses() {
		return new RandomGenerator() {
			@Override
			public long nextLong() {
				return 0;
			}

			@Override
			public int nextInt(int bound) {
				return bound - 1;
			}
		};
	}

	/** Snapshot of the casino settings these tests change (CasinoConfig has public fields only). */
	private record CasinoSettings(boolean enabled, long minBet, long maxBet, double maxBetPct, double maxWinPct, long lossLimit,
		long announceProfit, double announceMult, long announceMultPayout) {
		static CasinoSettings of(CasinoConfig c) {
			return new CasinoSettings(c.enabled, c.minBet, c.maxBet, c.maxBetTreasuryPercent, c.maxWinTreasuryPercent, c.dailyLossLimit,
				c.announceMinProfit, c.announceMinMultiplier, c.announceMinPayoutForMultiplier);
		}

		void apply(CasinoConfig c) {
			c.enabled = enabled;
			c.minBet = minBet;
			c.maxBet = maxBet;
			c.maxBetTreasuryPercent = maxBetPct;
			c.maxWinTreasuryPercent = maxWinPct;
			c.dailyLossLimit = lossLimit;
			c.announceMinProfit = announceProfit;
			c.announceMinMultiplier = announceMult;
			c.announceMinPayoutForMultiplier = announceMultPayout;
		}
	}

	/** Casino + crash config on defaults, treasury/clock/day saved; everything is restored after the test. */
	private static CrashConfig setup(TestContext ctx) {
		ctx.preserveTreasury();
		CasinoConfig casino = Casino.cfg();
		CasinoSettings savedCasino = CasinoSettings.of(casino);
		CrashConfig live = Crash.cfg();
		CrashConfig saved = live.copy();
		Supplier<LocalDate> savedToday = Crash.today;
		LongSupplier savedClock = Crash.clock;
		ctx.cleanup(() -> {
			savedCasino.apply(Casino.cfg());
			live.copyFrom(saved);
			Crash.today = savedToday;
			Crash.clock = savedClock;
		});
		CasinoSettings.of(new CasinoConfig()).apply(casino);
		live.copyFrom(new CrashConfig());
		return live;
	}

	/** Treasury so that exactly {@code free} is left after all casino and crash reservations (the casino counts both). */
	private static long setTreasuryFree(long free) {
		long total = Math.addExact(free, Casino.reservedTotal(null));
		EconomyService.set(Treasury.ID, total);
		return total;
	}

	private static FakePlayer funded(TestContext ctx, String name, long cents) {
		FakePlayer p = ctx.player(name);
		EconomyService.set(p.getUUID(), cents);
		return p;
	}

	private static String name(FakePlayer p) {
		return p.getGameProfile().name();
	}

	/** Flies the round tick by tick until the shown multiplier reaches {@code untilH} or the round is decided. */
	private static CrashMath.Phase fly(Round r, long untilH) {
		for (long t = 0; t < 1_000_000; t++) {
			CrashMath.Phase p = Crash.advance(r, t);
			Assert.notNull(p, "round open while flying");
			if (p != CrashMath.Phase.RUNNING || r.lastMultH >= untilH) return p;
		}
		throw new AssertionError("flight did not end");
	}

	private static Round start(FakePlayer p, long stake, long autoH, RandomGenerator rng) {
		Crash.Start s = Crash.start(p.getUUID(), name(p), stake, autoH, rng);
		Assert.isTrue(s.ok(), "round admitted: " + s.error());
		return s.round();
	}

	private static String fmt(double d) {
		return String.format(java.util.Locale.ROOT, "%.4f", d);
	}

	private static void rtpExact(TestContext ctx) {
		long cap = 10_000;
		for (int edgeBp : new int[] {300, 400, 500}) {
			double want = (10_000 - edgeBp) / 10_000.0;
			for (long a = 101; a <= cap; a++) {
				double rtp = CrashMath.rtp(a, edgeBp, cap);
				if (Math.abs(rtp - want) > 1e-9) Assert.fail("edge " + edgeBp + ": RTP at " + CrashMath.format(a) + " = " + rtp + ", expected " + want);
			}
		}
		Assert.check(Math.abs(CrashMath.rtp(cap, 400, cap) - 0.96) < 1e-9, "riding to the cap (×100) pays 96 % as well");
		double instant = 1 - CrashMath.survival(101, 400, cap);
		Assert.check(instant > 0.049 && instant < 0.050, "instant crash at ×1.00 ≈ 4.95 % (" + fmt(instant) + ")");
		Assert.eq(100L, CrashMath.crashPointFor((1L << 48) - 1, 400, cap), "smallest draw → ×1.00");
		Assert.eq(cap, CrashMath.crashPointFor(0, 400, cap), "largest draw → cap");
		Assert.eq(0.0, CrashMath.survival(cap + 1, 400, cap), "nothing beyond the cap");
		ctx.detail("RTP exactly 97/96/95 % for every target ×1.01–×100 at edge 3/4/5 %, instant crash " + fmt(instant));
	}

	private static void rtpSimulation(TestContext ctx) {
		int edgeBp = 400;
		long cap = 10_000;
		double dbl = 5.0;
		SplittableRandom rng = new SplittableRandom(20251010L);
		// the engine's curve per tick (same values as Crash.advance uses), long enough to reach the cap
		long[] curve = new long[(int) CrashMath.ticksUntil(cap, dbl) + 2];
		for (int t = 0; t < curve.length; t++) curve[t] = CrashMath.multiplierAt(t, dbl);
		Assert.check(curve[curve.length - 1] >= cap, "curve reaches the cap");
		// A) auto cash-out at random targets, through the tick engine
		double sum = 0;
		double sumSq = 0;
		double expected = 0;
		for (int i = 0; i < SIM_ROUNDS; i++) {
			long target = SIM_TARGETS[rng.nextInt(SIM_TARGETS.length)];
			long crash = CrashMath.crashPoint(rng, edgeBp, cap);
			double x = 0;
			for (int t = 0; ; t++) {
				CrashMath.Phase p = CrashMath.phase(crash, target, curve[t]);
				if (p == CrashMath.Phase.RUNNING) continue;
				if (p == CrashMath.Phase.CASHED) x = target / 100.0;
				break;
			}
			sum += x;
			sumSq += x * x;
			expected += CrashMath.rtp(target, edgeBp, cap);
		}
		check("auto cash-out", sum, sumSq, expected, SIM_ROUNDS);
		double autoRtp = sum / SIM_ROUNDS;
		// B) manual cash-out: the player clicks as soon as the shown multiplier is ≥ x (paid at the shown value)
		double sum2 = 0;
		double sumSq2 = 0;
		double expected2 = 0;
		// the multiplier the player clicks at: first shown value ≥ the wanted one (the curve jumps in whole ticks)
		long[] clickAt = new long[SIM_TARGETS.length];
		for (int j = 0; j < SIM_TARGETS.length; j++) {
			int t = 0;
			while (curve[t] < SIM_TARGETS[j]) t++;
			clickAt[j] = curve[t];
		}
		for (int i = 0; i < SIM_ROUNDS; i++) {
			int j = rng.nextInt(SIM_TARGETS.length);
			long want = SIM_TARGETS[j];
			long shownAt = clickAt[j];
			long crash = CrashMath.crashPoint(rng, edgeBp, cap);
			double x = 0;
			for (int t = 0; ; t++) {
				long m = curve[t];
				CrashMath.Phase p = CrashMath.phase(crash, cap, m);
				if (p == CrashMath.Phase.CRASHED) break;
				if (p == CrashMath.Phase.CASHED) {
					x = cap / 100.0;
					break;
				}
				if (m >= want) {
					Assert.eq(shownAt, m, "click value");
					x = m / 100.0;
					break;
				}
			}
			sum2 += x;
			sumSq2 += x * x;
			expected2 += CrashMath.rtp(shownAt, edgeBp, cap);
		}
		check("manual cash-out", sum2, sumSq2, expected2, SIM_ROUNDS);
		double manualRtp = sum2 / SIM_ROUNDS;
		Assert.check(autoRtp >= 0.945 && autoRtp <= 0.975, "simulated auto RTP " + fmt(autoRtp) + " in house-edge band");
		Assert.check(manualRtp >= 0.945 && manualRtp <= 0.975, "simulated manual RTP " + fmt(manualRtp) + " in house-edge band");
		ctx.detail((2 * SIM_ROUNDS) + " rounds: auto " + fmt(autoRtp) + " (theory " + fmt(expected / SIM_ROUNDS) + "), manual "
			+ fmt(manualRtp) + " (theory " + fmt(expected2 / SIM_ROUNDS) + ")");
	}

	private static void check(String what, double sum, double sumSq, double expectedSum, int n) {
		double mean = sum / n;
		double want = expectedSum / n;
		double var = Math.max(0, sumSq / n - mean * mean);
		double tol = 4 * Math.sqrt(var / n) + 0.0005;
		Assert.check(Math.abs(mean - want) <= tol, what + ": RTP " + fmt(mean) + " differs from " + fmt(want) + " (tolerance " + fmt(tol) + ")");
		Assert.check(want >= 0.95 && want <= 0.97, what + ": theoretical RTP " + fmt(want) + " not in [0.95, 0.97]");
	}

	private static void curveAndPhases(TestContext ctx) {
		Assert.eq(100L, CrashMath.multiplierAt(0, 5.0), "start ×1.00");
		Assert.eq(200L, CrashMath.multiplierAt(100, 5.0), "×2.00 after 5 s");
		Assert.eq(400L, CrashMath.multiplierAt(200, 5.0), "×4.00 after 10 s");
		long prev = 0;
		for (int t = 0; t < 2_000; t++) {
			long m = CrashMath.multiplierAt(t, 5.0);
			Assert.check(m >= prev, "curve monotone at tick " + t);
			prev = m;
		}
		for (long a : new long[] {101, 150, 777, 10_000}) {
			Assert.check(CrashMath.multiplierAt(CrashMath.ticksUntil(a, 5.0), 5.0) >= a, "ticksUntil(" + a + ") reaches it");
		}
		// phases: shown value = crash point is still cashable; past it → crash; auto target wins even if jumped over
		Assert.eq(CrashMath.Phase.RUNNING, CrashMath.phase(150, 10_000, 150), "m = crash point still flying");
		Assert.eq(CrashMath.Phase.CRASHED, CrashMath.phase(150, 10_000, 151), "m > crash point → crash");
		Assert.eq(CrashMath.Phase.CASHED, CrashMath.phase(300, 200, 210), "auto ×2 reached below crash point");
		Assert.eq(CrashMath.Phase.CASHED, CrashMath.phase(200, 200, 230), "auto = crash point wins");
		Assert.eq(CrashMath.Phase.CRASHED, CrashMath.phase(199, 200, 230), "auto above crash point loses");
		Assert.eq(CrashMath.Phase.CASHED, CrashMath.phase(10_000, 10_000, 10_050), "cap reached → paid at the cap");
		Assert.eq(CrashMath.Phase.RUNNING, CrashMath.phase(100, 10_000, 100), "instant crash: ×1.00 shown first");
		Assert.eq(CrashMath.Phase.CRASHED, CrashMath.phase(100, 10_000, 101), "instant crash: gone at ×1.01");
		Assert.eq(10_000L, CrashMath.target(0, 10_000), "no auto → cap");
		Assert.eq(250L, CrashMath.target(250, 10_000), "auto below cap");
		Assert.eq(10_000L, CrashMath.target(50_000, 10_000), "auto never above cap");
		Assert.eq(1_500L, CrashMath.payout(1_000, 150), "payout ×1.50");
		Assert.eq(336L, CrashMath.payout(333, 101), "payout rounds down");
		Assert.eq(Money.MAX * 100, CrashMath.payout(Money.MAX, 10_000), "no overflow (×100 on the largest amount)");
		Assert.eq(200L, CrashMath.parseMultiplier("2"), "parse 2");
		Assert.eq(250L, CrashMath.parseMultiplier("2.5"), "parse 2.5");
		Assert.eq(125L, CrashMath.parseMultiplier("x1.25"), "parse x1.25");
		Assert.eq(1_000L, CrashMath.parseMultiplier("10x"), "parse 10x");
		Assert.eq(-1L, CrashMath.parseMultiplier("1.234"), "three decimals invalid");
		Assert.eq(-1L, CrashMath.parseMultiplier("abc"), "text invalid");
		Assert.eq("×12.34", CrashMath.format(1_234), "format");
		Assert.eq("×1.05", CrashMath.format(105), "format leading zero");
		Assert.eq(5_000L, CrashMath.maxStakeFor(500_000, 10_000), "max stake for cover at ×100");
		ctx.detail("curve ×2/5 s monotone, phase rules, payout/parse/format");
	}

	private static void configValidation(TestContext ctx) {
		new CrashConfig().validate();
		for (double edge : new double[] {2.9, 5.1, Double.NaN}) {
			CrashConfig c = new CrashConfig();
			c.houseEdgePercent = edge;
			Assert.assertThrows(IllegalArgumentException.class, c::validate, "house edge " + edge + " rejected");
		}
		CrashConfig m = new CrashConfig();
		m.maxMultiplier = 1.5;
		Assert.assertThrows(IllegalArgumentException.class, m::validate, "max multiplier < 2 rejected");
		CrashConfig d = new CrashConfig();
		d.doublingSeconds = 0.5;
		Assert.assertThrows(IllegalArgumentException.class, d::validate, "doubling < 1 s rejected");
		Assert.eq(400, new CrashConfig().edgeBp(), "default edge 4 %");
		Assert.eq(10_000L, new CrashConfig().capH(), "default cap ×100");
	}

	private static void maxBetGuard(TestContext ctx) {
		setup(ctx);
		FakePlayer a = funded(ctx, "CrashMaxBet", 100_000_000);
		setTreasuryFree(1_000_000_000L); // $10M
		UUID id = a.getUUID();
		long maxBet = Casino.maxBet();
		Assert.eq(Math.min(Casino.cfg().maxBet, 20_000_000L), maxBet, "max bet = min(config, 2 % of the treasury)");
		Crash.Start over = Crash.start(id, name(a), maxBet + 1, 200, crashAt(500));
		Assert.isFalse(over.ok(), "above the max bet refused");
		Assert.check(over.error().contains("Maximum bet"), "message names the max bet: " + over.error());
		Assert.eq(100_000_000L, EconomyService.balance(id), "nothing taken");
		Round r = start(a, maxBet, 200, crashAt(500));
		Assert.eq(100_000_000L - maxBet, EconomyService.balance(id), "bet taken");
		Assert.eq(CrashMath.Phase.CASHED, fly(r, Long.MAX_VALUE), "auto ×2 below crash point ×5");
		Assert.eq(maxBet * 2, Crash.settle(r.id, null).payout(), "paid ×2");
		// tiny treasury: 2 % below the minimum bet → locked
		setTreasuryFree(Casino.cfg().minBet * 10);
		Crash.Start locked = Crash.start(id, name(a), Casino.cfg().minBet, 0, crashAt(500));
		Assert.isFalse(locked.ok(), "locked with a tiny treasury");
		Assert.eq(0L, Crash.maxStake(id, 0), "max stake 0 when locked");
		// casino closed → crash closed
		setTreasuryFree(1_000_000_000L);
		Casino.cfg().enabled = false;
		Assert.isFalse(Crash.start(id, name(a), 1_000, 0, crashAt(500)).ok(), "casino off → crash off");
		Casino.cfg().enabled = true;
		Crash.cfg().enabled = false;
		Assert.isFalse(Crash.start(id, name(a), 1_000, 0, crashAt(500)).ok(), "crash off");
		Crash.cfg().enabled = true;
		Assert.isFalse(Crash.start(id, name(a), 1_000, 100, crashAt(500)).ok(), "auto ×1.00 refused");
		Assert.isFalse(Crash.start(id, name(a), 1_000, 10_001, crashAt(500)).ok(), "auto above the cap refused");
		Assert.isFalse(Crash.start(id, name(a), Casino.cfg().minBet - 1, 0, crashAt(500)).ok(), "below the minimum bet refused");
		ctx.detail("max bet " + Money.format(maxBet) + " enforced, locked/closed/auto range");
	}

	private static void coverageGuard(TestContext ctx) {
		setup(ctx);
		Casino.cfg().maxWinTreasuryPercent = 100; // cover = free treasury (makes the reservation visible)
		FakePlayer a = funded(ctx, "CrashCoverA", 10_000_000);
		FakePlayer b = funded(ctx, "CrashCoverB", 10_000_000);
		FakePlayer c = funded(ctx, "CrashCoverC", 10_000_000);
		setTreasuryFree(1_000_000); // $10,000
		Assert.eq(1_000_000L, Crash.coverLimit(), "cover = free treasury");
		Assert.eq(10_000L, Crash.maxStake(a.getUUID(), 0), "no auto: max stake = cover / ×100");
		Assert.eq(20_000L, Crash.maxStake(a.getUUID(), 200), "auto ×2: limited by the 2 % max bet only");
		Crash.Start tooBig = Crash.start(a.getUUID(), name(a), 10_001, 0, crashAt(500));
		Assert.isFalse(tooBig.ok(), "potential above the cover refused");
		Assert.check(tooBig.error().contains("can't cover"), "coverage message: " + tooBig.error());
		Round ra = start(a, 5_000, 0, crashAt(500));
		Assert.eq(500_000L, ra.reserved, "reserves bet × cap");
		// one cover for the whole casino: the house games see the crash reservation (the bet went into the treasury)
		Assert.eq(1_000_000L + 5_000 - 500_000, Casino.coverLimit(null), "house-game cover = free treasury − crash reservation");
		Assert.eq(Casino.coverLimit(null), Crash.coverLimit(), "crash uses the same cover (nothing counted twice)");
		long cover = Crash.coverLimit();
		Assert.isFalse(Crash.start(b.getUUID(), name(b), cover / 100 + 1, 0, crashAt(500)).ok(), "second round above the rest refused");
		Round rb = start(b, cover / 100, 0, crashAt(500));
		long rest = Casino.coverLimit(null);
		Assert.eq(cover / 100, rest, "rest after two crash rounds = second bet");
		// a house game whose potential exceeds what the crash rounds left is refused, nothing taken
		Assert.check(Casino.potential(Game.MINES, 10_000, 3) > rest, "precondition: mines potential above the rest");
		Casino.Start mines = Casino.startMines(c.getUUID(), name(c), 10_000, 3, new SplittableRandom(1));
		Assert.isFalse(mines.ok(), "house game refused while crash reservations bind the treasury");
		Assert.check(mines.error().contains("can't cover"), "coverage message: " + mines.error());
		Assert.eq(10_000_000L, EconomyService.balance(c.getUUID()), "nothing taken");
		Assert.eq(rest / 2, Casino.maxStake(c.getUUID(), Game.COINFLIP, 0), "house-game max stake respects the crash reservations (coin flip pays 2×)");
		Crash.settle(rb.id, null);
		Crash.settle(ra.id, null);
		Assert.eq(0L, Crash.reservedTotal(), "reservations released");
		// and the other way round: house-game reservations bind crash (won coin flip: pot 2× bet reserved, bet added)
		long before = Crash.coverLimit();
		Casino.Start coin = Casino.startCoinflip(c.getUUID(), name(c), 10_000, false, coinflipWins());
		Assert.isTrue(coin.ok(), "coin flip admitted: " + coin.error());
		Assert.eq(20_000L, coin.round().reserved, "pot reserved");
		Assert.eq(before + 10_000 - 20_000, Crash.coverLimit(), "crash cover = free treasury − house reservation");
		Casino.settle(coin.round().id, null);
		ctx.detail("cover " + Money.format(1_000_000) + ", one cover for crash and house games in both directions");
	}

	private static void lossLimit(TestContext ctx) {
		setup(ctx);
		Casino.cfg().dailyLossLimit = 100_000; // $1,000
		FakePlayer a = funded(ctx, "CrashLoss", 10_000_000);
		setTreasuryFree(100_000_000);
		UUID id = a.getUUID();
		Assert.eq(100_000L, Crash.lossRoom(id), "full room");
		Round r = start(a, 60_000, 0, crashAt(100));
		Assert.eq(CrashMath.Phase.CRASHED, fly(r, Long.MAX_VALUE), "instant crash");
		Assert.eq(0L, Crash.settle(r.id, null).payout(), "lost");
		Assert.eq(60_000L, Crash.netLossToday(id), "crash loss booked");
		Assert.eq(40_000L, Crash.lossRoom(id), "room 40k");
		Crash.Start over = Crash.start(id, name(a), 50_000, 0, crashAt(500));
		Assert.isFalse(over.ok(), "above the remaining room refused");
		Assert.check(over.error().contains("Daily loss limit"), "message: " + over.error());
		// one limit for the whole casino: the house games see the crash loss too
		Assert.eq(60_000L, Casino.netLossToday(id), "casino net loss includes crash");
		Assert.eq(40_000L, Casino.lossRoom(id), "house-game room 40k");
		long balance = EconomyService.balance(id);
		Casino.Start houseOver = Casino.startCoinflip(id, name(a), 50_000, false, coinflipLoses());
		Assert.isFalse(houseOver.ok(), "house game above the remaining room refused");
		Assert.check(houseOver.error().contains("Daily loss limit"), "message: " + houseOver.error());
		Assert.eq(balance, EconomyService.balance(id), "nothing taken");
		// house-game losses count for crash too
		Casino.Start coin = Casino.startCoinflip(id, name(a), 10_000, false, coinflipLoses());
		Assert.isTrue(coin.ok(), "coin flip admitted: " + coin.error());
		Casino.settle(coin.round().id, null);
		Assert.eq(70_000L, Casino.netLossToday(id), "combined net loss: 60k crash + 10k coin flip");
		Assert.eq(30_000L, Crash.lossRoom(id), "combined: 100k − 60k − 10k");
		Assert.eq(Casino.lossRoom(id), Crash.lossRoom(id), "same room for crash and house games");
		// a win lowers the net loss, a refund does not count
		Round w = start(a, 20_000, 200, crashAt(300));
		Assert.eq(CrashMath.Phase.CASHED, fly(w, Long.MAX_VALUE), "auto ×2");
		Crash.settle(w.id, null);
		Assert.eq(40_000L, Crash.netLossToday(id), "60k − 20k profit");
		Assert.eq(50_000L, Crash.lossRoom(id), "room 50k");
		Round x = start(a, 30_000, 0, crashAt(500));
		Crash.Settled refunded = Crash.settle(x.id, "window closed");
		Assert.isTrue(refunded.refund(), "closed at ×1.00 → refund");
		Assert.eq(40_000L, Crash.netLossToday(id), "refund not counted");
		// personal limit (casino) applies
		Casino.setPersonalLimit(id, 20_000);
		Assert.eq(0L, Crash.lossRoom(id), "personal limit 20k already used");
		Assert.isFalse(Crash.start(id, name(a), 1_000, 0, crashAt(500)).ok(), "refused at the personal limit");
		// admin reset (/smpeco casino resetlimit) resets the crash day as well
		Casino.resetLimits(id);
		Assert.eq(0L, Crash.netLossToday(id), "crash day reset with the casino limits");
		Assert.eq(0L, Casino.netLossToday(id), "casino day reset");
		Assert.eq(100_000L, Crash.lossRoom(id), "full room after the reset");
		// … also for a player who only played crash (no casino stats)
		FakePlayer b = funded(ctx, "CrashLossB", 10_000_000);
		UUID idB = b.getUUID();
		Round rb = start(b, 30_000, 0, crashAt(100));
		fly(rb, Long.MAX_VALUE);
		Crash.settle(rb.id, null);
		Assert.isTrue(Casino.peekStats(idB) == null, "precondition: no casino stats");
		Assert.eq(30_000L, Casino.netLossToday(idB), "crash-only loss seen by the casino");
		Assert.eq(70_000L, Casino.lossRoom(idB), "house-game room of a crash-only player");
		Casino.resetLimits(idB);
		Assert.eq(0L, Crash.netLossToday(idB), "crash-only player reset");
		// next day: crash part resets
		Round y = start(a, 10_000, 0, crashAt(100));
		fly(y, Long.MAX_VALUE);
		Crash.settle(y.id, null);
		Assert.eq(10_000L, Crash.netLossToday(id), "loss booked again");
		Crash.today = () -> LocalDate.now().plusDays(5);
		Assert.eq(0L, Crash.netLossToday(id), "new day");
		ctx.detail("one casino-wide loss limit in both directions, wins/refunds, personal limit, admin reset");
	}

	private static void crashPointBeforeStart(TestContext ctx) {
		setup(ctx);
		FakePlayer a = funded(ctx, "CrashStored", 1_000_000);
		setTreasuryFree(100_000_000);
		Round r = start(a, 1_000, 0, crashAt(345));
		Assert.isTrue(Crash.data().rounds.get(r.id) == r, "round in the store");
		Assert.eq(345L, r.crashH, "crash point drawn at the start");
		Assert.eq(CrashData.PLAYING, r.state, "flying");
		Assert.eq(100L, r.lastMultH, "nothing shown yet");
		Assert.isTrue(Crash.STORE.isDirty(), "store marked for the next save (same commit as the bet)");
		CrashData copy = DataStore.gson().fromJson(DataStore.gson().toJson(Crash.data()), CrashData.class);
		copy.fixNulls();
		Assert.eq(345L, copy.rounds.get(r.id).crashH, "crash point survives save/load");
		Assert.isFalse(Crash.canCashOut(r), "no cash-out at ×1.00");
		Assert.isFalse(Crash.cashOut(r), "cash-out at ×1.00 refused");
		Crash.settle(r.id, null);
		ctx.detail("crash point stored before the first tick");
	}

	private static void exactlyOnce(TestContext ctx) {
		setup(ctx);
		FakePlayer a = funded(ctx, "CrashOnce", 1_000_000);
		setTreasuryFree(100_000_000);
		UUID id = a.getUUID();
		long b0 = EconomyService.balance(id);
		// auto ×1.50, crash ×3 → paid ×1.50 once
		Round auto = start(a, 1_000, 150, crashAt(300));
		Assert.eq(CrashMath.Phase.CASHED, fly(auto, Long.MAX_VALUE), "auto cash-out");
		Assert.eq(CrashData.DECIDED, auto.state, "decided before booking");
		Assert.isTrue(Crash.advance(auto, 9_999) == null, "decided round does not fly on");
		Assert.eq(1_500L, Crash.settle(auto.id, null).payout(), "×1.50");
		Assert.isTrue(Crash.settle(auto.id, null) == null, "second settle does nothing");
		Assert.eq(b0 + 500, EconomyService.balance(id), "paid exactly once");
		// manual cash-out at ≥ ×1.20 (crash ×2.5)
		Round manual = start(a, 1_000, 0, crashAt(250));
		Assert.eq(CrashMath.Phase.RUNNING, fly(manual, 120), "still flying at ×1.20");
		long m = manual.lastMultH;
		Assert.isTrue(Crash.cashOut(manual), "cash-out");
		Assert.isFalse(Crash.cashOut(manual), "second cash-out refused");
		Assert.eq(CrashMath.payout(1_000, m), Crash.settle(manual.id, null).payout(), "paid at the shown multiplier");
		Assert.isTrue(Crash.settle(manual.id, null) == null, "second settle does nothing");
		// crash ×1.30 without auto → lost
		Round lost = start(a, 1_000, 0, crashAt(130));
		Assert.eq(CrashMath.Phase.CRASHED, fly(lost, Long.MAX_VALUE), "crashed");
		Assert.isFalse(Crash.cashOut(lost), "no cash-out after the crash");
		Assert.eq(0L, Crash.settle(lost.id, null).payout(), "nothing paid");
		// window closed while flying at ≥ ×1.80 → cashed out at the shown multiplier
		Round closed = start(a, 1_000, 0, crashAt(900));
		fly(closed, 180);
		long shown = closed.lastMultH;
		Crash.Settled s = Crash.settle(closed.id, "window closed");
		Assert.isFalse(s.refund(), "not a refund");
		Assert.eq(CrashMath.payout(1_000, shown), s.payout(), "closing = cash-out at the shown multiplier");
		Assert.isTrue(Crash.settle(closed.id, "window closed") == null, "only once");
		long expect = b0 + 500 + (CrashMath.payout(1_000, m) - 1_000) - 1_000 + (CrashMath.payout(1_000, shown) - 1_000);
		Assert.eq(expect, EconomyService.balance(id), "balance after four rounds");
		CrashData.PlayerStats st = Crash.peekStats(id);
		Assert.eq(4L, st.rounds, "4 rounds counted once each");
		Assert.eq(4_000L, st.wagered, "wagered");
		Assert.eq(List.of(900L, 130L, 250L, 300L), st.history, "crash point history, newest first");
		Assert.eq(0L, Crash.reservedTotal(), "no reservation left");
		ctx.detail("auto/manual/crash/closed each settled exactly once");
	}

	private static void restartRecovery(TestContext ctx) {
		setup(ctx);
		FakePlayer a = funded(ctx, "CrashRestartA", 1_000_000);
		FakePlayer b = funded(ctx, "CrashRestartB", 1_000_000);
		FakePlayer c = funded(ctx, "CrashRestartC", 1_000_000);
		setTreasuryFree(100_000_000);
		Round flying = start(a, 10_000, 0, crashAt(500));
		fly(flying, 175);
		long shown = flying.lastMultH;
		Round notStarted = start(b, 5_000, 0, crashAt(500));
		Round decided = start(c, 2_000, 200, crashAt(300));
		fly(decided, Long.MAX_VALUE); // decided ×2, crash happened before booking
		// save + load: every round through Gson into the store
		for (Round r : List.of(flying, notStarted, decided)) {
			Round loaded = DataStore.gson().fromJson(DataStore.gson().toJson(r), Round.class);
			loaded.fixNulls();
			Crash.data().rounds.put(r.id, loaded);
		}
		long ba = EconomyService.balance(a.getUUID());
		long bb = EconomyService.balance(b.getUUID());
		long bc = EconomyService.balance(c.getUUID());
		Assert.eq(3, Crash.settleAll("server restart"), "three rounds settled");
		Assert.eq(0, Crash.settleAll("server restart"), "nothing twice");
		Assert.eq(ba + CrashMath.payout(10_000, shown), EconomyService.balance(a.getUUID()), "flying → last shown multiplier");
		Assert.eq(bb + 5_000, EconomyService.balance(b.getUUID()), "never above ×1.00 → bet back");
		Assert.eq(bc + 4_000, EconomyService.balance(c.getUUID()), "decided → decided payout");
		CrashData.PlayerStats sb = Crash.peekStats(b.getUUID());
		Assert.eq(0L, sb == null ? 0 : sb.rounds, "refund not counted");
		Assert.eq(0L, Crash.reservedTotal(), "no reservation left");
		ctx.detail("restart: flying/unstarted/decided rounds paid once");
	}

	private static void pendingPayout(TestContext ctx) {
		setup(ctx);
		FakePlayer a = funded(ctx, "CrashPending", 1_000_000);
		setTreasuryFree(10_000_000);
		UUID id = a.getUUID();
		long house = Casino.reservedTotal(null) - Crash.reservedTotal();
		Round r = start(a, 10_000, 500, crashAt(800));
		Assert.eq(CrashMath.Phase.CASHED, fly(r, Long.MAX_VALUE), "×5 reached");
		long bal = EconomyService.balance(id);
		EconomyService.set(Treasury.ID, 1_000); // treasury emptied (admin)
		Crash.Settled s = Crash.settle(r.id, null);
		Assert.isFalse(s.paid(), "payout not possible yet");
		Assert.eq(bal, EconomyService.balance(id), "nothing paid");
		Assert.eq(50_000L, Crash.reservedTotal(), "pending payout stays reserved");
		Assert.eq(house + 50_000, Casino.reservedTotal(null), "pending crash payout reserved for the house games too");
		Assert.eq(0L, Casino.coverLimit(null), "house games locked while the treasury can't cover the pending crash payout");
		Assert.eq(0, Crash.retryPending(id), "treasury still empty");
		EconomyService.set(Treasury.ID, 10_000_000);
		Assert.eq(1, Crash.retryPending(id), "paid later");
		Assert.eq(0, Crash.retryPending(id), "only once");
		Assert.eq(bal + 50_000, EconomyService.balance(id), "paid exactly once");
		Assert.eq(0L, Crash.reservedTotal(), "reservation released");
		Assert.eq(house, Casino.reservedTotal(null), "released for the house games");
	}

	private static void conservation(TestContext ctx) {
		setup(ctx);
		Casino.cfg().dailyLossLimit = Money.MAX;
		FakePlayer a = funded(ctx, "CrashCons", 1_000_000_000L);
		setTreasuryFree(1_000_000_000L);
		UUID id = a.getUUID();
		long t0 = Treasury.balance();
		long p0 = EconomyService.balance(id);
		SplittableRandom rng = new SplittableRandom(4711);
		long staked = 0;
		long paid = 0;
		int wins = 0;
		int refunds = 0;
		int n = 2_000;
		for (int i = 0; i < n; i++) {
			long stake = Casino.cfg().minBet + rng.nextInt(50_000);
			long auto = rng.nextInt(3) == 0 ? 0 : SIM_TARGETS[rng.nextInt(SIM_TARGETS.length)];
			Round r = start(a, stake, auto, rng);
			Assert.eq(p0 - staked + paid - stake, EconomyService.balance(id), "bet taken (round " + i + ")");
			switch (rng.nextInt(3)) {
				case 0 -> fly(r, Long.MAX_VALUE); // ride: auto/cap or crash
				case 1 -> {
					// manual cash-out at a random point
					if (fly(r, 101 + rng.nextInt(400)) == CrashMath.Phase.RUNNING && Crash.canCashOut(r)) Crash.cashOut(r);
				}
				default -> fly(r, 100 + rng.nextInt(300)); // interrupted (window closed / restart)
			}
			Crash.Settled s = Crash.settle(r.id, null);
			Assert.notNull(s, "settled");
			Assert.isTrue(s.paid(), "payout booked");
			if (s.refund()) refunds++;
			if (s.payout() > stake) wins++;
			staked += stake;
			paid += s.payout();
			Assert.eq(t0 + p0, Treasury.balance() + EconomyService.balance(id), "player + treasury constant after round " + i);
		}
		Assert.eq(t0 + staked - paid, Treasury.balance(), "treasury = start + bets − payouts");
		Assert.eq(0L, Crash.reservedTotal(), "no reservations left");
		Assert.isTrue(Crash.openRound(id) == null, "no open round");
		ctx.detail(n + " rounds (" + wins + " wins, " + refunds + " refunds), conserved; house net " + Money.format(staked - paid)
			+ " (single seed incl. ×100 rides – RTP is checked by rtp-*)");
	}

	private static void oneRound(TestContext ctx) {
		setup(ctx);
		FakePlayer a = funded(ctx, "CrashOne", 1_000_000);
		setTreasuryFree(100_000_000);
		Round r = start(a, 1_000, 0, crashAt(500));
		Crash.Start second = Crash.start(a.getUUID(), name(a), 1_000, 0, crashAt(500));
		Assert.isFalse(second.ok(), "second crash round refused");
		Crash.settle(r.id, null);
		Casino.Start hilo = Casino.startHilo(a.getUUID(), name(a), 1_000, new SplittableRandom(3));
		Assert.isTrue(hilo.ok(), "hi-lo admitted: " + hilo.error());
		Assert.isFalse(Crash.start(a.getUUID(), name(a), 1_000, 0, crashAt(500)).ok(), "open casino round blocks crash");
		Casino.settle(hilo.round().id, null);
		Crash.Start again = Crash.start(a.getUUID(), name(a), 1_000, 0, crashAt(500));
		Assert.isTrue(again.ok(), "free again");
		// and the other way round: an open crash round blocks a house game
		Casino.Start blocked = Casino.startHilo(a.getUUID(), name(a), 1_000, new SplittableRandom(3));
		Assert.isFalse(blocked.ok(), "open crash round blocks a house game");
		Assert.check(blocked.error().contains("already playing"), "message: " + blocked.error());
		Crash.settle(again.round().id, null);
		Casino.Start hilo2 = Casino.startHilo(a.getUUID(), name(a), 1_000, new SplittableRandom(3));
		Assert.isTrue(hilo2.ok(), "house game free again: " + hilo2.error());
		Casino.settle(hilo2.round().id, null);
	}

	private static void bigWin(TestContext ctx) {
		setup(ctx);
		Assert.isFalse(Crash.isBigWin(10_000, 20_000), "$100 → $200 is small");
		Assert.isTrue(Crash.isBigWin(10_000, 260_000), "profit ≥ $2,500 announced");
		Assert.isTrue(Crash.isBigWin(5_000, 50_000), "×10 with ≥ $500 announced");
		Assert.isFalse(Crash.isBigWin(1_000, 10_000), "×10 below $500 not announced");
		Assert.isFalse(Crash.isBigWin(10_000, 0), "loss not announced");
	}
}
