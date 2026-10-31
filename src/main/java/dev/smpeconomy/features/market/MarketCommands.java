package dev.smpeconomy.features.market;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.UUID;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import dev.smpeconomy.core.CommandRegistry;
import dev.smpeconomy.core.compat.Mc;
import dev.smpeconomy.core.Args;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Perms;
import dev.smpeconomy.core.PriceGuard;
import dev.smpeconomy.core.TimeUtil;
import dev.smpeconomy.core.Treasury;

/**
 * {@code /market}: GUI. {@code /market value}: market value of the held item. {@code /market admin
 * status|fill|clear|offers|due|plans|sweep|recompute|value <item>|drift <item> [factor]|budget <player> [reset]| bargain [reset |
 * <item> [count]]} (smpeconomy.admin.market).
 */
final class MarketCommands {
	private MarketCommands() {}

	static void register() {
		CommandRegistry.register((dispatcher, registryAccess, environment) -> {
			register(dispatcher, registryAccess, "market");
		});
	}

	private static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext registryAccess, String name) {
		LiteralArgumentBuilder<CommandSourceStack> admin = Commands.literal("admin").requires(Perms.requireAdmin(MarketFeature.PERM_ADMIN))
			.then(Commands.literal("status").executes(MarketCommands::status))
			.then(Commands.literal("fill").executes(MarketCommands::fill))
			.then(Commands.literal("clear").executes(MarketCommands::clear))
			.then(Commands.literal("due").executes(MarketCommands::due))
			.then(Commands.literal("plans").executes(MarketCommands::plans))
			.then(Commands.literal("offers").executes(MarketCommands::offers))
			.then(Commands.literal("sweep").executes(MarketCommands::sweep))
			.then(Commands.literal("budget")
				.then(Commands.argument("player", StringArgumentType.word()).suggests(Args.knownPlayers())
					.executes(ctx -> budget(ctx, false))
					.then(Commands.literal("reset").executes(ctx -> budget(ctx, true)))))
			.then(bargainTree("bargain", registryAccess))
			.then(Commands.literal("recompute").executes(ctx -> {
				ValueEngine.rebuild(ctx.getSource().getServer());
				Msg.success(ctx.getSource(), "Market values recalculated (" + ValueEngine.model().neutral().size() + " items).");
				return 1;
			}))
			.then(Commands.literal("value")
				.then(Commands.argument("item", ItemArgument.item(registryAccess)).executes(MarketCommands::value)))
			.then(Commands.literal("drift")
				.then(Commands.argument("item", ItemArgument.item(registryAccess)).executes(ctx -> drift(ctx, Double.NaN))
					.then(Commands.argument("factor", DoubleArgumentType.doubleArg(0.1, 5.0))
						.executes(ctx -> drift(ctx, DoubleArgumentType.getDouble(ctx, "factor"))))));
		dispatcher.register(Commands.literal(name).requires(Perms.require(MarketFeature.PERM))
			.executes(MarketCommands::openGui)
			.then(Commands.literal("value").executes(MarketCommands::handValue))
			.then(admin));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> bargainTree(String name, CommandBuildContext registryAccess) {
		return Commands.literal(name).executes(MarketCommands::bargainStatus)
			.then(Commands.literal("reset").executes(MarketCommands::bargainReset))
			.then(Commands.argument("item", ItemArgument.item(registryAccess)).executes(ctx -> bargain(ctx, 1))
				.then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
					.executes(ctx -> bargain(ctx, IntegerArgumentType.getInteger(ctx, "count")))));
	}

	private static int handValue(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		sendHandValue(ctx.getSource().getPlayerOrException());
		return 1;
	}

	private static int openGui(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		MarketGui.open(ctx.getSource().getPlayerOrException());
		return 1;
	}

	static void sendHandValue(ServerPlayer player) {
		ItemStack hand = player.getMainHandItem();
		if (hand.isEmpty()) {
			Msg.error(player, "Hold an item in your hand.");
			return;
		}
		OptionalLong unit = ValueEngine.unitValue(hand);
		if (unit.isEmpty()) {
			Msg.send(player, Msg.prefixed(Msg.legacy("§7").append(Msg.itemName(hand))
				.append(Msg.legacy(" §7has no market value – NPCs don't trade it."))));
			return;
		}
		long v = unit.getAsLong();
		String stack = hand.getCount() > 1 ? " §8(stack: §a" + Money.format(ValueEngine.saturatingMul(v, hand.getCount())) + "§8)" : "";
		Msg.send(player, Msg.prefixed(Msg.legacy("§7Market value of ").append(Msg.itemName(hand))
			.append(Msg.legacy("§7: §a" + Money.format(v) + " §8each" + stack))));
		sendNpcBuyerCap(player, hand, v);
		Msg.info(player, MarketGui.trendLine(Drift.current(hand.getItem(), System.currentTimeMillis()), hand));
	}

	static final String PLAYERS_ONLY_NOTE = "§8Only players pay for enchantments and fixed collector values (Totem, music disc …).";

	/**
	 * What NPC buyers pay at most for the whole held stack. {@code /ah sell <price>} takes the stack total, so a unit price here
	 * would invite players to dump 64 diamonds for the price of one.
	 */
	private static void sendNpcBuyerCap(ServerPlayer player, ItemStack hand, long unitValue) {
		MarketConfig c = MarketConfig.get();
		if (MarketFeature.bridge() == null || !c.npcBuyersEnabled) {
			Msg.info(player, "§7NPC buyers aren't active right now.");
			return;
		}
		long buyerValue = ValueEngine.buyerUnitValue(hand).orElse(0);
		boolean partial = buyerValue < unitValue;
		boolean fever = NpcBuyers.fever();
		long capUnit = buyerValue <= 0 ? 0 : PriceGuard.maxNpcBuyPrice(player.getUUID(), hand.getItem(), buyerValue,
			fever ? PriceGuard.NPC_BUY_MARKET_FACTOR_MAX : PriceGuard.NPC_BUY_MARKET_FACTOR);
		int count = hand.getCount();
		long capTotal = ValueEngine.saturatingMul(Math.max(0, capUnit), count);
		if (capTotal <= 0) {
			Msg.info(player, "§7NPC buyers don't buy this item.");
			if (partial) Msg.info(player, PLAYERS_ONLY_NOTE);
			return;
		}
		String what = count > 1 ? "your stack of §f" + count : "this item";
		String cmd = "/ah sell " + Money.commandAmount(capTotal);
		Msg.info(player, "§7NPC buyers pay up to §a" + Money.format(capTotal) + " §7for " + what + " §8(total price)"
			+ (fever ? " §6(Auction Fever!)" : "") + "§7.");
		Msg.send(player, Msg.prefixed(Msg.legacy("§7Sell it: ").append(Msg.clickSuggest("§e" + cmd, cmd,
			"§7Price = §ftotal price §7for the whole stack in your hand.\n§7The cheaper it is, the faster an NPC buys it."))
			.append(Msg.legacy(" §8(price for the whole stack)"))));
		if (partial) Msg.info(player, PLAYERS_ONLY_NOTE);
		long budget = Treasury.maxSpendPerHour();
		long maxPrice = NpcBuyers.sellerMaxPrice(budget, c);
		if (capTotal > maxPrice) {
			Msg.info(player, "§8NPC buyers pay at most §a" + Money.format(maxPrice) + " §8per listing – list fewer at once.");
		}
		long room = NpcBuyers.sellerRoom(player.getUUID(), budget, System.currentTimeMillis(), c);
		if (room < Math.min(capTotal, maxPrice)) {
			Msg.info(player, "§8Your NPC budget is almost used up: §a" + Money.format(room) + " §8left (max §a"
				+ Money.format(NpcBuyers.sellerHourCap(budget, c)) + " §8per hour, §a" + Money.format(NpcBuyers.sellerDayCap(budget, c))
				+ " §8per day).");
		}
	}

	private static MarketBridge requireBridge(CommandSourceStack src) {
		MarketBridge b = MarketFeature.bridge();
		if (b == null) Msg.error(src, "No Auction House connected (market only provides market values).");
		return b;
	}

	private static int status(CommandContext<CommandSourceStack> ctx) {
		MarketData d = MarketData.get();
		MarketBridge b = MarketFeature.bridge();
		int offers = b == null ? 0 : b.npcListings(MarketBridge.SOURCE).size();
		Msg.send(ctx.getSource(), Msg.legacy("§7market bridge=" + (b != null) + " auction=" + MarketFeature.auctionActive(ctx.getSource().getServer())
			+ " offers=" + offers + " target=" + d.targetOffers
			+ " plans=" + d.plans.size() + " rejected=" + d.rejected.size() + " drifts=" + d.drift.size() + " buys=" + d.npcBuys
			+ " buyCents=" + d.npcBuyCents + " sales=" + d.npcSales + " saleCents=" + d.npcSaleCents + " fever=" + NpcBuyers.fever()
			+ " values=" + ValueEngine.model().neutral().size() + " bargains=" + NpcSellers.activeBargains()
			+ " bargainBudget=" + NpcSellers.budgetUsed(System.currentTimeMillis()) + "/" + MarketConfig.get().bargainDailyBudget
			+ " bargainsCreated=" + d.bargainsCreated + " bargainsSold=" + d.bargainsSold));
		return 1;
	}

	private static int bargainStatus(CommandContext<CommandSourceStack> ctx) {
		long now = System.currentTimeMillis();
		MarketConfig c = MarketConfig.get();
		MarketData d = MarketData.get();
		StringBuilder out = new StringBuilder("§7market bargains active=" + NpcSellers.activeBargains() + "/" + c.bargainMaxActive
			+ " used=" + NpcSellers.budgetUsed(now) + " budget=" + c.bargainDailyBudget + " left=" + NpcSellers.budgetLeft(now, c));
		d.bargains.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue().createdAt, a.getValue().createdAt)).limit(20)
			.forEach(e -> out.append("\n§8 #").append(e.getKey()).append(' ').append(e.getValue().count).append("x ").append(e.getValue().itemId)
				.append(" price=").append(e.getValue().price).append(" potential=").append(e.getValue().potential)
				.append(e.getValue().shown ? " shown" : " hidden")
				.append(e.getValue().soldAt > 0 ? " sold " + TimeUtil.formatDuration(now - e.getValue().soldAt) + " ago" : " active"));
		Msg.send(ctx.getSource(), Msg.legacy(out.toString()));
		return 1;
	}

	/** Removes sold bargains from the flip budget; active ones stay booked. */
	private static int bargainReset(CommandContext<CommandSourceStack> ctx) {
		MarketData d = MarketData.get();
		int before = d.bargains.size();
		d.bargains.values().removeIf(b -> b.soldAt > 0);
		MarketData.markDirty();
		Msg.success(ctx.getSource(), "market bargains reset removed=" + (before - d.bargains.size()) + " active=" + NpcSellers.activeBargains());
		return 1;
	}

	/** Creates a bargain immediately (visible, lowest allowed price); budget and floors apply. */
	private static int bargain(CommandContext<CommandSourceStack> ctx, int count) {
		MarketBridge b = requireBridge(ctx.getSource());
		if (b == null) return 0;
		Item item = Mc.inputItem(ItemArgument.getItem(ctx, "item"));
		ItemStack stack = new ItemStack(item, Math.max(1, Math.min(count, item.getDefaultMaxStackSize())));
		MarketBridge.Listing[] out = new MarketBridge.Listing[1];
		String error = NpcSellers.createBargainNow(b, stack, System.currentTimeMillis(), out);
		if (error != null) {
			Msg.error(ctx.getSource(), "No bargain: " + error);
			return 0;
		}
		MarketBridge.Listing l = out[0];
		long sell = PriceGuard.effectiveSellPrice(ValueEngine.NEUTRAL, item).orElse(0);
		Msg.success(ctx.getSource(), "market bargain id=" + l.id() + " count=" + l.count() + " price=" + l.price()
			+ " floor=" + ValueEngine.saleFloor(null, l.item()) + " bargainFloor=" + ValueEngine.bargainFloor(null, l.item())
			+ " sell=" + sell + " potential=" + ValueEngine.bargainPotential(l.item(), l.price()));
		return 1;
	}

	private static int fill(CommandContext<CommandSourceStack> ctx) {
		MarketBridge b = requireBridge(ctx.getSource());
		if (b == null) return 0;
		int n = NpcSellers.fill(b, System.currentTimeMillis(), MarketFeature.RNG);
		Msg.success(ctx.getSource(), "market filled=" + n + " offers=" + b.npcListings(MarketBridge.SOURCE).size());
		return 1;
	}

	private static int clear(CommandContext<CommandSourceStack> ctx) {
		MarketBridge b = requireBridge(ctx.getSource());
		if (b == null) return 0;
		int n = NpcSellers.clear(b);
		Msg.success(ctx.getSource(), "market cleared=" + n);
		return 1;
	}

	private static int due(CommandContext<CommandSourceStack> ctx) {
		long now = System.currentTimeMillis();
		int n = 0;
		for (MarketData.BuyPlan p : MarketData.get().plans.values()) {
			p.dueAt = now;
			n++;
		}
		MarketData.markDirty();
		Msg.success(ctx.getSource(), "market due=" + n);
		return 1;
	}

	private static int plans(CommandContext<CommandSourceStack> ctx) {
		long now = System.currentTimeMillis();
		MarketData d = MarketData.get();
		List<MarketData.BuyPlan> list = new ArrayList<>(d.plans.values());
		list.sort(Comparator.comparingLong(p -> p.dueAt));
		StringBuilder out = new StringBuilder("§7market plans=" + list.size() + " rejected=" + d.rejected.size());
		for (int i = 0; i < list.size() && i < 20; i++) {
			MarketData.BuyPlan p = list.get(i);
			out.append("\n§8 #").append(p.listingId).append(' ').append(EconomyService.nameOrUnknown(p.seller)).append(' ').append(p.count)
				.append("x ").append(p.itemId).append(" price=").append(p.price).append(" in ").append(TimeUtil.formatDuration(p.dueAt - now))
				.append(p.fever ? " fever" : "").append(p.retries > 0 ? " retries=" + p.retries : "");
		}
		d.rejected.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue().at, a.getValue().at)).limit(10)
			.forEach(e -> out.append("\n§8 #").append(e.getKey()).append(" rejected: ").append(e.getValue().reason));
		Msg.send(ctx.getSource(), Msg.legacy(out.toString()));
		return 1;
	}

	private static int offers(CommandContext<CommandSourceStack> ctx) {
		MarketBridge b = requireBridge(ctx.getSource());
		if (b == null) return 0;
		long now = System.currentTimeMillis();
		List<MarketBridge.Listing> list = b.npcListings(MarketBridge.SOURCE);
		StringBuilder out = new StringBuilder("§7market offers=" + list.size());
		for (MarketBridge.Listing l : list) {
			long unit = ValueEngine.unitValue(l.item()).orElse(0);
			out.append("\n§8 #").append(l.id()).append(' ').append(l.count()).append("x ").append(NpcSellers.offerKey(l.item()))
				.append(" price=").append(l.price()).append(" value=").append(ValueEngine.saturatingMul(unit, l.count()))
				.append(" floor=").append(ValueEngine.saleFloor(null, l.item())).append(" in ").append(TimeUtil.formatDuration(l.expiresAt() - now))
				.append(l.bargain() ? (l.note().isEmpty() ? " bargain(hidden)" : " bargain") : "")
				.append(" §7").append(l.sellerName());
		}
		Msg.send(ctx.getSource(), Msg.legacy(out.toString()));
		return 1;
	}

	private static int sweep(CommandContext<CommandSourceStack> ctx) {
		MarketBridge b = requireBridge(ctx.getSource());
		if (b == null) return 0;
		NpcBuyers.sweep(b, System.currentTimeMillis(), MarketFeature.RNG, true);
		Msg.success(ctx.getSource(), "market sweep plans=" + MarketData.get().plans.size());
		return 1;
	}

	private static int budget(CommandContext<CommandSourceStack> ctx, boolean reset) throws CommandSyntaxException {
		UUID id = Args.knownPlayer(ctx, "player");
		String name = EconomyService.nameOrUnknown(id);
		if (reset) {
			boolean had = NpcBuyers.resetSellerBudget(id);
			Msg.success(ctx.getSource(), "market budget reset player=" + name + " had=" + had);
			return 1;
		}
		long now = System.currentTimeMillis();
		MarketConfig c = MarketConfig.get();
		long budget = Treasury.maxSpendPerHour();
		Msg.send(ctx.getSource(), Msg.legacy("§7market budget player=" + name + " hour=" + NpcBuyers.sellerPaid(id, now, 60)
			+ " day=" + NpcBuyers.sellerPaid(id, now, 24 * 60) + " hourCap=" + NpcBuyers.sellerHourCap(budget, c)
			+ " dayCap=" + NpcBuyers.sellerDayCap(budget, c) + " room=" + NpcBuyers.sellerRoom(id, budget, now, c)));
		return 1;
	}

	private static int value(CommandContext<CommandSourceStack> ctx) {
		Item item = Mc.inputItem(ItemArgument.getItem(ctx, "item"));
		ValueEngine.Info i = ValueEngine.info(item);
		ItemStack one = new ItemStack(item);
		Msg.send(ctx.getSource(), Msg.legacy(String.format(Locale.ROOT,
			"§7market value item=%s value=%d source=%s neutral=%.2f drift=%.3f mod=%.3f cap=%d fixed=%s buyer=%d floor=%d bargainFloor=%d payoutMax=%d",
			Items.id(item), i.value(), i.source(), i.neutral(), i.drift(), i.modifier(), i.cap(), ValueEngine.fixedValue(item),
			ValueEngine.buyerUnitValue(one).orElse(0), ValueEngine.saleFloor(null, one), ValueEngine.bargainFloor(null, one),
			ValueEngine.maxSystemPayoutUnit(one))));
		return 1;
	}

	private static int drift(CommandContext<CommandSourceStack> ctx, double factor) {
		Item item = Mc.inputItem(ItemArgument.getItem(ctx, "item"));
		long now = System.currentTimeMillis();
		if (!Double.isNaN(factor)) Drift.set(Drift.key(item), factor, now);
		Msg.send(ctx.getSource(), Msg.legacy(String.format(Locale.ROOT, "§7market drift item=%s factor=%.3f", Items.id(item),
			Drift.current(item, now))));
		return 1;
	}
}
