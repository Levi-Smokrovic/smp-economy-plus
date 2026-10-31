package dev.smpeconomy.core.mixin;

import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import dev.smpeconomy.core.AdminLog;

/**
 * {@code Commands#performCommand} runs every parsed command (chat, console, RCON, command blocks,
 * {@code performPrefixedCommand}), so admin commands are logged here before execution ({@link AdminLog}).
 */
@Mixin(Commands.class)
public abstract class CommandsMixin {
	@Inject(method = "performCommand", at = @At("HEAD"))
	private void smpeco$logAdminCommand(ParseResults<CommandSourceStack> parseResults, String command, CallbackInfo ci) {
		AdminLog.onCommand(parseResults, command);
	}
}
