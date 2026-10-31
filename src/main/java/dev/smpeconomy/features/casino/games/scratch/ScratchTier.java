package dev.smpeconomy.features.casino.games.scratch;

import java.util.Locale;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** The three scratch cards with fixed prices (cents). Same prize table and payout rate for all of them. */
public enum ScratchTier {
	BRONZE("bronze", "Bronze", "§6", 10_000, Items.COPPER_BLOCK, Items.ORANGE_STAINED_GLASS_PANE),
	SILVER("silver", "Silver", "§f", 100_000, Items.IRON_BLOCK, Items.LIGHT_GRAY_STAINED_GLASS_PANE),
	GOLD("gold", "Gold", "§e", 1_000_000, Items.GOLD_BLOCK, Items.YELLOW_STAINED_GLASS_PANE);

	private final String id;
	private final String label;
	private final String color;
	private final long price;
	private final Item icon;
	private final Item frame;

	ScratchTier(String id, String label, String color, long price, Item icon, Item frame) {
		this.id = id;
		this.label = label;
		this.color = color;
		this.price = price;
		this.icon = icon;
		this.frame = frame;
	}

	public String id() {
		return id;
	}

	/** "§6Bronze Card". */
	public String colored() {
		return color + label + " Card";
	}

	public String color() {
		return color;
	}

	public long price() {
		return price;
	}

	public Item icon() {
		return icon;
	}

	/** Glass pane around the card. */
	public Item frame() {
		return frame;
	}

	public static ScratchTier byId(String id) {
		if (id == null) return null;
		String s = id.toLowerCase(Locale.ROOT);
		for (ScratchTier t : values()) {
			if (t.id.equals(s)) return t;
		}
		return null;
	}

	/** Tier of a stored round (by its price); null if no tier has that price. */
	public static ScratchTier byPrice(long price) {
		for (ScratchTier t : values()) {
			if (t.price == price) return t;
		}
		return null;
	}
}
