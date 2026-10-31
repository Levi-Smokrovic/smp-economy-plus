package dev.smpeconomy.features.jobs.mixin;

import dev.smpeconomy.core.module.Modules;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import dev.smpeconomy.features.jobs.JobsEvents;
import dev.smpeconomy.features.jobs.VeinminerBreaks;

/**
 * Veinminer 2.11 and older: {@code VeinMinerEvent#destroyBlock} breaks the other blocks of a vein itself ({@code
 * removeBlock} + {@code Block#destroy}) with no {@code PlayerBlockBreakEvents} per block, so claim mods are never asked
 * and jobs would not pay. Only the origin goes through vanilla. Payment hooks the end of that method, where
 * PlacedBlockTracker still sees the block since removal waits for the end of the tick. From 2.12 the method is gone and
 * normal break events do all of this. {@code @Pseudo} + {@code require = 0} only cover a missing method; a same-named
 * method with another signature would throw at class load, so {@code ModuleMixinPlugin} applies this mixin only when
 * {@code VeinminerCompat#hookMatches} finds the right methods.
 */
@Pseudo
@Mixin(targets = "de.miraculixx.veinminer.event.VeinMinerEvent", remap = false)
public abstract class VeinminerCompatMixin {
	@Inject(method = "destroyBlock", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
	private void smpeco$askProtection(BlockState state, ItemStack tool, Level level, BlockPos pos, Player player, BlockPos origin, CallbackInfo ci) {
		if (!VeinminerBreaks.mayBreak(level, player, pos, state)) ci.cancel();
	}

	@Inject(method = "destroyBlock", at = @At("TAIL"), require = 0, remap = false)
	private void smpeco$onVeinmined(BlockState state, ItemStack tool, Level level, BlockPos pos, Player player, BlockPos origin, CallbackInfo ci) {
		if (!Modules.isEnabled("jobs")) return;
		JobsEvents.onVeinmined(player, level, pos, state);
	}

	/** Veinminer also listens on the event we fire for each block; it must not start a new vein from there. */
	@Inject(method = "onBlockBreakBefore", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
	private void smpeco$noNestedVein(Level level, Player player, BlockPos pos, BlockState state, CallbackInfoReturnable<Boolean> cir) {
		if (VeinminerBreaks.asking()) cir.setReturnValue(true);
	}
}
