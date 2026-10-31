package dev.smpeconomy.features.daily;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import dev.smpeconomy.core.Args;
import dev.smpeconomy.core.CommandRegistry;
import dev.smpeconomy.core.CrateKeys;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Perms;

/**
 * {@code /daily}, {@code /daily claim} and admin subcommands. The output of
 * {@code admin info} is machine-readable for the e2e tests.
 */
final class DailyCommands {
	static final String NODE = "smpeconomy.daily";
	static final String ADMIN_NODE = "smpeconomy.admin.daily";

	private DailyCommands() {}

	static void register() {
		CommandRegistry.register((dispatcher, registryAccess, environment) -> dispatcher.register(
			Commands.literal("daily")
				.requires(Perms.require(NODE))
				.executes(DailyCommands::open)
				.then(Commands.literal("claim").executes(DailyCommands::claim))
				.then(Commands.literal("admin")
					.requires(Perms.requireAdmin(ADMIN_NODE))
					.then(Commands.literal("info")
						.then(Commands.argument("player", StringArgumentType.word()).suggests(Args.knownPlayers())
							.executes(DailyCommands::info)))
					.then(Commands.literal("reset")
						.then(Commands.argument("player", StringArgumentType.word()).suggests(Args.knownPlayers())
							.executes(DailyCommands::reset)))
					.then(Commands.literal("shift")
						.then(Commands.argument("player", StringArgumentType.word()).suggests(Args.knownPlayers())
							.then(Commands.argument("hours", IntegerArgumentType.integer(1, 24 * 365))
								.executes(DailyCommands::shift))))
					.then(Commands.literal("setstreak")
						.then(Commands.argument("player", StringArgumentType.word()).suggests(Args.knownPlayers())
							.then(Commands.argument("streak", IntegerArgumentType.integer(1, 100_000))
								.executes(DailyCommands::setStreak)))))));
	}

	private static int open(CommandContext<CommandSourceStack> ctx) {
		ServerPlayer player = ctx.getSource().getPlayer();
		if (player == null) {
			Msg.error(ctx.getSource(), "Only players can do this.");
			return 0;
		}
		DailyGui.open(player);
		return 1;
	}

	private static int claim(CommandContext<CommandSourceStack> ctx) {
		ServerPlayer player = ctx.getSource().getPlayer();
		if (player == null) {
			Msg.error(ctx.getSource(), "Only players can do this.");
			return 0;
		}
		return DailyService.claimAndNotify(player).ok() ? 1 : 0;
	}

	private static int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		UUID id = Args.knownPlayer(ctx, "player");
		DailyConfig cfg = DailyConfig.get();
		long now = DailyService.now();
		DailyState s = DailyService.state(id);
		DailyLogic.Status st = DailyLogic.status(s, now, cfg.breakMs());
		Msg.send(ctx.getSource(), Msg.legacy("§7daily player=" + EconomyService.nameOrUnknown(id)
			+ " streak=" + st.streak() + " stored=" + s.streak + " next=" + st.nextDay()
			+ " claimable=" + st.claimable() + " broken=" + st.broken()
			+ " cycle=" + st.claimedInCycle() + " best=" + s.bestStreak + " claims=" + s.totalClaims
			+ " last=" + s.lastClaimMs + " balance=" + EconomyService.balance(id)
			+ " keys=" + CrateKeys.count(id, cfg.day7KeyTier)));
		return 1;
	}

	private static int reset(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		UUID id = Args.knownPlayer(ctx, "player");
		DailyService.reset(id);
		Msg.success(ctx.getSource(), "Daily reset for " + EconomyService.nameOrUnknown(id) + ".");
		return 1;
	}

	private static int shift(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		UUID id = Args.knownPlayer(ctx, "player");
		int hours = IntegerArgumentType.getInteger(ctx, "hours");
		if (!DailyService.shift(id, hours)) {
			Msg.error(ctx.getSource(), EconomyService.nameOrUnknown(id) + " has never claimed.");
			return 0;
		}
		Msg.success(ctx.getSource(), "Moved " + EconomyService.nameOrUnknown(id) + "'s last claim back by " + hours + "h.");
		return 1;
	}

	private static int setStreak(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		UUID id = Args.knownPlayer(ctx, "player");
		int streak = IntegerArgumentType.getInteger(ctx, "streak");
		DailyService.setStreak(id, streak, DailyService.now(), DailyConfig.get());
		Msg.success(ctx.getSource(), "Set " + EconomyService.nameOrUnknown(id) + "'s streak to " + DailyService.days(streak) + ".");
		return 1;
	}
}
