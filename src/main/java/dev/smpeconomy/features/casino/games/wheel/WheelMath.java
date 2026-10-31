package dev.smpeconomy.features.casino.games.wheel;

import java.math.BigInteger;
import java.util.random.RandomGenerator;

/**
 * Wheel of Fortune math (pure Java, no Minecraft): 20 segments in ring order, each with a multiplier in tenths (12 = 1.2×)
 * and a draw weight out of {@link #TOTAL_WEIGHT}. The wheel is weighted (segments of the same multiplier share one weight,
 * different multipliers have different odds); the true odds are shown in the GUI and in {@code /wheel help}.
 *
 * <pre>
 * multiplier  segments  weight/segment  chance   contribution to RTP
 *   0×          7         790           55.3 %   0
 *   1.2×        5         400           20.0 %   0.240
 *   1.5×        2         500           10.0 %   0.150
 *   2×          2         375            7.5 %   0.150
 *   3×          1         400            4.0 %   0.120
 *   5×          1         200            2.0 %   0.100
 *   10×         1         100            1.0 %   0.100
 *   50×         1          20            0.2 %   0.100
 *                                                 = 0.960 (house edge 4 %)
 * </pre>
 */
public final class WheelMath {
	public static final int SEGMENTS = 20;
	public static final int TOTAL_WEIGHT = 10_000;

	/** Multiplier per segment in tenths, in ring order (segment 0 is the jackpot). */
	static final int[] TENTHS = {500, 0, 12, 20, 0, 15, 12, 50, 0, 12, 30, 0, 15, 12, 100, 0, 20, 12, 0, 0};
	/** The multipliers (tenths), highest first (paytable order). */
	static final int[] TIERS = {500, 100, 50, 30, 20, 15, 12, 0};

	private static final int[] WEIGHTS = new int[SEGMENTS];

	static {
		for (int i = 0; i < SEGMENTS; i++) WEIGHTS[i] = weightPerSegment(TENTHS[i]);
	}

	private WheelMath() {}

	/** Draw weight of one segment with this multiplier. */
	static int weightPerSegment(int tenths) {
		return switch (tenths) {
			case 0 -> 790;
			case 12 -> 400;
			case 15 -> 500;
			case 20 -> 375;
			case 30 -> 400;
			case 50 -> 200;
			case 100 -> 100;
			case 500 -> 20;
			default -> throw new IllegalArgumentException("unknown wheel multiplier " + tenths);
		};
	}

	public static int tenths(int segment) {
		return TENTHS[segment];
	}

	public static int weight(int segment) {
		return WEIGHTS[segment];
	}

	/** Highest multiplier (tenths): 500 = 50×. */
	public static int maxTenths() {
		int max = 0;
		for (int t : TENTHS) max = Math.max(max, t);
		return max;
	}

	/** Draws the landing segment: one {@code nextInt(TOTAL_WEIGHT)} mapped through the cumulative weights. */
	public static int spin(RandomGenerator rng) {
		int roll = rng.nextInt(TOTAL_WEIGHT);
		for (int i = 0; i < SEGMENTS; i++) {
			roll -= WEIGHTS[i];
			if (roll < 0) return i;
		}
		return SEGMENTS - 1; // unreachable while the weights add up to TOTAL_WEIGHT (selftest)
	}

	/** First roll value that lands on {@code segment} (selftests script the RNG with it). */
	static int firstRoll(int segment) {
		int sum = 0;
		for (int i = 0; i < segment; i++) sum += WEIGHTS[i];
		return sum;
	}

	/** Payout of a stake on this segment, rounded down to the cent. */
	public static long payout(long stake, int segment) {
		return payoutTenths(stake, TENTHS[segment]);
	}

	static long payoutTenths(long stake, int tenths) {
		if (stake <= 0 || tenths <= 0) return 0;
		return BigInteger.valueOf(stake).multiply(BigInteger.valueOf(tenths)).divide(BigInteger.TEN).longValueExact();
	}

	/** Chance of a multiplier (all its segments together), 0..1. */
	static double chance(int tenths) {
		long w = 0;
		for (int i = 0; i < SEGMENTS; i++) if (TENTHS[i] == tenths) w += WEIGHTS[i];
		return w / (double) TOTAL_WEIGHT;
	}

	/** Σ weight × tenths; the exact RTP is this / (TOTAL_WEIGHT × 10). */
	static long rtpNumerator() {
		long sum = 0;
		for (int i = 0; i < SEGMENTS; i++) sum += (long) WEIGHTS[i] * TENTHS[i];
		return sum;
	}

	public static double theoreticalRtp() {
		return rtpNumerator() / (TOTAL_WEIGHT * 10.0);
	}

	static int totalWeight() {
		int sum = 0;
		for (int w : WEIGHTS) sum += w;
		return sum;
	}

	/** "0×", "1.2×", "50×". */
	public static String label(int tenths) {
		return (tenths % 10 == 0 ? String.valueOf(tenths / 10) : tenths / 10 + "." + tenths % 10) + "×";
	}

	/** "55.3 %", "0.2 %", "20 %". */
	static String percent(double p) {
		double v = Math.round(p * 1000) / 10.0;
		return (v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v)) + " %";
	}
}
