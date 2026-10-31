package dev.smpeconomy.features.sell;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.ItemCodec;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.PlayerDataService;

/**
 * Crash journal and return store of the sell GUI ({@code sell_sessions.json}).
 * <p>Open sessions: while items sit in the temporary container, a copy is kept here. Each change also requests {@link
 * DataStore#requestPlayerSave(ServerPlayer)} so journal and player inventory land in the same flush: items are either
 * in the saved inventory or in the journal. A regular end removes the entry in the same tick as settling. Entries
 * left after a restart go back to the player unsold.
 * <p>Return store ({@link Entry#pendingReturn}, one entry per player): whatever doesn't fit in the inventory on
 * return is never dropped but stored here, in the same tick as settling and so in the same flush as account and
 * inventory.
 * <p>Returning ({@link #deliver}): on join, after respawn and every second ({@link #sweep}), only whole stacks that
 * fit completely. Undecodable items stay in place, so nothing is ever lost.
 */
final class SellJournal {
	static final class Entry {
		UUID player;
		String name = "";
		long since;
		/** True for the return store, otherwise the contents of a sell GUI. */
		boolean pendingReturn;
		List<String> items = new ArrayList<>();
	}

	static final class Data {
		Map<String, Entry> sessions = new LinkedHashMap<>();
	}

	private static final DataStore<Data> STORE = new DataStore<>("sell_sessions.json", Data.class, Data::new);
	/** Sessions of this server run; anything else in the journal is from a crash or is a return store. */
	private static final Set<String> LIVE = new HashSet<>();
	static final String RETURN_PREFIX = "return-";
	/** Undecodable data seen this run: log once rather than retrying every second. */
	private static final Set<String> UNDECODABLE = new HashSet<>();

	static {
		STORE.onLoad(data -> {
			if (data.sessions == null) data.sessions = new LinkedHashMap<>();
			data.sessions.values().removeIf(e -> e == null || e.player == null);
			data.sessions.values().forEach(e -> {
				if (e.items == null) e.items = new ArrayList<>();
				if (e.name == null) e.name = "";
			});
			LIVE.clear();
			UNDECODABLE.clear();
			List<String> crashed = data.sessions.values().stream().filter(e -> !e.pendingReturn).map(e -> e.name + "(" + e.items.size() + ")").toList();
			List<String> waiting = data.sessions.values().stream().filter(e -> e.pendingReturn).map(e -> e.name + "(" + e.items.size() + ")").toList();
			if (!crashed.isEmpty()) {
				SmpEconomy.LOG.warn("[SMPEco] sell: {} sell menu(s) from a crash found, items return on next join: {}",
					crashed.size(), crashed);
			}
			if (!waiting.isEmpty()) SmpEconomy.LOG.info("[SMPEco] sell: return store waiting for space for: {}", waiting);
		});
	}

	private SellJournal() {}

	/** Loads the class so the DataStore loads with server start. */
	static void bootstrap() {
	}

	/**
	 * Empty session removes the entry.
	 * @return true if fully saved: journal loaded and every stack encodable
	 */
	static boolean update(String sessionId, ServerPlayer player, List<ItemStack> stacks) {
		if (!STORE.isLoaded()) return false;
		LIVE.add(sessionId);
		Data data = STORE.get();
		if (stacks.isEmpty()) {
			if (data.sessions.remove(sessionId) != null) {
				STORE.markDirty();
				DataStore.requestPlayerSave(player);
			}
			return true;
		}
		Entry e = new Entry();
		e.player = player.getUUID();
		e.name = player.getGameProfile().name();
		e.since = System.currentTimeMillis();
		boolean complete = true;
		for (ItemStack s : stacks) {
			try {
				e.items.add(ItemCodec.encode(s));
			} catch (RuntimeException ex) {
				// can't be saved: the session goes on normally and returns on close
				complete = false;
				SmpEconomy.LOG.error("[SMPEco] sell: journal cannot save {}x {}", s.getCount(), Items.id(s.getItem()), ex);
			}
		}
		data.sessions.put(sessionId, e);
		STORE.markDirty();
		DataStore.requestPlayerSave(player);
		return complete;
	}

	static void remove(String sessionId) {
		LIVE.remove(sessionId);
		if (STORE.isLoaded() && STORE.get().sessions.remove(sessionId) != null) STORE.markDirty();
	}

	static String returnKey(UUID player) {
		return RETURN_PREFIX + player;
	}

	/**
	 * Call in the same tick as settling, for the same flush as account and inventory.
	 * @return stacks that could NOT be saved (journal not loaded, not encodable); the caller must return them another
	 * way
	 */
	static List<ItemStack> addReturn(ServerPlayer player, List<ItemStack> stacks) {
		if (!STORE.isLoaded()) return List.copyOf(stacks);
		List<String> encoded = new ArrayList<>();
		List<ItemStack> failed = new ArrayList<>();
		int count = 0;
		for (ItemStack s : stacks) {
			if (s.isEmpty()) continue;
			try {
				encoded.add(ItemCodec.encode(s));
				count += s.getCount();
			} catch (RuntimeException ex) {
				failed.add(s);
				SmpEconomy.LOG.error("[SMPEco] sell: return store cannot save {}x {}", s.getCount(), Items.id(s.getItem()), ex);
			}
		}
		if (encoded.isEmpty()) return failed;
		String name = player.getGameProfile().name();
		returnEntry(STORE.get(), player.getUUID(), name).items.addAll(encoded);
		STORE.markDirty();
		DataStore.requestPlayerSave(player);
		SmpEconomy.LOG.info("[SMPEco] sell: {} items of {} don't fit in the inventory, in the return store", count, name);
		return failed;
	}

	private static Entry returnEntry(Data data, UUID player, String name) {
		Entry e = data.sessions.get(returnKey(player));
		if (e == null) {
			e = new Entry();
			e.player = player;
			e.since = System.currentTimeMillis();
			data.sessions.put(returnKey(player), e);
		}
		e.pendingReturn = true;
		if (name != null && !name.isEmpty()) e.name = name;
		return e;
	}

	static int restore(ServerPlayer player) {
		return deliver(player, true);
	}

	static int deliverNow(ServerPlayer player) {
		return deliver(player, false);
	}

	/**
	 * Returns all of this player's entries that don't belong to a running session, only whole stacks that fit
	 * completely ({@link SellService#placeBack}), never as a drop. Whatever doesn't fit or decode stays. A partly
	 * returned crash leftover moves to the return store. Inventory and journal change in the same tick.
	 * @param join uses pending messages for notices and reminds about waiting items even with no return
	 * @return number of items returned
	 */
	static int deliver(ServerPlayer player, boolean join) {
		if (!STORE.isLoaded() || !Items.canReceive(player)) return 0;
		UUID id = player.getUUID();
		Data data = STORE.get();
		Inventory inv = player.getInventory();
		int fromCrash = 0;
		int fromReturn = 0;
		int waiting = 0;
		boolean crashSeen = false;
		boolean changed = false;
		// return entry first: crash leftovers get appended there afterwards without being counted again in the same
		// pass
		List<String> keys = new ArrayList<>();
		for (Map.Entry<String, Entry> me : data.sessions.entrySet()) {
			if (LIVE.contains(me.getKey()) || !id.equals(me.getValue().player)) continue;
			if (me.getKey().equals(returnKey(id))) keys.add(0, me.getKey());
			else keys.add(me.getKey());
		}
		for (String key : keys) {
			Entry e = data.sessions.get(key);
			if (e == null) continue;
			if (!e.pendingReturn) crashSeen = true;
			List<String> left = new ArrayList<>();
			int got = 0;
			for (String raw : e.items) {
				ItemStack s = decode(key, e, raw);
				if (s == null) {
					left.add(raw); // undecodable: never delete
					continue;
				}
				if (s.isEmpty()) continue;
				if (SellService.returnSpace(inv, s) < s.getCount()) {
					left.add(raw);
					waiting += s.getCount();
					continue;
				}
				ItemStack rest = SellService.placeBack(inv, s, -1);
				got += s.getCount() - rest.getCount();
				if (!rest.isEmpty()) keepRest(player, rest, left); // can't happen, space was checked
			}
			if (got == 0 && left.size() == e.items.size()) continue;
			if (e.pendingReturn) fromReturn += got;
			else fromCrash += got;
			changed = true;
			if (left.isEmpty()) {
				data.sessions.remove(key);
			} else if (e.pendingReturn) {
				e.items = left;
			} else {
				// the rest of a crashed menu now waits in the return store (one entry per player)
				data.sessions.remove(key);
				returnEntry(data, id, e.name).items.addAll(left);
			}
			SmpEconomy.LOG.info("[SMPEco] sell: {} items from {} returned to {} ({} entries open)", got,
				key.startsWith(RETURN_PREFIX) ? "the return store" : "interrupted sell menu " + key, e.name, left.size());
		}
		if (changed) {
			STORE.markDirty();
			DataStore.requestPlayerSave(player);
			DataStore.requestFlush();
		}
		List<String> notes = new ArrayList<>();
		if (fromCrash > 0) {
			notes.add("§eYour sell menu was interrupted by a restart – §f" + SellService.items(fromCrash) + " §e"
				+ (fromCrash == 1 ? "was" : "were") + " returned to you unsold.");
		} else if (crashSeen && join && waiting > 0) {
			notes.add("§eYour sell menu was interrupted by a restart – your items are kept for you unsold.");
		}
		if (fromReturn > 0) {
			notes.add("§f" + SellService.items(fromReturn) + " §afrom the sell menu " + (fromReturn == 1 ? "is" : "are")
				+ " now in your inventory.");
		}
		if (waiting > 0 && (join || fromCrash + fromReturn > 0)) notes.add(waitingText(waiting));
		for (String n : notes) {
			if (!join && SellService.canReceiveChat(player)) Msg.info(player, n);
			else PlayerDataService.addPendingMessage(id, n);
		}
		return fromCrash + fromReturn;
	}

	static String waitingText(int n) {
		return "§f" + SellService.items(n) + " §e" + (n == 1 ? "is" : "are") + " still waiting for space in your inventory – "
			+ (n == 1 ? "it comes" : "they come") + " back automatically.";
	}

	/** Only falls back to giveOrDrop when not encodable. */
	private static void keepRest(ServerPlayer player, ItemStack rest, List<String> left) {
		try {
			left.add(ItemCodec.encode(rest));
		} catch (RuntimeException ex) {
			SmpEconomy.LOG.error("[SMPEco] sell: remainder {}x {} for {} cannot be stored, using giveOrDrop", rest.getCount(), Items.id(rest.getItem()),
				player.getPlainTextName(), ex);
			Items.giveOrDrop(player, rest);
		}
	}

	private static ItemStack decode(String key, Entry e, String raw) {
		if (raw == null || UNDECODABLE.contains(raw)) return null;
		try {
			return ItemCodec.decode(raw);
		} catch (RuntimeException ex) {
			UNDECODABLE.add(raw);
			SmpEconomy.LOG.error("[SMPEco] sell: journal entry {} of {} holds undecodable item data, left in place, needs admin review: {}",
				key, e.name, ex.getMessage());
			return null;
		}
	}

	static void sweep(MinecraftServer server) {
		if (server == null || !STORE.isLoaded()) return;
		Map<String, Entry> sessions = STORE.get().sessions;
		if (sessions.isEmpty()) return;
		Set<UUID> players = new LinkedHashSet<>();
		for (Map.Entry<String, Entry> me : sessions.entrySet()) {
			if (!LIVE.contains(me.getKey())) players.add(me.getValue().player);
		}
		for (UUID id : players) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p == null || p.hasDisconnected() || !Items.canReceive(p)) continue;
			try {
				deliverNow(p);
			} catch (RuntimeException ex) {
				SmpEconomy.LOG.error("[SMPEco] sell: return to {} failed", p.getPlainTextName(), ex);
			}
		}
	}

	static int returnsOf(UUID player) {
		int n = 0;
		if (!STORE.isLoaded()) return 0;
		for (Entry e : STORE.get().sessions.values()) {
			if (e.pendingReturn && player.equals(e.player)) n++;
		}
		return n;
	}

	static int waitingItemsOf(UUID player) {
		if (!STORE.isLoaded()) return 0;
		int n = 0;
		for (Map.Entry<String, Entry> me : STORE.get().sessions.entrySet()) {
			Entry e = me.getValue();
			if (LIVE.contains(me.getKey()) || !player.equals(e.player)) continue;
			for (String raw : e.items) {
				ItemStack s = decode(me.getKey(), e, raw);
				if (s != null) n += s.getCount();
			}
		}
		return n;
	}

	static int entriesOf(UUID player) {
		int n = 0;
		for (Entry e : STORE.get().sessions.values()) {
			if (player.equals(e.player)) n++;
		}
		return n;
	}

	static void removeAllOf(UUID player) {
		if (STORE.get().sessions.values().removeIf(e -> player.equals(e.player))) STORE.markDirty();
	}

	static void forgetLive(String sessionId) {
		LIVE.remove(sessionId);
	}

	static void putRaw(String sessionId, UUID player, String name, List<String> items) {
		Entry e = new Entry();
		e.player = player;
		e.name = name;
		e.since = System.currentTimeMillis();
		e.items = new ArrayList<>(items);
		STORE.get().sessions.put(sessionId, e);
		STORE.markDirty();
	}
}
