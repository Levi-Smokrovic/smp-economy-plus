package dev.smpeconomy.features.economy;

import java.util.UUID;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.EconomyService.Result;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Treasury;

/**
 * Admin money operations for /eco. The treasury only goes through {@link Treasury} so its stats and balance agree.
 * The command already filters system accounts via {@code Args.knownPlayer}; these methods reject them as well.
 */
public final class EcoAdmin {
	private EcoAdmin() {}

	public static Result give(UUID player, long cents, String actor) {
		if (EconomyService.isSystemAccount(player)) return Result.NO_ACCOUNT;
		return log("give", player, cents, actor, EconomyService.deposit(player, cents));
	}

	public static Result take(UUID player, long cents, String actor) {
		if (EconomyService.isSystemAccount(player)) return Result.NO_ACCOUNT;
		return log("take", player, cents, actor, EconomyService.withdraw(player, cents));
	}

	public static Result set(UUID player, long cents, String actor) {
		if (EconomyService.isSystemAccount(player)) return Result.NO_ACCOUNT;
		return log("set", player, cents, actor, EconomyService.set(player, cents));
	}

	public static Result reset(UUID player, String actor) {
		if (EconomyService.isSystemAccount(player)) return Result.NO_ACCOUNT;
		long start = Math.max(0, Math.min(EconomyService.startBalance(), Money.MAX));
		return log("reset", player, start, actor, EconomyService.set(player, start));
	}

	public static Result treasuryDeposit(long cents, String actor) {
		return log("treasury deposit", Treasury.ID, cents, actor, Treasury.deposit(cents, Treasury.R_ADMIN));
	}

	/** Destroys the money; does not count toward the NPC hourly cap. */
	public static Result treasuryWithdraw(long cents, String actor) {
		return log("treasury withdraw", Treasury.ID, cents, actor, Treasury.withdrawUncapped(cents, Treasury.R_ADMIN));
	}

	private static Result log(String op, UUID target, long cents, String actor, Result result) {
		if (result.ok()) {
			SmpEconomy.LOG.info("[SMPEco] /eco {} {} {} of {} -> balance {}", op, EconomyService.nameOrUnknown(target), Money.format(cents), actor,
				Money.format(EconomyService.balance(target)));
		}
		return result;
	}
}
