package dev.smpeconomy.features.bank;

import net.minecraft.server.MinecraftServer;
import dev.smpeconomy.core.Feature;
import dev.smpeconomy.core.Scheduler;
import dev.smpeconomy.core.Services;
import dev.smpeconomy.core.Treasury;
import dev.smpeconomy.core.test.TestPlayers;

public final class BankFeature implements Feature {
	private Scheduler.Task ticker;

	@Override
	public String id() {
		return "bank";
	}

	@Override
	public void register() {
		BankService.bootstrap();
		Treasury.registerReason(BankService.R_INTEREST, "Bank interest");
		Services.register(Services.Bank.class, BankService::balance);
		BankCommands.register();
		TestPlayers.onCleanup(BankService.LIVE::deleteAccount);
		BankSelfTests.register();
	}

	@Override
	public void onServerStarted(MinecraftServer server) {
		if (ticker != null) ticker.cancel();
		ticker = Scheduler.runRepeating(20, 20, BankService::tick);
	}

	@Override
	public void onServerStopping(MinecraftServer server) {
		if (ticker != null) ticker.cancel();
		ticker = null;
	}
}
