package dev.smpeconomy.features.stats;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Hooks;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.PriceGuard;
import dev.smpeconomy.core.Services;
import dev.smpeconomy.core.TimeUtil;

/** {@code /stats [player]}: display-only GUI (Gui.chest, no real slots, so no items go in or out). */
final class StatsGui {
	static final int ROWS = 5;
	static final int SLOT_HEAD = 4;
	static final int SLOT_KILLS = 19;
	static final int SLOT_DEATHS = 20;
	static final int SLOT_KD = 21;
	static final int SLOT_MOBS = 22;
	static final int SLOT_PLAYTIME = 23;
	static final int SLOT_MONEY = 24;
	static final int SLOT_MISC = 25;
	static final int SLOT_REFRESH = 36;
	static final int SLOT_TOP_KILLS = 38;
	static final int SLOT_TOP_DEATHS = 39;
	static final int SLOT_TOP_PLAYTIME = 40;
	static final int SLOT_TOP_MONEY = 41;
	static final int SLOT_TOP_MOBS = 42;
	static final int SLOT_MENU = 43;
	static final int SLOT_CLOSE = 44;

	/** No item is in every market day category, so this only shows bonuses valid for all items (e.g. trader rank). */
	private static final Item NEUTRAL_ITEM = Items.BARRIER;

	private StatsGui() {}

	static String title(String name) {
		return "§8Stats » " + name;
	}

	static void open(ServerPlayer viewer, UUID target) {
		if (!Gui.canOpen(viewer)) return;
		build(viewer, target).open();
	}

	/** Builds without opening; also used by selftests. */
	static SimpleGui build(ServerPlayer viewer, UUID target) {
		StatsService.View v = StatsService.view(target);
		SimpleGui gui = Gui.chest(viewer, ROWS, title(v.name()));

		gui.setSlot(SLOT_HEAD, head(v));
		gui.setSlot(SLOT_KILLS, Gui.item(Items.DIAMOND_SWORD, "§c§lPlayer Kills",
			"§f" + Msg.number(v.kills()),
			"§7Rank: " + rank(Leaderboard.Category.KILLS, target),
			"",
			"§7Kill Streak: §f" + Msg.number(v.streak()) + " §8(best §f" + Msg.number(v.bestStreak()) + "§8)"));
		gui.setSlot(SLOT_DEATHS, Gui.item(Items.SKELETON_SKULL, "§7§lDeaths",
			"§f" + Msg.number(v.deaths()),
			"§7Rank: " + rank(Leaderboard.Category.DEATHS, target),
			"",
			"§7By players: §f" + Msg.number(v.pvpDeaths())));
		gui.setSlot(SLOT_KD, Gui.item(Items.TOTEM_OF_UNDYING, "§6§lK/D",
			"§f" + StatsService.kd(v.kills(), v.deaths()),
			"§8Player kills per death"));
		gui.setSlot(SLOT_MOBS, Gui.item(Items.ZOMBIE_HEAD, "§2§lMob Kills",
			"§f" + Msg.number(v.mobKills()),
			"§7Rank: " + rank(Leaderboard.Category.MOBS, target)));
		List<String> playtime = new ArrayList<>(List.of(
			"§f" + TimeUtil.formatTicks(v.playtimeTicks()),
			"§7Rank: " + rank(Leaderboard.Category.PLAYTIME, target)));
		if (v.online() && v.sessionMs() > 0) playtime.add("§7This session: §f" + TimeUtil.formatDuration(v.sessionMs()));
		gui.setSlot(SLOT_PLAYTIME, Gui.item(Items.CLOCK, "§e§lPlaytime", playtime));
		gui.setSlot(SLOT_MONEY, Gui.item(Items.GOLD_INGOT, "§6§lBalance", moneyLore(target)));
		gui.setSlot(SLOT_MISC, Gui.item(Items.DIAMOND_PICKAXE, "§b§lOther", miscLore(v)));

		gui.setSlot(SLOT_REFRESH, Gui.button(Items.SUNFLOWER, "§aRefresh", () -> open(viewer, target), "§7Reload the values"));
		gui.setSlot(SLOT_TOP_KILLS, topButton(gui, viewer, Items.IRON_SWORD, Leaderboard.Category.KILLS));
		gui.setSlot(SLOT_TOP_DEATHS, topButton(gui, viewer, Items.BONE, Leaderboard.Category.DEATHS));
		gui.setSlot(SLOT_TOP_PLAYTIME, topButton(gui, viewer, Items.COMPASS, Leaderboard.Category.PLAYTIME));
		gui.setSlot(SLOT_TOP_MONEY, topButton(gui, viewer, Items.GOLD_NUGGET, Leaderboard.Category.MONEY));
		gui.setSlot(SLOT_TOP_MOBS, topButton(gui, viewer, Items.ROTTEN_FLESH, Leaderboard.Category.MOBS));
		Gui.menuButton(gui, SLOT_MENU);
		gui.setSlot(SLOT_CLOSE, Gui.closeButton(gui));
		Gui.frame(gui);
		Gui.fillEmpty(gui);
		return gui;
	}

	private static GuiElementBuilder head(StatsService.View v) {
		List<String> lore = new ArrayList<>();
		if (v.online()) {
			lore.add(v.sessionMs() > 0 ? "§a● Online §8(for " + TimeUtil.formatDuration(v.sessionMs()) + ")" : "§a● Online");
		} else if (v.lastSeen() > 0) {
			lore.add("§c● Offline §8(last seen " + TimeUtil.formatDuration(System.currentTimeMillis() - v.lastSeen()) + " ago)");
		} else {
			lore.add("§c● Offline");
		}
		if (v.firstSeen() > 0) lore.add("§7Tracked since: §f" + StatsService.date(v.firstSeen()));
		GuiElementBuilder b = Gui.item(Items.PLAYER_HEAD, "§e§l" + v.name(), lore);
		b.setProfile(profile(v));
		return b;
	}

	/** Online with skin data gives the resolved profile, otherwise by name and the client loads the skin. */
	private static ResolvableProfile profile(StatsService.View v) {
		ServerPlayer online = StatsService.online(v.id());
		if (online != null && !online.getGameProfile().properties().isEmpty()) return ResolvableProfile.createResolved(online.getGameProfile());
		if (!StatsService.UNKNOWN.equals(v.name()) && v.name().matches("[A-Za-z0-9_]{1,16}")) return ResolvableProfile.createUnresolved(v.name());
		return ResolvableProfile.createUnresolved(v.id());
	}

	private static String rank(Leaderboard.Category c, UUID target) {
		int r = Leaderboard.rankOf(Leaderboard.collect(c), target);
		return r > 0 ? "§e#" + r : "§8–";
	}

	private static List<String> moneyLore(UUID target) {
		List<String> lore = new ArrayList<>();
		lore.add("§a" + Money.format(EconomyService.balance(target)));
		lore.add("§7Rank: " + rank(Leaderboard.Category.MONEY, target));
		Optional<Services.Bank> bank = Services.find(Services.Bank.class);
		if (bank.isPresent()) {
			try {
				lore.add("§7Bank: §a" + Money.format(bank.get().balance(target)));
			} catch (RuntimeException e) {
				dev.smpeconomy.SmpEconomy.LOG.error("[SMPEco] stats: bank service threw", e);
			}
		}
		return lore;
	}

	private static List<String> miscLore(StatsService.View v) {
		List<String> lore = new ArrayList<>();
		lore.add("§7Blocks mined: §f" + Msg.number(v.blocksMined()));
		lore.add("§7Distance walked: §f" + StatsService.distance(v.walkedCm()));
		Optional<Services.TraderRanks> ranks = Services.find(Services.TraderRanks.class);
		if (ranks.isPresent()) {
			try {
				double bonus = ranks.get().bonusPercent(v.id());
				lore.add("§7Trader Rank: " + ranks.get().rankName(v.id()) + (bonus > 0 ? " §a(" + PriceGuard.percent(1.0 + bonus / 100.0) + ")" : ""));
				lore.add("§7Lifetime sales: §a" + Money.format(ranks.get().lifetimeSales(v.id())));
			} catch (RuntimeException e) {
				dev.smpeconomy.SmpEconomy.LOG.error("[SMPEco] stats: TraderRanks service threw", e);
			}
		}
		List<Hooks.Part> parts = Hooks.sellParts(v.id(), NEUTRAL_ITEM);
		if (parts.isEmpty()) {
			lore.add("§7Sell bonuses: §8none");
		} else {
			lore.add("§7Sell bonuses:");
			for (Hooks.Part p : parts) lore.add(" " + (p.factor() >= 1 ? "§a" : "§c") + PriceGuard.percent(p.factor()) + " §7" + p.label());
		}
		return lore;
	}

	private static GuiElementBuilder topButton(SimpleGui gui, ServerPlayer viewer, Item icon, Leaderboard.Category c) {
		return Gui.button(icon, "§eLeaderboard: " + c.label, () -> {
			gui.close();
			Leaderboard.send(viewer::sendSystemMessage, c, 1, viewer.getUUID());
		}, "§7Click: top list in chat", "§8/" + Leaderboard.COMMAND + " " + c.id);
	}
}
