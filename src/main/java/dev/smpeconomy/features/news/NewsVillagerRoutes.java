package dev.smpeconomy.features.news;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
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
import dev.smpeconomy.core.BasePrices;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.PriceGuard;
import dev.smpeconomy.core.TraderGoods;

/**
 * Shop items a flash sale must never discount, because villagers turn them into emeralds ("shop, villager, emerald, /sell"); a
 * flash sale pushes the shop price down to pivot + 1. A crash does not lower the shop price, since PriceGuard computes the buy
 * side without crashes.
 *
 * <p>Computed at server start and after price or datapack reload from vanilla trades (villagers including experimental ones and
 * the wandering trader, "items to emerald", fisherman boats hardcoded), all normal recipes, filling (bucket to water, milk or
 * lava bucket, glass bottle to water bottle) and current shop.json/prices.json prices. A trade "n × X to e emeralds" is critical
 * if X, on the cheapest route with all non-excluded shop items at pivot + 1, costs less than e × emerald sell price and less than
 * at normal price. The shop items of that route are then excluded until no trade is critical.
 *
 * <p>Emerald value: {@link TraderGoods#emeraldValue()} = max(sell price, best proceeds through villager goods). Trade results are
 * marked and not sellable themselves, but blocks (place and mine) and recipe results are again (at most {@value
 * TraderGoods#LIMIT_CENTS_PER_EMERALD} cents per emerald).
 *
 * <p>Not covered here: routes that already pay at normal price, and discounts from cured villagers (costs down to 1 item). News
 * just does not make them cheaper.
 *
 * <p>Before computing, or if it fails, all shop items count as excluded (rather no discount than a leak). Everything runs on the
 * server thread.
 */
final class NewsVillagerRoutes {
	record Trade(Item input, int count, int emeralds) {}

	record Recipe(Item out, int count, List<List<Item>> ingredients) {}

	/** {@code complete}: no critical trade left. {@code reasons}: one example trade per excluded item. */
	record Result(Set<Item> items, Map<Item, String> reasons, boolean complete) {}

	/** A master fisherman buys a boat for 1 emerald depending on village type; not readable without a villager entity. */
	static final List<String> BOAT_TRADES = List.of("minecraft:oak_boat", "minecraft:spruce_boat", "minecraft:jungle_boat",
		"minecraft:acacia_boat", "minecraft:dark_oak_boat");
	private static final double INF = Double.POSITIVE_INFINITY;
	private static final double EPS = 1e-9;

	private static List<Trade> trades = List.of();
	private static List<Recipe> recipes = List.of();
	private static boolean ready;
	private static boolean failed;
	private static Set<Item> cached;
	private static Map<Item, String> cachedReasons = Map.of();

	private NewsVillagerRoutes() {}

	static void init(MinecraftServer server) {
		cached = null;
		failed = false;
		try {
			trades = List.copyOf(readTrades(server.overworld()));
			recipes = List.copyOf(readRecipes(server));
			ready = true;
		} catch (RuntimeException e) {
			ready = false;
			SmpEconomy.LOG.error("[SMPEco] news: villager trades unreadable, crash/flash sale skip all shop items", e);
		}
	}

	static void invalidate() {
		cached = null;
		failed = false;
	}

	static boolean excluded(Item item) {
		Set<Item> set = items();
		return set == null ? BasePrices.shopBuyPrice(item).isPresent() : set.contains(item);
	}

	/** Null means not computed yet or failed, and all shop items count as excluded. */
	static Set<Item> items() {
		if (!ready || failed) return null;
		if (cached == null) {
			try {
				Result r = compute(trades, recipes, BasePrices.shopBuyPrices(), emeraldValue(), Set.of());
				if (!r.complete()) throw new IllegalStateException("critical trades remain");
				cached = Set.copyOf(r.items());
				cachedReasons = Map.copyOf(r.reasons());
				SmpEconomy.LOG.info("[SMPEco] news: {} shop items never in a flash sale (villager trades, {} trades, {} recipes)",
					cached.size(), trades.size(), recipes.size());
			} catch (RuntimeException e) {
				failed = true;
				SmpEconomy.LOG.error("[SMPEco] news: villager check failed, crash/flash sale skip all shop items", e);
				return null;
			}
		}
		return cached;
	}

	static long emeraldValue() {
		return TraderGoods.emeraldValue();
	}

	static String reason(Item item) {
		return items() == null ? null : cachedReasons.get(item);
	}

	static List<Trade> trades() {
		return trades;
	}

	static List<Recipe> recipes() {
		return recipes;
	}

	static List<Trade> readTrades(ServerLevel level) {
		Map<String, Trade> found = new LinkedHashMap<>();
		// Only "items to emerald": EmeraldForItems does not read the entity, other trades need a real villager
		VanillaTrades.Result trades = VanillaTrades.offers(level, VanillaTrades.Kind.EMERALDS_FOR_ITEMS, VanillaTrades.Experimental.ALWAYS, "news");
		for (VanillaTrades.Offer offer : trades.offers()) addTrade(found, offer);
		for (String boat : BOAT_TRADES) {
			Items.byId(boat).ifPresent(item -> found.putIfAbsent(boat + "|1|1", new Trade(item, 1, 1)));
		}
		return new ArrayList<>(found.values());
	}

	private static void addTrade(Map<String, Trade> found, VanillaTrades.Offer offer) {
		ItemStack cost = offer.costA();
		ItemStack result = offer.result();
		if (cost.isEmpty() || !offer.costB().isEmpty() || !result.is(net.minecraft.world.item.Items.EMERALD)) return;
		Trade t = new Trade(cost.getItem(), cost.getCount(), result.getCount());
		found.putIfAbsent(Items.id(t.input()) + "|" + t.count() + "|" + t.emeralds(), t);
	}

	static List<Recipe> readRecipes(MinecraftServer server) {
		HolderLookup.Provider registries = server.registryAccess();
		List<Recipe> list = new ArrayList<>();
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
				List<List<Item>> options = new ArrayList<>(ingredients.size());
				for (Ingredient ing : ingredients) {
					List<Item> items = ing.items().map(Holder::value).distinct().toList();
					if (items.isEmpty()) {
						options = null;
						break;
					}
					options.add(items);
				}
				if (options != null) list.add(new Recipe(out.getItem(), out.getCount(), List.copyOf(options)));
			} catch (RuntimeException e) {
				SmpEconomy.LOG.debug("[SMPEco] news: skipped recipe {}: {}", holder.id(), e.toString());
			}
		}
		list.addAll(fillRecipes());
		return list;
	}

	/** Filling without a recipe, since water, milk and lava cost nothing. */
	static List<Recipe> fillRecipes() {
		List<Recipe> list = new ArrayList<>();
		Item bucket = net.minecraft.world.item.Items.BUCKET;
		for (Item filled : List.of(net.minecraft.world.item.Items.WATER_BUCKET, net.minecraft.world.item.Items.MILK_BUCKET,
			net.minecraft.world.item.Items.LAVA_BUCKET)) {
			list.add(new Recipe(filled, 1, List.of(List.of(bucket))));
		}
		list.add(new Recipe(net.minecraft.world.item.Items.POTION, 1, List.of(List.of(net.minecraft.world.item.Items.GLASS_BOTTLE))));
		return list;
	}

	/** Cheapest production cost per item (cents per unit) and its route ({@code via} -1 = shop, otherwise recipe index). */
	record Costs(Map<Item, Double> cost, Map<Item, Integer> via) {
		double of(Item item) {
			return cost.getOrDefault(item, INF);
		}
	}

	/**
	 * Cost of all items from shop purchases and recipes. {@code news}: non-excluded shop items cost only pivot + 1 (the lowest a
	 * flash sale reaches), otherwise base buy price.
	 */
	static Costs solve(List<Recipe> recipes, Map<Item, Long> shop, Set<Item> excluded, boolean news) {
		Map<Item, Double> cost = new HashMap<>();
		Map<Item, Integer> via = new HashMap<>();
		for (Map.Entry<Item, Long> e : shop.entrySet()) {
			long base = e.getValue();
			if (base <= 0) continue;
			long floor = PriceGuard.pivot(base) + 1;
			cost.put(e.getKey(), (double) (news && !excluded.contains(e.getKey()) ? Math.min(base, floor) : base));
			via.put(e.getKey(), -1);
		}
		for (int round = 0; round < 64; round++) {
			boolean changed = false;
			for (int k = 0; k < recipes.size(); k++) {
				Recipe r = recipes.get(k);
				if (r.count() <= 0) continue;
				double c = 0;
				for (List<Item> options : r.ingredients()) {
					double best = INF;
					for (Item o : options) best = Math.min(best, cost.getOrDefault(o, INF));
					c += best;
					if (c == INF) break;
				}
				if (c == INF) continue;
				c /= r.count();
				if (c < cost.getOrDefault(r.out(), INF) - EPS) {
					cost.put(r.out(), c);
					via.put(r.out(), k);
					changed = true;
				}
			}
			if (!changed) break;
		}
		return new Costs(cost, via);
	}

	static void shopItemsOnPath(Item item, Costs costs, List<Recipe> recipes, Set<Item> seen, Set<Item> out) {
		if (!seen.add(item)) return;
		Integer via = costs.via().get(item);
		if (via == null) return;
		if (via < 0) {
			out.add(item);
			return;
		}
		for (List<Item> options : recipes.get(via).ingredients()) {
			Item best = null;
			for (Item o : options) {
				if (best == null || costs.of(o) < costs.of(best)) best = o;
			}
			if (best != null && costs.of(best) < INF) shopItemsOnPath(best, costs, recipes, seen, out);
		}
	}

	/** Critical: the trade pays and is cheaper than at normal price. */
	static boolean critical(Trade t, Costs news, Costs normal, long emeraldSell) {
		double c = news.of(t.input());
		if (!(c < INF) || emeraldSell <= 0) return false;
		return t.count() * c < (double) t.emeralds() * emeraldSell - EPS && c < normal.of(t.input()) - EPS;
	}

	/**
	 * Excludes the shop items on the cheapest route of each critical trade until none is left. {@code start} is already excluded
	 * (stays at normal price) but is not returned.
	 */
	static Result compute(List<Trade> trades, List<Recipe> recipes, Map<Item, Long> shop, long emeraldSell, Set<Item> start) {
		Set<Item> excluded = new HashSet<>(start);
		Set<Item> added = new LinkedHashSet<>();
		Map<Item, String> reasons = new LinkedHashMap<>();
		Costs normal = solve(recipes, shop, Set.of(), false);
		for (int round = 0; round < 256; round++) {
			Costs news = solve(recipes, shop, excluded, true);
			boolean any = false;
			boolean progress = false;
			for (Trade t : trades) {
				if (!critical(t, news, normal, emeraldSell)) continue;
				any = true;
				Set<Item> path = new LinkedHashSet<>();
				shopItemsOnPath(t.input(), news, recipes, new HashSet<>(), path);
				for (Item i : path) {
					if (!excluded.add(i)) continue;
					added.add(i);
					progress = true;
					reasons.putIfAbsent(i, t.count() + "× " + Items.id(t.input()) + " → " + t.emeralds() + " emeralds");
				}
			}
			if (!any) return new Result(added, reasons, true);
			if (!progress) break;
		}
		return new Result(added, reasons, false);
	}

	static List<Trade> criticalTrades(List<Trade> trades, List<Recipe> recipes, Map<Item, Long> shop, long emeraldSell, Set<Item> excluded) {
		Costs normal = solve(recipes, shop, Set.of(), false);
		Costs news = solve(recipes, shop, excluded, true);
		List<Trade> list = new ArrayList<>();
		for (Trade t : trades) {
			if (critical(t, news, normal, emeraldSell)) list.add(t);
		}
		return list;
	}
}
