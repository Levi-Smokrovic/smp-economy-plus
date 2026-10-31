package dev.smpeconomy.features.orders;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.locale.Language;
import net.minecraft.world.item.Item;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.features.orders.OrderData.Order;

/**
 * Search in the order overview. Matches the English item name, item id, NPC name and buyer, ignoring case and
 * underscores. All words must match.
 */
final class OrderSearch {
	static final int MAX_LENGTH = 32;
	private static final Map<Item, String> HAYSTACK = new HashMap<>();

	private OrderSearch() {}

	static String normalize(String s) {
		if (s == null) return "";
		String t = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", " ");
		return t.trim().replaceAll("\\s+", " ");
	}

	static boolean matches(Order o, Item item, String normalizedQuery) {
		if (normalizedQuery == null || normalizedQuery.isEmpty()) return true;
		String hay = HAYSTACK.computeIfAbsent(item, OrderSearch::haystack);
		String who = o.npc ? normalize(o.label) : normalize(EconomyService.nameOrUnknown(o.owner));
		for (String word : normalizedQuery.split(" ")) {
			if (word.isEmpty()) continue;
			if (!hay.contains(word) && !who.contains(word)) return false;
		}
		return true;
	}

	private static String haystack(Item item) {
		String id = Items.id(item);
		String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
		return normalize(path) + " | " + normalize(Language.getInstance().getOrDefault(item.getDescriptionId()));
	}
}
