package dev.smpeconomy.features.holo;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntFunction;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import dev.smpeconomy.core.compat.Mc;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.Scheduler;
import dev.smpeconomy.core.TimeUtil;
import dev.smpeconomy.features.holo.HoloStore.Holo;

/**
 * Owns the {@code text_display} entities of the holograms.
 *
 * <p>Avoiding duplicates: each display carries the tags {@value #TAG} and {@code smpeco_holo_<id>}, and
 * {@link #SESSION} holds the UUID of the one valid display per hologram for this server session. Any tagged display
 * loaded into a world (chunk load, restart, {@code /summon}) is checked on the next tick and removed unless its UUID
 * belongs to the current session. At startup the session is empty, so all old displays disappear on load and get
 * placed again.
 *
 * <p>A display is placed once its chunk is at least FULL with entities loaded ({@link #canSpawnAt}). It does not need
 * to tick: players still see chunks outside simulation distance, which ServerCore lowers under load and where C2ME
 * only loads to FULL. In a FULL chunk entities are {@code Visibility.TRACKED}, so a saved display can be found by
 * UUID and ENTITY_LOAD fires for copies. A new display is only kept if it can be found afterwards; otherwise it is
 * removed at once, which rules out invisible duplicates.
 *
 * <p>Everything runs on the server thread. ENTITY_LOAD checks are deferred to the end of the tick, never while
 * vanilla iterates a section's entities.
 */
final class HoloManager {
	static final String TAG = "smpeco_holo";
	static final String TAG_ID = "smpeco_holo_";
	private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

	/** Hologram id to UUID of the display placed this session. */
	private static final Map<Integer, UUID> SESSION = new HashMap<>();
	/** Hologram id to loaded display; may be unloaded or removed, so check isRemoved. */
	private static final Map<Integer, Display.TextDisplay> LIVE = new HashMap<>();
	private static final Map<HoloType, List<String>> LINES = new EnumMap<>(HoloType.class);
	/** Tagged displays reported by ENTITY_LOAD, checked at the end of the tick. */
	private static final List<Entity> PENDING = new ArrayList<>();
	private static final Set<String> WARNED_DIMENSIONS = new HashSet<>();

	private static Scheduler.Task task;
	private static boolean running;
	private static int secondsSinceUpdate;

	private HoloManager() {}

	static void registerEvents() {
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof Display.TextDisplay && entity.getTags().contains(TAG)) PENDING.add(entity);
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> processPending());
	}

	/** Starts a new session: secure entities once per second, rewrite text every {@code updateSeconds}. */
	static void start(MinecraftServer server) {
		SESSION.clear();
		LIVE.clear();
		LINES.clear();
		WARNED_DIMENSIONS.clear();
		secondsSinceUpdate = 0;
		running = true;
		if (task != null) task.cancel();
		task = Scheduler.runRepeating(20, 20, () -> tickSecond(server));
	}

	/**
	 * Removes this session's displays. The next session would replace them anyway, but this leaves nothing behind in
	 * loaded chunks if the mod is ever removed.
	 */
	static void stop() {
		running = false;
		if (task != null) task.cancel();
		task = null;
		for (Display.TextDisplay e : LIVE.values()) {
			if (e != null && !e.isRemoved()) e.discard();
		}
		LIVE.clear();
		SESSION.clear();
		LINES.clear();
		PENDING.clear();
	}

	private static void tickSecond(MinecraftServer server) {
		if (!running || SmpEconomy.isStopping()) return;
		ensureAll(server);
		if (++secondsSinceUpdate >= HoloConfig.get().updateSeconds) {
			secondsSinceUpdate = 0;
			updateAll(server);
		}
	}

	/** Recalculates all leaderboards and returns the number of displays updated. */
	static int updateAll(MinecraftServer server) {
		Set<HoloType> used = EnumSet.noneOf(HoloType.class);
		for (Holo h : HoloStore.data().holos) h.holoType().ifPresent(used::add);
		LINES.keySet().retainAll(used);
		for (HoloType t : used) LINES.put(t, compute(server, t));
		int updated = 0;
		for (Holo h : HoloStore.data().holos) {
			Display.TextDisplay e = live(h.id);
			if (e == null) continue;
			apply(e, h, server);
			updated++;
		}
		return updated;
	}

	static void ensureAll(MinecraftServer server) {
		for (Holo h : List.copyOf(HoloStore.data().holos)) {
			try {
				ensure(server, h);
			} catch (RuntimeException ex) {
				SmpEconomy.LOG.error("[SMPEco] holo: could not place hologram #{}", h.id, ex);
			}
		}
	}

	private static void ensure(MinecraftServer server, Holo h) {
		if (h.holoType().isEmpty()) return;
		ServerLevel level = level(server, h.dimension);
		if (level == null) {
			if (WARNED_DIMENSIONS.add(h.dimension)) SmpEconomy.LOG.warn("[SMPEco] holo: dimension {} of hologram #{} does not exist", h.dimension, h.id);
			return;
		}
		Display.TextDisplay e = live(h.id);
		if (e != null && e.level() != level) {
			LIVE.remove(h.id);
			e = null;
		}
		if (e == null) {
			UUID u = SESSION.get(h.id);
			Entity found = u == null ? null : level.getEntity(u);
			if (found instanceof Display.TextDisplay td && !td.isRemoved()) {
				LIVE.put(h.id, td);
				apply(td, h, server);
				e = td;
			}
		}
		if (e == null) {
			if (!Mc.canSpawnAt(level, h.x, h.z)) return;
			spawn(server, level, h);
			return;
		}
		// Moved, e.g. by /tp. needsSync also sends it outside simulation distance.
		if (e.distanceToSqr(h.x, h.y, h.z) > 0.25) {
			e.teleportTo(h.x, h.y, h.z);
			e.needsSync = true;
		}
	}

	/** Placing a new display invalidates the old session UUID. */
	static Display.TextDisplay spawn(MinecraftServer server, ServerLevel level, Holo h) {
		Display.TextDisplay td = EntityType.TEXT_DISPLAY.create(level, EntitySpawnReason.COMMAND);
		if (td == null) return null;
		td.snapTo(h.x, h.y, h.z, 0f, 0f);
		td.addTag(TAG);
		td.addTag(TAG_ID + h.id);
		apply(td, h, server);
		// Record before addFreshEntity: ENTITY_LOAD fires inside it and the check must see this display as valid.
		UUID previous = SESSION.put(h.id, td.getUUID());
		if (!level.addFreshEntity(td)) {
			restoreSession(h.id, previous);
			SmpEconomy.LOG.warn("[SMPEco] holo: could not place display for hologram #{}", h.id);
			return null;
		}
		if (level.getEntity(td.getUUID()) != td) {
			// Section not visible yet: an existing display couldn't be found here either, so don't keep this one.
			// The next pass tries again.
			td.discard();
			restoreSession(h.id, previous);
			SmpEconomy.LOG.debug("[SMPEco] holo: chunk of hologram #{} not visible yet, display discarded", h.id);
			return null;
		}
		Display.TextDisplay old = LIVE.put(h.id, td);
		if (old != null && old != td && !old.isRemoved()) old.discard();
		return td;
	}

	private static void restoreSession(int id, UUID previous) {
		if (previous != null) SESSION.put(id, previous);
		else SESSION.remove(id);
	}

	/** If the display is not loaded it disappears on its next load. */
	static void despawn(MinecraftServer server, Holo h) {
		Display.TextDisplay e = LIVE.remove(h.id);
		UUID u = SESSION.remove(h.id);
		if (e != null && !e.isRemoved()) {
			e.discard();
			return;
		}
		ServerLevel level = server == null ? null : level(server, h.dimension);
		Entity found = level == null || u == null ? null : level.getEntity(u);
		if (found != null && !found.isRemoved()) found.discard();
	}

	/** The creator is standing there, so the chunk is loaded. */
	static boolean create(MinecraftServer server, Holo h) {
		ServerLevel level = level(server, h.dimension);
		HoloType type = h.holoType().orElse(null);
		if (level == null || type == null) return false;
		LINES.put(type, compute(server, type));
		return spawn(server, level, h) != null;
	}

	/** Runs at the end of the tick; selftests call it directly. */
	static void processPending() {
		if (PENDING.isEmpty() || !running || !HoloStore.STORE.isLoaded()) return;
		MinecraftServer server = SmpEconomy.server();
		List<Entity> batch = new ArrayList<>(PENDING);
		PENDING.clear();
		for (Entity e : batch) {
			if (e.isRemoved() || !(e instanceof Display.TextDisplay td)) continue;
			int id = owner(td.getUUID(), dimensionId(td.level()), SESSION, HoloStore.data()::byId);
			if (id < 0) {
				SmpEconomy.LOG.info("[SMPEco] holo: removed stale hologram display ({} {})", td.getTags(), td.getUUID());
				td.discard();
				continue;
			}
			Holo h = HoloStore.data().byId(id);
			LIVE.put(id, td);
			if (h != null && server != null) apply(td, h, server);
		}
	}

	/**
	 * A loaded display belongs to a hologram only if its UUID is the session UUID of an existing hologram in the same
	 * dimension. Otherwise -1, meaning remove it.
	 */
	static int owner(UUID entity, String dimension, Map<Integer, UUID> session, IntFunction<Holo> holos) {
		if (entity == null) return -1;
		for (Map.Entry<Integer, UUID> en : session.entrySet()) {
			if (!entity.equals(en.getValue())) continue;
			Holo h = holos.apply(en.getKey());
			return h != null && h.holoType().isPresent() && HoloStore.sameDimension(h.dimension, dimension) ? en.getKey() : -1;
		}
		return -1;
	}

	/**
	 * SynchedEntityData only sends real changes. Outside simulation distance vanilla sends changes only with
	 * {@code needsSync}; without it the text would stay stale there.
	 */
	static void apply(Display.TextDisplay e, Holo h, MinecraftServer server) {
		style(e, HoloConfig.get());
		HoloType type = h.holoType().orElse(null);
		if (type != null) e.setText(HoloText.component(lines(server, type)));
		if (e.getEntityData().isDirty()) e.needsSync = true;
	}

	static void style(Display.TextDisplay e, HoloConfig cfg) {
		e.setBillboardConstraints(cfg.billboardConstraints());
		e.setBackgroundColor(cfg.backgroundArgb());
		e.setLineWidth(cfg.lineWidth);
		byte flags = 0;
		if (cfg.shadow) flags |= Display.TextDisplay.FLAG_SHADOW;
		if (cfg.seeThrough) flags |= Display.TextDisplay.FLAG_SEE_THROUGH;
		e.setFlags(flags);
		e.setViewRange(cfg.viewRange);
		e.setBrightnessOverride(cfg.fullBright ? Brightness.FULL_BRIGHT : null);
	}

	/** Lines from the last update's cache, otherwise calculated now. */
	static List<String> lines(MinecraftServer server, HoloType type) {
		List<String> cached = LINES.get(type);
		if (cached != null) return cached;
		List<String> fresh = compute(server, type);
		LINES.put(type, fresh);
		return fresh;
	}

	private static List<String> compute(MinecraftServer server, HoloType type) {
		HoloConfig cfg = HoloConfig.get();
		List<HoloText.Row> rows = new ArrayList<>();
		try {
			for (HoloText.Score s : HoloText.rank(Leaderboards.collect(server, type), cfg.entries)) rows.add(s.row());
		} catch (RuntimeException ex) {
			SmpEconomy.LOG.error("[SMPEco] holo: leaderboard {} failed", type.id, ex);
		}
		return HoloText.lines(type, rows, cfg.entries, TimeUtil.now().format(HH_MM));
	}

	static Display.TextDisplay live(int id) {
		Display.TextDisplay e = LIVE.get(id);
		if (e != null && e.isRemoved()) {
			LIVE.remove(id);
			return null;
		}
		return e;
	}

	static String plainText(MinecraftServer server, Holo h) {
		Display.TextDisplay e = live(h.id);
		if (e != null) return e.getText().getString();
		return h.holoType().map(t -> HoloText.plain(lines(server, t))).orElse("");
	}

	static Component textOf(Display.TextDisplay e) {
		return e.getText();
	}

	static ServerLevel level(MinecraftServer server, String dimension) {
		Identifier id = Identifier.tryParse(HoloStore.normalizeDimension(dimension));
		return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
	}

	static String dimensionId(Level level) {
		return level.dimension().identifier().toString();
	}
}
