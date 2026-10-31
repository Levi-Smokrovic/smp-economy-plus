package dev.smpeconomy.features.daily;

import dev.smpeconomy.core.ConfigManager;
import dev.smpeconomy.core.CrateKeys;
import dev.smpeconomy.core.Money;

/**
 * {@code config/smpeconomy/daily.json}. Always read through {@link #get()}: {@code /smpeco reload} replaces the
 * object.
 */
public final class DailyConfig {
	/** Cents per day 1-7, must not decrease. */
	public long[] rewardsCents = {25_000, 50_000, 75_000, 100_000, 150_000, 200_000, 250_000};
	/** {@code CrateKeys.TIERS}: common, rare, epic. */
	public String day7KeyTier = CrateKeys.RARE;
	public int day7Keys = 1;
	/** Hours without a claim until the streak breaks (25-720). */
	public int streakBreakHours = 48;
	public boolean reminder = true;
	/** Seconds, so the reminder shows after the join message. */
	public int reminderDelaySeconds = 3;

	static final ConfigManager.Config<DailyConfig> CONFIG = ConfigManager.register("daily", DailyConfig.class, DailyConfig::new, DailyConfig::validate);

	public static DailyConfig get() {
		return CONFIG.get();
	}

	/** Loads the class, which registers the config. */
	static void bootstrap() {
	}

	public long reward(int day) {
		return day >= 1 && day <= rewardsCents.length ? rewardsCents[day - 1] : 0;
	}

	public long breakMs() {
		return streakBreakHours * DailyLogic.HOUR_MS;
	}

	public int keysFor(int day) {
		return day == DailyLogic.DAYS && dev.smpeconomy.core.CrateKeys.enabled() ? day7Keys : 0; // keys need the crates module
	}

	/** Throws on invalid values, so a reload keeps the old config. */
	public static void validate(DailyConfig c) {
		if (c.rewardsCents == null || c.rewardsCents.length != DailyLogic.DAYS) {
			throw new IllegalArgumentException("rewardsCents needs exactly " + DailyLogic.DAYS + " values");
		}
		long prev = 0;
		boolean any = false;
		for (int i = 0; i < c.rewardsCents.length; i++) {
			long v = c.rewardsCents[i];
			if (v < 0 || v > Money.MAX) throw new IllegalArgumentException("rewardsCents[" + i + "] invalid: " + v);
			if (v < prev) throw new IllegalArgumentException("rewardsCents must not decrease (day " + (i + 1) + ")");
			prev = v;
			any |= v > 0;
		}
		if (c.day7KeyTier == null || !CrateKeys.TIERS.contains(c.day7KeyTier)) {
			throw new IllegalArgumentException("day7KeyTier must be one of " + CrateKeys.TIERS + ": " + c.day7KeyTier);
		}
		if (c.day7Keys < 0 || c.day7Keys > 64) throw new IllegalArgumentException("day7Keys must be 0–64");
		if (!any && c.day7Keys == 0) throw new IllegalArgumentException("At least one reward must be > 0");
		// below 25 h, claiming at a later time on consecutive days would lose the streak
		if (c.streakBreakHours < 25 || c.streakBreakHours > 720) throw new IllegalArgumentException("streakBreakHours must be 25–720");
		if (c.reminderDelaySeconds < 0 || c.reminderDelaySeconds > 600) throw new IllegalArgumentException("reminderDelaySeconds must be 0–600");
	}
}
