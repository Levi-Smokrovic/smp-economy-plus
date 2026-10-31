package dev.smpeconomy.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.world.item.Item;
import dev.smpeconomy.SmpEconomy;

/**
 * Per-item global price factor (default 1.0), e.g. from breaking news. The same factor applies to /sell, NPC market
 * value and NPC order prices, and as {@link #buySide(Item)}, where a crash counts as 1.0, to the shop price. Features
 * read it through {@link PriceGuard}; market multiplies its value by {@link #get(Item)}.
 *
 * <p>Each entry belongs to a {@code source} and transitions smoothly: fade from the current value to the target over
 * {@code fadeInMs}, after {@code durationMs} fade back to 1.0 over {@code fadeOutMs}, then delete. Several sources
 * multiply, clamped to [{@link #MIN_FACTOR}, {@link #MAX_FACTOR}]. Times are wall clock, and entries persist in
 * {@code price_modifiers.json} across restarts.
 */
public final class PriceModifiers {
	public static final double MIN_FACTOR = 0.05;
	public static final double MAX_FACTOR = 20.0;

	public static final class Entry {
		public String source = "";
		public String item = "";
		public String label = "";
		public double from = 1.0;
		public double to = 1.0;
		public long startMs;
		public long fadeInMs;
		/** 0 means unlimited, until {@link #clear}. */
		public long endMs;
		public long fadeOutMs;

		public double valueAt(long now) {
			double v = to;
			if (fadeInMs > 0 && now < startMs + fadeInMs) v = lerp(from, to, (double) (now - startMs) / fadeInMs);
			if (endMs > 0 && now >= endMs) {
				if (fadeOutMs <= 0 || now >= endMs + fadeOutMs) return 1.0;
				double atEnd = fadeInMs > 0 && endMs < startMs + fadeInMs ? lerp(from, to, (double) (endMs - startMs) / fadeInMs) : to;
				v = lerp(atEnd, 1.0, (double) (now - endMs) / fadeOutMs);
			}
			return v;
		}

		public boolean finishedAt(long now) {
			return endMs > 0 && now >= endMs + Math.max(0, fadeOutMs);
		}
	}

	public record Active(String source, Item item, String label, double current, double target, long endsAtMs) {}

	static final class Data {
		List<Entry> entries = new ArrayList<>();
	}

	private static final DataStore<Data> STORE = new DataStore<>("price_modifiers.json", Data.class, Data::new);
	private static final Map<Item, List<Entry>> INDEX = new HashMap<>();
	private static final List<Runnable> LISTENERS = new ArrayList<>();

	static {
		STORE.onLoad(data -> {
			if (data.entries == null) data.entries = new ArrayList<>();
			data.entries.removeIf(e -> e == null || e.source == null || e.item == null || Items.byId(e.item).isEmpty()
				|| !(e.from > 0) || !(e.to > 0) || Double.isInfinite(e.from) || Double.isInfinite(e.to));
			data.entries.forEach(e -> {
				if (e.label == null) e.label = e.source;
			});
			rebuildIndex();
		});
	}

	private PriceModifiers() {}

	public static void bootstrap() {
	}

	public static double get(Item item) {
		return get(item, System.currentTimeMillis());
	}

	public static double get(Item item, long now) {
		List<Entry> list = INDEX.get(item);
		if (list == null || list.isEmpty()) return 1.0;
		double product = 1.0;
		for (Entry e : list) product *= e.valueAt(now);
		return clamp(product);
	}

	/**
	 * Product of all sources, each source below 1.0 (crash, also while fading) counting as 1.0. A crash never lowers
	 * what the system charges, while another source's hype still applies fully.
	 */
	public static double buySide(Item item) {
		return buySide(item, System.currentTimeMillis());
	}

	public static double buySide(Item item, long now) {
		List<Entry> list = INDEX.get(item);
		if (list == null || list.isEmpty()) return 1.0;
		double product = 1.0;
		for (Entry e : list) {
			double v = e.valueAt(now);
			if (v > 1.0) product *= v;
		}
		return Double.isNaN(product) ? 1.0 : Math.max(1.0, Math.min(MAX_FACTOR, product));
	}

	public static double get(String source, Item item) {
		return find(source, item).map(e -> clamp(e.valueAt(System.currentTimeMillis()))).orElse(1.0);
	}

	public static List<Active> active() {
		long now = System.currentTimeMillis();
		List<Active> list = new ArrayList<>();
		for (Entry e : STORE.get().entries) {
			Optional<Item> item = Items.byId(e.item);
			if (item.isEmpty() || e.finishedAt(now)) continue;
			list.add(new Active(e.source, item.get(), e.label, clamp(e.valueAt(now)), e.to, e.endMs));
		}
		return list;
	}

	public static List<Active> active(Item item) {
		List<Active> list = new ArrayList<>();
		for (Active a : active()) {
			if (a.item() == item) list.add(a);
		}
		return list;
	}

	/**
	 * The transition starts at this source's current value.
	 *
	 * @param durationMs time from now until fade-out starts; 0 or less is unlimited, until {@link #clear}
	 */
	public static void set(String source, Item item, double target, long fadeInMs, long durationMs, long fadeOutMs, String label) {
		if (source == null || source.isBlank()) throw new IllegalArgumentException("source missing");
		if (!(target > 0) || Double.isInfinite(target)) throw new IllegalArgumentException("Invalid factor " + target);
		long now = System.currentTimeMillis();
		double current = find(source, item).map(e -> e.valueAt(now)).orElse(1.0);
		String id = Items.id(item);
		STORE.get().entries.removeIf(old -> old.source.equals(source) && old.item.equals(id));
		Entry e = new Entry();
		e.source = source;
		e.item = Items.id(item);
		e.label = label == null ? source : label;
		e.from = current;
		e.to = clamp(target);
		e.startMs = now;
		e.fadeInMs = Math.max(0, fadeInMs);
		e.endMs = durationMs > 0 ? now + durationMs : 0;
		e.fadeOutMs = Math.max(0, fadeOutMs);
		STORE.get().entries.add(e);
		changed();
	}

	public static void clear(String source, Item item, long fadeOutMs) {
		find(source, item).ifPresent(e -> fadeOut(e, fadeOutMs));
		changed();
	}

	public static void clearSource(String source, long fadeOutMs) {
		for (Entry e : List.copyOf(STORE.get().entries)) {
			if (e.source.equals(source)) fadeOut(e, fadeOutMs);
		}
		changed();
	}

	public static void remove(String source, Item item) {
		String id = Items.id(item);
		if (STORE.get().entries.removeIf(e -> e.source.equals(source) && e.item.equals(id))) changed();
	}

	public static void removeSource(String source) {
		if (STORE.get().entries.removeIf(e -> e.source.equals(source))) changed();
	}

	public static void addListener(Runnable listener) {
		LISTENERS.add(listener);
	}

	public static void tick() {
		long now = System.currentTimeMillis();
		if (STORE.get().entries.removeIf(e -> e.finishedAt(now))) changed();
	}

	private static void fadeOut(Entry e, long fadeOutMs) {
		long now = System.currentTimeMillis();
		double current = e.valueAt(now);
		e.from = current;
		e.to = current;
		e.startMs = now;
		e.fadeInMs = 0;
		e.endMs = now;
		e.fadeOutMs = Math.max(0, fadeOutMs);
	}

	private static Optional<Entry> find(String source, Item item) {
		String id = Items.id(item);
		for (Entry e : STORE.get().entries) {
			if (e.source.equals(source) && e.item.equals(id)) return Optional.of(e);
		}
		return Optional.empty();
	}

	private static void changed() {
		STORE.markDirty();
		rebuildIndex();
		for (Runnable l : List.copyOf(LISTENERS)) {
			try {
				l.run();
			} catch (RuntimeException ex) {
				SmpEconomy.LOG.error("[SMPEco] PriceModifiers listener failed", ex);
			}
		}
	}

	private static void rebuildIndex() {
		INDEX.clear();
		for (Entry e : STORE.get().entries) {
			Items.byId(e.item).ifPresent(item -> INDEX.computeIfAbsent(item, k -> new ArrayList<>()).add(e));
		}
		INDEX.replaceAll((k, v) -> Collections.unmodifiableList(v));
	}

	static double clamp(double v) {
		if (Double.isNaN(v)) return 1.0;
		return Math.max(MIN_FACTOR, Math.min(MAX_FACTOR, v));
	}

	static double lerp(double a, double b, double t) {
		double x = Math.max(0, Math.min(1, t));
		double s = x * x * (3 - 2 * x);
		return a + (b - a) * s;
	}
}
