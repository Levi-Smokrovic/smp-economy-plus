package dev.smpeconomy.features.bank;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.ConfigManager;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.EconomyService.Result;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.Treasury;

public final class BankService {
	public static final String R_INTEREST = "bank_interest";

	static final ConfigManager.Config<BankConfig> CONFIG = ConfigManager.register("bank", BankConfig.class, BankConfig::new, BankConfig::validate);
	static final DataStore<BankData> STORE = new DataStore<>("bank.json", BankData.class, BankData::new);
	public static final BankLedger LIVE = new BankLedger(STORE::get, STORE::markDirty, STORE::save, System::currentTimeMillis, CONFIG::get,
		BankVault.LIVE);

	static {
		// runs on load of bank.json; economy.json is already loaded because its store registers earlier (see
		// SmpEconomy)
		STORE.onLoad(data -> {
			int fixed = BankLedger.reconcile(data, System.currentTimeMillis(), BankVault.LIVE, candidates());
			if (fixed > 0) {
				SmpEconomy.LOG.warn("[SMPEco] bank.json: {} correction(s) reconciling with economy.json (lots, legacy balance, shadow accounts)", fixed);
				STORE.markDirty();
			}
		});
	}

	private BankService() {}

	static void bootstrap() {
	}

	/**
	 * Everyone with an economy account or player data: their shadow account may exist even if bank.json doesn't know
	 * it.
	 */
	private static List<UUID> candidates() {
		List<UUID> ids = new ArrayList<>();
		for (EconomyService.Entry e : EconomyService.top()) ids.add(e.id());
		ids.addAll(PlayerDataService.knownPlayers());
		return ids;
	}

	public static long balance(UUID player) {
		return LIVE.balance(player);
	}

	public static Result deposit(UUID player, long cents) {
		return LIVE.deposit(player, cents);
	}

	public static Result withdraw(UUID player, long cents) {
		return LIVE.withdraw(player, cents);
	}

	/**
	 * What the bank may use of the treasury's hourly budget right now: everything above the NPC reserve, or the whole
	 * remaining budget for the last installment.
	 */
	static long available(long allowance, long hourlyCap, double sharePercent, boolean last) {
		if (allowance <= 0) return 0;
		if (last) return allowance;
		long keep = Math.max(0, hourlyCap - Money.percentOf(hourlyCap, sharePercent));
		return Math.max(0, allowance - keep);
	}

	static BankLedger.InterestSource liveSource() {
		return new BankLedger.InterestSource() {
			@Override
			public long dailyBudget(long now) {
				return Money.percentOf(Treasury.balance(), CONFIG.get().maxTreasuryPercent);
			}

			@Override
			public long available(long now, boolean last) {
				return BankService.available(Treasury.allowance(), Treasury.maxSpendPerHour(), CONFIG.get().hourlyBudgetSharePercent, last);
			}

			@Override
			public Result pay(UUID account, long cents, long now) {
				return Treasury.payTo(account, cents, R_INTEREST);
			}
		};
	}

	static BankLedger.InterestSource ledgerSource(Treasury.Ledger ledger, Supplier<BankConfig> config) {
		return new BankLedger.InterestSource() {
			@Override
			public long dailyBudget(long now) {
				return Money.percentOf(ledger.balance(), config.get().maxTreasuryPercent);
			}

			@Override
			public long available(long now, boolean last) {
				return BankService.available(ledger.allowance(now), ledger.hourlyCap(now), config.get().hourlyBudgetSharePercent, last);
			}

			@Override
			public Result pay(UUID account, long cents, long now) {
				return ledger.payTo(account, cents, R_INTEREST, now);
			}
		};
	}

	static void tick() {
		if (SmpEconomy.isStopping() || !STORE.isLoaded()) return;
		for (BankLedger.Payout p : LIVE.dailyTick(liveSource())) announce(p, false);
	}

	/** Offline players see it in /bank; a pending message per day would be noise. */
	static void announce(BankLedger.Payout p, boolean forced) {
		SmpEconomy.LOG.info("[SMPEco] Bank interest{}: {} players, due {}, target {}, paid {} (budget {}, factor {}){}", forced ? " (manual)" : "",
			p.dueByPlayer().size(), Money.format(p.due()), Money.format(p.target()), Money.format(p.paid()), Money.format(p.budget()),
			String.format(java.util.Locale.ROOT, "%.3f", p.factor()), p.failed() ? " - treasury refused" : "");
		MinecraftServer server = SmpEconomy.server();
		if (server == null) return;
		String reason = p.treasuryShort() ? "Treasury is low" : "Treasury budget used up";
		for (Map.Entry<UUID, Long> e : p.dueByPlayer().entrySet()) {
			ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
			if (player == null) continue;
			long due = e.getValue();
			long paid = p.paidByPlayer().getOrDefault(e.getKey(), 0L);
			if (paid <= 0) {
				Msg.info(player, "§eNo interest today – " + (p.treasuryShort() ? "the Treasury is low." : "the Treasury's budget is used up."));
				continue;
			}
			String cut = paid < due ? " §8(reduced to " + percentText((double) paid / (double) due) + " – " + reason + ")" : "";
			Msg.info(player, "§aInterest credited: §a+" + Money.format(paid) + "§7 to your bank account." + cut);
		}
	}

	static String percentText(double factor) {
		return (int) Math.floor(Math.max(0, factor) * 100 + 1e-9) + "%";
	}

	static String rateText(double percent) {
		return java.math.BigDecimal.valueOf(percent).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "%";
	}
}
