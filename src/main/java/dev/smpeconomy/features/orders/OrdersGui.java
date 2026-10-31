package dev.smpeconomy.features.orders;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.TooltipDisplay;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.PriceGuard;
import dev.smpeconomy.core.PriceModifiers;
import dev.smpeconomy.core.TimeUtil;
import dev.smpeconomy.features.orders.OrderBook.CancelResult;
import dev.smpeconomy.features.orders.OrderBook.ClaimResult;
import dev.smpeconomy.features.orders.OrderBook.CreateResult;
import dev.smpeconomy.features.orders.OrderBook.DeliverResult;
import dev.smpeconomy.features.orders.OrderBook.Status;
import dev.smpeconomy.features.orders.OrderBook.Terms;
import dev.smpeconomy.features.orders.OrderData.Claim;
import dev.smpeconomy.features.orders.OrderData.Order;

/**
 * Chest GUIs for /orders (display slots only, no real item slots). Every action re-reads the order by id and goes
 * through {@link OrderBook}. The overview only renders the visible page of 45 entries.
 */
final class OrdersGui {
	static final String TITLE_MAIN = "§8Orders";
	static final String TITLE_MINE = "§8My Orders";
	static final String TITLE_CLAIMS = "§8Claim Chest";
	static final String TITLE_DRAFT = "§8New Order";
	static final String TITLE_CREATE = "§8Create Order?";
	static final String TITLE_CANCEL = "§8Cancel Order?";
	static final int[] DELIVER_STEPS = {1, 16, 64};

	private OrdersGui() {}

	private static OrderBook book() {
		return OrdersFeature.BOOK;
	}

	enum Filter {
		ALL("§fAll", "player and NPC orders"),
		PLAYERS("§aPlayers", "only orders from players"),
		NPC("§eNPC", "only NPC orders"),
		DELIVERABLE("§bDeliverable", "only ones you have items for");

		final String label;
		final String description;

		Filter(String label, String description) {
			this.label = label;
			this.description = description;
		}

		Filter next(boolean back) {
			Filter[] all = values();
			return all[(ordinal() + (back ? all.length - 1 : 1)) % all.length];
		}
	}

	static final class View {
		Filter filter = Filter.ALL;
		String query = "";
		int page;
		OrderSort.Sort sort = OrderSort.Sort.DEFAULT;
		boolean playersFirst = true;
		final OrderSort.Cache cache = new OrderSort.Cache();
	}

	sealed interface Row permits Header, Entry {}

	record Header(boolean players, int count) implements Row {}

	record Entry(Order order, int have) implements Row {}

	static void openMain(ServerPlayer player) {
		openMain(player, OrderSort.load(new View(), player.getUUID()));
	}

	static void openMain(ServerPlayer player, View view) {
		if (!Gui.canOpen(player)) return;
		SimpleGui gui = Gui.chest(player, 6, TITLE_MAIN);
		List<Gui.Pager<Row>> holder = new ArrayList<>(1);
		Gui.Pager<Row> pager = new Gui.Pager<>(gui, () -> rows(book(), player, view), row -> rowIcon(gui, player, view, holder.get(0), row));
		holder.add(pager);
		pager.withMenuButton(); // last row is full: main menu on the page indicator (49)
		UUID id = player.getUUID();
		gui.setSlot(45, Gui.button(Items.CHEST, "§eMy Orders", () -> openMine(player, 0),
			"§7Active: §f" + book().countOwned(id) + "§7/§f" + book().config().maxOrdersPerPlayer, "§7View progress, cancel", "", "§eClick: Open"));
		gui.setSlot(46, Gui.button(Items.BARREL, "§eClaim Chest", () -> openClaims(player, 0),
			"§7Delivered items: §f" + Msg.number(book().claimTotal(id)), "", "§eClick: Open"));
		gui.setSlot(47, Gui.button(Items.WRITABLE_BOOK, "§aNew Order", () -> startDraft(player),
			"§7Uses the item in your hand as a template", "§8(incl. enchantment/potion).", "§7Or: §f/orders create <item> <amount> <price>", "",
			"§eClick: Create"));
		renderFilter(gui, pager, view);
		renderSearch(gui, player, view);
		OrderSort.render(gui, pager, player, view); // 53 (not "Close": ESC closes)
		pager.show(view.page);
		gui.open();
	}

	private static void renderFilter(SimpleGui gui, Gui.Pager<Row> pager, View view) {
		List<String> lore = new ArrayList<>();
		for (Filter f : Filter.values()) lore.add((f == view.filter ? "§e» " : "§8  ") + f.label + " §8– " + f.description);
		lore.add("");
		lore.add("§eLeft: next §8· §eRight: back");
		gui.setSlot(51, Gui.button(Items.HOPPER, "§eFilter: " + view.filter.label, (Gui.ClickHandler) t -> {
			view.filter = view.filter.next(t.isRight);
			view.page = 0;
			renderFilter(gui, pager, view);
			pager.show(0);
		}, lore.toArray(String[]::new)));
	}

	private static void renderSearch(SimpleGui gui, ServerPlayer player, View view) {
		boolean active = !view.query.isBlank();
		List<String> lore = new ArrayList<>(List.of("§7Search by item, NPC name", "§7or player."));
		if (active) lore.add("§7Current: §f" + view.query);
		lore.add("");
		lore.add("§eLeft: Enter search term");
		if (active) lore.add("§eRight: Clear search");
		gui.setSlot(52, Gui.button(active ? Items.SPYGLASS : Items.NAME_TAG, active ? "§eSearch: §f" + view.query : "§eSearch", (Gui.ClickHandler) t -> {
			if (t.isRight && active) {
				view.query = "";
				view.page = 0;
				openMain(player, view);
				return;
			}
			Gui.textInput(player, "§8Search orders", active ? view.query : "", text -> {
				String q = text == null ? "" : text.trim();
				if (q.length() > OrderSearch.MAX_LENGTH) q = q.substring(0, OrderSearch.MAX_LENGTH);
				view.query = q;
				view.page = 0;
				openMain(player, view);
			}, () -> openMain(player, view));
		}, lore.toArray(String[]::new)));
	}

	static List<Row> rows(OrderBook book, ServerPlayer viewer, View view) {
		long now = book.now();
		UUID id = viewer.getUUID();
		Map<Item, Integer> pristine = new HashMap<>();
		for (int i = 0; i < dev.smpeconomy.core.Items.MAIN_SIZE; i++) {
			ItemStack s = viewer.getInventory().getItem(i);
			if (!s.isEmpty() && dev.smpeconomy.core.Items.isPristine(s)) pristine.merge(s.getItem(), s.getCount(), Integer::sum);
		}
		String q = OrderSearch.normalize(view.query);
		List<Entry> players = new ArrayList<>();
		List<Entry> npcs = new ArrayList<>();
		for (Order o : view.cache.get(book, id, view.sort, view.playersFirst)) {
			if (o.expiresAt <= now || o.open() <= 0) continue;
			Optional<Item> type = o.itemType();
			if (type.isEmpty()) continue;
			if (o.npc ? view.filter == Filter.PLAYERS : view.filter == Filter.NPC) continue;
			if (!OrderSearch.matches(o, type.get(), q)) continue;
			int have = o.isOwner(id) ? 0 : o.npc ? pristine.getOrDefault(type.get(), 0) : book.countDeliverable(viewer, o);
			if (view.filter == Filter.DELIVERABLE && have <= 0) continue;
			(o.npc && view.playersFirst ? npcs : players).add(new Entry(o, have));
		}
		if (!view.playersFirst) return new ArrayList<>(players);
		List<Row> rows = new ArrayList<>(players.size() + npcs.size() + 2);
		if (view.filter != Filter.NPC) {
			rows.add(new Header(true, players.size()));
			rows.addAll(players);
		}
		if (view.filter != Filter.PLAYERS) {
			rows.add(new Header(false, npcs.size()));
			rows.addAll(npcs);
		}
		return rows;
	}

	private static GuiElementBuilder rowIcon(SimpleGui gui, ServerPlayer player, View view, Gui.Pager<Row> pager, Row row) {
		if (row instanceof Header h) return headerIcon(player, view, h);
		Entry e = (Entry) row;
		Order o = e.order();
		long id = o.id;
		String itemId = o.item;
		OrderBook book = book();
		int quick = quickCount(book, player, o, e.have());
		Terms quickTerms = quick > 0 ? book.terms(player.getUUID(), o, quick) : null;
		GuiElementBuilder b = orderIcon(book, player, o, true, e.have(), quick, quickTerms);
		return b.setCallback((index, type, action, g) -> {
			if (!Gui.acceptClick(player, index, type)) return;
			if (type.shift && quickTerms != null) {
				// "Deliver all" right from the overview, on the displayed terms; then redraw for new prices and
				// amounts
				DeliverResult r = book().deliver(player, id, quick, quickTerms);
				report(player, itemId, r);
				view.page = pager.page();
				if (Gui.canOpen(player)) pager.refresh();
				return;
			}
			Gui.click(player);
			view.page = pager.page();
			openDeliver(player, id, () -> openMain(player, view));
		});
	}

	static int quickCount(OrderBook book, ServerPlayer player, Order o, int have) {
		if (have <= 0 || o.isOwner(player.getUUID()) || book.deliveryBlocked(player) || o.expiresAt <= book.now()) return 0;
		long unit = book.unitPrice(player.getUUID(), o);
		if (unit <= 0) return 0;
		long max = Math.min(have, o.open());
		if (!o.board) max = Math.min(max, o.escrow / unit);
		return (int) Math.max(0, max);
	}

	private static GuiElementBuilder headerIcon(ServerPlayer player, View view, Header h) {
		OrderBook book = book();
		List<String> lore = new ArrayList<>();
		String name;
		Item icon;
		if (h.players()) {
			icon = Items.PLAYER_HEAD;
			name = "§a§lPlayer Orders §7(" + Msg.number(h.count()) + ")";
			lore.add("§7Player orders always come first –");
			lore.add("§7sorted by: " + view.sort.label + ".");
			lore.add("§7Delivered items go to the player.");
			if (book.config().deliveryFeePercent > 0) lore.add("§8Delivery fee: " + percent(book.config().deliveryFeePercent));
			if (h.count() == 0) {
				lore.add("");
				lore.add(view.query.isBlank() && view.filter != Filter.DELIVERABLE ? "§8None right now – create one yourself!" : "§8No matches.");
			}
		} else {
			icon = Items.VILLAGER_SPAWN_EGG;
			name = "§e§lNPC Orders §7(" + Msg.number(h.count()) + ")";
			NpcBoard board = book.board();
			OrdersConfig cfg = book.config();
			lore.add("§7NPCs buy almost anything /sell");
			lore.add("§7takes – often above the /sell value.");
			lore.add("§7Price = /sell base value × demand:");
			lore.add("§7every delivery lowers the demand");
			lore.add("§7(supply), it recovers over time.");
			lore.add("§7Breaking News changes the prices.");
			lore.add("§8Trader Rank and Market Day don't");
			lore.add("§8count here – every order compares");
			lore.add("§8itself to your /sell price.");
			if (cfg.boardDailyBonusPerPlayer > 0) {
				lore.add("");
				lore.add("§7Your daily bonus above /sell: " + Msg.moneyStr(board.bonusLeft(player.getUUID())) + " §7of "
					+ Msg.moneyStr(cfg.boardDailyBonusPerPlayer));
			}
		}
		lore.add("");
		lore.add("§7Click an order: §fDeliver");
		lore.add("§7Shift-click: §fDeliver everything");
		lore.add("§8Unmodified items only (like /sell).");
		lore.add("");
		Filter target = h.players() ? Filter.PLAYERS : Filter.NPC;
		lore.add(view.filter == target ? "§eClick: Show all orders" : "§eClick: Show only these");
		return Gui.item(icon, name, lore).setCallback((index, type, action, g) -> {
			if (!Gui.acceptClick(player, index, type)) return;
			Gui.click(player);
			view.filter = view.filter == target ? Filter.ALL : target;
			view.page = 0;
			openMain(player, view);
		});
	}

	static GuiElementBuilder orderIcon(ServerPlayer viewer, Order o, boolean forDelivery) {
		OrderBook book = book();
		int have = o.isOwner(viewer.getUUID()) ? 0 : book.countDeliverable(viewer, o);
		return orderIcon(book, viewer, o, forDelivery, have, 0, null);
	}

	static GuiElementBuilder orderIcon(OrderBook book, ServerPlayer viewer, Order o, boolean forDelivery, int have, int quick, Terms quickTerms) {
		Optional<Item> type = o.itemType();
		if (type.isEmpty()) return Gui.item(Items.BARRIER, "§cUnknown item", "§8" + o.item, "§8Order #" + o.id);
		Item item = type.get();
		UUID id = viewer.getUUID();
		String prefix = o.bulk ? "§c§l[" + o.label + "] " : o.npc ? "§e[NPC] " : "§f";
		MutableComponent name = Msg.legacy(prefix).append(Msg.itemName(item).withStyle(o.npc ? ChatFormatting.YELLOW : ChatFormatting.WHITE));
		Optional<ItemStack> template = !o.npc && o.spec != null ? o.specStack() : Optional.empty();
		GuiElementBuilder b = template.isPresent() ? templateIcon(template.get()) : new GuiElementBuilder(item).hideDefaultTooltip();
		b.setName(name);
		b.setCount(Math.max(1, Math.min(o.open(), new ItemStack(item).getMaxStackSize())));
		List<String> lore = new ArrayList<>();
		long unit = book.unitPrice(id, o);
		if (o.board) {
			boardLore(book, viewer, o, item, unit, lore);
		} else {
			lore.add(o.npc ? "§7Buyer: §e" + dev.smpeconomy.core.Treasury.NAME : "§7Buyer: §f" + EconomyService.nameOrUnknown(o.owner));
			lore.add("§7Price per item: " + Msg.moneyStr(o.price));
			if (o.npc) {
				if (unit <= 0) lore.add("§cNot accepting anything right now §8(price cap)");
				else if (unit < o.price) lore.add("§7For you: " + Msg.moneyStr(unit) + " §8(price cap)");
			} else if (book.config().deliveryFeePercent > 0) {
				lore.add("§8Delivery fee: " + percent(book.config().deliveryFeePercent));
			}
		}
		lore.add("§7Delivered: §f" + Msg.number(o.delivered) + "§7/§f" + Msg.number(o.amount));
		long openValue = safeMul(o.open(), o.npc ? unit : o.price);
		lore.add("§7Remaining: §f" + Msg.number(o.open()) + " §8(" + (o.board ? "≈ " : "") + Money.format(openValue) + ")");
		lore.add("§7Expires in: §f" + TimeUtil.formatDuration(o.expiresAt - book.now()));
		if (!o.npc && o.spec != null) lore.add(template.isPresent() ? "§dOnly exactly these properties" : "§cTemplate unreadable – accepts nothing");
		else if (o.allowEnchanted) lore.add("§dAny enchantment §8(no name, no damage)");
		if (forDelivery) {
			lore.add("§7You have: §f" + Msg.number(have) + " §7matching");
			lore.add("");
			if (o.isOwner(id)) {
				lore.add("§8Your own order");
			} else if (have > 0 && unit > 0 && !book.deliveryBlocked(viewer)) {
				lore.add("§eClick: Deliver");
				if (quick > 0 && quickTerms != null) {
					long value = quickTerms.gross() >= 0 ? quickTerms.gross()
						: safeMul(quick, unit) - Math.max(0, quickTerms.feeFor(o, quick, unit));
					// board: the price falls item by item, so the total can drop below /sell even when the first item
					// is above it
					long sellValue = o.board ? safeMul(quick, NpcBoard.sellPrice(id, item)) : 0;
					lore.add("§eShift-click: Deliver all §f(" + Msg.number(quick) + ") §8→ " + Money.format(value)
						+ (sellValue > 0 && value < sellValue ? " §c(/sell: " + Money.format(sellValue) + ")" : ""));
				}
			} else {
				lore.add("§8Click: Details");
			}
		}
		lore.add("§8Order #" + o.id);
		Gui.lore(b, lore.toArray(String[]::new));
		if (o.npc) b.glow();
		return b;
	}

	private static void boardLore(OrderBook book, ServerPlayer viewer, Order o, Item item, long unit, List<String> lore) {
		NpcBoard board = book.board();
		UUID id = viewer.getUUID();
		lore.add("§7Buyer: §e" + o.label + " §8(NPC)");
		if (unit <= 0) {
			lore.add("§cNot accepting anything right now §8(price cap)");
			return;
		}
		NpcBoard.Trend trend = board.trend(o);
		lore.add("§7Price per item: " + Msg.moneyStr(unit) + " " + trend.arrow + " §8(" + trend.label + ")");
		double f = board.factor(o);
		NpcBoard.Payout one = board.payout(id, o, 1);
		long sell = one.sell(); // the VIEWER's /sell price, with rank and market day; unit = one.first()
		lore.add("§7Demand: " + NpcBoard.demandLabel(f) + " §8(" + (sell > 0 ? percentFine((double) unit / sell) + " vs. your /sell price "
			+ Money.format(sell) : "can't be sold with /sell") + ")");
		if (sell > 0 && unit < sell) lore.add("§cBelow your /sell price §8– /sell pays " + Money.format(sell));
		double pressure = board.pressure(o.item);
		if (pressure >= 0.0005) {
			lore.add("§7Supply: §c" + percentFine(1 - Math.min(pressure, Math.max(0, o.demand - book.config().boardDemandFloor)))
				+ " §8(from deliveries, recovering)");
		}
		for (PriceModifiers.Active a : PriceModifiers.active(item)) {
			lore.add((a.current() >= 1 ? "§a▲ " : "§c▼ ") + PriceGuard.percent(a.current()) + " " + a.label());
		}
		if (one.capped()) lore.add("§8Price cap active");
		if (book.config().boardDailyBonusPerPlayer > 0 && board.bonusLeft(id) <= 0 && one.bonusLimited()) {
			lore.add("§cDaily bonus used up §8(your /sell price at most)");
		}
	}

	static void openDeliver(ServerPlayer player, long orderId, Runnable back) {
		if (!Gui.canOpen(player)) return;
		Optional<Order> found = book().get(orderId);
		if (found.isEmpty()) {
			Msg.error(player, Status.NOT_FOUND.message());
			Gui.failSound(player);
			back.run();
			return;
		}
		SimpleGui gui = Gui.chest(player, 3, "§8Deliver – Order #" + orderId);
		renderDeliver(gui, player, orderId, back);
		gui.open();
	}

	private static void renderDeliver(SimpleGui gui, ServerPlayer player, long orderId, Runnable back) {
		Order o = book().get(orderId).orElse(null);
		if (o == null || o.open() <= 0) {
			back.run();
			return;
		}
		for (int i = 0; i < 27; i++) gui.setSlot(i, Gui.filler());
		gui.setSlot(4, orderIcon(player, o, false));
		boolean own = o.isOwner(player.getUUID());
		int have = book().countDeliverable(player, o);
		// displayed terms: the click only delivers if they are still at least as good, otherwise it redraws
		long unit = book().unitPrice(player.getUUID(), o);
		int max = (int) Math.min(Math.min(have, o.open()), unit > 0 ? (o.board ? Integer.MAX_VALUE : o.escrow / unit) : 0);
		if (own) {
			gui.setSlot(13, Gui.item(Items.BARRIER, "§cYour own order", "§7Cancel it under §eMy Orders§7."));
		} else if (book().deliveryBlocked(player)) {
			gui.setSlot(13, Gui.item(Items.BARRIER, "§cCreative or Spectator mode", "§7You can't deliver in", "§7this game mode."));
		} else if (o.expiresAt <= book().now()) {
			gui.setSlot(13, Gui.item(Items.BARRIER, "§cExpired", "§7This order doesn't accept anything anymore."));
		} else if (unit <= 0) {
			gui.setSlot(13, Gui.item(Items.BARRIER, "§cNot accepting right now", "§7The price cap is 0 right now.", "§7Try again later."));
		} else if (max <= 0) {
			if (!o.npc && o.spec != null) {
				gui.setSlot(13, Gui.item(Items.GRAY_DYE, "§7No matching items", "§7You need items with exactly the", "§7properties of the order (above)",
					"§8(no name, no damage)"));
			} else {
				gui.setSlot(13, Gui.item(Items.GRAY_DYE, "§7No matching items", "§7You need unmodified items", "§7of this type in your inventory",
					o.allowEnchanted ? "§8(no name, no damage; enchantments ok)" : "§8(no name, no enchantment, no damage)"));
			}
		} else {
			int[] slots = {10, 11, 12};
			for (int i = 0; i < DELIVER_STEPS.length; i++) {
				int n = DELIVER_STEPS[i];
				if (n > max) {
					gui.setSlot(slots[i], Gui.item(Items.GRAY_STAINED_GLASS_PANE, "§8Deliver " + n, n > have ? "§8Not enough matching items"
						: n > o.open() ? "§8Only " + Msg.number(o.open()) + " left" : "§8Not enough money left in the order"));
					continue;
				}
				gui.setSlot(slots[i], deliverButton(gui, player, o, n, Items.LIME_DYE, "§aDeliver " + n, back));
			}
			gui.setSlot(14, deliverButton(gui, player, o, max, Items.EMERALD, "§a§lDeliver all §7(" + Msg.number(max) + ")", back));
		}
		gui.setSlot(16, Gui.item(Items.CHEST, "§7Your inventory", "§7Matching items: §f" + Msg.number(have), "§7Remaining in order: §f" + Msg.number(o.open())));
		gui.setSlot(18, Gui.backButton(back));
		gui.setSlot(26, Gui.closeButton(gui));
	}

	/**
	 * The click carries the displayed terms. If the price has dropped since (crash, drift, price cap, another
	 * delivery to the board) or the fee has risen, it delivers nothing ({@link Status#PRICE_CHANGED}) and the window
	 * shows the new values.
	 */
	private static GuiElementBuilder deliverButton(SimpleGui gui, ServerPlayer player, Order o, int n, Item icon, String name, Runnable back) {
		Terms terms = book().terms(player.getUUID(), o, n);
		long unit = terms.unit();
		List<String> lore = new ArrayList<>();
		lore.add("§7Amount: §f" + Msg.number(n));
		if (o.board) {
			NpcBoard.Payout p = book().boardPayout(player.getUUID(), o, n);
			lore.add("§7You get: " + Msg.moneyStr(p.total()));
			if (n > 1) lore.add("§8Ø " + Money.format(p.average()) + " per item (price drops with every delivery)");
			long sellValue = p.sellValue();
			if (p.count() > 0 && p.sell() > 0 && sellValue < Long.MAX_VALUE) {
				lore.add("§8Via /sell: " + Money.format(sellValue) + " §8(board " + percentFine((double) p.total() / sellValue) + ")");
				if (p.belowSell()) lore.add("§cLess than /sell!");
			}
			if (p.bonusLimited()) lore.add("§8Daily bonus capped – rest at your /sell price");
		} else {
			long gross = safeMul(n, unit);
			long fee = Math.max(0, terms.feeFor(o, n, unit)); // = book().feeFor at render time, exactly what deliver() checks
			lore.add("§7You get: " + Msg.moneyStr(gross - fee));
			if (fee > 0) lore.add("§8Fee: " + Money.format(fee));
		}
		lore.add("");
		lore.add(o.npc ? "§8The items go to the NPC." : "§8The items go to the buyer.");
		lore.add("§eClick: Deliver");
		long id = o.id;
		String itemId = o.item;
		return Gui.button(icon, name, () -> {
			DeliverResult r = book().deliver(player, id, n, terms);
			report(player, itemId, r);
			if (Gui.canOpen(player)) renderDeliver(gui, player, id, back);
		}, lore.toArray(String[]::new));
	}

	static void report(ServerPlayer player, String itemId, DeliverResult r) {
		if (!r.status().ok()) {
			Msg.error(player, r.status().message());
			Gui.failSound(player);
			return;
		}
		MutableComponent line = Msg.legacy("§aDelivered: §f" + Msg.number(r.count()) + "× ");
		line.append(OrderBook.itemComponent(itemId).copy().withStyle(ChatFormatting.WHITE));
		String extra = r.fee() > 0 ? " §8(fee " + Money.format(r.fee()) + ")" : "";
		if (r.fee() <= 0 && r.count() > 1 && r.unitPrice() != r.average()) {
			extra = " §8(Ø " + Money.format(r.average()) + "/item" + (r.bonus() > 0 ? ", bonus " + Money.format(r.bonus()) : "") + ")";
		} else if (r.bonus() > 0) {
			extra = " §8(bonus " + Money.format(r.bonus()) + ")";
		}
		line.append(Msg.legacy(" §afor " + Msg.moneyStr(r.net()) + extra + (r.completed() ? " §a– Order complete!" : "")));
		Msg.send(player, Msg.prefixed(line));
		Msg.actionBar(player, "§a+" + Money.format(r.net()) + " §7for §f" + Msg.number(r.count()) + "× delivered");
		Gui.successSound(player);
	}

	static void openMine(ServerPlayer player, int page) {
		if (!Gui.canOpen(player)) return;
		SimpleGui gui = Gui.chest(player, 6, TITLE_MINE);
		List<Gui.Pager<Order>> holder = new ArrayList<>(1);
		Gui.Pager<Order> pager = new Gui.Pager<>(gui, () -> book().ownedBy(player.getUUID()), o -> {
			GuiElementBuilder b = orderIcon(player, o, false);
			Gui.lore(b, "§7Reserved: " + Msg.moneyStr(o.escrow), "", "§cClick: Cancel §8(remainder refunded)");
			return b.setCallback((index, type, action, g) -> {
				if (!Gui.acceptClick(player, index, type)) return;
				Gui.click(player);
				int back = holder.get(0).page();
				confirmCancel(player, o.id, () -> openMine(player, back));
			});
		});
		holder.add(pager);
		gui.setSlot(45, Gui.backButton(() -> openMain(player)));
		gui.setSlot(46, Gui.button(Items.BARREL, "§eClaim Chest", () -> openClaims(player, 0),
			"§7Delivered items: §f" + Msg.number(book().claimTotal(player.getUUID())), "", "§eClick: Open"));
		gui.setSlot(47, Gui.button(Items.WRITABLE_BOOK, "§aNew Order", () -> startDraft(player), "§7Item in your hand as template", "", "§eClick: Create"));
		gui.setSlot(53, Gui.closeButton(gui));
		pager.show(page);
		gui.open();
	}

	static void confirmCancel(ServerPlayer player, long orderId, Runnable back) {
		Optional<Order> found = book().get(orderId);
		if (found.isEmpty() || !found.get().isOwner(player.getUUID())) {
			Msg.error(player, found.isEmpty() ? Status.NOT_FOUND.message() : Status.NOT_OWNER.message());
			back.run();
			return;
		}
		Order o = found.get();
		GuiElementBuilder preview = orderIcon(player, o, false);
		Gui.lore(preview, "", "§7Refund: " + Msg.moneyStr(o.escrow), "§7Items already delivered stay", "§7in your Claim Chest.");
		Gui.confirm(player, TITLE_CANCEL, preview, () -> {
			CancelResult r = book().cancel(player.getUUID(), orderId, false);
			if (r.status().ok()) {
				Msg.success(player, "Order §f#" + orderId + " §acancelled. Refunded: " + Msg.moneyStr(r.refunded()) + "§a.");
				Gui.successSound(player);
			} else {
				Msg.error(player, r.status().message());
				Gui.failSound(player);
			}
			back.run();
		}, back);
	}

	static void openClaims(ServerPlayer player, int page) {
		if (!Gui.canOpen(player)) return;
		SimpleGui gui = Gui.chest(player, 6, TITLE_CLAIMS);
		List<Gui.Pager<Claim>> holder = new ArrayList<>(1);
		Gui.Pager<Claim> pager = new Gui.Pager<>(gui, () -> book().claims(player.getUUID()), c -> claimIcon(c).setCallback((index, type, action, g) -> {
			if (!Gui.acceptClick(player, index, type)) return;
			ClaimResult r = book().claim(player, c.item, c.nbt, Long.MAX_VALUE);
			reportClaim(player, r);
			holder.get(0).refresh();
			renderClaimAll(gui, player, holder.get(0));
		}));
		holder.add(pager);
		gui.setSlot(45, Gui.backButton(() -> openMain(player)));
		renderClaimAll(gui, player, pager);
		gui.setSlot(53, Gui.closeButton(gui));
		pager.show(page);
		gui.open();
	}

	private static void renderClaimAll(SimpleGui gui, ServerPlayer player, Gui.Pager<Claim> pager) {
		long total = book().claimTotal(player.getUUID());
		gui.setSlot(47, Gui.button(Items.HOPPER, "§a§lClaim all", () -> {
			ClaimResult r = book().claimAll(player);
			reportClaim(player, r);
			pager.refresh();
			renderClaimAll(gui, player, pager);
		}, "§7In the chest: §f" + Msg.number(total), "§7Only as much as fits in your inventory.", "", "§eClick: Claim"));
	}

	private static GuiElementBuilder claimIcon(Claim c) {
		Optional<ItemStack> proto = OrderBook.prototype(c);
		if (proto.isEmpty()) return Gui.item(Items.BARRIER, "§cBroken entry", "§7" + c.item + " ×" + c.count, "§8Please tell an admin");
		ItemStack shown = proto.get().copyWithCount((int) Math.max(1, Math.min(c.count, proto.get().getMaxStackSize())));
		return Gui.display(shown, "", "§7Amount: §f" + Msg.number(c.count), "§8Only as much as fits in your inventory.", "§eClick: Claim");
	}

	static void reportClaim(ServerPlayer player, ClaimResult r) {
		if (r.status().ok()) {
			Msg.success(player, "Claimed: §f" + Msg.number(r.given()) + " items§a." + (r.left() > 0 ? " §7Still in the chest: §f" + Msg.number(r.left()) : ""));
			Gui.successSound(player);
		} else {
			Msg.error(player, r.status() == Status.NOT_FOUND ? "Your Claim Chest is empty." : r.status().message());
			Gui.failSound(player);
		}
	}

	enum Mode {
		EXACT(Items.TARGET, "§dExactly like template", "§7Only items with exactly these", "§7properties (enchantment, potion …),", "§7without a name and without damage."),
		PRISTINE(Items.BOOK, "§fUnmodified only", "§7Only unmodified items of this type", "§7(no enchantment, no name,", "§7no damage)."),
		ANY_ENCHANT(Items.ENCHANTED_BOOK, "§dAny enchantment", "§7Enchanted items of this type too,", "§7no matter which enchantment", "§7(without a name and without damage).");

		final Item icon;
		final String label;
		final String[] lore;

		Mode(Item icon, String label, String... lore) {
			this.icon = icon;
			this.label = label;
			this.lore = lore;
		}
	}

	static final class Draft {
		final Item item;
		final ItemStack template;
		int amount;
		long price;
		Mode mode;

		Draft(Item item, ItemStack template, int amount, long price, Mode mode) {
			this.item = item;
			this.template = template;
			this.amount = amount;
			this.price = price;
			this.mode = mode;
		}

		List<Mode> modes() {
			List<Mode> list = new ArrayList<>();
			if (template != null) list.add(Mode.EXACT);
			if (!OrderBook.needsTemplate(item)) {
				list.add(Mode.PRISTINE);
				list.add(Mode.ANY_ENCHANT);
			}
			return list;
		}

		ItemStack orderTemplate() {
			return mode == Mode.EXACT ? template : null;
		}

		boolean allowEnchanted() {
			return mode == Mode.ANY_ENCHANT;
		}
	}

	static void startDraft(ServerPlayer player) {
		ItemStack hand = player.getMainHandItem();
		if (hand.isEmpty()) {
			Msg.error(player, "Hold the item you want in your hand – or use §e/orders create <item> <amount> <price>§c.");
			Gui.failSound(player);
			return;
		}
		Item item = hand.getItem();
		ItemStack template = OrderBook.templateOf(hand);
		Status st = OrderBook.checkTemplate(item, template);
		if (!st.ok()) {
			Msg.error(player, createMessage(st));
			Gui.failSound(player);
			return;
		}
		long price = PriceGuard.effectiveSellPrice(player, item).orElse(100);
		int amount = Math.min(64, new ItemStack(item).getMaxStackSize());
		openDraft(player, new Draft(item, template, Math.max(1, amount), Math.max(1, price), template != null ? Mode.EXACT : Mode.PRISTINE));
	}

	static void openDraft(ServerPlayer player, Draft d) {
		if (!Gui.canOpen(player)) return;
		SimpleGui gui = Gui.chest(player, 5, TITLE_DRAFT);
		renderDraft(gui, player, d);
		gui.open();
	}

	private static GuiElementBuilder orderedItem(Item item, ItemStack template) {
		GuiElementBuilder b = template != null ? templateIcon(template) : new GuiElementBuilder(item).hideDefaultTooltip();
		return b.setName(Msg.legacy("§f").append(Msg.itemName(item)));
	}

	private static void renderDraft(SimpleGui gui, ServerPlayer player, Draft d) {
		OrdersConfig cfg = book().config();
		for (int i = 0; i < 45; i++) gui.setSlot(i, Gui.filler());
		long total = safeMul(d.amount, d.price);
		OptionalLong sell = PriceGuard.effectiveSellPrice(player, d.item);
		GuiElementBuilder preview = orderedItem(d.item, d.orderTemplate());
		preview.setCount(Math.max(1, Math.min(d.amount, new ItemStack(d.item).getMaxStackSize())));
		Gui.lore(preview, "§7Amount: §f" + Msg.number(d.amount), "§7Price per item: " + Msg.moneyStr(d.price), "§7Total (reserved): " + Msg.moneyStr(total),
			"§7Your balance: " + Msg.moneyStr(EconomyService.balance(player.getUUID())), "§7Accepts: " + d.mode.label,
			sell.isPresent() ? "§8/sell value: " + Money.format(sell.getAsLong()) + " per item" : "§8Can't be sold with /sell");
		gui.setSlot(4, preview);
		int[] deltas = {-64, -16, -1, 0, 1, 16, 64};
		for (int i = 0; i < deltas.length; i++) {
			int delta = deltas[i];
			int slot = 10 + i;
			if (delta == 0) {
				gui.setSlot(slot, Gui.button(Items.PAPER, "§eAmount: §f" + Msg.number(d.amount), () -> Gui.textInput(player, "§8Enter amount", Integer.toString(d.amount), text -> {
					try {
						int v = Integer.parseInt(text.replace(".", "").replace(",", "").trim());
						if (v < 1 || v > cfg.maxAmountPerOrder) throw new NumberFormatException();
						d.amount = v;
					} catch (NumberFormatException e) {
						Msg.error(player, "Invalid amount (1–" + Msg.number(cfg.maxAmountPerOrder) + ").");
					}
					openDraft(player, d);
				}, () -> openDraft(player, d)), "§eClick: Type amount"));
				continue;
			}
			gui.setSlot(slot, Gui.button(delta > 0 ? Items.LIME_STAINED_GLASS_PANE : Items.RED_STAINED_GLASS_PANE, (delta > 0 ? "§a+" : "§c") + delta, () -> {
				long v = (long) d.amount + delta;
				d.amount = (int) Math.max(1, Math.min(cfg.maxAmountPerOrder, v));
				renderDraft(gui, player, d);
			}, "§7Amount: §f" + Msg.number(d.amount)));
		}
		gui.setSlot(19, Gui.button(Items.RED_STAINED_GLASS_PANE, "§cPrice -10%", () -> {
			d.price = Math.max(1, d.price - Math.max(1, d.price / 10));
			renderDraft(gui, player, d);
		}, "§7Price: " + Msg.moneyStr(d.price)));
		gui.setSlot(21, Gui.button(Items.GOLD_INGOT, "§ePrice: " + Msg.moneyStr(d.price), () -> Gui.textInput(player, "§8Price per item", Money.format(d.price), text -> {
			OptionalLong v = Money.parse(text);
			if (v.isPresent()) d.price = v.getAsLong();
			else Msg.error(player, "Invalid price. Examples: §e5§c, §e2.5k§c, §e0.10");
			openDraft(player, d);
		}, () -> openDraft(player, d)), "§7Price per item", "", "§eClick: Type price"));
		gui.setSlot(23, Gui.button(Items.LIME_STAINED_GLASS_PANE, "§aPrice +10%", () -> {
			d.price = Math.min(Money.MAX, d.price + Math.max(1, d.price / 10));
			renderDraft(gui, player, d);
		}, "§7Price: " + Msg.moneyStr(d.price)));
		List<Mode> modes = d.modes();
		if (!modes.contains(d.mode)) d.mode = modes.get(0);
		List<String> modeLore = new ArrayList<>(List.of(d.mode.lore));
		if (modes.size() > 1) {
			modeLore.add("");
			modeLore.add("§eClick: Switch");
			gui.setSlot(25, Gui.button(d.mode.icon, "§7Accepts: " + d.mode.label, () -> {
				d.mode = modes.get((modes.indexOf(d.mode) + 1) % modes.size());
				renderDraft(gui, player, d);
			}, modeLore.toArray(String[]::new)));
		} else {
			gui.setSlot(25, Gui.item(d.mode.icon, "§7Accepts: " + d.mode.label, modeLore));
		}
		gui.setSlot(31, Gui.button(Items.EMERALD, "§a§lCreate Order",
			() -> confirmCreate(player, d.item, d.orderTemplate(), d.amount, d.price, d.allowEnchanted(), () -> openDraft(player, d)),
			"§7Total: " + Msg.moneyStr(total), "§7is reserved right away.", "", "§eClick: Continue"));
		gui.setSlot(36, Gui.backButton(() -> openMain(player)));
		gui.setSlot(44, Gui.closeButton(gui));
	}

	/**
	 * Validates up front and again on confirm; {@link OrderBook#createPlayerOrder} books atomically. {@code back}
	 * (nullable) reopens the previous menu on cancel.
	 */
	static void confirmCreate(ServerPlayer player, Item item, ItemStack template, int amount, long price, boolean allowEnchanted, Runnable back) {
		OrdersConfig cfg = book().config();
		UUID id = player.getUUID();
		ItemStack spec = OrderBook.templateOf(template);
		Status pre = precheck(id, item, spec, amount, price);
		if (pre != Status.OK) {
			Msg.error(player, createMessage(pre));
			Gui.failSound(player);
			if (back != null) back.run();
			return;
		}
		if (!Gui.canOpen(player)) return;
		long total = safeMul(amount, price);
		long balance = EconomyService.balance(id);
		GuiElementBuilder preview = orderedItem(item, spec);
		preview.setCount(Math.max(1, Math.min(amount, new ItemStack(item).getMaxStackSize())));
		List<String> lore = new ArrayList<>(List.of(
			"§7Amount: §f" + Msg.number(amount),
			"§7Price per item: " + Msg.moneyStr(price),
			"§7Total (reserved): " + Msg.moneyStr(total),
			"§7Balance: " + Msg.moneyStr(balance) + " §8→ " + Money.format(balance - total)));
		Mode mode = spec != null ? Mode.EXACT : allowEnchanted ? Mode.ANY_ENCHANT : Mode.PRISTINE;
		lore.add("§7Accepts: " + mode.label);
		lore.addAll(List.of(mode.lore));
		lore.add("");
		lore.add("§8Runs for " + cfg.expiryDays + " days. The rest is");
		lore.add("§8refunded on cancel or expiry.");
		if (cfg.deliveryFeePercent > 0) lore.add("§8Sellers pay a " + percent(cfg.deliveryFeePercent) + " fee.");
		Gui.lore(preview, lore.toArray(String[]::new));
		Gui.confirm(player, TITLE_CREATE, preview, () -> {
			CreateResult r = book().createPlayerOrder(id, item, spec, amount, price, allowEnchanted);
			if (!r.status().ok()) {
				Msg.error(player, createMessage(r.status()));
				Gui.failSound(player);
				return;
			}
			Order o = r.order();
			Msg.send(player, Msg.prefixed(Msg.legacy("§aOrder §f#" + o.id + " §acreated: §f" + Msg.number(amount) + "× ")
				.append(Msg.itemName(item).withStyle(ChatFormatting.WHITE))
				.append(Msg.legacy((o.spec != null ? " §d(exactly like template)" : "") + " §7at " + Msg.moneyStr(price) + " each – " + Msg.moneyStr(total) + " reserved."))));
			Gui.successSound(player);
		}, back);
	}

	static String createMessage(Status s) {
		OrdersConfig cfg = book().config();
		return switch (s) {
			case INVALID_AMOUNT -> "Invalid amount (1–" + Msg.number(cfg.maxAmountPerOrder) + ").";
			case LIMIT -> "You already have " + cfg.maxOrdersPerPlayer + " active orders (maximum).";
			case TOO_EXPENSIVE -> "The total is too high (max " + Money.format(Money.MAX) + ").";
			case NEEDS_TEMPLATE -> "This item only exists with properties (enchantment, potion …). Hold a matching one in your "
				+ "hand: §e/orders §c→ §eNew Order§c.";
			default -> s.message();
		};
	}

	static Status precheck(UUID player, Item item, ItemStack template, int amount, long price) {
		OrdersConfig cfg = book().config();
		if (!book().ready()) return Status.NOT_READY;
		if (item == null || item == Items.AIR || new ItemStack(item).isEmpty()) return Status.INVALID_ITEM;
		Status ts = OrderBook.checkTemplate(item, template);
		if (!ts.ok()) return ts;
		if (amount < 1 || amount > cfg.maxAmountPerOrder) return Status.INVALID_AMOUNT;
		if (!Money.isValidAmount(price)) return Status.INVALID_PRICE;
		long total = safeMul(amount, price);
		if (!Money.isValidAmount(total)) return Status.TOO_EXPENSIVE;
		if (book().countOwned(player) >= cfg.maxOrdersPerPlayer) return Status.LIMIT;
		if (!EconomyService.has(player, total)) return Status.INSUFFICIENT_FUNDS;
		return Status.OK;
	}

	/**
	 * Real stack of the template with a vanilla tooltip only for its properties; attributes, durability etc. hidden.
	 * The caller sets name and lore.
	 */
	static GuiElementBuilder templateIcon(ItemStack template) {
		ItemStack shown = template.copyWithCount(1);
		TooltipDisplay display = TooltipDisplay.DEFAULT;
		for (TypedDataComponent<?> c : shown.getComponents()) {
			DataComponentType<?> t = c.type();
			if (!OrderBook.isTemplateComponent(t) && t != DataComponents.CUSTOM_NAME && t != DataComponents.ITEM_NAME && t != DataComponents.LORE) {
				display = display.withHidden(t, true);
			}
		}
		shown.set(DataComponents.TOOLTIP_DISPLAY, display);
		return new GuiElementBuilder(shown);
	}

	/** Overflow gives {@code Long.MAX_VALUE}. Display and pre-check only; booking uses multiplyExact. */
	static long safeMul(long a, long b) {
		try {
			return Math.multiplyExact(a, b);
		} catch (ArithmeticException e) {
			return Long.MAX_VALUE;
		}
	}

	static String percentFine(double factor) {
		double pct = (factor - 1.0) * 100;
		if (Math.abs(pct) >= 10) return PriceGuard.percent(factor);
		double r = Math.round(pct * 10) / 10.0;
		if (r == 0) return "±0%";
		String v = r == Math.rint(r) ? Long.toString((long) Math.abs(r)) : String.format(java.util.Locale.ROOT, "%.1f", Math.abs(r));
		return (r > 0 ? "+" : "-") + v + "%";
	}

	static String percent(double pct) {
		return (pct == Math.rint(pct) ? Long.toString((long) pct) : String.format(java.util.Locale.ROOT, "%.1f", pct)) + "%";
	}
}
