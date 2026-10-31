package dev.smpeconomy.core;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import dev.smpeconomy.SmpEconomy;

/**
 * One warning per config file for item ids nothing has registered. During mod init the verdict is held back, because a
 * mod that initializes after this one may still register them.
 */
public final class UnknownItems {
	private static final Map<String, List<String>> PENDING = new LinkedHashMap<>();
	private static boolean registriesComplete;

	private UnknownItems() {}

	public static void report(String file, Collection<String> ids) {
		if (registriesComplete) {
			warn(file, ids);
		} else if (ids.isEmpty()) {
			PENDING.remove(file);
		} else {
			PENDING.put(file, List.copyOf(ids));
		}
	}

	public static boolean registriesComplete() {
		return registriesComplete;
	}

	public static void markRegistriesComplete() {
		registriesComplete = true;
		PENDING.forEach(UnknownItems::warn);
		PENDING.clear();
	}

	public static boolean moddedItemsPresent() {
		for (Identifier key : BuiltInRegistries.ITEM.keySet()) {
			if (!Identifier.DEFAULT_NAMESPACE.equals(key.getNamespace())) return true;
		}
		return false;
	}

	public static String warning(String file, Collection<String> ids) {
		return "[SMPEco] " + file + ": skipped unknown items (mod not installed?): " + String.join(", ", new LinkedHashSet<>(ids));
	}

	private static void warn(String file, Collection<String> ids) {
		if (!ids.isEmpty()) SmpEconomy.LOG.warn(warning(file, ids));
	}
}
