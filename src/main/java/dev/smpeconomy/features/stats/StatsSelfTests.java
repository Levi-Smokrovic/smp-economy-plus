package dev.smpeconomy.features.stats;

import static dev.smpeconomy.core.test.Assert.assertThrows;
import static dev.smpeconomy.core.test.Assert.check;
import static dev.smpeconomy.core.test.Assert.eq;
import static dev.smpeconomy.core.test.Assert.isFalse;
import static dev.smpeconomy.core.test.Assert.isTrue;

import eu.pb4.sgui.api.gui.SimpleGui;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.stats.Stats;
import net.minecraft.stats.StatsCounter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.Blocks;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.Hooks;
import dev.smpeconomy.core.Services;
import dev.smpeconomy.core.TimeUtil;
import dev.smpeconomy.core.Treasury;
import dev.smpeconomy.core.test.SelfTests;
import dev.smpeconomy.core.test.TestContext;
import dev.smpeconomy.core.test.TestPlayers;

final class StatsSelfTests {
	private static final String F = StatsFeature.ID;

	private StatsSelfTests() {}

	static void register() {
		SelfTests.register(F, "service-registered", StatsSelfTests::serviceRegistered);
		SelfTests.register(F, "pvp-kill-hook", StatsSelfTests::pvpKillHook);
		SelfTests.register(F, "pvp-ignored-cases", StatsSelfTests::pvpIgnored);
		SelfTests.register(F, "death-resets-streak", StatsSelfTests::deathResetsStreak);
		SelfTests.register(F, "offline-cache", StatsSelfTests::offlineCache);
		SelfTests.register(F, "vanilla-read", StatsSelfTests::vanillaRead);
		SelfTests.register(F, "baseline-seeding", ctx -> baselineSeeding());
		SelfTests.register(F, "import-vanilla-file", StatsSelfTests::importVanillaFile);
		SelfTests.register(F, "leaderboard-rank", ctx -> leaderboardRank());
		SelfTests.register(F, "leaderboard-page", ctx -> leaderboardPage());
		SelfTests.register(F, "leaderboard-render", ctx -> leaderboardRender());
		SelfTests.register(F, "leaderboard-excludes-test", StatsSelfTests::leaderboardExcludesTest);
		SelfTests.register(F, "category-parse-format", ctx -> categoryParseFormat());
		SelfTests.register(F, "data-repair", ctx -> dataRepair());
		SelfTests.register(F, "format-helpers", ctx -> formatHelpers());
		SelfTests.register(F, "config-validate", ctx -> configValidate());
		SelfTests.register(F, "gui-build", StatsSelfTests::guiBuild);
	}

	private static Services.PlayerStats service() {
		return Services.find(Services.PlayerStats.class).orElseThrow(() -> new AssertionError("PlayerStats service not registered"));
	}

	private static StatsStore.Rec rec(FakePlayer p) {
		return StatsStore.peek(p.getUUID()).orElseThrow(() -> new AssertionError("no record for " + p.getPlainTextName()));
	}

	/**
	 * The global PvP hook reaches all listeners (bounty, tasks, missions and so on), so only use it with two
	 * different selftest players, whose data TestPlayers cleanup removes. Invalid pairs (suicide, foreign
	 * FakePlayers) are only tested against our own listener ({@link StatsStore#recordKill}).
	 */
	private static void fireKill(FakePlayer killer, FakePlayer victim) {
		check(TestPlayers.isTestPlayer(killer.getUUID()) && TestPlayers.isTestPlayer(victim.getUUID())
			&& !killer.getUUID().equals(victim.getUUID()), "PvP hook only with two different test players");
		Hooks.firePlayerKilledPlayer(killer, victim);
	}

	private static void serviceRegistered(TestContext ctx) {
		check(service() == StatsService.INSTANCE, "Services.PlayerStats is the stats service");
		UUID unknown = UUID.randomUUID();
		eq(0, service().playerKills(unknown), "unknown: kills");
		eq(0, service().deaths(unknown), "unknown: deaths");
		eq(0, service().mobKills(unknown), "unknown: mob kills");
		eq(0, service().playerKills(null), "null UUID: kills");
		ctx.detail("service registered, unknown players = 0");
	}

	private static void pvpKillHook(TestContext ctx) {
		FakePlayer a = ctx.player("StatsA");
		FakePlayer b = ctx.player("StatsB");
		ctx.preserveTreasury(); // Integrated listeners (e.g. the bounty fee) may book through the treasury.
		Services.PlayerStats svc = service();
		eq(0, svc.playerKills(a.getUUID()), "A starts without kills");

		fireKill(a, b);
		eq(1, svc.playerKills(a.getUUID()), "one hook = exactly one kill");
		eq(0, svc.playerKills(b.getUUID()), "victim gets no kill");
		eq(1, rec(b).pvpDeaths, "victim: one PvP death");
		eq(0, rec(a).pvpBaseline, "FakePlayer without earlier kills");

		fireKill(a, b);
		eq(2, svc.playerKills(a.getUUID()), "second kill");
		eq(2, rec(a).streak, "streak 2");
		eq(2, rec(a).bestStreak, "record 2");
		eq(2, rec(b).pvpDeaths, "victim: two PvP deaths");

		fireKill(b, a);
		eq(1, svc.playerKills(b.getUUID()), "counter kill");
		eq(0, rec(a).streak, "death ends A's streak");
		eq(2, rec(a).bestStreak, "record stays");
		eq(1, rec(a).pvpDeaths, "A: one PvP death");
		eq(2, svc.playerKills(a.getUUID()), "A's kills stay");
		ctx.detail("kills, PvP deaths and streak counted correctly");
	}

	private static void pvpIgnored(TestContext ctx) {
		// Core never delivers suicide or foreign FakePlayers through the hook and other features rely on that,
		// so never send these cases to all listeners through Hooks.firePlayerKilledPlayer.
		FakePlayer a = ctx.player("StatsC");
		FakePlayer b = ctx.player("StatsD");
		StatsStore.recordKill(a, a);
		isFalse(StatsStore.peek(a.getUUID()).isPresent(), "suicide creates nothing");
		StatsStore.recordKill(null, a);
		StatsStore.recordKill(a, null);
		StatsStore.recordKill(null, null);
		isFalse(StatsStore.peek(a.getUUID()).isPresent(), "null is ignored");

		FakePlayer foreign = FakePlayer.get(ctx.overworld());
		isFalse(TestPlayers.isTestPlayer(foreign.getUUID()), "standard FakePlayer is not a test player");
		isFalse(StatsStore.trackable(foreign), "foreign FakePlayer is not counted");
		isTrue(StatsStore.trackable(b), "selftest player is counted");
		boolean foreignBefore = StatsStore.peek(foreign.getUUID()).isPresent();
		StatsStore.recordKill(foreign, b);
		StatsStore.recordKill(b, foreign);
		StatsStore.recordDeath(foreign);
		eq(foreignBefore, StatsStore.peek(foreign.getUUID()).isPresent(), "foreign FakePlayer gets no record");
		isFalse(StatsStore.peek(b.getUUID()).isPresent(), "kill by or on a foreign FakePlayer doesn't count");
		ctx.detail("suicide, null, foreign FakePlayers ignored");
	}

	private static void deathResetsStreak(TestContext ctx) {
		FakePlayer a = ctx.player("StatsE");
		FakePlayer b = ctx.player("StatsF");
		ctx.preserveTreasury();
		for (int i = 0; i < 3; i++) fireKill(a, b);
		eq(3, rec(a).streak, "streak 3");
		StatsStore.recordDeath(a);
		eq(0, rec(a).streak, "environmental death ends the streak");
		eq(3, rec(a).bestStreak, "record stays 3");
		eq(3, service().playerKills(a.getUUID()), "environmental death changes no kills");
		eq(0, rec(a).pvpDeaths, "environmental death is no PvP death");
		fireKill(a, b);
		eq(1, rec(a).streak, "new streak");
		eq(3, rec(a).bestStreak, "record unchanged");
		ctx.detail("streak and record correct");
	}

	private static void offlineCache(TestContext ctx) {
		FakePlayer a = ctx.player("StatsG");
		StatsStore.Rec r = StatsStore.ensure(a, 0);
		StatsStore.apply(r, new StatsStore.Vanilla(9, 4, 17, 100, 5000));
		Services.PlayerStats svc = service();
		eq(4, svc.deaths(a.getUUID()), "offline: deaths from cache");
		eq(17, svc.mobKills(a.getUUID()), "offline: mob kills from cache");
		eq(0, svc.playerKills(a.getUUID()), "vanilla player_kills in cache don't change counted kills");
		StatsStore.apply(r, new StatsStore.Vanilla(9, 2, 20, 100, 5000));
		eq(2, svc.deaths(a.getUUID()), "cache is replaced, not added");
		eq(20, svc.mobKills(a.getUUID()), "mob kills replaced");
		StatsStore.apply(r, new StatsStore.Vanilla(-1, -5, -3, -1, -1));
		eq(0, svc.deaths(a.getUUID()), "negative -> 0");
		eq(0, r.walkedCm, "distance negative -> 0");
		StatsStore.apply(r, null);
		eq(0, svc.mobKills(a.getUUID()), "null changes nothing");
		StatsService.View v = StatsService.view(a.getUUID());
		isFalse(v.online(), "FakePlayer counts as offline");
		eq("StatsG", v.name(), "name from record or profile");
		ctx.detail("offline values from stats.json");
	}

	private static void vanillaRead(TestContext ctx) {
		FakePlayer a = ctx.player("StatsH");
		StatsCounter c = new StatsCounter();
		c.setValue(a, Stats.CUSTOM.get(Stats.PLAYER_KILLS), 3);
		c.setValue(a, Stats.CUSTOM.get(Stats.DEATHS), 7);
		c.setValue(a, Stats.CUSTOM.get(Stats.MOB_KILLS), 11);
		c.setValue(a, Stats.BLOCK_MINED.get(Blocks.STONE), 4);
		c.setValue(a, Stats.BLOCK_MINED.get(Blocks.DIRT), 2);
		c.setValue(a, Stats.CUSTOM.get(Stats.WALK_ONE_CM), 150);
		c.setValue(a, Stats.CUSTOM.get(Stats.SPRINT_ONE_CM), 50);
		c.setValue(a, Stats.CUSTOM.get(Stats.FLY_ONE_CM), 1000);
		StatsStore.Vanilla v = StatsStore.Vanilla.read(c);
		eq(new StatsStore.Vanilla(3, 7, 11, 6, 200), v, "vanilla values read");
		eq(StatsStore.Vanilla.ZERO, StatsStore.Vanilla.read(new StatsCounter()), "empty counter = 0");
		eq(null, StatsStore.live(a), "FakePlayer: no live values");
		eq(-1, StatsStore.liveCustom(a, Stats.DEATHS), "FakePlayer: no single live value");
		ctx.detail("kills, deaths, mobs, blocks, distance");
	}

	private static void baselineSeeding() {
		StatsStore.Rec r = StatsStore.newRecord("X", new StatsStore.Vanilla(5, 2, 3, 0, 0), 1, 1000);
		eq(4, r.pvpBaseline, "kill already in vanilla -> not counted twice");
		eq(2, r.deaths, "deaths taken over");
		eq(1000, r.firstSeen, "first contact");
		eq(5, StatsStore.newRecord("X", new StatsStore.Vanilla(5, 0, 0, 0, 0), 0, 0).pvpBaseline, "without already counted kills");
		eq(0, StatsStore.newRecord("X", new StatsStore.Vanilla(0, 0, 0, 0, 0), 1, 0).pvpBaseline, "never negative");
		eq(0, StatsStore.newRecord(null, null, 0, 0).pvpBaseline, "without vanilla values");
		eq("", StatsStore.newRecord(null, null, 0, 0).name, "name null -> empty");
		r.pvpKills = 3;
		eq(7, StatsService.displayKills(r, true), "with earlier kills");
		eq(3, StatsService.displayKills(r, false), "only counted kills");
		r.pvpKills = Long.MAX_VALUE - 1;
		eq(Long.MAX_VALUE, StatsService.displayKills(r, true), "overflow capped");
		eq(Long.MAX_VALUE, StatsStore.plus(Long.MAX_VALUE, 1), "plus capped");
	}

	private static void importVanillaFile(TestContext ctx) throws Exception {
		UUID id = TestPlayers.uuid("StatsImport");
		ctx.cleanup(() -> StatsStore.remove(id));
		Path dir = Files.createTempDirectory("smpeco-stats-selftest");
		Path file = dir.resolve(id + ".json");
		ctx.cleanup(() -> {
			try {
				Files.deleteIfExists(file);
				Files.deleteIfExists(dir);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
		int dataVersion = SharedConstants.getCurrentVersion().dataVersion().version();
		Files.writeString(file, "{\"stats\":{\"minecraft:custom\":{\"minecraft:player_kills\":2,\"minecraft:deaths\":3,"
			+ "\"minecraft:mob_kills\":9,\"minecraft:walk_one_cm\":250},\"minecraft:mined\":{\"minecraft:stone\":4,\"minecraft:dirt\":1}},"
			+ "\"DataVersion\":" + dataVersion + "}");
		Files.setLastModifiedTime(file, FileTime.fromMillis(1_700_000_000_000L));

		isFalse(StatsStore.importFile(id, "Imp", dir.resolve("missing.json"), ctx.server()), "missing file -> nothing");
		isFalse(StatsStore.peek(id).isPresent(), "no record without a file");
		isTrue(StatsStore.importFile(id, "Imp", file, ctx.server()), "file imported");
		StatsStore.Rec r = StatsStore.peek(id).orElseThrow();
		eq(2, r.pvpBaseline, "earlier kills");
		eq(0, r.pvpKills, "no counted kills");
		eq(3, r.deaths, "deaths");
		eq(9, r.mobKills, "Mob-Kills");
		eq(5, r.blocksMined, "blocks");
		eq(250, r.walkedCm, "distance");
		eq(1_700_000_000_000L, r.lastSeen, "last seen = file mtime");
		eq(0, r.firstSeen, "first join unknown");
		eq(StatsFeature.config().countFormerKills ? 2L : 0L, service().playerKills(id), "service returns imported kills");
		eq(3, service().deaths(id), "service returns imported deaths");

		Files.writeString(file, "{\"stats\":{\"minecraft:custom\":{\"minecraft:deaths\":99}},\"DataVersion\":" + dataVersion + "}");
		isFalse(StatsStore.importFile(id, "Imp", file, ctx.server()), "existing record is not overwritten");
		eq(3, StatsStore.peek(id).orElseThrow().deaths, "deaths unchanged");

		UUID broken = TestPlayers.uuid("StatsImportBroken");
		ctx.cleanup(() -> StatsStore.remove(broken));
		Path bad = dir.resolve(broken + ".json");
		ctx.cleanup(() -> {
			try {
				Files.deleteIfExists(bad);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
		Files.writeString(bad, "{broken");
		isTrue(StatsStore.importFile(broken, "Broken", bad, ctx.server()), "broken file: record with 0 rather than a crash");
		eq(0, StatsStore.peek(broken).orElseThrow().deaths, "broken file -> 0");
		ctx.detail("import, no overwrite, broken file");
	}

	private static Leaderboard.Entry entry(String name, long value) {
		return new Leaderboard.Entry(UUID.nameUUIDFromBytes(("stats-selftest:" + name).getBytes()), name, value);
	}

	private static void leaderboardRank() {
		Leaderboard.Entry a = entry("Zed", 10);
		Leaderboard.Entry b = entry("bob", 5);
		Leaderboard.Entry c = entry("Alice", 5);
		Leaderboard.Entry d = entry("Null", 0);
		Leaderboard.Entry e = entry("Minus", -3);
		Leaderboard.Entry f = entry("Fritz", 1);
		List<Leaderboard.Ranked> list = Leaderboard.rank(List.of(f, e, d, c, b, a));
		eq(4, list.size(), "values <= 0 hidden");
		eq("Zed", list.get(0).entry().name(), "highest value first");
		eq("Alice", list.get(1).entry().name(), "tie: name (case-insensitive)");
		eq("bob", list.get(2).entry().name(), "tie: name");
		eq(List.of(1, 2, 2, 4), list.stream().map(Leaderboard.Ranked::rank).toList(), "tie shares the place");
		eq(4, Leaderboard.rankOf(list, f.id()), "place of Fritz");
		eq(0, Leaderboard.rankOf(list, d.id()), "0 value not listed");
		eq(0, Leaderboard.rankOf(list, null), "null not listed");
		eq(0, Leaderboard.rank(List.of()).size(), "empty list");
	}

	private static List<Leaderboard.Ranked> ranked(int n) {
		List<Leaderboard.Entry> entries = new ArrayList<>();
		for (int i = 1; i <= n; i++) entries.add(entry("P" + (100 + i), 1000 - i));
		return Leaderboard.rank(entries);
	}

	private static void leaderboardPage() {
		List<Leaderboard.Ranked> list = ranked(25);
		Leaderboard.Page p1 = Leaderboard.page(list, 1, 10);
		eq(3, p1.pages(), "25 entries = 3 pages");
		eq(10, p1.rows().size(), "full page");
		eq(1, p1.rows().get(0).rank(), "page 1 starts with #1");
		eq(11, Leaderboard.page(list, 2, 10).rows().get(0).rank(), "page 2 starts with #11");
		Leaderboard.Page last = Leaderboard.page(list, 99, 10);
		eq(3, last.page(), "page too high -> last");
		eq(5, last.rows().size(), "last page: rest");
		eq(1, Leaderboard.page(list, 0, 10).page(), "page 0 -> 1");
		eq(1, Leaderboard.page(list, -5, 10).page(), "negative page -> 1");
		Leaderboard.Page empty = Leaderboard.page(List.of(), 3, 10);
		eq(1, empty.page(), "empty: page 1");
		eq(1, empty.pages(), "empty: one page");
		eq(0, empty.rows().size(), "empty: no lines");
		eq(25, Leaderboard.page(list, 1, 0).pages(), "page size 0 -> 1");
	}

	private static List<String> plain(List<MutableComponent> lines) {
		return lines.stream().map(MutableComponent::getString).toList();
	}

	private static void leaderboardRender() {
		List<Leaderboard.Ranked> list = ranked(12);
		UUID last = list.get(11).entry().id();
		List<String> page2 = plain(Leaderboard.render(Leaderboard.Category.KILLS, list, 2, 10, last));
		check(page2.get(0).contains("Leaderboard") && page2.get(0).contains("Player Kills") && page2.get(0).contains("Page 2/2"), "header: " + page2.get(0));
		check(page2.get(1).contains("#11") && page2.get(1).contains("P111") && page2.get(1).contains("989"), "line #11: " + page2.get(1));
		check(page2.stream().anyMatch(l -> l.contains("Your rank: #12")), "own place: " + page2);
		check(page2.stream().anyMatch(l -> l.contains("Back") && l.contains("Next")), "paging present");
		List<String> stranger = plain(Leaderboard.render(Leaderboard.Category.DEATHS, list, 1, 10, UUID.randomUUID()));
		check(stranger.stream().anyMatch(l -> l.contains("not on this leaderboard yet")), "not listed: notice");
		List<String> console = plain(Leaderboard.render(Leaderboard.Category.DEATHS, list, 1, 20, null));
		isFalse(console.stream().anyMatch(l -> l.contains("Your rank") || l.contains("not on this leaderboard")), "console without own place");
		isFalse(console.stream().anyMatch(l -> l.contains("Next")), "one page: no paging");
		eq(1 + 12, console.size(), "header + 12 lines");
		List<String> empty = plain(Leaderboard.render(Leaderboard.Category.MOBS, List.of(), 1, 10, null));
		check(empty.stream().anyMatch(l -> l.contains("No entries yet")), "empty leaderboard");
		List<String> money = plain(Leaderboard.render(Leaderboard.Category.MONEY, Leaderboard.rank(List.of(entry("Rich", 123456))), 1, 10, null));
		check(money.get(1).contains("$1,234.56"), "money formatted: " + money.get(1));
		check(plain(Leaderboard.usage()).get(1).contains("playtime"), "overview lists categories");
	}

	private static void leaderboardExcludesTest(TestContext ctx) {
		FakePlayer a = ctx.player("StatsTop");
		FakePlayer b = ctx.player("StatsTopB");
		ctx.preserveTreasury();
		for (int i = 0; i < 5; i++) fireKill(a, b);
		eq(5, Leaderboard.value(Leaderboard.Category.KILLS, a.getUUID()), "value of the test player");
		isFalse(Leaderboard.candidates().contains(a.getUUID()), "test player no candidate");
		eq(0, Leaderboard.rankOf(Leaderboard.collect(Leaderboard.Category.KILLS), a.getUUID()), "test player not on the kill leaderboard");
		eq(0, Leaderboard.rankOf(Leaderboard.collect(Leaderboard.Category.MONEY), Treasury.ID), "treasury not on the money leaderboard");
		isFalse(Leaderboard.candidates().contains(Treasury.ID), "treasury no candidate");
		ctx.detail("test and system accounts hidden");
	}

	private static void categoryParseFormat() {
		eq(Leaderboard.Category.KILLS, Leaderboard.Category.parse("KILLS").orElse(null), "case-insensitive");
		eq(Leaderboard.Category.MONEY, Leaderboard.Category.parse("money").orElse(null), "money");
		eq(Leaderboard.Category.DEATHS, Leaderboard.Category.parse(" death ").orElse(null), "whitespace + alias death");
		eq(Leaderboard.Category.DEATHS, Leaderboard.Category.parse("deaths").orElse(null), "deaths");
		eq(Leaderboard.Category.PLAYTIME, Leaderboard.Category.parse("playtime").orElse(null), "playtime");
		eq(Leaderboard.Category.MOBS, Leaderboard.Category.parse("mobkills").orElse(null), "Alias mobkills");
		isTrue(Leaderboard.Category.parse("top").isEmpty(), "unknown");
		isTrue(Leaderboard.Category.parse("").isEmpty(), "empty");
		isTrue(Leaderboard.Category.parse(null).isEmpty(), "null");
		eq(List.of("money", "kills", "playtime", "deaths", "mobs"), Leaderboard.Category.ids(), "categories");
		eq("$123.45", Leaderboard.Category.MONEY.format(12345), "money");
		eq(TimeUtil.formatTicks(72_000), Leaderboard.Category.PLAYTIME.format(72_000), "playtime");
		eq("1h 00m", Leaderboard.Category.PLAYTIME.format(72_000), "1 h");
		eq("1,234", Leaderboard.Category.KILLS.format(1234), "number with separator");
	}

	private static void dataRepair() {
		UUID id = UUID.nameUUIDFromBytes("stats-selftest:repair".getBytes());
		StatsStore.Data d = DataStore.gson().fromJson("{\"players\":{\"" + id + "\":{\"name\":null,\"pvpKills\":-5,\"streak\":4,"
			+ "\"bestStreak\":2,\"deaths\":-1}}}", StatsStore.Data.class);
		StatsStore.repair(d);
		StatsStore.Rec r = d.players.get(id);
		eq("", r.name, "name null -> empty");
		eq(0, r.pvpKills, "negative -> 0");
		eq(0, r.deaths, "deaths negative -> 0");
		eq(4, r.bestStreak, "record >= streak");
		StatsStore.Data nul = DataStore.gson().fromJson("{\"players\":null}", StatsStore.Data.class);
		StatsStore.repair(nul);
		check(nul.players != null && nul.players.isEmpty(), "players null -> empty");
		StatsStore.Data withNull = DataStore.gson().fromJson("{\"players\":{\"" + id + "\":null}}", StatsStore.Data.class);
		StatsStore.repair(withNull);
		isTrue(withNull.players.isEmpty(), "null entry removed");
		StatsStore.Data fresh = DataStore.gson().fromJson("{}", StatsStore.Data.class);
		StatsStore.repair(fresh);
		isTrue(fresh.players.isEmpty(), "empty file structure");
	}

	private static void formatHelpers() {
		eq("3.00", StatsService.kd(3, 0), "no deaths = kills");
		eq("2.50", StatsService.kd(5, 2), "5/2");
		eq("0.00", StatsService.kd(0, 0), "0/0");
		eq("0 m", StatsService.distance(99), "under 1 m");
		eq("123 m", StatsService.distance(12_345), "Meter");
		eq("2.5 km", StatsService.distance(250_000), "km");
		eq("1,234.6 km", StatsService.distance(123_456_789), "long distance");
		eq("0 m", StatsService.distance(-5), "negative");
		eq("01.01.1970", StatsService.date(0), "Datum");
	}

	private static void configValidate() {
		new StatsConfig().validate();
		StatsConfig c = new StatsConfig();
		c.pageSize = 0;
		assertThrows(IllegalArgumentException.class, c::validate, "pageSize 0");
		c = new StatsConfig();
		c.pageSize = 21;
		assertThrows(IllegalArgumentException.class, c::validate, "pageSize 21");
		c = new StatsConfig();
		c.snapshotSeconds = 5;
		assertThrows(IllegalArgumentException.class, c::validate, "snapshotSeconds 5");
		c = new StatsConfig();
		c.snapshotSeconds = 3601;
		assertThrows(IllegalArgumentException.class, c::validate, "snapshotSeconds 3601");
	}

	private static String firstLore(ItemStack stack) {
		ItemLore lore = stack.get(DataComponents.LORE);
		return lore == null || lore.lines().isEmpty() ? "" : lore.lines().get(0).getString();
	}

	private static void guiBuild(TestContext ctx) {
		FakePlayer a = ctx.player("StatsGuiA");
		FakePlayer b = ctx.player("StatsGuiB");
		ctx.preserveTreasury();
		fireKill(a, b);
		fireKill(a, b);
		StatsStore.apply(rec(a), new StatsStore.Vanilla(0, 4, 13, 0, 0));
		SimpleGui gui = StatsGui.build(a, a.getUUID());
		isFalse(gui.isOpen(), "built only, not opened");
		check(gui.getTitle().getString().contains("Stats » StatsGuiA"), "title: " + gui.getTitle().getString());
		ItemStack head = gui.getSlot(StatsGui.SLOT_HEAD).getItemStack();
		eq(Items.PLAYER_HEAD, head.getItem(), "player head");
		isTrue(head.has(DataComponents.PROFILE), "head has a profile");
		eq(Items.DIAMOND_SWORD, gui.getSlot(StatsGui.SLOT_KILLS).getItemStack().getItem(), "Kills-Symbol");
		eq("2", firstLore(gui.getSlot(StatsGui.SLOT_KILLS).getItemStack()), "kills in lore");
		eq("4", firstLore(gui.getSlot(StatsGui.SLOT_DEATHS).getItemStack()), "deaths in lore");
		eq("0.50", firstLore(gui.getSlot(StatsGui.SLOT_KD).getItemStack()), "K/D in lore");
		eq("13", firstLore(gui.getSlot(StatsGui.SLOT_MOBS).getItemStack()), "mob kills in lore");
		eq(Items.BLACK_STAINED_GLASS_PANE, gui.getSlot(0).getItemStack().getItem(), "frame");
		eq(Items.GRAY_STAINED_GLASS_PANE, gui.getSlot(10).getItemStack().getItem(), "filler");
		eq(Items.BARRIER, gui.getSlot(StatsGui.SLOT_CLOSE).getItemStack().getItem(), "close");
		eq(Items.IRON_SWORD, gui.getSlot(StatsGui.SLOT_TOP_KILLS).getItemStack().getItem(), "leaderboard button");
		for (int i = 0; i < StatsGui.ROWS * 9; i++) check(gui.getSlot(i) != null, "Slot " + i + " used");

		SimpleGui unknown = StatsGui.build(a, UUID.randomUUID());
		eq("0", firstLore(unknown.getSlot(StatsGui.SLOT_KILLS).getItemStack()), "unknown: 0 kills");
		check(unknown.getTitle().getString().contains(StatsService.UNKNOWN), "unknown: title");
		ctx.detail("head, values, frame, buttons");
	}
}
