package dev.smpeconomy.features.jobs.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Tree Harvester 9.x (the 1.21.11 build) drops every log itself inside its break event and cancels the break, so no
 * after-break event fires for any log of the tree. 10.x fires them itself and lives in another package, so this does
 * nothing there. The target has no descriptor because the {@code remap = false} string would not get remapped.
 */
@Pseudo
@Mixin(targets = "com.natamus.treeharvester_common_fabric.events.TreeCutEvents", remap = false)
public abstract class TreeHarvesterCompatMixin {
	@WrapOperation(method = "onTreeHarvest", at = @At(value = "INVOKE", target = "Lcom/natamus/collective_common_fabric/functions/BlockFunctions;dropBlock", remap = false),
		require = 0, remap = false)
	private static void smpeco$fireAfterBreak(Level level, BlockPos pos, Operation<Void> original, @Local(argsOnly = true) Player player) {
		BlockState state = level.getBlockState(pos);
		BlockEntity blockEntity = level.getBlockEntity(pos);
		original.call(level, pos);
		PlayerBlockBreakEvents.AFTER.invoker().afterBlockBreak(level, player, pos, state, blockEntity);
	}
}
