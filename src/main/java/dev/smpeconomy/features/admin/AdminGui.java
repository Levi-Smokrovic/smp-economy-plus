package dev.smpeconomy.features.admin;

import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.CommandRegistry;
import dev.smpeconomy.core.ConfigManager;
import dev.smpeconomy.core.CoreConfig;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Perms;
import dev.smpeconomy.core.Services;
import dev.smpeconomy.core.module.ModuleCatalog;
import dev.smpeconomy.core.module.ModuleGroup;
import dev.smpeconomy.core.module.ModuleInfo;
import dev.smpeconomy.core.module.Modules;

/**
 * {@code /smpeco admin}: settings without editing files. All changes go through {@link AdminSettings} or
 * {@link Modules#setConfigured}, with the same validation as files and commands. Module changes apply after a
 * restart.
 */
public final class AdminGui {
	public static final String TITLE = "§8SMP Economy+ » Admin";
	public static final String MODULES_TITLE = "§8SMP Economy+ » Modules";
	static final double STEP = 0.05;
	static final double BIG_STEP = 0.25;

	/** Selftests and e2e scripts use these. */
	static final int SLOT_INFO = 4;
	static final int SLOT_MODULES = 10;
	static final int SLOT_NPCS = 12;
	static final int SLOT_STARTER = 14;
	static final int SLOT_RELOAD = 16;
	static final int[] SLOT_SETTINGS = {19, 20, 21, 22, 23, 24, 25, 29, 31};
	static final int SLOT_RESTART = 33;
	static final int SLOT_CLOSE = 40;

	private AdminGui() {}

	public static void open(ServerPlayer player) {
		if (!Gui.canOpen(player)) return;
		if (!Perms.checkAdmin(player, Perms.ADMIN_SETTINGS)) {
			Msg.error(player, "You don't have permission for this.");
			return;
		}
		SimpleGui gui = Gui.chest(player, 5, TITLE);
		render(gui, player);
		gui.open();
	}

	static void render(SimpleGui gui, ServerPlayer player) {
		for (int i = 0; i < gui.getVirtualSize(); i++) gui.clearSlot(i);
		gui.setSlot(SLOT_INFO, info());
		gui.setSlot(SLOT_MODULES, Gui.button(Items.COMPARATOR, "§e§lModules", () -> openModules(player),
			"§7Turn features on or off.", "",
			"§7Running: §f" + Modules.enabled().size() + "§7/§f" + ModuleCatalog.ALL.size(),
			Modules.restartPending() ? "§6Restart required to apply changes" : "§8Everything is applied",
			"", "§eClick to open"));
		gui.setSlot(SLOT_NPCS, npcButton(gui, player));
		gui.setSlot(SLOT_STARTER, starterButton(player));
		gui.setSlot(SLOT_RELOAD, Gui.button(Items.WRITABLE_BOOK, "§e§lReload configs", () -> {
			List<String> errors = ConfigManager.reloadAll();
			if (errors.isEmpty()) {
				Msg.success(player, "Configs reloaded.");
				Gui.successSound(player);
			} else {
				for (String e : errors) Msg.error(player, e);
				Gui.failSound(player);
			}
			refresh(gui, player);
		}, "§7Reloads every file in config/smpeconomy/", "§7(same as §f/smpeco reload§7).", "", "§eClick to reload"));
		for (int i = 0; i < AdminSettings.ALL.size() && i < SLOT_SETTINGS.length; i++) {
			gui.setSlot(SLOT_SETTINGS[i], settingButton(gui, player, AdminSettings.ALL.get(i)));
		}
		gui.setSlot(SLOT_RESTART, restartItem());
		gui.setSlot(SLOT_CLOSE, Gui.closeButton(gui));
		Gui.frame(gui);
		Gui.fillEmpty(gui);
	}

	private static void refresh(SimpleGui gui, ServerPlayer player) {
		if (gui.isOpen()) render(gui, player);
	}

	private static GuiElementBuilder info() {
		String version = FabricLoader.getInstance().getModContainer(SmpEconomy.MOD_ID)
			.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
		List<String> lore = new ArrayList<>();
		lore.add("§7Version: §f" + version);
		lore.add("§7Modules running: §f" + Modules.enabled().size() + "§7/§f" + ModuleCatalog.ALL.size());
		List<String> warnings = SmpEconomy.adminWarnings();
		if (!warnings.isEmpty()) {
			lore.add("");
			lore.add("§c§lServer issues:");
			for (String w : warnings.subList(0, Math.min(6, warnings.size()))) lore.add("§c" + shorten(w, 60));
			lore.add("§7Details: §f/smpeco status");
		}
		return Gui.item(warnings.isEmpty() ? Items.NETHER_STAR : Items.REDSTONE_BLOCK, "§6§lSMP Economy+ §7Admin", lore);
	}

	private static GuiElementBuilder restartItem() {
		List<String> pending = Modules.pendingChanges();
		if (pending.isEmpty()) return Gui.item(Items.CLOCK, "§a§lNo restart needed", "§7All module changes are applied.");
		List<String> lore = new ArrayList<>();
		lore.add("§7Module changes take effect after a");
		lore.add("§7server restart:");
		for (String p : pending) lore.add("§6 • " + p);
		return Gui.item(Items.RED_CONCRETE, "§c§lRestart required", lore).glow();
	}

	private static GuiElementBuilder settingButton(SimpleGui gui, ServerPlayer player, AdminSettings.Setting s) {
		Item icon = dev.smpeconomy.core.Items.byId(s.icon()).orElse(Items.PAPER);
		String value = AdminSettings.value(s);
		List<String> lore = new ArrayList<>();
		lore.add("§7" + s.description());
		lore.add("");
		switch (s.kind()) {
			case BOOL -> {
				boolean on = Boolean.parseBoolean(value);
				lore.add("§7Now: " + (on ? "§aon" : "§coff") + " §8(example: " + Msg.stripCodes(dev.smpeconomy.core.Money.format(1250)) + ")");
				lore.add("");
				lore.add("§eClick to toggle");
			}
			case DECIMAL -> {
				lore.add("§7Now: §f" + value + "×");
				lore.add("");
				lore.add("§eLeft-click §7+" + AdminSettings.decimal(STEP) + " §8· §eRight-click §7-" + AdminSettings.decimal(STEP));
				lore.add("§eShift-click §7±" + AdminSettings.decimal(BIG_STEP));
				lore.add("§8Range " + CoreConfig.MIN_MULTIPLIER + "–" + CoreConfig.MAX_MULTIPLIER);
			}
			case MONEY -> {
				lore.add("§7Now: §a" + value);
				lore.add("");
				lore.add("§eClick to change");
			}
			case TEXT -> {
				lore.add("§7Now: §r" + value + "§r §8(" + AdminSettings.editable(value).replace("§", "") + ")");
				lore.add("");
				lore.add("§eClick to change");
			}
		}
		return Gui.button(icon, "§e§l" + s.label(), type -> onSettingClick(gui, player, s, type), lore.toArray(String[]::new));
	}

	private static void onSettingClick(SimpleGui gui, ServerPlayer player, AdminSettings.Setting s, ClickType type) {
		switch (s.kind()) {
			case BOOL -> apply(gui, player, s, Boolean.toString(!Boolean.parseBoolean(AdminSettings.value(s))), false);
			case DECIMAL -> {
				double now = Double.parseDouble(AdminSettings.value(s));
				double step = type.shift ? BIG_STEP : STEP;
				double next = type.isRight ? now - step : now + step;
				next = Math.max(CoreConfig.MIN_MULTIPLIER, Math.min(CoreConfig.MAX_MULTIPLIER, Math.round(next * 100.0) / 100.0));
				apply(gui, player, s, Double.toString(next), false);
			}
			case MONEY, TEXT -> {
				String initial = s.kind() == AdminSettings.Kind.MONEY ? Msg.stripCodes(AdminSettings.value(s)) : AdminSettings.editable(AdminSettings.value(s));
				Gui.textInput(player, "§8" + s.label(), initial, text -> {
					apply(null, player, s, text, true);
					open(player);
				}, () -> open(player));
			}
		}
	}

	private static void apply(SimpleGui gui, ServerPlayer player, AdminSettings.Setting s, String raw, boolean announce) {
		String error = AdminSettings.set(s.key(), raw, player.getPlainTextName());
		if (error != null) {
			Msg.error(player, error);
			Gui.failSound(player);
		} else {
			if (announce || s.kind() == AdminSettings.Kind.BOOL) {
				Msg.success(player, s.label() + " set to §r" + AdminSettings.value(s) + "§a.");
				if (s.key().startsWith("branding.chatPrefix")) Msg.info(player, "This is how messages look now.");
			}
			Gui.successSound(player);
		}
		if (gui != null) refresh(gui, player);
	}

	private static GuiElementBuilder npcButton(SimpleGui gui, ServerPlayer player) {
		boolean running = Modules.isEnabled("npc");
		boolean configured = Modules.isConfigured("npc");
		Optional<Services.NpcSwitch> sw = Services.find(Services.NpcSwitch.class);
		List<String> lore = new ArrayList<>();
		lore.add("§7Admin-placed NPCs that open shops and menus.");
		lore.add("");
		lore.add("§7Module: " + (configured ? "§aon" : "§coff") + (running != configured ? " §6(restart required)" : ""));
		if (sw.isPresent()) lore.add("§7Visible now: " + (sw.get().npcsVisible() ? "§ayes" : "§cno"));
		lore.add("");
		lore.add("§eLeft-click §7module on/off §8(restart)");
		if (sw.isPresent()) {
			lore.add("§eRight-click §7show/hide NPCs now");
			lore.add("§eShift-click §7manage NPCs");
		}
		return Gui.button(Items.VILLAGER_SPAWN_EGG, "§e§lNPCs", type -> {
			if (sw.isPresent() && type.shift) {
				gui.close();
				sw.get().openManager(player);
				return;
			}
			if (sw.isPresent() && type.isRight) {
				String error = sw.get().setNpcsVisible(!sw.get().npcsVisible(), player.getPlainTextName());
				if (error != null) Msg.error(player, error);
				else Msg.success(player, "NPCs are now " + (sw.get().npcsVisible() ? "visible" : "hidden") + ".");
				refresh(gui, player);
				return;
			}
			toggleModule(player, "npc", () -> refresh(gui, player));
		}, lore.toArray(String[]::new));
	}

	private static GuiElementBuilder starterButton(ServerPlayer player) {
		Optional<Services.StarterKit> kit = Services.find(Services.StarterKit.class);
		boolean command = AdminFeature.commandExists(player.level().getServer(), "smpeco", "starter", "capture");
		List<String> lore = new ArrayList<>();
		lore.add("§7What new players get on their first join.");
		lore.add("§7Start money: §a" + AdminSettings.value(AdminSettings.get("startBalance").orElseThrow()));
		kit.map(Services.StarterKit::summary).filter(s -> !s.isBlank()).ifPresent(s -> lore.add("§7Kit: §f" + s));
		lore.add("");
		if (kit.isPresent() || command) {
			lore.add("§7Put the kit in your inventory (armor and");
			lore.add("§7offhand count too), then click.");
			lore.add("");
			lore.add("§eClick to capture the kit from your inventory");
		} else {
			lore.add(Modules.isEnabled("starter") ? "§cThe starter module offers no kit editor." : "§cThe starter module is off (see Modules).");
		}
		return Gui.button(Items.CHEST, "§e§lStarter kit", () -> {
			if (kit.isPresent()) {
				kit.get().openCapture(player);
			} else if (AdminFeature.commandExists(player.level().getServer(), "smpeco", "starter", "capture")) {
				CommandRegistry.run(player, "smpeco starter capture");
			} else {
				Msg.error(player, "Starter kits are not available – enable the 'starter' module first.");
				Gui.failSound(player);
			}
		}, lore.toArray(String[]::new));
	}

	static void openModules(ServerPlayer player) {
		if (!Gui.canOpen(player)) return;
		SimpleGui gui = Gui.chest(player, 6, MODULES_TITLE);
		renderModules(gui, player);
		gui.open();
	}

	static List<ModuleInfo> moduleOrder() {
		List<ModuleInfo> out = new ArrayList<>();
		for (ModuleInfo m : ModuleCatalog.ALL) if (m.group() == ModuleGroup.CORE) out.add(m);
		for (ModuleInfo m : ModuleCatalog.ALL) if (m.group() == ModuleGroup.EXTRA) out.add(m);
		return out;
	}

	static void renderModules(SimpleGui gui, ServerPlayer player) {
		for (int i = 0; i < gui.getVirtualSize(); i++) gui.clearSlot(i);
		gui.setSlot(4, restartItem());
		int[] slots = Gui.innerSlots(6);
		List<ModuleInfo> order = moduleOrder();
		for (int i = 0; i < order.size() && i < slots.length; i++) {
			ModuleInfo m = order.get(i);
			gui.setSlot(slots[i], moduleButton(gui, player, m));
		}
		gui.setSlot(45, Gui.backButton(() -> open(player)));
		gui.setSlot(49, Gui.closeButton(gui));
		gui.setSlot(53, Gui.item(Items.BOOK, "§e§lHow it works",
			"§aGreen§7 = on, §cred§7 = off.",
			"§6Orange§7 = changes after the next restart.",
			"§7CORE modules are on by default,",
			"§7EXTRA modules are off by default.",
			"§7Saved in §fconfig/smpeconomy/modules.json§7."));
		Gui.frame(gui);
		Gui.fillEmpty(gui);
	}

	static GuiElementBuilder moduleButton(SimpleGui gui, ServerPlayer player, ModuleInfo m) {
		boolean running = Modules.isEnabled(m.id());
		boolean configured = Modules.isConfigured(m.id());
		boolean pending = running != configured;
		Item icon = dev.smpeconomy.core.Items.byId(m.icon()).orElse(Items.PAPER);
		String color = pending ? "§6" : configured ? "§a" : "§c";
		List<String> lore = new ArrayList<>();
		lore.add("§8" + m.group().name() + " · " + m.id());
		lore.add("§7" + m.description());
		lore.add("");
		lore.add("§7Now: " + (running ? "§arunning" : "§coff"));
		if (pending) lore.add("§6After restart: " + (configured ? "on" : "off"));
		if (!m.requires().isEmpty()) lore.add("§7Requires: §f" + String.join(", ", m.requires()));
		if (!m.uses().isEmpty()) lore.add("§8Works better with: " + String.join(", ", m.uses()));
		lore.add("");
		if (m.locked()) lore.add("§8Always on (base of the mod)");
		else lore.add("§eClick to " + (configured ? "disable" : "enable"));
		GuiElementBuilder b = Gui.button(icon, color + "§l" + m.name() + (configured ? " §a✔" : " §c✘"), () -> {
			if (m.locked()) {
				Msg.error(player, m.name() + " is the base of the mod and cannot be disabled.");
				Gui.failSound(player);
				return;
			}
			toggleModule(player, m.id(), () -> {
				if (gui.isOpen()) renderModules(gui, player);
			});
		}, lore.toArray(String[]::new));
		if (configured) b.glow();
		return b;
	}

	/** If dependencies require it, the GUI asks whether to switch them together. */
	static void toggleModule(ServerPlayer player, String id, Runnable after) {
		boolean on = !Modules.isConfigured(id);
		Modules.Result r = Modules.setConfigured(id, on, false, player.getPlainTextName());
		if (r.ok()) {
			Msg.success(player, r.message());
			Gui.successSound(player);
			after.run();
			return;
		}
		if (!dependencyProblem(id, on)) {
			Msg.error(player, r.message());
			Gui.failSound(player);
			after.run();
			return;
		}
		ModuleInfo m = ModuleCatalog.get(id).orElseThrow();
		Gui.confirm(player, "§8" + (on ? "Enable" : "Disable") + " " + m.name() + "?",
			Gui.item(Items.COMPARATOR, "§e" + (on ? "Enable with dependencies" : "Disable with dependents"), "§7" + r.message()),
			() -> {
				Modules.Result r2 = Modules.setConfigured(id, on, true, player.getPlainTextName());
				if (r2.ok()) Msg.success(player, r2.message());
				else Msg.error(player, r2.message());
				openModules(player);
			},
			() -> openModules(player));
	}

	private static boolean dependencyProblem(String id, boolean on) {
		ModuleInfo m = ModuleCatalog.get(id).orElse(null);
		if (m == null || m.locked()) return false;
		if (on) return m.requires().stream().anyMatch(r -> !Modules.isConfigured(r));
		return ModuleCatalog.dependents(id).stream().anyMatch(d -> Modules.isConfigured(d.id()));
	}

	private static String shorten(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max - 1) + "…";
	}

	static String lower(String s) {
		return s.toLowerCase(Locale.ROOT);
	}
}
