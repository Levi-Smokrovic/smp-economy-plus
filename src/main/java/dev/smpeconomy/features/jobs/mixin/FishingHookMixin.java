package dev.smpeconomy.features.jobs.mixin;

import dev.smpeconomy.core.module.Modules;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import dev.smpeconomy.features.jobs.JobsEvents;

/**
 * {@code FishingHook#retrieve} creates the loot through {@code LootTable#getRandomItems(LootParams)}, server side only
 * and only for a real catch, not when reeling in entities. The list is returned unchanged; vanilla spawns the items
 * afterwards.
 */
@Mixin(FishingHook.class)
public abstract class FishingHookMixin {
	@WrapOperation(
		method = "retrieve",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/storage/loot/LootTable;getRandomItems(Lnet/minecraft/world/level/storage/loot/LootParams;)Lit/unimi/dsi/fastutil/objects/ObjectArrayList;"))
	private ObjectArrayList<ItemStack> smpeco$onFishCaught(LootTable table, LootParams params, Operation<ObjectArrayList<ItemStack>> original) {
		ObjectArrayList<ItemStack> loot = original.call(table, params);
		if (!Modules.isEnabled("jobs")) return loot;
		Player player = ((FishingHook) (Object) this).getPlayerOwner();
		if (player != null && loot != null) JobsEvents.onFishCaught(player, loot);
		return loot;
	}
}
