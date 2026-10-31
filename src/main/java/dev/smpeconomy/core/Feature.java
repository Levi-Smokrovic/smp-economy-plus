package dev.smpeconomy.core;

import net.minecraft.server.MinecraftServer;

/**
 * A feature (economy, sell, shop...). {@link #register()} runs at mod init. SmpEconomy calls the lifecycle hooks:
 * started once all DataStores are loaded, stopping before everything is saved, in reverse registration order.
 */
public interface Feature {
	String id();

	void register();

	default void onServerStarted(MinecraftServer server) {}

	default void onServerStopping(MinecraftServer server) {}
}
