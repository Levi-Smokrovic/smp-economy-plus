package dev.smpeconomy.features.menu;

import dev.smpeconomy.core.ConfigManager;

/**
 * {@code config/smpeconomy/menu.json}. Always read through {@link #get()}, since {@code /smpeco reload} replaces the
 * object.
 */
public final class MenuConfig {
	public static final int MAX_DELAY_SECONDS = 60;

	/** After join, write a clickable hint about {@code /menu} and {@code /commands} to chat, at most once per join. */
	public boolean joinHint = true;
	/** Delay in seconds (0-60), so the hint appears after the other join messages (daily, tasks). */
	public int joinHintDelaySeconds = 4;
	/** New players up to this playtime (hours) get the hint on every join. */
	public int joinHintPlaytimeHours = 10;
	/** Players with more playtime get it until they have seen it this many times in total (0 means never again). */
	public int joinHintTimes = 5;

	static final ConfigManager.Config<MenuConfig> CONFIG = ConfigManager.register("menu", MenuConfig.class, MenuConfig::new, MenuConfig::validate);

	public static MenuConfig get() {
		return CONFIG.get();
	}

	static void bootstrap() {
	}

	/** Throws on invalid values, and reload then keeps the old config. */
	static void validate(MenuConfig c) {
		if (c.joinHintDelaySeconds < 0 || c.joinHintDelaySeconds > MAX_DELAY_SECONDS) {
			throw new IllegalArgumentException("joinHintDelaySeconds must be 0–" + MAX_DELAY_SECONDS);
		}
		if (c.joinHintPlaytimeHours < 0 || c.joinHintPlaytimeHours > 100_000) throw new IllegalArgumentException("joinHintPlaytimeHours must be 0–100000");
		if (c.joinHintTimes < 0 || c.joinHintTimes > 1_000) throw new IllegalArgumentException("joinHintTimes must be 0–1000");
	}
}
