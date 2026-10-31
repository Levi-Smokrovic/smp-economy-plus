package dev.smpeconomy.features.casino;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.Bots;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Services;

/**
 * {@link Services.DoubleOrNothing}: after a won PvP coin flip (minigames) the winner gets an offer in chat. It moves no
 * money; only accepting starts a normal house round (stake = amount won, same rules: max bet, coverage, daily limit).
 * Offers are transient (not stored), single-use and expire after {@code offerTimeoutSeconds}.
 */
final class CasinoOffers implements Services.DoubleOrNothing {
	record Offer(String id, long cents, long expiresAt) {}

	private static final Map<UUID, Offer> OFFERS = new HashMap<>();
	private static long counter;

	@Override
	public boolean offer(ServerPlayer player, long cents) {
		return create(player, cents) != null;
	}

	/** Creates an offer and sends the chat buttons; null = not offered (reason logged at DEBUG). */
	static Offer create(ServerPlayer player, long cents) {
		if (player == null || Bots.isBot(player) || cents <= 0) return null;
		MinecraftServer server = SmpEconomy.server();
		if (server == null || server.getPlayerList().getPlayer(player.getUUID()) != player || !Gui.canOpen(player)) return null;
		String error = check(player.getUUID(), cents);
		if (error != null) return null;
		Offer o = new Offer(Long.toString(++counter, 36) + Long.toString(Casino.RNG.nextInt(1_000_000), 36),
			cents, Casino.clock.getAsLong() + Casino.cfg().offerTimeoutSeconds * 1000L);
		OFFERS.put(player.getUUID(), o);
		Msg.send(player, Msg.prefixed("§6§lDouble or Nothing? §7Bet your " + Msg.moneyStr(cents) + " §7win against the house (48%): ")
			.append(Msg.clickRun("§a§l[Double or Nothing]", "/casino double " + o.id(), "§7Bet " + Msg.moneyStr(cents) + "§7, win " + Msg.moneyStr(cents * 2)))
			.append(Msg.legacy(" "))
			.append(Msg.clickRun("§c[No]", "/casino double " + o.id() + " no", "§7Decline the offer")));
		return o;
	}

	/** Same eligibility as a normal coin flip, plus enough money. */
	static String check(UUID player, long cents) {
		String error = Casino.checkAdmission(player, Game.COINFLIP, cents, Casino.potentialSafe(Game.COINFLIP, cents, 0), 0);
		if (error != null) return error;
		if (!EconomyService.has(player, cents)) return "Not enough money.";
		return null;
	}

	static void accept(ServerPlayer player, String id, boolean decline) {
		Offer o = OFFERS.get(player.getUUID());
		if (o == null || !o.id().equals(id)) {
			Msg.error(player, "This offer is no longer available.");
			return;
		}
		OFFERS.remove(player.getUUID());
		if (decline) {
			Msg.info(player, "Offer declined.");
			return;
		}
		if (Casino.clock.getAsLong() > o.expiresAt()) {
			Msg.error(player, "This offer has expired.");
			return;
		}
		CoinflipGui.openPvp(player, o.cents());
	}

	/** For selftests and admin: a player's open offer. */
	static Offer get(UUID player) {
		return OFFERS.get(player);
	}

	static void clear(UUID player) {
		OFFERS.remove(player);
	}

	/** Forgets expired offers (watchdog). */
	static void purgeExpired() {
		long now = Casino.clock.getAsLong();
		OFFERS.values().removeIf(o -> now > o.expiresAt());
	}

	static void clearAll() {
		OFFERS.clear();
	}
}
