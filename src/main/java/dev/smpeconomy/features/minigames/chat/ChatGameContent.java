package dev.smpeconomy.features.minigames.chat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Chat game content from {@code config/smpeconomy/chatgames.json} (template {@code
 * smpeconomy_defaults/chatgames.json}).
 */
public final class ChatGameContent {
	public static final int MIN_WORDS = 80;

	public List<String> words = new ArrayList<>();
	public List<String> phrases = new ArrayList<>();
	public List<Riddle> riddles = new ArrayList<>();

	public static final class Riddle {
		public List<String> answers = new ArrayList<>();
		public List<String> hints = new ArrayList<>();
	}

	/** Throws on unusable data, so the old state stays active. */
	public static void validate(ChatGameContent c) {
		if (c.words == null || c.phrases == null || c.riddles == null) throw new IllegalArgumentException("words/phrases/riddles missing");
		List<String> words = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (String w : c.words) {
			if (w == null) continue;
			String trimmed = w.trim();
			String norm = Answers.normalize(trimmed);
			// unscramble needs a single word with at least 4 different letter positions
			if (norm.length() < 4 || norm.contains(" ") || !seen.add(norm)) continue;
			if (norm.chars().distinct().count() < 2) continue;
			words.add(trimmed);
		}
		if (words.size() < MIN_WORDS) throw new IllegalArgumentException("at least " + MIN_WORDS + " valid words required (found: " + words.size() + ")");
		c.words = words;
		c.phrases.removeIf(p -> p == null || Answers.normalize(p).isEmpty());
		c.riddles.removeIf(r -> r == null || r.answers == null || r.hints == null || r.hints.isEmpty()
			|| r.answers.stream().allMatch(a -> a == null || Answers.normalize(a).isEmpty()));
		for (Riddle r : c.riddles) {
			r.answers.removeIf(a -> a == null || Answers.normalize(a).isEmpty());
			r.hints.removeIf(h -> h == null || h.isBlank());
		}
		c.riddles.removeIf(r -> r.hints.isEmpty());
	}
}
