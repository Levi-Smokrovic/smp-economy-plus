package dev.smpeconomy.features.holo;

import java.util.Locale;
import java.util.Optional;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Leaderboard types. {@link #id} is the command argument and the value in {@code holos.json}.
 */
public enum HoloType {
	MONEY("money", "Money", "§6§l★ Richest Players ★", "§a", Items.GOLD_INGOT),
	KILLS("kills", "Kills", "§c§l★ Most Kills ★", "§c", Items.IRON_SWORD),
	PLAYTIME("playtime", "Playtime", "§b§l★ Most Playtime ★", "§b", Items.CLOCK),
	JOBS("jobs", "Jobs", "§e§l★ Top Workers ★", "§e", Items.IRON_PICKAXE);

	public final String id;
	public final String label;
	public final String title;
	public final String valueColor;
	public final Item icon;

	HoloType(String id, String label, String title, String valueColor, Item icon) {
		this.id = id;
		this.label = label;
		this.title = title;
		this.valueColor = valueColor;
		this.icon = icon;
	}

	/** Case and surrounding whitespace are ignored. */
	public static Optional<HoloType> byId(String id) {
		if (id == null) return Optional.empty();
		String key = id.trim().toLowerCase(Locale.ROOT);
		for (HoloType t : values()) {
			if (t.id.equals(key)) return Optional.of(t);
		}
		return Optional.empty();
	}

	public static String idList() {
		StringBuilder sb = new StringBuilder();
		for (HoloType t : values()) {
			if (!sb.isEmpty()) sb.append('|');
			sb.append(t.id);
		}
		return sb.toString();
	}
}
