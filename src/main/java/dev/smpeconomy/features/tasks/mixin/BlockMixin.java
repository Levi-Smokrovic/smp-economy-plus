package dev.smpeconomy.features.tasks.mixin;

import dev.smpeconomy.core.module.Modules;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import dev.smpeconomy.features.tasks.BreakTracker;

/**
 * Vanilla calls {@code Block#destroy} only after a player break ({@code ServerPlayerGameMode#destroyBlock}), Veinminer
 * also for every follow-up block (after {@code Level#removeBlock}, no {@code destroyBlock}). {@link BreakTracker}
 * assigns these reports to the open break frame and checks there whether each position was player-placed, plus the
 * block type ({@code state}, Veinminer passes the follow-up's state): a follow-up block counts only for tasks with its
 * own stat.
 */
@Mixin(Block.class)
public abstract class BlockMixin {
	@Inject(method = "destroy(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V", at = @At("HEAD"))
	private void smpeco$tasksTrackDestroyed(LevelAccessor level, BlockPos pos, BlockState state, CallbackInfo ci) {
		if (!Modules.isEnabled("tasks")) return;
		BreakTracker.onDestroyed(level, pos, state);
	}
}
