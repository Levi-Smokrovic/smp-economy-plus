package dev.smpeconomy.features.tasks;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.stats.Stat;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.Bots;
import dev.smpeconomy.core.CrateKeys;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.TimeUtil;
import dev.smpeconomy.features.tasks.TaskState.ActiveTask;
import dev.smpeconomy.features.tasks.TasksConfig.TaskDef;

/**
 * Daily tasks: assignment (deterministic per date + UUID), progress from vanilla stats ({@code ServerStatsCounter}),
 * automatic payout and bonus. All on the server thread.
 * <p>Time: {@link #now()} is a swappable clock ({@link #useClock}) plus an admin offset ({@link #setOffset}); the day
 * is determined in the server time zone. On a day change the old state gets evaluated one last time (progress up to
 * midnight still counts), then new tasks get assigned with a fresh base. If the clock runs backwards the saved later
 * day stays, so nothing pays again.
 * <p>Saving: assignment, payout, exclusions and credits (mining) go to {@code players.json} immediately (same flush as
 * balances). Plain progress can be recomputed from stats, so it goes at most every 60 s and on stop.
 */
public final class TasksService {
	static final long PROGRESS_SAVE_INTERVAL_MS = 60_000;

	private static LongSupplier clock = System::currentTimeMillis;
	private static long offsetMs;

	/** Cache of the mutable states; valid only while PlayerData has a taskState. */
	private static final Map<UUID, TaskState> CACHE = new HashMap<>();
	private static final Set<UUID> PENDING = new HashSet<>();
	private static final Map<UUID, Long> LAST_SAVE = new HashMap<>();
	/**
	 * Selftests only: runs right after every credit ({@code "task"} or {@code "bonus"}), e.g. to simulate a failure.
	 */
	static java.util.function.Consumer<String> payoutHookForTest;

	private TasksService() {}

	public static long now() {
		return clock.getAsLong() + offsetMs;
	}

	public static LocalDate today() {
		return TimeUtil.dateOf(now());
	}

	/** Swaps the clock for tests; the returned handle restores the old one. */
	public static Runnable useClock(LongSupplier newClock) {
		LongSupplier old = clock;
		clock = newClock;
		return () -> clock = old;
	}

	/** Admin offset of the task clock (e.g. +1 day to test the day change); 0 is real time. */
	public static void setOffset(long ms) {
		offsetMs = ms;
		if (ms != 0) SmpEconomy.LOG.warn("[SMPEco] tasks: clock offset active ({} min)", ms / 60_000);
	}

	public static long offset() {
		return offsetMs;
	}

	public static TaskState peek(UUID id) {
		boolean stored = PlayerDataService.peek(id).map(d -> d.taskState != null && !d.taskState.isJsonNull()).orElse(false);
		if (!stored) {
			// player data deleted or reset, so the cache is stale
			CACHE.remove(id);
			PENDING.remove(id);
			return null;
		}
		TaskState cached = CACHE.get(id);
		if (cached != null) return cached;
		TaskState st;
		try {
			st = PlayerDataService.getTaskState(id, TaskState.class);
		} catch (RuntimeException e) {
			SmpEconomy.LOG.error("[SMPEco] tasks: unreadable task state of {}, reassigning", id, e);
			st = null;
		}
		if (st == null) return null;
		st.fixNulls();
		CACHE.put(id, st);
		return st;
	}

	static void save(UUID id, TaskState st) {
		st.recount();
		CACHE.put(id, st);
		PlayerDataService.setTaskState(id, st);
		PENDING.remove(id);
		LAST_SAVE.put(id, System.currentTimeMillis());
	}

	private static void saveLater(UUID id, TaskState st) {
		PENDING.add(id);
		Long last = LAST_SAVE.get(id);
		if (last == null || System.currentTimeMillis() - last >= PROGRESS_SAVE_INTERVAL_MS) save(id, st);
	}

	public static void clear(UUID id) {
		CACHE.remove(id);
		PENDING.remove(id);
		LAST_SAVE.remove(id);
		if (PlayerDataService.peek(id).isPresent()) PlayerDataService.setTaskState(id, null);
	}

	/** Discards a player's cache after writing unsaved progress. For "restart" tests. */
	static void forget(UUID id) {
		TaskState st = CACHE.get(id);
		if (st != null && PENDING.contains(id) && PlayerDataService.peek(id).isPresent()) save(id, st);
		CACHE.remove(id);
		PENDING.remove(id);
		LAST_SAVE.remove(id);
	}

	/** "Crash" selftests only: discards the cache WITHOUT saving first. */
	static void dropCacheForTest(UUID id) {
		CACHE.remove(id);
		PENDING.remove(id);
		LAST_SAVE.remove(id);
	}

	public static void flushPending() {
		for (UUID id : List.copyOf(PENDING)) {
			TaskState st = CACHE.get(id);
			if (st != null && PlayerDataService.peek(id).isPresent()) save(id, st);
		}
		PENDING.clear();
	}

	static void resetRuntime() {
		CACHE.clear();
		PENDING.clear();
		LAST_SAVE.clear();
		StatRefs.clearCache();
	}

	public static boolean usable(TaskDef def) {
		return !StatRefs.resolve(def).isEmpty();
	}

	public static List<TaskSelector.Pick> preview(UUID player, LocalDate date) {
		return TaskSelector.select(TasksConfig.get(), player, date, TasksService::usable);
	}

	public static final class Update {
		public boolean assigned;
		public int paidTasks;
		public long paidCents;
		public boolean bonus;
	}

	public static Update update(ServerPlayer player) {
		return update(player, now());
	}

	/**
	 * Evaluates progress, pays finished tasks, reassigns on a new day. For online players, whose stats are
	 * the only ones in memory, and test FakePlayers.
	 */
	public static Update update(ServerPlayer player, long nowMs) {
		return update(player, nowMs, null);
	}

	/**
	 * Like {@link #update(ServerPlayer, long)}; a non-null {@code frozenOverride} forces "creative/spectator" for
	 * tests.
	 */
	static Update update(ServerPlayer player, long nowMs, Boolean frozenOverride) {
		Update result = new Update();
		UUID id = player.getUUID();
		LocalDate today = TimeUtil.dateOf(nowMs);
		TaskState st = peek(id);
		if (st != null) evaluate(player, st, result, frozenOverride != null ? frozenOverride : isFrozen(player));
		LocalDate stored = st == null ? null : parseDate(st.date);
		if (st == null || stored == null || stored.isBefore(today)) {
			assign(player, today, nowMs);
			result.assigned = true;
		}
		return result;
	}

	/** The HUD and the per-second check read the same text several times per second per player. */
	private record ParsedDate(String text, LocalDate date) {}

	private static ParsedDate lastParsed;

	static LocalDate parseDate(String s) {
		if (s == null || s.isEmpty()) return null;
		ParsedDate last = lastParsed;
		if (last != null && last.text().equals(s)) return last.date();
		LocalDate date;
		try {
			date = LocalDate.parse(s);
		} catch (DateTimeParseException e) {
			date = null;
		}
		lastParsed = new ParsedDate(s, date);
		return date;
	}

	static TaskState assign(ServerPlayer player, LocalDate date, long nowMs) {
		TasksConfig cfg = TasksConfig.get();
		UUID id = player.getUUID();
		TaskState st = new TaskState();
		st.date = date.toString();
		st.assignedAt = nowMs;
		for (TaskSelector.Pick pick : TaskSelector.select(cfg, id, date, TasksService::usable)) {
			st.tasks.add(instantiate(player, pick.def(), pick.amount(), pick.reward()));
		}
		st.bonusMoney = cfg.bonusMoney;
		st.bonusKeyTier = cfg.bonusKeyTier;
		st.bonusKeys = cfg.bonusKeys;
		save(id, st);
		if (isReal(player)) {
			Msg.send(player, Msg.prefixed(Msg.legacy("§eNew Daily Tasks are here! ")
				.append(Msg.clickRun("§6[View]", "/tasks", "§7Open Daily Tasks"))));
		}
		return st;
	}

	static ActiveTask instantiate(ServerPlayer player, TaskDef def, long amount, long reward) {
		ActiveTask t = new ActiveTask();
		t.id = def.id;
		t.name = def.name;
		t.description = def.describe(amount);
		t.icon = def.icon;
		t.difficulty = def.difficulty;
		t.category = def.category;
		List<Stat<?>> stats = StatRefs.resolve(def);
		List<String> keys = new ArrayList<>();
		for (Stat<?> s : stats) keys.add(StatRefs.key(s));
		t.stats = keys;
		t.resolved = new ArrayList<>(stats);
		t.divisor = def.divisor;
		t.target = amount;
		t.reward = reward;
		t.base = StatRefs.sum(player, stats);
		return t;
	}

	/**
	 * Admin and tests: replaces slot {@code slot} (0-based) with {@code def} and a fresh base, creating the day's state
	 * first if needed. Returns the new task, or null for an invalid slot.
	 */
	public static ActiveTask replace(ServerPlayer player, int slot, TaskDef def, long amount) {
		TaskState st = peek(player.getUUID());
		if (st == null) {
			update(player);
			st = peek(player.getUUID());
		}
		if (st == null || slot < 0 || slot >= st.tasks.size()) return null;
		long n = Math.max(1, Math.min(TasksConfig.MAX_AMOUNT, amount));
		ActiveTask t = instantiate(player, def, n, TasksConfig.rewardFor(def, n));
		st.tasks.set(slot, t);
		save(player.getUUID(), st);
		return t;
	}

	/** Creative/spectator: stat increases don't count, unless countInCreative. */
	static boolean isFrozen(ServerPlayer player) {
		return !TasksConfig.get().countInCreative && (player.isCreative() || player.isSpectator());
	}

	/**
	 * Evaluates all tasks. Payouts mark and save the state BEFORE the credit ({@link #pay}, {@link #payBonus}); the
	 * {@code finally} also saves when something later in the loop throws, otherwise "paid" would sit only in the cache
	 * and a restart would pay again.
	 */
	private static void evaluate(ServerPlayer player, TaskState st, Update result, boolean frozen) {
		boolean structural = false;
		boolean progressed = false;
		try {
			for (ActiveTask t : st.tasks) {
				List<Stat<?>> stats = t.resolved();
				if (!stats.isEmpty()) {
					long sum = StatRefs.sum(player, stats);
					long raw = sum - t.base - t.excluded + t.credited;
					if (raw < t.progressRaw) {
						// stat dropped (admin reset, rollback): move the base along, reached progress stays
						t.base = sum - t.excluded + t.credited - t.progressRaw;
						progressed = true;
					} else if (raw > t.progressRaw) {
						if (frozen) {
							t.base += raw - t.progressRaw; // creative/spectator doesn't count
						} else {
							t.progressRaw = raw;
						}
						progressed = true;
					}
				}
				if (!t.paid && t.complete()) {
					structural = true;
					pay(player, st, t, result);
				}
			}
			if (!st.bonusPaid && st.allPaid()) {
				structural = true;
				payBonus(player, st, result);
			}
		} finally {
			if (structural) save(player.getUUID(), st);
			else if (progressed) saveLater(player.getUUID(), st);
		}
	}

	private static void pay(ServerPlayer player, TaskState st, ActiveTask t, Update result) {
		// mark AND save first, then credit: "paid" lands in the same flush as the credit
		// (players.json + economy.json at the end of the tick) and survives a later exception
		t.paid = true;
		save(player.getUUID(), st);
		EconomyService.Result r = t.reward > 0 ? EconomyService.deposit(player.getUUID(), t.reward) : EconomyService.Result.OK;
		if (!r.ok()) {
			SmpEconomy.LOG.error("[SMPEco] tasks: reward {} for {} ({}) not credited: {}", t.reward, player.getPlainTextName(), t.id, r);
			if (isReal(player)) Msg.error(player, "Task done, but the reward could not be paid: " + r.message());
			return;
		}
		if (payoutHookForTest != null) payoutHookForTest.accept("task");
		result.paidTasks++;
		result.paidCents += t.reward;
		if (isReal(player)) {
			Msg.success(player, "Task complete: §e" + t.name + " §7→ " + Msg.moneyStr(t.reward));
			Gui.successSound(player);
		}
	}

	private static void payBonus(ServerPlayer player, TaskState st, Update result) {
		UUID id = player.getUUID();
		st.bonusPaid = true;
		save(id, st); // before money and keys (see pay)
		List<String> parts = new ArrayList<>();
		if (st.bonusMoney > 0) {
			EconomyService.Result r = EconomyService.deposit(id, st.bonusMoney);
			if (r.ok()) {
				result.paidCents += st.bonusMoney;
				parts.add(Msg.moneyStr(st.bonusMoney));
			} else {
				SmpEconomy.LOG.error("[SMPEco] tasks: bonus {} for {} not credited: {}", st.bonusMoney, player.getPlainTextName(), r);
			}
		}
		if (st.bonusKeys > 0 && CrateKeys.enabled()) {
			String tier = CrateKeys.TIERS.contains(st.bonusKeyTier) ? st.bonusKeyTier : CrateKeys.COMMON;
			CrateKeys.give(id, tier, st.bonusKeys);
			parts.add("§e" + st.bonusKeys + "× " + CrateKeys.label(tier) + (st.bonusKeys == 1 ? " Key" : " Keys") + "§7");
		}
		if (payoutHookForTest != null) payoutHookForTest.accept("bonus");
		result.bonus = true;
		if (isReal(player)) {
			Msg.info(player, "§6§lAll Daily Tasks done! §7Bonus: " + (parts.isEmpty() ? "-" : String.join(" + ", parts)));
			Gui.sound(player, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE), 1f);
		}
	}

	/**
	 * From {@link BreakTracker} after a break frame: tasks with the origin's stat get the part of the rise excluded
	 * that natural matching blocks don't cover (placed origin, placed, unobserved or foreign-type Veinminer
	 * follow-ups). Tasks without that stat get natural foreign-type follow-ups with their stat credited, since
	 * Veinminer attributed them to the origin. Paid tasks stay unchanged.
	 */
	static void onBlockBroken(ServerPlayer player, BreakTracker.Outcome o) {
		TaskState st = peek(player.getUUID());
		if (st == null) return;
		boolean changed = false;
		for (ActiveTask t : st.tasks) {
			if (t.paid || t.stats == null) continue;
			if (t.stats.contains(o.originKey)) {
				long ex = o.excludedFor(t.stats);
				if (ex > 0) {
					t.excluded += ex;
					changed = true;
				}
			} else {
				long cr = o.creditFor(t.stats);
				if (cr > 0) {
					t.credited += cr;
					changed = true;
				}
			}
		}
		if (changed) save(player.getUUID(), st);
	}

	static void tickAll(MinecraftServer server) {
		long nowMs = now();
		for (ServerPlayer p : List.copyOf(server.getPlayerList().getPlayers())) {
			if (!isReal(p)) continue;
			try {
				update(p, nowMs);
				TasksGui.refresh(p);
			} catch (RuntimeException e) {
				SmpEconomy.LOG.error("[SMPEco] tasks: update for {} failed", p.getPlainTextName(), e);
			}
		}
		if (!PENDING.isEmpty()) {
			long real = System.currentTimeMillis();
			for (UUID id : List.copyOf(PENDING)) {
				Long last = LAST_SAVE.get(id);
				TaskState st = CACHE.get(id);
				if (st == null) {
					PENDING.remove(id);
				} else if (last == null || real - last >= PROGRESS_SAVE_INTERVAL_MS) {
					if (PlayerDataService.peek(id).isPresent()) save(id, st);
					else PENDING.remove(id);
				}
			}
		}
	}

	/** Real connected player, no FakePlayer. */
	static boolean isReal(ServerPlayer p) {
		return p != null && !Bots.isBot(p);
	}

	/** Relative to {@link #now()}, in the server time zone. */
	public static long millisUntilReset() {
		java.time.ZonedDateTime from = java.time.Instant.ofEpochMilli(now()).atZone(TimeUtil.zone());
		return TimeUtil.millisUntilNext(java.time.LocalTime.MIDNIGHT, from);
	}
}
