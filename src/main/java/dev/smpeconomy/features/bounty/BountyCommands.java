package dev.smpeconomy.features.bounty;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.Args;
import dev.smpeconomy.core.CommandRegistry;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Perms;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.TimeUtil;

/**
 * {@code /bounty}: GUI without arguments (console: top 10 in chat), {@code /bounty <player>} shows the
 * bounty, {@code /bounty <player> <amount>} places or raises it after a confirmation dialog. Admin: {@code
 * /smpeco bounty info|remove <player>}. Repair after a lock: {@code /smpeco bounty check}, {@code drop
 * <player>} (delete without refund), {@code surplus <account> [amount]} (refund escrow surplus), {@code
 * unlock}. Rules: {@code /smpeco bounty rule [team|sameip <true|false>|playtime <minutes>]} (machine-readable
 * for E2E).
 */
final class BountyCommands {
	static final String NODE = "smpeconomy.bounty";
	static final String ADMIN_NODE = "smpeconomy.admin.bounty";

	private BountyCommands() {}

	static void register() {
		CommandRegistry.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(root("bounty"));
			dispatcher.register(Commands.literal("smpeco")
				.requires(Perms.requireAdmin(Perms.ADMIN))
				.then(Commands.literal("bounty")
					.requires(Perms.requireAdmin(ADMIN_NODE))
					.then(Commands.literal("info")
						.then(Commands.argument("player", StringArgumentType.word()).suggests(Args.knownPlayers())
							.executes(BountyCommands::adminInfo)))
					.then(Commands.literal("remove")
						.then(Commands.argument("player", StringArgumentType.word()).suggests(Args.knownPlayers())
							.executes(BountyCommands::adminRemove)))
					.then(Commands.literal("drop")
						.then(Commands.argument("player", StringArgumentType.word()).suggests(Args.knownPlayers())
							.executes(BountyCommands::adminDrop)))
					.then(Commands.literal("check").executes(BountyCommands::adminCheck))
					.then(Commands.literal("unlock").executes(BountyCommands::adminUnlock))
					.then(Commands.literal("surplus")
						.then(Commands.argument("account", StringArgumentType.word()).suggests(Args.knownPlayers())
							.executes(ctx -> adminSurplus(ctx, false))
							.then(Commands.argument("amount", StringArgumentType.greedyString())
								.executes(ctx -> adminSurplus(ctx, true)))))
					.then(Commands.literal("rule")
						.executes(BountyCommands::adminRules)
						.then(Commands.literal("team")
							.then(Commands.argument("enabled", BoolArgumentType.bool()).executes(ctx -> adminSetRule(ctx, "team"))))
						.then(Commands.literal("sameip")
							.then(Commands.argument("enabled", BoolArgumentType.bool()).executes(ctx -> adminSetRule(ctx, "sameip"))))
						.then(Commands.literal("playtime")
							.then(Commands.argument("minutes", IntegerArgumentType.integer(0, BountyConfig.MAX_PLAYTIME_MINUTES))
								.executes(BountyCommands::adminSetPlaytime))))));
		});
	}

	private static LiteralArgumentBuilder<CommandSourceStack> root(String name) {
		return Commands.literal(name)
			.requires(Perms.require(NODE))
			.executes(BountyCommands::openOrList)
			.then(Commands.argument("player", StringArgumentType.word()).suggests(Args.knownPlayers())
				.executes(BountyCommands::show)
				.then(Commands.argument("amount", StringArgumentType.greedyString())
					.executes(BountyCommands::place)));
	}

	private static int openOrList(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		if (!ready(src)) return 0;
		ServerPlayer player = src.getPlayer();
		if (player != null) {
			BountyGui.open(player);
			return 1;
		}
		List<BountyLedger.Entry> top = Bounties.LIVE.top();
		if (top.isEmpty()) {
			Msg.info(src, "No active bounties.");
			return 1;
		}
		Msg.info(src, "§6Top bounties:");
		for (int i = 0; i < Math.min(10, top.size()); i++) {
			BountyLedger.Entry e = top.get(i);
			Msg.send(src, Msg.legacy("§8" + (i + 1) + ". §c" + EconomyService.nameOrUnknown(e.target()) + " §7" + Msg.moneyStr(e.total())
				+ " §8(" + BountyGui.contributors(e.contributors()) + ")"));
		}
		return 1;
	}

	private static int show(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack src = ctx.getSource();
		if (!ready(src)) return 0;
		UUID target = Args.knownPlayer(ctx, "player");
		String name = EconomyService.nameOrUnknown(target);
		long total = Bounties.LIVE.total(target);
		if (total <= 0) {
			Msg.info(src, "There's no bounty on §f" + name + "§7.");
			return 1;
		}
		Msg.info(src, "Bounty on §c" + name + "§7: " + Msg.moneyStr(total) + " §8(" + BountyGui.contributors(Bounties.LIVE.contributions(target).size()) + ")");
		return 1;
	}

	/**
	 * Does not charge right away. Errors (minimum, funds, limit, self) go straight to chat; otherwise the
	 * same confirmation dialog as the GUI opens, so a typo in the amount or name costs nothing.
	 */
	private static int place(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack src = ctx.getSource();
		ServerPlayer player = src.getPlayerOrException();
		if (!ready(src)) return 0;
		UUID target = Args.knownPlayer(ctx, "player");
		long amount = Args.money(ctx, "amount");
		if (!Gui.canOpen(player)) {
			Msg.error(player, "The confirmation can't be opened right now – nothing was charged.");
			return 0;
		}
		return BountyGui.confirm(player, target, amount, null) ? 1 : 0;
	}

	/**
	 * Dry run with {@link BountyLedger#check} before the dialog. {@link BountyLedger#place} checks everything
	 * again on confirm.
	 */
	static boolean precheck(ServerPlayer player, UUID target, long amount) {
		if (!Bounties.ready()) {
			Msg.error(player, "Bounties are unavailable right now.");
			return false;
		}
		BountyLedger.PlaceStatus status = Bounties.LIVE.check(player.getUUID(), target, amount);
		if (status.ok()) return true;
		reportError(player, status, EconomyService.nameOrUnknown(target));
		return false;
	}

	static boolean placeAndReport(ServerPlayer player, UUID target, long amount) {
		if (!Bounties.ready()) {
			Msg.error(player, "Bounties are unavailable right now.");
			return false;
		}
		long now = System.currentTimeMillis();
		BountyLedger.Placement p = Bounties.LIVE.place(player.getUUID(), target, amount, now);
		String name = EconomyService.nameOrUnknown(target);
		if (p.status().ok()) {
			boolean raised = p.total() > p.amount();
			Msg.success(player, "Bounty on §c" + name + " §a" + (raised ? "raised" : "placed") + ": " + Msg.moneyStr(p.amount())
				+ " §8(total " + Msg.moneyStr(p.total()) + "§8)");
			Gui.successSound(player);
			BountyNotify.placed(player.level().getServer(), player.getUUID(), player.getGameProfile().name(), target, name, p, now);
			DataStore.requestFlush();
			return true;
		}
		reportError(player, p.status(), name);
		return false;
	}

	private static void reportError(ServerPlayer player, BountyLedger.PlaceStatus status, String name) {
		BountyConfig cfg = Bounties.config();
		String error = switch (status) {
			case SELF -> "You can't place a bounty on yourself.";
			case BELOW_MIN -> "The minimum bounty is " + Money.format(cfg.minAmount) + ".";
			case INSUFFICIENT_FUNDS -> "Not enough money. Balance: " + Money.format(EconomyService.balance(player.getUUID())) + ".";
			case LIMIT_EXCEEDED -> "The bounty on §f" + name + " §cis at the limit (" + Money.format(cfg.maxTotal()) + ").";
			case INVALID_TARGET -> "You can't place a bounty on this account.";
			case NO_ACCOUNT -> "Bounties are unavailable right now.";
			case LOCKED -> "Bounties are locked right now (escrow check). Please report this to an admin.";
			default -> "Invalid amount.";
		};
		Msg.error(player, error);
		Gui.failSound(player);
	}

	/** By name (online, then name cache), without system accounts, like {@link Args#knownPlayer}. */
	static Optional<UUID> resolvePlayer(MinecraftServer server, String input) {
		if (input == null) return Optional.empty();
		String name = input.trim();
		if (name.isEmpty()) return Optional.empty();
		ServerPlayer online = server == null ? null : server.getPlayerList().getPlayerByName(name);
		Optional<UUID> id = online != null ? Optional.of(online.getUUID()) : EconomyService.findByName(name);
		return id.filter(u -> !EconomyService.isSystemAccount(u));
	}

	private static boolean ready(CommandSourceStack src) {
		if (Bounties.ready()) return true;
		Msg.error(src, "Bounties are unavailable right now.");
		return false;
	}

	private static int adminInfo(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack src = ctx.getSource();
		UUID target = Args.knownPlayer(ctx, "player");
		Map<UUID, Long> contributions = Bounties.LIVE.contributions(target);
		Msg.send(src, Msg.legacy("§7bounty target=" + EconomyService.nameOrUnknown(target) + " total=" + Bounties.LIVE.total(target)
			+ " contributors=" + contributions.size() + " balance=" + EconomyService.balance(target)
			+ " playtime=" + PlayerDataService.getPlaytimeTicks(target)));
		contributions.forEach((c, v) -> Msg.send(src, Msg.legacy("§8  contributor " + EconomyService.nameOrUnknown(c) + "=" + v)));
		Msg.send(src, Msg.legacy("§7bounty escrow=" + Bounties.LIVE.escrowBalance() + " sum=" + Bounties.LIVE.totalEscrowed()));
		return 1;
	}

	private static int adminRemove(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack src = ctx.getSource();
		if (!ready(src)) return 0;
		UUID target = Args.knownPlayer(ctx, "player");
		String name = EconomyService.nameOrUnknown(target);
		Map<UUID, Long> refunded = Bounties.LIVE.refundAll(target);
		long sum = 0;
		for (Map.Entry<UUID, Long> e : refunded.entrySet()) {
			sum += e.getValue();
			Msg.sendOrQueue(e.getKey(), "§7Your bounty on §c" + name + " §7was removed by an admin. Refunded: " + Msg.moneyStr(e.getValue()));
		}
		if (!refunded.isEmpty()) {
			Msg.sendOrQueue(target, "§7The bounty on you was removed by an admin.");
			DataStore.requestFlush();
		}
		long remaining = Bounties.LIVE.total(target);
		Msg.send(src, Msg.legacy((remaining == 0 ? "§a" : "§c") + "bounty removed target=" + name + " refunded=" + sum
			+ " contributors=" + refunded.size() + " remaining=" + remaining + " §8(" + Money.format(sum) + " refunded)"));
		if (remaining > 0 && Bounties.LIVE.isLocked()) {
			Msg.error(src, "Bounties are locked (" + Bounties.LIVE.lockReason() + "): refunds are blocked. See /smpeco bounty check.");
		}
		return remaining == 0 ? 1 : 0;
	}

	/**
	 * Repair: deletes the bounty without refund and the money stays in escrow. Only for bounties already paid
	 * out (log line "claims") that reappeared in bounties.json after a crash or backup restore.
	 */
	private static int adminDrop(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack src = ctx.getSource();
		if (!ready(src)) return 0;
		UUID target = Args.knownPlayer(ctx, "player");
		String name = EconomyService.nameOrUnknown(target);
		Map<UUID, Long> dropped = Bounties.LIVE.drop(target);
		long sum = dropped.values().stream().mapToLong(Long::longValue).sum();
		if (!dropped.isEmpty()) {
			DataStore.requestFlush();
			SmpEconomy.LOG.warn("[SMPEco] Bounty on {} deleted by {} without refund ({}): {}", name, src.getTextName(), Money.format(sum), dropped);
		}
		Msg.send(src, Msg.legacy("§7bounty dropped target=" + name + " total=" + sum + " contributors=" + dropped.size()
			+ " §8(no refund, the money stays in escrow)"));
		return adminCheck(ctx);
	}

	/**
	 * Repair: moves at most the escrow surplus to an account: a contributor whose stake (log line "places ...
	 * on ...") is missing from bounties.json, or the treasury. Without an amount, the whole surplus.
	 */
	private static int adminSurplus(CommandContext<CommandSourceStack> ctx, boolean withAmount) throws CommandSyntaxException {
		CommandSourceStack src = ctx.getSource();
		if (!ready(src)) return 0;
		UUID to = Args.knownAccount(ctx, "account");
		long surplus = Bounties.LIVE.escrowSurplus();
		long amount = withAmount ? Args.money(ctx, "amount") : surplus;
		if (surplus <= 0) {
			Msg.error(src, "There's no surplus in escrow.");
			adminCheck(ctx);
			return 0;
		}
		EconomyService.Result r = Bounties.LIVE.releaseSurplus(to, amount);
		String name = EconomyService.nameOrUnknown(to);
		if (!r.ok()) {
			Msg.error(src, r == EconomyService.Result.INSUFFICIENT_FUNDS
				? "You can transfer at most the surplus (" + Money.format(surplus) + ")."
				: "Transfer to " + name + " failed: " + r.message());
			return 0;
		}
		DataStore.requestFlush();
		SmpEconomy.LOG.warn("[SMPEco] Bounty escrow: {} moves surplus {} to {}", src.getTextName(), Money.format(amount), name);
		if (!EconomyService.isSystemAccount(to)) {
			Msg.sendOrQueue(to, "§7An admin refunded you " + Msg.moneyStr(amount) + " from the bounty escrow (data repair).");
		}
		Msg.send(src, Msg.legacy("§7bounty surplus to=" + name + " amount=" + amount + " §8(" + Money.format(amount) + ")"));
		return adminCheck(ctx);
	}

	private static int adminUnlock(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		if (!ready(src)) return 0;
		String before = Bounties.LIVE.lockReason();
		BountyLedger.UnlockStatus status = Bounties.LIVE.unlock();
		switch (status) {
			case OK -> {
				DataStore.requestFlush();
				SmpEconomy.LOG.warn("[SMPEco] Bounty lock lifted by {} (was: {})", src.getTextName(), before);
				Msg.send(src, Msg.legacy("§abounty unlock=ok"));
			}
			case NOT_LOCKED -> Msg.send(src, Msg.legacy("§7bounty unlock=not_locked"));
			case MISMATCH -> {
				Msg.send(src, Msg.legacy("§cbounty unlock=mismatch"));
				Msg.error(src, "Escrow ≠ sum of all bounties – fix it with drop/surplus first.");
				adminCheck(ctx);
			}
		}
		return status == BountyLedger.UnlockStatus.OK ? 1 : 0;
	}

	private static int adminRules(CommandContext<CommandSourceStack> ctx) {
		BountyConfig cfg = Bounties.config();
		Msg.send(ctx.getSource(), Msg.legacy("§7bounty rules team=" + cfg.blockSameTeam + " sameip=" + cfg.blockSameIp
			+ " playtime=" + cfg.minKillerPlaytimeMinutes + " notice=" + cfg.targetNoticeCooldownSeconds + " fee=" + cfg.feePercent
			+ " min=" + cfg.minAmount));
		return 1;
	}

	private static int adminSetPlaytime(CommandContext<CommandSourceStack> ctx) {
		Bounties.config().minKillerPlaytimeMinutes = IntegerArgumentType.getInteger(ctx, "minutes");
		saveConfig(ctx);
		return adminRules(ctx);
	}

	/** For example the same-IP rule, for E2E bots on one machine. */
	private static int adminSetRule(CommandContext<CommandSourceStack> ctx, String rule) {
		boolean on = BoolArgumentType.getBool(ctx, "enabled");
		BountyConfig cfg = Bounties.config();
		if ("team".equals(rule)) cfg.blockSameTeam = on;
		else cfg.blockSameIp = on;
		saveConfig(ctx);
		return adminRules(ctx);
	}

	private static void saveConfig(CommandContext<CommandSourceStack> ctx) {
		try {
			Bounties.CONFIG.save();
		} catch (IOException e) {
			Msg.error(ctx.getSource(), "Couldn't save bounty.json: " + e.getMessage());
		}
	}

	/**
	 * Machine-readable: {@code ok=true} only if escrow equals the bounty total and nothing is locked. When
	 * locked, also prints the reason, all bounties and the repair steps.
	 */
	private static int adminCheck(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		if (!ready(src)) return 0;
		BountyLedger l = Bounties.LIVE;
		long escrow = l.escrowBalance();
		long sum = l.totalEscrowed();
		boolean locked = l.isLocked();
		boolean ok = escrow == sum && !locked;
		long deficit = l.escrowDeficit();
		long surplus = l.escrowSurplus();
		Msg.send(src, Msg.legacy((ok ? "§a" : "§c") + "bounty escrow=" + escrow + " sum=" + sum + " active=" + l.top().size()
			+ " ok=" + ok + " deficit=" + deficit + " surplus=" + surplus + " locked=" + locked));
		if (locked) {
			Msg.error(src, "Locked for " + TimeUtil.formatDuration(System.currentTimeMillis() - l.lockedAt()) + ": " + l.lockReason());
			for (BountyLedger.Entry e : l.top()) {
				Msg.send(src, Msg.legacy("§8  " + EconomyService.nameOrUnknown(e.target()) + " " + Money.format(e.total()) + ": "
					+ describe(l.contributions(e.target()))));
			}
		}
		if (deficit > 0) {
			Msg.error(src, "Deficit " + Money.format(deficit) + ": delete bounties that were already paid out (log \"collected\") with "
				+ "/smpeco bounty drop <player>.");
		} else if (surplus > 0) {
			Msg.error(src, "Surplus " + Money.format(surplus) + ": money without a bounty entry. Delete bounties already paid out with drop, "
				+ "refund lost stakes (log \"staked\") with /smpeco bounty surplus <contributor> <amount>.");
		}
		if (locked && escrow == sum) Msg.info(src, "Escrow matches. Once checked, unlock with /smpeco bounty unlock");
		return ok ? 1 : 0;
	}

	private static String describe(Map<UUID, Long> contributions) {
		StringBuilder sb = new StringBuilder();
		contributions.forEach((c, v) -> {
			if (!sb.isEmpty()) sb.append(", ");
			sb.append(EconomyService.nameOrUnknown(c)).append(' ').append(Money.format(v));
		});
		return sb.toString();
	}
}
