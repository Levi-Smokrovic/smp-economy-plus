package dev.smpeconomy.features.auction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import dev.smpeconomy.core.compat.Mc;
import dev.smpeconomy.core.Items;

/** Search by item name or ID. */
final class AuctionSearch {
	private AuctionSearch() {}

	static String normalize(String s) {
		return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
	}

	static boolean matches(ItemStack stack, String query) {
		String q = normalize(query);
		if (q.isEmpty()) return true;
		String hay = haystack(stack);
		for (String word : q.split("\\s+")) {
			if (word.isEmpty()) continue;
			if (hay.contains(word)) continue;
			if (hay.contains(word.replace(' ', '_'))) continue;
			return false;
		}
		return true;
	}

	static String haystack(ItemStack stack) {
		List<String> parts = new ArrayList<>();
		String id = Items.id(stack.getItem());
		parts.add(id);
		String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
		parts.add(path.replace('_', ' '));
		parts.add(Mc.itemName(stack.getItem()).getString());
		parts.add(stack.getHoverName().getString());
		addEnchantments(parts, stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY));
		addEnchantments(parts, stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY));
		return normalize(String.join(" | ", parts));
	}

	private static void addEnchantments(List<String> parts, ItemEnchantments enchantments) {
		for (Holder<Enchantment> h : enchantments.keySet()) {
			h.unwrapKey().ifPresent(k -> {
				parts.add(k.identifier().getPath());
				parts.add(k.identifier().getPath().replace('_', ' '));
			});
		}
	}
}
