package dev.smpeconomy.features.events;

import java.util.Random;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import dev.smpeconomy.core.BasePrices;
import dev.smpeconomy.core.CommandRegistry;
import dev.smpeconomy.core.ConfigManager;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Feature;
import dev.smpeconomy.core.Hooks;
import dev.smpeconomy.core.Services;
import dev.smpeconomy.core.Treasury;
import dev.smpeconomy.core.test.TestPlayers;

/**
 * Random events (Market Day, Gold Rush, Lucky Hour) with bossbar countdown and title, trader rank from total
 * /sell sales, and the daily lottery (/lotto). Factors flow in only through core {@link Hooks} (PriceGuard
 * includes them for prices); tickets go to the treasury, which also pays the prizes.
 */
public final class EventsFeature implements Feature {
	public static final ConfigManager.Config<EventsConfig> CONFIG = registerConfig();

	static final DataStore<EventManager.Data> EVENT_STORE = new DataStore<>("events.json", EventManager.Data.class, EventManager.Data::new);
	static final DataStore<Lottery.Data> LOTTO_STORE = new DataStore<>("lotto.json", Lottery.Data.class, Lottery.Data::new);

	static final EventManager EVENTS = new EventManager(EVENT_STORE::get, EVENT_STORE::markDirty, CONFIG::get, new Random());
	/** {@link Lottery#POT_ID} is the legacy pot account. */
	static final Lottery LOTTO = new Lottery(Lottery.POT_ID, LOTTO_STORE::get, LOTTO_STORE::markDirty, CONFIG::get, Lottery.TREASURY,
		new java.security.SecureRandom());

	private static ConfigManager.Config<EventsConfig> registerConfig() {
		return ConfigManager.register("events", EventsConfig.class, EventsConfig::new, EventsConfig::validate);
	}

	@Override
	public String id() {
		return "events";
	}

	@Override
	public void register() {
		EVENT_STORE.onLoad(EventManager.Data::fixNulls);
		LOTTO_STORE.onLoad(Lottery.Data::fixNulls);
		// The legacy lottery pot account stays hidden (leftover money goes to the treasury at start)
		EconomyService.registerSystemAccount(Lottery.POT_ID, Lottery.POT_NAME);
		Treasury.registerReason(Lottery.R_TICKETS, "Lottery tickets");
		Treasury.registerReason(Lottery.R_PRIZES, "Lottery prizes");
		Treasury.registerReason(Lottery.R_REFUND, "Lottery refunds (old tickets)");
		Treasury.registerReason(Lottery.R_OLD_POT, "Old lottery pot");

		// Market Day items depend on prices.json and shop.json (pivot): recheck after each price reload
		BasePrices.onReload(EventsConfig::onPricesReloaded);
		// Factors differ from 1.0 only during a running event (time checked on each query)
		Hooks.addSellMultiplier(Events.HOOK_MARKET_DAY, EventType.MARKET_DAY.label(), (player, item) -> EVENTS.sellFactor(item, System.currentTimeMillis()));
		Hooks.addJobMultiplier(Events.HOOK_GOLD_RUSH, EventType.GOLD_RUSH.label(), (player, job) -> EVENTS.jobFactor(job, System.currentTimeMillis()));
		Hooks.addChatGameMultiplier(Events.HOOK_LUCKY_HOUR, EventType.LUCKY_HOUR.label(), player -> EVENTS.chatFactor(System.currentTimeMillis()));
		Hooks.addFlag(Events.FLAG_LUCKY_HOUR, Events::luckyHour);
		// Trader rank: sell bonus plus counting rank sales (net, from the reported lines of the sale)
		Hooks.addSellMultiplier(TraderRank.HOOK_ID, TraderRank.HOOK_LABEL, (player, item) -> TraderRank.multiplier(player));
		Hooks.onItemsSold(TraderRank.LISTENER);
		Services.register(Services.TraderRanks.class, new Services.TraderRanks() {
			@Override
			public String rankName(java.util.UUID player) {
				return TraderRank.rank(player).display();
			}

			@Override
			public double bonusPercent(java.util.UUID player) {
				return TraderRank.rank(player).bonusPercent();
			}

			@Override
			public long lifetimeSales(java.util.UUID player) {
				return TraderRank.lifetimeSales(player);
			}
		});

		CommandRegistry.register(EventsCommands::register);
		// JOIN fires on the server thread (PlayerList.placeNewPlayer); show the bossbar right away
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> EventsRuntime.onJoin(handler.getPlayer()));
		// Never leave tickets or owed prizes of test players (from other features' selftests) in the real
		// draw
		TestPlayers.onCleanup(LOTTO::removePlayer);

		EventsSelfTests.register();
	}

	@Override
	public void onServerStarted(MinecraftServer server) {
		EventsRuntime.start(server);
	}

	@Override
	public void onServerStopping(MinecraftServer server) {
		EventsRuntime.stop();
	}
}
