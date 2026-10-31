package dev.smpeconomy.features.minigames.mega;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.MobCategory;
import dev.smpeconomy.core.Money;

/**
 * {@code megaChallenge} section of {@code config/smpeconomy/minigames.json}. Money in cents.
 * <p>Answer challenges score only the first attempt per player and question; further attempts get a private hint.
 * Races count only growth since the start (stat deltas), never inventory, never blocks in a Veinminer group or
 * blocks that can be placed and mined again.
 */
public final class MegaConfig {
	public boolean enabled = true;
	/**
	 * The next one comes {@code intervalMinutes} after the last start (stored in {@code megachallenge.json},
	 * survives restarts), or after server start if there was none.
	 */
	public int intervalMinutes = 30;
	/** After a restart, let people log in first. */
	public int minMinutesAfterStart = 10;
	public int minPlayers = 1;
	public int announceSeconds = 60;
	/** No chat game multiplier. */
	public long reward = 1_000_000;
	public double epicKeyChance = 0.05;
	/**
	 * true: prize from the treasury (capped; what it can't pay is paid later). false: new money, like chat games.
	 */
	public boolean rewardsFromTreasury = false;
	public int answerSeconds = 240;
	/**
	 * Answers only count after a random secret wait between {@code minAnswerDelaySeconds} and {@code
	 * maxAnswerDelaySeconds} (never shown); too-early answers use an attempt. Against bots and macros firing at a
	 * fixed time.
	 */
	public int minAnswerDelaySeconds = 12;
	public int maxAnswerDelaySeconds = 30;
	/**
	 * At most one answer per player in this many seconds. Off by default, since only the first attempt counts
	 * anyway.
	 */
	public int guessCooldownSeconds = 0;
	/**
	 * Attempts per player on number answers, 0 = unlimited. Default 1: only the first counts, further ones get a
	 * private hint, so nobody can guess in series.
	 */
	public int maxGuesses = 1;
	/**
	 * Attempts on text answers (names, riddles, word scramble), 0 = unlimited. Only messages that are exactly one
	 * known term count, so normal chat uses no attempt.
	 */
	public int maxTextGuesses = 1;
	/**
	 * Minimum playtime in minutes to take part, 0 = off. Stops fresh alts guessing along, and also works behind the
	 * playit tunnel where the server sees no real IPs.
	 */
	public int minPlaytimeMinutes = 60;
	/**
	 * One account per public IP takes part, the one with the most playtime. Tunnel, Docker, LAN and loopback
	 * addresses count as unknown and never as equal, so behind playit without PROXY protocol this does nothing and
	 * {@link #minPlaytimeMinutes} protects instead.
	 */
	public boolean onePerIp = true;
	public int hintSeconds = 45;
	/** Next normal chat game comes no earlier than this after a MEGA-CHALLENGE. */
	public int chatGamePauseMinutes = 5;
	/**
	 * Chance (0-1) that a scheduled challenge becomes a BONUS question: always an answer challenge, never a race,
	 * for {@link #bonusReward}.
	 */
	public double bonusChance = 0.1;
	public long bonusReward = 2_000_000;
	/** math, trivia, race, riddle, unscramble */
	public Map<String, Integer> weights = defaultWeights();
	public Map<String, Integer> mathWeights = defaultMathWeights();
	public Map<String, Integer> triviaWeights = defaultTriviaWeights();
	public List<Race> races = defaultRaces();

	/**
	 * First to reach the goal since the start wins, online at start. Only someone who moved or looked around in the
	 * last minute can win (no AFK farms with macros).
	 * <ul>
	 * <li>{@code mine}: {@code target} is block ids separated by commas or {@code #tag}. Counts growth of the mined
	 * stat, capped by own counting of natural breaks (no placed blocks, right tool, no creative, no Veinminer
	 * follow-ups). Use only blocks outside any Veinminer group that drop something else when mined (stone to
	 * cobblestone), so they can't be placed again.</li>
	 * <li>{@code kill}: growth of the killed stat, capped by own counting at death (kill credit as in vanilla;
	 * optionally only in {@code dimension}, with {@code night} only at night). {@code target} is entity ids or
	 * {@code category:monster}.</li>
	 * <li>{@code stat}: general stat, e.g. {@code minecraft:sprint_one_cm}; {@code scale} divides raw values. Only
	 * movement stats by default, since fishing, breeding, trading and mob kills are won by existing AFK farms.</li>
	 * <li>{@code craft} and {@code portal}: for custom configs only. Ingredients in chests or a portal next to a
	 * base decide these before the start.</li>
	 * </ul>
	 * Inventory never counts: stock from chests or other players would be "collected" in seconds.
	 */
	public static final class Race {
		public String id = "";
		public String label = "";
		public String kind = "mine";
		public String target = "";
		/** {@code kill} only: counts only in this dimension (empty = everywhere). */
		public String dimension = "";
		/**
		 * {@code kill} only: picked only if enough overworld night is left; kills count only at night and sunrise
		 * (also from sleeping) ends the race.
		 */
		public boolean night = false;
		public int amount = 1;
		/** {@code stat} only: raw value / scale = progress (100: centimetres to blocks). */
		public int scale = 1;
		public int minutes = 10;
		public int weight = 1;

		public Race() {}

		public Race(String id, String label, String kind, String target, int amount, int minutes, int weight) {
			this.id = id;
			this.label = label;
			this.kind = kind;
			this.target = target;
			this.amount = amount;
			this.minutes = minutes;
			this.weight = weight;
		}

		public Race in(String dimension) {
			this.dimension = dimension;
			return this;
		}

		public Race atNight() {
			this.night = true;
			return this;
		}

		public Race scaled(int scale) {
			this.scale = scale;
			return this;
		}
	}

	public static final int MAX_PLAYTIME_MINUTES = 7 * 24 * 60;

	public static final Set<String> RACE_KINDS = Set.of("craft", "mine", "kill", "stat", "portal");
	public static final String CATEGORY_PREFIX = "category:";

	static Map<String, Integer> defaultWeights() {
		Map<String, Integer> m = new LinkedHashMap<>();
		m.put("math", 15);
		m.put("trivia", 15);
		m.put("race", 50);
		m.put("riddle", 20);
		m.put("unscramble", 0);
		return m;
	}

	private static Map<String, Integer> defaultMathWeights() {
		Map<String, Integer> m = new LinkedHashMap<>();
		for (String k : MegaMath.KINDS) m.put(k, k.equals("divisors") ? 2 : 1);
		return m;
	}

	private static Map<String, Integer> defaultTriviaWeights() {
		Map<String, Integer> m = new LinkedHashMap<>();
		for (String k : MegaTrivia.KINDS) m.put(k, 1);
		return m;
	}

	/**
	 * Only growth since the start, never inventory, no Veinminer groups, no blocks that can be placed again.
	 * Deliberately excluded: crafting (ingredients from chests), ores (Veinminer group), nether portal (standing
	 * next to one wins), furnace results, pickup, fishing, breeding and trading (AFK farms, trading halls, pens).
	 */
	static List<Race> defaultRaces() {
		List<Race> l = new ArrayList<>();
		l.add(new Race("stone", "Mine 100 Stone or Deepslate", "mine", "minecraft:stone,minecraft:deepslate", 100, 12, 4));
		l.add(new Race("deepslate", "Mine 64 Deepslate", "mine", "minecraft:deepslate", 64, 10, 1));
		l.add(new Race("sprint", "Sprint 1,000 blocks", "stat", "minecraft:sprint_one_cm", 1000, 8, 3).scaled(100));
		l.add(new Race("night_hunt", "Kill 10 hostile mobs before sunrise (Overworld)", "kill", CATEGORY_PREFIX + "monster", 10, 7, 6)
			.in("minecraft:overworld").atNight());
		l.add(new Race("endermen", "Kill 3 Endermen in the Overworld", "kill", "minecraft:enderman", 3, 15, 1).in("minecraft:overworld"));
		return l;
	}

	/** Throws on invalid values, so the old config stays active. */
	public static void validate(MegaConfig c) {
		if (c == null) throw new IllegalArgumentException("megaChallenge missing");
		if (c.intervalMinutes < 10 || c.intervalMinutes > 7 * 24 * 60) throw new IllegalArgumentException("megaChallenge.intervalMinutes 10–10080");
		if (c.minMinutesAfterStart < 0 || c.minMinutesAfterStart > c.intervalMinutes) throw new IllegalArgumentException("megaChallenge.minMinutesAfterStart 0–intervalMinutes");
		if (c.minPlayers < 1) throw new IllegalArgumentException("megaChallenge.minPlayers >= 1");
		if (c.announceSeconds < 0 || c.announceSeconds > 600) throw new IllegalArgumentException("megaChallenge.announceSeconds 0–600");
		if (c.reward < 0 || c.reward > Money.MAX) throw new IllegalArgumentException("megaChallenge.reward invalid");
		if (!(c.epicKeyChance >= 0 && c.epicKeyChance <= 1)) throw new IllegalArgumentException("megaChallenge.epicKeyChance 0–1");
		if (c.answerSeconds < 30 || c.answerSeconds > 1800) throw new IllegalArgumentException("megaChallenge.answerSeconds 30–1800");
		if (c.minAnswerDelaySeconds < 0 || c.maxAnswerDelaySeconds < c.minAnswerDelaySeconds || c.maxAnswerDelaySeconds > c.answerSeconds - 20) {
			throw new IllegalArgumentException("megaChallenge: 0 <= minAnswerDelaySeconds <= maxAnswerDelaySeconds <= answerSeconds - 20");
		}
		if (c.minPlaytimeMinutes < 0 || c.minPlaytimeMinutes > MAX_PLAYTIME_MINUTES) throw new IllegalArgumentException("megaChallenge.minPlaytimeMinutes 0–" + MAX_PLAYTIME_MINUTES);
		if (c.guessCooldownSeconds < 0 || c.guessCooldownSeconds > 60) throw new IllegalArgumentException("megaChallenge.guessCooldownSeconds 0–60");
		if (c.maxGuesses < 0 || c.maxTextGuesses < 0) throw new IllegalArgumentException("megaChallenge.maxGuesses/maxTextGuesses >= 0");
		if (c.hintSeconds < 10) throw new IllegalArgumentException("megaChallenge.hintSeconds >= 10");
		if (c.chatGamePauseMinutes < 0) throw new IllegalArgumentException("megaChallenge.chatGamePauseMinutes >= 0");
		if (!(c.bonusChance >= 0 && c.bonusChance <= 1)) throw new IllegalArgumentException("megaChallenge.bonusChance 0–1");
		if (c.bonusReward < 0 || c.bonusReward > Money.MAX) throw new IllegalArgumentException("megaChallenge.bonusReward invalid");
		if (c.weights == null) c.weights = defaultWeights();
		if (c.mathWeights == null) c.mathWeights = defaultMathWeights();
		if (c.triviaWeights == null) c.triviaWeights = defaultTriviaWeights();
		checkWeights("weights", c.weights, MegaChallenge.Type.ids());
		checkWeights("mathWeights", c.mathWeights, MegaMath.KINDS);
		checkWeights("triviaWeights", c.triviaWeights, MegaTrivia.KINDS);
		if (c.races == null) c.races = new ArrayList<>();
		Set<String> ids = new HashSet<>();
		for (Race r : c.races) {
			if (r == null || r.id == null || !r.id.matches("[a-z0-9_]{1,32}")) throw new IllegalArgumentException("megaChallenge.races: invalid id " + (r == null ? null : r.id));
			if (!ids.add(r.id)) throw new IllegalArgumentException("megaChallenge.races: duplicate id: " + r.id);
			if (r.label == null || r.label.isBlank()) throw new IllegalArgumentException("megaChallenge.races." + r.id + ": label missing");
			r.kind = r.kind == null ? "" : r.kind.toLowerCase(Locale.ROOT);
			if (r.kind.equals("obtain")) {
				throw new IllegalArgumentException("megaChallenge.races." + r.id + ": kind obtain no longer exists (stock from chests or other players"
					+ " would be collected instantly by dropping and picking up), use kill/mine");
			}
			if (!RACE_KINDS.contains(r.kind)) throw new IllegalArgumentException("megaChallenge.races." + r.id + ": kind " + r.kind + " unknown " + RACE_KINDS);
			if (r.amount < 1 || r.amount > 100_000) throw new IllegalArgumentException("megaChallenge.races." + r.id + ": amount 1–100000");
			if (r.scale < 1 || r.scale > 100_000) throw new IllegalArgumentException("megaChallenge.races." + r.id + ": scale 1–100000");
			if (r.scale != 1 && !r.kind.equals("stat")) throw new IllegalArgumentException("megaChallenge.races." + r.id + ": scale only with stat");
			if (r.night && !r.kind.equals("kill")) throw new IllegalArgumentException("megaChallenge.races." + r.id + ": night only with kill");
			if (r.minutes < 1 || r.minutes > 120) throw new IllegalArgumentException("megaChallenge.races." + r.id + ": minutes 1–120");
			if (r.weight < 0) throw new IllegalArgumentException("megaChallenge.races." + r.id + ": weight >= 0");
			if (r.dimension == null) r.dimension = "";
			r.dimension = r.dimension.trim();
			if (!r.dimension.isEmpty() && (!r.kind.equals("kill") || Identifier.tryParse(r.dimension) == null)) {
				throw new IllegalArgumentException("megaChallenge.races." + r.id + ": dimension only with kill and as an id (e.g. minecraft:overworld)");
			}
			String problem = targetProblem(r);
			if (problem != null) throw new IllegalArgumentException("megaChallenge.races." + r.id + ": " + problem);
		}
	}

	private static void checkWeights(String name, Map<String, Integer> weights, List<String> known) {
		int total = 0;
		for (Map.Entry<String, Integer> e : weights.entrySet()) {
			if (!known.contains(e.getKey())) throw new IllegalArgumentException("megaChallenge." + name + ": unknown " + e.getKey() + " (allowed: " + known + ")");
			if (e.getValue() == null || e.getValue() < 0) throw new IllegalArgumentException("megaChallenge." + name + "." + e.getKey() + " >= 0");
			total += e.getValue();
		}
		if (total <= 0) throw new IllegalArgumentException("megaChallenge." + name + ": at least one weight > 0");
	}

	static MobCategory category(String part) {
		String name = part.trim().substring(CATEGORY_PREFIX.length()).toLowerCase(Locale.ROOT);
		for (MobCategory mc : MobCategory.values()) {
			if (mc.getSerializedName().equals(name)) return mc;
		}
		return null;
	}

	/** null if ok, otherwise the error text. */
	static String targetProblem(Race r) {
		String t = r.target == null ? "" : r.target.trim();
		if (t.isEmpty()) return "target missing";
		switch (r.kind) {
			case "craft" -> {
				Identifier id = Identifier.tryParse(t);
				if (id == null || !BuiltInRegistries.ITEM.containsKey(id) || id.getPath().equals("air")) return "unknown item: " + t;
			}
			case "kill" -> {
				for (String part : t.split(",")) {
					String p = part.trim();
					if (p.startsWith(CATEGORY_PREFIX)) {
						if (category(p) == null) return "unknown mob category: " + p + " (e.g. " + CATEGORY_PREFIX + "monster)";
						continue;
					}
					Identifier id = Identifier.tryParse(p);
					if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) return "unknown mob: " + p;
				}
			}
			case "stat" -> {
				Identifier id = Identifier.tryParse(t);
				if (id == null || !BuiltInRegistries.CUSTOM_STAT.containsKey(id)) return "unknown stat: " + t;
			}
			case "mine" -> {
				for (String part : t.split(",")) {
					String p = part.trim();
					if (p.startsWith("#")) {
						Identifier id = Identifier.tryParse(p.substring(1));
						if (id == null) return "invalid block tag: " + p;
						// tags may not be bound yet when the config loads, so only check syntax
						TagKey.create(Registries.BLOCK, id);
					} else {
						Identifier id = Identifier.tryParse(p);
						if (id == null || !BuiltInRegistries.BLOCK.containsKey(id) || id.getPath().equals("air")) return "unknown block: " + p;
					}
				}
			}
			case "portal" -> {
				Identifier id = Identifier.tryParse(t);
				if (id == null) return "invalid dimension: " + t;
			}
			default -> {
				return "kind unknown";
			}
		}
		return null;
	}
}
