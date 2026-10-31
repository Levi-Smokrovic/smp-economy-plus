package dev.smpeconomy.core;

import com.google.gson.reflect.TypeToken;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import net.minecraft.world.item.Item;
import dev.smpeconomy.SmpEconomy;

/**
 * Base prices in cents per item, before modifiers. {@code prices.json} holds what /sell pays; {@code shop.json} holds
 * categories with buy price and per-purchase limit. Defaults ship under {@code smpeconomy_defaults/}.
 *
 * <p>Effective prices come only from {@link PriceGuard}. Invalid entries (unknown item id, price of 0 or less) are
 * logged and skipped.
 */
public final class BasePrices {
	public static final class ShopConfig {
		public List<CategoryConfig> categories = new ArrayList<>();
	}

	public static final class CategoryConfig {
		public String id = "";
		public String name = "";
		public String icon = "minecraft:chest";
		public List<ItemConfig> items = new ArrayList<>();
	}

	public static final class ItemConfig {
		public String item = "";
		public long price;
		public int max = 576;
	}

	public record ShopItem(Item item, long price, int max) {}

	public record ShopCategory(String id, String name, Item icon, List<ShopItem> items) {}

	private static volatile Map<Item, Long> sell = Map.of();
	private static volatile Map<Item, Long> buy = Map.of();
	private static volatile List<ShopCategory> categories = List.of();
	private static final List<Runnable> LISTENERS = new ArrayList<>();

	public static final ConfigManager.Config<Map<String, Long>> PRICES = ConfigManager.registerResource(
		"prices", new TypeToken<Map<String, Long>>() {}.getType(), "prices.json", BasePrices::indexPrices);
	public static final ConfigManager.Config<ShopConfig> SHOP = ConfigManager.registerResource(
		"shop", ShopConfig.class, "shop.json", BasePrices::indexShop);

	private BasePrices() {}

	public static void bootstrap() {
	}

	public static OptionalLong sellPrice(Item item) {
		Long v = sell.get(item);
		return v == null ? OptionalLong.empty() : OptionalLong.of(v);
	}

	public static Map<Item, Long> sellPrices() {
		return sell;
	}

	/** With duplicate entries, the lowest wins. */
	public static OptionalLong shopBuyPrice(Item item) {
		Long v = buy.get(item);
		return v == null ? OptionalLong.empty() : OptionalLong.of(v);
	}

	public static Map<Item, Long> shopBuyPrices() {
		return buy;
	}

	public static List<ShopCategory> shopCategories() {
		return categories;
	}

	public static Optional<ShopItem> shopItem(Item item) {
		for (ShopCategory c : categories) {
			for (ShopItem i : c.items()) {
				if (i.item() == item) return Optional.of(i);
			}
		}
		return Optional.empty();
	}

	public static void onReload(Runnable listener) {
		LISTENERS.add(listener);
	}

	private static void indexPrices(Map<String, Long> raw) {
		Map<Item, Long> map = new LinkedHashMap<>();
		List<String> unknown = new ArrayList<>();
		int skipped = 0;
		for (Map.Entry<String, Long> e : raw.entrySet()) {
			Optional<Item> item = Items.byId(e.getKey());
			Long price = e.getValue();
			if (item.isEmpty()) {
				unknown.add(e.getKey());
				skipped++;
				continue;
			}
			if (price == null || price <= 0 || price > Money.MAX) {
				SmpEconomy.LOG.warn("[SMPEco] prices.json: invalid entry '{}': {}, skipped", e.getKey(), price);
				skipped++;
				continue;
			}
			map.put(item.get(), price);
		}
		UnknownItems.report("prices.json", unknown);
		sell = Collections.unmodifiableMap(map);
		SmpEconomy.LOG.info("[SMPEco] {} sell prices loaded{}", map.size(), skipped > 0 ? " (" + skipped + " skipped)" : "");
		fire();
	}

	private static void indexShop(ShopConfig raw) {
		if (raw.categories == null) throw new IllegalArgumentException("'categories' is missing");
		Map<Item, Long> prices = new LinkedHashMap<>();
		List<ShopCategory> cats = new ArrayList<>();
		List<String> unknown = new ArrayList<>();
		int skipped = 0;
		for (CategoryConfig c : raw.categories) {
			if (c == null || c.id == null || c.id.isBlank()) throw new IllegalArgumentException("Category without 'id'");
			List<ShopItem> items = new ArrayList<>();
			for (ItemConfig ic : c.items == null ? List.<ItemConfig>of() : c.items) {
				Optional<Item> item = ic == null ? Optional.empty() : Items.byId(ic.item);
				if (ic != null && item.isEmpty()) {
					unknown.add(ic.item);
					skipped++;
					continue;
				}
				if (ic == null || ic.price <= 0 || ic.price > Money.MAX || ic.max <= 0) {
					SmpEconomy.LOG.warn("[SMPEco] shop.json ({}): invalid entry '{}', skipped", c.id, ic == null ? null : ic.item);
					skipped++;
					continue;
				}
				items.add(new ShopItem(item.get(), ic.price, ic.max));
				prices.merge(item.get(), ic.price, Math::min);
			}
			Item icon = Items.byId(c.icon).orElse(net.minecraft.world.item.Items.CHEST);
			cats.add(new ShopCategory(c.id, c.name == null || c.name.isBlank() ? c.id : c.name, icon, List.copyOf(items)));
		}
		UnknownItems.report("shop.json", unknown);
		buy = Collections.unmodifiableMap(prices);
		categories = List.copyOf(cats);
		SmpEconomy.LOG.info("[SMPEco] Shop loaded: {} items in {} categories{}", prices.size(), cats.size(),
			skipped > 0 ? " (" + skipped + " skipped)" : "");
		fire();
	}

	private static void fire() {
		for (Runnable l : List.copyOf(LISTENERS)) {
			try {
				l.run();
			} catch (RuntimeException e) {
				SmpEconomy.LOG.error("[SMPEco] BasePrices listener failed", e);
			}
		}
	}
}
