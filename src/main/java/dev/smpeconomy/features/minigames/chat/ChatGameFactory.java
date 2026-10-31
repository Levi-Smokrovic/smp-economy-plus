package dev.smpeconomy.features.minigames.chat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import dev.smpeconomy.features.minigames.MinigamesConfig;
import dev.smpeconomy.features.minigames.chat.ChatGame.Type;

public final class ChatGameFactory {
	/** Without easily confused characters such as O/0, I/1. */
	private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

	public record MathTask(String expression, long answer) {}

	private ChatGameFactory() {}

	/** Kinds without content drop out, falling back to math. */
	public static Type pickType(Map<String, Integer> weights, ChatGameContent content, Random random) {
		List<Type> pool = new ArrayList<>();
		List<Integer> w = new ArrayList<>();
		int total = 0;
		for (Type t : Type.values()) {
			if (!available(t, content)) continue;
			int weight = weights == null ? 1 : Math.max(0, weights.getOrDefault(t.id, 0));
			if (weight <= 0) continue;
			pool.add(t);
			w.add(weight);
			total += weight;
		}
		if (total <= 0) return Type.MATH;
		int roll = random.nextInt(total);
		for (int i = 0; i < pool.size(); i++) {
			roll -= w.get(i);
			if (roll < 0) return pool.get(i);
		}
		return pool.get(pool.size() - 1);
	}

	public static boolean available(Type t, ChatGameContent content) {
		return switch (t) {
			case UNSCRAMBLE -> content != null && !content.words.isEmpty();
			case RIDDLE -> content != null && !content.riddles.isEmpty();
			case MATH, FAST_TYPE -> true;
		};
	}

	public static ChatGame create(Type type, ChatGameContent content, MinigamesConfig.ChatGames cfg, Random random, long now) {
		if (!available(type, content)) type = Type.MATH;
		long endsAt = now + cfg.timeoutSeconds * 1000L;
		switch (type) {
			case UNSCRAMBLE -> {
				String word = content.words.get(random.nextInt(content.words.size())).trim().toUpperCase(Locale.ROOT);
				String scrambled = scramble(word, random);
				return new ChatGame(type, "§7Unscramble the word: §f§l" + scrambled, word, Set.of(Answers.normalize(word)), List.of(), cfg.rewardUnscramble, now, endsAt);
			}
			case MATH -> {
				MathTask task = math(random);
				return new ChatGame(type, "§7Solve: §f§l" + task.expression(), Long.toString(task.answer()), Set.of(Long.toString(task.answer())), List.of(), cfg.rewardMath, now, endsAt);
			}
			case FAST_TYPE -> {
				String text = !content.phrases.isEmpty() && random.nextInt(100) < 60
					? content.phrases.get(random.nextInt(content.phrases.size())).trim()
					: code(random);
				return new ChatGame(type, "§7First to type: §f§l" + text, text, Set.of(Answers.normalize(text)), List.of(), cfg.rewardFastType, now, endsAt);
			}
			case RIDDLE -> {
				ChatGameContent.Riddle r = content.riddles.get(random.nextInt(content.riddles.size()));
				Set<String> accepted = new LinkedHashSet<>();
				for (String a : r.answers) accepted.add(Answers.normalize(a));
				accepted.remove("");
				return new ChatGame(type, "§7Who am I? §f" + r.hints.get(0), r.answers.get(0), accepted, r.hints, cfg.rewardRiddle, now, endsAt);
			}
		}
		throw new IllegalStateException("unknown game kind " + type);
	}

	/** The result always differs from the original for words with at least 2 different letters. */
	public static String scramble(String word, Random random) {
		List<Character> chars = new ArrayList<>();
		for (char c : word.toCharArray()) chars.add(c);
		String result = word;
		for (int attempt = 0; attempt < 30 && result.equals(word); attempt++) {
			Collections.shuffle(chars, random);
			StringBuilder sb = new StringBuilder();
			for (char c : chars) sb.append(c);
			result = sb.toString();
		}
		if (result.equals(word)) {
			// very short words with repeats: rotate until it looks different
			for (int shift = 1; shift < word.length() && result.equals(word); shift++) result = word.substring(shift) + word.substring(0, shift);
		}
		return result;
	}

	public static MathTask math(Random random) {
		int kind = random.nextInt(6);
		return switch (kind) {
			case 0 -> {
				int a = between(random, 10, 99), b = between(random, 10, 99);
				yield new MathTask(a + " + " + b, a + b);
			}
			case 1 -> {
				int a = between(random, 30, 150), b = between(random, 5, a - 1);
				yield new MathTask(a + " - " + b, a - b);
			}
			case 2 -> {
				int a = between(random, 3, 15), b = between(random, 3, 15);
				yield new MathTask(a + " × " + b, (long) a * b);
			}
			case 3 -> {
				int a = between(random, 2, 12), b = between(random, 2, 12), c = between(random, 1, 50);
				yield new MathTask(a + " × " + b + " + " + c, (long) a * b + c);
			}
			case 4 -> {
				int a = between(random, 2, 20), b = between(random, 2, 20), c = between(random, 2, 9);
				yield new MathTask("(" + a + " + " + b + ") × " + c, (long) (a + b) * c);
			}
			default -> {
				int a = between(random, 20, 80), b = between(random, 10, 60), c = between(random, 1, a + b - 1);
				yield new MathTask(a + " + " + b + " - " + c, a + b - c);
			}
		};
	}

	public static String code(Random random) {
		StringBuilder sb = new StringBuilder("smp-");
		for (int i = 0; i < 5; i++) sb.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));
		return sb.toString();
	}

	private static int between(Random random, int min, int max) {
		return max <= min ? min : min + random.nextInt(max - min + 1);
	}
}
