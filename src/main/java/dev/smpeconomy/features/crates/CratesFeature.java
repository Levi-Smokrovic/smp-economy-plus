package dev.smpeconomy.features.crates;

import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.Bots;
import dev.smpeconomy.core.Feature;
import dev.smpeconomy.core.Scheduler;
import dev.smpeconomy.core.test.TestPlayers;

public final class CratesFeature implements Feature {
	/** Delay after join, so it follows the pending messages of the core. */
	static final int JOIN_DELAY_TICKS = 40;

	@Override
	public String id() {
		return "crates";
	}

	@Override
	public void register() {
		CratesConfig.bootstrap();
		CratesCommand.register();
		// JOIN runs on the server thread inside PlayerList.placeNewPlayer, so delivery is deferred through the
		// scheduler
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> scheduleDelivery(server, handler.getPlayer().getUUID(), JOIN_DELAY_TICKS));
		// Death during the animation leaves items pending, so deliver after respawn
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			MinecraftServer server = SmpEconomy.server();
			if (server != null) scheduleDelivery(server, newPlayer.getUUID(), 20);
		});
		TestPlayers.onCleanup(CrateService::clearForced);
		CratesSelfTests.register();
	}

	@Override
	public void onServerStarted(MinecraftServer server) {
		CratesConfig.verifyWithServer(server.registryAccess());
	}

	private static void scheduleDelivery(MinecraftServer server, UUID id, int delay) {
		Scheduler.runLater(delay, () -> {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p == null || Bots.isBot(p) || !p.isAlive() || p.hasDisconnected()) return;
			if (CrateService.deliverableCount(id) == 0) return;
			CratesGui.deliverPending(p, false);
		});
	}
}
