package dev.smpeconomy.features.tasks;

import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.Feature;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.JoinGrace;
import dev.smpeconomy.core.Scheduler;
import dev.smpeconomy.core.Services;

/**
 * Tasks feature: {@code /tasks} gives each player daily tasks, deterministic from date + UUID in the server time zone,
 * based on vanilla stats, with automatic rewards and a bonus (money + crate key). Resets at midnight. Logic in {@link
 * TasksService}, pool in {@code tasks.json}.
 */
public final class TasksFeature implements Feature {
	static final int CHECK_TICKS = 20;
	/** No "open tasks" hint when tasks were only just assigned; "New Daily Tasks" already went out then. */
	private static final long REMINDER_GRACE_MS = 15_000;

	@Override
	public String id() {
		return "tasks";
	}

	@Override
	public void register() {
		TasksConfig.bootstrap();
		Services.register(Services.DailyTasks.class, new Services.DailyTasks() {
			@Override
			public int done(UUID player) {
				TaskState st = todayState(player);
				return st == null ? 0 : st.done;
			}

			@Override
			public int total(UUID player) {
				TaskState st = todayState(player);
				return st == null || st.total <= 0 ? TasksConfig.get().slots.size() : st.total;
			}
		});
		TasksCommands.register();
		TasksSelfTests.register();
		// RightClickHarvest raises "mined" without destroyBlock, so it needs its own frame to keep placed harvests from
		// counting
		BreakTracker.registerHarvestFrames();
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			UUID id = handler.getPlayer().getUUID();
			Scheduler.runLater(60 + JoinGrace.extraTicks(handler.getPlayer()), () -> joinReminder(server, id));
		});
		// tags can change on /reload, so re-resolve stats (running tasks keep their list)
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> StatRefs.clearCache());
	}

	/** Null when the saved state is from an earlier day. */
	private static TaskState todayState(UUID player) {
		TaskState st = TasksService.peek(player);
		if (st == null) return null;
		java.time.LocalDate date = TasksService.parseDate(st.date);
		return date == null || date.isBefore(TasksService.today()) ? null : st;
	}

	@Override
	public void onServerStarted(MinecraftServer server) {
		TasksService.resetRuntime();
		BreakTracker.clear();
		Scheduler.runRepeating(CHECK_TICKS, CHECK_TICKS, () -> TasksService.tickAll(server));
	}

	@Override
	public void onServerStopping(MinecraftServer server) {
		TasksService.flushPending();
		TasksGui.closeAll();
		BreakTracker.clear();
	}

	private static void joinReminder(MinecraftServer server, UUID id) {
		if (SmpEconomy.isStopping()) return;
		ServerPlayer player = server.getPlayerList().getPlayer(id);
		if (player == null) return;
		TasksService.Update u = TasksService.update(player);
		if (u.assigned) return;
		TaskState st = TasksService.peek(id);
		if (st == null || st.tasks.isEmpty() || st.done >= st.total) return;
		if (TasksService.now() - st.assignedAt < REMINDER_GRACE_MS) return;
		Msg.send(player, Msg.prefixed(Msg.legacy("§7Daily Tasks: §a" + st.done + "§7/§f" + st.total + " §7done. ")
			.append(Msg.clickRun("§6[View]", "/tasks", "§7Open Daily Tasks"))));
	}
}
