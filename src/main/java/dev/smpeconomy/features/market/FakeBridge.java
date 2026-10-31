package dev.smpeconomy.features.market;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Hooks;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.PriceGuard;
import dev.smpeconomy.core.Services;
import dev.smpeconomy.core.Treasury;

/**
 * In-memory auction house for selftests with the same semantics as {@code AuctionService}: an NPC purchase pays through the
 * payer, the seller gets price minus tax, the tax goes to the treasury. NPC offers are raised to the floor on listing and
 * rechecked against the real buyer on purchase (100 % to the treasury). Ids are negative so they never collide with real
 * listings.
 */
final class FakeBridge implements MarketBridge {
	static final double TAX_PERCENT = 3.0;
	private final Map<Long, Listing> listings = new LinkedHashMap<>();
	private long nextId = -1_000_001L;
	private Events events;
	private SaleFloor floor;
	int maxNpcListings = 30;
	java.util.function.LongSupplier clock = System::currentTimeMillis;

	Listing addPlayerListing(UUID seller, ItemStack stack, long price, long now) {
		Listing l = new Listing(nextId--, seller, EconomyService.nameOrUnknown(seller), false, "player", stack.copy(), price, now, now + 48 * 3_600_000L);
		listings.put(l.id(), l);
		if (events != null) events.onListingCreated(l);
		return l;
	}

	/** Removes a listing without any event, for simulations that already booked the purchase. */
	void removeSilently(long id) {
		listings.remove(id);
	}

	void cancel(long id) {
		Listing l = listings.remove(id);
		if (l != null && events != null) events.onListingRemoved(l);
	}

	@Override
	public List<Listing> activeListings() {
		return new ArrayList<>(listings.values());
	}

	@Override
	public Optional<Listing> listing(long id) {
		return Optional.ofNullable(listings.get(id));
	}

	@Override
	public List<Listing> npcListings(String source) {
		List<Listing> list = new ArrayList<>();
		for (Listing l : listings.values()) {
			if (l.npc() && (source == null || source.equals(l.source()))) list.add(l);
		}
		return list;
	}

	/**
	 * Bargain floor like {@code AuctionService.npcBargainFloor}: per unit the buyer's /sell price + 1 cent (unmodified stacks) or
	 * pivot + 1, at least market's bargain floor; without market the normal floor.
	 */
	long npcBargainFloor(UUID buyer, ItemStack stack) {
		if (floor == null) return npcSaleFloor(buyer, stack);
		UUID who = buyer == null ? ValueEngine.NEUTRAL : buyer;
		long unit = 1;
		if (Items.isPristine(stack)) unit = Math.max(unit, PriceGuard.effectiveSellPrice(who, stack.getItem()).orElse(0) + 1);
		long pivot = PriceGuard.pivotPrice(stack.getItem());
		if (pivot >= 0) unit = Math.max(unit, pivot + 1);
		long total = Math.max(ValueEngine.saturatingMul(unit, Math.max(1, stack.getCount())), floor.minBargainTotalPrice(buyer, stack.copy()));
		return Math.max(1, Math.min(Money.MAX, total));
	}

	/** Floor like {@code AuctionService.npcSaleFloor}: PriceGuard with Services.marketValue, at least the extra floor. */
	long npcSaleFloor(UUID buyer, ItemStack stack) {
		long market = Services.marketValue(stack.getItem()).orElse(0);
		long total = ValueEngine.saturatingMul(PriceGuard.minSystemSalePrice(buyer == null ? ValueEngine.NEUTRAL : buyer, stack.getItem(), market),
			Math.max(1, stack.getCount()));
		if (floor != null) total = Math.max(total, floor.minTotalPrice(buyer, stack.copy()));
		return Math.max(1, Math.min(Money.MAX, total));
	}

	@Override
	public Created createNpcListing(ItemStack stack, long price, long durationMs, String sellerName, String source, boolean bargain,
			String note) {
		if (stack == null || stack.isEmpty() || !Money.isValidAmount(price) || durationMs <= 0) return new Created(false, null, "invalid");
		if (npcListings(null).size() >= maxNpcListings) return new Created(false, null, "LIMIT_REACHED");
		long effective = Math.max(price, bargain ? npcBargainFloor(null, stack) : npcSaleFloor(null, stack));
		long now = clock.getAsLong();
		Listing l = new Listing(nextId--, null, sellerName, true, source, stack.copy(), effective, now, now + durationMs, bargain,
			note == null ? "" : note);
		listings.put(l.id(), l);
		if (events != null) events.onListingCreated(l);
		return new Created(true, l, null);
	}

	@Override
	public boolean removeNpcListing(long id) {
		Listing l = listings.get(id);
		if (l == null || !l.npc()) return false;
		listings.remove(id);
		if (events != null) events.onListingRemoved(l);
		return true;
	}

	@Override
	public BuyStatus buyAsNpc(long id, long unitMarketValue, Payer payer) {
		Listing l = listings.get(id);
		if (l == null) return BuyStatus.GONE;
		if (l.npc() || l.seller() == null) return BuyStatus.ERROR;
		double factor = Hooks.flag(Hooks.FLAG_AUCTION_FEVER) ? PriceGuard.NPC_BUY_MARKET_FACTOR_MAX : PriceGuard.NPC_BUY_MARKET_FACTOR;
		long cap = ValueEngine.saturatingMul(PriceGuard.maxNpcBuyPrice(l.seller(), l.item().getItem(), unitMarketValue, factor), l.count());
		if (l.price() > cap) return BuyStatus.ABOVE_CAP;
		if (!payer.withdraw(l.price())) return BuyStatus.PAYMENT_FAILED;
		long tax = taxFor(l.price());
		long net = l.price() - tax;
		if (net > 0 && !EconomyService.deposit(l.seller(), net).ok()) {
			payer.refund(l.price());
			return BuyStatus.ERROR;
		}
		if (tax > 0) Treasury.deposit(tax, Treasury.R_AH_TAX);
		listings.remove(id);
		if (events != null) events.onListingSold(l, null, l.price(), tax);
		return BuyStatus.OK;
	}

	enum PlayerBuy {
		OK, GONE, PRICE_CHANGED, NO_MONEY
	}

	/**
	 * A player buys an NPC offer: recheck the floor (or bargain floor) against the real buyer, 100 % to the treasury, item to
	 * inventory.
	 */
	PlayerBuy buyNpcAsPlayer(ServerPlayer buyer, long id) {
		Listing l = listings.get(id);
		if (l == null || !l.npc()) return PlayerBuy.GONE;
		long min = l.bargain() ? npcBargainFloor(buyer.getUUID(), l.item()) : npcSaleFloor(buyer.getUUID(), l.item());
		if (l.price() < min) {
			listings.put(id, new Listing(l.id(), l.seller(), l.sellerName(), true, l.source(), l.item(), min, l.createdAt(), l.expiresAt(),
				l.bargain(), l.note()));
			return PlayerBuy.PRICE_CHANGED;
		}
		if (!Treasury.collectFrom(buyer.getUUID(), l.price(), Treasury.R_NPC_SALE).ok()) return PlayerBuy.NO_MONEY;
		listings.remove(id);
		Items.giveOrDrop(buyer, l.item());
		if (events != null) events.onListingSold(l, buyer.getUUID(), l.price(), 0);
		return PlayerBuy.OK;
	}

	@Override
	public long taxFor(long price) {
		return Math.min(price, Money.percentOf(price, TAX_PERCENT));
	}

	@Override
	public Hooks.Registration install(Events events, SaleFloor floor) {
		this.events = events;
		this.floor = floor;
		return Hooks.Registration.of(() -> {
			this.events = null;
			this.floor = null;
		});
	}
}
