package dev.smpeconomy.features.menu;

import java.util.UUID;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.Bots;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.JoinGrace;
import dev.smpeconomy.core.Scheduler;

/**
 * New players (playtime below {@link MenuConfig#joinHintPlaytimeHours}) see the hint on every join, everyone else
 * until they have seen it {@link MenuConfig#joinHintTimes} times (counter {@link #COUNTER} in player data).
 */
final class MenuJoinHint {
	/** Counted up to {@code joinHintTimes}. */
	static final String COUNTER = "menu.join_hints";
	static final long TICKS_PER_HOUR = 20L * 60 * 60;

	private MenuJoinHint() {}

	/** Server thread. Delayed; only sent if the player is still in the same session by then. */
	static void onJoin(ServerGamePacketListenerImpl handler, MinecraftServer server) {
		ServerPlayer player = handler.getPlayer();
		if (player == null || Bots.isBot(player)) return;
		MenuConfig cfg = MenuConfig.get();
		if (!cfg.joinHint) return;
		UUID id = player.getUUID();
		Scheduler.runLater(cfg.joinHintDelaySeconds * 20 + JoinGrace.extraTicks(player), () -> {
			if (SmpEconomy.isStopping()) return;
			ServerPlayer online = server.getPlayerList().getPlayer(id);
			// A respawn keeps the connection and a rejoin doesn't, so a quick rejoin gets no second hint.
			if (online == null || online.connection != handler) return;
			send(online);
		});
	}

	/** {@code shown} is the counter so far. */
	static boolean due(MenuConfig cfg, long playtimeTicks, long shown) {
		if (!cfg.joinHint) return false;
		if (playtimeTicks < cfg.joinHintPlaytimeHours * TICKS_PER_HOUR) return true;
		return shown < cfg.joinHintTimes;
	}

	/** Only if due and the player may use /menu or /commands. True if sent. */
	static boolean send(ServerPlayer player) {
		MenuConfig cfg = MenuConfig.get();
		UUID id = player.getUUID();
		long shown = PlayerDataService.getCounter(id, COUNTER);
		if (!due(cfg, PlayerDataService.getPlaytimeTicks(id), shown)) return false;
		MutableComponent msg = message(player);
		if (msg == null) return false;
		Msg.send(player, msg);
		if (shown < cfg.joinHintTimes) PlayerDataService.addCounter(id, COUNTER, 1);
		return true;
	}

	/** Null if the player may use neither command. */
	static MutableComponent message(ServerPlayer player) {
		boolean menu = MenuGui.available(player, MenuGui.MENU_COMMANDS.get(0));
		boolean help = MenuGui.available(player, MenuGui.HELP_COMMAND);
		if (!menu && !help) return null;
		MutableComponent line = Msg.legacy("");
		if (menu) {
			line.append(Msg.legacy("§7All features: "))
				.append(Msg.clickRun("§e[/menu]", "/menu", "§fOpen the Main Menu\n§7Shop, Auction House, Jobs, Missions, Bank …\n§8Click to run"));
		}
		if (menu && help) line.append(Msg.legacy(" §8· "));
		if (help) {
			line.append(Msg.legacy("§7Commands: "))
				.append(Msg.clickRun("§e[/" + MenuGui.HELP_COMMAND + "]", "/" + MenuGui.HELP_COMMAND, "§fShow all commands\n§8Click to run"));
		}
		return Msg.prefixed(line);
	}
}
