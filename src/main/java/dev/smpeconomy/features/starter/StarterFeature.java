package dev.smpeconomy.features.starter;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import dev.smpeconomy.core.Feature;

/**
 * New players get the starter kit on first join. Core creates the start balance
 * ({@code core.json startBalance}); this module only shows and changes it.
 */
public final class StarterFeature implements Feature {
	public static final String ID = "starter";
	/** Required in addition to Perms.ADMIN. */
	public static final String NODE = "smpeconomy.admin.starter";

	public StarterFeature() {}

	@Override
	public String id() {
		return ID;
	}

	@Override
	public void register() {
		StarterConfig.get();
		// Registered after the core JOIN (account with start balance, player data), so it runs after it.
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> StarterService.onJoin(server, handler.getPlayer()));
		StarterCommands.register();
		StarterSelfTests.register();
	}
}
