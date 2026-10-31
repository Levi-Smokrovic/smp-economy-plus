package dev.smpeconomy.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueOutput;
import dev.smpeconomy.SmpEconomy;

/**
 * A JSON file in {@code <world>/smpeconomy/}, read and written with Gson.
 *
 * <p>Saving is done as a group ({@link #flushDirty}, {@link #saveAll}) with a roll-forward journal: (1) write each
 * changed store to {@code .tmp} and each requested player file to {@code playerdata/<uuid>.dat.smpeco-tmp}, then fsync
 * them all ({@link #forceAll}); (2) the commit point: atomically write the journal {@value #COMMIT_JOURNAL}; (3) rename
 * everything (players keep {@code .dat_old} like vanilla), fsync directories, delete the journal.
 *
 * <p>A crash before the journal leaves everything on the old state; stray {@code .tmp} files were never committed and
 * get moved aside at startup. A crash after it is completed on the next start ({@link #loadAll}), before anyone can
 * join. If preparation fails nothing is written, everything stays dirty and admins see the problem (
 * {@link #persistenceProblem()}). Only right before vanilla writes player files anyway is the save best effort.
 *
 * <p>Restore points: after each successful start, a copy of all stores goes to {@code backups/<time>/} (latest {@value
 * #SNAPSHOTS_KEPT} kept). If a file is damaged at startup, or missing although it existed before, the server refuses to
 * start: restoring one old file alone would duplicate money or items because the others are newer. The marker file
 * {@value #RESTORE_MARKER} resets all stores to the same restore point, once.
 *
 * <p>Saves are tied to vanilla: right before vanilla writes player files (start of {@code PlayerList.saveAll}), on
 * disconnect, on stop, and on request ({@link #flushDirty}, {@code /smpeco save}). A crash then rolls world, player
 * files and mod data back together. If a player file were written ahead of the world, looting a grave and crashing
 * would duplicate the items. It also avoids synchronous fsyncs on every transaction. With no vanilla save for {@value
 * #FALLBACK_INTERVAL_MS} ms (autosave off), the mod saves on its own. Remaining gap: when one player disconnects, other
 * players' requested files are written with it to stay consistent with mod files, so until the next autosave those are
 * newer than the world, as vanilla already does for the leaving player.
 *
 * <p>After any change call {@link #markDirty()}. After moving items between inventory and mod storage also call
 * {@link #requestPlayerSave(ServerPlayer)}, so the player file lands in the same journal as the mod files.
 *
 * <p>Server thread only, no synchronization.
 */
public final class DataStore<T> {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final List<DataStore<?>> ALL = new ArrayList<>();
	/** Without vanilla saves (autosave off, {@code /save-off}) the mod saves on its own after this long. */
	public static final long FALLBACK_INTERVAL_MS = 10 * 60_000;
	public static final long SLOW_COMMIT_MS = 50;
	public static final String BACKUP_DIR = "backups";
	/** Marker file in the data directory: reset all stores once to the newest usable restore point. */
	public static final String RESTORE_MARKER = "RESTORE_ALLOWED";
	/** Older name of the marker file, still honored. */
	/** Present in the backup directory while a restore replaces files. A crash midway means a mixed state. */
	private static final String SNAPSHOT_LABEL = "restore point ";
	private static final String OLD_BACKUP_LABEL = "old single backups *";
	public static final String RESTORE_JOURNAL = "RESTORE_RUNNING";
	public static final int SNAPSHOTS_KEPT = 5;
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS"); // zone is applied when formatting: core.json timezone can change on reload
	private static final DateTimeFormatter PROBLEM_TIME = DateTimeFormatter.ofPattern("dd.MM. HH:mm:ss");
	private static final Pattern SNAPSHOT_NAME = Pattern.compile("\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}-\\d{3}(-\\d+)?");
	private static final String PARTIAL_PREFIX = ".partial-";
	/**
	 * Journal of a group save in the data directory. While it exists, every {@code .tmp} it names is committed and gets
	 * renamed before the next save or on the next start.
	 */
	public static final String COMMIT_JOURNAL = "COMMIT_RUNNING";
	public static final String PLAYER_TMP_SUFFIX = ".dat.smpeco-tmp";
	/** Minimum interval between repeated log lines for an ongoing save problem. */
	private static final long PROBLEM_LOG_INTERVAL_MS = 60_000;

	private static Path dataDir;
	private static long flushCount;
	/** Baseline for the fallback save. */
	private static long lastCommitPoint;
	private static final Set<UUID> PLAYERS_TO_SAVE = new LinkedHashSet<>();
	private static final List<Runnable> BEFORE_COMMIT = new ArrayList<>();
	private static boolean allPlayersToSave;
	private static long playerSaves;
	private static boolean snapshotRetry;
	private static long slowCommits;
	private static long lastCommitMs;
	/** Stores created while this is true are missing from the shared load, so startup aborts. */
	private static boolean loadingAll;
	private static final List<String> LATE_STORES = new ArrayList<>();
	/** Guards against nested saves, which should never happen. */
	private static boolean committing;
	private static String problem;
	private static long problemSince;
	private static long lastProblemLog;
	private static long filesWritten;
	private static long writesSkipped;
	private static long failedCommits;
	private static long preSaveFlushes;
	private static boolean lastPreSaveClean;
	/** True only during the commit right before {@code PlayerList.saveAll} ({@link #flushBeforeWorldSave}). */
	private static boolean worldSaveCommit;
	private static String startSnapshot;
	private static Set<String> presentAtStart = Set.of();

	private final String fileName;
	private final Class<T> type;
	private final Supplier<T> factory;
	private final List<Consumer<T>> loadListeners = new ArrayList<>();
	private T value;
	private boolean dirty;
	private boolean loaded;
	private boolean restored;
	/** Part of the shared load at startup, hence also part of restore points and recovery. */
	private boolean inStartGroup;
	/** On-disk state after the last read or write: content SHA-256, size, mtime. Null if unknown. */
	private byte[] committedDigest;
	private long committedSize = -1;
	private FileTime committedMtime;

	public DataStore(String fileName, Class<T> type, Supplier<T> factory) {
		this(fileName, type, factory, true);
	}

	private DataStore(String fileName, Class<T> type, Supplier<T> factory, boolean managed) {
		this.fileName = fileName;
		this.type = type;
		this.factory = factory;
		this.value = factory.get();
		if (managed) {
			ALL.add(this);
			if (dataDir != null) {
				// Programming error: the class was not loaded in onInitialize, so the store misses the shared load, the
				// restore point and recovery. During loadAll this aborts startup. After that it is still loaded (saving
				// locked on failure) so the class stays usable.
				if (loadingAll) {
					LATE_STORES.add(fileName);
				} else {
					SmpEconomy.LOG.error("[SMPEco] Bug: DataStore {} was created after startup, so it is missing from restore points and "
						+ "recovery (load the class in SmpEconomy.onInitialize)", fileName);
				}
				try {
					load();
				} catch (RuntimeException e) {
					SmpEconomy.LOG.error("[SMPEco] DataStore {} could not be loaded, saving is blocked", fileName, e);
				}
			}
		}
	}

	/** Not loaded or saved automatically. For selftests, which call load() and save() themselves. */
	public static <T> DataStore<T> unmanaged(String fileName, Class<T> type, Supplier<T> factory) {
		return new DataStore<>(fileName, type, factory, false);
	}

	/** Holds the defaults until loaded in SERVER_STARTED. */
	public T get() {
		return value;
	}

	public void set(T newValue) {
		this.value = newValue == null ? factory.get() : newValue;
		markDirty();
	}

	public void markDirty() {
		dirty = true;
	}

	public boolean isDirty() {
		return dirty;
	}

	public boolean isLoaded() {
		return loaded;
	}

	/** Restored stores are also dirty right after loading, including inside {@link #onLoad} listeners. */
	public boolean wasRestored() {
		return restored;
	}

	public String fileName() {
		return fileName;
	}

	/**
	 * Called after each load, and immediately if already loaded. If the listener throws during loading, the store
	 * counts as not loaded: saving stays locked and startup aborts.
	 */
	public void onLoad(Consumer<T> listener) {
		loadListeners.add(listener);
		if (loaded) listener.accept(value);
	}

	public Path path() {
		if (dataDir == null) throw new IllegalStateException("DataStore " + fileName + ": server not started yet");
		return dataDir.resolve(fileName);
	}

	/**
	 * Reads only this store; at server start {@link #loadAll} loads them together. A missing file with no trace of it
	 * (restore point, {@code .bak}, {@code .corrupt-*}, {@code .tmp}) yields defaults. A damaged, empty or {@code null}
	 * file, one missing despite traces, or an IO error throws {@link IllegalStateException}, leaves the file alone and
	 * keeps saving locked. Recovery is only possible through {@link #loadAll} with the admin marker.
	 */
	public void load() {
		LoadReport report = loadStores(List.of(this), backupRoot(), false);
		if (!report.ok()) throw new IllegalStateException(String.join("; ", report.failures()));
	}

	/**
	 * Writes immediately, alone. Returns false if not loaded, if an interrupted group save cannot be completed, or on
	 * error (stays dirty). Never throws.
	 */
	public boolean save() {
		if (!loaded || dataDir == null) return false;
		if (finishPendingJournal() != null) {
			dirty = true;
			return false;
		}
		try {
			Path file = path();
			byte[] bytes = GSON.toJson(value).getBytes(StandardCharsets.UTF_8);
			byte[] digest = sha256(bytes);
			if (unchangedOnDisk(file, digest)) {
				writesSkipped++;
			} else {
				writeAtomic(file, bytes);
				markCommitted(file, digest);
				filesWritten++;
			}
			dirty = false;
			return true;
		} catch (IOException | RuntimeException e) {
			SmpEconomy.LOG.error("[SMPEco] Saving {} failed", fileName, e);
			dirty = true;
			return false;
		}
	}

	private boolean unchangedOnDisk(Path file, byte[] digest) {
		if (committedDigest == null || !Arrays.equals(committedDigest, digest)) return false;
		try {
			BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class);
			// Size + mtime: files changed or deleted externally are overwritten again, memory wins.
			return a.isRegularFile() && a.size() == committedSize && a.lastModifiedTime().equals(committedMtime);
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	private void markCommitted(Path file, byte[] digest) {
		try {
			BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class);
			committedSize = a.size();
			committedMtime = a.lastModifiedTime();
			committedDigest = digest;
		} catch (IOException | RuntimeException e) {
			forgetCommitted();
		}
	}

	private void forgetCommitted() {
		committedDigest = null;
		committedSize = -1;
		committedMtime = null;
	}

	private record Stamp(byte[] digest, long size, FileTime mtime) {}

	private record Probe(Object value, boolean fresh, String broken, Stamp stamp) {
		static Probe ofValue(Object v, Stamp stamp) {
			return new Probe(v, false, null, stamp);
		}

		static Probe ofFresh() {
			return new Probe(null, true, null, null);
		}

		static Probe ofBroken(String why) {
			return new Probe(null, false, why, null);
		}
	}

	record RestoreSet(String label, Map<String, Path> files) {}

	private record RestoreChoice(RestoreSet set, Map<DataStore<?>, Object> values) {}

	public record LoadReport(List<String> failures, List<String> restored, String restoredFrom) {
		public boolean ok() {
			return failures.isEmpty();
		}
	}

	private Probe probe(List<RestoreSet> sets) {
		Path file = path();
		if (Files.notExists(file)) {
			List<String> traces = traces(sets);
			if (traces.isEmpty()) return Probe.ofFresh();
			return Probe.ofBroken("is missing although traces of it exist (" + String.join(", ", traces) + ")");
		}
		if (!Files.isRegularFile(file)) {
			throw new IllegalStateException(fileName + ": unclear state (not a readable file: " + file + "), saving is blocked");
		}
		String json;
		Stamp stamp;
		try {
			BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
			json = Files.readString(file, StandardCharsets.UTF_8);
			stamp = new Stamp(sha256(json.getBytes(StandardCharsets.UTF_8)), attrs.size(), attrs.lastModifiedTime());
		} catch (CharacterCodingException e) {
			return Probe.ofBroken("is damaged (not valid UTF-8)");
		} catch (IOException e) {
			// Unreadable: better never to save than to overwrite real data with defaults.
			throw new IllegalStateException(fileName + " is not readable (" + e + "), saving is blocked", e);
		}
		T v = parse(json, file);
		return v != null ? Probe.ofValue(v, stamp) : Probe.ofBroken("is damaged, empty or null");
	}

	private List<String> traces(List<RestoreSet> sets) {
		List<String> found = new ArrayList<>();
		for (RestoreSet set : sets) {
			if (set.files().containsKey(fileName)) found.add(set.label());
		}
		Path file = path();
		try (Stream<Path> list = Files.list(file.getParent())) {
			list.map(p -> p.getFileName().toString())
				.filter(n -> n.startsWith(fileName + ".corrupt-") || n.equals(fileName + ".tmp"))
				.sorted()
				.forEach(found::add);
		} catch (IOException e) {
			throw new IllegalStateException(fileName + " is missing and the data folder is not readable (" + e + "), saving is blocked", e);
		}
		return found;
	}

	/** Null for broken, empty or {@code null} content; Gson returns null for those rather than throwing. */
	private T parse(String json, Path source) {
		try {
			return GSON.fromJson(json, type);
		} catch (RuntimeException e) {
			SmpEconomy.LOG.error("[SMPEco] {} cannot be read: {}", source, e.getMessage());
			return null;
		}
	}

	private T readCopy(Path copy) {
		try {
			return parse(Files.readString(copy, StandardCharsets.UTF_8), copy);
		} catch (IOException e) {
			SmpEconomy.LOG.error("[SMPEco] Restore copy {} is not readable: {}", copy, e.toString());
			return null;
		}
	}

	private void accept(Object v, boolean wasRestored, Stamp stamp) {
		value = v != null ? type.cast(v) : factory.get();
		restored = wasRestored;
		dirty = wasRestored; // Restored state counts as dirty; features use this to detect a restore.
		if (stamp != null && !wasRestored) {
			committedDigest = stamp.digest();
			committedSize = stamp.size();
			committedMtime = stamp.mtime();
		} else {
			forgetCommitted();
		}
		try {
			for (Consumer<T> l : loadListeners) l.accept(value);
		} catch (RuntimeException e) {
			// Data does not match the code (e.g. a repair throws): never save, let startup abort.
			throw new IllegalStateException(fileName + ": onLoad failed (" + e + "), saving is blocked", e);
		}
		loaded = true;
	}

	/**
	 * Loads several stores together. Public only for selftests. If one is damaged or missing despite traces, nothing is
	 * loaded or touched unless {@code allowRestore} is set. With it, all given stores are reset to the newest restore
	 * point holding a usable copy of every broken store (stores that did not exist then get defaults). Current files
	 * are first kept in {@code <backupsRoot>/replaced-<time>/}.
	 */
	public static LoadReport loadStores(List<DataStore<?>> stores, Path backupsRoot, boolean allowRestore) {
		for (DataStore<?> s : stores) {
			s.loaded = false;
			s.restored = false;
		}
		List<String> failures = new ArrayList<>();
		List<RestoreSet> sets;
		try {
			sets = restoreSets(backupsRoot, stores);
		} catch (IOException | RuntimeException e) {
			// Without an overview of restore points, "missing = new store" cannot be decided safely.
			SmpEconomy.LOG.error("[SMPEco] Restore points in {} are not readable", backupsRoot, e);
			return new LoadReport(List.of("restore points in " + backupsRoot + " are not readable (" + e + ")"), List.of(), null);
		}
		String interruptedFrom = null;
		Path journal = backupsRoot.resolve(RESTORE_JOURNAL);
		if (Files.exists(journal)) {
			try {
				interruptedFrom = Files.readString(journal, StandardCharsets.UTF_8).strip();
			} catch (IOException e) {
				failures.add(journal + " is not readable (" + e + ")");
			}
		}
		Map<DataStore<?>, Object> values = new HashMap<>();
		Map<DataStore<?>, Stamp> stamps = new HashMap<>();
		List<DataStore<?>> broken = new ArrayList<>();
		for (DataStore<?> s : stores) {
			try {
				Probe p = s.probe(sets);
				if (p.broken() != null) {
					broken.add(s);
					SmpEconomy.LOG.error("[SMPEco] {} {}", s.fileName, p.broken());
				} else {
					if (p.fresh()) SmpEconomy.LOG.info("[SMPEco] {} does not exist yet, starting a new store with defaults", s.fileName);
					values.put(s, p.value());
					stamps.put(s, p.stamp());
				}
			} catch (RuntimeException e) {
				SmpEconomy.LOG.error("[SMPEco] DataStore {} could not be loaded", s.fileName, e);
				failures.add(s.fileName + ": " + e.getMessage());
			}
		}
		String restoredFrom = null;
		if (!broken.isEmpty() || interruptedFrom != null) {
			String what = (interruptedFrom != null ? "recovery from " + interruptedFrom + " was interrupted (data is mixed)" : "")
				+ (interruptedFrom != null && !broken.isEmpty() ? "; " : "")
				+ (broken.isEmpty() ? "" : "damaged or missing: " + String.join(", ", names(broken)));
			if (!failures.isEmpty()) {
				// Fix IO errors first, otherwise it is unclear which state to restore.
				failures.add(what);
				return new LoadReport(failures, List.of(), null);
			}
			RestoreChoice choice = choose(sets, stores, broken, interruptedFrom);
			if (!allowRestore) {
				logRefusal(what, choice, sets, stores, backupsRoot);
				failures.add(what + ", start aborted, see the log for instructions");
				return new LoadReport(failures, List.of(), null);
			}
			if (choice == null) {
				SmpEconomy.LOG.error("[SMPEco] Recovery is allowed, but no restore point has usable copies of {}. Repair the files by hand "
					+ "or restore them from a server backup", interruptedFrom != null ? interruptedFrom : String.join(", ", names(broken)));
				failures.add(what + ", no usable restore point");
				return new LoadReport(failures, List.of(), null);
			}
			Path kept;
			try {
				kept = applyRestore(choice, stores, backupsRoot);
			} catch (IOException | RuntimeException e) {
				SmpEconomy.LOG.error("[SMPEco] Recovery from {} failed", choice.set().label(), e);
				failures.add("recovery from " + choice.set().label() + " failed: " + e);
				return new LoadReport(failures, List.of(), null);
			}
			values.clear();
			stamps.clear(); // restored: counts as dirty and is rewritten anyway
			for (DataStore<?> s : stores) values.put(s, choice.values().get(s)); // not in the set, null means defaults
			restoredFrom = choice.set().label();
			List<String> missing = new ArrayList<>();
			for (DataStore<?> s : stores) {
				if (!choice.set().files().containsKey(s.fileName)) missing.add(s.fileName);
			}
			SmpEconomy.LOG.error("[SMPEco] RESTORED: all {} mod files were reset to {}{}. All changes since then are gone; "
					+ "player inventories (playerdata) were NOT reset. Previous files: {}", stores.size(), restoredFrom,
				missing.isEmpty() ? "" : " (did not exist back then, so they use defaults: " + String.join(", ", missing) + ")", kept);
		}
		for (DataStore<?> s : stores) {
			if (!values.containsKey(s)) continue;
			try {
				s.accept(values.get(s), restoredFrom != null, stamps.get(s));
			} catch (RuntimeException e) {
				SmpEconomy.LOG.error("[SMPEco] DataStore {} could not be loaded", s.fileName, e);
				failures.add(s.fileName + ": " + e.getMessage());
			}
		}
		List<String> restoredNames = restoredFrom != null ? names(stores) : List.of();
		return new LoadReport(failures, restoredNames, restoredFrom);
	}

	private static List<String> names(List<DataStore<?>> stores) {
		List<String> n = new ArrayList<>();
		for (DataStore<?> s : stores) n.add(s.fileName);
		return n;
	}

	public static List<Path> restoreMarkers(Path dir) {
		List<Path> markers = new ArrayList<>();
		for (String name : List.of(RESTORE_MARKER)) {
			if (Files.exists(dir.resolve(name))) markers.add(dir.resolve(name));
		}
		return markers;
	}

	/** Newest first: {@code backups/<time>/}, then single-file {@code .bak} and {@code .bak2}. */
	static List<RestoreSet> restoreSets(Path backupsRoot, List<DataStore<?>> stores) throws IOException {
		List<RestoreSet> sets = new ArrayList<>();
		for (Path dir : snapshotDirs(backupsRoot)) {
			Map<String, Path> files = new LinkedHashMap<>();
			for (DataStore<?> s : stores) {
				Path f = dir.resolve(s.fileName);
				if (Files.isRegularFile(f)) files.put(s.fileName, f);
			}
			sets.add(new RestoreSet(SNAPSHOT_LABEL + dir.getFileName(), files));
		}
		// .bak files were rotated for all stores together, so each suffix is one consistent set.
		for (String suffix : new String[] {".bak", ".bak2"}) {
			Map<String, Path> files = new LinkedHashMap<>();
			for (DataStore<?> s : stores) {
				Path f = s.path().resolveSibling(s.path().getFileName() + suffix);
				if (Files.isRegularFile(f)) files.put(s.fileName, f);
			}
			if (!files.isEmpty()) sets.add(new RestoreSet(OLD_BACKUP_LABEL + suffix, files));
		}
		return sets;
	}

	private static List<Path> snapshotDirs(Path backupsRoot) throws IOException {
		if (!Files.isDirectory(backupsRoot)) return List.of();
		try (Stream<Path> list = Files.list(backupsRoot)) {
			return list.filter(Files::isDirectory)
				.filter(p -> SNAPSHOT_NAME.matcher(p.getFileName().toString()).matches())
				.sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
				.toList();
		}
	}

	/**
	 * Newest set with a readable copy of every broken store. {@code requiredLabel} forces one set, e.g. after an
	 * interrupted restore.
	 */
	private static RestoreChoice choose(List<RestoreSet> sets, List<DataStore<?>> stores, List<DataStore<?>> broken, String requiredLabel) {
		for (RestoreSet set : sets) {
			if (requiredLabel != null && !requiredLabel.equals(set.label())) continue;
			if (set.files().isEmpty()) continue;
			boolean complete = true;
			for (DataStore<?> b : broken) {
				if (!set.files().containsKey(b.fileName)) {
					complete = false;
					break;
				}
			}
			if (!complete) continue;
			Map<DataStore<?>, Object> values = new HashMap<>();
			boolean usable = true;
			for (DataStore<?> s : stores) {
				Path copy = set.files().get(s.fileName);
				if (copy == null) continue;
				Object v = s.readCopy(copy);
				if (v == null) {
					usable = false;
					SmpEconomy.LOG.error("[SMPEco] {}: the copy of {} is unusable, skipping this set", set.label(), s.fileName);
					break;
				}
				values.put(s, v);
			}
			if (usable) return new RestoreChoice(set, values);
		}
		return null;
	}

	private static void logRefusal(String what, RestoreChoice choice, List<RestoreSet> sets, List<DataStore<?>> stores, Path backupsRoot) {
		Path dir = stores.isEmpty() ? backupsRoot : stores.get(0).path().getParent();
		List<String> labels = new ArrayList<>();
		for (RestoreSet s : sets) labels.add(s.label() + " (" + s.files().size() + " files)");
		SmpEconomy.LOG.error("[SMPEco] ===== Mod data: start aborted, {} =====", what);
		SmpEconomy.LOG.error("[SMPEco] Bringing back one old file on its own would duplicate or destroy money and items, because the other files are newer. "
			+ "Nothing was changed. Your options:");
		SmpEconomy.LOG.error("[SMPEco]  (a) Repair the file(s) by hand, or restore the world AND smpeconomy TOGETHER from a server backup. "
			+ "A leftover <file>.tmp is a save attempt that was NEVER committed and may be incomplete, so do not use it as a "
			+ "replacement. If that is the only reason a new store's file is missing (no restore point), delete the .tmp.");
		SmpEconomy.LOG.error("[SMPEco]  (b) Reset ALL mod data to one shared restore point: create the empty file {} and restart. "
			+ "This would use: {}. Player inventories are NOT reset; the current files are moved to {}/replaced-<time>/.",
			dir.resolve(RESTORE_MARKER), choice != null ? choice.set().label() : "NONE (no set with usable copies)", backupsRoot);
		SmpEconomy.LOG.error("[SMPEco]  (c) Start a deliberately deleted file from scratch: create the file with the content {}.", "{}");
		SmpEconomy.LOG.error("[SMPEco] Available restore points (newest first): {}", labels.isEmpty() ? "none" : String.join(", ", labels));
	}

	/**
	 * Copies current files to {@code replaced-<time>/}, writes new state as fsynced {@code .tmp}, creates the journal,
	 * renames or deletes, fsyncs directories, deletes the journal. A crash midway leaves the journal, so the next start
	 * refuses or repeats.
	 *
	 * @return directory holding the previous files
	 */
	private static Path applyRestore(RestoreChoice choice, List<DataStore<?>> stores, Path backupsRoot) throws IOException {
		Files.createDirectories(backupsRoot);
		Path kept = uniqueDir(backupsRoot, "replaced-" + STAMP.withZone(TimeUtil.zone()).format(Instant.now()));
		Files.createDirectories(kept);
		for (DataStore<?> s : stores) {
			Path f = s.path();
			if (!Files.isRegularFile(f)) continue;
			Path target = kept.resolve(s.fileName);
			Files.copy(f, target, StandardCopyOption.REPLACE_EXISTING);
			syncQuietly(target);
		}
		syncQuietly(kept);
		List<Path[]> moves = new ArrayList<>();
		for (DataStore<?> s : stores) {
			Path copy = choice.set().files().get(s.fileName);
			if (copy == null) continue;
			Path tmp = tmpPath(s.path());
			writeAndSync(tmp, Files.readAllBytes(copy));
			moves.add(new Path[] {tmp, s.path()});
		}
		Path journal = backupsRoot.resolve(RESTORE_JOURNAL);
		writeAtomic(journal, choice.set().label().getBytes(StandardCharsets.UTF_8));
		Set<Path> dirs = new LinkedHashSet<>();
		for (Path[] m : moves) {
			move(m[0], m[1]);
			dirs.add(m[1].getParent());
		}
		for (DataStore<?> s : stores) {
			if (choice.set().files().containsKey(s.fileName)) continue;
			Files.deleteIfExists(s.path()); // did not exist when the set was made: defaults (copy is in kept)
			dirs.add(s.path().getParent());
		}
		for (Path d : dirs) syncQuietly(d);
		Files.delete(journal);
		syncQuietly(backupsRoot);
		return kept;
	}

	private static Path uniqueDir(Path root, String base) {
		Path p = root.resolve(base);
		for (int n = 1; Files.exists(p) || Files.exists(root.resolve(PARTIAL_PREFIX + p.getFileName())); n++) p = root.resolve(base + "-" + n);
		return p;
	}

	/**
	 * Copies all store files to {@code <backupsRoot>/<time>/} via a {@code .partial-*} directory, then prunes to the
	 * newest {@value #SNAPSHOTS_KEPT}. Errors are only logged. Public only for selftests.
	 *
	 * @return name of the new restore point, or null
	 */
	public static String snapshot(List<DataStore<?>> stores, Path backupsRoot) {
		return snapshot(stores, backupsRoot, List.of());
	}

	static String snapshot(List<DataStore<?>> stores, Path backupsRoot, List<Path> extraFiles) {
		try {
			Files.createDirectories(backupsRoot);
			Path target = uniqueDir(backupsRoot, STAMP.withZone(TimeUtil.zone()).format(Instant.now()));
			Path partial = backupsRoot.resolve(PARTIAL_PREFIX + target.getFileName());
			Files.createDirectories(partial);
			int copied = 0;
			List<Path> files = new ArrayList<>();
			for (DataStore<?> s : stores) files.add(s.path());
			files.addAll(extraFiles);
			for (Path f : files) {
				if (!Files.isRegularFile(f)) continue;
				Path copy = partial.resolve(f.getFileName().toString());
				Files.copy(f, copy, StandardCopyOption.REPLACE_EXISTING);
				syncQuietly(copy);
				copied++;
			}
			if (copied == 0) {
				deleteTree(partial);
				return null;
			}
			syncQuietly(partial);
			move(partial, target);
			syncQuietly(backupsRoot);
			prune(backupsRoot);
			SmpEconomy.LOG.info("[SMPEco] Restore point {} created ({} files)", target.getFileName(), copied);
			return target.getFileName().toString();
		} catch (IOException | RuntimeException e) {
			SmpEconomy.LOG.error("[SMPEco] Restore point in {} not created: {}", backupsRoot, e.toString());
			return null;
		}
	}

	/**
	 * Restore points (newest first) containing {@code fileName}, e.g. as a trace of a missing
	 * {@code placed_blocks.dat}. A read error yields one entry holding the error, which counts as a trace.
	 */
	public static List<String> restorePointsContaining(String fileName) {
		if (dataDir == null) return List.of();
		List<String> out = new ArrayList<>();
		try {
			for (Path dir : snapshotDirs(backupRoot())) {
				if (Files.isRegularFile(dir.resolve(fileName))) out.add(dir.getFileName().toString());
			}
		} catch (IOException | RuntimeException e) {
			out.add("restore points are not readable (" + e + ")");
		}
		return out;
	}

	private static void prune(Path backupsRoot) throws IOException {
		List<Path> dirs = snapshotDirs(backupsRoot);
		for (int i = SNAPSHOTS_KEPT; i < dirs.size(); i++) deleteTree(dirs.get(i));
		List<Path> partials;
		try (Stream<Path> list = Files.list(backupsRoot)) {
			partials = list.filter(p -> p.getFileName().toString().startsWith(PARTIAL_PREFIX)).toList();
		}
		for (Path p : partials) deleteTree(p);
	}

	private static void deleteTree(Path root) throws IOException {
		if (!Files.exists(root)) return;
		List<Path> all;
		try (Stream<Path> walk = Files.walk(root)) {
			all = walk.sorted(Comparator.reverseOrder()).toList();
		}
		for (Path p : all) Files.deleteIfExists(p);
	}

	public static Gson gson() {
		return GSON;
	}

	public static Path dataDir() {
		return dataDir;
	}

	public static Path backupRoot() {
		if (dataDir == null) throw new IllegalStateException("DataStore: server not started yet");
		return dataDir.resolve(BACKUP_DIR);
	}

	/**
	 * After moving items between inventory and mod storage: include this player's {@code playerdata/<uuid>.dat} in the
	 * next flush, in the same journal as the mod files, so either both land or neither. Offline, test and fake players
	 * are skipped.
	 */
	public static void requestPlayerSave(ServerPlayer player) {
		if (player != null) requestPlayerSave(player.getUUID());
	}

	public static void requestPlayerSave(UUID player) {
		if (player != null) PLAYERS_TO_SAVE.add(player);
	}

	/** Only for when it is unclear whose inventory changed; otherwise use {@link #requestPlayerSave(ServerPlayer)}. */
	public static void requestPlayerSave() {
		allPlayersToSave = true;
	}

	public static boolean playerSavePending() {
		return allPlayersToSave || !PLAYERS_TO_SAVE.isEmpty();
	}

	public static long playerSaveCount() {
		return playerSaves;
	}

	/**
	 * No effect of its own: changes reach disk at the next vanilla save, disconnect or stop, together with world and
	 * player files. Kept for existing callers.
	 */
	public static void requestFlush() {
	}

	/**
	 * SERVER_STARTED: finishes an interrupted group save ({@value #COMMIT_JOURNAL}), moves aside {@code .tmp} files
	 * that were never committed, and loads all stores together ({@link #loadStores}). If a store is unreadable,
	 * damaged, missing despite traces, or only created during loading, this throws {@link IllegalStateException} and
	 * the server does not start: no start beats running on empty or individually restored data. With the marker {@value
	 * #RESTORE_MARKER} present, all stores are reset to one restore point and the marker is deleted. A normal start
	 * creates a new restore point.
	 */
	public static void loadAll(MinecraftServer server) {
		dataDir = server.getWorldPath(LevelResource.ROOT).resolve("smpeconomy").normalize();
		try {
			Files.createDirectories(dataDir);
		} catch (IOException e) {
			throw new IllegalStateException("[SMPEco] Data folder " + dataDir + " cannot be created", e);
		}
		// Crash after the commit point: finish renaming before anything else, no player is here yet.
		Path journal = dataDir.resolve(COMMIT_JOURNAL);
		try {
			int done = rollForward(worldRoot(), journal);
			if (done >= 0) SmpEconomy.LOG.warn("[SMPEco] Finished an interrupted save ({} files renamed, including player files)", done);
		} catch (IOException | RuntimeException e) {
			throw new IllegalStateException("[SMPEco] An interrupted save (" + journal + ") cannot be completed. Server start "
				+ "aborted, nothing was changed: " + e, e);
		}
		List<DataStore<?>> stores = List.copyOf(ALL);
		// Crash before the commit point: stray .tmp files were never valid, the old state is correct.
		discardUncommitted(stores, server.getWorldPath(LevelResource.PLAYER_DATA_DIR), backupRoot());
		List<Path> markers = restoreMarkers(dataDir);
		boolean allow = !markers.isEmpty();
		if (allow) SmpEconomy.LOG.warn("[SMPEco] Found {}: damaged or missing mod data will be reset to one shared restore point", markers);
		LATE_STORES.clear();
		LoadReport report;
		loadingAll = true;
		try {
			report = loadStores(stores, backupRoot(), allow);
		} finally {
			loadingAll = false;
		}
		lastCommitPoint = System.currentTimeMillis();
		if (!report.ok()) {
			throw new IllegalStateException("[SMPEco] Mod data is damaged or not readable. Server start aborted (check the log): " + String.join("; ", report.failures()));
		}
		if (!LATE_STORES.isEmpty()) {
			// E.g. created from another store's onLoad: not in the shared load, the restore point or recovery, and a
			// broken file would not have stopped startup.
			throw new IllegalStateException("[SMPEco] Bug: DataStore(s) " + String.join(", ", LATE_STORES) + " were only created while loading "
				+ "(class not loaded in SmpEconomy.onInitialize). Server start aborted");
		}
		for (DataStore<?> s : stores) s.inStartGroup = true;
		if (allow) {
			for (Path marker : markers) {
				try {
					Files.deleteIfExists(marker);
					SmpEconomy.LOG.warn("[SMPEco] {} {} and deleted", marker, report.restoredFrom() != null ? "used" : "not needed");
				} catch (IOException e) {
					SmpEconomy.LOG.error("[SMPEco] {} could not be deleted. Delete it by hand, otherwise it applies to the next problem without asking", marker, e);
				}
			}
		}
		Set<String> present = new LinkedHashSet<>();
		for (DataStore<?> s : stores) {
			if (Files.isRegularFile(s.path())) present.add(s.fileName);
		}
		presentAtStart = Collections.unmodifiableSet(present);
		// Every store gets a file now, new ones with defaults. Otherwise it is missing from the restore point, and if
		// its file were lost before the next start, probe() would treat it as new (starting balances, empty escrows)
		// rather than refusing to start. All or nothing; unchanged files are skipped.
		if (!commit(stores, server, false).complete()) {
			SmpEconomy.LOG.error("[SMPEco] Startup: not all stores could be written, the restore point may be incomplete and will be redone");
		}
		startSnapshot = null;
		snapshotRetry = false;
		if (report.restoredFrom() == null) {
			Path tracker = dataDir.resolve(PlacedBlockTracker.FILE);
			// Only a readable copy of placed_blocks.dat: a broken one is useless as a replacement.
			startSnapshot = snapshot(stores, backupRoot(), PlacedBlockTracker.isReadable(tracker) ? List.of(tracker) : List.of());
			snapshotRetry = startSnapshot == null || !snapshotComplete(stores, startSnapshot);
		}
	}

	private static boolean snapshotComplete(List<DataStore<?>> stores, String snapshot) {
		Path dir = backupRoot().resolve(snapshot);
		for (DataStore<?> s : stores) {
			if (Files.isRegularFile(s.path()) && !Files.isRegularFile(dir.resolve(s.fileName))) return false;
		}
		for (DataStore<?> s : stores) {
			if (!Files.isRegularFile(s.path())) return false;
		}
		return true;
	}

	private static void retrySnapshot() {
		if (!snapshotRetry || dataDir == null) return;
		List<DataStore<?>> stores = List.copyOf(ALL);
		Path tracker = dataDir.resolve(PlacedBlockTracker.FILE);
		String snap = snapshot(stores, backupRoot(), PlacedBlockTracker.isReadable(tracker) ? List.of(tracker) : List.of());
		if (snap != null) {
			startSnapshot = snap;
			snapshotRetry = !snapshotComplete(stores, snap);
			if (!snapshotRetry) SmpEconomy.LOG.info("[SMPEco] Restore point {} redone", snap);
		}
	}

	private static Path worldRoot() {
		return dataDir.toAbsolutePath().normalize().getParent();
	}

	/**
	 * Startup, before loading: {@code <store>.tmp} without a journal comes from a save that died before its commit
	 * point and may be incomplete. If the main file exists it is correct and the {@code .tmp} moves to
	 * {@code backups/incomplete-<time>/}. If the main file is missing the {@code .tmp} stays as a trace, so startup is
	 * refused. Prepared player files without a journal are deleted. Public only for selftests.
	 *
	 * @return number of files moved aside or deleted
	 */
	public static int discardUncommitted(List<DataStore<?>> stores, Path playerDir, Path backupsRoot) {
		int count = 0;
		Path aside = null;
		for (DataStore<?> s : stores) {
			Path tmp = tmpPath(s.path());
			if (!Files.isRegularFile(tmp) || !Files.isRegularFile(s.path())) continue;
			try {
				if (aside == null) {
					aside = uniqueDir(backupsRoot, "incomplete-" + STAMP.withZone(TimeUtil.zone()).format(Instant.now()));
					Files.createDirectories(aside);
				}
				move(tmp, aside.resolve(s.fileName + ".tmp"));
				count++;
				SmpEconomy.LOG.warn("[SMPEco] {}.tmp was never committed (save was interrupted), moved aside to {}; {} stays valid",
					s.fileName, aside, s.fileName);
			} catch (IOException | RuntimeException e) {
				SmpEconomy.LOG.error("[SMPEco] {}.tmp could not be moved aside (the next save overwrites it)", s.fileName, e);
			}
		}
		try {
			Files.deleteIfExists(tmpPath(dataDir.resolve(COMMIT_JOURNAL)));
		} catch (IOException e) {
			SmpEconomy.LOG.error("[SMPEco] {}.tmp cannot be deleted", COMMIT_JOURNAL, e);
		}
		if (playerDir == null || !Files.isDirectory(playerDir)) return count;
		List<Path> orphans;
		try (Stream<Path> list = Files.list(playerDir)) {
			orphans = list.filter(p -> p.getFileName().toString().endsWith(PLAYER_TMP_SUFFIX)).toList();
		} catch (IOException e) {
			SmpEconomy.LOG.error("[SMPEco] {} is not readable", playerDir, e);
			return count;
		}
		for (Path p : orphans) {
			try {
				Files.deleteIfExists(p);
				count++;
				SmpEconomy.LOG.warn("[SMPEco] Deleted the never committed player file {} (save was interrupted; the .dat stays valid)", p.getFileName());
			} catch (IOException e) {
				SmpEconomy.LOG.error("[SMPEco] {} cannot be deleted", p, e);
			}
		}
		return count;
	}

	/** Paths are relative to the world directory. */
	static final class Journal {
		List<JournalEntry> entries = new ArrayList<>();
	}

	/** {@code keepOld}: the old target becomes {@code <target>_old} like vanilla player files. */
	static final class JournalEntry {
		String tmp;
		String target;
		boolean keepOld;

		JournalEntry() {}

		JournalEntry(String tmp, String target, boolean keepOld) {
			this.tmp = tmp;
			this.target = target;
			this.keepOld = keepOld;
		}
	}

	private record Move(Path tmp, Path target, boolean keepOld) {}

	private static String relative(Path root, Path p) {
		Path rel = root.relativize(p.toAbsolutePath().normalize());
		List<String> parts = new ArrayList<>();
		for (Path part : rel) parts.add(part.toString());
		return String.join("/", parts);
	}

	/**
	 * Rejects paths outside the world directory, and any tmp that is not {@code <target>.tmp|.smpeco-tmp} next to its
	 * target.
	 */
	private static List<Move> resolve(Path worldRoot, Journal journal) throws IOException {
		if (journal == null || journal.entries == null) throw new IOException("Journal is empty");
		Path root = worldRoot.toAbsolutePath().normalize();
		List<Move> moves = new ArrayList<>();
		for (JournalEntry e : journal.entries) {
			if (e == null || e.tmp == null || e.target == null) throw new IOException("Journal entry incomplete");
			Path tmp = root.resolve(e.tmp).normalize();
			Path target = root.resolve(e.target).normalize();
			String targetName = target.getFileName() == null ? "" : target.getFileName().toString();
			String tmpName = tmp.getFileName() == null ? "" : tmp.getFileName().toString();
			boolean nameOk = tmpName.equals(targetName + ".tmp") || tmpName.equals(targetName + ".smpeco-tmp");
			if (!tmp.startsWith(root) || !target.startsWith(root) || target.equals(root) || !nameOk || !tmp.getParent().equals(target.getParent())) {
				throw new IOException("invalid journal entry: " + e.tmp + " → " + e.target);
			}
			moves.add(new Move(tmp, target, e.keepOld));
		}
		return moves;
	}

	/** A missing tmp means already done. Returns the number of renames. */
	private static int apply(List<Move> moves) throws IOException {
		int done = 0;
		Set<Path> dirs = new LinkedHashSet<>();
		for (Move m : moves) {
			if (!Files.exists(m.tmp())) continue;
			if (m.keepOld() && Files.isRegularFile(m.target())) move(m.target(), m.target().resolveSibling(m.target().getFileName() + "_old"));
			move(m.tmp(), m.target());
			dirs.add(m.target().getParent());
			done++;
		}
		forceAll(new ArrayList<>(dirs)); // best effort like syncQuietly: directories cannot be opened everywhere
		return done;
	}

	/**
	 * Finishes a committed but not fully renamed group save (idempotent) and deletes the journal. Public only for
	 * selftests.
	 *
	 * @return number of renames, -1 if there is no journal
	 * @throws IOException journal unreadable or invalid, or a rename failed (the journal stays)
	 */
	public static int rollForward(Path worldRoot, Path journal) throws IOException {
		if (!Files.exists(journal)) return -1;
		Journal j;
		try {
			j = GSON.fromJson(Files.readString(journal, StandardCharsets.UTF_8), Journal.class);
		} catch (RuntimeException e) {
			throw new IOException(journal + " is not readable: " + e.getMessage(), e);
		}
		int done = apply(resolve(worldRoot, j));
		Files.delete(journal);
		syncQuietly(journal.getParent());
		return done;
	}

	/** Null if fine, otherwise an error text. */
	private static String finishPendingJournal() {
		if (dataDir == null) return null;
		Path journal = dataDir.resolve(COMMIT_JOURNAL);
		if (!Files.exists(journal)) return null;
		try {
			int done = rollForward(worldRoot(), journal);
			SmpEconomy.LOG.warn("[SMPEco] Resumed an interrupted save ({} files renamed)", done);
			return null;
		} catch (IOException | RuntimeException e) {
			String text = "interrupted save can't be completed (" + e + ")";
			setProblem(text, e);
			return text;
		}
	}

	/**
	 * All or nothing: if any part fails nothing is written and everything stays dirty ({@link #persistenceProblem()}).
	 */
	public static void flushDirty() {
		flush(SmpEconomy.server(), false);
	}

	/**
	 * The mod's main save point: right before vanilla writes player files (start of {@code PlayerList.saveAll}, or
	 * disconnect). Commits all stores and requested player files so the {@code .dat} files and world written next match
	 * the mod files. Best effort: what cannot be written is reported and skipped, since vanilla writes the players
	 * regardless.
	 *
	 * @return true if nothing is dirty and no player save is pending afterwards
	 */
	public static boolean flushBeforeVanillaSave(MinecraftServer server) {
		if (dataDir == null) return false;
		saveAll(server, true);
		preSaveFlushes++;
		lastPreSaveClean = !anyDirty() && !playerSavePending();
		return lastPreSaveClean;
	}

	/**
	 * Like {@link #flushBeforeVanillaSave}, but vanilla also saves the world afterwards (except on datapack reload).
	 * {@link #isWorldSaveCommit()} is true during this commit. Features whose changes come from the world (backpack
	 * auto-pickup: item entity to store) write them only here, not on an arbitrary player's disconnect or
	 * {@code /smpeco save}.
	 */
	public static boolean flushBeforeWorldSave(MinecraftServer server) {
		worldSaveCommit = true;
		try {
			return flushBeforeVanillaSave(server);
		} finally {
			worldSaveCommit = false;
		}
	}

	public static boolean isWorldSaveCommit() {
		return worldSaveCommit;
	}

	private static void flush(MinecraftServer server, boolean bestEffort) {
		runBeforeCommit();
		List<DataStore<?>> dirtyStores = new ArrayList<>();
		for (DataStore<?> store : ALL) {
			if (store.dirty) dirtyStores.add(store);
		}
		CommitResult r = commit(dirtyStores, server, bestEffort);
		lastCommitPoint = System.currentTimeMillis();
		if (r.complete() || r.wrote()) flushCount++;
		if (r.complete()) retrySnapshot();
	}

	/**
	 * {@code hook} runs on the server thread before each group save, before dirty stores are collected. A feature
	 * brings its store up to date here (encode changed backpack slots, record the item held in an open GUI) and may
	 * call {@link #requestPlayerSave(UUID)}; both land in the same journal commit. A throwing hook is logged and the
	 * save goes ahead, since not saving would be worse.
	 */
	public static void beforeCommit(Runnable hook) {
		if (hook != null) BEFORE_COMMIT.add(hook);
	}

	private static void runBeforeCommit() {
		if (dataDir == null || committing) return;
		for (Runnable hook : List.copyOf(BEFORE_COMMIT)) {
			try {
				hook.run();
			} catch (RuntimeException e) {
				SmpEconomy.LOG.error("[SMPEco] A pre-save hook failed, saving anyway", e);
			}
		}
	}

	public static long flushCount() {
		return flushCount;
	}

	/** Best effort (stop, {@code /smpeco save}). */
	public static void saveAll(MinecraftServer server) {
		saveAll(server, true);
	}

	private static void saveAll(MinecraftServer server, boolean bestEffort) {
		runBeforeCommit();
		CommitResult r = commit(List.copyOf(ALL), server, bestEffort);
		if (r.complete() || r.wrote()) flushCount++;
		lastCommitPoint = System.currentTimeMillis();
		if (r.complete()) retrySnapshot();
	}

	/**
	 * Normally a no-op, since saves follow vanilla. Only if no save point occurred for {@value #FALLBACK_INTERVAL_MS}
	 * ms (autosave off, {@code /save-off}) and something is pending does the mod save itself, all or nothing.
	 */
	public static void tick(MinecraftServer server) {
		if (dataDir == null) return;
		long now = System.currentTimeMillis();
		if (now - lastCommitPoint < FALLBACK_INTERVAL_MS) return;
		if (!(playerSavePending() || anyDirty())) {
			lastCommitPoint = now;
			return;
		}
		SmpEconomy.LOG.warn("[SMPEco] No vanilla save for {} min (autosave off?), the mod saves on its own", (now - lastCommitPoint) / 60_000);
		saveAll(server, false);
	}

	/** Forgets the path so a restart within the same process loads cleanly. */
	public static void reset() {
		dataDir = null;
		PLAYERS_TO_SAVE.clear();
		allPlayersToSave = false;
		snapshotRetry = false;
		problem = null;
		startSnapshot = null;
		presentAtStart = Set.of();
		for (DataStore<?> store : ALL) {
			store.loaded = false;
			store.inStartGroup = false;
			store.forgetCommitted();
		}
	}

	private static boolean anyDirty() {
		for (DataStore<?> store : ALL) {
			if (store.dirty) return true;
		}
		return false;
	}

	public static Optional<String> persistenceProblem() {
		if (problem == null) return Optional.empty();
		return Optional.of("since " + PROBLEM_TIME.withZone(TimeUtil.zone()).format(Instant.ofEpochMilli(problemSince)) + ": " + problem);
	}

	public static List<String> dirtyStoreNames() {
		List<String> names = new ArrayList<>();
		for (DataStore<?> s : ALL) {
			if (s.dirty) names.add(s.fileName);
		}
		return names;
	}

	public static List<String> managedStoreNames() {
		return names(ALL);
	}

	public static List<String> storesOutsideStartGroup() {
		List<String> names = new ArrayList<>();
		for (DataStore<?> s : ALL) {
			if (!s.inStartGroup) names.add(s.fileName);
		}
		return names;
	}

	public static String startSnapshot() {
		return startSnapshot;
	}

	public static Set<String> presentAtStart() {
		return presentAtStart;
	}

	public static boolean journalPending() {
		return dataDir != null && Files.exists(dataDir.resolve(COMMIT_JOURNAL));
	}

	public static long filesWritten() {
		return filesWritten;
	}

	public static long writesSkipped() {
		return writesSkipped;
	}

	public static long failedCommits() {
		return failedCommits;
	}

	public static long preSaveFlushCount() {
		return preSaveFlushes;
	}

	public static boolean lastPreSaveClean() {
		return lastPreSaveClean;
	}

	private static void setProblem(String text, Throwable cause) {
		long now = System.currentTimeMillis();
		if (problem == null) problemSince = now;
		boolean changed = !text.equals(problem);
		problem = text;
		failedCommits++;
		if (changed || now - lastProblemLog >= PROBLEM_LOG_INTERVAL_MS) {
			lastProblemLog = now;
			SmpEconomy.LOG.error("[SMPEco] SAVING IS FAILING: {}. The affected changes exist only in memory and will be retried. "
				+ "Fix the cause (disk space, permissions, log); /smpeco status", text, cause);
		}
	}

	private static void clearProblem() {
		if (problem == null) return;
		SmpEconomy.LOG.warn("[SMPEco] Saving works again (problem lasted {} ms)", System.currentTimeMillis() - problemSince);
		problem = null;
	}

	private record Prepared(DataStore<?> store, Path tmp, Path file, byte[] digest) {}

	private record PreparedPlayer(Path tmp, Path file) {}

	private record CommitResult(boolean complete, boolean wrote) {}

	/**
	 * {@code bestEffort} false: all or nothing. True: what cannot be prepared is skipped and the rest written; only
	 * used right before vanilla's player save.
	 */
	private static CommitResult commit(List<DataStore<?>> stores, MinecraftServer server, boolean bestEffort) {
		if (dataDir == null || committing) return new CommitResult(false, false);
		committing = true;
		long start = System.nanoTime();
		long players = playerSaves;
		long files = filesWritten;
		try {
			return commitGroup(stores, server, bestEffort);
		} finally {
			committing = false;
			long ms = (System.nanoTime() - start) / 1_000_000;
			lastCommitMs = ms;
			if (ms >= SLOW_COMMIT_MS) {
				slowCommits++;
				// The server thread is blocked for this long (serializing, hashing, fsync), so make the cost visible.
				SmpEconomy.LOG.warn("[SMPEco] Saving took {} ms ({} stores checked, {} files + {} player files written)", ms, stores.size(),
					filesWritten - files, playerSaves - players);
			}
		}
	}

	public static long millisSinceCommitPoint() {
		return System.currentTimeMillis() - lastCommitPoint;
	}

	public static long lastCommitMs() {
		return lastCommitMs;
	}

	public static long slowCommits() {
		return slowCommits;
	}

	public static boolean commitStores(List<DataStore<?>> stores, boolean bestEffort) {
		return commit(stores, SmpEconomy.server(), bestEffort).complete();
	}

	private static CommitResult commitGroup(List<DataStore<?>> stores, MinecraftServer server, boolean bestEffort) {
		// Finish a leftover journal first: new .tmp files would overwrite its committed ones.
		if (finishPendingJournal() != null) return new CommitResult(false, false);
		List<String> failures = new ArrayList<>();
		Throwable firstError = null;
		List<Prepared> prepared = new ArrayList<>();
		List<DataStore<?>> cleared = new ArrayList<>();
		for (DataStore<?> s : stores) {
			if (!s.loaded) continue;
			Path file = s.path();
			byte[] bytes;
			byte[] digest;
			try {
				bytes = GSON.toJson(s.value).getBytes(StandardCharsets.UTF_8);
				digest = sha256(bytes);
			} catch (RuntimeException e) {
				s.dirty = true;
				failures.add(s.fileName + " (data cannot be saved: " + e.getMessage() + ")");
				if (firstError == null) firstError = e;
				continue;
			}
			if (s.dirty) {
				s.dirty = false;
				cleared.add(s);
			}
			if (s.unchangedOnDisk(file, digest)) {
				writesSkipped++;
				continue;
			}
			Path tmp = tmpPath(file);
			try {
				writeFile(tmp, bytes);
				prepared.add(new Prepared(s, tmp, file, digest));
			} catch (IOException | RuntimeException e) {
				s.dirty = true;
				deleteQuietly(tmp);
				failures.add(s.fileName + " (" + e + ")");
				if (firstError == null) firstError = e;
			}
		}
		if (!failures.isEmpty() && !bestEffort) return abort(prepared, List.of(), cleared, failures, firstError);
		// False means not possible right now; the requests stay.
		List<PreparedPlayer> players = new ArrayList<>();
		boolean playersHandled = preparePlayers(server, bestEffort, players, failures);
		if (!failures.isEmpty() && !bestEffort) return abort(prepared, players, cleared, failures, firstError);
		// fsync takes several ms on slow disks, which added up on the server thread when run in sequence. The journal
		// is only written once all of them are on disk.
		List<Path> toSync = new ArrayList<>(prepared.size() + players.size());
		for (Prepared p : prepared) toSync.add(p.tmp());
		for (PreparedPlayer p : players) toSync.add(p.tmp());
		Map<Path, Throwable> syncErrors = forceAll(toSync);
		if (!syncErrors.isEmpty()) {
			for (java.util.Iterator<Prepared> it = prepared.iterator(); it.hasNext();) {
				Prepared p = it.next();
				Throwable e = syncErrors.get(p.tmp());
				if (e == null) continue;
				it.remove();
				p.store().dirty = true;
				deleteQuietly(p.tmp());
				failures.add(p.store().fileName + " (" + e + ")");
				if (firstError == null) firstError = e;
			}
			for (java.util.Iterator<PreparedPlayer> it = players.iterator(); it.hasNext();) {
				PreparedPlayer p = it.next();
				Throwable e = syncErrors.get(p.tmp());
				if (e == null) continue;
				it.remove();
				deleteQuietly(p.tmp());
				failures.add("player file " + p.file().getFileName() + " (" + e + ")");
				SmpEconomy.LOG.error("[SMPEco] Player file {} not prepared", p.file().getFileName(), e);
				if (firstError == null) firstError = e;
			}
			if (!bestEffort) return abort(prepared, players, cleared, failures, firstError);
		}
		if (prepared.isEmpty() && players.isEmpty()) {
			if (playersHandled) consumePlayerRequests();
			if (failures.isEmpty()) clearProblem();
			else setProblem("not written: " + String.join("; ", failures), firstError);
			return new CommitResult(failures.isEmpty(), false);
		}
		// Commit point: write the journal atomically.
		Path root = worldRoot();
		Journal journal = new Journal();
		List<Move> moves = new ArrayList<>();
		for (Prepared p : prepared) {
			journal.entries.add(new JournalEntry(relative(root, p.tmp()), relative(root, p.file()), false));
			moves.add(new Move(p.tmp(), p.file(), false));
		}
		for (PreparedPlayer p : players) {
			journal.entries.add(new JournalEntry(relative(root, p.tmp()), relative(root, p.file()), true));
			moves.add(new Move(p.tmp(), p.file(), true));
		}
		Path journalFile = dataDir.resolve(COMMIT_JOURNAL);
		try {
			writeAtomic(journalFile, GSON.toJson(journal).getBytes(StandardCharsets.UTF_8));
		} catch (IOException | RuntimeException e) {
			deleteQuietly(journalFile);
			failures.add(COMMIT_JOURNAL + " (" + e + ")");
			return abort(prepared, players, cleared, failures, e);
		}
		if (playersHandled) consumePlayerRequests();
		try {
			apply(moves);
			Files.delete(journalFile);
			syncQuietly(dataDir);
		} catch (IOException | RuntimeException e) {
			// Committed but not fully renamed: the journal stays and is finished before the next save or on next start.
			// Nothing new is written until then.
			for (Prepared p : prepared) {
				p.store().forgetCommitted();
				p.store().dirty = true;
			}
			failures.add("renaming (" + e + ") – will be resumed");
			setProblem(String.join("; ", failures), e);
			return new CommitResult(false, true);
		}
		for (Prepared p : prepared) p.store().markCommitted(p.file(), p.digest());
		filesWritten += prepared.size();
		playerSaves += players.size();
		if (failures.isEmpty()) clearProblem();
		else setProblem("not written: " + String.join("; ", failures), firstError);
		return new CommitResult(failures.isEmpty(), true);
	}

	private static CommitResult abort(List<Prepared> prepared, List<PreparedPlayer> players, List<DataStore<?>> cleared, List<String> failures,
		Throwable cause) {
		for (Prepared p : prepared) deleteQuietly(p.tmp());
		for (PreparedPlayer p : players) deleteQuietly(p.tmp());
		for (DataStore<?> s : cleared) s.dirty = true;
		setProblem("nothing written (all or nothing): " + String.join("; ", failures), cause);
		return new CommitResult(false, false);
	}

	private static void consumePlayerRequests() {
		allPlayersToSave = false;
		PLAYERS_TO_SAVE.clear();
	}

	/**
	 * Writes the {@code .dat} of requested online players like vanilla, without stats or advancements, as
	 * {@code <uuid>.dat.smpeco-tmp}; fsync follows in {@link #forceAll}.
	 *
	 * @return false if not possible right now (no server, wrong thread); the requests stay
	 */
	private static boolean preparePlayers(MinecraftServer server, boolean bestEffort, List<PreparedPlayer> out, List<String> failures) {
		if (!playerSavePending()) return true;
		if (server == null || !server.isSameThread()) return false;
		PlayerList list = server.getPlayerList();
		if (list == null) return false;
		List<ServerPlayer> targets = new ArrayList<>();
		if (allPlayersToSave) {
			targets.addAll(list.getPlayers());
		} else {
			for (UUID id : PLAYERS_TO_SAVE) {
				ServerPlayer p = list.getPlayer(id); // not online or FakePlayer: null
				if (p != null) targets.add(p);
			}
		}
		if (targets.isEmpty()) return true;
		Path playerDir = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
		for (ServerPlayer p : targets) {
			Path file = playerDir.resolve(p.getStringUUID() + ".dat");
			Path tmp = playerDir.resolve(p.getStringUUID() + PLAYER_TMP_SUFFIX);
			try {
				Files.createDirectories(playerDir);
				NbtIo.writeCompressed(serializePlayer(p), tmp);
				out.add(new PreparedPlayer(tmp, file));
			} catch (IOException | RuntimeException e) {
				deleteQuietly(tmp);
				failures.add("player file of " + p.getPlainTextName() + " (" + e + ")");
				SmpEconomy.LOG.error("[SMPEco] Player file of {} not prepared", p.getPlainTextName(), e);
				if (!bestEffort) break;
			}
		}
		return true;
	}

	/** Same as {@code PlayerDataStorage.save}, including DataVersion. */
	private static CompoundTag serializePlayer(ServerPlayer player) {
		try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(player.problemPath(), SmpEconomy.LOG)) {
			TagValueOutput out = TagValueOutput.createWithContext(reporter, player.registryAccess());
			player.saveWithoutId(out);
			return out.buildResult();
		}
	}

	private static byte[] sha256(byte[] data) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(data);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is missing", e);
		}
	}

	private static void deleteQuietly(Path p) {
		try {
			Files.deleteIfExists(p);
		} catch (IOException | RuntimeException ignored) {
			// Best effort: a stray .tmp without a journal counts as never committed at startup.
		}
	}

	private static void forceSync(Path file) throws IOException {
		try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
			ch.force(true);
		}
	}

	/** Own daemon threads, IO only; they exit after 30 s idle. */
	static final int SYNC_THREADS = 8;
	private static java.util.concurrent.ThreadPoolExecutor syncPool;

	private static synchronized java.util.concurrent.ExecutorService syncPool() {
		if (syncPool == null) {
			java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
			syncPool = new java.util.concurrent.ThreadPoolExecutor(SYNC_THREADS, SYNC_THREADS, 30, java.util.concurrent.TimeUnit.SECONDS,
				new java.util.concurrent.LinkedBlockingQueue<>(), r -> {
					Thread t = new Thread(r, "smpeco-fsync-" + n.incrementAndGet());
					t.setDaemon(true);
					return t;
				});
			syncPool.allowCoreThreadTimeOut(true);
		}
		return syncPool;
	}

	/**
	 * Same guarantee as fsyncing in sequence, but on slow disks it takes roughly as long as the slowest fsync rather
	 * than the sum, since ext4 merges concurrent fsyncs into one journal commit. Public only for selftests.
	 *
	 * @return paths whose fsync failed, empty if all succeeded
	 */
	public static Map<Path, Throwable> forceAll(List<Path> paths) {
		Map<Path, Throwable> errors = new LinkedHashMap<>();
		if (paths.isEmpty()) return errors;
		if (paths.size() == 1) {
			try {
				forceSync(paths.get(0));
			} catch (IOException | RuntimeException e) {
				errors.put(paths.get(0), e);
			}
			return errors;
		}
		List<java.util.concurrent.Future<?>> futures = new ArrayList<>(paths.size());
		for (Path p : paths) {
			java.util.concurrent.Future<?> f;
			try {
				f = syncPool().submit(() -> {
					forceSync(p);
					return null;
				});
			} catch (RuntimeException rejected) {
				f = null;
			}
			futures.add(f);
		}
		boolean interrupted = false;
		for (int i = 0; i < paths.size(); i++) {
			Path p = paths.get(i);
			java.util.concurrent.Future<?> f = futures.get(i);
			try {
				if (f == null) forceSync(p); // pool unavailable: do it here
				else f.get();
			} catch (java.util.concurrent.ExecutionException e) {
				errors.put(p, e.getCause() != null ? e.getCause() : e);
			} catch (InterruptedException e) {
				interrupted = true;
				errors.put(p, e); // result unknown: treat as failed, nothing gets committed unless surely on disk
			} catch (IOException | RuntimeException e) {
				errors.put(p, e);
			}
		}
		if (interrupted) Thread.currentThread().interrupt();
		return errors;
	}

	public static void writeAtomic(Path file, byte[] data) throws IOException {
		Path tmp = tmpPath(file);
		writeAndSync(tmp, data);
		move(tmp, file);
		syncQuietly(file.getParent());
	}

	private static Path tmpPath(Path file) {
		return file.resolveSibling(file.getFileName() + ".tmp");
	}

	private static void writeAndSync(Path file, byte[] data) throws IOException {
		try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
			ByteBuffer buf = ByteBuffer.wrap(data);
			while (buf.hasRemaining()) ch.write(buf);
			ch.force(true);
		}
	}

	/** Like {@link #writeAndSync} but without fsync, which follows in {@link #forceAll}. */
	private static void writeFile(Path file, byte[] data) throws IOException {
		try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
			ByteBuffer buf = ByteBuffer.wrap(data);
			while (buf.hasRemaining()) ch.write(buf);
		}
	}

	private static void move(Path from, Path to) throws IOException {
		try {
			Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** Best effort (Windows cannot open directories). */
	static void syncQuietly(Path path) {
		if (path == null) return;
		try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
			ch.force(true);
		} catch (IOException | RuntimeException ignored) {
			// best effort
		}
	}
}
