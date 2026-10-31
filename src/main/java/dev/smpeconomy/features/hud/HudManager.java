package dev.smpeconomy.features.hud;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.BlankFormat;
import net.minecraft.network.chat.numbers.NumberFormat;
import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.stats.Stats;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.Bots;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Hooks;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.PriceModifiers;
import dev.smpeconomy.core.Services;
import dev.smpeconomy.core.Settings;
import dev.smpeconomy.integration.Integrations;

/**
 * Per-player sidebar and tablist, packets only: a never-registered {@link Objective} named {@link #OBJECTIVE} on a
 * private {@link Scoreboard}. The ':' in the name means commands and datapacks can't create it, so it can't collide
 * with vanilla objectives. Each line is a score with a fixed holder, display text and {@link BlankFormat} (no red
 * numbers). What the client has is tracked per connection and only changes are sent.
 *
 * <p>Server thread only: {@link #tick} runs as a scheduler task at the end of the tick. Full round every
 * {@code updateIntervalTicks}, in between only players with changes (money, setting, join).
 */
final class HudManager {
	static final String OBJECTIVE = "smpeco:hud";

	/** What a connection's client has right now. */
	static final class State {
		final ServerGamePacketListenerImpl connection;
		boolean shown;
		String title;
		Map<HudLines.Line, String> lines = Map.of();
		boolean displayAsserted;
		String header;
		String footer;

		State(ServerGamePacketListenerImpl connection) {
			this.connection = connection;
		}
	}

	private static final boolean STYLED_PLAYER_LIST = FabricLoader.getInstance().isModLoaded("styledplayerlist");

	record Shared(int online, int maxPlayers, boolean marketShock, boolean auctionFever) {}

	private static final Scoreboard PRIVATE_BOARD = new Scoreboard();
	private static final Map<UUID, State> STATES = new HashMap<>();
	private static final Set<UUID> DIRTY = new HashSet<>();
	private static Objective objective;
	private static String objectiveTitle;
	private static boolean allDirty = true;
	private static boolean displayReassert;
	private static long ticks;
	private static long lastErrorLogMs;
	private static boolean collisionWarned;
	private static boolean statsWarned;
	private static boolean jobsWarned;
	private static boolean missionsWarned;

	private HudManager() {}

	/** Clients get everything again on their next join. */
	static void reset() {
		STATES.clear();
		DIRTY.clear();
		allDirty = true;
		displayReassert = false;
		ticks = 0;
	}

	static void markDirty(UUID player) {
		if (player != null) DIRTY.add(player);
	}

	static boolean isDirty(UUID player) {
		return DIRTY.contains(player);
	}

	static void markAllDirty() {
		allDirty = true;
	}

	static void onJoin(ServerPlayer player) {
		markDirty(player.getUUID());
		allDirty = true;
	}

	/**
	 * Mixin (ServerScoreboard#setDisplayObjective, sidebar): vanilla showed all clients another sidebar, so show ours
	 * again on the next tick for players who have it on.
	 */
	static void onVanillaSidebarChanged() {
		displayReassert = true;
		allDirty = true;
	}

	static void tick(MinecraftServer server) {
		ticks++;
		HudConfig cfg = HudFeature.CONFIG.get();
		boolean full = allDirty || ticks % cfg.updateIntervalTicks == 0;
		if (!full && DIRTY.isEmpty()) return;
		if (displayReassert) {
			for (State s : STATES.values()) s.displayAsserted = false;
			displayReassert = false;
		}
		Shared shared = shared(server);
		if (full) {
			Set<UUID> online = new HashSet<>();
			for (ServerPlayer p : List.copyOf(server.getPlayerList().getPlayers())) {
				online.add(p.getUUID());
				safeUpdate(server, p, cfg, shared);
			}
			STATES.keySet().retainAll(online); // Forget disconnected players here, so no DISCONNECT event is needed.
		} else {
			for (UUID id : List.copyOf(DIRTY)) {
				ServerPlayer p = server.getPlayerList().getPlayer(id);
				if (p != null) safeUpdate(server, p, cfg, shared);
			}
		}
		DIRTY.clear();
		allDirty = false;
	}

	static Shared shared(MinecraftServer server) {
		return new Shared(server.getPlayerList().getPlayerCount(), server.getPlayerList().getMaxPlayers(), marketShockActive(),
			Hooks.flag(Hooks.FLAG_AUCTION_FEVER));
	}

	/** The count differs per viewer once Vanish hides someone. */
	static Shared forViewer(ServerPlayer viewer, Shared shared) {
		return new Shared(Integrations.onlineFor(viewer, shared.online()), shared.maxPlayers(), shared.marketShock(), shared.auctionFever());
	}

	private static void safeUpdate(MinecraftServer server, ServerPlayer p, HudConfig cfg, Shared shared) {
		try {
			update(server, p, cfg, shared);
		} catch (RuntimeException e) {
			long now = System.currentTimeMillis();
			if (now - lastErrorLogMs > 60_000) {
				lastErrorLogMs = now;
				SmpEconomy.LOG.error("[SMPEco] hud: update failed for {}", p.getPlainTextName(), e);
			}
		}
	}

	private static void update(MinecraftServer server, ServerPlayer p, HudConfig cfg, Shared shared) {
		if (Bots.isBot(p)) return;
		ServerGamePacketListenerImpl conn = p.connection;
		if (conn == null) return;
		UUID id = p.getUUID();
		State s = STATES.get(id);
		if (s == null || s.connection != conn) {
			// A new connection (join, relog, return from the configuration phase) means a fresh client scoreboard.
			s = new State(conn);
			STATES.put(id, s);
		}
		boolean wantSidebar = cfg.sidebar && Settings.get(id, Settings.SIDEBAR) && objectiveNameFree(server);
		boolean wantTablist = wantsTablist(cfg, STYLED_PLAYER_LIST);
		if (!wantSidebar && !s.shown && !wantTablist && s.header == null) return;
		HudLines.Data data = collect(p, forViewer(p, shared));

		if (wantSidebar) {
			String title = HudConfig.title();
			Objective obj = objective(title);
			if (!s.shown) {
				conn.send(new ClientboundSetObjectivePacket(obj, ClientboundSetObjectivePacket.METHOD_ADD));
				s.shown = true;
				s.title = title;
				s.lines = Map.of();
				s.displayAsserted = false;
			} else if (!title.equals(s.title)) {
				conn.send(new ClientboundSetObjectivePacket(obj, ClientboundSetObjectivePacket.METHOD_CHANGE));
				s.title = title;
			}
			if (!s.displayAsserted) {
				conn.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, obj));
				s.displayAsserted = true;
			}
			Map<HudLines.Line, String> lines = HudLines.sidebar(data, cfg);
			for (SidebarDiff.Op op : SidebarDiff.diff(s.lines, lines)) {
				conn.send(op.isRemove() ? resetPacket(op.line()) : scorePacket(op.line(), op.text()));
			}
			s.lines = lines;
		} else if (s.shown) {
			hide(server, s, cfg);
		}

		if (wantTablist) {
			String header = HudLines.header(data, cfg);
			String footer = HudLines.footer(data, cfg);
			if (!header.equals(s.header) || !footer.equals(s.footer)) {
				conn.send(new ClientboundTabListPacket(Msg.legacy(header), Msg.legacy(footer)));
				s.header = header;
				s.footer = footer;
			}
		} else if (s.header != null) {
			conn.send(new ClientboundTabListPacket(Component.empty(), Component.empty()));
			s.header = null;
			s.footer = null;
		}
	}

	static boolean wantsTablist(HudConfig cfg, boolean styledPlayerList) {
		return cfg.tablist && (!styledPlayerList || cfg.tablistWithStyledPlayerList);
	}

	static void logTablistOwner(HudConfig cfg) {
		if (STYLED_PLAYER_LIST && cfg.tablist && !cfg.tablistWithStyledPlayerList) {
			SmpEconomy.LOG.info("[SMPEco] hud: Styled Player List found, leaving the tab list to it (tablistWithStyledPlayerList in hud.json)");
		}
	}

	/** Removes our objective on the client and shows the vanilla sidebar again, if there is one. */
	private static void hide(MinecraftServer server, State s, HudConfig cfg) {
		s.connection.send(new ClientboundSetObjectivePacket(objective(s.title != null ? s.title : HudConfig.title()),
			ClientboundSetObjectivePacket.METHOD_REMOVE));
		s.shown = false;
		s.title = null;
		s.lines = Map.of();
		s.displayAsserted = false;
		Objective vanilla = server.getScoreboard().getDisplayObjective(DisplaySlot.SIDEBAR);
		if (vanilla != null) s.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, vanilla));
	}

	/** Never creates accounts or player data. */
	static HudLines.Data collect(ServerPlayer p, Shared shared) {
		return collect(p, shared, Services.find(Services.JobLevels.class).orElse(null), Services.find(Services.Missions.class).orElse(null));
	}

	/** Null services mean the feature is missing; used by selftests. */
	static HudLines.Data collect(ServerPlayer p, Shared shared, Services.JobLevels jobs, Services.Missions missions) {
		UUID id = p.getUUID();
		HudLines.JobInfo job = jobOf(jobs, id, PlayerDataService.getJob(id), HudManager::warnJobs);
		TaskProgress.Progress tasks = tasksOf(id);
		HudLines.MissionInfo m = missionsOf(missions, id, HudManager::warnMissions);
		long[] kd = killsDeaths(Services.find(Services.PlayerStats.class).orElse(null), id,
			() -> vanillaStat(p, Stats.PLAYER_KILLS), () -> vanillaStat(p, Stats.DEATHS), HudManager::warnStats);
		return new HudLines.Data(p.getGameProfile().name(), EconomyService.balance(id), job, tasks.done(), tasks.total(), m,
			kd[0], kd[1], PlayerDataService.getPlaytimeTicks(id), shared.online(), shared.maxPlayers(), shared.marketShock(),
			shared.auctionFever());
	}

	/**
	 * From {@link Services.JobLevels}; without the service, for an unknown job or if it throws, level from player
	 * data and progress unknown (no progress line).
	 */
	static HudLines.JobInfo jobOf(Services.JobLevels svc, UUID id, String job, Consumer<RuntimeException> onError) {
		if (job == null || job.isBlank()) return HudLines.JobInfo.NONE;
		if (svc != null) {
			try {
				Services.JobLevels.JobProgress jp = svc.progress(id, job);
				if (jp != null) return new HudLines.JobInfo(job, Math.max(1, jp.level()), Math.max(0, jp.xp()), jp.xpForNext(), jp.maxLevel());
			} catch (RuntimeException e) {
				onError.accept(e);
			}
		}
		return new HudLines.JobInfo(job, PlayerDataService.getJobLevel(id, job), 0, -1, 0);
	}

	/** Null without the service or if it throws, which drops the mission and elite lines. */
	static HudLines.MissionInfo missionsOf(Services.Missions svc, UUID id, Consumer<RuntimeException> onError) {
		if (svc == null) return null;
		try {
			Services.Missions.EliteStatus elite = svc.elite(id);
			return new HudLines.MissionInfo(Math.max(0, svc.activeCount(id)), svc.maxActive(), svc.nearest(id),
				elite == null ? Services.Missions.EliteStatus.NONE : elite);
		} catch (RuntimeException e) {
			onError.accept(e);
			return null;
		}
	}

	private static void warnJobs(RuntimeException e) {
		if (jobsWarned) return;
		jobsWarned = true;
		SmpEconomy.LOG.warn("[SMPEco] hud: JobLevels service threw, showing job without progress", e);
	}

	private static void warnMissions(RuntimeException e) {
		if (missionsWarned) return;
		missionsWarned = true;
		SmpEconomy.LOG.warn("[SMPEco] hud: Missions service threw, sidebar without missions", e);
	}

	/** Without the tasks service, or if it throws, falls back to the tolerant state reader. */
	static TaskProgress.Progress tasksOf(UUID id) {
		java.util.Optional<Services.DailyTasks> svc = Services.find(Services.DailyTasks.class);
		if (svc.isPresent()) {
			try {
				int total = svc.get().total(id);
				int done = svc.get().done(id);
				if (total > 0) return new TaskProgress.Progress(Math.max(0, Math.min(done, total)), total);
			} catch (RuntimeException e) {
				SmpEconomy.LOG.warn("[SMPEco] hud: DailyTasks service threw, reading task state directly", e);
			}
		}
		return TaskProgress.read(id);
	}

	private static long vanillaStat(ServerPlayer p, Identifier stat) {
		return p.getStats().getValue(Stats.CUSTOM.get(stat));
	}

	/**
	 * From {@link Services.PlayerStats} if present and plausible, otherwise vanilla stats.
	 *
	 * @return {kills, deaths}, never negative
	 */
	static long[] killsDeaths(Services.PlayerStats stats, UUID id, LongSupplier vanillaKills, LongSupplier vanillaDeaths,
		Consumer<RuntimeException> onError) {
		if (stats != null) {
			try {
				long k = stats.playerKills(id);
				long d = stats.deaths(id);
				if (k >= 0 && d >= 0) return new long[] {k, d};
			} catch (RuntimeException e) {
				onError.accept(e);
			}
		}
		return new long[] {Math.max(0, vanillaKills.getAsLong()), Math.max(0, vanillaDeaths.getAsLong())};
	}

	private static void warnStats(RuntimeException e) {
		if (statsWarned) return;
		statsWarned = true;
		SmpEconomy.LOG.warn("[SMPEco] hud: PlayerStats service threw, using vanilla stats", e);
	}

	/** Any price modifier other than 1.0 (breaking news, admin), including while fading in or out. */
	static boolean marketShockActive() {
		for (PriceModifiers.Active a : PriceModifiers.active()) {
			if (Math.abs(a.current() - 1.0) > 1e-3 || Math.abs(a.target() - 1.0) > 1e-3) return true;
		}
		return false;
	}

	/** Safety net: a server objective with the same name would make the client report "already exists". */
	private static boolean objectiveNameFree(MinecraftServer server) {
		if (server.getScoreboard().getObjective(OBJECTIVE) == null) return true;
		if (!collisionWarned) {
			collisionWarned = true;
			SmpEconomy.LOG.error("[SMPEco] hud: server scoreboard has an objective '{}', sidebar disabled", OBJECTIVE);
		}
		return false;
	}

	/** Packet template only; never registered on a server scoreboard. */
	static Objective objective(String title) {
		if (objective == null || !title.equals(objectiveTitle)) {
			objective = new Objective(PRIVATE_BOARD, OBJECTIVE, ObjectiveCriteria.DUMMY, Msg.legacy(title),
				ObjectiveCriteria.RenderType.INTEGER, false, BlankFormat.INSTANCE);
			objectiveTitle = title;
		}
		return objective;
	}

	static ClientboundSetScorePacket scorePacket(HudLines.Line line, String text) {
		return new ClientboundSetScorePacket(line.holder(), OBJECTIVE, line.score(), Optional.of(Msg.legacy(text)),
			Optional.<NumberFormat>of(BlankFormat.INSTANCE));
	}

	static ClientboundResetScorePacket resetPacket(HudLines.Line line) {
		return new ClientboundResetScorePacket(line.holder(), OBJECTIVE);
	}

	static Optional<State> state(UUID player) {
		return Optional.ofNullable(STATES.get(player));
	}

	static int stateCount() {
		return STATES.size();
	}
}
