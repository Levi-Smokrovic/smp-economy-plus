package dev.smpeconomy.features.shop;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import dev.smpeconomy.core.BasePrices;
import dev.smpeconomy.core.Hooks;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Settings;
import dev.smpeconomy.core.TimeUtil;

/**
 * One set of deals per day (server time zone), deterministic from the date, so restarts keep them and they
 * reset at midnight.
 *
 * <p>The discount acts only as a shop discount through {@link Hooks#addShopDiscount}, so the price always
 * comes from {@code PriceGuard.effectiveShopBuyPrice} and never drops below pivot + 1 or 1.25 x sell price.
 * Only items in shop.json qualify, for pivot protection over time; an item leaving shop.json loses its
 * discount at once.
 */
public final class DailyDeals {
	public static final String HOOK_ID = "shop.daily";
	public static final String LABEL = "Daily Deal";

	public record Deal(Item item, int percent) {
		/** Factor on the buy price, 0.8 at 20 %. */
		public double factor() {
			return 1.0 - percent / 100.0;
		}
	}

	private static LocalDate cachedDate;
	private static long cachedUntilMs;
	private static List<Deal> cachedDeals = List.of();
	private static Map<Item, Deal> cachedIndex = Map.of();
	/** Selftests only: forced deals (item to percent), on top of the real ones. */
	private static final Map<Item, Integer> TEST_OVERRIDES = new HashMap<>();
	private static LocalDate announcedDate;

	private DailyDeals() {}

	static void register() {
		Hooks.addShopDiscount(HOOK_ID, LABEL, DailyDeals::factor);
		BasePrices.onReload(DailyDeals::invalidate);
	}

	static void invalidate() {
		cachedDate = null;
		cachedUntilMs = 0;
	}

	public static List<Deal> today() {
		refresh();
		return cachedDeals;
	}

	public static Deal dealFor(Item item) {
		Integer forced = TEST_OVERRIDES.isEmpty() ? null : TEST_OVERRIDES.get(item);
		if (forced != null) return new Deal(item, forced);
		refresh();
		return cachedIndex.get(item);
	}

	/** Only for items currently in shop.json, since others have no pivot protection. */
	static double factor(Item item) {
		Deal d = dealFor(item);
		if (d == null || BasePrices.shopBuyPrice(item).isEmpty()) return 1.0;
		return d.factor();
	}

	public static long millisUntilReset() {
		return TimeUtil.millisUntilNext(LocalTime.MIDNIGHT);
	}

	private static void refresh() {
		long now = System.currentTimeMillis();
		if (cachedDate != null && now < cachedUntilMs) return;
		LocalDate date = TimeUtil.dateOf(now);
		ShopConfig cfg = ShopConfig.get();
		if (cfg == null) return; // Config not loaded yet, so cache nothing
		List<Deal> deals = dealsFor(date, BasePrices.shopBuyPrices(), cfg);
		Map<Item, Deal> index = new LinkedHashMap<>();
		for (Deal d : deals) index.put(d.item(), d);
		cachedDeals = List.copyOf(deals);
		cachedIndex = Collections.unmodifiableMap(index);
		cachedDate = date;
		cachedUntilMs = date.plusDays(1).atStartOfDay(TimeUtil.zone()).toInstant().toEpochMilli();
	}

	/** Candidates sorted by ID, so reordering the file changes nothing; randomness comes from the date. */
	static List<Deal> dealsFor(LocalDate date, Map<Item, Long> shopPrices, ShopConfig cfg) {
		if (cfg.dailyDealCount <= 0) return List.of();
		Set<String> excluded = new HashSet<>(cfg.dailyDealExcluded);
		List<Item> candidates = new ArrayList<>();
		for (Map.Entry<Item, Long> e : shopPrices.entrySet()) {
			String id = Items.id(e.getKey());
			if (e.getValue() >= cfg.dailyDealMinBasePrice && !excluded.contains(id)) candidates.add(e.getKey());
		}
		candidates.sort(Comparator.comparing(Items::id));
		Random rnd = new Random(seed(date));
		Collections.shuffle(candidates, rnd);
		int steps = Math.max(0, (cfg.dailyDealMaxPercent - cfg.dailyDealMinPercent) / 5);
		List<Deal> deals = new ArrayList<>();
		for (int i = 0; i < candidates.size() && deals.size() < cfg.dailyDealCount; i++) {
			int pct = Math.min(cfg.dailyDealMaxPercent, cfg.dailyDealMinPercent + 5 * rnd.nextInt(steps + 1));
			deals.add(new Deal(candidates.get(i), pct));
		}
		return deals;
	}

	private static long seed(LocalDate date) {
		long x = date.toEpochDay() * 0x9E3779B97F4A7C15L + 0x1272150FL;
		x ^= x >>> 31;
		return x * 0xBF58476D1CE4E5B9L;
	}

	/** Called at server start, so the running day is not announced. */
	static void markAnnounced() {
		announcedDate = TimeUtil.today();
	}

	static void checkRollover(MinecraftServer server) {
		LocalDate today = TimeUtil.today();
		if (announcedDate == null) {
			announcedDate = today;
			return;
		}
		if (today.equals(announcedDate)) return;
		announcedDate = today;
		invalidate();
		ShopConfig cfg = ShopConfig.get();
		List<Deal> deals = today();
		if (cfg == null || !cfg.announceDailyDeals || deals.isEmpty() || server == null) return;
		MutableComponent line = Msg.legacy("§eNew daily deals in the §6/shop§e: ");
		for (int i = 0; i < deals.size(); i++) {
			Deal d = deals.get(i);
			if (i > 0) line.append(Msg.legacy("§7, "));
			line.append(Msg.legacy("§f")).append(Msg.itemName(d.item())).append(Msg.legacy(" §c-" + d.percent() + "%"));
		}
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (Settings.get(p, Settings.ANNOUNCEMENTS)) Msg.send(p, Msg.prefixed(line.copy()));
		}
	}

	static void forceForTest(Item item, int percent) {
		TEST_OVERRIDES.put(item, percent);
	}

	static void clearTestOverrides() {
		TEST_OVERRIDES.clear();
	}
}
