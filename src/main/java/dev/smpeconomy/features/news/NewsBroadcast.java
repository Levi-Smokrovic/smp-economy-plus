package dev.smpeconomy.features.news;

import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Settings;
import dev.smpeconomy.features.news.NewsData.NewsEntry;

/**
 * Live announcer: title, subtitle and bell (only for players with the "announcements" setting), chat line with hover for everyone
 * since prices change for everyone, console log. The boss bar runs independently.
 */
final class NewsBroadcast implements NewsEngine.Announcer {
	private final java.util.function.Supplier<NewsEngine> engine;
	private final java.util.function.Supplier<NewsConfig> config;

	NewsBroadcast(java.util.function.Supplier<NewsEngine> engine, java.util.function.Supplier<NewsConfig> config) {
		this.engine = engine;
		this.config = config;
	}

	static boolean wantsAnnouncements(ServerPlayer player) {
		return Settings.get(player, Settings.ANNOUNCEMENTS);
	}

	@Override
	public void started(List<NewsEntry> group) {
		MinecraftServer server = SmpEconomy.server();
		if (server == null || group.isEmpty()) return;
		NewsConfig c = config.get();
		Component subtitle = NewsText.subtitle(group);
		Component title = Msg.legacy(NewsText.TITLE);
		boolean crash = group.stream().allMatch(e -> e.kind() == NewsEntry.Type.CRASH);
		for (ServerPlayer p : List.copyOf(server.getPlayerList().getPlayers())) {
			if (!wantsAnnouncements(p)) continue;
			if (c.titleOnStart) {
				p.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20));
				p.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
				p.connection.send(new ClientboundSetTitleTextPacket(title));
			}
			if (c.soundOnStart) {
				Gui.sound(p, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.BELL_BLOCK), crash ? 0.8f : 1.2f);
				Gui.sound(p, SoundEvents.NOTE_BLOCK_BELL, crash ? 0.7f : 1.4f);
			}
		}
		Msg.broadcast(server, NewsText.chatLine(engine.get(), group));
		for (NewsEntry e : group) {
			SmpEconomy.LOG.info("[SMPEco] Breaking News: {} {} factor={} pct={} until {}{}", e.type, e.item.isEmpty() ? e.category : e.item,
				e.factor, e.shownPercent, NewsText.when(e.endMs), e.manual ? " (manual)" : "");
		}
	}

	@Override
	public void ended(NewsEntry e) {
		MinecraftServer server = SmpEconomy.server();
		if (server == null) return;
		Component line = NewsText.endLine(e);
		// "announcements" only covers title and sound; the end affects everyone's prices
		for (ServerPlayer p : List.copyOf(server.getPlayerList().getPlayers())) p.sendSystemMessage(line);
		SmpEconomy.LOG.info("[SMPEco] Breaking News ended: {} {}", e.type, e.item.isEmpty() ? e.category : e.item);
	}
}
