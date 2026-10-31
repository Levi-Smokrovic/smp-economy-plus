package dev.smpeconomy.features.admin;

import static dev.smpeconomy.core.test.Assert.assertThrows;
import static dev.smpeconomy.core.test.Assert.eq;
import static dev.smpeconomy.core.test.Assert.isFalse;
import static dev.smpeconomy.core.test.Assert.isTrue;

import com.mojang.brigadier.tree.CommandNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.item.Items;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.CoreConfig;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.Feature;
import dev.smpeconomy.core.Hooks;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Settings;
import dev.smpeconomy.core.module.ModuleCatalog;
import dev.smpeconomy.core.module.ModuleCommands;
import dev.smpeconomy.core.module.ModuleGroup;
import dev.smpeconomy.core.module.ModuleInfo;
import dev.smpeconomy.core.module.Modules;
import dev.smpeconomy.core.module.ModulesFile;
import dev.smpeconomy.core.test.SelfTests;
import dev.smpeconomy.core.test.TestContext;

final class AdminSelfTests {
	private static final String M = "modules";
	private static final String A = "admin";

	/** Sample of module store files; a disabled module must create none of them. */
	private static final Map<String, List<String>> STORES = Map.ofEntries(
		Map.entry("auction", List.of("auctions.json")),
		Map.entry("bank", List.of("bank.json")),
		Map.entry("bounty", List.of("bounties.json")),
		Map.entry("casino", List.of("casino.json", "casino_crash.json", "casino_horserace.json")),
		Map.entry("clans", List.of("clans.json")),
		Map.entry("events", List.of("events.json", "lotto.json")),
		Map.entry("holo", List.of("holos.json")),
		Map.entry("jobs", List.of("jobs_boosted.json")),
		Map.entry("market", List.of("market.json")),
		Map.entry("minigames", List.of("coinflips.json", "treasure.json", "megachallenge.json")),
		Map.entry("missions", List.of("missions_state.json")),
		Map.entry("news", List.of("news.json")),
		Map.entry("orders", List.of("orders.json")),
		Map.entry("sell", List.of("sell_sessions.json")),
		Map.entry("stats", List.of("stats.json")));

	private AdminSelfTests() {}

	static void register() {
		SelfTests.register(M, "catalog", ctx -> catalog(ctx));
		SelfTests.register(M, "resolve-defaults", ctx -> resolveDefaults(ctx));
		SelfTests.register(M, "resolve-requires", ctx -> resolveRequires(ctx));
		SelfTests.register(M, "resolve-locked", ctx -> resolveLocked(ctx));
		SelfTests.register(M, "file-parse", ctx -> fileParse(ctx));
		SelfTests.register(M, "file-roundtrip", AdminSelfTests::fileRoundtrip);
		SelfTests.register(M, "mixin-package", ctx -> mixinPackage(ctx));
		SelfTests.register(M, "set-configured", AdminSelfTests::setConfigured);
		SelfTests.register(M, "running-matches-file", ctx -> runningMatchesFile(ctx));
		SelfTests.register(M, "disabled-no-commands", AdminSelfTests::disabledNoCommands);
		SelfTests.register(M, "enabled-commands", AdminSelfTests::enabledCommands);
		SelfTests.register(M, "disabled-no-stores", ctx -> disabledNoStores(ctx));
		SelfTests.register(M, "features-match", ctx -> featuresMatch(ctx));
		SelfTests.register(M, "settings-filter", ctx -> settingsFilter(ctx));
		SelfTests.register(A, "config-validate", ctx -> configValidate(ctx));
		SelfTests.register(A, "config-set", AdminSelfTests::configSet);
		SelfTests.register(A, "currency", AdminSelfTests::currency);
		SelfTests.register(A, "prefix", AdminSelfTests::prefix);
		SelfTests.register(A, "multipliers", AdminSelfTests::multipliers);
		SelfTests.register(A, "inputs", ctx -> inputs(ctx));
		SelfTests.register(A, "gui-layout", ctx -> guiLayout(ctx));
		SelfTests.register(A, "starter-link", ctx -> {
			// The "Starter kit" button runs /smpeco starter capture rather than using a service; the command exists exactly with the module.
			eq(Modules.isEnabled("starter"), AdminFeature.commandExists(ctx.server(), "smpeco", "starter", "capture"), "/smpeco starter capture");
		});
	}

	private static void catalog(TestContext ctx) {
		eq(26, ModuleCatalog.ALL.size(), "26 Module");
		eq("economy", ModuleCatalog.ALL.get(0).id(), "economy first");
		isTrue(ModuleCatalog.get("economy").orElseThrow().locked(), "economy locked");
		for (String core : List.of("economy", "starter", "sell", "shop", "auction", "orders", "jobs", "tasks", "missions", "bank", "menu",
				"settings", "hud", "stats", "holo", "npc")) {
			eq(ModuleGroup.CORE, ModuleCatalog.get(core).orElseThrow().group(), "CORE: " + core);
		}
		for (String extra : List.of("casino", "events", "clans", "bounty", "minigames", "crates", "daily", "afk", "market", "news")) {
			eq(ModuleGroup.EXTRA, ModuleCatalog.get(extra).orElseThrow().group(), "EXTRA: " + extra);
		}
		eq(List.of("auction"), ModuleCatalog.get("market").orElseThrow().requires(), "market requires auction");
		for (ModuleInfo m : ModuleCatalog.ALL) {
			isTrue(dev.smpeconomy.core.Items.byId(m.icon()).isPresent(), "valid icon: " + m.id() + " " + m.icon());
			for (String u : m.uses()) isTrue(ModuleCatalog.exists(u), m.id() + " uses unknown module " + u);
			for (String r : m.requires()) {
				isTrue(ModuleCatalog.ALL.indexOf(ModuleCatalog.get(r).orElseThrow()) < ModuleCatalog.ALL.indexOf(m), "requires before " + m.id());
			}
		}
		ctx.detail(ModuleCatalog.ALL.size() + " Module");
	}

	private static void resolveDefaults(TestContext ctx) {
		ModulesFile.Resolution r = ModulesFile.resolve(ModulesFile.defaults());
		isTrue(r.problems().isEmpty(), "no problems: " + r.problems());
		for (ModuleInfo m : ModuleCatalog.ALL) eq(m.group() == ModuleGroup.CORE, r.isEnabled(m.id()), "Default " + m.id());
		ctx.detail(r.enabled().size() + " on");
	}

	private static void resolveRequires(TestContext ctx) {
		Map<String, Boolean> cfg = ModulesFile.defaults();
		cfg.put("market", true);
		cfg.put("auction", false);
		ModulesFile.Resolution r = ModulesFile.resolve(cfg);
		isFalse(r.isEnabled("market"), "market without auction is off");
		isFalse(r.isEnabled("auction"), "auction off");
		isTrue(r.problems().contains("module market disabled: requires auction"), "problem reported: " + r.problems());
		cfg.put("auction", true);
		ModulesFile.Resolution ok = ModulesFile.resolve(cfg);
		isTrue(ok.isEnabled("market") && ok.isEnabled("auction"), "both on");
		isTrue(ok.problems().isEmpty(), "no problems");
		ctx.detail(r.problems().get(0));
	}

	private static void resolveLocked(TestContext ctx) {
		Map<String, Boolean> cfg = ModulesFile.defaults();
		cfg.put("economy", false);
		ModulesFile.Resolution r = ModulesFile.resolve(cfg);
		isTrue(r.isEnabled("economy"), "economy stays on");
		isTrue(r.problems().stream().anyMatch(p -> p.contains("economy cannot be disabled")), "problem reported");
		Map<String, Boolean> none = new LinkedHashMap<>();
		for (ModuleInfo m : ModuleCatalog.ALL) none.put(m.id(), false);
		ModulesFile.Resolution off = ModulesFile.resolve(none);
		eq(Set.of("economy"), off.enabled(), "all off -> only economy");
	}

	private static void fileParse(TestContext ctx) {
		ModulesFile.Parsed missing = ModulesFile.parse("{\"_info\":\"x\",\"casino\":true,\"sell\":false}");
		isTrue(missing.needsRewrite(), "missing keys -> normalize");
		eq(true, missing.configured().get("casino"), "casino on");
		eq(false, missing.configured().get("sell"), "sell off");
		eq(true, missing.configured().get("shop"), "missing -> default (CORE on)");
		eq(false, missing.configured().get("crates"), "missing -> default (EXTRA off)");
		ModulesFile.Parsed unknown = ModulesFile.parse(ModulesFile.toJson(ModulesFile.defaults(), Map.of()).replace("{", "{\"teleport\": true, \"_comment\": 1,"));
		eq(List.of("teleport"), unknown.unknownKeys(), "unknown key detected");
		isTrue(unknown.extra().containsKey("teleport") && unknown.extra().containsKey("_comment"), "unknown keys are kept");
		isFalse(unknown.needsRewrite(), "complete -> no rewrite");
		ModulesFile.Parsed wrong = ModulesFile.parse("{\"casino\": \"yes\", \"bank\": 1}");
		eq(2, wrong.problems().size(), "two type errors: " + wrong.problems());
		eq(false, wrong.configured().get("casino"), "type error -> default");
		eq(true, wrong.configured().get("bank"), "type error -> default");
		ModulesFile.Parsed broken = ModulesFile.parse("{ casino: true,,, ");
		isTrue(broken.broken(), "broken JSON detected");
		isFalse(broken.needsRewrite(), "broken file is not overwritten");
		eq(ModulesFile.defaults(), broken.configured(), "broken -> defaults");
		isTrue(broken.problems().get(0).contains("invalid"), "problem text");
		ctx.detail("ok");
	}

	private static void fileRoundtrip(TestContext ctx) throws Exception {
		Path dir = Files.createTempDirectory("smpeco-modules");
		Path file = dir.resolve(ModulesFile.FILE_NAME);
		ctx.cleanup(() -> {
			try {
				Files.deleteIfExists(file);
				Files.deleteIfExists(dir);
			} catch (Exception ignored) {}
		});
		Map<String, Boolean> cfg = ModulesFile.defaults();
		cfg.put("casino", true);
		cfg.put("npc", false);
		ModulesFile.Parsed none = ModulesFile.read(file);
		isTrue(none.needsRewrite() && !none.broken(), "missing file -> defaults and write");
		ModulesFile.write(file, cfg, Map.of("_note", new com.google.gson.JsonPrimitive("hi")));
		String json = Files.readString(file);
		isTrue(json.indexOf("\"_info\"") < json.indexOf("\"economy\"") && json.indexOf("\"economy\"") < json.indexOf("\"npc\""), "fixed order");
		ModulesFile.Parsed back = ModulesFile.read(file);
		eq(cfg, back.configured(), "Round-Trip");
		isTrue(back.extra().containsKey("_note"), "extra keys kept");
		isFalse(back.needsRewrite(), "no rewrite needed");
		isFalse(Files.exists(dir.resolve(ModulesFile.FILE_NAME + ".tmp")), "no tmp file left");
	}

	private static void mixinPackage(TestContext ctx) {
		eq("jobs", ModulesFile.moduleOfMixinPackage("dev.smpeconomy.features.jobs.mixin"), "jobs");
		eq("settings", ModulesFile.moduleOfMixinPackage("dev.smpeconomy.features.settings.mixin."), "settings with dot");
		eq(null, ModulesFile.moduleOfMixinPackage("dev.smpeconomy.core.mixin"), "core belongs to no module");
		eq(null, ModulesFile.moduleOfMixinPackage("dev.smpeconomy.features.nope.mixin"), "unknown module");
		eq(ModulesFile.resolve(ModulesFile.readEarly().configured()).enabled(), Modules.enabled(), "plugin state = startup state");
	}

	private static void setConfigured(TestContext ctx) throws Exception {
		Path dir = Files.createTempDirectory("smpeco-modules");
		Path file = dir.resolve(ModulesFile.FILE_NAME);
		Map<String, Boolean> state = ModulesFile.defaults();
		state.put("auction", false);
		Runnable restore = Modules.overrideFileForTest(file, state);
		ctx.cleanup(() -> {
			restore.run();
			try {
				Files.deleteIfExists(file);
				Files.deleteIfExists(dir);
			} catch (Exception ignored) {}
		});
		Modules.Result refused = Modules.setConfigured("market", true, false, "selftest");
		isFalse(refused.ok(), "market without auction rejected");
		isTrue(refused.message().contains("requires auction"), refused.message());
		isFalse(Files.exists(file), "rejected -> nothing written");
		Modules.Result both = Modules.setConfigured("market", true, true, "selftest");
		isTrue(both.ok(), both.message());
		eq(Set.of("market", "auction"), Set.copyOf(both.changed()), "with dependency");
		ModulesFile.Parsed written = ModulesFile.read(file);
		isTrue(written.configured().get("market") && written.configured().get("auction"), "file written");
		Modules.Result dep = Modules.setConfigured("auction", false, false, "selftest");
		isFalse(dep.ok(), "auction off although market needs it -> rejected");
		isTrue(dep.message().contains("market"), dep.message());
		Modules.Result cascade = Modules.setConfigured("auction", false, true, "selftest");
		isTrue(cascade.ok() && Set.copyOf(cascade.changed()).equals(Set.of("auction", "market")), "with dependents: " + cascade.changed());
		Modules.Result locked = Modules.setConfigured("economy", false, true, "selftest");
		isFalse(locked.ok(), "economy cannot be disabled");
		Modules.Result unknown = Modules.setConfigured("teleport", true, false, "selftest");
		isFalse(unknown.ok(), "unknown module");
		Modules.Result same = Modules.setConfigured("shop", true, false, "selftest");
		isTrue(same.ok() && same.changed().isEmpty(), "already on -> no change");
		Modules.Result casino = Modules.setConfigured("casino", true, false, "selftest");
		isTrue(casino.ok(), casino.message());
		isTrue(Modules.isConfigured("casino"), "file state on");
		eq(Modules.isEnabled("casino") ? false : true, Modules.pendingChanges().stream().anyMatch(p -> p.startsWith("casino:")),
			"restart needed if not currently running");
		ctx.detail(both.message());
	}

	private static void runningMatchesFile(TestContext ctx) {
		ModulesFile.Resolution start = ModulesFile.resolve(ModulesFile.readEarly().configured());
		eq(start.enabled(), Modules.enabled(), "running = startup resolution");
		for (String id : Modules.enabled()) {
			for (String r : ModuleCatalog.get(id).orElseThrow().requires()) isTrue(Modules.isEnabled(r), id + " runs without " + r);
		}
		ctx.detail(Modules.enabled().size() + " running");
	}

	private static Set<String> rootCommands(TestContext ctx) {
		Set<String> roots = new java.util.HashSet<>();
		for (CommandNode<CommandSourceStack> n : ctx.server().getCommands().getDispatcher().getRoot().getChildren()) roots.add(n.getName());
		return roots;
	}

	private static void disabledNoCommands(TestContext ctx) {
		Set<String> roots = rootCommands(ctx);
		CommandNode<CommandSourceStack> smpeco = ctx.server().getCommands().getDispatcher().getRoot().getChild("smpeco");
		int checked = 0;
		List<String> off = new ArrayList<>();
		for (ModuleInfo m : ModuleCatalog.ALL) {
			if (Modules.isEnabled(m.id())) continue;
			off.add(m.id());
			isTrue(ModuleCommands.roots(m.id()).isEmpty(), m.id() + " off, but registered commands: " + ModuleCommands.roots(m.id()));
			for (String c : m.commands()) {
				// Commands a running module also registers (e.g. /coinflip: minigames and casino) don't count.
				if (registeredByRunning(c)) continue;
				isFalse(roots.contains(c), "/" + c + " exists although " + m.id() + " is off");
				checked++;
			}
			if (smpeco != null) isTrue(smpeco.getChild(m.id()) == null, "/smpeco " + m.id() + " exists although the module is off");
		}
		ctx.detail(off.isEmpty() ? "all modules on" : off.size() + " off (" + String.join(",", off) + "), " + checked + " commands checked");
	}

	private static boolean registeredByRunning(String command) {
		for (String id : Modules.enabled()) if (ModuleCommands.roots(id).contains(command)) return true;
		return false;
	}

	private static void enabledCommands(TestContext ctx) {
		Set<String> roots = rootCommands(ctx);
		int checked = 0;
		for (ModuleInfo m : ModuleCatalog.ALL) {
			if (!Modules.isEnabled(m.id()) || !featureRunning(m.id())) continue;
			for (String c : m.commands()) {
				isTrue(roots.contains(c), "/" + c + " missing although " + m.id() + " runs");
				isTrue(ModuleCommands.roots(m.id()).contains(c) || registeredByRunning(c), "/" + c + " not assigned to " + m.id() + " : "
					+ ModuleCommands.roots(m.id()));
				checked++;
			}
		}
		ctx.detail(checked + " commands");
	}

	private static boolean featureRunning(String id) {
		for (Feature f : SmpEconomy.features()) if (f.id().equals(id)) return true;
		return false;
	}

	private static void disabledNoStores(TestContext ctx) {
		List<String> stores = DataStore.managedStoreNames();
		int checked = 0;
		for (Map.Entry<String, List<String>> e : STORES.entrySet()) {
			boolean on = Modules.isEnabled(e.getKey());
			for (String file : e.getValue()) {
				eq(on, stores.contains(file), (on ? "store missing: " : "store of a disabled module: ") + file + " (" + e.getKey() + ")");
				checked++;
			}
		}
		ctx.detail(checked + " stores checked");
	}

	private static void featuresMatch(TestContext ctx) {
		List<String> ids = new ArrayList<>();
		for (Feature f : SmpEconomy.features()) ids.add(f.id());
		isTrue(ids.contains(AdminFeature.ID), "admin always present");
		for (Feature f : SmpEconomy.features()) {
			if (f.id().equals(AdminFeature.ID)) continue;
			isTrue(Modules.isEnabled(f.id()), "Feature " + f.id() + " runs, but the module is off");
		}
		for (String id : Modules.enabled()) {
			isTrue(ids.contains(id), "module " + id + " on, feature missing");
		}
		ctx.detail(ids.size() + " Features");
	}

	private static void settingsFilter(TestContext ctx) {
		List<String> visible = Settings.all().stream().map(Settings.Def::key).toList();
		eq(Modules.isEnabled("minigames"), visible.contains(Settings.CHATGAMES), "chatgames only with minigames");
		eq(Modules.isEnabled("jobs"), visible.contains(Settings.JOB_ACTIONBAR), "job_actionbar only with jobs");
		isTrue(visible.contains(Settings.ANNOUNCEMENTS), "announcements always");
		isTrue(Settings.allDefined().size() >= visible.size(), "allDefined ⊇ all");
		ctx.detail(visible.size() + "/" + Settings.allDefined().size() + " visible");
	}

	private static void configValidate(TestContext ctx) {
		CoreConfig ok = new CoreConfig();
		CoreConfig.validate(ok);
		bad(c -> c.multipliers.sell = 0, "sell 0");
		bad(c -> c.multipliers.sell = 10.5, "sell > 10");
		bad(c -> c.multipliers.jobPay = Double.NaN, "jobPay NaN");
		bad(c -> c.multipliers.jobPay = 0.05, "jobPay < 0.1");
		bad(c -> c.currency.symbol = "", "empty symbol");
		bad(c -> c.currency.symbol = "5$", "digit in symbol");
		bad(c -> c.currency.symbol = "§a$", "color code in symbol");
		bad(c -> c.currency.symbol = "abcdefghijklm", "symbol too long");
		bad(c -> c.currency.nameSingular = " ", "empty name");
		bad(c -> c.branding.sidebarTitle = "§6§l", "title only color codes");
		bad(c -> c.branding.sidebarTitle = null, "title null");
		bad(c -> c.branding.chatPrefix = "x".repeat(65), "prefix too long");
		bad(c -> c.startBalance = -1, "negative start balance");
		CoreConfig nulls = new CoreConfig();
		nulls.branding = null;
		nulls.currency = null;
		nulls.multipliers = null;
		CoreConfig.validate(nulls);
		isTrue(nulls.branding != null && nulls.currency != null && nulls.multipliers != null, "missing sections filled in");
		CoreConfig empty = new CoreConfig();
		empty.branding.chatPrefix = "";
		CoreConfig.validate(empty);
		ctx.detail("13 invalid values rejected");
	}

	private interface Mutation {
		void apply(CoreConfig c);
	}

	private static void bad(Mutation m, String what) {
		CoreConfig c = new CoreConfig();
		m.apply(c);
		assertThrows(IllegalArgumentException.class, () -> CoreConfig.validate(c), what);
	}

	private static void preserveCoreConfig(TestContext ctx) {
		CoreConfig before = AdminSettings.copy(CoreConfig.get());
		ctx.cleanup(() -> {
			String error = AdminSettings.save(before);
			if (error != null) throw new IllegalStateException(error);
		});
	}

	private static void configSet(TestContext ctx) {
		preserveCoreConfig(ctx);
		eq(null, AdminSettings.set("multipliers.sell", "1.5", "selftest"), "valid");
		eq(1.5, CoreConfig.get().multipliers.sell, "applied");
		isTrue(CoreConfig.CONFIG.problem() == null, "no config problem");
		String bad = AdminSettings.set("multipliers.sell", "99", "selftest");
		isTrue(bad != null && bad.contains("between"), "too large rejected: " + bad);
		eq(1.5, CoreConfig.get().multipliers.sell, "unchanged after error");
		isTrue(AdminSettings.set("multipliers.sell", "abc", "selftest") != null, "not a number rejected");
		isTrue(AdminSettings.set("treasuryStartCapital", "5", "selftest").startsWith("Unknown setting"), "not whitelisted");
		eq(null, AdminSettings.set("startBalance", "2.5k", "selftest"), "start balance");
		eq(250_000L, CoreConfig.get().startBalance, "2.5k = $2,500");
		eq(null, AdminSettings.set("currency.symbolAfter", "on", "selftest"), "bool");
		isTrue(CoreConfig.get().currency.symbolAfter, "symbolAfter on");
		try {
			String json = Files.readString(CoreConfig.CONFIG.path());
			isTrue(json.contains("\"sell\": 1.5"), "written to core.json");
		} catch (java.io.IOException e) {
			throw new AssertionError("cannot read core.json: " + e);
		}
		ctx.detail("ok");
	}

	private static void currency(TestContext ctx) {
		preserveCoreConfig(ctx);
		eq("$1,234.56", Money.format(123456), "Default");
		eq(null, AdminSettings.set("currency.symbol", "€", "selftest"), "Symbol €");
		eq(null, AdminSettings.set("currency.symbolAfter", "true", "selftest"), "after");
		eq("1,234.56€", Money.format(123456), "€ after");
		eq("-5.00€", Money.format(-500), "negative");
		eq("1.2K€", Money.formatShort(123456), "short");
		eq(500L, Money.parse("5€").orElse(-1), "parse 5€");
		eq(500L, Money.parse("€5").orElse(-1), "parse €5");
		eq(500L, Money.parse("$5").orElse(-1), "parse $5 still allowed");
		eq(null, AdminSettings.set("currency.symbol", "coins", "selftest"), "Wort-Symbol");
		eq("12.50 coins", Money.format(1250), "space before word symbol");
		eq(250_000L, Money.parse("2.5k coins").orElse(-1), "parse with word symbol");
		eq(null, AdminSettings.set("currency.symbolAfter", "false", "selftest"), "before");
		eq(null, AdminSettings.set("currency.symbol", "⛃", "selftest"), "Symbol ⛃");
		eq("⛃12.50", Money.format(1250), "⛃ before");
		eq("dollars", new CoreConfig().currency.namePlural, "default name");
		ctx.detail(Money.format(1250));
	}

	private static void prefix(TestContext ctx) {
		preserveCoreConfig(ctx);
		eq(Msg.DEFAULT_PREFIX, Msg.prefix(), "default prefix");
		eq(null, AdminSettings.set("branding.chatPrefix", "&bMyServer &8»", "selftest"), "prefix with & codes");
		eq("§bMyServer §8» ", CoreConfig.get().branding.chatPrefix, "& -> §, trailing space");
		eq("MyServer » Hello", Msg.prefixed("Hello").getString(), "message with new prefix");
		eq(null, AdminSettings.set("branding.sidebarTitle", "&e&lMy SMP", "selftest"), "title");
		eq("§e§lMy SMP", CoreConfig.get().branding.sidebarTitle, "title applied");
		isTrue(AdminSettings.set("branding.sidebarTitle", "&e&l", "selftest") != null, "title without text rejected");
		ctx.detail(Msg.prefixed("ok").getString());
	}

	private static void multipliers(TestContext ctx) {
		preserveCoreConfig(ctx);
		UUID id = ctx.player("TestA").getUUID();
		double before = Hooks.sellMultiplier(id, Items.WHEAT);
		isTrue(Hooks.sellParts(id, Items.WHEAT).stream().noneMatch(p -> p.id().equals("server")), "1.0 does not appear as a part");
		eq(null, AdminSettings.set("multipliers.sell", "2", "selftest"), "sell 2");
		eq(null, AdminSettings.set("multipliers.jobPay", "0.5", "selftest"), "jobPay 0.5");
		isTrue(Math.abs(Hooks.sellMultiplier(id, Items.WHEAT) - before * 2) < 1e-9, "sell factor doubled");
		isTrue(Hooks.sellParts(id, Items.WHEAT).stream().anyMatch(p -> p.id().equals("server") && p.factor() == 2.0), "part 'server' = 2");
		isTrue(Hooks.jobParts(id, "miner").stream().anyMatch(p -> p.id().equals("server") && p.factor() == 0.5), "job part 'server' = 0.5");
		ctx.detail("ok");
	}

	private static void inputs(TestContext ctx) {
		eq("§aHi §lX", AdminSettings.colours("&aHi &lX"), "& → §");
		eq("&aHi", AdminSettings.editable("§aHi"), "§ → &");
		eq("", AdminSettings.prefix("   "), "empty prefix");
		eq("§6A ", AdminSettings.prefix("&6A"), "prefix plus space");
		eq(" coins", AdminSettings.symbol("coins", true), "word after with space");
		eq("€", AdminSettings.symbol("€", true), "character after without space");
		eq(1.25, AdminSettings.number("1,25"), "comma");
		eq(2.0, AdminSettings.number("2x"), "2x");
		isTrue(AdminSettings.bool("on") && !AdminSettings.bool("no"), "bool");
		assertThrows(IllegalArgumentException.class, () -> AdminSettings.bool("maybe"), "invalid bool");
		assertThrows(IllegalArgumentException.class, () -> AdminSettings.money("-5"), "invalid money");
		eq(0L, AdminSettings.money("0"), "0 allowed");
	}

	private static void guiLayout(TestContext ctx) {
		eq(AdminSettings.ALL.size(), AdminGui.SLOT_SETTINGS.length, "every setting has a slot");
		Set<Integer> used = new java.util.HashSet<>();
		for (int s : new int[] {AdminGui.SLOT_INFO, AdminGui.SLOT_MODULES, AdminGui.SLOT_NPCS, AdminGui.SLOT_STARTER, AdminGui.SLOT_RELOAD,
				AdminGui.SLOT_RESTART, AdminGui.SLOT_CLOSE}) isTrue(used.add(s), "duplicate slot: " + s);
		for (int s : AdminGui.SLOT_SETTINGS) {
			isTrue(used.add(s), "duplicate slot: " + s);
			isTrue(s % 9 != 0 && s % 9 != 8 && s > 8 && s < 36, "slot in frame: " + s);
		}
		isTrue(AdminGui.moduleOrder().size() <= dev.smpeconomy.core.Gui.innerSlots(6).length, "all modules fit on one page");
		eq(ModuleGroup.CORE, AdminGui.moduleOrder().get(0).group(), "CORE first");
		eq(ModuleGroup.EXTRA, AdminGui.moduleOrder().get(AdminGui.moduleOrder().size() - 1).group(), "EXTRA last");
	}
}
