package dev.smpeconomy.features.casino;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.LongUnaryOperator;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import java.util.random.RandomGenerator;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.CommandRegistry;
import dev.smpeconomy.core.ConfigManager;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.EconomyService.Result;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.Settings;
import dev.smpeconomy.core.TimeUtil;
import dev.smpeconomy.core.Treasury;
import dev.smpeconomy.core.test.TestPlayers;
import dev.smpeconomy.features.casino.CasinoData.PendingPayout;
import dev.smpeconomy.features.casino.CasinoData.PlayerStats;
import dev.smpeconomy.features.casino.CasinoData.Round;

/**
 * Game logic without GUI: admission (min/max bet, Treasury coverage, daily loss limit), rounds with an outcome drawn
 * and stored up front, exactly-once settlement ({@link #settle}) and stats.
 *
 * <p>Money: stake via {@link Treasury#collectFrom} at the start, winnings via {@link Treasury#payToUncapped} at
 * settlement. A round is admitted only if the Treasury, after all reservations, covers its maximum win. {@link
 * Round#reserved} holds what the round is worth now or can reach on the current move: coin flip and slots hold the
 * payout of the outcome drawn at once, mines and hi-lo hold only the current cash-out value (the stake before the first
 * winning step). Every further step (tile, guess, double or nothing) is checked against free coverage before the move
 * and only then reserved, so a round left open blocks no coverage for others. Rounds with no move are settled after
 * {@link CasinoConfig#idleTimeoutMinutes} even with the window open. A player has at most one open round. Everything
 * runs on the server thread.
 */
public final class Casino {
	/** Reason in the Treasury stats. */
	public static final String REASON = "casino";

	public static final ConfigManager.Config<CasinoConfig> CONFIG = ConfigManager.register("casino", CasinoConfig.class, CasinoConfig::new, CasinoConfig::validate);
	static final DataStore<CasinoData> STORE = new DataStore<>("casino.json", CasinoData.class, CasinoData::new);

	/** Live randomness (unpredictable). Selftests pass their own fixed source. */
	public static final RandomGenerator RNG = new SecureRandom();
	/** Today (server time zone), replaceable in selftests. */
	static Supplier<LocalDate> today = TimeUtil::today;
	static LongSupplier clock = System::currentTimeMillis;

	/** Open game GUIs per round, for the watchdog that settles orphaned rounds. */
	private static final Map<String, Window> GUIS = new HashMap<>();

	static {
		STORE.onLoad(CasinoData::fixNulls);
		Treasury.registerReason(REASON, "Casino");
	}

	private Casino() {}

	/** Loads the class (config, data store, Treasury reason). */
	static void bootstrap() {
	}

	public static CasinoConfig cfg() {
		return CONFIG.get();
	}

	static CasinoData data() {
		return STORE.get();
	}

	/**
	 * {@link DataStore#flushCount()} at the last {@link #persist()}; same count means the requested full flush is still
	 * pending.
	 */
	private static long fullFlushMark = Long.MIN_VALUE;

	/**
	 * After money moves (stake taken, payout, stats): full flush at the end of the tick, so casino.json reaches disk
	 * together with economy.json and treasury.json. A round saved without its deduction would refund the stake a second
	 * time after a restart.
	 */
	static void persist() {
		STORE.markDirty();
		DataStore.requestFlush();
		fullFlushMark = DataStore.flushCount();
	}

	/**
	 * After a move that only changes round state (tile, guess, double or nothing, decision, cash-out without
	 * settlement): write only casino.json right away, with no full flush of all stores (fsync, possibly a player save)
	 * on every click. If a full flush is still pending the move goes with it, because casino.json must not get ahead of
	 * economy and treasury. Test players are only marked dirty, since the selftests' flush detection covers them.
	 */
	static void persistStep(Round r) {
		STORE.markDirty();
		if (DataStore.flushCount() == fullFlushMark || TestPlayers.isTestPlayer(r.player)) return;
		STORE.save(); // false (not loaded or error): stays dirty, the normal flush retries
	}

	/** Start of a round: either {@code round} or an error message for the player. */
	public record Start(Round round, String error) {
		public boolean ok() {
			return round != null;
		}

		static Start fail(String error) {
			return new Start(null, error);
		}
	}

	public enum Step {
		/** Won or safe, round continues. */
		WIN,
		/** Lost, round decided (payout 0). */
		LOSE,
		/** Won and paid out automatically (all tiles revealed or maximum win reached). */
		DONE,
		/** Not allowed (coverage or maximum win); nothing happened. */
		BLOCKED,
		/** Invalid move (already revealed, impossible guess, round not open); nothing happened. */
		INVALID
	}

	public record Settled(Round round, long payout, boolean refund, boolean paid) {}

	/** Max bet: min(config maximum, 2 % of the Treasury balance). */
	public static long maxBet() {
		long byTreasury = percentOf(Treasury.balance(), cfg().effectiveMaxBetPercent());
		return Math.min(cfg().maxBet, byTreasury);
	}

	/** Highest payout of a round (share of the Treasury balance). */
	public static long maxWin() {
		return percentOf(Treasury.balance(), cfg().maxWinTreasuryPercent);
	}

	static long percentOf(long cents, double percent) {
		if (cents <= 0 || !(percent > 0)) return 0;
		return BigDecimal.valueOf(cents).multiply(BigDecimal.valueOf(percent)).movePointLeft(2).setScale(0, RoundingMode.FLOOR).longValue();
	}

	/**
	 * Sum of all reservations of open rounds (without {@code excludeRound}) plus pending payouts, including games with
	 * their own store ({@link ExtBook#reserved}).
	 */
	public static long reservedTotal(String excludeRound) {
		return reservedTotal(excludeRound, null);
	}

	/** Like {@link #reservedTotal(String)}, without the share of {@code excludeBook} (that game counts its own). */
	public static long reservedTotal(String excludeRound, ExtBook excludeBook) {
		long sum = 0;
		for (Round r : data().rounds.values()) {
			if (!r.id.equals(excludeRound)) sum = Math.addExact(sum, Math.max(0, r.reserved));
		}
		for (PendingPayout p : data().pending) sum = Math.addExact(sum, p.amount);
		for (ExtBook b : BOOKS) {
			if (b != excludeBook) sum = Money.saturatedAdd(sum, Math.max(0, b.reserved()));
		}
		return sum;
	}

	/** What the Treasury can still cover after all reservations. */
	public static long freeCoverage(String excludeRound) {
		return freeCoverage(excludeRound, null);
	}

	/** Like {@link #freeCoverage(String)}, without the share of {@code excludeBook}. */
	public static long freeCoverage(String excludeRound, ExtBook excludeBook) {
		return Math.max(0, Money.saturatedAdd(Treasury.balance(), -reservedTotal(excludeRound, excludeBook)));
	}

	/** Highest allowed payout of a new round or a further step. */
	public static long coverLimit(String excludeRound) {
		return Math.min(maxWin(), freeCoverage(excludeRound));
	}

	/**
	 * Highest possible payout of a round with this stake. The Treasury must cover it at the start, even though mines
	 * and hi-lo reserve step by step.
	 */
	public static long potential(Game game, long stake, int bombs) {
		CasinoConfig c = cfg();
		return switch (game) {
			case COINFLIP -> Math.multiplyExact(stake, 2);
			case SLOTS -> BigInteger.valueOf(stake).multiply(BigInteger.valueOf(CasinoMath.slotsMaxTenths())).divide(BigInteger.TEN).longValueExact();
			case MINES -> {
				int k = CasinoMath.minesMaxSafe(bombs, c.minesEdgeBp(), c.minesCapBp());
				yield k <= 0 ? Long.MAX_VALUE : CasinoMath.minesPayout(stake, bombs, k, c.minesEdgeBp());
			}
			case HILO -> BigInteger.valueOf(stake).multiply(BigInteger.valueOf(c.hiloCapBp())).divide(BigInteger.valueOf(10_000)).longValueExact();
		};
	}

	/** Highest stake this game would admit right now (ignoring the player's balance), 0 = locked. */
	public static long maxStake(UUID player, Game game, int bombs) {
		return maxStake(player, stake -> potentialSafe(game, stake, bombs));
	}

	/** Same for a game with this maximum payout per stake ({@code potentialOf} monotonic in the stake), 0 = locked. */
	public static long maxStake(UUID player, LongUnaryOperator potentialOf) {
		long max = Math.min(maxBet(), lossRoom(player));
		long cover = coverLimit(null);
		if (max < cfg().minBet) return 0;
		// largest stake with potential(stake) <= cover (potential is monotonic in the stake)
		long lo = 0;
		long hi = max;
		while (lo < hi) {
			long mid = lo + (hi - lo + 1) / 2;
			if (potentialSafe(potentialOf, mid) <= cover) lo = mid;
			else hi = mid - 1;
		}
		return lo >= cfg().minBet ? lo : 0;
	}

	static long potentialSafe(Game game, long stake, int bombs) {
		try {
			return potential(game, stake, bombs);
		} catch (ArithmeticException e) {
			return Long.MAX_VALUE;
		}
	}

	private static long potentialSafe(LongUnaryOperator potentialOf, long stake) {
		try {
			long p = potentialOf.applyAsLong(stake);
			return p < 0 ? Long.MAX_VALUE : p;
		} catch (ArithmeticException e) {
			return Long.MAX_VALUE;
		}
	}

	private static PlayerStats stats(UUID player) {
		return data().players.computeIfAbsent(player, k -> new PlayerStats());
	}

	/** Stats without creating them (null = never played). */
	public static PlayerStats peekStats(UUID player) {
		return data().players.get(player);
	}

	/** Effective daily limit (server limit, or a lower personal limit). */
	public static long lossLimit(UUID player) {
		long server = cfg().dailyLossLimit;
		PlayerStats s = data().players.get(player);
		if (s == null) return server;
		applyPendingLimit(s);
		return s.personalLimit >= 0 ? Math.min(server, s.personalLimit) : server;
	}

	private static void applyPendingLimit(PlayerStats s) {
		if (s.pendingLimit == -2 || s.pendingLimitDay.isEmpty()) return;
		if (today.get().toString().compareTo(s.pendingLimitDay) >= 0) {
			s.personalLimit = s.pendingLimit;
			s.pendingLimit = -2;
			s.pendingLimitDay = "";
			persist();
		}
	}

	/**
	 * Today's net loss across the casino (stakes minus payouts of settled rounds, games with their own store included;
	 * negative = in profit).
	 */
	public static long netLossToday(UUID player) {
		return netLossToday(player, null);
	}

	private static long netLossToday(UUID player, ExtBook excludeBook) {
		PlayerStats s = data().players.get(player);
		long net = s == null || !today.get().toString().equals(s.day) ? 0 : s.dayStaked - s.dayPaid;
		for (ExtBook b : BOOKS) {
			if (b != excludeBook) net = Money.saturatedAdd(net, b.netLossToday(player));
		}
		return net;
	}

	/**
	 * Daily limit used: net loss plus open stakes (running casino round, open bets and rounds of games with their own
	 * store). Otherwise the limit could be used up twice in two games at once. Leaves out the share of {@code
	 * excludeBook} (that game counts its own).
	 */
	public static long lossUsed(UUID player, ExtBook excludeBook) {
		long used = netLossToday(player, excludeBook);
		Round open = openRound(player);
		if (open != null) used = Money.saturatedAdd(used, Math.max(0, open.stake));
		for (ExtBook b : BOOKS) {
			if (b != excludeBook) used = Money.saturatedAdd(used, Math.max(0, b.openStake(player)));
		}
		return used;
	}

	/** How much may still be lost today (all casino games together, open stakes already deducted). */
	public static long lossRoom(UUID player) {
		long used = lossUsed(player, null);
		return Math.max(0, used == Long.MIN_VALUE ? Long.MAX_VALUE : Money.saturatedAdd(lossLimit(player), -used));
	}

	/**
	 * Sets the personal limit ({@code -1} = none). Lowering applies at once, raising or removing only from tomorrow, as
	 * protection against impulse decisions. Returns a message.
	 */
	public static String setPersonalLimit(UUID player, long limit) {
		PlayerStats s = stats(player);
		applyPendingLimit(s);
		long current = lossLimit(player);
		long wanted = limit < 0 ? cfg().dailyLossLimit : Math.min(limit, cfg().dailyLossLimit);
		if (wanted <= current) {
			// at or above the server limit: store no personal limit, or it would stick after the server limit is raised
			s.personalLimit = limit < 0 || wanted >= cfg().dailyLossLimit ? -1 : wanted;
			s.pendingLimit = -2;
			s.pendingLimitDay = "";
			persist();
			return "§aDaily loss limit is now " + Msg.moneyStr(wanted) + "§a.";
		}
		s.pendingLimit = limit < 0 || wanted >= cfg().dailyLossLimit ? -1 : wanted;
		s.pendingLimitDay = today.get().plusDays(1).toString();
		persist();
		return "§eRaising it to " + Msg.moneyStr(wanted) + " §etakes effect tomorrow. §7Today it stays at " + Msg.moneyStr(current) + "§7.";
	}

	/** Admin: resets the personal limit and day counters (including games with their own store). */
	public static void resetLimits(UUID player) {
		for (ExtBook b : BOOKS) b.resetDay(player);
		PlayerStats s = data().players.get(player);
		if (s == null) return;
		s.personalLimit = -1;
		s.pendingLimit = -2;
		s.pendingLimitDay = "";
		s.dayStaked = 0;
		s.dayPaid = 0;
		persist();
	}

	public static Round openRound(UUID player) {
		for (Round r : data().rounds.values()) {
			if (r.player.equals(player)) return r;
		}
		return null;
	}

	public static Round round(String id) {
		return id == null ? null : data().rounds.get(id);
	}

	/** Checks everything except the balance; null = admitted, otherwise a message for the player. */
	static String checkAdmission(UUID player, Game game, long stake, long potential, int bombs) {
		return checkAdmission(player, stake, potential, s -> potentialSafe(game, s, bombs));
	}

	private static String checkAdmission(UUID player, long stake, long potential, LongUnaryOperator potentialOf) {
		CasinoConfig c = cfg();
		if (!c.enabled) return "The casino is closed right now.";
		if (openRound(player) != null || hasBookRound(player)) return "You're already playing a round.";
		if (stake < c.minBet) return "Minimum bet: " + Msg.moneyStr(c.minBet) + "§c.";
		long maxBet = maxBet();
		if (maxBet < c.minBet) return "The casino is locked: the Treasury is too low.";
		if (stake > maxBet) return "Maximum bet: " + Msg.moneyStr(maxBet) + " §c(at most " + fmtPercent(c.effectiveMaxBetPercent()) + " of the Treasury).";
		if (potential > coverLimit(null)) {
			long max = maxStake(player, potentialOf);
			return "The Treasury can't cover a possible win of " + Msg.moneyStr(potential) + " §cright now."
				+ (max > 0 ? " Max bet: " + Msg.moneyStr(max) + "§c." : " Game locked.");
		}
		long room = lossRoom(player);
		if (stake > room) {
			return "Daily loss limit reached (" + Msg.moneyStr(lossLimit(player)) + "§c). "
				+ (room > 0 ? "You can bet up to " + Msg.moneyStr(room) + " §cmore today." : "Come back tomorrow.");
		}
		return null;
	}

	/** Open round in a game with its own store that blocks other rounds ({@link ExtBook#hasOpenRound})? */
	private static boolean hasBookRound(UUID player) {
		for (ExtBook b : BOOKS) {
			if (b.hasOpenRound(player)) return true;
		}
		return false;
	}

	static String fmtPercent(double p) {
		return (p == Math.floor(p) ? String.valueOf((long) p) : String.format(java.util.Locale.ROOT, "%.1f", p)) + "%";
	}

	/** Admits, takes the stake and creates the round (no game state yet). */
	private static Start admit(UUID player, String name, Game game, long stake, int bombs) {
		long potential = potentialSafe(game, stake, bombs);
		String error = checkAdmission(player, game, stake, potential, bombs);
		if (error != null) return Start.fail(error);
		return open(player, name, game.id(), stake, potential);
	}

	/** Takes the stake and creates the round (admission already checked); reserves {@code potential}. */
	private static Start open(UUID player, String name, String gameId, long stake, long potential) {
		if (!EconomyService.has(player, stake)) return Start.fail("Not enough money (bet " + Msg.moneyStr(stake) + "§c).");
		Result r = Treasury.collectFrom(player, stake, REASON);
		if (!r.ok()) return Start.fail(r.message());
		CasinoData d = data();
		Round round = new Round();
		round.id = "r" + d.nextId++;
		round.player = player;
		round.name = name == null ? "" : name;
		round.game = gameId;
		round.stake = stake;
		round.reserved = potential;
		round.createdAt = clock.getAsLong();
		round.lastActionAt = round.createdAt;
		round.state = CasinoData.PLAYING;
		d.rounds.put(round.id, round);
		PlayerStats s = stats(player);
		s.lastStake.put(gameId, stake);
		persist();
		return new Start(round, null);
	}

	/** Coin flip against the house: takes the stake and flips at once (outcome stored before anything is animated). */
	public static Start startCoinflip(UUID player, String name, long stake, boolean pvp, RandomGenerator rng) {
		Start s = admit(player, name, Game.COINFLIP, stake, 0);
		if (!s.ok()) return s;
		Round r = s.round();
		r.pvp = pvp;
		r.pot = stake;
		flip(r, rng);
		persist();
		return s;
	}

	private static void flip(Round r, RandomGenerator rng) {
		if (CasinoMath.coinflipWins(rng)) {
			r.pot = Math.multiplyExact(r.pot, 2);
			r.wins++;
			r.lastWin = true;
		} else {
			r.lastWin = false;
			decide(r, 0);
		}
	}

	/** Double or nothing: bets the current pot again. Only if the Treasury covers twice the pot (maximum win). */
	public static Step coinflipDouble(Round r, RandomGenerator rng) {
		if (!isOpen(r) || Game.byId(r.game) != Game.COINFLIP || r.wins < 1) return Step.INVALID;
		long next;
		try {
			next = Math.multiplyExact(r.pot, 2);
		} catch (ArithmeticException e) {
			return Step.BLOCKED;
		}
		if (next > coverLimit(r.id) || next > Money.MAX) return Step.BLOCKED;
		r.reserved = next;
		r.lastActionAt = clock.getAsLong();
		flip(r, rng);
		persistStep(r);
		return r.lastWin ? Step.WIN : Step.LOSE;
	}

	public static boolean canDouble(Round r) {
		if (!isOpen(r) || r.wins < 1 || !cfg().enabled) return false;
		long next = r.pot > Money.MAX / 2 ? Long.MAX_VALUE : r.pot * 2;
		return next <= coverLimit(r.id);
	}

	/** Slots: takes the stake, draws the payline and fixes the payout before the animation. */
	public static Start startSlots(UUID player, String name, long stake, RandomGenerator rng) {
		Start s = admit(player, name, Game.SLOTS, stake, 0);
		if (!s.ok()) return s;
		Round r = s.round();
		r.reels = CasinoMath.slotsSpin(rng);
		decide(r, CasinoMath.slotsPayout(stake, r.reels));
		persist();
		return s;
	}

	/** Mines: takes the stake and places the bombs (stored); the player reveals afterwards. */
	public static Start startMines(UUID player, String name, long stake, int bombs, RandomGenerator rng) {
		CasinoConfig c = cfg();
		if (bombs < 1 || bombs > CasinoMath.MINES_TILES - 1) return Start.fail("Bombs: 1 to " + (CasinoMath.MINES_TILES - 1) + ".");
		if (CasinoMath.minesMaxSafe(bombs, c.minesEdgeBp(), c.minesCapBp()) < 1) {
			return Start.fail("With " + bombs + " bombs the max multiplier would be exceeded.");
		}
		Start s = admit(player, name, Game.MINES, stake, bombs);
		if (!s.ok()) return s;
		Round r = s.round();
		r.bombs = bombs;
		r.edgeBp = c.minesEdgeBp();
		r.capBp = c.minesCapBp();
		r.reserved = r.stake; // only the current value (stake back); each tile is covered when it is revealed
		for (int t : CasinoMath.minesLayout(bombs, rng)) r.mines.add(t);
		persist();
		return s;
	}

	/** Payout after the next safe tile. */
	static long minesNextPayout(Round r) {
		try {
			return CasinoMath.minesPayout(r.stake, r.bombs, r.revealed.size() + 1, r.edgeBp);
		} catch (ArithmeticException e) {
			return Long.MAX_VALUE;
		}
	}

	/** Does the Treasury currently cover the next tile (otherwise cash-out only)? */
	public static boolean minesCovered(Round r) {
		return isOpen(r) && minesNextPayout(r) <= coverLimit(r.id);
	}

	/**
	 * Reveals a tile. Coverage for the next tile's win is checked BEFORE looking at the tile, so {@link Step#BLOCKED}
	 * reveals nothing about the bombs, and then reserved.
	 */
	public static Step minesReveal(Round r, int tile) {
		if (!isOpen(r) || Game.byId(r.game) != Game.MINES) return Step.INVALID;
		if (tile < 0 || tile >= CasinoMath.MINES_TILES || r.revealed.contains(tile)) return Step.INVALID;
		long next = minesNextPayout(r);
		if (next > coverLimit(r.id)) return Step.BLOCKED;
		r.reserved = Math.max(r.reserved, next);
		r.lastActionAt = clock.getAsLong();
		if (r.mines.contains(tile)) {
			r.boom = tile;
			decide(r, 0);
			persistStep(r);
			return Step.LOSE;
		}
		r.revealed.add(tile);
		int k = r.revealed.size();
		if (k >= CasinoMath.minesMaxSafe(r.bombs, r.edgeBp, r.capBp)) {
			decide(r, CasinoMath.minesPayout(r.stake, r.bombs, k, r.edgeBp));
			persistStep(r);
			return Step.DONE;
		}
		r.reserved = cashOutValue(r);
		persistStep(r);
		return Step.WIN;
	}

	/** Higher/lower: takes the stake and draws the first card. */
	public static Start startHilo(UUID player, String name, long stake, RandomGenerator rng) {
		CasinoConfig c = cfg();
		Start s = admit(player, name, Game.HILO, stake, 0);
		if (!s.ok()) return s;
		Round r = s.round();
		r.edgeBp = c.hiloEdgeBp();
		r.capBp = c.hiloCapBp();
		r.reserved = r.stake; // only the current value (stake back); each guess is covered when it is made
		r.card = CasinoMath.hiloRank(rng);
		r.suit = rng.nextInt(4);
		persist();
		return s;
	}

	/** Is this guess possible right now (card and cap)? */
	public static boolean hiloAllowed(Round r, boolean higher) {
		if (!isOpen(r)) return false;
		return CasinoMath.hiloWithinCap(new BigInteger(r.num), new BigInteger(r.den), r.card, higher, r.edgeBp, r.capBp);
	}

	/** Payout if this guess is right. */
	static long hiloNextPayout(Round r, boolean higher) {
		try {
			BigInteger[] m = CasinoMath.hiloStep(new BigInteger(r.num), new BigInteger(r.den), r.card, higher);
			return CasinoMath.hiloPayout(r.stake, m[0], m[1], r.edgeBp);
		} catch (ArithmeticException | IllegalArgumentException e) {
			return Long.MAX_VALUE;
		}
	}

	/** Does the Treasury currently cover this guess's win (otherwise cash out or guess the other way)? */
	public static boolean hiloCovered(Round r, boolean higher) {
		if (!isOpen(r) || CasinoMath.hiloCount(r.card, higher) <= 0) return false;
		return hiloNextPayout(r, higher) <= coverLimit(r.id);
	}

	/**
	 * Makes a guess: draws the next card (stored before anything is animated). Coverage for the possible win is checked
	 * and reserved before the draw.
	 */
	public static Step hiloGuess(Round r, boolean higher, RandomGenerator rng) {
		if (!isOpen(r) || Game.byId(r.game) != Game.HILO) return Step.INVALID;
		if (CasinoMath.hiloCount(r.card, higher) <= 0) return Step.INVALID;
		if (!hiloAllowed(r, higher)) return Step.BLOCKED;
		long potentialWin = hiloNextPayout(r, higher);
		if (potentialWin > coverLimit(r.id)) return Step.BLOCKED;
		r.reserved = Math.max(r.reserved, potentialWin);
		r.lastActionAt = clock.getAsLong();
		int previous = r.card;
		int next = CasinoMath.hiloNext(previous, rng);
		boolean win = higher ? next > previous : next < previous;
		r.history.add(previous * 4 + r.suit);
		while (r.history.size() > 20) r.history.remove(0);
		r.card = next;
		r.suit = rng.nextInt(4);
		if (!win) {
			decide(r, 0);
			persistStep(r);
			return Step.LOSE;
		}
		BigInteger[] m = CasinoMath.hiloStep(new BigInteger(r.num), new BigInteger(r.den), previous, higher);
		r.num = m[0].toString();
		r.den = m[1].toString();
		r.correct++;
		r.reserved = cashOutValue(r);
		if (!hiloAllowed(r, true) && !hiloAllowed(r, false)) {
			decide(r, cashOutValue(r));
			persistStep(r);
			return Step.DONE;
		}
		persistStep(r);
		return Step.WIN;
	}

	public static double hiloMultiplier(Round r) {
		return CasinoMath.hiloMultiplier(new BigInteger(r.num), new BigInteger(r.den), r.edgeBp);
	}

	/** May the player cash out now (at least one winning step)? */
	public static boolean canCashOut(Round r) {
		if (!isOpen(r)) return false;
		Game g = Game.byId(r.game);
		if (g == null) return false;
		return switch (g) {
			case COINFLIP -> r.wins >= 1;
			case MINES -> !r.revealed.isEmpty();
			case HILO -> r.correct >= 1;
			case SLOTS -> false;
		};
	}

	/** Player cashes out: fixes the result (then {@link #settle}). */
	public static boolean cashOut(Round r) {
		if (!canCashOut(r)) return false;
		decide(r, cashOutValue(r));
		persistStep(r);
		return true;
	}

	/** Value on cash-out or automatic settlement (close, disconnect, restart). */
	public static long cashOutValue(Round r) {
		if (CasinoData.DECIDED.equals(r.state)) return r.payout;
		Game g = Game.byId(r.game);
		if (g == null) {
			ExtGame x = EXT_GAMES.get(r.game);
			if (x == null) return r.stake; // unknown game (e.g. after a downgrade): refund the stake
			try {
				long v = x.settleValue(r);
				return v >= 0 ? v : r.stake;
			} catch (RuntimeException e) {
				SmpEconomy.LOG.error("[SMPEco] Casino {}: cannot determine the value of round {}, refunding stake", r.game, r.id, e);
				return r.stake;
			}
		}
		return switch (g) {
			case COINFLIP -> r.wins >= 1 ? r.pot : r.stake;
			case SLOTS -> r.reels != null ? CasinoMath.slotsPayout(r.stake, r.reels) : r.stake;
			case MINES -> r.revealed.isEmpty() ? r.stake : CasinoMath.minesPayout(r.stake, r.bombs, r.revealed.size(), r.edgeBp);
			case HILO -> r.correct < 1 ? r.stake : CasinoMath.hiloPayout(r.stake, new BigInteger(r.num), new BigInteger(r.den), r.edgeBp);
		};
	}

	/** Would automatic settlement only refund the stake (nothing played)? */
	static boolean isRefundState(Round r) {
		if (CasinoData.DECIDED.equals(r.state)) return r.refund;
		Game g = Game.byId(r.game);
		if (g == null) {
			ExtGame x = EXT_GAMES.get(r.game);
			try {
				return x == null || x.settleIsRefund(r);
			} catch (RuntimeException e) {
				return true;
			}
		}
		return switch (g) {
			case COINFLIP -> r.wins < 1;
			case SLOTS -> r.reels == null;
			case MINES -> r.revealed.isEmpty();
			case HILO -> r.correct < 1;
		};
	}

	public static boolean isOpen(Round r) {
		return r != null && CasinoData.PLAYING.equals(r.state) && data().rounds.get(r.id) == r;
	}

	private static void decide(Round r, long payout) {
		r.state = CasinoData.DECIDED;
		r.payout = Math.max(0, payout);
		r.refund = false;
		r.reserved = r.payout;
	}

	// One interface for all games outside the Game enum. Rounds in the casino store (casino.json) use ExtGame +
	// registerGame: games with moves start with startExt and report moves through extAddStake/extStep/extDecide
	// (blackjack); games with an instant outcome use startInstant (roulette, dice, plinko, wheel, scratch cards, with
	// InstantGame as a ready-made ExtGame). An open game window registers with the watchdog via bind(roundId, Window).
	// Settlement always goes once through settle. Games with their own store (crash, horse race) use ExtBook +
	// registerBook, so coverage, daily limit, limit reset and "one round at a time" apply across the casino.

	/** Open game window of a round (for the watchdog and admin settlements). */
	public interface Window {
		boolean isOpen();

		/** Idle: settles the round through {@link #settle} ({@code why} in the message) and closes the window. */
		void expire(String roundId, String why);

		/** The round was settled outside the window (e.g. admin); nothing is clickable any more. */
		void detach(String roundId);
	}

	/**
	 * Sub-package game with moves (e.g. blackjack). Its state lives in {@link Round#ext}, stored with the round in
	 * casino.json; money, admission, coverage, daily limit, stats and settlement go through this class.
	 */
	public interface ExtGame {
		/** Game id (= {@link Round#game}), not a built-in one. */
		String id();

		/** Coloured name for messages, e.g. {@code "§2Blackjack"}. */
		String colored();

		/** Highest possible payout of a round with this stake (the Treasury must cover it at admission). */
		long potential(long stake);

		/** Payout if an undecided round is settled now (close, disconnect, restart, idle). */
		long settleValue(Round r);

		/** Does this settlement only return the stake (nothing played, not counted in stats)? */
		default boolean settleIsRefund(Round r) {
			return false;
		}

		/** Icon in {@code /casino stats}. */
		default Item icon() {
			return Items.PAPER;
		}

		/** Name without colours (stats, e.g. "biggest win"). */
		default String label() {
			return colored().replaceAll("§.", "");
		}
	}

	/**
	 * Ready-made {@link ExtGame} for instant-outcome games ({@link #startInstant}): {@code maxPayout(stake)} is the
	 * highest possible payout across all settings of the game (for {@link #maxStake(UUID, ExtGame)}). A round that was
	 * never decided is refunded at settlement.
	 */
	public record InstantGame(String id, String colored, Item icon, LongUnaryOperator maxPayout) implements ExtGame {
		@Override
		public long potential(long stake) {
			return maxPayout.applyAsLong(stake);
		}

		@Override
		public long settleValue(Round r) {
			return r.stake;
		}

		@Override
		public boolean settleIsRefund(Round r) {
			return true;
		}
	}

	private static final Map<String, ExtGame> EXT_GAMES = new LinkedHashMap<>();

	/** Registered sub-package games in registration order (stats). */
	static List<ExtGame> extGames() {
		return List.copyOf(EXT_GAMES.values());
	}

	public static ExtGame extGame(String gameId) {
		return gameId == null ? null : EXT_GAMES.get(gameId);
	}

	/** Registers a sub-package game (from its {@code register()}). */
	public static void registerGame(ExtGame game) {
		if (game == null || game.id() == null || game.id().isBlank() || Game.byId(game.id()) != null) {
			throw new IllegalArgumentException("Casino game id invalid or taken: " + (game == null ? null : game.id()));
		}
		EXT_GAMES.put(game.id(), game);
	}

	/** Coloured name of a built-in or registered game ("Casino" for unknown ones). */
	public static String gameLabel(String gameId) {
		Game g = Game.byId(gameId);
		if (g != null) return g.colored();
		ExtGame x = EXT_GAMES.get(gameId);
		return x == null ? "Casino" : x.colored();
	}

	/** Highest stake this sub-package game would admit right now (ignoring balance), 0 = locked. */
	public static long maxStake(UUID player, ExtGame game) {
		return maxStake(player, game::potential);
	}

	private static void requireRegistered(ExtGame game) {
		if (game == null || EXT_GAMES.get(game.id()) != game) throw new IllegalArgumentException("Game not registered: " + (game == null ? null : game.id()));
	}

	/**
	 * Starts a round of a sub-package game: same admission as the built-in games (casino open, one round per player,
	 * min/max bet up to 2 % of the Treasury, Treasury covers {@link ExtGame#potential}, daily loss limit, balance),
	 * stake via {@link Treasury#collectFrom}. Reserves only the stake at first; the game then stores its state in
	 * {@link Round#ext} and reports it through {@link #extStep} or {@link #extDecide}.
	 */
	public static Start startExt(UUID player, String name, ExtGame game, long stake) {
		requireRegistered(game);
		long potential = potentialSafe(game::potential, stake);
		String error = checkAdmission(player, stake, potential, game::potential);
		if (error != null) return Start.fail(error);
		Start s = open(player, name, game.id(), stake, potential);
		if (s.ok()) s.round().reserved = stake; // only the current value; each move is covered through extStep/extAddStake
		return s;
	}

	/**
	 * Further stake in an open round (e.g. blackjack double or split). Checks the daily loss limit for the whole round
	 * stake, coverage of {@code reservedAfter} (highest possible payout afterwards) BEFORE the move and the balance,
	 * collects via {@link Treasury#collectFrom} and saves. null = booked, otherwise a message for the player (nothing
	 * changed).
	 */
	public static String extAddStake(Round r, long extra, long reservedAfter) {
		if (!isOpen(r) || EXT_GAMES.get(r.game) == null) return "This round is already over.";
		if (!Money.isValidAmount(extra)) return "Invalid amount.";
		long total;
		try {
			total = Math.addExact(r.stake, extra);
		} catch (ArithmeticException e) {
			return "Invalid amount.";
		}
		// lossRoom already deducts the stake of this open round
		long room = lossRoom(r.player);
		if (extra > room) {
			return "Daily loss limit reached (" + Msg.moneyStr(lossLimit(r.player)) + "§c) – this would put " + Msg.moneyStr(total) + " §cat stake.";
		}
		if (reservedAfter > coverLimit(r.id)) return "The Treasury can't cover " + Msg.moneyStr(reservedAfter) + " §cright now.";
		if (!EconomyService.has(r.player, extra)) return "Not enough money (" + Msg.moneyStr(extra) + "§c needed).";
		Result res = Treasury.collectFrom(r.player, extra, REASON);
		if (!res.ok()) return res.message();
		r.stake = total;
		r.reserved = Math.max(r.reserved, reservedAfter);
		r.lastActionAt = clock.getAsLong();
		persist();
		return null;
	}

	/** Move without money: reservation = highest possible payout now, resets the idle clock, saves. */
	public static void extStep(Round r, long reserved) {
		if (!isOpen(r)) return;
		r.reserved = Math.max(0, reserved);
		r.lastActionAt = clock.getAsLong();
		persistStep(r);
	}

	/**
	 * Player action in an open (possibly decided) round without a state change, e.g. a scratch field: only resets the
	 * idle clock, since the watchdog settles rounds with an open window after {@link CasinoConfig#idleTimeoutMinutes}.
	 */
	public static void extTouch(Round r) {
		if (r != null && data().rounds.get(r.id) == r) r.lastActionAt = clock.getAsLong();
	}

	/**
	 * Final result is known (before any animation): fixes the payout and saves; settlement goes through {@link
	 * #settle}.
	 */
	public static boolean extDecide(Round r, long payout) {
		if (!isOpen(r)) return false;
		decide(r, payout);
		r.lastActionAt = clock.getAsLong();
		persistStep(r);
		return true;
	}

	/**
	 * Round of an instant-outcome game (roulette, dice, plinko, wheel, scratch card): same admission as {@link
	 * #startExt}, except the Treasury must cover {@code potentialOf(stake)}, the highest possible payout of THIS round
	 * (e.g. for the chosen bet; monotonic in the stake, also gives the highest covered stake for the hint). After the
	 * deduction {@code outcome} determines the payout (0 = lost, at most {@code potentialOf(stake)}; it may put display
	 * data in {@link Round#reels}, {@link Round#card} or {@link Round#ext}). The round is then decided and saved with
	 * the deduction before the caller shows anything. Settled once through {@link #settle}: by the game at the end of
	 * the animation or on close, otherwise by the watchdog (after 5 s without a bound {@link Window}), or after a crash
	 * or stop by {@link #settleAll}. If {@code outcome} throws or the payout is out of range, the stake is refunded at
	 * once.
	 */
	public static Start startInstant(UUID player, String name, ExtGame game, long stake, LongUnaryOperator potentialOf, ToLongFunction<Round> outcome) {
		requireRegistered(game);
		long potential = potentialSafe(potentialOf, stake);
		String error = checkAdmission(player, stake, potential, potentialOf);
		if (error != null) return Start.fail(error);
		Start s = open(player, name, game.id(), stake, potential);
		if (!s.ok()) return s;
		Round r = s.round();
		long payout;
		try {
			payout = outcome.applyAsLong(r);
		} catch (RuntimeException e) {
			SmpEconomy.LOG.error("[SMPEco] Casino {}: cannot determine the result of round {}, refunding stake", game.id(), r.id, e);
			payout = -1;
		}
		if (payout < 0 || payout > potential) {
			if (payout >= 0) SmpEconomy.LOG.error("[SMPEco] Casino {}: payout {} outside [0, {}], refunding round {}", game.id(), payout, potential, r.id);
			voidRound(r);
			settle(r.id, "error");
			return Start.fail("Something went wrong – your bet was refunded.");
		}
		decide(r, payout);
		persist();
		return s;
	}

	/** Same, with the game's highest possible payout ({@link ExtGame#potential}). */
	public static Start startInstant(UUID player, String name, ExtGame game, long stake, ToLongFunction<Round> outcome) {
		return startInstant(player, name, game, stake, game::potential, outcome);
	}

	/** Voids a round: settlement only returns the stake (no stats, no daily loss). */
	private static void voidRound(Round r) {
		r.state = CasinoData.DECIDED;
		r.payout = r.stake;
		r.refund = true;
		r.reserved = r.stake;
	}

	/**
	 * Casino game with its own store (crash, horse race). The casino stays the single source for shared limits: the
	 * game's reservations and pending payouts count in {@link #reservedTotal} (free coverage of all games), its daily
	 * loss and open stakes in the shared daily limit ({@link #netLossToday}, {@link #lossUsed}, {@link #lossRoom}),
	 * {@link #resetLimits} also resets its day counter and {@link #hasOpenRound} blocks new casino rounds. A game that
	 * checks by itself asks with {@code excludeBook = this} ({@link #freeCoverage(String, ExtBook)}, {@link #lossUsed})
	 * so it does not count itself twice.
	 */
	public interface ExtBook {
		/** Coverage held by the game's open rounds, bets and pending payouts (Long.MAX_VALUE = overflow). */
		long reserved();

		/** Player's net loss today in this game (settled rounds; negative = in profit). */
		long netLossToday(UUID player);

		/** Stake of the player's open (unsettled) rounds or bets. */
		default long openStake(UUID player) {
			return 0;
		}

		/** Does an open round of the player in this game block new casino rounds (one round at a time)? */
		default boolean hasOpenRound(UUID player) {
			return false;
		}

		/** Admin: resets the player's day counter in this game. */
		void resetDay(UUID player);
	}

	private static final List<ExtBook> BOOKS = new ArrayList<>();

	/** Registers a game with its own store (repeat calls with the same object do nothing). */
	public static void registerBook(ExtBook book) {
		if (book != null && !BOOKS.contains(book)) BOOKS.add(book);
	}

	/** Selftests: unregisters a book registered for the test. */
	public static void unregisterBook(ExtBook book) {
		BOOKS.remove(book);
	}

	/**
	 * Settles a round: removes it (a second call does nothing), pays through {@link Treasury#payToUncapped} (on failure
	 * the payout is stored as pending and retried), books the stats and notifies the player. Open rounds are paid at
	 * their current value (mines and hi-lo with no winning step: stake back). {@code why} (nullable) appears in the
	 * message, e.g. "Server restart".
	 */
	public static Settled settle(String roundId, String why) {
		CasinoData d = data();
		Round r = d.rounds.remove(roundId);
		if (r == null) return null;
		Window gui = GUIS.remove(roundId);
		boolean refund = isRefundState(r);
		long payout = cashOutValue(r);
		if (!CasinoData.DECIDED.equals(r.state)) {
			r.state = CasinoData.DECIDED;
			r.payout = payout;
			r.refund = refund;
		}
		boolean paid = pay(r.player, payout, r.game);
		if (!refund) recordStats(r, payout);
		persist();
		notifySettled(r, payout, refund, paid, why);
		if (!refund) maybeAnnounce(r, payout);
		// settled from outside (admin): an open game GUI of this round goes back to idle
		if (gui != null) gui.detach(roundId);
		return new Settled(r, payout, refund, paid);
	}

	private static boolean pay(UUID player, long amount, String game) {
		if (amount <= 0) return true;
		Result res = Treasury.payToUncapped(player, amount, REASON);
		if (res.ok()) return true;
		PendingPayout p = new PendingPayout();
		p.player = player;
		p.amount = amount;
		p.game = game == null ? "" : game;
		p.createdAt = clock.getAsLong();
		data().pending.add(p);
		SmpEconomy.LOG.warn("[SMPEco] Casino payout {} to {} failed ({}), will be retried", Money.format(amount), player, res);
		return false;
	}

	/** Retries pending payouts (every 60 s and on join). */
	public static int retryPending(UUID onlyPlayer) {
		int done = 0;
		Iterator<PendingPayout> it = data().pending.iterator();
		List<PendingPayout> paid = new ArrayList<>();
		while (it.hasNext()) {
			PendingPayout p = it.next();
			if (onlyPlayer != null && !onlyPlayer.equals(p.player)) continue;
			if (p.player == null || (!EconomyService.hasAccount(p.player) && TestPlayers.isTestPlayer(p.player))) continue;
			// account deleted meanwhile (admin): recreate it, or the win would stay reserved forever and block coverage
			if (!EconomyService.hasAccount(p.player)) EconomyService.ensureAccount(p.player, EconomyService.nameOf(p.player).orElse(null));
			Result res = Treasury.payToUncapped(p.player, p.amount, REASON);
			if (res.ok()) {
				it.remove();
				paid.add(p);
				done++;
			}
		}
		if (done > 0) persist();
		for (PendingPayout p : paid) tell(p.player, "§aPending casino win paid out: " + Msg.moneyStr(p.amount));
		return done;
	}

	/** Settles all open rounds (server start after a crash, server stop). */
	public static int settleAll(String why) {
		int n = 0;
		for (String id : List.copyOf(data().rounds.keySet())) {
			if (settle(id, why) != null) n++;
		}
		return n;
	}

	/**
	 * Watchdog: settles rounds with no open game GUI (e.g. the GUI failed to open) after 5 s, and rounds with an open
	 * GUI that had no move for {@link CasinoConfig#idleTimeoutMinutes} (the window is closed).
	 */
	static void watchdog() {
		CasinoOffers.purgeExpired();
		long now = clock.getAsLong();
		for (Round r : List.copyOf(data().rounds.values())) {
			if (TestPlayers.isTestPlayer(r.player)) continue; // selftests clean up after themselves
			Window gui = GUIS.get(r.id);
			if (gui != null && gui.isOpen()) {
				if (idleExpired(r, now)) gui.expire(r.id, IDLE_REASON);
				continue;
			}
			if (now - r.createdAt < 5_000 && now >= r.createdAt) continue;
			settle(r.id, CLOSED_REASON);
		}
	}

	/** Reason in the settlement message: idle. */
	public static final String IDLE_REASON = "timed out";
	/** Reason in the settlement message: game window closed (or none open any more). */
	public static final String CLOSED_REASON = "window closed";
	/** Reason in the settlement message: open round at server start (after a crash). */
	public static final String RESTART_REASON = "server restart";
	/** Reason in the settlement message: open round at server stop. */
	public static final String STOP_REASON = "server stop";

	/** No move for {@link CasinoConfig#idleTimeoutMinutes} (or the clock jumped back by more than that)? */
	static boolean idleExpired(Round r, long now) {
		long last = Math.max(r.lastActionAt, r.createdAt);
		long limit = Math.max(1, cfg().idleTimeoutMinutes) * 60_000L;
		return Math.abs(now - last) >= limit;
	}

	/** Registers a round's game GUI (the watchdog settles the round after 5 s without an open window). */
	public static void bind(String roundId, Window gui) {
		if (roundId != null) GUIS.put(roundId, gui);
	}

	private static void recordStats(Round r, long payout) {
		PlayerStats s = stats(r.player);
		String day = today.get().toString();
		if (!day.equals(s.day)) {
			s.day = day;
			s.dayStaked = 0;
			s.dayPaid = 0;
		}
		s.dayStaked = Math.addExact(s.dayStaked, r.stake);
		s.dayPaid = Math.addExact(s.dayPaid, payout);
		s.rounds++;
		s.wagered = Math.addExact(s.wagered, r.stake);
		s.paidOut = Math.addExact(s.paidOut, payout);
		boolean win = payout > r.stake;
		if (win) s.wins++;
		if (win && payout > s.biggestWin) {
			s.biggestWin = payout;
			s.biggestWinGame = r.game;
		}
		CasinoData.GameStats g = s.games.computeIfAbsent(r.game, k -> new CasinoData.GameStats());
		g.rounds++;
		g.wagered = Math.addExact(g.wagered, r.stake);
		g.paidOut = Math.addExact(g.paidOut, payout);
		if (win) g.wins++;
		if (payout > g.biggestWin) g.biggestWin = payout;
		if (r.stake > 0) g.bestMultiplier = Math.max(g.bestMultiplier, payout / (double) r.stake);
	}

	/** Multiplier text of a payout, e.g. "×2.50". */
	static String multText(long stake, long payout) {
		return stake <= 0 ? "×0" : CasinoMath.formatMultiplier(payout / (double) stake);
	}

	private static void notifySettled(Round r, long payout, boolean refund, boolean paid, String why) {
		String label = gameLabel(r.game);
		String suffix = why == null ? "" : " §8(" + why + ")";
		String text;
		if (refund) {
			text = label + "§7: Round cancelled, bet " + Msg.moneyStr(payout) + " refunded." + suffix;
		} else if (payout > 0) {
			text = label + "§7: §aWon " + Msg.moneyStr(payout) + " §8(" + multText(r.stake, payout) + ", bet " + Money.format(r.stake) + ")" + suffix;
		} else {
			text = label + "§7: §cLost §8(bet " + Money.format(r.stake) + ")" + suffix;
		}
		tell(r.player, text);
		if (!paid) tell(r.player, "§eYour payout of " + Msg.moneyStr(payout) + " §ewill be paid as soon as the Treasury can cover it.");
	}

	/** Back to the casino menu, through the command so its permission check applies. */
	public static void openHub(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		if (server == null || !Gui.canOpen(player)) return;
		CommandRegistry.run(player, "casino");
	}

	/**
	 * Message to the player. If they are offline or just leaving (disconnect handling) it is delivered on the next
	 * join, because Msg.sendOrQueue would send it to the already closed connection.
	 */
	public static void tell(UUID player, String text) {
		MinecraftServer server = SmpEconomy.server();
		ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(player);
		if (p != null && !p.hasDisconnected() && p.connection != null && p.connection.isAcceptingMessages()) Msg.info(p, text);
		else PlayerDataService.addPendingMessage(player, text);
	}

	private static void maybeAnnounce(Round r, long payout) {
		CasinoConfig c = cfg();
		if (payout <= r.stake || TestPlayers.isTestPlayer(r.player)) return;
		double mult = payout / (double) r.stake;
		boolean big = payout - r.stake >= c.announceMinProfit && c.announceMinProfit > 0
			|| mult >= c.announceMinMultiplier && payout >= c.announceMinPayoutForMultiplier;
		if (!big) return;
		MinecraftServer server = SmpEconomy.server();
		if (server == null) return;
		String name = r.name.isEmpty() ? EconomyService.nameOrUnknown(r.player) : r.name;
		String text = "§e" + name + " §7won §a" + Money.format(payout) + " §7at " + gameLabel(r.game)
			+ " §8(" + multText(r.stake, payout) + ")§7!";
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (Settings.get(p, Settings.ANNOUNCEMENTS)) Msg.info(p, text);
		}
		SmpEconomy.LOG.info("[SMPEco] Casino big win: {} {} {}", name, r.game, Money.format(payout));
	}

	/** Removes all data of a test player: open rounds are NOT paid (test players only). */
	static void purge(UUID player) {
		CasinoData d = data();
		boolean changed = d.rounds.values().removeIf(r -> r.player.equals(player));
		changed |= d.pending.removeIf(p -> p.player.equals(player));
		changed |= d.players.remove(player) != null;
		if (changed) STORE.markDirty();
	}
}
