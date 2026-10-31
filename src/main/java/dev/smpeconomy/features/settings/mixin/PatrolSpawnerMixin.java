package dev.smpeconomy.features.settings.mixin;

import dev.smpeconomy.core.module.Modules;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.levelgen.PatrolSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import dev.smpeconomy.features.settings.SpawnRules;

/**
 * {@code spawnPatrolMember} puts a pillager exactly on {@code pos}. At a blocked position it returns false like an
 * invalid spot, so a leader cancels the whole patrol and a member only itself. HEAD inject, compatible with
 * Architectury's INVOKE_ASSIGN inject in the same method.
 */
@Mixin(PatrolSpawner.class)
public abstract class PatrolSpawnerMixin {
	@Inject(method = "spawnPatrolMember", at = @At("HEAD"), cancellable = true)
	private void smpeco$patrolSetting(ServerLevel level, BlockPos pos, RandomSource random, boolean leader, CallbackInfoReturnable<Boolean> cir) {
		if (!Modules.isEnabled("settings")) return;
		if (SpawnRules.blocksSpawnAt(level, EntityType.PILLAGER, pos.getX(), pos.getY(), pos.getZ())) cir.setReturnValue(false);
	}
}
