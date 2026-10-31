package dev.smpeconomy.features.clans;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Fixed color list for clan tags. {@link #id} is the stored value (clans.json and config) and the name
 * players type; ids must fit Brigadier word().
 */
public enum ClanColor {
	YELLOW("yellow", "Yellow", "§e", Items.YELLOW_BANNER),
	GOLD("gold", "Gold", "§6", Items.ORANGE_BANNER),
	RED("red", "Red", "§c", Items.RED_BANNER),
	DARK_RED("dark_red", "Dark Red", "§4", Items.RED_WOOL),
	GREEN("green", "Green", "§a", Items.LIME_BANNER),
	DARK_GREEN("dark_green", "Dark Green", "§2", Items.GREEN_BANNER),
	AQUA("aqua", "Aqua", "§b", Items.LIGHT_BLUE_BANNER),
	TURQUOISE("turquoise", "Turquoise", "§3", Items.CYAN_BANNER),
	BLUE("blue", "Blue", "§9", Items.BLUE_BANNER),
	DARK_BLUE("dark_blue", "Dark Blue", "§1", Items.BLUE_WOOL),
	PINK("pink", "Pink", "§d", Items.PINK_BANNER),
	PURPLE("purple", "Purple", "§5", Items.PURPLE_BANNER),
	WHITE("white", "White", "§f", Items.WHITE_BANNER),
	GRAY("gray", "Gray", "§7", Items.LIGHT_GRAY_BANNER);

	public static final ClanColor DEFAULT = YELLOW;

	public final String id;
	public final String label;
	public final String code;
	public final Item banner;

	ClanColor(String id, String label, String code, Item banner) {
		this.id = id;
		this.label = label;
		this.code = code;
		this.banner = banner;
	}

	/** By id, case-insensitive; empty if unknown. */
	public static Optional<ClanColor> byId(String id) {
		if (id == null) return Optional.empty();
		String key = id.trim().toLowerCase(Locale.ROOT);
		for (ClanColor c : values()) {
			if (c.id.equals(key)) return Optional.of(c);
		}
		return Optional.empty();
	}

	public static ClanColor orDefault(String id) {
		return byId(id).orElse(DEFAULT);
	}

	public static List<String> ids() {
		return Arrays.stream(values()).map(c -> c.id).toList();
	}
}
