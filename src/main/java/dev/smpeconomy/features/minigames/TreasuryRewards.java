package dev.smpeconomy.features.minigames;

import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import dev.smpeconomy.core.Bots;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.Treasury;

/**
 * Prizes paid from the treasury, capped via {@link Treasury#payTo}. What the treasury can't pay at win time (hourly
 * budget used up, amount above the budget, account full) is neither lost nor newly created: the rest is recorded
 * per player ({@link #C_PENDING} in players.json) and paid each minute in parts of at most {@link
 * Treasury#allowance()} while the player is online. Server thread only.
 */
public final class TreasuryRewards {
	public static final String C_PENDING = "minigames.pending_reward";

	public record Payout(long paid, long pending) {}

	private TreasuryRewards() {}

	/** If that isn't possible right now, the whole amount is recorded as pending. */
	public static Payout pay(UUID player, long cents) {
		if (cents <= 0) return new Payout(0, 0);
		EconomyService.Result r = Treasury.payTo(player, cents, Treasury.R_REWARD);
		if (r.ok()) return new Payout(cents, 0);
		PlayerDataService.addCounter(player, C_PENDING, cents);
		return new Payout(0, cents);
	}

	public static long pending(UUID player) {
		return PlayerDataService.getCounter(player, C_PENDING);
	}

	/** Pays as much of the pending prize as the treasury allows right now and returns the amount. */
	public static long settle(UUID player) {
		long open = pending(player);
		if (open <= 0) return 0;
		long amount = Math.min(open, Treasury.allowance());
		if (amount <= 0) return 0;
		if (!Treasury.payTo(player, amount, Treasury.R_REWARD).ok()) return 0;
		PlayerDataService.takeCounter(player, C_PENDING, amount);
		return amount;
	}

	public static void settleOnline(MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (Bots.isBot(p)) continue;
			UUID id = p.getUUID();
			if (pending(id) <= 0) continue;
			long paid = settle(id);
			if (paid <= 0) continue;
			long rest = pending(id);
			Msg.info(p, "Back payment from the Treasury: " + Msg.moneyStr(paid)
				+ (rest > 0 ? " §8(still owed: " + Money.format(rest) + ")" : ""));
		}
	}
}
