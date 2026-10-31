package dev.smpeconomy.features.tasks;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import dev.smpeconomy.core.CrateKeys;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.gui.SmpGui;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.TimeUtil;
import dev.smpeconomy.features.tasks.TaskState.ActiveTask;

/** Display only: no real slots, no actions with effects. Refreshed every second. */
public final class TasksGui {
	static final String TITLE = "§6§lDaily Tasks";
	static final int ROWS = 5;
	static final int INFO_SLOT = 4;
	static final int BONUS_SLOT = 31;
	static final int MENU_SLOT = 36;
	static final int CLOSE_SLOT = 40;
	static final int BAR_LENGTH = 20;
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.ENGLISH);

	private static final Map<UUID, SimpleGui> OPEN = new HashMap<>();

	private TasksGui() {}

	public static void open(ServerPlayer player) {
		if (!Gui.canOpen(player)) return;
		TasksService.update(player);
		UUID id = player.getUUID();
		SimpleGui gui = new SmpGui(Gui.menuType(ROWS), player, false) {
			@Override
			protected void onClosed() {
				OPEN.remove(id, this);
			}
		};
		gui.setTitle(Msg.legacy(TITLE));
		render(gui, player);
		OPEN.put(id, gui);
		gui.open();
	}

	static void refresh(ServerPlayer player) {
		SimpleGui gui = OPEN.get(player.getUUID());
		if (gui == null) return;
		if (!gui.isOpen()) {
			OPEN.remove(player.getUUID());
			return;
		}
		render(gui, player);
	}

	static void closeAll() {
		OPEN.clear();
	}

	/** Centered, at most {@link TasksConfig#MAX_SLOTS}. */
	static int[] taskSlots(int count) {
		return switch (Math.max(0, Math.min(TasksConfig.MAX_SLOTS, count))) {
			case 0 -> new int[0];
			case 1 -> new int[] {13};
			case 2 -> new int[] {12, 14};
			case 3 -> new int[] {11, 13, 15};
			case 4 -> new int[] {10, 12, 14, 16};
			default -> new int[] {11, 12, 13, 14, 15};
		};
	}

	private static void render(SimpleGui gui, ServerPlayer player) {
		TaskState st = TasksService.peek(player.getUUID());
		for (int i = 0; i < ROWS * 9; i++) gui.setSlot(i, Gui.filler());
		Gui.frame(gui, false);
		List<ActiveTask> tasks = st == null ? List.of() : st.tasks;
		int done = 0;
		for (ActiveTask t : tasks) {
			if (t.paid) done++;
		}

		String date = st == null ? "-" : formatDate(st.date);
		gui.setSlot(INFO_SLOT, Gui.item(net.minecraft.world.item.Items.CLOCK, "§6§lDaily Tasks",
			"§7Date: §f" + date,
			"§7New tasks in §e" + TimeUtil.formatDuration(TasksService.millisUntilReset()),
			"",
			"§7Completed: §a" + done + "§7/§f" + tasks.size(),
			"",
			"§8Progress counts automatically",
			"§8(from your stats since assignment)."));

		int[] slots = taskSlots(tasks.size());
		for (int i = 0; i < slots.length; i++) {
			ActiveTask t = tasks.get(i);
			gui.setSlot(slots[i], taskItem(t));
			gui.setSlot(slots[i] + 9, barItem(t));
		}
		if (tasks.isEmpty()) {
			gui.setSlot(22, Gui.item(net.minecraft.world.item.Items.BARRIER, "§cNo tasks available", "§7Please try again later."));
		}
		if (st != null && !tasks.isEmpty()) gui.setSlot(BONUS_SLOT, bonusItem(st, done));
		Gui.menuButton(gui, MENU_SLOT);
		gui.setSlot(CLOSE_SLOT, Gui.closeButton(gui));
	}

	static GuiElementBuilder taskItem(ActiveTask t) {
		Item icon = Items.byId(t.icon).orElse(net.minecraft.world.item.Items.PAPER);
		List<String> lore = new ArrayList<>();
		lore.add(TasksConfig.difficultyLabel(t.difficulty) + " §8· §7" + TasksConfig.categoryLabel(t.category));
		lore.add("");
		lore.add("§f" + t.description);
		lore.add("");
		lore.add(bar(t.fraction()));
		lore.add("§7Progress: §f" + Msg.number(t.progress()) + "§7/§f" + Msg.number(t.target));
		lore.add("§7Reward: " + Msg.moneyStr(t.reward));
		lore.add("");
		lore.add(t.paid ? "§a✔ Done – reward received" : "§eIn progress …");
		GuiElementBuilder b = Gui.item(icon, (t.paid ? "§a" : "§e") + t.name, lore);
		if (t.paid) b.glow();
		return b;
	}

	static GuiElementBuilder barItem(ActiveTask t) {
		Item pane = t.paid || t.complete() ? net.minecraft.world.item.Items.LIME_STAINED_GLASS_PANE
			: t.progressRaw > 0 ? net.minecraft.world.item.Items.YELLOW_STAINED_GLASS_PANE
			: net.minecraft.world.item.Items.RED_STAINED_GLASS_PANE;
		String color = t.paid || t.complete() ? "§a" : t.progressRaw > 0 ? "§e" : "§c";
		return Gui.item(pane, color + percent(t.fraction()) + "%", bar(t.fraction()), "§7" + Msg.number(t.progress()) + " / " + Msg.number(t.target));
	}

	static GuiElementBuilder bonusItem(TaskState st, int done) {
		List<String> lore = new ArrayList<>();
		lore.add("§7Complete all " + st.tasks.size() + " tasks:");
		if (st.bonusMoney > 0) lore.add("§8• " + Msg.moneyStr(st.bonusMoney));
		if (st.bonusKeys > 0 && CrateKeys.enabled()) lore.add("§8• §e" + st.bonusKeys + "× " + CrateKeys.label(st.bonusKeyTier) + (st.bonusKeys == 1 ? " Key" : " Keys"));
		lore.add("");
		lore.add(bar(st.tasks.isEmpty() ? 0 : (double) done / st.tasks.size()));
		lore.add(st.bonusPaid ? "§a✔ Bonus received" : "§7Progress: §f" + done + "§7/§f" + st.tasks.size());
		GuiElementBuilder b = Gui.item(st.bonusPaid ? net.minecraft.world.item.Items.ENDER_CHEST : net.minecraft.world.item.Items.CHEST,
			st.bonusPaid ? "§a§lBonus received" : "§6§lBonus", lore);
		if (st.bonusPaid) b.glow();
		return b;
	}

	public static String bar(double fraction) {
		double f = Double.isNaN(fraction) ? 0 : Math.max(0, Math.min(1, fraction));
		int filled = (int) Math.floor(f * BAR_LENGTH);
		if (f >= 1.0) filled = BAR_LENGTH;
		return "§a" + "|".repeat(filled) + "§8" + "|".repeat(BAR_LENGTH - filled) + " §f" + percent(f) + "%";
	}

	/** Rounded down (99.9% shows 99), 100 only when reached. */
	public static int percent(double fraction) {
		double f = Double.isNaN(fraction) ? 0 : Math.max(0, Math.min(1, fraction));
		return f >= 1.0 ? 100 : (int) Math.floor(f * 100);
	}

	static String formatDate(String iso) {
		var d = TasksService.parseDate(iso);
		return d == null ? "-" : DATE.format(d);
	}
}
