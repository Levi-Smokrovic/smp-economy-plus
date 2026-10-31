package dev.smpeconomy.features.shop;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.BasePrices;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Hooks;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.PriceGuard;
import dev.smpeconomy.core.Services;
import dev.smpeconomy.core.Treasury;

/**
 * Buy and sell logic without GUI, on the server thread. Prices come fresh from {@link PriceGuard} on every
 * transaction.
 *
 * <p>Buying delivers only what fits in the main inventory and charges only for that. Money is taken first,
 * then items are given, because {@code giveOrDrop} cannot fail. {@code treasurySharePercent} of the amount
 * goes to the treasury and the rest disappears.
 *
 * <p>Selling takes only pristine stacks from the main inventory, as /sell does, and likewise not in creative
 * or spectator mode ({@link #sellBlocked}). Items are removed first, then credited; if the credit fails they
 * go back.
 */
public final class ShopService {
	public enum Status {
		OK(""),
		NOT_IN_SHOP("This item isn't in the shop (anymore)."),
		INVALID_AMOUNT("Invalid amount."),
		NO_SPACE("No space in your inventory."),
		INSUFFICIENT_FUNDS("Not enough money."),
		PRICE_CHANGED("The price just changed – please check and click again."),
		CANNOT_RECEIVE("You can't do that right now."),
		NOT_SELLABLE("This item can't be sold to the server."),
		NOTHING_TO_SELL("You don't have any sellable ones (enchanted/renamed/damaged items don't count)."),
		LIMIT_EXCEEDED("Balance limit reached."),
		DISABLED("Selling in the shop is disabled."),
		NO_PERMISSION("You're not allowed to sell in the shop."),
		BLOCKED("You can't sell in Creative or Spectator mode.");

		private final String message;

		Status(String message) {
			this.message = message;
		}

		public boolean ok() {
			return this == OK;
		}

		public String message() {
			return message;
		}
	}

	/**
	 * {@code requested} is capped at the purchase limit; {@code delivered} is delivered and paid; {@code
	 * unitPrice} is the current price, also on failure, otherwise -1.
	 */
	public record BuyResult(Status status, int requested, int delivered, long unitPrice, long total, long treasuryShare, long balanceAfter) {
		public boolean partial() {
			return status.ok() && delivered < requested;
		}
	}

	public record SellResult(Status status, int sold, long unitPrice, long total, long balanceAfter) {}

	private ShopService() {}

	/** Empty when not in the shop. max(PriceGuard.effectiveShopBuyPrice, {@link RecipeGuard} floor). */
	public static OptionalLong unitBuyPrice(UUID player, Item item) {
		OptionalLong guarded = PriceGuard.effectiveShopBuyPrice(player, item);
		if (guarded.isEmpty()) return guarded;
		long floor = RecipeGuard.floor(item);
		return floor > guarded.getAsLong() ? OptionalLong.of(floor) : guarded;
	}

	/** Same as /sell. Empty when not sellable. */
	public static OptionalLong unitSellPrice(UUID player, Item item) {
		return PriceGuard.effectiveSellPrice(player, item);
	}

	/** Highest value on duplicate entries in shop.json; 0 when not in the shop. */
	public static int maxPerPurchase(Item item) {
		int max = 0;
		for (BasePrices.ShopCategory c : BasePrices.shopCategories()) {
			for (BasePrices.ShopItem i : c.items()) {
				if (i.item() == item) max = Math.max(max, i.max());
			}
		}
		return max;
	}

	/** Creative and spectator items come from nothing, as with /sell, unless {@code allowCreativeSell} is set. */
	public static boolean sellBlocked(ServerPlayer player) {
		return !ShopConfig.get().allowCreativeSell && Items.isCreativeOrSpectator(player);
	}

	public static int fitCount(ServerPlayer player, Item item) {
		return Items.spaceFor(player, new ItemStack(item));
	}

	public static int sellableCount(ServerPlayer player, Item item) {
		return Items.countPristine(player, item);
	}

	/** -1 on overflow or above {@link Money#MAX}. */
	public static long total(long unit, long count) {
		if (unit < 0 || count < 0) return -1;
		try {
			long t = Math.multiplyExact(unit, count);
			return t <= Money.MAX ? t : -1;
		} catch (ArithmeticException e) {
			return -1;
		}
	}

	public static int affordable(long balance, long unit) {
		if (unit <= 0 || balance <= 0) return 0;
		return (int) Math.min(Integer.MAX_VALUE, balance / unit);
	}

	/** Rounded down. */
	public static long treasuryShare(long total, double percent) {
		return Money.percentOf(total, percent);
	}

	/**
	 * Buys up to {@code amount}, limited by purchase limit and inventory space.
	 *
	 * @param maxUnitPrice the price the player saw; above it nothing is bought ({@link Status#PRICE_CHANGED}). -1
	 * skips the check.
	 */
	public static BuyResult buy(ServerPlayer player, Item item, int amount, long maxUnitPrice) {
		UUID id = player.getUUID();
		long balance = EconomyService.balance(id);
		if (!Items.canReceive(player)) return buyFail(Status.CANNOT_RECEIVE, 0, -1, balance);
		int limit = maxPerPurchase(item);
		OptionalLong price = unitBuyPrice(id, item);
		if (limit <= 0 || price.isEmpty()) return buyFail(Status.NOT_IN_SHOP, 0, -1, balance);
		if (amount <= 0) return buyFail(Status.INVALID_AMOUNT, 0, price.getAsLong(), balance);
		int requested = Math.min(amount, limit);
		long unit = price.getAsLong();
		if (maxUnitPrice >= 0 && unit > maxUnitPrice) return buyFail(Status.PRICE_CHANGED, requested, unit, balance);
		int deliver = Math.min(requested, fitCount(player, item));
		if (deliver <= 0) return buyFail(Status.NO_SPACE, requested, unit, balance);
		long total = total(unit, deliver);
		if (total <= 0) return buyFail(Status.INVALID_AMOUNT, requested, unit, balance);
		EconomyService.Result paid = EconomyService.withdraw(id, total);
		if (!paid.ok()) {
			return buyFail(paid == EconomyService.Result.INSUFFICIENT_FUNDS ? Status.INSUFFICIENT_FUNDS : Status.INVALID_AMOUNT, requested, unit, balance);
		}
		int dropped = Items.giveOrDrop(player, new ItemStack(item, deliver));
		if (dropped > 0) {
			// Should not happen since space was checked. The items land at the player's feet, so nothing is lost
			SmpEconomy.LOG.warn("[SMPEco] Shop: {}x {} for {} dropped instead of put in the inventory", dropped, Items.id(item), player.getPlainTextName());
		}
		long share = treasuryShare(total, ShopConfig.get().treasurySharePercent);
		if (share > 0) {
			EconomyService.Result r = Treasury.deposit(share, Treasury.R_SHOP);
			if (!r.ok()) SmpEconomy.LOG.debug("[SMPEco] Shop: treasury share {} not deposited: {}", share, r);
		}
		ShopPlayerData.recordPurchase(id, item, requested);
		DataStore.requestPlayerSave(player);
		return new BuyResult(Status.OK, requested, deliver, unit, total, share, EconomyService.balance(id));
	}

	private static BuyResult buyFail(Status status, int requested, long unit, long balance) {
		return new BuyResult(status, requested, 0, unit, 0, 0, balance);
	}

	/**
	 * Sells up to {@code amount} pristine items at the /sell price.
	 *
	 * @param minUnitPrice the price the player saw; below it nothing is sold ({@link Status#PRICE_CHANGED}). -1
	 * skips the check.
	 */
	public static SellResult sell(ServerPlayer player, Item item, int amount, long minUnitPrice) {
		UUID id = player.getUUID();
		long balance = EconomyService.balance(id);
		if (!ShopConfig.get().sellInShop) return sellFail(Status.DISABLED, -1, balance);
		if (sellBlocked(player)) return sellFail(Status.BLOCKED, -1, balance);
		if (!Items.canReceive(player)) return sellFail(Status.CANNOT_RECEIVE, -1, balance);
		OptionalLong price = unitSellPrice(id, item);
		if (price.isEmpty()) return sellFail(Status.NOT_SELLABLE, -1, balance);
		long unit = price.getAsLong();
		if (amount <= 0) return sellFail(Status.INVALID_AMOUNT, unit, balance);
		if (minUnitPrice >= 0 && unit < minUnitPrice) return sellFail(Status.PRICE_CHANGED, unit, balance);
		int have = sellableCount(player, item);
		if (have <= 0) return sellFail(Status.NOTHING_TO_SELL, unit, balance);
		int n = Math.min(amount, have);
		long expected = total(unit, n);
		if (expected <= 0 || balance > Money.MAX - expected) return sellFail(Status.LIMIT_EXCEEDED, unit, balance);
		int removed = Items.remove(player, s -> s.is(item) && Items.isPristine(s), n);
		if (removed <= 0) return sellFail(Status.NOTHING_TO_SELL, unit, balance);
		long pay = total(unit, removed);
		EconomyService.Result r = pay > 0 ? EconomyService.deposit(id, pay) : EconomyService.Result.INVALID_AMOUNT;
		if (!r.ok()) {
			// Credit failed; the space was just freed, so the items fit back
			Items.giveOrDrop(player, new ItemStack(item, removed));
			DataStore.requestPlayerSave(player);
			return sellFail(r == EconomyService.Result.LIMIT_EXCEEDED ? Status.LIMIT_EXCEEDED : Status.INVALID_AMOUNT, unit, balance);
		}
		Hooks.fireItemsSold(player, pay, removed, List.of(new Hooks.SoldLine(item, removed, unit)));
		DataStore.requestPlayerSave(player);
		return new SellResult(Status.OK, removed, unit, pay, EconomyService.balance(id));
	}

	private static SellResult sellFail(Status status, long unit, long balance) {
		return new SellResult(status, 0, unit, 0, balance);
	}
}
