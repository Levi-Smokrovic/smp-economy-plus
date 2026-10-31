package dev.smpeconomy.features.auction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.PriceGuard;
import dev.smpeconomy.core.Services;

/**
 * Preview and confirmation for {@code /ah sell <total>}. The price covers the whole stack; a player who reads
 * it as a unit price (as in {@code /orders create} or the shop) would give away 64 diamonds for the price of
 * one. More than one item always needs a repeat with the same slot, stack and price within {@link
 * AuctionConfig#sellConfirmSeconds}. A single item needs it only when priced below /sell or below {@link
 * AuctionConfig#sellWarnBelowMarketPercent} % of market value. Server thread only. Time is passed in so
 * selftests can control it.
 */
final class AuctionSellConfirm {
	/** {@code unitMarket} and {@code unitSell} are per item, 0 when unknown or not sellable to the system. */
	record Quote(int count, long price, long unitMarket, long unitSell, boolean belowSell, boolean belowMarket) {
		boolean warn() {
			return belowSell || belowMarket;
		}

		boolean needsConfirm() {
			return count > 1 || warn();
		}
	}

	enum Decision {
		LIST,
		CONFIRM,
		/** Like {@link #CONFIRM}, but a pending confirmation at the same price no longer matches (other slot or stack). */
		CHANGED
	}

	private record Pending(long until, int slot, ItemStack stack, long price) {}

	private static final Map<UUID, Pending> PENDING = new HashMap<>();

	private AuctionSellConfirm() {}

	/** Reads market value and /sell price fresh on every call. */
	static Quote quote(UUID seller, ItemStack stack, long price) {
		int count = Math.max(1, stack.getCount());
		long unitMarket = AuctionService.durabilityShare(Services.marketValue(stack.getItem()).orElse(0), stack);
		long unitSell = Items.isPristine(stack) ? PriceGuard.effectiveSellPrice(seller, stack.getItem()).orElse(0) : 0;
		boolean belowSell = unitSell > 0 && price < AuctionService.times(unitSell, count);
		double percent = AuctionConfig.get().sellWarnBelowMarketPercent;
		boolean belowMarket = unitMarket > 0 && percent > 0
			&& price < Money.percentOf(AuctionService.times(unitMarket, count), percent);
		return new Quote(count, price, unitMarket, unitSell, belowSell, belowMarket);
	}

	/**
	 * {@link Decision#LIST} consumes a matching pending confirmation; {@link Decision#CONFIRM} and {@link
	 * Decision#CHANGED} store the new preview, replacing an older one.
	 */
	static Decision decide(UUID player, int slot, ItemStack hand, Quote q, long nowMs) {
		Pending p = PENDING.remove(player);
		if (!q.needsConfirm()) return Decision.LIST;
		boolean open = p != null && nowMs <= p.until() && p.price() == q.price();
		if (open && p.slot() == slot && ItemStack.matches(p.stack(), hand)) return Decision.LIST;
		long window = AuctionConfig.get().sellConfirmSeconds * 1000L;
		PENDING.put(player, new Pending(nowMs + window, slot, hand.copy(), q.price()));
		return open ? Decision.CHANGED : Decision.CONFIRM;
	}

	static void clear(UUID player) {
		PENDING.remove(player);
	}

	/**
	 * Warnings as section-sign text without the warning symbol, shared by the chat preview and the {@link
	 * AuctionSellGui} dialog.
	 */
	static List<String> warnings(Quote q) {
		List<String> out = new ArrayList<>(2);
		if (q.belowSell()) {
			out.add("§c/sell pays you more: §a" + Money.format(q.unitSell()) + "§c each"
				+ (q.count() > 1 ? " (§a" + Money.format(AuctionService.times(q.unitSell(), q.count())) + "§c for all)" : "") + ".");
		}
		if (q.belowMarket()) {
			out.add("§cWay below market value: about §a" + Money.format(q.unitMarket()) + "§c each"
				+ (q.count() > 1 ? " (§a" + Money.format(AuctionService.times(q.unitMarket(), q.count())) + "§c for all)" : "") + ".");
		}
		return out;
	}

	/**
	 * Total if {@code q.price()} was meant as a unit price, for "Did you mean $x each?". Only for stacks with a
	 * warning and when that total is itself valid; otherwise 0.
	 */
	static long eachInstead(Quote q, long maxPrice) {
		if (q.count() <= 1 || !q.warn()) return 0;
		long total = AuctionService.times(q.price(), q.count());
		return total < Money.MAX && total <= maxPrice ? total : 0;
	}

	/**
	 * Price field default: market value times count, prorated by durability, at least what /sell pays, so the
	 * suggestion never triggers a warning itself. 0 when market value is unknown or the item has contents the
	 * market value does not cover.
	 */
	static long suggestedTotal(UUID seller, ItemStack stack, long maxPrice) {
		if (stack.isEmpty() || AuctionService.holdsItems(stack)) return 0;
		int count = Math.max(1, stack.getCount());
		long unitMarket = AuctionService.durabilityShare(Services.marketValue(stack.getItem()).orElse(0), stack);
		if (unitMarket <= 0) return 0;
		long unitSell = Items.isPristine(stack) ? PriceGuard.effectiveSellPrice(seller, stack.getItem()).orElse(0) : 0;
		long total = AuctionService.times(Math.max(unitMarket, unitSell), count);
		return Math.min(total, maxPrice);
	}

	static String unitText(long price, int count) {
		long n = Math.max(1, count);
		if (price % n == 0) return Money.format(price / n);
		long rounded = price / n + (price % n * 2 >= n ? 1 : 0);
		return rounded <= 0 ? "< " + Money.format(1) : "≈ " + Money.format(rounded);
	}
}
