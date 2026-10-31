package dev.smpeconomy.features.missions;

import java.util.Locale;
import java.util.Optional;
import dev.smpeconomy.core.CrateKeys;

/**
 * The four tiers of the mission board, in board row order. Values here are only code defaults; the effective settings
 * (offers, time limit, keys) come from {@code missions.json}, "tiers".
 */
public enum Tier {
	EASY("easy", "Easy", "§a", "minecraft:lime_dye", 4, 120, "", 0),
	MEDIUM("medium", "Medium", "§e", "minecraft:yellow_dye", 3, 360, "", 0),
	HARD("hard", "Hard", "§6", "minecraft:orange_dye", 3, 1440, CrateKeys.RARE, 1),
	ELITE("elite", "Elite", "§d", "minecraft:magenta_dye", 2, 2880, CrateKeys.EPIC, 1);

	public final String id;
	final String defaultLabel;
	final String defaultColor;
	final String defaultIcon;
	final int defaultOffers;
	final int defaultTimeLimitMinutes;
	final String defaultKeyTier;
	final int defaultKeys;

	Tier(String id, String label, String color, String icon, int offers, int timeLimitMinutes, String keyTier, int keys) {
		this.id = id;
		this.defaultLabel = label;
		this.defaultColor = color;
		this.defaultIcon = icon;
		this.defaultOffers = offers;
		this.defaultTimeLimitMinutes = timeLimitMinutes;
		this.defaultKeyTier = keyTier;
		this.defaultKeys = keys;
	}

	public static Optional<Tier> byId(String id) {
		if (id == null) return Optional.empty();
		String key = id.trim().toLowerCase(Locale.ROOT);
		for (Tier t : values()) {
			if (t.id.equals(key)) return Optional.of(t);
		}
		return Optional.empty();
	}

	/** Config filled in with code defaults. */
	public MissionConfig.TierSettings settings() {
		return MissionConfig.tier(this);
	}

	public String coloredLabel() {
		MissionConfig.TierSettings s = settings();
		return s.color + "§l" + s.label;
	}
}
