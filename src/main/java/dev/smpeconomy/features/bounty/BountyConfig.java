package dev.smpeconomy.features.bounty;

import dev.smpeconomy.core.Money;

/** {@code config/smpeconomy/bounty.json}. Amounts in cents. */
public final class BountyConfig {
	/** Upper bound for {@link #minKillerPlaytimeMinutes} (about 69 days). */
	static final int MAX_PLAYTIME_MINUTES = 100_000;

	/** Percent of the bounty taken on payout, paid to the treasury. Allowed 0 to 50. */
	public double feePercent = 5.0;
	/** Minimum per contribution. */
	public long minAmount = 10_000;
	/** Highest bounty per target (0 = limited only by Money.MAX). */
	public long maxTotalPerTarget = 0;
	/** Broadcast to everyone, except players with the announcements setting off. */
	public boolean announce = true;
	/** Only contributions from this amount are announced; payouts always are. */
	public long announceMinAmount = 10_000;
	/**
	 * At most one contribution announcement per target in this interval, against spam from many small
	 * amounts.
	 */
	public int announceCooldownSeconds = 30;
	/**
	 * At most one personal message per target in this interval (1 to 3600 s); later contributions are merged
	 * into one message. An offline target only collects a single note for the next join, since one pending
	 * message per contribution would push other messages out of the 50-entry store.
	 */
	public int targetNoticeCooldownSeconds = 30;
	/** No payout if killer and victim share a scoreboard team (e.g. a clan). The bounty stays. */
	public boolean blockSameTeam = true;
	/** Hint text in bounty.json, ignored on load. Explains when {@link #blockSameIp} applies. */
	public String _info = "blockSameIp only compares public IP addresses. Tunnel, Docker, LAN and loopback addresses"
		+ " (playit agent, 127.0.0.1, 10/8, 172.16/12, 192.168/16, 100.64/10, fc00::/7) count as unknown and never block."
		+ " Behind the playit tunnel the server sees no real player IPs without a PROXY protocol mod – the rule does"
		+ " nothing there (warning in the log on the first join); it only works with real client IPs. Until then"
		+ " minKillerPlaytimeMinutes protects against fresh alt accounts (the killer needs that much playtime, otherwise the bounty stays).";
	/**
	 * No payout if killer and victim share a public IP (alts). The bounty stays. Non-public addresses
	 * (tunnel, proxy, LAN) never count as equal, see {@link BountyFeature#publicIp}.
	 */
	public boolean blockSameIp = true;
	/**
	 * No payout if the killer has less playtime (minutes), against fresh alts claiming a bounty on their own
	 * main. Works without IP addresses, so also behind a tunnel. 0 = off. The bounty stays.
	 */
	public int minKillerPlaytimeMinutes = 120;
	/** Past payouts kept for the "recent hunts" GUI. */
	public int historySize = 20;

	/** Throws on invalid values; the previous state or the defaults then stay active. */
	void validate() {
		if (!(feePercent >= 0 && feePercent <= 50)) throw new IllegalArgumentException("feePercent must be between 0 and 50");
		if (!Money.isValidAmount(minAmount)) throw new IllegalArgumentException("minAmount must be > 0");
		if (maxTotalPerTarget < 0 || maxTotalPerTarget > Money.MAX) throw new IllegalArgumentException("maxTotalPerTarget must be 0..Money.MAX");
		if (maxTotalPerTarget > 0 && maxTotalPerTarget < minAmount) throw new IllegalArgumentException("maxTotalPerTarget < minAmount");
		if (announceMinAmount < 0) throw new IllegalArgumentException("announceMinAmount must be >= 0");
		if (announceCooldownSeconds < 0) throw new IllegalArgumentException("announceCooldownSeconds must be >= 0");
		if (targetNoticeCooldownSeconds < 1 || targetNoticeCooldownSeconds > 3600) {
			throw new IllegalArgumentException("targetNoticeCooldownSeconds must be 1..3600");
		}
		if (minKillerPlaytimeMinutes < 0 || minKillerPlaytimeMinutes > MAX_PLAYTIME_MINUTES) {
			throw new IllegalArgumentException("minKillerPlaytimeMinutes must be 0.." + MAX_PLAYTIME_MINUTES);
		}
		if (historySize < 0 || historySize > 200) throw new IllegalArgumentException("historySize must be 0..200");
	}

	/** Minimum killer playtime in ticks (0 = none). */
	long minKillerPlaytimeTicks() {
		return Math.max(0, minKillerPlaytimeMinutes) * 60L * 20L;
	}

	long maxTotal() {
		return maxTotalPerTarget > 0 ? Math.min(maxTotalPerTarget, Money.MAX) : Money.MAX;
	}
}
