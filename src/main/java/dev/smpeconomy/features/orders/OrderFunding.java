package dev.smpeconomy.features.orders;

import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.EconomyService.Result;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Treasury;

interface OrderFunding {
	long allowance();

	long maxSpendPerHour();

	boolean withdrawEscrow(long cents);

	void refundEscrow(long cents);

	void collectFee(long cents);

	static OrderFunding treasury() {
		return new OrderFunding() {
			@Override
			public long allowance() {
				return Treasury.allowance();
			}

			@Override
			public long maxSpendPerHour() {
				return Treasury.maxSpendPerHour();
			}

			@Override
			public boolean withdrawEscrow(long cents) {
				return Money.isValidAmount(cents) && Treasury.withdraw(cents, Treasury.R_NPC_ORDER).ok();
			}

			@Override
			public void refundEscrow(long cents) {
				if (cents <= 0) return;
				Result r = Treasury.deposit(cents, Treasury.R_NPC_ORDER);
				if (!r.ok()) SmpEconomy.LOG.error("[SMPEco] orders: NPC escrow {} not returned to the treasury: {}", Money.format(cents), r);
			}

			@Override
			public void collectFee(long cents) {
				if (cents <= 0) return;
				Result r = Treasury.deposit(cents, Treasury.R_ORDER_FEE);
				if (!r.ok()) SmpEconomy.LOG.error("[SMPEco] orders: delivery fee {} not booked: {}", Money.format(cents), r);
			}
		};
	}
}
