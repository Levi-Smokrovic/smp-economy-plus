package dev.smpeconomy.features.settings.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PatrolSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Only for the diagnostic {@code /smpeco settings spawntest … patrol}: a real vanilla patrol spawn, through the
 * mixin.
 */
@Mixin(PatrolSpawner.class)
public interface PatrolSpawnerInvoker {
	@Invoker("spawnPatrolMember")
	boolean smpeco$spawnPatrolMember(ServerLevel level, BlockPos pos, RandomSource random, boolean leader);
}
