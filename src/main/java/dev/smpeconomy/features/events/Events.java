package dev.smpeconomy.features.events;

import java.util.Optional;
import net.minecraft.world.item.Item;

/**
 * Public API: current factors without player context, default 1.0. Other features use core {@code Hooks}
 * (sell, jobs, minigames) or {@code PriceGuard} instead, where these factors are already included through the
 * hook ids below. Lucky Hour also sets {@link #FLAG_LUCKY_HOUR} ({@code Hooks.flag(...)}) so minigames starts
 * chat games twice as often.
 */
public final class Events {
	public static final String HOOK_MARKET_DAY = "events.marketday";
	public static final String HOOK_GOLD_RUSH = "events.goldrush";
	public static final String HOOK_LUCKY_HOUR = "events.luckyhour";
	/** Set while Lucky Hour runs. */
	public static final String FLAG_LUCKY_HOUR = "events.lucky_hour";

	private Events() {}

	/** 1.0 without Market Day or for items outside the category. */
	public static double sellMultiplier(Item item) {
		return EventsFeature.EVENTS.sellFactor(item, now());
	}

	public static double jobMultiplier(String jobId) {
		return EventsFeature.EVENTS.jobFactor(jobId, now());
	}

	public static double chatGameMultiplier() {
		return EventsFeature.EVENTS.chatFactor(now());
	}

	public static boolean luckyHour() {
		return active().map(a -> a.type() == EventType.LUCKY_HOUR).orElse(false);
	}

	public static Optional<EventManager.Active> active() {
		return EventsFeature.EVENTS.active(now());
	}

	static long now() {
		return System.currentTimeMillis();
	}
}
