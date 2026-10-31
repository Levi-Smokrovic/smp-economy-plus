package dev.smpeconomy.features.market;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.item.Item;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.ConfigManager;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.UnknownItems;
import dev.smpeconomy.core.Money;

/**
 * {@code config/smpeconomy/market.json}. Always read through {@link #get()}, {@code /smpeco reload} replaces the object. Money
 * values are in cents.
 */
public final class MarketConfig {
	/** Must stay above the fields (static initialization order). */
	static final List<String> TRADER_NAMES = List.of("Trader Harry", "Trader Grace", "Merchant Oliver", "Trader Freya",
		"Trader Kyle", "Shopkeeper Fred", "Trader Lily", "Merchant Henry", "Trader Bella", "Trader Ben", "Shopkeeper Holly",
		"Trader Connor", "Merchant Isla", "Trader Gus", "Trader Emma", "Trader Will", "Shopkeeper Abby", "Trader Louis",
		"Trader Gemma", "Merchant Eddie");

	/** Must stay above {@link #CONFIG} (static initialization order). */
	static final List<String> DISCS = List.of("minecraft:music_disc_13", "minecraft:music_disc_cat", "minecraft:music_disc_blocks",
		"minecraft:music_disc_chirp", "minecraft:music_disc_creator", "minecraft:music_disc_creator_music_box", "minecraft:music_disc_far",
		"minecraft:music_disc_lava_chicken", "minecraft:music_disc_mall", "minecraft:music_disc_mellohi", "minecraft:music_disc_stal",
		"minecraft:music_disc_strad", "minecraft:music_disc_ward", "minecraft:music_disc_11", "minecraft:music_disc_wait",
		"minecraft:music_disc_otherside", "minecraft:music_disc_relic", "minecraft:music_disc_5", "minecraft:music_disc_pigstep",
		"minecraft:music_disc_precipice", "minecraft:music_disc_tears");

	/**
	 * Never sold by NPC sellers even if market.json lists them ({@link #validate} removes them, {@code NpcSellers.pruneNeverSold}
	 * clears existing offers). The emerald is the villager currency and emerald blocks and ores turn into emeralds losslessly. A
	 * farmer makes 6 bread ($9 via /sell) from 1 emerald, a loop of system, villager, system. {@code ValueEngine.saleFloor}
	 * counts trades at base prices, but cured villagers and Hero of the Village push the emerald cost of nearly all trades down
	 * to 1, which no floor covers safely. Must stay above {@link #CONFIG} (static initialization order).
	 */
	static final Set<String> NEVER_SOLD = Set.of("minecraft:emerald", "minecraft:emerald_block", "minecraft:emerald_ore",
		"minecraft:deepslate_emerald_ore");

	/** Factor on the base sell price (prices.json) giving the market value. */
	public double marketFactor = 1.6;
	/** Recipe estimate: sum of ingredient sell values × this factor. */
	public double recipeMarkup = 1.3;
	/**
	 * Fixed market values (cents per unit, before drift and price modifier) for items without a sell price, e.g. totems, elytra,
	 * music discs. If a recipe estimate also exists, the lower value wins. Items with a sell price ignore this.
	 */
	public Map<String, Long> valueOverrides = defaultOverrides();
	/** Bonus per enchantment and level in cents. Index 0 is level I, higher levels use the last value. Negative for curses. */
	public Map<String, List<Long>> enchantmentValues = defaultEnchantments();
	/** Lower bound of the durability factor; nearly broken items are still worth this much. */
	public double minDurabilityFactor = 0.05;

	public double driftMin = 0.6;
	public double driftMax = 1.6;
	/** Half-life of the decay toward 1.0. */
	public double driftHalfLifeHours = 12.0;
	/** Drift change per full stack of traded items (buying from an NPC +, selling to an NPC -). */
	public double driftStepPerStack = 0.04;
	/** Largest drift change from a single trade. */
	public double driftMaxStepPerTrade = 0.06;

	public boolean npcBuyersEnabled = true;
	/** Buy only if price ≤ market value × random(min..max). */
	public double buyerRandomMin = 0.85;
	public double buyerRandomMax = 1.15;
	public int buyerMinDelayMinutes = 5;
	public int buyerMaxDelayMinutes = 90;
	/** Treasury exhausted for now (but price ≤ hourly budget): retry after this many minutes... */
	public int buyerRetryMinutes = 15;
	/** ...at most this many times, then drop. */
	public int buyerMaxRetries = 4;
	/**
	 * Whether NPC buyers pay the enchantment bonus ({@code enchantmentValues}). Off by default: enchanted books are cheap to make
	 * in vanilla (librarian trades, enchanting table with an XP farm), so the treasury would pay table values for endless supply.
	 * Only the item itself is valued then (curses still deduct). NPC sellers and the display keep using the full market value.
	 */
	public boolean buyerPaysEnchantments = false;
	/**
	 * Whether NPC buyers pay for items with a fixed market value ({@code valueOverrides}: totems, music discs, nether star,
	 * trident) and for recipe estimates based on them (beacon, conduit). Off by default: their supply from raid, creeper, wither
	 * and drowned farms is not part of the economy model.
	 */
	public boolean buyerPaysFixedValues = false;
	/**
	 * At most this share of {@code Treasury.maxSpendPerHour()} goes to one seller per rolling hour, which leaves budget for other
	 * players and NPC orders. No NPC plans a purchase of a larger listing.
	 */
	public double buyerSellerHourShare = 0.5;
	/**
	 * At most this many hourly budgets ({@code Treasury.maxSpendPerHour()}) go to one seller per rolling 24 h. 3.0 is about 15 %
	 * of the treasury per day at 5 %/h, against up to about 70 % at full load around the clock.
	 */
	public double buyerSellerDayShare = 3.0;

	public boolean npcSellersEnabled = true;
	/** NPC offers market keeps in the AH. The target is rerolled every {@code targetRerollHours}; 50-80 is two pages. */
	public int sellerMinOffers = 50;
	public int sellerMaxOffers = 80;
	public int offerMinHours = 6;
	public int offerMaxHours = 24;
	/** Price = market value × random(min..max), never below PriceGuard.minSystemSalePrice (mostly overpriced). */
	public double offerPriceMin = 0.95;
	public double offerPriceMax = 1.4;
	/**
	 * Chance that a new offer comes from a "dumb trader": a real bargain at {@code bargainPriceMin..Max} of the normal NPC price,
	 * never below the bargain floor ({@code ValueEngine.bargainFloor}: /sell price including recipes × {@code bargainSellMargin})
	 * and only while the bargain budget lasts.
	 */
	public double bargainChance = 0.2;
	/** Bargain price = normal NPC price × random(min..max). */
	public double bargainPriceMin = 0.4;
	public double bargainPriceMax = 0.8;
	/** Share below the normal floor a bargain must reach; otherwise it becomes a normal offer. */
	public double bargainMinDiscount = 0.1;
	/** Share of bargains without the ★ note, recognizable only by price or the market value in the lore. */
	public double bargainHiddenChance = 0.35;
	/** Note line of visible bargains in the AH, at most 80 characters. */
	public String bargainNote = "§a★ Bargain! §7The Trader miscalculated.";
	/**
	 * Bargain duration in hours, shorter than normal offers (6-24 h). With {@code bargainChance} 0.2 about one in ten AH offers
	 * is a bargain on average, budget permitting.
	 */
	public int bargainMinHours = 4;
	public int bargainMaxHours = 12;
	public int bargainMaxActive = 12;
	/**
	 * Margin on the /sell floor at listing time: bargain ≥ (/sell price or /sell via recipes) × this factor + 1 cent. 1.15 covers
	 * the trader rank up to +12 % of later buyers. On purchase the real buyer's /sell price + 1 cent applies.
	 */
	public double bargainSellMargin = 1.15;
	/**
	 * Flip budget in cents per rolling 24 h for all players together: the summed flip potential (highest system payment including
	 * news hype headroom minus bargain price) of all active bargains and those sold in the last 24 h. Bargains that expire unsold
	 * return their potential.
	 */
	public long bargainDailyBudget = 500_000L;
	/** Speculation headroom in the flip potential: /sell base price × this factor (news hype up to +200 %, at most the pivot). */
	public double bargainHypeHeadroom = 3.0;
	/** At most one new NPC offer every this many seconds. */
	public int refillIntervalSeconds = 120;
	/** Below {@code sellerMinOffers}, e.g. after startup, refill faster: one offer every this many seconds. */
	public int refillFastSeconds = 5;
	public int targetRerollHours = 3;
	public List<String> traderNames = new ArrayList<>(TRADER_NAMES);
	public List<PoolEntry> pool = defaultPool();

	/** Trader pool entry: one of {@code items}, count min..max, optionally an enchanted book. */
	public static final class PoolEntry {
		public List<String> items = new ArrayList<>();
		public double weight = 1.0;
		public int min = 1;
		public int max = 1;
		/** Only for {@code minecraft:enchanted_book}: enchantment id and level. */
		public String enchantment;
		public int level = 1;

		public PoolEntry() {}

		PoolEntry(double weight, int min, int max, String... items) {
			this.weight = weight;
			this.min = min;
			this.max = max;
			this.items = new ArrayList<>(List.of(items));
		}

		static PoolEntry book(double weight, String enchantment, int level) {
			PoolEntry e = new PoolEntry(weight, 1, 1, "minecraft:enchanted_book");
			e.enchantment = enchantment;
			e.level = level;
			return e;
		}
	}

	/**
	 * Version of this file's defaults, used for the one-time migration in {@link #migrate}. 0 if the key is missing. Do not edit
	 * by hand.
	 */
	public int defaultsVersion = 0;

	/** Current defaults version. 2: two pages of NPC offers, larger trader pool, bargains. */
	static final int DEFAULTS_VERSION = 3;
	static final int OLD_MIN_OFFERS = 8;
	static final int OLD_MAX_OFFERS = 15;
	static final double OLD_BARGAIN_CHANCE = 0.12;

	static final ConfigManager.Config<MarketConfig> CONFIG = ConfigManager.register("market", MarketConfig.class, MarketConfig::new,
		MarketConfig::validate);

	public static MarketConfig get() {
		return CONFIG.get();
	}

	/**
	 * {@link #migrate} changed something on load and validation passed, but the file on disk is still old, because ConfigManager
	 * only writes back when keys are missing. No initializer: this is already set during static initialization.
	 */
	private static boolean migratedUnsaved;

	/**
	 * Writes market.json back once if the migration changed something. Not on a load failure: then the last good state or the
	 * defaults are active, and writing would overwrite the admin's edits in the invalid file.
	 */
	static void saveIfMigrated() {
		if (!migratedUnsaved) return;
		migratedUnsaved = false;
		if (CONFIG.problem() != null) {
			SmpEconomy.LOG.warn("[SMPEco] market.json is invalid, migrated defaults not saved, file left untouched");
			return;
		}
		try {
			CONFIG.save();
		} catch (java.io.IOException | RuntimeException e) {
			SmpEconomy.LOG.warn("[SMPEco] market.json: migrated defaults not saved ({}), will be applied again on next load", e.toString());
		}
	}

	static boolean neverSold(String id) {
		return id != null && NEVER_SOLD.contains(id);
	}

	static boolean neverSold(Item item) {
		return item != null && neverSold(Items.id(item));
	}

	/**
	 * One-time migration of an older market.json (no {@code defaultsVersion} key): values still exactly equal to an old default
	 * get the new one, values set by an admin stay. ConfigManager then writes the file back with the new key, which also drops
	 * obsolete keys like {@code buyBackLockHours}. Returns the changes.
	 */
	static List<String> migrate(MarketConfig c) {
		List<String> changes = new ArrayList<>();
		if (c.defaultsVersion >= DEFAULTS_VERSION) return changes;
		if (c.defaultsVersion < 2) {
			MarketConfig fresh = new MarketConfig();
			if (c.sellerMinOffers == OLD_MIN_OFFERS && c.sellerMaxOffers == OLD_MAX_OFFERS) {
				c.sellerMinOffers = fresh.sellerMinOffers;
				c.sellerMaxOffers = fresh.sellerMaxOffers;
				changes.add("NPC offers " + OLD_MIN_OFFERS + "–" + OLD_MAX_OFFERS + " → " + c.sellerMinOffers + "–" + c.sellerMaxOffers);
			}
			if (c.bargainChance == OLD_BARGAIN_CHANCE) {
				c.bargainChance = fresh.bargainChance;
				changes.add("bargain chance " + OLD_BARGAIN_CHANCE + " → " + c.bargainChance);
			}
			if (c.pool != null && (samePool(c.pool, legacyPoolR3()) || samePool(c.pool, legacyPoolR2()))) {
				c.pool = fresh.pool;
				changes.add("trader pool -> new default (" + c.pool.size() + " entries)");
			}
		}
		c.defaultsVersion = DEFAULTS_VERSION;
		return changes;
	}

	static boolean samePool(List<PoolEntry> a, List<PoolEntry> b) {
		var gson = dev.smpeconomy.core.DataStore.gson();
		return gson.toJsonTree(a).equals(gson.toJsonTree(b));
	}

	static void validate(MarketConfig c) {
		boolean versionRaised = c.defaultsVersion < DEFAULTS_VERSION;
		List<String> migrated = migrate(c);
		require(c.marketFactor > 0 && c.marketFactor <= 10, "marketFactor must be in (0, 10]");
		require(c.recipeMarkup > 0 && c.recipeMarkup <= 10, "recipeMarkup must be in (0, 10]");
		require(c.minDurabilityFactor >= 0 && c.minDurabilityFactor <= 1, "minDurabilityFactor must be 0–1");
		require(c.driftMin > 0 && c.driftMin <= 1 && c.driftMax >= 1 && c.driftMax <= 5, "driftMin ≤ 1 ≤ driftMax ≤ 5");
		require(c.driftHalfLifeHours > 0 && c.driftHalfLifeHours <= 24 * 30, "driftHalfLifeHours must be > 0");
		require(c.driftStepPerStack >= 0 && c.driftStepPerStack <= 1, "driftStepPerStack must be 0–1");
		require(c.driftMaxStepPerTrade >= 0 && c.driftMaxStepPerTrade <= 1, "driftMaxStepPerTrade must be 0–1");
		require(c.buyerRandomMin > 0 && c.buyerRandomMin <= c.buyerRandomMax && c.buyerRandomMax <= 2, "0 < buyerRandomMin ≤ buyerRandomMax ≤ 2");
		require(c.buyerMinDelayMinutes >= 1 && c.buyerMinDelayMinutes <= c.buyerMaxDelayMinutes && c.buyerMaxDelayMinutes <= 24 * 60,
			"1 ≤ buyerMinDelayMinutes ≤ buyerMaxDelayMinutes ≤ 1440");
		require(c.buyerRetryMinutes >= 1 && c.buyerRetryMinutes <= 24 * 60, "buyerRetryMinutes must be 1–1440");
		require(c.buyerMaxRetries >= 0 && c.buyerMaxRetries <= 100, "buyerMaxRetries must be 0–100");
		require(c.buyerSellerHourShare > 0 && c.buyerSellerHourShare <= 1, "buyerSellerHourShare must be in (0, 1]");
		require(c.buyerSellerDayShare >= c.buyerSellerHourShare && c.buyerSellerDayShare <= 24,
			"buyerSellerHourShare ≤ buyerSellerDayShare ≤ 24");
		require(c.sellerMinOffers >= 0 && c.sellerMinOffers <= c.sellerMaxOffers && c.sellerMaxOffers <= 200, "0 ≤ sellerMinOffers ≤ sellerMaxOffers ≤ 200");
		require(c.offerMinHours >= 1 && c.offerMinHours <= c.offerMaxHours && c.offerMaxHours <= 48, "1 ≤ offerMinHours ≤ offerMaxHours ≤ 48");
		require(c.offerPriceMin > 0 && c.offerPriceMin <= c.offerPriceMax && c.offerPriceMax <= 10, "0 < offerPriceMin ≤ offerPriceMax ≤ 10");
		require(c.bargainChance >= 0 && c.bargainChance <= 1, "bargainChance must be 0–1");
		require(c.bargainPriceMin > 0 && c.bargainPriceMin <= c.bargainPriceMax && c.bargainPriceMax <= 1, "0 < bargainPriceMin ≤ bargainPriceMax ≤ 1");
		require(c.bargainMinDiscount >= 0 && c.bargainMinDiscount < 1, "bargainMinDiscount must be in [0, 1)");
		require(c.bargainHiddenChance >= 0 && c.bargainHiddenChance <= 1, "bargainHiddenChance must be 0–1");
		require(c.bargainMinHours >= 1 && c.bargainMinHours <= c.bargainMaxHours && c.bargainMaxHours <= 48, "1 ≤ bargainMinHours ≤ bargainMaxHours ≤ 48");
		require(c.bargainMaxActive >= 0 && c.bargainMaxActive <= 200, "bargainMaxActive must be 0–200");
		require(c.bargainSellMargin >= 1 && c.bargainSellMargin <= 5, "bargainSellMargin must be 1–5");
		require(c.bargainDailyBudget >= 0 && c.bargainDailyBudget <= Money.MAX, "bargainDailyBudget must be ≥ 0");
		require(c.bargainHypeHeadroom >= 1 && c.bargainHypeHeadroom <= 20, "bargainHypeHeadroom must be 1–20");
		require(c.refillIntervalSeconds >= 1 && c.refillIntervalSeconds <= 24 * 3600, "refillIntervalSeconds must be ≥ 1");
		require(c.refillFastSeconds >= 1 && c.refillFastSeconds <= c.refillIntervalSeconds, "1 ≤ refillFastSeconds ≤ refillIntervalSeconds");
		if (c.bargainNote == null) c.bargainNote = "";
		require(c.targetRerollHours >= 1 && c.targetRerollHours <= 24 * 7, "targetRerollHours must be 1–168");
		if (c.valueOverrides == null) c.valueOverrides = new LinkedHashMap<>();
		// Unknown ids stay in the map: this file can be written back, and the owning mod may not be initialized yet.
		c.valueOverrides.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null || e.getValue() <= 0 || e.getValue() > Money.MAX);
		if (c.enchantmentValues == null) c.enchantmentValues = new LinkedHashMap<>();
		c.enchantmentValues.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null || e.getValue().isEmpty()
			|| e.getValue().stream().anyMatch(v -> v == null || Math.abs(v) > Money.MAX));
		if (c.traderNames == null) c.traderNames = new ArrayList<>();
		c.traderNames.removeIf(n -> n == null || n.isBlank());
		if (c.traderNames.isEmpty()) c.traderNames.add(TRADER_NAMES.get(0));
		if (c.pool == null) c.pool = new ArrayList<>();
		int stripped = 0;
		for (PoolEntry p : c.pool) {
			if (p == null || p.items == null || p.items.stream().noneMatch(MarketConfig::neverSold)) continue;
			List<String> kept = new ArrayList<>(p.items);
			kept.removeIf(MarketConfig::neverSold);
			p.items = kept;
			stripped++;
		}
		if (stripped > 0) SmpEconomy.LOG.warn("[SMPEco] market: {} trader pool entries with emerald/emerald block/ore: NPC sellers never "
			+ "sell these (villager currency), removed from the pool", stripped);
		c.pool.removeIf(p -> p == null || p.items == null || p.items.isEmpty() || !(p.weight > 0) || p.min < 1 || p.max < p.min || p.max > 64);
		List<String> unknownItems = new ArrayList<>();
		for (String id : c.valueOverrides.keySet()) if (Items.byId(id).isEmpty()) unknownItems.add(id);
		for (PoolEntry p : c.pool) for (String id : p.items) if (Items.byId(id).isEmpty()) unknownItems.add(id);
		UnknownItems.report("market.json", unknownItems);
		// Only after all checks, an invalid file does not count as migrated. Also write back when only the version rose, otherwise
		// the migration would run on every start and translate an old name that was kept on purpose.
		if (!migrated.isEmpty()) SmpEconomy.LOG.warn("[SMPEco] market.json: old defaults adjusted once: {}", String.join(", ", migrated));
		if (versionRaised) migratedUnsaved = true;
	}

	private static void require(boolean ok, String message) {
		if (!ok) throw new IllegalArgumentException(message);
	}

	private static Map<String, Long> defaultOverrides() {
		Map<String, Long> m = new LinkedHashMap<>();
		m.put("minecraft:totem_of_undying", 150_000L);
		m.put("minecraft:elytra", 2_000_000L);
		m.put("minecraft:enchanted_golden_apple", 300_000L);
		m.put("minecraft:heart_of_the_sea", 150_000L);
		m.put("minecraft:nether_star", 400_000L);
		m.put("minecraft:trident", 150_000L);
		m.put("minecraft:name_tag", 1_500L);
		m.put("minecraft:saddle", 2_500L);
		m.put("minecraft:echo_shard", 5_000L);
		m.put("minecraft:dragon_breath", 1_500L);
		for (String disc : DISCS) m.put(disc, 40_000L);
		return m;
	}


	private static Map<String, List<Long>> defaultEnchantments() {
		Map<String, List<Long>> m = new LinkedHashMap<>();
		m.put("minecraft:mending", List.of(300_000L));
		m.put("minecraft:unbreaking", List.of(20_000L, 50_000L, 120_000L));
		m.put("minecraft:efficiency", List.of(10_000L, 25_000L, 50_000L, 90_000L, 150_000L));
		m.put("minecraft:protection", List.of(15_000L, 35_000L, 70_000L, 140_000L));
		m.put("minecraft:sharpness", List.of(15_000L, 35_000L, 60_000L, 100_000L, 150_000L));
		m.put("minecraft:fortune", List.of(40_000L, 100_000L, 200_000L));
		m.put("minecraft:silk_touch", List.of(80_000L));
		m.put("minecraft:looting", List.of(30_000L, 80_000L, 200_000L));
		m.put("minecraft:feather_falling", List.of(10_000L, 25_000L, 50_000L, 100_000L));
		m.put("minecraft:power", List.of(10_000L, 25_000L, 50_000L, 90_000L, 150_000L));
		m.put("minecraft:infinity", List.of(80_000L));
		m.put("minecraft:respiration", List.of(10_000L, 25_000L, 60_000L));
		m.put("minecraft:aqua_affinity", List.of(20_000L));
		m.put("minecraft:depth_strider", List.of(15_000L, 40_000L, 90_000L));
		m.put("minecraft:frost_walker", List.of(20_000L, 50_000L));
		m.put("minecraft:thorns", List.of(10_000L, 25_000L, 50_000L));
		m.put("minecraft:fire_protection", List.of(5_000L, 12_000L, 25_000L, 50_000L));
		m.put("minecraft:blast_protection", List.of(5_000L, 12_000L, 25_000L, 50_000L));
		m.put("minecraft:projectile_protection", List.of(5_000L, 12_000L, 25_000L, 50_000L));
		m.put("minecraft:smite", List.of(5_000L, 10_000L, 20_000L, 35_000L, 50_000L));
		m.put("minecraft:bane_of_arthropods", List.of(5_000L, 10_000L, 20_000L, 35_000L, 50_000L));
		m.put("minecraft:knockback", List.of(5_000L, 12_000L));
		m.put("minecraft:fire_aspect", List.of(20_000L, 50_000L));
		m.put("minecraft:sweeping_edge", List.of(10_000L, 25_000L, 50_000L));
		m.put("minecraft:punch", List.of(5_000L, 12_000L));
		m.put("minecraft:flame", List.of(30_000L));
		m.put("minecraft:luck_of_the_sea", List.of(15_000L, 35_000L, 80_000L));
		m.put("minecraft:lure", List.of(10_000L, 25_000L, 60_000L));
		m.put("minecraft:loyalty", List.of(15_000L, 35_000L, 70_000L));
		m.put("minecraft:impaling", List.of(5_000L, 10_000L, 20_000L, 35_000L, 50_000L));
		m.put("minecraft:riptide", List.of(20_000L, 40_000L, 80_000L));
		m.put("minecraft:channeling", List.of(50_000L));
		m.put("minecraft:multishot", List.of(40_000L));
		m.put("minecraft:quick_charge", List.of(10_000L, 25_000L, 60_000L));
		m.put("minecraft:piercing", List.of(5_000L, 10_000L, 20_000L, 35_000L));
		m.put("minecraft:density", List.of(5_000L, 10_000L, 20_000L, 35_000L, 50_000L));
		m.put("minecraft:breach", List.of(10_000L, 25_000L, 50_000L, 90_000L));
		m.put("minecraft:wind_burst", List.of(30_000L, 70_000L, 150_000L));
		m.put("minecraft:lunge", List.of(10_000L, 25_000L, 50_000L));
		m.put("minecraft:swift_sneak", List.of(50_000L, 100_000L, 200_000L));
		m.put("minecraft:soul_speed", List.of(20_000L, 50_000L, 100_000L));
		m.put("minecraft:binding_curse", List.of(-20_000L));
		m.put("minecraft:vanishing_curse", List.of(-10_000L));
		return m;
	}

	/**
	 * Trader pool: raw materials and blocks, mob drops, food, collector items and enchanted books. Every good needs a market
	 * value (selftest {@code service-and-config}); never emeralds. Deliberately not in the pool:
	 * <ul>
	 *   <li>goods whose floor through villager trades is a multiple of the market value (coal block, leather, string, rabbit's
	 *     foot, turtle scute), since nobody would buy those offers,</li>
	 *   <li>goods without a shop pivot that breaking news shocks (copper and lapis blocks, quartz, amethyst, shulker shell,
	 *     breeze rod) or whose market value depends on a shocked ingredient (ender eye from ender pearl): market reports its
	 *     goods to news ({@link ShockGuard}) and news then never hypes them, so having them here would remove those hypes. An
	 *     admin can still add them; the protection then applies automatically.</li>
	 * </ul>
	 */
	private static List<PoolEntry> defaultPool() {
		List<PoolEntry> p = new ArrayList<>();
		p.add(new PoolEntry(10, 4, 16, "minecraft:diamond"));
		p.add(new PoolEntry(6, 16, 64, "minecraft:iron_ingot"));
		p.add(new PoolEntry(6, 2, 8, "minecraft:iron_block"));
		p.add(new PoolEntry(5, 8, 32, "minecraft:gold_ingot"));
		p.add(new PoolEntry(5, 2, 6, "minecraft:gold_block"));
		p.add(new PoolEntry(3, 8, 32, "minecraft:redstone_block"));
		p.add(new PoolEntry(4, 8, 32, "minecraft:obsidian"));
		p.add(new PoolEntry(2, 16, 64, "minecraft:glowstone_dust"));
		p.add(new PoolEntry(1, 1, 2, "minecraft:diamond_block"));
		p.add(new PoolEntry(1, 1, 3, "minecraft:ancient_debris"));
		p.add(new PoolEntry(3, 1, 4, "minecraft:netherite_scrap"));
		p.add(new PoolEntry(0.3, 1, 1, "minecraft:netherite_ingot"));
		p.add(new PoolEntry(6, 4, 16, "minecraft:ender_pearl"));
		p.add(new PoolEntry(6, 4, 16, "minecraft:blaze_rod"));
		p.add(new PoolEntry(3, 8, 32, "minecraft:slime_ball"));
		p.add(new PoolEntry(4, 8, 32, "minecraft:gunpowder"));
		p.add(new PoolEntry(2, 2, 8, "minecraft:ghast_tear"));
		p.add(new PoolEntry(2, 4, 16, "minecraft:magma_cream"));
		p.add(new PoolEntry(2, 4, 16, "minecraft:phantom_membrane"));
		p.add(new PoolEntry(1, 1, 3, "minecraft:nautilus_shell"));
		p.add(new PoolEntry(1, 1, 2, "minecraft:wither_skeleton_skull"));
		p.add(new PoolEntry(2, 16, 64, "minecraft:prismarine_shard"));
		p.add(new PoolEntry(5, 2, 6, "minecraft:golden_apple"));
		p.add(new PoolEntry(3, 8, 32, "minecraft:golden_carrot"));
		p.add(new PoolEntry(4, 1, 2, "minecraft:name_tag"));
		p.add(new PoolEntry(4, 1, 1, "minecraft:saddle"));
		p.add(new PoolEntry(4, 1, 1, DISCS.toArray(String[]::new)));
		p.add(new PoolEntry(1, 1, 4, "minecraft:echo_shard"));
		p.add(new PoolEntry(0.5, 1, 1, "minecraft:heart_of_the_sea"));
		p.add(new PoolEntry(0.3, 1, 1, "minecraft:trident"));
		p.add(new PoolEntry(1, 1, 1, "minecraft:totem_of_undying"));
		p.add(new PoolEntry(0.3, 1, 1, "minecraft:elytra"));
		p.add(PoolEntry.book(3, "minecraft:mending", 1));
		p.add(PoolEntry.book(4, "minecraft:unbreaking", 3));
		p.add(PoolEntry.book(3, "minecraft:efficiency", 5));
		p.add(PoolEntry.book(4, "minecraft:protection", 4));
		p.add(PoolEntry.book(2, "minecraft:sharpness", 5));
		p.add(PoolEntry.book(2, "minecraft:fortune", 3));
		p.add(PoolEntry.book(2, "minecraft:silk_touch", 1));
		p.add(PoolEntry.book(2, "minecraft:looting", 3));
		p.add(PoolEntry.book(2, "minecraft:feather_falling", 4));
		p.add(PoolEntry.book(2, "minecraft:power", 5));
		p.add(PoolEntry.book(1, "minecraft:infinity", 1));
		p.add(PoolEntry.book(1, "minecraft:flame", 1));
		p.add(PoolEntry.book(1, "minecraft:respiration", 3));
		p.add(PoolEntry.book(1, "minecraft:aqua_affinity", 1));
		p.add(PoolEntry.book(1, "minecraft:depth_strider", 3));
		p.add(PoolEntry.book(1, "minecraft:fire_protection", 4));
		p.add(PoolEntry.book(1, "minecraft:blast_protection", 4));
		p.add(PoolEntry.book(1, "minecraft:projectile_protection", 4));
		p.add(PoolEntry.book(1, "minecraft:thorns", 3));
		p.add(PoolEntry.book(1, "minecraft:sweeping_edge", 3));
		p.add(PoolEntry.book(1, "minecraft:fire_aspect", 2));
		p.add(PoolEntry.book(1, "minecraft:smite", 5));
		p.add(PoolEntry.book(1, "minecraft:luck_of_the_sea", 3));
		p.add(PoolEntry.book(1, "minecraft:lure", 3));
		p.add(PoolEntry.book(0.5, "minecraft:knockback", 2));
		p.add(PoolEntry.book(0.5, "minecraft:punch", 2));
		p.add(PoolEntry.book(0.5, "minecraft:bane_of_arthropods", 5));
		p.add(PoolEntry.book(0.5, "minecraft:loyalty", 3));
		p.add(PoolEntry.book(0.5, "minecraft:riptide", 3));
		p.add(PoolEntry.book(0.5, "minecraft:channeling", 1));
		p.add(PoolEntry.book(0.5, "minecraft:impaling", 5));
		p.add(PoolEntry.book(0.5, "minecraft:multishot", 1));
		p.add(PoolEntry.book(0.5, "minecraft:quick_charge", 3));
		p.add(PoolEntry.book(0.5, "minecraft:piercing", 4));
		p.add(PoolEntry.book(0.5, "minecraft:frost_walker", 2));
		p.add(PoolEntry.book(0.5, "minecraft:soul_speed", 3));
		p.add(PoolEntry.book(0.5, "minecraft:swift_sneak", 3));
		p.add(PoolEntry.book(0.5, "minecraft:breach", 4));
		p.add(PoolEntry.book(0.5, "minecraft:density", 5));
		p.add(PoolEntry.book(0.3, "minecraft:wind_burst", 3));
		return p;
	}

	/** Old default pool including emeralds, found in older market.json files. Only for the migration. */
	static List<PoolEntry> legacyPoolR2() {
		List<PoolEntry> p = legacyPoolR3();
		int totem = p.size() - 2;
		p.add(totem, new PoolEntry(4, 8, 32, "minecraft:emerald"));
		return p;
	}

	/** Old default pool, only for the migration: an unchanged old market.json gets the new pool. */
	static List<PoolEntry> legacyPoolR3() {
		List<PoolEntry> p = new ArrayList<>();
		p.add(new PoolEntry(10, 4, 16, "minecraft:diamond"));
		p.add(new PoolEntry(8, 2, 8, "minecraft:iron_block"));
		p.add(new PoolEntry(6, 2, 6, "minecraft:gold_block"));
		p.add(new PoolEntry(6, 2, 6, "minecraft:golden_apple"));
		p.add(new PoolEntry(8, 4, 16, "minecraft:ender_pearl"));
		p.add(new PoolEntry(8, 4, 16, "minecraft:blaze_rod"));
		p.add(new PoolEntry(4, 1, 2, "minecraft:name_tag"));
		p.add(new PoolEntry(4, 1, 1, "minecraft:saddle"));
		p.add(new PoolEntry(4, 1, 1, DISCS.toArray(String[]::new)));
		p.add(PoolEntry.book(3, "minecraft:mending", 1));
		p.add(PoolEntry.book(4, "minecraft:unbreaking", 3));
		p.add(PoolEntry.book(3, "minecraft:efficiency", 5));
		p.add(PoolEntry.book(4, "minecraft:protection", 4));
		p.add(PoolEntry.book(2, "minecraft:sharpness", 5));
		p.add(PoolEntry.book(2, "minecraft:fortune", 3));
		p.add(PoolEntry.book(2, "minecraft:silk_touch", 1));
		p.add(new PoolEntry(3, 1, 4, "minecraft:netherite_scrap"));
		p.add(new PoolEntry(1, 1, 1, "minecraft:totem_of_undying"));
		p.add(new PoolEntry(0.3, 1, 1, "minecraft:elytra"));
		return p;
	}
}
