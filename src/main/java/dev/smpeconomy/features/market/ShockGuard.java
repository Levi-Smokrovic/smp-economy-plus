package dev.smpeconomy.features.market;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.MarketGoods;

/**
 * {@link MarketGoods} service: which items news must never hype automatically because market sells them through NPC sellers.
 * Goods are every item in the trader pool and every item of an active NPC offer (goods removed from the pool stay protected until
 * their offers expire); {@link ValueEngine#shockSensitive} derives the affected items from them. A crash gives no time arbitrage
 * on these goods ({@link ValueEngine.Mode#HIGH} excludes it from floors); whether news also spares them from crashes is up to
 * news.
 *
 * <p>Cached until the model, market.json or the goods change. Active offers are read on the first call, after {@link #invalidate}
 * and then at most every {@value #LISTINGS_TTL_MS} ms, only on the server thread; elsewhere the last state applies.
 */
final class ShockGuard {
	/** News asks per selection for every candidate, so the read goods are kept this long. */
	static final long LISTINGS_TTL_MS = 5_000L;

	private record Key(int model, MarketConfig config, Set<Item> goods) {}

	private static Key key;
	private static Set<String> ids = Set.of();
	private static Set<Item> listed = Set.of();
	/** {@link #listed} was read at least once, independent of {@link #listedAt}. */
	private static boolean listedValid;
	private static long listedAt;

	/** Only selftests replace these ({@link #testHooks}). */
	private static Supplier<MarketBridge> bridgeSource = MarketFeature::bridge;
	private static LongSupplier clock = System::currentTimeMillis;

	private ShockGuard() {}

	static final MarketGoods SERVICE = ShockGuard::ids;

	static Set<String> ids() {
		MarketConfig c = MarketConfig.get();
		long now = clock.getAsLong();
		if (!listedValid || now - listedAt >= LISTINGS_TTL_MS || now < listedAt) {
			MinecraftServer server = SmpEconomy.server();
			if (server != null && server.isSameThread()) {
				listed = listedGoods(bridgeSource.get());
				listedAt = now;
				listedValid = true;
			}
		}
		Set<Item> goods = poolGoods(c);
		goods.addAll(listed);
		Key k = new Key(ValueEngine.modelVersion(), c, goods);
		if (!k.equals(key)) {
			ids = compute(goods);
			key = k;
		}
		return ids;
	}

	static Set<String> compute(Collection<Item> goods) {
		Set<String> out = new LinkedHashSet<>();
		for (Item i : ValueEngine.shockSensitive(goods)) out.add(Items.id(i));
		return Set.copyOf(out);
	}

	static Set<Item> poolGoods(MarketConfig c) {
		Set<Item> set = new HashSet<>();
		if (c.pool == null) return set;
		for (MarketConfig.PoolEntry e : c.pool) {
			if (e == null || e.items == null) continue;
			for (String id : e.items) {
				if (!MarketConfig.neverSold(id)) Items.byId(id).ifPresent(set::add);
			}
		}
		return set;
	}

	/**
	 * Items of market's active NPC offers. Empty without an auction house, off the server thread or if the AH is not readable
	 * yet; the pool covers the normal case.
	 */
	static Set<Item> listedGoods(MarketBridge bridge) {
		MinecraftServer server = SmpEconomy.server();
		if (bridge == null || server == null || !server.isSameThread()) return Set.of();
		try {
			Set<Item> set = new HashSet<>();
			for (MarketBridge.Listing l : bridge.npcListings(MarketBridge.SOURCE)) {
				if (l.item() != null && !l.item().isEmpty()) set.add(l.item().getItem());
			}
			return set;
		} catch (RuntimeException e) {
			SmpEconomy.LOG.debug("[SMPEco] market: NPC offers unreadable for news protection: {}", e.toString());
			return Set.of();
		}
	}

	static void invalidate() {
		key = null;
		listedValid = false;
	}

	/**
	 * Own AH connection and clock for {@link #ids} (cache dropped). The returned value restores the real ones, for {@code
	 * ctx.cleanup}.
	 */
	static Runnable testHooks(MarketBridge bridge, LongSupplier testClock) {
		bridgeSource = () -> bridge;
		clock = testClock;
		invalidate();
		return () -> {
			bridgeSource = MarketFeature::bridge;
			clock = System::currentTimeMillis;
			invalidate();
		};
	}
}
