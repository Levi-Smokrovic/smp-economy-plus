package dev.smpeconomy.features.minigames.chat;

import java.util.List;
import java.util.Set;

/**
 * A running chat game. {@code accepted} holds the normalized answers ({@link Answers#normalize}), {@code solution}
 * the displayed one.
 */
public final class ChatGame {
	public enum Type {
		UNSCRAMBLE("unscramble", "Unscramble"),
		MATH("math", "Quick Math"),
		FAST_TYPE("fasttype", "Fast Type"),
		RIDDLE("riddle", "Who am I?");

		public final String id;
		public final String command;
		public final String label;

		Type(String id, String label) {
			this.id = id;
			this.command = id;
			this.label = label;
		}

		public static Type parse(String s) {
			if (s == null) return null;
			for (Type t : values()) {
				if (t.id.equalsIgnoreCase(s) || t.name().equalsIgnoreCase(s)) return t;
			}
			return null;
		}
	}

	private final Type type;
	/** What players see, without prefix, with § codes. */
	private final String question;
	private final String solution;
	private final Set<String> accepted;
	private final List<String> hints;
	private final long baseReward;
	private final long startedAt;
	private final long endsAt;
	private int hintsShown;

	public ChatGame(Type type, String question, String solution, Set<String> accepted, List<String> hints, long baseReward, long startedAt, long endsAt) {
		this.type = type;
		this.question = question;
		this.solution = solution;
		this.accepted = Set.copyOf(accepted);
		this.hints = List.copyOf(hints);
		this.baseReward = baseReward;
		this.startedAt = startedAt;
		this.endsAt = endsAt;
		this.hintsShown = hints.isEmpty() ? 0 : 1;
	}

	public Type type() {
		return type;
	}

	public String question() {
		return question;
	}

	public String solution() {
		return solution;
	}

	public Set<String> accepted() {
		return accepted;
	}

	public List<String> hints() {
		return hints;
	}

	public long baseReward() {
		return baseReward;
	}

	public long startedAt() {
		return startedAt;
	}

	public long endsAt() {
		return endsAt;
	}

	public int hintsShown() {
		return hintsShown;
	}

	void setHintsShown(int n) {
		this.hintsShown = Math.max(0, Math.min(hints.size(), n));
	}

	public boolean isCorrect(String text) {
		String n = Answers.normalize(text);
		return !n.isEmpty() && accepted.contains(n);
	}
}
