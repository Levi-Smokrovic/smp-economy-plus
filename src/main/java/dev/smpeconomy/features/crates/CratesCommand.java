package dev.smpeconomy.features.crates;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerPlayer;
import dev.smpeconomy.core.Args;
import dev.smpeconomy.core.CommandRegistry;
import dev.smpeconomy.core.CrateKeys;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Perms;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.features.crates.CratesConfig.CrateDef;
import dev.smpeconomy.features.crates.CratesConfig.RewardDef;

final class CratesCommand {
	static final String NODE = "smpeconomy.crates";
	static final String ADMIN_NODE = "smpeconomy.admin.crates";
	static final int MAX_GIVE = 100_000;

	private static final SuggestionProvider<CommandSourceStack> CRATES =
		(ctx, builder) -> SharedSuggestionProvider.suggest(CratesConfig.crateNames(), builder);
	private static final SuggestionProvider<CommandSourceStack> REWARDS = (ctx, builder) -> {
		List<String> ids = new ArrayList<>();
		try {
			CratesConfig.crate(StringArgumentType.getString(ctx, "crate")).ifPresent(c -> c.rewards.forEach(r -> ids.add(r.id)));
		} catch (IllegalArgumentException ignored) {
			// Argument not present yet
		}
		return SharedSuggestionProvider.suggest(ids, builder);
	};

	private CratesCommand() {}

	static void register() {
		CommandRegistry.register((dispatcher, registryAccess, environment) -> {
			LiteralCommandNode<CommandSourceStack> root = dispatcher.register(Commands.literal("crates")
				.requires(Perms.require(NODE))
				.executes(CratesCommand::menu)
				.then(Commands.literal("claim").executes(CratesCommand::claim))
				.then(Commands.literal("preview")
					.then(Commands.argument("crate", StringArgumentType.word()).suggests(CRATES).executes(CratesCommand::preview)))
				.then(Commands.literal("give").requires(Perms.requireAdmin(ADMIN_NODE))
					.then(Commands.argument("player", StringArgumentType.word()).suggests(Args.knownPlayers())
						.then(Commands.argument("crate", StringArgumentType.word()).suggests(CRATES)
							.executes(ctx -> give(ctx, 1))
							.then(Commands.argument("amount", IntegerArgumentType.integer(1, MAX_GIVE))
								.executes(ctx -> give(ctx, IntegerArgumentType.getInteger(ctx, "amount")))))))
				.then(Commands.literal("set").requires(Perms.requireAdmin(ADMIN_NODE))
					.then(Commands.argument("player", StringArgumentType.word()).suggests(Args.knownPlayers())
						.then(Commands.argument("crate", StringArgumentType.word()).suggests(CRATES)
							.then(Commands.argument("amount", IntegerArgumentType.integer(0, MAX_GIVE)).executes(CratesCommand::set)))))
				.then(Commands.literal("info").requires(Perms.requireAdmin(ADMIN_NODE))
					.then(Commands.argument("player", StringArgumentType.word()).suggests(Args.knownPlayers()).executes(CratesCommand::info)))
				.then(Commands.literal("force").requires(Perms.requireAdmin(ADMIN_NODE))
					.then(Commands.argument("player", StringArgumentType.word()).suggests(Args.knownPlayers())
						.then(Commands.argument("crate", StringArgumentType.word()).suggests(CRATES)
							.then(Commands.argument("reward", StringArgumentType.word()).suggests(REWARDS).executes(CratesCommand::force)))))
				.then(Commands.literal("values").requires(Perms.requireAdmin(ADMIN_NODE)).executes(CratesCommand::values)));
		});
	}

	private static ServerPlayer playerOrError(CommandContext<CommandSourceStack> ctx) {
		ServerPlayer p = ctx.getSource().getPlayer();
		if (p == null) Msg.error(ctx.getSource(), "Players only.");
		return p;
	}

	private static int menu(CommandContext<CommandSourceStack> ctx) {
		ServerPlayer p = playerOrError(ctx);
		if (p == null) return 0;
		CratesGui.openMain(p);
		return 1;
	}

	private static int claim(CommandContext<CommandSourceStack> ctx) {
		ServerPlayer p = playerOrError(ctx);
		if (p == null) return 0;
		CratesGui.claim(p);
		return 1;
	}

	private static int preview(CommandContext<CommandSourceStack> ctx) {
		ServerPlayer p = playerOrError(ctx);
		if (p == null) return 0;
		Optional<CrateDef> crate = crateArg(ctx);
		if (crate.isEmpty()) return 0;
		CratesGui.openPreview(p, crate.get().id, 0);
		return 1;
	}

	private static Optional<CrateDef> crateArg(CommandContext<CommandSourceStack> ctx) {
		String raw = StringArgumentType.getString(ctx, "crate");
		Optional<CrateDef> crate = CratesConfig.crate(raw);
		if (crate.isEmpty()) Msg.error(ctx.getSource(), "Unknown crate '" + raw + "'. Crates: " + String.join(", ", CratesConfig.crateNames()));
		return crate;
	}

	private static int give(CommandContext<CommandSourceStack> ctx, int amount) throws CommandSyntaxException {
		UUID target = Args.knownPlayer(ctx, "player");
		Optional<CrateDef> crate = crateArg(ctx);
		if (crate.isEmpty()) return 0;
		long now;
		try {
			now = CrateKeys.give(target, crate.get().id, amount);
		} catch (ArithmeticException e) {
			Msg.error(ctx.getSource(), "Too many keys.");
			return 0;
		}
		String name = EconomyService.nameOrUnknown(target);
		Msg.success(ctx.getSource(), "Gave " + name + " " + amount + "× " + crate.get().name + " Key§a (now " + now + ").");
		Msg.sendOrQueue(target, "§aYou got §e" + amount + "× " + crate.get().name + " Key§a! §7(/crates)");
		return 1;
	}

	private static int set(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		UUID target = Args.knownPlayer(ctx, "player");
		Optional<CrateDef> crate = crateArg(ctx);
		if (crate.isEmpty()) return 0;
		int amount = IntegerArgumentType.getInteger(ctx, "amount");
		PlayerDataService.setCounter(target, CrateKeys.key(crate.get().id), amount);
		Msg.success(ctx.getSource(), EconomyService.nameOrUnknown(target) + ": " + crate.get().name + " Keys§a set to " + amount + ".");
		return 1;
	}

	/**
	 * Machine-readable: {@code <name>: common=1 rare=0 epic=0 pending=0 money=12345}. restart.mjs,
	 * crash-fuzz.mjs and crates.mjs parse exactly this format.
	 */
	private static int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		UUID target = Args.knownPlayer(ctx, "player");
		StringBuilder sb = new StringBuilder("§7").append(EconomyService.nameOrUnknown(target)).append(":");
		for (String id : CratesConfig.crateIds()) sb.append(' ').append(id).append('=').append(CrateKeys.count(target, id));
		sb.append(" pending=").append(CrateService.pendingCount(target));
		sb.append(" money=").append(EconomyService.balance(target));
		Msg.send(ctx.getSource(), Msg.legacy(sb.toString()));
		return 1;
	}

	private static int force(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		UUID target = Args.knownPlayer(ctx, "player");
		Optional<CrateDef> crate = crateArg(ctx);
		if (crate.isEmpty()) return 0;
		String rewardId = StringArgumentType.getString(ctx, "reward").toLowerCase(Locale.ROOT);
		if (crate.get().reward(rewardId).isEmpty()) {
			Msg.error(ctx.getSource(), "Unknown reward '" + rewardId + "' in the " + crate.get().name + "§c.");
			return 0;
		}
		CrateService.force(target, crate.get().id, rewardId);
		Msg.success(ctx.getSource(), "Next reward for " + EconomyService.nameOrUnknown(target) + " from the " + crate.get().name + "§a: " + rewardId);
		return 1;
	}

	/** Money plus market value of pristine items; keys and enchanted items are counted separately. */
	private static int values(CommandContext<CommandSourceStack> ctx) {
		HolderLookup.Provider reg = ctx.getSource().getServer().registryAccess();
		for (CrateDef c : CratesConfig.get().crates) {
			Expected e = expected(c, reg);
			Msg.send(ctx.getSource(), Msg.legacy(c.name + "§7: avg " + Msg.moneyStr(e.cents()) + " per opening"
				+ String.format(Locale.ROOT, " §8(unvalued: %.1f%%, keys: %.3f/opening)", e.unvaluedPercent(), e.keysPerOpen())));
		}
		return 1;
	}

	record Expected(long cents, double unvaluedPercent, double keysPerOpen) {}

	static Expected expected(CrateDef c, HolderLookup.Provider reg) {
		long total = c.totalWeight();
		double cents = 0;
		double unvalued = 0;
		double keys = 0;
		for (RewardDef r : c.rewards) {
			if (r.weight <= 0) continue;
			double p = (double) r.weight / total;
			if (r.kind() == CratesConfig.Kind.KEY) {
				keys += p * r.effectiveCount();
				continue;
			}
			OptionalLong v = CratesGui.valueOf(r, reg);
			if (v.isPresent()) cents += p * v.getAsLong();
			else unvalued += p;
		}
		return new Expected((long) Math.min(Money.MAX, Math.floor(cents)), unvalued * 100, keys);
	}
}
