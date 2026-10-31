package dev.smpeconomy.features.settings;

import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Settings;

/**
 * {@code /settings}: 6 rows with a frame. Each switch has its icon on top and the toggle (green or red dye) right
 * below; both toggle, debounced. Up to {@value #PER_PAGE} switches per page. No real slots, so no items can be taken
 * out.
 */
public final class SettingsGui {
	public static final String TITLE = "§8Settings";
	static final int ROWS = 6;
	static final int PER_ROW = 7;
	static final int PER_PAGE = 2 * PER_ROW;
	static final int SLOT_INFO = 4;
	static final int SLOT_MENU = 45;
	static final int SLOT_PREV = 48;
	static final int SLOT_CLOSE = 49;
	static final int SLOT_NEXT = 50;
	static final int SLOT_RESET = 53;
	private static final int LORE_WIDTH = 34;

	private SettingsGui() {}

	/** 0-based, clamped. False if no GUI may open right now (dead, stopping). */
	public static boolean open(ServerPlayer player, int page) {
		if (!Gui.canOpen(player)) return false;
		List<Settings.Def> defs = Settings.all();
		int pages = pages(defs.size());
		int p = Math.max(0, Math.min(page, pages - 1));
		SimpleGui gui = Gui.chest(player, ROWS, pages > 1 ? TITLE + " §7(" + (p + 1) + "/" + pages + ")" : TITLE);
		render(gui, player, defs, p, pages);
		return gui.open();
	}

	/** Refills all slots in the same window, also after a toggle, instead of reopening. */
	private static void render(SimpleGui gui, ServerPlayer player, List<Settings.Def> defs, int page, int pages) {
		for (int i = 0; i < gui.getVirtualSize(); i++) gui.clearSlot(i);
		int from = page * PER_PAGE;
		int count = Math.max(0, Math.min(PER_PAGE, defs.size() - from));
		int[] slots = iconSlots(count);
		for (int i = 0; i < count; i++) {
			Settings.Def def = defs.get(from + i);
			boolean on = Settings.get(player.getUUID(), def.key());
			gui.setSlot(slots[i], icon(def, on).setCallback((index, type, action, g) -> onToggle(gui, player, def, page, pages, index, type)));
			gui.setSlot(slots[i] + 9, toggle(def, on).setCallback((index, type, action, g) -> onToggle(gui, player, def, page, pages, index, type)));
		}

		int changed = SettingsLogic.changedCount(player.getUUID());
		gui.setSlot(SLOT_INFO, Gui.item(Items.COMPARATOR, "§6§lSettings",
			"§7Click an icon or the switch",
			"§7below it to toggle.",
			"",
			"§7Changed from default: §f" + changed + "§7/§f" + defs.size(),
			"§8Also in chat: /settings <name> on|off"));
		if (page > 0) gui.setSlot(SLOT_PREV, Gui.button(Items.ARROW, "§e« Previous Page", () -> open(player, page - 1)));
		if (page < pages - 1) gui.setSlot(SLOT_NEXT, Gui.button(Items.ARROW, "§eNext Page »", () -> open(player, page + 1)));
		Gui.menuButton(gui, SLOT_MENU);
		gui.setSlot(SLOT_CLOSE, Gui.closeButton(gui));
		gui.setSlot(SLOT_RESET, Gui.button(Items.MILK_BUCKET, "§eRestore Defaults", () -> askReset(player, page),
			"§7Resets all settings",
			"§7to their defaults.",
			"",
			changed == 0 ? "§8Everything is on default." : "§7Changed: §f" + changed));
		Gui.frame(gui);
		Gui.fillEmpty(gui);
	}

	private static void onToggle(SimpleGui gui, ServerPlayer player, Settings.Def def, int page, int pages, int index, ClickType type) {
		if (!Gui.acceptClick(player, index, type)) return;
		Optional<Boolean> value = SettingsLogic.toggle(player.getUUID(), def.key());
		if (value.isEmpty()) {
			Gui.failSound(player);
			return;
		}
		feedback(player, def, value.get());
		if (gui.isOpen()) render(gui, player, Settings.all(), page, pages);
	}

	static void feedback(ServerPlayer player, Settings.Def def, boolean on) {
		if (on) Gui.sound(player, SoundEvents.NOTE_BLOCK_PLING, 1.6f);
		else Gui.sound(player, SoundEvents.NOTE_BLOCK_BASS, 0.7f);
		Msg.actionBar(player, "§e" + SettingsLogic.plain(def.label()) + "§7: " + SettingsLogic.stateLabel(on));
	}

	private static void askReset(ServerPlayer player, int page) {
		if (SettingsLogic.changedCount(player.getUUID()) == 0) {
			Gui.failSound(player);
			Msg.info(player, "All settings are already on default.");
			return;
		}
		Gui.confirm(player, "§8Reset all settings?",
			Gui.item(Items.MILK_BUCKET, "§eRestore Defaults", "§7All settings will be set", "§7back to their defaults."),
			() -> {
				int changed = SettingsLogic.resetAll(player.getUUID());
				Msg.success(player, "Settings reset (" + changed + " changed).");
				Gui.successSound(player);
				if (Gui.canOpen(player)) open(player, page);
			},
			() -> open(player, page));
	}

	static GuiElementBuilder icon(Settings.Def def, boolean on) {
		Item item = dev.smpeconomy.core.Items.byId(def.icon()).orElse(Items.PAPER);
		List<String> lore = new ArrayList<>();
		for (String line : SettingsLogic.wrap(def.description(), LORE_WIDTH)) lore.add("§7" + line);
		lore.add("");
		lore.add("§7Status: " + SettingsLogic.stateLabel(on));
		lore.add("§8Default: " + (def.defaultValue() ? "On" : "Off"));
		lore.add("§eClick to toggle");
		return Gui.item(item, (on ? "§a" : "§c") + SettingsLogic.plain(def.label()), lore).glow(on);
	}

	static GuiElementBuilder toggle(Settings.Def def, boolean on) {
		return Gui.item(on ? Items.LIME_DYE : Items.RED_DYE, on ? "§a✔ On" : "§c✘ Off",
			"§7" + SettingsLogic.plain(def.label()),
			"§eClick to toggle");
	}

	static int pages(int count) {
		return Math.max(1, (count + PER_PAGE - 1) / PER_PAGE);
	}

	/**
	 * Icon slots for {@code count} (0-14) switches of a page: the first 7 in row 1, the rest in row 3, with toggles
	 * one row below. Each row is centered; with an even count the middle column stays free.
	 */
	static int[] iconSlots(int count) {
		int n = Math.max(0, Math.min(PER_PAGE, count));
		int[] slots = new int[n];
		int first = Math.min(PER_ROW, n);
		int[] cols1 = columns(first);
		int[] cols2 = columns(n - first);
		for (int i = 0; i < first; i++) slots[i] = 9 + cols1[i];
		for (int i = 0; i < n - first; i++) slots[first + i] = 27 + cols2[i];
		return slots;
	}

	/** Columns (1-7), symmetric around the middle (column 4). */
	static int[] columns(int n) {
		int k = Math.max(0, Math.min(PER_ROW, n));
		int[] cols = new int[k];
		if (k % 2 == 1) {
			int start = 4 - k / 2;
			for (int i = 0; i < k; i++) cols[i] = start + i;
		} else {
			int half = k / 2;
			for (int i = 0; i < half; i++) cols[i] = 4 - half + i;
			for (int i = 0; i < half; i++) cols[half + i] = 5 + i;
		}
		return cols;
	}
}
