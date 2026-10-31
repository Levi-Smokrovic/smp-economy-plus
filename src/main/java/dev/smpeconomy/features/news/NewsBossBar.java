package dev.smpeconomy.features.news;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import dev.smpeconomy.core.Bots;
import dev.smpeconomy.core.compat.Mc;
import dev.smpeconomy.features.news.NewsData.NewsEntry;

/**
 * Boss bar for all players regardless of "announcements", since prices change for everyone. Red for a crash, otherwise green. The
 * text cycles through running events every {@code tickerSeconds}; progress is time left. Players are synced with the player list
 * every second, so no disconnect event is needed.
 */
final class NewsBossBar {
	private final ServerBossEvent bar = Mc.bossEvent(Component.literal("Breaking News"), BossEvent.BossBarColor.RED,
		BossEvent.BossBarOverlay.PROGRESS);
	private boolean shown;

	static List<NewsEntry> displayable(NewsEngine engine, long now) {
		List<NewsEntry> list = new ArrayList<>();
		for (NewsEntry e : engine.active()) {
			if (!e.finished(now)) list.add(e);
		}
		list.sort(Comparator.comparingLong((NewsEntry e) -> e.startMs).thenComparingLong(e -> e.id));
		return list;
	}

	static int tickerIndex(long now, int tickerSeconds, int size) {
		if (size <= 0) return 0;
		long period = Math.max(1, tickerSeconds) * 1000L;
		return (int) Math.floorMod(now / period, (long) size);
	}

	void update(MinecraftServer server, NewsEngine engine, NewsConfig config, long now) {
		List<NewsEntry> list = displayable(engine, now);
		if (list.isEmpty()) {
			hide();
			return;
		}
		int idx = tickerIndex(now, config.tickerSeconds, list.size());
		NewsEntry e = list.get(idx);
		bar.setColor(NewsText.color(e)); // Color before text: clients never see the new text in the old color
		bar.setName(NewsText.bossLine(engine, e, now, idx, list.size()));
		bar.setProgress(NewsText.progress(e, now));
		shown = true;
		syncPlayers(server);
	}

	void syncPlayers(MinecraftServer server) {
		if (!shown) return;
		Set<ServerPlayer> online = new HashSet<>(server.getPlayerList().getPlayers());
		for (ServerPlayer p : List.copyOf(bar.getPlayers())) {
			if (!online.contains(p) || p.hasDisconnected()) bar.removePlayer(p);
		}
		for (ServerPlayer p : online) {
			if (!Bots.isBot(p) && !bar.getPlayers().contains(p)) bar.addPlayer(p);
		}
	}

	void onJoin(ServerPlayer player) {
		if (shown && !Bots.isBot(player)) bar.addPlayer(player);
	}

	boolean isShown() {
		return shown;
	}

	boolean hasPlayer(ServerPlayer player) {
		return bar.getPlayers().contains(player);
	}

	Component name() {
		return bar.getName();
	}

	BossEvent.BossBarColor color() {
		return bar.getColor();
	}

	float progress() {
		return bar.getProgress();
	}

	void hide() {
		shown = false;
		bar.removeAllPlayers();
	}
}
