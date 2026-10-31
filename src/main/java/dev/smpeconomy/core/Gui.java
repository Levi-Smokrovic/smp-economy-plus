package dev.smpeconomy.core;

import com.mojang.brigadier.tree.CommandNode;
import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.AnvilInputGui;
import eu.pb4.sgui.api.gui.SimpleGui;
import dev.smpeconomy.core.gui.SmpAnvilGui;
import dev.smpeconomy.core.gui.SmpGui;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Util;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * sgui helpers for chest GUIs on vanilla clients. All callbacks run on the server thread.
 *
 * <p>sgui calls element callbacks for every click type (double-click collect, drag, Q, number keys, middle click,
 * offhand swap). Buttons here only fire on plain left or right clicks, with or without shift, and are debounced (
 * {@link #acceptClick}): a double click or shift double click fires at most once. Custom {@code setCallback(...)} with an
 * effect (buying, selling, money) must check {@link #acceptClick} too.
 */
public final class Gui {
	/** Shift double click fires on every matching item, hence a per-player minimum. */
	public static final long CLICK_DEBOUNCE_MS = 150;
	/** Double click is 2x PICKUP + PICKUP_ALL, even across a newly opened menu. */
	public static final long SAME_SLOT_DEBOUNCE_MS = 300;

	/** {time ms, slot}. */
	private static final Map<UUID, long[]> LAST_CLICK = new HashMap<>();

	private Gui() {}

	public static boolean isPlainClick(ClickType type) {
		return type == ClickType.MOUSE_LEFT || type == ClickType.MOUSE_RIGHT || type == ClickType.MOUSE_LEFT_SHIFT || type == ClickType.MOUSE_RIGHT_SHIFT;
	}

	/**
	 * At most one trigger per {@value #CLICK_DEBOUNCE_MS} ms per player and per {@value #SAME_SLOT_DEBOUNCE_MS} ms on
	 * one slot index. Records the click if accepted.
	 */
	public static boolean acceptClick(ServerPlayer player, int slot, ClickType type) {
		if (player == null || !isPlainClick(type)) return false;
		long now = Util.getMillis();
		long[] last = LAST_CLICK.get(player.getUUID());
		if (last != null) {
			long dt = now - last[0];
			if (dt >= 0 && (dt < CLICK_DEBOUNCE_MS || (last[1] == slot && dt < SAME_SLOT_DEBOUNCE_MS))) return false;
		}
		LAST_CLICK.put(player.getUUID(), new long[] {now, slot});
		return true;
	}

	public static SimpleGui chest(ServerPlayer player, int rows, String titleLegacy) {
		SimpleGui gui = new SimpleGui(menuType(rows), player, false);
		gui.setTitle(Msg.legacy(titleLegacy));
		return gui;
	}

	/**
	 * False on death, on disconnect, during disconnect handling (connection already closed) and during shutdown. Check
	 * this in close hooks ({@code SmpGui#onClosed}) before reopening a previous menu.
	 */
	public static boolean canOpen(ServerPlayer player) {
		return !dev.smpeconomy.SmpEconomy.isStopping() && player.isAlive() && !player.hasDisconnected() && player.connection != null
			&& player.connection.isAcceptingMessages();
	}

	public static MenuType<?> menuType(int rows) {
		return switch (Math.max(1, Math.min(6, rows))) {
			case 1 -> MenuType.GENERIC_9x1;
			case 2 -> MenuType.GENERIC_9x2;
			case 3 -> MenuType.GENERIC_9x3;
			case 4 -> MenuType.GENERIC_9x4;
			case 5 -> MenuType.GENERIC_9x5;
			default -> MenuType.GENERIC_9x6;
		};
	}

	public static GuiElementBuilder item(Item item, String nameLegacy, String... loreLegacy) {
		GuiElementBuilder b = new GuiElementBuilder(item).setName(Msg.legacy(nameLegacy)).hideDefaultTooltip();
		return lore(b, loreLegacy);
	}

	public static GuiElementBuilder item(Item item, String nameLegacy, List<String> loreLegacy) {
		return item(item, nameLegacy, loreLegacy.toArray(String[]::new));
	}

	/** Shows a real stack (e.g. an auction item) with all components. The original stack is not changed. */
	public static GuiElementBuilder display(ItemStack stack, String... extraLoreLegacy) {
		GuiElementBuilder b = GuiElementBuilder.from(stack.copy());
		return lore(b, extraLoreLegacy);
	}

	public static GuiElementBuilder lore(GuiElementBuilder builder, String... loreLegacy) {
		for (String line : loreLegacy) builder.addLoreLine(Msg.legacy(line));
		return builder;
	}

	public static GuiElementBuilder filler() {
		return new GuiElementBuilder(net.minecraft.world.item.Items.GRAY_STAINED_GLASS_PANE).setName(Msg.legacy(" ")).hideTooltip();
	}

	public static GuiElementBuilder border() {
		return new GuiElementBuilder(net.minecraft.world.item.Items.BLACK_STAINED_GLASS_PANE).setName(Msg.legacy(" ")).hideTooltip();
	}

	public static void frame(SimpleGui gui, boolean keepExisting) {
		int size = gui.getVirtualSize();
		int rows = size / 9;
		for (int i = 0; i < size; i++) {
			int row = i / 9;
			int col = i % 9;
			boolean edge = row == 0 || row == rows - 1 || col == 0 || col == 8;
			if (edge && (!keepExisting || gui.getSlot(i) == null)) gui.setSlot(i, border());
		}
	}

	public static void frame(SimpleGui gui) {
		frame(gui, true);
	}

	public static int[] innerSlots(int rows) {
		int r = Math.max(3, Math.min(6, rows));
		int[] slots = new int[(r - 2) * 7];
		int n = 0;
		for (int row = 1; row < r - 1; row++) {
			for (int col = 1; col < 8; col++) slots[n++] = row * 9 + col;
		}
		return slots;
	}

	/** Plays the click sound and passes the sgui click type. Plain clicks only, debounced ({@link #acceptClick}). */
	public static GuiElementBuilder button(Item item, String nameLegacy, ClickHandler onClick, String... loreLegacy) {
		return item(item, nameLegacy, loreLegacy).setCallback((index, type, action, g) -> {
			if (!acceptClick(g.getPlayer(), index, type)) return;
			click(g.getPlayer());
			onClick.onClick(type);
		});
	}

	public static GuiElementBuilder button(Item item, String nameLegacy, Runnable onClick, String... loreLegacy) {
		return button(item, nameLegacy, type -> onClick.run(), loreLegacy);
	}

	@FunctionalInterface
	public interface ClickHandler {
		void onClick(ClickType type);
	}

	public static void fillEmpty(SimpleGui gui) {
		for (int i = 0; i < gui.getVirtualSize(); i++) {
			if (gui.getSlot(i) == null) gui.setSlot(i, filler());
		}
	}

	public static void fillRow(SimpleGui gui, int row) {
		for (int i = row * 9; i < row * 9 + 9 && i < gui.getVirtualSize(); i++) gui.setSlot(i, filler());
	}

	public static GuiElementBuilder backButton(Runnable onClick) {
		return item(net.minecraft.world.item.Items.ARROW, "§c« Back").setCallback((index, type, action, g) -> {
			if (!acceptClick(g.getPlayer(), index, type)) return;
			click(g.getPlayer());
			onClick.run();
		});
	}

	public static GuiElementBuilder closeButton(SimpleGui gui) {
		return item(net.minecraft.world.item.Items.BARRIER, "§cClose").setCallback((index, type, action, g) -> {
			if (isPlainClick(type)) gui.close();
		});
	}

	/** Features open the menu through the command so they do not depend on its package. */
	public static final String MAIN_MENU_COMMAND = "menu";

	public static boolean mainMenuAvailable(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		if (server == null) return false;
		CommandNode<CommandSourceStack> node = CommandRegistry.root().getChild(MAIN_MENU_COMMAND);
		return node != null && node.canUse(player.createCommandSourceStack());
	}

	/**
	 * Runs {@code /menu} as the player, which replaces the open GUI; its {@code onClosed} runs as with ESC. False if
	 * not possible right now.
	 */
	public static boolean openMainMenu(ServerPlayer player) {
		if (!canOpen(player) || !mainMenuAvailable(player)) return false;
		MinecraftServer server = player.level().getServer();
		if (server == null) return false;
		CommandRegistry.run(player, MAIN_MENU_COMMAND);
		return true;
	}

	/** Returns null when {@link #mainMenuAvailable} is false; the GUI then shows no such button. */
	public static GuiElementBuilder menuButton(ServerPlayer player, String... headLore) {
		if (player == null || !mainMenuAvailable(player)) return null;
		List<String> lore = new ArrayList<>(List.of(headLore));
		if (!lore.isEmpty()) lore.add("");
		lore.add("§7Overview of all features:");
		lore.add("§7Shop, Auction House, Jobs, Casino …");
		lore.add("");
		lore.add("§eClick to open §8(/" + MAIN_MENU_COMMAND + ")");
		return button(net.minecraft.world.item.Items.NETHER_STAR, "§6§lMain Menu", () -> {
			if (!openMainMenu(player) && canOpen(player)) {
				failSound(player);
				Msg.error(player, "The Main Menu isn't available right now.");
			}
		}, lore.toArray(String[]::new));
	}

	/**
	 * Bottom left in a feature's main GUI ({@code rows * 9 - 9}), otherwise a free slot next to "Close". If the last
	 * row of a pager GUI is full, use {@link Pager#withMenuButton()}. Returns true if set.
	 */
	public static boolean menuButton(SimpleGui gui, int slot) {
		GuiElementBuilder b = menuButton(gui.getPlayer());
		if (b == null) return false;
		gui.setSlot(slot, b);
		return true;
	}

	public static void sound(ServerPlayer player, Holder<SoundEvent> sound, float pitch) {
		player.connection.send(new ClientboundSoundPacket(sound, SoundSource.MASTER, player.getX(), player.getY(), player.getZ(), 0.6f, pitch, player.getRandom().nextLong()));
	}

	public static void click(ServerPlayer player) {
		sound(player, SoundEvents.UI_BUTTON_CLICK, 1f);
	}

	public static void successSound(ServerPlayer player) {
		sound(player, SoundEvents.NOTE_BLOCK_PLING, 1.6f);
	}

	public static void failSound(ServerPlayer player) {
		sound(player, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.VILLAGER_NO), 1f);
	}

	/**
	 * {@code onConfirm} runs at most once, after the dialog closed, and must re-check state there (offer still there?
	 * money?). {@code onCancel} may be null, is for navigation only, and runs on cancel or ESC but not on death or
	 * disconnect.
	 */
	public static SimpleGui confirm(ServerPlayer player, String titleLegacy, GuiElementBuilder preview, Runnable onConfirm, Runnable onCancel) {
		boolean[] done = {false};
		SimpleGui gui = new SmpGui(MenuType.GENERIC_9x3, player, false) {
			@Override
			protected void onClosed() {
				if (done[0]) return;
				done[0] = true;
				if (onCancel != null && canOpen(player)) onCancel.run();
			}
		};
		gui.setTitle(Msg.legacy(titleLegacy));
		GuiElementBuilder yes = item(net.minecraft.world.item.Items.LIME_STAINED_GLASS_PANE, "§a§lConfirm").setCallback((i, t, a, g) -> {
			// Debounced: the second click of a double click on the button that opened this dialog must not confirm.
			if (done[0] || !acceptClick(player, i, t)) return;
			done[0] = true;
			click(player);
			gui.close();
			onConfirm.run();
		});
		GuiElementBuilder no = item(net.minecraft.world.item.Items.RED_STAINED_GLASS_PANE, "§c§lCancel").setCallback((i, t, a, g) -> {
			if (!acceptClick(player, i, t)) return;
			click(player);
			gui.close(); // onClosed calls onCancel
		});
		for (int i = 0; i < 27; i++) gui.setSlot(i, filler());
		for (int slot : new int[] {9, 10, 11}) gui.setSlot(slot, yes);
		for (int slot : new int[] {15, 16, 17}) gui.setSlot(slot, no);
		gui.setSlot(13, preview);
		gui.open();
		return gui;
	}

	/**
	 * Text input through an anvil window. Clicking the right result slot calls {@code onSubmit} with the trimmed text,
	 * at most once, after closing. Slot 1 cancels. {@code onCancel} may be null and runs on cancel or ESC but not on
	 * death or disconnect.
	 */
	public static AnvilInputGui textInput(ServerPlayer player, String titleLegacy, String initial, java.util.function.Consumer<String> onSubmit, Runnable onCancel) {
		boolean[] done = {false};
		AnvilInputGui gui = new SmpAnvilGui(player, false) {
			@Override
			protected void onClosed() {
				if (done[0]) return;
				done[0] = true;
				if (onCancel != null && canOpen(player)) onCancel.run();
			}
		};
		gui.setTitle(Msg.legacy(titleLegacy));
		gui.setDefaultInputValue(initial == null ? "" : initial);
		gui.setSlot(1, item(net.minecraft.world.item.Items.BARRIER, "§cCancel").setCallback((i, t, a, g) -> {
			if (!acceptClick(player, i, t)) return;
			click(player);
			gui.close();
		}));
		gui.setSlot(2, item(net.minecraft.world.item.Items.LIME_DYE, "§a§lConfirm", "§7Type your text above, then click here").setCallback((i, t, a, g) -> {
			if (done[0] || !acceptClick(player, i, t)) return;
			done[0] = true;
			String input = gui.getInput() == null ? "" : gui.getInput().trim();
			click(player);
			gui.close();
			onSubmit.accept(input);
		}));
		gui.open();
		return gui;
	}

	/**
	 * Content goes in all rows but the last. Last row: index 3 back, 4 page display, 5 next; the rest stays free for
	 * custom buttons. {@link #show(int)} re-reads the list from the supplier each time. With {@link #withMenuButton()}
	 * the page display doubles as the main menu button.
	 */
	public static final class Pager<T> {
		private final SimpleGui gui;
		private final Supplier<List<T>> entries;
		private final Function<T, GuiElementBuilder> render;
		private int page;
		private int pages = 1;
		private boolean menuButton;

		public Pager(SimpleGui gui, Supplier<List<T>> entries, Function<T, GuiElementBuilder> render) {
			this.gui = gui;
			this.entries = entries;
			this.render = render;
		}

		/**
		 * Page and count move to the button's lore. Without {@code /menu} or the permission the normal page display
		 * stays. Call before {@link #show(int)}.
		 */
		public Pager<T> withMenuButton() {
			this.menuButton = true;
			return this;
		}

		public int perPage() {
			return gui.getVirtualSize() - 9;
		}

		public int page() {
			return page;
		}

		public int pages() {
			return pages;
		}

		public void refresh() {
			show(page);
		}

		public void show(int p) {
			List<T> list = entries.get();
			int per = perPage();
			pages = Math.max(1, (list.size() + per - 1) / per);
			page = Math.max(0, Math.min(p, pages - 1));
			for (int i = 0; i < per; i++) {
				int idx = page * per + i;
				if (idx < list.size()) gui.setSlot(i, render.apply(list.get(idx)));
				else gui.clearSlot(i);
			}
			int nav = per;
			for (int i = nav; i < nav + 9; i++) {
				if (gui.getSlot(i) == null) gui.setSlot(i, filler());
			}
			gui.setSlot(nav + 3, page > 0
				? item(net.minecraft.world.item.Items.ARROW, "§e« Previous Page").setCallback((i, t, a, g) -> {
					if (!acceptClick(gui.getPlayer(), i, t)) return;
					click(gui.getPlayer());
					show(page - 1);
				})
				: filler());
			String pageLabel = "§7Page §f" + (page + 1) + "§7/§f" + pages;
			GuiElementBuilder menu = menuButton ? Gui.menuButton(gui.getPlayer(), pageLabel + " §8(" + list.size() + " entries)") : null;
			gui.setSlot(nav + 4, menu != null ? menu : item(net.minecraft.world.item.Items.PAPER, pageLabel, "§8" + list.size() + " entries"));
			gui.setSlot(nav + 5, page < pages - 1
				? item(net.minecraft.world.item.Items.ARROW, "§eNext Page »").setCallback((i, t, a, g) -> {
					if (!acceptClick(gui.getPlayer(), i, t)) return;
					click(gui.getPlayer());
					show(page + 1);
				})
				: filler());
		}
	}
}
