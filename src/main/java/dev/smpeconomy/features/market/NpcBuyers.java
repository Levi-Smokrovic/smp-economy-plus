package dev.smpeconomy.features.market;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;
import java.util.UUID;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.Hooks;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.PriceGuard;
import dev.smpeconomy.core.Treasury;

/**
 * NPC buyers. For each new player listing it is decided once whether an NPC buys it: only if price ≤ market value ×
 * random(0.85-1.15) (auction fever: up to × 1.25), ≤ {@link PriceGuard#maxNpcBuyPrice}, and the treasury could ever pay it
 * ({@code ≤ Treasury.maxSpendPerHour()} and ≤ per-seller budget). The purchase happens after 5-90 min, faster when cheaper, twice
 * as fast during fever. Everything is rechecked at purchase time. If only a budget is used up for now, it retries a few times and
 * then drops the plan. Payment comes from the treasury (capped, {@link Treasury#R_NPC_BUY}), the seller gets price minus AH tax.
 *
 * <p>Market value here is {@link ValueEngine#buyerUnitValue}: no enchantment bonuses or fixed collector values, whose vanilla
 * supply is unlimited. A player gets at most {@code buyerSellerHourShare} × hourly budget per rolling hour and {@code
 * buyerSellerDayShare} × hourly budget per 24 h, so a single farm cannot drain the treasury.
 */
public final class NpcBuyers {
	/** Markup on the random factor during auction fever (1.25 / 1.15). */
	static final double FEVER_SCALE = PriceGuard.NPC_BUY_MARKET_FACTOR_MAX / PriceGuard.NPC_BUY_MARKET_FACTOR;
	private static final int MAX_BUYS_PER_TICK = 5;
	private static final long MINUTE_MS = 60_000L;
	private static final long HOUR_MINUTES = 60;
	private static final long DAY_MINUTES = 24 * 60;
	/** Reason when an item is worth nothing to NPC buyers but something to players (enchantment, fixed value). */
	static final String REASON_PLAYERS_ONLY = "players only (enchantment/fixed value)";
	static final String REASON_SELLER_CAP = "above the NPC budget per player";

	public record Decision(boolean buy, String reason, boolean priceRelated, long delayMs) {
		static Decision no(String reason, boolean priceRelated) {
			return new Decision(false, reason, priceRelated, 0);
		}
	}

	static final MarketBridge.Payer TREASURY = new MarketBridge.Payer() {
		@Override
		public boolean withdraw(long cents) {
			return Treasury.tryWithdraw(cents, Treasury.R_NPC_BUY);
		}

		@Override
		public void refund(long cents) {
			Treasury.deposit(cents, Treasury.R_NPC_BUY);
		}
	};

	private static boolean lastFever;

	private NpcBuyers() {}

	/**
	 * Pure purchase decision (stack totals in cents).
	 *
	 * @param guardUnit  {@code PriceGuard.maxNpcBuyPrice(seller, item, unitValue, factor)} per unit
	 * @param random     rolled factor in [buyerRandomMin, buyerRandomMax], without fever
	 * @param jitter     random value in [0, 1) for the delay
	 */
	public static Decision decide(long price, int count, long unitValue, long guardUnit, long maxSpendPerHour, boolean fever, double random,
			double jitter, MarketConfig c) {
		if (count <= 0 || price <= 0) return Decision.no("invalid listing", false);
		if (unitValue <= 0) return Decision.no("no market value", false);
		if (price > maxSpendPerHour) return Decision.no("above the Treasury's hourly budget", false);
		long guardTotal = ValueEngine.saturatingMul(guardUnit, count);
		if (price > guardTotal) return Decision.no("above the PriceGuard cap", true);
		long threshold = threshold(unitValue, count, random, fever);
		if (price > threshold) return Decision.no("too expensive", true);
		double q = threshold <= 0 ? 1.0 : Math.min(1.0, (double) price / threshold);
		double minMs = c.buyerMinDelayMinutes * 60_000.0;
		double maxMs = c.buyerMaxDelayMinutes * 60_000.0;
		double delay = minMs + (maxMs - minMs) * q * q;
		delay *= 0.9 + 0.2 * Math.max(0, Math.min(1, jitter));
		delay = Math.max(minMs, Math.min(maxMs, delay));
		if (fever) delay /= 2;
		return new Decision(true, "ok", false, (long) delay);
	}

	/** Highest stack total by the random factor: floor(market value × factor (× 1.25/1.15 during fever) × count). */
	static long threshold(long unitValue, int count, double random, boolean fever) {
		double f = random * (fever ? FEVER_SCALE : 1.0);
		double v = (double) unitValue * f * count;
		return v >= Money.MAX ? Money.MAX : (long) Math.floor(v + 1e-9);
	}

	/** PriceGuard cap per unit for this seller (fever: market value factor 1.25). */
	static long guardUnit(UUID seller, MarketBridge.Listing l, long unitValue, boolean fever) {
		return PriceGuard.maxNpcBuyPrice(seller, l.item().getItem(), unitValue,
			fever ? PriceGuard.NPC_BUY_MARKET_FACTOR_MAX : PriceGuard.NPC_BUY_MARKET_FACTOR);
	}

	static boolean fever() {
		return Hooks.flag(Hooks.FLAG_AUCTION_FEVER);
	}

	static void consider(MarketBridge.Listing l, long now, Random rng) {
		consider(l, now, rng, fever());
	}

	static void consider(MarketBridge.Listing l, long now, Random rng, boolean fever) {
		MarketConfig c = MarketConfig.get();
		if (!c.npcBuyersEnabled || l.npc() || l.seller() == null) return;
		MarketData d = MarketData.get();
		if (d.plans.containsKey(l.id()) || d.rejected.containsKey(l.id())) return;
		double random = c.buyerRandomMin + (c.buyerRandomMax - c.buyerRandomMin) * rng.nextDouble();
		// No buy-back lock: items bought from NPC sellers, bargains included, may be resold to NPC buyers. Bounded by the treasury,
		// the per-seller budget and the bargain flip budget.
		OptionalLong unit = ValueEngine.buyerUnitValue(l.item());
		long u = unit.orElse(0);
		long guard = u > 0 ? guardUnit(l.seller(), l, u, fever) : 0;
		long budget = Treasury.maxSpendPerHour();
		Decision decision = decide(l.price(), l.count(), u, guard, budget, fever, random, rng.nextDouble(), c);
		if (unit.isEmpty() && ValueEngine.unitValue(l.item()).isPresent()) decision = Decision.no(REASON_PLAYERS_ONLY, false);
		else if (decision.buy() && l.price() > sellerMaxPrice(budget, c)) decision = Decision.no(REASON_SELLER_CAP, false);
		if (decision.buy()) {
			MarketData.BuyPlan p = new MarketData.BuyPlan();
			p.listingId = l.id();
			p.seller = l.seller();
			p.itemId = Items.id(l.item().getItem());
			p.count = l.count();
			p.price = l.price();
			p.random = random;
			p.createdAt = now;
			p.dueAt = now + decision.delayMs();
			p.fever = fever;
			d.plans.put(l.id(), p);
		} else {
			MarketData.Rejection r = new MarketData.Rejection();
			r.at = now;
			r.reason = decision.reason();
			r.fever = fever;
			r.priceRelated = decision.priceRelated();
			d.rejected.put(l.id(), r);
		}
		MarketData.markDirty();
	}

	static void forget(long listingId) {
		MarketData d = MarketData.get();
		boolean changed = d.plans.remove(listingId) != null;
		changed |= d.rejected.remove(listingId) != null;
		if (changed) MarketData.markDirty();
	}

	/**
	 * Sync with the auction house (startup, every few minutes): evaluate player listings without a decision, e.g. listed during a
	 * restart, delete plans and rejections of vanished listings, and clean old budget entries.
	 */
	static void sweep(MarketBridge bridge, long now, Random rng) {
		sweep(bridge, now, rng, false);
	}

	/** @param live real auction house: never evaluate test players' listings (see {@link MarketFeature#LIVE_EVENTS}) */
	static void sweep(MarketBridge bridge, long now, Random rng, boolean live) {
		MarketData d = MarketData.get();
		List<MarketBridge.Listing> active = bridge.activeListings();
		java.util.Set<Long> ids = new java.util.HashSet<>();
		for (MarketBridge.Listing l : active) ids.add(l.id());
		boolean changed = d.plans.keySet().removeIf(id -> !ids.contains(id));
		changed |= d.rejected.keySet().removeIf(id -> !ids.contains(id));
		long oldest = Math.floorDiv(now, MINUTE_MS) - DAY_MINUTES;
		for (Map<Long, Long> m : d.sellerPayouts.values()) changed |= m.keySet().removeIf(minute -> minute <= oldest);
		changed |= d.sellerPayouts.values().removeIf(Map::isEmpty);
		if (changed) MarketData.markDirty();
		for (MarketBridge.Listing l : active) {
			if (!l.npc() && !(live && MarketFeature.isTestPlayer(l.seller()))) consider(l, now, rng);
		}
	}

	/** Auction fever began: running plans twice as fast, price-related rejections rechecked once. */
	static void onFeverStart(MarketBridge bridge, long now, Random rng) {
		MarketData d = MarketData.get();
		for (MarketData.BuyPlan p : d.plans.values()) {
			if (p.fever) continue;
			p.dueAt = now + Math.max(0, p.dueAt - now) / 2;
			p.fever = true;
		}
		List<Long> retry = new ArrayList<>();
		d.rejected.forEach((id, r) -> {
			if (r.priceRelated && !r.fever) retry.add(id);
		});
		for (Long id : retry) {
			d.rejected.remove(id);
			Optional<MarketBridge.Listing> l = bridge.listing(id);
			if (l.isPresent() && !l.get().npc()) consider(l.get(), now, rng, true);
		}
		MarketData.markDirty();
	}

	static void tick(MarketBridge bridge, long now, Random rng) {
		boolean fever = fever();
		if (fever && !lastFever) onFeverStart(bridge, now, rng);
		lastFever = fever;
		MarketData d = MarketData.get();
		if (d.plans.isEmpty()) return;
		List<MarketData.BuyPlan> due = new ArrayList<>();
		for (MarketData.BuyPlan p : d.plans.values()) {
			if (p.dueAt <= now) due.add(p);
		}
		due.sort(Comparator.comparingLong(p -> p.dueAt));
		for (int i = 0; i < due.size() && i < MAX_BUYS_PER_TICK; i++) execute(bridge, due.get(i), now);
	}

	public enum Outcome {
		BOUGHT, RETRY, DROPPED
	}

	static Outcome execute(MarketBridge bridge, MarketData.BuyPlan p, long now) {
		MarketData d = MarketData.get();
		MarketConfig c = MarketConfig.get();
		Optional<MarketBridge.Listing> found = bridge.listing(p.listingId);
		if (found.isEmpty()) return drop(p, "listing gone");
		MarketBridge.Listing l = found.get();
		if (l.npc() || l.seller() == null || !l.seller().equals(p.seller) || l.price() != p.price || l.count() != p.count
			|| !Items.id(l.item().getItem()).equals(p.itemId)) return drop(p, "listing changed");
		if (!c.npcBuyersEnabled) return drop(p, "NPC buyers off");
		OptionalLong unit = ValueEngine.buyerUnitValue(l.item());
		if (unit.isEmpty()) return drop(p, "no market value anymore");
		boolean fever = fever();
		long guardTotal = ValueEngine.saturatingMul(guardUnit(l.seller(), l, unit.getAsLong(), fever), l.count());
		long threshold = threshold(unit.getAsLong(), l.count(), p.random, fever);
		if (l.price() > guardTotal || l.price() > threshold) return drop(p, "too expensive now");
		long budget = Treasury.maxSpendPerHour();
		if (l.price() > budget) return drop(p, "above the hourly budget");
		if (l.price() > sellerMaxPrice(budget, c)) return drop(p, REASON_SELLER_CAP);
		if (l.price() > sellerRoom(l.seller(), budget, now, c)) return retry(p, now, c, "seller's budget used up for now");
		if (l.price() > Treasury.allowance()) return retry(p, now, c, "hourly budget used up for now");
		MarketBridge.BuyStatus status;
		try {
			status = bridge.buyAsNpc(l.id(), unit.getAsLong(), TREASURY);
		} catch (RuntimeException e) {
			SmpEconomy.LOG.error("[SMPEco] market: NPC purchase of #{} failed", l.id(), e);
			return drop(p, "error");
		}
		switch (status) {
			case OK -> {
				d.plans.remove(p.listingId);
				d.npcBuys++;
				d.npcBuyCents = Math.min(Money.MAX, d.npcBuyCents + l.price());
				recordSellerPayout(l.seller(), l.price(), now);
				applyDrift(l, -1, now);
				MarketData.markDirty();
				SmpEconomy.LOG.info("[SMPEco] market: NPC bought #{} ({}x {}) from {} for {}", l.id(), l.count(), p.itemId, l.sellerName(),
					Money.format(l.price()));
				return Outcome.BOUGHT;
			}
			case PAYMENT_FAILED -> {
				return retry(p, now, c, "Treasury declined");
			}
			default -> {
				return drop(p, status.name());
			}
		}
	}

	private static Outcome drop(MarketData.BuyPlan p, String reason) {
		MarketData d = MarketData.get();
		d.plans.remove(p.listingId);
		MarketData.Rejection r = new MarketData.Rejection();
		r.at = System.currentTimeMillis();
		r.reason = reason;
		r.fever = true; // No recheck, never retry endlessly
		d.rejected.put(p.listingId, r);
		MarketData.markDirty();
		return Outcome.DROPPED;
	}

	private static Outcome retry(MarketData.BuyPlan p, long now, MarketConfig c, String reason) {
		if (p.retries >= c.buyerMaxRetries) return drop(p, reason + " (gave up)");
		p.retries++;
		p.dueAt = now + c.buyerRetryMinutes * 60_000L;
		MarketData.markDirty();
		return Outcome.RETRY;
	}

	/** {@code sign} -1: an NPC bought (factor falls). +1: a player bought from an NPC. */
	static void applyDrift(MarketBridge.Listing l, int sign, long now) {
		MarketConfig c = MarketConfig.get();
		var stack = l.item();
		double delta = sign * Drift.impact(stack.getCount(), stack.getMaxStackSize(), c.driftStepPerStack, c.driftMaxStepPerTrade);
		if (delta == 0) return;
		if (ValueEngine.value(stack.getItem()).isPresent()) Drift.apply(Drift.key(stack.getItem()), delta, now);
		if (sign < 0 && !c.buyerPaysEnchantments) return; // NPC buyers did not pay for the enchantments
		for (String id : ValueEngine.enchantmentIds(stack)) {
			if (c.enchantmentValues.containsKey(id)) Drift.apply(Drift.enchantKey(id), delta, now);
		}
	}

	static long sellerHourCap(long maxSpendPerHour, MarketConfig c) {
		return share(maxSpendPerHour, c.buyerSellerHourShare);
	}

	static long sellerDayCap(long maxSpendPerHour, MarketConfig c) {
		return share(maxSpendPerHour, c.buyerSellerDayShare);
	}

	/** Highest price of a single listing an NPC buyer could ever pay (treasury and per-seller budget). */
	static long sellerMaxPrice(long maxSpendPerHour, MarketConfig c) {
		return Math.max(0, Math.min(maxSpendPerHour, Math.min(sellerHourCap(maxSpendPerHour, c), sellerDayCap(maxSpendPerHour, c))));
	}

	static long sellerRoom(UUID seller, long maxSpendPerHour, long now, MarketConfig c) {
		long hour = sellerHourCap(maxSpendPerHour, c) - sellerPaid(seller, now, HOUR_MINUTES);
		long day = sellerDayCap(maxSpendPerHour, c) - sellerPaid(seller, now, DAY_MINUTES);
		return Math.max(0, Math.min(hour, day));
	}

	/**
	 * Paid by NPC buyers to {@code seller} in the last {@code minutes} minutes, including the minute of {@code now}. Later
	 * entries count too, so a clock set back gives no new budget.
	 */
	static long sellerPaid(UUID seller, long now, long minutes) {
		if (seller == null) return 0;
		Map<Long, Long> m = MarketData.get().sellerPayouts.get(seller);
		if (m == null) return 0;
		long from = Math.floorDiv(now, MINUTE_MS) - minutes;
		long sum = 0;
		for (Map.Entry<Long, Long> e : m.entrySet()) {
			if (e.getKey() > from) sum = Math.min(Money.MAX, sum + Math.max(0, e.getValue()));
		}
		return sum;
	}

	static void recordSellerPayout(UUID seller, long cents, long now) {
		if (seller == null || cents <= 0) return;
		long minute = Math.floorDiv(now, MINUTE_MS);
		Map<Long, Long> m = MarketData.get().sellerPayouts.computeIfAbsent(seller, k -> new java.util.HashMap<>());
		m.merge(minute, cents, (a, b) -> Math.min(Money.MAX, a + b));
		m.keySet().removeIf(k -> k <= minute - DAY_MINUTES);
		MarketData.markDirty();
	}

	static boolean resetSellerBudget(UUID seller) {
		boolean changed = MarketData.get().sellerPayouts.remove(seller) != null;
		if (changed) MarketData.markDirty();
		return changed;
	}

	private static long share(long budget, double share) {
		if (budget <= 0 || !(share > 0)) return 0;
		double v = Math.floor(budget * share + 1e-9);
		return v >= Money.MAX ? Money.MAX : (long) v;
	}
}
