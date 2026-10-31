package dev.smpeconomy.features.stats;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import dev.smpeconomy.core.Args;
import dev.smpeconomy.core.CommandRegistry;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Perms;
import dev.smpeconomy.core.Services;
import dev.smpeconomy.core.TimeUtil;

final class StatsCommands {
	static final String NODE_STATS = "smpeconomy.stats";
	static final String NODE_OTHERS = "smpeconomy.stats.others";
	static final String NODE_PLAYTIME = "smpeconomy.playtime";
	static final String NODE_TOP = "smpeconomy.leaderboard";
	/** Required in addition to Perms.ADMIN. */
	static final String NODE_ADMIN = "smpeconomy.admin.stats";

	private StatsCommands() {}

	static void register() {
		CommandRegistry.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(stats("stats"));
			dispatcher.register(playtime("playtime"));
			for (String name : List.of(Leaderboard.COMMAND)) {
				dispatcher.register(Commands.literal(name)
					.requires(Perms.require(NODE_TOP))
					.executes(ctx -> {
						for (MutableComponent line : Leaderboard.usage()) Msg.send(ctx.getSource(), line);
						return 1;
					})
					.then(Commands.argument("category", StringArgumentType.word())
						.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(Leaderboard.Category.ids(), builder))
						.executes(ctx -> top(ctx, 1))
						.then(Commands.argument("page", IntegerArgumentType.integer(1))
							.executes(ctx -> top(ctx, IntegerArgumentType.getInteger(ctx, "page"))))));
			}
			dispatcher.register(Commands.literal("smpeco")
				.requires(Perms.requireAdmin(Perms.ADMIN))
				.then(Commands.literal("stats")
					.requires(Perms.requireAdmin(NODE_ADMIN))
					.then(Commands.argument("player", StringArgumentType.word()).suggests(Args.knownPlayers())
						.executes(StatsCommands::adminInfo))));
		});
	}

	private static LiteralArgumentBuilder<CommandSourceStack> stats(String name) {
		return Commands.literal(name)
			.requires(Perms.require(NODE_STATS))
			.executes(ctx -> {
				ServerPlayer self = ctx.getSource().getPlayer();
				if (self == null) {
					Msg.error(ctx.getSource(), "In-game only – use /stats <player>.");
					return 0;
				}
				return show(ctx.getSource(), self.getUUID());
			})
			.then(Commands.argument("player", StringArgumentType.word()).suggests(Args.knownPlayers())
				.executes(ctx -> {
					UUID target = Args.knownPlayer(ctx, "player");
					if (!mayViewOthers(ctx.getSource(), target)) return 0;
					return show(ctx.getSource(), target);
				}));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> playtime(String name) {
		return Commands.literal(name)
			.requires(Perms.require(NODE_PLAYTIME))
			.executes(ctx -> {
				ServerPlayer self = ctx.getSource().getPlayer();
				if (self == null) {
					Msg.error(ctx.getSource(), "In-game only – use /playtime <player>.");
					return 0;
				}
				return playtime(ctx.getSource(), self.getUUID());
			})
			.then(Commands.argument("player", StringArgumentType.word()).suggests(Args.knownPlayers())
				.executes(ctx -> {
					UUID target = Args.knownPlayer(ctx, "player");
					if (!mayViewOthers(ctx.getSource(), target)) return 0;
					return playtime(ctx.getSource(), target);
				}));
	}

	private static boolean mayViewOthers(CommandSourceStack src, UUID target) {
		ServerPlayer self = src.getPlayer();
		if (self == null || self.getUUID().equals(target) || Perms.check(src, NODE_OTHERS)) return true;
		Msg.error(src, "You can only view your own stats.");
		return false;
	}

	/** Players get the GUI, console and RCON get chat lines. */
	private static int show(CommandSourceStack src, UUID target) {
		ServerPlayer self = src.getPlayer();
		if (self != null) {
			if (!Gui.canOpen(self)) return 0;
			StatsGui.open(self, target);
			return 1;
		}
		StatsService.View v = StatsService.view(target);
		Msg.send(src, Msg.prefixed("§6§lStats §8» §f" + v.name() + (v.online() ? " §a(online)" : " §7(offline)")));
		Msg.send(src, Msg.legacy(" §7Player Kills: §f" + Msg.number(v.kills()) + " §8| §7Deaths: §f" + Msg.number(v.deaths())
			+ " §8| §7K/D: §f" + StatsService.kd(v.kills(), v.deaths())));
		Msg.send(src, Msg.legacy(" §7Mob Kills: §f" + Msg.number(v.mobKills()) + " §8| §7Kill Streak: §f" + Msg.number(v.streak())
			+ " §8(best " + Msg.number(v.bestStreak()) + ")"));
		Msg.send(src, Msg.legacy(" §7Playtime: §f" + TimeUtil.formatTicks(v.playtimeTicks()) + " §8| §7Balance: §a"
			+ Money.format(EconomyService.balance(target))));
		return 1;
	}

	private static int playtime(CommandSourceStack src, UUID target) {
		ServerPlayer self = src.getPlayer();
		boolean own = self != null && self.getUUID().equals(target);
		long ticks = StatsService.playtimeTicks(target);
		int rank = Leaderboard.rankOf(Leaderboard.collect(Leaderboard.Category.PLAYTIME), target);
		String rankStr = rank > 0 ? " §8(Rank §e#" + rank + "§8)" : "";
		String name = StatsService.name(target);
		Msg.info(src, (own ? "Your playtime: §e" : "Playtime of §f" + name + "§7: §e") + TimeUtil.formatTicks(ticks) + rankStr);
		long session = StatsService.sessionMs(target);
		if (StatsService.online(target) != null) {
			if (session > 0) Msg.info(src, (own ? "This session: §e" : "§aOnline §7for §e") + TimeUtil.formatDuration(session));
		} else {
			long lastSeen = StatsStore.peek(target).map(r -> r.lastSeen).orElse(0L);
			if (lastSeen > 0) Msg.info(src, "Last online: §e" + TimeUtil.formatDuration(System.currentTimeMillis() - lastSeen) + " §7ago");
		}
		return 1;
	}

	private static int top(CommandContext<CommandSourceStack> ctx, int page) {
		CommandSourceStack src = ctx.getSource();
		Optional<Leaderboard.Category> c = Leaderboard.Category.parse(StringArgumentType.getString(ctx, "category"));
		if (c.isEmpty()) {
			Msg.error(src, "Unknown category. Options: " + String.join(", ", Leaderboard.Category.ids()) + ".");
			return 0;
		}
		ServerPlayer self = src.getPlayer();
		Leaderboard.send(line -> Msg.send(src, line), c.get(), page, self == null ? null : self.getUUID());
		return 1;
	}

	/** Machine-readable, for e2e scripts and admins: values through the registered service and the raw data. */
	private static int adminInfo(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		UUID id = Args.knownPlayer(ctx, "player");
		StatsService.View v = StatsService.view(id);
		Optional<Services.PlayerStats> svc = Services.find(Services.PlayerStats.class);
		Services.PlayerStats s = svc.orElse(StatsService.INSTANCE);
		String line = "§7stats name=" + v.name() + " uuid=" + id + " online=" + v.online() + " service=" + (svc.isPresent() ? "yes" : "no")
			+ " kills=" + s.playerKills(id) + " pvpKills=" + v.pvpKills() + " baseline=" + v.baseline() + " pvpDeaths=" + v.pvpDeaths()
			+ " deaths=" + s.deaths(id) + " mobKills=" + s.mobKills(id) + " streak=" + v.streak() + " bestStreak=" + v.bestStreak()
			+ " playtimeTicks=" + v.playtimeTicks() + " blocksMined=" + v.blocksMined() + " walkedCm=" + v.walkedCm()
			+ " cachedDeaths=" + StatsStore.peek(id).map(r -> r.deaths).orElse(-1L)
			+ " cachedMobKills=" + StatsStore.peek(id).map(r -> r.mobKills).orElse(-1L)
			+ " lastSeen=" + v.lastSeen();
		Msg.send(ctx.getSource(), Msg.legacy(line));
		return 1;
	}
}
