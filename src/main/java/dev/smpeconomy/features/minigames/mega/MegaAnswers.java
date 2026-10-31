package dev.smpeconomy.features.minigames.mega;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import dev.smpeconomy.features.minigames.chat.Answers;

/**
 * Answer normalization. Numbers: thousands separators (dot, comma, apostrophe, space, only in real groups of
 * three), signs (also "−"), "x = 5", "= 54" and "54 divisors" become the canonical integer. Digit sequences ("5, 3
 * and 1" gives "531") only on questions asking for one. Text as in chat games ({@link Answers#normalize}).
 */
public final class MegaAnswers {
	private static final Pattern STRICT = Pattern.compile("^(?:[a-z]{1,3}\\s*=\\s*|=\\s*)?([+-]?)\\s*(\\d{1,3}(?:[.,' ]\\d{3})+|\\d+)$");
	private static final Pattern TOKEN = Pattern.compile("^([+-]?)(\\d{1,3}(?:[.,']\\d{3})+|\\d+)[.!?,:;]?$");
	private static final Pattern SEQUENCE = Pattern.compile("^\\d+(?:(?:\\s*[,;/.\\-]\\s*|\\s+(?:and|&)\\s+|\\s+)\\d+)*$");

	private MegaAnswers() {}

	private static String clean(String input) {
		if (input == null) return "";
		String t = input.trim().toLowerCase(java.util.Locale.ROOT)
			.replace('−', '-').replace('–', '-').replace('—', '-')
			.replace(' ', ' ').replace(' ', ' ');
		while (!t.isEmpty() && ".!?".indexOf(t.charAt(t.length() - 1)) >= 0) t = t.substring(0, t.length() - 1).trim();
		return t;
	}

	/**
	 * Canonical integer from a chat answer, or null. Accepts the whole message as a number or a number with at most
	 * one more word. Longer messages ("brb 5 min") are no attempt, though number questions still keep them private,
	 * see {@link #hasDigit}.
	 */
	public static String number(String input) {
		String t = clean(input);
		if (t.isEmpty()) return null;
		Matcher m = STRICT.matcher(t);
		if (m.matches()) return canonical(m.group(1), m.group(2));
		String[] tokens = t.split("\\s+");
		if (tokens.length > 2) return null;
		String found = null;
		for (String token : tokens) {
			Matcher tm = TOKEN.matcher(token);
			if (!tm.matches()) continue;
			if (found != null) return null; // several numbers: no unambiguous answer
			found = canonical(tm.group(1), tm.group(2));
		}
		return found;
	}

	/**
	 * Digit sequence from a chat answer or null. Only for questions explicitly asking for one, where there are no
	 * negative numbers or thousands.
	 */
	public static String digitSequence(String input) {
		String t = clean(input);
		if (t.isEmpty() || !SEQUENCE.matcher(t).matches()) return null;
		String digits = t.replaceAll("[^0-9]", "");
		return digits.isEmpty() ? null : digits;
	}

	/** Includes full-width and other scripts, since those could reveal a number too. */
	public static boolean hasDigit(String text) {
		if (text == null) return false;
		for (int i = 0; i < text.length(); i++) {
			if (Character.isDigit(text.charAt(i))) return true;
		}
		return false;
	}

	public static String canonical(String sign, String digits) {
		String d = digits.replaceAll("[^0-9]", "").replaceFirst("^0+(?=\\d)", "");
		if (d.equals("0")) return "0";
		return ("-".equals(sign) ? "-" : "") + d;
	}

	public static String canonical(long value) {
		return Long.toString(value);
	}

	public static int words(String text) {
		String n = Answers.normalize(text);
		return n.isEmpty() ? 0 : n.split(" ").length;
	}

	public static boolean containsTerm(String normalized, String term) {
		if (term == null || term.isEmpty() || normalized == null || normalized.length() < term.length()) return false;
		return (" " + normalized + " ").contains(" " + term + " ");
	}

	/**
	 * Typos and inflection ("elder guardians" ~ "elder guardian"): Levenshtein distance at most 1 (terms of 7-9
	 * characters) or 2 (10+). Shorter terms exact only, or "garden" would match "warden".
	 */
	public static boolean nearly(String candidate, String term) {
		if (candidate == null || term == null) return false;
		if (term.length() < 7) return candidate.equals(term);
		int max = term.length() < 10 ? 1 : 2;
		if (Math.abs(candidate.length() - term.length()) > max) return false;
		return distance(candidate, term, max) <= max;
	}

	public static boolean mentions(String normalized, String term) {
		if (containsTerm(normalized, term)) return true;
		if (term.length() < 7) return false;
		String[] words = normalized.split(" ");
		int n = term.split(" ").length;
		for (int i = 0; i + n <= words.length; i++) {
			if (nearly(String.join(" ", java.util.Arrays.copyOfRange(words, i, i + n)), term)) return true;
		}
		return false;
	}

	/** Stops once it is certainly above {@code limit}, then returns limit + 1. */
	static int distance(String a, String b, int limit) {
		int[] prev = new int[b.length() + 1];
		int[] cur = new int[b.length() + 1];
		for (int j = 0; j <= b.length(); j++) prev[j] = j;
		for (int i = 1; i <= a.length(); i++) {
			cur[0] = i;
			int rowMin = cur[0];
			for (int j = 1; j <= b.length(); j++) {
				int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
				cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
				rowMin = Math.min(rowMin, cur[j]);
			}
			if (rowMin > limit) return limit + 1;
			int[] t = prev;
			prev = cur;
			cur = t;
		}
		return prev[b.length()];
	}
}
