package dev.smpeconomy.features.minigames.mega;

import com.google.common.net.InetAddresses;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.BossEvent;
import dev.smpeconomy.core.Bots;
import dev.smpeconomy.core.compat.Mc;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.Settings;
import dev.smpeconomy.core.TimeUtil;
import dev.smpeconomy.features.minigames.chat.ChatGames;

/**
 * Live binding of the MEGA-CHALLENGE: output only to players with chat games on, environment and race measuring.
 */
public final class MegaLive implements MegaChallenge.Output, MegaChallenge.Env {
	private final ServerBossEvent bar = Mc.bossEvent(Component.literal("MEGA-CHALLENGE"), BossEvent.BossBarColor.YELLOW,
		BossEvent.BossBarOverlay.NOTCHED_10);
	private boolean barShown;
	private final ChatGames chat;
	private final Supplier<MegaConfig> config;
	private final Supplier<MegaContent> content;
	private final MegaRaceProbe probe = new MegaRaceProbe(MegaLive::player);

	public MegaLive(ChatGames chat, Supplier<MegaConfig> config, Supplier<MegaContent> content) {
		this.chat = chat;
		this.config = config;
		this.content = content;
	}

	public MegaRaceProbe probe() {
		return probe;
	}

	private static ServerPlayer player(UUID id) {
		MinecraftServer server = SmpEconomy.server();
		return server == null ? null : server.getPlayerList().getPlayer(id);
	}

	private static List<ServerPlayer> audience() {
		MinecraftServer server = SmpEconomy.server();
		if (server == null) return List.of();
		return server.getPlayerList().getPlayers().stream()
			.filter(p -> !Bots.isBot(p) && !p.hasDisconnected() && Settings.get(p, Settings.CHATGAMES))
			.toList();
	}

	@Override
	public Set<UUID> eligible() {
		Set<UUID> out = new LinkedHashSet<>();
		for (ServerPlayer p : audience()) out.add(p.getUUID());
		return out;
	}

	/**
	 * Minimum playtime and one account per public IP (most playtime takes part). Unknown IPs (tunnel, LAN,
	 * loopback, see {@link #publicIp}) never count as equal.
	 */
	@Override
	public Map<UUID, String> exclusions(Set<UUID> eligible) {
		MegaConfig c = config.get();
		return exclusions(eligible, id -> {
			ServerPlayer p = player(id);
			return p == null ? null : ipOf(p);
		}, PlayerDataService::getPlaytimeTicks, c.minPlaytimeMinutes, c.onePerIp);
	}

	static Map<UUID, String> exclusions(Set<UUID> eligible, Function<UUID, String> rawIp, ToLongFunction<UUID> playtimeTicks, int minMinutes,
		boolean onePerIp) {
		Map<UUID, String> out = new LinkedHashMap<>();
		long minTicks = minMinutes * 60L * 20L;
		for (UUID id : eligible) {
			long t = playtimeTicks.applyAsLong(id);
			if (minTicks > 0 && t < minTicks) {
				out.put(id, "only from " + minMinutes + " min playtime (you have " + TimeUtil.formatTicks(Math.max(0, t)) + ")");
			}
		}
		if (onePerIp) {
			Map<String, List<UUID>> byIp = new LinkedHashMap<>();
			for (UUID id : eligible) {
				if (out.containsKey(id)) continue;
				String ip = publicIp(rawIp.apply(id));
				if (ip != null) byIp.computeIfAbsent(ip, k -> new ArrayList<>()).add(id);
			}
			for (List<UUID> same : byIp.values()) {
				if (same.size() < 2) continue;
				UUID keep = same.get(0);
				for (UUID id : same) {
					if (playtimeTicks.applyAsLong(id) > playtimeTicks.applyAsLong(keep)) keep = id;
				}
				for (UUID id : same) {
					if (!id.equals(keep)) out.put(id, "only one account per IP address – another account on your IP is playing");
				}
			}
		}
		return out;
	}

	private static String ipOf(ServerPlayer player) {
		try {
			return player.connection == null ? null : player.getIpAddress();
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * Only public routable addresses count; anything else returns null (unknown, never equal): loopback, 0.0.0.0/8,
	 * private networks, link-local, CGNAT 100.64/10, IPv6 ULA, multicast, non-IPs. Behind the playit agent the
	 * server sees the same private address for all players, so comparing it would exclude everyone but one.
	 */
	static String publicIp(String raw) {
		if (raw == null) return null;
		String s = raw.trim();
		if (s.isEmpty() || !InetAddresses.isInetAddress(s)) return null;
		InetAddress a = InetAddresses.forString(s);
		if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isMulticastAddress()) return null;
		byte[] b = a.getAddress();
		if (b.length == 4) {
			int b0 = b[0] & 0xff;
			int b1 = b[1] & 0xff;
			if (b0 == 0 || b0 == 255) return null;
			if (b0 == 100 && (b1 & 0xc0) == 64) return null;
		} else if ((b[0] & 0xfe) == 0xfc) {
			return null;
		}
		return InetAddresses.toAddrString(a);
	}

	@Override
	public boolean chatGamesOn(UUID player) {
		return Settings.get(player, Settings.CHATGAMES);
	}

	@Override
	public boolean chatGameRunning() {
		return chat.active().isPresent();
	}

	@Override
	public void stopChatGame(String message) {
		chat.stop(message);
	}

	@Override
	public void postponeChatGames(long until) {
		chat.postpone(until);
	}

	@Override
	public MegaChallenge.Prepared prepare(MegaChallenge.Type type, String variant, Random random) {
		MinecraftServer server = SmpEconomy.server();
		return MegaGenerator.prepare(type, variant, config.get(), content.get(), () -> {
			if (server == null) throw new IllegalStateException("Server not running");
			return MegaTrivia.build(server);
		}, MegaNames::englishNames, MegaNames::vocabulary, this::nightMillisLeft, random);
	}

	@Override
	public MegaChallenge.RaceProbe raceProbe() {
		return probe;
	}

	@Override
	public long nightMillisLeft() {
		MinecraftServer server = SmpEconomy.server();
		if (server == null) return 0;
		return MegaRaceProbe.nightTicksLeft(Mc.dayTime(server.overworld())) * 50L;
	}

	@Override
	public void broadcast(Component message) {
		for (ServerPlayer p : audience()) p.sendSystemMessage(message);
	}

	@Override
	public void send(UUID player, Component message) {
		ServerPlayer p = player(player);
		if (p != null) p.sendSystemMessage(message);
	}

	@Override
	public void title(Component title, Component subtitle, MegaChallenge.Sound sound) {
		for (ServerPlayer p : audience()) {
			p.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20));
			p.connection.send(new ClientboundSetTitleTextPacket(title));
			p.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
			switch (sound) {
				case ANNOUNCE -> {
					Gui.sound(p, SoundEvents.RAID_HORN, 1.0f);
					Gui.sound(p, SoundEvents.NOTE_BLOCK_BELL, 0.8f);
				}
				case START -> Gui.sound(p, SoundEvents.NOTE_BLOCK_BELL, 1.4f);
				case WIN -> Gui.sound(p, holder(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE), 1.0f);
				case END -> Gui.sound(p, SoundEvents.NOTE_BLOCK_BASS, 0.6f);
			}
		}
	}

	private static Holder<SoundEvent> holder(SoundEvent e) {
		return BuiltInRegistries.SOUND_EVENT.wrapAsHolder(e);
	}

	@Override
	public void bar(Component name, float progress, MegaChallenge.Phase phase) {
		BossEvent.BossBarColor color = switch (phase) {
			case ANNOUNCE -> BossEvent.BossBarColor.YELLOW;
			case RACE -> BossEvent.BossBarColor.RED;
			default -> BossEvent.BossBarColor.PURPLE;
		};
		bar.setColor(color);
		bar.setName(name);
		bar.setProgress(progress);
		barShown = true;
		syncBar();
	}

	@Override
	public void hideBar() {
		barShown = false;
		bar.removeAllPlayers();
	}

	@Override
	public void actionBar(UUID player, Component message) {
		ServerPlayer p = player(player);
		if (p != null) Msg.actionBar(p, message);
	}

	public void syncBar() {
		if (!barShown) return;
		Set<ServerPlayer> want = new HashSet<>(audience());
		for (ServerPlayer p : List.copyOf(bar.getPlayers())) {
			if (!want.contains(p)) bar.removePlayer(p);
		}
		for (ServerPlayer p : want) {
			if (!bar.getPlayers().contains(p)) bar.addPlayer(p);
		}
	}

	public String barTitle() {
		return barShown ? bar.getName().getString() : null;
	}
}
