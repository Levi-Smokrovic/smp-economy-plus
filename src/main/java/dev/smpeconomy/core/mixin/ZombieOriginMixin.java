package dev.smpeconomy.core.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import dev.smpeconomy.core.MobOrigin;

/**
 * On hard difficulty a hurt zombie (also husk, drowned) calls reinforcements through {@code entityType.create(level,
 * REINFORCEMENT)} in {@code Zombie#hurtServer}. They inherit the caller's origin tags, so spawner zombies do not supply
 * fully paid zombies.
 */
@Mixin(Zombie.class)
public abstract class ZombieOriginMixin {
	@ModifyExpressionValue(
		method = "hurtServer",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/EntityType;create(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/EntitySpawnReason;)Lnet/minecraft/world/entity/Entity;"))
	private Entity smpeco$inheritOrigin(Entity reinforcement) {
		MobOrigin.inherit((Entity) (Object) this, reinforcement);
		return reinforcement;
	}
}
