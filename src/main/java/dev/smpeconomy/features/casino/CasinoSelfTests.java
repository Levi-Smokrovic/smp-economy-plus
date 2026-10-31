package dev.smpeconomy.features.casino;

import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
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
import dev.smpeconomy.core.Services;
import dev.smpeconomy.core.Treasury;
import dev.smpeconomy.core.test.Assert;
import dev.smpeconomy.core.test.SelfTests;
import dev.smpeconomy.core.test.TestContext;
import dev.smpeconomy.features.casino.CasinoData.Round;

/** Casino selftests (no GUIs): RTP per game (100k rounds), Treasury, limits, exactly-once settlement, recovery. */
final class CasinoSelfTests {
	private static final String F = "casino";
	private static final int SIM_ROUNDS = 100_000;

	private CasinoSelfTests() {}

	static void register() {
		SelfTests.register(F, "slots-rtp", CasinoSelfTests::slotsRtp);
		SelfTests.register(F, "coinflip-rtp", CasinoSelfTests::coinflipRtp);
		SelfTests.register(F, "mines-rtp", CasinoSelfTests::minesRtp);
		SelfTests.register(F, "hilo-rtp", CasinoSelfTests::hiloRtp);
		SelfTests.register(F, "engine-rtp", CasinoSelfTests::engineRtp);
		SelfTests.register(F, "mines-math", CasinoSelfTests::minesMath);
		SelfTests.register(F, "hilo-math", CasinoSelfTests::hiloMath);
		SelfTests.register(F, "config-validation", CasinoSelfTests::configValidation);
		SelfTests.register(F, "max-bet-guard", CasinoSelfTests::maxBetGuard);
		SelfTests.register(F, "coverage-guard", CasinoSelfTests::coverageGuard);
		SelfTests.register(F, "treasury-conservation", CasinoSelfTests::conservation);
		SelfTests.register(F, "exactly-once", CasinoSelfTests::exactlyOnce);
		SelfTests.register(F, "crash-recovery", CasinoSelfTests::crashRecovery);
		SelfTests.register(F, "pending-payout", CasinoSelfTests::pendingPayout);
		SelfTests.register(F, "loss-limit", CasinoSelfTests::lossLimit);
		SelfTests.register(F, "personal-limit", CasinoSelfTests::personalLimit);
		SelfTests.register(F, "double-or-nothing", CasinoSelfTests::doubleOrNothing);
		SelfTests.register(F, "one-round-per-player", CasinoSelfTests::oneRound);
		SelfTests.register(F, "idle-timeout", CasinoSelfTests::idleTimeout);
	}

	/** Random source with preset values for nextInt(bound), then a fixed seed. */
	static final class Scripted implements RandomGenerator {
		private final int[] values;
		private int index;
		private final SplittableRandom fallback = new SplittableRandom(99);

		Scripted(int... values) {
			this.values = values;
		}

		@Override
		public long nextLong() {
			return fallback.nextLong();
		}

		@Override
		public int nextInt(int bound) {
			if (index < values.length) return Math.floorMod(values[index++], bound);
			return fallback.nextInt(bound);
		}
	}

	/** Always the same value (e.g. 0 = every coin flip wins, 9999 = every one loses). */
	static RandomGenerator always(int value) {
		return new RandomGenerator() {
			@Override
			public long nextLong() {
				return value;
			}

			@Override
			public int nextInt(int bound) {
				return Math.min(Math.max(0, value), bound - 1);
			}
		};
	}

	/**
	 * Sets config to defaults (plus an adjustment), saves time, day and Treasury; everything is restored after the
	 * test.
	 */
	private static CasinoConfig setup(TestContext ctx) {
		ctx.preserveTreasury();
		CasinoConfig live = Casino.cfg();
		CasinoConfig saved = live.copy();
		Supplier<LocalDate> savedToday = Casino.today;
		LongSupplier savedClock = Casino.clock;
		ctx.cleanup(() -> {
			live.copyFrom(saved);
			Casino.today = savedToday;
			Casino.clock = savedClock;
		});
		live.copyFrom(new CasinoConfig());
		return live;
	}

	/** Sets the Treasury so that exactly {@code free} is left after other reservations. */
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

	private static void checkRtp(String what, double sum, double sumSq, int n, double expected) {
		double mean = sum / n;
		double var = Math.max(0, sumSq / n - mean * mean);
		double tol = 4 * Math.sqrt(var / n) + 0.0005;
		Assert.check(Math.abs(mean - expected) <= tol, what + ": RTP " + fmt(mean) + " deviates from " + fmt(expected) + " (tolerance " + fmt(tol) + ")");
	}

	private static String fmt(double d) {
		return String.format(java.util.Locale.ROOT, "%.4f", d);
	}

	private static void slotsRtp(TestContext ctx) {
		double theo = CasinoMath.slotsTheoreticalRtp();
		Assert.check(theo >= 0.95 && theo <= 0.97, "Theoretical slots RTP " + fmt(theo) + " not in [0.95, 0.97] (house edge 3–5 %)");
		Assert.eq(250, CasinoMath.slotsMaxTenths(), "highest slots multiplier ×25");
		SplittableRandom rng = new SplittableRandom(20251010L);
		double sum = 0;
		double sumSq = 0;
		long stake = 10_000;
		for (int i = 0; i < SIM_ROUNDS; i++) {
			double x = CasinoMath.slotsPayout(stake, CasinoMath.slotsSpin(rng)) / (double) stake;
			sum += x;
			sumSq += x * x;
		}
		checkRtp("Slots", sum, sumSq, SIM_ROUNDS, theo);
		Assert.eq(6_250L, CasinoMath.slotsPayout(250, new int[] {5, 5, 5}), "3× diamond ×25 on $2.50");
		Assert.eq(15L, CasinoMath.slotsPayout(10, new int[] {0, 3, 0}), "2 of a kind ×1.5");
		Assert.eq(0L, CasinoMath.slotsPayout(10_000, new int[] {0, 1, 2}), "no match = 0");
		ctx.detail("theoretical " + fmt(theo) + ", simulated " + fmt(sum / SIM_ROUNDS) + " (" + SIM_ROUNDS + " Spins)");
	}

	private static void coinflipRtp(TestContext ctx) {
		SplittableRandom rng = new SplittableRandom(11);
		double sum = 0;
		double sumSq = 0;
		for (int i = 0; i < SIM_ROUNDS; i++) {
			double x = CasinoMath.coinflipWins(rng) ? 2 : 0;
			sum += x;
			sumSq += x * x;
		}
		checkRtp("Coinflip", sum, sumSq, SIM_ROUNDS, 0.96);
		Assert.eq(4800, CasinoMath.COINFLIP_WIN_BP, "48 % win chance");
		ctx.detail("simulated " + fmt(sum / SIM_ROUNDS) + " (target 0.96)");
	}

	private static void minesRtp(TestContext ctx) {
		int edgeBp = 400;
		long capBp = 250_000;
		long stake = 10_000;
		SplittableRandom rng = new SplittableRandom(4242);
		double sum = 0;
		double sumSq = 0;
		int n = 0;
		int[] order = new int[CasinoMath.MINES_TILES];
		while (n < SIM_ROUNDS) {
			int bombs = 1 + rng.nextInt(CasinoMath.MINES_TILES - 1);
			int maxK = CasinoMath.minesMaxSafe(bombs, edgeBp, capBp);
			if (maxK < 1) continue;
			int target = 1 + rng.nextInt(maxK);
			int[] layout = CasinoMath.minesLayout(bombs, rng);
			boolean[] bomb = new boolean[CasinoMath.MINES_TILES];
			for (int t : layout) bomb[t] = true;
			for (int i = 0; i < order.length; i++) order[i] = i;
			for (int i = order.length - 1; i > 0; i--) {
				int j = rng.nextInt(i + 1);
				int tmp = order[i];
				order[i] = order[j];
				order[j] = tmp;
			}
			int k = 0;
			boolean alive = true;
			for (int t : order) {
				if (k == target) break;
				if (bomb[t]) {
					alive = false;
					break;
				}
				k++;
			}
			double x = alive ? CasinoMath.minesPayout(stake, bombs, k, edgeBp) / (double) stake : 0;
			sum += x;
			sumSq += x * x;
			n++;
		}
		checkRtp("Mines", sum, sumSq, n, 0.96);
		ctx.detail("simulated " + fmt(sum / n) + " (target 0.96, bombs 1–24, random exits)");
	}

	private static void hiloRtp(TestContext ctx) {
		int edgeBp = 400;
		long capBp = 250_000;
		long stake = 10_000;
		SplittableRandom rng = new SplittableRandom(777);
		double sum = 0;
		double sumSq = 0;
		for (int i = 0; i < SIM_ROUNDS; i++) {
			int target = 1 + rng.nextInt(6);
			int rank = CasinoMath.hiloRank(rng);
			BigInteger num = BigInteger.ONE;
			BigInteger den = BigInteger.ONE;
			int correct = 0;
			boolean alive = true;
			while (correct < target) {
				int hi = CasinoMath.hiloCount(rank, true);
				int lo = CasinoMath.hiloCount(rank, false);
				boolean higher = hi == lo ? rng.nextInt(2) == 0 : hi > lo;
				if (!CasinoMath.hiloWithinCap(num, den, rank, higher, edgeBp, capBp)) {
					higher = !higher;
					if (!CasinoMath.hiloWithinCap(num, den, rank, higher, edgeBp, capBp)) break;
				}
				int next = CasinoMath.hiloNext(rank, rng);
				boolean win = higher ? next > rank : next < rank;
				if (!win) {
					alive = false;
					break;
				}
				BigInteger[] m = CasinoMath.hiloStep(num, den, rank, higher);
				num = m[0];
				den = m[1];
				correct++;
				rank = next;
			}
			double x = alive && correct > 0 ? CasinoMath.hiloPayout(stake, num, den, edgeBp) / (double) stake : 0;
			sum += x;
			sumSq += x * x;
		}
		checkRtp("Higher/Lower", sum, sumSq, SIM_ROUNDS, 0.96);
		ctx.detail("simulated " + fmt(sum / SIM_ROUNDS) + " (target 0.96)");
	}

	/** RTP through the real game logic (stake to Treasury, payout from Treasury), 25,000 rounds per game. */
	private static void engineRtp(TestContext ctx) {
		CasinoConfig c = setup(ctx);
		c.dailyLossLimit = Money.MAX;
		FakePlayer a = funded(ctx, "CasinoRtp", 10_000_000_000L);
		setTreasuryFree(10_000_000_000L);
		UUID id = a.getUUID();
		long stake = 10_000;
		int n = 25_000;
		SplittableRandom rng = new SplittableRandom(2026);
		StringBuilder detail = new StringBuilder();
		for (Game g : Game.values()) {
			double sum = 0;
			double sumSq = 0;
			long t0 = Treasury.balance();
			long staked = 0;
			long paidTotal = 0;
			for (int i = 0; i < n; i++) {
				Casino.Start s = switch (g) {
					case COINFLIP -> Casino.startCoinflip(id, name(a), stake, false, rng);
					case SLOTS -> Casino.startSlots(id, name(a), stake, rng);
					case MINES -> Casino.startMines(id, name(a), stake, 3, rng);
					case HILO -> Casino.startHilo(id, name(a), stake, rng);
				};
				Assert.isTrue(s.ok(), g + " round " + i + ": " + s.error());
				Round r = s.round();
				switch (g) {
					case COINFLIP -> {
						if (Casino.isOpen(r)) Casino.cashOut(r);
					}
					case MINES -> {
						// 3 random tiles, then cash out
						for (int k = 0; k < 3 && Casino.isOpen(r); k++) {
							int tile;
							do {
								tile = rng.nextInt(25);
							} while (r.revealed.contains(tile));
							Casino.minesReveal(r, tile);
						}
						if (Casino.isOpen(r)) Casino.cashOut(r);
					}
					case HILO -> {
						boolean higher = CasinoMath.hiloCount(r.card, true) >= CasinoMath.hiloCount(r.card, false);
						Casino.hiloGuess(r, higher, rng);
						if (Casino.isOpen(r)) Casino.cashOut(r);
					}
					default -> { }
				}
				Casino.Settled st = Casino.settle(r.id, null);
				Assert.isFalse(st.refund(), "no refund");
				double x = st.payout() / (double) stake;
				sum += x;
				sumSq += x * x;
				staked += stake;
				paidTotal += st.payout();
			}
			double expected = g == Game.SLOTS ? CasinoMath.slotsTheoreticalRtp() : 0.96;
			checkRtp(g.label() + " (Engine)", sum, sumSq, n, expected);
			Assert.eq(t0 + staked - paidTotal, Treasury.balance(), g + ": Treasury = stakes − payouts");
			detail.append(g.id()).append('=').append(fmt(sum / n)).append(' ');
		}
		ctx.detail(detail.toString().trim() + " (each " + n + " rounds)");
	}

	private static void minesMath(TestContext ctx) {
		Assert.eq(10_000L, CasinoMath.minesPayout(10_000, 1, 1, 400), "1 bomb, 1 tile: ×1.00 (0.96 × 25/24)");
		Assert.eq(10_909L, CasinoMath.minesPayout(10_000, 3, 1, 400), "3 bombs, 1 tile: floor(0.96 × 25/22)");
		Assert.eq(240_000L, CasinoMath.minesPayout(10_000, 24, 1, 400), "24 bombs, 1 tile: ×24");
		Assert.eq(5_200_300L, CasinoMath.binom(25, 12), "C(25,12)");
		for (int b = 1; b <= 24; b++) {
			int k = CasinoMath.minesMaxSafe(b, 400, 250_000);
			Assert.check(k >= 1, "Bombs " + b + " playable with cap ×25");
			Assert.check(CasinoMath.minesWithinCap(b, k, 400, 250_000), "k=" + k + " under the cap");
			Assert.check(k == 25 - b || !CasinoMath.minesWithinCap(b, k + 1, 400, 250_000), "k+1 above the cap (b=" + b + ")");
			Assert.check(CasinoMath.minesPayout(10_000, b, k, 400) <= 250_000, "payout ≤ ×25 (b=" + b + ")");
		}
		Assert.eq(0, CasinoMath.minesMaxSafe(24, 400, 100_000), "cap ×10: 24 bombs not playable");
		// bomb distribution: uniform, distinct, sorted
		SplittableRandom rng = new SplittableRandom(5);
		int[] hits = new int[25];
		int layouts = 25_000;
		for (int i = 0; i < layouts; i++) {
			int[] l = CasinoMath.minesLayout(3, rng);
			Assert.eq(3, l.length, "3 bombs");
			for (int j = 0; j < l.length; j++) {
				Assert.check(l[j] >= 0 && l[j] < 25, "tile in range");
				if (j > 0) Assert.check(l[j] > l[j - 1], "distinct + sorted");
				hits[l[j]]++;
			}
		}
		double expected = layouts * 3 / 25.0;
		for (int t = 0; t < 25; t++) Assert.check(Math.abs(hits[t] - expected) < expected * 0.1, "Tile " + t + " uniform (" + hits[t] + ")");
		ctx.detail("multipliers, cap and distribution ok");
	}

	private static void hiloMath(TestContext ctx) {
		Assert.eq(0, CasinoMath.hiloCount(14, true), "ace: higher impossible");
		Assert.eq(0, CasinoMath.hiloCount(2, false), "2: lower impossible");
		Assert.eq(12, CasinoMath.hiloCount(2, true), "2: everything is higher");
		SplittableRandom rng = new SplittableRandom(3);
		int[] counts = new int[15];
		int draws = 60_000;
		for (int i = 0; i < draws; i++) {
			int n = CasinoMath.hiloNext(7, rng);
			Assert.check(n != 7 && n >= 2 && n <= 14, "next card ≠ current");
			counts[n]++;
		}
		for (int r = 2; r <= 14; r++) {
			if (r == 7) continue;
			Assert.check(Math.abs(counts[r] - draws / 12.0) < draws / 12.0 * 0.08, "Value " + r + " uniform (" + counts[r] + ")");
		}
		BigInteger[] m = CasinoMath.hiloStep(BigInteger.ONE, BigInteger.ONE, 13, true);
		Assert.eq("12/1", m[0] + "/" + m[1], "king, higher: ×12");
		Assert.eq(115_200L, CasinoMath.hiloPayout(10_000, m[0], m[1], 400), "payout 0.96 × 12");
		Assert.isFalse(CasinoMath.hiloWithinCap(m[0], m[1], 13, true, 400, 250_000), "second ×12 above the ×25 cap");
		Assert.isTrue(CasinoMath.hiloWithinCap(m[0], m[1], 2, true, 400, 250_000), "safe guess (×1) stays allowed");
		BigInteger[] same = CasinoMath.hiloStep(m[0], m[1], 2, true);
		Assert.eq("12/1", same[0] + "/" + same[1], "2, higher does not change the multiplier");
		Assert.assertThrows(IllegalArgumentException.class, () -> CasinoMath.hiloStep(BigInteger.ONE, BigInteger.ONE, 14, true), "ace higher throws");
		ctx.detail("cards, probabilities and cap ok");
	}

	private static void configValidation(TestContext ctx) {
		CasinoConfig c = new CasinoConfig();
		c.validate();
		c.maxBetTreasuryPercent = 10;
		c.validate();
		Assert.check(c.effectiveMaxBetPercent() == 2.0, "max bet percent capped at 2 %");
		CasinoConfig bad = new CasinoConfig();
		bad.minesHouseEdgePercent = 1;
		Assert.assertThrows(IllegalArgumentException.class, bad::validate, "house edge < 3 % refused");
		CasinoConfig bad2 = new CasinoConfig();
		bad2.minBet = 0;
		Assert.assertThrows(IllegalArgumentException.class, bad2::validate, "minimum bet 0 refused");
		CasinoConfig bad3 = new CasinoConfig();
		bad3.maxWinTreasuryPercent = 0;
		Assert.assertThrows(IllegalArgumentException.class, bad3::validate, "maximum win 0 % refused");
		ctx.detail("validation ok");
	}

	private static void maxBetGuard(TestContext ctx) {
		CasinoConfig c = setup(ctx);
		FakePlayer a = funded(ctx, "CasinoBetA", 100_000_000);
		setTreasuryFree(1_000_000); // $10,000 free
		long maxBet = Casino.maxBet();
		Assert.eq(Casino.percentOf(Treasury.balance(), 2.0), maxBet, "max bet = 2 % of the Treasury");
		long before = EconomyService.balance(a.getUUID());
		Casino.Start over = Casino.startCoinflip(a.getUUID(), name(a), maxBet + 1, false, always(9999));
		Assert.isFalse(over.ok(), "stake above 2 % refused");
		Assert.check(over.error().contains("Maximum bet"), "message names the max bet: " + over.error());
		Assert.eq(before, EconomyService.balance(a.getUUID()), "refused: nothing taken");
		Assert.isTrue(Casino.openRound(a.getUUID()) == null, "refused: no open round");
		Casino.Start ok = Casino.startCoinflip(a.getUUID(), name(a), maxBet, false, always(9999));
		Assert.isTrue(ok.ok(), "stake = max bet admitted: " + ok.error());
		Casino.settle(ok.round().id, null);
		Casino.Start small = Casino.startCoinflip(a.getUUID(), name(a), c.minBet - 1, false, always(9999));
		Assert.isFalse(small.ok(), "below the minimum bet refused");
		// config: percent > 2 is capped, absolute maximum applies
		c.maxBetTreasuryPercent = 50;
		Assert.eq(Casino.percentOf(Treasury.balance(), 2.0), Casino.maxBet(), "config 50 %, still 2 %");
		c.maxBet = 5_000;
		Assert.eq(5_000L, Casino.maxBet(), "absolute maximum applies");
		// empty Treasury: locked
		EconomyService.set(Treasury.ID, 0);
		Casino.Start closed = Casino.startSlots(a.getUUID(), name(a), 1_000, always(0));
		Assert.isFalse(closed.ok(), "empty Treasury: locked");
		Assert.check(closed.error().contains("locked"), "message locked: " + closed.error());
		c.enabled = false;
		EconomyService.set(Treasury.ID, 100_000_000);
		Assert.isFalse(Casino.startSlots(a.getUUID(), name(a), 1_000, always(0)).ok(), "casino off: no round");
		ctx.detail("2 % limit, minimum, config cap, empty Treasury, off");
	}

	private static void coverageGuard(TestContext ctx) {
		CasinoConfig c = setup(ctx);
		FakePlayer a = funded(ctx, "CasinoCovA", 100_000_000);
		FakePlayer b = funded(ctx, "CasinoCovB", 100_000_000);
		FakePlayer d = funded(ctx, "CasinoCovC", 100_000_000);
		setTreasuryFree(1_000_000); // $10,000 free, so maximum win ≥ $5,000
		long stake = Math.min(Casino.maxBet(), 20_000);
		long maxBefore = Casino.maxStake(d.getUUID(), Game.HILO, 0);
		Assert.check(maxBefore > 0, "C may play before");
		// A (hi-lo, first card king) and B (mines, 24 bombs): admission checks the ×25 maximum win, but only the
		// current value (stake) is reserved, so rounds held open block nobody
		Casino.Start ra = Casino.startHilo(a.getUUID(), name(a), stake, new Scripted(11, 0));
		Assert.isTrue(ra.ok(), "round A admitted: " + ra.error());
		Assert.eq(13, ra.round().card, "A: king");
		Assert.eq(stake, ra.round().reserved, "A reserves only the stake (current value)");
		Casino.Start rb = Casino.startMines(b.getUUID(), name(b), stake, 24, new SplittableRandom(2));
		Assert.isTrue(rb.ok(), "round B admitted: " + rb.error());
		Assert.eq(stake, rb.round().reserved, "B reserves only the stake (current value)");
		Assert.eq(1_000_000L, Casino.freeCoverage(null), "rounds without a move hold no coverage (stake in = reservation)");
		Assert.check(Casino.maxStake(d.getUUID(), Game.HILO, 0) >= maxBefore, "C keeps its max bet");
		// A guesses "higher" on a king (next card ace): now reserves exactly the new cash-out value (×12 × 0.96)
		long aWin = CasinoMath.hiloPayout(stake, BigInteger.valueOf(12), BigInteger.ONE, c.hiloEdgeBp());
		Assert.eq(aWin, Casino.hiloNextPayout(ra.round(), true), "possible win of the guess");
		Assert.eq(Casino.Step.WIN, Casino.hiloGuess(ra.round(), true, new Scripted(11, 0)), "A: king to ace, higher wins");
		Assert.eq(aWin, ra.round().reserved, "A reserves the current cash-out value");
		Assert.eq(aWin, Casino.cashOutValue(ra.round()), "reservation = cash-out value");
		Assert.eq(1_000_000L + stake - aWin, Casino.freeCoverage(null), "free coverage = free + stakes − reservations");
		// Treasury shrinks (e.g. other payouts): only $1,000 free besides B
		EconomyService.set(Treasury.ID, Math.addExact(Casino.reservedTotal(rb.round().id), 100_000));
		long bNext = Casino.minesNextPayout(rb.round());
		Assert.eq(stake * 24, bNext, "B: first tile with 24 bombs = ×24");
		Assert.check(bNext > Casino.coverLimit(rb.round().id), "B: next tile not covered");
		Assert.isFalse(Casino.minesCovered(rb.round()), "minesCovered = false");
		int bomb = rb.round().mines.get(0);
		int safe = -1;
		for (int t = 0; t < 25; t++) if (!rb.round().mines.contains(t)) safe = t;
		Assert.eq(Casino.Step.BLOCKED, Casino.minesReveal(rb.round(), bomb), "bomb: blocked (reveals nothing)");
		Assert.eq(Casino.Step.BLOCKED, Casino.minesReveal(rb.round(), safe), "safe tile: blocked as well");
		Assert.isTrue(Casino.isOpen(rb.round()) && rb.round().revealed.isEmpty(), "B continues unchanged");
		Assert.eq(stake, rb.round().reserved, "B: reservation unchanged");
		// C: maximum win above free coverage: refused, nothing taken
		long cStake = Casino.maxBet();
		Assert.check(Casino.potential(Game.HILO, cStake, 0) > Casino.coverLimit(null), "precondition: C not covered");
		Casino.Start rc = Casino.startHilo(d.getUUID(), name(d), cStake, new SplittableRandom(3));
		Assert.isFalse(rc.ok(), "round C without coverage refused");
		Assert.check(rc.error().contains("can't cover") || rc.error().contains("locked"), "coverage message: " + rc.error());
		Assert.eq(100_000_000L, EconomyService.balance(d.getUUID()), "refused: nothing taken");
		// A cashes out, reservation freed; Treasury refilled, so B may reveal
		Assert.isTrue(Casino.cashOut(ra.round()), "A cashes out");
		Assert.eq(aWin, Casino.settle(ra.round().id, null).payout(), "A: payout");
		setTreasuryFree(1_000_000);
		Assert.isTrue(Casino.minesCovered(rb.round()), "B: covered now");
		Assert.eq(Casino.Step.DONE, Casino.minesReveal(rb.round(), safe), "B: only safe tile, paid automatically");
		Assert.eq(bNext, rb.round().reserved, "B: reservation = payout");
		Assert.eq(bNext, Casino.settle(rb.round().id, null).payout(), "B: ×24 paid");
		setTreasuryFree(1_000_000);
		Casino.Start rc2 = Casino.startCoinflip(d.getUUID(), name(d), c.minBet, false, always(9999));
		Assert.isTrue(rc2.ok(), "admitted after release: " + rc2.error());
		Casino.settle(rc2.round().id, null);
		// a decided round reserves only its payout (3× coal = ×3)
		Casino.Start rs = Casino.startSlots(a.getUUID(), name(a), stake, always(0));
		Assert.eq(stake * 3, rs.round().reserved, "decided round reserves exactly the payout");
		Casino.settle(rs.round().id, null);
		// double or nothing ends at the maximum win (50 % of the Treasury)
		setTreasuryFree(1_000_000);
		long cf = Math.min(Casino.maxBet(), 20_000);
		Casino.Start rd = Casino.startCoinflip(d.getUUID(), name(d), cf, false, always(0));
		Assert.isTrue(rd.ok() && rd.round().wins == 1, "coin flip won (scripted)");
		int doubles = 0;
		while (Casino.canDouble(rd.round())) {
			Assert.eq(Casino.Step.WIN, Casino.coinflipDouble(rd.round(), always(0)), "double won");
			doubles++;
			Assert.check(doubles < 40, "doubling ends");
		}
		Assert.check(rd.round().pot * 2 > Casino.coverLimit(rd.round().id), "next double above the maximum win");
		Assert.eq(Casino.Step.BLOCKED, Casino.coinflipDouble(rd.round(), always(0)), "double blocked");
		Assert.check(rd.round().pot <= Casino.maxWin(), "pot ≤ maximum win");
		Assert.check(doubles >= 1, "doubled at least once");
		Casino.Settled sd = Casino.settle(rd.round().id, null);
		Assert.eq(cf << (doubles + 1), sd.payout(), "pot paid");
		// small mines cap: 24 bombs not playable
		c.minesMaxMultiplier = 10;
		Casino.Start rm = Casino.startMines(a.getUUID(), name(a), 1_000, 24, new SplittableRandom(2));
		Assert.isFalse(rm.ok(), "24 bombs with cap ×10 refused");
		ctx.detail("stepwise reservation, block without coverage, refusal, release, maximum win after " + doubles + " doubles");
	}

	/** Many random rounds of all games: player + Treasury stays constant, Treasury moves by stakes minus payouts. */
	private static void conservation(TestContext ctx) {
		CasinoConfig c = setup(ctx);
		c.dailyLossLimit = Money.MAX;
		FakePlayer a = funded(ctx, "CasinoCons", 1_000_000_000L);
		setTreasuryFree(1_000_000_000L);
		UUID id = a.getUUID();
		long t0 = Treasury.balance();
		long p0 = EconomyService.balance(id);
		long reserved0 = Casino.reservedTotal(null);
		SplittableRandom rng = new SplittableRandom(31337);
		long staked = 0;
		long paid = 0;
		int rounds = 0;
		int refunds = 0;
		for (int i = 0; i < 2_000; i++) {
			Game g = Game.values()[i % 4];
			long stake = c.minBet + rng.nextInt(50_000);
			Casino.Start s = switch (g) {
				case COINFLIP -> Casino.startCoinflip(id, name(a), stake, false, rng);
				case SLOTS -> Casino.startSlots(id, name(a), stake, rng);
				case MINES -> Casino.startMines(id, name(a), stake, 1 + rng.nextInt(24), rng);
				case HILO -> Casino.startHilo(id, name(a), stake, rng);
			};
			Assert.isTrue(s.ok(), "Round " + i + " (" + g + ") admitted: " + s.error());
			Round r = s.round();
			Assert.eq(p0 - staked + paid - stake, EconomyService.balance(id), "stake taken (" + g + ")");
			switch (g) {
				case COINFLIP -> {
					while (Casino.isOpen(r) && rng.nextInt(3) == 0 && Casino.canDouble(r)) Casino.coinflipDouble(r, rng);
					if (Casino.isOpen(r)) Casino.cashOut(r);
				}
				case MINES -> {
					int want = rng.nextInt(6);
					for (int k = 0; k < want && Casino.isOpen(r); k++) {
						int tile;
						do {
							tile = rng.nextInt(25);
						} while (r.revealed.contains(tile));
						Casino.minesReveal(r, tile);
					}
				}
				case HILO -> {
					int want = rng.nextInt(5);
					for (int k = 0; k < want && Casino.isOpen(r); k++) {
						boolean higher = CasinoMath.hiloCount(r.card, true) >= CasinoMath.hiloCount(r.card, false);
						if (!Casino.hiloAllowed(r, higher)) higher = !higher;
						if (!Casino.hiloAllowed(r, higher)) break;
						Casino.hiloGuess(r, higher, rng);
					}
				}
				default -> { }
			}
			Casino.Settled st = Casino.settle(r.id, null);
			Assert.notNull(st, "settled");
			Assert.isTrue(st.paid(), "payout booked");
			if (st.refund()) {
				refunds++;
				Assert.eq(stake, st.payout(), "refund = stake");
			}
			staked += stake;
			paid += st.payout();
			rounds++;
			Assert.eq(t0 + p0, Treasury.balance() + EconomyService.balance(id), "player + Treasury constant after round " + i);
		}
		Assert.eq(t0 + staked - paid, Treasury.balance(), "Treasury = start + stakes − payouts");
		Assert.eq(reserved0, Casino.reservedTotal(null), "no reservations left");
		Assert.check(Casino.openRound(id) == null, "no open round");
		ctx.detail(rounds + " rounds (" + refunds + " refunds), Treasury " + (staked - paid >= 0 ? "+" : "") + Money.format(staked - paid));
	}

	private static void exactlyOnce(TestContext ctx) {
		setup(ctx);
		FakePlayer a = funded(ctx, "CasinoOnce", 10_000_000);
		setTreasuryFree(100_000_000);
		UUID id = a.getUUID();
		long b0 = EconomyService.balance(id);
		// slots 3× diamond (roll 21 of 22 = diamond): ×25
		Casino.Start s = Casino.startSlots(id, name(a), 1_000, new Scripted(21, 21, 21));
		Assert.isTrue(s.ok(), "slots started");
		Assert.eq(CasinoData.DECIDED, s.round().state, "result decided before the animation");
		Assert.eq(25_000L, s.round().payout, "payout stored");
		Assert.eq(b0 - 1_000, EconomyService.balance(id), "stake taken");
		Casino.Settled first = Casino.settle(s.round().id, null);
		Assert.eq(25_000L, first.payout(), "payout");
		Assert.isTrue(Casino.settle(s.round().id, null) == null, "second settlement has no effect");
		Assert.isTrue(Casino.openRound(id) == null, "nothing left open");
		Assert.eq(b0 - 1_000 + 25_000, EconomyService.balance(id), "paid exactly once");
		// mines: lost pays 0, settling twice does nothing
		Casino.Start m = Casino.startMines(id, name(a), 1_000, 3, new SplittableRandom(8));
		Round r = m.round();
		Assert.eq(Casino.Step.LOSE, Casino.minesReveal(r, r.mines.get(0)), "bomb hit");
		Assert.eq(Casino.Step.INVALID, Casino.minesReveal(r, 0), "no move after a bomb");
		Assert.isFalse(Casino.cashOut(r), "no cash-out after a bomb");
		Assert.eq(0L, Casino.settle(r.id, null).payout(), "bomb = 0");
		Assert.isTrue(Casino.settle(r.id, null) == null, "second settlement has no effect");
		Assert.eq(b0 - 2_000 + 25_000, EconomyService.balance(id), "balance correct");
		// stats count each round exactly once
		CasinoData.PlayerStats st = Casino.peekStats(id);
		Assert.eq(2L, st.rounds, "2 rounds in the stats");
		Assert.eq(2_000L, st.wagered, "stakes");
		Assert.eq(25_000L, st.paidOut, "payouts");
		ctx.detail("slots and mines each settled exactly once");
	}

	/** Restart after a crash: stored rounds (JSON round trip) settle at the right value exactly once. */
	private static void crashRecovery(TestContext ctx) {
		setup(ctx);
		FakePlayer a = funded(ctx, "CasinoCrashA", 10_000_000);
		FakePlayer b = funded(ctx, "CasinoCrashB", 10_000_000);
		FakePlayer d = funded(ctx, "CasinoCrashC", 10_000_000);
		setTreasuryFree(100_000_000);
		// A: mines with 2 safe tiles
		Round mines = Casino.startMines(a.getUUID(), name(a), 10_000, 3, new SplittableRandom(9)).round();
		int revealed = 0;
		for (int t = 0; t < 25 && revealed < 2; t++) {
			if (!mines.mines.contains(t)) {
				Assert.eq(Casino.Step.WIN, Casino.minesReveal(mines, t), "safe tile");
				revealed++;
			}
		}
		// B: hi-lo without a guess: refund
		Round hilo = Casino.startHilo(b.getUUID(), name(b), 5_000, new SplittableRandom(10)).round();
		// C: coin flip won, waiting for a decision: pot
		Round coin = Casino.startCoinflip(d.getUUID(), name(d), 2_000, false, always(0)).round();
		Assert.eq(4_000L, coin.pot, "pot after win");
		// simulate save and load: every round through Gson and back into the data
		for (Round r : List.of(mines, hilo, coin)) {
			String json = DataStore.gson().toJson(r);
			Round loaded = DataStore.gson().fromJson(json, Round.class);
			loaded.fixNulls();
			Casino.data().rounds.put(r.id, loaded);
		}
		long ba = EconomyService.balance(a.getUUID());
		long bb = EconomyService.balance(b.getUUID());
		long bd = EconomyService.balance(d.getUUID());
		List<String> ids = new ArrayList<>(List.of(mines.id, hilo.id, coin.id));
		for (String rid : ids) Assert.notNull(Casino.settle(rid, Casino.RESTART_REASON), "Round " + rid + " settled");
		for (String rid : ids) Assert.isTrue(Casino.settle(rid, Casino.RESTART_REASON) == null, "not twice");
		Assert.eq(ba + CasinoMath.minesPayout(10_000, 3, 2, Casino.cfg().minesEdgeBp()), EconomyService.balance(a.getUUID()), "mines: value after 2 tiles");
		Assert.eq(bb + 5_000, EconomyService.balance(b.getUUID()), "hi-lo without a guess: stake back");
		Assert.eq(bd + 4_000, EconomyService.balance(d.getUUID()), "coin flip: pot paid");
		CasinoData.PlayerStats sb = Casino.peekStats(b.getUUID());
		Assert.eq(0L, sb == null ? 0 : sb.rounds, "refund does not count as a round");
		Assert.eq(0L, Casino.netLossToday(b.getUUID()), "refund does not count towards the daily loss");
		// whole data file: round trip keeps rounds and stats
		CasinoData data = new CasinoData();
		Round x = new Round();
		x.id = "rX";
		x.player = a.getUUID();
		x.game = "nonexistent";
		x.stake = 777;
		data.rounds.put("rX", x);
		CasinoData back = DataStore.gson().fromJson(DataStore.gson().toJson(data), CasinoData.class);
		back.fixNulls();
		Assert.isTrue(back.rounds.containsKey("rX"), "round with an unknown game is kept (gets refunded)");
		Assert.eq(777L, Casino.cashOutValue(back.rounds.get("rX")), "unknown game: stake back");
		ctx.detail("mines, hi-lo and coin flip correct and once after a restart");
	}

	private static void pendingPayout(TestContext ctx) {
		setup(ctx);
		FakePlayer a = funded(ctx, "CasinoPending", 10_000_000);
		setTreasuryFree(10_000_000);
		UUID id = a.getUUID();
		Round r = Casino.startSlots(id, name(a), 10_000, new Scripted(21, 21, 21)).round();
		Assert.eq(250_000L, r.payout, "×25 decided");
		long bal = EconomyService.balance(id);
		long reservedBefore = Casino.reservedTotal(null);
		// Treasury gets emptied (e.g. admin): payout must wait, never get lost
		EconomyService.set(Treasury.ID, 1_000);
		Casino.Settled s = Casino.settle(r.id, null);
		Assert.isFalse(s.paid(), "payout not possible for now");
		Assert.eq(bal, EconomyService.balance(id), "nothing paid");
		Assert.eq(reservedBefore, Casino.reservedTotal(null), "pending payout stays reserved");
		Assert.eq(0, Casino.retryPending(id), "Treasury still empty: still pending");
		EconomyService.set(Treasury.ID, 10_000_000);
		Assert.eq(1, Casino.retryPending(id), "retried");
		Assert.eq(0, Casino.retryPending(id), "only once");
		Assert.eq(bal + 250_000, EconomyService.balance(id), "paid exactly once");
		Assert.eq(reservedBefore - 250_000, Casino.reservedTotal(null), "reservation released");
		ctx.detail("pending payout retried once");
	}

	private static void lossLimit(TestContext ctx) {
		CasinoConfig c = setup(ctx);
		c.dailyLossLimit = 100_000; // $1,000
		FakePlayer a = funded(ctx, "CasinoLoss", 10_000_000);
		setTreasuryFree(100_000_000);
		UUID id = a.getUUID();
		LocalDate day = LocalDate.of(2030, 1, 1);
		Casino.today = () -> day;
		for (int i = 0; i < 2; i++) {
			Round r = Casino.startCoinflip(id, name(a), 40_000, false, always(9999)).round();
			Assert.notNull(r, "Round " + i);
			Casino.settle(r.id, null);
		}
		Assert.eq(80_000L, Casino.netLossToday(id), "daily loss $800");
		Assert.eq(20_000L, Casino.lossRoom(id), "$200 left");
		Casino.Start over = Casino.startCoinflip(id, name(a), 40_000, false, always(9999));
		Assert.isFalse(over.ok(), "above the daily limit refused");
		Assert.check(over.error().contains("Daily loss limit"), "daily limit message: " + over.error());
		Round last = Casino.startCoinflip(id, name(a), 20_000, false, always(9999)).round();
		Assert.notNull(last, "remaining stake allowed");
		Casino.settle(last.id, null);
		Assert.isFalse(Casino.startSlots(id, name(a), c.minBet, always(0)).ok(), "limit reached: nothing more");
		Assert.eq(0L, Casino.maxStake(id, Game.SLOTS, 0), "max stake 0");
		// new day: free again
		Casino.today = () -> day.plusDays(1);
		Assert.eq(100_000L, Casino.lossRoom(id), "new day: full limit");
		// wins lower the daily loss
		Round win = Casino.startCoinflip(id, name(a), 10_000, false, always(0)).round();
		Casino.cashOut(win);
		Casino.settle(win.id, null);
		Assert.eq(-10_000L, Casino.netLossToday(id), "win: in profit");
		Assert.eq(110_000L, Casino.lossRoom(id), "win widens the room");
		ctx.detail("daily limit, remainder, new day, wins");
	}

	private static void personalLimit(TestContext ctx) {
		CasinoConfig c = setup(ctx);
		FakePlayer a = funded(ctx, "CasinoPersonal", 10_000_000);
		setTreasuryFree(100_000_000);
		UUID id = a.getUUID();
		LocalDate day = LocalDate.of(2030, 2, 1);
		Casino.today = () -> day;
		Assert.eq(c.dailyLossLimit, Casino.lossLimit(id), "server limit");
		Casino.setPersonalLimit(id, 50_000);
		Assert.eq(50_000L, Casino.lossLimit(id), "lowering applies at once");
		Casino.setPersonalLimit(id, 80_000);
		Assert.eq(50_000L, Casino.lossLimit(id), "raising not at once");
		Casino.setPersonalLimit(id, Money.MAX);
		Assert.eq(50_000L, Casino.lossLimit(id), "large raise also only tomorrow");
		Casino.today = () -> day.plusDays(1);
		Assert.eq(c.dailyLossLimit, Casino.lossLimit(id), "from tomorrow: at most the server limit");
		Casino.setPersonalLimit(id, 0);
		Assert.eq(0L, Casino.lossLimit(id), "limit 0 = pause");
		Assert.isFalse(Casino.startSlots(id, name(a), c.minBet, always(0)).ok(), "no game with limit 0");
		Casino.setPersonalLimit(id, -1);
		Assert.eq(0L, Casino.lossLimit(id), "removal only tomorrow");
		Casino.today = () -> day.plusDays(2);
		Assert.eq(c.dailyLossLimit, Casino.lossLimit(id), "removed");
		ctx.detail("lower at once, raise or remove from tomorrow");
	}

	private static void doubleOrNothing(TestContext ctx) {
		CasinoConfig c = setup(ctx);
		FakePlayer a = funded(ctx, "CasinoDon", 10_000_000);
		setTreasuryFree(10_000_000);
		Assert.isTrue(Services.find(Services.DoubleOrNothing.class).isPresent(), "DoubleOrNothing service registered");
		Assert.isFalse(Services.find(Services.DoubleOrNothing.class).get().offer(a, 1_000), "FakePlayer gets no offer");
		Assert.isTrue(CasinoOffers.check(a.getUUID(), 1_000) == null, "allowed amount: " + CasinoOffers.check(a.getUUID(), 1_000));
		Assert.notNull(CasinoOffers.check(a.getUUID(), Casino.maxBet() + 1), "above the max bet: no offer");
		Assert.notNull(CasinoOffers.check(a.getUUID(), 20_000_000), "more than balance: no offer");
		// accepting = normal house round flagged pvp
		Round r = Casino.startCoinflip(a.getUUID(), name(a), 1_000, true, always(0)).round();
		Assert.isTrue(r.pvp && r.pot == 2_000, "PvP double or nothing won");
		Casino.settle(r.id, null);
		c.enabled = false;
		Assert.notNull(CasinoOffers.check(a.getUUID(), 1_000), "casino off: no offer");
		ctx.detail("service registered, checks as for the coin flip");
	}

	private static void oneRound(TestContext ctx) {
		setup(ctx);
		FakePlayer a = funded(ctx, "CasinoOne", 10_000_000);
		setTreasuryFree(100_000_000);
		// first card: ace (hiloRank: 2 + 12)
		Round r = Casino.startHilo(a.getUUID(), name(a), 1_000, new Scripted(12, 0)).round();
		Assert.eq(14, r.card, "ace drawn");
		Casino.Start second = Casino.startSlots(a.getUUID(), name(a), 1_000, always(0));
		Assert.isFalse(second.ok(), "second simultaneous round refused");
		Assert.eq(10_000_000L - 1_000, EconomyService.balance(a.getUUID()), "only one stake taken");
		Assert.isFalse(Casino.cashOut(r), "no cash-out without a hit");
		Assert.eq(Casino.Step.INVALID, Casino.hiloGuess(r, true, new SplittableRandom(1)), "ace: higher impossible");
		Assert.isTrue(Casino.isOpen(r), "round continues");
		// lower is certain (everything is below an ace): multiplier ×1
		Assert.eq(Casino.Step.WIN, Casino.hiloGuess(r, false, new SplittableRandom(1)), "ace: lower always wins");
		Assert.eq("1", r.num + "", "×1 after a safe guess");
		Casino.Settled s = Casino.settle(r.id, null);
		Assert.eq(960L, s.payout(), "payout 0.96 × stake after a safe guess");
		Assert.eq(10_000_000L - 40, EconomyService.balance(a.getUUID()), "balance");
		ctx.detail("one round per player, impossible guesses refused");
	}

	/**
	 * Idle: each move resets the clock, then the round expires after idleTimeoutMinutes (rounds without lastActionAt
	 * use createdAt).
	 */
	private static void idleTimeout(TestContext ctx) {
		CasinoConfig c = setup(ctx);
		c.idleTimeoutMinutes = 5;
		FakePlayer a = funded(ctx, "CasinoIdle", 10_000_000);
		setTreasuryFree(100_000_000);
		UUID id = a.getUUID();
		long[] now = {1_000_000_000L};
		Casino.clock = () -> now[0];
		long minute = 60_000L;
		// hi-lo (king): start counts as a move
		Round r = Casino.startHilo(id, name(a), 1_000, new Scripted(11, 0)).round();
		Assert.eq(now[0], r.lastActionAt, "start = last move");
		now[0] += 4 * minute;
		Assert.isFalse(Casino.idleExpired(r, now[0]), "still active after 4 min");
		Assert.eq(Casino.Step.WIN, Casino.hiloGuess(r, false, new Scripted(0, 0)), "king to 2, lower wins");
		Assert.eq(now[0], r.lastActionAt, "guess = last move");
		now[0] += 5 * minute - 1;
		Assert.isFalse(Casino.idleExpired(r, now[0]), "just under 5 min after the guess");
		now[0] += 1;
		Assert.isTrue(Casino.idleExpired(r, now[0]), "5 min without a move: expired");
		long before = EconomyService.balance(id);
		Casino.Settled s = Casino.settle(r.id, Casino.IDLE_REASON);
		Assert.isFalse(s.refund(), "no refund after a winning step");
		Assert.eq(before + Casino.cashOutValue(s.round()), EconomyService.balance(id), "expiry pays the current value");
		// mines without a move: expiry = stake back; revealing resets the clock; a clock jump back counts too
		Round m = Casino.startMines(id, name(a), 1_000, 3, new SplittableRandom(4)).round();
		now[0] += 3 * minute;
		int safe = 0;
		while (m.mines.contains(safe)) safe++;
		Assert.eq(Casino.Step.WIN, Casino.minesReveal(m, safe), "safe tile");
		Assert.eq(now[0], m.lastActionAt, "tile = last move");
		Assert.isFalse(Casino.idleExpired(m, now[0] + 4 * minute), "active 4 min after the tile");
		Assert.isTrue(Casino.idleExpired(m, now[0] - 6 * minute), "clock 6 min back: expired, not stuck");
		Casino.settle(m.id, null);
		Round fresh = Casino.startMines(id, name(a), 1_000, 3, new SplittableRandom(5)).round();
		now[0] += 5 * minute;
		Assert.isTrue(Casino.idleExpired(fresh, now[0]), "round with no move expires");
		long b0 = EconomyService.balance(id);
		Casino.Settled fs = Casino.settle(fresh.id, Casino.IDLE_REASON);
		Assert.isTrue(fs.refund(), "no move: refund");
		Assert.eq(b0 + 1_000, EconomyService.balance(id), "stake back");
		// stored round without lastActionAt: createdAt
		Round old = new Round();
		old.createdAt = 12_345L;
		com.google.gson.JsonObject json = DataStore.gson().toJsonTree(old).getAsJsonObject();
		json.remove("lastActionAt");
		Round loaded = DataStore.gson().fromJson(json, Round.class);
		loaded.fixNulls();
		Assert.eq(12_345L, loaded.lastActionAt, "missing lastActionAt = createdAt");
		// config limits
		CasinoConfig bad = new CasinoConfig();
		bad.idleTimeoutMinutes = 0;
		Assert.assertThrows(IllegalArgumentException.class, bad::validate, "idleTimeoutMinutes 0 refused");
		ctx.detail("a move resets the clock, expiry after " + c.idleTimeoutMinutes + " min pays the current value");
	}
}
