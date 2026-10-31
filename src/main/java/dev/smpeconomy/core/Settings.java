package dev.smpeconomy.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerPlayer;
import dev.smpeconomy.SmpEconomy;

/**
 * Per-player on/off switches with a registry. Values live in {@link PlayerDataService}; with none stored the
 * definition's default applies.
 *
 * <p>Features register further switches in {@code register()}. Admins can disable individual switches by config (
 * {@link #setAvailability}); they then vanish from {@link #all()} and count as unknown for {@code /settings}.
 */
public final class Settings {
	public static final String SIDEBAR = "sidebar";
	public static final String MOBSPAWN = "mobspawn";
	public static final String PHANTOMS = "phantoms";
	public static final String PAY_RECEIVE = "pay_receive";
	public static final String CHATGAMES = "chatgames";
	public static final String ANNOUNCEMENTS = "announcements";
	public static final String NOTIFICATIONS = "notifications";
	public static final String JOB_ACTIONBAR = "job_actionbar";
	public static final String DEATH_COORDS = "death_coords";

	/** {@code icon} is an item id for the GUI. Texts use section codes. */
	public record Def(String key, String label, String description, boolean defaultValue, String icon) {}

	/**
	 * Owning module of a predefined switch; absent means always visible. Switches of disabled modules are hidden from
	 * {@link #all()} but their values stay stored.
	 */
	private static final Map<String, String> OWNER = Map.of(
		SIDEBAR, "hud",
		MOBSPAWN, "settings",
		PHANTOMS, "settings",
		DEATH_COORDS, "settings",
		PAY_RECEIVE, "economy",
		CHATGAMES, "minigames",
		JOB_ACTIONBAR, "jobs");

	@FunctionalInterface
	public interface Listener {
		void onChange(UUID player, String key, boolean value);
	}

	private static final Map<String, Def> DEFS = new LinkedHashMap<>();
	private static final List<Listener> LISTENERS = new ArrayList<>();
	private static final Set<String> WARNED = new HashSet<>();
	private static volatile Predicate<String> availability = key -> true;

	static {
		register(SIDEBAR, "Sidebar", "Shows the sidebar on the right with your balance, job, tasks and playtime.", true, "minecraft:oak_sign");
		register(MOBSPAWN, "Mob spawning near me", "When off, no natural hostile mobs spawn within 64 blocks of you (spawners, spawn eggs and raids still work).", true, "minecraft:zombie_head");
		register(PHANTOMS, "Phantoms", "When off, no phantoms spawn for you, even if you haven't slept in a long time.", true, "minecraft:phantom_membrane");
		register(PAY_RECEIVE, "Receive payments", "Lets other players send you money with /pay.", true, "minecraft:gold_ingot");
		register(CHATGAMES, "Chat games", "Shows chat games and lets you play along.", true, "minecraft:writable_book");
		register(ANNOUNCEMENTS, "Announcements", "Coinflip, bounty and event announcements, plus Breaking News titles and sounds.", true, "minecraft:bell");
		register(NOTIFICATIONS, "Notifications", "Messages about sold auctions and filled orders.", true, "minecraft:paper");
		register(JOB_ACTIONBAR, "Job action bar", "Shows your job earnings above the hotbar.", true, "minecraft:iron_pickaxe");
		register(DEATH_COORDS, "Death coordinates", "Privately sends you your coordinates when you die.", true, "minecraft:recovery_compass");
	}

	private Settings() {}

	public static void bootstrap() {
	}

	/** Registration order is GUI order. */
	public static Def register(String key, String label, String description, boolean defaultValue, String icon) {
		if (key == null || !key.matches("[a-z0-9_.]+")) throw new IllegalArgumentException("Invalid settings key: " + key);
		Def def = new Def(key, label, description, defaultValue, icon);
		DEFS.put(key, def);
		return def;
	}

	public static List<Def> all() {
		List<Def> out = new ArrayList<>(DEFS.size());
		for (Def d : DEFS.values()) {
			if (visible(d.key())) out.add(d);
		}
		return Collections.unmodifiableList(out);
	}

	public static boolean visible(String key) {
		if (!DEFS.containsKey(key)) return false;
		String owner = OWNER.get(key);
		if (owner != null && !dev.smpeconomy.core.module.Modules.isEnabled(owner)) return false;
		return available(key);
	}

	public static boolean available(String key) {
		try {
			return availability.test(key);
		} catch (RuntimeException e) {
			return true;
		}
	}

	public static void setAvailability(Predicate<String> predicate) {
		availability = predicate == null ? key -> true : predicate;
	}

	public static List<Def> allDefined() {
		return Collections.unmodifiableList(new ArrayList<>(DEFS.values()));
	}

	public static String owner(String key) {
		return OWNER.get(key);
	}

	public static Optional<Def> def(String key) {
		return Optional.ofNullable(DEFS.get(key));
	}

	/** Unknown keys return {@code true} and warn once in the log. */
	public static boolean get(UUID player, String key) {
		Def def = DEFS.get(key);
		boolean fallback = def == null ? warnUnknown(key) : def.defaultValue();
		String raw = PlayerDataService.getSetting(player, key, null);
		if (raw == null) return fallback;
		return "true".equalsIgnoreCase(raw) || (!"false".equalsIgnoreCase(raw) && fallback);
	}

	public static boolean get(ServerPlayer player, String key) {
		return get(player.getUUID(), key);
	}

	/** Default values are not stored, so later changes to a default take effect. */
	public static void set(UUID player, String key, boolean value) {
		Def def = DEFS.get(key);
		if (def == null) throw new IllegalArgumentException("Unknown setting: " + key);
		boolean old = get(player, key);
		PlayerDataService.setSetting(player, key, value == def.defaultValue() ? null : Boolean.toString(value));
		if (old != value) fire(player, key, value);
	}

	public static boolean toggle(UUID player, String key) {
		boolean value = !get(player, key);
		set(player, key, value);
		return value;
	}

	public static Map<String, Boolean> values(UUID player) {
		Map<String, Boolean> map = new LinkedHashMap<>();
		for (Def d : DEFS.values()) map.put(d.key(), get(player, d.key()));
		return map;
	}

	public static Hooks.Registration addListener(Listener listener) {
		LISTENERS.add(listener);
		return Hooks.Registration.of(() -> LISTENERS.remove(listener));
	}

	private static void fire(UUID player, String key, boolean value) {
		for (Listener l : List.copyOf(LISTENERS)) {
			try {
				l.onChange(player, key, value);
			} catch (RuntimeException e) {
				SmpEconomy.LOG.error("[SMPEco] Settings listener failed ({})", key, e);
			}
		}
	}

	private static boolean warnUnknown(String key) {
		if (WARNED.add(String.valueOf(key))) SmpEconomy.LOG.warn("[SMPEco] Unknown setting '{}' queried, using 'on'", key);
		return true;
	}
}
