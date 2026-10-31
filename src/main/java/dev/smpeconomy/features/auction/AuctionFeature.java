package dev.smpeconomy.features.auction;

import net.minecraft.server.MinecraftServer;
import dev.smpeconomy.core.Feature;
import dev.smpeconomy.core.Scheduler;
import dev.smpeconomy.core.test.TestPlayers;

public final class AuctionFeature implements Feature {
	private static final int EXPIRY_PERIOD_TICKS = 100;

	@Override
	public String id() {
		return "auction";
	}

	@Override
	public void register() {
		AuctionConfig.get();
		AuctionStore.data();
		AuctionCommands.register();
		// Test players' listings and claims go without refund; their inventory is wiped anyway
		TestPlayers.onCleanup(uuid -> AuctionService.purge(uuid, false));
		AuctionSelfTests.register();
	}

	@Override
	public void onServerStarted(MinecraftServer server) {
		// Listings may have expired while the server was offline
		AuctionService.processExpired();
		Scheduler.runRepeating(EXPIRY_PERIOD_TICKS, EXPIRY_PERIOD_TICKS, AuctionService::processExpired);
	}
}
