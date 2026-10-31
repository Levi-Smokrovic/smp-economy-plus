package dev.smpeconomy.features.news;

import java.util.Collection;
import java.util.OptionalLong;
import java.util.UUID;
import net.minecraft.world.item.Item;
import dev.smpeconomy.core.BasePrices;
import dev.smpeconomy.core.Hooks;
import dev.smpeconomy.core.PriceGuard;
import dev.smpeconomy.core.PriceModifiers;

/**
 * Display calculations for events, only through PriceGuard's cores, so nothing is promised beyond what is paid (shop items: sale
 * capped at the pivot). "Neutral" means without personal sell boosts; the /news GUI also shows the player's real prices through
 * {@link PriceGuard#breakdown}. Shop prices use the buy modifier like PriceGuard: any source below 1.0 (crash) counts as 1.0.
 */
final class NewsPrices {
	private NewsPrices() {}

	/** Before and after of a unit price in cents; -1 means absent. */
	record Change(long before, long after) {
		boolean present() {
			return before > 0 && after > 0;
		}

		/** Percent change truncated toward 0, never promise more: 1.799 gives +79, 0.5 gives -50. */
		int percent() {
			return present() ? truncPercent((double) after / before) : 0;
		}
	}

	static double otherModifier(String source, Item item) {
		double product = 1.0;
		for (PriceModifiers.Active a : PriceModifiers.active(item)) {
			if (!a.source().equals(source)) product *= a.current();
		}
		return product;
	}

	static double otherBuyModifier(String source, Item item) {
		double product = 1.0;
		for (PriceModifiers.Active a : PriceModifiers.active(item)) {
			if (!a.source().equals(source) && a.current() > 1.0) product *= a.current();
		}
		return Math.min(PriceModifiers.MAX_FACTOR, product);
	}

	static double otherDiscount(String hookId, Item item) {
		double product = 1.0;
		for (Hooks.Part p : Hooks.shopParts(item)) {
			if (!p.id().equals(hookId)) product *= p.factor();
		}
		return product;
	}

	static Change sell(String source, Item item, double newsFactor) {
		OptionalLong base = BasePrices.sellPrice(item);
		if (base.isEmpty()) return new Change(-1, -1);
		long pivot = PriceGuard.pivotPrice(item);
		double other = otherModifier(source, item);
		long before = PriceGuard.computeSell(base.getAsLong(), other, 1.0, pivot);
		long after = PriceGuard.computeSell(base.getAsLong(), other * newsFactor, 1.0, pivot);
		return new Change(before > 0 ? before : -1, after > 0 ? after : -1);
	}

	static boolean sellCapped(String source, Item item, double newsFactor) {
		OptionalLong base = BasePrices.sellPrice(item);
		long pivot = PriceGuard.pivotPrice(item);
		if (base.isEmpty() || pivot < 0) return false;
		return PriceGuard.computeSell(base.getAsLong(), otherModifier(source, item) * newsFactor, 1.0, -1) > pivot;
	}

	/**
	 * Neutral shop price without and with the news factor; -1 if not in the shop. A crash ({@code newsFactor < 1}) never changes
	 * it (buy modifier), a hype raises it.
	 */
	static Change shop(String source, Item item, double newsFactor) {
		OptionalLong baseBuy = BasePrices.shopBuyPrice(item);
		if (baseBuy.isEmpty()) return new Change(-1, -1);
		long before = shopAt(item, baseBuy.getAsLong(), otherBuyModifier(source, item), 1.0, null);
		long after = shopAt(item, baseBuy.getAsLong(), Math.min(PriceModifiers.MAX_FACTOR, otherBuyModifier(source, item)
			* PriceGuard.buySideModifier(newsFactor)), 1.0, null);
		return new Change(before, after);
	}

	/**
	 * Shop price like PriceGuard at buy modifier {@code buyMod}: max(base × buyMod × discount, 1.25 × sell at buy modifier, pivot
	 * + 1). Discount is all shop discounts × {@code extraDiscount}; {@code player} null means neutral.
	 */
	static long shopAt(Item item, long baseBuy, double buyMod, double extraDiscount, UUID player) {
		long pivot = PriceGuard.pivotPrice(item);
		OptionalLong baseSell = BasePrices.sellPrice(item);
		double mult = player == null ? 1.0 : Hooks.sellMultiplier(player, item);
		long sell = baseSell.isPresent() ? PriceGuard.computeSell(baseSell.getAsLong(), buyMod, mult, pivot) : 0;
		return PriceGuard.computeShop(baseBuy, buyMod, Hooks.shopDiscount(item) * extraDiscount, sell, pivot);
	}

	static Change flash(String hookId, Item item, double flashFactor) {
		return flashFor(hookId, null, item, flashFactor);
	}

	private static Change flashFor(String hookId, UUID player, Item item, double flashFactor) {
		OptionalLong baseBuy = BasePrices.shopBuyPrice(item);
		if (baseBuy.isEmpty()) return new Change(-1, -1);
		long pivot = PriceGuard.pivotPrice(item);
		double mod = PriceGuard.buySideModifier(item);
		OptionalLong baseSell = BasePrices.sellPrice(item);
		double mult = player == null ? 1.0 : Hooks.sellMultiplier(player, item);
		long sell = baseSell.isPresent() ? PriceGuard.computeSell(baseSell.getAsLong(), mod, mult, pivot) : 0;
		double other = otherDiscount(hookId, item);
		long before = PriceGuard.computeShop(baseBuy.getAsLong(), mod, other, sell, pivot);
		long after = PriceGuard.computeShop(baseBuy.getAsLong(), mod, other * flashFactor, sell, pivot);
		return new Change(before, after);
	}

	static Change personalSell(String source, UUID player, Item item, double newsFactor) {
		OptionalLong base = BasePrices.sellPrice(item);
		if (base.isEmpty()) return new Change(-1, -1);
		long pivot = PriceGuard.pivotPrice(item);
		double other = otherModifier(source, item);
		double mult = Hooks.sellMultiplier(player, item);
		long before = PriceGuard.computeSell(base.getAsLong(), other, mult, pivot);
		long after = PriceGuard.computeSell(base.getAsLong(), other * newsFactor, mult, pivot);
		return new Change(before > 0 ? before : -1, after > 0 ? after : -1);
	}

	static Change personalFlash(String hookId, UUID player, Item item, double flashFactor) {
		return flashFor(hookId, player, item, flashFactor);
	}

	/**
	 * Displayed percent of a shock: neutral, for hypes at most the smallest value among the given online players. Someone already
	 * near the pivot through market day or trader rank gets less. Never promise more.
	 */
	static int shockPercent(String source, Item item, double newsFactor, Collection<UUID> players) {
		Change neutral = sell(source, item, newsFactor);
		int pct = neutral.present() ? neutral.percent() : truncPercent(newsFactor);
		if (newsFactor > 1.0) {
			for (UUID p : players) {
				Change c = personalSell(source, p, item, newsFactor);
				if (c.present()) pct = Math.min(pct, c.percent());
			}
		}
		return pct;
	}

	/** Flash sale discount of an item (negative): the smallest discount among neutral and the given players; 0 if none. */
	static int flashPercent(String hookId, Item item, double flashFactor, Collection<UUID> players) {
		Change neutral = flash(hookId, item, flashFactor);
		if (!neutral.present()) return 0;
		int pct = neutral.percent();
		for (UUID p : players) {
			Change c = personalFlash(hookId, p, item, flashFactor);
			if (c.present()) pct = Math.max(pct, c.percent());
		}
		return Math.min(0, pct);
	}

	static long personalSellWithout(String source, UUID player, Item item) {
		OptionalLong base = BasePrices.sellPrice(item);
		if (base.isEmpty()) return -1;
		long v = PriceGuard.computeSell(base.getAsLong(), otherModifier(source, item), Hooks.sellMultiplier(player, item), PriceGuard.pivotPrice(item));
		return v > 0 ? v : -1;
	}

	static long personalShopWithout(String source, UUID player, Item item) {
		OptionalLong baseBuy = BasePrices.shopBuyPrice(item);
		if (baseBuy.isEmpty()) return -1;
		return shopAt(item, baseBuy.getAsLong(), otherBuyModifier(source, item), 1.0, player);
	}

	static int truncPercent(double ratio) {
		if (Double.isNaN(ratio) || Double.isInfinite(ratio)) return 0;
		double pct = (ratio - 1.0) * 100.0;
		return (int) (pct >= 0 ? Math.floor(pct + 1e-9) : Math.ceil(pct - 1e-9));
	}

	static String pct(int percent) {
		return percent > 0 ? "+" + percent + "%" : percent < 0 ? percent + "%" : "±0%";
	}
}
