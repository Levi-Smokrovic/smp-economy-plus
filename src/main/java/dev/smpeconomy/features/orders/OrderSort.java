package dev.smpeconomy.features.orders;

import com.google.gson.JsonElement;
import eu.pb4.sgui.api.gui.SimpleGui;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.BasePrices;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.PriceModifiers;
import dev.smpeconomy.features.orders.OrderData.Order;

/**
 * Sorting of the {@code /orders} overview: the sort button, sort keys, a cache of sorted lists and the per-player
 * choice.
 * <p>Prices are the effective prices shown in the lore ({@link OrderBook#unitPrice} for the viewer). Demand is that
 * price divided by the /sell reference, on the same scale for all orders. For the board that means what the next item
 * really pays and not the raw {@link NpcBoard#factor}: with the daily bonus used up, a board order at factor 1.30
 * only pays /sell and ranks behind a player order at 1.25 × /sell. Orders accepting nothing (price 0) always sort
 * last on price and demand.
 * <p>The cache holds one sorted list per state (sort × players first) and is dropped on any order book change, price
 * change, new config, or after {@link #CACHE_TTL_MS} for slow drift such as news fading and supply recovering.
 * Filter, search and "You have" are read fresh from the sorted list on each page build, which is cheap.
 */
final class OrderSort {
	static final int SLOT = 53;
	static final String KEY_SORT = "orders.sort";
	static final String KEY_PLAYERS_FIRST = "orders.players_first";
	static final long CACHE_TTL_MS = 10_000L;

	/** Bumped on every price change (PriceModifiers, BasePrices reload). */
	private static long priceVersion;
	private static boolean registered;
	private static final Map<Item, String> NAME_KEYS = new HashMap<>();

	private OrderSort() {}

	enum Sort {
		PRICE_DESC("price_desc", "Price per item ↓", "most expensive first"),
		PRICE_ASC("price_asc", "Price per item ↑", "cheapest first"),
		TOTAL_DESC("total_desc", "Total payout ↓", "remaining amount × price"),
		DEMAND_DESC("demand_desc", "Demand ↓", "price compared to the /sell value"),
		NEWEST("newest", "Newest", "most recently created first"),
		NAME_AZ("name_az", "Name A–Z", "by item name");

		static final Sort DEFAULT = PRICE_DESC;

		final String id;
		final String label;
		final String hint;

		Sort(String id, String label, String hint) {
			this.id = id;
			this.label = label;
			this.hint = hint;
		}

		Sort next(boolean back) {
			Sort[] all = values();
			return all[(ordinal() + (back ? all.length - 1 : 1)) % all.length];
		}

		static Optional<Sort> byId(String id) {
			for (Sort s : values()) {
				if (s.id.equals(id)) return Optional.of(s);
			}
			return Optional.empty();
		}
	}

	static void register() {
		if (registered) return;
		registered = true;
		PriceModifiers.addListener(OrderSort::pricesChanged);
		BasePrices.onReload(OrderSort::pricesChanged);
	}

	static void pricesChanged() {
		priceVersion++;
	}

	static OrdersGui.View load(OrdersGui.View view, UUID player) {
		view.sort = savedSort(player);
		view.playersFirst = savedPlayersFirst(player);
		return view;
	}

	static Sort savedSort(UUID player) {
		String id = player == null ? null : PlayerDataService.getString(player, KEY_SORT);
		return id == null ? Sort.DEFAULT : Sort.byId(id).orElse(Sort.DEFAULT);
	}

	/** Only a stored {@code false} turns it off; a missing or unreadable entry means on. */
	static boolean savedPlayersFirst(UUID player) {
		if (player == null) return true;
		JsonElement e = PlayerDataService.peek(player).map(d -> d.blobs.get(KEY_PLAYERS_FIRST)).orElse(null);
		return e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean() || e.getAsBoolean();
	}

	static void save(UUID player, Sort sort, boolean playersFirst) {
		if (player == null) return;
		if (sort == Sort.DEFAULT) {
			if (PlayerDataService.getString(player, KEY_SORT) != null) PlayerDataService.remove(player, KEY_SORT);
		} else if (!sort.id.equals(PlayerDataService.getString(player, KEY_SORT))) {
			PlayerDataService.setString(player, KEY_SORT, sort.id);
		}
		boolean stored = PlayerDataService.peek(player).map(d -> d.blobs.containsKey(KEY_PLAYERS_FIRST)).orElse(false);
		if (playersFirst) {
			if (stored) PlayerDataService.remove(player, KEY_PLAYERS_FIRST);
		} else if (!stored || savedPlayersFirst(player)) {
			PlayerDataService.setBlob(player, KEY_PLAYERS_FIRST, false);
		}
	}

	static void render(SimpleGui gui, Gui.Pager<OrdersGui.Row> pager, ServerPlayer player, OrdersGui.View view) {
		List<String> lore = new ArrayList<>();
		for (Sort s : Sort.values()) lore.add((s == view.sort ? "§e» §f" : "§8  ") + s.label + " §8– " + s.hint);
		lore.add("");
		lore.add("§7Players first: " + (view.playersFirst ? "§aon" : "§coff"));
		lore.add("§8on: player orders on top, then NPCs");
		lore.add("§8off: everything mixed in one list");
		lore.add("");
		lore.add("§eLeft: next §8· §eRight: back");
		lore.add("§eShift-click: players first on/off");
		gui.setSlot(SLOT, Gui.button(Items.COMPARATOR, "§eSort: §f" + view.sort.label, (Gui.ClickHandler) t -> {
			if (t.shift) view.playersFirst = !view.playersFirst;
			else view.sort = view.sort.next(t.isRight);
			save(player.getUUID(), view.sort, view.playersFirst);
			view.page = 0;
			render(gui, pager, player, view);
			pager.show(0);
		}, lore.toArray(String[]::new)));
	}

	private record Keyed(Order order, int group, long unit, long total, double demand, String name) {}

	static List<Order> sort(OrderBook book, UUID viewer, Sort sort, boolean playersFirst) {
		Collection<Order> src = book.data().orders.values();
		List<Keyed> keyed = new ArrayList<>(src.size());
		for (Order o : src) keyed.add(keyOf(book, viewer, o, sort, playersFirst));
		keyed.sort(comparator(sort));
		List<Order> out = new ArrayList<>(keyed.size());
		for (Keyed k : keyed) out.add(k.order());
		return out;
	}

	static int group(Order o, boolean playersFirst) {
		return !playersFirst || !o.npc ? 0 : o.bulk ? 1 : 2;
	}

	private static Keyed keyOf(OrderBook book, UUID viewer, Order o, Sort sort, boolean playersFirst) {
		int group = group(o, playersFirst);
		Optional<Item> type = o.itemType();
		return switch (sort) {
			case PRICE_DESC, PRICE_ASC -> new Keyed(o, group, unit(book, viewer, o, type), 0, 0, null);
			case TOTAL_DESC -> new Keyed(o, group, 0, OrdersGui.safeMul(o.open(), unit(book, viewer, o, type)), 0, null);
			case DEMAND_DESC -> new Keyed(o, group, 0, 0, demand(book, viewer, o, type), null);
			case NEWEST -> new Keyed(o, group, 0, 0, 0, null);
			case NAME_AZ -> new Keyed(o, group, 0, 0, 0, type.map(OrderSort::nameKey).orElse("~" + o.item));
		};
	}

	static long unit(OrderBook book, UUID viewer, Order o) {
		return unit(book, viewer, o, o.itemType());
	}

	private static long unit(OrderBook book, UUID viewer, Order o, Optional<Item> type) {
		if (type.isEmpty()) return 0;
		// board: unitPrice = quote().unit() = payout(…, 1).first(), taken directly to skip the extra breakdown in
		// quote
		return Math.max(0, o.board ? book.board().payout(viewer, o, 1).first() : book.unitPrice(viewer, o));
	}

	/**
	 * Effective unit price ({@link #unit}) divided by the /sell reference, for all order types. 0 means it accepts
	 * nothing right now, -1 means no /sell value (sorted last).
	 */
	static double demand(OrderBook book, UUID viewer, Order o) {
		return demand(book, viewer, o, o.itemType());
	}

	private static double demand(OrderBook book, UUID viewer, Order o, Optional<Item> type) {
		if (type.isEmpty()) return -1;
		long unit = unit(book, viewer, o, type);
		if (unit <= 0) return 0;
		long ref = NpcBoard.referencePrice(type.get());
		return ref <= 0 ? -1 : (double) unit / ref;
	}

	static String nameKey(Item item) {
		String key = NAME_KEYS.get(item);
		if (key != null) return key;
		key = OrderSearch.normalize(net.minecraft.locale.Language.getInstance().getOrDefault(item.getDescriptionId()));
		if (SmpEconomy.server() != null) NAME_KEYS.put(item, key); // possibly loaded before without the language file
		return key;
	}

	private static Comparator<Keyed> comparator(Sort sort) {
		Comparator<Keyed> byGroup = Comparator.comparingInt(Keyed::group);
		Comparator<Keyed> byKey = switch (sort) {
			// accepts nothing (price 0): always last
			case PRICE_DESC -> (a, b) -> a.unit() > 0 != b.unit() > 0 ? (a.unit() > 0 ? -1 : 1) : Long.compare(b.unit(), a.unit());
			case PRICE_ASC -> (a, b) -> a.unit() > 0 != b.unit() > 0 ? (a.unit() > 0 ? -1 : 1) : Long.compare(a.unit(), b.unit());
			case TOTAL_DESC -> (a, b) -> Long.compare(b.total(), a.total());
			case DEMAND_DESC -> (a, b) -> Double.compare(b.demand(), a.demand());
			case NEWEST -> (a, b) -> {
				int c = Long.compare(b.order().createdAt, a.order().createdAt);
				return c != 0 ? c : Long.compare(b.order().id, a.order().id);
			};
			case NAME_AZ -> (a, b) -> {
				int c = a.name().compareTo(b.name());
				return c != 0 ? c : a.order().item.compareTo(b.order().item);
			};
		};
		Comparator<Keyed> tie = Comparator.<Keyed>comparingInt(k -> OrderBook.rank(k.order())).thenComparingLong(k -> k.order().id);
		return byGroup.thenComparing(byKey).thenComparing(tie);
	}

	static final class Cache {
		private final Map<Integer, List<Order>> lists = new HashMap<>();
		private OrderBook book;
		private UUID viewer;
		private Object config;
		private long bookVersion = -1;
		private long prices = -1;
		private long builtAt;
		int builds;

		List<Order> get(OrderBook book, UUID viewer, Sort sort, boolean playersFirst) {
			long now = book.now();
			Object cfg = book.config();
			if (book != this.book || !viewer.equals(this.viewer) || cfg != config || book.version() != bookVersion || prices != priceVersion
					|| now < builtAt || now - builtAt >= CACHE_TTL_MS) {
				lists.clear();
				this.book = book;
				this.viewer = viewer;
				config = cfg;
				bookVersion = book.version();
				prices = priceVersion;
				builtAt = now;
			}
			int key = sort.ordinal() * 2 + (playersFirst ? 1 : 0);
			List<Order> list = lists.get(key);
			if (list == null) {
				list = sort(book, viewer, sort, playersFirst);
				lists.put(key, list);
				builds++;
			}
			return list;
		}
	}
}
