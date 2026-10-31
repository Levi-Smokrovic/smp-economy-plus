package dev.smpeconomy.features.shop;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import net.minecraft.world.item.Item;
import dev.smpeconomy.core.BasePrices;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.PriceGuard;
import dev.smpeconomy.core.PriceModifiers;
import dev.smpeconomy.core.TimeUtil;

public final class ShopLore {
	private ShopLore() {}

	/** Price trend of an item from running modifiers. */
	public enum Trend {
		STABLE("§7● stable"),
		RISING("§a▲ rising"),
		FALLING("§c▼ falling"),
		MIXED("§e◆ volatile"),
		CHANGED("§e● changed");

		public final String label;

		Trend(String label) {
			this.label = label;
		}
	}

	/**
	 * An entry fading in moves toward its target, and after its end toward 1.0. While it holds, the price counts
	 * as changed and returns later. Mixed directions mean fluctuating.
	 */
	public static Trend trend(List<PriceModifiers.Active> actives, long now) {
		if (actives.isEmpty()) return Trend.STABLE;
		boolean up = false;
		boolean down = false;
		boolean holding = false;
		for (PriceModifiers.Active a : actives) {
			double towards = a.endsAtMs() > 0 && now >= a.endsAtMs() ? 1.0 : a.target();
			double diff = towards - a.current();
			if (Math.abs(diff) < 1e-4) {
				if (Math.abs(a.current() - 1.0) >= 1e-4) holding = true;
			} else if (diff > 0) {
				up = true;
			} else {
				down = true;
			}
		}
		if (up && down) return Trend.MIXED;
		if (up) return Trend.RISING;
		if (down) return Trend.FALLING;
		return holding ? Trend.CHANGED : Trend.STABLE;
	}

	/** Time in ms from which the next holding modifier fades out, or 0. */
	static long nextChange(List<PriceModifiers.Active> actives, long now) {
		long best = 0;
		for (PriceModifiers.Active a : actives) {
			if (a.endsAtMs() > now && (best == 0 || a.endsAtMs() < best)) best = a.endsAtMs();
		}
		return best;
	}

	public static List<String> priceLines(UUID player, Item item, int inventoryCount) {
		List<String> lines = new ArrayList<>();
		PriceGuard.Breakdown b = PriceGuard.breakdown(player, item);
		DailyDeals.Deal deal = DailyDeals.dealFor(item);
		if (deal != null && b.baseBuy() > 0) lines.add("§6§lDAILY DEAL §c-" + deal.percent() + "%");
		// The same price ShopService.buy charges
		long buy = b.effectiveBuy() > 0 ? ShopService.unitBuyPrice(player, item).orElse(-1) : -1;
		boolean recipeFloor = buy > b.effectiveBuy() && b.effectiveBuy() > 0;
		if (buy > 0) {
			String suffix = "";
			if (buy < b.baseBuy()) suffix = " §8instead of §m" + Money.format(b.baseBuy());
			else if (buy > b.baseBuy()) suffix = " §8(normally " + Money.format(b.baseBuy()) + ")";
			lines.add("§7Buy: §a" + Money.format(buy) + " §8each" + suffix);
			int stack = Math.max(1, item.getDefaultMaxStackSize());
			long stackPrice = ShopService.total(buy, stack);
			if (stack > 1 && stackPrice > 0) lines.add("§7Stack (" + stack + "): §a" + Money.format(stackPrice));
		} else {
			lines.add("§8Not available in the shop");
		}
		if (b.effectiveSell() > 0) lines.add("§7Sell: §e" + Money.format(b.effectiveSell()) + " §8each");
		else lines.add("§8Sell: not possible");
		for (String l : b.loreLines()) {
			// The daily deal already has its own line above; the bracketed addition comes from PriceGuard
			if (deal != null && l.contains(" " + DailyDeals.LABEL + " §8(")) continue;
			lines.add(l);
		}
		if (recipeFloor) lines.add("§8Minimum price active (recipe protection)");
		long now = System.currentTimeMillis();
		List<PriceModifiers.Active> actives = PriceModifiers.active(item);
		lines.add("§7Trend: " + trend(actives, now).label);
		long next = nextChange(actives, now);
		if (next > 0) lines.add("§8Back to normal price in " + TimeUtil.formatDuration(next - now));
		if (inventoryCount > 0) lines.add("§7In inventory: §f" + Msg.number(inventoryCount));
		return lines;
	}

	public static List<String> clickLines(boolean sellable, boolean favorite) {
		List<String> lines = new ArrayList<>();
		lines.add("");
		lines.add("§eLeft-click §7» Buy");
		if (sellable && ShopConfig.get().sellInShop) lines.add("§eRight-click §7» Sell");
		lines.add("§eShift+Right-click §7» " + (favorite ? "Remove favorite" : "Add to favorites ★"));
		return lines;
	}

	static boolean sellable(UUID player, Item item) {
		OptionalLong p = PriceGuard.effectiveSellPrice(player, item);
		return p.isPresent();
	}

	/** -1 if not in shop.json. */
	static long baseBuy(Item item) {
		return BasePrices.shopBuyPrice(item).orElse(-1);
	}
}
