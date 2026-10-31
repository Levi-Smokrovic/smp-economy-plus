package dev.smpeconomy.core.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import dev.smpeconomy.SmpEconomy;

/**
 * Vanilla writes all players' {@code .dat} in {@code PlayerList.saveAll}: on autosave and {@code /save-all} (
 * {@code MinecraftServer.saveEverything}: players first, then chunks; Fabric's BEFORE_/AFTER_SAVE both sit in
 * {@code saveAllChunks}, so afterwards), on stop and on datapack reload. Mod data is committed right before so the
 * {@code .dat} files written next are never newer than the mod files.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
	@Shadow
	@Final
	private MinecraftServer server;

	@Inject(method = "saveAll", at = @At("HEAD"))
	private void smpeco$beforeSaveAll(CallbackInfo ci) {
		try {
			SmpEconomy.beforeVanillaPlayerSave(this.server);
		} catch (Throwable t) {
			SmpEconomy.LOG.error("[SMPEco] Saving before vanilla's player save failed", t);
		}
	}
}
