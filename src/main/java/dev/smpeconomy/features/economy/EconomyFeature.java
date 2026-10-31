package dev.smpeconomy.features.economy;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import dev.smpeconomy.core.CommandRegistry;
import dev.smpeconomy.core.Feature;

public final class EconomyFeature implements Feature {
	@Override
	public String id() {
		return "economy";
	}

	@Override
	public void register() {
		CommandRegistry.register((dispatcher, registryAccess, environment) -> EconomyCommands.register(dispatcher));
		// JOIN fires on the server thread (PlayerList#placeNewPlayer). Reset the offline payment summary here; core
		// delivers the pending lines a second later
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> Payments.onJoin(handler.getPlayer().getUUID()));
		EconomySelfTests.register();
	}
}
