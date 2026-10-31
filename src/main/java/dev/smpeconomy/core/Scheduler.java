package dev.smpeconomy.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import dev.smpeconomy.SmpEconomy;

/**
 * Tick-based tasks on the server thread, run at tick end. {@code runLater(1, ..)} from a command or event runs at the
 * end of the current tick, from a scheduler task at the end of the next. Exceptions are logged. All tasks are dropped
 * on server stop.
 */
public final class Scheduler {
	private static final List<Task> TASKS = new ArrayList<>();
	private static final List<Task> PENDING = new ArrayList<>();

	private Scheduler() {}

	public static Task runLater(int delayTicks, Runnable action) {
		return add(new Task(action, Math.max(1, delayTicks), 0));
	}

	public static Task runRepeating(int delayTicks, int periodTicks, Runnable action) {
		return add(new Task(action, Math.max(1, delayTicks), Math.max(1, periodTicks)));
	}

	public static void tick() {
		TASKS.addAll(PENDING);
		PENDING.clear();
		Iterator<Task> it = TASKS.iterator();
		while (it.hasNext()) {
			Task t = it.next();
			if (t.cancelled) {
				it.remove();
				continue;
			}
			if (--t.remaining > 0) continue;
			try {
				t.action.run();
			} catch (Throwable e) {
				SmpEconomy.LOG.error("[SMPEco] Scheduler task failed", e);
			}
			if (t.period > 0 && !t.cancelled) {
				t.remaining = t.period;
			} else {
				t.cancelled = true;
				it.remove();
			}
		}
	}

	public static void clear() {
		TASKS.forEach(t -> t.cancelled = true);
		PENDING.forEach(t -> t.cancelled = true);
		TASKS.clear();
		PENDING.clear();
	}

	private static Task add(Task task) {
		PENDING.add(task);
		return task;
	}

	public static final class Task {
		private final Runnable action;
		private final int period;
		private int remaining;
		private boolean cancelled;

		private Task(Runnable action, int delay, int period) {
			this.action = action;
			this.remaining = delay;
			this.period = period;
		}

		public void cancel() {
			cancelled = true;
		}

		public boolean isCancelled() {
			return cancelled;
		}
	}
}
