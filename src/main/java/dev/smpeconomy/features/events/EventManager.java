package dev.smpeconomy.features.events;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.function.Supplier;
import net.minecraft.world.item.Item;
import dev.smpeconomy.core.TimeUtil;

/**
 * Random event logic on state with explicit time, so selftests can instantiate it. At most one event at a
 * time. Factors apply only while {@code now < endsAt}, even if a tick has not processed the end yet. Display
 * belongs in {@link EventAnnouncer}; this only changes state.
 */
public final class EventManager {
	public static final int HISTORY = 10;
	/** Only warn "ending in 1 minute" for events longer than that. */
	public static final long WARN_BEFORE_END_MS = 60_000;

	/** Persisted in {@code <world>/smpeconomy/events.json}. */
	public static final class Data {
		public String type;
		/** Category or job id (Market Day, Gold Rush), otherwise null. */
		public String param;
		public long startedAt;
		public long endsAt;
		/** "auto" or the admin's name. */
		public String startedBy;
		public boolean warned;
		/** Epoch ms; 0 = not planned yet. */
		public long nextEventAt;
		public List<Past> history = new ArrayList<>();

		void fixNulls() {
			if (history == null) history = new ArrayList<>();
			history.removeIf(p -> p == null || p.type == null);
		}
	}

	public static final class Past {
		public String type;
		public String param;
		public long startedAt;
		public long endedAt;
		public String startedBy;
	}

	public record Active(EventType type, String param, long startedAt, long endsAt, String startedBy) {
		public long remaining(long now) {
			return Math.max(0, endsAt - now);
		}

		public long duration() {
			return Math.max(1, endsAt - startedAt);
		}

		/** 1.0 = just started, 0.0 = over. */
		public float progress(long now) {
			return (float) Math.max(0.0, Math.min(1.0, remaining(now) / (double) duration()));
		}
	}

	public record TickResult(Active ended, Active started, boolean warnEnding) {
		static final TickResult NONE = new TickResult(null, null, false);
	}

	private final Supplier<Data> data;
	private final Runnable markDirty;
	private final Supplier<EventsConfig> config;
	private final Random random;

	public EventManager(Supplier<Data> data, Runnable markDirty, Supplier<EventsConfig> config, Random random) {
		this.data = data;
		this.markDirty = markDirty;
		this.config = config;
		this.random = random;
	}

	/** Only if not expired yet. */
	public Optional<Active> active(long now) {
		Data d = data.get();
		if (d.type == null || now >= d.endsAt) return Optional.empty();
		Optional<EventType> type = EventType.byId(d.type);
		return type.map(t -> new Active(t, d.param, d.startedAt, d.endsAt, d.startedBy));
	}

	public double sellFactor(Item item, long now) {
		Optional<Active> a = active(now);
		if (a.isEmpty() || a.get().type() != EventType.MARKET_DAY || item == null) return 1.0;
		EventsConfig cfg = config.get();
		return cfg.categoryItems(a.get().param()).contains(item) ? cfg.marketDayMultiplier : 1.0;
	}

	public double jobFactor(String jobId, long now) {
		Optional<Active> a = active(now);
		if (a.isEmpty() || a.get().type() != EventType.GOLD_RUSH) return 1.0;
		EventsConfig cfg = config.get();
		return cfg.job(a.get().param()).filter(j -> EventsConfig.jobMatches(j, jobId)).isPresent() ? cfg.goldRushMultiplier : 1.0;
	}

	public double chatFactor(long now) {
		Optional<Active> a = active(now);
		return a.isPresent() && a.get().type() == EventType.LUCKY_HOUR ? config.get().luckyHourMultiplier : 1.0;
	}

	/** 0 = not planned or event running. */
	public long nextEventAt() {
		return data.get().nextEventAt;
	}

	public List<Past> history() {
		return List.copyOf(data.get().history);
	}

	/**
	 * Replaces a running event, which goes into history. {@code param} must be a category with items for
	 * Market Day and a configured job for Gold Rush (null = random); Lucky Hour ignores it.
	 *
	 * @throws IllegalArgumentException on unknown parameter or duration <= 0
	 */
	public Active start(EventType type, String param, long durationMs, long now, String startedBy) {
		if (type == null) throw new IllegalArgumentException("Event type missing");
		if (durationMs <= 0) throw new IllegalArgumentException("Duration must be > 0");
		String p = resolveParam(type, param);
		Data d = data.get();
		if (d.type != null) archive(d, now);
		d.type = type.id();
		d.param = p;
		d.startedAt = now;
		d.endsAt = now + durationMs;
		d.startedBy = startedBy == null ? "auto" : startedBy;
		d.warned = false;
		d.nextEventAt = 0;
		markDirty.run();
		return new Active(type, p, d.startedAt, d.endsAt, d.startedBy);
	}

	/** Empty if none was running. */
	public Optional<Active> stop(long now) {
		Data d = data.get();
		Optional<Active> current = active(now);
		if (d.type == null) return Optional.empty();
		Active ended = current.orElseGet(() -> EventType.byId(d.type).map(t -> new Active(t, d.param, d.startedAt, d.endsAt, d.startedBy)).orElse(null));
		archive(d, Math.min(now, Math.max(d.startedAt, d.endsAt)));
		d.nextEventAt = now + randomIntervalMs();
		markDirty.run();
		return Optional.ofNullable(ended);
	}

	/** Admin, only while no event runs. */
	public void scheduleNext(long at) {
		data.get().nextEventAt = at;
		markDirty.run();
	}

	/**
	 * One step per second: ends expired events, warns shortly before the end, and starts due random events if
	 * enough players are online and it is not quiet time. {@code hour} is the current hour in the server
	 * timezone.
	 */
	public TickResult tick(long now, int onlinePlayers, int hour) {
		Data d = data.get();
		EventsConfig cfg = config.get();
		Active ended = null;
		boolean warn = false;
		if (d.type != null) {
			Optional<Active> a = active(now);
			if (a.isEmpty()) {
				ended = EventType.byId(d.type).map(t -> new Active(t, d.param, d.startedAt, d.endsAt, d.startedBy)).orElse(null);
				archive(d, Math.max(d.startedAt, Math.min(now, d.endsAt)));
				d.nextEventAt = now + randomIntervalMs();
				markDirty.run();
			} else if (!d.warned && a.get().remaining(now) <= WARN_BEFORE_END_MS && a.get().duration() > 2 * WARN_BEFORE_END_MS) {
				d.warned = true;
				warn = true;
				markDirty.run();
			}
		}
		Active started = null;
		if (d.type == null) {
			if (d.nextEventAt <= 0) {
				d.nextEventAt = now + randomIntervalMs();
				markDirty.run();
			} else if (ended == null && cfg.randomEvents && now >= d.nextEventAt && onlinePlayers >= cfg.minPlayersOnline
				&& !TimeUtil.isHourBetween(hour, cfg.quietFromHour, cfg.quietToHour)) {
				Optional<EventType> type = randomType();
				if (type.isEmpty()) {
					d.nextEventAt = now + randomIntervalMs();
					markDirty.run();
				} else {
					started = start(type.get(), null, randomDurationMs(), now, "auto");
				}
			}
		}
		return ended == null && started == null && !warn ? TickResult.NONE : new TickResult(ended, started, warn);
	}

	/** Weighted; only types possible right now (categories or jobs present). */
	public Optional<EventType> randomType() {
		EventsConfig cfg = config.get();
		int total = 0;
		for (EventType t : EventType.values()) if (possible(t)) total += cfg.weight(t);
		if (total <= 0) return Optional.empty();
		int r = random.nextInt(total);
		for (EventType t : EventType.values()) {
			if (!possible(t)) continue;
			r -= cfg.weight(t);
			if (r < 0) return Optional.of(t);
		}
		return Optional.empty();
	}

	public long randomDurationMs() {
		EventsConfig cfg = config.get();
		return minutesBetween(cfg.minDurationMinutes, cfg.maxDurationMinutes) * 60_000L;
	}

	public long randomIntervalMs() {
		EventsConfig cfg = config.get();
		return minutesBetween(cfg.minIntervalMinutes, cfg.maxIntervalMinutes) * 60_000L;
	}

	private long minutesBetween(int min, int max) {
		int lo = Math.max(1, Math.min(min, max));
		int hi = Math.max(lo, max);
		return lo + random.nextInt(hi - lo + 1);
	}

	private boolean possible(EventType t) {
		EventsConfig cfg = config.get();
		return switch (t) {
			case MARKET_DAY -> cfg.marketDayCategories.stream().anyMatch(c -> !cfg.categoryItems(c.id).isEmpty());
			case GOLD_RUSH -> !cfg.goldRushJobs.isEmpty();
			case LUCKY_HOUR -> true;
		};
	}

	private String resolveParam(EventType type, String param) {
		EventsConfig cfg = config.get();
		switch (type) {
			case MARKET_DAY -> {
				List<String> ids = new ArrayList<>();
				for (EventsConfig.Category c : cfg.marketDayCategories) if (!cfg.categoryItems(c.id).isEmpty()) ids.add(c.id);
				if (param == null || param.isBlank()) {
					if (ids.isEmpty()) throw new IllegalArgumentException("No Market Day categories configured");
					return ids.get(random.nextInt(ids.size()));
				}
				String n = EventsConfig.normalize(param);
				if (!ids.contains(n)) {
					if (cfg.category(n).isPresent()) throw new IllegalArgumentException("Category " + n + " has no items eligible for Market Day right now (see log)");
					throw new IllegalArgumentException("Unknown category: " + param);
				}
				return n;
			}
			case GOLD_RUSH -> {
				if (param == null || param.isBlank()) {
					if (cfg.goldRushJobs.isEmpty()) throw new IllegalArgumentException("No Gold Rush jobs configured");
					return cfg.goldRushJobs.get(random.nextInt(cfg.goldRushJobs.size())).id;
				}
				for (EventsConfig.Job j : cfg.goldRushJobs) {
					if (EventsConfig.jobMatches(j, param)) return j.id;
				}
				throw new IllegalArgumentException("Unknown job: " + param);
			}
			default -> {
				return null;
			}
		}
	}

	private void archive(Data d, long endedAt) {
		Past p = new Past();
		p.type = d.type;
		p.param = d.param;
		p.startedAt = d.startedAt;
		p.endedAt = endedAt;
		p.startedBy = d.startedBy;
		d.history.add(0, p);
		while (d.history.size() > HISTORY) d.history.remove(d.history.size() - 1);
		d.type = null;
		d.param = null;
		d.startedAt = 0;
		d.endsAt = 0;
		d.startedBy = null;
		d.warned = false;
	}
}
