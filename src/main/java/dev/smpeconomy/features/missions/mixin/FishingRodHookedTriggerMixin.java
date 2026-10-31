package dev.smpeconomy.features.missions.mixin;

import dev.smpeconomy.core.module.Modules;
import java.util.Collection;
import net.minecraft.advancements.criterion.FishingRodHookedTrigger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import dev.smpeconomy.features.missions.MissionHooks;

/**
 * {@code FishingHook#retrieve} calls this advancement trigger with exactly the loot list about to drop (or an empty
 * list when reeling in a mob). HEAD inject instead of a redirect so other mods can use the same spot independently.
 */
@Mixin(FishingRodHookedTrigger.class)
public abstract class FishingRodHookedTriggerMixin {
	@Inject(method = "trigger(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/projectile/FishingHook;Ljava/util/Collection;)V",
		at = @At("HEAD"))
	private void smpeco$missionsFishingLoot(ServerPlayer player, ItemStack rod, FishingHook hook, Collection<ItemStack> loot, CallbackInfo ci) {
		if (!Modules.isEnabled("missions")) return;
		MissionHooks.onFishingLoot(player, loot);
	}
}
