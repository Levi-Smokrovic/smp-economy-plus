package dev.smpeconomy.features.casino.games.dice;

/**
 * {@code config/smpeconomy/casino_dice.json}. Bet limits, Treasury coverage and the daily limit come from casino.json
 * (shared by all casino games); here only the dice-specific numbers.
 */
public final class DiceConfig {
	/** Dice on/off (off: no new rolls; the casino itself stays open). */
	public boolean enabled = true;
	/**
	 * Edge in the payout formula 99 / chance × (1 − edge), in percent. Default 3 gives RTP 0.99 × 0.97 = 96.03 % (house
	 * edge 3.97 %). Allowed 2.1–4.0 gives house edge 3.08–4.96 % (RTP 95.04–96.92 %).
	 */
	public double edgePercent = 3.0;

	public static final double MIN_EDGE_PERCENT = 2.1;
	public static final double MAX_EDGE_PERCENT = 4.0;

	/** Throws on invalid values (ConfigManager then keeps the previous state). */
	public void validate() {
		if (!(edgePercent >= MIN_EDGE_PERCENT) || edgePercent > MAX_EDGE_PERCENT) {
			throw new IllegalArgumentException("edgePercent must be in [" + MIN_EDGE_PERCENT + ", " + MAX_EDGE_PERCENT + "] (house edge 3–5 %, RTP 95–97 %)");
		}
		double rtp = DiceMath.rtp(edgeBp());
		if (rtp < 0.95 || rtp > 0.97) throw new IllegalArgumentException("RTP " + rtp + " not in [0.95, 0.97]");
	}

	/** Edge in basis points (3 % = 300). */
	public int edgeBp() {
		return (int) Math.round(edgePercent * 100);
	}
}
