package dev.smpeconomy.features.tasks;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import dev.smpeconomy.core.ConfigManager;
import dev.smpeconomy.core.CrateKeys;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.PlacedBlockTracker;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.TimeUtil;
import dev.smpeconomy.core.test.Assert;
import dev.smpeconomy.core.test.SelfTests;
import dev.smpeconomy.core.test.TestContext;
import dev.smpeconomy.features.tasks.TaskState.ActiveTask;
import dev.smpeconomy.features.tasks.TasksConfig.TaskDef;

/**
 * Selftests for the tasks feature, without GUI clicks (the GUI is covered by e2e). Test player stats get reset
 * afterwards.
 */
final class TasksSelfTests {
	private static final String F = "tasks";
	private static final Stat<?> JUMP = Stats.CUSTOM.get(Stats.JUMP);
	private static final Stat<?> WALK = Stats.CUSTOM.get(Stats.WALK_ONE_CM);
	private static final Stat<?> SPRINT = Stats.CUSTOM.get(Stats.SPRINT_ONE_CM);
	private static final Stat<?> MINED_STONE = Stats.BLOCK_MINED.get(Blocks.STONE);
	private static final Stat<?> MINED_DEEPSLATE = Stats.BLOCK_MINED.get(Blocks.DEEPSLATE);
	private static final Stat<?> MINED_DIAMOND = Stats.BLOCK_MINED.get(Blocks.DIAMOND_ORE);
	private static final Stat<?> MINED_DEEP_DIAMOND = Stats.BLOCK_MINED.get(Blocks.DEEPSLATE_DIAMOND_ORE);
	private static final Stat<?> MINED_COAL = Stats.BLOCK_MINED.get(Blocks.COAL_ORE);
	private static final Stat<?> MINED_CANE = Stats.BLOCK_MINED.get(Blocks.SUGAR_CANE);

	private TasksSelfTests() {}

	static void register() {
		SelfTests.register(F, "pool-defaults", ctx -> poolDefaults());
		SelfTests.register(F, "config-validation", ctx -> configValidation());
		SelfTests.register(F, "reward-scaling", ctx -> rewardScaling());
		SelfTests.register(F, "progress-bar", ctx -> progressBar());
		SelfTests.register(F, "determinism", TasksSelfTests::determinism);
		SelfTests.register(F, "progress-stat-delta", TasksSelfTests::progressStatDelta);
		SelfTests.register(F, "pay-once", TasksSelfTests::payOnce);
		SelfTests.register(F, "bonus-no-keys-without-crates", TasksSelfTests::bonusNoKeysWithoutCrates);
		SelfTests.register(F, "reset-next-day", TasksSelfTests::resetNextDay);
		SelfTests.register(F, "placed-blocks-excluded", TasksSelfTests::placedBlocksExcluded);
		SelfTests.register(F, "veinminer-follow-up-blocks", TasksSelfTests::veinminerFollowUps);
		SelfTests.register(F, "veinminer-mixed-types", TasksSelfTests::veinminerMixedTypes);
		SelfTests.register(F, "right-click-harvest", TasksSelfTests::rightClickHarvest);
		SelfTests.register(F, "paid-saved-before-payout", TasksSelfTests::paidSavedBeforePayout);
		SelfTests.register(F, "stat-decrease-and-creative", TasksSelfTests::statDecreaseAndCreative);
		SelfTests.register(F, "corrupt-state", TasksSelfTests::corruptState);
		SelfTests.register(F, "english-labels", ctx -> englishLabels());
	}

	private static long at(int y, int m, int d, int h, int min, int s) {
		return ZonedDateTime.of(y, m, d, h, min, s, 0, TimeUtil.zone()).toInstant().toEpochMilli();
	}

	/** Test player with an account; the given stats are zeroed before and after the test. */
	private static FakePlayer player(TestContext ctx, String name, Stat<?>... stats) {
		FakePlayer p = ctx.player(name);
		EconomyService.ensureAccount(p.getUUID(), name);
		for (Stat<?> s : stats) setStat(p, s, 0);
		ctx.cleanup(() -> {
			for (Stat<?> s : stats) setStat(p, s, 0);
			TasksService.forget(p.getUUID());
		});
		return p;
	}

	private static void setStat(FakePlayer p, Stat<?> stat, int value) {
		p.getStats().setValue(p, stat, value);
	}

	/** Break stats only count with the correct tool, as in vanilla; the hand is emptied afterwards. */
	private static void hold(TestContext ctx, FakePlayer p, net.minecraft.world.item.Item tool) {
		p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(tool));
		ctx.cleanup(() -> p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY));
	}

	private static TaskDef def(String id, long min, long reward, int divisor, String... stats) {
		TaskDef d = new TaskDef();
		d.id = "selftest_" + id;
		d.name = "Test " + id;
		d.description = "Test {n}";
		d.icon = "minecraft:paper";
		d.category = "test";
		d.difficulty = "easy";
		d.stats = new ArrayList<>(List.of(stats));
		d.min = min;
		d.max = min * 2;
		d.step = 1;
		d.reward = reward;
		d.divisor = divisor;
		return d;
	}

	private static TaskState state(FakePlayer p) {
		TaskState st = TasksService.peek(p.getUUID());
		Assert.notNull(st, "state exists");
		return st;
	}

	private static String ids(List<TaskSelector.Pick> picks) {
		StringBuilder sb = new StringBuilder();
		for (TaskSelector.Pick p : picks) sb.append(p.def().id).append(':').append(p.amount()).append(':').append(p.reward()).append(',');
		return sb.toString();
	}

	private static void poolDefaults() {
		TasksConfig defaults = TasksConfig.CONFIG.defaults();
		TasksConfig.validate(defaults);
		Assert.isTrue(defaults.tasks.size() >= 30, "at least 30 tasks in the default pool (" + defaults.tasks.size() + ")");
		Set<String> categories = new HashSet<>();
		for (TaskDef d : defaults.tasks) {
			categories.add(d.category);
			Assert.isTrue(d.description.contains("{n}"), d.id + ": description with {n}");
			Assert.isTrue(Items.byId(d.icon).isPresent(), d.id + ": icon " + d.icon + " exists");
			for (String ref : d.stats) Assert.isFalse(StatRefs.resolve(ref).isEmpty(), d.id + ": stat " + ref + " resolvable");
			Assert.isTrue(defaults.slots.contains(d.difficulty), d.id + ": difficulty " + d.difficulty + " is a slot");
		}
		for (String slot : defaults.slots) {
			long n = defaults.tasks.stream().filter(d -> d.difficulty.equals(slot) && d.enabled).count();
			Assert.isTrue(n >= 5, "Slot " + slot + " has enough tasks (" + n + ")");
		}
		Assert.isTrue(categories.size() >= 8, "varied: " + categories.size() + " categories");
		// tags (e.g. #minecraft:logs) yield several stats
		Assert.isTrue(StatRefs.resolve("mined:#minecraft:logs").size() >= 8, "tag #logs resolved");
		Assert.eq("mined:minecraft:stone", StatRefs.key(Stats.BLOCK_MINED.get(Blocks.STONE)), "canonical key");
		Assert.eq(JUMP, StatRefs.byKey("custom:minecraft:jump"), "key to stat");
	}

	private static void configValidation() {
		TasksConfig base = TasksConfig.CONFIG.defaults();
		TasksConfig.validate(base);
		String[] bad = {"dup", "minmax", "type", "customtag", "nostats", "slot", "tier", "reward", "divisor", "noslots", "toomanyslots"};
		for (String b : bad) {
			TasksConfig c = TasksConfig.CONFIG.defaults();
			TaskDef d = c.tasks.get(0);
			switch (b) {
				case "dup" -> c.tasks.get(1).id = d.id;
				case "minmax" -> { d.min = 10; d.max = 5; }
				case "type" -> d.stats = new ArrayList<>(List.of("foo:bar"));
				case "customtag" -> d.stats = new ArrayList<>(List.of("custom:#minecraft:x"));
				case "nostats" -> d.stats = new ArrayList<>();
				case "slot" -> c.slots = new ArrayList<>(List.of("Easy!"));
				case "tier" -> c.bonusKeyTier = "Apples";
				case "reward" -> d.reward = 0;
				case "divisor" -> d.divisor = 0;
				case "noslots" -> c.slots = new ArrayList<>();
				case "toomanyslots" -> c.slots = new ArrayList<>(List.of("a", "b", "c", "d", "e", "f"));
				default -> { }
			}
			Assert.assertThrows(IllegalArgumentException.class, () -> TasksConfig.validate(c), "invalid config rejected: " + b);
		}
		// unknown IDs aren't errors, the task just isn't usable
		TaskDef unknown = def("unknown", 1, 100, 1, "mined:minecraft:does_not_exist", "killed:minecraft:also_missing");
		Assert.isFalse(TasksService.usable(unknown), "task without a resolvable stat isn't usable");
		TasksConfig c = new TasksConfig();
		c.tasks = new ArrayList<>(List.of(unknown, def("ok", 1, 100, 1, "custom:jump")));
		c.slots = new ArrayList<>(List.of("easy", "easy"));
		List<TaskSelector.Pick> picks = TaskSelector.select(c, UUID.randomUUID(), LocalDate.of(2026, 1, 1), TasksService::usable);
		Assert.eq(1, picks.size(), "only the usable task gets assigned, none twice");
		Assert.eq("selftest_ok", picks.get(0).def().id, "usable task picked");
		StatRefs.clearCache();
	}

	private static void rewardScaling() {
		TaskDef d = def("reward", 10, 10_000, 1, "custom:jump");
		Assert.eq(10_000, TasksConfig.rewardFor(d, 10), "reward for min");
		Assert.eq(20_000, TasksConfig.rewardFor(d, 20), "double amount = double reward");
		Assert.eq(15_000, TasksConfig.rewardFor(d, 15), "linear");
		d.reward = 12_345;
		Assert.eq(12_300, TasksConfig.rewardFor(d, 10), "rounded to whole dollars");
		d.reward = 1;
		Assert.eq(100, TasksConfig.rewardFor(d, 10), "at least $1");
		d.reward = TasksConfig.MAX_REWARD;
		Assert.eq(TasksConfig.MAX_REWARD, TasksConfig.rewardFor(d, 1000), "capped");
		d.min = 3;
		d.max = 10;
		d.step = 4;
		Assert.eq(2, d.amountChoices(), "amounts 3, 7");
		Assert.eq(7, d.amount(1), "second amount");
		Assert.eq(10, d.amount(5), "never above max");
	}

	private static void progressBar() {
		Assert.eq("§a§8|||||||||||||||||||| §f0%", TasksGui.bar(0), "0 %");
		Assert.eq("§a||||||||||§8|||||||||| §f50%", TasksGui.bar(0.5), "50 %");
		Assert.eq("§a||||||||||||||||||||§8 §f100%", TasksGui.bar(1.0), "100 %");
		Assert.eq(TasksGui.bar(1.0), TasksGui.bar(7.5), "clamped above 100%");
		Assert.eq(TasksGui.bar(0), TasksGui.bar(-3), "negative = 0%");
		Assert.eq(TasksGui.bar(0), TasksGui.bar(Double.NaN), "NaN = 0%");
		Assert.eq(99, TasksGui.percent(0.999), "99.9% shows 99, not 100");
		ActiveTask t = new ActiveTask();
		t.target = 1000;
		t.divisor = 100;
		t.progressRaw = 150;
		Assert.eq(1, t.progress(), "divisor: 150 cm = 1 block");
		Assert.isFalse(t.complete(), "not done yet");
		t.progressRaw = 100_000;
		Assert.isTrue(t.complete(), "1000 blocks reached");
		t.progressRaw = 999_999;
		Assert.eq(1000, t.progress(), "display never above the target");
		Assert.eq(3, TasksGui.taskSlots(3).length, "3 Slots");
		Assert.eq(0, TasksGui.taskSlots(0).length, "0 Slots");
		Assert.eq(TasksConfig.MAX_SLOTS, TasksGui.taskSlots(99).length, "at most MAX_SLOTS");
		Assert.eq(13, TasksGui.taskSlots(3)[1], "middle task centered");
	}

	private static void determinism(TestContext ctx) {
		TasksConfig cfg = TasksConfig.get();
		LocalDate day = LocalDate.of(2026, 10, 10);
		Set<String> combos = new HashSet<>();
		Set<String> firstSlotOverDays = new HashSet<>();
		for (int i = 0; i < 60; i++) {
			UUID id = UUID.nameUUIDFromBytes(("tasks-determinism-" + i).getBytes());
			LocalDate date = day.plusDays(i % 7);
			List<TaskSelector.Pick> a = TaskSelector.select(cfg, id, date, TasksService::usable);
			List<TaskSelector.Pick> b = TaskSelector.select(cfg, id, date, TasksService::usable);
			Assert.eq(ids(a), ids(b), "same UUID + date, same selection");
			Assert.eq(cfg.slots.size(), a.size(), "one task per slot");
			Set<String> seen = new HashSet<>();
			Set<String> cats = new HashSet<>();
			for (int s = 0; s < a.size(); s++) {
				TaskSelector.Pick p = a.get(s);
				Assert.isTrue(seen.add(p.def().id), "no task twice");
				cats.add(p.def().category);
				Assert.eq(cfg.slots.get(s), p.def().difficulty, "difficulty matches the slot");
				Assert.isTrue(p.amount() >= p.def().min && p.amount() <= p.def().max, "amount in range");
				Assert.isTrue(p.amount() == p.def().max || (p.amount() - p.def().min) % p.def().step == 0, "amount on the step grid");
				Assert.eq(TasksConfig.rewardFor(p.def(), p.amount()), p.reward(), "reward matches the amount");
			}
			Assert.eq(a.size(), cats.size(), "different categories");
			combos.add(ids(a));
		}
		UUID fixed = UUID.nameUUIDFromBytes("tasks-determinism-fixed".getBytes());
		for (int d = 0; d < 30; d++) firstSlotOverDays.add(TaskSelector.select(cfg, fixed, day.plusDays(d), TasksService::usable).get(0).def().id);
		Assert.isTrue(combos.size() >= 30, "different players get different tasks (" + combos.size() + "/60)");
		Assert.isTrue(firstSlotOverDays.size() >= 6, "tasks change over the days (" + firstSlotOverDays.size() + " different in 30 days)");
		Assert.isFalse(TaskSelector.seed(fixed, day) == TaskSelector.seed(fixed, day.plusDays(1)), "seed depends on the date");

		// assignment to a player equals the preview (deterministic from date + UUID)
		FakePlayer p = player(ctx, "TasksDet");
		long t0 = at(2026, 10, 10, 9, 0, 0);
		Assert.isTrue(TasksService.update(p, t0).assigned, "first assignment");
		TaskState st = state(p);
		Assert.eq("2026-10-10", st.date, "date saved");
		List<TaskSelector.Pick> preview = TasksService.preview(p.getUUID(), LocalDate.of(2026, 10, 10));
		Assert.eq(preview.size(), st.tasks.size(), "count matches preview");
		for (int i = 0; i < preview.size(); i++) {
			Assert.eq(preview.get(i).def().id, st.tasks.get(i).id, "task " + (i + 1) + " matches preview");
			Assert.eq(preview.get(i).amount(), st.tasks.get(i).target, "amount " + (i + 1) + " matches preview");
		}
		// reassigning on the same day (admin reset) gives the same tasks
		TasksService.clear(p.getUUID());
		Assert.isTrue(TasksService.update(p, t0 + 3_600_000).assigned, "reassigned after reset");
		TaskState again = state(p);
		for (int i = 0; i < st.tasks.size(); i++) Assert.eq(st.tasks.get(i).id, again.tasks.get(i).id, "reset gives the same task " + (i + 1));
		JsonObject json = PlayerDataService.getTaskState(p.getUUID(), JsonObject.class);
		Assert.eq("2026-10-10", json.get("date").getAsString(), "JSON date");
		Assert.eq(st.tasks.size(), json.get("total").getAsInt(), "JSON total");
		Assert.eq(0, json.get("done").getAsInt(), "JSON done");
		ctx.detail(combos.size() + " combinations for 60 players, " + firstSlotOverDays.size() + " tasks in 30 days");
	}

	private static void progressStatDelta(TestContext ctx) {
		FakePlayer p = player(ctx, "TasksProg", JUMP, WALK, SPRINT);
		UUID id = p.getUUID();
		long t0 = at(2026, 3, 10, 12, 0, 0);
		setStat(p, JUMP, 100); // an old stat from before the assignment doesn't count
		TasksService.update(p, t0);
		TaskDef jump = def("jump", 5, 10_000, 1, "custom:jump");
		ActiveTask t = TasksService.replace(p, 0, jump, 5);
		Assert.notNull(t, "slot replaced");
		Assert.eq(100, t.base, "base = stat at assignment");
		TaskDef walk = def("walk", 2, 5_000, 100, "custom:walk_one_cm", "custom:sprint_one_cm");
		ActiveTask w = TasksService.replace(p, 1, walk, 2);
		Assert.notNull(w, "slot 2 replaced");
		long before = EconomyService.balance(id);

		TasksService.update(p, t0 + 1000);
		Assert.eq(0, state(p).tasks.get(0).progress(), "no progress without new jumps");
		setStat(p, JUMP, 103);
		TasksService.Update u = TasksService.update(p, t0 + 2000);
		Assert.eq(3, state(p).tasks.get(0).progress(), "progress = difference (3)");
		Assert.isFalse(state(p).tasks.get(0).paid, "not done yet");
		Assert.eq(0, u.paidTasks, "nothing paid");
		Assert.eq(before, EconomyService.balance(id), "balance unchanged");

		setStat(p, WALK, 50);
		setStat(p, SPRINT, 120);
		TasksService.update(p, t0 + 3000);
		Assert.eq(1, state(p).tasks.get(1).progress(), "170 cm (walking + sprinting) = 1 block");
		setStat(p, SPRINT, 150);
		u = TasksService.update(p, t0 + 4000);
		Assert.isTrue(state(p).tasks.get(1).paid, "200 cm = 2 blocks, done");
		Assert.eq(1, u.paidTasks, "one task paid");
		Assert.eq(w.reward, u.paidCents, "reward of the walking task");

		setStat(p, JUMP, 105);
		u = TasksService.update(p, t0 + 5000);
		Assert.isTrue(state(p).tasks.get(0).paid, "5 jumps, done");
		Assert.eq(t.reward, u.paidCents, "reward of the jump task");
		Assert.eq(before + t.reward + w.reward, EconomyService.balance(id), "balance = both rewards");
		Assert.eq(2, state(p).done, "2 done");
		ctx.detail("base 100, +3 gives 3/5, walking 170 cm gives 1/2, paid " + dev.smpeconomy.core.Money.format(t.reward + w.reward));
	}

	private static void bonusNoKeysWithoutCrates(TestContext ctx) {
		ctx.cleanup(CrateKeys.overrideEnabledForTest(false));
		FakePlayer p = player(ctx, "TasksNoKeys", JUMP);
		UUID id = p.getUUID();
		long t0 = at(2026, 5, 1, 8, 0, 0);
		TasksService.update(p, t0);
		TaskDef jump = def("jump_nokey", 2, 10_000, 1, "custom:jump");
		int n = state(p).tasks.size();
		Assert.isTrue(n >= 1, "tasks assigned");
		for (int i = 0; i < n; i++) TasksService.replace(p, i, jump, 2 + i);
		TaskState st = state(p);
		long[] keys = new long[CrateKeys.TIERS.size()];
		for (int i = 0; i < keys.length; i++) keys[i] = CrateKeys.count(id, CrateKeys.TIERS.get(i));
		long give = CrateKeys.give(id, CrateKeys.COMMON, 5);
		Assert.eq(keys[0], give, "CrateKeys.give is a no-op without crates");
		setStat(p, JUMP, 1 + n);
		TasksService.Update u = TasksService.update(p, t0 + 1000);
		Assert.isTrue(u.bonus, "bonus triggered");
		for (int i = 0; i < keys.length; i++) Assert.eq(keys[i], CrateKeys.count(id, CrateKeys.TIERS.get(i)), "no key " + CrateKeys.TIERS.get(i));
		ctx.detail("crates off: bonus " + dev.smpeconomy.core.Money.format(st.bonusMoney) + ", 0 keys (config bonusKeys=" + st.bonusKeys + ")");
	}

	private static void payOnce(TestContext ctx) {
		FakePlayer p = player(ctx, "TasksOnce", JUMP);
		UUID id = p.getUUID();
		long t0 = at(2026, 5, 1, 8, 0, 0);
		TasksService.update(p, t0);
		TaskDef jump = def("jump_once", 2, 10_000, 1, "custom:jump");
		int n = state(p).tasks.size();
		Assert.isTrue(n >= 1, "tasks assigned");
		long rewards = 0;
		for (int i = 0; i < n; i++) rewards += TasksService.replace(p, i, jump, 2 + i).reward;
		TaskState st = state(p);
		long balance = EconomyService.balance(id);
		long keys = CrateKeys.count(id, st.bonusKeyTier);

		setStat(p, JUMP, 1 + n); // all targets (2..n+1) reached
		TasksService.Update u = TasksService.update(p, t0 + 1000);
		Assert.eq(n, u.paidTasks, "all tasks paid in one pass");
		Assert.isTrue(u.bonus, "bonus triggered");
		Assert.eq(balance + rewards + st.bonusMoney, EconomyService.balance(id), "rewards + bonus credited");
		Assert.eq(keys + st.bonusKeys, CrateKeys.count(id, st.bonusKeyTier), "bonus key credited");
		Assert.isTrue(state(p).bonusPaid, "bonus recorded");

		long after = EconomyService.balance(id);
		for (int i = 0; i < 5; i++) {
			setStat(p, JUMP, 10 + i * 10);
			u = TasksService.update(p, t0 + 2000 + i * 1000L);
			Assert.eq(0, u.paidTasks, "no second payout (" + i + ")");
			Assert.isFalse(u.bonus, "no second bonus (" + i + ")");
		}
		// "restart": cache gone, state from players.json
		TasksService.forget(id);
		u = TasksService.update(p, t0 + 10_000);
		Assert.eq(0, u.paidTasks, "no second payout after reload");
		Assert.isFalse(u.assigned, "no new day");
		Assert.eq(after, EconomyService.balance(id), "balance stable");
		Assert.eq(keys + st.bonusKeys, CrateKeys.count(id, st.bonusKeyTier), "key exactly once");
		ctx.detail(n + " tasks + bonus exactly once: " + dev.smpeconomy.core.Money.format(rewards + st.bonusMoney) + " + " + st.bonusKeys + " keys");
	}

	private static void resetNextDay(TestContext ctx) {
		FakePlayer p = player(ctx, "TasksReset", JUMP);
		UUID id = p.getUUID();
		// night of the DST change (2026-03-29, 02:00 to 03:00)
		long lastSecond = at(2026, 3, 28, 23, 59, 59);
		TasksService.update(p, lastSecond);
		Assert.eq("2026-03-28", state(p).date, "day 1");
		ActiveTask t = TasksService.replace(p, 0, def("jump_reset", 3, 10_000, 1, "custom:jump"), 3);
		long balance = EconomyService.balance(id);
		setStat(p, JUMP, 3); // finished before midnight but not checked yet

		TasksService.Update u = TasksService.update(p, at(2026, 3, 29, 0, 0, 0) + 500);
		Assert.eq(1, u.paidTasks, "yesterday's task still pays on the day change");
		Assert.eq(balance + t.reward, EconomyService.balance(id), "yesterday's reward");
		Assert.isTrue(u.assigned, "new day, new tasks");
		TaskState st = state(p);
		Assert.eq("2026-03-29", st.date, "day 2");
		Assert.isFalse(st.bonusPaid, "bonus reset");
		Assert.eq(0, st.done, "nothing done");
		List<TaskSelector.Pick> preview = TasksService.preview(id, LocalDate.of(2026, 3, 29));
		for (int i = 0; i < st.tasks.size(); i++) {
			ActiveTask a = st.tasks.get(i);
			Assert.isFalse(a.paid, "new task " + (i + 1) + " unpaid");
			Assert.eq(0, a.progress(), "new task " + (i + 1) + " starts at 0");
			Assert.eq(preview.get(i).def().id, a.id, "new task " + (i + 1) + " = selection for day 2");
		}

		// clock runs backwards: no reset, no payout
		long b2 = EconomyService.balance(id);
		u = TasksService.update(p, at(2026, 3, 28, 12, 0, 0));
		Assert.isFalse(u.assigned, "clock back, no new day");
		Assert.eq("2026-03-29", state(p).date, "later day stays");
		Assert.eq(b2, EconomyService.balance(id), "no payout");
		Assert.isFalse(TasksService.update(p, at(2026, 3, 29, 23, 59, 0)).assigned, "same day, no reset");
		// several days offline: day of the next update
		u = TasksService.update(p, at(2026, 4, 5, 10, 0, 0));
		Assert.isTrue(u.assigned, "reassigned after days offline");
		Assert.eq("2026-04-05", state(p).date, "current day");
		Assert.isFalse(TasksService.update(p, at(2026, 4, 5, 23, 59, 59) + 999).assigned, "23:59:59.999 is still the same day");
		Assert.isTrue(TasksService.update(p, at(2026, 4, 6, 0, 0, 0)).assigned, "00:00:00 is a new day");
		ctx.detail("yesterday paid, day 2 fresh, clock back has no effect, midnight in server time zone");
	}

	private static void placedBlocksExcluded(TestContext ctx) {
		FakePlayer p = player(ctx, "TasksPlaced", MINED_STONE, MINED_DEEPSLATE, JUMP);
		ctx.cleanup(BreakTracker::clear); // on a FAIL mid-frame, leave no test frame behind
		hold(ctx, p, net.minecraft.world.item.Items.DIAMOND_PICKAXE);
		long t0 = at(2026, 6, 1, 12, 0, 0);
		setStat(p, MINED_STONE, 10);
		TasksService.update(p, t0);
		TasksService.replace(p, 0, def("stone", 5, 10_000, 1, "mined:minecraft:stone"), 5);
		boolean twoSlots = state(p).tasks.size() > 1;
		if (twoSlots) TasksService.replace(p, 1, def("jump_placed", 5, 10_000, 1, "custom:jump"), 5);

		mine(p, Blocks.STONE, false, MINED_STONE, 1);
		TasksService.update(p, t0 + 1000);
		Assert.eq(1, state(p).tasks.get(0).progress(), "natural stone counts");
		mine(p, Blocks.STONE, true, MINED_STONE, 1);
		TasksService.update(p, t0 + 2000);
		Assert.eq(1, state(p).tasks.get(0).progress(), "placed stone doesn't count");
		Assert.eq(1, state(p).tasks.get(0).excluded, "1 excluded");
		if (twoSlots) Assert.eq(0, state(p).tasks.get(1).excluded, "other tasks untouched");

		// nested (Veinminer): placed outside, natural + placed inside, outside itself +1
		Object outer = BreakTracker.beginForTest(p, Blocks.STONE, true);
		Object natural = BreakTracker.beginForTest(p, Blocks.STONE, false);
		bump(p, MINED_STONE, 1);
		BreakTracker.end(natural);
		Object placed = BreakTracker.beginForTest(p, Blocks.STONE, true);
		bump(p, MINED_STONE, 1);
		BreakTracker.end(placed);
		Object deep = BreakTracker.beginForTest(p, Blocks.DEEPSLATE, false);
		bump(p, MINED_DEEPSLATE, 1);
		BreakTracker.end(deep);
		bump(p, MINED_STONE, 1);
		BreakTracker.end(outer);
		Assert.eq(0, BreakTracker.depth(), "stack empty");
		TasksService.update(p, t0 + 3000);
		Assert.eq(3, state(p).tasks.get(0).excluded, "placed inside + placed outside excluded");
		Assert.eq(2, state(p).tasks.get(0).progress(), "only natural blocks count (2)");

		// a break without a stat rise (e.g. wrong tool) excludes nothing
		mine(p, Blocks.STONE, true, MINED_STONE, 0);
		Assert.eq(3, state(p).tasks.get(0).excluded, "no exclusion without a stat");
		TasksService.forget(p.getUUID());
		Assert.eq(3, state(p).tasks.get(0).excluded, "exclusion survives reload");
		Assert.eq(2, state(p).tasks.get(0).progress(), "progress survives reload");
		ctx.detail("placed and nested correctly subtracted");
	}

	/**
	 * Veinminer breaks follow-up blocks through removeBlock + Block#destroy inside the frame (not nested) and raises
	 * the origin's stat once by count - 1. Placed follow-up blocks must not count, a placed origin must exclude only
	 * itself.
	 */
	private static void veinminerFollowUps(TestContext ctx) {
		FakePlayer p = player(ctx, "TasksVein", MINED_STONE);
		ctx.cleanup(BreakTracker::clear);
		hold(ctx, p, net.minecraft.world.item.Items.DIAMOND_PICKAXE);
		long t0 = at(2026, 6, 2, 12, 0, 0);
		TasksService.update(p, t0);
		TasksService.replace(p, 0, def("stone_vein", 100, 10_000, 1, "mined:minecraft:stone"), 100);

		Assert.eq(0, BreakTracker.excludedAmount(1, false, 0), "natural single block counts");
		Assert.eq(1, BreakTracker.excludedAmount(1, true, 0), "placed single block doesn't count");
		Assert.eq(0, BreakTracker.excludedAmount(0, true, 0), "no exclusion without a stat");
		Assert.eq(0, BreakTracker.excludedAmount(0, false, 3), "never negative");

		// (a) natural origin + 3 natural + 4 placed follow-up blocks, stat +8 in one frame
		vein(p, false, 3, 4, 8);
		TasksService.update(p, t0 + 1000);
		Assert.eq(4, state(p).tasks.get(0).excluded, "(a) 4 placed follow-up blocks excluded");
		Assert.eq(4, state(p).tasks.get(0).progress(), "(a) origin + 3 natural count");
		// (b) placed origin (filled-in hole) + 5 natural follow-up blocks: only the origin drops out
		vein(p, true, 5, 0, 6);
		TasksService.update(p, t0 + 2000);
		Assert.eq(5, state(p).tasks.get(0).excluded, "(b) only the placed origin excluded");
		Assert.eq(9, state(p).tasks.get(0).progress(), "(b) natural vein counts");
		// (c) follow-up blocks not observed in the frame (Veinminer delay > 0): only the origin, to be safe
		vein(p, false, 0, 0, 5);
		TasksService.update(p, t0 + 3000);
		Assert.eq(9, state(p).tasks.get(0).excluded, "(c) unobserved rise excluded");
		Assert.eq(10, state(p).tasks.get(0).progress(), "(c) only the origin");
		// (d) tool breaks: stat +4, but only 2 follow-up blocks actually broken
		vein(p, false, 2, 0, 4);
		TasksService.update(p, t0 + 4000);
		Assert.eq(13, state(p).tasks.get(0).progress(), "(d) only blocks actually broken");

		// (e) real mixin path: Block#destroy in the frame (nothing in the world changes; markers at the world edge)
		ServerLevel level = ctx.overworld();
		BlockPos origin = new BlockPos(29_999_000, -60, 29_999_000);
		List<BlockPos> placed = List.of(origin.east(), origin.east(2), origin.east(3));
		List<BlockPos> natural = List.of(origin.west(), origin.west(2));
		for (BlockPos pos : placed) Assert.isFalse(PlacedBlockTracker.isPlayerPlaced(level, pos), "test position free");
		for (BlockPos pos : placed) PlacedBlockTracker.markPlaced(level, pos);
		ctx.cleanup(() -> placed.forEach(pos -> PlacedBlockTracker.unmark(level, pos)));
		BlockState stone = Blocks.STONE.defaultBlockState();
		Object frame = BreakTracker.beginForTest(p, level, origin, Blocks.STONE);
		Blocks.STONE.destroy(level, origin, stone); // own origin doesn't count twice
		for (BlockPos pos : placed) Blocks.STONE.destroy(level, pos, stone);
		for (BlockPos pos : natural) Blocks.STONE.destroy(level, pos, stone);
		Blocks.STONE.destroy(level, natural.get(0), stone); // same position reported twice: counts once
		ServerLevel nether = ctx.server().getLevel(Level.NETHER);
		if (nether != null) Blocks.STONE.destroy(nether, origin.north(), stone); // other level: not part of it
		bump(p, MINED_STONE, 6); // origin + 5 follow-up blocks
		BreakTracker.end(frame);
		Assert.eq(0, BreakTracker.depth(), "stack empty");
		Blocks.STONE.destroy(level, origin.south(), stone); // no open frame: no effect
		TasksService.update(p, t0 + 5000);
		Assert.eq(13, state(p).tasks.get(0).excluded, "(e) 3 placed follow-up blocks excluded via Block#destroy");
		Assert.eq(16, state(p).tasks.get(0).progress(), "(e) origin + 2 natural count");
		ctx.detail("placed follow-ups excluded, placed origin only itself, mixin path ok");
	}

	/**
	 * Veinminer groups mix block types ("Ores" = #c:ores, shovel group = gravel, sand, ...) and credit ALL follow-up
	 * blocks to the origin's stat. A follow-up block may count only for tasks with its own stat, otherwise one placed
	 * diamond ore on a coal vein fills the diamond task.
	 */
	private static void veinminerMixedTypes(TestContext ctx) {
		FakePlayer p = player(ctx, "TasksVeinMix", MINED_STONE, MINED_DEEPSLATE, MINED_DIAMOND, MINED_DEEP_DIAMOND, MINED_COAL);
		UUID id = p.getUUID();
		ctx.cleanup(BreakTracker::clear);
		hold(ctx, p, net.minecraft.world.item.Items.DIAMOND_PICKAXE);
		long t0 = at(2026, 6, 3, 12, 0, 0);
		TasksService.update(p, t0);
		TaskState st = state(p);
		st.tasks.clear();
		st.tasks.add(TasksService.instantiate(p, def("mix_stone", 100, 10_000, 1, "mined:minecraft:stone"), 100, 10_000));
		st.tasks.add(TasksService.instantiate(p, def("mix_deepslate", 100, 10_000, 1, "mined:minecraft:deepslate"), 100, 10_000));
		st.tasks.add(TasksService.instantiate(p, def("mix_diamond", 100, 10_000, 1, "mined:#minecraft:diamond_ores"), 100, 10_000));
		st.tasks.add(TasksService.instantiate(p, def("mix_coal", 100, 10_000, 1, "mined:#minecraft:coal_ores"), 100, 10_000));
		TasksService.save(id, st);

		// follow-up blocks of another type cover only tasks with their stat
		BreakTracker.Outcome o = new BreakTracker.Outcome("mined:minecraft:diamond_ore", 4, 0, java.util.Map.of("mined:minecraft:coal_ore", 3L));
		Assert.eq(4L, o.excludedFor(List.of("mined:minecraft:diamond_ore", "mined:minecraft:deepslate_diamond_ore")), "coal doesn't cover diamond");
		Assert.eq(1L, o.excludedFor(List.of("mined:minecraft:diamond_ore", "mined:minecraft:coal_ore")), "ore task with both: only the origin drops out");
		Assert.eq(3L, o.creditFor(List.of("mined:minecraft:coal_ore")), "coal task gets the coal");
		Assert.eq(0L, o.creditFor(List.of("mined:minecraft:iron_ore")), "unrelated task gets nothing");

		// (a) real mixin path: natural stone + 3 natural deepslate follow-ups, Veinminer: stone stat +4
		ServerLevel level = ctx.overworld();
		BlockPos origin = new BlockPos(29_999_100, -60, 29_999_100);
		List<BlockPos> deep = List.of(origin.east(), origin.east(2), origin.east(3));
		for (BlockPos pos : deep) Assert.isFalse(PlacedBlockTracker.isPlayerPlaced(level, pos), "test position free");
		BlockState deepslate = Blocks.DEEPSLATE.defaultBlockState();
		Object frame = BreakTracker.beginForTest(p, level, origin, Blocks.STONE);
		for (BlockPos pos : deep) Blocks.DEEPSLATE.destroy(level, pos, deepslate);
		bump(p, MINED_STONE, 4);
		BreakTracker.end(frame);
		Assert.eq(0, BreakTracker.depth(), "stack empty");
		TasksService.update(p, t0 + 1000);
		Assert.eq(3, state(p).tasks.get(0).excluded, "(a) deepslate share of the stone stat excluded");
		Assert.eq(1, state(p).tasks.get(0).progress(), "(a) stone: only the origin");
		Assert.eq(3, state(p).tasks.get(1).credited, "(a) deepslate credited");
		Assert.eq(3, state(p).tasks.get(1).progress(), "(a) deepslate: 3 follow-up blocks");

		// (b) exploit: placed diamond ore (silk touch) next to 3 natural coal ores, diamond stat +4
		vein(p, Blocks.DIAMOND_ORE, MINED_DIAMOND, true, Blocks.COAL_ORE, 3, 4);
		TasksService.update(p, t0 + 2000);
		Assert.eq(0, state(p).tasks.get(2).progress(), "(b) diamond task gets nothing");
		Assert.eq(4, state(p).tasks.get(2).excluded, "(b) whole rise excluded");
		Assert.eq(3, state(p).tasks.get(3).progress(), "(b) coal counts for the coal task");

		// (c) honest: natural diamond ore + 2 deepslate diamond ores (same tag) + 2 coal ores, stat +5
		Object mixed = BreakTracker.beginForTest(p, Blocks.DIAMOND_ORE, false);
		BreakTracker.recordForTest(Blocks.DEEPSLATE_DIAMOND_ORE, false);
		BreakTracker.recordForTest(Blocks.DEEPSLATE_DIAMOND_ORE, false);
		BreakTracker.recordForTest(Blocks.COAL_ORE, false);
		BreakTracker.recordForTest(Blocks.COAL_ORE, false);
		bump(p, MINED_DIAMOND, 5);
		BreakTracker.end(mixed);
		TasksService.update(p, t0 + 3000);
		Assert.eq(3, state(p).tasks.get(2).progress(), "(c) diamond: origin + 2 deepslate diamond ores");
		Assert.eq(5, state(p).tasks.get(3).progress(), "(c) coal: +2");

		// (d) wrong tool: wooden pickaxe, coal origin + diamond follow-up (vanilla wouldn't count it)
		hold(ctx, p, net.minecraft.world.item.Items.WOODEN_PICKAXE);
		vein(p, Blocks.COAL_ORE, MINED_COAL, false, Blocks.DIAMOND_ORE, 1, 2);
		TasksService.update(p, t0 + 4000);
		Assert.eq(6, state(p).tasks.get(3).progress(), "(d) coal: only the origin");
		Assert.eq(3, state(p).tasks.get(2).progress(), "(d) diamond without the correct tool: no credit");
		hold(ctx, p, net.minecraft.world.item.Items.DIAMOND_PICKAXE);

		// (e) follow-up block with its own stat rise (other mods): don't count twice
		Object own = BreakTracker.beginForTest(p, Blocks.STONE, false);
		BreakTracker.recordForTest(Blocks.DEEPSLATE, false);
		BreakTracker.recordForTest(Blocks.DEEPSLATE, false);
		bump(p, MINED_DEEPSLATE, 2);
		bump(p, MINED_STONE, 1);
		BreakTracker.end(own);
		TasksService.update(p, t0 + 5000);
		Assert.eq(5, state(p).tasks.get(1).progress(), "(e) deepslate +2 (only through its own stat)");
		Assert.eq(3, state(p).tasks.get(1).credited, "(e) no extra credit");
		Assert.eq(2, state(p).tasks.get(0).progress(), "(e) stone +1");

		// credit survives a reload; paid tasks stay untouched
		TasksService.forget(id);
		Assert.eq(3, state(p).tasks.get(1).credited, "credit saved");
		Assert.eq(5, state(p).tasks.get(1).progress(), "progress after reload");
		state(p).tasks.get(3).paid = true;
		long excludedBefore = state(p).tasks.get(3).excluded;
		long creditedBefore = state(p).tasks.get(3).credited;
		vein(p, Blocks.DIAMOND_ORE, MINED_DIAMOND, true, Blocks.COAL_ORE, 2, 3);
		Assert.eq(excludedBefore, state(p).tasks.get(3).excluded, "paid: no exclusion");
		Assert.eq(creditedBefore, state(p).tasks.get(3).credited, "paid: no credit");
		ctx.detail("foreign-type follow-ups only for their own task, placed origin has no effect, tool checked");
	}

	/**
	 * RightClickHarvest: {@code completeHarvest} fires BEFORE (harvested position), raises {@code BLOCK_MINED} itself
	 * and removes the block, with no destroyBlock, inside the right-click's {@code useItemOn}. The exploit: set sugar
	 * cane on sugar cane, right-click harvest, set it again. Placed harvests must not count, naturally grown ones must.
	 * Simulates the exact calls of the mixin ({@code useItemOn}) and the BEFORE listener; nothing in the world changes.
	 */
	private static void rightClickHarvest(TestContext ctx) {
		FakePlayer p = player(ctx, "TasksRch", MINED_CANE);
		ctx.cleanup(BreakTracker::clear);
		long t0 = at(2026, 6, 4, 12, 0, 0);
		TasksService.update(p, t0);
		TasksService.replace(p, 0, def("cane", 100, 10_000, 1, "mined:minecraft:sugar_cane"), 100);

		ServerLevel level = ctx.overworld();
		BlockPos base = new BlockPos(29_999_200, -60, 29_999_200);
		List<BlockPos> placed = List.of(base.above(), base.above(2), base.east().above(), base.east(2).above());
		List<BlockPos> natural = List.of(base.west().above(), base.west(2).above(), base.west(3).above(), base.west(4).above());
		for (BlockPos pos : placed) Assert.isFalse(PlacedBlockTracker.isPlayerPlaced(level, pos), "test position free");
		for (BlockPos pos : natural) Assert.isFalse(PlacedBlockTracker.isPlayerPlaced(level, pos), "test position free");
		for (BlockPos pos : placed) PlacedBlockTracker.markPlaced(level, pos);
		ctx.cleanup(() -> placed.forEach(pos -> PlacedBlockTracker.unmark(level, pos)));
		BlockState cane = Blocks.SUGAR_CANE.defaultBlockState();

		// (a) exploit: self-placed sugar cane harvested by right-click doesn't count
		rightClick(level, () -> harvest(p, level, placed.get(0), cane, 1));
		Assert.eq(0, BreakTracker.depth(), "(a) frame closed at the end of useItemOn");
		TasksService.update(p, t0 + 1000);
		Assert.eq(0, state(p).tasks.get(0).progress(), "(a) placed harvest doesn't count");
		Assert.eq(1, state(p).tasks.get(0).excluded, "(a) 1 excluded");
		// (b) naturally grown sugar cane counts
		rightClick(level, () -> harvest(p, level, natural.get(0), cane, 1));
		TasksService.update(p, t0 + 2000);
		Assert.eq(1, state(p).tasks.get(0).progress(), "(b) natural harvest counts");
		Assert.eq(1, state(p).tasks.get(0).excluded, "(b) no further exclusion");
		// (c) radius harvest: one click, several harvests in a row (placed, natural, placed)
		rightClick(level, () -> {
			harvest(p, level, placed.get(1), cane, 1);
			harvest(p, level, natural.get(1), cane, 1);
			harvest(p, level, placed.get(2), cane, 1);
		});
		Assert.eq(0, BreakTracker.depth(), "(c) stack empty");
		TasksService.update(p, t0 + 3000);
		Assert.eq(2, state(p).tasks.get(0).progress(), "(c) only the natural harvest counts");
		Assert.eq(3, state(p).tasks.get(0).excluded, "(c) 2 placed excluded");
		// (d) Veinminer reacts in the same BEFORE: placed origin + 2 natural follow-ups through Block#destroy,
		// stat: Veinminer +2 (count - 1), RightClickHarvest +1
		rightClick(level, () -> {
			harvest(p, level, placed.get(3), cane, 0);
			Blocks.SUGAR_CANE.destroy(level, natural.get(2), cane);
			Blocks.SUGAR_CANE.destroy(level, natural.get(3), cane);
			bump(p, MINED_CANE, 3);
		});
		TasksService.update(p, t0 + 4000);
		Assert.eq(4, state(p).tasks.get(0).progress(), "(d) natural follow-up blocks count");
		Assert.eq(4, state(p).tasks.get(0).excluded, "(d) placed origin excluded");

		// (e) BEFORE without a right-click (other paths) or inside a break opens no right-click frame
		BreakTracker.beforeHarvest(p, level, natural.get(0), cane);
		Assert.eq(0, BreakTracker.depth(), "(e) no frame outside useItemOn");
		Object breaking = BreakTracker.beginForTest(p, level, natural.get(0), Blocks.SUGAR_CANE);
		rightClick(level, () -> {
			BreakTracker.beforeHarvest(p, level, natural.get(1), cane);
			Assert.eq(1, BreakTracker.depth(), "(e) no second frame inside a break frame");
		});
		Assert.eq(1, BreakTracker.depth(), "(e) end of useItemOn closes no break frame");
		BreakTracker.end(breaking);
		// (f) FakePlayer (machines from other mods) and air open no frame
		rightClick(level, () -> {
			BreakTracker.onBreakBefore(level, p, placed.get(0), cane);
			BreakTracker.beforeHarvest(p, level, placed.get(0), Blocks.AIR.defaultBlockState());
			Assert.eq(0, BreakTracker.depth(), "(f) no frame for FakePlayer or air");
		});
		Assert.eq(0, BreakTracker.depth(), "stack empty");
		TasksService.update(p, t0 + 5000);
		Assert.eq(4, state(p).tasks.get(0).progress(), "(e/f) progress unchanged");

		TasksService.forget(p.getUUID());
		Assert.eq(4, state(p).tasks.get(0).excluded, "exclusion survives reload");
		Assert.eq(4, state(p).tasks.get(0).progress(), "progress survives reload");
		ctx.detail("right-click harvest: placed sugar cane excluded, natural counts, radius and Veinminer correct");
	}

	/**
	 * Like the mixin around {@code ServerPlayerGameMode#useItemOn}: right-click begins, {@code inside} runs, end in
	 * finally.
	 */
	private static void rightClick(ServerLevel level, Runnable inside) {
		boolean counted = BreakTracker.beginUse(level);
		Assert.isTrue(counted, "right-click counted on the server thread");
		try {
			inside.run();
		} finally {
			BreakTracker.endUse(counted);
		}
	}

	/** A harvest like RightClickHarvest#completeHarvest: BEFORE (harvested position), then stat +delta. */
	private static void harvest(FakePlayer p, ServerLevel level, BlockPos pos, BlockState state, int delta) {
		BreakTracker.beforeHarvest(p, level, pos, state);
		Assert.eq(1, BreakTracker.depth(), "right-click frame open");
		bump(p, MINED_CANE, delta);
	}

	/**
	 * Veinminer break without a level: origin {@code origin}, {@code n} natural follow-ups {@code follow}, stat +delta.
	 */
	private static void vein(FakePlayer p, Block origin, Stat<?> stat, boolean originPlaced, Block follow, int n, int delta) {
		Object frame = BreakTracker.beginForTest(p, origin, originPlaced);
		for (int i = 0; i < n; i++) BreakTracker.recordForTest(follow, false);
		bump(p, stat, delta);
		BreakTracker.end(frame);
	}

	/** A Veinminer break: frame, reported follow-ups, stat once by {@code statDelta}. */
	private static void vein(FakePlayer p, boolean originPlaced, int natural, int placed, int statDelta) {
		Object frame = BreakTracker.beginForTest(p, Blocks.STONE, originPlaced);
		for (int i = 0; i < natural; i++) BreakTracker.recordForTest(Blocks.STONE, false);
		for (int i = 0; i < placed; i++) BreakTracker.recordForTest(Blocks.STONE, true);
		bump(p, MINED_STONE, statDelta);
		BreakTracker.end(frame);
	}

	/**
	 * If something throws AFTER a credit (message, sound, key), "paid" must already be in players.json, or a restart
	 * pays again.
	 */
	private static void paidSavedBeforePayout(TestContext ctx) {
		FakePlayer p = player(ctx, "TasksCrash", JUMP);
		UUID id = p.getUUID();
		ctx.cleanup(() -> TasksService.payoutHookForTest = null);
		long t0 = at(2026, 9, 1, 12, 0, 0);
		TasksService.update(p, t0);
		TaskState st = state(p);
		st.tasks.clear();
		st.tasks.add(TasksService.instantiate(p, def("jump_crash_a", 1, 10_000, 1, "custom:jump"), 1, 10_000));
		st.tasks.add(TasksService.instantiate(p, def("jump_crash_b", 1, 20_000, 1, "custom:jump"), 3, 20_000));
		TasksService.save(id, st);
		long balance = EconomyService.balance(id);
		long keys = CrateKeys.count(id, st.bonusKeyTier);

		String[] failAt = {"task"};
		boolean[] stored = {false};
		TasksService.payoutHookForTest = what -> {
			if (!what.equals(failAt[0])) return;
			JsonObject json = PlayerDataService.getTaskState(id, JsonObject.class);
			stored[0] = what.equals("bonus") ? json.get("bonusPaid").getAsBoolean()
				: json.getAsJsonArray("tasks").get(0).getAsJsonObject().get("paid").getAsBoolean();
			throw new IllegalStateException("selftest: failure after the credit (" + what + ")");
		};

		setStat(p, JUMP, 1);
		Assert.assertThrows(IllegalStateException.class, () -> TasksService.update(p, t0 + 1000), "failure after the credit");
		Assert.isTrue(stored[0], "task: 'paid' was already in players.json at the credit");
		Assert.eq(balance + 10_000, EconomyService.balance(id), "task credited once");
		TasksService.dropCacheForTest(id); // crash: cache gone, nothing more saved
		TasksService.Update u = TasksService.update(p, t0 + 2000);
		Assert.eq(0, u.paidTasks, "no second payout after restart");
		Assert.eq(balance + 10_000, EconomyService.balance(id), "balance stable");

		failAt[0] = "bonus";
		stored[0] = false;
		setStat(p, JUMP, 3);
		Assert.assertThrows(IllegalStateException.class, () -> TasksService.update(p, t0 + 3000), "failure after the bonus");
		Assert.isTrue(stored[0], "bonus: 'paid' was already in players.json at the credit");
		long afterBonus = EconomyService.balance(id);
		Assert.eq(balance + 30_000 + st.bonusMoney, afterBonus, "second task + bonus credited once");
		Assert.eq(keys + st.bonusKeys, CrateKeys.count(id, st.bonusKeyTier), "bonus key once");
		TasksService.dropCacheForTest(id);
		u = TasksService.update(p, t0 + 4000);
		Assert.eq(0, u.paidTasks, "no payout after restart");
		Assert.isFalse(u.bonus, "no second bonus after restart");
		Assert.eq(afterBonus, EconomyService.balance(id), "balance stable (bonus)");
		Assert.eq(keys + st.bonusKeys, CrateKeys.count(id, st.bonusKeyTier), "key exactly once");
		ctx.detail("failure after credit or bonus: state was saved, restart doesn't pay again");
	}

	private static void mine(FakePlayer p, Block block, boolean placed, Stat<?> stat, int delta) {
		Object frame = BreakTracker.beginForTest(p, block, placed);
		bump(p, stat, delta);
		BreakTracker.end(frame);
	}

	private static void bump(FakePlayer p, Stat<?> stat, int delta) {
		setStat(p, stat, p.getStats().getValue(stat) + delta);
	}

	private static void statDecreaseAndCreative(TestContext ctx) {
		FakePlayer p = player(ctx, "TasksDecr", JUMP);
		long t0 = at(2026, 7, 1, 12, 0, 0);
		setStat(p, JUMP, 50);
		TasksService.update(p, t0, false);
		ActiveTask t = TasksService.replace(p, 0, def("jump_decr", 5, 10_000, 1, "custom:jump"), 5);
		setStat(p, JUMP, 52);
		TasksService.update(p, t0 + 1000, false);
		Assert.eq(2, state(p).tasks.get(0).progress(), "2/5");
		// stat file deleted or reset: progress stays, further jumps keep counting
		setStat(p, JUMP, 0);
		TasksService.update(p, t0 + 2000, false);
		Assert.eq(2, state(p).tasks.get(0).progress(), "progress doesn't drop");
		setStat(p, JUMP, 1);
		TasksService.update(p, t0 + 3000, false);
		Assert.eq(3, state(p).tasks.get(0).progress(), "keeps counting after reset");
		// creative/spectator: the increase gets swallowed
		setStat(p, JUMP, 20);
		TasksService.Update u = TasksService.update(p, t0 + 4000, true);
		Assert.eq(3, state(p).tasks.get(0).progress(), "creative doesn't count");
		Assert.eq(0, u.paidTasks, "no payout in creative");
		setStat(p, JUMP, 21);
		TasksService.update(p, t0 + 5000, false);
		Assert.eq(4, state(p).tasks.get(0).progress(), "back in survival only new progress counts");
		long before = EconomyService.balance(p.getUUID());
		setStat(p, JUMP, 22);
		u = TasksService.update(p, t0 + 6000, false);
		Assert.eq(1, u.paidTasks, "5/5, paid");
		Assert.eq(before + t.reward, EconomyService.balance(p.getUUID()), "reward");
	}

	private static void englishLabels() {
		for (TaskDef d : TasksConfig.CONFIG.defaults().tasks) {
			Assert.isTrue(d.name != null && !d.name.matches(".*[\\u00C0-\\u00D6\\u00D8-\\u00F6\\u00F8-\\u00FF].*"), d.id + ": name in English: " + d.name);
			Assert.isTrue(d.description != null && !d.description.matches(".*[\\u00C0-\\u00D6\\u00D8-\\u00F6\\u00F8-\\u00FF].*"), d.id + ": description in English: " + d.description);
		}
		ActiveTask a = new ActiveTask();
		a.id = "quarry";
		a.name = "Quarry";
		a.target = 1234;
		a.description = "Mine 1,234 stone blocks";
		Assert.eq("§aEasy", TasksConfig.difficultyLabel("easy"), "difficulty easy");
		Assert.eq("§cHard", TasksConfig.difficultyLabel("hard"), "difficulty hard");
		Assert.eq("Mining", TasksConfig.categoryLabel("mining"), "category mining");
		Assert.eq("Custom", TasksConfig.categoryLabel("custom"), "custom category capitalized");

		TaskState bonus = new TaskState();
		bonus.tasks.add(a);
		bonus.bonusKeys = 1;
		bonus.bonusKeyTier = CrateKeys.COMMON;
		List<String> lore = loreLines(TasksGui.bonusItem(bonus, 0).asStack());
		Assert.isTrue(lore.contains("• 1× Common Key"), "bonus lore 1× Common Key: " + lore);
		bonus.bonusKeys = 2;
		bonus.bonusKeyTier = CrateKeys.RARE;
		lore = loreLines(TasksGui.bonusItem(bonus, 0).asStack());
		Assert.isTrue(lore.contains("• 2× Rare Keys"), "bonus lore 2× Rare Keys: " + lore);
		Assert.eq("Epic", CrateKeys.label(CrateKeys.EPIC), "crate epic is called Epic");
	}

	private static List<String> loreLines(ItemStack stack) {
		net.minecraft.world.item.component.ItemLore lore = stack.get(net.minecraft.core.component.DataComponents.LORE);
		return lore == null ? List.of() : lore.lines().stream().map(net.minecraft.network.chat.Component::getString).toList();
	}

	private static void corruptState(TestContext ctx) {
		FakePlayer p = player(ctx, "TasksCorrupt");
		long t0 = at(2026, 8, 1, 12, 0, 0);
		PlayerDataService.setTaskState(p.getUUID(), new JsonPrimitive("broken"));
		TasksService.forget(p.getUUID());
		Assert.isTrue(TasksService.update(p, t0).assigned, "unreadable state, reassigned");
		JsonObject bad = new JsonObject();
		bad.addProperty("date", "yesterday");
		bad.add("tasks", new com.google.gson.JsonArray());
		PlayerDataService.setTaskState(p.getUUID(), bad);
		TasksService.forget(p.getUUID());
		Assert.isTrue(TasksService.update(p, t0).assigned, "invalid date, reassigned");
		Assert.eq("2026-08-01", state(p).date, "valid state");
		// player data deleted: cache discarded
		PlayerDataService.delete(p.getUUID());
		Assert.isTrue(TasksService.peek(p.getUUID()) == null, "deleted data, no state");
		Assert.isTrue(TasksService.update(p, t0).assigned, "reassigned afterwards");
	}
}
