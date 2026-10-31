package dev.smpeconomy.features.bounty;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import dev.smpeconomy.SmpEconomy;

/**
 * Contents of {@code bounties.json}. The money itself is on system account {@link Bounties#ESCROW_ID} in
 * {@code economy.json}; this only records who owns it.
 */
public final class BountyData {
	/** Target UUID to bounty. */
	public Map<UUID, Bounty> bounties = new HashMap<>();
	/** Newest first. */
	public List<Claim> history = new ArrayList<>();
	/**
	 * Blocks contributions, payouts and refunds until an admin runs {@code /smpeco bounty unlock}. Set on any
	 * mismatch between the escrow account and the bounty total, and when bounties.json was restored from
	 * backup. See {@link BountyLedger#lock}.
	 */
	public boolean locked;
	public String lockReason = "";
	/** Epoch ms. */
	public long lockedAt;

	/** Contributions per supporter in cents, each above 0. */
	public static final class Bounty {
		public Map<UUID, Long> contributions = new LinkedHashMap<>();
		public long createdAt;
		public long updatedAt;

		/**
		 * {@link BountyLedger#place} keeps this at or below Money.MAX. Hand-edited data saturates at {@code
		 * Long.MAX_VALUE}, so a payout fails the escrow check instead of creating money.
		 */
		public long total() {
			long sum = 0;
			for (Long v : contributions.values()) {
				if (v != null && v > 0) sum = v > Long.MAX_VALUE - sum ? Long.MAX_VALUE : sum + v;
			}
			return sum;
		}

		public Map.Entry<UUID, Long> topContributor() {
			Map.Entry<UUID, Long> best = null;
			for (Map.Entry<UUID, Long> e : contributions.entrySet()) {
				if (best == null || e.getValue() > best.getValue()) best = e;
			}
			return best;
		}
	}

	public static final class Claim {
		public UUID killer;
		public String killerName = "";
		public UUID victim;
		public String victimName = "";
		public long total;
		public long fee;
		public long at;
	}

	/** Repairs nulls and invalid entries, logging loudly: contributions of 0 or less must never exist. */
	void fixNulls(String source) {
		if (bounties == null) bounties = new HashMap<>();
		if (history == null) history = new ArrayList<>();
		if (lockReason == null) lockReason = "";
		history.removeIf(c -> c == null);
		bounties.entrySet().removeIf(e -> {
			if (e.getKey() == null || e.getValue() == null) return true;
			Bounty b = e.getValue();
			if (b.contributions == null) b.contributions = new LinkedHashMap<>();
			b.contributions.entrySet().removeIf(c -> {
				boolean bad = c.getKey() == null || c.getValue() == null || c.getValue() <= 0;
				if (bad) SmpEconomy.LOG.error("[SMPEco] {}: removed invalid contribution {} = {} on {}", source, c.getKey(), c.getValue(), e.getKey());
				return bad;
			});
			return b.contributions.isEmpty();
		});
	}
}
