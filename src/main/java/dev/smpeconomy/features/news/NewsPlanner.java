package dev.smpeconomy.features.news;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Random;
import dev.smpeconomy.core.TimeUtil;

/**
 * Schedule of automatic events, pure logic with an injected clock so selftests can simulate weeks.
 *
 * <p>Counts online time ({@link NewsData#progressMs}), only while enough players are past the wait and outside quiet hours. When
 * it reaches the drawn interval ({@link NewsData#targetMs}), an event starts and a new interval is drawn. If {@code maxActive}
 * events run, the due one waits until one ends, and a market shock then only takes free slots ({@link Result#slots}). Counter and
 * interval live in news.json: a restart creates no extra event and time without players never counts, so someone joining after
 * hours does not get a caught-up event.
 */
final class NewsPlanner {
	record Rules(long minIntervalMs, long maxIntervalMs, int quietFrom, int quietTo, long graceMs, int minPlayers, int maxActive, ZoneId zone) {
		static Rules of(NewsConfig c, ZoneId zone) {
			return new Rules(c.minIntervalMinutes * 60_000L, c.maxIntervalMinutes * 60_000L, c.quietFromHour, c.quietToHour,
				c.onlineGraceMinutes * 60_000L, c.minPlayersOnline, c.maxActive, zone);
		}
	}

	/** {@code fire}: start an event now with at most {@code slots} entries. {@code changed}: data changed, save it. */
	record Result(boolean fire, boolean changed, int slots) {
		static final Result NOTHING = new Result(false, false, 0);
	}

	private NewsPlanner() {}

	/** Whether {@code now} is in quiet hours [quietFrom, quietTo), hour in the rules' zone; from = to means never. */
	static boolean isQuiet(long now, Rules r) {
		return TimeUtil.isHourBetween(hourOf(now, r), r.quietFrom(), r.quietTo());
	}

	static int hourOf(long ms, Rules r) {
		return Instant.ofEpochMilli(ms).atZone(r.zone()).getHour();
	}

	/**
	 * One step. {@code stepMs}: time since the last step, limited by the caller (e.g. after lag). {@code playersReady}: enough
	 * players online past the wait. {@code running}: running events in main phase. On {@code fire} the planner has already reset
	 * the counter; the caller starts the event.
	 */
	static Result tick(NewsData d, long now, long stepMs, boolean playersReady, int running, Rules r, Random random) {
		if (!playersReady || isQuiet(now, r)) return Result.NOTHING;
		boolean changed = false;
		// None yet, or the schedule changed through /smpeco reload: redraw, effective immediately
		if (d.targetMs <= 0 || d.targetMs > Math.max(1_000, r.maxIntervalMs()) || d.targetMs < r.minIntervalMs()) {
			d.targetMs = drawInterval(r, random);
			changed = true;
		}
		if (d.progressMs < d.targetMs && stepMs > 0) {
			d.progressMs = Math.min(d.targetMs, d.progressMs + stepMs);
			changed = true;
		}
		if (d.progressMs < d.targetMs) return new Result(false, changed, 0);
		int free = r.maxActive() - Math.max(0, running);
		if (free <= 0) return new Result(false, changed, 0);
		d.progressMs = 0;
		d.targetMs = drawInterval(r, random);
		d.autoStarted++;
		return new Result(true, true, free);
	}

	static long drawInterval(Rules r, Random random) {
		long lo = Math.max(1_000, r.minIntervalMs());
		long hi = Math.max(lo, r.maxIntervalMs());
		long v = lo + (long) (random.nextDouble() * (hi - lo + 1));
		return Math.min(hi, v) / 1000 * 1000;
	}

	/** Online time left until the next automatic event (0 = due, -1 = no interval drawn yet). */
	static long remaining(NewsData d) {
		return d.targetMs <= 0 ? -1 : Math.max(0, d.targetMs - d.progressMs);
	}
}
