package dev.smpeconomy.features.minigames.mixin;

import dev.smpeconomy.core.module.Modules;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import dev.smpeconomy.features.minigames.MinigamesFeature;

/**
 * Makes the treasure chest explosion-proof by removing its position from the final block list right before
 * destruction. Hooks {@code interactWithBlocks} rather than {@code calculateExplodedPositions} because Lithium
 * fills the list there with its own RETURN inject, so ordering wouldn't be guaranteed.
 */
@Mixin(ServerExplosion.class)
public abstract class ServerExplosionMixin {
	@Shadow
	@Final
	private ServerLevel level;

	@Inject(method = "interactWithBlocks", at = @At("HEAD"))
	private void smpeco$protectTreasure(List<BlockPos> positions, CallbackInfo ci) {
		if (!Modules.isEnabled("minigames")) return;
		if (positions == null || positions.isEmpty() || MinigamesFeature.treasure().active().isEmpty()) return;
		positions.removeIf(pos -> MinigamesFeature.treasure().isTreasure(this.level, pos));
	}
}
