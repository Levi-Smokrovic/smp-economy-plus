package dev.smpeconomy.features.casino.games.blackjack;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.entity.FakePlayer;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.Treasury;
import dev.smpeconomy.core.test.Assert;
import dev.smpeconomy.core.test.SelfTests;
import dev.smpeconomy.core.test.TestContext;
import dev.smpeconomy.features.casino.Casino;
import dev.smpeconomy.features.casino.CasinoConfig;
import dev.smpeconomy.features.casino.CasinoData;
import dev.smpeconomy.features.casino.CasinoData.Round;
import dev.smpeconomy.features.casino.games.blackjack.BlackjackGame.Move;
import dev.smpeconomy.features.casino.games.blackjack.BlackjackRules.Hand;
import dev.smpeconomy.features.casino.games.blackjack.BlackjackRules.State;

/**
 * Selftests ({@code /smpeco selftest casino-blackjack}): rules with fixed shoes, RTP simulation (200k rounds, basic
 * strategy), and, through the real casino services, stake/Treasury guards, double/split guards, exactly-once settlement,
 * restart recovery and money conservation over 10k rounds.
 */
final class BlackjackSelfTests {
	static final String F = "casino-blackjack";
	/** Long-run RTP with basic strategy (paid / all money bet incl. doubles and splits), 60M simulated rounds offline. */
	static final double REFERENCE_RTP = 0.9658;
	/** Same per initial bet (the classic "house edge" view: 3.9 %). */
	static final double REFERENCE_RTP_INITIAL = 0.9614;
	static final int SIM_ROUNDS = 200_000;

	private BlackjackSelfTests() {}

	static void register() {
		SelfTests.register(F, "rules", BlackjackSelfTests::rules);
		SelfTests.register(F, "split-double", BlackjackSelfTests::splitDouble);
		SelfTests.register(F, "shoe-seed", BlackjackSelfTests::shoeSeed);
		SelfTests.register(F, "rtp", BlackjackSelfTests::rtp);
		SelfTests.register(F, "admission-guards", BlackjackSelfTests::admissionGuards);
		SelfTests.register(F, "double-split-guards", BlackjackSelfTests::doubleSplitGuards);
		SelfTests.register(F, "exactly-once", BlackjackSelfTests::exactlyOnce);
		SelfTests.register(F, "restart-recovery", BlackjackSelfTests::restartRecovery);
		SelfTests.register(F, "english-messages", BlackjackSelfTests::englishMessages);
		SelfTests.register(F, "conservation", BlackjackSelfTests::conservation);
	}

	/** Card code for a value: 11 = ace, 2–9, 10 = ten; {@code face} 1–3 = J/Q/K. */
	static int c(int value) {
		return value == 11 ? 0 : value - 1;
	}

	static final int J = 10;
	static final int Q = 11;
	static final int K = 12;

	/** Shoe that starts with these cards, the rest are 2s (never needed). */
	static int[] shoe(int... first) {
		int[] s = new int[BlackjackRules.SHOE_SIZE];
		Arrays.fill(s, c(2));
		System.arraycopy(first, 0, s, 0, first.length);
		return s;
	}

	static final int BP = BlackjackRules.COMMISSION_BP;

	/** Casino config to defaults (restored afterwards), Treasury preserved. */
	private static CasinoConfig setup(TestContext ctx) {
		ctx.preserveTreasury();
		CasinoConfig live = Casino.cfg();
		CasinoConfig saved = DataStore.gson().fromJson(DataStore.gson().toJson(live), CasinoConfig.class);
		ctx.cleanup(() -> copyConfig(saved, live));
		copyConfig(new CasinoConfig(), live);
		return live;
	}

	private static void copyConfig(CasinoConfig from, CasinoConfig to) {
		try {
			for (Field f : CasinoConfig.class.getFields()) {
				if (!Modifier.isStatic(f.getModifiers()) && !Modifier.isFinal(f.getModifiers())) f.set(to, f.get(from));
			}
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Treasury balance so that exactly {@code free} is not reserved by other rounds. */
	private static void setTreasuryFree(long free) {
		EconomyService.set(Treasury.ID, Math.addExact(free, Casino.reservedTotal(null)));
	}

	private static FakePlayer funded(TestContext ctx, String name, long cents) {
		FakePlayer p = ctx.player(name);
		EconomyService.set(p.getUUID(), cents);
		return p;
	}

	private static String name(FakePlayer p) {
		return p.getGameProfile().name();
	}

	/** First seed (from a fixed sequence) whose dealt round matches. */
	static long seedWhere(Predicate<State> p) {
		SplittableRandom rng = new SplittableRandom(777);
		for (int i = 0; i < 1_000_000; i++) {
			long seed = rng.nextLong();
			if (p.test(BlackjackRules.deal(seed, 1_000, BP))) return seed;
		}
		throw new AssertionError("no seed found");
	}

	private static String fmt(double d) {
		return String.format(java.util.Locale.ROOT, "%.4f", d);
	}

	/** Plays the round to the end with basic strategy (via the casino), returns the number of moves. */
	private static int playBasic(Round r) {
		int moves = 0;
		State s;
		while ((s = BlackjackGame.state(r)) != null && !s.finished && Casino.isOpen(r)) {
			Move m = switch (BlackjackRules.basic(s)) {
				case HIT -> Move.HIT;
				case STAND -> Move.STAND;
				case DOUBLE -> Move.DOUBLE;
				case SPLIT -> Move.SPLIT;
			};
			String err = BlackjackGame.play(r, m);
			Assert.isTrue(err == null, "move " + m + " played: " + err);
			Assert.check(++moves < 30, "round ends");
		}
		return moves;
	}

	private static void rules(TestContext ctx) {
		long bet = 10_000;
		// payouts: 1:1 and 3:2 minus the commission on the profit
		Assert.eq(bet + bet * (10_000 - BP) / 10_000, BlackjackRules.winPayout(bet, false, BP), "win 1:1 minus commission");
		Assert.eq(bet + bet * 3 / 2 * (10_000 - BP) / 10_000, BlackjackRules.winPayout(bet, true, BP), "blackjack 3:2 minus commission");
		Assert.eq(25_000L, BlackjackRules.winPayout(bet, true, 0), "3:2 without commission");
		Assert.eq(2 * BlackjackRules.winPayout(2 * bet, false, BP), BlackjackRules.potential(bet, BP), "potential = split + double both + both win");
		Assert.eq(19L, BlackjackRules.total(List.of(c(11), c(8))), "A+8 = 19");
		Assert.isTrue(BlackjackRules.soft(List.of(c(11), c(6))), "A+6 soft");
		Assert.eq(12L, BlackjackRules.total(List.of(c(11), c(11))), "A+A = 12");
		Assert.eq(21L, BlackjackRules.total(List.of(c(11), K, c(11), c(9))), "A+K+A+9 = 21");
		Assert.isFalse(BlackjackRules.soft(List.of(c(11), c(6), K)), "A+6+K hard 17");
		Assert.eq(10L, BlackjackRules.value(Q), "queen = 10");

		// natural vs 16 → 3:2, round over at once
		State s = BlackjackRules.dealFrom(shoe(c(11), c(9), K, c(7)), bet, BP);
		Assert.isTrue(s.finished, "natural ends the round");
		Assert.eq(BlackjackRules.BLACKJACK, s.hands.get(0).result, "blackjack");
		Assert.eq(BlackjackRules.winPayout(bet, true, BP), s.payout(), "blackjack pays 3:2");
		Assert.eq(2L, s.dealer.size(), "dealer does not draw");
		// both naturals → push
		s = BlackjackRules.dealFrom(shoe(c(11), c(11), K, Q), bet, BP);
		Assert.eq(BlackjackRules.PUSH, s.hands.get(0).result, "blackjack vs blackjack = push");
		Assert.eq(bet, s.payout(), "push returns the bet");
		// dealer natural (peek) vs 20 → lose at once, no move possible
		s = BlackjackRules.dealFrom(shoe(K, c(11), Q, J), bet, BP);
		Assert.isTrue(s.finished, "dealer blackjack ends the round");
		Assert.eq(BlackjackRules.LOSE, s.hands.get(0).result, "20 loses to dealer blackjack");
		Assert.eq(0L, s.payout(), "nothing back");
		Assert.isFalse(BlackjackRules.canHit(s) || BlackjackRules.canDouble(s) || BlackjackRules.canSplit(s), "no moves");
		// dealer stands on soft 17: 18 vs A+6
		s = BlackjackRules.dealFrom(shoe(K, c(11), c(8), c(6), c(5)), bet, BP);
		Assert.isFalse(s.finished, "no natural");
		BlackjackRules.stand(s);
		Assert.eq(2L, s.dealer.size(), "dealer stands on soft 17");
		Assert.eq(BlackjackRules.WIN, s.hands.get(0).result, "18 beats 17");
		Assert.eq(BlackjackRules.winPayout(bet, false, BP), s.payout(), "win pays 1:1 minus commission");
		// dealer hits 16: 19 vs 10+6, draws 5 → 21
		s = BlackjackRules.dealFrom(shoe(K, c(10), c(9), c(6), c(5)), bet, BP);
		BlackjackRules.stand(s);
		Assert.eq(3L, s.dealer.size(), "dealer hits 16");
		Assert.eq(BlackjackRules.LOSE, s.hands.get(0).result, "19 loses to 21");
		// ties push
		s = BlackjackRules.dealFrom(shoe(K, c(10), c(8), c(8)), bet, BP);
		BlackjackRules.stand(s);
		Assert.eq(BlackjackRules.PUSH, s.hands.get(0).result, "18 vs 18 push");
		Assert.eq(bet, s.payout(), "push returns the bet");
		// bust: dealer does not draw, payout 0
		s = BlackjackRules.dealFrom(shoe(K, c(9), c(6), c(5), Q, c(3)), bet, BP);
		long before = BlackjackRules.payoutIfStandAll(s);
		Assert.eq(BlackjackRules.winPayout(bet, false, BP), before, "standing on 16 would win (dealer 14 draws the Q and busts)");
		BlackjackRules.hit(s);
		Assert.isTrue(s.finished, "bust ends the round");
		Assert.eq(BlackjackRules.BUST, s.hands.get(0).result, "bust");
		Assert.eq(2L, s.dealer.size(), "dealer does not draw after a bust");
		Assert.eq(0L, s.payout(), "bust pays nothing");
		// no double after a hit
		s = BlackjackRules.dealFrom(shoe(c(2), c(9), c(3), c(7), c(4)), bet, BP);
		Assert.isTrue(BlackjackRules.canDouble(s), "double on the first two cards");
		BlackjackRules.hit(s);
		Assert.isFalse(BlackjackRules.canDouble(s), "no double after a hit");
		Assert.isFalse(BlackjackRules.canSplit(s), "no split of 2+3");
		ctx.detail("payouts, naturals, peek, soft 17, push, bust");
	}

	private static void splitDouble(TestContext ctx) {
		long bet = 10_000;
		// 8,8 vs 10+7: split, hand 1 = 8+3 → double → +10 = 21, hand 2 = 8+10 = 18 → stand; dealer 17
		State s = BlackjackRules.dealFrom(shoe(c(8), c(10), c(8), c(7), c(3), c(10), c(10)), bet, BP);
		Assert.isTrue(BlackjackRules.canSplit(s), "8,8 can split");
		Assert.eq(2 * BlackjackRules.winPayout(bet, false, BP), BlackjackRules.maxPayoutAfterSplit(s), "reservation after split");
		BlackjackRules.split(s);
		Assert.eq(2L, s.hands.size(), "two hands");
		Assert.eq(2L, s.hands.get(0).cards.size(), "hand 1 got its second card");
		Assert.eq(1L, s.hands.get(1).cards.size(), "hand 2 waits for its second card");
		Assert.isFalse(BlackjackRules.canSplit(s), "split only once");
		Assert.eq(BlackjackRules.winPayout(2 * bet, false, BP) + BlackjackRules.winPayout(bet, false, BP), BlackjackRules.maxPayoutAfterDouble(s), "reservation after double");
		BlackjackRules.doubleDown(s);
		Assert.eq(21L, BlackjackRules.total(s.hands.get(0).cards), "hand 1 = 21 after double");
		Assert.isTrue(s.hands.get(0).done && s.hands.get(0).doubled, "doubled hand is done");
		Assert.eq(1L, s.active, "hand 2 active");
		Assert.eq(18L, BlackjackRules.total(s.hands.get(1).cards), "hand 2 got its second card");
		BlackjackRules.stand(s);
		Assert.isTrue(s.finished, "round over");
		Assert.eq(3 * bet, s.totalBet(), "total bet = 3 × bet");
		Assert.eq(BlackjackRules.winPayout(2 * bet, false, BP) + BlackjackRules.winPayout(bet, false, BP), s.payout(), "both hands win");
		// split aces: one card each, A+K after a split is 21 but not a blackjack
		s = BlackjackRules.dealFrom(shoe(c(11), c(10), c(11), c(7), K, c(5)), bet, BP);
		BlackjackRules.split(s);
		Assert.isTrue(s.finished, "split aces: both hands done at once");
		Assert.eq(BlackjackRules.WIN, s.hands.get(0).result, "A+K after split = 21, a normal win");
		Assert.eq(BlackjackRules.winPayout(bet, false, BP), s.hands.get(0).payout, "pays 1:1, not 3:2");
		Assert.eq(BlackjackRules.LOSE, s.hands.get(1).result, "A+5 (16) loses to 17");
		// ten-value cards of different rank can be split
		s = BlackjackRules.dealFrom(shoe(K, c(6), Q, c(9)), bet, BP);
		Assert.isTrue(BlackjackRules.canSplit(s), "K+Q can split (same value)");
		// stand-all value does not change the state and equals standing everywhere
		s = BlackjackRules.dealFrom(shoe(c(8), c(6), c(8), c(10), c(2), c(9), c(10), c(5)), bet, BP);
		BlackjackRules.split(s); // hand 1 = 8+2
		String json = DataStore.gson().toJson(s);
		long value = BlackjackRules.payoutIfStandAll(s);
		Assert.eq(json, DataStore.gson().toJson(s), "payoutIfStandAll leaves the state alone");
		BlackjackRules.standAll(s);
		Assert.isTrue(s.finished, "standAll finishes");
		Assert.eq(value, s.payout(), "same value");
		Assert.eq(10L, BlackjackRules.total(s.hands.get(0).cards), "hand 1 stood on 10");
		Assert.eq(17L, BlackjackRules.total(s.hands.get(1).cards), "hand 2 got its card (9) and stood");
		ctx.detail("split + double after split, split aces, ten-value split, stand-all");
	}

	private static void shoeSeed(TestContext ctx) {
		int[] a = BlackjackRules.shoe(42);
		Assert.isTrue(Arrays.equals(a, BlackjackRules.shoe(42)), "same seed → same shoe");
		Assert.isFalse(Arrays.equals(a, BlackjackRules.shoe(43)), "other seed → other shoe");
		int[] count = new int[52];
		for (int card : a) count[card]++;
		for (int n : count) Assert.eq((long) BlackjackRules.DECKS, n, "every card " + BlackjackRules.DECKS + "×");
		// fixed values: the algorithm (SplitMix64 + Fisher–Yates) must never change, or open rounds would change after an update
		Assert.eq("[15, 19, 1, 10, 19, 36]", Arrays.toString(Arrays.copyOf(BlackjackRules.shoe(1L), 6)), "shoe(1) starts with the fixed cards");
		// dealt state follows the shoe: player 0+2, dealer 1+3
		State s = BlackjackRules.deal(99, 1_000, BP);
		int[] sh = BlackjackRules.shoe(99);
		Assert.eq(99L, s.seed, "seed stored");
		Assert.eq(List.of(sh[0], sh[2]), s.hands.get(0).cards, "player cards from the shoe");
		Assert.eq(List.of(sh[1], sh[3]), s.dealer.subList(0, 2), "dealer cards from the shoe");
		// JSON round trip (the shoe is not stored, rebuilt from the seed)
		long seed = seedWhere(x -> !x.finished);
		State open = BlackjackRules.deal(seed, 1_000, BP);
		State back = DataStore.gson().fromJson(DataStore.gson().toJson(open), State.class);
		back.fixNulls();
		Assert.isTrue(back.shoe == null, "shoe not stored");
		BlackjackRules.standAll(open);
		BlackjackRules.standAll(back);
		Assert.eq(open.dealer, back.dealer, "same dealer cards after the round trip");
		Assert.eq(open.payout(), back.payout(), "same payout after the round trip");
		ctx.detail("deterministic, 6×52 cards, fixed algorithm, round trip");
	}

	private static void rtp(TestContext ctx) {
		Assert.check(REFERENCE_RTP >= 0.95 && REFERENCE_RTP <= 0.97, "reference RTP " + REFERENCE_RTP + " in [0.95, 0.97]");
		Assert.check(REFERENCE_RTP_INITIAL >= 0.95 && REFERENCE_RTP_INITIAL <= 0.97, "reference RTP per initial bet in [0.95, 0.97]");
		SplittableRandom rng = new SplittableRandom(20261010L);
		long bet = 10_000;
		double paid = 0;
		double staked = 0;
		double[] p = new double[SIM_ROUNDS];
		double[] t = new double[SIM_ROUNDS];
		for (int i = 0; i < SIM_ROUNDS; i++) {
			State s = BlackjackRules.deal(rng.nextLong(), bet, BP);
			int guard = 0;
			while (!s.finished) {
				switch (BlackjackRules.basic(s)) {
					case HIT -> BlackjackRules.hit(s);
					case STAND -> BlackjackRules.stand(s);
					case DOUBLE -> BlackjackRules.doubleDown(s);
					case SPLIT -> BlackjackRules.split(s);
				}
				Assert.check(++guard < 30, "round ends");
			}
			p[i] = s.payout();
			t[i] = s.totalBet();
			paid += p[i];
			staked += t[i];
		}
		double rtp = paid / staked;
		double rtpInitial = (paid - staked + (double) bet * SIM_ROUNDS) / ((double) bet * SIM_ROUNDS);
		// ratio estimator: variance of (paid − rtp × staked)
		double meanT = staked / SIM_ROUNDS;
		double var = 0;
		for (int i = 0; i < SIM_ROUNDS; i++) {
			double d = p[i] - rtp * t[i];
			var += d * d;
		}
		var /= SIM_ROUNDS;
		double tol = 4 * Math.sqrt(var / SIM_ROUNDS) / meanT + 0.001;
		Assert.check(Math.abs(rtp - REFERENCE_RTP) <= tol, "RTP " + fmt(rtp) + " differs from " + REFERENCE_RTP + " (tolerance " + fmt(tol) + ")");
		Assert.check(Math.abs(rtpInitial - REFERENCE_RTP_INITIAL) <= tol * 1.2, "RTP per initial bet " + fmt(rtpInitial) + " differs from " + REFERENCE_RTP_INITIAL);
		ctx.detail("RTP " + fmt(rtp) + " of all bets (ref " + REFERENCE_RTP + " ± " + fmt(tol) + "), " + fmt(rtpInitial)
			+ " per initial bet, " + SIM_ROUNDS + " rounds basic strategy, commission " + BlackjackGui.percent(BP));
	}

	private static void admissionGuards(TestContext ctx) {
		CasinoConfig c = setup(ctx);
		FakePlayer a = funded(ctx, "BjGuardA", 100_000_000);
		UUID id = a.getUUID();
		long seed = seedWhere(x -> !x.finished);
		setTreasuryFree(1_000_000); // $10,000 free
		long maxBet = Casino.maxBet();
		Assert.check(maxBet >= c.minBet, "casino open");
		// over 2 % of the Treasury → rejected, nothing booked
		Casino.Start over = BlackjackGame.start(id, name(a), maxBet + 1, seed);
		Assert.isFalse(over.ok(), "bet above 2 % rejected");
		Assert.eq(100_000_000L, EconomyService.balance(id), "rejected: nothing taken");
		Assert.isTrue(Casino.openRound(id) == null, "rejected: no round");
		// below the minimum
		Assert.isFalse(BlackjackGame.start(id, name(a), c.minBet - 1, seed).ok(), "below the minimum rejected");
		// coverage: max win 10 % of the Treasury < potential (×7.7) of a 2 % bet → rejected; maxStake fits
		c.maxWinTreasuryPercent = 10;
		Assert.check(BlackjackGame.GAME.potential(maxBet) > Casino.coverLimit(null), "precondition: 2 % bet not covered");
		Casino.Start uncovered = BlackjackGame.start(id, name(a), maxBet, seed);
		Assert.isFalse(uncovered.ok(), "uncovered potential win rejected");
		long fit = BlackjackGame.maxStake(id);
		Assert.check(fit >= c.minBet && fit < maxBet, "maxStake below the 2 % bet: " + fit);
		Assert.check(BlackjackGame.GAME.potential(fit) <= Casino.coverLimit(null), "maxStake is covered");
		Assert.check(BlackjackGame.GAME.potential(fit + 1) > Casino.coverLimit(null), "maxStake + 1 is not covered");
		Casino.Start ok = BlackjackGame.start(id, name(a), fit, seed);
		Assert.isTrue(ok.ok(), "maxStake accepted: " + ok.error());
		Round r = ok.round();
		Assert.eq(100_000_000L - fit, EconomyService.balance(id), "stake taken");
		Assert.eq(BlackjackRules.maxPayoutNow(BlackjackGame.state(r)), r.reserved, "reserves what the visible hand can pay");
		// one round per player
		Assert.isFalse(BlackjackGame.start(id, name(a), c.minBet, seed).ok(), "second round at the same time rejected");
		Assert.eq(100_000_000L - fit, EconomyService.balance(id), "only one stake taken");
		Casino.settle(r.id, null);
		// daily loss limit
		c.maxWinTreasuryPercent = 50;
		c.dailyLossLimit = Math.max(c.minBet, Casino.netLossToday(id) + 5_000);
		long room = Casino.lossRoom(id);
		Assert.isFalse(BlackjackGame.start(id, name(a), room + 1, seed).ok(), "over the daily loss limit rejected");
		// casino closed
		c.dailyLossLimit = Money.MAX;
		c.enabled = false;
		Assert.isFalse(BlackjackGame.start(id, name(a), c.minBet, seed).ok(), "casino closed → no round");
		ctx.detail("2 %, minimum, coverage (maxStake " + Money.format(fit) + "), one round, loss limit, closed");
	}

	private static void doubleSplitGuards(TestContext ctx) {
		CasinoConfig c = setup(ctx);
		c.dailyLossLimit = Money.MAX;
		setTreasuryFree(100_000_000);
		long stake = 10_000;
		long splitSeed = seedWhere(x -> !x.finished && BlackjackRules.canSplit(x));
		// (1) not enough money for the extra bet
		FakePlayer a = funded(ctx, "BjGuardB", stake);
		Round r = BlackjackGame.start(a.getUUID(), name(a), stake, splitSeed).round();
		Assert.notNull(r, "round started");
		String ext = r.ext;
		String err = BlackjackGame.play(r, Move.SPLIT);
		Assert.notNull(err, "split without money rejected");
		Assert.eq(ext, r.ext, "state unchanged");
		Assert.eq(stake, r.stake, "stake unchanged");
		err = BlackjackGame.play(r, Move.DOUBLE);
		Assert.notNull(err, "double without money rejected");
		Assert.eq(0L, EconomyService.balance(a.getUUID()), "nothing taken");
		Casino.settle(r.id, null);
		// (2) Treasury cannot cover the higher win → blocked before any card is drawn
		FakePlayer b = funded(ctx, "BjGuardC", 10_000_000);
		r = BlackjackGame.start(b.getUUID(), name(b), stake, splitSeed).round();
		Assert.notNull(r, "round started");
		State s = BlackjackGame.state(r);
		long afterSplit = BlackjackRules.maxPayoutAfterSplit(s);
		EconomyService.set(Treasury.ID, Math.addExact(Casino.reservedTotal(r.id), afterSplit - 1));
		long t0 = Treasury.balance();
		ext = r.ext;
		err = BlackjackGame.play(r, Move.SPLIT);
		Assert.notNull(err, "uncovered split rejected");
		Assert.check(err.contains("Treasury"), "message names the Treasury: " + err);
		Assert.eq(ext, r.ext, "state unchanged (no card drawn)");
		Assert.eq(t0, Treasury.balance(), "nothing booked");
		Assert.eq(10_000_000L - stake, EconomyService.balance(b.getUUID()), "nothing taken");
		setTreasuryFree(100_000_000);
		Assert.isTrue(BlackjackGame.play(r, Move.SPLIT) == null, "covered split played");
		Assert.eq(2 * stake, r.stake, "round stake includes the split");
		Assert.eq(10_000_000L - 2 * stake, EconomyService.balance(b.getUUID()), "split bet taken");
		Assert.eq(afterSplit, r.reserved, "reserves both hands");
		Casino.settle(r.id, null);
		// (3) daily loss limit counts the whole round stake
		FakePlayer d = funded(ctx, "BjGuardD", 10_000_000);
		c.dailyLossLimit = stake + stake / 2;
		r = BlackjackGame.start(d.getUUID(), name(d), stake, splitSeed).round();
		Assert.notNull(r, "round within the limit");
		err = BlackjackGame.play(r, Move.SPLIT);
		Assert.notNull(err, "split over the daily loss limit rejected");
		Assert.check(err.contains("limit"), "message names the limit: " + err);
		Assert.eq(stake, r.stake, "stake unchanged");
		Assert.isTrue(BlackjackGame.play(r, Move.STAND) == null, "stand still allowed");
		Casino.settle(r.id, null);
		ctx.detail("money, Treasury coverage and daily limit checked before double/split");
	}

	private static void exactlyOnce(TestContext ctx) {
		CasinoConfig c = setup(ctx);
		c.dailyLossLimit = Money.MAX;
		setTreasuryFree(100_000_000);
		FakePlayer a = funded(ctx, "BjOnce", 10_000_000);
		UUID id = a.getUUID();
		long stake = 10_000;
		long b0 = EconomyService.balance(id);
		long reserved0 = Casino.reservedTotal(null);
		// round played to the end: decided (and saved) before the GUI animates, paid exactly once
		long seed = seedWhere(x -> !x.finished);
		Round r = BlackjackGame.start(id, name(a), stake, seed).round();
		Assert.notNull(r, "round started");
		Assert.eq(CasinoData.PLAYING, r.state, "waiting for the player");
		Assert.eq(seed, BlackjackGame.state(r).seed, "seed stored in the round");
		Assert.isTrue(BlackjackGame.play(r, Move.STAND) == null, "stand");
		Assert.eq(CasinoData.DECIDED, r.state, "decided after the last hand");
		State done = BlackjackGame.state(r);
		Assert.isTrue(done.finished, "state finished");
		Assert.eq(done.payout(), r.payout, "payout stored");
		Assert.notNull(BlackjackGame.play(r, Move.HIT), "no move after the end");
		Casino.Settled first = Casino.settle(r.id, null);
		Assert.eq(done.payout(), first.payout(), "paid the decided payout");
		Assert.isTrue(Casino.settle(r.id, null) == null, "second settle does nothing");
		Assert.eq(b0 - stake + done.payout(), EconomyService.balance(id), "paid exactly once");
		// natural blackjack: decided at the deal
		long bjSeed = seedWhere(x -> x.finished && BlackjackRules.BLACKJACK.equals(x.hands.get(0).result));
		long b1 = EconomyService.balance(id);
		Round bj = BlackjackGame.start(id, name(a), stake, bjSeed).round();
		Assert.eq(CasinoData.DECIDED, bj.state, "natural decided at the deal");
		Assert.eq(BlackjackRules.winPayout(stake, true, BP), Casino.settle(bj.id, null).payout(), "blackjack 3:2");
		Assert.isTrue(Casino.settle(bj.id, null) == null, "once");
		Assert.eq(b1 - stake + BlackjackRules.winPayout(stake, true, BP), EconomyService.balance(id), "balance");
		// window closed with an open hand: stands, the dealer plays from the stored shoe, paid once
		Round open = BlackjackGame.start(id, name(a), stake, seed).round();
		long expected = BlackjackRules.payoutIfStandAll(BlackjackGame.state(open));
		long b2 = EconomyService.balance(id);
		Casino.Settled closed = Casino.settle(open.id, "window closed");
		Assert.isFalse(closed.refund(), "not a refund (cards were dealt)");
		Assert.eq(expected, closed.payout(), "stand-all value");
		Assert.isTrue(Casino.settle(open.id, "window closed") == null, "once");
		Assert.eq(b2 + expected, EconomyService.balance(id), "balance");
		Assert.eq(reserved0, Casino.reservedTotal(null), "no reservation left");
		CasinoData.PlayerStats st = Casino.peekStats(id);
		Assert.eq(3L, st.rounds, "3 rounds in the stats");
		Assert.eq(3 * stake, st.wagered, "wagered");
		Assert.eq(done.payout() + BlackjackRules.winPayout(stake, true, BP) + expected, st.paidOut, "paid out");
		ctx.detail("played, natural and closed rounds each settled once");
	}

	/** Restart: the stored round (JSON) gives the same result as the live one and is settled once with that value. */
	private static void restartRecovery(TestContext ctx) {
		CasinoConfig c = setup(ctx);
		c.dailyLossLimit = Money.MAX;
		setTreasuryFree(100_000_000);
		FakePlayer a = funded(ctx, "BjRestart", 10_000_000);
		UUID id = a.getUUID();
		long stake = 10_000;
		// a non-ace pair whose split round would pay something when both hands stand (stronger check than 0 = 0)
		long seed = seedWhere(x -> {
			if (x.finished || !BlackjackRules.canSplit(x) || BlackjackRules.value(x.hands.get(0).cards.get(0)) == 11) return false;
			State y = x.copy();
			BlackjackRules.split(y);
			return !y.finished && BlackjackRules.payoutIfStandAll(y) > 0;
		});
		Round r = BlackjackGame.start(id, name(a), stake, seed).round();
		Assert.isTrue(BlackjackGame.play(r, Move.SPLIT) == null, "split");
		State live = BlackjackGame.state(r);
		Assert.isFalse(live.finished, "hand 1 still open");
		long expected = BlackjackRules.payoutIfStandAll(live);
		// what casino.json holds after a crash
		Round stored = DataStore.gson().fromJson(DataStore.gson().toJson(r), Round.class);
		Assert.eq(r.ext, stored.ext, "state stored with the round");
		Assert.eq(2 * stake, stored.stake, "stake incl. split stored");
		Assert.eq(expected, BlackjackGame.GAME.settleValue(stored), "stored round settles to the same value");
		Assert.isFalse(BlackjackGame.GAME.settleIsRefund(stored), "no refund");
		long b0 = EconomyService.balance(id);
		Casino.Settled s = Casino.settle(r.id, "server restart");
		Assert.eq(expected, s.payout(), "settled with the stand-all value");
		Assert.isTrue(Casino.settle(r.id, "server restart") == null, "once");
		Assert.eq(b0 + expected, EconomyService.balance(id), "paid once");
		// broken state (e.g. edited file) → stake refunded, no crash
		Round broken = DataStore.gson().fromJson(DataStore.gson().toJson(r), Round.class);
		broken.ext = "{not json";
		Assert.eq(broken.stake, BlackjackGame.GAME.settleValue(broken), "broken state → stake back");
		Assert.isTrue(BlackjackGame.GAME.settleIsRefund(broken), "broken state counts as refund");
		broken.ext = DataStore.gson().toJson(live);
		broken.stake = stake; // stake and state disagree
		Assert.isTrue(BlackjackGame.state(broken) == null, "inconsistent stake → state rejected");
		ctx.detail("stored split round = live value " + Money.format(expected) + ", broken state refunds");
	}

	/** Blackjack messages (admission, moves, settlement from the casino core) are plain English: no accented letters. */
	static final java.util.regex.Pattern NON_ENGLISH = java.util.regex.Pattern.compile("[\\u00C0-\\u00D6\\u00D8-\\u00F6\\u00F8-\\u00FF]");

	/** Every message a blackjack player gets from the casino core is English. */
	private static void englishMessages(TestContext ctx) {
		CasinoConfig c = setup(ctx);
		c.dailyLossLimit = Money.MAX;
		setTreasuryFree(100_000_000);
		FakePlayer a = funded(ctx, "BjEnglish", 10_000_000);
		UUID id = a.getUUID();
		long stake = 10_000;
		long seed = seedWhere(x -> !x.finished && BlackjackRules.canSplit(x));
		List<String> texts = new java.util.ArrayList<>();
		// admission (Casino.checkAdmission / open)
		String over = BlackjackGame.start(id, name(a), Casino.maxBet() + 1, seed).error();
		Assert.check(over != null && over.contains("Maximum bet"), "max bet message: " + over);
		String under = BlackjackGame.start(id, name(a), c.minBet - 1, seed).error();
		Assert.check(under != null && under.contains("Minimum bet"), "min bet message: " + under);
		Round r = BlackjackGame.start(id, name(a), stake, seed).round();
		Assert.notNull(r, "round started");
		String twice = BlackjackGame.start(id, name(a), stake, seed).error();
		Assert.check(twice != null && twice.contains("already playing"), "one round message: " + twice);
		// a move whose extra stake is refused (Casino.extAddStake)
		EconomyService.set(id, 0);
		String noMoney = BlackjackGame.play(r, Move.SPLIT);
		Assert.check(noMoney != null && noMoney.contains("Not enough money"), "split without money: " + noMoney);
		EconomyService.set(id, 10_000_000);
		texts.addAll(List.of(over, under, twice, noMoney));
		// settlement messages (Casino.notifySettled): test players get them as pending messages
		int p0 = PlayerDataService.getPendingMessages(id).size();
		Casino.settle(r.id, "window closed");
		Round refund = BlackjackGame.start(id, name(a), stake, seed).round();
		Assert.notNull(refund, "second round started");
		refund.ext = ""; // no game state → settles as a refund
		Casino.settle(refund.id, "error");
		List<String> pending = PlayerDataService.getPendingMessages(id);
		Assert.eq(p0 + 2, pending.size(), "two settlement messages queued");
		String settled = pending.get(p0);
		String refunded = pending.get(p0 + 1);
		Assert.check(settled.startsWith(BlackjackGame.LABEL + "§7: ") && (settled.contains("Won ") || settled.contains("Lost ")),
			"settlement message: " + settled);
		Assert.check(settled.contains("bet ") && settled.endsWith("(window closed)"), "English template + reason: " + settled);
		Assert.check(refunded.contains("Round cancelled, bet ") && refunded.contains("refunded") && refunded.endsWith("(error)"),
			"refund message: " + refunded);
		texts.add(settled);
		texts.add(refunded);
		// daily loss limit and closed casino
		c.dailyLossLimit = Math.max(c.minBet, Casino.netLossToday(id) + 5_000);
		String limit = BlackjackGame.start(id, name(a), Casino.lossRoom(id) + 1, seed).error();
		Assert.check(limit != null && limit.contains("Daily loss limit"), "loss limit message: " + limit);
		c.dailyLossLimit = Money.MAX;
		c.enabled = false;
		String closed = BlackjackGame.start(id, name(a), stake, seed).error();
		Assert.check(closed != null && closed.contains("closed"), "closed message: " + closed);
		texts.addAll(List.of(limit, closed));
		for (String t : texts) Assert.isFalse(NON_ENGLISH.matcher(t).find(), "non-English text in: " + t);
		ctx.detail(texts.size() + " messages English, e.g. \"" + settled.replaceAll("§.", "") + "\"");
	}

	/** 10k rounds through the casino: player + Treasury constant, Treasury = stakes − payouts, RTP near the reference. */
	private static void conservation(TestContext ctx) {
		CasinoConfig c = setup(ctx);
		c.dailyLossLimit = Money.MAX;
		FakePlayer a = funded(ctx, "BjCons", 10_000_000_000L);
		setTreasuryFree(10_000_000_000L);
		UUID id = a.getUUID();
		long t0 = Treasury.balance();
		long p0 = EconomyService.balance(id);
		long reserved0 = Casino.reservedTotal(null);
		SplittableRandom rng = new SplittableRandom(4711);
		long staked = 0;
		long paid = 0;
		int closedEarly = 0;
		int n = 10_000;
		for (int i = 0; i < n; i++) {
			long stake = c.minBet + rng.nextInt(20_000);
			Casino.Start st = BlackjackGame.start(id, name(a), stake, rng.nextLong());
			Assert.isTrue(st.ok(), "round " + i + ": " + st.error());
			Round r = st.round();
			long expected;
			if (rng.nextInt(20) == 0 && CasinoData.PLAYING.equals(r.state)) {
				expected = BlackjackRules.payoutIfStandAll(BlackjackGame.state(r)); // player closes the window
				closedEarly++;
			} else {
				playBasic(r);
				expected = BlackjackGame.state(r).payout();
			}
			long roundStake = r.stake;
			Casino.Settled s = Casino.settle(r.id, null);
			Assert.isTrue(s.paid(), "payout booked");
			Assert.eq(expected, s.payout(), "payout = game result (round " + i + ")");
			staked += roundStake;
			paid += s.payout();
			Assert.eq(t0 + p0, Treasury.balance() + EconomyService.balance(id), "player + Treasury constant after round " + i);
		}
		Assert.eq(t0 + staked - paid, Treasury.balance(), "Treasury = start + stakes − payouts");
		Assert.eq(reserved0, Casino.reservedTotal(null), "no reservations left");
		Assert.isTrue(Casino.openRound(id) == null, "no open round");
		double rtp = paid / (double) staked;
		Assert.check(Math.abs(rtp - REFERENCE_RTP) < 0.04, "RTP over the casino " + fmt(rtp) + " near " + REFERENCE_RTP);
		Treasury.Flow flow = Treasury.totalsAllTime().getOrDefault(Casino.REASON, Treasury.Flow.ZERO);
		Assert.check(flow.income() >= staked && flow.expense() >= paid, "Treasury books the casino flows");
		ctx.detail(n + " rounds (" + closedEarly + " closed early), Treasury " + (staked - paid >= 0 ? "+" : "") + Money.format(staked - paid)
			+ ", RTP " + fmt(rtp));
	}
}
