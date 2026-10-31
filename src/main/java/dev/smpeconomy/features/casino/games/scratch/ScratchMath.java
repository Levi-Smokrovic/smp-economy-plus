package dev.smpeconomy.features.casino.games.scratch;

import java.math.BigInteger;
import java.util.random.RandomGenerator;

/**
 * Scratch card maths (pure Java). A card has 9 fields; exactly 3 equal symbols win that symbol's prize (price ×
 * multiplier), a card without a triple wins nothing. The prize is drawn first from a fixed table (per 1,000 cards), then a
 * grid is built that shows exactly that result, never two triples, never a triple on a losing card.
 *
 * <p>Return to player = Σ weight × multiplier / 1,000 = 955 / 1,000 = 95.5 % (house edge 4.5 %) for every tier.
 */
public final class ScratchMath {
	public static final int CELLS = 9;
	public static final int MATCH = 3;
	/** Symbols from the smallest to the biggest prize. */
	public static final String[] NAMES = {"Copper", "Iron", "Gold", "Emerald", "Diamond", "Nether Star"};
	public static final int[] MULTIPLIERS = {1, 2, 3, 5, 10, 25};
	/** Cards per {@link #TOTAL_WEIGHT} with this prize; the rest (561) wins nothing. */
	public static final int[] WEIGHTS = {215, 120, 60, 30, 12, 2};
	public static final int TOTAL_WEIGHT = 1_000;
	public static final int SYMBOLS = NAMES.length;
	/** No prize. */
	public static final int NONE = -1;

	private ScratchMath() {}

	/** Highest multiplier (the Treasury must cover price × this before a card is sold). */
	public static int maxMultiplier() {
		int max = 0;
		for (int m : MULTIPLIERS) max = Math.max(max, m);
		return max;
	}

	/** Exact return to player of the prize table (0.955). */
	public static double theoreticalRtp() {
		long sum = 0;
		for (int i = 0; i < SYMBOLS; i++) sum += (long) WEIGHTS[i] * MULTIPLIERS[i];
		return sum / (double) TOTAL_WEIGHT;
	}

	/** Chance of any prize (incl. money back). */
	public static double prizeChance() {
		long sum = 0;
		for (int w : WEIGHTS) sum += w;
		return sum / (double) TOTAL_WEIGHT;
	}

	/** Draws the prize of a new card: symbol index or {@link #NONE}. */
	public static int drawPrize(RandomGenerator rng) {
		int x = rng.nextInt(TOTAL_WEIGHT);
		for (int i = SYMBOLS - 1; i >= 0; i--) {
			if (x < WEIGHTS[i]) return i;
			x -= WEIGHTS[i];
		}
		return NONE;
	}

	/**
	 * Builds the 9 fields for a drawn prize: the prize symbol exactly 3 times, every other symbol at most twice (so there is
	 * no second triple); for {@link #NONE} every symbol at most twice. Positions and fillers are random.
	 */
	public static int[] grid(int prize, RandomGenerator rng) {
		if (prize < NONE || prize >= SYMBOLS) throw new IllegalArgumentException("prize " + prize);
		int[] pos = new int[CELLS];
		for (int i = 0; i < CELLS; i++) pos[i] = i;
		for (int i = CELLS - 1; i > 0; i--) {
			int j = rng.nextInt(i + 1);
			int t = pos[i];
			pos[i] = pos[j];
			pos[j] = t;
		}
		int[] cells = new int[CELLS];
		int[] count = new int[SYMBOLS];
		int next = 0;
		if (prize != NONE) {
			for (int k = 0; k < MATCH; k++) cells[pos[next++]] = prize;
			count[prize] = MATCH;
		}
		int[] allowed = new int[SYMBOLS];
		while (next < CELLS) {
			int n = 0;
			for (int s = 0; s < SYMBOLS; s++) {
				if (count[s] < MATCH - 1) allowed[n++] = s;
			}
			int s = allowed[rng.nextInt(n)];
			cells[pos[next++]] = s;
			count[s]++;
		}
		return cells;
	}

	public static Card draw(RandomGenerator rng) {
		int prize = drawPrize(rng);
		return new Card(prize, grid(prize, rng));
	}

	/** Prize shown by a grid: the symbol that appears at least 3 times, or {@link #NONE}; -2 = invalid grid (two triples). */
	public static int evaluate(int[] grid) {
		if (grid == null || grid.length != CELLS) return -2;
		int[] count = new int[SYMBOLS];
		for (int s : grid) {
			if (s < 0 || s >= SYMBOLS) return -2;
			count[s]++;
		}
		int found = NONE;
		for (int s = 0; s < SYMBOLS; s++) {
			if (count[s] >= MATCH) {
				if (found != NONE) return -2;
				found = s;
			}
		}
		return found;
	}

	/** Payout of a card with this prize (0 for {@link #NONE}). */
	public static long payout(long price, int prize) {
		if (prize == NONE) return 0;
		return BigInteger.valueOf(price).multiply(BigInteger.valueOf(MULTIPLIERS[prize])).longValueExact();
	}

	public static long maxPayout(long price) {
		return BigInteger.valueOf(price).multiply(BigInteger.valueOf(maxMultiplier())).longValueExact();
	}

	/** One card: the prize (symbol index or {@link #NONE}) and the 9 fields that show it. */
	public record Card(int prize, int[] grid) {}
}
