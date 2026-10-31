package dev.smpeconomy.features.market;

import java.util.Random;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.BasePrices;
import dev.smpeconomy.core.CommandRegistry;
import dev.smpeconomy.core.Feature;
import dev.smpeconomy.core.Hooks;
import dev.smpeconomy.core.MarketGoods;
import dev.smpeconomy.core.Scheduler;
import dev.smpeconomy.core.Services;
import dev.smpeconomy.core.test.TestPlayers;

/**
 * Market feature: market values for everyone ({@code Services.MarketValues}), drift, NPC buyers for player listings and NPC
 * sellers with their own offers. Tells news through {@link MarketGoods} ({@link ShockGuard}) which items must never get a news
 * hype. The auction house is only reached through {@link MarketBridge}; without a bridge market only provides market values.
 */
public final class MarketFeature implements Feature {
	public static final String ID = "market";
	public static final String PERM = "smpeconomy.market";
	public static final String PERM_ADMIN = "smpeconomy.admin.market";

	static final Random RNG = new Random();
	private static MarketBridge bridge;
	private static Hooks.Registration bridgeRegistration;
	private static Scheduler.Task task;
	private static long seconds;

	/** Unfiltered auction house events; the selftests attach these to their {@link FakeBridge}. */
	static final MarketBridge.Events EVENTS = new MarketBridge.Events() {
		@Override
		public void onListingCreated(MarketBridge.Listing l) {
			if (!l.npc() && !SmpEconomy.isStopping()) NpcBuyers.consider(l, System.currentTimeMillis(), RNG);
		}

		@Override
		public void onListingSold(MarketBridge.Listing l, UUID buyer, long price, long tax) {
			NpcBuyers.forget(l.id());
			if (l.npc() && MarketBridge.SOURCE.equals(l.source()) && buyer != null) NpcSellers.onSold(l, buyer, price, System.currentTimeMillis());
		}

		@Override
		public void onListingRemoved(MarketBridge.Listing l) {
			NpcBuyers.forget(l.id());
			if (l.npc() && MarketBridge.SOURCE.equals(l.source())) NpcSellers.forgetBargain(l.id());
		}
	};

	static final MarketBridge.SaleFloor SALE_FLOOR = new MarketBridge.SaleFloor() {
		@Override
		public long minTotalPrice(UUID buyer, net.minecraft.world.item.ItemStack stack) {
			return ValueEngine.saleFloor(buyer, stack);
		}

		@Override
		public long minBargainTotalPrice(UUID buyer, net.minecraft.world.item.ItemStack stack) {
			return ValueEngine.bargainFloor(buyer, stack);
		}
	};

	/**
	 * Events of the real auction house: like {@link #EVENTS}, but test players from other features' selftests never change real
	 * market data. Their listings are not evaluated (no NPC purchase from the treasury), and their purchases of NPC offers change
	 * neither drift nor stats nor the flip budget (a bargain bought that way is released).
	 */
	static final MarketBridge.Events LIVE_EVENTS = new MarketBridge.Events() {
		@Override
		public void onListingCreated(MarketBridge.Listing l) {
			if (!isTestPlayer(l.seller())) EVENTS.onListingCreated(l);
		}

		@Override
		public void onListingSold(MarketBridge.Listing l, UUID buyer, long price, long tax) {
			if (isTestPlayer(l.seller()) || isTestPlayer(buyer)) {
				NpcBuyers.forget(l.id());
				if (l.npc() && MarketBridge.SOURCE.equals(l.source())) NpcSellers.forgetBargain(l.id());
			} else EVENTS.onListingSold(l, buyer, price, tax);
		}

		@Override
		public void onListingRemoved(MarketBridge.Listing l) {
			EVENTS.onListingRemoved(l);
		}
	};

	@Override
	public String id() {
		return ID;
	}

	@Override
	public void register() {
		MarketConfig.get();
		MarketConfig.saveIfMigrated();
		MarketData.get();
		ValueEngine.rebuild(null);
		BasePrices.onReload(ValueEngine::recompute);
		Services.register(Services.MarketValues.class, ValueEngine::value);
		Services.register(MarketGoods.class, ShockGuard.SERVICE);
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
			if (success) ValueEngine.rebuild(server);
		});
		TestPlayers.onCleanup(MarketFeature::cleanupTestPlayer);
		MarketCommands.register();
		MarketSelfTests.register();
		MarketBridge b = createBridge();
		if (b != null) setBridge(b);
	}

	private static MarketBridge createBridge() {
		return new AuctionMarketAdapter();
	}

	/** Command of the real auction house; a stub auction feature registers none. */
	static final String AUCTION_COMMAND = "ah";

	/**
	 * True only if the auction feature is registered and {@code /ah} exists. A stub with an empty {@code register()} does not
	 * count.
	 */
	static boolean auctionActive(MinecraftServer server) {
		if (server == null) return false;
		boolean registered = false;
		for (Feature f : SmpEconomy.features()) registered |= "auction".equals(f.id());
		return registered && CommandRegistry.root().getChild(AUCTION_COMMAND) != null;
	}

	static boolean isTestPlayer(UUID id) {
		return id != null && TestPlayers.isTestPlayer(id);
	}

	@Override
	public void onServerStarted(MinecraftServer server) {
		ValueEngine.rebuild(server);
		seconds = 0;
		if (task != null) task.cancel();
		task = Scheduler.runRepeating(20, 20, MarketFeature::everySecond);
		if (bridge == null) {
			String msg = "[SMPEco] market: no auction house connected, NPC buyers and sellers are OFF, market only provides "
				+ "market values (MarketFeature#createBridge).";
			if (auctionActive(server)) SmpEconomy.LOG.error(msg + " The auction house (/ah) is running, this is an integration error.");
			else SmpEconomy.LOG.warn(msg);
		}
	}

	@Override
	public void onServerStopping(MinecraftServer server) {
		if (task != null) task.cancel();
		task = null;
	}

	public static MarketBridge bridge() {
		return bridge;
	}

	public static void setBridge(MarketBridge b) {
		if (bridgeRegistration != null) bridgeRegistration.remove();
		bridgeRegistration = null;
		bridge = b;
		if (b != null) bridgeRegistration = b.install(LIVE_EVENTS, SALE_FLOOR);
	}

	private static void everySecond() {
		MarketBridge b = bridge;
		if (b == null || SmpEconomy.isStopping() || !MarketData.STORE.isLoaded()) return;
		long now = System.currentTimeMillis();
		seconds++;
		try {
			if (seconds == 5 || seconds % 300 == 0) {
				NpcBuyers.sweep(b, now, RNG, true);
				NpcSellers.pruneNeverSold(b);
				NpcSellers.sweepBargains(b, now);
			}
			NpcBuyers.tick(b, now, RNG);
			NpcSellers.tick(b, now, RNG);
			if (seconds % 3600 == 0) Drift.prune(now);
		} catch (RuntimeException e) {
			SmpEconomy.LOG.error("[SMPEco] market: error in the market tick", e);
		}
	}

	private static void cleanupTestPlayer(UUID id) {
		MarketData d = MarketData.get();
		boolean changed = d.sellerPayouts.remove(id) != null;
		changed |= d.plans.values().removeIf(p -> id.equals(p.seller));
		if (changed) MarketData.markDirty();
	}
}
