package dev.smpeconomy.features.jobs;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import dev.smpeconomy.SmpEconomy;

/**
 * Optional integration with RightClickHarvest (checked against 4.6.1). {@code RightClickHarvest#completeHarvest} fires
 * Fabric's {@code PlayerBlockBreakEvents.BEFORE} first (level, player, position, ripe state, via {@code
 * RightClickHarvestPlatformImpl#postBreakEvent}), harvests and replants, then fires {@code
 * RightClickHarvestFabricCallbacks.AFTER_HARVEST} with {@code HarvestContext(Player player, Block block)}, which has no
 * position. So position and state are remembered in BEFORE ({@link JobsEvents#registerHarvestTracking}) and pay happens
 * only in AFTER_HARVEST, when a harvest really happened.
 * <p>The mod isn't on the compile classpath, so registration goes through reflection and a proxy on its public Fabric
 * event. If the mod is missing or its API changes, that gets logged and right-click harvests pay nothing; breaking
 * still pays.
 */
public final class RightClickHarvestCompat {
	static final String MOD_ID = "rightclickharvest";
	private static final String CALLBACKS = "io.github.jamalam360.rightclickharvest.fabric.RightClickHarvestFabricCallbacks";
	private static final String CALLBACK_TYPE = CALLBACKS + "$AfterHarvestCallback";

	private static boolean active;
	private static Method contextPlayer;
	private static Method contextBlock;

	private RightClickHarvestCompat() {}

	public static boolean isActive() {
		return active;
	}

	public static boolean isModLoaded() {
		return FabricLoader.getInstance().isModLoaded(MOD_ID);
	}

	@SuppressWarnings("unchecked")
	public static void register() {
		if (!isModLoaded()) {
			SmpEconomy.LOG.info("[SMPEco] jobs: RightClickHarvest not loaded, harvests pay only by breaking");
			return;
		}
		try {
			Class<?> callbacks = Class.forName(CALLBACKS);
			Class<?> callbackType = Class.forName(CALLBACK_TYPE);
			Object field = callbacks.getField("AFTER_HARVEST").get(null);
			if (!(field instanceof Event<?> event)) throw new IllegalStateException("AFTER_HARVEST is not a Fabric event");
			Object listener = Proxy.newProxyInstance(callbackType.getClassLoader(), new Class<?>[] {callbackType}, (proxy, method, args) -> {
				switch (method.getName()) {
					case "afterHarvest" -> {
						if (args != null && args.length == 1) onHarvest(args[0]);
						return null;
					}
					case "equals" -> {
						return args != null && args.length == 1 && proxy == args[0];
					}
					case "hashCode" -> {
						return System.identityHashCode(proxy);
					}
					case "toString" -> {
						return "smpeco-jobs RightClickHarvest listener";
					}
					default -> {
						return null;
					}
				}
			});
			((Event<Object>) event).register(listener);
			JobsEvents.registerHarvestTracking();
			active = true;
			SmpEconomy.LOG.info("[SMPEco] jobs: RightClickHarvest harvests count for the farmer");
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			SmpEconomy.LOG.error("[SMPEco] jobs: RightClickHarvest integration failed, harvests pay only by breaking", e);
		}
	}

	private static void onHarvest(Object context) {
		try {
			if (contextPlayer == null || contextPlayer.getDeclaringClass() != context.getClass()) {
				contextPlayer = context.getClass().getMethod("player");
				contextBlock = context.getClass().getMethod("block");
			}
			Object player = contextPlayer.invoke(context);
			Object block = contextBlock.invoke(context);
			if (player instanceof Player p && block instanceof Block b) JobsEvents.onRightClickHarvest(p, b);
		} catch (ReflectiveOperationException | RuntimeException e) {
			SmpEconomy.LOG.warn("[SMPEco] jobs: could not read RightClickHarvest event: {}", e.toString());
		}
	}
}
