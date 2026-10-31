package dev.smpeconomy.features.jobs;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.DataStore;

/**
 * Crops sped up with bone meal, per dimension, stored in {@code jobs_boosted.json}. The farmer gets nothing for them,
 * otherwise buying bone meal from the shop and harvesting instantly would print money.
 * Markers go away on harvest, or at the end of the tick once the position turns into air, fluid or another block, so
 * {@code PlayerBlockBreakEvents.AFTER} can still see them. Server thread only.
 */
public final class BoostedCrops {
	/** Past this the oldest entries go, and that crop simply pays once. */
	static final int MAX_ENTRIES = 200_000;
	private static boolean capWarned;

	static final class Data {
		/** Positions are {@code BlockPos.asLong()}, in insertion order. */
		Map<String, LinkedHashSet<Long>> positions = new HashMap<>();
	}

	private static final DataStore<Data> STORE = new DataStore<>("jobs_boosted.json", Data.class, Data::new);
	/** Fast pre-filter for the hot path in Level.setBlock: all positions regardless of dimension. */
	private static final LongOpenHashSet ANY = new LongOpenHashSet();
	private static final Map<String, LongOpenHashSet> PENDING_REMOVAL = new HashMap<>();
	private static final Map<ResourceKey<Level>, String> DIM_KEYS = new IdentityHashMap<>();
	private static int size;

	static {
		STORE.onLoad(data -> {
			if (data.positions == null) data.positions = new HashMap<>();
			data.positions.values().removeIf(s -> s == null);
			data.positions.values().forEach(s -> s.removeIf(v -> v == null));
			rebuild(data);
		});
	}

	private BoostedCrops() {}

	public static void bootstrap() {
	}

	public static boolean isEmpty() {
		return size == 0;
	}

	public static int size() {
		return size;
	}

	public static boolean contains(Level level, BlockPos pos) {
		if (size == 0) return false;
		long p = pos.asLong();
		if (!ANY.contains(p)) return false;
		LinkedHashSet<Long> set = data().positions.get(key(level));
		return set != null && set.contains(p);
	}

	public static void add(Level level, BlockPos pos) {
		String dim = key(level);
		long p = pos.asLong();
		LongOpenHashSet pending = PENDING_REMOVAL.get(dim);
		if (pending != null) pending.remove(p);
		LinkedHashSet<Long> set = data().positions.computeIfAbsent(dim, k -> new LinkedHashSet<>());
		if (!set.add(p)) return;
		ANY.add(p);
		size++;
		while (size > MAX_ENTRIES) {
			// evict the oldest entry of the largest dimension, never the one just added
			LinkedHashSet<Long> victim = null;
			for (LinkedHashSet<Long> s : data().positions.values()) {
				if (s.size() > (s == set ? 1 : 0) && (victim == null || s.size() > victim.size())) victim = s;
			}
			if (victim == null) break;
			if (!capWarned) {
				capWarned = true;
				dev.smpeconomy.SmpEconomy.LOG.warn("[SMPEco] jobs: reached {} bone meal markers, evicting the oldest", MAX_ENTRIES);
			}
			Iterator<Long> it = victim.iterator();
			long oldest = it.next();
			it.remove();
			size--;
			rebuildAnyIfNeeded(oldest);
		}
		STORE.markDirty();
	}

	/** For right-click harvest, where the crop is reset instead of broken. */
	public static void remove(Level level, BlockPos pos) {
		if (size == 0) return;
		String dim = key(level);
		long p = pos.asLong();
		LongOpenHashSet pending = PENDING_REMOVAL.get(dim);
		if (pending != null) pending.remove(p);
		LinkedHashSet<Long> set = data().positions.get(dim);
		if (set != null && set.remove(p)) {
			size--;
			rebuildAnyIfNeeded(p);
			STORE.markDirty();
		}
	}

	/** Hot path (mixin at the head of {@code Level.setBlock}). Growth keeps the marker: same block, different age. */
	public static void onSetBlock(Level level, BlockPos pos, BlockState newState) {
		if (size == 0 || !ANY.contains(pos.asLong())) return;
		if (!(level instanceof ServerLevel serverLevel) || !serverLevel.getServer().isSameThread()) return;
		if (!contains(level, pos)) return;
		boolean gone = newState.isAir() || newState.getBlock() instanceof LiquidBlock || newState.getBlock() != level.getBlockState(pos).getBlock();
		if (gone) PENDING_REMOVAL.computeIfAbsent(key(level), k -> new LongOpenHashSet()).add(pos.asLong());
	}

	public static void endTick() {
		if (PENDING_REMOVAL.isEmpty()) return;
		boolean changed = false;
		for (Map.Entry<String, LongOpenHashSet> e : PENDING_REMOVAL.entrySet()) {
			LinkedHashSet<Long> set = data().positions.get(e.getKey());
			if (set == null) continue;
			for (long p : e.getValue()) {
				if (set.remove(p)) {
					size--;
					rebuildAnyIfNeeded(p);
					changed = true;
				}
			}
		}
		PENDING_REMOVAL.clear();
		if (changed) STORE.markDirty();
	}

	public static void onServerStopping(MinecraftServer server) {
		endTick();
	}

	private static Data data() {
		return STORE.get();
	}

	private static void rebuild(Data data) {
		ANY.clear();
		PENDING_REMOVAL.clear();
		int n = 0;
		for (LinkedHashSet<Long> set : data.positions.values()) {
			for (long p : set) ANY.add(p);
			n += set.size();
		}
		size = n;
	}

	/** {@link #ANY} contains a position only while it is marked in some dimension. */
	private static void rebuildAnyIfNeeded(long p) {
		for (LinkedHashSet<Long> set : data().positions.values()) {
			if (set.contains(p)) return;
		}
		ANY.remove(p);
	}

	private static String key(Level level) {
		ResourceKey<Level> dim = level.dimension();
		String k = DIM_KEYS.get(dim);
		if (k == null) {
			k = dim.identifier().toString();
			DIM_KEYS.put(dim, k);
		}
		return k;
	}

	static void logState() {
		SmpEconomy.LOG.info("[SMPEco] jobs: {} boosted crops marked", size);
	}
}
