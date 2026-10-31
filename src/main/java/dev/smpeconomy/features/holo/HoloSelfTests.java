package dev.smpeconomy.features.holo;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;
import dev.smpeconomy.core.compat.Mc;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.Services;
import dev.smpeconomy.core.TimeUtil;
import dev.smpeconomy.core.Treasury;
import dev.smpeconomy.core.test.Assert;
import dev.smpeconomy.core.test.SelfTests;
import dev.smpeconomy.core.test.TestPlayers;
import dev.smpeconomy.features.holo.HoloStore.Holo;
import dev.smpeconomy.features.holo.HoloText.Row;
import dev.smpeconomy.features.holo.HoloText.Score;

final class HoloSelfTests {
	private static final String F = "holo";

	private HoloSelfTests() {}

	private static UUID uuid(String name) {
		return UUID.nameUUIDFromBytes(("holo-selftest:" + name).getBytes(StandardCharsets.UTF_8));
	}

	static void register() {
		SelfTests.register(F, "types", ctx -> {
			Assert.eq(HoloType.MONEY, HoloType.byId("money").orElse(null), "money");
			Assert.eq(HoloType.KILLS, HoloType.byId("kills").orElse(null), "kills");
			Assert.eq(HoloType.PLAYTIME, HoloType.byId(" PlayTime ").orElse(null), "case + whitespace");
			Assert.eq(HoloType.JOBS, HoloType.byId("JOBS").orElse(null), "jobs");
			Assert.isTrue(HoloType.byId("nonexistent").isEmpty(), "unknown type");
			Assert.isTrue(HoloType.byId("").isEmpty(), "empty");
			Assert.isTrue(HoloType.byId(null).isEmpty(), "null");
			Assert.eq("money|kills|playtime|jobs", HoloType.idList(), "idList");
			ctx.detail("4 types; unknown, empty, null rejected");
		});

		SelfTests.register(F, "text-layout", ctx -> {
			List<Row> rows = List.of(new Row("Levi", "$1.2M"), new Row("Anna", "$300K"), new Row("Bob", "$12K"), new Row("Carl", "$5"));
			List<String> lines = HoloText.lines(HoloType.MONEY, rows, 10, "14:05");
			Assert.eq(14, lines.size(), "title + separator + 10 places + separator + time");
			Assert.eq(HoloType.MONEY.title, lines.get(0), "title");
			Assert.eq(HoloText.SEPARATOR, lines.get(1), "top separator");
			Assert.eq("§6#1 §fLevi §8» §a$1.2M", lines.get(2), "place 1 gold");
			Assert.eq("§f#2 §fAnna §8» §a$300K", lines.get(3), "place 2 silver");
			Assert.eq("§c#3 §fBob §8» §a$12K", lines.get(4), "place 3 bronze");
			Assert.eq("§7#4 §7Carl §8» §a$5", lines.get(5), "place 4 gray");
			Assert.eq("§8#5 §7-", lines.get(6), "placeholder 5");
			Assert.eq("§8#10 §7-", lines.get(11), "placeholder 10");
			Assert.eq(HoloText.SEPARATOR, lines.get(12), "bottom separator");
			Assert.eq("§7Updated: §f14:05", lines.get(13), "time");
			List<String> kills = HoloText.lines(HoloType.KILLS, List.of(new Row("Max", "3 Kills")), 10, "08:00");
			Assert.eq("§6#1 §fMax §8» §c3 Kills", kills.get(2), "kills color");
			Assert.eq(HoloType.KILLS.title, kills.get(0), "kills title");
			String plain = HoloText.plain(lines);
			Assert.isTrue(plain.startsWith("★ Richest Players ★\n"), "plain text title: " + plain);
			Assert.isTrue(plain.contains("\n#1 Levi » $1.2M\n"), "plain text place 1");
			Assert.isFalse(plain.contains("§"), "plain text without §");
			Assert.eq(14, plain.split("\n", -1).length, "14 plain text lines");
			Component c = HoloText.component(lines);
			Assert.eq(plain, c.getString(), "component = plain text");
			Assert.isFalse(c.getString().contains("§"), "component without § characters");
			Component title = c.getSiblings().get(0);
			Assert.isTrue(title.getStyle().isBold(), "title bold");
			Assert.eq(TextColor.fromLegacyFormat(ChatFormatting.GOLD), title.getStyle().getColor(), "title gold");
			ctx.detail("14 lines, color per place, component ok");
		});

		SelfTests.register(F, "text-empty-and-limit", ctx -> {
			List<String> empty = HoloText.lines(HoloType.PLAYTIME, List.of(), 10, "00:00");
			Assert.eq(14, empty.size(), "empty: same height");
			for (int r = 1; r <= 10; r++) Assert.eq(HoloText.placeholder(r), empty.get(r + 1), "placeholder " + r);
			Assert.eq(empty, HoloText.lines(HoloType.PLAYTIME, null, 10, "00:00"), "null rows same as empty");
			List<Row> many = new ArrayList<>();
			for (int i = 1; i <= 15; i++) many.add(new Row("P" + i, i + "h"));
			List<String> three = HoloText.lines(HoloType.PLAYTIME, many, 3, "12:00");
			Assert.eq(7, three.size(), "only 3 places");
			Assert.eq("§c#3 §fP3 §8» §b3h", three.get(4), "place 3");
			Assert.isFalse(HoloText.plain(three).contains("P4"), "place 4 cut off");
			Assert.eq(5, HoloText.lines(HoloType.JOBS, many, 0, "12:00").size(), "entries 0 -> 1 place");
			List<String> noStand = HoloText.lines(HoloType.JOBS, List.of(), 1, null);
			Assert.eq("§7Updating …", noStand.get(noStand.size() - 1), "without time");
			Assert.eq("§6#1 §fX §8» §e-", HoloText.rankLine(HoloType.JOBS, 1, new Row("X", "")), "empty value -> -");
			Assert.eq("§6#1 §fX §8» §e-", HoloText.rankLine(HoloType.JOBS, 1, new Row("X", null)), "null value → -");
			ctx.detail("empty, null, 15->3, entries 0, no time");
		});

		SelfTests.register(F, "text-names", ctx -> {
			Assert.eq("Evil", HoloText.cleanName("§cEvil§l"), "§ codes removed");
			Assert.eq("BadName", HoloText.cleanName("Bad\nName"), "line break removed");
			Assert.eq("ABCDEFGHIJKLMNOP", HoloText.cleanName("ABCDEFGHIJKLMNOPQRST"), "cut to 16");
			Assert.eq("Unknown", HoloText.cleanName(null), "null");
			Assert.eq("Unknown", HoloText.cleanName("   "), "only whitespace");
			Assert.eq("Unknown", HoloText.cleanName("§"), "only §");
			Assert.eq("§6#1 §fEvil §8» §a$5", HoloText.rankLine(HoloType.MONEY, 1, new Row("§k§cEvil\r\n", "§4$5")), "no format injection");
			List<String> lines = HoloText.lines(HoloType.MONEY, List.of(new Row("A\nB\nC", "$1\n$2")), 10, "1\n2");
			Assert.eq(14, HoloText.plain(lines).split("\n", -1).length, "line count holds despite \\n in data");
			ctx.detail("§, control characters, length, empty");
		});

		SelfTests.register(F, "ranking", ctx -> {
			UUID a = uuid("a"), b = uuid("b"), c = uuid("c"), d = uuid("d"), e = uuid("e"), f = uuid("f");
			List<Score> in = new ArrayList<>();
			in.add(new Score(a, "Zed", 100, 0, "100"));
			in.add(new Score(b, "Bea", 300, 0, "300"));
			in.add(new Score(c, "Cid", 100, 5, "100+"));
			in.add(new Score(d, "Dan", 0, 9, "0"));
			in.add(new Score(e, "Eve", -5, 0, "-5"));
			in.add(null);
			in.add(new Score(f, "aaron", 100, 0, "100"));
			List<Score> r = HoloText.rank(in, 10);
			Assert.eq(4, r.size(), "0, negative, null dropped");
			Assert.eq(b, r.get(0).id(), "highest value first");
			Assert.eq(c, r.get(1).id(), "tie: more secondary first");
			Assert.eq(f, r.get(2).id(), "tie: name A-Z (case-insensitive)");
			Assert.eq(a, r.get(3).id(), "Zed last");
			Assert.eq(2, HoloText.rank(in, 2).size(), "Limit 2");
			Assert.eq(0, HoloText.rank(in, 0).size(), "Limit 0");
			Assert.eq(0, HoloText.rank(null, 5).size(), "null list");
			Assert.eq(r, HoloText.rank(new ArrayList<>(List.of(r.get(3), r.get(1), r.get(0), r.get(2))), 10), "deterministic");
			ctx.detail("sorting, ties, exclusion, limits");
		});

		SelfTests.register(F, "money-with-bank", ctx -> {
			UUID a = uuid("ga"), b = uuid("gb"), c = uuid("gc"), d = uuid("gd");
			List<EconomyService.Entry> top = List.of(
				new EconomyService.Entry(a, "A", 1_000),
				new EconomyService.Entry(b, "B", 0),
				new EconomyService.Entry(c, "C", Money.MAX - 10),
				new EconomyService.Entry(d, "D", 50));
			Map<UUID, Long> bank = Map.of(a, 500L, c, 100L, d, -100L);
			List<Score> s = Leaderboards.money(top, id -> bank.getOrDefault(id, 0L));
			Assert.eq(3, s.size(), "account 0 without bank is missing");
			List<Score> r = HoloText.rank(s, 10);
			Assert.eq(c, r.get(0).id(), "largest wealth first");
			Assert.eq(Money.MAX, r.get(0).primary(), "capped at Money.MAX");
			Assert.eq(a, r.get(1).id(), "A second");
			Assert.eq(1_500, r.get(1).primary(), "account + bank");
			Assert.eq(Money.formatShort(1_500), r.get(1).value(), "short format");
			Assert.eq(50, r.get(2).primary(), "negative bank ignored");
			Assert.eq(1_000, Leaderboards.money(top, null).get(0).primary(), "without bank source only account");
			Assert.eq(Money.MAX, Leaderboards.addCapped(Long.MAX_VALUE, Long.MAX_VALUE), "no overflow");
			Assert.eq(Money.MAX, Leaderboards.addCapped(0, Long.MAX_VALUE), "bank > MAX capped");
			Assert.eq(3, Leaderboards.addCapped(-5, 3), "negative account as 0");
			Assert.eq(0, Leaderboards.bankSource(false).applyAsLong(a), "includeBank=false");
			Assert.eq(0, Leaderboards.money(null, null).size(), "null list");
			ctx.detail("bank added, cap, exclusion");
		});

		SelfTests.register(F, "jobs-bester-job", ctx -> {
			Leaderboards.BestJob bj = Leaderboards.bestJob(Map.of("miner", 12, "farmer", 12), Map.of("miner", 100L, "farmer", 300L));
			Assert.eq(new Leaderboards.BestJob("farmer", 12, 300), bj, "same level: more XP");
			Assert.eq(new Leaderboards.BestJob("miner", 20, 0), Leaderboards.bestJob(Map.of("miner", 20, "farmer", 12), Map.of("farmer", 9_999L)), "higher level before XP");
			Assert.isTrue(Leaderboards.bestJob(Map.of("miner", 1), Map.of()) == null, "level 1 without XP doesn't count");
			Assert.eq(new Leaderboards.BestJob("digger", 1, 50), Leaderboards.bestJob(Map.of(), Map.of("digger", 50L)), "only XP: level 1");
			Assert.isTrue(Leaderboards.bestJob(null, null) == null, "null-Maps");
			Assert.eq("a", Leaderboards.bestJob(Map.of("b", 5, "a", 5), Map.of()).job(), "tie: job id");
			Map<String, Integer> lv = new HashMap<>();
			Map<String, Long> xp = new HashMap<>();
			lv.put("x", null);
			xp.put("x", null);
			Assert.isTrue(Leaderboards.bestJob(lv, xp) == null, "null values = level 1, 0 XP");
			Assert.eq("Lumberjack", Leaderboards.jobLabel("lumberjack"), "lumberjack");
			Assert.eq("Lumberjack", Leaderboards.jobLabel("lumberjack"), "Lumberjack");
			Assert.eq("Hunter", Leaderboards.jobLabel("HUNTER"), "hunter");
			Assert.eq("Digger", Leaderboards.jobLabel("digger"), "digger");
			Assert.eq("Fisher", Leaderboards.jobLabel("fisher"), "Fisher");
			Assert.eq("Blacksmith", Leaderboards.jobLabel("blacksmith"), "unknown -> capitalized");
			Assert.eq("Super miner", Leaderboards.jobLabel("super_miner"), "underscore");
			Assert.eq("Job", Leaderboards.jobLabel(""), "empty");
			Assert.eq("Job", Leaderboards.jobLabel(null), "null");

			FakePlayer p = ctx.player("HoloJobs");
			UUID id = p.getUUID();
			PlayerDataService.setJobLevel(id, "miner", 7);
			PlayerDataService.setJobXp(id, "miner", 42);
			PlayerDataService.setJobXp(id, "farmer", 900);
			List<Score> s = Leaderboards.jobs(List.of(id), u -> "HoloJobs");
			Assert.eq(1, s.size(), "one entry");
			Assert.eq(7, s.get(0).primary(), "Level 7");
			Assert.eq(42, s.get(0).secondary(), "XP of the best job");
			Assert.eq("Miner Lvl 7", s.get(0).value(), "display");
			Assert.isFalse(Leaderboards.candidates(ctx.server()).contains(id), "test players never on real leaderboards");
			for (Score x : Leaderboards.collect(ctx.server(), HoloType.JOBS)) Assert.isFalse(id.equals(x.id()), "jobs leaderboard without test players");
			ctx.detail("level, XP, ties, labels, PlayerData");
		});

		SelfTests.register(F, "sources-without-test-players", ctx -> {
			MinecraftServer server = ctx.server();
			FakePlayer p = ctx.player("HoloTime");
			UUID id = p.getUUID();
			PlayerDataService.get(id).playtimeTicks = 72_000;
			PlayerDataService.markDirty();
			EconomyService.deposit(id, 5_000);
			Assert.isFalse(Leaderboards.candidates(server).contains(id), "test player not among candidates");
			Assert.isFalse(Leaderboards.candidates(server).contains(Treasury.ID), "treasury not among candidates");
			for (HoloType t : HoloType.values()) {
				for (Score s : Leaderboards.collect(server, t)) {
					Assert.isFalse(id.equals(s.id()), t.id + ": without test players");
					Assert.isFalse(Treasury.ID.equals(s.id()), t.id + ": without treasury");
				}
			}
			List<Score> explicit = Leaderboards.counts(List.of(id), PlayerDataService::getPlaytimeTicks, u -> "HoloTime", TimeUtil::formatTicks);
			Assert.eq(1, explicit.size(), "playtime source reads PlayerData");
			Assert.eq(72_000, explicit.get(0).primary(), "Ticks");
			Assert.eq(TimeUtil.formatTicks(72_000), explicit.get(0).value(), "Format");
			Assert.eq(0, Leaderboards.counts(List.of(uuid("nobody")), PlayerDataService::getPlaytimeTicks, u -> "x", TimeUtil::formatTicks).size(), "0 playtime is missing");
			Assert.eq("1 Kill", Leaderboards.killsText(1), "Singular");
			Assert.eq("1,234 Kills", Leaderboards.killsText(1234), "plural + thousands");
			ctx.detail("4 types without test or system accounts, playtime source");
		});

		SelfTests.register(F, "kills-sources", ctx -> {
			MinecraftServer server = ctx.server();
			UUID id = TestPlayers.uuid("HoloKills");
			Path dir = server.getWorldPath(LevelResource.PLAYER_STATS_DIR);
			Path file = dir.resolve(id + ".json");
			Files.deleteIfExists(file);
			Leaderboards.forgetCached(id);
			ctx.cleanup(() -> {
				try {
					Files.deleteIfExists(file);
				} catch (java.io.IOException ignored) {
				}
				Leaderboards.forgetCached(id);
			});
			Assert.eq(0, Leaderboards.vanillaKills(server, id), "no file gives 0");
			Files.createDirectories(dir);
			int version = SharedConstants.getCurrentVersion().dataVersion().version();
			Files.writeString(file, "{\"stats\":{\"minecraft:custom\":{\"minecraft:player_kills\":7,\"minecraft:deaths\":2}},\"DataVersion\":" + version + "}");
			Assert.eq(7, Leaderboards.vanillaKills(server, id), "vanilla stat offline");
			Files.writeString(file, "{\"stats\":{\"minecraft:custom\":{\"minecraft:player_kills\":9}},\"DataVersion\":" + version + "}");
			Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 5_000));
			Assert.eq(9, Leaderboards.vanillaKills(server, id), "cache detects changed file");
			Services.PlayerStats fake = new Services.PlayerStats() {
				@Override
				public long playerKills(UUID player) {
					return player.equals(id) ? 4 : -3;
				}

				@Override
				public long deaths(UUID player) {
					return 0;
				}

				@Override
				public long mobKills(UUID player) {
					return 0;
				}
			};
			Assert.eq(4, Leaderboards.killsSource(server, Optional.of(fake)).applyAsLong(id), "stats service takes precedence");
			Assert.eq(0, Leaderboards.killsSource(server, Optional.of(fake)).applyAsLong(uuid("x")), "negative -> 0");
			Services.PlayerStats broken = new Services.PlayerStats() {
				@Override
				public long playerKills(UUID player) {
					throw new IllegalStateException("selftest");
				}

				@Override
				public long deaths(UUID player) {
					return 0;
				}

				@Override
				public long mobKills(UUID player) {
					return 0;
				}
			};
			Assert.eq(9, Leaderboards.killsSource(server, Optional.of(broken)).applyAsLong(id), "service throws -> vanilla");
			Assert.eq(9, Leaderboards.killsSource(server, Optional.empty()).applyAsLong(id), "no service -> vanilla");
			List<Score> s = Leaderboards.counts(List.of(id), Leaderboards.killsSource(server, Optional.of(fake)), u -> "HoloKills", Leaderboards::killsText);
			Assert.eq("4 Kills", s.get(0).value(), "display");
			Files.delete(file);
			Assert.eq(0, Leaderboards.vanillaKills(server, id), "file deleted -> 0 (cache dropped)");
			ctx.detail("file, cache, service, service failure, deleted file");
		});

		SelfTests.register(F, "storage", ctx -> {
			HoloStore.Data d = new HoloStore.Data();
			Holo a = d.add(HoloType.MONEY, "minecraft:overworld", 10.123, 64, -5.5, "Levi", 1_000);
			Holo b = d.add(HoloType.KILLS, "minecraft:the_nether", 10, 64, -5, "Levi", 1_000);
			Holo c = d.add(HoloType.JOBS, "minecraft:overworld", 13, 64, -5.5, "Levi", 1_000);
			Assert.eq(1, a.id, "first id 1");
			Assert.eq(3, c.id, "sequential");
			Assert.eq(10.12, a.x, "rounded to 2 places");
			Assert.eq("overworld 10.12, 64, -5.5", a.where(), "location text");
			Assert.eq(a, d.nearest("minecraft:overworld", 11, 64, -5.5, 5).orElse(null), "nearest");
			Assert.eq(c, d.nearest("minecraft:overworld", 13, 64, -5.5, 5).orElse(null), "exactly on it");
			Assert.isTrue(d.nearest("minecraft:overworld", 100, 64, 0, 5).isEmpty(), "outside the radius");
			Assert.eq(b, d.nearest("minecraft:the_nether", 10, 64, -5, 5).orElse(null), "other dimension");
			Assert.isTrue(d.nearest("minecraft:the_end", 10, 64, -5, 5).isEmpty(), "wrong dimension");
			Holo tie = d.add(HoloType.MONEY, "minecraft:overworld", 12, 64, -5.5, "x", 0);
			Assert.eq(c, d.nearest("minecraft:overworld", 12.5, 64, -5.5, 5).orElse(null), "tie: smaller id");
			d.remove(tie.id);
			Assert.eq(a, d.remove(1), "removed");
			Assert.isTrue(d.byId(1) == null, "gone");
			Assert.isTrue(d.remove(1) == null, "remove twice");
			Assert.eq(5, d.add(HoloType.MONEY, "minecraft:overworld", 0, 0, 0, "", 0).id, "ids never reused");
			Assert.assertThrows(IllegalArgumentException.class, () -> d.add(HoloType.MONEY, "minecraft:overworld", Double.NaN, 0, 0, "", 0), "NaN rejected");

			HoloStore.Data r = new HoloStore.Data();
			r.nextId = 2;
			Holo x1 = new Holo();
			x1.id = 5;
			x1.type = "money";
			Holo x2 = new Holo();
			x2.id = 5;
			x2.type = "kills";
			Holo x3 = new Holo();
			x3.id = 0;
			x3.type = null;
			x3.dimension = null;
			x3.createdBy = null;
			Holo x4 = new Holo();
			x4.id = 9;
			x4.x = Double.POSITIVE_INFINITY;
			r.holos.add(null);
			r.holos.add(x1);
			r.holos.add(x2);
			r.holos.add(x3);
			r.holos.add(x4);
			Assert.isTrue(r.repair(), "repair reports change");
			Assert.eq(3, r.holos.size(), "null and infinite dropped");
			Assert.eq(5, x1.id, "first id stays");
			Assert.isTrue(x2.id != 5 && x2.id > 0, "duplicate id reassigned");
			Assert.isTrue(x3.id > 0 && x3.id != x2.id, "id 0 reassigned");
			Assert.eq("minecraft:overworld", x3.dimension, "dimension filled in");
			Assert.eq("money", x1.type, "type id money stays");
			Assert.eq("kills", x2.type, "type id kills stays");
			Assert.eq("", x3.type, "type filled in");
			Assert.isTrue(r.nextId > Math.max(x2.id, x3.id), "nextId past all ids");
			Assert.isFalse(r.repair(), "second repair changes nothing");

			// Hand-edited dimensions must become canonical, otherwise ownership never matches and the display respawns in a loop.
			HoloStore.Data n = new HoloStore.Data();
			n.nextId = 4;
			Holo n1 = new Holo();
			n1.id = 1;
			n1.type = "money";
			n1.dimension = "overworld";
			Holo n2 = new Holo();
			n2.id = 2;
			n2.type = "kills";
			n2.dimension = " minecraft:the_nether ";
			Holo n3 = new Holo();
			n3.id = 3;
			n3.type = "jobs";
			n3.dimension = "Broken World";
			n.holos.add(n1);
			n.holos.add(n2);
			n.holos.add(n3);
			Assert.isTrue(n.repair(), "dimension without namespace: repair reports change");
			Assert.eq("minecraft:overworld", n1.dimension, "overworld -> minecraft:overworld");
			Assert.eq("money", n1.type, "type id money");
			Assert.eq("minecraft:the_nether", n2.dimension, "whitespace removed");
			Assert.eq("Broken World", n3.dimension, "invalid dimension stays (warning only)");
			Assert.isFalse(n.repair(), "dimensions stable afterwards");
			Assert.eq(n1, n.nearest("minecraft:overworld", 0, 0, 0, 5).orElse(null), "nearest after repair");
			Assert.eq("minecraft:the_end", HoloStore.normalizeDimension(":the_end"), "empty namespace");
			Assert.eq("minecraft:overworld", HoloStore.normalizeDimension(null), "null -> overworld");
			Assert.eq("minecraft:the_nether", d.add(HoloType.MONEY, "the_nether", 0, 0, 0, "", 0).dimension, "add normalizes");
			Holo raw = new Holo();
			raw.id = 7;
			raw.type = "money";
			raw.dimension = "overworld";
			HoloStore.Data rd = new HoloStore.Data();
			rd.holos.add(raw);
			Assert.eq(raw, rd.nearest("minecraft:overworld", 0, 0, 0, 5).orElse(null), "nearest compares canonically");
			HoloStore.Data back = DataStore.gson().fromJson(DataStore.gson().toJson(d), HoloStore.Data.class);
			back.repair();
			Assert.eq(d.holos.size(), back.holos.size(), "JSON round trip");
			Assert.eq(d.nextId, back.nextId, "nextId saved");
			Assert.eq("kills", back.byId(2).type, "type saved");
			Assert.eq("0", HoloStore.coord(-0.0), "coord -0");
			Assert.eq("3.25", HoloStore.coord(3.25), "coord 3.25");
			Assert.eq("-12.5", HoloStore.coord(-12.5), "coord -12.5");
			ctx.detail("ids, nearest, remove, repair, dimensions, JSON");
		});

		SelfTests.register(F, "display-style-and-ownership", ctx -> {
			ServerLevel level = ctx.overworld();
			Display.TextDisplay td = EntityType.TEXT_DISPLAY.create(level, EntitySpawnReason.COMMAND);
			Assert.notNull(td, "text_display can be created");
			HoloConfig cfg = new HoloConfig();
			cfg.background = "11223344";
			cfg.billboard = "vertical";
			cfg.lineWidth = 280;
			cfg.seeThrough = true;
			cfg.fullBright = false;
			cfg.viewRange = 2f;
			HoloManager.style(td, cfg);
			Assert.eq(Display.BillboardConstraints.VERTICAL, td.getBillboardConstraints(), "Billboard");
			Assert.eq(0x11223344, td.getBackgroundColor(), "background");
			Assert.eq(280, td.getLineWidth(), "line width");
			Assert.eq(Display.TextDisplay.FLAG_SHADOW | Display.TextDisplay.FLAG_SEE_THROUGH, td.getFlags(), "Flags");
			Assert.isTrue(td.getBrightnessOverride() == null, "without full brightness");
			Assert.eq(2f, td.getViewRange(), "view range");
			HoloConfig def = new HoloConfig();
			HoloManager.style(td, def);
			Assert.eq(Display.BillboardConstraints.CENTER, td.getBillboardConstraints(), "Default center");
			Assert.eq(0x80000000, td.getBackgroundColor(), "default background");
			Assert.eq(Display.TextDisplay.FLAG_SHADOW, td.getFlags(), "default shadow only");
			Assert.eq(Brightness.FULL_BRIGHT, td.getBrightnessOverride(), "default full bright");
			Assert.eq(300, td.getLineWidth(), "default width");
			List<String> lines = HoloText.lines(HoloType.MONEY, List.of(new Row("Levi", "$5")), 10, "10:00");
			td.setText(HoloText.component(lines));
			Assert.eq(HoloText.plain(lines), td.getText().getString(), "text on display");

			UUID u1 = uuid("e1"), u2 = uuid("e2"), u3 = uuid("e3"), u4 = uuid("e4");
			HoloStore.Data d = new HoloStore.Data();
			Holo h1 = d.add(HoloType.MONEY, "minecraft:overworld", 0, 64, 0, "", 0);
			Holo h2 = d.add(HoloType.KILLS, "minecraft:the_nether", 0, 64, 0, "", 0);
			Holo h3 = d.add(HoloType.JOBS, "minecraft:overworld", 0, 64, 0, "", 0);
			h3.type = "nonexistent";
			Map<Integer, UUID> session = new HashMap<>();
			session.put(h1.id, u1);
			session.put(h2.id, u2);
			session.put(h3.id, u4);
			Assert.eq(h1.id, HoloManager.owner(u1, "minecraft:overworld", session, d::byId), "valid display kept");
			Assert.eq(h2.id, HoloManager.owner(u2, "minecraft:the_nether", session, d::byId), "Nether");
			Assert.eq(-1, HoloManager.owner(u1, "minecraft:the_nether", session, d::byId), "wrong dimension -> remove");
			Assert.eq(-1, HoloManager.owner(u3, "minecraft:overworld", session, d::byId), "old session or copy -> remove");
			Assert.eq(-1, HoloManager.owner(u4, "minecraft:overworld", session, d::byId), "unknown type -> remove");
			Assert.eq(-1, HoloManager.owner(null, "minecraft:overworld", session, d::byId), "without UUID");
			Assert.eq(-1, HoloManager.owner(u1, "minecraft:overworld", Map.of(), d::byId), "new session: everything old gone");
			h2.dimension = "the_nether";
			Assert.eq(h2.id, HoloManager.owner(u2, "minecraft:the_nether", session, d::byId), "dimension without namespace: keep");
			Assert.eq(-1, HoloManager.owner(u2, "minecraft:overworld", session, d::byId), "without namespace, wrong dimension");
			d.remove(h1.id);
			Assert.eq(-1, HoloManager.owner(u1, "minecraft:overworld", session, d::byId), "deleted hologram -> remove");
			ctx.detail("style (config, default), text, ownership rules");
		});

		SelfTests.register(F, "config", ctx -> {
			HoloConfig.validate(new HoloConfig());
			HoloConfig.validate(HoloConfig.get());
			Assert.eq(0x80000000, HoloConfig.parseArgb("80000000"), "8 digits");
			Assert.eq(0xFFFF0000, HoloConfig.parseArgb("#FF0000"), "6 digits opaque");
			Assert.eq(0x40000000, HoloConfig.parseArgb("0x40000000"), "0x prefix");
			Assert.eq(0, HoloConfig.parseArgb("00000000"), "no background");
			Assert.eq(Display.BillboardConstraints.FIXED, HoloConfig.parseBillboard(" FIXED "), "billboard case");
			String[][] bad = {
				{"updateSeconds", "5"}, {"updateSeconds", "4000"}, {"entries", "0"}, {"entries", "21"}, {"lineWidth", "10"},
				{"background", "xyz"}, {"background", "1234567"}, {"background", null}, {"billboard", "diagonal"},
				{"viewRange", "0"}, {"viewRange", "NaN"}, {"maxHolos", "0"}, {"removeRadius", "100"}};
			for (String[] b : bad) {
				HoloConfig c = new HoloConfig();
				switch (b[0]) {
					case "updateSeconds" -> c.updateSeconds = Integer.parseInt(b[1]);
					case "entries" -> c.entries = Integer.parseInt(b[1]);
					case "lineWidth" -> c.lineWidth = Integer.parseInt(b[1]);
					case "background" -> c.background = b[1];
					case "billboard" -> c.billboard = b[1];
					case "viewRange" -> c.viewRange = Float.parseFloat(b[1]);
					case "maxHolos" -> c.maxHolos = Integer.parseInt(b[1]);
					case "removeRadius" -> c.removeRadius = Double.parseDouble(b[1]);
					default -> Assert.fail("test case " + b[0]);
				}
				Assert.assertThrows(IllegalArgumentException.class, () -> HoloConfig.validate(c), b[0] + "=" + b[1] + " rejected");
			}
			ctx.detail(bad.length + " invalid values rejected");
		});

		SelfTests.register(F, "welt-displays", ctx -> {
			MinecraftServer server = ctx.server();
			ServerPlayer anchor = realPlayer(server);
			if (anchor == null) {
				ctx.detail("skipped (no player online, real displays need a chunk)");
				return;
			}
			ServerLevel level = anchor.level();
			double x = anchor.getX(), y = Math.min(anchor.getY() + 2, level.getMaxY() - 1), z = anchor.getZ();
			if (!Mc.canSpawnAt(level, x, z)) {
				ctx.detail("skipped (chunk near player still loading)");
				return;
			}
			Holo h = HoloStore.data().add(HoloType.PLAYTIME, HoloManager.dimensionId(level), x, y, z, "selftest", System.currentTimeMillis());
			HoloStore.markDirty();
			List<Display.TextDisplay> extra = new ArrayList<>();
			ctx.cleanup(() -> {
				HoloCommands.remove(server, h.id);
				for (Display.TextDisplay e : extra) {
					if (!e.isRemoved()) e.discard();
				}
				HoloManager.processPending();
			});
			String tag = HoloManager.TAG_ID + h.id;
			Assert.isTrue(HoloManager.create(server, h), "display placed");
			Display.TextDisplay first = HoloManager.live(h.id);
			Assert.notNull(first, "display loaded");
			Assert.isTrue(first.getTags().contains(HoloManager.TAG) && first.getTags().contains(tag), "Tags");
			Assert.isTrue(first.getText().getString().startsWith("★ Most Playtime ★"), "title on display");
			Assert.eq(1, count(level, tag), "exactly one display");

			HoloManager.processPending();
			HoloManager.ensureAll(server);
			Assert.isFalse(first.isRemoved(), "own display stays after check");
			Assert.eq(1, count(level, tag), "no second one after ensure");

			Display.TextDisplay copy = EntityType.TEXT_DISPLAY.create(level, EntitySpawnReason.COMMAND);
			copy.snapTo(x, y, z, 0f, 0f);
			copy.addTag(HoloManager.TAG);
			copy.addTag(tag);
			extra.add(copy);
			Assert.isTrue(level.addFreshEntity(copy), "copy placed");
			HoloManager.processPending();
			Assert.isTrue(copy.isRemoved(), "copy removed");
			Assert.isFalse(first.isRemoved(), "original stays");
			Assert.eq(1, count(level, tag), "exactly one again");

			first.teleportTo(x + 3, y, z);
			HoloManager.ensureAll(server);
			Assert.isTrue(first.distanceToSqr(x, y, z) < 0.01, "moved back");

			UUID oldUuid = first.getUUID();
			first.discard();
			HoloManager.ensureAll(server);
			Display.TextDisplay second = HoloManager.live(h.id);
			Assert.notNull(second, "placed again");
			Assert.isFalse(oldUuid.equals(second.getUUID()), "new UUID");
			Assert.eq(1, count(level, tag), "exactly one after replacing");

			HoloManager.updateAll(server);
			Assert.eq(1, count(level, tag), "exactly one after update");

			Assert.notNull(HoloCommands.remove(server, h.id), "definition removed");
			Assert.isTrue(second.isRemoved(), "display removed");
			Assert.eq(0, count(level, tag), "none left");
			ctx.detail("place, copy removed, move back, replace, remove");
		});

		SelfTests.register(F, "world-display-without-tick", ctx -> {
			MinecraftServer server = ctx.server();
			ServerPlayer anchor = realPlayer(server);
			if (anchor == null) {
				ctx.detail("skipped (no player online, needs loaded chunks)");
				return;
			}
			ServerLevel level = anchor.level();
			// A chunk players can see but that doesn't tick (outside simulation distance, at the load border).
			ChunkPos chunk = fullButNotTicking(level, anchor.chunkPosition(), 40);
			if (chunk == null) {
				ctx.detail("skipped (no loaded, non-ticking chunk nearby)");
				return;
			}
			double x = chunk.getMiddleBlockX() + 0.5, z = chunk.getMiddleBlockZ() + 0.5;
			double y = Math.min(anchor.getY() + 2, level.getMaxY() - 1);
			Assert.isFalse(level.areEntitiesActuallyLoadedAndTicking(chunk), "chunk doesn't tick");
			Assert.isTrue(level.areEntitiesLoaded(Mc.chunkKey(chunk)) && level.getChunkSource().hasChunk(Mc.chunkX(chunk), Mc.chunkZ(chunk)), "chunk FULL, entities loaded");
			Assert.isTrue(Mc.canSpawnAt(level, x, z), "may place there");

			String dim = HoloManager.dimensionId(level);
			Holo h = HoloStore.data().add(HoloType.PLAYTIME, dim, x, y, z, "selftest", System.currentTimeMillis());
			Holo far = HoloStore.data().add(HoloType.KILLS, dim, x + 480_000, y, z, "selftest", System.currentTimeMillis());
			HoloStore.markDirty();
			List<Display.TextDisplay> extra = new ArrayList<>();
			ctx.cleanup(() -> {
				HoloCommands.remove(server, h.id);
				HoloCommands.remove(server, far.id);
				for (Display.TextDisplay e : extra) {
					if (!e.isRemoved()) e.discard();
				}
				HoloManager.processPending();
			});
			String tag = HoloManager.TAG_ID + h.id;

			Assert.isFalse(Mc.canSpawnAt(level, far.x, far.z), "unloaded chunk: don't place");
			HoloManager.ensureAll(server);
			Assert.isTrue(HoloManager.live(far.id) == null, "unloaded chunk: no display");
			Assert.eq(0, count(level, HoloManager.TAG_ID + far.id), "unloaded chunk: nothing in the world");

			Display.TextDisplay first = HoloManager.live(h.id);
			Assert.notNull(first, "display placed in non-ticking chunk");
			Assert.isTrue(level.getEntity(first.getUUID()) == first, "found by UUID (TRACKED)");
			Assert.isTrue(first.getText().getString().startsWith("★ Most Playtime ★"), "title on display");
			Assert.eq(1, count(level, tag), "exactly one display");

			HoloManager.processPending();
			HoloManager.ensureAll(server);
			HoloManager.ensureAll(server);
			Assert.isFalse(first.isRemoved(), "own display stays");
			Assert.isTrue(HoloManager.live(h.id) == first, "no new display on further passes");
			Assert.eq(1, count(level, tag), "no second one after ensure");

			// ENTITY_LOAD also fires in non-ticking chunks.
			Display.TextDisplay copy = EntityType.TEXT_DISPLAY.create(level, EntitySpawnReason.COMMAND);
			copy.snapTo(x, y, z, 0f, 0f);
			copy.addTag(HoloManager.TAG);
			copy.addTag(tag);
			extra.add(copy);
			Assert.isTrue(level.addFreshEntity(copy), "copy placed");
			HoloManager.processPending();
			Assert.isTrue(copy.isRemoved(), "copy removed");
			Assert.isFalse(first.isRemoved(), "original stays");

			// Vanilla only syncs changes in non-ticking chunks with needsSync.
			first.needsSync = false;
			first.setText(Component.literal("old"));
			HoloManager.apply(first, h, server);
			Assert.isTrue(first.getText().getString().startsWith("★ Most Playtime ★"), "text restored");
			Assert.isTrue(first.needsSync, "text change marked for sending");
			first.teleportTo(x + 3, y, z);
			first.needsSync = false;
			HoloManager.ensureAll(server);
			Assert.isTrue(first.distanceToSqr(x, y, z) < 0.01, "moved -> moved back");
			Assert.isTrue(first.needsSync, "move back marked for sending");

			UUID oldUuid = first.getUUID();
			first.discard();
			HoloManager.ensureAll(server);
			Display.TextDisplay second = HoloManager.live(h.id);
			Assert.notNull(second, "placed again without tick");
			Assert.isFalse(oldUuid.equals(second.getUUID()), "new UUID");
			Assert.isTrue(level.getEntity(second.getUUID()) == second, "new display can be found");
			HoloManager.ensureAll(server);
			Assert.isTrue(HoloManager.live(h.id) == second, "stable afterwards");
			Assert.eq(1, count(level, tag), "exactly one after replacing");

			Assert.notNull(HoloCommands.remove(server, h.id), "definition removed");
			Assert.isTrue(second.isRemoved(), "display removed");
			Assert.eq(0, count(level, tag), "none left");
			ctx.detail("Chunk " + Mc.chunkX(chunk) + "," + Mc.chunkZ(chunk) + " (" + chunk.getChessboardDistance(anchor.chunkPosition())
				+ " from player): place, no duplicate, copy gone, sync, replace; unloaded: nothing");
		});
	}

	private static ServerPlayer realPlayer(MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!(p instanceof FakePlayer)) return p;
		}
		return null;
	}

	private static ChunkPos fullButNotTicking(ServerLevel level, ChunkPos center, int maxRadius) {
		for (int r = 1; r <= maxRadius; r++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
					ChunkPos c = new ChunkPos(Mc.chunkX(center) + dx, Mc.chunkZ(center) + dz);
					if (level.areEntitiesLoaded(Mc.chunkKey(c)) && level.getChunkSource().hasChunk(Mc.chunkX(c), Mc.chunkZ(c))
						&& !level.areEntitiesActuallyLoadedAndTicking(c)) return c;
				}
			}
		}
		return null;
	}

	private static int count(ServerLevel level, String tag) {
		int n = 0;
		for (Display.TextDisplay e : level.getEntities(EntityType.TEXT_DISPLAY, e -> e.getTags().contains(tag))) {
			if (!e.isRemoved()) n++;
		}
		return n;
	}
}
