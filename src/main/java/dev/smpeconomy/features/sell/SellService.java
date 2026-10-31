package dev.smpeconomy.features.sell;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import dev.smpeconomy.core.compat.Mc;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.BasePrices;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Hooks;
import dev.smpeconomy.core.ItemCodec;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.PriceGuard;
import dev.smpeconomy.core.PriceModifiers;

/**
 * Selling logic for /sell and /worth with no GUI dependency, so selftests can reach it.
 * <p>All paths go through {@link #settle}, with the stacks already taken from the player. Each stack is then either
 * paid (one {@link EconomyService#deposit} over the total) or returned exactly once. What doesn't fit waits in the
 * return store of {@link SellJournal} and comes back once there is room, never as a drop. Until payment succeeds,
 * everything goes back on doubt (error, account limit, overflow, creative). Prices are read fresh from {@link
 * PriceGuard#effectiveSellPrice} per transaction; only {@link Items#isPristine} stacks sell.
 */
public final class SellService {
	public static final String COUNTER_EARNED = "sell.earned";
	public static final String COUNTER_ITEMS = "sell.items";

	static final String BLOCKED_MESSAGE = "You can't sell in Creative or Spectator mode.";

	private static final Map<UUID, PendingAll> PENDING_ALL = new HashMap<>();

	private SellService() {}

	public enum Status {
		SOLD,
		NOTHING_SELLABLE,
		CANCELLED,
		BLOCKED,
		FAILED,
		/**
		 * Not settled: contents are unsold in the crash journal and return on next join. Happens on server stop while
		 * the player can't receive anything, e.g. in the end credits.
		 */
		POSTPONED
	}

	/**
	 * What doesn't fit in the inventory is never dropped in any mode; it waits in the return store ({@link
	 * SellJournal#addReturn}) and comes back on join, after respawn or in the per-second sweep once there is room.
	 */
	public enum ReturnMode {
		/**
		 * Origin slot, main inventory, empty offhand. If the player can't receive anything right now (dead,
		 * disconnected, removed), everything goes to the return store, since items put in a dead player's inventory
		 * would be lost at respawn.
		 */
		NORMAL,
		/**
		 * Straight to the inventory (main, then empty offhand), even while dying at {@code ServerPlayer#die} HEAD:
		 * vanilla drops, {@code keep_inventory} and graves then treat them like any inventory item.
		 */
		INVENTORY,
		/**
		 * Back to the source first ({@link Taken#back}, e.g. a backpack slot), then the return store. Never the
		 * inventory, never a drop. Stacks without {@code back} behave like {@link #NORMAL}.
		 */
		TO_SOURCE
	}

	/**
	 * {@code originSlot} ≥ 0 is a main inventory slot to put it back in, otherwise -1. {@code back} (nullable)
	 * returns the stack to its source and gives back what didn't fit; only used by {@link ReturnMode#TO_SOURCE}.
	 */
	public record Taken(ItemStack stack, int originSlot, java.util.function.UnaryOperator<ItemStack> back) {
		public Taken(ItemStack stack, int originSlot) {
			this(stack, originSlot, null);
		}
	}

	public record Line(Item item, int count, long unitPrice, long total) {}

	/** {@code overflow}: total exceeds {@link Money#MAX}, so don't sell. */
	public record Quote(List<Line> lines, long total, int count, boolean overflow) {
		static final Quote EMPTY = new Quote(List.of(), 0, 0, false);

		public boolean isEmpty() {
			return count == 0;
		}
	}

	/**
	 * {@code returnedCount}: all items returned. Of those, {@code pendingCount} went to the return store for lack of
	 * room and {@code droppedCount} were dropped, only when the return store wasn't usable, which shouldn't happen.
	 */
	public record Outcome(Status status, long cents, int soldCount, List<Line> lines, List<String> bonuses, int returnedCount, int pendingCount,
			int droppedCount, String failure) {
		public boolean sold() {
			return status == Status.SOLD;
		}
	}

	/** {@code changed}: an open confirmation no longer matched inventory or prices. */
	public record Attempt(String error, Quote preview, boolean changed, Outcome outcome) {
		static Attempt error(String message) {
			return new Attempt(message, null, false, null);
		}

		static Attempt confirm(Quote quote, boolean changed) {
			return new Attempt(null, quote, changed, null);
		}

		static Attempt done(Outcome outcome) {
			return new Attempt(null, null, false, outcome);
		}
	}

	/**
	 * Only exactly this preview can be confirmed: same amounts per item, total no more than {@code
	 * confirmPriceTolerancePercent} below it. Anything else gets a new preview.
	 */
	private record PendingAll(long until, long total, Map<Item, Long> counts) {
		static PendingAll of(Quote q, long until) {
			return new PendingAll(until, q.total(), counts(q));
		}

		boolean matches(Quote now) {
			if (now.overflow() || !counts.equals(counts(now))) return false;
			long tolerance = Money.percentOf(total, SellConfig.get().confirmPriceTolerancePercent);
			return now.total() >= total - tolerance;
		}

		private static Map<Item, Long> counts(Quote q) {
			Map<Item, Long> m = new HashMap<>();
			for (Line l : q.lines()) m.merge(l.item(), (long) l.count(), Long::sum);
			return m;
		}
	}

	public static OptionalLong unitPrice(UUID player, ItemStack stack) {
		if (stack == null || stack.isEmpty() || !Items.isPristine(stack)) return OptionalLong.empty();
		return PriceGuard.effectiveSellPrice(player, stack.getItem());
	}

	public static Quote quote(UUID player, List<ItemStack> stacks) {
		return quote(player, stacks, new HashMap<>());
	}

	private static Quote quote(UUID player, List<ItemStack> stacks, Map<Item, OptionalLong> prices) {
		Map<Item, Long> counts = new LinkedHashMap<>();
		for (ItemStack s : stacks) {
			if (s == null || s.isEmpty() || !Items.isPristine(s)) continue;
			OptionalLong p = prices.computeIfAbsent(s.getItem(), i -> PriceGuard.effectiveSellPrice(player, i));
			if (p.isEmpty()) continue;
			counts.merge(s.getItem(), (long) s.getCount(), Long::sum);
		}
		if (counts.isEmpty()) return Quote.EMPTY;
		List<Line> lines = new ArrayList<>(counts.size());
		long total = 0;
		long count = 0;
		boolean overflow = false;
		for (Map.Entry<Item, Long> e : counts.entrySet()) {
			long unit = prices.get(e.getKey()).getAsLong();
			long n = e.getValue();
			long lineTotal;
			try {
				lineTotal = Math.multiplyExact(unit, n);
				total = Math.addExact(total, lineTotal);
			} catch (ArithmeticException ex) {
				overflow = true;
				lineTotal = Money.MAX;
			}
			count += n;
			lines.add(new Line(e.getKey(), (int) Math.min(Integer.MAX_VALUE, n), unit, lineTotal));
		}
		if (total > Money.MAX || count > Integer.MAX_VALUE) overflow = true;
		return new Quote(List.copyOf(lines), total, (int) Math.min(Integer.MAX_VALUE, count), overflow);
	}

	public static String notSellableReason(UUID player, ItemStack stack) {
		if (stack == null || stack.isEmpty()) return "No item.";
		if (BasePrices.sellPrice(stack.getItem()).isEmpty()) return "This item can't be sold.";
		if (!Items.isPristine(stack)) return "Can't be sold: " + modifiedReason(stack) + " – only unmodified items.";
		if (PriceGuard.effectiveSellPrice(player, stack.getItem()).isEmpty()) return "This item is worth nothing right now (under 1 cent).";
		return null;
	}

	public static String modifiedReason(ItemStack stack) {
		DataComponentPatch patch = stack.getComponentsPatch();
		if (patch.isEmpty()) return "unmodified";
		if (dev.smpeconomy.core.TraderGoods.isMarked(stack)) return "bought from a villager/trader";
		Set<DataComponentType<?>> types = new java.util.HashSet<>();
		types.addAll(Mc.patchChanges(patch).keySet());
		if (types.contains(DataComponents.CUSTOM_NAME)) return "renamed";
		if (types.contains(DataComponents.ENCHANTMENTS) || types.contains(DataComponents.STORED_ENCHANTMENTS)) return "enchanted";
		if (types.contains(DataComponents.DAMAGE)) return "damaged";
		if (types.contains(DataComponents.CONTAINER) || types.contains(DataComponents.BUNDLE_CONTENTS)) return "has contents";
		return "modified";
	}

	public static boolean isBlocked(ServerPlayer player) {
		return !SellConfig.get().allowCreative && Items.isCreativeOrSpectator(player);
	}

	/**
	 * Each stack in {@code taken} is either paid or returned exactly once. {@code sell == false} returns everything.
	 * Server thread.
	 */
	public static Outcome settle(ServerPlayer player, List<Taken> taken, boolean sell) {
		return settle(player, taken, sell, ReturnMode.NORMAL);
	}

	public static Outcome settle(ServerPlayer player, List<Taken> taken, boolean sell, ReturnMode mode) {
		UUID id = player.getUUID();
		List<Taken> back = taken; // until payment succeeds EVERYTHING goes back
		List<Taken> rejected = new ArrayList<>();
		Status status = Status.FAILED;
		Quote quote = Quote.EMPTY;
		String failure = null;
		boolean paid = false;
		boolean depositCalled = false;
		long balanceBefore = 0;
		try {
			if (!sell) {
				status = Status.CANCELLED;
			} else if (isBlocked(player)) {
				status = Status.BLOCKED;
				failure = BLOCKED_MESSAGE;
			} else {
				Map<Item, OptionalLong> prices = new HashMap<>();
				List<ItemStack> sellable = new ArrayList<>();
				for (Taken t : taken) {
					ItemStack s = t.stack();
					if (s.isEmpty()) continue;
					boolean ok = Items.isPristine(s) && prices.computeIfAbsent(s.getItem(), i -> PriceGuard.effectiveSellPrice(id, i)).isPresent();
					if (ok) sellable.add(s);
					else rejected.add(t);
				}
				quote = quote(id, sellable, prices);
				if (quote.isEmpty()) {
					status = Status.NOTHING_SELLABLE;
				} else if (quote.overflow()) {
					failure = "The amount is too large.";
				} else {
					balanceBefore = EconomyService.balance(id);
					depositCalled = true;
					EconomyService.Result r = EconomyService.deposit(id, quote.total());
					if (r.ok()) {
						paid = true;
						back = rejected;
						status = Status.SOLD;
					} else {
						failure = r.message();
					}
				}
			}
		} catch (RuntimeException e) {
			SmpEconomy.LOG.error("[SMPEco] sell: sale for {} failed", player.getPlainTextName(), e);
			if (!paid && depositCalled && EconomyService.balance(id) >= balanceBefore + quote.total()) {
				// the credit went through despite the exception: don't return the sold items, that would print money
				paid = true;
				back = rejected;
				status = Status.SOLD;
			} else if (!paid) {
				back = taken;
				status = Status.FAILED;
				failure = "Internal error.";
			}
		}

		int returned = 0;
		List<ItemStack> overflow = new ArrayList<>();
		for (Taken t : back) {
			if (t.stack().isEmpty()) continue;
			returned += t.stack().getCount();
			ItemStack rest = mode == ReturnMode.TO_SOURCE && t.back() != null ? restoreToSource(player, t) : restore(player, t, mode);
			if (!rest.isEmpty()) overflow.add(rest);
		}
		int pending = 0;
		int dropped = 0;
		if (!overflow.isEmpty()) {
			for (ItemStack s : overflow) pending += s.getCount();
			for (ItemStack s : keepForLater(player, overflow)) {
				// last resort, return store unusable: inventory or at the player's feet
				pending -= s.getCount();
				dropped += Items.giveOrDrop(player, s);
			}
		}

		List<String> bonuses = List.of();
		if (paid) {
			try {
				bonuses = bonuses(id, quote.lines());
				PlayerDataService.addCounter(id, COUNTER_EARNED, quote.total());
				PlayerDataService.addCounter(id, COUNTER_ITEMS, quote.count());
			} catch (RuntimeException e) {
				SmpEconomy.LOG.error("[SMPEco] sell: post-processing for {} failed (the sale is booked)", player.getPlainTextName(), e);
			}
			Hooks.fireItemsSold(player, quote.total(), quote.count(), soldLines(quote)); // catches listener errors itself
		}
		if (!taken.isEmpty()) DataStore.requestPlayerSave(player); // save inventory and balance together against duping on a crash
		return new Outcome(status, paid ? quote.total() : 0, paid ? quote.count() : 0, paid ? quote.lines() : List.of(), bonuses, returned,
			pending, dropped, failure);
	}

	static List<Hooks.SoldLine> soldLines(Quote quote) {
		List<Hooks.SoldLine> lines = new ArrayList<>(quote.lines().size());
		for (Line l : quote.lines()) lines.add(new Hooks.SoldLine(l.item(), l.count(), l.unitPrice()));
		return lines;
	}

	/**
	 * Origin slot if still empty, then main inventory, then empty offhand. With {@link ReturnMode#NORMAL} only when
	 * the player can receive right now. {@link ReturnMode#INVENTORY} places even for a dying player.
	 * @return what couldn't be placed; it goes to the return store, never on the ground
	 */
	private static ItemStack restore(ServerPlayer player, Taken t, ReturnMode mode) {
		ItemStack stack = t.stack();
		try {
			if (mode != ReturnMode.INVENTORY && !Items.canReceive(player)) return stack;
			return placeBack(player.getInventory(), stack, t.originSlot());
		} catch (RuntimeException e) {
			// should never happen; no substitute drop, a partial return could duplicate. Log loudly for a manual
			// admin return
			String encoded;
			try {
				encoded = ItemCodec.encode(stack);
			} catch (RuntimeException ex) {
				encoded = "?";
			}
			SmpEconomy.LOG.error("[SMPEco] sell: RETURN FAILED to {} ({}): {}x {} [{}]", player.getPlainTextName(), player.getUUID(),
				stack.getCount(), Items.id(stack.getItem()), encoded, e);
			return ItemStack.EMPTY;
		}
	}

	/**
	 * What doesn't fit, or everything if the way back throws, stays for the return store. Never inventory, never a
	 * drop.
	 */
	private static ItemStack restoreToSource(ServerPlayer player, Taken t) {
		ItemStack stack = t.stack();
		try {
			ItemStack rest = t.back().apply(stack.copy());
			return rest == null ? ItemStack.EMPTY : rest;
		} catch (RuntimeException e) {
			// like restore(): no substitute, a partly returned stack would duplicate. Log loudly for a manual admin
			// return
			String encoded;
			try {
				encoded = ItemCodec.encode(stack);
			} catch (RuntimeException ex) {
				encoded = "?";
			}
			SmpEconomy.LOG.error("[SMPEco] sell: RETURN TO SOURCE FAILED to {} ({}): {}x {} [{}]", player.getPlainTextName(), player.getUUID(),
				stack.getCount(), Items.id(stack.getItem()), encoded, e);
			return ItemStack.EMPTY;
		}
	}

	/**
	 * @return stacks that could not be stored (journal not loaded, stack not encodable; shouldn't happen). The caller
	 * returns them through {@link Items#giveOrDrop} as a last resort
	 */
	private static List<ItemStack> keepForLater(ServerPlayer player, List<ItemStack> overflow) {
		List<ItemStack> unstored;
		try {
			unstored = SellJournal.addReturn(player, overflow);
		} catch (RuntimeException e) {
			SmpEconomy.LOG.error("[SMPEco] sell: return store for {} unusable", player.getPlainTextName(), e);
			unstored = overflow;
		}
		for (ItemStack s : unstored) {
			SmpEconomy.LOG.error("[SMPEco] sell: {}x {} for {} cannot go in the return store, returning through giveOrDrop", s.getCount(),
				Items.id(s.getItem()), player.getPlainTextName());
		}
		return unstored;
	}

	/**
	 * Origin slot if still empty, then main inventory (slots 0-35, topping up matching stacks before empty slots),
	 * then the empty offhand. Never armor, never {@code Inventory.add}, which deletes overflow in creative. No {@code
	 * canReceive} check.
	 * @return what didn't fit; {@code stack} itself is not changed
	 */
	static ItemStack placeBack(Inventory inv, ItemStack stack, int originSlot) {
		if (stack.isEmpty()) return ItemStack.EMPTY;
		if (originSlot >= 0 && originSlot < Items.MAIN_SIZE && inv.getItem(originSlot).isEmpty()) {
			inv.setItem(originSlot, stack.copy());
			inv.setChanged();
			return ItemStack.EMPTY;
		}
		ItemStack rest = stack.copy();
		insertMain(inv, rest);
		if (!rest.isEmpty() && inv.getItem(Inventory.SLOT_OFFHAND).isEmpty()) {
			inv.setItem(Inventory.SLOT_OFFHAND, rest.split(Math.min(maxStack(inv, rest), rest.getCount())));
			inv.setChanged();
		}
		return rest;
	}

	static int returnSpace(Inventory inv, ItemStack stack) {
		if (stack.isEmpty()) return 0;
		int max = maxStack(inv, stack);
		long space = 0;
		for (int i = 0; i < Items.MAIN_SIZE; i++) {
			ItemStack slot = inv.getItem(i);
			if (slot.isEmpty()) space += max;
			else if (ItemStack.isSameItemSameComponents(slot, stack)) space += Math.max(0, max - slot.getCount());
		}
		if (inv.getItem(Inventory.SLOT_OFFHAND).isEmpty()) space += max;
		return (int) Math.min(Integer.MAX_VALUE, space);
	}

	private static int maxStack(Inventory inv, ItemStack stack) {
		return Math.max(1, inv.getMaxStackSize(stack));
	}

	/**
	 * Slots 0-35, topping up matching stacks first; {@code rest} shrinks by what went in. Deliberately not {@code
	 * Inventory.add}, which deletes overflow in creative.
	 */
	private static void insertMain(Inventory inv, ItemStack rest) {
		int max = maxStack(inv, rest);
		boolean changed = false;
		for (int pass = 0; pass < 2 && !rest.isEmpty(); pass++) {
			for (int i = 0; i < Items.MAIN_SIZE && !rest.isEmpty(); i++) {
				ItemStack slot = inv.getItem(i);
				if (pass == 0 && !slot.isEmpty() && ItemStack.isSameItemSameComponents(slot, rest) && slot.getCount() < max) {
					int add = Math.min(max - slot.getCount(), rest.getCount());
					slot.grow(add);
					rest.shrink(add);
					changed = true;
				} else if (pass == 1 && slot.isEmpty()) {
					inv.setItem(i, rest.split(Math.min(max, rest.getCount())));
					changed = true;
				}
			}
		}
		if (changed) inv.setChanged();
	}

	public static Attempt sellHand(ServerPlayer player) {
		Inventory inv = player.getInventory();
		int slot = inv.getSelectedSlot();
		ItemStack hand = inv.getItem(slot);
		if (hand.isEmpty()) return Attempt.error("You're not holding anything.");
		String why = notSellableReason(player.getUUID(), hand);
		if (why != null) return Attempt.error(why);
		if (isBlocked(player)) return Attempt.error(BLOCKED_MESSAGE);
		inv.setItem(slot, ItemStack.EMPTY);
		inv.setChanged();
		return Attempt.done(settle(player, List.of(new Taken(hand, slot)), true));
	}

	/**
	 * First call returns a preview ({@link Attempt#preview}). A second within {@code confirmSeconds} sells, but only
	 * if exactly what was shown would still sell: same amounts per item, total at most {@code
	 * confirmPriceTolerancePercent} lower. Otherwise a new preview with {@link Attempt#changed} and a new deadline.
	 * Main inventory only, never armor or offhand.
	 */
	public static Attempt sellAll(ServerPlayer player, long nowMs) {
		UUID id = player.getUUID();
		if (isBlocked(player)) {
			PENDING_ALL.remove(id);
			return Attempt.error(BLOCKED_MESSAGE);
		}
		Inventory inv = player.getInventory();
		List<ItemStack> main = new ArrayList<>(Items.MAIN_SIZE);
		for (int i = 0; i < Items.MAIN_SIZE; i++) main.add(inv.getItem(i));
		Quote preview = quote(id, main);
		if (preview.isEmpty()) {
			PENDING_ALL.remove(id);
			return Attempt.error("You have nothing sellable in your inventory.");
		}
		PendingAll pending = PENDING_ALL.get(id);
		long until = nowMs + SellConfig.get().confirmSeconds * 1000L;
		if (pending == null || nowMs > pending.until()) {
			PENDING_ALL.put(id, PendingAll.of(preview, until));
			return Attempt.confirm(preview, false);
		}
		if (!pending.matches(preview)) {
			// inventory or prices (crash, hype ending) changed since the preview
			PENDING_ALL.put(id, PendingAll.of(preview, until));
			return Attempt.confirm(preview, true);
		}
		PENDING_ALL.remove(id);
		List<Taken> taken = new ArrayList<>();
		for (int i = 0; i < Items.MAIN_SIZE; i++) {
			ItemStack s = inv.getItem(i);
			if (unitPrice(id, s).isEmpty()) continue;
			taken.add(new Taken(s, i));
			inv.setItem(i, ItemStack.EMPTY);
		}
		inv.setChanged();
		return Attempt.done(settle(player, taken, true));
	}

	static void clearPending(UUID player) {
		PENDING_ALL.remove(player);
	}

	static List<String> bonuses(UUID player, List<Line> lines) {
		Set<String> set = new LinkedHashSet<>();
		boolean capped = false;
		for (Line l : lines) {
			PriceGuard.Breakdown b = PriceGuard.breakdown(player, l.item());
			for (PriceModifiers.Active a : b.itemParts()) addBonus(set, a.current(), a.label());
			for (Hooks.Part p : b.sellParts()) addBonus(set, p.factor(), p.label());
			capped |= b.sellCapped();
		}
		List<String> out = new ArrayList<>(set);
		if (capped) out.add("§8Price cap");
		return out;
	}

	private static void addBonus(Set<String> set, double factor, String label) {
		String pct = PriceGuard.percent(factor);
		if ("±0%".equals(pct)) return;
		set.add((factor >= 1 ? "§a" : "§c") + pct + " " + label);
	}

	static String bonusSuffix(List<String> bonuses) {
		if (bonuses.isEmpty()) return "";
		int shown = Math.min(3, bonuses.size());
		StringBuilder sb = new StringBuilder(" §8(");
		for (int i = 0; i < shown; i++) {
			if (i > 0) sb.append("§8, ");
			sb.append(bonuses.get(i));
		}
		if (bonuses.size() > shown) sb.append("§8, +").append(bonuses.size() - shown);
		return sb.append("§8)").toString();
	}

	static String items(long n) {
		return Msg.number(n) + (n == 1 ? " item" : " items");
	}

	static String summaryText(Outcome o) {
		return "§aSold §f" + items(o.soldCount()) + " §7for " + Msg.moneyStr(o.cents()) + bonusSuffix(o.bonuses());
	}

	static MutableComponent summaryComponent(Outcome o) {
		MutableComponent hover = Component.empty();
		List<Line> sorted = new ArrayList<>(o.lines());
		sorted.sort(Comparator.comparingLong(Line::total).reversed());
		int max = SellConfig.get().summaryHoverLines;
		for (int i = 0; i < sorted.size() && i < max; i++) {
			Line l = sorted.get(i);
			if (i > 0) hover.append(Component.literal("\n"));
			hover.append(Msg.legacy("§f" + Msg.number(l.count()) + "× "))
				.append(Msg.itemName(l.item()).withStyle(ChatFormatting.WHITE))
				.append(Msg.legacy(" §7at " + Money.format(l.unitPrice()) + " = §a" + Money.format(l.total()) + changeSuffix(l)));
		}
		if (sorted.size() > max) hover.append(Msg.legacy("\n§8… and " + (sorted.size() - max) + " more"));
		if (!o.bonuses().isEmpty()) {
			hover.append(Msg.legacy("\n\n§7Active:"));
			for (String b : o.bonuses()) hover.append(Msg.legacy("\n" + b));
		}
		MutableComponent line = Msg.prefixed(summaryText(o));
		return line.withStyle(s -> s.withHoverEvent(new HoverEvent.ShowText(hover)));
	}

	private static String changeSuffix(Line l) {
		OptionalLong base = BasePrices.sellPrice(l.item());
		if (base.isEmpty() || base.getAsLong() <= 0 || base.getAsLong() == l.unitPrice()) return "";
		return " §8(" + PriceGuard.percent((double) l.unitPrice() / base.getAsLong()) + ")";
	}

	static String pendingText(int n) {
		return "§eInventory full: §f" + items(n) + " §e" + (n == 1 ? "is" : "are") + " kept for you and " + (n == 1 ? "comes" : "come")
			+ " back automatically once you have space.";
	}

	/** Not during disconnect or server stop; those use a pending message. */
	static boolean canReceiveChat(ServerPlayer player) {
		return !SmpEconomy.isStopping() && !player.hasDisconnected() && player.connection != null && player.connection.isAcceptingMessages();
	}

	public static void report(ServerPlayer player, Outcome o) {
		List<String> lines = new ArrayList<>();
		MutableComponent summary = null;
		switch (o.status()) {
			case SOLD -> summary = summaryComponent(o);
			case CANCELLED -> {
				if (o.returnedCount() > 0) lines.add("§7Sale cancelled – §f" + items(o.returnedCount()) + " §7returned.");
			}
			case BLOCKED -> lines.add("§c" + BLOCKED_MESSAGE + (o.returnedCount() > 0 ? " §7Items returned." : ""));
			case FAILED -> lines.add("§cSale failed: " + (o.failure() == null ? "unknown error." : o.failure())
				+ (o.returnedCount() > 0 ? " §7All items returned." : ""));
			case NOTHING_SELLABLE -> {
				if (o.returnedCount() > 0) lines.add("§eNone of it can be sold – §f" + items(o.returnedCount()) + " §ereturned.");
			}
			case POSTPONED -> {
				// the journal handles the return and its notice on the next join
			}
		}
		if (o.status() == Status.SOLD && o.returnedCount() > 0) {
			lines.add("§e" + (o.returnedCount() == 1 ? "1 item couldn't be sold and was returned."
				: Msg.number(o.returnedCount()) + " items couldn't be sold and were returned."));
		}
		if (o.pendingCount() > 0) lines.add(pendingText(o.pendingCount()));
		if (o.droppedCount() > 0) lines.add("§eInventory full: §f" + items(o.droppedCount()) + " §e" + (o.droppedCount() == 1 ? "was" : "were") + " dropped at your feet.");

		if (canReceiveChat(player)) {
			if (summary != null) Msg.send(player, summary);
			for (String l : lines) Msg.info(player, l);
		} else {
			UUID id = player.getUUID();
			if (summary != null) PlayerDataService.addPendingMessage(id, summaryText(o) + " §8(sell menu closed when you left)");
			for (String l : lines) PlayerDataService.addPendingMessage(id, l);
		}
	}

	public static List<Component> worthLines(UUID player, ItemStack stack, int stackCount) {
		List<Component> out = new ArrayList<>();
		Item item = stack.getItem();
		out.add(Msg.prefixed(Msg.legacy("§7Worth: ").append(Msg.itemName(stack).withStyle(ChatFormatting.WHITE))
			.append(Msg.legacy(" §8(×" + Msg.number(stackCount) + ")"))));
		OptionalLong base = BasePrices.sellPrice(item);
		if (base.isEmpty()) {
			out.add(Msg.legacy("§8  ▪ §cCan't be sold (no price)."));
			return out;
		}
		PriceGuard.Breakdown b = PriceGuard.breakdown(player, item);
		out.add(Msg.legacy("§8  ▪ §7Base price: " + Msg.moneyStr(base.getAsLong()) + " each"));
		long eff = b.effectiveSell();
		if (eff > 0) {
			double factor = (double) eff / base.getAsLong();
			String pct = PriceGuard.percent(factor);
			out.add(Msg.legacy("§8  ▪ §7Modifier: " + ("±0%".equals(pct) ? "§fnone" : (factor >= 1 ? "§a" : "§c") + pct)));
		}
		for (PriceModifiers.Active a : b.itemParts()) out.add(Msg.legacy("§8      " + (a.current() >= 1 ? "§a▲ " : "§c▼ ") + PriceGuard.percent(a.current()) + " " + a.label()));
		for (Hooks.Part p : b.sellParts()) out.add(Msg.legacy("§8      " + (p.factor() >= 1 ? "§a▲ " : "§c▼ ") + PriceGuard.percent(p.factor()) + " " + p.label()));
		if (b.sellCapped()) out.add(Msg.legacy("§8      Sale capped (price cap)"));
		if (eff <= 0) {
			out.add(Msg.legacy("§8  ▪ §cCan't be sold right now (price under 1 cent)."));
			return out;
		}
		if (!Items.isPristine(stack)) {
			out.add(Msg.legacy("§8  ▪ §7Effective: " + Msg.moneyStr(eff) + " each §8(if unmodified)"));
			out.add(Msg.legacy("§8  ▪ §cThis stack can't be sold: " + modifiedReason(stack) + " §8(only unmodified items)"));
			return out;
		}
		long stackValue;
		try {
			stackValue = Math.min(Money.MAX, Math.multiplyExact(eff, (long) stackCount));
		} catch (ArithmeticException e) {
			stackValue = Money.MAX;
		}
		out.add(Msg.legacy("§8  ▪ §7Effective: " + Msg.moneyStr(eff) + " each §8· §7Stack (" + Msg.number(stackCount) + "): " + Msg.moneyStr(stackValue)));
		return out;
	}
}
