package dev.smpeconomy.features.auction;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.gui.SmpAnvilGui;
import dev.smpeconomy.core.gui.SmpGui;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Perms;
import dev.smpeconomy.features.auction.AuctionService.CreateResult;
import dev.smpeconomy.features.auction.AuctionService.Result;

/**
 * "Sell item" button: the same path as {@code /ah sell <total>} without knowing the command. Confirm lists
 * only the stack still in the main hand: same hotbar slot and {@link ItemStack#matches} against the state at
 * click time, otherwise it aborts with no change. All steps are display-only GUIs with no real slots.
 */
final class AuctionSellGui {
	static final int SLOT = 52;
	static final String CONFIRM_TITLE = "§8Confirm Listing";
	static final String HAND_CHANGED = "The item in your main hand changed – nothing was listed. Click »Sell item« again.";
	static final String NO_PERMISSION = "You can't sell items in the Auction House.";
	static final int SLOT_WARNING = 4;
	static final int SLOT_ITEM = 13;
	static final int SLOT_CHANGE_PRICE = 22;
	static final int SLOT_EACH = 23;
	static final int[] CONFIRM_SLOTS = {9, 10, 11};
	static final int[] CANCEL_SLOTS = {15, 16, 17};

	record Held(int slot, ItemStack stack) {
		Held {
			stack = stack.copy();
		}

		@Override
		public ItemStack stack() {
			return stack.copy();
		}

		boolean stillHeld(ServerPlayer player) {
			Inventory inv = player.getInventory();
			return !stack.isEmpty() && inv.getSelectedSlot() == slot && ItemStack.matches(stack, inv.getItem(slot));
		}
	}

	/** {@code handChanged} means nothing was listed and {@code result} does not apply. */
	record Outcome(boolean handChanged, CreateResult result) {
		boolean ok() {
			return !handChanged && result != null && result.ok();
		}
	}

	private AuctionSellGui() {}

	static Held held(ServerPlayer player) {
		Inventory inv = player.getInventory();
		int slot = inv.getSelectedSlot();
		ItemStack hand = inv.getItem(slot);
		return hand.isEmpty() ? null : new Held(slot, hand);
	}

	/** Everything {@code /ah sell} would reject regardless of price. 1 cent is always a valid price. */
	static Result precheck(ServerPlayer player) {
		return AuctionService.checkListFromHand(player, 1);
	}

	static OptionalLong parsePrice(String input) {
		return Money.parse(input);
	}

	/**
	 * No item is copied or replaced: {@link AuctionService#listFromHand} takes the checked stack from the slot in
	 * the same call.
	 */
	static Outcome list(ServerPlayer player, Held held, long price) {
		if (held == null || !held.stillHeld(player)) return new Outcome(true, null);
		return new Outcome(false, AuctionService.listFromHand(player, price));
	}

	/**
	 * {@code back} reopens the origin menu. Without {@code smpeconomy.ah.sell} this is a filler, matching the
	 * missing command.
	 */
	static GuiElementBuilder button(ServerPlayer player, Runnable back) {
		if (!Perms.check(player, AuctionCommands.NODE_SELL)) return Gui.filler();
		AuctionConfig cfg = AuctionConfig.get();
		ItemStack hand = player.getInventory().getSelectedItem();
		List<String> lore = new ArrayList<>();
		lore.add("");
		lore.add("§7How to sell:");
		lore.add("§f1. §7Hold the item in your §fmain hand");
		lore.add("§f2. §7Click here and type the §ftotal price");
		lore.add("§f3. §7Check the summary and confirm");
		lore.add("");
		lore.add(hand.isEmpty() ? "§cYour main hand is empty." : "§7In your hand: §f" + hand.getCount() + "x " + hand.getHoverName().getString());
		lore.add("§7Active listings: §f" + AuctionService.activeCount(player.getUUID()) + "§7/§f" + cfg.maxListings);
		lore.add("§7Tax on sale: §f" + AuctionGui.percent(cfg.taxPercent) + " §8· §7Duration: §f" + cfg.durationHours + "h");
		lore.add("");
		lore.add("§8Command: /ah sell <total price>");
		lore.add("§eClick to sell the item in your hand");
		return Gui.button(Items.EMERALD_BLOCK, "§a§lSell item", () -> start(player, back), lore.toArray(String[]::new)).glow();
	}

	static void start(ServerPlayer player, Runnable back) {
		if (!Perms.check(player, AuctionCommands.NODE_SELL)) {
			Msg.error(player, NO_PERMISSION);
			Gui.failSound(player);
			return;
		}
		Held held = held(player);
		Result pre = held == null ? Result.NO_ITEM : precheck(player);
		if (pre != Result.OK) {
			AuctionCommands.sellFailed(player, pre, AuctionConfig.get());
			return;
		}
		long suggested = AuctionSellConfirm.suggestedTotal(player.getUUID(), held.stack(), AuctionConfig.get().maxPrice);
		openPriceInput(player, held, suggested > 0 ? Money.commandAmount(suggested) : "", back);
	}

	static void openPriceInput(ServerPlayer player, Held held, String initial, Runnable back) {
		if (!Gui.canOpen(player)) return;
		new PriceInput(player, held, initial == null ? "" : initial, back).open();
	}

	private static final class PriceInput extends SmpAnvilGui {
		private final ServerPlayer player;
		private final Held held;
		private final Runnable back;
		private boolean done;

		PriceInput(ServerPlayer player, Held held, String initial, Runnable back) {
			super(player, false);
			this.player = player;
			this.held = held;
			this.back = back;
			int count = held.stack().getCount();
			setTitle(Msg.legacy(count > 1 ? "§8Total price for all " + count : "§8Price for this item"));
			setDefaultInputValue(initial);
			setSlot(0, inputItem(held.stack(), initial, count));
			setSlot(1, Gui.item(Items.BARRIER, "§c§lCancel", "§7Back without listing anything").setCallback((i, t, a, g) -> {
				if (!Gui.acceptClick(player, i, t)) return;
				Gui.click(player);
				close(); // onClosed goes back
			}));
			setSlot(2, nextButton(initial));
		}

		@Override
		public void onInput(String input) {
			setSlot(2, nextButton(input)); // input() sends slot 2 to the client afterwards
		}

		@Override
		protected void onClosed() {
			if (done) return;
			done = true;
			if (Gui.canOpen(player)) back.run();
		}

		/** The vanilla client copies the name of slot 0 into the text field. */
		private static GuiElementBuilder inputItem(ItemStack stack, String initial, int count) {
			List<String> lore = List.of("", "§7Type the §ftotal price §7for " + (count > 1 ? "all §f" + count + " §7items" : "this item") + " above",
				"§7e.g. §f1000§7, §f2.5k§7, §f1m");
			try {
				ItemStack copy = stack.copy();
				AuctionGui.lighten(copy); // Keeps contents and book pages out of the window packet
				copy.remove(DataComponents.LORE);
				return Gui.lore(GuiElementBuilder.from(copy).setName(Component.literal(initial)), lore.toArray(String[]::new));
			} catch (RuntimeException e) {
				SmpEconomy.LOG.warn("[SMPEco] Auction: price field display failed for {}: {}", dev.smpeconomy.core.Items.id(stack.getItem()), e.toString());
				return Gui.lore(new GuiElementBuilder(Items.PAPER).setName(Component.literal(initial)), lore.toArray(String[]::new));
			}
		}

		private GuiElementBuilder nextButton(String input) {
			AuctionConfig cfg = AuctionConfig.get();
			OptionalLong parsed = parsePrice(input);
			GuiElementBuilder b;
			if (parsed.isEmpty()) {
				b = Gui.item(Items.GRAY_DYE, "§7Type the total price", "", "§7Examples: §f1000§7, §f2.5k§7, §f1m",
					"§8The price is for the whole stack.");
			} else if (parsed.getAsLong() > cfg.maxPrice) {
				b = Gui.item(Items.RED_DYE, "§cPrice too high", "", "§7Max §f" + Money.format(cfg.maxPrice) + " §7for the whole stack.");
			} else {
				long price = parsed.getAsLong();
				ItemStack stack = held.stack();
				AuctionSellConfirm.Quote q = AuctionSellConfirm.quote(player.getUUID(), stack, price);
				List<String> lore = new ArrayList<>();
				lore.add("");
				if (q.count() > 1) lore.add("§7Each: §f" + AuctionSellConfirm.unitText(price, q.count()) + " §8× " + q.count());
				for (String w : AuctionSellConfirm.warnings(q)) lore.add("§c⚠ " + w);
				lore.add("");
				lore.add("§eClick to check and confirm");
				b = Gui.item(Items.LIME_DYE, "§a§lNext: " + Money.format(price), lore);
			}
			return b.setCallback((i, t, a, g) -> {
				if (done || !Gui.acceptClick(player, i, t)) return;
				Gui.click(player);
				submit();
			});
		}

		private void submit() {
			AuctionConfig cfg = AuctionConfig.get();
			OptionalLong parsed = parsePrice(getInput());
			if (parsed.isEmpty()) {
				Msg.error(player, "That's not a valid price – type e.g. 1000, 2.5k or 1m (for the whole stack).");
				Gui.failSound(player);
				return;
			}
			long price = parsed.getAsLong();
			Result r = held.stillHeld(player) ? AuctionService.checkListFromHand(player, price) : null;
			if (r == Result.INVALID_PRICE) {
				AuctionCommands.sellFailed(player, r, cfg);
				return;
			}
			done = true;
			close();
			if (r == null) {
				Msg.error(player, HAND_CHANGED);
				Gui.failSound(player);
				if (Gui.canOpen(player)) back.run();
			} else if (r != Result.OK) {
				AuctionCommands.sellFailed(player, r, cfg);
				if (Gui.canOpen(player)) back.run();
			} else {
				openConfirm(player, held, price, back);
			}
		}
	}

	/** Confirm runs at most once, after the close. */
	static void openConfirm(ServerPlayer player, Held held, long price, Runnable back) {
		if (!Gui.canOpen(player)) return;
		AuctionConfig cfg = AuctionConfig.get();
		ItemStack stack = held.stack();
		AuctionSellConfirm.Quote q = AuctionSellConfirm.quote(player.getUUID(), stack, price);
		List<String> warnings = AuctionSellConfirm.warnings(q);
		boolean[] done = {false};
		SimpleGui gui = new SmpGui(MenuType.GENERIC_9x3, player, false) {
			@Override
			protected void onClosed() {
				if (done[0]) return;
				done[0] = true;
				if (Gui.canOpen(player)) back.run();
			}
		};
		gui.setTitle(Msg.legacy(CONFIRM_TITLE));
		for (int i = 0; i < 27; i++) gui.setSlot(i, Gui.filler());

		long tax = AuctionService.taxFor(price);
		String what = q.count() + "x " + stack.getHoverName().getString();
		List<String> lore = new ArrayList<>();
		lore.add("");
		lore.add("§8§m                        ");
		lore.add("§7Total price: " + Msg.moneyStr(price) + (q.count() > 1 ? " §8(whole stack)" : ""));
		if (q.count() > 1) lore.add("§7Each: §f" + AuctionSellConfirm.unitText(price, q.count()) + " §8× " + q.count());
		lore.add("§7Tax on sale (" + AuctionGui.percent(cfg.taxPercent) + "): §c-" + Money.format(tax));
		lore.add("§7You get: " + Msg.moneyStr(price - tax));
		lore.add("§7Expires in: §f" + cfg.durationHours + "h");
		if (!warnings.isEmpty()) {
			lore.add("");
			for (String w : warnings) lore.add("§c⚠ " + w);
		}
		gui.setSlot(SLOT_ITEM, AuctionGui.preview(stack, lore.toArray(String[]::new)));

		long each = AuctionSellConfirm.eachInstead(q, cfg.maxPrice);
		if (!warnings.isEmpty()) {
			List<String> wl = new ArrayList<>();
			wl.add("");
			for (String w : warnings) wl.add("§c⚠ " + w);
			wl.add("");
			wl.add("§7Sure? Then confirm – otherwise change the price.");
			gui.setSlot(SLOT_WARNING, Gui.item(Items.REDSTONE_TORCH, "§c§l⚠ Check the price", wl));
		}

		GuiElementBuilder yes = Gui.item(Items.LIME_STAINED_GLASS_PANE, "§a§lConfirm", "", "§7List §f" + what,
			"§7for " + Msg.moneyStr(price) + " §7in total.").setCallback((i, t, a, g) -> {
				if (done[0] || !Gui.acceptClick(player, i, t)) return;
				done[0] = true;
				Gui.click(player);
				gui.close();
				finish(player, held, price);
				if (Gui.canOpen(player)) back.run();
			});
		GuiElementBuilder no = Gui.item(Items.RED_STAINED_GLASS_PANE, "§c§lCancel", "", "§7Nothing is listed.").setCallback((i, t, a, g) -> {
			if (!Gui.acceptClick(player, i, t)) return;
			Gui.click(player);
			gui.close(); // onClosed goes back
		});
		for (int slot : CONFIRM_SLOTS) gui.setSlot(slot, yes);
		for (int slot : CANCEL_SLOTS) gui.setSlot(slot, no);

		gui.setSlot(SLOT_CHANGE_PRICE, Gui.item(Items.NAME_TAG, "§eChange price", "", "§7Type a different total price.").setCallback((i, t, a, g) -> {
			if (done[0] || !Gui.acceptClick(player, i, t)) return;
			done[0] = true;
			Gui.click(player);
			gui.close();
			openPriceInput(player, held, Money.commandAmount(price), back);
		}));
		if (each > 0) {
			gui.setSlot(SLOT_EACH, Gui.item(Items.GOLD_NUGGET, "§eDid you mean " + Money.format(price) + " each?", "",
				"§7Then the total is §a" + Money.format(each), "§8(" + Money.format(price) + " × " + q.count() + ")", "",
				"§eClick to use " + Money.format(each) + " in total").setCallback((i, t, a, g) -> {
					if (done[0] || !Gui.acceptClick(player, i, t)) return;
					done[0] = true;
					Gui.click(player);
					gui.close();
					openConfirm(player, held, each, back);
				}));
		}
		gui.open();
	}

	private static void finish(ServerPlayer player, Held held, long price) {
		AuctionConfig cfg = AuctionConfig.get();
		if (!Perms.check(player, AuctionCommands.NODE_SELL)) {
			Msg.error(player, NO_PERMISSION);
			Gui.failSound(player);
			return;
		}
		Outcome o = list(player, held, price);
		if (o.handChanged()) {
			Msg.error(player, HAND_CHANGED);
			Gui.failSound(player);
			return;
		}
		if (!o.ok()) {
			AuctionCommands.sellFailed(player, o.result().result(), cfg);
			return;
		}
		AuctionSellConfirm.clear(player.getUUID()); // A pending /ah sell preview is now obsolete
		AuctionCommands.announceListed(player, o.result().listing(), cfg);
	}
}
