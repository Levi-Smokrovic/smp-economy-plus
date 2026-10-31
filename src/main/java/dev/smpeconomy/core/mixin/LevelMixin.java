package dev.smpeconomy.core.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import dev.smpeconomy.core.PlacedBlockTracker;

@Mixin(Level.class)
public abstract class LevelMixin {
	@WrapOperation(
		method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;"))
	private BlockState smpeco$trackChange(LevelChunk chunk, BlockPos pos, BlockState state, int flags, Operation<BlockState> original) {
		BlockState old = original.call(chunk, pos, state, flags);
		if (old != null) PlacedBlockTracker.onBlockChanged((Level) (Object) this, pos, state);
		return old;
	}
}
