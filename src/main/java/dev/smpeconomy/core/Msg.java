package dev.smpeconomy.core;

import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import dev.smpeconomy.core.compat.Mc;
import dev.smpeconomy.SmpEconomy;

/**
 * Text helpers. Texts are strings with section codes, converted to components with real styles by
 * {@link #legacy(String)}.
 */
public final class Msg {
	public static final String DEFAULT_PREFIX = "§6SMP §8» §7";

	/**
	 * {@link ChatFormatting#getByCode} copies {@code values()} on every call, and {@link #legacy} runs for every lore,
	 * sidebar and chat line, so this is a fixed table with the same results.
	 */
	private static final ChatFormatting[] BY_CODE = new ChatFormatting[128];

	static {
		for (ChatFormatting f : ChatFormatting.values()) {
			char c = Mc.formatChar(f);
			if (c < BY_CODE.length && BY_CODE[c] == null) BY_CODE[c] = f; // like getByCode: first match wins
		}
	}

	private Msg() {}

	public static String prefix() {
		try {
			return CoreConfig.get().branding.chatPrefix + "§7";
		} catch (RuntimeException e) {
			return DEFAULT_PREFIX;
		}
	}

	public static String stripCodes(String text) {
		if (text == null) return "";
		return text.replaceAll("§.", "");
	}

	/** Same as {@code ChatFormatting.getByCode(Character.toLowerCase(c))} without the array copy. */
	public static ChatFormatting formatByCode(char c) {
		char d = Character.toLowerCase(Character.toLowerCase(c));
		return d < BY_CODE.length ? BY_CODE[d] : null;
	}

	public static MutableComponent legacy(String text) {
		MutableComponent root = Component.empty();
		if (text == null || text.isEmpty()) return root;
		Style style = Style.EMPTY;
		StringBuilder segment = new StringBuilder();
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '§' && i + 1 < text.length()) {
				ChatFormatting fmt = formatByCode(text.charAt(i + 1));
				if (fmt != null) {
					if (!segment.isEmpty()) {
						root.append(Component.literal(segment.toString()).withStyle(style));
						segment.setLength(0);
					}
					style = style.applyLegacyFormat(fmt);
					i++;
					continue;
				}
			}
			segment.append(c);
		}
		if (!segment.isEmpty()) root.append(Component.literal(segment.toString()).withStyle(style));
		return root;
	}

	public static MutableComponent prefixed(String text) {
		return legacy(prefix() + text);
	}

	public static MutableComponent prefixed(Component content) {
		return legacy(prefix()).append(content);
	}

	public static void info(ServerPlayer player, String text) {
		player.sendSystemMessage(prefixed(text));
	}

	public static void success(ServerPlayer player, String text) {
		player.sendSystemMessage(prefixed("§a" + text));
	}

	public static void error(ServerPlayer player, String text) {
		player.sendSystemMessage(prefixed("§c" + text));
	}

	public static void send(ServerPlayer player, Component component) {
		player.sendSystemMessage(component);
	}

	public static void actionBar(ServerPlayer player, String text) {
		Mc.overlay(player, legacy(text));
	}

	public static void actionBar(ServerPlayer player, Component component) {
		Mc.overlay(player, component);
	}

	public static void info(CommandSourceStack src, String text) {
		src.sendSuccess(() -> prefixed(text), false);
	}

	public static void success(CommandSourceStack src, String text) {
		src.sendSuccess(() -> prefixed("§a" + text), false);
	}

	public static void error(CommandSourceStack src, String text) {
		src.sendFailure(prefixed("§c" + text));
	}

	public static void send(CommandSourceStack src, Component component) {
		src.sendSuccess(() -> component, false);
	}

	public static MutableComponent money(long cents) {
		return legacy("§a" + Money.format(cents));
	}

	public static String moneyStr(long cents) {
		return "§a" + Money.format(cents) + "§7";
	}

	public static String number(long value) {
		// same output as String.format(Locale.ROOT, "%,d", value) without Formatter; HUD and lore call this often
		String digits = Long.toString(value);
		int start = value < 0 ? 1 : 0;
		int len = digits.length() - start;
		if (len <= 3) return digits;
		StringBuilder sb = new StringBuilder(digits.length() + (len - 1) / 3);
		sb.append(digits, 0, start);
		int first = len % 3 == 0 ? 3 : len % 3;
		sb.append(digits, start, start + first);
		for (int i = start + first; i < digits.length(); i += 3) sb.append(',').append(digits, i, i + 3);
		return sb.toString();
	}

	/** Translatable, so the client shows it in its own language. */
	public static MutableComponent itemName(net.minecraft.world.item.Item item) {
		return Mc.itemName(item).copy();
	}

	public static MutableComponent itemName(net.minecraft.world.item.ItemStack stack) {
		return stack.getHoverName().copy();
	}

	public static MutableComponent hover(Component text, String hoverLegacy) {
		return text.copy().withStyle(s -> s.withHoverEvent(new HoverEvent.ShowText(legacy(hoverLegacy))));
	}

	public static MutableComponent clickRun(String textLegacy, String command, String hoverLegacy) {
		return legacy(textLegacy).withStyle(s -> s.withClickEvent(new ClickEvent.RunCommand(CommandRegistry.publicCommand(command)))
			.withHoverEvent(new HoverEvent.ShowText(legacy(hoverLegacy))));
	}

	public static MutableComponent clickSuggest(String textLegacy, String command, String hoverLegacy) {
		return legacy(textLegacy).withStyle(s -> s.withClickEvent(new ClickEvent.SuggestCommand(CommandRegistry.publicCommand(command)))
			.withHoverEvent(new HoverEvent.ShowText(legacy(hoverLegacy))));
	}

	public static void broadcast(MinecraftServer server, Component component) {
		server.getPlayerList().broadcastSystemMessage(component, false);
	}

	public static void broadcast(MinecraftServer server, String text) {
		broadcast(server, prefixed(text));
	}

	/** Text without the prefix, which is added here. */
	public static void sendOrQueue(UUID player, String text) {
		MinecraftServer server = SmpEconomy.server();
		ServerPlayer online = server == null ? null : server.getPlayerList().getPlayer(player);
		if (online != null) {
			info(online, text);
		} else {
			PlayerDataService.addPendingMessage(player, text);
		}
	}
}
