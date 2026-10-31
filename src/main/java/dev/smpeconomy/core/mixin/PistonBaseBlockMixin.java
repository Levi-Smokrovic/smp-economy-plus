package dev.smpeconomy.core.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import dev.smpeconomy.core.PlacedBlockTracker;

/** Otherwise place, push, break would pay. */
@Mixin(PistonBaseBlock.class)
public abstract class PistonBaseBlockMixin {
	@WrapOperation(method = "moveBlocks", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/piston/PistonStructureResolver;getToPush()Ljava/util/List;"))
	private List<BlockPos> smpeco$snapshotPlaced(PistonStructureResolver resolver, Operation<List<BlockPos>> original,
			@Local(argsOnly = true) Level level, @Share("smpecoPlaced") LocalRef<List<BlockPos>> placed) {
		List<BlockPos> toPush = original.call(resolver);
		placed.set(PlacedBlockTracker.placedAmong(level, toPush));
		return toPush;
	}

	@Inject(method = "moveBlocks", at = @At("RETURN"))
	private void smpeco$movePlaced(Level level, BlockPos pos, Direction direction, boolean extending, CallbackInfoReturnable<Boolean> cir,
			@Share("smpecoPlaced") LocalRef<List<BlockPos>> placed) {
		List<BlockPos> moved = placed.get();
		if (cir.getReturnValueZ() && moved != null && !moved.isEmpty()) {
			PlacedBlockTracker.onPistonMoved(level, moved, extending ? direction : direction.getOpposite());
		}
	}
}
