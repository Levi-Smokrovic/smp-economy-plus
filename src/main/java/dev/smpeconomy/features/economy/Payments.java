package dev.smpeconomy.features.economy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.Settings;

/**
 * Logic behind /pay. Offline payments collapse into one pending line per sender (sum and count), so nobody can fill a
 * recipient's 50 pending slots with tiny payments and evict other messages such as sold auctions. Server thread only.
 */
public final class Payments {
	static final String OFFLINE_KEY = "economy.offline_pay";

	public enum Status {
		OK(""),
		INVALID_AMOUNT("Invalid amount."),
		SELF("You can't send money to yourself."),
		SYSTEM_ACCOUNT("You can't send money to the Treasury."),
		NO_ACCOUNT("Player not found."),
		RECEIVE_DISABLED("§e%s §cisn't accepting payments right now."),
		INSUFFICIENT_FUNDS("You don't have enough money."),
		TARGET_FULL("§e%s§c's balance is full.");

		private final String message;

		Status(String message) {
			this.message = message;
		}

		public boolean ok() {
			return this == OK;
		}

		public String message(String targetName) {
			return message.contains("%s") ? String.format(message, targetName) : message;
		}
	}

	public record Outcome(Status status, long amount, boolean targetOnline) {
		public boolean ok() {
			return status.ok();
		}
	}

	static final class OfflinePay {
		Map<String, Pending> bySender = new HashMap<>();
	}

	static final class Pending {
		String name = "";
		long cents;
		int count;
		String text;
	}

	private Payments() {}

	/** Validates without moving money. Balances are checked atomically by the transfer itself. */
	public static Status precheck(UUID from, UUID to, long cents) {
		if (!Money.isValidAmount(cents)) return Status.INVALID_AMOUNT;
		if (from.equals(to)) return Status.SELF;
		if (EconomyService.isSystemAccount(to)) return Status.SYSTEM_ACCOUNT;
		if (!EconomyService.hasAccount(to)) return Status.NO_ACCOUNT;
		if (!Settings.get(to, Settings.PAY_RECEIVE)) return Status.RECEIVE_DISABLED;
		if (!EconomyService.has(from, cents)) return Status.INSUFFICIENT_FUNDS;
		return Status.OK;
	}

	/** @param server null means the recipient counts as offline */
	public static Outcome pay(MinecraftServer server, UUID from, String fromName, UUID to, long cents) {
		Status pre = precheck(from, to, cents);
		if (!pre.ok()) return new Outcome(pre, cents, false);
		Status status = switch (EconomyService.transfer(from, to, cents)) {
			case OK -> Status.OK;
			case INSUFFICIENT_FUNDS -> Status.INSUFFICIENT_FUNDS;
			case LIMIT_EXCEEDED -> Status.TARGET_FULL;
			case SAME_ACCOUNT -> Status.SELF;
			case NO_ACCOUNT -> Status.NO_ACCOUNT;
			default -> Status.INVALID_AMOUNT;
		};
		if (!status.ok()) return new Outcome(status, cents, false);

		ServerPlayer online = server == null ? null : server.getPlayerList().getPlayer(to);
		if (online != null) {
			Msg.info(online, "§e" + fromName + " §7sent you " + Msg.moneyStr(cents) + ".");
			Gui.successSound(online);
		} else {
			queueOffline(from, fromName, to, cents);
		}
		SmpEconomy.LOG.info("[SMPEco] /pay {} -> {}: {}", fromName, EconomyService.nameOrUnknown(to), Money.format(cents));
		return new Outcome(Status.OK, cents, online != null);
	}

	static void queueOffline(UUID from, String fromName, UUID to, long cents) {
		OfflinePay data = PlayerDataService.getBlob(to, OFFLINE_KEY, OfflinePay.class);
		if (data == null) data = new OfflinePay();
		if (data.bySender == null) data.bySender = new HashMap<>();
		Pending p = data.bySender.computeIfAbsent(from.toString(), k -> new Pending());
		if (p.text != null) {
			List<String> queued = PlayerDataService.get(to).pendingMessages;
			// if the line is gone (delivered, or evicted by the limit of 50) the sum is still right: evicted payments
			// were never reported and onJoin resets delivered ones
			if (queued.remove(p.text)) PlayerDataService.markDirty();
		}
		p.name = fromName;
		p.cents = Money.saturatedAdd(p.cents, cents);
		p.count = p.count == Integer.MAX_VALUE ? p.count : p.count + 1;
		p.text = offlineText(p);
		PlayerDataService.addPendingMessage(to, p.text);
		PlayerDataService.setBlob(to, OFFLINE_KEY, data);
	}

	static void onJoin(UUID player) {
		if (PlayerDataService.peek(player).map(d -> d.blobs.containsKey(OFFLINE_KEY)).orElse(false)) {
			PlayerDataService.remove(player, OFFLINE_KEY);
		}
	}

	static String offlineText(Pending p) {
		String base = "§e" + p.name + " §7sent you " + Msg.moneyStr(p.cents);
		return p.count > 1 ? base + " §8(" + p.count + " payments)" : base + ".";
	}

	public static boolean setReceive(UUID player, Boolean value) {
		if (value == null) return Settings.toggle(player, Settings.PAY_RECEIVE);
		Settings.set(player, Settings.PAY_RECEIVE, value);
		return value;
	}
}
