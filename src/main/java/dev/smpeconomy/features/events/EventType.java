package dev.smpeconomy.features.events;

import java.util.Optional;
import net.minecraft.world.BossEvent;
import net.minecraft.world.item.Item;

/**
 * Bossbar colors avoid red and green on purpose: those belong to the breaking news bar, so the two bars stay
 * distinguishable at a glance.
 */
public enum EventType {
	/** One category sells higher through /sell (param = category id). */
	MARKET_DAY("marketday", "Market Day", "The", "§b", BossEvent.BossBarColor.BLUE, net.minecraft.world.item.Items.EMERALD),
	/** Payouts for one job ×2 (param = job id). */
	GOLD_RUSH("goldrush", "Gold Rush", "The", "§e", BossEvent.BossBarColor.YELLOW, net.minecraft.world.item.Items.GOLD_INGOT),
	/** Chat games twice as often with double prizes (no param). */
	LUCKY_HOUR("luckyhour", "Lucky Hour", "The", "§d", BossEvent.BossBarColor.PURPLE, net.minecraft.world.item.Items.RABBIT_FOOT);

	private final String id;
	private final String label;
	private final String article;
	private final String color;
	private final BossEvent.BossBarColor barColor;
	private final Item icon;

	EventType(String id, String label, String article, String color, BossEvent.BossBarColor barColor, Item icon) {
		this.id = id;
		this.label = label;
		this.article = article;
		this.color = color;
		this.barColor = barColor;
		this.icon = icon;
	}

	public String id() {
		return id;
	}

	public String label() {
		return label;
	}

	/** Definite article, capitalized ("The"). */
	public String article() {
		return article;
	}

	public String color() {
		return color;
	}

	public BossEvent.BossBarColor barColor() {
		return barColor;
	}

	public Item icon() {
		return icon;
	}

	public static Optional<EventType> byId(String id) {
		if (id == null) return Optional.empty();
		String n = EventsConfig.normalize(id);
		for (EventType t : values()) {
			if (t.id.equals(n) || EventsConfig.normalize(t.label).equals(n)) return Optional.of(t);
		}
		return Optional.empty();
	}
}
