package dev.smpeconomy.core;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;

/**
 * On a player's very first join the welcome lines and starter kit come first. Other join notices wait
 * {@link #FIRST_JOIN_EXTRA_TICKS} longer so they do not push the welcome out of chat. Returning players get no delay.
 */
public final class JoinGrace {
	/** The welcome is done by then and stays readable for a few more seconds. */
	public static final int FIRST_JOIN_EXTRA_TICKS = 160;

	private JoinGrace() {}

	public static boolean firstJoin(ServerPlayer p) {
		if (p == null || Bots.isBot(p)) return false;
		return p.getStats().getValue(Stats.CUSTOM.get(Stats.LEAVE_GAME)) == 0;
	}

	public static int extraTicks(ServerPlayer p) {
		return firstJoin(p) ? FIRST_JOIN_EXTRA_TICKS : 0;
	}
}
