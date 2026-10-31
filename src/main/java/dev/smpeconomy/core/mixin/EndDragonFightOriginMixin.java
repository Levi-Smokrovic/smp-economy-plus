package dev.smpeconomy.core.mixin;

import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.dimension.end.EndDragonFight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import dev.smpeconomy.core.MobOrigin;

/**
 * The dragon is created in {@code EndDragonFight#createNewDragon} through {@code create(level, EVENT)} and would stay
 * untagged. If this world already had a defeated dragon, the new one comes from a respawn with four end crystals, whose
 * ingredients the shop sells, so it is tagged {@link MobOrigin#RESPAWNED}. The world's first dragon stays untagged and
 * pays in full.
 *
 * <p>Reads the public {@code hasPreviouslyKilledDragon()} rather than the field: the field is {@code previouslyKilled}
 * on 1.21.11 and {@code hasPreviouslyKilledDragon} from 26.1, where the class is renamed but the method kept.
 */
@Mixin(EndDragonFight.class)
public abstract class EndDragonFightOriginMixin {
	@Inject(method = "createNewDragon()Lnet/minecraft/world/entity/boss/enderdragon/EnderDragon;", at = @At("RETURN"))
	private void smpeco$tagRespawnedDragon(CallbackInfoReturnable<EnderDragon> cir) {
		MobOrigin.onDragonCreated(cir.getReturnValue(), ((EndDragonFight) (Object) this).hasPreviouslyKilledDragon());
	}
}
