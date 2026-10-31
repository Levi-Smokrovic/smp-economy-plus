package dev.smpeconomy.features.bank;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.List;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerPlayer;
import dev.smpeconomy.core.Args;
import dev.smpeconomy.core.CommandRegistry;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Perms;
import dev.smpeconomy.core.TimeUtil;

final class BankCommands {
	static final String NODE = "smpeconomy.bank";
	static final String ADMIN_NODE = "smpeconomy.admin.bank";
	private static final SuggestionProvider<CommandSourceStack> AMOUNTS =
		(ctx, builder) -> SharedSuggestionProvider.suggest(List.of("all", "10%", "50%", "100", "1000", "10k"), builder);

	private BankCommands() {}

	static void register() {
		CommandRegistry.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(Commands.literal("bank")
				.requires(Perms.require(NODE))
				.executes(BankCommands::openGui)
				.then(moveLiteral("deposit", true))
				.then(moveLiteral("withdraw", false))
				.then(Commands.literal("info").executes(BankCommands::info)));

			dispatcher.register(Commands.literal("smpeco")
				.requires(Perms.requireAdmin(Perms.ADMIN))
				.then(Commands.literal("bank")
					.requires(Perms.requireAdmin(ADMIN_NODE))
					.then(Commands.literal("info")
						.then(Commands.argument("player", StringArgumentType.word()).suggests(Args.knownPlayers())
							.executes(BankCommands::adminInfo)))
					.then(Commands.literal("status").executes(BankCommands::status))
					// single player only: paying everyone would be a second daily payout from the treasury
					.then(Commands.literal("payout")
						.then(Commands.argument("player", StringArgumentType.word()).suggests(Args.knownPlayers())
							.executes(ctx -> payout(ctx, Args.knownPlayer(ctx, "player")))))
					.then(Commands.literal("backdate")
						.then(Commands.argument("player", StringArgumentType.word()).suggests(Args.knownPlayers())
							.then(Commands.argument("hours", IntegerArgumentType.integer(1, 24 * 365))
								.executes(BankCommands::backdate))))));
		});
	}

	private static LiteralArgumentBuilder<CommandSourceStack> moveLiteral(String name, boolean deposit) {
		return Commands.literal(name)
			.executes(ctx -> {
				Msg.error(ctx.getSource(), "Usage: §e/bank " + (deposit ? "deposit" : "withdraw") + " <amount|50%|all>§c.");
				return 0;
			})
			.then(Commands.argument("amount", StringArgumentType.greedyString()).suggests(AMOUNTS)
				.executes(ctx -> {
					ServerPlayer player = ctx.getSource().getPlayerOrException();
					return BankActions.handleInput(player, deposit, StringArgumentType.getString(ctx, "amount")) ? 1 : 0;
				}));
	}

	private static int openGui(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		BankGui.open(ctx.getSource().getPlayerOrException());
		return 1;
	}

	private static int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		Msg.info(player, "§6§lBank Account");
		for (String line : BankActions.infoLines(player.getUUID())) Msg.send(player, Msg.legacy(" " + line));
		return 1;
	}

	private static int adminInfo(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		UUID id = Args.knownPlayer(ctx, "player");
		BankLedger l = BankService.LIVE;
		int lots = l.peek(id).map(a -> a.lots.size()).orElse(0);
		long interest = l.peek(id).map(a -> a.totalInterest).orElse(0L);
		Msg.send(ctx.getSource(), Msg.legacy("§7bank player=" + EconomyService.nameOrUnknown(id) + " balance=" + l.balance(id)
			+ " wallet=" + EconomyService.balance(id) + " eligible=" + l.eligibleNow(id) + " eligibleNext=" + l.eligibleAtNextPayout(id)
			+ " lots=" + lots + " interest=" + interest + " §8(" + Money.format(l.balance(id)) + ")"));
		return 1;
	}

	private static int status(CommandContext<CommandSourceStack> ctx) {
		BankLedger l = BankService.LIVE;
		BankData d = BankService.STORE.get();
		BankConfig cfg = l.config();
		BankData.Batch b = d.pending;
		String pending = b == null ? "pending=0" : "pending=" + b.claims.size() + " released=" + b.released + "/" + b.target
			+ " deadlineIn=" + TimeUtil.formatDuration(Math.max(0, b.deadline - l.now()));
		Msg.send(ctx.getSource(), Msg.legacy("§7bank status accounts=" + l.accountCount() + " total=" + l.total()
			+ " lastDate=" + d.lastInterestDate + " lastDue=" + d.lastPayoutDue + " lastPaid=" + d.lastPayoutPaid + " " + pending
			+ " nextIn=" + TimeUtil.formatDuration(l.nextPayoutAt() - l.now()) + " enabled=" + cfg.interestEnabled
			+ " rate=" + cfg.interestPercent + " cap=" + cfg.maxInterestPerDay + " holdHours=" + cfg.holdHours
			+ " §8(" + Money.format(l.total()) + ")"));
		return 1;
	}

	/** Pays with a cutoff of now; does not count as the regular midnight payout. */
	private static int payout(CommandContext<CommandSourceStack> ctx, UUID only) {
		BankLedger l = BankService.LIVE;
		if (!l.config().interestEnabled) {
			Msg.error(ctx.getSource(), "Interest is turned off in bank.json.");
			return 0;
		}
		java.util.function.Predicate<UUID> filter = only == null ? null : id -> id.equals(only);
		BankLedger.Payout p = l.payInterest(l.now(), BankService.liveSource(), filter);
		BankService.announce(p, true);
		Msg.send(ctx.getSource(), Msg.legacy("§7bank payout players=" + p.players() + " due=" + p.due() + " paid=" + p.paid()
			+ " budget=" + p.budget() + " factor=" + String.format(java.util.Locale.ROOT, "%.4f", p.factor()) + " failed=" + p.failed()
			+ " §8(" + Money.format(p.paid()) + ")"));
		return p.failed() ? 0 : 1;
	}

	private static int backdate(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		UUID id = Args.knownPlayer(ctx, "player");
		int hours = IntegerArgumentType.getInteger(ctx, "hours");
		boolean ok = BankService.LIVE.backdate(id, hours * BankLedger.HOUR_MS);
		if (!ok) {
			Msg.error(ctx.getSource(), "No bank account.");
			return 0;
		}
		Msg.send(ctx.getSource(), Msg.legacy("§7bank backdate player=" + EconomyService.nameOrUnknown(id) + " hours=" + hours
			+ " eligible=" + BankService.LIVE.eligibleNow(id)));
		return 1;
	}
}
