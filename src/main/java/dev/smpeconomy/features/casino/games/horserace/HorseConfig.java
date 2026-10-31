package dev.smpeconomy.features.casino.games.horserace;

/**
 * {@code config/smpeconomy/casino_horserace.json}. Limits (minimum/maximum bet, 2 % of the treasury, maximum win, daily
 * loss limit, casino on/off, big-win thresholds) come from the casino config ({@code casino.json}); here only the race
 * itself.
 */
public final class HorseConfig {
	/** Horse race on/off (off: no new bets; a running race still finishes and pays). */
	public boolean enabled = true;
	/**
	 * Base house edge in % (3–4.5). Odds are rounded down to 0.01, which adds less than 0.41 points: the effective edge of
	 * every bet is base … base + 0.41 (default 4.5 → RTP 95.1–95.5 %).
	 */
	public double baseHouseEdgePercent = 4.5;
	/** At most one race start per this many seconds (global cadence, ~2 minutes). */
	public int raceIntervalSeconds = 120;
	/** A race starts at the earliest this many seconds after the first bet on its card (time for others to join in). */
	public int bettingWindowSeconds = 30;
	/** "A horse race starts in 30 s" in chat (setting "announcements") when the first bet comes in, at most this often. */
	public int raceAnnounceCooldownMinutes = 10;
	public boolean raceAnnouncements = true;

	/** Throws on invalid values (the ConfigManager then keeps the previous state). */
	public void validate() {
		if (!(baseHouseEdgePercent >= 3.0) || baseHouseEdgePercent > 4.5) {
			throw new IllegalArgumentException("baseHouseEdgePercent must be in [3, 4.5] (effective edge 3–4.9 %, RTP 95–97 %)");
		}
		if (raceIntervalSeconds < 30 || raceIntervalSeconds > 3600) throw new IllegalArgumentException("raceIntervalSeconds must be in [30, 3600]");
		if (bettingWindowSeconds < 5 || bettingWindowSeconds > 600) throw new IllegalArgumentException("bettingWindowSeconds must be in [5, 600]");
		if (raceAnnounceCooldownMinutes < 0 || raceAnnounceCooldownMinutes > 1440) throw new IllegalArgumentException("raceAnnounceCooldownMinutes must be in [0, 1440]");
	}

	public int edgeBp() {
		return (int) Math.round(baseHouseEdgePercent * 100);
	}

	HorseConfig copy() {
		HorseConfig c = new HorseConfig();
		c.copyFrom(this);
		return c;
	}

	void copyFrom(HorseConfig o) {
		enabled = o.enabled;
		baseHouseEdgePercent = o.baseHouseEdgePercent;
		raceIntervalSeconds = o.raceIntervalSeconds;
		bettingWindowSeconds = o.bettingWindowSeconds;
		raceAnnounceCooldownMinutes = o.raceAnnounceCooldownMinutes;
		raceAnnouncements = o.raceAnnouncements;
	}
}
