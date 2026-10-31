package dev.smpeconomy.features.orders;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.BasePrices;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.PriceGuard;
import dev.smpeconomy.core.PriceModifiers;
import dev.smpeconomy.core.Services;
import dev.smpeconomy.core.TimeUtil;
import dev.smpeconomy.features.orders.OrderData.BoardStats;
import dev.smpeconomy.features.orders.OrderData.Bonus;
import dev.smpeconomy.features.orders.OrderData.Order;
import dev.smpeconomy.features.orders.OrderData.Supply;

/**
 * NPC order board: hundreds of NPC buyers behind the player orders, for nearly every item sellable through /sell. No
 * escrow. Like /sell it is a regulated source of new money, not the treasury.
 * <p>Unit price, recomputed for each item delivered:
 * <pre>
 *   reference = /sell value without personal bonuses = PriceGuard.computeSell(base, PriceModifiers.get(item), 1.0, pivot)
 *   factor    = max(floor, buyer demand - item supply pressure)
 *   unit      = min(floor(reference × factor), PriceGuard.maxNpcOrderPrice(deliverer, item, marketValue), conversion cap)
 * </pre>
 * Breaking news acts through {@code PriceModifiers} in the reference, for shop items at most up to the pivot. Each
 * item delivered raises supply pressure by {@code dropPerStack / 64}, shared by all buyers of that item and capped at
 * {@code boardMaxPressure}; pressure halves every {@code boardRecoveryHalfLifeHours}.
 * <p>Daily bonus: what a player earns above their own /sell price ({@link #sellPrice}, including rank and market day)
 * is capped per day at {@code boardDailyBonusPerPlayer}; after that the board pays at most that /sell price. A
 * delivery paying less than /sell uses no bonus.
 * <p>Money supply: each item brings at most the deliverer's /sell price, which /sell pays without limit anyway, plus
 * its bonus share. So at most the daily bonus of extra money is created per player and day. That is the hard limit;
 * supply pressure is the soft one (an item's premium is gone after about 17 stacks). Shop to board, NPC offer to
 * board and shop ingredients to recipe to board never profit (PriceGuard, market value crafting cap, conversion cap,
 * demand ≤ 1.6 &lt; recipe guard 1.68). Flipping market dealer bargains (≥ /sell value) to the board is intended and
 * bounded by supply pressure and the daily bonus.
 */
final class NpcBoard {
	/** Below the shop's recipe guard {@code recipeGuardSellFactor} of 1.68. */
	static final double HARD_MAX_DEMAND = 1.6;
	static final long TREND_TAU_MS = 45 * OrderBook.MINUTE_MS;
	static final double TREND_THRESHOLD = 0.015;
	static final long ELIGIBLE_TTL_MS = 60_000L;

	private final OrderBook book;
	/** In memory only; "stable" after a restart until something moves. */
	private final Map<String, double[]> trends = new HashMap<>();
	private List<Item> eligibleCache = List.of();
	private long eligibleAt = Long.MIN_VALUE;
	private Object eligibleConfig;

	NpcBoard(OrderBook book) {
		this.book = book;
	}

	private OrdersConfig cfg() {
		return book.config();
	}

	private OrderData data() {
		return book.data();
	}

	/**
	 * /sell reference per item: base × item modifier (breaking news), without personal bonuses, for shop items at
	 * most the pivot. 0 means not sellable.
	 */
	static long referencePrice(Item item) {
		OptionalLong base = BasePrices.sellPrice(item);
		if (base.isEmpty()) return 0;
		return Math.max(0, PriceGuard.computeSell(base.getAsLong(), PriceModifiers.get(item), 1.0, PriceGuard.pivotPrice(item)));
	}

	/**
	 * What {@code who} would get per item through /sell right now, with personal bonuses. Basis of the daily bonus,
	 * since only what's above it is extra money. 0 means not sellable for them; without a player, the {@link
	 * #referencePrice reference}.
	 */
	static long sellPrice(UUID who, Item item) {
		if (who == null) return referencePrice(item);
		return Math.max(0, PriceGuard.effectiveSellPrice(who, item).orElse(0));
	}

	long cap(UUID who, Item item) {
		OptionalLong market = Services.marketValue(item);
		if (market.isEmpty()) return 0;
		long guard = PriceGuard.maxNpcOrderPrice(who, item, market.getAsLong());
		return Math.max(0, Math.min(guard, book.conversionCap(who, item)));
	}

	double halfLifeMs() {
		return cfg().boardRecoveryHalfLifeHours * OrderBook.HOUR_MS;
	}

	double pressure(String itemId) {
		Supply s = data().supply.get(itemId);
		return s == null ? 0 : Math.min(cfg().boardMaxPressure, s.current(book.now(), halfLifeMs()));
	}

	double factor(Order o) {
		return factorAt(o.demand, pressure(o.item), cfg().boardDemandFloor);
	}

	static double factorAt(double demand, double pressure, double floor) {
		double f = demand - Math.max(0, pressure);
		return Math.max(floor, Math.min(demand, f));
	}

	double dropPerUnit() {
		return cfg().boardSupplyDropPerStack / 64.0;
	}

	long bonusLeft(UUID who) {
		long limit = cfg().boardDailyBonusPerPlayer;
		Bonus b = who == null ? null : data().bonus.get(who);
		if (b == null || !today().equals(b.day)) return limit;
		return Math.max(0, limit - b.cents);
	}

	long bonusUsed(UUID who) {
		Bonus b = who == null ? null : data().bonus.get(who);
		return b == null || !today().equals(b.day) ? 0 : b.cents;
	}

	String today() {
		return TimeUtil.dateOf(book.now()).toString();
	}

	/**
	 * Proceeds for {@code n} items delivered to this order, item by item with falling demand. {@code count} can be
	 * below {@code n} when a unit price reaches 0. {@code first}: price of the first item, for display. {@code
	 * bonus}: share above the deliverer's /sell price. {@code sell}: that /sell price per item. {@code capped}:
	 * limited by PriceGuard or conversion. {@code bonusLimited}: limited by the daily bonus.
	 */
	record Payout(int count, long total, long bonus, long first, long ref, long sell, long cap, double factorStart, double factorEnd,
			boolean capped, boolean bonusLimited) {
		static final Payout NONE = new Payout(0, 0, 0, 0, 0, 0, 0, 0, 0, false, false);

		long average() {
			return count <= 0 ? 0 : total / count;
		}

		long sellValue() {
			try {
				return Math.multiplyExact((long) count, sell);
			} catch (ArithmeticException e) {
				return Long.MAX_VALUE;
			}
		}

		boolean belowSell() {
			return count > 0 && sell > 0 && total < sellValue();
		}
	}

	Payout payout(UUID who, Order o, int n) {
		Optional<Item> type = o.itemType();
		if (type.isEmpty() || !o.board) return Payout.NONE;
		Item item = type.get();
		long ref = referencePrice(item);
		long sell = sellPrice(who, item);
		long cap = cap(who, item);
		if (ref <= 0 || cap <= 0) return new Payout(0, 0, 0, 0, ref, sell, cap, 0, 0, false, false);
		OrdersConfig c = cfg();
		double p0 = pressure(o.item);
		double u = dropPerUnit();
		double maxP = c.boardMaxPressure;
		long bonusLeft = bonusLeft(who);
		long total = 0;
		long bonusSum = 0;
		long first = 0;
		boolean capped = false;
		boolean limited = false;
		int count = 0;
		double fStart = factorAt(o.demand, p0, c.boardDemandFloor);
		double fEnd = fStart;
		for (int k = 0; k < Math.max(0, n); k++) {
			double p = Math.min(maxP, p0 + k * u);
			double f = factorAt(o.demand, p, c.boardDemandFloor);
			long raw = Money.floorCents(ref * f);
			long price = Math.min(raw, cap);
			// the bonus is what exceeds THIS player's /sell price, with their bonuses: only that is extra money
			long bonus = Math.max(0, price - sell);
			boolean clipped = bonus > bonusLeft;
			if (clipped) {
				price -= bonus - bonusLeft; // = sell + bonusLeft (bonus > 0 implies price > sell)
				bonus = bonusLeft;
			}
			if (price <= 0) break;
			if (raw > cap) capped = true;
			if (clipped) limited = true;
			bonusLeft -= bonus;
			bonusSum += bonus;
			total = Math.addExact(total, price);
			if (k == 0) first = price;
			fEnd = f;
			count++;
		}
		return new Payout(count, total, bonusSum, first, ref, sell, cap, fStart, fEnd, capped, limited);
	}

	void record(UUID who, Order o, Payout p) {
		if (p.count() <= 0) return;
		long now = book.now();
		Supply s = data().supply.computeIfAbsent(o.item, k -> new Supply());
		s.settle(now, halfLifeMs());
		s.pressure = Math.min(cfg().boardMaxPressure, s.pressure + p.count() * dropPerUnit());
		s.at = now;
		s.delivered += p.count();
		String day = today();
		if (p.bonus() > 0) {
			Bonus b = data().bonus.computeIfAbsent(who, k -> new Bonus());
			if (!day.equals(b.day)) {
				b.day = day;
				b.cents = 0;
			}
			b.cents = Math.addExact(b.cents, p.bonus());
		}
		BoardStats st = stats(day);
		st.paidToday = Math.addExact(st.paidToday, p.total());
		st.bonusToday = Math.addExact(st.bonusToday, p.bonus());
		st.unitsToday += p.count();
		st.deliveriesToday++;
		st.paidTotal = Math.addExact(st.paidTotal, p.total());
		st.bonusTotal = Math.addExact(st.bonusTotal, p.bonus());
		st.unitsTotal += p.count();
	}

	BoardStats stats(String day) {
		BoardStats st = data().boardStats;
		if (!day.equals(st.day)) {
			st.day = day;
			st.paidToday = 0;
			st.bonusToday = 0;
			st.unitsToday = 0;
			st.deliveriesToday = 0;
		}
		return st;
	}

	void onCompleted(Order o) {
		long cd = cfg().boardCooldownMinutes * OrderBook.MINUTE_MS;
		if (cd <= 0) return;
		Supply s = data().supply.computeIfAbsent(o.item, k -> new Supply());
		s.cooldownUntil = Math.max(s.cooldownUntil, book.now() + cd);
	}

	enum Trend {
		UP("§a▲", "rising"), DOWN("§c▼", "falling"), FLAT("§7■", "stable");

		final String arrow;
		final String label;

		Trend(String arrow, String label) {
			this.arrow = arrow;
			this.label = label;
		}
	}

	private double index(Item item, String itemId) {
		OrdersConfig c = cfg();
		double typical = (c.boardDemandMin + c.boardDemandMax) / 2;
		return referencePrice(item) * factorAt(typical, pressure(itemId), c.boardDemandFloor);
	}

	Trend trend(Order o) {
		Optional<Item> item = o.itemType();
		if (item.isEmpty()) return Trend.FLAT;
		double now = index(item.get(), o.item);
		double[] t = trends.get(o.item);
		if (t == null || !(t[0] > 0)) return Trend.FLAT;
		double rel = now / t[0] - 1;
		return rel > TREND_THRESHOLD ? Trend.UP : rel < -TREND_THRESHOLD ? Trend.DOWN : Trend.FLAT;
	}

	void updateTrends() {
		long now = book.now();
		Set<String> seen = new HashSet<>();
		for (Order o : data().orders.values()) {
			if (!o.board || !seen.add(o.item)) continue;
			Optional<Item> item = o.itemType();
			if (item.isEmpty()) continue;
			double idx = index(item.get(), o.item);
			double[] t = trends.get(o.item);
			if (t == null || !(t[0] > 0)) {
				trends.put(o.item, new double[] {idx, now});
				continue;
			}
			double dt = Math.max(0, now - t[1]);
			double a = 1 - Math.exp(-dt / TREND_TAU_MS);
			t[0] += (idx - t[0]) * a;
			t[1] = now;
		}
		trends.keySet().retainAll(seen);
	}

	static String demandLabel(double factor) {
		if (factor >= 1.22) return "§ahigh";
		if (factor >= 1.10) return "§enormal";
		if (factor >= 1.0) return "§6low";
		return "§csaturated";
	}

	Map<String, Integer> counts() {
		Map<String, Integer> map = new HashMap<>();
		for (Order o : data().orders.values()) {
			if (o.board) map.merge(o.item, 1, Integer::sum);
		}
		return map;
	}

	int size() {
		int n = 0;
		for (Order o : data().orders.values()) {
			if (o.board) n++;
		}
		return n;
	}

	boolean enabled() {
		OrdersConfig c = cfg();
		return c.npcEnabled && c.boardEnabled && c.boardSize > 0;
	}

	void maintain() {
		if (!enabled()) {
			int n = clear();
			if (n > 0 && book == OrdersFeature.BOOK) SmpEconomy.LOG.info("[SMPEco] orders: board disabled, {} board orders removed", n);
			return;
		}
		int size = size();
		int target = cfg().boardSize;
		if (size < target) {
			boolean initial = size < target / 2;
			refill(initial ? target : cfg().boardRefillPerTick, initial);
		}
		updateTrends();
	}

	int clear() {
		int before = data().orders.size();
		data().orders.values().removeIf(o -> o.board);
		int n = before - data().orders.size();
		if (n > 0) book.dirty();
		return n;
	}

	/**
	 * Creates up to {@code max} board orders: items without an order first, then second buyers for common items,
	 * skipping items on cooldown. {@code initial} staggers durations so they don't all expire together.
	 */
	int refill(int max, boolean initial) {
		if (!enabled() || max <= 0) return 0;
		OrdersConfig c = cfg();
		int room = c.boardSize - size();
		if (room <= 0) return 0;
		Map<String, Integer> counts = counts();
		Set<Item> common = commonItems();
		long now = book.now();
		List<Item> first = new ArrayList<>();
		List<Item> second = new ArrayList<>();
		for (Item item : eligible(false)) {
			String id = Items.id(item);
			Supply s = data().supply.get(id);
			if (s != null && s.cooldownUntil > now) continue;
			int have = counts.getOrDefault(id, 0);
			if (have == 0) first.add(item);
			if (have <= 1 && common.contains(item)) second.add(item);
		}
		Random rnd = book.random();
		Collections.shuffle(first, rnd);
		Collections.shuffle(second, rnd);
		first.addAll(second);
		int n = 0;
		for (Item item : first) {
			if (n >= Math.min(max, room)) break;
			if (spawn(item, initial, common.contains(item)) != null) n++;
		}
		return n;
	}

	Set<Item> commonItems() {
		Set<Item> set = new HashSet<>();
		for (String id : cfg().boardCommonItems) Items.byId(id).ifPresent(set::add);
		return set;
	}

	/**
	 * Eligible items: sellable, with a market value, deliverable unmodified (no template-only or one-of-a-kind
	 * items), not excluded, and with a price cap of at least the /sell value, otherwise the order makes no sense.
	 * Cached for {@link #ELIGIBLE_TTL_MS}.
	 */
	List<Item> eligible(boolean fresh) {
		long now = book.now();
		OrdersConfig c = cfg();
		if (!fresh && c == eligibleConfig && now >= eligibleAt && now - eligibleAt < ELIGIBLE_TTL_MS) return eligibleCache;
		Set<Item> exclude = new HashSet<>();
		for (String id : c.boardExclude) Items.byId(id).ifPresent(exclude::add);
		List<Item> list = new ArrayList<>();
		for (Item item : BasePrices.sellPrices().keySet()) {
			if (isEligible(item, exclude)) list.add(item);
		}
		list.sort((a, b) -> Items.id(a).compareTo(Items.id(b))); // deterministic for selftests with a fixed RNG
		eligibleCache = List.copyOf(list);
		eligibleAt = now;
		eligibleConfig = c;
		return eligibleCache;
	}

	boolean isEligible(Item item, Set<Item> exclude) {
		if (item == null || item == net.minecraft.world.item.Items.AIR || new ItemStack(item).isEmpty()) return false;
		if (exclude.contains(item) || OrderBook.needsTemplate(item) || OrderBook.isUnique(item)) return false;
		long ref = referencePrice(item);
		if (ref <= 0) return false;
		return cap(OrderBook.NEUTRAL, item) >= ref;
	}

	Order spawn(Item item, boolean initial, boolean common) {
		long ref = referencePrice(item);
		if (ref <= 0) return null;
		OrdersConfig c = cfg();
		Random rnd = book.random();
		double demand = c.boardDemandMin + rnd.nextDouble() * (c.boardDemandMax - c.boardDemandMin);
		// amount: item count and order value are each log-uniform and the smaller wins, so cheap items get 64 to 2304
		// and expensive ones fewer (diamond 4 to 80, netherite ingot 1 to 2)
		int maxAmount = Math.max(1, Math.min(c.boardMaxAmount, c.maxAmountPerOrder));
		int minAmount = Math.max(1, Math.min(c.boardMinAmount, maxAmount));
		double units = Math.exp(Math.log(minAmount) + rnd.nextDouble() * (Math.log(maxAmount) - Math.log(minAmount)));
		double lo = Math.log(c.boardOrderMinValue);
		double hi = Math.log(c.boardOrderMaxValue);
		double value = Math.exp(lo + rnd.nextDouble() * (hi - lo)) * (common ? 2 : 1);
		long raw = (long) Math.floor(Math.min(units, value / ref));
		int amount = (int) Math.max(1, Math.min(maxAmount, raw));
		amount = Math.max(1, OrderBook.niceAmount(amount));
		long hours = c.boardMinHours + rnd.nextInt(c.boardMaxHours - c.boardMinHours + 1);
		long duration = hours * OrderBook.HOUR_MS + rnd.nextInt(60) * OrderBook.MINUTE_MS;
		if (initial) duration = Math.max(OrderBook.HOUR_MS, (long) (duration * (0.15 + 0.85 * rnd.nextDouble())));
		long now = book.now();
		OrderData d = data();
		Order o = new Order();
		o.id = d.nextId++;
		o.owner = null;
		o.npc = true;
		o.board = true;
		o.bulk = false;
		o.demand = Math.round(demand * 1000) / 1000.0;
		o.label = NpcNames.forItem(item, rnd);
		o.item = Items.id(item);
		o.amount = amount;
		o.price = 0;
		o.escrow = 0;
		o.createdAt = now;
		o.expiresAt = now + duration;
		d.orders.put(o.id, o);
		book.dirty();
		return o;
	}
}
