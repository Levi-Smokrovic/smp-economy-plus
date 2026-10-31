package dev.smpeconomy.features.daily;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.Bots;
import dev.smpeconomy.core.Feature;
import dev.smpeconomy.core.JoinGrace;
import dev.smpeconomy.core.Scheduler;
import dev.smpeconomy.core.Settings;
import dev.smpeconomy.core.TimeUtil;

/**
 * /daily reward with a streak: rising money over days 1-7, a rare key on day 7. Players get a reminder on join and
 * once after midnight if online ({@link #SETTING_REMINDER}).
 */
public final class DailyFeature implements Feature {
	public static final String ID = "daily";
	public static final String SETTING_REMINDER = "daily.reminder";
	private static final int CHECK_PERIOD_TICKS = 20 * 60;

	/** Day an online player was last reminded. In memory only: after a restart the join reminder fires again. */
	private static final Map<UUID, LocalDate> REMINDED = new HashMap<>();

	@Override
	public String id() {
		return ID;
	}

	@Override
	public void register() {
		DailyConfig.bootstrap();
		Settings.register(SETTING_REMINDER, "Daily Reminder", "Reminds you about your daily reward when you join and after midnight (/daily).", true, "minecraft:clock");
		DailyCommands.register();
		// JOIN fires on the server thread (PlayerList#placeNewPlayer)
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> onJoin(server, handler.getPlayer()));
		DailySelfTests.register();
	}

	@Override
	public void onServerStarted(MinecraftServer server) {
		REMINDED.clear();
		Scheduler.runRepeating(CHECK_PERIOD_TICKS, CHECK_PERIOD_TICKS, () -> checkOnline(server));
	}

	@Override
	public void onServerStopping(MinecraftServer server) {
		REMINDED.clear();
	}

	private static void onJoin(MinecraftServer server, ServerPlayer player) {
		if (player == null || Bots.isBot(player)) return;
		DailyConfig cfg = DailyConfig.get();
		if (!cfg.reminder) return;
		UUID id = player.getUUID();
		REMINDED.put(id, TimeUtil.dateOf(DailyService.now()));
		Scheduler.runLater(Math.max(1, cfg.reminderDelaySeconds * 20) + JoinGrace.extraTicks(player), () -> {
			ServerPlayer online = server.getPlayerList().getPlayer(id);
			if (online != null) remindIfWanted(online);
		});
	}

	private static void checkOnline(MinecraftServer server) {
		if (SmpEconomy.isStopping()) return;
		REMINDED.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
		if (!DailyConfig.get().reminder) return;
		LocalDate today = TimeUtil.dateOf(DailyService.now());
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (Bots.isBot(p) || today.equals(REMINDED.get(p.getUUID()))) continue;
			if (!DailyService.status(p.getUUID()).claimable()) continue;
			REMINDED.put(p.getUUID(), today);
			remindIfWanted(p);
		}
	}

	private static void remindIfWanted(ServerPlayer player) {
		try {
			if (Settings.get(player, SETTING_REMINDER)) DailyService.remind(player);
		} catch (RuntimeException e) {
			SmpEconomy.LOG.error("[SMPEco] daily: reminder for {} failed", player.getPlainTextName(), e);
		}
	}
}
