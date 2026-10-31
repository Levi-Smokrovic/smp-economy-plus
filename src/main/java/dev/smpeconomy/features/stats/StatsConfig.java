package dev.smpeconomy.features.stats;

/** {@code config/smpeconomy/stats.json}. Values are validated on load; invalid ones keep the old value active. */
public final class StatsConfig {
	/** Entries per page in {@code /leaderboard} (1-20). */
	public int pageSize = 10;
	/** How often the offline cache (stats.json) is refreshed from vanilla stats for online players (10-3600 s). */
	public int snapshotSeconds = 60;
	/**
	 * At server start, import players without a stats.json entry once from {@code <world>/stats/<uuid>.json}, so
	 * leaderboards are right immediately after install.
	 */
	public boolean importVanillaOnStart = true;
	/** Add player kills from before the install (vanilla stat at first capture) to the counted PvP kills. */
	public boolean countFormerKills = true;

	void validate() {
		if (pageSize < 1 || pageSize > 20) throw new IllegalArgumentException("pageSize must be between 1 and 20");
		if (snapshotSeconds < 10 || snapshotSeconds > 3600) throw new IllegalArgumentException("snapshotSeconds must be between 10 and 3600");
	}
}
