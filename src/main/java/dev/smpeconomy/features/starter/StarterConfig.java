package dev.smpeconomy.features.starter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import dev.smpeconomy.core.ConfigManager;
import dev.smpeconomy.core.DataStore;

/**
 * {@code config/smpeconomy/starter.json}. The start balance is deliberately not here: it stays in core.json
 * {@code startBalance}, because core uses it for every new account, even with this module off.
 *
 * <p>Kit entries: {@code item} is Base64 from {@link dev.smpeconomy.core.ItemCodec} (all components and DataVersion,
 * so it survives MC updates; {@code /smpeco starterkit capture} stores this form), or the hand-written
 * {@code {"id": "minecraft:bread", "count": 16}}. {@code slot}: 0-35 inventory (0-8 hotbar), 36 feet, 37 legs, 38
 * chest, 39 head, 40 offhand, -1 anywhere. An occupied slot means anywhere, a full inventory means at the player's
 * feet.
 */
public final class StarterConfig {
	public boolean giveKit = true;
	/**
	 * Only genuinely new players (vanilla playtime at join below {@link #newPlayerMaxMinutes}), so installing on an
	 * existing server doesn't hand kits to veterans.
	 */
	public boolean onlyNewPlayers = true;
	/** Minutes of playtime (0-1440). */
	public int newPlayerMaxMinutes = 5;
	/** Raising this ({@code /smpeco starterkit bump}) gives the kit once more to everyone still qualified. */
	public int kitVersion = 1;
	/** Chat lines (with chat prefix) sent with the kit. */
	public List<String> welcome = new ArrayList<>(List.of("§7Welcome! Here is your starter kit – type §e/menu§7 to see everything."));
	public List<KitItem> kit = defaultKit();

	public static final class KitItem {
		public int slot = -1;
		/** ItemCodec Base64; takes precedence over id and count. */
		public String item = "";
		public String id = "";
		public int count = 1;

		public KitItem() {}

		KitItem(int slot, String id, int count) {
			this.slot = slot;
			this.id = id;
			this.count = count;
		}

		static KitItem encoded(int slot, String base64) {
			KitItem k = new KitItem();
			k.slot = slot;
			k.item = base64;
			k.id = "";
			k.count = 0;
			return k;
		}

		boolean isEncoded() {
			return item != null && !item.isEmpty();
		}
	}

	static List<KitItem> defaultKit() {
		List<KitItem> kit = new ArrayList<>();
		kit.add(new KitItem(0, "minecraft:stone_sword", 1));
		kit.add(new KitItem(1, "minecraft:stone_pickaxe", 1));
		kit.add(new KitItem(2, "minecraft:stone_axe", 1));
		kit.add(new KitItem(8, "minecraft:bread", 16));
		return kit;
	}

	static final int MAX_SLOT = 40;
	static final int MAX_ENTRIES = 64;
	static final int MAX_COUNT = 64 * 36;

	static final ConfigManager.Config<StarterConfig> CONFIG = ConfigManager.register("starter", StarterConfig.class, StarterConfig::new,
		StarterConfig::validate);

	public static StarterConfig get() {
		return CONFIG.get();
	}

	static void validate(StarterConfig c) {
		if (c == null) throw new IllegalArgumentException("empty");
		if (c.newPlayerMaxMinutes < 0 || c.newPlayerMaxMinutes > 1440) throw new IllegalArgumentException("newPlayerMaxMinutes must be 0–1440");
		if (c.kitVersion < 1) throw new IllegalArgumentException("kitVersion must be at least 1");
		if (c.welcome == null) c.welcome = new ArrayList<>();
		c.welcome.removeIf(l -> l == null);
		if (c.welcome.size() > 10) throw new IllegalArgumentException("welcome: at most 10 lines");
		if (c.kit == null) c.kit = new ArrayList<>();
		if (c.kit.size() > MAX_ENTRIES) throw new IllegalArgumentException("kit: at most " + MAX_ENTRIES + " entries");
		for (int i = 0; i < c.kit.size(); i++) {
			KitItem k = c.kit.get(i);
			String where = "kit[" + i + "]";
			if (k == null) throw new IllegalArgumentException(where + ": empty entry");
			if (k.slot < -1 || k.slot > MAX_SLOT) throw new IllegalArgumentException(where + ": slot must be -1 (anywhere) or 0–40");
			if (k.item == null) k.item = "";
			if (k.id == null) k.id = "";
			k.item = k.item.trim();
			k.id = k.id.trim().toLowerCase(Locale.ROOT);
			if (k.isEncoded()) continue; // Content is decoded only when given (registries); broken entries are skipped then.
			if (k.id.isEmpty()) throw new IllegalArgumentException(where + ": set \"id\" (e.g. minecraft:bread) or \"item\"");
			if (!k.id.contains(":")) k.id = "minecraft:" + k.id;
			if (k.count < 1 || k.count > MAX_COUNT) throw new IllegalArgumentException(where + ": count must be 1–" + MAX_COUNT);
		}
	}

	static StarterConfig copy(StarterConfig c) {
		return DataStore.gson().fromJson(DataStore.gson().toJson(c), StarterConfig.class);
	}

	/**
	 * Writes an edited copy to starter.json and loads it, validated like a manual load. On failure the previous file
	 * is restored and the old state stays active. Returns null if ok, otherwise an error message.
	 */
	static String commit(StarterConfig edited) {
		try {
			validate(copy(edited));
		} catch (IllegalArgumentException e) {
			return e.getMessage();
		}
		return writeAndReload(CONFIG, DataStore.gson().toJson(edited, StarterConfig.class));
	}

	static String writeAndReload(ConfigManager.Config<?> config, String json) {
		Path file = config.path();
		byte[] previous = null;
		try {
			if (Files.isRegularFile(file)) previous = Files.readAllBytes(file);
			Files.createDirectories(file.getParent());
			DataStore.writeAtomic(file, json.getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			return "could not write " + file.getFileName() + ": " + e.getMessage();
		}
		String error = config.reload();
		if (error != null) {
			try {
				if (previous != null) DataStore.writeAtomic(file, previous);
			} catch (IOException ignored) {
				// The old state is still active; reload keeps it.
			}
		}
		return error;
	}
}
