package dev.smpeconomy.integration;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import dev.smpeconomy.SmpEconomy;

/**
 * Entry point for optional mod support. Classes that reference another mod are only touched behind its check, so with
 * the mod absent they are never loaded.
 */
public final class Integrations {
	public static final String FEATURE = "integrations";

	private static boolean vanish;

	private Integrations() {}

	public static void register() {
		FabricLoader loader = FabricLoader.getInstance();
		if (loader.isModLoaded("common-economy-api")) {
			guarded("Common Economy API", () -> {
				WalletProvider.register();
				WalletProviderSelfTests.register();
			});
		}
		if (loader.isModLoaded("placeholder-api")) {
			guarded("Placeholder API", () -> {
				PlaceholderSupport.register();
				PlaceholderSupportSelfTests.register();
			});
		}
		if (loader.isModLoaded("vanish")) {
			guarded("Vanish", () -> {
				VanishSupport.check();
				vanish = true;
			});
		}
		CommandAndItemSelfTests.register();
		ToggleSelfTests.register();
	}

	/** Online count as this player may know it: staff see everyone, others not the vanished. */
	public static int onlineFor(ServerPlayer viewer, int total) {
		return vanish ? VanishSupport.visiblePlayers(viewer) : total;
	}

	/** A future major version of the other mod must only cost the integration, not the server start. */
	private static void guarded(String name, Runnable setup) {
		try {
			setup.run();
		} catch (LinkageError e) {
			SmpEconomy.LOG.warn("[SMPEco] This version of {} is not supported, integration disabled: {}", name, e.toString());
		}
	}
}
