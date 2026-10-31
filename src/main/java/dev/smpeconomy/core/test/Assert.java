package dev.smpeconomy.core.test;

import java.util.Objects;

/** Failures throw {@link AssertionError}, which fails the test with the message. */
public final class Assert {
	private Assert() {}

	@FunctionalInterface
	public interface ThrowingRunnable {
		void run() throws Throwable;
	}

	public static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	public static void isTrue(boolean condition, String message) {
		check(condition, message);
	}

	public static void isFalse(boolean condition, String message) {
		check(!condition, message);
	}

	public static void eq(Object expected, Object actual, String message) {
		if (!Objects.equals(expected, actual)) throw new AssertionError(message + " (expected " + expected + ", was " + actual + ")");
	}

	public static void eq(long expected, long actual, String message) {
		if (expected != actual) throw new AssertionError(message + " (expected " + expected + ", was " + actual + ")");
	}

	public static void notNull(Object value, String message) {
		if (value == null) throw new AssertionError(message + " (was null)");
	}

	public static void fail(String message) {
		throw new AssertionError(message);
	}

	public static <T extends Throwable> T assertThrows(Class<T> type, ThrowingRunnable code, String message) {
		try {
			code.run();
		} catch (Throwable t) {
			if (type.isInstance(t)) return type.cast(t);
			throw new AssertionError(message + " (wrong exception " + t.getClass().getSimpleName() + ": " + t.getMessage() + ")");
		}
		throw new AssertionError(message + " (no exception)");
	}
}
