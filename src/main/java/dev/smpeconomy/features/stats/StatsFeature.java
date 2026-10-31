package dev.smpeconomy.features.stats;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.Bots;
import dev.smpeconomy.core.ConfigManager;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Feature;
import dev.smpeconomy.core.Hooks;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.Scheduler;
import dev.smpeconomy.core.Services;
import dev.smpeconomy.core.test.TestPlayers;

/**
 * Player kills (through {@code Hooks.onPlayerKilledPlayer}), deaths, mob kills and playtime, with offline values from
 * stats.json. Registers {@link Services.PlayerStats} for hud and holo.
 */
public final class StatsFeature implements Feature {
	public static final String ID = "stats";

	private static ConfigManager.Config<StatsConfig> config;
	private static Scheduler.Task snapshotTask;
	private static int secondsSinceSnapshot;

	@Override
	public String id() {
		return ID;
	}

	static StatsConfig config() {
		StatsConfig c = config == null ? null : config.get();
		return c == null ? new StatsConfig() : c;
	}

	@Override
	public void register() {
		config = ConfigManager.register("stats", StatsConfig.class, StatsConfig::new, StatsConfig::validate);
		StatsStore.init();
		Services.register(Services.PlayerStats.class, StatsService.INSTANCE);

		// Core fires PvP kills (AFTER_DEATH with a player as the cause); stats only listens.
		Hooks.onPlayerKilledPlayer(StatsStore::recordKill);
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof ServerPlayer player && !Bots.isBot(player)) StatsStore.recordDeath(player);
		});
		// Server thread, before the player is saved.
		ServerPlayerEvents.JOIN.register(StatsFeature::onJoin);
		ServerPlayerEvents.LEAVE.register(StatsFeature::onLeave);
		TestPlayers.onCleanup(StatsStore::remove);

		StatsCommands.register();
		StatsSelfTests.register();
	}

	@Override
	public void onServerStarted(MinecraftServer server) {
		if (config().importVanillaOnStart) importVanilla(server);
		for (ServerPlayer p : List.copyOf(server.getPlayerList().getPlayers())) onJoin(p);
		secondsSinceSnapshot = 0;
		if (snapshotTask != null) snapshotTask.cancel();
		// Check every second so a /smpeco reload of the interval applies at once.
		snapshotTask = Scheduler.runRepeating(20, 20, () -> {
			if (++secondsSinceSnapshot < config().snapshotSeconds) return;
			secondsSinceSnapshot = 0;
			snapshotAll(server);
		});
	}

	@Override
	public void onServerStopping(MinecraftServer server) {
		// Before DataStore.saveAll: fill the offline cache with the online players' last state.
		snapshotAll(server);
		StatsService.clearSessions();
		snapshotTask = null;
	}

	private static void onJoin(ServerPlayer player) {
		if (Bots.isBot(player)) return;
		StatsStore.snapshot(player);
		StatsService.markJoined(player.getUUID());
	}

	private static void onLeave(ServerPlayer player) {
		if (Bots.isBot(player)) return;
		StatsStore.snapshot(player);
		StatsService.markLeft(player.getUUID());
	}

	static void snapshotAll(MinecraftServer server) {
		for (ServerPlayer p : List.copyOf(server.getPlayerList().getPlayers())) {
			try {
				StatsStore.snapshot(p);
			} catch (RuntimeException e) {
				SmpEconomy.LOG.error("[SMPEco] stats: snapshot failed for {}", p.getPlainTextName(), e);
			}
		}
	}

	/** Read-only import, once per known player without an entry. */
	static int importVanilla(MinecraftServer server) {
		Path dir = server.getWorldPath(LevelResource.PLAYER_STATS_DIR);
		Set<UUID> ids = new LinkedHashSet<>(PlayerDataService.knownPlayers());
		for (EconomyService.Entry e : EconomyService.top()) ids.add(e.id());
		int imported = 0;
		for (UUID id : ids) {
			if (TestPlayers.isTestPlayer(id) || EconomyService.isSystemAccount(id) || StatsStore.peek(id).isPresent()) continue;
			try {
				String name = StatsService.name(id);
				if (StatsStore.importFile(id, StatsService.UNKNOWN.equals(name) ? "" : name, dir.resolve(id + ".json"), server)) imported++;
			} catch (RuntimeException e) {
				SmpEconomy.LOG.warn("[SMPEco] stats: vanilla stats of {} not imported", id, e);
			}
		}
		if (imported > 0) SmpEconomy.LOG.info("[SMPEco] stats: imported {} players from vanilla stats", imported);
		return imported;
	}
}
