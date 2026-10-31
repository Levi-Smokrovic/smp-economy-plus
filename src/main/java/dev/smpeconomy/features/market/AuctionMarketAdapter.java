package dev.smpeconomy.features.market;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import dev.smpeconomy.core.Hooks;
import dev.smpeconomy.features.auction.AuctionService;

/** {@link MarketBridge} on top of {@code AuctionService}. The only class in market that knows the auction package. */
public final class AuctionMarketAdapter implements MarketBridge {
	static Listing map(AuctionService.Listing l) {
		return new Listing(l.id(), l.seller(), l.sellerName(), l.npc(), l.source(), l.item(), l.price(), l.createdAt(), l.expiresAt(),
			l.bargain(), l.note());
	}

	private static List<Listing> map(List<AuctionService.Listing> list) {
		List<Listing> out = new ArrayList<>(list.size());
		for (AuctionService.Listing l : list) out.add(map(l));
		return out;
	}

	@Override
	public List<Listing> activeListings() {
		return map(AuctionService.activeListings());
	}

	@Override
	public Optional<Listing> listing(long id) {
		return AuctionService.listing(id).map(AuctionMarketAdapter::map);
	}

	@Override
	public List<Listing> npcListings(String source) {
		return map(AuctionService.npcListings(source));
	}

	@Override
	public Created createNpcListing(net.minecraft.world.item.ItemStack stack, long price, long durationMs, String sellerName, String source,
			boolean bargain, String note) {
		AuctionService.CreateResult r = AuctionService.createNpcListing(stack, price, durationMs, sellerName, source,
			new AuctionService.NpcOffer(bargain, note));
		if (r.ok() && r.listing() != null) return new Created(true, map(r.listing()), null);
		return new Created(false, null, r.result().name() + ": " + r.result().message());
	}

	@Override
	public boolean removeNpcListing(long id) {
		return AuctionService.removeNpcListing(id);
	}

	@Override
	public BuyStatus buyAsNpc(long id, long unitMarketValue, Payer payer) {
		AuctionService.Result r = AuctionService.buyListingAsNpc(id, unitMarketValue, new AuctionService.NpcPayer() {
			@Override
			public boolean withdraw(long cents) {
				return payer.withdraw(cents);
			}

			@Override
			public void refund(long cents) {
				payer.refund(cents);
			}
		});
		return switch (r) {
			case OK -> BuyStatus.OK;
			case NOT_FOUND -> BuyStatus.GONE;
			case PRICE_ABOVE_CAP -> BuyStatus.ABOVE_CAP;
			case PAYMENT_FAILED -> BuyStatus.PAYMENT_FAILED;
			case NOT_NPC_TRADABLE, ITEM_TOO_LARGE -> BuyStatus.NOT_TRADABLE;
			default -> BuyStatus.ERROR;
		};
	}

	@Override
	public long taxFor(long price) {
		return AuctionService.taxFor(price);
	}

	@Override
	public Hooks.Registration install(Events events, SaleFloor floor) {
		List<Hooks.Registration> regs = new ArrayList<>();
		regs.add(AuctionService.onListingCreated(l -> events.onListingCreated(map(l))));
		regs.add(AuctionService.onListingSold((l, buyer, price, tax) -> events.onListingSold(map(l), buyer, price, tax)));
		regs.add(AuctionService.onListingRemoved((l, reason) -> events.onListingRemoved(map(l))));
		if (floor != null) {
			regs.add(AuctionService.addNpcSaleFloor(new AuctionService.NpcSaleFloor() {
				@Override
				public long minTotalPrice(java.util.UUID buyer, net.minecraft.world.item.ItemStack stack) {
					return floor.minTotalPrice(buyer, stack);
				}

				@Override
				public long minBargainTotalPrice(java.util.UUID buyer, net.minecraft.world.item.ItemStack stack) {
					return floor.minBargainTotalPrice(buyer, stack);
				}
			}));
		}
		return Hooks.Registration.of(() -> regs.forEach(Hooks.Registration::remove));
	}
}
