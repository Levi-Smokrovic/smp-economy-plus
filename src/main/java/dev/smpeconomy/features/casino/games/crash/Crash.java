package dev.smpeconomy.features.casino.games.crash;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.ConfigManager;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.EconomyService.Result;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.Settings;
import dev.smpeconomy.core.TimeUtil;
import dev.smpeconomy.core.Treasury;
import dev.smpeconomy.core.test.TestPlayers;
import dev.smpeconomy.features.casino.Casino;
import dev.smpeconomy.features.casino.CasinoConfig;
import dev.smpeconomy.features.casino.games.crash.CrashData.PendingPayout;
import dev.smpeconomy.features.casino.games.crash.CrashData.PlayerStats;
import dev.smpeconomy.features.casino.games.crash.CrashData.Round;

/**
 * Crash rounds without GUI: admission, start (crash point drawn and stored before the rocket starts), the flight
 * ({@link #advance}), cash-out, exactly-once settlement and stats.
 *
 * <p>Uses the casino's services for everything that is shared: on/off switch, minimum bet, maximum bet
 * ({@link Casino#maxBet()}, ≤ 2 % of the treasury), maximum win and free treasury coverage ({@link Casino#coverLimit}),
 * the daily loss limit ({@link Casino#lossRoom}) and the treasury ledger reason {@link Casino#REASON}. Crash rounds live in
 * their own store and are booked into the casino through {@link #BOOK} ({@link Casino#registerBook}): the casino is the
 * single source of truth, so its coverage (every house game and crash) subtracts crash reservations and pending crash
 * payouts, its net loss of the day includes crash, {@code /smpeco casino resetlimit} resets the crash day as well and an
 * open crash round blocks a house-game round (and vice versa).
 *
 * <p>Money: bet via {@link Treasury#collectFrom} at the start, payout via {@link Treasury#payToUncapped} at the end
 * (the round was only admitted when the free coverage covered its highest possible payout, which stays reserved). All on
 * the server thread.
 */
public final class Crash {
	static final String LABEL = "§6Crash";
	/** Number of past crash points shown in the GUI. */
	static final int HISTORY = 6;
	/** Rounds without an open window are settled after this long (the GUI drives the flight). */
	static final long ORPHAN_MS = 5_000;
	/** Safety net: a round whose window is still open is settled after this long (normal rounds end at the cap). */
	static final long MAX_ROUND_MS = 10 * 60_000L;

	static final ConfigManager.Config<CrashConfig> CONFIG = ConfigManager.register("casino_crash", CrashConfig.class, CrashConfig::new, CrashConfig::validate);
	static final DataStore<CrashData> STORE = new DataStore<>("casino_crash.json", CrashData.class, CrashData::new);

	/** Today (server time zone) for the crash part of the daily loss, replaceable in selftests. */
	static Supplier<LocalDate> today = TimeUtil::today;
	static LongSupplier clock = System::currentTimeMillis;

	/** Open crash windows per round (watchdog). */
	private static final Map<String, CrashGui> GUIS = new HashMap<>();

	static {
		STORE.onLoad(CrashData::fixNulls);
	}

	private Crash() {}

	/** Loads the class (config + store must exist before the common load in SERVER_STARTED). */
	static void bootstrap() {
	}

	static CrashConfig cfg() {
		return CONFIG.get();
	}

	static CrashData data() {
		return STORE.get();
	}

	/**
	 * After every money/round change: the store is written together with economy.json/treasury.json (same commit group
	 * at the next save), never alone, so the stored round and the booked bet/payout cannot drift apart after a crash.
	 */
	static void persist() {
		STORE.markDirty();
		DataStore.requestFlush();
	}

	/** Start of a round: either {@code round} or an error message for the player. */
	record Start(Round round, String error) {
		boolean ok() {
			return round != null;
		}

		static Start fail(String error) {
			return new Start(null, error);
		}
	}

	record Settled(Round round, long payout, boolean refund, boolean paid) {}

	/** Crash's part in the casino's shared books (registered in {@link CrashGame#register()}). */
	static final Casino.ExtBook BOOK = new Casino.ExtBook() {
		@Override
		public long reserved() {
			return reservedTotal();
		}

		@Override
		public long netLossToday(UUID player) {
			return Crash.netLossToday(player);
		}

		@Override
		public void resetDay(UUID player) {
			Crash.resetDay(player);
		}

		@Override
		public long openStake(UUID player) {
			Round r = openRound(player);
			return r == null ? 0 : r.stake;
		}

		@Override
		public boolean hasOpenRound(UUID player) {
			return openRound(player) != null;
		}
	};

	/** Coverage held by open crash rounds and pending crash payouts (counted by {@link Casino#reservedTotal} via {@link #BOOK}). */
	static long reservedTotal() {
		long sum = 0;
		for (Round r : data().rounds.values()) sum = Math.addExact(sum, Math.max(0, r.reserved));
		for (PendingPayout p : data().pending) sum = Math.addExact(sum, p.amount);
		return sum;
	}

	/**
	 * Highest payout a new round may have: the casino's cover limit (maximum win, and what the treasury still covers after
	 * ALL reservations, house games and crash alike, see {@link #BOOK}).
	 */
	static long coverLimit() {
		return Casino.coverLimit(null);
	}

	/** Highest possible payout of a round with this bet (at the auto cash-out, else at the cap). */
	static long potential(long stake, long autoH) {
		try {
			return CrashMath.payout(stake, CrashMath.target(autoH, cfg().capH()));
		} catch (ArithmeticException e) {
			return Long.MAX_VALUE;
		}
	}

	/**
	 * Crash part of today's net loss (bets − payouts of settled rounds; negative = in profit). The casino adds it to its
	 * own ({@link Casino#netLossToday}) via {@link #BOOK}.
	 */
	static long netLossToday(UUID player) {
		PlayerStats s = data().players.get(player);
		if (s == null || !today.get().toString().equals(s.day)) return 0;
		return s.dayStaked - s.dayPaid;
	}

	/** How much may still be lost today (the casino's shared daily loss limit, crash losses included). */
	static long lossRoom(UUID player) {
		return Casino.lossRoom(player);
	}

	/** Highest bet that would be admitted right now with this auto cash-out (balance not considered), 0 = locked. */
	static long maxStake(UUID player, long autoH) {
		long minBet = Casino.cfg().minBet;
		long max = Math.min(Casino.maxBet(), lossRoom(player));
		max = Math.min(max, CrashMath.maxStakeFor(coverLimit(), CrashMath.target(autoH, cfg().capH())));
		return max >= minBet ? max : 0;
	}

	static String pct(double p) {
		return (p == Math.floor(p) ? String.valueOf((long) p) : String.format(Locale.ROOT, "%.1f", p)) + " %";
	}

	/** Everything except the player's balance; null = admitted, otherwise a message (red). */
	static String checkAdmission(UUID player, long stake, long autoH) {
		CasinoConfig cc = Casino.cfg();
		CrashConfig c = cfg();
		if (!cc.enabled || !c.enabled) return "Crash is closed right now.";
		if (Casino.openRound(player) != null || openRound(player) != null) return "You are already playing a round.";
		long capH = c.capH();
		if (autoH != 0 && (autoH <= CrashMath.ONE || autoH > capH)) {
			return "Auto cash-out must be between ×1.01 and " + CrashMath.format(capH) + " (or off).";
		}
		if (stake < cc.minBet) return "Minimum bet: " + Msg.moneyStr(cc.minBet) + "§c.";
		long maxBet = Casino.maxBet();
		if (maxBet < cc.minBet) return "The casino is locked: the treasury is too small.";
		if (stake > maxBet) return "Maximum bet: " + Msg.moneyStr(maxBet) + " §c(at most " + pct(cc.effectiveMaxBetPercent()) + " of the treasury).";
		long potential = potential(stake, autoH);
		if (potential > coverLimit()) {
			long max = maxStake(player, autoH);
			return "The treasury can't cover a possible win of " + Msg.moneyStr(potential) + " §cright now. "
				+ (max > 0 ? "At most " + Msg.moneyStr(max) + " §cbet" + (autoH > 0 ? " with this auto cash-out." : " – a lower auto cash-out allows more.")
				: "Game locked.");
		}
		long room = lossRoom(player);
		if (stake > room) {
			return "Daily loss limit reached (" + Msg.moneyStr(Casino.lossLimit(player)) + "§c). "
				+ (room > 0 ? "Today at most " + Msg.moneyStr(room) + " §cbet." : "Come back tomorrow.");
		}
		return null;
	}

	static Round openRound(UUID player) {
		for (Round r : data().rounds.values()) {
			if (r.player.equals(player)) return r;
		}
		return null;
	}

	static boolean isOpen(Round r) {
		return r != null && CrashData.PLAYING.equals(r.state) && data().rounds.get(r.id) == r;
	}

	/**
	 * Admits the round, takes the bet and draws the crash point, stored in the round (and in the same save as the bet)
	 * before the rocket starts. {@code autoH} = auto cash-out in hundredths, 0 = off.
	 */
	static Start start(UUID player, String name, long stake, long autoH, RandomGenerator rng) {
		String error = checkAdmission(player, stake, autoH);
		if (error != null) return Start.fail(error);
		if (!EconomyService.has(player, stake)) return Start.fail("Not enough money (bet " + Msg.moneyStr(stake) + "§c).");
		Result res = Treasury.collectFrom(player, stake, Casino.REASON);
		if (!res.ok()) {
			return Start.fail(res == Result.INSUFFICIENT_FUNDS ? "Not enough money (bet " + Msg.moneyStr(stake) + "§c)." : "The bet could not be booked (" + res.name() + ").");
		}
		CrashConfig c = cfg();
		CrashData d = data();
		Round r = new Round();
		r.id = "x" + d.nextId++;
		r.player = player;
		r.name = name == null ? "" : name;
		r.stake = stake;
		r.autoH = autoH;
		r.capH = c.capH();
		r.edgeBp = c.edgeBp();
		r.doublingSeconds = c.doublingSeconds;
		r.crashH = CrashMath.crashPoint(rng, r.edgeBp, r.capH);
		r.reserved = CrashMath.payout(stake, CrashMath.target(autoH, r.capH));
		r.createdAt = clock.getAsLong();
		r.lastMultH = CrashMath.ONE;
		r.state = CrashData.PLAYING;
		d.rounds.put(r.id, r);
		PlayerStats s = stats(player);
		s.lastStake = stake;
		s.lastAutoH = autoH;
		persist();
		return new Start(r, null);
	}

	/**
	 * One step of the flight: the multiplier after {@code ticks} ticks. Auto cash-out/cap reached → decided at the target;
	 * past the crash point → decided as lost; otherwise the shown multiplier is remembered (value on interruption).
	 * null = round not open.
	 */
	static CrashMath.Phase advance(Round r, long ticks) {
		if (!isOpen(r)) return null;
		long m = CrashMath.multiplierAt(ticks, r.doublingSeconds);
		long target = CrashMath.target(r.autoH, r.capH);
		CrashMath.Phase p = CrashMath.phase(r.crashH, target, m);
		switch (p) {
			case RUNNING -> r.lastMultH = m;
			case CASHED -> decide(r, CrashMath.payout(r.stake, target), target);
			case CRASHED -> decide(r, 0, 0);
		}
		return p;
	}

	/** Can the player cash out right now (rocket flying and above ×1.00)? */
	static boolean canCashOut(Round r) {
		return isOpen(r) && r.lastMultH > CrashMath.ONE && r.lastMultH <= r.crashH;
	}

	/** The player cashes out at the last shown multiplier (then {@link #settle}). */
	static boolean cashOut(Round r) {
		if (!canCashOut(r)) return false;
		decide(r, CrashMath.payout(r.stake, r.lastMultH), r.lastMultH);
		return true;
	}

	/** Value of the round now: decided payout, otherwise the last shown multiplier (nothing above ×1.00 → bet back). */
	static long cashOutValue(Round r) {
		if (CrashData.DECIDED.equals(r.state)) return r.payout;
		return isRefundState(r) ? r.stake : CrashMath.payout(r.stake, r.lastMultH);
	}

	/** Would an interruption just refund the bet (rocket not yet above ×1.00)? */
	static boolean isRefundState(Round r) {
		if (CrashData.DECIDED.equals(r.state)) return r.refund;
		return !(r.lastMultH > CrashMath.ONE && r.lastMultH <= r.crashH && r.lastMultH <= CrashMath.target(r.autoH, r.capH));
	}

	private static void decide(Round r, long payout, long cashedAtH) {
		r.state = CrashData.DECIDED;
		r.payout = Math.max(0, payout);
		r.cashedAtH = payout > 0 ? cashedAtH : 0;
		r.refund = false;
		r.reserved = r.payout;
	}

	/**
	 * Settles a round: removes it (a second call does nothing), pays via {@link Treasury#payToUncapped} (if that fails the
	 * payout is stored as pending and retried), books the stats and tells the player. A round that is still flying is
	 * cashed out at the last shown multiplier (window closed, disconnect, restart); if it never got above ×1.00 the bet is
	 * refunded. {@code why} (nullable) appears in the message.
	 */
	static Settled settle(String roundId, String why) {
		CrashData d = data();
		Round r = d.rounds.remove(roundId);
		if (r == null) return null;
		CrashGui gui = GUIS.remove(roundId);
		if (!CrashData.DECIDED.equals(r.state)) {
			boolean refund = isRefundState(r);
			long value = cashOutValue(r);
			r.state = CrashData.DECIDED;
			r.refund = refund;
			r.payout = value;
			r.cashedAtH = refund ? 0 : r.lastMultH;
		}
		boolean paid = pay(r.player, r.payout);
		if (!r.refund) recordStats(r);
		persist();
		notifySettled(r, paid, why);
		if (!r.refund) maybeAnnounce(r);
		if (gui != null) gui.detach(roundId);
		return new Settled(r, r.payout, r.refund, paid);
	}

	private static boolean pay(UUID player, long amount) {
		if (amount <= 0) return true;
		Result res = Treasury.payToUncapped(player, amount, Casino.REASON);
		if (res.ok()) return true;
		PendingPayout p = new PendingPayout();
		p.player = player;
		p.amount = amount;
		p.createdAt = clock.getAsLong();
		data().pending.add(p);
		SmpEconomy.LOG.warn("[SMPEco] Crash payout {} to {} failed ({}) – will be retried", Money.format(amount), player, res);
		return false;
	}

	/** Retries pending payouts (every 60 s and on join). */
	static int retryPending(UUID onlyPlayer) {
		int done = 0;
		List<PendingPayout> paid = new ArrayList<>();
		Iterator<PendingPayout> it = data().pending.iterator();
		while (it.hasNext()) {
			PendingPayout p = it.next();
			if (onlyPlayer != null && !onlyPlayer.equals(p.player)) continue;
			if (!EconomyService.hasAccount(p.player) && TestPlayers.isTestPlayer(p.player)) continue;
			if (!EconomyService.hasAccount(p.player)) EconomyService.ensureAccount(p.player, EconomyService.nameOf(p.player).orElse(null));
			if (Treasury.payToUncapped(p.player, p.amount, Casino.REASON).ok()) {
				it.remove();
				paid.add(p);
				done++;
			}
		}
		if (done > 0) persist();
		for (PendingPayout p : paid) Casino.tell(p.player, "§aPending crash payout paid: " + Msg.moneyStr(p.amount));
		return done;
	}

	/** Settles every open round (server start after a crash, server stop). */
	static int settleAll(String why) {
		int n = 0;
		for (String id : List.copyOf(data().rounds.keySet())) {
			if (settle(id, why) != null) n++;
		}
		return n;
	}

	/** Every second: settles rounds whose window is gone (after {@link #ORPHAN_MS}) and runaway rounds. */
	static void watchdog() {
		long now = clock.getAsLong();
		for (Round r : List.copyOf(data().rounds.values())) {
			if (TestPlayers.isTestPlayer(r.player)) continue; // self-tests clean up themselves
			CrashGui gui = GUIS.get(r.id);
			boolean young = now >= r.createdAt && now - r.createdAt < ORPHAN_MS;
			if (gui != null && gui.isOpen()) {
				if (Math.abs(now - r.createdAt) >= MAX_ROUND_MS) gui.expire(r.id, "timeout");
				continue;
			}
			if (!young) settle(r.id, "window closed");
		}
	}

	static void bind(String roundId, CrashGui gui) {
		if (roundId != null) GUIS.put(roundId, gui);
	}

	static PlayerStats stats(UUID player) {
		return data().players.computeIfAbsent(player, k -> new PlayerStats());
	}

	static PlayerStats peekStats(UUID player) {
		return data().players.get(player);
	}

	private static void recordStats(Round r) {
		PlayerStats s = stats(r.player);
		String day = today.get().toString();
		if (!day.equals(s.day)) {
			s.day = day;
			s.dayStaked = 0;
			s.dayPaid = 0;
		}
		s.dayStaked = Math.addExact(s.dayStaked, r.stake);
		s.dayPaid = Math.addExact(s.dayPaid, r.payout);
		s.rounds++;
		s.wagered = Math.addExact(s.wagered, r.stake);
		s.paidOut = Math.addExact(s.paidOut, r.payout);
		if (r.payout > r.stake) {
			s.wins++;
			s.biggestWin = Math.max(s.biggestWin, r.payout);
		}
		if (r.payout > 0) s.bestMultH = Math.max(s.bestMultH, r.cashedAtH);
		s.history.add(0, r.crashH);
		while (s.history.size() > HISTORY) s.history.remove(s.history.size() - 1);
	}

	/** Result line (also parsed by the E2E test). */
	static String resultText(Round r, String why) {
		String suffix = why == null ? "" : " §8(" + why + ")";
		if (r.refund) return LABEL + "§7: Round cancelled, bet " + Msg.moneyStr(r.payout) + " refunded." + suffix;
		if (r.payout > 0) {
			return LABEL + "§7: §aCashed out at " + CrashMath.format(r.cashedAtH) + " → " + Money.format(r.payout)
				+ " §8(bet " + Money.format(r.stake) + ", crash point " + CrashMath.format(r.crashH) + ")" + suffix;
		}
		return LABEL + "§7: §cCrashed at " + CrashMath.format(r.crashH) + " §8(bet " + Money.format(r.stake) + " lost)" + suffix;
	}

	private static void notifySettled(Round r, boolean paid, String why) {
		Casino.tell(r.player, resultText(r, why));
		if (!paid) Casino.tell(r.player, "§eYour payout of " + Msg.moneyStr(r.payout) + " §ewill be paid as soon as the treasury can cover it.");
	}

	/** Big win by the casino's thresholds (profit, or multiplier with a minimum payout)? */
	static boolean isBigWin(long stake, long payout) {
		if (stake <= 0 || payout <= stake) return false;
		CasinoConfig c = Casino.cfg();
		double mult = payout / (double) stake;
		return c.announceMinProfit > 0 && payout - stake >= c.announceMinProfit
			|| mult >= c.announceMinMultiplier && payout >= c.announceMinPayoutForMultiplier;
	}

	private static void maybeAnnounce(Round r) {
		if (TestPlayers.isTestPlayer(r.player) || !isBigWin(r.stake, r.payout)) return;
		MinecraftServer server = SmpEconomy.server();
		if (server == null) return;
		String name = r.name.isEmpty() ? EconomyService.nameOrUnknown(r.player) : r.name;
		String text = "§e" + name + " §7won §a" + Money.format(r.payout) + " §7at " + LABEL + " §8(cashed out at " + CrashMath.format(r.cashedAtH) + ")§7!";
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (Settings.get(p, Settings.ANNOUNCEMENTS)) Msg.info(p, text);
		}
		SmpEconomy.LOG.info("[SMPEco] Crash big win: {} {} at {}", name, Money.format(r.payout), CrashMath.format(r.cashedAtH));
	}

	/** Resets the crash part of today's loss (admin, E2E, and {@link Casino#resetLimits} via {@link #BOOK}). */
	static void resetDay(UUID player) {
		PlayerStats s = peekStats(player);
		if (s == null) return;
		s.dayStaked = 0;
		s.dayPaid = 0;
		persist();
	}

	/** Removes all data of a (test) player: open rounds are NOT paid (test players only!). */
	static void purge(UUID player) {
		CrashData d = data();
		boolean changed = d.rounds.values().removeIf(r -> r.player.equals(player));
		changed |= d.pending.removeIf(p -> p.player.equals(player));
		changed |= d.players.remove(player) != null;
		if (changed) STORE.markDirty();
	}
}
