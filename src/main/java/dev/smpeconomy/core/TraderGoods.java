package dev.smpeconomy.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SingleItemRecipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import dev.smpeconomy.core.compat.Mc;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.compat.VanillaTrades;

/**
 * Villagers and wandering traders trade at fixed rates, and an emerald only costs a few cheap items (9 glass bottles
 * are about 3 sand; cured villagers even 1 item). Without marking, "1 emerald to 6 bread to /sell" would pay $9 per
 * emerald, scalable through breeding and outside the treasury cap.
 *
 * <p>{@code core.mixin.MerchantOfferMixin} on {@code MerchantOffer#assemble} marks every otherwise unmodified trade
 * result with {@code custom_data {smpeco_trader_goods:1b}} and a lore line. It is then no longer
 * {@link Items#isPristine}, so /sell, shop right-click, NPC orders, mission deliveries and NPC buyers reject it. Exempt:
 * emeralds (the currency, sell price close to cost) and already modified results such as enchanted books, which are
 * unsellable anyway and which player orders with a template should keep accepting.
 *
 * <p>The mark does not survive everything, so prices limit the rest ({@link #analyze}, selftest
 * {@code core/villager-trades}): blocks lose it when placed and broken, and crafted results come out fresh. Villager goods may
 * yield at most {@value #LIMIT_CENTS_PER_EMERALD} cents per emerald on these paths, which is why quartz blocks and
 * bricks are not sellable. Wandering traders cannot be bred and offer few trades, so they are only reported.
 */
public final class TraderGoods {
	/** Key in {@code minecraft:custom_data}. */
	public static final String MARKER = "smpeco_trader_goods";
	public static final String LORE = "§8Trader goods – can't be sold to the server";
	/** Maximum cents villager goods may yield per emerald through placing and breaking or a recipe. */
	public static final long LIMIT_CENTS_PER_EMERALD = 100;

	/** -1 means recompute. */
	private static long emeraldValue = -1;

	static {
		BasePrices.onReload(TraderGoods::invalidate);
	}

	private TraderGoods() {}

	public static void markTradeResult(ItemStack stack) {
		if (stack == null || stack.isEmpty() || stack.is(net.minecraft.world.item.Items.EMERALD)) return;
		if (!stack.getComponentsPatch().isEmpty()) return;
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(MARKER, true));
		ItemLore lore = stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY);
		stack.set(DataComponents.LORE, lore.withLineAdded(Msg.legacy(LORE).withStyle(s -> s.withItalic(false))));
	}

	public static boolean isMarked(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return false;
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data != null && data.copyTag().contains(MARKER);
	}

	/** One trader purchase and what the marked result yields per emerald on the best unmarked path ({@code via}). */
	public record Leak(boolean wandering, Item output, int count, int emeralds, long centsPerEmerald, String via) {
		public String describe() {
			return (wandering ? "trader " : "") + emeralds + " emerald -> " + count + "× " + Items.id(output) + " = " + centsPerEmerald
				+ " ¢/emerald (" + via + ")";
		}
	}

	public record Analysis(List<Leak> leaks, int offers, int skipped) {
		public List<Leak> villagerViolations() {
			return leaks.stream().filter(l -> !l.wandering() && l.centsPerEmerald() > LIMIT_CENTS_PER_EMERALD).toList();
		}

		public long villagerMax() {
			return leaks.stream().filter(l -> !l.wandering()).mapToLong(Leak::centsPerEmerald).max().orElse(0);
		}
	}

	/**
	 * Rates all vanilla emerald purchases whose result gets marked (villagers including experimental trades, wandering
	 * trader). A marked item's value is the max of its sell price if it is a block (placing and breaking returns it
	 * fresh, with silk touch even glass) and its best recipe path: count x result sell price, minus other ingredients
	 * at the lower of sell and shop price, divided by slots holding this item. Ingredient B of a swap is subtracted.
	 * Uses base prices without modifiers. Trades needing a real villager (biome-bound, enchanted) are skipped since
	 * their result is modified anyway. Server thread.
	 */
	public static Analysis analyze(MinecraftServer server) {
		Map<Item, List<Recipe>> byIngredient = recipesByIngredient(server);
		List<Leak> leaks = new ArrayList<>();
		VanillaTrades.Result trades = VanillaTrades.offers(server.overworld(), VanillaTrades.Kind.ALL, VanillaTrades.Experimental.ALWAYS, null);
		int evaluated = 0;
		for (VanillaTrades.Offer offer : trades.offers()) {
			if (evaluate(offer, byIngredient, leaks)) evaluated++;
		}
		leaks.sort(Comparator.comparingLong(Leak::centsPerEmerald).reversed());
		return new Analysis(List.copyOf(leaks), evaluated, trades.skipped());
	}

	/**
	 * Most an emerald can yield when sold to the system: max(sell price, best villager goods value from
	 * {@link #analyze}). If there is no server or rating fails, {@link #LIMIT_CENTS_PER_EMERALD} as a cautious value. Cached
	 * until the next price reload.
	 */
	public static long emeraldValue() {
		long sell = BasePrices.sellPrice(net.minecraft.world.item.Items.EMERALD).orElse(0);
		if (emeraldValue >= 0) return Math.max(sell, emeraldValue);
		MinecraftServer server = SmpEconomy.server();
		if (server == null || !server.isSameThread()) return Math.max(sell, LIMIT_CENTS_PER_EMERALD);
		try {
			emeraldValue = analyze(server).villagerMax();
		} catch (RuntimeException e) {
			SmpEconomy.LOG.error("[SMPEco] Trader goods cannot be rated, an emerald counts as {} ¢", LIMIT_CENTS_PER_EMERALD, e);
			return Math.max(sell, LIMIT_CENTS_PER_EMERALD);
		}
		return Math.max(sell, emeraldValue);
	}

	public static void invalidate() {
		emeraldValue = -1;
	}

	/** False if the offer is not such a purchase; it then is not counted. */
	private static boolean evaluate(VanillaTrades.Offer offer, Map<Item, List<Recipe>> byIngredient, List<Leak> out) {
		ItemStack costA = offer.costA();
		ItemStack costB = offer.costB();
		ItemStack result = offer.result();
		if (!costA.is(net.minecraft.world.item.Items.EMERALD) || costA.getCount() <= 0) return false;
		if (result.isEmpty() || result.is(net.minecraft.world.item.Items.EMERALD) || !result.getComponentsPatch().isEmpty()) return false;
		Value v = launderValue(result.getItem(), byIngredient);
		double gain = result.getCount() * v.cents() - (costB.isEmpty() ? 0 : costB.getCount() * (double) cost(costB.getItem()));
		long perEmerald = (long) Math.floor(Math.max(0, gain) / costA.getCount() + 1e-9);
		if (perEmerald > 0) out.add(new Leak(offer.wandering(), result.getItem(), result.getCount(), costA.getCount(), perEmerald, v.via()));
		return true;
	}

	private record Value(double cents, String via) {}

	private static Value launderValue(Item item, Map<Item, List<Recipe>> byIngredient) {
		double best = 0;
		String via = "-";
		long own = BasePrices.sellPrice(item).orElse(0);
		if (item instanceof BlockItem && own > 0) {
			best = own;
			via = "place + break";
		}
		for (Recipe r : byIngredient.getOrDefault(item, List.of())) {
			long outSell = BasePrices.sellPrice(r.out()).orElse(0);
			if (outSell <= 0) continue;
			int same = 0;
			double others = 0;
			for (List<Item> slot : r.ingredients()) {
				if (slot.contains(item)) {
					same++;
					continue;
				}
				double cheapest = Double.POSITIVE_INFINITY;
				for (Item o : slot) cheapest = Math.min(cheapest, cost(o));
				others += cheapest == Double.POSITIVE_INFINITY ? 0 : cheapest;
			}
			if (same == 0) continue;
			double value = (r.count() * (double) outSell - others) / same;
			if (value > best) {
				best = value;
				via = "recipe -> " + r.count() + "× " + Items.id(r.out());
			}
		}
		return new Value(best, via);
	}

	/** The lower of sell price (lost revenue) and shop price, ignoring zeros. */
	private static long cost(Item item) {
		OptionalLong sell = BasePrices.sellPrice(item);
		OptionalLong buy = BasePrices.shopBuyPrice(item);
		if (sell.isPresent() && buy.isPresent()) return Math.min(sell.getAsLong(), buy.getAsLong());
		return sell.isPresent() ? sell.getAsLong() : buy.orElse(0);
	}

	private record Recipe(Item out, int count, List<List<Item>> ingredients) {}

	private static Map<Item, List<Recipe>> recipesByIngredient(MinecraftServer server) {
		HolderLookup.Provider registries = server.registryAccess();
		Map<Item, List<Recipe>> index = new HashMap<>();
		Collection<RecipeHolder<?>> all = server.getRecipeManager().getRecipes();
		for (RecipeHolder<?> holder : all) {
			net.minecraft.world.item.crafting.Recipe<?> recipe = holder.value();
			try {
				if (recipe.isSpecial()) continue;
				List<Ingredient> ingredients = recipe.placementInfo().ingredients();
				if (ingredients.isEmpty()) continue;
				ItemStack out;
				if (recipe instanceof ShapedRecipe r) out = Mc.assemble(r, CraftingInput.EMPTY, registries);
				else if (recipe instanceof ShapelessRecipe r) out = Mc.assemble(r, CraftingInput.EMPTY, registries);
				else if (recipe instanceof SingleItemRecipe r) out = Mc.assemble(r, new SingleRecipeInput(ItemStack.EMPTY), registries);
				else continue;
				if (out == null || out.isEmpty()) continue;
				List<List<Item>> slots = new ArrayList<>(ingredients.size());
				for (Ingredient ing : ingredients) {
					List<Item> items = ing.items().map(Holder::value).distinct().toList();
					if (items.isEmpty()) {
						slots = null;
						break;
					}
					slots.add(items);
				}
				if (slots == null) continue;
				Recipe r = new Recipe(out.getItem(), out.getCount(), List.copyOf(slots));
				java.util.Set<Item> seen = new java.util.HashSet<>();
				for (List<Item> slot : slots) {
					for (Item i : slot) {
						if (seen.add(i)) index.computeIfAbsent(i, k -> new ArrayList<>()).add(r);
					}
				}
			} catch (RuntimeException e) {
				SmpEconomy.LOG.debug("[SMPEco] Trader goods: recipe {} skipped: {}", holder.id(), e.toString());
			}
		}
		return index;
	}
}
