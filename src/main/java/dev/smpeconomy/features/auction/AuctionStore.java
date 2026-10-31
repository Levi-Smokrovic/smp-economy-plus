package dev.smpeconomy.features.auction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.ItemCodec;

/**
 * Persistence of {@code auctions.json}: active listings and the claim bin. Items are {@link ItemCodec}
 * strings, with decoded stacks cached per entry. Undecodable entries are never deleted, which would lose the
 * item; they are skipped and logged.
 */
final class AuctionStore {
	static final class Entry {
		long id;
		/** Null for NPC listings. */
		UUID seller;
		/** Player name at listing time, or NPC name with color codes. */
		String sellerName = "";
		boolean npc;
		/** "player" or the NPC listing's source, for example "market". */
		String source = AuctionService.SOURCE_PLAYER;
		/** ItemCodec Base64. */
		String item = "";
		/** For display and diagnostics without decoding. */
		String itemId = "";
		int count;
		long price;
		long createdAt;
		long expiresAt;
		/**
		 * NPC listings only: the bargain floor ({@link AuctionService#npcBargainFloor}) applies at purchase. False in
		 * old files.
		 */
		boolean bargain;
		/** NPC listings only: extra display line from the source, empty for none. */
		String note = "";
	}

	/** {@code id} is the original listing's ID. */
	static final class Claim {
		long id;
		UUID owner;
		String item = "";
		String itemId = "";
		int count;
		/** Original listing price, for display. */
		long price;
		/** When the item entered the claim bin. */
		long createdAt;
		/** "expired", "cancelled" or "admin". */
		String reason = "expired";
	}

	static final class Data {
		long nextId = 1;
		List<Entry> listings = new ArrayList<>();
		List<Claim> claims = new ArrayList<>();
	}

	static final DataStore<Data> STORE = new DataStore<>("auctions.json", Data.class, Data::new);

	/** Keyed by entry ID, shared by listings and claims. Never hand out values; always copy. */
	private static final Map<Long, ItemStack> DECODED = new HashMap<>();
	/** Unreadable IDs, so each is logged only once. */
	private static final Set<Long> BROKEN = new HashSet<>();

	static {
		STORE.onLoad(d -> {
			DECODED.clear();
			BROKEN.clear();
			if (repair(d)) STORE.markDirty();
		});
	}

	private AuctionStore() {}

	static Data data() {
		return STORE.get();
	}

	static void markDirty() {
		STORE.markDirty();
	}

	/**
	 * After load: removes nulls, reassigns duplicate IDs (never deletes), moves nextId past all IDs. Returns true
	 * if the file needs writing back.
	 */
	static boolean repair(Data d) {
		boolean changed = false;
		if (d.listings == null) {
			d.listings = new ArrayList<>();
			changed = true;
		}
		if (d.claims == null) {
			d.claims = new ArrayList<>();
			changed = true;
		}
		changed |= d.listings.removeIf(e -> e == null);
		changed |= d.claims.removeIf(c -> c == null);
		long max = 0;
		for (Entry e : d.listings) max = Math.max(max, e.id);
		for (Claim c : d.claims) max = Math.max(max, c.id);
		if (d.nextId <= max) d.nextId = max + 1;
		if (d.nextId < 1) d.nextId = 1;
		Set<Long> seen = new HashSet<>();
		for (Entry e : d.listings) {
			if (e.item == null) e.item = "";
			if (e.sellerName == null) e.sellerName = "";
			if (e.source == null) e.source = e.npc ? "npc" : AuctionService.SOURCE_PLAYER;
			if (e.itemId == null) e.itemId = "";
			if (e.note == null) e.note = "";
			if (!e.npc && (e.bargain || !e.note.isEmpty())) {
				// Bargains and notes only exist on NPC listings; player listings have no floor
				e.bargain = false;
				e.note = "";
				changed = true;
			}
			if (!e.npc && e.seller == null) {
				// Player listing without a seller: keep it, deleting would lose the item, and let an admin decide
				SmpEconomy.LOG.error("[SMPEco] Auction #{} has no seller", e.id);
			}
			if (e.id <= 0 || !seen.add(e.id)) {
				long old = e.id;
				e.id = d.nextId++;
				seen.add(e.id);
				changed = true;
				SmpEconomy.LOG.warn("[SMPEco] Auction: duplicate or invalid ID {}, now {}", old, e.id);
			}
		}
		for (Claim c : d.claims) {
			if (c.item == null) c.item = "";
			if (c.itemId == null) c.itemId = "";
			if (c.reason == null) c.reason = "expired";
			if (c.id <= 0 || !seen.add(c.id)) {
				long old = c.id;
				c.id = d.nextId++;
				seen.add(c.id);
				changed = true;
				SmpEconomy.LOG.warn("[SMPEco] Auction claim bin: duplicate or invalid ID {}, now {}", old, c.id);
			}
		}
		return changed;
	}

	static long nextId() {
		Data d = data();
		long id = d.nextId;
		d.nextId = id + 1;
		return id;
	}

	/** A copy, or empty when the data is unreadable. */
	static Optional<ItemStack> stack(long id, String encoded) {
		ItemStack cached = DECODED.get(id);
		if (cached != null) return Optional.of(cached.copy());
		if (BROKEN.contains(id)) return Optional.empty();
		try {
			ItemStack s = ItemCodec.decode(encoded);
			if (s.isEmpty()) throw new IllegalArgumentException("empty stack");
			DECODED.put(id, s);
			return Optional.of(s.copy());
		} catch (RuntimeException e) {
			BROKEN.add(id);
			SmpEconomy.LOG.error("[SMPEco] Auction #{}: item data unreadable ({}), entry kept", id, e.getMessage());
			return Optional.empty();
		}
	}

	static Optional<ItemStack> stack(Entry e) {
		return stack(e.id, e.item);
	}

	static Optional<ItemStack> stack(Claim c) {
		return stack(c.id, c.item);
	}

	/** For a freshly encoded, already checked item. */
	static void cache(long id, ItemStack stack) {
		DECODED.put(id, stack.copy());
		BROKEN.remove(id);
	}

	/** Claims keep their listing's ID, so only call this when the entry is gone for good. */
	static void forget(long id) {
		DECODED.remove(id);
		BROKEN.remove(id);
	}

	static Entry findListing(long id) {
		for (Entry e : data().listings) {
			if (e.id == id) return e;
		}
		return null;
	}

	static Claim findClaim(long id) {
		for (Claim c : data().claims) {
			if (c.id == id) return c;
		}
		return null;
	}
}
