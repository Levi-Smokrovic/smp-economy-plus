package dev.smpeconomy.features.orders;

import java.util.ArrayList;
import java.util.List;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.UnknownItems;

/** Validated on load; an invalid file keeps the old values active. */
public final class OrdersConfig {
	public int maxOrdersPerPlayer = 10;
	public int maxAmountPerOrder = 100_000;
	/** Percent on each delivery to a player order; paid by the deliverer, goes to the treasury. */
	public double deliveryFeePercent = 3.0;
	/** After this many days the rest is refunded. */
	public int expiryDays = 7;
	/** Same as {@code allowCreative} for /sell and /ah; intended for testing. */
	public boolean allowCreative = false;

	/** NPC orders on or off (board and bulk orders). */
	public boolean npcEnabled = true;

	/** The many dynamic NPC orders behind the player orders. */
	public boolean boardEnabled = true;
	/**
	 * Target board size (about 9 to 10 pages of 45), limited by the number of tradable items. Common items can appear
	 * twice.
	 */
	public int boardSize = 420;
	/**
	 * Base demand of an NPC buyer (factor on the /sell reference), rolled in this range at creation. At most {@link
	 * NpcBoard#HARD_MAX_DEMAND} (1.6), which stays below the shop's recipe guard (1.68) so that buying ingredients,
	 * crafting and delivering never profits.
	 */
	public double boardDemandMin = 1.05;
	public double boardDemandMax = 1.35;
	/**
	 * Supply: every 64 items delivered lower an item's demand by this much (absolute, 0.02 = 2 points). At 0.02 an
	 * item's premium (demand up to 1.35) is gone after about 17 stacks. The hard limit is {@link
	 * #boardDailyBonusPerPlayer}.
	 */
	public double boardSupplyDropPerStack = 0.02;
	/** Below 1.0 the board pays less than /sell and the item counts as saturated. */
	public double boardDemandFloor = 0.95;
	/** Half-life in hours of supply pressure. */
	public double boardRecoveryHalfLifeHours = 3.0;
	/** In factor points; 1.0 is 50 stacks at 0.02. More deliveries don't extend the recovery further. */
	public double boardMaxPressure = 1.0;
	/**
	 * Bonus above a player's own /sell price per player and day, in cents (server time zone). This is the hard limit
	 * on new money from the board: every source you can buy from costs at least /sell, so any premium over /sell
	 * comes out of this bonus. Once used up, the board pays at most the /sell price for the rest of the day. 0 means
	 * no bonus.
	 */
	public long boardDailyBonusPerPlayer = 150_000;
	/**
	 * Amount of a board order: item count log-uniform in [boardMinAmount, boardMaxAmount] and order value (cents at
	 * the /sell reference, common items × 2) log-uniform in [boardOrderMinValue, boardOrderMaxValue]; the smaller
	 * wins, so expensive items go below boardMinAmount, with a minimum of 1.
	 */
	public int boardMinAmount = 64;
	public int boardMaxAmount = 2304;
	public long boardOrderMinValue = 20_000;
	public long boardOrderMaxValue = 400_000;
	/** Hours, random within the range. */
	public int boardMinHours = 6;
	public int boardMaxHours = 36;
	/** New orders per check (every 30 s). A board less than half full is refilled completely. */
	public int boardRefillPerTick = 6;
	/** Minutes before an item comes back after a fully delivered order. */
	public int boardCooldownMinutes = 30;
	/** Ores, crops, wood, mob drops: up to 2 NPC buyers at once and double order value. */
	public List<String> boardCommonItems = new ArrayList<>(List.of(
		"minecraft:coal", "minecraft:raw_iron", "minecraft:iron_ingot", "minecraft:raw_copper", "minecraft:copper_ingot",
		"minecraft:raw_gold", "minecraft:gold_ingot", "minecraft:redstone", "minecraft:lapis_lazuli", "minecraft:quartz",
		"minecraft:amethyst_shard", "minecraft:diamond", "minecraft:emerald",
		"minecraft:wheat", "minecraft:carrot", "minecraft:potato", "minecraft:beetroot", "minecraft:sugar_cane",
		"minecraft:pumpkin", "minecraft:melon_slice", "minecraft:cactus", "minecraft:cocoa_beans", "minecraft:nether_wart",
		"minecraft:bamboo", "minecraft:kelp",
		"minecraft:oak_log", "minecraft:spruce_log", "minecraft:birch_log", "minecraft:jungle_log", "minecraft:acacia_log",
		"minecraft:dark_oak_log", "minecraft:mangrove_log", "minecraft:cherry_log",
		"minecraft:rotten_flesh", "minecraft:bone", "minecraft:string", "minecraft:spider_eye", "minecraft:gunpowder",
		"minecraft:slime_ball", "minecraft:ender_pearl", "minecraft:blaze_rod", "minecraft:leather", "minecraft:feather",
		"minecraft:ink_sac", "minecraft:phantom_membrane", "minecraft:cobblestone", "minecraft:stone", "minecraft:sand"));
	public List<String> boardExclude = new ArrayList<>();

	/**
	 * Conversions {@code in × from → out × to}, such as storage blocks. An NPC order for {@code to} never pays enough
	 * per item that buying {@code from} from the system (PriceGuard.minSystemSalePrice floor, valid for every item
	 * with a market value, or the shop price), converting and delivering yields a profit. Checked on each delivery,
	 * chains up to depth 3. Defaults only cover recipes with consistent base prices (block = 9 × item), so nothing
	 * gets permanently cheaper. If you let the system sell other sources (nuggets, raw ore), add the conversion, e.g.
	 * {@code {"from":"minecraft:raw_iron","in":1,"to":"minecraft:iron_ingot","out":1}} for smelting.
	 */
	public List<Conversion> npcConversions = defaultConversions();

	/** Percent of Treasury.maxSpendPerHour() the escrow may use, and never more than the current budget. */
	public double bulkBudgetPercent = 40.0;
	/** Only when the budget allows at least this many items, or the requested amount if smaller. */
	public int bulkMinAmount = 16;
	public int maxBulkOrders = 3;

	public void validate() {
		require(maxOrdersPerPlayer >= 1 && maxOrdersPerPlayer <= 1000, "maxOrdersPerPlayer 1..1000");
		require(maxAmountPerOrder >= 1 && maxAmountPerOrder <= 10_000_000, "maxAmountPerOrder 1..10000000");
		require(deliveryFeePercent >= 0 && deliveryFeePercent <= 50, "deliveryFeePercent 0..50");
		require(expiryDays >= 1 && expiryDays <= 365, "expiryDays 1..365");
		require(boardSize >= 0 && boardSize <= 2000, "boardSize 0..2000");
		require(boardDemandMin > 0 && boardDemandMax >= boardDemandMin && boardDemandMax <= NpcBoard.HARD_MAX_DEMAND,
			"0 < boardDemandMin <= boardDemandMax <= " + NpcBoard.HARD_MAX_DEMAND);
		require(boardDemandFloor > 0 && boardDemandFloor <= boardDemandMin, "0 < boardDemandFloor <= boardDemandMin");
		require(boardSupplyDropPerStack >= 0 && boardSupplyDropPerStack <= 1, "boardSupplyDropPerStack 0..1");
		require(boardRecoveryHalfLifeHours > 0 && boardRecoveryHalfLifeHours <= 24 * 30, "boardRecoveryHalfLifeHours 0..720");
		require(boardMaxPressure >= 0 && boardMaxPressure <= 10, "boardMaxPressure 0..10");
		require(boardDailyBonusPerPlayer >= 0 && boardDailyBonusPerPlayer <= 1_000_000_000_000L, "boardDailyBonusPerPlayer 0..10^12");
		require(boardOrderMinValue >= 1 && boardOrderMaxValue >= boardOrderMinValue && boardOrderMaxValue <= 1_000_000_000_000L,
			"1 <= boardOrderMinValue <= boardOrderMaxValue <= 10^12");
		require(boardMinAmount >= 1 && boardMaxAmount >= boardMinAmount && boardMaxAmount <= 100_000, "1 <= boardMinAmount <= boardMaxAmount <= 100000");
		require(boardMinHours >= 1 && boardMaxHours >= boardMinHours && boardMaxHours <= 24 * 14, "1 <= boardMinHours <= boardMaxHours <= 336");
		require(boardRefillPerTick >= 1 && boardRefillPerTick <= 2000, "boardRefillPerTick 1..2000");
		require(boardCooldownMinutes >= 0 && boardCooldownMinutes <= 24 * 60, "boardCooldownMinutes 0..1440");
		require(boardCommonItems != null && boardExclude != null, "boardCommonItems/boardExclude missing");
		require(!boardCommonItems.contains(null) && !boardExclude.contains(null), "boardCommonItems/boardExclude: empty entry");
		List<String> unknown = new ArrayList<>();
		for (String id : boardCommonItems) if (Items.byId(id).isEmpty()) unknown.add(id);
		for (String id : boardExclude) if (Items.byId(id).isEmpty()) unknown.add(id);
		require(npcConversions != null, "npcConversions missing");
		for (Conversion c : npcConversions) {
			require(c != null && c.from != null && c.to != null, "npcConversions: empty entry");
			if (Items.byId(c.from).isEmpty()) unknown.add(c.from);
			if (Items.byId(c.to).isEmpty()) unknown.add(c.to);
			require(!c.from.equals(c.to), "npcConversions: from == to (" + c.from + ")");
			require(c.in >= 1 && c.in <= 64 && c.out >= 1 && c.out <= 64, "npcConversions: in/out 1..64 (" + c.from + " → " + c.to + ")");
		}
		UnknownItems.report("orders.json", unknown);
		require(bulkBudgetPercent > 0 && bulkBudgetPercent <= 100, "bulkBudgetPercent 0..100");
		require(bulkMinAmount >= 1, "bulkMinAmount >= 1");
		require(maxBulkOrders >= 0 && maxBulkOrders <= 20, "maxBulkOrders 0..20");
	}

	private static void require(boolean ok, String message) {
		if (!ok) throw new IllegalArgumentException("orders.json: " + message);
	}

	public static final class Conversion {
		public String from = "";
		public int in = 1;
		public String to = "";
		public int out = 1;

		public Conversion() {}

		Conversion(String from, int in, String to, int out) {
			this.from = from;
			this.in = in;
			this.to = to;
			this.out = out;
		}
	}

	/**
	 * Reversible vanilla recipes (storage block to 9 items) in both directions, plus waxed copper blocks and melons
	 * (mining with fortune gives at most 9 slices). Base prices are consistent here, so the cap only applies when
	 * drift or events pull the two forms apart. Nuggets are deliberately missing: 9 × nugget price &lt; ingot price
	 * would make ingot orders permanently cheaper, and the system sells no nuggets by default.
	 */
	static List<Conversion> defaultConversions() {
		List<Conversion> list = new ArrayList<>();
		String[][] nine = {
			{"coal", "coal_block"}, {"raw_iron", "raw_iron_block"}, {"iron_ingot", "iron_block"}, {"raw_copper", "raw_copper_block"},
			{"copper_ingot", "copper_block"}, {"raw_gold", "raw_gold_block"}, {"gold_ingot", "gold_block"}, {"redstone", "redstone_block"},
			{"lapis_lazuli", "lapis_block"}, {"emerald", "emerald_block"}, {"diamond", "diamond_block"}, {"netherite_ingot", "netherite_block"},
			{"wheat", "hay_block"}, {"slime_ball", "slime_block"}, {"dried_kelp", "dried_kelp_block"}, {"bone_meal", "bone_block"},
			{"melon_slice", "melon"}};
		for (String[] pair : nine) {
			String small = "minecraft:" + pair[0];
			String big = "minecraft:" + pair[1];
			list.add(new Conversion(big, 1, small, 9));
			list.add(new Conversion(small, 9, big, 1));
		}
		list.add(new Conversion("minecraft:waxed_copper_block", 1, "minecraft:copper_ingot", 9));
		return list;
	}
}
