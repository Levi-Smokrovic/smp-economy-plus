package dev.smpeconomy.features.bank;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.EconomyService.Result;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.TimeUtil;

final class BankActions {
	static final String AMOUNT_HINT = "Examples: §e100§c, §e2.5k§c, §e50%§c, §eall";
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH); // resolve the zone at format time: the core.json timezone can change on reload

	private BankActions() {}

	static boolean handleInput(ServerPlayer player, boolean deposit, String input) {
		UUID id = player.getUUID();
		long base = deposit ? EconomyService.balance(id) : BankService.balance(id);
		long amount = BankLedger.parseAmount(input, base);
		if (amount < 0) {
			fail(player, "Invalid amount. " + AMOUNT_HINT);
			return false;
		}
		return move(player, deposit, amount);
	}

	/** Re-reads all balances, so input validated earlier can still fail here. */
	static boolean move(ServerPlayer player, boolean deposit, long amount) {
		UUID id = player.getUUID();
		if (amount <= 0) {
			fail(player, deposit ? "You have no cash to deposit." : "You have no bank balance to withdraw.");
			return false;
		}
		Result r = deposit ? BankService.deposit(id, amount) : BankService.withdraw(id, amount);
		if (!r.ok()) {
			fail(player, errorText(r, deposit));
			return false;
		}
		Msg.info(player, (deposit ? "§aDeposited: " : "§aWithdrew: ") + Msg.moneyStr(amount)
			+ " §8| §7Bank: " + Msg.moneyStr(BankService.balance(id)) + " §8| §7Cash: " + Msg.moneyStr(EconomyService.balance(id)));
		Gui.successSound(player);
		return true;
	}

	static String errorText(Result r, boolean deposit) {
		return switch (r) {
			case INSUFFICIENT_FUNDS -> deposit ? "Not enough cash." : "Not enough bank balance.";
			case LIMIT_EXCEEDED -> deposit ? "Your bank account is full." : "Your balance is full.";
			case NO_ACCOUNT -> "No account found.";
			case INVALID_AMOUNT -> "Invalid amount. " + AMOUNT_HINT;
			default -> r.message();
		};
	}

	private static void fail(ServerPlayer player, String text) {
		Msg.error(player, text);
		Gui.failSound(player);
	}

	static List<String> infoLines(UUID id) {
		BankLedger l = BankService.LIVE;
		List<String> lines = new ArrayList<>();
		lines.add("§7Bank balance: " + Msg.moneyStr(l.balance(id)));
		lines.add("§7Cash: " + Msg.moneyStr(EconomyService.balance(id)));
		lines.addAll(interestLines(id));
		return lines;
	}

	static List<String> interestLines(UUID id) {
		BankLedger l = BankService.LIVE;
		BankConfig cfg = l.config();
		List<String> lines = new ArrayList<>();
		if (!cfg.interestEnabled) {
			lines.add("§8Interest is currently turned off.");
			return lines;
		}
		long next = l.eligibleAtNextPayout(id);
		lines.add("§7Interest: §e" + BankService.rateText(cfg.interestPercent) + " §7per day §8(max. " + Msg.moneyStr(cfg.maxInterestPerDay) + "§8)");
		lines.add("§7Money earns interest after §e" + cfg.holdHours + "h §7in the bank.");
		lines.add("§7Next interest: §a~" + money(l.interestFor(next)) + " §7in §e" + TimeUtil.formatDuration(l.nextPayoutAt() - l.now()));
		lines.add("§8(on " + money(next) + "; less if the Treasury is low)");
		BankData.Batch batch = l.pending().orElse(null);
		BankData.Claim claim = batch == null ? null : batch.claims.get(id);
		if (claim != null && !claim.closed) {
			long expected = BankLedger.share(claim.due, batch.target, batch.totalDue);
			lines.add("§7Interest today: §a" + money(claim.paid) + " §7of §a" + money(expected) + " §8(payout in progress)");
			return lines;
		}
		l.peek(id).filter(a -> a.lastInterestAt > 0).ifPresent(a ->
			lines.add("§7Last interest: §a" + money(a.lastInterest) + " §8(" + DATE.withZone(TimeUtil.zone()).format(Instant.ofEpochMilli(a.lastInterestAt)) + ")"));
		return lines;
	}

	private static String money(long cents) {
		return dev.smpeconomy.core.Money.format(cents);
	}
}
