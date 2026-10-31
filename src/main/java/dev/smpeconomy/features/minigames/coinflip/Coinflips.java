package dev.smpeconomy.features.minigames.coinflip;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.Treasury;
import dev.smpeconomy.features.minigames.MinigamesConfig;

/**
 * Player-vs-player coinflip with escrow: the stake is debited on creation and held in the flip ({@code
 * coinflips.json}). Accepting debits the same stake, the winner gets the pot minus a fee that goes to the treasury.
 * Cancelling and server stop refund. Money is never created: accounts + escrow + treasury stay constant.
 * <p>Server thread only.
 */
public final class Coinflips {
	public static final String C_WINS = "minigames.coinflip_wins";
	public static final String C_LOSSES = "minigames.coinflip_losses";
	public static final String C_WON_CENTS = "minigames.coinflip_won_cents";
	public static final String C_LOST_CENTS = "minigames.coinflip_lost_cents";
	/** Only for the id suffix, separate from the outcome {@link #random}. */
	private static final SecureRandom ID_RANDOM = new SecureRandom();

	public static final class Flip {
		public String id;
		public UUID creator;
		public String creatorName = "";
		public long amount;
		public long createdAt;
	}

	public static final class Data {
		public List<Flip> open = new ArrayList<>();
		public long seq;

		public void fixNulls() {
			if (open == null) open = new ArrayList<>();
			open.removeIf(f -> f == null || f.id == null || f.creator == null || f.amount <= 0);
			for (Flip f : open) {
				if (f.creatorName == null) f.creatorName = "";
			}
		}

		public long escrow() {
			long sum = 0;
			for (Flip f : open) sum = Math.addExact(sum, f.amount);
			return sum;
		}
	}

	public record Result(Flip flip, String error) {
		public boolean ok() {
			return error == null;
		}

		static Result fail(String error) {
			return new Result(null, error);
		}
	}

	public record Outcome(Flip flip, UUID acceptor, String acceptorName, UUID winner, UUID loser, long pot, long fee, long payout, String error) {
		public boolean ok() {
			return error == null;
		}

		public boolean creatorWon() {
			return flip != null && flip.creator.equals(winner);
		}

		public String winnerName() {
			return creatorWon() ? flip.creatorName : acceptorName;
		}

		public String loserName() {
			return creatorWon() ? acceptorName : flip.creatorName;
		}

		static Outcome fail(String error) {
			return new Outcome(null, null, null, null, null, 0, 0, 0, error);
		}
	}

	private final Supplier<Data> data;
	private final Runnable markDirty;
	private final Supplier<MinigamesConfig.Coinflip> config;
	private final Random random;
	private final LongSupplier clock;
	/** Last "coinflip started" announcement per creator. In memory only; expired entries are dropped. */
	private final Map<UUID, Long> lastAnnounced = new HashMap<>();

	public Coinflips(Supplier<Data> data, Runnable markDirty, Supplier<MinigamesConfig.Coinflip> config, Random random, LongSupplier clock) {
		this.data = data;
		this.markDirty = markDirty;
		this.config = config;
		this.random = random;
		this.clock = clock;
	}

	public List<Flip> open() {
		List<Flip> list = new ArrayList<>(data.get().open);
		list.sort(Comparator.comparingLong((Flip f) -> f.amount).reversed().thenComparingLong(f -> f.createdAt));
		return list;
	}

	public Optional<Flip> find(String id) {
		if (id == null) return Optional.empty();
		for (Flip f : data.get().open) {
			if (f.id.equals(id)) return Optional.of(f);
		}
		return Optional.empty();
	}

	public List<Flip> openOf(UUID player) {
		List<Flip> list = new ArrayList<>();
		for (Flip f : data.get().open) {
			if (f.creator.equals(player)) list.add(f);
		}
		return list;
	}

	public long escrow() {
		return data.get().escrow();
	}

	public long feeFor(long pot) {
		return Money.percentOf(pot, config.get().feePercent);
	}

	/**
	 * Announced only from {@code announceMinBet} and at most once per {@code announceCooldownSeconds} per player.
	 * Creating and cancelling is free, so "/cf" + "/cf cancel" could otherwise flood everyone's chat. A yes counts
	 * as the last announcement.
	 */
	public boolean claimCreateAnnouncement(UUID player, long amount) {
		MinigamesConfig.Coinflip cfg = config.get();
		if (amount < cfg.announceMinBet) return false;
		long now = clock.getAsLong();
		long cooldownMs = cfg.announceCooldownSeconds * 1000L;
		lastAnnounced.values().removeIf(t -> now < t || now - t >= cooldownMs);
		if (lastAnnounced.containsKey(player)) return false;
		lastAnnounced.put(player, now);
		return true;
	}

	/** Accepting costs the fee, hence no cooldown. */
	public boolean announcesResult(long amount) {
		return amount >= config.get().announceMinBet;
	}

	public Result create(UUID player, String name, long amount) {
		MinigamesConfig.Coinflip cfg = config.get();
		if (!Money.isValidAmount(amount)) return Result.fail("Invalid amount.");
		if (amount < cfg.minBet) return Result.fail("Minimum bet: " + Money.format(cfg.minBet));
		if (amount > cfg.maxBet) return Result.fail("Maximum bet: " + Money.format(cfg.maxBet));
		if (openOf(player).size() >= cfg.maxOpenPerPlayer) return Result.fail("You already have " + cfg.maxOpenPerPlayer + " open coinflips.");
		EconomyService.Result r = EconomyService.withdraw(player, amount);
		if (!r.ok()) return Result.fail(r.message());
		Data d = data.get();
		Flip f = new Flip();
		f.id = newId(++d.seq);
		f.creator = player;
		f.creatorName = name == null ? "" : name;
		f.amount = amount;
		f.createdAt = clock.getAsLong();
		d.open.add(f);
		markDirty.run();
		return new Result(f, null);
	}

	/**
	 * Sequence number (base 36) plus exactly 2 random characters. The fixed width keeps ids unique (with variable
	 * width, "1"+"ab" = "1a"+"b"). The suffix comes from its own generator: creating and cancelling must not
	 * advance the generator that decides wins and losses.
	 */
	public static String newId(long seq) {
		int r = ID_RANDOM.nextInt(36 * 36);
		return Long.toString(seq, 36) + Character.forDigit(r / 36, 36) + Character.forDigit(r % 36, 36);
	}

	/** Credits first, then removes. */
	public Result cancel(UUID player, String flipId) {
		Optional<Flip> found = find(flipId);
		if (found.isEmpty()) return Result.fail("This coinflip no longer exists.");
		Flip f = found.get();
		if (!f.creator.equals(player)) return Result.fail("That's not your coinflip.");
		EconomyService.Result r = EconomyService.deposit(f.creator, f.amount);
		if (!r.ok()) return Result.fail("Refund failed: " + r.message());
		data.get().open.remove(f);
		markDirty.run();
		return new Result(f, null);
	}

	public long cancelAll(UUID player) {
		long sum = 0;
		for (Flip f : openOf(player)) {
			if (cancel(player, f.id).ok()) sum += f.amount;
		}
		return sum;
	}

	/**
	 * Refunds all open flips (server stop, restart after a crash). Only refunded flips are removed; the rest stay
	 * saved and are retried next time. Returns the number refunded.
	 */
	public int refundAll(String reasonText) {
		int n = 0;
		for (Flip f : List.copyOf(data.get().open)) {
			EconomyService.Result r = EconomyService.deposit(f.creator, f.amount);
			if (!r.ok()) {
				SmpEconomy.LOG.error("[SMPEco] Coinflip {} by {} ({}) not refunded: {}", f.id, f.creatorName, Money.format(f.amount), r);
				continue;
			}
			data.get().open.remove(f);
			n++;
			Msg.sendOrQueue(f.creator, "§7Your open coinflip for " + Msg.moneyStr(f.amount) + " §7was refunded §8(" + reasonText + ")§7.");
		}
		if (n > 0) markDirty.run();
		return n;
	}

	/** {@code expectedAmount} protects against a flip that changed in between. */
	public Outcome accept(UUID acceptor, String acceptorName, String flipId, long expectedAmount) {
		return accept(acceptor, acceptorName, flipId, expectedAmount, null);
	}

	/**
	 * {@code creatorWins} forces the outcome (selftests only). Order: validate, debit the acceptor, remove the
	 * flip, pay the winner, send the fee to the treasury.
	 */
	public Outcome accept(UUID acceptor, String acceptorName, String flipId, long expectedAmount, Boolean creatorWins) {
		Optional<Flip> found = find(flipId);
		if (found.isEmpty()) return Outcome.fail("This coinflip no longer exists.");
		Flip f = found.get();
		if (f.creator.equals(acceptor)) return Outcome.fail("You can't accept your own coinflip.");
		if (f.amount != expectedAmount) return Outcome.fail("The bet has changed.");
		long pot;
		try {
			pot = Math.multiplyExact(f.amount, 2L);
		} catch (ArithmeticException e) {
			return Outcome.fail("Invalid amount.");
		}
		if (pot > Money.MAX) return Outcome.fail("Invalid amount.");
		long fee = Math.min(pot, feeFor(pot));
		long payout = pot - fee;
		if (!EconomyService.has(acceptor, f.amount)) return Outcome.fail("Not enough money (bet " + Money.format(f.amount) + ").");
		// both possible winners must be able to receive the prize, there is no way back afterwards
		if (EconomyService.balance(f.creator) > Money.MAX - payout || EconomyService.balance(acceptor) - f.amount > Money.MAX - payout) {
			return Outcome.fail("Balance limit reached.");
		}
		EconomyService.Result w = EconomyService.withdraw(acceptor, f.amount);
		if (!w.ok()) return Outcome.fail(w.message());
		data.get().open.remove(f);
		markDirty.run();

		boolean creatorWon = creatorWins != null ? creatorWins : random.nextBoolean();
		UUID winner = creatorWon ? f.creator : acceptor;
		UUID loser = creatorWon ? acceptor : f.creator;
		EconomyService.Result paid = payout > 0 ? EconomyService.deposit(winner, payout) : EconomyService.Result.OK;
		if (!paid.ok()) {
			// the check above should prevent this: refund both stakes, no fee
			SmpEconomy.LOG.error("[SMPEco] Coinflip {}: payout to {} failed ({}), refunding stakes", f.id, winner, paid);
			EconomyService.deposit(acceptor, f.amount);
			EconomyService.deposit(f.creator, f.amount);
			return Outcome.fail("Payout failed, bets refunded.");
		}
		if (fee > 0) {
			EconomyService.Result t = Treasury.deposit(fee, Treasury.R_COINFLIP_FEE);
			if (!t.ok()) SmpEconomy.LOG.warn("[SMPEco] Coinflip fee {} not booked to the treasury: {}", Money.format(fee), t);
		}
		PlayerDataService.addCounter(winner, C_WINS, 1);
		PlayerDataService.addCounter(loser, C_LOSSES, 1);
		if (payout - f.amount > 0) PlayerDataService.addCounter(winner, C_WON_CENTS, payout - f.amount);
		PlayerDataService.addCounter(loser, C_LOST_CENTS, f.amount);
		return new Outcome(f, acceptor, acceptorName == null ? "" : acceptorName, winner, loser, pot, fee, payout, null);
	}
}
