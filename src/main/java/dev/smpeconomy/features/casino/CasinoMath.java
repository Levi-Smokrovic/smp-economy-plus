package dev.smpeconomy.features.casino;

import java.math.BigInteger;
import java.util.random.RandomGenerator;

/**
 * Pure game math (no Minecraft classes, deterministic with a passed random source, for selftests and RTP simulation).
 * All payouts are computed exactly (BigInteger) and rounded down. The mines and hi-lo house edge applies exactly once,
 * on payout, so the expected return does not depend on when the player stops.
 */
public final class CasinoMath {
	private CasinoMath() {}

	private static final BigInteger BP = BigInteger.valueOf(10_000);

	/** Win chance against the house: 48 % (house edge 4 %). */
	public static final int COINFLIP_WIN_BP = 4_800;

	public static boolean coinflipWins(RandomGenerator rng) {
		return rng.nextInt(10_000) < COINFLIP_WIN_BP;
	}

	/** Symbols (index = rank, 0 = most common). Weights per reel, payout in tenths of the stake. */
	public static final String[] SLOT_NAMES = {"Coal", "Iron", "Gold", "Lapis", "Emerald", "Diamond"};
	public static final int[] SLOT_WEIGHTS = {7, 5, 4, 3, 2, 1};
	/** 3 of a kind on the payline: ×3, ×4, ×6, ×12, ×15, ×25. */
	public static final int[] SLOT_TRIPLE_TENTHS = {30, 40, 60, 120, 150, 250};
	/** 2 of a kind (any position): ×1.5, diamond ×2. */
	public static final int[] SLOT_PAIR_TENTHS = {15, 15, 15, 15, 15, 20};
	private static final int SLOT_WEIGHT_SUM;

	static {
		int sum = 0;
		for (int w : SLOT_WEIGHTS) sum += w;
		SLOT_WEIGHT_SUM = sum;
	}

	public static int slotSymbol(RandomGenerator rng) {
		int roll = rng.nextInt(SLOT_WEIGHT_SUM);
		for (int i = 0; i < SLOT_WEIGHTS.length; i++) {
			roll -= SLOT_WEIGHTS[i];
			if (roll < 0) return i;
		}
		return 0;
	}

	/** Payline result (3 reels). */
	public static int[] slotsSpin(RandomGenerator rng) {
		return new int[] {slotSymbol(rng), slotSymbol(rng), slotSymbol(rng)};
	}

	/** Multiplier in tenths (250 = ×25, 15 = ×1.5, 0 = lost). */
	public static int slotsTenths(int[] reels) {
		int a = reels[0];
		int b = reels[1];
		int c = reels[2];
		if (a == b && b == c) return SLOT_TRIPLE_TENTHS[a];
		if (a == b || a == c) return SLOT_PAIR_TENTHS[a];
		if (b == c) return SLOT_PAIR_TENTHS[b];
		return 0;
	}

	public static long slotsPayout(long stake, int[] reels) {
		return BigInteger.valueOf(stake).multiply(BigInteger.valueOf(slotsTenths(reels))).divide(BigInteger.TEN).longValueExact();
	}

	/** Highest slots multiplier in tenths (for coverage). */
	public static int slotsMaxTenths() {
		int max = 0;
		for (int t : SLOT_TRIPLE_TENTHS) max = Math.max(max, t);
		for (int t : SLOT_PAIR_TENTHS) max = Math.max(max, t);
		return max;
	}

	/** Exact theoretical return of the paytable (sum over all 6³ combinations). */
	public static double slotsTheoreticalRtp() {
		double sum = 0;
		int n = SLOT_WEIGHTS.length;
		double w = SLOT_WEIGHT_SUM;
		for (int a = 0; a < n; a++) {
			for (int b = 0; b < n; b++) {
				for (int c = 0; c < n; c++) {
					double p = SLOT_WEIGHTS[a] / w * SLOT_WEIGHTS[b] / w * SLOT_WEIGHTS[c] / w;
					sum += p * slotsTenths(new int[] {a, b, c}) / 10.0;
				}
			}
		}
		return sum;
	}

	public static final int MINES_TILES = 25;

	/** Binomial coefficient (n ≤ 25 fits in a long). */
	public static long binom(int n, int k) {
		if (k < 0 || k > n) return 0;
		k = Math.min(k, n - k);
		long r = 1;
		for (int i = 1; i <= k; i++) r = r * (n - k + i) / i;
		return r;
	}

	/** Bomb positions (0–24), random and distinct (partial Fisher-Yates). */
	public static int[] minesLayout(int bombs, RandomGenerator rng) {
		int[] tiles = new int[MINES_TILES];
		for (int i = 0; i < MINES_TILES; i++) tiles[i] = i;
		for (int i = 0; i < bombs; i++) {
			int j = i + rng.nextInt(MINES_TILES - i);
			int t = tiles[i];
			tiles[i] = tiles[j];
			tiles[j] = t;
		}
		int[] result = new int[bombs];
		System.arraycopy(tiles, 0, result, 0, bombs);
		java.util.Arrays.sort(result);
		return result;
	}

	/** Multiplier after {@code k} safe tiles: {@code (1 − edge) × C(25, k) / C(25 − bombs, k)}. */
	public static double minesMultiplier(int bombs, int k, int edgeBp) {
		if (k <= 0) return 0;
		return (10_000 - edgeBp) / 10_000.0 * binom(MINES_TILES, k) / binom(MINES_TILES - bombs, k);
	}

	/** Payout after {@code k} safe tiles (rounded down). */
	public static long minesPayout(long stake, int bombs, int k, int edgeBp) {
		if (k <= 0) return 0;
		BigInteger num = BigInteger.valueOf(stake).multiply(BigInteger.valueOf(10_000L - edgeBp)).multiply(BigInteger.valueOf(binom(MINES_TILES, k)));
		BigInteger den = BP.multiply(BigInteger.valueOf(binom(MINES_TILES - bombs, k)));
		return num.divide(den).longValueExact();
	}

	/** Is the multiplier after {@code k} safe tiles within the cap? */
	public static boolean minesWithinCap(int bombs, int k, int edgeBp, long capBp) {
		if (k > MINES_TILES - bombs) return false;
		BigInteger lhs = BigInteger.valueOf(10_000L - edgeBp).multiply(BigInteger.valueOf(binom(MINES_TILES, k)));
		BigInteger rhs = BigInteger.valueOf(capBp).multiply(BigInteger.valueOf(binom(MINES_TILES - bombs, k)));
		return lhs.compareTo(rhs) <= 0;
	}

	/**
	 * Results of {@link #minesMaxSafe} (pure function; {@code Casino.maxStake} asks about 40 times per stake display).
	 */
	private static final java.util.Map<MinesKey, Integer> MAX_SAFE = new java.util.concurrent.ConcurrentHashMap<>();

	private record MinesKey(int bombs, int edgeBp, long capBp) {}

	/** Largest k under the cap (0 = not playable with this bomb count). */
	public static int minesMaxSafe(int bombs, int edgeBp, long capBp) {
		MinesKey key = new MinesKey(bombs, edgeBp, capBp);
		Integer cached = MAX_SAFE.get(key);
		if (cached != null) return cached;
		int k = 0;
		while (k < MINES_TILES - bombs && minesWithinCap(bombs, k + 1, edgeBp, capBp)) k++;
		if (MAX_SAFE.size() < 4096) MAX_SAFE.put(key, k);
		return k;
	}

	/** Card values 2–14 (11 = jack, 12 = queen, 13 = king, 14 = ace). */
	public static final int HILO_MIN = 2;
	public static final int HILO_MAX = 14;
	/** The next card never has the same value, so 12 possible values. */
	public static final int HILO_OTHERS = HILO_MAX - HILO_MIN;

	public static int hiloRank(RandomGenerator rng) {
		return HILO_MIN + rng.nextInt(HILO_MAX - HILO_MIN + 1);
	}

	/** Uniform among the 12 other values. */
	public static int hiloNext(int current, RandomGenerator rng) {
		int r = HILO_MIN + rng.nextInt(HILO_OTHERS);
		return r >= current ? r + 1 : r;
	}

	/** Number of values that win on "higher" or "lower". */
	public static int hiloCount(int rank, boolean higher) {
		return higher ? HILO_MAX - rank : rank - HILO_MIN;
	}

	/** Probability (0–1) of "higher" or "lower". */
	public static double hiloChance(int rank, boolean higher) {
		return hiloCount(rank, higher) / (double) HILO_OTHERS;
	}

	/** Fraction for the multiplier (without house edge) after a right guess: × 12 / hits. Reduced: {num, den}. */
	public static BigInteger[] hiloStep(BigInteger num, BigInteger den, int rank, boolean higher) {
		int hits = hiloCount(rank, higher);
		if (hits <= 0) throw new IllegalArgumentException("impossible guess");
		BigInteger n = num.multiply(BigInteger.valueOf(HILO_OTHERS));
		BigInteger d = den.multiply(BigInteger.valueOf(hits));
		BigInteger g = n.gcd(d);
		return new BigInteger[] {n.divide(g), d.divide(g)};
	}

	public static double hiloMultiplier(BigInteger num, BigInteger den, int edgeBp) {
		return (10_000 - edgeBp) / 10_000.0 * num.doubleValue() / den.doubleValue();
	}

	public static long hiloPayout(long stake, BigInteger num, BigInteger den, int edgeBp) {
		return BigInteger.valueOf(stake).multiply(BigInteger.valueOf(10_000L - edgeBp)).multiply(num)
			.divide(BP.multiply(den)).longValueExact();
	}

	/** Does the multiplier stay under the cap after a right guess? */
	public static boolean hiloWithinCap(BigInteger num, BigInteger den, int rank, boolean higher, int edgeBp, long capBp) {
		int hits = hiloCount(rank, higher);
		if (hits <= 0) return false;
		BigInteger lhs = BigInteger.valueOf(10_000L - edgeBp).multiply(num).multiply(BigInteger.valueOf(HILO_OTHERS));
		BigInteger rhs = BigInteger.valueOf(capBp).multiply(den).multiply(BigInteger.valueOf(hits));
		return lhs.compareTo(rhs) <= 0;
	}

	/** "×1.23" (two decimals, rounded down: never promises more than is paid). */
	public static String formatMultiplier(double m) {
		double floored = Math.floor(m * 100 + 1e-9) / 100;
		return "×" + String.format(java.util.Locale.ROOT, "%.2f", floored);
	}

	public static String formatTenths(int tenths) {
		return tenths % 10 == 0 ? "×" + tenths / 10 : "×" + tenths / 10 + "." + tenths % 10;
	}
}
