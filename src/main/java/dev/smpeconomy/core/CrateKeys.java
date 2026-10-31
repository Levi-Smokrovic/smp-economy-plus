package dev.smpeconomy.core;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Virtual crate keys: counters in {@link PlayerDataService}, not items, so they cannot be duplicated. Features granting
 * or redeeming keys use these constants and {@link #give}/{@link #take}, never their own strings.
 *
 * <p>Keys only exist while the {@code crates} module is on. {@link #give} is otherwise a no-op, and granting features
 * drop the key part of their reward text ({@link #enabled()}).
 */
public final class CrateKeys {
	public static final String PREFIX = "crate_keys.";
	public static final String COMMON = "common";
	public static final String RARE = "rare";
	public static final String EPIC = "epic";
	public static final List<String> TIERS = List.of(COMMON, RARE, EPIC);

	private static final Pattern TIER = Pattern.compile("[a-z0-9_]{1,32}");

	/** Selftests only: forces {@link #enabled()}; null means the real module state. */
	private static volatile Boolean testOverride;

	private CrateKeys() {}

	public static boolean enabled() {
		Boolean o = testOverride;
		return o != null ? o : dev.smpeconomy.core.module.Modules.isEnabled("crates");
	}

	/** The returned Runnable restores the previous state. */
	public static Runnable overrideEnabledForTest(Boolean on) {
		Boolean previous = testOverride;
		testOverride = on;
		return () -> testOverride = previous;
	}

	/** The tier must be {@code [a-z0-9_]}. */
	public static String key(String tier) {
		if (tier == null || !TIER.matcher(tier).matches()) throw new IllegalArgumentException("Invalid crate: " + tier);
		return PREFIX + tier;
	}

	public static String label(String tier) {
		return switch (tier) {
			case COMMON -> "Common";
			case RARE -> "Rare";
			case EPIC -> "Epic";
			default -> tier;
		};
	}

	public static long count(UUID player, String tier) {
		return PlayerDataService.getCounter(player, key(tier));
	}

	/** Does nothing if {@code crates} is off and returns the unchanged count. */
	public static long give(UUID player, String tier, long amount) {
		if (!enabled()) return count(player, tier);
		return PlayerDataService.addCounter(player, key(tier), amount);
	}

	/** Takes nothing and returns false if there are not enough. */
	public static boolean take(UUID player, String tier, long amount) {
		return PlayerDataService.takeCounter(player, key(tier), amount);
	}
}
