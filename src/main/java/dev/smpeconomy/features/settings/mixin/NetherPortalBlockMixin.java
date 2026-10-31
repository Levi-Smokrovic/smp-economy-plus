package dev.smpeconomy.features.settings.mixin;

import dev.smpeconomy.core.module.Modules;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.NetherPortalBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import dev.smpeconomy.features.settings.SpawnRules;

/**
 * Zombified piglins from nether portals ({@code randomTick}): at a blocked position the spawn returns null like a
 * failed spawn, which vanilla checks for. Position as in {@code EntityType.spawn} (block center, Y at the block's
 * bottom). A separate call site, compatible with ServerCore's {@code isValidSpawn} mobcap in the same method.
 */
@Mixin(NetherPortalBlock.class)
public abstract class NetherPortalBlockMixin {
	@WrapOperation(method = "randomTick", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/entity/EntityType;spawn(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/EntitySpawnReason;)Lnet/minecraft/world/entity/Entity;"))
	private Entity smpeco$portalSetting(EntityType<?> type, ServerLevel level, BlockPos pos, EntitySpawnReason reason, Operation<Entity> original) {
		if (Modules.isEnabled("settings") && SpawnRules.blocksSpawnAt(level, type, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5)) return null;
		return original.call(type, level, pos, reason);
	}
}
