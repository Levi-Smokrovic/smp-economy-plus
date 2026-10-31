package dev.smpeconomy.features.holo;

import net.minecraft.server.MinecraftServer;
import dev.smpeconomy.core.Feature;

/**
 * Leaderboard holograms built from vanilla {@code text_display} entities. Definitions live in {@code holos.json}, the
 * look in {@code config/smpeconomy/holo.json}. See {@link HoloManager} for how duplicates are avoided.
 */
public final class HoloFeature implements Feature {
	/** Admin node for all /holo commands (OP level 2 without a LuckPerms entry). */
	public static final String NODE = "smpeconomy.admin.holo";

	@Override
	public String id() {
		return "holo";
	}

	@Override
	public void register() {
		HoloConfig.get();
		HoloStore.init();
		HoloManager.registerEvents();
		HoloCommands.register();
		HoloSelfTests.register();
	}

	@Override
	public void onServerStarted(MinecraftServer server) {
		HoloManager.start(server);
	}

	@Override
	public void onServerStopping(MinecraftServer server) {
		HoloManager.stop();
	}
}
