package dev.smpeconomy.features.casino.games.scratch;

import java.util.SplittableRandom;
import java.util.UUID;
import java.util.random.RandomGenerator;
import net.fabricmc.fabric.api.entity.FakePlayer;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Treasury;
import dev.smpeconomy.core.test.Assert;
import dev.smpeconomy.core.test.SelfTests;
import dev.smpeconomy.core.test.TestContext;
import dev.smpeconomy.features.casino.Casino;
import dev.smpeconomy.features.casino.CasinoConfig;
import dev.smpeconomy.features.casino.CasinoData;
import dev.smpeconomy.features.casino.CasinoData.Round;
import dev.smpeconomy.features.casino.Game;

/**
 * Selftests of the scratch cards ({@code /smpeco selftest casino-scratch}, without GUI): prize table and RTP over 200k
 * cards, grids that show exactly the drawn prize, result fixed at purchase, exactly-once settlement (also after a reload),
 * max bet / coverage / daily loss limit / one round via the casino, refund when the setup fails, money conservation.
 */
final class ScratchSelfTests {
	private static final String F = ScratchGame.TEST_FEATURE;
	private static final int SIM_CARDS = 200_000;

	private ScratchSelfTests() {}

	static void register() {
		SelfTests.register(F, "scratch-rtp", ScratchSelfTests::rtp);
		SelfTests.register(F, "scratch-help-currency", ScratchSelfTests::helpCurrency);
		SelfTests.register(F, "scratch-grids", ScratchSelfTests::grids);
		SelfTests.register(F, "scratch-decided-at-purchase", ScratchSelfTests::decidedAtPurchase);
		SelfTests.register(F, "scratch-exactly-once", ScratchSelfTests::exactlyOnce);
		SelfTests.register(F, "scratch-guards", ScratchSelfTests::guards);
		SelfTests.register(F, "scratch-loss-limit", ScratchSelfTests::lossLimit);
		SelfTests.register(F, "scratch-setup-refund", ScratchSelfTests::setupRefund);
		SelfTests.register(F, "scratch-conservation", ScratchSelfTests::conservation);
	}

	/** nextInt(bound) returns the given values first (mod bound), then a fixed seed. */
	static final class Scripted implements RandomGenerator {
		private final int[] values;
		private int index;
		private final SplittableRandom fallback = new SplittableRandom(4242);

		Scripted(int... values) {
			this.values = values;
		}

		@Override
		public long nextLong() {
			return fallback.nextLong();
		}

		@Override
		public int nextInt(int bound) {
			if (index < values.length) return Math.floorMod(values[index++], bound);
			return fallback.nextInt(bound);
		}
	}

	/** First draw = jackpot (Nether Star ×25). */
	static RandomGenerator jackpot() {
		return new Scripted(0);
	}

	/** First draw = no prize. */
	static RandomGenerator blank() {
		return new Scripted(ScratchMath.TOTAL_WEIGHT - 1);
	}

	/** Treasury, casino limits and config fields used here are restored after the test. */
	private static CasinoConfig setup(TestContext ctx) {
		ctx.preserveTreasury();
		CasinoConfig c = Casino.cfg();
		boolean enabled = c.enabled;
		long minBet = c.minBet;
		long maxBet = c.maxBet;
		double maxBetPct = c.maxBetTreasuryPercent;
		double maxWinPct = c.maxWinTreasuryPercent;
		long lossLimit = c.dailyLossLimit;
		ctx.cleanup(() -> {
			CasinoConfig live = Casino.cfg();
			live.enabled = enabled;
			live.minBet = minBet;
			live.maxBet = maxBet;
			live.maxBetTreasuryPercent = maxBetPct;
			live.maxWinTreasuryPercent = maxWinPct;
			live.dailyLossLimit = lossLimit;
		});
		CasinoConfig d = new CasinoConfig();
		c.enabled = true;
		c.minBet = d.minBet;
		c.maxBet = d.maxBet;
		c.maxBetTreasuryPercent = d.maxBetTreasuryPercent;
		c.maxWinTreasuryPercent = d.maxWinTreasuryPercent;
		c.dailyLossLimit = d.dailyLossLimit;
		return c;
	}

	/** Treasury balance so that exactly {@code free} is left after all other reservations. */
	private static long setTreasuryFree(long free) {
		long total = Math.addExact(free, Casino.reservedTotal(null));
		EconomyService.set(Treasury.ID, total);
		return total;
	}

	private static FakePlayer funded(TestContext ctx, String name, long cents) {
		FakePlayer p = ctx.player(name);
		EconomyService.set(p.getUUID(), cents);
		return p;
	}

	private static String name(FakePlayer p) {
		return p.getGameProfile().name();
	}

	private static String fmt(double d) {
		return String.format(java.util.Locale.ROOT, "%.4f", d);
	}

	private static void rtp(TestContext ctx) {
		double theo = ScratchMath.theoreticalRtp();
		Assert.check(theo >= 0.95 && theo <= 0.97, "theoretical RTP " + fmt(theo) + " not in [0.95, 0.97] (house edge 3–5 %)");
		Assert.eq(955L, Math.round(theo * 1000), "prize table pays 955 per 1,000");
		int weights = 0;
		for (int w : ScratchMath.WEIGHTS) weights += w;
		Assert.check(weights < ScratchMath.TOTAL_WEIGHT, "a blank card is possible");
		Assert.eq(25, ScratchMath.maxMultiplier(), "top prize ×25");
		// simulation through the real draw: every card's grid must show exactly its prize
		SplittableRandom rng = new SplittableRandom(20261010);
		long price = ScratchTier.BRONZE.price();
		double sum = 0;
		double sumSq = 0;
		long prizes = 0;
		long[] seen = new long[ScratchMath.SYMBOLS];
		for (int i = 0; i < SIM_CARDS; i++) {
			ScratchMath.Card card = ScratchMath.draw(rng);
			Assert.eq(card.prize(), ScratchMath.evaluate(card.grid()), "card " + i + ": grid shows the drawn prize");
			double m = ScratchMath.payout(price, card.prize()) / (double) price;
			sum += m;
			sumSq += m * m;
			if (card.prize() >= 0) {
				prizes++;
				seen[card.prize()]++;
			}
		}
		double mean = sum / SIM_CARDS;
		double var = Math.max(0, sumSq / SIM_CARDS - mean * mean);
		double tol = 4 * Math.sqrt(var / SIM_CARDS) + 0.0005;
		Assert.check(Math.abs(mean - theo) <= tol, "simulated RTP " + fmt(mean) + " differs from " + fmt(theo) + " (tolerance " + fmt(tol) + ")");
		Assert.check(mean >= 0.93 && mean <= 0.98, "simulated RTP " + fmt(mean) + " plausible");
		double chance = prizes / (double) SIM_CARDS;
		Assert.check(Math.abs(chance - ScratchMath.prizeChance()) < 0.01, "prize chance " + fmt(chance) + " ≈ " + fmt(ScratchMath.prizeChance()));
		for (int s = 0; s < ScratchMath.SYMBOLS; s++) Assert.check(seen[s] > 0, "prize " + ScratchMath.NAMES[s] + " drawn");
		ctx.detail(SIM_CARDS + " cards: RTP " + fmt(mean) + " (theory " + fmt(theo) + " ± " + fmt(tol) + "), any prize " + fmt(chance)
			+ ", jackpots " + seen[ScratchMath.SYMBOLS - 1]);
	}

	private static void grids(TestContext ctx) {
		SplittableRandom rng = new SplittableRandom(7);
		for (int prize = ScratchMath.NONE; prize < ScratchMath.SYMBOLS; prize++) {
			for (int i = 0; i < 5_000; i++) {
				int[] g = ScratchMath.grid(prize, rng);
				Assert.eq(ScratchMath.CELLS, g.length, "9 fields");
				int[] count = new int[ScratchMath.SYMBOLS];
				for (int s : g) count[s]++;
				for (int s = 0; s < ScratchMath.SYMBOLS; s++) {
					if (s == prize) Assert.eq(3, count[s], "prize symbol exactly 3×");
					else Assert.check(count[s] <= 2, "other symbol " + s + " at most 2× (prize " + prize + ")");
				}
				Assert.eq(prize, ScratchMath.evaluate(g), "evaluate = drawn prize");
			}
		}
		Assert.eq(-2, ScratchMath.evaluate(new int[] {0, 0, 0, 1, 1, 1, 2, 3, 4}), "two triples are invalid");
		for (ScratchTier t : ScratchTier.values()) {
			Assert.eq(t.price() * 25, ScratchMath.maxPayout(t.price()), t.id() + ": top prize = price × 25");
			Assert.eq(0L, ScratchMath.payout(t.price(), ScratchMath.NONE), t.id() + ": blank pays 0");
			Assert.eq(t.price(), ScratchMath.payout(t.price(), 0), t.id() + ": Copper = money back");
			Assert.eq(t, ScratchTier.byPrice(t.price()), "tier by price");
			Assert.eq(t, ScratchTier.byId(t.id().toUpperCase(java.util.Locale.ROOT)), "tier by id");
		}
		Assert.eq(10_000L, ScratchTier.BRONZE.price(), "Bronze $100");
		Assert.eq(100_000L, ScratchTier.SILVER.price(), "Silver $1,000");
		Assert.eq(1_000_000L, ScratchTier.GOLD.price(), "Gold $10,000");
		Assert.isTrue(Casino.extGame(ScratchGame.ID) == ScratchGame.GAME, "registered as casino game");
		Assert.isTrue(Game.byId(ScratchGame.ID) == null, "no clash with a built-in game");
		ctx.detail("7 × 5,000 grids, prices, payouts");
	}

	private static void decidedAtPurchase(TestContext ctx) {
		setup(ctx);
		FakePlayer a = funded(ctx, "ScratchBuyA", 10_000_000);
		setTreasuryFree(100_000_000);
		UUID id = a.getUUID();
		long b0 = EconomyService.balance(id);
		Treasury.Flow f0 = Treasury.totalsAllTime().getOrDefault(Casino.REASON, Treasury.Flow.ZERO);
		Casino.Start s = ScratchGame.buy(id, name(a), ScratchTier.BRONZE, jackpot());
		Assert.isTrue(s.ok(), "bronze card bought: " + s.error());
		Round r = s.round();
		Assert.eq(CasinoData.DECIDED, r.state, "result fixed at purchase");
		Assert.eq(ScratchGame.ID, r.game, "game id");
		Assert.eq(250_000L, r.payout, "jackpot ×25 = $2,500 stored");
		Assert.eq(250_000L, r.reserved, "Treasury reserves exactly the decided payout");
		Assert.eq(ScratchTier.BRONZE.ordinal(), r.card, "tier stored");
		Assert.eq(5, ScratchMath.evaluate(r.reels), "stored grid shows the jackpot");
		Assert.eq(b0 - 10_000, EconomyService.balance(id), "price collected, prize not paid yet");
		Treasury.Flow f1 = Treasury.totalsAllTime().getOrDefault(Casino.REASON, Treasury.Flow.ZERO);
		Assert.eq(10_000L, f1.income() - f0.income(), "Treasury booked the price under 'casino'");
		Assert.isTrue(Casino.openRound(id) == r, "card is the player's open casino round");
		Casino.Settled st = Casino.settle(r.id, null);
		Assert.eq(250_000L, st.payout(), "settle pays the decided prize");
		Assert.isTrue(st.paid(), "paid");
		Assert.eq(b0 - 10_000 + 250_000, EconomyService.balance(id), "balance after the jackpot");
		Treasury.Flow f2 = Treasury.totalsAllTime().getOrDefault(Casino.REASON, Treasury.Flow.ZERO);
		Assert.eq(250_000L, f2.expense() - f1.expense(), "Treasury booked the payout under 'casino'");
		CasinoData.PlayerStats ps = Casino.peekStats(id);
		Assert.eq(1L, ps.rounds, "statistics: 1 round");
		Assert.eq(1L, ps.games.get(ScratchGame.ID).rounds, "statistics per game");
		Assert.eq(Long.valueOf(10_000), ps.lastStake.get(ScratchGame.ID), "last card remembered");
		ctx.detail("jackpot decided + reserved at purchase, paid once on settle");
	}

	private static void exactlyOnce(TestContext ctx) {
		setup(ctx);
		FakePlayer a = funded(ctx, "ScratchOnceA", 10_000_000);
		FakePlayer b = funded(ctx, "ScratchOnceB", 10_000_000);
		setTreasuryFree(100_000_000);
		long ba = EconomyService.balance(a.getUUID());
		Round win = ScratchGame.buy(a.getUUID(), name(a), ScratchTier.BRONZE, new Scripted(2)).round(); // Diamond ×10
		Assert.eq(100_000L, win.payout, "Diamond ×10");
		Assert.eq(100_000L, Casino.settle(win.id, null).payout(), "first settle pays");
		Assert.isTrue(Casino.settle(win.id, Casino.CLOSED_REASON) == null, "second settle does nothing");
		Assert.isTrue(Casino.settle(win.id, Casino.RESTART_REASON) == null, "third settle does nothing");
		Assert.eq(ba - 10_000 + 100_000, EconomyService.balance(a.getUUID()), "paid exactly once");
		// stored and loaded again (crash between purchase and scratching): same result, settled once on restart
		long bb = EconomyService.balance(b.getUUID());
		Round open = ScratchGame.buy(b.getUUID(), name(b), ScratchTier.BRONZE, new Scripted(14)).round(); // Emerald ×5
		Assert.eq(50_000L, open.payout, "Emerald ×5");
		Round loaded = DataStore.gson().fromJson(DataStore.gson().toJson(open), Round.class);
		Assert.eq(CasinoData.DECIDED, loaded.state, "reloaded: still decided");
		Assert.eq(50_000L, loaded.payout, "reloaded: same payout");
		Assert.eq(50_000L, Casino.cashOutValue(loaded), "reloaded: settles to the decided payout");
		Assert.eq(3, ScratchMath.evaluate(loaded.reels), "reloaded: same grid");
		Assert.eq(50_000L, Casino.settle(open.id, Casino.RESTART_REASON).payout(), "restart pays the fixed prize");
		Assert.isTrue(Casino.settle(open.id, Casino.RESTART_REASON) == null, "only once");
		Assert.eq(bb - 10_000 + 50_000, EconomyService.balance(b.getUUID()), "B paid exactly once");
		// a blank card: nothing to pay, still counted once
		Round blank = ScratchGame.buy(b.getUUID(), name(b), ScratchTier.BRONZE, blank()).round();
		Assert.eq(0L, blank.payout, "blank card");
		Assert.eq(ScratchMath.NONE, ScratchMath.evaluate(blank.reels), "blank grid has no triple");
		Assert.eq(0L, Casino.settle(blank.id, null).payout(), "blank pays 0");
		Assert.isTrue(Casino.settle(blank.id, null) == null, "blank settled once");
		Assert.eq(2L, Casino.peekStats(b.getUUID()).rounds, "B: 2 rounds in the statistics");
		ctx.detail("win/blank/reloaded card each settled exactly once");
	}

	private static void guards(TestContext ctx) {
		CasinoConfig c = setup(ctx);
		FakePlayer a = funded(ctx, "ScratchGuardA", 100_000_000);
		FakePlayer b = funded(ctx, "ScratchGuardB", 100_000_000);
		UUID id = a.getUUID();
		// max bet 2 %: $25,000 Treasury → $500 max → Bronze yes, Silver no
		setTreasuryFree(2_500_000);
		Assert.check(Casino.maxBet() < ScratchTier.SILVER.price(), "precondition: max bet " + Casino.maxBet() + " < Silver");
		long before = EconomyService.balance(id);
		Casino.Start silver = ScratchGame.buy(id, name(a), ScratchTier.SILVER, jackpot());
		Assert.isFalse(silver.ok(), "Silver above the max bet rejected");
		Assert.check(silver.error().contains("Maximum bet"), "message names the max bet: " + silver.error());
		Assert.eq(before, EconomyService.balance(id), "rejected: nothing collected");
		Assert.isTrue(Casino.openRound(id) == null, "rejected: no open round");
		Assert.isTrue(ScratchGame.blockedReason(id, ScratchTier.SILVER) != null, "GUI shows Silver as locked");
		Assert.isTrue(ScratchGame.blockedReason(id, ScratchTier.BRONZE) == null, "GUI shows Bronze as available");
		// one open round per player (shared with the built-in games)
		Round open = ScratchGame.buy(id, name(a), ScratchTier.BRONZE, blank()).round();
		Assert.notNull(open, "Bronze bought");
		Assert.isFalse(ScratchGame.buy(id, name(a), ScratchTier.BRONZE, blank()).ok(), "second card while one is open rejected");
		Assert.isFalse(Casino.startSlots(id, name(a), c.minBet, new Scripted(0, 0, 0)).ok(), "slots while a card is open rejected");
		Casino.settle(open.id, null);
		// coverage: B holds a Silver jackpot ($25,000 reserved); only $2,400 free → Bronze top prize $2,500 not covered
		setTreasuryFree(100_000_000);
		Round held = ScratchGame.buy(b.getUUID(), name(b), ScratchTier.SILVER, jackpot()).round();
		Assert.notNull(held, "B: Silver card bought");
		Assert.eq(2_500_000L, held.reserved, "B: $25,000 reserved");
		setTreasuryFree(240_000);
		Assert.check(Casino.maxBet() >= ScratchTier.BRONZE.price(), "precondition: max bet allows Bronze (" + Casino.maxBet() + ")");
		before = EconomyService.balance(id);
		Casino.Start uncovered = ScratchGame.buy(id, name(a), ScratchTier.BRONZE, jackpot());
		Assert.isFalse(uncovered.ok(), "Bronze rejected: top prize not covered");
		Assert.check(uncovered.error().contains("cover"), "message names the coverage: " + uncovered.error());
		Assert.eq(before, EconomyService.balance(id), "uncovered: nothing collected");
		Assert.isTrue(ScratchGame.blockedReason(id, ScratchTier.BRONZE) != null, "GUI shows Bronze as locked");
		setTreasuryFree(250_000);
		Casino.Start covered = ScratchGame.buy(id, name(a), ScratchTier.BRONZE, jackpot());
		Assert.isTrue(covered.ok(), "exactly covered → allowed: " + covered.error());
		Assert.eq(ScratchTier.BRONZE.price(), Casino.freeCoverage(null), "the jackpot reservation uses the free coverage (only the price came in)");
		Assert.eq(250_000L, Casino.settle(covered.round().id, null).payout(), "A: jackpot paid");
		Assert.eq(2_500_000L, Casino.settle(held.id, null).payout(), "B: jackpot paid");
		// casino closed
		setTreasuryFree(100_000_000);
		c.enabled = false;
		Assert.isFalse(ScratchGame.buy(id, name(a), ScratchTier.BRONZE, blank()).ok(), "casino closed: no card");
		Assert.isTrue(ScratchGame.blockedReason(id, ScratchTier.BRONZE) != null, "GUI: closed");
		c.enabled = true;
		// not enough money
		EconomyService.set(id, 9_999);
		Casino.Start poor = ScratchGame.buy(id, name(a), ScratchTier.BRONZE, blank());
		Assert.isFalse(poor.ok(), "not enough money: rejected");
		Assert.eq(9_999L, EconomyService.balance(id), "nothing collected");
		ctx.detail("max bet, one round, coverage incl. other reservations, closed, money");
	}

	private static void lossLimit(TestContext ctx) {
		CasinoConfig c = setup(ctx);
		c.dailyLossLimit = 25_000; // $250
		FakePlayer a = funded(ctx, "ScratchLossA", 10_000_000);
		setTreasuryFree(100_000_000);
		UUID id = a.getUUID();
		for (int i = 0; i < 2; i++) {
			Round r = ScratchGame.buy(id, name(a), ScratchTier.BRONZE, blank()).round();
			Assert.notNull(r, "card " + i);
			Casino.settle(r.id, null);
		}
		Assert.eq(20_000L, Casino.netLossToday(id), "scratch losses count for the casino's daily loss ($200)");
		Assert.eq(5_000L, Casino.lossRoom(id), "$50 left – shared with all casino games");
		long before = EconomyService.balance(id);
		Casino.Start over = ScratchGame.buy(id, name(a), ScratchTier.BRONZE, blank());
		Assert.isFalse(over.ok(), "card above the remaining loss limit rejected");
		Assert.check(over.error().contains("Daily loss limit"), "message names the limit: " + over.error());
		Assert.eq(before, EconomyService.balance(id), "nothing collected");
		Assert.isTrue(ScratchGame.blockedReason(id, ScratchTier.BRONZE) != null, "GUI: limit reached");
		Assert.eq(5_000L, Casino.maxStake(id, Game.SLOTS, 0), "slots see the same remaining limit");
		// a win lowers the day's loss again
		c.dailyLossLimit = 1_000_000;
		Round win = ScratchGame.buy(id, name(a), ScratchTier.BRONZE, new Scripted(14)).round(); // ×5
		Casino.settle(win.id, null);
		Assert.eq(20_000L + 10_000 - 50_000, Casino.netLossToday(id), "win counts against the day's loss");
		ctx.detail("shared daily loss limit, rejection, wins");
	}

	private static void setupRefund(TestContext ctx) {
		setup(ctx);
		FakePlayer a = funded(ctx, "ScratchSetupA", 10_000_000);
		setTreasuryFree(100_000_000);
		UUID id = a.getUUID();
		long b0 = EconomyService.balance(id);
		long t0 = Treasury.balance();
		// a decision above the covered reservation must never stick: refund
		Casino.Start bad = Casino.startInstant(id, name(a), ScratchGame.GAME, 10_000, s -> 250_000, r -> 250_001);
		Assert.isFalse(bad.ok(), "payout above the reservation: start fails");
		Assert.eq(b0, EconomyService.balance(id), "stake refunded");
		Assert.eq(t0, Treasury.balance(), "Treasury unchanged");
		Assert.isTrue(Casino.openRound(id) == null, "no open round left");
		CasinoData.PlayerStats ps = Casino.peekStats(id);
		Assert.eq(0L, ps == null ? 0 : ps.rounds, "refund is not a round");
		Assert.eq(0L, Casino.netLossToday(id), "refund is no loss");
		// any exception in the setup: refund as well
		Casino.Start boom = Casino.startInstant(id, name(a), ScratchGame.GAME, 10_000, s -> 250_000, r -> {
			throw new IllegalStateException("test");
		});
		Assert.isFalse(boom.ok(), "setup exception: start fails");
		Assert.eq(b0, EconomyService.balance(id), "stake refunded again");
		// an undecided round of an instant game is refunded when settled (e.g. after a crash mid-setup)
		Round undecided = Casino.startExt(id, name(a), ScratchGame.GAME, 10_000).round();
		Assert.notNull(undecided, "undecided round started");
		Casino.Settled s = Casino.settle(undecided.id, Casino.RESTART_REASON);
		Assert.isTrue(s.refund(), "undecided → refund");
		Assert.eq(10_000L, s.payout(), "refund = stake");
		Assert.eq(b0, EconomyService.balance(id), "balance restored");
		Casino.InstantGame nope = new Casino.InstantGame("nope", "x", net.minecraft.world.item.Items.STONE, stake -> stake);
		Assert.assertThrows(IllegalArgumentException.class, () -> Casino.startInstant(id, name(a), nope, 10_000, r -> 0L), "unknown game id");
		Casino.InstantGame builtin = new Casino.InstantGame("slots", "x", net.minecraft.world.item.Items.STONE, stake -> stake);
		Assert.assertThrows(IllegalArgumentException.class, () -> Casino.registerGame(builtin), "built-in id");
		ctx.detail("bad decision / exception / undecided → stake back");
	}

	/** Many random cards of all tiers: player + Treasury constant, Treasury = start + prices − payouts. */
	private static void conservation(TestContext ctx) {
		CasinoConfig c = setup(ctx);
		c.dailyLossLimit = Money.MAX;
		FakePlayer a = funded(ctx, "ScratchCons", 1_000_000_000L);
		setTreasuryFree(1_000_000_000L);
		UUID id = a.getUUID();
		long t0 = Treasury.balance();
		long p0 = EconomyService.balance(id);
		long reserved0 = Casino.reservedTotal(null);
		SplittableRandom rng = new SplittableRandom(1337);
		long staked = 0;
		long paid = 0;
		int wins = 0;
		for (int i = 0; i < 2_000; i++) {
			ScratchTier t = i % 10 == 9 ? ScratchTier.GOLD : i % 3 == 2 ? ScratchTier.SILVER : ScratchTier.BRONZE;
			Casino.Start s = ScratchGame.buy(id, name(a), t, rng);
			Assert.isTrue(s.ok(), "card " + i + " (" + t.id() + "): " + s.error());
			Round r = s.round();
			Assert.eq(p0 - staked + paid - t.price(), EconomyService.balance(id), "price collected (" + t.id() + ")");
			Assert.eq(ScratchMath.payout(t.price(), ScratchMath.evaluate(r.reels)), r.payout, "grid and payout agree");
			Casino.Settled st = Casino.settle(r.id, null);
			Assert.isTrue(st.paid(), "payout booked");
			staked += t.price();
			paid += st.payout();
			if (st.payout() > t.price()) wins++;
			Assert.eq(t0 + p0, Treasury.balance() + EconomyService.balance(id), "player + Treasury constant after card " + i);
		}
		Assert.eq(t0 + staked - paid, Treasury.balance(), "Treasury = start + prices − payouts");
		Assert.eq(reserved0, Casino.reservedTotal(null), "no reservation left");
		Assert.isTrue(Casino.openRound(id) == null, "no open round");
		Assert.eq(2_000L, Casino.peekStats(id).games.get(ScratchGame.ID).rounds, "2,000 rounds in the statistics");
		ctx.detail("2,000 cards (" + wins + " wins), house " + (staked - paid >= 0 ? "+" : "") + Money.format(staked - paid)
			+ ", RTP " + fmt(paid / (double) staked));
	}

	/** The help text uses the configured currency symbol (no fixed "$" prices). */
	private static void helpCurrency(dev.smpeconomy.core.test.TestContext ctx) {
		String sym = dev.smpeconomy.core.Money.symbol();
		boolean after = dev.smpeconomy.core.Money.symbolAfter();
		ctx.cleanup(() -> dev.smpeconomy.core.Money.setCurrency(sym, after));
		dev.smpeconomy.core.Money.setCurrency("€", true);
		String all = String.join("\n", ScratchGame.help());
		dev.smpeconomy.core.test.Assert.isFalse(all.contains("$"), "no $ in the help text: " + all);
		dev.smpeconomy.core.test.Assert.isTrue(all.contains("100€"), "Bronze price with €: " + all);
	}
}
