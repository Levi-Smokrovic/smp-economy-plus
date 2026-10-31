package dev.smpeconomy.features.settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.Settings;

/**
 * Rules for the spawn switches, called from the mixins on the server thread.
 *
 * <p>{@link Settings#MOBSPAWN}: natural spawning of hostile mobs ({@link MobCategory#MONSTER}) is blocked if the
 * nearest player (no spectators, as in vanilla) is within {@value #MOBSPAWN_RADIUS} blocks of the spawn position and
 * has the switch off. Covered are {@code NaturalSpawner} (including structures such as fortresses and outposts),
 * pillager patrols ({@code PatrolSpawner}), zombie sieges ({@code VillageSiege}) and zombified piglins from nether
 * portals ({@code NetherPortalBlock}). Not covered, because they are triggered spawns: skeleton horse traps, sculk
 * shrieker wardens, silverfish, zombie reinforcements and summons. Spawners, trial spawners, spawn eggs, raids,
 * golems and withers stay untouched. Economy side effect: the switch acts like free spawn-proofing in the radius, and
 * mob farms outside it get more of the monster cap.
 *
 * <p>{@link Settings#PHANTOMS}: {@code PhantomSpawner} skips players with the switch off.
 *
 * <p>Errors here must never break spawning: exceptions are logged and count as allowed.
 */
public final class SpawnRules {
	/** Blocks, 3D like vanilla spawn distances. */
	public static final double MOBSPAWN_RADIUS = 64.0;
	private static final double MOBSPAWN_RADIUS_SQ = MOBSPAWN_RADIUS * MOBSPAWN_RADIUS;

	private static boolean warnedMobspawn;
	private static boolean warnedPhantom;

	/** For {@code /smpeco settings phantomtest}: record phantom checks without spawning anything. */
	private static Map<UUID, Boolean> phantomDryRun;

	private SpawnRules() {}

	/**
	 * Mixin {@code NaturalSpawner#isValidSpawnPostitionForType}: true blocks this natural spawn position. Position as
	 * in vanilla (block center, Y at the block's bottom).
	 */
	public static boolean blocksNaturalSpawn(ServerLevel level, MobCategory category, EntityType<?> type, BlockPos pos) {
		try {
			return blocksNaturalSpawn(level.players(), category, type, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
		} catch (RuntimeException e) {
			warnMobspawn(e);
			return false;
		}
	}

	/**
	 * Mixins for spawn paths outside {@code NaturalSpawner} (patrols, sieges, portal piglins): true blocks this mob
	 * at exactly this position. Same rule as above, with the category taken from the type.
	 */
	public static boolean blocksSpawnAt(ServerLevel level, EntityType<?> type, double x, double y, double z) {
		try {
			return blocksSpawnAt(level.players(), type, x, y, z);
		} catch (RuntimeException e) {
			warnMobspawn(e);
			return false;
		}
	}

	/** With an explicit player list, for selftests. Without a type nothing is blocked. */
	public static boolean blocksSpawnAt(List<? extends Player> players, EntityType<?> type, double x, double y, double z) {
		return type != null && blocksNaturalSpawn(players, type.getCategory(), type, x, y, z);
	}

	private static void warnMobspawn(RuntimeException e) {
		if (!warnedMobspawn) {
			warnedMobspawn = true;
			SmpEconomy.LOG.error("[SMPEco] settings: mob spawning rule failed, spawning stays allowed", e);
		}
	}

	/** With an explicit player list, for selftests with FakePlayers that aren't in the world's list. */
	public static boolean blocksNaturalSpawn(List<? extends Player> players, MobCategory category, EntityType<?> type, double x, double y, double z) {
		if (!isMonster(category, type) || !Settings.available(Settings.MOBSPAWN)) return false;
		Player decider = decidingPlayer(players, x, y, z);
		return decider != null && !Settings.get(decider.getUUID(), Settings.MOBSPAWN);
	}

	/** Only hostile mobs; animals and water and cave mobs stay untouched. */
	public static boolean isMonster(MobCategory category, EntityType<?> type) {
		return category == MobCategory.MONSTER || (type != null && type.getCategory() == MobCategory.MONSTER);
	}

	/** The nearest non-spectator if within {@value #MOBSPAWN_RADIUS} blocks, otherwise null. */
	public static Player decidingPlayer(Iterable<? extends Player> players, double x, double y, double z) {
		return nearestWithin(players, p -> p.distanceToSqr(x, y, z), p -> !p.isSpectator(), MOBSPAWN_RADIUS);
	}

	/** Nearest element, the first on a tie, if within {@code radius}. {@code distSq} gives the squared distance. */
	public static <T> T nearestWithin(Iterable<? extends T> items, ToDoubleFunction<? super T> distSq, Predicate<? super T> eligible, double radius) {
		T best = null;
		double bestDist = Double.MAX_VALUE;
		for (T item : items) {
			if (item == null || !eligible.test(item)) continue;
			double d = distSq.applyAsDouble(item);
			if (Double.isNaN(d)) continue;
			if (d < bestDist) {
				bestDist = d;
				best = item;
			}
		}
		return best != null && bestDist <= radius * radius ? best : null;
	}

	public static double radiusSq() {
		return MOBSPAWN_RADIUS_SQ;
	}

	/** Mixin {@code PhantomSpawner#tick}: true skips this player, like a spectator. */
	public static boolean skipPhantomsFor(ServerPlayer player) {
		boolean skip;
		try {
			skip = Settings.available(Settings.PHANTOMS) && !Settings.get(player.getUUID(), Settings.PHANTOMS);
		} catch (RuntimeException e) {
			if (!warnedPhantom) {
				warnedPhantom = true;
				SmpEconomy.LOG.error("[SMPEco] settings: phantom rule failed, phantoms stay allowed", e);
			}
			skip = false;
		}
		if (phantomDryRun != null) {
			phantomDryRun.put(player.getUUID(), skip);
			return true; // Diagnostics: never actually spawn.
		}
		return skip;
	}

	/** {@link #skipPhantomsFor} records and skips all players until the round ends. */
	static void beginPhantomDryRun() {
		phantomDryRun = new LinkedHashMap<>();
	}

	/** Returns player to would-have-been-skipped. */
	static Map<UUID, Boolean> endPhantomDryRun() {
		Map<UUID, Boolean> result = phantomDryRun == null ? Map.of() : Collections.unmodifiableMap(phantomDryRun);
		phantomDryRun = null;
		return result;
	}

	static boolean phantomDryRunActive() {
		return phantomDryRun != null;
	}

	static List<String> names(ServerLevel level, Iterable<UUID> ids) {
		List<String> out = new ArrayList<>();
		for (UUID id : ids) {
			Player p = level.getServer().getPlayerList().getPlayer(id);
			out.add(p == null ? id.toString() : p.getGameProfile().name());
		}
		return out;
	}
}
