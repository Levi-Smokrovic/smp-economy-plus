package dev.smpeconomy.integration;

import static dev.smpeconomy.core.test.Assert.eq;
import static dev.smpeconomy.core.test.Assert.isTrue;

import eu.pb4.placeholders.api.PlaceholderContext;
import eu.pb4.placeholders.api.Placeholders;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.module.Modules;
import dev.smpeconomy.core.test.SelfTests;
import dev.smpeconomy.core.test.TestContext;
//? if >=26.1 {
/*import eu.pb4.placeholders.api.ServerPlaceholderContext;
*///?}

final class PlaceholderSupportSelfTests {
	private PlaceholderSupportSelfTests() {}

	static void register() {
		SelfTests.register(Integrations.FEATURE, "placeholders-values", PlaceholderSupportSelfTests::values);
		SelfTests.register(Integrations.FEATURE, "placeholders-cheap", PlaceholderSupportSelfTests::cheap);
	}

	private static String parse(String text, ServerPlayer player) {
		//? if >=26.1 {
		/*PlaceholderContext context = ServerPlaceholderContext.of(player);
		return Placeholders.SERVER_PLACEHOLDER_PARSER.parseNode(eu.pb4.placeholders.api.node.TextNode.of(text)).toComponent(context.asParserContext()).getString();
		*///?} else {
		return Placeholders.parseText(Component.literal(text), PlaceholderContext.of(player)).getString();
		//?}
	}

	private static void values(TestContext ctx) {
		ServerPlayer player = ctx.player("PapiValues");
		EconomyService.deleteAccount(player.getUUID());
		EconomyService.ensureAccount(player.getUUID(), "PapiValues");
		EconomyService.set(player.getUUID(), 123_456);
		eq(Money.format(123_456), parse("%smpeconomy:balance%", player), "balance");
		eq(Money.formatShort(123_456), parse("%smpeconomy:balance_short%", player), "balance_short");
		eq(Money.symbol(), parse("%smpeconomy:currency%", player), "currency");
		eq(Money.format(0), parse("%smpeconomy:bank%", player), "bank starts empty");
		if (Modules.isEnabled("jobs")) {
			eq("", parse("%smpeconomy:job%", player), "no job");
			eq("0", parse("%smpeconomy:job_level%", player), "no job level");
			isTrue(PlayerDataService.getJob(player.getUUID()) == null, "reading does not create a job");
		}
	}

	private static void cheap(TestContext ctx) {
		ServerPlayer player = ctx.player("PapiCheap");
		EconomyService.ensureAccount(player.getUUID(), "PapiCheap");
		String text = "%smpeconomy:balance% %smpeconomy:balance_short% %smpeconomy:bank% %smpeconomy:job% %smpeconomy:job_level% %smpeconomy:currency%";
		long start = System.nanoTime();
		for (int i = 0; i < 2_000; i++) parse(text, player);
		long micros = (System.nanoTime() - start) / 1_000 / 2_000;
		isTrue(micros < 500, "one line of six placeholders took " + micros + " us");
		ctx.detail(micros + " us per line of six");
	}
}
