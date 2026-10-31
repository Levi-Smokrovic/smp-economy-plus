package dev.smpeconomy.features.casino;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persistent state ({@code <world>/smpeconomy/casino.json}): open rounds (outcome already drawn, or state saved after
 * each decision), per-player stats including daily loss, and payouts that could not be booked yet (retried until they
 * succeed).
 */
public final class CasinoData {
	/** Player still deciding (mines, hi-lo, double or nothing). */
	public static final String PLAYING = "playing";
	/** Final result fixed ({@link Round#payout}), only payment left. */
	public static final String DECIDED = "decided";

	public Map<String, Round> rounds = new LinkedHashMap<>();
	public Map<UUID, PlayerStats> players = new HashMap<>();
	public List<PendingPayout> pending = new ArrayList<>();
	public long nextId = 1;

	public static final class Round {
		public String id;
		public UUID player;
		public String name = "";
		/** {@link Game#id()}. */
		public String game;
		public long stake;
		/**
		 * Coverage reserved by the Treasury: coin flip and slots the highest possible or fixed payout, mines and hi-lo
		 * the current cash-out value (the next step is checked and reserved on the move).
		 */
		public long reserved;
		public long createdAt;
		/** Last move (start, tile, guess, double), for the idle limit. 0 = not recorded, use {@link #createdAt}. */
		public long lastActionAt;
		public String state = PLAYING;
		/** Final payout (when {@link #DECIDED}). */
		public long payout;
		/** Payout is a refund of the stake (round not played). */
		public boolean refund;

		// Coin flip: current pot (after wins), flips won, from PvP double or nothing
		public long pot;
		public int wins;
		public boolean pvp;
		/** Last flip won? For display after a restart or reopening. */
		public boolean lastWin;

		// Slots: payline
		public int[] reels;

		// Mines
		public int bombs;
		public List<Integer> mines = new ArrayList<>();
		public List<Integer> revealed = new ArrayList<>();
		public int edgeBp;
		public long capBp;
		/** Revealed bomb (-1 = none). */
		public int boom = -1;

		// Higher/Lower
		public int card;
		public int suit;
		/** Cards so far (rank * 4 + suit), oldest first. */
		public List<Integer> history = new ArrayList<>();
		/** Multiplier without house edge as a fraction (strings, arbitrarily large). */
		public String num = "1";
		public String den = "1";
		public int correct;

		/** State of a sub-package game ({@link Casino.ExtGame}), e.g. JSON. */
		public String ext = "";

		void fixNulls() {
			if (name == null) name = "";
			if (state == null) state = PLAYING;
			if (mines == null) mines = new ArrayList<>();
			if (revealed == null) revealed = new ArrayList<>();
			if (history == null) history = new ArrayList<>();
			if (num == null || num.isBlank()) num = "1";
			if (den == null || den.isBlank()) den = "1";
			if (lastActionAt <= 0) lastActionAt = createdAt;
			if (ext == null) ext = "";
		}
	}

	public static final class GameStats {
		public long rounds;
		public long wins;
		public long wagered;
		public long paidOut;
		public long biggestWin;
		public double bestMultiplier;
	}

	public static final class PlayerStats {
		public long rounds;
		public long wins;
		public long wagered;
		public long paidOut;
		public long biggestWin;
		public String biggestWinGame = "";
		public Map<String, GameStats> games = new HashMap<>();
		/** Day (ISO, server time zone) for {@link #dayStaked}/{@link #dayPaid}. */
		public String day = "";
		public long dayStaked;
		public long dayPaid;
		/** Personal daily loss limit (-1 = none, the server limit applies). */
		public long personalLimit = -1;
		/** Raised or removed personal limit, effective from {@link #pendingLimitDay} (-2 = none). */
		public long pendingLimit = -2;
		public String pendingLimitDay = "";
		/** Last chosen stakes per game. */
		public Map<String, Long> lastStake = new HashMap<>();
		public int lastBombs;

		void fixNulls() {
			if (games == null) games = new HashMap<>();
			games.values().removeIf(g -> g == null);
			if (day == null) day = "";
			if (biggestWinGame == null) biggestWinGame = "";
			if (pendingLimitDay == null) pendingLimitDay = "";
			if (lastStake == null) lastStake = new HashMap<>();
		}
	}

	/** Payout that could not be booked at settlement (e.g. Treasury briefly empty); retried. */
	public static final class PendingPayout {
		public UUID player;
		public long amount;
		public String game = "";
		public long createdAt;
	}

	void fixNulls() {
		if (rounds == null) rounds = new LinkedHashMap<>();
		if (players == null) players = new HashMap<>();
		if (pending == null) pending = new ArrayList<>();
		// rounds without a player can never be paid; everything else stays (unknown game: refund at settlement)
		rounds.entrySet().removeIf(e -> {
			if (e.getValue() == null) return true;
			if (e.getValue().player == null) {
				dev.smpeconomy.SmpEconomy.LOG.error("[SMPEco] casino.json: dropped round {} without a player (stake {})", e.getKey(), e.getValue().stake);
				return true;
			}
			return false;
		});
		rounds.forEach((id, r) -> {
			r.id = id;
			r.fixNulls();
		});
		players.values().removeIf(p -> p == null);
		players.values().forEach(PlayerStats::fixNulls);
		pending.removeIf(p -> p == null || p.player == null || p.amount <= 0);
		if (nextId < 1) nextId = 1;
	}
}
