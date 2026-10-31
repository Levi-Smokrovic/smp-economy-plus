package dev.smpeconomy.features.bank;

import static dev.smpeconomy.core.test.Assert.assertThrows;
import static dev.smpeconomy.core.test.Assert.eq;
import static dev.smpeconomy.core.test.Assert.isFalse;
import static dev.smpeconomy.core.test.Assert.isTrue;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.function.ToLongFunction;
import net.fabricmc.fabric.api.entity.FakePlayer;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.EconomyService.Result;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Services;
import dev.smpeconomy.core.TimeUtil;
import dev.smpeconomy.core.Treasury;
import dev.smpeconomy.core.test.SelfTests;
import dev.smpeconomy.core.test.TestContext;
import dev.smpeconomy.core.test.TestPlayers;
import dev.smpeconomy.features.bank.BankData.Account;
import dev.smpeconomy.features.bank.BankData.Batch;
import dev.smpeconomy.features.bank.BankData.Claim;
import dev.smpeconomy.features.bank.BankData.Lot;

/**
 * Bank logic selftests; e2e/bank.mjs covers the GUI. Most run on a private {@link BankLedger} with its own data,
 * shadow account namespace and fake clock, so bank.json and live accounts stay untouched.
 */
final class BankSelfTests {
	private static final String F = "bank";
	private static final long H = BankLedger.HOUR_MS;
	private static final long DAY = 24 * H;
	private static final long MIN = 60_000L;

	private BankSelfTests() {}

	static void register() {
		SelfTests.register(F, "deposit-withdraw-conservation", BankSelfTests::conservation);
		SelfTests.register(F, "money-only-in-economy-json", BankSelfTests::singleFile);
		SelfTests.register(F, "amount-input", BankSelfTests::amountInput);
		SelfTests.register(F, "interest-24h-rule", BankSelfTests::holdRule);
		SelfTests.register(F, "interest-cap", BankSelfTests::interestCap);
		SelfTests.register(F, "interest-treasury-low", BankSelfTests::treasuryShortage);
		SelfTests.register(F, "interest-npc-hourly-budget-used", BankSelfTests::npcBudgetUsed);
		SelfTests.register(F, "interest-prorated-period", BankSelfTests::deadlineCut);
		SelfTests.register(F, "interest-exactly-once-on-crash", BankSelfTests::crashSafety);
		SelfTests.register(F, "midnight-clock", BankSelfTests::midnightClock);
		SelfTests.register(F, "entries-minimum-balance", BankSelfTests::lotsProperty);
		SelfTests.register(F, "persistence-repair", BankSelfTests::persistence);
		SelfTests.register(F, "service-live", BankSelfTests::liveService);
		SelfTests.register(F, "interest-live-treasury", BankSelfTests::liveTreasury);
	}

	private static final class Env {
		BankData data = new BankData();
		final BankConfig cfg = new BankConfig();
		final long[] now;
		final int[] dirty = {0};
		final int[] commits = {0};
		boolean commitOk = true;
		/** Runs on every commit, e.g. to capture what bank.json would contain at that point. */
		Runnable onCommit;
		final BankVault vault;
		final BankLedger ledger;

		Env(TestContext ctx, String name, long start) {
			now = new long[] {start};
			vault = BankVault.forTest(name);
			ctx.cleanup(vault::deleteAll);
			ledger = new BankLedger(() -> data, () -> dirty[0]++, () -> {
				commits[0]++;
				if (onCommit != null) onCommit.run();
				return commitOk;
			}, () -> now[0], () -> cfg, vault);
		}
	}

	private static class Unlimited implements BankLedger.InterestSource {
		long taken;

		@Override
		public long dailyBudget(long now) {
			return Money.MAX;
		}

		@Override
		public long available(long now, boolean last) {
			return Money.MAX;
		}

		@Override
		public Result pay(UUID account, long cents, long now) {
			Result r = EconomyService.deposit(account, cents);
			if (r.ok()) taken += cents;
			return r;
		}
	}

	private static long zurich(int y, int mo, int d, int h, int mi, int s) {
		return ZonedDateTime.of(y, mo, d, h, mi, s, 0, TimeUtil.zone()).toInstant().toEpochMilli();
	}

	private static UUID player(TestContext ctx, String name, long wallet) {
		FakePlayer p = ctx.player(name);
		UUID id = p.getUUID();
		EconomyService.ensureAccount(id, name);
		eq(Result.OK, EconomyService.set(id, wallet), "set cash " + name);
		return id;
	}

	private static void seed(Env env, UUID id, long amount, long t) {
		UUID acc = env.vault.ensure(id);
		isTrue(acc != null, "shadow account created");
		eq(Result.OK, EconomyService.set(acc, amount), "set bank balance");
		Account a = new Account();
		if (amount > 0) a.lots.add(new Lot(t, amount));
		env.data.accounts.put(id, a);
	}

	private static Treasury.Ledger testTreasury(TestContext ctx, long cents) {
		UUID till = TestPlayers.uuid("BankTill");
		ctx.cleanup(() -> {
			EconomyService.unregisterSystemAccount(till);
			EconomyService.deleteAccount(till);
		});
		EconomyService.registerSystemAccount(till, "BankTill");
		EconomyService.set(till, cents);
		Treasury.Data tdata = new Treasury.Data();
		return new Treasury.Ledger(till, () -> tdata, () -> {}, () -> 5.0);
	}

	private static long get(Map<UUID, Long> map, UUID id) {
		Long v = map.get(id);
		return v == null ? 0 : v;
	}

	private static Optional<BankLedger.Payout> tick(BankLedger l, BankLedger.InterestSource src) {
		List<BankLedger.Payout> list = l.dailyTick(src);
		isTrue(list.size() <= 1, "at most one payout per tick (" + list.size() + ")");
		return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
	}

	private static long reserve(Treasury.Ledger tl, long now, BankConfig cfg) {
		long cap = tl.hourlyCap(now);
		return cap - Money.percentOf(cap, cfg.hourlyBudgetSharePercent);
	}

	private static void checkInvariant(Env env, String where) {
		checkInvariant(env.data, env.vault::balance, where);
	}

	private static void checkInvariant(BankData data, ToLongFunction<UUID> balanceOf, String where) {
		data.accounts.forEach((id, a) -> {
			long sum = 0;
			long prev = Long.MIN_VALUE;
			for (Lot l : a.lots) {
				isTrue(l.a > 0, where + ": lot > 0");
				isTrue(l.t > prev, where + ": lots ascending and unique");
				prev = l.t;
				sum += l.a;
			}
			long balance = balanceOf.applyAsLong(id);
			eq(balance, sum, where + ": lot sum == balance");
			isTrue(balance >= 0 && balance <= Money.MAX, where + ": balance in [0, MAX]");
		});
	}

	private static void conservation(TestContext ctx) {
		Env env = new Env(ctx, "conservation", zurich(2026, 6, 10, 10, 0, 0));
		BankLedger l = env.ledger;
		UUID a = player(ctx, "BankA", 10_000);
		long total = 10_000;

		eq(Result.OK, l.deposit(a, 2_500), "deposit $25");
		eq(7_500L, EconomyService.balance(a), "cash -$25");
		eq(2_500L, l.balance(a), "bank +$25");
		eq(total, EconomyService.balance(a) + l.balance(a), "conservation after deposit");
		eq(Result.INSUFFICIENT_FUNDS, l.deposit(a, 7_501), "deposit more than cash");
		eq(Result.OK, l.deposit(a, 7_500), "deposit the rest");
		eq(0L, EconomyService.balance(a), "cash empty");
		eq(10_000L, l.balance(a), "bank $100");
		eq(Result.INSUFFICIENT_FUNDS, l.withdraw(a, 10_001), "withdraw more than bank");
		eq(10_000L, l.balance(a), "unchanged after error");
		eq(Result.OK, l.withdraw(a, 1), "withdraw 1 cent");
		eq(1L, EconomyService.balance(a), "cash 1 cent");
		eq(total, EconomyService.balance(a) + l.balance(a), "conservation after withdrawal");
		for (long bad : new long[] {0, -5, Money.MAX + 1, Long.MIN_VALUE}) {
			eq(Result.INVALID_AMOUNT, l.deposit(a, bad), "deposit invalid " + bad);
			eq(Result.INVALID_AMOUNT, l.withdraw(a, bad), "withdraw invalid " + bad);
		}
		eq(total, EconomyService.balance(a) + l.balance(a), "conservation after invalid amounts");
		checkInvariant(env, "after in/out");

		Random rnd = new Random(42);
		for (int i = 0; i < 500; i++) {
			env.now[0] += rnd.nextInt(600_000);
			long amount = 1 + rnd.nextInt(3_000);
			Result r = rnd.nextBoolean() ? l.deposit(a, amount) : l.withdraw(a, amount);
			isTrue(r == Result.OK || r == Result.INSUFFICIENT_FUNDS, "only OK/insufficient: " + r);
			eq(total, EconomyService.balance(a) + l.balance(a), "conservation at operation " + i);
		}
		checkInvariant(env, "after 500 random operations");
		isTrue(env.dirty[0] > 0, "marked dirty");

		UUID ghost = TestPlayers.uuid("BankGhost");
		EconomyService.deleteAccount(ghost);
		eq(Result.NO_ACCOUNT, l.deposit(ghost, 1), "deposit without account");
		seed(env, ghost, 500, env.now[0] - DAY);
		eq(Result.NO_ACCOUNT, l.withdraw(ghost, 100), "withdraw without account");
		isFalse(EconomyService.hasAccount(ghost), "no account created");
		eq(500L, l.balance(ghost), "bank balance unchanged");
		env.data.accounts.remove(ghost);
		env.vault.delete(ghost);
		eq(Result.NO_ACCOUNT, l.deposit(Treasury.ID, 1), "treasury has no bank");
		eq(Result.NO_ACCOUNT, l.deposit(env.vault.id(a), 1), "shadow account has no bank");

		UUID b = player(ctx, "BankB", 10);
		seed(env, b, Money.MAX - 5, env.now[0] - DAY);
		eq(Result.LIMIT_EXCEEDED, l.deposit(b, 6), "bank would exceed MAX");
		eq(10L, EconomyService.balance(b), "cash unchanged");
		eq(Result.OK, l.deposit(b, 5), "up to exactly MAX");
		eq(Money.MAX, l.balance(b), "bank = MAX");
		EconomyService.set(b, Money.MAX);
		eq(Result.LIMIT_EXCEEDED, l.withdraw(b, 1), "cash would exceed MAX");
		eq(Money.MAX, l.balance(b), "bank unchanged");
		checkInvariant(env, "after edge cases");
		ctx.detail("conservation over 500 random operations, limits MAX/no account ok");
	}

	/**
	 * Cash and bank balance both live in economy.json: a deposit never changes the total there, and a stale, restored
	 * or empty bank.json only affects lots, never money. A legacy balance moves exactly once.
	 */
	private static void singleFile(TestContext ctx) {
		long t0 = zurich(2026, 6, 10, 10, 0, 0);
		Env env = new Env(ctx, "one-file", t0);
		BankLedger l = env.ledger;
		UUID a = player(ctx, "BankA", 100_000);
		long money = EconomyService.totalMoney();

		eq(Result.OK, l.deposit(a, 50_000), "deposit $500");
		UUID shadow = env.vault.id(a);
		eq(50_000L, EconomyService.balance(shadow), "bank money sits on the shadow account in economy.json");
		eq(money, EconomyService.totalMoney(), "deposit doesn't change the money total in economy.json");
		isTrue(EconomyService.isSystemAccount(shadow), "shadow account is a system account");
		isFalse(EconomyService.top().stream().anyMatch(e -> e.id().equals(shadow)), "shadow account not in /baltop");
		isTrue(EconomyService.nameOf(shadow).isEmpty(), "shadow account nameless (not reachable through /pay or /eco)");
		isFalse(EconomyService.knownNames().contains(""), "no empty name in the name list");

		String stale = DataStore.gson().toJson(env.data);
		env.now[0] += 3 * H + 5 * MIN;
		eq(Result.OK, l.withdraw(a, 30_000), "withdraw $300");
		eq(Result.OK, l.deposit(a, 5_000), "deposit $50");
		eq(money, EconomyService.totalMoney(), "withdraw/deposit don't change the money total");
		long wallet = EconomyService.balance(a);
		eq(25_000L, l.balance(a), "bank $250");

		// crash between files, or bank.json restored from .bak: stale lots ($500), economy.json stays authoritative
		env.data = DataStore.gson().fromJson(stale, BankData.class);
		eq(50_000L, env.data.accounts.get(a).lots.stream().mapToLong(x -> x.a).sum(), "stale bank.json says $500");
		isTrue(BankLedger.reconcile(env.data, env.now[0], env.vault, List.of()) > 0, "reconciliation corrects the lots");
		eq(25_000L, l.balance(a), "bank stays $250 (no doubling)");
		eq(wallet, EconomyService.balance(a), "cash unchanged");
		eq(money, EconomyService.totalMoney(), "money total unchanged");
		checkInvariant(env, "after reconciling with stale bank.json");

		env.data = new BankData();
		BankLedger.reconcile(env.data, env.now[0], env.vault, List.of(a));
		eq(25_000L, l.balance(a), "bank balance after losing bank.json");
		isTrue(env.data.accounts.containsKey(a), "entry recreated");
		eq(0L, l.eligible(a, env.now[0]), "restored balance not yet eligible for interest");
		eq(25_000L, l.eligible(a, BankLedger.ceilHour(env.now[0])), "as a lot from the next full hour");
		eq(money, EconomyService.totalMoney(), "money total unchanged");
		checkInvariant(env, "after losing bank.json");

		UUID old = TestPlayers.uuid("BankAlt");
		BankData legacy = DataStore.gson().fromJson("{\"accounts\":{\"" + old + "\":{\"balance\":12345,\"lots\":[{\"t\":"
			+ (t0 - 2 * DAY) + ",\"a\":12345}]}}}", BankData.class);
		eq(Long.valueOf(12_345L), legacy.accounts.get(old).legacyBalance, "legacy field 'balance' read");
		isFalse(env.vault.exists(old), "no shadow account yet");
		long before = EconomyService.totalMoney();
		BankLedger.reconcile(legacy, t0, env.vault, List.of());
		eq(12_345L, env.vault.balance(old), "legacy balance moved");
		eq(before + 12_345L, EconomyService.totalMoney(), "exactly once in economy.json");
		eq(Long.valueOf(12_345L), legacy.accounts.get(old).legacyBalance, "legacy value stays until next load");
		BankLedger.reconcile(legacy, t0, env.vault, List.of());
		eq(12_345L, env.vault.balance(old), "second load doesn't move again");
		eq(before + 12_345L, EconomyService.totalMoney(), "no doubling");
		eq(null, legacy.accounts.get(old).legacyBalance, "legacy value dropped");
		isFalse(DataStore.gson().toJson(legacy).contains("\"balance\""), "bank.json holds no balance any more");
		eq(12_345L, BankLedger.eligible(legacy.accounts.get(old), t0 - DAY, env.vault.balance(old)), "old lots stay eligible");
		ctx.detail("money total constant, stale/empty bank.json changes no money, legacy balance exactly once");
	}

	private static void amountInput(TestContext ctx) {
		eq(10_000L, BankLedger.parseAmount("100", 0), "100");
		eq(250_000L, BankLedger.parseAmount("2.5k", 0), "2.5k");
		eq(5_000L, BankLedger.parseAmount("$50", 0), "$50");
		eq(1_250L, BankLedger.parseAmount(" 12,50 ", 0), "decimal comma");
		for (String all : new String[] {"all", "ALL", "max"}) eq(777L, BankLedger.parseAmount(all, 777), all);
		eq(0L, BankLedger.parseAmount("all", 0), "all of nothing = 0");
		eq(0L, BankLedger.parseAmount("all", -5), "negative balance = 0");
		eq(77L, BankLedger.parseAmount("10%", 777), "10% rounded down");
		eq(388L, BankLedger.parseAmount("50%", 777), "50% rounded down");
		eq(777L, BankLedger.parseAmount("100%", 777), "100% = all");
		eq(97L, BankLedger.parseAmount("12.5%", 777), "12.5 %");
		eq(97L, BankLedger.parseAmount("12,5 %", 777), "12.5% with spaces");
		eq(0L, BankLedger.parseAmount("10%", 5), "10% of 5 cents = 0");
		eq(Money.MAX, BankLedger.parseAmount("100%", Money.MAX), "100% of MAX");
		for (String bad : new String[] {"0%", "101%", "-5%", "%", "abc", "", "  ", "0", "-1", "1.000", "12.345", "1e5", "50%%", "100.001%"}) {
			eq(-1L, BankLedger.parseAmount(bad, 777), "invalid: '" + bad + "'");
		}
		eq(-1L, BankLedger.parseAmount(null, 777), "null");
	}

	private static void holdRule(TestContext ctx) {
		Env env = new Env(ctx, "24h", zurich(2026, 6, 10, 9, 0, 0));
		BankLedger l = env.ledger;
		Unlimited src = new Unlimited();
		UUID a = player(ctx, "BankA", 1_000_000);
		UUID b = player(ctx, "BankB", 1_000_000);
		UUID c = player(ctx, "BankC", 1_000_000);
		UUID d = player(ctx, "BankD", 1_000_000);
		UUID e = player(ctx, "BankE", 1_000_000);

		eq(Optional.empty(), tick(l, src), "first tick only records the date");
		eq("2026-06-10", env.data.lastInterestDate, "date recorded");

		env.now[0] = zurich(2026, 6, 10, 10, 0, 0);
		eq(Result.OK, l.deposit(a, 100_000), "A deposits D 10:00");
		eq(Result.OK, l.deposit(c, 100_000), "C deposits D 10:00");

		env.now[0] = zurich(2026, 6, 11, 0, 0, 5);
		eq(Optional.empty(), tick(l, src), "M1: nothing due");
		eq("2026-06-11", env.data.lastInterestDate, "M1: date moves on");
		eq(BankLedger.midnightOf(LocalDate.of(2026, 6, 11)), env.data.lastPayoutAt, "M1: cutoff = midnight");
		eq(0L, env.data.lastPayoutDue, "M1: nothing is 24 h old");
		isTrue(env.data.pending == null, "M1: no running payout");
		eq(Optional.empty(), tick(l, src), "M1 exactly once");

		env.now[0] = zurich(2026, 6, 11, 0, 0, 0);
		eq(Result.OK, l.deposit(d, 100_000), "D deposits exactly at 00:00:00");
		env.now[0] += 1;
		eq(Result.OK, l.deposit(e, 100_000), "E deposits 1 ms after midnight");
		env.now[0] = zurich(2026, 6, 11, 12, 0, 0);
		eq(Result.OK, l.withdraw(c, 60_000), "C withdraws D+1 12:00");
		env.now[0] = zurich(2026, 6, 11, 13, 0, 0);
		eq(Result.OK, l.deposit(c, 60_000), "C redeposits D+1 13:00");
		env.now[0] = zurich(2026, 6, 11, 23, 59, 0);
		eq(Result.OK, l.deposit(b, 100_000), "B deposits 1 min before midnight (farming attempt)");
		eq(100_000L, l.eligible(a, zurich(2026, 6, 11, 0, 0, 0)), "A: continuous since D+1 00:00");

		env.now[0] = zurich(2026, 6, 12, 0, 0, 3);
		BankLedger.Payout m2 = tick(l, src).orElseThrow(() -> new AssertionError("payout M2 missing"));
		eq(BankLedger.midnightOf(LocalDate.of(2026, 6, 12)), m2.reference(), "cutoff = midnight");
		Map<UUID, Long> paid = m2.paidByPlayer();
		eq(1_000L, get(paid, a), "A: 1% on $1,000 (38 h old)");
		eq(0L, get(paid, b), "B: 1 min old, no interest");
		eq(400L, get(paid, c), "C: only the minimum balance $400 (withdraw/redeposit)");
		eq(1_000L, get(paid, d), "D: exactly 24 h, interest");
		eq(0L, get(paid, e), "E: 24 h minus 1 ms, no interest");
		eq(2_400L, m2.paid(), "sum M2");
		eq(m2.paid(), src.taken, "taken from source = credited");
		eq(101_000L, l.balance(a), "interest to bank account");
		eq(1_000L, l.peek(a).orElseThrow().lastInterest, "last interest recorded");
		eq(Optional.empty(), tick(l, src), "M2 exactly once");

		env.now[0] = zurich(2026, 6, 12, 0, 1, 0);
		eq(Result.OK, l.withdraw(b, 100_000), "B withdraws everything");
		checkInvariant(env, "before M3");

		env.now[0] = zurich(2026, 6, 13, 0, 0, 1);
		BankLedger.Payout m3 = tick(l, src).orElseThrow(() -> new AssertionError("payout M3 missing"));
		eq(1_010L, get(m3.paidByPlayer(), a), "A: compound interest on $1,010");
		eq(0L, get(m3.paidByPlayer(), b), "B: nothing");
		eq(1_000L, get(m3.paidByPlayer(), e), "E: now 48 h old");
		eq(1_004L, get(m3.paidByPlayer(), c), "C: $1,004 (incl. M2 interest) continuously");
		checkInvariant(env, "after M3");
		ctx.detail("farming 1 min before midnight, exactly 24 h, LIFO minimum balance, compound interest ok");
	}

	private static void interestCap(TestContext ctx) {
		long t0 = zurich(2026, 6, 10, 0, 0, 0);
		Env env = new Env(ctx, "cap", t0);
		BankLedger l = env.ledger;
		eq(200_000L, env.cfg.maxInterestPerDay, "default cap $2,000");
		eq(1.0, env.cfg.interestPercent, "default 1%");
		eq(24, env.cfg.holdHours, "default 24 h");
		eq(50.0, env.cfg.hourlyBudgetSharePercent, "default: half the hourly budget for interest");
		eq(6, env.cfg.payoutHours, "default: payout until 06:00");

		eq(0L, l.interestFor(99), "99 cents gives 0");
		eq(1L, l.interestFor(100), "$1 gives 1 cent");
		eq(123L, l.interestFor(12_345), "rounded down");
		eq(200_000L, l.interestFor(20_000_000), "exactly at the cap");
		eq(200_000L, l.interestFor(20_000_100), "just above the cap");
		eq(200_000L, l.interestFor(300_000_000), "$3M capped at $2,000");
		eq(200_000L, l.interestFor(Money.MAX), "MAX capped");
		eq(0L, l.interestFor(0), "0");
		eq(0L, l.interestFor(-100), "negative");

		UUID rich = TestPlayers.uuid("BankRich");
		UUID mid = TestPlayers.uuid("BankMid");
		UUID full = TestPlayers.uuid("BankFull");
		seed(env, rich, 300_000_000, t0 - 2 * DAY);
		seed(env, mid, 12_345, t0 - 2 * DAY);
		seed(env, full, Money.MAX - 10, t0 - 2 * DAY);
		Unlimited src = new Unlimited();
		BankLedger.Payout p = l.payInterest(t0, src, null);
		eq(200_000L, get(p.paidByPlayer(), rich), "rich player gets at most $2,000");
		eq(123L, get(p.paidByPlayer(), mid), "small balance 1%");
		eq(10L, get(p.paidByPlayer(), full), "never above MAX");
		eq(Money.MAX, l.balance(full), "full = MAX");
		eq(1.0, p.factor(), "full rate");
		checkInvariant(env, "after capped payout");

		env.cfg.maxInterestPerDay = 50_000;
		eq(50_000L, l.interestFor(300_000_000), "cap configurable");
		env.cfg.interestPercent = 0;
		eq(0L, l.interestFor(300_000_000), "0% = no interest");
		env.cfg.interestPercent = 2.5;
		eq(30_000L, l.interestFor(1_200_000), "2.5 %");
		env.cfg.interestEnabled = false;
		eq(0L, l.interestFor(1_200_000), "disabled");
		eq(0L, l.payInterest(t0 + DAY, src, null).paid(), "disabled: nothing paid");

		for (java.util.function.Consumer<BankConfig> broken : List.<java.util.function.Consumer<BankConfig>>of(
			c -> c.interestPercent = -1, c -> c.maxInterestPerDay = -1, c -> c.holdHours = 0, c -> c.interestPercent = Double.NaN,
			c -> c.hourlyBudgetSharePercent = -1, c -> c.hourlyBudgetSharePercent = 101, c -> c.payoutHours = 21, c -> c.payoutHours = -1)) {
			BankConfig bad = new BankConfig();
			broken.accept(bad);
			assertThrows(IllegalArgumentException.class, bad::validate, "invalid config rejected");
		}
		new BankConfig().validate();
	}

	private static void treasuryShortage(TestContext ctx) {
		Treasury.Ledger tl = testTreasury(ctx, 1_000_000); // $10,000: daily budget 5% = $500, hourly budget $500
		long t0 = zurich(2026, 6, 10, 0, 0, 0);
		Env env = new Env(ctx, "treasury-low", t0 + 5_000);
		BankLedger l = env.ledger;
		BankLedger.InterestSource src = BankService.ledgerSource(tl, () -> env.cfg);
		UUID a = TestPlayers.uuid("BankA");
		UUID b = TestPlayers.uuid("BankB");
		UUID c = TestPlayers.uuid("BankC");
		seed(env, a, 10_000_000, t0 - 2 * DAY); // due $1,000
		seed(env, b, 5_000_000, t0 - 2 * DAY); // due $500
		seed(env, c, 2_000_000, t0 - 2 * DAY); // due $200
		eq(50_000L, src.dailyBudget(env.now[0]), "daily budget = 5% of $10,000");
		eq(25_000L, src.available(env.now[0], false), "per installment at most the bank share (50%) of the hourly budget");
		eq(50_000L, src.available(env.now[0], true), "last installment: whole remaining budget");

		long bankBefore = l.total();
		env.data.lastInterestDate = "2026-06-09";
		Optional<BankLedger.Payout> done = tick(l, src);
		eq(Optional.empty(), done, "first installment, not finished yet");
		Batch batch = l.pending().orElseThrow(() -> new AssertionError("payout created"));
		eq(170_000L, batch.totalDue, "due $1,700");
		eq(50_000L, batch.target, "cut to the daily budget $500");
		eq(t0 + 5_000 + 6 * H, batch.deadline, "deadline 6 h");
		isTrue(1_000_000L - tl.balance() <= 25_002, "first installment ≤ bank share");
		isTrue(tl.allowance(env.now[0]) + 3 >= reserve(tl, env.now[0], env.cfg), "NPC reserve stays free");
		for (int i = 0; i < 6 * 60 && done.isEmpty(); i++) {
			env.now[0] += MIN;
			long allowanceBefore = tl.allowance(env.now[0]);
			long reserveBefore = reserve(tl, env.now[0], env.cfg);
			done = tick(l, src);
			if (done.isEmpty()) {
				isTrue(tl.allowance(env.now[0]) + 3 >= Math.min(reserveBefore, allowanceBefore), "NPC reserve stays free (minute " + i + ")");
			}
		}
		BankLedger.Payout p = done.orElseThrow(() -> new AssertionError("payout not closed"));
		isTrue(env.now[0] < batch.deadline, "finished before the deadline");
		eq(170_000L, p.due(), "due $1,700");
		isTrue(p.treasuryShort(), "reason: treasury short");
		eq(29_411L, get(p.paidByPlayer(), a), "A pro rata floor(100000×5/17)");
		eq(14_705L, get(p.paidByPlayer(), b), "B pro rata");
		eq(5_882L, get(p.paidByPlayer(), c), "C pro rata");
		eq(49_998L, p.paid(), "sum ≤ budget");
		isTrue(p.factor() < 0.3 && p.factor() > 0.29, "factor ~0.294");
		eq(1_000_000L - 49_998L, tl.balance(), "treasury down by exactly what was paid");
		eq(bankBefore + 49_998L, l.total(), "bank up by exactly what was paid (conservation)");
		eq(new Treasury.Flow(0, 49_998), tl.stats24h(env.now[0]).get(BankService.R_INTEREST), "stats bank_interest");
		isTrue(env.data.pending == null, "closed");
		eq(49_998L, env.data.lastPayoutPaid, "stats of the last payout");
		checkInvariant(env, "after installments");

		env.now[0] += 2 * H;
		env.cfg.maxTreasuryPercent = 0;
		long till0 = tl.balance();
		long bank0 = l.total();
		BankLedger.Payout p4 = l.payInterest(t0 + 2 * DAY, src, null);
		eq(0L, p4.paid(), "budget 0, no interest");
		isTrue(p4.due() > 0, "something would be due");
		isFalse(p4.failed(), "no error");
		eq(till0, tl.balance(), "treasury unchanged");
		eq(bank0, l.total(), "bank unchanged");
		eq(0L, l.peek(a).orElseThrow().lastInterest, "last interest 0");

		env.cfg.maxTreasuryPercent = 100;
		EconomyService.set(TestPlayers.uuid("BankTill"), 0);
		env.now[0] += 2 * H;
		eq(0L, src.dailyBudget(env.now[0]), "empty treasury, budget 0");
		eq(0L, l.payInterest(t0 + 3 * DAY, src, null).paid(), "empty treasury pays nothing");
		eq(0L, tl.balance(), "treasury stays 0");

		env.cfg.maxTreasuryPercent = 5;
		EconomyService.set(TestPlayers.uuid("BankTill"), 100_000_000); // $1M: $50,000/h
		env.now[0] += 2 * H;
		long due = 0;
		for (UUID id : List.of(a, b, c)) due += l.interestFor(l.eligible(id, t0 + 4 * DAY - DAY));
		BankLedger.Payout p5 = l.payInterest(t0 + 4 * DAY, src, null);
		eq(due, p5.paid(), "enough budget, full rate");
		eq(1.0, p5.factor(), "factor 1");

		long bank5 = l.total();
		BankLedger.InterestSource refuse = new Unlimited() {
			@Override
			public Result pay(UUID account, long cents, long now) {
				return Result.TREASURY_LIMIT;
			}
		};
		BankLedger.Payout p6 = l.payInterest(t0 + 5 * DAY, refuse, null);
		isTrue(p6.failed(), "refused");
		eq(0L, p6.paid(), "nothing paid");
		eq(bank5, l.total(), "bank unchanged on refusal");
		checkInvariant(env, "after treasury tests");
		ctx.detail("pro rata 49,998/50,000 cents in installments, NPC reserve free, budget 0, empty treasury, refusal ok");
	}

	/**
	 * NPC purchases nearly drained the hourly budget just before midnight while the treasury itself is large:
	 * interest stays at the full rate and pays out once the hourly budget recovers, with the NPC reserve kept free.
	 */
	private static void npcBudgetUsed(TestContext ctx) {
		Treasury.Ledger tl = testTreasury(ctx, 2_500_000); // $25,000: hourly budget $1,250
		Env env = new Env(ctx, "npc-budget", zurich(2026, 6, 9, 23, 30, 0));
		BankLedger l = env.ledger;
		BankLedger.InterestSource src = BankService.ledgerSource(tl, () -> env.cfg);
		UUID a = TestPlayers.uuid("BankA");
		UUID b = TestPlayers.uuid("BankB");
		long t0 = zurich(2026, 6, 10, 0, 0, 0);
		seed(env, a, 5_000_000, t0 - 2 * DAY); // due $500
		seed(env, b, 1_000_000, t0 - 2 * DAY); // due $100
		env.data.lastInterestDate = "2026-06-09";

		eq(Result.OK, tl.withdraw(120_000, Treasury.R_NPC_BUY, env.now[0], true), "NPC purchases before midnight");
		env.now[0] = t0 + 1_000;
		long oldBudget = Math.min(tl.allowance(env.now[0]), Money.percentOf(tl.balance(), 5.0));
		isTrue(oldBudget * 10 < 60_000, "remaining budget at midnight only " + Money.format(oldBudget) + " (would be a cut below 10%)");

		long tillBefore = tl.balance();
		eq(Optional.empty(), tick(l, src), "midnight: payout created, NPC reserve still in use");
		Batch batch = l.pending().orElseThrow(() -> new AssertionError("payout created"));
		eq(60_000L, batch.totalDue, "due $600");
		eq(60_000L, batch.target, "not cut: the treasury is big enough");
		eq(tillBefore, tl.balance(), "nothing paid while the NPC reserve is in use");

		Optional<BankLedger.Payout> done = Optional.empty();
		for (int i = 0; i < 6 * 60 && done.isEmpty(); i++) {
			env.now[0] += MIN;
			long allowanceBefore = tl.allowance(env.now[0]);
			long reserveBefore = reserve(tl, env.now[0], env.cfg);
			long till = tl.balance();
			done = tick(l, src);
			if (allowanceBefore <= reserveBefore) eq(till, tl.balance(), "no installment into the NPC reserve (minute " + i + ")");
			if (done.isEmpty()) {
				isTrue(tl.allowance(env.now[0]) + 2 >= Math.min(reserveBefore, allowanceBefore), "NPC reserve stays free (minute " + i + ")");
			}
		}
		BankLedger.Payout p = done.orElseThrow(() -> new AssertionError("payout not closed"));
		isTrue(env.now[0] < batch.deadline, "finished before the deadline (06:00)");
		eq(1.0, p.factor(), "full rate");
		isFalse(p.treasuryShort(), "not 'treasury short'");
		eq(50_000L, get(p.paidByPlayer(), a), "A: full $500");
		eq(10_000L, get(p.paidByPlayer(), b), "B: full $100");
		eq(tillBefore - 60_000L, tl.balance(), "treasury - $600");
		eq(5_050_000L, l.balance(a), "A: interest on the bank account");
		eq(Optional.empty(), tick(l, src), "nothing further afterwards");
		checkInvariant(env, "after NPC budget test");
		ctx.detail("full rate despite $" + (120_000 / 100) + " NPC spending before midnight, finished after "
			+ TimeUtil.formatDuration(env.now[0] - t0) + " (would be: " + Money.format(oldBudget) + " budget)");
	}

	private static void deadlineCut(TestContext ctx) {
		Treasury.Ledger tl = testTreasury(ctx, 2_500_000);
		long t0 = zurich(2026, 6, 10, 0, 0, 0);
		Env env = new Env(ctx, "period", t0 + 1_000);
		env.cfg.payoutHours = 2;
		BankLedger l = env.ledger;
		BankLedger.InterestSource src = BankService.ledgerSource(tl, () -> env.cfg);
		UUID a = TestPlayers.uuid("BankA");
		UUID b = TestPlayers.uuid("BankB");
		seed(env, a, 5_000_000, t0 - 2 * DAY); // due $500
		seed(env, b, 1_000_000, t0 - 2 * DAY); // due $100
		env.data.lastInterestDate = "2026-06-09";
		long npcSpent = 0;
		Optional<BankLedger.Payout> done = Optional.empty();
		for (int i = 0; i <= 3 * 60 && done.isEmpty(); i++) {
			long allowance = tl.allowance(env.now[0]);
			if (allowance > 1_000) {
				eq(Result.OK, tl.withdraw(allowance - 1_000, Treasury.R_NPC_BUY, env.now[0], true), "NPC spending");
				npcSpent += allowance - 1_000;
			}
			done = tick(l, src);
			if (done.isEmpty()) env.now[0] += MIN;
		}
		BankLedger.Payout p = done.orElseThrow(() -> new AssertionError("deadline not met"));
		isTrue(env.now[0] >= t0 + 1_000 + 2 * H && env.now[0] < t0 + 1_000 + 2 * H + MIN, "closed exactly at the deadline");
		eq(60_000L, p.due(), "due $600");
		isFalse(p.treasuryShort(), "treasury big enough, cut only because the hourly budget is used up");
		eq(833L, get(p.paidByPlayer(), a), "A: floor(50000 × 1000/60000)");
		eq(166L, get(p.paidByPlayer(), b), "B: floor(10000 × 1000/60000), same share");
		eq(999L, p.paid(), "last installment = remaining budget $10");
		isTrue(env.data.pending == null, "closed");
		long till = tl.balance();
		env.now[0] += 3 * H;
		eq(Optional.empty(), tick(l, src), "nothing after the deadline");
		eq(till, tl.balance(), "treasury unchanged");
		eq(999L, env.data.lastPayoutPaid, "stats");
		checkInvariant(env, "after deadline");
		ctx.detail("deadline 2 h, NPCs " + Money.format(npcSpent) + ", rest pro rata 833/166 cents");
	}

	private static void crashSafety(TestContext ctx) {
		long t0 = zurich(2026, 6, 10, 0, 0, 0);
		Env env = new Env(ctx, "crash", t0 + 1_000);
		BankLedger l = env.ledger;
		Unlimited src = new Unlimited();
		UUID a = TestPlayers.uuid("BankA");
		UUID b = TestPlayers.uuid("BankB");
		seed(env, a, 5_000_000, t0 - 2 * DAY);
		seed(env, b, 1_000_000, t0 - 2 * DAY);
		env.data.lastInterestDate = "2026-06-09";

		env.commitOk = false;
		eq(Optional.empty(), tick(l, src), "save error: not closed");
		eq(0L, src.taken, "save error: nothing paid");
		eq(5_000_000L, l.balance(a), "A unchanged");
		Batch batch = l.pending().orElseThrow(() -> new AssertionError("payout created"));
		eq(0L, batch.released, "nothing released");
		batch.claims.values().forEach(c -> eq(0L, c.paid, "nothing marked as paid"));

		// crash after payment: economy.json saved, bank.json as of the commit
		env.commitOk = true;
		String[] onDisk = {null};
		env.onCommit = () -> onDisk[0] = DataStore.gson().toJson(env.data);
		env.now[0] += MIN;
		BankLedger.Payout p = tick(l, src).orElseThrow(() -> new AssertionError("payout missing"));
		eq(60_000L, p.paid(), "$600 paid");
		isTrue(onDisk[0] != null, "committed before payment");
		long balA = l.balance(a);
		long balB = l.balance(b);
		env.data = DataStore.gson().fromJson(onDisk[0], BankData.class);
		BankLedger.reconcile(env.data, env.now[0], env.vault, List.of());
		env.now[0] += MIN;
		List<BankLedger.Payout> again = l.dailyTick(src);
		eq(60_000L, src.taken, "not paid again after restart");
		eq(balA, l.balance(a), "A not doubled");
		eq(balB, l.balance(b), "B not doubled");
		isTrue(again.size() <= 1 && env.data.pending == null, "payout closed");
		checkInvariant(env, "after crash following payment");

		// crash before economy.json was saved: bank.json says paid, money missing. Better no interest than double
		env.data.lastInterestDate = "2026-06-10";
		env.now[0] = t0 + DAY + 1_000;
		env.onCommit = () -> onDisk[0] = DataStore.gson().toJson(env.data);
		long tillA = EconomyService.balance(env.vault.id(a));
		long tillB = EconomyService.balance(env.vault.id(b));
		long takenBefore = src.taken;
		tick(l, src).orElseThrow(() -> new AssertionError("payout day 2 missing"));
		isTrue(src.taken > takenBefore, "day 2 paid");
		EconomyService.set(env.vault.id(a), tillA);
		EconomyService.set(env.vault.id(b), tillB);
		env.data = DataStore.gson().fromJson(onDisk[0], BankData.class);
		BankLedger.reconcile(env.data, env.now[0], env.vault, List.of());
		long taken2 = src.taken;
		for (int i = 0; i < 5; i++) {
			env.now[0] += MIN;
			l.dailyTick(src);
		}
		eq(taken2, src.taken, "installment counts as paid, no second time");
		eq(tillA, l.balance(a), "A: this installment lost, not doubled");
		checkInvariant(env, "after crash before payment");
		ctx.detail("save error, crash after/before the economy save: never double");
	}

	private static void midnightClock(TestContext ctx) {
		Env env = new Env(ctx, "clock", zurich(2026, 3, 28, 12, 0, 0));
		BankLedger l = env.ledger;
		Unlimited src = new Unlimited();
		UUID a = TestPlayers.uuid("BankA");
		seed(env, a, 1_000_000, zurich(2026, 3, 20, 0, 0, 0));

		eq(Optional.empty(), tick(l, src), "first tick");
		env.now[0] = zurich(2026, 3, 28, 23, 59, 59);
		eq(Optional.empty(), tick(l, src), "nothing before midnight");
		env.now[0] = zurich(2026, 3, 29, 0, 0, 0);
		BankLedger.Payout p = tick(l, src).orElseThrow(() -> new AssertionError("midnight missed"));
		eq(zurich(2026, 3, 29, 0, 0, 0), p.reference(), "cutoff 03-29 00:00");
		eq(10_000L, p.paid(), "1% of $10,000");
		for (int i = 0; i < 5; i++) {
			env.now[0] += 1_000;
			eq(Optional.empty(), tick(l, src), "no second time on the same day");
		}
		// DST change on 2026-03-29: 23-hour day
		eq(23 * H, BankLedger.midnightOf(LocalDate.of(2026, 3, 30)) - BankLedger.midnightOf(LocalDate.of(2026, 3, 29)), "03-29 has 23 h");
		eq(25 * H, BankLedger.midnightOf(LocalDate.of(2026, 10, 26)) - BankLedger.midnightOf(LocalDate.of(2026, 10, 25)), "10-25 has 25 h");
		eq(zurich(2026, 3, 30, 0, 0, 0), BankLedger.nextMidnight(zurich(2026, 3, 29, 12, 0, 0)), "next midnight across DST start");
		eq(zurich(2026, 10, 26, 0, 0, 0), BankLedger.nextMidnight(zurich(2026, 10, 25, 2, 30, 0)), "next midnight across DST end");
		eq(zurich(2026, 3, 29, 1, 0, 0), BankLedger.ceilHour(zurich(2026, 3, 29, 0, 0, 1)), "round up to full hour");
		eq(zurich(2026, 3, 29, 0, 0, 0), BankLedger.ceilHour(zurich(2026, 3, 29, 0, 0, 0)), "full hour stays");

		UUID late = TestPlayers.uuid("BankLate");
		seed(env, late, 100_000, zurich(2026, 4, 2, 1, 0, 0));
		env.now[0] = zurich(2026, 4, 2, 5, 0, 0);
		long beforeA = l.balance(a);
		BankLedger.Payout catchUp = tick(l, src).orElseThrow(() -> new AssertionError("catch-up missing"));
		eq(zurich(2026, 4, 2, 0, 0, 0), catchUp.reference(), "catch-up with cutoff today 00:00");
		eq(1, catchUp.players(), "only A");
		isTrue(l.balance(a) - beforeA <= 200_000, "once, capped");
		eq(0L, get(catchUp.paidByPlayer(), late), "deposit after midnight not eligible");
		eq(Optional.empty(), tick(l, src), "nothing further afterwards");
		eq("2026-04-02", env.data.lastInterestDate, "date set");

		env.now[0] = zurich(2026, 4, 1, 12, 0, 0);
		eq(Optional.empty(), tick(l, src), "clock set back, no payout");
		eq("2026-04-02", env.data.lastInterestDate, "date stays");

		env.data.lastInterestDate = "broken";
		env.now[0] = zurich(2026, 4, 3, 0, 0, 1);
		eq(Optional.empty(), tick(l, src), "broken date, no payout");
		eq("2026-04-03", env.data.lastInterestDate, "date reset");

		// overlap: started 23:00 after an outage, the deadline reaches past the next midnight, so the payout closes
		// there first
		Unlimited onlyLast = new Unlimited() {
			@Override
			public long available(long now, boolean last) {
				return last ? Money.MAX : 0;
			}
		};
		env.cfg.payoutHours = 20;
		env.data.lastInterestDate = "2026-04-02";
		env.now[0] = zurich(2026, 4, 3, 23, 0, 0);
		eq(Optional.empty(), tick(l, onlyLast), "payout 04-03 created (pays only at deadline)");
		long ref3 = l.pending().orElseThrow(() -> new AssertionError("payout 04-03")).reference;
		env.now[0] = zurich(2026, 4, 4, 0, 0, 1);
		List<BankLedger.Payout> overlap = l.dailyTick(onlyLast);
		eq(1, overlap.size(), "old payout closed at midnight");
		eq(ref3, overlap.get(0).reference(), "the one from 04-03");
		eq(1.0, overlap.get(0).factor(), "last installment full");
		eq(zurich(2026, 4, 4, 0, 0, 0), l.pending().orElseThrow(() -> new AssertionError("new payout")).reference, "new one for 04-04");
		env.now[0] = zurich(2026, 4, 4, 20, 0, 2);
		eq(1.0, tick(l, onlyLast).orElseThrow(() -> new AssertionError("deadline 04-04")).factor(), "fully paid by the deadline");
		env.cfg.payoutHours = 6;

		env.cfg.interestEnabled = false;
		Account acc = l.peek(a).orElseThrow();
		BankLedger.addLot(acc, zurich(2026, 4, 4, 5, 0, 0), 1);
		BankLedger.addLot(acc, zurich(2026, 4, 4, 6, 0, 0), 1);
		eq(Result.OK, EconomyService.deposit(env.vault.id(a), 2), "test money to the lots");
		long taken = src.taken;
		env.now[0] = zurich(2026, 4, 6, 0, 0, 1);
		eq(Optional.empty(), tick(l, src), "disabled, no payout");
		eq(taken, src.taken, "nothing taken");
		eq("2026-04-06", env.data.lastInterestDate, "date moves on");
		eq(1, acc.lots.size(), "old lots merged");
		checkInvariant(env, "clock tests");
	}

	/**
	 * Property: the eligible balance at cutoff C (full hour) equals the minimum balance in [C, now] for random
	 * operations; merging old lots doesn't change later cutoffs.
	 */
	private static void lotsProperty(TestContext ctx) {
		long start = zurich(2026, 6, 10, 0, 0, 0);
		Env env = new Env(ctx, "entries", start);
		BankLedger l = env.ledger;
		UUID a = player(ctx, "BankA", 10_000_000_000L);
		Random rnd = new Random(7);
		List<long[]> history = new ArrayList<>();
		for (int i = 0; i < 400; i++) {
			env.now[0] += 1 + rnd.nextInt(20 * 60_000);
			long amount = 1 + rnd.nextInt(50_000);
			boolean dep = rnd.nextInt(10) < 6 || l.balance(a) < amount;
			Result r = dep ? l.deposit(a, amount) : l.withdraw(a, amount);
			eq(Result.OK, r, "Operation " + i);
			history.add(new long[] {env.now[0], l.balance(a)});
		}
		long end = env.now[0];
		checkInvariant(env, "after random operations");
		isTrue(l.peek(a).orElseThrow().lots.size() <= (end - start) / H + 2, "at most one lot per hour");

		int checked = 0;
		for (long cutoff = start; cutoff <= BankLedger.ceilHour(end); cutoff += H) {
			eq(minHeldSince(history, cutoff), l.eligible(a, cutoff), "minimum balance since cutoff " + (cutoff - start) / H + " h");
			checked++;
		}
		long mid = start + ((end - start) / 2 / H) * H;
		List<Long> expected = new ArrayList<>();
		for (long cutoff = mid; cutoff <= BankLedger.ceilHour(end); cutoff += H) expected.add(l.eligible(a, cutoff));
		long balance = l.balance(a);
		long oldLots = l.peek(a).orElseThrow().lots.stream().filter(x -> x.t <= mid).count();
		int merged = l.compact(mid);
		eq(Math.max(0, oldLots - 1), merged, "old lots merged");
		isTrue(l.peek(a).orElseThrow().lots.stream().filter(x -> x.t <= mid).count() <= 1, "at most one old lot");
		int idx = 0;
		for (long cutoff = mid; cutoff <= BankLedger.ceilHour(end); cutoff += H) {
			eq((long) expected.get(idx++), l.eligible(a, cutoff), "after merging, same at cutoff " + (cutoff - start) / H + " h");
		}
		eq(balance, l.balance(a), "balance unchanged");
		checkInvariant(env, "after merging");

		UUID b = player(ctx, "BankB", 1_000);
		env.now[0] = zurich(2026, 7, 1, 10, 0, 1);
		for (int i = 0; i < 100; i++) {
			eq(Result.OK, l.deposit(b, 1), "tiny deposit " + i);
			env.now[0] += 30_000;
		}
		isTrue(l.peek(b).orElseThrow().lots.size() <= 2, "100 deposits in 50 min, ≤ 2 lots");
		ctx.detail(checked + " cutoffs checked against minimum balance");
	}

	private static long minHeldSince(List<long[]> history, long cutoff) {
		long at = 0;
		int i = 0;
		for (; i < history.size() && history.get(i)[0] <= cutoff; i++) at = history.get(i)[1];
		long min = at;
		for (; i < history.size(); i++) min = Math.min(min, history.get(i)[1]);
		return min;
	}

	private static void persistence(TestContext ctx) {
		long t0 = zurich(2026, 6, 10, 12, 0, 0);
		Env env = new Env(ctx, "persistence", t0);
		UUID a = TestPlayers.uuid("BankA");
		UUID b = TestPlayers.uuid("BankB");
		seed(env, a, 5_000, t0 - 3 * DAY);
		BankLedger.addLot(env.data.accounts.get(a), t0 - H, 2_500);
		EconomyService.deposit(env.vault.id(a), 2_500);
		env.data.accounts.get(a).totalInterest = 77;
		seed(env, b, 0, t0);
		env.data.lastInterestDate = "2026-06-10";
		Batch batch = new Batch();
		batch.reference = t0 - 12 * H;
		batch.totalDue = 300;
		batch.target = 300;
		batch.budget = 1_000;
		batch.released = 100;
		Claim claim = new Claim(300);
		claim.paid = 100;
		batch.claims.put(a, claim);
		env.data.pending = batch;

		String json = DataStore.gson().toJson(env.data);
		isFalse(json.contains("\"balance\""), "bank.json holds no balance");
		BankData copy = DataStore.gson().fromJson(json, BankData.class);
		eq(0, BankLedger.repair(copy, t0, env.vault::balance), "valid data needs no repair");
		BankLedger again = new BankLedger(() -> copy, () -> {}, () -> true, () -> t0, () -> env.cfg, env.vault);
		eq(7_500L, again.balance(a), "balance (economy.json)");
		eq(env.ledger.eligible(a, t0 - DAY), again.eligible(a, t0 - DAY), "eligible after JSON round trip");
		eq(77L, again.peek(a).orElseThrow().totalInterest, "interest total after JSON round trip");
		eq("2026-06-10", copy.lastInterestDate, "date after JSON round trip");
		eq(100L, copy.pending.claims.get(a).paid, "running payout after JSON round trip");

		BankData broken = new BankData();
		Map<UUID, Long> balances = new HashMap<>();
		Account tooMuch = new Account();
		tooMuch.lots.add(new Lot(t0 - 2 * DAY, 800));
		tooMuch.lots.add(new Lot(t0 - H, 700));
		Account tooLittle = new Account();
		tooLittle.lots.add(new Lot(t0 - 2 * DAY, 300));
		Account messy = new Account();
		messy.lots.add(new Lot(t0 - H, 100));
		messy.lots.add(null);
		messy.lots.add(new Lot(t0 - 2 * DAY, -50));
		messy.lots.add(new Lot(t0 - 2 * DAY, 200));
		messy.lots.add(new Lot(t0 - 2 * DAY, 300));
		Account nullLots = new Account();
		nullLots.lots = null;
		Account gone = new Account();
		gone.lots.add(new Lot(t0, 5));
		UUID u1 = UUID.randomUUID(), u2 = UUID.randomUUID(), u3 = UUID.randomUUID(), u4 = UUID.randomUUID(), u5 = UUID.randomUUID();
		broken.accounts.put(u1, tooMuch);
		broken.accounts.put(u2, tooLittle);
		broken.accounts.put(u3, messy);
		broken.accounts.put(u4, nullLots);
		broken.accounts.put(u5, gone);
		broken.accounts.put(UUID.randomUUID(), null);
		balances.put(u1, 1_000L);
		balances.put(u2, 1_000L);
		balances.put(u3, 600L);
		balances.put(u4, 50L);
		balances.put(u5, -10L);
		Batch bad = new Batch();
		bad.claims.put(u1, new Claim(100));
		bad.claims.get(u1).paid = 500;
		bad.claims.put(u2, null);
		bad.totalDue = 9_999;
		bad.target = 9_999;
		bad.budget = 50;
		bad.released = 9_999;
		broken.pending = bad;
		ToLongFunction<UUID> balanceOf = id -> balances.getOrDefault(id, 0L);
		int fixed = BankLedger.repair(broken, t0, balanceOf);
		isTrue(fixed >= 6, "all broken accounts repaired (" + fixed + ")");
		checkInvariant(broken, id -> Math.max(0, balanceOf.applyAsLong(id)), "after repair");
		eq(5, broken.accounts.size(), "null account removed");
		eq(800L, BankLedger.eligible(tooMuch, t0 - DAY, 1_000), "too many lots: newest trimmed, old ones stay");
		eq(300L, BankLedger.eligible(tooLittle, t0 - DAY, 1_000), "missing rest not eligible");
		eq(500L, BankLedger.eligible(messy, t0 - DAY, 600), "equal timestamps merged");
		isTrue(gone.lots.isEmpty(), "balance 0, no lots");
		eq(1, broken.pending.claims.size(), "broken claim removed");
		eq(100L, broken.pending.claims.get(u1).paid, "paid ≤ due");
		eq(100L, broken.pending.totalDue, "sum recalculated");
		isTrue(broken.pending.target <= 50 && broken.pending.released <= broken.pending.target, "target ≤ budget, released ≤ target");
		eq(0, BankLedger.repair(broken, t0, balanceOf), "second repair: nothing left to do");
	}

	private static void liveService(TestContext ctx) {
		Services.Bank service = Services.find(Services.Bank.class).orElseThrow(() -> new AssertionError("Services.Bank not registered"));
		FakePlayer fp = ctx.player("BankA");
		UUID a = player(ctx, "BankA", 5_000);
		long money = EconomyService.totalMoney();
		eq(0L, service.balance(a), "0 without a bank account");
		eq(Result.OK, BankService.deposit(a, 1_234), "live deposit");
		eq(1_234L, service.balance(a), "service returns bank balance");
		eq(3_766L, EconomyService.balance(a), "cash");
		eq(money, EconomyService.totalMoney(), "money stays in economy.json");
		UUID shadow = BankVault.LIVE.id(a);
		isTrue(EconomyService.isSystemAccount(shadow), "live shadow account is a system account");
		eq(Result.OK, BankService.withdraw(a, 234), "live withdrawal");
		eq(1_000L, service.balance(a), "service after withdrawal");
		eq(Result.INSUFFICIENT_FUNDS, BankService.withdraw(a, 1_001), "live overdraw");
		eq("Bank interest", Treasury.reasonLabel(BankService.R_INTEREST), "treasury reason named");
		isTrue(BankService.STORE.isLoaded(), "bank.json loaded");
		checkInvariant(BankService.STORE.get(), BankVault.LIVE::balance, "live data");
		TestPlayers.cleanup(fp);
		eq(0L, service.balance(a), "cleanup deletes the test bank account");
		isTrue(BankService.LIVE.peek(a).isEmpty(), "no account left");
		isFalse(EconomyService.hasAccount(shadow), "shadow account deleted");
		isFalse(EconomyService.isSystemAccount(shadow), "shadow account unregistered");
		BankService.CONFIG.get().validate();
	}

	private static void liveTreasury(TestContext ctx) {
		ctx.preserveTreasury();
		UUID a = player(ctx, "BankA", 500_000);
		BankLedger l = BankService.LIVE;
		eq(Result.OK, BankService.deposit(a, 400_000), "deposit $4,000");
		BankLedger.Payout fresh = l.payInterest(l.now(), BankService.liveSource(), id -> id.equals(a));
		eq(0L, fresh.due(), "fresh deposit, no interest");
		isTrue(l.backdate(a, 25 * H), "backdate 25 h");
		eq(400_000L, l.eligibleNow(a), "eligible now");
		long treasuryBefore = Treasury.balance();
		long allowance = Treasury.allowance();
		BankLedger.Payout p = l.payInterest(l.now(), BankService.liveSource(), id -> id.equals(a));
		eq(4_000L, p.due(), "1% of $4,000 due");
		isTrue(p.budget() <= allowance, "budget ≤ remaining treasury budget");
		eq(Math.min(p.due(), p.budget()), p.paid(), "paid = min(due, budget)");
		eq(treasuryBefore - p.paid(), Treasury.balance(), "treasury - paid");
		eq(400_000L + p.paid(), BankService.balance(a), "bank + paid");
		eq(1, p.dueByPlayer().size(), "only the test player");
		if (p.paid() > 0) {
			Treasury.Flow flow = Treasury.stats24h().get(BankService.R_INTEREST);
			isTrue(flow != null && flow.expense() >= p.paid(), "stats bank_interest");
		}
		ctx.detail("paid " + Money.format(p.paid()) + " (budget " + Money.format(p.budget()) + ")");
	}
}
