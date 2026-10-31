package dev.smpeconomy.features.events;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.TimeUtil;
import dev.smpeconomy.core.Treasury;

/**
 * Tickets are atomic transfers from player to treasury ({@link Bank#collect}) and only sell while the treasury covers
 * everything the draw can cost ({@link #covered}), so prizes are already covered obligations, like casino wins, and are paid
 * uncapped ({@link Bank#pay}), never below the reserve. Smaller prizes go first, the jackpot last. If the treasury still
 * falls short because it spent money between the last ticket and the draw, what lies above the reserve is paid and the rest
 * stays as {@link Owed} in {@code lotto.json} until {@link #settleOwed} can pay it. No money appears or vanishes.
 *
 * <p>The draw is a single step on the server thread; lotto.json and economy.json are saved together, all or nothing, so each
 * draw pays once. Winning numbers depend only on the draw's {@link Data#seed}, rolled and stored at opening: if an older
 * state loads after a crash, the catch-up draw draws the same numbers.
 *
 * <p>An admin drawing before the slot replaces the scheduled draw, so one day cannot draw twice and roll over twice. Given
 * winning numbers require {@code lottoForcedDraws}; such draws are publicly marked ({@link Draw#forced}).
 *
 * <p>{@link #migrate} refunds open tickets of the pot model on system account {@link #POT_ID} through the treasury and moves
 * the pot to the treasury.
 */
public final class Lottery {
	/**
	 * System account of the legacy pot model; stays registered (hidden), leftovers go to the treasury at
	 * start.
	 */
	public static final UUID POT_ID = UUID.fromString("00000000-0000-0000-0000-00000000107e");
	public static final String POT_NAME = "Lottery-Pot";
	public static final int HISTORY = 10;
	/** lotto.json version: 0 = legacy pot model, 2 = numbered tickets with jackpot. */
	public static final int MODEL = 2;
	/** Jackpot rank in {@link Prize#rank}; extra prizes have 1, 2, ... */
	public static final int JACKPOT = 0;
	/** {@link Owed#rank} of a legacy ticket refund (migration). */
	public static final int REFUND = -1;

	/** Treasury reasons (feature prefix). */
	public static final String R_TICKETS = "events.lotto_tickets";
	public static final String R_PRIZES = "events.lotto_prizes";
	public static final String R_REFUND = "events.lotto_refund";
	public static final String R_OLD_POT = "events.lotto_old_pot";

	/** Live: the treasury. Selftests: their own account. */
	public interface Bank {
		long balance();

		/** Account to treasury, atomic. */
		EconomyService.Result collect(UUID from, long cents, String reason);

		/**
		 * Treasury to player, uncapped (prizes already covered, since tickets only sell while the treasury
		 * covers them, and refunds), never more than the balance.
		 */
		EconomyService.Result pay(UUID to, long cents, String reason);
	}

	public static final Bank TREASURY = new Bank() {
		@Override
		public long balance() {
			return Treasury.balance();
		}

		@Override
		public EconomyService.Result collect(UUID from, long cents, String reason) {
			return Treasury.collectFrom(from, cents, reason);
		}

		@Override
		public EconomyService.Result pay(UUID to, long cents, String reason) {
			return Treasury.payToUncapped(to, cents, reason);
		}
	};

	/** For selftests with their own account and stats. */
	public static Bank ledger(Treasury.Ledger ledger) {
		return new Bank() {
			@Override
			public long balance() {
				return ledger.balance();
			}

			@Override
			public EconomyService.Result collect(UUID from, long cents, String reason) {
				return ledger.collectFrom(from, cents, reason, System.currentTimeMillis());
			}

			@Override
			public EconomyService.Result pay(UUID to, long cents, String reason) {
				return ledger.payTo(to, cents, reason, System.currentTimeMillis(), false);
			}
		};
	}

	/** Persisted in {@code <world>/smpeconomy/lotto.json}. */
	public static final class Data {
		/** 0 = legacy state (pot model), {@link #MODEL} = numbered tickets. */
		public int model;
		/** Number of the running (next) draw. */
		public long drawId = 1;
		/** Epoch ms; 0 = not planned yet. */
		public long nextDrawAt;
		/** {@link #nextDrawAt} the reminder was already sent for. */
		public long remindedFor;
		/** Time used to compute {@link #nextDrawAt}; replans when the config changes. */
		public String plannedFor = "";
		/** Draw whose sales start was already announced. */
		public long saleAnnouncedFor;
		/** In cents. */
		public long jackpot;
		/** Numbers 1..ticketCount, copied from the config at opening. */
		public int ticketCount;
		/** In cents, copied from the config at opening. */
		public List<Long> prizes = new ArrayList<>();
		/** Rolled at opening. */
		public long seed;
		/** Player to numbers (ascending, each number at most once). */
		public Map<UUID, List<Integer>> ticketNumbers = new LinkedHashMap<>();
		/** Newest first. */
		public List<Draw> draws = new ArrayList<>();
		/** Prizes and refunds not (fully) paid, oldest first. */
		public List<Owed> owed = new ArrayList<>();
		/** Legacy pot model: player to ticket count; empty after migration. */
		public Map<UUID, Integer> tickets = new LinkedHashMap<>();
		/** Legacy pot model: old draws, only kept. */
		public List<LegacyDraw> history = new ArrayList<>();

		void fixNulls() {
			if (plannedFor == null) plannedFor = "";
			if (drawId < 1) drawId = 1;
			if (prizes == null) prizes = new ArrayList<>();
			prizes.removeIf(p -> p == null || p <= 0);
			if (draws == null) draws = new ArrayList<>();
			draws.removeIf(Objects::isNull);
			draws.forEach(Draw::fixNulls);
			if (owed == null) owed = new ArrayList<>();
			owed.removeIf(o -> o == null || o.player == null || o.amount <= 0);
			if (tickets == null) tickets = new LinkedHashMap<>();
			tickets.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null || e.getValue() <= 0);
			if (history == null) history = new ArrayList<>();
			history.removeIf(Objects::isNull);
			if (ticketNumbers == null) ticketNumbers = new LinkedHashMap<>();
			// Each number at most once and only 1..ticketCount (only broken by hand-editing the file, so log
			// loudly)
			Set<Integer> seen = new HashSet<>();
			Iterator<Map.Entry<UUID, List<Integer>>> it = ticketNumbers.entrySet().iterator();
			while (it.hasNext()) {
				Map.Entry<UUID, List<Integer>> e = it.next();
				if (e.getKey() == null || e.getValue() == null) {
					SmpEconomy.LOG.warn("[SMPEco] lotto.json: tickets without player or numbers discarded: {}", e);
					it.remove();
					continue;
				}
				List<Integer> clean = new ArrayList<>();
				for (Integer n : e.getValue()) {
					if (n != null && n >= 1 && (ticketCount <= 0 || n <= ticketCount) && seen.add(n)) clean.add(n);
					else SmpEconomy.LOG.warn("[SMPEco] lotto.json: invalid or duplicate ticket {} from {} discarded", n, e.getKey());
				}
				Collections.sort(clean);
				if (clean.isEmpty()) it.remove();
				else e.setValue(clean);
			}
		}
	}

	public static final class Draw {
		public long id;
		public long at;
		public int ticketCount;
		public int sold;
		public int players;
		/** Won: back to base, otherwise jackpot + base. */
		public long jackpot;
		public long nextJackpot;
		/**
		 * Numbers given by an admin ({@code /lottery draw <numbers>}, only with {@code lottoForcedDraws});
		 * publicly marked.
		 */
		public boolean forced;
		/** Triggered by an admin ({@code /lottery draw}) rather than the schedule. */
		public boolean admin;
		/** In rank order: [0] = jackpot. */
		public List<Prize> prizes = new ArrayList<>();

		public boolean jackpotWon() {
			return !prizes.isEmpty() && prizes.get(0).winner != null;
		}

		public List<Integer> numbers() {
			List<Integer> list = new ArrayList<>();
			for (Prize p : prizes) list.add(p.number);
			return list;
		}

		public long paid() {
			long sum = 0;
			for (Prize p : prizes) sum += p.paid;
			return sum;
		}

		public long owed() {
			long sum = 0;
			for (Prize p : prizes) sum += p.owed;
			return sum;
		}

		void fixNulls() {
			if (prizes == null) prizes = new ArrayList<>();
			prizes.removeIf(Objects::isNull);
		}
	}

	/** {@code winner} null = number not sold. */
	public static final class Prize {
		public int rank;
		public int number;
		public long amount;
		public UUID winner;
		public String winnerName;
		/** Paid at the draw, or left owed because the treasury fell short. */
		public long paid;
		public long owed;
	}

	/** A prize the treasury could not cover, or a legacy ticket refund. */
	public static final class Owed {
		public UUID player;
		public String name;
		public long drawId;
		/** {@link #JACKPOT}, extra prize 1.., or {@link #REFUND}. */
		public int rank;
		/** 0 for {@link #REFUND}. */
		public int number;
		/** For {@link #REFUND}: number of legacy tickets. */
		public int tickets;
		/** Still owed, and originally (cents). */
		public long amount;
		public long total;
		public long since;
	}

	/** Draw of the legacy pot model, only read and kept. */
	public static final class LegacyDraw {
		public long id;
		public long at;
		public UUID winner;
		public String winnerName;
		public long pot;
		public long prize;
		public long fee;
		public int winnerTickets;
		public int tickets;
		public int players;
	}

	/**
	 * {@code DENIED} only comes from {@link EventsGui#buy} (player without {@code smpeconomy.lottery}), never
	 * from {@link #buy}.
	 */
	public enum BuyStatus {
		OK, DISABLED, INVALID_COUNT, MAX_REACHED, SOLD_OUT, INSUFFICIENT_FUNDS, ERROR, DENIED,
		/** Sales paused: the treasury does not cover jackpot + extras + owed + reserve ({@link #covered}). */
		NOT_COVERED
	}

	/**
	 * {@code bought} tickets ({@code numbers}) for {@code cost}, then {@code total} own tickets; {@code
	 * wanted} is what was requested.
	 */
	public record Purchase(BuyStatus status, int wanted, int bought, int total, long cost, List<Integer> numbers) {
		public boolean ok() {
			return status == BuyStatus.OK;
		}
	}

	/**
	 * {@code migrated}: legacy state converted; also refunded legacy tickets and the pot moved to the
	 * treasury.
	 */
	public record Migration(boolean migrated, long potSwept, int refundedPlayers, long refunded, List<Owed> settled) {}

	private final UUID legacyPot;
	private final Supplier<Data> data;
	private final Runnable markDirty;
	private final Supplier<EventsConfig> config;
	private final Bank bank;
	private final Random random;

	/**
	 * @param legacyPot legacy pot account (live {@link #POT_ID})
	 * @param bank      treasury for tickets and prizes (live {@link #TREASURY})
	 * @param random    for ticket numbers and each draw's seed (live {@link java.security.SecureRandom})
	 */
	public Lottery(UUID legacyPot, Supplier<Data> data, Runnable markDirty, Supplier<EventsConfig> config, Bank bank, Random random) {
		this.legacyPot = legacyPot;
		this.data = data;
		this.markDirty = markDirty;
		this.config = config;
		this.bank = bank;
		this.random = random;
	}

	public UUID legacyPotAccount() {
		return legacyPot;
	}

	/** Leftover on the legacy pot account; 0 after migration. */
	public long legacyPotBalance() {
		return EconomyService.balance(legacyPot);
	}

	public Bank bank() {
		return bank;
	}

	/** Migrated and a draw opened, which only happens after {@link #migrate} at server start. */
	public boolean isReady() {
		Data d = data.get();
		return d.model == MODEL && d.ticketCount > 0 && d.jackpot > 0;
	}

	public long drawId() {
		return data.get().drawId;
	}

	public long jackpot() {
		return data.get().jackpot;
	}

	public int ticketCount() {
		return data.get().ticketCount;
	}

	public int winningCount() {
		return 1 + data.get().prizes.size();
	}

	/** 0 = jackpot, 1.. = extras. */
	public long prizeAmount(int rank) {
		Data d = data.get();
		return rank == JACKPOT ? d.jackpot : d.prizes.get(rank - 1);
	}

	/** Copy. */
	public List<Long> prizes() {
		return List.copyOf(data.get().prizes);
	}

	/** If this one is not won. */
	public long nextJackpotIfNotWon() {
		return grown(data.get().jackpot);
	}

	/** + base, at most {@code lottoJackpotMax} (0 = no limit), never below the current. */
	long grown(long jackpot) {
		EventsConfig cfg = config.get();
		long next = saturatingAdd(jackpot, cfg.lottoJackpotBase);
		return cfg.lottoJackpotMax > 0 ? Math.max(jackpot, Math.min(next, cfg.lottoJackpotMax)) : next;
	}

	/**
	 * Most that can fall due for the running draw and everything owed: jackpot + all extras + owed prizes
	 * (cents).
	 */
	public long obligations() {
		Data d = data.get();
		long sum = saturatingAdd(d.jackpot, owedTotal());
		for (long p : d.prizes) sum = saturatingAdd(sum, p);
		return sum;
	}

	/** Balance that ticket sales need: {@link #obligations} + reserve. */
	public long coverageNeeded() {
		return saturatingAdd(obligations(), config.get().lottoTreasuryReserve);
	}

	/** Otherwise sales are paused. */
	public boolean covered() {
		return bank.balance() >= coverageNeeded();
	}

	/** What the lottery may take from the treasury now: balance above the reserve. */
	public long spendable() {
		return Math.max(0, bank.balance() - config.get().lottoTreasuryReserve);
	}

	public List<Integer> numbers(UUID player) {
		List<Integer> list = data.get().ticketNumbers.get(player);
		return list == null ? List.of() : List.copyOf(list);
	}

	public int tickets(UUID player) {
		List<Integer> list = data.get().ticketNumbers.get(player);
		return list == null ? 0 : list.size();
	}

	public int soldCount() {
		int sum = 0;
		for (List<Integer> list : data.get().ticketNumbers.values()) sum += list.size();
		return sum;
	}

	public int participants() {
		return data.get().ticketNumbers.size();
	}

	public Set<UUID> participantIds() {
		return new LinkedHashSet<>(data.get().ticketNumbers.keySet());
	}

	public Map<Integer, UUID> owners() {
		Map<Integer, UUID> map = new HashMap<>();
		data.get().ticketNumbers.forEach((player, list) -> list.forEach(n -> map.put(n, player)));
		return map;
	}

	public long nextDrawAt() {
		return data.get().nextDrawAt;
	}

	public List<Draw> draws() {
		return List.copyOf(data.get().draws);
	}

	public List<Owed> owed() {
		return List.copyOf(data.get().owed);
	}

	public boolean hasOwed() {
		return !data.get().owed.isEmpty();
	}

	public long owedTo(UUID player) {
		long sum = 0;
		for (Owed o : data.get().owed) if (o.player.equals(player)) sum = saturatingAdd(sum, o.amount);
		return sum;
	}

	public long owedTotal() {
		long sum = 0;
		for (Owed o : data.get().owed) sum = saturatingAdd(sum, o.amount);
		return sum;
	}

	/** Chance on each single prize, since every winning number is uniform over all numbers. */
	public double chance(UUID player) {
		int n = ticketCount();
		return n <= 0 ? 0 : tickets(player) / (double) n;
	}

	/** Chance of at least one prize: 1 - C(N-m, k) / C(N, k). */
	public double chanceAny(UUID player) {
		return chanceAny(ticketCount(), tickets(player), winningCount());
	}

	static double chanceAny(int total, int mine, int winning) {
		if (total <= 0 || mine <= 0) return 0;
		double none = 1;
		for (int j = 0; j < winning; j++) {
			double free = total - mine - j;
			if (free <= 0) return 1;
			none *= free / (total - j);
		}
		return 1 - none;
	}

	/** Server timezone, DST safe. */
	public long nextDrawAfter(long now) {
		ZonedDateTime from = Instant.ofEpochMilli(now).atZone(TimeUtil.zone());
		return now + TimeUtil.millisUntilNext(config.get().drawTime(), from);
	}

	/**
	 * Buys up to {@code count}, limited by the player's maximum and the free numbers; all or nothing for the
	 * limited amount. Numbers are random among the free ones.
	 */
	public Purchase buy(UUID player, int count) {
		EventsConfig cfg = config.get();
		Data d = data.get();
		int have = tickets(player);
		if (!cfg.lottoEnabled) return new Purchase(BuyStatus.DISABLED, count, 0, have, 0, List.of());
		if (count < 1) return new Purchase(BuyStatus.INVALID_COUNT, count, 0, have, 0, List.of());
		if (player == null || player.equals(legacyPot) || EconomyService.isSystemAccount(player)) return new Purchase(BuyStatus.ERROR, count, 0, have, 0, List.of());
		if (!isReady()) {
			SmpEconomy.LOG.warn("[SMPEco] Lottery purchase by {} before migration (model={}) rejected", player, d.model);
			return new Purchase(BuyStatus.ERROR, count, 0, have, 0, List.of());
		}
		int room = cfg.lottoMaxTicketsPerPlayer - have;
		if (room <= 0) return new Purchase(BuyStatus.MAX_REACHED, count, 0, have, 0, List.of());
		Set<Integer> sold = owners().keySet();
		int left = d.ticketCount - sold.size();
		if (left <= 0) return new Purchase(BuyStatus.SOLD_OUT, count, 0, have, 0, List.of());
		if (!covered()) return new Purchase(BuyStatus.NOT_COVERED, count, 0, have, 0, List.of());
		int n = Math.min(count, Math.min(room, left));
		long cost = Math.multiplyExact((long) n, cfg.lottoTicketPrice);
		EconomyService.Result r = bank.collect(player, cost, R_TICKETS);
		if (!r.ok()) {
			BuyStatus s = r == EconomyService.Result.INSUFFICIENT_FUNDS || r == EconomyService.Result.NO_ACCOUNT ? BuyStatus.INSUFFICIENT_FUNDS : BuyStatus.ERROR;
			if (s == BuyStatus.ERROR) SmpEconomy.LOG.warn("[SMPEco] Lottery purchase by {} failed: {}", player, r);
			return new Purchase(s, count, 0, have, cost, List.of());
		}
		List<Integer> picked = pickUnsold(d.ticketCount, sold, n, random);
		List<Integer> mine = d.ticketNumbers.computeIfAbsent(player, k -> new ArrayList<>());
		mine.addAll(picked);
		Collections.sort(mine);
		markDirty.run();
		Collections.sort(picked);
		return new Purchase(BuyStatus.OK, count, n, have + n, cost, List.copyOf(picked));
	}

	/**
	 * {@code n} distinct random numbers from 1..{@code ticketCount} excluding {@code sold} (partial
	 * Fisher-Yates).
	 */
	static List<Integer> pickUnsold(int ticketCount, Set<Integer> sold, int n, Random random) {
		int[] free = new int[ticketCount];
		int size = 0;
		for (int i = 1; i <= ticketCount; i++) if (!sold.contains(i)) free[size++] = i;
		if (n > size) throw new IllegalArgumentException("only " + size + " numbers left");
		List<Integer> out = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			int j = i + random.nextInt(size - i);
			int t = free[i];
			free[i] = free[j];
			free[j] = t;
			out.add(free[i]);
		}
		return out;
	}

	/** No refund: test player cleanup only. */
	public void removePlayer(UUID player) {
		Data d = data.get();
		boolean changed = d.ticketNumbers.remove(player) != null;
		changed |= d.tickets.remove(player) != null;
		changed |= d.owed.removeIf(o -> o.player.equals(player));
		if (changed) markDirty.run();
	}

	/**
	 * {@code k} distinct winning numbers, every subset equally likely and in random order ([0] = jackpot):
	 * partial Fisher-Yates with {@code new Random(seed)}, so same seed, same numbers.
	 */
	static int[] winningNumbers(long seed, int ticketCount, int k) {
		if (k < 1 || k > ticketCount) throw new IllegalArgumentException("k=" + k + " ticketCount=" + ticketCount);
		Random r = new Random(seed);
		int[] pool = new int[ticketCount];
		for (int i = 0; i < ticketCount; i++) pool[i] = i + 1;
		int[] out = new int[k];
		for (int i = 0; i < k; i++) {
			int j = i + r.nextInt(ticketCount - i);
			int t = pool[i];
			pool[i] = pool[j];
			pool[j] = t;
			out[i] = pool[i];
		}
		return out;
	}

	/** Selftests and diagnostics only; never show to players. */
	int[] plannedNumbers() {
		Data d = data.get();
		return winningNumbers(d.seed, d.ticketCount, winningCount());
	}

	/** Validates given winning numbers; null if valid, otherwise an English error message. */
	public String checkNumbers(int[] numbers) {
		int k = winningCount();
		int n = ticketCount();
		if (numbers == null || numbers.length != k) return "Give exactly " + k + " winning numbers (jackpot first).";
		Set<Integer> seen = new HashSet<>();
		for (int x : numbers) {
			if (x < 1 || x > n) return "Numbers must be between 1 and " + n + ".";
			if (!seen.add(x)) return "The winning numbers must be different.";
		}
		return null;
	}

	/** Draw by schedule or selftests ({@code admin} means given numbers). */
	public Draw draw(long now, int[] forced) {
		return draw(now, forced, forced != null);
	}

	/**
	 * Draws now: winning numbers from the seed (or {@code forced}), pays each sold hit from the treasury,
	 * smaller prizes first and jackpot last, never below the reserve (the rest stays owed), sets the next
	 * jackpot and opens the next draw (new number, no tickets, config taken over, new seed, next slot;
	 * drawing before the slot uses it up).
	 *
	 * @param admin triggered by an admin (noted in history)
	 * @throws IllegalStateException    before migration
	 * @throws IllegalArgumentException invalid {@code forced} numbers ({@link #checkNumbers})
	 */
	public Draw draw(long now, int[] forced, boolean admin) {
		Data d = data.get();
		if (!isReady()) throw new IllegalStateException("lottery not migrated (model=" + d.model + ")");
		int k = winningCount();
		int[] numbers = forced != null ? forced.clone() : winningNumbers(d.seed, d.ticketCount, k);
		String problem = checkNumbers(numbers);
		if (problem != null) throw new IllegalArgumentException(problem);
		Map<Integer, UUID> owners = owners();
		Draw res = new Draw();
		res.id = d.drawId;
		res.at = now;
		res.ticketCount = d.ticketCount;
		res.sold = owners.size();
		res.players = d.ticketNumbers.size();
		res.jackpot = d.jackpot;
		res.forced = forced != null;
		res.admin = admin;
		for (int rank = 0; rank < k; rank++) {
			Prize p = new Prize();
			p.rank = rank;
			p.number = numbers[rank];
			p.amount = prizeAmount(rank);
			UUID winner = owners.get(p.number);
			if (winner != null) {
				p.winner = winner;
				p.winnerName = EconomyService.nameOrUnknown(winner);
			}
			res.prizes.add(p);
		}
		// Smallest prize first, jackpot last: if the treasury falls short, small winners do not wait
		List<Prize> payOrder = new ArrayList<>(res.prizes);
		payOrder.sort(java.util.Comparator.comparingLong((Prize p) -> p.amount).thenComparing((Prize p) -> -p.rank));
		for (Prize p : payOrder) {
			if (p.winner == null) continue;
			p.paid = payNow(p.winner, p.amount, R_PRIZES);
			p.owed = p.amount - p.paid;
			if (p.owed > 0) {
				d.owed.add(owed(p.winner, p.winnerName, d.drawId, p.rank, p.number, 0, p.owed, p.amount, now));
				SmpEconomy.LOG.warn("[SMPEco] Lottery draw #{}: treasury does not fully cover prize {} to {} (reserve {}), {} paid, {} owed",
					d.drawId, Money.format(p.amount), p.winnerName, Money.format(config.get().lottoTreasuryReserve), Money.format(p.paid), Money.format(p.owed));
			}
		}
		res.nextJackpot = res.jackpotWon() ? config.get().lottoJackpotBase : grown(d.jackpot);
		openNext(now, res.nextJackpot);
		d.draws.add(0, res);
		while (d.draws.size() > HISTORY) d.draws.remove(d.draws.size() - 1);
		markDirty.run();
		return res;
	}

	/** As far as the treasury allows above the reserve; returns the amount paid. */
	private long payNow(UUID player, long amount, String reason) {
		long now = Math.min(amount, spendable());
		if (now <= 0) return 0;
		EconomyService.Result r = bank.pay(player, now, reason);
		if (r.ok()) return now;
		SmpEconomy.LOG.warn("[SMPEco] Lottery payout {} to {} failed ({}), stays owed", Money.format(now), player, r);
		return 0;
	}

	private static Owed owed(UUID player, String name, long drawId, int rank, int number, int tickets, long amount, long total, long now) {
		Owed o = new Owed();
		o.player = player;
		o.name = name;
		o.drawId = drawId;
		o.rank = rank;
		o.number = number;
		o.tickets = tickets;
		o.amount = amount;
		o.total = total;
		o.since = now;
		return o;
	}

	/**
	 * New number, no tickets, config taken over, new seed, new slot. The slot of the draw just made is used
	 * up: drawn early (admin) means the following slot, otherwise (on time or caught up) the next after now.
	 * So each slot has at most one draw and one rollover.
	 */
	private void openNext(long now, long jackpot) {
		Data d = data.get();
		EventsConfig cfg = config.get();
		long consumed = d.nextDrawAt;
		d.drawId++;
		d.ticketNumbers.clear();
		d.jackpot = jackpot;
		snapshot(cfg);
		d.seed = random.nextLong();
		d.nextDrawAt = nextDrawAfter(Math.max(now, consumed));
		d.plannedFor = cfg.lottoDrawTime;
	}

	public boolean isEarly(long now) {
		long at = data.get().nextDrawAt;
		return at > 0 && now < at;
	}

	private void snapshot(EventsConfig cfg) {
		Data d = data.get();
		d.ticketCount = cfg.lottoTicketsPerDraw;
		d.prizes = new ArrayList<>(cfg.lottoPrizes);
	}

	/**
	 * Pays owed amounts as far as the treasury allows: first legacy ticket refunds (the players' own money,
	 * also from the reserve), then prizes, smallest owed amount first (a $1,000 winner does not wait for a
	 * whole jackpot), older first on ties, and only from the part above the reserve. Returns the entries now
	 * fully paid (for messages); partly paid ones stay with the rest. A missing account is skipped and
	 * retried later. {@link Data#owed} keeps its order (oldest first).
	 */
	public List<Owed> settleOwed() {
		Data d = data.get();
		if (d.owed.isEmpty()) return List.of();
		List<Owed> order = new ArrayList<>(d.owed);
		order.sort(java.util.Comparator.comparing((Owed o) -> o.rank != REFUND).thenComparingLong(o -> o.amount).thenComparingLong(o -> o.since));
		List<Owed> done = new ArrayList<>();
		boolean changed = false;
		for (Owed o : order) {
			long available = o.rank == REFUND ? Math.max(0, bank.balance()) : spendable();
			if (available <= 0) continue;
			long amount = Math.min(o.amount, available);
			EconomyService.Result r = bank.pay(o.player, amount, o.rank == REFUND ? R_REFUND : R_PRIZES);
			if (!r.ok()) continue;
			o.amount -= amount;
			changed = true;
			if (o.amount <= 0) {
				d.owed.remove(o);
				done.add(o);
			}
		}
		if (changed) markDirty.run();
		return done;
	}

	/**
	 * Plans the next draw if needed (first start, changed time in the config). A missed draw (server was down
	 * at draw time) stays due and runs on the next tick. While no ticket is sold, the running draw takes over
	 * a changed config (ticket count, extra prizes) right away.
	 */
	public void ensurePlanned(long now) {
		Data d = data.get();
		EventsConfig cfg = config.get();
		String time = cfg.lottoDrawTime;
		if (d.nextDrawAt <= 0) {
			d.nextDrawAt = nextDrawAfter(now);
			d.plannedFor = time;
			markDirty.run();
		} else if (!time.equals(d.plannedFor) && d.nextDrawAt > now) {
			d.nextDrawAt = nextDrawAfter(now);
			d.plannedFor = time;
			markDirty.run();
		}
		if (isReady() && d.ticketNumbers.isEmpty() && (d.ticketCount != cfg.lottoTicketsPerDraw || !d.prizes.equals(cfg.lottoPrizes))) {
			snapshot(cfg);
			markDirty.run();
		}
	}

	public boolean isDue(long now) {
		long at = data.get().nextDrawAt;
		return at > 0 && now >= at;
	}

	/**
	 * Cancelled only with no ticket sold at all: when {@code lottoRolloverWithoutTickets} is false (default),
	 * or nobody could buy, because the lottery is off or sales are paused as the treasury does not cover the
	 * prizes (the jackpot must not grow while nobody can buy).
	 */
	public boolean shouldSkip() {
		EventsConfig cfg = config.get();
		return soldCount() == 0 && (!cfg.lottoEnabled || !cfg.lottoRolloverWithoutTickets || !covered());
	}

	/** Same draw (number, jackpot, seed), new slot. */
	public void skip(long now) {
		Data d = data.get();
		d.nextDrawAt = nextDrawAfter(now);
		d.plannedFor = config.get().lottoDrawTime;
		markDirty.run();
	}

	/** Once per slot; marks it as sent. */
	public boolean takeReminder(long now) {
		Data d = data.get();
		int minutes = config.get().lottoReminderMinutes;
		if (minutes <= 0 || d.nextDrawAt <= 0 || d.remindedFor == d.nextDrawAt) return false;
		long at = d.nextDrawAt - minutes * 60_000L;
		if (now < at || now >= d.nextDrawAt) return false;
		d.remindedFor = d.nextDrawAt;
		markDirty.run();
		return true;
	}

	public boolean inReminderWindow(long now) {
		int minutes = config.get().lottoReminderMinutes;
		long at = data.get().nextDrawAt;
		return minutes > 0 && at > 0 && now >= at - minutes * 60_000L;
	}

	public boolean saleAnnouncementPending() {
		Data d = data.get();
		return isReady() && d.saleAnnouncedFor != d.drawId;
	}

	public void markSaleAnnounced() {
		Data d = data.get();
		if (d.saleAnnouncedFor == d.drawId) return;
		d.saleAnnouncedFor = d.drawId;
		markDirty.run();
	}

	/**
	 * At server start with all data loaded: converts a legacy state (pot model, {@code model} &lt; {@link
	 * #MODEL}). Open legacy tickets are refunded at the current ticket price (through the treasury, right
	 * away where possible), the pot goes to the treasury, and the running draw (number and slot stay) starts
	 * at the base jackpot. On each later start, leftover money on the legacy pot account (e.g. from an admin)
	 * goes to the treasury. Safe to call repeatedly.
	 */
	public Migration migrate(long now) {
		Data d = data.get();
		EventsConfig cfg = config.get();
		boolean fresh = d.model < MODEL;
		int refundedPlayers = 0;
		long refunded = 0;
		if (fresh) {
			for (Map.Entry<UUID, Integer> e : d.tickets.entrySet()) {
				long amount = Math.multiplyExact((long) e.getValue(), cfg.lottoTicketPrice);
				d.owed.add(owed(e.getKey(), EconomyService.nameOrUnknown(e.getKey()), d.drawId, REFUND, 0, e.getValue(), amount, amount, now));
				refunded = saturatingAdd(refunded, amount);
				refundedPlayers++;
			}
			d.tickets.clear();
		}
		long pot = legacyPotBalance();
		long swept = 0;
		if (pot > 0) {
			EconomyService.Result r = bank.collect(legacyPot, pot, R_OLD_POT);
			if (r.ok()) swept = pot;
			else SmpEconomy.LOG.error("[SMPEco] Legacy lottery pot ({}) not moved to the treasury: {}, next start retries", Money.format(pot), r);
		}
		if (fresh) {
			d.model = MODEL;
			d.jackpot = cfg.lottoJackpotBase;
			d.ticketNumbers.clear();
			snapshot(cfg);
			d.seed = random.nextLong();
			d.saleAnnouncedFor = 0;
		}
		if (fresh || swept > 0) markDirty.run();
		List<Owed> settled = refundedPlayers > 0 ? settleOwed() : List.of();
		return new Migration(fresh, swept, refundedPlayers, refunded, settled);
	}

	static long saturatingAdd(long a, long b) {
		long s = a + b;
		if (((a ^ s) & (b ^ s)) < 0 || s > Money.MAX) return Money.MAX;
		return s;
	}
}
