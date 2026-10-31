package dev.smpeconomy;

import java.util.Map;
import java.util.function.Supplier;
import dev.smpeconomy.core.Feature;
import dev.smpeconomy.features.afk.AfkFeature;
import dev.smpeconomy.features.auction.AuctionFeature;
import dev.smpeconomy.features.bank.BankFeature;
import dev.smpeconomy.features.bounty.BountyFeature;
import dev.smpeconomy.features.casino.CasinoFeature;
import dev.smpeconomy.features.clans.ClansFeature;
import dev.smpeconomy.features.crates.CratesFeature;
import dev.smpeconomy.features.daily.DailyFeature;
import dev.smpeconomy.features.economy.EconomyFeature;
import dev.smpeconomy.features.events.EventsFeature;
import dev.smpeconomy.features.holo.HoloFeature;
import dev.smpeconomy.features.hud.HudFeature;
import dev.smpeconomy.features.jobs.JobsFeature;
import dev.smpeconomy.features.market.MarketFeature;
import dev.smpeconomy.features.menu.MenuFeature;
import dev.smpeconomy.features.minigames.MinigamesFeature;
import dev.smpeconomy.features.missions.MissionsFeature;
import dev.smpeconomy.features.news.NewsFeature;
import dev.smpeconomy.features.npc.NpcFeature;
import dev.smpeconomy.features.orders.OrdersFeature;
import dev.smpeconomy.features.sell.SellFeature;
import dev.smpeconomy.features.settings.SettingsFeature;
import dev.smpeconomy.features.shop.ShopFeature;
import dev.smpeconomy.features.starter.StarterFeature;
import dev.smpeconomy.features.stats.StatsFeature;
import dev.smpeconomy.features.tasks.TasksFeature;

/**
 * Module id to feature factory. Constructor references mean a feature class is only initialized when called, so a
 * disabled module creates no DataStores, configs, commands or selftests. Order and metadata live in
 * {@link dev.smpeconomy.core.module.ModuleCatalog}.
 *
 * <p>Core and other modules must never touch static members of a feature class outside their {@code requires} modules,
 * or a disabled module's store would be created after startup.
 */
final class ModuleRegistry {
	private static final Map<String, Supplier<Feature>> FACTORIES = Map.ofEntries(
		Map.entry("economy", EconomyFeature::new),
		Map.entry("starter", StarterFeature::new),
		Map.entry("sell", SellFeature::new),
		Map.entry("shop", ShopFeature::new),
		Map.entry("auction", AuctionFeature::new),
		Map.entry("jobs", JobsFeature::new),
		Map.entry("tasks", TasksFeature::new),
		Map.entry("minigames", MinigamesFeature::new),
		Map.entry("hud", HudFeature::new),
		Map.entry("market", MarketFeature::new),
		Map.entry("orders", OrdersFeature::new),
		Map.entry("settings", SettingsFeature::new),
		Map.entry("daily", DailyFeature::new),
		Map.entry("stats", StatsFeature::new),
		Map.entry("bounty", BountyFeature::new),
		Map.entry("crates", CratesFeature::new),
		Map.entry("afk", AfkFeature::new),
		Map.entry("events", EventsFeature::new),
		Map.entry("news", NewsFeature::new),
		Map.entry("holo", HoloFeature::new),
		Map.entry("missions", MissionsFeature::new),
		Map.entry("casino", CasinoFeature::new),
		Map.entry("bank", BankFeature::new),
		Map.entry("clans", ClansFeature::new),
		Map.entry("menu", MenuFeature::new),
		Map.entry("npc", NpcFeature::new));

	private ModuleRegistry() {}

	/** Factory for the module, or null if it is not part of this build. */
	static Supplier<Feature> factory(String id) {
		return FACTORIES.get(id);
	}
}
