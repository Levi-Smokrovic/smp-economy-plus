package dev.smpeconomy.features.missions;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import dev.smpeconomy.core.Args;
import dev.smpeconomy.core.CommandRegistry;
import dev.smpeconomy.core.CrateKeys;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Perms;
import dev.smpeconomy.features.missions.MissionStore.ActiveMission;
import dev.smpeconomy.features.missions.MissionStore.PlayerState;

/**
 * {@code /missions [active]} (node smpeconomy.missions) and admin subcommands {@code /smpeco missions
 * status|board|assign|expire|reset|cooldown|check|elite [status|set|clear|raids|reset|preview]} (smpeconomy.admin,
 * machine-readable output for e2e).
 */
final class MissionCommands {
	static final String NODE = "smpeconomy.missions";
	static final String NODE_ADMIN = "smpeconomy.admin.missions";

	private MissionCommands() {}

	static void register() {
		CommandRegistry.register((dispatcher, registryAccess, environment) -> {
			LiteralCommandNode<CommandSourceStack> main = dispatcher.register(Commands.literal("missions")
				.requires(Perms.require(NODE))
				.executes(ctx -> openBoard(ctx))
				.then(Commands.literal("active").executes(MissionCommands::openActive)));

			dispatcher.register(Commands.literal("smpeco")
				.requires(Perms.requireAdmin(Perms.ADMIN))
				.then(Commands.literal("missions")
					.requires(Perms.requireAdmin(NODE_ADMIN))
					.then(Commands.literal("check").executes(MissionCommands::check))
					.then(Commands.literal("status").then(player().executes(MissionCommands::status)))
					.then(Commands.literal("board").then(player().executes(MissionCommands::board)))
					.then(Commands.literal("assign").then(player()
						.then(Commands.argument("mission", StringArgumentType.word())
							.suggests((ctx, b) -> SharedSuggestionProvider.suggest(MissionConfig.get().byId().keySet(), b))
							.executes(ctx -> assign(ctx, 0))
							.then(Commands.argument("amount", IntegerArgumentType.integer(1, MissionConfig.MAX_AMOUNT))
								.executes(ctx -> assign(ctx, IntegerArgumentType.getInteger(ctx, "amount")))))))
					.then(Commands.literal("expire").then(player().executes(MissionCommands::expire)))
					.then(Commands.literal("reset").then(player().executes(MissionCommands::reset)))
					.then(Commands.literal("cooldown").then(player().then(Commands.literal("clear").executes(MissionCommands::clearCooldown))))
					.then(Commands.literal("elite")
						.executes(MissionCommands::eliteInfo)
						.then(Commands.literal("status").then(player().executes(MissionCommands::eliteStatus)))
						.then(Commands.literal("set").then(Commands.argument("id", StringArgumentType.word())
							.suggests((ctx, b) -> SharedSuggestionProvider.suggest(EliteConfig.get().byId().keySet(), b))
							.executes(MissionCommands::eliteSet)))
						.then(Commands.literal("clear").executes(MissionCommands::eliteClear))
						.then(Commands.literal("raids").executes(MissionCommands::eliteRaids))
						.then(Commands.literal("reset").then(player().executes(MissionCommands::eliteReset)))
						.then(Commands.literal("preview").executes(ctx -> elitePreview(ctx, 14))
							.then(Commands.argument("days", IntegerArgumentType.integer(1, 120))
								.executes(ctx -> elitePreview(ctx, IntegerArgumentType.getInteger(ctx, "days"))))))));
		});
	}

	private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> player() {
		return Commands.argument("player", StringArgumentType.word()).suggests(Args.knownPlayers());
	}

	private static int openBoard(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		MissionGui.openBoard(ctx.getSource().getPlayerOrException());
		return 1;
	}

	private static int openActive(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		MissionGui.openActive(ctx.getSource().getPlayerOrException());
		return 1;
	}

	/** RCON joins messages without line breaks, so this sends a single multi-line message. */
	private static void reply(CommandContext<CommandSourceStack> ctx, String text) {
		ctx.getSource().sendSuccess(() -> Component.literal(text), false);
	}

	private static int check(CommandContext<CommandSourceStack> ctx) {
		StringBuilder sb = new StringBuilder();
		MissionConfig.Compiled c = MissionConfig.get();
		sb.append("missions check pool=").append(c.pool().size());
		for (Tier t : Tier.values()) sb.append(' ').append(t.id).append('=').append(MissionConfig.candidates(t).size());
		sb.append(" dailyelite=").append(EliteConfig.candidates().size());
		List<String> problems = new java.util.ArrayList<>(MissionConfig.problems());
		problems.addAll(EliteConfig.problems());
		sb.append(" problems=").append(problems.size());
		for (String p : problems) sb.append('\n').append("problem ").append(p);
		reply(ctx, sb.toString());
		return problems.isEmpty() ? 1 : 0;
	}

	private static int status(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		UUID id = Args.knownPlayer(ctx, "player");
		long now = System.currentTimeMillis();
		ServerPlayer online = ctx.getSource().getServer().getPlayerList().getPlayer(id);
		if (online != null) MissionService.update(online, now);
		PlayerState s = MissionStore.peek(id);
		StringBuilder sb = new StringBuilder();
		sb.append("missions status player=").append(EconomyService.nameOrUnknown(id))
			.append(" period=").append(MissionService.period(now))
			.append(" active=").append(s == null ? 0 : s.active.size())
			.append(" max=").append(MissionConfig.maxActive())
			.append(" cooldownMs=").append(s == null ? 0 : Math.max(0, s.cooldownUntil - now))
			.append(" completed=").append(s == null ? 0 : s.completed)
			.append(" earned=").append(s == null ? 0 : s.earnedCents)
			.append(" balance=").append(EconomyService.balance(id))
			.append(" keys=").append(CrateKeys.count(id, CrateKeys.COMMON)).append('/').append(CrateKeys.count(id, CrateKeys.RARE))
			.append('/').append(CrateKeys.count(id, CrateKeys.EPIC))
			.append(" explored=").append(s == null ? 0 : s.explored.size())
			.append(" taken=").append(s == null || s.period != MissionService.period(now) ? "" : String.join(",", s.taken));
		if (s != null) {
			for (ActiveMission a : s.active) {
				Mission m = a.mission();
				sb.append('\n').append("missions active instance=").append(a.instance)
					.append(" id=").append(a.missionId)
					.append(" type=").append(m == null ? "?" : m.type.id)
					.append(" tier=").append(m == null ? "?" : m.tier.id)
					.append(" progress=").append(a.progress).append('/').append(a.amount())
					.append(" paid=").append(a.paid)
					.append(" reward=").append(a.reward)
					.append(" keys=").append(a.keys).append(a.keys > 0 ? ":" + a.keyTier : "")
					.append(" item=").append(m != null && m.item != null ? Items.id(m.item) : "-")
					.append(" perItem=").append(m != null && m.type == Mission.Type.DELIVER ? MissionService.deliveryPrice(id, m, a.def) : 0)
					.append(" armed=").append(a.armed)
					.append(" killMilli=").append(a.killMilli)
					.append(" expiresInMs=").append(a.expiresAt - now);
			}
		}
		reply(ctx, sb.toString());
		return 1;
	}

	private static int board(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		UUID id = Args.knownPlayer(ctx, "player");
		long now = System.currentTimeMillis();
		long period = MissionService.period(now);
		StringBuilder sb = new StringBuilder("missions board period=" + period + " endsInMs=" + (MissionService.periodEnd(period) - now));
		for (Map.Entry<Tier, List<Mission>> e : MissionService.board(id, period).entrySet()) {
			List<Mission> offers = e.getValue();
			for (int i = 0; i < offers.size(); i++) {
				Mission m = offers.get(i);
				MissionService.AcceptResult r = MissionService.canAccept(id, m.id, period, now);
				sb.append('\n').append("offer slot=").append(MissionGui.offerSlot(e.getKey(), i))
					.append(" tier=").append(e.getKey().id)
					.append(" id=").append(m.id)
					.append(" type=").append(m.type.id)
					.append(" amount=").append(m.def.amount)
					.append(" item=").append(m.item != null ? Items.id(m.item) : "-")
					.append(" status=").append(r.name().toLowerCase(java.util.Locale.ROOT))
					.append(" name=").append(m.def.name);
			}
		}
		reply(ctx, sb.toString());
		return 1;
	}

	private static int assign(CommandContext<CommandSourceStack> ctx, int amount) throws CommandSyntaxException {
		UUID id = Args.knownPlayer(ctx, "player");
		ServerPlayer player = ctx.getSource().getServer().getPlayerList().getPlayer(id);
		if (player == null) {
			Msg.error(ctx.getSource(), "The player must be online.");
			return 0;
		}
		Mission m = MissionConfig.byId(StringArgumentType.getString(ctx, "mission"));
		if (m == null) {
			Msg.error(ctx.getSource(), "Unknown mission.");
			return 0;
		}
		MissionService.AcceptResult r = MissionService.assign(player, m, amount, System.currentTimeMillis());
		if (r != MissionService.AcceptResult.OK) {
			reply(ctx, "missions assign failed=" + r.name().toLowerCase(java.util.Locale.ROOT));
			return 0;
		}
		PlayerState s = MissionStore.peek(id);
		ActiveMission a = s.active.get(s.active.size() - 1);
		reply(ctx, "missions assign ok instance=" + a.instance + " id=" + m.id + " amount=" + a.amount());
		Msg.info(player, "§eAn admin assigned you the mission §f" + m.def.name + "§e.");
		return 1;
	}

	private static int expire(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		UUID id = Args.knownPlayer(ctx, "player");
		PlayerState s = MissionStore.peek(id);
		long now = System.currentTimeMillis();
		int n = 0;
		if (s != null) {
			for (ActiveMission a : s.active) {
				a.expiresAt = Math.min(a.expiresAt, now - 1);
				n++;
			}
			MissionStore.markDirty();
		}
		reply(ctx, "missions expire marked=" + n);
		return n;
	}

	private static int reset(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		UUID id = Args.knownPlayer(ctx, "player");
		MissionStore.remove(id);
		reply(ctx, "missions reset ok");
		return 1;
	}

	private static int clearCooldown(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		UUID id = Args.knownPlayer(ctx, "player");
		PlayerState s = MissionStore.peek(id);
		if (s != null) {
			s.cooldownUntil = 0;
			MissionStore.markDirty();
		}
		reply(ctx, "missions cooldown cleared");
		return 1;
	}

	private static int eliteInfo(CommandContext<CommandSourceStack> ctx) {
		long now = System.currentTimeMillis();
		EliteService.Today t = EliteService.today(now);
		StringBuilder sb = new StringBuilder("missions elite enabled=").append(EliteConfig.enabled())
			.append(" pool=").append(EliteConfig.candidates().size())
			.append(" day=").append(EliteService.dayLabel(EliteService.epochDay(now)))
			.append(" endsInMs=").append(EliteService.millisUntilReset(now));
		if (t != null) {
			EliteConfig.Elite el = t.elite();
			sb.append(" id=").append(el.id())
				.append(" type=").append(el.type().id)
				.append(" amount=").append(el.amount())
				.append(" reward=").append(el.def().rewardCents)
				.append(" keys=").append(EliteService.keys(el)).append(':').append(EliteConfig.keyTier(el.def()))
				.append(" item=").append(el.item() != null ? Items.id(el.item()) : "-")
				.append(" override=").append(t.override())
				.append(" name=").append(el.def().name);
		} else {
			sb.append(" id=-");
		}
		reply(ctx, sb.toString());
		return t == null ? 0 : 1;
	}

	private static int eliteStatus(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		UUID id = Args.knownPlayer(ctx, "player");
		long now = System.currentTimeMillis();
		EliteService.Today t = EliteService.today(now);
		PlayerState s = MissionStore.peek(id);
		MissionStore.EliteProgress e = t == null ? null : EliteService.peekProgress(id, t);
		StringBuilder sb = new StringBuilder("missions elite status player=").append(EconomyService.nameOrUnknown(id))
			.append(" day=").append(EliteService.dayLabel(EliteService.epochDay(now)))
			.append(" id=").append(t == null ? "-" : t.id())
			.append(" progress=").append(t == null ? 0 : EliteService.progressValue(id, t)).append('/').append(t == null ? 0 : t.elite().amount())
			.append(" killMilli=").append(e == null || t == null || !t.id().equals(e.missionId) ? 0 : e.killMilli)
			.append(" done=").append(t != null && EliteService.doneToday(id, t))
			.append(" paid=").append(e == null ? 0 : e.paid)
			.append(" payout=").append(t == null ? 0 : EliteService.payout(id, t.elite()))
			.append(" completed=").append(s == null ? 0 : s.eliteCompleted)
			.append(" earned=").append(s == null ? 0 : s.eliteEarned)
			.append(" announced=").append(s != null && t != null && s.elite.announcedDay == t.day())
			.append(" balance=").append(EconomyService.balance(id))
			.append(" keys=").append(CrateKeys.count(id, CrateKeys.COMMON)).append('/').append(CrateKeys.count(id, CrateKeys.RARE))
			.append('/').append(CrateKeys.count(id, CrateKeys.EPIC));
		reply(ctx, sb.toString());
		return 1;
	}

	/** Until midnight. Players already done today stay done. */
	private static int eliteSet(CommandContext<CommandSourceStack> ctx) {
		EliteConfig.Elite el = EliteConfig.byId(StringArgumentType.getString(ctx, "id"));
		if (el == null) {
			reply(ctx, "missions elite set failed=unknown");
			return 0;
		}
		EliteService.Today t = EliteService.override(el, System.currentTimeMillis());
		reply(ctx, "missions elite set ok id=" + (t == null ? "-" : t.id()));
		return t == null ? 0 : 1;
	}

	private static int eliteClear(CommandContext<CommandSourceStack> ctx) {
		EliteService.Today t = EliteService.clearOverride(System.currentTimeMillis());
		reply(ctx, "missions elite clear ok id=" + (t == null ? "-" : t.id()));
		return 1;
	}

	/** Test helper: deletes a player's progress and completion for today, so it can be finished again. */
	private static int eliteReset(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		UUID id = Args.knownPlayer(ctx, "player");
		PlayerState s = MissionStore.peek(id);
		if (s != null) {
			long announced = s.elite.announcedDay;
			s.elite = new MissionStore.EliteProgress();
			s.elite.announcedDay = announced;
			MissionStore.markDirty();
		}
		reply(ctx, "missions elite reset ok");
		return 1;
	}

	private static int eliteRaids(CommandContext<CommandSourceStack> ctx) {
		Map<String, MissionStore.RaidTrack> raids = new java.util.TreeMap<>(MissionStore.raids());
		StringBuilder sb = new StringBuilder("missions elite raids tracked=").append(raids.size());
		for (Map.Entry<String, MissionStore.RaidTrack> e : raids.entrySet()) {
			MissionStore.RaidTrack t = e.getValue();
			sb.append('\n').append("raid key=").append(e.getKey()).append(" start=").append(t.startLevel)
				.append(" total=").append(t.totalKills).append(" finished=").append(t.finishedAt != 0);
			net.minecraft.world.entity.raid.Raid raid = EliteRaids.vanilla(ctx.getSource().getServer(), e.getKey());
			if (raid != null) {
				String status = raid.isVictory() ? "victory" : raid.isLoss() ? "loss" : raid.isStopped() ? "stopped" : "ongoing";
				sb.append(" level=").append(raid.getRaidOmenLevel()).append(" groups=").append(raid.getGroupsSpawned())
					.append(" alive=").append(raid.getTotalRaidersAlive()).append(" status=").append(status)
					.append(" center=").append(raid.getCenter().getX()).append(',').append(raid.getCenter().getY()).append(',').append(raid.getCenter().getZ());
			} else {
				sb.append(" level=- status=gone");
			}
			StringBuilder kills = new StringBuilder();
			for (Map.Entry<UUID, Integer> k : t.kills.entrySet()) {
				if (!kills.isEmpty()) kills.append(',');
				kills.append(EconomyService.nameOrUnknown(k.getKey())).append(':').append(k.getValue());
			}
			sb.append(" kills=").append(kills.isEmpty() ? "-" : kills);
		}
		reply(ctx, sb.toString());
		return raids.size();
	}

	/** Without override, e.g. for planning. */
	private static int elitePreview(CommandContext<CommandSourceStack> ctx, int days) {
		long today = EliteService.epochDay(System.currentTimeMillis());
		StringBuilder sb = new StringBuilder("missions elite preview");
		for (int i = 0; i < days; i++) {
			EliteConfig.Elite el = EliteConfig.pick(today + i);
			sb.append('\n').append(EliteService.dayLabel(today + i)).append(' ').append(el == null ? "-" : el.id());
		}
		reply(ctx, sb.toString());
		return 1;
	}
}
