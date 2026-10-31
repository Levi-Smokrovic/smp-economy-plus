package dev.smpeconomy.features.casino.games.wheel;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import dev.smpeconomy.core.Args;
import dev.smpeconomy.core.CommandRegistry;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Perms;
import dev.smpeconomy.features.casino.Casino;

/**
 * {@code /wheel [bet]}. A spin is one {@link Casino#startInstant} round, decided and saved before the wheel turns, and
 * settled by {@link Casino#settle} after the animation or when the window closes (disconnect, death, server stop). After a
 * crash the casino's start-up {@code settleAll} settles it.
 */
public final class WheelGame {
	/** Game id in rounds and stats ({@code CasinoData.Round#game}). */
	public static final String ID = "wheel";
	/** Coloured name of the game (casino result line, big-win announcement, /casino stats). */
	public static final String LABEL = "§6Wheel of Fortune";
	/** Same permission as the other casino games. */
	static final String NODE = "smpeconomy.casino";
	/** The wheel at the casino: highest possible payout = the 50× jackpot. */
	static final Casino.InstantGame GAME = new Casino.InstantGame(ID, LABEL, WheelGui.ICON, WheelRounds::potential);

	private WheelGame() {}

	/** Entry point, called once from {@code CasinoFeature.register()}: game, commands, selftests. */
	public static void register() {
		Casino.registerGame(GAME);
		CommandRegistry.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher));
		WheelSelfTests.register();
	}

	public static GuiElementBuilder hubButton(ServerPlayer player) {
		long max = WheelRounds.maxStake(player.getUUID());
		return Gui.button(WheelGui.ICON, LABEL, () -> WheelGui.open(player, null),
			"§7Spin the wheel – win up to §b§l50×§7.",
			"§8Return to player ~" + WheelMath.percent(WheelMath.theoreticalRtp()),
			"§8/wheel <bet>",
			max > 0 ? "§7Max bet: " + Msg.moneyStr(max) : "§cLocked right now (Treasury/limit)",
			"",
			"§eClick to play");
	}

	private static void registerCommands(CommandDispatcher<CommandSourceStack> d) {
		d.register(Commands.literal("wheel").requires(Perms.require(NODE))
			.executes(ctx -> open(ctx, null))
			.then(Commands.literal("help").executes(WheelGame::help))
			.then(Commands.argument("bet", StringArgumentType.greedyString())
				.executes(ctx -> open(ctx, Args.money(ctx, "bet")))));
	}

	private static int open(CommandContext<CommandSourceStack> ctx, Long stake) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		if (!Gui.canOpen(p)) return 0;
		WheelGui.open(p, stake);
		return 1;
	}

	private static int help(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		Msg.info(src, LABEL + " §8– §7spin for up to §b§l50× §7your bet.");
		Msg.info(src, "§e/wheel <bet> §7spins right away (e.g. §e/wheel 500§7), §e/wheel §7opens the wheel.");
		StringBuilder odds = new StringBuilder("§7Odds: ");
		for (int i = WheelMath.TIERS.length - 1; i >= 0; i--) {
			int t = WheelMath.TIERS[i];
			odds.append(WheelGui.color(t)).append(WheelMath.label(t)).append(" §8").append(WheelMath.percent(WheelMath.chance(t)));
			if (i > 0) odds.append(" §7· ");
		}
		Msg.info(src, odds.toString());
		ServerPlayer p = src.getPlayer();
		long max = p == null ? 0 : WheelRounds.maxStake(p.getUUID());
		Msg.info(src, "§7Return to player " + WheelMath.percent(WheelMath.theoreticalRtp()) + "§7. "
			+ (p == null ? "" : max > 0 ? "Max bet now: " + Msg.moneyStr(max) + "§7. " : "§cLocked right now. §7")
			+ "Daily loss limit: §e/casino limit");
		return 1;
	}
}
