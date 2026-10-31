package dev.smpeconomy.features.holo;

import java.util.Locale;
import net.minecraft.world.entity.Display;
import dev.smpeconomy.core.ConfigManager;

/** {@code config/smpeconomy/holo.json}: look and update rate of the leaderboard holograms. */
public final class HoloConfig {
	/** Seconds between leaderboard updates (10-3600). */
	public int updateSeconds = 60;
	/** Places per leaderboard (1-20). */
	public int entries = 10;
	/** Maximum line width in pixels ({@code line_width}, 50-1000); longer lines wrap. */
	public int lineWidth = 300;
	/**
	 * Background as ARGB hex: {@code "80000000"} is half-transparent black, {@code "00000000"} is none, 6 digits are
	 * opaque.
	 */
	public String background = "80000000";
	/** Billboard mode: {@code fixed}, {@code vertical}, {@code horizontal} or {@code center}. */
	public String billboard = "center";
	public boolean shadow = true;
	public boolean seeThrough = false;
	/** Always full bright, so the text stays readable at night and in caves. */
	public boolean fullBright = true;
	/** View range factor (1.0 is the vanilla default, 0.1-10). */
	public float viewRange = 1.0f;
	/** Count bank balance on the money leaderboard. */
	public boolean includeBank = true;
	/** Maximum number of holograms (1-500). */
	public int maxHolos = 50;
	/** Search radius in blocks for {@code /holo remove} without an id (0.5-64). */
	public double removeRadius = 5.0;

	private static final ConfigManager.Config<HoloConfig> CONFIG =
		ConfigManager.register("holo", HoloConfig.class, HoloConfig::new, HoloConfig::validate);

	/** Reload replaces this object, so never cache it. */
	public static HoloConfig get() {
		return CONFIG.get();
	}

	/** Throws {@link IllegalArgumentException} on an invalid value; reload then keeps the old config. */
	public static void validate(HoloConfig c) {
		if (c == null) throw new IllegalArgumentException("empty");
		if (c.updateSeconds < 10 || c.updateSeconds > 3600) throw new IllegalArgumentException("updateSeconds must be 10–3600");
		if (c.entries < 1 || c.entries > 20) throw new IllegalArgumentException("entries must be 1–20");
		if (c.lineWidth < 50 || c.lineWidth > 1000) throw new IllegalArgumentException("lineWidth must be 50–1000");
		parseArgb(c.background); // throws on an invalid value
		parseBillboard(c.billboard);
		if (!(c.viewRange >= 0.1f && c.viewRange <= 10f)) throw new IllegalArgumentException("viewRange must be 0.1–10");
		if (c.maxHolos < 1 || c.maxHolos > 500) throw new IllegalArgumentException("maxHolos must be 1–500");
		if (!(c.removeRadius >= 0.5 && c.removeRadius <= 64)) throw new IllegalArgumentException("removeRadius must be 0.5–64");
	}

	public int backgroundArgb() {
		return parseArgb(background);
	}

	public Display.BillboardConstraints billboardConstraints() {
		return parseBillboard(billboard);
	}

	/** {@code "80000000"} gives 0x80000000; 6 digits get alpha FF; optional {@code #} or {@code 0x} prefix. */
	static int parseArgb(String hex) {
		if (hex == null) throw new IllegalArgumentException("background is missing");
		String h = hex.trim();
		if (h.startsWith("#")) h = h.substring(1);
		else if (h.startsWith("0x") || h.startsWith("0X")) h = h.substring(2);
		if (!(h.length() == 6 || h.length() == 8) || !h.chars().allMatch(ch -> Character.digit(ch, 16) >= 0)) {
			throw new IllegalArgumentException("background must have 6 or 8 hex digits (e.g. 80000000)");
		}
		long v = Long.parseLong(h, 16);
		if (h.length() == 6) v |= 0xFF000000L;
		return (int) v;
	}

	static Display.BillboardConstraints parseBillboard(String name) {
		if (name != null) {
			String key = name.trim().toLowerCase(Locale.ROOT);
			for (Display.BillboardConstraints b : Display.BillboardConstraints.values()) {
				if (b.getSerializedName().equals(key)) return b;
			}
		}
		throw new IllegalArgumentException("billboard must be fixed, vertical, horizontal or center");
	}
}
