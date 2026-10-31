package dev.smpeconomy.features.bank;

import dev.smpeconomy.core.Money;

public final class BankConfig {
	/** When off, nothing is paid; deposits keep working. */
	public boolean interestEnabled = true;
	/** Percent per day on the eligible balance. */
	public double interestPercent = 1.0;
	/** Cap per player per day, in cents. */
	public long maxInterestPerDay = 200_000;
	/** Only balance held at least this many hours earns interest. */
	public int holdHours = 24;
	/**
	 * Daily budget as a share of the treasury balance at the cutoff. Rates drop, proportionally for everyone, only
	 * when the interest due exceeds it.
	 */
	public double maxTreasuryPercent = 5.0;
	/**
	 * Share of the treasury's hourly budget that interest may use; the rest stays free for NPC spending. Payouts run
	 * in installments as the budget frees up.
	 */
	public double hourlyBudgetSharePercent = 50.0;
	/**
	 * A payout ends at most this many hours after midnight with one last installment from the whole available hourly
	 * budget; anything left is forfeited pro rata. 0 pays in one step.
	 */
	public int payoutHours = 6;

	public void validate() {
		if (!(interestPercent >= 0 && interestPercent <= 100)) throw new IllegalArgumentException("interestPercent must be in [0, 100]");
		if (maxInterestPerDay < 0 || maxInterestPerDay > Money.MAX) throw new IllegalArgumentException("maxInterestPerDay must be in [0, " + Money.MAX + "]");
		if (holdHours < 1 || holdHours > 24 * 30) throw new IllegalArgumentException("holdHours must be in [1, 720]");
		if (!(maxTreasuryPercent >= 0 && maxTreasuryPercent <= 100)) throw new IllegalArgumentException("maxTreasuryPercent must be in [0, 100]");
		if (!(hourlyBudgetSharePercent >= 0 && hourlyBudgetSharePercent <= 100)) {
			throw new IllegalArgumentException("hourlyBudgetSharePercent must be in [0, 100]");
		}
		if (payoutHours < 0 || payoutHours > 20) throw new IllegalArgumentException("payoutHours must be in [0, 20]");
	}

	long holdMillis() {
		return holdHours * BankLedger.HOUR_MS;
	}
}
