package dev.smpeconomy.features.hud;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.brigadier.StringReader;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.network.chat.numbers.BlankFormat;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.PriceModifiers;
import dev.smpeconomy.core.Services;
import dev.smpeconomy.core.Services.Missions.EliteStatus;
import dev.smpeconomy.core.Services.Missions.MissionProgress;
import dev.smpeconomy.core.Settings;
import dev.smpeconomy.core.TimeUtil;
import dev.smpeconomy.core.test.Assert;
import dev.smpeconomy.core.test.SelfTests;

final class HudSelfTests {
	private static final String F = "hud";
	private static final String MOD_SOURCE = "hud.selftest";

	private HudSelfTests() {}

	/** The default is empty, meaning no address line. */
	private static final String ADDR = "§7play.example.org";

	private static HudConfig cfg() {
		HudConfig c = new HudConfig();
		c.address = ADDR;
		HudConfig.validate(c);
		return c;
	}

	private static final HudLines.MissionInfo NO_MISSIONS = null;

	private static HudLines.JobInfo job(String id, int level) {
		return id == null ? HudLines.JobInfo.NONE : new HudLines.JobInfo(id, level, 0, -1, 0);
	}

	private static HudLines.Data data(long balance, HudLines.JobInfo job, int done, int total, HudLines.MissionInfo missions, long kills,
		long deaths, long ticks, int online, boolean shock, boolean fever) {
		return new HudLines.Data("Levi", balance, job, done, total, missions, kills, deaths, ticks, online, 20, shock, fever);
	}

	private static HudLines.Data data(long balance, String jobId, int level, int done, int total, long kills, long deaths, long ticks,
		int online, boolean shock, boolean fever) {
		return data(balance, job(jobId, level), done, total, NO_MISSIONS, kills, deaths, ticks, online, shock, fever);
	}

	private static HudLines.MissionInfo missions(int active, String name, long progress, long amount, EliteStatus elite) {
		return new HudLines.MissionInfo(active, 3, name == null ? null : new MissionProgress(name, progress, amount), elite);
	}

	private static List<String> texts(HudLines.Data d, HudConfig c) {
		return HudLines.texts(HudLines.sidebar(d, c));
	}

	static void register() {
		SelfTests.register(F, "lines-format", ctx -> {
			long ticks = 3 * 72_000L + 7 * 1200L;
			HudLines.Data full = data(123_456, new HudLines.JobInfo("miner", 12, 31, 50, 50), 1, 3,
				missions(2, "Smith Supplies", 20, 32, EliteStatus.OPEN), 5, 3, ticks, 4, false, false);
			List<String> lines = texts(full, cfg());
			Assert.eq(List.of(
				"",
				"§7Money: §a$1,234.56",
				"§7Job: §eMiner §8(Lv. 12)",
				"§8 ▸ §a■■■■■■§7■■■■ §f62%",
				"§7Missions: §f2§7/§f3",
				"§8 ▸ §fSmith Supplies §e20§7/§e32",
				"§7Elite: §eopen",
				"§7Tasks: §f1§7/§f3",
				"§7Kills: §f5 §8| §7Deaths: §f3",
				"§7Playtime: §f3h 07m",
				"§7Online: §f4",
				"",
				ADDR), lines, "sidebar lines");
			Assert.isTrue(lines.size() <= 13, "everything in, still at most 13 lines");
			Assert.eq(List.of(
				"",
				"§7Money: §a$1,234.56",
				"§7Job: §eMiner §8(Lv. 12)",
				"§7Tasks: §f1§7/§f3",
				"§7Kills: §f5 §8| §7Deaths: §f3",
				"§7Playtime: §f3h 07m",
				"§7Online: §f4",
				"",
				ADDR), texts(data(123_456, "miner", 12, 1, 3, 5, 3, ticks, 4, false, false), cfg()), "without services");
			HudConfig c = cfg();
			HudLines.Data max = data(1, new HudLines.JobInfo("miner", 1, 0, 50, 50), 0, 3, missions(1, "X", 0, 1, EliteStatus.DONE), 0, 0, 0, 1,
				true, true);
			Map<HudLines.Line, String> all = HudLines.sidebar(max, c);
			Assert.eq(HudLines.MAX_LINES, all.size(), "all 15 lines");
			Assert.eq(HudLines.Line.values().length, HudLines.MAX_LINES, "as many lines as the client shows");
			Assert.eq(c.address, texts(max, c).get(HudLines.MAX_LINES - 1), "address at the very bottom");
			Assert.eq("§8 ▸ §7■■■■■■■■■■ §f0%", all.get(HudLines.Line.JOB_PROGRESS), "0%: empty bar");
			Assert.eq("§7Elite: §a✔", all.get(HudLines.Line.ELITE), "elite done");
			Assert.eq("§6§lSMP Economy", HudConfig.title(), "title");
			Assert.eq("SMP Economy", Msg.legacy(HudConfig.title()).getString(), "title without color codes");
			Assert.eq("", new HudConfig().address, "default without address line");
			ctx.detail(lines.size() + " lines normal, " + all.size() + " at most");
		});

		SelfTests.register(F, "lines-limits", ctx -> {
			HudConfig c = cfg();
			Assert.eq("$0.00", HudLines.money(0), "0");
			Assert.eq("$0.00", HudLines.money(-500), "negative -> 0");
			Assert.eq("$0.01", HudLines.money(1), "1 Cent");
			Assert.eq("$999,999.99", HudLines.money(HudLines.SHORT_MONEY_FROM - 1), "just under 1M in full");
			Assert.eq("$1M", HudLines.money(HudLines.SHORT_MONEY_FROM), "1M short");
			Assert.eq("$1.5M", HudLines.money(150_000_000L), "1.5M");
			Assert.eq("$1.9M", HudLines.money(199_999_999L), "rounded down, never more than available");
			String max = HudLines.money(Money.MAX);
			Assert.isTrue(max.startsWith("$") && max.endsWith("T"), "maximum short: " + max);
			Assert.eq("§8none", HudLines.job(null, 0, c.jobNames), "no job");
			Assert.eq("§8none", HudLines.job("  ", 3, c.jobNames), "empty job");
			Assert.eq("§eMiner §8(Lv. 1)", HudLines.job("miner", 0, c.jobNames), "Level min. 1");
			Assert.eq("§eLumberjack §8(Lv. 50)", HudLines.job("lumberjack", 50, c.jobNames), "Lumberjack");
			Assert.eq("Miner", HudLines.jobName("MINER", c.jobNames), "id capitalized");
			Assert.eq("Stone mason", HudLines.jobName("stone_mason", c.jobNames), "unknown id");
			Assert.eq("Hunter", HudLines.jobName("smpeco:hunter", Map.of()), "namespace, no config");
			Assert.eq(null, HudLines.jobProgress(HudLines.JobInfo.NONE), "no job, no line");
			Assert.eq(null, HudLines.jobProgress(null), "null");
			Assert.eq(null, HudLines.jobProgress(job("miner", 3)), "no known threshold, no line");
			Assert.eq("§8 ▸ §a■■■■■■■■■§7■ §f99%", HudLines.jobProgress(new HudLines.JobInfo("miner", 3, 99, 100, 50)), "99% (never 100%)");
			Assert.eq("§8 ▸ §a■■■■■■■■■§7■ §f99%", HudLines.jobProgress(new HudLines.JobInfo("miner", 3, 500, 100, 50)), "XP over threshold -> 99%");
			Assert.eq("§8 ▸ §7■■■■■■■■■■ §f0%", HudLines.jobProgress(new HudLines.JobInfo("miner", 3, -5, 100, 50)), "negative XP → 0 %");
			Assert.eq("§8 ▸ §a■§7■■■■■■■■■ §f19%", HudLines.jobProgress(new HudLines.JobInfo("miner", 3, 19, 100, 50)), "rounded down");
			Assert.eq("§8 ▸ §7■■■■■■■■■■ §f0%", HudLines.jobProgress(new HudLines.JobInfo("miner", 3, 0, 1, 50)), "threshold 1");
			Assert.eq("§8 ▸ §a■■■■■§7■■■■■ §f50%",
				HudLines.jobProgress(new HudLines.JobInfo("miner", 3, 500_000_000_000_000L, 1_000_000_000_000_000L, 50)), "huge XP without overflow");
			Assert.eq("§8 ▸ §6Max Level", HudLines.jobProgress(new HudLines.JobInfo("miner", 50, 0, 0, 50)), "max level");
			Assert.eq("§8 ▸ §6Max Level", HudLines.jobProgress(new HudLines.JobInfo("miner", 60, 0, 0, 50)), "above max level");
			Assert.eq("§f0§7/§f3", HudLines.missions(0, 3), "0/3");
			Assert.eq("§f0§7/§f3", HudLines.missions(-2, 3), "negative -> 0");
			Assert.eq("§f4", HudLines.missions(4, 0), "no room given, only the number");
			Assert.eq(null, HudLines.missionProgress(null, 20), "no active mission, no line");
			Assert.eq("§8 ▸ §fMining §e1,024§7/§e2,048", HudLines.missionProgress(new MissionProgress("Mining", 1024, 2048), 20), "thousands separator");
			Assert.eq("§8 ▸ §fX §e1§7/§e1", HudLines.missionProgress(new MissionProgress("X", 5, 0), 20), "amount at least 1, progress <= amount");
			Assert.eq("§8 ▸ §fX §e0§7/§e3", HudLines.missionProgress(new MissionProgress("X", -1, 3), 20), "progress >= 0");
			Assert.eq("Iron Shipment XXL", HudLines.shortName("Iron Shipment XXL", 20), "17 characters fit");
			Assert.eq("Iron Shipment…", HudLines.shortName("Iron Shipment XXL", 15), "cut (no space before …)");
			Assert.eq("Mission", HudLines.shortName("  §a ", 20), "empty -> Mission");
			Assert.eq("Mission", HudLines.shortName(null, 20), "null → Mission");
			Assert.eq("Red", HudLines.shortName("§c§lRed", 20), "§ codes removed");
			Assert.eq(20, HudLines.shortName("x".repeat(60), 20).length(), "never longer than the maximum");
			Assert.eq(null, HudLines.elite(null), "null -> no line");
			Assert.eq(null, HudLines.elite(EliteStatus.NONE), "no daily elite -> no line");
			Assert.eq("§7Elite: §eopen", HudLines.elite(EliteStatus.OPEN), "open");
			Assert.eq("§7Elite: §a✔", HudLines.elite(EliteStatus.DONE), "done");
			Map<HudLines.Line, String> noNearest = HudLines.sidebar(data(0, HudLines.JobInfo.NONE, 0, 3, missions(0, null, 0, 0, EliteStatus.NONE),
				0, 0, 0, 1, false, false), c);
			Assert.eq("§7Missions: §f0§7/§f3", noNearest.get(HudLines.Line.MISSIONS), "0 active");
			Assert.isFalse(noNearest.containsKey(HudLines.Line.MISSION_PROGRESS) || noNearest.containsKey(HudLines.Line.ELITE)
				|| noNearest.containsKey(HudLines.Line.JOB_PROGRESS), "no mission, elite or job, no sub-lines");
			Assert.eq("§7Job: §8none", noNearest.get(HudLines.Line.JOB), "no job");
			Assert.eq("§8–", HudLines.tasks(-1, 3), "unknown");
			Assert.eq("§8–", HudLines.tasks(1, 0), "without tasks");
			Assert.eq("§f0§7/§f3", HudLines.tasks(0, 3), "0/3");
			Assert.eq("§a3/3 ✔", HudLines.tasks(3, 3), "all done");
			Assert.eq("§a3/3 ✔", HudLines.tasks(7, 3), "more than all -> 3/3");
			Assert.eq("0m", HudLines.playtime(0), "0");
			Assert.eq("0m", HudLines.playtime(-100), "negative");
			Assert.eq("0m", HudLines.playtime(1199), "under 1 min");
			Assert.eq("59m", HudLines.playtime(59 * 1200L), "59 min");
			Assert.eq("1h 00m", HudLines.playtime(72_000L), "1h");
			Assert.eq("23h 59m", HudLines.playtime(1439 * 1200L), "23h 59m");
			Assert.eq("1d 4h", HudLines.playtime(28 * 72_000L), "1d 4h");
			Assert.eq("400d 0h", HudLines.playtime(400L * 24 * 72_000L), "many days");
			Map<HudLines.Line, String> both = HudLines.sidebar(data(0, null, 0, -1, 3, 1_234_567, -3, 0, -1, true, true), c);
			Assert.eq("§7Kills: §f1,234,567 §8| §7Deaths: §f0", both.get(HudLines.Line.KILLS_DEATHS), "thousands separator, negative -> 0");
			Assert.eq("§7Online: §f0", both.get(HudLines.Line.ONLINE), "online never negative");
			List<String> bothTexts = HudLines.texts(both);
			Assert.eq(c.marketShockLine, bothTexts.get(bothTexts.size() - 3), "market shock line before the address");
			Assert.eq(c.auctionFeverLine, bothTexts.get(bothTexts.size() - 2), "fever line");
			Assert.eq(c.address, bothTexts.get(bothTexts.size() - 1), "address at the bottom");
			Assert.isTrue(both.size() <= HudLines.MAX_LINES, "at most 15 lines");
			c.address = "";
			c.marketShockLine = "";
			List<String> noAddr = texts(data(0, null, 0, 0, 3, 0, 0, 0, 1, true, false), c);
			Assert.eq("§7Online: §f1", noAddr.get(noAddr.size() - 1), "empty address and marker lines drop out, no trailing blank line");
			Assert.eq(7, noAddr.size(), "7 lines without address and marker");
		});

		SelfTests.register(F, "lines-toggles", ctx -> {
			HudLines.Data d = data(123_456, new HudLines.JobInfo("miner", 12, 31, 50, 50), 1, 3, missions(2, "Mining", 3, 32, EliteStatus.DONE),
				5, 3, 0, 4, true, true);
			Map<HudLines.Line, String> all = HudLines.sidebar(d, cfg());
			Assert.eq(HudLines.MAX_LINES, all.size(), "all on");
			for (String key : HudLines.Line.configKeys()) {
				HudConfig c = cfg();
				c.lines.put(key, false);
				Map<HudLines.Line, String> without = HudLines.sidebar(d, c);
				for (HudLines.Line line : HudLines.Line.values()) {
					if (line.configKey.equals(key)) Assert.isFalse(without.containsKey(line), key + ": " + line + " off");
					else Assert.eq(all.get(line), without.get(line), key + ": " + line + " unchanged");
				}
			}
			HudConfig none = cfg();
			none.lines.replaceAll((k, v) -> false);
			Assert.eq(0, HudLines.sidebar(d, none).size(), "all off -> only the title");
			HudConfig onlyAddress = cfg();
			onlyAddress.lines.replaceAll((k, v) -> "address".equals(k));
			Assert.eq(List.of(ADDR), texts(d, onlyAddress), "only address");
			Assert.eq(Set.of("money", "job", "jobProgress", "missions", "missionProgress", "elite", "tasks", "killsDeaths", "playtime", "online",
				"spacers", "marketShock", "auctionFever", "address"), HudLines.Line.configKeys(), "switch names (hud.json)");
			ctx.detail(HudLines.Line.configKeys().size() + " switches");
		});

		SelfTests.register(F, "diff-changes-only", ctx -> {
			HudConfig c = cfg();
			HudLines.JobInfo miner = new HudLines.JobInfo("miner", 3, 31, 50, 50);
			HudLines.MissionInfo m = missions(1, "Mining", 3, 32, EliteStatus.OPEN);
			Map<HudLines.Line, String> before = HudLines.sidebar(data(10_000, miner, 0, 3, m, 0, 0, 0, 2, false, false), c);
			List<SidebarDiff.Op> first = SidebarDiff.diff(Map.of(), before);
			Assert.eq(before.size(), first.size(), "first time everything");
			Assert.isTrue(first.stream().noneMatch(SidebarDiff.Op::isRemove), "first time only sets");
			Assert.eq(List.of(), SidebarDiff.diff(before, HudLines.sidebar(data(10_000, miner, 0, 3, m, 0, 0, 0, 2, false, false), c)), "unchanged -> nothing");
			Assert.eq(List.of(), SidebarDiff.diff(null, null), "null/null");
			Assert.eq(List.of(new SidebarDiff.Op(HudLines.Line.MONEY, null)), SidebarDiff.diff(Map.of(HudLines.Line.MONEY, "x"), null), "null = empty");
			Assert.isTrue(new SidebarDiff.Op(HudLines.Line.MONEY, null).isRemove(), "removal detected");

			assertOnly(before, HudLines.sidebar(data(20_000, miner, 0, 3, m, 0, 0, 0, 2, false, false), c), HudLines.Line.MONEY, false, "money");
			Assert.eq(0, SidebarDiff.diff(before, HudLines.sidebar(data(10_000, miner, 0, 3, m, 0, 0, 599, 2, false, false), c)).size(),
				"playtime under 1 min changes nothing");
			assertOnly(before, HudLines.sidebar(data(10_000, new HudLines.JobInfo("miner", 3, 32, 50, 50), 0, 3, m, 0, 0, 0, 2, false, false), c),
				HudLines.Line.JOB_PROGRESS, false, "Job-XP");
			Assert.eq(0, SidebarDiff.diff(before, HudLines.sidebar(data(10_000, new HudLines.JobInfo("miner", 3, 31, 50, 50), 0, 3, m, 0, 0, 0, 2,
				false, false), c)).size(), "same XP -> nothing");
			List<SidebarDiff.Op> levelUp = SidebarDiff.diff(before, HudLines.sidebar(data(10_000, new HudLines.JobInfo("miner", 4, 0, 60, 50), 0, 3,
				m, 0, 0, 0, 2, false, false), c));
			Assert.eq(List.of(HudLines.Line.JOB, HudLines.Line.JOB_PROGRESS), levelUp.stream().map(SidebarDiff.Op::line).toList(), "Level-Up");
			assertOnly(before, HudLines.sidebar(data(10_000, miner, 0, 3, missions(1, "Mining", 4, 32, EliteStatus.OPEN), 0, 0, 0, 2, false, false), c),
				HudLines.Line.MISSION_PROGRESS, false, "mission progress");
			assertOnly(before, HudLines.sidebar(data(10_000, miner, 0, 3, missions(2, "Mining", 3, 32, EliteStatus.OPEN), 0, 0, 0, 2, false, false), c),
				HudLines.Line.MISSIONS, false, "second mission");
			assertOnly(before, HudLines.sidebar(data(10_000, miner, 0, 3, missions(1, "Mining", 3, 32, EliteStatus.DONE), 0, 0, 0, 2, false, false), c),
				HudLines.Line.ELITE, false, "elite done");
			List<SidebarDiff.Op> done = SidebarDiff.diff(before, HudLines.sidebar(data(10_000, miner, 0, 3, missions(0, null, 0, 0, EliteStatus.OPEN),
				0, 0, 0, 2, false, false), c));
			Assert.eq(List.of(new SidebarDiff.Op(HudLines.Line.MISSIONS, "§7Missions: §f0§7/§f3"), new SidebarDiff.Op(HudLines.Line.MISSION_PROGRESS, null)),
				done, "mission done: 2 packets");
			Map<HudLines.Line, String> shock = HudLines.sidebar(data(10_000, miner, 0, 3, m, 0, 0, 0, 2, true, false), c);
			Assert.eq(List.of(new SidebarDiff.Op(HudLines.Line.MARKET_SHOCK, c.marketShockLine)), SidebarDiff.diff(before, shock), "marker on");
			Assert.eq(List.of(new SidebarDiff.Op(HudLines.Line.MARKET_SHOCK, null)), SidebarDiff.diff(shock, before), "marker off");

			Set<String> holders = new HashSet<>();
			HudLines.Line prev = null;
			for (HudLines.Line line : HudLines.Line.values()) {
				String h = line.holder();
				Assert.isTrue(holders.add(h), "holder unique: " + h);
				Assert.isFalse(h.startsWith("#"), "not hidden: " + h);
				Assert.isTrue(h.contains("."), "no player name: " + h);
				Assert.isTrue(line.score() > 0, "Score > 0");
				if (prev != null) Assert.isTrue(line.score() < prev.score(), "scores descending");
				prev = line;
			}
			ctx.detail("diff minimal, " + holders.size() + " holders");
		});

		SelfTests.register(F, "packets-blank-number-format", ctx -> {
			ClientboundSetObjectivePacket add = new ClientboundSetObjectivePacket(HudManager.objective("§6§lTest"),
				ClientboundSetObjectivePacket.METHOD_ADD);
			Assert.eq(HudManager.OBJECTIVE, add.getObjectiveName(), "Objective-Name");
			Assert.isTrue(add.getNumberFormat().isPresent() && add.getNumberFormat().get() == BlankFormat.INSTANCE, "Objective: BlankFormat");
			Assert.eq("Test", add.getDisplayName().getString(), "title");
			Assert.eq("SMP Economy", HudManager.objective(HudConfig.title()).getDisplayName().getString(), "new title -> new objective");
			ClientboundSetScorePacket score = HudManager.scorePacket(HudLines.Line.MONEY, "§7Money: §a$5.00");
			Assert.eq("smpeco.hud.money", score.owner(), "Halter");
			Assert.eq(HudManager.OBJECTIVE, score.objectiveName(), "objective in score");
			Assert.eq((long) HudLines.Line.MONEY.score(), (long) score.score(), "Score");
			Assert.eq("Money: $5.00", score.display().map(x -> x.getString()).orElse(null), "display text");
			Assert.isTrue(score.numberFormat().isPresent() && score.numberFormat().get() == BlankFormat.INSTANCE, "Score: BlankFormat");
			Assert.eq(HudManager.OBJECTIVE, HudManager.resetPacket(HudLines.Line.ELITE).objectiveName(), "reset only our objective");
			Assert.eq("smpeco.hud.elite", HudManager.resetPacket(HudLines.Line.ELITE).owner(), "reset only this line");
			Assert.isFalse(HudManager.OBJECTIVE.equals(new StringReader(HudManager.OBJECTIVE).readUnquotedString()),
				"name can't be created through /scoreboard");
			Assert.isTrue(ctx.server().getScoreboard().getObjective(HudManager.OBJECTIVE) == null, "no server objective with the same name");
		});

		SelfTests.register(F, "tablist", ctx -> {
			HudConfig c = cfg();
			HudLines.Data d = data(123_456, null, 0, 0, 3, 0, 0, 0, 3, false, false);
			String header = HudLines.header(d, c);
			Assert.isTrue(header.contains("§f" + "Levi" + "§7!"), "player name in header: " + header);
			String footer = HudLines.footer(d, c);
			Assert.isTrue(footer.contains("$1,234.56"), "balance in footer");
			Assert.isTrue(footer.contains("§f3§8/§f20"), "online and max in footer");
			Assert.isFalse(footer.contains("{"), "no unresolved placeholders");
			Assert.isFalse(footer.contains("Breaking News"), "no shock, no news line");
			Assert.eq(c.tabFooter.size() - 1, footer.split("\n", -1).length, "empty {news} line drops out");
			String shock = HudLines.footer(data(0, null, 0, 0, 3, 0, 0, 0, 1, true, true), c);
			Assert.isTrue(shock.contains(c.marketShockLine) && shock.contains(c.auctionFeverLine), "news line with shock and fever");
			Assert.eq(c.tabFooter.size(), shock.split("\n", -1).length, "news line present");
			Assert.eq("", HudLines.fill(List.of(), d, c), "empty template");
			Assert.eq("x {unknown}\n", HudLines.fill(java.util.Arrays.asList("x {unknown}", null), d, c), "unknown placeholders stay, null = empty");
			Assert.eq("$1.2K", HudLines.fill(List.of("{money_short}"), d, c), "short form");
		});

		SelfTests.register(F, "tablist-with-styled-player-list", ctx -> {
			HudConfig c = cfg();
			Assert.isTrue(HudManager.wantsTablist(c, false), "without the mod the tab list is ours");
			Assert.isFalse(HudManager.wantsTablist(c, true), "the mod keeps the tab list by default");
			c.tablistWithStyledPlayerList = true;
			Assert.isTrue(HudManager.wantsTablist(c, true), "override");
			c.tablist = false;
			Assert.isFalse(HudManager.wantsTablist(c, true), "tablist off stays off");
		});

		SelfTests.register(F, "read-tasks", ctx -> {
			LocalDate today = LocalDate.of(2026, 10, 10);
			Assert.eq(TaskProgress.Progress.NONE, TaskProgress.parse(null, today), "no state -> 0/3");
			Assert.eq(TaskProgress.Progress.NONE, TaskProgress.parse(json("null"), today), "JSON null -> 0/3");
			Assert.eq(TaskProgress.Progress.UNKNOWN, TaskProgress.parse(json("[1,2]"), today), "array -> unknown");
			Assert.eq(TaskProgress.Progress.UNKNOWN, TaskProgress.parse(json("\"x\""), today), "text -> unknown");
			Assert.eq(TaskProgress.Progress.UNKNOWN, TaskProgress.parse(json("{\"foo\":1}"), today), "no list -> unknown");
			Assert.eq(new TaskProgress.Progress(2, 3), TaskProgress.parse(json(
				"{\"date\":\"2026-10-10\",\"tasks\":[{\"done\":true},{\"done\":false},{\"completed\":true}]}"), today), "2/3 today");
			Assert.eq(TaskProgress.Progress.NONE, TaskProgress.parse(json(
				"{\"date\":\"2026-10-09\",\"tasks\":[{\"done\":true},{\"done\":true},{\"done\":true}]}"), today), "yesterday -> 0/3");
			Assert.eq(new TaskProgress.Progress(1, 3), TaskProgress.parse(json(
				"{\"day\":" + today.toEpochDay() + ",\"entries\":[{\"progress\":5,\"target\":5},{\"progress\":4,\"target\":5},{\"progress\":0,\"target\":0}]}"),
				today), "Epoch-Tag + progress/target");
			long ms = today.atTime(12, 0).atZone(TimeUtil.zone()).toInstant().toEpochMilli();
			Assert.eq(new TaskProgress.Progress(3, 3), TaskProgress.parse(json(
				"{\"assignedDate\":" + ms + ",\"tasks\":{\"a\":{\"rewarded\":true},\"b\":{\"claimed\":true},\"c\":{\"finished\":true}}}"),
				today), "epoch ms + object by id");
			Assert.eq(new TaskProgress.Progress(1, 3), TaskProgress.parse(json(
				"{\"updatedAt\":1,\"list\":[{\"done\":\"true\"},{\"done\":1},{\"done\":true}]}"), today), "only real booleans; updatedAt is no date");
			Assert.eq(new TaskProgress.Progress(2, 3), TaskProgress.parse(json(
				"{\"date\":\"2026-10-10\",\"ids\":[\"a\",\"b\",\"c\"],\"completed\":[0,2]}"), today), "list of completed ids");
			Assert.eq(new TaskProgress.Progress(2, 4), TaskProgress.parse(json(
				"{\"tasks\":[{\"done\":true},{\"done\":true},{},{}]}"), today), "4 tasks without a date");
			Assert.eq(new TaskProgress.Progress(0, 3), TaskProgress.parse(json("{\"tasks\":[]}"), today), "empty list -> 0/3");
			Assert.eq(new TaskProgress.Progress(2, 3), TaskProgress.parse(json(
				"{\"date\":\"broken\",\"tasks\":[{\"done\":true},{\"done\":true},{\"done\":false}]}"), today), "broken date ignored");
			FakePlayer a = ctx.player("HudTasks");
			UUID id = a.getUUID();
			Assert.eq(TaskProgress.Progress.NONE, TaskProgress.read(id), "no player data -> 0/3");
			Assert.isTrue(PlayerDataService.peek(id).isEmpty(), "reading creates no player data");
			PlayerDataService.setTaskState(id, json("{\"date\":\"" + TimeUtil.today() + "\",\"tasks\":[{\"done\":true},{\"done\":false},{\"done\":false}]}"));
			Assert.eq(new TaskProgress.Progress(1, 3), TaskProgress.read(id), "through PlayerDataService");
		});

		SelfTests.register(F, "kills-source", ctx -> {
			UUID id = UUID.randomUUID();
			Services.PlayerStats svc = new Services.PlayerStats() {
				public long playerKills(UUID p) {
					return 7;
				}

				public long deaths(UUID p) {
					return 2;
				}

				public long mobKills(UUID p) {
					return 99;
				}
			};
			Services.PlayerStats broken = new Services.PlayerStats() {
				public long playerKills(UUID p) {
					throw new IllegalStateException("broken");
				}

				public long deaths(UUID p) {
					return 1;
				}

				public long mobKills(UUID p) {
					return 0;
				}
			};
			Services.PlayerStats negative = new Services.PlayerStats() {
				public long playerKills(UUID p) {
					return -1;
				}

				public long deaths(UUID p) {
					return 4;
				}

				public long mobKills(UUID p) {
					return 0;
				}
			};
			List<RuntimeException> errors = new ArrayList<>();
			Assert.eq(List.of(7L, 2L), asList(HudManager.killsDeaths(svc, id, () -> 1, () -> 1, errors::add)), "service takes precedence");
			Assert.eq(List.of(3L, 4L), asList(HudManager.killsDeaths(null, id, () -> 3, () -> 4, errors::add)), "no service, vanilla");
			Assert.eq(List.of(5L, 6L), asList(HudManager.killsDeaths(broken, id, () -> 5, () -> 6, errors::add)), "service throws -> vanilla");
			Assert.eq(List.of(5L, 6L), asList(HudManager.killsDeaths(negative, id, () -> 5, () -> 6, errors::add)), "service negative -> vanilla");
			Assert.eq(List.of(0L, 0L), asList(HudManager.killsDeaths(null, id, () -> -5, () -> -1, errors::add)), "never negative");
			Assert.eq(1, errors.size(), "service error reported (once, only the throwing service)");
		});

		SelfTests.register(F, "toggle-persistence", ctx -> {
			FakePlayer a = ctx.player("HudToggle");
			UUID id = a.getUUID();
			Assert.isTrue(HudFeature.isSidebarEnabled(id), "default on");
			Assert.eq(null, PlayerDataService.getSetting(id, Settings.SIDEBAR, null), "default without entry");
			Assert.isTrue(Settings.def(Settings.SIDEBAR).isPresent(), "switch in settings registry (GUI)");

			HudFeature.setSidebarEnabled(id, false);
			Assert.isFalse(HudFeature.isSidebarEnabled(id), "off");
			Assert.eq("false", PlayerDataService.getSetting(id, Settings.SIDEBAR, null), "stored in player data");
			Assert.isTrue(HudManager.isDirty(id), "settings listener notified the HUD");
			String saved = DataStore.gson().toJson(PlayerDataService.get(id));
			PlayerDataService.PlayerData loaded = DataStore.gson().fromJson(saved, PlayerDataService.PlayerData.class);
			Assert.eq("false", loaded.settings.get(Settings.SIDEBAR), "survives save and load");

			HudFeature.setSidebarEnabled(id, true);
			Assert.isTrue(HudFeature.isSidebarEnabled(id), "on again");
			Assert.eq(null, PlayerDataService.getSetting(id, Settings.SIDEBAR, null), "default value -> entry removed");
			Assert.isFalse(Settings.toggle(id, Settings.SIDEBAR), "toggle -> off");
			Assert.isTrue(Settings.toggle(id, Settings.SIDEBAR), "toggle -> on");
			Assert.isFalse(HudManager.state(id).isPresent(), "no client state for test player (not connected)");
		});

		SelfTests.register(F, "collect-data", ctx -> {
			FakePlayer a = ctx.player("HudData");
			UUID id = a.getUUID();
			HudManager.Shared shared = new HudManager.Shared(3, 20, false, false);
			HudLines.Data empty = HudManager.collect(a, shared);
			Assert.eq(0L, empty.balance(), "no account, $0");
			Assert.eq(HudLines.JobInfo.NONE, empty.job(), "without job");
			Assert.isTrue(PlayerDataService.peek(id).isEmpty() && !EconomyService.hasAccount(id), "collecting creates nothing");

			EconomyService.ensureAccount(id, "HudData");
			Assert.isTrue(EconomyService.set(id, 4_200).ok(), "set balance");
			Assert.isTrue(HudManager.isDirty(id), "economy listener reports balance change to the HUD at once");
			PlayerDataService.setJob(id, "miner");
			PlayerDataService.setJobLevel(id, "miner", 7);
			PlayerDataService.get(id).playtimeTicks = 72_000L;
			PlayerDataService.markDirty();
			PlayerDataService.setTaskState(id, json("{\"date\":\"" + TimeUtil.today() + "\",\"done\":3,\"total\":3,\"tasks\":[{\"done\":true},{\"done\":true},{\"done\":true}]}"));
			HudLines.Data d = HudManager.collect(a, shared);
			Map<HudLines.Line, String> lines = HudLines.sidebar(d, cfg());
			Assert.eq("§7Money: §a$42.00", lines.get(HudLines.Line.MONEY), "money");
			Assert.eq("§7Job: §eMiner §8(Lv. 7)", lines.get(HudLines.Line.JOB), "Job + Level");
			Assert.eq("§7Tasks: §a3/3 ✔", lines.get(HudLines.Line.TASKS), "tasks");
			String kd = lines.get(HudLines.Line.KILLS_DEATHS);
			Assert.isTrue(kd.startsWith("§7Kills: §f") && kd.contains("§7Deaths: §f"), "Kills/Deaths: " + kd);
			Assert.eq("§7Playtime: §f1h 00m", lines.get(HudLines.Line.PLAYTIME), "playtime");
			Assert.eq("§7Online: §f3", lines.get(HudLines.Line.ONLINE), "Online");
			Assert.eq("HudData", d.playerName(), "name for the tablist");
			Assert.isTrue(HudLines.footer(d, cfg()).contains("$42.00"), "footer with balance");
			if (Services.find(Services.JobLevels.class).isPresent()) {
				Assert.eq("§8 ▸ §7■■■■■■■■■■ §f0%", lines.get(HudLines.Line.JOB_PROGRESS), "job progress through the jobs service (0 XP)");
			}
			Services.Missions ms = Services.find(Services.Missions.class).orElse(null);
			if (ms != null) {
				Assert.eq("§7Missions: §f0§7/§f" + ms.maxActive(), lines.get(HudLines.Line.MISSIONS), "missions through the missions service");
				Assert.isFalse(lines.containsKey(HudLines.Line.MISSION_PROGRESS), "no active mission, no sub-line");
				// A fresh player has never completed the elite. Whether the daily elite is on at all is covered by the missions test
				// "hud-service-elite-source", which must then report OPEN or DONE.
				EliteStatus es = ms.elite(id);
				Assert.isTrue(es != EliteStatus.DONE, "fresh player hasn't completed the daily elite (" + es + ")");
				Assert.eq(HudLines.elite(es), lines.get(HudLines.Line.ELITE), "elite line from the missions service (" + es + ")");
			}
			ctx.detail(String.join(" | ", HudLines.texts(lines)).replaceAll("§.", "").replace(" |  | ", " | "));
		});

		SelfTests.register(F, "service-sources", ctx -> {
			UUID id = UUID.randomUUID();
			List<RuntimeException> errors = new ArrayList<>();
			Services.JobLevels jobs = (p, job) -> "miner".equals(job) ? new Services.JobLevels.JobProgress(5, 50, 30, 60) : null;
			Assert.eq(HudLines.JobInfo.NONE, HudManager.jobOf(jobs, id, null, errors::add), "no job");
			Assert.eq(HudLines.JobInfo.NONE, HudManager.jobOf(jobs, id, " ", errors::add), "empty job");
			HudLines.JobInfo miner = HudManager.jobOf(jobs, id, "miner", errors::add);
			Assert.eq(new HudLines.JobInfo("miner", 5, 30, 60, 50), miner, "progress from the service");
			Assert.eq("§8 ▸ §a■■■■■§7■■■■■ §f50%", HudLines.jobProgress(miner), "50 %");
			HudLines.JobInfo unknown = HudManager.jobOf(jobs, id, "gone", errors::add);
			Assert.eq(new HudLines.JobInfo("gone", 1, 0, -1, 0), unknown, "unknown job -> level from player data, no progress");
			Assert.eq(null, HudLines.jobProgress(unknown), "-> no progress line");
			Assert.eq(new HudLines.JobInfo("miner", 1, 0, -1, 0), HudManager.jobOf(null, id, "miner", errors::add), "no service");
			Services.JobLevels broken = (p, job) -> {
				throw new IllegalStateException("broken");
			};
			Assert.eq(new HudLines.JobInfo("miner", 1, 0, -1, 0), HudManager.jobOf(broken, id, "miner", errors::add), "service throws -> no progress");
			Assert.eq(1, errors.size(), "jobs service error reported");
			Services.JobLevels weird = (p, job) -> new Services.JobLevels.JobProgress(0, 50, -9, 60);
			Assert.eq(new HudLines.JobInfo("miner", 1, 0, 60, 50), HudManager.jobOf(weird, id, "miner", errors::add), "Level ≥ 1, XP ≥ 0");

			errors.clear();
			Assert.eq(null, HudManager.missionsOf(null, id, errors::add), "no service, no mission lines");
			Services.Missions fake = new Services.Missions() {
				public int activeCount(UUID p) {
					return 2;
				}

				public int maxActive() {
					return 3;
				}

				public MissionProgress nearest(UUID p) {
					return new MissionProgress("Mining", 20, 32);
				}

				public EliteStatus elite(UUID p) {
					return null;
				}
			};
			Assert.eq(new HudLines.MissionInfo(2, 3, new MissionProgress("Mining", 20, 32), EliteStatus.NONE),
				HudManager.missionsOf(fake, id, errors::add), "from the service (elite null -> NONE)");
			Services.Missions throwing = new Services.Missions() {
				public int activeCount(UUID p) {
					throw new IllegalStateException("broken");
				}

				public int maxActive() {
					return 3;
				}

				public MissionProgress nearest(UUID p) {
					return null;
				}

				public EliteStatus elite(UUID p) {
					return EliteStatus.OPEN;
				}
			};
			Assert.eq(null, HudManager.missionsOf(throwing, id, errors::add), "service throws -> no mission lines");
			Assert.eq(1, errors.size(), "missions service error reported");
		});

		SelfTests.register(F, "market-shock-marker", ctx -> {
			boolean baseline = HudManager.marketShockActive();
			ctx.cleanup(() -> PriceModifiers.removeSource(MOD_SOURCE));
			PriceModifiers.set(MOD_SOURCE, net.minecraft.world.item.Items.BEDROCK, 1.0, 0, 60_000, 0, "Selftest");
			Assert.eq(baseline, HudManager.marketShockActive(), "factor 1.0 doesn't count");
			PriceModifiers.set(MOD_SOURCE, net.minecraft.world.item.Items.BEDROCK, 0.5, 0, 60_000, 0, "Selftest");
			Assert.isTrue(HudManager.marketShockActive(), "factor 0.5 -> market shock");
			PriceModifiers.set(MOD_SOURCE, net.minecraft.world.item.Items.BEDROCK, 2.0, 30_000, 60_000, 5_000, "Selftest");
			Assert.isTrue(HudManager.marketShockActive(), "hype fading in -> market shock");
			PriceModifiers.removeSource(MOD_SOURCE);
			Assert.eq(baseline, HudManager.marketShockActive(), "removed -> as before");
		});

		SelfTests.register(F, "config-validation", ctx -> {
			HudConfig ok = new HudConfig();
			HudConfig.validate(ok);
			HudConfig zero = new HudConfig();
			zero.updateIntervalTicks = 0;
			Assert.assertThrows(IllegalArgumentException.class, () -> HudConfig.validate(zero), "interval 0");
			HudConfig huge = new HudConfig();
			huge.updateIntervalTicks = HudConfig.MAX_INTERVAL + 1;
			Assert.assertThrows(IllegalArgumentException.class, () -> HudConfig.validate(huge), "interval too large");
			HudConfig nulls = new HudConfig();
			nulls.tabHeader = null;
			nulls.tabFooter = new ArrayList<>(java.util.Arrays.asList("a", null));
			nulls.jobNames = null;
			nulls.address = null;
			nulls.marketShockLine = null;
			HudConfig.validate(nulls);
			Assert.isTrue(nulls.tabHeader.isEmpty() && "".equals(nulls.tabFooter.get(1)), "lists repaired");
			Assert.eq("Miner", nulls.jobNames.get("miner"), "job name defaults");
			Assert.eq(List.of("", "§7Money: §a$0.00"), texts(data(0, null, 0, 0, 3, 0, 0, 0, 1, true, false), nulls).subList(0, 2),
				"sidebar with repaired config");
			Assert.isTrue(new HudConfig().tabFooter.contains(HudConfig.FOOTER_COMMANDS), "default footer names the commands");
			Assert.isTrue(HudConfig.FOOTER_COMMANDS.contains("/menu") && HudConfig.FOOTER_COMMANDS.contains("/commands"), "footer names /menu and /commands");
			for (String l : new HudConfig().tabHeader) Assert.isFalse(l.contains("{news}"), "header without news placeholder: " + l);
			HudConfig sw = new HudConfig();
			sw.lines = new java.util.HashMap<>();
			sw.lines.put("playtime", false);
			sw.lines.put("nonexistent", false);
			sw.lines.put("online", null);
			sw.missionNameMaxLength = 1;
			HudConfig.validate(sw);
			Assert.eq(HudLines.Line.configKeys(), sw.lines.keySet(), "all switches present, unknown removed");
			Assert.isFalse(sw.shows(HudLines.Line.PLAYTIME), "playtime off");
			Assert.isTrue(sw.shows(HudLines.Line.ONLINE) && sw.shows(HudLines.Line.MONEY), "null or missing = on");
			Assert.eq(HudConfig.MIN_NAME_LENGTH, sw.missionNameMaxLength, "name length min");
			HudConfig longNames = new HudConfig();
			longNames.missionNameMaxLength = 500;
			longNames.lines = null;
			HudConfig.validate(longNames);
			Assert.eq(HudConfig.MAX_NAME_LENGTH, longNames.missionNameMaxLength, "name length max");
			Assert.isTrue(longNames.lines.values().stream().allMatch(v -> v), "lines missing -> all on");
			Assert.notNull(HudFeature.CONFIG.get(), "hud.json loaded");
			Assert.eq(HudLines.Line.configKeys(), HudFeature.CONFIG.get().lines.keySet(), "loaded hud.json knows all line switches");
			Assert.isTrue(HudFeature.CONFIG.get().updateIntervalTicks >= HudConfig.MIN_INTERVAL, "loaded interval valid");
		});
	}

	private static void assertOnly(Map<HudLines.Line, String> before, Map<HudLines.Line, String> after, HudLines.Line line, boolean removed,
		String what) {
		List<SidebarDiff.Op> ops = SidebarDiff.diff(before, after);
		Assert.eq(List.of(new SidebarDiff.Op(line, removed ? null : after.get(line))), ops, what + ": only " + line);
	}

	private static JsonElement json(String s) {
		return JsonParser.parseString(s);
	}

	private static List<Long> asList(long[] values) {
		List<Long> list = new ArrayList<>();
		for (long v : values) list.add(v);
		return list;
	}
}
