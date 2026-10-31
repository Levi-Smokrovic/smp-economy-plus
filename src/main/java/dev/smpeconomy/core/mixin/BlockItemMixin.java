package dev.smpeconomy.core.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import dev.smpeconomy.core.PlacedBlockTracker;

/** Uses the position from the context actually used. */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
	@WrapOperation(method = "place", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/BlockItem;placeBlock(Lnet/minecraft/world/item/context/BlockPlaceContext;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
	private boolean smpeco$trackPlacement(BlockItem self, BlockPlaceContext ctx, BlockState state, Operation<Boolean> original) {
		boolean placed = original.call(self, ctx, state);
		if (placed && ctx.getPlayer() instanceof ServerPlayer player && !(player instanceof FakePlayer)) {
			PlacedBlockTracker.markPlaced(ctx.getLevel(), ctx.getClickedPos());
		}
		return placed;
	}
}
