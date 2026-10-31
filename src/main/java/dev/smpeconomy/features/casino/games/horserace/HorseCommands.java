package dev.smpeconomy.features.casino.games.horserace;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
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
import dev.smpeconomy.core.Treasury;
import dev.smpeconomy.features.casino.Casino;
import dev.smpeconomy.features.casino.games.horserace.HorseData.Bet;
import dev.smpeconomy.features.casino.games.horserace.HorseData.Pending;
import dev.smpeconomy.features.casino.games.horserace.HorseData.PlayerStats;
import dev.smpeconomy.features.casino.games.horserace.HorseData.Race;

/**
 * {@code /horserace [help | bet <1-6> <amount>]} and admin/RCON {@code /smpeco casino horserace status | info <player> |
 * start | settle} (machine-readable lines for the e2e test).
 */
final class HorseCommands {
	static final String ROOT = "horserace";
	static final String NODE = "smpeconomy.casino";
	static final String NODE_ADMIN = "smpeconomy.admin.casino";

	private HorseCommands() {}

	static void register() {
		CommandRegistry.register((dispatcher, registryAccess, environment) -> register(dispatcher));
	}

	private static void register(CommandDispatcher<CommandSourceStack> d) {
		d.register(Commands.literal(ROOT).requires(Perms.require(NODE))
			.executes(HorseCommands::open)
			.then(Commands.literal("help").executes(HorseCommands::help))
			.then(Commands.literal("bet")
				.then(Commands.argument("horse", IntegerArgumentType.integer(1, HorseMath.HORSES))
					.then(Commands.argument("amount", StringArgumentType.greedyString()).executes(HorseCommands::bet)))));

		d.register(Commands.literal("smpeco").requires(Perms.requireAdmin(Perms.ADMIN))
			.then(Commands.literal("casino").requires(Perms.requireAdmin(NODE_ADMIN))
				.then(Commands.literal("horserace")
					.then(Commands.literal("status").executes(HorseCommands::status))
					.then(Commands.literal("info").then(Commands.argument("player", StringArgumentType.word()).suggests(Args.knownPlayers())
						.executes(HorseCommands::info)))
					.then(Commands.literal("start").executes(HorseCommands::start))
					.then(Commands.literal("settle").executes(HorseCommands::settle)))));
	}

	private static boolean ready(CommandSourceStack src) {
		if (HorseRace.STORE.isLoaded()) return true;
		Msg.error(src, "The horse race is not available right now.");
		return false;
	}

	private static int open(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		if (!ready(ctx.getSource()) || !Gui.canOpen(p)) return 0;
		HorseRaceGui.open(p);
		return 1;
	}

	private static int help(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		HorseConfig c = HorseRace.cfg();
		Msg.info(src, HorseRace.LABEL + " §7– 6 horses, one race for everyone.");
		Msg.info(src, "§e/" + ROOT + " §7– open the track: pick a horse, set your bet, click §aBet§7.");
		Msg.info(src, "§e/" + ROOT + " bet <1-6> <amount> §7– bet directly, e.g. §f/" + ROOT + " bet 3 50");
		Msg.info(src, "§7A race starts §f" + c.bettingWindowSeconds + " s §7after the first bet (at most one every §f"
			+ HorseRace.seconds(c.raceIntervalSeconds * 1000L) + "§7). Your horse wins: §abet × odds§7. Closing the window keeps your bet.");
		Msg.info(src, "§8Limits as in /casino: max bet ≤ 2 % of the treasury, daily loss limit (/casino limit).");
		return 1;
	}

	private static int bet(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		if (!ready(ctx.getSource())) return 0;
		int horse = IntegerArgumentType.getInteger(ctx, "horse") - 1;
		OptionalLong amount = Money.parse(StringArgumentType.getString(ctx, "amount"));
		if (amount.isEmpty()) {
			Msg.error(p, "Invalid amount, e.g. §f/" + ROOT + " bet 3 50");
			return 0;
		}
		if (!HorseRace.placeBet(p, horse, amount.getAsLong())) return 0;
		p.sendSystemMessage(Msg.prefixed("§7").append(Msg.clickRun("§e[Watch the race]", "/" + ROOT, "§fOpen the race track\n§8/" + ROOT)));
		return 1;
	}

	private static int status(CommandContext<CommandSourceStack> ctx) {
		if (!ready(ctx.getSource())) return 0;
		RaceEngine e = HorseRace.LIVE;
		Race r = e.card();
		HorseData d = HorseRace.data();
		long now = e.clock.getAsLong();
		StringBuilder odds = new StringBuilder();
		StringBuilder chances = new StringBuilder();
		for (int h = 0; h < HorseMath.HORSES; h++) {
			if (h > 0) {
				odds.append(',');
				chances.append(',');
			}
			odds.append(r.odds[h]);
			chances.append(r.chances[h]);
		}
		boolean running = HorseData.RUNNING.equals(r.state);
		String line = "horserace status race=" + r.id + " state=" + r.state
			+ " closesIn=" + (r.closesAt > 0 && !running ? Math.max(0, r.closesAt - now) : -1)
			+ " bets=" + r.bets.size() + " pool=" + r.pool() + " odds=" + odds + " chances=" + chances
			+ " winner=" + (running ? r.winner + 1 : 0) + " settleIn=" + (running ? Math.max(0, r.settleAt - now) : -1)
			+ " reserved=" + e.reserved() + " pendingTotal=" + e.pendingTotal()
			+ " lastRace=" + (d.last == null ? 0 : d.last.id) + " lastWinner=" + (d.last == null ? 0 : d.last.winner + 1);
		ctx.getSource().sendSuccess(() -> Msg.legacy(line), false);
		return 1;
	}

	private static int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		if (!ready(ctx.getSource())) return 0;
		UUID id = Args.knownPlayer(ctx, "player");
		RaceEngine e = HorseRace.LIVE;
		Race r = e.card();
		Bet b = r.bets.get(id);
		long pending = 0;
		for (Pending p : HorseRace.data().pending) if (p.player.equals(id)) pending += p.amount;
		PlayerStats s = HorseRace.data().players.get(id);
		String line = "horserace info balance=" + EconomyService.balance(id) + " treasury=" + Treasury.balance()
			+ " maxBet=" + Casino.maxBet() + " maxWin=" + Casino.maxWin() + " free=" + e.freeCoverage()
			+ " maxStake=" + e.maxStake(id, -1) + " room=" + e.lossRoom(id) + " netToday=" + e.netToday(id)
			+ " otherUsed=" + Casino.lossUsed(id, e) + " race=" + r.id + " state=" + r.state
			+ " bet=" + (b == null ? 0 : b.horse + 1) + " stake=" + (b == null ? 0 : b.stake) + " payout=" + (b == null ? -1 : b.payout)
			+ " pending=" + pending + " races=" + (s == null ? 0 : s.races) + " wagered=" + (s == null ? 0 : s.wagered)
			+ " paid=" + (s == null ? 0 : s.paidOut);
		ctx.getSource().sendSuccess(() -> Msg.legacy(line), false);
		return 1;
	}

	private static int start(CommandContext<CommandSourceStack> ctx) {
		if (!ready(ctx.getSource())) return 0;
		RaceEngine e = HorseRace.LIVE;
		Race r = e.card();
		int bets = r.bets.size();
		boolean ok = e.forceStart(e.clock.getAsLong());
		String line = ok ? "horserace start ok race=" + r.id + " bets=" + bets : "horserace start running race=" + r.id;
		ctx.getSource().sendSuccess(() -> Msg.legacy(line), false);
		return ok ? 1 : 0;
	}

	private static int settle(CommandContext<CommandSourceStack> ctx) {
		if (!ready(ctx.getSource())) return 0;
		RaceEngine e = HorseRace.LIVE;
		Race r = e.card();
		String line;
		if (HorseData.RUNNING.equals(r.state)) line = "horserace settle paid=" + e.finish("admin");
		else line = "horserace settle refunded=" + e.refundAll("admin");
		ctx.getSource().sendSuccess(() -> Msg.legacy(line), false);
		return 1;
	}
}
