package dev.smpeconomy.features.bounty;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.Scheduler;
import dev.smpeconomy.core.Settings;
import dev.smpeconomy.core.test.TestPlayers;

/**
 * Participants (placer, target, killer, contributors) get personal messages, as pending messages when
 * offline. Everyone else gets an announcement if {@link Settings#ANNOUNCEMENTS} is on. Test players never
 * trigger announcements to real players.
 *
 * <p>Contribution notices to the target are throttled, since many small bounties could flood the chat or,
 * offline, push other features' messages out of the 50-entry pending store. Online: one message per {@link
 * BountyConfig#targetNoticeCooldownSeconds}, then one summary. Offline: a single note in the PlayerData blob
 * {@link #OFFLINE_KEY} that becomes one pending message on join.
 */
final class BountyNotify {
	/** Contributions on this offline player since the last join. */
	static final String OFFLINE_KEY = "bounty.notice";
	/** Target to time of the last contribution announcement (spam guard). */
	private static final Map<UUID, Long> LAST_ANNOUNCE = new HashMap<>();
	/** In memory only: online target to last personal message and contributions collected since. */
	private static final Map<UUID, Burst> BURSTS = new HashMap<>();
	/** The IP rule warning is logged once per server run. */
	private static boolean warnedIpRuleBlind;

	static final class Notice {
		int count;
		long amount;
		String lastFrom = "";

		void add(int n, long cents, String from) {
			count = n > Integer.MAX_VALUE - count ? Integer.MAX_VALUE : count + n;
			amount = cents > Money.MAX - amount ? Money.MAX : amount + Math.max(0, cents);
			if (from != null && !from.isEmpty()) lastFrom = from;
		}
	}

	private static final class Burst {
		long lastSentAt;
		Notice pending = new Notice();
		Scheduler.Task flush;
	}

	private BountyNotify() {}

	static List<ServerPlayer> recipients(Collection<? extends ServerPlayer> online, Set<UUID> exclude) {
		List<ServerPlayer> list = new ArrayList<>();
		for (ServerPlayer p : online) {
			if (exclude.contains(p.getUUID())) continue;
			if (Settings.get(p, Settings.ANNOUNCEMENTS)) list.add(p);
		}
		return list;
	}

	/** Whether a contribution may be announced now (amount, interval, no test players). Records the time. */
	static boolean shouldAnnouncePlacement(BountyConfig cfg, UUID contributor, UUID target, long amount, long now) {
		if (!cfg.announce || amount < cfg.announceMinAmount) return false;
		if (TestPlayers.isTestPlayer(contributor) || TestPlayers.isTestPlayer(target)) return false;
		long cooldown = cfg.announceCooldownSeconds * 1000L;
		Long last = LAST_ANNOUNCE.get(target);
		if (last != null && now - last >= 0 && now - last < cooldown) return false;
		LAST_ANNOUNCE.put(target, now);
		if (LAST_ANNOUNCE.size() > 256) LAST_ANNOUNCE.values().removeIf(t -> now - t >= cooldown);
		return true;
	}

	static void placed(MinecraftServer server, UUID contributor, String contributorName, UUID target, String targetName,
		BountyLedger.Placement p, long now) {
		notifyTarget(server, target, contributorName, p.amount(), p.total(), now);
		SmpEconomy.LOG.info("[SMPEco] Bounty: {} places {} on {} (total {})", contributorName, Money.format(p.amount()), targetName, Money.format(p.total()));
		if (server == null || !shouldAnnouncePlacement(Bounties.config(), contributor, target, p.amount(), now)) return;
		Component msg = Msg.prefixed("§c☠ §f" + contributorName + " §7put a " + Msg.moneyStr(p.amount()) + " bounty on §c" + targetName
			+ " §8(total " + Msg.moneyStr(p.total()) + "§8) ")
			.append(Msg.clickRun("§e[View]", "/bounty", "§7Open the bounty list"));
		for (ServerPlayer r : recipients(server.getPlayerList().getPlayers(), Set.of(contributor, target))) Msg.send(r, msg);
	}

	static void paid(MinecraftServer server, UUID killer, String killerName, UUID victim, String victimName, BountyLedger.Payout p) {
		Msg.sendOrQueue(killer, "§aBounty claimed: " + Msg.moneyStr(p.net()) + " for §c" + victimName
			+ (p.fee() > 0 ? " §8(" + Money.format(p.fee()) + " fee to the Treasury)" : ""));
		Msg.sendOrQueue(victim, "§f" + killerName + " §7claimed the " + Msg.moneyStr(p.total()) + " bounty on you.");
		Set<UUID> involved = new HashSet<>();
		involved.add(killer);
		involved.add(victim);
		for (UUID c : p.contributions().keySet()) {
			if (involved.add(c)) Msg.sendOrQueue(c, "§7Your bounty on §c" + victimName + " §7was claimed by §f" + killerName + "§7.");
		}
		SmpEconomy.LOG.info("[SMPEco] Bounty: {} claims {} for {} (fee {})", killerName, Money.format(p.total()), victimName, Money.format(p.fee()));
		if (server == null) return;
		ServerPlayer online = server.getPlayerList().getPlayer(killer);
		if (online != null) {
			Gui.successSound(online);
			Msg.actionBar(online, "§a+" + Money.format(p.net()) + " §7bounty for §c" + victimName);
		}
		if (!Bounties.config().announce || TestPlayers.isTestPlayer(killer) || TestPlayers.isTestPlayer(victim)) return;
		Component msg = Msg.prefixed("§c☠ §f" + killerName + " §7took out §c" + victimName + " §7and claimed a " + Msg.moneyStr(p.total()) + " bounty!");
		for (ServerPlayer r : recipients(server.getPlayerList().getPlayers(), involved)) Msg.send(r, msg);
	}

	/** {@code total} is the current bounty. One contribution names the player; several are summarized. */
	static String targetText(Notice n, long total, boolean more) {
		if (n.count <= 1) {
			return "§cA bounty was placed on you: " + Msg.moneyStr(n.amount) + " by §f" + n.lastFrom
				+ " §8(total " + Msg.moneyStr(total) + "§8)";
		}
		return "§c" + Msg.number(n.count) + (more ? " more" : "") + " bounties were placed on you: " + Msg.moneyStr(n.amount)
			+ " §8(total " + Msg.moneyStr(total) + "§8)";
	}

	static void notifyTarget(MinecraftServer server, UUID target, String from, long amount, long total, long now) {
		ServerPlayer online = server == null ? null : server.getPlayerList().getPlayer(target);
		if (online == null) {
			queueOffline(target, 1, amount, from);
			return;
		}
		long cooldownMs = Bounties.config().targetNoticeCooldownSeconds * 1000L;
		String text = placedOnline(target, from, amount, total, now, cooldownMs);
		if (text != null) {
			Msg.info(online, text);
			return;
		}
		Burst b = BURSTS.get(target);
		if (b != null && (b.flush == null || b.flush.isCancelled())) {
			long waitMs = Math.max(0, Math.min(cooldownMs, b.lastSentAt + cooldownMs - now));
			b.flush = Scheduler.runLater((int) ((waitMs + 49) / 50), () -> flush(target));
		}
	}

	/**
	 * Testable core for an online target: the text to send now (first message, or cooldown over with nothing
	 * collected), otherwise null and the contribution is collected for {@link #takeOnlineSummary}.
	 */
	static String placedOnline(UUID target, String from, long amount, long total, long now, long cooldownMs) {
		Burst b = BURSTS.computeIfAbsent(target, k -> new Burst());
		boolean free = b.pending.count == 0 && (b.lastSentAt == 0 || now - b.lastSentAt >= cooldownMs || now < b.lastSentAt);
		if (!free) {
			b.pending.add(1, amount, from);
			return null;
		}
		b.lastSentAt = now;
		Notice single = new Notice();
		single.add(1, amount, from);
		if (BURSTS.size() > 256) {
			BURSTS.entrySet().removeIf(e -> e.getValue().pending.count == 0 && e.getValue().flush == null && now - e.getValue().lastSentAt >= cooldownMs);
		}
		return targetText(single, total, false);
	}

	/** Restarts the cooldown. Null if nothing was collected. */
	static String takeOnlineSummary(UUID target, long total, long now) {
		Burst b = BURSTS.get(target);
		if (b == null || b.pending.count == 0) return null;
		Notice n = b.pending;
		b.pending = new Notice();
		b.lastSentAt = now;
		return total > 0 ? targetText(n, total, true) : null;
	}

	/** Cooldown over: sends the summary, or moves it to the offline note if the target has left. */
	private static void flush(UUID target) {
		Burst b = BURSTS.get(target);
		if (b == null) return;
		b.flush = null;
		MinecraftServer server = SmpEconomy.server();
		ServerPlayer online = server == null ? null : server.getPlayerList().getPlayer(target);
		if (online == null) {
			if (b.pending.count > 0) queueOffline(target, b.pending.count, b.pending.amount, b.pending.lastFrom);
			BURSTS.remove(target);
			return;
		}
		String text = takeOnlineSummary(target, Bounties.ready() ? Bounties.LIVE.total(target) : 0, System.currentTimeMillis());
		if (text != null) Msg.info(online, text);
	}

	/** Goes into players.json, no pending message of its own. */
	static void queueOffline(UUID target, int count, long amount, String from) {
		Notice n = PlayerDataService.getBlob(target, OFFLINE_KEY, Notice.class);
		if (n == null) n = new Notice();
		n.add(count, amount, from);
		PlayerDataService.setBlob(target, OFFLINE_KEY, n);
	}

	static Notice offlineNotice(UUID target) {
		return PlayerDataService.getBlob(target, OFFLINE_KEY, Notice.class);
	}

	/**
	 * Server thread. Stores the note as one pending message, which core delivers a second later in the "while
	 * you were away" block. If no bounty is left (an admin refunded it), only clears the note.
	 */
	static void onJoin(ServerPlayer player) {
		if (player == null || !Bounties.ready()) return;
		warnIfIpRuleBlind(player);
		UUID id = player.getUUID();
		if (offlineNotice(id) != null) queueJoinSummary(id, Bounties.LIVE.total(id));
	}

	static void queueJoinSummary(UUID id, long total) {
		Notice n = offlineNotice(id);
		if (n == null) return;
		PlayerDataService.setBlob(id, OFFLINE_KEY, null);
		if (n.count > 0 && total > 0) PlayerDataService.addPendingMessage(id, targetText(n, total, false));
	}

	/** Scheduler tasks are dropped on stop, so move unsummarized contributions to the offline note. */
	static void onServerStopping() {
		BURSTS.forEach((target, b) -> {
			if (b.pending.count > 0) queueOffline(target, b.pending.count, b.pending.amount, b.pending.lastFrom);
			if (b.flush != null) b.flush.cancel();
		});
		BURSTS.clear();
	}

	static void forget(UUID id) {
		Burst b = BURSTS.remove(id);
		if (b != null && b.flush != null) b.flush.cancel();
		LAST_ANNOUNCE.remove(id);
	}

	/**
	 * Once per server run: warns that the IP rule cannot see anything because a player arrived through a
	 * tunnel, proxy or LAN address, leaving only {@link BountyConfig#minKillerPlaytimeMinutes} as protection.
	 */
	private static void warnIfIpRuleBlind(ServerPlayer player) {
		BountyConfig cfg = Bounties.config();
		if (warnedIpRuleBlind || !cfg.blockSameIp || TestPlayers.isTestPlayer(player.getUUID())) return;
		String raw;
		try {
			if (player.connection == null) return;
			raw = player.getIpAddress();
		} catch (RuntimeException e) {
			return;
		}
		if (BountyFeature.publicIp(raw) != null) return;
		warnedIpRuleBlind = true;
		SmpEconomy.LOG.warn("[SMPEco] Bounty: {} connects from non-public address {} (tunnel or proxy without PROXY protocol?), so rule "
				+ "blockSameIp cannot detect alts. Protection against fresh alts: minKillerPlaytimeMinutes={}{}",
			player.getGameProfile().name(), raw, cfg.minKillerPlaytimeMinutes, cfg.minKillerPlaytimeMinutes == 0 ? " (OFF!)" : "");
	}
}
