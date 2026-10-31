package dev.smpeconomy.core.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import dev.smpeconomy.SmpEconomy;

/**
 * Disconnect handling on the server thread. {@code removePlayerFromWorld} runs from {@code onDisconnect}
 * (Connection.handleDisconnection, always server thread) or {@code switchToConfig}, before {@code player.disconnect()}
 * and before {@code PlayerList.remove()}, which saves the player. Fabric's
 * {@code ServerPlayConnectionEvents.DISCONNECT} usually fires earlier on the Netty thread and is unfit for game logic.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
	@Shadow
	public ServerPlayer player;

	@Inject(method = "removePlayerFromWorld", at = @At("HEAD"))
	private void smpeco$beforeRemovePlayer(CallbackInfo ci) {
		try {
			SmpEconomy.onPlayerLeaving(this.player);
		} catch (Throwable t) {
			SmpEconomy.LOG.error("[SMPEco] Disconnect handling failed", t);
		}
	}
}
