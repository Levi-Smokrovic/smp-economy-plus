package dev.smpeconomy.core.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import dev.smpeconomy.core.PlacedBlockTracker;

/**
 * Cobblestone, obsidian and basalt from lava with water or blue ice (generators) count as placed, so jobs pay nothing.
 */
@Mixin(LiquidBlock.class)
public abstract class LiquidBlockMixin {
	@WrapOperation(method = "shouldSpreadLiquid", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
	private boolean smpeco$markGenerated(Level level, BlockPos pos, BlockState state, Operation<Boolean> original) {
		boolean set = original.call(level, pos, state);
		if (set) PlacedBlockTracker.onFluidGenerated(level, pos);
		return set;
	}
}
