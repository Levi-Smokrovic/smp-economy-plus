package dev.smpeconomy.integration;

import static dev.smpeconomy.core.test.Assert.eq;
import static dev.smpeconomy.core.test.Assert.isFalse;

import net.fabricmc.loader.api.FabricLoader;
import dev.smpeconomy.core.Bots;
import dev.smpeconomy.core.test.SelfTests;
import dev.smpeconomy.core.test.TestContext;
import dev.smpeconomy.features.jobs.JobsEvents;

final class ToggleSelfTests {
	private ToggleSelfTests() {}

	static void register() {
		SelfTests.register(Integrations.FEATURE, "vanish-absent-count-unchanged", ToggleSelfTests::vanishAbsent);
		SelfTests.register(Integrations.FEATURE, "fake-player-is-bot", ToggleSelfTests::fakePlayerIsBot);
	}

	private static void vanishAbsent(TestContext ctx) {
		if (FabricLoader.getInstance().isModLoaded("vanish")) {
			ctx.detail("Vanish installed, nothing to check");
			return;
		}
		eq(7, Integrations.onlineFor(ctx.player("TestVanish"), 7), "total passes through");
	}

	private static void fakePlayerIsBot(TestContext ctx) {
		var player = ctx.player("TestBot");
		eq(true, Bots.isBot(player), "FakePlayer counts as a bot");
		isFalse(Bots.isCarpetBot(player), "FakePlayer is not a Carpet bot");
		isFalse(JobsEvents.eligible(player), "FakePlayer earns nothing");
	}
}
