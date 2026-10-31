package dev.smpeconomy.features.casino.games.plinko;

import java.math.BigInteger;
import java.util.Locale;
import java.util.random.RandomGenerator;

/**
 * Plinko math (no Minecraft classes, deterministic with a passed random source, for selftests and RTP simulation).
 *
 * <p>The ball falls through {@link #ROWS} = 8 rows of pegs and bounces left or right at each peg with 50 % each; the
 * bucket is the number of right bounces (0–8), so binomially distributed: {@code P(k) = C(8, k) / 256}. 9 buckets =
 * exactly one chest row. The multipliers (in hundredths) are symmetric and chosen so that the return {@code Σ C(8, k) ·
 * m(k) / 256} is about 96 % exactly (house edge ≈ 4 %). Payouts are rounded down.
 */
public final class PlinkoMath {
	private PlinkoMath() {}

	/** Peg rows = left/right decisions. */
	public static final int ROWS = 8;
	/** Buckets (one chest row). */
	public static final int BUCKETS = ROWS + 1;
	/** Possible paths (2^8). */
	public static final int PATHS = 1 << ROWS;
	/** Paths into each bucket: C(8, k). */
	static final int[] WAYS = {1, 8, 28, 56, 70, 56, 28, 8, 1};

	/** Risk levels with their own paytable (multiplier per bucket in hundredths: 560 = ×5.6). */
	public enum Risk {
		/** RTP 24,640 / 25,600 = 96.25 %. */
		LOW("low", "Low", "§a", new int[] {560, 210, 110, 100, 40, 100, 110, 210, 560}),
		/** RTP 24,620 / 25,600 ≈ 96.17 %. */
		MEDIUM("medium", "Medium", "§e", new int[] {1300, 300, 130, 70, 30, 70, 130, 300, 1300}),
		/** RTP 24,600 / 25,600 ≈ 96.09 %. */
		HIGH("high", "High", "§c", new int[] {2800, 400, 140, 30, 20, 30, 140, 400, 2800});

		private final String id;
		private final String label;
		private final String color;
		private final int[] hundredths;

		Risk(String id, String label, String color, int[] hundredths) {
			this.id = id;
			this.label = label;
			this.color = color;
			this.hundredths = hundredths;
		}

		public String id() {
			return id;
		}

		public String label() {
			return label;
		}

		public String color() {
			return color;
		}

		/** Multiplier of the bucket in hundredths. */
		public int hundredths(int bucket) {
			return hundredths[bucket];
		}

		/** Copy of the table (for display and tests). */
		public int[] table() {
			return hundredths.clone();
		}

		/** "low"/"l", "medium"/"med"/"mid"/"m", "high"/"h" (case-insensitive); null = unknown. */
		public static Risk parse(String s) {
			if (s == null) return null;
			return switch (s.trim().toLowerCase(Locale.ROOT)) {
				case "low", "l" -> LOW;
				case "medium", "med", "mid", "m" -> MEDIUM;
				case "high", "h" -> HIGH;
				default -> null;
			};
		}

		/** Stored id (e.g. from player data) or {@code fallback}. */
		public static Risk byId(String id, Risk fallback) {
			for (Risk r : values()) {
				if (r.id.equals(id)) return r;
			}
			return fallback;
		}
	}

	/** Ball path: 8 decisions, 0 = left, 1 = right (50 % each). */
	public static int[] path(RandomGenerator rng) {
		int[] p = new int[ROWS];
		for (int i = 0; i < ROWS; i++) p[i] = rng.nextInt(2);
		return p;
	}

	/** Bucket (0–8) = number of right bounces. */
	public static int bucket(int[] path) {
		if (path == null || path.length != ROWS) throw new IllegalArgumentException("path needs " + ROWS + " steps");
		int k = 0;
		for (int b : path) {
			if (b != 0 && b != 1) throw new IllegalArgumentException("step must be 0 or 1");
			k += b;
		}
		return k;
	}

	/** Payout (rounded down, exact via BigInteger: no overflow on large stakes). */
	public static long payout(long stake, Risk risk, int bucket) {
		return BigInteger.valueOf(stake).multiply(BigInteger.valueOf(risk.hundredths(bucket))).divide(BigInteger.valueOf(100)).longValueExact();
	}

	/** Highest possible payout at this stake (the Treasury must cover it). */
	public static long potential(long stake, Risk risk) {
		return BigInteger.valueOf(stake).multiply(BigInteger.valueOf(maxHundredths(risk))).divide(BigInteger.valueOf(100)).longValueExact();
	}

	public static int maxHundredths(Risk risk) {
		int max = 0;
		for (int h : risk.hundredths) max = Math.max(max, h);
		return max;
	}

	/** Probability of the bucket (0–1). */
	public static double chance(int bucket) {
		return WAYS[bucket] / (double) PATHS;
	}

	/** Exact theoretical return (sum over all 256 paths). */
	public static double theoreticalRtp(Risk risk) {
		return weightedHundredths(risk) / (PATHS * 100.0);
	}

	/** {@code Σ C(8, k) · m(k)} in hundredths (RTP × 25,600, integer). */
	public static long weightedHundredths(Risk risk) {
		long sum = 0;
		for (int k = 0; k < BUCKETS; k++) sum += (long) WAYS[k] * risk.hundredths[k];
		return sum;
	}

	/**
	 * Ball positions for the animation: {@code [step][row, column]}, step 0 = drop (row 0, column 4). After every two
	 * decisions the ball is one row lower (row 4 = buckets, column = bucket); in between it bounces one column sideways
	 * at the peg (in the bounce direction). All columns are in 0–8.
	 */
	public static int[][] frames(int[] path) {
		bucket(path); // checks the path
		int[][] f = new int[ROWS + 1][];
		f[0] = new int[] {0, 4};
		int rights = 0;
		for (int j = 1; j <= ROWS; j++) {
			int b = path[j - 1];
			if (j % 2 == 1) {
				int i = (j - 1) / 2;
				int c = rights + 4 - i; // column after 2i decisions
				rights += b;
				f[j] = new int[] {i, c + (b == 1 ? 1 : -1)};
			} else {
				rights += b;
				int row = j / 2;
				f[j] = new int[] {row, rights + 4 - row};
			}
		}
		return f;
	}

	/** "×5.6", "×13", "×0.4", "×1.25" (exact, no needless zeros). */
	public static String formatHundredths(int h) {
		if (h % 100 == 0) return "×" + h / 100;
		if (h % 10 == 0) return "×" + h / 100 + "." + h % 100 / 10;
		return "×" + h / 100 + "." + String.format(Locale.ROOT, "%02d", h % 100);
	}

	/** "96.25 %" */
	public static String formatPercent(double fraction) {
		return String.format(Locale.ROOT, "%.2f %%", fraction * 100);
	}
}
