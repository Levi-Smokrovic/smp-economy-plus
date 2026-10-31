package dev.smpeconomy.features.settings.mixin;

import dev.smpeconomy.core.module.Modules;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import dev.smpeconomy.features.settings.SpawnRules;

/**
 * {@code isValidSpawnPostitionForType} is the position check of natural spawning, called only from
 * {@code spawnCategoryForPosition}. Spawners, trial spawners, eggs, raids and golems don't pass through it; patrols,
 * sieges and portal piglins have their own mixins. HEAD inject rather than redirect, to stay compatible with
 * Architectury, ServerCore and Collective mixins.
 */
@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnerMixin {
	@Inject(method = "isValidSpawnPostitionForType", at = @At("HEAD"), cancellable = true)
	private static void smpeco$mobspawnSetting(ServerLevel level, MobCategory category, StructureManager structures, ChunkGenerator generator,
			MobSpawnSettings.SpawnerData spawnerData, BlockPos.MutableBlockPos pos, double nearestPlayerDistSq, CallbackInfoReturnable<Boolean> cir) {
		if (!Modules.isEnabled("settings")) return;
		if (SpawnRules.blocksNaturalSpawn(level, category, spawnerData.type(), pos)) cir.setReturnValue(false);
	}
}
