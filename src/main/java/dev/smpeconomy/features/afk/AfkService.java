package dev.smpeconomy.features.afk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.Bots;
import dev.smpeconomy.core.CrateKeys;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.EconomyService.Result;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.TimeUtil;
import dev.smpeconomy.core.Treasury;
import dev.smpeconomy.features.afk.AfkTracker.Kind;
import dev.smpeconomy.features.afk.AfkTracker.Step;

/**
 * Runs the AFK zone on the server thread: checks server-side positions every second and pays out after each full
 * interval. Leaving, dying, changing dimension, reconnecting or a changed zone resets the timer. Limited by the
 * per-account daily limit and optionally by {@code maxAccountsPerIp}.
 */
final class AfkService {
	static final AfkTracker TRACKER = new AfkTracker();
	static final String KEY_DAY = "afk.day";
	static final String KEY_TODAY = "afk.today";
	static final String COUNTER_REWARDS = "afk.rewards";
	static final String COUNTER_EARNED = "afk.earned";

	/** Zone and padding from the last check, used to tell players standing in it that the zone changed. */
	private static AfkZone lastZone;
	private static int lastPadding;
	private static Set<UUID> ipBlockedNow = Set.of();

	private AfkService() {}

	static void reset() {
		TRACKER.reset();
		lastZone = null;
		lastPadding = 0;
		ipBlockedNow = Set.of();
	}

	/** {@code money} is null when no money is configured (rewardCents 0). */
	record Payout(Result money, long cents, boolean key, String tier, boolean counted) {
		boolean moneyOk() {
			return money != null && money.ok();
		}
	}

	static void tick(MinecraftServer server) {
		AfkConfig cfg = AfkFeature.CONFIG.get();
		AfkZone zone = cfg.zone();
		AfkZone oldZone = lastZone;
		int oldPadding = lastPadding;
		Set<UUID> dropped = TRACKER.syncZone(zone == null ? null : List.of(zone, cfg.heightPadding));
		boolean changed = !Objects.equals(oldZone, zone) || oldPadding != cfg.heightPadding;
		lastZone = zone;
		lastPadding = cfg.heightPadding;
		long now = monotonicMillis();
		Set<UUID> online = new HashSet<>();
		List<ServerPlayer> active = new ArrayList<>();
		for (ServerPlayer p : new ArrayList<>(server.getPlayerList().getPlayers())) {
			if (Bots.isBot(p)) continue;
			online.add(p.getUUID());
			if (changed && (dropped.contains(p.getUUID()) || isInside(p, oldZone, oldPadding))) {
				// Timer discarded; the next check counts the new zone from zero
				Msg.actionBar(p, zone == null ? "§cThe AFK zone was removed" : "§eThe AFK zone was changed §8– §7timer reset");
				continue;
			}
			active.add(p);
		}
		Set<UUID> blocked = Set.of();
		try {
			blocked = ipBlocked(ipCandidates(active, zone, cfg), cfg.maxAccountsPerIp);
		} catch (RuntimeException e) {
			SmpEconomy.LOG.error("[SMPEco] AFK IP rule failed", e);
		}
		ipBlockedNow = blocked;
		for (ServerPlayer p : active) {
			try {
				process(p, zone, cfg, now, () -> ThreadLocalRandom.current().nextDouble(), blocked.contains(p.getUUID()));
			} catch (RuntimeException e) {
				SmpEconomy.LOG.error("[SMPEco] AFK check for {} failed", p.getPlainTextName(), e);
			}
		}
		TRACKER.retainOnly(online);
	}

	/** {@code ip} null means unrestricted; {@code seconds} is the running session, -1 if none. */
	record IpCandidate(UUID id, String ip, int seconds) {}

	private static List<IpCandidate> ipCandidates(List<ServerPlayer> players, AfkZone zone, AfkConfig cfg) {
		if (cfg.maxAccountsPerIp <= 0 || zone == null) return List.of();
		List<IpCandidate> list = new ArrayList<>();
		for (ServerPlayer p : players) {
			UUID id = p.getUUID();
			if (!isInside(p, zone, cfg.heightPadding) || limitReached(id, cfg)) continue;
			list.add(new IpCandidate(id, ipOf(p), TRACKER.seconds(id, p)));
		}
		return list;
	}

	/**
	 * At most {@code maxPerIp} accounts per IP collect at once (0 disables the rule). Whoever has been collecting
	 * longest keeps the slot, ties go by list order. Returns the blocked players.
	 */
	static Set<UUID> ipBlocked(List<IpCandidate> candidates, int maxPerIp) {
		if (maxPerIp <= 0 || candidates.isEmpty()) return Set.of();
		List<IpCandidate> sorted = new ArrayList<>(candidates);
		sorted.sort(Comparator.comparingInt(IpCandidate::seconds).reversed()); // stable sort keeps list order on ties
		Map<String, Integer> used = new HashMap<>();
		Set<UUID> blocked = new HashSet<>();
		for (IpCandidate c : sorted) {
			if (c.ip() == null) continue;
			if (used.merge(c.ip(), 1, Integer::sum) > maxPerIp) blocked.add(c.id());
		}
		return blocked;
	}

	static String ipOf(ServerPlayer p) {
		try {
			if (p.connection == null) return null;
			String ip = p.getIpAddress();
			return ip == null || ip.isEmpty() || ip.startsWith("<") ? null : ip;
		} catch (RuntimeException e) {
			return null;
		}
	}

	static boolean ipBlockedNow(UUID id) {
		return ipBlockedNow.contains(id);
	}

	static long monotonicMillis() {
		return System.nanoTime() / 1_000_000L;
	}

	static boolean counts(boolean alive, boolean spectator, boolean removed) {
		return alive && !spectator && !removed;
	}

	static String dimensionOf(ServerPlayer p) {
		return p.level().dimension().identifier().toString();
	}

	static boolean isInside(ServerPlayer p, AfkZone zone, int heightPadding) {
		if (zone == null || !counts(p.isAlive(), p.isSpectator(), p.isRemoved())) return false;
		BlockPos feet = p.blockPosition();
		return zone.contains(dimensionOf(p), feet.getX(), feet.getY(), feet.getZ(), heightPadding);
	}

	static Step process(ServerPlayer p, AfkZone zone, AfkConfig cfg, long nowMs, DoubleSupplier roll) {
		return process(p, zone, cfg, nowMs, roll, false);
	}

	static Step process(ServerPlayer p, AfkZone zone, AfkConfig cfg, long nowMs, DoubleSupplier roll, boolean ipBlocked) {
		UUID id = p.getUUID();
		boolean inside = isInside(p, zone, cfg.heightPadding);
		if (inside && limitReached(id, cfg)) {
			TRACKER.remove(id);
			Msg.actionBar(p, "§6AFK Zone §8» §7Daily limit reached §8(§e" + rewardsToday(id) + "/" + cfg.maxRewardsPerDay
				+ "§8) §7– more tomorrow");
			return new Step(Kind.LIMIT, 0, cfg.intervalSeconds);
		}
		if (inside && ipBlocked) {
			TRACKER.remove(id);
			Msg.actionBar(p, "§6AFK Zone §8» §7Another account on your IP is already collecting §8(max " + cfg.maxAccountsPerIp + ")");
			return new Step(Kind.IP_LIMIT, 0, cfg.intervalSeconds);
		}
		Step step = TRACKER.step(id, p, inside, cfg.intervalSeconds, nowMs);
		switch (step.kind()) {
			case ENTERED -> Msg.actionBar(p, "§aEntered the AFK zone §8» " + countdown(step.remaining(), cfg));
			case PROGRESS -> Msg.actionBar(p, "§6AFK Zone §8» " + countdown(step.remaining(), cfg));
			case LEFT -> Msg.actionBar(p, "§cLeft the AFK zone §8– §7timer reset");
			case REWARD -> {
				Payout payout = payout(id, cfg, roll);
				announce(p, payout);
				Msg.actionBar(p, "§6AFK Zone §8» " + countdown(step.remaining(), cfg));
			}
			default -> {}
		}
		return step;
	}

	private static String countdown(int remainingSeconds, AfkConfig cfg) {
		String reward = cfg.rewardCents > 0 ? " §8(" + Msg.moneyStr(cfg.rewardCents) + "§8)" : "";
		return "§7Next reward in §e" + TimeUtil.formatDuration(remainingSeconds * 1000L) + reward;
	}

	/** A reward above the hourly budget would otherwise warn every interval, so log it once per run. */
	private static boolean treasuryWarned;

	/**
	 * Money (new, or capped from the treasury) and a key roll. Counts toward the daily limit only if something was
	 * credited. A failed money payment leaves the balance untouched.
	 */
	static Payout payout(UUID id, AfkConfig cfg, DoubleSupplier roll) {
		long cents = cfg.rewardCents;
		Result money = null;
		if (cents > 0) {
			if (!cfg.payFromTreasury) {
				money = EconomyService.deposit(id, cents);
			} else if (cents > Treasury.maxSpendPerHour()) {
				// Can never fit the hourly budget, so don't keep retrying; only the key roll counts
				money = Result.TREASURY_LIMIT;
				if (!treasuryWarned) {
					treasuryWarned = true;
					SmpEconomy.LOG.warn("[SMPEco] afk: reward {} is above the treasury's hourly budget ({}), no AFK money",
						Money.format(cents), Money.format(Treasury.maxSpendPerHour()));
				}
			} else {
				money = Treasury.payTo(id, cents, Treasury.R_REWARD);
			}
		}
		boolean key = CrateKeys.enabled() && rollKey(cfg.keyChancePercent, roll.getAsDouble()); // keys need the crates module
		if (key) CrateKeys.give(id, cfg.keyTier, 1);
		boolean counted = key || (money != null && money.ok());
		if (counted) {
			long day = TimeUtil.today().toEpochDay();
			long today = PlayerDataService.getLong(id, KEY_DAY, Long.MIN_VALUE) == day ? PlayerDataService.getLong(id, KEY_TODAY, 0) : 0;
			PlayerDataService.setLong(id, KEY_DAY, day);
			PlayerDataService.setLong(id, KEY_TODAY, today + 1);
			PlayerDataService.addCounter(id, COUNTER_REWARDS, 1);
			if (money != null && money.ok()) PlayerDataService.addCounter(id, COUNTER_EARNED, cents);
		}
		return new Payout(money, cents, key, cfg.keyTier, counted);
	}

	static boolean rollKey(double chancePercent, double roll) {
		if (!(chancePercent > 0) || !Double.isFinite(roll)) return false;
		return roll * 100.0 < chancePercent;
	}

	private static void announce(ServerPlayer p, Payout payout) {
		String keyPart = payout.key() ? "§61× " + CrateKeys.label(payout.tier()) + " Key" : null;
		if (payout.moneyOk()) {
			Msg.success(p, "AFK reward: " + Msg.moneyStr(payout.cents()) + (keyPart != null ? " §8+ " + keyPart : ""));
			Gui.successSound(p);
			return;
		}
		if (payout.money() != null) {
			Msg.error(p, payout.money() == Result.TREASURY_LIMIT || payout.money() == Result.INSUFFICIENT_FUNDS
				? "The Treasury can't pay the AFK reward right now."
				: "AFK reward not possible: " + payout.money().message());
		}
		if (keyPart != null) {
			Msg.success(p, "AFK reward: " + keyPart);
			Gui.successSound(p);
		}
	}

	static long rewardsToday(UUID id) {
		long day = TimeUtil.today().toEpochDay();
		if (PlayerDataService.getLong(id, KEY_DAY, Long.MIN_VALUE) != day) return 0;
		return PlayerDataService.getLong(id, KEY_TODAY, 0);
	}

	static boolean limitReached(UUID id, AfkConfig cfg) {
		return cfg.maxRewardsPerDay > 0 && rewardsToday(id) >= cfg.maxRewardsPerDay;
	}

	/** -1 if this player object has no session. */
	static int remaining(ServerPlayer p, AfkConfig cfg) {
		int s = TRACKER.seconds(p.getUUID(), p);
		return s < 0 ? -1 : Math.max(0, cfg.intervalSeconds - s);
	}
}
