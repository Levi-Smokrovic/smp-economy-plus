package dev.smpeconomy.features.market;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.Money;

/**
 * NPC sellers: keep 50-80 NPC offers active in the auction house from a weighted pool, refilling gently and faster after startup.
 * Normal offers cost market value × random(0.95-1.4), never below {@code ValueEngine.saleFloor} (about market value × 1.25 + 1
 * cent, above every system payment; the auction house raises to it on listing and rechecks against the real buyer).
 *
 * <p>Bargains ("dumb trader"): with {@code bargainChance} an offer costs only {@code bargainPriceMin..Max} of the normal price,
 * but never below {@code ValueEngine.bargainFloor} (/sell price including recipes × 1.15 + 1 cent, so /sell is never a risk-free
 * instant profit). Bought bargains are ordinary items and flipping is intended. Profit is limited by quantity: at most {@code
 * bargainMaxActive} at once, short duration, and a flip budget per 24 h for all players together ({@code bargainDailyBudget}).
 * Visible with {@code bargainNote}, a share ({@code bargainHiddenChance}) without. Duration is 6-24 h normally, 4-12 h for
 * bargains, then offers simply vanish. Proceeds go 100 % to the treasury, booked by the auction house.
 */
public final class NpcSellers {
	public static final String NAME_PREFIX = "§e[Market] ";
	/** Name prefix of old German NPC offers, removed by {@link #pruneLegacyNames}. */
	static final long DAY_MS = 24 * 3_600_000L;
	/** Avoids logging the same warning every 2 min. */
	private static String lastWarning;

	private NpcSellers() {}

	static void tick(MarketBridge bridge, long now, Random rng) {
		MarketConfig c = MarketConfig.get();
		if (!c.npcSellersEnabled) return;
		MarketData d = MarketData.get();
		rerollTargetIfNeeded(d, c, now, rng);
		if (d.lastRefillAt > now) d.lastRefillAt = 0; // Clock set back: do not wait forever
		long since = now - d.lastRefillAt;
		if (since < c.refillFastSeconds * 1000L) return;
		List<MarketBridge.Listing> active = bridge.npcListings(MarketBridge.SOURCE);
		if (active.size() >= d.targetOffers) return;
		boolean fast = active.size() < c.sellerMinOffers;
		if (!fast && since < c.refillIntervalSeconds * 1000L) return;
		d.lastRefillAt = now;
		MarketData.markDirty();
		createOffer(bridge, now, rng, active);
	}

	static int fill(MarketBridge bridge, long now, Random rng) {
		MarketConfig c = MarketConfig.get();
		MarketData d = MarketData.get();
		rerollTargetIfNeeded(d, c, now, rng);
		int created = 0;
		for (int attempt = 0; attempt < d.targetOffers * 3 + 5; attempt++) {
			List<MarketBridge.Listing> active = bridge.npcListings(MarketBridge.SOURCE);
			if (active.size() >= d.targetOffers) break;
			if (createOffer(bridge, now, rng, active).isPresent()) created++;
		}
		d.lastRefillAt = now;
		MarketData.markDirty();
		return created;
	}

	static int clear(MarketBridge bridge) {
		int n = 0;
		for (MarketBridge.Listing l : bridge.npcListings(MarketBridge.SOURCE)) {
			if (bridge.removeNpcListing(l.id())) n++;
		}
		return n;
	}

	/** Removes NPC offers of items market never sells ({@link MarketConfig#NEVER_SOLD}). Returns the number removed. */
	static int pruneNeverSold(MarketBridge bridge) {
		int n = 0;
		for (MarketBridge.Listing l : bridge.npcListings(MarketBridge.SOURCE)) {
			if (l.item() != null && MarketConfig.neverSold(l.item().getItem()) && bridge.removeNpcListing(l.id())) n++;
		}
		if (n > 0) SmpEconomy.LOG.info("[SMPEco] market: removed {} NPC offers with emerald/emerald block (market never sells these)", n);
		return n;
	}

	static void rerollTargetIfNeeded(MarketData d, MarketConfig c, long now, Random rng) {
		boolean outside = d.targetOffers < c.sellerMinOffers || d.targetOffers > c.sellerMaxOffers;
		if (outside || now - d.targetRolledAt >= c.targetRerollHours * 3_600_000L) {
			d.targetOffers = c.sellerMinOffers + rng.nextInt(c.sellerMaxOffers - c.sellerMinOffers + 1);
			d.targetRolledAt = now;
			MarketData.markDirty();
		}
	}

	static Optional<MarketBridge.Listing> createOffer(MarketBridge bridge, long now, Random rng, List<MarketBridge.Listing> active) {
		return createOffer(bridge, now, rng, active, rng.nextDouble() < MarketConfig.get().bargainChance);
	}

	/**
	 * {@code wantBargain} attempts a bargain; it becomes a normal offer if no real bargain is possible (limit reached, floor too
	 * close to the normal price, flip budget used up).
	 */
	static Optional<MarketBridge.Listing> createOffer(MarketBridge bridge, long now, Random rng, List<MarketBridge.Listing> active,
			boolean wantBargain) {
		MarketConfig c = MarketConfig.get();
		if (c.pool.isEmpty() || SmpEconomy.server() == null) return Optional.empty();
		Set<String> offered = new HashSet<>();
		for (MarketBridge.Listing l : active) offered.add(offerKey(l.item()));
		HolderLookup.Provider registries = SmpEconomy.server().registryAccess();
		for (int attempt = 0; attempt < 8; attempt++) {
			MarketConfig.PoolEntry entry = pick(c.pool, rng);
			if (entry == null) return Optional.empty();
			Optional<ItemStack> built = buildStack(entry, rng, registries);
			if (built.isEmpty()) continue;
			ItemStack stack = built.get();
			if (offered.contains(offerKey(stack)) && attempt < 6) continue;
			OptionalLong unit = ValueEngine.unitValue(stack);
			if (unit.isEmpty()) continue;
			long floor = ValueEngine.saleFloor(null, stack);
			long price = offerPrice(unit.getAsLong(), stack.getCount(), floor, rng, c);
			BargainPlan bargain = wantBargain ? planBargain(stack, price, floor, rng, c, now) : null;
			// This item gives no real bargain (e.g. shop item at its pivot), try a few other goods
			if (wantBargain && bargain == null && attempt < 4) continue;
			long duration = bargain != null ? hours(c.bargainMinHours, c.bargainMaxHours, rng) : hours(c.offerMinHours, c.offerMaxHours, rng);
			if (bargain != null) price = bargain.price();
			String name = NAME_PREFIX + c.traderNames.get(rng.nextInt(c.traderNames.size()));
			MarketBridge.Created created = bridge.createNpcListing(stack, price, duration, name, MarketBridge.SOURCE, bargain != null,
				bargain != null && bargain.shown() ? c.bargainNote : "");
			if (!created.ok()) {
				String warning = String.valueOf(created.error());
				if (!warning.equals(lastWarning)) SmpEconomy.LOG.warn("[SMPEco] market: NPC offer {}x {} not created: {}", stack.getCount(),
					Items.id(stack.getItem()), warning);
				lastWarning = warning;
				return Optional.empty();
			}
			lastWarning = null;
			if (bargain != null) recordBargain(created.listing(), bargain.shown(), now);
			return Optional.of(created.listing());
		}
		return Optional.empty();
	}

	private static long hours(int min, int max, Random rng) {
		return min * 3_600_000L + (long) ((max - min) * 3_600_000.0 * rng.nextDouble());
	}

	/**
	 * Pure price core of normal offers: market value × count × random(offerPriceMin..Max), never below {@code floorTotal}. A
	 * random price below the floor is raised just above it (0-5 %).
	 */
	public static long offerPrice(long unitValue, int count, long floorTotal, Random rng, MarketConfig c) {
		double base = (double) unitValue * Math.max(1, count);
		long floor = Math.max(1, floorTotal);
		long price = ceil(base * (c.offerPriceMin + (c.offerPriceMax - c.offerPriceMin) * rng.nextDouble()));
		if (price < floor) price = ceil(floor * (1 + 0.05 * rng.nextDouble()));
		return Math.min(Money.MAX, Math.max(floor, price));
	}

	record BargainPlan(long price, boolean shown, long potential) {}

	/**
	 * Plans a bargain for {@code stack}, or null if none is possible: active limit reached, price not at least {@code
	 * bargainMinDiscount} below the normal floor (e.g. shop items at their pivot), or the flip potential no longer fits the
	 * budget of the last 24 h.
	 */
	static BargainPlan planBargain(ItemStack stack, long normalPrice, long normalFloor, Random rng, MarketConfig c, long now) {
		if (c.bargainMaxActive <= 0 || activeBargains() >= c.bargainMaxActive) return null;
		long min = ValueEngine.bargainFloor(null, stack);
		long price = bargainPrice(normalPrice, min, rng, c);
		if (!isRealBargain(price, normalFloor, c)) return null;
		long potential = ValueEngine.bargainPotential(stack, price);
		if (potential > budgetLeft(now, c)) return null;
		return new BargainPlan(price, rng.nextDouble() >= c.bargainHiddenChance, potential);
	}

	public static long bargainPrice(long normalPrice, long minTotal, Random rng, MarketConfig c) {
		double f = c.bargainPriceMin + (c.bargainPriceMax - c.bargainPriceMin) * rng.nextDouble();
		long price = ceil(Math.max(1, normalPrice) * f);
		return Math.min(Money.MAX, Math.max(Math.max(1, minTotal), price));
	}

	/**
	 * Lowest stack total a seller lists {@code stack} at as a bargain: the lowest normal price (market value × {@code
	 * offerPriceMin}, never below {@code floor}) × {@code bargainPriceMin}, never below {@link ValueEngine#bargainFloor}. {@link
	 * #isRealBargain} says whether it is a real bargain.
	 *
	 * @param unitValue market value per unit ({@link ValueEngine#unitValue})
	 * @param floor     normal floor of the stack at listing time ({@code ValueEngine.saleFloor(null, stack)})
	 */
	static long lowestBargainPrice(ItemStack stack, long unitValue, long floor, MarketConfig c) {
		long normal = Math.max(floor, ceil((double) unitValue * stack.getCount() * c.offerPriceMin));
		return Math.min(Money.MAX, Math.max(ValueEngine.bargainFloor(null, stack), ceil(normal * c.bargainPriceMin)));
	}

	/** A real bargain is at least {@code bargainMinDiscount} below the normal floor. */
	static boolean isRealBargain(long price, long normalFloor, MarketConfig c) {
		return price > 0 && price <= (long) Math.floor(normalFloor * (1 - c.bargainMinDiscount));
	}

	static int activeBargains() {
		int n = 0;
		for (MarketData.Bargain b : MarketData.get().bargains.values()) {
			if (b.soldAt == 0) n++;
		}
		return n;
	}

	/** Used flip budget: potential of all active bargains and those sold in the last 24 h. */
	static long budgetUsed(long now) {
		long sum = 0;
		for (MarketData.Bargain b : MarketData.get().bargains.values()) {
			if (b.soldAt == 0 || now - b.soldAt < DAY_MS) sum = Math.min(Money.MAX, sum + Math.max(0, b.potential));
		}
		return sum;
	}

	static long budgetLeft(long now, MarketConfig c) {
		return Math.max(0, c.bargainDailyBudget - budgetUsed(now));
	}

	static void recordBargain(MarketBridge.Listing l, boolean shown, long now) {
		MarketData d = MarketData.get();
		MarketData.Bargain b = new MarketData.Bargain();
		b.createdAt = now;
		b.price = l.price();
		b.potential = ValueEngine.bargainPotential(l.item(), l.price());
		b.itemId = Items.id(l.item().getItem());
		b.count = l.count();
		b.shown = shown;
		d.bargains.put(l.id(), b);
		d.bargainsCreated++;
		MarketData.markDirty();
	}

	static void forgetBargain(long listingId) {
		MarketData.Bargain b = MarketData.get().bargains.get(listingId);
		if (b != null && b.soldAt == 0) {
			MarketData.get().bargains.remove(listingId);
			MarketData.markDirty();
		}
	}

	static void sweepBargains(MarketBridge bridge, long now) {
		MarketData d = MarketData.get();
		if (d.bargains.isEmpty()) return;
		java.util.Set<Long> ids = new HashSet<>();
		for (MarketBridge.Listing l : bridge.npcListings(MarketBridge.SOURCE)) ids.add(l.id());
		boolean changed = d.bargains.entrySet().removeIf(e -> e.getValue().soldAt == 0 ? !ids.contains(e.getKey())
			: now - e.getValue().soldAt >= DAY_MS);
		if (changed) MarketData.markDirty();
	}

	/**
	 * Creates a bargain immediately (visibly marked, lowest allowed price) with all limits except randomness and the active
	 * limit. Returns an error text, or null with the offer in {@code out[0]}.
	 */
	static String createBargainNow(MarketBridge bridge, ItemStack stack, long now, MarketBridge.Listing[] out) {
		MarketConfig c = MarketConfig.get();
		if (MarketConfig.neverSold(stack.getItem())) return "market never sells this item";
		OptionalLong unit = ValueEngine.unitValue(stack);
		if (unit.isEmpty()) return "no market value";
		long floor = ValueEngine.saleFloor(null, stack);
		long price = lowestBargainPrice(stack, unit.getAsLong(), floor, c);
		if (!isRealBargain(price, floor, c)) return "no real bargain possible (floor " + price + " ≈ normal " + floor + ")";
		long potential = ValueEngine.bargainPotential(stack, price);
		if (potential > budgetLeft(now, c)) return "flip budget used up (left " + budgetLeft(now, c) + ", needed " + potential + ")";
		MarketBridge.Created created = bridge.createNpcListing(stack, price, c.bargainMaxHours * 3_600_000L,
			NAME_PREFIX + c.traderNames.get(0), MarketBridge.SOURCE, true, c.bargainNote);
		if (!created.ok()) return String.valueOf(created.error());
		recordBargain(created.listing(), true, now);
		out[0] = created.listing();
		return null;
	}

	private static long ceil(double v) {
		if (!(v > 0)) return 0;
		return v >= Money.MAX ? Money.MAX : (long) Math.ceil(v - 1e-9);
	}

	static MarketConfig.PoolEntry pick(List<MarketConfig.PoolEntry> pool, Random rng) {
		double total = 0;
		for (MarketConfig.PoolEntry e : pool) total += Math.max(0, e.weight);
		if (!(total > 0)) return null;
		double r = rng.nextDouble() * total;
		for (MarketConfig.PoolEntry e : pool) {
			r -= Math.max(0, e.weight);
			if (r < 0) return e;
		}
		return pool.get(pool.size() - 1);
	}

	static Optional<ItemStack> buildStack(MarketConfig.PoolEntry entry, Random rng, HolderLookup.Provider registries) {
		if (entry.items.isEmpty()) return Optional.empty();
		String itemId = entry.items.get(rng.nextInt(entry.items.size()));
		if (MarketConfig.neverSold(itemId)) return Optional.empty(); // Also without validate (pool changed at runtime)
		Optional<Item> item = Items.byId(itemId);
		if (item.isEmpty()) return Optional.empty();
		int count = entry.min + (entry.max > entry.min ? rng.nextInt(entry.max - entry.min + 1) : 0);
		count = Math.max(1, Math.min(count, item.get().getDefaultMaxStackSize()));
		if (entry.enchantment != null && !entry.enchantment.isBlank()) {
			if (item.get() != net.minecraft.world.item.Items.ENCHANTED_BOOK) return Optional.empty();
			Identifier id = Identifier.tryParse(entry.enchantment);
			if (id == null) return Optional.empty();
			Optional<Holder.Reference<Enchantment>> holder = registries.lookupOrThrow(Registries.ENCHANTMENT)
				.get(ResourceKey.create(Registries.ENCHANTMENT, id));
			if (holder.isEmpty()) return Optional.empty();
			int level = Math.max(1, Math.min(entry.level, holder.get().value().getMaxLevel()));
			return Optional.of(EnchantmentHelper.createBook(new EnchantmentInstance(holder.get(), level)));
		}
		return Optional.of(new ItemStack(item.get(), count));
	}

	static String offerKey(ItemStack stack) {
		List<String> ench = new ArrayList<>(ValueEngine.enchantmentIds(stack));
		ench.sort(String::compareTo);
		return Items.id(stack.getItem()) + (ench.isEmpty() ? "" : ench.toString());
	}

	/**
	 * A player bought an NPC offer: drift rises, stats update, a bargain counts toward the flip budget for 24 h. No lock: resale
	 * to players, NPC buyers, NPC orders and via /sell is allowed.
	 */
	static void onSold(MarketBridge.Listing l, UUID buyer, long price, long now) {
		MarketData d = MarketData.get();
		d.npcSales++;
		d.npcSaleCents = Math.min(Money.MAX, d.npcSaleCents + Math.max(0, price));
		MarketData.Bargain b = d.bargains.get(l.id());
		if (b != null && b.soldAt == 0) {
			b.soldAt = now;
			d.bargainsSold++;
		}
		MarketData.markDirty();
		NpcBuyers.applyDrift(l, +1, now);
	}
}
