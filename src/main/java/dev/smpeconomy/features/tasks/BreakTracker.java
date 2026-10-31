package dev.smpeconomy.features.tasks;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.Bots;
import dev.smpeconomy.core.PlacedBlockTracker;

/**
 * Anti-exploit for "mine X" tasks: the vanilla {@code mined} stat also counts blocks the player placed (place, break,
 * repeat). {@code ServerPlayerGameModeMixin} wraps {@code ServerPlayerGameMode#destroyBlock} in a frame (before: stat
 * value, position, placed according to {@link PlacedBlockTracker}, generators included). {@code BlockMixin} reports
 * every {@code Block#destroy} (with block state) inside the frame; vanilla calls that only for player breaks, and
 * Veinminer for every follow-up block.
 * <p>Veinminer up to 2.12 (checked: 2.11.2; from 2.13 every follow-up block goes through {@code destroyBlock} and gets
 * its own frame) runs in {@code PlayerBlockBreakEvents.BEFORE}, so inside the frame. Follow-up blocks don't go through
 * {@code destroyBlock} but {@code Level#removeBlock} + {@code Block#destroy} (immediately with {@code delay = 0},
 * otherwise in later ticks), and the stat of the origin block rises once by {@code count - 1}, also for follow-up
 * blocks of another type in the same group (group "Ores" = {@code #c:ores}: coal follow-ups raise "diamond ore mined").
 * So a follow-up block belongs only to tasks containing its own stat. At the end of the frame, per open task:
 * <pre>
 *   own        = rise of the origin stat - rise from nested destroyBlock frames (other mods)
 *   counted(b) = observed natural follow-up blocks of type b (other types: minus the rise of
 *                their own stat in the frame, which already counts directly)
 *   task contains the origin stat:
 *     allowed  = (origin natural ? 1 : 0) + sum of counted(b) for all b whose stat is in the task
 *     excluded = max(0, own - allowed)
 *   task doesn't contain it:
 *     credit   = sum of counted(b) for all b other than the origin type whose stat is in the task
 * </pre>
 * Placed follow-up blocks never count, a placed origin excludes only itself, follow-up blocks of another type never
 * fill the origin's task (at most their own). "Natural" also means the correct tool ({@code hasCorrectToolForDrops}, as
 * in vanilla, where the stat doesn't rise otherwise); each position counts at most once per frame. Rises that weren't
 * observed (Veinminer with {@code delay > 0}: follow-ups only after the frame; a tool about to break) don't count
 * either, to be safe. Server thread only.
 * <p>Right-click harvest (checked: rightclickharvest-fabric 4.6.1): {@code RightClickHarvest#completeHarvest} runs in
 * {@code UseBlockCallback} (so inside {@code ServerPlayerGameMode#useItemOn}), fires {@code
 * PlayerBlockBreakEvents.BEFORE} (level, player, harvested position, state), then raises {@code BLOCK_MINED} of the
 * block by 1 itself and removes the block ({@code Level#removeBlock} for sugar cane and cactus, replanting for crops),
 * with no {@code destroyBlock}. So a BEFORE listener in an early phase ({@link #registerHarvestFrames}, before
 * Veinminer) opens a right-click frame for the harvested position when a {@code useItemOn} is running and no break
 * frame is open. It closes at the next BEFORE of the same click (radius harvest: one position after another) or at the
 * end of {@code useItemOn} (mixin, in finally), so synchronously before tasks are checked. Evaluation is the same as
 * above: a placed harvest (sugar cane set on sugar cane) doesn't count.
 */
public final class BreakTracker {
	private static final class Frame {
		final ServerPlayer player;
		/** Null only in selftests without a world. */
		final ServerLevel level;
		final BlockPos pos;
		final Block block;
		final Stat<Block> stat;
		final int before;
		/** Origin counts as a natural break: not placed, correct tool. */
		final boolean originNatural;
		/**
		 * Right-click frame (BEFORE without destroyBlock, e.g. RightClickHarvest); always alone at the bottom of the
		 * stack.
		 */
		final boolean harvest;
		/** Stat rise from nested breaks of the same block type. */
		long nested;
		/** Natural follow-up blocks observed in the frame, per block type. */
		final Map<Block, Long> natural = new LinkedHashMap<>();
		/** "Mined" stat of other block types at their first observation in the frame. */
		final Map<Block, Integer> otherBefore = new HashMap<>();
		/** Positions already reported; each counts at most once. */
		final Set<BlockPos> seen = new HashSet<>();

		Frame(ServerPlayer player, ServerLevel level, BlockPos pos, Block block, int before, boolean originNatural, boolean harvest) {
			this.player = player;
			this.level = level;
			this.pos = pos;
			this.block = block;
			this.stat = Stats.BLOCK_MINED.get(block);
			this.before = before;
			this.originNatural = originNatural;
			this.harvest = harvest;
		}
	}

	/**
	 * Result of a break frame for the tasks (see class docs). {@code others}: mined key of another block type mapped to
	 * natural follow-up blocks that neither the origin stat nor their own stat counted directly.
	 */
	static final class Outcome {
		final String originKey;
		final long own;
		final long originNatural;
		final Map<String, Long> others;

		Outcome(String originKey, long own, long originNatural, Map<String, Long> others) {
			this.originKey = originKey;
			this.own = own;
			this.originNatural = originNatural;
			this.others = others;
		}

		boolean relevant() {
			return own > originNatural || !others.isEmpty();
		}

		/** Exclusion for a task whose stats contain {@link #originKey}. */
		long excludedFor(Collection<String> taskKeys) {
			long allowed = originNatural;
			for (Map.Entry<String, Long> e : others.entrySet()) {
				if (taskKeys.contains(e.getKey())) allowed += e.getValue();
			}
			return Math.max(0, own - allowed);
		}

		/** Credit for a task whose stats don't contain {@link #originKey}. */
		long creditFor(Collection<String> taskKeys) {
			long credit = 0;
			for (Map.Entry<String, Long> e : others.entrySet()) {
				if (taskKeys.contains(e.getKey())) credit += e.getValue();
			}
			return credit;
		}
	}

	private static final Deque<Frame> STACK = new ArrayDeque<>();
	/**
	 * Phase before {@link Event#DEFAULT_PHASE}: the right-click frame has to be open before Veinminer breaks follow-up
	 * blocks.
	 */
	private static final Identifier HARVEST_PHASE = Identifier.fromNamespaceAndPath(SmpEconomy.MOD_ID, "tasks_harvest_frame");
	/** Nesting depth of running {@code ServerPlayerGameMode#useItemOn} calls (mixin). */
	private static int useDepth;
	private static boolean harvestErrorLogged;

	private BreakTracker() {}

	/** Before the break, while the block still stands. Returns a token for {@link #end}; null means nothing to do. */
	public static Object begin(ServerPlayer player, ServerLevel level, BlockPos pos) {
		if (player == null || Bots.isBot(player) || level == null || pos == null) return null;
		if (!level.getServer().isSameThread()) return null;
		BlockState state = level.getBlockState(pos);
		if (state.isAir()) return null;
		return push(player, level, pos.immutable(), state, PlacedBlockTracker.isPlayerPlaced(level, pos), false);
	}

	private static Frame push(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state, boolean placed, boolean harvest) {
		Block block = state.getBlock();
		boolean originNatural = !placed && player.hasCorrectToolForDrops(state);
		Frame f = new Frame(player, level, pos, block, player.getStats().getValue(Stats.BLOCK_MINED.get(block)), originNatural, harvest);
		if (pos != null) f.seen.add(pos);
		STACK.push(f);
		return f;
	}

	/**
	 * Registers the BEFORE listener for right-click frames in its own phase ahead of the default phase (Veinminer,
	 * jobs, minigames), so the frame is open when Veinminer breaks follow-up blocks in the same BEFORE. Always returns
	 * {@code true}: it never prevents a harvest or break.
	 */
	static void registerHarvestFrames() {
		PlayerBlockBreakEvents.BEFORE.addPhaseOrdering(HARVEST_PHASE, Event.DEFAULT_PHASE);
		PlayerBlockBreakEvents.BEFORE.register(HARVEST_PHASE, (level, player, pos, state, blockEntity) -> {
			try {
				onBreakBefore(level, player, pos, state);
			} catch (RuntimeException e) {
				logHarvestError(e);
			}
			return true;
		});
	}

	private static void logHarvestError(RuntimeException e) {
		if (harvestErrorLogged) {
			SmpEconomy.LOG.warn("[SMPEco] tasks: right-click frame failed: {}", e.toString());
			return;
		}
		harvestErrorLogged = true;
		SmpEconomy.LOG.error("[SMPEco] tasks: right-click frame failed", e);
	}

	/** Mixin at the head of {@code ServerPlayerGameMode#useItemOn}. Returns a token for {@link #endUse}. */
	public static boolean beginUse(ServerLevel level) {
		if (level == null || !level.getServer().isSameThread()) return false;
		useDepth++;
		return true;
	}

	/**
	 * Mixin in the finally of {@code ServerPlayerGameMode#useItemOn}: the end of the outermost right-click closes the
	 * open right-click frame. RightClickHarvest has harvested and raised the stat by then.
	 */
	public static void endUse(boolean counted) {
		if (!counted) return;
		if (useDepth > 0) useDepth--;
		if (useDepth > 0) return;
		try {
			closeHarvestFrame();
		} catch (RuntimeException e) {
			STACK.removeIf(f -> f.harvest); // never leave a frame behind
			logHarvestError(e);
		}
	}

	/**
	 * {@code PlayerBlockBreakEvents.BEFORE} (early phase): a mod firing the event during a right-click with no {@code
	 * destroyBlock} frame open is a right-click harvest, so this opens a frame for the harvested position. No
	 * FakePlayers, as in {@link #begin}.
	 */
	public static void onBreakBefore(Level level, Player player, BlockPos pos, BlockState state) {
		if (!(player instanceof ServerPlayer sp) || Bots.isBot(sp)) return;
		beforeHarvest(sp, level, pos, state);
	}

	static void beforeHarvest(ServerPlayer player, Level level, BlockPos pos, BlockState state) {
		if (useDepth <= 0 || !(level instanceof ServerLevel serverLevel) || pos == null || state == null || state.isAir()) return;
		if (!serverLevel.getServer().isSameThread()) return;
		Frame top = STACK.peek();
		if (top != null && !top.harvest) return; // inside a break, whose frame already counts
		if (top != null) end(top); // the previous harvest of this click is done (radius harvest goes one by one)
		if (!STACK.isEmpty()) return;
		push(player, serverLevel, pos.immutable(), state, PlacedBlockTracker.isPlayerPlaced(serverLevel, pos), true);
	}

	/** There is at most one, at the very bottom of the stack. */
	private static void closeHarvestFrame() {
		Frame harvest = null;
		for (Frame f : STACK) {
			if (f.harvest) harvest = f;
		}
		if (harvest != null) end(harvest);
	}

	/**
	 * From {@code BlockMixin} (head of {@code Block#destroy}): a player break removed {@code state}. Counts for the
	 * innermost open frame in the same level, unless it's that frame's own origin. "Placed" still holds in the break
	 * tick since {@link PlacedBlockTracker} removes at the end of the tick.
	 */
	public static void onDestroyed(LevelAccessor level, BlockPos pos, BlockState state) {
		if (!(level instanceof ServerLevel serverLevel) || pos == null || !serverLevel.getServer().isSameThread()) return;
		Frame f = STACK.peek();
		if (f == null || level != f.level) return;
		BlockPos at = pos.immutable();
		if (!f.seen.add(at)) return; // origin or already reported
		record(f, state, PlacedBlockTracker.isPlayerPlaced(f.level, at));
	}

	private static void record(Frame f, BlockState state, boolean placed) {
		if (placed || state == null || state.isAir()) return; // placed or unknown: counts for no task
		if (!f.player.hasCorrectToolForDrops(state)) return; // vanilla doesn't count it without the correct tool either
		Block b = state.getBlock();
		if (b != f.block && !f.otherBefore.containsKey(b)) {
			f.otherBefore.put(b, f.player.getStats().getValue(Stats.BLOCK_MINED.get(b)));
		}
		f.natural.merge(b, 1L, Long::sum);
	}

	/** After the break, always, also on abort or exception: the mixin calls this in finally. */
	public static void end(Object token) {
		if (!(token instanceof Frame f)) return;
		if (!STACK.contains(f)) return;
		while (!STACK.isEmpty() && STACK.pop() != f) {
			// discard orphaned inner frames (shouldn't happen with try/finally)
		}
		long total = (long) f.player.getStats().getValue(f.stat) - f.before;
		long own = total - f.nested;
		Frame parent = STACK.peek();
		if (parent != null && total > 0 && parent.player == f.player && parent.stat.equals(f.stat)) parent.nested += total;
		Outcome o = outcome(f, own);
		if (o.relevant()) TasksService.onBlockBroken(f.player, o);
	}

	private static Outcome outcome(Frame f, long own) {
		long originNatural = (f.originNatural ? 1 : 0) + f.natural.getOrDefault(f.block, 0L);
		Map<String, Long> others = new LinkedHashMap<>();
		for (Map.Entry<Block, Long> e : f.natural.entrySet()) {
			Block b = e.getKey();
			if (b == f.block) continue;
			long rise = (long) f.player.getStats().getValue(Stats.BLOCK_MINED.get(b)) - f.otherBefore.getOrDefault(b, 0);
			long uncounted = e.getValue() - Math.max(0, rise);
			if (uncounted > 0) others.merge(StatRefs.minedKey(b), uncounted, Long::sum);
		}
		return new Outcome(StatRefs.minedKey(f.block), Math.max(0, own), originNatural, others);
	}

	/** Pure calculation for a single-type break (see class docs): how much of the own rise doesn't count. */
	static long excludedAmount(long own, boolean originPlaced, long extraNatural) {
		long natural = (originPlaced ? 0 : 1) + Math.max(0, extraNatural);
		return Math.max(0, own - natural);
	}

	/** For selftests: stack depth, must be 0 after every break. */
	static int depth() {
		return STACK.size();
	}

	static Object beginForTest(ServerPlayer player, Block block, boolean placed) {
		return push(player, null, null, block.defaultBlockState(), placed, false);
	}

	/**
	 * For selftests: simulates a break in a level with no block change, so {@code Block#destroy} counts through the
	 * mixin.
	 */
	static Object beginForTest(ServerPlayer player, ServerLevel level, BlockPos pos, Block block) {
		return push(player, level, pos.immutable(), block.defaultBlockState(), PlacedBlockTracker.isPlayerPlaced(level, pos), false);
	}

	static void recordForTest(Block block, boolean placed) {
		Frame f = STACK.peek();
		if (f != null) record(f, block.defaultBlockState(), placed);
	}

	static void clear() {
		STACK.clear();
		useDepth = 0;
	}
}
