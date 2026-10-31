package dev.smpeconomy.features.bounty;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.EconomyService.Result;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Treasury;

/**
 * Bounty logic on arbitrary data and an arbitrary escrow account (live: {@link Bounties#LIVE}; selftests: own
 * data and a test account). Money only moves through {@link EconomyService#transfer} and {@link
 * Treasury#collectFrom}, so none is ever created: every booking first checks that escrow holds exactly the
 * bounty total ({@link #escrowDifference}). Any mismatch, shortfall or surplus, and any restore of
 * bounties.json from backup locks everything ({@link #lock}, persisted) until an admin has checked and
 * repaired the data and called {@link #unlock}. Server thread only.
 */
public final class BountyLedger {
	public enum PlaceStatus {
		OK, INVALID_AMOUNT, BELOW_MIN, SELF, INVALID_TARGET, INSUFFICIENT_FUNDS, LIMIT_EXCEEDED, NO_ACCOUNT,
		/** Locked ({@link #isLocked}): nothing charged until an admin unlocks. */
		LOCKED;

		public boolean ok() {
			return this == OK;
		}
	}

	/**
	 * {@code amount} is this contribution, {@code contribution} the player's total after it, {@code total}
	 * the bounty after it.
	 */
	public record Placement(PlaceStatus status, long amount, long contribution, long total) {}

	public enum ClaimStatus {
		PAID,
		NONE,
		SELF,
		/** Locked ({@link #isLocked}): nothing paid, the bounty stays. */
		LOCKED,
		/** Crediting the killer failed (no account, balance cap): nothing paid, the bounty stays. */
		PAYOUT_FAILED;

		public boolean paid() {
			return this == PAID;
		}
	}

	/** {@code net} goes to the killer, {@code fee} to the treasury; {@code contributions} is who paid. */
	public record Payout(ClaimStatus status, long total, long fee, long net, Map<UUID, Long> contributions) {
		static Payout none(ClaimStatus status) {
			return new Payout(status, 0, 0, 0, Map.of());
		}
	}

	public record Entry(UUID target, long total, int contributors, UUID topContributor, long topAmount, long updatedAt) {}

	private final Supplier<BountyData> data;
	private final Runnable markDirty;
	private final UUID escrow;
	private final Supplier<BountyConfig> config;

	public BountyLedger(Supplier<BountyData> data, Runnable markDirty, UUID escrow, Supplier<BountyConfig> config) {
		this.data = data;
		this.markDirty = markDirty;
		this.escrow = escrow;
		this.config = config;
	}

	public UUID escrow() {
		return escrow;
	}

	/**
	 * Moves {@code cents} atomically from contributor to escrow and records the contribution. Any failure
	 * leaves everything unchanged.
	 */
	public Placement place(UUID contributor, UUID target, long cents, long now) {
		BountyData.Bounty existing = data.get().bounties.get(target);
		long current = existing == null ? 0 : existing.total();
		long own = existing == null ? 0 : existing.contributions.getOrDefault(contributor, 0L);
		PlaceStatus rule = rules(contributor, target, cents, current);
		if (rule != PlaceStatus.OK) return new Placement(rule, cents, own, current);
		// Payouts are locked on a mismatch, so take no more money into escrow either.
		if (!open("contribution")) return new Placement(PlaceStatus.LOCKED, cents, own, current);
		Result r = EconomyService.transfer(contributor, escrow, cents);
		if (!r.ok()) {
			PlaceStatus status = switch (r) {
				case INSUFFICIENT_FUNDS -> PlaceStatus.INSUFFICIENT_FUNDS;
				case LIMIT_EXCEEDED -> PlaceStatus.LIMIT_EXCEEDED;
				case NO_ACCOUNT -> PlaceStatus.NO_ACCOUNT;
				case SAME_ACCOUNT -> PlaceStatus.INVALID_TARGET;
				default -> PlaceStatus.INVALID_AMOUNT;
			};
			if (status == PlaceStatus.NO_ACCOUNT) SmpEconomy.LOG.error("[SMPEco] Bounty: escrow account {} missing", escrow);
			return new Placement(status, cents, own, current);
		}
		BountyData.Bounty b = existing;
		if (b == null) {
			b = new BountyData.Bounty();
			b.createdAt = now;
			data.get().bounties.put(target, b);
		}
		long contribution = b.contributions.merge(contributor, cents, Long::sum); // <= total <= maxTotal <= MAX, checked above
		b.updatedAt = now;
		markDirty.run();
		return new Placement(PlaceStatus.OK, cents, contribution, current + cents);
	}

	/**
	 * Dry run for the confirmation dialog: same rules in the same order as {@link #place}, plus lock or
	 * escrow mismatch, contributor balance and room on the escrow account. Changes nothing, not even locking
	 * on a mismatch; {@link #place} does that when it rechecks on confirm.
	 */
	public PlaceStatus check(UUID contributor, UUID target, long cents) {
		BountyData.Bounty existing = data.get().bounties.get(target);
		PlaceStatus rule = rules(contributor, target, cents, existing == null ? 0 : existing.total());
		if (rule != PlaceStatus.OK) return rule;
		if (isLocked() || escrowDifference() != 0) return PlaceStatus.LOCKED;
		// Same order as EconomyService.transfer: target account, funds, target balance cap
		if (!EconomyService.hasAccount(escrow)) return PlaceStatus.NO_ACCOUNT;
		if (EconomyService.balance(contributor) < cents) return PlaceStatus.INSUFFICIENT_FUNDS;
		if (escrowBalance() > Money.MAX - cents) return PlaceStatus.LIMIT_EXCEEDED;
		return PlaceStatus.OK;
	}

	/**
	 * Rules without money or lock, shared by {@link #place} and {@link #check}. {@code current} is the bounty
	 * before this contribution.
	 */
	private PlaceStatus rules(UUID contributor, UUID target, long cents, long current) {
		BountyConfig cfg = config.get();
		if (!Money.isValidAmount(cents)) return PlaceStatus.INVALID_AMOUNT;
		if (contributor.equals(target)) return PlaceStatus.SELF;
		if (target.equals(escrow) || contributor.equals(escrow) || EconomyService.isSystemAccount(target)) return PlaceStatus.INVALID_TARGET;
		if (cents < cfg.minAmount) return PlaceStatus.BELOW_MIN;
		if (current > cfg.maxTotal() - cents) return PlaceStatus.LIMIT_EXCEEDED;
		return PlaceStatus.OK;
	}

	/**
	 * Pays the whole bounty minus the fee to {@code killer}, exactly once, and deletes it. Suicide pays
	 * nothing. If the credit fails, everything stays as it was.
	 */
	public Payout claim(UUID killer, String killerName, UUID victim, String victimName, long now) {
		if (killer == null || victim == null) return Payout.none(ClaimStatus.NONE);
		if (killer.equals(victim)) return Payout.none(ClaimStatus.SELF);
		BountyData d = data.get();
		BountyData.Bounty b = d.bounties.get(victim);
		if (b == null) return Payout.none(ClaimStatus.NONE);
		long total = b.total();
		if (total <= 0) {
			d.bounties.remove(victim);
			markDirty.run();
			return Payout.none(ClaimStatus.NONE);
		}
		// The global invariant "escrow == total", not just "escrow >= this bounty" or "no shortfall":
		// otherwise a stale bounty (crash between economy.json and bounties.json, .bak restore) pays out with
		// other contributors' money, even when a lost contribution hides the shortfall as surplus.
		if (!open("payout on " + victimName)) return Payout.none(ClaimStatus.LOCKED);
		long fee = Math.min(total, Money.percentOf(total, config.get().feePercent));
		long net = total - fee;
		if (net > 0) {
			Result r = EconomyService.transfer(escrow, killer, net);
			if (!r.ok()) {
				SmpEconomy.LOG.warn("[SMPEco] Bounty on {}: payout to {} failed ({}), bounty stays", victimName, killerName, r);
				return Payout.none(ClaimStatus.PAYOUT_FAILED);
			}
		}
		if (fee > 0) {
			Result r = Treasury.collectFrom(escrow, fee, Treasury.R_BOUNTY_FEE);
			if (!r.ok()) {
				// Treasury full (Money.MAX) or similar: the fee still leaves the game as a money sink and
				// never stays on the escrow account
				Result w = EconomyService.withdraw(escrow, fee);
				SmpEconomy.LOG.warn("[SMPEco] Bounty fee {} not paid to the treasury ({}), destroyed instead: {}", Money.format(fee), r, w);
			}
		}
		Map<UUID, Long> contributions = new LinkedHashMap<>(b.contributions);
		d.bounties.remove(victim);
		addHistory(d, killer, killerName, victim, victimName, total, fee, now);
		markDirty.run();
		return new Payout(ClaimStatus.PAID, total, fee, net, contributions);
	}

	private void addHistory(BountyData d, UUID killer, String killerName, UUID victim, String victimName, long total, long fee, long now) {
		int max = config.get().historySize;
		if (max <= 0) {
			d.history.clear();
			return;
		}
		BountyData.Claim c = new BountyData.Claim();
		c.killer = killer;
		c.killerName = killerName == null ? "" : killerName;
		c.victim = victim;
		c.victimName = victimName == null ? "" : victimName;
		c.total = total;
		c.fee = fee;
		c.at = now;
		d.history.add(0, c);
		while (d.history.size() > max) d.history.remove(d.history.size() - 1);
	}

	/**
	 * Refunds all contributions on {@code target} and deletes the bounty. Contributions whose refund fails
	 * (no account, balance cap) stay. Refunds nothing while locked.
	 *
	 * @return refunded amount per contributor
	 */
	public Map<UUID, Long> refundAll(UUID target) {
		BountyData d = data.get();
		BountyData.Bounty b = d.bounties.get(target);
		Map<UUID, Long> refunded = new LinkedHashMap<>();
		if (b == null) return refunded;
		if (!b.contributions.isEmpty() && !open("refund on " + target)) return refunded;
		for (Map.Entry<UUID, Long> e : List.copyOf(b.contributions.entrySet())) {
			if (refund(target, e.getKey(), e.getValue())) {
				b.contributions.remove(e.getKey());
				refunded.put(e.getKey(), e.getValue());
			}
		}
		if (b.contributions.isEmpty()) d.bounties.remove(target);
		markDirty.run();
		return refunded;
	}

	/**
	 * Refunds everything from one contributor across all targets and returns the total. Refunds nothing while
	 * locked.
	 */
	public long refundContributor(UUID contributor) {
		BountyData d = data.get();
		long sum = 0;
		if (contributedBy(contributor).isEmpty() || !open("refund to " + contributor)) return sum;
		for (Map.Entry<UUID, BountyData.Bounty> e : List.copyOf(d.bounties.entrySet())) {
			Long amount = e.getValue().contributions.get(contributor);
			if (amount == null) continue;
			if (refund(e.getKey(), contributor, amount)) {
				e.getValue().contributions.remove(contributor);
				sum += amount;
				if (e.getValue().contributions.isEmpty()) d.bounties.remove(e.getKey());
			}
		}
		if (sum > 0) markDirty.run();
		return sum;
	}

	private boolean refund(UUID target, UUID contributor, long amount) {
		if (amount <= 0) return true;
		Result r = EconomyService.transfer(escrow, contributor, amount);
		if (!r.ok()) SmpEconomy.LOG.warn("[SMPEco] Bounty on {}: refund {} to {} failed ({})", target, Money.format(amount), contributor, r);
		return r.ok();
	}

	public enum UnlockStatus {
		OK, NOT_LOCKED,
		/** Escrow differs from the bounty total: repair first ({@link #drop}, {@link #releaseSurplus}). */
		MISMATCH
	}

	/**
	 * Balance minus bounty total: {@code > 0} surplus, {@code < 0} shortfall. Any difference means
	 * economy.json and bounties.json disagree (crash between saving the two, backup restore, admin edit on
	 * the escrow account). A surplus is as suspicious as a shortfall: it can hide a stale, already paid
	 * bounty.
	 */
	public long escrowDifference() {
		// Balance in [0, Money.MAX], total in [0, Long.MAX_VALUE] (saturating), so no overflow
		return escrowBalance() - totalEscrowed();
	}

	public long escrowDeficit() {
		return Math.max(0, -escrowDifference());
	}

	public long escrowSurplus() {
		return Math.max(0, escrowDifference());
	}

	public boolean isLocked() {
		return data.get().locked;
	}

	public String lockReason() {
		return data.get().lockReason;
	}

	public long lockedAt() {
		return data.get().lockedAt;
	}

	/** Persisted in bounties.json. If already locked, only appends a new reason. */
	public void lock(String reason, long now) {
		BountyData d = data.get();
		String r = reason == null || reason.isBlank() ? "unknown" : reason;
		if (!d.locked) {
			d.locked = true;
			d.lockReason = r;
			d.lockedAt = now;
		} else if (!d.lockReason.contains(r)) {
			d.lockReason = d.lockReason.isEmpty() ? r : d.lockReason + "; " + r;
		} else {
			return;
		}
		markDirty.run();
		SmpEconomy.LOG.error("[SMPEco] Bounties LOCKED ({}): escrow account {} holds {}, bounty total {}. Admin: /smpeco bounty check",
			r, EconomyService.nameOrUnknown(escrow), Money.format(escrowBalance()), Money.format(totalEscrowed()));
	}

	/**
	 * Lifts the lock only if escrow now holds exactly the bounty total. Confirms that the bounties were
	 * checked (stale ones dropped with {@link #drop}, lost stakes refunded with {@link #releaseSurplus}).
	 */
	public UnlockStatus unlock() {
		BountyData d = data.get();
		if (!d.locked) return UnlockStatus.NOT_LOCKED;
		if (escrowDifference() != 0) return UnlockStatus.MISMATCH;
		SmpEconomy.LOG.warn("[SMPEco] Bounty lock lifted (was: {}), escrow {} = bounty total", d.lockReason, Money.format(escrowBalance()));
		d.locked = false;
		d.lockReason = "";
		d.lockedAt = 0;
		markDirty.run();
		return UnlockStatus.OK;
	}

	/**
	 * Locks on any mismatch and when bounties.json came from backup, since all changes since the last start
	 * are then missing, even if the totals happen to match.
	 *
	 * @return whether the ledger is locked afterwards, including from an earlier lock not yet lifted
	 */
	public boolean startupCheck(boolean restoredFromBackup, long now) {
		if (restoredFromBackup) lock("bounties.json restored from backup", now);
		long diff = escrowDifference();
		if (diff != 0) lock(mismatchReason(diff), now);
		return isLocked();
	}

	private static String mismatchReason(long diff) {
		return diff < 0 ? "escrow deficit " + Money.format(-diff) : "escrow surplus " + Money.format(diff);
	}

	/**
	 * Clear only if not locked and escrow holds exactly the bounty total. A mismatch locks right away
	 * (persisted) rather than give one contributor's money to another bounty.
	 */
	private boolean open(String action) {
		long diff = escrowDifference();
		if (diff != 0) lock(mismatchReason(diff), System.currentTimeMillis());
		if (!isLocked()) return true;
		SmpEconomy.LOG.error("[SMPEco] Bounty {} rejected ({}): locked ({}). Admin: /smpeco bounty check", action,
			EconomyService.nameOrUnknown(escrow), lockReason());
		return false;
	}

	/**
	 * Deletes the bounty without moving money, e.g. one already paid that reappeared after a crash or backup
	 * restore. The money stays in escrow and the lock stays.
	 *
	 * @return the deleted contributions, empty if there was no bounty
	 */
	public Map<UUID, Long> drop(UUID target) {
		BountyData.Bounty b = data.get().bounties.remove(target);
		if (b == null) return Map.of();
		markDirty.run();
		return new LinkedHashMap<>(b.contributions);
	}

	/**
	 * Moves at most the escrow surplus (money with no bounty entry, e.g. a stake lost to a backup restore) to
	 * {@code to}: the contributor according to the log, or the treasury ({@link Treasury#ID}). Money behind
	 * real bounties stays untouched and the lock stays.
	 *
	 * @return {@code INSUFFICIENT_FUNDS} if {@code cents} exceeds the surplus, otherwise the transfer result
	 */
	public Result releaseSurplus(UUID to, long cents) {
		if (!Money.isValidAmount(cents)) return Result.INVALID_AMOUNT;
		if (to == null || to.equals(escrow)) return Result.SAME_ACCOUNT;
		if (cents > escrowSurplus()) return Result.INSUFFICIENT_FUNDS;
		Result r = to.equals(Treasury.ID) ? Treasury.collectFrom(escrow, cents, Treasury.R_ADMIN) : EconomyService.transfer(escrow, to, cents);
		if (r.ok()) SmpEconomy.LOG.warn("[SMPEco] Bounty escrow: surplus {} moved to {}", Money.format(cents), EconomyService.nameOrUnknown(to));
		return r;
	}

	public long total(UUID target) {
		BountyData.Bounty b = data.get().bounties.get(target);
		return b == null ? 0 : b.total();
	}

	/** Copy, in order of first contribution. */
	public Map<UUID, Long> contributions(UUID target) {
		BountyData.Bounty b = data.get().bounties.get(target);
		return b == null ? Map.of() : new LinkedHashMap<>(b.contributions);
	}

	/** Target to own contribution, largest first. */
	public Map<UUID, Long> contributedBy(UUID contributor) {
		List<Map.Entry<UUID, Long>> list = new ArrayList<>();
		data.get().bounties.forEach((target, b) -> {
			Long v = b.contributions.get(contributor);
			if (v != null && v > 0) list.add(Map.entry(target, v));
		});
		list.sort(Map.Entry.<UUID, Long>comparingByValue().reversed());
		Map<UUID, Long> result = new LinkedHashMap<>();
		for (Map.Entry<UUID, Long> e : list) result.put(e.getKey(), e.getValue());
		return result;
	}

	/** Highest first; ties go to the most recently raised. */
	public List<Entry> top() {
		List<Entry> list = new ArrayList<>();
		data.get().bounties.forEach((target, b) -> {
			long total = b.total();
			if (total <= 0) return;
			Map.Entry<UUID, Long> best = b.topContributor();
			list.add(new Entry(target, total, b.contributions.size(), best == null ? null : best.getKey(), best == null ? 0 : best.getValue(), b.updatedAt));
		});
		list.sort(Comparator.comparingLong(Entry::total).reversed().thenComparing(Comparator.comparingLong(Entry::updatedAt).reversed())
			.thenComparing(e -> e.target().toString()));
		return list;
	}

	/** What the escrow account should hold; saturating. */
	public long totalEscrowed() {
		long sum = 0;
		for (BountyData.Bounty b : data.get().bounties.values()) {
			long t = b.total();
			sum = t > Long.MAX_VALUE - sum ? Long.MAX_VALUE : sum + t;
		}
		return sum;
	}

	public long escrowBalance() {
		return EconomyService.balance(escrow);
	}

	/** Newest first, copy. */
	public List<BountyData.Claim> history() {
		return List.copyOf(data.get().history);
	}
}
