package dev.smpeconomy.features.missions;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import net.fabricmc.fabric.api.entity.FakePlayer;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.Services;
import dev.smpeconomy.core.test.Assert;
import dev.smpeconomy.core.test.SelfTests;
import dev.smpeconomy.features.missions.MissionStore.ActiveMission;
import dev.smpeconomy.features.missions.MissionStore.PlayerState;

/**
 * {@link Services.Missions} for the sidebar: active missions, the most advanced one and the Daily Elite state. Never
 * creates player data ({@link MissionStore#peek}). Progress is the saved one ({@code ActiveMission.progress}, updated
 * every second by {@link MissionService#update}).
 * <p>Daily Elite: {@link #register()} sets {@link #dailyElite(UUID, long)} as the source ({@link EliteService}: NONE =
 * no Daily Elite today or turned off, OPEN, DONE). That isn't purely read-only: the first access of a new day pins its
 * Daily Elite in the store ({@link EliteService#today}, markDirty), just as the board, join hint or day change would.
 * The midnight announcement doesn't depend on it ({@link EliteService#tick} remembers the day itself). With no source
 * (null) the state is {@link Services.Missions.EliteStatus#NONE} and the sidebar shows no elite line.
 */
final class MissionsHudService implements Services.Missions {
	private static final String F = "missions";
	private static Function<UUID, EliteStatus> eliteSource;
	private static boolean eliteWarned;

	private MissionsHudService() {}

	static void register() {
		eliteSource(id -> dailyElite(id, System.currentTimeMillis()));
		Services.register(Services.Missions.class, new MissionsHudService());
		registerSelfTests();
	}

	/** Null, or a null result, means no Daily Elite. Nothing gets created per player. */
	static void eliteSource(Function<UUID, EliteStatus> source) {
		eliteSource = source;
	}

	/**
	 * NONE (turned off or pool empty), DONE (finished today) or OPEN. Creates no player data; pins the day's Daily
	 * Elite on the first access of a day.
	 */
	static EliteStatus dailyElite(UUID player, long now) {
		EliteService.Today t = EliteService.today(now);
		if (t == null) return EliteStatus.NONE;
		return EliteService.doneToday(player, t) ? EliteStatus.DONE : EliteStatus.OPEN;
	}

	@Override
	public int activeCount(UUID player) {
		PlayerState s = MissionStore.peek(player);
		return s == null ? 0 : s.active.size();
	}

	@Override
	public int maxActive() {
		return MissionConfig.maxActive();
	}

	@Override
	public MissionProgress nearest(UUID player) {
		PlayerState s = MissionStore.peek(player);
		return s == null ? null : nearest(s.active);
	}

	@Override
	public EliteStatus elite(UUID player) {
		Function<UUID, EliteStatus> source = eliteSource;
		if (source == null) return EliteStatus.NONE;
		try {
			EliteStatus status = source.apply(player);
			return status == null ? EliteStatus.NONE : status;
		} catch (RuntimeException e) {
			if (!eliteWarned) {
				eliteWarned = true;
				SmpEconomy.LOG.warn("[SMPEco] missions: could not read Daily Elite state for the sidebar", e);
			}
			return EliteStatus.NONE;
		}
	}

	/** Largest share done; on a tie less left, then expiring sooner, then accepted earlier. Null for an empty list. */
	static MissionProgress nearest(List<ActiveMission> active) {
		ActiveMission best = null;
		for (ActiveMission a : active) {
			if (a == null || a.def == null) continue;
			if (best == null || closer(a, best)) best = a;
		}
		if (best == null) return null;
		return new MissionProgress(displayName(best.name()), done(best), best.amount());
	}

	private static long done(ActiveMission a) {
		return Math.max(0, Math.min(a.amount(), a.progress));
	}

	/** Amounts up to 1M, so cross products fit in a long. */
	private static boolean closer(ActiveMission a, ActiveMission b) {
		long pa = done(a), na = a.amount(), pb = done(b), nb = b.amount();
		int byRatio = Long.compare(pa * nb, pb * na);
		if (byRatio != 0) return byRatio > 0;
		int byRemaining = Long.compare(na - pa, nb - pb);
		if (byRemaining != 0) return byRemaining < 0;
		if (a.expiresAt != b.expiresAt) return a.expiresAt < b.expiresAt;
		return a.instance < b.instance;
	}

	/** Without § codes (the sidebar colors it itself), trimmed. */
	static String displayName(String name) {
		if (name == null) return "";
		return name.replaceAll("§.?", "").trim();
	}

	private static ActiveMission active(long instance, String name, int amount, long progress, long expiresAt) {
		ActiveMission a = new ActiveMission();
		a.instance = instance;
		a.missionId = "hudtest_" + instance;
		a.def = new MissionConfig.MissionDef();
		a.def.id = a.missionId;
		a.def.name = name;
		a.def.amount = amount;
		a.progress = progress;
		a.expiresAt = expiresAt;
		return a;
	}

	private static void registerSelfTests() {
		SelfTests.register(F, "hud-service-nearest", ctx -> {
			Assert.eq(null, nearest(List.of()), "empty gives null");
			ActiveMission iron = active(1, "§fSmithy §lsupplies", 32, 20, 5_000);
			ActiveMission fish = active(2, "Fishing Trip", 10, 9, 9_000);
			ActiveMission nether = active(3, "Hell Gate", 1, 0, 1_000);
			Assert.eq(new MissionProgress("Fishing Trip", 9, 10), nearest(List.of(iron, fish, nether)), "largest share (9/10 before 20/32)");
			Assert.eq(new MissionProgress("Smithy supplies", 20, 32), nearest(List.of(nether, iron)), "§ codes removed");
			// tie at 50%: less left wins, then expiring sooner, then accepted earlier
			ActiveMission halfSmall = active(4, "Small", 10, 5, 9_000);
			ActiveMission halfLarge = active(5, "Large", 32, 16, 1_000);
			Assert.eq("Small", nearest(List.of(halfLarge, halfSmall)).name(), "tie: less left");
			ActiveMission late = active(6, "Late", 10, 5, 9_000);
			ActiveMission early = active(7, "Early", 10, 5, 2_000);
			Assert.eq("Early", nearest(List.of(late, early)).name(), "tie: expires sooner");
			ActiveMission firstIn = active(1, "First", 10, 5, 2_000);
			Assert.eq("First", nearest(List.of(early, firstIn)).name(), "tie: accepted earlier (instance)");
			// limits: amount 0 becomes 1, progress clamped to the amount, a missing name gives an empty string, never
			// null
			Assert.eq(new MissionProgress("Null", 0, 1), nearest(List.of(active(9, "Null", 0, -4, 1))), "amount at least 1, progress ≥ 0");
			Assert.eq(new MissionProgress("Full", 10, 10), nearest(List.of(active(10, "Full", 10, 99, 1))), "progress ≤ amount");
			ActiveMission noName = active(11, null, 3, 1, 1);
			Assert.eq("hudtest_11", nearest(List.of(noName)).name(), "no name gives mission ID");
			Assert.eq("", displayName(null), "null name");
			Assert.eq("Ab", displayName("§aA§r§lb §"), "also a § at the end");
			Assert.eq(new MissionProgress("Huge", 999_999, 1_000_000),
				nearest(List.of(active(12, "Huge", 1_000_000, 999_999, 1), active(13, "Fast", 1_000_000, 999_998, 1))), "large amounts without overflow");
		});

		SelfTests.register(F, "hud-service-player", ctx -> {
			FakePlayer p = ctx.player("MissioHud");
			UUID id = p.getUUID();
			Services.Missions svc = Services.find(Services.Missions.class).orElse(null);
			Assert.isTrue(svc instanceof MissionsHudService, "service registered");
			Assert.eq(0, svc.activeCount(id), "no data: 0 active");
			Assert.eq(null, svc.nearest(id), "no data: no nearest mission");
			Assert.isTrue(MissionStore.peek(id) == null, "reading creates no mission state");
			Assert.eq(MissionConfig.maxActive(), svc.maxActive(), "slots from missions.json");

			PlayerState s = MissionStore.state(id);
			s.active.add(active(1, "Stonemason", 64, 10, Long.MAX_VALUE));
			s.active.add(active(2, "Mining", 32, 16, Long.MAX_VALUE));
			Assert.eq(2, svc.activeCount(id), "2 active");
			Assert.eq(new MissionProgress("Mining", 16, 32), svc.nearest(id), "nearest: 16/32 before 10/64");
			s.active.get(0).progress = 60;
			Assert.eq(new MissionProgress("Stonemason", 60, 64), svc.nearest(id), "progress is read live");
			s.active.clear();
			Assert.eq(0, svc.activeCount(id), "0 active after completion");
			Assert.eq(null, svc.nearest(id), "no nearest mission after completion");
		});

		SelfTests.register(F, "hud-service-elite", ctx -> {
			Function<UUID, EliteStatus> previous = eliteSource;
			boolean warnedBefore = eliteWarned;
			ctx.cleanup(() -> {
				eliteSource = previous;
				eliteWarned = warnedBefore;
			});
			MissionsHudService svc = new MissionsHudService();
			UUID done = UUID.randomUUID();
			UUID open = UUID.randomUUID();
			eliteSource(null);
			Assert.eq(EliteStatus.NONE, svc.elite(done), "no Daily Elite gives NONE");
			eliteSource(x -> x.equals(done) ? EliteStatus.DONE : EliteStatus.OPEN);
			Assert.eq(EliteStatus.DONE, svc.elite(done), "done today gives DONE");
			Assert.eq(EliteStatus.OPEN, svc.elite(open), "still open gives OPEN");
			eliteSource(x -> null);
			Assert.eq(EliteStatus.NONE, svc.elite(open), "source returns null gives NONE");
			eliteWarned = true; // no log warning from the test
			eliteSource(x -> {
				throw new IllegalStateException("broken");
			});
			Assert.eq(EliteStatus.NONE, svc.elite(open), "source throws gives NONE (sidebar without elite line)");
		});

		// real wiring to the Daily Elite (without it the elite line would stay empty forever while everything else is
		// green)
		SelfTests.register(F, "hud-service-elite-source", ctx -> {
			Assert.isTrue(eliteSource != null, "Daily Elite source set in register()");
			Services.Missions svc = Services.find(Services.Missions.class).orElse(null);
			Assert.isTrue(svc instanceof MissionsHudService, "service registered");
			EliteConfig.Root root = EliteConfig.get().root();
			boolean enabled = root.enabled;
			ctx.cleanup(() -> EliteConfig.get().root().enabled = enabled);
			root.enabled = true;
			FakePlayer p = ctx.player("MissioHudElite");
			UUID id = p.getUUID();
			long now = System.currentTimeMillis();
			EliteService.Today t = EliteService.today(now);
			if (t == null) {
				Assert.isTrue(EliteConfig.candidates().isEmpty(), "enabled with a pool: a Daily Elite today");
				Assert.eq(EliteStatus.NONE, svc.elite(id), "empty pool gives NONE");
				ctx.detail("Daily Elite pool empty, only NONE checked");
				return;
			}
			Assert.eq(EliteStatus.OPEN, svc.elite(id), "enabled, not done today gives OPEN (not NONE)");
			Assert.isTrue(MissionStore.peek(id) == null, "reading elite state creates no mission state");
			PlayerState s = MissionStore.state(id);
			EliteService.progress(s, t).done = true;
			Assert.eq(EliteStatus.DONE, svc.elite(id), "done today gives DONE");
			Assert.eq(EliteStatus.DONE, dailyElite(id, now), "dailyElite = service");
			root.enabled = false;
			Assert.eq(EliteStatus.NONE, svc.elite(id), "turned off gives NONE (no elite line)");
			ctx.detail("today: " + t.id());
		});
	}
}
