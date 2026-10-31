package dev.smpeconomy.features.missions;

import static dev.smpeconomy.core.test.Assert.check;
import static dev.smpeconomy.core.test.Assert.eq;
import static dev.smpeconomy.core.test.Assert.isFalse;
import static dev.smpeconomy.core.test.Assert.isTrue;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import dev.smpeconomy.core.CrateKeys;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.MobOrigin;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.PlacedBlockTracker;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.PriceGuard;
import dev.smpeconomy.core.PriceModifiers;
import dev.smpeconomy.core.Services;
import dev.smpeconomy.core.TimeUtil;
import dev.smpeconomy.core.test.SelfTests;
import dev.smpeconomy.core.test.TestContext;
import dev.smpeconomy.features.missions.MissionService.AcceptResult;
import dev.smpeconomy.features.missions.MissionService.DeliverStatus;
import dev.smpeconomy.features.missions.MissionStore.ActiveMission;
import dev.smpeconomy.features.missions.MissionStore.PlayerState;

/** Selftests for the mission board, without GUI clicks (e2e/missions.mjs covers the GUIs). */
final class MissionSelfTests {
	private static final String F = "missions";
	private static final String MOD_SOURCE = "missions.selftest";

	private MissionSelfTests() {}

	static void register() {
		SelfTests.register(F, "pool-defaults", MissionSelfTests::poolDefaults);
		SelfTests.register(F, "config-validation", ctx -> configValidation());
		SelfTests.register(F, "period-math", ctx -> periodMath());
		SelfTests.register(F, "rotation-determinism", MissionSelfTests::rotationDeterminism);
		SelfTests.register(F, "accept-limits-cooldown", MissionSelfTests::acceptLimits);
		SelfTests.register(F, "delivery-guard-cap", MissionSelfTests::deliveryGuardCap);
		SelfTests.register(F, "delivery-pristine-and-limits", MissionSelfTests::deliveryPristine);
		SelfTests.register(F, "delivery-no-keys", MissionSelfTests::deliveryNoKeys);
		SelfTests.register(F, "delivery-creative-blocked", MissionSelfTests::deliveryCreativeBlocked);
		SelfTests.register(F, "stat-delta-progress", MissionSelfTests::statDelta);
		SelfTests.register(F, "mined-natural-blocks-only", MissionSelfTests::minedNaturalOnly);
		SelfTests.register(F, "mined-veinminer-stat-ignored", MissionSelfTests::minedVeinminerIgnored);
		SelfTests.register(F, "mined-legacy-migration", MissionSelfTests::minedLegacyMigration);
		SelfTests.register(F, "kill-origin-not-counted", MissionSelfTests::killOrigin);
		SelfTests.register(F, "kill-dragon-only-first", MissionSelfTests::killDragonOnlyFirst);
		SelfTests.register(F, "treasure-fishing", MissionSelfTests::treasure);
		SelfTests.register(F, "explore-enter", MissionSelfTests::explore);
		SelfTests.register(F, "explore-once-per-player", MissionSelfTests::exploreOnce);
		SelfTests.register(F, "exactly-once-payout-keys", MissionSelfTests::exactlyOnce);
		SelfTests.register(F, "expire-online-offline", MissionSelfTests::expire);
		SelfTests.register(F, "cancel-keeps-paid", MissionSelfTests::cancelKeepsPaid);
		SelfTests.register(F, "persistence-roundtrip", MissionSelfTests::persistence);
		SelfTests.register(F, "texts-english", MissionSelfTests::textsEnglish);
	}

	private static MissionConfig.MissionDef def(String id, Tier tier, Mission.Type type, int amount, long reward) {
		MissionConfig.MissionDef d = new MissionConfig.MissionDef();
		d.id = id;
		d.tier = tier.id;
		d.type = type.id;
		d.name = "Test " + id;
		d.description = "Selftest-Mission " + id;
		d.amount = amount;
		d.rewardCents = reward;
		return d;
	}

	private static Mission compile(MissionConfig.MissionDef d) {
		String err = MissionConfig.validate(d);
		check(err == null, "test mission valid: " + err);
		return Mission.compile(d);
	}

	private static Mission statMission(String id, Tier tier, int amount, long reward, String... stats) {
		MissionConfig.MissionDef d = def(id, tier, Mission.Type.STAT, amount, reward);
		d.stats = List.of(stats);
		return compile(d);
	}

	private static Mission deliverMission(String id, Item item, int amount, long perItem) {
		return deliverMission(id, Tier.MEDIUM, item, amount, perItem);
	}

	private static Mission deliverMission(String id, Tier tier, Item item, int amount, long perItem) {
		MissionConfig.MissionDef d = def(id, tier, Mission.Type.DELIVER, amount, 0);
		d.item = Items.id(item);
		d.rewardPerItemCents = perItem;
		return compile(d);
	}

	private static ActiveMission assign(FakePlayer p, Mission m, long now) {
		eq(AcceptResult.OK, MissionService.assign(p, m, 0, now), "assign " + m.id);
		PlayerState s = MissionStore.peek(p.getUUID());
		return s.active.get(s.active.size() - 1);
	}

	private static void setStat(FakePlayer p, Stat<?> stat, int value) {
		p.getStats().setValue(p, stat, value);
	}

	/** Remembers a stat value and resets it in cleanup; test players share the PlayerList's stats counter. */
	private static void restoreStat(TestContext ctx, FakePlayer p, Stat<?> stat) {
		int before = p.getStats().getValue(stat);
		ctx.cleanup(() -> p.getStats().setValue(p, stat, before));
	}

	/** Mining only counts with the correct tool, as in vanilla: diamond pickaxe in hand, cleanup empties it. */
	private static void holdPickaxe(TestContext ctx, FakePlayer p) {
		p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(net.minecraft.world.item.Items.DIAMOND_PICKAXE));
		ctx.cleanup(() -> p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY));
	}

	private static BlockPos freePos(ServerLevel level, int dy) {
		BlockPos pos = new BlockPos(12_345_678, 300 + dy, -12_345_678);
		check(!PlacedBlockTracker.isPlayerPlaced(level, pos), "test position free " + pos.toShortString());
		return pos;
	}

	private static BlockPos placedPos(TestContext ctx, ServerLevel level, int dy) {
		BlockPos pos = freePos(level, dy);
		PlacedBlockTracker.markPlaced(level, pos);
		ctx.cleanup(() -> PlacedBlockTracker.unmark(level, pos));
		return pos;
	}

	private static <T extends LivingEntity> T mob(List<Entity> created, T entity) {
		check(entity != null, "mob created");
		created.add(entity);
		return entity;
	}

	private static ItemStack renamed(Item item, int count) {
		ItemStack s = new ItemStack(item, count);
		s.set(DataComponents.CUSTOM_NAME, Component.literal("Forgery"));
		return s;
	}

	private static void poolDefaults(TestContext ctx) {
		MissionConfig.Root root = MissionConfig.CONFIG.defaults();
		check(root.missions.size() >= 40, "at least 40 missions in the default pool (" + root.missions.size() + ")");
		Set<String> ids = new HashSet<>();
		Map<Tier, Integer> perTier = new java.util.EnumMap<>(Tier.class);
		Set<Mission.Type> types = EnumSet.noneOf(Mission.Type.class);
		UUID probe = UUID.nameUUIDFromBytes("missions-selftest-probe".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		MinecraftServer server = ctx.server();
		Set<String> exploreTargets = new HashSet<>();
		for (MissionConfig.MissionDef d : root.missions) {
			String err = MissionConfig.validate(d);
			check(err == null, "default mission " + d.id + ": " + err);
			check(ids.add(d.id), "duplicate ID " + d.id);
			Mission m = Mission.compile(d.copy());
			String target = m.checkTargets(server);
			check(target == null, "target of " + d.id + ": " + target);
			check(d.name.length() <= 32 && !d.description.isBlank(), "texts of " + d.id);
			perTier.merge(m.tier, 1, Integer::sum);
			types.add(m.type);
			if (m.type == Mission.Type.DELIVER) {
				OptionalLong market = Services.marketValue(m.item);
				check(market.isPresent(), "delivery item " + d.item + " has a market value");
				long cap = PriceGuard.maxNpcOrderPrice(probe, m.item, market.orElse(0));
				check(cap > 0, "delivery item " + d.item + " has a price cap > 0");
				eq(0L, d.rewardCents, "delivery " + d.id + " without completion money (the price cap could be bypassed otherwise)");
				check(d.keys == null || d.keys == 0, "delivery " + d.id + " without its own keys");
				eq(0, MissionService.keys(m), "delivery " + d.id + " gives no keys (items can be bought from the system)");
			} else {
				check(d.rewardCents > 0, "reward > 0 for " + d.id);
			}
			if (m.type == Mission.Type.EXPLORE) check(exploreTargets.add(m.exploreKey), "exploration target of " + d.id + " duplicated: " + m.exploreKey);
		}
		for (Tier t : Tier.values()) {
			int offers = root.tiers.get(t.id) != null && root.tiers.get(t.id).offers != null ? root.tiers.get(t.id).offers : t.defaultOffers;
			check(perTier.getOrDefault(t, 0) >= offers * 2, "tier " + t.id + ": enough missions for the rotation (" + perTier.get(t) + ")");
		}
		eq(EnumSet.allOf(Mission.Type.class), types, "all mission types in the pool");
		check(root.tiers.get("hard").keys > 0 && CrateKeys.RARE.equals(root.tiers.get("hard").keyTier), "hard gives a rare key");
		check(root.tiers.get("elite").keys > 0 && CrateKeys.EPIC.equals(root.tiers.get("elite").keyTier), "elite gives an epic key");
		check(root.tiers.get("easy").keys == 0 && root.tiers.get("medium").keys == 0, "easy and medium without keys");
		eq(List.of(), MissionConfig.problems(), "missions.json without problems");
		ctx.detail(root.missions.size() + " missions, " + perTier);
	}

	private static void configValidation() {
		MissionConfig.MissionDef ok = def("t_ok", Tier.EASY, Mission.Type.STAT, 3, 100);
		ok.stats = List.of("killed:zombie");
		eq(null, MissionConfig.validate(ok), "valid mission");
		MissionConfig.MissionDef badId = def("Bad ID", Tier.EASY, Mission.Type.STAT, 3, 100);
		check(MissionConfig.validate(badId) != null, "invalid ID rejected");
		MissionConfig.MissionDef badTier = def("t_tier", Tier.EASY, Mission.Type.STAT, 3, 100);
		badTier.tier = "legendary";
		check(MissionConfig.validate(badTier) != null, "unknown tier rejected");
		MissionConfig.MissionDef badType = def("t_type", Tier.EASY, Mission.Type.STAT, 3, 100);
		badType.type = "casting";
		check(MissionConfig.validate(badType) != null, "unknown type rejected");
		MissionConfig.MissionDef zero = def("t_zero", Tier.EASY, Mission.Type.STAT, 0, 100);
		check(MissionConfig.validate(zero) != null, "amount 0 rejected");
		MissionConfig.MissionDef negative = def("t_neg", Tier.EASY, Mission.Type.STAT, 1, -5);
		check(MissionConfig.validate(negative) != null, "negative reward rejected");
		MissionConfig.MissionDef badStat = def("t_stat", Tier.EASY, Mission.Type.STAT, 1, 1);
		badStat.stats = List.of("killed:dragoncat");
		dev.smpeconomy.core.test.Assert.assertThrows(IllegalArgumentException.class, () -> Mission.compile(badStat), "unknown stat");
		MissionConfig.MissionDef noStat = def("t_nostat", Tier.EASY, Mission.Type.STAT, 1, 1);
		dev.smpeconomy.core.test.Assert.assertThrows(IllegalArgumentException.class, () -> Mission.compile(noStat), "missing stat");
		MissionConfig.MissionDef badItem = def("t_item", Tier.EASY, Mission.Type.DELIVER, 1, 0);
		badItem.item = "minecraft:nonexistent";
		dev.smpeconomy.core.test.Assert.assertThrows(IllegalArgumentException.class, () -> Mission.compile(badItem), "unknown item");
		MissionConfig.MissionDef noTarget = def("t_explore", Tier.EASY, Mission.Type.EXPLORE, 1, 1);
		dev.smpeconomy.core.test.Assert.assertThrows(IllegalArgumentException.class, () -> Mission.compile(noTarget), "exploration without a target");
		eq(true, Mission.parseStat("custom:fish_caught").isPresent(), "custom:fish_caught");
		eq(true, Mission.parseStat("mined:minecraft:deepslate_diamond_ore").isPresent(), "mined with namespace");
		eq(false, Mission.parseStat("killed").isPresent(), "invalid without an object");
		eq(false, Mission.parseStat("flying:zombie").isPresent(), "unknown stat type");
		MissionConfig.MissionDef explore = def("t_explore2", Tier.EASY, Mission.Type.EXPLORE, 5, 1);
		explore.structure = "minecraft:nonexistent";
		Mission m = Mission.compile(explore);
		eq(1, m.def.amount, "exploration always has amount 1");
		check(m.checkTargets(dev.smpeconomy.SmpEconomy.server()) != null, "unknown structure gets detected");
	}

	private static void periodMath() {
		int h = 6;
		long t = ZonedDateTime.of(2026, 5, 4, 13, 37, 0, 0, TimeUtil.zone()).toInstant().toEpochMilli();
		long p = MissionService.period(t, h);
		long start = MissionService.periodStart(p, h);
		long end = MissionService.periodEnd(p, h);
		eq(ZonedDateTime.of(2026, 5, 4, 12, 0, 0, 0, TimeUtil.zone()).toInstant().toEpochMilli(), start, "round begins 12:00");
		eq(6 * 3_600_000L, end - start, "round lasts 6h");
		eq(p, MissionService.period(start, h), "start belongs to the round");
		eq(p, MissionService.period(end - 1, h), "last ms belongs to the round");
		eq(p + 1, MissionService.period(end, h), "end = next round");
		eq(LocalDate.of(2026, 5, 4).toEpochDay() * 4 + 2, p, "index = day × 4 + round");
		// DST change (2026-03-29, 02:00 to 03:00): first round only 5h, then 06:00/12:00/18:00 again
		long dst = ZonedDateTime.of(2026, 3, 29, 1, 0, 0, 0, TimeUtil.zone()).toInstant().toEpochMilli();
		long pd = MissionService.period(dst, h);
		eq(5 * 3_600_000L, MissionService.periodEnd(pd, h) - MissionService.periodStart(pd, h), "DST round 5h");
		eq(6, java.time.Instant.ofEpochMilli(MissionService.periodEnd(pd, h)).atZone(TimeUtil.zone()).getHour(), "next round 06:00");
		// end of DST (2026-10-25, 03:00 to 02:00): first round 7h
		long back = ZonedDateTime.of(2026, 10, 25, 1, 0, 0, 0, TimeUtil.zone()).toInstant().toEpochMilli();
		long pb = MissionService.period(back, h);
		eq(7 * 3_600_000L, MissionService.periodEnd(pb, h) - MissionService.periodStart(pb, h), "end-of-DST round 7h");
		for (int rh : MissionConfig.ROTATION_HOURS) {
			long pr = MissionService.period(t, rh);
			check(MissionService.periodStart(pr, rh) <= t && t < MissionService.periodEnd(pr, rh), "Rotation " + rh + "h contains t");
		}
	}

	private static void rotationDeterminism(TestContext ctx) {
		FakePlayer a = ctx.player("MissTestA");
		FakePlayer b = ctx.player("MissTestB");
		long now = System.currentTimeMillis();
		long period = MissionService.period(now);
		Map<Tier, List<Mission>> first = MissionService.board(a.getUUID(), period);
		int total = 0;
		for (Tier t : Tier.values()) {
			List<Mission> offers = first.get(t);
			int expected = Math.min(t.settings().offers, MissionConfig.candidates(t).size());
			eq(expected, offers.size(), "offers " + t.id);
			Set<String> seen = new HashSet<>();
			for (Mission m : offers) {
				eq(t, m.tier, "tier matches");
				check(seen.add(m.id), "no duplicate offers");
				check(m.def.enabled, "only enabled missions");
			}
			total += offers.size();
		}
		check(total > 0, "board not empty");
		// same player + same round = same board, also after accepting a mission
		eq(ids(first), ids(MissionService.board(a.getUUID(), period)), "board deterministic");
		Mission any = first.values().stream().filter(l -> !l.isEmpty()).findFirst().get().get(0);
		eq(AcceptResult.OK, MissionService.accept(a, any.id, period, now), "accept");
		eq(ids(first), ids(MissionService.board(a.getUUID(), period)), "board unchanged after accepting");
		// other rounds or players: (almost certainly) a different selection
		boolean otherPeriod = false;
		boolean otherPlayer = false;
		for (int i = 1; i <= 12; i++) {
			if (!ids(MissionService.board(a.getUUID(), period + i)).equals(ids(first))) otherPeriod = true;
			if (!ids(MissionService.board(b.getUUID(), period + i - 1)).equals(ids(MissionService.board(a.getUUID(), period + i - 1)))) otherPlayer = true;
		}
		check(otherPeriod, "board rotates across rounds");
		check(otherPlayer, "each player has their own selection");
		// seed purely from (player, round, tier, salt)
		eq(MissionService.seed(a.getUUID(), period, Tier.ELITE, 0), MissionService.seed(a.getUUID(), period, Tier.ELITE, 0), "seed stable");
		check(MissionService.seed(a.getUUID(), period, Tier.ELITE, 0) != MissionService.seed(a.getUUID(), period, Tier.ELITE, 1), "salt changes the seed");
		ctx.detail(total + " offers per round");
	}

	private static List<String> ids(Map<Tier, List<Mission>> board) {
		List<String> list = new ArrayList<>();
		for (List<Mission> l : board.values()) for (Mission m : l) list.add(m.id);
		return list;
	}

	private static void acceptLimits(TestContext ctx) {
		FakePlayer p = ctx.player("MissTestA");
		UUID id = p.getUUID();
		long now = System.currentTimeMillis();
		long period = MissionService.period(now);
		List<Mission> offers = new ArrayList<>();
		for (List<Mission> l : MissionService.board(id, period).values()) offers.addAll(l);
		int max = MissionConfig.maxActive();
		check(offers.size() >= max + 2, "enough offers for the test (" + offers.size() + ")");

		eq(AcceptResult.UNKNOWN, MissionService.accept(p, "does_not_exist", period, now), "unknown mission");
		eq(AcceptResult.ROTATED, MissionService.accept(p, offers.get(0).id, period - 1, now), "old board");
		Mission notOnBoard = null;
		for (Mission m : MissionConfig.get().pool()) {
			if (!MissionService.isOnBoard(id, period, m)) {
				notOnBoard = m;
				break;
			}
		}
		if (notOnBoard != null) eq(AcceptResult.NOT_ON_BOARD, MissionService.accept(p, notOnBoard.id, period, now), "not on the board");

		for (int i = 0; i < max; i++) eq(AcceptResult.OK, MissionService.accept(p, offers.get(i).id, period, now), "accept " + i);
		PlayerState s = MissionStore.peek(id);
		eq(max, s.active.size(), "max active");
		eq(AcceptResult.ALREADY_ACTIVE, MissionService.assign(p, offers.get(1), 0, now), "same mission not active twice");
		eq(AcceptResult.TAKEN, MissionService.accept(p, offers.get(0).id, period, now), "already accepted");
		eq(AcceptResult.FULL, MissionService.accept(p, offers.get(max).id, period, now), "all slots taken");
		eq(max, s.active.size(), "nothing added");

		// abandoning frees the slot but starts the cooldown; an abandoned mission stays used up for this round
		ActiveMission first = s.active.get(0);
		check(MissionService.cancel(p, first.instance, now), "cancel");
		isFalse(MissionService.cancel(p, first.instance, now), "second abandon changes nothing");
		eq(max - 1, s.active.size(), "slot free");
		long cd = MissionConfig.cancelCooldownMs();
		if (cd > 0) {
			eq(AcceptResult.COOLDOWN, MissionService.accept(p, offers.get(max).id, period, now), "cooldown");
			eq(now + cd, s.cooldownUntil, "cooldown end");
			s.cooldownUntil = now; // "fast forward" without a round change
		}
		eq(AcceptResult.TAKEN, MissionService.accept(p, offers.get(0).id, period, now), "abandoned one stays used up");
		eq(AcceptResult.OK, MissionService.accept(p, offers.get(max).id, period, now), "after the cooldown");
		eq(max, s.active.size(), "full again");
		check(s.taken.contains(offers.get(max).id) && s.taken.size() == max + 1, "taken remembers all of the round's accepts");

		// a round change empties "taken", active missions stay
		long next = MissionService.periodEnd(period);
		PlayerState s2 = MissionService.state(id, next);
		eq(0, s2.taken.size(), "new round: taken empty");
		eq(max, s2.active.size(), "active missions survive the rotation");
		ctx.detail("max=" + max + ", cooldown " + TimeUtil.formatDuration(cd));
	}

	private static void deliveryGuardCap(TestContext ctx) {
		FakePlayer p = ctx.player("MissTestA");
		UUID id = p.getUUID();
		ctx.cleanup(() -> PriceModifiers.removeSource(MOD_SOURCE));
		long now = System.currentTimeMillis();
		Item iron = net.minecraft.world.item.Items.IRON_INGOT;
		// absurdly high desired price: the price cap applies
		Mission m = deliverMission("t_iron", iron, 10, 1_000_000_000L);
		ActiveMission a = assign(p, m, now);
		long cap = PriceGuard.maxNpcOrderPrice(id, iron, Services.marketValue(iron).orElse(0));
		check(cap > 0, "iron has a price cap");
		eq(cap, MissionService.deliveryPrice(id, m, a.def), "price per piece = price cap");

		Items.giveOrDrop(p, new ItemStack(iron, 6));
		long before = EconomyService.balance(id);
		MissionService.DeliverResult r = MissionService.deliver(p, a.instance, now);
		eq(DeliverStatus.OK, r.status(), "delivery ok");
		eq(6, r.delivered(), "6 delivered");
		eq(6 * cap, r.paid(), "paid 6 × price cap");
		eq(before + 6 * cap, EconomyService.balance(id), "balance");
		eq(0, Items.countPristine(p, iron), "items removed");
		eq(6, a.progress, "progress 6/10");

		// crash -50%: the price cap is recomputed on EVERY delivery
		PriceModifiers.set(MOD_SOURCE, iron, 0.5, 0, 60_000, 0, "Selftest");
		long cap2 = PriceGuard.maxNpcOrderPrice(id, iron, Services.marketValue(iron).orElse(0));
		check(cap2 < cap, "crash lowers the price cap (" + cap2 + " < " + cap + ")");
		Items.giveOrDrop(p, new ItemStack(iron, 10));
		long before2 = EconomyService.balance(id);
		r = MissionService.deliver(p, a.instance, now);
		eq(DeliverStatus.OK, r.status(), "second delivery ok");
		eq(4, r.delivered(), "only the rest (4) taken");
		eq(4 * cap2, r.paid(), "new cap applies");
		eq(before2 + 4 * cap2, EconomyService.balance(id), "balance 2");
		isTrue(r.completed(), "mission completed");
		eq(6, Items.countPristine(p, iron), "6 iron stay in the inventory");
		eq(0, MissionStore.peek(id).active.size(), "no longer active");
		eq(6 * cap + 4 * cap2, MissionStore.peek(id).earnedCents, "earned statistic");
		eq(List.of(), ctx.drops(p), "nothing dropped");

		// shop item: never above the pivot; a small desired price stays small
		Item stone = net.minecraft.world.item.Items.STONE;
		long pivot = PriceGuard.pivotPrice(stone);
		Mission stoneMission = deliverMission("t_stein", stone, 64, 1_000_000_000L);
		if (pivot >= 0) check(MissionService.deliveryPrice(id, stoneMission, stoneMission.def) <= pivot, "stone ≤ pivot");
		Mission cheap = deliverMission("t_cheap", iron, 5, 1);
		eq(1L, MissionService.deliveryPrice(id, cheap, cheap.def), "desired price below the cap stays");
		ctx.detail("cap=" + cap + " crash=" + cap2);
	}

	private static void deliveryPristine(TestContext ctx) {
		FakePlayer p = ctx.player("MissTestB");
		UUID id = p.getUUID();
		long now = System.currentTimeMillis();
		Item gold = net.minecraft.world.item.Items.GOLD_INGOT;
		ActiveMission a = assign(p, deliverMission("t_gold", gold, 5, 0), now);

		eq(DeliverStatus.NONE, MissionService.deliver(p, a.instance, now).status(), "without items");
		Items.giveOrDrop(p, renamed(gold, 3));
		isFalse(Items.isPristine(renamed(gold, 1)), "renamed isn't pristine");
		eq(DeliverStatus.ONLY_MODIFIED, MissionService.deliver(p, a.instance, now).status(), "only renamed items");
		eq(3, Items.count(p, s -> s.is(gold)), "renamed ones stay");
		eq(0, a.progress, "no progress");

		// account limit: credit impossible, nothing taken
		Items.giveOrDrop(p, new ItemStack(gold, 2));
		EconomyService.set(id, Money.MAX - 1);
		eq(DeliverStatus.PAYMENT_FAILED, MissionService.deliver(p, a.instance, now).status(), "account limit");
		eq(2, Items.countPristine(p, gold), "items unchanged");
		eq(0, a.progress, "no progress at the account limit");
		EconomyService.set(id, 0);

		// delivery from an expired mission: rejected, items stay
		a.expiresAt = now - 1;
		eq(DeliverStatus.EXPIRED, MissionService.deliver(p, a.instance, now).status(), "expired");
		eq(2, Items.countPristine(p, gold), "items stay on expiry");
		eq(DeliverStatus.NOT_ACTIVE, MissionService.deliver(p, a.instance, now).status(), "no longer active afterwards");

		ActiveMission st = assign(p, statMission("t_stat_type", Tier.EASY, 3, 100, "killed:zombie"), now);
		eq(DeliverStatus.WRONG_TYPE, MissionService.deliver(p, st.instance, now).status(), "no delivery");
		eq(0L, EconomyService.balance(id), "no money moved");
		eq(List.of(), ctx.drops(p), "nothing dropped");
	}

	/** Creative and spectator: delivery rejected (items from nothing); items, progress and balance stay. */
	private static void deliveryCreativeBlocked(TestContext ctx) {
		FakePlayer p = ctx.player("MissCreative");
		UUID id = p.getUUID();
		if (p.gameMode() != GameType.SURVIVAL) p.setGameMode(GameType.SURVIVAL);
		ctx.cleanup(() -> p.setGameMode(GameType.SURVIVAL));
		long now = System.currentTimeMillis();
		Item iron = net.minecraft.world.item.Items.IRON_INGOT;
		ActiveMission a = assign(p, deliverMission("t_creative", iron, 10, 0), now);
		EconomyService.set(id, 0);
		Items.giveOrDrop(p, new ItemStack(iron, 5));
		for (GameType mode : new GameType[] {GameType.CREATIVE, GameType.SPECTATOR}) {
			p.setGameMode(mode);
			eq(DeliverStatus.BLOCKED, MissionService.deliver(p, a.instance, now).status(), "rejected: " + mode.getName());
			eq(5, Items.countPristine(p, iron), "items stay: " + mode.getName());
			eq(0, a.progress, "no progress: " + mode.getName());
			eq(0L, EconomyService.balance(id), "no money: " + mode.getName());
		}
		p.setGameMode(GameType.SURVIVAL);
		MissionService.DeliverResult r = MissionService.deliver(p, a.instance, now);
		eq(DeliverStatus.OK, r.status(), "survival: delivery ok");
		eq(5, r.delivered(), "5 delivered");
		check(r.paid() > 0, "paid");
		eq(r.paid(), EconomyService.balance(id), "balance = payment");
		eq(List.of(), ctx.drops(p), "nothing dropped");
	}

	private static void deliveryNoKeys(TestContext ctx) {
		FakePlayer p = ctx.player("MissTestA");
		UUID id = p.getUUID();
		long now = System.currentTimeMillis();
		Item coal = net.minecraft.world.item.Items.COAL;
		// config: a delivery with its own keys is rejected
		MissionConfig.MissionDef keyed = def("t_lief_key", Tier.HARD, Mission.Type.DELIVER, 1, 0);
		keyed.item = "minecraft:diamond";
		keyed.keys = 1;
		keyed.keyTier = CrateKeys.RARE;
		check(MissionConfig.validate(keyed) != null, "delivery with keys rejected");
		keyed.keys = 0;
		eq(null, MissionConfig.validate(keyed), "delivery without keys valid");

		// the tier's keys (hard, elite) don't apply to deliveries
		long rare = CrateKeys.count(id, CrateKeys.RARE);
		long epic = CrateKeys.count(id, CrateKeys.EPIC);
		for (Tier tier : List.of(Tier.HARD, Tier.ELITE)) {
			Mission m = deliverMission("t_lief_" + tier.id, tier, coal, 2, 0);
			eq(0, MissionService.keys(m), "no keys for delivery " + tier.id);
			ActiveMission a = assign(p, m, now);
			eq(0, a.keys, "snapshot without keys " + tier.id);
			Items.giveOrDrop(p, new ItemStack(coal, 2));
			MissionService.DeliverResult r = MissionService.deliver(p, a.instance, now);
			eq(DeliverStatus.OK, r.status(), "delivery " + tier.id);
			isTrue(r.completed(), "completed " + tier.id);
		}
		eq(rare, CrateKeys.count(id, CrateKeys.RARE), "no rare keys for deliveries");
		eq(epic, CrateKeys.count(id, CrateKeys.EPIC), "no epic keys for deliveries");

		// older snapshot with keys: completion still gives none
		ActiveMission old = assign(p, deliverMission("t_deliver_old", Tier.ELITE, coal, 1, 0), now);
		old.keys = 1;
		old.keyTier = CrateKeys.EPIC;
		isTrue(MissionService.complete(id, p, MissionStore.peek(id), old), "completion of an old snapshot");
		eq(epic, CrateKeys.count(id, CrateKeys.EPIC), "old snapshot: no key");

		// other mission types in the same tier keep the tier's keys
		Mission stat = statMission("t_lief_stat", Tier.HARD, 1, 100, "killed:zombie");
		eq((int) Tier.HARD.settings().keys, MissionService.keys(stat), "stat mission keeps tier keys");
		eq(List.of(), ctx.drops(p), "nothing dropped");
	}

	/**
	 * Stat difference (crafting, fishing) from accepting on; hunting and mining count through events (separate tests).
	 */
	private static void statDelta(TestContext ctx) {
		FakePlayer p = ctx.player("MissTestA");
		UUID id = p.getUUID();
		Stat<?> stat = Stats.ITEM_CRAFTED.get(net.minecraft.world.item.Items.BREAD);
		Stat<?> stray = Stats.CUSTOM.get(Stats.FISH_CAUGHT);
		restoreStat(ctx, p, stat);
		restoreStat(ctx, p, stray);
		setStat(p, stat, 100);
		setStat(p, stray, 7);
		long now = System.currentTimeMillis();
		ActiveMission a = assign(p, statMission("t_crafting", Tier.EASY, 5, 12_345, "crafted:bread", "custom:fish_caught"), now);
		eq(107L, a.baseline, "baseline = sum at accepting");
		MissionService.update(p, now);
		eq(0, a.progress, "crafting before accepting doesn't count");
		setStat(p, stat, 102);
		setStat(p, stray, 8);
		MissionService.update(p, now);
		eq(3, a.progress, "difference across both stats");
		eq(1, MissionStore.peek(id).active.size(), "still active");
		long before = EconomyService.balance(id);
		setStat(p, stat, 104);
		MissionService.update(p, now);
		eq(0, MissionStore.peek(id).active.size(), "completed");
		eq(before + 12_345, EconomyService.balance(id), "reward paid");
		MissionService.update(p, now);
		setStat(p, stat, 200);
		MissionService.update(p, now);
		eq(before + 12_345, EconomyService.balance(id), "not a second time");
		eq(1L, MissionStore.peek(id).completed, "1 completed");

		// counter reset (e.g. stats file deleted): progress never negative
		ActiveMission b = assign(p, statMission("t_reset", Tier.EASY, 5, 100, "crafted:bread"), now);
		setStat(p, stat, 10);
		MissionService.update(p, now);
		eq(0, b.progress, "progress stays ≥ 0");
		eq(1, MissionStore.peek(id).active.size(), "not completed");
	}

	/**
	 * Mining counts only through break events of natural blocks, with the correct tool, not in creative; the stat
	 * doesn't matter.
	 */
	private static void minedNaturalOnly(TestContext ctx) {
		FakePlayer p = ctx.player("MissTestB");
		UUID id = p.getUUID();
		ServerLevel level = ctx.overworld();
		Stat<?> stat = Stats.BLOCK_MINED.get(Blocks.STONE);
		restoreStat(ctx, p, stat);
		setStat(p, stat, 50);
		BlockPos placed = placedPos(ctx, level, 0);
		BlockPos natural = freePos(level, 5);
		long now = System.currentTimeMillis();
		ActiveMission a = assign(p, statMission("t_stein", Tier.EASY, 3, 500, "mined:stone"), now);
		ActiveMission other = assign(p, statMission("t_dirt", Tier.EASY, 3, 500, "mined:dirt"), now);
		eq(0L, a.baseline, "baseline without mining stat");
		isTrue(a.minedEvents, "new mission counts mining through events");

		// without a pickaxe vanilla gives no mined stat for stone, so it doesn't count; dirt needs no tool
		MissionService.onBlockBroken(p, level, natural, Blocks.STONE.defaultBlockState());
		eq(0L, a.mined, "stone without a pickaxe doesn't count");
		MissionService.onBlockBroken(p, level, natural, Blocks.DIRT.defaultBlockState());
		eq(1L, other.mined, "natural dirt by hand counts");
		holdPickaxe(ctx, p);

		// placed stone: stat +1, but doesn't count
		setStat(p, stat, 51);
		MissionService.onBlockBroken(p, level, placed, Blocks.STONE.defaultBlockState());
		MissionService.update(p, now);
		eq(0L, a.mined, "placed block doesn't count");
		eq(0, a.progress, "no progress from a placed block");
		// natural stone counts, however much the stat rises
		setStat(p, stat, 60);
		MissionService.onBlockBroken(p, level, natural, Blocks.STONE.defaultBlockState());
		MissionService.onBlockBroken(p, level, natural, Blocks.STONE.defaultBlockState());
		MissionService.update(p, now);
		eq(2L, a.mined, "2 natural stones");
		eq(2, a.progress, "progress = counted mining, not the stat");
		MissionService.onBlockBroken(p, level, placed, Blocks.DIRT.defaultBlockState());
		eq(1L, other.mined, "placed dirt doesn't count");
		eq(2L, a.mined, "dirt doesn't count for stone");
		// creative: vanilla gives no mined stat
		p.getAbilities().instabuild = true;
		try {
			MissionService.onBlockBroken(p, level, natural, Blocks.STONE.defaultBlockState());
		} finally {
			p.getAbilities().instabuild = false;
		}
		eq(2L, a.mined, "creative doesn't count");
		long before = EconomyService.balance(id);
		MissionService.onBlockBroken(p, level, natural, Blocks.STONE.defaultBlockState());
		MissionService.update(p, now);
		eq(before + 500, EconomyService.balance(id), "completed after 3 natural");
		eq(1, MissionStore.peek(id).active.size(), "only the dirt mission stays");
	}

	/**
	 * Veinminer calls {@code awardStat(BLOCK_MINED, n - 1)} for a chain and removes the other blocks with no
	 * PlayerBlockBreakEvents.AFTER, self-placed ones too (silk touch ore). Simulated without AFTER events: no progress,
	 * no money, no key; on a natural chain only the first block counts.
	 */
	private static void minedVeinminerIgnored(TestContext ctx) {
		FakePlayer p = ctx.player("MissTestB");
		UUID id = p.getUUID();
		ServerLevel level = ctx.overworld();
		Stat<?> ore = Stats.BLOCK_MINED.get(Blocks.DIAMOND_ORE);
		Stat<?> bread = Stats.ITEM_CRAFTED.get(net.minecraft.world.item.Items.BREAD);
		restoreStat(ctx, p, ore);
		restoreStat(ctx, p, bread);
		setStat(p, ore, 5);
		setStat(p, bread, 3);
		BlockPos placed = placedPos(ctx, level, 10);
		BlockPos natural = freePos(level, 13);
		holdPickaxe(ctx, p);
		long now = System.currentTimeMillis();
		ActiveMission a = assign(p, statMission("t_vein", Tier.HARD, 16, 350_000, "mined:diamond_ore", "mined:deepslate_diamond_ore"), now);
		ActiveMission mixed = assign(p, statMission("t_vein_mix", Tier.EASY, 10, 500, "crafted:bread", "mined:diamond_ore"), now);
		eq(3L, mixed.baseline, "mixed: baseline only from the crafting stat");
		long balance = EconomyService.balance(id);
		long rare = CrateKeys.count(id, CrateKeys.RARE);

		// chain of 17 placed silk touch ores: first block through the vanilla flow (AFTER + Block#playerDestroy +1),
		// the other 16 only through Veinminer's awardStat, with no AFTER events
		MissionService.onBlockBroken(p, level, placed, Blocks.DIAMOND_ORE.defaultBlockState());
		p.getStats().increment(p, ore, 1);
		p.getStats().increment(p, ore, 16);
		MissionService.update(p, now);
		eq(0, a.progress, "Veinminer chain of placed ores: no progress");
		eq(0, mixed.progress, "mixed: mining stat doesn't count");
		eq(2, MissionStore.peek(id).active.size(), "both still active");
		eq(balance, EconomyService.balance(id), "no money");
		eq(rare, CrateKeys.count(id, CrateKeys.RARE), "no key");

		// natural chain of 8 ores: only the self-mined first block counts
		MissionService.onBlockBroken(p, level, natural, Blocks.DIAMOND_ORE.defaultBlockState());
		p.getStats().increment(p, ore, 1);
		p.getStats().increment(p, ore, 7);
		MissionService.update(p, now);
		eq(1, a.progress, "natural chain: only the first block");
		eq(1, mixed.progress, "mixed: first block counts");
		// the mixed mission's other stats keep counting through the difference
		p.getStats().increment(p, bread, 2);
		MissionService.update(p, now);
		eq(3, mixed.progress, "mixed: crafting difference + mining");
		eq(2, MissionStore.peek(id).active.size(), "both still active");
		eq(balance, EconomyService.balance(id), "no money yet");
		eq(rare, CrateKeys.count(id, CrateKeys.RARE), "no key yet");
	}

	/** Missions accepted before the conversion (baseline with mining and hunting stats) keep their progress. */
	private static void minedLegacyMigration(TestContext ctx) {
		FakePlayer p = ctx.player("MissTestB");
		ServerLevel level = ctx.overworld();
		List<Entity> created = new ArrayList<>();
		ctx.cleanup(() -> created.forEach(Entity::discard));
		Stat<?> stone = Stats.BLOCK_MINED.get(Blocks.STONE);
		Stat<?> zombie = Stats.ENTITY_KILLED.get(EntityType.ZOMBIE);
		restoreStat(ctx, p, stone);
		restoreStat(ctx, p, zombie);
		setStat(p, stone, 40);
		setStat(p, zombie, 10);
		BlockPos natural = freePos(level, 16);
		holdPickaxe(ctx, p);
		long now = System.currentTimeMillis();

		// mixed, accepted before both rules: 35 stone + 10 zombies at accepting, 5 stone since, so progress 5 (old
		// calculation)
		ActiveMission a = assign(p, statMission("t_old", Tier.EASY, 20, 500, "mined:stone", "killed:zombie"), now);
		a.minedEvents = false;
		a.killEvents = false;
		a.baseline = 45;
		a.mined = 0;
		a.progress = 5;
		MissionService.update(p, now);
		isTrue(a.minedEvents && a.killEvents, "switched");
		eq(5, a.progress, "progress stays");
		eq(5L, a.mined, "existing progress carried over");
		eq(0L, a.killMilli, "hunting difference was 0");
		eq(0L, a.baseline, "baseline without mining and hunting stats");
		p.getStats().increment(p, stone, 30);
		p.getStats().increment(p, zombie, 4);
		MissionService.update(p, now);
		eq(5, a.progress, "mining and hunting stats no longer count after the conversion");
		MissionService.onBlockBroken(p, level, natural, Blocks.STONE.defaultBlockState());
		MissionHooks.onKill(p, mob(created, EntityType.ZOMBIE.create(level, EntitySpawnReason.NATURAL)));
		MissionService.update(p, now);
		eq(7, a.progress, "mining event + kill event");

		// hunting only, accepted after the mining rule (baseline without mining, with hunting): old difference 2 gives
		// 2 kills
		ActiveMission b = assign(p, statMission("t_old_hunt", Tier.EASY, 20, 500, "killed:zombie"), now);
		b.killEvents = false;
		b.baseline = p.getStats().getValue(zombie) - 2L;
		b.progress = 2;
		MissionService.update(p, now);
		isTrue(b.killEvents, "converted (hunting)");
		eq(2000L, b.killMilli, "existing hunting difference carried over");
		eq(0L, b.baseline, "baseline without hunting stat");
		eq(2, b.progress, "progress stays (hunting)");
		p.getStats().increment(p, zombie, 2);
		MissionService.update(p, now);
		eq(2, b.progress, "hunting stat no longer counts");
		MissionHooks.onKill(p, mob(created, EntityType.ZOMBIE.create(level, EntitySpawnReason.NATURAL)));
		MissionService.update(p, now);
		eq(3, b.progress, "kill event counts");

		// first mining event before the first check: convert first, then count
		ActiveMission c = assign(p, statMission("t_alt_stein", Tier.EASY, 20, 500, "mined:stone"), now);
		c.minedEvents = false;
		c.baseline = p.getStats().getValue(stone) - 4L;
		c.mined = 0;
		c.progress = 4;
		MissionService.onBlockBroken(p, level, natural, Blocks.STONE.defaultBlockState());
		isTrue(c.minedEvents, "converted (event)");
		eq(0L, c.baseline, "baseline without mining stat (event)");
		eq(5L, c.mined, "4 old + 1 new");
		MissionService.update(p, now);
		eq(5, c.progress, "progress 5");
	}

	/**
	 * Hunt missions must not read vanilla {@code ENTITY_KILLED}: a respawned ender dragon (4 crystals from shop
	 * ingredients, about $124) would earn "elite_dragon" ($15,000 + epic key), and shulkers from a splitting farm would
	 * fill "elite_shulker_hunt". Only kill events with an origin check count: respawned, bred, summoned, spawn egg give
	 * 0; spawner × spawnerKillFactor; the stat alone moves nothing.
	 */
	private static void killOrigin(TestContext ctx) {
		FakePlayer p = ctx.player("MissHunter");
		UUID id = p.getUUID();
		ServerLevel level = ctx.overworld();
		if (p.gameMode() != GameType.SURVIVAL) p.setGameMode(GameType.SURVIVAL);
		ctx.cleanup(() -> p.setGameMode(GameType.SURVIVAL));
		List<Entity> created = new ArrayList<>();
		ctx.cleanup(() -> created.forEach(Entity::discard));
		Stat<?> dragonStat = Stats.ENTITY_KILLED.get(EntityType.ENDER_DRAGON);
		Stat<?> shulkerStat = Stats.ENTITY_KILLED.get(EntityType.SHULKER);
		restoreStat(ctx, p, dragonStat);
		restoreStat(ctx, p, shulkerStat);
		long now = System.currentTimeMillis();
		Mission dragonMission = statMission("t_dragon", Tier.ELITE, 1, 1_500_000, "killed:ender_dragon");
		ActiveMission dragon = assign(p, dragonMission, now);
		ActiveMission shulker = assign(p, statMission("t_shulker", Tier.ELITE, 3, 800_000, "killed:shulker"), now);
		isTrue(dragon.killEvents && shulker.killEvents, "new missions count kills through events");
		eq(0L, dragon.baseline, "baseline without hunting stat");
		EconomyService.set(id, 0);
		String keyTier = MissionService.keyTier(dragonMission);
		int keys = MissionService.keys(dragonMission);
		long keysBefore = keys > 0 ? CrateKeys.count(id, keyTier) : 0;

		// respawned dragon (tag from the EndDragonFight mixin): vanilla raises the stat, the mission doesn't count
		LivingEntity respawned = mob(created, EntityType.ENDER_DRAGON.create(level, EntitySpawnReason.EVENT));
		respawned.addTag(MobOrigin.RESPAWNED);
		eq(0L, MissionService.killMilli(respawned.getTags()), "respawned dragon counts 0");
		MissionHooks.onKill(p, respawned);
		p.getStats().increment(p, dragonStat, 1);
		MissionService.update(p, now, () -> true);
		eq(0, dragon.progress, "respawned dragon: no progress");
		eq(0L, dragon.killMilli, "respawned dragon: nothing counted");
		eq(2, MissionStore.peek(id).active.size(), "dragon mission still active");
		eq(0L, EconomyService.balance(id), "respawned dragon: no money");
		if (keys > 0) eq(keysBefore, CrateKeys.count(id, keyTier), "respawned dragon: no key");

		// shulker splitting (BREEDING, tagged by the EntityType mixin), summoned, spawn egg: never count
		LivingEntity bred = mob(created, EntityType.SHULKER.create(level, EntitySpawnReason.BREEDING));
		isTrue(bred.getTags().contains(MobOrigin.BRED), "shulker split tagged (EntityType mixin active)");
		LivingEntity egg = mob(created, EntityType.SHULKER.create(level, EntitySpawnReason.SPAWN_ITEM_USE));
		isTrue(egg.getTags().contains(MobOrigin.SPAWN_EGG), "spawn egg shulker tagged");
		LivingEntity summoned = mob(created, EntityType.SHULKER.create(level, EntitySpawnReason.NATURAL));
		summoned.addTag(MobOrigin.SUMMONED);
		for (int i = 0; i < 5; i++) MissionHooks.onKill(p, bred);
		MissionHooks.onKill(p, egg);
		MissionHooks.onKill(p, summoned);
		p.getStats().increment(p, shulkerStat, 7);
		MissionService.update(p, now, () -> true);
		eq(0L, shulker.killMilli, "bred, spawn egg and summoned shulkers don't count");
		eq(0, shulker.progress, "shulker stat alone moves nothing");
		// spawner, and spawner + bred (zombie reinforcement inherits both): spawner partly, with bred 0
		long perSpawner = Math.round(MissionConfig.spawnerKillFactor() * 1000);
		eq(perSpawner, MissionService.killMilli(Set.of(MobOrigin.SPAWNER)), "spawner mob × spawnerKillFactor");
		eq(0L, MissionService.killMilli(Set.of(MobOrigin.SPAWNER, MobOrigin.BRED)), "spawner + bred gives 0");
		eq(1000L, MissionService.killMilli(Set.of("other_tag")), "foreign tags don't matter");
		LivingEntity fromSpawner = mob(created, EntityType.SHULKER.create(level, EntitySpawnReason.NATURAL));
		fromSpawner.addTag(MobOrigin.SPAWNER);
		MissionHooks.onKill(p, fromSpawner);
		eq(perSpawner, shulker.killMilli, "spawner shulker counts partly");
		MissionHooks.onKill(p, mob(created, EntityType.ZOMBIE.create(level, EntitySpawnReason.NATURAL)));
		eq(perSpawner, shulker.killMilli, "zombie doesn't count for shulker");
		// natural shulker counts fully, not in creative or spectator
		LivingEntity natural = mob(created, EntityType.SHULKER.create(level, EntitySpawnReason.NATURAL));
		isTrue(natural.getTags().isEmpty(), "natural shulker untagged");
		for (GameType mode : new GameType[] {GameType.CREATIVE, GameType.SPECTATOR}) {
			p.setGameMode(mode);
			MissionHooks.onKill(p, natural);
			eq(perSpawner, shulker.killMilli, "no kill in mode " + mode.getName());
		}
		p.setGameMode(GameType.SURVIVAL);
		MissionHooks.onKill(p, natural);
		MissionService.update(p, now, () -> true);
		eq(perSpawner + 1000, shulker.killMilli, "natural shulker counts");
		eq(1 + perSpawner / 1000, shulker.progress, "progress = whole kills");
		// no non-players (mob kills mob) and no null victim
		MissionHooks.onKill(mob(created, EntityType.ZOMBIE.create(level, EntitySpawnReason.NATURAL)), natural);
		MissionHooks.onKill(p, null);
		eq(perSpawner + 1000, shulker.killMilli, "only player kills");
		// an expired mission counts no more kills (the next tick decides expiry)
		long expires = shulker.expiresAt;
		shulker.expiresAt = now - 1;
		MissionHooks.onKill(p, natural);
		eq(perSpawner + 1000, shulker.killMilli, "expired: no kill");
		shulker.expiresAt = expires;

		// world's first dragon (untagged): counts, completion pays money + key exactly once
		LivingEntity first = mob(created, EntityType.ENDER_DRAGON.create(level, EntitySpawnReason.EVENT));
		isTrue(first.getTags().isEmpty(), "first dragon untagged");
		MissionHooks.onKill(p, first);
		MissionService.update(p, now, () -> true);
		isFalse(MissionStore.peek(id).active.contains(dragon), "dragon mission completed");
		eq(1_500_000L, EconomyService.balance(id), "reward for the first dragon");
		if (keys > 0) eq(keysBefore + keys, CrateKeys.count(id, keyTier), "elite key exactly once");
		MissionHooks.onKill(p, first);
		MissionService.update(p, now, () -> true);
		eq(1_500_000L, EconomyService.balance(id), "not a second time");
		if (keys > 0) eq(keysBefore + keys, CrateKeys.count(id, keyTier), "no second key");
		ctx.detail("spawner mob counts " + perSpawner + "/1000");
	}

	/**
	 * After the first defeated dragon every later one is a respawn and doesn't count: pure dragon missions stop being
	 * offered and running ones get removed without cooldown (a target already reached still pays first).
	 */
	private static void killDragonOnlyFirst(TestContext ctx) {
		FakePlayer p = ctx.player("MissDragon");
		UUID id = p.getUUID();
		ServerLevel level = ctx.overworld();
		List<Entity> created = new ArrayList<>();
		ctx.cleanup(() -> created.forEach(Entity::discard));
		Mission dragonOnly = statMission("t_dragon_only", Tier.ELITE, 1, 1_000, "killed:ender_dragon");
		isTrue(dragonOnly.dragonOnly(), "pure dragon hunt detected");
		isFalse(statMission("t_dragon_wither", Tier.ELITE, 1, 1_000, "killed:ender_dragon", "killed:wither").dragonOnly(), "dragon + wither not");
		isFalse(statMission("t_shulker2", Tier.ELITE, 1, 1_000, "killed:shulker").dragonOnly(), "shulker not");

		// board: without an available dragon no pure dragon mission; the other offers stay, the slot moves up
		long period = MissionService.period(System.currentTimeMillis());
		int offers = Tier.ELITE.settings().offers;
		long others = MissionConfig.candidates(Tier.ELITE).stream().filter(m -> !m.dragonOnly()).count();
		int found = 0;
		for (int i = 0; i < 400; i++) {
			List<Mission> with = MissionService.board(id, period + i, Tier.ELITE, true);
			List<Mission> without = MissionService.board(id, period + i, Tier.ELITE, false);
			check(without.stream().noneMatch(Mission::dragonOnly), "no dragon offer without a dragon (round +" + i + ")");
			eq((int) Math.min(offers, others), without.size(), "offers without a dragon (round +" + i + ")");
			List<Mission> kept = with.stream().filter(m -> !m.dragonOnly()).toList();
			if (kept.size() < with.size()) found++;
			eq(ids(Map.of(Tier.ELITE, kept)), ids(Map.of(Tier.ELITE, without.subList(0, Math.min(kept.size(), without.size())))),
				"other offers unchanged (round +" + i + ")");
		}
		boolean inPool = MissionConfig.candidates(Tier.ELITE).stream().anyMatch(Mission::dragonOnly);
		if (inPool) check(found > 0, "dragon mission appears on the board with a dragon");
		boolean worldDragon = MissionService.dragonAvailable(ctx.server());
		var fight = ctx.server().getLevel(Level.END) == null ? null : ctx.server().getLevel(Level.END).getDragonFight();
		eq(fight != null && !fight.hasPreviouslyKilledDragon(), worldDragon, "dragon state from the End dimension");

		// running mission without an available dragon: removed, no money, no cooldown
		long now = System.currentTimeMillis();
		EconomyService.set(id, 0);
		PlayerState s = MissionService.state(id, now);
		long cooldown = s.cooldownUntil;
		ActiveMission a = assign(p, dragonOnly, now);
		MissionService.update(p, now, () -> true);
		isTrue(s.active.contains(a), "stays active with a dragon");
		MissionService.update(p, now, () -> false);
		isFalse(s.active.contains(a), "removed without a dragon");
		eq(cooldown, s.cooldownUntil, "no cooldown");
		eq(0L, EconomyService.balance(id), "no money");
		eq(0L, s.completed, "not completed");
		// already killed (first dragon), then the dragon counts as defeated: still pays
		ActiveMission b = assign(p, dragonOnly, now);
		MissionHooks.onKill(p, mob(created, EntityType.ENDER_DRAGON.create(level, EntitySpawnReason.EVENT)));
		MissionService.update(p, now, () -> false);
		isFalse(s.active.contains(b), "completed");
		eq(1_000L, EconomyService.balance(id), "reached target paid");
		eq(1L, s.completed, "1 completed");
		ctx.detail("dragon " + (worldDragon ? "not defeated yet" : "already defeated") + ", dragon offer in " + found + "/400 rounds");
	}

	private static void treasure(TestContext ctx) {
		FakePlayer p = ctx.player("MissTestA");
		UUID id = p.getUUID();
		ItemStack lureRod = new ItemStack(net.minecraft.world.item.Items.FISHING_ROD);
		lureRod.enchant(ctx.server().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.LURE), 2);
		isTrue(Mission.isTreasure(new ItemStack(net.minecraft.world.item.Items.NAME_TAG)), "name tag");
		isTrue(Mission.isTreasure(new ItemStack(net.minecraft.world.item.Items.SADDLE)), "saddle");
		isTrue(Mission.isTreasure(new ItemStack(net.minecraft.world.item.Items.NAUTILUS_SHELL)), "nautilus shell");
		isTrue(Mission.isTreasure(new ItemStack(net.minecraft.world.item.Items.ENCHANTED_BOOK)), "book");
		isTrue(Mission.isTreasure(new ItemStack(net.minecraft.world.item.Items.BOW)), "bow");
		isTrue(Mission.isTreasure(lureRod), "enchanted rod");
		isFalse(Mission.isTreasure(new ItemStack(net.minecraft.world.item.Items.FISHING_ROD)), "junk rod");
		isFalse(Mission.isTreasure(new ItemStack(net.minecraft.world.item.Items.COD)), "fish");
		isFalse(Mission.isTreasure(new ItemStack(net.minecraft.world.item.Items.LEATHER_BOOTS)), "junk");
		isFalse(Mission.isTreasure(ItemStack.EMPTY), "empty");

		long now = System.currentTimeMillis();
		MissionConfig.MissionDef d = def("t_treasure", Tier.HARD, Mission.Type.TREASURE, 3, 7_000);
		ActiveMission a = assign(p, compile(d), now);
		eq(1, a.keys, "hard: 1 key");
		MissionService.onFishingLoot(p, List.of(new ItemStack(net.minecraft.world.item.Items.NAME_TAG), new ItemStack(net.minecraft.world.item.Items.COD)));
		eq(1, a.progress, "1 treasure");
		MissionService.onFishingLoot(p, List.of(new ItemStack(net.minecraft.world.item.Items.SALMON)));
		eq(1, a.progress, "fish doesn't count");
		long before = EconomyService.balance(id);
		long keys = CrateKeys.count(id, CrateKeys.RARE);
		MissionService.onFishingLoot(p, List.of(new ItemStack(net.minecraft.world.item.Items.SADDLE), lureRod, new ItemStack(net.minecraft.world.item.Items.BOW)));
		eq(0, MissionStore.peek(id).active.size(), "completed");
		eq(before + 7_000, EconomyService.balance(id), "reward");
		eq(keys + 1, CrateKeys.count(id, CrateKeys.RARE), "rare key");
		MissionService.onFishingLoot(p, List.of(new ItemStack(net.minecraft.world.item.Items.SADDLE)));
		eq(before + 7_000, EconomyService.balance(id), "not a second time");
	}

	private static void explore(TestContext ctx) {
		FakePlayer p = ctx.player("MissTestB");
		UUID id = p.getUUID();
		ServerLevel overworld = ctx.overworld();
		ServerLevel nether = ctx.server().getLevel(Level.NETHER);
		BlockPos spawn = p.blockPosition();
		overworld.getChunk(spawn); // spawn chunks aren't permanently loaded without players
		isTrue(overworld.isLoaded(spawn), "spawn chunk loaded");
		String biomeId = overworld.getBiome(spawn).unwrapKey().map(k -> k.identifier().toString()).orElseThrow();

		MissionConfig.MissionDef dimDef = def("t_nether", Tier.EASY, Mission.Type.EXPLORE, 1, 100);
		dimDef.dimension = "minecraft:the_nether";
		Mission dim = compile(dimDef);
		isFalse(dim.matchesLocation(overworld, spawn), "overworld isn't the nether");
		if (nether != null) {
			BlockPos np = new BlockPos(0, 64, 0);
			nether.getChunk(np); // load (or generate), so isLoaded is right
			if (nether.isLoaded(np)) isTrue(dim.matchesLocation(nether, np), "in the nether");
		}
		MissionConfig.MissionDef cityDef = def("t_city", Tier.HARD, Mission.Type.EXPLORE, 1, 100);
		cityDef.structure = "minecraft:ancient_city";
		isFalse(compile(cityDef).matchesLocation(overworld, spawn), "no ancient city at spawn");

		// spawn biome: whoever already stands there has to leave first
		MissionConfig.MissionDef biomeDef = def("t_biome", Tier.ELITE, Mission.Type.EXPLORE, 1, 4_321);
		biomeDef.biome = biomeId;
		Mission biome = compile(biomeDef);
		isTrue(biome.matchesLocation(overworld, spawn), "spawn biome " + biomeId);
		long now = System.currentTimeMillis();
		ActiveMission a = assign(p, biome, now);
		isFalse(a.armed, "already at the target when accepting: not armed yet");
		long before = EconomyService.balance(id);
		long epic = CrateKeys.count(id, CrateKeys.EPIC);
		MissionService.update(p, now);
		eq(1, MissionStore.peek(id).active.size(), "standing still doesn't count");
		p.setPos(29_000_000.5, 100, 29_000_000.5); // far away (not loaded = not at the target)
		MissionService.update(p, now);
		isTrue(a.armed, "left the target, armed");
		eq(1, MissionStore.peek(id).active.size(), "away from the target: not completed");
		p.setPos(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
		MissionService.update(p, now);
		eq(0, MissionStore.peek(id).active.size(), "entered the target, completed");
		eq(before + 4_321, EconomyService.balance(id), "reward");
		eq(epic + 1, CrateKeys.count(id, CrateKeys.EPIC), "elite: epic key");

		// only once per player: the same target (also under another ID) is blocked afterwards, no /home farming
		PlayerState s = MissionStore.peek(id);
		check(s.explored.containsKey(biome.exploreKey), "target recorded");
		eq(AcceptResult.EXPLORED, MissionService.assign(p, biome, 0, now), "target pays only once");
		MissionConfig.MissionDef copyDef = def("t_biome_copy", Tier.ELITE, Mission.Type.EXPLORE, 1, 9_999);
		copyDef.biome = biomeId;
		eq(AcceptResult.EXPLORED, MissionService.assign(p, compile(copyDef), 0, now), "same target under another ID");
		p.setPos(29_000_000.5, 100, 29_000_000.5);
		MissionService.update(p, now);
		p.setPos(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
		MissionService.update(p, now);
		eq(before + 4_321, EconomyService.balance(id), "returning pays nothing more");
		eq(epic + 1, CrateKeys.count(id, CrateKeys.EPIC), "no second key");
		ctx.detail("spawn biome " + biomeId);
	}

	private static void exploreOnce(TestContext ctx) {
		FakePlayer p = ctx.player("MissTestA");
		UUID id = p.getUUID();
		long now = System.currentTimeMillis();
		PlayerState s = MissionService.state(id, now);

		// structure instances: the same structure pays only once, also for another target (tag vs. single structure)
		MissionConfig.MissionDef tagDef = def("t_village_tag", Tier.EASY, Mission.Type.EXPLORE, 1, 1_000);
		tagDef.structure = "#minecraft:village";
		MissionConfig.MissionDef plainsDef = def("t_village_plains", Tier.MEDIUM, Mission.Type.EXPLORE, 1, 2_000);
		plainsDef.structure = "minecraft:village_plains";
		Mission tag = compile(tagDef);
		Mission plains = compile(plainsDef);
		check(!tag.exploreKey.equals(plains.exploreKey), "different targets");
		String village1 = "minecraft:village_plains@minecraft:overworld:10,-4";
		String village2 = "minecraft:village_plains@minecraft:overworld:55,31";
		ActiveMission a = assign(p, tag, now);
		ActiveMission b = assign(p, plains, now);
		long before = EconomyService.balance(id);
		isTrue(MissionService.reachTarget(id, p, s, a, tag, village1, now), "first village pays");
		eq(before + 1_000, EconomyService.balance(id), "reward first village");
		isFalse(MissionService.reachTarget(id, p, s, b, plains, village1, now), "same village doesn't pay again");
		isTrue(s.active.contains(b), "mission stays active (find another village)");
		eq(before + 1_000, EconomyService.balance(id), "no money for the same village");
		isTrue(MissionService.reachTarget(id, p, s, b, plains, village2, now), "other village pays");
		eq(before + 3_000, EconomyService.balance(id), "reward second village");
		isFalse(MissionService.reachTarget(id, p, s, b, plains, village2, now), "completed mission doesn't pay again");
		eq(List.of(village1, village2), s.exploredStructures, "instances recorded");

		// same target under two IDs (spelling doesn't matter): not active at once
		MissionConfig.MissionDef endA = def("t_end_a", Tier.HARD, Mission.Type.EXPLORE, 1, 500);
		endA.dimension = "minecraft:the_end";
		MissionConfig.MissionDef endB = def("t_end_b", Tier.HARD, Mission.Type.EXPLORE, 1, 500);
		endB.dimension = "the_end";
		Mission ea = compile(endA);
		Mission eb = compile(endB);
		eq(ea.exploreKey, eb.exploreKey, "target key independent of ID and spelling");
		ActiveMission ae = assign(p, ea, now);
		eq(AcceptResult.SAME_TARGET, MissionService.assign(p, eb, 0, now), "same target not active twice");
		// old save: target already explored, removed without reward or key
		s.explored.put(ea.exploreKey, now);
		long before2 = EconomyService.balance(id);
		long rare = CrateKeys.count(id, CrateKeys.RARE);
		isFalse(MissionService.reachTarget(id, p, s, ae, ea, "", now), "explored target doesn't pay");
		isFalse(s.active.contains(ae), "and gets removed");
		eq(before2, EconomyService.balance(id), "no money");
		eq(rare, CrateKeys.count(id, CrateKeys.RARE), "no key");

		// board: targets explored before the round stop being offered, those explored during the round stay
		s.explored.clear(); // the test targets above partly overlap with pool targets (End, #village)
		Mission pool = null;
		for (Mission m : MissionConfig.get().pool()) {
			if (m.type == Mission.Type.EXPLORE && MissionConfig.candidates(m.tier).contains(m)) {
				pool = m;
				break;
			}
		}
		if (pool != null) {
			int hours = MissionConfig.rotationHours();
			long period = MissionService.period(now);
			long on = Long.MIN_VALUE;
			for (long q = period; q < period + 400 && on == Long.MIN_VALUE; q++) {
				if (MissionService.isOnBoard(id, q, pool)) on = q;
			}
			check(on != Long.MIN_VALUE, "exploration " + pool.id + " appears on the board");
			List<String> boardBefore = boardIds(id, on, pool.tier);
			s.explored.put(pool.exploreKey, MissionService.periodStart(on, hours) + 1);
			eq(boardBefore, boardIds(id, on, pool.tier), "explored during the round: board unchanged");
			int later = 0;
			for (long q = on + 1; q <= on + 100; q++) if (MissionService.isOnBoard(id, q, pool)) later++;
			eq(0, later, "never offered again afterwards");
			s.explored.put(pool.exploreKey, MissionService.periodStart(on, hours) - 1);
			isFalse(MissionService.isOnBoard(id, on, pool), "explored before the round: not offered");
			eq(Math.min(pool.tier.settings().offers, MissionConfig.candidates(pool.tier).size() - 1),
				MissionService.board(id, on, pool.tier).size(), "free slot gets refilled");
		}
		ctx.detail("instances " + s.exploredStructures.size() + ", board sample " + (pool == null ? "-" : pool.id));
	}

	private static List<String> boardIds(UUID player, long period, Tier tier) {
		List<String> list = new ArrayList<>();
		for (Mission m : MissionService.board(player, period, tier)) list.add(m.id);
		return list;
	}

	private static void exactlyOnce(TestContext ctx) {
		FakePlayer p = ctx.player("MissTestA");
		UUID id = p.getUUID();
		long now = System.currentTimeMillis();
		long[] rare = {CrateKeys.count(id, CrateKeys.RARE)};
		long[] epic = {CrateKeys.count(id, CrateKeys.EPIC)};
		long[] common = {CrateKeys.count(id, CrateKeys.COMMON)};
		for (Tier tier : Tier.values()) {
			ActiveMission a = assign(p, statMission("t_once_" + tier.id, tier, 1, 1_000, "killed:zombie"), now);
			PlayerState s = MissionStore.peek(id);
			long before = EconomyService.balance(id);
			isTrue(MissionService.complete(id, p, s, a), "first payout " + tier.id);
			isFalse(MissionService.complete(id, p, s, a), "second payout " + tier.id);
			MissionService.update(p, now);
			eq(before + 1_000, EconomyService.balance(id), "money exactly once " + tier.id);
		}
		eq(rare[0] + 1, CrateKeys.count(id, CrateKeys.RARE), "exactly 1 rare key (hard)");
		eq(epic[0] + 1, CrateKeys.count(id, CrateKeys.EPIC), "exactly 1 epic key (elite)");
		eq(common[0], CrateKeys.count(id, CrateKeys.COMMON), "no common keys");
		eq(4L, MissionStore.peek(id).completed, "4 completed");
		eq(4_000L, MissionStore.peek(id).earnedCents, "4 × reward");
	}

	private static void expire(TestContext ctx) {
		FakePlayer p = ctx.player("MissTestB");
		UUID id = p.getUUID();
		long now = System.currentTimeMillis();
		Mission m = statMission("t_expiry", Tier.EASY, 5, 999, "killed:zombie");
		ActiveMission a = assign(p, m, now);
		eq(now + MissionService.timeLimitMinutes(m) * 60_000L, a.expiresAt, "tier's time limit");
		eq((long) Tier.EASY.settings().timeLimitMinutes, MissionService.timeLimitMinutes(m), "easy time limit");
		MissionService.update(p, a.expiresAt - 1);
		eq(1, MissionStore.peek(id).active.size(), "active shortly before expiry");
		MissionService.update(p, a.expiresAt);
		eq(0, MissionStore.peek(id).active.size(), "expired");
		eq(0L, EconomyService.balance(id), "nothing paid");
		eq(0L, MissionStore.peek(id).completed, "not counted as completed");

		// offline (test players aren't in the PlayerList): cleanup with a pending message
		ActiveMission b = assign(p, statMission("t_expiry2", Tier.EASY, 5, 999, "killed:zombie"), now);
		int pendingBefore = PlayerDataService.getPendingMessages(id).size();
		// clean up only the test player: the time is ~2 h in the future and must never hit real players
		eq(0, MissionService.sweepOffline(ctx.server(), b.expiresAt - 1, id::equals), "not expired yet");
		eq(1, MissionService.sweepOffline(ctx.server(), b.expiresAt, id::equals), "expired offline");
		eq(0, MissionStore.peek(id).active.size(), "removed offline");
		eq(pendingBefore + 1, PlayerDataService.getPendingMessages(id).size(), "message for the next join");
		check(PlayerDataService.getPendingMessages(id).get(pendingBefore).contains("Mission expired"), "Text 'Mission expired'");
	}

	private static void cancelKeepsPaid(TestContext ctx) {
		FakePlayer p = ctx.player("MissTestA");
		UUID id = p.getUUID();
		long now = System.currentTimeMillis();
		Item coal = net.minecraft.world.item.Items.COAL;
		ActiveMission a = assign(p, deliverMission("t_coal", coal, 10, 0), now);
		Items.giveOrDrop(p, new ItemStack(coal, 4));
		MissionService.DeliverResult r = MissionService.deliver(p, a.instance, now);
		eq(DeliverStatus.OK, r.status(), "partial delivery");
		long balance = EconomyService.balance(id);
		eq(r.paid(), balance, "paid");
		long rare = CrateKeys.count(id, CrateKeys.RARE);
		isTrue(MissionService.cancel(p, a.instance, now), "cancel");
		eq(balance, EconomyService.balance(id), "money stays");
		eq(rare, CrateKeys.count(id, CrateKeys.RARE), "no keys");
		eq(0L, MissionStore.peek(id).completed, "not completed");
		eq(now + MissionConfig.cancelCooldownMs(), MissionStore.peek(id).cooldownUntil, "cooldown set");
		eq(DeliverStatus.NOT_ACTIVE, MissionService.deliver(p, a.instance, now).status(), "no more delivery afterwards");
	}

	private static void persistence(TestContext ctx) {
		FakePlayer p = ctx.player("MissTestB");
		UUID id = p.getUUID();
		long now = System.currentTimeMillis();
		ActiveMission a = assign(p, statMission("t_persist", Tier.MEDIUM, 7, 2_500, "mined:diamond_ore", "mined:deepslate_diamond_ore"), now);
		a.progress = 3;
		a.mined = 2;
		a.killMilli = 1_250;
		PlayerState s = MissionStore.peek(id);
		s.taken.add("t_persist");
		s.explored.put("dim=*;biome=minecraft:plains;structure=*", 1_234L);
		s.exploredStructures.add("minecraft:village_plains@minecraft:overworld:1,2");
		String json = DataStore.gson().toJson(s);
		PlayerState back = DataStore.gson().fromJson(json, PlayerState.class);
		back.fixNulls();
		eq(1, back.active.size(), "active mission saved");
		ActiveMission b = back.active.get(0);
		eq(a.instance, b.instance, "instance");
		eq(a.expiresAt, b.expiresAt, "expiry");
		eq(3L, b.progress, "progress");
		eq(2L, b.mined, "counted mining");
		isTrue(b.minedEvents, "mining through events");
		eq(1_250L, b.killMilli, "counted kills (thousandths)");
		isTrue(b.killEvents, "hunting through events");
		eq(a.baseline, b.baseline, "Baseline");
		eq(2_500L, b.reward, "reward");
		eq(List.of("t_persist"), back.taken, "taken");
		eq(Map.of("dim=*;biome=minecraft:plains;structure=*", 1_234L), new java.util.HashMap<>(back.explored), "explored targets");
		eq(List.of("minecraft:village_plains@minecraft:overworld:1,2"), back.exploredStructures, "explored structures");
		Mission m = b.mission();
		check(m != null && m.type == Mission.Type.STAT && m.stats.size() == 2, "snapshot compiled");
		check(back.nextInstance > b.instance, "instance counter");
		// old save (field "excluded", no "minedEvents") is read and converted later
		ActiveMission legacy = DataStore.gson().fromJson("{\"instance\":9,\"missionId\":\"x\",\"baseline\":5,\"excluded\":2,\"progress\":3}", ActiveMission.class);
		isFalse(legacy.minedEvents, "old save: not converted yet");
		isFalse(legacy.killEvents, "old save: hunting not converted yet");
		eq(0L, legacy.killMilli, "old save: no counted kills");
		eq(3L, legacy.progress, "old save: progress");
		eq(0L, legacy.mined, "old save: no counted mining");
	}

	private static com.google.gson.JsonObject bundled(String resource) {
		String json = dev.smpeconomy.core.ConfigManager.readBundled(dev.smpeconomy.core.ConfigManager.DEFAULTS_DIR + resource)
			.orElseThrow(() -> new IllegalStateException("bundled file missing: " + resource));
		return com.google.gson.JsonParser.parseString(json).getAsJsonObject();
	}

	private static void textsEnglish(TestContext ctx) {
		java.util.regex.Pattern accented = java.util.regex.Pattern.compile("[\\u00C0-\\u00D6\\u00D8-\\u00F6\\u00F8-\\u00FF]");
		int n = 0;
		for (String f : List.of("missions.json", "missions_elite.json")) {
			com.google.gson.JsonObject en = bundled(f);
			check(!accented.matcher(en.get("_info").getAsString()).find(), f + ": _info in English");
			for (com.google.gson.JsonElement e : en.getAsJsonArray("missions")) {
				com.google.gson.JsonObject m = e.getAsJsonObject();
				for (String key : List.of("name", "description")) {
					String text = m.get(key).getAsString();
					check(!accented.matcher(text).find(), f + " " + m.get("id").getAsString() + "." + key + " in English: " + text);
				}
				n++;
			}
		}
		eq(List.of("Easy", "Medium", "Hard", "Elite"), java.util.Arrays.stream(Tier.values()).map(t -> t.defaultLabel).toList(), "tier defaults");
		com.google.gson.JsonObject tiers = bundled("missions.json").getAsJsonObject("tiers");
		for (Tier t : Tier.values()) eq(t.defaultLabel, tiers.getAsJsonObject(t.id).get("label").getAsString(), "missions.json: tier " + t.id);
		ctx.detail(n + " mission texts in English");
	}
}
