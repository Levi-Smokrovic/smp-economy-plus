package dev.smpeconomy.features.tasks;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.stats.Stat;

/**
 * A player's persisted task state ({@code PlayerData.taskState}, Gson). Target, reward and stats are copied at
 * assignment so a config reload doesn't change running tasks. Top-level {@code date}/{@code done}/{@code total} are
 * kept simple on purpose, e.g. for the sidebar.
 */
public final class TaskState {
	public int version = 1;
	/** Server time zone, ISO {@code yyyy-MM-dd}. */
	public String date = "";
	public long assignedAt;
	/** Derived, for display and HUD. */
	public int done;
	public int total;
	public List<ActiveTask> tasks = new ArrayList<>();
	/** Fixed at assignment. */
	public long bonusMoney;
	public String bonusKeyTier = "";
	public int bonusKeys;
	public boolean bonusPaid;

	public static final class ActiveTask {
		public String id = "";
		public String name = "";
		public String description = "";
		public String icon = "minecraft:paper";
		public String difficulty = "";
		public String category = "";
		/** Canonical stat keys ({@code "mined:minecraft:stone"}). */
		public List<String> stats = new ArrayList<>();
		public int divisor = 1;
		/** In display units. */
		public long target = 1;
		/** In cents. */
		public long reward;
		/** Raw sum of the stats at assignment. */
		public long base;
		/**
		 * Raw values that don't count: mined player-placed blocks, and Veinminer follow-up blocks of another type that
		 * raised the origin's stat.
		 */
		public long excluded;
		/**
		 * Raw values that count on top: natural Veinminer follow-up blocks with a stat of this task, which Veinminer
		 * credited to an origin of another type outside this task. See {@link BreakTracker}.
		 */
		public long credited;
		/** Raw and monotonic. */
		public long progressRaw;
		public boolean paid;

		/** Not saved. */
		transient List<Stat<?>> resolved;

		public long targetRaw() {
			long t = Math.max(1, target);
			long d = Math.max(1, divisor);
			return t > Long.MAX_VALUE / d ? Long.MAX_VALUE : t * d;
		}

		public boolean complete() {
			return progressRaw >= targetRaw();
		}

		/** In display units, at most the target. */
		public long progress() {
			return Math.min(Math.max(1, target), Math.max(0, progressRaw) / Math.max(1, divisor));
		}

		public double fraction() {
			return Math.max(0, Math.min(1.0, (double) Math.max(0, progressRaw) / targetRaw()));
		}

		List<Stat<?>> resolved() {
			if (resolved == null) {
				List<Stat<?>> list = new ArrayList<>();
				if (stats != null) {
					for (String key : stats) {
						Stat<?> s = StatRefs.byKey(key);
						if (s != null) list.add(s);
					}
				}
				resolved = list;
			}
			return resolved;
		}
	}

	void recount() {
		int d = 0;
		for (ActiveTask t : tasks) {
			if (t.paid) d++;
		}
		done = d;
		total = tasks.size();
	}

	boolean allPaid() {
		if (tasks.isEmpty()) return false;
		for (ActiveTask t : tasks) {
			if (!t.paid) return false;
		}
		return true;
	}

	void fixNulls() {
		if (date == null) date = "";
		if (tasks == null) tasks = new ArrayList<>();
		tasks.removeIf(t -> t == null);
		if (bonusKeyTier == null) bonusKeyTier = "";
		for (ActiveTask t : tasks) {
			if (t.id == null) t.id = "";
			if (t.name == null) t.name = t.id;
			if (t.description == null) t.description = "";
			if (t.icon == null) t.icon = "minecraft:paper";
			if (t.difficulty == null) t.difficulty = "";
			if (t.category == null) t.category = "";
			if (t.stats == null) t.stats = new ArrayList<>();
			if (t.divisor < 1) t.divisor = 1;
			if (t.target < 1) t.target = 1;
		}
	}
}
