package dev.smpeconomy.features.casino.games.blackjack;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure blackjack rules (no Minecraft classes, deterministic, used by the game, the selftests and the RTP simulation).
 *
 * <p>Rules: 6-deck shoe, freshly shuffled every round from a seed that is stored before the first card is dealt (own
 * SplitMix64, so a JDK update can never change the order of an open round). Dealer peeks for blackjack (a dealer
 * blackjack ends the round before any double/split), dealer stands on all 17s (incl. soft 17). Double on any first two
 * cards (also after a split), split once (any two cards of equal value; split aces get one card each), no insurance, no
 * surrender. Wins pay 1:1, a natural blackjack 3:2, ties push. The house edge comes from a fixed commission on the
 * <em>profit</em> of winning hands ({@link #COMMISSION_BP}); pushes and losses are untouched.
 */
public final class BlackjackRules {
	private BlackjackRules() {}

	public static final int DECKS = 6;
	public static final int SHOE_SIZE = 52 * DECKS;
	/** House commission on the profit of a winning hand: 6.5 % → RTP ≈ 96.6 % of all money bet (96.1 % per initial bet) with basic strategy. */
	public static final int COMMISSION_BP = 650;
	private static final BigInteger BP = BigInteger.valueOf(10_000);

	/** Card code {@code c} in [0, 52): rank {@code c % 13} (0 = ace, 1–8 = 2–9, 9 = 10, 10 = J, 11 = Q, 12 = K), suit {@code c / 13}. */
	public static int rank(int card) {
		return Math.floorMod(card, 13);
	}

	public static int suit(int card) {
		return Math.floorMod(card, 52) / 13;
	}

	/** Blackjack value: ace 11 (reduced to 1 by {@link #total}), faces 10. */
	public static int value(int card) {
		int r = rank(card);
		return r == 0 ? 11 : Math.min(10, r + 1);
	}

	/** Best total ≤ 21 if possible (aces count 11 until the hand would bust). */
	public static int total(List<Integer> cards) {
		int sum = 0;
		int aces = 0;
		for (int c : cards) {
			sum += value(c);
			if (rank(c) == 0) aces++;
		}
		while (sum > 21 && aces > 0) {
			sum -= 10;
			aces--;
		}
		return sum;
	}

	/** Does the best total still count an ace as 11 ("soft" hand)? */
	public static boolean soft(List<Integer> cards) {
		int sum = 0;
		int aces = 0;
		for (int c : cards) {
			sum += value(c);
			if (rank(c) == 0) aces++;
		}
		while (sum > 21 && aces > 0) {
			sum -= 10;
			aces--;
		}
		return aces > 0 && sum <= 21;
	}

	/** SplitMix64: fixed algorithm, independent of the JDK. */
	static final class Mix {
		private long state;

		Mix(long seed) {
			state = seed;
		}

		long nextLong() {
			long z = (state += 0x9E3779B97F4A7C15L);
			z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
			z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
			return z ^ (z >>> 31);
		}

		/** Uniform in [0, bound) without modulo bias (rejection sampling). */
		int nextInt(int bound) {
			long b = bound;
			long limit = Long.MAX_VALUE - (Long.MAX_VALUE % b);
			while (true) {
				long v = nextLong() >>> 1;
				if (v < limit) return (int) (v % b);
			}
		}
	}

	/** Shoe order for this seed (Fisher–Yates). Same seed → same order, always. */
	public static int[] shoe(long seed) {
		int[] s = new int[SHOE_SIZE];
		for (int i = 0; i < SHOE_SIZE; i++) s[i] = i % 52;
		Mix rng = new Mix(seed);
		for (int i = SHOE_SIZE - 1; i > 0; i--) {
			int j = rng.nextInt(i + 1);
			int t = s[i];
			s[i] = s[j];
			s[j] = t;
		}
		return s;
	}

	public static final String WIN = "win";
	public static final String BLACKJACK = "blackjack";
	public static final String PUSH = "push";
	public static final String LOSE = "lose";
	public static final String BUST = "bust";

	public static final class Hand {
		public List<Integer> cards = new ArrayList<>();
		public long bet;
		public boolean doubled;
		public boolean done;
		/** Came from a split (an ace + ten is then 21, not a blackjack). */
		public boolean split;
		/** Result after the round: {@link #WIN}, {@link #BLACKJACK}, {@link #PUSH}, {@link #LOSE}, {@link #BUST} ("" = open). */
		public String result = "";
		public long payout;

		Hand copy() {
			Hand h = new Hand();
			h.cards = new ArrayList<>(cards);
			h.bet = bet;
			h.doubled = doubled;
			h.done = done;
			h.split = split;
			h.result = result;
			h.payout = payout;
			return h;
		}
	}

	public static final class State {
		/** Shoe seed, stored before the first card is dealt. */
		public long seed;
		/** Cards already drawn from the shoe. */
		public int next;
		public int commissionBp = COMMISSION_BP;
		public List<Hand> hands = new ArrayList<>();
		/** Hand the player is acting on. */
		public int active;
		/** Dealer cards: [up card, hole card, draws …]. */
		public List<Integer> dealer = new ArrayList<>();
		/** Round over: dealer played (or naturals), every hand has its result and payout. */
		public boolean finished;
		/** Cached shoe (not stored). */
		transient int[] shoe;

		public State copy() {
			State s = new State();
			s.seed = seed;
			s.next = next;
			s.commissionBp = commissionBp;
			for (Hand h : hands) s.hands.add(h.copy());
			s.active = active;
			s.dealer = new ArrayList<>(dealer);
			s.finished = finished;
			s.shoe = shoe;
			return s;
		}

		public Hand activeHand() {
			return finished || active < 0 || active >= hands.size() ? null : hands.get(active);
		}

		/** Sum of all bets (initial stake + doubles + split). */
		public long totalBet() {
			long sum = 0;
			for (Hand h : hands) sum = Math.addExact(sum, h.bet);
			return sum;
		}

		/** Total payout (only meaningful once {@link #finished}). */
		public long payout() {
			long sum = 0;
			for (Hand h : hands) sum = Math.addExact(sum, h.payout);
			return sum;
		}

		void fixNulls() {
			if (hands == null) hands = new ArrayList<>();
			hands.removeIf(h -> h == null);
			for (Hand h : hands) {
				if (h.cards == null) h.cards = new ArrayList<>();
				if (h.result == null) h.result = "";
			}
			if (dealer == null) dealer = new ArrayList<>();
		}
	}

	static int draw(State s) {
		if (s.shoe == null) s.shoe = shoe(s.seed);
		if (s.next >= s.shoe.length) throw new IllegalStateException("shoe empty");
		return s.shoe[s.next++];
	}

	/** Payout of a winning hand: bet back + profit (1:1, natural 3:2) minus the commission on the profit (rounded down). */
	public static long winPayout(long bet, boolean natural, int commissionBp) {
		BigInteger b = BigInteger.valueOf(bet);
		BigInteger profit = natural ? b.multiply(BigInteger.valueOf(3)).divide(BigInteger.TWO) : b;
		BigInteger net = profit.multiply(BP.subtract(BigInteger.valueOf(commissionBp))).divide(BP);
		return b.add(net).longValueExact();
	}

	/** Highest payout a round with this stake can ever reach: split once, double both hands, both win. */
	public static long potential(long stake, int commissionBp) {
		long doubled = Math.multiplyExact(stake, 2);
		long split = Math.multiplyExact(winPayout(doubled, false, commissionBp), 2);
		return Math.max(split, winPayout(stake, true, commissionBp));
	}

	/**
	 * What the round can pay at most from its current, visible state if the player only hits/stands from now on (no
	 * further double/split), the coverage the casino reserves. Uses no hidden information (dealer hole card, shoe).
	 */
	public static long maxPayoutNow(State s) {
		if (s.finished) return s.payout();
		long sum = 0;
		for (Hand h : s.hands) {
			if (h.done && total(h.cards) > 21) continue;
			sum = Math.addExact(sum, winPayout(h.bet, false, s.commissionBp));
		}
		return sum;
	}

	/** {@link #maxPayoutNow} after doubling the active hand. */
	public static long maxPayoutAfterDouble(State s) {
		Hand a = s.activeHand();
		if (a == null) return maxPayoutNow(s);
		long sum = 0;
		for (Hand h : s.hands) {
			if (h.done && total(h.cards) > 21) continue;
			long bet = h == a ? Math.multiplyExact(h.bet, 2) : h.bet;
			sum = Math.addExact(sum, winPayout(bet, false, s.commissionBp));
		}
		return sum;
	}

	/** {@link #maxPayoutNow} after splitting (two hands with the same bet). */
	public static long maxPayoutAfterSplit(State s) {
		Hand a = s.activeHand();
		if (a == null) return maxPayoutNow(s);
		return Math.multiplyExact(winPayout(a.bet, false, s.commissionBp), 2);
	}

	/** True blackjack: first two cards = 21, not from a split. */
	public static boolean natural(Hand h) {
		return !h.split && h.cards.size() == 2 && total(h.cards) == 21;
	}

	public static boolean dealerNatural(State s) {
		return s.dealer.size() == 2 && total(s.dealer) == 21;
	}

	/** New round: stores the seed, then deals player, dealer up, player, dealer hole. Naturals end the round at once. */
	public static State deal(long seed, long bet, int commissionBp) {
		State s = new State();
		s.seed = seed;
		return deal(s, bet, commissionBp);
	}

	/** Selftests: deal from a fixed card order (not stored, only for states that are never serialized). */
	static State dealFrom(int[] cards, long bet, int commissionBp) {
		State s = new State();
		s.shoe = cards.clone();
		return deal(s, bet, commissionBp);
	}

	private static State deal(State s, long bet, int commissionBp) {
		s.commissionBp = commissionBp;
		Hand h = new Hand();
		h.bet = bet;
		s.hands.add(h);
		h.cards.add(draw(s));
		s.dealer.add(draw(s));
		h.cards.add(draw(s));
		s.dealer.add(draw(s));
		// dealer peeks (up card ace/ten): a dealer blackjack ends the round; so does a player blackjack
		if (natural(h) || dealerNatural(s)) {
			h.done = true;
			finish(s);
		} else if (total(h.cards) == 21) {
			h.done = true; // cannot happen with two cards (21 = natural), kept for safety
			advance(s);
		}
		return s;
	}

	public static boolean canHit(State s) {
		Hand h = s.activeHand();
		return h != null && !h.done && total(h.cards) < 21 && !(h.split && rank(h.cards.get(0)) == 0);
	}

	public static boolean canStand(State s) {
		Hand h = s.activeHand();
		return h != null && !h.done;
	}

	public static boolean canDouble(State s) {
		Hand h = s.activeHand();
		return h != null && !h.done && h.cards.size() == 2 && !h.doubled && !(h.split && rank(h.cards.get(0)) == 0);
	}

	/** Split once: only the very first hand, two cards of equal value. */
	public static boolean canSplit(State s) {
		Hand h = s.activeHand();
		return h != null && !h.done && s.hands.size() == 1 && h.cards.size() == 2 && value(h.cards.get(0)) == value(h.cards.get(1));
	}

	public static void hit(State s) {
		if (!canHit(s)) throw new IllegalStateException("hit not allowed");
		Hand h = s.activeHand();
		h.cards.add(draw(s));
		if (total(h.cards) >= 21) {
			h.done = true;
			advance(s);
		}
	}

	public static void stand(State s) {
		if (!canStand(s)) throw new IllegalStateException("stand not allowed");
		s.activeHand().done = true;
		advance(s);
	}

	/** Double the bet (the caller has collected it), take exactly one card, hand is done. */
	public static void doubleDown(State s) {
		if (!canDouble(s)) throw new IllegalStateException("double not allowed");
		Hand h = s.activeHand();
		h.bet = Math.multiplyExact(h.bet, 2);
		h.doubled = true;
		h.cards.add(draw(s));
		h.done = true;
		advance(s);
	}

	/** Split into two hands with the same bet (the caller has collected it); the first hand gets its second card now. */
	public static void split(State s) {
		if (!canSplit(s)) throw new IllegalStateException("split not allowed");
		Hand a = s.activeHand();
		Hand b = new Hand();
		b.bet = a.bet;
		b.split = true;
		a.split = true;
		b.cards.add(a.cards.remove(1));
		s.hands.add(b);
		completeSplitHand(s, a);
		if (a.done) advance(s);
	}

	/** Second card for a split hand; split aces (one card each) and 21 are done at once. */
	private static void completeSplitHand(State s, Hand h) {
		if (h.cards.size() >= 2) return;
		h.cards.add(draw(s));
		if (rank(h.cards.get(0)) == 0 || total(h.cards) >= 21) h.done = true;
	}

	/** Next open hand (dealing the second card of a split hand), or the dealer once every hand is done. */
	static void advance(State s) {
		while (s.active < s.hands.size()) {
			Hand h = s.hands.get(s.active);
			if (!h.done) return;
			s.active++;
			if (s.active < s.hands.size()) completeSplitHand(s, s.hands.get(s.active));
		}
		finish(s);
	}

	/** Dealer plays (stands on all 17s) unless every hand busted or a natural decided the round; results + payouts. */
	static void finish(State s) {
		if (s.finished) return;
		s.active = s.hands.size();
		boolean anyLive = false;
		for (Hand h : s.hands) {
			if (total(h.cards) <= 21) anyLive = true;
		}
		boolean naturalRound = s.hands.size() == 1 && (natural(s.hands.get(0)) || dealerNatural(s));
		if (anyLive && !naturalRound) {
			while (total(s.dealer) < 17) s.dealer.add(draw(s));
		}
		int dealer = total(s.dealer);
		boolean dealerBj = dealerNatural(s);
		for (Hand h : s.hands) {
			int p = total(h.cards);
			if (p > 21) {
				h.result = BUST;
				h.payout = 0;
			} else if (natural(h)) {
				h.result = dealerBj ? PUSH : BLACKJACK;
				h.payout = dealerBj ? h.bet : winPayout(h.bet, true, s.commissionBp);
			} else if (dealerBj) {
				h.result = LOSE;
				h.payout = 0;
			} else if (dealer > 21 || p > dealer) {
				h.result = WIN;
				h.payout = winPayout(h.bet, false, s.commissionBp);
			} else if (p == dealer) {
				h.result = PUSH;
				h.payout = h.bet;
			} else {
				h.result = LOSE;
				h.payout = 0;
			}
			h.done = true;
		}
		s.finished = true;
	}

	/**
	 * Payout if the round is closed now (window closed, disconnect, restart, idle): every open hand stands, the dealer
	 * plays from the stored shoe. Does not change {@code s}.
	 */
	public static long payoutIfStandAll(State s) {
		if (s.finished) return s.payout();
		State c = s.copy();
		standAll(c);
		return c.payout();
	}

	/** Stand on every remaining hand (dealer plays). */
	public static void standAll(State s) {
		int guard = 0;
		while (!s.finished && guard++ < 10) {
			Hand h = s.activeHand();
			if (h == null) {
				finish(s);
				break;
			}
			if (h.cards.size() < 2) completeSplitHand(s, h);
			if (!h.done) h.done = true;
			advance(s);
		}
	}

	public enum Move { HIT, STAND, DOUBLE, SPLIT }

	/** Basic strategy for these rules (multi-deck, dealer stands on soft 17, double after split, no surrender). */
	public static Move basic(State s) {
		Hand h = s.activeHand();
		if (h == null) return Move.STAND;
		int up = value(s.dealer.get(0)); // 2..11
		if (canSplit(s)) {
			int v = value(h.cards.get(0));
			boolean split = switch (v) {
				case 11, 8 -> true;
				case 10, 5 -> false;
				case 9 -> up <= 9 && up != 7;
				case 7 -> up <= 7;
				case 6 -> up <= 6;
				case 4 -> up == 5 || up == 6;
				case 3, 2 -> up <= 7;
				default -> false;
			};
			if (split) return Move.SPLIT;
		}
		int t = total(h.cards);
		boolean dbl = canDouble(s);
		if (soft(h.cards) && t <= 21) {
			if (t >= 19) return Move.STAND;
			if (t == 18) {
				if (up >= 3 && up <= 6) return dbl ? Move.DOUBLE : Move.STAND;
				return up <= 8 ? Move.STAND : Move.HIT;
			}
			boolean d = switch (t) {
				case 17 -> up >= 3 && up <= 6;
				case 16, 15 -> up >= 4 && up <= 6;
				case 14, 13 -> up == 5 || up == 6;
				default -> false;
			};
			return d && dbl ? Move.DOUBLE : Move.HIT;
		}
		if (t >= 17) return Move.STAND;
		if (t >= 13) return up <= 6 ? Move.STAND : Move.HIT;
		if (t == 12) return up >= 4 && up <= 6 ? Move.STAND : Move.HIT;
		if (t == 11) return up <= 10 && dbl ? Move.DOUBLE : Move.HIT;
		if (t == 10) return up <= 9 && dbl ? Move.DOUBLE : Move.HIT;
		if (t == 9) return up >= 3 && up <= 6 && dbl ? Move.DOUBLE : Move.HIT;
		return Move.HIT;
	}
}
