package dev.smpeconomy.features.casino.games.horserace;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persistent state ({@code <world>/smpeconomy/casino_horserace.json}): the current race card with all bets (the stakes are
 * already in the treasury), the decided outcome of a running race (winner + payout of every bet, stored before the
 * animation starts), payouts that could not be booked yet, and per-player statistics incl. today's net loss.
 */
public final class HorseData {
	/** Card open for bets, no outcome yet. */
	public static final String BETTING = "betting";
	/** Outcome decided ({@link Race#winner}, {@link Bet#payout}), animation running, bets not settled yet. */
	public static final String RUNNING = "running";

	public Race race;
	/** Last finished race (display only: results/animation for viewers). */
	public Race last;
	public long nextRaceId = 1;
	/** Start of the last race (epoch ms), for the global cadence. */
	public long lastStartAt;
	/** Last "race starts soon" chat announcement (epoch ms). */
	public long lastAnnounceAt;
	public List<Pending> pending = new ArrayList<>();
	public Map<UUID, PlayerStats> players = new HashMap<>();
	/** Recent results, newest first (display). */
	public List<History> history = new ArrayList<>();

	public static final class Race {
		public long id;
		public String state = BETTING;
		/** Win chances in basis points per horse (sum 10,000). */
		public int[] chances;
		/** Odds in hundredths per horse (fixed for the card). */
		public int[] odds;
		public long createdAt;
		/** Betting closes and the race starts at this time (0 = waiting for the first bet). */
		public long closesAt;
		public long startedAt;
		/** Animation seed (display only). */
		public long seed;
		/** Winning horse 0–5 (-1 while betting). */
		public int winner = -1;
		/** Bets are paid out at this time (winner crosses the line). */
		public long settleAt;
		/** Animation incl. result display ends at this time. */
		public long endAt;
		/** Bets by player: at most one per player and race. A settled bet is removed, so it cannot pay twice. */
		public Map<UUID, Bet> bets = new LinkedHashMap<>();
		/** Horse of every bet when the race started (display after settlement: "your horse"). */
		public Map<UUID, Integer> picks = new LinkedHashMap<>();

		boolean valid() {
			if (chances == null || odds == null || chances.length != HorseMath.HORSES || odds.length != HorseMath.HORSES) return false;
			int sum = 0;
			for (int i = 0; i < HorseMath.HORSES; i++) {
				if (chances[i] <= 0 || odds[i] <= 0) return false;
				sum += chances[i];
			}
			return sum == HorseMath.BP && (!RUNNING.equals(state) || (winner >= 0 && winner < HorseMath.HORSES));
		}

		long pool() {
			long sum = 0;
			for (Bet b : bets.values()) sum = Math.addExact(sum, b.stake);
			return sum;
		}

		void fixNulls() {
			if (state == null) state = BETTING;
			if (bets == null) bets = new LinkedHashMap<>();
			if (picks == null) picks = new LinkedHashMap<>();
			picks.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null);
			bets.entrySet().removeIf(e -> {
				if (e.getValue() != null && e.getKey() != null) return false;
				dev.smpeconomy.SmpEconomy.LOG.error("[SMPEco] casino_horserace.json: broken bet in race {} dropped", id);
				return true;
			});
			bets.forEach((k, b) -> {
				b.player = k;
				if (b.name == null) b.name = "";
			});
		}
	}

	public static final class Bet {
		public UUID player;
		public String name = "";
		/** Horse 0–5. */
		public int horse;
		public long stake;
		/** Odds in hundredths when the bet was placed. */
		public int odds;
		/** Decided payout (-1 = not decided yet). */
		public long payout = -1;
		public long placedAt;
	}

	/** A payout that could not be booked when the race was settled; retried until it succeeds. */
	public static final class Pending {
		public UUID player;
		public long amount;
		public long raceId;
		public long createdAt;
	}

	public static final class PlayerStats {
		public long races;
		public long wins;
		public long wagered;
		public long paidOut;
		public long biggestWin;
		/** Day (ISO, server time zone) of {@link #dayStaked}/{@link #dayPaid}. */
		public String day = "";
		public long dayStaked;
		public long dayPaid;
		/** Last bet (comfort). */
		public long lastStake;

		void fixNulls() {
			if (day == null) day = "";
		}
	}

	public static final class History {
		public long raceId;
		public int winner;
		public int odds;
		public long finishedAt;
	}

	void fixNulls() {
		if (pending == null) pending = new ArrayList<>();
		if (players == null) players = new HashMap<>();
		if (history == null) history = new ArrayList<>();
		pending.removeIf(p -> p == null || p.player == null || p.amount <= 0);
		players.values().removeIf(p -> p == null);
		players.values().forEach(PlayerStats::fixNulls);
		history.removeIf(h -> h == null);
		if (race != null) race.fixNulls();
		if (last != null) last.fixNulls();
		if (nextRaceId < 1) nextRaceId = 1;
	}
}
