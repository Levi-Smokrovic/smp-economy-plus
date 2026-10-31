package dev.smpeconomy.core;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import dev.smpeconomy.core.compat.Mc;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.test.GuiTest;
import dev.smpeconomy.core.test.SelfTests;

/**
 * The {@code /smpeco} root (node {@link Perms#ADMIN}). {@code pricemod} also needs {@link Perms#ADMIN_PRICES},
 * {@code notify} needs {@link Perms#ADMIN_NOTIFY}. Features attach subcommands by registering their own
 * {@code Commands.literal("smpeco")} tree; Brigadier merges same-named nodes, and the root requirement comes from the first
 * registration.
 */
public final class SmpecoCommand {
	private static final int MAX_FAILURE_LINES = 10;
	public static final String ADMIN_MODIFIER_SOURCE = "admin";

	private SmpecoCommand() {}

	public static void register() {
		CommandRegistry.register((dispatcher, registryAccess, environment) -> dispatcher.register(
			Commands.literal("smpeco")
				.requires(Perms.requireAdmin(Perms.ADMIN))
				.then(Commands.literal("reload").executes(SmpecoCommand::reload))
				.then(Commands.literal("save").executes(SmpecoCommand::save))
				.then(Commands.literal("status").executes(SmpecoCommand::status))
				.then(Commands.literal("selftest")
					.requires(source -> SelfTests.ENABLED)
					.executes(ctx -> selftest(ctx, null))
					.then(Commands.argument("feature", StringArgumentType.word())
						.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(SelfTests.features(), builder))
						.executes(ctx -> selftest(ctx, StringArgumentType.getString(ctx, "feature")))))
				.then(Commands.literal("placed")
					.then(Commands.literal("confirm").executes(SmpecoCommand::placedAcknowledge))
					.then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(SmpecoCommand::placed)))
				.then(Commands.literal("notify")
					.requires(Perms.requireAdmin(Perms.ADMIN_NOTIFY))
					.then(Commands.argument("player", StringArgumentType.word()).suggests(Args.knownPlayers())
						.then(Commands.argument("text", StringArgumentType.greedyString()).executes(SmpecoCommand::notify))))
				.then(Commands.literal("price")
					.then(Commands.argument("item", ItemArgument.item(registryAccess))
						.executes(ctx -> price(ctx, null))
						.then(Commands.argument("player", StringArgumentType.word()).suggests(Args.knownPlayers())
							.executes(ctx -> price(ctx, Args.knownPlayer(ctx, "player"))))))
				.then(Commands.literal("pricemod")
					.requires(Perms.requireAdmin(Perms.ADMIN_PRICES))
					.then(Commands.literal("clear").executes(SmpecoCommand::priceModClear))
					.then(Commands.argument("item", ItemArgument.item(registryAccess))
						.then(Commands.argument("factor", DoubleArgumentType.doubleArg(PriceModifiers.MIN_FACTOR, PriceModifiers.MAX_FACTOR))
							.executes(ctx -> priceMod(ctx, 10))
							.then(Commands.argument("minutes", IntegerArgumentType.integer(1, 24 * 60))
								.executes(ctx -> priceMod(ctx, IntegerArgumentType.getInteger(ctx, "minutes")))))))
				.then(Commands.literal("treasury").executes(SmpecoCommand::treasury)
					.then(Commands.literal("flows").executes(SmpecoCommand::treasuryFlows)))
				.then(Commands.literal("guitest")
					.requires(source -> SelfTests.ENABLED)
					.executes(SmpecoCommand::guiTest))));
	}

	private static int reload(CommandContext<CommandSourceStack> ctx) {
		List<String> errors = ConfigManager.reloadAll();
		if (errors.isEmpty()) {
			Msg.success(ctx.getSource(), "Configs reloaded.");
			return 1;
		}
		for (String e : errors) Msg.error(ctx.getSource(), e);
		return 0;
	}

	private static int save(CommandContext<CommandSourceStack> ctx) {
		// Like /save-all: vanilla saves players and world, the mod before them via the PlayerList.saveAll mixin, so
		// everything is consistent rather than player files ahead of the world.
		ctx.getSource().getServer().saveEverything(true, false, true);
		DataStore.flushDirty(); // what changed while chunks were saving
		PlacedBlockTracker.save();
		var problem = DataStore.persistenceProblem();
		if (problem.isPresent()) {
			Msg.error(ctx.getSource(), "Saving impaired " + problem.get());
			return 0;
		}
		Msg.success(ctx.getSource(), "All data saved.");
		return 1;
	}

	/**
	 * First line is machine-readable: {@code status persistence=ok|degraded journal=<bool> dirty=<n> written=<n>
	 * skipped=<n> failed=<n> configProblems=<n> placed=ok|unreliable lastCommitMs=<n> slowCommits=<n>}.
	 */
	private static int status(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		var problem = DataStore.persistenceProblem();
		List<String> dirty = DataStore.dirtyStoreNames();
		List<String> configProblems = ConfigManager.problems();
		var placed = PlacedBlockTracker.problem();
		String line = "status persistence=" + (problem.isPresent() ? "degraded" : "ok") + " journal=" + DataStore.journalPending()
			+ " dirty=" + dirty.size() + " written=" + DataStore.filesWritten() + " skipped=" + DataStore.writesSkipped()
			+ " failed=" + DataStore.failedCommits() + " configProblems=" + configProblems.size()
			+ " placed=" + (placed.isPresent() ? "unreliable" : "ok") + " lastCommitMs=" + DataStore.lastCommitMs()
			+ " slowCommits=" + DataStore.slowCommits();
		src.sendSuccess(() -> Msg.legacy("§7" + line), false);
		problem.ifPresent(p -> Msg.send(src, Msg.legacy("§cSaving impaired " + p)));
		placed.ifPresent(p -> Msg.send(src, Msg.legacy("§cPlaced blocks unreliable (mining pays nothing): " + p
			+ " §7– restore the copy from backups/ + restart, or §e/smpeco placed confirm")));
		if (!dirty.isEmpty()) Msg.send(src, Msg.legacy("§8Unsaved: " + String.join(", ", dirty)));
		for (String c : configProblems) Msg.send(src, Msg.legacy("§cConfig: " + c));
		return problem.isEmpty() && configProblems.isEmpty() && placed.isEmpty() ? 1 : 0;
	}

	private static int selftest(CommandContext<CommandSourceStack> ctx, String feature) {
		CommandSourceStack src = ctx.getSource();
		// Test players reach Ledger with no name: its inserts fail and retry until /stop hangs on the full queue.
		if (FabricLoader.getInstance().isModLoaded("ledger")) {
			Msg.error(src, "Self tests don't run with Ledger installed. Remove it from a test copy of the server first.");
			return 0;
		}
		if (feature != null && !SelfTests.features().contains(feature)) {
			Msg.error(src, "No tests for feature '" + feature + "'.");
			return 0;
		}
		SelfTests.Summary summary = SelfTests.run(src.getServer(), feature, SmpEconomy.LOG::info);
		// Keep it short, RCON replies above 4 KB get fragmented: only errors and the total go to the source.
		int shown = 0;
		for (SelfTests.Result r : summary.results()) {
			if (r.passed()) continue;
			if (shown++ >= MAX_FAILURE_LINES) break;
			String line = r.line();
			Msg.send(src, Msg.legacy("§c" + (line.length() > 300 ? line.substring(0, 300) + "…" : line)));
		}
		if (summary.failed() > MAX_FAILURE_LINES) Msg.send(src, Msg.legacy("§c… and " + (summary.failed() - MAX_FAILURE_LINES) + " more (see log)"));
		Msg.send(src, Msg.legacy((summary.failed() == 0 ? "§a" : "§c") + summary.line()));
		return summary.failed() == 0 ? 1 : 0;
	}

	private static int notify(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		UUID target = Args.knownPlayer(ctx, "player");
		String text = StringArgumentType.getString(ctx, "text");
		SmpEconomy.LOG.info("[SMPEco] /smpeco notify from {} to {}: {}", ctx.getSource().getTextName(), EconomyService.nameOrUnknown(target), text);
		Msg.sendOrQueue(target, "§f" + text);
		Msg.success(ctx.getSource(), "Message sent to " + EconomyService.nameOrUnknown(target) + " (or queued for their next join).");
		return 1;
	}

	private static int placed(CommandContext<CommandSourceStack> ctx) {
		BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");
		boolean placed = PlacedBlockTracker.isPlayerPlaced(ctx.getSource().getLevel(), pos);
		Msg.send(ctx.getSource(), Msg.legacy("§7" + pos.toShortString() + " placed=" + placed + " reliable=" + PlacedBlockTracker.reliable()));
		return placed ? 1 : 0;
	}

	private static int placedAcknowledge(CommandContext<CommandSourceStack> ctx) {
		String error = PlacedBlockTracker.acknowledge(ctx.getSource().getTextName());
		if (error != null) {
			Msg.error(ctx.getSource(), error);
			return 0;
		}
		Msg.success(ctx.getSource(), "Placed blocks confirmed – from now on only blocks placed since the start count.");
		return 1;
	}

	/** Machine-readable for e2e scripts. */
	private static int price(CommandContext<CommandSourceStack> ctx, UUID target) {
		Item item = Mc.inputItem(ItemArgument.getItem(ctx, "item"));
		ServerPlayer self = ctx.getSource().getPlayer();
		UUID player = target != null ? target : self != null ? self.getUUID() : new UUID(0, 0);
		PriceGuard.Breakdown b = PriceGuard.breakdown(player, item);
		long shop = b.effectiveBuy();
		long market = Services.marketValue(item).orElse(0);
		String line = "§7" + Items.id(item) + " sell=" + b.effectiveSell() + " buy=" + shop
			+ " baseSell=" + b.baseSell() + " baseBuy=" + b.baseBuy()
			+ " mod=" + String.format(java.util.Locale.ROOT, "%.3f", b.itemModifier())
			+ " buyMod=" + String.format(java.util.Locale.ROOT, "%.3f", PriceGuard.buySideModifier(item))
			+ " sellMult=" + String.format(java.util.Locale.ROOT, "%.3f", b.sellMultiplier())
			+ " discount=" + String.format(java.util.Locale.ROOT, "%.3f", b.shopDiscount())
			+ " pivot=" + b.pivot() + " market=" + market
			+ " npcBuyMax=" + PriceGuard.maxNpcBuyPrice(player, item, market)
			+ " npcOrderMax=" + PriceGuard.maxNpcOrderPrice(player, item, market)
			+ " minSystemSale=" + PriceGuard.minSystemSalePrice(player, item, market)
			+ " floor=" + b.shopFloorApplied() + " capped=" + b.sellCapped()
			+ " sellNoCrash=" + PriceGuard.effectiveSellPriceWithoutCrash(player, item).orElse(-1);
		Msg.send(ctx.getSource(), Msg.legacy(line));
		for (String l : b.loreLines()) Msg.send(ctx.getSource(), Msg.legacy("  " + l));
		return 1;
	}

	private static int priceMod(CommandContext<CommandSourceStack> ctx, int minutes) {
		Item item = Mc.inputItem(ItemArgument.getItem(ctx, "item"));
		double factor = DoubleArgumentType.getDouble(ctx, "factor");
		// The factor affects /sell for all players (non-shop items have no pivot cap), so always log it.
		SmpEconomy.LOG.warn("[SMPEco] /smpeco pricemod by {}: {} factor {} for {} min", ctx.getSource().getTextName(), Items.id(item), factor, minutes);
		PriceModifiers.set(ADMIN_MODIFIER_SOURCE, item, factor, 0, minutes * 60_000L, 60_000L, "Admin");
		Msg.success(ctx.getSource(), "Price modifier " + Items.id(item) + " = " + PriceGuard.percent(factor) + " for " + minutes + " min.");
		return 1;
	}

	private static int priceModClear(CommandContext<CommandSourceStack> ctx) {
		SmpEconomy.LOG.info("[SMPEco] /smpeco pricemod clear by {}", ctx.getSource().getTextName());
		PriceModifiers.removeSource(ADMIN_MODIFIER_SOURCE);
		Msg.success(ctx.getSource(), "Admin price modifiers removed.");
		return 1;
	}

	private static int treasury(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		Msg.send(src, Msg.legacy("§7treasury balance=" + Treasury.balance() + " allowance=" + Treasury.allowance()
			+ " spentLastHour=" + Treasury.spentLastHour() + " maxPerHour=" + Treasury.maxSpendPerHour()
			+ " §8(" + Money.format(Treasury.balance()) + ")"));
		for (Map.Entry<String, Treasury.Flow> e : Treasury.stats24h().entrySet()) {
			Msg.send(src, Msg.legacy("§8  " + e.getKey() + " (" + Treasury.reasonLabel(e.getKey()) + "): §a+" + Money.format(e.getValue().income())
				+ " §c-" + Money.format(e.getValue().expense())));
		}
		return 1;
	}

	/**
	 * One machine-readable line: {@code treasury flows balance=<c> flow:<reason>=<in>/<out> ...}. Balance and totals
	 * come from the same tick, so e2e tests can check their own reason's bookings rather than the raw balance, which
	 * background jobs change at any time. Unlike the 24-hour stats, no old hour ever drops out.
	 */
	private static int treasuryFlows(CommandContext<CommandSourceStack> ctx) {
		StringBuilder sb = new StringBuilder("treasury flows balance=").append(Treasury.balance());
		for (Map.Entry<String, Treasury.Flow> e : Treasury.totalsAllTime().entrySet()) {
			sb.append(" flow:").append(e.getKey().replaceAll("\\s", "_")).append('=')
				.append(e.getValue().income()).append('/').append(e.getValue().expense());
		}
		String line = sb.toString();
		ctx.getSource().sendSuccess(() -> Msg.legacy(line), false);
		return 1;
	}

	private static int guiTest(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		GuiTest.open(ctx.getSource().getPlayerOrException());
		return 1;
	}
}
