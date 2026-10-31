package dev.smpeconomy.features.minigames;

import java.security.SecureRandom;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.Bots;
import dev.smpeconomy.core.CommandRegistry;
import dev.smpeconomy.core.ConfigManager;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.Feature;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Scheduler;
import dev.smpeconomy.core.Settings;
import dev.smpeconomy.core.test.TestPlayers;
import dev.smpeconomy.features.minigames.chat.ChatGameContent;
import dev.smpeconomy.features.minigames.chat.ChatGames;
import dev.smpeconomy.features.minigames.coinflip.Coinflips;
import dev.smpeconomy.features.minigames.mega.MegaChallenge;
import dev.smpeconomy.features.minigames.mega.MegaContent;
import dev.smpeconomy.features.minigames.mega.MegaLive;
import dev.smpeconomy.features.minigames.mega.MegaNames;
import dev.smpeconomy.features.minigames.treasure.TreasureGui;
import dev.smpeconomy.features.minigames.treasure.TreasureHunt;

/**
 * Chat games, player coinflip with escrow, treasure hunt and the MEGA-CHALLENGE. Respects the {@code chatgames} and
 * {@code announcements} settings.
 */
public final class MinigamesFeature implements Feature {
	public static final String ID = "minigames";
	public static final String ADMIN_NODE = "smpeconomy.admin.minigames";

	public static final ConfigManager.Config<MinigamesConfig> CONFIG =
		ConfigManager.register("minigames", MinigamesConfig.class, MinigamesConfig::new, MinigamesConfig::validate);
	public static final ConfigManager.Config<ChatGameContent> CONTENT =
		ConfigManager.registerResource("chatgames", ChatGameContent.class, "chatgames.json", ChatGameContent::validate);
	public static final ConfigManager.Config<MegaContent> MEGA_CONTENT =
		ConfigManager.registerResource("megachallenge", MegaContent.class, "megachallenge.json", MegaContent::validate);

	static final DataStore<Coinflips.Data> COINFLIP_STORE = new DataStore<>("coinflips.json", Coinflips.Data.class, Coinflips.Data::new);
	static final DataStore<TreasureHunt.Data> TREASURE_STORE = new DataStore<>("treasure.json", TreasureHunt.Data.class, TreasureHunt.Data::new);
	static final DataStore<MegaChallenge.Data> MEGA_STORE = new DataStore<>("megachallenge.json", MegaChallenge.Data.class, MegaChallenge.Data::new);

	/**
	 * Each instance gets its own unpredictable SecureRandom. A shared java.util.Random could be reconstructed from
	 * public chat game content (fast-type codes, math tasks) and then advanced on purpose to predict coinflip
	 * outcomes and treasure locations. Selftests pass a seeded Random through the same constructors.
	 */
	private static final ChatGames CHAT = new ChatGames(() -> CONFIG.get().chatGames, CONTENT::get, System::currentTimeMillis, new SecureRandom(), new ChatAnnouncer());
	private static final Coinflips COINFLIPS = new Coinflips(COINFLIP_STORE::get, COINFLIP_STORE::markDirty, () -> CONFIG.get().coinflip, new SecureRandom(), System::currentTimeMillis);
	private static final TreasureHunt TREASURE = new TreasureHunt(TREASURE_STORE::get, TREASURE_STORE::markDirty, () -> CONFIG.get().treasure,
		System::currentTimeMillis, new SecureRandom(), new TreasureAnnouncer());

	private static final MegaLive MEGA_LIVE = new MegaLive(CHAT, () -> CONFIG.get().megaChallenge, MEGA_CONTENT::get);
	private static final MegaChallenge MEGA = new MegaChallenge(MEGA_STORE::get, MEGA_STORE::markDirty, () -> CONFIG.get().megaChallenge,
		System::currentTimeMillis, new SecureRandom(), MEGA_LIVE, MEGA_LIVE);

	private static final Identifier CHAT_PHASE = Identifier.fromNamespaceAndPath(SmpEconomy.MOD_ID, "minigames_answers");
	private static Scheduler.Task ticker;
	private static Scheduler.Task raceTicker;
	private static long seconds;

	@Override
	public String id() {
		return ID;
	}

	public static ChatGames chatGames() {
		return CHAT;
	}

	public static Coinflips coinflips() {
		return COINFLIPS;
	}

	public static TreasureHunt treasure() {
		return TREASURE;
	}

	public static MegaChallenge mega() {
		return MEGA;
	}

	public static MegaLive megaLive() {
		return MEGA_LIVE;
	}

	public static MinigamesConfig config() {
		return CONFIG.get();
	}

	@Override
	public void register() {
		COINFLIP_STORE.onLoad(Coinflips.Data::fixNulls);
		TREASURE_STORE.onLoad(TreasureHunt.Data::fixNulls);
		MEGA_STORE.onLoad(MegaChallenge.Data::fixNulls);
		// normal chat games don't start while the MEGA-CHALLENGE is announced or running
		CHAT.setPause(MEGA::busy);

		// evaluate answers before other chat handlers (clan chat redirection, for example, swallows the message)
		ServerMessageEvents.ALLOW_CHAT_MESSAGE.addPhaseOrdering(CHAT_PHASE, Event.DEFAULT_PHASE);
		ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(CHAT_PHASE, MinigamesFeature::onChat);

		UseBlockCallback.EVENT.register((player, level, hand, hit) -> onTreasureClick(player, level, hand, hit.getBlockPos()));
		AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> onTreasureClick(player, level, hand, pos));
		// MEGA race: natural mining (after the break, while PlacedBlockTracker is still valid) and portal travel
		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
			if (level.isClientSide() || !(player instanceof ServerPlayer sp) || Bots.isBot(player)) return;
			try {
				MEGA_LIVE.probe().onBlockBroken(sp, level, pos, state);
			} catch (RuntimeException e) {
				SmpEconomy.LOG.error("[SMPEco] MEGA race: could not count mining", e);
			}
		});
		ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> {
			if (Bots.isBot(player)) return;
			try {
				MEGA_LIVE.probe().onDimensionChange(player, origin, destination);
			} catch (RuntimeException e) {
				SmpEconomy.LOG.error("[SMPEco] MEGA race: could not evaluate dimension change", e);
			}
		});
		// kill credit like the vanilla stat, plus dimension (endermen only in the overworld)
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity.level().isClientSide() || MEGA.phase() != MegaChallenge.Phase.RACE) return;
			try {
				if (entity.getKillCredit() instanceof ServerPlayer killer && !Bots.isBot(killer)) {
					MEGA_LIVE.probe().onKill(killer, entity.getType(), entity.level());
				}
			} catch (RuntimeException e) {
				SmpEconomy.LOG.error("[SMPEco] MEGA race: could not count kill", e);
			}
		});
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
			if (level.isClientSide() || !TREASURE.isTreasure(level, pos)) return true;
			if (player instanceof ServerPlayer sp) Msg.error(sp, "You can't mine the treasure – click it to open it!");
			return false;
		});

		CommandRegistry.register((dispatcher, registryAccess, environment) -> MinigamesCommands.register(dispatcher));

		TestPlayers.onCleanup(id -> {
			if (COINFLIP_STORE.get().open.removeIf(f -> f.creator.equals(id))) COINFLIP_STORE.markDirty();
		});

		MinigamesSelfTests.register();
		dev.smpeconomy.features.minigames.mega.MegaSelfTests.register(MEGA_CONTENT::get);
		saveMigratedConfig();
	}

	/**
	 * Writes minigames.json back once when loading changed a value, so the file shows what applies. A missing
	 * {@code defaultsVersion} key already makes ConfigManager rewrite the file; this covers the rest.
	 */
	private static void saveMigratedConfig() {
		MinigamesConfig cfg = CONFIG.get();
		if (cfg == null || !(cfg.announceMinBetMigrated || cfg.defaultsMigrated)) return;
		try {
			CONFIG.save();
			if (cfg.announceMinBetMigrated) {
				SmpEconomy.LOG.info("[SMPEco] minigames.json: coinflip.announceMinBet changed from 0 to {} (announcements only from this stake)",
					cfg.coinflip.announceMinBet);
			}
			if (cfg.defaultsMigrated) SmpEconomy.LOG.info("[SMPEco] minigames.json: migrated defaults saved (defaultsVersion {})", cfg.defaultsVersion);
			cfg.announceMinBetMigrated = false;
			cfg.defaultsMigrated = false;
		} catch (java.io.IOException e) {
			SmpEconomy.LOG.warn("[SMPEco] minigames.json: could not write migrated values (they still apply)", e);
		}
	}

	@Override
	public void onServerStarted(MinecraftServer server) {
		// after a crash, open coinflips and treasure chests can remain: refund and clean up
		int refunded = COINFLIPS.refundAll("restart");
		if (refunded > 0) SmpEconomy.LOG.warn("[SMPEco] Refunded {} open coinflips from the last run", refunded);
		TREASURE.active().ifPresent(a -> {
			SmpEconomy.LOG.warn("[SMPEco] Removing treasure chest from the last run at {} {} {}", a.x, a.y, a.z);
			try {
				TREASURE.cleanup(server, null);
			} catch (RuntimeException e) {
				SmpEconomy.LOG.error("[SMPEco] Could not remove old treasure chest", e);
			}
		});
		CHAT.scheduleNext(System.currentTimeMillis());
		MegaNames.clearCache();
		MEGA.onServerStarted();
		seconds = 0;
		ticker = Scheduler.runRepeating(20, 20, () -> tickSecond(server));
		raceTicker = Scheduler.runRepeating(5, 5, () -> {
			try {
				MEGA.raceTick();
			} catch (RuntimeException e) {
				SmpEconomy.LOG.error("[SMPEco] MEGA race tick failed", e);
			}
		});
	}

	@Override
	public void onServerStopping(MinecraftServer server) {
		if (ticker != null) ticker.cancel();
		if (raceTicker != null) raceTicker.cancel();
		try {
			MEGA.onServerStopping();
		} catch (RuntimeException e) {
			SmpEconomy.LOG.error("[SMPEco] Could not end MEGA-CHALLENGE on stop", e);
		}
		CHAT.stop(null);
		COINFLIPS.refundAll("server stop");
		TREASURE.abortSearch();
		try {
			TREASURE.cleanup(server, null);
		} catch (RuntimeException e) {
			SmpEconomy.LOG.error("[SMPEco] Could not remove treasure chest on stop (retried on start)", e);
		}
	}

	private static void tickSecond(MinecraftServer server) {
		seconds++;
		if (seconds % 60 == 0) {
			try {
				TreasuryRewards.settleOnline(server);
			} catch (RuntimeException e) {
				SmpEconomy.LOG.error("[SMPEco] Paying pending prizes failed", e);
			}
		}
		int online = 0;
		int chatEligible = 0;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (Bots.isBot(p)) continue;
			online++;
			if (Settings.get(p, Settings.CHATGAMES)) chatEligible++;
		}
		try {
			MEGA.tick();
			MEGA_LIVE.syncBar();
		} catch (RuntimeException e) {
			SmpEconomy.LOG.error("[SMPEco] MEGA-CHALLENGE tick failed", e);
		}
		try {
			CHAT.tick(chatEligible);
		} catch (RuntimeException e) {
			SmpEconomy.LOG.error("[SMPEco] Chat game tick failed", e);
		}
		try {
			TREASURE.tick(server, online, seconds);
		} catch (RuntimeException e) {
			SmpEconomy.LOG.error("[SMPEco] Treasure hunt tick failed", e);
		}
	}

	private static boolean onChat(PlayerChatMessage message, ServerPlayer sender, ChatType.Bound params) {
		if (sender == null || Bots.isBot(sender)) return true;
		MinecraftServer server = SmpEconomy.server();
		if (server == null) return true;
		UUID id = sender.getUUID();
		String name = sender.getGameProfile().name();
		String text = message.signedContent();
		if (server.isSameThread() && MEGA.busy()) {
			// MEGA-CHALLENGE attempts stay private: the message is not broadcast
			try {
				MegaChallenge.GuessResult r = MEGA.guess(id, name, text);
				if (r.suppress()) return false;
			} catch (RuntimeException e) {
				SmpEconomy.LOG.error("[SMPEco] MEGA answer from {} failed", name, e);
			}
		}
		Runnable evaluate = () -> {
			try {
				CHAT.answer(id, name, text);
			} catch (RuntimeException e) {
				SmpEconomy.LOG.error("[SMPEco] Chat game answer from {} failed", name, e);
			}
		};
		if (server.isSameThread()) evaluate.run();
		else server.execute(evaluate); // shouldn't happen, chat runs on the server executor
		return true;
	}

	private static InteractionResult onTreasureClick(Player player, Level level, InteractionHand hand, BlockPos pos) {
		return treasureClick(TREASURE, player, level, pos);
	}

	/**
	 * Click on the treasure chest (right: UseBlockCallback, left: AttackBlockCallback). The left-click callback
	 * runs before vanilla's reach check, so check it here. Out of reach returns PASS so vanilla discards the packet
	 * as for any distant block; FAIL would send the client a block update and reveal that the treasure is at this
	 * position.
	 */
	static InteractionResult treasureClick(TreasureHunt hunt, Player player, Level level, BlockPos pos) {
		if (level.isClientSide() || !hunt.isTreasure(level, pos)) return InteractionResult.PASS;
		if (!(player instanceof ServerPlayer sp)) return InteractionResult.FAIL;
		if (!TreasureHunt.canReach(sp, level, pos)) return InteractionResult.PASS;
		if (Bots.isBot(player) || sp.isSpectator()) return InteractionResult.FAIL;
		hunt.claim(sp).ifPresent(claim -> Scheduler.runLater(1, () -> {
			if (Gui.canOpen(sp)) TreasureGui.open(sp, claim);
		}));
		return InteractionResult.SUCCESS;
	}

	/** One tick later so the answer line comes first. */
	private static final class ChatAnnouncer implements ChatGames.Announcer {
		@Override
		public void broadcast(Component message) {
			Scheduler.runLater(1, () -> {
				MinecraftServer server = SmpEconomy.server();
				if (server == null) return;
				for (ServerPlayer p : server.getPlayerList().getPlayers()) {
					if (!Bots.isBot(p) && Settings.get(p, Settings.CHATGAMES)) p.sendSystemMessage(message);
				}
			});
		}

		@Override
		public void send(UUID player, Component message) {
			Scheduler.runLater(1, () -> {
				MinecraftServer server = SmpEconomy.server();
				ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(player);
				if (p != null) p.sendSystemMessage(message);
			});
		}
	}

	/** The finder always gets their messages. */
	private static final class TreasureAnnouncer implements TreasureHunt.Announcer {
		@Override
		public void broadcast(Component message) {
			MinecraftServer server = SmpEconomy.server();
			if (server == null) return;
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				if (!Bots.isBot(p) && Settings.get(p, Settings.ANNOUNCEMENTS)) p.sendSystemMessage(message);
			}
		}

		@Override
		public void send(UUID player, Component message) {
			MinecraftServer server = SmpEconomy.server();
			ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(player);
			if (p != null) p.sendSystemMessage(message);
		}

		@Override
		public void startTitle() {
			MinecraftServer server = SmpEconomy.server();
			if (server == null) return;
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				if (Bots.isBot(p) || !Settings.get(p, Settings.ANNOUNCEMENTS)) continue;
				p.connection.send(new ClientboundSetTitlesAnimationPacket(10, 60, 20));
				p.connection.send(new ClientboundSetTitleTextPacket(Msg.legacy("§6§lTREASURE HUNT")));
				p.connection.send(new ClientboundSetSubtitleTextPacket(Msg.legacy("§eA treasure has been hidden!")));
				Gui.sound(p, SoundEvents.NOTE_BLOCK_BELL, 1.2f);
			}
		}

		@Override
		public void proximity(TreasureHunt hunt, TreasureHunt.Active a) {
			MinecraftServer server = SmpEconomy.server();
			if (server == null) return;
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				if (Bots.isBot(p) || p.isSpectator() || !Settings.get(p, Settings.ANNOUNCEMENTS)) continue;
				if (!p.level().dimension().identifier().toString().equals(a.dimension)) continue;
				double dx = p.getX() - (a.x + 0.5);
				double dz = p.getZ() - (a.z + 0.5);
				double dist = Math.sqrt(dx * dx + dz * dz);
				if (dist <= 12) Msg.actionBar(p, "§c§l✦ Burning hot! ✦");
				else if (dist <= 40) Msg.actionBar(p, "§6§l✦ Hot! §6The treasure is very close.");
				else if (dist <= 100) Msg.actionBar(p, "§e✦ Warm – the treasure is nearby.");
			}
		}
	}
}
