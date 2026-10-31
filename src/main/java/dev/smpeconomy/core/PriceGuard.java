package dev.smpeconomy.core;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;

/**
 * Binding price calculation for every deal with the system. Recompute on each transaction, never cache, per player and
 * item. All prices are cents per item.
 *
 * <p>Effective sell price = base x item modifier x the player's sell multipliers, rounded down; capped at the pivot for
 * shop items; 0 or less means not sellable.
 *
 * <p>Effective shop price = max(base buy x buy-side modifier x discount, 1.25 x the same player's sell price at the
 * buy-side modifier, pivot + 1), rounded up, at least 1 cent. NPC buyers (market) pay at most 0.9 x shop price, market
 * value x 1.15 (1.25 in fever) and the pivot. NPC orders pay at most 0.8 x shop price, market value x 1.25 and the
 * pivot. Anything the system sells to players costs at least {@link #minSystemSalePrice}, more than any system payment
 * for the same item at that moment.
 *
 * <p>A crash only affects the sell side. An item modifier below 1 (breaking news crash, admin {@code pricemod}) lowers
 * /sell, NPC buyers, orders and market value, but never the shop price or the /sell part of the NPC offer floor: both
 * use {@link PriceModifiers#buySide}, where any source below 1.0 counts as 1.0. So buying during a crash and selling
 * after it never earns more than without the crash. Hypes act on both sides. Buying cheaper is only possible through
 * shop discounts ({@link Hooks#shopDiscount}), and those respect the pivot. The caller supplies the market value part
 * of NPC offers; market passes one without crash or downward drift.
 *
 * <p>The pivot (shop.json items only) is {@code floor(0.6 x base shop price)}, independent of time, events and player.
 * Every system payment for a shop item is at most the pivot and every system sale at least pivot + 1, so buying from
 * the system and selling back later is never profitable, even across crashes, flash sales and hypes. As a result, hypes
 * on shop items only affect selling up to the pivot, and shop discounts reach at most -40% (with buy price at least
 * 2.67 x sell price).
 *
 * <p>The pure calculations ({@link #computeSell}, {@link #computeShop}, {@link #capNpcBuy}, {@link #capNpcOrder},
 * {@link #minSystemSale}, {@link #pivot}) are checked by a selftest with random combinations.
 */
public final class PriceGuard {
	public static final double SHOP_FLOOR_FACTOR = 1.25;
	public static final double NPC_BUY_SHOP_FACTOR = 0.9;
	public static final double NPC_BUY_MARKET_FACTOR = 1.15;
	/** Highest market value factor allowed for NPC buyers (auction fever). */
	public static final double NPC_BUY_MARKET_FACTOR_MAX = 1.25;
	public static final double NPC_ORDER_SHOP_FACTOR = 0.8;
	public static final double NPC_ORDER_MARKET_FACTOR = 1.25;
	/** System payments stay at or below the pivot and below system sales, at all times. */
	public static final double PIVOT_FACTOR = 0.6;

	private static final double EPS = 1e-9;

	private PriceGuard() {}

	/** -1 if the item is not in the shop. */
	public static long pivotPrice(Item item) {
		OptionalLong base = BasePrices.shopBuyPrice(item);
		return base.isPresent() ? pivot(base.getAsLong()) : -1;
	}

	/** For everything the system sells. Any source below 1.0 (crash) counts as 1.0, hypes count fully. */
	public static double buySideModifier(Item item) {
		return PriceModifiers.buySide(item);
	}

	/** Pure version for a single overall factor. NaN gives 1.0. */
	public static double buySideModifier(double itemModifier) {
		return Double.isNaN(itemModifier) ? 1.0 : Math.max(1.0, itemModifier);
	}

	/** Empty if not sellable: no base price, or modifiers pushed it under 1 cent. */
	public static OptionalLong effectiveSellPrice(UUID player, Item item) {
		OptionalLong base = BasePrices.sellPrice(item);
		if (base.isEmpty()) return OptionalLong.empty();
		long v = computeSell(base.getAsLong(), PriceModifiers.get(item), Hooks.sellMultiplier(player, item), pivotPrice(item));
		return v > 0 ? OptionalLong.of(v) : OptionalLong.empty();
	}

	public static OptionalLong effectiveSellPrice(ServerPlayer player, Item item) {
		return effectiveSellPrice(player.getUUID(), item);
	}

	/**
	 * The player's /sell price as it would be without a running crash. Floor for anything the system sells and would
	 * take back via /sell: using the crash-lowered price as the floor would make "buy during the crash, sell after it"
	 * a guaranteed profit.
	 */
	public static OptionalLong effectiveSellPriceWithoutCrash(UUID player, Item item) {
		long v = sellAt(player, item, buySideModifier(item));
		return v > 0 ? OptionalLong.of(v) : OptionalLong.empty();
	}

	/**
	 * For a given base price, e.g. a daily offer with its own. A crash does not change it. The pivot always comes from
	 * shop.json, so daily offers should only use shop.json items; otherwise they are protected at that moment but not
	 * over time.
	 */
	public static long effectiveShopBuyPrice(UUID player, Item item, long baseBuy) {
		double mod = buySideModifier(item);
		return computeShop(baseBuy, mod, Hooks.shopDiscount(item), sellAt(player, item, mod), pivotPrice(item));
	}

	/** 0 means not sellable. */
	private static long sellAt(UUID player, Item item, double itemModifier) {
		OptionalLong base = BasePrices.sellPrice(item);
		if (base.isEmpty()) return 0;
		return computeSell(base.getAsLong(), itemModifier, Hooks.sellMultiplier(player, item), pivotPrice(item));
	}

	public static OptionalLong effectiveShopBuyPrice(UUID player, Item item) {
		OptionalLong base = BasePrices.shopBuyPrice(item);
		return base.isEmpty() ? OptionalLong.empty() : OptionalLong.of(effectiveShopBuyPrice(player, item, base.getAsLong()));
	}

	public static long effectiveShopBuyPrice(ServerPlayer player, Item item, long baseBuy) {
		return effectiveShopBuyPrice(player.getUUID(), item, baseBuy);
	}

	public static OptionalLong effectiveShopBuyPrice(ServerPlayer player, Item item) {
		return effectiveShopBuyPrice(player.getUUID(), item);
	}

	/**
	 * Maximum an NPC buyer may pay {@code seller}, who may be offline: min(0.9 x seller's shop price if in the shop,
	 * market value x 1.15, pivot).
	 *
	 * @param marketValue per item in cents, already including {@link PriceModifiers#get(Item)}; normally
	 * {@link Services#marketValue(Item)}
	 */
	public static long maxNpcBuyPrice(UUID seller, Item item, long marketValue) {
		return maxNpcBuyPrice(seller, item, marketValue, NPC_BUY_MARKET_FACTOR);
	}

	/** With a custom market factor, clamped to [0, 1.25]. Shop cap and pivot stay. */
	public static long maxNpcBuyPrice(UUID seller, Item item, long marketValue, double marketFactor) {
		OptionalLong shop = effectiveShopBuyPrice(seller, item);
		return capNpcBuy(shop.isPresent() ? shop.getAsLong() : -1, marketValue, marketFactor, pivotPrice(item));
	}

	/**
	 * Maximum for an NPC order delivery: min(0.8 x shop price if in the shop, market value x 1.25, pivot). Recheck on
	 * each delivery. Also applies to mission deliveries.
	 *
	 * @param marketValue the same value market uses ({@link Services#marketValue(Item)}), including modifiers; 0 or
	 * less yields 0 (no order)
	 */
	public static long maxNpcOrderPrice(UUID deliverer, Item item, long marketValue) {
		OptionalLong shop = effectiveShopBuyPrice(deliverer, item);
		return capNpcOrder(shop.isPresent() ? shop.getAsLong() : -1, marketValue, pivotPrice(item));
	}

	/**
	 * Minimum at which the system may sell the item to this player: above every payment the system would make at that
	 * moment. max(1.25 x sell price, NPC buy cap at fever + 1, NPC order cap + 1, pivot + 1, 1). The sell price ignores
	 * a running crash ({@link #effectiveSellPriceWithoutCrash}) and the shop cap is crash-neutral anyway. To stay
	 * protected past the end of a crash, pass a {@code marketValue} without the crash. Market checks this both on
	 * listing and on purchase.
	 *
	 * @param marketValue the value NPC buyers and orders use, or an upper bound of it without the crash
	 */
	public static long minSystemSalePrice(UUID buyer, Item item, long marketValue) {
		long pivot = pivotPrice(item);
		long sell = sellAt(buyer, item, buySideModifier(item));
		OptionalLong shop = effectiveShopBuyPrice(buyer, item);
		long shopPrice = shop.isPresent() ? shop.getAsLong() : -1;
		long payout = Math.max(capNpcBuy(shopPrice, marketValue, NPC_BUY_MARKET_FACTOR_MAX, pivot), capNpcOrder(shopPrice, marketValue, pivot));
		return minSystemSale(sell, payout, pivot);
	}

	/**
	 * Price components for GUIs and debugging. Missing prices are -1, including an {@code effectiveSell} below 1 cent.
	 * {@code shopFloorApplied}: buy price was raised to 1.25 x sell or the pivot. {@code sellCapped}: selling hit the
	 * pivot.
	 */
	public record Breakdown(Item item, long baseSell, long baseBuy, double itemModifier, List<PriceModifiers.Active> itemParts,
			double sellMultiplier, List<Hooks.Part> sellParts, double shopDiscount, List<Hooks.Part> shopParts,
			long effectiveSell, long effectiveBuy, boolean shopFloorApplied, long pivot, boolean sellCapped) {

		/** Finished lore lines for active modifiers plus notes when a limit applies. */
		public List<String> loreLines() {
			List<String> lines = new ArrayList<>();
			for (PriceModifiers.Active a : itemParts) {
				lines.add((a.current() >= 1 ? "§a▲ " : "§c▼ ") + percent(a.current()) + " " + a.label() + (a.current() < 1 ? " §8(sell only)" : ""));
			}
			for (Hooks.Part p : sellParts) lines.add((p.factor() >= 1 ? "§a▲ " : "§c▼ ") + percent(p.factor()) + " " + p.label() + " §8(sell)");
			for (Hooks.Part p : shopParts) lines.add((p.factor() <= 1 ? "§a▼ " : "§c▲ ") + percent(p.factor()) + " " + p.label() + " §8(buy)");
			if (sellCapped) lines.add("§8Sell price capped (price limit)");
			if (shopFloorApplied) lines.add("§8Minimum buy price active");
			return lines;
		}
	}

	public static Breakdown breakdown(UUID player, Item item) {
		long baseSell = BasePrices.sellPrice(item).orElse(-1);
		long baseBuy = BasePrices.shopBuyPrice(item).orElse(-1);
		long pivot = pivotPrice(item);
		double mod = PriceModifiers.get(item);
		double buyMod = buySideModifier(item);
		double sellMult = Hooks.sellMultiplier(player, item);
		double discount = Hooks.shopDiscount(item);
		long rawSell = baseSell < 0 ? -1 : computeSell(baseSell, mod, sellMult, -1);
		long effSell = baseSell < 0 ? -1 : computeSell(baseSell, mod, sellMult, pivot);
		if (effSell <= 0) effSell = -1;
		boolean capped = pivot >= 0 && rawSell > pivot;
		long sellForBuy = baseSell < 0 ? 0 : computeSell(baseSell, buyMod, sellMult, pivot);
		long effBuy = baseBuy < 0 ? -1 : computeShop(baseBuy, buyMod, discount, sellForBuy, pivot);
		boolean floor = baseBuy >= 0 && effBuy > ceilD(baseBuy * buyMod * discount);
		return new Breakdown(item, baseSell, baseBuy, mod, PriceModifiers.active(item), sellMult, Hooks.sellParts(player, item),
			discount, Hooks.shopParts(item), effSell, effBuy, floor, pivot, capped);
	}

	public static String percent(double factor) {
		long pct = Math.round((factor - 1.0) * 100);
		return pct > 0 ? "+" + pct + "%" : pct < 0 ? pct + "%" : "±0%";
	}

	public static long pivot(long baseBuy) {
		return baseBuy <= 0 ? -1 : Math.floorDiv(Math.multiplyExact(Math.min(baseBuy, Money.MAX), 3L), 5L);
	}

	public static long computeSell(long base, double itemModifier, double sellMultiplier, long pivot) {
		if (base <= 0) return 0;
		long v = Money.floorCents(base * itemModifier * sellMultiplier);
		return pivot >= 0 ? Math.min(v, pivot) : v;
	}

	public static long computeShop(long baseBuy, double itemModifier, double discount, long effectiveSell, long pivot) {
		long raw = ceilD(Math.max(0, baseBuy) * itemModifier * discount);
		long floor = effectiveSell <= 0 ? 0 : ceilDiv(Math.multiplyExact(effectiveSell, 5L), 4L);
		long pivotFloor = pivot >= 0 ? pivot + 1 : 0;
		return Math.max(1, Math.max(raw, Math.max(floor, pivotFloor)));
	}

	public static long capNpcBuy(long shop, long marketValue, double marketFactor, long pivot) {
		double factor = Double.isNaN(marketFactor) ? NPC_BUY_MARKET_FACTOR : Math.max(0, Math.min(NPC_BUY_MARKET_FACTOR_MAX, marketFactor));
		long cap = marketValue <= 0 ? 0 : Money.floorCents(marketValue * factor);
		if (shop >= 0) cap = Math.min(cap, Math.floorDiv(Math.multiplyExact(shop, 9L), 10L));
		if (pivot >= 0) cap = Math.min(cap, pivot);
		return cap;
	}

	public static long capNpcOrder(long shop, long marketValue, long pivot) {
		long cap = marketValue <= 0 ? 0 : Money.floorCents(marketValue * NPC_ORDER_MARKET_FACTOR);
		if (shop >= 0) cap = Math.min(cap, Math.floorDiv(Math.multiplyExact(shop, 4L), 5L));
		if (pivot >= 0) cap = Math.min(cap, pivot);
		return cap;
	}

	public static long minSystemSale(long effectiveSell, long maxPayout, long pivot) {
		long floor = effectiveSell <= 0 ? 0 : ceilDiv(Math.multiplyExact(effectiveSell, 5L), 4L);
		long payout = maxPayout <= 0 ? 0 : Math.addExact(maxPayout, 1L);
		long pivotFloor = pivot >= 0 ? pivot + 1 : 0;
		return Math.max(1, Math.max(floor, Math.max(payout, pivotFloor)));
	}

	private static long ceilD(double v) {
		if (!(v > 0)) return 0;
		if (v >= Money.MAX) return Money.MAX;
		return (long) Math.ceil(v - EPS);
	}

	private static long ceilDiv(long a, long b) {
		return -Math.floorDiv(-a, b);
	}
}
