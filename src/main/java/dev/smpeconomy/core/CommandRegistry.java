package dev.smpeconomy.core;

import com.google.gson.annotations.SerializedName;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import dev.smpeconomy.SmpEconomy;

/**
 * All commands of this mod are built in a private dispatcher and then published to the real one, after every mod that
 * registers in the default event phase.
 *
 * <p>Registering a name twice in Brigadier does not replace anything. {@code CommandNode.addChild} merges the nodes: the
 * first registration keeps its requirement, the last one's executor wins, and the children of both end up side by side.
 * Two mods with a {@code /pay} would both be broken, one possibly running with the other's permission check. So a name
 * another mod already holds is left alone and ours is skipped with a warning. commands.json can rename it instead.
 *
 * <p>The private tree stays complete, so GUI buttons keep working when a root is disabled, renamed or taken.
 */
public final class CommandRegistry {
	public static final class Config {
		@SerializedName("_info")
		public String info = "disabled: root commands of this mod to leave out, for example [\"pay\"]. renamed: new names, for example "
			+ "{\"shop\": \"market\"}. An alias such as bal or money is its own root. GUIs and buttons keep working. Applied on restart or /reload.";
		public List<String> disabled = new ArrayList<>();
		public Map<String, String> renamed = new HashMap<>();
	}

	public static final String FILE = "config/smpeconomy/commands.json";
	private static final Pattern VALID_NAME = Pattern.compile("[a-z0-9_-]+");
	/** Without this command the file could not be reloaded or checked in-game. */
	private static final String ADMIN_ROOT = "smpeco";

	private static final ConfigManager.Config<Config> CONFIG = ConfigManager.register("commands", Config.class, Config::new, CommandRegistry::sanitize);
	private static final Identifier LATE = Identifier.fromNamespaceAndPath(SmpEconomy.MOD_ID, "after_other_mods");
	private static final List<CommandRegistrationCallback> CALLBACKS = new ArrayList<>();
	private static final Set<String> WARNED = new HashSet<>();

	private static volatile CommandDispatcher<CommandSourceStack> own = new CommandDispatcher<>();
	private static volatile Map<String, String> renamedRoots = Map.of();

	static {
		CommandRegistrationCallback.EVENT.addPhaseOrdering(Event.DEFAULT_PHASE, LATE);
		CommandRegistrationCallback.EVENT.register(LATE, CommandRegistry::publish);
	}

	private CommandRegistry() {}

	public static void register(CommandRegistrationCallback callback) {
		CALLBACKS.add(callback);
	}

	/** Root of the private tree: what exists is decided here, not by what other mods registered under the same name. */
	public static RootCommandNode<CommandSourceStack> root() {
		return own.getRoot();
	}

	/** Runs one of our commands as the player, whatever its public name is. */
	public static void run(ServerPlayer player, String command) {
		MinecraftServer server = player.level().getServer();
		if (server != null) server.getCommands().performCommand(own.parse(command, player.createCommandSourceStack()), command);
	}

	/** Runs a command as if the player typed it, for configured commands that may belong to any mod. */
	public static void runAsTyped(ServerPlayer player, String command) {
		MinecraftServer server = player.level().getServer();
		if (server != null) server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), publicCommand(command));
	}

	/** {@code /shop search x} with its root replaced by the configured name, for click events and config commands. */
	public static String publicCommand(String command) {
		boolean slash = command.startsWith("/");
		String body = slash ? command.substring(1) : command;
		int space = body.indexOf(' ');
		String root = space < 0 ? body : body.substring(0, space);
		String renamed = renamedRoots.get(root);
		if (renamed == null) return command;
		return (slash ? "/" : "") + renamed + (space < 0 ? "" : body.substring(space));
	}

	/**
	 * Copies the roots of {@code source} into {@code target}. Nodes are moved, not cloned, unless they get a new name, so
	 * aliases that redirect to them stay valid. Returns the roots that were published under another name.
	 */
	public static Map<String, String> publish(RootCommandNode<CommandSourceStack> source, RootCommandNode<CommandSourceStack> target,
			Config config, BiConsumer<String, CommandNode<CommandSourceStack>> onTaken) {
		Map<CommandNode<CommandSourceStack>, CommandNode<CommandSourceStack>> copies = new HashMap<>();
		Map<String, String> renamed = new HashMap<>();
		for (CommandNode<CommandSourceStack> node : source.getChildren()) {
			String name = config.renamed.get(node.getName());
			if (name != null && !config.disabled.contains(node.getName())) copies.put(node, copy(node, name, node.getRedirect()));
		}
		for (CommandNode<CommandSourceStack> node : source.getChildren()) {
			if (config.disabled.contains(node.getName())) continue;
			CommandNode<CommandSourceStack> out = copies.getOrDefault(node, node);
			// the client command tree only keeps a redirect whose target it also received
			CommandNode<CommandSourceStack> redirect = copies.get(out.getRedirect());
			if (redirect != null) out = copy(out, out.getName(), redirect);
			CommandNode<CommandSourceStack> existing = target.getChild(out.getName());
			if (existing != null) {
				onTaken.accept(out.getName(), existing);
				continue;
			}
			target.addChild(out);
			if (copies.containsKey(node)) renamed.put(node.getName(), out.getName());
		}
		return renamed;
	}

	private static void publish(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext registryAccess, Commands.CommandSelection environment) {
		CommandDispatcher<CommandSourceStack> built = new CommandDispatcher<>();
		for (CommandRegistrationCallback callback : CALLBACKS) callback.register(built, registryAccess, environment);
		own = built;
		CONFIG.reload();
		Config config = CONFIG.get();
		for (String name : config.disabled) warnIfMissing(built, name);
		for (String name : config.renamed.keySet()) warnIfMissing(built, name);
		renamedRoots = Map.copyOf(publish(built.getRoot(), dispatcher.getRoot(), config, CommandRegistry::warnTaken));
	}

	private static CommandNode<CommandSourceStack> copy(CommandNode<CommandSourceStack> node, String name, CommandNode<CommandSourceStack> redirect) {
		LiteralCommandNode<CommandSourceStack> copy = new LiteralCommandNode<>(name, node.getCommand(), node.getRequirement(), redirect,
			node.getRedirectModifier(), node.isFork());
		for (CommandNode<CommandSourceStack> child : node.getChildren()) copy.addChild(child);
		return copy;
	}

	private static void warnIfMissing(CommandDispatcher<CommandSourceStack> built, String name) {
		if (built.getRoot().getChild(name) == null && WARNED.add("missing:" + name)) {
			SmpEconomy.LOG.warn("[SMPEco] commands.json: /{} is not a command of this mod (or its module is off)", name);
		}
	}

	private static void warnTaken(String name, CommandNode<CommandSourceStack> existing) {
		if (WARNED.add("taken:" + name)) {
			SmpEconomy.LOG.warn("[SMPEco] /{} is already registered by {}. It keeps the name and SMP Economy+ does not add its own /{}. "
				+ "To use ours, give it another name under \"renamed\" in {}.", name, ownerOf(existing), name, FILE);
		}
	}

	private static void sanitize(Config config) {
		if (config.disabled == null) config.disabled = new ArrayList<>();
		if (config.renamed == null) config.renamed = new HashMap<>();
		if (config.disabled.removeIf(ADMIN_ROOT::equals)) {
			SmpEconomy.LOG.warn("[SMPEco] commands.json: /{} cannot be disabled, only renamed", ADMIN_ROOT);
		}
		config.disabled.removeIf(name -> name == null);
		config.renamed.entrySet().removeIf(e -> {
			if (e.getValue() != null && VALID_NAME.matcher(e.getValue()).matches()) return false;
			SmpEconomy.LOG.warn("[SMPEco] commands.json: '{}' is not a valid name for /{}, ignored", e.getValue(), e.getKey());
			return true;
		});
	}

	/** Best effort: the mod whose jar defined the command's executor. Brigadier itself does not record an owner. */
	private static String ownerOf(CommandNode<CommandSourceStack> node) {
		Command<CommandSourceStack> command = firstExecutor(node);
		CodeSource source = command == null ? null : command.getClass().getProtectionDomain().getCodeSource();
		if (source == null) return "another mod";
		try {
			Path path = Path.of(source.getLocation().toURI());
			for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
				if (mod.getOrigin().getKind() == ModOrigin.Kind.PATH && mod.getOrigin().getPaths().contains(path)) {
					return mod.getMetadata().getName();
				}
			}
		} catch (URISyntaxException | RuntimeException e) {
			SmpEconomy.LOG.debug("[SMPEco] owner of /{} unknown", node.getName(), e);
		}
		return "another mod";
	}

	private static Command<CommandSourceStack> firstExecutor(CommandNode<CommandSourceStack> node) {
		if (node.getCommand() != null) return node.getCommand();
		for (CommandNode<CommandSourceStack> child : node.getChildren()) {
			Command<CommandSourceStack> command = firstExecutor(child);
			if (command != null) return command;
		}
		return null;
	}
}
