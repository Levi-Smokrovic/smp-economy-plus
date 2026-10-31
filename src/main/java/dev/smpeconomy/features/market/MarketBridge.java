package dev.smpeconomy.features.market;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;
import dev.smpeconomy.core.Hooks;

/**
 * The only link between market and the auction house. Production uses {@link AuctionMarketAdapter}, selftests use an in-memory
 * fake. Prices are always stack totals in cents; everything runs on the server thread.
 */
public interface MarketBridge {
	String SOURCE = "market";

	/**
	 * Immutable view of an active listing. {@code seller} is null for NPC listings and {@code item} is a copy. {@code bargain}
	 * marks an NPC bargain (bargain floor on purchase), {@code note} is an extra display line.
	 */
	record Listing(long id, UUID seller, String sellerName, boolean npc, String source, ItemStack item, long price, long createdAt,
			long expiresAt, boolean bargain, String note) {
		public Listing {
			note = note == null ? "" : note;
		}

		public Listing(long id, UUID seller, String sellerName, boolean npc, String source, ItemStack item, long price, long createdAt,
				long expiresAt) {
			this(id, seller, sellerName, npc, source, item, price, createdAt, expiresAt, false, "");
		}

		public int count() {
			return item.getCount();
		}
	}

	/** {@code listing} is set only on success and carries the actual price, which may have been raised. */
	record Created(boolean ok, Listing listing, String error) {}

	enum BuyStatus {
		OK, GONE, ABOVE_CAP, PAYMENT_FAILED, NOT_TRADABLE, ERROR;

		public boolean ok() {
			return this == OK;
		}
	}

	/** Who pays for an NPC purchase: {@code withdraw} books exactly {@code cents} (true) or nothing (false). */
	interface Payer {
		boolean withdraw(long cents);

		/** Only if the purchase fails after {@code withdraw}. */
		void refund(long cents);
	}

	interface Events {
		void onListingCreated(Listing l);

		/** {@code buyer == null} means an NPC bought it. */
		void onListingSold(Listing l, UUID buyer, long price, long tax);

		void onListingRemoved(Listing l);
	}

	/** Extra floor for NPC offers (stack total), e.g. depending on enchantments. {@code buyer} is null at listing time. */
	@FunctionalInterface
	interface SaleFloor {
		long minTotalPrice(UUID buyer, ItemStack stack);

		default long minBargainTotalPrice(UUID buyer, ItemStack stack) {
			return minTotalPrice(buyer, stack);
		}
	}

	List<Listing> activeListings();

	Optional<Listing> listing(long id);

	List<Listing> npcListings(String source);

	/**
	 * Creates an NPC offer. The item only comes into existence on purchase and proceeds go to the treasury; the price may be
	 * raised to the floor.
	 */
	default Created createNpcListing(ItemStack stack, long price, long durationMs, String sellerName, String source) {
		return createNpcListing(stack, price, durationMs, sellerName, source, false, "");
	}

	/**
	 * As above, for bargains: the price is only raised to the bargain floor and checked against the buyer's on purchase ({@link
	 * SaleFloor#minBargainTotalPrice}). {@code note} is an extra display line, empty for none.
	 */
	Created createNpcListing(ItemStack stack, long price, long durationMs, String sellerName, String source, boolean bargain, String note);

	boolean removeNpcListing(long id);

	/**
	 * An NPC buys a player listing: cap check with {@code unitMarketValue}, {@code payer.withdraw(price)}, the seller gets price
	 * minus AH tax, the tax goes to the treasury, the item is deleted. Failures change nothing.
	 */
	BuyStatus buyAsNpc(long id, long unitMarketValue, Payer payer);

	long taxFor(long price);

	/** Registers event listeners and the price floor; {@code remove()} unregisters everything. */
	Hooks.Registration install(Events events, SaleFloor floor);
}
