package dev.smpeconomy.features.jobs;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import dev.smpeconomy.core.PriceGuard;

/**
 * Minimum cost of creating a paid mob purely from system items. Basis for the hunter pay cap on respawned ender dragons
 * (otherwise buying crystal ingredients, respawning the dragon and collecting pay would turn a profit from about level
 * 26) and for the selftest {@code summons-not-profitable}.
 * <p>An item's minimum system price is pivot + 1 ({@link PriceGuard}: shop, daily offers, flash sales and NPC offers
 * never sell below it, whatever events run). Recomputed per call through {@link PriceGuard#pivotPrice}, so it follows
 * {@code /smpeco reload} of shop.json. Everything errs low: raw materials the system doesn't sell count as 0, glass
 * doesn't count at all (sand can be smelted).
 */
public final class SummonCosts {
	/** Highest share of the crystals' minimum cost a respawned dragon may pay, after level and all event hooks. */
	public static final double RESPAWN_MAX_SHARE = 0.5;
	/** 4 on the exit portal; they explode in the process. */
	public static final int RESPAWN_CRYSTALS = 4;

	private SummonCosts() {}

	/** Minimum price (cents each) the system sells this item for; -1 if it doesn't sell it. */
	public static long minSystemPrice(Item item) {
		long pivot = PriceGuard.pivotPrice(item);
		return pivot < 0 ? -1 : pivot + 1;
	}

	/** Direct purchase as an alternative: minimum price or "impossible" ({@code Long.MAX_VALUE}). */
	private static long direct(Item item) {
		long p = minSystemPrice(item);
		return p < 0 ? Long.MAX_VALUE : p;
	}

	/**
	 * Raw material in a recipe chain: minimum price, or 0 if the system doesn't sell it and it has to be self-sourced.
	 */
	private static long raw(Item item) {
		return Math.max(0, minSystemPrice(item));
	}

	/**
	 * Minimum cost of one end crystal from the system, in cents: direct purchase, or ghast tear + eye of ender (direct,
	 * or ender pearl + blaze powder; powder direct or half a blaze rod). The 7 glass don't count.
	 */
	public static long endCrystalMinCost() {
		long powder = Math.min(direct(Items.BLAZE_POWDER), raw(Items.BLAZE_ROD) / 2);
		long eye = Math.min(direct(Items.ENDER_EYE), raw(Items.ENDER_PEARL) + powder);
		return Math.min(direct(Items.END_CRYSTAL), raw(Items.GHAST_TEAR) + eye);
	}

	public static long dragonRespawnMinCost() {
		return Math.multiplyExact(endCrystalMinCost(), (long) RESPAWN_CRYSTALS);
	}

	/**
	 * Highest pay in thousandths of a cent for a respawned dragon: {@link #RESPAWN_MAX_SHARE} of its crystals' minimum
	 * cost, so always strictly less than respawning costs (0 if it costs nothing).
	 */
	public static long respawnedDragonCapMilli() {
		long cost = dragonRespawnMinCost();
		return cost <= 0 ? 0 : (long) Math.floor(cost * 1000.0 * RESPAWN_MAX_SHARE);
	}
}
