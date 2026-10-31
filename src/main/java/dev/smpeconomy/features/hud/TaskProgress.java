package dev.smpeconomy.features.hud;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.TimeUtil;

/**
 * Daily task progress ("x/3") for the sidebar. Without {@code Services.DailyTasks} the HUD reads the state the tasks
 * feature stores through {@link PlayerDataService#setTaskState}, tolerantly and read-only: a date ({@code date},
 * {@code day} and so on; ISO text, epoch day or epoch ms) other than today means 0 done; the task list
 * ({@code tasks}, {@code entries} and so on, or the first array or object of objects) counts
 * entries with {@code done}, {@code completed}, {@code rewarded} and so on true or {@code progress >= target}. An
 * unknown format gives "unknown" (sidebar shows "–"), never an exception.
 */
final class TaskProgress {
	static final int DEFAULT_TOTAL = 3;
	private static final int MAX_TOTAL = 9;

	private static final Set<String> LIST_KEYS = Set.of("tasks", "entries", "list", "quests", "daily", "assigned", "current");
	private static final Set<String> DONE_FLAGS = Set.of("done", "completed", "complete", "finished", "rewarded", "claimed", "paid");
	private static final List<String> PROGRESS_KEYS = List.of("progress", "current", "count", "value");
	private static final List<String> TARGET_KEYS = List.of("target", "goal", "amount", "required", "needed");
	private static final Set<String> DONE_COUNT_KEYS = Set.of("completed", "done", "finished", "donecount", "completedcount");

	/** {@code done = -1} means unknown. */
	record Progress(int done, int total) {
		static final Progress UNKNOWN = new Progress(-1, DEFAULT_TOTAL);
		static final Progress NONE = new Progress(0, DEFAULT_TOTAL);

		boolean known() {
			return done >= 0;
		}
	}

	private static boolean warned;

	private TaskProgress() {}

	static Progress read(UUID player) {
		try {
			JsonElement state = PlayerDataService.getTaskState(player, JsonElement.class);
			return parse(state, TimeUtil.today());
		} catch (RuntimeException e) {
			if (!warned) {
				warned = true;
				SmpEconomy.LOG.warn("[SMPEco] hud: cannot read task state, sidebar shows '–'", e);
			}
			return Progress.UNKNOWN;
		}
	}

	static Progress parse(JsonElement state, LocalDate today) {
		if (state == null || state.isJsonNull()) return Progress.NONE; // no tasks assigned yet
		if (!state.isJsonObject()) return Progress.UNKNOWN;
		JsonObject root = state.getAsJsonObject();

		Optional<LocalDate> date = findDate(root);
		if (date.isPresent() && date.get().isBefore(today)) return Progress.NONE; // yesterday's state, so nothing done today

		List<JsonObject> entries = findEntries(root);
		Integer doneCount = findDoneCount(root);
		if (entries == null) {
			if (doneCount == null) return Progress.UNKNOWN;
			return new Progress(Math.min(doneCount, DEFAULT_TOTAL), DEFAULT_TOTAL);
		}
		int total = entries.isEmpty() || entries.size() > MAX_TOTAL ? DEFAULT_TOTAL : entries.size();
		int done = 0;
		for (JsonObject e : entries) {
			if (isDone(e)) done++;
		}
		if (done == 0 && doneCount != null) done = doneCount;
		return new Progress(Math.max(0, Math.min(done, total)), total);
	}

	private static Optional<LocalDate> findDate(JsonObject o) {
		for (Map.Entry<String, JsonElement> e : o.entrySet()) {
			String key = e.getKey().toLowerCase(Locale.ROOT);
			if (!isDateKey(key)) continue;
			if (!e.getValue().isJsonPrimitive()) continue;
			Optional<LocalDate> d = toDate(e.getValue().getAsJsonPrimitive());
			if (d.isPresent()) return d;
		}
		return Optional.empty();
	}

	/**
	 * {@code date}, {@code assignedDate}, {@code dateKey}, {@code day}, {@code epochDay} and so on (not
	 * {@code updatedAt}).
	 */
	private static boolean isDateKey(String lowerKey) {
		return lowerKey.startsWith("date") || lowerKey.endsWith("date") || lowerKey.equals("day") || lowerKey.equals("epochday")
			|| lowerKey.equals("tag") || lowerKey.equals("datum");
	}

	private static Optional<LocalDate> toDate(JsonPrimitive p) {
		try {
			if (p.isString()) return Optional.of(LocalDate.parse(p.getAsString().trim()));
			if (p.isNumber()) {
				long v = p.getAsLong();
				if (v > 100_000_000_000L) return Optional.of(TimeUtil.dateOf(v)); // epoch ms
				if (v > 0 && v < 1_000_000L) return Optional.of(LocalDate.ofEpochDay(v)); // epoch day
			}
		} catch (DateTimeParseException | NumberFormatException e) {
		}
		return Optional.empty();
	}

	private static List<JsonObject> findEntries(JsonObject root) {
		for (Map.Entry<String, JsonElement> e : root.entrySet()) {
			if (!LIST_KEYS.contains(e.getKey().toLowerCase(Locale.ROOT))) continue;
			List<JsonObject> list = objects(e.getValue());
			if (list != null) return list;
		}
		for (Map.Entry<String, JsonElement> e : root.entrySet()) {
			if (!e.getValue().isJsonArray()) continue;
			List<JsonObject> list = objects(e.getValue());
			if (list != null && !list.isEmpty()) return list;
		}
		return null;
	}

	/** Array of only objects, or object with only object values (e.g. by task id), as a list; otherwise null. */
	private static List<JsonObject> objects(JsonElement el) {
		List<JsonObject> list = new ArrayList<>();
		if (el.isJsonArray()) {
			JsonArray arr = el.getAsJsonArray();
			for (JsonElement x : arr) {
				if (!x.isJsonObject()) return null;
				list.add(x.getAsJsonObject());
			}
			return list;
		}
		if (el.isJsonObject()) {
			JsonObject obj = el.getAsJsonObject();
			if (obj.size() == 0) return null;
			for (Map.Entry<String, JsonElement> x : obj.entrySet()) {
				if (!x.getValue().isJsonObject()) return null;
				list.add(x.getValue().getAsJsonObject());
			}
			return list;
		}
		return null;
	}

	private static boolean isDone(JsonObject task) {
		for (Map.Entry<String, JsonElement> e : task.entrySet()) {
			if (!DONE_FLAGS.contains(e.getKey().toLowerCase(Locale.ROOT))) continue;
			JsonElement v = e.getValue();
			if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean() && v.getAsBoolean()) return true;
		}
		Long progress = number(task, PROGRESS_KEYS);
		Long target = number(task, TARGET_KEYS);
		return progress != null && target != null && target > 0 && progress >= target;
	}

	private static Long number(JsonObject o, List<String> keys) {
		for (String k : keys) {
			for (Map.Entry<String, JsonElement> e : o.entrySet()) {
				if (!e.getKey().equalsIgnoreCase(k)) continue;
				JsonElement v = e.getValue();
				if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) return v.getAsLong();
			}
		}
		return null;
	}

	/** Top level: {@code "completed": 2} or {@code "completed": [0, 2]} gives a count. */
	private static Integer findDoneCount(JsonObject root) {
		for (Map.Entry<String, JsonElement> e : root.entrySet()) {
			if (!DONE_COUNT_KEYS.contains(e.getKey().toLowerCase(Locale.ROOT))) continue;
			JsonElement v = e.getValue();
			if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) return (int) Math.max(0, Math.min(MAX_TOTAL, v.getAsLong()));
			if (v.isJsonArray()) {
				boolean scalars = true;
				for (JsonElement x : v.getAsJsonArray()) scalars &= x.isJsonPrimitive();
				if (scalars) return Math.min(MAX_TOTAL, v.getAsJsonArray().size());
			}
		}
		return null;
	}
}
