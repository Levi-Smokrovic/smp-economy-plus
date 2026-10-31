package dev.smpeconomy.core;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Time helpers in the server zone (core.json {@code timezone}, by default the host's), plus duration formatting. Plain
 * Java.
 */
public final class TimeUtil {
	public static final String SYSTEM = "system";
	private static volatile ZoneId zone = ZoneId.systemDefault();

	public static ZoneId zone() {
		return zone;
	}

	public static void setZone(ZoneId z) {
		zone = z == null ? ZoneId.systemDefault() : z;
	}

	public static ZoneId parseZone(String id) {
		if (id == null || id.isBlank() || SYSTEM.equalsIgnoreCase(id.trim())) return ZoneId.systemDefault();
		try {
			return ZoneId.of(id.trim());
		} catch (java.time.DateTimeException e) {
			throw new IllegalArgumentException("timezone: unknown time zone '" + id + "' (e.g. Europe/Berlin, America/New_York, UTC or system)");
		}
	}

	private TimeUtil() {}

	public static ZonedDateTime now() {
		return ZonedDateTime.now(zone());
	}

	public static LocalDate today() {
		return LocalDate.now(zone());
	}

	public static LocalDate dateOf(long epochMillis) {
		return Instant.ofEpochMilli(epochMillis).atZone(zone()).toLocalDate();
	}

	/** Never 0. */
	public static long millisUntilNext(LocalTime time) {
		return millisUntilNext(time, now());
	}

	public static long millisUntilNext(LocalTime time, ZonedDateTime from) {
		ZonedDateTime next = from.toLocalDate().atTime(time).atZone(zone());
		if (!next.isAfter(from)) next = from.toLocalDate().plusDays(1).atTime(time).atZone(zone());
		return Duration.between(from, next).toMillis();
	}

	public static long nextOccurrence(LocalTime time) {
		return System.currentTimeMillis() + millisUntilNext(time);
	}

	/** Wraps past midnight: {@code (22, 6)} means 22:00-05:59. */
	public static boolean isHourBetween(int fromHour, int toHour) {
		return isHourBetween(now().getHour(), fromHour, toHour);
	}

	public static boolean isHourBetween(int hour, int fromHour, int toHour) {
		if (fromHour == toHour) return false;
		return fromHour < toHour ? hour >= fromHour && hour < toHour : hour >= fromHour || hour < toHour;
	}

	/** Negative gives {@code "0s"}. */
	public static String formatDuration(long millis) {
		long s = Math.max(0, millis) / 1000;
		long d = s / 86_400;
		long h = s % 86_400 / 3600;
		long m = s % 3600 / 60;
		long sec = s % 60;
		if (d > 0) return d + "d " + h + "h";
		if (h > 0) return h + "h " + pad(m) + "m";
		if (m > 0) return m + "m " + pad(sec) + "s";
		return sec + "s";
	}

	public static String formatTicks(long ticks) {
		return formatDuration(ticks * 50);
	}

	private static String pad(long v) {
		return v < 10 ? "0" + v : Long.toString(v);
	}
}
