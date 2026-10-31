package dev.smpeconomy.features.shop;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.world.item.Item;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.PlayerDataService;

/**
 * Per-player shop data in {@code players.json}. Unknown item IDs are skipped when read but not deleted, since
 * they may return after a mod update.
 */
public final class ShopPlayerData {
	public static final String FAVORITES_KEY = "shop.favorites";
	public static final String RECENT_KEY = "shop.recent";
	public static final int RECENT_MAX = 9;

	static final class Favorites {
		List<String> items = new ArrayList<>();
	}

	/** Newest first. */
	static final class Recent {
		List<Entry> items = new ArrayList<>();
	}

	static final class Entry {
		String item = "";
		int amount = 1;

		Entry() {}

		Entry(String item, int amount) {
			this.item = item;
			this.amount = amount;
		}
	}

	/** With the chosen amount, for quick buy. */
	public record RecentPurchase(Item item, int amount) {}

	public enum Toggle { ADDED, REMOVED, LIMIT }

	private ShopPlayerData() {}

	/** In order of adding; known items only. */
	public static List<Item> favorites(UUID player) {
		List<Item> list = new ArrayList<>();
		for (String id : rawFavorites(player)) {
			Optional<Item> item = Items.byId(id);
			if (item.isPresent() && !list.contains(item.get())) list.add(item.get());
		}
		return list;
	}

	public static boolean isFavorite(UUID player, Item item) {
		return rawFavorites(player).contains(Items.id(item));
	}

	/** {@link Toggle#LIMIT} if {@code max} favorites are already stored. */
	public static Toggle toggleFavorite(UUID player, Item item, int max) {
		Favorites fav = load(player);
		String id = Items.id(item);
		if (fav.items.remove(id)) {
			save(player, fav);
			return Toggle.REMOVED;
		}
		if (fav.items.size() >= max) return Toggle.LIMIT;
		fav.items.add(id);
		save(player, fav);
		return Toggle.ADDED;
	}

	static List<String> rawFavorites(UUID player) {
		return List.copyOf(load(player).items);
	}

	private static Favorites load(UUID player) {
		Favorites f = PlayerDataService.getBlob(player, FAVORITES_KEY, Favorites.class);
		if (f == null) f = new Favorites();
		if (f.items == null) f.items = new ArrayList<>();
		f.items.removeIf(s -> s == null || s.isBlank());
		return f;
	}

	private static void save(UUID player, Favorites f) {
		PlayerDataService.setBlob(player, FAVORITES_KEY, f.items.isEmpty() ? null : f);
	}

	/** Newest first, at most {@value #RECENT_MAX}, no duplicates. */
	public static List<RecentPurchase> recent(UUID player) {
		List<RecentPurchase> list = new ArrayList<>();
		for (Entry e : loadRecent(player).items) {
			Optional<Item> item = Items.byId(e.item);
			if (item.isEmpty() || list.stream().anyMatch(r -> r.item() == item.get())) continue;
			list.add(new RecentPurchase(item.get(), Math.max(1, e.amount)));
			if (list.size() >= RECENT_MAX) break;
		}
		return list;
	}

	/** Moves it to the front; older entries of the same item drop out. */
	public static void recordPurchase(UUID player, Item item, int amount) {
		Recent r = loadRecent(player);
		String id = Items.id(item);
		r.items.removeIf(e -> id.equals(e.item));
		r.items.add(0, new Entry(id, Math.max(1, amount)));
		while (r.items.size() > RECENT_MAX) r.items.remove(r.items.size() - 1);
		PlayerDataService.setBlob(player, RECENT_KEY, r);
	}

	private static Recent loadRecent(UUID player) {
		Recent r = PlayerDataService.getBlob(player, RECENT_KEY, Recent.class);
		if (r == null) r = new Recent();
		if (r.items == null) r.items = new ArrayList<>();
		r.items.removeIf(e -> e == null || e.item == null || e.item.isBlank());
		return r;
	}
}
