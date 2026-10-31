package dev.smpeconomy.features.tasks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatType;
import net.minecraft.stats.StatsCounter;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import dev.smpeconomy.SmpEconomy;

/**
 * Stat references from tasks.json ({@code "mined:#minecraft:logs"}, {@code "killed:zombie"}, {@code "custom:jump"})
 * resolved to vanilla {@link Stat}s. Each assigned task stores the resolved list as canonical keys {@code
 * "<type>:<namespace>:<path>"} so later config or tag changes don't alter running tasks. Tags resolve at assignment,
 * they aren't bound earlier.
 */
public final class StatRefs {
	public record Ref(String type, boolean tag, Identifier id) {}

	/** Server thread only; cleared on config and datapack reload. */
	private static final Map<String, List<Stat<?>>> CACHE = new HashMap<>();
	/** So the log doesn't flood. */
	private static final java.util.Set<String> WARNED = new java.util.HashSet<>();

	private StatRefs() {}

	/** Checks {@code <type>:[#]<id>}; throws {@link IllegalArgumentException}. */
	public static Ref parse(String ref) {
		if (ref == null) throw new IllegalArgumentException("statistic missing");
		int colon = ref.indexOf(':');
		if (colon <= 0 || colon == ref.length() - 1) throw new IllegalArgumentException("statistic without type: " + ref);
		String type = ref.substring(0, colon);
		if (!TasksConfig.STAT_TYPES.contains(type)) throw new IllegalArgumentException("unknown statistic type: " + ref);
		String rest = ref.substring(colon + 1);
		boolean tag = rest.startsWith("#");
		if (tag) rest = rest.substring(1);
		if (tag && type.equals("custom")) throw new IllegalArgumentException("custom statistics have no tags: " + ref);
		Identifier id = Identifier.tryParse(rest);
		if (id == null) throw new IllegalArgumentException("invalid ID in " + ref);
		return new Ref(type, tag, id);
	}

	public static <T> String key(Stat<T> stat) {
		Identifier type = BuiltInRegistries.STAT_TYPE.getKey(stat.getType());
		Identifier value = stat.getType().getRegistry().getKey(stat.getValue());
		return (type == null ? "?" : type.getPath()) + ":" + value;
	}

	public static String minedKey(Block block) {
		return "mined:" + BuiltInRegistries.BLOCK.getKey(block);
	}

	/** Tags expand to all entries; empty for an unknown ID or empty tag. */
	public static List<Stat<?>> resolve(String ref) {
		Ref r = parse(ref);
		StatType<?> type = BuiltInRegistries.STAT_TYPE.getValue(Identifier.withDefaultNamespace(r.type()));
		if (type == null) return List.of();
		List<Stat<?>> out = new ArrayList<>();
		collect(type, r, out);
		return out;
	}

	private static <T> void collect(StatType<T> type, Ref r, List<Stat<?>> out) {
		Registry<T> registry = type.getRegistry();
		if (r.tag()) {
			for (Holder<T> h : dev.smpeconomy.core.compat.Mc.configTagMembers(registry, r.id())) out.add(type.get(h.value()));
		} else if (registry.containsKey(r.id())) {
			T value = registry.getValue(r.id());
			if (value != null) out.add(type.get(value));
		}
	}

	public static Stat<?> byKey(String key) {
		try {
			Ref r = parse(key);
			if (r.tag()) return null;
			List<Stat<?>> list = resolve(key);
			return list.isEmpty() ? null : list.get(0);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/**
	 * Resolved, unique stats of a task (cached). Unknown references are skipped and logged once; a task with no
	 * resolvable stat is empty and doesn't get assigned.
	 */
	public static List<Stat<?>> resolve(TasksConfig.TaskDef def) {
		List<Stat<?>> cached = CACHE.get(def.id);
		if (cached != null) return cached;
		Map<String, Stat<?>> unique = new LinkedHashMap<>();
		for (String ref : def.stats) {
			List<Stat<?>> list;
			try {
				list = resolve(ref);
			} catch (IllegalArgumentException e) {
				list = List.of();
			}
			if (list.isEmpty() && WARNED.add(def.id + "|" + ref)) {
				SmpEconomy.LOG.warn("[SMPEco] tasks: stat '{}' of task '{}' is unknown or empty, skipped", ref, def.id);
			}
			for (Stat<?> s : list) unique.putIfAbsent(key(s), s);
		}
		List<Stat<?>> result = List.copyOf(unique.values());
		CACHE.put(def.id, result);
		return result;
	}

	public static void clearCache() {
		CACHE.clear();
	}

	public static long sum(ServerPlayer player, List<Stat<?>> stats) {
		StatsCounter counter = player.getStats();
		long total = 0;
		for (Stat<?> s : stats) total += counter.getValue(s);
		return total;
	}
}
