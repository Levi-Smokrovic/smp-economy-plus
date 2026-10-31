package dev.smpeconomy.features.auction;

import java.util.ArrayList;
import java.util.List;
import dev.smpeconomy.core.ConfigManager;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.Money;

/** Always read through {@link #get()}: {@code /smpeco reload} replaces the object. */
public final class AuctionConfig {
	/** Maximum active listings per player. */
	public int maxListings = 10;
	/** Maximum listing price in cents. */
	public long maxPrice = 100_000_000_000L;
	/** Player listing duration in hours. */
	public int durationHours = 48;
	/** Tax on player sales in percent, paid to the treasury. */
	public double taxPercent = 3.0;
	/** Listing is blocked once a player has this many unclaimed items. */
	public int maxClaims = 45;
	/** Off by default: creative items would be money from nothing. */
	public boolean allowCreative = false;
	/** Safety cap on concurrent NPC listings. The market feature keeps 50 to 80 of them, about two pages. */
	public int maxNpcListings = 150;
	/** Longest NPC listing duration in hours. */
	public int maxNpcDurationHours = 48;
	/**
	 * Largest item accepted for listing, in bytes of uncompressed NBT including contents. Protects memory and GUIs
	 * from items such as shulker boxes full of written books.
	 */
	public int maxItemBytes = 65_536;
	/** Maximum lore lines on a listed item. The GUI preview appends lines and vanilla allows at most 256. */
	public int maxLoreLines = 64;
	/**
	 * Confirmation window in seconds for {@code /ah sell}. Required for more than one item, since the price covers
	 * the whole stack, and for prices that trigger a warning.
	 */
	public int sellConfirmSeconds = 10;
	/**
	 * {@code /ah sell} also warns and asks for confirmation on single items priced below this percentage of market
	 * value, or below what /sell pays. 0 means only the /sell limit applies.
	 */
	public double sellWarnBelowMarketPercent = 50.0;
	public List<String> blockedItems = new ArrayList<>(List.of("minecraft:barrier", "minecraft:command_block", "minecraft:chain_command_block",
		"minecraft:repeating_command_block", "minecraft:command_block_minecart", "minecraft:structure_block", "minecraft:jigsaw",
		"minecraft:debug_stick", "minecraft:light", "minecraft:structure_void", "minecraft:test_block", "minecraft:test_instance_block"));

	/** Version of this file's defaults, for {@link #migrate}. 0 when the key is missing. Do not edit by hand. */
	public int defaultsVersion = 0;

	static final int DEFAULTS_VERSION = 2;
	static final int OLD_MAX_NPC_LISTINGS = 30;

	static final ConfigManager.Config<AuctionConfig> CONFIG =
		ConfigManager.register("auction", AuctionConfig.class, AuctionConfig::new, AuctionConfig::validate);

	public static AuctionConfig get() {
		return CONFIG.get();
	}

	/**
	 * Moves values still exactly on an old default to the new default; admin-set values stay. ConfigManager then
	 * writes the file back with the new key. Returns a description of the changes.
	 */
	static List<String> migrate(AuctionConfig c) {
		List<String> changes = new ArrayList<>();
		if (c.defaultsVersion >= DEFAULTS_VERSION) return changes;
		if (c.defaultsVersion < 2 && c.maxNpcListings == OLD_MAX_NPC_LISTINGS) {
			c.maxNpcListings = new AuctionConfig().maxNpcListings;
			changes.add("maxNpcListings " + OLD_MAX_NPC_LISTINGS + " → " + c.maxNpcListings);
		}
		c.defaultsVersion = DEFAULTS_VERSION;
		return changes;
	}

	static void validate(AuctionConfig c) {
		List<String> migrated = migrate(c);
		if (!migrated.isEmpty()) {
			dev.smpeconomy.SmpEconomy.LOG.warn("[SMPEco] auction.json: migrated old defaults: {}", String.join(", ", migrated));
		}
		if (c.maxListings < 1 || c.maxListings > 1000) throw new IllegalArgumentException("maxListings must be 1-1000");
		if (!Money.isValidAmount(c.maxPrice)) throw new IllegalArgumentException("maxPrice must be > 0 and <= Money.MAX");
		if (c.durationHours < 1 || c.durationHours > 24 * 30) throw new IllegalArgumentException("durationHours must be 1-720");
		if (!(c.taxPercent >= 0 && c.taxPercent <= 50)) throw new IllegalArgumentException("taxPercent must be 0-50");
		if (c.maxClaims < 1 || c.maxClaims > 10_000) throw new IllegalArgumentException("maxClaims must be 1-10000");
		if (c.maxNpcListings < 0 || c.maxNpcListings > 1000) throw new IllegalArgumentException("maxNpcListings must be 0-1000");
		if (c.maxNpcDurationHours < 1 || c.maxNpcDurationHours > 24 * 30) throw new IllegalArgumentException("maxNpcDurationHours must be 1-720");
		if (c.maxItemBytes < 1024 || c.maxItemBytes > 1_048_576) throw new IllegalArgumentException("maxItemBytes must be 1024-1048576");
		if (c.maxLoreLines < 0 || c.maxLoreLines > 200) throw new IllegalArgumentException("maxLoreLines must be 0-200");
		if (c.sellConfirmSeconds < 3 || c.sellConfirmSeconds > 120) throw new IllegalArgumentException("sellConfirmSeconds must be 3-120");
		if (!(c.sellWarnBelowMarketPercent >= 0 && c.sellWarnBelowMarketPercent <= 100)) {
			throw new IllegalArgumentException("sellWarnBelowMarketPercent must be 0-100");
		}
		if (c.blockedItems == null) c.blockedItems = new ArrayList<>();
		c.blockedItems.removeIf(s -> s == null || s.isBlank());
	}

	public boolean isBlocked(net.minecraft.world.item.Item item) {
		String id = Items.id(item);
		for (String b : blockedItems) {
			if (id.equals(b) || id.equals("minecraft:" + b)) return true;
		}
		return false;
	}
}
