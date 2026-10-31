package dev.smpeconomy.features.bounty;

import java.util.UUID;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.ConfigManager;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Money;

/**
 * Live state. All bounty money sits on a system account in economy.json, so it never leaves the economy, and
 * placing and paying out are atomic transfers.
 */
public final class Bounties {
	/** Holds all placed bounty money. Not a player, never in /baltop. */
	public static final UUID ESCROW_ID = UUID.fromString("00000000-0000-0000-0000-00000000b047");
	public static final String ESCROW_NAME = "Bounty-Escrow";

	static final ConfigManager.Config<BountyConfig> CONFIG =
		ConfigManager.register("bounty", BountyConfig.class, BountyConfig::new, BountyConfig::validate);
	static final DataStore<BountyData> STORE = new DataStore<>("bounties.json", BountyData.class, BountyData::new);
	public static final BountyLedger LIVE = new BountyLedger(STORE::get, STORE::markDirty, ESCROW_ID, CONFIG::get);

	/**
	 * True if bounties.json was last restored from {@code .bak} or {@code .bak2}, meaning recent changes are
	 * missing.
	 */
	private static boolean restoredFromBackup;

	static {
		STORE.onLoad(d -> {
			// DataStore.load sets "dirty" exactly when it loaded from a restore point, before the onLoad
			// listeners run.
			restoredFromBackup = STORE.isDirty();
			d.fixNulls("bounties.json");
		});
		EconomyService.registerSystemAccount(ESCROW_ID, ESCROW_NAME);
	}

	private Bounties() {}

	/** Forces class loading (config, DataStore, system account). Call from {@code register()}. */
	static void bootstrap() {
	}

	public static BountyConfig config() {
		return CONFIG.get();
	}

	public static boolean ready() {
		return STORE.isLoaded();
	}

	/**
	 * Never moves money on load. If the escrow balance and the bounty total differ at all, or bounties.json
	 * came from a backup, everything locks ({@link BountyLedger#startupCheck}) and each bounty is logged for
	 * repair.
	 */
	static void checkConsistency() {
		if (!STORE.isLoaded()) return;
		boolean restored = restoredFromBackup;
		restoredFromBackup = false;
		long sum = LIVE.totalEscrowed();
		long balance = LIVE.escrowBalance();
		if (!LIVE.startupCheck(restored, System.currentTimeMillis())) {
			SmpEconomy.LOG.info("[SMPEco] Bounties: {} active, {} in escrow", STORE.get().bounties.size(), Money.format(balance));
			return;
		}
		SmpEconomy.LOG.error("[SMPEco] Bounties LOCKED ({}): escrow account {}, bounty total {}. No contribution, payout or "
				+ "refund until an admin repairs and unlocks. Steps: /smpeco bounty check; drop bounties already paid out (log \"claims\") "
				+ "with /smpeco bounty drop <player>; refund lost stakes (log \"places\", surplus) with /smpeco bounty surplus "
				+ "<contributor> <amount>; then /smpeco bounty unlock",
			LIVE.lockReason(), Money.format(balance), Money.format(sum));
		BountyData d = STORE.get();
		d.bounties.forEach((target, b) -> SmpEconomy.LOG.error("[SMPEco]   Bounty on {} ({}): {} from {}", EconomyService.nameOrUnknown(target), target,
			Money.format(b.total()), describe(b.contributions)));
		for (int i = 0; i < Math.min(10, d.history.size()); i++) {
			BountyData.Claim c = d.history.get(i);
			SmpEconomy.LOG.error("[SMPEco]   History: {} claimed {} for {} (time {})", c.killerName, Money.format(c.total), c.victimName, c.at);
		}
	}

	private static String describe(java.util.Map<UUID, Long> contributions) {
		StringBuilder sb = new StringBuilder();
		contributions.forEach((c, v) -> {
			if (!sb.isEmpty()) sb.append(", ");
			sb.append(EconomyService.nameOrUnknown(c)).append(' ').append(Money.format(v));
		});
		return sb.toString();
	}
}
