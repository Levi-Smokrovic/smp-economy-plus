package dev.smpeconomy.features.jobs;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Collects positions where blocks get set during a bone meal application ({@code BonemealableBlock#performBonemeal}):
 * crops, trees from saplings, stems, huge mushrooms. {@link JobsEvents#onBonemealGrowth} then marks the paid ones as
 * not natural. Server thread only; calls from other threads are ignored.
 */
public final class GrowthCapture {
	/** Per application. A huge tree sets a few hundred blocks. */
	static final int MAX_POSITIONS = 8192;

	private static volatile boolean active;
	private static int depth;
	private static Level level;
	private static Thread thread;
	private static final Set<BlockPos> CHANGED = new LinkedHashSet<>();

	private GrowthCapture() {}

	public static void begin(Level l) {
		if (depth++ == 0) {
			level = l;
			thread = Thread.currentThread();
			CHANGED.clear();
			active = true;
		}
	}

	/** Ends the capture and returns the changed positions. When nested, only the outermost end returns them. */
	public static List<BlockPos> end() {
		if (depth <= 0) {
			depth = 0;
			return List.of();
		}
		if (--depth > 0) return List.of();
		active = false;
		level = null;
		thread = null;
		List<BlockPos> result = new ArrayList<>(CHANGED);
		CHANGED.clear();
		return result;
	}

	public static void onSetBlock(Level l, BlockPos pos) {
		if (!active || l != level || Thread.currentThread() != thread) return;
		if (CHANGED.size() < MAX_POSITIONS) CHANGED.add(pos.immutable());
	}

	public static boolean isActive() {
		return active;
	}
}
