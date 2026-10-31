package dev.smpeconomy.features.missions;

import static dev.smpeconomy.core.test.Assert.check;
import static dev.smpeconomy.core.test.Assert.eq;
import static dev.smpeconomy.core.test.Assert.isFalse;
import static dev.smpeconomy.core.test.Assert.isTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import dev.smpeconomy.core.BasePrices;
import dev.smpeconomy.core.CrateKeys;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.MobOrigin;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.PriceGuard;
import dev.smpeconomy.core.PriceModifiers;
import dev.smpeconomy.core.Services;
import dev.smpeconomy.core.TraderGoods;
import dev.smpeconomy.core.test.SelfTests;
import dev.smpeconomy.core.test.TestContext;
import dev.smpeconomy.features.missions.EliteConfig.Elite;
import dev.smpeconomy.features.missions.EliteService.DeliverStatus;
import dev.smpeconomy.features.missions.EliteService.Today;
import dev.smpeconomy.features.missions.MissionStore.EliteDay;
import dev.smpeconomy.features.missions.MissionStore.EliteProgress;
import dev.smpeconomy.features.missions.MissionStore.PlayerState;

/**
 * Selftests for the Daily Elite (named {@code elite-*}). Tests replacing today's Daily Elite restore the pinned day in
 * cleanup, so real players see the same mission as before.
 */
final class EliteSelfTests {
	private static final String F = "missions";
	private static final String MOD_SOURCE = "missions.elite.selftest";
	private static final long DAY_MS = 86_400_000L;

	private EliteSelfTests() {}

	static void register() {
		SelfTests.register(F, "elite-pool-defaults", EliteSelfTests::poolDefaults);
		SelfTests.register(F, "elite-config-validation", ctx -> configValidation());
		SelfTests.register(F, "elite-determinism", EliteSelfTests::determinism);
		SelfTests.register(F, "elite-once-per-day-reset", EliteSelfTests::oncePerDay);
		SelfTests.register(F, "elite-anti-farm", EliteSelfTests::antiFarm);
		SelfTests.register(F, "elite-raid", EliteSelfTests::raid);
		SelfTests.register(F, "elite-delivery", EliteSelfTests::delivery);
		SelfTests.register(F, "elite-delivery-changed", EliteSelfTests::deliveryChanged);
		SelfTests.register(F, "elite-exactly-once-payout", EliteSelfTests::exactlyOnce);
		SelfTests.register(F, "elite-persistence", ctx -> persistence());
	}

	/** Saves the pinned day and switch, restores them in cleanup; turns the Daily Elite on for the test. */
	private static void preserve(TestContext ctx) {
		EliteDay g = MissionStore.eliteDay();
		String json = DataStore.gson().toJson(g);
		EliteConfig.Root root = EliteConfig.get().root();
		boolean enabled = root.enabled;
		root.enabled = true;
		ctx.cleanup(() -> {
			EliteDay back = DataStore.gson().fromJson(json, EliteDay.class);
			EliteDay cur = MissionStore.eliteDay();
			cur.day = back.day;
			cur.def = back.def;
			cur.override = back.override;
			cur.compiled = null;
			cur.compileFailed = false;
			MissionStore.markDirty();
			EliteConfig.get().root().enabled = enabled;
		});
	}

	private static EliteConfig.EliteDef def(String id, EliteConfig.Type type, int amount, long reward) {
		EliteConfig.EliteDef d = new EliteConfig.EliteDef();
		d.id = id;
		d.type = type.id;
		d.name = "Test " + id;
		d.description = "selftest Daily Elite " + id;
		d.amount = amount;
		d.rewardCents = reward;
		return d;
	}

	private static Elite kill(String id, int amount, long reward, String... entities) {
		EliteConfig.EliteDef d = def(id, EliteConfig.Type.KILL, amount, reward);
		d.entities = List.of(entities);
		return EliteConfig.compileDef(d);
	}

	private static Elite deliver(String id, Item item, int amount, long reward) {
		EliteConfig.EliteDef d = def(id, EliteConfig.Type.DELIVER, amount, reward);
		d.item = Items.id(item);
		return EliteConfig.compileDef(d);
	}

	private static <T extends LivingEntity> T mob(List<Entity> created, T entity) {
		check(entity != null, "mob created");
		created.add(entity);
		return entity;
	}

	private static long epic(UUID id) {
		return CrateKeys.count(id, CrateKeys.EPIC);
	}

	private static void poolDefaults(TestContext ctx) {
		EliteConfig.Root root = EliteConfig.CONFIG.defaults();
		check(root.missions.size() >= 8, "at least 8 Daily Elite missions in the default pool (" + root.missions.size() + ")");
		eq(CrateKeys.EPIC, root.keyTier, "epic key");
		eq(1, root.keys, "1 key");
		isTrue(root.enabled && root.announceStart && root.announceComplete, "on, with announcements");
		Set<String> ids = new HashSet<>();
		Set<EliteConfig.Type> types = EnumSet.noneOf(EliteConfig.Type.class);
		UUID probe = UUID.nameUUIDFromBytes("missions-elite-probe".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		for (EliteConfig.EliteDef d : root.missions) {
			Elite e = EliteConfig.compileDef(d.copy());
			check(ids.add(d.id), "duplicate ID " + d.id);
			types.add(e.type());
			check(d.name.length() <= 32, "short name: " + d.id);
			check(d.rewardCents >= 1_500_000 && d.rewardCents <= 2_500_000, d.id + ": reward $15,000-$25,000 (" + Money.format(d.rewardCents) + ")");
			isTrue(d.keys == null && d.keyTier == null, d.id + ": keys from the root (1× epic)");
			if (e.type() == EliteConfig.Type.KILL) {
				for (EntityType<?> t : e.entities()) {
					String key = EntityType.getKey(t).toString();
					isFalse(EliteConfig.FORBIDDEN_TARGETS.contains(key), d.id + ": " + key + " not allowed");
					isFalse(t.getCategory().isFriendly(), d.id + ": " + key + " is a hostile mob");
				}
			}
			if (e.type() == EliteConfig.Type.RAID) eq(EliteConfig.MAX_RAID_LEVEL, d.minRaidLevel, d.id + ": highest level");
			if (e.type() == EliteConfig.Type.DELIVER) {
				isFalse(BasePrices.shopBuyPrice(e.item()).isPresent(), d.id + ": no shop item");
				OptionalLong sell = BasePrices.sellPrice(e.item());
				check(sell.isPresent(), d.id + ": delivery item has a sell price");
				long sellTotal = sell.getAsLong() * e.amount();
				// price cap at the default market value (no drift or events): floor(sell × 1.6) × 1.25 each
				long market = (long) Math.floor(sell.getAsLong() * Services.DEFAULT_MARKET_FACTOR);
				long capTotal = PriceGuard.capNpcOrder(-1, market, PriceGuard.pivotPrice(e.item())) * e.amount();
				check(d.rewardCents > sellTotal, d.id + ": reward " + Money.format(d.rewardCents) + " > sell value " + Money.format(sellTotal));
				check(d.rewardCents <= capTotal, d.id + ": reward " + Money.format(d.rewardCents) + " <= price cap " + Money.format(capTotal));
				long now = EliteService.payout(probe, e);
				check(now > 0 && now <= d.rewardCents, d.id + ": payout now " + Money.format(now));
			}
		}
		eq(EnumSet.allOf(EliteConfig.Type.class), types, "all types in the pool (hunt, raid, delivery)");
		isTrue(ids.contains("warden"), "warden hunt in the pool");
		eq(List.of(), EliteConfig.problems(), "missions_elite.json without problems");
		ctx.detail(root.missions.size() + " missions, types " + types);
	}

	private static void configValidation() {
		EliteConfig.EliteDef ok = def("t_ok", EliteConfig.Type.KILL, 1, 100);
		ok.entities = List.of("minecraft:warden");
		eq("t_ok", EliteConfig.compileDef(ok).id(), "valid");
		for (String forbidden : List.of("minecraft:player", "minecraft:ender_dragon", "minecraft:iron_golem")) {
			EliteConfig.EliteDef d = def("t_bad", EliteConfig.Type.KILL, 1, 100);
			d.entities = List.of(forbidden);
			dev.smpeconomy.core.test.Assert.assertThrows(IllegalArgumentException.class, () -> EliteConfig.compileDef(d), forbidden + " rejected");
		}
		EliteConfig.EliteDef unknownMob = def("t_mob", EliteConfig.Type.KILL, 1, 100);
		unknownMob.entities = List.of("minecraft:dragoncat");
		dev.smpeconomy.core.test.Assert.assertThrows(IllegalArgumentException.class, () -> EliteConfig.compileDef(unknownMob), "unknown mob");
		EliteConfig.EliteDef noMob = def("t_nomob", EliteConfig.Type.KILL, 1, 100);
		dev.smpeconomy.core.test.Assert.assertThrows(IllegalArgumentException.class, () -> EliteConfig.compileDef(noMob), "hunt without mobs");
		EliteConfig.EliteDef raid = def("t_raid", EliteConfig.Type.RAID, 1, 100);
		raid.minRaidLevel = 6;
		dev.smpeconomy.core.test.Assert.assertThrows(IllegalArgumentException.class, () -> EliteConfig.compileDef(raid), "raid level 6");
		EliteConfig.EliteDef raidKills = def("t_raid_kills", EliteConfig.Type.RAID, 1, 100);
		raidKills.minRaidKills = 0;
		dev.smpeconomy.core.test.Assert.assertThrows(IllegalArgumentException.class, () -> EliteConfig.compileDef(raidKills), "minRaidKills 0");
		for (double share : new double[] {-0.1, 1.5, Double.NaN}) {
			EliteConfig.EliteDef raidShare = def("t_raid_share", EliteConfig.Type.RAID, 1, 100);
			raidShare.minRaidKillShare = share;
			dev.smpeconomy.core.test.Assert.assertThrows(IllegalArgumentException.class, () -> EliteConfig.compileDef(raidShare), "minRaidKillShare " + share);
		}
		EliteConfig.EliteDef raidDefaults = def("t_raid_ok", EliteConfig.Type.RAID, 1, 100);
		eq(5, raidDefaults.minRaidKills, "default minRaidKills");
		eq(0.2, raidDefaults.minRaidKillShare, "default minRaidKillShare");
		eq("t_raid_ok", EliteConfig.compileDef(raidDefaults).id(), "raid with defaults valid");
		EliteConfig.EliteDef zero = def("t_zero", EliteConfig.Type.RAID, 1, 0);
		dev.smpeconomy.core.test.Assert.assertThrows(IllegalArgumentException.class, () -> EliteConfig.compileDef(zero), "reward 0");
		EliteConfig.EliteDef amount = def("t_amount", EliteConfig.Type.RAID, 0, 100);
		dev.smpeconomy.core.test.Assert.assertThrows(IllegalArgumentException.class, () -> EliteConfig.compileDef(amount), "amount 0");
		EliteConfig.EliteDef type = def("t_type", EliteConfig.Type.RAID, 1, 100);
		type.type = "casting";
		dev.smpeconomy.core.test.Assert.assertThrows(IllegalArgumentException.class, () -> EliteConfig.compileDef(type), "unknown type");
		// shop items as a delivery: buying from the system and handing in would make the key almost free
		Item shopItem = BasePrices.shopBuyPrices().keySet().stream().findFirst().orElse(null);
		if (shopItem != null) {
			EliteConfig.EliteDef shop = def("t_shop", EliteConfig.Type.DELIVER, 1, 100);
			shop.item = Items.id(shopItem);
			dev.smpeconomy.core.test.Assert.assertThrows(IllegalArgumentException.class, () -> EliteConfig.compileDef(shop), "shop item rejected");
		}
		EliteConfig.EliteDef badItem = def("t_item", EliteConfig.Type.DELIVER, 1, 100);
		badItem.item = "minecraft:nonexistent";
		dev.smpeconomy.core.test.Assert.assertThrows(IllegalArgumentException.class, () -> EliteConfig.compileDef(badItem), "unknown item");
	}

	private static void determinism(TestContext ctx) {
		preserve(ctx);
		// pure: same input, same pick; never the same on two consecutive days; each once per cycle
		for (int n = 1; n <= 12; n++) {
			List<Integer> pool = new ArrayList<>();
			for (int i = 0; i < n; i++) pool.add(i);
			for (long seed : new long[] {0, 1, 42, -7}) {
				long start = 20_000 - 3L * n;
				Integer prev = null;
				for (long day = start; day < start + 400; day++) {
					Integer p = EliteConfig.pick(day, pool, seed);
					eq(p, EliteConfig.pick(day, pool, seed), "deterministic n=" + n);
					if (n >= 2 && prev != null) check(!p.equals(prev), "never twice in a row (n=" + n + ", seed=" + seed + ", day " + day + ")");
					prev = p;
				}
				if (n >= 3) {
					long cycleStart = Math.floorDiv(start, n) * n + n;
					Set<Integer> seen = new HashSet<>();
					for (long day = cycleStart; day < cycleStart + n; day++) seen.add(EliteConfig.pick(day, pool, seed));
					eq(n, seen.size(), "each mission once per cycle (n=" + n + ")");
				}
			}
		}
		eq(null, EliteConfig.pick(5, List.of(), 0), "empty pool");
		List<Integer> ten = List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
		boolean seedMatters = false;
		for (long day = 20_000; day < 20_030; day++) if (!EliteConfig.pick(day, ten, 0).equals(EliteConfig.pick(day, ten, 1))) seedMatters = true;
		isTrue(seedMatters, "salt changes the order");

		// today: the same for all (no player input), pinned; reload and override only change today
		long now = System.currentTimeMillis();
		MissionStore.eliteDay().day = Long.MIN_VALUE; // pretend today hasn't been picked yet
		Today t = EliteService.today(now);
		check(t != null, "there is a Daily Elite today");
		eq(EliteConfig.pick(t.day()).id(), t.id(), "today = deterministic pick");
		eq(t.id(), EliteService.today(now + 1).id(), "second access the same");
		isFalse(t.override(), "no override");
		Elite other = null;
		for (Elite e : EliteConfig.candidates()) {
			if (!e.id().equals(t.id())) {
				other = e;
				break;
			}
		}
		if (other != null) {
			// pinning: another mission in the store (as after a reload with a changed pool) stays for today
			MissionStore.eliteDay().set(t.day(), other, false);
			eq(other.id(), EliteService.today(now).id(), "pinned mission stays (reload doesn't change the day)");
			eq(other.id(), EliteService.override(other, now).id(), "admin override");
			isTrue(EliteService.today(now).override(), "override recorded");
			eq(t.id(), EliteService.clearOverride(now).id(), "clearing the override = deterministic pick");
		}
		// next day: new deterministic pick, not the same as today
		long tomorrow = EliteService.dayEnd(t.day()) + 1;
		Today t2 = EliteService.today(tomorrow);
		eq(t.day() + 1, t2.day(), "next day");
		eq(EliteConfig.pick(t2.day()).id(), t2.id(), "tomorrow = deterministic pick");
		if (EliteConfig.candidates().size() >= 2) check(!t2.id().equals(t.id()), "another mission tomorrow");
		// clock back (before the pinned day): only computed, the pin stays
		eq(t.id(), EliteService.today(now).id(), "yesterday (clock back) is only computed");
		eq(t2.day(), MissionStore.eliteDay().day, "pin stays on the newer day");
		// day boundaries (test zone, DST safe)
		long day = EliteService.epochDay(now);
		check(EliteService.dayStart(day) <= now && now < EliteService.dayEnd(day), "now is within today");
		eq(day + 1, EliteService.epochDay(EliteService.dayEnd(day)), "midnight = new day");
		eq(day, EliteService.epochDay(EliteService.dayEnd(day) - 1), "23:59:59.999 = old day");
		ctx.detail("today " + t.id() + ", tomorrow " + t2.id());
	}

	private static void oncePerDay(TestContext ctx) {
		preserve(ctx);
		FakePlayer p = ctx.player("EliteTestA");
		UUID id = p.getUUID();
		ServerLevel level = ctx.overworld();
		if (p.gameMode() != GameType.SURVIVAL) p.setGameMode(GameType.SURVIVAL);
		List<Entity> created = new ArrayList<>();
		ctx.cleanup(() -> created.forEach(Entity::discard));
		long now = System.currentTimeMillis();
		long day = EliteService.epochDay(now);
		Elite warden = kill("t_warden", 1, 2_000_000, "minecraft:warden");
		EliteService.override(warden, now);
		EconomyService.set(id, 0);
		long keys = epic(id);

		MissionHooks.onKill(p, mob(created, EntityType.WARDEN.create(level, EntitySpawnReason.TRIGGERED)));
		Today t = EliteService.today(now);
		isTrue(EliteService.doneToday(id, t), "warden killed, done");
		eq(2_000_000L, EconomyService.balance(id), "$20,000");
		eq(keys + 1, epic(id), "1 epic key");
		PlayerState s = MissionStore.peek(id);
		eq(1L, s.eliteCompleted, "done 1×");
		eq(2_000_000L, s.eliteEarned, "earned");
		// on the same day: not a second time, also not with another mission through an override
		MissionHooks.onKill(p, mob(created, EntityType.WARDEN.create(level, EntitySpawnReason.TRIGGERED)));
		eq(2_000_000L, EconomyService.balance(id), "second warden doesn't pay");
		Elite wither = kill("t_wither", 1, 1_800_000, "minecraft:wither");
		EliteService.override(wither, now);
		isTrue(EliteService.doneToday(id, EliteService.today(now)), "override on the same day: stays done");
		MissionHooks.onKill(p, mob(created, EntityType.WITHER.create(level, EntitySpawnReason.TRIGGERED)));
		eq(2_000_000L, EconomyService.balance(id), "once per day, whichever mission");
		eq(keys + 1, epic(id), "no second key");

		// midnight: new day, possible again; yesterday's progress doesn't count
		long tomorrow = EliteService.dayEnd(day) + 1_000;
		Elite two = kill("t_warden2", 2, 2_500_000, "minecraft:warden");
		EliteService.override(two, tomorrow);
		Today t2 = EliteService.today(tomorrow);
		isFalse(EliteService.doneToday(id, t2), "new day: not done");
		eq(0L, EliteService.progressValue(id, t2), "new day: progress 0");
		EliteService.onMobKilled(p, mob(created, EntityType.WARDEN.create(level, EntitySpawnReason.TRIGGERED)), tomorrow);
		eq(1L, EliteService.progressValue(id, t2), "1/2");
		eq(2_000_000L, EconomyService.balance(id), "not paid yet");
		long dayAfter = EliteService.dayEnd(t2.day()) + 1_000;
		EliteService.override(two, dayAfter);
		Today t3 = EliteService.today(dayAfter);
		eq(0L, EliteService.progressValue(id, t3), "yesterday's progress doesn't count (only kills from the start of the day)");
		EliteService.onMobKilled(p, mob(created, EntityType.WARDEN.create(level, EntitySpawnReason.TRIGGERED)), dayAfter);
		eq(1L, EliteService.progressValue(id, t3), "1/2 on the new day");
		EliteService.onMobKilled(p, mob(created, EntityType.WARDEN.create(level, EntitySpawnReason.TRIGGERED)), dayAfter);
		isTrue(EliteService.doneToday(id, t3), "2/2, done");
		eq(4_500_000L, EconomyService.balance(id), "second day paid");
		eq(keys + 2, epic(id), "second key on the new day");
		eq(2L, s.eliteCompleted, "done 2×");
		// an admin change on the same day resets progress, not completion
		ctx.detail("day " + EliteService.dayLabel(day) + " + 2 following days");
	}

	/**
	 * Only self-killed natural mobs: spawner (unlike the mission board, not even partly), spawn egg, bred, summoned,
	 * respawned give 0; so do creative/spectator, a foreign killer and the wrong mob type.
	 */
	private static void antiFarm(TestContext ctx) {
		preserve(ctx);
		FakePlayer p = ctx.player("EliteTestB");
		UUID id = p.getUUID();
		ServerLevel level = ctx.overworld();
		if (p.gameMode() != GameType.SURVIVAL) p.setGameMode(GameType.SURVIVAL);
		ctx.cleanup(() -> p.setGameMode(GameType.SURVIVAL));
		List<Entity> created = new ArrayList<>();
		ctx.cleanup(() -> created.forEach(Entity::discard));
		long now = System.currentTimeMillis();
		EliteService.override(kill("t_shulker", 3, 1_600_000, "minecraft:shulker"), now);
		EconomyService.set(id, 0);
		Today t = EliteService.today(now);

		isFalse(EliteService.naturalKill(Set.of(MobOrigin.SPAWNER)), "spawner doesn't count");
		isFalse(EliteService.naturalKill(Set.of(MobOrigin.SPAWN_EGG)), "spawn egg doesn't count");
		isFalse(EliteService.naturalKill(Set.of(MobOrigin.BRED)), "bred doesn't count");
		isFalse(EliteService.naturalKill(Set.of(MobOrigin.SUMMONED)), "summoned doesn't count");
		isFalse(EliteService.naturalKill(Set.of(MobOrigin.RESPAWNED)), "respawned doesn't count");
		isTrue(EliteService.naturalKill(Set.of()), "natural counts");
		isTrue(EliteService.naturalKill(Set.of("other_tag")), "foreign tags don't matter");

		LivingEntity bred = mob(created, EntityType.SHULKER.create(level, EntitySpawnReason.BREEDING));
		isTrue(bred.getTags().contains(MobOrigin.BRED), "shulker split tagged (EntityType mixin active)");
		LivingEntity egg = mob(created, EntityType.SHULKER.create(level, EntitySpawnReason.SPAWN_ITEM_USE));
		isTrue(egg.getTags().contains(MobOrigin.SPAWN_EGG), "spawn egg tagged");
		LivingEntity spawner = mob(created, EntityType.SHULKER.create(level, EntitySpawnReason.NATURAL));
		spawner.addTag(MobOrigin.SPAWNER);
		LivingEntity summoned = mob(created, EntityType.SHULKER.create(level, EntitySpawnReason.NATURAL));
		summoned.addTag(MobOrigin.SUMMONED);
		for (int i = 0; i < 4; i++) {
			MissionHooks.onKill(p, bred);
			MissionHooks.onKill(p, egg);
			MissionHooks.onKill(p, spawner);
			MissionHooks.onKill(p, summoned);
		}
		eq(0L, EliteService.progressValue(id, t), "farm and spawner shulkers don't count (not even 4 spawner mobs)");
		LivingEntity natural = mob(created, EntityType.SHULKER.create(level, EntitySpawnReason.NATURAL));
		for (GameType mode : new GameType[] {GameType.CREATIVE, GameType.SPECTATOR}) {
			p.setGameMode(mode);
			MissionHooks.onKill(p, natural);
		}
		p.setGameMode(GameType.SURVIVAL);
		eq(0L, EliteService.progressValue(id, t), "no kill in creative or spectator");
		MissionHooks.onKill(mob(created, EntityType.ZOMBIE.create(level, EntitySpawnReason.NATURAL)), natural);
		MissionHooks.onKill(p, null);
		MissionHooks.onKill(p, mob(created, EntityType.ZOMBIE.create(level, EntitySpawnReason.NATURAL)));
		eq(0L, EliteService.progressValue(id, t), "only own kills of the matching mob");
		MissionHooks.onKill(p, natural);
		eq(1L, EliteService.progressValue(id, t), "natural shulker counts (1/3)");
		eq(0L, EconomyService.balance(id), "no money yet");
		// another Daily Elite today: shulkers stop counting, progress belongs to the mission
		EliteService.override(kill("t_brute", 2, 1_000, "minecraft:piglin_brute"), now);
		Today other = EliteService.today(now);
		MissionHooks.onKill(p, natural);
		eq(0L, EliteService.progressValue(id, other), "shulker doesn't count for another mission");
		// the vanilla stat alone moves nothing, there is no stat counting
		var bruteStat = net.minecraft.stats.Stats.ENTITY_KILLED.get(EntityType.PIGLIN_BRUTE);
		int statBefore = p.getStats().getValue(bruteStat);
		ctx.cleanup(() -> p.getStats().setValue(p, bruteStat, statBefore));
		p.getStats().increment(p, bruteStat, 5);
		eq(0L, EliteService.progressValue(id, other), "stat doesn't count");
	}

	/** Own pseudo dimension, never collides with real raids; removed in cleanup. */
	private static String raidKey(TestContext ctx) {
		String key = EliteRaids.key("smpeco_selftest:raid", (int) (System.nanoTime() & 0x3FFFFFFF));
		ctx.cleanup(() -> {
			MissionStore.raids().remove(key);
			MissionStore.markDirty();
		});
		return key;
	}

	private static EliteRaids.Outcome outcome(String key, UUID player, int endLevel, boolean inArea) {
		MissionStore.RaidTrack t = EliteRaids.get(key);
		return new EliteRaids.Outcome(t == null ? 0 : t.startLevel, endLevel, t == null ? 0 : t.killsOf(player), t == null ? 0 : t.totalKills, inArea);
	}

	private static void kills(String key, UUID player, int n, long now) {
		for (int i = 0; i < n; i++) EliteRaids.kill(key, player, now);
	}

	/**
	 * Raid: the start level counts (Bad Omen mid-raid doesn't raise it), own contribution (at least 5 kills and 20% of
	 * player kills; a single kill already makes a vanilla hero), in the village at the win, once per day.
	 */
	private static void raid(TestContext ctx) {
		preserve(ctx);
		FakePlayer p = ctx.player("EliteTestA");
		FakePlayer q = ctx.player("EliteTestB");
		UUID id = p.getUUID();
		UUID qid = q.getUUID();
		for (FakePlayer f : List.of(p, q)) {
			if (f.gameMode() != GameType.SURVIVAL) f.setGameMode(GameType.SURVIVAL);
			ctx.cleanup(() -> f.setGameMode(GameType.SURVIVAL));
		}
		long now = System.currentTimeMillis();
		EliteConfig.EliteDef d = def("t_raid", EliteConfig.Type.RAID, 1, 2_000_000);
		d.minRaidLevel = 5;
		EliteService.override(EliteConfig.compileDef(d), now);
		EconomyService.set(id, 0);
		EconomyService.set(qid, 0);
		Today t = EliteService.today(now);
		long keys = epic(id);
		long qKeys = epic(qid);

		// minimum contribution: max(5, ⌈20% of player kills⌉)
		eq(5, EliteRaids.requiredKills(d, 0), "no kills: 5");
		eq(5, EliteRaids.requiredKills(d, 25), "25 kills: 5");
		eq(6, EliteRaids.requiredKills(d, 26), "26 kills: 6");
		eq(20, EliteRaids.requiredKills(d, 100), "100 kills: 20");

		// start level 1, stacked to 5 mid-raid: doesn't count, even with all kills
		String stacked = raidKey(ctx);
		kills(stacked, id, 3, now);
		check(EliteRaids.get(stacked) == null, "kills before the first wave create no raid");
		EliteRaids.firstWave(stacked, 1, now);
		kills(stacked, id, 30, now);
		EliteRaids.Outcome o = outcome(stacked, id, 5, true);
		eq(1, o.level(), "scored level = start level");
		eq(30, o.kills(), "30 kills counted");
		EliteService.onRaidWon(p, o, now);
		isFalse(EliteService.doneToday(id, t), "level 1 stacked to 5 doesn't count");
		// start not recorded: doesn't count
		EliteService.onRaidWon(p, new EliteRaids.Outcome(0, 5, 30, 30, true), now);
		isFalse(EliteService.doneToday(id, t), "unknown start level doesn't count");
		for (int level = 1; level <= 4; level++) EliteService.onRaidWon(p, new EliteRaids.Outcome(level, level, 30, 30, true), now);
		isFalse(EliteService.doneToday(id, t), "levels 1-4 don't count");

		// start level 5: q kills a single raider (vanilla hero), p too few, then enough, but only in the village
		String raid = raidKey(ctx);
		EliteRaids.firstWave(raid, 5, now);
		kills(raid, qid, 1, now);
		kills(raid, id, 4, now);
		EliteService.onRaidWon(p, outcome(raid, id, 5, true), now);
		isFalse(EliteService.doneToday(id, t), "4 own kills aren't enough");
		kills(raid, id, 1, now);
		eq(6, EliteRaids.get(raid).totalKills, "total player kills");
		EliteService.onRaidWon(p, outcome(raid, id, 5, false), now);
		isFalse(EliteService.doneToday(id, t), "not in the village at the win: doesn't count");
		p.setGameMode(GameType.CREATIVE);
		EliteService.onRaidWon(p, outcome(raid, id, 5, true), now);
		isFalse(EliteService.doneToday(id, t), "creative doesn't count");
		p.setGameMode(GameType.SURVIVAL);
		EliteService.onRaidWon(p, outcome(raid, id, 5, true), now);
		isTrue(EliteService.doneToday(id, t), "level 5, 5 kills, in the village: done");
		eq(2_000_000L, EconomyService.balance(id), "reward");
		eq(keys + 1, epic(id), "key");
		EliteService.onRaidWon(q, outcome(raid, qid, 5, true), now);
		isFalse(EliteService.doneToday(qid, t), "a single kill (Hero of the Village) doesn't count");
		eq(0L, EconomyService.balance(qid), "no money for bystanders");
		eq(qKeys, epic(qid), "no key for bystanders");
		EliteService.onRaidWon(p, outcome(raid, id, 5, true), now);
		eq(2_000_000L, EconomyService.balance(id), "second win doesn't pay");

		// share (next day for q): 19 of 99 player kills (needs ⌈19.8⌉ = 20) doesn't count, 20 of 100 does
		long next = EliteService.dayEnd(t.day()) + 1;
		Today t2 = EliteService.override(EliteConfig.compileDef(d), next);
		String shared = raidKey(ctx);
		EliteRaids.firstWave(shared, 5, next);
		kills(shared, id, 80, next);
		kills(shared, qid, 19, next);
		EliteService.onRaidWon(q, outcome(shared, qid, 5, true), next);
		isFalse(EliteService.doneToday(qid, t2), "19 of 99 kills (< 20%) doesn't count");
		EliteRaids.kill(shared, qid, next);
		EliteService.onRaidWon(q, outcome(shared, qid, 5, true), next);
		isTrue(EliteService.doneToday(qid, t2), "20 of 100 kills (20%): done");
		eq(2_000_000L, EconomyService.balance(qid), "q paid");

		// raid wins don't count for hunt missions
		long after = EliteService.dayEnd(t2.day()) + 1;
		Today hunt = EliteService.override(kill("t_warden3", 1, 1_000, "minecraft:warden"), after);
		EliteService.onRaidWon(p, new EliteRaids.Outcome(5, 5, 30, 30, true), after);
		isFalse(EliteService.doneToday(id, hunt), "raid doesn't count for a hunt");
		eq(2_000_000L, EconomyService.balance(id), "no money");

		// cleanup: kept briefly after the win (all heroes in the same tick), then gone; old entries too. Uses its own
		// map, so real running raids on the server stay untouched
		EliteRaids.finish(raid, now);
		EliteRaids.kill(raid, id, now);
		eq(5, EliteRaids.get(raid).killsOf(id), "no more kills after the win");
		Map<String, MissionStore.RaidTrack> copy = new java.util.HashMap<>();
		copy.put(raid, EliteRaids.get(raid));
		copy.put(stacked, EliteRaids.get(stacked));
		isFalse(EliteRaids.prune(copy, now + 1_000), "nothing cleaned up shortly after the win");
		check(copy.containsKey(raid), "still there shortly after the win");
		isTrue(EliteRaids.prune(copy, now + EliteRaids.FINISHED_KEEP_MS + 1), "cleaned up after the win");
		check(!copy.containsKey(raid) && copy.containsKey(stacked), "finished one gone, running one stays");
		EliteRaids.prune(copy, now + EliteRaids.STALE_MS + 1);
		check(copy.isEmpty(), "long unchanged raid cleaned up");
		Map<String, MissionStore.RaidTrack> many = new java.util.HashMap<>();
		for (int i = 0; i < EliteRaids.MAX_TRACKED + 3; i++) {
			MissionStore.RaidTrack r = new MissionStore.RaidTrack();
			r.updatedAt = now + i;
			many.put("x#" + i, r);
		}
		EliteRaids.prune(many, now + 10_000);
		eq(EliteRaids.MAX_TRACKED, many.size(), "at most MAX_TRACKED entries");
		check(!many.containsKey("x#0") && many.containsKey("x#" + (EliteRaids.MAX_TRACKED + 2)), "the oldest go first");

		// mixins applied (defaultRequire 1 would abort startup otherwise; this is only for visibility)
		for (String m : List.of("missionsRaidWon", "missionsFirstWave")) {
			isTrue(java.util.Arrays.stream(Raid.class.getDeclaredMethods()).anyMatch(x -> x.getName().contains(m)), "RaidMixin " + m);
		}
		isTrue(java.util.Arrays.stream(net.minecraft.world.entity.raid.Raider.class.getDeclaredMethods())
			.anyMatch(x -> x.getName().contains("missionsRaiderDied")), "RaiderMixin active");
	}

	private static void delivery(TestContext ctx) {
		preserve(ctx);
		FakePlayer p = ctx.player("EliteTestB");
		UUID id = p.getUUID();
		ctx.cleanup(() -> PriceModifiers.removeSource(MOD_SOURCE));
		if (p.gameMode() != GameType.SURVIVAL) p.setGameMode(GameType.SURVIVAL);
		ctx.cleanup(() -> p.setGameMode(GameType.SURVIVAL));
		long now = System.currentTimeMillis();
		Item block = net.minecraft.world.item.Items.NETHERITE_BLOCK;
		Elite el = deliver("t_netherite", block, 2, 2_200_000);
		EliteService.override(el, now);
		Today t = EliteService.today(now);
		EconomyService.set(id, 0);
		long keys = epic(id);
		long cap = EliteService.deliveryCap(id, el);
		check(cap > 0, "netherite block has a price cap");
		eq(Math.min(2_200_000L, cap), EliteService.payout(id, el), "payout = min(reward, price cap × amount)");

		eq(DeliverStatus.NONE, EliteService.deliver(p, now).status(), "nothing in the inventory");
		ItemStack renamed = new ItemStack(block, 2);
		renamed.set(DataComponents.CUSTOM_NAME, Component.literal("Forgery"));
		Items.giveOrDrop(p, renamed);
		eq(DeliverStatus.ONLY_MODIFIED, EliteService.deliver(p, now).status(), "renamed doesn't count");
		ItemStack trader = new ItemStack(block, 2);
		TraderGoods.markTradeResult(trader);
		Items.giveOrDrop(p, trader);
		eq(DeliverStatus.ONLY_MODIFIED, EliteService.deliver(p, now).status(), "merchant goods don't count");
		Items.giveOrDrop(p, new ItemStack(block, 1));
		EliteService.DeliverResult few = EliteService.deliver(p, now);
		eq(DeliverStatus.NOT_ENOUGH, few.status(), "1 of 2: nothing handed in");
		eq(1, Items.countPristine(p, block), "item stays");
		Items.giveOrDrop(p, new ItemStack(block, 2));
		p.setGameMode(GameType.CREATIVE);
		eq(DeliverStatus.BLOCKED, EliteService.deliver(p, now).status(), "creative blocked");
		p.setGameMode(GameType.SURVIVAL);
		// account limit: credit impossible, so nothing gets taken
		EconomyService.set(id, Money.MAX);
		eq(DeliverStatus.PAYMENT_FAILED, EliteService.deliver(p, now).status(), "account limit");
		eq(3, Items.countPristine(p, block), "nothing taken");
		EconomyService.set(id, 0);
		// crash -50%: price cap recomputed at hand-in (visible here with an absurd desired amount)
		Elite greedy = deliver("t_netherite_greedy", block, 2, Money.MAX / 4);
		EliteService.override(greedy, now);
		long capBefore = EliteService.deliveryCap(id, greedy);
		PriceModifiers.set(MOD_SOURCE, block, 0.5, 0, 60_000, 0, "Selftest");
		long capCrash = EliteService.deliveryCap(id, greedy);
		check(capCrash < capBefore, "crash lowers the price cap (" + capCrash + " < " + capBefore + ")");
		eq(capCrash, EliteService.payout(id, greedy), "payout capped");
		eq(capCrash, PriceGuard.maxNpcOrderPrice(id, block, Services.marketValue(block).orElse(0)) * 2, "= maxNpcOrderPrice × amount");
		EliteService.DeliverResult r = EliteService.deliver(p, now);
		eq(DeliverStatus.OK, r.status(), "handed in");
		eq(capCrash, r.paid(), "paid = price cap");
		eq(capCrash, EconomyService.balance(id), "balance");
		eq(1, Items.countPristine(p, block), "exactly 2 taken");
		eq(4, Items.count(p, st -> st.is(block) && !Items.isPristine(st)), "modified ones stay (2 renamed + 2 merchant goods)");
		eq(keys + 1, epic(id), "key");
		isTrue(EliteService.doneToday(id, EliteService.today(now)), "done");
		Items.giveOrDrop(p, new ItemStack(block, 2));
		eq(DeliverStatus.DONE, EliteService.deliver(p, now).status(), "second hand-in on the same day rejected");
		eq(3, Items.countPristine(p, block), "nothing taken");
		eq(capCrash, EconomyService.balance(id), "no second money");
		EliteService.override(kill("t_kill", 1, 1_000, "minecraft:warden"), EliteService.dayEnd(t.day()) + 5);
		eq(DeliverStatus.WRONG_TYPE, EliteService.deliver(p, EliteService.dayEnd(t.day()) + 5).status(), "no delivery");
		ctx.detail("price cap " + Money.format(capBefore) + ", in a crash " + Money.format(capCrash));
	}

	/**
	 * Confirmation dialog: exactly the delivery shown gets confirmed. If the Daily Elite changes while the dialog is
	 * open (admin {@code set}, midnight), {@code deliver} takes nothing, even if the player could fulfil the new one.
	 */
	private static void deliveryChanged(TestContext ctx) {
		preserve(ctx);
		FakePlayer p = ctx.player("EliteTestB");
		UUID id = p.getUUID();
		if (p.gameMode() != GameType.SURVIVAL) p.setGameMode(GameType.SURVIVAL);
		ctx.cleanup(() -> p.setGameMode(GameType.SURVIVAL));
		Item block = net.minecraft.world.item.Items.NETHERITE_BLOCK;
		Item debris = net.minecraft.world.item.Items.ANCIENT_DEBRIS;
		ctx.cleanup(() -> {
			Items.remove(p, st -> st.is(block) || st.is(debris), Integer.MAX_VALUE);
		});
		Items.remove(p, st -> st.is(block) || st.is(debris), Integer.MAX_VALUE);
		long now = System.currentTimeMillis();
		EconomyService.set(id, 0);
		long keys = epic(id);
		Today shown = EliteService.override(deliver("t_netherite_1", block, 1, 2_000_000), now);
		// admin switches to another delivery the player can fulfil
		Today other = EliteService.override(deliver("t_debris_40", debris, 40, 1_500_000), now);
		Items.giveOrDrop(p, new ItemStack(debris, 40));
		Items.giveOrDrop(p, new ItemStack(block, 1));
		isFalse(EliteService.sameDelivery(shown, other), "other mission ≠ the one shown");
		eq(DeliverStatus.CHANGED, EliteService.deliver(p, now, shown).status(), "admin change: nothing handed in");
		eq(40, Items.countPristine(p, debris), "ancient debris stays");
		eq(1, Items.countPristine(p, block), "netherite block stays");
		isFalse(EliteService.doneToday(id, EliteService.today(now)), "not done");
		eq(0L, EconomyService.balance(id), "no money");
		eq(keys, epic(id), "no key");
		// same ID but another amount (config reloaded and set again): also nothing
		EliteService.override(deliver("t_netherite_1", block, 2, 2_000_000), now);
		Items.giveOrDrop(p, new ItemStack(block, 1));
		eq(DeliverStatus.CHANGED, EliteService.deliver(p, now, shown).status(), "other amount: nothing handed in");
		eq(2, Items.countPristine(p, block), "netherite blocks stay");
		// midnight: the same mission on the next day isn't the one shown
		long tomorrow = EliteService.dayEnd(shown.day()) + 1;
		Today next = EliteService.override(deliver("t_netherite_1", block, 1, 2_000_000), tomorrow);
		isFalse(EliteService.sameDelivery(shown, next), "new day ≠ the one shown");
		eq(DeliverStatus.CHANGED, EliteService.deliver(p, tomorrow, shown).status(), "after midnight: nothing handed in");
		eq(2, Items.countPristine(p, block), "nothing taken");
		EliteService.override(deliver("t_netherite_1", block, 1, 2_000_000), now);
		Today same = EliteService.today(now);
		isTrue(EliteService.sameDelivery(shown, same), "same delivery recognized");
		EliteService.DeliverResult r = EliteService.deliver(p, now, shown);
		eq(DeliverStatus.OK, r.status(), "shown delivery handed in");
		eq(1, Items.countPristine(p, block), "exactly 1 taken");
		eq(40, Items.countPristine(p, debris), "ancient debris untouched");
		eq(r.paid(), EconomyService.balance(id), "paid");
		eq(keys + 1, epic(id), "key");
	}

	private static void exactlyOnce(TestContext ctx) {
		preserve(ctx);
		FakePlayer p = ctx.player("EliteTestA");
		UUID id = p.getUUID();
		long now = System.currentTimeMillis();
		Elite el = kill("t_once", 1, 1_234, "minecraft:warden");
		Today t = EliteService.override(el, now);
		EconomyService.set(id, 0);
		long keys = epic(id);
		PlayerState s = MissionStore.state(id);
		EliteProgress e = EliteService.progress(s, t);
		isTrue(EliteService.complete(id, p, s, e, t, 1_234, now, false), "first payout");
		isFalse(EliteService.complete(id, p, s, e, t, 1_234, now, false), "second payout");
		isFalse(EliteService.complete(id, p, s, e, t, 1_234, now, true), "not atomically either");
		eq(1_234L, EconomyService.balance(id), "money exactly once");
		eq(keys + 1, epic(id), "exactly one key");
		eq(1_234L, e.paid, "paid recorded");
		// account limit on a hunt: the day stays done (no endless retries); on a delivery (atomic) it stays open
		long next = EliteService.dayEnd(t.day()) + 1;
		Today t2 = EliteService.override(el, next);
		EconomyService.set(id, Money.MAX);
		EliteProgress e2 = EliteService.progress(s, t2);
		isFalse(EliteService.complete(id, p, s, e2, t2, 1_234, next, true), "atomic: credit fails");
		isFalse(e2.done, "atomic: day stays open");
		isTrue(EliteService.complete(id, p, s, e2, t2, 1_234, next, false), "not atomic: completed");
		isTrue(e2.done, "day done");
		eq(0L, e2.paid, "nothing paid");
		// test player: no announcement to real players; message as pending (test players aren't in the PlayerList)
		eq(2L, s.eliteCompleted, "done 2×");
	}

	private static void persistence() {
		PlayerState s = new PlayerState();
		s.elite.day = 20_371;
		s.elite.missionId = "warden";
		s.elite.killMilli = 1_000;
		s.elite.progress = 0;
		s.elite.done = true;
		s.elite.doneAt = 123;
		s.elite.paid = 2_000_000;
		s.elite.announcedDay = 20_371;
		s.eliteCompleted = 3;
		s.eliteEarned = 6_000_000;
		PlayerState back = DataStore.gson().fromJson(DataStore.gson().toJson(s), PlayerState.class);
		back.fixNulls();
		eq(20_371L, back.elite.day, "day");
		eq("warden", back.elite.missionId, "Mission");
		eq(1_000L, back.elite.killMilli, "Kills");
		isTrue(back.elite.done, "done");
		eq(2_000_000L, back.elite.paid, "paid");
		eq(20_371L, back.elite.announcedDay, "announced");
		eq(3L, back.eliteCompleted, "count");
		eq(6_000_000L, back.eliteEarned, "earned");
		// old save without Daily Elite fields
		PlayerState legacy = DataStore.gson().fromJson("{\"completed\":4}", PlayerState.class);
		legacy.fixNulls();
		check(legacy.elite != null && !legacy.elite.done && legacy.elite.day == Long.MIN_VALUE, "old save: empty Daily Elite state");
		EliteDay g = new EliteDay();
		Elite el = kill("t_pin", 3, 1_500_000, "minecraft:elder_guardian");
		g.set(20_371, el, true);
		EliteDay g2 = DataStore.gson().fromJson(DataStore.gson().toJson(g), EliteDay.class);
		eq(20_371L, g2.day, "pin day");
		isTrue(g2.override, "Override");
		Elite back2 = g2.elite();
		check(back2 != null && back2.id().equals("t_pin") && back2.amount() == 3 && back2.countsKill(EntityType.ELDER_GUARDIAN), "snapshot compiled");
		MissionStore.Data legacyData = DataStore.gson().fromJson("{\"players\":{}}", MissionStore.Data.class);
		check(legacyData.players != null, "old missions_state.json readable");
		check(legacyData.raids != null && legacyData.raids.isEmpty(), "old save: no tracked raids");
		// tracked raid (restart mid-raid)
		MissionStore.Data data = new MissionStore.Data();
		MissionStore.RaidTrack track = new MissionStore.RaidTrack();
		UUID hero = UUID.fromString("00000000-0000-0000-0000-00000000e1e1");
		track.startLevel = 5;
		track.kills.put(hero, 7);
		track.totalKills = 9;
		track.updatedAt = 1_234;
		data.raids.put(EliteRaids.key("minecraft:overworld", 42), track);
		MissionStore.Data data2 = DataStore.gson().fromJson(DataStore.gson().toJson(data), MissionStore.Data.class);
		MissionStore.RaidTrack track2 = data2.raids.get("minecraft:overworld#42");
		check(track2 != null, "raid saved");
		eq(5, track2.startLevel, "start level");
		eq(7, track2.killsOf(hero), "hero's kills");
		eq(9, track2.totalKills, "total kills");
		eq(0, track2.killsOf(UUID.randomUUID()), "other player 0");
		eq(Map.of(), legacyData.players, "no players");
	}
}
