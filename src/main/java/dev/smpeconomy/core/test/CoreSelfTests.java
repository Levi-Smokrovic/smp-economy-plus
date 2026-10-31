package dev.smpeconomy.core.test;

import static dev.smpeconomy.core.test.Assert.assertThrows;
import static dev.smpeconomy.core.test.Assert.check;
import static dev.smpeconomy.core.test.Assert.eq;
import static dev.smpeconomy.core.test.Assert.isFalse;
import static dev.smpeconomy.core.test.Assert.isTrue;
import static dev.smpeconomy.core.test.Assert.notNull;

import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import dev.smpeconomy.core.compat.Mc;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.EconomyService.Result;
import dev.smpeconomy.core.ItemCodec;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.MobOrigin;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.PlacedBlockTracker;
import dev.smpeconomy.core.PlayerDataService;

public final class CoreSelfTests {
	private static final String F = "core";

	private CoreSelfTests() {}

	public static void register() {
		SelfTests.register(F, "money-parse", ctx -> moneyParse());
		SelfTests.register(F, "money-format", ctx -> moneyFormat());
		SelfTests.register(F, "currency-symbol-texts", CoreSelfTests::currencySymbolTexts);
		SelfTests.register(F, "msg-legacy", ctx -> msgLegacy());
		SelfTests.register(F, "time-util", ctx -> timeUtil());
		SelfTests.register(F, "timezone-config", ctx -> timezoneConfig());
		SelfTests.register(F, "economy-deposit-withdraw", CoreSelfTests::economyDepositWithdraw);
		SelfTests.register(F, "economy-transfer", CoreSelfTests::economyTransfer);
		SelfTests.register(F, "economy-limits", CoreSelfTests::economyLimits);
		SelfTests.register(F, "playerdata", CoreSelfTests::playerData);
		SelfTests.register(F, "datastore-roundtrip", CoreSelfTests::dataStoreRoundTrip);
		SelfTests.register(F, "datastore-restore", CoreSelfTests::dataStoreRestore);
		SelfTests.register(F, "datastore-player-save", CoreSelfTests::dataStorePlayerSave);
		SelfTests.register(F, "datastore-start-group", CoreSelfTests::dataStoreStartGroup);
		SelfTests.register(F, "datastore-presave-flush", CoreSelfTests::dataStorePreSaveFlush);
		SelfTests.register(F, "datastore-skip-unchanged", CoreSelfTests::dataStoreSkipUnchanged);
		SelfTests.register(F, "datastore-all-or-nothing", CoreSelfTests::dataStoreAllOrNothing);
		SelfTests.register(F, "datastore-journal", CoreSelfTests::dataStoreJournal);
		SelfTests.register(F, "datastore-parallel-fsync", CoreSelfTests::dataStoreParallelFsync);
		SelfTests.register(F, "config-lastgood", CoreSelfTests::configLastGood);
		SelfTests.register(F, "itemcodec-roundtrip", CoreSelfTests::itemCodecRoundTrip);
		SelfTests.register(F, "items-give-or-drop", CoreSelfTests::giveOrDrop);
		SelfTests.register(F, "items-count-remove", CoreSelfTests::countRemove);
		SelfTests.register(F, "placed-mark-unmark", CoreSelfTests::placedMarkUnmark);
		SelfTests.register(F, "placed-persist", CoreSelfTests::placedPersist);
		SelfTests.register(F, "placed-world-hooks", CoreSelfTests::placedWorldHooks);
		SelfTests.register(F, "placed-fluid-generated", CoreSelfTests::placedFluidGenerated);
		SelfTests.register(F, "mob-origin-tags", CoreSelfTests::mobOriginTags);
		EconomyModelSelfTests.register();
		SafetySelfTests.register();
		GuiCloseSelfTests.register();
		CompatSelfTests.register();
	}

	private static void moneyParse() {
		Object[][] ok = {
			{"100", 10_000L}, {"2.5k", 250_000L}, {"1m", 100_000_000L}, {"1.5b", 150_000_000_000L}, {"12.34", 1_234L},
			{"$5", 500L}, {"1,000", 100_000L}, {"1,234.56", 123_456L}, {"12,50", 1_250L}, {" 7 ", 700L}, {"0.01", 1L},
			{"1.234k", 123_400L}, {"2K", 200_000L}, {"10t", Money.MAX}, {"007", 700L}, {"1.00", 100L}, {"1.50k", 150_000L},
			{"1.0001k", 100_010L}, {"0.10", 10L},
		};
		for (Object[] c : ok) {
			OptionalLong v = Money.parse((String) c[0]);
			check(v.isPresent(), "parse(\"" + c[0] + "\") should be valid");
			eq((long) c[1], v.getAsLong(), "parse(\"" + c[0] + "\")");
		}
		String[] bad = {"", " ", "0", "0.00", "-5", "abc", "1.234", "1e5", "0.001", "1,2345", "1.5.5", "10.01t", "k", "$", "1.", ".5",
			"+5", "1 000", "99999999999999999999999999", "NaN", "Infinity", "1.0000001k",
			// German thousands separators must never read as $1.00 or $2.50.
			"1.000", "2.500", "1.230", "0.010", "12.500", "1.00000", "1,000.000"};
		for (String s : bad) isFalse(Money.parse(s).isPresent(), "parse(\"" + s + "\") should be invalid");
		isFalse(Money.parse(null).isPresent(), "parse(null)");
		eq(0L, Money.parseAllowZero("0").orElse(-1), "parseAllowZero(0)");
		isFalse(Money.parseAllowZero("0.000").isPresent(), "parseAllowZero(0.000): 3 decimals");
		isFalse(Money.parseAllowZero("-1").isPresent(), "parseAllowZero(-1)");
	}

	private static void moneyFormat() {
		eq("$0.00", Money.format(0), "format 0");
		eq("$0.05", Money.format(5), "format 5");
		eq("$1.50", Money.format(150), "format 150");
		eq("$1,234.56", Money.format(123_456), "format 123456");
		eq("$1,000,000.00", Money.format(100_000_000), "format 1M");
		eq("-$1.50", Money.format(-150), "format -150");
		eq("-$92,233,720,368,547,758.08", Money.format(Long.MIN_VALUE), "format MIN");
		eq("$92,233,720,368,547,758.07", Money.format(Long.MAX_VALUE), "format MAX");
		eq("$15", Money.formatShort(1_500), "short 15");
		eq("$12.30", Money.formatShort(1_230), "short 12.30");
		eq("$999.99", Money.formatShort(99_999), "short 999.99");
		eq("$1K", Money.formatShort(100_000), "short 1K");
		eq("$1.2K", Money.formatShort(123_456), "short 1.2K");
		eq("$1.9K", Money.formatShort(199_999), "short rounded down");
		eq("$3.4M", Money.formatShort(340_000_000), "short 3.4M");
		eq("$1.1B", Money.formatShort(110_000_000_000L), "short 1.1B");
		eq("$10T", Money.formatShort(Money.MAX), "short MAX");
		eq("-$1.2K", Money.formatShort(-123_456), "short negative");
		eq(50L, Money.percentOf(1_000, 5.0), "percentOf");
		for (long v : new long[] {1, 99, 100, 123_456, 987_654_321L, Money.MAX}) {
			eq(v, Money.parse(Money.format(v)).orElse(-1), "format/parse " + v);
		}
	}

	private static void msgLegacy() {
		Component c = Msg.legacy("§6§lA§7B§rC");
		eq("ABC", c.getString(), "Text");
		List<Component> parts = c.getSiblings();
		eq(3, parts.size(), "segments");
		eq((long) Mc.formatRgb(ChatFormatting.GOLD), (long) parts.get(0).getStyle().getColor().getValue(), "color A");
		isTrue(parts.get(0).getStyle().isBold(), "A bold");
		isFalse(parts.get(1).getStyle().isBold(), "color code resets bold");
		check(parts.get(2).getStyle().getColor() == null, "§r resets color");
		eq("SMP » Hello", Msg.prefixed("Hello").getString(), "prefix");
		// Fast paths must match the vanilla and JDK versions.
		for (int code = 0; code <= Character.MAX_VALUE; code++) {
			char ch = (char) code;
			if (Msg.formatByCode(ch) != ChatFormatting.getByCode(Character.toLowerCase(ch))) {
				Assert.fail("formatByCode('" + ch + "' U+" + Integer.toHexString(code) + ") differs from ChatFormatting.getByCode");
			}
		}
		java.util.Random rnd = new java.util.Random(42);
		List<Long> values = new ArrayList<>(List.of(0L, 1L, -1L, 9L, 99L, 999L, -999L, 1000L, -1000L, 1001L, 9999L, 10_000L, 99_999L,
			100_000L, 999_999L, 1_000_000L, -1_234_567L, 123_456_789L, Long.MAX_VALUE, Long.MIN_VALUE, Long.MIN_VALUE + 1));
		for (int i = 0; i < 20_000; i++) values.add(i % 2 == 0 ? rnd.nextLong() : rnd.nextLong() >> rnd.nextInt(64));
		for (long v : values) eq(String.format(java.util.Locale.ROOT, "%,d", v), Msg.number(v), "Msg.number(" + v + ")");
	}

	private static void timeUtil() {
		eq("0s", dev.smpeconomy.core.TimeUtil.formatDuration(-5), "negative");
		eq("12s", dev.smpeconomy.core.TimeUtil.formatDuration(12_999), "seconds");
		eq("4m 03s", dev.smpeconomy.core.TimeUtil.formatDuration(243_000), "minutes");
		eq("1h 05m", dev.smpeconomy.core.TimeUtil.formatDuration(3_900_000), "hours");
		eq("2d 3h", dev.smpeconomy.core.TimeUtil.formatDuration((2 * 24 + 3) * 3_600_000L + 59_000), "days");
		eq("1h 00m", dev.smpeconomy.core.TimeUtil.formatTicks(72_000), "Ticks");
		isTrue(dev.smpeconomy.core.TimeUtil.isHourBetween(3, 1, 8), "3 in 1–8");
		isFalse(dev.smpeconomy.core.TimeUtil.isHourBetween(8, 1, 8), "8 not in 1-8");
		isTrue(dev.smpeconomy.core.TimeUtil.isHourBetween(23, 22, 6), "23 in 22–6");
		isTrue(dev.smpeconomy.core.TimeUtil.isHourBetween(2, 22, 6), "2 in 22–6");
		isFalse(dev.smpeconomy.core.TimeUtil.isHourBetween(12, 22, 6), "12 not in 22-6");
		var from = java.time.ZonedDateTime.of(2026, 3, 28, 23, 0, 0, 0, dev.smpeconomy.core.TimeUtil.zone());
		eq(3_600_000L, dev.smpeconomy.core.TimeUtil.millisUntilNext(java.time.LocalTime.MIDNIGHT, from), "until midnight");
		eq(20 * 3_600_000L, dev.smpeconomy.core.TimeUtil.millisUntilNext(java.time.LocalTime.of(20, 0), from), "until 20:00 (past midnight, DST change: 20 h real time)");
		var dst = java.time.ZonedDateTime.of(2026, 3, 29, 0, 0, 0, 0, dev.smpeconomy.core.TimeUtil.zone());
		eq(23 * 3_600_000L, dev.smpeconomy.core.TimeUtil.millisUntilNext(java.time.LocalTime.MIDNIGHT, dst), "DST day has 23 h");
		eq("1,234,567", Msg.number(1_234_567), "Msg.number");
		eq("Diamond", Msg.itemName(net.minecraft.world.item.Items.DIAMOND).getString(), "item name (server language)");
	}

	private static void economyDepositWithdraw(TestContext ctx) {
		UUID a = ctx.player("TestA").getUUID();
		EconomyService.deleteAccount(a);
		EconomyService.ensureAccount(a, "TestA");
		eq(Result.OK, EconomyService.set(a, 0), "set 0");
		eq(Result.OK, EconomyService.deposit(a, 1_000), "deposit");
		eq(1_000L, EconomyService.balance(a), "balance");
		eq(Result.INVALID_AMOUNT, EconomyService.deposit(a, 0), "deposit 0");
		eq(Result.INVALID_AMOUNT, EconomyService.deposit(a, -5), "deposit negative");
		eq(Result.INVALID_AMOUNT, EconomyService.withdraw(a, -1), "withdraw negative");
		eq(Result.INSUFFICIENT_FUNDS, EconomyService.withdraw(a, 1_001), "withdraw too much");
		eq(1_000L, EconomyService.balance(a), "unchanged after failure");
		eq(Result.OK, EconomyService.withdraw(a, 400), "withdraw");
		eq(600L, EconomyService.balance(a), "after withdraw");
		isTrue(EconomyService.has(a, 600), "has 600");
		isFalse(EconomyService.has(a, 601), "has 601");
		eq(Result.OK, EconomyService.withdraw(a, 600), "withdraw all");
		eq(0L, EconomyService.balance(a), "empty");
		eq(Result.INSUFFICIENT_FUNDS, EconomyService.withdraw(a, 1), "below 0");
		UUID none = TestPlayers.uuid("TestNoAccount");
		EconomyService.deleteAccount(none);
		eq(Result.INSUFFICIENT_FUNDS, EconomyService.withdraw(none, 1), "without account");
		eq(0L, EconomyService.balance(none), "without account 0");
	}

	private static void economyTransfer(TestContext ctx) {
		UUID a = ctx.player("TestA").getUUID();
		UUID b = ctx.player("TestB").getUUID();
		EconomyService.ensureAccount(a, "TestA");
		EconomyService.ensureAccount(b, "TestB");
		EconomyService.set(a, 1_000);
		EconomyService.set(b, 0);
		long total = EconomyService.totalMoney();
		eq(Result.OK, EconomyService.transfer(a, b, 300), "transfer");
		eq(700L, EconomyService.balance(a), "Sender");
		eq(300L, EconomyService.balance(b), "recipient");
		eq(Result.INSUFFICIENT_FUNDS, EconomyService.transfer(a, b, 701), "too much");
		eq(Result.SAME_ACCOUNT, EconomyService.transfer(a, a, 1), "to self");
		eq(Result.INVALID_AMOUNT, EconomyService.transfer(a, b, 0), "0");
		eq(Result.INVALID_AMOUNT, EconomyService.transfer(a, b, -10), "negative");
		UUID none = TestPlayers.uuid("TestNoAccount");
		EconomyService.deleteAccount(none);
		eq(Result.NO_ACCOUNT, EconomyService.transfer(a, none, 1), "recipient without account");
		eq(700L, EconomyService.balance(a), "sender unchanged");
		eq(total, EconomyService.totalMoney(), "total money preserved");
		eq(a, EconomyService.findByName("testa").orElse(null), "name cache (case-insensitive)");
		isTrue(EconomyService.top().stream().noneMatch(e -> e.id().equals(a)), "test accounts not on the leaderboard");

		UUID system = TestPlayers.uuid("TestSystem");
		ctx.cleanup(() -> {
			EconomyService.unregisterSystemAccount(system);
			EconomyService.deleteAccount(system);
		});
		EconomyService.registerSystemAccount(system, "TestTill");
		isTrue(EconomyService.hasAccount(system), "system account created");
		eq(0L, EconomyService.balance(system), "system account without start balance");
		isTrue(EconomyService.isSystemAccount(system), "recognized as system account");
		eq(Result.OK, EconomyService.transfer(a, system, 100), "to system account");
		eq(system, EconomyService.findByName("TestTill").orElse(null), "system account by name");
	}

	private static void economyLimits(TestContext ctx) {
		UUID a = ctx.player("TestA").getUUID();
		UUID b = ctx.player("TestB").getUUID();
		EconomyService.ensureAccount(a, "TestA");
		EconomyService.ensureAccount(b, "TestB");
		eq(Result.OK, EconomyService.set(a, Money.MAX), "set MAX");
		eq(Result.LIMIT_EXCEEDED, EconomyService.deposit(a, 1), "above MAX");
		eq(Money.MAX, EconomyService.balance(a), "MAX unchanged");
		eq(Result.INVALID_AMOUNT, EconomyService.set(a, Money.MAX + 1), "set > MAX");
		eq(Result.INVALID_AMOUNT, EconomyService.set(a, -1), "set negative");
		eq(Result.INVALID_AMOUNT, EconomyService.deposit(a, Long.MAX_VALUE), "deposit Long.MAX");
		eq(Result.INVALID_AMOUNT, EconomyService.withdraw(a, Long.MAX_VALUE), "withdraw Long.MAX");
		eq(Result.INVALID_AMOUNT, EconomyService.deposit(a, Long.MIN_VALUE), "deposit Long.MIN");
		EconomyService.set(a, 100);
		EconomyService.set(b, Money.MAX - 10);
		eq(Result.LIMIT_EXCEEDED, EconomyService.transfer(a, b, 11), "recipient above MAX");
		eq(100L, EconomyService.balance(a), "sender unchanged");
		eq(Money.MAX - 10, EconomyService.balance(b), "recipient unchanged");
		eq(Result.OK, EconomyService.transfer(a, b, 10), "exactly up to MAX");
		eq(Money.MAX, EconomyService.balance(b), "recipient MAX");
	}

	private static void playerData(TestContext ctx) {
		UUID a = ctx.player("TestA").getUUID();
		PlayerDataService.delete(a);
		PlayerDataService.setJob(a, "miner");
		eq("miner", PlayerDataService.getJob(a), "Job");
		isTrue(PlayerDataService.getJobChangedAt(a) > 0, "job change time");
		eq(15L, PlayerDataService.addJobXp(a, "miner", 15), "XP");
		eq(15L, PlayerDataService.getJobXp(a, "miner"), "XP read");
		eq(1, PlayerDataService.getJobLevel(a, "miner"), "default level");
		PlayerDataService.setJobLevel(a, "miner", 7);
		eq(7, PlayerDataService.getJobLevel(a, "miner"), "Level");
		PlayerDataService.setBool(a, "sidebar", false);
		isFalse(PlayerDataService.getBool(a, "sidebar", true), "Setting");
		JsonObject blob = new JsonObject();
		blob.addProperty("wins", 3);
		PlayerDataService.setBlob(a, "test.blob", blob);
		eq(3, PlayerDataService.getBlob(a, "test.blob", JsonObject.class).get("wins").getAsInt(), "Blob");
		PlayerDataService.addPendingMessage(a, "one");
		PlayerDataService.addPendingMessage(a, "two");
		eq(List.of("one", "two"), PlayerDataService.getPendingMessages(a), "Pending");
		PlayerDataService.removePendingMessages(a, 1);
		eq(List.of("two"), PlayerDataService.getPendingMessages(a), "pending after delivery");
		PlayerDataService.addPlaytime(a, 40);
		eq(40L, PlayerDataService.getPlaytimeTicks(a), "playtime");
		eq(3L, PlayerDataService.addCounter(a, "test.keys", 3), "counter");
		isFalse(PlayerDataService.takeCounter(a, "test.keys", 4), "not enough");
		isTrue(PlayerDataService.takeCounter(a, "test.keys", 3), "take");
		eq(0L, PlayerDataService.getCounter(a, "test.keys"), "counter empty");
		assertThrows(IllegalArgumentException.class, () -> PlayerDataService.addCounter(a, "test.keys", -1), "negative delta");
	}

	/** Gson needs public + no-arg constructor. */
	public static final class RoundTrip {
		public Map<UUID, Long> balances = new HashMap<>();
		public List<String> list = new ArrayList<>();
		public String text = "";
		public long big;
		public JsonObject blob;
	}

	private static void dataStoreRoundTrip(TestContext ctx) throws Exception {
		String file = "selftest-roundtrip.json";
		Path dir = DataStore.dataDir();
		Runnable wipe = () -> {
			try (var files = Files.list(dir)) {
				for (Path p : files.filter(f -> f.getFileName().toString().startsWith(file)).toList()) Files.deleteIfExists(p);
			} catch (Exception ignored) {
			}
		};
		ctx.cleanup(wipe);
		wipe.run();
		DataStore<RoundTrip> store = DataStore.unmanaged(file, RoundTrip.class, RoundTrip::new);
		store.load();
		isTrue(store.isLoaded(), "loaded");
		UUID id = TestPlayers.uuid("TestA");
		store.get().balances.put(id, Money.MAX);
		store.get().list.add("a");
		store.get().list.add("b");
		store.get().text = "Greetings § \"quoted\" <html> \n new line";
		store.get().big = Long.MAX_VALUE;
		store.get().blob = new JsonObject();
		store.get().blob.addProperty("n", 9_007_199_254_740_993L); // above 2^53: must not pass through double
		store.markDirty();
		isTrue(store.save(), "saved");
		isFalse(store.isDirty(), "no longer dirty");
		isFalse(Files.exists(dir.resolve(file + ".tmp")), "no tmp file left");

		DataStore<RoundTrip> again = DataStore.unmanaged(file, RoundTrip.class, RoundTrip::new);
		again.load();
		RoundTrip r = again.get();
		eq(Money.MAX, (long) r.balances.get(id), "UUID-Map");
		eq(List.of("a", "b"), r.list, "list");
		eq(store.get().text, r.text, "Text");
		eq(Long.MAX_VALUE, r.big, "long");
		eq(9_007_199_254_740_993L, r.blob.get("n").getAsLong(), "large value in blob");
		ctx.detail("round trip with large numbers, special chars, UUID map");
	}

	/**
	 * Damaged, empty, null or missing files block startup with nothing touched. Only the marker resets all stores to
	 * the same restore point, so nothing duplicates across files.
	 */
	private static void dataStoreRestore(TestContext ctx) throws Exception {
		Path dir = DataStore.dataDir();
		Path root = dir.resolve("selftest-backups");
		String fa = "selftest-ds-a.json";
		String fb = "selftest-ds-b.json";
		String fc = "selftest-ds-c.json";
		Runnable wipe = () -> {
			try (var files = Files.list(dir)) {
				for (Path p : files.filter(f -> f.getFileName().toString().startsWith("selftest-ds-")).toList()) Files.deleteIfExists(p);
			} catch (Exception ignored) {
			}
			deleteTree(root);
		};
		ctx.cleanup(wipe);
		wipe.run();
		DataStore<RoundTrip> a = DataStore.unmanaged(fa, RoundTrip.class, RoundTrip::new);
		DataStore<RoundTrip> b = DataStore.unmanaged(fb, RoundTrip.class, RoundTrip::new);
		List<DataStore<?>> both = List.of(a, b);

		isTrue(DataStore.loadStores(both, root, false).ok(), "new stores load");
		isTrue(a.isLoaded() && a.get().text.isEmpty(), "Defaults");
		a.get().text = "a1";
		b.get().text = "b1";
		isTrue(a.save() && b.save(), "state 1 saved");
		isTrue(DataStore.loadStores(both, root, false).ok(), "state 1 loaded");
		isFalse(a.wasRestored(), "not restored");
		String snap = DataStore.snapshot(both, root);
		notNull(snap, "restore point created");
		isTrue(Files.readString(root.resolve(snap).resolve(fa)).contains("a1"), "copy in the set");
		// state 2, e.g. money charged in a, stake booked in b
		a.get().text = "a2";
		b.get().text = "b2";
		isTrue(a.save() && b.save(), "state 2 saved");

		for (String broken : new String[] {"{ broken", "", "   \n", "null"}) {
			String what = "content [" + broken.strip() + "]";
			Files.writeString(dir.resolve(fa), broken);
			DataStore.LoadReport r = DataStore.loadStores(both, root, false);
			isFalse(r.ok(), what + ": start refused");
			isFalse(a.isLoaded() || b.isLoaded(), what + ": nothing loaded");
			eq(broken, Files.readString(dir.resolve(fa)), what + ": file untouched");
			isFalse(b.save(), what + ": saving locked");
		}
		isTrue(Files.readString(dir.resolve(fb)).contains("b2"), "b unchanged at state 2");
		assertThrows(IllegalStateException.class, a::load, "loading one broken file throws");

		Files.delete(dir.resolve(fa));
		isFalse(DataStore.loadStores(both, root, false).ok(), "missing file with restore point: refused");
		isFalse(Files.exists(dir.resolve(fa)), "missing file not created with defaults");

		Files.writeString(dir.resolve(fa), "{ broken");
		DataStore.LoadReport r = DataStore.loadStores(both, root, true);
		isTrue(r.ok(), "restore: " + r.failures());
		eq("a1", a.get().text, "a from the set");
		eq("b1", b.get().text, "b reset too (otherwise money/items double)");
		isTrue(a.wasRestored() && b.wasRestored() && a.isDirty() && b.isDirty(), "marked as restored (dirty)");
		eq(List.of(fa, fb), r.restored(), "reset stores");
		isTrue(Files.readString(dir.resolve(fa)).contains("a1") && Files.readString(dir.resolve(fb)).contains("b1"), "main files written at once");
		Path kept;
		try (var dirs = Files.list(root)) {
			kept = dirs.filter(p -> p.getFileName().toString().startsWith("replaced-")).findFirst().orElse(null);
		}
		notNull(kept, "directory with the previous files");
		eq("{ broken", Files.readString(kept.resolve(fa)), "broken file kept");
		isTrue(Files.readString(kept.resolve(fb)).contains("b2"), "newer state of b kept");
		isFalse(Files.exists(root.resolve(DataStore.RESTORE_JOURNAL)), "journal removed");
		isTrue(DataStore.loadStores(both, root, false).ok(), "normal start afterwards");
		isFalse(a.wasRestored(), "no longer marked as restored afterwards");

		DataStore<RoundTrip> c = DataStore.unmanaged(fc, RoundTrip.class, RoundTrip::new);
		List<DataStore<?>> three = List.of(a, b, c);
		isTrue(DataStore.loadStores(three, root, false).ok(), "c new");
		c.get().text = "c-new";
		isTrue(c.save(), "c saved");
		Files.writeString(dir.resolve(fb), "null");
		r = DataStore.loadStores(three, root, true);
		isTrue(r.ok(), "restore with new store: " + r.failures());
		eq("b1", b.get().text, "b from the set");
		eq("", c.get().text, "c did not exist then: defaults");
		isFalse(Files.exists(dir.resolve(fc)), "c file replaced");

		Files.writeString(root.resolve(DataStore.RESTORE_JOURNAL), "restore point " + snap);
		isFalse(DataStore.loadStores(both, root, false).ok(), "interrupted restore: refused");
		isTrue(DataStore.loadStores(both, root, true).ok(), "interrupted restore repeated with marker");
		isFalse(Files.exists(root.resolve(DataStore.RESTORE_JOURNAL)), "journal gone afterwards");
		eq(List.of(), DataStore.restoreMarkers(root), "no marker");
		Files.writeString(root.resolve(DataStore.RESTORE_MARKER), "");
		eq(List.of(root.resolve(DataStore.RESTORE_MARKER)), DataStore.restoreMarkers(root), "new name works");
		Files.delete(root.resolve(DataStore.RESTORE_MARKER));

		Files.writeString(root.resolve(snap).resolve(fa), "{ broken");
		Files.writeString(dir.resolve(fa), "");
		isFalse(DataStore.loadStores(both, root, true).ok(), "no usable restore point: refused");
		eq("", Files.readString(dir.resolve(fa)), "nothing touched");
		// Single .bak files as a shared last resort.
		Files.writeString(dir.resolve(fa + ".bak"), "{\"text\":\"old-a\"}");
		Files.writeString(dir.resolve(fb + ".bak"), "{\"text\":\"old-b\"}");
		r = DataStore.loadStores(both, root, true);
		isTrue(r.ok(), "from .bak: " + r.failures());
		eq("old-a", a.get().text, "a from .bak");
		eq("old-b", b.get().text, "b from .bak (same set)");

		for (int i = 0; i < DataStore.SNAPSHOTS_KEPT + 2; i++) notNull(DataStore.snapshot(both, root), "restore point " + i);
		long sets;
		try (var dirs = Files.list(root)) {
			sets = dirs.filter(Files::isDirectory).map(p -> p.getFileName().toString()).filter(n -> !n.startsWith("replaced-") && !n.startsWith(".")).count();
		}
		eq(DataStore.SNAPSHOTS_KEPT, sets, "restore point count");

		String fd = "selftest-ds-d.json";
		DataStore<RoundTrip> d = DataStore.unmanaged(fd, RoundTrip.class, RoundTrip::new);
		Files.writeString(dir.resolve(fd + ".corrupt-1"), "{ broken");
		isFalse(DataStore.loadStores(List.of(d), root, false).ok(), "only .corrupt-*: refused");
		Files.delete(dir.resolve(fd + ".corrupt-1"));
		Files.writeString(dir.resolve(fd + ".bak"), "{\"text\":\"old-d\"}");
		isFalse(DataStore.loadStores(List.of(d), root, false).ok(), "only .bak: refused");
		Files.delete(dir.resolve(fd + ".bak"));
		isTrue(DataStore.loadStores(List.of(d), root, false).ok() && d.get().text.isEmpty(), "no traces: new");
		ctx.detail("broken/empty/null/missing refused; marker uses a shared set, new stores get defaults, journal, .bak fallback, rotation");
	}

	private static void dataStorePlayerSave(TestContext ctx) throws Exception {
		FakePlayer p = ctx.player("TestSave");
		// A player who just connected may have a full save pending, which would land inside the count below.
		DataStore.flushDirty();
		long before = DataStore.playerSaveCount();
		DataStore.requestPlayerSave(p);
		DataStore.requestPlayerSave(UUID.randomUUID());
		isTrue(DataStore.playerSavePending(), "requested");
		DataStore.flushDirty();
		isFalse(DataStore.playerSavePending(), "processed");
		eq(before, DataStore.playerSaveCount(), "FakePlayer/offline not saved");
		int online = ctx.server().getPlayerList().getPlayers().size();
		DataStore.requestPlayerSave();
		DataStore.flushDirty();
		eq(before + online, DataStore.playerSaveCount(), "all online players saved");
		isFalse(DataStore.journalPending(), "no journal left");
		Path playerDir = ctx.server().getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR);
		if (Files.isDirectory(playerDir)) {
			try (var files = Files.list(playerDir)) {
				eq(0L, files.filter(f -> f.getFileName().toString().endsWith(DataStore.PLAYER_TMP_SUFFIX)).count(), "no prepared player file left");
			}
		}
		for (ServerPlayer online1 : ctx.server().getPlayerList().getPlayers()) {
			isTrue(Files.isRegularFile(playerDir.resolve(online1.getStringUUID() + ".dat")), "player file of " + online1.getPlainTextName());
		}
		ctx.detail(online + " online");
	}

	/**
	 * All managed stores were loaded together at startup, otherwise they miss the startup check, restore point and
	 * recovery. This start's restore point holds every file present then.
	 */
	private static void dataStoreStartGroup(TestContext ctx) {
		eq(List.of(), DataStore.storesOutsideStartGroup(), "stores outside the shared load");
		for (String core : List.of("economy.json", "players.json", "treasury.json", "price_modifiers.json")) {
			isTrue(DataStore.managedStoreNames().contains(core), core + " managed");
		}
		String snap = DataStore.startSnapshot();
		notNull(snap, "restore point of this start");
		Path dir = DataStore.backupRoot().resolve(snap);
		List<String> missing = new ArrayList<>();
		for (String name : DataStore.presentAtStart()) {
			if (!Files.isRegularFile(dir.resolve(name))) missing.add(name);
		}
		eq(List.of(), missing, "store files missing from the restore point");
		// Very first start (empty data dir): players.json only appears afterwards.
		if (!DataStore.presentAtStart().isEmpty() && Files.isRegularFile(DataStore.dataDir().resolve("players.json"))) {
			isTrue(DataStore.presentAtStart().contains("players.json"), "players.json recorded at start");
		}
		ctx.detail(DataStore.managedStoreNames().size() + " stores, restore point " + snap + " with " + DataStore.presentAtStart().size() + " files");
	}

	/**
	 * Vanilla writes all player files first on autosave and /save-all, then chunks; Fabric's AFTER_SAVE only comes
	 * after that. So everything must already be committed at the start of PlayerList.saveAll.
	 */
	private static void dataStorePreSaveFlush(TestContext ctx) {
		PlayerDataService.markDirty();
		DataStore.requestPlayerSave(UUID.randomUUID()); // offline: discarded, but handled before the vanilla save
		isTrue(DataStore.playerSavePending(), "player save requested");
		long before = DataStore.preSaveFlushCount();
		ctx.server().getPlayerList().saveAll();
		eq(before + 1, DataStore.preSaveFlushCount(), "PlayerList.saveAll calls the mod flush first");
		isTrue(DataStore.lastPreSaveClean(), "at the start of PlayerList.saveAll nothing is dirty and no player save is pending");
		isFalse(DataStore.dirtyStoreNames().contains("players.json"), "players.json committed");
		isFalse(DataStore.journalPending(), "no journal left");
	}

	/** NaN is not valid JSON, so Gson throws and the store fails to save. */
	public static final class Fragile {
		public String text = "";
		public double ratio;
	}

	/** Unchanged stores are not rewritten; externally changed or deleted ones are. */
	private static void dataStoreSkipUnchanged(TestContext ctx) throws Exception {
		Path dir = DataStore.dataDir();
		String fa = "selftest-skip-a.json";
		String fb = "selftest-skip-b.json";
		Runnable wipe = () -> wipePrefix(dir, "selftest-skip-");
		ctx.cleanup(wipe);
		wipe.run();
		DataStore<RoundTrip> a = DataStore.unmanaged(fa, RoundTrip.class, RoundTrip::new);
		DataStore<RoundTrip> b = DataStore.unmanaged(fb, RoundTrip.class, RoundTrip::new);
		a.load();
		b.load();
		a.get().text = "a1";
		a.markDirty();
		long written = DataStore.filesWritten();
		long skipped = DataStore.writesSkipped();
		isTrue(a.save(), "first save");
		eq(written + 1, DataStore.filesWritten(), "written");
		a.markDirty();
		isTrue(a.save(), "save without change");
		eq(written + 1, DataStore.filesWritten(), "not written again");
		eq(skipped + 1, DataStore.writesSkipped(), "skipped");
		isFalse(a.isDirty(), "no longer dirty");
		Path fileA = dir.resolve(fa);
		Files.writeString(fileA, "{\"text\":\"from outside\"}");
		isTrue(a.save(), "save after external change");
		isTrue(Files.readString(fileA).contains("a1"), "externally changed file overwritten again (memory wins)");
		Files.delete(fileA);
		isTrue(a.save() && Files.isRegularFile(fileA), "deleted file rewritten");

		b.get().text = "b1";
		isTrue(DataStore.commitStores(List.of(a, b), false), "group");
		long afterGroup = DataStore.filesWritten();
		isTrue(DataStore.commitStores(List.of(a, b), false), "group without change");
		eq(afterGroup, DataStore.filesWritten(), "unchanged group writes nothing");
		DataStore<RoundTrip> again = DataStore.unmanaged(fa, RoundTrip.class, RoundTrip::new);
		again.load();
		isTrue(DataStore.commitStores(List.of(again), false), "freshly loaded");
		eq(afterGroup, DataStore.filesWritten(), "freshly loaded, unchanged store is not rewritten");
		again.get().text = "a2";
		isTrue(DataStore.commitStores(List.of(again), false), "changed");
		eq(afterGroup + 1, DataStore.filesWritten(), "changed store is written");
		isTrue(Files.readString(fileA).contains("a2"), "new content");
	}

	/**
	 * If one store cannot be written the other is not written either, or e.g. new economy.json with old auctions.json
	 * is a dupe. Everything stays dirty and the problem shows. Best effort (only right before vanilla's player save)
	 * writes what it can.
	 */
	private static void dataStoreAllOrNothing(TestContext ctx) throws Exception {
		Path dir = DataStore.dataDir();
		String fa = "selftest-aon-a.json";
		String fb = "selftest-aon-b.json";
		DataStore<RoundTrip> a = DataStore.unmanaged(fa, RoundTrip.class, RoundTrip::new);
		DataStore<Fragile> b = DataStore.unmanaged(fb, Fragile.class, Fragile::new);
		ctx.cleanup(() -> {
			b.get().ratio = 0; // end the problem state with a successful save, then clean up
			if (a.isLoaded() && b.isLoaded()) DataStore.commitStores(List.of(a, b), false);
			wipePrefix(dir, "selftest-aon-");
		});
		wipePrefix(dir, "selftest-aon-");
		a.load();
		b.load();
		a.get().text = "a1";
		b.get().text = "b1";
		isTrue(DataStore.commitStores(List.of(a, b), false), "state 1");
		isTrue(DataStore.persistenceProblem().isEmpty(), "no problem");

		a.get().text = "a2";
		a.markDirty();
		b.get().text = "b2";
		b.get().ratio = Double.NaN;
		b.markDirty();
		isFalse(DataStore.commitStores(List.of(a, b), false), "NaN in b: group aborted");
		isTrue(Files.readString(dir.resolve(fa)).contains("a1"), "a not written (all or nothing)");
		isFalse(Files.exists(dir.resolve(fa + ".tmp")), "no .tmp of a left");
		isTrue(a.isDirty() && b.isDirty(), "both stay dirty");
		isTrue(DataStore.persistenceProblem().isPresent(), "problem reported");
		isFalse(DataStore.journalPending(), "no journal");

		isFalse(DataStore.commitStores(List.of(a, b), true), "best effort: not complete");
		isTrue(Files.readString(dir.resolve(fa)).contains("a2"), "best effort: a written");
		isTrue(Files.readString(dir.resolve(fb)).contains("b1"), "best effort: b on old state");
		isFalse(a.isDirty(), "a clean");
		isTrue(b.isDirty(), "b stays dirty");
		isTrue(DataStore.persistenceProblem().isPresent(), "problem stays");

		b.get().ratio = 0.5;
		isTrue(DataStore.commitStores(List.of(a, b), false), "repaired");
		isTrue(Files.readString(dir.resolve(fb)).contains("b2"), "b written");
		isTrue(DataStore.persistenceProblem().isEmpty(), "problem resolved");
	}

	private static void dataStoreParallelFsync(TestContext ctx) throws Exception {
		Path dir = DataStore.dataDir();
		ctx.cleanup(() -> wipePrefix(dir, "selftest-pfs-"));
		wipePrefix(dir, "selftest-pfs-");
		Path a = dir.resolve("selftest-pfs-a.bin");
		Path b = dir.resolve("selftest-pfs-b.bin");
		Path missing = dir.resolve("selftest-pfs-missing.bin");
		Files.writeString(a, "a");
		Files.writeString(b, "b");
		eq(Map.of(), DataStore.forceAll(List.of()), "empty list");
		eq(Map.of(), DataStore.forceAll(List.of(a)), "one file");
		Map<Path, Throwable> errors = DataStore.forceAll(List.of(a, missing, b, dir));
		eq(List.of(missing), new ArrayList<>(errors.keySet()), "only the missing file fails (files + directories ok)");
		eq(1, DataStore.forceAll(List.of(missing)).size(), "single missing file");

		List<DataStore<RoundTrip>> stores = new ArrayList<>();
		for (int i = 0; i < 12; i++) {
			DataStore<RoundTrip> s = DataStore.unmanaged("selftest-pfs-" + i + ".json", RoundTrip.class, RoundTrip::new);
			s.load();
			s.get().text = "value-" + i;
			s.markDirty();
			stores.add(s);
		}
		isTrue(DataStore.commitStores(new ArrayList<>(stores), false), "12 stores written together");
		for (int i = 0; i < 12; i++) {
			Path f = dir.resolve("selftest-pfs-" + i + ".json");
			isTrue(Files.readString(f).contains("value-" + i), f.getFileName() + " written");
			isFalse(Files.exists(dir.resolve("selftest-pfs-" + i + ".json.tmp")), f.getFileName() + ".tmp gone");
			isFalse(stores.get(i).isDirty(), f.getFileName() + " clean");
		}
		isFalse(DataStore.journalPending(), "no journal left");
		isTrue(DataStore.persistenceProblem().isEmpty(), "no problem");
	}

	/**
	 * Commit point is the journal. If it exists (crash while renaming), the next start or save completes it, including
	 * player files with .dat_old; a missing .tmp counts as done. Invalid journals are not applied. .tmp without a
	 * journal was never committed and moves aside.
	 */
	private static void dataStoreJournal(TestContext ctx) throws Exception {
		Path dir = DataStore.dataDir();
		Path root = dir.resolve("selftest-journal");
		String fx = "selftest-journal-x.json";
		Path fileX = dir.resolve(fx);
		DataStore<RoundTrip> x = DataStore.unmanaged(fx, RoundTrip.class, RoundTrip::new);
		ctx.cleanup(() -> {
			if (Files.isDirectory(fileX)) deleteTree(fileX);
			if (x.isLoaded()) DataStore.commitStores(List.of(x), false);
			deleteTree(root);
			wipePrefix(dir, "selftest-journal-");
		});
		deleteTree(root);
		wipePrefix(dir, "selftest-journal-");
		Path td = root.resolve("smpeconomy");
		Path pd = root.resolve("playerdata");
		Files.createDirectories(td);
		Files.createDirectories(pd);
		Files.writeString(td.resolve("a.json"), "old-a");
		Files.writeString(td.resolve("a.json.tmp"), "new-a");
		Files.writeString(td.resolve("b.json.tmp"), "new-b"); // b did not exist yet
		Files.writeString(td.resolve("c.json"), "new-c"); // c was already renamed
		UUID id = UUID.randomUUID();
		Files.writeString(pd.resolve(id + ".dat"), "old-p");
		Files.writeString(pd.resolve(id + DataStore.PLAYER_TMP_SUFFIX), "new-p");
		Path journal = td.resolve(DataStore.COMMIT_JOURNAL);
		Files.writeString(journal, "{\"entries\":["
			+ "{\"tmp\":\"smpeconomy/a.json.tmp\",\"target\":\"smpeconomy/a.json\",\"keepOld\":false},"
			+ "{\"tmp\":\"smpeconomy/b.json.tmp\",\"target\":\"smpeconomy/b.json\",\"keepOld\":false},"
			+ "{\"tmp\":\"smpeconomy/c.json.tmp\",\"target\":\"smpeconomy/c.json\",\"keepOld\":false},"
			+ "{\"tmp\":\"playerdata/" + id + DataStore.PLAYER_TMP_SUFFIX + "\",\"target\":\"playerdata/" + id + ".dat\",\"keepOld\":true}]}");
		eq(3, DataStore.rollForward(root, journal), "renames (c was already done)");
		eq("new-a", Files.readString(td.resolve("a.json")), "a done");
		eq("new-b", Files.readString(td.resolve("b.json")), "b done");
		eq("new-c", Files.readString(td.resolve("c.json")), "c unchanged");
		eq("new-p", Files.readString(pd.resolve(id + ".dat")), "player file done");
		eq("old-p", Files.readString(pd.resolve(id + ".dat_old")), "previous player file as .dat_old");
		isFalse(Files.exists(journal), "journal deleted");
		isFalse(Files.exists(td.resolve("a.json.tmp")) || Files.exists(pd.resolve(id + DataStore.PLAYER_TMP_SUFFIX)), "no tmp left");
		eq(-1, DataStore.rollForward(root, journal), "nothing to do without a journal");

		Files.writeString(td.resolve("a.json.tmp"), "bad");
		for (String bad : new String[] {
			"{ broken",
			"{\"entries\":[{\"tmp\":\"../x.json.tmp\",\"target\":\"../x.json\"}]}",
			"{\"entries\":[{\"tmp\":\"smpeconomy/a.json.tmp\",\"target\":\"smpeconomy/c.json\"}]}",
			"{\"entries\":[{\"tmp\":\"smpeconomy/a.json.tmp\",\"target\":\"playerdata/a.json\"}]}"}) {
			Files.writeString(journal, bad);
			assertThrows(java.io.IOException.class, () -> DataStore.rollForward(root, journal), "invalid journal " + bad);
			isTrue(Files.exists(journal), "journal stays: " + bad);
			eq("new-a", Files.readString(td.resolve("a.json")), "nothing applied: " + bad);
		}
		Files.delete(journal);

		String fo = "selftest-journal-o.json";
		DataStore<RoundTrip> o = DataStore.unmanaged(fo, RoundTrip.class, RoundTrip::new);
		Files.writeString(dir.resolve(fo), "{\"text\":\"valid\"}");
		Files.writeString(dir.resolve(fo + ".tmp"), "{\"text\":\"half");
		Files.writeString(pd.resolve(id + DataStore.PLAYER_TMP_SUFFIX), "half-p");
		Path aside = root.resolve("backups");
		eq(2, DataStore.discardUncommitted(List.of(o), pd, aside), "moved aside or deleted");
		isFalse(Files.exists(dir.resolve(fo + ".tmp")), "never committed .tmp gone");
		isFalse(Files.exists(pd.resolve(id + DataStore.PLAYER_TMP_SUFFIX)), "never committed player file gone");
		o.load();
		eq("valid", o.get().text, "main file applies");
		try (var dirs = Files.list(aside)) {
			isTrue(dirs.anyMatch(p -> p.getFileName().toString().startsWith("incomplete-") && Files.exists(p.resolve(fo + ".tmp"))), ".tmp kept");
		}

		// Real group save whose rename fails (target is a directory): the journal stays, single saves wait, and the
		// next save finishes it first and then writes the new state.
		x.load();
		x.get().text = "x1";
		isTrue(DataStore.commitStores(List.of(x), false), "x1");
		x.get().text = "x2";
		Files.delete(fileX);
		Files.createDirectories(fileX.resolve("obstacle"));
		isFalse(DataStore.commitStores(List.of(x), false), "rename fails");
		isTrue(DataStore.journalPending(), "journal stays");
		isTrue(Files.readString(dir.resolve(fx + ".tmp")).contains("x2"), "committed state is ready");
		isTrue(DataStore.persistenceProblem().isPresent(), "problem reported");
		isFalse(x.save(), "single save waits for the journal");
		deleteTree(fileX);
		x.get().text = "x3";
		isTrue(DataStore.commitStores(List.of(x), false), "journal finished, then rewritten");
		isFalse(DataStore.journalPending(), "journal gone");
		isTrue(Files.readString(fileX).contains("x3"), "newest state");
		isTrue(DataStore.persistenceProblem().isEmpty(), "problem resolved");
	}

	public static final class TestConfig {
		public int value = 1;
	}

	private static void configLastGood(TestContext ctx) throws Exception {
		String name = "selftest-lastgood";
		java.util.function.Supplier<dev.smpeconomy.core.ConfigManager.Config<TestConfig>> fresh = () -> dev.smpeconomy.core.ConfigManager
			.unmanaged(name, TestConfig.class, TestConfig::new, c -> {
				if (c.value < 0) throw new IllegalArgumentException("value negative");
			});
		var cfg = fresh.get();
		Path file = cfg.path();
		Path lastGood = cfg.lastGoodPath();
		Runnable wipe = () -> {
			try {
				Files.deleteIfExists(file);
				Files.deleteIfExists(lastGood);
			} catch (Exception ignored) {
			}
		};
		ctx.cleanup(wipe);
		wipe.run();

		cfg.initialLoad(true);
		eq(1, cfg.get().value, "missing: defaults");
		isTrue(Files.isRegularFile(file) && Files.isRegularFile(lastGood), "file + last good copy written");
		Files.writeString(file, "{\"value\": 7}");
		eq(null, cfg.reload(), "valid change");
		eq(7, cfg.get().value, "new value");
		isTrue(Files.readString(lastGood).contains("7"), "last good copy updated");

		for (String broken : new String[] {"{\"value\": 9", "{\"value\": -5}", ""}) {
			Files.writeString(file, broken);
			var start = fresh.get();
			start.initialLoad(true);
			eq(7, start.get().value, "start with [" + broken + "]: last good copy rather than defaults");
			notNull(start.problem(), "start with [" + broken + "]: problem reported");
			eq(broken, Files.readString(file), "start with [" + broken + "]: file untouched");
		}
		var running = fresh.get();
		running.initialLoad(true);
		notNull(running.reload(), "reload with broken file fails");
		eq(7, running.get().value, "old state stays");
		notNull(running.problem(), "problem stays");
		Files.writeString(file, "{\"value\": 8}");
		eq(null, running.reload(), "repaired");
		eq(8, running.get().value, "new value");
		eq(null, running.problem(), "problem resolved");

		Files.delete(lastGood);
		Files.writeString(file, "{ broken");
		assertThrows(IllegalStateException.class, () -> fresh.get().initialLoad(true), "without last good copy: start aborted");
		var late = fresh.get();
		late.initialLoad(false);
		eq(1, late.get().value, "late (server running): defaults");
		notNull(late.problem(), "late: problem reported");
		eq("{ broken", Files.readString(file), "file untouched");
	}

	private static void wipePrefix(Path dir, String prefix) {
		try (var files = Files.list(dir)) {
			for (Path p : files.filter(f -> f.getFileName().toString().startsWith(prefix)).toList()) {
				if (Files.isDirectory(p)) deleteTree(p);
				else Files.deleteIfExists(p);
			}
		} catch (Exception ignored) {
		}
	}

	private static void deleteTree(Path root) {
		if (!Files.exists(root)) return;
		try (var walk = Files.walk(root)) {
			for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
		} catch (Exception ignored) {
		}
	}

	private static void itemCodecRoundTrip(TestContext ctx) {
		var registries = ctx.server().registryAccess();
		ItemStack sword = new ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD);
		sword.enchant(registries.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS), 5);
		sword.set(DataComponents.CUSTOM_NAME, Component.literal("Excalibur").withStyle(ChatFormatting.GOLD));
		sword.setDamageValue(42);
		ItemStack back = ItemCodec.decode(ItemCodec.encode(sword));
		isTrue(ItemStack.matches(sword, back), "sword equal after round trip");
		eq(42, back.getDamageValue(), "damage");
		isFalse(Items.isPristine(back), "not pristine");

		ItemStack box = new ItemStack(net.minecraft.world.item.Items.SHULKER_BOX);
		box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(sword.copy(), new ItemStack(net.minecraft.world.item.Items.DIAMOND, 64))));
		isTrue(ItemStack.matches(box, ItemCodec.decode(ItemCodec.encode(box))), "shulker with contents");

		ItemStack dirt = new ItemStack(net.minecraft.world.item.Items.DIRT, 37);
		isTrue(ItemStack.matches(dirt, ItemCodec.decode(ItemCodec.encode(dirt))), "count stays");

		assertThrows(IllegalArgumentException.class, () -> ItemCodec.decode("not base64!!"), "invalid data");
		assertThrows(IllegalArgumentException.class, () -> ItemCodec.decode(""), "empty data");
		assertThrows(IllegalArgumentException.class, () -> ItemCodec.encode(ItemStack.EMPTY), "empty stack");
		isTrue(ItemCodec.tryDecode("broken").isEmpty(), "tryDecode empty");
	}

	private static void giveOrDrop(TestContext ctx) {
		FakePlayer p = ctx.player("TestA");
		Inventory inv = p.getInventory();
		List<ItemEntity> drops = new ArrayList<>();
		ctx.cleanup(() -> drops.forEach(Entity::discard));
		for (int i = 0; i < Items.MAIN_SIZE; i++) inv.setItem(i, new ItemStack(net.minecraft.world.item.Items.STONE, 64));
		inv.setItem(35, new ItemStack(net.minecraft.world.item.Items.DIRT, 60));
		inv.setItem(Inventory.SLOT_OFFHAND, new ItemStack(net.minecraft.world.item.Items.DIRT, 1));

		ItemStack input = new ItemStack(net.minecraft.world.item.Items.DIRT, 10);
		eq(4, Items.spaceFor(p, input), "space for dirt");
		isFalse(Items.canFit(p, input), "10 dirt do not fit");
		eq(6, Items.giveOrDrop(p, input, drops::add), "dropped");
		eq(10, input.getCount(), "input unchanged");
		eq(64, inv.getItem(35).getCount(), "stack filled");
		eq(1, inv.getItem(Inventory.SLOT_OFFHAND).getCount(), "offhand untouched");
		eq(1, drops.size(), "one drop entity");
		eq(6, drops.get(0).getItem().getCount(), "drop count");
		isTrue(drops.get(0).isRemoved(), "test player drop removed at once (no real items at spawn)");
		eq(1, ctx.drops(p).size(), "ctx.drops recorded");
		eq(6, ctx.drops(p).get(0).getCount(), "ctx.drops count");

		eq(100, Items.giveOrDrop(p, new ItemStack(net.minecraft.world.item.Items.DIAMOND, 100), drops::add), "full inventory");
		eq(3, drops.size(), "64 + 36 as two entities");
		eq(36, drops.get(2).getItem().getCount(), "remainder stack");
		eq(100, Items.giveOrDrop(p, new ItemStack(net.minecraft.world.item.Items.DIAMOND, 100)), "recorded without callback too");
		eq(5, ctx.drops(p).size(), "all drops in ctx.drops");

		inv.setItem(0, ItemStack.EMPTY);
		eq(64, Items.spaceFor(p, new ItemStack(net.minecraft.world.item.Items.DIAMOND)), "one free slot");
		eq(0, Items.giveOrDrop(p, new ItemStack(net.minecraft.world.item.Items.DIAMOND, 64), drops::add), "fits exactly");
		eq(64, inv.getItem(0).getCount(), "diamonds in slot 0");
		eq(3, drops.size(), "no new drops");

		// Dead player: nothing into the inventory (lost on respawn), drop everything.
		p.setHealth(0);
		try {
			isFalse(Items.canReceive(p), "dead player cannot receive");
			inv.setItem(2, ItemStack.EMPTY);
			eq(5, Items.giveOrDrop(p, new ItemStack(net.minecraft.world.item.Items.EMERALD, 5), drops::add), "dead: everything dropped");
			isTrue(drops.get(drops.size() - 1).isRemoved(), "dead: test player drop removed");
			isTrue(inv.getItem(2).isEmpty(), "dead: inventory unchanged");
		} finally {
			p.setHealth(p.getMaxHealth());
		}
		isTrue(Items.canReceive(p), "alive: can receive again");

		inv.setItem(2, new ItemStack(net.minecraft.world.item.Items.STONE, 64)); // emptied in the death test
		inv.setItem(1, ItemStack.EMPTY);
		isTrue(Items.canFitAll(p, List.of(new ItemStack(net.minecraft.world.item.Items.GOLD_INGOT, 64))), "64 gold fit");
		isFalse(Items.canFitAll(p, List.of(new ItemStack(net.minecraft.world.item.Items.GOLD_INGOT, 64), new ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 1))), "65 items do not");
	}

	private static void countRemove(TestContext ctx) {
		FakePlayer p = ctx.player("TestA");
		Inventory inv = p.getInventory();
		ItemStack named = new ItemStack(net.minecraft.world.item.Items.STONE, 5);
		named.set(DataComponents.CUSTOM_NAME, Component.literal("Special"));
		inv.setItem(0, new ItemStack(net.minecraft.world.item.Items.STONE, 10));
		inv.setItem(5, new ItemStack(net.minecraft.world.item.Items.STONE, 20));
		inv.setItem(7, named);
		inv.setItem(Inventory.SLOT_OFFHAND, new ItemStack(net.minecraft.world.item.Items.STONE, 3));
		eq(35, Items.count(p, s -> s.is(net.minecraft.world.item.Items.STONE)), "count without offhand");
		eq(30, Items.countPristine(p, net.minecraft.world.item.Items.STONE), "countPristine");
		eq(25, Items.remove(p, s -> s.is(net.minecraft.world.item.Items.STONE) && Items.isPristine(s), 25), "remove 25");
		eq(5, Items.countPristine(p, net.minecraft.world.item.Items.STONE), "rest pristine");
		eq(5, inv.getItem(7).getCount(), "named stone untouched");
		eq(5, Items.remove(p, s -> s.is(net.minecraft.world.item.Items.STONE) && Items.isPristine(s), 100), "only those present");
		eq(3, inv.getItem(Inventory.SLOT_OFFHAND).getCount(), "offhand untouched");

		isTrue(Items.isPristine(new ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD)), "new sword pristine");
		ItemStack damaged = new ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD);
		damaged.setDamageValue(1);
		isFalse(Items.isPristine(damaged), "damaged");
		isFalse(Items.isPristine(named), "named");
		isFalse(Items.isPristine(ItemStack.EMPTY), "empty");
		isTrue(Items.byId("diamond").isPresent(), "byId diamond");
		eq(net.minecraft.world.item.Items.DIAMOND, Items.byId("minecraft:diamond").orElse(null), "byId minecraft:diamond");
		isFalse(Items.byId("minecraft:nonexistent").isPresent(), "unknown id");
		isFalse(Items.byId("air").isPresent(), "air");
		isFalse(Items.byId("Not A Valid Name").isPresent(), "invalid id");
		eq("minecraft:diamond", Items.id(net.minecraft.world.item.Items.DIAMOND), "id");
	}

	private static void placedMarkUnmark(TestContext ctx) {
		ServerLevel level = ctx.overworld();
		ServerLevel nether = ctx.server().getLevel(Level.NETHER);
		BlockPos pos = new BlockPos(1_234_567, -60, 7_654_321);
		ctx.cleanup(() -> {
			PlacedBlockTracker.unmark(level, pos);
			if (nether != null) PlacedBlockTracker.unmark(nether, pos);
		});
		isFalse(PlacedBlockTracker.isPlayerPlaced(level, pos), "free initially");
		int before = PlacedBlockTracker.size();
		PlacedBlockTracker.markPlaced(level, pos);
		isTrue(PlacedBlockTracker.isPlayerPlaced(level, pos), "marked");
		eq(before + 1, PlacedBlockTracker.size(), "size");
		if (nether != null) isFalse(PlacedBlockTracker.isPlayerPlaced(nether, pos), "other dimension separate");
		isFalse(PlacedBlockTracker.isPlayerPlaced(level, pos.above()), "neighbor free");
		PlacedBlockTracker.unmark(level, pos);
		isFalse(PlacedBlockTracker.isPlayerPlaced(level, pos), "removed");
		eq(before, PlacedBlockTracker.size(), "size back");

		// Deferred: air removes only at tick end, other blocks never.
		PlacedBlockTracker.markPlaced(level, pos);
		PlacedBlockTracker.onBlockChanged(level, pos, Blocks.STRIPPED_OAK_LOG.defaultBlockState());
		PlacedBlockTracker.endTick();
		isTrue(PlacedBlockTracker.isPlayerPlaced(level, pos), "state change keeps mark");
		PlacedBlockTracker.onBlockChanged(level, pos, Blocks.AIR.defaultBlockState());
		isTrue(PlacedBlockTracker.isPlayerPlaced(level, pos), "still marked in the same tick");
		PlacedBlockTracker.endTick();
		isFalse(PlacedBlockTracker.isPlayerPlaced(level, pos), "removed after tick end");
		PlacedBlockTracker.markPlaced(level, pos);
		PlacedBlockTracker.onBlockChanged(level, pos, Blocks.WATER.defaultBlockState());
		PlacedBlockTracker.markPlaced(level, pos); // replaced in the same tick: stays
		PlacedBlockTracker.endTick();
		isTrue(PlacedBlockTracker.isPlayerPlaced(level, pos), "replacing cancels removal");
	}

	private static void placedPersist(TestContext ctx) throws Exception {
		PlacedBlockTracker.Store store = new PlacedBlockTracker.Store();
		long a = BlockPos.asLong(1, 2, 3);
		long b = BlockPos.asLong(-30_000_000, -64, 29_999_999);
		long c = BlockPos.asLong(5, 319, -5);
		store.add("minecraft:overworld", a);
		store.add("minecraft:the_nether", b);
		store.add("minecraft:overworld", c);
		store.scheduleRemoval("minecraft:overworld", c); // removal deferred: do not save it
		PlacedBlockTracker.Store loaded = new PlacedBlockTracker.Store();
		loaded.readFrom(new ByteArrayInputStream(store.toBytes()));
		eq(2, loaded.size(), "count");
		isTrue(loaded.contains("minecraft:overworld", a), "a");
		isTrue(loaded.contains("minecraft:the_nether", b), "b (extreme values)");
		isFalse(loaded.contains("minecraft:overworld", c), "c (removed)");
		isFalse(loaded.contains("minecraft:overworld", b), "dimensions separate");

		ServerLevel level = ctx.overworld();
		BlockPos pos = new BlockPos(7_654_321, 100, -1_234_567);
		ctx.cleanup(() -> PlacedBlockTracker.unmark(level, pos));
		PlacedBlockTracker.markPlaced(level, pos);
		PlacedBlockTracker.save();
		PlacedBlockTracker.Store fromFile = new PlacedBlockTracker.Store();
		fromFile.readFrom(Files.newInputStream(DataStore.dataDir().resolve("placed_blocks.dat")));
		isTrue(fromFile.contains(level.dimension().identifier().toString(), pos.asLong()), "in placed_blocks.dat");
		assertThrows(java.io.IOException.class, () -> new PlacedBlockTracker.Store().readFrom(new ByteArrayInputStream(new byte[] {1, 2, 3})), "garbage is rejected");
	}

	/** Tests the real mixins (Level.setBlock, FallingBlockEntity.fall) at the world height limit above spawn. */
	/** The core mixins set origin tags even with jobs off, since missions read them too. */
	private static void mobOriginTags(TestContext ctx) {
		ServerLevel level = ctx.overworld();
		List<Entity> created = new ArrayList<>();
		ctx.cleanup(() -> created.forEach(Entity::discard));
		Entity bred = EntityType.HOGLIN.create(level, EntitySpawnReason.BREEDING);
		Entity summoned = EntityType.VEX.create(level, EntitySpawnReason.MOB_SUMMONED);
		Entity egg = EntityType.ZOMBIE.create(level, EntitySpawnReason.SPAWN_ITEM_USE);
		Entity natural = EntityType.ZOMBIE.create(level, EntitySpawnReason.NATURAL);
		Villager parent = EntityType.VILLAGER.create(level, EntitySpawnReason.COMMAND);
		created.add(bred);
		created.add(summoned);
		created.add(egg);
		created.add(natural);
		created.add(parent);
		Entity baby = parent.getBreedOffspring(level, parent);
		created.add(baby);
		isTrue(bred.getTags().contains(MobOrigin.BRED), "BREEDING tagged (core EntityTypeOriginMixin)");
		isTrue(summoned.getTags().contains(MobOrigin.SUMMONED), "MOB_SUMMONED tagged");
		isTrue(egg.getTags().contains(MobOrigin.SPAWN_EGG), "spawn egg tagged");
		isTrue(natural.getTags().isEmpty(), "natural mob untagged");
		isTrue(baby != null && baby.getTags().contains(MobOrigin.BRED), "villager offspring tagged (core VillagerOriginMixin)");
		ctx.detail("jobs " + (dev.smpeconomy.core.module.Modules.isEnabled("jobs") ? "on" : "off") + " , tagging independent of it");
	}

	private static void timezoneConfig() {
		eq(java.time.ZoneId.of("America/New_York"), dev.smpeconomy.core.TimeUtil.parseZone("America/New_York"), "zone id");
		eq(java.time.ZoneId.of("UTC"), dev.smpeconomy.core.TimeUtil.parseZone("UTC"), "UTC");
		eq(java.time.ZoneId.systemDefault(), dev.smpeconomy.core.TimeUtil.parseZone("system"), "system = host zone");
		eq(java.time.ZoneId.systemDefault(), dev.smpeconomy.core.TimeUtil.parseZone(""), "empty = host zone");
		assertThrows(IllegalArgumentException.class, () -> dev.smpeconomy.core.TimeUtil.parseZone("Mars/Olympus"), "unknown zone");
		dev.smpeconomy.core.CoreConfig bad = new dev.smpeconomy.core.CoreConfig();
		bad.timezone = "Nowhere/Town";
		assertThrows(IllegalArgumentException.class, () -> dev.smpeconomy.core.CoreConfig.validate(bad), "validate rejects unknown zone");
		dev.smpeconomy.core.CoreConfig ok = new dev.smpeconomy.core.CoreConfig();
		ok.timezone = "Asia/Kolkata";
		dev.smpeconomy.core.CoreConfig.validate(ok);
		eq(SelfTests.TEST_ZONE, dev.smpeconomy.core.TimeUtil.zone(), "selftests run in the fixed test zone");
		eq("system", new dev.smpeconomy.core.CoreConfig().timezone, "default: system");
	}

	private static void currencySymbolTexts(TestContext ctx) {
		String sym = Money.symbol();
		boolean after = Money.symbolAfter();
		ctx.cleanup(() -> Money.setCurrency(sym, after));
		Money.setCurrency("€", true);
		for (String s : List.of(Money.format(123_456), Money.format(-5), Money.formatShort(10_000), Money.formatShort(100_000),
			Money.formatShort(250_000_000), Money.withSymbol(false, "0.005"), Msg.moneyStr(200))) {
			isFalse(s.contains("$"), "no $: " + s);
			isTrue(s.contains("€"), "€ present: " + s);
		}
		eq("1,234.56€", Money.format(123_456), "symbol after");
		eq("100€", Money.formatShort(10_000), "short form");
		ctx.detail("€ after the amount: " + Money.format(123_456) + ", " + Money.formatShort(100_000));
	}

	private static void placedWorldHooks(TestContext ctx) {
		ServerLevel level = ctx.overworld();
		BlockPos spawn = level.getRespawnData().pos();
		BlockPos pos = new BlockPos(spawn.getX(), level.getMaxY(), spawn.getZ());
		level.getChunk(pos); // loads the chunk if needed
		if (!level.getBlockState(pos).isAir()) {
			ctx.detail("skipped: world height limit above spawn is not free");
			return;
		}
		ctx.cleanup(() -> {
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
			PlacedBlockTracker.unmark(level, pos);
		});
		PlacedBlockTracker.markPlaced(level, pos);
		level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
		level.setBlock(pos, Blocks.COBBLESTONE.defaultBlockState(), 3);
		isTrue(PlacedBlockTracker.isPlayerPlaced(level, pos), "block change keeps mark");
		level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
		isTrue(PlacedBlockTracker.isPlayerPlaced(level, pos), "still marked in the breaking tick (Level mixin deferred)");
		PlacedBlockTracker.endTick();
		isFalse(PlacedBlockTracker.isPlayerPlaced(level, pos), "removed after tick end (Level mixin active)");

		level.setBlock(pos, Blocks.SAND.defaultBlockState(), 3);
		PlacedBlockTracker.markPlaced(level, pos);
		FallingBlockEntity falling = FallingBlockEntity.fall(level, pos, Blocks.SAND.defaultBlockState());
		try {
			isTrue(falling.getTags().contains(PlacedBlockTracker.FALLING_TAG), "falling block carries tag (Falling mixin active)");
		} finally {
			falling.discard();
		}
		PlacedBlockTracker.endTick();
		isFalse(PlacedBlockTracker.isPlayerPlaced(level, pos), "start position free");

		// Landing: the falling sand's block is marked again (FallingBlockEntity#tick calls setBlock, from 26.3
		// setBlockAndUpdate). Stone floor directly below so the sand lands in the first tick.
		BlockPos floor = pos.below();
		if (!level.getBlockState(floor).isAir()) {
			ctx.detail("Level and Falling mixins active (landing skipped: space below not free)");
			return;
		}
		ctx.cleanup(() -> {
			level.setBlock(floor, Blocks.AIR.defaultBlockState(), 3);
			PlacedBlockTracker.unmark(level, floor);
		});
		level.setBlock(floor, Blocks.STONE.defaultBlockState(), 3);
		level.setBlock(pos, Blocks.SAND.defaultBlockState(), 3);
		PlacedBlockTracker.markPlaced(level, pos);
		FallingBlockEntity dropped = FallingBlockEntity.fall(level, pos, Blocks.SAND.defaultBlockState());
		try {
			PlacedBlockTracker.endTick();
			isFalse(PlacedBlockTracker.isPlayerPlaced(level, pos), "free before landing");
			for (int i = 0; i < 40 && !dropped.isRemoved(); i++) dropped.tick();
			isTrue(dropped.isRemoved() && level.getBlockState(pos).is(Blocks.SAND), "sand landed");
			isTrue(PlacedBlockTracker.isPlayerPlaced(level, pos), "landed block marked (Falling mixin active on landing)");
		} finally {
			if (!dropped.isRemoved()) dropped.discard();
		}
		ctx.detail("Level and Falling mixins active (also on landing)");
	}

	private static void placedFluidGenerated(TestContext ctx) {
		ServerLevel level = ctx.overworld();
		BlockPos spawn = level.getRespawnData().pos();
		BlockPos lavaPos = new BlockPos(spawn.getX() + 3, level.getMaxY(), spawn.getZ() + 3);
		BlockPos waterSide = lavaPos.east();
		BlockPos waterBelow = new BlockPos(spawn.getX() + 6, level.getMaxY() - 1, spawn.getZ() + 3);
		BlockPos lavaAbove = waterBelow.above();
		List<BlockPos> all = List.of(lavaPos, waterSide, waterBelow, lavaAbove);
		for (BlockPos p : all) {
			level.getChunk(p);
			if (!level.getBlockState(p).isAir()) {
				ctx.detail("skipped: world height limit above spawn is not free");
				return;
			}
		}
		ctx.cleanup(() -> {
			for (BlockPos p : all) {
				level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
				PlacedBlockTracker.unmark(level, p);
			}
		});

		level.setBlock(waterSide, Blocks.WATER.defaultBlockState(), 3);
		isFalse(PlacedBlockTracker.isPlayerPlaced(level, lavaPos), "not marked initially");
		level.setBlock(lavaPos, Blocks.LAVA.defaultBlockState(), 3);
		eq(Blocks.OBSIDIAN, level.getBlockState(lavaPos).getBlock(), "lava source + water = obsidian");
		isTrue(PlacedBlockTracker.isPlayerPlaced(level, lavaPos), "generator block marked (LiquidBlock mixin active)");
		level.setBlock(lavaPos, Blocks.AIR.defaultBlockState(), 3);
		PlacedBlockTracker.endTick();
		isFalse(PlacedBlockTracker.isPlayerPlaced(level, lavaPos), "removed after breaking");

		level.setBlock(waterBelow, Blocks.WATER.defaultBlockState(), 3);
		level.setBlock(lavaAbove, Blocks.LAVA.defaultBlockState(), 3);
		level.getFluidState(lavaAbove).tick(level, lavaAbove, level.getBlockState(lavaAbove));
		eq(Blocks.STONE, level.getBlockState(waterBelow).getBlock(), "lava over water = stone");
		isTrue(PlacedBlockTracker.isPlayerPlaced(level, waterBelow), "stone generator marked (LavaFluid mixin active)");
		ctx.detail("LiquidBlock and LavaFluid mixins active");
	}
}
