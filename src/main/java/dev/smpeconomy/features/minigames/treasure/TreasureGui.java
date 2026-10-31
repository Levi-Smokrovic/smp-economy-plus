package dev.smpeconomy.features.minigames.treasure;

import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import dev.smpeconomy.core.CrateKeys;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Money;

/**
 * Display only: money, items and keys were credited at the find, whether or not the GUI opens. There
 * is nothing to take here.
 */
public final class TreasureGui {
	public static final String TITLE = "§6§lTreasure found!";

	private TreasureGui() {}

	public static void open(ServerPlayer player, TreasureHunt.Claim claim) {
		SimpleGui gui = Gui.chest(player, 3, TITLE);
		Gui.frame(gui, false);
		int[] slots = {10, 11, 12, 13, 14, 15, 16};
		int i = 0;
		if (claim.payError() != null) {
			gui.setSlot(slots[i++], Gui.item(Items.PAPER, "§cMoney Voucher", "§7Payout failed:", "§c" + claim.payError()));
		} else if (claim.pendingMoney() > 0) {
			gui.setSlot(slots[i++], Gui.item(Items.PAPER, "§e§lMoney Voucher: " + Money.format(claim.money() + claim.pendingMoney()),
				"§7The Treasury is out of budget right now.", "§7It will be paid to you automatically", "§7once there's enough again §8(while you're online)").glow());
		} else if (claim.money() > 0) {
			gui.setSlot(slots[i++], Gui.item(Items.PAPER, "§a§lMoney Voucher: " + Money.format(claim.money()), "§7Added to your balance §a✔").glow());
		}
		for (ItemStack stack : claim.items()) {
			if (i >= slots.length) break;
			// show at most a full stack; larger amounts were split at the find
			ItemStack shown = stack.copyWithCount(Math.max(1, Math.min(stack.getCount(), stack.getMaxStackSize())));
			gui.setSlot(slots[i++], Gui.display(shown, "", "§7Amount: §f" + stack.getCount(), "§7Put into your inventory §a✔"));
		}
		if (claim.commonKeys() > 0 && i < slots.length) {
			gui.setSlot(slots[i++], Gui.item(Items.TRIPWIRE_HOOK, "§d" + claim.commonKeys() + "× " + CrateKeys.label(CrateKeys.COMMON) + " Key",
				"§7Redeem with §e/crates").glow());
		}
		if (claim.rareKeys() > 0 && i < slots.length) {
			gui.setSlot(slots[i++], Gui.item(Items.TRIPWIRE_HOOK, "§5" + claim.rareKeys() + "× " + CrateKeys.label(CrateKeys.RARE) + " Key",
				"§7Redeem with §e/crates").glow());
		}
		if (i == 0) gui.setSlot(13, Gui.item(Items.CHEST, "§7Empty…", "§8Nothing inside this time."));
		gui.setSlot(22, Gui.closeButton(gui));
		Gui.fillEmpty(gui);
		Gui.sound(player, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE), 1.0f);
		gui.open();
	}
}
