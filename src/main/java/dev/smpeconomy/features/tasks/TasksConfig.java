package dev.smpeconomy.features.tasks;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import dev.smpeconomy.core.ConfigManager;
import dev.smpeconomy.core.CrateKeys;
import dev.smpeconomy.core.Money;

/**
 * {@code config/smpeconomy/tasks.json}: task pool, slots per day (difficulties in order) and the bonus for finishing
 * all of them. Amounts in cents.
 * <p>Stat references ({@link TaskDef#stats}): {@code <type>:<id>} or {@code <type>:#<tag>}, types as in vanilla stats:
 * {@code mined} (blocks), {@code crafted}, {@code used}, {@code broken} (items), {@code killed}, {@code killed_by}
 * (entities), {@code custom} (e.g. {@code custom:jump}, {@code custom:walk_one_cm}). The namespace is optional ({@code
 * minecraft}). Progress is the sum of all stat differences since assignment, divided by {@link TaskDef#divisor} (e.g.
 * 100 for cm to blocks).
 */
public final class TasksConfig {
	/** GUI space. */
	public static final int MAX_SLOTS = 5;
	/** Against config typos: $100,000. */
	public static final long MAX_REWARD = 10_000_000L;
	/** Also applies to the admin command. */
	public static final long MAX_AMOUNT = 10_000_000L;
	static final Pattern ID = Pattern.compile("[a-z0-9_]{1,48}");
	/**
	 * Allowed stat types. No {@code picked_up}/{@code dropped}: dropping and picking up the same stack raises both at
	 * will, which would be a free reward. Use {@code crafted} only for irreversible recipes.
	 */
	static final Set<String> STAT_TYPES = Set.of("mined", "crafted", "used", "broken", "killed", "killed_by", "custom");

	/** Difficulty per daily slot, in display order. */
	public List<String> slots = new ArrayList<>(List.of("easy", "medium", "hard"));
	/** In cents. */
	public long bonusMoney = 20_000;
	public String bonusKeyTier = CrateKeys.COMMON;
	public int bonusKeys = 1;
	/** Off by default: progress made there gets swallowed. */
	public boolean countInCreative = false;
	public List<TaskDef> tasks = new ArrayList<>();

	public static final class TaskDef {
		public String id = "";
		public String name = "";
		public String description = "";
		public String icon = "minecraft:paper";
		/** For variety: different categories per day where possible. */
		public String category = "misc";
		/** A slot name from {@link TasksConfig#slots}. */
		public String difficulty = "easy";
		public List<String> stats = new ArrayList<>();
		/** Raw value per display unit (e.g. 100 cm = 1 block). */
		public int divisor = 1;
		/** Target amount, picked deterministically from min, min+step, …, max. */
		public long min = 1;
		public long max = 1;
		public long step = 1;
		/** In cents for {@code min}; larger targets scale linearly, rounded to whole dollars. */
		public long reward = 10_000;
		public int weight = 1;
		public boolean enabled = true;

		public int amountChoices() {
			return (int) Math.min(10_000, (max - min) / step + 1);
		}

		public long amount(int index) {
			return Math.min(max, min + (long) index * step);
		}

		public String describe(long amount) {
			return description.replace("{n}", dev.smpeconomy.core.Msg.number(amount));
		}
	}

	/** In cents: linear to {@code reward}/{@code min}, rounded to whole dollars, at least $1. */
	public static long rewardFor(TaskDef def, long amount) {
		double exact = (double) def.reward * Math.max(1, amount) / Math.max(1, def.min);
		long dollars = Math.round(exact / 100.0);
		return Math.max(100, Math.min(MAX_REWARD, dollars * 100));
	}

	public TaskDef find(String id) {
		for (TaskDef d : tasks) {
			if (d.id.equals(id)) return d;
		}
		return null;
	}

		public static String difficultyLabel(String difficulty) {
		return switch (difficulty == null ? "" : difficulty) {
			case "easy" -> "§aEasy";
			case "medium" -> "§eMedium";
			case "hard" -> "§cHard";
			default -> "§b" + capitalize(difficulty);
		};
	}

	/**
	 * Default category IDs have fixed labels (e.g. {@code mining} shows as "Mining"); custom IDs get capitalized.
	 */
	public static String categoryLabel(String category) {
		return switch (category == null ? "" : category) {
			case "mining" -> "Mining";
			case "ores" -> "Ores";
			case "wood" -> "Wood";
			case "digging" -> "Digging";
			case "combat" -> "Combat";
			case "hunting" -> "Hunting";
			case "fishing" -> "Fishing";
			case "movement" -> "Movement";
			case "farm" -> "Farming";
			case "crafting" -> "Crafting";
			case "nether" -> "Nether";
			case "trading" -> "Trading";
			case "magic" -> "Magic";
			case "misc" -> "Misc";
			default -> capitalize(category);
		};
	}

	public static String capitalize(String s) {
		if (s == null || s.isEmpty()) return "";
		return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
	}

	/**
	 * Structural check, throws {@link IllegalArgumentException}; on reload the previous state stays active. Unknown
	 * block, item and entity IDs aren't errors: a task only gets skipped when none of its stats resolves (see {@link
	 * StatRefs}), so a Minecraft update can't prevent server start.
	 */
	public static void validate(TasksConfig c) {
		if (c.slots == null || c.slots.isEmpty()) throw new IllegalArgumentException("slots is empty");
		if (c.slots.size() > MAX_SLOTS) throw new IllegalArgumentException("at most " + MAX_SLOTS + " slots");
		for (String s : c.slots) {
			if (s == null || !ID.matcher(s).matches()) throw new IllegalArgumentException("invalid slot: " + s);
		}
		if (c.bonusMoney < 0 || c.bonusMoney > Money.MAX) throw new IllegalArgumentException("bonusMoney invalid");
		if (c.bonusKeys < 0 || c.bonusKeys > 64) throw new IllegalArgumentException("bonusKeys must be 0..64");
		if (c.bonusKeyTier == null || !CrateKeys.TIERS.contains(c.bonusKeyTier))
			throw new IllegalArgumentException("bonusKeyTier must be one of " + CrateKeys.TIERS + ": " + c.bonusKeyTier);
		if (c.tasks == null || c.tasks.isEmpty()) throw new IllegalArgumentException("no tasks in the pool");
		Set<String> ids = new HashSet<>();
		for (TaskDef d : c.tasks) {
			if (d == null) throw new IllegalArgumentException("empty entry in tasks");
			if (d.id == null || !ID.matcher(d.id).matches()) throw new IllegalArgumentException("invalid task ID: " + d.id);
			if (!ids.add(d.id)) throw new IllegalArgumentException("duplicate task ID: " + d.id);
			String where = "Task " + d.id + ": ";
			if (d.name == null || d.name.isBlank()) throw new IllegalArgumentException(where + "name missing");
			if (d.description == null || d.description.isBlank()) throw new IllegalArgumentException(where + "description missing");
			if (d.icon == null) d.icon = "minecraft:paper";
			if (d.category == null || d.category.isBlank()) d.category = "misc";
			if (d.difficulty == null || !ID.matcher(d.difficulty).matches()) throw new IllegalArgumentException(where + "difficulty invalid");
			if (d.min < 1 || d.max < d.min || d.max > MAX_AMOUNT) throw new IllegalArgumentException(where + "min/max invalid");
			if (d.step < 1) throw new IllegalArgumentException(where + "step must be ≥ 1");
			if (d.divisor < 1) throw new IllegalArgumentException(where + "divisor must be ≥ 1");
			if (d.reward < 1 || d.reward > MAX_REWARD) throw new IllegalArgumentException(where + "reward must be 1.." + MAX_REWARD + " cents");
			if (d.weight < 1) throw new IllegalArgumentException(where + "weight must be ≥ 1");
			if (d.stats == null || d.stats.isEmpty()) throw new IllegalArgumentException(where + "stats is empty");
			for (String ref : d.stats) StatRefs.parse(ref); // throws on syntax errors or an unknown type
		}
	}

	static final ConfigManager.Config<TasksConfig> CONFIG = ConfigManager.registerResource("tasks", TasksConfig.class, "tasks.json", cfg -> {
		validate(cfg);
		StatRefs.clearCache();
	});

	static TasksConfig get() {
		return CONFIG.get();
	}

	static void bootstrap() {
		CONFIG.name();
	}
}
