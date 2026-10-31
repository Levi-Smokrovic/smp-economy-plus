package dev.smpeconomy.features.minigames.mega;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import dev.smpeconomy.core.compat.Mc;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.PlacedBlockTracker;

/**
 * Measures races: only growth since the start per participant, never inventory.
 * <ul>
 * <li>craft/stat: vanilla stat minus start value, for stat divided by {@code scale}</li>
 * <li>mine: growth of the mined stat of target blocks, capped by own counting via {@code
 * PlayerBlockBreakEvents.AFTER} of natural blocks only (not placed per {@link PlacedBlockTracker}, right tool, no
 * creative). So neither re-placed silk touch blocks nor Veinminer follow-ups count: Veinminer raises the origin's
 * stat but fires no AFTER.</li>
 * <li>kill: growth of the killed stat of target mobs, capped by own counting at death ({@code
 * ServerLivingEntityEvents.AFTER_DEATH}, kill credit as in vanilla), optionally only in one dimension (endermen
 * only in the overworld: no End or warped forest farm) and only at night in night races</li>
 * <li>portal: dimension change into the target with portal cooldown set, so through a portal rather than /rtp,
 * /home or /tpa</li>
 * </ul>
 * Only someone online, not in creative or spectator, who moved or looked around in the last minute can win ({@link
 * #sample}); standing AFK at a farm with a click macro doesn't win. Server thread only.
 */
public final class MegaRaceProbe implements MegaChallenge.RaceProbe {
	public static final long ACTIVE_WINDOW_MS = 60_000;
	private static final double MOVE_SQR = 0.25 * 0.25;
	private static final float TURN_DEGREES = 2f;

	/** Night (hostile mobs on the surface) runs from 13000 to 23000. */
	public static final long DAY_TICKS = 24_000;
	public static final long NIGHT_START = 13_000;
	public static final long NIGHT_END = 23_000;

	private final Function<UUID, ServerPlayer> lookup;
	private MegaConfig.Race race;
	private Set<UUID> participants = Set.of();
	/** craft/stat: the one stat; mine/kill: all stats of the targets, the cap for own counting. */
	private Stat<?> stat;
	private final List<Stat<?>> capStats = new ArrayList<>();
	private final Set<Block> blocks = new HashSet<>();
	private TagKey<Block> blockTag;
	private final Set<EntityType<?>> mobs = new HashSet<>();
	private Identifier dimension;
	private long scale = 1;
	private final Map<UUID, Long> base = new HashMap<>();
	private final Map<UUID, Long> counted = new HashMap<>();
	private final Map<UUID, double[]> pose = new HashMap<>();
	private final Map<UUID, Long> activeAt = new HashMap<>();

	public MegaRaceProbe(Function<UUID, ServerPlayer> lookup) {
		this.lookup = lookup;
	}

	public MegaConfig.Race race() {
		return race;
	}

	@Override
	public void begin(MegaConfig.Race race, Set<UUID> participants) {
		end();
		this.race = race;
		this.participants = Set.copyOf(participants);
		String target = race.target.trim();
		switch (race.kind) {
			case "craft" -> {
				Item item = Items.byId(target).orElseThrow();
				stat = Stats.ITEM_CRAFTED.get(item);
			}
			case "kill" -> {
				for (String part : target.split(",")) {
					String p = part.trim();
					if (p.startsWith(MegaConfig.CATEGORY_PREFIX)) {
						MobCategory category = MegaConfig.category(p);
						for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
							if (type.getCategory() == category) mobs.add(type);
						}
					} else {
						mobs.add(BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(p)));
					}
				}
				for (EntityType<?> type : mobs) capStats.add(Stats.ENTITY_KILLED.get(type));
				dimension = race.dimension == null || race.dimension.isBlank() ? null : Identifier.parse(race.dimension.trim());
			}
			case "stat" -> {
				Identifier id = Identifier.parse(target);
				Identifier value = BuiltInRegistries.CUSTOM_STAT.getValue(id);
				stat = Stats.CUSTOM.get(value != null ? value : id);
				scale = Math.max(1, race.scale);
			}
			case "mine" -> {
				Set<Block> all = new HashSet<>();
				for (String part : target.split(",")) {
					String p = part.trim();
					if (p.startsWith("#")) {
						blockTag = Mc.configTag(Registries.BLOCK, Identifier.parse(p.substring(1)));
						for (Holder<Block> h : BuiltInRegistries.BLOCK.getTagOrEmpty(blockTag)) all.add(h.value());
					} else {
						Block b = BuiltInRegistries.BLOCK.getValue(Identifier.parse(p));
						blocks.add(b);
						all.add(b);
					}
				}
				for (Block b : all) capStats.add(Stats.BLOCK_MINED.get(b));
			}
			case "portal" -> dimension = Identifier.parse(target);
			default -> throw new IllegalArgumentException("Unknown race kind " + race.kind);
		}
		for (UUID id : this.participants) {
			ServerPlayer p = lookup.apply(id);
			if (p == null) continue;
			if (stat != null) base.put(id, (long) p.getStats().getValue(stat));
			else if (!capStats.isEmpty()) base.put(id, capSum(p));
			pose.put(id, pose(p));
		}
	}

	private long capSum(ServerPlayer p) {
		long sum = 0;
		for (Stat<?> s : capStats) sum += p.getStats().getValue(s);
		return sum;
	}

	/** All dimensions share the overworld's time of day. */
	public static long nightTicksLeft(long dayTime) {
		long t = Math.floorMod(dayTime, DAY_TICKS);
		return t >= NIGHT_START && t < NIGHT_END ? NIGHT_END - t : 0;
	}

	private static double[] pose(ServerPlayer p) {
		return new double[] {p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot()};
	}

	@Override
	public long progress(UUID player) {
		if (race == null || !participants.contains(player)) return -1;
		long own = counted.getOrDefault(player, 0L);
		if (stat == null && capStats.isEmpty()) return own; // portal
		ServerPlayer p = lookup.apply(player);
		if (p == null) return -1;
		Long b = base.get(player);
		long now = stat != null ? p.getStats().getValue(stat) : capSum(p);
		if (b == null) {
			// wasn't measurable at the start (shouldn't happen): count from now on
			base.put(player, now);
			return 0;
		}
		long delta = Math.max(0, now - b);
		if (stat != null) return delta / scale;
		// mine/kill: only stat growth since start, capped by own counted fair breaks or kills
		return Math.min(delta, own);
	}

	/** Movement of at least 0.25 blocks or a look change of at least 2 degrees counts as active. */
	@Override
	public void sample(long now) {
		if (race == null) return;
		for (UUID id : participants) {
			ServerPlayer p = lookup.apply(id);
			if (p == null) continue;
			double[] cur = pose(p);
			double[] last = pose.put(id, cur);
			if (last == null) continue;
			double dx = cur[0] - last[0], dy = cur[1] - last[1], dz = cur[2] - last[2];
			boolean moved = dx * dx + dy * dy + dz * dz >= MOVE_SQR;
			boolean turned = Math.abs(Mth.wrapDegrees((float) (cur[3] - last[3]))) >= TURN_DEGREES || Math.abs(cur[4] - last[4]) >= TURN_DEGREES;
			if (moved || turned) activeAt.put(id, now);
		}
	}

	public long activeAt(UUID player) {
		return activeAt.getOrDefault(player, -1L);
	}

	@Override
	public boolean canWin(UUID player, long now) {
		ServerPlayer p = lookup.apply(player);
		if (p == null || p.hasDisconnected() || Items.isCreativeOrSpectator(p)) return false;
		Long at = activeAt.get(player);
		return at != null && now - at <= ACTIVE_WINDOW_MS;
	}

	@Override
	public void end() {
		race = null;
		participants = Set.of();
		stat = null;
		capStats.clear();
		blocks.clear();
		blockTag = null;
		mobs.clear();
		dimension = null;
		scale = 1;
		base.clear();
		counted.clear();
		pose.clear();
		activeAt.clear();
	}

	public void onBlockBroken(ServerPlayer player, Level level, BlockPos pos, BlockState state) {
		if (race == null || !race.kind.equals("mine") || !participants.contains(player.getUUID())) return;
		if (!(blocks.contains(state.getBlock()) || blockTag != null && state.is(blockTag))) return;
		if (Items.isCreativeOrSpectator(player) || !player.hasCorrectToolForDrops(state)) return;
		if (PlacedBlockTracker.isPlayerPlaced(level, pos)) return;
		counted.merge(player.getUUID(), 1L, Long::sum);
	}

	/**
	 * {@code killer} is the mob's kill credit ({@code getKillCredit}, like the vanilla stat), {@code level} is
	 * where it died.
	 */
	public void onKill(ServerPlayer killer, EntityType<?> type, Level level) {
		if (race == null || !race.kind.equals("kill") || killer == null || !participants.contains(killer.getUUID())) return;
		if (!mobs.contains(type) || Items.isCreativeOrSpectator(killer)) return;
		if (dimension != null && !level.dimension().identifier().equals(dimension)) return;
		if (race.night && nightTicksLeft(Mc.dayTime(level)) <= 0) return;
		counted.merge(killer.getUUID(), 1L, Long::sum);
	}

	public void onDimensionChange(ServerPlayer player, Level origin, Level destination) {
		if (race == null || !race.kind.equals("portal") || !participants.contains(player.getUUID())) return;
		if (dimension == null || !destination.dimension().identifier().equals(dimension) || origin.dimension().equals(destination.dimension())) return;
		if (!player.isOnPortalCooldown() || player.portalProcess == null) return;
		counted.put(player.getUUID(), 1L);
	}
}
