package dev.smpeconomy.features.casino.games.plinko;

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
import dev.smpeconomy.features.casino.games.plinko.PlinkoMath.Risk;

/**
 * Plinko selftests (feature "casino-plinko", names {@code plinko-*}; no GUI): exact tables and RTP, RTP simulation with
 * 200,000 balls per risk level (math) and 25,000 through the real casino logic, path to bucket and animation, result
 * saved before the animation and exactly-once settlement, restart after a crash, max bet, coverage, daily limit, one
 * round, refund on errors in game code, money conservation (player + Treasury constant).
 */
final class PlinkoSelfTests {
	private static final String F = "casino-plinko";
	private static final int SIM_ROUNDS = 200_000;
	private static final int ENGINE_ROUNDS = 25_000;

	private PlinkoSelfTests() {}

	static void register() {
		SelfTests.register(F, "plinko-tables", PlinkoSelfTests::tables);
		SelfTests.register(F, "plinko-rtp", PlinkoSelfTests::rtp);
		SelfTests.register(F, "plinko-path", PlinkoSelfTests::path);
		SelfTests.register(F, "plinko-engine-rtp", PlinkoSelfTests::engineRtp);
		SelfTests.register(F, "plinko-exactly-once", PlinkoSelfTests::exactlyOnce);
		SelfTests.register(F, "plinko-crash-recovery", PlinkoSelfTests::crashRecovery);
		SelfTests.register(F, "plinko-guards", PlinkoSelfTests::guards);
		SelfTests.register(F, "plinko-void", PlinkoSelfTests::voided);
		SelfTests.register(F, "plinko-loss-limit", PlinkoSelfTests::lossLimit);
		SelfTests.register(F, "plinko-conservation", PlinkoSelfTests::conservation);
	}

	/** Random source with preset values for nextInt(bound), then a fixed seed. */
	static final class Scripted implements RandomGenerator {
		private final int[] values;
		private int index;
		private final SplittableRandom fallback = new SplittableRandom(77);

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

	/** Path all the way left (bucket 0 = edge, highest multiplier). */
	static RandomGenerator allLeft() {
		return new Scripted(0, 0, 0, 0, 0, 0, 0, 0);
	}

	/** Path to the middle (4× right gives bucket 4, lowest multiplier). */
	static RandomGenerator middle() {
		return new Scripted(0, 1, 0, 1, 0, 1, 0, 1);
	}

	/**
	 * Casino config to defaults (Treasury saved); everything is restored after the test. Config copied through Gson
	 * because CasinoConfig#copy is package-private.
	 */
	private static CasinoConfig setup(TestContext ctx) {
		ctx.preserveTreasury();
		CasinoConfig live = Casino.cfg();
		String saved = DataStore.gson().toJson(live);
		ctx.cleanup(() -> assign(live, DataStore.gson().fromJson(saved, CasinoConfig.class)));
		assign(live, new CasinoConfig());
		return live;
	}

	private static void assign(CasinoConfig to, CasinoConfig from) {
		to.enabled = from.enabled;
		to.minBet = from.minBet;
		to.maxBet = from.maxBet;
		to.maxBetTreasuryPercent = from.maxBetTreasuryPercent;
		to.maxWinTreasuryPercent = from.maxWinTreasuryPercent;
		to.dailyLossLimit = from.dailyLossLimit;
		to.minesHouseEdgePercent = from.minesHouseEdgePercent;
		to.hiloHouseEdgePercent = from.hiloHouseEdgePercent;
		to.minesMaxMultiplier = from.minesMaxMultiplier;
		to.hiloMaxMultiplier = from.hiloMaxMultiplier;
		to.minesDefaultBombs = from.minesDefaultBombs;
		to.announceMinProfit = from.announceMinProfit;
		to.announceMinMultiplier = from.announceMinMultiplier;
		to.announceMinPayoutForMultiplier = from.announceMinPayoutForMultiplier;
		to.offerTimeoutSeconds = from.offerTimeoutSeconds;
		to.idleTimeoutMinutes = from.idleTimeoutMinutes;
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

	private static void tables(TestContext ctx) {
		long ways = 0;
		for (int w : PlinkoMath.WAYS) ways += w;
		Assert.eq((long) PlinkoMath.PATHS, ways, "paths sum to 256");
		Assert.eq(9, PlinkoMath.BUCKETS, "9 buckets = one chest row");
		StringBuilder detail = new StringBuilder();
		for (Risk r : Risk.values()) {
			int[] t = r.table();
			Assert.eq(PlinkoMath.BUCKETS, t.length, r + ": 9 multipliers");
			for (int k = 0; k < t.length; k++) {
				Assert.check(t[k] > 0, r + ": bucket " + k + " pays something");
				Assert.eq(t[k], t[t.length - 1 - k], r + ": symmetric (bucket " + k + ")");
			}
			double rtp = PlinkoMath.theoreticalRtp(r);
			Assert.check(rtp >= 0.95 && rtp <= 0.97, r + ": RTP " + fmt(rtp) + " not in [0.95, 0.97] (house edge 3–5 %)");
			Assert.check(Math.abs(rtp - 0.96) <= 0.005, r + ": RTP " + fmt(rtp) + " not ≈ 96 %");
			Assert.eq(PlinkoMath.maxHundredths(r), t[0], r + ": edge pays most");
			Assert.check(t[4] < 100, r + ": middle is a loss");
			detail.append(r.id()).append('=').append(fmt(rtp)).append(' ');
		}
		Assert.eq(24_640L, PlinkoMath.weightedHundredths(Risk.LOW), "low exactly 96.25 %");
		Assert.eq(24_620L, PlinkoMath.weightedHundredths(Risk.MEDIUM), "medium exact");
		Assert.eq(24_600L, PlinkoMath.weightedHundredths(Risk.HIGH), "high exact");
		// payouts: rounded down, exact, no overflow
		Assert.eq(56_000L, PlinkoMath.payout(10_000, Risk.LOW, 0), "$100 × 5.6");
		Assert.eq(2_000L, PlinkoMath.payout(10_000, Risk.HIGH, 4), "$100 × 0.2");
		Assert.eq(111L, PlinkoMath.payout(101, Risk.LOW, 2), "$1.01 × 1.1 = $1.111, rounded down");
		Assert.eq(280_000L, PlinkoMath.potential(10_000, Risk.HIGH), "maximum win high ×28");
		Assert.eq(28_000_000_000_000_000L, PlinkoMath.potential(Money.MAX, Risk.HIGH), "largest stake × 28 without overflow");
		Assert.assertThrows(ArithmeticException.class, () -> PlinkoMath.potential(Long.MAX_VALUE / 10, Risk.HIGH), "overflow detected");		Assert.eq("×5.6", PlinkoMath.formatHundredths(560), "Format ×5.6");
		Assert.eq("×13", PlinkoMath.formatHundredths(1300), "Format ×13");
		Assert.eq("×0.4", PlinkoMath.formatHundredths(40), "Format ×0.4");
		Assert.eq("×1.25", PlinkoMath.formatHundredths(125), "Format ×1.25");
		Assert.eq(Risk.HIGH, Risk.parse(" High "), "parse risk");
		Assert.eq(Risk.MEDIUM, Risk.parse("mid"), "risk alias");
		Assert.isTrue(Risk.parse("extreme") == null, "unknown risk");
		ctx.detail("RTP exact " + detail.toString().trim());
	}

	/** RTP simulation over pure math: 200,000 balls per risk level plus bucket distribution. */
	private static void rtp(TestContext ctx) {
		StringBuilder detail = new StringBuilder();
		for (Risk r : Risk.values()) {
			SplittableRandom rng = new SplittableRandom(20261010L + r.ordinal());
			double sum = 0;
			double sumSq = 0;
			long stake = 10_000;
			long[] hits = new long[PlinkoMath.BUCKETS];
			for (int i = 0; i < SIM_ROUNDS; i++) {
				int bucket = PlinkoMath.bucket(PlinkoMath.path(rng));
				hits[bucket]++;
				double x = PlinkoMath.payout(stake, r, bucket) / (double) stake;
				sum += x;
				sumSq += x * x;
			}
			checkRtp("Plinko " + r.label(), sum, sumSq, SIM_ROUNDS, PlinkoMath.theoreticalRtp(r));
			for (int k = 0; k < PlinkoMath.BUCKETS; k++) {
				double p = PlinkoMath.chance(k);
				double expected = SIM_ROUNDS * p;
				double sd = Math.sqrt(SIM_ROUNDS * p * (1 - p));
				Assert.check(Math.abs(hits[k] - expected) <= 5 * sd + 1, r + ": bucket " + k + " " + hits[k] + "× instead of ≈ " + Math.round(expected));
			}
			detail.append(r.id()).append('=').append(fmt(sum / SIM_ROUNDS)).append(' ');
		}
		ctx.detail("simulated " + detail.toString().trim() + " (each " + SIM_ROUNDS + " balls)");
	}

	/** All 256 paths: bucket = number of rights, animation stays on the board and ends in the right bucket. */
	private static void path(TestContext ctx) {
		int[] perBucket = new int[PlinkoMath.BUCKETS];
		for (int bits = 0; bits < PlinkoMath.PATHS; bits++) {
			int[] p = new int[PlinkoMath.ROWS];
			for (int i = 0; i < p.length; i++) p[i] = bits >> i & 1;
			int bucket = PlinkoMath.bucket(p);
			Assert.eq(Integer.bitCount(bits), bucket, "bucket = number of rights");
			perBucket[bucket]++;
			int[][] f = PlinkoMath.frames(p);
			Assert.eq(PlinkoMath.ROWS + 1, f.length, "9 animation steps");
			Assert.isTrue(f[0][0] == 0 && f[0][1] == 4, "drop at top centre");
			for (int j = 1; j < f.length; j++) {
				Assert.check(f[j][1] >= 0 && f[j][1] <= 8, "column in the window (path " + bits + ", step " + j + ")");
				Assert.eq((j + 1) / 2 - (j % 2), f[j][0], "row after step " + j);
				Assert.check(Math.abs(f[j][1] - f[j - 1][1]) <= 1, "ball moves at most one column (path " + bits + ")");
			}
			Assert.isTrue(f[PlinkoMath.ROWS][0] == 4 && f[PlinkoMath.ROWS][1] == bucket, "lands in bucket " + bucket);
		}
		for (int k = 0; k < PlinkoMath.BUCKETS; k++) Assert.eq(PlinkoMath.WAYS[k], perBucket[k], "paths into bucket " + k);
		Assert.assertThrows(IllegalArgumentException.class, () -> PlinkoMath.bucket(new int[] {0, 1}), "path too short");
		Assert.assertThrows(IllegalArgumentException.class, () -> PlinkoMath.bucket(new int[] {0, 0, 0, 0, 0, 0, 0, 2}), "invalid step");
		ctx.detail("256 paths: bucket, rows and columns ok");
	}

	/** RTP through the real game logic (stake to Treasury, payout from Treasury, settlement through Casino.settle). */
	private static void engineRtp(TestContext ctx) {
		CasinoConfig c = setup(ctx);
		c.dailyLossLimit = Money.MAX;
		FakePlayer a = funded(ctx, "PlinkoRtp", 10_000_000_000L);
		setTreasuryFree(10_000_000_000L);
		UUID id = a.getUUID();
		long stake = 10_000;
		SplittableRandom rng = new SplittableRandom(2027);
		StringBuilder detail = new StringBuilder();
		for (Risk r : Risk.values()) {
			double sum = 0;
			double sumSq = 0;
			long t0 = Treasury.balance();
			long p0 = EconomyService.balance(id);
			long staked = 0;
			long paid = 0;
			for (int i = 0; i < ENGINE_ROUNDS; i++) {
				Casino.Start s = Plinko.drop(id, name(a), stake, r, rng);
				Assert.isTrue(s.ok(), r + " round " + i + ": " + s.error());
				Casino.Settled st = Casino.settle(s.round().id, null);
				Assert.isFalse(st.refund(), "no refund");
				Assert.eq(s.round().payout, st.payout(), "paid as decided");
				double x = st.payout() / (double) stake;
				sum += x;
				sumSq += x * x;
				staked += stake;
				paid += st.payout();
			}
			checkRtp("Plinko " + r.label() + " (Engine)", sum, sumSq, ENGINE_ROUNDS, PlinkoMath.theoreticalRtp(r));
			Assert.eq(t0 + staked - paid, Treasury.balance(), r + ": Treasury = stakes − payouts");
			Assert.eq(p0 - staked + paid, EconomyService.balance(id), r + ": player = − stakes + payouts");
			detail.append(r.id()).append('=').append(fmt(sum / ENGINE_ROUNDS)).append(' ');
		}
		CasinoData.GameStats gs = Casino.peekStats(id).games.get(Plinko.GAME_ID);
		Assert.notNull(gs, "per-game stats under \"plinko\"");
		Assert.eq(3L * ENGINE_ROUNDS, gs.rounds, "each round counted exactly once");
		ctx.detail(detail.toString().trim() + " (each " + ENGINE_ROUNDS + " rounds)");
	}

	private static void exactlyOnce(TestContext ctx) {
		setup(ctx);
		FakePlayer a = funded(ctx, "PlinkoOnce", 10_000_000);
		setTreasuryFree(100_000_000);
		UUID id = a.getUUID();
		long b0 = EconomyService.balance(id);
		long t0 = Treasury.balance();
		long reserved0 = Casino.reservedTotal(null);
		// far left on high: ×28
		Casino.Start s = Plinko.drop(id, name(a), 1_000, Risk.HIGH, allLeft());
		Assert.isTrue(s.ok(), "ball dropped: " + s.error());
		Round r = s.round();
		Assert.eq(CasinoData.DECIDED, r.state, "result decided before the animation");
		Assert.eq(28_000L, r.payout, "payout stored (×28)");
		Assert.eq(Plinko.GAME_ID, r.game, "game id");
		Assert.eq(0, PlinkoMath.bucket(r.reels), "path stored (bucket 0)");
		Assert.isTrue(Casino.openRound(id) == r, "round open until the end of the animation");
		Assert.eq(reserved0 + 28_000L, Casino.reservedTotal(null), "Treasury reserves exactly the fixed payout");
		Assert.eq(b0 - 1_000, EconomyService.balance(id), "stake taken");
		Assert.eq(t0 + 1_000, Treasury.balance(), "stake in the Treasury");
		Casino.Settled first = Casino.settle(r.id, null);
		Assert.notNull(first, "settled");
		Assert.eq(28_000L, first.payout(), "payout");
		Assert.isTrue(first.paid() && !first.refund(), "booked, no refund");
		Assert.isTrue(Casino.settle(r.id, null) == null, "second settlement (e.g. close after watchdog) has no effect");
		Assert.isTrue(Casino.settle(r.id, "window closed") == null, "third settlement has no effect");
		Assert.isTrue(Casino.openRound(id) == null, "nothing left open");
		Assert.eq(reserved0, Casino.reservedTotal(null), "reservation released");
		Assert.eq(b0 - 1_000 + 28_000, EconomyService.balance(id), "paid exactly once");
		// middle on low: ×0.4, a partial loss
		Round m = Plinko.drop(id, name(a), 1_000, Risk.LOW, middle()).round();
		Assert.notNull(m, "second ball");
		Assert.eq(400L, m.payout, "×0.4 decided");
		Assert.eq(400L, Casino.settle(m.id, null).payout(), "×0.4 paid");
		Assert.isTrue(Casino.settle(m.id, null) == null, "not twice");
		Assert.eq(b0 - 2_000 + 28_400, EconomyService.balance(id), "balance correct");
		Assert.eq(t0 + 2_000 - 28_400, Treasury.balance(), "Treasury correct");
		CasinoData.PlayerStats st = Casino.peekStats(id);
		Assert.eq(2L, st.rounds, "2 rounds in the stats");
		Assert.eq(2_000L, st.wagered, "stakes");
		Assert.eq(28_400L, st.paidOut, "payouts");
		Assert.eq(1_000L, (long) st.lastStake.get(Plinko.GAME_ID), "last plinko stake remembered");
		ctx.detail("×28 and ×0.4 each paid exactly once");
	}

	/**
	 * Crash during the animation: the round is stored decided in casino.json; after loading (JSON round trip) the
	 * restart settlement pays exactly the stored payout, exactly once.
	 */
	private static void crashRecovery(TestContext ctx) {
		setup(ctx);
		FakePlayer a = funded(ctx, "PlinkoCrash", 10_000_000);
		setTreasuryFree(100_000_000);
		UUID id = a.getUUID();
		Round r = Plinko.drop(id, name(a), 5_000, Risk.MEDIUM, allLeft()).round();
		Assert.notNull(r, "ball dropped");
		Assert.eq(65_000L, r.payout, "×13 decided");
		String json = DataStore.gson().toJson(r);
		Round loaded = DataStore.gson().fromJson(json, Round.class);
		Assert.eq(CasinoData.DECIDED, loaded.state, "decided after loading");
		Assert.eq(65_000L, loaded.payout, "payout survives saving");
		Assert.isFalse(loaded.refund, "no refund");
		Assert.eq(65_000L, Casino.cashOutValue(loaded), "restart settlement pays the stored payout");
		Assert.eq(0, PlinkoMath.bucket(loaded.reels), "path survives saving");
		CasinoData whole = new CasinoData();
		whole.rounds.put(loaded.id, loaded);
		CasinoData back = DataStore.gson().fromJson(DataStore.gson().toJson(whole), CasinoData.class);
		Assert.isTrue(back.rounds.containsKey(r.id) && back.rounds.get(r.id).payout == 65_000L, "whole file: round kept");
		long bal = EconomyService.balance(id);
		Assert.notNull(Casino.settle(r.id, "Server restart"), "settled on restart");
		Assert.isTrue(Casino.settle(r.id, "Server restart") == null, "not twice");
		Assert.eq(bal + 65_000, EconomyService.balance(id), "paid exactly once");
		ctx.detail("decided round survives save and load, settled once");
	}

	private static void guards(TestContext ctx) {
		CasinoConfig c = setup(ctx);
		FakePlayer a = funded(ctx, "PlinkoGuard", 100_000_000);
		FakePlayer poor = funded(ctx, "PlinkoPoor", 500);
		UUID id = a.getUUID();
		// max bet: 2 % of the Treasury ($10,000 free gives $200)
		setTreasuryFree(1_000_000);
		long maxBet = Casino.maxBet();
		Assert.check(maxBet > 0 && maxBet <= Treasury.balance() / 50, "max bet ≤ 2 % of the Treasury");
		long before = EconomyService.balance(id);
		long t0 = Treasury.balance();
		Casino.Start over = Plinko.drop(id, name(a), maxBet + 1, Risk.LOW, allLeft());
		Assert.isFalse(over.ok(), "stake above the max bet refused");
		Assert.eq(before, EconomyService.balance(id), "refused: nothing taken");
		Assert.eq(t0, Treasury.balance(), "refused: Treasury unchanged");
		Assert.isTrue(Casino.openRound(id) == null, "refused: no round");
		Assert.isFalse(Plinko.drop(id, name(a), c.minBet - 1, Risk.LOW, allLeft()).ok(), "below the minimum bet refused");
		// coverage: max bet × 28 > maximum win (50 % of the Treasury), so high only up to Treasury/2/28
		long maxHigh = Plinko.maxStake(id, Risk.HIGH);
		Assert.check(maxHigh > 0 && maxHigh < maxBet, "high: coverage limits the stake (" + maxHigh + " < " + maxBet + ")");
		Assert.check(PlinkoMath.potential(maxHigh, Risk.HIGH) <= Casino.coverLimit(null), "max stake on high is covered");
		Assert.check(PlinkoMath.potential(maxHigh + 1, Risk.HIGH) > Casino.coverLimit(null), "one cent more would not be covered");
		Casino.Start notCovered = Plinko.drop(id, name(a), maxHigh + 1, Risk.HIGH, allLeft());
		Assert.isFalse(notCovered.ok(), "not covered: refused");
		Assert.check(notCovered.error().contains(Money.format(maxHigh)), "message names the possible stake: " + notCovered.error());
		Assert.eq(before, EconomyService.balance(id), "not covered: nothing taken");
		Assert.eq(maxBet, Plinko.maxStake(id, Risk.LOW), "low (×5.6 covered): max stake = max bet");
		Round ok = Plinko.drop(id, name(a), maxHigh, Risk.HIGH, allLeft()).round();
		Assert.notNull(ok, "exactly covered stake admitted");
		// one round per player (also across games)
		Casino.Start second = Plinko.drop(id, name(a), c.minBet, Risk.LOW, allLeft());
		Assert.isFalse(second.ok(), "second ball during the animation refused");
		Assert.isFalse(Casino.startSlots(id, name(a), c.minBet, Casino.RNG).ok(), "other game during plinko refused");
		// reserved coverage blocks others: the open ×28 round is reserved
		Assert.eq(Casino.freeCoverage(null) + ok.payout, Casino.freeCoverage(ok.id), "payout of the open round reserved");
		Casino.settle(ok.id, null);
		// not enough money
		long poorBefore = EconomyService.balance(poor.getUUID());
		Assert.isFalse(Plinko.drop(poor.getUUID(), name(poor), 1_000, Risk.LOW, allLeft()).ok(), "not enough money refused");
		Assert.eq(poorBefore, EconomyService.balance(poor.getUUID()), "not enough money: nothing taken");
		// casino closed
		c.enabled = false;
		long closedBefore = EconomyService.balance(id);
		Assert.isFalse(Plinko.drop(id, name(a), c.minBet, Risk.LOW, allLeft()).ok(), "casino closed: refused");
		Assert.eq(closedBefore, EconomyService.balance(id), "casino closed: nothing taken");
		c.enabled = true;
		// Treasury too small: locked
		setTreasuryFree(1);
		Assert.eq(0L, Plinko.maxStake(id, Risk.LOW), "tiny Treasury: locked");
		Assert.isFalse(Plinko.drop(id, name(a), c.minBet, Risk.LOW, allLeft()).ok(), "tiny Treasury: refused");
		ctx.detail("max bet " + Money.format(maxBet) + ", high covered up to " + Money.format(maxHigh) + ", one round, money, casino closed");
	}

	/**
	 * Error in game code while determining the result ({@link Casino#startInstant}): stake refunded at once, nothing
	 * reserved or counted, no round open. Built-in game ids are refused for add-on games.
	 */
	private static void voided(TestContext ctx) {
		setup(ctx);
		FakePlayer a = funded(ctx, "PlinkoVoid", 10_000_000);
		setTreasuryFree(100_000_000);
		UUID id = a.getUUID();
		long p0 = EconomyService.balance(id);
		long t0 = Treasury.balance();
		long reserved0 = Casino.reservedTotal(null);
		Casino.Start thrown = Casino.startInstant(id, name(a), Plinko.GAME, 1_000, s -> PlinkoMath.potential(s, Risk.LOW), r -> {
			throw new IllegalStateException("Selftest plinko-void: deliberate error (expected)");
		});
		Assert.isFalse(thrown.ok(), "error in the result: refused");
		Assert.eq(p0, EconomyService.balance(id), "error: stake refunded");
		Casino.Start tooHigh = Casino.startInstant(id, name(a), Plinko.GAME, 1_000, s -> PlinkoMath.potential(s, Risk.LOW),
			r -> PlinkoMath.potential(r.stake, Risk.LOW) + 1);
		Assert.isFalse(tooHigh.ok(), "payout above the covered maximum: refused");
		Assert.eq(p0, EconomyService.balance(id), "payout too high: stake refunded, nothing paid");
		Assert.eq(t0, Treasury.balance(), "Treasury unchanged");
		Assert.eq(reserved0, Casino.reservedTotal(null), "nothing reserved");
		Assert.isTrue(Casino.openRound(id) == null, "no open round");
		CasinoData.PlayerStats st = Casino.peekStats(id);
		Assert.eq(0L, st == null ? 0L : st.rounds, "no round in stats or daily limit");
		Casino.InstantGame builtin = new Casino.InstantGame("slots", "x", net.minecraft.world.item.Items.PAPER, s -> s);
		Assert.assertThrows(IllegalArgumentException.class, () -> Casino.registerGame(builtin), "built-in game id refused");
		Assert.assertThrows(IllegalArgumentException.class,
			() -> Casino.startInstant(id, name(a), builtin, 1_000, s -> s, r -> 0L), "unregistered game refused");
		Assert.eq(p0, EconomyService.balance(id), "built-in id: nothing taken");
		ctx.detail("error in game code refunds the stake exactly once");
	}

	private static void lossLimit(TestContext ctx) {
		CasinoConfig c = setup(ctx);
		c.dailyLossLimit = 30_000; // $300
		FakePlayer a = funded(ctx, "PlinkoLoss", 10_000_000);
		setTreasuryFree(100_000_000);
		UUID id = a.getUUID();
		// high, middle: ×0.2, so $80 loss each on a $100 stake
		for (int i = 0; i < 3; i++) {
			Round r = Plinko.drop(id, name(a), 10_000, Risk.HIGH, middle()).round();
			Assert.notNull(r, "Round " + i);
			Casino.settle(r.id, null);
		}
		Assert.eq(24_000L, Casino.netLossToday(id), "daily loss $240");
		Assert.eq(6_000L, Casino.lossRoom(id), "$60 left");
		Assert.eq(6_000L, Plinko.maxStake(id, Risk.LOW), "max stake = rest of the daily limit");
		long before = EconomyService.balance(id);
		Assert.isFalse(Plinko.drop(id, name(a), 6_001, Risk.LOW, middle()).ok(), "above the daily limit refused");
		Assert.eq(before, EconomyService.balance(id), "refused: nothing taken");
		Round last = Plinko.drop(id, name(a), 6_000, Risk.LOW, allLeft()).round();
		Assert.notNull(last, "remaining stake allowed");
		Casino.settle(last.id, null); // ×5.6: a win lowers the daily loss
		Assert.eq(24_000L + 6_000 - 33_600, Casino.netLossToday(id), "win offsets the daily loss");
		ctx.detail("daily limit also applies to plinko");
	}

	/** Many random balls: player + Treasury stays constant, no reservations left. */
	private static void conservation(TestContext ctx) {
		CasinoConfig c = setup(ctx);
		c.dailyLossLimit = Money.MAX;
		FakePlayer a = funded(ctx, "PlinkoCons", 1_000_000_000L);
		setTreasuryFree(1_000_000_000L);
		UUID id = a.getUUID();
		long t0 = Treasury.balance();
		long p0 = EconomyService.balance(id);
		long reserved0 = Casino.reservedTotal(null);
		SplittableRandom rng = new SplittableRandom(4711);
		long staked = 0;
		long paid = 0;
		for (int i = 0; i < 3_000; i++) {
			Risk r = Risk.values()[rng.nextInt(3)];
			long stake = c.minBet + rng.nextInt(50_000);
			Casino.Start s = Plinko.drop(id, name(a), stake, r, rng);
			Assert.isTrue(s.ok(), "Ball " + i + ": " + s.error());
			Assert.eq(p0 - staked + paid - stake, EconomyService.balance(id), "stake taken (" + i + ")");
			Casino.Settled st = Casino.settle(s.round().id, i % 2 == 0 ? null : "window closed");
			Assert.isTrue(st.paid() && !st.refund(), "booked");
			Assert.eq(PlinkoMath.payout(stake, r, PlinkoMath.bucket(s.round().reels)), st.payout(), "payout = stake × bucket");
			staked += stake;
			paid += st.payout();
			Assert.eq(t0 + p0, Treasury.balance() + EconomyService.balance(id), "player + Treasury constant after ball " + i);
		}
		Assert.eq(t0 + staked - paid, Treasury.balance(), "Treasury = start + stakes − payouts");
		Assert.eq(reserved0, Casino.reservedTotal(null), "no reservations left");
		Assert.isTrue(Casino.openRound(id) == null, "no open round");
		ctx.detail("3000 balls, Treasury " + (staked - paid >= 0 ? "+" : "") + Money.format(staked - paid));
	}
}
