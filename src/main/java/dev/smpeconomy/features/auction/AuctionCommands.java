package dev.smpeconomy.features.auction;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import dev.smpeconomy.core.CommandRegistry;
import dev.smpeconomy.core.compat.Mc;
import dev.smpeconomy.core.Args;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Perms;
import dev.smpeconomy.features.auction.AuctionService.CreateResult;
import dev.smpeconomy.features.auction.AuctionService.Listing;
import dev.smpeconomy.features.auction.AuctionService.Result;

/**
 * {@code /ah}, {@code /ah sell <total>} (see {@link AuctionSellConfirm}), {@code /ah search <text>}, {@code
 * /ah my}, and the {@code /ah admin} subcommands. Admin output is machine-readable for the e2e scripts.
 */
final class AuctionCommands {
	static final String NODE = "smpeconomy.ah";
	static final String NODE_SELL = "smpeconomy.ah.sell";
	static final String NODE_ADMIN = "smpeconomy.admin.auction";
	static final String ADMIN_NPC_NAME = "§e[Market] Test Trader";
	static final String ARG_PRICE = "total_price";
	/**
	 * Every name gets the full tree instead of a Brigadier redirect, because a redirect would not open the GUI for
	 * the bare alias.
	 */
	static final List<String> NAMES = List.of("ah", "auction");

	private AuctionCommands() {}

	static void register() {
		CommandRegistry.register((dispatcher, registryAccess, environment) -> {
			for (String name : NAMES) dispatcher.register(tree(name, registryAccess));
		});
	}

	private static LiteralArgumentBuilder<CommandSourceStack> tree(String name, CommandBuildContext registryAccess) {
		return Commands.literal(name)
				.requires(Perms.require(NODE))
				.executes(AuctionCommands::browse)
				.then(Commands.literal("sell")
					.requires(Perms.require(NODE_SELL))
					.executes(AuctionCommands::sellUsage)
					// The argument name is the only hint in tab completion that the price covers the whole stack
					.then(Commands.argument(ARG_PRICE, StringArgumentType.greedyString()).executes(AuctionCommands::sell)))
				.then(Commands.literal("search")
					.then(Commands.argument("text", StringArgumentType.greedyString()).executes(AuctionCommands::search)))
				.then(Commands.literal("my").executes(AuctionCommands::my))
				.then(Commands.literal("admin")
					.requires(Perms.requireAdmin(NODE_ADMIN))
					.then(Commands.literal("info")
						.executes(ctx -> info(ctx, null))
						.then(Commands.argument("player", StringArgumentType.word()).suggests(Args.knownPlayers())
							.executes(ctx -> info(ctx, Args.knownPlayer(ctx, "player")))))
					.then(Commands.literal("expire")
						.then(Commands.argument("player", StringArgumentType.word()).suggests(Args.knownPlayers())
							.executes(AuctionCommands::expire)))
					.then(Commands.literal("remove")
						.then(Commands.argument("id", LongArgumentType.longArg(1)).executes(AuctionCommands::remove)))
					.then(Commands.literal("npc")
						.then(Commands.argument("item", ItemArgument.item(registryAccess))
							.then(Commands.argument("count", IntegerArgumentType.integer(1, 99))
								.then(Commands.argument("price", StringArgumentType.greedyString()).executes(AuctionCommands::npc)))))
					.then(Commands.literal("clearnpc").executes(AuctionCommands::clearNpc))
					.then(Commands.literal("purge")
						.then(Commands.argument("player", StringArgumentType.word()).suggests(Args.knownPlayers())
							.executes(AuctionCommands::purge))));
	}

	private static int browse(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		AuctionGui.state(p).query = ""; // Sort and filter stay
		AuctionService.processExpired();
		AuctionGui.openBrowse(p, 0);
		return 1;
	}

	private static int my(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		AuctionService.processExpired();
		AuctionGui.openMy(p, 0);
		return 1;
	}

	private static int search(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		String text = StringArgumentType.getString(ctx, "text").trim();
		AuctionGui.state(p).query = text.length() > 50 ? text.substring(0, 50) : text;
		AuctionService.processExpired();
		AuctionGui.openBrowse(p, 0);
		return 1;
	}

	private static int sellUsage(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		Msg.info(p, "§7Usage: §e/ah sell <total price> §8– §7price for the §fwhole stack §7in your main hand"
			+ " §8(64 items at " + Money.formatShort(200) + " each = /ah sell 128)§7. Easier: open §e/ah §7and click §a§lSell item§7.");
		return 0;
	}

	private static int sell(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		long price = Args.money(ctx, ARG_PRICE);
		AuctionConfig cfg = AuctionConfig.get();
		Result pre = AuctionService.checkListFromHand(p, price);
		if (pre != Result.OK) return sellFailed(p, pre, cfg);
		// The price is a total, so stacks and suspiciously low prices get a preview first and are listed on the repeat
		Inventory inv = p.getInventory();
		int slot = inv.getSelectedSlot();
		ItemStack hand = inv.getItem(slot);
		AuctionSellConfirm.Quote q = AuctionSellConfirm.quote(p.getUUID(), hand, price);
		AuctionSellConfirm.Decision d = AuctionSellConfirm.decide(p.getUUID(), slot, hand, q, Util.getMillis());
		if (d != AuctionSellConfirm.Decision.LIST) {
			sendPreview(p, hand, q, d == AuctionSellConfirm.Decision.CHANGED, cfg);
			return 1;
		}
		CreateResult r = AuctionService.listFromHand(p, price);
		if (!r.ok()) return sellFailed(p, r.result(), cfg);
		announceListed(p, r.listing(), cfg);
		return 1;
	}

	static void announceListed(ServerPlayer p, Listing l, AuctionConfig cfg) {
		String perPiece = l.count() > 1
			? " §atotal §8(" + AuctionSellConfirm.unitText(l.price(), l.count()) + " each)" : "";
		Msg.send(p, Msg.prefixed(Msg.legacy("§aListed ").append(AuctionService.itemText(l.item()))
			.append(Msg.legacy(" §afor " + Msg.moneyStr(l.price()) + perPiece + "§a. §7Expires in " + cfg.durationHours + "h"
				+ (cfg.taxPercent > 0 ? ", " + AuctionGui.percent(cfg.taxPercent) + " tax on sale" : "") + "."))));
		Gui.successSound(p);
	}

	static int sellFailed(ServerPlayer p, Result result, AuctionConfig cfg) {
		String msg = switch (result) {
			case LIMIT_REACHED -> "You already have " + AuctionService.activeCount(p.getUUID()) + "/" + cfg.maxListings
				+ " listings. Cancel one in §e/ah my §cor wait until one sells.";
			case INVALID_PRICE -> "Invalid total price (max " + Money.format(cfg.maxPrice) + " for the whole stack).";
			default -> result.message();
		};
		Msg.error(p, msg);
		Gui.failSound(p);
		return 0;
	}

	private static void sendPreview(ServerPlayer p, ItemStack hand, AuctionSellConfirm.Quote q, boolean changed, AuctionConfig cfg) {
		String cmd = "/ah sell " + Money.commandAmount(q.price());
		if (changed) Msg.info(p, "§eYour hand or stack changed – please confirm again.");
		Msg.send(p, Msg.prefixed(Msg.legacy("§7List ").append(AuctionService.itemText(hand))
			.append(Msg.legacy(" §7for " + Msg.moneyStr(q.price()) + " §7in total? "))
			.append(Msg.clickRun("§a§l[Confirm]", cmd, "§7Click to list for §a" + Money.format(q.price())
				+ "\n§7The price is for the §fwhole stack§7."))));
		long tax = AuctionService.taxFor(q.price());
		Msg.send(p, Msg.legacy("§8  » " + (q.count() > 1 ? "§f" + AuctionSellConfirm.unitText(q.price(), q.count()) + " §7each §8· " : "")
			+ (tax > 0 ? "§7you get §a" + Money.format(q.price() - tax) + " §8(" + AuctionGui.percent(cfg.taxPercent) + " tax) · " : "")
			+ "§7expires in §f" + cfg.durationHours + "h"));
		for (String w : AuctionSellConfirm.warnings(q)) Msg.send(p, Msg.legacy("§c  ⚠ " + w));
		long each = AuctionSellConfirm.eachInstead(q, cfg.maxPrice);
		if (each > 0) {
			String alt = "/ah sell " + Money.commandAmount(each);
			Msg.send(p, Msg.legacy("§7  Did you mean " + Msg.moneyStr(q.price()) + " each? ")
				.append(Msg.clickSuggest("§e" + alt, alt, "§7Put it in the chat box: " + Money.format(q.price()) + " × "
					+ q.count() + " = §a" + Money.format(each))));
		}
		Msg.send(p, Msg.legacy("§8  The price is for the whole stack. Click Confirm, or repeat " + cmd + " within "
			+ cfg.sellConfirmSeconds + "s."));
		if (changed || q.warn()) Gui.failSound(p);
	}

	private static int info(CommandContext<CommandSourceStack> ctx, UUID player) {
		AuctionService.processExpired();
		CommandSourceStack src = ctx.getSource();
		if (player == null) {
			int listings = AuctionStore.data().listings.size();
			Msg.send(src, Msg.legacy("§7auction listings=" + listings + " npc=" + AuctionService.npcCount()
				+ " claims=" + AuctionStore.data().claims.size() + " tax=" + AuctionConfig.get().taxPercent));
			return 1;
		}
		Msg.send(src, Msg.legacy("§7auction player=" + EconomyService.nameOrUnknown(player) + " active=" + AuctionService.activeCount(player)
			+ " claims=" + AuctionService.claimCount(player) + " balance=" + EconomyService.balance(player)));
		return 1;
	}

	private static int expire(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		UUID player = Args.knownPlayer(ctx, "player");
		int n = AuctionService.forceExpire(player);
		Msg.success(ctx.getSource(), "auction expired=" + n + " (" + EconomyService.nameOrUnknown(player) + ")");
		return n;
	}

	private static int remove(CommandContext<CommandSourceStack> ctx) {
		long id = LongArgumentType.getLong(ctx, "id");
		boolean ok = AuctionService.adminRemove(id);
		if (ok) Msg.success(ctx.getSource(), "auction removed=" + id);
		else Msg.error(ctx.getSource(), "Listing #" + id + " not found.");
		return ok ? 1 : 0;
	}

	private static int npc(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		int count = IntegerArgumentType.getInteger(ctx, "count");
		ItemStack stack = Mc.inputStack(ItemArgument.getItem(ctx, "item"), count, true); // Never above the max stack size, which could not be bought
		OptionalLong price = Money.parse(StringArgumentType.getString(ctx, "price"));
		if (price.isEmpty()) throw Args.INVALID_AMOUNT.create();
		CreateResult r = AuctionService.createNpcListing(stack, price.getAsLong(), 6 * AuctionService.HOUR_MS, ADMIN_NPC_NAME, AuctionService.SOURCE_ADMIN);
		if (!r.ok()) {
			Msg.error(ctx.getSource(), r.result().message());
			return 0;
		}
		Msg.success(ctx.getSource(), "auction npc id=" + r.listing().id() + " price=" + r.listing().price());
		return 1;
	}

	private static int clearNpc(CommandContext<CommandSourceStack> ctx) {
		int n = AuctionService.removeNpcListings(AuctionService.SOURCE_ADMIN);
		Msg.success(ctx.getSource(), "auction clearnpc=" + n);
		return 1;
	}

	private static int purge(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		UUID player = Args.knownPlayer(ctx, "player");
		int n = AuctionService.purge(player, true);
		Msg.success(ctx.getSource(), "auction purged=" + n + " (" + EconomyService.nameOrUnknown(player) + ", item data in the log)");
		return 1;
	}
}
