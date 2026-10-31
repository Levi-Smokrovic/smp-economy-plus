package dev.smpeconomy.core;

import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.entity.player.Player;
import dev.smpeconomy.SmpEconomy;

/**
 * Players with no person behind them: machines from other mods (Fabric's {@link FakePlayer}) and Carpet's {@code /player}
 * bots. Carpet's bot is a plain {@code ServerPlayer} subclass, so {@code instanceof FakePlayer} misses it.
 */
public final class Bots {
	private static final Class<?> CARPET_BOT = carpetBotClass();

	private Bots() {}

	public static boolean isBot(Player player) {
		return player instanceof FakePlayer || isCarpetBot(player);
	}

	public static boolean isCarpetBot(Player player) {
		return CARPET_BOT != null && CARPET_BOT.isInstance(player);
	}

	private static Class<?> carpetBotClass() {
		if (!FabricLoader.getInstance().isModLoaded("carpet")) return null;
		try {
			return Class.forName("carpet.patches.EntityPlayerMPFake", false, Bots.class.getClassLoader());
		} catch (ClassNotFoundException | LinkageError e) {
			SmpEconomy.LOG.warn("[SMPEco] Carpet bot class not found in this Carpet version, its bots count as real players: {}", e.toString());
			return null;
		}
	}
}
