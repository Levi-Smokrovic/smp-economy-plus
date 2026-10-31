package dev.smpeconomy.features.daily;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.CrateKeys;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.TimeUtil;

/**
 * Claiming, state and texts of /daily, all on the server thread. The clock is replaceable ({@link #useClock}).
 * <p>Daily is a money source like missions, not paid by the treasury: the treasury's hourly NPC cap (about $1,250
 * at the default reserve) could never pay the day 7 reward.
 */
public final class DailyService {
	public static final String STATE_KEY = "daily.state";

	private static final DateTimeFormatter DEADLINE_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH); // zone applied only when formatting, since core.json timezone can change on reload
	private static LongSupplier clock = System::currentTimeMillis;

	private DailyService() {}

	public static long now() {
		return clock.getAsLong();
	}

	/** Returns a Runnable that restores the previous clock. */
	public static Runnable useClock(LongSupplier newClock) {
		LongSupplier old = clock;
		clock = newClock;
		return () -> clock = old;
	}

	public static DailyState state(UUID id) {
		DailyState s = null;
		try {
			s = PlayerDataService.getBlob(id, STATE_KEY, DailyState.class);
		} catch (RuntimeException e) {
			SmpEconomy.LOG.warn("[SMPEco] daily: state of {} unreadable ({}), treating as empty", id, e.toString());
		}
		return s == null ? new DailyState() : s.sanitize();
	}

	static void save(UUID id, DailyState s) {
		if (s == null || s.isEmpty()) {
			// create nothing, only remove an existing entry
			boolean present = PlayerDataService.peek(id).map(d -> d.blobs.containsKey(STATE_KEY)).orElse(false);
			if (present) PlayerDataService.remove(id, STATE_KEY);
			return;
		}
		PlayerDataService.setBlob(id, STATE_KEY, s);
	}

	public static DailyLogic.Status status(UUID id) {
		return status(id, now(), DailyConfig.get());
	}

	public static DailyLogic.Status status(UUID id, long now, DailyConfig cfg) {
		return DailyLogic.status(state(id), now, cfg.breakMs());
	}

	public enum Outcome { OK, ALREADY_CLAIMED, MONEY_FAILED }

	public record Claim(Outcome outcome, int day, int streak, long money, int keys, String keyTier, boolean streakLost,
		DailyLogic.Status before, EconomyService.Result error) {
		public boolean ok() {
			return outcome == Outcome.OK;
		}
	}

	public static Claim claim(UUID id) {
		return claim(id, now(), DailyConfig.get());
	}

	/**
	 * At most once per calendar day. New state is saved first, then money is credited; if crediting fails (account
	 * limit) the old state is restored, so money never exists without a "claimed" record. The day 7 key comes only
	 * after a successful credit.
	 */
	public static Claim claim(UUID id, long now, DailyConfig cfg) {
		DailyState before = state(id);
		DailyLogic.Status st = DailyLogic.status(before, now, cfg.breakMs());
		if (!st.claimable()) return new Claim(Outcome.ALREADY_CLAIMED, 0, st.streak(), 0, 0, cfg.day7KeyTier, false, st, null);
		int day = st.nextDay();
		long money = cfg.reward(day);
		int keys = cfg.keysFor(day);
		boolean streakLost = st.broken() && st.hadStreak();

		DailyState after = DailyLogic.applyClaim(before, st, now, money);
		save(id, after);
		if (money > 0) {
			EconomyService.Result r = EconomyService.deposit(id, money);
			if (!r.ok()) {
				save(id, before);
				return new Claim(Outcome.MONEY_FAILED, day, st.streak(), 0, 0, cfg.day7KeyTier, false, st, r);
			}
		}
		int givenKeys = 0;
		if (keys > 0) {
			try {
				CrateKeys.give(id, cfg.day7KeyTier, keys);
				givenKeys = keys;
			} catch (RuntimeException e) {
				// only conceivable on counter overflow; money and record are booked, so better no key than a
				// duplicate
				SmpEconomy.LOG.error("[SMPEco] daily: key for {} not credited", id, e);
			}
		}
		DataStore.requestFlush(); // flush money (economy.json) and record (players.json) soon
		return new Claim(Outcome.OK, day, after.streak, money, givenKeys, cfg.day7KeyTier, streakLost, st, null);
	}

	public static Claim claimAndNotify(ServerPlayer player) {
		Claim c = claim(player.getUUID());
		switch (c.outcome()) {
			case OK -> {
				if (c.streakLost()) Msg.info(player, "§cYour streak was lost §7– you're back to Day 1.");
				StringBuilder sb = new StringBuilder("§aDay " + c.day() + " claimed! §7+" + Msg.moneyStr(c.money()));
				sb.append(" §8· §7Streak: §f").append(days(c.streak()));
				Msg.info(player, sb.toString());
				if (c.keys() > 0) {
					Msg.info(player, "§6+" + c.keys() + "× " + CrateKeys.label(c.keyTier()) + " Key §7– week complete! Open it with §e/crates§7.");
				}
				Gui.successSound(player);
			}
			case ALREADY_CLAIMED -> {
				long wait = c.before().nextClaimAtMs() - now();
				Msg.error(player, "You've already claimed your daily reward today. Next one in " + TimeUtil.formatDuration(wait) + ".");
				Gui.failSound(player);
			}
			case MONEY_FAILED -> {
				Msg.error(player, "Can't claim: " + c.error().message());
				Gui.failSound(player);
			}
		}
		return c;
	}

	public static Optional<String> reminderText(UUID id, long now, DailyConfig cfg) {
		DailyLogic.Status st = status(id, now, cfg);
		if (!st.claimable()) return Optional.empty();
		int day = st.nextDay();
		StringBuilder sb = new StringBuilder("§eYour daily reward is waiting: §fDay ").append(day).append(" §8(").append(Msg.moneyStr(cfg.reward(day)));
		if (cfg.keysFor(day) > 0) sb.append(" §8+ §6").append(cfg.keysFor(day)).append("× ").append(CrateKeys.label(cfg.day7KeyTier)).append(" Key");
		sb.append("§8)");
		if (st.streak() > 0) {
			sb.append(" §7Streak §f").append(days(st.streak())).append(" §7expires in §c").append(TimeUtil.formatDuration(st.streakDeadlineMs() - now)).append("§7.");
		} else if (st.broken() && st.hadStreak()) {
			sb.append(" §7Your streak was lost – back to Day 1.");
		}
		return Optional.of(sb.toString());
	}

	public static boolean remind(ServerPlayer player) {
		Optional<String> text = reminderText(player.getUUID(), now(), DailyConfig.get());
		if (text.isEmpty()) return false;
		MutableComponent line = Msg.prefixed(text.get() + " ")
			.append(Msg.clickRun("§a§l[Claim]", "/daily", "§7Opens §e/daily"));
		Msg.send(player, line);
		return true;
	}

	public static boolean shift(UUID id, long hours) {
		DailyState s = state(id);
		if (s.lastClaimMs <= 0) return false;
		s.lastClaimMs = Math.max(1, s.lastClaimMs - hours * DailyLogic.HOUR_MS);
		save(id, s);
		return true;
	}

	/**
	 * Sets the streak for support, e.g. after an outage. With no running streak it counts as "claimed yesterday",
	 * so the next claim continues it. Creates no money.
	 */
	public static void setStreak(UUID id, int streak, long now, DailyConfig cfg) {
		DailyState s = state(id);
		DailyLogic.Status st = DailyLogic.status(s, now, cfg.breakMs());
		s.streak = Math.max(1, streak);
		s.bestStreak = Math.max(s.bestStreak, s.streak);
		if (s.lastClaimMs <= 0 || st.broken()) s.lastClaimMs = now - 24 * DailyLogic.HOUR_MS;
		save(id, s);
	}

	public static void reset(UUID id) {
		save(id, null);
	}

	static String days(int n) {
		return n == 1 ? "1 day" : n + " days";
	}

	static String formatDeadline(long epochMs) {
		return DEADLINE_FORMAT.withZone(TimeUtil.zone()).format(Instant.ofEpochMilli(epochMs));
	}
}
