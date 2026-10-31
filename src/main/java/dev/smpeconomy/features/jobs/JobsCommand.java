package dev.smpeconomy.features.jobs;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import dev.smpeconomy.core.Args;
import dev.smpeconomy.core.CommandRegistry;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Perms;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.TimeUtil;
import dev.smpeconomy.features.jobs.JobsConfig.Example;
import dev.smpeconomy.features.jobs.JobsConfig.Job;

public final class JobsCommand {
	public static final String NODE = "smpeconomy.jobs";
	public static final String ADMIN_NODE = "smpeconomy.admin.jobs";

	private JobsCommand() {}

	public static void register() {
		CommandRegistry.register((dispatcher, registryAccess, environment) -> {
			LiteralCommandNode<CommandSourceStack> root = dispatcher.register(Commands.literal("jobs")
				.requires(Perms.require(NODE))
				.executes(JobsCommand::openGui)
				.then(Commands.literal("join")
					.then(Commands.argument("job", StringArgumentType.word()).suggests(jobSuggestions()).executes(JobsCommand::join)))
				.then(Commands.literal("leave").executes(JobsCommand::leave))
				.then(Commands.literal("info")
					.executes(ctx -> info(ctx, null))
					.then(Commands.argument("job", StringArgumentType.word()).suggests(jobSuggestions())
						.executes(ctx -> info(ctx, StringArgumentType.getString(ctx, "job")))))
				.then(Commands.literal("admin")
					.requires(Perms.requireAdmin(ADMIN_NODE))
					.then(Commands.literal("set")
						.then(Commands.argument("player", StringArgumentType.word()).suggests(Args.knownPlayers())
							.then(Commands.argument("job", StringArgumentType.word()).suggests(jobSuggestionsWithNone())
								.executes(JobsCommand::adminSet))))
					.then(Commands.literal("level")
						.then(Commands.argument("player", StringArgumentType.word()).suggests(Args.knownPlayers())
							.then(Commands.argument("job", StringArgumentType.word()).suggests(jobSuggestions())
								.then(Commands.argument("level", IntegerArgumentType.integer(1, 1000))
									.executes(ctx -> adminLevel(ctx, 0))
									.then(Commands.argument("xp", IntegerArgumentType.integer(0))
										.executes(ctx -> adminLevel(ctx, IntegerArgumentType.getInteger(ctx, "xp"))))))))
					.then(Commands.literal("cooldown")
						.then(Commands.argument("player", StringArgumentType.word()).suggests(Args.knownPlayers())
							.executes(JobsCommand::adminCooldown)))
					.then(Commands.literal("info")
						.then(Commands.argument("player", StringArgumentType.word()).suggests(Args.knownPlayers())
							.executes(JobsCommand::adminInfo)))
					.then(Commands.literal("flush").executes(JobsCommand::adminFlush))));
			dispatcher.register(Commands.literal("job").requires(Perms.require(NODE)).executes(JobsCommand::openGui).redirect(root));
		});
	}

	private static SuggestionProvider<CommandSourceStack> jobSuggestions() {
		return (ctx, builder) -> {
			List<String> names = new ArrayList<>();
			for (Job j : JobsConfig.jobs()) names.add(JobsConfig.normalize(j.name));
			return SharedSuggestionProvider.suggest(names, builder);
		};
	}

	private static SuggestionProvider<CommandSourceStack> jobSuggestionsWithNone() {
		return (ctx, builder) -> {
			List<String> names = new ArrayList<>();
			names.add("none");
			for (Job j : JobsConfig.jobs()) names.add(j.id);
			return SharedSuggestionProvider.suggest(names, builder);
		};
	}

	private static Job jobArg(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		Job job = JobsConfig.find(StringArgumentType.getString(ctx, "job"));
		if (job == null) throw unknownJob();
		return job;
	}

	private static CommandSyntaxException unknownJob() {
		List<String> names = new ArrayList<>();
		for (Job j : JobsConfig.jobs()) names.add(JobsConfig.normalize(j.name));
		return new SimpleCommandExceptionType(Msg.prefixed("§cUnknown job. Available: §e" + String.join("§7, §e", names))).create();
	}

	private static int openGui(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		JobsGui.open(ctx.getSource().getPlayerOrException());
		return 1;
	}

	private static int join(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		return applyJoin(player, jobArg(ctx)) ? 1 : 0;
	}

	static boolean applyJoin(ServerPlayer player, Job job) {
		String previous = PlayerDataService.getJob(player.getUUID());
		JobsService.JoinResult r = JobsService.join(player.getUUID(), job.id, false);
		switch (r.status()) {
			case OK -> {
				Job old = JobsConfig.job(previous);
				Msg.success(player, "Your job is now " + job.display() + "§a!" + (old != null ? " §8(was " + old.name + ")" : ""));
				long cooldown = JobsConfig.raw().switchCooldownMinutes;
				if (cooldown > 0) Msg.info(player, "§7Next job switch in §e" + TimeUtil.formatDuration(cooldown * 60_000L) + "§7.");
				dev.smpeconomy.core.Gui.successSound(player);
				return true;
			}
			case ALREADY -> Msg.error(player, job.name + " is already your job.");
			case COOLDOWN -> Msg.error(player, "You can switch jobs in §e" + TimeUtil.formatDuration(r.remainingMs()) + "§c.");
			default -> Msg.error(player, "Unknown job.");
		}
		dev.smpeconomy.core.Gui.failSound(player);
		return false;
	}

	private static int leave(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		return applyLeave(ctx.getSource().getPlayerOrException()) ? 1 : 0;
	}

	static boolean applyLeave(ServerPlayer player) {
		Job job = JobsService.currentJob(player.getUUID());
		if (!JobsService.leave(player.getUUID())) {
			Msg.error(player, "You don't have a job.");
			return false;
		}
		long cooldown = JobsConfig.raw().switchCooldownMinutes;
		Msg.info(player, "You left your job" + (job != null ? " " + job.display() : "") + "§7. You keep your level."
			+ (cooldown > 0 ? " New job in §e" + TimeUtil.formatDuration(cooldown * 60_000L) + "§7." : ""));
		return true;
	}

	private static int info(CommandContext<CommandSourceStack> ctx, String jobInput) throws CommandSyntaxException {
		CommandSourceStack src = ctx.getSource();
		ServerPlayer player = src.getPlayer();
		if (jobInput != null) {
			Job job = JobsConfig.find(jobInput);
			if (job == null) throw unknownJob();
			double mult = player == null ? 1.0 : JobsService.multiplier(player.getUUID(), job);
			Msg.info(src, job.display() + " §8– §7Rewards" + (player != null ? " §8(your multiplier ×" + JobsText.trim(round2(mult)) + ")" : "") + ":");
			for (String d : job.description) Msg.send(src, Msg.legacy("  §7" + d));
			List<Example> rewards = job.allRewards();
			int shown = 0;
			for (Example e : rewards) {
				if (shown++ >= 12) {
					Msg.send(src, Msg.legacy("  §8… and " + (rewards.size() - 12) + " more"));
					break;
				}
				MutableComponent line = Msg.legacy("  ").append(JobsText.rewardLine(e, mult));
				Msg.send(src, line);
			}
			return 1;
		}
		if (player == null) {
			Msg.error(src, "Players only – console: /jobs admin info <player>");
			return 0;
		}
		UUID id = player.getUUID();
		Job job = JobsService.currentJob(id);
		if (job == null) {
			Msg.info(src, "You don't have a job. §e/jobs §7shows all jobs.");
		} else {
			Msg.info(src, "Your job: " + job.display());
			for (String line : JobsText.status(id, job)) Msg.send(src, Msg.legacy("  " + line));
		}
		Msg.send(src, Msg.legacy("  " + JobsText.cooldownLine(id)));
		long total = PlayerDataService.getCounter(id, JobsService.COUNTER_EARNED);
		if (total > 0) Msg.send(src, Msg.legacy("  §7Earned from jobs (total): " + Msg.moneyStr(total)));
		return 1;
	}

	private static double round2(double v) {
		return Math.round(v * 100.0) / 100.0;
	}

	private static int adminSet(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		UUID target = Args.knownPlayer(ctx, "player");
		String input = StringArgumentType.getString(ctx, "job");
		String name = EconomyService.nameOrUnknown(target);
		if ("none".equalsIgnoreCase(input)) {
			JobsService.adminSet(target, null);
			Msg.success(ctx.getSource(), name + " no longer has a job.");
			return 1;
		}
		Job job = jobArg(ctx);
		JobsService.adminSet(target, job.id);
		Msg.success(ctx.getSource(), name + "'s job is now " + job.display() + "§a.");
		return 1;
	}

	/** {@code xp} is XP within the level and stays below the next threshold. */
	private static int adminLevel(CommandContext<CommandSourceStack> ctx, long xp) throws CommandSyntaxException {
		UUID target = Args.knownPlayer(ctx, "player");
		Job job = jobArg(ctx);
		int level = Math.min(JobsService.maxLevel(), IntegerArgumentType.getInteger(ctx, "level"));
		long x = level >= JobsService.maxLevel() ? 0 : Math.min(xp, JobsService.xpForNextLevel(level) - 1);
		PlayerDataService.setJobLevel(target, job.id, level);
		PlayerDataService.setJobXp(target, job.id, x);
		Msg.success(ctx.getSource(), EconomyService.nameOrUnknown(target) + ": " + job.name + " Level " + level + " (" + x + " XP).");
		return 1;
	}

	private static int adminCooldown(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		UUID target = Args.knownPlayer(ctx, "player");
		JobsService.resetCooldown(target);
		Msg.success(ctx.getSource(), "Reset the job cooldown of " + EconomyService.nameOrUnknown(target) + ".");
		return 1;
	}

	/**
	 * Machine-readable for e2e: {@code jobs player=… job=… level=… xp=… cooldownMs=… balance=… pendingMilli=… earned=…
	 * mult=… actionbar=…}.
	 */
	private static int adminInfo(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		UUID target = Args.knownPlayer(ctx, "player");
		String job = PlayerDataService.getJob(target);
		String levelPart = job == null ? "level=0 xp=0" : "level=" + JobsService.level(target, job) + " xp=" + PlayerDataService.getJobXp(target, job);
		Msg.send(ctx.getSource(), Msg.legacy("§7jobs player=" + EconomyService.nameOrUnknown(target) + " job=" + (job == null ? "none" : job)
			+ " " + levelPart + " cooldownMs=" + JobsService.cooldownRemaining(target) + " balance=" + EconomyService.balance(target)
			+ " pendingMilli=" + JobsService.pendingMilli(target) + " earned=" + PlayerDataService.getCounter(target, JobsService.COUNTER_EARNED)
			+ " mult=" + String.format(java.util.Locale.ROOT, "%.6f", JobsConfig.job(job) == null ? 1.0 : JobsService.multiplier(target, JobsConfig.job(job)))
			+ " actionbar=" + dev.smpeconomy.core.Settings.get(target, dev.smpeconomy.core.Settings.JOB_ACTIONBAR)));
		return 1;
	}

	private static int adminFlush(CommandContext<CommandSourceStack> ctx) {
		JobsService.flushAll();
		Msg.success(ctx.getSource(), "Job earnings paid out.");
		return 1;
	}
}
