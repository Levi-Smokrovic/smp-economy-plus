package dev.smpeconomy.features.casino.games.horserace;

import java.math.BigInteger;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

/**
 * Pure horse race maths (no Minecraft classes, deterministic for a given random source, used by the selftests).
 *
 * <p>Every race card gives the 6 horses fresh win chances in basis points (sum exactly 10,000). The odds of a horse are
 * {@code floor((10000 - edgeBp) * 100 / chanceBp)} hundredths, so a bet on horse {@code i} returns
 * {@code chance_i * odds_i = (1 - edge) - (rounding < 0.01 * chance_i)} on average. Chances stay below 41 %, so the rounding
 * costs less than 0.41 points: with the default base edge of 4.5 % every single bet has an RTP in (95.09 %, 95.5 %], i.e. a
 * house edge of 4.5 to 4.9 %.
 */
public final class HorseMath {
	private HorseMath() {}

	public static final int HORSES = 6;
	public static final int BP = 10_000;
	/** Base chances of a card (favourite … outsider) before jitter and shuffling, sum 10,000. */
	static final int[] BASE_BP = {3_100, 2_300, 1_700, 1_300, 900, 700};
	/** Jitter of each base chance in per mille: factor 0.80 … 1.20. */
	static final int JITTER_MIN = 800;
	static final int JITTER_SPAN = 401;
	/** Track cells 0–7 (chest columns 0–7); position 8 = crossed the finish line (column 8). */
	public static final int FINISH = 8;

	/** Win chances in basis points for a new card (each ≥ 1, sum exactly {@value #BP}). */
	public static int[] chances(RandomGenerator rng) {
		long[] w = new long[HORSES];
		long sum = 0;
		for (int i = 0; i < HORSES; i++) {
			w[i] = (long) BASE_BP[i] * (JITTER_MIN + rng.nextInt(JITTER_SPAN));
			sum += w[i];
		}
		// shuffle the strengths over the 6 horses (Fisher-Yates)
		for (int i = HORSES - 1; i > 0; i--) {
			int j = rng.nextInt(i + 1);
			long t = w[i];
			w[i] = w[j];
			w[j] = t;
		}
		// normalise to exactly 10,000 bp (largest remainder)
		int[] c = new int[HORSES];
		long[] rem = new long[HORSES];
		int total = 0;
		for (int i = 0; i < HORSES; i++) {
			long scaled = w[i] * BP;
			c[i] = (int) (scaled / sum);
			rem[i] = scaled % sum;
			total += c[i];
		}
		while (total < BP) {
			int best = 0;
			for (int i = 1; i < HORSES; i++) if (rem[i] > rem[best]) best = i;
			c[best]++;
			rem[best] = -1;
			total++;
		}
		for (int i = 0; i < HORSES; i++) {
			if (c[i] < 1) throw new IllegalStateException("chance 0");
		}
		return c;
	}

	/** Odds in hundredths (318 = ×3.18): fair odds minus the edge, rounded down. */
	public static int odds(int chanceBp, int edgeBp) {
		if (chanceBp <= 0 || chanceBp > BP) throw new IllegalArgumentException("chance " + chanceBp);
		if (edgeBp < 0 || edgeBp >= BP) throw new IllegalArgumentException("edge " + edgeBp);
		return (int) ((long) (BP - edgeBp) * 100 / chanceBp);
	}

	public static int[] odds(int[] chances, int edgeBp) {
		int[] o = new int[chances.length];
		for (int i = 0; i < chances.length; i++) o[i] = odds(chances[i], edgeBp);
		return o;
	}

	/** Payout of a winning bet: stake × odds, rounded down. */
	public static long payout(long stake, int oddsHundredths) {
		return BigInteger.valueOf(stake).multiply(BigInteger.valueOf(oddsHundredths)).divide(BigInteger.valueOf(100)).longValueExact();
	}

	/** Payout without throwing (overflow → {@link Long#MAX_VALUE}, never admitted). */
	public static long payoutSafe(long stake, int oddsHundredths) {
		try {
			return payout(stake, oddsHundredths);
		} catch (ArithmeticException e) {
			return Long.MAX_VALUE;
		}
	}

	/** Expected return of a bet on horse {@code i} (chance × odds), e.g. 0.958. */
	public static double betRtp(int chanceBp, int oddsHundredths) {
		return chanceBp / (double) BP * oddsHundredths / 100.0;
	}

	/** Expected return of the whole card if every horse got the same stake. */
	public static double cardRtp(int[] chances, int[] odds) {
		double sum = 0;
		for (int i = 0; i < chances.length; i++) sum += betRtp(chances[i], odds[i]);
		return sum;
	}

	public static int winner(int[] chances, RandomGenerator rng) {
		int roll = rng.nextInt(BP);
		for (int i = 0; i < chances.length; i++) {
			roll -= chances[i];
			if (roll < 0) return i;
		}
		return chances.length - 1;
	}

	/** Highest odds a card can have with this edge (smallest possible chance); bounds the maximum payout. */
	public static int maxOdds(int edgeBp) {
		// smallest jittered weight against the largest possible total
		long minW = (long) BASE_BP[HORSES - 1] * JITTER_MIN;
		long maxTotal = 0;
		for (int i = 0; i < HORSES; i++) maxTotal += (long) BASE_BP[i] * (JITTER_MIN + JITTER_SPAN - 1);
		maxTotal = maxTotal - (long) BASE_BP[HORSES - 1] * (JITTER_MIN + JITTER_SPAN - 1) + minW;
		int minChance = (int) (minW * BP / maxTotal);
		return odds(Math.max(1, minChance), edgeBp);
	}

	/**
	 * Track positions per frame for a decided race: {@code pos[frame][horse]} in 0–{@value #FINISH} (8 = finished). The
	 * winner crosses the line first (strictly), the others one after another in a random order; positions never go back.
	 * Pure display: the same seed and winner always give the same race.
	 */
	public static int[][] frames(long seed, int winner) {
		SplittableRandom r = new SplittableRandom(seed);
		int[] finish = finishFrames(r, winner);
		int end = endFrame(finish[winner]);
		int[][] pos = new int[end + 1][HORSES];
		for (int h = 0; h < HORSES; h++) {
			int t = finish[h];
			long[] cum = new long[t + 1];
			// running style: front-runner (fast start), even pace or closer (strong finish), which gives lead changes
			int style = r.nextInt(3);
			for (int f = 1; f <= t; f++) {
				boolean firstHalf = f * 2 <= t;
				int base = style == 0 ? (firstHalf ? 4 : 1) : style == 2 ? (firstHalf ? 1 : 4) : 2;
				cum[f] = cum[f - 1] + base + r.nextInt(4); // always > 0: never backwards, never across the line early
			}
			for (int f = 0; f <= end; f++) {
				pos[f][h] = f >= t ? FINISH : (int) (FINISH * cum[f] / cum[t]);
			}
		}
		return pos;
	}

	/** Frame in which each horse crosses the line (winner first, others strictly later). */
	static int[] finishFrames(SplittableRandom r, int winner) {
		int[] finish = new int[HORSES];
		int first = 22 + r.nextInt(5);
		finish[winner] = first;
		int[] others = new int[HORSES - 1];
		int n = 0;
		for (int h = 0; h < HORSES; h++) if (h != winner) others[n++] = h;
		for (int i = others.length - 1; i > 0; i--) {
			int j = r.nextInt(i + 1);
			int t = others[i];
			others[i] = others[j];
			others[j] = t;
		}
		int t = first;
		for (int h : others) {
			t += 1 + r.nextInt(2);
			finish[h] = t;
		}
		return finish;
	}

	/** Frame in which the winner crosses the line. */
	public static int winnerFrame(long seed, int winner) {
		return finishFrames(new SplittableRandom(seed), winner)[winner];
	}

	/** Last animated frame (a few frames after the winner, slow horses may still be on the track). */
	static int endFrame(int winnerFrame) {
		return winnerFrame + 6;
	}

	/** Finishing place (1 = winner) of every horse that has crossed by {@code frame}, otherwise 0. */
	public static int[] places(int[][] pos, int frame) {
		int f = Math.max(0, Math.min(frame, pos.length - 1));
		int[] place = new int[HORSES];
		int next = 1;
		for (int g = 0; g <= f; g++) {
			for (int h = 0; h < HORSES; h++) {
				if (place[h] == 0 && pos[g][h] >= FINISH) place[h] = next++;
			}
		}
		return place;
	}

	/** "×3.18" */
	public static String formatOdds(int hundredths) {
		return "×" + hundredths / 100 + "." + String.format(java.util.Locale.ROOT, "%02d", hundredths % 100);
	}

	/** "31.0 %" */
	public static String formatChance(int bp) {
		return String.format(java.util.Locale.ROOT, "%.1f %%", bp / 100.0);
	}
}
