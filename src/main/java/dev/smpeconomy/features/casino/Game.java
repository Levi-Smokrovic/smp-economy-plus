package dev.smpeconomy.features.casino;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public enum Game {
	COINFLIP("coinflip", "Coinflip", "§6", Items.SUNFLOWER),
	SLOTS("slots", "Slots", "§d", Items.DIAMOND),
	MINES("mines", "Mines", "§c", Items.TNT),
	HILO("hilo", "Higher/Lower", "§b", Items.PAPER);

	private final String id;
	private final String label;
	private final String color;
	private final Item icon;

	Game(String id, String label, String color, Item icon) {
		this.id = id;
		this.label = label;
		this.color = color;
		this.icon = icon;
	}

	public String id() {
		return id;
	}

	public String label() {
		return label;
	}

	/** Coloured name, e.g. {@code "§dSlots"}. */
	public String colored() {
		return color + label;
	}

	public Item icon() {
		return icon;
	}

	public static Game byId(String id) {
		if (id == null) return null;
		for (Game g : values()) {
			if (g.id.equals(id)) return g;
		}
		return null;
	}
}
