package dev.smpeconomy.features.auction;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UTFDataFormatException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Hooks;
import dev.smpeconomy.core.ItemCodec;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.PriceGuard;
import dev.smpeconomy.core.Services;
import dev.smpeconomy.core.Settings;
import dev.smpeconomy.core.TimeUtil;
import dev.smpeconomy.core.Treasury;
import dev.smpeconomy.features.auction.AuctionStore.Claim;
import dev.smpeconomy.features.auction.AuctionStore.Entry;

/**
 * Public API and logic of the auction house, see {@code AUCTION_API.md}. Everything runs on the server thread;
 * other threads get an {@link IllegalStateException}. Money is in cents and prices are stack totals.
 *
 * <p>An item is always in exactly one place: a player's inventory, an active listing or the claim bin. Each
 * transition happens synchronously in one call (check, money, store, inventory) and then requests {@link
 * DataStore#requestPlayerSave(ServerPlayer)} for the affected player, so a crash cannot dupe it.
 */
public final class AuctionService {
	public static final String SOURCE_PLAYER = "player";
	/** Source of the admin test NPC listings from {@code /ah admin npc}. */
	public static final String SOURCE_ADMIN = "admin";
	static final long HOUR_MS = 3_600_000L;
	/** No specific buyer, for PriceGuard calculations when an NPC listing is created. */
	private static final UUID NEUTRAL = new UUID(0L, 0L);

	/**
	 * Immutable view of an active listing. {@link #item()} returns a new copy each call. {@code bargain} and
	 * {@code note} apply only to NPC listings.
	 */
	public record Listing(long id, UUID seller, String sellerName, boolean npc, String source, ItemStack item, long price,
			long createdAt, long expiresAt, boolean bargain, String note) {
		public Listing {
			item = item.copy();
			note = note == null ? "" : note;
		}

		@Override
		public ItemStack item() {
			return item.copy();
		}

		public boolean isNpc() {
			return npc;
		}

		public int count() {
			return item.getCount();
		}

		/** Rounded down. */
		public long unitPrice() {
			return price / Math.max(1, item.getCount());
		}

		public long remainingMs() {
			return Math.max(0, expiresAt - now());
		}
	}

	/** {@code reason} is "expired", "cancelled" or "admin". */
	public record ClaimInfo(long id, UUID owner, ItemStack item, long price, long createdAt, String reason) {
		public ClaimInfo {
			item = item.copy();
		}

		@Override
		public ItemStack item() {
			return item.copy();
		}
	}

	public enum Result {
		OK("OK"),
		/** {@link #cancelListing} only: cancelled, but no space, so it went to the claim bin. */
		STORED("Cancelled – no space in your inventory, so the item is waiting in your claim box (/ah my)."),
		NOT_FOUND("This listing is no longer available."),
		OWN_LISTING("That's your own listing."),
		NPC_LISTING("That's an NPC listing."),
		NOT_NPC_LISTING("That's not an NPC listing."),
		INSUFFICIENT_FUNDS("You don't have enough money."),
		NO_SPACE("Not enough space in your inventory."),
		PRICE_CHANGED("The price has changed – please take another look."),
		PRICE_ABOVE_CAP("Price is above the NPC price cap."),
		PAYMENT_FAILED("Payment failed."),
		NO_ITEM("Hold the item you want to sell in your main hand."),
		INVALID_ITEM("This item can't be listed."),
		/** Item data above {@code maxItemBytes} or more than {@code maxLoreLines} lore lines. */
		ITEM_TOO_LARGE("This item holds too much data (e.g. full books, very long lore) and can't be listed."),
		/** NPC purchase rejected: the item has contents, or is not pristine when priced by item market value. */
		NOT_NPC_TRADABLE("NPC buyers don't buy items with contents, or damaged or modified items."),
		INVALID_PRICE("Invalid price."),
		INVALID_DURATION("Invalid duration."),
		LIMIT_REACHED("You already have the maximum number of listings."),
		CLAIM_BIN_FULL("Your claim box is full – claim your items first (/ah my)."),
		CREATIVE("You can't list items in Creative mode."),
		NOT_ALLOWED("You can't do that right now."),
		ITEM_UNREADABLE("Item data is unreadable – please report this to an admin."),
		MONEY_ERROR("Transaction failed – nothing was changed.");

		private final String message;

		Result(String message) {
			this.message = message;
		}

		public boolean ok() {
			return this == OK || this == STORED;
		}

		public String message() {
			return message;
		}
	}

	public record CreateResult(Result result, Listing listing) {
		public boolean ok() {
			return result == Result.OK;
		}
	}

	public interface NpcPayer {
		/** Takes exactly {@code cents} and returns true, or takes nothing and returns false. */
		boolean withdraw(long cents);

		/** Only called when the purchase fails after {@link #withdraw}. */
		void refund(long cents);

		/** Pays from the treasury, capped, with reason {@link Treasury#R_NPC_BUY}. */
		static NpcPayer treasury() {
			return new NpcPayer() {
				@Override
				public boolean withdraw(long cents) {
					return Treasury.tryWithdraw(cents, Treasury.R_NPC_BUY);
				}

				@Override
				public void refund(long cents) {
					EconomyService.Result r = Treasury.deposit(cents, Treasury.R_NPC_BUY);
					if (!r.ok()) SmpEconomy.LOG.error("[SMPEco] Auction: refund of {} to the treasury failed: {}", Money.format(cents), r);
				}
			};
		}
	}

	@FunctionalInterface
	public interface SoldListener {
		/** {@code buyer == null} means an NPC bought. {@code tax} is 0 for NPC listings. */
		void onSold(Listing listing, UUID buyer, long price, long tax);
	}

	public enum RemoveReason { EXPIRED, CANCELLED, NPC_REMOVED, ADMIN }

	@FunctionalInterface
	public interface RemovedListener {
		void onRemoved(Listing listing, RemoveReason reason);
	}

	/** Extra floor on the total price of NPC listings; {@code buyer} is null at listing time. */
	@FunctionalInterface
	public interface NpcSaleFloor {
		long minTotalPrice(UUID buyer, ItemStack stack);

		/**
		 * Floor for bargains ({@link #npcBargainFloor}). The default is the normal floor, so only a provider
		 * overriding this allows bargains below it.
		 */
		default long minBargainTotalPrice(UUID buyer, ItemStack stack) {
			return minTotalPrice(buyer, stack);
		}
	}

	/**
	 * {@code bargain}: {@link #npcBargainFloor} applies at listing and purchase instead of {@link #npcSaleFloor}.
	 * {@code note}: extra display line, single line, at most {@value #MAX_NOTE_LENGTH} characters.
	 */
	public record NpcOffer(boolean bargain, String note) {
		public static final NpcOffer NORMAL = new NpcOffer(false, "");

		public NpcOffer {
			note = cleanNote(note);
		}
	}

	public static final int MAX_NOTE_LENGTH = 80;

	static String cleanNote(String note) {
		if (note == null) return "";
		String s = note.replace('\n', ' ').replace('\r', ' ').strip();
		return s.length() > MAX_NOTE_LENGTH ? s.substring(0, MAX_NOTE_LENGTH) : s;
	}

	public enum Filter {
		ALL("All"),
		PLAYERS("Players only"),
		NPC("Market only");

		public final String label;

		Filter(String label) {
			this.label = label;
		}

		public Filter next() {
			return values()[(ordinal() + 1) % values().length];
		}
	}

	public enum Sort {
		NEWEST("Newest"),
		PRICE_ASC("Price ↑"),
		PRICE_DESC("Price ↓");

		public final String label;

		Sort(String label) {
			this.label = label;
		}

		public Sort next() {
			return values()[(ordinal() + 1) % values().length];
		}
	}

	private static final List<Consumer<Listing>> CREATED = new ArrayList<>();
	private static final List<SoldListener> SOLD = new ArrayList<>();
	private static final List<RemovedListener> REMOVED = new ArrayList<>();
	private static final List<NpcSaleFloor> EXTRA_FLOORS = new ArrayList<>();

	private AuctionService() {}

	public static Hooks.Registration onListingCreated(Consumer<Listing> listener) {
		CREATED.add(listener);
		return Hooks.Registration.of(() -> CREATED.remove(listener));
	}

	public static Hooks.Registration onListingSold(SoldListener listener) {
		SOLD.add(listener);
		return Hooks.Registration.of(() -> SOLD.remove(listener));
	}

	public static Hooks.Registration onListingRemoved(RemovedListener listener) {
		REMOVED.add(listener);
		return Hooks.Registration.of(() -> REMOVED.remove(listener));
	}

	/**
	 * The maximum of all registered floors and the PriceGuard floor applies. {@code remove()} unregisters only
	 * this one.
	 */
	public static Hooks.Registration addNpcSaleFloor(NpcSaleFloor floor) {
		if (floor == null) throw new IllegalArgumentException("floor missing");
		EXTRA_FLOORS.add(floor);
		return Hooks.Registration.of(() -> EXTRA_FLOORS.remove(floor));
	}

	/** Not expired and readable, newest first. */
	public static List<Listing> activeListings() {
		checkThread();
		long now = now();
		List<Listing> out = new ArrayList<>();
		for (Entry e : AuctionStore.data().listings) {
			if (e.expiresAt > now) view(e).ifPresent(out::add);
		}
		out.sort(Comparator.comparingLong(Listing::createdAt).thenComparingLong(Listing::id).reversed());
		return out;
	}

	/** {@code null} source means all NPC listings. Newest first. */
	public static List<Listing> npcListings(String source) {
		List<Listing> out = new ArrayList<>();
		for (Listing l : activeListings()) {
			if (l.npc() && (source == null || source.equals(l.source()))) out.add(l);
		}
		return out;
	}

	/** Empty when sold, expired, cancelled or unreadable. */
	public static Optional<Listing> listing(long id) {
		checkThread();
		Entry e = AuctionStore.findListing(id);
		if (e == null || e.expiresAt <= now()) return Optional.empty();
		return view(e);
	}

	/** Includes expired listings not yet moved to the claim bin. */
	public static int activeCount(UUID seller) {
		int n = 0;
		for (Entry e : AuctionStore.data().listings) {
			if (!e.npc && seller.equals(e.seller)) n++;
		}
		return n;
	}

	/** Includes unreadable entries. */
	public static int claimCount(UUID owner) {
		int n = 0;
		for (Claim c : AuctionStore.data().claims) {
			if (owner.equals(c.owner)) n++;
		}
		return n;
	}

	/** Readable entries, oldest first. */
	public static List<ClaimInfo> claims(UUID owner) {
		checkThread();
		List<ClaimInfo> out = new ArrayList<>();
		for (Claim c : AuctionStore.data().claims) {
			if (!owner.equals(c.owner)) continue;
			AuctionStore.stack(c).ifPresent(s -> out.add(new ClaimInfo(c.id, c.owner, s, c.price, c.createdAt, c.reason)));
		}
		return out;
	}

	public static int npcCount() {
		int n = 0;
		for (Entry e : AuctionStore.data().listings) {
			if (e.npc) n++;
		}
		return n;
	}

	/** Rounded down. */
	public static long taxFor(long price) {
		return Math.min(price, Money.percentOf(price, AuctionConfig.get().taxPercent));
	}

	/**
	 * Highest total an NPC may pay for this player listing now; 0 for NPC listings and items with contents.
	 * <ol>
	 * <li>{@code unitMarketValue} is capped at max(item market value prorated by durability, extra floor per
	 * item), so a higher value, for example one based on enchantments, only counts if a matching NPC floor is
	 * registered;</li>
	 * <li>{@link PriceGuard#maxNpcBuyPrice} times count;</li>
	 * <li>at most {@link #npcSaleFloor}(seller, stack) - 1: an NPC always pays less than an NPC listing of the
	 * same stack costs, so buying from the system and selling back never profits.</li>
	 * </ol>
	 */
	public static long npcBuyCap(Listing listing, long unitMarketValue) {
		if (listing == null || listing.npc() || listing.seller() == null) return 0;
		ItemStack stack = listing.item(); // Copy: floor providers never see the stored stack
		if (holdsItems(stack)) return 0;
		UUID seller = listing.seller();
		Item item = stack.getItem();
		int count = Math.max(1, stack.getCount());
		long extra = extraSaleFloor(seller, stack);
		long ceiling = Math.max(durabilityShare(Services.marketValue(item).orElse(0), stack), extra / count);
		long unitValue = Math.min(Math.max(0, unitMarketValue), ceiling);
		double factor = Hooks.flag(Hooks.FLAG_AUCTION_FEVER) ? PriceGuard.NPC_BUY_MARKET_FACTOR_MAX : PriceGuard.NPC_BUY_MARKET_FACTOR;
		long cap = times(Math.max(0, PriceGuard.maxNpcBuyPrice(seller, item, unitValue, factor)), count);
		return Math.max(0, Math.min(cap, saleFloor(seller, stack, extra) - 1));
	}

	/** Minimum total of an NPC listing for this buyer ({@code null} at listing time, neutral buyer). */
	public static long npcSaleFloor(UUID buyer, ItemStack stack) {
		return saleFloor(buyer, stack, extraSaleFloor(buyer, stack));
	}

	/**
	 * Minimum total of an NPC bargain for this buyer ({@code null} at listing time). Bargains may undercut {@link
	 * #npcSaleFloor}: flipping them to players, NPC buyers and NPC orders is intended and limited by those
	 * budgets. They never go so low that /sell profits immediately: at least {@link #bargainHardFloor} and the
	 * maximum of the registered {@link NpcSaleFloor#minBargainTotalPrice}. With no registered floor, or if one
	 * throws, the normal floor applies.
	 */
	public static long npcBargainFloor(UUID buyer, ItemStack stack) {
		if (EXTRA_FLOORS.isEmpty()) return npcSaleFloor(buyer, stack);
		long total = bargainHardFloor(buyer, stack);
		for (NpcSaleFloor extra : List.copyOf(EXTRA_FLOORS)) {
			try {
				total = Math.max(total, extra.minBargainTotalPrice(buyer, stack.copy()));
			} catch (RuntimeException ex) {
				SmpEconomy.LOG.error("[SMPEco] Auction: bargain floor threw, using the normal floor", ex);
				return npcSaleFloor(buyer, stack);
			}
		}
		return Math.min(Math.max(1, total), Money.MAX);
	}

	/** Without an extra floor there is no bargain relaxation. */
	static boolean npcSaleFloorProviders() {
		return !EXTRA_FLOORS.isEmpty();
	}

	/**
	 * Hard floor of every bargain: per item this buyer's effective /sell price + 1 cent (pristine stacks only, the
	 * only ones /sell takes), for shop items at least pivot + 1 (every system sale is above the pivot), at least 1
	 * cent.
	 */
	static long bargainHardFloor(UUID buyer, ItemStack stack) {
		UUID who = buyer == null ? NEUTRAL : buyer;
		Item item = stack.getItem();
		long unit = 1;
		if (Items.isPristine(stack)) {
			OptionalLong sell = PriceGuard.effectiveSellPrice(who, item);
			if (sell.isPresent()) unit = Math.max(unit, Math.min(Money.MAX - 1, sell.getAsLong()) + 1);
		}
		long pivot = PriceGuard.pivotPrice(item);
		if (pivot >= 0) unit = Math.max(unit, Math.min(Money.MAX - 1, pivot) + 1);
		return times(unit, Math.max(1, stack.getCount()));
	}

	private static long saleFloor(UUID buyer, ItemStack stack, long extra) {
		Item item = stack.getItem();
		long market = Services.marketValue(item).orElse(0);
		long total = times(PriceGuard.minSystemSalePrice(buyer == null ? NEUTRAL : buyer, item, market), Math.max(1, stack.getCount()));
		total = Math.max(total, extra);
		return Math.min(Math.max(1, total), Money.MAX);
	}

	private static long extraSaleFloor(UUID buyer, ItemStack stack) {
		long max = 0;
		for (NpcSaleFloor extra : List.copyOf(EXTRA_FLOORS)) {
			try {
				max = Math.max(max, extra.minTotalPrice(buyer, stack.copy()));
			} catch (RuntimeException ex) {
				SmpEconomy.LOG.error("[SMPEco] Auction: NpcSaleFloor threw, ignored", ex);
			}
		}
		return max;
	}

	/**
	 * True if the stack holds other items or entities. No NPC buys these, since the contents would be destroyed
	 * with it.
	 */
	public static boolean holdsItems(ItemStack stack) {
		return Items.holdsItems(stack);
	}

	/** {@code value} prorated by remaining durability, rounded down. */
	static long durabilityShare(long value, ItemStack stack) {
		if (value <= 0) return 0;
		if (!stack.isDamaged()) return value;
		int max = stack.getMaxDamage();
		if (max <= 0) return value;
		long left = Math.max(0, max - stack.getDamageValue());
		return BigInteger.valueOf(value).multiply(BigInteger.valueOf(left)).divide(BigInteger.valueOf(max)).longValue();
	}

	/** Checks {@link #listFromHand} without changing anything, except that it cannot detect unencodable items. */
	static Result checkListFromHand(ServerPlayer seller, long price) {
		checkThread();
		if (!seller.isAlive() || seller.isSpectator() || seller.hasDisconnected()) return Result.NOT_ALLOWED;
		if (seller.isCreative() && !AuctionConfig.get().allowCreative) return Result.CREATIVE;
		ItemStack hand = seller.getInventory().getSelectedItem();
		if (hand.isEmpty()) return Result.NO_ITEM;
		return checkPlayerListing(seller.getUUID(), hand, price);
	}

	/** Lists the whole main hand stack; the price covers all of it. */
	public static CreateResult listFromHand(ServerPlayer seller, long price) {
		Result pre = checkListFromHand(seller, price);
		if (pre != Result.OK) return fail(pre);
		Inventory inv = seller.getInventory();
		int slot = inv.getSelectedSlot();
		ItemStack hand = inv.getItem(slot);
		if (hand.isEmpty()) return fail(Result.NO_ITEM);
		ItemStack taken = hand.copy();
		String encoded = encodeVerified(taken);
		if (encoded == null) return fail(Result.INVALID_ITEM);
		// Synchronous, right before the entry is added, so there is no in-between state
		inv.setItem(slot, ItemStack.EMPTY);
		inv.setChanged();
		Listing l = insert(seller.getUUID(), seller.getGameProfile().name(), false, SOURCE_PLAYER, taken, encoded, price, durationMs(), NpcOffer.NORMAL);
		DataStore.requestPlayerSave(seller);
		return new CreateResult(Result.OK, l);
	}

	/**
	 * Player listing with a stack already out of the inventory (escrow). On {@code OK} the stack belongs to the
	 * auction house, on failure still to the caller. {@code durationMs <= 0} uses the config duration. If the
	 * stack came from another inventory, the caller must call {@link DataStore#requestPlayerSave} for it.
	 */
	public static CreateResult createPlayerListing(UUID seller, ItemStack stack, long price, long durationMs) {
		checkThread();
		if (seller == null || EconomyService.isSystemAccount(seller)) return fail(Result.NOT_ALLOWED);
		if (stack == null || stack.isEmpty()) return fail(Result.NO_ITEM);
		long duration = durationMs <= 0 ? durationMs() : durationMs;
		if (duration > durationMs()) return fail(Result.INVALID_DURATION);
		Result pre = checkPlayerListing(seller, stack, price);
		if (pre != Result.OK) return fail(pre);
		ItemStack copy = stack.copy();
		String encoded = encodeVerified(copy);
		if (encoded == null) return fail(Result.INVALID_ITEM);
		String name = EconomyService.nameOf(seller).orElse("Unknown");
		Listing l = insert(seller, name, false, SOURCE_PLAYER, copy, encoded, price, duration, NpcOffer.NORMAL);
		// No global save of all players here: on another player's disconnect that would write every player file before
		// the world, see DataStore.
		DataStore.requestPlayerSave(seller);
		return new CreateResult(Result.OK, l);
	}

	/**
	 * NPC listing. The stack is copied; the real item is only created at purchase. A {@code price} below {@link
	 * #npcSaleFloor(UUID, ItemStack)} is raised, and the result holds the actual price.
	 */
	public static CreateResult createNpcListing(ItemStack stack, long price, long durationMs, String sellerName, String source) {
		return createNpcListing(stack, price, durationMs, sellerName, source, NpcOffer.NORMAL);
	}

	/**
	 * With {@code bargain}, {@code price} is raised only to {@link #npcBargainFloor}, and the purchase checks the
	 * buyer's bargain floor.
	 */
	public static CreateResult createNpcListing(ItemStack stack, long price, long durationMs, String sellerName, String source, NpcOffer offer) {
		checkThread();
		NpcOffer kind = offer == null ? NpcOffer.NORMAL : offer;
		AuctionConfig cfg = AuctionConfig.get();
		if (stack == null || stack.isEmpty()) return fail(Result.NO_ITEM);
		if (stack.getCount() > stack.getMaxStackSize()) return fail(Result.INVALID_ITEM); // Could never fit in an inventory, so it could not be bought
		if (!Money.isValidAmount(price) || price > cfg.maxPrice) return fail(Result.INVALID_PRICE);
		if (durationMs <= 0 || durationMs > cfg.maxNpcDurationHours * HOUR_MS) return fail(Result.INVALID_DURATION);
		if (npcCount() >= cfg.maxNpcListings) return fail(Result.LIMIT_REACHED);
		ItemStack copy = stack.copy();
		long effective = Math.max(price, kind.bargain() ? npcBargainFloor(null, copy) : npcSaleFloor(null, copy));
		if (effective > cfg.maxPrice) return fail(Result.INVALID_PRICE);
		Result size = checkSize(copy);
		if (size != Result.OK) return fail(size);
		String encoded = encodeVerified(copy);
		if (encoded == null) return fail(Result.INVALID_ITEM);
		String name = sellerName == null || sellerName.isBlank() ? "§e[Market] Trader" : sellerName;
		String src = source == null || source.isBlank() ? "npc" : source;
		return new CreateResult(Result.OK, insert(null, name, true, src, copy, encoded, effective, durationMs, kind));
	}

	private static Result checkPlayerListing(UUID seller, ItemStack stack, long price) {
		AuctionConfig cfg = AuctionConfig.get();
		if (stack.isEmpty()) return Result.NO_ITEM;
		if (cfg.isBlocked(stack.getItem())) return Result.INVALID_ITEM;
		if (!Money.isValidAmount(price) || price > cfg.maxPrice) return Result.INVALID_PRICE;
		if (activeCount(seller) >= cfg.maxListings) return Result.LIMIT_REACHED;
		if (claimCount(seller) >= cfg.maxClaims) return Result.CLAIM_BIN_FULL;
		return checkSize(stack);
	}

	private static Result checkSize(ItemStack stack) {
		int loreLines = stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines().size();
		int maxLore = AuctionConfig.get().maxLoreLines;
		if (loreLines > maxLore) {
			SmpEconomy.LOG.info("[SMPEco] Auction: {} rejected: {} lore lines (max {})", Items.id(stack.getItem()), loreLines, maxLore);
			return Result.ITEM_TOO_LARGE;
		}
		int limit = AuctionConfig.get().maxItemBytes;
		long size = itemBytes(stack, limit);
		if (size < 0) return Result.INVALID_ITEM;
		if (size > limit) {
			SmpEconomy.LOG.info("[SMPEco] Auction: {} rejected: over {} bytes of item data", Items.id(stack.getItem()), limit);
			return Result.ITEM_TOO_LARGE;
		}
		return Result.OK;
	}

	/**
	 * Item size in bytes as uncompressed NBT, as stored and about the size of the network packet. Stops counting
	 * at {@code limit + 1}; -1 when not encodable.
	 */
	static long itemBytes(ItemStack stack, long limit) {
		MinecraftServer server = SmpEconomy.server();
		if (server == null || stack.isEmpty()) return -1;
		Tag tag;
		try {
			tag = ItemStack.CODEC.encodeStart(server.registryAccess().createSerializationContext(NbtOps.INSTANCE), stack)
				.getOrThrow(msg -> new IllegalArgumentException("Item not encodable: " + msg));
		} catch (RuntimeException e) {
			SmpEconomy.LOG.warn("[SMPEco] Auction: cannot measure {}: {}", Items.id(stack.getItem()), e.toString());
			return -1;
		}
		ByteCounter counter = new ByteCounter(limit);
		try (DataOutputStream out = new DataOutputStream(counter)) {
			NbtIo.writeAnyTag(tag, out);
		} catch (LimitReached | UTFDataFormatException e) {
			return limit + 1; // UTFDataFormatException: a single string above 64 KiB
		} catch (IOException e) {
			SmpEconomy.LOG.warn("[SMPEco] Auction: cannot measure {}: {}", Items.id(stack.getItem()), e.toString());
			return -1;
		}
		return counter.count;
	}

	private static final class ByteCounter extends OutputStream {
		private final long limit;
		long count;

		ByteCounter(long limit) {
			this.limit = limit;
		}

		@Override
		public void write(int b) throws IOException {
			add(1);
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			add(len);
		}

		private void add(long n) throws LimitReached {
			count += n;
			if (count > limit) throw new LimitReached();
		}
	}

	private static final class LimitReached extends IOException {
		@Override
		public synchronized Throwable fillInStackTrace() {
			return this;
		}
	}

	/** Encodes and checks the round trip, which must give an exactly equal item; null means it cannot be listed. */
	static String encodeVerified(ItemStack stack) {
		try {
			String encoded = ItemCodec.encode(stack);
			ItemStack back = ItemCodec.decode(encoded);
			if (!ItemStack.matches(stack, back)) {
				SmpEconomy.LOG.warn("[SMPEco] Auction: {} does not survive storage unchanged, rejected", Items.id(stack.getItem()));
				return null;
			}
			return encoded;
		} catch (RuntimeException e) {
			SmpEconomy.LOG.warn("[SMPEco] Auction: cannot store {}: {}", Items.id(stack.getItem()), e.toString());
			return null;
		}
	}

	private static Listing insert(UUID seller, String sellerName, boolean npc, String source, ItemStack stack, String encoded, long price,
			long durationMs, NpcOffer offer) {
		long now = now();
		Entry e = new Entry();
		e.id = AuctionStore.nextId();
		e.seller = seller;
		e.sellerName = sellerName == null ? "" : sellerName;
		e.npc = npc;
		e.source = source;
		e.item = encoded;
		e.itemId = Items.id(stack.getItem());
		e.count = stack.getCount();
		e.price = price;
		e.createdAt = now;
		e.expiresAt = now + durationMs;
		e.bargain = npc && offer.bargain();
		e.note = npc ? offer.note() : "";
		AuctionStore.data().listings.add(e);
		AuctionStore.cache(e.id, stack);
		AuctionStore.markDirty();
		Listing l = view(e, stack);
		fireCreated(l);
		return l;
	}

	public static Result buyListing(ServerPlayer buyer, long id) {
		return buyListing(buyer, id, -1);
	}

	/** {@code expectedPrice >= 0} buys only at exactly this price (GUI confirmation). Everything is rechecked here. */
	public static Result buyListing(ServerPlayer buyer, long id, long expectedPrice) {
		checkThread();
		Entry e = AuctionStore.findListing(id);
		if (e == null || e.expiresAt <= now()) return Result.NOT_FOUND;
		UUID buyerId = buyer.getUUID();
		if (!e.npc && buyerId.equals(e.seller)) return Result.OWN_LISTING;
		if (!e.npc && e.seller == null) return Result.NOT_ALLOWED;
		if (!Items.canReceive(buyer)) return Result.NOT_ALLOWED;
		if (expectedPrice >= 0 && e.price != expectedPrice) return Result.PRICE_CHANGED;
		Optional<ItemStack> stackOpt = AuctionStore.stack(e);
		if (stackOpt.isEmpty()) return Result.ITEM_UNREADABLE;
		ItemStack stack = stackOpt.get();
		if (!Items.canFit(buyer, stack)) return Result.NO_SPACE;
		Listing view = view(e, stack);
		long price = e.price;
		long tax = 0;
		if (e.npc) {
			// NPC listings never go below this buyer's system sale floor, checked again at purchase. Bargains use their
			// own floor, never below this buyer's /sell price
			long floor = e.bargain ? npcBargainFloor(buyerId, stack) : npcSaleFloor(buyerId, stack);
			if (price < floor) {
				e.price = floor;
				AuctionStore.markDirty();
				return Result.PRICE_CHANGED;
			}
			if (!EconomyService.has(buyerId, price)) return Result.INSUFFICIENT_FUNDS;
			EconomyService.Result r = Treasury.collectFrom(buyerId, price, Treasury.R_NPC_SALE);
			if (!r.ok()) return r == EconomyService.Result.INSUFFICIENT_FUNDS ? Result.INSUFFICIENT_FUNDS : Result.MONEY_ERROR;
		} else {
			if (!EconomyService.has(buyerId, price)) return Result.INSUFFICIENT_FUNDS;
			tax = taxFor(price);
			Result paid = paySeller(buyerId, e.seller, price, tax);
			if (paid != Result.OK) return paid;
		}
		// Money is booked and space was checked
		AuctionStore.data().listings.remove(e);
		AuctionStore.forget(e.id);
		AuctionStore.markDirty();
		Items.giveOrDrop(buyer, stack);
		DataStore.requestPlayerSave(buyer);
		if (!e.npc) {
			notify(e.seller, "§7Sold ", stack, " §7to §f" + buyer.getGameProfile().name() + " §7for " + Msg.moneyStr(price)
				+ (tax > 0 ? " §8(tax " + Money.format(tax) + ")" : "") + "§7.");
		}
		fireSold(view, buyerId, price, tax);
		return Result.OK;
	}

	/** All or nothing. */
	private static Result paySeller(UUID buyer, UUID seller, long price, long tax) {
		EconomyService.Result w = EconomyService.withdraw(buyer, price);
		if (!w.ok()) return w == EconomyService.Result.INSUFFICIENT_FUNDS ? Result.INSUFFICIENT_FUNDS : Result.MONEY_ERROR;
		long net = price - tax;
		if (net > 0) {
			EconomyService.Result d = EconomyService.deposit(seller, net);
			if (!d.ok()) {
				EconomyService.Result back = EconomyService.deposit(buyer, price);
				if (!back.ok()) SmpEconomy.LOG.error("[SMPEco] Auction: refund of {} to {} failed: {}", Money.format(price), buyer, back);
				return Result.MONEY_ERROR;
			}
		}
		depositTax(tax);
		return Result.OK;
	}

	private static void depositTax(long tax) {
		if (tax <= 0) return;
		EconomyService.Result t = Treasury.deposit(tax, Treasury.R_AH_TAX);
		// A full treasury makes the tax vanish, which is acceptable deflation, but it gets logged
		if (!t.ok()) SmpEconomy.LOG.warn("[SMPEco] Auction: tax {} not booked to the treasury: {}", Money.format(tax), t);
	}

	/**
	 * Uses {@link Services#marketValue}, a per-item-type value, so only pristine stacks qualify; others get {@code
	 * NOT_NPC_TRADABLE}. Use the overload with an explicit market value for those.
	 */
	public static Result buyListingAsNpc(long id, NpcPayer payer) {
		checkThread();
		if (payer == null) throw new IllegalArgumentException("payer missing");
		Entry e = AuctionStore.findListing(id);
		if (e == null || e.expiresAt <= now()) return Result.NOT_FOUND;
		if (e.npc) return Result.NPC_LISTING;
		Optional<ItemStack> s = AuctionStore.stack(e);
		if (s.isEmpty()) return Result.ITEM_UNREADABLE;
		if (!Items.isPristine(s.get())) return Result.NOT_NPC_TRADABLE;
		return buyListingAsNpc(id, Services.marketValue(s.get().getItem()).orElse(0), payer);
	}

	/**
	 * Item is destroyed; the NPC keeps it. Fails on contents ({@code NOT_NPC_TRADABLE}) or a price above {@link
	 * #npcBuyCap} ({@code PRICE_ABOVE_CAP}).
	 */
	public static Result buyListingAsNpc(long id, long unitMarketValue, NpcPayer payer) {
		checkThread();
		if (payer == null) throw new IllegalArgumentException("payer missing");
		Entry e = AuctionStore.findListing(id);
		if (e == null || e.expiresAt <= now()) return Result.NOT_FOUND;
		if (e.npc) return Result.NPC_LISTING;
		if (e.seller == null) return Result.NOT_ALLOWED;
		Optional<ItemStack> stackOpt = AuctionStore.stack(e);
		if (stackOpt.isEmpty()) return Result.ITEM_UNREADABLE;
		ItemStack stack = stackOpt.get();
		if (holdsItems(stack)) return Result.NOT_NPC_TRADABLE;
		Listing view = view(e, stack);
		long price = e.price;
		if (price > npcBuyCap(view, unitMarketValue)) return Result.PRICE_ABOVE_CAP;
		boolean paid;
		try {
			paid = payer.withdraw(price);
		} catch (RuntimeException ex) {
			SmpEconomy.LOG.error("[SMPEco] Auction: NpcPayer threw, purchase aborted", ex);
			paid = false;
		}
		if (!paid) return Result.PAYMENT_FAILED;
		if (AuctionStore.findListing(id) != e) {
			// The payer changed the listing synchronously, so refund
			safeRefund(payer, price);
			return Result.NOT_FOUND;
		}
		long tax = taxFor(price);
		long net = price - tax;
		if (net > 0) {
			EconomyService.Result d = EconomyService.deposit(e.seller, net);
			if (!d.ok()) {
				safeRefund(payer, price);
				return Result.MONEY_ERROR;
			}
		}
		depositTax(tax);
		AuctionStore.data().listings.remove(e);
		AuctionStore.forget(e.id);
		AuctionStore.markDirty();
		notify(e.seller, "§7Sold ", stack, " §7to an §eNPC buyer §7for " + Msg.moneyStr(price)
			+ (tax > 0 ? " §8(tax " + Money.format(tax) + ")" : "") + "§7.");
		fireSold(view, null, price, tax);
		return Result.OK;
	}

	private static void safeRefund(NpcPayer payer, long cents) {
		try {
			payer.refund(cents);
		} catch (RuntimeException ex) {
			SmpEconomy.LOG.error("[SMPEco] Auction: NpcPayer refund of {} failed", Money.format(cents), ex);
		}
	}

	/** False if unknown or a player listing. */
	public static boolean removeNpcListing(long id) {
		checkThread();
		Entry e = AuctionStore.findListing(id);
		if (e == null || !e.npc) return false;
		Optional<Listing> view = view(e);
		AuctionStore.data().listings.remove(e);
		AuctionStore.forget(e.id);
		AuctionStore.markDirty();
		view.ifPresent(l -> fireRemoved(l, RemoveReason.NPC_REMOVED));
		return true;
	}

	public static int removeNpcListings(String source) {
		checkThread();
		List<Long> ids = new ArrayList<>();
		for (Entry e : AuctionStore.data().listings) {
			if (e.npc && (source == null || source.equals(e.source))) ids.add(e.id);
		}
		int n = 0;
		for (long id : ids) {
			if (removeNpcListing(id)) n++;
		}
		return n;
	}

	/** Item goes back to the inventory ({@code OK}) or, without space, to the claim bin ({@code STORED}). */
	public static Result cancelListing(ServerPlayer owner, long id) {
		checkThread();
		Entry e = AuctionStore.findListing(id);
		if (e == null) return Result.NOT_FOUND;
		if (e.npc || !owner.getUUID().equals(e.seller)) return Result.NOT_ALLOWED;
		Optional<ItemStack> stackOpt = AuctionStore.stack(e);
		if (stackOpt.isEmpty()) return Result.ITEM_UNREADABLE;
		ItemStack stack = stackOpt.get();
		Listing view = view(e, stack);
		if (!AuctionStore.data().listings.remove(e)) return Result.NOT_FOUND;
		Result result;
		if (Items.canReceive(owner) && Items.canFit(owner, stack)) {
			AuctionStore.forget(e.id);
			Items.giveOrDrop(owner, stack);
			DataStore.requestPlayerSave(owner);
			result = Result.OK;
		} else {
			toClaims(e, "cancelled");
			result = Result.STORED;
		}
		AuctionStore.markDirty();
		fireRemoved(view, RemoveReason.CANCELLED);
		return result;
	}

	/** Only with enough space. */
	public static Result claim(ServerPlayer owner, long claimId) {
		checkThread();
		Claim c = AuctionStore.findClaim(claimId);
		if (c == null || !owner.getUUID().equals(c.owner)) return Result.NOT_FOUND;
		if (!Items.canReceive(owner)) return Result.NOT_ALLOWED;
		Optional<ItemStack> stackOpt = AuctionStore.stack(c);
		if (stackOpt.isEmpty()) return Result.ITEM_UNREADABLE;
		ItemStack stack = stackOpt.get();
		if (!Items.canFit(owner, stack)) return Result.NO_SPACE;
		AuctionStore.data().claims.remove(c);
		AuctionStore.forget(c.id);
		AuctionStore.markDirty();
		Items.giveOrDrop(owner, stack);
		DataStore.requestPlayerSave(owner);
		return Result.OK;
	}

	/** Claims what fits, one at a time. Returns {claimed, left}. */
	public static int[] claimAll(ServerPlayer owner) {
		checkThread();
		List<Long> ids = new ArrayList<>();
		for (Claim c : AuctionStore.data().claims) {
			if (owner.getUUID().equals(c.owner)) ids.add(c.id);
		}
		int claimed = 0;
		for (long id : ids) {
			if (claim(owner, id) == Result.OK) claimed++;
		}
		return new int[] {claimed, ids.size() - claimed};
	}

	private static void toClaims(Entry e, String reason) {
		Claim c = new Claim();
		c.id = e.id;
		c.owner = e.seller;
		c.item = e.item;
		c.itemId = e.itemId;
		c.count = e.count;
		c.price = e.price;
		c.createdAt = now();
		c.reason = reason;
		AuctionStore.data().claims.add(c);
	}

	/** Moves expired player listings to the claim bin and deletes expired NPC listings. */
	public static int processExpired() {
		checkThread();
		long now = now();
		List<Entry> due = new ArrayList<>();
		for (Entry e : AuctionStore.data().listings) {
			if (e.expiresAt <= now && (e.npc || e.seller != null)) due.add(e);
		}
		int n = 0;
		for (Entry e : due) {
			if (AuctionStore.data().listings.contains(e)) {
				expire(e);
				n++;
			}
		}
		return n;
	}

	private static void expire(Entry e) {
		// A listener from an earlier expiry may already have removed it, which would otherwise put the item in the bin
		// twice
		if (!AuctionStore.data().listings.remove(e)) return;
		Optional<ItemStack> stack = AuctionStore.stack(e);
		Optional<Listing> view = stack.map(s -> view(e, s));
		if (e.npc) {
			AuctionStore.forget(e.id);
		} else {
			toClaims(e, "expired");
			if (stack.isPresent()) notify(e.seller, "§7Expired: ", stack.get(), " §7– claim it in §e/ah my§7.");
		}
		AuctionStore.markDirty();
		view.ifPresent(l -> fireRemoved(l, RemoveReason.EXPIRED));
	}

	static int forceExpire(UUID seller) {
		checkThread();
		long now = now();
		int n = 0;
		for (Entry e : AuctionStore.data().listings) {
			if (!e.npc && seller.equals(e.seller)) {
				e.expiresAt = Math.min(e.expiresAt, now - 1);
				n++;
			}
		}
		if (n > 0) {
			AuctionStore.markDirty();
			processExpired();
		}
		return n;
	}

	/** NPC listing is deleted; player listing goes to the seller's claim bin, so nothing is lost. */
	static boolean adminRemove(long id) {
		checkThread();
		Entry e = AuctionStore.findListing(id);
		if (e == null) return false;
		if (e.npc) {
			Optional<Listing> view = view(e);
			AuctionStore.data().listings.remove(e);
			AuctionStore.forget(e.id);
			AuctionStore.markDirty();
			view.ifPresent(l -> fireRemoved(l, RemoveReason.ADMIN));
			return true;
		}
		if (e.seller == null) return false;
		Optional<ItemStack> stack = AuctionStore.stack(e);
		Optional<Listing> view = stack.map(s -> view(e, s));
		if (!AuctionStore.data().listings.remove(e)) return false;
		toClaims(e, "admin");
		AuctionStore.markDirty();
		stack.ifPresent(s -> notify(e.seller, "§7Removed by an admin: ", s, " §7– claim it in §e/ah my§7."));
		view.ifPresent(l -> fireRemoved(l, RemoveReason.ADMIN));
		return true;
	}

	/**
	 * Deletes all of a player's listings and claims without refund, for selftest cleanup and purging test bots.
	 * With {@code log}, item data goes to the log as Base64 so it can be restored.
	 */
	static int purge(UUID owner, boolean log) {
		int n = 0;
		List<Entry> listings = new ArrayList<>();
		for (Entry e : AuctionStore.data().listings) {
			if (!e.npc && owner.equals(e.seller)) listings.add(e);
		}
		for (Entry e : listings) {
			if (log) SmpEconomy.LOG.warn("[SMPEco] Auction purge {}: listing #{} {}x {} {} item={}", owner, e.id, e.count, e.itemId, Money.format(e.price), e.item);
			Optional<Listing> view = view(e);
			if (!AuctionStore.data().listings.remove(e)) continue; // Already removed by a listener
			AuctionStore.forget(e.id);
			view.ifPresent(l -> fireRemoved(l, RemoveReason.ADMIN));
			n++;
		}
		List<Claim> claims = new ArrayList<>();
		for (Claim c : AuctionStore.data().claims) {
			if (owner.equals(c.owner)) claims.add(c);
		}
		for (Claim c : claims) {
			if (!AuctionStore.data().claims.remove(c)) continue;
			if (log) SmpEconomy.LOG.warn("[SMPEco] Auction purge {}: claim #{} {}x {} item={}", owner, c.id, c.count, c.itemId, c.item);
			AuctionStore.forget(c.id);
			n++;
		}
		if (n > 0) AuctionStore.markDirty();
		return n;
	}

	public static List<Listing> browse(String query, Sort sort, boolean playersOnly) {
		return browse(query, sort, playersOnly ? Filter.PLAYERS : Filter.ALL);
	}

	public static List<Listing> browse(String query, Sort sort, Filter filter) {
		Filter f = filter == null ? Filter.ALL : filter;
		List<Listing> list = new ArrayList<>();
		for (Listing l : activeListings()) {
			if (f == Filter.PLAYERS && l.npc()) continue;
			if (f == Filter.NPC && !l.npc()) continue;
			if (!AuctionSearch.matches(l.item, query)) continue;
			list.add(l);
		}
		Comparator<Listing> newest = Comparator.comparingLong(Listing::createdAt).thenComparingLong(Listing::id).reversed();
		switch (sort == null ? Sort.NEWEST : sort) {
			case PRICE_ASC -> list.sort(Comparator.comparingLong(Listing::price).thenComparing(newest));
			case PRICE_DESC -> list.sort(Comparator.comparingLong(Listing::price).reversed().thenComparing(newest));
			default -> list.sort(newest);
		}
		return list;
	}

	/** Includes unreadable ones with an empty {@code item}. */
	record OwnEntry(long id, boolean claim, ItemStack item, long price, long expiresAt, String reason) {}

	static List<OwnEntry> ownEntries(UUID owner) {
		List<OwnEntry> out = new ArrayList<>();
		List<Entry> mine = new ArrayList<>();
		for (Entry e : AuctionStore.data().listings) {
			if (!e.npc && owner.equals(e.seller)) mine.add(e);
		}
		mine.sort(Comparator.comparingLong((Entry e) -> e.createdAt).reversed());
		for (Entry e : mine) out.add(new OwnEntry(e.id, false, AuctionStore.stack(e).orElse(ItemStack.EMPTY), e.price, e.expiresAt, ""));
		for (Claim c : AuctionStore.data().claims) {
			if (owner.equals(c.owner)) out.add(new OwnEntry(c.id, true, AuctionStore.stack(c).orElse(ItemStack.EMPTY), c.price, 0, c.reason));
		}
		return out;
	}

	static long now() {
		return System.currentTimeMillis();
	}

	static long durationMs() {
		return AuctionConfig.get().durationHours * HOUR_MS;
	}

	private static Optional<Listing> view(Entry e) {
		return AuctionStore.stack(e).map(s -> view(e, s));
	}

	private static Listing view(Entry e, ItemStack stack) {
		String name = e.sellerName;
		if (!e.npc && e.seller != null) name = EconomyService.nameOf(e.seller).orElse(e.sellerName);
		return new Listing(e.id, e.seller, name == null ? "" : name, e.npc, e.source, stack, e.price, e.createdAt, e.expiresAt,
			e.npc && e.bargain, e.npc ? e.note : "");
	}

	static long times(long unit, int count) {
		try {
			return Math.min(Money.MAX, Math.multiplyExact(unit, (long) count));
		} catch (ArithmeticException ex) {
			return Money.MAX;
		}
	}

	private static CreateResult fail(Result r) {
		return new CreateResult(r, null);
	}

	private static void checkThread() {
		MinecraftServer s = SmpEconomy.server();
		if (s != null && !s.isSameThread()) throw new IllegalStateException("AuctionService is server thread only");
	}

	/** Component, so the client translates the name. */
	static MutableComponent itemText(ItemStack stack) {
		return Msg.legacy("§f" + stack.getCount() + "x ").append(Msg.itemName(stack).withStyle(ChatFormatting.WHITE));
	}

	/** Plain text for stored messages, in the server language. */
	static String itemPlain(ItemStack stack) {
		return "§f" + stack.getCount() + "x " + stack.getHoverName().getString();
	}

	/** Sent now if online, otherwise on join. Respects {@link Settings#NOTIFICATIONS}. */
	private static void notify(UUID player, String before, ItemStack stack, String after) {
		if (player == null || !Settings.get(player, Settings.NOTIFICATIONS)) return;
		MinecraftServer s = SmpEconomy.server();
		ServerPlayer online = s == null ? null : s.getPlayerList().getPlayer(player);
		if (online != null) {
			online.sendSystemMessage(Msg.prefixed(Msg.legacy(before).append(itemText(stack)).append(Msg.legacy(after))));
		} else {
			PlayerDataService.addPendingMessage(player, before + itemPlain(stack) + after);
		}
	}

	private static void fireCreated(Listing l) {
		for (Consumer<Listing> c : List.copyOf(CREATED)) {
			try {
				c.accept(l);
			} catch (RuntimeException ex) {
				SmpEconomy.LOG.error("[SMPEco] Auction: onListingCreated listener failed", ex);
			}
		}
	}

	private static void fireSold(Listing l, UUID buyer, long price, long tax) {
		for (SoldListener c : List.copyOf(SOLD)) {
			try {
				c.onSold(l, buyer, price, tax);
			} catch (RuntimeException ex) {
				SmpEconomy.LOG.error("[SMPEco] Auction: onListingSold listener failed", ex);
			}
		}
	}

	private static void fireRemoved(Listing l, RemoveReason reason) {
		for (RemovedListener c : List.copyOf(REMOVED)) {
			try {
				c.onRemoved(l, reason);
			} catch (RuntimeException ex) {
				SmpEconomy.LOG.error("[SMPEco] Auction: onListingRemoved listener failed", ex);
			}
		}
	}

	static String remaining(long expiresAt) {
		return TimeUtil.formatDuration(Math.max(0, expiresAt - now()));
	}

	static String lower(String s) {
		return s == null ? "" : s.toLowerCase(Locale.ROOT);
	}
}
