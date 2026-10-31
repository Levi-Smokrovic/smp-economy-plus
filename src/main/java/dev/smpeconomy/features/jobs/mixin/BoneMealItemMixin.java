package dev.smpeconomy.features.jobs.mixin;

import dev.smpeconomy.core.module.Modules;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import dev.smpeconomy.features.jobs.GrowthCapture;
import dev.smpeconomy.features.jobs.JobsEvents;

/**
 * Anti-exploit: whatever grows during a bone meal application ({@code BoneMealItem#growCrop} into {@code
 * performBonemeal}, also from dispensers) doesn't count as natural, otherwise shop bone meal plus instant trees or
 * wheat would print money. {@link GrowthCapture} collects the positions (Level.setBlock mixin).
 */
@Mixin(BoneMealItem.class)
public abstract class BoneMealItemMixin {
	// 26.3: performBonemeal also takes the source (BonemealSource; growCrop passes INTERACTION)
	//? if >=26.3 {
	/*@WrapOperation(
		method = "growCrop",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/BonemealableBlock;performBonemeal(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/BonemealSource;)V"))
	private static void smpeco$captureGrowth(BonemealableBlock block, ServerLevel level, RandomSource random, BlockPos pos, BlockState state,
			net.minecraft.world.level.block.BonemealSource source, Operation<Void> original) {
		smpeco$grow(level, () -> original.call(block, level, random, pos, state, source));
	}
	*///?} else {
	@WrapOperation(
		method = "growCrop",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/BonemealableBlock;performBonemeal(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V"))
	private static void smpeco$captureGrowth(BonemealableBlock block, ServerLevel level, RandomSource random, BlockPos pos, BlockState state, Operation<Void> original) {
		smpeco$grow(level, () -> original.call(block, level, random, pos, state));
	}
	//?}

	/** Runs vanilla growth; with jobs enabled it records and reports the changed blocks. */
	@Unique
	private static void smpeco$grow(ServerLevel level, Runnable vanilla) {
		if (!Modules.isEnabled("jobs")) {
			vanilla.run();
			return;
		}
		GrowthCapture.begin(level);
		List<BlockPos> changed;
		try {
			vanilla.run();
		} finally {
			changed = GrowthCapture.end();
		}
		JobsEvents.onBonemealGrowth(level, changed);
	}
}
