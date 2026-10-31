package dev.smpeconomy.core.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.LavaFluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import dev.smpeconomy.core.PlacedBlockTracker;

/** Stone from lava flowing down into water (stone generator) counts as placed. */
@Mixin(LavaFluid.class)
public abstract class LavaFluidMixin {
	// 26.3 sets the stone with setBlockAndUpdate (no flags) rather than setBlock(..., 3).
	//? if >=26.3 {
	/*@WrapOperation(method = "spreadTo", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelAccessor;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
	private boolean smpeco$markGenerated(LevelAccessor level, BlockPos pos, BlockState state, Operation<Boolean> original) {
		boolean set = original.call(level, pos, state);
		if (set && level instanceof Level l) PlacedBlockTracker.onFluidGenerated(l, pos);
		return set;
	}
	*///?} else {
	@WrapOperation(method = "spreadTo", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelAccessor;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
	private boolean smpeco$markGenerated(LevelAccessor level, BlockPos pos, BlockState state, int flags, Operation<Boolean> original) {
		boolean set = original.call(level, pos, state, flags);
		if (set && level instanceof Level l) PlacedBlockTracker.onFluidGenerated(l, pos);
		return set;
	}
	//?}
}
