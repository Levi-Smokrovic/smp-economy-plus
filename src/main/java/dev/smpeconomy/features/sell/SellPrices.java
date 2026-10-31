package dev.smpeconomy.features.sell;

import java.util.OptionalLong;
import java.util.UUID;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import dev.smpeconomy.core.BasePrices;
import dev.smpeconomy.core.PriceGuard;

public final class SellPrices {
	private SellPrices() {}

	public static OptionalLong get(Item item) {
		return BasePrices.sellPrice(item);
	}

	public static OptionalLong effective(UUID player, Item item) {
		return PriceGuard.effectiveSellPrice(player, item);
	}

	public static OptionalLong effective(UUID player, ItemStack stack) {
		return SellService.unitPrice(player, stack);
	}
}
