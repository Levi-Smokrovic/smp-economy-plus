package dev.smpeconomy.features.shop;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import dev.smpeconomy.core.BasePrices;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Perms;
import dev.smpeconomy.core.TimeUtil;

/**
 * Display-only slots with no real items, so nothing can be taken out. Entries: left click buys, right click
 * sells, shift + right click toggles favorite.
 */
public final class ShopGui {
	public static final String NODE = "smpeconomy.shop";
	public static final String NODE_SELL = "smpeconomy.shop.sell";

	static final int MAIN_INFO = 4;
	static final int MAIN_DEALS_HEADER = 10;
	static final int MAIN_DEALS_FIRST = 11;
	static final int MAIN_DEALS_SLOTS = 5;
	static final int[] MAIN_CATEGORY_SLOTS = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
	static final int MAIN_RECENT_FIRST = 36;
	static final int MAIN_MENU = 45;
	static final int MAIN_FAVORITES = 47;
	static final int MAIN_CLOSE = 49;
	static final int MAIN_SEARCH = 51;

	static final int LIST_PER_PAGE = 45;
	static final int LIST_BACK = 45;
	static final int LIST_BALANCE = 47;
	static final int LIST_PREV = 48;
	static final int LIST_PAGE = 49;
	static final int LIST_NEXT = 50;
	static final int LIST_SEARCH = 51;
	static final int LIST_CLOSE = 53;

	private ShopGui() {}

	public static void openMain(ServerPlayer player) {
		if (!Gui.canOpen(player)) return;
		SimpleGui gui = Gui.chest(player, 6, "§8Shop");
		renderMain(player, gui);
		gui.open();
	}

	private static void renderMain(ServerPlayer player, SimpleGui gui) {
		UUID id = player.getUUID();
		for (int i = 0; i < 54; i++) gui.setSlot(i, Gui.border());
		gui.setSlot(MAIN_INFO, Gui.item(net.minecraft.world.item.Items.GOLD_INGOT, "§6§lServer Shop",
			"§7Balance: §a" + Money.format(EconomyService.balance(id)),
			"",
			"§eLeft-click §7» Buy",
			"§eRight-click §7» Sell §8(same as /sell)",
			"§eShift+Right-click §7» Favorite ★",
			"",
			"§8Search: /shop search <text>"));

		List<DailyDeals.Deal> deals = DailyDeals.today();
		gui.setSlot(MAIN_DEALS_HEADER, Gui.button(net.minecraft.world.item.Items.CLOCK, "§6§lDaily Deals",
			() -> openList(player, ListKind.DEALS, "", 0),
			deals.isEmpty() ? "§7No deals today." : "§7" + deals.size() + (deals.size() == 1 ? " item" : " items") + " cheaper today.",
			"§7New deals in §f" + TimeUtil.formatDuration(DailyDeals.millisUntilReset()),
			"",
			"§eClick §7» Show all").glow());
		Runnable reopenMain = () -> openMain(player);
		Runnable refreshMain = () -> renderMain(player, gui);
		for (int i = 0; i < MAIN_DEALS_SLOTS; i++) {
			int slot = MAIN_DEALS_FIRST + i;
			if (i < deals.size() && BasePrices.shopBuyPrice(deals.get(i).item()).isPresent()) {
				gui.setSlot(slot, entry(player, deals.get(i).item(), refreshMain, reopenMain));
			} else {
				gui.setSlot(slot, Gui.filler());
			}
		}
		gui.setSlot(16, Gui.filler());

		List<BasePrices.ShopCategory> cats = BasePrices.shopCategories();
		for (int i = 0; i < MAIN_CATEGORY_SLOTS.length; i++) {
			int slot = MAIN_CATEGORY_SLOTS[i];
			if (i < cats.size()) {
				BasePrices.ShopCategory c = cats.get(i);
				gui.setSlot(slot, Gui.button(c.icon(), "§e§l" + c.name(), () -> openList(player, ListKind.CATEGORY, c.id(), 0),
					"§7" + distinct(c).size() + " items", "", "§eClick §7» Open"));
			} else {
				gui.setSlot(slot, Gui.filler());
			}
		}

		List<ShopPlayerData.RecentPurchase> recent = ShopPlayerData.recent(id);
		for (int i = 0; i < ShopPlayerData.RECENT_MAX; i++) {
			int slot = MAIN_RECENT_FIRST + i;
			if (i < recent.size() && BasePrices.shopBuyPrice(recent.get(i).item()).isPresent()) {
				gui.setSlot(slot, recentEntry(player, recent.get(i), refreshMain, reopenMain));
			} else {
				gui.setSlot(slot, Gui.item(net.minecraft.world.item.Items.LIGHT_GRAY_STAINED_GLASS_PANE, "§8Recently bought",
					"§8Your last purchases show up", "§8here for a quick rebuy."));
			}
		}

		int favCount = listEntries(id, ListKind.FAVORITES, "").size();
		gui.setSlot(MAIN_FAVORITES, Gui.button(net.minecraft.world.item.Items.NETHER_STAR, "§6§l★ Favorites",
			() -> openList(player, ListKind.FAVORITES, "", 0),
			"§7" + favCount + " saved", "§8Shift+Right-click an item", "§8to save it here.", "", "§eClick §7» Open"));
		gui.setSlot(MAIN_CLOSE, Gui.closeButton(gui));
		gui.setSlot(MAIN_SEARCH, Gui.button(net.minecraft.world.item.Items.OAK_SIGN, "§e§lSearch",
			() -> openSearchInput(player, reopenMain),
			"§7Find an item by name", "§8(name or item ID)", "", "§eClick §7» Enter a search term"));
		Gui.menuButton(gui, MAIN_MENU);
	}

	private static List<Item> distinct(BasePrices.ShopCategory c) {
		Set<Item> set = new LinkedHashSet<>();
		for (BasePrices.ShopItem i : c.items()) set.add(i.item());
		return new ArrayList<>(set);
	}

	static void openSearchInput(ServerPlayer player, Runnable onCancel) {
		if (!Gui.canOpen(player)) return;
		Gui.textInput(player, "§8Shop Search", "", text -> {
			if (!Gui.canOpen(player)) return;
			if (!ShopSearch.isValidQuery(text)) {
				Gui.failSound(player);
				Msg.error(player, "Search term: " + ShopSearch.MIN_LENGTH + "–" + ShopSearch.MAX_LENGTH + " characters.");
				onCancel.run();
				return;
			}
			openList(player, ListKind.SEARCH, text.trim(), 0);
		}, onCancel);
	}

	public enum ListKind { CATEGORY, DEALS, FAVORITES, SEARCH }

	/** {@code key} is a category ID or search term. */
	public static void openList(ServerPlayer player, ListKind kind, String rawKey, int page) {
		if (!Gui.canOpen(player)) return;
		// The search text comes from the player, so formatting codes must not reach titles or texts
		String key = rawKey == null ? "" : rawKey.replace('§', ' ').trim();
		String title = switch (kind) {
			case CATEGORY -> "§8Shop » " + categoryName(key);
			case DEALS -> "§8Shop » Daily Deals";
			case FAVORITES -> "§8Shop » Favorites";
			case SEARCH -> "§8Shop » Search: " + (key.length() > 20 ? key.substring(0, 20) + "…" : key);
		};
		SimpleGui gui = Gui.chest(player, 6, title);
		int[] currentPage = {page};
		Runnable[] render = new Runnable[1];
		render[0] = () -> renderList(player, gui, kind, key, currentPage, render[0]);
		render[0].run();
		gui.open();
	}

	private static String categoryName(String id) {
		for (BasePrices.ShopCategory c : BasePrices.shopCategories()) {
			if (c.id().equals(id)) return c.name();
		}
		return id;
	}

	/** Only items currently in the shop. */
	static List<Item> listEntries(UUID player, ListKind kind, String key) {
		List<Item> items = new ArrayList<>();
		switch (kind) {
			case CATEGORY -> {
				for (BasePrices.ShopCategory c : BasePrices.shopCategories()) {
					if (c.id().equals(key)) items.addAll(distinct(c));
				}
			}
			case DEALS -> {
				for (DailyDeals.Deal d : DailyDeals.today()) items.add(d.item());
			}
			case FAVORITES -> items.addAll(ShopPlayerData.favorites(player));
			case SEARCH -> items.addAll(ShopSearch.search(key));
		}
		items.removeIf(i -> BasePrices.shopBuyPrice(i).isEmpty());
		return items;
	}

	private static void renderList(ServerPlayer player, SimpleGui gui, ListKind kind, String key, int[] page, Runnable rerender) {
		UUID id = player.getUUID();
		List<Item> entries = listEntries(id, kind, key);
		int total = entries.size();
		int pages = Math.max(1, (total + LIST_PER_PAGE - 1) / LIST_PER_PAGE);
		page[0] = Math.max(0, Math.min(page[0], pages - 1));
		int p = page[0];
		Runnable reopen = () -> openList(player, kind, key, p);
		for (int i = 0; i < LIST_PER_PAGE; i++) {
			int idx = p * LIST_PER_PAGE + i;
			if (idx < total) gui.setSlot(i, entry(player, entries.get(idx), rerender, reopen));
			else gui.clearSlot(i);
		}
		if (total == 0) {
			String[] hint = switch (kind) {
				case FAVORITES -> new String[] {"§7No favorites yet.", "§8Shift+Right-click an item in the shop", "§8to save it here."};
				case SEARCH -> new String[] {"§7No results for §f" + key + "§7.", "§8Try a different search term."};
				case DEALS -> new String[] {"§7No deals today."};
				case CATEGORY -> new String[] {"§7This category is empty."};
			};
			gui.setSlot(22, Gui.item(net.minecraft.world.item.Items.BARRIER, "§cNothing found", hint));
		}
		for (int i = LIST_PER_PAGE; i < 54; i++) gui.setSlot(i, Gui.border());
		gui.setSlot(LIST_BACK, Gui.backButton(() -> openMain(player)));
		gui.setSlot(LIST_BALANCE, Gui.item(net.minecraft.world.item.Items.GOLD_INGOT, "§7Balance: §a" + Money.format(EconomyService.balance(id))));
		gui.setSlot(LIST_PREV, p > 0
			? Gui.button(net.minecraft.world.item.Items.ARROW, "§e« Previous Page", () -> {
				page[0] = p - 1;
				rerender.run();
			})
			: Gui.border());
		gui.setSlot(LIST_PAGE, Gui.item(net.minecraft.world.item.Items.PAPER, "§7Page §f" + (p + 1) + "§7/§f" + pages, "§8" + total + (total == 1 ? " item" : " items")));
		gui.setSlot(LIST_NEXT, p < pages - 1
			? Gui.button(net.minecraft.world.item.Items.ARROW, "§eNext Page »", () -> {
				page[0] = p + 1;
				rerender.run();
			})
			: Gui.border());
		gui.setSlot(LIST_SEARCH, Gui.button(net.minecraft.world.item.Items.OAK_SIGN, "§eSearch", () -> openSearchInput(player, reopen),
			"§7Find an item by name"));
		gui.setSlot(LIST_CLOSE, Gui.closeButton(gui));
	}

	static MutableComponent displayName(UUID player, Item item) {
		boolean fav = ShopPlayerData.isFavorite(player, item);
		boolean deal = DailyDeals.dealFor(item) != null;
		MutableComponent name = Component.empty();
		if (fav) name.append(Msg.legacy("§6★ "));
		name.append(Msg.itemName(item).withStyle(deal ? ChatFormatting.GOLD : ChatFormatting.WHITE));
		return name;
	}

	static GuiElementBuilder entry(ServerPlayer player, Item item, Runnable refresh, Runnable reopen) {
		UUID id = player.getUUID();
		boolean fav = ShopPlayerData.isFavorite(id, item);
		boolean sellable = ShopLore.sellable(id, item);
		List<String> lore = new ArrayList<>(ShopLore.priceLines(id, item, Items.count(player, s -> s.is(item))));
		lore.addAll(ShopLore.clickLines(sellable && Perms.check(player, NODE_SELL), fav));
		GuiElementBuilder b = new GuiElementBuilder(item).setName(displayName(id, item)).hideDefaultTooltip();
		Gui.lore(b, lore.toArray(String[]::new));
		if (DailyDeals.dealFor(item) != null) b.glow();
		b.setCallback((index, type, action, g) -> {
			if (!Gui.acceptClick(player, index, type)) return;
			if (type.isRight && type.shift) {
				toggleFavorite(player, item);
				refresh.run();
				return;
			}
			Gui.click(player);
			if (type.isRight) openSell(player, item, reopen);
			else TradeDialog.open(player, item, TradeDialog.Mode.BUY, 1, reopen);
		});
		return b;
	}

	private static GuiElementBuilder recentEntry(ServerPlayer player, ShopPlayerData.RecentPurchase r, Runnable refresh, Runnable reopen) {
		UUID id = player.getUUID();
		Item item = r.item();
		int amount = Math.max(1, Math.min(r.amount(), Math.max(1, ShopService.maxPerPurchase(item))));
		OptionalLong unit = ShopService.unitBuyPrice(id, item);
		long shownUnit = unit.orElse(-1);
		long total = shownUnit > 0 ? ShopService.total(shownUnit, amount) : -1;
		boolean fav = ShopPlayerData.isFavorite(id, item);
		List<String> lore = new ArrayList<>();
		lore.add("§8Recently bought");
		lore.add("§7Amount: §f" + Msg.number(amount));
		if (shownUnit > 0) {
			lore.add("§7Price now: §a" + Money.format(shownUnit) + " §8each");
			if (total > 0) lore.add("§7Total: §a" + Money.format(total));
		}
		lore.add("");
		lore.add("§eLeft-click §7» Buy " + Msg.number(amount) + "× again");
		lore.add("§eShift+Left-click §7» Choose amount");
		if (ShopLore.sellable(id, item) && ShopConfig.get().sellInShop && Perms.check(player, NODE_SELL)) lore.add("§eRight-click §7» Sell");
		lore.add("§eShift+Right-click §7» " + (fav ? "Remove favorite" : "Add to favorites ★"));
		GuiElementBuilder b = new GuiElementBuilder(item).setName(displayName(id, item)).hideDefaultTooltip()
			.setCount(Math.max(1, Math.min(amount, Math.min(64, item.getDefaultMaxStackSize()))));
		Gui.lore(b, lore.toArray(String[]::new));
		b.setCallback((index, type, action, g) -> {
			if (!Gui.acceptClick(player, index, type)) return;
			if (type.isRight && type.shift) {
				toggleFavorite(player, item);
				refresh.run();
				return;
			}
			Gui.click(player);
			if (type.isRight) {
				openSell(player, item, reopen);
			} else if (type.shift) {
				TradeDialog.open(player, item, TradeDialog.Mode.BUY, amount, reopen);
			} else {
				ShopService.BuyResult res = ShopService.buy(player, item, amount, shownUnit > 0 ? shownUnit : 0);
				feedbackBuy(player, item, res);
				refresh.run();
			}
		});
		return b;
	}

	private static void openSell(ServerPlayer player, Item item, Runnable reopen) {
		if (!ShopConfig.get().sellInShop || !Perms.check(player, NODE_SELL)) {
			Gui.failSound(player);
			Msg.error(player, (ShopConfig.get().sellInShop ? ShopService.Status.NO_PERMISSION : ShopService.Status.DISABLED).message());
			return;
		}
		if (ShopService.sellBlocked(player)) {
			Gui.failSound(player);
			Msg.error(player, ShopService.Status.BLOCKED.message());
			return;
		}
		if (!ShopLore.sellable(player.getUUID(), item)) {
			Gui.failSound(player);
			Msg.error(player, ShopService.Status.NOT_SELLABLE.message());
			return;
		}
		TradeDialog.open(player, item, TradeDialog.Mode.SELL, Integer.MAX_VALUE, reopen);
	}

	static void toggleFavorite(ServerPlayer player, Item item) {
		int max = ShopConfig.get().maxFavorites;
		switch (ShopPlayerData.toggleFavorite(player.getUUID(), item, max)) {
			case ADDED -> {
				Gui.sound(player, SoundEvents.NOTE_BLOCK_BELL, 1.6f);
				Msg.send(player, Msg.prefixed(Msg.legacy("§6★ ").append(Msg.itemName(item).withStyle(ChatFormatting.WHITE))
					.append(Msg.legacy(" §7added to your favorites."))));
			}
			case REMOVED -> {
				Gui.sound(player, SoundEvents.NOTE_BLOCK_BELL, 0.8f);
				Msg.send(player, Msg.prefixed(Msg.legacy("§7").append(Msg.itemName(item).withStyle(ChatFormatting.WHITE))
					.append(Msg.legacy(" §7removed from your favorites."))));
			}
			case LIMIT -> {
				Gui.failSound(player);
				Msg.error(player, "You already have " + max + " favorites – remove one first.");
			}
		}
	}

	static void feedbackBuy(ServerPlayer player, Item item, ShopService.BuyResult r) {
		if (r.status().ok()) {
			Gui.successSound(player);
			Msg.send(player, Msg.prefixed(Msg.legacy("§aBought §f" + Msg.number(r.delivered()) + "× ")
				.append(Msg.itemName(item).withStyle(ChatFormatting.WHITE))
				.append(Msg.legacy(" §7for §a" + Money.format(r.total())))));
			if (r.partial()) {
				Msg.info(player, "§eOnly " + Msg.number(r.delivered()) + " of " + Msg.number(r.requested())
					+ " fit in your inventory – you only paid for those.");
			}
			return;
		}
		Gui.failSound(player);
		switch (r.status()) {
			case INSUFFICIENT_FUNDS -> {
				int n = Math.max(1, Math.min(r.requested(), ShopService.fitCount(player, item)));
				long cost = r.unitPrice() > 0 ? ShopService.total(r.unitPrice(), n) : -1;
				Msg.error(player, cost > 0
					? "Not enough money: " + Msg.number(n) + "× would cost " + Money.format(cost) + ", you have " + Money.format(r.balanceAfter()) + "."
					: r.status().message());
			}
			case PRICE_CHANGED -> Msg.error(player, "The price just changed (now " + Money.format(r.unitPrice())
				+ " each) – please check and click again.");
			default -> Msg.error(player, r.status().message());
		}
	}

	static void feedbackSell(ServerPlayer player, Item item, ShopService.SellResult r) {
		if (r.status().ok()) {
			Gui.sound(player, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.EXPERIENCE_ORB_PICKUP), 1.2f);
			Msg.send(player, Msg.prefixed(Msg.legacy("§aSold §f" + Msg.number(r.sold()) + "× ")
				.append(Msg.itemName(item).withStyle(ChatFormatting.WHITE))
				.append(Msg.legacy(" §7for §a" + Money.format(r.total())))));
			return;
		}
		Gui.failSound(player);
		if (r.status() == ShopService.Status.PRICE_CHANGED) {
			Msg.error(player, "The sell price just changed (now " + Money.format(r.unitPrice())
				+ " each) – please check and click again.");
		} else {
			Msg.error(player, r.status().message());
		}
	}

	static Optional<BasePrices.ShopCategory> category(String id) {
		for (BasePrices.ShopCategory c : BasePrices.shopCategories()) {
			if (c.id().equalsIgnoreCase(id)) return Optional.of(c);
		}
		return Optional.empty();
	}
}
