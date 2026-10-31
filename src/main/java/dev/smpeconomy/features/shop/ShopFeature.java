package dev.smpeconomy.features.shop;

import net.minecraft.server.MinecraftServer;
import dev.smpeconomy.core.Feature;
import dev.smpeconomy.core.Scheduler;

public final class ShopFeature implements Feature {
	private static final int ROLLOVER_CHECK_TICKS = 20 * 30;

	@Override
	public String id() {
		return "shop";
	}

	@Override
	public void register() {
		ShopConfig.bootstrap();
		
		DailyDeals.register();
		RecipeGuard.register();
		ShopCommand.register();
		ShopSelfTests.register();
	}

	@Override
	public void onServerStarted(MinecraftServer server) {
		DailyDeals.markAnnounced();
		RecipeGuard.rebuild(server);
		Scheduler.runRepeating(ROLLOVER_CHECK_TICKS, ROLLOVER_CHECK_TICKS, () -> DailyDeals.checkRollover(server));
	}
}
