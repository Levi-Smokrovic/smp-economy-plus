package dev.smpeconomy.features.events;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.BossEvent;
import net.minecraft.world.item.Item;
import dev.smpeconomy.core.Bots;
import dev.smpeconomy.core.CommandRegistry;
import dev.smpeconomy.core.compat.Mc;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Scheduler;
import dev.smpeconomy.core.Settings;
import dev.smpeconomy.core.TimeUtil;

/**
 * Live instance runtime (server thread, every second): ending and starting events, bossbar countdown with its
 * own bar (blue, yellow, purple; red and green belong to breaking news), title and sound on start (only with
 * the "announcements" setting), chat lines, and lottery: migration at start, sales start, reminder, draw,
 * paying owed prizes.
 */
public final class EventsRuntime {
	private static ServerBossEvent bar;
	private static Scheduler.Task task;

	private EventsRuntime() {}

	static void start(MinecraftServer server) {
		if (task != null) task.cancel();
		long now = System.currentTimeMillis();
		try {
			lottoStart(now);
		} catch (RuntimeException e) {
			// Events still run; the lottery stays closed until the next start (isReady false: no sales, no
			// draw)
			SmpEconomy.LOG.error("[SMPEco] events: lottery start or migration failed", e);
		}
		task = Scheduler.runRepeating(20, 20, () -> onSecond(server));
	}

	static void stop() {
		if (task != null) task.cancel();
		task = null;
		removeBar();
	}

	static void onJoin(ServerPlayer player) {
		if (Bots.isBot(player)) return;
		Optional<EventManager.Active> a = Events.active();
		if (a.isEmpty()) return;
		if (bar != null) bar.addPlayer(player);
		Scheduler.runLater(40, () -> {
			if (player.hasDisconnected()) return;
			Events.active().ifPresent(active -> Msg.send(player, Msg.prefixed(startLine(active, System.currentTimeMillis(), "§7Now running: "))));
		});
	}

	private static void onSecond(MinecraftServer server) {
		if (SmpEconomy.isStopping()) return;
		long now = System.currentTimeMillis();
		try {
			EventManager.TickResult r = EventsFeature.EVENTS.tick(now, server.getPlayerList().getPlayerCount(), TimeUtil.now().getHour());
			if (r.ended() != null) announceEnd(server, r.ended(), false);
			if (r.started() != null) announceStart(server, r.started(), now);
			if (r.warnEnding()) EventsFeature.EVENTS.active(now).ifPresent(a -> announceWarning(server, a));
			updateBar(server, now);
		} catch (RuntimeException e) {
			SmpEconomy.LOG.error("[SMPEco] events: event tick failed", e);
		}
		try {
			lottoTick(server, now);
		} catch (RuntimeException e) {
			SmpEconomy.LOG.error("[SMPEco] events: lottery tick failed", e);
		}
	}

	static EventManager.Active adminStart(MinecraftServer server, EventType type, String param, long durationMs, String by) {
		long now = System.currentTimeMillis();
		Optional<EventManager.Active> previous = EventsFeature.EVENTS.active(now);
		EventManager.Active a = EventsFeature.EVENTS.start(type, param, durationMs, now, by);
		previous.ifPresent(p -> announceEnd(server, p, true));
		announceStart(server, a, now);
		updateBar(server, now);
		return a;
	}

	static Optional<EventManager.Active> adminStop(MinecraftServer server) {
		long now = System.currentTimeMillis();
		Optional<EventManager.Active> ended = EventsFeature.EVENTS.stop(now);
		ended.ifPresent(a -> announceEnd(server, a, true));
		updateBar(server, now);
		return ended;
	}

	/**
	 * "§ftwice as often" or "§f×2.5 as often": minigames schedules chat games more often by the Lucky Hour
	 * factor (at most ×3).
	 */
	static String frequencyText(EventsConfig cfg) {
		double f = Math.min(3.0, cfg.luckyHourMultiplier);
		return Math.abs(f - 2.0) < 1e-9 ? "§ftwice as often" : "§f×" + factor(f) + " as often";
	}

	/** E.g. "§7Sell §fWood §7for §a×1.5". */
	static String effectText(EventManager.Active a) {
		EventsConfig cfg = EventsFeature.CONFIG.get();
		return switch (a.type()) {
			case MARKET_DAY -> "§7Sell §f" + categoryName(a.param()) + " §7for §a×" + factor(cfg.marketDayMultiplier);
			case GOLD_RUSH -> "§7Job §f" + jobName(a.param()) + "§7: pay §a×" + factor(cfg.goldRushMultiplier);
			case LUCKY_HOUR -> "§7Chat games " + frequencyText(cfg) + " §7& §a×" + factor(cfg.luckyHourMultiplier) + " §7rewards";
		};
	}

	static String categoryName(String id) {
		return EventsFeature.CONFIG.get().category(id).map(c -> c.name).orElse(id == null ? "?" : id);
	}

	static String jobName(String id) {
		return EventsFeature.CONFIG.get().job(id).map(j -> j.name).orElse(id == null ? "?" : id);
	}

	static String factor(double f) {
		return TraderRank.trim(f);
	}

	static String clock(long ms) {
		long s = Math.max(0, ms) / 1000;
		long h = s / 3600;
		long m = s % 3600 / 60;
		long sec = s % 60;
		return h > 0 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, sec) : String.format(java.util.Locale.ROOT, "%02d:%02d", m, sec);
	}

	static String barTitle(EventManager.Active a, long now) {
		return a.type().color() + "§l" + a.type().label().toUpperCase(java.util.Locale.ROOT) + " §8» " + effectText(a) + " §8» §e" + clock(a.remaining(now));
	}

	private static MutableComponent startLine(EventManager.Active a, long now, String lead) {
		MutableComponent line = Msg.legacy(lead + a.type().color() + "§l" + a.type().label() + "! " + effectText(a) + " §7(§e"
			+ TimeUtil.formatDuration(a.remaining(now)) + " §7left)");
		MutableComponent details = Msg.legacy(" §8[Details]");
		MutableComponent hover = Msg.legacy(a.type().color() + "§l" + a.type().label() + "\n");
		if (a.type() == EventType.MARKET_DAY) {
			hover.append(Msg.legacy("§7Applies to: "));
			List<Item> items = new ArrayList<>(EventsFeature.CONFIG.get().categoryItems(a.param()));
			int shown = 0;
			for (Item item : items) {
				if (shown > 0) hover.append(Msg.legacy("§7, "));
				if (shown >= 12) {
					hover.append(Msg.legacy("§7… (+" + (items.size() - shown) + ")"));
					break;
				}
				hover.append(Msg.itemName(item).withStyle(net.minecraft.ChatFormatting.WHITE));
				shown++;
			}
			hover.append(Msg.legacy("\n§8Price caps (PriceGuard) still apply."));
		} else if (a.type() == EventType.GOLD_RUSH) {
			hover.append(Msg.legacy("§7Job payouts for §f" + jobName(a.param()) + " §7×" + factor(EventsFeature.CONFIG.get().goldRushMultiplier)));
		} else {
			hover.append(Msg.legacy("§7Chat games come " + frequencyText(EventsFeature.CONFIG.get()) + "§7,\n§7rewards ×" + factor(EventsFeature.CONFIG.get().luckyHourMultiplier)));
		}
		hover.append(Msg.legacy("\n§eClick: /event"));
		details.withStyle(s -> s.withHoverEvent(new HoverEvent.ShowText(hover)).withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand(CommandRegistry.publicCommand("/event"))));
		return line.append(details);
	}

	private static void announceStart(MinecraftServer server, EventManager.Active a, long now) {
		Component chat = Msg.prefixed(startLine(a, now, ""));
		Component title = Msg.legacy(a.type().color() + "§l" + a.type().label().toUpperCase(java.util.Locale.ROOT) + "!");
		Component subtitle = Msg.legacy(effectText(a) + " §7(" + TimeUtil.formatDuration(a.remaining(now)) + ")");
		for (ServerPlayer p : List.copyOf(server.getPlayerList().getPlayers())) {
			Msg.send(p, chat);
			if (!Settings.get(p, Settings.ANNOUNCEMENTS)) continue;
			title(p, title, subtitle);
			Gui.sound(p, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE), 1.2f);
		}
		SmpEconomy.LOG.info("[SMPEco] Event started: {} ({}) until {} by {}", a.type().id(), a.param(), a.endsAt(), a.startedBy());
	}

	private static void announceEnd(MinecraftServer server, EventManager.Active a, boolean early) {
		Msg.broadcast(server, "§7" + a.type().article() + " " + a.type().color() + a.type().label() + " §7"
			+ (early ? "has been stopped." : "is over. §8Thanks for joining in!"));
		SmpEconomy.LOG.info("[SMPEco] Event ended: {} ({}){}", a.type().id(), a.param(), early ? " (early)" : "");
	}

	private static void announceWarning(MinecraftServer server, EventManager.Active a) {
		String hint = switch (a.type()) {
			case MARKET_DAY -> "sell now!";
			case GOLD_RUSH -> "get to work!";
			case LUCKY_HOUR -> "last chance for bigger rewards!";
		};
		Msg.broadcast(server, a.type().color() + a.type().label() + " §7ends in §e1 minute §7– " + hint);
	}

	static void title(ServerPlayer p, Component title, Component subtitle) {
		if (p.connection == null) return;
		p.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20));
		p.connection.send(new ClientboundSetTitleTextPacket(title));
		p.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
	}

	private static void updateBar(MinecraftServer server, long now) {
		Optional<EventManager.Active> active = EventsFeature.EVENTS.active(now);
		if (active.isEmpty()) {
			removeBar();
			return;
		}
		EventManager.Active a = active.get();
		if (bar == null) {
			bar = Mc.bossEvent(Msg.legacy(barTitle(a, now)), a.type().barColor(), BossEvent.BossBarOverlay.NOTCHED_10);
		} else {
			bar.setColor(a.type().barColor());
			bar.setName(Msg.legacy(barTitle(a, now)));
		}
		bar.setProgress(a.progress(now));
		// Sync players: add new, remove gone (also without a disconnect event)
		List<ServerPlayer> online = server.getPlayerList().getPlayers();
		Set<ServerPlayer> onlineSet = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		onlineSet.addAll(online);
		for (ServerPlayer p : List.copyOf(bar.getPlayers())) {
			if (!onlineSet.contains(p)) bar.removePlayer(p);
		}
		for (ServerPlayer p : online) bar.addPlayer(p);
	}

	private static void removeBar() {
		if (bar == null) return;
		bar.removeAllPlayers();
		bar.setVisible(false);
		bar = null;
	}

	/** For diagnostics and E2E. */
	static String barTitleForDebug() {
		return bar == null ? null : bar.getName().getString();
	}

	/** Interval in seconds for paying owed prizes (the treasury fell short). */
	static final int OWED_INTERVAL_SECONDS = 5;
	private static int lottoSeconds;
	/** Last logged state of "sales paused, treasury does not cover the prizes"; logs only on change. */
	private static Boolean lottoPausedLogged;

	/** At start: migrates legacy pot state once, moves the leftover pot to the treasury, reports refunds. */
	private static void lottoStart(long now) {
		Lottery lotto = EventsFeature.LOTTO;
		Lottery.Migration m = lotto.migrate(now);
		if (m.migrated()) {
			SmpEconomy.LOG.info("[SMPEco] Lottery migrated to numbered tickets: draw #{}, jackpot {}, {} tickets; legacy tickets refunded: {} players, {}",
				lotto.drawId(), Money.format(lotto.jackpot()), lotto.ticketCount(), m.refundedPlayers(), Money.format(m.refunded()));
		}
		if (m.potSwept() > 0) SmpEconomy.LOG.info("[SMPEco] Legacy lottery pot {} moved to the treasury", Money.format(m.potSwept()));
		notifySettled(m.settled());
		lotto.ensurePlanned(now);
	}

	private static void lottoTick(MinecraftServer server, long now) {
		Lottery lotto = EventsFeature.LOTTO;
		EventsConfig cfg = EventsFeature.CONFIG.get();
		if (!lotto.isReady()) return;
		lotto.ensurePlanned(now);
		if (++lottoSeconds % OWED_INTERVAL_SECONDS == 0 && lotto.hasOwed()) notifySettled(lotto.settleOwed());
		boolean covered = lotto.covered();
		if (cfg.lottoEnabled && (lottoPausedLogged == null || lottoPausedLogged == covered)) {
			lottoPausedLogged = !covered;
			if (!covered) SmpEconomy.LOG.warn("[SMPEco] Lottery: ticket sales paused, treasury {} does not cover jackpot + extra prizes + owed prizes + reserve ({})",
				Money.format(lotto.bank().balance()), Money.format(lotto.coverageNeeded()));
			else SmpEconomy.LOG.info("[SMPEco] Lottery: treasury {} covers draw #{} ({}), ticket sales open", Money.format(lotto.bank().balance()), lotto.drawId(),
				Money.format(lotto.coverageNeeded()));
		}
		if (lotto.isDue(now)) {
			if (lotto.shouldSkip()) {
				lotto.skip(now);
				SmpEconomy.LOG.info("[SMPEco] Lottery draw #{} cancelled (no tickets, {}), jackpot stays {}, new slot {}", lotto.drawId(),
					!cfg.lottoEnabled ? "lottery off" : !covered ? "sales paused, treasury does not cover the prizes" : "lottoRolloverWithoutTickets=false",
					Money.format(lotto.jackpot()), Instant.ofEpochMilli(lotto.nextDrawAt()));
			} else {
				drawNow(server, now, null, false);
			}
			return;
		}
		boolean online = !server.getPlayerList().getPlayers().isEmpty();
		// Sales start only once the treasury covers the draw (the announcement stays open until then)
		if (cfg.lottoEnabled && online && covered && lotto.saleAnnouncementPending()) {
			// Shortly before the draw the reminder takes over (otherwise two announcements back to back)
			if (!lotto.inReminderWindow(now)) announceSale(server, Set.of());
			lotto.markSaleAnnounced();
		}
		// Reminder only if tickets can still be bought or exist (a cancelled draw is not announced)
		if (lotto.takeReminder(now) && cfg.lottoEnabled && (covered || lotto.soldCount() > 0)) announceReminder(server, now);
	}

	/**
	 * Immediate admin draw, optionally with given numbers (only with {@code lottoForcedDraws}, publicly
	 * marked, in the admin log). {@code EventsCommands} does the checks (confirmation, cancelled draw).
	 *
	 * @throws IllegalStateException given numbers without {@code lottoForcedDraws}
	 */
	static Lottery.Draw adminDraw(MinecraftServer server, int[] forced) {
		if (forced != null && !EventsFeature.CONFIG.get().lottoForcedDraws) throw new IllegalStateException("lottoForcedDraws is off");
		return drawNow(server, System.currentTimeMillis(), forced, true);
	}

	private static Lottery.Draw drawNow(MinecraftServer server, long now, int[] forced, boolean admin) {
		Lottery lotto = EventsFeature.LOTTO;
		Set<UUID> participants = lotto.participantIds();
		boolean early = lotto.isEarly(now);
		Lottery.Draw d = lotto.draw(now, forced, admin);
		SmpEconomy.LOG.info("[SMPEco] Lottery draw #{}{}: numbers {} jackpot {} {}, sold {}/{} from {} players, paid {}, owed {}, next jackpot {}, next slot {}",
			d.id, d.forced ? " (admin numbers)" : admin ? (early ? " (Admin, early)" : " (Admin)") : "", d.numbers(), Money.format(d.jackpot),
			d.jackpotWon() ? "WON" : "not won", d.sold, d.ticketCount, d.players, Money.format(d.paid()), Money.format(d.owed()),
			Money.format(d.nextJackpot), Instant.ofEpochMilli(lotto.nextDrawAt()));
		for (Lottery.Prize p : d.prizes) {
			if (p.winner != null) SmpEconomy.LOG.info("[SMPEco] Lottery draw #{}: rank {} #{} {} to {} ({} paid, {} owed)", d.id, p.rank, p.number,
				Money.format(p.amount), p.winnerName, Money.format(p.paid), Money.format(p.owed));
		}
		broadcastDraw(server, d, participants);
		Set<UUID> winners = notifyWinners(d);
		if (!server.getPlayerList().getPlayers().isEmpty() && EventsFeature.CONFIG.get().lottoEnabled) {
			if (lotto.covered()) {
				announceSale(server, winners);
				lotto.markSaleAnnounced();
			} else {
				// Sales start follows (lottoTick) once the treasury covers the next draw again
				for (ServerPlayer p : List.copyOf(server.getPlayerList().getPlayers())) {
					if (Settings.get(p, Settings.ANNOUNCEMENTS) || participants.contains(p.getUUID())) {
						Msg.info(p, "§7Tickets for draw #" + lotto.drawId() + " go on sale as soon as the Treasury can cover the prizes.");
					}
				}
			}
		}
		return d;
	}

	/**
	 * "today at 20:00" / "tomorrow at 20:00" / "on Oct 14 at 20:00", based on the stored slot of the running
	 * draw.
	 */
	static String nextDrawText() {
		return drawText(EventsFeature.LOTTO.nextDrawAt(), System.currentTimeMillis());
	}

	static String drawText(long at, long now) {
		if (at <= 0) return "at " + EventsFeature.CONFIG.get().lottoDrawTime;
		java.time.ZonedDateTime when = Instant.ofEpochMilli(at).atZone(TimeUtil.zone());
		java.time.LocalDate today = Instant.ofEpochMilli(now).atZone(TimeUtil.zone()).toLocalDate();
		String time = String.format(java.util.Locale.ROOT, "%02d:%02d", when.getHour(), when.getMinute());
		java.time.LocalDate day = when.toLocalDate();
		if (!day.isAfter(today)) return "today at " + time;
		if (day.equals(today.plusDays(1))) return "tomorrow at " + time;
		return "on " + java.time.format.DateTimeFormatter.ofPattern("MMM d", java.util.Locale.ENGLISH).format(day) + " at " + time;
	}

	/** "Jackpot" or the amount ("$10,000"). */
	static String prizeName(int rank, long amount) {
		return rank == Lottery.JACKPOT ? "Jackpot" : Money.format(amount);
	}

	private static MutableComponent lottoLink(String legacy) {
		return Msg.legacy(legacy).withStyle(s -> s.withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand(CommandRegistry.publicCommand("/lottery")))
			.withHoverEvent(new HoverEvent.ShowText(Msg.legacy("§eOpen the lottery: /lottery"))));
	}

	/**
	 * To everyone with "announcements" and all participants: all winning numbers with winners (or "not
	 * sold"), then whether the jackpot was won or rolls over. Participants without a win get a short note.
	 */
	private static void broadcastDraw(MinecraftServer server, Lottery.Draw d, Set<UUID> participants) {
		if (!EventsFeature.CONFIG.get().lottoEnabled && d.sold == 0) return; // Lottery off and nobody had tickets
		List<Component> lines = new ArrayList<>();
		StringBuilder nums = new StringBuilder();
		for (Lottery.Prize p : d.prizes) nums.append(nums.isEmpty() ? "" : " §8| ").append("§e#").append(p.number);
		lines.add(Msg.prefixed(Msg.legacy("§6§lLottery draw #" + d.id + " §8» §7Winning numbers: " + nums)));
		if (d.forced) lines.add(Msg.legacy("  §c§lADMIN TEST DRAW §8» §cthe winning numbers were set by an admin"));
		else if (d.admin) lines.add(Msg.legacy("  §8(drawn by an admin)"));
		for (Lottery.Prize p : d.prizes) {
			String who = p.winner == null ? "§7not sold" : (p.rank == Lottery.JACKPOT ? "§a§l" : "§a") + p.winnerName;
			String label = p.rank == Lottery.JACKPOT ? "§6§lJackpot " + Msg.moneyStr(p.amount) : "§e" + Money.format(p.amount);
			lines.add(Msg.legacy("  " + label + " §8» §e#" + p.number + " §8» " + who));
		}
		Lottery.Prize jackpot = d.prizes.get(0);
		lines.add(Msg.prefixed(lottoLink(d.jackpotWon()
			? "§a§l" + jackpot.winnerName + " hit the JACKPOT of " + Money.format(jackpot.amount) + "! §7Next jackpot: " + Msg.moneyStr(d.nextJackpot)
			: "§6Jackpot not won – rolls over to §e§l" + Money.format(d.nextJackpot) + "§6!")));
		lines.add(Msg.legacy("  §8" + d.sold + "/" + d.ticketCount + " tickets sold, " + d.players + (d.players == 1 ? " player" : " players")));
		Set<UUID> winners = new java.util.HashSet<>();
		for (Lottery.Prize p : d.prizes) if (p.winner != null) winners.add(p.winner);
		for (ServerPlayer p : List.copyOf(server.getPlayerList().getPlayers())) {
			boolean participant = participants.contains(p.getUUID());
			if (!participant && !Settings.get(p, Settings.ANNOUNCEMENTS)) continue;
			for (Component line : lines) Msg.send(p, line);
			if (participant && !winners.contains(p.getUUID())) Msg.info(p, "§7No luck this time – next draw " + nextDrawText() + ".");
		}
	}

	/**
	 * One line per prize: online right away with title and sound, otherwise as a pending message on next
	 * join. Returns the winners.
	 */
	static Set<UUID> notifyWinners(Lottery.Draw d) {
		Map<UUID, Long> totals = new java.util.LinkedHashMap<>();
		for (Lottery.Prize p : d.prizes) {
			if (p.winner == null) continue;
			totals.merge(p.winner, p.amount, Lottery::saturatingAdd);
			String what = p.rank == Lottery.JACKPOT
				? "§6§lJACKPOT! §7Your ticket §e#" + p.number + " §7won the jackpot of " + Msg.moneyStr(p.amount)
				: "§7Your ticket §e#" + p.number + " §7won the " + Msg.moneyStr(p.amount) + " prize";
			String paid = p.owed <= 0 ? " §8– paid out."
				: p.paid > 0 ? " §8(" + Msg.moneyStr(p.paid) + " §8paid now, §e" + Money.format(p.owed) + " §8follows as soon as the Treasury can cover it)"
				: " §8(the Treasury can't pay it right now – it follows as soon as the Treasury can cover it)";
			Msg.sendOrQueue(p.winner, "§a§lLottery win! " + what + " §7in draw #" + d.id + "!" + paid);
		}
		MinecraftServer server = SmpEconomy.server();
		for (Map.Entry<UUID, Long> e : totals.entrySet()) {
			ServerPlayer online = server == null ? null : server.getPlayerList().getPlayer(e.getKey());
			if (online == null) continue;
			boolean hit = d.jackpotWon() && d.prizes.get(0).winner.equals(e.getKey());
			title(online, Msg.legacy(hit ? "§6§lJACKPOT!" : "§6§lLOTTERY WIN!"), Msg.legacy("§a+" + Money.format(e.getValue())));
			SoundEvent sound = hit ? SoundEvents.UI_TOAST_CHALLENGE_COMPLETE : SoundEvents.PLAYER_LEVELUP;
			Gui.sound(online, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound), 1.0f);
		}
		return totals.keySet();
	}

	/** Reports fully paid prizes and refunds (online right away, otherwise on next join). */
	static void notifySettled(List<Lottery.Owed> settled) {
		for (Lottery.Owed o : settled) {
			if (o.rank == Lottery.REFUND) {
				Msg.sendOrQueue(o.player, "§6The lottery has new rules! §7Your " + o.tickets + (o.tickets == 1 ? " old ticket was" : " old tickets were")
					+ " refunded: " + Msg.moneyStr(o.total) + " §8– §e/lottery");
			} else {
				Msg.sendOrQueue(o.player, "§aLottery: §7the rest of your " + Msg.moneyStr(o.total) + " §7prize from draw #" + o.drawId
					+ " has now been paid out in full.");
			}
			SmpEconomy.LOG.info("[SMPEco] Lottery: owed payout to {} (draw #{}, rank {}) paid in full: {}", o.name, o.drawId, o.rank, Money.format(o.total));
		}
	}

	/** Chat and title to all with "announcements" (no title for {@code noTitle}, e.g. fresh winners). */
	private static void announceSale(MinecraftServer server, Set<UUID> noTitle) {
		Lottery lotto = EventsFeature.LOTTO;
		EventsConfig cfg = EventsFeature.CONFIG.get();
		Component chat = Msg.prefixed(lottoLink("§6Tickets for lottery draw #" + lotto.drawId() + " are on sale! §6§lJackpot: §e§l" + Money.format(lotto.jackpot())
			+ " §8(" + lotto.ticketCount() + " tickets, " + Money.format(cfg.lottoTicketPrice) + " each, draw " + nextDrawText() + ") §8– §e/lottery"));
		Component title = Msg.legacy("§6§lLOTTERY");
		Component subtitle = Msg.legacy("§eJackpot " + Money.format(lotto.jackpot()) + " §7– tickets on sale!");
		for (ServerPlayer p : List.copyOf(server.getPlayerList().getPlayers())) {
			if (!Settings.get(p, Settings.ANNOUNCEMENTS)) continue;
			Msg.send(p, chat);
			if (!noTitle.contains(p.getUUID())) title(p, title, subtitle);
		}
	}

	/** Chat to participants and all with "announcements", title only with "announcements". */
	private static void announceReminder(MinecraftServer server, long now) {
		Lottery lotto = EventsFeature.LOTTO;
		String in = TimeUtil.formatDuration(Math.max(0, lotto.nextDrawAt() - now));
		Component chat = Msg.prefixed(lottoLink("§6Lottery draw in §e" + in + "§6! §6§lJackpot: §e§l" + Money.format(lotto.jackpot()) + " §8(§f"
			+ lotto.soldCount() + "/" + lotto.ticketCount() + " §8sold) §8– §e/lottery"));
		Component title = Msg.legacy("§6§lLOTTERY");
		Component subtitle = Msg.legacy("§eJackpot " + Money.format(lotto.jackpot()) + " §7– draw in " + in + "!");
		Set<UUID> participants = lotto.participantIds();
		for (ServerPlayer p : List.copyOf(server.getPlayerList().getPlayers())) {
			boolean announce = Settings.get(p, Settings.ANNOUNCEMENTS);
			if (!announce && !participants.contains(p.getUUID())) continue;
			Msg.send(p, chat);
			if (announce) title(p, title, subtitle);
		}
	}
}
