package dev.smpeconomy.features.holo;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Perms;
import dev.smpeconomy.features.holo.HoloStore.Holo;

/**
 * Admin GUI for {@code /holo}. No real slots; all actions go through {@link Gui#button} and {@link Gui#confirm},
 * which filter click types and debounce.
 */
final class HoloGui {
	private static final int ROWS = 6;

	private HoloGui() {}

	static List<Holo> sorted() {
		List<Holo> list = new ArrayList<>(HoloStore.data().holos);
		list.sort(Comparator.comparingInt(h -> h.id));
		return list;
	}

	static void openList(ServerPlayer player, int page) {
		if (!Gui.canOpen(player) || !allowed(player)) return;
		SimpleGui gui = Gui.chest(player, ROWS, "§8Holograms");
		@SuppressWarnings("unchecked")
		Gui.Pager<Holo>[] pager = new Gui.Pager[1];
		pager[0] = new Gui.Pager<>(gui, HoloGui::sorted, h -> entry(player, gui, h, pager[0]));
		int nav = pager[0].perPage();
		gui.setSlot(nav, Gui.button(Items.NETHER_STAR, "§a§lNew Hologram", () -> openCreate(player),
			"§7Place a leaderboard at", "§7your position (" + HoloType.idList().replace("|", ", ") + ")."));
		gui.setSlot(nav + 1, Gui.button(Items.SUNFLOWER, "§bRefresh All", () -> {
			if (!allowed(player)) return;
			refreshAll(player);
			pager[0].refresh();
		}, "§7Recalculate all leaderboards now."));
		gui.setSlot(nav + 8, Gui.closeButton(gui));
		pager[0].show(page);
		gui.open();
	}

	private static GuiElementBuilder entry(ServerPlayer player, SimpleGui gui, Holo h, Gui.Pager<Holo> pager) {
		Item icon = h.holoType().map(t -> t.icon).orElse(Items.BARRIER);
		boolean live = HoloManager.live(h.id) != null;
		String distance = HoloStore.sameDimension(h.dimension, HoloManager.dimensionId(player.level()))
			? "§7Distance: §f" + Msg.number(Math.round(Math.sqrt(h.distanceSqr(player.getX(), player.getY(), player.getZ())))) + " blocks"
			: "§7Other dimension";
		int id = h.id;
		return Gui.button(icon, "§e#" + id + " §f" + h.label(), type -> {
			if (type.isRight) confirmRemove(player, id, pager.page());
			else teleport(player, gui, id);
		},
			"§7Location: §f" + h.where(),
			distance,
			"§7Display: " + (live ? "§aloaded" : "§8not loaded"),
			"§7Created by: §f" + (h.createdBy.isEmpty() ? "?" : HoloText.cleanName(h.createdBy)),
			"",
			"§eLeft-click: §7Teleport",
			"§cRight-click: §7Remove");
	}

	private static void teleport(ServerPlayer player, SimpleGui gui, int id) {
		if (!allowed(player)) return;
		Holo h = HoloStore.data().byId(id);
		if (h == null) {
			Msg.error(player, "Hologram #" + id + " no longer exists.");
			Gui.failSound(player);
			openList(player, 0);
			return;
		}
		gui.close();
		HoloCommands.teleport(player, h);
	}

	private static void confirmRemove(ServerPlayer player, int id, int returnPage) {
		if (!allowed(player)) return;
		Holo h = HoloStore.data().byId(id);
		if (h == null) {
			Msg.error(player, "Hologram #" + id + " no longer exists.");
			Gui.failSound(player);
			openList(player, returnPage);
			return;
		}
		GuiElementBuilder preview = Gui.item(h.holoType().map(t -> t.icon).orElse(Items.BARRIER), "§e#" + id + " §f" + h.label(), "§7" + h.where());
		Gui.confirm(player, "§4Remove hologram #" + id + "?", preview, () -> {
			// Re-check everything: the state may have changed while the dialog was open.
			if (!allowed(player)) return;
			Holo removed = HoloCommands.remove(player.level().getServer(), id);
			if (removed == null) {
				Msg.error(player, "Hologram #" + id + " no longer exists.");
				Gui.failSound(player);
			} else {
				Msg.success(player, "Hologram " + HoloCommands.describe(removed) + " §aremoved.");
				Gui.successSound(player);
			}
			if (Gui.canOpen(player)) openList(player, returnPage);
		}, () -> openList(player, returnPage));
	}

	static void openCreate(ServerPlayer player) {
		if (!Gui.canOpen(player) || !allowed(player)) return;
		SimpleGui gui = Gui.chest(player, 3, "§8New Hologram");
		HoloType[] types = HoloType.values();
		for (int i = 0; i < types.length; i++) {
			HoloType t = types[i];
			gui.setSlot(10 + 2 * i, Gui.button(t.icon, "§e" + t.label, () -> create(player, t),
				"§7" + HoloText.clean(t.title).replace("★", "").trim(),
				"§7Appears at your position.",
				"",
				"§aClick to create"));
		}
		gui.setSlot(22, Gui.backButton(() -> openList(player, 0)));
		Gui.fillEmpty(gui);
		gui.open();
	}

	private static void create(ServerPlayer player, HoloType type) {
		if (!allowed(player)) return;
		HoloCommands.CreateResult r = HoloCommands.createAt(player, type);
		if (r.error() != null) {
			Msg.error(player, r.error());
			Gui.failSound(player);
		} else {
			Msg.success(player, "Hologram " + HoloCommands.describe(r.holo()) + " §acreated.");
			Gui.successSound(player);
		}
		openList(player, Integer.MAX_VALUE); // The new one is on the last page.
	}

	/** Permissions may have been revoked while the GUI was open. */
	private static boolean allowed(ServerPlayer player) {
		if (Perms.checkAdmin(player, HoloFeature.NODE)) return true;
		Msg.error(player, "You don't have permission to do that.");
		player.closeContainer();
		return false;
	}

	private static void refreshAll(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		HoloManager.ensureAll(server);
		int n = HoloManager.updateAll(server);
		Msg.success(player, "Holograms refreshed §7(" + n + " loaded)§a.");
	}
}
