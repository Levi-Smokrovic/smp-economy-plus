package dev.smpeconomy.features.casino.games.roulette;

import java.util.random.RandomGenerator;

/**
 * European roulette (one zero), pure math without server state. A table is a {@code long[SPOTS]} with the stake (cents)
 * per spot: 0–36 = number (straight, 35:1), then the outside spots (red/black, even/odd, 1–18/19–36 at 1:1; dozens and
 * columns at 2:1). Zero loses all outside spots (no "la partage"). Each spot returns gross {@code 36 / covered numbers}
 * × stake; the house keeps 1 % of a spin's payout (rounded down, {@link #afterFee}), so the payout rate of every stake
 * and combination is 36/37 × 0.99 ≈ 96.3 % (house edge ≈ 3.7 %).
 */
final class RouletteMath {
	static final int POCKETS = 37;
	/** Wheel order clockwise from zero (European wheel). */
	static final int[] WHEEL = {0, 32, 15, 19, 4, 21, 2, 25, 17, 34, 6, 27, 13, 36, 11, 30, 8, 23, 10, 5, 24, 16, 33, 1, 20, 14, 31, 9, 22, 18,
		29, 7, 28, 12, 35, 3, 26};
	private static final boolean[] RED = new boolean[POCKETS];
	private static final int[] WHEEL_INDEX = new int[POCKETS];

	static final int RED_SPOT = 37;
	static final int BLACK_SPOT = 38;
	static final int EVEN = 39;
	static final int ODD = 40;
	static final int LOW = 41;
	static final int HIGH = 42;
	static final int DOZEN_1 = 43;
	static final int COLUMN_1 = 46;
	static final int SPOTS = 49;

	/**
	 * House fee on each payout in percent (classic odds alone would give only 2.7 % house edge, the target band is 3–5
	 * %).
	 */
	static final int FEE_PERCENT = 1;
	/** Payout rate of every stake and combination: 36/37 × 0.99 ≈ 96.32 %. */
	static final double RTP = 36.0 / 37.0 * (100 - FEE_PERCENT) / 100.0;
	/** For player texts. */
	static final String RTP_TEXT = "96.3 %";

	static {
		for (int n : new int[] {1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36}) RED[n] = true;
		for (int i = 0; i < WHEEL.length; i++) WHEEL_INDEX[WHEEL[i]] = i;
	}

	private RouletteMath() {}

	static boolean isRed(int n) {
		return n > 0 && n < POCKETS && RED[n];
	}

	static boolean isBlack(int n) {
		return n > 0 && n < POCKETS && !RED[n];
	}

	/** Position of the number on the wheel ({@link #WHEEL}). */
	static int wheelIndex(int n) {
		return WHEEL_INDEX[n];
	}

	/** The ball: each of the 37 numbers equally likely. */
	static int spin(RandomGenerator rng) {
		return rng.nextInt(POCKETS);
	}

	/** Does the spot win on number {@code n}? */
	static boolean covers(int spot, int n) {
		if (spot >= 0 && spot < POCKETS) return spot == n;
		if (n == 0) return false;
		return switch (spot) {
			case RED_SPOT -> RED[n];
			case BLACK_SPOT -> !RED[n];
			case EVEN -> n % 2 == 0;
			case ODD -> n % 2 == 1;
			case LOW -> n <= 18;
			case HIGH -> n >= 19;
			case DOZEN_1, DOZEN_1 + 1, DOZEN_1 + 2 -> (n - 1) / 12 == spot - DOZEN_1;
			case COLUMN_1, COLUMN_1 + 1, COLUMN_1 + 2 -> (n - 1) % 3 == spot - COLUMN_1;
			default -> throw new IllegalArgumentException("Spot " + spot);
		};
	}

	/** How many numbers the spot covers (1, 12 or 18). */
	static int coverCount(int spot) {
		if (spot >= 0 && spot < POCKETS) return 1;
		if (spot >= DOZEN_1 && spot < SPOTS) return 12;
		if (spot >= RED_SPOT && spot < DOZEN_1) return 18;
		throw new IllegalArgumentException("Spot " + spot);
	}

	/**
	 * Gross return including stake per cent of stake (before the fee): straight ×36 (35:1), dozen or column ×3 (2:1),
	 * even-money ×2 (1:1).
	 */
	static int returnMultiplier(int spot) {
		return 36 / coverCount(spot);
	}

	/** Classic odds as text (before the fee), e.g. "35:1". */
	static String odds(int spot) {
		return (returnMultiplier(spot) - 1) + ":1";
	}

	static long total(long[] bets) {
		long sum = 0;
		for (long b : bets) sum = Math.addExact(sum, b);
		return sum;
	}

	/**
	 * Gross payout minus {@link #FEE_PERCENT} %, rounded down to the cent: {@code floor(gross × 99 / 100) = gross −
	 * ceil(gross / 100)}.
	 */
	static long afterFee(long gross) {
		if (gross <= 0) return 0;
		return gross - Math.ceilDiv(Math.multiplyExact(gross, (long) FEE_PERCENT), 100L);
	}

	/** Payout of a single spot with stake {@code bet} if it wins (after the fee; display only). */
	static long spotWin(long bet, int spot) {
		return afterFee(Math.multiplyExact(bet, returnMultiplier(spot)));
	}

	/** Payout (including returned winning stakes, after the fee) on number {@code n}. */
	static long payout(long[] bets, int n) {
		long sum = 0;
		for (int spot = 0; spot < SPOTS; spot++) {
			if (bets[spot] > 0 && covers(spot, n)) sum = Math.addExact(sum, Math.multiplyExact(bets[spot], returnMultiplier(spot)));
		}
		return afterFee(sum);
	}

	/** Highest possible payout over all 37 numbers (the Treasury must cover it). */
	static long maxPayout(long[] bets) {
		long max = 0;
		for (int n = 0; n < POCKETS; n++) max = Math.max(max, payout(bets, n));
		return max;
	}

	/** Valid table: right length, no negative stakes. */
	static boolean valid(long[] bets) {
		if (bets == null || bets.length != SPOTS) return false;
		for (long b : bets) if (b < 0) return false;
		return true;
	}

	static String color(int n) {
		return n == 0 ? "§a" : RED[n] ? "§c" : "§8";
	}

	static String colorName(int n) {
		return n == 0 ? "Green" : RED[n] ? "Red" : "Black";
	}

	/** E.g. "§c17 Red". */
	static String pocketLabel(int n) {
		return color(n) + n + " " + colorName(n);
	}

	/** Name of a spot, e.g. "17", "Red", "1st 12", "Column 2". */
	static String spotName(int spot) {
		if (spot >= 0 && spot < POCKETS) return String.valueOf(spot);
		return switch (spot) {
			case RED_SPOT -> "Red";
			case BLACK_SPOT -> "Black";
			case EVEN -> "Even";
			case ODD -> "Odd";
			case LOW -> "1–18";
			case HIGH -> "19–36";
			case DOZEN_1 -> "1st 12";
			case DOZEN_1 + 1 -> "2nd 12";
			case DOZEN_1 + 2 -> "3rd 12";
			case COLUMN_1, COLUMN_1 + 1, COLUMN_1 + 2 -> "Column " + (spot - COLUMN_1 + 1);
			default -> "?";
		};
	}

	/** Covered numbers as short text (lore). */
	static String coverText(int spot) {
		if (spot >= 0 && spot < POCKETS) return "the number " + spot;
		if (spot >= DOZEN_1 && spot < COLUMN_1) {
			int from = (spot - DOZEN_1) * 12 + 1;
			return from + " to " + (from + 11);
		}
		if (spot >= COLUMN_1 && spot < SPOTS) {
			int first = spot - COLUMN_1 + 1;
			return first + ", " + (first + 3) + ", " + (first + 6) + " … " + (first + 33);
		}
		return switch (spot) {
			case RED_SPOT -> "all 18 red numbers";
			case BLACK_SPOT -> "all 18 black numbers";
			case EVEN -> "2, 4, 6 … 36";
			case ODD -> "1, 3, 5 … 35";
			case LOW -> "1 to 18";
			case HIGH -> "19 to 36";
			default -> "?";
		};
	}
}
