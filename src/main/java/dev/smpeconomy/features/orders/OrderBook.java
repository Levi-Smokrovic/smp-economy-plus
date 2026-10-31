package dev.smpeconomy.features.orders;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import dev.smpeconomy.core.compat.Mc;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.EconomyService.Result;
import dev.smpeconomy.core.ItemCodec;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.PriceGuard;
import dev.smpeconomy.core.Services;
import dev.smpeconomy.core.Settings;
import dev.smpeconomy.features.orders.OrderData.AwayNote;
import dev.smpeconomy.features.orders.OrderData.Claim;
import dev.smpeconomy.features.orders.OrderData.Order;

/**
 * Buy order logic over an {@link OrderData}, so selftests can use their own instance and clock. Server thread only.
 * For player orders and NPC bulk orders money only moves between accounts, order escrows and the treasury ({@link
 * OrderFunding}), so none is created or lost. Board orders ({@link NpcBoard}) pay new money like /sell, capped by
 * PriceGuard, supply pressure and the daily bonus.
 */
public final class OrderBook {
	public static final String NPC_LABEL = "NPC Order";
	public static final String BULK_LABEL = "NPC Bulk Order";
	/** Reference player with no personal bonuses, for NPC prices at creation. Delivery uses the real deliverer. */
	public static final UUID NEUTRAL = new UUID(0L, 0L);
	static final long MINUTE_MS = 60_000L;
	static final long HOUR_MS = 3_600_000L;
	static final long DAY_MS = 86_400_000L;
	static final long MAX_BULK_MS = 7 * DAY_MS;

	public enum Status {
		OK(""),
		NOT_READY("Orders aren't available right now."),
		INVALID_ITEM("This item can't be ordered."),
		INVALID_AMOUNT("Invalid amount."),
		INVALID_PRICE("Invalid price."),
		TOO_EXPENSIVE("The total is too high."),
		LIMIT("You already have the maximum number of orders."),
		INSUFFICIENT_FUNDS("Not enough money."),
		NOT_FOUND("This order doesn't exist anymore."),
		NOT_OWNER("That's not your order."),
		OWN_ORDER("You can't deliver to your own order."),
		EXPIRED("This order has expired."),
		NO_ITEMS("You don't have any matching items (same item, unmodified or exactly like the template)."),
		NEEDS_TEMPLATE("This item only exists with properties (enchantment, potion …) – order it with a template."),
		NOT_ORDERABLE("This item is one of a kind and can't be ordered."),
		NOT_ACCEPTING("This order isn't accepting anything right now (price cap)."),
		PRICE_CHANGED("The price has changed – please check again."),
		BALANCE_LIMIT("Your balance is at the limit."),
		NO_SPACE("No space in your inventory."),
		CANNOT_RECEIVE("You can't receive items right now."),
		CREATIVE("You can't deliver in Creative or Spectator mode."),
		BROKEN("This entry is broken – please tell an admin."),
		REFUND_FAILED("Refund failed – please tell an admin."),
		BUDGET("The Treasury has no budget for this right now."),
		ERROR("Internal error – nothing was changed.");

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

	public record CreateResult(Status status, Order order) {}

	public record CancelResult(Status status, long refunded, int delivered) {}

	/**
	 * {@code unitPrice}: board orders give the first item's price. {@code bonus}: share above the deliverer's /sell
	 * price (board, daily bonus).
	 */
	public record DeliverResult(Status status, int count, long unitPrice, long gross, long fee, long net, boolean completed, long bonus) {
		static DeliverResult fail(Status s) {
			return new DeliverResult(s, 0, 0, 0, 0, 0, false, 0);
		}

		public long average() {
			return count <= 0 ? 0 : gross / count;
		}
	}

	public record ClaimResult(Status status, int given, long left) {}

	/**
	 * {@code unit} = min(order price, {@code guard}, {@code conversion}); -1 means no limit for the latter two.
	 * Player orders always pay the order price.
	 */
	public record Quote(long unit, long guard, long conversion) {}

	/**
	 * Delivery terms as a deliverer saw them. {@link #deliver(ServerPlayer, long, int, Terms)} only delivers if it
	 * now pays at least as much per item and the fee for the same amount is no higher, otherwise {@link
	 * Status#PRICE_CHANGED} without moving anything. The order's progress is part of the terms because the fee is
	 * cumulative ({@link #feeFor}). Board prices fall item by item, so {@code unit} is the first item's price and
	 * {@code gross} optionally holds the displayed proceeds; delivery requires the current average to be no lower.
	 * {@link #ANY} takes whatever the current price is (selftests, tools).
	 */
	public record Terms(long unit, double feePercent, int delivered, long gross, int count) {
		public static final Terms ANY = new Terms(0, -1, -1, -1, 0);

		public Terms(long unit, double feePercent, int delivered) {
			this(unit, feePercent, delivered, -1, 0);
		}

		boolean averageBelowShown(long actualGross, int actualCount) {
			if (gross < 0 || count <= 0 || actualCount <= 0) return false;
			return java.math.BigInteger.valueOf(actualGross).multiply(java.math.BigInteger.valueOf(count))
				.compareTo(java.math.BigInteger.valueOf(gross).multiply(java.math.BigInteger.valueOf(actualCount))) < 0;
		}

		long feeFor(Order order, int count, long unitNow) {
			if (feePercent < 0 || delivered < 0) return -1;
			return cumulativeFee(order.price, delivered, count, unitNow, feePercent);
		}
	}

	static final int MAX_CONVERSION_DEPTH = 3;
	/** Micro-cents per item: exact fractions like 9001 / 9 without double. */
	private static final long MICRO = 1_000_000L;
	/** System prices above this are meaningless for the conversion cap; avoids overflow. */
	private static final long MAX_FLOOR_CENTS = 1_000_000_000_000L;

	private record Link(Item from, int in, int out) {}

	private List<OrdersConfig.Conversion> linkSource;
	private int linkSourceSize = -1;
	private Map<Item, List<Link>> linksTo = Map.of();

	private final NpcBoard board = new NpcBoard(this);
	private final Supplier<OrderData> data;
	private final Runnable markDirty;
	private final LongSupplier clock;
	private final OrderFunding funding;
	private final Random random;
	private final Supplier<OrdersConfig> config;
	private final BooleanSupplier ready;
	private long version;

	public OrderBook(Supplier<OrderData> data, Runnable markDirty, LongSupplier clock, OrderFunding funding, Random random,
			Supplier<OrdersConfig> config, BooleanSupplier ready) {
		this.data = data;
		this.markDirty = () -> {
			version++;
			markDirty.run();
		};
		this.clock = clock;
		this.funding = funding;
		this.random = random;
		this.config = config;
		this.ready = ready;
	}

	public OrderData data() {
		return data.get();
	}

	public long now() {
		return clock.getAsLong();
	}

	public OrdersConfig config() {
		return config.get();
	}

	public boolean ready() {
		return ready.getAsBoolean();
	}

	NpcBoard board() {
		return board;
	}

	Random random() {
		return random;
	}

	void dirty() {
		markDirty.run();
	}

	long version() {
		return version;
	}

	public Optional<Order> get(long id) {
		return Optional.ofNullable(data.get().orders.get(id));
	}

	/**
	 * Open means present, with something left, and not expired. Expired orders count as closed at once; the refund
	 * follows in the next {@link #tick}. Without loaded data, true: unknown, so nothing ends early.
	 */
	public boolean isOpen(long id) {
		if (!ready()) return true;
		Order o = data.get().orders.get(id);
		return o != null && o.open() > 0 && now() < o.expiresAt;
	}

	public List<Order> all() {
		List<Order> list = new ArrayList<>(data.get().orders.values());
		Map<Long, Double> factors = new HashMap<>();
		for (Order o : list) {
			if (o.board) factors.put(o.id, board.factor(o));
		}
		list.sort((a, b) -> {
			int r = Integer.compare(rank(a), rank(b));
			if (r != 0) return r;
			switch (rank(a)) {
				case 0 -> {
					int c = Long.compare(b.price, a.price);
					if (c != 0) return c;
					c = Long.compare(a.createdAt, b.createdAt);
					return c != 0 ? c : Long.compare(a.id, b.id);
				}
				case 2 -> {
					int c = Double.compare(factors.getOrDefault(b.id, 0.0), factors.getOrDefault(a.id, 0.0));
					if (c != 0) return c;
					c = a.item.compareTo(b.item);
					return c != 0 ? c : Long.compare(a.id, b.id);
				}
				default -> {
					int c = Long.compare(b.createdAt, a.createdAt);
					return c != 0 ? c : Long.compare(b.id, a.id);
				}
			}
		});
		return list;
	}

	static int rank(Order o) {
		return !o.npc ? 0 : o.bulk ? 1 : o.board ? 2 : 3;
	}

	public List<Order> ownedBy(UUID player) {
		List<Order> list = new ArrayList<>();
		for (Order o : all()) {
			if (o.isOwner(player)) list.add(o);
		}
		return list;
	}

	public int countOwned(UUID player) {
		int n = 0;
		for (Order o : data.get().orders.values()) {
			if (o.isOwner(player)) n++;
		}
		return n;
	}

	public long totalEscrow() {
		long sum = 0;
		for (Order o : data.get().orders.values()) sum = Math.addExact(sum, o.escrow);
		return sum;
	}

	public long escrowOf(UUID player) {
		long sum = 0;
		for (Order o : data.get().orders.values()) {
			if (o.isOwner(player)) sum = Math.addExact(sum, o.escrow);
		}
		return sum;
	}

	public List<Claim> claims(UUID player) {
		return data.get().claimsOf(player);
	}

	public long claimTotal(UUID player) {
		long sum = 0;
		for (Claim c : data.get().claimsOf(player)) sum += c.count;
		return sum;
	}

	/**
	 * Components defining WHAT an item is. Only these go into a template; name, damage, contents, repair cost never
	 * do.
	 */
	public static boolean isTemplateComponent(DataComponentType<?> type) {
		return type == DataComponents.ENCHANTMENTS || type == DataComponents.STORED_ENCHANTMENTS || type == DataComponents.POTION_CONTENTS
			|| type == DataComponents.SUSPICIOUS_STEW_EFFECTS || type == DataComponents.OMINOUS_BOTTLE_AMPLIFIER
			|| type == DataComponents.INSTRUMENT || type == DataComponents.FIREWORKS || type == DataComponents.FIREWORK_EXPLOSION;
	}

	/**
	 * Never exists unmodified in survival, so only orderable with a template. A type-only order would accept nothing,
	 * or with "any enchantment" every book.
	 */
	public static boolean needsTemplate(Item item) {
		return item == net.minecraft.world.item.Items.ENCHANTED_BOOK || item == net.minecraft.world.item.Items.POTION
			|| item == net.minecraft.world.item.Items.SPLASH_POTION || item == net.minecraft.world.item.Items.LINGERING_POTION
			|| item == net.minecraft.world.item.Items.TIPPED_ARROW || item == net.minecraft.world.item.Items.SUSPICIOUS_STEW
			|| item == net.minecraft.world.item.Items.FIREWORK_STAR;
	}

	/** Each copy is unique (book contents, map id), so not orderable. */
	public static boolean isUnique(Item item) {
		return item == net.minecraft.world.item.Items.WRITTEN_BOOK || item == net.minecraft.world.item.Items.FILLED_MAP;
	}

	/** One item with only the {@link #isTemplateComponent property components}; null when the stack has none. */
	public static ItemStack templateOf(ItemStack source) {
		if (source == null || source.isEmpty()) return null;
		DataComponentPatch keep = source.getComponentsPatch().forget(t -> !isTemplateComponent(t));
		if (keep.isEmpty()) return null;
		return new ItemStack(Mc.itemHolder(source), 1, keep);
	}

	public static Status checkTemplate(Item item, ItemStack template) {
		if (item == null) return Status.INVALID_ITEM;
		if (isUnique(item)) return Status.NOT_ORDERABLE;
		if (template != null && !template.is(item)) return Status.INVALID_ITEM;
		if (template == null && needsTemplate(item)) return Status.NEEDS_TEMPLATE;
		return Status.OK;
	}

	/** Repair cost from the anvil doesn't count. */
	static boolean matchesTemplate(ItemStack template, ItemStack stack) {
		return stack.getComponentsPatch().forget(t -> t == DataComponents.REPAIR_COST).equals(template.getComponentsPatch());
	}

	/**
	 * Same item and unmodified, the same rule as /sell. Templates require exactly their properties. A player order
	 * without template that allows enchantments also accepts stacks changed only by enchantments, stored enchantments
	 * or repair cost. Items that only exist with properties never match a template-less order; old orders can still
	 * be in that state.
	 */
	public static boolean accepts(Order order, Item item, ItemStack stack) {
		if (stack.isEmpty() || !stack.is(item)) return false;
		if (order.npc) return Items.isPristine(stack);
		if (order.spec != null) {
			Optional<ItemStack> template = order.specStack();
			return template.isPresent() && template.get().is(item) && matchesTemplate(template.get(), stack);
		}
		if (needsTemplate(item) || isUnique(item)) return false;
		if (Items.isPristine(stack)) return true;
		if (!order.allowEnchanted) return false;
		DataComponentPatch patch = stack.getComponentsPatch();
		for (Map.Entry<DataComponentType<?>, Boolean> e : Mc.patchChanges(patch).entrySet()) {
			if (!e.getValue()) return false; // removed default component
			DataComponentType<?> type = e.getKey();
			if (type != DataComponents.ENCHANTMENTS && type != DataComponents.STORED_ENCHANTMENTS && type != DataComponents.REPAIR_COST) return false;
		}
		return true;
	}

	public int countDeliverable(ServerPlayer player, Order order) {
		Optional<Item> item = order.itemType();
		return item.isEmpty() ? 0 : Items.count(player, s -> accepts(order, item.get(), s));
	}

	/**
	 * Unit price before fee that {@code deliverer} would get now. NPC orders: at most {@link
	 * PriceGuard#maxNpcOrderPrice} at the current {@link Services#marketValue} and at most {@link #conversionCap},
	 * re-evaluated on each delivery. Board orders: price of the next item. 0 means it accepts nothing right now.
	 */
	public long unitPrice(UUID deliverer, Order order) {
		return quote(deliverer, order).unit();
	}

	public double feePercent(Order order) {
		return order.npc ? 0 : config.get().deliveryFeePercent;
	}

	public Terms terms(UUID deliverer, Order order) {
		return new Terms(unitPrice(deliverer, order), feePercent(order), Math.max(0, order.delivered));
	}

	public Terms terms(UUID deliverer, Order order, int count) {
		if (!order.board || count <= 0) return terms(deliverer, order);
		NpcBoard.Payout p = board.payout(deliverer, order, count);
		return new Terms(p.first(), 0, Math.max(0, order.delivered), p.total(), p.count());
	}

	NpcBoard.Payout boardPayout(UUID deliverer, Order order, int count) {
		return board.payout(deliverer, order, count);
	}

	/** Breakdown of {@link #unitPrice}. Board: {@code unit} is the next item. */
	public Quote quote(UUID deliverer, Order order) {
		if (!order.npc) return new Quote(Math.max(0, order.price), -1, -1);
		if (order.board) {
			Optional<Item> type = order.itemType();
			if (type.isEmpty()) return new Quote(0, -1, -1);
			long conv = conversionCap(deliverer, type.get());
			OptionalLong market = Services.marketValue(type.get());
			long guard = market.isEmpty() ? -1 : PriceGuard.maxNpcOrderPrice(deliverer, type.get(), market.getAsLong());
			return new Quote(board.payout(deliverer, order, 1).first(), guard, conv == Long.MAX_VALUE ? -1 : conv);
		}
		Optional<Item> item = order.itemType();
		if (item.isEmpty()) return new Quote(0, -1, -1);
		long conv = conversionCap(deliverer, item.get());
		OptionalLong market = Services.marketValue(item.get());
		long convShown = conv == Long.MAX_VALUE ? -1 : conv;
		if (market.isEmpty()) return new Quote(0, -1, convShown);
		long guard = PriceGuard.maxNpcOrderPrice(deliverer, item.get(), market.getAsLong());
		return new Quote(Math.max(0, Math.min(Math.min(order.price, guard), conv)), guard, convShown);
	}

	long npcOfferCap(Item item) {
		OptionalLong market = Services.marketValue(item);
		if (market.isEmpty()) return 0;
		return Math.max(0, Math.min(PriceGuard.maxNpcOrderPrice(NEUTRAL, item, market.getAsLong()), conversionCap(NEUTRAL, item)));
	}

	/**
	 * Highest unit price for {@code target} at which "buy the source from the system, convert (iron block to 9
	 * ingots), deliver to an NPC order" yields no profit: price × out &lt; systemPrice(from) × in, across all {@link
	 * OrdersConfig#npcConversions} and chains up to {@link #MAX_CONVERSION_DEPTH}. System price is the lower of
	 * {@link PriceGuard#minSystemSalePrice} and the shop price, for {@code who} and for a player without bonuses.
	 * PriceGuard alone only covers the same item, while drift and events move block and ingot separately.
	 */
	public long conversionCap(UUID who, Item target) {
		Map<Item, List<Link>> links = linksTo();
		if (!links.containsKey(target)) return Long.MAX_VALUE;
		long cost = acquireCost(who, target, 0, new HashSet<>(), new HashMap<>(), false, links);
		if (cost == Long.MAX_VALUE) return Long.MAX_VALUE;
		return Math.max(0, (cost - 1) / MICRO); // largest whole cent amount strictly below cost
	}

	/** Rounds down, the stricter direction. MAX means not obtainable. */
	private long acquireCost(UUID who, Item item, int depth, Set<Item> path, Map<Item, Long> floors, boolean buyable, Map<Item, List<Link>> links) {
		long best = buyable ? systemFloorMicro(who, item, floors) : Long.MAX_VALUE;
		List<Link> sources = links.get(item);
		if (sources == null || depth >= MAX_CONVERSION_DEPTH) return best;
		path.add(item);
		for (Link l : sources) {
			if (path.contains(l.from())) continue;
			long src = acquireCost(who, l.from(), depth + 1, path, floors, true, links);
			if (src == Long.MAX_VALUE || src > Long.MAX_VALUE / l.in()) continue;
			best = Math.min(best, src * l.in() / l.out());
		}
		path.remove(item);
		return best;
	}

	private static long systemFloorMicro(UUID who, Item item, Map<Item, Long> floors) {
		Long cached = floors.get(item);
		if (cached != null) return cached;
		long floor = Long.MAX_VALUE;
		OptionalLong market = Services.marketValue(item);
		if (market.isPresent()) {
			floor = Math.min(PriceGuard.minSystemSalePrice(who, item, market.getAsLong()),
				PriceGuard.minSystemSalePrice(NEUTRAL, item, market.getAsLong()));
		}
		OptionalLong shopWho = PriceGuard.effectiveShopBuyPrice(who, item);
		if (shopWho.isPresent()) floor = Math.min(floor, shopWho.getAsLong());
		OptionalLong shopNeutral = PriceGuard.effectiveShopBuyPrice(NEUTRAL, item);
		if (shopNeutral.isPresent()) floor = Math.min(floor, shopNeutral.getAsLong());
		long micro = floor <= 0 ? 0 : floor > MAX_FLOOR_CENTS ? Long.MAX_VALUE : floor * MICRO;
		floors.put(item, micro);
		return micro;
	}

	private Map<Item, List<Link>> linksTo() {
		List<OrdersConfig.Conversion> src = config.get().npcConversions;
		if (src == null) return Map.of();
		if (src != linkSource || src.size() != linkSourceSize) {
			Map<Item, List<Link>> index = new HashMap<>();
			for (OrdersConfig.Conversion c : src) {
				if (c == null || c.in < 1 || c.out < 1) continue;
				Optional<Item> from = Items.byId(c.from);
				Optional<Item> to = Items.byId(c.to);
				if (from.isEmpty() || to.isEmpty() || from.get() == to.get()) continue;
				index.computeIfAbsent(to.get(), k -> new ArrayList<>()).add(new Link(from.get(), c.in, c.out));
			}
			linksTo = index;
			linkSource = src;
			linkSourceSize = src.size();
		}
		return linksTo;
	}

	/**
	 * Delivery fee for {@code count} more items (player orders only). Computed cumulatively as {@code fee(sofar +
	 * count) - fee(sofar)}, so splitting a delivery to round down saves nothing: all fees always total {@code
	 * floor(delivered × price × rate)}. Never exceeds gross.
	 */
	public long feeFor(Order order, int count, long unit) {
		if (order.npc) return 0;
		return cumulativeFee(order.price, order.delivered, count, unit, feePercent(order));
	}

	static long cumulativeFee(long price, int delivered, int count, long unit, double pct) {
		if (count <= 0 || unit <= 0 || pct <= 0) return 0;
		long before = Money.percentOf(Math.multiplyExact((long) Math.max(0, delivered), price), pct);
		long after = Money.percentOf(Math.multiplyExact((long) Math.max(0, delivered) + count, price), pct);
		long gross = Math.multiplyExact((long) count, unit);
		return Math.max(0, Math.min(gross, after - before));
	}

	public CreateResult createPlayerOrder(UUID owner, Item item, int amount, long price, boolean allowEnchanted) {
		return createPlayerOrder(owner, item, null, amount, price, allowEnchanted);
	}

	/**
	 * Reserves {@code amount × price} from the buyer immediately. A {@code template} ({@link #templateOf}) makes its
	 * properties mandatory, which makes {@code allowEnchanted} meaningless.
	 */
	public CreateResult createPlayerOrder(UUID owner, Item item, ItemStack template, int amount, long price, boolean allowEnchanted) {
		if (!ready()) return new CreateResult(Status.NOT_READY, null);
		OrdersConfig cfg = config.get();
		if (owner == null || EconomyService.isSystemAccount(owner)) return new CreateResult(Status.ERROR, null);
		if (item == null || item == net.minecraft.world.item.Items.AIR || new ItemStack(item).isEmpty()) return new CreateResult(Status.INVALID_ITEM, null);
		ItemStack spec = templateOf(template);
		Status ts = checkTemplate(item, spec);
		if (!ts.ok()) return new CreateResult(ts, null);
		String specData = null;
		if (spec != null) {
			try {
				specData = ItemCodec.encode(spec);
			} catch (RuntimeException e) {
				SmpEconomy.LOG.error("[SMPEco] orders: template {} cannot be saved", Items.id(item), e);
				return new CreateResult(Status.ERROR, null);
			}
		}
		if (amount < 1 || amount > cfg.maxAmountPerOrder) return new CreateResult(Status.INVALID_AMOUNT, null);
		if (!Money.isValidAmount(price)) return new CreateResult(Status.INVALID_PRICE, null);
		long total;
		try {
			total = Math.multiplyExact((long) amount, price);
		} catch (ArithmeticException e) {
			return new CreateResult(Status.TOO_EXPENSIVE, null);
		}
		if (!Money.isValidAmount(total)) return new CreateResult(Status.TOO_EXPENSIVE, null);
		if (countOwned(owner) >= cfg.maxOrdersPerPlayer) return new CreateResult(Status.LIMIT, null);
		Result r = EconomyService.withdraw(owner, total);
		if (!r.ok()) return new CreateResult(r == Result.INSUFFICIENT_FUNDS ? Status.INSUFFICIENT_FUNDS : Status.ERROR, null);
		OrderData d = data.get();
		long now = now();
		Order o = new Order();
		o.id = d.nextId++;
		o.owner = owner;
		o.npc = false;
		o.item = Items.id(item);
		o.amount = amount;
		o.price = price;
		o.escrow = total;
		o.createdAt = now;
		o.expiresAt = now + cfg.expiryDays * DAY_MS;
		o.allowEnchanted = spec == null && allowEnchanted;
		o.spec = specData;
		d.orders.put(o.id, o);
		markDirty.run();
		return new CreateResult(Status.OK, o);
	}

	public CancelResult cancel(UUID requester, long id, boolean admin) {
		if (!ready()) return new CancelResult(Status.NOT_READY, 0, 0);
		Order o = data.get().orders.get(id);
		if (o == null) return new CancelResult(Status.NOT_FOUND, 0, 0);
		if (!admin && !o.isOwner(requester)) return new CancelResult(Status.NOT_OWNER, 0, 0);
		long escrow = o.escrow;
		Status s = refundAndRemove(o);
		return new CancelResult(s, s.ok() ? escrow : 0, o.delivered);
	}

	/**
	 * Creative and spectator (same rule as /sell) only with {@code allowCreative}, otherwise conjured items get
	 * swapped for escrow or treasury money.
	 */
	public boolean deliveryBlocked(ServerPlayer deliverer) {
		return !config.get().allowCreative && Items.isCreativeOrSpectator(deliverer);
	}

	public DeliverResult deliver(ServerPlayer deliverer, long id, int requested) {
		return deliver(deliverer, id, requested, Terms.ANY);
	}

	/**
	 * Delivers up to {@code requested} matching items from the main inventory, at most the open amount and what the
	 * escrow covers. Everything is validated before the first change, and items go back if the credit still fails.
	 * {@code shown} holds the terms the deliverer saw when clicking; if the current ones are worse, nothing is
	 * delivered ({@link Status#PRICE_CHANGED}).
	 */
	public DeliverResult deliver(ServerPlayer deliverer, long id, int requested, Terms shown) {
		Terms expected = Objects.requireNonNullElse(shown, Terms.ANY);
		if (!ready()) return DeliverResult.fail(Status.NOT_READY);
		Order o = data.get().orders.get(id);
		if (o == null) return DeliverResult.fail(Status.NOT_FOUND);
		UUID who = deliverer.getUUID();
		if (now() >= o.expiresAt) return DeliverResult.fail(Status.EXPIRED);
		if (o.isOwner(who)) return DeliverResult.fail(Status.OWN_ORDER);
		if (!o.npc && o.owner == null) return DeliverResult.fail(Status.BROKEN); // items would have no claim chest
		if (o.board && (!board.enabled() || o.escrow != 0)) return DeliverResult.fail(Status.NOT_ACCEPTING);
		Optional<Item> type = o.itemType();
		if (type.isEmpty() || o.open() <= 0) return DeliverResult.fail(Status.NOT_FOUND);
		if (!Items.canReceive(deliverer)) return DeliverResult.fail(Status.CANNOT_RECEIVE);
		if (deliveryBlocked(deliverer)) return DeliverResult.fail(Status.CREATIVE); // creative items must not earn escrow or treasury money
		Item item = type.get();
		long unit = unitPrice(who, o);
		if (unit <= 0) return DeliverResult.fail(Status.NOT_ACCEPTING);
		if (unit < expected.unit()) return DeliverResult.fail(Status.PRICE_CHANGED);
		long coverable = o.board ? Integer.MAX_VALUE : o.escrow / unit;
		if (coverable <= 0) return DeliverResult.fail(Status.NOT_ACCEPTING);
		int want = (int) Math.min(Math.min(Math.max(0, requested), o.open()), coverable);
		if (want <= 0) return DeliverResult.fail(Status.INVALID_AMOUNT);

		// compute claim chest keys BEFORE removing anything
		List<ItemStack> protos = new ArrayList<>();
		List<String> keys = new ArrayList<>();
		int have = 0;
		Inventory inv = deliverer.getInventory();
		for (int i = Items.MAIN_SIZE - 1; i >= 0; i--) {
			ItemStack s = inv.getItem(i);
			if (!accepts(o, item, s)) continue;
			have += s.getCount();
			boolean known = false;
			for (ItemStack p : protos) {
				if (ItemStack.isSameItemSameComponents(p, s)) {
					known = true;
					break;
				}
			}
			if (known) continue;
			ItemStack proto = s.copyWithCount(1);
			String key = null;
			if (!o.npc && !Items.isPristine(proto)) {
				try {
					key = ItemCodec.encode(proto);
				} catch (RuntimeException e) {
					SmpEconomy.LOG.error("[SMPEco] orders: item for order #{} cannot be saved, skipped", o.id, e);
					continue;
				}
			}
			protos.add(proto);
			keys.add(key);
		}
		int n = Math.min(want, have);
		if (n <= 0 || protos.isEmpty()) return DeliverResult.fail(Status.NO_ITEMS);
		Priced priced = price(who, o, n, unit);
		if (priced == null) return DeliverResult.fail(Status.NOT_ACCEPTING);
		n = priced.count;
		if (worseThanShown(expected, o, n, priced)) return DeliverResult.fail(Status.PRICE_CHANGED);
		if (priced.net > 0 && EconomyService.balance(who) > Money.MAX - priced.net) return DeliverResult.fail(Status.BALANCE_LIMIT);

		List<ItemStack> taken = new ArrayList<>();
		List<String> takenKeys = new ArrayList<>();
		int removed = 0;
		for (int i = 0; i < protos.size() && removed < n; i++) {
			ItemStack proto = protos.get(i);
			int k = Items.remove(deliverer, s -> accepts(o, item, s) && ItemStack.isSameItemSameComponents(s, proto), n - removed);
			if (k <= 0) continue;
			removed += k;
			taken.add(proto.copyWithCount(k));
			takenKeys.add(keys.get(i));
		}
		if (removed <= 0) return DeliverResult.fail(Status.NO_ITEMS);
		if (removed != n) {
			Priced again = price(who, o, removed, unit);
			if (again == null || again.count != removed || worseThanShown(expected, o, removed, again)) {
				for (ItemStack s : taken) Items.giveOrDrop(deliverer, s);
				return DeliverResult.fail(again == null ? Status.NOT_ACCEPTING : Status.PRICE_CHANGED);
			}
			priced = again;
			n = removed;
		}

		long gross = priced.gross;
		long fee = priced.fee;
		long net = priced.net;
		if (net > 0) {
			Result r = EconomyService.deposit(who, net);
			if (!r.ok()) {
				SmpEconomy.LOG.error("[SMPEco] orders: credit for {} failed ({}), items returned", deliverer.getPlainTextName(), r);
				for (ItemStack s : taken) Items.giveOrDrop(deliverer, s);
				return DeliverResult.fail(Status.ERROR);
			}
		}
		if (o.board) board.record(who, o, priced.payout);
		else o.escrow -= gross;
		o.delivered += n;
		if (fee > 0) funding.collectFee(fee);
		if (!o.npc && o.owner != null) {
			for (int i = 0; i < taken.size(); i++) addClaim(o.owner, Items.id(item), takenKeys.get(i), taken.get(i).getCount());
		}
		boolean completed = o.open() <= 0;
		if (completed) {
			if (o.board) board.onCompleted(o);
			Status s = refundAndRemove(o);
			if (!s.ok()) SmpEconomy.LOG.error("[SMPEco] orders: remaining escrow of #{} not refunded ({}), order stays", o.id, s);
		}
		markDirty.run();
		DataStore.requestPlayerSave(deliverer);
		if (!o.npc && o.owner != null) notifyDelivery(o, item, deliverer, n, gross - fee, completed);
		long bonus = priced.payout == null ? 0 : priced.payout.bonus();
		return new DeliverResult(Status.OK, n, priced.firstUnit, gross, fee, net, completed, bonus);
	}

	private record Priced(int count, long gross, long fee, long net, long firstUnit, NpcBoard.Payout payout) {}

	private Priced price(UUID who, Order o, int n, long unit) {
		if (o.board) {
			NpcBoard.Payout p = board.payout(who, o, n);
			if (p.count() <= 0 || p.total() <= 0) return null;
			return new Priced(p.count(), p.total(), 0, p.total(), p.first(), p);
		}
		long gross = Math.multiplyExact((long) n, unit);
		long fee = feeFor(o, n, unit);
		return new Priced(n, gross, fee, gross - fee, unit, null);
	}

	private static boolean worseThanShown(Terms shown, Order order, int count, Priced now) {
		if (now.firstUnit < shown.unit()) return true;
		if (now.payout != null) return shown.averageBelowShown(now.gross, now.count);
		long shownFee = shown.feeFor(order, count, now.firstUnit);
		return shownFee >= 0 && now.fee > shownFee;
	}

	/** If a player refund fails, the order stays. */
	private Status refundAndRemove(Order o) {
		long escrow = o.escrow;
		if (escrow > 0) {
			if (o.npc) {
				funding.refundEscrow(escrow);
			} else {
				if (o.owner == null) return Status.REFUND_FAILED;
				Result r = EconomyService.deposit(o.owner, escrow);
				if (!r.ok()) {
					SmpEconomy.LOG.error("[SMPEco] orders: refund {} to {} for #{} failed: {}", Money.format(escrow), o.owner, o.id, r);
					return Status.REFUND_FAILED;
				}
			}
		}
		o.escrow = 0;
		data.get().orders.remove(o.id);
		markDirty.run();
		return Status.OK;
	}

	private void addClaim(UUID owner, String itemId, String nbt, long count) {
		if (count <= 0) return;
		List<Claim> list = data.get().claims.computeIfAbsent(owner, k -> new ArrayList<>());
		for (Claim c : list) {
			if (c.item.equals(itemId) && Objects.equals(c.nbt, nbt)) {
				c.count = Math.addExact(c.count, count);
				return;
			}
		}
		list.add(new Claim(itemId, nbt, count));
	}

	/** Empty if the item can't be read any more; the entry is kept then. */
	public static Optional<ItemStack> prototype(Claim c) {
		if (c.nbt == null) return Items.byId(c.item).map(ItemStack::new);
		return ItemCodec.tryDecode(c.nbt).filter(s -> !s.isEmpty()).map(s -> s.copyWithCount(1));
	}

	public ClaimResult claim(ServerPlayer player, String itemId, String nbt, long max) {
		if (!ready()) return new ClaimResult(Status.NOT_READY, 0, 0);
		UUID id = player.getUUID();
		List<Claim> list = data.get().claims.get(id);
		Claim c = null;
		if (list != null) {
			for (Claim x : list) {
				if (x.item.equals(itemId) && Objects.equals(x.nbt, nbt)) {
					c = x;
					break;
				}
			}
		}
		if (c == null) return new ClaimResult(Status.NOT_FOUND, 0, 0);
		Optional<ItemStack> proto = prototype(c);
		if (proto.isEmpty()) return new ClaimResult(Status.BROKEN, 0, c.count);
		if (!Items.canReceive(player)) return new ClaimResult(Status.CANNOT_RECEIVE, 0, c.count);
		int fit = Items.spaceFor(player, proto.get());
		int give = (int) Math.min(Math.min((long) fit, c.count), Math.max(0, max));
		if (give <= 0) return new ClaimResult(fit <= 0 ? Status.NO_SPACE : Status.INVALID_AMOUNT, 0, c.count);
		c.count -= give;
		long left = c.count;
		if (c.count <= 0) list.remove(c);
		if (list.isEmpty()) data.get().claims.remove(id);
		markDirty.run();
		DataStore.requestPlayerSave(player);
		int dropped = Items.giveOrDrop(player, proto.get().copyWithCount(give));
		if (dropped > 0) SmpEconomy.LOG.warn("[SMPEco] orders: {} items from the claim chest of {} dropped (space miscalculated?)", dropped, player.getPlainTextName());
		return new ClaimResult(Status.OK, give, left);
	}

	public ClaimResult claimAll(ServerPlayer player) {
		if (!ready()) return new ClaimResult(Status.NOT_READY, 0, 0);
		int given = 0;
		Status last = Status.NOT_FOUND;
		for (Claim c : claims(player.getUUID())) {
			ClaimResult r = claim(player, c.item, c.nbt, Long.MAX_VALUE);
			given += r.given();
			if (r.status().ok()) last = Status.OK;
			else if (last != Status.OK) last = r.status();
		}
		long left = claimTotal(player.getUUID());
		return new ClaimResult(given > 0 ? Status.OK : last, given, left);
	}

	public int expireDue() {
		if (!ready()) return 0;
		long now = now();
		int n = 0;
		for (Order o : List.copyOf(data.get().orders.values())) {
			if (now < o.expiresAt) continue;
			long escrow = o.escrow;
			if (!refundAndRemove(o).ok()) continue;
			n++;
			if (!o.npc && o.owner != null) notifyExpired(o, escrow);
		}
		return n;
	}

	/** Fills the board even with nobody online: it costs nothing. */
	public void tick() {
		expireDue();
		if (ready()) board.maintain();
	}

	/**
	 * One-time migration of old regular NPC orders with treasury escrow, now replaced by the board: rest of the
	 * escrow back to the treasury, order removed. Bulk orders stay.
	 */
	public int migrateLegacy() {
		if (!ready()) return 0;
		int n = 0;
		long refunded = 0;
		for (Order o : List.copyOf(data.get().orders.values())) {
			if (!o.npc || o.bulk || o.board) continue;
			long escrow = o.escrow;
			if (!refundAndRemove(o).ok()) continue;
			n++;
			refunded += escrow;
		}
		if (n > 0) {
			OrderData.BoardStats st = data.get().boardStats;
			st.migrated += n;
			st.migratedEscrow = Math.addExact(st.migratedEscrow, refunded);
			markDirty.run();
			SmpEconomy.LOG.info("[SMPEco] orders: {} old NPC orders moved to the board, {} returned to the treasury", n, Money.format(refunded));
		}
		return n;
	}

	static int niceAmount(int x) {
		if (x >= 256) return x - x % 64;
		if (x >= 64) return x - x % 16;
		if (x >= 16) return x - x % 8;
		return Math.max(0, x);
	}

	/** Escrows above the hourly budget are not attempted since they could never succeed. Null means not created. */
	Order createNpcOrder(Item item, int amount, long price, long durationMs, String label, boolean bulk) {
		if (!ready() || item == null || amount <= 0 || price <= 0 || durationMs <= 0) return null;
		long escrow;
		try {
			escrow = Math.multiplyExact((long) amount, price);
		} catch (ArithmeticException e) {
			return null;
		}
		if (!Money.isValidAmount(escrow) || escrow > funding.maxSpendPerHour()) return null;
		if (!funding.withdrawEscrow(escrow)) return null;
		OrderData d = data.get();
		long now = now();
		Order o = new Order();
		o.id = d.nextId++;
		o.owner = null;
		o.npc = true;
		o.bulk = bulk;
		o.label = label == null || label.isBlank() ? (bulk ? BULK_LABEL : NPC_LABEL) : label;
		o.item = Items.id(item);
		o.amount = amount;
		o.price = price;
		o.escrow = escrow;
		o.createdAt = now;
		o.expiresAt = now + durationMs;
		d.orders.put(o.id, o);
		markDirty.run();
		return o;
	}

	/** NPC bulk order at the highest allowed price, amount limited by the treasury budget. */
	public Order startBulk(Item item, int amount, long durationMs, String label) {
		if (!ready() || item == null || amount <= 0 || durationMs <= 0) return null;
		OrdersConfig cfg = config.get();
		if (!cfg.npcEnabled) return null;
		int bulks = 0;
		for (Order o : data.get().orders.values()) {
			if (o.bulk) bulks++;
		}
		if (bulks >= cfg.maxBulkOrders) return null;
		if (needsTemplate(item) || isUnique(item)) return null; // NPC orders only take unmodified items
		long price = npcOfferCap(item);
		if (price <= 0) return null;
		long hourCap = funding.maxSpendPerHour();
		long budget = Math.min((long) Math.floor(hourCap * cfg.bulkBudgetPercent / 100.0), funding.allowance());
		if (budget <= 0) return null;
		long affordable = budget / price;
		int amt = (int) Math.min(Math.min(amount, affordable), cfg.maxAmountPerOrder);
		if (amt < Math.min(amount, cfg.bulkMinAmount) || amt <= 0) return null;
		return createNpcOrder(item, amt, price, Math.min(durationMs, MAX_BULK_MS), label, true);
	}

	/** No refund: the selftest player's account gets deleted anyway. */
	void purge(UUID player) {
		OrderData d = data.get();
		boolean changed = d.orders.values().removeIf(o -> o.isOwner(player));
		changed |= d.claims.remove(player) != null;
		changed |= d.away.remove(player) != null;
		changed |= d.bonus.remove(player) != null;
		if (changed) markDirty.run();
	}

	private void notifyDelivery(Order o, Item item, ServerPlayer deliverer, int count, long value, boolean completed) {
		UUID owner = o.owner;
		if (!Settings.get(owner, Settings.NOTIFICATIONS)) return;
		ServerPlayer online = onlinePlayer(owner);
		if (online != null) {
			MutableComponent line = Msg.legacy("§f" + deliverer.getGameProfile().name() + " §7delivered §f" + Msg.number(count) + "× ")
				.append(Msg.itemName(item).withStyle(ChatFormatting.WHITE))
				.append(Msg.legacy(" §8(#" + o.id + ": " + Msg.number(o.delivered) + "/" + Msg.number(o.amount) + ")§7."
					+ (completed ? " §aOrder complete!" : "") + " §7Claim: "))
				.append(Msg.clickRun("§e/orders", "/orders", "§7Open orders"));
			Msg.send(online, Msg.prefixed(line));
			return;
		}
		List<AwayNote> notes = data.get().away.computeIfAbsent(owner, k -> new ArrayList<>());
		AwayNote note = null;
		for (AwayNote n : notes) {
			if (n.orderId == o.id) note = n;
		}
		if (note == null) {
			note = new AwayNote();
			note.orderId = o.id;
			note.item = o.item;
			notes.add(note);
		}
		note.count += count;
		note.cents = Math.addExact(note.cents, Math.max(0, value));
		markDirty.run();
	}

	private void notifyExpired(Order o, long refunded) {
		UUID owner = o.owner;
		if (!Settings.get(owner, Settings.NOTIFICATIONS)) return;
		String text = "§7Your order §f#" + o.id + " §7has expired §8(" + Msg.number(o.delivered) + "/" + Msg.number(o.amount)
			+ " delivered)§7." + (refunded > 0 ? " Refunded: " + Msg.moneyStr(refunded) + "." : "");
		Msg.sendOrQueue(owner, text);
	}

	List<AwayNote> takeAwayNotes(UUID player) {
		List<AwayNote> notes = data.get().away.remove(player);
		if (notes == null) return List.of();
		markDirty.run();
		return notes;
	}

	static ServerPlayer onlinePlayer(UUID id) {
		MinecraftServer server = SmpEconomy.server();
		return server == null || id == null ? null : server.getPlayerList().getPlayer(id);
	}

	static Component itemComponent(String itemId) {
		return Items.byId(itemId).<Component>map(i -> Msg.itemName(i)).orElse(Component.literal(itemId));
	}
}
