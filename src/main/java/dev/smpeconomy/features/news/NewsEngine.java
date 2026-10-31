package dev.smpeconomy.features.news;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.minecraft.world.item.Item;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.BasePrices;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.PriceGuard;
import dev.smpeconomy.core.PriceModifiers;
import dev.smpeconomy.core.Services;
import dev.smpeconomy.core.Treasury;
import dev.smpeconomy.features.news.NewsData.NewsEntry;
import dev.smpeconomy.features.news.NewsData.NewsEntry.Type;

/**
 * Breaking news logic: market shocks through {@link PriceModifiers} (source {@link #source}), flash sales (shop discount through
 * hook {@link #flashHookId}, PriceGuard keeps pivot + 1), NPC bulk orders ({@link Services.NpcOrders}) and auction fever ({@code
 * Hooks.FLAG_AUCTION_FEVER}). State lives in {@link NewsData}.
 *
 * <p>The live instance ({@link NewsFeature#engine()}) uses source "news" and news.json. Selftests build their own instances with
 * their own source, state and test announcer, so they never disturb running events. Everything runs on the server thread.
 */
final class NewsEngine {
	static final String LABEL = "Breaking News";
	static final String BULK_LABEL = "Breaking News: NPC Bulk Order";

	interface Announcer {
		void started(List<NewsEntry> group);

		void ended(NewsEntry entry);
	}

	record Start(List<NewsEntry> entries, String error) {
		static Start fail(String error) {
			return new Start(List.of(), error);
		}

		boolean ok() {
			return error == null && !entries.isEmpty();
		}
	}

	final String source;
	final String flashHookId;
	private final Supplier<NewsData> data;
	private final Runnable markDirty;
	private final Supplier<NewsConfig> config;
	private final LongSupplier clock;
	private final Random random;
	private final Announcer announcer;
	private final Supplier<Optional<Services.NpcOrders>> npcOrders;
	private final BooleanSupplier marketActive;
	/** Online players for the conservative percent display: never promise more than someone gets. */
	private final Supplier<Collection<UUID>> players;
	private final Map<String, Set<Item>> categoryItems = new HashMap<>();
	/** Runs every second, so only log once. */
	private boolean bulkSyncErrorLogged;

	NewsEngine(String source, String flashHookId, Supplier<NewsData> data, Runnable markDirty, Supplier<NewsConfig> config,
			LongSupplier clock, Random random, Announcer announcer, Supplier<Optional<Services.NpcOrders>> npcOrders,
			BooleanSupplier marketActive, Supplier<Collection<UUID>> players) {
		this.source = source;
		this.flashHookId = flashHookId;
		this.data = data;
		this.markDirty = markDirty;
		this.config = config;
		this.clock = clock;
		this.random = random;
		this.announcer = announcer;
		this.npcOrders = npcOrders;
		this.marketActive = marketActive;
		this.players = players;
	}

	NewsData data() {
		return data.get();
	}

	long now() {
		return clock.getAsLong();
	}

	List<NewsEntry> active() {
		return List.copyOf(data.get().active);
	}

	boolean busy(long now) {
		return runningCount(now) > 0;
	}

	/** Fading shocks do not count toward {@code maxActive}. */
	int runningCount(long now) {
		int n = 0;
		for (NewsEntry e : data.get().active) {
			if (e.running(now)) n++;
		}
		return n;
	}

	void invalidateCategories() {
		categoryItems.clear();
	}

	boolean autoPaused() {
		return data.get().autoPaused;
	}

	/**
	 * {@code /news auto on|off} (persisted). No online time counts during a pause. An event already due when pausing (limit
	 * {@code maxActive} reached) is dropped on resume, otherwise one would start right after. Returns 1 if a due event was
	 * dropped, otherwise 0.
	 */
	int setAutoPaused(boolean paused) {
		NewsData d = data.get();
		int dropped = 0;
		if (!paused && d.autoPaused && d.targetMs > 0 && d.progressMs >= d.targetMs) {
			d.progressMs = 0;
			dropped = 1;
		}
		d.autoPaused = paused;
		markDirty.run();
		return dropped;
	}

	/**
	 * Shop discount factor of flash sales for {@code item}. If an item is in several categories on sale, only the strongest
	 * applies, flash sales never stack. PriceGuard multiplies other shop discounts on top, with pivot + 1 as the floor.
	 */
	double flashDiscount(Item item) {
		long now = now();
		double best = 1.0;
		for (NewsEntry e : data.get().active) {
			if (e.kind() == Type.FLASH_SALE && e.running(now) && itemsOf(e.category).contains(item) && e.factor > 0) best = Math.min(best, e.factor);
		}
		return best == 1.0 || flashExcluded(item) ? 1.0 : best;
	}

	/**
	 * Never in a flash sale: {@link NewsConfig#flashSaleExcludedItems} and villager goods ({@link NewsVillagerRoutes}), since
	 * "shop, villager, emerald" must never get cheaper.
	 */
	boolean flashExcluded(Item item) {
		String id = Items.id(item);
		return NewsConfig.FLASH_ALWAYS_EXCLUDED.contains(id) || config.get().flashSaleExcludedItems.contains(id)
			|| NewsVillagerRoutes.excluded(item);
	}

	/**
	 * Never bones ({@link NewsConfig#NEVER_CRASH}), market NPC goods only with {@link NewsConfig#crashMarketSold}. Villager goods
	 * are allowed: a crash does not lower the shop price (PriceGuard), so "shop, villager, emerald" does not get cheaper.
	 */
	boolean crashAllowed(Item item) {
		NewsConfig c = config.get();
		if (!c.crashMarketSold && c.isMarketSold(Items.id(item))) return false;
		for (Item m : family(item)) {
			if (NewsConfig.NEVER_CRASH.contains(Items.id(m))) return false;
		}
		return true;
	}

	boolean hypeAllowed(Item item) {
		NewsConfig c = config.get();
		if (c.isMarketSold(Items.id(item))) return false;
		for (Item m : family(item)) {
			if (NewsConfig.NEVER_HYPE.contains(Items.id(m))) return false;
		}
		return true;
	}

	List<Item> flashExcludedIn(String categoryId) {
		List<Item> list = new ArrayList<>();
		for (BasePrices.ShopCategory c : BasePrices.shopCategories()) {
			if (!c.id().equals(categoryId)) continue;
			for (BasePrices.ShopItem si : c.items()) {
				if (flashExcluded(si.item()) && !list.contains(si.item())) list.add(si.item());
			}
		}
		return list;
	}

	boolean feverActive() {
		long now = now();
		for (NewsEntry e : data.get().active) {
			if (e.kind() == Type.AUCTION_FEVER && e.running(now)) return true;
		}
		return false;
	}

	Set<Item> itemsOf(String categoryId) {
		return categoryItems.computeIfAbsent(categoryId, id -> {
			Set<Item> set = new HashSet<>();
			for (BasePrices.ShopCategory c : BasePrices.shopCategories()) {
				if (c.id().equals(id)) c.items().forEach(i -> set.add(i.item()));
			}
			return set;
		});
	}

	List<Item> family(Item item) {
		String id = Items.id(item);
		Set<String> ids = new LinkedHashSet<>();
		ids.add(id);
		for (Map.Entry<String, List<String>> f : config.get().families.entrySet()) {
			List<String> members = f.getValue() == null ? List.of() : f.getValue();
			if (f.getKey().equals(id) || members.contains(id)) {
				ids.add(f.getKey());
				ids.addAll(members);
			}
		}
		List<Item> list = new ArrayList<>();
		for (String m : ids) Items.byId(m).ifPresent(list::add);
		return list;
	}

	/**
	 * Items a shock on {@code item} with {@code factor} really hits: the {@link #family family}, but for a hype on a shop item
	 * without members not in the shop ({@link #hypeSkips}).
	 */
	List<Item> shockMembers(Item item, double factor) {
		List<Item> list = new ArrayList<>();
		for (Item m : family(item)) {
			if (!hypeSkips(item, m, factor)) list.add(m);
		}
		return list;
	}

	/**
	 * Whether a hype on {@code main} skips family member {@code member}: yes if {@code main} is a shop item (sale capped at the
	 * pivot) and {@code member} has no pivot. A shop hype promises a pivot cap, but the member would sell uncapped at ×2-3 and be
	 * valued by the market (nether wart to nether wart block from fertilized crimson fungi, redstone to redstone ore). Crashes
	 * always hit the whole family (otherwise "buy from the shop during a crash, craft a block, sell the block at normal price").
	 */
	static boolean hypeSkips(Item main, Item member, double factor) {
		return factor > 1.0 && member != main && PriceGuard.pivotPrice(main) >= 0 && PriceGuard.pivotPrice(member) < 0;
	}

	Set<String> shockedIds() {
		Set<String> set = new HashSet<>();
		for (NewsEntry e : data.get().active) {
			if (e.kind().isShock()) set.addAll(e.members);
		}
		return set;
	}

	Start startRandom(boolean manual) {
		return startRandom(manual, 3);
	}

	Start startRandom(boolean manual, int maxEntries) {
		if (random.nextDouble() < config.get().actionChance) {
			Start s = startRandomAction(manual);
			if (s.ok()) return s;
		}
		return startRandomShock(manual, maxEntries);
	}

	Start startRandomAction(boolean manual) {
		syncBulkOrders(now());
		NewsConfig c = config.get();
		List<Type> options = new ArrayList<>();
		List<Double> weights = new ArrayList<>();
		if (c.flashSaleWeight > 0 && !flashCategories().isEmpty()) {
			options.add(Type.FLASH_SALE);
			weights.add(c.flashSaleWeight);
		}
		if (c.bulkOrderWeight > 0 && npcOrders.get().isPresent() && !hasRunning(Type.BULK_ORDER)) {
			options.add(Type.BULK_ORDER);
			weights.add(c.bulkOrderWeight);
		}
		if (c.auctionFeverWeight > 0 && !feverActive() && (!c.auctionFeverRequiresMarket || marketActive.getAsBoolean())) {
			options.add(Type.AUCTION_FEVER);
			weights.add(c.auctionFeverWeight);
		}
		while (!options.isEmpty()) {
			int i = weightedIndex(weights);
			Type t = options.remove(i);
			weights.remove(i);
			Start s = switch (t) {
				case FLASH_SALE -> startFlashSale(null, 0, 0, manual);
				case BULK_ORDER -> startBulkOrder(null, 0, manual);
				default -> startAuctionFever(0, manual);
			};
			if (s.ok()) return s;
		}
		return Start.fail("No action possible");
	}

	Start startRandomShock(boolean manual) {
		return startRandomShock(manual, 3);
	}

	Start startRandomShock(boolean manual, int maxItems) {
		NewsConfig c = config.get();
		List<Double> countWeights = new ArrayList<>();
		for (Integer w : c.itemCountWeights) countWeights.add(w == null ? 0.0 : Math.max(0, w));
		int count = Math.max(1, Math.min(Math.max(1, maxItems), weightedIndex(countWeights) + 1));
		Set<String> used = new HashSet<>(shockedIds());
		List<Item> items = new ArrayList<>();
		List<Double> factors = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			boolean hype = random.nextDouble() < c.hypeChance;
			double factor = hype ? randomHypeFactor() : randomCrashFactor();
			Optional<Item> pick = hype ? pickHype(used, factor) : pickCrash(used);
			if (pick.isEmpty()) {
				hype = !hype;
				factor = hype ? randomHypeFactor() : randomCrashFactor();
				pick = hype ? pickHype(used, factor) : pickCrash(used);
			}
			if (pick.isEmpty()) break;
			items.add(pick.get());
			factors.add(factor);
			for (Item m : family(pick.get())) used.add(Items.id(m));
		}
		if (items.isEmpty()) return Start.fail("No suitable items (all already affected or without a price)");
		long duration = randomMinutes(c.minDurationMinutes, c.maxDurationMinutes) * 60_000L;
		long group = data.get().nextId;
		List<NewsEntry> started = new ArrayList<>();
		for (int i = 0; i < items.size(); i++) started.add(applyShock(items.get(i), factors.get(i), duration, manual, group));
		finishStart(started);
		return new Start(started, null);
	}

	double randomHypeFactor() {
		NewsConfig c = config.get();
		// In 5 % steps: +25 %, +30 % ... +100 %
		return randomStep(c.hypeMinFactor, c.hypeMaxFactor);
	}

	double randomCrashFactor() {
		NewsConfig c = config.get();
		return randomStep(c.crashFactor, Math.max(c.crashFactor, c.crashMaxFactor));
	}

	private double randomStep(double min, double max) {
		int lo = (int) Math.ceil(min * 20 - 1e-9);
		int hi = (int) Math.floor(max * 20 + 1e-9);
		if (hi < lo) return min;
		return (lo + random.nextInt(hi - lo + 1)) / 20.0;
	}

	boolean isFocus(Item item) {
		List<String> focus = config.get().focusItems;
		for (Item m : family(item)) {
			if (focus.contains(Items.id(m))) return true;
		}
		return false;
	}

	private double weight(Item item) {
		return isFocus(item) ? Math.max(0, config.get().focusWeight) : 1.0;
	}

	private Optional<Item> pickWeighted(List<Item> pool) {
		if (pool.isEmpty()) return Optional.empty();
		List<Double> weights = new ArrayList<>();
		double total = 0;
		for (Item item : pool) {
			double w = weight(item);
			weights.add(w);
			total += w;
		}
		if (!(total > 0)) return Optional.of(pool.get(random.nextInt(pool.size())));
		return Optional.of(pool.get(weightedIndex(weights)));
	}

	Optional<Item> pickCrash(Set<String> used) {
		NewsConfig c = config.get();
		Set<String> ids = new LinkedHashSet<>(c.crashItems);
		ids.addAll(c.focusItems);
		List<Item> pool = new ArrayList<>();
		for (String id : ids) {
			Optional<Item> item = Items.byId(id);
			if (item.isEmpty() || pool.contains(item.get()) || !free(item.get(), used) || !crashAllowed(item.get())) continue;
			OptionalLong base = BasePrices.sellPrice(item.get());
			if (base.isEmpty() || base.getAsLong() < Math.max(1, c.minBaseSellCents)) continue;
			pool.add(item.get());
		}
		return pickWeighted(pool);
	}

	Optional<Item> pickHype(Set<String> used, double factor) {
		NewsConfig c = config.get();
		List<Item> nonShop = new ArrayList<>();
		for (String id : c.hypeItems) {
			Optional<Item> item = Items.byId(id);
			if (item.isPresent() && free(item.get(), used) && hypeAllowed(item.get()) && BasePrices.sellPrice(item.get()).isPresent()) {
				nonShop.add(item.get());
			}
		}
		List<Item> shop = new ArrayList<>();
		for (String id : c.hypeShopItems) {
			Optional<Item> item = Items.byId(id);
			if (item.isEmpty() || !free(item.get(), used) || !hypeAllowed(item.get())) continue;
			NewsPrices.Change change = NewsPrices.sell(source, item.get(), factor);
			if (change.present() && (double) change.after() / change.before() >= 1.0 + c.minShopHypeEffect) shop.add(item.get());
		}
		boolean preferNonShop = random.nextDouble() < c.hypePreferNonShop;
		List<Item> first = preferNonShop ? nonShop : shop;
		List<Item> second = preferNonShop ? shop : nonShop;
		return first.isEmpty() ? pickWeighted(second) : pickWeighted(first);
	}

	private boolean free(Item item, Set<String> used) {
		for (Item m : family(item)) {
			if (used.contains(Items.id(m))) return false;
		}
		return true;
	}

	/**
	 * Admin shock on a specific item with family. {@code factor} ≤ 0 means random (crash or hype), {@code minutes} ≤ 0 means
	 * random duration. Running shocks on the same items are replaced.
	 */
	Start triggerShock(Item item, double factor, int minutes, boolean manual) {
		NewsConfig c = config.get();
		if (BasePrices.sellPrice(item).isEmpty()) return Start.fail(Items.id(item) + " has no sell price");
		if (factor <= 0) factor = random.nextDouble() < c.hypeChance ? randomHypeFactor() : randomCrashFactor();
		if (!(factor >= PriceModifiers.MIN_FACTOR && factor <= PriceModifiers.MAX_FACTOR) || factor == 1.0) {
			return Start.fail("Factor must be between " + PriceModifiers.MIN_FACTOR + " and " + PriceModifiers.MAX_FACTOR + " (≠ 1)");
		}
		long duration = (minutes > 0 ? minutes : randomMinutes(c.minDurationMinutes, c.maxDurationMinutes)) * 60_000L;
		// End replaced shocks; fade out their items the new shock does not set, including family members a shop hype skips, which
		// would otherwise keep the old factor
		Set<String> fam = new HashSet<>();
		for (Item m : family(item)) fam.add(Items.id(m));
		Set<String> next = new HashSet<>();
		for (Item m : shockMembers(item, factor)) next.add(Items.id(m));
		for (NewsEntry e : List.copyOf(data.get().active)) {
			if (!e.kind().isShock() || Collections.disjoint(e.members, fam)) continue;
			for (String m : e.members) {
				if (!next.contains(m)) Items.byId(m).ifPresent(it -> PriceModifiers.clear(source, it, fadeOutMs()));
			}
			cancel(e);
		}
		NewsEntry entry = applyShock(item, factor, duration, manual, data.get().nextId);
		finishStart(List.of(entry));
		return new Start(List.of(entry), null);
	}

	private NewsEntry applyShock(Item item, double factor, long durationMs, boolean manual, long group) {
		long now = now();
		long fade = fadeOutMs();
		NewsEntry e = newEntry(factor < 1 ? Type.CRASH : Type.HYPE, group, manual, now);
		e.item = Items.id(item);
		e.factor = factor;
		// Compute the display before setting: other sources stay, this source's old values do not count
		e.shownPercent = NewsPrices.shockPercent(source, item, factor, players.get());
		for (Item m : shockMembers(item, factor)) {
			PriceModifiers.set(source, m, factor, 0, durationMs, fade, LABEL);
			e.members.add(Items.id(m));
		}
		e.endMs = now + durationMs;
		e.fadeOutMs = fade;
		return e;
	}

	long fadeOutMs() {
		return Math.max(0, config.get().fadeOutMinutes) * 60_000L;
	}

	List<BasePrices.ShopCategory> flashCategories() {
		NewsConfig c = config.get();
		Set<String> running = new HashSet<>();
		long now = now();
		for (NewsEntry e : data.get().active) {
			if (e.kind() == Type.FLASH_SALE && e.running(now)) running.add(e.category);
		}
		List<BasePrices.ShopCategory> list = new ArrayList<>();
		for (BasePrices.ShopCategory cat : BasePrices.shopCategories()) {
			if (cat.items().isEmpty() || running.contains(cat.id())) continue;
			if (!c.flashSaleCategories.isEmpty() && !c.flashSaleCategories.contains(cat.id())) continue;
			list.add(cat);
		}
		return list;
	}

	Start startFlashSale(String categoryId, int percent, int minutes, boolean manual) {
		NewsConfig c = config.get();
		List<BasePrices.ShopCategory> cats = flashCategories();
		if (categoryId != null) cats.removeIf(cat -> !cat.id().equals(categoryId));
		if (cats.isEmpty()) return Start.fail(categoryId == null ? "No shop category available" : "Unknown category or already on sale: " + categoryId);
		Collections.shuffle(cats, random);
		int pct = percent > 0 ? Math.min(90, percent) : randomStep5(c.flashSaleMinPercent, c.flashSaleMaxPercent);
		double factor = 1.0 - pct / 100.0;
		for (BasePrices.ShopCategory cat : cats) {
			int effect = 0;
			Collection<UUID> online = players.get();
			for (BasePrices.ShopItem si : cat.items()) {
				if (!flashExcluded(si.item())) effect = Math.min(effect, NewsPrices.flashPercent(flashHookId, si.item(), factor, online));
			}
			if (effect >= 0) continue; // Minimum prices leave no room for a discount
			long now = now();
			NewsEntry e = newEntry(Type.FLASH_SALE, data.get().nextId, manual, now);
			e.category = cat.id();
			e.categoryName = cat.name();
			e.item = Items.id(cat.icon());
			e.factor = factor;
			e.shownPercent = effect;
			e.endMs = now + (minutes > 0 ? minutes : c.flashSaleMinutes) * 60_000L;
			finishStart(List.of(e));
			return new Start(List.of(e), null);
		}
		return Start.fail("Flash Sale would have no effect (minimum prices)");
	}

	/**
	 * NPC bulk order through orders ({@link Services.NpcOrders}), sized to the treasury budget. At most one at a time, also for
	 * admins, otherwise two orders together tie up the whole NPC hourly budget. The event stores the order id and ends once
	 * orders closes it ({@link #syncBulkOrders}).
	 */
	Start startBulkOrder(Item fixed, int minutes, boolean manual) {
		Optional<Services.NpcOrders> svc = npcOrders.get();
		if (svc.isEmpty()) return Start.fail("NPC orders (orders) not active");
		// An order just completed or cancelled does not block until the next tick
		syncBulkOrders(now());
		if (hasRunning(Type.BULK_ORDER)) return Start.fail("An NPC Bulk Order is already running");
		NewsConfig c = config.get();
		List<Item> candidates = new ArrayList<>();
		if (fixed != null) candidates.add(fixed);
		else {
			for (String id : c.bulkOrderItems) Items.byId(id).ifPresent(candidates::add);
			Collections.shuffle(candidates, random);
		}
		long durationMs = (minutes > 0 ? minutes : c.bulkOrderMinutes) * 60_000L;
		for (Item item : candidates) {
			int amount = bulkAmount(item);
			if (amount <= 0) continue;
			Services.NpcOrders.BulkOrder order;
			try {
				order = svc.get().startBulkOrder(item, amount, durationMs, BULK_LABEL);
			} catch (RuntimeException ex) {
				SmpEconomy.LOG.error("[SMPEco] news: NpcOrders.startBulkOrder failed", ex);
				order = null;
			}
			if (order == null) continue;
			long now = now();
			NewsEntry e = newEntry(Type.BULK_ORDER, data.get().nextId, manual, now);
			e.item = Items.id(item);
			// orders may create less than requested (treasury budget); never announce more than NPCs buy
			e.amount = order.amount() > 0 ? Math.min(amount, order.amount()) : amount;
			e.orderId = Math.max(0, order.id());
			e.endMs = now + durationMs;
			finishStart(List.of(e));
			return new Start(List.of(e), null);
		}
		return Start.fail("No bulk order possible (Treasury budget or items without market value)");
	}

	/**
	 * Amount orders can pay at the highest allowed price (market value × 1.25) from the treasury's hourly budget (share {@code
	 * bulkOrderBudgetShare}); 0 if not possible.
	 */
	int bulkAmount(Item item) {
		NewsConfig c = config.get();
		OptionalLong mv = Services.marketValue(item);
		if (mv.isEmpty() || mv.getAsLong() <= 0) return 0;
		long perItem = Math.max(1, (long) Math.ceil(mv.getAsLong() * PriceGuard.NPC_ORDER_MARKET_FACTOR));
		long budget = (long) Math.floor(Treasury.maxSpendPerHour() * c.bulkOrderBudgetShare);
		long amount = Math.min(c.bulkOrderMaxAmount, budget / perItem);
		if (amount >= 16) amount -= amount % 16;
		return amount >= c.bulkOrderMinAmount ? (int) amount : 0;
	}

	Start startAuctionFever(int minutes, boolean manual) {
		if (feverActive()) return Start.fail("Auction Fever is already running");
		long now = now();
		NewsEntry e = newEntry(Type.AUCTION_FEVER, data.get().nextId, manual, now);
		e.item = "minecraft:gold_ingot";
		e.endMs = now + (minutes > 0 ? minutes : config.get().auctionFeverMinutes) * 60_000L;
		finishStart(List.of(e));
		return new Start(List.of(e), null);
	}

	boolean hasRunning(Type type) {
		long now = now();
		for (NewsEntry e : data.get().active) {
			if (e.kind() == type && e.running(now)) return true;
		}
		return false;
	}

	private NewsEntry newEntry(Type type, long group, boolean manual, long now) {
		NewsData d = data.get();
		NewsEntry e = new NewsEntry();
		e.id = d.nextId++;
		e.group = group;
		e.type = type.name();
		e.manual = manual;
		e.startMs = now;
		return e;
	}

	private void finishStart(List<NewsEntry> entries) {
		NewsData d = data.get();
		long now = now();
		for (NewsEntry e : entries) {
			d.active.add(e);
			d.history.add(0, e.copy());
		}
		trimHistory();
		d.lastEventAt = now;
		markDirty.run();
		try {
			announcer.started(entries);
		} catch (RuntimeException ex) {
			SmpEconomy.LOG.error("[SMPEco] news: announcement failed", ex);
		}
	}

	private void trimHistory() {
		List<NewsEntry> h = data.get().history;
		int max = Math.max(1, config.get().historySize);
		while (h.size() > max) h.remove(h.size() - 1);
	}

	/** Ends an event immediately, without price fade-out for flash and fever. The caller handles shock factors. */
	private void cancel(NewsEntry e) {
		long now = now();
		data.get().active.remove(e);
		// Already ended normally (only fading): do not mark as cancelled in the history
		if (!e.running(now)) {
			markDirty.run();
			return;
		}
		e.cancelled = true;
		e.endNotified = true;
		for (NewsEntry h : data.get().history) {
			if (h.id == e.id) {
				h.cancelled = true;
				h.endMs = Math.min(h.endMs, now);
			}
		}
		markDirty.run();
	}

	void tick() {
		long now = now();
		NewsData d = data.get();
		boolean changed = syncBulkOrders(now);
		for (NewsEntry e : List.copyOf(d.active)) {
			if (!e.endNotified && !e.running(now)) {
				e.endNotified = true;
				changed = true;
				try {
					announcer.ended(e);
				} catch (RuntimeException ex) {
					SmpEconomy.LOG.error("[SMPEco] news: end message failed", ex);
				}
			}
			if (e.finished(now)) {
				d.active.remove(e);
				changed = true;
			}
		}
		if (changed) markDirty.run();
	}

	/**
	 * {@code /news clear}: ends market shocks, flash sales and auction fever immediately. NPC bulk orders stay: {@link
	 * Services.NpcOrders} has no cancel, so the order would keep running invisibly and a second one could start. They set no
	 * price factors. Cancel through {@code /orders admin cancel <id>}; the event then follows via {@link #syncBulkOrders}.
	 * Returns the number ended.
	 */
	int clearAll() {
		NewsData d = data.get();
		syncBulkOrders(now());
		PriceModifiers.removeSource(source);
		int n = 0;
		for (NewsEntry e : List.copyOf(d.active)) {
			if (e.kind() == Type.BULK_ORDER) continue;
			cancel(e);
			n++;
		}
		markDirty.run();
		return n;
	}

	List<NewsEntry> runningBulkOrders() {
		long now = now();
		List<NewsEntry> list = new ArrayList<>();
		for (NewsEntry e : data.get().active) {
			if (e.kind() == Type.BULK_ORDER && e.running(now)) list.add(e);
		}
		return list;
	}

	/**
	 * Bulk orders whose order was closed by orders (delivered, cancelled, expired, or no orders service) end now instead of after
	 * their duration: main phase end becomes {@code now} and {@link #tick} announces the end and removes them. Not "cancelled" in
	 * the history, the action ran normally. Entries without order id (older news.json) expire by time. Returns whether anything
	 * changed.
	 */
	boolean syncBulkOrders(long now) {
		NewsData d = data.get();
		Optional<Services.NpcOrders> svc = Optional.empty();
		boolean looked = false;
		boolean changed = false;
		for (NewsEntry e : d.active) {
			if (e.kind() != Type.BULK_ORDER || e.orderId <= 0 || !e.running(now)) continue;
			if (!looked) {
				svc = npcOrders.get();
				looked = true;
			}
			boolean open;
			try {
				open = svc.isPresent() && svc.get().isOpen(e.orderId);
			} catch (RuntimeException ex) {
				if (!bulkSyncErrorLogged) SmpEconomy.LOG.error("[SMPEco] news: NpcOrders.isOpen failed, bulk order expires by time", ex);
				bulkSyncErrorLogged = true;
				open = true;
			}
			if (open) continue;
			e.endMs = now;
			for (NewsEntry h : d.history) {
				if (h.id == e.id) h.endMs = Math.min(h.endMs, now);
			}
			SmpEconomy.LOG.info("[SMPEco] news: NPC bulk order #{} ({}) closed in orders, event ended", e.orderId, e.item);
			changed = true;
		}
		if (changed) markDirty.run();
		return changed;
	}

	/**
	 * After loading: removes expired entries, restores missing price factors of running shocks (e.g. price_modifiers.json from an
	 * older backup), fades out orphaned factors of this source.
	 */
	void reconcile() {
		long now = now();
		NewsData d = data.get();
		d.repair();
		Set<String> expected = new HashSet<>();
		for (NewsEntry e : List.copyOf(d.active)) {
			if (e.finished(now)) {
				d.active.remove(e);
				continue;
			}
			// Expired during downtime: no late "over" in chat
			if (!e.running(now)) e.endNotified = true;
			if (!e.kind().isShock()) continue;
			dropUncappedHypeMembers(e);
			for (String m : e.members) {
				Optional<Item> item = Items.byId(m);
				if (item.isEmpty()) continue;
				expected.add(m);
				boolean present = false;
				for (PriceModifiers.Active a : PriceModifiers.active(item.get())) present |= a.source().equals(source);
				if (present) continue;
				if (now < e.endMs) {
					PriceModifiers.set(source, item.get(), e.factor, 0, e.endMs - now, e.fadeOutMs, LABEL);
				} else {
					long remaining = e.endMs + e.fadeOutMs - now;
					PriceModifiers.set(source, item.get(), fadeValue(e, now), 0, 1, Math.max(0, remaining), LABEL);
				}
				SmpEconomy.LOG.warn("[SMPEco] news: price factor for {} was missing, restored", m);
			}
		}
		for (PriceModifiers.Active a : PriceModifiers.active()) {
			if (a.source().equals(source) && !expected.contains(Items.id(a.item()))) {
				SmpEconomy.LOG.warn("[SMPEco] news: orphaned price factor for {}, fading out", Items.id(a.item()));
				PriceModifiers.clear(source, a.item(), fadeOutMs());
			}
		}
		trimHistory();
		markDirty.run();
	}

	/**
	 * Removes pivotless members of a shop hype from an older state ({@link #hypeSkips}): back to 1.0 immediately without
	 * fade-out, and out of the event and history.
	 */
	private void dropUncappedHypeMembers(NewsEntry e) {
		if (e.kind() != Type.HYPE) return;
		Optional<Item> main = Items.byId(e.item);
		if (main.isEmpty()) return;
		List<String> dropped = new ArrayList<>();
		for (String m : e.members) {
			Optional<Item> member = Items.byId(m);
			if (member.isPresent() && hypeSkips(main.get(), member.get(), e.factor)) dropped.add(m);
		}
		if (dropped.isEmpty()) return;
		e.members.removeAll(dropped);
		for (NewsEntry h : data.get().history) {
			if (h.id == e.id) h.members.removeAll(dropped);
		}
		for (String m : dropped) Items.byId(m).ifPresent(it -> PriceModifiers.remove(source, it));
		SmpEconomy.LOG.warn("[SMPEco] news: hype on {}: removed {} without shop pivot from the event (factor immediately 1.0)", e.item, dropped);
	}

	/** Value of a shock while fading out (smoothstep like PriceModifiers). */
	static double fadeValue(NewsEntry e, long now) {
		if (now < e.endMs) return e.factor;
		if (e.fadeOutMs <= 0 || now >= e.endMs + e.fadeOutMs) return 1.0;
		double t = (double) (now - e.endMs) / e.fadeOutMs;
		double s = t * t * (3 - 2 * t);
		return e.factor + (1.0 - e.factor) * s;
	}

	double currentFactor(NewsEntry e) {
		return Items.byId(e.item).map(i -> PriceModifiers.get(source, i)).orElse(1.0);
	}

	int livePercent(NewsEntry e) {
		Type t = e.kind();
		if (t.isShock()) {
			Optional<Item> item = Items.byId(e.item);
			if (item.isEmpty()) return e.shownPercent;
			double f = currentFactor(e);
			return f == 1.0 ? 0 : NewsPrices.shockPercent(source, item.get(), f, players.get());
		}
		return e.shownPercent;
	}

	int randomMinutes(int min, int max) {
		int lo = Math.max(1, min);
		int hi = Math.max(lo, max);
		return lo + random.nextInt(hi - lo + 1);
	}

	int randomStep5(int min, int max) {
		int lo = (min + 4) / 5;
		int hi = max / 5;
		if (hi < lo) return Math.max(1, min);
		return (lo + random.nextInt(hi - lo + 1)) * 5;
	}

	int weightedIndex(List<Double> weights) {
		double total = 0;
		for (double w : weights) total += Math.max(0, w);
		if (total <= 0) return 0;
		double r = random.nextDouble() * total;
		for (int i = 0; i < weights.size(); i++) {
			r -= Math.max(0, weights.get(i));
			if (r < 0) return i;
		}
		return weights.size() - 1;
	}
}
