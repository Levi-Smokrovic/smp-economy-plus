package dev.smpeconomy.features.bounty;

import com.google.common.net.InetAddresses;
import java.net.InetAddress;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.Feature;
import dev.smpeconomy.core.Hooks;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.TimeUtil;
import dev.smpeconomy.core.test.TestPlayers;

/**
 * {@code /bounty <player> <amount>} places a stackable bounty in escrow after a confirmation dialog. Whoever
 * kills the target in PvP ({@link Hooks#onPlayerKilledPlayer}, never fired for suicide or environmental
 * death) gets it all minus a treasury fee, unless a team, IP or playtime rule applies ({@link #blockReason}).
 */
public final class BountyFeature implements Feature {
	@Override
	public String id() {
		return "bounty";
	}

	@Override
	public void register() {
		Bounties.bootstrap();
		BountyCommands.register();
		Hooks.onPlayerKilledPlayer(BountyFeature::onPlayerKilled);
		// JOIN fires in PlayerList#placeNewPlayer on the server thread; only DISCONNECT is on the Netty
		// thread.
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> BountyNotify.onJoin(handler.getPlayer()));
		TestPlayers.onCleanup(BountyFeature::cleanupTestPlayer);
		BountySelfTests.register();
	}

	@Override
	public void onServerStarted(MinecraftServer server) {
		Bounties.checkConsistency();
	}

	@Override
	public void onServerStopping(MinecraftServer server) {
		if (Bounties.ready()) BountyNotify.onServerStopping();
	}

	static void onPlayerKilled(ServerPlayer killer, ServerPlayer victim) {
		if (killer == null || victim == null || !Bounties.ready()) return;
		String killerName = killer.getGameProfile().name();
		String victimName = victim.getGameProfile().name();
		if (killer.getUUID().equals(victim.getUUID()) || Bounties.LIVE.total(victim.getUUID()) <= 0) return;
		String blocked = blockReason(sameTeam(killer, victim), ipOf(killer), ipOf(victim),
			PlayerDataService.getPlaytimeTicks(killer.getUUID()), Bounties.config());
		if (blocked != null) {
			// The bounty stays, same as an environmental death.
			Msg.sendOrQueue(killer.getUUID(), "§7No bounty for §c" + victimName + "§7: " + blocked + ".");
			return;
		}
		BountyLedger.Payout p = Bounties.LIVE.claim(killer.getUUID(), killerName, victim.getUUID(), victimName, System.currentTimeMillis());
		switch (p.status()) {
			case PAID -> {
				BountyNotify.paid(SmpEconomy.server(), killer.getUUID(), killerName, victim.getUUID(), victimName, p);
				DataStore.requestFlush();
			}
			case LOCKED, PAYOUT_FAILED -> Msg.sendOrQueue(killer.getUUID(),
				"§cThe bounty on §f" + victimName + " §ccouldn't be paid out and stays active. Please report this to an admin.");
			default -> {
			}
		}
	}

	/**
	 * Rule against arranged kills: the reason a payout is blocked, or null. Unknown IPs (null: FakePlayer,
	 * disconnected, tunnel or LAN address, see {@link #publicIp}) never block. Behind a tunnel every IP is
	 * unknown, so {@link BountyConfig#minKillerPlaytimeMinutes} also guards against fresh alts.
	 */
	static String blockReason(boolean allied, String killerIp, String victimIp, long killerPlaytimeTicks, BountyConfig cfg) {
		if (cfg.blockSameTeam && allied) return "you're on the same team";
		if (cfg.blockSameIp && killerIp != null && killerIp.equals(victimIp)) return "same IP address";
		long minTicks = cfg.minKillerPlaytimeTicks();
		if (minTicks > 0 && killerPlaytimeTicks < minTicks) {
			return "your account only has " + TimeUtil.formatTicks(Math.max(0, killerPlaytimeTicks)) + " of playtime (needs "
				+ TimeUtil.formatTicks(minTicks) + ")";
		}
		return null;
	}

	/**
	 * Compares by scoreboard name, like {@code Entity#getTeam} does for real players; FakePlayers always
	 * return null there.
	 */
	static boolean sameTeam(ServerPlayer a, ServerPlayer b) {
		Scoreboard scoreboard = a.level().getScoreboard();
		PlayerTeam team = scoreboard.getPlayersTeam(a.getScoreboardName());
		return team != null && team.isAlliedTo(scoreboard.getPlayersTeam(b.getScoreboardName()));
	}

	/** Null if unknown (FakePlayer, no connection) or not a real player address ({@link #publicIp}). */
	static String ipOf(ServerPlayer player) {
		try {
			if (player.connection == null) return null;
			return publicIp(player.getIpAddress());
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * Only public, routable addresses count for the IP rule. Everything else returns null (unknown, never
	 * blocks): loopback, 0.0.0.0/8, private ranges (10/8, 172.16/12, 192.168/16), link-local, CGNAT/Tailscale
	 * 100.64/10, IPv6 ULA fc00::/7, multicast and non-IPs ({@code "<unknown>"}). Those belong to a tunnel,
	 * proxy or LAN. Behind a tunnel agent without the PROXY protocol the server sees the same address for all
	 * players, and comparing it would block every payout.
	 */
	static String publicIp(String raw) {
		if (raw == null) return null;
		String s = raw.trim();
		if (s.isEmpty() || !InetAddresses.isInetAddress(s)) return null;
		InetAddress a = InetAddresses.forString(s); // Literals only, no DNS lookup; IPv4-mapped IPv6 becomes IPv4
		if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isMulticastAddress()) {
			return null;
		}
		byte[] b = a.getAddress();
		if (b.length == 4) {
			int b0 = b[0] & 0xff;
			int b1 = b[1] & 0xff;
			if (b0 == 0 || b0 == 255) return null; // 0.0.0.0/8, broadcast
			if (b0 == 100 && (b1 & 0xc0) == 64) return null; // 100.64.0.0/10 (CGNAT, Tailscale)
		} else if ((b[0] & 0xfe) == 0xfc) {
			return null; // fc00::/7 (unique local, IPv6 LAN)
		}
		return InetAddresses.toAddrString(a);
	}

	static void cleanupTestPlayer(UUID id) {
		BountyNotify.forget(id);
		if (!Bounties.ready()) return;
		Bounties.LIVE.refundAll(id);
		Bounties.LIVE.refundContributor(id);
		if (Bounties.STORE.get().history.removeIf(c -> id.equals(c.killer) || id.equals(c.victim))) Bounties.STORE.markDirty();
	}
}
