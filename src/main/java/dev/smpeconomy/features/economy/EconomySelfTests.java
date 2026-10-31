package dev.smpeconomy.features.economy;

import static dev.smpeconomy.core.test.Assert.check;
import static dev.smpeconomy.core.test.Assert.eq;
import static dev.smpeconomy.core.test.Assert.isFalse;
import static dev.smpeconomy.core.test.Assert.isTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.ToLongFunction;
import net.fabricmc.fabric.api.entity.FakePlayer;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.EconomyService.Result;
import dev.smpeconomy.core.Hooks;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.Services;
import dev.smpeconomy.core.Settings;
import dev.smpeconomy.core.Treasury;
import dev.smpeconomy.core.test.SelfTests;
import dev.smpeconomy.core.test.TestContext;
import dev.smpeconomy.core.test.TestPlayers;

final class EconomySelfTests {
	private static final String F = "economy";

	private EconomySelfTests() {}

	static void register() {
		SelfTests.register(F, "pay-ok", EconomySelfTests::payOk);
		SelfTests.register(F, "pay-edge-cases", EconomySelfTests::payEdgeCases);
		SelfTests.register(F, "pay-offline-aggregate", EconomySelfTests::payOfflineAggregate);
		SelfTests.register(F, "paytoggle", EconomySelfTests::payToggle);
		SelfTests.register(F, "baltop-ordering", EconomySelfTests::baltopOrdering);
		SelfTests.register(F, "baltop-paging", EconomySelfTests::baltopPaging);
		SelfTests.register(F, "baltop-bank-service", EconomySelfTests::baltopBankService);
		SelfTests.register(F, "eco-ops", EconomySelfTests::ecoOps);
		SelfTests.register(F, "treasury-math", EconomySelfTests::treasuryMath);
		SelfTests.register(F, "treasury-admin", EconomySelfTests::treasuryAdmin);
	}

	private static UUID account(TestContext ctx, String name, long balance) {
		FakePlayer p = ctx.player(name);
		UUID id = p.getUUID();
		EconomyService.ensureAccount(id, name);
		eq(Result.OK, EconomyService.set(id, balance), "set " + name);
		return id;
	}

	private static void payOk(TestContext ctx) {
		UUID a = account(ctx, "EcoPayA", 10_000);
		UUID b = account(ctx, "EcoPayB", 0);
		long total = EconomyService.totalMoney();
		long treasury = Treasury.balance();

		// offline target: FakePlayers aren't in the PlayerList, so resolve through the name cache like
		// Args.knownPlayer
		eq(b, EconomyService.findByName("ecopayb").orElse(null), "target via name cache (case-insensitive)");
		isTrue(ctx.server().getPlayerList().getPlayer(b) == null, "target is offline");

		Payments.Outcome o = Payments.pay(ctx.server(), a, "EcoPayA", b, 2_500);
		eq(Payments.Status.OK, o.status(), "payment");
		isFalse(o.targetOnline(), "target reported offline");
		eq(7_500L, EconomyService.balance(a), "sender");
		eq(2_500L, EconomyService.balance(b), "recipient");
		eq(total, EconomyService.totalMoney(), "money total preserved");
		eq(treasury, Treasury.balance(), "treasury untouched");

		List<String> pending = PlayerDataService.getPendingMessages(b);
		eq(1, pending.size(), "one pending message");
		isTrue(pending.get(0).contains("EcoPayA") && pending.get(0).contains("$25.00"), "Pending-Text: " + pending.get(0));

		eq(Payments.Status.OK, Payments.pay(ctx.server(), a, "EcoPayA", b, 7_500).status(), "send everything");
		eq(0L, EconomyService.balance(a), "sender empty");
		eq(10_000L, EconomyService.balance(b), "recipient everything");
		eq(Payments.Status.INSUFFICIENT_FUNDS, Payments.pay(ctx.server(), a, "EcoPayA", b, 1).status(), "broke afterwards");
		eq(total, EconomyService.totalMoney(), "money total preserved (2)");
		ctx.detail("transfer, offline target, pending message, money total preserved");
	}

	private static void payEdgeCases(TestContext ctx) {
		UUID a = account(ctx, "EcoPayA", 1_000);
		UUID b = account(ctx, "EcoPayB", 0);
		long total = EconomyService.totalMoney();
		long treasury = Treasury.balance();

		eq(Payments.Status.INVALID_AMOUNT, Payments.pay(ctx.server(), a, "EcoPayA", b, 0).status(), "0");
		eq(Payments.Status.INVALID_AMOUNT, Payments.pay(ctx.server(), a, "EcoPayA", b, -100).status(), "negative");
		eq(Payments.Status.INVALID_AMOUNT, Payments.pay(ctx.server(), a, "EcoPayA", b, Long.MIN_VALUE).status(), "Long.MIN_VALUE");
		eq(Payments.Status.INVALID_AMOUNT, Payments.pay(ctx.server(), a, "EcoPayA", b, Money.MAX + 1).status(), "above MAX");
		eq(Payments.Status.SELF, Payments.pay(ctx.server(), a, "EcoPayA", a, 100).status(), "to self");
		eq(Payments.Status.SYSTEM_ACCOUNT, Payments.pay(ctx.server(), a, "EcoPayA", Treasury.ID, 100).status(), "to the treasury");

		UUID ghost = TestPlayers.uuid("EcoGhost");
		EconomyService.deleteAccount(ghost);
		eq(Payments.Status.NO_ACCOUNT, Payments.pay(ctx.server(), a, "EcoPayA", ghost, 100).status(), "target without account");
		isFalse(EconomyService.hasAccount(ghost), "no account created");

		eq(Payments.Status.INSUFFICIENT_FUNDS, Payments.pay(ctx.server(), a, "EcoPayA", b, 1_001).status(), "not enough money");

		EconomyService.set(b, Money.MAX - 50);
		eq(Payments.Status.TARGET_FULL, Payments.pay(ctx.server(), a, "EcoPayA", b, 100).status(), "recipient account full");
		eq(Money.MAX - 50, EconomyService.balance(b), "recipient unchanged on overflow");
		eq(Payments.Status.OK, Payments.pay(ctx.server(), a, "EcoPayA", b, 50).status(), "up to exactly MAX allowed");
		eq(Money.MAX, EconomyService.balance(b), "recipient exactly MAX");
		EconomyService.set(b, 0);
		EconomyService.set(a, 1_000);
		total = EconomyService.totalMoney();

		Settings.set(b, Settings.PAY_RECEIVE, false);
		eq(Payments.Status.RECEIVE_DISABLED, Payments.pay(ctx.server(), a, "EcoPayA", b, 100).status(), "pay_receive off");
		isTrue(Payments.Status.RECEIVE_DISABLED.message("EcoPayB").contains("EcoPayB"), "message names the recipient");
		Settings.set(b, Settings.PAY_RECEIVE, true);
		eq(Payments.Status.OK, Payments.pay(ctx.server(), a, "EcoPayA", b, 100).status(), "pay_receive on again");

		eq(900L, EconomyService.balance(a), "sender after all cases");
		eq(100L, EconomyService.balance(b), "recipient after all cases");
		eq(total, EconomyService.totalMoney(), "no money created");
		eq(treasury, Treasury.balance(), "treasury untouched");
		List<String> pending = PlayerDataService.getPendingMessages(b);
		eq(1, pending.size(), "only successful payments reported (one line)");
		isTrue(pending.get(0).contains("$1.50") && pending.get(0).contains("2 payments"), "Pending: " + pending.get(0));

		isTrue(Money.parse("0").isEmpty(), "input 0 rejected");
		isTrue(Money.parse("-5").isEmpty(), "input -5 rejected");
		isTrue(Money.parse("abc").isEmpty(), "input abc rejected");
		isTrue(Money.parse("1.000").isEmpty(), "input 1.000 (thousands dot) rejected");
		isTrue(Money.parse("0.001").isEmpty(), "input 0.001 rejected");
		isTrue(Money.parse("99999999999999t").isEmpty(), "input above MAX rejected");
		eq(250_000L, Money.parse("2.5k").orElse(-1), "input 2.5k");
		ctx.detail("0, negative, MIN_VALUE, >MAX, self, treasury, no account, not enough, overflow, pay_receive off");
	}

	private static void payOfflineAggregate(TestContext ctx) {
		UUID a = account(ctx, "EcoPayA", 100_000);
		UUID b = account(ctx, "EcoPayB", 0);
		UUID c = account(ctx, "EcoPayC", 100_000);

		PlayerDataService.addPendingMessage(b, "§7Other message");
		for (long cents : new long[] {100, 250, 50}) {
			eq(Payments.Status.OK, Payments.pay(ctx.server(), a, "EcoPayA", b, cents).status(), "payment " + cents);
		}
		List<String> pending = PlayerDataService.getPendingMessages(b);
		eq(2, pending.size(), "one line per sender (+ other message)");
		String line = pending.get(1);
		isTrue(line.contains("EcoPayA") && line.contains("$4.00") && line.contains("3 payments"), "sum/count: " + line);

		eq(Payments.Status.OK, Payments.pay(ctx.server(), c, "EcoPayC", b, 1_000).status(), "second sender");
		pending = PlayerDataService.getPendingMessages(b);
		eq(3, pending.size(), "second sender has its own line");
		isTrue(pending.get(2).contains("EcoPayC") && pending.get(2).contains("$10.00") && !pending.get(2).contains("payments"), "single payment: " + pending.get(2));

		eq(Payments.Status.OK, Payments.pay(ctx.server(), a, "EcoPayA", b, 1).status(), "another payment A");
		pending = PlayerDataService.getPendingMessages(b);
		eq(3, pending.size(), "still one line per sender");
		eq("§7Other message", pending.get(0), "other message stays");
		isTrue(pending.get(2).contains("EcoPayA") && pending.get(2).contains("$4.01") && pending.get(2).contains("4 payments"), "A updated: " + pending.get(2));
		isTrue(pending.get(1).contains("EcoPayC"), "C stays");

		// flood: 60 tiny payments evict nothing (pending limit is 50)
		for (int i = 0; i < 60; i++) Payments.pay(ctx.server(), a, "EcoPayA", b, 1);
		pending = PlayerDataService.getPendingMessages(b);
		eq(3, pending.size(), "flood creates no new lines");
		eq("§7Other message", pending.get(0), "other message not evicted");
		isTrue(pending.get(2).contains("$4.61") && pending.get(2).contains("64 payments"), "flood summed: " + pending.get(2));

		Payments.onJoin(b);
		PlayerDataService.removePendingMessages(b, pending.size());
		eq(Payments.Status.OK, Payments.pay(ctx.server(), a, "EcoPayA", b, 500).status(), "payment after join");
		pending = PlayerDataService.getPendingMessages(b);
		eq(1, pending.size(), "new line after delivery");
		isTrue(pending.get(0).contains("$5.00") && !pending.get(0).contains("payments"), "sum starts over: " + pending.get(0));

		// line was evicted before delivery: the sum still includes every payment never reported
		PlayerDataService.removePendingMessages(b, 1);
		eq(Payments.Status.OK, Payments.pay(ctx.server(), a, "EcoPayA", b, 100).status(), "payment after eviction");
		pending = PlayerDataService.getPendingMessages(b);
		eq(1, pending.size(), "one line");
		isTrue(pending.get(0).contains("$6.00") && pending.get(0).contains("2 payments"), "never-reported payments stay in the sum: " + pending.get(0));

		eq(100_000L - 400 - 1 - 60 - 500 - 100, EconomyService.balance(a), "sender A");
		eq(400L + 1_000 + 1 + 60 + 500 + 100, EconomyService.balance(b), "recipient");
		ctx.detail("sums per sender, no eviction of other messages, reset on join");
	}

	private static void payToggle(TestContext ctx) {
		UUID a = account(ctx, "EcoPayA", 0);
		isTrue(Settings.get(a, Settings.PAY_RECEIVE), "default on");
		isFalse(Payments.setReceive(a, null), "toggle to off");
		isFalse(Settings.get(a, Settings.PAY_RECEIVE), "stored off");
		isTrue(Payments.setReceive(a, null), "toggle to on");
		isFalse(Payments.setReceive(a, false), "explicit off");
		isFalse(Payments.setReceive(a, false), "explicit off (idempotent)");
		isFalse(Settings.get(a, Settings.PAY_RECEIVE), "stays off");
		isTrue(Payments.setReceive(a, true), "explicit on");
		isTrue(Settings.get(a, Settings.PAY_RECEIVE), "on again");
	}

	private static UUID id(int n) {
		return new UUID(0x5eedL, n);
	}

	private static void baltopOrdering(TestContext ctx) {
		List<EconomyService.Entry> entries = List.of(
			new EconomyService.Entry(id(1), "Bob", 500),
			new EconomyService.Entry(id(2), "alice", 500),
			new EconomyService.Entry(id(3), "Carl", 1_000),
			new EconomyService.Entry(id(4), "dave", 0),
			new EconomyService.Entry(id(5), "Eve", 200),
			new EconomyService.Entry(id(6), "", 100));

		List<Baltop.Row> rows = Baltop.rank(entries, null);
		eq(List.of("Carl", "alice", "Bob", "Eve", "Unknown", "dave"), rows.stream().map(Baltop.Row::name).toList(), "order without bank");
		eq(1, Baltop.rankOf(rows, id(3)), "Carl #1");
		eq(2, Baltop.rankOf(rows, id(2)), "tie by name: alice before Bob");
		eq(0, Baltop.rankOf(rows, id(99)), "not listed");
		isTrue(rows.stream().allMatch(r -> r.bank() == 0 && r.total() == r.balance()), "without bank: total = account");

		Map<UUID, Long> bank = new HashMap<>();
		bank.put(id(5), 2_000L);
		bank.put(id(4), -50L);
		ToLongFunction<UUID> provider = u -> {
			if (u.equals(id(1))) throw new IllegalStateException("bank broken");
			return bank.getOrDefault(u, 0L);
		};
		rows = Baltop.rank(entries, provider);
		eq(List.of("Eve", "Carl", "alice", "Bob", "Unknown", "dave"), rows.stream().map(Baltop.Row::name).toList(), "order with bank");
		Baltop.Row eve = rows.get(0);
		eq(200L, eve.balance(), "Eve account");
		eq(2_000L, eve.bank(), "Eve bank");
		eq(2_200L, eve.total(), "Eve total");
		eq(0L, rows.get(5).bank(), "negative bank = 0");
		eq(0L, rows.get(3).bank(), "bank service error = 0");

		List<Baltop.Row> big = Baltop.rank(List.of(new EconomyService.Entry(id(7), "Rich", Money.MAX)), u -> Money.MAX);
		eq(2 * Money.MAX, big.get(0).total(), "account MAX + bank MAX without overflow");

		UUID t = account(ctx, "EcoTopA", Money.MAX);
		eq(0, Baltop.rankOf(Baltop.live(), t), "test account not in /baltop");
		ctx.detail("sorting, ties, bank column, errors/negative, overflow, test accounts hidden");
	}

	private static void baltopPaging(TestContext ctx) {
		List<EconomyService.Entry> entries = new ArrayList<>();
		for (int i = 0; i < 25; i++) entries.add(new EconomyService.Entry(id(100 + i), "P" + (100 + i), 1_000L * (i + 1)));
		List<Baltop.Row> rows = Baltop.rank(entries, null);
		eq("P124", rows.get(0).name(), "richest first");

		Baltop.Page p1 = Baltop.page(rows, 1, 10);
		eq(10, p1.rows().size(), "page 1 full");
		eq(3, p1.pages(), "3 pages");
		eq(0, p1.offset(), "offset page 1");
		eq(25, p1.total(), "total count");
		Baltop.Page p3 = Baltop.page(rows, 3, 10);
		eq(5, p3.rows().size(), "page 3 rest");
		eq(20, p3.offset(), "offset page 3 (rank 21)");
		eq("P104", p3.rows().get(0).name(), "rank 21");
		eq(3, Baltop.page(rows, 99, 10).page(), "page too large gives the last");
		eq(1, Baltop.page(rows, 0, 10).page(), "page 0 gives 1");
		eq(1, Baltop.page(rows, -5, 10).page(), "negative page gives 1");

		Baltop.Page empty = Baltop.page(List.of(), 3, 10);
		eq(1, empty.pages(), "empty: 1 page");
		eq(1, empty.page(), "empty: page 1");
		eq(0, empty.rows().size(), "empty: no rows");
		eq(1, Baltop.page(rows.subList(0, 10), 2, 10).pages(), "exactly 10 gives 1 page");
		eq(2, Baltop.page(rows.subList(0, 11), 2, 10).pages(), "11 gives 2 pages");
		eq(1, Baltop.page(rows.subList(0, 11), 2, 10).rows().size(), "11 gives page 2 with 1 row");
	}

	private static void baltopBankService(TestContext ctx) {
		Optional<Services.Bank> previous = Services.find(Services.Bank.class);
		Services.Bank fake = id -> 7;
		Hooks.Registration reg = Services.register(Services.Bank.class, fake);
		ctx.cleanup(() -> {
			reg.remove();
			previous.ifPresent(p -> Services.register(Services.Bank.class, p));
		});
		isTrue(Baltop.hasBank(), "bank service detected");
		List<Baltop.Row> rows = Baltop.live();
		eq(EconomyService.top().size(), rows.size(), "all accounts in the ranking");
		isTrue(rows.stream().allMatch(r -> r.bank() == 7 && r.total() == r.balance() + 7), "bank column from the service, total = account + bank");
		for (int i = 1; i < rows.size(); i++) check(rows.get(i - 1).total() >= rows.get(i).total(), "descending by total");
		reg.remove();
		isTrue(Services.find(Services.Bank.class).isEmpty() || previous.isPresent(), "service unregistered");
		if (previous.isEmpty()) {
			isFalse(Baltop.hasBank(), "no bank column without the service");
			isTrue(Baltop.live().stream().allMatch(r -> r.bank() == 0 && r.total() == r.balance()), "without the service total = account");
		}
		ctx.detail("Services.Bank drives column and sorting, neutral without the service (" + rows.size() + " accounts)");
	}

	private static void ecoOps(TestContext ctx) {
		ctx.preserveTreasury();
		UUID a = account(ctx, "EcoAdmA", 0);
		long treasury = Treasury.balance();

		eq(Result.OK, EcoAdmin.give(a, 500, "selftest"), "give");
		eq(500L, EconomyService.balance(a), "after give");
		eq(Result.INVALID_AMOUNT, EcoAdmin.give(a, 0, "selftest"), "give 0");
		eq(Result.INVALID_AMOUNT, EcoAdmin.give(a, -1, "selftest"), "give negative");
		eq(Result.INSUFFICIENT_FUNDS, EcoAdmin.take(a, 501, "selftest"), "take too much");
		eq(500L, EconomyService.balance(a), "unchanged after failed take");
		eq(Result.INVALID_AMOUNT, EcoAdmin.take(a, 0, "selftest"), "take 0");
		eq(Result.OK, EcoAdmin.take(a, 200, "selftest"), "take");
		eq(300L, EconomyService.balance(a), "after take");
		eq(Result.OK, EcoAdmin.take(a, 300, "selftest"), "take all");
		eq(0L, EconomyService.balance(a), "empty");

		eq(Result.OK, EcoAdmin.set(a, 0, "selftest"), "set 0");
		eq(Result.INVALID_AMOUNT, EcoAdmin.set(a, -1, "selftest"), "set negative");
		eq(Result.INVALID_AMOUNT, EcoAdmin.set(a, Money.MAX + 1, "selftest"), "set above MAX");
		eq(Result.OK, EcoAdmin.set(a, Money.MAX, "selftest"), "set MAX");
		eq(Result.LIMIT_EXCEEDED, EcoAdmin.give(a, 1, "selftest"), "give above MAX");
		eq(Money.MAX, EconomyService.balance(a), "unchanged at MAX");

		eq(Result.OK, EcoAdmin.reset(a, "selftest"), "reset");
		eq(Math.max(0, EconomyService.startBalance()), EconomyService.balance(a), "reset gives starting balance");

		eq(Result.NO_ACCOUNT, EcoAdmin.give(Treasury.ID, 100, "selftest"), "give treasury");
		eq(Result.NO_ACCOUNT, EcoAdmin.take(Treasury.ID, 100, "selftest"), "take treasury");
		eq(Result.NO_ACCOUNT, EcoAdmin.set(Treasury.ID, 0, "selftest"), "set treasury");
		eq(Result.NO_ACCOUNT, EcoAdmin.reset(Treasury.ID, "selftest"), "reset treasury");
		eq(treasury, Treasury.balance(), "treasury unchanged");
		ctx.detail("give/take/set/reset incl. 0, negative, MAX, not enough, system account");
	}

	private static void treasuryMath(TestContext ctx) {
		UUID till = account(ctx, "EcoTill", 100_000);
		UUID p = account(ctx, "EcoPayA", 0);
		Treasury.Data data = new Treasury.Data();
		Treasury.Ledger ledger = new Treasury.Ledger(till, () -> data, () -> {}, () -> 5.0);
		long t0 = 1_800_000_000_000L; // fixed time keeps minute and hour boundaries deterministic

		TreasuryReport r0 = TreasuryReport.of(ledger, t0, 5.0);
		eq(100_000L, r0.balance(), "balance");
		eq(5_000L, r0.maxPerHour(), "budget 5% of $1,000");
		eq(5_000L, r0.allowance(), "free");
		eq(0, r0.usedPercent(), "usage 0%");

		eq(Result.OK, ledger.payTo(p, 2_000, Treasury.R_NPC_BUY, t0), "NPC spending");
		TreasuryReport r1 = TreasuryReport.of(ledger, t0, 5.0);
		eq(98_000L, r1.balance(), "balance after spending");
		eq(5_000L, r1.maxPerHour(), "budget includes this hour's spending");
		eq(2_000L, r1.spentLastHour(), "spent");
		eq(3_000L, r1.allowance(), "free = budget - spent");
		eq(40, r1.usedPercent(), "usage 40%");
		eq(Result.TREASURY_LIMIT, ledger.payTo(p, 3_001, Treasury.R_NPC_BUY, t0), "above the remaining budget");
		eq(Result.OK, ledger.payTo(p, 3_000, Treasury.R_NPC_BUY, t0), "remaining budget exactly");
		eq(100, TreasuryReport.of(ledger, t0, 5.0).usedPercent(), "usage 100%");

		eq(Result.OK, ledger.deposit(10_000, Treasury.R_SHOP, t0 + 1_000), "income");
		TreasuryReport r2 = TreasuryReport.of(ledger, t0 + 1_000, 5.0);
		eq(105_000L, r2.balance(), "balance after income");
		eq(5_500L, r2.maxPerHour(), "budget = 5% × (balance + this hour's spending)");
		eq(500L, r2.allowance(), "free");
		eq(90, r2.usedPercent(), "usage 90%");
		eq(10_000L, r2.totals24h().income(), "income 24 h");
		eq(5_000L, r2.totals24h().expense(), "spending 24 h");
		eq(5_000L, r2.totals24h().net(), "net 24 h");
		eq(List.of(Treasury.R_SHOP, Treasury.R_NPC_BUY), r2.topReasons24h().stream().map(Map.Entry::getKey).toList(), "reasons by volume");

		List<String> lines = r2.publicLines();
		String all = String.join("\n", lines);
		isTrue(all.contains("Balance: §a$1,050.00"), "balance line: " + all);
		isTrue(all.contains("§e$55.00 §8(5% of the Treasury)"), "budget line");
		isTrue(all.contains("§c$50.00 §8(90%)") && all.contains("left: §a$5.00"), "usage line");
		isTrue(all.contains("§a+$100.00") && all.contains("§c-$50.00") && all.contains("net §a+$50.00"), "24 h line");
		isTrue(all.contains(Treasury.reasonLabel(Treasury.R_SHOP)) && all.contains(Treasury.reasonLabel(Treasury.R_NPC_BUY)), "reasons named");

		TreasuryReport r3 = TreasuryReport.of(ledger, t0 + 61 * 60_000L, 5.0);
		eq(0L, r3.spentLastHour(), "after 61 min nothing left in the hour window");
		eq(5_250L, r3.maxPerHour(), "budget after one hour");
		eq(5_250L, r3.allowance(), "full budget free");
		eq(15_000L, r3.totals24h().income() + r3.totals24h().expense(), "24-h stats still complete");

		TreasuryReport r4 = TreasuryReport.of(ledger, t0 + 25 * 3_600_000L, 5.0);
		eq(0L, r4.totals24h().income(), "after 25 h no 24-h income");
		eq(0L, r4.totals24h().expense(), "after 25 h no 24-h spending");
		isTrue(r4.topReasons24h().isEmpty(), "no reasons left");
		eq(10_000L, r4.allTimeTotal().income(), "total income stays");
		eq(5_000L, r4.allTimeTotal().expense(), "total spending stays");
		isTrue(String.join("\n", r4.adminLines()).contains("All time: §a+$100.00"), "admin line total");

		eq("5%", TreasuryReport.percent(5.0), "percent whole number");
		eq("2.5%", TreasuryReport.percent(2.5), "percent with decimals");
		eq("§c-$1.50", TreasuryReport.signed(-150), "negative red");
		eq("§7$0.00", TreasuryReport.signed(0), "zero gray");
		TreasuryReport zero = new TreasuryReport(0, 0, 0, 0, 5.0, Map.of(), Treasury.Flow.ZERO, Map.of());
		eq(0, zero.usedPercent(), "no budget and no spending 0%");
		check(new TreasuryReport(0, 0, 0, 10, 5.0, Map.of(), Treasury.Flow.ZERO, Map.of()).usedPercent() == 100, "no budget with spending 100%");
		check(TreasuryReport.reasonLine(Treasury.R_ADMIN, new Treasury.Flow(100, 0)).equals("§8 • §7" + Treasury.reasonLabel(Treasury.R_ADMIN) + ": §a+$1.00"), "reason income only");
		ctx.detail("budget/usage/remaining, 24-h window, total, formatting");
	}

	private static void treasuryAdmin(TestContext ctx) {
		ctx.preserveTreasury();
		long b0 = Treasury.balance();
		long spent0 = Treasury.spentLastHour();
		Treasury.Flow admin0 = Treasury.stats24h().getOrDefault(Treasury.R_ADMIN, Treasury.Flow.ZERO);

		eq(Result.OK, EcoAdmin.treasuryDeposit(12_345, "selftest"), "deposit");
		eq(b0 + 12_345, Treasury.balance(), "balance after deposit");
		eq(Result.OK, EcoAdmin.treasuryWithdraw(345, "selftest"), "withdraw");
		eq(b0 + 12_000, Treasury.balance(), "balance after withdrawal");
		eq(spent0, Treasury.spentLastHour(), "admin withdrawal doesn't count toward the NPC cap");
		Treasury.Flow admin1 = Treasury.stats24h().getOrDefault(Treasury.R_ADMIN, Treasury.Flow.ZERO);
		eq(admin0.income() + 12_345, admin1.income(), "stats income (admin)");
		eq(admin0.expense() + 345, admin1.expense(), "stats spending (admin)");

		long now = Treasury.balance();
		eq(Result.INSUFFICIENT_FUNDS, EcoAdmin.treasuryWithdraw(Math.min(Money.MAX, now + 1), "selftest"), "more than available");
		eq(now, Treasury.balance(), "unchanged after failure");
		eq(Result.INVALID_AMOUNT, EcoAdmin.treasuryDeposit(0, "selftest"), "deposit 0");
		eq(Result.INVALID_AMOUNT, EcoAdmin.treasuryWithdraw(-5, "selftest"), "withdraw negative");

		TreasuryReport live = TreasuryReport.live();
		eq(Treasury.balance(), live.balance(), "report = balance");
		eq(Treasury.maxSpendPerHour(), live.maxPerHour(), "report = Treasury.maxSpendPerHour");
		eq(Treasury.allowance(), live.allowance(), "report = Treasury.allowance");
		isTrue(live.publicLines().get(1).contains(Money.format(Treasury.balance())), "balance line");
		isTrue(live.publicLines().get(2).contains(Money.format(Treasury.maxSpendPerHour())), "budget line");
		ctx.detail("deposit/withdraw through Treasury (reason admin), cap untouched, failure cases, live report");
	}
}
