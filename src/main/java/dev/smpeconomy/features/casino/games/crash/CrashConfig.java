package dev.smpeconomy.features.casino.games.crash;

/**
 * {@code config/smpeconomy/casino_crash.json}. Bet limits, treasury coverage and the daily loss limit come from the casino
 * ({@code casino.json}); here only the crash-specific numbers.
 */
public final class CrashConfig {
	/** Crash on/off (off: no new rounds; running rounds finish normally). The casino switch applies as well. */
	public boolean enabled = true;
	/** House edge in % (3–5). RTP = 100 − edge, the same for every cash-out target. */
	public double houseEdgePercent = 4.0;
	/** Highest multiplier: the rocket is cashed out automatically there. Decides the treasury coverage per round. */
	public double maxMultiplier = 100.0;
	/** Seconds until the multiplier doubles (×2 after this time, ×4 after twice as long …). */
	public double doublingSeconds = 5.0;

	/** Throws on invalid values (ConfigManager then keeps the previous state). */
	public void validate() {
		if (!(houseEdgePercent >= 3.0) || houseEdgePercent > 5.0) throw new IllegalArgumentException("houseEdgePercent must be in [3, 5]");
		if (!(maxMultiplier >= 2.0) || maxMultiplier > 1000.0) throw new IllegalArgumentException("maxMultiplier must be in [2, 1000]");
		if (!(doublingSeconds >= 1.0) || doublingSeconds > 60.0) throw new IllegalArgumentException("doublingSeconds must be in [1, 60]");
	}

	/** House edge in basis points (4 % = 400). */
	int edgeBp() {
		return (int) Math.round(houseEdgePercent * 100);
	}

	/** Cap in hundredths (×100 = 10000). */
	long capH() {
		return Math.round(maxMultiplier * 100);
	}

	void copyFrom(CrashConfig o) {
		enabled = o.enabled;
		houseEdgePercent = o.houseEdgePercent;
		maxMultiplier = o.maxMultiplier;
		doublingSeconds = o.doublingSeconds;
	}

	CrashConfig copy() {
		CrashConfig c = new CrashConfig();
		c.copyFrom(this);
		return c;
	}
}
