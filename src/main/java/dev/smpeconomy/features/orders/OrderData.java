package dev.smpeconomy.features.orders;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.ItemCodec;
import dev.smpeconomy.core.Items;

/**
 * Persistent state of orders.json. Money in cents.
 * <p>Invariants. Player order: {@code escrow == (amount - delivered) × price}. NPC bulk order: {@code escrow >=
 * (amount - delivered) × price}, since deliveries pay at most the order price and the rest returns to the treasury at
 * the end. Board order ({@link Order#board}): no escrow and no fixed price. Delivered items of player orders stay in
 * {@link #claims} until picked up, also after the order has ended.
 */
public final class OrderData {
	public long nextId = 1;
	public Map<Long, Order> orders = new LinkedHashMap<>();
	public Map<UUID, List<Claim>> claims = new HashMap<>();
	public Map<UUID, List<AwayNote>> away = new HashMap<>();
	/** Kept while no order is active. */
	public Map<String, Supply> supply = new HashMap<>();
	public Map<UUID, Bonus> bonus = new HashMap<>();
	public BoardStats boardStats = new BoardStats();

	public static final class Order {
		public long id;
		public UUID owner;
		public boolean npc;
		public boolean bulk;
		/**
		 * No escrow, {@code price} = 0. Each delivery pays the dynamic price ({@link NpcBoard#payout}) as new money,
		 * like /sell.
		 */
		public boolean board;
		/** Factor on the /sell reference, e.g. 1.05 to 1.35. */
		public double demand;
		public String label;
		public String item = "";
		public int amount;
		public int delivered;
		/** Cents. Bulk order: maximum, also capped through PriceGuard on delivery. Board: 0. */
		public long price;
		public long escrow;
		public long createdAt;
		public long expiresAt;
		/** Allows only enchantments and repair cost, no name or damage. Player orders only. */
		public boolean allowEnchanted;
		/**
		 * {@link ItemCodec} prototype with only property components ({@link OrderBook#templateOf}). Deliveries must
		 * have exactly these. Null means item type only.
		 */
		public String spec;

		private transient String specKey;
		private transient Optional<ItemStack> specValue;

		public int open() {
			return Math.max(0, amount - delivered);
		}

		public Optional<Item> itemType() {
			return Items.byId(item);
		}

		/** Empty without a template or if it's unreadable, in which case nothing matches. */
		public Optional<ItemStack> specStack() {
			if (spec == null) return Optional.empty();
			if (specValue == null || !spec.equals(specKey)) {
				if (SmpEconomy.server() == null) return Optional.empty(); // registries missing: don't cache
				specKey = spec;
				specValue = ItemCodec.tryDecode(spec).filter(s -> !s.isEmpty()).map(s -> s.copyWithCount(1));
				if (specValue.isEmpty()) SmpEconomy.LOG.error("[SMPEco] orders: template of order #{} unreadable, accepts nothing", id);
			}
			return specValue.map(ItemStack::copy);
		}

		public boolean isOwner(UUID player) {
			return !npc && owner != null && owner.equals(player);
		}
	}

	public static final class Claim {
		public String item = "";
		public String nbt;
		public long count;

		public Claim() {}

		public Claim(String item, String nbt, long count) {
			this.item = item;
			this.nbt = nbt;
			this.count = count;
		}
	}

	public static final class AwayNote {
		public long orderId;
		public String item = "";
		public long count;
		public long cents;
	}

	/**
	 * How far (in factor points) demand of all buyers of this item sits below base. {@code pressure} is valid at time
	 * {@code at} and decays exponentially after that; computed on read, stored only on delivery.
	 */
	public static final class Supply {
		public double pressure;
		public long at;
		public long cooldownUntil;
		public long delivered;

		public Supply() {}

		public double current(long now, double halfLifeMs) {
			if (!(pressure > 0)) return 0;
			if (!(halfLifeMs > 0) || now <= at) return pressure;
			return pressure * Math.pow(0.5, (now - at) / halfLifeMs);
		}

		void settle(long now, double halfLifeMs) {
			pressure = current(now, halfLifeMs);
			if (pressure < 1e-9) pressure = 0;
			at = Math.max(at, now);
		}
	}

	/** Bonus above their own /sell price a player has received on {@code day} (yyyy-MM-dd, server time zone). */
	public static final class Bonus {
		public String day = "";
		public long cents;
	}

	public static final class BoardStats {
		public String day = "";
		public long paidToday;
		public long bonusToday;
		public long unitsToday;
		public long deliveriesToday;
		public long paidTotal;
		public long bonusTotal;
		public long unitsTotal;
		public long migrated;
		public long migratedEscrow;
	}

	/** Repairs nulls and invalid entries after loading. Never discards items or money, only logs. */
	void fixNulls() {
		if (orders == null) orders = new LinkedHashMap<>();
		if (claims == null) claims = new HashMap<>();
		if (away == null) away = new HashMap<>();
		if (supply == null) supply = new HashMap<>();
		if (bonus == null) bonus = new HashMap<>();
		if (boardStats == null) boardStats = new BoardStats();
		if (boardStats.day == null) boardStats.day = "";
		supply.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null);
		for (Supply s : supply.values()) {
			if (!Double.isFinite(s.pressure) || s.pressure < 0) s.pressure = 0;
			if (s.delivered < 0) s.delivered = 0;
		}
		bonus.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null);
		for (Bonus b : bonus.values()) {
			if (b.day == null) b.day = "";
			if (b.cents < 0) b.cents = 0;
		}
		orders.entrySet().removeIf(e -> {
			if (e.getValue() != null) return false;
			SmpEconomy.LOG.warn("[SMPEco] orders.json: empty order {} removed", e.getKey());
			return true;
		});
		long maxId = 0;
		for (Map.Entry<Long, Order> e : orders.entrySet()) {
			Order o = e.getValue();
			o.id = e.getKey();
			if (o.item == null) o.item = "";
			if (o.delivered < 0) o.delivered = 0;
			if (o.escrow < 0) {
				SmpEconomy.LOG.error("[SMPEco] orders.json: order #{} with negative escrow {}, set to 0", o.id, o.escrow);
				o.escrow = 0;
			}
			if (!o.npc && o.owner == null) SmpEconomy.LOG.error("[SMPEco] orders.json: player order #{} without a buyer", o.id);
			if (o.board && !o.npc) {
				SmpEconomy.LOG.error("[SMPEco] orders.json: player order #{} marked as board order, mark removed", o.id);
				o.board = false;
			}
			if (o.board && (o.bulk || o.escrow > 0)) {
				// contradictory: treat as an old escrow order, which never pays more than the escrow and gets
				// migrated at startup
				SmpEconomy.LOG.error("[SMPEco] orders.json: board order #{} with escrow or bulk flag, kept as escrow order", o.id);
				o.board = false;
			}
			if (o.board && (!Double.isFinite(o.demand) || o.demand <= 0 || o.demand > NpcBoard.HARD_MAX_DEMAND)) {
				SmpEconomy.LOG.warn("[SMPEco] orders.json: board order #{} with invalid demand {}, set to 1.0", o.id, o.demand);
				o.demand = 1.0;
			}
			maxId = Math.max(maxId, o.id);
		}
		if (nextId <= maxId) nextId = maxId + 1;
		if (nextId < 1) nextId = 1;
		claims.values().removeIf(l -> l == null);
		for (List<Claim> list : claims.values()) {
			list.removeIf(c -> c == null || c.count <= 0 || c.item == null);
		}
		claims.values().removeIf(List::isEmpty);
		away.values().removeIf(l -> l == null);
		for (List<AwayNote> list : away.values()) list.removeIf(n -> n == null);
		away.values().removeIf(List::isEmpty);
	}

	List<Claim> claimsOf(UUID player) {
		List<Claim> list = claims.get(player);
		return list == null ? List.of() : new ArrayList<>(list);
	}
}
