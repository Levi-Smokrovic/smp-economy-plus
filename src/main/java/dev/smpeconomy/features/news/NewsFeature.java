package dev.smpeconomy.features.news;

import java.util.Random;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.BasePrices;
import dev.smpeconomy.core.Bots;
import dev.smpeconomy.core.ConfigManager;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.Feature;
import dev.smpeconomy.core.Hooks;
import dev.smpeconomy.core.JoinGrace;
import dev.smpeconomy.core.Scheduler;
import dev.smpeconomy.core.Services;
import dev.smpeconomy.core.TimeUtil;

/**
 * Breaking news and market shocks. Crashes (-20 to -50 %, sell side only) and hypes (+25 to +100 %) for 1-3 curated items,
 * preferably those NPC orders move, through {@code core/PriceModifiers} (source "news", 10-20 min, gentle return over 3 min),
 * plus actions: flash sale (shop discount via {@link Hooks#addShopDiscount}, PriceGuard keeps pivot + 1), NPC bulk order ({@link
 * Services.NpcOrders}) and auction fever ({@link Hooks#FLAG_AUCTION_FEVER} for market). About one event per Minecraft day ({@link
 * NewsPlanner}), at most 3 at once, never 01-08 o'clock, pausable with {@code /news auto off}.
 *
 * <p>Arbitrage: hype items are not shop items and cannot be crafted from shop items (checked offline against all vanilla
 * recipes). Shocks apply to the whole item family, so conversions between shocked and normal forms bring nothing. A crash only
 * lowers what the system pays (PriceGuard keeps shop price and NPC offers at their no-crash level), so "buy during the crash,
 * sell after" brings nothing. What market NPCs sell never gets a hype ({@link NewsConfig#MARKET_SOLD}: without a pivot nothing
 * protects it over time). Flash sales skip villager goods ({@link NewsVillagerRoutes}) and do not stack. All prices still go
 * through PriceGuard.
 */
public final class NewsFeature implements Feature {
	public static final String SOURCE = "news";
	public static final String FLASH_HOOK = "news.flashsale";

	static final ConfigManager.Config<NewsConfig> CONFIG = ConfigManager.register("news", NewsConfig.class, NewsConfig::new, NewsConfig::validate);
	static final DataStore<NewsData> STORE = new DataStore<>("news.json", NewsData.class, NewsData::new);
	private static final Random RANDOM = new Random();
	private static final NewsBossBar BOSSBAR = new NewsBossBar();
	private static NewsEngine engine;
	private static Scheduler.Task task;
	/** Since when enough players are online without interruption (0 = not now). Not persisted. */
	private static long playersOnlineSince;
	/** Time of the last schedule step (0 = none yet); a step counts at most {@link #MAX_STEP_MS}. */
	private static long lastTickAt;
	/** Online time credited per one-second step at most (lag, clock jump). */
	static final long MAX_STEP_MS = 5_000;

	static NewsEngine engine() {
		return engine;
	}

	static NewsConfig config() {
		return CONFIG.get();
	}

	static NewsBossBar bossBar() {
		return BOSSBAR;
	}

	static java.util.Collection<java.util.UUID> onlinePlayers() {
		MinecraftServer server = SmpEconomy.server();
		if (server == null) return java.util.List.of();
		java.util.List<java.util.UUID> list = new java.util.ArrayList<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!Bots.isBot(p)) list.add(p.getUUID());
		}
		return list;
	}

	@Override
	public String id() {
		return "news";
	}

	@Override
	public void register() {
		STORE.onLoad(NewsData::repair);
		engine = new NewsEngine(SOURCE, FLASH_HOOK, STORE::get, STORE::markDirty, CONFIG::get, System::currentTimeMillis, RANDOM,
			new NewsBroadcast(NewsFeature::engine, CONFIG::get), () -> Services.find(Services.NpcOrders.class),
			() -> Services.find(Services.MarketValues.class).isPresent(), NewsFeature::onlinePlayers);
		// Providers only read state; before loading (defaults) they return neutral 1.0 or false
		Hooks.addShopDiscount(FLASH_HOOK, "Flash Sale", item -> engine.flashDiscount(item));
		Hooks.addFlag(Hooks.FLAG_AUCTION_FEVER, () -> engine.feverActive());
		BasePrices.onReload(() -> {
			engine.invalidateCategories();
			NewsVillagerRoutes.invalidate();
		});
		// Reread recipes and trades (the callback runs on the server thread)
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
			if (success) NewsVillagerRoutes.init(server);
		});
		NewsCommands.register(NewsFeature::engine);
		// JOIN fires on the server thread (PlayerList#placeNewPlayer)
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> onJoin(server, handler.getPlayer()));
		NewsSelfTests.register();
	}

	@Override
	public void onServerStarted(MinecraftServer server) {
		engine.invalidateCategories();
		NewsVillagerRoutes.init(server);
		NewsVillagerRoutes.items();
		engine.reconcile();
		playersOnlineSince = 0;
		lastTickAt = 0;
		task = Scheduler.runRepeating(20, 20, () -> tick(server));
	}

	@Override
	public void onServerStopping(MinecraftServer server) {
		if (task != null) task.cancel();
		task = null;
		BOSSBAR.hide();
	}

	static void tick(MinecraftServer server) {
		long now = System.currentTimeMillis();
		engine.tick();
		NewsConfig c = CONFIG.get();
		int online = 0;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!Bots.isBot(p)) online++;
		}
		if (online >= c.minPlayersOnline) {
			if (playersOnlineSince == 0) playersOnlineSince = now;
		} else {
			playersOnlineSince = 0;
		}
		boolean ready = playersOnlineSince > 0 && now - playersOnlineSince >= c.onlineGraceMinutes * 60_000L;
		long step = lastTickAt > 0 ? Math.max(0, Math.min(MAX_STEP_MS, now - lastTickAt)) : 0;
		lastTickAt = now;
		if (autoActive(c, STORE.get())) {
			NewsPlanner.Result r = NewsPlanner.tick(STORE.get(), now, step, ready, engine.runningCount(now), NewsPlanner.Rules.of(c, TimeUtil.zone()), RANDOM);
			if (r.changed()) STORE.markDirty();
			if (r.fire()) {
				NewsEngine.Start s = engine.startRandom(false, r.slots());
				if (!s.ok()) SmpEconomy.LOG.warn("[SMPEco] news: planned event not possible: {}", s.error());
			}
		}
		BOSSBAR.update(server, engine, c, now);
	}

	static boolean autoActive(NewsConfig c, NewsData d) {
		return c.enabled && !d.autoPaused;
	}

	private static void onJoin(MinecraftServer server, ServerPlayer player) {
		BOSSBAR.onJoin(player);
		java.util.UUID id = player.getUUID();
		Scheduler.runLater(40 + JoinGrace.extraTicks(player), () -> {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p == null) return; // Note about running market shocks regardless of "announcements", which only covers title and sound
			int count = NewsBossBar.displayable(engine, engine.now()).size();
			if (count > 0) p.sendSystemMessage(NewsText.joinHint(count));
		});
	}
}
