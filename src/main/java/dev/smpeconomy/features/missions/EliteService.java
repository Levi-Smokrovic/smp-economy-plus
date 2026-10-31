package dev.smpeconomy.features.missions;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.BasePrices;
import dev.smpeconomy.core.Bots;
import dev.smpeconomy.core.CrateKeys;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.MobOrigin;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.PriceGuard;
import dev.smpeconomy.core.Services;
import dev.smpeconomy.core.Settings;
import dev.smpeconomy.core.TimeUtil;
import dev.smpeconomy.core.test.TestPlayers;
import dev.smpeconomy.features.missions.EliteConfig.Elite;
import dev.smpeconomy.features.missions.MissionStore.EliteDay;
import dev.smpeconomy.features.missions.MissionStore.EliteProgress;
import dev.smpeconomy.features.missions.MissionStore.PlayerState;

/**
 * Daily Elite (all on the server thread, time as a parameter for selftests):
 * <ul>
 *   <li>Exactly one mission per day (server time zone), the same for all players: deterministic from the pool ({@link
 *   EliteConfig#pick}), pinned in the store on the day's first access, so reloads and restarts don't change the
 *   day.</li>
 *   <li>No accepting needed: counting starts at the start of the day and progress belongs to the day (midnight =
 *   0).</li>
 *   <li>Each player can finish it once per day; {@link EliteProgress#done} is set BEFORE the payout.</li>
 *   <li>Hunt: only kill events ({@code AFTER_KILLED_OTHER_ENTITY}, the player as cause, so projectiles too, pets not)
 *   with an origin check. Spawner, spawn egg, bred, summoned and respawned mobs don't count AT ALL (not even partly as
 *   on the mission board), nothing in creative or spectator.</li>
 *   <li>Raid: win as Hero of the Village ({@code Raid#tick}, mixin) of a raid that began at level {@code minRaidLevel},
 *   with enough own raider kills and in the village ({@link EliteRaids}).</li>
 *   <li>Delivery: all items at once (unmodified, no merchant goods), money at most {@link PriceGuard#maxNpcOrderPrice}
 *   × amount, recomputed at hand-in; no shop items.</li>
 * </ul>
 * Rewards are new money like on the mission board (not the treasury), limited to once per player per day.
 */
final class EliteService {
	static final String TITLE = "§d§lDAILY ELITE";
	/** MIN means the server just started: no broadcast, the join hint is enough. */
	private static long lastTickDay = Long.MIN_VALUE;
	/** Once a minute ({@link EliteRaids#prune}). */
	private static long lastRaidPrune;

	private EliteService() {}

	record Today(long day, Elite elite, boolean override) {
		String id() {
			return elite.id();
		}
	}

	static long epochDay(long now) {
		return TimeUtil.dateOf(now).toEpochDay();
	}

	/** DST safe. */
	static long dayStart(long day) {
		return LocalDate.ofEpochDay(day).atStartOfDay(TimeUtil.zone()).toInstant().toEpochMilli();
	}

	/** Exclusive: the next midnight. */
	static long dayEnd(long day) {
		return dayStart(day + 1);
	}

	/**
	 * Null when turned off or the pool is empty. A new day gets picked on first access and pinned in the store; times
	 * before the pinned day (clock turned back) are only computed.
	 */
	static Today today(long now) {
		if (!EliteConfig.enabled()) return null;
		long day = epochDay(now);
		EliteDay g = MissionStore.eliteDay();
		if (g.day == day) {
			Elite pinned = g.elite();
			if (pinned != null) return new Today(day, pinned, g.override);
		}
		Elite pick = EliteConfig.pick(day);
		if (pick == null) return null;
		if (day < g.day) return new Today(day, pick, false);
		g.set(day, pick, false);
		MissionStore.markDirty();
		Elite pinned = g.elite();
		return pinned == null ? null : new Today(day, pinned, false);
	}

	/** Until midnight. Days already finished stay finished. */
	static Today override(Elite elite, long now) {
		long day = epochDay(now);
		MissionStore.eliteDay().set(day, elite, true);
		MissionStore.markDirty();
		return today(now);
	}

	static Today clearOverride(long now) {
		EliteDay g = MissionStore.eliteDay();
		g.day = Long.MIN_VALUE;
		g.def = null;
		g.override = false;
		g.compiled = null;
		g.compileFailed = false;
		MissionStore.markDirty();
		return today(now);
	}

	/** A new day means 0 and not done; another mission on the same day means progress 0. */
	static EliteProgress progress(PlayerState s, Today t) {
		EliteProgress e = s.elite;
		if (e.day != t.day()) {
			e.day = t.day();
			e.missionId = t.id();
			e.killMilli = 0;
			e.progress = 0;
			e.done = false;
			e.doneAt = 0;
			e.paid = 0;
			MissionStore.markDirty();
		} else if (!t.id().equals(e.missionId)) {
			e.missionId = t.id();
			e.killMilli = 0;
			e.progress = 0;
			MissionStore.markDirty();
		}
		return e;
	}

	static EliteProgress peekProgress(UUID player, Today t) {
		PlayerState s = MissionStore.peek(player);
		if (s == null || s.elite == null || s.elite.day != t.day()) return null;
		return s.elite;
	}

	static boolean doneToday(UUID player, Today t) {
		EliteProgress e = peekProgress(player, t);
		return e != null && e.done;
	}

	static long progressValue(UUID player, Today t) {
		EliteProgress e = peekProgress(player, t);
		if (e == null) return 0;
		if (e.done) return t.elite().amount();
		if (!t.id().equals(e.missionId)) return 0;
		return Math.min(t.elite().amount(), t.elite().type() == EliteConfig.Type.KILL ? Math.max(0, e.killMilli) / 1000 : Math.max(0, e.progress));
	}

	/** {@code maxNpcOrderPrice × amount}; 0 means not deliverable right now. */
	static long deliveryCap(UUID player, Elite el) {
		if (el.type() != EliteConfig.Type.DELIVER || el.item() == null) return 0;
		long market = Services.marketValue(el.item()).orElse(0);
		long cap = PriceGuard.maxNpcOrderPrice(player, el.item(), market);
		if (cap <= 0) return 0;
		long total = MissionService.safeMultiply(cap, el.amount());
		return total < 0 ? Long.MAX_VALUE : total;
	}

	static long payout(UUID player, Elite el) {
		long reward = el.def().rewardCents;
		if (el.type() != EliteConfig.Type.DELIVER) return reward;
		return Math.min(reward, deliveryCap(player, el));
	}

	/** Never for shop items, in case the shop was extended after the config loaded. */
	static int keys(Elite el) {
		if (el.type() == EliteConfig.Type.DELIVER && el.item() != null && BasePrices.shopBuyPrice(el.item()).isPresent()) return 0;
		return EliteConfig.keys(el.def());
	}

	/**
	 * Only a natural mob counts: every origin tag ({@link MobOrigin}), spawner included, rules it out. The mission
	 * board counts spawner mobs partly, the Daily Elite not at all.
	 */
	static boolean naturalKill(Set<String> tags) {
		return !MobOrigin.isArtificial(tags) && !MobOrigin.fromSpawner(tags);
	}

	static void onMobKilled(ServerPlayer player, LivingEntity killed, long now) {
		if (player.isCreative() || player.isSpectator()) return;
		Today t = today(now);
		if (t == null || !t.elite().countsKill(killed.getType())) return;
		if (!naturalKill(killed.getTags())) return;
		PlayerState s = MissionStore.state(player.getUUID());
		EliteProgress e = progress(s, t);
		if (e.done) return;
		long amount = t.elite().amount();
		e.killMilli = Math.min(amount * 1000L, Math.max(0, e.killMilli) + 1000);
		MissionStore.markDirty();
		if (e.killMilli / 1000 >= amount) complete(player.getUUID(), player, s, e, t, payout(player.getUUID(), t.elite()), now, false);
		else progressActionbar(player, t, e.killMilli / 1000, now);
	}

	/**
	 * Raid won (mixin on {@code Raid#tick}, {@link EliteRaids}): {@code player} is a Hero of the Village. Counts only
	 * if the raid began at {@code minRaidLevel} or higher (start level, not stacked mid-raid), the player killed enough
	 * raiders personally ({@link EliteRaids#requiredKills}) and is in the village at the win (at most 96 blocks from
	 * the center).
	 */
	static void onRaidWon(ServerPlayer player, EliteRaids.Outcome o, long now) {
		if (player.isCreative() || player.isSpectator()) return;
		Today t = today(now);
		if (t == null || t.elite().type() != EliteConfig.Type.RAID || doneToday(player.getUUID(), t)) return;
		EliteConfig.EliteDef d = t.elite().def();
		if (o.level() < d.minRaidLevel) {
			Msg.info(player, o.startLevel() <= 0
				? "§7Daily Elite: This raid wasn't tracked from the first wave – it doesn't count."
				: "§7Daily Elite: This raid started at level §f" + o.level() + "§7 – only raids from level §f" + d.minRaidLevel
					+ "§7 count (Bad Omen during the raid doesn't raise the level).");
			return;
		}
		int need = EliteRaids.requiredKills(d, o.totalKills());
		if (o.kills() < need) {
			Msg.info(player, "§7Daily Elite: Not enough raider kills in this raid – you have §f" + o.kills() + "§7, you need §f"
				+ need + "§7 (at least " + d.minRaidKills + " and " + Math.round(d.minRaidKillShare * 100) + "% of all player kills).");
			return;
		}
		if (!o.inArea()) {
			Msg.info(player, "§7Daily Elite: You weren't in the village when the raid was won – only players who stay until the end count.");
			return;
		}
		PlayerState s = MissionStore.state(player.getUUID());
		EliteProgress e = progress(s, t);
		if (e.done) return;
		e.progress = Math.min(t.elite().amount(), Math.max(0, e.progress) + 1);
		MissionStore.markDirty();
		if (e.progress >= t.elite().amount()) complete(player.getUUID(), player, s, e, t, payout(player.getUUID(), t.elite()), now, false);
		else progressActionbar(player, t, e.progress, now);
	}

	enum DeliverStatus { OK, DISABLED, WRONG_TYPE, CHANGED, DONE, BLOCKED, NO_PRICE, NONE, ONLY_MODIFIED, NOT_ENOUGH, PAYMENT_FAILED }

	record DeliverResult(DeliverStatus status, int have, int need, long paid) {
		static DeliverResult of(DeliverStatus status) {
			return new DeliverResult(status, 0, 0, 0);
		}
	}

	/**
	 * Hands in the Daily Elite delivery: exactly {@code amount} unmodified items at once, or nothing, pays {@link
	 * #payout} and completes. If the credit fails the items go back and nothing is done.
	 */
	static DeliverResult deliver(ServerPlayer player, long now) {
		return deliver(player, now, null);
	}

	/**
	 * As above, but only if the Daily Elite is still {@code expected} (day, mission, item, amount, as the confirmation
	 * dialog showed them). If it changed since (midnight, admin {@code set}/{@code clear}), nothing gets taken: {@link
	 * DeliverStatus#CHANGED}.
	 */
	static DeliverResult deliver(ServerPlayer player, long now, Today expected) {
		Today t = today(now);
		if (expected != null && !sameDelivery(expected, t)) return DeliverResult.of(DeliverStatus.CHANGED);
		DeliverResult check = check(player, t);
		if (check.status() != DeliverStatus.OK) return check;
		UUID id = player.getUUID();
		Elite el = t.elite();
		Item item = el.item();
		int need = el.amount();
		long pay = check.paid();
		int removed = Items.remove(player, stack -> stack.is(item) && Items.isPristine(stack), need);
		if (removed < need) {
			if (removed > 0) Items.giveOrDrop(player, new ItemStack(item, removed));
			return new DeliverResult(DeliverStatus.NOT_ENOUGH, removed, need, 0);
		}
		DataStore.requestPlayerSave(player); // items leave the inventory, so save the player too (anti-dupe on crash)
		PlayerState s = MissionStore.state(id);
		EliteProgress e = progress(s, t);
		e.progress = need;
		MissionStore.markDirty();
		if (!complete(id, player, s, e, t, pay, now, true)) {
			Items.giveOrDrop(player, new ItemStack(item, removed));
			e.progress = 0;
			MissionStore.markDirty();
			SmpEconomy.LOG.warn("[SMPEco] missions: Daily Elite delivery by {} failed, {} items returned", player.getPlainTextName(), removed);
			return DeliverResult.of(DeliverStatus.PAYMENT_FAILED);
		}
		return new DeliverResult(DeliverStatus.OK, need, need, pay);
	}

	static boolean sameDelivery(Today shown, Today now) {
		return shown != null && now != null && shown.day() == now.day() && shown.id().equals(now.id())
			&& shown.elite().type() == now.elite().type() && shown.elite().item() == now.elite().item()
			&& shown.elite().amount() == now.elite().amount();
	}

	/**
	 * Can the player hand in now, without changing anything? OK carries amount and payout, otherwise the reason. The
	 * GUI asks before the confirmation dialog, {@link #deliver} asks again on confirm.
	 */
	static DeliverResult check(ServerPlayer player, Today t) {
		if (t == null) return DeliverResult.of(DeliverStatus.DISABLED);
		Elite el = t.elite();
		if (el.type() != EliteConfig.Type.DELIVER) return DeliverResult.of(DeliverStatus.WRONG_TYPE);
		UUID id = player.getUUID();
		if (doneToday(id, t)) return DeliverResult.of(DeliverStatus.DONE);
		if (Items.isCreativeOrSpectator(player)) return DeliverResult.of(DeliverStatus.BLOCKED);
		long pay = payout(id, el);
		if (pay <= 0) return DeliverResult.of(DeliverStatus.NO_PRICE);
		Item item = el.item();
		int need = el.amount();
		int have = Items.countPristine(player, item);
		if (have < need) {
			DeliverStatus st = have == 0 && Items.count(player, stack -> stack.is(item)) > 0 ? DeliverStatus.ONLY_MODIFIED
				: have == 0 ? DeliverStatus.NONE : DeliverStatus.NOT_ENOUGH;
			return new DeliverResult(st, have, need, 0);
		}
		if (pay > Money.MAX - EconomyService.balance(id)) return DeliverResult.of(DeliverStatus.PAYMENT_FAILED);
		return new DeliverResult(DeliverStatus.OK, have, need, pay);
	}

	static String deliverMessage(DeliverResult r, Today t) {
		String item = t == null || t.elite().item() == null ? "Items" : t.elite().def().name;
		return switch (r.status()) {
			case OK -> "Daily Elite handed in.";
			case DISABLED -> "There's no Daily Elite today.";
			case WRONG_TYPE -> "Today's Daily Elite isn't a delivery.";
			case CHANGED -> "The Daily Elite has changed – nothing was handed in. Check out the new one.";
			case DONE -> "You've already completed today's Daily Elite – there's a new one tomorrow.";
			case BLOCKED -> "You can't hand in items in Creative or Spectator mode.";
			case NO_PRICE -> "This item has no market value right now – try again later.";
			case NONE -> "You don't have any matching items in your inventory (" + item + ").";
			case ONLY_MODIFIED -> "Only unmodified items count (no custom names, enchantments or damage, no Trader goods).";
			case NOT_ENOUGH -> "You need §f" + r.need() + "§7 unmodified items at once (in inventory: §f" + r.have() + "§7).";
			case PAYMENT_FAILED -> "Payment not possible (balance limit) – nothing handed in.";
		};
	}

	/**
	 * Completes today's Daily Elite and pays once: {@link EliteProgress#done} is set before the credit, a
	 * second call pays nothing. With {@code atomic} (delivery), if the credit fails the day stays open (returns false,
	 * the caller returns the items); otherwise it stays done (error in the log).
	 */
	static boolean complete(UUID id, ServerPlayer online, PlayerState s, EliteProgress e, Today t, long payout, long now, boolean atomic) {
		if (e.done || e.day != t.day()) return false;
		e.done = true;
		e.doneAt = now;
		MissionStore.markDirty();
		long paid = 0;
		String failure = null;
		if (payout > 0) {
			EconomyService.Result r = EconomyService.deposit(id, payout);
			if (r.ok()) {
				paid = payout;
			} else {
				SmpEconomy.LOG.error("[SMPEco] missions: Daily Elite reward {} for {} not credited: {}", payout, id, r);
				if (atomic) {
					e.done = false;
					e.doneAt = 0;
					return false;
				}
				failure = r.message();
			}
		}
		e.paid = paid;
		s.eliteCompleted++;
		s.eliteEarned = MissionService.safeAdd(s.eliteEarned, paid);
		s.earnedCents = MissionService.safeAdd(s.earnedCents, paid);
		Elite el = t.elite();
		int keys = keys(el);
		String keyTier = EliteConfig.keyTier(el.def());
		if (keys > 0) CrateKeys.give(id, keyTier, keys);
		String reward = rewardText(paid, keys, keyTier) + (failure == null ? "" : " §c(" + failure + ")");
		SmpEconomy.LOG.info("[SMPEco] Daily Elite done: {} - {} (+{} cents, {} keys)", EconomyService.nameOrUnknown(id), el.id(), paid, keys);
		String text = "§d§lDaily Elite complete: §f" + el.def().name + "§d! §7Reward: " + reward;
		if (online != null && !Bots.isBot(online)) {
			Msg.success(online, text);
			title(online, Msg.legacy("§d§lCOMPLETE!"), Msg.legacy("§fDaily Elite §8» " + shortReward(paid, keys, keyTier)));
			Gui.sound(online, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE), 1f);
		} else {
			Msg.sendOrQueue(id, text);
		}
		if (EliteConfig.get().root().announceComplete) announceComplete(id, el, paid);
		return true;
	}

	private static void announceComplete(UUID id, Elite el, long paid) {
		MinecraftServer server = SmpEconomy.server();
		if (server == null || TestPlayers.isTestPlayer(id)) return;
		String name = EconomyService.nameOrUnknown(id);
		Component title = Msg.legacy(TITLE);
		Component subtitle = Msg.legacy("§f" + name + " §7did it!");
		Component chat = Msg.prefixed("§d" + name + " §7completed the Daily Elite §f" + el.def().name + "§7!"
			+ (paid > 0 ? " §8(+" + Money.format(paid) + ")" : ""));
		for (ServerPlayer p : List.copyOf(server.getPlayerList().getPlayers())) {
			if (Bots.isBot(p) || p.getUUID().equals(id) || !Settings.get(p, Settings.ANNOUNCEMENTS)) continue;
			p.sendSystemMessage(chat);
			title(p, title, subtitle);
			Gui.sound(p, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE), 1.2f);
		}
	}

	private static void title(ServerPlayer p, Component title, Component subtitle) {
		p.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20));
		p.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
		p.connection.send(new ClientboundSetTitleTextPacket(title));
	}

	static String rewardText(long cents, int keys, String keyTier) {
		StringBuilder sb = new StringBuilder();
		if (cents > 0) sb.append(Msg.moneyStr(cents));
		if (keys > 0) {
			if (!sb.isEmpty()) sb.append(" + ");
			sb.append("§d").append(keys).append("× ").append(CrateKeys.label(keyTier)).append(" Key§7");
		}
		return sb.isEmpty() ? "§8–" : sb.toString();
	}

	private static String shortReward(long cents, int keys, String keyTier) {
		String money = cents > 0 ? "§a+" + Money.format(cents) : "";
		String key = keys > 0 ? "§d+" + keys + " " + CrateKeys.label(keyTier) + " Key" : "";
		return money.isEmpty() ? key : key.isEmpty() ? money : money + " " + key;
	}

	private static void progressActionbar(ServerPlayer player, Today t, long progress, long now) {
		if (!MissionConfig.get().root().progressActionbar) return;
		MissionService.actionbar(player, "§d§lDaily Elite §8» §f" + t.elite().def().name + " §7" + progress + "/" + t.elite().amount(), now);
	}

	/** Server start: no midnight broadcast for the running day, players get the join hint. */
	static void resetRuntime() {
		lastTickDay = Long.MIN_VALUE;
	}

	/** Every second: on a new day, title + chat to all online players (title only with the "announcements" setting). */
	static void tick(MinecraftServer server, long now) {
		if (Math.abs(now - lastRaidPrune) >= 60_000L) {
			lastRaidPrune = now;
			EliteRaids.prune(now);
		}
		long day = epochDay(now);
		if (lastTickDay == Long.MIN_VALUE || day <= lastTickDay) {
			if (lastTickDay == Long.MIN_VALUE) lastTickDay = day;
			return;
		}
		lastTickDay = day;
		Today t = today(now);
		if (t == null || !EliteConfig.get().root().announceStart || server == null) return;
		for (ServerPlayer p : List.copyOf(server.getPlayerList().getPlayers())) {
			if (Bots.isBot(p)) continue;
			announceStart(p, t, now, Settings.get(p, Settings.ANNOUNCEMENTS));
		}
		SmpEconomy.LOG.info("[SMPEco] new Daily Elite ({}): {}", LocalDate.ofEpochDay(day), t.id());
	}

	/** Only if not finished today. */
	static void joinHint(MinecraftServer server, UUID id, long now) {
		if (server == null || !EliteConfig.get().root().announceStart) return;
		ServerPlayer p = server.getPlayerList().getPlayer(id);
		if (p == null || Bots.isBot(p)) return;
		Today t = today(now);
		if (t == null) return;
		PlayerState s = MissionStore.peek(id);
		if (s != null && s.elite.announcedDay == t.day()) return;
		announceStart(p, t, now, false);
	}

	static void announceStart(ServerPlayer p, Today t, long now, boolean withTitle) {
		PlayerState s = MissionStore.state(p.getUUID());
		s.elite.announcedDay = t.day();
		MissionStore.markDirty();
		if (doneToday(p.getUUID(), t)) return;
		Elite el = t.elite();
		long pay = payout(p.getUUID(), el);
		int keys = keys(el);
		String keyTier = EliteConfig.keyTier(el.def());
		if (withTitle) {
			title(p, Msg.legacy("§d§lNEW DAILY ELITE"), Msg.legacy("§f" + el.def().name + " §8» " + shortReward(pay, keys, keyTier)));
			Gui.sound(p, SoundEvents.NOTE_BLOCK_BELL, 0.8f);
		}
		p.sendSystemMessage(Msg.prefixed(Msg.legacy("§d§lDaily Elite: §f" + el.def().name + " §8– §7" + el.def().description + " §8(")
			.append(Msg.legacy(rewardText(pay, keys, keyTier)))
			.append(Msg.legacy("§8, " + TimeUtil.formatDuration(dayEnd(t.day()) - now) + " left) "))
			.append(Msg.clickRun("§e[View]", "/missions", "§7Open the Mission Board"))));
	}

	static long millisUntilReset(long now) {
		return dayEnd(epochDay(now)) - now;
	}

	static String dayLabel(long day) {
		return LocalDate.ofEpochDay(day).toString();
	}
}
