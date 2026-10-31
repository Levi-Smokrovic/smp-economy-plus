package dev.smpeconomy.features.npc;

import net.minecraft.server.MinecraftServer;
import dev.smpeconomy.core.Feature;

/**
 * Admin-placed NPCs: vanilla mannequins with player skin, equipment and a {@code text_display} name tag. A click
 * opens a menu of a running module, runs a command as the player or just sends text. See {@link NpcManager} for how
 * duplicates are avoided.
 */
public final class NpcFeature implements Feature {
	/** Required in addition to Perms.ADMIN. */
	public static final String NODE = "smpeconomy.admin.npc";

	@Override
	public String id() {
		return "npc";
	}

	@Override
	public void register() {
		NpcConfig.get();
		NpcManager.registerEvents();
		NpcCommands.register();
		NpcSelfTests.register();
	}

	@Override
	public void onServerStarted(MinecraftServer server) {
		NpcManager.start(server);
	}

	@Override
	public void onServerStopping(MinecraftServer server) {
		NpcManager.stop();
	}
}
