package dev.smpeconomy.features.settings.mixin;

import dev.smpeconomy.core.module.Modules;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.village.VillageSiege;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import dev.smpeconomy.features.settings.SpawnRules;

/**
 * {@code trySpawn} puts the zombie exactly on the position from {@code findRandomSpawnPos}. A blocked position counts
 * as "no spot found" (null), so vanilla spawns nothing and the siege continues or ends normally.
 */
@Mixin(VillageSiege.class)
public abstract class VillageSiegeMixin {
	@WrapOperation(method = "trySpawn", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/entity/ai/village/VillageSiege;findRandomSpawnPos(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/Vec3;"))
	private Vec3 smpeco$siegeSetting(VillageSiege siege, ServerLevel level, BlockPos center, Operation<Vec3> original) {
		Vec3 pos = original.call(siege, level, center);
		return pos != null && Modules.isEnabled("settings") && SpawnRules.blocksSpawnAt(level, EntityType.ZOMBIE, pos.x, pos.y, pos.z) ? null : pos;
	}
}
