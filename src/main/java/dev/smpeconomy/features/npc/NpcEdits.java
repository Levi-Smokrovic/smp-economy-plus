package dev.smpeconomy.features.npc;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Edits on an npc.json copy, with no world so selftests can call them directly. All throw
 * {@link IllegalArgumentException} with a message for the admin. Save afterwards with {@link NpcConfig#commit}.
 */
final class NpcEdits {
	private NpcEdits() {}

	/** Horizontally block-centered, height to 1/100, whole-number yaw. */
	record Spot(String dimension, double x, double y, double z, float yaw) {
		static Spot of(String dimension, double x, double y, double z, float yaw) {
			return new Spot(dimension, Math.floor(x) + 0.5, Math.round(y * 100) / 100.0, Math.floor(z) + 0.5, Math.round(wrapYaw(yaw)));
		}
	}

	static float wrapYaw(float deg) {
		float d = deg % 360f;
		if (d >= 180f) d -= 360f;
		if (d < -180f) d += 360f;
		return d;
	}

	/**
	 * {@code action}: menu target from {@link NpcActions} (template for name, description, skin, equipment),
	 * {@code command} (set later with {@code set <id> action command …}) or {@code none}. Empty {@code skin} uses the
	 * template.
	 */
	static NpcConfig.Npc create(NpcConfig cfg, String id, String action, String skin, Spot at) {
		String key = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
		if (!NpcConfig.ID.matcher(key).matches()) throw new IllegalArgumentException("The id must be 1–32 characters a-z, 0-9, _ or -.");
		if (exists(cfg, key)) throw new IllegalArgumentException("The id " + key + " is already used.");
		String a = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
		NpcConfig.Npc n = new NpcConfig.Npc();
		n.id = key;
		NpcActions.Preset p = NpcActions.preset(a);
		if (p != null) {
			n.action = NpcConfig.Action.open(p.target(), "");
			n.name = p.name();
			n.description = p.description();
			n.skin = p.skin();
			n.equipment = new LinkedHashMap<>(p.equipment());
		} else if (a.equals(NpcConfig.Action.COMMAND) || a.equals(NpcConfig.Action.NONE)) {
			n.action = NpcConfig.Action.none();
			n.name = "§e" + key;
			n.description = "";
			n.skin = "wide/steve";
		} else {
			throw new IllegalArgumentException("Unknown action " + a + " – use one of: " + String.join(", ", NpcActions.targets()) + ", command, none");
		}
		if (skin != null && !skin.isBlank()) n.skin = checkSkin(skin);
		place(n, at);
		cfg.npcs.add(n);
		return n;
	}

	static void move(NpcConfig cfg, String id, Spot at) {
		for (NpcConfig.Npc n : cfg.npcs) {
			if (n.id.equals(id)) {
				place(n, at);
				return;
			}
		}
		for (NpcConfig.Text t : cfg.texts) {
			if (t.id.equals(id)) {
				t.dimension = at.dimension();
				t.x = at.x();
				t.y = at.y() + 2.5;
				t.z = at.z();
				return;
			}
		}
		throw unknown(id);
	}

	static void remove(NpcConfig cfg, String id) {
		if (!cfg.npcs.removeIf(n -> n.id.equals(id)) && !cfg.texts.removeIf(t -> t.id.equals(id))) throw unknown(id);
	}

	static void rename(NpcConfig cfg, String id, String name) {
		npc(cfg, id).name = colors(name);
	}

	static void setTitle(NpcConfig cfg, String id, String title) {
		npc(cfg, id).description = title == null || title.isBlank() || title.trim().equals("-") ? "" : colors(title);
	}

	static void setSkin(NpcConfig cfg, String id, String skin) {
		npc(cfg, id).skin = skin == null || skin.isBlank() || skin.trim().equals("-") ? "" : checkSkin(skin);
	}

	static void setLook(NpcConfig cfg, String id, boolean look) {
		npc(cfg, id).lookAtPlayer = look;
	}

	/** {@code target}: menu target with optional {@code arg} (e.g. shop category), or {@code none}. */
	static void setOpen(NpcConfig cfg, String id, String target, String arg) {
		NpcConfig.Npc n = npc(cfg, id);
		String t = target == null ? "" : target.trim().toLowerCase(Locale.ROOT);
		if (t.equals(NpcConfig.Action.NONE)) {
			n.action = NpcConfig.Action.none();
			return;
		}
		if (NpcActions.preset(t) == null) throw new IllegalArgumentException("Unknown action " + t + " – use one of: " + String.join(", ", NpcActions.targets()));
		n.action = NpcConfig.Action.open(t, arg);
	}

	static void setCommand(NpcConfig cfg, String id, String command) {
		NpcConfig.Action a = NpcConfig.Action.command(command);
		if (a.command.isEmpty()) throw new IllegalArgumentException("The command is empty.");
		npc(cfg, id).action = a;
	}

	static void setEquipment(NpcConfig cfg, String id, Map<String, String> equipment) {
		npc(cfg, id).equipment = new LinkedHashMap<>(equipment);
	}

	static void addMessage(NpcConfig cfg, String id, String text) {
		npc(cfg, id).messages.add(colors(text));
	}

	static void clearMessages(NpcConfig cfg, String id) {
		npc(cfg, id).messages.clear();
	}

	static NpcConfig.Npc npc(NpcConfig cfg, String id) {
		for (NpcConfig.Npc n : cfg.npcs) if (n.id.equals(id)) return n;
		throw unknown(id);
	}

	static boolean exists(NpcConfig cfg, String id) {
		for (NpcConfig.Npc n : cfg.npcs) if (n.id.equals(id)) return true;
		for (NpcConfig.Text t : cfg.texts) if (t.id.equals(id)) return true;
		return false;
	}

	/** § can't be typed in chat. */
	static String colors(String text) {
		String t = text == null ? "" : text.trim();
		return t.replaceAll("&([0-9a-fk-orA-FK-OR])", "§$1");
	}

	private static String checkSkin(String skin) {
		String s = skin.trim();
		if (!NpcConfig.VANILLA_SKIN.matcher(s).matches() && !NpcConfig.PLAYER_NAME.matcher(s).matches()) {
			throw new IllegalArgumentException("Skin must be a player name or wide/<name> / slim/<name> (vanilla skins: steve, alex, ari, efe, kai, makena, noor, sunny, zuri).");
		}
		return s;
	}

	private static void place(NpcConfig.Npc n, Spot at) {
		NpcConfig.checkPlace("npc " + n.id, at.dimension(), at.x(), at.y(), at.z(), at.yaw());
		n.dimension = at.dimension();
		n.x = at.x();
		n.y = at.y();
		n.z = at.z();
		n.yaw = at.yaw();
	}

	private static IllegalArgumentException unknown(String id) {
		return new IllegalArgumentException("Unknown NPC: " + id);
	}
}
