package dev.smpeconomy.features.economy;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import dev.smpeconomy.core.Args;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.EconomyService.Result;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Perms;
import dev.smpeconomy.core.Treasury;

public final class EconomyCommands {
	public static final String NODE_BALANCE = "smpeconomy.balance";
	public static final String NODE_BALANCE_OTHERS = "smpeconomy.balance.others";
	public static final String NODE_PAY = "smpeconomy.pay";
	public static final String NODE_PAY_TOGGLE = "smpeconomy.pay.toggle";
	public static final String NODE_BALTOP = "smpeconomy.baltop";
	public static final String NODE_TREASURY = "smpeconomy.treasury";
	public static final String NODE_ADMIN = "smpeconomy.admin.eco";

	private static final SimpleCommandExceptionType TREASURY_IS_NO_PLAYER =
		new SimpleCommandExceptionType(Msg.prefixed("§cThe Treasury isn't a player – use §e/treasury§c."));

	private EconomyCommands() {}

	public static void register(CommandDispatcher<CommandSourceStack> d) {
		for (String name : List.of("balance", "bal", "money")) d.register(balanceTree(name));

		d.register(literal("pay").requires(Perms.require(NODE_PAY))
			.then(player()
				.then(argument("amount", StringArgumentType.greedyString()).executes(EconomyCommands::pay))));

		d.register(literal("paytoggle").requires(Perms.require(NODE_PAY_TOGGLE))
			.executes(ctx -> payToggle(ctx, null))
			.then(literal("on").executes(ctx -> payToggle(ctx, true)))
			.then(literal("off").executes(ctx -> payToggle(ctx, false))));

		d.register(literal("baltop").requires(Perms.require(NODE_BALTOP))
			.executes(ctx -> baltop(ctx, 1))
			.then(argument("page", IntegerArgumentType.integer(1)).executes(ctx -> baltop(ctx, IntegerArgumentType.getInteger(ctx, "page")))));

		d.register(literal("treasury").requires(Perms.require(NODE_TREASURY)).executes(EconomyCommands::treasury));

		d.register(literal("eco").requires(Perms.requireAdmin(NODE_ADMIN))
			.then(literal("give").then(player().then(amount().executes(EconomyCommands::ecoGive))))
			.then(literal("take").then(player().then(amount().executes(EconomyCommands::ecoTake))))
			.then(literal("set").then(player().then(amount().executes(EconomyCommands::ecoSet))))
			.then(literal("reset").then(player().executes(EconomyCommands::ecoReset)))
			.then(literal("get").then(player().executes(EconomyCommands::ecoGet)))
			.then(literal("treasury")
				.executes(EconomyCommands::treasuryShow)
				.then(literal("show").executes(EconomyCommands::treasuryShow))
				.then(literal("deposit").then(amount().executes(EconomyCommands::treasuryDeposit)))
				.then(literal("withdraw").then(amount().executes(EconomyCommands::treasuryWithdraw)))));
	}

	private static RequiredArgumentBuilder<CommandSourceStack, String> player() {
		return argument("player", StringArgumentType.word()).suggests(Args.knownPlayers());
	}

	private static RequiredArgumentBuilder<CommandSourceStack, String> amount() {
		return argument("amount", StringArgumentType.greedyString());
	}

	private static UUID target(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		// before name resolution: a real player named "Treasury" must not intercept payments to the
		// treasury
		if (isTreasuryName(StringArgumentType.getString(ctx, "player"))) throw TREASURY_IS_NO_PLAYER.create();
		try {
			return Args.knownPlayer(ctx, "player");
		} catch (CommandSyntaxException e) {
			if (isTreasuryName(StringArgumentType.getString(ctx, "player"))) throw TREASURY_IS_NO_PLAYER.create();
			throw e;
		}
	}

	private static boolean isTreasuryName(String name) {
		return Treasury.NAME.equalsIgnoreCase(name) || "Treasury".equalsIgnoreCase(name);
	}

	private static String nameOf(CommandContext<CommandSourceStack> ctx, UUID id) {
		ServerPlayer online = ctx.getSource().getServer().getPlayerList().getPlayer(id);
		return online != null ? online.getGameProfile().name() : EconomyService.nameOrUnknown(id);
	}

	private static LiteralArgumentBuilder<CommandSourceStack> balanceTree(String name) {
		return literal(name).requires(Perms.require(NODE_BALANCE))
			.executes(EconomyCommands::balanceSelf)
			.then(player().requires(Perms.require(NODE_BALANCE_OTHERS)).executes(EconomyCommands::balanceOther));
	}

	private static int balanceSelf(CommandContext<CommandSourceStack> ctx) {
		ServerPlayer self = ctx.getSource().getPlayer();
		if (self == null) {
			Msg.error(ctx.getSource(), "Players only – use /balance <player>.");
			return 0;
		}
		Msg.info(self, "Balance: " + balanceText(self.getUUID()));
		return 1;
	}

	private static int balanceOther(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		UUID id = target(ctx);
		Msg.info(ctx.getSource(), "§e" + nameOf(ctx, id) + "§7's balance: " + balanceText(id));
		return 1;
	}

	private static String balanceText(UUID id) {
		List<Baltop.Row> rows = Baltop.live();
		int rank = Baltop.rankOf(rows, id);
		StringBuilder sb = new StringBuilder("§a").append(Money.format(EconomyService.balance(id)));
		if (Baltop.hasBank()) {
			Baltop.Row row = rank > 0 ? rows.get(rank - 1) : null;
			if (row != null) sb.append(" §8| §7Bank: §a").append(Money.format(row.bank())).append(" §8| §7Total: §a").append(Money.format(row.total()));
		}
		if (rank > 0) sb.append(" §8(Rank #").append(rank).append(")");
		return sb.toString();
	}

	private static int pay(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer sender = ctx.getSource().getPlayer();
		if (sender == null) {
			Msg.error(ctx.getSource(), "Players only – admins use /eco give.");
			return 0;
		}
		UUID target = target(ctx);
		long cents = Args.money(ctx, "amount");
		String targetName = nameOf(ctx, target);
		Payments.Outcome o = Payments.pay(ctx.getSource().getServer(), sender.getUUID(), sender.getGameProfile().name(), target, cents);
		if (!o.ok()) {
			Msg.error(sender, o.status().message(targetName));
			Gui.failSound(sender);
			return 0;
		}
		String suffix = o.targetOnline() ? "" : " §8(offline – they'll be notified when they join)";
		Msg.info(sender, "§aYou sent §e" + targetName + " " + Msg.moneyStr(cents) + "§a. §8(Balance: "
			+ Money.format(EconomyService.balance(sender.getUUID())) + ")" + suffix);
		Gui.successSound(sender);
		return 1;
	}

	private static int payToggle(CommandContext<CommandSourceStack> ctx, Boolean value) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayer();
		if (player == null) {
			Msg.error(ctx.getSource(), "Players only.");
			return 0;
		}
		boolean now = Payments.setReceive(player.getUUID(), value);
		Msg.info(player, "Receive payments: " + (now ? "§aon" : "§coff") + " §8(/paytoggle " + (now ? "off" : "on") + ")");
		return 1;
	}

	private static int baltop(CommandContext<CommandSourceStack> ctx, int requested) {
		CommandSourceStack src = ctx.getSource();
		List<Baltop.Row> rows = Baltop.live();
		boolean bank = Baltop.hasBank();
		Baltop.Page page = Baltop.page(rows, requested, Baltop.PER_PAGE);
		List<MutableComponent> out = new ArrayList<>();
		out.add(Msg.prefixed("§6§lBaltop §8– §7Page §f" + page.page() + "§7/§f" + page.pages() + " §8(" + page.total() + (page.total() == 1 ? " player" : " players")
			+ (bank ? ", incl. Bank" : "") + ")"));
		if (page.rows().isEmpty()) out.add(Msg.legacy("§7  No accounts yet."));
		for (int i = 0; i < page.rows().size(); i++) out.add(rowLine(page.offset() + i + 1, page.rows().get(i), bank));
		ServerPlayer self = src.getPlayer();
		if (self != null) {
			int rank = Baltop.rankOf(rows, self.getUUID());
			out.add(rank > 0
				? Msg.legacy("§7Your rank: §e#" + rank + " §8(§a" + Money.format(rows.get(rank - 1).total()) + "§8)")
				: Msg.legacy("§7Your rank: §8not listed"));
		}
		if (page.pages() > 1) {
			MutableComponent nav = Msg.legacy("§8");
			nav.append(page.page() > 1
				? Msg.clickRun("§e« Back", "/baltop " + (page.page() - 1), "§7Page " + (page.page() - 1))
				: Msg.legacy("§8« Back"));
			nav.append(Msg.legacy(" §8| §7Page " + page.page() + "/" + page.pages() + " §8| "));
			nav.append(page.page() < page.pages()
				? Msg.clickRun("§eNext »", "/baltop " + (page.page() + 1), "§7Page " + (page.page() + 1))
				: Msg.legacy("§8Next »"));
			out.add(nav);
		}
		sendBlock(src, out);
		return 1;
	}

	/**
	 * Several lines as one message: they stay together in chat and still arrive as separate lines over RCON and
	 * console, where individual messages get concatenated with no separator.
	 */
	private static void sendBlock(CommandSourceStack src, List<MutableComponent> lines) {
		MutableComponent root = Component.empty();
		for (int i = 0; i < lines.size(); i++) {
			if (i > 0) root.append(Component.literal("\n"));
			root.append(lines.get(i));
		}
		Msg.send(src, root);
	}

	private static MutableComponent rowLine(int rank, Baltop.Row r, boolean bank) {
		String color = switch (rank) {
			case 1 -> "§6";
			case 2 -> "§f";
			case 3 -> "§c";
			default -> "§e";
		};
		String text = color + "#" + rank + " §f" + r.name() + " §8» §a" + Money.format(r.total());
		if (bank) text += " §8(Balance " + Money.formatShort(r.balance()) + " · Bank " + Money.formatShort(r.bank()) + ")";
		String hover = "§7Balance: §a" + Money.format(r.balance()) + (bank ? "\n§7Bank: §a" + Money.format(r.bank()) + "\n§7Total: §a" + Money.format(r.total()) : "");
		return Msg.hover(Msg.legacy(text), hover);
	}

	private static int treasury(CommandContext<CommandSourceStack> ctx) {
		sendLines(ctx.getSource(), TreasuryReport.live().publicLines());
		return 1;
	}

	private static void sendLines(CommandSourceStack src, List<String> lines) {
		List<MutableComponent> out = new ArrayList<>(lines.size());
		for (int i = 0; i < lines.size(); i++) out.add(i == 0 ? Msg.prefixed(lines.get(i)) : Msg.legacy(lines.get(i)));
		sendBlock(src, out);
	}

	private static String actor(CommandContext<CommandSourceStack> ctx) {
		return ctx.getSource().getTextName();
	}

	private static int ecoGive(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		UUID id = target(ctx);
		long cents = Args.money(ctx, "amount");
		String name = nameOf(ctx, id);
		Result r = EcoAdmin.give(id, cents, actor(ctx));
		if (!r.ok()) return adminError(ctx, r, name, id);
		Msg.success(ctx.getSource(), "§e" + name + " §areceived " + Msg.moneyStr(cents) + "§a. §8(Balance: " + Money.format(EconomyService.balance(id)) + ")");
		notifyOnline(ctx, id, "You received " + Msg.moneyStr(cents) + ". §8(Admin)");
		return 1;
	}

	private static int ecoTake(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		UUID id = target(ctx);
		long cents = Args.money(ctx, "amount");
		String name = nameOf(ctx, id);
		Result r = EcoAdmin.take(id, cents, actor(ctx));
		if (!r.ok()) return adminError(ctx, r, name, id);
		Msg.success(ctx.getSource(), "Took " + Msg.moneyStr(cents) + " §afrom §e" + name + "§a. §8(Balance: " + Money.format(EconomyService.balance(id)) + ")");
		notifyOnline(ctx, id, Msg.moneyStr(cents) + " was taken from your balance. §8(Admin)");
		return 1;
	}

	private static int ecoSet(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		UUID id = target(ctx);
		long cents = Args.moneyAllowZero(ctx, "amount");
		String name = nameOf(ctx, id);
		Result r = EcoAdmin.set(id, cents, actor(ctx));
		if (!r.ok()) return adminError(ctx, r, name, id);
		Msg.success(ctx.getSource(), "Set §e" + name + "§a's balance to " + Msg.moneyStr(EconomyService.balance(id)) + "§a.");
		return 1;
	}

	private static int ecoReset(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		UUID id = target(ctx);
		String name = nameOf(ctx, id);
		Result r = EcoAdmin.reset(id, actor(ctx));
		if (!r.ok()) return adminError(ctx, r, name, id);
		Msg.success(ctx.getSource(), "Reset §e" + name + "§a's balance to the starting balance (" + Msg.moneyStr(EconomyService.balance(id)) + "§a).");
		return 1;
	}

	private static int ecoGet(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		UUID id = target(ctx);
		long balance = EconomyService.balance(id);
		Msg.info(ctx.getSource(), "§e" + nameOf(ctx, id) + "§7's balance: " + Msg.moneyStr(balance) + " §8(" + balance + " cents)");
		return 1;
	}

	private static int adminError(CommandContext<CommandSourceStack> ctx, Result r, String name, UUID id) {
		String text = switch (r) {
			case INSUFFICIENT_FUNDS -> "§e" + name + " §conly has " + Msg.moneyStr(EconomyService.balance(id)) + "§c.";
			case LIMIT_EXCEEDED -> "Balance limit reached (max. " + Money.format(Money.MAX) + ").";
			case NO_ACCOUNT -> "No player account.";
			default -> r.message();
		};
		Msg.error(ctx.getSource(), text);
		return 0;
	}

	private static void notifyOnline(CommandContext<CommandSourceStack> ctx, UUID id, String text) {
		ServerPlayer online = ctx.getSource().getServer().getPlayerList().getPlayer(id);
		if (online != null) Msg.info(online, text);
	}

	private static int treasuryShow(CommandContext<CommandSourceStack> ctx) {
		sendLines(ctx.getSource(), TreasuryReport.live().adminLines());
		return 1;
	}

	private static int treasuryDeposit(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		long cents = Args.money(ctx, "amount");
		Result r = EcoAdmin.treasuryDeposit(cents, actor(ctx));
		if (!r.ok()) {
			Msg.error(ctx.getSource(), r == Result.LIMIT_EXCEEDED ? "The Treasury is full (max. " + Money.format(Money.MAX) + ")." : r.message());
			return 0;
		}
		Msg.success(ctx.getSource(), "Treasury: deposited " + Msg.moneyStr(cents) + "§a. §8(Balance: " + Money.format(Treasury.balance()) + ")");
		return 1;
	}

	private static int treasuryWithdraw(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		long cents = Args.money(ctx, "amount");
		Result r = EcoAdmin.treasuryWithdraw(cents, actor(ctx));
		if (!r.ok()) {
			Msg.error(ctx.getSource(), r == Result.INSUFFICIENT_FUNDS ? "The Treasury only has " + Msg.moneyStr(Treasury.balance()) + "§c." : r.message());
			return 0;
		}
		Msg.success(ctx.getSource(), "Treasury: withdrew " + Msg.moneyStr(cents) + "§a. §8(Balance: " + Money.format(Treasury.balance()) + ")");
		return 1;
	}
}
