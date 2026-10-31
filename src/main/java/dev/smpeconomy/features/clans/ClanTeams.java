package dev.smpeconomy.features.clans;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.features.clans.ClanData.Clan;
import dev.smpeconomy.features.clans.ClanData.Member;

/**
 * Shows clan tags in the tab list and above heads through one vanilla scoreboard team per clan ({@code
 * tri_clan_<id>}, prefix {@code [TAG] }, friendly fire off). Vanilla saves teams in scoreboard.dat; we sync
 * them after each change, at server start ({@link #syncAll}) and on join. Foreign teams (without the {@code
 * tri_clan_} prefix) are never touched, and by default players in them are not moved.
 */
public final class ClanTeams {
	public static final String PREFIX = "tri_clan_";

	private ClanTeams() {}

	public static String teamName(Clan c) {
		return PREFIX + c.id;
	}

	public static Component prefix(Clan c) {
		return Msg.legacy(c.coloredTag() + " ");
	}

	private static Scoreboard scoreboard() {
		MinecraftServer s = SmpEconomy.server();
		return s == null ? null : s.getScoreboard();
	}

	public static void sync(Clan c) {
		Scoreboard sb = scoreboard();
		if (sb != null) sync(sb, c);
	}

	public static void sync(Scoreboard sb, Clan c) {
		String name = teamName(c);
		PlayerTeam team = sb.getPlayerTeam(name);
		if (team == null) team = sb.addPlayerTeam(name);
		Component display = Component.literal(c.name);
		if (!display.equals(team.getDisplayName())) team.setDisplayName(display);
		Component prefix = prefix(c);
		if (!prefix.equals(team.getPlayerPrefix())) team.setPlayerPrefix(prefix);
		if (team.isAllowFriendlyFire()) team.setAllowFriendlyFire(false);

		Set<String> wanted = new HashSet<>();
		for (Member m : c.members.values()) {
			if (m.name != null && !m.name.isEmpty()) wanted.add(m.name);
		}
		for (String player : List.copyOf(team.getPlayers())) {
			if (!wanted.contains(player)) sb.removePlayerFromTeam(player, team);
		}
		boolean respectForeign = ClanService.config().respectForeignTeams;
		for (String player : wanted) {
			PlayerTeam current = sb.getPlayersTeam(player);
			if (current == team) continue;
			if (current != null && !current.getName().startsWith(PREFIX) && respectForeign) continue;
			sb.addPlayerToTeam(player, team);
		}
	}

	public static void remove(Clan c) {
		Scoreboard sb = scoreboard();
		if (sb == null) return;
		PlayerTeam team = sb.getPlayerTeam(teamName(c));
		if (team != null) sb.removePlayerTeam(team);
	}

	/** Also removes orphaned {@code tri_clan_} teams whose clan no longer exists. */
	public static void syncAll(MinecraftServer server) {
		Scoreboard sb = server.getScoreboard();
		for (PlayerTeam team : List.copyOf(sb.getPlayerTeams())) {
			if (!team.getName().startsWith(PREFIX)) continue;
			Optional<Clan> c = clanOfTeam(team.getName());
			if (c.isEmpty()) {
				SmpEconomy.LOG.info("[SMPEco] clans: orphaned team {} removed", team.getName());
				sb.removePlayerTeam(team);
			}
		}
		for (Clan c : ClanService.all()) sync(sb, c);
	}

	/** E.g. kicked while offline. */
	public static void cleanupPlayer(String playerName) {
		Scoreboard sb = scoreboard();
		if (sb == null || playerName == null) return;
		PlayerTeam team = sb.getPlayersTeam(playerName);
		if (team == null || !team.getName().startsWith(PREFIX)) return;
		Optional<Clan> c = clanOfTeam(team.getName());
		boolean member = c.isPresent() && c.get().members.values().stream().anyMatch(m -> playerName.equals(m.name));
		if (!member) sb.removePlayerFromTeam(playerName, team);
	}

	static Optional<Clan> clanOfTeam(String teamName) {
		if (!teamName.startsWith(PREFIX)) return Optional.empty();
		try {
			return ClanService.byId(Integer.parseInt(teamName.substring(PREFIX.length())));
		} catch (NumberFormatException e) {
			return Optional.empty();
		}
	}
}
