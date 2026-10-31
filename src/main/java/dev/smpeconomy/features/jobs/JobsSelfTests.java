package dev.smpeconomy.features.jobs;

import static dev.smpeconomy.core.test.Assert.assertThrows;
import static dev.smpeconomy.core.test.Assert.eq;
import static dev.smpeconomy.core.test.Assert.isFalse;
import static dev.smpeconomy.core.test.Assert.isTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BeetrootBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.end.EndDragonFight;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.compat.Mc;
import dev.smpeconomy.core.BasePrices;
import dev.smpeconomy.core.ConfigManager;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Hooks;
import dev.smpeconomy.core.MobOrigin;
import dev.smpeconomy.core.PlacedBlockTracker;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.PriceGuard;
import dev.smpeconomy.core.test.SelfTests;
import dev.smpeconomy.core.test.TestContext;
import dev.smpeconomy.features.jobs.JobsConfig.Job;
import dev.smpeconomy.features.jobs.JobsConfig.Reward;

/**
 * Selftests for the jobs feature ({@code /smpeco selftest jobs}). Logic runs without the GUI, world tests run in the
 * sky above spawn.
 */
public final class JobsSelfTests {
	private static final String F = "jobs";
	/** Far-away positions for logic-only tests; the world isn't touched. */
	private static final BlockPos FAR_A = new BlockPos(2_345_671, 64, -2_345_671);
	private static final BlockPos FAR_B = new BlockPos(2_345_673, 64, -2_345_673);

	private JobsSelfTests() {}

	public static void register() {
		SelfTests.register(F, "config-defaults", JobsSelfTests::configDefaults);
		SelfTests.register(F, "money-text-currency", JobsSelfTests::moneyTextCurrency);
		SelfTests.register(F, "config-validation", ctx -> configValidation());
		SelfTests.register(F, "payout-per-job", JobsSelfTests::payoutPerJob);
		SelfTests.register(F, "wrong-or-no-job-pays-nothing", JobsSelfTests::wrongJob);
		SelfTests.register(F, "placed-blocks-pay-nothing", JobsSelfTests::placedBlocks);
		SelfTests.register(F, "immature-crops-pay-nothing", JobsSelfTests::immatureCrops);
		SelfTests.register(F, "bonemeal-crop-pays-nothing", JobsSelfTests::bonemealCrop);
		SelfTests.register(F, "bonemeal-tree-marked", JobsSelfTests::bonemealTree);
		SelfTests.register(F, "level-multiplier", JobsSelfTests::levelMultiplier);
		SelfTests.register(F, "xp-and-level-up", JobsSelfTests::xpLevelUp);
		SelfTests.register(F, "event-multiplier", JobsSelfTests::eventMultiplier);
		SelfTests.register(F, "switch-cooldown", JobsSelfTests::cooldown);
		SelfTests.register(F, "spawner-and-egg-mobs", JobsSelfTests::spawnerMobs);
		SelfTests.register(F, "bred-and-summoned-mobs", JobsSelfTests::bredAndSummonedMobs);
		SelfTests.register(F, "respawned-dragon", JobsSelfTests::respawnedDragon);
		SelfTests.register(F, "summons-not-profitable", JobsSelfTests::summonsNotProfitable);
		SelfTests.register(F, "fishing", JobsSelfTests::fishing);
		SelfTests.register(F, "payout-flush", JobsSelfTests::payoutFlush);
		SelfTests.register(F, "eligibility-and-text", JobsSelfTests::eligibilityAndText);
		SelfTests.register(F, "integrations", ctx -> integrations(ctx));
	}

	private static UUID setup(TestContext ctx, String name, String job) {
		FakePlayer p = ctx.player(name);
		UUID id = p.getUUID();
		ctx.cleanup(() -> JobsService.discard(id));
		EconomyService.ensureAccount(id, name);
		if (job != null) JobsService.adminSet(id, job);
		return id;
	}

	private static long expected(UUID id, String job, double money) {
		return JobsService.milliFor(new Reward("t", money, 0, false), JobsService.levelMultiplier(JobsService.level(id, job)) * Hooks.jobMultiplier(id, job));
	}

	private static BlockState wheat(int age) {
		return Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, age);
	}

	private static void configDefaults(TestContext ctx) {
		for (String id : List.of("miner", "lumberjack", "farmer", "hunter", "fisher", "digger")) isTrue(JobsConfig.job(id) != null, "Job " + id);
		String bundled = ConfigManager.readBundled("smpeconomy_defaults/jobs.json").orElseThrow();
		JobsConfig raw = DataStore.gson().fromJson(bundled, JobsConfig.class);
		JobsConfig.Compiled c = JobsConfig.compile(raw);
		eq(6, c.jobs.size(), "6 jobs in the defaults");
		eq(List.of(), c.warnings, "defaults without unknown IDs");
		eq(30, raw.switchCooldownMinutes, "default cooldown 30 min");
		eq(50, raw.maxLevel, "max level 50");
		isTrue(raw.bredMobFactor <= raw.spawnerMobFactor && raw.summonedMobFactor <= raw.spawnerMobFactor,
			"bred and summoned pay at most like spawner mobs");
		eq(0.0, raw.respawnedDragonFactor, "respawned ender dragons pay nothing by default");
		for (Job j : c.jobs) isTrue(!j.examples(6).isEmpty(), "examples for " + j.id);
		ctx.detail(c.jobs.size() + " jobs, " + c.jobs.stream().mapToInt(Job::size).sum() + " rewards");
	}

	private static void configValidation() {
		String bundled = ConfigManager.readBundled("smpeconomy_defaults/jobs.json").orElseThrow();
		assertThrows(IllegalArgumentException.class, () -> {
			JobsConfig c = DataStore.gson().fromJson(bundled, JobsConfig.class);
			c.jobs.get(0).blocks.values().iterator().next().money = -1;
			JobsConfig.compile(c);
		}, "negative amount");
		assertThrows(IllegalArgumentException.class, () -> {
			JobsConfig c = DataStore.gson().fromJson(bundled, JobsConfig.class);
			c.jobs.get(1).id = c.jobs.get(0).id;
			JobsConfig.compile(c);
		}, "duplicate ID");
		assertThrows(IllegalArgumentException.class, () -> {
			JobsConfig c = DataStore.gson().fromJson(bundled, JobsConfig.class);
			c.jobs.get(0).id = "Miner!";
			JobsConfig.compile(c);
		}, "invalid ID");
		assertThrows(IllegalArgumentException.class, () -> {
			JobsConfig c = DataStore.gson().fromJson(bundled, JobsConfig.class);
			c.spawnerMobFactor = 2;
			JobsConfig.compile(c);
		}, "spawner factor > 1");
		assertThrows(IllegalArgumentException.class, () -> {
			JobsConfig c = DataStore.gson().fromJson(bundled, JobsConfig.class);
			c.bredMobFactor = -0.5;
			JobsConfig.compile(c);
		}, "bred factor < 0");
		assertThrows(IllegalArgumentException.class, () -> {
			JobsConfig c = DataStore.gson().fromJson(bundled, JobsConfig.class);
			c.summonedMobFactor = 1.5;
			JobsConfig.compile(c);
		}, "summon factor > 1");
		assertThrows(IllegalArgumentException.class, () -> {
			JobsConfig c = DataStore.gson().fromJson(bundled, JobsConfig.class);
			c.respawnedDragonFactor = 1.5;
			JobsConfig.compile(c);
		}, "dragon factor > 1");
		assertThrows(IllegalArgumentException.class, () -> {
			JobsConfig c = DataStore.gson().fromJson(bundled, JobsConfig.class);
			c.respawnedDragonFactor = Double.NaN;
			JobsConfig.compile(c);
		}, "dragon factor NaN");
		assertThrows(IllegalArgumentException.class, () -> {
			JobsConfig c = DataStore.gson().fromJson(bundled, JobsConfig.class);
			c.jobs.clear();
			JobsConfig.compile(c);
		}, "no jobs");
		JobsConfig c = DataStore.gson().fromJson(bundled, JobsConfig.class);
		JobsConfig.RewardEntry bogus = new JobsConfig.RewardEntry();
		bogus.money = 1;
		c.jobs.get(0).blocks.put("minecraft:nonexistent", bogus);
		JobsConfig.Compiled compiled = JobsConfig.compile(c);
		isTrue(compiled.warnings.stream().anyMatch(w -> w.contains("nonexistent")), "unknown block gives a warning, not an error");
	}

	private static void payoutPerJob(TestContext ctx) {
		ServerLevel level = ctx.overworld();
		UUID id = setup(ctx, "JobsPay", null);
		long before = EconomyService.balance(id);
		long total = 0;

		JobsService.adminSet(id, "miner");
		long m = JobsService.onBlock(id, level, FAR_A, Blocks.DIAMOND_ORE.defaultBlockState());
		eq(expected(id, "miner", 250), m, "miner: diamond ore");
		total += m;
		eq(expected(id, "miner", 1), JobsService.onBlock(id, level, FAR_A, Blocks.STONE.defaultBlockState()), "miner: stone");
		total += expected(id, "miner", 1);

		JobsService.adminSet(id, "lumberjack");
		long l = JobsService.onBlock(id, level, FAR_A, Blocks.SPRUCE_LOG.defaultBlockState());
		eq(expected(id, "lumberjack", 3), l, "lumberjack: spruce log (via #logs)");
		total += l;

		JobsService.adminSet(id, "farmer");
		long f = JobsService.onBlock(id, level, FAR_A, wheat(7));
		eq(expected(id, "farmer", 4), f, "farmer: ripe wheat");
		total += f;
		long melon = JobsService.onBlock(id, level, FAR_A, Blocks.MELON.defaultBlockState());
		eq(expected(id, "farmer", 6), melon, "farmer: melon");
		total += melon;

		JobsService.adminSet(id, "digger");
		long d = JobsService.onBlock(id, level, FAR_A, Blocks.GRAVEL.defaultBlockState());
		eq(expected(id, "digger", 1.5), d, "digger: gravel");
		total += d;

		JobsService.adminSet(id, "hunter");
		long h = JobsService.onMobKilled(id, EntityType.ZOMBIE, true, Set.of());
		eq(expected(id, "hunter", 30), h, "hunter: zombie");
		total += h;
		long other = JobsService.onMobKilled(id, EntityType.ILLUSIONER, true, Set.of());
		eq(expected(id, "hunter", 20), other, "hunter: unlisted enemy gives mobDefault");
		total += other;
		eq(0L, JobsService.onMobKilled(id, EntityType.COW, false, Set.of()), "hunter: cow (passive) pays nothing");

		JobsService.adminSet(id, "fisher");
		long fi = JobsService.onFish(id, List.of(new ItemStack(Items.COD)));
		eq(expected(id, "fisher", 25), fi, "fisher: cod");
		total += fi;

		JobsService.flush(id);
		// every job switch pays out the old job's collected pay (fractions carry over), so the total matches
		long paid = EconomyService.balance(id) - before;
		eq(total / 1000, paid, "payout = sum of whole cents");
		eq(paid, PlayerDataService.getCounter(id, JobsService.COUNTER_EARNED), "'earned' counter");
		isTrue(PlayerDataService.getCounter(id, JobsService.COUNTER_EARNED + ".miner") >= expected(id, "miner", 250) / 1000,
			"miner pay credited to miner on switch");
		ctx.detail("6 jobs, paid " + JobsText.money(paid * 1000));
	}

	private static void wrongJob(TestContext ctx) {
		ServerLevel level = ctx.overworld();
		UUID id = setup(ctx, "JobsWrong", null);
		eq(0L, JobsService.onBlock(id, level, FAR_A, Blocks.DIAMOND_ORE.defaultBlockState()), "no job");
		eq(0L, JobsService.onMobKilled(id, EntityType.ZOMBIE, true, Set.of()), "no job: mob");
		eq(0L, JobsService.onFish(id, List.of(new ItemStack(Items.COD))), "no job: fish");
		JobsService.adminSet(id, "miner");
		eq(0L, JobsService.onBlock(id, level, FAR_A, Blocks.DIRT.defaultBlockState()), "miner digs dirt");
		eq(0L, JobsService.onBlock(id, level, FAR_A, Blocks.OAK_PLANKS.defaultBlockState()), "miner: planks");
		eq(0L, JobsService.onMobKilled(id, EntityType.ZOMBIE, true, Set.of()), "miner kills zombie");
		eq(0L, JobsService.onFish(id, List.of(new ItemStack(Items.COD))), "miner fishing");
		JobsService.adminSet(id, "digger");
		eq(0L, JobsService.onBlock(id, level, FAR_A, Blocks.DIAMOND_ORE.defaultBlockState()), "digger: diamond ore");
		eq(0L, JobsService.onBlock(id, level, FAR_A, Blocks.SNOW.defaultBlockState()), "digger: snow layer (snow golem) pays nothing");
		PlayerDataService.setJob(id, "nonexistent");
		eq(0L, JobsService.onBlock(id, level, FAR_A, Blocks.STONE.defaultBlockState()), "unknown job in players.json");
		eq(0L, JobsService.pendingMilli(id), "nothing collected");
	}

	private static void placedBlocks(TestContext ctx) {
		ServerLevel level = ctx.overworld();
		UUID id = setup(ctx, "JobsPlaced", "miner");
		ctx.cleanup(() -> {
			PlacedBlockTracker.unmark(level, FAR_A);
			PlacedBlockTracker.unmark(level, FAR_B);
		});
		PlacedBlockTracker.markPlaced(level, FAR_A);
		eq(0L, JobsService.onBlock(id, level, FAR_A, Blocks.DIAMOND_ORE.defaultBlockState()), "placed diamond ore");
		eq(0L, JobsService.onBlock(id, level, FAR_A, Blocks.STONE.defaultBlockState()), "placed or generated stone");
		JobsService.adminSet(id, "digger");
		eq(0L, JobsService.onBlock(id, level, FAR_A, Blocks.SAND.defaultBlockState()), "placed sand");
		JobsService.adminSet(id, "lumberjack");
		eq(0L, JobsService.onBlock(id, level, FAR_A, Blocks.OAK_LOG.defaultBlockState()), "placed log");
		JobsService.adminSet(id, "farmer");
		eq(0L, JobsService.onBlock(id, level, FAR_A, Blocks.PUMPKIN.defaultBlockState()), "placed pumpkin");
		isTrue(JobsService.onBlock(id, level, FAR_A, wheat(7)) > 0, "ripe wheat pays despite marker (seeds are always placed)");
		JobsService.adminSet(id, "miner");
		isTrue(JobsService.onBlock(id, level, FAR_B, Blocks.DIAMOND_ORE.defaultBlockState()) > 0, "natural ore next to it pays");
		PlacedBlockTracker.unmark(level, FAR_A);
		isTrue(JobsService.onBlock(id, level, FAR_A, Blocks.DIAMOND_ORE.defaultBlockState()) > 0, "pays after the marker is removed");
	}

	private static void immatureCrops(TestContext ctx) {
		ServerLevel level = ctx.overworld();
		UUID id = setup(ctx, "JobsCrops", "farmer");
		for (int age = 0; age < 7; age++) {
			eq(0L, JobsService.onBlock(id, level, FAR_A, wheat(age)), "wheat age " + age);
			eq(0L, JobsService.onBlock(id, level, FAR_A, Blocks.CARROTS.defaultBlockState().setValue(CropBlock.AGE, age)), "carrots age " + age);
		}
		isTrue(JobsService.onBlock(id, level, FAR_A, wheat(7)) > 0, "wheat age 7");
		isTrue(JobsService.onBlock(id, level, FAR_A, Blocks.POTATOES.defaultBlockState().setValue(CropBlock.AGE, 7)) > 0, "potatoes ripe");
		for (int age = 0; age < 3; age++) {
			eq(0L, JobsService.onBlock(id, level, FAR_A, Blocks.BEETROOTS.defaultBlockState().setValue(BeetrootBlock.AGE, age)), "beetroot age " + age);
			eq(0L, JobsService.onBlock(id, level, FAR_A, Blocks.NETHER_WART.defaultBlockState().setValue(NetherWartBlock.AGE, age)), "nether wart age " + age);
		}
		isTrue(JobsService.onBlock(id, level, FAR_A, Blocks.BEETROOTS.defaultBlockState().setValue(BeetrootBlock.AGE, 3)) > 0, "beetroot ripe (age 3)");
		isTrue(JobsService.onBlock(id, level, FAR_A, Blocks.NETHER_WART.defaultBlockState().setValue(NetherWartBlock.AGE, 3)) > 0, "nether wart ripe");
		eq(0L, JobsService.onBlock(id, level, FAR_A, Blocks.COCOA.defaultBlockState().setValue(CocoaBlock.AGE, 1)), "cocoa age 1");
		isTrue(JobsService.onBlock(id, level, FAR_A, Blocks.COCOA.defaultBlockState().setValue(CocoaBlock.AGE, 2)) > 0, "cocoa ripe (age 2)");
		isFalse(JobsService.isMature(Blocks.WHEAT.defaultBlockState()), "isMature: fresh wheat");
		isTrue(JobsService.isMature(Blocks.MELON.defaultBlockState()), "isMature: block without age");
	}

	private static void bonemealCrop(TestContext ctx) {
		ServerLevel level = ctx.overworld();
		UUID id = setup(ctx, "JobsBone", "farmer");
		BlockPos spawn = level.getRespawnData().pos();
		BlockPos crop = new BlockPos(spawn.getX() + 11, level.getMaxY(), spawn.getZ() + 11);
		BlockPos soil = crop.below();
		level.getChunk(crop);
		if (!level.getBlockState(crop).isAir() || !level.getBlockState(soil).isAir()) {
			ctx.detail("skipped: sky above spawn not clear");
			return;
		}
		ctx.cleanup(() -> {
			level.setBlock(crop, Blocks.AIR.defaultBlockState(), 3);
			level.setBlock(soil, Blocks.AIR.defaultBlockState(), 3);
			BoostedCrops.remove(level, crop);
			PlacedBlockTracker.unmark(level, crop);
			PlacedBlockTracker.unmark(level, soil);
		});
		level.setBlock(soil, Blocks.FARMLAND.defaultBlockState(), 3);
		level.setBlock(crop, wheat(0), 3);
		isFalse(BoostedCrops.contains(level, crop), "not marked at first");
		for (int i = 0; i < 10 && !JobsService.isMature(level.getBlockState(crop)); i++) {
			isTrue(BoneMealItem.growCrop(new ItemStack(Items.BONE_MEAL), level, crop), "bone meal applied");
		}
		BlockState grown = level.getBlockState(crop);
		isTrue(grown.is(Blocks.WHEAT) && JobsService.isMature(grown), "wheat ripe through bone meal");
		isTrue(BoostedCrops.contains(level, crop), "marked as boosted (BoneMealItem and Level mixins active)");
		eq(0L, JobsService.onBlock(id, level, crop, grown), "boosted wheat pays nothing");

		// breaking: air now, but the marker only goes away at the end of the tick
		level.setBlock(crop, Blocks.AIR.defaultBlockState(), 3);
		isTrue(BoostedCrops.contains(level, crop), "still marked in the break tick");
		BoostedCrops.endTick();
		isFalse(BoostedCrops.contains(level, crop), "removed after the end of the tick");
		level.setBlock(crop, wheat(7), 3);
		isTrue(JobsService.onBlock(id, level, crop, wheat(7)) > 0, "newly grown wheat pays again");

		// growth only changes the age, so the marker stays; right-click harvest removes it explicitly
		BoostedCrops.add(level, crop);
		level.setBlock(crop, wheat(3), 3);
		BoostedCrops.endTick();
		isTrue(BoostedCrops.contains(level, crop), "age change keeps the marker");
		BoostedCrops.remove(level, crop);
		isFalse(BoostedCrops.contains(level, crop), "remove() right away");
	}

	private static void bonemealTree(TestContext ctx) {
		ServerLevel level = ctx.overworld();
		UUID id = setup(ctx, "JobsTree", "lumberjack");
		BlockPos spawn = level.getRespawnData().pos();
		BlockPos sapling = new BlockPos(spawn.getX() + 24, level.getMaxY() - 32, spawn.getZ() + 24);
		int r = 6;
		int height = 24;
		List<BlockPos> box = new ArrayList<>();
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				for (int dy = -1; dy < height; dy++) box.add(sapling.offset(dx, dy, dz));
			}
		}
		level.getChunk(sapling);
		for (BlockPos p : box) {
			level.getChunk(p);
			if (!level.getBlockState(p).isAir()) {
				ctx.detail("skipped: sky above spawn not clear");
				return;
			}
		}
		ctx.cleanup(() -> {
			for (BlockPos p : box) {
				if (!level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
				PlacedBlockTracker.unmark(level, p);
			}
		});
		level.setBlock(sapling.below(), Blocks.DIRT.defaultBlockState(), 3);
		// birch has a fixed narrow shape; 10% of oak saplings become big oaks with leaves outside the box
		level.setBlock(sapling, Blocks.BIRCH_SAPLING.defaultBlockState(), 3);
		for (int i = 0; i < 80 && level.getBlockState(sapling).is(Blocks.BIRCH_SAPLING); i++) {
			BoneMealItem.growCrop(new ItemStack(Items.BONE_MEAL), level, sapling);
		}
		isTrue(level.getBlockState(sapling).is(BlockTags.LOGS), "tree grown");
		int logs = 0;
		for (BlockPos p : box) {
			BlockState s = level.getBlockState(p);
			if (!s.is(BlockTags.LOGS)) continue;
			logs++;
			isTrue(PlacedBlockTracker.isPlayerPlaced(level, p), "log " + p.toShortString() + " marked as placed");
			eq(0L, JobsService.onBlock(id, level, p, s), "log from a bone meal tree pays nothing");
		}
		isTrue(logs > 0, "logs found");
		isFalse(PlacedBlockTracker.isPlayerPlaced(level, sapling.offset(r, height - 1, r)), "surrounding air not marked");
		ctx.detail(logs + " logs marked");
	}

	private static void levelMultiplier(TestContext ctx) {
		ServerLevel level = ctx.overworld();
		eq(1.0, JobsService.levelMultiplier(1), "level 1 = ×1.0");
		isTrue(Math.abs(JobsService.levelMultiplier(2) - 1.02) < 1e-9, "level 2 = ×1.02");
		isTrue(Math.abs(JobsService.levelMultiplier(50) - 1.98) < 1e-9, "level 50 = ×1.98");
		eq(1.0, JobsService.levelMultiplier(0), "level 0 same as 1");
		isTrue(Math.abs(JobsService.levelMultiplier(999) - 1.98) < 1e-9, "above max same as max");
		UUID id = setup(ctx, "JobsLevel", "miner");
		double hook = Hooks.jobMultiplier(id, "miner");
		long at1 = JobsService.onBlock(id, level, FAR_A, Blocks.DIAMOND_ORE.defaultBlockState());
		PlayerDataService.setJobLevel(id, "miner", 10);
		long at10 = JobsService.onBlock(id, level, FAR_A, Blocks.DIAMOND_ORE.defaultBlockState());
		eq((long) Math.floor(250 * 1000 * hook + 1e-6), at1, "level 1: base amount");
		eq((long) Math.floor(250 * 1000 * 1.18 * hook + 1e-6), at10, "level 10: +18%");
		PlayerDataService.setJobLevel(id, "miner", 50);
		eq((long) Math.floor(250 * 1000 * 1.98 * hook + 1e-6), JobsService.onBlock(id, level, FAR_A, Blocks.DIAMOND_ORE.defaultBlockState()), "level 50: +98%");
		eq(1, JobsService.level(id, "digger"), "level separate per job");
	}

	private static void xpLevelUp(TestContext ctx) {
		ServerLevel level = ctx.overworld();
		int max = JobsService.maxLevel();
		for (int l = 1; l < max; l++) isTrue(JobsService.xpForNextLevel(l + 1) >= JobsService.xpForNextLevel(l), "XP curve rises at " + l);
		long need1 = JobsService.xpForNextLevel(1);
		JobsService.XpResult r = JobsService.applyXp(1, 0, need1 - 1);
		eq(1, r.level(), "just below the threshold");
		eq(need1 - 1, r.xp(), "XP kept");
		r = JobsService.applyXp(1, need1 - 1, 1);
		eq(2, r.level(), "exact threshold gives level 2");
		eq(0L, r.xp(), "remainder 0");
		r = JobsService.applyXp(1, 0, need1 + JobsService.xpForNextLevel(2) + 5);
		eq(3, r.level(), "two level-ups at once");
		eq(5L, r.xp(), "remainder carried over");
		eq(2, r.levelsGained(), "levelsGained");
		r = JobsService.applyXp(1, 0, Long.MAX_VALUE / 4);
		eq(max, r.level(), "cap at maxLevel");
		eq(0L, r.xp(), "no XP above the maximum");
		r = JobsService.applyXp(max, 0, 1000);
		eq(max, r.level(), "max stays max");
		eq(0, r.levelsGained(), "no level-up above max");

		// stored progress: diamond ore gives 40 XP
		UUID id = setup(ctx, "JobsXp", "miner");
		PlayerDataService.setJobXp(id, "miner", need1 - 10);
		JobsService.onBlock(id, level, FAR_A, Blocks.DIAMOND_ORE.defaultBlockState());
		eq(2, PlayerDataService.getJobLevel(id, "miner"), "level-up saved");
		eq(30L, PlayerDataService.getJobXp(id, "miner"), "XP remainder saved");
		eq(0L, PlayerDataService.getJobXp(id, "digger"), "other jobs untouched");
	}

	/** Reset level and XP so earlier XP can't cause a level-up between two measurements. */
	private static void resetLevel(UUID id, String job) {
		PlayerDataService.setJobLevel(id, job, 1);
		PlayerDataService.setJobXp(id, job, 0);
	}

	private static void eventMultiplier(TestContext ctx) {
		ServerLevel level = ctx.overworld();
		UUID id = setup(ctx, "JobsEvent", "miner");
		resetLevel(id, "miner");
		long base = JobsService.onBlock(id, level, FAR_A, Blocks.DIAMOND_ORE.defaultBlockState());
		Hooks.Registration reg = Hooks.addJobMultiplier("jobs.selftest", "selftest gold rush", (p, job) -> p.equals(id) && "miner".equals(job) ? 2.0 : 1.0);
		ctx.cleanup(reg::remove);
		resetLevel(id, "miner");
		long doubled = JobsService.onBlock(id, level, FAR_A, Blocks.DIAMOND_ORE.defaultBlockState());
		isTrue(Math.abs(doubled - 2 * base) <= 1, "gold rush ×2 on miner (" + base + " → " + doubled + ")");
		isTrue(Hooks.jobParts(id, "miner").stream().anyMatch(p -> p.id().equals("jobs.selftest")), "bonus appears in the parts (GUI)");
		JobsService.adminSet(id, "digger");
		long dirt = JobsService.onBlock(id, level, FAR_A, Blocks.DIRT.defaultBlockState());
		eq(expected(id, "digger", 1), dirt, "other job untouched");
		reg.remove();
		JobsService.adminSet(id, "miner");
		resetLevel(id, "miner");
		eq(base, JobsService.onBlock(id, level, FAR_A, Blocks.DIAMOND_ORE.defaultBlockState()), "normal again after the end");
	}

	private static void cooldown(TestContext ctx) {
		UUID id = setup(ctx, "JobsCooldown", null);
		long cd = JobsConfig.raw().switchCooldownMinutes * 60_000L;
		eq(0L, JobsService.cooldownRemaining(id), "new player has no cooldown");
		eq(JobsService.JoinStatus.OK, JobsService.join(id, "miner", false).status(), "first join");
		eq("miner", PlayerDataService.getJob(id), "job set");
		JobsService.JoinResult r = JobsService.join(id, "digger", false);
		eq(JobsService.JoinStatus.COOLDOWN, r.status(), "instant switch blocked");
		isTrue(r.remainingMs() > cd - 5_000 && r.remainingMs() <= cd, "time left ≈ cooldown");
		eq("miner", PlayerDataService.getJob(id), "job unchanged");
		eq(JobsService.JoinStatus.ALREADY, JobsService.join(id, "miner", false).status(), "same job");
		eq(JobsService.JoinStatus.UNKNOWN, JobsService.join(id, "nonexistent", false).status(), "unknown job");
		isTrue(JobsService.leave(id), "leave");
		isFalse(JobsService.leave(id), "leaving twice");
		eq(JobsService.JoinStatus.COOLDOWN, JobsService.join(id, "miner", false).status(), "cooldown runs after leaving");
		PlayerDataService.get(id).jobChangedAt = System.currentTimeMillis() - cd - 1_000;
		eq(JobsService.JoinStatus.OK, JobsService.join(id, "digger", false).status(), "allowed after expiry");
		eq(JobsService.JoinStatus.OK, JobsService.join(id, "farmer", true).status(), "admin bypasses the cooldown");
		PlayerDataService.get(id).jobChangedAt = System.currentTimeMillis() + 10 * cd;
		isTrue(JobsService.cooldownRemaining(id) <= cd, "timestamp in the future gives at most one cooldown");
		JobsService.resetCooldown(id);
		eq(0L, JobsService.cooldownRemaining(id), "Reset");
		PlayerDataService.setJobLevel(id, "farmer", 7);
		JobsService.join(id, "miner", false);
		eq(7, JobsService.level(id, "farmer"), "level survives a switch");
	}

	private static void spawnerMobs(TestContext ctx) {
		ServerLevel level = ctx.overworld();
		UUID id = setup(ctx, "JobsSpawner", "hunter");
		JobsConfig cfg = JobsConfig.raw();
		long normal = JobsService.onMobKilled(id, EntityType.ZOMBIE, true, Set.of());
		long spawner = JobsService.onMobKilled(id, EntityType.ZOMBIE, true, Set.of(JobsEvents.TAG_SPAWNER));
		long egg = JobsService.onMobKilled(id, EntityType.ZOMBIE, true, Set.of(JobsEvents.TAG_SPAWN_EGG));
		eq(expected(id, "hunter", 30 * cfg.spawnerMobFactor), spawner, "spawner mob × " + cfg.spawnerMobFactor);
		isTrue(spawner < normal || cfg.spawnerMobFactor >= 1, "spawner pays less");
		eq(expected(id, "hunter", 30 * cfg.spawnEggMobFactor), egg, "spawn egg mob × " + cfg.spawnEggMobFactor);
		long both = JobsService.onMobKilled(id, EntityType.ZOMBIE, true, Set.of(JobsEvents.TAG_SPAWNER, JobsEvents.TAG_SPAWN_EGG));
		eq(expected(id, "hunter", 30 * Math.min(cfg.spawnerMobFactor, cfg.spawnEggMobFactor)), both, "both tags: lowest factor");

		// real paths (EntityType mixin): BaseSpawner and TrialSpawner load through loadEntityRecursive(ValueInput, ...,
		// SPAWNER, ...)
		// on all versions, plus the NBT path (from 26.2 with EntitySpawnRequest, which the spawner also uses directly
		// for its
		// display mob) and the spawn egg via create(..., SPAWN_ITEM_USE, ...)
		CompoundTag tag = new CompoundTag();
		tag.putString("id", "minecraft:zombie");
		Entity fromSpawner = EntityType.loadEntityRecursive(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag), level,
			EntitySpawnReason.SPAWNER, EntityProcessor.NOP);
		Entity fromTrial = EntityType.loadEntityRecursive(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag), level,
			EntitySpawnReason.TRIAL_SPAWNER, EntityProcessor.NOP);
		Entity fromTag = Mc.loadEntityRecursive(tag, level, EntitySpawnReason.SPAWNER, EntityProcessor.NOP);
		Entity fromLoad = Mc.loadEntityRecursive(tag, level, EntitySpawnReason.LOAD, EntityProcessor.NOP);
		// skeleton, not zombie: Zombie#finalizeSpawn can create a real chicken as a jockey mount
		Entity fromEgg = EntityType.SKELETON.create(level, null, level.getRespawnData().pos(), EntitySpawnReason.SPAWN_ITEM_USE, false, false);
		Entity fromCommand = EntityType.SKELETON.create(level, null, level.getRespawnData().pos(), EntitySpawnReason.COMMAND, false, false);
		try {
			isTrue(fromSpawner != null && fromSpawner.getTags().contains(JobsEvents.TAG_SPAWNER), "spawner mob tagged (EntityType mixin active)");
			isTrue(fromTrial != null && fromTrial.getTags().contains(JobsEvents.TAG_SPAWNER), "trial spawner mob tagged");
			isTrue(fromTag != null && fromTag.getTags().contains(JobsEvents.TAG_SPAWNER), "spawner mob tagged via the NBT path");
			isTrue(fromLoad != null && fromLoad.getTags().isEmpty(), "mob loaded from NBT (LOAD) untagged");
			isTrue(fromEgg != null && fromEgg.getTags().contains(JobsEvents.TAG_SPAWN_EGG), "spawn egg mob tagged");
			isTrue(fromCommand != null && fromCommand.getTags().isEmpty(), "/summon mob untagged");
		} finally {
			for (Entity e : new Entity[] {fromSpawner, fromTrial, fromTag, fromLoad, fromEgg, fromCommand}) {
				if (e != null) e.discard();
			}
		}
		eq(1.0, JobsService.mobFactor(Set.of("anything")), "foreign tags don't matter");
	}

	/**
	 * Bred (BREEDING), summoned (MOB_SUMMONED) and reinforcement mobs through the real vanilla calls: shulker splitting
	 * and hoglin offspring ({@code EntityType#create(Level, BREEDING)}), vex ({@code MOB_SUMMONED}), villager offspring
	 * (constructor, core VillagerOriginMixin), zombie reinforcement (inherits the caller's origin).
	 */
	private static void bredAndSummonedMobs(TestContext ctx) {
		ServerLevel level = ctx.overworld();
		UUID id = setup(ctx, "JobsBred", "hunter");
		resetLevel(id, "hunter");
		JobsConfig cfg = JobsConfig.raw();
		List<Entity> created = new ArrayList<>();
		ctx.cleanup(() -> created.forEach(Entity::discard));

		Entity shulker = keep(created, EntityType.SHULKER.create(level, EntitySpawnReason.BREEDING));
		Entity hoglin = keep(created, EntityType.HOGLIN.create(level, EntitySpawnReason.BREEDING));
		Entity vex = keep(created, EntityType.VEX.create(level, EntitySpawnReason.MOB_SUMMONED));
		Hoglin wild = keep(created, EntityType.HOGLIN.create(level, EntitySpawnReason.NATURAL));
		Entity summoned = keep(created, EntityType.ZOMBIE.create(level, EntitySpawnReason.COMMAND));
		isTrue(shulker.getTags().contains(JobsEvents.TAG_BRED), "shulker split (BREEDING) tagged, EntityType mixin active");
		isTrue(hoglin.getTags().contains(JobsEvents.TAG_BRED), "hoglin (BREEDING) tagged");
		isTrue(vex.getTags().contains(JobsEvents.TAG_SUMMONED), "vex (MOB_SUMMONED) tagged");
		isTrue(wild.getTags().isEmpty(), "natural hoglin untagged");
		isTrue(summoned.getTags().isEmpty(), "/summon mob untagged");

		AgeableMob piglet = keep(created, wild.getBreedOffspring(level, wild));
		isTrue(piglet.getTags().contains(JobsEvents.TAG_BRED), "hoglin offspring tagged via getBreedOffspring");
		Villager parent = keep(created, EntityType.VILLAGER.create(level, EntitySpawnReason.COMMAND));
		Villager baby = keep(created, parent.getBreedOffspring(level, parent));
		isTrue(baby.getTags().contains(JobsEvents.TAG_BRED), "villager offspring tagged (core VillagerOriginMixin active)");
		isFalse(parent.getTags().contains(JobsEvents.TAG_BRED), "parents stay untagged");

		eq(expected(id, "hunter", 100 * cfg.bredMobFactor), JobsService.onMobKilled(id, EntityType.SHULKER, true, shulker.getTags()),
			"bred shulker × bredMobFactor " + cfg.bredMobFactor);
		eq(expected(id, "hunter", 40 * cfg.bredMobFactor), JobsService.onMobKilled(id, EntityType.HOGLIN, true, piglet.getTags()),
			"bred hoglin × bredMobFactor");
		eq(expected(id, "hunter", 10 * cfg.summonedMobFactor), JobsService.onMobKilled(id, EntityType.VEX, true, vex.getTags()),
			"vex × summonedMobFactor " + cfg.summonedMobFactor);
		// conversion copies all tags (ConversionType#convertCommon): zombie villager from a bred villager
		eq(expected(id, "hunter", 30 * cfg.bredMobFactor), JobsService.onMobKilled(id, EntityType.ZOMBIE_VILLAGER, true, baby.getTags()),
			"zombie villager from breeding × bredMobFactor");
		eq(expected(id, "hunter", 40), JobsService.onMobKilled(id, EntityType.HOGLIN, true, wild.getTags()), "natural hoglin in full");
		eq(cfg.bredMobFactor, JobsService.mobFactor(Set.of(JobsEvents.TAG_BRED)), "mobFactor bred");
		eq(cfg.summonedMobFactor, JobsService.mobFactor(Set.of(JobsEvents.TAG_SUMMONED)), "mobFactor summoned");
		eq(Math.min(cfg.spawnerMobFactor, cfg.bredMobFactor), JobsService.mobFactor(Set.of(JobsEvents.TAG_SPAWNER, JobsEvents.TAG_BRED)),
			"several origin tags: lowest factor");

		// the tag survives save and load (chunk unload, restart)
		TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
		isTrue(shulker.save(out), "shulker saved");
		Entity reloaded = keep(created, Mc.loadEntityRecursive(out.buildResult(), level, EntitySpawnReason.LOAD, EntityProcessor.NOP));
		isTrue(reloaded.getTags().contains(JobsEvents.TAG_BRED), "tag still there after save and load");

		// zombie reinforcement (REINFORCEMENT) inherits only the caller's origin tags (core ZombieOriginMixin)
		Entity spawnerZombie = keep(created, EntityType.ZOMBIE.create(level, EntitySpawnReason.NATURAL));
		spawnerZombie.addTag(JobsEvents.TAG_SPAWNER);
		spawnerZombie.addTag("other_tag");
		Entity reinforcement = keep(created, EntityType.ZOMBIE.create(level, EntitySpawnReason.REINFORCEMENT));
		isTrue(reinforcement.getTags().isEmpty(), "reinforcement untagged before inheriting");
		MobOrigin.inherit(spawnerZombie, reinforcement);
		eq(Set.of(JobsEvents.TAG_SPAWNER), Set.copyOf(reinforcement.getTags()), "reinforcement inherits only the spawner tag");
		eq(expected(id, "hunter", 30 * cfg.spawnerMobFactor), JobsService.onMobKilled(id, EntityType.ZOMBIE, true, reinforcement.getTags()),
			"reinforcement of a spawner zombie × spawnerMobFactor");
		Entity naturalCaller = keep(created, EntityType.ZOMBIE.create(level, EntitySpawnReason.NATURAL));
		Entity naturalReinforcement = keep(created, EntityType.ZOMBIE.create(level, EntitySpawnReason.REINFORCEMENT));
		MobOrigin.inherit(naturalCaller, naturalReinforcement);
		isTrue(naturalReinforcement.getTags().isEmpty(), "reinforcement of a natural zombie stays untagged");
		MobOrigin.inherit(null, naturalReinforcement);
		MobOrigin.inherit(spawnerZombie, null);
		ctx.detail("bred ×" + cfg.bredMobFactor + ", summoned ×" + cfg.summonedMobFactor + ", Spawner ×" + cfg.spawnerMobFactor);
	}

	/**
	 * A respawned ender dragon ({@code EndDragonFight#createNewDragon} with {@code previouslyKilled}, spawn reason
	 * EVENT) must be tagged and pay less than its 4 end crystals cost in the shop: pay × {@code respawnedDragonFactor},
	 * capped separately below the crystals' minimum shop cost.
	 */
	private static void respawnedDragon(TestContext ctx) {
		ServerLevel level = ctx.overworld();
		UUID id = setup(ctx, "JobsDragon", "hunter");
		resetLevel(id, "hunter");
		JobsConfig cfg = JobsConfig.raw();
		Job hunter = JobsConfig.job("hunter");
		Reward dragon = hunter.mob(EntityType.ENDER_DRAGON, true);
		isTrue(dragon != null && dragon.money() > 0, "ender dragon has hunter pay");
		List<Entity> created = new ArrayList<>();
		ctx.cleanup(() -> created.forEach(Entity::discard));

		// vanilla creates every dragon through create(level, EVENT), so the tag comes from the EndDragonFight mixin
		// logic
		Entity first = keep(created, EntityType.ENDER_DRAGON.create(level, EntitySpawnReason.EVENT));
		isTrue(first.getTags().isEmpty(), "dragon (EVENT) not tagged by the EntityType mixin");
		MobOrigin.onDragonCreated(first, false);
		isTrue(first.getTags().isEmpty(), "world's first dragon (previouslyKilled = false) stays untagged");
		Entity respawned = keep(created, EntityType.ENDER_DRAGON.create(level, EntitySpawnReason.EVENT));
		MobOrigin.onDragonCreated(respawned, true);
		eq(Set.of(JobsEvents.TAG_RESPAWNED), Set.copyOf(respawned.getTags()), "respawned dragon tagged");
		MobOrigin.onDragonCreated(null, true);
		isTrue(Arrays.stream(EndDragonFight.class.getDeclaredMethods()).anyMatch(m -> m.getName().contains("tagRespawnedDragon")),
			"EndDragonFight mixin active (createNewDragon)");
		TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
		isTrue(respawned.save(out), "dragon saved");
		Entity reloaded = keep(created, Mc.loadEntityRecursive(out.buildResult(), level, EntitySpawnReason.LOAD, EntityProcessor.NOP));
		isTrue(reloaded.getTags().contains(JobsEvents.TAG_RESPAWNED), "tag survives save and load");

		long cap = JobsService.maxMilliFor(Set.of(JobsEvents.TAG_RESPAWNED));
		eq(SummonCosts.respawnedDragonCapMilli(), cap, "cap for respawned dragons");
		eq(Long.MAX_VALUE, JobsService.maxMilliFor(Set.of()), "no cap without a tag");
		eq(Long.MAX_VALUE, JobsService.maxMilliFor(null), "no cap without tags");
		eq(cfg.respawnedDragonFactor, JobsService.mobFactor(Set.of(JobsEvents.TAG_RESPAWNED)), "mobFactor respawned");
		long expFull = expected(id, "hunter", dragon.money());
		eq(expFull, JobsService.onMobKilled(id, EntityType.ENDER_DRAGON, true, first.getTags()), "first dragon pays in full");
		resetLevel(id, "hunter");
		long xpBefore = PlayerDataService.getJobXp(id, "hunter");
		long expRespawned = Math.min(expected(id, "hunter", dragon.money() * cfg.respawnedDragonFactor), cap);
		eq(expRespawned, JobsService.onMobKilled(id, EntityType.ENDER_DRAGON, true, respawned.getTags()),
			"respawned dragon × respawnedDragonFactor " + cfg.respawnedDragonFactor + ", capped");
		if (cfg.respawnedDragonFactor == 0) eq(xpBefore, PlayerDataService.getJobXp(id, "hunter"), "factor 0: no job XP either");

		// the cap holds even with factor 1, max level and an event hook at Hooks.MAX_FACTOR
		PlayerDataService.setJobLevel(id, "hunter", JobsService.maxLevel());
		Hooks.Registration reg = Hooks.addJobMultiplier("jobs.selftest.dragon", "selftest cap", (p, job) -> p.equals(id) ? Hooks.MAX_FACTOR : 1.0);
		ctx.cleanup(reg::remove);
		long worst = JobsService.reward(id, hunter, dragon, 1.0, cap);
		reg.remove();
		long cost = SummonCosts.dragonRespawnMinCost();
		isTrue(worst <= cap, "factor 1 + max level + hook ×" + Hooks.MAX_FACTOR + " stays at the cap (" + worst + " ≤ " + cap + ")");
		isTrue(cost > 0 ? worst < Math.multiplyExact(cost, 1000L) : worst == 0,
			"pay " + JobsText.money(worst) + " < minimum crystal cost " + JobsText.money(cost * 1000));

		// cost through PriceGuard: pivot + 1 per ingredient (glass doesn't count); with shop defaults 4 × (tear + pearl
		// + half a rod)
		long tear = SummonCosts.minSystemPrice(Items.GHAST_TEAR);
		long pearl = SummonCosts.minSystemPrice(Items.ENDER_PEARL);
		long rod = SummonCosts.minSystemPrice(Items.BLAZE_ROD);
		boolean onlyRaw = SummonCosts.minSystemPrice(Items.END_CRYSTAL) < 0 && SummonCosts.minSystemPrice(Items.ENDER_EYE) < 0
			&& SummonCosts.minSystemPrice(Items.BLAZE_POWDER) < 0;
		if (onlyRaw && tear > 0 && pearl > 0 && rod > 0) {
			eq(tear + pearl + rod / 2, SummonCosts.endCrystalMinCost(), "crystal = tear + pearl + ½ rod (each pivot + 1)");
			eq(tear, PriceGuard.pivotPrice(Items.GHAST_TEAR) + 1, "minimum price = pivot + 1");
		}
		eq(SummonCosts.endCrystalMinCost() * SummonCosts.RESPAWN_CRYSTALS, cost, "4 crystals per respawn");
		ctx.detail("crystal ≥ " + JobsText.money(SummonCosts.endCrystalMinCost() * 1000) + ", respawn ≥ " + JobsText.money(cost * 1000)
			+ ", cap " + JobsText.money(cap) + ", factor " + cfg.respawnedDragonFactor);
	}

	/** A way to create a paid mob from system items alone; {@code minCost} in cents, -1 if not fully in the shop. */
	private record Summon(String label, EntityType<?> type, Set<String> tags, long minCost) {}

	/** Sum of minimum prices × amount; -1 once any part isn't available from the system. */
	private static long costOf(long... priceAndCount) {
		long sum = 0;
		for (int i = 0; i + 1 < priceAndCount.length; i += 2) {
			if (priceAndCount[i] < 0) return -1;
			sum = Math.addExact(sum, Math.multiplyExact(priceAndCount[i], priceAndCount[i + 1]));
		}
		return sum;
	}

	/** Cheaper of two alternatives (-1 = unavailable). */
	private static long cheapest(long a, long b) {
		if (a < 0) return b;
		if (b < 0) return a;
		return Math.min(a, b);
	}

	/**
	 * No buy-from-system, create-mob, get-paid loop: every mob in the table that can be made entirely from shop items
	 * pays less than the ingredients' minimum system cost (pivot + 1), even at max level, origin factor 1 and an event
	 * hook at {@code Hooks.MAX_FACTOR}. Placed shop blocks pay in no job either. Crops and saplings are exempt: growth
	 * takes time, bone meal pays nothing, and harvests return seeds.
	 */
	private static void summonsNotProfitable(TestContext ctx) {
		ServerLevel level = ctx.overworld();
		UUID id = setup(ctx, "JobsSummon", "hunter");
		PlayerDataService.setJobLevel(id, "hunter", JobsService.maxLevel());
		PlayerDataService.setJobXp(id, "hunter", 0);
		Hooks.Registration reg = Hooks.addJobMultiplier("jobs.selftest.summon", "selftest cap", (p, job) -> p.equals(id) ? Hooks.MAX_FACTOR : 1.0);
		ctx.cleanup(reg::remove);
		Job hunter = JobsConfig.job("hunter");
		isTrue(hunter != null, "hunter job exists");

		long soul = cheapest(SummonCosts.minSystemPrice(Items.SOUL_SAND), SummonCosts.minSystemPrice(Items.SOUL_SOIL));
		List<Summon> summons = List.of(
			new Summon("ender dragon (respawn, 4 end crystals)", EntityType.ENDER_DRAGON, Set.of(JobsEvents.TAG_RESPAWNED),
				SummonCosts.dragonRespawnMinCost()),
			new Summon("wither (4 soul sand or soil + 3 wither skeleton skulls)", EntityType.WITHER, Set.of(),
				costOf(soul, 4, SummonCosts.minSystemPrice(Items.WITHER_SKELETON_SKULL), 3)),
			new Summon("endermite (ender pearl)", EntityType.ENDERMITE, Set.of(), costOf(SummonCosts.minSystemPrice(Items.ENDER_PEARL), 1)));
		int checked = 0;
		List<String> skipped = new ArrayList<>();
		for (Summon s : summons) {
			Reward r = hunter.mob(s.type(), true);
			if (r == null) {
				skipped.add(s.label() + ": no pay");
				continue;
			}
			if (s.minCost() < 0) {
				skipped.add(s.label() + ": not fully in the shop");
				continue;
			}
			long actual = JobsService.onMobKilled(id, s.type(), true, s.tags());
			long worst = JobsService.reward(id, hunter, r, 1.0, JobsService.maxMilliFor(s.tags()));
			long costMilli = Math.multiplyExact(s.minCost(), 1000L);
			isTrue(actual <= worst, s.label() + ": config factor pays no more than factor 1");
			isTrue(worst == 0 || worst < costMilli,
				s.label() + ": pay " + JobsText.money(worst) + " (max level, hook ×" + Hooks.MAX_FACTOR + ") < minimum cost " + JobsText.money(costMilli));
			checked++;
		}
		reg.remove();

		// placed shop blocks: no job pays (PlacedBlockTracker), except crops with a ripeness check
		PlacedBlockTracker.markPlaced(level, FAR_A);
		ctx.cleanup(() -> PlacedBlockTracker.unmark(level, FAR_A));
		int blocks = 0;
		for (Job j : JobsConfig.jobs()) {
			JobsService.adminSet(id, j.id);
			for (Item item : BasePrices.shopBuyPrices().keySet()) {
				if (!(item instanceof BlockItem bi)) continue;
				BlockState state = bi.getBlock().defaultBlockState();
				Reward r = j.block(state);
				if (r == null || Job.needsMaturity(r, state)) continue;
				eq(0L, JobsService.onBlock(id, level, FAR_A, state), j.id + ": placed shop block " + item);
				blocks++;
			}
		}
		isTrue(blocks > 0, "shop blocks with job pay found");
		ctx.detail(checked + " summons checked, " + blocks + " shop blocks" + (skipped.isEmpty() ? "" : "; skipped: " + String.join(", ", skipped)));
	}

	private static <T extends Entity> T keep(List<Entity> created, T entity) {
		if (entity == null) throw new AssertionError("could not create entity");
		created.add(entity);
		return entity;
	}

	private static void fishing(TestContext ctx) {
		UUID id = setup(ctx, "JobsFish", "fisher");
		eq(expected(id, "fisher", 25), JobsService.onFish(id, List.of(new ItemStack(Items.COD))), "cod");
		eq(expected(id, "fisher", 100), JobsService.onFish(id, List.of(new ItemStack(Items.NAME_TAG))), "treasure: name tag");
		eq(expected(id, "fisher", 5), JobsService.onFish(id, List.of(new ItemStack(Items.STICK))), "junk gives fishDefault");
		eq(expected(id, "fisher", 25) + expected(id, "fisher", 30), JobsService.onFish(id, List.of(new ItemStack(Items.COD), new ItemStack(Items.SALMON))), "two items");
		eq(0L, JobsService.onFish(id, List.of()), "empty catch");
		eq(0L, JobsService.onFish(id, List.of(ItemStack.EMPTY)), "empty stack");
		JobsService.adminSet(id, "digger");
		eq(0L, JobsService.onFish(id, List.of(new ItemStack(Items.COD))), "digger fishing gives nothing");
	}

	private static void payoutFlush(TestContext ctx) {
		ServerLevel level = ctx.overworld();
		UUID id = setup(ctx, "JobsFlush", "miner");
		eq(0L, JobsService.flush(id), "nothing collected, nothing paid");
		double hook = Hooks.jobMultiplier(id, "miner");
		long before = EconomyService.balance(id);
		long sum = 0;
		for (int i = 0; i < 3; i++) sum += JobsService.onBlock(id, level, FAR_A, Blocks.NETHERRACK.defaultBlockState());
		eq((long) Math.floor(500 * hook + 1e-6) * 3, sum, "3 × 0.5 cents netherrack");
		eq(sum, JobsService.pendingMilli(id), "collected in thousandths of a cent");
		long paid = JobsService.flush(id);
		eq(sum / 1000, paid, "whole cents paid");
		eq(before + paid, EconomyService.balance(id), "balance");
		eq(0L, JobsService.pendingMilli(id), "fractions of an offline player discarded");
		JobsService.onBlock(id, level, FAR_A, Blocks.DIAMOND_ORE.defaultBlockState());
		isTrue(JobsService.pendingMilli(id) > 0, "collected something again");
		JobsService.discard(id);
		eq(0L, JobsService.pendingMilli(id), "discard drops it (selftest cleanup)");
		eq(0L, JobsService.flush(id), "nothing left to pay after discard");
		isTrue(JobsService.milliFor(new Reward("x", 1, 0, false), Double.NaN) == 0, "NaN factor gives 0");
		isTrue(JobsService.milliFor(new Reward("x", 1, 0, false), -2) == 0, "negative factor gives 0");
		eq(JobsService.MAX_MILLI_PER_ACTION, JobsService.milliFor(new Reward("x", 100_000, 0, false), 1e12), "overflow guard");
		eq(Long.MAX_VALUE, Money.saturatedAdd(Long.MAX_VALUE - 1, 5), "saturatedAdd");
	}

	private static void eligibilityAndText(TestContext ctx) {
		FakePlayer fake = ctx.player("JobsFake");
		isFalse(JobsEvents.eligible(fake), "FakePlayer gets no job pay");
		eq("$0.005", JobsText.money(500), "fraction display");
		eq("$2.50", JobsText.money(250_000), "whole cents");
		eq("$0.01", JobsText.money(1_000), "1 cent");
		eq("+18%", JobsText.percent(1.18), "percent");
		eq("+0%", JobsText.percent(1.0), "percent 0");
		eq("lumberjack", JobsConfig.find("Lumberjack").id, "English name");
		eq("digger", JobsConfig.find("DIGGER").id, "case doesn't matter");
		eq("hunter", JobsConfig.find("  HUNTER ").id, "whitespace and case don't matter");
		eq("fisher", JobsConfig.find("fisher").id, "ID");
		isTrue(JobsConfig.find("baker") == null, "unknown");
		JobsConfig.Compiled c = JobsConfig.get();
		eq(1, c.growthKind(wheat(3)), "wheat counts as boosted");
		eq(2, c.growthKind(Blocks.OAK_LOG.defaultBlockState()), "log counts as placed");
		eq(2, c.growthKind(Blocks.MELON.defaultBlockState()), "melon counts as placed");
		eq(0, c.growthKind(Blocks.OAK_SAPLING.defaultBlockState()), "sapling doesn't matter");
		eq(0, c.growthKind(Blocks.SHORT_GRASS.defaultBlockState()), "grass doesn't matter");
	}

	private static JsonObject jobJson(JsonObject root, String id) {
		for (JsonElement el : root.getAsJsonArray("jobs")) {
			if (id.equals(el.getAsJsonObject().get("id").getAsString())) return el.getAsJsonObject();
		}
		throw new AssertionError("Job " + id + " missing");
	}

	private static void integrations(TestContext ctx) {
		boolean rch = RightClickHarvestCompat.isModLoaded();
		eq(rch, RightClickHarvestCompat.isActive(), "RightClickHarvest integration active when the mod is loaded");
		String vein = VeinminerCompat.describe();
		eq("(Lnet/a;I)V", VeinminerCompat.targetDescriptor("(Lnet/a;ILorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", org.objectweb.asm.Type.VOID_TYPE), "target signature from the handler");
		ctx.detail("RightClickHarvest=" + (rch ? "active" : "missing") + ", Veinminer=" + (vein != null ? vein : "missing"));
	}

	private static void moneyTextCurrency(TestContext ctx) {
		String sym = dev.smpeconomy.core.Money.symbol();
		boolean after = dev.smpeconomy.core.Money.symbolAfter();
		ctx.cleanup(() -> dev.smpeconomy.core.Money.setCurrency(sym, after));
		dev.smpeconomy.core.Money.setCurrency("€", true);
		eq("0.005€", JobsText.money(500), "half a cent");
		eq("0.25€", JobsText.money(25_000), "whole cents");
		dev.smpeconomy.core.Money.setCurrency("$", false);
		eq("$0.005", JobsText.money(500), "default $");
	}
}
