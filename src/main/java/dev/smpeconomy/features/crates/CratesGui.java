package dev.smpeconomy.features.crates;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.CrateKeys;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.Services;
import dev.smpeconomy.core.Settings;
import dev.smpeconomy.core.test.TestPlayers;
import dev.smpeconomy.features.crates.CrateService.DeliverResult;
import dev.smpeconomy.features.crates.CrateService.DeliverStatus;
import dev.smpeconomy.features.crates.CrateService.OpenResult;
import dev.smpeconomy.features.crates.CrateService.PendingReward;
import dev.smpeconomy.features.crates.CratesConfig.CrateDef;
import dev.smpeconomy.features.crates.CratesConfig.RewardDef;

final class CratesGui {
	static final String TITLE = "§8Crates";
	static final int SLOT_INFO = 18;
	static final int SLOT_CLAIM = 22;
	static final int SLOT_MENU = 25;
	static final int SLOT_CLOSE = 26;
	private static final Random RANDOM = new Random();

	private CratesGui() {}

	static HolderLookup.Provider registries(ServerPlayer player) {
		return player.registryAccess();
	}

	static void openMain(ServerPlayer player) {
		if (!Gui.canOpen(player)) return;
		SimpleGui gui = Gui.chest(player, 3, TITLE);
		Gui.frame(gui);
		List<CrateDef> crates = CratesConfig.get().crates;
		int[] slots = crateSlots(crates.size());
		for (int i = 0; i < crates.size() && i < slots.length; i++) gui.setSlot(slots[i], crateButton(player, crates.get(i)));
		gui.setSlot(SLOT_INFO, Gui.item(net.minecraft.world.item.Items.BOOK, "§eHow to get Keys",
			"§7• §f/daily §7(day 7)", "§7• finish all Daily Tasks", "§7• Chat Games (with luck)", "§7• AFK Zone (with luck)",
			"§7• Missions (Hard/Elite)"));
		int pending = CrateService.deliverableCount(player.getUUID());
		if (pending > 0) {
			gui.setSlot(SLOT_CLAIM, Gui.button(net.minecraft.world.item.Items.CHEST_MINECART, "§6Claim Rewards", () -> {
				claim(player);
				openMain(player);
			}, "§7Waiting: §e" + pending, "", "§7Click to claim").glow());
		}
		Gui.menuButton(gui, SLOT_MENU);
		gui.setSlot(SLOT_CLOSE, Gui.closeButton(gui));
		Gui.fillEmpty(gui);
		gui.open();
	}

	static int[] crateSlots(int n) {
		int count = Math.max(0, Math.min(7, n));
		int[] slots = new int[count];
		if (count <= 4) {
			int start = 13 - (count - 1); // Spacing 2: 1 gives 13; 3 gives 11, 13, 15
			for (int i = 0; i < count; i++) slots[i] = start + 2 * i;
		} else {
			int start = 13 - count / 2;
			for (int i = 0; i < count; i++) slots[i] = start + i;
		}
		return slots;
	}

	private static GuiElementBuilder crateButton(ServerPlayer player, CrateDef crate) {
		long keys = CrateKeys.count(player.getUUID(), crate.id);
		long opened = PlayerDataService.getCounter(player.getUUID(), CrateService.OPENED_PREFIX + crate.id);
		List<String> lore = new ArrayList<>();
		lore.add("§7Keys: " + (keys > 0 ? "§e" : "§c") + Msg.number(keys));
		if (opened > 0) lore.add("§7Opened: §f" + Msg.number(opened));
		lore.add("");
		if (CratesConfig.problem() != null) lore.add("§cLocked right now (config)");
		lore.add("§aLeft-click §8» §7Open");
		lore.add("§eRight-click §8» §7Preview");
		GuiElementBuilder b = Gui.button(crate.iconItem(), crate.name, type -> {
			if (type.isRight) openPreview(player, crate.id, 0);
			else openCrate(player, crate.id);
		}, lore.toArray(String[]::new));
		if (keys > 0) b.glow();
		return b;
	}

	static void openPreview(ServerPlayer player, String crateId, int page) {
		if (!Gui.canOpen(player)) return;
		Optional<CrateDef> found = CratesConfig.crate(crateId);
		if (found.isEmpty()) {
			Msg.error(player, "Unknown crate.");
			return;
		}
		CrateDef crate = found.get();
		List<RewardDef> entries = new ArrayList<>();
		for (RewardDef r : crate.rewards) {
			if (r.weight > 0) entries.add(r);
		}
		entries.sort(Comparator.comparingInt((RewardDef r) -> r.weight).reversed());
		HolderLookup.Provider reg = registries(player);
		SimpleGui gui = Gui.chest(player, 6, "§8Preview: " + crate.name);
		Gui.Pager<RewardDef> pager = new Gui.Pager<>(gui, () -> entries, r -> previewIcon(crate, r, reg));
		gui.setSlot(45, Gui.backButton(() -> openMain(player)));
		long keys = CrateKeys.count(player.getUUID(), crate.id);
		gui.setSlot(53, Gui.button(net.minecraft.world.item.Items.TRIPWIRE_HOOK, "§aOpen Crate", () -> openCrate(player, crate.id),
			"§7Keys: " + (keys > 0 ? "§e" : "§c") + Msg.number(keys)));
		pager.show(page);
		gui.open();
	}

	private static GuiElementBuilder previewIcon(CrateDef crate, RewardDef r, HolderLookup.Provider reg) {
		GuiElementBuilder b = rewardIcon(r, reg);
		List<String> lore = new ArrayList<>();
		lore.add("");
		if (r.kind() == CratesConfig.Kind.ITEM && r.effectiveCount() > 1) lore.add("§7Amount: §f" + Msg.number(r.effectiveCount()));
		lore.add("§7Chance: §e" + CrateRewards.formatPercent(CrateRewards.chancePercent(crate, r)));
		valueOf(r, reg).ifPresent(v -> lore.add("§7Worth ~" + Msg.moneyStr(v)));
		if (r.announce) lore.add("§6✦ Announced in chat");
		return Gui.lore(b, lore.toArray(String[]::new));
	}

	/** Approximate value in cents: money directly, pristine items by market value. */
	static OptionalLong valueOf(RewardDef r, HolderLookup.Provider reg) {
		if (r.kind() == CratesConfig.Kind.MONEY) return OptionalLong.of(r.money);
		if (r.kind() != CratesConfig.Kind.ITEM || (r.enchantments != null && !r.enchantments.isEmpty()) || r.name != null) return OptionalLong.empty();
		Optional<net.minecraft.world.item.Item> item = Items.byId(r.item);
		if (item.isEmpty()) return OptionalLong.empty();
		OptionalLong each = Services.marketValue(item.get());
		if (each.isEmpty()) return OptionalLong.empty();
		long total = each.getAsLong() > Long.MAX_VALUE / Math.max(1, r.effectiveCount()) ? Long.MAX_VALUE : each.getAsLong() * r.effectiveCount();
		return OptionalLong.of(total);
	}

	static GuiElementBuilder rewardIcon(RewardDef r, HolderLookup.Provider reg) {
		try {
			return switch (r.kind()) {
				case MONEY -> Gui.item(CrateRewards.moneyIcon(r.money), CrateRewards.moneyText(r.money));
				case KEY -> Gui.item(net.minecraft.world.item.Items.TRIPWIRE_HOOK, CrateRewards.keyText(r.key, r.effectiveCount())).glow();
				case ITEM -> itemIcon(CrateRewards.buildItem(r, reg), r.effectiveCount());
			};
		} catch (RuntimeException e) {
			return Gui.item(net.minecraft.world.item.Items.BARRIER, "§cBroken entry", "§8" + r.id);
		}
	}

	static GuiElementBuilder pendingIcon(PendingReward p, HolderLookup.Provider reg) {
		if (p.isMoney()) return Gui.item(CrateRewards.moneyIcon(p.money), CrateRewards.moneyText(p.money));
		if (p.isKey()) return Gui.item(net.minecraft.world.item.Items.TRIPWIRE_HOOK, CrateRewards.keyText(p.key, Math.max(1, p.count)));
		Optional<ItemStack> t = CrateService.decode(p, reg);
		return t.map(s -> itemIcon(s, Math.max(1, p.count))).orElseGet(() -> Gui.item(net.minecraft.world.item.Items.CHEST, "§f" + p.entry));
	}

	private static GuiElementBuilder itemIcon(ItemStack template, int count) {
		ItemStack shown = template.copyWithCount(Math.max(1, Math.min(count, template.getMaxStackSize())));
		return Gui.display(shown);
	}

	static boolean openCrate(ServerPlayer player, String crateId) {
		if (!Gui.canOpen(player)) return false;
		HolderLookup.Provider reg = registries(player);
		OpenResult r = CrateService.open(player.getUUID(), crateId, RANDOM, reg);
		String name = CratesConfig.displayName(crateId);
		switch (r.status()) {
			case NO_KEY -> {
				Msg.error(player, "You don't have a key for the " + name + "§c.");
				Gui.failSound(player);
				return false;
			}
			case UNKNOWN_CRATE -> {
				Msg.error(player, "Unknown crate.");
				return false;
			}
			case LOCKED -> {
				Msg.error(player, "Crates are locked right now (config error). Please tell an admin.");
				Gui.failSound(player);
				return false;
			}
			case CONFIG_ERROR -> {
				Msg.error(player, "This crate is set up wrong – your key was not used.");
				Gui.failSound(player);
				return false;
			}
			case OK -> {
				// The reward is stored from here on: every branch must deliver it or leave it pending, never discard it
				CrateDef crate = CratesConfig.crate(crateId).orElseThrow();
				SpinGui gui;
				try {
					gui = new SpinGui(player, crate, r.reward(), reg);
				} catch (RuntimeException e) {
					SmpEconomy.LOG.error("[SMPEco] Could not create crate animation, delivering immediately", e);
					DeliverResult res = CrateService.deliver(player, r.reward().id, false, reg);
					reportSpin(player, crate, res, true);
					return true;
				}
				Gui.sound(player, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.CHEST_OPEN), 1f);
				if (!gui.open()) gui.finishNow();
				return true;
			}
		}
		return false;
	}

	/** {@code skipped} means the animation was skipped. */
	static void reportSpin(ServerPlayer player, CrateDef crate, DeliverResult res, boolean skipped) {
		if (res == null || res.status() == DeliverStatus.NOT_FOUND) return;
		HolderLookup.Provider reg = registries(player);
		PendingReward reward = res.reward();
		MutableComponent desc = CrateService.describe(reward, reg);
		switch (res.status()) {
			case DELIVERED -> {
				Msg.send(player, Msg.prefixed(Msg.legacy("§aYou won: ").append(desc).append(Msg.legacy(" §8(" + crate.name + "§8)"))));
				if (res.dropped() > 0) Msg.info(player, "§eInventory full – " + res.dropped() + " items were dropped at your feet.");
			}
			case KEPT_NO_SPACE -> Msg.send(player, Msg.prefixed(Msg.legacy("§eYour reward is waiting: ").append(desc)
				.append(Msg.legacy("§e. ")).append(Msg.clickRun("§7Make room, then §f/crates claim", "/crates claim", "§7Click to claim"))));
			case KEPT_UNAVAILABLE -> Msg.send(player, Msg.prefixed(Msg.legacy("§eYour reward is waiting: ").append(desc)
				.append(Msg.legacy("§e. §7You'll get it after respawning or on your next join."))));
			case KEPT_ERROR -> Msg.error(player, "Your reward couldn't be paid out right now – it stays saved. Please tell an admin.");
			default -> {
			}
		}
		if (reward.announce) announce(player, crate, desc);
	}

	static void announce(ServerPlayer winner, CrateDef crate, MutableComponent desc) {
		if (TestPlayers.isTestPlayer(winner.getUUID())) return;
		MinecraftServer server = SmpEconomy.server();
		if (server == null) return;
		MutableComponent line = Msg.prefixed(Msg.legacy("§e" + winner.getGameProfile().name() + " §7won ").append(desc.copy())
			.append(Msg.legacy("§7! §8(" + crate.name + "§8)")));
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (p == winner || !Settings.get(p, Settings.ANNOUNCEMENTS)) continue;
			p.sendSystemMessage(line);
		}
	}

	/**
	 * Delivers only what fits entirely in the inventory.
	 *
	 * @param manual player-triggered claim, which also reports when nothing is pending
	 */
	static void deliverPending(ServerPlayer player, boolean manual) {
		HolderLookup.Provider reg = registries(player);
		if (CrateService.deliverableCount(player.getUUID()) == 0) {
			if (manual) Msg.info(player, "You have no crate rewards waiting.");
			return;
		}
		List<DeliverResult> results = CrateService.deliverAll(player, false, reg);
		int kept = 0;
		boolean error = false;
		for (DeliverResult res : results) {
			if (res.delivered()) {
				Msg.send(player, Msg.prefixed(Msg.legacy("§aCrate reward received: ").append(CrateService.describe(res.reward(), reg))));
			} else if (res.kept()) {
				kept++;
				if (res.status() == DeliverStatus.KEPT_ERROR) error = true;
			}
		}
		if (results.stream().anyMatch(DeliverResult::delivered) && Gui.canOpen(player)) Gui.successSound(player);
		if (kept > 0) {
			Msg.send(player, Msg.prefixed(Msg.legacy("§e" + kept + (kept == 1 ? " crate reward is" : " crate rewards are") + " still waiting. ")
				.append(Msg.clickRun("§7Make room, then §f/crates claim", "/crates claim", "§7Click to claim"))));
			if (manual && !error) Gui.failSound(player);
			if (error) Msg.error(player, "A reward couldn't be paid out – it stays saved. Please tell an admin.");
		}
	}

	static void claim(ServerPlayer player) {
		deliverPending(player, true);
	}
}
