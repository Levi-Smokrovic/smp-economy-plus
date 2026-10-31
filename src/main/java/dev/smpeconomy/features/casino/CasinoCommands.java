package dev.smpeconomy.features.casino;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import dev.smpeconomy.core.Args;
import dev.smpeconomy.core.CommandRegistry;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Perms;
import dev.smpeconomy.core.Services;
import dev.smpeconomy.core.Treasury;
import dev.smpeconomy.features.casino.CasinoData.PlayerStats;
import dev.smpeconomy.features.casino.CasinoData.Round;

/**
 * Commands: {@code /casino [stats [player]|limit [amount|off]|double <id> [no]]}, {@code /coinflip house [amount]}
 * (attached to the /coinflip command of minigames; Brigadier merges nodes of the same name), {@code /slots [bet]},
 * {@code /mines [bet] [bombs]}, {@code /hilo [bet]} and admin {@code /smpeco casino info|peek|settle|resetlimit|offer}.
 */
final class CasinoCommands {
	static final String NODE = "smpeconomy.casino";
	static final String NODE_ADMIN = "smpeconomy.admin.casino";

	private CasinoCommands() {}

	static void register() {
		CommandRegistry.register((dispatcher, registryAccess, environment) -> register(dispatcher));
	}

	private static void register(CommandDispatcher<CommandSourceStack> d) {
		d.register(Commands.literal("casino").requires(Perms.require(NODE))
			.executes(ctx -> run(ctx, CasinoMenus::openMenu))
			.then(Commands.literal("stats")
				.executes(ctx -> run(ctx, p -> CasinoMenus.openStats(p, p.getUUID(), true)))
				.then(Commands.argument("player", StringArgumentType.word()).requires(Perms.requireAdmin(NODE_ADMIN)).suggests(Args.knownPlayers())
					.executes(ctx -> {
						UUID target = Args.knownPlayer(ctx, "player");
						return run(ctx, p -> CasinoMenus.openStats(p, target, false));
					})))
			.then(Commands.literal("limit")
				.executes(CasinoCommands::showLimit)
				.then(Commands.argument("amount", StringArgumentType.greedyString()).executes(CasinoCommands::setLimit)))
			.then(doubleOrNothing("double", "no")));

		// attach to minigames' /coinflip (literals of the same name are merged; the literal "house" takes precedence
		// over the amount). minigames' CoinflipGui looks up and uses exactly this node.
		d.register(Commands.literal("coinflip").requires(Perms.require("smpeconomy.coinflip"))
			.then(coinflipHouse("house")));

		d.register(Commands.literal("slots").requires(Perms.require(NODE))
			.executes(ctx -> run(ctx, p -> SlotsGui.open(p, null)))
			.then(Commands.argument("bet", StringArgumentType.greedyString())
				.executes(ctx -> {
					long stake = Args.money(ctx, "bet");
					return run(ctx, p -> SlotsGui.open(p, stake));
				})));

		d.register(Commands.literal("mines").requires(Perms.require(NODE))
			.executes(ctx -> run(ctx, p -> MinesGui.open(p, null, null)))
			.then(Commands.argument("bet", StringArgumentType.word())
				.executes(ctx -> {
					long stake = Args.money(ctx, "bet");
					return run(ctx, p -> MinesGui.open(p, stake, null));
				})
				.then(Commands.argument("bombs", IntegerArgumentType.integer(1, CasinoMath.MINES_TILES - 1))
					.executes(ctx -> {
						long stake = Args.money(ctx, "bet");
						int bombs = IntegerArgumentType.getInteger(ctx, "bombs");
						return run(ctx, p -> MinesGui.open(p, stake, bombs));
					}))));

		d.register(Commands.literal("hilo").requires(Perms.require(NODE))
			.executes(ctx -> run(ctx, p -> HiloGui.open(p, null)))
			.then(Commands.argument("bet", StringArgumentType.greedyString())
				.executes(ctx -> {
					long stake = Args.money(ctx, "bet");
					return run(ctx, p -> HiloGui.open(p, stake));
				})));

		// Admin/diagnostics (also via RCON): machine-readable lines for E2E
		d.register(Commands.literal("smpeco").requires(Perms.requireAdmin(Perms.ADMIN))
			.then(Commands.literal("casino")
				.requires(Perms.requireAdmin(NODE_ADMIN))
				.then(Commands.literal("info").then(playerArg().executes(CasinoCommands::info)))
				.then(Commands.literal("peek").then(playerArg().executes(CasinoCommands::peek)))
				.then(Commands.literal("settle").then(playerArg().executes(CasinoCommands::forceSettle)))
				.then(Commands.literal("resetlimit").then(playerArg().executes(CasinoCommands::resetLimit)))
				.then(Commands.literal("offer").then(playerArg()
					.then(Commands.argument("amount", StringArgumentType.greedyString()).executes(CasinoCommands::offer))))));
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> doubleOrNothing(String literal, String decline) {
		return Commands.literal(literal)
			.then(Commands.argument("id", StringArgumentType.word())
				.executes(ctx -> run(ctx, p -> CasinoOffers.accept(p, StringArgumentType.getString(ctx, "id"), false)))
				.then(Commands.literal(decline)
					.executes(ctx -> run(ctx, p -> CasinoOffers.accept(p, StringArgumentType.getString(ctx, "id"), true)))));
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> coinflipHouse(String literal) {
		return Commands.literal(literal).requires(Perms.require(NODE))
			.executes(ctx -> run(ctx, p -> CoinflipGui.open(p, null)))
			.then(Commands.argument("amount", StringArgumentType.greedyString())
				.executes(ctx -> {
					long stake = Args.money(ctx, "amount");
					return run(ctx, p -> CoinflipGui.open(p, stake));
				}));
	}

	private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> playerArg() {
		return Commands.argument("player", StringArgumentType.word()).suggests(Args.knownPlayers());
	}

	@FunctionalInterface
	private interface PlayerAction {
		void run(ServerPlayer player) throws CommandSyntaxException;
	}

	private static int run(CommandContext<CommandSourceStack> ctx, PlayerAction action) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		if (!Gui.canOpen(p)) return 0;
		action.run(p);
		return 1;
	}

	private static int showLimit(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		UUID id = p.getUUID();
		Msg.info(p, "Daily loss limit: " + Msg.moneyStr(Casino.lossLimit(id)) + " §8· §7lost today: "
			+ Msg.moneyStr(Math.max(0, Casino.netLossToday(id))) + " §8· §7left: " + Msg.moneyStr(Casino.lossRoom(id)));
		PlayerStats s = Casino.peekStats(id);
		if (s != null && s.pendingLimit != -2 && !s.pendingLimitDay.isEmpty()) {
			Msg.info(p, "§8From " + s.pendingLimitDay + ": " + (s.pendingLimit < 0 ? "server limit" : Money.format(s.pendingLimit)));
		}
		Msg.info(p, "§8Change it: /casino limit <amount> or /casino limit off");
		return 1;
	}

	private static int setLimit(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		String raw = StringArgumentType.getString(ctx, "amount").trim().toLowerCase(Locale.ROOT);
		long limit;
		if (raw.equals("off")) {
			limit = -1;
		} else {
			OptionalLong v = Money.parseAllowZero(raw);
			if (v.isEmpty()) throw Args.INVALID_AMOUNT.create();
			limit = v.getAsLong();
		}
		Msg.info(p, Casino.setPersonalLimit(p.getUUID(), limit));
		return 1;
	}

	private static int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		UUID id = Args.knownPlayer(ctx, "player");
		Round r = Casino.openRound(id);
		long pending = Casino.data().pending.stream().filter(p -> p.player.equals(id)).mapToLong(p -> p.amount).sum();
		PlayerStats s = Casino.peekStats(id);
		String line = "casino balance=" + EconomyService.balance(id) + " treasury=" + Treasury.balance()
			+ " maxBet=" + Casino.maxBet() + " maxWin=" + Casino.maxWin() + " cover=" + Casino.coverLimit(null)
			+ " netToday=" + Casino.netLossToday(id) + " limit=" + Casino.lossLimit(id) + " room=" + Casino.lossRoom(id)
			+ " open=" + (r == null ? "none" : r.game + ":" + r.state) + " pending=" + pending
			+ " rounds=" + (s == null ? 0 : s.rounds) + " wagered=" + (s == null ? 0 : s.wagered) + " paid=" + (s == null ? 0 : s.paidOut);
		ctx.getSource().sendSuccess(() -> Msg.legacy(line), false);
		return 1;
	}

	private static int peek(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		// reveals bomb positions, so console/RCON only (E2E), never in game (no admin advantage at their own table)
		if (ctx.getSource().isPlayer()) {
			Msg.error(ctx.getSource(), "Console/RCON only.");
			return 0;
		}
		UUID id = Args.knownPlayer(ctx, "player");
		Round r = Casino.openRound(id);
		String line;
		if (r == null) {
			line = "casino peek open=none";
		} else {
			line = "casino peek open=" + r.game + " state=" + r.state + " stake=" + r.stake + " reserved=" + r.reserved
				+ " mines=" + r.mines.toString().replace(" ", "") + " revealed=" + r.revealed.toString().replace(" ", "")
				+ " card=" + r.card + " correct=" + r.correct + " pot=" + r.pot + " wins=" + r.wins + " payout=" + r.payout;
		}
		ctx.getSource().sendSuccess(() -> Msg.legacy(line), false);
		return 1;
	}

	private static int forceSettle(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		UUID id = Args.knownPlayer(ctx, "player");
		Round r = Casino.openRound(id);
		if (r == null) {
			Msg.info(ctx.getSource(), "casino settle none");
			return 0;
		}
		Casino.Settled s = Casino.settle(r.id, "Admin");
		Msg.info(ctx.getSource(), "casino settle payout=" + (s == null ? 0 : s.payout()));
		return 1;
	}

	private static int resetLimit(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		UUID id = Args.knownPlayer(ctx, "player");
		Casino.resetLimits(id);
		Msg.info(ctx.getSource(), "casino resetlimit ok");
		return 1;
	}

	private static int offer(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		UUID id = Args.knownPlayer(ctx, "player");
		long cents = Args.money(ctx, "amount");
		ServerPlayer p = ctx.getSource().getServer().getPlayerList().getPlayer(id);
		boolean offered = p != null && Services.find(Services.DoubleOrNothing.class).map(s -> s.offer(p, cents)).orElse(false);
		CasinoOffers.Offer o = CasinoOffers.get(id);
		String reason = p == null ? "offline" : CasinoOffers.check(id, cents);
		Msg.info(ctx.getSource(), "casino offer offered=" + offered + (offered && o != null ? " offer=" + o.id() : " reason=" + reason));
		return offered ? 1 : 0;
	}
}
