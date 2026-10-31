package dev.smpeconomy.features.jobs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import dev.smpeconomy.core.compat.Mc;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.ConfigManager;

/**
 * {@code config/smpeconomy/jobs.json}. Raw Gson data plus an immutable compiled index ({@link Compiled}) for fast
 * lookups. Amounts are in cents and may be fractional, XP is whole. Keys are IDs ({@code minecraft:stone}) or tags
 * ({@code #minecraft:logs}).
 */
public final class JobsConfig {
	/** These end up in players.json, hooks and other features. */
	public static final String ID_PATTERN = "[a-z0-9_]{1,32}";
	/** Per-action cap against config typos: $1,000. */
	public static final double MAX_MONEY_CENTS = 100_000;
	public static final int MAX_XP = 100_000;

	public int switchCooldownMinutes = 30;
	public int maxLevel = 50;
	public double levelBonusPercent = 2.0;
	public double xpBase = 50.0;
	public double xpExponent = 1.35;
	public int payoutIntervalSeconds = 5;
	public double spawnerMobFactor = 0.25;
	public double spawnEggMobFactor = 0.0;
	/**
	 * Bred or multiplied mobs (shulker splitting, hoglin offspring, converted bred villagers): pay times this factor.
	 */
	public double bredMobFactor = 0.0;
	/** Mobs summoned by other mobs (evoker vexes): pay times this factor. */
	public double summonedMobFactor = 0.0;
	/**
	 * Respawned ender dragons (4 end crystals, every ingredient is in the shop): pay times this factor. Separately
	 * capped at {@link SummonCosts#RESPAWN_MAX_SHARE} of the crystals' minimum system cost ({@link
	 * SummonCosts#respawnedDragonCapMilli}).
	 */
	public double respawnedDragonFactor = 0.0;
	/** Lets Carpet's {@code /player} bots earn job pay. Off, because one bot on a mining farm prints money all night. */
	public boolean botsEarn = false;
	public List<JobEntry> jobs = new ArrayList<>();

	public static final class JobEntry {
		public String id;
		public String name;
		public String color = "§e";
		public String icon = "minecraft:paper";
		public List<String> description = new ArrayList<>();
		public List<String> examples = new ArrayList<>();
		public Map<String, RewardEntry> blocks = new LinkedHashMap<>();
		public Map<String, RewardEntry> mobs = new LinkedHashMap<>();
		public RewardEntry mobDefault;
		public Map<String, RewardEntry> fish = new LinkedHashMap<>();
		public RewardEntry fishDefault;
	}

	public static final class RewardEntry {
		public double money;
		public int xp;
		public boolean mature;
	}

	/** {@code money} is in cents and may be fractional; {@code key} as written in the config. */
	public record Reward(String key, double money, int xp, boolean mature) {}

	public record Example(Component name, Reward reward) {}

	private record TagReward<T>(TagKey<T> tag, Reward reward) {}

	public static final class Job {
		public final String id;
		public final String name;
		public final String color;
		public final Item icon;
		public final List<String> description;
		private final Map<Block, Reward> blocks;
		private final List<TagReward<Block>> blockTags;
		private final Map<EntityType<?>, Reward> mobs;
		private final List<TagReward<EntityType<?>>> mobTags;
		private final Reward mobDefault;
		private final Map<Item, Reward> fish;
		private final List<TagReward<Item>> fishTags;
		private final Reward fishDefault;
		private final List<String> exampleKeys;

		private Job(String id, String name, String color, Item icon, List<String> description, Map<Block, Reward> blocks,
			List<TagReward<Block>> blockTags, Map<EntityType<?>, Reward> mobs, List<TagReward<EntityType<?>>> mobTags, Reward mobDefault,
			Map<Item, Reward> fish, List<TagReward<Item>> fishTags, Reward fishDefault, List<String> exampleKeys) {
			this.id = id;
			this.name = name;
			this.color = color;
			this.icon = icon;
			this.description = description;
			this.blocks = blocks;
			this.blockTags = blockTags;
			this.mobs = mobs;
			this.mobTags = mobTags;
			this.mobDefault = mobDefault;
			this.fish = fish;
			this.fishTags = fishTags;
			this.fishDefault = fishDefault;
			this.exampleKeys = exampleKeys;
		}

		public String display() {
			return color + name;
		}

		/** Reward for this block, ignoring age and placed checks; null if none. */
		public Reward block(BlockState state) {
			Reward r = blocks.get(state.getBlock());
			if (r != null) return r;
			for (TagReward<Block> t : blockTags) {
				if (state.is(t.tag())) return t.reward();
			}
			return null;
		}

		/** {@code enemy} means hostile, which is when {@code mobDefault} applies. */
		public Reward mob(EntityType<?> type, boolean enemy) {
			Reward r = mobs.get(type);
			if (r != null) return r;
			for (TagReward<EntityType<?>> t : mobTags) {
				if (Mc.is(type, t.tag())) return t.reward();
			}
			return enemy ? mobDefault : null;
		}

		/** Reward for a catch ({@code fishDefault} if the job fishes), else null. */
		public Reward fish(ItemStack stack) {
			if (stack == null || stack.isEmpty()) return null;
			Reward r = fish.get(stack.getItem());
			if (r != null) return r;
			for (TagReward<Item> t : fishTags) {
				if (stack.is(t.tag())) return t.reward();
			}
			return fishDefault;
		}

		public static boolean needsMaturity(Reward r, BlockState state) {
			return r.mature() || state.getBlock() instanceof CropBlock;
		}

		/** From "examples", otherwise the best paid entries. */
		public List<Example> examples(int max) {
			List<Example> out = new ArrayList<>();
			for (String key : exampleKeys) {
				Example e = example(key);
				if (e != null) out.add(e);
				if (out.size() >= max) return out;
			}
			if (!out.isEmpty()) return out;
			List<Example> all = new ArrayList<>();
			blocks.forEach((b, r) -> all.add(new Example(b.getName(), r)));
			mobs.forEach((t, r) -> all.add(new Example(t.getDescription().copy(), r)));
			fish.forEach((i, r) -> all.add(new Example(Mc.itemName(i).copy(), r)));
			all.sort(Comparator.comparingDouble((Example e) -> e.reward().money()).reversed());
			return all.subList(0, Math.min(max, all.size()));
		}

		private Example example(String key) {
			Identifier id = Identifier.tryParse(key);
			if (id == null) return null;
			if (BuiltInRegistries.BLOCK.containsKey(id)) {
				Block b = BuiltInRegistries.BLOCK.getValue(id);
				Reward r = block(b.defaultBlockState());
				if (r != null) return new Example(b.getName(), r);
			}
			if (BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
				EntityType<?> t = BuiltInRegistries.ENTITY_TYPE.getValue(id);
				Reward r = mob(t, false);
				if (r != null) return new Example(t.getDescription().copy(), r);
			}
			if (BuiltInRegistries.ITEM.containsKey(id)) {
				Item i = BuiltInRegistries.ITEM.getValue(id);
				Reward r = fish.get(i);
				if (r != null) return new Example(Mc.itemName(i).copy(), r);
			}
			return null;
		}

		public List<Example> allRewards() {
			List<Example> all = new ArrayList<>();
			blocks.forEach((b, r) -> all.add(new Example(b.getName(), r)));
			blockTags.forEach(t -> all.add(new Example(Component.literal(tagLabel(t.tag().location().getPath())), t.reward())));
			mobs.forEach((t, r) -> all.add(new Example(t.getDescription().copy(), r)));
			mobTags.forEach(t -> all.add(new Example(Component.literal(tagLabel(t.tag().location().getPath())), t.reward())));
			if (mobDefault != null) all.add(new Example(Component.literal("Other hostile mobs"), mobDefault));
			fish.forEach((i, r) -> all.add(new Example(Mc.itemName(i).copy(), r)));
			fishTags.forEach(t -> all.add(new Example(Component.literal(tagLabel(t.tag().location().getPath())), t.reward())));
			if (fishDefault != null) all.add(new Example(Component.literal("Other catches"), fishDefault));
			all.sort(Comparator.comparingDouble((Example e) -> e.reward().money()).reversed());
			return all;
		}

		static String tagLabel(String path) {
			return switch (path) {
				case "logs", "logs_that_burn" -> "All logs";
				case "base_stone_overworld" -> "Stone (all types)";
				case "base_stone_nether" -> "Nether stone (all types)";
				case "dirt" -> "Dirt (all types)";
				case "sand" -> "Sand (all types)";
				case "crops" -> "Crops";
				case "leaves" -> "Leaves";
				case "skeletons" -> "Skeletons";
				case "zombies" -> "Zombies";
				case "undead" -> "Undead";
				case "raiders" -> "Raiders";
				case "arthropod" -> "Arthropods";
				case "fishes" -> "Fish";
				default -> "Group: " + path.replace('_', ' ');
			};
		}

		public int size() {
			return blocks.size() + blockTags.size() + mobs.size() + mobTags.size() + fish.size() + fishTags.size()
				+ (mobDefault != null ? 1 : 0) + (fishDefault != null ? 1 : 0);
		}
	}

	public static final class Compiled {
		public final JobsConfig raw;
		public final List<Job> jobs;
		public final List<String> warnings;
		private final Map<String, Job> byId;

		private Compiled(JobsConfig raw, List<Job> jobs, List<String> warnings) {
			this.raw = raw;
			this.jobs = List.copyOf(jobs);
			this.warnings = List.copyOf(warnings);
			Map<String, Job> map = new LinkedHashMap<>();
			for (Job j : jobs) map.put(j.id, j);
			this.byId = Collections.unmodifiableMap(map);
		}

		public Job job(String id) {
			return id == null ? null : byId.get(id);
		}

		/**
		 * What marker does a block need after growing from bone meal? Bit 1: crop with a ripeness check ({@link
		 * BoostedCrops}), bit 2: other paid block (PlacedBlockTracker), 0: none.
		 */
		public int growthKind(BlockState state) {
			int kind = 0;
			for (Job j : jobs) {
				Reward r = j.block(state);
				if (r == null) continue;
				kind |= Job.needsMaturity(r, state) ? 1 : 2;
			}
			return kind;
		}
	}

	/**
	 * Last successfully loaded index; a failed reload leaves it untouched. Declared before {@link #CONFIG} and without
	 * an initializer on purpose: registering calls {@link #onLoad} during class initialization.
	 */
	private static volatile Compiled compiled;
	static final ConfigManager.Config<JobsConfig> CONFIG =
		ConfigManager.registerResource("jobs", JobsConfig.class, "jobs.json", JobsConfig::onLoad);

	public static void bootstrap() {
		if (compiled == null || CONFIG.get() == null) throw new IllegalStateException("could not load jobs.json");
	}

	public static Compiled get() {
		return compiled;
	}

	public static JobsConfig raw() {
		return compiled.raw;
	}

	public static Job job(String id) {
		return compiled.job(id);
	}

	public static List<Job> jobs() {
		return compiled.jobs;
	}

	/** By ID or name, case-insensitive, umlauts also as ae/oe/ue; null if unknown. */
	public static Job find(String input) {
		if (input == null) return null;
		String needle = normalize(input);
		for (Job j : compiled.jobs) {
			if (j.id.equals(needle) || normalize(j.name).equals(needle)) return j;
		}
		return null;
	}

		public static String normalize(String s) {
		return s.trim().toLowerCase(java.util.Locale.ROOT);
	}

	private static void onLoad(JobsConfig cfg) {
		compiled = compile(cfg);
		for (String w : compiled.warnings) SmpEconomy.LOG.warn("[SMPEco] jobs.json: {}", w);
	}

	/** Throws IllegalArgumentException on invalid values; the previous state then stays active. */
	public static Compiled compile(JobsConfig cfg) {
		if (cfg.jobs == null || cfg.jobs.isEmpty()) throw new IllegalArgumentException("no jobs defined");
		range(cfg.switchCooldownMinutes, 0, 7 * 24 * 60, "switchCooldownMinutes");
		range(cfg.maxLevel, 1, 1000, "maxLevel");
		range(cfg.levelBonusPercent, 0, 100, "levelBonusPercent");
		range(cfg.xpBase, 1, 1_000_000, "xpBase");
		range(cfg.xpExponent, 0, 5, "xpExponent");
		range(cfg.payoutIntervalSeconds, 1, 300, "payoutIntervalSeconds");
		range(cfg.spawnerMobFactor, 0, 1, "spawnerMobFactor");
		range(cfg.spawnEggMobFactor, 0, 1, "spawnEggMobFactor");
		range(cfg.bredMobFactor, 0, 1, "bredMobFactor");
		range(cfg.summonedMobFactor, 0, 1, "summonedMobFactor");
		range(cfg.respawnedDragonFactor, 0, 1, "respawnedDragonFactor");
		List<String> warnings = new ArrayList<>();
		List<Job> jobs = new ArrayList<>();
		Set<String> ids = new HashSet<>();
		Set<String> names = new HashSet<>();
		for (JobEntry e : cfg.jobs) {
			if (e == null) throw new IllegalArgumentException("empty job entry");
			if (e.id == null || !e.id.matches(ID_PATTERN)) throw new IllegalArgumentException("invalid job ID: " + e.id);
			if (!ids.add(e.id)) throw new IllegalArgumentException("duplicate job ID: " + e.id);
			if (e.name == null || e.name.isBlank()) throw new IllegalArgumentException("Job " + e.id + ": name missing");
			if (!names.add(normalize(e.name))) throw new IllegalArgumentException("duplicate job name: " + e.name);
			Map<Block, Reward> blocks = new IdentityHashMap<>();
			List<TagReward<Block>> blockTags = new ArrayList<>();
			Map<EntityType<?>, Reward> mobs = new IdentityHashMap<>();
			List<TagReward<EntityType<?>>> mobTags = new ArrayList<>();
			Map<Item, Reward> fish = new IdentityHashMap<>();
			List<TagReward<Item>> fishTags = new ArrayList<>();
			String where = "Job " + e.id;
			if (e.blocks != null) {
				for (Map.Entry<String, RewardEntry> en : e.blocks.entrySet()) {
					Reward r = reward(en.getKey(), en.getValue(), where);
					String key = en.getKey();
					if (key.startsWith("#")) {
						Identifier tag = Identifier.tryParse(key.substring(1));
						if (tag == null) warnings.add(where + ": invalid tag " + key);
						else blockTags.add(new TagReward<>(Mc.configTag(Registries.BLOCK, tag), r));
					} else {
						Identifier id = Identifier.tryParse(key);
						if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) warnings.add(where + ": unknown block " + key);
						else blocks.put(BuiltInRegistries.BLOCK.getValue(id), r);
					}
				}
			}
			if (e.mobs != null) {
				for (Map.Entry<String, RewardEntry> en : e.mobs.entrySet()) {
					Reward r = reward(en.getKey(), en.getValue(), where);
					String key = en.getKey();
					if (key.startsWith("#")) {
						Identifier tag = Identifier.tryParse(key.substring(1));
						if (tag == null) warnings.add(where + ": invalid tag " + key);
						else mobTags.add(new TagReward<>(TagKey.create(Registries.ENTITY_TYPE, tag), r));
					} else {
						Identifier id = Identifier.tryParse(key);
						if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) warnings.add(where + ": unknown mob " + key);
						else mobs.put(BuiltInRegistries.ENTITY_TYPE.getValue(id), r);
					}
				}
			}
			if (e.fish != null) {
				for (Map.Entry<String, RewardEntry> en : e.fish.entrySet()) {
					Reward r = reward(en.getKey(), en.getValue(), where);
					String key = en.getKey();
					if (key.startsWith("#")) {
						Identifier tag = Identifier.tryParse(key.substring(1));
						if (tag == null) warnings.add(where + ": invalid tag " + key);
						else fishTags.add(new TagReward<>(TagKey.create(Registries.ITEM, tag), r));
					} else {
						Identifier id = Identifier.tryParse(key);
						Item item = id == null || !BuiltInRegistries.ITEM.containsKey(id) ? null : BuiltInRegistries.ITEM.getValue(id);
						if (item == null || item == net.minecraft.world.item.Items.AIR) warnings.add(where + ": unknown item " + key);
						else fish.put(item, r);
					}
				}
			}
			Reward mobDefault = e.mobDefault == null ? null : reward("mobDefault", e.mobDefault, where);
			Reward fishDefault = e.fishDefault == null ? null : reward("fishDefault", e.fishDefault, where);
			Item icon = net.minecraft.world.item.Items.PAPER;
			Identifier iconId = e.icon == null ? null : Identifier.tryParse(e.icon);
			if (iconId != null && BuiltInRegistries.ITEM.containsKey(iconId)) icon = BuiltInRegistries.ITEM.getValue(iconId);
			else warnings.add(where + ": unknown icon " + e.icon);
			String color = e.color == null || e.color.isBlank() ? "§e" : e.color;
			List<String> description = e.description == null ? List.of() : List.copyOf(e.description);
			List<String> examples = e.examples == null ? List.of() : List.copyOf(e.examples);
			Job job = new Job(e.id, e.name.trim(), color, icon, description, Collections.unmodifiableMap(blocks), List.copyOf(blockTags),
				Collections.unmodifiableMap(mobs), List.copyOf(mobTags), mobDefault, Collections.unmodifiableMap(fish), List.copyOf(fishTags),
				fishDefault, examples);
			if (job.size() == 0) warnings.add(where + ": no rewards");
			jobs.add(job);
		}
		return new Compiled(cfg, jobs, warnings);
	}

	private static Reward reward(String key, RewardEntry r, String where) {
		if (r == null) throw new IllegalArgumentException(where + ": reward for " + key + " missing");
		if (Double.isNaN(r.money) || r.money < 0 || r.money > MAX_MONEY_CENTS) throw new IllegalArgumentException(where + ": invalid amount for " + key);
		if (r.xp < 0 || r.xp > MAX_XP) throw new IllegalArgumentException(where + ": invalid XP for " + key);
		return new Reward(key, r.money, r.xp, r.mature);
	}

	private static void range(double v, double min, double max, String name) {
		if (Double.isNaN(v) || v < min || v > max) throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
	}
}
