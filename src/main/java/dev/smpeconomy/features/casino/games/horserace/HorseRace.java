package dev.smpeconomy.features.casino.games.horserace;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Items;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.Bots;
import dev.smpeconomy.core.ConfigManager;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.Scheduler;
import dev.smpeconomy.core.Settings;
import dev.smpeconomy.core.TimeUtil;
import dev.smpeconomy.core.test.TestPlayers;
import dev.smpeconomy.features.casino.Casino;
import dev.smpeconomy.features.casino.CasinoConfig;
import dev.smpeconomy.features.casino.games.horserace.HorseData.Bet;
import dev.smpeconomy.features.casino.games.horserace.HorseData.Pending;
import dev.smpeconomy.features.casino.games.horserace.HorseData.Race;

/**
 * One global race that everybody bets on. A card starts {@link HorseConfig#bettingWindowSeconds} after its first bet, at
 * most one race per {@link HorseConfig#raceIntervalSeconds}. The winner is drawn and every payout stored when the race starts; the chest
 * track animation afterwards is display only. Bets are paid when the winner crosses the line, also for players who closed
 * the window or left. Server stop/start: a decided race is paid, bets on a card that has not started are refunded.
 */
public final class HorseRace {
	public static final String GAME_ID = "horserace";
	static final String LABEL = "§6Horse Race";
	static final ConfigManager.Config<HorseConfig> CONFIG = ConfigManager.register("casino_horserace", HorseConfig.class, HorseConfig::new, HorseConfig::validate);
	static final DataStore<HorseData> STORE = new DataStore<>("casino_horserace.json", HorseData.class, HorseData::new);
	/** Runs before the core's stop handler, which saves all stores right after the features' onServerStopping. */
	private static final Identifier STOP_PHASE = Identifier.fromNamespaceAndPath(SmpEconomy.MOD_ID, "casino_horserace_settle");

	static final RaceEngine LIVE = new RaceEngine(STORE::get, HorseRace::persist, CONFIG::get, Casino.RNG, System::currentTimeMillis,
		TimeUtil::today, new LiveEvents());

	private static Scheduler.Task ticker;
	private static Scheduler.Task pendingRetry;
	private static boolean registered;

	static {
		STORE.onLoad(HorseData::fixNulls);
	}

	private HorseRace() {}

	public static void register() {
		if (registered) return;
		registered = true;
		// the casino games see the race's reservation (shared cover) and its losses (shared daily limit, admin reset)
		Casino.registerBook(LIVE);
		HorseCommands.register();
		HorseSelfTests.register();
		TestPlayers.onCleanup(LIVE::purge);
		ServerLifecycleEvents.SERVER_STARTED.register(HorseRace::onStarted);
		ServerLifecycleEvents.SERVER_STOPPING.addPhaseOrdering(STOP_PHASE, Event.DEFAULT_PHASE);
		ServerLifecycleEvents.SERVER_STOPPING.register(STOP_PHASE, HorseRace::onStopping);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (Bots.isBot(handler.getPlayer())) return;
			UUID id = handler.getPlayer().getUUID();
			Scheduler.runLater(40, () -> {
				if (STORE.isLoaded()) LIVE.retryPending(id);
			});
		});
	}

	/** Group commit with economy/treasury (written at the next save point, crash = all together rolled back). */
	static void persist() {
		STORE.markDirty();
		DataStore.requestFlush();
	}

	static HorseData data() {
		return STORE.get();
	}

	static HorseConfig cfg() {
		return CONFIG.get();
	}

	private static void onStarted(MinecraftServer server) {
		if (!STORE.isLoaded()) return; // core start failed
		// crash during a race: the outcome was stored → pay it now; bets on a card that never started → refund
		int n = LIVE.settleOpen("server restart");
		if (n > 0) SmpEconomy.LOG.info("[SMPEco] Horse race: {} open bet(s) settled after restart", n);
		LIVE.retryPending(null);
		LIVE.card();
		ticker = Scheduler.runRepeating(1, 1, HorseRace::tick);
		pendingRetry = Scheduler.runRepeating(1200, 1200, () -> LIVE.retryPending(null));
	}

	private static void onStopping(MinecraftServer server) {
		if (ticker != null) ticker.cancel();
		if (pendingRetry != null) pendingRetry.cancel();
		ticker = null;
		pendingRetry = null;
		for (HorseRaceGui g : List.copyOf(HorseRaceGui.OPEN)) g.forget();
		if (!STORE.isLoaded()) return;
		int n = LIVE.settleOpen("server stop");
		if (n > 0) SmpEconomy.LOG.info("[SMPEco] Horse race: {} open bet(s) settled on stop", n);
	}

	/** Every tick: start/settle the race when due, then update the open windows. */
	private static void tick() {
		if (!STORE.isLoaded()) return;
		long now = LIVE.clock.getAsLong();
		LIVE.card();
		LIVE.startIfDue(now);
		LIVE.finishIfDue(now);
		for (HorseRaceGui g : List.copyOf(HorseRaceGui.OPEN)) g.update(now, false);
	}

	/** Bet from a player (GUI or command): places it and tells the player. true = placed. */
	static boolean placeBet(ServerPlayer player, int horse, long stake) {
		RaceEngine.Placed p = LIVE.place(player.getUUID(), player.getGameProfile().name(), horse, stake);
		if (!p.ok()) {
			Msg.error(player, p.error());
			Gui.failSound(player);
			return false;
		}
		Bet b = p.bet();
		Race r = data().race;
		long in = Math.max(0, r.closesAt - LIVE.clock.getAsLong());
		Msg.success(player, "Bet placed: " + Msg.moneyStr(b.stake) + " §aon " + horseName(b.horse) + " §8(" + HorseMath.formatOdds(b.odds)
			+ ", win " + Money.format(HorseMath.payout(b.stake, b.odds)) + ")§a. Race #" + r.id + " starts in " + seconds(in) + ".");
		Gui.sound(player, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.HORSE_AMBIENT), 1.1f);
		Gui.successSound(player);
		return true;
	}

	static String seconds(long ms) {
		long s = (ms + 999) / 1000;
		return s >= 60 ? s / 60 + ":" + String.format(java.util.Locale.ROOT, "%02d", s % 60) + " min" : s + " s";
	}

	/** "§f#3 §cCopper Comet" */
	static String horseName(int h) {
		return "§f#" + (h + 1) + " " + HorseRaceGui.COLORS[h] + HorseRaceGui.NAMES[h];
	}

	public static GuiElementBuilder hubButton(ServerPlayer player) {
		List<String> lore = new ArrayList<>(List.of(
			"§76 horses, odds up to about ×20.",
			"§7Everyone bets on the same race –",
			"§7one every ~2 minutes.",
			"§8/horserace"));
		if (STORE.isLoaded()) {
			Race r = data().race;
			if (r != null && HorseData.RUNNING.equals(r.state)) lore.add("§eA race is running right now!");
			else if (r != null && r.closesAt > 0) lore.add("§eNext race in " + seconds(Math.max(0, r.closesAt - LIVE.clock.getAsLong())) + "§e!");
			long max = LIVE.maxStake(player.getUUID(), -1);
			lore.add(max > 0 ? "§7Max bet: " + Msg.moneyStr(max) : "§cLocked right now (treasury/limit)");
		}
		lore.add("");
		lore.add("§eClick to play");
		return Gui.button(Items.GOLDEN_HORSE_ARMOR, LABEL, () -> HorseRaceGui.open(player), lore.toArray(String[]::new));
	}

	/**
	 * Message to a player; offline (or just leaving) → delivered on the next join. Text without prefix.
	 */
	static void tell(UUID player, String text) {
		ServerPlayer p = online(player);
		if (p != null) Msg.info(p, text);
		else PlayerDataService.addPendingMessage(player, text);
	}

	static ServerPlayer online(UUID player) {
		MinecraftServer server = SmpEconomy.server();
		ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(player);
		return p != null && !p.hasDisconnected() && p.connection != null && p.connection.isAcceptingMessages() ? p : null;
	}

	private static final class LiveEvents implements RaceEngine.Events {
		@Override
		public void firstBet(Race race, Bet bet) {
			HorseConfig c = cfg();
			MinecraftServer server = SmpEconomy.server();
			if (!c.raceAnnouncements || server == null || TestPlayers.isTestPlayer(bet.player)) return;
			long now = LIVE.clock.getAsLong();
			if (now - data().lastAnnounceAt < c.raceAnnounceCooldownMinutes * 60_000L && now >= data().lastAnnounceAt) return;
			data().lastAnnounceAt = now;
			persist();
			MutableComponent line = Msg.prefixed(LABEL + " §7#" + race.id + " starts in §f" + seconds(Math.max(0, race.closesAt - now)) + "§7! ")
				.append(Msg.clickRun("§e[Place a bet]", "/" + HorseCommands.ROOT, "§fOpen the horse race\n§8/" + HorseCommands.ROOT));
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				if (Settings.get(p, Settings.ANNOUNCEMENTS)) p.sendSystemMessage(line);
			}
		}

		@Override
		public void raceStarted(Race race) {
			for (UUID id : race.bets.keySet()) {
				ServerPlayer p = online(id);
				if (p == null || HorseRaceGui.isWatching(p)) continue;
				p.sendSystemMessage(Msg.prefixed(LABEL + " §7#" + race.id + ": §eAnd they're off! ")
					.append(Msg.clickRun("§e[Watch]", "/" + HorseCommands.ROOT, "§fWatch the race\n§8/" + HorseCommands.ROOT)));
			}
		}

		@Override
		public void betSettled(Race race, Bet bet, long amount, boolean refund, boolean paid, String why) {
			String head = LABEL + " §7#" + race.id + ": ";
			String suffix = why == null ? "" : " §8(" + why + ")";
			String text;
			if (refund) {
				text = head + "race cancelled" + suffix + "§7 – your bet of " + Msg.moneyStr(amount) + " was refunded.";
			} else if (amount > 0) {
				text = head + horseName(race.winner) + " §awon! §7You win " + Msg.moneyStr(amount) + " §8(" + HorseMath.formatOdds(bet.odds)
					+ ", bet " + Money.format(bet.stake) + ")" + suffix;
			} else {
				text = head + horseName(race.winner) + " §7won. §cYour bet on " + horseName(bet.horse) + " §clost §8(bet " + Money.format(bet.stake) + ")" + suffix;
			}
			tell(bet.player, text);
			if (!paid) tell(bet.player, "§eYour payout of " + Msg.moneyStr(amount) + " §ewill be paid as soon as the treasury can cover it.");
			ServerPlayer p = online(bet.player);
			if (p != null && !refund) {
				if (amount > 0) {
					Gui.sound(p, SoundEvents.NOTE_BLOCK_PLING, 1.4f);
					Gui.sound(p, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.PLAYER_LEVELUP), 1.2f);
				} else {
					Gui.sound(p, SoundEvents.NOTE_BLOCK_BASS, 0.6f);
				}
			}
			if (!refund) announceBigWin(bet, amount);
		}

		@Override
		public void pendingPaid(Pending pending) {
			tell(pending.player, "§aPending horse race payout paid: " + Msg.moneyStr(pending.amount));
		}
	}

	/** Big win → chat for everyone with the setting "announcements" (casino thresholds). */
	static void announceBigWin(Bet bet, long payout) {
		CasinoConfig c = Casino.cfg();
		if (payout <= bet.stake || TestPlayers.isTestPlayer(bet.player)) return;
		double mult = payout / (double) bet.stake;
		boolean big = payout - bet.stake >= c.announceMinProfit && c.announceMinProfit > 0
			|| mult >= c.announceMinMultiplier && payout >= c.announceMinPayoutForMultiplier;
		if (!big) return;
		MinecraftServer server = SmpEconomy.server();
		if (server == null) return;
		String name = bet.name.isEmpty() ? EconomyService.nameOrUnknown(bet.player) : bet.name;
		String text = "§e" + name + " §7won §a" + Money.format(payout) + " §7in the " + LABEL + " §8(" + HorseMath.formatOdds(bet.odds) + ")§7!";
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (Settings.get(p, Settings.ANNOUNCEMENTS)) Msg.info(p, text);
		}
		SmpEconomy.LOG.info("[SMPEco] Horse race big win: {} {}", name, Money.format(payout));
	}
}
