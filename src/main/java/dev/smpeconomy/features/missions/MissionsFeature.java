package dev.smpeconomy.features.missions;

import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.Bots;
import dev.smpeconomy.core.Feature;
import dev.smpeconomy.core.JoinGrace;
import dev.smpeconomy.core.Scheduler;
import dev.smpeconomy.core.test.TestPlayers;

/**
 * Missions feature: mission board (/missions) with delivery, stat difference (hunting and mining through individual
 * events), treasure and exploration missions in four tiers, plus the Daily Elite ({@link EliteService}): one extremely
 * hard mission per day for everyone.
 */
public final class MissionsFeature implements Feature {
	static final int CHECK_TICKS = 20;
	static final int SWEEP_TICKS = 20 * 60;

	@Override
	public String id() {
		return "missions";
	}

	@Override
	public void register() {
		MissionConfig.bootstrap();
		EliteConfig.bootstrap();
		MissionStore.bootstrap();
		MissionCommands.register();
		MissionsHudService.register();
		MissionSelfTests.register();
		EliteSelfTests.register();
		TestPlayers.onCleanup(MissionStore::remove);
		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
			if (level.isClientSide() || !(player instanceof ServerPlayer sp) || Bots.isBot(player)) return;
			try {
				MissionService.onBlockBroken(sp, level, pos, state);
			} catch (RuntimeException e) {
				SmpEconomy.LOG.error("[SMPEco] missions: could not evaluate block break", e);
			}
		});
		ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register((level, killer, killed, source) -> MissionHooks.onKill(killer, killed));
		// Daily Elite: hint on the day's first join, after the join messages of other features
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			java.util.UUID id = handler.getPlayer().getUUID();
			Scheduler.runLater(70 + JoinGrace.extraTicks(handler.getPlayer()), () -> {
				try {
					EliteService.joinHint(server, id, System.currentTimeMillis());
				} catch (RuntimeException e) {
					SmpEconomy.LOG.error("[SMPEco] missions: Daily Elite hint failed", e);
				}
			});
		});
	}

	@Override
	public void onServerStarted(MinecraftServer server) {
		MissionConfig.checkAgainstServer(server);
		EliteService.resetRuntime();
		Scheduler.runRepeating(CHECK_TICKS, CHECK_TICKS, () -> tick(server));
		Scheduler.runRepeating(SWEEP_TICKS, SWEEP_TICKS, () -> {
			MissionService.sweepOffline(server, System.currentTimeMillis());
			MissionService.pruneActionbar(server);
		});
	}

	private static void tick(MinecraftServer server) {
		if (SmpEconomy.isStopping()) return;
		long now = System.currentTimeMillis();
		try {
			EliteService.tick(server, now);
		} catch (RuntimeException e) {
			SmpEconomy.LOG.error("[SMPEco] missions: Daily Elite day change failed", e);
		}
		for (ServerPlayer p : java.util.List.copyOf(server.getPlayerList().getPlayers())) {
			if (Bots.isBot(p)) continue;
			try {
				MissionService.update(p, now);
			} catch (RuntimeException e) {
				SmpEconomy.LOG.error("[SMPEco] missions: check for {} failed", p.getPlainTextName(), e);
			}
		}
	}
}
