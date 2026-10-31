package dev.smpeconomy.features.shop;

import com.google.gson.reflect.TypeToken;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.locale.Language;
import net.minecraft.world.item.Item;
import dev.smpeconomy.core.BasePrices;
import dev.smpeconomy.core.ConfigManager;
import dev.smpeconomy.core.Items;

/**
 * Searches the English item name and item ID. Ignores case and underscores; all words must match. Order: exact name,
 * name prefix, rest in shop order.
 */
public final class ShopSearch {
	public static final int MIN_LENGTH = 2;
	public static final int MAX_LENGTH = 32;

	private ShopSearch() {}

	/** Server language is en_us. */
	static String englishName(Item item) {
		return Language.getInstance().getOrDefault(item.getDescriptionId());
	}

	/** Lowercase, everything except a-z and 0-9 becomes a single space. */
	public static String normalize(String s) {
		if (s == null) return "";
		String t = s.toLowerCase(Locale.ROOT)
			.replaceAll("[^a-z0-9]", " ");
		return t.trim().replaceAll("\\s+", " ");
	}

	/** 2 to 32 characters after normalizing. */
	public static boolean isValidQuery(String query) {
		String q = normalize(query);
		return q.length() >= MIN_LENGTH && query.trim().length() <= MAX_LENGTH;
	}

	/** Empty on invalid input. */
	public static List<Item> search(String query) {
		if (!isValidQuery(query)) return List.of();
		String q = normalize(query);
		String[] tokens = q.split(" ");
		Set<Item> all = new LinkedHashSet<>();
		for (BasePrices.ShopCategory c : BasePrices.shopCategories()) {
			for (BasePrices.ShopItem i : c.items()) all.add(i.item());
		}
		List<Item> hits = new ArrayList<>();
		Map<Item, Integer> rank = new HashMap<>();
		int order = 0;
		Map<Item, Integer> pos = new HashMap<>();
		for (Item item : all) {
			pos.put(item, order++);
			String en = normalize(englishName(item));
			String id = normalize(Items.id(item).replace("minecraft:", ""));
			String hay = en + " / " + id;
			boolean ok = true;
			for (String t : tokens) {
				if (!hay.contains(t)) {
					ok = false;
					break;
				}
			}
			if (!ok) continue;
			int r = q.equals(en) || q.equals(id) ? 0 : en.startsWith(q) || id.startsWith(q) ? 1 : 2;
			rank.put(item, r);
			hits.add(item);
		}
		hits.sort(Comparator.<Item>comparingInt(rank::get).thenComparingInt(pos::get));
		return hits;
	}
}
