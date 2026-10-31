package dev.smpeconomy.features.casino;

import dev.smpeconomy.core.Money;

/**
 * {@code config/smpeconomy/casino.json}. Amounts in cents. Game math (coin flip 48 %, slots paytable) is fixed in code
 * ({@link CasinoMath}); this holds only limits and the mines and hi-lo house edge (3–5 %).
 */
public final class CasinoConfig {
	/** Casino on/off (off: no new rounds, open ones settle normally). */
	public boolean enabled = true;
	/** Minimum bet (default $1). */
	public long minBet = 100;
	/** Absolute maximum bet (default $25,000); {@link #maxBetTreasuryPercent} applies as well. */
	public long maxBet = 2_500_000;
	/** Maximum bet in % of the Treasury balance. At most 2 %; larger values are capped at 2 %. */
	public double maxBetTreasuryPercent = 2.0;
	/** Maximum win (payout) of a round in % of the Treasury balance (e.g. end of double or nothing). */
	public double maxWinTreasuryPercent = 50.0;
	/**
	 * Daily loss limit per player (server time zone), default $50,000. Players can set a lower limit for themselves.
	 */
	public long dailyLossLimit = 5_000_000;
	/** Mines house edge in % (3–5). */
	public double minesHouseEdgePercent = 4.0;
	/** Higher/lower house edge in % (3–5). */
	public double hiloHouseEdgePercent = 4.0;
	/** Highest mines multiplier (paid out automatically there). Decides the coverage the Treasury reserves. */
	public double minesMaxMultiplier = 25.0;
	/** Highest higher/lower multiplier. */
	public double hiloMaxMultiplier = 25.0;
	/** Default bomb count for /mines without an argument. */
	public int minesDefaultBombs = 3;
	/** Big win: chat announcement from this net win (payout minus stake), default $2,500. */
	public long announceMinProfit = 250_000;
	/** ... or from this multiplier (with at least {@link #announceMinPayoutForMultiplier} payout). */
	public double announceMinMultiplier = 10.0;
	public long announceMinPayoutForMultiplier = 50_000;
	/** Validity of a double-or-nothing offer after a PvP coin flip win (seconds). */
	public int offerTimeoutSeconds = 30;
	/**
	 * Idle: a round with no move for this many minutes is settled at its current value even with the window open (no
	 * winning step: stake back) and the window is closed.
	 */
	public int idleTimeoutMinutes = 5;

	/** Throws on invalid values (ConfigManager then keeps the previous state). */
	public void validate() {
		if (!Money.isValidAmount(minBet)) throw new IllegalArgumentException("minBet must be > 0");
		if (!Money.isValidAmount(maxBet) || maxBet < minBet) throw new IllegalArgumentException("maxBet must be >= minBet");
		if (!(maxBetTreasuryPercent > 0) || maxBetTreasuryPercent > 100) throw new IllegalArgumentException("maxBetTreasuryPercent must be in (0, 100] (effectively at most 2)");
		if (!(maxWinTreasuryPercent > 0) || maxWinTreasuryPercent > 100) throw new IllegalArgumentException("maxWinTreasuryPercent must be in (0, 100]");
		if (dailyLossLimit < 0 || dailyLossLimit > Money.MAX) throw new IllegalArgumentException("dailyLossLimit is invalid");
		checkEdge(minesHouseEdgePercent, "minesHouseEdgePercent");
		checkEdge(hiloHouseEdgePercent, "hiloHouseEdgePercent");
		if (!(minesMaxMultiplier >= 2) || minesMaxMultiplier > 1000) throw new IllegalArgumentException("minesMaxMultiplier must be in [2, 1000]");
		if (!(hiloMaxMultiplier >= 2) || hiloMaxMultiplier > 1000) throw new IllegalArgumentException("hiloMaxMultiplier must be in [2, 1000]");
		if (minesDefaultBombs < 1 || minesDefaultBombs > CasinoMath.MINES_TILES - 1) throw new IllegalArgumentException("minesDefaultBombs must be in [1, 24]");
		if (announceMinProfit < 0 || announceMinPayoutForMultiplier < 0) throw new IllegalArgumentException("announce thresholds must not be negative");
		if (!(announceMinMultiplier >= 1)) throw new IllegalArgumentException("announceMinMultiplier must be >= 1");
		if (offerTimeoutSeconds < 5 || offerTimeoutSeconds > 600) throw new IllegalArgumentException("offerTimeoutSeconds must be in [5, 600]");
		if (idleTimeoutMinutes < 1 || idleTimeoutMinutes > 120) throw new IllegalArgumentException("idleTimeoutMinutes must be in [1, 120]");
	}

	/** Copies all values (selftests set defaults and restore them afterwards). */
	void copyFrom(CasinoConfig o) {
		enabled = o.enabled;
		minBet = o.minBet;
		maxBet = o.maxBet;
		maxBetTreasuryPercent = o.maxBetTreasuryPercent;
		maxWinTreasuryPercent = o.maxWinTreasuryPercent;
		dailyLossLimit = o.dailyLossLimit;
		minesHouseEdgePercent = o.minesHouseEdgePercent;
		hiloHouseEdgePercent = o.hiloHouseEdgePercent;
		minesMaxMultiplier = o.minesMaxMultiplier;
		hiloMaxMultiplier = o.hiloMaxMultiplier;
		minesDefaultBombs = o.minesDefaultBombs;
		announceMinProfit = o.announceMinProfit;
		announceMinMultiplier = o.announceMinMultiplier;
		announceMinPayoutForMultiplier = o.announceMinPayoutForMultiplier;
		offerTimeoutSeconds = o.offerTimeoutSeconds;
		idleTimeoutMinutes = o.idleTimeoutMinutes;
	}

	CasinoConfig copy() {
		CasinoConfig c = new CasinoConfig();
		c.copyFrom(this);
		return c;
	}

	private static void checkEdge(double v, String name) {
		if (!(v >= 3.0) || v > 5.0) throw new IllegalArgumentException(name + " must be in [3, 5] (house edge 3–5%)");
	}

	/** Maximum bet percent, hard-capped at 2 %. */
	public double effectiveMaxBetPercent() {
		return Math.min(2.0, maxBetTreasuryPercent);
	}

	/** House edge in basis points (1 % = 100). */
	public int minesEdgeBp() {
		return (int) Math.round(minesHouseEdgePercent * 100);
	}

	public int hiloEdgeBp() {
		return (int) Math.round(hiloHouseEdgePercent * 100);
	}

	/** Multiplier cap in basis points (×25 = 250000). */
	public long minesCapBp() {
		return Math.round(minesMaxMultiplier * 10_000);
	}

	public long hiloCapBp() {
		return Math.round(hiloMaxMultiplier * 10_000);
	}
}
