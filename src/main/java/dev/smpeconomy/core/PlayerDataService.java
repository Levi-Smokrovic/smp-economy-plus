package dev.smpeconomy.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;

/**
 * Per-player data in {@code players.json}: job, XP, task state, playtime, pending messages, settings (prefer
 * {@link Settings}), counters (crate keys only through {@link CrateKeys}) and a free key/value store per feature, with keys
 * always prefixed like {@code "daily.streak"}. All setters mark the store dirty. Server thread only.
 */
public final class PlayerDataService {
	/** Raw data. Prefer the service methods, which mark dirty. */
	public static final class PlayerData {
		public String name = "";
		public String job;
		public long jobChangedAt;
		public Map<String, Long> jobXp = new HashMap<>();
		public Map<String, Integer> jobLevel = new HashMap<>();
		public JsonElement taskState;
		/** Taken from the vanilla statistic on first join. */
		public long playtimeTicks;
		/** Section-coded text without the prefix. */
		public List<String> pendingMessages = new ArrayList<>();
		public Map<String, String> settings = new HashMap<>();
		public Map<String, Long> counters = new HashMap<>();
		public Map<String, JsonElement> blobs = new HashMap<>();

		void fixNulls() {
			if (name == null) name = "";
			if (jobXp == null) jobXp = new HashMap<>();
			if (jobLevel == null) jobLevel = new HashMap<>();
			if (pendingMessages == null) pendingMessages = new ArrayList<>();
			if (settings == null) settings = new HashMap<>();
			if (counters == null) counters = new HashMap<>();
			if (blobs == null) blobs = new HashMap<>();
		}
	}

	static final class Data {
		Map<UUID, PlayerData> players = new HashMap<>();
	}

	private static final int MAX_PENDING = 50;
	private static final DataStore<Data> STORE = new DataStore<>("players.json", Data.class, Data::new);

	static {
		STORE.onLoad(data -> {
			data.players.values().removeIf(p -> p == null);
			data.players.values().forEach(PlayerData::fixNulls);
		});
	}

	private PlayerDataService() {}

	/**
	 * Forces class loading from onInitialize. Otherwise the store would be created on first access, possibly after the
	 * shared load, and be missing from the restore point and startup check.
	 */
	public static void bootstrap() {
	}

	/** Creates the record if needed. After direct changes call {@link #markDirty()}. */
	public static PlayerData get(UUID id) {
		return players().computeIfAbsent(id, k -> {
			STORE.markDirty();
			return new PlayerData();
		});
	}

	public static Optional<PlayerData> peek(UUID id) {
		return Optional.ofNullable(players().get(id));
	}

	public static Set<UUID> knownPlayers() {
		return Collections.unmodifiableSet(players().keySet());
	}

	public static void markDirty() {
		STORE.markDirty();
	}

	public static void delete(UUID id) {
		if (players().remove(id) != null) STORE.markDirty();
	}

	public static void onJoin(ServerPlayer player) {
		boolean isNew = !players().containsKey(player.getUUID());
		PlayerData d = get(player.getUUID());
		d.name = player.getGameProfile().name();
		if (isNew) d.playtimeTicks = player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME));
		STORE.markDirty();
	}

	public static String getJob(UUID id) {
		return peek(id).map(d -> d.job).orElse(null);
	}

	public static void setJob(UUID id, String job) {
		PlayerData d = get(id);
		d.job = job;
		d.jobChangedAt = System.currentTimeMillis();
		STORE.markDirty();
	}

	public static long getJobChangedAt(UUID id) {
		return peek(id).map(d -> d.jobChangedAt).orElse(0L);
	}

	public static long getJobXp(UUID id, String job) {
		return peek(id).map(d -> d.jobXp.getOrDefault(job, 0L)).orElse(0L);
	}

	public static void setJobXp(UUID id, String job, long xp) {
		get(id).jobXp.put(job, Math.max(0, xp));
		STORE.markDirty();
	}

	public static long addJobXp(UUID id, String job, long xp) {
		PlayerData d = get(id);
		long value = Math.max(0, d.jobXp.getOrDefault(job, 0L) + xp);
		d.jobXp.put(job, value);
		STORE.markDirty();
		return value;
	}

	public static int getJobLevel(UUID id, String job) {
		return peek(id).map(d -> d.jobLevel.getOrDefault(job, 1)).orElse(1);
	}

	public static void setJobLevel(UUID id, String job, int level) {
		get(id).jobLevel.put(job, level);
		STORE.markDirty();
	}

	public static <T> T getTaskState(UUID id, Class<T> type) {
		JsonElement e = peek(id).map(d -> d.taskState).orElse(null);
		return e == null || e.isJsonNull() ? null : DataStore.gson().fromJson(e, type);
	}

	public static void setTaskState(UUID id, Object state) {
		get(id).taskState = state == null ? null : DataStore.gson().toJsonTree(state);
		STORE.markDirty();
	}

	public static <T> T getBlob(UUID id, String key, Class<T> type) {
		JsonElement e = peek(id).map(d -> d.blobs.get(key)).orElse(null);
		return e == null || e.isJsonNull() ? null : DataStore.gson().fromJson(e, type);
	}

	public static void setBlob(UUID id, String key, Object value) {
		PlayerData d = get(id);
		if (value == null) d.blobs.remove(key);
		else d.blobs.put(key, DataStore.gson().toJsonTree(value));
		STORE.markDirty();
	}

	public static String getString(UUID id, String key) {
		JsonElement e = peek(id).map(d -> d.blobs.get(key)).orElse(null);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}

	public static void setString(UUID id, String key, String value) {
		setBlob(id, key, value == null ? null : new JsonPrimitive(value));
	}

	public static long getLong(UUID id, String key, long def) {
		JsonElement e = peek(id).map(d -> d.blobs.get(key)).orElse(null);
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return def;
		return e.getAsLong();
	}

	public static void setLong(UUID id, String key, long value) {
		setBlob(id, key, new JsonPrimitive(value));
	}

	public static void remove(UUID id, String key) {
		setBlob(id, key, null);
	}

	public static long getPlaytimeTicks(UUID id) {
		return peek(id).map(d -> d.playtimeTicks).orElse(0L);
	}

	/** Deliberately does not mark dirty; autosave and stop are enough. */
	public static void addPlaytime(UUID id, long ticks) {
		get(id).playtimeTicks += ticks;
	}

	/** Usually {@link Msg#sendOrQueue} is the better choice. */
	public static void addPendingMessage(UUID id, String text) {
		List<String> list = get(id).pendingMessages;
		list.add(text);
		while (list.size() > MAX_PENDING) list.remove(0);
		STORE.markDirty();
	}

	public static List<String> getPendingMessages(UUID id) {
		return peek(id).map(d -> List.copyOf(d.pendingMessages)).orElse(List.of());
	}

	public static void removePendingMessages(UUID id, int count) {
		peek(id).ifPresent(d -> {
			int n = Math.min(count, d.pendingMessages.size());
			if (n > 0) {
				d.pendingMessages.subList(0, n).clear();
				STORE.markDirty();
			}
		});
	}

	public static String getSetting(UUID id, String key, String def) {
		return peek(id).map(d -> d.settings.getOrDefault(key, def)).orElse(def);
	}

	public static void setSetting(UUID id, String key, String value) {
		PlayerData d = get(id);
		if (value == null) d.settings.remove(key);
		else d.settings.put(key, value);
		STORE.markDirty();
	}

	public static boolean getBool(UUID id, String key, boolean def) {
		String v = getSetting(id, key, null);
		return v == null ? def : Boolean.parseBoolean(v);
	}

	public static void setBool(UUID id, String key, boolean value) {
		setSetting(id, key, Boolean.toString(value));
	}

	public static long getCounter(UUID id, String key) {
		return peek(id).map(d -> d.counters.getOrDefault(key, 0L)).orElse(0L);
	}

	public static long addCounter(UUID id, String key, long delta) {
		if (delta <= 0) throw new IllegalArgumentException("delta must be > 0");
		PlayerData d = get(id);
		long value = Math.addExact(d.counters.getOrDefault(key, 0L), delta);
		d.counters.put(key, value);
		STORE.markDirty();
		return value;
	}

	public static boolean takeCounter(UUID id, String key, long amount) {
		if (amount <= 0) return false;
		long current = getCounter(id, key);
		if (current < amount) return false;
		get(id).counters.put(key, current - amount);
		STORE.markDirty();
		return true;
	}

	public static void setCounter(UUID id, String key, long value) {
		if (value < 0) throw new IllegalArgumentException("Counter must not be negative");
		PlayerData d = get(id);
		if (value == 0) d.counters.remove(key);
		else d.counters.put(key, value);
		STORE.markDirty();
	}

	public static Map<String, Long> counters(UUID id, String prefix) {
		Map<String, Long> result = new TreeMap<>();
		peek(id).ifPresent(d -> d.counters.forEach((k, v) -> {
			if (k.startsWith(prefix)) result.put(k, v);
		}));
		return result;
	}

	private static Map<UUID, PlayerData> players() {
		return STORE.get().players;
	}
}
