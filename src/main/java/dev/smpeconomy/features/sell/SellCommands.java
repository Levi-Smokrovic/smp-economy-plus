package dev.smpeconomy.features.sell;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import dev.smpeconomy.core.CommandRegistry;
import dev.smpeconomy.core.compat.Mc;
import dev.smpeconomy.core.Args;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Perms;
import dev.smpeconomy.core.PlayerDataService;

/**
 * Admin output: {@code sell balance=<cent> earned=<cent> items=<n> open=<n> returns=<n>}, where returns counts items
 * in the return store waiting for inventory space.
 */
final class SellCommands {
	static final String NODE_SELL = "smpeconomy.sell";
	static final String NODE_WORTH = "smpeconomy.worth";
	static final String NODE_ADMIN = "smpeconomy.admin.sell";

	private SellCommands() {}

	static void register() {
		CommandRegistry.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(Commands.literal("sell")
				.requires(Perms.require(NODE_SELL))
				.executes(SellCommands::openGui)
				.then(Commands.literal("hand").executes(SellCommands::sellHand))
				.then(Commands.literal("all").executes(SellCommands::sellAll)));
			dispatcher.register(Commands.literal("worth")
				.requires(Perms.require(NODE_WORTH))
				.executes(SellCommands::worthHand)
				.then(Commands.literal("hand").executes(SellCommands::worthHand))
				.then(Commands.argument("item", ItemArgument.item(registryAccess)).executes(SellCommands::worthItem)));
			dispatcher.register(Commands.literal("smpeco")
				.requires(Perms.requireAdmin(Perms.ADMIN))
				.then(Commands.literal("sell")
					.requires(Perms.requireAdmin(NODE_ADMIN))
					.then(Commands.literal("balance")
						.then(Commands.argument("player", com.mojang.brigadier.arguments.StringArgumentType.word()).suggests(Args.knownPlayers())
							.executes(SellCommands::debugBalance)))));
		});
	}

	private static int openGui(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		if (SellService.isBlocked(player)) {
			Msg.error(player, SellService.BLOCKED_MESSAGE);
			return 0;
		}
		if (!SellGui.open(player)) {
			Msg.error(player, "Couldn't open the sell menu.");
			return 0;
		}
		return 1;
	}

	private static int sellHand(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		SellService.Attempt a = SellService.sellHand(player);
		if (a.error() != null) {
			Msg.error(player, a.error());
			Gui.failSound(player);
			return 0;
		}
		SellService.report(player, a.outcome());
		if (a.outcome().sold()) Gui.successSound(player);
		return a.outcome().sold() ? 1 : 0;
	}

	private static int sellAll(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		SellService.Attempt a = SellService.sellAll(player, Util.getMillis());
		if (a.error() != null) {
			Msg.error(player, a.error());
			Gui.failSound(player);
			return 0;
		}
		if (a.preview() != null) {
			SellService.Quote q = a.preview();
			int seconds = SellConfig.get().confirmSeconds;
			if (a.changed()) {
				Msg.info(player, "§eYour inventory or prices changed – please confirm again.");
				Gui.failSound(player);
			}
			Msg.send(player, Msg.prefixed("§7Sell all sellable items (§f" + Msg.number(q.count()) + "§7) for " + Msg.moneyStr(q.total()) + "? ")
				.append(Msg.clickRun("§a§l[Confirm]", "/sell all", "§7Click to sell\n§8Armor and offhand stay untouched.")));
			Msg.send(player, Msg.legacy("§8  (or repeat /sell all within " + seconds + "s)"));
			return 1;
		}
		SellService.report(player, a.outcome());
		if (a.outcome().sold()) Gui.successSound(player);
		return a.outcome().sold() ? 1 : 0;
	}

	private static int worthHand(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		ItemStack hand = player.getMainHandItem();
		if (hand.isEmpty()) {
			Msg.error(player, "You're not holding anything. §7(/worth <item> for any item)");
			return 0;
		}
		for (Component line : SellService.worthLines(player.getUUID(), hand, hand.getCount())) Msg.send(player, line);
		return 1;
	}

	private static int worthItem(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		Item item = Mc.inputItem(ItemArgument.getItem(ctx, "item"));
		CommandSourceStack src = ctx.getSource();
		ServerPlayer player = src.getPlayer();
		UUID id = player != null ? player.getUUID() : new UUID(0, 0);
		ItemStack stack = new ItemStack(item);
		int count = Math.max(1, stack.getMaxStackSize());
		for (Component line : SellService.worthLines(id, stack, count)) Msg.send(src, line);
		return 1;
	}

	private static int debugBalance(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		UUID id = Args.knownPlayer(ctx, "player");
		Msg.send(ctx.getSource(), Msg.legacy("§7sell balance=" + EconomyService.balance(id)
			+ " earned=" + PlayerDataService.getCounter(id, SellService.COUNTER_EARNED)
			+ " items=" + PlayerDataService.getCounter(id, SellService.COUNTER_ITEMS)
			+ " open=" + SellGui.openCount()
			+ " returns=" + SellJournal.waitingItemsOf(id)));
		return 1;
	}
}
