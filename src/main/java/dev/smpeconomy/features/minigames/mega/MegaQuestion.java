package dev.smpeconomy.features.minigames.mega;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.network.chat.Component;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.features.minigames.chat.Answers;

/**
 * An answerable MEGA question, correct by construction: the generator knows the solution before phrasing the
 * question. Questions and explanations are components, so the client translates item and mob names. {@code
 * solutionPlain} is for the console, the log after the end, and selftests.
 * <p>Number questions compare the canonical integer ({@link MegaAnswers#number}) or digit sequence; text questions
 * compare the normal form ({@link Answers#normalize}) against all {@code accepted}.
 * <p>What stays private ({@link #read}): classifying a chat message never depends on whether it is correct, or
 * "disappears/appears" could be used to query the solution. Number questions: any message with a digit. Text
 * questions: any message containing a {@code guard} term (all possible solutions of this kind, the right one always
 * among them), even with a typo or inflection ({@link MegaAnswers#mentions}), and any message that is exactly one
 * {@code vocabulary} term.
 */
public final class MegaQuestion {
	public enum ReadKind {
		/** Can't reveal the solution: normal public chat. */
		CHAT,
		/** Attempt ({@link Read#value} canonical): private, counts. */
		ATTEMPT,
		/** Could reveal the solution but is no clear attempt: private, doesn't count. */
		UNCLEAR
	}

	/** Normalized once, so large lists aren't converted per question. */
	public record Terms(Set<String> normalized) {
		public static final Terms NONE = new Terms(Set.of());

		public static Terms of(Collection<String> raw) {
			Set<String> out = new HashSet<>();
			for (String t : raw) {
				String n = Answers.normalize(t);
				if (!n.isEmpty()) out.add(n);
			}
			return new Terms(Set.copyOf(out));
		}
	}

	public record Read(ReadKind kind, String value) {
		static final Read CHAT = new Read(ReadKind.CHAT, null);
		static final Read UNCLEAR = new Read(ReadKind.UNCLEAR, null);

		public boolean hidden() {
			return kind != ReadKind.CHAT;
		}
	}

	private final MegaChallenge.Type type;
	private final String variant;
	private final String variantLabel;
	private final List<Component> lines;
	private final List<Component> hints;
	private final Set<String> accepted;
	private final boolean numeric;
	private final boolean digitSequence;
	private final Set<String> guard;
	private final Set<String> vocabulary;
	private final Component solution;
	private final String solutionPlain;
	private final List<Component> explanation;
	private final int maxGuesses;
	private final Map<String, Object> facts;

	private MegaQuestion(Builder b) {
		this.type = b.type;
		this.variant = b.variant;
		this.variantLabel = b.variantLabel;
		this.lines = List.copyOf(b.lines);
		this.hints = List.copyOf(b.hints);
		this.numeric = b.numeric;
		this.digitSequence = b.numeric && b.digitSequence;
		Set<String> acc = new LinkedHashSet<>();
		for (String a : b.accepted) {
			String n = numeric ? (digitSequence ? a.replaceAll("[^0-9]", "") : MegaAnswers.number(a)) : Answers.normalize(a);
			if (n != null && !n.isEmpty()) acc.add(n);
		}
		if (acc.isEmpty()) throw new IllegalStateException("question without a valid answer: " + b.solutionPlain);
		this.accepted = Set.copyOf(acc);
		Set<String> g = new HashSet<>(acc);
		if (!numeric) {
			for (String t : b.guard) {
				String n = Answers.normalize(t);
				if (!n.isEmpty()) g.add(n);
			}
		}
		this.guard = Set.copyOf(g);
		this.vocabulary = numeric ? Set.of() : b.vocabulary.normalized();
		this.solution = b.solution != null ? b.solution : Msg.legacy("§f" + b.solutionPlain);
		this.solutionPlain = b.solutionPlain;
		this.explanation = List.copyOf(b.explanation);
		this.maxGuesses = b.maxGuesses;
		this.facts = Map.copyOf(b.facts);
	}

	public static Builder builder(MegaChallenge.Type type, String variant, String variantLabel) {
		return new Builder(type, variant, variantLabel);
	}

	public MegaChallenge.Type type() {
		return type;
	}

	public String variant() {
		return variant;
	}

	public String variantLabel() {
		return variantLabel;
	}

	public List<Component> lines() {
		return lines;
	}

	public List<Component> hints() {
		return hints;
	}

	public Set<String> accepted() {
		return accepted;
	}

	public boolean numeric() {
		return numeric;
	}

	/** E.g. "5, 3 and 1" gives 531. */
	public boolean digitSequence() {
		return digitSequence;
	}

	public Set<String> guard() {
		return guard;
	}

	public Component solution() {
		return solution;
	}

	public String solutionPlain() {
		return solutionPlain;
	}

	public List<Component> explanation() {
		return explanation;
	}

	/** -1 = config value of the kind. */
	public int maxGuesses() {
		return maxGuesses;
	}

	public Map<String, Object> facts() {
		return facts;
	}

	/** Only use on messages already classified as an attempt. */
	public boolean isCorrect(String text) {
		Read r = read(text);
		return r.kind() == ReadKind.ATTEMPT && accepted.contains(r.value());
	}

	/**
	 * Classifies a chat message regardless of whether it is correct (see class doc). Number questions: no digit is
	 * chat; with a digit it is private, an attempt only in a clear form ("54", "x = -5", "54 divisors", for digit
	 * sequences also "5, 3 and 1"). Text questions: exactly one {@code guard} or {@code vocabulary} term is an
	 * attempt; containing a {@code guard} term or matching one up to a typo is private without scoring; otherwise
	 * chat.
	 */
	public Read read(String text) {
		if (text == null || text.isBlank()) return Read.CHAT;
		if (numeric) {
			if (!MegaAnswers.hasDigit(text)) return Read.CHAT;
			String n = MegaAnswers.number(text);
			if (digitSequence) {
				String seq = MegaAnswers.digitSequence(text);
				if (seq != null) return new Read(ReadKind.ATTEMPT, seq);
				if (n != null && !n.startsWith("-")) return new Read(ReadKind.ATTEMPT, n);
				return Read.UNCLEAR;
			}
			return n != null ? new Read(ReadKind.ATTEMPT, n) : Read.UNCLEAR;
		}
		String n = Answers.normalize(text);
		if (n.isEmpty()) return Read.CHAT;
		if (guard.contains(n) || vocabulary.contains(n)) return new Read(ReadKind.ATTEMPT, n);
		for (String t : guard) {
			if (MegaAnswers.mentions(n, t)) return Read.UNCLEAR;
		}
		return Read.CHAT;
	}

	public static final class Builder {
		private final MegaChallenge.Type type;
		private final String variant;
		private final String variantLabel;
		private final List<Component> lines = new ArrayList<>();
		private final List<Component> hints = new ArrayList<>();
		private final List<String> accepted = new ArrayList<>();
		private final List<String> guard = new ArrayList<>();
		private Terms vocabulary = Terms.NONE;
		private final List<Component> explanation = new ArrayList<>();
		private final Map<String, Object> facts = new java.util.LinkedHashMap<>();
		private boolean numeric;
		private boolean digitSequence;
		private Component solution;
		private String solutionPlain = "";
		private int maxGuesses = -1;

		private Builder(MegaChallenge.Type type, String variant, String variantLabel) {
			this.type = type;
			this.variant = variant;
			this.variantLabel = variantLabel;
		}

		public Builder line(Component c) {
			lines.add(c);
			return this;
		}

		public Builder line(String legacy) {
			return line(Msg.legacy(legacy));
		}

		public Builder hint(Component c) {
			hints.add(c);
			return this;
		}

		public Builder hint(String legacy) {
			return hint(Msg.legacy(legacy));
		}

		public Builder number(String canonical) {
			numeric = true;
			accepted.add(canonical);
			solutionPlain = canonical;
			return this;
		}

		public Builder number(long value) {
			return number(Long.toString(value));
		}

		/** Digit sequence as the answer ("531"); chat also accepts "5 3 1", "5, 3 and 1". */
		public Builder digits(String digits) {
			numeric = true;
			digitSequence = true;
			accepted.add(digits);
			solutionPlain = digits;
			return this;
		}

		public Builder answers(List<String> answers) {
			numeric = false;
			for (String a : answers) {
				if (a != null && !a.isBlank()) accepted.add(a);
			}
			if (solutionPlain.isEmpty() && !accepted.isEmpty()) solutionPlain = accepted.get(0);
			return this;
		}

		/**
		 * All possible solutions of this kind, independent of the correct one. Messages containing one stay
		 * private, so hiding reveals nothing about the solution.
		 */
		public Builder guard(Collection<String> terms) {
			guard.addAll(terms);
			return this;
		}

		public Builder vocabulary(Collection<String> terms) {
			return vocabulary(Terms.of(terms));
		}

		public Builder vocabulary(Terms terms) {
			vocabulary = terms == null ? Terms.NONE : terms;
			return this;
		}

		public Builder solution(Component c, String plain) {
			solution = c;
			solutionPlain = plain;
			return this;
		}

		public Builder explain(Component c) {
			explanation.add(c);
			return this;
		}

		public Builder explain(String legacy) {
			return explain(Msg.legacy(legacy));
		}

		public Builder maxGuesses(int max) {
			maxGuesses = max;
			return this;
		}

		public Builder fact(String key, Object value) {
			facts.put(key, value);
			return this;
		}

		public MegaQuestion build() {
			return new MegaQuestion(this);
		}
	}
}
