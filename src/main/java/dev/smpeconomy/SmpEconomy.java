package dev.smpeconomy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.smpeconomy.core.Bots;
import dev.smpeconomy.core.BasePrices;
import dev.smpeconomy.core.ConfigManager;
import dev.smpeconomy.core.CoreConfig;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Feature;
import dev.smpeconomy.core.Hooks;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Perms;
import dev.smpeconomy.core.PlacedBlockTracker;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.PriceModifiers;
import dev.smpeconomy.core.Scheduler;
import dev.smpeconomy.core.Settings;
import dev.smpeconomy.core.Treasury;
import dev.smpeconomy.core.UnknownItems;
import dev.smpeconomy.core.SmpecoCommand;
import dev.smpeconomy.core.module.ModuleCatalog;
import dev.smpeconomy.core.module.ModuleCommands;
import dev.smpeconomy.core.module.ModuleInfo;
import dev.smpeconomy.core.module.Modules;
import dev.smpeconomy.core.test.CoreSelfTests;
import dev.smpeconomy.features.admin.AdminFeature;
import dev.smpeconomy.integration.Integrations;
import java.util.function.Supplier;

public class SmpEconomy implements ModInitializer {
	public static final String MOD_ID = "smpeconomy";
	public static final Logger LOG = LoggerFactory.getLogger("SMP Economy+");

	private static final List<Feature> FEATURES = new ArrayList<>();
	private static MinecraftServer server;
	/** SERVER_STARTED ran to completion (all DataStores loaded). Stopping only saves after that. */
	private static boolean started;
	private static boolean stopping;

	public static MinecraftServer server() {
		return server;
	}

	public static boolean isStopping() {
		return stopping;
	}

	public static List<Feature> features() {
		return Collections.unmodifiableList(FEATURES);
	}

	@Override
	public void onInitialize() {
		Modules.load();
		CoreConfig.get();
		BasePrices.bootstrap();
		Settings.bootstrap();
		// Every DataStore must exist before the shared load in SERVER_STARTED, otherwise it is missing from restore
		// points, recovery and the startup check (DataStore.loadAll aborts). Classes with a store are loaded explicitly
		// here.
		EconomyService.bootstrap();
		PlayerDataService.bootstrap();
		Treasury.bootstrap();
		PriceModifiers.bootstrap();
		registerLifecycle();
		Hooks.registerEvents();
		CoreConfig.registerMultipliers();
		SmpecoCommand.register();
		CoreSelfTests.register();

		// Modules in fixed order; stopping runs in reverse. Disabled modules are never instantiated, so their classes
		// never initialize.
		for (ModuleInfo m : ModuleCatalog.ALL) {
			if (!Modules.isEnabled(m.id())) continue;
			Supplier<Feature> factory = ModuleRegistry.factory(m.id());
			Feature f = factory == null ? null : factory.get();
			if (f == null) {
				LOG.warn("[SMPEco] module {} is enabled but not part of this build – skipped", m.id());
				continue;
			}
			if (!m.id().equals(f.id())) throw new IllegalStateException("Module " + m.id() + " returned feature " + f.id());
			FEATURES.add(f);
			// No try/catch on purpose: a half-registered feature (say a GUI without its close handler) could lose
			// items, so fail loudly at startup.
			ModuleCommands.begin(m.id());
			f.register();
			ModuleCommands.end(m.id());
		}
		Feature admin = new AdminFeature();
		FEATURES.add(admin);
		admin.register();
		Integrations.register();
		LOG.info("SMP Economy+ loaded ({} features)", FEATURES.size());
	}

	private static void registerLifecycle() {
		ServerLifecycleEvents.SERVER_STARTING.register(s -> {
			// before the server reference is set: config callbacks must see the same state as during mod init
			ConfigManager.retryAfterAllModsInitialized(UnknownItems.moddedItemsPresent());
			UnknownItems.markRegistriesComplete();
			server = s;
			started = false;
			stopping = false;
		});

		ServerLifecycleEvents.SERVER_STARTED.register(s -> {
			server = s;
			// Throws if a store is unreadable, which aborts startup. Never run on unsaved defaults.
			DataStore.loadAll(s);
			PlacedBlockTracker.load(s);
			Treasury.init();
			started = true;
			for (Feature f : FEATURES) safely(f, "onServerStarted", () -> f.onServerStarted(s));
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
			stopping = true;
			if (!started) {
				// Startup was aborted (e.g. unreadable file): do not touch or save anything.
				LOG.warn("[SMPEco] Server stopping before startup finished, mod data will not be saved");
				return;
			}
			// Vanilla saves players right after this event (playerList.saveAll) and never calls PlayerList.remove()
			// afterwards. Close open GUIs now so returned items end up in the saved inventory and sale proceeds get
			// saved below.
			for (ServerPlayer p : List.copyOf(s.getPlayerList().getPlayers())) closeOpenScreen(p);
			// Reverse order, e.g. coinflips refund before economy saves.
			for (int i = FEATURES.size() - 1; i >= 0; i--) {
				Feature f = FEATURES.get(i);
				safely(f, "onServerStopping", () -> f.onServerStopping(s));
			}
			Scheduler.clear();
			DataStore.saveAll(s); // vanilla saves players right after this
			PlacedBlockTracker.save();
		});

		ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
			if (started) {
				// Changes made while kicking players (after SERVER_STOPPING). Best effort, there is no later chance.
				// Unchanged stores are skipped.
				DataStore.saveAll(s);
				PlacedBlockTracker.saveIfDirty();
			}
			DataStore.reset();
			PlacedBlockTracker.reset();
			started = false;
			server = null;
		});

		// PlayerListMixin already wrote our data before the player files (beforeVanillaPlayerSave). This only covers
		// what changed while chunks were saving.
		ServerLifecycleEvents.AFTER_SAVE.register((s, flush, force) -> {
			if (started && DataStore.dataDir() != null) DataStore.flushDirty();
		});

		ServerTickEvents.END_SERVER_TICK.register(SmpEconomy::onEndTick);

		ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> onJoin(s, handler.getPlayer()));

		// Not ServerPlayConnectionEvents.DISCONNECT: that usually fires on the Netty thread
		// (Connection.channelInactive). ServerGamePacketListenerImplMixin calls onPlayerLeaving() on the server thread
		// before vanilla saves the player.
	}

	/**
	 * Called by a mixin at the start of {@code ServerGamePacketListenerImpl#removePlayerFromWorld} on the server
	 * thread. The player is leaving but is not saved yet, not marked disconnected and still in the PlayerList, so
	 * closing the GUI here returns items to the inventory that is about to be saved.
	 */
	public static void onPlayerLeaving(ServerPlayer player) {
		if (player == null || player instanceof FakePlayer) return;
		MinecraftServer s = server;
		if (s != null && !s.isSameThread()) {
			// Vanilla only calls this from the server thread. Doing nothing beats changing data concurrently.
			LOG.error("[SMPEco] onPlayerLeaving for {} not on the server thread, skipped", player.getPlainTextName());
			return;
		}
		closeOpenScreen(player);
		if (!started || DataStore.dataDir() == null) return;
		// Vanilla writes this player's .dat next (PlayerList.remove), so commit first. Best effort.
		DataStore.flushBeforeVanillaSave(s);
		DataStore.requestFlush();
	}

	/**
	 * Called by {@code core.mixin.PlayerListMixin} at the start of {@code PlayerList.saveAll}: vanilla is about to
	 * write every player's {@code .dat} (autosave, {@code /save-all}, stop, datapack reload). Mod data must be
	 * committed first, otherwise player files are newer than mod files until the next flush, and a crash in between
	 * duplicates or loses items.
	 */
	public static void beforeVanillaPlayerSave(MinecraftServer s) {
		if (!started || s == null || DataStore.dataDir() == null) return;
		if (!s.isSameThread()) {
			LOG.error("[SMPEco] PlayerList.saveAll not on the server thread, mod data not saved beforehand");
			return;
		}
		DataStore.flushBeforeWorldSave(s);
	}

	public static List<String> adminWarnings() {
		List<String> out = new ArrayList<>();
		DataStore.persistenceProblem().ifPresent(p -> out.add("Saving impaired " + p));
		PlacedBlockTracker.problem().ifPresent(p -> out.add("Placed blocks unreliable – mining pays nothing: " + p
			+ " (restore the copy from backups/ + restart, or /smpeco placed confirm)"));
		for (String c : ConfigManager.problems()) out.add("Config: " + c);
		for (String m : Modules.problems()) out.add("Modules: " + m);
		return out;
	}

	private static void onEndTick(MinecraftServer s) {
		Scheduler.tick();
		if (s.getTickCount() % 20 == 0) {
			for (ServerPlayer p : s.getPlayerList().getPlayers()) {
				if (!Bots.isBot(p)) PlayerDataService.addPlaytime(p.getUUID(), 20);
			}
			PriceModifiers.tick();
		}
		PlacedBlockTracker.endTick();
		DataStore.tick(s);
		PlacedBlockTracker.saveIfDue();
	}

	private static void onJoin(MinecraftServer s, ServerPlayer player) {
		// No account: a bot would collect the start balance and show up in /baltop.
		if (Bots.isBot(player)) return;
		UUID id = player.getUUID();
		EconomyService.ensureAccount(id, player.getGameProfile().name());
		PlayerDataService.onJoin(player);
		// Delay so the messages appear after the join message.
		Scheduler.runLater(20, () -> deliverPending(s, id));
		Scheduler.runLater(40, () -> warnAdmin(s, id));
	}

	private static void warnAdmin(MinecraftServer s, UUID id) {
		ServerPlayer player = s.getPlayerList().getPlayer(id);
		if (player == null || !Perms.checkAdmin(player, Perms.ADMIN)) return;
		List<String> warnings = adminWarnings();
		for (String w : warnings) Msg.send(player, Msg.legacy("§c§lServer issue §8» §c" + w));
		if (!warnings.isEmpty()) Msg.send(player, Msg.legacy("§7Details: §e/smpeco status §7and the server log"));
	}

	private static void deliverPending(MinecraftServer s, UUID id) {
		ServerPlayer player = s.getPlayerList().getPlayer(id);
		if (player == null) return; // already gone again: the messages stay stored
		List<String> messages = PlayerDataService.getPendingMessages(id);
		if (messages.isEmpty()) return;
		Msg.info(player, "§eWhile you were away:");
		for (String m : messages) Msg.info(player, m);
		PlayerDataService.removePendingMessages(id, messages.size());
	}

	/**
	 * Closes an open sgui GUI (with its close hooks, {@link dev.smpeconomy.core.gui.SmpGui}) or a vanilla container
	 * while the player still counts as online. Loops in case a hook opens another menu despite {@code Gui.canOpen}
	 * being false here.
	 */
	private static void closeOpenScreen(ServerPlayer player) {
		for (int round = 0; round < 3; round++) {
			try {
				if (dev.smpeconomy.core.gui.SmpGui.closeCurrent(player)) continue;
				if (player.hasContainerOpen()) player.closeContainer();
				else return;
			} catch (Throwable t) {
				LOG.error("[SMPEco] Closing the GUI of {} failed", player.getPlainTextName(), t);
				return;
			}
		}
	}

	private static void safely(Feature f, String what, Runnable action) {
		try {
			action.run();
		} catch (Throwable t) {
			LOG.error("[SMPEco] Feature {}: {} failed", f.id(), what, t);
		}
	}
}
