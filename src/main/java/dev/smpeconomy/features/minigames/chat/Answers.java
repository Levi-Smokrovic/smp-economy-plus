package dev.smpeconomy.features.minigames.chat;

import java.util.Locale;

/**
 * Answer comparison for chat games: case-insensitive, punctuation and repeated spaces don't count.
 */
public final class Answers {
	private Answers() {}

	public static String normalize(String input) {
		if (input == null) return "";
		String t = input.trim().toLowerCase(Locale.ROOT)
			.replace("é", "e").replace("è", "e").replace("à", "a");
		StringBuilder sb = new StringBuilder(t.length());
		boolean space = false;
		for (int i = 0; i < t.length(); i++) {
			char c = t.charAt(i);
			if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
				if (space && !sb.isEmpty()) sb.append(' ');
				sb.append(c);
				space = false;
			} else {
				space = true;
			}
		}
		return sb.toString();
	}

	public static boolean matches(String expected, String given) {
		String e = normalize(expected);
		return !e.isEmpty() && e.equals(normalize(given));
	}
}
