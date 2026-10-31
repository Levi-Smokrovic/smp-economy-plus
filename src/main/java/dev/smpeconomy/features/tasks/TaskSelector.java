package dev.smpeconomy.features.tasks;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import dev.smpeconomy.features.tasks.TasksConfig.TaskDef;

/**
 * Deterministic daily selection: same UUID + date + pool gives the same tasks and amounts (own SplitMix64 generator,
 * independent of JVM and platform). One task per slot of the slot's difficulty, from different categories where
 * possible, never the same task twice.
 */
public final class TaskSelector {
	public record Pick(TaskDef def, long amount, long reward) {}

	private TaskSelector() {}

	public static List<Pick> select(TasksConfig cfg, UUID player, LocalDate date, Predicate<TaskDef> usable) {
		Rng rng = new Rng(seed(player, date));
		List<TaskDef> pool = new ArrayList<>();
		for (TaskDef d : cfg.tasks) {
			if (d.enabled && usable.test(d)) pool.add(d);
		}
		List<Pick> picks = new ArrayList<>();
		Set<String> usedIds = new HashSet<>();
		Set<String> usedCategories = new HashSet<>();
		for (String slot : cfg.slots) {
			// 1) matching difficulty + new category, 2) matching difficulty, 3) any task still free
			List<TaskDef> candidates = filter(pool, d -> d.difficulty.equals(slot) && !usedIds.contains(d.id) && !usedCategories.contains(d.category));
			if (candidates.isEmpty()) candidates = filter(pool, d -> d.difficulty.equals(slot) && !usedIds.contains(d.id));
			if (candidates.isEmpty()) candidates = filter(pool, d -> !usedIds.contains(d.id) && !usedCategories.contains(d.category));
			if (candidates.isEmpty()) candidates = filter(pool, d -> !usedIds.contains(d.id));
			if (candidates.isEmpty()) break;
			TaskDef def = weighted(candidates, rng);
			long amount = def.amount(rng.nextInt(def.amountChoices()));
			picks.add(new Pick(def, amount, TasksConfig.rewardFor(def, amount)));
			usedIds.add(def.id);
			usedCategories.add(def.category);
		}
		return picks;
	}

	private static List<TaskDef> filter(List<TaskDef> pool, Predicate<TaskDef> p) {
		List<TaskDef> out = new ArrayList<>();
		for (TaskDef d : pool) {
			if (p.test(d)) out.add(d);
		}
		return out;
	}

	private static TaskDef weighted(List<TaskDef> list, Rng rng) {
		long total = 0;
		for (TaskDef d : list) total += Math.max(1, d.weight);
		long r = rng.nextLong(total);
		for (TaskDef d : list) {
			r -= Math.max(1, d.weight);
			if (r < 0) return d;
		}
		return list.get(list.size() - 1);
	}

	/** Stable across restarts and platforms. */
	static long seed(UUID player, LocalDate date) {
		long a = mix(player.getMostSignificantBits() ^ 0x5DEECE66DL);
		long b = mix(player.getLeastSignificantBits() + 0x9E3779B97F4A7C15L);
		long c = mix(date.toEpochDay() * 0xD1B54A32D192ED03L + 0x12C0FFEEL);
		return mix(a ^ Long.rotateLeft(b, 21) ^ Long.rotateLeft(c, 42));
	}

	static long mix(long z) {
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	static final class Rng {
		private long state;

		Rng(long seed) {
			this.state = seed;
		}

		long next() {
			state += 0x9E3779B97F4A7C15L;
			return mix(state);
		}

		long nextLong(long bound) {
			if (bound <= 1) return 0;
			return Long.remainderUnsigned(next(), bound);
		}

		int nextInt(int bound) {
			return (int) nextLong(bound);
		}
	}
}
