package dev.smpeconomy.features.crates;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import dev.smpeconomy.core.compat.Mc;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.features.crates.CratesConfig.CrateDef;
import dev.smpeconomy.features.crates.CratesConfig.RewardDef;

/** Weighted draw, item building and reward texts. No state and no side effects. */
final class CrateRewards {
	private CrateRewards() {}

	/**
	 * Index for {@code r} in {@code [0, sum)}: entry i covers {@code [w0+...+w(i-1), w0+...+wi)}, so weight 0 is
	 * never picked.
	 */
	static int pickIndex(int[] weights, long r) {
		long acc = 0;
		for (int i = 0; i < weights.length; i++) {
			int w = Math.max(0, weights[i]);
			acc += w;
			if (w > 0 && r < acc) return i;
		}
		throw new IllegalArgumentException("random value " + r + " outside the weight sum " + acc);
	}

	static int[] weights(CrateDef crate) {
		int[] w = new int[crate.rewards.size()];
		for (int i = 0; i < w.length; i++) w[i] = crate.rewards.get(i).weight;
		return w;
	}

	static RewardDef roll(CrateDef crate, RandomGenerator random) {
		long total = crate.totalWeight();
		if (total <= 0) throw new IllegalStateException("crate " + crate.id + " has no weights");
		return crate.rewards.get(pickIndex(weights(crate), random.nextLong(total)));
	}

	/** 0 to 100. */
	static double chancePercent(CrateDef crate, RewardDef reward) {
		long total = crate.totalWeight();
		return total <= 0 ? 0 : 100.0 * Math.max(0, reward.weight) / total;
	}

	static String formatPercent(double pct) {
		if (pct >= 10) return String.format(java.util.Locale.ROOT, "%.1f%%", pct);
		if (pct >= 0.1) return String.format(java.util.Locale.ROOT, "%.2f%%", pct);
		return String.format(java.util.Locale.ROOT, "%.3f%%", pct);
	}

	/**
	 * Template stack with count 1; the real amount is kept separately because ItemStack codecs only allow up to
	 * 99. Books get {@code stored_enchantments}, everything else {@code enchantments}.
	 *
	 * @throws IllegalArgumentException unknown item or enchantment, or an enchantment that does not fit the item
	 */
	static ItemStack buildItem(RewardDef r, HolderLookup.Provider registries) {
		Item item = Items.byId(r.item).orElseThrow(() -> new IllegalArgumentException("unknown item '" + r.item + "'"));
		ItemStack stack = new ItemStack(item, 1);
		if (r.enchantments != null && !r.enchantments.isEmpty()) {
			HolderLookup.RegistryLookup<Enchantment> lookup = registries.lookupOrThrow(Registries.ENCHANTMENT);
			ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
			boolean book = stack.is(net.minecraft.world.item.Items.ENCHANTED_BOOK);
			for (Map.Entry<String, Integer> e : r.enchantments.entrySet()) {
				Identifier id = Identifier.tryParse(e.getKey());
				if (id == null) throw new IllegalArgumentException("invalid enchantment '" + e.getKey() + "'");
				Holder<Enchantment> holder = lookup.get(ResourceKey.create(Registries.ENCHANTMENT, id))
					.orElseThrow(() -> new IllegalArgumentException("unknown enchantment '" + e.getKey() + "'"));
				if (!book && !holder.value().canEnchant(stack)) {
					throw new IllegalArgumentException("enchantment '" + e.getKey() + "' does not fit " + r.item);
				}
				int level = e.getValue() == null ? 1 : e.getValue();
				if (level < 1 || level > 255) throw new IllegalArgumentException("level of '" + e.getKey() + "' must be 1..255");
				mutable.set(holder, level);
			}
			stack.set(EnchantmentHelper.getComponentType(stack), mutable.toImmutable());
		}
		if (r.name != null && !r.name.isBlank()) {
			stack.set(DataComponents.CUSTOM_NAME, Msg.legacy(r.name).withStyle(s -> s.withItalic(false)));
		}
		return stack;
	}

	/** Section-sign text. */
	static String moneyText(long cents) {
		return "§a" + Money.format(cents);
	}

	static String keyText(String crateId, int count) {
		return "§e" + count + "× " + CratesConfig.displayName(crateId) + " Key";
	}

	/** Vanilla name, books with enchantments, hover shows the item. */
	static MutableComponent itemText(ItemStack template, int count) {
		MutableComponent c = Msg.legacy("§f" + count + "× ").append(template.getHoverName().copy());
		List<Component> ench = enchantmentNames(template);
		if (!ench.isEmpty()) {
			c.append(Msg.legacy(" §8("));
			for (int i = 0; i < ench.size(); i++) {
				if (i > 0) c.append(Msg.legacy("§8, "));
				c.append(ench.get(i));
			}
			c.append(Msg.legacy("§8)"));
		}
		ItemStack hover = template.copyWithCount(Math.max(1, Math.min(count, template.getMaxStackSize())));
		return c.withStyle(s -> s.withHoverEvent(Mc.showItem(hover)));
	}

	static List<Component> enchantmentNames(ItemStack stack) {
		List<Component> names = new ArrayList<>();
		ItemEnchantments ench = stack.getOrDefault(EnchantmentHelper.getComponentType(stack), ItemEnchantments.EMPTY);
		for (Object2IntMap.Entry<Holder<Enchantment>> e : ench.entrySet()) {
			names.add(Enchantment.getFullname(e.getKey(), e.getIntValue()));
		}
		return names;
	}

	static Item moneyIcon(long cents) {
		if (cents >= 1_000_000) return net.minecraft.world.item.Items.GOLD_BLOCK; // From $10,000
		if (cents >= 100_000) return net.minecraft.world.item.Items.GOLD_INGOT; // From $1,000
		return net.minecraft.world.item.Items.GOLD_NUGGET;
	}
}
