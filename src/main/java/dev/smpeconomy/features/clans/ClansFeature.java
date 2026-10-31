package dev.smpeconomy.features.clans;

import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import dev.smpeconomy.core.Feature;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Scheduler;
import dev.smpeconomy.core.Treasury;
import dev.smpeconomy.core.test.TestPlayers;
import dev.smpeconomy.features.clans.ClanData.Clan;

/**
 * /clan with roles, invites, clan chat (/cc, /clan chat), clan bank (system account, log of the last 20
 * bookings), clan home with delayed teleport, tags through vanilla scoreboard teams {@code tri_clan_<id>}
 * (friendly fire off), leaderboard and GUI. Data: {@code <world>/smpeconomy/clans.json}, config: {@code
 * config/smpeconomy/clans.json}.
 */
public final class ClansFeature implements Feature {
	@Override
	public String id() {
		return "clans";
	}

	@Override
	public void register() {
		ClanService.bootstrap();
		Treasury.registerReason(ClanService.TREASURY_REASON, "Clan creation fees");
		Treasury.registerReason(ClanService.TREASURY_REASON_ORPHAN, "Orphaned Clan Banks");
		ClanCommands.register();
		ClanChat.register();
		// JOIN fires on the server thread (PlayerList.placeNewPlayer)
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> onJoin(handler.getPlayer()));
		TestPlayers.onCleanup(ClanService::purgeTestPlayer);
		ClanSelfTests.register();
	}

	@Override
	public void onServerStarted(MinecraftServer server) {
		ClanTeams.syncAll(server);
		ClanService.scanOrphanBanks(); // Take orphaned clan banks out of leaderboards (economy.json is loaded)
		ClanService.purgeExpired(System.currentTimeMillis());
		ClanTeleport.start();
		Scheduler.runRepeating(20 * 60, 20 * 60, () -> ClanService.purgeExpired(System.currentTimeMillis()));
	}

	@Override
	public void onServerStopping(MinecraftServer server) {
		ClanTeleport.stop();
	}

	private static void onJoin(ServerPlayer player) {
		UUID id = player.getUUID();
		String name = player.getGameProfile().name();
		ClanService.claimName(id, name);
		if (ClanService.clanOf(id).isPresent()) {
			ClanService.updateName(id, name); // Name change: update the team entry
		} else {
			ClanTeams.cleanupPlayer(name); // E.g. kicked while offline
			if (ClanService.chatToggled(id)) ClanService.toggleChat(id);
		}
		List<Clan> invites = ClanService.invitesFor(id, System.currentTimeMillis());
		if (!invites.isEmpty()) {
			Scheduler.runLater(40, () -> {
				ServerPlayer online = player.level().getServer().getPlayerList().getPlayer(id);
				if (online != null) {
					Msg.info(online, "You have §e" + invites.size() + " §7clan invite" + (invites.size() == 1 ? "" : "s") + ". §e/clan");
				}
			});
		}
	}
}
