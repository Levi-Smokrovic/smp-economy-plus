package dev.smpeconomy.features.minigames.mega;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import dev.smpeconomy.features.minigames.chat.Answers;

/**
 * MEGA-CHALLENGE content from {@code config/smpeconomy/megachallenge.json} (template {@code
 * smpeconomy_defaults/megachallenge.json}).
 */
public final class MegaContent {
	public static final int MIN_RIDDLES = 10;
	public static final int MIN_TERMS = 10;

	public List<Riddle> riddles = new ArrayList<>();
	public List<String> terms = new ArrayList<>();

	/** Hints from hardest to easiest; the first answer is the displayed one. */
	public static final class Riddle {
		public List<String> answers = new ArrayList<>();
		public List<String> clues = new ArrayList<>();
	}

	/** Throws on unusable data, so the old state stays active. */
	public static void validate(MegaContent c) {
		if (c.riddles == null || c.terms == null) throw new IllegalArgumentException("riddles/terms missing");
		c.riddles.removeIf(r -> r == null || r.answers == null || r.clues == null);
		for (Riddle r : c.riddles) {
			r.answers.removeIf(a -> a == null || Answers.normalize(a).isEmpty());
			r.clues.removeIf(h -> h == null || h.isBlank());
		}
		c.riddles.removeIf(r -> r.answers.isEmpty() || r.clues.size() < 2);
		if (c.riddles.size() < MIN_RIDDLES) throw new IllegalArgumentException("at least " + MIN_RIDDLES + " riddles with 2+ hints required");
		List<String> terms = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (String t : c.terms) {
			if (t == null) continue;
			String trimmed = t.trim().replaceAll("\\s+", " ");
			if (!MegaWords.scramblable(trimmed) || !seen.add(Answers.normalize(trimmed))) continue;
			terms.add(trimmed);
		}
		if (terms.size() < MIN_TERMS) throw new IllegalArgumentException("at least " + MIN_TERMS + " terms of 2+ words required (found: " + terms.size() + ")");
		c.terms = terms;
	}
}
