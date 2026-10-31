package dev.smpeconomy.features.daily;

/**
 * Per-player daily state (Gson), stored as blob {@value DailyService#STATE_KEY} in players.json, the same file as
 * the crate keys, so "claimed" and the day 7 key are saved together.
 */
public final class DailyState {
	public long lastClaimMs;
	/** Streak length at the last claim. Cycle day = {@link DailyLogic#cycleDay(int)}. */
	public int streak;
	public int bestStreak;
	public long totalClaims;
	public long totalEarned;

	public DailyState copy() {
		DailyState c = new DailyState();
		c.lastClaimMs = lastClaimMs;
		c.streak = streak;
		c.bestStreak = bestStreak;
		c.totalClaims = totalClaims;
		c.totalEarned = totalEarned;
		return c;
	}

	/** Repairs impossible values from hand edits or older versions. */
	public DailyState sanitize() {
		if (lastClaimMs < 0) lastClaimMs = 0;
		if (streak < 0) streak = 0;
		if (bestStreak < streak) bestStreak = streak;
		if (totalClaims < 0) totalClaims = 0;
		if (totalEarned < 0) totalEarned = 0;
		return this;
	}

	boolean isEmpty() {
		return lastClaimMs == 0 && streak == 0 && bestStreak == 0 && totalClaims == 0 && totalEarned == 0;
	}
}
