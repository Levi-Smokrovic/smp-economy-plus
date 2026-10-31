package dev.smpeconomy.features.afk;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Time logic of the AFK zone: one "continuously in the zone" session per player, with {@link #step} called once per
 * second with the server position. At most one reward per interval. Real time must also have passed ({@code
 * interval} s minus {@link #CLOCK_TOLERANCE_MS}) so {@code /tick sprint} can't speed up rewards. Leaving the zone
 * or a new player object (reconnect, respawn) restarts the session.
 */
final class AfkTracker {
	/** Slack for tick jitter in the real-time check. */
	static final long CLOCK_TOLERANCE_MS = 500;

	enum Kind {
		OUTSIDE,
		ENTERED,
		LEFT,
		PROGRESS,
		REWARD,
		/** Set only by the service: in the zone, daily limit reached, timer stopped. */
		LIMIT,
		/**
		 * Set only by the service: in the zone, but enough other accounts on this IP are collecting; timer stopped.
		 */
		IP_LIMIT
	}

	record Step(Kind kind, int seconds, int remaining) {}

	private static final class Session {
		final Object identity;
		int seconds;
		long periodStartMs;

		Session(Object identity, long now) {
			this.identity = identity;
			this.periodStartMs = now;
		}
	}

	private final Map<UUID, Session> sessions = new HashMap<>();
	private Object zoneKey;

	/**
	 * {@code identity} is the player object: when it changes (reconnect, respawn) the session restarts. {@code
	 * nowMs} is a monotonic clock.
	 */
	Step step(UUID id, Object identity, boolean inside, int intervalSeconds, long nowMs) {
		int interval = Math.max(1, intervalSeconds);
		Session s = sessions.get(id);
		if (!inside) {
			if (s == null) return new Step(Kind.OUTSIDE, 0, interval);
			sessions.remove(id);
			return new Step(Kind.LEFT, 0, interval);
		}
		if (s == null || s.identity != identity) {
			sessions.put(id, new Session(identity, nowMs));
			return new Step(Kind.ENTERED, 0, interval);
		}
		if (s.seconds < interval) s.seconds++;
		if (s.seconds >= interval && nowMs - s.periodStartMs >= interval * 1000L - CLOCK_TOLERANCE_MS) {
			s.seconds = 0;
			s.periodStartMs = nowMs;
			return new Step(Kind.REWARD, 0, interval);
		}
		return new Step(Kind.PROGRESS, s.seconds, Math.max(0, interval - s.seconds));
	}

	/** -1 means no session. */
	int seconds(UUID id) {
		Session s = sessions.get(id);
		return s == null ? -1 : s.seconds;
	}

	/**
	 * Like {@link #seconds(UUID)} but only for this player object: a stale session from before a reconnect or
	 * respawn returns -1.
	 */
	int seconds(UUID id, Object identity) {
		Session s = sessions.get(id);
		return s == null || s.identity != identity ? -1 : s.seconds;
	}

	boolean has(UUID id) {
		return sessions.containsKey(id);
	}

	Set<UUID> ids() {
		return Set.copyOf(sessions.keySet());
	}

	void remove(UUID id) {
		sessions.remove(id);
	}

	void retainOnly(Set<UUID> online) {
		sessions.keySet().retainAll(online);
	}

	void clear() {
		sessions.clear();
	}

	void reset() {
		sessions.clear();
		zoneKey = null;
	}

	/**
	 * Drops all sessions if the zone changed (set, moved, deleted, reloaded) and returns their ids; empty if
	 * unchanged.
	 */
	Set<UUID> syncZone(Object key) {
		if (Objects.equals(zoneKey, key)) return Set.of();
		zoneKey = key;
		Set<UUID> dropped = ids();
		sessions.clear();
		return dropped;
	}
}
