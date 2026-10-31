package dev.smpeconomy.features.daily;

import java.time.LocalDate;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.TimeUtil;

/**
 * Streak logic without Minecraft or storage. All times are explicit epoch ms, so any clock can be tested. Claims
 * are per calendar day in the server time zone (DST-safe via {@link TimeUtil#dateOf}). A streak breaks after more
 * than {@code breakMs} since the last claim; exactly {@code breakMs} still holds. Days 1-7 cycle: streak 8 is day 1
 * again while the streak counter keeps running.
 */
public final class DailyLogic {
	public static final int DAYS = 7;
	public static final long HOUR_MS = 3_600_000L;

	private DailyLogic() {}

	public static int cycleDay(int streak) {
		return streak <= 0 ? 0 : (streak - 1) % DAYS + 1;
	}

	public enum DayState {
		CLAIMED,
		AVAILABLE,
		TOMORROW,
		LOCKED
	}

	/**
	 * @param streak valid streak now, 0 if broken or never claimed
	 * @param claimedInCycle days claimed this week (0-7)
	 * @param nextClaimAtMs when claimable again ({@code now} if {@code claimable})
	 * @param streakDeadlineMs claim by this time to keep the streak, 0 without a running streak
	 */
	public record Status(boolean claimable, boolean broken, boolean hadStreak, int streak, int nextStreak, int nextDay,
		int claimedInCycle, long nextClaimAtMs, long streakDeadlineMs) {

		public DayState dayState(int day) {
			if (day <= claimedInCycle) return DayState.CLAIMED;
			if (day == nextDay) return claimable ? DayState.AVAILABLE : DayState.TOMORROW;
			return DayState.LOCKED;
		}
	}

	public static Status status(DailyState s, long now, long breakMs) {
		if (s == null || s.lastClaimMs <= 0) return new Status(true, false, false, 0, 1, 1, 0, now, 0);
		long last = s.lastClaimMs;
		LocalDate today = TimeUtil.dateOf(now);
		LocalDate lastDate = TimeUtil.dateOf(last);
		// last claim in the future (clock set back): today is not after lastDate, so not claimable
		boolean claimable = today.isAfter(lastDate);
		// Deadline: breakMs after the last claim, but never before the end of the next calendar day. Otherwise the
		// 25-hour DST day (00:10 to 23:50 the day after next is 48 h 40 min) would break a streak on consecutive
		// days.
		long calendarFloor = lastDate.plusDays(2).atStartOfDay(TimeUtil.zone()).toInstant().toEpochMilli() - 1;
		long deadlineMs = Math.max(Money.saturatedAdd(last, breakMs), calendarFloor);
		boolean broken = s.streak <= 0 || now > deadlineMs;
		int streak = broken ? 0 : s.streak;
		int nextStreak = broken ? 1 : s.streak == Integer.MAX_VALUE ? s.streak : s.streak + 1;
		int nextDay = cycleDay(nextStreak);
		int claimedInCycle;
		if (broken) {
			claimedInCycle = 0;
		} else if (claimable) {
			claimedInCycle = nextDay - 1;
		} else {
			claimedInCycle = cycleDay(s.streak);
			// claimed today: after day 7 a new week starts tomorrow, until then all 7 stay ticked
		}
		long nextClaimAt = claimable ? now : lastDate.plusDays(1).atStartOfDay(TimeUtil.zone()).toInstant().toEpochMilli();
		long deadline = broken ? 0 : deadlineMs;
		return new Status(claimable, broken, true, streak, nextStreak, nextDay, claimedInCycle, nextClaimAt, deadline);
	}

	/** Applies a claim to a copy of the state. Only valid if {@code status.claimable()}. */
	public static DailyState applyClaim(DailyState s, Status status, long now, long money) {
		DailyState n = s == null ? new DailyState() : s.copy();
		n.lastClaimMs = now;
		n.streak = status.nextStreak();
		n.bestStreak = Math.max(n.bestStreak, n.streak);
		n.totalClaims = n.totalClaims == Long.MAX_VALUE ? n.totalClaims : n.totalClaims + 1;
		n.totalEarned = Money.saturatedAdd(n.totalEarned, Math.max(0, money));
		return n;
	}
}
