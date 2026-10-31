package dev.smpeconomy.features.missions.mixin;

import dev.smpeconomy.core.module.Modules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.raid.Raider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import dev.smpeconomy.features.missions.MissionHooks;

/**
 * At the head of {@code Raider#die}, since vanilla takes the raider out of the raid afterwards: counts a player's kill
 * toward the Daily Elite contribution. Vanilla makes every player cause a Hero of the Village here, even for a single
 * raider; the Daily Elite wants more ({@code minRaidKills} / {@code minRaidKillShare}).
 */
@Mixin(Raider.class)
public abstract class RaiderMixin {
	@Inject(method = "die", at = @At("HEAD"))
	private void smpeco$missionsRaiderDied(DamageSource source, CallbackInfo ci) {
		if (!Modules.isEnabled("missions")) return;
		MissionHooks.onRaiderDied((Raider) (Object) this, source);
	}
}
