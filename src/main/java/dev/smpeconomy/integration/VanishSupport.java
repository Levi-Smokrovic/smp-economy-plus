package dev.smpeconomy.integration;

import me.drex.vanish.api.VanishAPI;
import net.minecraft.server.level.ServerPlayer;

final class VanishSupport {
	private VanishSupport() {}

	/** Fails here, not at the first HUD update, if this Vanish version changed the API. */
	static void check() {
		try {
			VanishAPI.class.getMethod("getVisiblePlayers", ServerPlayer.class);
		} catch (NoSuchMethodException e) {
			throw new NoSuchMethodError(e.getMessage());
		}
	}

	static int visiblePlayers(ServerPlayer viewer) {
		return VanishAPI.getVisiblePlayers(viewer).size();
	}
}
