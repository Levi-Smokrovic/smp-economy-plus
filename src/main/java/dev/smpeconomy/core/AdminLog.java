package dev.smpeconomy.core;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.CommandContextBuilder;
import com.mojang.brigadier.context.ParsedCommandNode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.commands.CommandSourceStack;
import dev.smpeconomy.SmpEconomy;

/**
 * Audit trail for admin commands. Every executed command whose path contains a {@link Perms#requireAdmin} node is
 * logged as {@code [SMPEco-admin] <sender> (<permission>): /<command>}, whether it came from a player, console, RCON or
 * a command block, including behind {@code /execute ... run}. Feature feedback only goes to the sender (
 * {@code sendSuccess(..., false)}), so without this line an owner could not tell who handed out 500 keys.
 *
 * <p>Called by {@code core.mixin.CommandsMixin} at the start of {@code Commands#performCommand}. That is after parsing,
 * so syntax errors and missing permissions produce no line. It logs the attempt; whether the command changed anything
 * shows in the feature's reply.
 */
public final class AdminLog {
	/** Prefix of every line, matched by grep and e2e scripts. */
	public static final String PREFIX = "[SMPEco-admin]";
	private static Consumer<String> listener;

	private AdminLog() {}

	/** Never throws. */
	public static void onCommand(ParseResults<CommandSourceStack> parse, String command) {
		try {
			String line = lineFor(parse, command);
			if (line == null) return;
			SmpEconomy.LOG.info(line);
			Consumer<String> l = listener;
			if (l != null) l.accept(line);
		} catch (RuntimeException e) {
			SmpEconomy.LOG.warn("[SMPEco] Admin command not logged: /{} ({})", command, e.toString());
		}
	}

	/** Null if this is not an admin command, or is incomplete or has a syntax error. */
	public static String lineFor(ParseResults<CommandSourceStack> parse, String command) {
		if (parse == null || command == null) return null;
		if (parse.getReader().canRead() || !parse.getExceptions().isEmpty()) return null; // not going to execute
		CommandContextBuilder<CommandSourceStack> root = parse.getContext();
		if (root.getLastChild().getCommand() == null) return null; // incomplete: "Unknown command"
		List<String> nodes = adminNodes(root);
		if (nodes.isEmpty()) return null;
		CommandSourceStack src = root.getSource();
		String who = src == null ? "?" : src.getTextName();
		String cmd = command.startsWith("/") ? command.substring(1) : command;
		return PREFIX + " " + who + " (" + String.join(", ", nodes) + "): /" + cmd;
	}

	/** Walks forwarded contexts too, e.g. {@code /execute ... run}. */
	static List<String> adminNodes(CommandContextBuilder<CommandSourceStack> root) {
		List<String> out = new ArrayList<>();
		for (CommandContextBuilder<CommandSourceStack> c = root; c != null; c = c.getChild()) {
			for (ParsedCommandNode<CommandSourceStack> n : c.getNodes()) {
				if (n.getNode().getRequirement() instanceof Perms.AdminRequirement r && !out.contains(r.node())) out.add(r.node());
			}
		}
		return out;
	}

	public static void setListener(Consumer<String> l) {
		listener = l;
	}
}
