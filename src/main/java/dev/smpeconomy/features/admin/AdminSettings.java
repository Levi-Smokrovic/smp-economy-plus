package dev.smpeconomy.features.admin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.BiConsumer;
import java.util.function.Function;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.CoreConfig;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.Money;

/**
 * The core.json settings {@code /smpeco admin} and {@code /smpeco config get|set} may change (whitelist). A change
 * copies the current config, sets the value, runs {@link CoreConfig#validate} as on load, writes the file and
 * reloads, so GUI and manual edits end up identical in the file.
 */
public final class AdminSettings {
	public enum Kind { TEXT, MONEY, DECIMAL, BOOL }

	/** {@code key} is the path in core.json. {@code parse} throws {@link IllegalArgumentException} on bad input. */
	public record Setting(String key, String label, String description, String icon, Kind kind,
			Function<CoreConfig, String> get, BiConsumer<CoreConfig, String> parse) {}

	public static final List<Setting> ALL = List.of(
		new Setting("startBalance", "Start money", "Balance of new accounts.", "minecraft:gold_ingot", Kind.MONEY,
			c -> Money.format(c.startBalance), (c, v) -> c.startBalance = money(v)),
		new Setting("branding.chatPrefix", "Chat prefix", "In front of every chat message. Use & for colours.", "minecraft:name_tag", Kind.TEXT,
			c -> c.branding.chatPrefix, (c, v) -> c.branding.chatPrefix = prefix(v)),
		new Setting("branding.sidebarTitle", "Sidebar title", "Title of the sidebar and tab list. Use & for colours.", "minecraft:oak_sign", Kind.TEXT,
			c -> c.branding.sidebarTitle, (c, v) -> c.branding.sidebarTitle = colours(v).trim()),
		new Setting("currency.symbol", "Currency symbol", "For example $, €, ⛃ or \"coins\".", "minecraft:gold_nugget", Kind.TEXT,
			c -> c.currency.symbol, (c, v) -> c.currency.symbol = symbol(v, c.currency.symbolAfter)),
		new Setting("currency.symbolAfter", "Symbol after the amount", "12.50€ instead of $12.50.", "minecraft:repeater", Kind.BOOL,
			c -> Boolean.toString(c.currency.symbolAfter), (c, v) -> c.currency.symbolAfter = bool(v)),
		new Setting("currency.nameSingular", "Currency name (singular)", "For example dollar or coin.", "minecraft:paper", Kind.TEXT,
			c -> c.currency.nameSingular, (c, v) -> c.currency.nameSingular = v.trim()),
		new Setting("currency.namePlural", "Currency name (plural)", "For example dollars or coins.", "minecraft:book", Kind.TEXT,
			c -> c.currency.namePlural, (c, v) -> c.currency.namePlural = v.trim()),
		new Setting("multipliers.sell", "Sell multiplier", "Factor on all /sell prices (price caps still apply).", "minecraft:hopper", Kind.DECIMAL,
			c -> decimal(c.multipliers.sell), (c, v) -> c.multipliers.sell = number(v)),
		new Setting("multipliers.jobPay", "Job pay multiplier", "Factor on all job payments.", "minecraft:iron_pickaxe", Kind.DECIMAL,
			c -> decimal(c.multipliers.jobPay), (c, v) -> c.multipliers.jobPay = number(v)));

	private AdminSettings() {}

	public static Optional<Setting> get(String key) {
		return ALL.stream().filter(s -> s.key().equalsIgnoreCase(key)).findFirst();
	}

	public static String value(Setting s) {
		return s.get().apply(CoreConfig.get());
	}

	/** Returns null if ok, otherwise an English error message; on error nothing changes. */
	public static String set(String key, String raw, String actor) {
		Optional<Setting> setting = get(key);
		if (setting.isEmpty()) return "Unknown setting '" + key + "'. Allowed: " + String.join(", ", keys());
		CoreConfig copy = copy(CoreConfig.get());
		String before = setting.get().get().apply(copy);
		try {
			setting.get().parse().accept(copy, raw == null ? "" : raw);
			CoreConfig.validate(copy);
		} catch (IllegalArgumentException e) {
			return e.getMessage();
		}
		String after = setting.get().get().apply(copy);
		String error = save(copy);
		if (error != null) return error;
		SmpEconomy.LOG.info("[SMPEco] core.json {}: '{}' → '{}' by {}", setting.get().key(), before, after, actor);
		return null;
	}

	static String save(CoreConfig cfg) {
		try {
			DataStore.writeAtomic(CoreConfig.CONFIG.path(), DataStore.gson().toJson(cfg, CoreConfig.class).getBytes(StandardCharsets.UTF_8));
		} catch (IOException | RuntimeException e) {
			SmpEconomy.LOG.error("[SMPEco] core.json not written", e);
			return "core.json could not be written: " + e.getMessage();
		}
		String error = CoreConfig.CONFIG.reload();
		return error == null ? null : "core.json reload failed: " + error;
	}

	static CoreConfig copy(CoreConfig cfg) {
		return DataStore.gson().fromJson(DataStore.gson().toJson(cfg, CoreConfig.class), CoreConfig.class);
	}

	public static List<String> keys() {
		return ALL.stream().map(Setting::key).toList();
	}

	/** Vanilla clients can't type §. */
	public static String colours(String v) {
		return v == null ? "" : v.replaceAll("&([0-9a-fk-orA-FK-OR])", "§$1");
	}

	public static String editable(String v) {
		return v == null ? "" : v.replaceAll("§([0-9a-fk-orA-FK-OR])", "&$1");
	}

	/** Always ends in a space (input fields trim it); empty stays empty. */
	static String prefix(String v) {
		String p = colours(v).stripTrailing();
		return p.isEmpty() ? "" : p + " ";
	}

	/** Word symbols get a leading space ({@code 5 coins}, but {@code 5€}). */
	static String symbol(String v, boolean after) {
		String s = v == null ? "" : v.trim();
		if (after && s.length() > 1 && Character.isLetter(s.charAt(0))) return " " + s;
		return s;
	}

	static long money(String v) {
		OptionalLong cents = Money.parseAllowZero(v);
		if (cents.isEmpty()) throw new IllegalArgumentException("'" + v + "' is not a valid amount (e.g. 100, 2.5k, 1m)");
		return cents.getAsLong();
	}

	static double number(String v) {
		try {
			double d = Double.parseDouble(v.trim().replace(',', '.').replaceAll("[xX×]$", ""));
			if (Double.isNaN(d) || Double.isInfinite(d)) throw new NumberFormatException();
			return Math.round(d * 1000.0) / 1000.0;
		} catch (NumberFormatException | NullPointerException e) {
			throw new IllegalArgumentException("'" + v + "' is not a number (e.g. 1.25)");
		}
	}

	static boolean bool(String v) {
		String s = v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
		return switch (s) {
			case "true", "on", "yes", "1" -> true;
			case "false", "off", "no", "0" -> false;
			default -> throw new IllegalArgumentException("'" + v + "' must be true or false");
		};
	}

	static String decimal(double d) {
		return String.format(Locale.ROOT, "%.2f", d).replaceAll("0$", "");
	}
}
