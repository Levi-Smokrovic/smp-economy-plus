package dev.smpeconomy.features.shop;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Perms;

/**
 * Buy and sell dialog, display-only slots. The action button goes through {@link ShopService}, which rechecks
 * everything, and never trades at a worse unit price or a larger amount than the button showed; inventory
 * space freed since then only raises the amount at the next render. A button shown as blocked never trades on
 * click, even if the state has changed since; it only re-renders.
 */
public final class TradeDialog {
	public enum Mode { BUY, SELL }

	static final int PREVIEW = 4;
	static final int[] PRESETS = {1, 8, 16, 32, 64};
	static final int PRESET_FIRST = 11;
	static final int[] STACKS = {2, 3, 4, 6, 9, 18, 36};
	static final int STACK_FIRST = 19;
	static final int[] ADJUST = {-64, -8, -1, 0, 1, 8, 64};
	static final int ADJUST_FIRST = 28;
	static final int MAX_BUTTON = 31;
	static final int INFO_SPACE = 38;
	static final int INFO_BALANCE = 40;
	static final int INFO_PRICE = 42;
	static final int BACK = 45;
	static final int MODE = 47;
	static final int ACTION = 49;
	static final int FAVORITE = 51;
	static final int CLOSE = 53;

	private final ServerPlayer player;
	private final Item item;
	private final Mode mode;
	private final Runnable back;
	private final SimpleGui gui;
	private int selected;
	/** Guards against buying dearer or selling cheaper than displayed. */
	private long shownUnit = -1;
	/**
	 * Trades never exceed this, even if inventory space opened up since the render; a click must not cost more
	 * than the button promised.
	 */
	private int shownAmount;
	/** Null when trading is possible. */
	private String blockedReason;

	private TradeDialog(ServerPlayer player, Item item, Mode mode, int selected, Runnable back) {
		this.player = player;
		this.item = item;
		this.mode = mode;
		this.selected = Math.max(1, selected);
		this.back = back;
		this.gui = Gui.chest(player, 6, mode == Mode.BUY ? "§8Buy" : "§8Sell");
		this.gui.setTitle(Msg.legacy(mode == Mode.BUY ? "§8Buy » " : "§8Sell » ").append(Msg.itemName(item).withStyle(ChatFormatting.DARK_GRAY)));
	}

	/** {@code initial} is capped at the stock when selling. */
	public static void open(ServerPlayer player, Item item, Mode mode, int initial, Runnable back) {
		if (!Gui.canOpen(player)) return;
		TradeDialog d = new TradeDialog(player, item, mode, initial, back);
		d.render();
		d.gui.open();
	}

	/** min(purchase limit, space, affordable), never negative. */
	static int maxBuyable(int limit, int fit, int affordable) {
		return Math.max(0, Math.min(limit, Math.min(fit, affordable)));
	}

	static int clampSelection(long value, int cap) {
		return (int) Math.max(1, Math.min(Math.max(1, cap), value));
	}

	static int stackAmount(int stacks, int maxStack) {
		return stacks * Math.max(1, maxStack);
	}

	private int cap() {
		return mode == Mode.BUY ? ShopService.maxPerPurchase(item) : ShopService.sellableCount(player, item);
	}

	private void render() {
		UUID id = player.getUUID();
		long balance = EconomyService.balance(id);
		int maxStack = Math.max(1, item.getDefaultMaxStackSize());
		int cap = cap();
		selected = clampSelection(selected, cap);
		boolean buy = mode == Mode.BUY;

		long unit;
		int fit = 0;
		int have = 0;
		int changed = 0;
		int effective; // Amount actually traded
		if (buy) {
			unit = ShopService.unitBuyPrice(id, item).orElse(-1);
			fit = ShopService.fitCount(player, item);
			effective = Math.min(selected, fit);
		} else {
			unit = ShopService.unitSellPrice(id, item).orElse(-1);
			have = ShopService.sellableCount(player, item);
			changed = Items.count(player, s -> s.is(item) && !Items.isPristine(s));
			effective = Math.min(selected, have);
		}
		shownUnit = unit;
		shownAmount = Math.max(0, effective);
		long total = unit > 0 ? ShopService.total(unit, effective) : -1;
		long after = total < 0 ? balance : buy ? balance - total : balance + total;

		for (int i = 0; i < 54; i++) gui.setSlot(i, Gui.border());

		List<String> summary = summary(balance, unit, fit, have, changed, effective, total, after, cap);
		GuiElementBuilder preview = new GuiElementBuilder(item).setName(ShopGui.displayName(id, item)).hideDefaultTooltip()
			.setCount(Math.max(1, Math.min(selected, Math.min(64, maxStack))));
		Gui.lore(preview, summary.toArray(String[]::new));
		gui.setSlot(PREVIEW, preview);

		for (int i = 0; i < PRESETS.length; i++) gui.setSlot(PRESET_FIRST + i, amountButton(PRESETS[i], PRESETS[i] + "×", item, Math.min(PRESETS[i], 64), cap, unit, fit, have, balance));
		gui.setSlot(10, Gui.filler());
		gui.setSlot(16, Gui.filler());
		for (int i = 0; i < STACKS.length; i++) {
			int amount = stackAmount(STACKS[i], maxStack);
			gui.setSlot(STACK_FIRST + i, amountButton(amount, STACKS[i] + " stacks §7(" + Msg.number(amount) + ")", net.minecraft.world.item.Items.CHEST,
				STACKS[i], cap, unit, fit, have, balance));
		}
		for (int i = 0; i < ADJUST.length; i++) {
			int delta = ADJUST[i];
			int slot = ADJUST_FIRST + i;
			if (delta == 0) {
				gui.setSlot(slot, maxButton(cap, fit, have, unit, balance));
				continue;
			}
			String label = (delta > 0 ? "§a+" : "§c") + delta;
			gui.setSlot(slot, Gui.button(delta > 0 ? net.minecraft.world.item.Items.LIME_STAINED_GLASS_PANE : net.minecraft.world.item.Items.RED_STAINED_GLASS_PANE,
				label, () -> {
					selected = clampSelection((long) selected + delta, cap());
					render();
				}, "§7Amount: §f" + Msg.number(selected) + " §8→ §f" + Msg.number(clampSelection((long) selected + delta, cap))));
		}

		if (buy) {
			int limit = cap;
			gui.setSlot(INFO_SPACE, Gui.item(net.minecraft.world.item.Items.CHEST, (fit < selected ? "§c" : "§a") + "Fits in inventory: " + Msg.number(fit),
				fit < selected ? "§eYou only get " + Msg.number(Math.max(0, fit)) + " and only pay for those." : "§7Enough space for your selection.",
				"§8Buy limit: " + Msg.number(limit) + " per purchase"));
		} else {
			List<String> lines = new ArrayList<>();
			lines.add("§7Unmodified items in your inventory.");
			if (changed > 0) lines.add("§8" + Msg.number(changed) + " modified (enchanted/renamed/damaged) don't count.");
			lines.add("§8Armor and offhand don't count.");
			gui.setSlot(INFO_SPACE, Gui.item(net.minecraft.world.item.Items.HOPPER, "§7Sellable: §f" + Msg.number(have), lines));
		}
		List<String> money = new ArrayList<>();
		money.add("§7Before: §a" + Money.format(balance));
		if (total >= 0) money.add("§7After: " + (after < 0 ? "§c" : "§a") + Money.format(Math.max(0, after)));
		if (after < 0) money.add("§cYou're short " + Money.format(-after));
		gui.setSlot(INFO_BALANCE, Gui.item(net.minecraft.world.item.Items.GOLD_INGOT, "§6Balance", money));
		gui.setSlot(INFO_PRICE, Gui.item(net.minecraft.world.item.Items.PAPER, "§7Prices", ShopLore.priceLines(id, item, 0)));

		gui.setSlot(BACK, Gui.backButton(back));
		boolean canSwitch = buy
			? ShopLore.sellable(id, item) && ShopConfig.get().sellInShop && Perms.check(player, ShopGui.NODE_SELL) && !ShopService.sellBlocked(player)
			: ShopService.maxPerPurchase(item) > 0;
		if (canSwitch) {
			gui.setSlot(MODE, buy
				? Gui.button(net.minecraft.world.item.Items.HOPPER, "§6» Sell", () -> open(player, item, Mode.SELL, Integer.MAX_VALUE, back),
					"§7Switch to selling")
				: Gui.button(net.minecraft.world.item.Items.EMERALD, "§a» Buy", () -> open(player, item, Mode.BUY, 1, back),
					"§7Switch to buying"));
		}
		gui.setSlot(ACTION, actionButton(unit, fit, have, effective, total, balance, after));
		boolean fav = ShopPlayerData.isFavorite(id, item);
		gui.setSlot(FAVORITE, Gui.button(fav ? net.minecraft.world.item.Items.NETHER_STAR : net.minecraft.world.item.Items.FIREWORK_STAR,
			fav ? "§6★ Favorite" : "§7☆ Not a favorite", () -> {
				ShopGui.toggleFavorite(player, item);
				render();
			}, fav ? "§eClick §7» Remove favorite" : "§eClick §7» Add to favorites"));
		gui.setSlot(CLOSE, Gui.closeButton(gui));
	}

	private List<String> summary(long balance, long unit, int fit, int have, int changed, int effective, long total, long after, int cap) {
		List<String> l = new ArrayList<>();
		boolean buy = mode == Mode.BUY;
		if (buy) {
			l.add("§7Amount: §f" + Msg.number(selected));
			if (unit > 0) l.add("§7Price: §a" + Money.format(unit) + " §8each");
			if (total >= 0) l.add("§7Total: §a" + Money.format(total) + (effective < selected ? " §8(for " + Msg.number(effective) + ")" : ""));
			l.add((fit < selected ? "§c" : "§7") + "Fits in inventory: §f" + Msg.number(fit));
			if (cap <= 0) l.add("§cNo longer available in the shop");
		} else {
			l.add("§7Amount: §f" + Msg.number(effective) + " §8(of " + Msg.number(have) + " sellable)");
			if (unit > 0) l.add("§7Price: §e" + Money.format(unit) + " §8each");
			if (total >= 0) l.add("§7You get: §a" + Money.format(total));
			if (changed > 0) l.add("§8" + Msg.number(changed) + " modified items won't be sold");
		}
		l.add("§7Balance: §a" + Money.format(balance) + " §8→ " + (after < 0 ? "§c-" + Money.format(-after) : "§a" + Money.format(after)));
		return l;
	}

	/** Disabled (gray pane) above the purchase limit or stock. */
	private GuiElementBuilder amountButton(int amount, String label, Item icon, int iconCount, int cap, long unit, int fit, int have, long balance) {
		boolean buy = mode == Mode.BUY;
		boolean enabled = amount <= cap;
		if (!enabled) {
			return Gui.item(net.minecraft.world.item.Items.GRAY_STAINED_GLASS_PANE, "§8" + label.replace("§7", "§8"),
				buy ? "§8Over the buy limit (" + Msg.number(Math.max(0, cap)) + " per purchase)" : "§8You only have " + Msg.number(have) + " to sell.");
		}
		boolean active = amount == selected;
		List<String> lore = new ArrayList<>();
		long total = unit > 0 ? ShopService.total(unit, buy ? Math.min(amount, fit) : amount) : -1;
		if (total >= 0) lore.add((buy ? "§7Cost: §a" : "§7You get: §a") + Money.format(total));
		if (buy && amount > fit) lore.add("§eOnly " + Msg.number(fit) + " fit in your inventory");
		if (buy && total > balance) lore.add("§cToo expensive for your balance");
		lore.add(active ? "§aSelected" : "§eClick §7» Select");
		GuiElementBuilder b = Gui.button(icon, (active ? "§a§l▶ " : "§f") + label, () -> {
			selected = clampSelection(amount, cap());
			render();
		}, lore.toArray(String[]::new));
		b.setCount(Math.max(1, Math.min(64, iconCount)));
		if (active) b.glow();
		return b;
	}

	private GuiElementBuilder maxButton(int cap, int fit, int have, long unit, long balance) {
		if (mode == Mode.BUY) {
			int max = maxBuyable(cap, fit, ShopService.affordable(balance, unit));
			if (max <= 0) {
				String why = cap <= 0 ? "§8Not in the shop" : fit <= 0 ? "§8No space in your inventory" : "§8Not enough money for 1";
				return Gui.item(net.minecraft.world.item.Items.GRAY_STAINED_GLASS_PANE, "§8Maximum", why);
			}
			return Gui.button(net.minecraft.world.item.Items.GOLD_BLOCK, "§e§lMaximum: §f" + Msg.number(max), () -> {
				selected = clampSelection(maxBuyable(cap(), ShopService.fitCount(player, item),
					ShopService.affordable(EconomyService.balance(player.getUUID()), ShopService.unitBuyPrice(player.getUUID(), item).orElse(-1))), cap());
				render();
			}, "§7As many as fit and you can afford", "§8(at most " + Msg.number(cap) + " per purchase)");
		}
		if (have <= 0) return Gui.item(net.minecraft.world.item.Items.GRAY_STAINED_GLASS_PANE, "§8All", "§8Nothing to sell");
		return Gui.button(net.minecraft.world.item.Items.HOPPER, "§e§lAll: §f" + Msg.number(have), () -> {
			selected = clampSelection(ShopService.sellableCount(player, item), cap());
			render();
		}, "§7Sell all unmodified items");
	}

	private GuiElementBuilder actionButton(long unit, int fit, int have, int effective, long total, long balance, long after) {
		boolean buy = mode == Mode.BUY;
		String reason = null;
		if (unit <= 0) reason = buy ? "Not available in the shop" : "Can't be sold";
		else if (buy && fit <= 0) reason = "No space in your inventory";
		else if (buy && after < 0) reason = "Not enough money (" + Money.format(-after) + " short)";
		else if (!buy && have <= 0) reason = "Nothing to sell";
		Runnable action = buy ? this::doBuy : this::doSell;
		blockedReason = reason;
		if (reason != null) {
			// A blocked button never trades: money, space or stock may have appeared since the render. The click shows the
			// current state and the reason.
			return Gui.button(net.minecraft.world.item.Items.BARRIER, buy ? "§c§lCan't buy" : "§c§lCan't sell",
				this::blockedClick, "§c" + reason);
		}
		List<String> lore = new ArrayList<>();
		lore.add("§7Balance after: §a" + Money.format(after));
		if (buy && effective < selected) lore.add("§eOnly " + Msg.number(effective) + " fit in your inventory – you only pay for those.");
		lore.add("");
		lore.add("§eClick §7» " + (buy ? "Buy now" : "Sell now"));
		return Gui.button(buy ? net.minecraft.world.item.Items.LIME_CONCRETE : net.minecraft.world.item.Items.ORANGE_CONCRETE,
			(buy ? "§a§lBuy: §f" : "§6§lSell: §f") + Msg.number(effective) + "× for §a" + Money.format(total), action,
			lore.toArray(String[]::new));
	}

	private void blockedClick() {
		if (gui.isOpen()) render();
		if (blockedReason != null) {
			Gui.failSound(player);
			Msg.error(player, blockedReason + ".");
		} else {
			Msg.info(player, "§eNow possible – please check amount and price and click again.");
		}
	}

	/** 0 means nothing was displayed, so do not trade. */
	static int actionAmount(int selected, int shownAmount) {
		return Math.max(0, Math.min(selected, shownAmount));
	}

	private void doBuy() {
		int amount = actionAmount(selected, shownAmount);
		if (amount <= 0) {
			blockedClick();
			return;
		}
		// Never dearer than displayed, in unit price and amount. If no price was visible, any price counts as changed
		ShopService.BuyResult r = ShopService.buy(player, item, amount, shownUnit > 0 ? shownUnit : 0);
		ShopGui.feedbackBuy(player, item, r);
		if (r.status().ok() && !r.partial() && r.delivered() < selected) {
			// More was selected, but only the displayed amount that fits was bought
			Msg.info(player, "§eOnly " + Msg.number(r.delivered()) + " of " + Msg.number(selected) + " fit in your inventory – you only paid for those.");
		}
		if (gui.isOpen()) render();
	}

	private void doSell() {
		if (!Perms.check(player, ShopGui.NODE_SELL)) {
			Gui.failSound(player);
			Msg.error(player, ShopService.Status.NO_PERMISSION.message());
			return;
		}
		int amount = actionAmount(selected, shownAmount);
		if (amount <= 0) {
			blockedClick();
			return;
		}
		ShopService.SellResult r = ShopService.sell(player, item, amount, shownUnit > 0 ? shownUnit : Long.MAX_VALUE);
		ShopGui.feedbackSell(player, item, r);
		if (gui.isOpen()) render();
	}
}
