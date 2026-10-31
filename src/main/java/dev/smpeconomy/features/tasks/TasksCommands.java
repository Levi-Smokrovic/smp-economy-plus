package dev.smpeconomy.features.tasks;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerPlayer;
import dev.smpeconomy.core.Args;
import dev.smpeconomy.core.CommandRegistry;
import dev.smpeconomy.core.CrateKeys;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Perms;
import dev.smpeconomy.core.TimeUtil;
import dev.smpeconomy.features.tasks.TaskState.ActiveTask;
import dev.smpeconomy.features.tasks.TasksConfig.TaskDef;

/**
 * {@code /tasks} opens the GUI. Admin commands under {@code /smpeco tasks …} (smpeconomy.admin.tasks, OP 2;
 * machine-readable lines for RCON and e2e):
 * <ul>
 *   <li>{@code info <player>}: state, {@code tasks player=… date=… done=x/y bonus=… balance=… keys=…} plus one line per
 *   task</li>
 *   <li>{@code preview <player> [yyyy-mm-dd]}: deterministic selection for a date, changes nothing</li>
 *   <li>{@code set <player> <slot 1..n> <task> [amount]}: replaces a slot (fresh base, unpaid)</li>
 *   <li>{@code reset <player>}: deletes the day's state and reassigns</li>
 *   <li>{@code clock [minutes|reset]}: offset of the task clock, for testing day changes</li>
 * </ul>
 */
public final class TasksCommands {
	static final String NODE = "smpeconomy.tasks";
	static final String ADMIN_NODE = "smpeconomy.admin.tasks";

	private TasksCommands() {}

	static void register() {
		CommandRegistry.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(Commands.literal("tasks").requires(Perms.require(NODE)).executes(TasksCommands::open));
			dispatcher.register(Commands.literal("smpeco").requires(Perms.requireAdmin(Perms.ADMIN))
				.then(Commands.literal("tasks").requires(Perms.requireAdmin(ADMIN_NODE))
					.then(Commands.literal("info")
						.then(Commands.argument("player", StringArgumentType.word()).suggests(Args.knownPlayers())
							.executes(TasksCommands::info)))
					.then(Commands.literal("preview")
						.then(Commands.argument("player", StringArgumentType.word()).suggests(Args.knownPlayers())
							.executes(ctx -> preview(ctx, null))
							.then(Commands.argument("date", StringArgumentType.word())
								.executes(ctx -> preview(ctx, StringArgumentType.getString(ctx, "date"))))))
					.then(Commands.literal("set")
						.then(Commands.argument("player", StringArgumentType.word()).suggests(Args.knownPlayers())
							.then(Commands.argument("slot", IntegerArgumentType.integer(1, TasksConfig.MAX_SLOTS))
								.then(Commands.argument("task", StringArgumentType.word())
									.suggests((ctx, b) -> SharedSuggestionProvider.suggest(taskIds(), b))
									.executes(ctx -> set(ctx, -1))
									.then(Commands.argument("amount", LongArgumentType.longArg(1, TasksConfig.MAX_AMOUNT))
										.executes(ctx -> set(ctx, LongArgumentType.getLong(ctx, "amount"))))))))
					.then(Commands.literal("reset")
						.then(Commands.argument("player", StringArgumentType.word()).suggests(Args.knownPlayers())
							.executes(TasksCommands::reset)))
					.then(Commands.literal("clock")
						.executes(TasksCommands::clockShow)
						.then(Commands.literal("reset").executes(ctx -> clockSet(ctx, 0)))
						.then(Commands.argument("minutes", IntegerArgumentType.integer(-525_600, 525_600))
							.executes(ctx -> clockSet(ctx, IntegerArgumentType.getInteger(ctx, "minutes")))))));
		});
	}

	private static List<String> taskIds() {
		List<String> ids = new ArrayList<>();
		for (TaskDef d : TasksConfig.get().tasks) ids.add(d.id);
		return ids;
	}

	private static int open(CommandContext<CommandSourceStack> ctx) {
		ServerPlayer player = ctx.getSource().getPlayer();
		if (player == null) {
			Msg.error(ctx.getSource(), "Players only.");
			return 0;
		}
		TasksGui.open(player);
		return 1;
	}

	private static int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack src = ctx.getSource();
		UUID id = Args.knownPlayer(ctx, "player");
		ServerPlayer online = src.getServer().getPlayerList().getPlayer(id);
		if (online != null) TasksService.update(online);
		TaskState st = TasksService.peek(id);
		String name = EconomyService.nameOrUnknown(id);
		String money = " balance=" + EconomyService.balance(id) + " keys=" + CrateKeys.count(id, CrateKeys.COMMON);
		if (st == null) {
			Msg.send(src, Msg.legacy("§7tasks player=" + name + " date=- done=0/0 bonus=false" + money + " online=" + (online != null)));
			return 0;
		}
		Msg.send(src, Msg.legacy("§7tasks player=" + name + " date=" + st.date + " done=" + st.done + "/" + st.total
			+ " bonus=" + st.bonusPaid + money + " online=" + (online != null) + " today=" + TasksService.today()
			+ " bonusMoney=" + st.bonusMoney + " bonusKeys=" + st.bonusKeys + " bonusTier=" + st.bonusKeyTier));
		for (int i = 0; i < st.tasks.size(); i++) {
			ActiveTask t = st.tasks.get(i);
			Msg.send(src, Msg.legacy("§8  slot=" + (i + 1) + " id=" + t.id + " diff=" + t.difficulty + " target=" + t.target
				+ " progress=" + t.progress() + " reward=" + t.reward + " paid=" + t.paid + " excluded=" + t.excluded
				+ " credited=" + t.credited
				+ " §7(" + t.description + ")"));
		}
		return 1;
	}

	private static int preview(CommandContext<CommandSourceStack> ctx, String dateArg) throws CommandSyntaxException {
		CommandSourceStack src = ctx.getSource();
		UUID id = Args.knownPlayer(ctx, "player");
		LocalDate date = dateArg == null ? TasksService.today() : TasksService.parseDate(dateArg);
		if (date == null) {
			Msg.error(src, "Use the date format YYYY-MM-DD.");
			return 0;
		}
		List<TaskSelector.Pick> picks = TasksService.preview(id, date);
		StringBuilder sb = new StringBuilder("§7tasks preview player=" + EconomyService.nameOrUnknown(id) + " date=" + date + " picks=");
		for (int i = 0; i < picks.size(); i++) {
			TaskSelector.Pick p = picks.get(i);
			if (i > 0) sb.append(',');
			sb.append(p.def().id).append(':').append(p.amount()).append(':').append(p.reward());
		}
		Msg.send(src, Msg.legacy(sb.toString()));
		return picks.size();
	}

	private static int set(CommandContext<CommandSourceStack> ctx, long amount) throws CommandSyntaxException {
		CommandSourceStack src = ctx.getSource();
		UUID id = Args.knownPlayer(ctx, "player");
		ServerPlayer online = src.getServer().getPlayerList().getPlayer(id);
		if (online == null) {
			Msg.error(src, "The player must be online (statistics).");
			return 0;
		}
		String taskId = StringArgumentType.getString(ctx, "task");
		TaskDef def = TasksConfig.get().find(taskId);
		if (def == null || !TasksService.usable(def)) {
			Msg.error(src, "Unknown or unusable task: " + taskId);
			return 0;
		}
		int slot = IntegerArgumentType.getInteger(ctx, "slot");
		ActiveTask t = TasksService.replace(online, slot - 1, def, amount > 0 ? amount : def.min);
		if (t == null) {
			Msg.error(src, "Slot " + slot + " doesn't exist.");
			return 0;
		}
		TasksGui.refresh(online);
		Msg.success(src, "tasks set slot=" + slot + " id=" + t.id + " target=" + t.target + " reward=" + t.reward);
		return 1;
	}

	private static int reset(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack src = ctx.getSource();
		UUID id = Args.knownPlayer(ctx, "player");
		TasksService.clear(id);
		ServerPlayer online = src.getServer().getPlayerList().getPlayer(id);
		if (online != null) {
			TasksService.update(online);
			TasksGui.refresh(online);
		}
		Msg.success(src, "tasks reset " + EconomyService.nameOrUnknown(id) + (online != null ? " (reassigned)" : " (on next join)"));
		return 1;
	}

	private static int clockShow(CommandContext<CommandSourceStack> ctx) {
		Msg.send(ctx.getSource(), Msg.legacy("§7tasks clock offsetMinutes=" + TasksService.offset() / 60_000 + " today=" + TasksService.today()
			+ " resetIn=" + TimeUtil.formatDuration(TasksService.millisUntilReset())));
		return 1;
	}

	private static int clockSet(CommandContext<CommandSourceStack> ctx, int minutes) {
		TasksService.setOffset(minutes * 60_000L);
		if (minutes != 0) {
			// applies to ALL online players and sticks in their task state (date), so testing only
			Msg.error(ctx.getSource(), "Warning: the clock offset applies to all players and shifts their task day. Use on test servers only.");
		}
		return clockShow(ctx);
	}
}
