package dev.smpeconomy.core;

import java.util.Set;

/**
 * Service from market to news: which items news must never hype automatically ({@link PriceModifiers}), because a hype
 * would lift them above what a player paid an NPC trader.
 *
 * <p>Nothing protects NPC offers of items without a shop pivot over time. Their price is set above every system payment
 * at listing time, but a later hype raises /sell, NPC buyers, orders and missions above that, so "buy from the NPC
 * before the hype, sell during it" would be risk-free money. News therefore never auto-hypes these items and their
 * family; admin hypes stay possible, with a warning. A crash is free of time arbitrage for them because market computes
 * offer floors without the crash.
 */
@FunctionalInterface
public interface MarketGoods {
	/**
	 * Immutable item ids for the current trader pool and active NPC offers: every good without a pivot, the ingredients
	 * its market value derives from for recipe estimates, and anything craftable from it within three steps
	 * (conversions like block to 9 ingots). Empty if market sells nothing a hype could affect.
	 */
	Set<String> shockSensitiveIds();
}
