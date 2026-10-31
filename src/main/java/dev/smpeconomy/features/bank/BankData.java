package dev.smpeconomy.features.bank;

import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Contents of bank.json: metadata only, no money. The balance lives on the player's shadow account in economy.json
 * ({@link BankVault}), so a stale bank.json can change interest eligibility but never money. Invariant: sum of lots
 * == shadow balance, reconciled on load by {@link BankLedger#repair}.
 */
public final class BankData {
	public Map<UUID, Account> accounts = new HashMap<>();
	/** ISO date (server time zone) of the last regular midnight payout; null before the first start. */
	public String lastInterestDate;
	public Batch pending;
	public long lastPayoutAt;
	public long lastPayoutDue;
	public long lastPayoutPaid;
	public int lastPayoutPlayers;

	public static final class Account {
		/**
		 * Moved to the shadow account once on load and cleared only when the shadow account is in
		 * economy.json on the next load, so the money is never doubled or lost.
		 */
		@SerializedName("balance")
		public Long legacyBalance;
		/**
		 * Deposit lots, ascending by time. Deposit times round up to the full hour: cutoffs always fall on full
		 * hours, so eligibility is unaffected while the lot count stays bounded. Withdrawals take from the newest lot
		 * (LIFO), which makes the sum of lots up to T exactly the minimum balance since T.
		 */
		public List<Lot> lots = new ArrayList<>();
		/** Interest at the last cutoff in cents (grows during a payout) and that cutoff in epoch ms. */
		public long lastInterest;
		public long lastInterestAt;
		public long totalInterest;
	}

	/** {@code t} is epoch ms, {@code a} is cents (> 0). */
	public static final class Lot {
		public long t;
		public long a;

		public Lot() {}

		public Lot(long t, long a) {
			this.t = t;
			this.a = a;
		}
	}

	/**
	 * One interest payout for cutoff {@code reference}. {@code target} is the total due capped by the daily {@code
	 * budget}. {@code released} grows towards {@code target} as the hourly budget allows; at any moment each player
	 * has received {@code floor(due × released / totalDue)}. Whatever is unpaid at {@code deadline} is forfeited.
	 */
	public static final class Batch {
		public long reference;
		public long cutoff;
		public long createdAt;
		public long deadline;
		public long nextStepAt;
		public long budget;
		public long totalDue;
		public long target;
		public long released;
		public Map<UUID, Claim> claims = new LinkedHashMap<>();
	}

	/** A player's claim in a {@link Batch}. {@code paid} is persisted before the money moves. */
	public static final class Claim {
		public long due;
		public long paid;
		/** Payment permanently impossible, e.g. full bank account. */
		public boolean closed;

		public Claim() {}

		public Claim(long due) {
			this.due = due;
		}
	}
}
