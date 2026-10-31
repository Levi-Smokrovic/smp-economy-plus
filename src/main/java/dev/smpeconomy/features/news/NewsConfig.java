package dev.smpeconomy.features.news;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.MarketGoods;
import dev.smpeconomy.core.Services;

/**
 * Hype items must not be shop items and must not be craftable cheaply from shop items. {@link #families} makes a shock on
 * an item also hit its storage blocks, nuggets and ores (otherwise "buy redstone in the shop during a crash, craft a block, sell the block at normal price"). Items the system
 * sells through market NPCs ({@link #MARKET_SOLD}) never get a hype, and a crash only with {@link #crashMarketSold}. Flash sales
 * also skip villager goods ({@link NewsVillagerRoutes}). A crash only lowers the sell side (PriceGuard keeps shop price and NPC
 * offers at their no-crash level), so it gives no time arbitrage on any item.
 */
public final class NewsConfig {
	/**
	 * Never hyped, with family, whatever news.json says: non-shop items market sells through NPC offers. Without a shop
	 * entry no pivot protects them over time: buying from the NPC before a hype and selling via /sell during it would be money
	 * from nothing. Crashes are allowed ({@link #crashMarketSold}), since the floor of NPC offers does not fall during a crash.
	 * Admin hypes stay possible, with a warning.
	 */
	static final List<String> MARKET_SOLD = List.of("minecraft:diamond", "minecraft:emerald", "minecraft:iron_ingot",
		"minecraft:gold_ingot", "minecraft:golden_apple", "minecraft:netherite_scrap");
	/**
	 * Never hypeable, even if a news.json contains them ({@link #validate} removes them, selection also checks the family):
	 * craftable without limit or cheaply from shop items, villager trades or farms. Armadillo scute: Armadillo#brushOffScute has
	 * no cooldown and a brush (shop ingredients) gives 4, with durability and repair as many as you like, and dispensers brush
	 * automatically.
	 */
	static final List<String> NEVER_HYPE = List.of("minecraft:armadillo_scute", "minecraft:wheat", "minecraft:beetroot",
		"minecraft:flint", "minecraft:glowstone_dust", "minecraft:cocoa_beans", "minecraft:carrot", "minecraft:potato",
		"minecraft:charcoal", "minecraft:nether_brick", "minecraft:emerald", "minecraft:nautilus_shell", "minecraft:chicken",
		"minecraft:rabbit", "minecraft:porkchop", "minecraft:rabbit_foot", "minecraft:melon_slice");
	/**
	 * Never crashed (enforced like {@link #NEVER_HYPE}): bone and bone meal. A safety margin for the sensitive bone meal chain
	 * ("fertilize, sell logs or crops"), even though a crash does not lower the shop price. Bone meal never gets cheaper in the
	 * shop, also not in a flash sale ({@link #FLASH_ALWAYS_EXCLUDED}).
	 */
	static final List<String> NEVER_CRASH = List.of("minecraft:bone", "minecraft:bone_block", "minecraft:bone_meal");
	/**
	 * Never in a flash sale, for the same reason: bone meal, its sources and saplings. Villager goods are added at runtime
	 * ({@link NewsVillagerRoutes}).
	 */
	static final List<String> FLASH_ALWAYS_EXCLUDED = List.of("minecraft:bone_meal", "minecraft:bone", "minecraft:bone_block",
		"minecraft:oak_sapling", "minecraft:spruce_sapling", "minecraft:birch_sapling", "minecraft:jungle_sapling",
		"minecraft:acacia_sapling", "minecraft:dark_oak_sapling", "minecraft:cherry_sapling", "minecraft:pale_oak_sapling",
		"minecraft:mangrove_propagule");

	/** Config format version; older files (no field = 0) are migrated once on load ({@link #migrate}). */
	static final int CONFIG_VERSION = 2;
	/** Curated items NPC orders move most (ores, crops, wood, mob drops); default for {@link #focusItems}. */
	static final List<String> DEFAULT_FOCUS = List.of("minecraft:coal", "minecraft:iron_ingot", "minecraft:wheat", "minecraft:diamond",
		"minecraft:copper_ingot", "minecraft:redstone", "minecraft:gold_ingot", "minecraft:lapis_lazuli", "minecraft:potato",
		"minecraft:carrot", "minecraft:sugar_cane", "minecraft:oak_log", "minecraft:pumpkin", "minecraft:quartz", "minecraft:gunpowder",
		"minecraft:string", "minecraft:bone", "minecraft:cod", "minecraft:salmon", "minecraft:beef");

	public int configVersion;
	public boolean enabled = true;

	/**
	 * Gap between two automatic events in minutes of online time (counted only while enough players are past the wait and outside
	 * quiet hours), random in [min, max] per event. The default 15-25 is about one Minecraft day.
	 */
	public int minIntervalMinutes = 15;
	public int maxIntervalMinutes = 25;
	/**
	 * Events running at once in main phase; a market shock with 2 items counts 2. At the limit a due event waits until one ends,
	 * and a market shock only takes as many items as there are free slots. Admin commands ignore this.
	 */
	public int maxActive = 3;
	/** Quiet hours [from, to) in server time zone; 1-8 means no automatic events 01:00-07:59. */
	public int quietFromHour = 1;
	public int quietToHour = 8;
	public int minPlayersOnline = 1;
	/** How long players must be online before a due event starts, so not right at join. */
	public int onlineGraceMinutes = 3;

	/** Strongest crash factor (0.5 = -50 %); an automatic crash is random between this and {@link #crashMaxFactor}. */
	public double crashFactor = 0.5;
	/** Mildest crash factor (0.8 = -20 %). */
	public double crashMaxFactor = 0.8;
	public double hypeMinFactor = 1.25;
	public double hypeMaxFactor = 2.0;
	public double hypeChance = 0.5;
	/** Share of hypes on non-shop items (shop items are capped at the pivot on sale). */
	public double hypePreferNonShop = 0.85;
	/** Hype on shop items only if its effect is at least this much (0.25 = +25 %). */
	public double minShopHypeEffect = 0.25;
	/** No crash on items with a base sell price below this (cents); -50 % of 2 cents is pointless. */
	public long minBaseSellCents = 10;
	public int minDurationMinutes = 10;
	public int maxDurationMinutes = 20;
	public int fadeOutMinutes = 3;
	/** Weights for 1, 2 or 3 items per market shock. */
	public List<Integer> itemCountWeights = new ArrayList<>(List.of(70, 25, 5));
	/**
	 * Items NPC orders move most ("coal +50 %" and orders follow): preferred in selection with {@link #focusWeight}. Crash
	 * candidates in addition to {@link #crashItems}, since a crash gives no time arbitrage on any item; hypes only if listed in
	 * {@link #hypeItems}/{@link #hypeShopItems}, which are curated.
	 */
	public List<String> focusItems = new ArrayList<>(DEFAULT_FOCUS);
	/** Weight of {@link #focusItems} in selection (1.0 = same as all others). */
	public double focusWeight = 3.0;
	/**
	 * Crash on market NPC goods too ({@link #MARKET_SOLD}): safe, because neither the shop price nor the floor of NPC offers
	 * falls during a crash. Hypes on market NPC goods never happen (buy from the NPC first, sell during the hype).
	 */
	public boolean crashMarketSold = true;

	/**
	 * Crash candidates in addition to {@link #focusItems}, without bones ({@link #NEVER_CRASH}). A crash only lowers what the
	 * system pays, shop price and NPC offers stay, so it gives no time arbitrage on villager or market NPC goods either ({@link
	 * #crashMarketSold}).
	 */
	public List<String> crashItems = new ArrayList<>(List.of(
		"minecraft:copper_ingot", "minecraft:coal", "minecraft:lapis_lazuli", "minecraft:quartz", "minecraft:amethyst_shard",
		"minecraft:redstone", "minecraft:wheat", "minecraft:beetroot", "minecraft:pumpkin", "minecraft:cactus",
		"minecraft:cocoa_beans", "minecraft:gunpowder", "minecraft:spider_eye", "minecraft:ender_pearl",
		"minecraft:slime_ball", "minecraft:blaze_rod", "minecraft:cod", "minecraft:salmon", "minecraft:beef", "minecraft:porkchop",
		"minecraft:chicken", "minecraft:mutton"));

	/**
	 * Hypes: only non-shop items that cannot be made cheaply from shop items (recipes, bone meal, mining, breeding with shop
	 * feed) and that the system does not sell through market NPCs ({@link #isMarketSold}, otherwise "buy NPC offer, sell via
	 * /sell during hype"). Copper, lapis, quartz, amethyst, shulker shell and breeze rod are therefore not in the default trader
	 * pool; if an admin adds them, selection filters them at runtime. Deliberately without emerald (villagers trade cheap shop
	 * items for emeralds), nautilus shell (wandering trader) and armadillo scute, see {@link #NEVER_HYPE}.
	 */
	public List<String> hypeItems = new ArrayList<>(List.of(
		"minecraft:copper_ingot", "minecraft:coal", "minecraft:lapis_lazuli", "minecraft:quartz", "minecraft:amethyst_shard",
		"minecraft:pumpkin",
		"minecraft:cod", "minecraft:salmon", "minecraft:tropical_fish", "minecraft:pufferfish", "minecraft:beef",
		"minecraft:mutton", "minecraft:shulker_shell", "minecraft:breeze_rod", "minecraft:turtle_scute"));

	/**
	 * Rare hypes on shop items (sale capped at the pivot, the effective value is shown). Without items that multiply themselves
	 * with bone meal (cocoa, carrots, potatoes), where buying bone meal would pay. Without melon slice: its family (melon, not a
	 * shop item, craftable from 9 shop slices) would not be capped by the pivot. A shop hype always skips family members without
	 * a pivot ({@link NewsEngine#hypeSkips}), also from an older news.json and on admin triggers.
	 */
	public List<String> hypeShopItems = new ArrayList<>(List.of(
		"minecraft:sugar_cane", "minecraft:cactus", "minecraft:bamboo", "minecraft:nether_wart", "minecraft:redstone",
		"minecraft:ender_pearl", "minecraft:blaze_rod", "minecraft:bone", "minecraft:phantom_membrane",
		"minecraft:leather", "minecraft:ghast_tear"));

	/**
	 * Item to further items getting the same factor (storage blocks, nuggets, ores, raw forms). Exception: a hype on a shop item
	 * only sets members with a pivot ({@link NewsEngine#hypeSkips}); crashes always hit the whole family.
	 */
	public Map<String, List<String>> families = defaultFamilies();

	/** Share of events that are an action instead of a market shock. */
	public double actionChance = 0.3;
	public double flashSaleWeight = 1.0;
	public double bulkOrderWeight = 1.0;
	public double auctionFeverWeight = 1.0;

	public int flashSaleMinPercent = 20;
	public int flashSaleMaxPercent = 40;
	public int flashSaleMinutes = 15;
	/** Shop category ids from shop.json for flash sales; empty means all. */
	public List<String> flashSaleCategories = new ArrayList<>();
	/**
	 * Items a flash sale never discounts, even if their category is on sale. Always includes {@link #FLASH_ALWAYS_EXCLUDED}; more
	 * ids can be added.
	 */
	public List<String> flashSaleExcludedItems = new ArrayList<>(FLASH_ALWAYS_EXCLUDED);

	public int bulkOrderMinutes = 20;
	public int bulkOrderMinAmount = 64;
	public int bulkOrderMaxAmount = 2048;
	/** Share of the treasury's hourly budget a bulk order ties up at most. */
	public double bulkOrderBudgetShare = 0.5;
	public List<String> bulkOrderItems = new ArrayList<>(List.of(
		"minecraft:wheat", "minecraft:carrot", "minecraft:potato", "minecraft:beetroot", "minecraft:pumpkin",
		"minecraft:sugar_cane", "minecraft:oak_log", "minecraft:spruce_log", "minecraft:birch_log", "minecraft:cobblestone",
		"minecraft:coal", "minecraft:iron_ingot", "minecraft:copper_ingot", "minecraft:redstone", "minecraft:bone",
		"minecraft:string", "minecraft:rotten_flesh", "minecraft:gunpowder", "minecraft:cod", "minecraft:salmon"));

	public int auctionFeverMinutes = 15;
	/** Pick auction fever at random only if the market is active (MarketValues service registered). */
	public boolean auctionFeverRequiresMarket = true;

	public int tickerSeconds = 2;
	/** History entries; at about 50 events per day this is about 2 days. */
	public int historySize = 100;
	public boolean titleOnStart = true;
	public boolean soundOnStart = true;

	static Map<String, List<String>> defaultFamilies() {
		Map<String, List<String>> m = new LinkedHashMap<>();
		m.put("minecraft:diamond", List.of("minecraft:diamond_block", "minecraft:diamond_ore", "minecraft:deepslate_diamond_ore"));
		m.put("minecraft:emerald", List.of("minecraft:emerald_block", "minecraft:emerald_ore", "minecraft:deepslate_emerald_ore"));
		m.put("minecraft:iron_ingot", List.of("minecraft:iron_block", "minecraft:iron_nugget", "minecraft:raw_iron",
			"minecraft:raw_iron_block", "minecraft:iron_ore", "minecraft:deepslate_iron_ore"));
		m.put("minecraft:gold_ingot", List.of("minecraft:gold_block", "minecraft:gold_nugget", "minecraft:raw_gold",
			"minecraft:raw_gold_block", "minecraft:gold_ore", "minecraft:deepslate_gold_ore", "minecraft:nether_gold_ore"));
		m.put("minecraft:copper_ingot", List.of("minecraft:copper_block", "minecraft:copper_nugget", "minecraft:raw_copper",
			"minecraft:raw_copper_block", "minecraft:copper_ore", "minecraft:deepslate_copper_ore"));
		m.put("minecraft:coal", List.of("minecraft:coal_block", "minecraft:coal_ore", "minecraft:deepslate_coal_ore"));
		m.put("minecraft:lapis_lazuli", List.of("minecraft:lapis_block", "minecraft:lapis_ore", "minecraft:deepslate_lapis_ore"));
		m.put("minecraft:redstone", List.of("minecraft:redstone_block", "minecraft:redstone_ore", "minecraft:deepslate_redstone_ore"));
		m.put("minecraft:quartz", List.of("minecraft:nether_quartz_ore"));
		m.put("minecraft:amethyst_shard", List.of("minecraft:amethyst_block"));
		m.put("minecraft:netherite_scrap", List.of("minecraft:netherite_ingot", "minecraft:netherite_block", "minecraft:ancient_debris"));
		m.put("minecraft:wheat", List.of("minecraft:hay_block"));
		m.put("minecraft:melon_slice", List.of("minecraft:melon"));
		m.put("minecraft:slime_ball", List.of("minecraft:slime_block"));
		m.put("minecraft:bone", List.of("minecraft:bone_block"));
		m.put("minecraft:nether_wart", List.of("minecraft:nether_wart_block"));
		m.put("minecraft:bamboo", List.of("minecraft:bamboo_block"));
		return m;
	}

	/**
	 * One-time migration of an older news.json (format &lt; {@link #CONFIG_VERSION}) to the new schedule: values still equal to
	 * the old default get the new one (WARN in the log), custom values stay. ConfigManager adds new keys and drops removed ones
	 * (minPerDay, maxPerDay, minGapHours) on write-back. Returns the changed keys.
	 */
	List<String> migrate() {
		List<String> changed = new ArrayList<>();
		if (configVersion >= CONFIG_VERSION) return changed;
		if (minDurationMinutes == 20) { minDurationMinutes = 10; changed.add("minDurationMinutes 20→10"); }
		if (maxDurationMinutes == 60) { maxDurationMinutes = 20; changed.add("maxDurationMinutes 60→20"); }
		if (fadeOutMinutes == 5) { fadeOutMinutes = 3; changed.add("fadeOutMinutes 5→3"); }
		if (hypeMinFactor == 2.0 && hypeMaxFactor == 3.0) {
			hypeMinFactor = 1.25;
			hypeMaxFactor = 2.0;
			changed.add("hypeMinFactor/hypeMaxFactor 2.0–3.0→1.25–2.0");
		}
		if (minShopHypeEffect == 0.5) { minShopHypeEffect = 0.25; changed.add("minShopHypeEffect 0.5→0.25"); }
		if (itemCountWeights != null && itemCountWeights.equals(List.of(50, 35, 15))) {
			itemCountWeights = new ArrayList<>(List.of(70, 25, 5));
			changed.add("itemCountWeights [50,35,15]→[70,25,5]");
		}
		if (bulkOrderMinutes == 30) { bulkOrderMinutes = 20; changed.add("bulkOrderMinutes 30→20"); }
		if (auctionFeverMinutes == 30) { auctionFeverMinutes = 15; changed.add("auctionFeverMinutes 30→15"); }
		if (historySize == 20) { historySize = 100; changed.add("historySize 20→100"); }
		configVersion = CONFIG_VERSION;
		if (!changed.isEmpty()) {
			SmpEconomy.LOG.warn("[SMPEco] news.json migrated once to the new schedule (~1 event per Minecraft day): {}", changed);
		}
		return changed;
	}

	/** ConfigManager: throwing keeps the old state active. */
	void validate() {
		migrate();
		if (!(crashFactor > 0) || crashFactor >= 1) throw new IllegalArgumentException("crashFactor must be in (0, 1)");
		if (!(crashMaxFactor >= crashFactor) || crashMaxFactor >= 1) throw new IllegalArgumentException("crashMaxFactor must be in [crashFactor, 1)");
		if (!(hypeMinFactor > 1) || hypeMaxFactor < hypeMinFactor || hypeMaxFactor > 20) {
			throw new IllegalArgumentException("hypeMinFactor/hypeMaxFactor invalid (1 < min <= max <= 20)");
		}
		if (minIntervalMinutes < 1 || maxIntervalMinutes < minIntervalMinutes || maxIntervalMinutes > 24 * 60) {
			throw new IllegalArgumentException("minIntervalMinutes/maxIntervalMinutes invalid (1 <= min <= max <= 1440)");
		}
		if (maxActive < 1 || maxActive > 10) throw new IllegalArgumentException("maxActive must be 1–10");
		if (quietFromHour < 0 || quietFromHour > 23 || quietToHour < 0 || quietToHour > 23) throw new IllegalArgumentException("quiet hours must be 0–23");
		if (minDurationMinutes < 1 || maxDurationMinutes < minDurationMinutes) throw new IllegalArgumentException("duration invalid");
		if (flashSaleMinPercent < 1 || flashSaleMaxPercent < flashSaleMinPercent || flashSaleMaxPercent > 90) {
			throw new IllegalArgumentException("flash sale percent invalid (1–90)");
		}
		if (bulkOrderMinAmount < 1 || bulkOrderMaxAmount < bulkOrderMinAmount) throw new IllegalArgumentException("bulk order amount invalid");
		if (crashItems == null) crashItems = new ArrayList<>();
		if (hypeItems == null) hypeItems = new ArrayList<>();
		if (hypeShopItems == null) hypeShopItems = new ArrayList<>();
		if (families == null) families = new LinkedHashMap<>();
		if (flashSaleCategories == null) flashSaleCategories = new ArrayList<>();
		if (bulkOrderItems == null) bulkOrderItems = new ArrayList<>();
		if (flashSaleExcludedItems == null) flashSaleExcludedItems = new ArrayList<>();
		if (focusItems == null) focusItems = new ArrayList<>();
		focusItems.removeIf(id -> id == null);
		if (!(focusWeight >= 0) || Double.isInfinite(focusWeight)) focusWeight = 1.0;
		crashItems = without(crashItems, NEVER_CRASH, "crashItems");
		hypeItems = without(hypeItems, NEVER_HYPE, "hypeItems");
		hypeShopItems = without(hypeShopItems, NEVER_HYPE, "hypeShopItems");
		List<String> marketSold = new ArrayList<>(marketSoldIds());
		if (!crashMarketSold) crashItems = without(crashItems, marketSold, "crashItems");
		hypeItems = without(hypeItems, marketSold, "hypeItems");
		hypeShopItems = without(hypeShopItems, marketSold, "hypeShopItems");
		List<String> excluded = new ArrayList<>(flashSaleExcludedItems);
		excluded.removeIf(id -> id == null);
		for (String id : FLASH_ALWAYS_EXCLUDED) {
			if (!excluded.contains(id)) excluded.add(id);
		}
		flashSaleExcludedItems = excluded;
		if (itemCountWeights == null || itemCountWeights.isEmpty()) itemCountWeights = new ArrayList<>(List.of(1));
		hypeChance = clamp01(hypeChance);
		hypePreferNonShop = clamp01(hypePreferNonShop);
		actionChance = clamp01(actionChance);
		bulkOrderBudgetShare = clamp01(bulkOrderBudgetShare);
		fadeOutMinutes = Math.max(0, fadeOutMinutes);
		tickerSeconds = Math.max(1, Math.min(10, tickerSeconds));
		historySize = Math.max(1, Math.min(500, historySize)); // The history GUI pages by 45
		onlineGraceMinutes = Math.max(0, onlineGraceMinutes);
		minPlayersOnline = Math.max(1, minPlayersOnline);
		flashSaleMinutes = Math.max(1, flashSaleMinutes);
		bulkOrderMinutes = Math.max(1, bulkOrderMinutes);
		auctionFeverMinutes = Math.max(1, auctionFeverMinutes);
	}

	Set<String> familyIds(String id) {
		Set<String> set = new LinkedHashSet<>();
		set.add(id);
		if (families == null) return set;
		families.forEach((k, v) -> {
			List<String> members = v == null ? List.of() : v;
			if (id.equals(k) || members.contains(id)) {
				set.add(k);
				set.addAll(members);
			}
		});
		set.remove(null);
		return set;
	}

	/** {@link #MARKET_SOLD} with families: any item whose family contains one of them is never shocked. */
	Set<String> marketSoldIds() {
		Set<String> set = new LinkedHashSet<>();
		for (String id : MARKET_SOLD) set.addAll(familyIds(id));
		return set;
	}

	/**
	 * Whether {@code id} belongs to market NPC goods through its family: fixed list {@link #MARKET_SOLD} ({@link #marketSoldIds},
	 * also without market) or what market reports at runtime ({@link MarketGoods}). {@link #validate} only removes the fixed list
	 * from item lists; selection filters what market reports, so items come back once market stops selling them. If the market
	 * service throws, every item counts as market goods (fail-closed: rather no hype than a money leak).
	 */
	boolean isMarketSold(String id) {
		Set<String> goods = marketGoods();
		if (goods == null) return true;
		Set<String> sold = marketSoldIds();
		for (String m : familyIds(id)) {
			if (sold.contains(m) || goods.contains(m)) return true;
		}
		return false;
	}

	/** Asked per candidate, so the service exception is only logged once. */
	private static boolean marketGoodsErrorLogged;

	/**
	 * What market currently sells through NPC sellers ({@link MarketGoods#shockSensitiveIds}). Empty without market, null if the
	 * service throws.
	 */
	static Set<String> marketGoods() {
		Optional<MarketGoods> service = Services.find(MarketGoods.class);
		if (service.isEmpty()) return Set.of();
		try {
			Set<String> ids = service.get().shockSensitiveIds();
			marketGoodsErrorLogged = false;
			return ids == null ? Set.of() : ids;
		} catch (RuntimeException e) {
			if (!marketGoodsErrorLogged) {
				marketGoodsErrorLogged = true;
				SmpEconomy.LOG.error("[SMPEco] news: market service MarketGoods throws, every item counts as market goods until it answers again", e);
			}
			return null;
		}
	}

	private static List<String> without(List<String> list, List<String> banned, String key) {
		List<String> out = new ArrayList<>(list);
		out.removeIf(id -> id == null);
		List<String> removed = new ArrayList<>(out);
		out.removeIf(banned::contains);
		removed.removeAll(out);
		if (!removed.isEmpty()) SmpEconomy.LOG.warn("[SMPEco] news: {} contains blocked items {}, ignored", key, removed);
		return out;
	}

	private static double clamp01(double v) {
		return Double.isNaN(v) ? 0 : Math.max(0, Math.min(1, v));
	}
}
