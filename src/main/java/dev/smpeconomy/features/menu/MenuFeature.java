package dev.smpeconomy.features.menu;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import dev.smpeconomy.core.CommandRegistry;
import dev.smpeconomy.core.Feature;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Perms;

/**
 * Main menu {@code /menu}: a chest GUI with one icon per feature that runs the feature's command as the player, so
 * there is no dependency on other feature packages. {@code /commands} lists all player commands, clickable, in chat.
 * Only shows what exists on the server and what the player may use.
 */
public final class MenuFeature implements Feature {
	public static final String ID = "menu";
	static final String NODE_MENU = "smpeconomy.menu";
	static final String NODE_HELP = "smpeconomy.help";

	@Override
	public String id() {
		return ID;
	}

	@Override
	public void register() {
		MenuConfig.bootstrap();
		CommandRegistry.register((dispatcher, registryAccess, environment) -> {
			for (String name : MenuGui.MENU_COMMANDS) {
				dispatcher.register(Commands.literal(name).requires(Perms.require(NODE_MENU)).executes(MenuFeature::openMenu));
			}
			dispatcher.register(Commands.literal(MenuGui.HELP_COMMAND).requires(Perms.require(NODE_HELP)).executes(MenuFeature::help));
			for (String alias : MenuGui.HELP_ALIASES) {
				dispatcher.register(Commands.literal(alias).requires(Perms.require(NODE_HELP)).executes(MenuFeature::help));
			}
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> MenuJoinHint.onJoin(handler, server));
		MenuSelfTests.register();
	}

	private static int openMenu(CommandContext<CommandSourceStack> ctx) {
		ServerPlayer player = ctx.getSource().getPlayer();
		if (player == null) {
			Msg.error(ctx.getSource(), "Players only.");
			return 0;
		}
		MenuGui.open(player);
		return 1;
	}

	private static int help(CommandContext<CommandSourceStack> ctx) {
		MenuGui.sendHelp(ctx.getSource());
		return 1;
	}
}
