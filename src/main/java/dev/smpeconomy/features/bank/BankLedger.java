package dev.smpeconomy.features.bank;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.EconomyService.Result;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.TimeUtil;
import dev.smpeconomy.features.bank.BankData.Account;
import dev.smpeconomy.features.bank.BankData.Batch;
import dev.smpeconomy.features.bank.BankData.Claim;
import dev.smpeconomy.features.bank.BankData.Lot;

/**
 * Bank logic over arbitrary data with an injectable clock, so selftests can run on their own instance.
 * <p>Money: the balance sits on the player's shadow account in economy.json ({@link BankVault}). A deposit or
 * withdrawal is a single {@link EconomyService#transfer} within that file and interest is credited straight to the
 * shadow account, so a crash between two files or a restored bank.json cannot create or destroy money.
 * <p>Hold rule: withdrawals take from the newest lot, which makes the sum of lots up to T exactly the minimum balance
 * in [T, now]. Interest at midnight M counts lots with {@code t <= M - holdHours}.
 * <p>Interest: the amount due is computed once at midnight and stored as a {@link Batch}. It is cut, proportionally
 * for everyone, only when it exceeds the daily budget, which is a share of the treasury balance and not whatever is
 * left of the hourly budget. Installments run at most once a minute, as much as the source allows. At the {@code
 * payoutHours} deadline one last installment uses the whole available budget and the payout closes. Each installment
 * is committed to bank.json before paying, so a crash costs at most one installment and never pays twice.
 */
public final class BankLedger {
	public static final long HOUR_MS = 3_600_000L;
	public static final long STEP_MS = 60_000L;

	public interface InterestSource {
		long dailyBudget(long now);

		/**
		 * Cents payable right now (>= 0). {@code last} marks the final installment (deadline or admin payout), which
		 * may use the whole available budget.
		 */
		long available(long now, boolean last);

		/** Pays atomically to shadow account {@code account}; nothing changes on failure. */
		Result pay(UUID account, long cents, long now);
	}

	/**
	 * {@code due}: interest before any cut. {@code target}: cap from the daily budget. {@code paid}: actually
	 * credited. {@code budget}: daily budget, or for an admin payout the budget usable at that moment. {@code
	 * failed}: the source refused a payment.
	 */
	public record Payout(long reference, long cutoff, long due, long target, long paid, long budget, Map<UUID, Long> dueByPlayer,
		Map<UUID, Long> paidByPlayer, boolean failed) {
		public double factor() {
			return due <= 0 ? 1.0 : (double) paid / (double) due;
		}

		public int players() {
			return paidByPlayer.size();
		}

		/** Cut because the daily budget could not cover all the interest due. */
		public boolean treasuryShort() {
			return target < due;
		}
	}

	record Step(long available, long paid, boolean refused) {}

	private final Supplier<BankData> data;
	private final Runnable markDirty;
	private final BooleanSupplier commit;
	private final LongSupplier clock;
	private final Supplier<BankConfig> config;
	private final BankVault vault;

	/** @param commit writes bank.json immediately; if it returns false the installment is not paid */
	public BankLedger(Supplier<BankData> data, Runnable markDirty, BooleanSupplier commit, LongSupplier clock,
		Supplier<BankConfig> config, BankVault vault) {
		this.data = data;
		this.markDirty = markDirty;
		this.commit = commit;
		this.clock = clock;
		this.config = config;
		this.vault = vault;
	}

	public long now() {
		return clock.getAsLong();
	}

	public BankConfig config() {
		return config.get();
	}

	BankVault vault() {
		return vault;
	}

	private Map<UUID, Account> accounts() {
		return data.get().accounts;
	}

	public long balance(UUID id) {
		return vault.balance(id);
	}

	public Optional<Account> peek(UUID id) {
		return Optional.ofNullable(accounts().get(id));
	}

	public Optional<Batch> pending() {
		return Optional.ofNullable(data.get().pending);
	}

	/** Saturates at Long.MAX_VALUE. */
	public long total() {
		long sum = 0;
		for (UUID id : accounts().keySet()) sum = satAdd(sum, vault.balance(id));
		return sum;
	}

	private static long satAdd(long a, long b) {
		long r = a + b;
		return ((a ^ r) & (b ^ r)) < 0 ? Long.MAX_VALUE : r;
	}

	public int accountCount() {
		return accounts().size();
	}

	/** Balance held continuously since at most {@code cutoff}. */
	public long eligible(UUID id, long cutoff) {
		Account a = accounts().get(id);
		return a == null ? 0 : eligible(a, cutoff, vault.balance(id));
	}

	static long eligible(Account a, long cutoff, long balance) {
		long sum = 0;
		for (Lot l : a.lots) {
			if (l.t > cutoff) break;
			sum += l.a;
		}
		return Math.max(0, Math.min(sum, balance));
	}

	public long eligibleNow(UUID id) {
		return eligible(id, now() - config.get().holdMillis());
	}

	public long nextPayoutAt() {
		return nextMidnight(now());
	}

	public long eligibleAtNextPayout(UUID id) {
		return eligible(id, nextPayoutAt() - config.get().holdMillis());
	}

	public long interestFor(long eligible) {
		BankConfig cfg = config.get();
		if (!cfg.interestEnabled || eligible <= 0) return 0;
		return Math.max(0, Math.min(Money.percentOf(eligible, cfg.interestPercent), cfg.maxInterestPerDay));
	}

	public Result deposit(UUID player, long cents) {
		if (!Money.isValidAmount(cents)) return Result.INVALID_AMOUNT;
		if (!EconomyService.hasAccount(player) || EconomyService.isSystemAccount(player)) return Result.NO_ACCOUNT;
		if (vault.balance(player) > Money.MAX - cents) return Result.LIMIT_EXCEEDED;
		if (!EconomyService.has(player, cents)) return Result.INSUFFICIENT_FUNDS;
		UUID account = vault.ensure(player);
		if (account == null) return Result.NO_ACCOUNT;
		Result r = EconomyService.transfer(player, account, cents);
		if (!r.ok()) return r;
		addLot(accounts().computeIfAbsent(player, k -> new Account()), ceilHour(now()), cents);
		markDirty.run();
		return Result.OK;
	}

	public Result withdraw(UUID player, long cents) {
		if (!Money.isValidAmount(cents)) return Result.INVALID_AMOUNT;
		if (vault.balance(player) < cents) return Result.INSUFFICIENT_FUNDS;
		// the recipient needs an account: transfer won't create one, and creating one would hand out the starting
		// balance
		if (!EconomyService.hasAccount(player) || EconomyService.isSystemAccount(player)) return Result.NO_ACCOUNT;
		Result r = EconomyService.transfer(vault.id(player), player, cents);
		if (!r.ok()) return r;
		Account acc = accounts().get(player);
		if (acc != null) removeNewest(acc, cents);
		markDirty.run();
		return Result.OK;
	}

	/**
	 * Midnight check and installments, called every second. When a new day (server time zone) has begun since the
	 * last payout, creates exactly one payout for today 00:00, also late after a restart across midnight. Missed days
	 * are not paid retroactively. The very first call only records the date.
	 * @return payouts completed in this call, usually none
	 */
	public List<Payout> dailyTick(InterestSource source) {
		BankData d = data.get();
		long now = now();
		List<Payout> done = new ArrayList<>();
		LocalDate today = TimeUtil.dateOf(now);
		LocalDate last = parseDate(d.lastInterestDate);
		BankConfig cfg = config.get();
		if (last == null) {
			d.lastInterestDate = today.toString();
			markDirty.run();
		} else if (today.isAfter(last)) {
			// close a payout still running (started just before midnight after an outage) with a last installment
			// first
			if (d.pending != null) {
				if (cfg.interestEnabled) step(d.pending, source, now, true, true);
				done.add(closePending(d));
			}
			// date first, committed with the first installment before any payment: better no interest once than
			// paying twice
			d.lastInterestDate = today.toString();
			markDirty.run();
			long midnight = midnightOf(today);
			if (cfg.interestEnabled) {
				Batch b = newBatch(midnight, source, null, now);
				if (b.totalDue > 0) {
					b.deadline = now + cfg.payoutHours * HOUR_MS;
					b.nextStepAt = now;
					d.pending = b;
				} else {
					d.lastPayoutAt = midnight;
					d.lastPayoutDue = 0;
					d.lastPayoutPaid = 0;
					d.lastPayoutPlayers = 0;
				}
			}
			compact(midnight - cfg.holdMillis());
		}
		Batch p = d.pending;
		// due, or the clock was set back: don't wait forever
		if (p != null && (now >= p.nextStepAt || p.nextStepAt - now > STEP_MS)) {
			if (!cfg.interestEnabled) {
				done.add(closePending(d));
			} else {
				boolean lastStep = now >= p.deadline;
				step(p, source, now, lastStep, true);
				p.nextStepAt = now + STEP_MS;
				markDirty.run();
				if (lastStep || isComplete(p)) done.add(closePending(d));
			}
		}
		return done;
	}

	/**
	 * Admin and tests: pays interest for {@code reference} now in one installment using the whole available budget.
	 * Does not count as the regular midnight payout.
	 */
	public Payout payInterest(long reference, InterestSource source, Predicate<UUID> filter) {
		long now = now();
		Batch b = newBatch(reference, source, filter, now);
		if (b.totalDue == 0) return new Payout(reference, b.cutoff, 0, 0, 0, 0, Map.of(), Map.of(), false);
		b.deadline = now;
		Step s = step(b, source, now, true, false);
		return summarize(b, Math.min(b.budget, s.available()), s.refused());
	}

	Batch newBatch(long reference, InterestSource source, Predicate<UUID> filter, long now) {
		BankConfig cfg = config.get();
		Batch b = new Batch();
		b.reference = reference;
		b.cutoff = reference - cfg.holdMillis();
		b.createdAt = now;
		long totalDue = 0;
		for (Map.Entry<UUID, Account> e : accounts().entrySet()) {
			if (filter != null && !filter.test(e.getKey())) continue;
			long balance = vault.balance(e.getKey());
			long due = Math.min(interestFor(eligible(e.getValue(), b.cutoff, balance)), Money.MAX - balance);
			due = Math.min(due, Money.MAX - totalDue); // the sum stays exact (<= MAX) and shares never exceed the intended amount
			if (due <= 0) continue;
			b.claims.put(e.getKey(), new Claim(due));
			totalDue += due;
		}
		b.totalDue = totalDue;
		if (totalDue > 0) {
			b.budget = Math.max(0, source.dailyBudget(now));
			b.target = Math.min(totalDue, b.budget);
		}
		return b;
	}

	static long share(long due, long released, long totalDue) {
		if (totalDue <= 0 || released <= 0) return 0;
		if (released >= totalDue) return due;
		return BigInteger.valueOf(due).multiply(BigInteger.valueOf(released)).divide(BigInteger.valueOf(totalDue)).longValueExact();
	}

	/**
	 * One installment: releases up to {@code source.available} (at most up to {@code target}) and pays everyone their
	 * resulting share. With {@code durable} the new state is committed before paying; if that fails, nothing is paid.
	 */
	Step step(Batch b, InterestSource source, long now, boolean last, boolean durable) {
		long available = 0;
		long add = 0;
		if (b.released < b.target) {
			available = Math.max(0, source.available(now, last));
			add = Math.min(b.target - b.released, available);
		}
		long released = b.released + add;
		Map<UUID, Long> deltas = new LinkedHashMap<>();
		for (Map.Entry<UUID, Claim> e : b.claims.entrySet()) {
			Claim c = e.getValue();
			if (c.closed) continue;
			long delta = share(c.due, released, b.totalDue) - c.paid;
			if (delta > 0) deltas.put(e.getKey(), delta);
		}
		b.released = released;
		if (deltas.isEmpty()) return new Step(available, 0, false);
		// commit first: a crash in between costs at most this installment but never pays twice
		deltas.forEach((id, delta) -> b.claims.get(id).paid += delta);
		if (durable && !commit.getAsBoolean()) {
			deltas.forEach((id, delta) -> b.claims.get(id).paid -= delta);
			b.released -= add;
			SmpEconomy.LOG.warn("[SMPEco] Bank interest: bank.json not saved, installment of {} postponed", Money.format(add));
			return new Step(available, 0, true);
		}
		long paid = 0;
		boolean refused = false;
		for (Map.Entry<UUID, Long> e : deltas.entrySet()) {
			UUID id = e.getKey();
			long delta = e.getValue();
			Claim c = b.claims.get(id);
			UUID account = vault.ensure(id);
			Result r = account == null ? Result.NO_ACCOUNT : source.pay(account, delta, now);
			if (r.ok()) {
				credit(id, b.reference, delta);
				paid += delta;
				continue;
			}
			c.paid -= delta;
			refused = true;
			// hourly cap or treasury: retry next installment. Anything else (full bank account, no account) is
			// permanent
			if (r != Result.TREASURY_LIMIT && r != Result.INSUFFICIENT_FUNDS) c.closed = true;
			SmpEconomy.LOG.warn("[SMPEco] Bank interest: {} to {} not paid ({})", Money.format(delta), EconomyService.nameOrUnknown(id), r);
		}
		markDirty.run();
		return new Step(available, paid, refused);
	}

	/** Credits an installment as a lot at the cutoff, eligible from the next cutoff on. */
	private void credit(UUID id, long reference, long cents) {
		Account a = accounts().computeIfAbsent(id, k -> new Account());
		if (a.lastInterestAt != reference) {
			a.lastInterestAt = reference;
			a.lastInterest = 0;
		}
		a.lastInterest = satAdd(a.lastInterest, cents);
		a.totalInterest = satAdd(a.totalInterest, cents);
		addLot(a, reference, cents);
	}

	static boolean isComplete(Batch b) {
		if (b.released < b.target) return false;
		for (Claim c : b.claims.values()) {
			if (!c.closed && c.paid < share(c.due, b.released, b.totalDue)) return false;
		}
		return true;
	}

	private Payout closePending(BankData d) {
		Batch b = d.pending;
		Payout p = summarize(b, b.budget, false);
		d.pending = null;
		d.lastPayoutAt = b.reference;
		d.lastPayoutDue = p.due();
		d.lastPayoutPaid = p.paid();
		d.lastPayoutPlayers = p.players();
		markDirty.run();
		return p;
	}

	private Payout summarize(Batch b, long budget, boolean failed) {
		Map<UUID, Long> due = new LinkedHashMap<>();
		Map<UUID, Long> paid = new LinkedHashMap<>();
		long total = 0;
		for (Map.Entry<UUID, Claim> e : b.claims.entrySet()) {
			Claim c = e.getValue();
			due.put(e.getKey(), c.due);
			if (c.paid > 0) {
				paid.put(e.getKey(), c.paid);
				total += c.paid;
			}
			Account a = accounts().get(e.getKey());
			if (a != null && a.lastInterestAt != b.reference) {
				a.lastInterestAt = b.reference; // show "Last interest: $0" rather than a stale value
				a.lastInterest = 0;
			}
		}
		return new Payout(b.reference, b.cutoff, b.totalDue, b.target, total, budget, Collections.unmodifiableMap(due),
			Collections.unmodifiableMap(paid), failed);
	}

	/** Merges lots with {@code t <= cutoff} per account; they stay eligible for all later cutoffs. */
	public int compact(long cutoff) {
		int merged = 0;
		Batch pending = data.get().pending;
		var it = accounts().entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, Account> e = it.next();
			Account a = e.getValue();
			if (a.lots.isEmpty() && a.totalInterest == 0 && a.legacyBalance == null && vault.balance(e.getKey()) == 0
				&& (pending == null || !pending.claims.containsKey(e.getKey()))) {
				it.remove();
				merged++;
				continue;
			}
			int n = 0;
			long sum = 0;
			long maxT = Long.MIN_VALUE;
			for (Lot l : a.lots) {
				if (l.t > cutoff) break;
				n++;
				sum += l.a;
				maxT = l.t;
			}
			if (n <= 1) continue;
			a.lots.subList(0, n).clear();
			a.lots.add(0, new Lot(maxT, sum));
			merged += n - 1;
		}
		if (merged > 0) markDirty.run();
		return merged;
	}

	public boolean backdate(UUID player, long millis) {
		Account a = accounts().get(player);
		if (a == null || millis <= 0) return false;
		for (Lot l : a.lots) l.t = l.t < Long.MIN_VALUE + millis ? Long.MIN_VALUE : l.t - millis;
		markDirty.run();
		return true;
	}

	/** Test players and selftests only: the money on it disappears. */
	public boolean deleteAccount(UUID player) {
		boolean had = accounts().remove(player) != null;
		if (vault.exists(player)) {
			had = true;
			vault.delete(player);
		}
		Batch pending = data.get().pending;
		Claim c = pending == null ? null : pending.claims.get(player);
		if (c != null) c.closed = true;
		if (had) markDirty.run();
		return had;
	}

	static void addLot(Account a, long t, long amount) {
		List<Lot> lots = a.lots;
		int i = lots.size();
		while (i > 0 && lots.get(i - 1).t > t) i--;
		if (i > 0 && lots.get(i - 1).t == t) {
			Lot l = lots.get(i - 1);
			l.a = Math.addExact(l.a, amount);
			return;
		}
		lots.add(i, new Lot(t, amount));
	}

	static void removeNewest(Account a, long amount) {
		long rest = amount;
		for (int i = a.lots.size() - 1; i >= 0 && rest > 0; i--) {
			Lot l = a.lots.get(i);
			long take = Math.min(l.a, rest);
			l.a -= take;
			rest -= take;
			if (l.a == 0) a.lots.remove(i);
		}
	}

	/**
	 * On load of bank.json (economy.json is already loaded): moves a legacy balance to the shadow account, registers
	 * existing shadow accounts to keep them out of /baltop, creates missing entries and aligns lots with the
	 * authoritative balance. Only that one-time legacy move changes money.
	 * @param candidates further players whose shadow account may exist
	 * @return number of corrections
	 */
	static int reconcile(BankData d, long now, BankVault vault, Iterable<UUID> candidates) {
		int fixed = 0;
		if (d.accounts == null) {
			d.accounts = new HashMap<>();
			fixed++;
		}
		for (Map.Entry<UUID, Account> e : d.accounts.entrySet()) {
			Account a = e.getValue();
			if (e.getKey() == null || a == null || a.legacyBalance == null) continue;
			UUID player = e.getKey();
			long legacy = Math.max(0, Math.min(Money.MAX, a.legacyBalance));
			if (vault.exists(player)) {
				// already moved (the shadow account is in economy.json): drop the legacy value, never double it
				a.legacyBalance = null;
				fixed++;
			} else if (legacy > 0) {
				UUID account = vault.ensure(player);
				Result r = account == null ? Result.NO_ACCOUNT : EconomyService.deposit(account, legacy);
				if (r.ok()) {
					SmpEconomy.LOG.info("[SMPEco] bank.json: legacy balance {} of {} moved to the bank account in economy.json",
						Money.format(legacy), EconomyService.nameOrUnknown(player));
				} else {
					SmpEconomy.LOG.error("[SMPEco] bank.json: legacy balance {} of {} not moved ({})", Money.format(legacy),
						EconomyService.nameOrUnknown(player), r);
				}
				// kept until the shadow account is in economy.json on the next load, which drops it above
				fixed++;
			} else {
				a.legacyBalance = null;
				fixed++;
			}
		}
		Set<UUID> players = new LinkedHashSet<>(d.accounts.keySet());
		for (UUID id : candidates) players.add(id);
		players.remove(null);
		for (UUID player : players) {
			if (!vault.registerIfExists(player)) continue;
			if (!d.accounts.containsKey(player) && vault.balance(player) > 0) {
				d.accounts.put(player, new Account()); // stale bank.json: a lot at "now" loses eligibility rather than inventing it
				fixed++;
			}
		}
		return fixed + repair(d, now, vault::balance);
	}

	/**
	 * Repairs loaded data. The balance is authoritative: excess lots are trimmed from the newest end and a missing
	 * amount becomes a lot at "now", which would rather lose eligibility than invent it. Never changes money.
	 * @return number of repaired accounts or fields
	 */
	public static int repair(BankData d, long now, ToLongFunction<UUID> balanceOf) {
		int fixed = 0;
		if (d.accounts == null) {
			d.accounts = new HashMap<>();
			fixed++;
		}
		int before = d.accounts.size();
		d.accounts.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null);
		fixed += before - d.accounts.size();
		for (Map.Entry<UUID, Account> e : d.accounts.entrySet()) {
			Account a = e.getValue();
			long balance = Math.max(0, Math.min(Money.MAX, balanceOf.applyAsLong(e.getKey())));
			boolean changed = false;
			if (a.lots == null) {
				a.lots = new ArrayList<>();
				changed = true;
			}
			int n = a.lots.size();
			a.lots.removeIf(l -> l == null || l.a <= 0 || l.a > Money.MAX);
			List<Lot> sorted = new ArrayList<>(a.lots);
			sorted.sort(Comparator.comparingLong(l -> l.t));
			List<Lot> merged = new ArrayList<>();
			for (Lot l : sorted) {
				Lot last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
				if (last != null && last.t == l.t) last.a = Math.min(Money.MAX, last.a + l.a);
				else merged.add(new Lot(l.t, l.a));
			}
			if (merged.size() != n || !sameOrder(a.lots, merged)) changed = true;
			a.lots = merged;
			long sum = 0;
			for (Lot l : a.lots) sum = Math.min(Long.MAX_VALUE / 2, sum + l.a);
			if (sum > balance) {
				removeNewest(a, sum - balance);
				changed = true;
			} else if (sum < balance) {
				addLot(a, ceilHour(now), balance - sum);
				changed = true;
			}
			if (a.lastInterest < 0 || a.totalInterest < 0) {
				a.lastInterest = Math.max(0, a.lastInterest);
				a.totalInterest = Math.max(0, a.totalInterest);
				changed = true;
			}
			if (changed) fixed++;
		}
		return fixed + repairBatch(d);
	}

	/** Discards an implausible payout: better no interest than too much. */
	private static int repairBatch(BankData d) {
		Batch b = d.pending;
		if (b == null) return 0;
		int fixed = 0;
		if (b.claims == null) {
			b.claims = new LinkedHashMap<>();
			fixed++;
		}
		int before = b.claims.size();
		b.claims.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null || e.getValue().due <= 0 || e.getValue().due > Money.MAX);
		fixed += before - b.claims.size();
		long total = 0;
		for (Claim c : b.claims.values()) {
			if (c.paid < 0 || c.paid > c.due) {
				c.paid = Math.max(0, Math.min(c.due, c.paid));
				fixed++;
			}
			total = Math.min(Money.MAX, total + c.due);
		}
		if (b.claims.isEmpty()) {
			d.pending = null;
			return fixed + 1;
		}
		if (b.totalDue != total) {
			b.totalDue = total;
			fixed++;
		}
		long target = Math.max(0, Math.min(b.target, Math.min(total, Math.max(0, b.budget))));
		long released = Math.max(0, Math.min(b.released, target));
		if (target != b.target || released != b.released) {
			b.target = target;
			b.released = released;
			fixed++;
		}
		return fixed;
	}

	private static boolean sameOrder(List<Lot> a, List<Lot> b) {
		if (a.size() != b.size()) return false;
		for (int i = 0; i < a.size(); i++) {
			if (a.get(i).t != b.get(i).t || a.get(i).a != b.get(i).a) return false;
		}
		return true;
	}

	/**
	 * Rounds up to the full hour in the server time zone, so lots and midnight cutoffs share a grid even in zones
	 * with half-hour or quarter-hour offsets.
	 */
	public static long ceilHour(long t) {
		long offset = TimeUtil.zone().getRules().getOffset(java.time.Instant.ofEpochMilli(t)).getTotalSeconds() * 1000L;
		return Math.ceilDiv(t + offset, HOUR_MS) * HOUR_MS - offset;
	}

	public static long midnightOf(LocalDate d) {
		return d.atStartOfDay(TimeUtil.zone()).toInstant().toEpochMilli();
	}

	public static long nextMidnight(long now) {
		return midnightOf(TimeUtil.dateOf(now).plusDays(1));
	}

	static LocalDate parseDate(String s) {
		if (s == null || s.isBlank()) return null;
		try {
			return LocalDate.parse(s.trim());
		} catch (DateTimeParseException e) {
			return null;
		}
	}

	/**
	 * Amount from player input relative to {@code base} (cash for deposits, bank balance for withdrawals): {@code
	 * all}/{@code max}, a percentage such as {@code 12.5%} (0 &lt; p ≤ 100, rounded down), or an amount
	 * as in {@link Money#parse}.
	 * @return cents; {@code -1} for invalid input; {@code 0} for relative input when nothing is available
	 */
	public static long parseAmount(String input, long base) {
		if (input == null) return -1;
		String s = input.trim().toLowerCase(Locale.ROOT);
		if (s.isEmpty()) return -1;
		long b = Math.max(0, base);
		switch (s) {
			case "all", "max" -> {
				return b;
			}
			default -> {
			}
		}
		if (s.endsWith("%")) {
			String num = s.substring(0, s.length() - 1).trim().replace(',', '.');
			if (!num.matches("[0-9]{1,3}(\\.[0-9]{1,2})?")) return -1;
			BigDecimal pct = new BigDecimal(num);
			if (pct.signum() <= 0 || pct.compareTo(BigDecimal.valueOf(100)) > 0) return -1;
			return BigDecimal.valueOf(b).multiply(pct).movePointLeft(2).setScale(0, RoundingMode.FLOOR).longValueExact();
		}
		OptionalLong v = Money.parse(s);
		return v.isPresent() ? v.getAsLong() : -1;
	}
}
