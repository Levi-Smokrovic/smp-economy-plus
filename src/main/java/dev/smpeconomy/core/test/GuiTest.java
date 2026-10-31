package dev.smpeconomy.core.test;

import eu.pb4.sgui.api.gui.SimpleGui;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Msg;

/** {@code /smpeco guitest}: demo of the Gui helpers for the e2e test. Hands out no items. */
public final class GuiTest {
	public static final String TITLE = "§8GUI Test";
	public static final String FRAME_TITLE = "§8GUI Test Frame";
	private static final int ENTRIES = 60;

	private GuiTest() {}

	public static void open(ServerPlayer player) {
		openList(player, 0);
	}

	private static void openList(ServerPlayer player, int page) {
		List<Integer> entries = new ArrayList<>();
		for (int i = 1; i <= ENTRIES; i++) entries.add(i);
		SimpleGui gui = Gui.chest(player, 6, TITLE);
		List<Gui.Pager<Integer>> pager = new ArrayList<>(1);
		pager.add(new Gui.Pager<>(gui, () -> entries, n -> Gui.button(Items.PAPER, "§fEntry " + n,
			() -> Gui.confirm(player, "§8Confirm?", Gui.item(Items.PAPER, "§fEntry " + n, "§7Really select this?"),
				() -> {
					Msg.success(player, "GUI test confirmed: Entry " + n);
					Gui.successSound(player);
				},
				() -> openList(player, pager.get(0).page())),
			"§7Click to select")));
		gui.setSlot(45, Gui.button(Items.ITEM_FRAME, "§eFrame Test", () -> openFrame(player)));
		gui.setSlot(46, Gui.button(Items.NAME_TAG, "§eInput Test", () -> Gui.textInput(player, "§8GUI Test Input", "Search",
			text -> Msg.info(player, "GUI test input: [" + text + "]"),
			() -> openList(player, 0))));
		gui.setSlot(53, Gui.closeButton(gui));
		pager.get(0).show(page);
		gui.open();
	}

	private static void openFrame(ServerPlayer player) {
		SimpleGui gui = Gui.chest(player, 4, FRAME_TITLE);
		Gui.frame(gui);
		int[] inner = Gui.innerSlots(4);
		for (int i = 0; i < inner.length; i++) {
			int n = i + 1;
			gui.setSlot(inner[i], Gui.button(Items.LIME_DYE, "§aField " + n, type ->
				Msg.info(player, "GUI test field " + n + (type.isRight ? " right" : " left") + (type.shift ? " shift" : ""))));
		}
		gui.setSlot(27, Gui.backButton(() -> openList(player, 0)));
		gui.open();
	}
}
