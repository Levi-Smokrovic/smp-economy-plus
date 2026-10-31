package dev.smpeconomy.features.casino.games.dice;

import java.math.BigInteger;
import java.util.random.RandomGenerator;

/**
 * Dice math (pure Java, deterministic with a passed random source, for selftests and RTP simulation).
 *
 * <p>Rolls a whole number 1–100. "Over N" wins on a roll &gt; N (chance 100 − N %), "Under N" on a roll &lt; N (chance
 * N − 1 %). Payout = stake × 99 / chance × (1 − edge), computed exactly and rounded down. The expected return is
 * therefore 0.99 × (1 − edge) for every target (default 3 % gives RTP 96.03 %, house edge 3.97 %); rounding down to the
 * cent only takes fractions of a cent from the player.
 */
public final class DiceMath {
	private DiceMath() {}

	/** Smallest and largest roll. */
	public static final int MIN_ROLL = 1;
	public static final int MAX_ROLL = 100;
	/** Allowed win chance in percent (1 % = up to ×96, 95 % = just above ×1). */
	public static final int MIN_CHANCE = 1;
	public static final int MAX_CHANCE = 95;

	private static final BigInteger BP = BigInteger.valueOf(10_000);
	private static final BigInteger NINETY_NINE = BigInteger.valueOf(99);

	/** Win chance in percent (= number of winning rolls out of 100). */
	public static int chance(boolean over, int target) {
		return over ? MAX_ROLL - target : target - MIN_ROLL;
	}

	/** Target for a win chance (over: 100 − chance, under: chance + 1). */
	public static int targetFor(boolean over, int chance) {
		return over ? MAX_ROLL - chance : chance + MIN_ROLL;
	}

	public static int minTarget(boolean over) {
		return over ? targetFor(true, MAX_CHANCE) : targetFor(false, MIN_CHANCE);
	}

	public static int maxTarget(boolean over) {
		return over ? targetFor(true, MIN_CHANCE) : targetFor(false, MAX_CHANCE);
	}

	/** Is the target playable in this mode (chance 1–95 %)? */
	public static boolean valid(boolean over, int target) {
		if (target < MIN_ROLL || target > MAX_ROLL) return false;
		int c = chance(over, target);
		return c >= MIN_CHANCE && c <= MAX_CHANCE;
	}

	/** Moves the target into the playable range. */
	public static int clampTarget(boolean over, int target) {
		return Math.max(minTarget(over), Math.min(maxTarget(over), target));
	}

	public static boolean wins(int roll, boolean over, int target) {
		return over ? roll > target : roll < target;
	}

	public static int roll(RandomGenerator rng) {
		return MIN_ROLL + rng.nextInt(MAX_ROLL - MIN_ROLL + 1);
	}

	/** Payout on a win: floor(stake × 99 × (10000 − edgeBp) / (chance × 10000)). */
	public static long payout(long stake, int chance, int edgeBp) {
		if (chance < MIN_CHANCE || chance > MAX_CHANCE) throw new IllegalArgumentException("Chance " + chance + " not in [" + MIN_CHANCE + ", " + MAX_CHANCE + "]");
		if (stake <= 0) return 0;
		return BigInteger.valueOf(stake).multiply(NINETY_NINE).multiply(BigInteger.valueOf(10_000L - edgeBp))
			.divide(BigInteger.valueOf(chance).multiply(BP)).longValueExact();
	}

	/** Multiplier (display): 99 / chance × (1 − edge). */
	public static double multiplier(int chance, int edgeBp) {
		return 99.0 * (10_000 - edgeBp) / (chance * 10_000.0);
	}

	/** Expected return without rounding: 0.99 × (1 − edge), the same for every target. */
	public static double rtp(int edgeBp) {
		return 0.99 * (10_000 - edgeBp) / 10_000.0;
	}

	/** Exact expected return of a stake at this chance (with rounding down to the cent). */
	public static double exactRtp(long stake, int chance, int edgeBp) {
		return chance / 100.0 * payout(stake, chance, edgeBp) / (double) stake;
	}

	/** Multiplier text "×1.96" (rounded down: never promises more than is paid). */
	public static String formatMultiplier(int chance, int edgeBp) {
		double m = Math.floor(multiplier(chance, edgeBp) * 100 + 1e-9) / 100;
		return "×" + String.format(java.util.Locale.ROOT, "%.2f", m);
	}

	/** "96.03 %". */
	public static String formatPercent(double fraction) {
		return String.format(java.util.Locale.ROOT, "%.2f %%", fraction * 100);
	}
}
