package dev.smpeconomy.features.minigames.mega;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import dev.smpeconomy.features.minigames.mega.MegaChallenge.Prepared;
import dev.smpeconomy.features.minigames.mega.MegaChallenge.Type;

public final class MegaGenerator {
	/** Seconds of night a night race needs after the announcement. */
	public static final int MIN_NIGHT_RACE_SECONDS = 240;

	private MegaGenerator() {}

	public static boolean raceAvailable(MegaConfig.Race race, MegaConfig cfg, long nightMillisLeft) {
		if (!race.night) return true;
		return nightMillisLeft >= (cfg.announceSeconds + (long) MIN_NIGHT_RACE_SECONDS) * 1000L;
	}

	/**
	 * @param variant null = random by weight, otherwise math/trivia kind or race id
	 * @param nightMillisLeft remaining overworld night, 0 by day
	 */
	public static Prepared prepare(Type type, String variant, MegaConfig cfg, MegaContent content, Supplier<MegaTrivia.Data> trivia,
		Supplier<Collection<String>> itemNames, Supplier<MegaQuestion.Terms> vocabulary, LongSupplier nightMillisLeft, Random r) {
		String v = variant == null || variant.isBlank() ? null : variant.trim().toLowerCase(Locale.ROOT);
		switch (type) {
			case MATH -> {
				String kind = v != null ? checkVariant(v, MegaMath.KINDS) : MegaChallenge.pickWeighted(cfg.mathWeights, MegaMath.KINDS, r);
				if (kind == null) throw new IllegalStateException("No math task with weight > 0");
				return Prepared.of(MegaMath.create(kind, r));
			}
			case TRIVIA -> {
				MegaTrivia.Data d = trivia.get();
				List<String> available = new ArrayList<>();
				for (String k : MegaTrivia.KINDS) {
					if (MegaTrivia.available(k, d)) available.add(k);
				}
				String kind;
				if (v != null) {
					kind = checkVariant(v, MegaTrivia.KINDS);
					if (!available.contains(kind)) throw new IllegalStateException("Trivia question " + kind + " not possible right now (not enough data)");
				} else {
					kind = MegaChallenge.pickWeighted(cfg.triviaWeights, available, r);
					if (kind == null) throw new IllegalStateException("No trivia question possible");
				}
				return Prepared.of(MegaTrivia.create(kind, d, vocabulary.get(), r));
			}
			case RACE -> {
				MegaConfig.Race race = null;
				long night = nightMillisLeft.getAsLong();
				if (v != null) {
					for (MegaConfig.Race rc : cfg.races) {
						if (rc.id.equalsIgnoreCase(v)) race = rc;
					}
					if (race == null) throw new IllegalArgumentException("Unknown race: " + v + " (allowed: " + raceIds(cfg) + ")");
					if (!raceAvailable(race, cfg, night)) {
						throw new IllegalStateException("Race " + race.id + " only at night (Overworld, at least " + MIN_NIGHT_RACE_SECONDS / 60
							+ " min of night after the announcement; now " + night / 1000 + " s)");
					}
				} else {
					Map<String, Integer> weights = new LinkedHashMap<>();
					for (MegaConfig.Race rc : cfg.races) {
						if (raceAvailable(rc, cfg, night)) weights.put(rc.id, rc.weight);
					}
					String id = MegaChallenge.pickWeighted(weights, new ArrayList<>(weights.keySet()), r);
					if (id == null) throw new IllegalStateException("No race with weight > 0 possible right now");
					for (MegaConfig.Race rc : cfg.races) {
						if (rc.id.equals(id)) race = rc;
					}
				}
				return Prepared.of(race);
			}
			case RIDDLE -> {
				return Prepared.of(MegaWords.riddle(content, vocabulary.get(), r));
			}
			default -> {
				return Prepared.of(MegaWords.unscramble(MegaWords.terms(content, itemNames.get()), vocabulary.get(), r));
			}
		}
	}

	private static String checkVariant(String v, List<String> allowed) {
		for (String a : allowed) {
			if (a.equalsIgnoreCase(v)) return a;
		}
		throw new IllegalArgumentException("Unknown variant: " + v + " (allowed: " + String.join(", ", allowed) + ")");
	}

	public static List<String> raceIds(MegaConfig cfg) {
		return cfg.races.stream().map(r -> r.id).toList();
	}

	public static List<String> variants(Type type, MegaConfig cfg) {
		return switch (type) {
			case MATH -> MegaMath.KINDS;
			case TRIVIA -> MegaTrivia.KINDS;
			case RACE -> raceIds(cfg);
			default -> List.of();
		};
	}
}
