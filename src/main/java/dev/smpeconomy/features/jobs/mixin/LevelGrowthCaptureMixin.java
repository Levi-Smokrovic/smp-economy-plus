package dev.smpeconomy.features.jobs.mixin;

import dev.smpeconomy.core.module.Modules;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import dev.smpeconomy.features.jobs.BoostedCrops;
import dev.smpeconomy.features.jobs.GrowthCapture;

/**
 * Hot path, so only cheap checks: (1) collect positions set during bone meal, (2) drop a crop's bone meal marker once
 * something else appears there (deferred to the end of the tick). Runs before the set, so {@code getBlockState} still
 * returns the old state.
 */
@Mixin(Level.class)
public abstract class LevelGrowthCaptureMixin {
	@Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"))
	private void smpeco$jobsOnSetBlock(BlockPos pos, BlockState state, int flags, int recursionLeft, CallbackInfoReturnable<Boolean> cir) {
		if (!Modules.isEnabled("jobs")) return;
		if (GrowthCapture.isActive()) GrowthCapture.onSetBlock((Level) (Object) this, pos);
		if (!BoostedCrops.isEmpty()) BoostedCrops.onSetBlock((Level) (Object) this, pos, state);
	}
}
