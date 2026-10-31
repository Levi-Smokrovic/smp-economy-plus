package dev.smpeconomy.features.sell.mixin;

import dev.smpeconomy.core.module.Modules;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.features.sell.SellGui;

/**
 * Settles at the start of {@code ServerPlayer#die}, before {@code dropAllDeathLoot} and so before Universal Graves
 * hooks in, so unsellable items land in the inventory and get treated like any other item. sgui only closes the GUI
 * at the end of {@code die}, by which time this is a no-op or the fallback if something went wrong here.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
	@Inject(method = "die", at = @At("HEAD"))
	private void smpeco$sellBeforeDeathDrops(DamageSource source, CallbackInfo ci) {
		if (!Modules.isEnabled("sell")) return;
		try {
			SellGui.onDeath((ServerPlayer) (Object) this);
		} catch (Throwable t) {
			SmpEconomy.LOG.error("[SMPEco] sell: settling on death failed (fallback: sgui close)", t);
		}
	}
}
