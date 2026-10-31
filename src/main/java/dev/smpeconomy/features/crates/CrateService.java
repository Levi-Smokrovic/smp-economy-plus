package dev.smpeconomy.features.crates;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.random.RandomGenerator;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.CrateKeys;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.ItemCodec;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.features.crates.CratesConfig.CrateDef;
import dev.smpeconomy.features.crates.CratesConfig.RewardDef;

/**
 * Opens crates and delivers rewards exactly once.
 *
 * <p>{@link #open} draws and fully builds the reward, then takes the key and stores the reward as pending.
 * Both live in {@code players.json} (counter and blob {@value #BLOB}), so one atomic write covers them, and
 * only then does the animation run. {@link #deliver} is the only place that credits a reward: it removes the
 * entry and then credits it, so a second call finds nothing. If an item cannot be handed over safely (dead,
 * disconnected, full inventory without drop permission, unreadable data), the entry stays and is delivered on
 * the next join or respawn or through /crates claim. Server thread only.
 */
final class CrateService {
	static final String BLOB = "crates.pending";
	/** Per crate: {@code crates.opened.<crate>}. */
	static final String OPENED_PREFIX = "crates.opened.";

	/** A decided reward not yet credited. */
	static final class PendingReward {
		String id;
		String crate;
		String entry;
		/** "money", "item" or "key". */
		String type;
		long money;
		/** ItemCodec template with count 1. */
		String item;
		int count;
		String key;
		boolean announce;
		long created;

		boolean isMoney() {
			return "money".equals(type);
		}

		boolean isKey() {
			return "key".equals(type);
		}

		boolean isItem() {
			return "item".equals(type);
		}
	}

	static final class PendingList {
		List<PendingReward> rewards = new ArrayList<>();
	}

	enum OpenStatus { OK, UNKNOWN_CRATE, NO_KEY, LOCKED, CONFIG_ERROR }

	record OpenResult(OpenStatus status, PendingReward reward, String error) {
		boolean ok() {
			return status == OpenStatus.OK;
		}
	}

	enum DeliverStatus {
		/** Credited; items may be partly dropped, see {@code dropped}. */
		DELIVERED,
		/** Does not exist or was already delivered. */
		NOT_FOUND,
		/** Inventory full without drop permission; stays pending. */
		KEPT_NO_SPACE,
		/** Player dead or disconnected; stays pending. */
		KEPT_UNAVAILABLE,
		/** Cannot be credited (account limit, broken item data); stays pending and is logged. */
		KEPT_ERROR
	}

	record DeliverResult(DeliverStatus status, PendingReward reward, int dropped) {
		boolean delivered() {
			return status == DeliverStatus.DELIVERED;
		}

		boolean kept() {
			return status == DeliverStatus.KEPT_NO_SPACE || status == DeliverStatus.KEPT_UNAVAILABLE || status == DeliverStatus.KEPT_ERROR;
		}
	}

	/** Admin override of the next reward, in memory only: player to crate to reward ID. */
	private static final Map<UUID, Map<String, String>> FORCED = new HashMap<>();
	/**
	 * Rewards with a running animation (ID to start time). The animation delivers them itself, at the latest on
	 * close, so join, respawn and claim delivery leave them alone. As a safety net they count as normal pending
	 * after {@link #ACTIVE_TIMEOUT_MS}.
	 */
	private static final Map<String, Long> ACTIVE = new HashMap<>();
	static final long ACTIVE_TIMEOUT_MS = 60_000;

	private CrateService() {}

	static OpenResult open(UUID player, String crateId, RandomGenerator random, HolderLookup.Provider registries) {
		if (CratesConfig.problem() != null) return new OpenResult(OpenStatus.LOCKED, null, CratesConfig.problem());
		Optional<CrateDef> crate = CratesConfig.crate(crateId);
		if (crate.isEmpty()) return new OpenResult(OpenStatus.UNKNOWN_CRATE, null, null);
		RewardDef forced = takeForced(player, crate.get());
		return open(player, crate.get(), forced, random, registries);
	}

	/**
	 * Draws or takes {@code forced}, builds the reward, takes one key and stores it as pending. A failed build
	 * takes no key.
	 */
	static OpenResult open(UUID player, CrateDef crate, RewardDef forced, RandomGenerator random, HolderLookup.Provider registries) {
		if (CrateKeys.count(player, crate.id) < 1) return new OpenResult(OpenStatus.NO_KEY, null, null);
		RewardDef def = forced != null ? forced : CrateRewards.roll(crate, random);
		PendingReward reward;
		try {
			reward = toPending(crate.id, def, registries);
		} catch (RuntimeException e) {
			SmpEconomy.LOG.error("[SMPEco] Crate {}: cannot build reward {}", crate.id, def.id, e);
			return new OpenResult(OpenStatus.CONFIG_ERROR, null, e.getMessage());
		}
		if (!CrateKeys.take(player, crate.id, 1)) return new OpenResult(OpenStatus.NO_KEY, null, null);
		try {
			List<PendingReward> list = pending(player);
			list.add(reward);
			savePending(player, list);
		} catch (RuntimeException e) {
			// Should not happen. Returns the key so nothing is lost
			CrateKeys.give(player, crate.id, 1);
			SmpEconomy.LOG.error("[SMPEco] Crate {}: could not store reward, key refunded", crate.id, e);
			return new OpenResult(OpenStatus.CONFIG_ERROR, null, e.getMessage());
		}
		PlayerDataService.addCounter(player, OPENED_PREFIX + crate.id, 1);
		DataStore.requestFlush(); // Reward and key removal reach disk at the end of this tick, before the animation ends
		return new OpenResult(OpenStatus.OK, reward, null);
	}

	static PendingReward toPending(String crateId, RewardDef def, HolderLookup.Provider registries) {
		PendingReward p = new PendingReward();
		p.id = UUID.randomUUID().toString();
		p.crate = crateId;
		p.entry = def.id;
		p.announce = def.announce;
		p.created = System.currentTimeMillis();
		switch (def.kind()) {
			case MONEY -> {
				p.type = "money";
				p.money = def.money;
			}
			case KEY -> {
				p.type = "key";
				p.key = def.key;
				p.count = def.effectiveCount();
				CrateKeys.key(def.key); // Throws on an invalid crate
			}
			case ITEM -> {
				p.type = "item";
				ItemStack template = CrateRewards.buildItem(def, registries);
				p.item = ItemCodec.encode(template, registries);
				p.count = def.effectiveCount();
			}
		}
		return p;
	}

	/**
	 * Credits pending reward {@code pendingId} exactly once.
	 *
	 * @param allowDrop the player is present (animation over, ESC), so what does not fit lands at their feet.
	 * False for disconnect, server stop, death, join and claim: the reward is delivered only if everything fits,
	 * otherwise it stays pending.
	 */
	static DeliverResult deliver(ServerPlayer player, String pendingId, boolean allowDrop, HolderLookup.Provider registries) {
		UUID id = player.getUUID();
		List<PendingReward> list = pending(id);
		PendingReward r = null;
		for (PendingReward p : list) {
			if (p.id != null && p.id.equals(pendingId)) {
				r = p;
				break;
			}
		}
		if (r == null) return new DeliverResult(DeliverStatus.NOT_FOUND, null, 0);
		if (r.isMoney()) {
			EconomyService.Result res = EconomyService.deposit(id, r.money);
			if (!res.ok()) {
				SmpEconomy.LOG.warn("[SMPEco] Crate reward {} for {} not credited: {}", r.money, player.getPlainTextName(), res);
				return new DeliverResult(DeliverStatus.KEPT_ERROR, r, 0);
			}
			list.remove(r);
			savePending(id, list);
			return new DeliverResult(DeliverStatus.DELIVERED, r, 0);
		}
		if (r.isKey()) {
			try {
				CrateKeys.give(id, r.key, Math.max(1, r.count));
			} catch (RuntimeException e) {
				SmpEconomy.LOG.error("[SMPEco] Crate reward (key {}) for {} not credited", r.key, player.getPlainTextName(), e);
				return new DeliverResult(DeliverStatus.KEPT_ERROR, r, 0);
			}
			list.remove(r);
			savePending(id, list);
			return new DeliverResult(DeliverStatus.DELIVERED, r, 0);
		}
		if (!r.isItem()) {
			SmpEconomy.LOG.error("[SMPEco] Crate reward {} of {} has unknown type '{}', stays saved", r.id, player.getPlainTextName(), r.type);
			return new DeliverResult(DeliverStatus.KEPT_ERROR, r, 0);
		}
		Optional<ItemStack> template = decode(r, registries);
		if (template.isEmpty()) return new DeliverResult(DeliverStatus.KEPT_ERROR, r, 0);
		if (!Items.canReceive(player)) return new DeliverResult(DeliverStatus.KEPT_UNAVAILABLE, r, 0);
		ItemStack stack = template.get().copy();
		stack.setCount(Math.max(1, r.count)); // Items.giveOrDrop splits it into stacks
		if (!allowDrop && !Items.canFit(player, stack)) return new DeliverResult(DeliverStatus.KEPT_NO_SPACE, r, 0);
		// Remove first, then give: a second call (close + animation end, join + claim) finds nothing
		list.remove(r);
		savePending(id, list);
		int dropped = Items.giveOrDrop(player, stack);
		DataStore.requestPlayerSave(player);
		return new DeliverResult(DeliverStatus.DELIVERED, r, dropped);
	}

	/** Skips rewards of a running animation. */
	static List<DeliverResult> deliverAll(ServerPlayer player, boolean allowDrop, HolderLookup.Provider registries) {
		List<DeliverResult> results = new ArrayList<>();
		for (PendingReward p : pending(player.getUUID())) {
			if (p.id == null || isActive(p.id)) continue;
			results.add(deliver(player, p.id, allowDrop, registries));
		}
		return results;
	}

	static int deliverableCount(UUID player) {
		int n = 0;
		for (PendingReward p : pending(player)) {
			if (p.id != null && !isActive(p.id)) n++;
		}
		return n;
	}

	static void markActive(String pendingId) {
		ACTIVE.put(pendingId, System.currentTimeMillis());
	}

	static void unmarkActive(String pendingId) {
		ACTIVE.remove(pendingId);
	}

	static boolean isActive(String pendingId) {
		Long since = ACTIVE.get(pendingId);
		if (since == null) return false;
		if (System.currentTimeMillis() - since > ACTIVE_TIMEOUT_MS) {
			ACTIVE.remove(pendingId);
			return false;
		}
		return true;
	}

	static Optional<ItemStack> decode(PendingReward r, HolderLookup.Provider registries) {
		if (r.item == null) return Optional.empty();
		try {
			return Optional.of(ItemCodec.decode(r.item, registries));
		} catch (IllegalArgumentException e) {
			// Never delete, that would lose the item: keep it and log
			SmpEconomy.LOG.error("[SMPEco] Crate reward {} (crate {}) unreadable, stays saved: {}", r.id, r.crate, e.getMessage());
			return Optional.empty();
		}
	}

	/** Mutable copy, in order of creation. */
	static List<PendingReward> pending(UUID player) {
		PendingList l = PlayerDataService.getBlob(player, BLOB, PendingList.class);
		if (l == null || l.rewards == null) return new ArrayList<>();
		List<PendingReward> list = new ArrayList<>(l.rewards.size());
		for (PendingReward p : l.rewards) {
			if (p != null) list.add(p);
		}
		return list;
	}

	static int pendingCount(UUID player) {
		return pending(player).size();
	}

	static void savePending(UUID player, List<PendingReward> list) {
		if (list.isEmpty()) {
			if (PlayerDataService.getBlob(player, BLOB, PendingList.class) != null) PlayerDataService.remove(player, BLOB);
		} else {
			PendingList l = new PendingList();
			l.rewards = new ArrayList<>(list);
			PlayerDataService.setBlob(player, BLOB, l);
		}
		DataStore.requestFlush();
	}

	/** One-shot and in memory only. */
	static void force(UUID player, String crateId, String rewardId) {
		FORCED.computeIfAbsent(player, k -> new HashMap<>()).put(crateId, rewardId);
	}

	static void clearForced(UUID player) {
		FORCED.remove(player);
	}

	private static RewardDef takeForced(UUID player, CrateDef crate) {
		Map<String, String> m = FORCED.get(player);
		if (m == null) return null;
		String rewardId = m.remove(crate.id);
		if (m.isEmpty()) FORCED.remove(player);
		return rewardId == null ? null : crate.reward(rewardId).orElse(null);
	}

	static MutableComponent describe(PendingReward r, HolderLookup.Provider registries) {
		if (r.isMoney()) return Msg.legacy(CrateRewards.moneyText(r.money));
		if (r.isKey()) return Msg.legacy(CrateRewards.keyText(r.key, Math.max(1, r.count)));
		Optional<ItemStack> t = r.item == null ? Optional.empty() : quietDecode(r.item, registries);
		return t.map(s -> CrateRewards.itemText(s, Math.max(1, r.count))).orElseGet(() -> Msg.legacy("§f" + r.count + "× " + r.entry));
	}

	private static Optional<ItemStack> quietDecode(String data, HolderLookup.Provider registries) {
		try {
			return Optional.of(ItemCodec.decode(data, registries));
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}
}
