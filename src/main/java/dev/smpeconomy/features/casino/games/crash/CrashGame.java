package dev.smpeconomy.features.casino.games.crash;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.Args;
import dev.smpeconomy.core.Bots;
import dev.smpeconomy.core.CommandRegistry;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Perms;
import dev.smpeconomy.core.Scheduler;
import dev.smpeconomy.core.Treasury;
import dev.smpeconomy.core.test.TestPlayers;
import dev.smpeconomy.features.casino.Casino;
import dev.smpeconomy.features.casino.games.crash.CrashData.PlayerStats;
import dev.smpeconomy.features.casino.games.crash.CrashData.Round;

public final class CrashGame {
	static final String NODE = "smpeconomy.casino";
	static final String NODE_ADMIN = "smpeconomy.admin.casino";
	static final String SELFTEST_FEATURE = "casino-crash";

	private static final SimpleCommandExceptionType INVALID_BET =
		new SimpleCommandExceptionType(Msg.prefixed("§cInvalid bet. Examples: §e100§c, §e2.5k§c, §e1m§c, §e12.34"));
	private static final SimpleCommandExceptionType INVALID_AUTO =
		new SimpleCommandExceptionType(Msg.prefixed("§cInvalid auto cash-out. Examples: §e2§c, §e1.5§c, §e10x§c or §eoff"));

	private static Scheduler.Task watchdog;
	private static Scheduler.Task pendingRetry;
	private static boolean registered;

	private CrashGame() {}

	public static void register() {
		if (registered) return;
		registered = true;
		Crash.bootstrap(); // config + store exist before the common load in SERVER_STARTED
		Casino.registerBook(Crash.BOOK); // casino coverage, daily loss limit, limit reset and "one round" include crash
		CommandRegistry.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher));
		TestPlayers.onCleanup(Crash::purge);
		// registered after SmpEconomy's own lifecycle listeners → runs after the stores were loaded / GUIs were closed
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			if (!Crash.STORE.isLoaded()) return;
			// crash during a round: the stored round is settled now (last shown multiplier, else bet back)
			int n = Crash.settleAll("server restart");
			if (n > 0) SmpEconomy.LOG.info("[SMPEco] Crash: {} open round(s) settled after the restart", n);
			Crash.retryPending(null);
			watchdog = Scheduler.runRepeating(20, 20, Crash::watchdog);
			pendingRetry = Scheduler.runRepeating(1200, 1200, () -> Crash.retryPending(null));
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			// windows were already closed (→ settled); anything left over now (saved with SERVER_STOPPED's final save)
			if (Crash.STORE.isLoaded() && SmpEconomy.server() != null) {
				int n = Crash.settleAll("server stop");
				if (n > 0) SmpEconomy.LOG.info("[SMPEco] Crash: {} open round(s) settled on stop", n);
			}
			if (watchdog != null) watchdog.cancel();
			if (pendingRetry != null) pendingRetry.cancel();
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (Bots.isBot(handler.getPlayer())) return;
			UUID id = handler.getPlayer().getUUID();
			Scheduler.runLater(40, () -> Crash.retryPending(id));
		});
		CrashSelfTests.register();
	}

	public static GuiElementBuilder hubButton(ServerPlayer player) {
		CrashConfig c = Crash.cfg();
		List<String> lore = new ArrayList<>(List.of(
			"§7The multiplier climbs until the rocket",
			"§7crashes – cash out in time!",
			"§7Up to §a" + CrashMath.format(c.capH()) + "§7, RTP " + Crash.pct(100 - c.houseEdgePercent) + ".",
			"§8/crash <bet> [auto]"));
		PlayerStats s = Crash.peekStats(player.getUUID());
		long auto = s == null || s.lastAutoH < 0 || s.lastAutoH > c.capH() ? 0 : s.lastAutoH;
		long max = Crash.maxStake(player.getUUID(), auto);
		lore.add(max > 0 ? "§7Max bet: " + Msg.moneyStr(max) : "§cLocked right now (treasury/limit)");
		lore.add("");
		lore.add("§eClick to play");
		return Gui.button(Items.FIREWORK_ROCKET, Crash.LABEL, () -> CrashGui.open(player, null, null), lore.toArray(String[]::new));
	}

	private static void registerCommands(CommandDispatcher<CommandSourceStack> d) {
		d.register(Commands.literal("crash").requires(Perms.require(NODE))
			.executes(ctx -> run(ctx, p -> CrashGui.open(p, null, null)))
			.then(Commands.literal("help").executes(ctx -> {
				help(ctx.getSource());
				return 1;
			}))
			.then(Commands.argument("bet", StringArgumentType.word())
				.executes(ctx -> {
					long bet = bet(ctx);
					return run(ctx, p -> CrashGui.open(p, bet, null));
				})
				.then(Commands.argument("auto", StringArgumentType.word())
					.suggests((ctx, b) -> SharedSuggestionProvider.suggest(List.of("off", "1.5", "2", "3", "5", "10"), b))
					.executes(ctx -> {
						long bet = bet(ctx);
						long auto = auto(ctx);
						return run(ctx, p -> CrashGui.open(p, bet, auto));
					}))));

		d.register(Commands.literal("smpeco").requires(Perms.requireAdmin(Perms.ADMIN))
			.then(Commands.literal("crash")
				.requires(Perms.requireAdmin(NODE_ADMIN))
				.then(Commands.literal("info").then(playerArg().executes(CrashGame::info)))
				.then(Commands.literal("peek").then(playerArg().executes(CrashGame::peek)))
				.then(Commands.literal("settle").then(playerArg().executes(CrashGame::forceSettle)))
				.then(Commands.literal("reset").then(playerArg().executes(CrashGame::reset)))));
	}

	private static RequiredArgumentBuilder<CommandSourceStack, String> playerArg() {
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

	private static long bet(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		OptionalLong v = Money.parse(StringArgumentType.getString(ctx, "bet"));
		if (v.isEmpty()) throw INVALID_BET.create();
		return v.getAsLong();
	}

	/** Auto cash-out argument: {@code off}/{@code 0} → 0, else hundredths in [×1.01, cap]. */
	private static long auto(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		String raw = StringArgumentType.getString(ctx, "auto").trim().toLowerCase(Locale.ROOT);
		if (raw.equals("off") || raw.equals("none") || raw.equals("0")) return 0;
		long h = CrashMath.parseMultiplier(raw);
		if (h <= CrashMath.ONE || h > Crash.cfg().capH()) throw INVALID_AUTO.create();
		return h;
	}

	static void help(CommandSourceStack src) {
		CrashConfig c = Crash.cfg();
		Msg.info(src, Crash.LABEL + " §8– §7the multiplier climbs until the rocket crashes. Cash out in time to win bet × multiplier.");
		Msg.info(src, "§e/crash §8– §7open the game  §e/crash <bet> [auto|off] §8– §7launch right away, e.g. §f/crash 100 2.5");
		Msg.info(src, "§7Click the graph or §aCASH OUT §7before the crash; closing the window cashes out. Auto cash-out pays exactly its target.");
		Msg.info(src, "§7House edge " + Crash.pct(c.houseEdgePercent) + " (RTP " + Crash.pct(100 - c.houseEdgePercent) + "), max "
			+ CrashMath.format(c.capH()) + ", max bet ≤ " + Crash.pct(Casino.cfg().effectiveMaxBetPercent())
			+ " of the treasury, daily loss limit shared with the casino (§e/casino limit§7).");
	}

	private static int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		UUID id = Args.knownPlayer(ctx, "player");
		Round r = Crash.openRound(id);
		PlayerStats s = Crash.peekStats(id);
		long pending = Crash.data().pending.stream().filter(p -> p.player.equals(id)).mapToLong(p -> p.amount).sum();
		String line = "crash balance=" + EconomyService.balance(id) + " treasury=" + Treasury.balance()
			+ " maxBet=" + Casino.maxBet() + " cover=" + Crash.coverLimit() + " maxStake=" + Crash.maxStake(id, 0)
			+ " limit=" + Casino.lossLimit(id) + " netToday=" + Crash.netLossToday(id) + " room=" + Crash.lossRoom(id)
			+ " open=" + (r == null ? "none" : r.state + ":" + r.lastMultH) + " pending=" + pending
			+ " rounds=" + (s == null ? 0 : s.rounds) + " wagered=" + (s == null ? 0 : s.wagered) + " paid=" + (s == null ? 0 : s.paidOut);
		ctx.getSource().sendSuccess(() -> Msg.legacy(line), false);
		return 1;
	}

	private static int peek(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		// reveals the crash point, so console/RCON only (E2E), never in game
		if (ctx.getSource().isPlayer()) {
			Msg.error(ctx.getSource(), "Console/RCON only.");
			return 0;
		}
		UUID id = Args.knownPlayer(ctx, "player");
		Round r = Crash.openRound(id);
		String line = r == null ? "crash peek open=none"
			: "crash peek open=" + r.id + " state=" + r.state + " stake=" + r.stake + " crash=" + r.crashH + " auto=" + r.autoH
			+ " mult=" + r.lastMultH + " reserved=" + r.reserved + " cap=" + r.capH;
		ctx.getSource().sendSuccess(() -> Msg.legacy(line), false);
		return 1;
	}

	private static int forceSettle(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		UUID id = Args.knownPlayer(ctx, "player");
		Round r = Crash.openRound(id);
		if (r == null) {
			Msg.info(ctx.getSource(), "crash settle none");
			return 0;
		}
		Crash.Settled s = Crash.settle(r.id, "admin");
		Msg.info(ctx.getSource(), "crash settle payout=" + (s == null ? 0 : s.payout()));
		return 1;
	}

	private static int reset(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		UUID id = Args.knownPlayer(ctx, "player");
		Crash.resetDay(id);
		Msg.info(ctx.getSource(), "crash reset ok");
		return 1;
	}
}
