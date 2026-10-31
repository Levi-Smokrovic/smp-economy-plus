package dev.smpeconomy.core.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import dev.smpeconomy.core.PlacedBlockTracker;

/**
 * Placed sand that falls carries its mark along; otherwise placing sand in the air, dropping and breaking it would pay.
 * On start, the origin position still counts as "being broken" in the tracker since removal waits for tick end.
 */
@Mixin(FallingBlockEntity.class)
public abstract class FallingBlockEntityMixin {
	@Inject(method = "fall", at = @At("RETURN"))
	private static void smpeco$carryMark(Level level, BlockPos pos, BlockState state, CallbackInfoReturnable<FallingBlockEntity> cir) {
		if (PlacedBlockTracker.isPlayerPlaced(level, pos)) cir.getReturnValue().addTag(PlacedBlockTracker.FALLING_TAG);
	}

	// Landing: 26.3 sets the block with setBlockAndUpdate (no flags) rather than setBlock(..., 3).
	//? if >=26.3 {
	/*@WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
	private boolean smpeco$markLanding(Level level, BlockPos pos, BlockState state, Operation<Boolean> original) {
		boolean ok = original.call(level, pos, state);
		smpeco$onLanded(ok, level, pos);
		return ok;
	}
	*///?} else {
	@WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
	private boolean smpeco$markLanding(Level level, BlockPos pos, BlockState state, int flags, Operation<Boolean> original) {
		boolean ok = original.call(level, pos, state, flags);
		smpeco$onLanded(ok, level, pos);
		return ok;
	}
	//?}

	@Unique
	private void smpeco$onLanded(boolean placed, Level level, BlockPos pos) {
		if (placed && ((FallingBlockEntity) (Object) this).getTags().contains(PlacedBlockTracker.FALLING_TAG)) {
			PlacedBlockTracker.markPlaced(level, pos);
		}
	}
}
