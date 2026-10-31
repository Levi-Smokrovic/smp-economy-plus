package dev.smpeconomy.features.afk;

import static dev.smpeconomy.core.test.Assert.assertThrows;
import static dev.smpeconomy.core.test.Assert.check;
import static dev.smpeconomy.core.test.Assert.eq;
import static dev.smpeconomy.core.test.Assert.isFalse;
import static dev.smpeconomy.core.test.Assert.isTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.BlockPos;
import dev.smpeconomy.core.CrateKeys;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.EconomyService.Result;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.TimeUtil;
import dev.smpeconomy.core.Treasury;
import dev.smpeconomy.core.test.SelfTests;
import dev.smpeconomy.core.test.TestContext;
import dev.smpeconomy.features.afk.AfkConfig.FileState;
import dev.smpeconomy.features.afk.AfkService.IpCandidate;
import dev.smpeconomy.features.afk.AfkTracker.Kind;
import dev.smpeconomy.features.afk.AfkTracker.Step;

final class AfkSelfTests {
	private static final String F = "afk";
	private static final DoubleSupplier ALWAYS_KEY = () -> 0.0;
	private static final DoubleSupplier NEVER_KEY = () -> 0.999_999;

	private AfkSelfTests() {}

	static void register() {
		SelfTests.register(F, "zone-limits", AfkSelfTests::zoneBounds);
		SelfTests.register(F, "config-validation", AfkSelfTests::configValidation);
		SelfTests.register(F, "config-file-protection", AfkSelfTests::configFileGuard);
		SelfTests.register(F, "timer-interval", AfkSelfTests::timerInterval);
		SelfTests.register(F, "timer-leave-reset", AfkSelfTests::timerLeaveReset);
		SelfTests.register(F, "timer-reconnect-respawn", AfkSelfTests::timerNewIdentity);
		SelfTests.register(F, "timer-clock-guard", AfkSelfTests::timerClockGuard);
		SelfTests.register(F, "timer-interval-changed", AfkSelfTests::timerIntervalChanged);
		SelfTests.register(F, "timer-zone-changed", AfkSelfTests::timerZoneChanged);
		SelfTests.register(F, "key-roll", AfkSelfTests::keyRoll);
		SelfTests.register(F, "reward-money-and-key", AfkSelfTests::rewardMoneyAndKey);
		SelfTests.register(F, "leave-max-one-per-interval", AfkSelfTests::leaveAndMaxOne);
		SelfTests.register(F, "dead-spectator-dimension", AfkSelfTests::notCounted);
		SelfTests.register(F, "daily-limit", AfkSelfTests::dayLimit);
		SelfTests.register(F, "ip-rule-choice", AfkSelfTests::ipRule);
		SelfTests.register(F, "ip-rule-blocked", AfkSelfTests::ipBlockedPlayer);
		SelfTests.register(F, "treasury", AfkSelfTests::treasury);
		SelfTests.register(F, "balance-maximum", AfkSelfTests::balanceMax);
		SelfTests.register(F, "key-only-no-money", AfkSelfTests::keyOnly);
	}

	private static AfkConfig config(int interval, long reward, double keyChance) {
		AfkConfig c = new AfkConfig();
		c.intervalSeconds = interval;
		c.rewardCents = reward;
		c.keyChancePercent = keyChance;
		c.keyTier = CrateKeys.COMMON;
		c.maxRewardsPerDay = 0;
		c.payFromTreasury = false;
		c.heightPadding = 2;
		return c;
	}

	private static AfkZone zoneAround(FakePlayer p) {
		BlockPos b = p.blockPosition();
		return AfkZone.of(AfkService.dimensionOf(p), b.getX() - 1, b.getY(), b.getZ() - 1, b.getX() + 1, b.getY() + 1, b.getZ() + 1);
	}

	private static final class Clock {
		long now = 1_000_000L;

		long tick() {
			now += 1000;
			return now;
		}
	}

	private static List<Kind> run(FakePlayer p, AfkZone zone, AfkConfig cfg, Clock clock, int n, DoubleSupplier roll) {
		List<Kind> kinds = new ArrayList<>();
		for (int i = 0; i < n; i++) kinds.add(AfkService.process(p, zone, cfg, clock.tick(), roll).kind());
		return kinds;
	}

	private static long count(List<Kind> kinds, Kind kind) {
		return kinds.stream().filter(k -> k == kind).count();
	}

	/** Account with $0; deposit would otherwise create one with the starting balance. */
	private static long freshAccount(FakePlayer p) {
		EconomyService.ensureAccount(p.getUUID(), p.getGameProfile().name());
		EconomyService.set(p.getUUID(), 0);
		return 0;
	}

	private static void restoreHealth(FakePlayer p, TestContext ctx) {
		ctx.cleanup(() -> p.setHealth(p.getMaxHealth()));
	}

	private static void zoneBounds(TestContext ctx) {
		AfkZone z = AfkZone.of("minecraft:overworld", 10, 64, 10, 5, 62, 20);
		eq(new AfkZone("minecraft:overworld", 5, 62, 10, 10, 64, 20), z, "corners sorted");
		eq("6×3×11", z.sizeLabel(), "size");
		String ow = "minecraft:overworld";
		isTrue(z.contains(ow, 5, 62, 10, 2), "lower corner inside");
		isTrue(z.contains(ow, 10, 64, 20, 2), "upper corner inside");
		isTrue(z.contains(ow, 7, 63, 15, 0), "middle inside");
		isFalse(z.contains(ow, 4, 63, 15, 2), "x too small");
		isFalse(z.contains(ow, 11, 63, 15, 2), "x too large");
		isFalse(z.contains(ow, 7, 63, 9, 2), "z too small");
		isFalse(z.contains(ow, 7, 63, 21, 2), "z too large");
		isFalse(z.contains(ow, 7, 61, 15, 2), "below the zone");
		isTrue(z.contains(ow, 7, 66, 15, 2), "jumping (padding 2) still counts");
		isFalse(z.contains(ow, 7, 67, 15, 2), "above the padding");
		isFalse(z.contains(ow, 7, 65, 15, 0), "no padding above the zone");
		isFalse(z.contains("minecraft:the_nether", 7, 63, 15, 2), "other dimension");
		eq("minecraft:the_nether", AfkZone.of("the_nether", 0, 0, 0, 1, 1, 1).dimension(), "dimension normalized");
		AfkZone extreme = AfkZone.of(ow, -30_000_000, -64, -30_000_000, 30_000_000, Integer.MAX_VALUE, 30_000_000);
		isTrue(extreme.contains(ow, 0, Integer.MAX_VALUE, 0, 16), "no overflow in height padding");
		ctx.detail("inclusive bounds, padding, dimension");
	}

	private static void configValidation(TestContext ctx) {
		AfkConfig d = new AfkConfig();
		AfkConfig.validate(d);
		eq(300, d.intervalSeconds, "default 5 min");
		eq(10_000L, d.rewardCents, "default $100");
		eq(CrateKeys.COMMON, d.keyTier, "default key tier common");
		isTrue(d.keyChancePercent > 0 && d.keyChancePercent < 100, "small chance");
		isFalse(d.payFromTreasury, "default: not from the treasury (would drain the NPC budget)");
		// without the treasury the money is new, so never ship this unlimited
		eq(AfkConfig.DEFAULT_DAY_LIMIT, d.maxRewardsPerDay, "default daily limit");
		isTrue(d.maxRewardsPerDay > 0, "default: daily limit set (0 would be unlimited)");
		isTrue((long) d.maxRewardsPerDay * d.rewardCents <= 250_000L,
			"default AFK money per day <= $2,500 (/daily maximum), was " + Money.format(d.maxRewardsPerDay * d.rewardCents));
		eq(0, d.maxAccountsPerIp, "default: IP rule off (everyone shares one IP behind a tunnel or proxy)");
		String json = DataStore.gson().toJson(d);
		isTrue(json.contains("\"maxRewardsPerDay\": " + AfkConfig.DEFAULT_DAY_LIMIT), "afk.json contains the default daily limit");
		isTrue(json.contains("0 = unlimited") && json.contains("0 = off"), "afk.json explains the 0 values");
		AfkConfig stale = new AfkConfig();
		stale.note = "old";
		AfkConfig.validate(stale);
		eq(AfkConfig.NOTE, stale.note, "note is refreshed on load");
		check(d.zone() == null, "no zone without corners");
		AfkConfig.validate(AfkFeature.CONFIG.get());

		List<Consumer<AfkConfig>> bad = List.of(
			c -> c.intervalSeconds = AfkConfig.MIN_INTERVAL_SECONDS - 1,
			c -> c.intervalSeconds = AfkConfig.MAX_INTERVAL_SECONDS + 1,
			c -> c.rewardCents = -1,
			c -> c.rewardCents = Money.MAX + 1,
			c -> c.keyChancePercent = -0.1,
			c -> c.keyChancePercent = 100.01,
			c -> c.keyChancePercent = Double.NaN,
			c -> c.keyTier = "legendary",
			c -> c.keyTier = null,
			c -> c.maxRewardsPerDay = -1,
			c -> c.maxRewardsPerDay = AfkConfig.MAX_DAY_LIMIT + 1,
			c -> c.maxAccountsPerIp = -1,
			c -> c.maxAccountsPerIp = AfkConfig.MAX_ACCOUNTS_PER_IP + 1,
			c -> c.heightPadding = -1,
			c -> c.heightPadding = AfkConfig.MAX_HEIGHT_PADDING + 1,
			c -> c.pos1 = new AfkConfig.Corner("Over World", 0, 64, 0),
			c -> c.pos2 = new AfkConfig.Corner("minecraft:overworld", 0, 99_999, 0),
			c -> c.pos2 = new AfkConfig.Corner("minecraft:overworld", 40_000_000, 64, 0));
		int i = 0;
		for (Consumer<AfkConfig> change : bad) {
			AfkConfig c = new AfkConfig();
			change.accept(c);
			int n = i++;
			assertThrows(IllegalArgumentException.class, () -> AfkConfig.validate(c), "invalid value #" + n + " rejected");
		}
		AfkConfig ok = new AfkConfig();
		ok.rewardCents = 0;
		ok.keyChancePercent = 100;
		ok.intervalSeconds = AfkConfig.MIN_INTERVAL_SECONDS;
		ok.pos1 = new AfkConfig.Corner("minecraft:overworld", 10, 64, 10);
		AfkConfig.validate(ok);
		check(ok.zone() == null, "one corner only: no zone");
		ok.pos2 = new AfkConfig.Corner("minecraft:the_nether", 0, 64, 0);
		check(ok.zone() == null, "corners in different dimensions: no zone");
		ok.pos2 = new AfkConfig.Corner("minecraft:overworld", 0, 66, 0);
		eq(new AfkZone("minecraft:overworld", 0, 64, 0, 10, 66, 10), ok.zone(), "zone from both corners");
		ctx.detail(bad.size() + " invalid values rejected");
	}

	private static void configFileGuard(TestContext ctx) throws Exception {
		Path file = DataStore.dataDir().resolve("selftest-afk-config.json");
		Path dir = DataStore.dataDir().resolve("selftest-afk-config-dir.json");
		Runnable wipe = () -> {
			try {
				Files.deleteIfExists(file);
				Files.deleteIfExists(dir);
			} catch (Exception ignored) {
			}
		};
		ctx.cleanup(wipe);
		wipe.run();
		AfkConfig active = new AfkConfig();
		active.pos1 = new AfkConfig.Corner("minecraft:overworld", 10, 64, 10);
		active.pos2 = new AfkConfig.Corner("minecraft:overworld", 20, 66, 20);
		active.rewardCents = 25_000;
		AfkConfig.validate(active);

		AfkConfig.FileCheck missing = AfkConfig.checkFile(file, active);
		eq(FileState.MISSING, missing.state(), "missing file");
		isTrue(missing.writable(), "missing file may be written");

		String json = DataStore.gson().toJson(active);
		Files.writeString(file, json);
		AfkConfig.FileCheck same = AfkConfig.checkFile(file, active);
		eq(FileState.SAME, same.state(), "file = active state");
		isTrue(same.writable(), "same state may be written");

		// same content spelled differently: 5 instead of 5.0, old note, unknown key
		JsonObject variant = JsonParser.parseString(json).getAsJsonObject();
		variant.addProperty("keyChancePercent", 5);
		variant.addProperty("note", "old text");
		variant.addProperty("removedOption", 1);
		Files.writeString(file, variant.toString());
		eq(FileState.SAME, AfkConfig.checkFile(file, active).state(), "different spelling, same state");

		// broken afk.json: ConfigManager uses code defaults (no zone), so a command must not overwrite
		AfkConfig fallback = new AfkConfig();
		AfkConfig.validate(fallback);
		String body = json.substring(0, json.lastIndexOf('}')).stripTrailing();
		List<String> broken = List.of(body + ",\n}", body, "{ broken", "", "null", "[]",
			json.replace("\"intervalSeconds\": 300", "\"intervalSeconds\": 1"),
			json.replace("\"keyTier\": \"common\"", "\"keyTier\": \"legendary\""));
		int n = 0;
		for (String content : broken) {
			Files.writeString(file, content);
			AfkConfig.FileCheck c = AfkConfig.checkFile(file, fallback);
			eq(FileState.BROKEN, c.state(), "broken/invalid file #" + n);
			isFalse(c.writable(), "broken file #" + n + " is not overwritten");
			isFalse(c.detail().isEmpty(), "error text #" + n);
			isTrue(c.detail().length() <= 160, "error text short #" + n);
			n++;
		}
		isTrue(Files.readString(file).contains("legendary"), "check does not change the file");

		// file repaired but not loaded yet: defaults still active, so don't overwrite with them
		Files.writeString(file, json);
		AfkConfig.FileCheck stale = AfkConfig.checkFile(file, fallback);
		eq(FileState.DIFFERENT, stale.state(), "valid, but differs from active state");
		isFalse(stale.writable(), "differing file is not overwritten");
		AfkConfig edited = DataStore.gson().fromJson(json, AfkConfig.class);
		AfkConfig.validate(edited);
		edited.pos2 = null;
		Files.writeString(file, DataStore.gson().toJson(edited));
		eq(FileState.DIFFERENT, AfkConfig.checkFile(file, active).state(), "edited by hand (pos2 removed)");

		Files.createDirectories(dir);
		AfkConfig.FileCheck unreadable = AfkConfig.checkFile(dir, active);
		eq(FileState.UNREADABLE, unreadable.state(), "unreadable (directory)");
		isFalse(unreadable.writable(), "unreadable file is not overwritten");

		AfkConfig.FileCheck real = AfkConfig.checkFile(AfkFeature.CONFIG.path(), AfkFeature.CONFIG.get());
		ctx.detail(broken.size() + " broken variants blocked, afk.json now: " + real.state());
	}

	private static void timerInterval(TestContext ctx) {
		AfkTracker t = new AfkTracker();
		UUID id = UUID.randomUUID();
		Object me = new Object();
		long now = 0;
		Step s = t.step(id, me, true, 5, now);
		eq(Kind.ENTERED, s.kind(), "enter");
		eq(5, s.remaining(), "countdown starts at the interval");
		for (int i = 1; i <= 4; i++) {
			s = t.step(id, me, true, 5, now += 1000);
			eq(Kind.PROGRESS, s.kind(), "second " + i);
			eq(i, s.seconds(), "counter " + i);
			eq(5 - i, s.remaining(), "remaining " + i);
		}
		eq(Kind.REWARD, t.step(id, me, true, 5, now += 1000).kind(), "reward after 5 s");
		eq(0, t.seconds(id), "counter back to 0");
		eq(Kind.PROGRESS, t.step(id, me, true, 5, now += 1000).kind(), "keeps counting afterwards");
		int rewards = 0;
		for (int i = 0; i < 3 * 5 + 2; i++) if (t.step(id, me, true, 5, now += 1000).kind() == Kind.REWARD) rewards++;
		eq(3, rewards, "17 more seconds (from second 1): exactly 3 rewards");
		ctx.detail("reward exactly every 5 checks");
	}

	private static void timerLeaveReset(TestContext ctx) {
		AfkTracker t = new AfkTracker();
		UUID id = UUID.randomUUID();
		Object me = new Object();
		long now = 0;
		t.step(id, me, true, 5, now);
		for (int i = 0; i < 4; i++) t.step(id, me, true, 5, now += 1000);
		eq(4, t.seconds(id), "4 s in the zone");
		eq(Kind.LEFT, t.step(id, me, false, 5, now += 1000).kind(), "leave");
		eq(-1, t.seconds(id), "no session anymore");
		eq(Kind.OUTSIDE, t.step(id, me, false, 5, now += 1000).kind(), "still outside");
		eq(Kind.ENTERED, t.step(id, me, true, 5, now += 1000).kind(), "entered again");
		for (int i = 1; i <= 4; i++) eq(Kind.PROGRESS, t.step(id, me, true, 5, now += 1000).kind(), "no reward after returning, s=" + i);
		eq(Kind.REWARD, t.step(id, me, true, 5, now += 1000).kind(), "only after a full interval");
		ctx.detail("leaving discards progress");
	}

	private static void timerNewIdentity(TestContext ctx) {
		AfkTracker t = new AfkTracker();
		UUID id = UUID.randomUUID();
		Object before = new Object();
		Object after = new Object();
		long now = 0;
		t.step(id, before, true, 5, now);
		for (int i = 0; i < 4; i++) t.step(id, before, true, 5, now += 1000);
		eq(Kind.ENTERED, t.step(id, after, true, 5, now += 1000).kind(), "new player object: restart");
		int rewards = 0;
		for (int i = 1; i <= 4; i++) if (t.step(id, after, true, 5, now += 1000).kind() == Kind.REWARD) rewards++;
		eq(0, rewards, "progress before reconnect does not count");
		eq(Kind.REWARD, t.step(id, after, true, 5, now += 1000).kind(), "full interval after reconnect");
		t.retainOnly(Set.of());
		eq(-1, t.seconds(id), "offline: session removed");
		ctx.detail("reconnect/respawn resets");
	}

	private static void timerClockGuard(TestContext ctx) {
		AfkTracker t = new AfkTracker();
		UUID id = UUID.randomUUID();
		Object me = new Object();
		long now = 0;
		t.step(id, me, true, 5, now);
		List<Long> rewardTimes = new ArrayList<>();
		// /tick sprint: checks every 100 ms instead of 1000 ms
		while (now < 10_000) {
			now += 100;
			if (t.step(id, me, true, 5, now).kind() == Kind.REWARD) rewardTimes.add(now);
		}
		eq(2, rewardTimes.size(), "at most 2 rewards in 10 s of real time (interval 5 s)");
		long minGap = 5_000 - AfkTracker.CLOCK_TOLERANCE_MS;
		isTrue(rewardTimes.get(0) >= minGap, "first reward no earlier than " + minGap + " ms (was " + rewardTimes.get(0) + ")");
		isTrue(rewardTimes.get(1) - rewardTimes.get(0) >= minGap, "gap >= " + minGap + " ms");
		isTrue(t.seconds(id) <= 5, "counter stays capped at the interval");
		ctx.detail("real-time guard: " + rewardTimes);
	}

	private static void timerIntervalChanged(TestContext ctx) {
		AfkTracker t = new AfkTracker();
		UUID id = UUID.randomUUID();
		Object me = new Object();
		long now = 0;
		t.step(id, me, true, 10, now);
		for (int i = 0; i < 8; i++) t.step(id, me, true, 10, now += 1000);
		eq(8, t.seconds(id), "8 of 10 s");
		eq(Kind.REWARD, t.step(id, me, true, 5, now += 1000).kind(), "interval shortened to 5: one reward immediately");
		eq(Kind.PROGRESS, t.step(id, me, true, 5, now += 1000).kind(), "but only one");
		for (int i = 0; i < 2; i++) t.step(id, me, true, 5, now += 1000);
		eq(3, t.seconds(id), "3 of 5 s");
		int rewards = 0;
		for (int i = 0; i < 6; i++) if (t.step(id, me, true, 10, now += 1000).kind() == Kind.REWARD) rewards++;
		eq(0, rewards, "interval extended to 10: runs on to 9");
		eq(Kind.REWARD, t.step(id, me, true, 10, now += 1000).kind(), "reward at 10 s");
		ctx.detail("interval change causes no double reward");
	}

	private static void timerZoneChanged(TestContext ctx) {
		AfkTracker t = new AfkTracker();
		UUID id = UUID.randomUUID();
		Object me = new Object();
		eq(Set.of(), t.syncZone("A"), "first zone, no sessions");
		t.step(id, me, true, 5, 0);
		t.step(id, me, true, 5, 1000);
		eq(Set.of(), t.syncZone("A"), "unchanged zone");
		eq(1, t.seconds(id), "session stays");
		eq(Set.of(id), t.syncZone("B"), "changed zone discards sessions");
		eq(-1, t.seconds(id), "session gone");
		t.step(id, me, true, 5, 2000);
		eq(Set.of(id), t.syncZone(null), "deleted zone discards sessions");
		eq(Set.of(), t.syncZone(null), "still no zone");
		ctx.detail("zone changed/deleted: timer reset");
	}

	private static void keyRoll(TestContext ctx) {
		isFalse(AfkService.rollKey(0, 0.0), "0 % never");
		isTrue(AfkService.rollKey(100, 0.999_999), "100 % always");
		isTrue(AfkService.rollKey(5, 0.049_9), "5 %: 0.0499 hits");
		isFalse(AfkService.rollKey(5, 0.05), "5 %: 0.05 misses");
		isFalse(AfkService.rollKey(Double.NaN, 0.0), "NaN-Chance");
		isFalse(AfkService.rollKey(-1, 0.0), "negative chance");
		isFalse(AfkService.rollKey(50, Double.NaN), "NaN roll");
		int hits = 0;
		int n = 20_000;
		for (int i = 0; i < n; i++) if (AfkService.rollKey(5, ThreadLocalRandom.current().nextDouble())) hits++;
		double rate = hits * 100.0 / n;
		isTrue(rate > 3.5 && rate < 6.5, "5 % chance statistically plausible (" + rate + " %)");
		ctx.detail("roll ok, " + rate + " % at 5 %");
	}

	private static void rewardMoneyAndKey(TestContext ctx) {
		FakePlayer p = ctx.player("AfkTestA");
		UUID id = p.getUUID();
		AfkConfig cfg = config(5, 12_345, 100);
		AfkZone zone = zoneAround(p);
		Clock clock = new Clock();
		long start = freshAccount(p);
		List<Kind> first = run(p, zone, cfg, clock, 5, ALWAYS_KEY);
		eq(List.of(Kind.ENTERED, Kind.PROGRESS, Kind.PROGRESS, Kind.PROGRESS, Kind.PROGRESS), first, "countdown running");
		eq(start, EconomyService.balance(id), "no money before expiry");
		eq(0L, CrateKeys.count(id, CrateKeys.COMMON), "no key before expiry");
		eq(Kind.REWARD, AfkService.process(p, zone, cfg, clock.tick(), ALWAYS_KEY).kind(), "reward after 5 s");
		eq(start + 12_345, EconomyService.balance(id), "money credited");
		eq(1L, CrateKeys.count(id, CrateKeys.COMMON), "key (common) via CrateKeys");
		eq(0L, CrateKeys.count(id, CrateKeys.RARE), "no other crates");
		eq(0L, CrateKeys.count(id, CrateKeys.EPIC), "no other crates");
		eq(1L, AfkService.rewardsToday(id), "today 1");
		eq(1L, PlayerDataService.getCounter(id, AfkService.COUNTER_REWARDS), "total counter");
		eq(12_345L, PlayerDataService.getCounter(id, AfkService.COUNTER_EARNED), "total earned");
		cfg.keyChancePercent = 50;
		List<Kind> second = run(p, zone, cfg, clock, 5, NEVER_KEY);
		eq(1L, count(second, Kind.REWARD), "next interval: exactly one reward again");
		eq(start + 2 * 12_345, EconomyService.balance(id), "money twice");
		eq(1L, CrateKeys.count(id, CrateKeys.COMMON), "50 %, roll misses: no further key");
		eq(2L, AfkService.rewardsToday(id), "today 2");
		ctx.detail("money + key after a full interval");
	}

	private static void leaveAndMaxOne(TestContext ctx) {
		FakePlayer p = ctx.player("AfkTestB");
		UUID id = p.getUUID();
		AfkConfig cfg = config(5, 1_000, 0);
		AfkZone zone = zoneAround(p);
		Clock clock = new Clock();
		long start = freshAccount(p);
		run(p, zone, cfg, clock, 5, NEVER_KEY);
		eq(4, AfkService.TRACKER.seconds(id), "4 s in the zone");
		double x = p.getX();
		double y = p.getY();
		double z = p.getZ();
		p.setPos(x + 2, y, z);
		eq(Kind.LEFT, AfkService.process(p, zone, cfg, clock.tick(), NEVER_KEY).kind(), "leaving detected (server position)");
		eq(-1, AfkService.TRACKER.seconds(id), "timer discarded");
		List<Kind> outside = run(p, zone, cfg, clock, 7, NEVER_KEY);
		eq(7L, count(outside, Kind.OUTSIDE), "no session outside");
		eq(start, EconomyService.balance(id), "no money outside");
		p.setPos(x, y, z);
		List<Kind> back = run(p, zone, cfg, clock, 5, NEVER_KEY);
		eq(Kind.ENTERED, back.get(0), "entered again");
		eq(0L, count(back, Kind.REWARD), "full interval again after returning");
		eq(start, EconomyService.balance(id), "progress before leaving does not count");
		eq(Kind.REWARD, AfkService.process(p, zone, cfg, clock.tick(), NEVER_KEY).kind(), "5 s after returning");
		eq(start + 1_000, EconomyService.balance(id), "first reward");
		List<Kind> stay = run(p, zone, cfg, clock, 3 * 5 + 3, NEVER_KEY);
		eq(3L, count(stay, Kind.REWARD), "18 s more in the zone: exactly 3 rewards");
		eq(start + 4_000, EconomyService.balance(id), "at most one reward per interval");
		eq(3, AfkService.TRACKER.seconds(id), "3 s into the next interval");
		p.setPos(x, y + 3, z);
		eq(Kind.PROGRESS, AfkService.process(p, zone, cfg, clock.tick(), NEVER_KEY).kind(), "jumping does not count as leaving");
		p.setPos(x, y + 4, z);
		eq(Kind.LEFT, AfkService.process(p, zone, cfg, clock.tick(), NEVER_KEY).kind(), "above the padding = outside");
		p.setPos(x, y, z);
		ctx.detail("leaving resets, 3 rewards in 3 intervals");
	}

	private static void notCounted(TestContext ctx) {
		FakePlayer p = ctx.player("AfkTestC");
		UUID id = p.getUUID();
		AfkConfig cfg = config(5, 1_000, 100);
		AfkZone zone = zoneAround(p);
		Clock clock = new Clock();
		freshAccount(p);
		restoreHealth(p, ctx);
		p.setHealth(0);
		List<Kind> dead = run(p, zone, cfg, clock, 7, ALWAYS_KEY);
		eq(7L, count(dead, Kind.OUTSIDE), "dead does not count");
		p.setHealth(p.getMaxHealth());
		BlockPos b = p.blockPosition();
		AfkZone nether = AfkZone.of("minecraft:the_nether", b.getX() - 1, b.getY(), b.getZ() - 1, b.getX() + 1, b.getY() + 1, b.getZ() + 1);
		List<Kind> otherDim = run(p, nether, cfg, clock, 7, ALWAYS_KEY);
		eq(7L, count(otherDim, Kind.OUTSIDE), "same coordinates in another dimension do not count");
		List<Kind> noZone = run(p, null, cfg, clock, 7, ALWAYS_KEY);
		eq(7L, count(noZone, Kind.OUTSIDE), "nothing without a zone");
		isFalse(AfkService.counts(true, true, false), "spectator does not count");
		isFalse(AfkService.counts(false, false, false), "dead does not count");
		isFalse(AfkService.counts(true, false, true), "removed does not count");
		isTrue(AfkService.counts(true, false, false), "living player counts");
		eq(0L, EconomyService.balance(id), "no money");
		eq(0L, CrateKeys.count(id, CrateKeys.COMMON), "no key");
		ctx.detail("dead/spectator/other dimension/no zone");
	}

	private static void dayLimit(TestContext ctx) {
		FakePlayer p = ctx.player("AfkTestD");
		UUID id = p.getUUID();
		AfkConfig cfg = config(5, 500, 0);
		cfg.maxRewardsPerDay = 2;
		AfkZone zone = zoneAround(p);
		Clock clock = new Clock();
		long start = freshAccount(p);
		List<Kind> kinds = run(p, zone, cfg, clock, 1 + 3 * 5, NEVER_KEY);
		eq(2L, count(kinds, Kind.REWARD), "daily limit 2");
		eq(Kind.LIMIT, kinds.get(kinds.size() - 1), "limit notice afterwards");
		eq(start + 1_000, EconomyService.balance(id), "money only 2 times");
		eq(2L, AfkService.rewardsToday(id), "today 2");
		eq(-1, AfkService.TRACKER.seconds(id), "timer does not run at the limit");
		PlayerDataService.setLong(id, AfkService.KEY_DAY, TimeUtil.today().toEpochDay() - 1);
		eq(0L, AfkService.rewardsToday(id), "new day: 0");
		List<Kind> next = run(p, zone, cfg, clock, 1 + 5, NEVER_KEY);
		eq(Kind.ENTERED, next.get(0), "timer restarts");
		eq(1L, count(next, Kind.REWARD), "reward again on the new day");
		eq(start + 1_500, EconomyService.balance(id), "3rd payout");
		eq(1L, AfkService.rewardsToday(id), "counter of the new day");
		ctx.detail("limit applies, resets on the new day");
	}

	private static void ipRule(TestContext ctx) {
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		UUID c = UUID.randomUUID();
		UUID d = UUID.randomUUID();
		UUID e = UUID.randomUUID();
		List<IpCandidate> list = List.of(
			new IpCandidate(a, "10.0.0.1", -1),
			new IpCandidate(b, "10.0.0.1", 120),
			new IpCandidate(c, "10.0.0.2", -1),
			new IpCandidate(d, null, -1),
			new IpCandidate(e, null, 30));
		eq(Set.of(), AfkService.ipBlocked(list, 0), "rule off: nobody blocked");
		eq(Set.of(a), AfkService.ipBlocked(list, 1), "same IP: whoever is already collecting (b) keeps the slot");
		eq(Set.of(), AfkService.ipBlocked(list, 2), "2 per IP allowed");
		eq(Set.of(e), AfkService.ipBlocked(List.of(new IpCandidate(d, "10.0.0.3", -1), new IpCandidate(e, "10.0.0.3", -1)), 1),
			"tie: list order");
		eq(Set.of(b, c), AfkService.ipBlocked(List.of(new IpCandidate(a, "10.0.0.4", 0), new IpCandidate(b, "10.0.0.4", -1),
			new IpCandidate(c, "10.0.0.4", -1)), 1), "right after a reward (0 s) the slot stays with the collector");
		eq(Set.of(), AfkService.ipBlocked(List.of(), 1), "no candidates");
		ctx.detail("max N accounts per IP, unknown IP never blocked");
	}

	private static void ipBlockedPlayer(TestContext ctx) {
		FakePlayer p = ctx.player("AfkTestI");
		UUID id = p.getUUID();
		AfkConfig cfg = config(5, 1_000, 100);
		cfg.maxAccountsPerIp = 1;
		AfkZone zone = zoneAround(p);
		Clock clock = new Clock();
		long start = freshAccount(p);
		run(p, zone, cfg, clock, 3, ALWAYS_KEY);
		eq(2, AfkService.TRACKER.seconds(id), "collected 2 s");
		List<Kind> blocked = new ArrayList<>();
		for (int i = 0; i < 7; i++) blocked.add(AfkService.process(p, zone, cfg, clock.tick(), ALWAYS_KEY, true).kind());
		eq(7L, count(blocked, Kind.IP_LIMIT), "blocked: notice only");
		eq(-1, AfkService.TRACKER.seconds(id), "blocked: timer discarded");
		eq(start, EconomyService.balance(id), "blocked: no money");
		eq(0L, CrateKeys.count(id, CrateKeys.COMMON), "blocked: no key");
		eq(0L, AfkService.rewardsToday(id), "blocked: does not count");
		double x = p.getX();
		p.setPos(x + 2, p.getY(), p.getZ());
		eq(Kind.OUTSIDE, AfkService.process(p, zone, cfg, clock.tick(), ALWAYS_KEY, true).kind(), "block does not apply outside");
		p.setPos(x, p.getY(), p.getZ());
		List<Kind> free = run(p, zone, cfg, clock, 1 + 5, ALWAYS_KEY);
		eq(Kind.ENTERED, free.get(0), "free again: timer restarts");
		eq(1L, count(free, Kind.REWARD), "only after a full interval");
		eq(start + 1_000, EconomyService.balance(id), "one reward");
		ctx.detail("IP block: no timer, no money, no key");
	}

	private static void treasury(TestContext ctx) {
		ctx.preserveTreasury();
		FakePlayer p = ctx.player("AfkTestT");
		UUID id = p.getUUID();
		freshAccount(p);
		AfkConfig cfg = config(5, 1, 0);
		cfg.payFromTreasury = true;
		String detail;
		if (Treasury.allowance() >= 1) {
			long before = Treasury.balance();
			long total = EconomyService.totalMoney();
			AfkService.Payout ok = AfkService.payout(id, cfg, NEVER_KEY);
			eq(Result.OK, ok.money(), "payout from the treasury");
			eq(1L, EconomyService.balance(id), "player +1 cent");
			eq(before - 1, Treasury.balance(), "treasury -1 cent");
			eq(total, EconomyService.totalMoney(), "no new money");
			detail = "treasury pays, cap applies";
		} else {
			detail = "treasury empty, only the cap was checked";
		}
		long tooMuch = Treasury.allowance() + 1;
		check(tooMuch <= Money.MAX, "test amount valid");
		cfg.rewardCents = tooMuch;
		long balance = EconomyService.balance(id);
		long till = Treasury.balance();
		long today = AfkService.rewardsToday(id);
		AfkService.Payout capped = AfkService.payout(id, cfg, NEVER_KEY);
		isFalse(capped.moneyOk(), "above the hourly cap: no payment (" + capped.money() + ")");
		eq(balance, EconomyService.balance(id), "player unchanged");
		eq(till, Treasury.balance(), "treasury unchanged");
		isFalse(capped.counted(), "does not count toward the daily limit");
		eq(today, AfkService.rewardsToday(id), "daily counter unchanged");
		cfg.keyChancePercent = 100;
		AfkService.Payout keyOnly = AfkService.payout(id, cfg, ALWAYS_KEY);
		isTrue(keyOnly.key() && keyOnly.counted(), "key even without treasury money");
		eq(1L, CrateKeys.count(id, CrateKeys.COMMON), "key credited");
		ctx.detail(detail);
	}

	private static void balanceMax(TestContext ctx) {
		FakePlayer p = ctx.player("AfkTestM");
		UUID id = p.getUUID();
		EconomyService.ensureAccount(id, "AfkTestM");
		EconomyService.set(id, Money.MAX - 5);
		AfkConfig cfg = config(5, 100, 0);
		AfkService.Payout payout = AfkService.payout(id, cfg, NEVER_KEY);
		eq(Result.LIMIT_EXCEEDED, payout.money(), "rejected above the maximum");
		eq(Money.MAX - 5, EconomyService.balance(id), "balance unchanged");
		isFalse(payout.counted(), "does not count");
		eq(0L, AfkService.rewardsToday(id), "daily counter unchanged");
		eq(0L, PlayerDataService.getCounter(id, AfkService.COUNTER_EARNED), "nothing earned");
		ctx.detail("Money.MAX is never exceeded");
	}

	private static void keyOnly(TestContext ctx) {
		FakePlayer p = ctx.player("AfkTestK");
		UUID id = p.getUUID();
		AfkConfig cfg = config(5, 0, 100);
		AfkZone zone = zoneAround(p);
		Clock clock = new Clock();
		long start = freshAccount(p);
		List<Kind> kinds = run(p, zone, cfg, clock, 1 + 5, ALWAYS_KEY);
		eq(1L, count(kinds, Kind.REWARD), "one round");
		eq(start, EconomyService.balance(id), "reward 0: no money");
		eq(1L, CrateKeys.count(id, CrateKeys.COMMON), "but the key");
		cfg.keyChancePercent = 0;
		cfg.keyTier = CrateKeys.RARE;
		run(p, zone, cfg, clock, 5, ALWAYS_KEY);
		eq(0L, CrateKeys.count(id, CrateKeys.RARE), "0 %: never a key");
		eq(1L, AfkService.rewardsToday(id), "empty round does not count toward the limit");
		ctx.detail("key only, 0 % chance");
	}
}
