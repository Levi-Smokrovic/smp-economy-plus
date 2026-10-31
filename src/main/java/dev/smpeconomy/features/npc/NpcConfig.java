package dev.smpeconomy.features.npc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import dev.smpeconomy.core.ConfigManager;
import dev.smpeconomy.core.DataStore;

/**
 * {@code config/smpeconomy/npc.json}: admin-placed NPCs (mannequins with a name tag) and optional text displays.
 * Defaults to none. Edited by {@code /smpeco npc} (writes this file, see {@link #commit}) or by hand plus
 * {@code /smpeco reload}.
 */
public final class NpcConfig {
	/** When off, all our entities disappear. */
	public boolean enabled = true;
	/** Minimum ticks between two clicks of a player (double packets per hand, spam). 1-100. */
	public int clickCooldownTicks = 10;
	/** NPCs with {@code lookAtPlayer} turn their head to the nearest player in this radius (blocks, 1-32). */
	public double lookRange = 6.0;
	/** ARGB hex. */
	public String plateBackground = "60101018";
	/** ARGB hex. */
	public String textBackground = "80101018";
	/** Height of the name tag above the feet (blocks, 1.5-4). */
	public double plateHeight = 2.05;
	/** Empty means no sound. */
	public String npcSound = "minecraft:entity.villager.trade";
	public List<Npc> npcs = new ArrayList<>();
	public List<Text> texts = new ArrayList<>();

	/**
	 * Skin: {@code "wide/efe"} or {@code "slim/alex"} is a vanilla default skin (no Mojang lookup), anything else a
	 * player name, empty the default.
	 */
	public static final class Npc {
		public String id = "";
		public String dimension = "minecraft:overworld";
		public double x;
		public double y;
		public double z;
		/** MC yaw: 0 south, 90 west, 180 north, -90 east. */
		public float yaw;
		/** First name tag line; § codes allowed. */
		public String name = "";
		/** Second line; § codes allowed, empty means none. */
		public String description = "";
		public String skin = "";
		/**
		 * Slot ({@code head, chest, legs, feet, mainhand, offhand}) to item, optionally {@code #RRGGBB} for leather
		 * color.
		 */
		public Map<String, String> equipment = new LinkedHashMap<>();
		public Action action = new Action();
		/** Chat lines (with chat prefix) sent before the action. */
		public List<String> messages = new ArrayList<>();
		public boolean lookAtPlayer = true;

		Npc() {}

		Npc(String id, double x, double y, double z, float yaw, String name, String description, String skin, Action action, String... equipment) {
			this.id = id;
			this.x = x;
			this.y = y;
			this.z = z;
			this.yaw = yaw;
			this.name = name;
			this.description = description;
			this.skin = skin;
			this.action = action;
			for (int i = 0; i + 1 < equipment.length; i += 2) this.equipment.put(equipment[i], equipment[i + 1]);
		}
	}

	/**
	 * {@code open}: menu {@code target} from {@link NpcActions} ({@code arg} e.g. a shop category); if the module
	 * isn't running the player gets "That isn't available on this server.". {@code command}: command without
	 * {@code /}, run as the clicking player with their permissions, {@code {player}} is their name. {@code none}:
	 * only {@code messages}.
	 */
	public static final class Action {
		public static final String OPEN = "open";
		public static final String COMMAND = "command";
		public static final String NONE = "none";

		public String type = NONE;
		public String target = "";
		public String arg = "";
		public String command = "";

		Action() {}

		static Action open(String target, String arg) {
			Action a = new Action();
			a.type = OPEN;
			a.target = target;
			a.arg = arg == null ? "" : arg.trim();
			return a;
		}

		static Action command(String command) {
			Action a = new Action();
			a.type = COMMAND;
			a.command = stripSlash(command);
			return a;
		}

		static Action none() {
			return new Action();
		}

		/** Short form: {@code open:bank}, {@code command:warp_spawn}, {@code none}. */
		String describe() {
			return switch (type) {
				case OPEN -> "open:" + target + (arg.isEmpty() ? "" : "_" + arg.replace(' ', '_'));
				case COMMAND -> "command:" + (command.isEmpty() ? "-" : command.replace(' ', '_'));
				default -> NONE;
			};
		}
	}

	public static final class Text {
		public String id = "";
		public String dimension = "minecraft:overworld";
		public double x;
		public double y;
		public double z;
		public List<String> lines = new ArrayList<>();
		/** Pixels (50-1000). */
		public int lineWidth = 200;
		/** 0.2-5; 0 means the default of 1. */
		public double scale = 0;

		Text() {}

		Text(String id, double x, double y, double z, String... lines) {
			this.id = id;
			this.x = x;
			this.y = y;
			this.z = z;
			this.lines = new ArrayList<>(List.of(lines));
		}

		double effectiveScale() {
			return scale >= 0.2 && scale <= 5 ? scale : 1.0;
		}
	}

	static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,32}");
	static final Pattern VANILLA_SKIN = Pattern.compile("(wide|slim)/[a-z_]{1,16}");
	static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
	static final Pattern DIMENSION = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+|[a-z0-9_./-]+");
	static final Set<String> SLOTS = Set.of("head", "chest", "legs", "feet", "mainhand", "offhand");
	static final int MAX_TEXT = 200;

	// After the patterns above: register() loads and validates at once, and static init runs in textual order.
	static final ConfigManager.Config<NpcConfig> CONFIG = ConfigManager.register("npc", NpcConfig.class, NpcConfig::new, c -> {
		validate(c);
		NpcManager.markConfigChanged();
	});

	/** Reload replaces this object: never cache it, never change it directly, use {@link #commit}. */
	public static NpcConfig get() {
		return CONFIG.get();
	}

	/** Deep copy for editing; the active object stays untouched until {@link #commit} is done. */
	static NpcConfig copy(NpcConfig c) {
		return DataStore.gson().fromJson(DataStore.gson().toJson(c), NpcConfig.class);
	}

	/**
	 * Writes an edited copy to npc.json and loads it, validated like a manual load. On failure the previous file is
	 * restored and the old state stays active.
	 *
	 * @return null if ok, otherwise an error message
	 */
	static String commit(NpcConfig edited) {
		try {
			validate(copy(edited));
		} catch (IllegalArgumentException e) {
			return e.getMessage();
		}
		Path file = CONFIG.path();
		byte[] previous = null;
		try {
			if (Files.isRegularFile(file)) previous = Files.readAllBytes(file);
			Files.createDirectories(file.getParent());
			DataStore.writeAtomic(file, DataStore.gson().toJson(edited, NpcConfig.class).getBytes(java.nio.charset.StandardCharsets.UTF_8));
		} catch (IOException e) {
			return "could not write npc.json: " + e.getMessage();
		}
		String error = CONFIG.reload();
		if (error != null) {
			try {
				if (previous != null) DataStore.writeAtomic(file, previous);
			} catch (IOException ignored) {
				// The old state is still active; reload keeps it.
			}
			return error;
		}
		return null;
	}

	/** Validates and normalizes; throws {@link IllegalArgumentException}, and reload then keeps the old state. */
	static void validate(NpcConfig c) {
		if (c == null) throw new IllegalArgumentException("empty");
		if (c.clickCooldownTicks < 1 || c.clickCooldownTicks > 100) throw new IllegalArgumentException("clickCooldownTicks must be 1–100");
		if (!(c.lookRange >= 1 && c.lookRange <= 32)) throw new IllegalArgumentException("lookRange must be 1–32");
		if (!(c.plateHeight >= 1.5 && c.plateHeight <= 4)) throw new IllegalArgumentException("plateHeight must be 1.5–4");
		NpcLayout.parseArgb(c.plateBackground);
		NpcLayout.parseArgb(c.textBackground);
		if (c.npcSound == null) c.npcSound = "";
		if (c.npcs == null) c.npcs = new ArrayList<>();
		if (c.texts == null) c.texts = new ArrayList<>();
		Set<String> ids = new HashSet<>();
		for (Npc n : c.npcs) {
			if (n == null) throw new IllegalArgumentException("npcs: empty entry");
			String where = "npc " + n.id;
			if (n.id == null || !ID.matcher(n.id).matches()) throw new IllegalArgumentException("npc id must match [a-z0-9_-]{1,32}: " + n.id);
			if (!ids.add(n.id)) throw new IllegalArgumentException("duplicate id: " + n.id);
			checkPlace(where, n.dimension, n.x, n.y, n.z, n.yaw);
			if (n.name == null) n.name = "";
			if (n.description == null) n.description = "";
			if (n.name.length() > MAX_TEXT || n.description.length() > MAX_TEXT) throw new IllegalArgumentException(where + ": name/description longer than " + MAX_TEXT);
			if (n.messages == null) n.messages = new ArrayList<>();
			n.messages.removeIf(m -> m == null);
			if (n.equipment == null) n.equipment = new LinkedHashMap<>();
			if (n.skin == null) n.skin = "";
			n.skin = n.skin.trim();
			if (!n.skin.isEmpty() && !VANILLA_SKIN.matcher(n.skin).matches() && !PLAYER_NAME.matcher(n.skin).matches()) {
				throw new IllegalArgumentException(where + ": skin must be wide/<name>, slim/<name> or a player name");
			}
			for (Map.Entry<String, String> e : n.equipment.entrySet()) {
				if (!SLOTS.contains(e.getKey())) throw new IllegalArgumentException(where + ": unknown equipment slot " + e.getKey());
				NpcLayout.parseItemSpec(e.getValue()); // throws on a bad color or empty id
			}
			validateAction(where, n);
		}
		for (Text t : c.texts) {
			if (t == null) throw new IllegalArgumentException("texts: empty entry");
			if (t.id == null || !ID.matcher(t.id).matches()) throw new IllegalArgumentException("text id must match [a-z0-9_-]{1,32}: " + t.id);
			if (!ids.add(t.id)) throw new IllegalArgumentException("duplicate id: " + t.id);
			checkPlace("text " + t.id, t.dimension, t.x, t.y, t.z, 0f);
			if (t.lines == null || t.lines.isEmpty()) throw new IllegalArgumentException("text " + t.id + ": lines are missing");
			for (int i = 0; i < t.lines.size(); i++) if (t.lines.get(i) == null) t.lines.set(i, "");
			if (t.lineWidth < 50 || t.lineWidth > 1000) throw new IllegalArgumentException("text " + t.id + ": lineWidth must be 50–1000");
			if (!(t.scale == 0 || (t.scale >= 0.2 && t.scale <= 5))) throw new IllegalArgumentException("text " + t.id + ": scale must be 0 (auto) or 0.2–5");
		}
	}

	private static void validateAction(String where, Npc n) {
		if (n.action == null) n.action = new Action();
		Action a = n.action;
		a.type = a.type == null || a.type.isBlank() ? Action.NONE : a.type.trim().toLowerCase(Locale.ROOT);
		if (a.target == null) a.target = "";
		if (a.arg == null) a.arg = "";
		a.target = a.target.trim().toLowerCase(Locale.ROOT);
		a.arg = a.arg.trim();
		a.command = stripSlash(a.command);
		switch (a.type) {
			case Action.OPEN -> {
				if (NpcActions.preset(a.target) == null) {
					throw new IllegalArgumentException(where + ": unknown action target \"" + a.target + "\" (" + String.join(", ", NpcActions.targets()) + ")");
				}
			}
			case Action.COMMAND -> {
				if (a.command.isEmpty()) throw new IllegalArgumentException(where + ": action command is empty");
			}
			case Action.NONE -> {
			}
			default -> throw new IllegalArgumentException(where + ": action type must be open, command or none");
		}
	}

	static void checkPlace(String where, String dimension, double x, double y, double z, float yaw) {
		if (dimension == null || !DIMENSION.matcher(dimension).matches()) throw new IllegalArgumentException(where + ": invalid dimension");
		if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || Math.abs(x) > 29_999_000 || Math.abs(z) > 29_999_000
			|| y < -64 || y > 320) {
			throw new IllegalArgumentException(where + ": invalid position");
		}
		if (!Float.isFinite(yaw)) throw new IllegalArgumentException(where + ": invalid yaw");
	}

	static String stripSlash(String command) {
		String c = command == null ? "" : command.trim();
		while (c.startsWith("/")) c = c.substring(1).trim();
		return c;
	}
}
