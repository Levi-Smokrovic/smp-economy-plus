package dev.smpeconomy.features.casino.games.blackjack;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import dev.smpeconomy.core.Args;
import dev.smpeconomy.core.CommandRegistry;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Perms;

/** {@code /blackjack [bet]} (alias {@code /bj}) opens the table (with a bet: deals at once), {@code /blackjack help} explains the rules. */
final class BlackjackCommands {
	/** Same node as the other house games. */
	static final String NODE = "smpeconomy.casino";

	private BlackjackCommands() {}

	static void register() {
		CommandRegistry.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(tree("blackjack"));
			dispatcher.register(tree("bj"));
			// diagnosis for E2E (console/RCON only, shows what closing the round would pay): /smpeco casino blackjack <player>
			dispatcher.register(Commands.literal("smpeco").requires(Perms.requireAdmin(Perms.ADMIN))
				.then(Commands.literal("casino").requires(Perms.requireAdmin("smpeconomy.admin.casino"))
					.then(Commands.literal("blackjack")
						.then(Commands.argument("player", StringArgumentType.word()).suggests(Args.knownPlayers())
							.executes(BlackjackCommands::peek)))));
		});
	}

	private static int peek(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		if (ctx.getSource().isPlayer()) {
			Msg.error(ctx.getSource(), "Console/RCON only.");
			return 0;
		}
		java.util.UUID id = Args.knownPlayer(ctx, "player");
		dev.smpeconomy.features.casino.CasinoData.Round r = dev.smpeconomy.features.casino.Casino.openRound(id);
		BlackjackRules.State s = r == null ? null : BlackjackGame.state(r);
		String line;
		if (s == null) {
			line = "blackjack peek open=" + (r == null ? "none" : r.game);
		} else {
			StringBuilder hands = new StringBuilder();
			for (BlackjackRules.Hand h : s.hands) {
				if (hands.length() > 0) hands.append(',');
				hands.append(BlackjackRules.total(h.cards)).append('/').append(h.bet).append(h.done ? "d" : "");
			}
			line = "blackjack peek open=blackjack state=" + r.state + " stake=" + r.stake + " reserved=" + r.reserved
				+ " finished=" + s.finished + " active=" + s.active + " hands=" + hands + " dealerUp=" + BlackjackRules.value(s.dealer.get(0))
				+ " standValue=" + BlackjackRules.payoutIfStandAll(s) + " seed=" + s.seed;
		}
		ctx.getSource().sendSuccess(() -> Msg.legacy(line), false);
		return 1;
	}

	private static LiteralArgumentBuilder<CommandSourceStack> tree(String name) {
		return Commands.literal(name).requires(Perms.require(NODE))
			.executes(ctx -> open(ctx, null))
			.then(Commands.literal("help").executes(BlackjackCommands::help))
			.then(Commands.argument("bet", StringArgumentType.greedyString())
				.executes(ctx -> open(ctx, Args.money(ctx, "bet"))));
	}

	private static int open(CommandContext<CommandSourceStack> ctx, Long bet) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		if (!Gui.canOpen(p)) return 0;
		BlackjackGui.open(p, bet);
		return 1;
	}

	/** Rules in chat (also the "How to play" item in the GUI). */
	static final String[] RULES = {
		"§7Beat the dealer without going over §f21§7. Cards 2–10 count their value, §fJ Q K §7count 10, an §fAce §71 or 11.",
		"§aHit §7= take a card · §cStand §7= keep your hand · §6Double §7= double your bet, take exactly one card ·",
		"§dSplit §7= two cards of the same value become two hands (once; split aces get one card each).",
		"§7Dealer stands on all 17s (incl. soft 17) and peeks for blackjack. Wins pay §f1:1§7, a §fBlackjack 3:2§7, ties push.",
		"§8House commission: " + BlackjackGui.percent(BlackjackRules.COMMISSION_BP) + " of the profit of winning hands. 6 decks, reshuffled every round.",
		"§8Closing the table stands on every open hand – the dealer finishes the round.",
	};

	private static int help(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		Msg.info(src, BlackjackGame.LABEL + " §8– §7/blackjack <bet> §8(or /bj)");
		for (String line : RULES) src.sendSystemMessage(Msg.legacy(line));
		return 1;
	}
}
