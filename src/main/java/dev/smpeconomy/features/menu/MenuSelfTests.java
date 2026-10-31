package dev.smpeconomy.features.menu;

import static dev.smpeconomy.core.test.Assert.check;
import static dev.smpeconomy.core.test.Assert.eq;

import com.mojang.brigadier.tree.CommandNode;
import eu.pb4.sgui.api.gui.SimpleGui;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.test.Assert;
import dev.smpeconomy.core.test.SelfTests;

/** The click round trip is covered by {@code e2e/menu.mjs}. */
final class MenuSelfTests {
	private MenuSelfTests() {}

	static void register() {
		registerBackButton();
		SelfTests.register(MenuFeature.ID, "commands-exist", ctx -> {
			var root = ctx.server().getCommands().getDispatcher().getRoot();
			Set<Integer> slots = new HashSet<>();
			int skipped = 0;
			for (MenuGui.Entry e : MenuGui.ENTRIES) {
				check(slots.add(e.slot()), "Slot " + e.slot() + " duplicate");
				// Commands of disabled modules are missing on purpose.
				if (dev.smpeconomy.core.module.Modules.disabledOwnerOf(e.root()).isPresent()) {
					skipped++;
					continue;
				}
				CommandNode<CommandSourceStack> node = child(root, e.root());
				check(node != null, "menu command /" + e.root() + " registered");
				check(!e.command().equals(e.root()) || node.getCommand() != null, "/" + e.root() + " runs without arguments");
				check(e.slot() > 9 && e.slot() < (MenuGui.ROWS - 1) * 9 && e.slot() % 9 != 0 && e.slot() % 9 != 8, "Slot " + e.slot() + " in frame");
			}
			eq(20, MenuGui.ENTRIES.size(), "20 feature icons");
			Set<String> roots = new HashSet<>();
			for (MenuGui.Entry e : MenuGui.ENTRIES) check(roots.add(e.root()), "command /" + e.root() + " twice in the menu");
			for (String r : List.of("coinflip", "market", "event")) check(roots.contains(r), "menu has an icon for /" + r);
			for (String r : MenuGui.helpRoots()) {
				if (dev.smpeconomy.core.module.Modules.disabledOwnerOf(r).isPresent()) continue;
				check(child(root, r) != null, "help command /" + r + " registered");
			}
			for (MenuGui.HelpGroup g : MenuGui.HELP) for (MenuGui.HelpCmd c : g.commands()) {
				if (dev.smpeconomy.core.module.Modules.disabledOwnerOf(c.checkPath().get(0)).isPresent()) continue;
				CommandNode<CommandSourceStack> n = root;
				for (String w : c.checkPath()) n = n == null ? null : n == root ? child(root, w) : n.getChild(w);
				check(n != null, "help path /" + String.join(" ", c.checkPath()) + " registered");
			}
			for (String r : MenuGui.MENU_COMMANDS) check(root.getChild(r) != null, "/" + r + " registered");
			check(root.getChild(MenuGui.HELP_COMMAND) != null, "/" + MenuGui.HELP_COMMAND + " registered");
			for (String r : MenuGui.HELP_ALIASES) check(root.getChild(r) != null, "Alias /" + r + " registered");
			check(MenuGui.helpRoots().contains(MenuGui.HELP_COMMAND), "help lists /" + MenuGui.HELP_COMMAND);
			ctx.detail(MenuGui.ENTRIES.size() + " icons (" + skipped + " disabled), " + MenuGui.helpRoots().size() + " help commands");
		});

		SelfTests.register(MenuFeature.ID, "join-notice", ctx -> {
			var p = ctx.player("TestMenuHint");
			var msg = MenuJoinHint.message(p);
			Assert.notNull(msg, "hint for a normal player");
			String text = msg.getString();
			check(text.contains("[/menu]") && text.contains("[/commands]"), "text names /menu and /commands: " + text);
			List<String> clicks = new ArrayList<>();
			collectRunCommands(msg, clicks);
			eq(List.of("/menu", "/commands"), clicks, "both clickable (run the command)");

			MenuConfig cfg = new MenuConfig();
			MenuConfig.validate(cfg);
			long hours = cfg.joinHintPlaytimeHours * MenuJoinHint.TICKS_PER_HOUR;
			check(MenuJoinHint.due(cfg, 0, 0), "new player");
			check(MenuJoinHint.due(cfg, hours - 1, 1_000), "new player: every join, however often seen");
			check(MenuJoinHint.due(cfg, hours, 0), "experienced player: never seen");
			check(!MenuJoinHint.due(cfg, hours, cfg.joinHintTimes), "experienced player: seen often enough");
			MenuConfig off = new MenuConfig();
			off.joinHint = false;
			check(!MenuJoinHint.due(off, 0, 0), "disabled");
			MenuConfig never = new MenuConfig();
			never.joinHintTimes = 0;
			check(!MenuJoinHint.due(never, hours, 0), "joinHintTimes 0: only new players");

			// Test player: playtime 0, data deleted afterwards.
			MenuConfig live = MenuConfig.get();
			if (live.joinHint && live.joinHintPlaytimeHours > 0) {
				long minTicks = live.joinHintPlaytimeHours * MenuJoinHint.TICKS_PER_HOUR;
				check(MenuJoinHint.send(p), "new test player gets the hint");
				eq(Math.min(1, live.joinHintTimes), PlayerDataService.getCounter(p.getUUID(), MenuJoinHint.COUNTER), "counter +1 (up to joinHintTimes)");
				PlayerDataService.addPlaytime(p.getUUID(), minTicks + 1);
				int sent = 0;
				for (int i = 0; i < live.joinHintTimes + 3; i++) if (MenuJoinHint.send(p)) sent++;
				eq(Math.max(0, live.joinHintTimes - 1), sent, "afterwards only up to joinHintTimes");
				eq(live.joinHintTimes, PlayerDataService.getCounter(p.getUUID(), MenuJoinHint.COUNTER), "counter stays at joinHintTimes");
				check(!MenuJoinHint.send(p), "then no more hints");
			}

			Assert.assertThrows(IllegalArgumentException.class, () -> {
				MenuConfig bad = new MenuConfig();
				bad.joinHintDelaySeconds = MenuConfig.MAX_DELAY_SECONDS + 1;
				MenuConfig.validate(bad);
			}, "delay too large");
			Assert.assertThrows(IllegalArgumentException.class, () -> {
				MenuConfig bad = new MenuConfig();
				bad.joinHintTimes = -1;
				MenuConfig.validate(bad);
			}, "negative count");
			Assert.notNull(MenuConfig.get(), "menu.json loaded");
			ctx.detail(text);
		});
	}

	static void registerBackButton() {
		SelfTests.register(MenuFeature.ID, "main-menu-button", ctx -> {
			eq(MenuGui.MENU_COMMANDS.get(0), Gui.MAIN_MENU_COMMAND, "Gui.MAIN_MENU_COMMAND is the menu command");
			var p = ctx.player("TestMenuBack");
			check(Gui.mainMenuAvailable(p), "/menu available to a normal player");
			var b = Gui.menuButton(p);
			Assert.notNull(b, "main menu button for a normal player");
			ItemStack stack = b.asStack();
			eq(Items.NETHER_STAR, stack.getItem(), "nether star icon");
			check(name(stack).contains("Main Menu"), "name mentions the main menu: " + name(stack));
			check(lore(stack).stream().anyMatch(l -> l.contains("/menu")), "lore mentions /menu: " + lore(stack));

			SimpleGui chest = Gui.chest(p, 3, "§8Test");
			check(Gui.menuButton(chest, 18), "menuButton(gui, slot) sets the button");
			var el = chest.getSlot(18);
			check(el != null && el.getItemStack().is(Items.NETHER_STAR), "slot 18 = main menu");

			// Pager with a full last row: the page display becomes the main menu button, page and count move to the lore.
			SimpleGui paged = Gui.chest(p, 6, "§8Test");
			new Gui.Pager<Integer>(paged, () -> List.of(1, 2, 3), i -> Gui.item(Items.STONE, "§f" + i)).withMenuButton().show(0);
			ItemStack center = paged.getSlot(49).getItemStack();
			check(center.is(Items.NETHER_STAR), "Pager.withMenuButton: slot 49 = main menu");
			check(lore(center).stream().anyMatch(l -> l.contains("Page 1/1") && l.contains("3 entries")), "page display in lore: " + lore(center));
			SimpleGui plain = Gui.chest(p, 6, "§8Test");
			new Gui.Pager<Integer>(plain, () -> List.of(1), i -> Gui.item(Items.STONE, "§f" + i)).show(0);
			check(plain.getSlot(49).getItemStack().is(Items.PAPER), "without withMenuButton: normal page display");
			ctx.detail(name(stack));
		});
	}

	private static CommandNode<CommandSourceStack> child(CommandNode<CommandSourceStack> root, String name) {
		return root.getChild(name);
	}

	private static String name(ItemStack stack) {
		Component c = stack.get(DataComponents.CUSTOM_NAME);
		return c == null ? "" : c.getString();
	}

	private static List<String> lore(ItemStack stack) {
		ItemLore lore = stack.get(DataComponents.LORE);
		List<String> out = new ArrayList<>();
		if (lore != null) for (Component l : lore.lines()) out.add(l.getString());
		return out;
	}

	private static void collectRunCommands(Component c, List<String> out) {
		if (c.getStyle().getClickEvent() instanceof ClickEvent.RunCommand run) out.add(run.command());
		for (Component s : c.getSiblings()) collectRunCommands(s, out);
	}
}
