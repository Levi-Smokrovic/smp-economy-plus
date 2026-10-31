package dev.smpeconomy.features.market;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import dev.smpeconomy.core.DataStore;

/** Persistent market state ({@code <world>/smpeconomy/market.json}): drift, planned NPC purchases, trader rotation. */
public final class MarketData {
	public static final class DriftEntry {
		public double factor = 1.0;
		public long at;

		public DriftEntry() {}

		DriftEntry(double factor, long at) {
			this.factor = factor;
			this.at = at;
		}
	}

	public static final class BuyPlan {
		public long listingId;
		public UUID seller;
		public String itemId = "";
		public int count;
		public long price;
		/** Rolled factor (buyerRandomMin..Max) without the fever markup. */
		public double random = 1.0;
		public long createdAt;
		public long dueAt;
		public int retries;
		/** Auction fever ran at or after planning; the delay was already halved. */
		public boolean fever;
	}

	public static final class Rejection {
		public long at;
		public String reason = "";
		/** Decided during auction fever, so the next fever start does not recheck it. */
		public boolean fever;
		/** Only price-related rejections are rechecked on fever start. */
		public boolean priceRelated;
	}

	/**
	 * A bargain in the flip budget ({@code bargainDailyBudget}): counts while listed and for 24 h after the sale. Expired or
	 * removed unsold bargains are deleted and return their potential.
	 */
	public static final class Bargain {
		public long createdAt;
		/** Flip potential at listing time in cents: highest system payment for the stack minus price. */
		public long potential;
		public long price;
		public String itemId = "";
		public int count;
		public boolean shown;
		/** Time of sale, 0 while still listed. */
		public long soldAt;
	}

	public Map<String, DriftEntry> drift = new HashMap<>();
	public Map<Long, BuyPlan> plans = new HashMap<>();
	public Map<Long, Rejection> rejected = new HashMap<>();
	public Map<Long, Bargain> bargains = new HashMap<>();
	/** Seller, then minute (epoch / 60 000), then cents paid by NPC buyers. Per-seller budget, kept for 24 h. */
	public Map<UUID, Map<Long, Long>> sellerPayouts = new HashMap<>();
	public int targetOffers;
	public long targetRolledAt;
	public long lastRefillAt;
	public long npcBuys;
	public long npcBuyCents;
	public long npcSales;
	public long npcSaleCents;
	public long bargainsCreated;
	public long bargainsSold;

	void fixNulls() {
		if (drift == null) drift = new HashMap<>();
		if (plans == null) plans = new HashMap<>();
		if (rejected == null) rejected = new HashMap<>();
		if (bargains == null) bargains = new HashMap<>();
		if (sellerPayouts == null) sellerPayouts = new HashMap<>();
		drift.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null || !Double.isFinite(e.getValue().factor) || e.getValue().factor <= 0);
		plans.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null || e.getValue().seller == null || e.getValue().price <= 0);
		plans.values().forEach(p -> {
			if (p.itemId == null) p.itemId = "";
			if (!Double.isFinite(p.random) || p.random <= 0) p.random = 1.0;
		});
		rejected.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null);
		rejected.values().forEach(r -> {
			if (r.reason == null) r.reason = "";
		});
		bargains.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null || e.getValue().potential < 0);
		bargains.values().forEach(b -> {
			if (b.itemId == null) b.itemId = "";
		});
		sellerPayouts.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null);
		sellerPayouts.values().forEach(m -> m.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null || e.getValue() <= 0));
		sellerPayouts.values().removeIf(Map::isEmpty);
	}

	static final DataStore<MarketData> STORE = new DataStore<>("market.json", MarketData.class, MarketData::new);

	static {
		STORE.onLoad(MarketData::fixNulls);
	}

	static MarketData get() {
		return STORE.get();
	}

	static void markDirty() {
		STORE.markDirty();
	}
}
