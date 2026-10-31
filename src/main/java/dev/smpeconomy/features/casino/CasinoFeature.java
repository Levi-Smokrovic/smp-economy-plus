package dev.smpeconomy.features.casino;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.Bots;
import dev.smpeconomy.core.Feature;
import dev.smpeconomy.core.Hooks;
import dev.smpeconomy.core.Scheduler;
import dev.smpeconomy.core.Services;
import dev.smpeconomy.core.test.TestPlayers;

/**
 * Feature "casino": house games against the Treasury (coin flip with double or nothing, slots, mines, higher/lower) and
 * the games in {@code games/<game>/} (blackjack, roulette, dice, plinko, wheel, scratch cards, crash, horse race).
 * Logic in {@link Casino}, math in {@link CasinoMath}, GUIs in the {@code *Gui} classes.
 */
public final class CasinoFeature implements Feature {
	private Hooks.Registration service;
	private Scheduler.Task watchdog;
	private Scheduler.Task pendingRetry;

	@Override
	public String id() {
		return "casino";
	}

	@Override
	public void register() {
		Casino.bootstrap();
		CasinoCommands.register();
		// sub-package games (shared interface Casino.ExtGame/ExtBook); order = /casino stats
		dev.smpeconomy.features.casino.games.blackjack.BlackjackGame.register();
		dev.smpeconomy.features.casino.games.roulette.RouletteGame.register();
		dev.smpeconomy.features.casino.games.dice.DiceGame.register();
		dev.smpeconomy.features.casino.games.plinko.Plinko.register();
		dev.smpeconomy.features.casino.games.wheel.WheelGame.register();
		dev.smpeconomy.features.casino.games.scratch.ScratchGame.register();
		dev.smpeconomy.features.casino.games.crash.CrashGame.register();
		dev.smpeconomy.features.casino.games.horserace.HorseRace.register();
		service = Services.register(Services.DoubleOrNothing.class, new CasinoOffers());
		TestPlayers.onCleanup(Casino::purge);
		// JOIN runs on the server thread (PlayerList#placeNewPlayer); retries pending payouts
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (Bots.isBot(handler.getPlayer())) return;
			Scheduler.runLater(40, () -> Casino.retryPending(handler.getPlayer().getUUID()));
		});
		CasinoSelfTests.register();
	}

	@Override
	public void onServerStarted(MinecraftServer server) {
		// crash during a round: outcome was fixed or state saved, so settle now
		int n = Casino.settleAll(Casino.RESTART_REASON);
		if (n > 0) SmpEconomy.LOG.info("[SMPEco] Casino: {} open round(s) settled after the restart", n);
		Casino.retryPending(null);
		watchdog = Scheduler.runRepeating(20, 20, Casino::watchdog);
		pendingRetry = Scheduler.runRepeating(1200, 1200, () -> Casino.retryPending(null));
	}

	@Override
	public void onServerStopping(MinecraftServer server) {
		// GUIs are already closed (and so settled); remaining rounds now, before saving
		int n = Casino.settleAll(Casino.STOP_REASON);
		if (n > 0) SmpEconomy.LOG.info("[SMPEco] Casino: {} open round(s) settled on stop", n);
		CasinoOffers.clearAll();
		if (watchdog != null) watchdog.cancel();
		if (pendingRetry != null) pendingRetry.cancel();
	}
}
