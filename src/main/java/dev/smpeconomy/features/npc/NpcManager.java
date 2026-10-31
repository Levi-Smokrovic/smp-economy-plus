package dev.smpeconomy.features.npc;

import com.mojang.math.Transformation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Brightness;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import dev.smpeconomy.core.CommandRegistry;
import dev.smpeconomy.core.compat.Mc;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Perms;
import dev.smpeconomy.core.Scheduler;

/**
 * Places and maintains NPC and text display entities and handles clicks on NPCs. An NPC is a body ({@link NpcBody}:
 * invulnerable, immobile, no gravity, silent) plus a {@code text_display} name tag; a text is one
 * {@code text_display}.
 *
 * <p>Avoiding duplicates works as in features/holo: each entity carries {@value #TAG} and {@code smpeco_npc_<key>},
 * and {@link #SESSION} holds the one valid UUID per part. Any loaded entity with our tag (chunk load, restart,
 * {@code /summon} copy) is checked at the end of the tick and removed unless its UUID is current. {@link #ensureAll}
 * replaces missing parts once per second as soon as their chunk and entities are loaded, and moves displaced ones
 * back. Stopping removes all entities of this session.
 *
 * <p>Clicks go through {@link UseEntityCallback} and {@link AttackEntityCallback}: right-click runs the action, and
 * vanilla behavior such as damage, name tags and leads is always cancelled. Sneaking admins ({@link NpcFeature#NODE})
 * get the NPC info with edit links instead. Commands run as the clicking player ({@code performPrefixedCommand}),
 * with the same permissions and cooldowns as typing. Server thread only.
 */
final class NpcManager {
	static final String TAG = "smpeco_npc";
	static final String TAG_KEY = "smpeco_npc_";
	private static final int LOOK_PERIOD = 4;

	enum Kind { BODY, PLATE, TEXT }

	/** {@code owner} is the NPC or text id. */
	record Part(String key, Kind kind, String owner, String dimension, double x, double y, double z, float yaw) {}

	private static final Map<String, Part> PARTS = new LinkedHashMap<>();
	private static final Map<String, NpcConfig.Npc> NPCS = new HashMap<>();
	private static final Map<String, NpcConfig.Text> TEXTS = new HashMap<>();
	/** Part key to UUID of the entity placed this session. */
	private static final Map<String, UUID> SESSION = new HashMap<>();
	/** Part key to loaded entity; may be removed, so check isRemoved. */
	private static final Map<String, Entity> LIVE = new HashMap<>();
	private static final Map<UUID, Part> BY_UUID = new HashMap<>();
	private static final List<Entity> PENDING = new ArrayList<>();
	private static final Map<UUID, Long> LAST_CLICK = new HashMap<>();
	private static final Set<String> WARNED = new HashSet<>();

	private static volatile boolean configChanged = true;
	/** Selftests can use their own config; null means npc.json. */
	private static NpcConfig override;
	private static boolean running;
	private static Scheduler.Task ensureTask;
	private static long tick;
	/** Clicks that triggered an action, for status and e2e. */
	static int clicks;
	/** For e2e and selftests: {@code <npc> <player> <command|error:…|none>}. */
	static String lastAction = "";

	private NpcManager() {}

	static void markConfigChanged() {
		configChanged = true;
	}

	static NpcConfig config() {
		return override != null ? override : NpcConfig.get();
	}

	/** Null returns to npc.json; everything is placed again. */
	static void setOverride(NpcConfig cfg) {
		override = cfg;
		configChanged = true;
	}

	static boolean overrideActive() {
		return override != null;
	}

	static void registerEvents() {
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity.getTags().contains(TAG)) PENDING.add(entity);
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			tick++;
			processPending();
			if (running && tick % LOOK_PERIOD == 0) lookTick();
		});
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> onClick(player, level.isClientSide(), entity, true));
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> onClick(player, level.isClientSide(), entity, false));
	}

	static void start(MinecraftServer server) {
		SESSION.clear();
		LIVE.clear();
		BY_UUID.clear();
		LAST_CLICK.clear();
		WARNED.clear();
		configChanged = true;
		running = true;
		if (ensureTask != null) ensureTask.cancel();
		ensureTask = Scheduler.runRepeating(20, 20, () -> ensureAll(server));
	}

	static void stop() {
		running = false;
		if (ensureTask != null) ensureTask.cancel();
		ensureTask = null;
		discardAll();
		PENDING.clear();
		PARTS.clear();
	}

	private static void discardAll() {
		for (Entity e : LIVE.values()) {
			if (e != null && !e.isRemoved()) e.discard();
		}
		LIVE.clear();
		SESSION.clear();
		BY_UUID.clear();
	}

	static void rebuild() {
		configChanged = false;
		discardAll();
		PARTS.clear();
		NPCS.clear();
		TEXTS.clear();
		NpcConfig cfg = config();
		if (!cfg.enabled) return;
		for (NpcConfig.Npc n : cfg.npcs) {
			NPCS.put(n.id, n);
			NpcLayout.Place pl = NpcLayout.npcPlace(n);
			add(new Part("n." + n.id + ".body", Kind.BODY, n.id, n.dimension, pl.x(), pl.y(), pl.z(), pl.yaw()));
			add(new Part("n." + n.id + ".plate", Kind.PLATE, n.id, n.dimension, pl.x(), pl.y() + cfg.plateHeight, pl.z(), pl.yaw()));
		}
		for (NpcConfig.Text t : cfg.texts) {
			TEXTS.put(t.id, t);
			NpcLayout.Place pl = NpcLayout.textPlace(t);
			add(new Part("t." + t.id, Kind.TEXT, t.id, t.dimension, pl.x(), pl.y(), pl.z(), 0f));
		}
	}

	private static void add(Part p) {
		PARTS.put(p.key(), p);
	}

	static void ensureAll(MinecraftServer server) {
		if (!running || SmpEconomy.isStopping()) return;
		if (configChanged) rebuild();
		for (Part p : List.copyOf(PARTS.values())) {
			try {
				ensure(server, p);
			} catch (RuntimeException ex) {
				if (WARNED.add("ensure:" + p.key())) SmpEconomy.LOG.error("[SMPEco] npc: {} could not be placed", p.key(), ex);
			}
		}
	}

	private static void ensure(MinecraftServer server, Part p) {
		ServerLevel level = level(server, p.dimension());
		if (level == null) {
			if (WARNED.add("dim:" + p.dimension())) SmpEconomy.LOG.warn("[SMPEco] npc: dimension {} does not exist", p.dimension());
			return;
		}
		Entity e = live(p.key());
		if (e != null && e.level() != level) {
			LIVE.remove(p.key());
			e = null;
		}
		if (e == null) {
			UUID u = SESSION.get(p.key());
			Entity found = u == null ? null : level.getEntity(u);
			if (found != null && !found.isRemoved()) {
				LIVE.put(p.key(), found);
				BY_UUID.put(found.getUUID(), p);
				e = found;
			}
		}
		if (e == null) {
			if (Mc.canSpawnAt(level, p.x(), p.z())) spawn(level, p);
			return;
		}
		// Moved by an explosion, /tp, a piston. needsSync also sends it outside simulation distance.
		if (e.distanceToSqr(p.x(), p.y(), p.z()) > 0.0025 || (p.kind() == Kind.BODY && Math.abs(e.getYRot() - p.yaw()) > 0.5f)) {
			e.snapTo(p.x(), p.y(), p.z(), p.kind() == Kind.BODY ? p.yaw() : e.getYRot(), p.kind() == Kind.BODY ? 0f : e.getXRot());
			e.setDeltaMovement(Vec3.ZERO);
			if (e instanceof LivingEntity le) {
				le.setYBodyRot(p.yaw());
				le.setYHeadRot(p.yaw());
			}
			e.needsSync = true;
		}
	}

	/** Null if it failed; the next pass tries again. */
	static Entity spawn(ServerLevel level, Part p) {
		Entity e = create(level, p);
		if (e == null) return null;
		e.addTag(TAG);
		e.addTag(TAG_KEY + p.key());
		// Record before addFreshEntity: ENTITY_LOAD fires inside it and the check must see this entity as valid.
		UUID previous = SESSION.put(p.key(), e.getUUID());
		if (!level.addFreshEntity(e) || level.getEntity(e.getUUID()) != e) {
			if (!e.isRemoved()) e.discard();
			if (previous != null) SESSION.put(p.key(), previous);
			else SESSION.remove(p.key());
			return null;
		}
		Entity old = LIVE.put(p.key(), e);
		if (old != null && old != e && !old.isRemoved()) old.discard();
		BY_UUID.put(e.getUUID(), p);
		return e;
	}

	private static Entity create(ServerLevel level, Part p) {
		NpcConfig cfg = config();
		return switch (p.kind()) {
			case BODY -> {
				NpcConfig.Npc n = NPCS.get(p.owner());
				yield NpcBody.create(level, p.x(), p.y(), p.z(), p.yaw(), n, item -> {
					if (WARNED.add("equip:" + n.id + ":" + item)) NpcBody.logUnknownItem(n.id, item);
				});
			}
			case PLATE -> {
				NpcConfig.Npc n = NPCS.get(p.owner());
				yield textDisplay(level, p, plateText(n), cfg);
			}
			case TEXT -> {
				NpcConfig.Text t = TEXTS.get(p.owner());
				Display.TextDisplay td = textDisplay(level, p, String.join("\n", t.lines), cfg);
				if (td != null) {
					td.setBackgroundColor(NpcLayout.parseArgb(cfg.textBackground));
					td.setLineWidth(t.lineWidth);
					td.setViewRange(1.5f);
					// A scale other than 1 grows upward from the bottom edge.
					float sc = (float) t.effectiveScale();
					if (sc != 1f) td.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(sc, sc, sc), new Quaternionf()));
				}
				yield td;
			}
		};
	}

	static String plateText(NpcConfig.Npc n) {
		return n.description.isEmpty() ? n.name : n.name + "\n" + n.description;
	}

	private static Display.TextDisplay textDisplay(ServerLevel level, Part p, String text, NpcConfig cfg) {
		Display.TextDisplay td = EntityType.TEXT_DISPLAY.create(level, EntitySpawnReason.COMMAND);
		if (td == null) return null;
		td.snapTo(p.x(), p.y(), p.z(), 0f, 0f);
		td.setText(Msg.legacy(text));
		td.setBillboardConstraints(Display.BillboardConstraints.VERTICAL);
		td.setBackgroundColor(NpcLayout.parseArgb(cfg.plateBackground));
		td.setLineWidth(240);
		td.setFlags(Display.TextDisplay.FLAG_SHADOW);
		td.setBrightnessOverride(Brightness.FULL_BRIGHT);
		td.setViewRange(0.5f);
		return td;
	}

	private static void lookTick() {
		if (PARTS.isEmpty()) return;
		double range = config().lookRange;
		double range2 = range * range;
		for (Part p : PARTS.values()) {
			if (p.kind() != Kind.BODY) continue;
			NpcConfig.Npc n = NPCS.get(p.owner());
			if (n == null) continue;
			if (!(live(p.key()) instanceof LivingEntity m) || !NpcBody.isBody(m)) continue;
			ServerPlayer nearest = null;
			if (n.lookAtPlayer) {
				double best = range2;
				for (ServerPlayer pl : ((ServerLevel) m.level()).players()) {
					if (pl.isSpectator()) continue;
					double d = pl.distanceToSqr(m);
					if (d <= best) {
						best = d;
						nearest = pl;
					}
				}
			}
			float yaw = p.yaw(), pitch = 0f;
			if (nearest != null) {
				float[] r = NpcLayout.lookAt(m.getX(), m.getY() + NpcLayout.EYE, m.getZ(), nearest.getX(), nearest.getEyeY(), nearest.getZ(), p.yaw());
				yaw = r[0];
				pitch = r[1];
			}
			if (Math.abs(Mth.wrapDegrees(m.getYHeadRot() - yaw)) > 1f || Math.abs(m.getXRot() - pitch) > 1f) {
				m.setYHeadRot(yaw);
				m.setXRot(pitch);
				m.setYBodyRot(p.yaw());
			}
		}
	}

	static InteractionResult onClick(Player player, boolean clientSide, Entity entity, boolean use) {
		if (!entity.getTags().contains(TAG)) return InteractionResult.PASS;
		if (clientSide) return InteractionResult.PASS;
		if (player.isSpectator()) return InteractionResult.PASS;
		Part p = BY_UUID.get(entity.getUUID());
		if (p == null || PARTS.get(p.key()) != p) return InteractionResult.FAIL; // Stale, but vanilla behavior must still not run.
		if (use && p.kind() != Kind.TEXT && player instanceof ServerPlayer sp) {
			Entity body = live("n." + p.owner() + ".body");
			Entity at = body != null ? body : entity;
			clickNpc(sp, p.owner(), at.getX(), at.getY(), at.getZ());
		}
		return use ? InteractionResult.SUCCESS : InteractionResult.FAIL;
	}

	/** True if the click counts. */
	private static boolean cooldownOk(ServerPlayer player) {
		Long last = LAST_CLICK.get(player.getUUID());
		if (last != null && tick - last < config().clickCooldownTicks) return false;
		LAST_CLICK.put(player.getUUID(), tick);
		return Gui.canOpen(player) && player.level().getServer() != null;
	}

	/** Sneaking admin gets info; everyone else gets text lines, sound and action. True if anything happened. */
	static boolean clickNpc(ServerPlayer player, String id, double x, double y, double z) {
		NpcConfig.Npc n = NPCS.get(id);
		if (n == null || !cooldownOk(player)) return false;
		if (player.isShiftKeyDown() && Perms.checkAdmin(player, NpcFeature.NODE)) {
			NpcCommands.sendInfo(player.createCommandSourceStack(), n);
			lastAction = id + " " + player.getPlainTextName() + " info";
			return true;
		}
		for (String line : n.messages) Msg.send(player, Msg.prefixed(line));
		clicks++;
		playSound(player, x, y, z, config().npcSound);
		MinecraftServer server = player.level().getServer();
		NpcActions.Resolved r = NpcActions.resolve(n.action, player.getGameProfile().name(), pr -> NpcActions.available(server, pr));
		if (r.error() != null) {
			Msg.error(player, r.error());
			lastAction = id + " " + player.getPlainTextName() + " error:" + r.error();
			return true;
		}
		lastAction = id + " " + player.getPlainTextName() + " " + (r.command().isEmpty() ? "none" : r.command());
		run(player, r.command());
		return true;
	}

	private static void run(ServerPlayer player, String command) {
		if (command != null && !command.isEmpty()) CommandRegistry.runAsTyped(player, command);
	}

	private static void playSound(ServerPlayer player, double x, double y, double z, String id) {
		if (id == null || id.isEmpty()) return;
		Identifier key = Identifier.tryParse(id);
		SoundEvent sound = key == null ? null : BuiltInRegistries.SOUND_EVENT.getValue(key);
		if (sound == null) return;
		player.level().playSound(null, x, y + 1, z, sound, SoundSource.NEUTRAL, 0.6f, 1.0f);
	}

	static void processPending() {
		if (PENDING.isEmpty()) return;
		List<Entity> batch = new ArrayList<>(PENDING);
		PENDING.clear();
		if (!running || configChanged) {
			// Before the first build or after a config switch, every loaded entity of ours is stale.
			for (Entity e : batch) if (!e.isRemoved()) e.discard();
			return;
		}
		for (Entity e : batch) {
			if (e.isRemoved()) continue;
			String key = keyOf(e);
			Part p = key == null ? null : PARTS.get(key);
			UUID valid = key == null ? null : SESSION.get(key);
			if (p == null || !e.getUUID().equals(valid) || !dimensionId(e).equals(normalize(p.dimension()))) {
				SmpEconomy.LOG.debug("[SMPEco] npc: stale entity removed ({} {})", e.getTags(), e.getUUID());
				e.discard();
				continue;
			}
			LIVE.put(key, e);
			BY_UUID.put(e.getUUID(), p);
		}
	}

	static String keyOf(Entity e) {
		for (String t : e.getTags()) {
			if (t.startsWith(TAG_KEY)) return t.substring(TAG_KEY.length());
		}
		return null;
	}

	static Entity live(String key) {
		Entity e = LIVE.get(key);
		if (e != null && e.isRemoved()) {
			LIVE.remove(key);
			return null;
		}
		return e;
	}

	static Map<String, Part> parts() {
		return PARTS;
	}

	static NpcConfig.Npc npc(String id) {
		return NPCS.get(id);
	}

	static int liveCount() {
		int n = 0;
		for (String k : PARTS.keySet()) if (live(k) != null) n++;
		return n;
	}

	static ServerLevel level(MinecraftServer server, String dimension) {
		Identifier id = Identifier.tryParse(normalize(dimension));
		return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
	}

	static String normalize(String dimension) {
		return NpcLayout.normalize(dimension);
	}

	static String dimensionId(Entity e) {
		return dimensionId(e.level());
	}

	static String dimensionId(Level level) {
		return level.dimension().identifier().toString();
	}

	static Component text(Entity e) {
		return e instanceof Display.TextDisplay td ? td.getText() : Component.empty();
	}
}
