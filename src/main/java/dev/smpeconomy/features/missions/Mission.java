package dev.smpeconomy.features.missions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatType;
import net.minecraft.stats.Stats;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import dev.smpeconomy.core.compat.Mc;
import dev.smpeconomy.core.Items;

/**
 * Validated, resolved mission (from a {@link MissionConfig.MissionDef}). Active missions hold their own copy of the
 * definition (snapshot) and recompile it on demand, so a reload doesn't change running missions.
 */
public final class Mission {
	public enum Type {
		/** Hand in items from the inventory (pristine rule), paid per piece (capped). */
		DELIVER("deliver", "Delivery"),
		/**
		 * Difference of vanilla stats since accepting (fishing, crafting); mining ({@code mined:}) counts individually
		 * broken natural blocks (see {@link MissionService#onBlockBroken}), hunting ({@code killed:}) individual kills
		 * by mob origin (see {@link MissionService#onMobKilled}).
		 */
		STAT("stat", "Stats"),
		/** Fishing treasure (name tag, saddle, nautilus shell, enchanted books, bows and rods). */
		TREASURE("treasure", "Fishing"),
		EXPLORE("explore", "Exploration");

		public final String id;
		public final String label;

		Type(String id, String label) {
			this.id = id;
			this.label = label;
		}

		static Optional<Type> byId(String id) {
			if (id == null) return Optional.empty();
			String key = id.trim().toLowerCase(Locale.ROOT);
			for (Type t : values()) {
				if (t.id.equals(key)) return Optional.of(t);
			}
			return Optional.empty();
		}
	}

	public final MissionConfig.MissionDef def;
	public final String id;
	public final Tier tier;
	public final Type type;
	public final Item item;
	public final List<Stat<?>> stats;
	public final Identifier dimension;
	public final Identifier biome;
	public final TagKey<Biome> biomeTag;
	public final Identifier structure;
	public final TagKey<Structure> structureTag;
	/**
	 * EXPLORE: target key from dimension/biome/structure, independent of the mission ID, e.g. {@code
	 * dim=*;biome=*;structure=minecraft:end_city}. Each target pays only once per player. Otherwise null.
	 */
	public final String exploreKey;
	public final Item icon;

	private Mission(MissionConfig.MissionDef def, Tier tier, Type type, Item item, List<Stat<?>> stats, Identifier dimension, Identifier biome,
			TagKey<Biome> biomeTag, Identifier structure, TagKey<Structure> structureTag, Item icon) {
		this.def = def;
		this.id = def.id;
		this.tier = tier;
		this.type = type;
		this.item = item;
		this.stats = stats;
		this.dimension = dimension;
		this.biome = biome;
		this.biomeTag = biomeTag;
		this.structure = structure;
		this.structureTag = structureTag;
		this.exploreKey = type == Type.EXPLORE ? "dim=" + (dimension == null ? "*" : dimension)
			+ ";biome=" + (biome != null ? biome.toString() : biomeTag != null ? "#" + biomeTag.location() : "*")
			+ ";structure=" + (structure != null ? structure.toString() : structureTag != null ? "#" + structureTag.location() : "*") : null;
		this.icon = icon;
	}

	/** Throws IllegalArgumentException with an English error message. */
	static Mission compile(MissionConfig.MissionDef def) {
		Tier tier = Tier.byId(def.tier).orElseThrow(() -> new IllegalArgumentException("unknown tier '" + def.tier + "'"));
		Type type = Type.byId(def.type).orElseThrow(() -> new IllegalArgumentException("unknown type '" + def.type + "'"));
		Item item = null;
		List<Stat<?>> stats = List.of();
		Identifier dimension = null;
		Identifier biome = null;
		TagKey<Biome> biomeTag = null;
		Identifier structure = null;
		TagKey<Structure> structureTag = null;
		switch (type) {
			case DELIVER -> item = Items.byId(def.item).orElseThrow(() -> new IllegalArgumentException("unknown item '" + def.item + "'"));
			case STAT -> {
				if (def.stats == null || def.stats.isEmpty()) throw new IllegalArgumentException("stats missing");
				List<Stat<?>> list = new ArrayList<>();
				for (String spec : def.stats) {
					Stat<?> stat = parseStat(spec).orElseThrow(() -> new IllegalArgumentException("unknown statistic '" + spec + "'"));
					if (!list.contains(stat)) list.add(stat);
				}
				stats = Collections.unmodifiableList(list);
			}
			case TREASURE -> {
			}
			case EXPLORE -> {
				if (blank(def.dimension) && blank(def.biome) && blank(def.structure)) throw new IllegalArgumentException("exploration target missing");
				if (!blank(def.dimension)) dimension = id(def.dimension, "dimension");
				if (!blank(def.biome)) {
					if (def.biome.startsWith("#")) biomeTag = TagKey.create(Registries.BIOME, id(def.biome.substring(1), "biome tag"));
					else biome = id(def.biome, "biome");
				}
				if (!blank(def.structure)) {
					if (def.structure.startsWith("#")) structureTag = TagKey.create(Registries.STRUCTURE, id(def.structure.substring(1), "structure tag"));
					else structure = id(def.structure, "structure");
				}
				if (def.amount != 1) def.amount = 1;
			}
		}
		Item icon = def.icon != null ? Items.byId(def.icon).orElse(null) : null;
		if (icon == null) icon = defaultIcon(type, item, stats);
		return new Mission(def, tier, type, item, stats, dimension, biome, biomeTag, structure, structureTag, icon);
	}

	private static boolean blank(String s) {
		return s == null || s.isBlank();
	}

	private static Identifier id(String raw, String what) {
		Identifier id = Identifier.tryParse(raw.trim());
		if (id == null) throw new IllegalArgumentException(what + " '" + raw + "' is not a valid ID");
		return id;
	}

	private static Item defaultIcon(Type type, Item item, List<Stat<?>> stats) {
		return switch (type) {
			case DELIVER -> item;
			case TREASURE -> net.minecraft.world.item.Items.FISHING_ROD;
			case EXPLORE -> net.minecraft.world.item.Items.COMPASS;
			case STAT -> {
				Stat<?> first = stats.isEmpty() ? null : stats.get(0);
				if (first == null) yield net.minecraft.world.item.Items.PAPER;
				if (first.getType() == Stats.ENTITY_KILLED) yield net.minecraft.world.item.Items.IRON_SWORD;
				if (first.getType() == Stats.BLOCK_MINED) yield net.minecraft.world.item.Items.IRON_PICKAXE;
				if (first.getType() == Stats.ITEM_CRAFTED) yield net.minecraft.world.item.Items.CRAFTING_TABLE;
				if (first.getType() == Stats.CUSTOM && Stats.FISH_CAUGHT.equals(first.getValue())) yield net.minecraft.world.item.Items.FISHING_ROD;
				yield net.minecraft.world.item.Items.PAPER;
			}
		};
	}

	/**
	 * Stat from {@code "type:object"}, e.g. {@code "killed:skeleton"}, {@code "mined:minecraft:diamond_ore"}, {@code
	 * "custom:fish_caught"}, {@code "crafted:bread"}; empty if unknown.
	 */
	static Optional<Stat<?>> parseStat(String spec) {
		if (spec == null) return Optional.empty();
		String s = spec.trim();
		int idx = s.indexOf(':');
		if (idx <= 0 || idx == s.length() - 1) return Optional.empty();
		Identifier typeId = Identifier.tryParse(s.substring(0, idx));
		Identifier objId = Identifier.tryParse(s.substring(idx + 1));
		if (typeId == null || objId == null || !BuiltInRegistries.STAT_TYPE.containsKey(typeId)) return Optional.empty();
		StatType<?> type = BuiltInRegistries.STAT_TYPE.getValue(typeId);
		return type == null ? Optional.empty() : resolve(type, objId);
	}

	private static <T> Optional<Stat<?>> resolve(StatType<T> type, Identifier id) {
		Registry<T> registry = type.getRegistry();
		if (!registry.containsKey(id)) return Optional.empty();
		T value = registry.getValue(id);
		if (value == null) return Optional.empty();
		return Optional.of(type.get(value));
	}

	/**
	 * Sum of this player's stats counted through vanilla: all EXCEPT mining ({@code mined:}) and hunting ({@code
	 * killed:}). Vanilla counters, never negative. Those two count only through individual events:
	 * <ul>
	 *   <li>Mining ({@link MissionService#onBlockBroken}): Veinminer raises {@code BLOCK_MINED} for whole chains whose
	 *   blocks it removes with no break event, self-placed ones too (silk touch ore, logs from the shop).</li>
	 *   <li>Hunting ({@link MissionService#onMobKilled}): {@code ENTITY_KILLED} also counts respawned ender dragons
	 *   (end crystals from shop ingredients), bred shulkers (splitting farm) and spawner mobs; the origin only shows on
	 *   the mob.</li>
	 * </ul>
	 */
	long statSum(ServerPlayer player) {
		long sum = 0;
		for (Stat<?> stat : stats) {
			if (stat.getType() != Stats.BLOCK_MINED && stat.getType() != Stats.ENTITY_KILLED) sum += Math.max(0, player.getStats().getValue(stat));
		}
		return sum;
	}

	/**
	 * Old saves: sum WITHOUT mining but WITH hunting stats, which is how the baseline was computed for missions
	 * accepted before hunting counted through events. Only for conversion: {@link MissionService#migrateMined}, {@link
	 * MissionService#migrateKills}.
	 */
	long statSumWithoutMined(ServerPlayer player) {
		long sum = 0;
		for (Stat<?> stat : stats) {
			if (stat.getType() != Stats.BLOCK_MINED) sum += Math.max(0, player.getStats().getValue(stat));
		}
		return sum;
	}

	boolean hasMined() {
		for (Stat<?> stat : stats) {
			if (stat.getType() == Stats.BLOCK_MINED) return true;
		}
		return false;
	}

	boolean hasKilled() {
		for (Stat<?> stat : stats) {
			if (stat.getType() == Stats.ENTITY_KILLED) return true;
		}
		return false;
	}

	boolean countsKill(EntityType<?> type) {
		for (Stat<?> stat : stats) {
			if (stat.getType() == Stats.ENTITY_KILLED && stat.getValue() == type) return true;
		}
		return false;
	}

	/**
	 * Pure dragon hunt (only {@code killed:ender_dragon})? Only the world's first dragon counts; every later one is a
	 * respawn ({@link dev.smpeconomy.core.MobOrigin#RESPAWNED}) and doesn't.
	 */
	boolean dragonOnly() {
		if (type != Type.STAT || stats.isEmpty()) return false;
		for (Stat<?> stat : stats) {
			if (stat.getType() != Stats.ENTITY_KILLED || stat.getValue() != EntityType.ENDER_DRAGON) return false;
		}
		return true;
	}

	boolean countsMined(Block block) {
		for (Stat<?> stat : stats) {
			if (stat.getType() == Stats.BLOCK_MINED && stat.getValue() == block) return true;
		}
		return false;
	}

	String category() {
		if (type != Type.STAT) return type.label;
		Stat<?> first = stats.isEmpty() ? null : stats.get(0);
		if (first == null) return type.label;
		if (first.getType() == Stats.ENTITY_KILLED) return "Hunting";
		if (first.getType() == Stats.BLOCK_MINED) return "Mining";
		if (first.getType() == Stats.ITEM_CRAFTED) return "Crafting";
		if (first.getType() == Stats.CUSTOM && Stats.FISH_CAUGHT.equals(first.getValue())) return "Fishing";
		return type.label;
	}

	boolean matchesLocation(ServerPlayer player) {
		return locate(player) != null;
	}

	boolean matchesLocation(ServerLevel level, BlockPos pos) {
		return locate(level, pos) != null;
	}

	String locate(ServerPlayer player) {
		if (type != Type.EXPLORE) return null;
		return locate(player.level(), player.blockPosition());
	}

	/**
	 * Location check for exploration: null means not at the target, otherwise "" (target without structure) or the
	 * structure instance key {@code structure@dimension:chunkX,chunkZ} (start chunk), so the same structure doesn't pay
	 * twice.
	 */
	String locate(ServerLevel level, BlockPos pos) {
		if (type != Type.EXPLORE) return null;
		if (dimension != null && !level.dimension().identifier().equals(dimension)) return null;
		if (!level.isLoaded(pos)) return null;
		if (biome != null || biomeTag != null) {
			Holder<Biome> holder = level.getBiome(pos);
			if (biome != null && !holder.is(biome)) return null;
			if (biomeTag != null && !holder.is(biomeTag)) return null;
		}
		if (structure != null || structureTag != null) {
			StructureStart start = level.structureManager()
				.getStructureWithPieceAt(pos, holder -> structure != null ? holder.is(structure) : holder.is(structureTag));
			if (!start.isValid()) return null;
			return structureInstance(level, start);
		}
		return "";
	}

	static String structureInstance(ServerLevel level, StructureStart start) {
		Identifier sid = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getKey(start.getStructure());
		ChunkPos c = start.getChunkPos();
		return (sid == null ? "?" : sid.toString()) + "@" + level.dimension().identifier() + ":" + Mc.chunkX(c) + "," + Mc.chunkZ(c);
	}

	/** Null if ok, otherwise an error text. */
	String checkTargets(MinecraftServer server) {
		if (type != Type.EXPLORE) return null;
		if (dimension != null && server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension)) == null) {
			return "dimension " + dimension + " does not exist";
		}
		Registry<Biome> biomes = server.registryAccess().lookupOrThrow(Registries.BIOME);
		if (biome != null && !biomes.containsKey(biome)) return "biome " + biome + " does not exist";
		if (biomeTag != null && biomes.get(biomeTag).isEmpty()) return "biome tag #" + biomeTag.location() + " does not exist";
		Registry<Structure> structures = server.registryAccess().lookupOrThrow(Registries.STRUCTURE);
		if (structure != null && !structures.containsKey(structure)) return "structure " + structure + " does not exist";
		if (structureTag != null && structures.get(structureTag).isEmpty()) return "structure tag #" + structureTag.location() + " does not exist";
		return null;
	}

	/**
	 * Is this catch from the vanilla treasure table ({@code gameplay/fishing/treasure})? Name tag, saddle, nautilus
	 * shell, enchanted book, bow (treasure only) and enchanted rod (junk rods are never enchanted).
	 */
	static boolean isTreasure(ItemStack stack) {
		if (stack.isEmpty()) return false;
		if (stack.is(net.minecraft.world.item.Items.NAME_TAG) || stack.is(net.minecraft.world.item.Items.SADDLE)
			|| stack.is(net.minecraft.world.item.Items.NAUTILUS_SHELL) || stack.is(net.minecraft.world.item.Items.ENCHANTED_BOOK)
			|| stack.is(net.minecraft.world.item.Items.BOW)) {
			return true;
		}
		return stack.is(net.minecraft.world.item.Items.FISHING_ROD) && stack.isEnchanted();
	}
}
