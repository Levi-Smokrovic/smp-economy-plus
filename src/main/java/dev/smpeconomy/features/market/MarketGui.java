package dev.smpeconomy.features.market;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.CommandRegistry;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.PriceGuard;
import dev.smpeconomy.core.PriceModifiers;
import dev.smpeconomy.core.Treasury;

/**
 * {@code /market}: market values of trader goods with drift trend, active price events, what NPC buyers pay at most and from
 * which price NPC sellers sell. Display only, no real slots. The last row is full: the page indicator (49) is the main menu
 * button if /menu exists.
 */
final class MarketGui {
	static final String TITLE = "§8Market §7» Market Values";

	private MarketGui() {}

	static void open(ServerPlayer player) {
		if (!Gui.canOpen(player)) return;
		SimpleGui gui = Gui.chest(player, 6, TITLE);
		List<ItemStack> entries = entries();
		MarketBridge b = MarketFeature.bridge();
		List<MarketBridge.Listing> npcOffers = b == null ? List.of() : b.npcListings(MarketBridge.SOURCE);
		// Count once per open, instead of copying the whole AH list per displayed entry
		Map<String, Integer> counts = new HashMap<>();
		for (MarketBridge.Listing l : npcOffers) counts.merge(NpcSellers.offerKey(l.item()), 1, Integer::sum);
		Gui.Pager<ItemStack> pager = new Gui.Pager<>(gui, () -> entries, stack -> render(player, stack, counts)).withMenuButton();
		gui.setSlot(45, Gui.item(net.minecraft.world.item.Items.BOOK, "§6How the Market Works",
			"§7Every item has a §fmarket value§7.",
			"§7List something cheap in the AH",
			"§7(§e/ah sell <total price>§7 – price for the",
			"§7whole stack) and an §eNPC buyer§7 often",
			"§7buys it – the cheaper, the faster (5–90 min).",
			"§7They pay from the §6Treasury§7,",
			"§7minus AH tax.",
			"§7Only players pay for enchantments",
			"§7and collectibles (Totem, music disc …).",
			"",
			"§7NPC Traders sell their own goods",
			"§7(marked §e[Market]§7) – usually pricey.",
			"§7Some miscalculate: §a★ Bargain§7!",
			"§7Not every one is marked – compare",
			"§7the price and §fmarket value §7in the lore.",
			"§7You can resell what you buy",
			"§7(AH, NPC Orders, /sell) – flip it!",
			"§7High demand §a▲ §7raises prices, lots of",
			"§7sales to NPCs §c▼ §7lower them."));
		gui.setSlot(46, Gui.button(net.minecraft.world.item.Items.SPYGLASS, "§eValue My Item", () -> {
			MarketCommands.sendHandValue(player);
			Gui.successSound(player);
		}, "§7Shows the market value of the", "§7item in your hand in chat."));
		long budget = Treasury.maxSpendPerHour();
		MarketConfig c = MarketConfig.get();
		gui.setSlot(47, Gui.item(net.minecraft.world.item.Items.GOLD_INGOT, "§6Treasury",
			"§7Balance: " + Money.format(Treasury.balance()),
			"§7NPC budget per hour: §a" + Money.format(budget),
			"§7Still available: §a" + Money.format(Treasury.allowance()),
			"",
			"§7Max per player: §a" + Money.format(NpcBuyers.sellerHourCap(budget, c)) + "§7/h",
			"§7and §a" + Money.format(NpcBuyers.sellerDayCap(budget, c)) + "§7/day.",
			"§7You have left: §a" + Money.format(NpcBuyers.sellerRoom(player.getUUID(), budget, System.currentTimeMillis(), c))));
		if (NpcBuyers.fever()) {
			gui.setSlot(51, Gui.item(net.minecraft.world.item.Items.BLAZE_POWDER, "§6§lAuction Fever!",
				"§7NPC buyers are buying twice as", "§7fast and pay up to market value §a×1.25§7."));
		}
		if (b == null) {
			gui.setSlot(52, Gui.item(net.minecraft.world.item.Items.EMERALD, "§eNPC listings in the AH: §f0", "§8Auction House unavailable"));
		} else {
			gui.setSlot(52, Gui.button(net.minecraft.world.item.Items.EMERALD, "§eNPC listings in the AH: §f" + npcOffers.size(),
				() -> openAuctionHouse(player), "§7Open the Auction House with §e/ah", "§8(or /auction)", "",
				"§eClick to open"));
		}
		gui.setSlot(53, Gui.closeButton(gui));
		pager.show(0);
		gui.open();
	}

	/** Opens the auction house as the player, so permissions apply as if typed. */
	private static void openAuctionHouse(ServerPlayer player) {
		var server = SmpEconomy.server();
		if (server == null || !Gui.canOpen(player)) return;
		CommandRegistry.run(player, MarketFeature.AUCTION_COMMAND);
	}

	static List<ItemStack> entries() {
		List<ItemStack> list = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		var server = SmpEconomy.server();
		if (server == null) return list;
		for (MarketConfig.PoolEntry e : MarketConfig.get().pool) {
			for (int i = 0; i < e.items.size(); i++) {
				MarketConfig.PoolEntry single = new MarketConfig.PoolEntry();
				single.items = List.of(e.items.get(i));
				single.min = 1;
				single.max = 1;
				single.enchantment = e.enchantment;
				single.level = e.level;
				NpcSellers.buildStack(single, MarketFeature.RNG, server.registryAccess()).ifPresent(stack -> {
					if (seen.add(NpcSellers.offerKey(stack)) && ValueEngine.unitValue(stack).isPresent()) list.add(stack);
				});
			}
		}
		return list;
	}

	static GuiElementBuilder render(ServerPlayer player, ItemStack stack, Map<String, Integer> offerCounts) {
		List<String> lore = new ArrayList<>();
		lore.add("");
		OptionalLong value = ValueEngine.unitValue(stack);
		if (value.isEmpty()) {
			lore.add("§7No market value");
			return Gui.display(stack, lore.toArray(String[]::new));
		}
		long v = value.getAsLong();
		lore.add("§7Market value: §a" + Money.format(v) + " §8each");
		lore.add(trendLine(Drift.current(stack.getItem(), System.currentTimeMillis()), stack));
		for (PriceModifiers.Active a : PriceModifiers.active(stack.getItem())) {
			lore.add((a.current() >= 1 ? "§a▲ " : "§c▼ ") + PriceGuard.percent(a.current()) + " " + a.label());
		}
		boolean fever = NpcBuyers.fever();
		long buyerValue = ValueEngine.buyerUnitValue(stack).orElse(0);
		long buyCap = buyerValue <= 0 ? 0 : PriceGuard.maxNpcBuyPrice(player.getUUID(), stack.getItem(), buyerValue,
			fever ? PriceGuard.NPC_BUY_MARKET_FACTOR_MAX : PriceGuard.NPC_BUY_MARKET_FACTOR);
		if (buyCap > 0) lore.add("§7NPC buyers pay up to: §a" + Money.format(buyCap) + " §8each" + (buyerValue < v ? " §8(base value only)" : ""));
		else lore.add("§7NPC buyers: §cdon't buy this §8(players only)");
		if (MarketConfig.neverSold(stack.getItem())) lore.add("§7NPC Traders: §cnever sell this §8(villager currency)");
		else {
			ItemStack one = stack.copyWithCount(1);
			long normal = ValueEngine.saleFloor(player.getUUID(), one);
			lore.add("§7NPC Traders sell from: §e" + Money.format(normal) + " §8each");
			// The lowest a dumb trader lists the item at (like NpcSellers: normal price × bargainPriceMin, never below /sell). Not the bare
			// floor, which is only 1 cent for books and collector items without a /sell price.
			MarketConfig c = MarketConfig.get();
			long floor = ValueEngine.saleFloor(null, one);
			long bargain = NpcSellers.lowestBargainPrice(one, v, floor, c);
			if (NpcSellers.isRealBargain(bargain, floor, c)) lore.add("§8Bargains (rare) from " + Money.format(bargain) + " each");
		}
		int n = offerCounts.getOrDefault(NpcSellers.offerKey(stack), 0);
		if (n > 0) lore.add("§e" + n + " NPC listing" + (n == 1 ? "" : "s") + " in the AH");
		return Gui.display(stack, lore.toArray(String[]::new));
	}

	static String trendLine(double itemDrift, ItemStack stack) {
		double d = itemDrift;
		List<String> ench = ValueEngine.enchantmentIds(stack);
		if (!ench.isEmpty()) d = Drift.current(Drift.enchantKey(ench.getFirst()), System.currentTimeMillis());
		long pct = Math.round((d - 1.0) * 100);
		if (pct >= 1) return "§a▲ +" + pct + "% demand";
		if (pct <= -1) return "§c▼ " + pct + "% oversupply";
		return "§7● stable";
	}
}
