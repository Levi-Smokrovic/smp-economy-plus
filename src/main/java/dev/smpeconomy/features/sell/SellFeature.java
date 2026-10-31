package dev.smpeconomy.features.sell;

import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.Feature;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.Scheduler;
import dev.smpeconomy.core.test.TestPlayers;

public final class SellFeature implements Feature {
	private Scheduler.Task sweeper;

	@Override
	public String id() {
		return "sell";
	}

	@Override
	public void register() {
		SellConfig.get();
		SellJournal.bootstrap();
		SellCommands.register();
		// JOIN runs on the server thread (PlayerList.placeNewPlayer); a tick later the player is fully in the world.
		// Then return items from a sell menu open at a crash, and from the return store
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			java.util.UUID id = handler.getPlayer().getUUID();
			Scheduler.runLater(1, () -> {
				ServerPlayer p = server.getPlayerList().getPlayer(id);
				if (p != null && !p.hasDisconnected()) SellJournal.restore(p);
			});
		});
		// after respawn: return what no longer fit in the inventory at death
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			java.util.UUID id = newPlayer.getUUID();
			Scheduler.runLater(1, () -> {
				MinecraftServer server = SmpEconomy.server();
				ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(id);
				if (p != null && !(p instanceof FakePlayer) && !p.hasDisconnected() && Items.canReceive(p)) SellJournal.deliverNow(p);
			});
		});
		TestPlayers.onCleanup(SellJournal::removeAllOf);
		SellSelfTests.register();
	}

	@Override
	public void onServerStarted(MinecraftServer server) {
		// safety net: settle sell GUIs that vanished without a close event (should never trigger), then return
		// waiting items once the player has room
		sweeper = Scheduler.runRepeating(20, 20, () -> {
			try {
				SellGui.sweep();
			} finally {
				SellJournal.sweep(server);
			}
		});
	}

	@Override
	public void onServerStopping(MinecraftServer server) {
		if (sweeper != null) sweeper.cancel();
		sweeper = null;
		SellGui.finishAll();
	}
}
