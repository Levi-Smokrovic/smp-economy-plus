package dev.smpeconomy.features.casino.games.horserace;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
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
import dev.smpeconomy.features.casino.Game;
import dev.smpeconomy.features.casino.games.horserace.HorseData.Bet;
import dev.smpeconomy.features.casino.games.horserace.HorseData.Pending;
import dev.smpeconomy.features.casino.games.horserace.HorseData.Race;

/**
 * Horse race selftests ({@code /smpeco selftest casino-horserace}). Engine tests run on a private {@link RaceEngine} with its own
 * data, clock and day, never on the live race, but book real money through the treasury with test players.
 */
final class HorseSelfTests {
	private static final String F = "casino-horserace";
	private static final int SIM_RACES = 500_000;

	private HorseSelfTests() {}

	static void register() {
		SelfTests.register(F, "rtp", HorseSelfTests::rtp);
		SelfTests.register(F, "math", HorseSelfTests::math);
		SelfTests.register(F, "track", HorseSelfTests::track);
		SelfTests.register(F, "config", HorseSelfTests::config);
		SelfTests.register(F, "guards", HorseSelfTests::guards);
		SelfTests.register(F, "coverage", HorseSelfTests::coverage);
		SelfTests.register(F, "loss-limit", HorseSelfTests::lossLimit);
		SelfTests.register(F, "shared-cover", HorseSelfTests::sharedCover);
		SelfTests.register(F, "shared-limit", HorseSelfTests::sharedLimit);
		SelfTests.register(F, "conservation", HorseSelfTests::conservation);
		SelfTests.register(F, "exactly-once", HorseSelfTests::exactlyOnce);
		SelfTests.register(F, "crash-recovery", HorseSelfTests::crashRecovery);
		SelfTests.register(F, "pending-payout", HorseSelfTests::pendingPayout);
		SelfTests.register(F, "cadence", HorseSelfTests::cadence);
	}

	/** Random source whose winner draws (nextInt(10000)) can be forced; everything else from a fixed seed. */
	static final class Steer implements RandomGenerator {
		private final SplittableRandom base;
		private final Deque<Integer> rolls = new ArrayDeque<>();

		Steer(long seed) {
			base = new SplittableRandom(seed);
		}

		@Override
		public long nextLong() {
			return base.nextLong();
		}

		@Override
		public int nextInt(int bound) {
			if (bound == HorseMath.BP && !rolls.isEmpty()) return rolls.poll();
			return base.nextInt(bound);
		}

		/** The next race is won by {@code horse}. */
		void win(Race r, int horse) {
			int roll = 0;
			for (int i = 0; i < horse; i++) roll += r.chances[i];
			rolls.add(roll);
		}
	}

	/** Private engine with recording events. */
	static final class Rig {
		HorseData data = new HorseData();
		final HorseConfig cfg = new HorseConfig();
		final long[] now = {1_900_000_000_000L};
		LocalDate day = LocalDate.of(2031, 3, 1);
		final RandomGenerator rng;
		int firstBets;
		int starts;
		final List<Object[]> settled = new ArrayList<>();
		final RaceEngine e;

		Rig(RandomGenerator rng) {
			this.rng = rng;
			e = new RaceEngine(() -> data, () -> {}, () -> cfg, rng, () -> now[0], () -> day, new RaceEngine.Events() {
				@Override
				public void firstBet(Race race, Bet bet) {
					firstBets++;
				}

				@Override
				public void raceStarted(Race race) {
					starts++;
				}

				@Override
				public void betSettled(Race race, Bet bet, long amount, boolean refund, boolean paid, String why) {
					settled.add(new Object[] {bet.player, amount, refund, paid});
				}
			});
		}

		Race card() {
			return e.card();
		}

		/** Runs the card to the end: start at its closing time (or now), settle when the winner crosses. */
		Race run() {
			Race r = e.card();
			if (r.closesAt <= 0) {
				Assert.isTrue(e.forceStart(now[0]), "start");
			} else {
				now[0] = Math.max(now[0], r.closesAt);
				Assert.isTrue(e.startIfDue(now[0]), "start due");
			}
			now[0] = Math.max(now[0], r.settleAt);
			Assert.isTrue(e.finishIfDue(now[0]), "settle due");
			return r;
		}
	}

	private static Steer steer(Rig g) {
		return (Steer) g.rng;
	}

	/** Casino limits to defaults (restored after the test), treasury preserved, private engine. */
	private static Rig setup(TestContext ctx, RandomGenerator rng) {
		ctx.preserveTreasury();
		CasinoConfig c = Casino.cfg();
		boolean enabled = c.enabled;
		long minBet = c.minBet;
		long maxBet = c.maxBet;
		double maxBetPct = c.maxBetTreasuryPercent;
		double maxWinPct = c.maxWinTreasuryPercent;
		long loss = c.dailyLossLimit;
		ctx.cleanup(() -> {
			c.enabled = enabled;
			c.minBet = minBet;
			c.maxBet = maxBet;
			c.maxBetTreasuryPercent = maxBetPct;
			c.maxWinTreasuryPercent = maxWinPct;
			c.dailyLossLimit = loss;
		});
		CasinoConfig def = new CasinoConfig();
		c.enabled = def.enabled;
		c.minBet = def.minBet;
		c.maxBet = def.maxBet;
		c.maxBetTreasuryPercent = def.maxBetTreasuryPercent;
		c.maxWinTreasuryPercent = def.maxWinTreasuryPercent;
		c.dailyLossLimit = def.dailyLossLimit;
		return new Rig(rng);
	}

	/** Treasury so that exactly {@code free} is left after the casino's own reservations. */
	private static void setTreasuryFree(long free) {
		EconomyService.set(Treasury.ID, Math.addExact(free, Casino.reservedTotal(null)));
	}

	/** Casino coin flip that always loses. */
	private static final RandomGenerator LOSE = new RandomGenerator() {
		@Override
		public long nextLong() {
			return 0;
		}

		@Override
		public int nextInt(int bound) {
			return bound - 1;
		}
	};

	/** Registers the rig's engine with the casino like the live race (removed after the test). */
	private static void registerWithCasino(TestContext ctx, Rig g) {
		Casino.registerBook(g.e);
		ctx.cleanup(() -> Casino.unregisterBook(g.e));
	}

	private static FakePlayer funded(TestContext ctx, String name, long cents) {
		FakePlayer p = ctx.player(name);
		EconomyService.set(p.getUUID(), cents);
		return p;
	}

	private static String name(FakePlayer p) {
		return p.getGameProfile().name();
	}

	private static long bal(FakePlayer p) {
		return EconomyService.balance(p.getUUID());
	}

	private static String fmt(double d) {
		return String.format(java.util.Locale.ROOT, "%.4f", d);
	}

	/** Horse with the highest / lowest odds on the card. */
	private static int longest(Race r) {
		int best = 0;
		for (int h = 1; h < HorseMath.HORSES; h++) if (r.odds[h] > r.odds[best]) best = h;
		return best;
	}

	private static int shortest(Race r) {
		int best = 0;
		for (int h = 1; h < HorseMath.HORSES; h++) if (r.odds[h] < r.odds[best]) best = h;
		return best;
	}

	/** 500,000 simulated races (random horse) + 250,000 (always the favourite): RTP within tolerance and in 95–97 %. */
	private static void rtp(TestContext ctx) {
		int edgeBp = new HorseConfig().edgeBp();
		Assert.eq(450, edgeBp, "default base edge 4.5 %");
		SplittableRandom rng = new SplittableRandom(20261010L);
		double minBet = 1;
		double maxBet = 0;
		double[] r1 = simulate(rng, edgeBp, SIM_RACES, false);
		double[] r2 = simulate(rng, edgeBp, SIM_RACES / 2, true);
		for (double[] r : new double[][] {r1, r2}) {
			minBet = Math.min(minBet, r[2]);
			maxBet = Math.max(maxBet, r[3]);
		}
		Assert.check(minBet >= 0.95 && maxBet <= 0.97, "every bet's RTP in [0.95, 0.97]: " + fmt(minBet) + " … " + fmt(maxBet));
		Assert.check(maxBet <= 1 - edgeBp / 10_000.0 + 1e-12, "never above 1 − base edge");
		// the extremes of the config range stay in 95–97 % as well
		SplittableRandom r3 = new SplittableRandom(3);
		for (int edge : new int[] {300, 450}) {
			for (int i = 0; i < 20_000; i++) {
				int[] c = HorseMath.chances(r3);
				int[] o = HorseMath.odds(c, edge);
				for (int h = 0; h < HorseMath.HORSES; h++) {
					double b = HorseMath.betRtp(c[h], o[h]);
					Assert.check(b >= 0.95 && b <= 0.97, "edge " + edge + "bp: bet RTP " + fmt(b) + " in [0.95, 0.97]");
				}
			}
		}
		ctx.detail("random horse: simulated " + fmt(r1[0]) + " vs expected " + fmt(r1[1]) + " (" + SIM_RACES + " races); favourite: "
			+ fmt(r2[0]) + " vs " + fmt(r2[1]) + " (" + SIM_RACES / 2 + "); bet RTP " + fmt(minBet) + "–" + fmt(maxBet));
	}

	/** {simulated RTP, expected RTP, min bet RTP, max bet RTP}; asserts the simulation is within 4σ of the expectation. */
	private static double[] simulate(SplittableRandom rng, int edgeBp, int n, boolean favourite) {
		double sum = 0;
		double sumSq = 0;
		double exp = 0;
		double min = 1;
		double max = 0;
		long stake = 10_000;
		for (int i = 0; i < n; i++) {
			int[] c = HorseMath.chances(rng);
			int[] o = HorseMath.odds(c, edgeBp);
			for (int h = 0; h < HorseMath.HORSES; h++) {
				double b = HorseMath.betRtp(c[h], o[h]);
				min = Math.min(min, b);
				max = Math.max(max, b);
			}
			int pick = rng.nextInt(HorseMath.HORSES);
			if (favourite) {
				for (int h = 0; h < HorseMath.HORSES; h++) if (c[h] > c[pick]) pick = h;
			}
			int w = HorseMath.winner(c, rng);
			double x = w == pick ? HorseMath.payout(stake, o[pick]) / (double) stake : 0;
			sum += x;
			sumSq += x * x;
			exp += HorseMath.betRtp(c[pick], o[pick]);
		}
		double mean = sum / n;
		double expected = exp / n;
		double var = Math.max(0, sumSq / n - mean * mean);
		double tol = 4 * Math.sqrt(var / n) + 0.0005;
		Assert.check(Math.abs(mean - expected) <= tol, (favourite ? "favourite" : "random horse") + ": simulated RTP " + fmt(mean)
			+ " deviates from " + fmt(expected) + " (tolerance " + fmt(tol) + ")");
		Assert.check(expected >= 0.95 && expected <= 0.97, "expected RTP " + fmt(expected) + " in [0.95, 0.97]");
		Assert.check(mean >= 0.95 - tol && mean <= 0.97 + tol, "simulated RTP " + fmt(mean) + " in [0.95, 0.97] ± " + fmt(tol));
		return new double[] {mean, expected, min, max};
	}

	private static void math(TestContext ctx) {
		Assert.eq(318, HorseMath.odds(3_000, 450), "30 % at 4.5 %: floor(9550 / 30) = ×3.18");
		Assert.eq(95, HorseMath.odds(10_000, 450), "100 %: ×0.95");
		Assert.eq(1_364, HorseMath.odds(700, 450), "7 %: ×13.64");
		Assert.eq(31_800L, HorseMath.payout(10_000, 318), "$100 × 3.18");
		Assert.eq(3_176L, HorseMath.payout(999, 318), "rounded down");
		Assert.eq(Long.MAX_VALUE, HorseMath.payoutSafe(Long.MAX_VALUE / 2, 2_000), "overflow → MAX (never admitted)");
		Assert.eq("×3.18", HorseMath.formatOdds(318), "format");
		Assert.eq("×12.05", HorseMath.formatOdds(1_205), "format 2");
		Assert.eq("×0.95", HorseMath.formatOdds(95), "format 3");
		// cards: exactly 10,000 bp, every chance within the expected band, odds never above maxOdds
		SplittableRandom rng = new SplittableRandom(77);
		int maxOdds = HorseMath.maxOdds(450);
		int seenMin = Integer.MAX_VALUE;
		int seenMax = 0;
		for (int i = 0; i < 50_000; i++) {
			int[] c = HorseMath.chances(rng);
			Assert.eq(HorseMath.BP, Arrays.stream(c).sum(), "chances sum to 10,000");
			for (int h = 0; h < HorseMath.HORSES; h++) {
				Assert.check(c[h] >= 400 && c[h] <= 4_100, "chance " + c[h] + " in [400, 4100]");
				Assert.check(HorseMath.odds(c[h], 450) <= maxOdds, "odds ≤ maxOdds ×" + maxOdds / 100.0);
				seenMin = Math.min(seenMin, c[h]);
				seenMax = Math.max(seenMax, c[h]);
			}
		}
		// winner distribution follows the chances (5σ per horse)
		int[] card = {3_100, 2_300, 1_700, 1_300, 900, 700};
		int n = 200_000;
		int[] wins = new int[HorseMath.HORSES];
		for (int i = 0; i < n; i++) wins[HorseMath.winner(card, rng)]++;
		for (int h = 0; h < HorseMath.HORSES; h++) {
			double p = card[h] / 10_000.0;
			double sd = Math.sqrt(n * p * (1 - p));
			Assert.check(Math.abs(wins[h] - n * p) <= 5 * sd, "horse " + (h + 1) + " wins " + wins[h] + " ≈ " + (long) (n * p));
		}
		ctx.detail("odds/payout exact, chances " + seenMin + "–" + seenMax + " bp, max odds ×" + maxOdds / 100.0 + ", winner distribution ok");
	}

	private static void track(TestContext ctx) {
		int checked = 0;
		for (long seed = 0; seed < 3_000; seed++) {
			int w = (int) (seed % HorseMath.HORSES);
			int[][] f = HorseMath.frames(seed * 7919, w);
			Assert.isTrue(Arrays.deepEquals(f, HorseMath.frames(seed * 7919, w)), "deterministic");
			int wf = HorseMath.winnerFrame(seed * 7919, w);
			Assert.eq(HorseMath.endFrame(wf) + 1, f.length, "frame count");
			for (int h = 0; h < HorseMath.HORSES; h++) {
				Assert.eq(0, f[0][h], "all at the gate");
				for (int t = 1; t < f.length; t++) {
					Assert.check(f[t][h] >= f[t - 1][h] && f[t][h] <= HorseMath.FINISH, "monotone, on the track");
				}
				if (h != w) Assert.check(f[wf][h] < HorseMath.FINISH, "nobody else has finished when the winner crosses");
			}
			Assert.eq(HorseMath.FINISH, f[wf][w], "winner crosses in its frame");
			Assert.check(f[wf - 1][w] < HorseMath.FINISH, "winner not earlier");
			int[] places = HorseMath.places(f, f.length - 1);
			Assert.eq(1, places[w], "winner is 1st");
			boolean[] used = new boolean[HorseMath.HORSES + 1];
			for (int p : places) {
				if (p == 0) continue;
				Assert.isFalse(used[p], "places unique");
				used[p] = true;
			}
			checked++;
		}
		ctx.detail(checked + " races: winner first, positions monotone, places unique");
	}

	private static void config(TestContext ctx) {
		HorseConfig c = new HorseConfig();
		c.validate();
		HorseConfig hi = new HorseConfig();
		hi.baseHouseEdgePercent = 5.0;
		Assert.assertThrows(IllegalArgumentException.class, hi::validate, "edge 5 % rejected (RTP would drop below 95 %)");
		HorseConfig lo = new HorseConfig();
		lo.baseHouseEdgePercent = 2.9;
		Assert.assertThrows(IllegalArgumentException.class, lo::validate, "edge < 3 % rejected");
		HorseConfig fast = new HorseConfig();
		fast.raceIntervalSeconds = 10;
		Assert.assertThrows(IllegalArgumentException.class, fast::validate, "interval < 30 s rejected");
		ctx.detail("validation ok");
	}

	private static void guards(TestContext ctx) {
		Rig g = setup(ctx, new Steer(1));
		CasinoConfig c = Casino.cfg();
		FakePlayer a = funded(ctx, "HorseGuardA", 100_000_000);
		FakePlayer b = funded(ctx, "HorseGuardB", 100_000_000);
		FakePlayer poor = funded(ctx, "HorseGuardPoor", 500);
		setTreasuryFree(10_000_000); // $100,000 → max bet 2 % = $2,000
		Race r = g.card();
		long maxBet = Casino.maxBet();
		Assert.check(maxBet > c.minBet && maxBet <= Treasury.balance() / 50, "max bet ≤ 2 % of the treasury (" + maxBet + ")");
		long before = bal(a);
		RaceEngine.Placed over = g.e.place(a.getUUID(), name(a), 0, maxBet + 1);
		Assert.isFalse(over.ok(), "above the max bet rejected");
		Assert.check(over.error().contains("Maximum bet"), "message names the max bet: " + over.error());
		Assert.eq(before, bal(a), "rejected: nothing taken");
		Assert.isTrue(r.bets.isEmpty() && r.closesAt == 0, "rejected: no bet, no countdown");
		Assert.check(g.e.place(a.getUUID(), name(a), 0, c.minBet - 1).error().contains("Minimum bet"), "below the minimum rejected");
		Assert.check(g.e.place(a.getUUID(), name(a), 6, 1_000).error().contains("Pick a horse"), "horse 7 rejected");
		Assert.check(g.e.place(a.getUUID(), name(a), -1, 1_000).error().contains("Pick a horse"), "horse 0 rejected");
		c.enabled = false;
		Assert.check(g.e.place(a.getUUID(), name(a), 0, 1_000).error().contains("closed"), "casino off: closed");
		c.enabled = true;
		g.cfg.enabled = false;
		Assert.check(g.e.place(a.getUUID(), name(a), 0, 1_000).error().contains("closed"), "horse race off: closed");
		g.cfg.enabled = true;
		Assert.check(g.e.place(poor.getUUID(), name(poor), 0, 1_000).error().contains("Not enough money"), "not enough money");
		Assert.eq(500L, bal(poor), "poor: nothing taken");
		Assert.eq(before, bal(a), "still nothing taken");
		long t0 = Treasury.balance();
		// valid bets
		RaceEngine.Placed ok = g.e.place(a.getUUID(), name(a), 2, 10_000);
		Assert.isTrue(ok.ok(), "bet placed: " + ok.error());
		Assert.eq(before - 10_000, bal(a), "stake taken");
		Assert.eq(t0 + 10_000, Treasury.balance(), "stake in the treasury");
		Assert.eq(r.odds[2], ok.bet().odds, "odds of the card fixed in the bet");
		Assert.eq(-1L, ok.bet().payout, "no outcome while betting");
		Assert.eq(g.now[0] + g.cfg.bettingWindowSeconds * 1000L, r.closesAt, "first bet starts the countdown");
		Assert.eq(1, g.firstBets, "first-bet event");
		Assert.check(g.e.place(a.getUUID(), name(a), 3, 10_000).error().contains("already"), "second bet on the same race rejected");
		long closes = r.closesAt;
		g.now[0] += 5_000;
		Assert.isTrue(g.e.place(b.getUUID(), name(b), 4, 20_000).ok(), "B bets on the same race");
		Assert.eq(closes, r.closesAt, "later bets don't move the start");
		Assert.eq(1, g.firstBets, "only one first-bet event");
		Assert.isFalse(g.e.startIfDue(closes - 1), "not before the countdown");
		g.now[0] = closes;
		FakePlayer late = funded(ctx, "HorseGuardLate", 100_000);
		Assert.check(g.e.place(late.getUUID(), name(late), 0, 1_000).error().contains("closed"), "betting closed at the start time");
		steer(g).win(r, 2);
		Assert.isTrue(g.e.startIfDue(g.now[0]), "race starts");
		Assert.eq(HorseData.RUNNING, r.state, "running");
		Assert.eq(2, r.winner, "winner decided at the start");
		Assert.eq(HorseMath.payout(10_000, r.odds[2]), r.bets.get(a.getUUID()).payout, "A's payout stored before the animation");
		Assert.eq(0L, r.bets.get(b.getUUID()).payout, "B's payout (0) stored");
		Assert.eq(before - 10_000, bal(a), "nothing paid before the winner crosses");
		Assert.check(g.e.place(late.getUUID(), name(late), 0, 1_000).error().contains("closed"), "no bets while running");
		Assert.isFalse(g.e.finishIfDue(r.settleAt - 1), "not settled before the winner crosses");
		Assert.isTrue(g.e.finishIfDue(r.settleAt), "settled when the winner crosses");
		Assert.eq(before - 10_000 + HorseMath.payout(10_000, r.odds[2]), bal(a), "A paid");
		Assert.eq(100_000_000L - 20_000, bal(b), "B lost the stake");
		Assert.eq(100_000L, bal(late), "late player untouched");
		Assert.isTrue(g.data.race != r && HorseData.BETTING.equals(g.data.race.state), "next card open");
		ctx.detail("max/min bet, horse, on/off, money, one bet per race, closing time, outcome before animation");
	}

	private static void coverage(TestContext ctx) {
		Rig g = setup(ctx, new Steer(2));
		CasinoConfig c = Casino.cfg();
		FakePlayer a = funded(ctx, "HorseCovA", 100_000_000);
		FakePlayer b = funded(ctx, "HorseCovB", 100_000_000);
		FakePlayer d = funded(ctx, "HorseCovC", 100_000_000);
		setTreasuryFree(10_000_000); // $100,000, max bet $2,000, max win $50,000
		Race r = g.card();
		// pending payouts of the race count as reserved: leave $5,000 of cover
		long avail = 500_000;
		Pending p = new Pending();
		p.player = d.getUUID();
		p.amount = Casino.freeCoverage(null) - avail;
		p.raceId = 0;
		g.data.pending.add(p);
		Assert.eq(p.amount, g.e.reserved(), "pending payouts are reserved");
		int x = longest(r);
		int y = x == 0 ? 1 : 0;
		long s1 = avail * 6 / 10 * 100 / r.odds[x];
		long s2 = avail * 6 / 10 * 100 / r.odds[y];
		long p1 = HorseMath.payout(s1, r.odds[x]);
		long p2 = HorseMath.payout(s2, r.odds[y]);
		Assert.check(s1 >= c.minBet && s1 <= Casino.maxBet() && s2 <= Casino.maxBet(), "stakes within limits");
		Assert.isTrue(g.e.place(a.getUUID(), name(a), x, s1).ok(), "A on the outsider (60 % of the cover)");
		Assert.eq(p1, RaceEngine.worstCase(r), "worst case = A's payout");
		// B on another horse: both together exceed the cover, but only one horse can win → admitted
		Assert.check(p1 + p2 > avail, "precondition: sum above the cover");
		Assert.isTrue(g.e.place(b.getUUID(), name(b), y, s2).ok(), "B on another horse admitted (worst case = max, not sum)");
		Assert.eq(Math.max(p1, p2), RaceEngine.worstCase(r), "worst case = most expensive winner");
		// C on A's horse: that horse would cost more than the cover → rejected, nothing taken
		long before = bal(d);
		RaceEngine.Placed rc = g.e.place(d.getUUID(), name(d), x, s1);
		Assert.isFalse(rc.ok(), "C on the same horse rejected");
		Assert.check(rc.error().contains("can't cover"), "message: " + rc.error());
		Assert.eq(before, bal(d), "rejected: nothing taken");
		long max = g.e.maxStake(d.getUUID(), x);
		Assert.check(max > 0, "C may bet less");
		Assert.isTrue(g.e.covered(r, x, max), "max stake is covered");
		Assert.isFalse(g.e.covered(r, x, max + 1), "max stake + 1 is not");
		Assert.isTrue(g.e.place(d.getUUID(), name(d), x, max).ok(), "C with the max stake admitted");
		Assert.check(RaceEngine.worstCase(r) <= Casino.freeCoverage(null) - g.e.pendingTotal(), "worst case covered");
		// max win (casino): a payout above the max win is never admitted
		FakePlayer e2 = funded(ctx, "HorseCovD", 100_000_000);
		c.maxWinTreasuryPercent = 0.01;
		Assert.isFalse(g.e.covered(r, shortest(r), 100_000), "payout above the max win not covered");
		Assert.check(g.e.place(e2.getUUID(), name(e2), shortest(r), 100_000).error().contains("can't cover"), "rejected by max win");
		c.maxWinTreasuryPercent = 50;
		// running: reservation = the decided payouts
		g.data.pending.clear();
		steer(g).win(r, x);
		g.e.forceStart(g.now[0]);
		long decided = 0;
		for (Bet bet : r.bets.values()) decided += bet.payout;
		Assert.eq(decided, RaceEngine.worstCase(r), "running race reserves exactly its payouts");
		g.now[0] = r.settleAt;
		g.e.finishIfDue(g.now[0]);
		Assert.eq(0L, g.e.reserved(), "nothing reserved after settling");
		ctx.detail("worst case per horse, pending reserved, max stake exact, max win");
	}

	private static void lossLimit(TestContext ctx) {
		Rig g = setup(ctx, new Steer(3));
		CasinoConfig c = Casino.cfg();
		c.dailyLossLimit = 100_000; // $1,000
		FakePlayer a = funded(ctx, "HorseLoss", 10_000_000);
		setTreasuryFree(100_000_000);
		UUID id = a.getUUID();
		Assert.eq(100_000L, g.e.lossRoom(id), "full room");
		Race r = g.card();
		Assert.isTrue(g.e.place(id, name(a), 0, 40_000).ok(), "bet 1");
		Assert.eq(60_000L, g.e.lossRoom(id), "open bet counts");
		steer(g).win(r, 1);
		g.run();
		Assert.eq(40_000L, g.e.netToday(id), "lost $400");
		Assert.eq(60_000L, g.e.lossRoom(id), "room $600");
		Race r2 = g.card();
		RaceEngine.Placed over = g.e.place(id, name(a), 0, 70_000);
		Assert.isFalse(over.ok(), "above the room rejected");
		Assert.check(over.error().contains("Daily loss limit"), "message: " + over.error());
		Assert.isTrue(g.e.place(id, name(a), 0, 60_000).ok(), "the rest is allowed");
		Assert.eq(0L, g.e.lossRoom(id), "room used up by the open bet");
		steer(g).win(r2, 1);
		g.run();
		Assert.eq(0L, g.e.lossRoom(id), "limit reached");
		Assert.check(g.e.place(id, name(a), 0, c.minBet).error().contains("Daily loss limit"), "nothing more today");
		Assert.eq(0L, g.e.maxStake(id, -1), "max stake 0");
		// next day: full room; a win widens it (like the casino)
		g.day = g.day.plusDays(1);
		Assert.eq(100_000L, g.e.lossRoom(id), "new day");
		Race r3 = g.card();
		Assert.isTrue(g.e.place(id, name(a), 3, 10_000).ok(), "bet on a new day");
		steer(g).win(r3, 3);
		g.run();
		long win = HorseMath.payout(10_000, r3.odds[3]);
		Assert.eq(10_000 - win, g.e.netToday(id), "won → negative loss");
		Assert.eq(100_000 + win - 10_000, g.e.lossRoom(id), "winnings widen the room");
		// refunds don't count
		Race r4 = g.card();
		long net = g.e.netToday(id);
		Assert.isTrue(g.e.place(id, name(a), 0, 5_000).ok(), "bet to be refunded");
		g.e.refundAll("test");
		Assert.eq(net, g.e.netToday(id), "refund not counted");
		Assert.isTrue(r4.bets.isEmpty(), "refunded bet gone");
		// casino losses count too (shared daily limit)
		c.dailyLossLimit = 50_000;
		long room = g.e.lossRoom(id);
		Casino.Start cf = Casino.startCoinflip(id, name(a), 20_000, false, new RandomGenerator() {
			@Override
			public long nextLong() {
				return 0;
			}

			@Override
			public int nextInt(int bound) {
				return bound - 1; // coin flip lost
			}
		});
		Assert.isTrue(cf.ok(), "casino coin flip: " + cf.error());
		Casino.settle(cf.round().id, null);
		Assert.eq(20_000L, Casino.netLossToday(id), "casino loss booked");
		Assert.eq(Math.max(0, room - 20_000), g.e.lossRoom(id), "casino loss reduces the race room");
		ctx.detail("open bet, losses, rest, next day, wins, refunds, shared with the casino");
	}

	/** One cover pool: the casino games see the race's worst case; the race does not count its own share twice. */
	private static void sharedCover(TestContext ctx) {
		Rig g = setup(ctx, new Steer(9));
		FakePlayer a = funded(ctx, "HorseShareA", 100_000_000);
		FakePlayer b = funded(ctx, "HorseShareB", 100_000_000);
		setTreasuryFree(10_000_000); // $100,000: max bet $2,000, max win $50,000
		Race r = g.card();
		int x = longest(r);
		int y = x == 0 ? 1 : 0;
		long maxAlone = g.e.maxStake(b.getUUID(), x);
		registerWithCasino(ctx, g);
		Assert.eq(maxAlone, g.e.maxStake(b.getUUID(), x), "registered: the race does not subtract its own reservation twice");
		long free0 = Casino.freeCoverage(null);
		long stake = Casino.maxBet();
		Assert.isTrue(g.e.place(a.getUUID(), name(a), x, stake).ok(), "A: max bet on the outsider");
		long worst = HorseMath.payout(stake, r.odds[x]);
		Assert.eq(worst, g.e.reserved(), "race reserves A's payout");
		Assert.eq(free0 + stake - worst, Casino.freeCoverage(null), "casino cover: stake in, the race's worst case reserved");
		Assert.eq(Casino.freeCoverage(null), g.e.freeCoverage(), "race and casino see the same free cover");
		// $100 left after the casino's and the race's reservations: a $100 coin flip (pays $200) is refused
		EconomyService.set(Treasury.ID, Math.addExact(Casino.reservedTotal(null), 10_000));
		Assert.eq(10_000L, Casino.coverLimit(null), "casino cover limit = what the race left");
		Casino.unregisterBook(g.e);
		Assert.check(Casino.coverLimit(null) >= 20_000, "precondition: without the race's reservation the flip would be covered");
		Casino.registerBook(g.e);
		long bal = bal(b);
		Assert.isFalse(Casino.startCoinflip(b.getUUID(), name(b), 10_000, false, LOSE).ok(), "casino round refused (race reservation)");
		Assert.eq(bal, bal(b), "refused: nothing taken");
		Assert.eq(5_000L, Casino.maxStake(b.getUUID(), Game.COINFLIP, 0), "casino max stake = half of the remaining cover");
		// the race is settled (A lost) → the reservation is released for the casino
		steer(g).win(r, y);
		g.run();
		Assert.eq(0L, g.e.reserved(), "nothing reserved after the race");
		Assert.eq(Math.min(Casino.maxWin(), 10_000 + worst), Casino.coverLimit(null), "casino cover back after the race");
		ctx.detail("casino sees the race's worst case, no double count, casino round refused, released after the race");
	}

	/** One daily loss limit for the race and the casino games (open bets included); the admin reset clears both. */
	private static void sharedLimit(TestContext ctx) {
		Rig g = setup(ctx, new Steer(10));
		CasinoConfig c = Casino.cfg();
		c.dailyLossLimit = 100_000; // $1,000
		FakePlayer a = funded(ctx, "HorseShareLossA", 10_000_000);
		FakePlayer b = funded(ctx, "HorseShareLossB", 10_000_000);
		setTreasuryFree(100_000_000);
		registerWithCasino(ctx, g);
		UUID id = a.getUUID();
		Assert.eq(100_000L, Casino.lossRoom(id), "full room");
		Race r = g.card();
		Assert.isTrue(g.e.place(id, name(a), 0, 40_000).ok(), "race bet $400");
		Assert.eq(60_000L, Casino.lossRoom(id), "open race bet counts for the casino");
		Assert.eq(60_000L, g.e.lossRoom(id), "race: open bet counted once");
		steer(g).win(r, 1);
		g.run();
		Assert.eq(40_000L, Casino.netLossToday(id), "race loss in the casino's daily loss");
		Assert.eq(60_000L, Casino.lossRoom(id), "casino room $600");
		long bal = bal(a);
		Assert.isFalse(Casino.startCoinflip(id, name(a), 70_000, false, LOSE).ok(), "casino: above the shared room refused");
		Assert.eq(bal, bal(a), "refused: nothing taken");
		Casino.Start cf = Casino.startCoinflip(id, name(a), 60_000, false, LOSE);
		Assert.isTrue(cf.ok(), "casino: the rest is allowed: " + cf.error());
		Casino.settle(cf.round().id, null);
		Assert.eq(100_000L, Casino.netLossToday(id), "race + casino losses");
		Assert.eq(0L, Casino.lossRoom(id), "casino: limit used up");
		Assert.eq(0L, g.e.lossRoom(id), "race: limit used up");
		Assert.check(g.e.place(id, name(a), 0, c.minBet).error().contains("Daily loss limit"), "race: nothing more today");
		// admin reset (smpeco casino resetlimit) clears the race's day counters too
		Casino.resetLimits(id);
		Assert.eq(0L, g.e.netToday(id), "reset: race counters cleared");
		Assert.eq(0L, Casino.netLossToday(id), "reset: nothing lost today");
		Assert.eq(100_000L, g.e.lossRoom(id), "reset: race room back");
		// an open casino round counts for the race
		Casino.Start mines = Casino.startMines(id, name(a), 30_000, 3, new SplittableRandom(4));
		Assert.isTrue(mines.ok(), "mines round: " + mines.error());
		Assert.eq(70_000L, g.e.lossRoom(id), "open casino stake counts for the race");
		Casino.settle(mines.round().id, null); // nothing revealed → stake back, not counted
		Assert.eq(100_000L, g.e.lossRoom(id), "refunded round: room back");
		// race-only player (no casino statistics): the reset still reaches the race
		Race r2 = g.card();
		Assert.isTrue(g.e.place(b.getUUID(), name(b), 0, 25_000).ok(), "B: race bet");
		steer(g).win(r2, 1);
		g.run();
		Assert.eq(25_000L, Casino.netLossToday(b.getUUID()), "B: race loss counted by the casino");
		Assert.isTrue(Casino.peekStats(b.getUUID()) == null, "B never played a casino game");
		Casino.resetLimits(b.getUUID());
		Assert.eq(0L, g.e.netToday(b.getUUID()), "B: reset reached the race");
		ctx.detail("race losses and open bets in the casino limit and back, admin reset clears both");
	}

	/** Many random races with 3 players: players + treasury constant, treasury = stakes − payouts, statistics match. */
	private static void conservation(TestContext ctx) {
		Rig g = setup(ctx, new SplittableRandom(31337));
		Casino.cfg().dailyLossLimit = Money.MAX;
		FakePlayer[] ps = {funded(ctx, "HorseConsA", 1_000_000_000L), funded(ctx, "HorseConsB", 1_000_000_000L),
			funded(ctx, "HorseConsC", 1_000_000_000L)};
		setTreasuryFree(10_000_000_000L);
		SplittableRandom rng = new SplittableRandom(4711);
		long t0 = Treasury.balance();
		long p0 = 0;
		for (FakePlayer p : ps) p0 += bal(p);
		long staked = 0;
		long paid = 0;
		int bets = 0;
		int races = 1_500;
		for (int i = 0; i < races; i++) {
			Race r = g.card();
			for (FakePlayer p : ps) {
				if (rng.nextInt(5) == 0) continue;
				long stake = Casino.cfg().minBet + rng.nextInt(50_000);
				RaceEngine.Placed pl = g.e.place(p.getUUID(), name(p), rng.nextInt(HorseMath.HORSES), stake);
				Assert.isTrue(pl.ok(), "race " + i + ": " + pl.error());
				staked += stake;
				bets++;
			}
			int settledBefore = g.settled.size();
			g.run();
			for (int k = settledBefore; k < g.settled.size(); k++) {
				Object[] s = g.settled.get(k);
				Assert.isTrue((Boolean) s[3], "paid");
				paid += (Long) s[1];
			}
			Assert.eq(r.picks.size(), g.settled.size() - settledBefore, "race " + i + ": every bet settled once");
			long sum = Treasury.balance();
			for (FakePlayer p : ps) sum += bal(p);
			Assert.eq(t0 + p0, sum, "players + treasury constant after race " + i);
		}
		Assert.eq(t0 + staked - paid, Treasury.balance(), "treasury = start + stakes − payouts");
		Assert.eq(0L, g.e.reserved(), "nothing reserved");
		long wagered = 0;
		long paidOut = 0;
		long counted = 0;
		for (HorseData.PlayerStats s : g.data.players.values()) {
			wagered += s.wagered;
			paidOut += s.paidOut;
			counted += s.races;
		}
		Assert.eq(staked, wagered, "statistics: stakes");
		Assert.eq(paid, paidOut, "statistics: payouts");
		Assert.eq((long) bets, counted, "statistics: bets");
		ctx.detail(races + " races, " + bets + " bets, realised RTP " + fmt(paid / (double) staked) + ", treasury "
			+ (staked - paid >= 0 ? "+" : "") + Money.format(staked - paid));
	}

	private static void exactlyOnce(TestContext ctx) {
		Rig g = setup(ctx, new Steer(5));
		FakePlayer a = funded(ctx, "HorseOnceA", 10_000_000);
		FakePlayer b = funded(ctx, "HorseOnceB", 10_000_000);
		setTreasuryFree(100_000_000);
		Race r = g.card();
		Assert.isTrue(g.e.place(a.getUUID(), name(a), 2, 10_000).ok(), "A bets");
		Assert.isTrue(g.e.place(b.getUUID(), name(b), 4, 5_000).ok(), "B bets");
		steer(g).win(r, 2);
		g.now[0] = r.closesAt;
		g.e.startIfDue(g.now[0]);
		long win = HorseMath.payout(10_000, r.odds[2]);
		Assert.eq(win, r.bets.get(a.getUUID()).payout, "decided before the animation");
		Assert.eq(2, g.e.finish(null), "2 bets settled");
		Assert.eq(0, g.e.finish(null), "second settle: nothing (next card is betting)");
		Assert.isFalse(g.e.settleBet(r, a.getUUID(), null, false), "settling A again does nothing");
		Assert.isFalse(g.e.settleBet(r, b.getUUID(), null, true), "refunding B afterwards does nothing");
		Assert.eq(10_000_000L - 10_000 + win, bal(a), "A paid exactly once");
		Assert.eq(10_000_000L - 5_000, bal(b), "B lost exactly once");
		Assert.eq(2, g.settled.size(), "2 settle events");
		Assert.eq(1L, g.data.players.get(a.getUUID()).races, "A: 1 race in the statistics");
		Assert.eq(1L, g.data.players.get(a.getUUID()).wins, "A: 1 win");
		Assert.eq(0L, g.data.players.get(b.getUUID()).wins, "B: no win");
		Assert.eq(r, g.data.last, "finished race kept for display");
		Assert.eq(2, r.picks.size(), "picks kept for display");
		Assert.isTrue(r.bets.isEmpty(), "no open bets left");
		ctx.detail("payout once, second settle/refund ignored, statistics once");
	}

	/** Stored state after a crash (JSON round trip): decided race paid once, unstarted card refunded once. */
	private static void crashRecovery(TestContext ctx) {
		Rig g = setup(ctx, new Steer(6));
		FakePlayer a = funded(ctx, "HorseCrashA", 10_000_000);
		FakePlayer b = funded(ctx, "HorseCrashB", 10_000_000);
		FakePlayer d = funded(ctx, "HorseCrashC", 10_000_000);
		setTreasuryFree(100_000_000);
		// 1) running race (outcome stored), crash before the winner crossed
		Race r = g.card();
		Assert.isTrue(g.e.place(a.getUUID(), name(a), 1, 10_000).ok(), "A");
		Assert.isTrue(g.e.place(b.getUUID(), name(b), 3, 10_000).ok(), "B");
		Assert.isTrue(g.e.place(d.getUUID(), name(d), 1, 2_000).ok(), "C");
		steer(g).win(r, 1);
		g.e.forceStart(g.now[0]);
		// C was already paid before the crash (partially settled race)
		Assert.isTrue(g.e.settleBet(r, d.getUUID(), null, false), "C settled before the crash");
		long cAfter = bal(d);
		g.data = roundTrip(g.data);
		long ba = bal(a);
		long bb = bal(b);
		Assert.eq(2, g.e.settleOpen("server restart"), "the 2 remaining bets settled after the restart");
		Assert.eq(0, g.e.settleOpen("server restart"), "second restart: nothing");
		Assert.eq(ba + HorseMath.payout(10_000, r.odds[1]), bal(a), "A: stored payout");
		Assert.eq(bb, bal(b), "B: lost");
		Assert.eq(cAfter, bal(d), "C: not paid twice");
		// 2) card that never started: refund once, no statistics, no daily loss
		Race r2 = g.card();
		Assert.isTrue(g.e.place(b.getUUID(), name(b), 0, 7_000).ok(), "B bets on the next card");
		g.data = roundTrip(g.data);
		long bb2 = bal(b);
		long racesB = g.data.players.get(b.getUUID()).races;
		long netB = g.e.netToday(b.getUUID());
		Assert.eq(1, g.e.settleOpen("server stop"), "refunded");
		Assert.eq(0, g.e.settleOpen("server stop"), "only once");
		Assert.eq(bb2 + 7_000, bal(b), "stake back");
		Assert.eq(racesB, g.data.players.get(b.getUUID()).races, "refund not in the statistics");
		Assert.eq(netB, g.e.netToday(b.getUUID()), "refund not in the daily loss");
		Assert.isTrue(g.data.race != null && g.data.race.id != r2.id && g.data.race.bets.isEmpty(), "fresh card after the refund");
		// 3) broken card (odds lost) with a bet → refunded instead of guessing
		Race r3 = g.card();
		Assert.isTrue(g.e.place(a.getUUID(), name(a), 0, 3_000).ok(), "A bets");
		r3.odds = null;
		g.data = roundTrip(g.data);
		long ba3 = bal(a);
		Assert.eq(1, g.e.settleOpen("server restart"), "broken card refunded");
		Assert.eq(ba3 + 3_000, bal(a), "stake back");
		ctx.detail("decided race paid once (also partially settled), unstarted card refunded once, broken card refunded");
	}

	private static HorseData roundTrip(HorseData d) {
		HorseData back = DataStore.gson().fromJson(DataStore.gson().toJson(d), HorseData.class);
		back.fixNulls();
		return back;
	}

	private static void pendingPayout(TestContext ctx) {
		Rig g = setup(ctx, new Steer(7));
		FakePlayer a = funded(ctx, "HorsePending", 10_000_000);
		setTreasuryFree(10_000_000);
		UUID id = a.getUUID();
		Race r = g.card();
		Assert.isTrue(g.e.place(id, name(a), 5, 10_000).ok(), "bet");
		steer(g).win(r, 5);
		g.e.forceStart(g.now[0]);
		long win = r.bets.get(id).payout;
		long bal = bal(a);
		// treasury emptied meanwhile (e.g. admin) → payout must wait, never get lost
		EconomyService.set(Treasury.ID, 1_000);
		Assert.eq(1, g.e.finish(null), "settled");
		Assert.isFalse((Boolean) g.settled.get(0)[3], "not paid yet");
		Assert.eq(bal, bal(a), "nothing paid");
		Assert.eq(win, g.e.pendingTotal(), "pending");
		Assert.eq(win, g.e.reserved(), "pending stays reserved");
		Assert.eq(0, g.e.retryPending(id), "treasury still empty");
		EconomyService.set(Treasury.ID, 10_000_000);
		Assert.eq(1, g.e.retryPending(id), "paid later");
		Assert.eq(0, g.e.retryPending(id), "only once");
		Assert.eq(bal + win, bal(a), "paid exactly once");
		Assert.eq(0L, g.e.reserved(), "reservation released");
		ctx.detail("pending payout reserved and paid once");
	}

	private static void cadence(TestContext ctx) {
		Rig g = setup(ctx, new Steer(8));
		FakePlayer a = funded(ctx, "HorseCadence", 10_000_000);
		setTreasuryFree(100_000_000);
		Race r = g.card();
		Assert.eq(0L, r.closesAt, "no countdown without bets");
		g.now[0] += 600_000;
		Assert.isFalse(g.e.startIfDue(g.now[0]), "no race without bets");
		long t = g.now[0];
		Assert.isTrue(g.e.place(a.getUUID(), name(a), 0, 1_000).ok(), "first bet");
		Assert.eq(t + 30_000L, r.closesAt, "on demand: 30 s after the first bet");
		Assert.isFalse(g.e.startIfDue(t + 29_999), "not earlier");
		Assert.isTrue(g.e.startIfDue(t + 30_000), "starts");
		Assert.eq(t + 30_000, g.data.lastStartAt, "start remembered");
		g.now[0] = r.settleAt;
		g.e.finishIfDue(g.now[0]);
		Race next = g.card();
		Assert.eq(r.id + 1, next.id, "next card");
		g.now[0] += 5_000;
		Assert.isTrue(g.e.place(a.getUUID(), name(a), 1, 1_000).ok(), "bet on the next card");
		Assert.eq(t + 30_000 + 120_000L, next.closesAt, "global cadence: at most one race per 2 minutes");
		// clock jumped back by more than a minute while running → settled anyway (no stuck race)
		g.now[0] = next.closesAt;
		g.e.startIfDue(g.now[0]);
		Assert.isTrue(g.e.finishIfDue(next.startedAt - 120_000), "clock jump back: settled");
		// edge change applies to new cards only
		Race card = g.card();
		int[] odds = card.odds.clone();
		g.cfg.baseHouseEdgePercent = 3.0;
		Assert.isTrue(Arrays.equals(odds, g.card().odds), "open card keeps its odds");
		g.e.forceStart(g.now[0]);
		g.now[0] = card.settleAt;
		g.e.finishIfDue(g.now[0]);
		Race fresh = g.card();
		Assert.isTrue(Arrays.equals(HorseMath.odds(fresh.chances, 300), fresh.odds), "new card uses the new edge");
		for (int i = 0; i < 12; i++) g.run();
		Assert.eq(RaceEngine.HISTORY, g.data.history.size(), "history capped");
		Assert.eq(g.data.last.id, g.data.history.get(0).raceId, "newest first");
		ctx.detail("on demand + 2 min cadence, no empty races, clock jump, odds fixed per card");
	}
}
