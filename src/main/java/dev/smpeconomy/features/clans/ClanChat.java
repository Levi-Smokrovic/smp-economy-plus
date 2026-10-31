package dev.smpeconomy.features.clans;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.features.clans.ClanData.Clan;
import dev.smpeconomy.features.clans.ClanData.Member;

/**
 * {@code /cc <message>} and the {@code /clan chat} toggle (normal chat then goes only to the clan). The
 * toggle applies only to real chat ({@link ChatType#CHAT}), not /me, /say or /msg. Runs on the server thread,
 * because vanilla handles chat through the FutureChain with the server as executor.
 */
public final class ClanChat {
	public static final int MAX_LENGTH = 256;

	private ClanChat() {}

	static void register() {
		ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(ClanChat::onChat);
	}

	private static boolean onChat(PlayerChatMessage message, ServerPlayer sender, ChatType.Bound params) {
		if (sender == null || !ClanService.chatToggled(sender.getUUID())) return true;
		if (!params.chatType().is(ChatType.CHAT)) return true;
		Clan c = ClanService.clanOf(sender.getUUID()).orElse(null);
		if (c == null) {
			ClanService.toggleChat(sender.getUUID()); // Mode on without a clan (e.g. kicked): turn off and continue normally
			return true;
		}
		send(sender, c, message.signedContent());
		return false;
	}

	/** Strips § codes with their letter and control characters, and truncates. */
	public static String clean(String text) {
		if (text == null) return "";
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < text.length() && sb.length() < MAX_LENGTH; i++) {
			char ch = text.charAt(i);
			if (ch == '§') {
				i++;
				continue;
			}
			if (ch < ' ' || ch == 127) continue;
			sb.append(ch);
		}
		return sb.toString().trim();
	}

	/** {@code [Clan] ★ Name: Text}. The text stays literal, so players cannot format it. */
	public static MutableComponent format(Clan c, Member sender, String text) {
		return Msg.legacy("§8[" + c.colorEnum().code + "Clan§8] " + sender.role.symbol + "§f" + sender.name + "§8: ")
			.append(Component.literal(clean(text)).withStyle(ChatFormatting.WHITE));
	}

	public static List<ServerPlayer> onlineMembers(MinecraftServer server, Clan c) {
		List<ServerPlayer> list = new ArrayList<>();
		if (server == null) return list;
		for (UUID id : c.members.keySet()) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p != null) list.add(p);
		}
		return list;
	}

	/**
	 * Sends to all online members including the sender, and to the log.
	 *
	 * @return recipient count, -1 if the text is empty or the sender is not a member
	 */
	public static int send(ServerPlayer sender, Clan c, String text) {
		String cleaned = clean(text);
		Member m = c.members.get(sender.getUUID());
		if (cleaned.isEmpty() || m == null) return -1;
		MutableComponent line = format(c, m, cleaned);
		List<ServerPlayer> online = onlineMembers(sender.level().getServer(), c);
		for (ServerPlayer p : online) p.sendSystemMessage(line);
		SmpEconomy.LOG.info("[ClanChat] [{}] {}: {}", c.tag, m.name, cleaned);
		return online.size();
	}
}
