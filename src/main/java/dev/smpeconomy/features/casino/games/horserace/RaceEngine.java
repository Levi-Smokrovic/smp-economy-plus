package dev.smpeconomy.features.casino.games.horserace;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.EconomyService.Result;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Treasury;
import dev.smpeconomy.core.test.TestPlayers;
import dev.smpeconomy.features.casino.Casino;
import dev.smpeconomy.features.casino.CasinoConfig;
import dev.smpeconomy.features.casino.games.horserace.HorseData.Bet;
import dev.smpeconomy.features.casino.games.horserace.HorseData.History;
import dev.smpeconomy.features.casino.games.horserace.HorseData.Pending;
import dev.smpeconomy.features.casino.games.horserace.HorseData.PlayerStats;
import dev.smpeconomy.features.casino.games.horserace.HorseData.Race;

/**
 * Race logic without GUI: one global race card, bets (admission against the casino limits), outcome decided and stored when
 * the race starts (before any animation), settlement, refunds, pending payouts, statistics.
 *
 * <p>Limits come from the casino ({@link Casino#maxBet()} = at most 2 % of the treasury, {@link Casino#maxWin()},
 * {@link Casino#freeCoverage}, {@link Casino#lossLimit}/{@link Casino#lossUsed}, {@code casino.json} on/off and minimum
 * bet). On top of the casino's own reservations the race reserves its worst case: the largest total payout if any single
 * horse wins, plus payouts still pending. A bet is admitted only if that worst case stays covered by the treasury. The
 * daily loss limit counts the casino's losses plus the race's losses of the day plus the open bet.
 *
 * <p>The live engine is registered as a {@link Casino.ExtBook}: the casino games count the race's reservation in their
 * cover and its losses/open bets in the shared daily limit, and an admin limit reset clears the race's day counters too.
 * The engine itself asks the casino for everything except its own share ({@code excludeGame = this}), so it never counts
 * itself twice.
 *
 * <p>Money: stakes via {@link Treasury#collectFrom} when the bet is placed, payouts via {@link Treasury#payToUncapped} when
 * the bet is settled, both under {@link Casino#REASON}. A bet leaves {@link Race#bets} before it is paid, so settling it a
 * second time does nothing. Everything runs on the server thread; the data store is written together with economy and
 * treasury (group commit), so a crash rolls back bets and stakes together.
 */
final class RaceEngine implements Casino.ExtBook {
	/** Countdown at the gates before the horses run. */
	static final long COUNTDOWN_MS = 3_000;
	/** One animation frame. */
	static final long FRAME_MS = 500;
	/** Final positions stay visible this long after the last frame. */
	static final long RESULT_HOLD_MS = 6_000;
	/** Recent results kept for the GUI. */
	static final int HISTORY = 8;

	/** Callbacks for messages/sounds (live) or recording (selftests). */
	interface Events {
		default void firstBet(Race race, Bet bet) {}

		default void raceStarted(Race race) {}

		default void betSettled(Race race, Bet bet, long amount, boolean refund, boolean paid, String why) {}

		default void pendingPaid(Pending pending) {}
	}

	/** Result of a bet attempt: the bet or a message for the player. */
	record Placed(Bet bet, String error) {
		boolean ok() {
			return bet != null;
		}

		static Placed fail(String error) {
			return new Placed(null, error);
		}
	}

	private final Supplier<HorseData> data;
	private final Runnable dirty;
	private final Supplier<HorseConfig> cfg;
	RandomGenerator rng;
	LongSupplier clock;
	Supplier<LocalDate> today;
	Events events;

	RaceEngine(Supplier<HorseData> data, Runnable dirty, Supplier<HorseConfig> cfg, RandomGenerator rng, LongSupplier clock,
		Supplier<LocalDate> today, Events events) {
		this.data = data;
		this.dirty = dirty;
		this.cfg = cfg;
		this.rng = rng;
		this.clock = clock;
		this.today = today;
		this.events = events;
	}

	HorseData data() {
		return data.get();
	}

	HorseConfig cfg() {
		return cfg.get();
	}

	/** The current race (creates a new betting card if there is none). */
	Race card() {
		HorseData d = data();
		if (d.race == null) {
			d.race = newCard();
			dirty.run();
		}
		return d.race;
	}

	private Race newCard() {
		HorseData d = data();
		Race r = new Race();
		r.id = d.nextRaceId++;
		r.state = HorseData.BETTING;
		r.chances = HorseMath.chances(rng);
		r.odds = HorseMath.odds(r.chances, cfg().edgeBp());
		r.createdAt = clock.getAsLong();
		r.closesAt = 0;
		return r;
	}

	/** Worst case payout of a race: betting = most expensive single winner, running = all decided payouts still open. */
	static long worstCase(Race r) {
		if (r == null) return 0;
		try {
			if (HorseData.RUNNING.equals(r.state)) {
				long sum = 0;
				for (Bet b : r.bets.values()) sum = Math.addExact(sum, Math.max(0, decided(r, b)));
				return sum;
			}
			long[] perHorse = new long[HorseMath.HORSES];
			for (Bet b : r.bets.values()) {
				if (b.horse >= 0 && b.horse < HorseMath.HORSES) perHorse[b.horse] = Math.addExact(perHorse[b.horse], HorseMath.payout(b.stake, b.odds));
			}
			long max = 0;
			for (long v : perHorse) max = Math.max(max, v);
			return max;
		} catch (ArithmeticException e) {
			return Long.MAX_VALUE;
		}
	}

	/** Worst case of a betting race if a bet paying {@code payout} on {@code horse} were added. */
	static long worstCaseWith(Race r, int horse, long payout) {
		try {
			long[] perHorse = new long[HorseMath.HORSES];
			for (Bet b : r.bets.values()) {
				if (b.horse >= 0 && b.horse < HorseMath.HORSES) perHorse[b.horse] = Math.addExact(perHorse[b.horse], HorseMath.payout(b.stake, b.odds));
			}
			perHorse[horse] = Math.addExact(perHorse[horse], payout);
			long max = 0;
			for (long v : perHorse) max = Math.max(max, v);
			return max;
		} catch (ArithmeticException e) {
			return Long.MAX_VALUE;
		}
	}

	long pendingTotal() {
		long sum = 0;
		for (Pending p : data().pending) sum = Money.saturatedAdd(sum, p.amount);
		return sum;
	}

	/** Everything the race has promised: worst case of the current race plus pending payouts (Long.MAX_VALUE = overflow). */
	@Override
	public long reserved() {
		long w = worstCase(data().race);
		return w == Long.MAX_VALUE ? w : Money.saturatedAdd(w, pendingTotal());
	}

	/** Treasury left after the casino's (and other games') and the race's reservations. */
	long freeCoverage() {
		long r = reserved();
		return r == Long.MAX_VALUE ? 0 : Math.max(0, Casino.freeCoverage(null, this) - r);
	}

	/** Can the treasury cover this bet (its own payout ≤ max win, the race's worst case incl. it ≤ free coverage)? */
	boolean covered(Race r, int horse, long stake) {
		long payout = HorseMath.payoutSafe(stake, r.odds[horse]);
		if (payout > Casino.maxWin()) return false;
		long worst = worstCaseWith(r, horse, payout);
		if (worst == Long.MAX_VALUE) return false;
		long available = Casino.freeCoverage(null, this) - pendingTotal();
		return worst <= available;
	}

	/** Today's net loss in the race (stakes − payouts of settled bets; negative = up). */
	long netToday(UUID player) {
		PlayerStats s = data().players.get(player);
		if (s == null || !today.get().toString().equals(s.day)) return 0;
		return s.dayStaked - s.dayPaid;
	}

	@Override
	public long netLossToday(UUID player) {
		return netToday(player);
	}

	@Override
	public long openStake(UUID player) {
		Race r = data().race;
		Bet b = r == null ? null : r.bets.get(player);
		return b == null ? 0 : b.stake;
	}

	/** Admin ({@code smpeco casino resetlimit}): today's race counters back to 0. */
	@Override
	public void resetDay(UUID player) {
		PlayerStats s = data().players.get(player);
		if (s == null || s.dayStaked == 0 && s.dayPaid == 0) return;
		s.dayStaked = 0;
		s.dayPaid = 0;
		dirty.run();
	}

	/** How much may still be lost today: casino limit − (casino and other games' losses/open stakes + race losses + open bet). */
	long lossRoom(UUID player) {
		long limit = Casino.lossLimit(player);
		long used = Money.saturatedAdd(Money.saturatedAdd(Casino.lossUsed(player, this), netToday(player)), openStake(player));
		// like the casino: today's winnings (negative loss) widen the room
		return Math.max(0, used == Long.MIN_VALUE ? Long.MAX_VALUE : Money.saturatedAdd(limit, -used));
	}

	/** Highest bet on {@code horse} (-1 = the most expensive horse) that would be admitted now, 0 = none. */
	long maxStake(UUID player, int horse) {
		Race r = card();
		if (horse < 0) {
			long min = Long.MAX_VALUE;
			for (int h = 0; h < HorseMath.HORSES; h++) min = Math.min(min, maxStake(player, h));
			return min;
		}
		CasinoConfig cc = Casino.cfg();
		long max = Math.min(Casino.maxBet(), lossRoom(player));
		if (max < cc.minBet) return 0;
		long lo = 0;
		long hi = max;
		while (lo < hi) {
			long mid = lo + (hi - lo + 1) / 2;
			if (covered(r, horse, mid)) lo = mid;
			else hi = mid - 1;
		}
		return lo >= cc.minBet ? lo : 0;
	}

	/** Everything except the player's balance; null = admitted, otherwise a message for the player. */
	String check(UUID player, int horse, long stake) {
		CasinoConfig cc = Casino.cfg();
		if (!cc.enabled || !cfg().enabled) return "The casino is closed right now.";
		Race r = card();
		if (!HorseData.BETTING.equals(r.state)) return "Betting is closed – the race is running. The next card opens right after it.";
		if (r.closesAt > 0 && clock.getAsLong() >= r.closesAt) return "Betting is closed – the race is about to start.";
		if (horse < 0 || horse >= HorseMath.HORSES) return "Pick a horse from 1 to " + HorseMath.HORSES + ".";
		if (r.bets.containsKey(player)) return "You already have a bet on this race.";
		if (stake < cc.minBet) return "Minimum bet: " + Msg.moneyStr(cc.minBet) + "§c.";
		long maxBet = Casino.maxBet();
		if (maxBet < cc.minBet) return "The casino is locked: the treasury is too small.";
		if (stake > maxBet) {
			return "Maximum bet: " + Msg.moneyStr(maxBet) + " §c(at most " + percent(cc.effectiveMaxBetPercent()) + " of the treasury).";
		}
		if (!covered(r, horse, stake)) {
			long max = maxStake(player, horse);
			return "The treasury can't cover a win of " + Msg.moneyStr(HorseMath.payoutSafe(stake, r.odds[horse])) + " §cright now."
				+ (max > 0 ? " Max bet on this horse: " + Msg.moneyStr(max) + "§c." : " Betting is locked.");
		}
		long room = lossRoom(player);
		if (stake > room) {
			return "Daily loss limit reached (" + Msg.moneyStr(Casino.lossLimit(player)) + "§c). "
				+ (room > 0 ? "You can bet at most " + Msg.moneyStr(room) + " §ctoday." : "Come back tomorrow.");
		}
		return null;
	}

	static String percent(double p) {
		return (p == Math.floor(p) ? String.valueOf((long) p) : String.format(java.util.Locale.ROOT, "%.1f", p)) + " %";
	}

	/** Places a bet: checks, takes the stake into the treasury, stores the bet. The first bet starts the countdown. */
	Placed place(UUID player, String name, int horse, long stake) {
		String error = check(player, horse, stake);
		if (error != null) return Placed.fail(error);
		if (!EconomyService.has(player, stake)) return Placed.fail("Not enough money (bet " + Msg.moneyStr(stake) + "§c).");
		Result res = Treasury.collectFrom(player, stake, Casino.REASON);
		if (!res.ok()) return Placed.fail(res == Result.INSUFFICIENT_FUNDS ? "Not enough money." : "The bet could not be booked (" + res.name() + ").");
		HorseData d = data();
		Race r = d.race;
		long now = clock.getAsLong();
		Bet b = new Bet();
		b.player = player;
		b.name = name == null ? "" : name;
		b.horse = horse;
		b.stake = stake;
		b.odds = r.odds[horse];
		b.payout = -1;
		b.placedAt = now;
		r.bets.put(player, b);
		boolean first = r.closesAt <= 0;
		if (first) {
			long byWindow = now + cfg().bettingWindowSeconds * 1000L;
			long byCadence = d.lastStartAt > 0 && d.lastStartAt <= now ? d.lastStartAt + cfg().raceIntervalSeconds * 1000L : 0;
			r.closesAt = Math.max(byWindow, byCadence);
		}
		stats(player).lastStake = stake;
		dirty.run();
		if (first) events.firstBet(r, b);
		return new Placed(b, null);
	}

	boolean startIfDue(long now) {
		Race r = data().race;
		if (r == null || !HorseData.BETTING.equals(r.state) || r.closesAt <= 0 || now < r.closesAt) return false;
		start(r, now);
		return true;
	}

	/** Admin/e2e: start the current card now (also without bets). false = already running. */
	boolean forceStart(long now) {
		Race r = card();
		if (!HorseData.BETTING.equals(r.state)) return false;
		r.closesAt = now;
		start(r, now);
		return true;
	}

	/** Decides the winner and every payout and stores them before anything is animated. */
	void start(Race r, long now) {
		if (!r.valid()) {
			SmpEconomy.LOG.error("[SMPEco] Horse race {}: broken card – bets refunded", r.id);
			refundAll("broken race card");
			return;
		}
		r.winner = HorseMath.winner(r.chances, rng);
		r.seed = rng.nextLong();
		r.startedAt = now;
		int wf = HorseMath.winnerFrame(r.seed, r.winner);
		r.settleAt = now + COUNTDOWN_MS + wf * FRAME_MS;
		r.endAt = now + COUNTDOWN_MS + HorseMath.endFrame(wf) * FRAME_MS + RESULT_HOLD_MS;
		for (Bet b : r.bets.values()) {
			b.payout = b.horse == r.winner ? HorseMath.payout(b.stake, b.odds) : 0;
			r.picks.put(b.player, b.horse);
		}
		r.state = HorseData.RUNNING;
		data().lastStartAt = now;
		dirty.run();
		events.raceStarted(r);
	}

	/** Pays the race once the winner has crossed the line (or the clock jumped back by more than a minute). */
	boolean finishIfDue(long now) {
		Race r = data().race;
		if (r == null || !HorseData.RUNNING.equals(r.state)) return false;
		if (now < r.settleAt && now >= r.startedAt - 60_000) return false;
		finish(null);
		return true;
	}

	/** Settles every bet of the running race with its decided payout and opens the next card. Returns the bets settled. */
	int finish(String why) {
		HorseData d = data();
		Race r = d.race;
		if (r == null || !HorseData.RUNNING.equals(r.state)) return 0;
		int n = 0;
		for (UUID p : List.copyOf(r.bets.keySet())) {
			if (settleBet(r, p, why, false)) n++;
		}
		History h = new History();
		h.raceId = r.id;
		h.winner = r.winner;
		h.odds = r.odds[r.winner];
		h.finishedAt = clock.getAsLong();
		d.history.add(0, h);
		while (d.history.size() > HISTORY) d.history.remove(d.history.size() - 1);
		d.last = r;
		d.race = newCard();
		dirty.run();
		return n;
	}

	/** Refunds every bet of a betting card (not run) and opens a fresh card. */
	int refundAll(String why) {
		HorseData d = data();
		Race r = d.race;
		if (r == null) return 0;
		int n = 0;
		for (UUID p : List.copyOf(r.bets.keySet())) {
			if (settleBet(r, p, why, true)) n++;
		}
		d.race = newCard();
		dirty.run();
		return n;
	}

	/**
	 * Server start (after a crash) or stop: a decided race is paid with its stored outcome, bets on a card that has not
	 * started are refunded. Returns the bets settled.
	 */
	int settleOpen(String why) {
		Race r = data().race;
		if (r == null) return 0;
		if (!r.valid()) return refundAll(why);
		if (HorseData.RUNNING.equals(r.state)) return finish(why);
		if (!r.bets.isEmpty()) return refundAll(why);
		if (r.closesAt != 0) {
			r.closesAt = 0;
			dirty.run();
		}
		return 0;
	}

	/** Payout of a bet of a running race (stored when the race started). */
	static long decided(Race r, Bet b) {
		if (b.payout >= 0) return b.payout;
		return b.horse == r.winner ? HorseMath.payoutSafe(b.stake, b.odds) : 0;
	}

	/**
	 * Settles one bet exactly once: removes it from the race (a second call finds nothing), pays the decided payout (or the
	 * stake back), books statistics/daily loss (not for refunds) and reports it.
	 */
	boolean settleBet(Race r, UUID player, String why, boolean refund) {
		Bet b = r.bets.remove(player);
		if (b == null) return false;
		long amount = refund ? b.stake : decided(r, b);
		boolean paid = pay(player, amount, r.id);
		if (!refund) recordStats(b, amount);
		dirty.run();
		events.betSettled(r, b, amount, refund, paid, why);
		return true;
	}

	private boolean pay(UUID player, long amount, long raceId) {
		if (amount <= 0) return true;
		Result res = Treasury.payToUncapped(player, amount, Casino.REASON);
		if (res.ok()) return true;
		Pending p = new Pending();
		p.player = player;
		p.amount = amount;
		p.raceId = raceId;
		p.createdAt = clock.getAsLong();
		data().pending.add(p);
		SmpEconomy.LOG.warn("[SMPEco] Horse race payout {} to {} failed ({}) – will be retried", Money.format(amount), player, res);
		return false;
	}

	/** Retries pending payouts (every minute and on join). Returns how many were paid. */
	int retryPending(UUID onlyPlayer) {
		int done = 0;
		List<Pending> paid = new ArrayList<>();
		Iterator<Pending> it = data().pending.iterator();
		while (it.hasNext()) {
			Pending p = it.next();
			if (onlyPlayer != null && !onlyPlayer.equals(p.player)) continue;
			if (!EconomyService.hasAccount(p.player) && TestPlayers.isTestPlayer(p.player)) continue;
			// account deleted meanwhile (admin): recreate it, otherwise the payout would block coverage forever
			if (!EconomyService.hasAccount(p.player)) EconomyService.ensureAccount(p.player, EconomyService.nameOf(p.player).orElse(null));
			if (Treasury.payToUncapped(p.player, p.amount, Casino.REASON).ok()) {
				it.remove();
				paid.add(p);
				done++;
			}
		}
		if (done > 0) dirty.run();
		for (Pending p : paid) events.pendingPaid(p);
		return done;
	}

	PlayerStats stats(UUID player) {
		return data().players.computeIfAbsent(player, k -> new PlayerStats());
	}

	private void recordStats(Bet b, long payout) {
		PlayerStats s = stats(b.player);
		String day = today.get().toString();
		if (!day.equals(s.day)) {
			s.day = day;
			s.dayStaked = 0;
			s.dayPaid = 0;
		}
		s.dayStaked = Math.addExact(s.dayStaked, b.stake);
		s.dayPaid = Math.addExact(s.dayPaid, payout);
		s.races++;
		s.wagered = Math.addExact(s.wagered, b.stake);
		s.paidOut = Math.addExact(s.paidOut, payout);
		if (payout > b.stake) {
			s.wins++;
			s.biggestWin = Math.max(s.biggestWin, payout);
		}
	}

	/** Test players only: drop their bets, pending payouts and statistics WITHOUT paying anything. */
	void purge(UUID player) {
		HorseData d = data();
		boolean changed = d.race != null && d.race.bets.remove(player) != null;
		changed |= d.pending.removeIf(p -> p.player.equals(player));
		changed |= d.players.remove(player) != null;
		if (changed) dirty.run();
	}
}
