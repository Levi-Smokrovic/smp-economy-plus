package dev.smpeconomy.features.jobs;

import net.minecraft.server.MinecraftServer;
import dev.smpeconomy.core.Feature;
import dev.smpeconomy.core.Scheduler;
import dev.smpeconomy.core.test.TestPlayers;

/**
 * Jobs feature: /jobs with miner, lumberjack, farmer, hunter, fisher and digger (jobs.json), levels 1-50 at +2% per
 * level, multipliers through {@code Hooks.jobMultiplier}. Pay is collected and paid out every 5 s as an action bar
 * message.
 * Anti-exploit: placed and generated blocks pay nothing (PlacedBlockTracker), only ripe crops pay, nothing grown with
 * bone meal pays, spawner, spawn egg, bred and summoned mobs pay less, creative pays nothing.
 * Veinminer ({@link VeinminerCompat}) and RightClickHarvest (harvest event via reflection) are both optional.
 */
public final class JobsFeature implements Feature {
	private Scheduler.Task payoutTask;
	private int secondsSincePayout;

	@Override
	public String id() {
		return "jobs";
	}

	@Override
	public void register() {
		JobsConfig.bootstrap();
		BoostedCrops.bootstrap();
		JobsEvents.register();
		RightClickHarvestCompat.register();
		JobsCommand.register();
		// drop pending pay of selftest players, otherwise the next payout would recreate their deleted account
		TestPlayers.onCleanup(JobsService::discard);
		JobsSelfTests.register();
		JobsHudService.register();
	}

	@Override
	public void onServerStarted(MinecraftServer server) {
		JobsService.clearAll();
		JobsEvents.clear();
		secondsSincePayout = 0;
		// checked every second so a changed payoutIntervalSeconds applies right after /smpeco reload
		payoutTask = Scheduler.runRepeating(20, 20, () -> {
			if (++secondsSincePayout >= JobsConfig.raw().payoutIntervalSeconds) {
				secondsSincePayout = 0;
				JobsService.flushAll();
			}
		});
		BoostedCrops.logState();
		String veinminer = VeinminerCompat.describe();
		if (veinminer != null) dev.smpeconomy.SmpEconomy.LOG.info("[SMPEco] jobs: Veinminer found, paying per block through {}", veinminer);
	}

	@Override
	public void onServerStopping(MinecraftServer server) {
		if (payoutTask != null) payoutTask.cancel();
		// credit everything collected before the economy saves
		JobsService.flushAll();
		JobsService.clearAll();
		JobsEvents.clear();
		BoostedCrops.onServerStopping(server);
	}
}
