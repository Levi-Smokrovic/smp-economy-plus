package dev.smpeconomy.features.bounty;

import static dev.smpeconomy.core.test.Assert.check;
import static dev.smpeconomy.core.test.Assert.eq;
import static dev.smpeconomy.core.test.Assert.isFalse;
import static dev.smpeconomy.core.test.Assert.isTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.server.level.ServerPlayer;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Hooks;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.Settings;
import dev.smpeconomy.core.Treasury;
import dev.smpeconomy.core.test.Assert;
import dev.smpeconomy.core.test.SelfTests;
import dev.smpeconomy.core.test.TestContext;
import dev.smpeconomy.core.test.TestPlayers;

/**
 * Most tests run on their own data with a test escrow account ({@link Fixture}). Only the hook and cleanup
 * tests use live data with test players, who are refunded and removed from history afterwards. Any test that
 * books a fee preserves the treasury first ({@code ctx.preserveTreasury()}).
 */
final class BountySelfTests {
	private static final String F = "bounty";
	private static final long D = 100; // $1 in cents

	private BountySelfTests() {}

	static void register() {
		SelfTests.register(F, "stake-escrow", BountySelfTests::placeEscrow);
		SelfTests.register(F, "invalid-stakes", BountySelfTests::invalidPlacements);
		SelfTests.register(F, "precheck-books-nothing", BountySelfTests::precheckBooksNothing);
		SelfTests.register(F, "multiple-contributors", BountySelfTests::multipleContributors);
		SelfTests.register(F, "payout", BountySelfTests::payout);
		SelfTests.register(F, "conservation", BountySelfTests::conservation);
		SelfTests.register(F, "conservation-random", BountySelfTests::conservationRandom);
		SelfTests.register(F, "suicide", BountySelfTests::selfKill);
		SelfTests.register(F, "payout-fails", BountySelfTests::payoutFailures);
		SelfTests.register(F, "refund", BountySelfTests::refunds);
		SelfTests.register(F, "fee-config", BountySelfTests::feeConfig);
		SelfTests.register(F, "persistence", BountySelfTests::persistence);
		SelfTests.register(F, "announcement-setting", BountySelfTests::announcements);
		SelfTests.register(F, "pvp-hook-live", BountySelfTests::liveHook);
		SelfTests.register(F, "rule-team-ip", BountySelfTests::teamIpRule);
		SelfTests.register(F, "ip-public-only", BountySelfTests::publicIpOnly);
		SelfTests.register(F, "escrow-mismatch-locks", BountySelfTests::escrowMismatchLocks);
		SelfTests.register(F, "start-check-lock", BountySelfTests::startupLock);
		SelfTests.register(F, "playtime-rule", BountySelfTests::playtimeRule);
		SelfTests.register(F, "target-message-throttled", BountySelfTests::targetNoticeThrottle);
		SelfTests.register(F, "test-players-cleanup", BountySelfTests::testCleanup);
		SelfTests.register(F, "escrow-live-consistent", BountySelfTests::liveConsistency);
	}

	/** Isolated bounty data and its own escrow account (test player account with $0, deleted afterwards). */
	private static final class Fixture {
		final BountyData data = new BountyData();
		final BountyConfig cfg = new BountyConfig();
		final UUID escrow;
		final BountyLedger ledger;
		long now = 1_000_000L;

		Fixture(TestContext ctx) {
			escrow = ctx.player("BountyEscrow").getUUID();
			EconomyService.set(escrow, 0);
			ledger = new BountyLedger(() -> data, () -> {}, escrow, () -> cfg);
		}

		BountyLedger.Placement place(FakePlayer from, FakePlayer to, long cents) {
			return ledger.place(from.getUUID(), to.getUUID(), cents, ++now);
		}

		BountyLedger.Payout kill(FakePlayer killer, FakePlayer victim) {
			return ledger.claim(killer.getUUID(), killer.getGameProfile().name(), victim.getUUID(), victim.getGameProfile().name(), ++now);
		}

		long escrowBalance() {
			return EconomyService.balance(escrow);
		}
	}

	private static FakePlayer funded(TestContext ctx, String name, long cents) {
		FakePlayer p = ctx.player(name);
		EconomyService.set(p.getUUID(), cents);
		return p;
	}

	private static long bal(ServerPlayer p) {
		return EconomyService.balance(p.getUUID());
	}

	/** Has enough playtime for any allowed minimum playtime rule. */
	private static FakePlayer veteran(FakePlayer p) {
		PlayerDataService.addPlaytime(p.getUUID(), BountyConfig.MAX_PLAYTIME_MINUTES * 60L * 20L);
		return p;
	}

	private static void placeEscrow(TestContext ctx) {
		Fixture f = new Fixture(ctx);
		FakePlayer a = funded(ctx, "BountyA", 1_000 * D);
		FakePlayer b = funded(ctx, "BountyB", 0);
		BountyLedger.Placement p = f.place(a, b, 500 * D);
		eq(BountyLedger.PlaceStatus.OK, p.status(), "place");
		eq(500 * D, p.amount(), "amount");
		eq(500 * D, p.total(), "bounty total");
		eq(500 * D, p.contribution(), "own contribution");
		eq(500 * D, bal(a), "placer charged");
		eq(500 * D, f.escrowBalance(), "escrow holds the money");
		eq(0, bal(b), "target gets nothing");
		eq(500 * D, f.ledger.total(b.getUUID()), "total()");
		eq(Map.of(a.getUUID(), 500 * D), f.ledger.contributions(b.getUUID()), "contributions");
		eq(f.escrowBalance(), f.ledger.totalEscrowed(), "escrow = total");
		ctx.detail("$500 in escrow");
	}

	private static void invalidPlacements(TestContext ctx) {
		Fixture f = new Fixture(ctx);
		FakePlayer a = funded(ctx, "BountyA", 1_000 * D);
		FakePlayer b = funded(ctx, "BountyB", 0);
		FakePlayer c = funded(ctx, "BountyC", 0);
		eq(BountyLedger.PlaceStatus.SELF, f.place(a, a, 200 * D).status(), "on self");
		eq(BountyLedger.PlaceStatus.INVALID_AMOUNT, f.place(a, b, 0).status(), "0");
		eq(BountyLedger.PlaceStatus.INVALID_AMOUNT, f.place(a, b, -500).status(), "negative");
		eq(BountyLedger.PlaceStatus.INVALID_AMOUNT, f.place(a, b, Money.MAX + 1).status(), "above MAX");
		eq(BountyLedger.PlaceStatus.BELOW_MIN, f.place(a, b, f.cfg.minAmount - 1).status(), "below minimum");
		eq(BountyLedger.PlaceStatus.INSUFFICIENT_FUNDS, f.place(a, b, 1_000 * D + 1).status(), "not enough money");
		eq(BountyLedger.PlaceStatus.INVALID_TARGET, f.ledger.place(a.getUUID(), f.escrow, 200 * D, 1).status(), "target is escrow");
		eq(BountyLedger.PlaceStatus.INVALID_TARGET, f.ledger.place(a.getUUID(), Treasury.ID, 200 * D, 1).status(), "target is treasury");
		eq(1_000 * D, bal(a), "nothing charged");
		eq(0, f.escrowBalance(), "escrow empty");
		isTrue(f.data.bounties.isEmpty(), "no bounty created");

		f.cfg.maxTotalPerTarget = 300 * D;
		eq(BountyLedger.PlaceStatus.OK, f.place(a, b, 200 * D).status(), "below limit");
		eq(BountyLedger.PlaceStatus.LIMIT_EXCEEDED, f.place(a, b, 200 * D).status(), "above limit");
		eq(BountyLedger.PlaceStatus.OK, f.place(a, b, 100 * D).status(), "exactly at limit");
		eq(300 * D, f.ledger.total(b.getUUID()), "limit held");
		eq(700 * D, bal(a), "only successful stakes charged");

		// Escrow balance cap (Money.MAX): the transfer fails atomically
		f.cfg.maxTotalPerTarget = 0;
		EconomyService.set(a.getUUID(), Money.MAX);
		eq(BountyLedger.PlaceStatus.LIMIT_EXCEEDED, f.place(a, c, Money.MAX).status(), "escrow would exceed MAX");
		eq(Money.MAX, bal(a), "nothing charged (balance cap)");
		eq(300 * D, f.escrowBalance(), "escrow unchanged");
		eq(0, f.ledger.total(c.getUUID()), "no bounty on C");
		ctx.detail("12 cases ok");
	}

	/**
	 * {@link BountyLedger#check} reports the same as {@link BountyLedger#place} but never charges, not even
	 * on OK, and does not lock on an escrow mismatch.
	 */
	private static void precheckBooksNothing(TestContext ctx) {
		Fixture f = new Fixture(ctx);
		FakePlayer a = funded(ctx, "BountyA", 1_000 * D);
		FakePlayer b = funded(ctx, "BountyB", 0);
		FakePlayer c = funded(ctx, "BountyC", 0);
		UUID ia = a.getUUID();
		UUID ib = b.getUUID();

		eq(BountyLedger.PlaceStatus.OK, f.ledger.check(ia, ib, 500 * D), "valid stake");
		eq(BountyLedger.PlaceStatus.OK, f.ledger.check(ia, ib, 1_000 * D), "whole balance");
		eq(1_000 * D, bal(a), "dry run charges nothing");
		eq(0, f.escrowBalance(), "escrow empty");
		isTrue(f.data.bounties.isEmpty(), "no bounty created");

		// Error cases: the dry run must match place, which charges nothing here either
		long[][] cases = {
			{0, 200 * D}, {1, 0}, {1, -500}, {1, Money.MAX + 1}, {1, f.cfg.minAmount - 1}, {1, 1_000 * D + 1}, {2, 200 * D}, {3, 200 * D}};
		String[] labels = {"on self", "0", "negative", "above MAX", "below minimum", "not enough money", "target is escrow", "target is treasury"};
		BountyLedger.PlaceStatus[] expected = {BountyLedger.PlaceStatus.SELF, BountyLedger.PlaceStatus.INVALID_AMOUNT,
			BountyLedger.PlaceStatus.INVALID_AMOUNT, BountyLedger.PlaceStatus.INVALID_AMOUNT, BountyLedger.PlaceStatus.BELOW_MIN,
			BountyLedger.PlaceStatus.INSUFFICIENT_FUNDS, BountyLedger.PlaceStatus.INVALID_TARGET, BountyLedger.PlaceStatus.INVALID_TARGET};
		UUID[] targets = {ia, ib, f.escrow, Treasury.ID};
		for (int i = 0; i < cases.length; i++) {
			UUID to = targets[(int) cases[i][0]];
			long cents = cases[i][1];
			eq(expected[i], f.ledger.check(ia, to, cents), "dry run " + labels[i]);
			eq(expected[i], f.ledger.place(ia, to, cents, ++f.now).status(), "place " + labels[i]);
		}
		eq(1_000 * D, bal(a), "nothing charged");
		eq(0, f.escrowBalance(), "escrow still empty");

		f.cfg.maxTotalPerTarget = 300 * D;
		check(f.place(a, b, 200 * D).status().ok(), "A→B 200");
		eq(BountyLedger.PlaceStatus.LIMIT_EXCEEDED, f.ledger.check(ia, ib, 200 * D), "dry run above limit");
		eq(BountyLedger.PlaceStatus.OK, f.ledger.check(ia, ib, 100 * D), "dry run exactly at limit");
		f.cfg.maxTotalPerTarget = 0;
		EconomyService.set(ia, Money.MAX);
		eq(BountyLedger.PlaceStatus.LIMIT_EXCEEDED, f.ledger.check(ia, c.getUUID(), Money.MAX), "dry run: escrow would exceed MAX");
		eq(BountyLedger.PlaceStatus.LIMIT_EXCEEDED, f.place(a, c, Money.MAX).status(), "place: escrow would exceed MAX");
		eq(Money.MAX, bal(a), "balance cap: nothing charged");
		eq(200 * D, f.escrowBalance(), "escrow = only the real stake");

		// Escrow mismatch: the dry run reports LOCKED but only place locks
		EconomyService.set(f.escrow, 200 * D + 1);
		eq(BountyLedger.PlaceStatus.LOCKED, f.ledger.check(ia, ib, 100 * D), "dry run on mismatch");
		isFalse(f.ledger.isLocked(), "dry run does not lock");
		eq(BountyLedger.PlaceStatus.LOCKED, f.place(a, b, 100 * D).status(), "place on mismatch");
		isTrue(f.ledger.isLocked(), "place locks");
		EconomyService.set(f.escrow, 200 * D);
		eq(BountyLedger.PlaceStatus.LOCKED, f.ledger.check(ia, ib, 100 * D), "locked until unlock");
		eq(BountyLedger.UnlockStatus.OK, f.ledger.unlock(), "admin unlocks");
		eq(BountyLedger.PlaceStatus.OK, f.ledger.check(ia, ib, 100 * D), "OK again afterwards");
		eq(200 * D, f.ledger.total(ib), "bounty unchanged");
		eq(200 * D, f.escrowBalance(), "escrow unchanged");
		ctx.detail(cases.length + 5 + " cases, dry run = place, nothing charged");
	}

	private static void multipleContributors(TestContext ctx) {
		Fixture f = new Fixture(ctx);
		FakePlayer a = funded(ctx, "BountyA", 1_000 * D);
		FakePlayer b = funded(ctx, "BountyB", 1_000 * D);
		FakePlayer c = funded(ctx, "BountyC", 1_000 * D);
		FakePlayer k = funded(ctx, "BountyK", 0);
		eq(BountyLedger.PlaceStatus.OK, f.place(a, b, 300 * D).status(), "A on B");
		BountyLedger.Placement second = f.place(c, b, 200 * D);
		eq(500 * D, second.total(), "stacked");
		eq(200 * D, second.contribution(), "contribution C");
		BountyLedger.Placement third = f.place(a, b, 100 * D);
		eq(600 * D, third.total(), "A raises");
		eq(400 * D, third.contribution(), "contribution A summed");
		Map<UUID, Long> contrib = f.ledger.contributions(b.getUUID());
		eq(List.of(a.getUUID(), c.getUUID()), new ArrayList<>(contrib.keySet()), "contributor order");
		eq(400 * D, (long) contrib.get(a.getUUID()), "A");
		eq(200 * D, (long) contrib.get(c.getUUID()), "C");
		eq(600 * D, f.escrowBalance(), "escrow");

		f.place(c, a, 600 * D); // Same as B, but later
		f.place(b, k, 100 * D);
		List<BountyLedger.Entry> top = f.ledger.top();
		eq(3, top.size(), "3 bounties");
		eq(a.getUUID(), top.get(0).target(), "tie: most recently raised first");
		eq(b.getUUID(), top.get(1).target(), "then B");
		eq(k.getUUID(), top.get(2).target(), "smallest last");
		eq(2, top.get(1).contributors(), "B has 2 contributors");
		eq(a.getUUID(), top.get(1).topContributor(), "largest contribution on B");
		eq(400 * D, top.get(1).topAmount(), "largest contribution amount");
		Map<UUID, Long> byC = f.ledger.contributedBy(c.getUUID());
		eq(List.of(a.getUUID(), b.getUUID()), new ArrayList<>(byC.keySet()), "contributions from C, largest first");

		ctx.preserveTreasury();
		BountyLedger.Payout p = f.kill(k, b);
		eq(BountyLedger.ClaimStatus.PAID, p.status(), "paid out");
		eq(600 * D, p.total(), "all contributions");
		eq(Map.of(a.getUUID(), 400 * D, c.getUUID(), 200 * D), p.contributions(), "contributors in the payout");
		eq(570 * D, bal(k), "600 minus 5 %");
		ctx.detail("2 contributors, leaderboard ok");
	}

	private static void payout(TestContext ctx) {
		ctx.preserveTreasury();
		Fixture f = new Fixture(ctx);
		FakePlayer a = funded(ctx, "BountyA", 1_000 * D);
		FakePlayer b = funded(ctx, "BountyB", 50 * D);
		FakePlayer k = funded(ctx, "BountyK", 10 * D);
		long treasury = Treasury.balance();
		long feeIncome = Treasury.totalsAllTime().getOrDefault(Treasury.R_BOUNTY_FEE, Treasury.Flow.ZERO).income();
		f.place(a, b, 1_000 * D);
		BountyLedger.Payout p = f.kill(k, b);
		eq(BountyLedger.ClaimStatus.PAID, p.status(), "paid out");
		eq(1_000 * D, p.total(), "bounty");
		eq(50 * D, p.fee(), "5 % fee");
		eq(950 * D, p.net(), "net");
		eq(960 * D, bal(k), "killer gets net");
		eq(50 * D, bal(b), "victim unchanged");
		eq(0, bal(a), "placer unchanged");
		eq(treasury + 50 * D, Treasury.balance(), "fee in the treasury");
		eq(feeIncome + 50 * D, Treasury.totalsAllTime().get(Treasury.R_BOUNTY_FEE).income(), "reason bounty_fee booked");
		eq(0, f.escrowBalance(), "escrow empty");
		eq(0, f.ledger.total(b.getUUID()), "bounty deleted");
		eq(1, f.ledger.history().size(), "history");
		eq("BountyK", f.ledger.history().get(0).killerName, "history killer");
		eq("BountyB", f.ledger.history().get(0).victimName, "history victim");
		// Exactly once: a second kill pays nothing
		eq(BountyLedger.ClaimStatus.NONE, f.kill(k, b).status(), "second kill");
		eq(960 * D, bal(k), "no second payout");
		eq(treasury + 50 * D, Treasury.balance(), "no second fee");
		eq(BountyLedger.ClaimStatus.NONE, f.kill(b, k).status(), "no bounty");
		ctx.detail("$1,000 → $950 + $50 fee, exactly once");
	}

	private static void conservation(TestContext ctx) {
		ctx.preserveTreasury();
		Fixture f = new Fixture(ctx);
		FakePlayer a = funded(ctx, "BountyA", 123_456);
		FakePlayer b = funded(ctx, "BountyB", 98_765);
		FakePlayer c = funded(ctx, "BountyC", 55_555);
		FakePlayer k = funded(ctx, "BountyK", 1);
		FakePlayer[] all = {a, b, c, k};
		long before = sum(f, all);
		check(f.place(a, b, 33_333).status().ok(), "A→B");
		eq(before, sum(f, all), "after A→B");
		check(f.place(c, b, 12_345).status().ok(), "C→B");
		check(f.place(a, k, 10_001).status().ok(), "A→K");
		eq(before, sum(f, all), "after stakes");
		BountyLedger.Payout p1 = f.kill(k, b);
		eq(45_678, p1.total(), "bounty B");
		eq(2_283, p1.fee(), "fee rounded down (2283.9)");
		eq(43_395, p1.net(), "net");
		eq(before, sum(f, all), "after payout 1");
		BountyLedger.Payout p2 = f.kill(b, k);
		eq(500, p2.fee(), "fee 500.05 → 500");
		eq(9_501, p2.net(), "net 2");
		eq(before, sum(f, all), "after payout 2");
		eq(0, f.escrowBalance(), "escrow empty");
		eq(0, f.ledger.totalEscrowed(), "no bounties");
		ctx.detail("total constant: " + Money.format(before));
	}

	/**
	 * 400 random placements, kills and refunds: total money including the treasury stays constant, escrow
	 * equals the bounty total.
	 */
	private static void conservationRandom(TestContext ctx) {
		ctx.preserveTreasury();
		Fixture f = new Fixture(ctx);
		FakePlayer[] all = {funded(ctx, "BountyA", 50_000 * D), funded(ctx, "BountyB", 20_000 * D), funded(ctx, "BountyC", 5_000 * D),
			funded(ctx, "BountyK", 333 * D)};
		long before = sum(f, all);
		Random rnd = new Random(12_345);
		int placed = 0;
		int paid = 0;
		for (int i = 0; i < 400; i++) {
			FakePlayer x = all[rnd.nextInt(all.length)];
			FakePlayer y = all[rnd.nextInt(all.length)];
			int op = rnd.nextInt(10);
			if (op < 6) {
				long amount = f.cfg.minAmount - 50 + rnd.nextInt(5_000) * 7L;
				if (f.place(x, y, amount).status().ok()) placed++;
			} else if (op < 9) {
				if (f.kill(x, y).status().paid()) paid++;
			} else {
				f.ledger.refundContributor(x.getUUID());
			}
			if (sum(f, all) != before) Assert.fail("total changed after step " + i + " (op " + op + ")");
			if (f.escrowBalance() != f.ledger.totalEscrowed()) Assert.fail("escrow ≠ bounty total after step " + i);
		}
		check(placed > 50 && paid > 10, "enough cases (placed=" + placed + ", paid=" + paid + ")");
		ctx.detail(placed + " stakes, " + paid + " payouts, total constant");
	}

	private static long sum(Fixture f, FakePlayer... players) {
		long s = f.escrowBalance() + Treasury.balance();
		for (FakePlayer p : players) s += bal(p);
		return s;
	}

	private static void selfKill(TestContext ctx) {
		Fixture f = new Fixture(ctx);
		FakePlayer a = funded(ctx, "BountyA", 1_000 * D);
		FakePlayer b = funded(ctx, "BountyB", 0);
		f.place(a, b, 400 * D);
		eq(BountyLedger.ClaimStatus.SELF, f.kill(b, b).status(), "suicide");
		eq(BountyLedger.ClaimStatus.NONE, f.ledger.claim(null, "", b.getUUID(), "BountyB", 1).status(), "no killer (environment)");
		eq(400 * D, f.ledger.total(b.getUUID()), "bounty stays");
		eq(0, bal(b), "no money to the victim");
		eq(400 * D, f.escrowBalance(), "escrow unchanged");
		isTrue(f.ledger.history().isEmpty(), "no history");

		// Also through the real hook (live data): suicide pays nothing
		ctx.preserveTreasury();
		long escrowBefore = Bounties.LIVE.escrowBalance();
		check(Bounties.LIVE.place(a.getUUID(), b.getUUID(), 200 * D, System.currentTimeMillis()).status().ok(), "live stake");
		Hooks.firePlayerKilledPlayer(b, b);
		eq(200 * D, Bounties.LIVE.total(b.getUUID()), "live bounty stays after suicide");
		eq(0, bal(b), "victim gets nothing");
		eq(escrowBefore + 200 * D, Bounties.LIVE.escrowBalance(), "live escrow holds the stake");
		ctx.cleanup(() -> {
			Bounties.LIVE.refundAll(b.getUUID());
			if (Bounties.LIVE.escrowBalance() != escrowBefore) throw new IllegalStateException("live escrow not restored");
		});
		ctx.detail("suicide and environment pay nothing");
	}

	private static void payoutFailures(TestContext ctx) {
		ctx.preserveTreasury();
		Fixture f = new Fixture(ctx);
		FakePlayer a = funded(ctx, "BountyA", 1_000 * D);
		FakePlayer b = funded(ctx, "BountyB", 0);
		FakePlayer k = funded(ctx, "BountyK", 0);
		long treasury = Treasury.balance();
		f.place(a, b, 500 * D);

		// Escrow holds less than the bounty (inconsistent data): pay nothing, create nothing, lock
		EconomyService.set(f.escrow, 400 * D);
		eq(BountyLedger.ClaimStatus.LOCKED, f.kill(k, b).status(), "escrow too empty");
		eq(0, bal(k), "nothing paid");
		eq(400 * D, f.escrowBalance(), "escrow unchanged");
		eq(500 * D, f.ledger.total(b.getUUID()), "bounty stays");
		EconomyService.set(f.escrow, 500 * D);
		eq(BountyLedger.UnlockStatus.OK, f.ledger.unlock(), "unlocked after repair");

		// Killer without account: the transfer fails atomically
		UUID ghost = TestPlayers.uuid("BountyGhost");
		ctx.cleanup(() -> EconomyService.deleteAccount(ghost));
		EconomyService.deleteAccount(ghost);
		eq(BountyLedger.ClaimStatus.PAYOUT_FAILED, f.ledger.claim(ghost, "BountyGhost", b.getUUID(), "BountyB", 5).status(), "no account");
		isFalse(EconomyService.hasAccount(ghost), "no account created");

		// Killer at balance cap
		EconomyService.set(k.getUUID(), Money.MAX);
		eq(BountyLedger.ClaimStatus.PAYOUT_FAILED, f.kill(k, b).status(), "balance cap");
		eq(Money.MAX, bal(k), "killer unchanged");
		eq(500 * D, f.escrowBalance(), "escrow unchanged");
		eq(500 * D, f.ledger.total(b.getUUID()), "bounty stays");
		eq(treasury, Treasury.balance(), "no fee booked");

		EconomyService.set(k.getUUID(), 0);
		eq(BountyLedger.ClaimStatus.PAID, f.kill(k, b).status(), "paid now");
		eq(475 * D, bal(k), "net");
		ctx.detail("shortfall, no account, balance cap: nothing paid");
	}

	private static void refunds(TestContext ctx) {
		Fixture f = new Fixture(ctx);
		FakePlayer a = funded(ctx, "BountyA", 1_000 * D);
		FakePlayer b = funded(ctx, "BountyB", 1_000 * D);
		FakePlayer c = funded(ctx, "BountyC", 1_000 * D);
		f.place(a, b, 300 * D);
		f.place(c, b, 200 * D);
		Map<UUID, Long> refunded = f.ledger.refundAll(b.getUUID());
		eq(Map.of(a.getUUID(), 300 * D, c.getUUID(), 200 * D), refunded, "refunded");
		eq(1_000 * D, bal(a), "A refunded");
		eq(1_000 * D, bal(c), "C refunded");
		eq(0, f.escrowBalance(), "escrow empty");
		eq(0, f.ledger.total(b.getUUID()), "bounty gone");
		isTrue(f.ledger.refundAll(b.getUUID()).isEmpty(), "harmless twice");

		f.place(a, b, 100 * D);
		f.place(a, c, 100 * D);
		f.place(c, b, 100 * D);
		eq(200 * D, f.ledger.refundContributor(a.getUUID()), "all contributions from A");
		eq(Map.of(c.getUUID(), 100 * D), f.ledger.contributions(b.getUUID()), "only C remains on B");
		eq(0, f.ledger.total(c.getUUID()), "bounty on C (only A) deleted");
		eq(100 * D, f.escrowBalance(), "escrow = remainder");

		// Contributor without account: the contribution stays, no money lost or created
		FakePlayer g = funded(ctx, "BountyGone", 500 * D);
		f.place(g, b, 200 * D);
		EconomyService.deleteAccount(g.getUUID());
		Map<UUID, Long> partial = f.ledger.refundAll(b.getUUID());
		eq(Map.of(c.getUUID(), 100 * D), partial, "only C refunded");
		eq(Map.of(g.getUUID(), 200 * D), f.ledger.contributions(b.getUUID()), "contribution without account stays");
		eq(200 * D, f.escrowBalance(), "escrow still holds it");
		ctx.detail("refundAll/refundContributor ok");
	}

	private static void feeConfig(TestContext ctx) {
		ctx.preserveTreasury();
		Fixture f = new Fixture(ctx);
		FakePlayer a = funded(ctx, "BountyA", 1_000 * D);
		FakePlayer b = funded(ctx, "BountyB", 0);
		FakePlayer k = funded(ctx, "BountyK", 0);
		long treasury = Treasury.balance();
		f.cfg.feePercent = 0;
		f.place(a, b, 300 * D);
		BountyLedger.Payout p = f.kill(k, b);
		eq(0, p.fee(), "0 % fee");
		eq(300 * D, bal(k), "all to the killer");
		eq(treasury, Treasury.balance(), "treasury unchanged");
		f.cfg.feePercent = 50;
		f.place(a, b, 300 * D);
		eq(150 * D, f.kill(k, b).fee(), "50 % fee");
		eq(treasury + 150 * D, Treasury.balance(), "treasury +50 %");

		BountyConfig def = new BountyConfig();
		def.validate();
		eq(5.0, def.feePercent, "Default 5 %");
		for (double bad : new double[] {-1, 50.01, Double.NaN, Double.POSITIVE_INFINITY}) {
			BountyConfig c = new BountyConfig();
			c.feePercent = bad;
			Assert.assertThrows(IllegalArgumentException.class, c::validate, "feePercent " + bad);
		}
		BountyConfig c1 = new BountyConfig();
		c1.minAmount = 0;
		Assert.assertThrows(IllegalArgumentException.class, c1::validate, "minAmount 0");
		BountyConfig c2 = new BountyConfig();
		c2.maxTotalPerTarget = c2.minAmount - 1;
		Assert.assertThrows(IllegalArgumentException.class, c2::validate, "limit < minimum");
		ctx.detail("fee 0/5/50 %, validation ok");
	}

	private static void persistence(TestContext ctx) {
		Fixture f = new Fixture(ctx);
		FakePlayer a = funded(ctx, "BountyA", 1_000 * D);
		FakePlayer b = funded(ctx, "BountyB", 1_000 * D);
		FakePlayer c = funded(ctx, "BountyC", 1_000 * D);
		f.place(c, b, 150 * D);
		f.place(a, b, 250 * D);
		f.place(b, a, 100 * D);
		ctx.preserveTreasury();
		f.kill(c, a);
		String json = DataStore.gson().toJson(f.data);
		BountyData copy = DataStore.gson().fromJson(json, BountyData.class);
		copy.fixNulls("selftest");
		eq(1, copy.bounties.size(), "1 bounty");
		eq(400 * D, copy.bounties.get(b.getUUID()).total(), "total");
		eq(List.of(c.getUUID(), a.getUUID()), new ArrayList<>(copy.bounties.get(b.getUUID()).contributions.keySet()), "order");
		eq(1, copy.history.size(), "history");
		eq(a.getUUID(), copy.history.get(0).victim, "history victim");
		copy.bounties.get(b.getUUID()).contributions.put(c.getUUID(), 1L); // Still mutable
		BountyLedger reloaded = new BountyLedger(() -> copy, () -> {}, f.escrow, () -> f.cfg);
		eq(250 * D + 1, reloaded.total(b.getUUID()), "loaded data usable");

		// Repair drops invalid contributions and empty bounties
		String bad = "{\"bounties\":{\"" + a.getUUID() + "\":{\"contributions\":{\"" + b.getUUID() + "\":-5,\"" + c.getUUID() + "\":0}},"
			+ "\"" + b.getUUID() + "\":{\"contributions\":{\"" + a.getUUID() + "\":700}},\"" + c.getUUID() + "\":null},\"history\":null}";
		BountyData broken = DataStore.gson().fromJson(bad, BountyData.class);
		broken.fixNulls("selftest (deliberately broken)");
		eq(1, broken.bounties.size(), "only valid bounty stays");
		eq(700L, broken.bounties.get(b.getUUID()).total(), "valid contribution");
		isTrue(broken.history != null && broken.history.isEmpty(), "history repaired");
		BountyData empty = DataStore.gson().fromJson("{}", BountyData.class);
		empty.fixNulls("selftest");
		isTrue(empty.bounties.isEmpty() && empty.history.isEmpty(), "empty file");
		ctx.detail("Gson round trip + repair ok");
	}

	private static void announcements(TestContext ctx) {
		FakePlayer a = ctx.player("BountyA");
		FakePlayer b = ctx.player("BountyB");
		FakePlayer c = ctx.player("BountyC");
		Settings.set(b.getUUID(), Settings.ANNOUNCEMENTS, false);
		List<ServerPlayer> got = BountyNotify.recipients(List.<ServerPlayer>of(a, b, c), java.util.Set.of(c.getUUID()));
		eq(List.of(a.getUUID()), got.stream().map(ServerPlayer::getUUID).toList(), "only A (B has announcements off, C is involved)");
		Settings.set(b.getUUID(), Settings.ANNOUNCEMENTS, true);
		eq(2, BountyNotify.recipients(List.<ServerPlayer>of(a, b, c), java.util.Set.of(c.getUUID())).size(), "B on again");

		BountyConfig cfg = new BountyConfig();
		UUID x = UUID.randomUUID();
		UUID y = UUID.randomUUID();
		long t = 5_000_000L;
		isTrue(BountyNotify.shouldAnnouncePlacement(cfg, x, y, cfg.announceMinAmount, t), "first contribution announced");
		isFalse(BountyNotify.shouldAnnouncePlacement(cfg, x, y, cfg.announceMinAmount, t + 1_000), "not within the cooldown");
		isTrue(BountyNotify.shouldAnnouncePlacement(cfg, x, y, cfg.announceMinAmount, t + cfg.announceCooldownSeconds * 1000L), "again after the cooldown");
		isFalse(BountyNotify.shouldAnnouncePlacement(cfg, x, UUID.randomUUID(), cfg.announceMinAmount - 1, t), "not below minimum");
		isFalse(BountyNotify.shouldAnnouncePlacement(cfg, a.getUUID(), UUID.randomUUID(), cfg.announceMinAmount, t), "test players never");
		cfg.announce = false;
		isFalse(BountyNotify.shouldAnnouncePlacement(cfg, x, UUID.randomUUID(), cfg.announceMinAmount, t), "announcements off");
		ctx.detail("settings filter + spam guard ok");
	}

	/** Live data through the real PvP hook. */
	/**
	 * Live tests pay out through the real ledger, so restore the recent hunts list afterwards. Test entries
	 * would otherwise displace real ones that cleanup cannot bring back.
	 */
	private static void preserveHistory(TestContext ctx) {
		List<BountyData.Claim> saved = new ArrayList<>(Bounties.STORE.get().history);
		ctx.cleanup(() -> {
			Bounties.STORE.get().history = new ArrayList<>(saved);
			Bounties.STORE.markDirty();
		});
	}

	private static void liveHook(TestContext ctx) {
		preserveHistory(ctx);
		ctx.preserveTreasury();
		FakePlayer a = funded(ctx, "BountyA", 1_000 * D);
		FakePlayer b = funded(ctx, "BountyB", 0);
		FakePlayer c = funded(ctx, "BountyC", 1_000 * D);
		FakePlayer k = veteran(funded(ctx, "BountyK", 0));
		long escrowBefore = Bounties.LIVE.escrowBalance();
		long treasury = Treasury.balance();
		long now = System.currentTimeMillis();
		check(Bounties.LIVE.place(a.getUUID(), b.getUUID(), 300 * D, now).status().ok(), "A places live");
		check(Bounties.LIVE.place(c.getUUID(), b.getUUID(), 100 * D, now).status().ok(), "C places live");
		eq(escrowBefore + 400 * D, Bounties.LIVE.escrowBalance(), "live escrow");
		Hooks.firePlayerKilledPlayer(k, b);
		eq(380 * D, bal(k), "killer gets 400 − 5 %");
		eq(treasury + 20 * D, Treasury.balance(), "fee");
		eq(0, Bounties.LIVE.total(b.getUUID()), "bounty deleted");
		eq(escrowBefore, Bounties.LIVE.escrowBalance(), "live escrow back to before");
		if (Bounties.config().historySize > 0) eq(b.getUUID(), Bounties.LIVE.history().get(0).victim, "history entry");
		Hooks.firePlayerKilledPlayer(k, b);
		eq(380 * D, bal(k), "second kill pays nothing");
		ctx.detail("hook pays $380 + $20 fee");
	}

	/** Pure decision plus the real hook with a scoreboard team: the bounty stays and the killer is told. */
	private static void teamIpRule(TestContext ctx) {
		preserveHistory(ctx);
		BountyConfig cfg = new BountyConfig();
		long vet = cfg.minKillerPlaytimeTicks();
		eq(null, BountyFeature.blockReason(false, "1.2.3.4", "5.6.7.8", vet, cfg), "different IPs, no team");
		check(BountyFeature.blockReason(true, "1.2.3.4", "5.6.7.8", vet, cfg) != null, "same team blocks");
		check(BountyFeature.blockReason(false, "1.2.3.4", "1.2.3.4", vet, cfg) != null, "same IP blocks");
		eq(null, BountyFeature.blockReason(false, null, null, vet, cfg), "unknown IPs never block");
		eq(null, BountyFeature.blockReason(false, "1.2.3.4", null, vet, cfg), "one IP unknown");
		cfg.blockSameIp = false;
		eq(null, BountyFeature.blockReason(false, "1.2.3.4", "1.2.3.4", vet, cfg), "IP rule off");
		cfg.blockSameTeam = false;
		eq(null, BountyFeature.blockReason(true, null, null, vet, cfg), "team rule off");

		ctx.preserveTreasury();
		FakePlayer a = funded(ctx, "BountyA", 1_000 * D);
		FakePlayer b = funded(ctx, "BountyB", 0);
		FakePlayer k = veteran(funded(ctx, "BountyK", 0));
		isTrue(BountyFeature.ipOf(k) == null, "FakePlayer has no IP");
		BountyConfig live = Bounties.config();
		boolean teamRule = live.blockSameTeam;
		live.blockSameTeam = true;
		ctx.cleanup(() -> live.blockSameTeam = teamRule);
		var scoreboard = ctx.server().getScoreboard();
		String teamName = "tri_bounty_selftest";
		net.minecraft.world.scores.PlayerTeam stale = scoreboard.getPlayerTeam(teamName);
		if (stale != null) scoreboard.removePlayerTeam(stale);
		net.minecraft.world.scores.PlayerTeam team = scoreboard.addPlayerTeam(teamName);
		ctx.cleanup(() -> {
			net.minecraft.world.scores.PlayerTeam t = scoreboard.getPlayerTeam(teamName);
			if (t != null) scoreboard.removePlayerTeam(t);
		});
		scoreboard.addPlayerToTeam(k.getScoreboardName(), team);
		scoreboard.addPlayerToTeam(b.getScoreboardName(), team);
		isTrue(BountyFeature.sameTeam(k, b), "killer and victim in the same team");
		isFalse(BountyFeature.sameTeam(k, a), "A in no team");
		check(Bounties.LIVE.place(a.getUUID(), b.getUUID(), 200 * D, System.currentTimeMillis()).status().ok(), "live stake");
		Hooks.firePlayerKilledPlayer(k, b);
		eq(200 * D, Bounties.LIVE.total(b.getUUID()), "team kill: bounty stays");
		eq(0, bal(k), "team kill: no payout");
		isTrue(dev.smpeconomy.core.PlayerDataService.getPendingMessages(k.getUUID()).stream().anyMatch(m -> m.contains("same team")),
			"killer learns the reason");
		scoreboard.removePlayerFromTeam(k.getScoreboardName(), team);
		Hooks.firePlayerKilledPlayer(k, b);
		eq(190 * D, bal(k), "no team: payout");
		eq(0, Bounties.LIVE.total(b.getUUID()), "bounty deleted");
		ctx.detail("team and IP rule ok");
	}

	/**
	 * Tunnel, Docker and LAN addresses never count as the same IP. Behind a tunnel agent all players share
	 * one address, and with the default config nothing would ever pay out.
	 */
	private static void publicIpOnly(TestContext ctx) {
		BountyConfig cfg = new BountyConfig();
		isTrue(cfg.blockSameIp, "default: IP rule on");
		String[] internal = {"127.0.0.1", "127.8.9.10", "0.0.0.0", "172.18.0.2", "172.17.0.1", "10.0.0.5", "192.168.1.50", "169.254.3.4",
			"100.64.0.1", "100.127.255.254", "255.255.255.255", "224.0.0.1", "::1", "::", "fe80::1", "fd00::1", "fc12:3456::7",
			"::ffff:127.0.0.1", "::ffff:172.18.0.2"};
		for (String ip : internal) {
			String n = BountyFeature.publicIp(ip);
			eq(null, n, "not public: " + ip);
			eq(null, BountyFeature.blockReason(false, n, n, cfg.minKillerPlaytimeTicks(), cfg), "same internal IP does not block: " + ip);
		}
		for (String bad : new String[] {null, "", "  ", "<unknown>", "local:E:1234", "example.com", "1.2.3"}) {
			eq(null, BountyFeature.publicIp(bad), "no IP: " + bad);
		}
		String[] pub = {"9.9.9.9", "8.8.8.8", "100.63.255.255", "100.128.0.1", "172.32.0.1", "2001:4860:4860::8888"};
		for (String ip : pub) {
			String n = BountyFeature.publicIp(ip);
			eq(ip, n, "public: " + ip);
			check(BountyFeature.blockReason(false, n, n, cfg.minKillerPlaytimeTicks(), cfg) != null, "same public IP blocks: " + ip);
		}
		eq("9.9.9.9", BountyFeature.publicIp(" ::ffff:9.9.9.9 "), "IPv4-mapped → IPv4");
		long vet = cfg.minKillerPlaytimeTicks();
		eq(null, BountyFeature.blockReason(false, BountyFeature.publicIp("9.9.9.9"), BountyFeature.publicIp("9.9.9.10"), vet, cfg),
			"different public IPs");
		eq(null, BountyFeature.blockReason(false, BountyFeature.publicIp("172.18.0.2"), BountyFeature.publicIp("9.9.9.9"), vet, cfg),
			"one internal, one public IP");
		ctx.detail(internal.length + " internal addresses never block, " + pub.length + " public ones do");
	}

	/**
	 * Any escrow mismatch locks everything until an admin repairs and unlocks. Part 1: shortfall (crash after
	 * a payout). Part 2: bounties.json from backup, where a paid bounty is back and a newer one is missing,
	 * whose stake hides the shortfall as surplus. Without the lock the stale bounty would pay a second time
	 * with another contributor's money.
	 */
	private static void escrowMismatchLocks(TestContext ctx) {
		ctx.preserveTreasury();
		Fixture f = new Fixture(ctx);
		FakePlayer a = funded(ctx, "BountyA", 1_000 * D);
		FakePlayer b = funded(ctx, "BountyB", 1_000 * D);
		FakePlayer c = funded(ctx, "BountyC", 1_000 * D);
		FakePlayer w = funded(ctx, "BountyW", 0);
		FakePlayer k = funded(ctx, "BountyK", 0);
		long treasury = Treasury.balance();

		// Part 1: shortfall
		check(f.place(a, b, 300 * D).status().ok(), "A→B");
		check(f.place(c, a, 500 * D).status().ok(), "C→A");
		// B was already paid and economy.json saved (escrow -300), but bounties.json was not, so B is back
		EconomyService.set(f.escrow, 500 * D);
		eq(-300 * D, f.ledger.escrowDifference(), "mismatch = stale bounty");
		eq(300 * D, f.ledger.escrowDeficit(), "shortfall");
		eq(BountyLedger.ClaimStatus.LOCKED, f.kill(k, b).status(), "stale bounty does not pay from C's money");
		isTrue(f.ledger.isLocked(), "locked");
		check(f.ledger.lockReason().contains("deficit"), "reason: " + f.ledger.lockReason());
		eq(BountyLedger.ClaimStatus.LOCKED, f.kill(k, a).status(), "valid bounties locked too");
		isTrue(f.ledger.refundAll(b.getUUID()).isEmpty(), "refund on B locked");
		eq(0, f.ledger.refundContributor(c.getUUID()), "refund to C locked");
		eq(BountyLedger.PlaceStatus.LOCKED, f.place(c, b, 100 * D).status(), "contribution locked");
		eq(0, bal(k), "nothing paid");
		eq(500 * D, bal(c), "C unchanged");
		eq(500 * D, f.escrowBalance(), "escrow unchanged");
		eq(800 * D, f.ledger.totalEscrowed(), "bounties unchanged");
		eq(BountyLedger.UnlockStatus.MISMATCH, f.ledger.unlock(), "unlock only after repair");

		// Repair: drop the stale bounty without refund. The totals match again, but it stays locked until
		// unlock
		eq(Map.of(a.getUUID(), 300 * D), f.ledger.drop(b.getUUID()), "drop returns the deleted contributions");
		isTrue(f.ledger.drop(b.getUUID()).isEmpty(), "drop harmless twice");
		eq(700 * D, bal(a), "A gets nothing back (already paid out)");
		eq(500 * D, f.escrowBalance(), "drop moves no money");
		eq(0, f.ledger.escrowDifference(), "matches again");
		isTrue(f.ledger.isLocked(), "drop alone does not unlock");
		eq(BountyLedger.ClaimStatus.LOCKED, f.kill(k, a).status(), "locked until unlock");
		eq(BountyLedger.UnlockStatus.OK, f.ledger.unlock(), "admin unlocks");
		eq(BountyLedger.UnlockStatus.NOT_LOCKED, f.ledger.unlock(), "harmless twice");
		BountyLedger.Payout p = f.kill(k, a);
		eq(BountyLedger.ClaimStatus.PAID, p.status(), "paid again afterwards");
		eq(475 * D, bal(k), "net $500 − 5 %");
		eq(0, f.escrowBalance(), "escrow empty");

		// Part 2: surplus hides a stale bounty (backup restore)
		EconomyService.set(a.getUUID(), 1_000 * D);
		EconomyService.set(c.getUUID(), 5_000 * D);
		check(f.place(a, b, 1_000 * D).status().ok(), "A places $1,000 on V (= B)");
		BountyData backup = DataStore.gson().fromJson(DataStore.gson().toJson(f.data), BountyData.class); // = bounties.json.bak
		eq(BountyLedger.ClaimStatus.PAID, f.kill(k, b).status(), "V paid out");
		check(f.place(c, w, 5_000 * D).status().ok(), "C places $5,000 on W");
		long kBefore = bal(k);
		// bounties.json broken, loaded from .bak (V back, W missing), economy.json current
		backup.fixNulls("selftest");
		f.data.bounties.clear();
		f.data.bounties.putAll(backup.bounties);
		eq(4_000 * D, f.ledger.escrowSurplus(), "surplus $5,000 − $1,000");
		eq(0, f.ledger.escrowDeficit(), "no shortfall, so a shortfall-only check notices nothing");
		eq(BountyLedger.ClaimStatus.LOCKED, f.kill(k, b).status(), "stale bounty is not paid a second time");
		eq(kBefore, bal(k), "killer gets nothing");
		eq(5_000 * D, f.escrowBalance(), "C's money stays in escrow");
		check(f.ledger.lockReason().contains("surplus"), "reason: " + f.ledger.lockReason());
		eq(BountyLedger.PlaceStatus.LOCKED, f.place(a, b, 100 * D).status(), "contribution locked");
		isTrue(f.ledger.refundAll(b.getUUID()).isEmpty(), "refund locked");
		eq(BountyLedger.UnlockStatus.MISMATCH, f.ledger.unlock(), "cannot be unlocked");

		// Repair: drop V (claimed according to history), refund C's lost stake, unlock
		eq(Map.of(a.getUUID(), 1_000 * D), f.ledger.drop(b.getUUID()), "V deleted");
		eq(EconomyService.Result.INSUFFICIENT_FUNDS, f.ledger.releaseSurplus(c.getUUID(), 5_000 * D + 1), "never more than the surplus");
		eq(EconomyService.Result.INVALID_AMOUNT, f.ledger.releaseSurplus(c.getUUID(), 0), "amount 0");
		eq(EconomyService.Result.OK, f.ledger.releaseSurplus(c.getUUID(), 5_000 * D), "surplus to C");
		eq(5_000 * D, bal(c), "C has the lost stake back");
		eq(0, f.escrowBalance(), "escrow empty");
		eq(EconomyService.Result.INSUFFICIENT_FUNDS, f.ledger.releaseSurplus(c.getUUID(), 1), "no surplus left");
		eq(BountyLedger.UnlockStatus.OK, f.ledger.unlock(), "unlocked");
		check(f.place(c, b, 100 * D).status().ok(), "clear again afterwards");

		// Surplus goes to the treasury (no owner to assign it to)
		long t = Treasury.balance();
		EconomyService.set(f.escrow, f.escrowBalance() + 10 * D);
		eq(EconomyService.Result.OK, f.ledger.releaseSurplus(Treasury.ID, 10 * D), "surplus to the treasury");
		eq(t + 10 * D, Treasury.balance(), "treasury +$10");
		eq(0, f.ledger.escrowDifference(), "matches");
		eq(treasury + 25 * D + 50 * D + 10 * D, Treasury.balance(), "only real fees (+ surplus) in the treasury");
		ctx.detail("shortfall and surplus both lock; drop, surplus and unlock repair");
	}

	/** Startup check: a mismatch or backup restore locks, and the lock persists. */
	private static void startupLock(TestContext ctx) {
		Fixture f = new Fixture(ctx);
		FakePlayer a = funded(ctx, "BountyA", 1_000 * D);
		FakePlayer b = funded(ctx, "BountyB", 0);
		check(f.place(a, b, 200 * D).status().ok(), "A→B");
		isFalse(f.ledger.startupCheck(false, 1), "matches, loaded normally → clear");
		isTrue(f.ledger.startupCheck(true, 2), "loaded from backup → locked, even if the totals match");
		check(f.ledger.lockReason().contains("backup"), "reason: " + f.ledger.lockReason());
		eq(2L, f.ledger.lockedAt(), "timestamp");
		eq(BountyLedger.PlaceStatus.LOCKED, f.place(a, b, 100 * D).status(), "contribution locked");

		// The lock survives save, load and the next start
		BountyData copy = DataStore.gson().fromJson(DataStore.gson().toJson(f.data), BountyData.class);
		copy.fixNulls("selftest");
		isTrue(copy.locked, "locked saved");
		eq(f.ledger.lockReason(), copy.lockReason, "reason saved");
		BountyLedger reloaded = new BountyLedger(() -> copy, () -> {}, f.escrow, () -> f.cfg);
		isTrue(reloaded.startupCheck(false, 3), "lock stays after a restart");
		eq(BountyLedger.UnlockStatus.OK, f.ledger.unlock(), "admin unlocks");
		isFalse(f.ledger.isLocked(), "clear");
		eq("", f.ledger.lockReason(), "reason cleared");

		EconomyService.set(f.escrow, 300 * D);
		isTrue(f.ledger.startupCheck(false, 4), "surplus → locked");
		check(f.ledger.lockReason().contains("surplus $100.00"), "reason: " + f.ledger.lockReason());
		EconomyService.set(f.escrow, 200 * D);
		eq(BountyLedger.UnlockStatus.OK, f.ledger.unlock(), "clear");
		EconomyService.set(f.escrow, 150 * D);
		isTrue(f.ledger.startupCheck(false, 5), "shortfall → locked");
		check(f.ledger.lockReason().contains("deficit $50.00"), "reason: " + f.ledger.lockReason());
		EconomyService.set(f.escrow, 200 * D);
		eq(BountyLedger.UnlockStatus.OK, f.ledger.unlock(), "clear");
		isFalse(f.ledger.startupCheck(false, 6), "clear again afterwards");
		ctx.detail("backup, surplus and shortfall lock at start, lock persists");
	}

	/**
	 * Minimum killer playtime (guard against alts while no real IPs arrive behind a tunnel): pure decision
	 * plus real hook (the bounty stays, the killer is told why).
	 */
	private static void playtimeRule(TestContext ctx) {
		BountyConfig cfg = new BountyConfig();
		eq(120, cfg.minKillerPlaytimeMinutes, "default 2 h");
		long min = cfg.minKillerPlaytimeTicks();
		eq(120L * 60 * 20, min, "in ticks");
		String r = BountyFeature.blockReason(false, null, null, min - 1, cfg);
		check(r != null && r.contains("playtime"), "just below blocks: " + r);
		eq(null, BountyFeature.blockReason(false, null, null, min, cfg), "exactly enough playtime");
		cfg.minKillerPlaytimeMinutes = 0;
		eq(null, BountyFeature.blockReason(false, null, null, 0, cfg), "rule off");
		BountyConfig bad = new BountyConfig();
		bad.minKillerPlaytimeMinutes = -1;
		Assert.assertThrows(IllegalArgumentException.class, bad::validate, "negative playtime");
		BountyConfig bad2 = new BountyConfig();
		bad2.targetNoticeCooldownSeconds = 0;
		Assert.assertThrows(IllegalArgumentException.class, bad2::validate, "target cooldown 0");

		ctx.preserveTreasury();
		FakePlayer a = funded(ctx, "BountyA", 1_000 * D);
		FakePlayer b = funded(ctx, "BountyB", 0);
		FakePlayer k = funded(ctx, "BountyK", 0); // Fresh account, e.g. an alt of the target
		BountyConfig live = Bounties.config();
		int before = live.minKillerPlaytimeMinutes;
		live.minKillerPlaytimeMinutes = 60;
		ctx.cleanup(() -> live.minKillerPlaytimeMinutes = before);
		PlayerDataService.addPlaytime(k.getUUID(), 59L * 60 * 20);
		check(Bounties.LIVE.place(a.getUUID(), b.getUUID(), 200 * D, System.currentTimeMillis()).status().ok(), "live stake");
		Hooks.firePlayerKilledPlayer(k, b);
		eq(200 * D, Bounties.LIVE.total(b.getUUID()), "too little playtime: bounty stays");
		eq(0, bal(k), "no payout");
		isTrue(PlayerDataService.getPendingMessages(k.getUUID()).stream().anyMatch(m -> m.contains("playtime")), "killer learns the reason");
		PlayerDataService.addPlaytime(k.getUUID(), 60L * 20);
		Hooks.firePlayerKilledPlayer(k, b);
		eq(190 * D, bal(k), "with 60 min playtime: payout");
		eq(0, Bounties.LIVE.total(b.getUUID()), "bounty deleted");
		ctx.detail("minimum playtime blocks fresh accounts");
	}

	/**
	 * Many small bounties on one target: offline only one note (other features' 50 pending messages stay) and
	 * exactly one message on join; online at most one message per cooldown plus a summary.
	 */
	private static void targetNoticeThrottle(TestContext ctx) {
		FakePlayer t = ctx.player("BountyT"); // FakePlayers are never in the PlayerList, so they count as offline
		UUID id = t.getUUID();
		ctx.cleanup(() -> BountyNotify.forget(id));
		for (int i = 0; i < 50; i++) PlayerDataService.addPendingMessage(id, "other " + i);
		for (int i = 0; i < 100; i++) BountyNotify.notifyTarget(ctx.server(), id, "BountyA", 100 * D, (i + 1) * 100 * D, 1_000 + i);
		List<String> pending = PlayerDataService.getPendingMessages(id);
		eq(50, pending.size(), "pending store still full");
		eq("other 0", pending.get(0), "oldest foreign message not displaced");
		BountyNotify.Notice n = BountyNotify.offlineNotice(id);
		Assert.notNull(n, "collected note");
		eq(100, n.count, "100 contributions counted");
		eq(10_000 * D, n.amount, "total");

		// Join: exactly one message
		PlayerDataService.removePendingMessages(id, 50);
		PlayerDataService.addPendingMessage(id, "other X");
		BountyNotify.queueJoinSummary(id, 10_000 * D);
		pending = PlayerDataService.getPendingMessages(id);
		eq(2, pending.size(), "one message added");
		eq("other X", pending.get(0), "foreign message stays");
		check(pending.get(1).contains("100 bounties") && pending.get(1).contains("$10,000.00"), "summary: " + pending.get(1));
		eq(null, BountyNotify.offlineNotice(id), "note cleared");
		BountyNotify.queueJoinSummary(id, 10_000 * D);
		eq(2, PlayerDataService.getPendingMessages(id).size(), "not twice");

		// One contribution: same text as online, with the name
		BountyNotify.notifyTarget(ctx.server(), id, "BountyC", 250 * D, 250 * D, 5_000);
		BountyNotify.queueJoinSummary(id, 250 * D);
		BountyNotify.Notice single = new BountyNotify.Notice();
		single.add(1, 250 * D, "BountyC");
		pending = PlayerDataService.getPendingMessages(id);
		eq(BountyNotify.targetText(single, 250 * D, false), pending.get(pending.size() - 1), "single contribution with name");
		// Bounty refunded meanwhile: the note lapses silently
		BountyNotify.notifyTarget(ctx.server(), id, "BountyC", 100 * D, 100 * D, 6_000);
		BountyNotify.queueJoinSummary(id, 0);
		eq(3, PlayerDataService.getPendingMessages(id).size(), "no note without a bounty");
		eq(null, BountyNotify.offlineNotice(id), "note cleared anyway");

		// Online: first right away, more collected during the cooldown, then one summary
		UUID on = UUID.randomUUID();
		ctx.cleanup(() -> BountyNotify.forget(on));
		long cd = 30_000;
		long t0 = 10_000_000L;
		String first = BountyNotify.placedOnline(on, "BountyA", 100 * D, 100 * D, t0, cd);
		check(first != null && first.contains("by §fBountyA"), "first contribution right away: " + first);
		for (int i = 1; i <= 49; i++) {
			if (BountyNotify.placedOnline(on, "BountyB", 100 * D, (i + 1) * 100 * D, t0 + i * 100L, cd) != null) Assert.fail("message " + i + " not throttled");
		}
		String summary = BountyNotify.takeOnlineSummary(on, 5_000 * D, t0 + cd);
		check(summary != null && summary.contains("49 more bounties") && summary.contains("$4,900.00") && summary.contains("$5,000.00"),
			"summary: " + summary);
		eq(null, BountyNotify.takeOnlineSummary(on, 5_000 * D, t0 + cd + 1), "only once");
		eq(null, BountyNotify.placedOnline(on, "BountyC", 100 * D, 5_100 * D, t0 + cd + 10, cd), "cooldown restarts after the summary");
		String one = BountyNotify.takeOnlineSummary(on, 5_100 * D, t0 + 2 * cd);
		check(one != null && one.contains("by §fBountyC"), "one collected contribution with name: " + one);
		check(BountyNotify.placedOnline(on, "BountyA", 100 * D, 5_200 * D, t0 + 3 * cd, cd) != null, "right away again after the cooldown");
		ctx.detail("offline 100 → 1 message, online 50 → 2 messages");
	}

	/**
	 * TestPlayers cleanup refunds live bounties from and on test players and deletes their history entries.
	 */
	private static void testCleanup(TestContext ctx) {
		preserveHistory(ctx);
		ctx.preserveTreasury();
		FakePlayer a = veteran(funded(ctx, "BountyA", 1_000 * D));
		FakePlayer b = funded(ctx, "BountyB", 1_000 * D);
		FakePlayer k = funded(ctx, "BountyK", 0);
		long escrowBefore = Bounties.LIVE.escrowBalance();
		long now = System.currentTimeMillis();
		check(Bounties.LIVE.place(a.getUUID(), b.getUUID(), 300 * D, now).status().ok(), "A→B");
		check(Bounties.LIVE.place(b.getUUID(), a.getUUID(), 200 * D, now).status().ok(), "B→A");
		check(Bounties.LIVE.place(b.getUUID(), k.getUUID(), 100 * D, now).status().ok(), "B→K");
		Hooks.firePlayerKilledPlayer(a, k); // History entry involving test players
		BountyFeature.cleanupTestPlayer(a.getUUID());
		eq(0, Bounties.LIVE.total(a.getUUID()), "bounty on A refunded");
		eq(0, Bounties.LIVE.total(b.getUUID()), "contribution from A refunded");
		eq(1_000 * D + 95 * D, bal(a), "A has everything back (+ bounty on K)");
		eq(900 * D, bal(b), "B: 1000 − 100 (on K, claimed) − 200 (on A, refunded) + 200 = 900");
		isFalse(Bounties.LIVE.history().stream().anyMatch(cl -> a.getUUID().equals(cl.killer)), "history without A");
		eq(escrowBefore, Bounties.LIVE.escrowBalance(), "live escrow restored");
		ctx.detail("live data clean");
	}

	private static void liveConsistency(TestContext ctx) {
		eq(Bounties.LIVE.totalEscrowed(), Bounties.LIVE.escrowBalance(), "escrow account = bounty total");
		eq(0, Bounties.LIVE.escrowDifference(), "neither shortfall nor surplus");
		isFalse(Bounties.LIVE.isLocked(), "not locked (" + Bounties.LIVE.lockReason() + ")");
		isTrue(EconomyService.isSystemAccount(Bounties.ESCROW_ID), "escrow is a system account");
		isFalse(EconomyService.top().stream().anyMatch(e -> e.id().equals(Bounties.ESCROW_ID)), "not in /baltop");
		ctx.detail(Bounties.LIVE.top().size() + " active, " + Money.format(Bounties.LIVE.escrowBalance()) + " in escrow");
	}
}
