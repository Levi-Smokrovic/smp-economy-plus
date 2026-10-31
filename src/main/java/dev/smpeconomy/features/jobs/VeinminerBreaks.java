package dev.smpeconomy.features.jobs;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Asks claim mods about a follow-up block of a vein. See {@code mixin.VeinminerCompatMixin}. */
public final class VeinminerBreaks {
	private static boolean asking;

	private VeinminerBreaks() {}

	public static boolean asking() {
		return asking;
	}

	public static boolean mayBreak(Level level, Player player, BlockPos pos, BlockState state) {
		if (level.isClientSide() || asking) return true;
		BlockEntity blockEntity = level.getBlockEntity(pos);
		boolean allowed;
		asking = true;
		try {
			allowed = PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(level, player, pos, state, blockEntity);
		} finally {
			asking = false;
		}
		if (!allowed) {
			PlayerBlockBreakEvents.CANCELED.invoker().onBlockBreakCanceled(level, player, pos, state, blockEntity);
			return false;
		}
		// A listener may have broken it already (Tree Harvester fells the tree in this event); breaking again would drop twice.
		return level.getBlockState(pos) == state;
	}
}
