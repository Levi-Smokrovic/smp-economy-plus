package dev.smpeconomy.features.events;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import net.minecraft.world.item.Item;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.BasePrices;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.PriceGuard;

/**
 * {@code config/smpeconomy/events.json}. Read through {@code EventsFeature.CONFIG.get()} and never cached,
 * because {@code /smpeco reload} replaces the object.
 */
public final class EventsConfig {

	/** Admins can still use /event start. */
	public boolean randomEvents = true;
	/** Pause between events (end to next start), random in [min, max]. */
	public int minIntervalMinutes = 120;
	public int maxIntervalMinutes = 240;
	/** Random in [min, max]. */
	public int minDurationMinutes = 20;
	public int maxDurationMinutes = 30;
	/** Below this many online players a due event waits. */
	public int minPlayersOnline = 1;
	/** No automatic starts in [quietFromHour, quietToHour) (server timezone); equal means no quiet time. */
	public int quietFromHour = 0;
	public int quietToHour = 0;
	/** Market Day on /sell (category), Gold Rush on job payouts, Lucky Hour on chat game prizes. */
	public double marketDayMultiplier = DEFAULT_MARKET_DAY_MULTIPLIER;
	public double goldRushMultiplier = 2.0;
	public double luckyHourMultiplier = 2.0;
	/** 0 = never automatically. */
	public Map<String, Integer> weights = defaultWeights();
	/**
	 * Only shop items (with a pivot) whose base sell price × Market Day × highest rank bonus stays within the
	 * pivot take effect, see {@link #marketDayProblem}. Others are skipped with a log warning; this also
	 * depends on prices.json and shop.json, so it is not a load error. Categories with no eligible items are
	 * never picked.
	 */
	public List<Category> marketDayCategories = defaultCategories();
	/** Id as in the jobs feature, plus aliases for matching. */
	public List<Job> goldRushJobs = defaultJobs();

	/** Ascending by total sales (/sell); the first must start at 0. Bonus in percent (max 20). */
	public List<Rank> traderRanks = defaultRanks();

	public boolean lottoEnabled = true;
	/** In cents; the money goes to the treasury. */
	public long lottoTicketPrice = 10_000;
	public int lottoMaxTicketsPerPlayer = 50;
	/** Daily draw (server timezone), format HH:mm. */
	public String lottoDrawTime = "20:00";
	/** Legacy pot model: ignored, only stays in the file. */
	public double lottoFeePercent = 10.0;
	/** Reminder this many minutes before the draw (0 = off). */
	public int lottoReminderMinutes = 10;
	/** Numbered #1 to #N. Applies from the next draw, or right away while no ticket is sold. */
	public int lottoTicketsPerDraw = 1000;
	/** In cents. Not won: next jackpot = jackpot + base. Won: back to base. */
	public long lottoJackpotBase = 7_200_000;
	/** In cents. These never roll over. */
	public List<Long> lottoPrizes = defaultPrizes();
	/**
	 * Does the jackpot grow on a day with no tickets sold? Default false: the draw is cancelled and the
	 * jackpot stays; otherwise it would grow daily on an empty server.
	 */
	public boolean lottoRolloverWithoutTickets = false;
	/**
	 * In cents, 0 = no limit. A rollover raises the jackpot at most to here; a jackpot already higher (limit
	 * lowered later) stays.
	 */
	public long lottoJackpotMax = 0;
	/**
	 * In cents. Lottery prizes never take the treasury below this (the rest stays owed and is paid later),
	 * and tickets only sell while the treasury covers jackpot + all extra prizes + owed prizes + this
	 * reserve. That leaves money for NPC buys, orders, rewards and the casino.
	 */
	public long lottoTreasuryReserve = 2_500_000;
	/**
	 * Test servers only: allows {@code /lottery draw <numbers>}. Such draws are marked as admin test draws in
	 * chat, history and GUI.
	 */
	public boolean lottoForcedDraws = false;

	public static final class Category {
		public String id = "";
		public String name = "";
		public String icon = "minecraft:chest";
		public List<String> items = new ArrayList<>();

		public Category() {}

		Category(String id, String name, String icon, String... items) {
			this.id = id;
			this.name = name;
			this.icon = icon;
			for (String s : items) this.items.add(s.contains(":") ? s : "minecraft:" + s);
		}
	}

	public static final class Job {
		public String id = "";
		public String name = "";
		public String icon = "minecraft:iron_pickaxe";
		public List<String> aliases = new ArrayList<>();

		public Job() {}

		Job(String id, String name, String icon, String... aliases) {
			this.id = id;
			this.name = name;
			this.icon = "minecraft:" + icon;
			this.aliases.addAll(List.of(aliases));
		}
	}

	public static final class Rank {
		public String id = "";
		public String name = "";
		/** E.g. "§6". */
		public String color = "§7";
		/** From this total sales amount (cents). */
		public long minSales;
		/** 0 to 20 percent. */
		public double bonusPercent;

		public Rank() {}

		Rank(String id, String name, String color, long minSales, double bonusPercent) {
			this.id = id;
			this.name = name;
			this.color = color;
			this.minSales = minSales;
			this.bonusPercent = bonusPercent;
		}
	}

	/** The numbers are stored in lotto.json. */
	public static final int MAX_LOTTO_TICKETS = 100_000;
	/** Limited by space in GUI and chat. */
	public static final int MAX_LOTTO_PRIZES = 9;
	/** Limit for base jackpot and single prizes; rollover adds, saturating at {@link Money#MAX}. */
	public static final long MAX_LOTTO_AMOUNT = Money.MAX / 1_000_000;

	public int lottoWinningNumbers() {
		return 1 + (lottoPrizes == null ? 0 : lottoPrizes.size());
	}

	/** Stays well below the 25 % gap between sell and shop price (PriceGuard). */
	public static final double MAX_RANK_BONUS_PERCENT = 20.0;
	/** Also used to select the default categories. */
	static final double DEFAULT_MARKET_DAY_MULTIPLIER = 1.5;

	/**
	 * Counts each reload of prices.json and shop.json ({@code BasePrices.onReload}), so resolved categories
	 * get rechecked.
	 */
	private static int priceGeneration;

	private transient Map<String, Set<Item>> resolvedCategories;
	private transient int resolvedGeneration = -1;
	private transient LocalTime drawTime;

	static void onPricesReloaded() {
		priceGeneration++;
	}

	/**
	 * Items of a category currently eligible ({@link #marketDayProblem}), skipping unknown and ineligible
	 * ids, rechecked after each price reload. Empty if the category does not exist or no item is eligible.
	 */
	public Set<Item> categoryItems(String categoryId) {
		if (resolvedCategories == null || resolvedGeneration != priceGeneration) resolve();
		return resolvedCategories.getOrDefault(categoryId, Set.of());
	}

	/** Factor of the highest configured rank (e.g. 1.12), capped at {@link #MAX_RANK_BONUS_PERCENT}. */
	public double maxRankMultiplier() {
		return 1.0 + maxBonusPercent(traderRanks) / 100.0;
	}

	private static double maxBonusPercent(List<Rank> ranks) {
		double max = 0;
		if (ranks == null) return max;
		for (Rank r : ranks) {
			if (r != null && !Double.isNaN(r.bonusPercent)) max = Math.max(max, Math.min(MAX_RANK_BONUS_PERCENT, r.bonusPercent));
		}
		return max;
	}

	/**
	 * Why {@code item} must not sell higher on Market Day (empty = eligible). Only shop items qualify: their
	 * pivot ({@link PriceGuard#pivotPrice}) guards "buy from the system, sell back later" over time too.
	 * Items without a pivot (diamonds, ingots, raw ores) are only protected at one moment, so Market Day
	 * would make "buy an NPC offer in a crash or at low market value, /sell on Market Day" profitable. Base ×
	 * Market Day × highest rank factor must also stay within the pivot, or PriceGuard caps it and the
	 * announced bonus would not be paid.
	 */
	static Optional<String> marketDayProblem(Item item, double marketDayMultiplier, double maxRankMultiplier) {
		long pivot = PriceGuard.pivotPrice(item);
		if (pivot < 0) return Optional.of("not a shop item (no pivot, so no protection against arbitrage over time)");
		OptionalLong base = BasePrices.sellPrice(item);
		if (base.isEmpty()) return Optional.of("not sellable (no entry in prices.json)");
		long boosted = PriceGuard.computeSell(base.getAsLong(), 1.0, marketDayMultiplier * maxRankMultiplier, -1);
		if (boosted > pivot) {
			return Optional.of("Market Day × highest rank bonus = " + Money.format(boosted) + " above the price limit " + Money.format(pivot));
		}
		return Optional.empty();
	}

	public Optional<Category> category(String id) {
		for (Category c : marketDayCategories) {
			if (c.id.equals(id)) return Optional.of(c);
		}
		return Optional.empty();
	}

	public Optional<Job> job(String id) {
		for (Job j : goldRushJobs) {
			if (j.id.equals(id)) return Optional.of(j);
		}
		return Optional.empty();
	}

	/** Ignores case and surrounding whitespace. */
	public static boolean jobMatches(Job job, String jobId) {
		if (job == null || jobId == null) return false;
		String n = normalize(jobId);
		if (n.isEmpty()) return false;
		if (normalize(job.id).equals(n) || normalize(job.name).equals(n)) return true;
		for (String a : job.aliases) {
			if (normalize(a).equals(n)) return true;
		}
		return false;
	}

	static String normalize(String s) {
		if (s == null) return "";
		return s.trim().toLowerCase(Locale.ROOT);
	}

	public LocalTime drawTime() {
		if (drawTime == null) drawTime = LocalTime.parse(lottoDrawTime.trim());
		return drawTime;
	}

	public int weight(EventType type) {
		Integer w = weights == null ? null : weights.get(type.id());
		return w == null ? 1 : Math.max(0, w);
	}

	private void resolve() {
		Map<String, Set<Item>> map = new LinkedHashMap<>();
		double rankMultiplier = maxRankMultiplier();
		for (Category c : marketDayCategories) {
			Set<Item> set = new LinkedHashSet<>();
			for (String id : c.items) {
				Optional<Item> item = id == null ? Optional.empty() : Items.byId(id);
				if (item.isEmpty()) {
					SmpEconomy.LOG.warn("[SMPEco] events.json: unknown item {} in Market Day category {}", id, c.id);
					continue;
				}
				// Depends on prices.json and shop.json: warn and skip, never discard the whole config
				Optional<String> problem = marketDayProblem(item.get(), marketDayMultiplier, rankMultiplier);
				if (problem.isPresent()) {
					SmpEconomy.LOG.warn("[SMPEco] events.json: {} in Market Day category {} ignored: {}", id, c.id, problem.get());
					continue;
				}
				set.add(item.get());
			}
			if (set.isEmpty()) SmpEconomy.LOG.warn("[SMPEco] events.json: Market Day category {} has no eligible items right now and is skipped", c.id);
			map.put(c.id, Set.copyOf(set));
		}
		resolvedCategories = map;
		resolvedGeneration = priceGeneration;
	}

	/**
	 * Throws only on structural errors (ConfigManager then keeps the old state, or the defaults at start).
	 * Market Day items that do not qualify because of prices.json or shop.json are no error: {@link #resolve}
	 * skips them with a warning, so a price change never tips over other settings.
	 */
	public static void validate(EventsConfig c) {
		if (c.weights == null) c.weights = defaultWeights();
		if (c.marketDayCategories == null) c.marketDayCategories = new ArrayList<>();
		if (c.goldRushJobs == null) c.goldRushJobs = new ArrayList<>();
		if (c.traderRanks == null || c.traderRanks.isEmpty()) throw new IllegalArgumentException("traderRanks must not be empty");
		require(c.minIntervalMinutes >= 1 && c.minIntervalMinutes <= c.maxIntervalMinutes, "minIntervalMinutes/maxIntervalMinutes invalid");
		require(c.maxIntervalMinutes <= 7 * 24 * 60, "maxIntervalMinutes too large");
		require(c.minDurationMinutes >= 1 && c.minDurationMinutes <= c.maxDurationMinutes, "minDurationMinutes/maxDurationMinutes invalid");
		require(c.maxDurationMinutes <= 24 * 60, "maxDurationMinutes too large");
		require(c.minPlayersOnline >= 0, "minPlayersOnline < 0");
		require(c.quietFromHour >= 0 && c.quietFromHour <= 23 && c.quietToHour >= 0 && c.quietToHour <= 23, "quietFromHour/quietToHour must be 0–23");
		requireFactor(c.marketDayMultiplier, "marketDayMultiplier");
		requireFactor(c.goldRushMultiplier, "goldRushMultiplier");
		requireFactor(c.luckyHourMultiplier, "luckyHourMultiplier");
		Set<String> ids = new HashSet<>();
		for (Job job : c.goldRushJobs) {
			require(job != null && job.id != null && !job.id.isBlank(), "job ID missing");
			require(ids.add(normalize(job.id)), "duplicate job ID: " + job.id);
			if (job.name == null || job.name.isBlank()) job.name = job.id;
			if (job.aliases == null) job.aliases = new ArrayList<>();
		}
		long prev = -1;
		ids.clear();
		for (int i = 0; i < c.traderRanks.size(); i++) {
			Rank r = c.traderRanks.get(i);
			require(r != null && r.id != null && r.id.matches("[a-z0-9_]+"), "rank ID must be [a-z0-9_]+");
			require(ids.add(r.id), "duplicate rank ID: " + r.id);
			require(i > 0 || r.minSales == 0, "the first rank must start at minSales 0");
			require(r.minSales > prev && r.minSales <= Money.MAX, "ranks must be strictly ascending by minSales");
			require(r.bonusPercent >= 0 && r.bonusPercent <= MAX_RANK_BONUS_PERCENT && !Double.isNaN(r.bonusPercent),
				"bonusPercent 0–" + MAX_RANK_BONUS_PERCENT);
			if (r.name == null || r.name.isBlank()) r.name = r.id;
			if (r.color == null) r.color = "§7";
			prev = r.minSales;
		}
		require(c.traderRanks.size() <= 7, "at most 7 ranks (room in the rank overview)");
		// Market Day categories: only check structure (id, duplicates, at least one existing item). Whether
		// an item is eligible right now depends on prices.json and shop.json; resolve() below warns and skips
		// it.
		ids.clear();
		for (Category cat : c.marketDayCategories) {
			require(cat != null && cat.id != null && cat.id.matches("[a-z0-9_]+"), "category ID must be [a-z0-9_]+");
			require(ids.add(cat.id), "duplicate category ID: " + cat.id);
			if (cat.name == null || cat.name.isBlank()) cat.name = cat.id;
			if (cat.items == null) cat.items = new ArrayList<>();
			require(cat.items.stream().anyMatch(id -> id != null && Items.byId(id).isPresent()), "category " + cat.id + " has no valid items");
		}
		// lottoFeePercent (legacy pot) is ignored, so not checked
		if (c.lottoPrizes == null) c.lottoPrizes = defaultPrizes();
		require(c.lottoPrizes.size() <= MAX_LOTTO_PRIZES, "lottoPrizes: at most " + MAX_LOTTO_PRIZES + " extra prizes");
		for (Long p : c.lottoPrizes) require(p != null && p > 0 && p <= MAX_LOTTO_AMOUNT, "lottoPrizes: every prize must be > 0 and <= " + MAX_LOTTO_AMOUNT);
		require(c.lottoTicketsPerDraw >= c.lottoWinningNumbers() && c.lottoTicketsPerDraw <= MAX_LOTTO_TICKETS,
			"lottoTicketsPerDraw must be " + c.lottoWinningNumbers() + "–" + MAX_LOTTO_TICKETS + " (at least one ticket per winning number)");
		require(c.lottoMaxTicketsPerPlayer >= 1 && c.lottoMaxTicketsPerPlayer <= c.lottoTicketsPerDraw, "lottoMaxTicketsPerPlayer must be 1–lottoTicketsPerDraw");
		require(c.lottoTicketPrice > 0 && c.lottoTicketPrice <= Money.MAX / MAX_LOTTO_TICKETS, "lottoTicketPrice invalid");
		require(c.lottoJackpotBase > 0 && c.lottoJackpotBase <= MAX_LOTTO_AMOUNT, "lottoJackpotBase must be > 0 and <= " + MAX_LOTTO_AMOUNT);
		require(c.lottoReminderMinutes >= 0 && c.lottoReminderMinutes <= 600, "lottoReminderMinutes must be 0–600");
		require(c.lottoJackpotMax == 0 || (c.lottoJackpotMax >= c.lottoJackpotBase && c.lottoJackpotMax <= Money.MAX),
			"lottoJackpotMax must be 0 (no limit) or between lottoJackpotBase and " + Money.MAX);
		require(c.lottoTreasuryReserve >= 0 && c.lottoTreasuryReserve <= Money.MAX, "lottoTreasuryReserve must be 0–" + Money.MAX);
		try {
			c.drawTime = LocalTime.parse(c.lottoDrawTime == null ? "" : c.lottoDrawTime.trim());
		} catch (DateTimeParseException e) {
			throw new IllegalArgumentException("lottoDrawTime must be HH:mm");
		}
		// Resolve now: warnings about unknown or ineligible items show on load, not on the first Market Day
		c.resolve();
	}

	private static void require(boolean ok, String message) {
		if (!ok) throw new IllegalArgumentException(message);
	}

	private static void requireFactor(double v, String name) {
		require(!Double.isNaN(v) && v >= 1.0 && v <= 5.0, name + " must be between 1.0 and 5.0");
	}

	static List<Long> defaultPrizes() {
		return new ArrayList<>(List.of(1_000_000L, 500_000L, 100_000L));
	}

	static Map<String, Integer> defaultWeights() {
		Map<String, Integer> m = new LinkedHashMap<>();
		for (EventType t : EventType.values()) m.put(t.id(), 1);
		return m;
	}

	/**
	 * Only shop items (pivot) that stay below the pivot even with Market Day ×1.5 and the highest allowed
	 * rank bonus ({@link #MAX_RANK_BONUS_PERCENT}), which with the shipped prices means all. If an admin
	 * changed prices.json or shop.json, ineligible items silently drop out (empty categories entirely), so
	 * the defaults stay valid and {@link #validate} never fails a server start on them.
	 */
	static List<Category> defaultCategories() {
		List<Category> list = new ArrayList<>();
		list.add(new Category("mining", "Mining & Stone", "minecraft:cobblestone",
			"cobblestone", "cobbled_deepslate", "stone", "deepslate", "andesite", "diorite", "granite", "tuff", "blackstone", "basalt",
			"netherrack", "end_stone", "obsidian", "redstone", "glowstone", "sand", "red_sand", "gravel", "clay", "soul_sand"));
		list.add(new Category("crops", "Crops", "minecraft:carrot",
			"carrot", "potato", "sugar_cane", "cactus", "bamboo", "kelp", "cocoa_beans", "nether_wart", "sweet_berries",
			"glow_berries", "melon_slice", "apple", "red_mushroom", "brown_mushroom", "crimson_fungus", "warped_fungus"));
		list.add(new Category("mobdrops", "Mob Drops", "minecraft:bone",
			"rotten_flesh", "bone", "arrow", "string", "spider_eye", "gunpowder", "ender_pearl", "blaze_rod", "slime_ball",
			"magma_cream", "ghast_tear", "phantom_membrane", "leather", "feather", "rabbit_hide", "egg", "ink_sac", "glow_ink_sac",
			"prismarine_shard", "prismarine_crystals"));
		list.add(new Category("wood", "Wood", "minecraft:oak_log",
			"oak_log", "spruce_log", "birch_log", "jungle_log", "acacia_log", "dark_oak_log", "mangrove_log", "cherry_log",
			"pale_oak_log", "crimson_stem", "warped_stem"));
		list.add(new Category("cooked_food", "Cooked Food", "minecraft:cooked_beef",
			"cooked_beef", "cooked_porkchop", "cooked_chicken", "cooked_mutton", "cooked_salmon", "pumpkin_pie", "cookie"));
		double rankMultiplier = 1.0 + MAX_RANK_BONUS_PERCENT / 100.0;
		List<Category> eligible = new ArrayList<>();
		for (Category c : list) {
			c.items.removeIf(id -> Items.byId(id).map(i -> marketDayProblem(i, DEFAULT_MARKET_DAY_MULTIPLIER, rankMultiplier).isPresent()).orElse(true));
			if (!c.items.isEmpty()) eligible.add(c);
		}
		return eligible;
	}

	static List<Job> defaultJobs() {
		List<Job> list = new ArrayList<>();
		list.add(new Job("miner", "Miner", "iron_pickaxe", "mining"));
		list.add(new Job("lumberjack", "Lumberjack", "iron_axe", "woodcutter", "woodcutting"));
		list.add(new Job("farmer", "Farmer", "iron_hoe", "farming"));
		list.add(new Job("hunter", "Hunter", "iron_sword", "hunting"));
		list.add(new Job("fisher", "Fisher", "fishing_rod", "fisherman", "fishing"));
		list.add(new Job("digger", "Digger", "iron_shovel", "digging", "excavator"));
		return list;
	}

	static List<Rank> defaultRanks() {
		List<Rank> list = new ArrayList<>();
		list.add(new Rank("apprentice", "Apprentice", "§7", 0, 0));
		list.add(new Rank("trader", "Trader", "§a", 1_000_000, 2)); // $10,000
		list.add(new Rank("merchant", "Merchant", "§b", 5_000_000, 5)); // $50,000
		list.add(new Rank("magnate", "Magnate", "§d", 25_000_000, 8)); // $250,000
		list.add(new Rank("tycoon", "Tycoon", "§6", 100_000_000, 12)); // $1,000,000
		return list;
	}
}
