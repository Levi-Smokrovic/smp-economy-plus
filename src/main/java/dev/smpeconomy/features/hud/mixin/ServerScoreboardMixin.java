package dev.smpeconomy.features.hud.mixin;

import dev.smpeconomy.core.module.Modules;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import dev.smpeconomy.features.hud.HudFeature;

/**
 * On {@code /scoreboard objectives setdisplay sidebar …} vanilla sends all clients a new sidebar display, which
 * overrides our packet sidebar. Show ours again on the next tick, for players who have it on; players with it off see
 * the vanilla sidebar.
 */
@Mixin(ServerScoreboard.class)
public abstract class ServerScoreboardMixin {
	@Inject(method = "setDisplayObjective", at = @At("TAIL"))
	private void smpeco$hudAfterSetDisplay(DisplaySlot slot, Objective objective, CallbackInfo ci) {
		if (!Modules.isEnabled("hud")) return;
		if (slot == DisplaySlot.SIDEBAR) HudFeature.onVanillaSidebarChanged();
	}
}
