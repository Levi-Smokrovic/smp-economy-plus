package dev.smpeconomy.features.market;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
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
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import dev.smpeconomy.core.compat.Mc;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.compat.VanillaTrades;
import dev.smpeconomy.core.BasePrices;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.PriceGuard;
import dev.smpeconomy.core.PriceModifiers;

/**
 * Market values, the single source for market, orders, news and PriceGuard callers (registered as {@code Services.MarketValues}).
 *
 * <p>Per item (pristine, per unit, cents): base × drift × {@link PriceModifiers#get}. The base is
 * <ul>
 *   <li>sell price (prices.json) × {@code marketFactor},</li>
 *   <li>otherwise a fixed value from {@code valueOverrides}, at most the recipe estimate if there is a recipe,</li>
 *   <li>otherwise a recipe estimate: sum of ingredient sell values × {@code recipeMarkup} per result item. The cheapest recipe is
 *     found once at startup, but computed dynamically from current ingredient market values so that drift and crashes on
 *     ingredients move the result too (no "buy cheap ingredient from NPC, sell expensive result to NPC").</li>
 * </ul>
 * No base value means empty: NPCs do not trade the item. Crafting cap: if an item can be crafted from shop items, its market
 * value stays low enough that every NPC payment (≤ market value × 1.25) is below the cheapest cost of crafting it from shop
 * purchases (≥ pivot + 1), also during flash sales and crashes.
 *
 * <p>For stacks ({@link #unitValue}): item value + enchantment bonus (table, own drift per enchantment) × durability factor.
 * Other components (contents, potions, banners) mean no value. NPC buyers use {@link #buyerUnitValue}, without enchantment
 * bonuses and fixed values ({@link #fixedValue}), because vanilla supplies both without limit.
 *
 * <p>Floor of NPC offers ({@link #saleFloor}): above every payment the system would make for the item, now and after downward
 * factors that will foreseeably end are back at 1.0 ({@link Mode#HIGH}), and also after converting by recipe or villager trade at
 * base prices ({@link #conversionFloor}).
 */
public final class ValueEngine {
	public enum Source {
		PRICE, OVERRIDE, RECIPE, NONE
	}

	/**
	 * Valuation mode. Drift and price modifiers foreseeably return to 1.0 (half-life, news end with a visible timer), so price
	 * limits over time must use the bound, not the current value.
	 */
	public enum Mode {
		CURRENT,
		/** Upper bound: factors below 1 (crash, downward drift) count as 1, also for the ingredients of a recipe estimate. */
		HIGH,
		/** Lower bound: factors above 1 (hype, upward drift) count as 1, also for ingredients. */
		LOW;

		double factor(double f) {
			return switch (this) {
				case CURRENT -> f;
				case HIGH -> Math.max(1.0, f);
				case LOW -> Math.min(1.0, f);
			};
		}

		/** For recipe remainders (a deduction): the opposite bound, which keeps HIGH ≥ CURRENT ≥ LOW. */
		Mode opposite() {
			return switch (this) {
				case CURRENT -> CURRENT;
				case HIGH -> LOW;
				case LOW -> HIGH;
			};
		}
	}

	public record RawRecipe(Item out, int count, List<List<Item>> ingredients) {}

	/** Chosen recipe of an estimate; ingredients only with items valued earlier, so no cycles. */
	public record Estimate(Item out, int count, List<List<Item>> options) {}

	/**
	 * A recipe consuming an item: {@code k} of it plus {@code others} (one list of allowed items per slot) give {@code count} ×
	 * {@code out}. Empty {@code others} means a pure conversion (block to ingot, smelting, stonecutter, villager "1 emerald to 6
	 * bread").
	 */
	public record Use(Item out, int count, int k, List<List<Item>> others) {
		public boolean pure() {
			return others.isEmpty();
		}
	}

	/**
	 * Static model, rebuilt at startup and on recipe, price or config reload. Values are neutral (drift 1, modifier 1). {@code
	 * fixed} holds items valued from {@code valueOverrides}: the override item itself and recipe estimates with an ingredient
	 * that only exists as such an item (beacon from nether star). {@code uses} contains recipes and villager trades ({@code
	 * tradeCount}); market values and crafting caps only come from recipes.
	 */
	public record Model(Map<Item, Double> neutral, Map<Item, Source> sources, Map<Item, Estimate> estimates, Map<Item, Long> craftCaps,
			Map<Item, Double> craftCosts, Map<Item, List<Use>> uses, int recipeCount, Set<Item> fixed, int tradeCount) {
		static final Model EMPTY = new Model(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), 0, Set.of(), 0);

		public Source source(Item item) {
			return sources.getOrDefault(item, Source.NONE);
		}

		public List<Use> uses(Item item) {
			return uses.getOrDefault(item, List.of());
		}
	}

	public record Info(Item item, Source source, double neutral, double drift, double modifier, long cap, long value) {}

	/** Components a valuation allows; anything else means no value. The name is ignored. */
	private static final Set<DataComponentType<?>> ALLOWED = Set.of(DataComponents.ENCHANTMENTS, DataComponents.STORED_ENCHANTMENTS,
		DataComponents.DAMAGE, DataComponents.REPAIR_COST, DataComponents.CUSTOM_NAME);

	private static Model model = Model.EMPTY;
	private static List<RawRecipe> lastRecipes = List.of();
	private static List<RawRecipe> lastTrades = List.of();
	private static MarketConfig modelConfig;
	private static int modelVersion;
	private static int modifierVersion;
	private static boolean modifierListener;

	// Per tick and mode; values only change through drift, modifiers, config and model
	private static final EnumMap<Mode, Map<Item, Long>> CACHES = new EnumMap<>(Mode.class);

	static {
		for (Mode mode : Mode.values()) CACHES.put(mode, new IdentityHashMap<>());
	}
	private static long cacheTick = Long.MIN_VALUE;
	private static int cacheDrift = -1;
	private static int cacheModel = -1;
	private static int cacheMods = -1;
	private static MarketConfig cacheConfig;

	private ValueEngine() {}

	public static Model model() {
		return model;
	}

	/**
	 * Rereads recipes and villager trades (server start, datapack reload) and builds the model. On recipe errors the model uses
	 * only prices and overrides; on trade errors conversions use only recipes (logged).
	 */
	public static void rebuild(MinecraftServer server) {
		List<RawRecipe> recipes = List.of();
		List<RawRecipe> trades = List.of();
		if (server != null) {
			try {
				recipes = List.copyOf(extractRecipes(server));
			} catch (RuntimeException e) {
				SmpEconomy.LOG.error("[SMPEco] market: could not read recipes, using prices/overrides only", e);
			}
			try {
				trades = List.copyOf(extractTrades(server.overworld()));
			} catch (RuntimeException e) {
				SmpEconomy.LOG.error("[SMPEco] market: villager trades unreadable, floors ignore trades (market never sells "
					+ "emeralds anyway)", e);
			}
		}
		lastRecipes = recipes;
		lastTrades = trades;
		recompute();
		if (server != null) {
			Model m = model;
			long estimated = m.sources.values().stream().filter(s -> s == Source.RECIPE).count();
			SmpEconomy.LOG.info("[SMPEco] market: {} market values ({} estimated from {} recipes, {} with crafting cap, {} villager trades)",
				m.neutral.size(), estimated, m.recipeCount, m.craftCaps.size(), m.tradeCount);
		}
	}

	public static void recompute() {
		if (!modifierListener) {
			modifierListener = true;
			PriceModifiers.addListener(() -> modifierVersion++);
		}
		MarketConfig c = MarketConfig.get();
		Map<Item, Long> overrides = new LinkedHashMap<>();
		c.valueOverrides.forEach((id, v) -> Items.byId(id).ifPresent(i -> overrides.put(i, v)));
		Map<Item, Double> leaves = new HashMap<>();
		BasePrices.shopBuyPrices().forEach((item, buy) -> {
			long pivot = PriceGuard.pivot(buy);
			if (pivot >= 0) leaves.put(item, (double) pivot + 1);
		});
		model = compute(lastRecipes, lastTrades, BasePrices.sellPrices(), overrides, leaves, c.marketFactor, c.recipeMarkup);
		modelConfig = c;
		modelVersion++;
		clearCaches();
	}

	private static void clearCaches() {
		for (Map<Item, Long> cache : CACHES.values()) cache.clear();
	}

	static List<RawRecipe> extractRecipes(MinecraftServer server) {
		HolderLookup.Provider registries = server.registryAccess();
		List<RawRecipe> list = new ArrayList<>();
		Collection<RecipeHolder<?>> all = server.getRecipeManager().getRecipes();
		for (RecipeHolder<?> holder : all) {
			Recipe<?> recipe = holder.value();
			try {
				if (recipe.isSpecial()) continue;
				List<Ingredient> ingredients = recipe.placementInfo().ingredients();
				if (ingredients.isEmpty()) continue;
				ItemStack out;
				if (recipe instanceof ShapedRecipe r) out = Mc.assemble(r, CraftingInput.EMPTY, registries);
				else if (recipe instanceof ShapelessRecipe r) out = Mc.assemble(r, CraftingInput.EMPTY, registries);
				else if (recipe instanceof SingleItemRecipe r) out = Mc.assemble(r, new SingleRecipeInput(ItemStack.EMPTY), registries);
				else if (recipe instanceof SmithingTransformRecipe r) {
					Optional<Holder<Item>> base = r.baseIngredient().items().findFirst();
					if (base.isEmpty()) continue;
					out = Mc.assemble(r, new SmithingRecipeInput(ItemStack.EMPTY, new ItemStack(base.get()), ItemStack.EMPTY), registries);
				} else continue;
				if (out == null || out.isEmpty() || !out.getComponentsPatch().isEmpty()) continue;
				List<List<Item>> options = new ArrayList<>(ingredients.size());
				for (Ingredient ing : ingredients) {
					List<Item> items = ing.items().map(Holder::value).distinct().toList();
					if (items.isEmpty()) {
						options = null;
						break;
					}
					options.add(items);
				}
				if (options != null) list.add(new RawRecipe(out.getItem(), out.getCount(), List.copyOf(options)));
			} catch (RuntimeException e) {
				SmpEconomy.LOG.debug("[SMPEco] market: skipped recipe {}: {}", holder.id(), e.toString());
			}
		}
		return list;
	}

	/**
	 * A master fisherman buys a boat for 1 emerald depending on village type ({@code EmeraldsForVillagerTypeItem}). Not readable
	 * without a villager entity, hence hardcoded.
	 */
	static final List<Item> BOAT_TRADES = List.of(net.minecraft.world.item.Items.OAK_BOAT, net.minecraft.world.item.Items.SPRUCE_BOAT,
		net.minecraft.world.item.Items.JUNGLE_BOAT, net.minecraft.world.item.Items.ACACIA_BOAT, net.minecraft.world.item.Items.DARK_OAK_BOAT);

	/**
	 * Reads vanilla trades through {@link VanillaTrades} ({@link VanillaTrades.Kind#ENTITY_FREE}) as recipes "cost A (+ cost B)
	 * to result" at base prices. Only trades whose offer needs neither entity nor world, without side effects (treasure maps
	 * create map data), and only with unmodified costs and result; enchanted, dyed, potion and map results have no market value
	 * anyway. Demand surcharges and discounts (cured villagers, Hero of the Village) are not modeled, which is why market never
	 * sells emeralds ({@link MarketConfig#NEVER_SOLD}). {@link #conversionFloor} credits a recipe remainder (bucket) like for a
	 * recipe even though the trade takes it, so the floor errs high, the safe direction.
	 */
	static List<RawRecipe> extractTrades(ServerLevel level) {
		Set<RawRecipe> found = new LinkedHashSet<>();
		// Experimental trades only with "trade_rebalance" (like Villager#updateTrades); otherwise they do not exist in this world
		VanillaTrades.Result trades = VanillaTrades.offers(level, VanillaTrades.Kind.ENTITY_FREE, VanillaTrades.Experimental.IF_ENABLED, "market");
		for (VanillaTrades.Offer offer : trades.offers()) addTrade(found, offer);
		for (Item boat : BOAT_TRADES) found.add(new RawRecipe(net.minecraft.world.item.Items.EMERALD, 1, List.of(List.of(boat))));
		return new ArrayList<>(found);
	}

	private static void addTrade(Set<RawRecipe> found, VanillaTrades.Offer offer) {
		// With an enchantment provider the offer reads the entity; skipped (the result would be enchanted anyway)
		ItemStack a = offer.costA();
		ItemStack b = offer.costB();
		ItemStack result = offer.result();
		if (a.isEmpty() || result.isEmpty() || !a.getComponentsPatch().isEmpty() || !b.getComponentsPatch().isEmpty()
			|| !result.getComponentsPatch().isEmpty()) return;
		List<List<Item>> ingredients = new ArrayList<>(a.getCount() + b.getCount());
		for (int i = 0; i < a.getCount(); i++) ingredients.add(List.of(a.getItem()));
		for (int i = 0; i < b.getCount(); i++) ingredients.add(List.of(b.getItem()));
		found.add(new RawRecipe(result.getItem(), result.getCount(), List.copyOf(ingredients)));
	}

	public static Model compute(List<RawRecipe> recipes, Map<Item, Long> sell, Map<Item, Long> overrides, Map<Item, Double> leaves,
			double factor, double markup) {
		return compute(recipes, List.of(), sell, overrides, leaves, factor, markup);
	}

	/**
	 * Pure model computation.
	 *
	 * @param trades    villager trades as recipes ({@link #extractTrades}), only for the conversion index ({@link Model#uses}),
	 *                  not for market values or crafting caps
	 * @param sell      base sell prices in cents
	 * @param overrides fixed market values for items without a sell price
	 * @param leaves    cheapest system buy prices (shop: pivot + 1)
	 */
	public static Model compute(List<RawRecipe> recipes, List<RawRecipe> trades, Map<Item, Long> sell, Map<Item, Long> overrides,
			Map<Item, Double> leaves, double factor, double markup) {
		Map<Item, Double> neutral = new HashMap<>();
		Map<Item, Source> sources = new HashMap<>();
		Map<Item, Integer> level = new HashMap<>();
		Map<Item, Estimate> estimates = new HashMap<>();
		sell.forEach((item, cents) -> {
			if (cents != null && cents > 0) {
				neutral.put(item, cents * factor);
				sources.put(item, Source.PRICE);
				level.put(item, 0);
			}
		});
		overrides.forEach((item, cents) -> {
			if (cents != null && cents > 0 && !sources.containsKey(item)) {
				neutral.put(item, (double) cents);
				sources.put(item, Source.OVERRIDE);
				level.put(item, 0);
			}
		});
		Map<Item, List<RawRecipe>> byOut = new HashMap<>();
		for (RawRecipe r : recipes) {
			if (r.count() <= 0 || sources.get(r.out()) == Source.PRICE) continue;
			byOut.computeIfAbsent(r.out(), k -> new ArrayList<>()).add(r);
		}
		double ratio = markup / factor;
		Set<Item> estimatedOverrides = new HashSet<>();
		// In layers: an item gets its value once from ingredients of earlier layers, so no cycles (A <-> B)
		for (int round = 1; round <= 64; round++) {
			Map<Item, Double> newValues = new HashMap<>();
			Map<Item, Estimate> newEstimates = new HashMap<>();
			for (Map.Entry<Item, List<RawRecipe>> e : byOut.entrySet()) {
				Item out = e.getKey();
				Source s = sources.get(out);
				if (s == Source.RECIPE || (s == Source.OVERRIDE && estimatedOverrides.contains(out))) continue;
				double best = Double.POSITIVE_INFINITY;
				Estimate bestEstimate = null;
				for (RawRecipe r : e.getValue()) {
					double cost = 0;
					List<List<Item>> known = new ArrayList<>(r.ingredients().size());
					for (List<Item> ing : r.ingredients()) {
						double min = Double.POSITIVE_INFINITY;
						List<Item> ok = new ArrayList<>();
						for (Item i : ing) {
							Integer l = level.get(i);
							if (l == null || l >= round || i == out) continue;
							ok.add(i);
							min = Math.min(min, Math.max(0, neutral.get(i) - remainderNeutral(i, neutral)));
						}
						if (ok.isEmpty()) {
							cost = Double.NaN;
							break;
						}
						cost += min;
						known.add(List.copyOf(ok));
					}
					if (Double.isNaN(cost)) continue;
					double est = ratio * cost / r.count();
					if (est > 0 && est < best) {
						best = est;
						bestEstimate = new Estimate(out, r.count(), List.copyOf(known));
					}
				}
				if (bestEstimate != null) {
					newValues.put(out, best);
					newEstimates.put(out, bestEstimate);
				}
			}
			if (newValues.isEmpty()) break;
			final int r = round;
			newValues.forEach((out, v) -> {
				if (sources.get(out) == Source.OVERRIDE) {
					// Override with recipe: at most the static estimate, stays a leaf for the dynamic computation
					neutral.put(out, Math.min(neutral.get(out), v));
					estimatedOverrides.add(out);
				} else {
					neutral.put(out, v);
					sources.put(out, Source.RECIPE);
					level.put(out, r);
					estimates.put(out, newEstimates.get(out));
				}
			});
		}
		// Crafting cost from system purchases (Bellman-Ford, improvements only; profitless cycles converge)
		Map<Item, Double> system = new HashMap<>(leaves);
		Map<Item, Double> craft = new HashMap<>();
		for (int pass = 0; pass < 48; pass++) {
			boolean changed = false;
			for (RawRecipe r : recipes) {
				if (r.count() <= 0) continue;
				double cost = 0;
				for (List<Item> ing : r.ingredients()) {
					double min = Double.POSITIVE_INFINITY;
					for (Item i : ing) {
						Double c = system.get(i);
						if (c != null) min = Math.min(min, Math.max(0, c - remainderCredit(i, system, neutral)));
					}
					if (min == Double.POSITIVE_INFINITY) {
						cost = Double.NaN;
						break;
					}
					cost += min;
				}
				if (Double.isNaN(cost)) continue;
				double unit = cost / r.count();
				Double old = craft.get(r.out());
				if (old == null || unit < old * (1 - 1e-9) - 1e-9) {
					craft.put(r.out(), unit);
					changed = true;
				}
				Double sys = system.get(r.out());
				if (sys == null || unit < sys * (1 - 1e-9) - 1e-9) {
					system.put(r.out(), unit);
					changed = true;
				}
			}
			if (!changed) break;
		}
		// Layers ascending: the ingredients of an estimate come from earlier layers
		Set<Item> fixed = new HashSet<>();
		sources.forEach((item, src) -> {
			if (src == Source.OVERRIDE) fixed.add(item);
		});
		List<Item> estimated = new ArrayList<>(estimates.keySet());
		estimated.sort(java.util.Comparator.comparingInt(i -> level.getOrDefault(i, Integer.MAX_VALUE)));
		for (Item out : estimated) {
			for (List<Item> slot : estimates.get(out).options()) {
				// If a slot also allows an ingredient without a fixed value, that one caps the estimate
				if (!slot.isEmpty() && fixed.containsAll(slot)) {
					fixed.add(out);
					break;
				}
			}
		}
		Map<Item, Long> caps = new HashMap<>();
		craft.forEach((item, cost) -> {
			// NPC payments ≤ floor(market value × 1.25) < crafting cost
			long cap = (long) Math.ceil(cost / PriceGuard.NPC_BUY_MARKET_FACTOR_MAX - 1e-9) - 1;
			caps.put(item, Math.max(0, cap));
		});
		List<RawRecipe> conversions = new ArrayList<>(recipes);
		conversions.addAll(trades);
		return new Model(Map.copyOf(neutral), Map.copyOf(sources), Map.copyOf(estimates), Map.copyOf(caps), Map.copyOf(craft),
			buildUses(conversions), recipes.size(), Set.copyOf(fixed), trades.size());
	}

	/**
	 * Index of recipes consuming item X. {@code k} is the number of slots allowing only X (at least 1). A slot allowing X among
	 * others counts as another ingredient, since the player can use the cheapest there.
	 */
	static Map<Item, List<Use>> buildUses(List<RawRecipe> recipes) {
		Map<Item, List<Use>> uses = new HashMap<>();
		for (RawRecipe r : recipes) {
			if (r.count() <= 0 || r.ingredients().isEmpty()) continue;
			Set<Item> inputs = new LinkedHashSet<>();
			for (List<Item> ing : r.ingredients()) inputs.addAll(ing);
			for (Item p : inputs) {
				if (p == null || p == net.minecraft.world.item.Items.AIR) continue;
				int exclusive = 0;
				for (List<Item> ing : r.ingredients()) {
					if (ing.size() == 1 && ing.getFirst() == p) exclusive++;
				}
				List<List<Item>> others = new ArrayList<>();
				boolean takeFlexible = exclusive == 0;
				for (List<Item> ing : r.ingredients()) {
					if (exclusive > 0 && ing.size() == 1 && ing.getFirst() == p) continue;
					if (takeFlexible && ing.contains(p)) {
						takeFlexible = false; // This slot gets the item itself
						continue;
					}
					others.add(ing);
				}
				uses.computeIfAbsent(p, x -> new ArrayList<>()).add(new Use(r.out(), r.count(), Math.max(1, exclusive), List.copyOf(others)));
			}
		}
		Map<Item, List<Use>> out = new HashMap<>(uses.size());
		uses.forEach((item, list) -> out.put(item, List.copyOf(list)));
		return Map.copyOf(out);
	}

	private static double remainderNeutral(Item item, Map<Item, Double> neutral) {
		ItemStack rem = Mc.craftingRemainder(item);
		if (rem.isEmpty()) return 0;
		Double v = neutral.get(rem.getItem());
		return v == null ? 0 : v * rem.getCount();
	}

	/** Credit for a recipe remainder (e.g. bucket): the larger of what the system pays for it at most and what it costs. */
	private static double remainderCredit(Item item, Map<Item, Double> system, Map<Item, Double> neutral) {
		ItemStack rem = Mc.craftingRemainder(item);
		if (rem.isEmpty()) return 0;
		double credit = 0;
		Double sys = system.get(rem.getItem());
		if (sys != null) credit = Math.max(credit, sys);
		Double n = neutral.get(rem.getItem());
		if (n != null) credit = Math.max(credit, n * PriceGuard.NPC_BUY_MARKET_FACTOR_MAX);
		return credit * rem.getCount();
	}

	public static OptionalLong value(Item item) {
		return value(item, System.currentTimeMillis(), Mode.CURRENT);
	}

	/**
	 * {@link Mode#HIGH}: highest value foreseeably reached again (crash and downward drift neutralized, also on ingredients).
	 * {@link Mode#LOW}: hype and upward drift neutralized. The crafting cap always applies.
	 */
	public static OptionalLong value(Item item, Mode mode) {
		return value(item, System.currentTimeMillis(), mode);
	}

	private static OptionalLong value(Item item, long now, Mode mode) {
		if (item == null || item == net.minecraft.world.item.Items.AIR) return OptionalLong.empty();
		validateCache();
		long v = valueAtDepth(item, now, 0, mode);
		return v > 0 ? OptionalLong.of(v) : OptionalLong.empty();
	}

	public static Info info(Item item) {
		long now = System.currentTimeMillis();
		Model m = model;
		return new Info(item, m.source(item), m.neutral.getOrDefault(item, 0.0), Drift.current(item, now), PriceModifiers.get(item),
			m.craftCaps.getOrDefault(item, -1L), value(item, now, Mode.CURRENT).orElse(0));
	}

	public static void invalidate() {
		clearCaches();
	}

	private static void validateCache() {
		if (modelConfig != null && MarketConfig.get() != modelConfig) recompute(); // /smpeco reload replaced market.json
		MinecraftServer server = SmpEconomy.server();
		long tick = server == null ? Long.MIN_VALUE + 1 : server.getTickCount();
		MarketConfig c = MarketConfig.get();
		if (tick != cacheTick || Drift.version() != cacheDrift || modelVersion != cacheModel || modifierVersion != cacheMods || c != cacheConfig) {
			clearCaches();
			cacheTick = tick;
			cacheDrift = Drift.version();
			cacheModel = modelVersion;
			cacheMods = modifierVersion;
			cacheConfig = c;
		}
	}

	private static long compute(Item item, long now, int depth, Mode mode) {
		Model m = model;
		MarketConfig c = MarketConfig.get();
		double base;
		switch (m.source(item)) {
			case PRICE -> {
				OptionalLong sell = BasePrices.sellPrice(item);
				base = sell.isPresent() ? sell.getAsLong() * c.marketFactor : 0;
			}
			case OVERRIDE -> base = m.neutral.getOrDefault(item, 0.0);
			case RECIPE -> base = depth > 64 ? 0 : recipeBase(m.estimates.get(item), c, now, depth, mode);
			default -> {
				// Model not built yet (before server start): directly from prices.json / overrides
				OptionalLong sell = BasePrices.sellPrice(item);
				if (sell.isPresent()) base = sell.getAsLong() * c.marketFactor;
				else if (m == Model.EMPTY) base = c.valueOverrides.getOrDefault(Items.id(item), 0L);
				else base = 0;
			}
		}
		if (!(base > 0)) return 0;
		double v = base * mode.factor(Drift.current(item, now)) * mode.factor(PriceModifiers.get(item));
		long cents = v >= Money.MAX ? Money.MAX : (long) Math.floor(v + 1e-9);
		Long cap = m.craftCaps.get(item);
		if (cap != null) cents = Math.min(cents, cap);
		return Math.max(0, cents);
	}

	/**
	 * Dynamic recipe estimate: recipeMarkup × Σ (market value of the cheapest ingredient / marketFactor - remainder) / count.
	 * Ingredients in the same mode (a gold crash lowers the golden apple only in {@link Mode#CURRENT}), remainders with the
	 * opposite bound.
	 */
	private static double recipeBase(Estimate e, MarketConfig c, long now, int depth, Mode mode) {
		if (e == null) return 0;
		double cost = 0;
		for (List<Item> options : e.options()) {
			double min = Double.POSITIVE_INFINITY;
			for (Item i : options) {
				long v = valueAtDepth(i, now, depth + 1, mode);
				if (v <= 0) continue;
				ItemStack rem = Mc.craftingRemainder(i);
				double credit = rem.isEmpty() ? 0 : valueAtDepth(rem.getItem(), now, depth + 1, mode.opposite()) * (double) rem.getCount();
				min = Math.min(min, Math.max(0, v - credit));
			}
			if (min == Double.POSITIVE_INFINITY) return 0;
			cost += min;
		}
		return c.recipeMarkup / c.marketFactor * cost / Math.max(1, e.count());
	}

	private static long valueAtDepth(Item item, long now, int depth, Mode mode) {
		Map<Item, Long> cache = CACHES.get(mode);
		Long cached = cache.get(item);
		if (cached == null) {
			cached = compute(item, now, depth, mode);
			cache.put(item, cached);
		}
		return cached;
	}

	/**
	 * (item value + enchantment bonus × drift × modifier) × durability. Empty with foreign components (contents, potion, banner)
	 * or no value.
	 */
	public static OptionalLong unitValue(ItemStack stack) {
		return stackValue(stack, Mode.CURRENT, false);
	}

	/**
	 * Value for system sales (floor of NPC offers): like {@link #unitValue}, but in {@link Mode#HIGH}. Drift and price modifiers
	 * below 1.0 on the item, its enchantments and estimate ingredients are not deducted. Otherwise one could buy cheaply from the
	 * NPC seller during a crash or after pushing drift down, and sell higher to NPC buyers/orders or via /sell after the return
	 * to 1.0.
	 */
	public static OptionalLong saleUnitValue(ItemStack stack) {
		return stackValue(stack, Mode.HIGH, false);
	}

	/**
	 * Value per unit NPC buyers bid against, paid from the treasury: like {@link #unitValue}, but only with values whose supply
	 * the economy model covers. Without {@code buyerPaysFixedValues} fixed-value items ({@link #fixedValue}) count nothing,
	 * without {@code buyerPaysEnchantments} no enchantment bonus counts (curses still deduct). Vanilla makes both endlessly
	 * (librarian, enchanting table, raid/creeper/wither farms), which would drain the treasury's NPC budget at table prices.
	 * Empty means NPC buyers do not buy it. Always ≤ {@link #unitValue}.
	 */
	public static OptionalLong buyerUnitValue(ItemStack stack) {
		return stackValue(stack, Mode.CURRENT, true);
	}

	/** Whether the item's market value rests on a fixed value ({@code valueOverrides}), also through a recipe ingredient. */
	public static boolean fixedValue(Item item) {
		if (item == null) return false;
		validateCache(); // After /smpeco reload rebuild the model first
		Model m = model;
		if (m == Model.EMPTY) return BasePrices.sellPrice(item).isEmpty() && MarketConfig.get().valueOverrides.containsKey(Items.id(item));
		return m.fixed().contains(item);
	}

	private static OptionalLong stackValue(ItemStack stack, Mode mode, boolean buyer) {
		long now = System.currentTimeMillis();
		if (stack == null || stack.isEmpty()) return OptionalLong.empty();
		boolean pristine = stack.getComponentsPatch().isEmpty();
		if (!pristine) {
			for (Map.Entry<DataComponentType<?>, Boolean> e : Mc.patchChanges(stack.getComponentsPatch()).entrySet()) {
				if (!ALLOWED.contains(e.getKey()) || !e.getValue()) return OptionalLong.empty();
			}
		}
		MarketConfig c = MarketConfig.get();
		double base = value(stack.getItem(), now, mode).orElse(0);
		if (buyer && !c.buyerPaysFixedValues && fixedValue(stack.getItem())) base = 0;
		if (pristine) {
			if (!(base >= 1)) return OptionalLong.empty();
			return OptionalLong.of(base >= Money.MAX ? Money.MAX : (long) Math.floor(base + 1e-9));
		}
		boolean positive = !buyer || c.buyerPaysEnchantments;
		double bonus = enchantmentBonus(stack, c, now, mode, positive) * mode.factor(PriceModifiers.get(stack.getItem()));
		double total = base + bonus;
		if (stack.isDamageableItem() && stack.getMaxDamage() > 0) {
			double f = (double) (stack.getMaxDamage() - stack.getDamageValue()) / stack.getMaxDamage();
			total *= Math.max(c.minDurabilityFactor, Math.min(1.0, f));
		}
		if (!(total >= 1)) return OptionalLong.empty();
		return OptionalLong.of(total >= Money.MAX ? Money.MAX : (long) Math.floor(total + 1e-9));
	}

	/**
	 * Sum of enchantment bonuses, including stored ones in books, each with its own drift. {@code positive} false means
	 * deductions (curses) only, the value for NPC buyers.
	 */
	static double enchantmentBonus(ItemStack stack, MarketConfig c, long now, Mode mode, boolean positive) {
		double sum = 0;
		sum += bonusOf(stack.get(DataComponents.ENCHANTMENTS), c, now, mode, positive);
		sum += bonusOf(stack.get(DataComponents.STORED_ENCHANTMENTS), c, now, mode, positive);
		return sum;
	}

	private static double bonusOf(ItemEnchantments enchantments, MarketConfig c, long now, Mode mode, boolean positive) {
		if (enchantments == null || enchantments.isEmpty()) return 0;
		double sum = 0;
		for (var entry : enchantments.entrySet()) {
			Holder<Enchantment> holder = entry.getKey();
			String id = holder.getRegisteredName();
			long v = levelValue(c.enchantmentValues.get(id), entry.getIntValue());
			if (v > 0) {
				if (positive) sum += v * mode.factor(Drift.current(Drift.enchantKey(id), now));
			} else sum += v;
		}
		return sum;
	}

	static long levelValue(List<Long> values, int level) {
		if (values == null || values.isEmpty() || level <= 0) return 0;
		Long v = values.get(Math.min(level, values.size()) - 1);
		return v == null ? 0 : v;
	}

	static List<String> enchantmentIds(ItemStack stack) {
		List<String> ids = new ArrayList<>();
		for (DataComponentType<ItemEnchantments> type : List.of(DataComponents.ENCHANTMENTS, DataComponents.STORED_ENCHANTMENTS)) {
			ItemEnchantments e = stack.get(type);
			if (e == null) continue;
			for (Holder<Enchantment> h : e.keySet()) ids.add(h.getRegisteredName());
		}
		return ids;
	}

	/** Neutral "buyer" for prices at listing time: no personal boosts, hooks only read. */
	public static final UUID NEUTRAL = new UUID(0L, 0L);

	/**
	 * Minimum stack total of an NPC offer for this buyer (null at listing time), rounded up to 0.5 %; 0 without value. Per unit
	 * the maximum of
	 * <ul>
	 *   <li>{@code PriceGuard.minSystemSalePrice} with {@link #saleUnitValue} (crash and downward drift neutralized),</li>
	 *   <li>for unmodified stacks {@code PriceGuard.minSystemSale} with the sell price after a crash ends ({@link #payoutHigh}),
	 *     so /sell boosts do not make buying during a crash cheaper,</li>
	 *   <li>{@link #conversionFloor}: above every system payment for what can be made from it.</li>
	 * </ul>
	 */
	public static long saleFloor(UUID buyer, ItemStack stack) {
		if (stack == null || stack.isEmpty()) return 0;
		UUID who = buyer == null ? NEUTRAL : buyer;
		Item item = stack.getItem();
		OptionalLong unit = saleUnitValue(stack);
		long per = unit.isPresent() ? PriceGuard.minSystemSalePrice(who, item, unit.getAsLong()) : 0;
		Payouts payouts = new Payouts(who);
		if (stack.getComponentsPatch().isEmpty()) {
			long high = payouts.high(item);
			if (high > 0) per = Math.max(per, PriceGuard.minSystemSale(payouts.sellHigh(item), high, PriceGuard.pivotPrice(item)));
		}
		per = Math.max(per, conversionFloor(payouts, item));
		if (per <= 0) return 0;
		return roundUpToBand(saturatingMul(per, stack.getCount()));
	}

	/**
	 * Floor of an NPC bargain (stack total, rounded up to 0.5 %). Bargains may be below {@link #saleFloor}: flipping to players,
	 * NPC buyers and NPC orders is intended and bounded by their budgets and the bargain budget ({@link NpcSellers}), but /sell
	 * must never be a risk-free instant profit. Per unit at least
	 * <ul>
	 *   <li>the /sell price after a crash ends ({@code Payouts.sellHigh}: buying during a crash does not sell higher afterwards)
	 *     + 1 cent,</li>
	 *   <li>the highest /sell payment through recipes and conversions ({@link #sellConversionUnit}, up to {@value
	 *     #CONVERSION_DEPTH} steps) + 1 cent,</li>
	 *   <li>for shop items pivot + 1 (PriceGuard).</li>
	 * </ul>
	 * At listing time ({@code buyer == null}, neutral buyer including a running market day) with margin {@code
	 * bargainSellMargin}, which covers the trader rank up to +12 % of later buyers; on purchase with the real buyer's boosts and
	 * no margin (the auction house raises the price below that). /sell does not take modified stacks (enchanted books): only
	 * pivot or 1 cent.
	 */
	public static long bargainFloor(UUID buyer, ItemStack stack) {
		if (stack == null || stack.isEmpty()) return 0;
		Item item = stack.getItem();
		UUID who = buyer == null ? NEUTRAL : buyer;
		long per = 1;
		if (stack.getComponentsPatch().isEmpty()) {
			Payouts payouts = new Payouts(who);
			double sell = Math.max(payouts.sellHigh(item), payouts.sellConversion(item, 1));
			if (sell > 0) {
				double margin = buyer == null ? Math.max(1.0, MarketConfig.get().bargainSellMargin) : 1.0;
				double v = Math.ceil(sell * margin - 1e-9) + 1;
				per = Math.max(per, v >= Money.MAX ? Money.MAX : (long) v);
			}
		}
		long pivot = PriceGuard.pivotPrice(item);
		if (pivot >= 0) per = Math.max(per, Math.min(Money.MAX - 1, pivot) + 1);
		return roundUpToBand(saturatingMul(per, stack.getCount()));
	}

	static long sellConversionUnit(UUID buyer, Item item) {
		double v = new Payouts(buyer == null ? NEUTRAL : buyer).sellConversion(item, 1);
		return v >= Money.MAX ? Money.MAX : (long) Math.floor(v + 1e-9);
	}

	/**
	 * Upper bound of what the system pays for one unit of this stack, the basis of a bargain's flip potential. Unmodified stacks:
	 * /sell after a crash ends, NPC buyers during auction fever and NPC orders/missions (market value × 1.25, at most pivot) with
	 * drift at the upper edge ({@code driftMax}, since buying many NPC offers raises drift and so NPC payments), the same through
	 * recipes, and speculation on a news hype (/sell base price × {@code bargainHypeHeadroom}, at most pivot). Modified stacks:
	 * only NPC buyers (base value without enchantment bonus unless {@code buyerPaysEnchantments}).
	 *
	 * <p>The hype headroom is a cautious margin, not a real route: goods without a pivot, with recipe ingredients and products,
	 * are reported to news ({@link #shockSensitive}, service {@code MarketGoods}) and never get an automatic hype. Shop items can
	 * be hyped, but every system payment for the item stays at most pivot, and their bargains cost at least pivot + 1.
	 */
	public static long maxSystemPayoutUnit(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return 0;
		Item item = stack.getItem();
		long pivot = PriceGuard.pivotPrice(item);
		MarketConfig c = MarketConfig.get();
		double best = 0;
		if (stack.getComponentsPatch().isEmpty() && fixedValue(item) && !c.buyerPaysFixedValues) {
			// Fixed collector values: no /sell price, NPC buyers do not pay them, NPC orders and missions require a /sell price, so the
			// system pays at most through recipes via /sell
			Payouts payouts = new Payouts(NEUTRAL);
			best = Math.max(payouts.sellHigh(item), payouts.sellConversion(item, 1));
		} else if (stack.getComponentsPatch().isEmpty()) {
			Payouts payouts = new Payouts(NEUTRAL);
			best = Math.max(payouts.high(item), payouts.conversion(item, 1));
			long high = value(item, Mode.HIGH).orElse(0);
			if (high > 0) {
				// Project the item's drift to the upper edge (crafting cap still applies)
				double atMax = high / Math.max(1.0, Drift.current(item, System.currentTimeMillis())) * c.driftMax;
				Long cap = model.craftCaps().get(item);
				if (cap != null) atMax = Math.min(atMax, cap);
				long v = atMax >= Money.MAX ? Money.MAX : (long) Math.ceil(atMax - 1e-9);
				best = Math.max(best, PriceGuard.capNpcBuy(-1, v, PriceGuard.NPC_BUY_MARKET_FACTOR_MAX, pivot));
			}
			OptionalLong base = BasePrices.sellPrice(item);
			if (base.isPresent()) {
				double hyped = base.getAsLong() * Math.max(1.0, c.bargainHypeHeadroom);
				if (pivot >= 0) hyped = Math.min(hyped, pivot);
				best = Math.max(best, hyped);
			}
		} else {
			long v = buyerUnitValue(stack).orElse(0);
			if (v > 0) {
				double atMax = Math.ceil(v * Math.max(1.0, c.driftMax) - 1e-9);
				best = PriceGuard.capNpcBuy(-1, atMax >= Money.MAX ? Money.MAX : (long) atMax, PriceGuard.NPC_BUY_MARKET_FACTOR_MAX, pivot);
			}
		}
		return best >= Money.MAX ? Money.MAX : (long) Math.ceil(best - 1e-9);
	}

	public static long bargainPotential(ItemStack stack, long price) {
		if (stack == null || stack.isEmpty()) return 0;
		long payout = saturatingMul(maxSystemPayoutUnit(stack), stack.getCount());
		return Math.max(0, payout - Math.max(0, price));
	}

	/** Recipe index from ingredient to results (recipes only, no villager trades) for {@link #shockSensitive}, per model. */
	private static Map<Item, Set<Item>> outputsByInput = Map.of();
	private static int outputsModel = -1;

	/** Counter of model rebuilds; cache key for {@link ShockGuard}. */
	static int modelVersion() {
		return modelVersion;
	}

	/**
	 * Items whose price modifier (news crash or hype) changes what the system pays for {@code goods}; the basis of {@link
	 * dev.smpeconomy.core.MarketGoods}. News never hypes these automatically. A crash has no time arbitrage thanks to {@link
	 * Mode#HIGH}, whether news crashes them is up to news. Per good without a pivot:
	 * <ul>
	 *   <li>the good itself ({@link #saleFloor} applies at listing time; a later hype would raise /sell, NPC buyers, NPC orders
	 *     and missions above it),</li>
	 *   <li>for a recipe estimate all ingredients, substitutes and remainders its market value derives from, also through further
	 *     estimates (an ender pearl hype raises the ender eye's market value),</li>
	 *   <li>everything craftable from it, up to {@value #CONVERSION_DEPTH} steps, as main or side ingredient: {@link
	 *     #conversionFloor} uses payments at listing time.</li>
	 * </ul>
	 * Goods with a pivot do not count: every system payment is at most pivot and NPC sellers never go below pivot + 1, even
	 * during a hype. Villager trades do not count: their results are marked, and news never touches emeralds.
	 */
	static Set<Item> shockSensitive(Collection<Item> goods) {
		Model m = model;
		Map<Item, Set<Item>> outputs = outputsIndex();
		Set<Item> out = new LinkedHashSet<>();
		for (Item g : goods) {
			if (g == null || g == net.minecraft.world.item.Items.AIR || PriceGuard.pivotPrice(g) >= 0) continue;
			out.add(g);
			addEstimateInputs(m, g, out, new HashSet<>());
			Set<Item> seen = new HashSet<>();
			seen.add(g);
			List<Item> frontier = List.of(g);
			for (int depth = 0; depth < CONVERSION_DEPTH && !frontier.isEmpty(); depth++) {
				List<Item> next = new ArrayList<>();
				for (Item f : frontier) {
					for (Item o : outputs.getOrDefault(f, Set.of())) {
						if (seen.add(o)) {
							next.add(o);
							out.add(o);
						}
					}
				}
				frontier = next;
			}
		}
		return out;
	}

	private static void addEstimateInputs(Model m, Item item, Set<Item> out, Set<Item> seen) {
		if (!seen.add(item) || m.source(item) != Source.RECIPE) return;
		Estimate e = m.estimates().get(item);
		if (e == null) return;
		for (List<Item> options : e.options()) {
			for (Item i : options) {
				out.add(i);
				ItemStack rem = Mc.craftingRemainder(i);
				if (!rem.isEmpty()) out.add(rem.getItem());
				addEstimateInputs(m, i, out, seen);
			}
		}
	}

	/** Rebuilt after every model rebuild. */
	private static Map<Item, Set<Item>> outputsIndex() {
		if (outputsModel == modelVersion) return outputsByInput;
		Map<Item, Set<Item>> index = new HashMap<>();
		for (RawRecipe r : lastRecipes) {
			if (r.out() == null || r.count() <= 0) continue;
			for (List<Item> slot : r.ingredients()) {
				for (Item i : slot) {
					if (i != null && i != r.out()) index.computeIfAbsent(i, k -> new LinkedHashSet<>()).add(r.out());
				}
			}
		}
		outputsByInput = index;
		outputsModel = modelVersion;
		return index;
	}

	static final int CONVERSION_DEPTH = 3;
	/**
	 * A recipe counts for an item only if the item supplies at least this share of the ingredient value (main ingredient). The
	 * added value of a recipe whose value comes from other ingredients (gold in a netherite ingot, blaze rod in copper lamps) is
	 * available to anyone holding those ingredients, even without NPC sellers, and is attributed to the main ingredient.
	 */
	static final double MAIN_INPUT_SHARE = 0.5;

	/**
	 * Minimum price per unit from conversions, 0 if none. For every recipe consuming the item as main ingredient (up to {@value
	 * #CONVERSION_DEPTH} steps): {@code k × price > count × highest system payment for the result - other ingredients}. Results
	 * are valued with {@link #payoutHigh}, other ingredients with {@link #payoutLow} (what the system pays for sure). So "buy
	 * iron block from the NPC, split it, sell ingots during hype" or "4 netherite scrap + gold to netherite ingot, sold to NPC
	 * buyers" never pays.
	 */
	static long conversionFloor(UUID buyer, Item item) {
		return conversionFloor(new Payouts(buyer == null ? NEUTRAL : buyer), item);
	}

	private static long conversionFloor(Payouts payouts, Item item) {
		double best = payouts.conversion(item, 1);
		if (!(best > 0)) return 0;
		double v = Math.ceil(best - 1e-9) + 1;
		return v >= Money.MAX ? Money.MAX : (long) v;
	}

	/** Upper bound of what the system pays {@code who} now or after a crash ends for an unmodified unit. */
	static long payoutHigh(UUID who, Item item) {
		return new Payouts(who).high(item);
	}

	/** Lower bound of what the system pays {@code who} for an unmodified unit (hype, upward drift and boosts neutralized). */
	static long payoutLow(UUID who, Item item) {
		return new Payouts(who).low(item);
	}

	/** System payments per unit for one buyer; caches intermediate results for one computation at a single moment. */
	private static final class Payouts {
		private final UUID who;
		private final Map<Item, List<Use>> uses = model.uses();
		private final Map<Item, Long> high = new IdentityHashMap<>();
		private final Map<Item, Long> sellHigh = new IdentityHashMap<>();
		private final Map<Item, Long> low = new IdentityHashMap<>();
		private final Map<Item, double[]> conversions = new IdentityHashMap<>();
		private final Map<Item, double[]> sellConversions = new IdentityHashMap<>();

		Payouts(UUID who) {
			this.who = who;
		}

		/**
		 * /sell price now, projected to after a crash ends (effective price ÷ modifier, rounded up, at most pivot). Personal
		 * boosts as they are now.
		 */
		long sellHigh(Item item) {
			Long cached = sellHigh.get(item);
			if (cached != null) return cached;
			long sell = PriceGuard.effectiveSellPrice(who, item).orElse(0);
			double mod = PriceModifiers.get(item);
			if (sell > 0 && mod < 1) {
				double undone = Math.ceil((sell + 1) / mod);
				sell = undone >= Money.MAX ? Money.MAX : (long) undone;
				long pivot = PriceGuard.pivotPrice(item);
				if (pivot >= 0) sell = Math.min(sell, pivot);
			}
			sellHigh.put(item, sell);
			return sell;
		}

		/**
		 * max(/sell after crash end, NPC buyer/order with market value in {@link Mode#HIGH} × 1.25, without shop cap, at most
		 * pivot).
		 */
		long high(Item item) {
			Long cached = high.get(item);
			if (cached != null) return cached;
			long v = value(item, Mode.HIGH).orElse(0);
			long npc = v > 0 ? PriceGuard.capNpcBuy(-1, v, PriceGuard.NPC_BUY_MARKET_FACTOR_MAX, PriceGuard.pivotPrice(item)) : 0;
			long result = Math.max(sellHigh(item), npc);
			high.put(item, result);
			return result;
		}

		/**
		 * max(/sell without boosts and hype, NPC buyer (× 1.15) / NPC order with market value in {@link Mode#LOW}): what the item
		 * brings once hype, fever and market day are over.
		 */
		long low(Item item) {
			Long cached = low.get(item);
			if (cached != null) return cached;
			long sell = 0;
			OptionalLong base = BasePrices.sellPrice(item);
			OptionalLong eff = PriceGuard.effectiveSellPrice(who, item);
			if (base.isPresent() && eff.isPresent()) {
				long noHype = PriceGuard.computeSell(base.getAsLong(), Math.min(1.0, PriceModifiers.get(item)), 1.0, PriceGuard.pivotPrice(item));
				sell = Math.min(eff.getAsLong(), noHype);
			}
			long v = value(item, Mode.LOW).orElse(0);
			long npc = v > 0 ? Math.max(PriceGuard.maxNpcBuyPrice(who, item, v), PriceGuard.maxNpcOrderPrice(who, item, v)) : 0;
			long result = Math.max(sell, npc);
			low.put(item, result);
			return result;
		}

		/** /sell without boosts and hype (a crash counts): the least an extra ingredient brings via /sell. 0 if unsellable. */
		long sellLow(Item item) {
			OptionalLong base = BasePrices.sellPrice(item);
			OptionalLong eff = PriceGuard.effectiveSellPrice(who, item);
			if (base.isEmpty() || eff.isEmpty()) return 0;
			long noHype = PriceGuard.computeSell(base.getAsLong(), Math.min(1.0, PriceModifiers.get(item)), 1.0, PriceGuard.pivotPrice(item));
			return Math.min(eff.getAsLong(), noHype);
		}

		/**
		 * Highest /sell payment per unit reachable through recipes and conversions from step {@code depth}, 0 if none. Like
		 * {@link #conversion}, but only for recipes where the item is the main ingredient ({@value #MAIN_INPUT_SHARE} of the
		 * value): the added value of a recipe whose value comes from other ingredients (4 netherite scrap + 4 gold) is available
		 * to anyone owning those, mined gold as much as bargain gold, so the main ingredient carries it.
		 */
		double sellConversion(Item item, int depth) {
			double[] memo = sellConversions.get(item);
			if (memo == null) {
				memo = new double[CONVERSION_DEPTH + 1];
				java.util.Arrays.fill(memo, Double.NaN);
				sellConversions.put(item, memo);
			}
			if (!Double.isNaN(memo[depth])) return memo[depth];
			memo[depth] = 0;
			double best = 0;
			List<Use> list = uses.getOrDefault(item, List.of());
			if (!list.isEmpty()) {
				double ownRemainder = sellRemainderHigh(item);
				double own = high(item); // Share as in conversion (upper bound of the value)
				for (Use u : list) {
					double others = 0;
					for (List<Item> slot : u.others()) others += sellCredit(slot);
					double inputs = u.k() * own + others;
					if (inputs > 0 && u.k() * own < MAIN_INPUT_SHARE * inputs) continue;
					double out = sellHigh(u.out());
					if (depth < CONVERSION_DEPTH) out = Math.max(out, sellConversion(u.out(), depth + 1));
					double gain = u.count() * out + u.k() * ownRemainder - others;
					if (gain > 0) best = Math.max(best, gain / u.k());
				}
			}
			memo[depth] = best;
			return best;
		}

		private double sellCredit(List<Item> slot) {
			double min = Double.POSITIVE_INFINITY;
			for (Item i : slot) min = Math.min(min, Math.max(0, sellLow(i) - sellRemainderHigh(i)));
			return min == Double.POSITIVE_INFINITY ? 0 : min;
		}

		private double sellRemainderHigh(Item item) {
			ItemStack rem = Mc.craftingRemainder(item);
			return rem.isEmpty() ? 0 : (double) sellHigh(rem.getItem()) * rem.getCount();
		}

		double conversion(Item item, int depth) {
			double[] memo = conversions.get(item);
			if (memo == null) {
				memo = new double[CONVERSION_DEPTH + 1];
				java.util.Arrays.fill(memo, Double.NaN);
				conversions.put(item, memo);
			}
			if (!Double.isNaN(memo[depth])) return memo[depth];
			memo[depth] = 0;
			double best = 0;
			List<Use> list = uses.getOrDefault(item, List.of());
			if (!list.isEmpty()) {
				// Share with the upper bound: a crash on the item itself does not turn it into a side ingredient
				double own = high(item);
				double ownRemainder = remainderHigh(item);
				for (Use u : list) {
					double others = 0;
					for (List<Item> slot : u.others()) others += credit(slot);
					double inputs = u.k() * own + others;
					if (inputs > 0 && u.k() * own < MAIN_INPUT_SHARE * inputs) continue;
					double out = high(u.out());
					if (depth < CONVERSION_DEPTH) out = Math.max(out, conversion(u.out(), depth + 1));
					double gain = u.count() * out + u.k() * ownRemainder - others;
					if (gain > 0) best = Math.max(best, gain / u.k());
				}
			}
			memo[depth] = best;
			return best;
		}

		private double credit(List<Item> slot) {
			double min = Double.POSITIVE_INFINITY;
			for (Item i : slot) min = Math.min(min, Math.max(0, low(i) - remainderHigh(i)));
			return min == Double.POSITIVE_INFINITY ? 0 : min;
		}

		private double remainderHigh(Item item) {
			ItemStack rem = Mc.craftingRemainder(item);
			return rem.isEmpty() ? 0 : (double) high(rem.getItem()) * rem.getCount();
		}
	}

	/**
	 * Rounds a floor up to the next 0.5 % grid. Gentle price changes (modifier fade-out, drift steps) then rarely move the floor,
	 * so a raised price stays valid for a while instead of rejecting every purchase attempt with "price changed".
	 */
	static long roundUpToBand(long floor) {
		if (floor <= 1) return floor;
		long step = Math.max(1, (floor + 199) / 200);
		long rounded = ((floor + step - 1) / step) * step;
		return Math.min(Money.MAX, Math.max(floor, rounded));
	}

	static long saturatingMul(long a, long b) {
		if (a <= 0 || b <= 0) return 0;
		if (a > Money.MAX / b) return Money.MAX;
		return a * b;
	}

	static Runnable swapModel(Model m) {
		Model old = model;
		model = m;
		modelVersion++;
		return () -> {
			model = old;
			modelVersion++;
		};
	}

	static String sourceLabel(Source s) {
		return switch (s) {
			case PRICE -> "sell price × factor";
			case OVERRIDE -> "fixed market value";
			case RECIPE -> "recipe estimate";
			case NONE -> "no value";
		};
	}

	static Function<Item, OptionalLong> asService() {
		return ValueEngine::value;
	}
}
