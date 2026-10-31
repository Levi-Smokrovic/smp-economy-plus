package dev.smpeconomy.features.settings.mixin;

import dev.smpeconomy.core.module.Modules;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.PhantomSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import dev.smpeconomy.features.settings.SpawnRules;

/** Players with the switch off are skipped in PhantomSpawner like spectators. */
@Mixin(PhantomSpawner.class)
public abstract class PhantomSpawnerMixin {
	@WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;isSpectator()Z"))
	private boolean smpeco$phantomSetting(ServerPlayer player, Operation<Boolean> original) {
		return original.call(player) || Modules.isEnabled("settings") && SpawnRules.skipPhantomsFor(player);
	}
}
