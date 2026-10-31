package dev.smpeconomy.integration;

import eu.pb4.placeholders.api.PlaceholderContext;
import eu.pb4.placeholders.api.PlaceholderResult;
import eu.pb4.placeholders.api.Placeholders;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.resources.Identifier;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.module.Modules;
import dev.smpeconomy.features.bank.BankService;
import dev.smpeconomy.features.jobs.JobsConfig;
import dev.smpeconomy.features.jobs.JobsService;

/**
 * {@code %smpeconomy:...%} placeholders. Scoreboard and tab-list mods evaluate these constantly, so every handler is a
 * lookup in loaded data.
 */
final class PlaceholderSupport {
	private PlaceholderSupport() {}

	static void register() {
		forPlayer("balance", id -> Money.format(EconomyService.balance(id)));
		forPlayer("balance_short", id -> Money.formatShort(EconomyService.balance(id)));
		forPlayer("bank", id -> Modules.isEnabled("bank") ? bank(id) : Money.format(0));
		forPlayer("job", id -> Modules.isEnabled("jobs") ? jobName(id) : "");
		forPlayer("job_level", id -> Modules.isEnabled("jobs") ? jobLevel(id) : "0");
		add("currency", ctx -> PlaceholderResult.value(Money.symbol()));
	}

	private static String bank(UUID id) {
		return Money.format(BankService.balance(id));
	}

	private static String jobName(UUID id) {
		JobsConfig.Job job = JobsService.currentJob(id);
		return job == null ? "" : job.name;
	}

	private static String jobLevel(UUID id) {
		JobsConfig.Job job = JobsService.currentJob(id);
		return job == null ? "0" : Integer.toString(JobsService.level(id, job.id));
	}

	private static void forPlayer(String name, Function<UUID, String> value) {
		add(name, ctx -> {
			UUID id = playerId(ctx);
			if (id == null) return PlaceholderResult.invalid("No player!");
			// Tab lists can ask while the world is still loading or already saved away.
			if (!EconomyService.isLoaded()) return PlaceholderResult.invalid("Economy not loaded");
			return PlaceholderResult.value(value.apply(id));
		});
	}

	private static UUID playerId(PlaceholderContext ctx) {
		if (ctx.hasPlayer()) return ctx.player().getUUID();
		return ctx.hasGameProfile() ? ctx.gameProfile().id() : null;
	}

	private static void add(String name, Function<PlaceholderContext, PlaceholderResult> handler) {
		Identifier id = Identifier.fromNamespaceAndPath(SmpEconomy.MOD_ID, name);
		//? if >=26.1 {
		/*Placeholders.registerServer(id, (ctx, arg) -> handler.apply(ctx));
		*///?} else {
		Placeholders.register(id, (ctx, arg) -> handler.apply(ctx));
		//?}
	}
}
