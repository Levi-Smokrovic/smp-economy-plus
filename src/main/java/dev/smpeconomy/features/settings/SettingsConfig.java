package dev.smpeconomy.features.settings;

import dev.smpeconomy.core.ConfigManager;
import dev.smpeconomy.core.Settings;

/**
 * {@code config/smpeconomy/settings.json}: which gameplay-changing player switches exist. Both default to off,
 * because an economy mod shouldn't let players disable mob spawning without the server asking for it. A disabled
 * switch is missing from /settings and the GUI, and the spawn mixins never apply; stored player values stay. Always
 * read through {@link #get()}, since {@code /smpeco reload} replaces the object.
 */
public final class SettingsConfig {
	public String _info = "allowMobSpawnToggle: players get a /settings toggle that stops natural hostile mob spawning (plus patrols, sieges and"
		+ " portal piglins) within 64 blocks of them. allowPhantomToggle: players get a toggle that stops phantoms spawning for them."
		+ " Both are off by default; /smpeco reload applies changes.";
	public boolean allowMobSpawnToggle = false;
	public boolean allowPhantomToggle = false;

	static final ConfigManager.Config<SettingsConfig> CONFIG = ConfigManager.register("settings", SettingsConfig.class, SettingsConfig::new);

	private SettingsConfig() {}

	public static SettingsConfig get() {
		return CONFIG.get();
	}

	static void bootstrap() {
		Settings.setAvailability(SettingsConfig::allows);
	}

	/** Switches other than mobspawn and phantoms are always allowed. */
	static boolean allows(String key) {
		SettingsConfig c = get();
		if (c == null) return true;
		if (Settings.MOBSPAWN.equals(key)) return c.allowMobSpawnToggle;
		if (Settings.PHANTOMS.equals(key)) return c.allowPhantomToggle;
		return true;
	}

	/** Selftests: forces both switches on; the Runnable restores the previous state. */
	static Runnable overrideForTest(boolean mobSpawn, boolean phantoms) {
		SettingsConfig c = get();
		boolean oldMob = c.allowMobSpawnToggle;
		boolean oldPhantom = c.allowPhantomToggle;
		c.allowMobSpawnToggle = mobSpawn;
		c.allowPhantomToggle = phantoms;
		return () -> {
			SettingsConfig now = get();
			now.allowMobSpawnToggle = oldMob;
			now.allowPhantomToggle = oldPhantom;
		};
	}
}
