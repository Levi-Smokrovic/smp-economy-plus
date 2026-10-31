package dev.smpeconomy.features.bank;

import eu.pb4.sgui.api.gui.SimpleGui;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;

final class BankGui {
	static final String TITLE = "§8Bank";
	static final int[] PERCENTS = {10, 50, 100};
	static final int[] DEPOSIT_SLOTS = {10, 11, 12};
	static final int[] WITHDRAW_SLOTS = {14, 15, 16};
	static final int INFO_SLOT = 13;
	static final int DEPOSIT_INPUT_SLOT = 20;
	static final int INTEREST_SLOT = 22;
	static final int WITHDRAW_INPUT_SLOT = 24;
	static final int MENU_SLOT = 27;
	static final int CLOSE_SLOT = 31;

	private BankGui() {}

	static void open(ServerPlayer player) {
		if (!Gui.canOpen(player)) return;
		SimpleGui gui = Gui.chest(player, 4, TITLE);
		render(gui, player);
		gui.setSlot(CLOSE_SLOT, Gui.closeButton(gui));
		Gui.menuButton(gui, MENU_SLOT);
		Gui.frame(gui);
		Gui.fillEmpty(gui);
		gui.open();
	}

	private static void render(SimpleGui gui, ServerPlayer player) {
		UUID id = player.getUUID();
		long cash = EconomyService.balance(id);
		long bank = BankService.balance(id);
		Item[] gold = {Items.GOLD_NUGGET, Items.GOLD_INGOT, Items.GOLD_BLOCK};
		Item[] iron = {Items.IRON_NUGGET, Items.IRON_INGOT, Items.IRON_BLOCK};
		for (int i = 0; i < PERCENTS.length; i++) {
			int pct = PERCENTS[i];
			gui.setSlot(DEPOSIT_SLOTS[i], percentButton(gui, player, true, pct, gold[i], cash, bank));
			gui.setSlot(WITHDRAW_SLOTS[i], percentButton(gui, player, false, pct, iron[i], cash, bank));
		}
		List<String> info = new ArrayList<>(BankActions.infoLines(id));
		gui.setSlot(INFO_SLOT, Gui.item(Items.ENDER_CHEST, "§6§lBank Account", info));

		gui.setSlot(DEPOSIT_INPUT_SLOT, Gui.button(Items.NAME_TAG, "§a§lDeposit Amount", () -> askAmount(player, true),
			"§7Cash: " + Msg.moneyStr(cash), "", "§7Type an amount, e.g. §e1000§7,", "§e2.5k§7, §e25% §7or §eall§7.", "", "§eClick to enter"));
		gui.setSlot(WITHDRAW_INPUT_SLOT, Gui.button(Items.NAME_TAG, "§c§lWithdraw Amount", () -> askAmount(player, false),
			"§7Bank balance: " + Msg.moneyStr(bank), "", "§7Type an amount, e.g. §e1000§7,", "§e2.5k§7, §e25% §7or §eall§7.", "", "§eClick to enter"));
		List<String> interest = new ArrayList<>(BankActions.interestLines(id));
		interest.add("");
		interest.add("§8Interest is paid by the Treasury,");
		interest.add("§8every day at midnight.");
		gui.setSlot(INTEREST_SLOT, Gui.item(Items.CLOCK, "§e§lInterest", interest));
	}

	private static eu.pb4.sgui.api.elements.GuiElementBuilder percentButton(SimpleGui gui, ServerPlayer player, boolean deposit, int pct, Item icon,
		long cash, long bank) {
		long base = deposit ? cash : bank;
		long amount = BankLedger.parseAmount(pct + "%", base);
		String name = deposit ? "§a§lDeposit " + pct + "%" : "§c§lWithdraw " + pct + "%";
		String[] lore = amount > 0
			? new String[] {"§7Amount: " + Msg.moneyStr(amount),
				deposit ? "§7Bank after: " + Msg.moneyStr(bank + amount) : "§7Cash after: " + Msg.moneyStr(safeAdd(cash, amount)),
				"", "§eClick"}
			: new String[] {deposit ? "§8No cash." : "§8No bank balance."};
		return Gui.button(icon, name, () -> {
			// read balances at click time, never the displayed values
			long now = deposit ? EconomyService.balance(player.getUUID()) : BankService.balance(player.getUUID());
			BankActions.move(player, deposit, BankLedger.parseAmount(pct + "%", now));
			if (gui.isOpen()) render(gui, player);
		}, lore);
	}

	private static long safeAdd(long a, long b) {
		return a > Money.MAX - b ? Money.MAX : a + b;
	}

	private static void askAmount(ServerPlayer player, boolean deposit) {
		Gui.textInput(player, deposit ? "§8Deposit amount" : "§8Withdraw amount", "", text -> {
			BankActions.handleInput(player, deposit, text);
			open(player);
		}, () -> open(player));
	}
}
