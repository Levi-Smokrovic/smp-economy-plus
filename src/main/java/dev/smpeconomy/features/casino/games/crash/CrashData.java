package dev.smpeconomy.features.casino.games.crash;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code <world>/smpeconomy/casino_crash.json}: open rounds (crash point drawn and stored before the rocket starts),
 * per-player stats incl. today's net loss (counts towards the casino's daily loss limit), and payouts that could not be
 * booked yet (retried until they succeed).
 */
public final class CrashData {
	/** Rocket still flying (or waiting for the window); the outcome depends on when the player cashes out. */
	public static final String PLAYING = "playing";
	/** Final payout fixed ({@link Round#payout}); only the booking is left. */
	public static final String DECIDED = "decided";

	public Map<String, Round> rounds = new LinkedHashMap<>();
	public Map<UUID, PlayerStats> players = new HashMap<>();
	public List<PendingPayout> pending = new ArrayList<>();
	public long nextId = 1;

	public static final class Round {
		public String id;
		public UUID player;
		public String name = "";
		public long stake;
		/** Crash point in hundredths (drawn at the start, never shown before the round is over). */
		public long crashH;
		/** Auto cash-out in hundredths, 0 = off. */
		public long autoH;
		/** Cap (hundredths) and house edge (basis points) at the start of the round. */
		public long capH;
		public int edgeBp;
		/** Speed at the start of the round. */
		public double doublingSeconds;
		/** Treasury coverage held for this round: the payout at the effective target (auto cash-out or cap). */
		public long reserved;
		public long createdAt;
		public String state = PLAYING;
		/** Last multiplier shown while flying: value of the round if it is interrupted (window closed, restart). */
		public long lastMultH = CrashMath.ONE;
		/** Multiplier at which it was cashed out (0 = not cashed out). */
		public long cashedAtH;
		public long payout;
		/** Payout = refund of the bet (nothing was played). */
		public boolean refund;

		void fixNulls() {
			if (name == null) name = "";
			if (state == null) state = PLAYING;
			if (lastMultH < CrashMath.ONE) lastMultH = CrashMath.ONE;
			if (!(doublingSeconds >= 1.0)) doublingSeconds = 5.0;
		}
	}

	public static final class PlayerStats {
		public long rounds;
		public long wins;
		public long wagered;
		public long paidOut;
		public long biggestWin;
		/** Highest multiplier cashed out (hundredths). */
		public long bestMultH;
		/** Day (ISO, server time zone) for {@link #dayStaked}/{@link #dayPaid}. */
		public String day = "";
		public long dayStaked;
		public long dayPaid;
		/** Last crash points (hundredths), newest first. */
		public List<Long> history = new ArrayList<>();
		public long lastStake;
		/** Last auto cash-out choice (hundredths, 0 = off, -1 = never chosen). */
		public long lastAutoH = -1;

		void fixNulls() {
			if (day == null) day = "";
			if (history == null) history = new ArrayList<>();
			history.removeIf(h -> h == null);
		}
	}

	/** Payout that could not be booked at the end of a round; retried until it succeeds. */
	public static final class PendingPayout {
		public UUID player;
		public long amount;
		public long createdAt;
	}

	void fixNulls() {
		if (rounds == null) rounds = new LinkedHashMap<>();
		if (players == null) players = new HashMap<>();
		if (pending == null) pending = new ArrayList<>();
		rounds.entrySet().removeIf(e -> {
			if (e.getValue() == null) return true;
			if (e.getValue().player == null) {
				dev.smpeconomy.SmpEconomy.LOG.error("[SMPEco] casino_crash.json: round {} without player dropped (bet {})", e.getKey(), e.getValue().stake);
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
