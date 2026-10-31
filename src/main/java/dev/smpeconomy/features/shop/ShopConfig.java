package dev.smpeconomy.features.shop;

import java.util.ArrayList;
import java.util.List;
import dev.smpeconomy.core.ConfigManager;
import dev.smpeconomy.core.PriceModifiers;

/**
 * {@code config/smpeconomy/shop_settings.json}. The prices themselves are in {@code shop.json} and {@code
 * prices.json}.
 */
public final class ShopConfig {
	/** Share of each purchase, in percent of the amount paid, that goes to the treasury; the rest disappears. */
	public double treasurySharePercent = 50.0;
	/** Right click in the shop sells, with the same rules and prices as /sell. */
	public boolean sellInShop = true;
	/**
	 * Off by default, like {@code allowCreative} in sell.json: items appear from nothing there, so selling prints
	 * money. Buying stays allowed.
	 */
	public boolean allowCreativeSell = false;
	/** Number of daily deals, 0 for off. */
	public int dailyDealCount = 5;
	/** Inclusive, in steps of 5 from the minimum. */
	public int dailyDealMinPercent = 10;
	public int dailyDealMaxPercent = 25;
	/** Minimum base buy price in cents, to avoid discounts on $0.25 dirt. */
	public long dailyDealMinBasePrice = 100;
	public List<String> dailyDealExcluded = new ArrayList<>();
	/** Only reaches players with the "announcements" setting. */
	public boolean announceDailyDeals = true;
	public int maxFavorites = 45;
	/**
	 * Highest factor at which a non-shop recipe result can be sold to the system: all sell bonuses combined. The
	 * default is market day 1.5 x Tycoon rank 1.12 = 1.68; shop results always count at their pivot. Raise it when
	 * stronger bonuses are added. See {@link RecipeGuard}.
	 */
	public double recipeGuardSellFactor = DEFAULT_RECIPE_GUARD_SELL_FACTOR;
	/**
	 * Highest item modifier at which a non-shop result can be sold after its ingredients were bought earlier at a
	 * low (stockpiling for a hype). Running modifiers of any size are covered live anyway. 1.0 means only sell
	 * bonuses over time; 3.0 also covers stockpiles against news hypes, but lifts many base materials permanently
	 * above their base price (see the log at start).
	 */
	public double recipeGuardModifierFactor = DEFAULT_RECIPE_GUARD_MODIFIER_FACTOR;

	static final double DEFAULT_RECIPE_GUARD_SELL_FACTOR = 1.68;
	static final double DEFAULT_RECIPE_GUARD_MODIFIER_FACTOR = 1.0;

	static final ConfigManager.Config<ShopConfig> CONFIG =
		ConfigManager.register("shop_settings", ShopConfig.class, ShopConfig::new, ShopConfig::validate);

	/** New object after {@code /smpeco reload}, so never cache it. */
	public static ShopConfig get() {
		return CONFIG.get();
	}

	static void validate(ShopConfig c) {
		if (!(c.treasurySharePercent >= 0 && c.treasurySharePercent <= 100)) throw new IllegalArgumentException("treasurySharePercent must be 0..100");
		if (c.dailyDealCount < 0 || c.dailyDealCount > ShopGui.MAIN_DEALS_SLOTS) throw new IllegalArgumentException("dailyDealCount must be 0..5 (space in the main menu)");
		if (c.dailyDealMinPercent < 1 || c.dailyDealMaxPercent > 40 || c.dailyDealMinPercent > c.dailyDealMaxPercent) {
			throw new IllegalArgumentException("dailyDealMinPercent/MaxPercent must be 1 <= min <= max <= 40");
		}
		if (c.dailyDealMinBasePrice < 0) throw new IllegalArgumentException("dailyDealMinBasePrice must be >= 0");
		if (c.dailyDealExcluded == null) c.dailyDealExcluded = new ArrayList<>();
		if (c.maxFavorites < 1 || c.maxFavorites > 200) throw new IllegalArgumentException("maxFavorites must be 1..200");
		if (!(c.recipeGuardSellFactor >= 1.0 && c.recipeGuardSellFactor <= 10.0)) throw new IllegalArgumentException("recipeGuardSellFactor must be 1.0..10.0");
		if (!(c.recipeGuardModifierFactor >= 1.0 && c.recipeGuardModifierFactor <= PriceModifiers.MAX_FACTOR)) {
			throw new IllegalArgumentException("recipeGuardModifierFactor must be 1.0.." + PriceModifiers.MAX_FACTOR + " (inclusive)");
		}
		DailyDeals.invalidate();
		RecipeGuard.invalidate();
	}

	/** Loads the class, and with it the config. */
	static void bootstrap() {
	}
}
