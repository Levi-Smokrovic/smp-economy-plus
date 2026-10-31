package dev.smpeconomy.features.settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import dev.smpeconomy.core.Settings;

/** Logic behind GUI and commands, testable without a player object. Values live only in core {@link Settings}. */
public final class SettingsLogic {
	private SettingsLogic() {}

	/** Empty for an unknown key, in which case nothing is created. */
	public static Optional<Boolean> toggle(UUID player, String key) {
		if (!Settings.visible(key)) return Optional.empty();
		return Optional.of(Settings.toggle(player, key));
	}

	/** False for an unknown key. */
	public static boolean set(UUID player, String key, boolean value) {
		if (!Settings.visible(key)) return false;
		Settings.set(player, key, value);
		return true;
	}

	/** Returns the number of switches actually changed. */
	public static int resetAll(UUID player) {
		int changed = 0;
		for (Settings.Def d : Settings.all()) {
			if (Settings.get(player, d.key()) != d.defaultValue()) {
				Settings.set(player, d.key(), d.defaultValue());
				changed++;
			}
		}
		return changed;
	}

	public static int changedCount(UUID player) {
		int n = 0;
		for (Settings.Def d : Settings.all()) {
			if (Settings.get(player, d.key()) != d.defaultValue()) n++;
		}
		return n;
	}

	/** Also accepts true/false/1/0/yes/no, case-insensitive; empty if invalid. */
	public static Optional<Boolean> parseOnOff(String input) {
		if (input == null) return Optional.empty();
		return switch (input.trim().toLowerCase(Locale.ROOT)) {
			case "on", "true", "1", "yes" -> Optional.of(true);
			case "off", "false", "0", "no" -> Optional.of(false);
			default -> Optional.empty();
		};
	}

	public static String stateLabel(boolean on) {
		return on ? "§aOn" : "§cOff";
	}

	/** Descriptions from other features may contain colors. */
	public static String plain(String text) {
		return text == null ? "" : text.replaceAll("§[0-9a-fk-orA-FK-OR]", "");
	}

	/**
	 * Wraps at spaces to at most {@code width} characters; overlong words stay whole. § codes don't count and are
	 * removed, because the GUI colors each line itself.
	 */
	public static List<String> wrap(String text, int width) {
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : plain(text).trim().split("\\s+")) {
			if (word.isEmpty()) continue;
			if (!line.isEmpty() && line.length() + 1 + word.length() > width) {
				lines.add(line.toString());
				line.setLength(0);
			}
			if (!line.isEmpty()) line.append(' ');
			line.append(word);
		}
		if (!line.isEmpty()) lines.add(line.toString());
		return lines;
	}
}
