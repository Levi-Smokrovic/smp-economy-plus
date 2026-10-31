package dev.smpeconomy.features.shop;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.ToLongFunction;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SingleItemRecipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import dev.smpeconomy.core.compat.Mc;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.BasePrices;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.PriceGuard;
import dev.smpeconomy.core.PriceModifiers;

/**
 * No recipe may turn shop items into output worth more than the input cost, under any mix of events, ranks and
 * discounts.
 *
 * <p>PriceGuard protects each item through its own pivot, but a recipe makes a different item: buy the
 * ingredient at its low (crash, flash sale, daily deal, down to pivot + 1), process it, and sell the result
 * with sell bonuses (market day times trader rank) and item modifiers (news hype, admin {@code pricemod}).
 *
 * <p>Worst-case model:
 * <ul>
 * <li>Cost is transitive: anything craftable from shop items, including through non-shop intermediates, gets
 * its cheapest production cost ({@link #derivedCosts}, Bellman-Ford over crafting, furnace and stonecutter
 * recipes). Shop items cost their lowest possible price, fuel is free (lava and kelp are free to get), and
 * returned containers are credited.</li>
 * <li>Revenue is {@link #worstSellValue}: shop items at the pivot, since modifiers can always lift their sale
 * there, others at base x {@code recipeGuardSellFactor} x item modifier.</li>
 * <li>Over time (buy low today, sell into a later hype), the modifier is {@code recipeGuardModifierFactor}:
 * {@link #staticFloor}.</li>
 * <li>Live: while a modifier above that factor runs on a non-shop result, floors are recomputed with its upper
 * bound until it ends ({@link #potential}), so buying during a hype never pays however strong the hype.</li>
 * </ul>
 * If a recipe is not a loss, the prices of the shop ingredients on its cheapest path are raised proportionally
 * until no recipe profits ({@link #raise}). The shop price is {@code max(PriceGuard.effectiveShopBuyPrice,
 * floor)}, which only ever raises. Static floors above the base price are reported by the selftest, and
 * shop.json or prices.json should be adjusted.
 *
 * <p>Computed at server start and lazily after price, config, datapack or modifier changes, always on the
 * server thread.
 */
final class RecipeGuard {
	/** Default data needs at most 10. */
	static final int MAX_ROUNDS = 256;
	/** Recipe chains are short. */
	static final int MAX_COST_PASSES = 64;
	/** Cents, for floating point comparisons. A tie counts as profit. */
	static final double EPS = 1e-6;

	/**
	 * {@code count} of {@code output} from one ingredient per slot (candidates sorted by ID). {@code crafting}
	 * means returns such as buckets count; otherwise furnace or stonecutter.
	 */
	record Edge(String id, Item output, int count, List<List<Item>> slots, boolean crafting) {}

	/**
	 * {@code gross} is the sum of shop price x amount over the base ingredients in {@code roots}; {@code refund}
	 * is the value of returned containers. Per item for an item, a total for {@link #run}.
	 */
	record Cost(double gross, double refund, Map<Item, Double> roots) {
		double net() {
			return gross - refund;
		}

		Cost per(int count) {
			if (count <= 1) return this;
			Map<Item, Double> r = new HashMap<>();
			roots.forEach((k, q) -> r.put(k, q / count));
			return new Cost(gross / count, refund / count, r);
		}
	}

	/** A recipe that does not lose at the given prices. */
	record Violation(String id, double value, double cost) {}

	record Graph(List<Edge> edges, Map<Item, ItemStack> remainders, Set<Item> outputs) {
		static final Graph EMPTY = new Graph(List.of(), Map.of(), Set.of());
	}

	private static Graph graph = Graph.EMPTY;
	private static Map<Item, Long> staticFloors = Map.of();
	/** Floors including running hypes; per item at least the static floor. */
	private static Map<Item, Long> liveFloors = Map.of();
	private static boolean dirty = true;
	private static boolean liveDirty = true;

	private RecipeGuard() {}

	static void register() {
		BasePrices.onReload(RecipeGuard::invalidate);
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> invalidate());
		PriceModifiers.addListener(RecipeGuard::invalidateLive);
	}

	static void invalidate() {
		dirty = true;
		liveDirty = true;
	}

	/**
	 * PriceModifiers calls this on every set, fade-out and expiry; in between an entry cannot exceed its bound
	 * ({@link #potential}).
	 */
	static void invalidateLive() {
		liveDirty = true;
	}

	/** Current floor per item including running hypes; 0 means none. */
	static long floor(Item item) {
		ensureFresh();
		Long s = staticFloors.get(item);
		Long l = liveFloors.get(item);
		return Math.max(s == null ? 0 : s, l == null ? 0 : l);
	}

	/** Floor over time, without running hypes; 0 means none. */
	static long staticFloor(Item item) {
		ensureFresh();
		Long f = staticFloors.get(item);
		return f == null ? 0 : f;
	}

	static Map<Item, Long> floors() {
		ensureFresh();
		Map<Item, Long> all = new HashMap<>(staticFloors);
		liveFloors.forEach((k, v) -> all.merge(k, v, Math::max));
		return all;
	}

	static Map<Item, Long> staticFloors() {
		ensureFresh();
		return staticFloors;
	}

	static int lastCheckCount() {
		ensureFresh();
		return graph.edges().size();
	}

	/** max(pivot + 1, recipe floor, 1); -1 when not in the shop. */
	static long lowestPrice(Item item) {
		OptionalLong base = BasePrices.shopBuyPrice(item);
		if (base.isEmpty()) return -1;
		return Math.max(lowestGuardPrice(base.getAsLong()), floor(item));
	}

	static long lowestGuardPrice(long baseBuy) {
		return Math.max(1, PriceGuard.pivot(baseBuy) + 1);
	}

	/**
	 * Highest unit price the system pays in the worst case; 0 means not sellable. Shop items: the pivot. Others:
	 * floor(base x {@code sellFactor} x {@code modifierFactor}).
	 */
	static long worstSellValue(Item item, double sellFactor, double modifierFactor) {
		OptionalLong base = BasePrices.sellPrice(item);
		if (base.isEmpty()) return 0;
		long pivot = PriceGuard.pivotPrice(item);
		if (pivot >= 0) return pivot;
		return PriceGuard.computeSell(base.getAsLong(), modifierFactor, sellFactor, -1);
	}

	/**
	 * Upper bound of the item modifier until the next listener call: the product over all entries of max(1,
	 * current, target). A fade-in runs to its target and a fade-out back to 1.0 while the target stays stored;
	 * crashes count as 1.0. Capped at {@link PriceModifiers#MAX_FACTOR}.
	 */
	static double potential(List<PriceModifiers.Active> actives) {
		double p = 1.0;
		for (PriceModifiers.Active a : actives) {
			double f = Math.max(1.0, Math.max(a.current(), a.target()));
			p *= Double.isFinite(f) ? f : PriceModifiers.MAX_FACTOR;
		}
		return Math.min(PriceModifiers.MAX_FACTOR, p);
	}

	private static void ensureFresh() {
		if (!dirty && !liveDirty) return;
		MinecraftServer server = SmpEconomy.server();
		if (server == null || !server.isSameThread()) return;
		if (dirty) rebuild(server);
		if (liveDirty) rebuildLive();
	}

	private static double sellFactor(ShopConfig cfg) {
		return cfg == null ? ShopConfig.DEFAULT_RECIPE_GUARD_SELL_FACTOR : cfg.recipeGuardSellFactor;
	}

	private static double modifierFactor(ShopConfig cfg) {
		return cfg == null ? ShopConfig.DEFAULT_RECIPE_GUARD_MODIFIER_FACTOR : cfg.recipeGuardModifierFactor;
	}

	static Map<Item, Long> lowestGuardPrices() {
		Map<Item, Long> lowest = new HashMap<>();
		for (Map.Entry<Item, Long> e : BasePrices.shopBuyPrices().entrySet()) lowest.put(e.getKey(), lowestGuardPrice(e.getValue()));
		return lowest;
	}

	/** If this fails the old floors stay active and the error is logged. */
	static void rebuild(MinecraftServer server) {
		dirty = false;
		liveDirty = true;
		try {
			ShopConfig cfg = ShopConfig.get();
			double sell = sellFactor(cfg);
			double mod = modifierFactor(cfg);
			Graph g = collect(server);
			Map<Item, Long> lowest = lowestGuardPrices();
			ToLongFunction<Item> value = item -> worstSellValue(item, sell, mod);
			Map<Item, Long> result = computeFloors(g.edges(), lowest, value, remainderValues(g.remainders(), value));
			graph = g;
			staticFloors = Map.copyOf(result);
			if (!result.isEmpty()) {
				SmpEconomy.LOG.warn("[SMPEco] Shop: recipe guard raises {} floors (sell factor {}, modifier {}): {}. Adjust shop.json or prices.json",
					result.size(), sell, mod, describe(result, lowest));
			}
		} catch (RuntimeException e) {
			SmpEconomy.LOG.error("[SMPEco] Shop: recipe guard computation failed, old floors stay active", e);
		}
	}

	/**
	 * Like the static floors, but non-shop results are valued at max({@code recipeGuardModifierFactor}, {@link
	 * #potential}).
	 */
	private static void rebuildLive() {
		liveDirty = false;
		try {
			ShopConfig cfg = ShopConfig.get();
			double sell = sellFactor(cfg);
			double mod = modifierFactor(cfg);
			Map<Item, List<PriceModifiers.Active>> byItem = new HashMap<>();
			for (PriceModifiers.Active a : PriceModifiers.active()) byItem.computeIfAbsent(a.item(), k -> new ArrayList<>()).add(a);
			Map<Item, Double> hyped = new HashMap<>();
			Set<Item> relevant = new HashSet<>(graph.outputs());
			for (ItemStack rem : graph.remainders().values()) relevant.add(rem.getItem());
			for (Map.Entry<Item, List<PriceModifiers.Active>> e : byItem.entrySet()) {
				Item item = e.getKey();
				if (!relevant.contains(item) || PriceGuard.pivotPrice(item) >= 0 || BasePrices.sellPrice(item).isEmpty()) continue;
				double p = potential(e.getValue());
				if (p > mod) hyped.put(item, p);
			}
			if (hyped.isEmpty()) {
				liveFloors = Map.of();
				return;
			}
			ToLongFunction<Item> value = item -> worstSellValue(item, sell, Math.max(mod, hyped.getOrDefault(item, 1.0)));
			Map<Item, Long> lowest = lowestGuardPrices();
			Map<Item, Long> start = new HashMap<>(lowest);
			staticFloors.forEach((k, v) -> start.merge(k, v, Math::max));
			Map<Item, Long> prices = raise(graph.edges(), start, value, remainderValues(graph.remainders(), value));
			Map<Item, Long> live = new HashMap<>();
			prices.forEach((k, v) -> {
				Long base = lowest.get(k);
				if (base != null && v > base && v > staticFloors.getOrDefault(k, 0L)) live.put(k, v);
			});
			liveFloors = Map.copyOf(live);
			if (!live.isEmpty()) {
				List<String> h = new ArrayList<>();
				hyped.entrySet().stream().sorted(Comparator.comparing(e -> Items.id(e.getKey())))
					.forEach(e -> h.add(Items.id(e.getKey()) + " ×" + String.format(java.util.Locale.ROOT, "%.2f", e.getValue())));
				SmpEconomy.LOG.info("[SMPEco] Shop: recipe guard during price modifiers ({}): {} floors temporarily raised: {}",
					String.join(", ", h), live.size(), describe(live, lowest));
			}
		} catch (RuntimeException e) {
			SmpEconomy.LOG.error("[SMPEco] Shop: live recipe guard computation failed, old floors stay active", e);
		}
	}

	private static String describe(Map<Item, Long> floors, Map<Item, Long> lowest) {
		List<String> parts = new ArrayList<>();
		floors.entrySet().stream().sorted(Comparator.comparing(e -> Items.id(e.getKey())))
			.forEach(e -> parts.add(Items.id(e.getKey()) + " " + Money.format(e.getValue()) + " (Pivot+1 " + Money.format(lowest.getOrDefault(e.getKey(), 0L)) + ")"));
		return String.join(", ", parts);
	}

	/** Ingredient candidates are limited to items that can be bought or made. */
	static Graph collect(MinecraftServer server) {
		RegistryAccess access = server.registryAccess();
		Set<Item> shop = BasePrices.shopBuyPrices().keySet();
		List<Edge> raw = new ArrayList<>();
		for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
			try {
				Edge e = toEdge(holder, access);
				if (e != null) raw.add(e);
			} catch (RuntimeException ex) {
				SmpEconomy.LOG.debug("[SMPEco] Shop: recipe {} skipped: {}", holder.id(), ex.toString());
			}
		}
		Set<Item> reachable = new HashSet<>(shop);
		boolean grew = true;
		while (grew) {
			grew = false;
			for (Edge e : raw) {
				if (reachable.contains(e.output())) continue;
				if (allSlotsReachable(e, reachable)) {
					reachable.add(e.output());
					grew = true;
				}
			}
		}
		List<Edge> edges = new ArrayList<>();
		Map<Item, ItemStack> remainders = new HashMap<>();
		Set<Item> outputs = new HashSet<>();
		for (Edge e : raw) {
			if (!allSlotsReachable(e, reachable)) continue;
			List<List<Item>> slots = new ArrayList<>();
			for (List<Item> slot : e.slots()) {
				List<Item> keep = new ArrayList<>();
				for (Item item : slot) {
					if (!reachable.contains(item)) continue;
					keep.add(item);
					if (e.crafting()) {
						ItemStack rem = Mc.craftingRemainder(item);
						if (rem != null && !rem.isEmpty()) remainders.put(item, rem.copy());
					}
				}
				slots.add(List.copyOf(keep));
			}
			edges.add(new Edge(e.id(), e.output(), e.count(), List.copyOf(slots), e.crafting()));
			outputs.add(e.output());
		}
		edges.sort(Comparator.comparing(Edge::id));
		return new Graph(List.copyOf(edges), Map.copyOf(remainders), Set.copyOf(outputs));
	}

	private static boolean allSlotsReachable(Edge e, Set<Item> reachable) {
		for (List<Item> slot : e.slots()) {
			boolean any = false;
			for (Item item : slot) {
				if (reachable.contains(item)) {
					any = true;
					break;
				}
			}
			if (!any) return false;
		}
		return true;
	}

	@SuppressWarnings("deprecation") // Ingredient#items gives candidates, including Fabric custom ingredient matches; test() checks afterwards
	private static Edge toEdge(RecipeHolder<?> holder, RegistryAccess access) {
		Recipe<?> recipe = holder.value();
		ItemStack out;
		if (recipe instanceof ShapedRecipe r) out = Mc.assemble(r, CraftingInput.EMPTY, access);
		else if (recipe instanceof ShapelessRecipe r) out = Mc.assemble(r, CraftingInput.EMPTY, access);
		else if (recipe instanceof SingleItemRecipe r) out = Mc.assemble(r, new SingleRecipeInput(ItemStack.EMPTY), access);
		else return null;
		if (out.isEmpty()) return null;
		List<Ingredient> ingredients = recipe.placementInfo().ingredients();
		if (ingredients.isEmpty()) return null;
		List<List<Item>> slots = new ArrayList<>();
		for (Ingredient ing : ingredients) {
			Set<Item> matching = new LinkedHashSet<>();
			ing.items().map(Holder::value).forEach(item -> {
				if (item != net.minecraft.world.item.Items.AIR && ing.test(new ItemStack(item))) matching.add(item);
			});
			if (matching.isEmpty()) return null;
			List<Item> sorted = new ArrayList<>(matching);
			sorted.sort(Comparator.comparing(Items::id));
			slots.add(List.copyOf(sorted));
		}
		return new Edge(holder.id().identifier().toString(), out.getItem(), out.getCount(), List.copyOf(slots), !(recipe instanceof SingleItemRecipe));
	}

	/** Per ingredient: container sell value x count; only entries > 0. */
	static Map<Item, Long> remainderValues(Map<Item, ItemStack> remainders, ToLongFunction<Item> sellValue) {
		Map<Item, Long> m = new HashMap<>();
		remainders.forEach((item, rem) -> {
			long v = mulSat(sellValue.applyAsLong(rem.getItem()), rem.getCount());
			if (v > 0) m.put(item, v);
		});
		return m;
	}

	/**
	 * Shop items start at {@code prices}; recipes lower their output cost while it gets cheaper. Lossless cycles
	 * such as ingot and block change nothing.
	 */
	static Map<Item, Cost> derivedCosts(List<Edge> edges, Map<Item, Long> prices, Map<Item, Long> remainderValue) {
		Map<Item, Cost> costs = new HashMap<>();
		prices.forEach((item, p) -> costs.put(item, new Cost(p, 0, Map.of(item, 1.0))));
		for (int pass = 0; pass < MAX_COST_PASSES; pass++) {
			boolean changed = false;
			for (Edge e : edges) {
				Cost total = run(e, costs, remainderValue);
				if (total == null) continue;
				Cost unit = total.per(e.count());
				Cost old = costs.get(e.output());
				if (old == null || unit.net() < old.net() - EPS) {
					costs.put(e.output(), unit);
					changed = true;
				}
			}
			if (!changed) break;
		}
		return costs;
	}

	/**
	 * One recipe run at {@code costs}, using the player's cheapest ingredient per slot. Null if an ingredient
	 * cannot be obtained.
	 */
	static Cost run(Edge e, Map<Item, Cost> costs, Map<Item, Long> remainderValue) {
		double gross = 0;
		double refund = 0;
		Map<Item, Double> roots = new HashMap<>();
		for (List<Item> slot : e.slots()) {
			Cost best = null;
			long bestRem = 0;
			double bestNet = 0;
			for (Item item : slot) {
				Cost c = costs.get(item);
				if (c == null) continue;
				long rem = e.crafting() ? remainderValue.getOrDefault(item, 0L) : 0;
				double net = c.net() - rem;
				if (best == null || net < bestNet) {
					best = c;
					bestRem = rem;
					bestNet = net;
				}
			}
			if (best == null) return null;
			gross += best.gross();
			refund += best.refund() + bestRem;
			best.roots().forEach((k, q) -> roots.merge(k, q, Double::sum));
		}
		return new Cost(gross, refund, roots);
	}

	/** Recipes that do not lose at {@code prices}: revenue + returns >= cost. */
	static List<Violation> violations(List<Edge> edges, Map<Item, Long> prices, ToLongFunction<Item> sellValue, Map<Item, Long> remainderValue) {
		Map<Item, Cost> costs = derivedCosts(edges, prices, remainderValue);
		List<Violation> list = new ArrayList<>();
		for (Edge e : edges) {
			Cost c = run(e, costs, remainderValue);
			if (c == null || c.gross() <= 0) continue;
			double value = mulSat(Math.max(0, sellValue.applyAsLong(e.output())), e.count()) + c.refund();
			if (value > 0 && value + EPS >= c.gross()) list.add(new Violation(e.id(), value, c.gross()));
		}
		return list;
	}

	/**
	 * Fixed point: while a recipe does not lose, the shop ingredients on its cheapest path scale by (value + 1) /
	 * cost, rounded up, at least +1 cent. Each round applies the strongest raise per item, and prices only rise.
	 */
	static Map<Item, Long> raise(List<Edge> edges, Map<Item, Long> start, ToLongFunction<Item> sellValue, Map<Item, Long> remainderValue) {
		Map<Item, Long> prices = new HashMap<>(start);
		for (int round = 0; round < MAX_ROUNDS; round++) {
			Map<Item, Cost> costs = derivedCosts(edges, prices, remainderValue);
			Map<Item, Long> next = new HashMap<>();
			for (Edge e : edges) {
				Cost c = run(e, costs, remainderValue);
				if (c == null || c.gross() <= 0) continue;
				double value = mulSat(Math.max(0, sellValue.applyAsLong(e.output())), e.count()) + c.refund();
				if (value <= 0 || value + EPS < c.gross()) continue;
				double factor = (value + 1) / c.gross();
				for (Item root : c.roots().keySet()) {
					Long cur = prices.get(root);
					if (cur == null) continue;
					next.merge(root, raiseBy(cur, factor), Math::max);
				}
			}
			boolean changed = false;
			for (Map.Entry<Item, Long> n : next.entrySet()) {
				if (n.getValue() > prices.get(n.getKey())) {
					prices.put(n.getKey(), n.getValue());
					changed = true;
				}
			}
			if (!changed) return prices;
		}
		SmpEconomy.LOG.warn("[SMPEco] Shop: recipe guard not stable after {} rounds, check the selftest", MAX_ROUNDS);
		return prices;
	}

	static Map<Item, Long> computeFloors(List<Edge> edges, Map<Item, Long> lowest, ToLongFunction<Item> sellValue, Map<Item, Long> remainderValue) {
		Map<Item, Long> prices = raise(edges, lowest, sellValue, remainderValue);
		Map<Item, Long> result = new HashMap<>();
		prices.forEach((item, p) -> {
			Long base = lowest.get(item);
			if (base != null && p > base) result.put(item, p);
		});
		return result;
	}

	/**
	 * Floating point residue below 1e-9 cents is ignored, since the next round checks again. Saturates at {@link
	 * Money#MAX}.
	 */
	static long raiseBy(long price, double factor) {
		double d = Math.ceil(price * factor - 1e-9);
		long r = !(d < Money.MAX) ? Money.MAX : (long) d;
		return Math.min(Money.MAX, Math.max(price + 1, r));
	}

	private static long mulSat(long a, long b) {
		try {
			return Math.min(Money.MAX, Math.multiplyExact(a, b));
		} catch (ArithmeticException e) {
			return Money.MAX;
		}
	}
}
