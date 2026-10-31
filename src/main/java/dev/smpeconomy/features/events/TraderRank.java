package dev.smpeconomy.features.events;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.BasePrices;
import dev.smpeconomy.core.Bots;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Hooks;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.PriceGuard;

/**
 * Sales to the system (/sell, shop right-click, reported through {@code Hooks.onItemsSold}) decide a rank
 * (Apprentice, Trader, Merchant, Magnate, Tycoon) with a sell bonus (+0/2/5/8/12 %) that enters {@code
 * PriceGuard} as sell multiplier {@code "events.trader_rank"} (pivot cap applies). Sales are stored as
 * counter {@link #COUNTER} in PlayerDataService, so they are readable offline.
 *
 * <p>Net rather than gross: shop items can always be bought back at pivot + 1 (crash, flash sale) and sold at
 * up to pivot (hype, Market Day), so a round costs only 1 cent per piece. To keep rank from being cheap to
 * buy, a shop item counts per piece at most {@link #NET_CREDIT_FACTOR} × (pivot + 1 - sell price), four times
 * the minimum loss of such a round ({@link #deduction}). At normal prices (sale at or below 80 % of pivot)
 * the full revenue counts. Rank progress through buy and sell rounds thus always costs at least 25 % of the
 * credited sales, more than any rank bonus (at most {@link EventsConfig#MAX_RANK_BONUS_PERCENT} %) ever
 * returns on that amount.
 *
 * <p>sell and shop report which items were sold with the hook ({@link Hooks.SoldLine}: item, count, unit
 * price paid). This credits exactly: sum of count × (price - {@link #deduction}) ({@link #netCredit}),
 * regardless of what else gets priced for the player in the same tick (GUIs, NPC buyers). Only with a missing
 * or inconsistent breakdown (sum of count × price differs from revenue) is it estimated cautiously: all shop
 * items give two safe upper bounds for the deduction, count × largest per-piece deduction and revenue ×
 * largest deduction share of the price ({@link Bound}), and the smaller is deducted ({@link #credit}). Being
 * exact including non-shop items bought through NPC offers would need a buy hook in core.
 */
public final class TraderRank {
	/** Counter with the credited sales in cents. */
	public static final String COUNTER = "events.trader_sales";
	/** Hook id of the sell multiplier. */
	public static final String HOOK_ID = "events.trader_rank";
	public static final String HOOK_LABEL = "Trader Rank";
	/**
	 * A shop item counts per piece at most this many times the minimum buyback loss (pivot + 1 - sell price).
	 */
	public static final int NET_CREDIT_FACTOR = 4;

	/** Hook listener: credits the reported lines (without lines: cautious estimate). */
	static final Hooks.ItemsSoldListener LISTENER = new Hooks.ItemsSoldListener() {
		@Override
		public void onItemsSold(ServerPlayer player, long cents, int count) {
			TraderRank.onItemsSold(player, cents, count, List.of());
		}

		@Override
		public void onItemsSold(ServerPlayer player, long cents, int count, List<Hooks.SoldLine> lines) {
			TraderRank.onItemsSold(player, cents, count, lines);
		}
	};

	/** Log an inconsistent breakdown only once. */
	private static boolean warnedMismatch;

	/** Index 0 = lowest. */
	public record Rank(int index, String id, String name, String color, long minSales, double bonusPercent) {
		/** Factor on the sell price, e.g. 1.05. */
		public double multiplier() {
			return 1.0 + bonusPercent / 100.0;
		}

		/** Colored name, e.g. "§bMerchant". */
		public String display() {
			return color + name;
		}

		/** "+5%" or "±0%". */
		public String bonusLabel() {
			return bonusPercent <= 0 ? "±0%" : "+" + trim(bonusPercent) + "%";
		}
	}

	private TraderRank() {}

	/** 1.0 = lowest rank or unknown player. */
	public static double multiplier(UUID player) {
		return rank(player).multiplier();
	}

	/** In cents; 0 for unknown players. Creates no data. */
	public static long lifetimeSales(UUID player) {
		return player == null ? 0 : PlayerDataService.getCounter(player, COUNTER);
	}

	public static Rank rank(UUID player) {
		return rankFor(lifetimeSales(player), ranks());
	}

	public static List<Rank> ranks() {
		return ranksOf(EventsFeature.CONFIG.get());
	}

	public static Optional<Rank> next(Rank current) {
		List<Rank> all = ranks();
		return current.index() + 1 < all.size() ? Optional.of(all.get(current.index() + 1)) : Optional.empty();
	}

	static List<Rank> ranksOf(EventsConfig cfg) {
		List<Rank> list = new ArrayList<>();
		for (int i = 0; i < cfg.traderRanks.size(); i++) {
			EventsConfig.Rank r = cfg.traderRanks.get(i);
			list.add(new Rank(i, r.id, r.name, r.color, r.minSales, Math.max(0, Math.min(EventsConfig.MAX_RANK_BONUS_PERCENT, r.bonusPercent))));
		}
		return list;
	}

	/**
	 * Highest rank whose threshold {@code sales} reaches (ranks ascending; empty list gives a neutral rank).
	 */
	static Rank rankFor(long sales, List<Rank> ranks) {
		Rank best = ranks.isEmpty() ? new Rank(0, "apprentice", "Apprentice", "§7", 0, 0) : ranks.get(0);
		for (Rank r : ranks) {
			if (sales >= r.minSales()) best = r;
		}
		return best;
	}

	/** In [0, 1]; 1.0 at the highest rank. */
	static double progress(long sales, Rank current, Optional<Rank> next) {
		if (next.isEmpty()) return 1.0;
		long span = next.get().minSales() - current.minSales();
		if (span <= 0) return 1.0;
		return Math.max(0, Math.min(1.0, (sales - current.minSales()) / (double) span));
	}

	/** Capped at {@link Money#MAX}; returns the new total. */
	static long addSales(UUID player, long cents) {
		long current = lifetimeSales(player);
		if (cents <= 0) return current;
		long next = current > Money.MAX - cents ? Money.MAX : current + cents;
		if (next != current) PlayerDataService.setCounter(player, COUNTER, next);
		return next;
	}

	/** Admin and tests. 0 deletes the entry. */
	public static void setSales(UUID player, long cents) {
		PlayerDataService.setCounter(player, COUNTER, Math.max(0, Math.min(Money.MAX, cents)));
	}

	/**
	 * Deduction per piece when a shop item (pivot {@code pivot} >= 0) sells to the system for {@code price}:
	 * {@code max(0, price - K × (pivot + 1 - price))} with K = {@link #NET_CREDIT_FACTOR}. At most K times
	 * the minimum buyback loss (at least pivot + 1) stays credited. No shop item ({@code pivot < 0}) gives 0;
	 * a price above the pivot (must not exist) counts nothing.
	 */
	static long deduction(long price, long pivot) {
		if (pivot < 0 || price <= 0) return 0;
		if (price > pivot) return price;
		long allowed = NET_CREDIT_FACTOR * (pivot + 1 - price);
		return Math.max(0, price - allowed);
	}

	/**
	 * Upper bounds on a sale's deduction from a set of possible items: largest per-piece deduction ({@code
	 * perItem}) and largest deduction share of the unit price ({@code shareNum / shareDen}, an exact
	 * fraction, at most 1).
	 */
	record Bound(long perItem, long shareNum, long shareDen) {
		static final Bound NONE = new Bound(0, 0, 1);

		/** An item selling for {@code price}, with {@code deduction} taken off. */
		static Bound of(long price, long deduction) {
			if (price <= 0 || deduction <= 0) return NONE;
			long d = Math.min(deduction, price);
			return new Bound(d, d, price);
		}

		Bound max(Bound o) {
			boolean otherShare = BigInteger.valueOf(o.shareNum).multiply(BigInteger.valueOf(shareDen))
				.compareTo(BigInteger.valueOf(shareNum).multiply(BigInteger.valueOf(o.shareDen))) > 0;
			return new Bound(Math.max(perItem, o.perItem), otherShare ? o.shareNum : shareNum, otherShare ? o.shareDen : shareDen);
		}
	}

	/**
	 * Credited sales of a sale ({@code cents} for {@code count} pieces), never negative. For each sold item X
	 * the deduction dₓ <= {@code perItem} and dₓ <= share × price pₓ, so Σ nₓ·dₓ <= count × perItem and Σ
	 * nₓ·dₓ <= share × cents. Both are safe upper bounds, and the smaller is deducted. The first fits
	 * expensive items next to cheap ones at the pivot, the second many cheap items next to a Market Day item.
	 * Unknown count ({@code count <= 0}) uses only the share.
	 */
	static long credit(long cents, int count, Bound bound) {
		if (cents <= 0) return 0;
		long cut = Math.min(perItemCut(cents, count, bound.perItem()), shareCut(cents, bound));
		return cents - cut;
	}

	/** {@code count × perItem}, at most {@code cents}, no overflow. */
	private static long perItemCut(long cents, int count, long perItem) {
		if (perItem <= 0) return 0;
		if (count <= 0 || count > cents / perItem) return cents;
		return count * perItem;
	}

	/** ⌈cents × shareNum / shareDen⌉, at most {@code cents}. */
	private static long shareCut(long cents, Bound b) {
		if (b.shareNum() <= 0) return 0;
		if (b.shareNum() >= b.shareDen()) return cents;
		BigInteger den = BigInteger.valueOf(b.shareDen());
		return BigInteger.valueOf(cents).multiply(BigInteger.valueOf(b.shareNum())).add(den.subtract(BigInteger.ONE)).divide(den).longValueExact();
	}

	/** Bounds over {@code items} at this player's current sell prices. */
	static Bound bound(UUID player, Iterable<Item> items) {
		Bound max = Bound.NONE;
		for (Item item : items) {
			long pivot = PriceGuard.pivotPrice(item);
			if (pivot < 0) continue;
			OptionalLong price = PriceGuard.effectiveSellPrice(player, item);
			if (price.isPresent()) max = max.max(Bound.of(price.getAsLong(), deduction(price.getAsLong(), pivot)));
		}
		return max;
	}

	/**
	 * Exact credit from the reported lines: sum of count × (unit price paid - {@link #deduction}), at most
	 * {@code cents}. Empty if the lines do not match the sale (no lines, sum of count × price differs from
	 * {@code cents}, sum of counts differs from {@code count}, count or price <= 0, overflow); the cautious
	 * estimate then applies.
	 */
	static OptionalLong netCredit(long cents, int count, List<Hooks.SoldLine> lines) {
		if (cents <= 0 || lines == null || lines.isEmpty()) return OptionalLong.empty();
		long paid = 0;
		long items = 0;
		long credit = 0;
		try {
			for (Hooks.SoldLine l : lines) {
				if (l == null || l.item() == null || l.count() <= 0 || l.unitPrice() <= 0) return OptionalLong.empty();
				paid = Math.addExact(paid, Math.multiplyExact(l.count(), l.unitPrice()));
				items = Math.addExact(items, l.count());
				long unit = l.unitPrice() - deduction(l.unitPrice(), PriceGuard.pivotPrice(l.item()));
				credit = Math.addExact(credit, Math.multiplyExact(l.count(), Math.max(0, unit)));
			}
		} catch (ArithmeticException e) {
			return OptionalLong.empty();
		}
		if (paid != cents || items != count) return OptionalLong.empty();
		return OptionalLong.of(Math.min(cents, credit));
	}

	/**
	 * Exact from the lines ({@link #netCredit}), cautious over all shop items ({@link #credit}) with missing
	 * or inconsistent lines.
	 */
	static long creditFor(UUID player, long cents, int count, List<Hooks.SoldLine> lines) {
		if (cents <= 0) return 0;
		OptionalLong exact = netCredit(cents, count, lines);
		if (exact.isPresent()) return exact.getAsLong();
		return credit(cents, count, bound(player, BasePrices.shopBuyPrices().keySet()));
	}

	/**
	 * {@code Hooks.onItemsSold} through {@link #LISTENER}: counts credited sales and congratulates on rank
	 * up.
	 */
	static void onItemsSold(ServerPlayer player, long cents, int count, List<Hooks.SoldLine> lines) {
		if (player == null || cents <= 0) return;
		UUID id = player.getUUID();
		long credit = creditFor(id, cents, count, lines);
		if (!warnedMismatch && lines != null && !lines.isEmpty() && !Bots.isBot(player) && netCredit(cents, count, lines).isEmpty()) {
			warnedMismatch = true; // Error in the selling feature: report loudly once
			SmpEconomy.LOG.warn("[SMPEco] events: sale lines do not match revenue ({} cents, {} pieces, {}), trader rank credits cautiously",
				cents, count, lines);
		}
		if (credit <= 0) return;
		Rank before = rank(id);
		addSales(id, credit);
		Rank after = rank(id);
		if (after.index() > before.index() && !Bots.isBot(player)) announceRankUp(player, after);
	}

	private static void announceRankUp(ServerPlayer player, Rank rank) {
		Msg.success(player, "New Trader Rank: " + rank.display() + " §7(§a" + rank.bonusLabel() + " §7sell bonus)");
		if (player.connection == null) return;
		player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 50, 15));
		player.connection.send(new ClientboundSetTitleTextPacket(Msg.legacy("§6§lRANK UP!")));
		player.connection.send(new ClientboundSetSubtitleTextPacket(Msg.legacy(rank.display() + " §7– §a" + rank.bonusLabel() + " §7when selling")));
		Gui.sound(player, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.PLAYER_LEVELUP), 1.0f);
	}

	static String trim(double v) {
		return v == Math.rint(v) ? Long.toString((long) v) : String.format(java.util.Locale.ROOT, "%.1f", v);
	}
}
