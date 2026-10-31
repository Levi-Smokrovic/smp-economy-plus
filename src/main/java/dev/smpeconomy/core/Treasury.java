package dev.smpeconomy.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.EconomyService.Result;

/**
 * The treasury: a system account (fixed UUID {@link #ID}) in {@link EconomyService} that never goes negative. Income
 * via {@link #deposit}/{@link #collectFrom}, NPC spending via {@link #payTo}/{@link #tryWithdraw}. NPC spending is
 * capped per rolling hour: at most {@code treasuryHourlySpendPercent} of the balance as it stood before that spending
 * ({@code balance + spent in the last hour}). Income and spending per reason are counted hourly for 24 h. Stored in
 * {@code treasury.json}; the balance itself lives in {@code economy.json}.
 */
public final class Treasury {
	public static final UUID ID = UUID.fromString("00000000-0000-0000-0000-00000000c0de");
	public static final String NAME = "Treasury";

	public static final String R_START = "start";
	public static final String R_SHOP = "shop";
	public static final String R_AH_TAX = "ah_tax";
	public static final String R_NPC_SALE = "npc_sale";
	public static final String R_NPC_BUY = "npc_buy";
	public static final String R_NPC_ORDER = "npc_order";
	public static final String R_ORDER_FEE = "order_fee";
	public static final String R_COINFLIP_FEE = "coinflip_fee";
	public static final String R_BOUNTY_FEE = "bounty_fee";
	public static final String R_LOTTO_FEE = "lotto_fee";
	public static final String R_REWARD = "reward";
	public static final String R_ADMIN = "admin";

	private static final long MINUTE_MS = 60_000L;
	private static final long HOUR_MS = 3_600_000L;
	private static final Map<String, String> REASON_LABELS = new LinkedHashMap<>();

	public record Flow(long income, long expense) {
		public static final Flow ZERO = new Flow(0, 0);

		public long net() {
			return income - expense;
		}

		public Flow plus(Flow o) {
			return new Flow(Math.addExact(income, o.income), Math.addExact(expense, o.expense));
		}
	}

	/** Public only for selftests. */
	public static final class Data {
		public boolean initialized;
		public Map<Long, Long> spendByMinute = new HashMap<>();
		public Map<Long, Map<String, long[]>> flowsByHour = new HashMap<>();
		public Map<String, long[]> totals = new HashMap<>();

		void fixNulls() {
			if (spendByMinute == null) spendByMinute = new HashMap<>();
			if (flowsByHour == null) flowsByHour = new HashMap<>();
			if (totals == null) totals = new HashMap<>();
			spendByMinute.values().removeIf(v -> v == null || v < 0);
			flowsByHour.values().removeIf(m -> m == null);
			flowsByHour.values().forEach(m -> m.values().removeIf(a -> a == null || a.length != 2));
			totals.values().removeIf(a -> a == null || a.length != 2);
		}
	}

	private static final DataStore<Data> STORE = new DataStore<>("treasury.json", Data.class, Data::new);
	private static final Ledger LIVE = new Ledger(ID, STORE::get, STORE::markDirty, () -> CoreConfig.get().treasuryHourlySpendPercent);

	static {
		STORE.onLoad(Data::fixNulls);
		EconomyService.registerSystemAccount(ID, NAME);
		registerReason(R_START, "Starting capital");
		registerReason(R_SHOP, "Shop purchases");
		registerReason(R_AH_TAX, "AH tax");
		registerReason(R_NPC_SALE, "NPC sales");
		registerReason(R_NPC_BUY, "NPC purchases in the AH");
		registerReason(R_NPC_ORDER, "NPC orders");
		registerReason(R_ORDER_FEE, "Order fees");
		registerReason(R_COINFLIP_FEE, "Coinflip fees");
		registerReason(R_BOUNTY_FEE, "Bounty fees");
		registerReason(R_LOTTO_FEE, "Lottery fees");
		registerReason(R_REWARD, "Rewards");
		registerReason(R_ADMIN, "Admin");
	}

	private Treasury() {}

	public static void bootstrap() {
	}

	public static void init() {
		EconomyService.registerSystemAccount(ID, NAME);
		Data data = STORE.get();
		if (!STORE.isLoaded() || data.initialized) return;
		if (EconomyService.balance(ID) > 0) {
			// treasury.json was missing or broken but the treasury already has money: paying in the initial capital
			// again would print money.
			data.initialized = true;
			STORE.markDirty();
			SmpEconomy.LOG.warn("[SMPEco] treasury.json has no opening record but the treasury holds {}, no new start capital", Money.format(EconomyService.balance(ID)));
			return;
		}
		long capital = CoreConfig.get().treasuryStartCapital;
		if (capital > 0) {
			Result r = LIVE.deposit(capital, R_START, System.currentTimeMillis());
			if (!r.ok()) SmpEconomy.LOG.error("[SMPEco] Treasury start capital not paid in: {}", r);
		}
		data.initialized = true;
		STORE.markDirty();
		SmpEconomy.LOG.info("[SMPEco] Treasury opened with {}", Money.format(capital));
	}

	public static long balance() {
		return LIVE.balance();
	}

	/** Not new money: the caller already charged it, e.g. from a player. Errors (treasury full) may be ignored. */
	public static Result deposit(long cents, String reason) {
		return LIVE.deposit(cents, reason, System.currentTimeMillis());
	}

	public static Result collectFrom(UUID player, long cents, String reason) {
		return LIVE.collectFrom(player, cents, reason, System.currentTimeMillis());
	}

	public static Result payTo(UUID player, long cents, String reason) {
		return LIVE.payTo(player, cents, reason, System.currentTimeMillis());
	}

	public static boolean tryWithdraw(long cents, String reason) {
		return LIVE.withdraw(cents, reason, System.currentTimeMillis(), true).ok();
	}

	public static Result withdraw(long cents, String reason) {
		return LIVE.withdraw(cents, reason, System.currentTimeMillis(), true);
	}

	/** Admin commands only. Does not count toward the hourly cap. */
	public static Result withdrawUncapped(long cents, String reason) {
		return LIVE.withdraw(cents, reason, System.currentTimeMillis(), false);
	}

	/**
	 * Uncapped payout that never takes the treasury negative. Only for commitments already covered: casino wins (play
	 * is only allowed if the treasury covers the maximum win) and refunds of money this player paid in. Never for NPC
	 * purchases, orders or rewards, which go capped through {@link #payTo}.
	 */
	public static Result payToUncapped(UUID player, long cents, String reason) {
		return LIVE.payTo(player, cents, reason, System.currentTimeMillis(), false);
	}

	public static boolean canSpend(long cents) {
		return cents > 0 && cents <= allowance();
	}

	public static long allowance() {
		return LIVE.allowance(System.currentTimeMillis());
	}

	/**
	 * {@code pct% x (balance + spent last hour)}, at most what is there. A single capped expense above this will not
	 * succeed within the next hour, so such actions should be rejected rather than retried.
	 */
	public static long maxSpendPerHour() {
		return LIVE.hourlyCap(System.currentTimeMillis());
	}

	public static long spentLastHour() {
		return LIVE.spentLastHour(System.currentTimeMillis());
	}

	public static Map<String, Flow> stats24h() {
		return LIVE.stats24h(System.currentTimeMillis());
	}

	public static Flow totals24h() {
		return LIVE.totals24h(System.currentTimeMillis());
	}

	public static Map<String, Flow> totalsAllTime() {
		return LIVE.totalsAllTime();
	}

	/**
	 * Selftests only: the returned Runnable restores balance and stats ({@code TestContext.preserveTreasury()}), so
	 * tests do not distort the real treasury.
	 */
	public static Runnable snapshot() {
		long balance = EconomyService.balance(ID);
		String json = DataStore.gson().toJson(STORE.get());
		return () -> {
			Data restored = DataStore.gson().fromJson(json, Data.class);
			restored.fixNulls();
			STORE.set(restored);
			EconomyService.set(ID, balance);
		};
	}

	public static void registerReason(String id, String label) {
		REASON_LABELS.put(id, label);
	}

	public static String reasonLabel(String id) {
		return REASON_LABELS.getOrDefault(id, id);
	}

	/** Treasury logic on any account with explicit time. Public only for selftests. */
	public static final class Ledger {
		private final UUID account;
		private final Supplier<Data> data;
		private final Runnable markDirty;
		private final DoubleSupplier capPercent;

		public Ledger(UUID account, Supplier<Data> data, Runnable markDirty, DoubleSupplier capPercent) {
			this.account = account;
			this.data = data;
			this.markDirty = markDirty;
			this.capPercent = capPercent;
		}

		public long balance() {
			return EconomyService.balance(account);
		}

		public long spentLastHour(long now) {
			long nowMinute = Math.floorDiv(now, MINUTE_MS);
			long sum = 0;
			for (Map.Entry<Long, Long> e : data.get().spendByMinute.entrySet()) {
				if (e.getKey() > nowMinute - 60 && e.getKey() <= nowMinute) sum = Math.addExact(sum, e.getValue());
			}
			return sum;
		}

		public long allowance(long now) {
			long balance = balance();
			long spent = spentLastHour(now);
			double pct = capPercent.getAsDouble();
			if (!(pct > 0)) return 0;
			if (pct >= 100) return balance;
			return Math.max(0, Math.min(balance, cap(balance, spent, pct) - spent));
		}

		public long hourlyCap(long now) {
			long balance = balance();
			long spent = spentLastHour(now);
			double pct = capPercent.getAsDouble();
			if (!(pct > 0)) return 0;
			long total = Math.addExact(balance, spent);
			return pct >= 100 ? total : Math.min(total, cap(balance, spent, pct));
		}

		private static long cap(long balance, long spent, double pct) {
			return BigDecimal.valueOf(balance).add(BigDecimal.valueOf(spent))
				.multiply(BigDecimal.valueOf(pct)).movePointLeft(2).setScale(0, RoundingMode.FLOOR).longValue();
		}

		public Result deposit(long cents, String reason, long now) {
			Result r = EconomyService.deposit(account, cents);
			if (r.ok()) record(reason, cents, 0, now);
			return r;
		}

		public Result withdraw(long cents, String reason, long now, boolean capped) {
			if (!Money.isValidAmount(cents)) return Result.INVALID_AMOUNT;
			if (balance() < cents) return Result.INSUFFICIENT_FUNDS;
			if (capped && cents > allowance(now)) return Result.TREASURY_LIMIT;
			Result r = EconomyService.withdraw(account, cents);
			if (r.ok()) {
				if (capped) addSpend(cents, now);
				record(reason, 0, cents, now);
			}
			return r;
		}

		public Result payTo(UUID player, long cents, String reason, long now) {
			return payTo(player, cents, reason, now, true);
		}

		public Result payTo(UUID player, long cents, String reason, long now, boolean capped) {
			if (!Money.isValidAmount(cents)) return Result.INVALID_AMOUNT;
			if (account.equals(player)) return Result.SAME_ACCOUNT;
			if (balance() < cents) return Result.INSUFFICIENT_FUNDS;
			if (capped && cents > allowance(now)) return Result.TREASURY_LIMIT;
			Result r = EconomyService.transfer(account, player, cents);
			if (r.ok()) {
				if (capped) addSpend(cents, now);
				record(reason, 0, cents, now);
			}
			return r;
		}

		public Result collectFrom(UUID player, long cents, String reason, long now) {
			if (account.equals(player)) return Result.SAME_ACCOUNT;
			Result r = EconomyService.transfer(player, account, cents);
			if (r.ok()) record(reason, cents, 0, now);
			return r;
		}

		public Map<String, Flow> stats24h(long now) {
			long nowHour = Math.floorDiv(now, HOUR_MS);
			Map<String, Flow> result = new TreeMap<>();
			data.get().flowsByHour.forEach((hour, byReason) -> {
				if (hour <= nowHour - 24 || hour > nowHour) return;
				byReason.forEach((reason, f) -> result.merge(reason, new Flow(f[0], f[1]), Flow::plus));
			});
			return result;
		}

		public Flow totals24h(long now) {
			Flow sum = Flow.ZERO;
			for (Flow f : stats24h(now).values()) sum = sum.plus(f);
			return sum;
		}

		public Map<String, Flow> totalsAllTime() {
			Map<String, Flow> result = new TreeMap<>();
			data.get().totals.forEach((reason, f) -> result.put(reason, new Flow(f[0], f[1])));
			return result;
		}

		private void addSpend(long cents, long now) {
			Data d = data.get();
			long nowMinute = Math.floorDiv(now, MINUTE_MS);
			d.spendByMinute.merge(nowMinute, cents, Math::addExact);
			d.spendByMinute.keySet().removeIf(m -> m <= nowMinute - 60);
			markDirty.run();
		}

		private void record(String reason, long income, long expense, long now) {
			Data d = data.get();
			String key = reason == null || reason.isBlank() ? "unknown" : reason;
			long nowHour = Math.floorDiv(now, HOUR_MS);
			long[] hour = d.flowsByHour.computeIfAbsent(nowHour, h -> new HashMap<>()).computeIfAbsent(key, r -> new long[2]);
			hour[0] = Math.addExact(hour[0], income);
			hour[1] = Math.addExact(hour[1], expense);
			long[] total = d.totals.computeIfAbsent(key, r -> new long[2]);
			total[0] = Math.addExact(total[0], income);
			total[1] = Math.addExact(total[1], expense);
			d.flowsByHour.keySet().removeIf(h -> h <= nowHour - 25);
			markDirty.run();
		}
	}
}
