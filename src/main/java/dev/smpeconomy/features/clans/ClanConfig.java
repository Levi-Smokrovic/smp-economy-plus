package dev.smpeconomy.features.clans;

import dev.smpeconomy.SmpEconomy;

/** {@code config/smpeconomy/clans.json}. Clamped to valid ranges on load, see {@link #sanitize}. */
public final class ClanConfig {
	/** Creation fee in cents, paid to the treasury. */
	public long creationFee = 500_000;
	/** 2 to 27; the GUI shows up to 27 heads. */
	public int maxMembers = 20;
	/** Invite validity in minutes. */
	public int inviteExpiryMinutes = 10;
	/** Teleport delay for /clan home in seconds (1 to 30). */
	public int teleportDelaySeconds = 3;
	/**
	 * Someone joining an open clan without an invite can use {@code /clan home} only after this many minutes
	 * (0 = right away; officers and leader exempt). Protects bases from strangers who only join to loot.
	 */
	public int openJoinHomeMinutes = 30;
	/** Tag color when none is given at creation: id from the fixed list. */
	public String defaultColor = ClanColor.DEFAULT.id;
	/**
	 * Players already in a foreign scoreboard team (not {@code tri_clan_...}, e.g. set by an admin with
	 * /team) are not moved, so their clan tag does not show.
	 */
	public boolean respectForeignTeams = true;
	/** Only sent to players with the "announcements" setting. */
	public boolean announce = true;

	/** Config mistakes should not stop the server. */
	public void sanitize() {
		if (creationFee < 0) {
			SmpEconomy.LOG.warn("[SMPEco] clans.json: creationFee < 0 → 0");
			creationFee = 0;
		}
		if (maxMembers < 2 || maxMembers > 27) {
			SmpEconomy.LOG.warn("[SMPEco] clans.json: maxMembers {} outside 2-27, clamped", maxMembers);
			maxMembers = Math.max(2, Math.min(27, maxMembers));
		}
		if (inviteExpiryMinutes < 1) inviteExpiryMinutes = 1;
		if (inviteExpiryMinutes > 7 * 24 * 60) inviteExpiryMinutes = 7 * 24 * 60;
		if (teleportDelaySeconds < 1) teleportDelaySeconds = 1;
		if (teleportDelaySeconds > 30) teleportDelaySeconds = 30;
		if (openJoinHomeMinutes < 0) openJoinHomeMinutes = 0;
		if (openJoinHomeMinutes > 7 * 24 * 60) openJoinHomeMinutes = 7 * 24 * 60;
		if (ClanColor.byId(defaultColor).isEmpty()) {
			SmpEconomy.LOG.warn("[SMPEco] clans.json: unknown defaultColor '{}' → {}", defaultColor, ClanColor.DEFAULT.id);
			defaultColor = ClanColor.DEFAULT.id;
		}
	}
}
