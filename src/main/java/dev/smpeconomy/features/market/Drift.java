package dev.smpeconomy.features.market;

import java.util.Map;
import net.minecraft.world.item.Item;
import dev.smpeconomy.core.Items;

/**
 * Drift factors of market values. Player purchases from NPC sellers raise a factor, NPC purchases from players lower it, and
 * without trades it decays toward 1.0 with a half-life. Stored as factor plus timestamp, decay is computed on read. Keys are item
 * ids or {@code "enchant:<id>"}.
 */
public final class Drift {
	/**
	 * Decay runs in 10-minute steps so market values, and with them the floors of NPC offers, stay stable between steps. A
	 * continuously rising value would reject every purchase attempt with "price changed".
	 */
	public static final long DECAY_STEP_MS = 10 * 60_000L;
	private static int version;

	private Drift() {}

	/** Changes with every drift update; part of the market value cache key. */
	static int version() {
		return version;
	}

	public static String key(Item item) {
		return Items.id(item);
	}

	public static String enchantKey(String enchantmentId) {
		return "enchant:" + enchantmentId;
	}

	public static double current(String key, long now) {
		MarketData.DriftEntry e = MarketData.get().drift.get(key);
		if (e == null) return 1.0;
		MarketConfig c = MarketConfig.get();
		long dt = Math.max(0, now - e.at);
		return decayed(e.factor, e.at, e.at + dt - dt % DECAY_STEP_MS, c.driftHalfLifeHours * 3_600_000.0, c.driftMin, c.driftMax);
	}

	public static double current(Item item, long now) {
		return current(key(item), now);
	}

	/** Moves the factor by {@code delta} from its current decayed value, clamped to [driftMin, driftMax]. */
	public static double apply(String key, double delta, long now) {
		if (!Double.isFinite(delta) || delta == 0) return current(key, now);
		MarketConfig c = MarketConfig.get();
		double next = clamp(current(key, now) + delta, c.driftMin, c.driftMax);
		Map<String, MarketData.DriftEntry> map = MarketData.get().drift;
		if (Math.abs(next - 1.0) < 1e-6) map.remove(key);
		else map.put(key, new MarketData.DriftEntry(next, now));
		version++;
		MarketData.markDirty();
		return next;
	}

	public static void set(String key, double factor, long now) {
		MarketConfig c = MarketConfig.get();
		double v = clamp(factor, c.driftMin, c.driftMax);
		if (Math.abs(v - 1.0) < 1e-6) MarketData.get().drift.remove(key);
		else MarketData.get().drift.put(key, new MarketData.DriftEntry(v, now));
		version++;
		MarketData.markDirty();
	}

	static void prune(long now) {
		boolean changed = MarketData.get().drift.entrySet().removeIf(e -> Math.abs(current(e.getKey(), now) - 1.0) < 0.001);
		if (changed) {
			version++;
			MarketData.markDirty();
		}
	}

	/** Drift change for a trade of {@code count} items with stack size {@code maxStack}: step × stacks, at most maxStep. */
	public static double impact(int count, int maxStack, double stepPerStack, double maxStep) {
		if (count <= 0) return 0;
		double stacks = (double) count / Math.max(1, maxStack);
		return Math.min(maxStep, stepPerStack * stacks);
	}

	/** {@code 1 + (f - 1) × 0.5^(Δt / halfLife)}, clamped. */
	public static double decayed(double factor, long at, long now, double halfLifeMs, double min, double max) {
		double f = Double.isFinite(factor) ? factor : 1.0;
		long dt = Math.max(0, now - at);
		double v = halfLifeMs > 0 ? 1.0 + (f - 1.0) * Math.pow(0.5, dt / halfLifeMs) : f;
		return clamp(v, min, max);
	}

	static double clamp(double v, double min, double max) {
		if (Double.isNaN(v)) return 1.0;
		return Math.max(min, Math.min(max, v));
	}

	static void bumpVersion() {
		version++;
	}
}
