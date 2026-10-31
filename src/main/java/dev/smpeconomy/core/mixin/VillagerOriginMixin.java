package dev.smpeconomy.core.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import dev.smpeconomy.core.MobOrigin;

/**
 * Villager offspring comes from the constructor ({@code new Villager(...)} + {@code finalizeSpawn(..., BREEDING,
 * ...)}), not {@code EntityType#create}, so it is tagged here. A bred villager turning into a zombie villager, or a
 * witch by lightning, passes the tag on; otherwise "breed villagers, let zombies convert them, kill" would pay in full.
 */
@Mixin(Villager.class)
public abstract class VillagerOriginMixin {
	@Inject(
		method = "getBreedOffspring(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/AgeableMob;)Lnet/minecraft/world/entity/npc/villager/Villager;",
		at = @At("RETURN"))
	private void smpeco$tagBredVillager(ServerLevel level, AgeableMob partner, CallbackInfoReturnable<Villager> cir) {
		MobOrigin.tag(cir.getReturnValue(), EntitySpawnReason.BREEDING);
	}
}
