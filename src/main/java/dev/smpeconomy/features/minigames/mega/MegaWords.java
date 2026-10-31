package dev.smpeconomy.features.minigames.mega;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import dev.smpeconomy.features.minigames.chat.Answers;
import dev.smpeconomy.features.minigames.chat.ChatGameFactory;

public final class MegaWords {
	private MegaWords() {}

	static List<String> words(String term) {
		List<String> out = new ArrayList<>();
		for (String w : term.trim().split("[\\s-]+")) {
			if (!w.isEmpty()) out.add(w);
		}
		return out;
	}

	public static boolean scramblable(String term) {
		if (term == null || term.isBlank() || term.length() > 48) return false;
		List<String> words = words(term);
		if (words.size() < 2) return false;
		int letters = 0;
		for (String w : words) {
			if (w.length() < 2 || !w.chars().allMatch(Character::isLetter)) return false;
			if (w.toLowerCase(Locale.ROOT).chars().distinct().count() < 2) return false;
			letters += w.length();
		}
		return letters >= 12 && !Answers.normalize(term).isEmpty();
	}

	static String upper(String s) {
		StringBuilder sb = new StringBuilder(s.length());
		for (char c : s.toCharArray()) {
			sb.append(Character.toUpperCase(c));
		}
		return sb.toString();
	}

	/** Separators stay in place. */
	public static String scramble(String term, Random r) {
		String up = upper(term.trim());
		StringBuilder out = new StringBuilder();
		StringBuilder word = new StringBuilder();
		for (char c : up.toCharArray()) {
			if (Character.isLetter(c)) {
				word.append(c);
			} else {
				if (!word.isEmpty()) {
					out.append(ChatGameFactory.scramble(word.toString(), r));
					word.setLength(0);
				}
				out.append(c == ' ' ? "  " : String.valueOf(c));
			}
		}
		if (!word.isEmpty()) out.append(ChatGameFactory.scramble(word.toString(), r));
		return out.toString();
	}

	public static List<String> terms(MegaContent content, java.util.Collection<String> itemNames) {
		Set<String> seen = new LinkedHashSet<>();
		List<String> out = new ArrayList<>();
		if (content != null) {
			for (String t : content.terms) {
				if (scramblable(t) && seen.add(Answers.normalize(t))) out.add(t.trim());
			}
		}
		if (itemNames != null) {
			List<String> sorted = new ArrayList<>(itemNames);
			sorted.sort(String::compareTo);
			for (String t : sorted) {
				if (scramblable(t) && seen.add(Answers.normalize(t))) out.add(t.trim());
			}
		}
		return out;
	}

	public static MegaQuestion unscramble(List<String> terms, Random r) {
		return unscramble(terms, MegaQuestion.Terms.NONE, r);
	}

	/**
	 * @param vocabulary known terms that count as an attempt as a whole message; all terms in the list stay private
	 * in chat, whichever is being asked
	 */
	public static MegaQuestion unscramble(List<String> terms, MegaQuestion.Terms vocabulary, Random r) {
		if (terms.isEmpty()) throw new IllegalStateException("no terms for the word scramble");
		String term = terms.get(r.nextInt(terms.size()));
		List<String> words = words(term);
		String scrambled = scramble(term, r);
		MegaQuestion.Builder b = MegaQuestion.builder(MegaChallenge.Type.UNSCRAMBLE, "unscramble", "Word Scramble");
		b.line("§fUnscramble the Minecraft term §7(" + words.size() + " words, each word scrambled on its own)§f:");
		b.line("§e§l" + scrambled);
		StringBuilder first = new StringBuilder();
		StringBuilder two = new StringBuilder();
		for (String w : words) {
			String u = upper(w);
			first.append(first.isEmpty() ? "" : "  ").append(u.charAt(0)).append("…");
			two.append(two.isEmpty() ? "" : "  ").append(u, 0, Math.min(2, u.length())).append("…");
		}
		b.hint("§fFirst letters: §e" + first);
		b.hint("§fThe first two letters: §e" + two);
		b.hint("§fThe first word is: §e" + upper(words.get(0)));
		b.answers(List.of(term));
		b.guard(terms);
		b.vocabulary(vocabulary);
		b.explain("§7" + scrambled + " §7→ §a" + term);
		b.fact("term", term);
		b.fact("scrambled", scrambled);
		return b.build();
	}

	public static MegaQuestion riddle(MegaContent content, Random r) {
		return riddle(content, MegaQuestion.Terms.NONE, r);
	}

	/**
	 * @param vocabulary known terms that count as an attempt as a whole message; answers of all riddles stay
	 * private in chat, whichever is running
	 */
	public static MegaQuestion riddle(MegaContent content, MegaQuestion.Terms vocabulary, Random r) {
		if (content == null || content.riddles.isEmpty()) throw new IllegalStateException("no riddles");
		MegaContent.Riddle riddle = content.riddles.get(r.nextInt(content.riddles.size()));
		MegaQuestion.Builder b = MegaQuestion.builder(MegaChallenge.Type.RIDDLE, "riddle", "Riddle");
		b.line("§fWho or what am I?");
		b.line("§e1. §f" + riddle.clues.get(0));
		for (int i = 1; i < riddle.clues.size(); i++) b.hint("§e" + (i + 1) + ". §f" + riddle.clues.get(i));
		b.answers(riddle.answers);
		List<String> guard = new ArrayList<>();
		for (MegaContent.Riddle other : content.riddles) guard.addAll(other.answers);
		b.guard(guard);
		b.vocabulary(vocabulary);
		b.explain("§7All clues fit §a" + riddle.answers.get(0) + "§7.");
		b.fact("riddle", riddle);
		return b.build();
	}
}
