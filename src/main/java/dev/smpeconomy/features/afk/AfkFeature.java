package dev.smpeconomy.features.afk;

import net.minecraft.server.MinecraftServer;
import dev.smpeconomy.core.ConfigManager;
import dev.smpeconomy.core.Feature;
import dev.smpeconomy.core.Scheduler;
import dev.smpeconomy.core.test.TestPlayers;

/**
 * AFK zone with rewards. Admins set it with {@code /afkzone pos1|pos2}; players standing in it get money and a
 * chance at a crate key after each interval.
 */
public final class AfkFeature implements Feature {
	/** Also holds the zone, saved by command. */
	static final ConfigManager.Config<AfkConfig> CONFIG =
		ConfigManager.register("afk", AfkConfig.class, AfkConfig::new, AfkConfig::validate);

	private Scheduler.Task task;

	@Override
	public String id() {
		return "afk";
	}

	@Override
	public void register() {
		AfkCommand.register();
		AfkSelfTests.register();
		TestPlayers.onCleanup(AfkService.TRACKER::remove);
	}

	@Override
	public void onServerStarted(MinecraftServer server) {
		AfkService.reset();
		task = Scheduler.runRepeating(20, 20, () -> AfkService.tick(server));
	}

	@Override
	public void onServerStopping(MinecraftServer server) {
		if (task != null) task.cancel();
		task = null;
		AfkService.reset();
	}
}
