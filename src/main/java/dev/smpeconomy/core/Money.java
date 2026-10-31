package dev.smpeconomy.core;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.regex.Pattern;

/**
 * Amounts are always {@code long} cents. Plain Java with no Minecraft classes, so it stays testable without a server.
 */
public final class Money {
	/** 1 quadrillion cents ($10 trillion). Keeps all sums far from long overflow. */
	public static final long MAX = 1_000_000_000_000_000L;

	private static final double FLOOR_EPSILON = 1e-9;
	private static final Pattern NUMBER = Pattern.compile("[0-9]+(\\.[0-9]+)?");
	private static final Pattern GROUPED = Pattern.compile("[0-9]{1,3}(,[0-9]{3})+(\\.[0-9]+)?");
	private static final long[] SHORT_UNITS = {100_000_000_000_000L, 100_000_000_000L, 100_000_000L, 100_000L};
	private static final String[] SHORT_SUFFIX = {"T", "B", "M", "K"};

	private static volatile String symbol = "$";
	private static volatile boolean symbolAfter = false;

	private Money() {}

	public static void setCurrency(String newSymbol, boolean after) {
		symbol = newSymbol == null || newSymbol.isBlank() ? "$" : newSymbol;
		symbolAfter = after;
	}

	public static String symbol() {
		return symbol;
	}

	public static boolean symbolAfter() {
		return symbolAfter;
	}

	public static String withSymbol(boolean negative, String number) {
		String sign = negative ? "-" : "";
		return symbolAfter ? sign + number + symbol : sign + symbol + number;
	}

	public static String format(long cents) {
		String digits = magnitude(cents);
		if (digits.length() < 3) digits = "0".repeat(3 - digits.length()) + digits;
		String whole = digits.substring(0, digits.length() - 2);
		String frac = digits.substring(digits.length() - 2);
		return withSymbol(cents < 0, group(whole) + "." + frac);
	}

	/** Short form for HUD and GUIs. Rounds down so it never shows more than is there. */
	public static String formatShort(long cents) {
		if (cents < 0) return "-" + formatShort(cents == Long.MIN_VALUE ? Long.MAX_VALUE : -cents);
		for (int i = 0; i < SHORT_UNITS.length; i++) {
			long unit = SHORT_UNITS[i];
			if (cents >= unit) {
				long tenths = cents / (unit / 10);
				String num = (tenths % 10 == 0) ? Long.toString(tenths / 10) : (tenths / 10) + "." + (tenths % 10);
				return withSymbol(false, num + SHORT_SUFFIX[i]);
			}
		}
		String digits = magnitude(cents);
		if (digits.length() < 3) digits = "0".repeat(3 - digits.length()) + digits;
		String whole = group(digits.substring(0, digits.length() - 2));
		String frac = digits.substring(digits.length() - 2);
		return withSymbol(false, frac.equals("00") ? whole : whole + "." + frac);
	}

	/**
	 * Parses player input: {@code 100}, {@code 2.5k}, {@code 1m}, {@code 1.5b}, {@code 2t}, {@code 12.34}, {@code $5},
	 * {@code 1,000}, {@code 12,50}. At most 2 decimals after applying the suffix, zeros included: {@code 1.000} and
	 * {@code 2.500} (German thousands separators) are invalid rather than $1 or $2.50. The result must be above 0 and
	 * at most {@code MAX}.
	 *
	 * @return cents, or empty if invalid
	 */
	public static OptionalLong parse(String input) {
		OptionalLong v = parseAllowZero(input);
		return v.isPresent() && v.getAsLong() > 0 ? v : OptionalLong.empty();
	}

	public static OptionalLong parseAllowZero(String input) {
		if (input == null) return OptionalLong.empty();
		String s = input.trim().toLowerCase(Locale.ROOT);
		String sym = symbol.trim().toLowerCase(Locale.ROOT);
		if (s.startsWith("$")) s = s.substring(1).trim();
		else if (s.endsWith("$")) s = s.substring(0, s.length() - 1).trim();
		if (!sym.isEmpty() && !sym.equals("$")) {
			if (s.startsWith(sym)) s = s.substring(sym.length()).trim();
			else if (s.endsWith(sym)) s = s.substring(0, s.length() - sym.length()).trim();
		}
		if (s.isEmpty() || s.length() > 40) return OptionalLong.empty();

		int exponent = switch (s.charAt(s.length() - 1)) {
			case 'k' -> 3;
			case 'm' -> 6;
			case 'b' -> 9;
			case 't' -> 12;
			default -> 0;
		};
		if (exponent != 0) s = s.substring(0, s.length() - 1);

		s = normalizeSeparators(s);
		if (s == null || !NUMBER.matcher(s).matches()) return OptionalLong.empty();

		// Count decimals before zeros drop: "1.000" (a thousand in German) is invalid, not $1.00. With a suffix, count
		// after shifting ("1.234k" = 1234 is ok, "1.0001k" = 1000.1 is ok).
		BigDecimal cents = new BigDecimal(s).movePointRight(2 + exponent);
		if (cents.signum() < 0 || cents.scale() > 0) return OptionalLong.empty();
		long value;
		try {
			value = cents.longValueExact(); // throws on overflow
		} catch (ArithmeticException e) {
			return OptionalLong.empty();
		}
		return value <= MAX ? OptionalLong.of(value) : OptionalLong.empty();
	}

	public static long saturatedAdd(long a, long b) {
		long r = a + b;
		return ((a ^ r) & (b ^ r)) < 0 ? (a < 0 ? Long.MIN_VALUE : Long.MAX_VALUE) : r;
	}

	/** Rounds a computed amount down to cents, clamped to 0..{@link #MAX}. The epsilon absorbs double error at whole cents. */
	public static long floorCents(double v) {
		if (!(v > 0)) return 0;
		if (v >= MAX) return MAX;
		return (long) Math.floor(v + FLOOR_EPSILON);
	}

	/** Formatted the way {@link #parse} reads it, without thousands separators: "5888" or "5888.40". */
	public static String commandAmount(long cents) {
		long c = Math.max(0, cents);
		return c % 100 == 0 ? Long.toString(c / 100) : (c / 100) + "." + String.format(Locale.ROOT, "%02d", c % 100);
	}

	public static boolean isValidAmount(long cents) {
		return cents > 0 && cents <= MAX;
	}

	/** Rounds down. */
	public static long percentOf(long cents, double percent) {
		if (cents <= 0 || percent <= 0) return 0;
		return BigDecimal.valueOf(cents).multiply(BigDecimal.valueOf(percent)).movePointLeft(2).longValue();
	}

	/** Comma as thousands separator ("1,000") or German decimal comma ("12,50"). */
	private static String normalizeSeparators(String s) {
		if (s.indexOf(',') < 0) return s;
		if (GROUPED.matcher(s).matches()) return s.replace(",", "");
		int idx = s.indexOf(',');
		int decimals = s.length() - idx - 1;
		if (idx == s.lastIndexOf(',') && s.indexOf('.') < 0 && decimals >= 1 && decimals <= 2) {
			return s.replace(',', '.');
		}
		return null;
	}

	private static String magnitude(long cents) {
		// -Long.MIN_VALUE is MIN_VALUE again, but read as unsigned it is the correct amount
		return cents < 0 ? Long.toUnsignedString(-cents) : Long.toString(cents);
	}

	private static String group(String digits) {
		StringBuilder sb = new StringBuilder(digits.length() + digits.length() / 3);
		int first = digits.length() % 3;
		if (first == 0) first = 3;
		sb.append(digits, 0, first);
		for (int i = first; i < digits.length(); i += 3) sb.append(',').append(digits, i, i + 3);
		return sb.toString();
	}
}
