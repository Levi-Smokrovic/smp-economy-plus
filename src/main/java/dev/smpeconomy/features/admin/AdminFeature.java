package dev.smpeconomy.features.admin;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import dev.smpeconomy.core.CommandRegistry;
import dev.smpeconomy.core.Feature;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Perms;
import dev.smpeconomy.core.module.ModuleCatalog;
import dev.smpeconomy.core.module.ModuleCommands;
import dev.smpeconomy.core.module.ModuleInfo;
import dev.smpeconomy.core.module.Modules;

/**
 * Admin tools that are always on, all under {@link Perms#ADMIN_SETTINGS}: {@code /smpeco admin},
 * {@code /smpeco module} (modules.json, applies after a restart) and {@code /smpeco config} (whitelist from
 * {@link AdminSettings}, live). The first line of {@code module list} is machine-readable:
 * {@code modules running=<ids,…> configured=<ids,…> pending=<n>}.
 */
public final class AdminFeature implements Feature {
	public static final String ID = "admin";

	@Override
	public String id() {
		return ID;
	}

	@Override
	public void register() {
		CommandRegistry.register((dispatcher, registryAccess, environment) -> dispatcher.register(
			Commands.literal("smpeco")
				.requires(Perms.requireAdmin(Perms.ADMIN))
				.then(Commands.literal("admin")
					.requires(Perms.requireAdmin(Perms.ADMIN_SETTINGS))
					.executes(ctx -> {
						AdminGui.open(ctx.getSource().getPlayerOrException());
						return 1;
					}))
				.then(Commands.literal("module")
					.requires(Perms.requireAdmin(Perms.ADMIN_SETTINGS))
					.then(Commands.literal("list").executes(AdminFeature::moduleList))
					.then(Commands.literal("info")
						.then(Commands.argument("module", StringArgumentType.word()).suggests(AdminFeature::suggestModules)
							.executes(AdminFeature::moduleInfo)))
					.then(Commands.literal("enable")
						.then(Commands.argument("module", StringArgumentType.word()).suggests(AdminFeature::suggestModules)
							.executes(ctx -> moduleSet(ctx, true, false))
							.then(Commands.literal("with-dependencies").executes(ctx -> moduleSet(ctx, true, true)))))
					.then(Commands.literal("disable")
						.then(Commands.argument("module", StringArgumentType.word()).suggests(AdminFeature::suggestModules)
							.executes(ctx -> moduleSet(ctx, false, false))
							.then(Commands.literal("with-dependents").executes(ctx -> moduleSet(ctx, false, true))))))
				.then(Commands.literal("config")
					.requires(Perms.requireAdmin(Perms.ADMIN_SETTINGS))
					.then(Commands.literal("list").executes(AdminFeature::configList))
					.then(Commands.literal("get")
						.then(Commands.argument("key", StringArgumentType.word())
							.suggests((c, b) -> SharedSuggestionProvider.suggest(AdminSettings.keys(), b))
							.executes(AdminFeature::configGet)))
					.then(Commands.literal("set")
						.then(Commands.argument("key", StringArgumentType.word())
							.suggests((c, b) -> SharedSuggestionProvider.suggest(AdminSettings.keys(), b))
							.then(Commands.argument("value", StringArgumentType.greedyString()).executes(AdminFeature::configSet)))))));
		AdminSelfTests.register();
	}

	private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestModules(
			CommandContext<CommandSourceStack> ctx, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
		return SharedSuggestionProvider.suggest(ModuleCatalog.ALL.stream().map(ModuleInfo::id), builder);
	}

	private static int moduleList(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		List<String> configured = new ArrayList<>();
		for (ModuleInfo m : ModuleCatalog.ALL) if (Modules.isConfigured(m.id())) configured.add(m.id());
		List<String> pending = Modules.pendingChanges();
		String line = "modules running=" + String.join(",", Modules.enabled()) + " configured=" + String.join(",", configured)
			+ " pending=" + pending.size();
		src.sendSuccess(() -> Msg.legacy("§7" + line), false);
		for (ModuleInfo m : ModuleCatalog.ALL) {
			boolean on = Modules.isEnabled(m.id());
			boolean cfg = Modules.isConfigured(m.id());
			String state = (on ? "§a✔ " : "§c✘ ") + "§f" + m.id() + " §8(" + m.group().name().toLowerCase(java.util.Locale.ROOT) + ")"
				+ (on != cfg ? " §6→ " + (cfg ? "on" : "off") + " after restart" : "");
			Msg.send(src, Msg.legacy(state));
		}
		for (String p : Modules.problems()) Msg.send(src, Msg.legacy("§c" + p));
		if (!pending.isEmpty()) Msg.send(src, Msg.legacy("§6Restart required: §7" + String.join(", ", pending)));
		return 1;
	}

	private static int moduleInfo(CommandContext<CommandSourceStack> ctx) {
		String id = StringArgumentType.getString(ctx, "module");
		ModuleInfo m = ModuleCatalog.get(id).orElse(null);
		if (m == null) {
			Msg.error(ctx.getSource(), "Unknown module '" + id + "'.");
			return 0;
		}
		CommandSourceStack src = ctx.getSource();
		Set<String> roots = ModuleCommands.roots(id);
		Set<String> admin = ModuleCommands.adminSubcommands(id);
		String line = "module " + id + " enabled=" + Modules.isEnabled(id) + " configured=" + Modules.isConfigured(id)
			+ " group=" + m.group().name().toLowerCase(java.util.Locale.ROOT) + " locked=" + m.locked()
			+ " requires=" + String.join(",", m.requires()) + " commands=" + String.join(",", roots)
			+ " admin=" + String.join(",", admin);
		src.sendSuccess(() -> Msg.legacy("§7" + line), false);
		Msg.send(src, Msg.legacy("§f" + m.name() + " §8– §7" + m.description()));
		if (!m.uses().isEmpty()) Msg.send(src, Msg.legacy("§8Works better with: §7" + String.join(", ", m.uses())));
		return 1;
	}

	private static int moduleSet(CommandContext<CommandSourceStack> ctx, boolean on, boolean withDeps) {
		String id = StringArgumentType.getString(ctx, "module");
		Modules.Result r = Modules.setConfigured(id, on, withDeps, ctx.getSource().getTextName());
		if (r.ok()) Msg.success(ctx.getSource(), r.message());
		else Msg.error(ctx.getSource(), r.message());
		return r.ok() ? 1 : 0;
	}

	private static int configList(CommandContext<CommandSourceStack> ctx) {
		for (AdminSettings.Setting s : AdminSettings.ALL) {
			Msg.send(ctx.getSource(), Msg.legacy("§7" + s.key() + " §8= §f" + AdminSettings.editable(AdminSettings.value(s))));
		}
		return 1;
	}

	private static int configGet(CommandContext<CommandSourceStack> ctx) {
		String key = StringArgumentType.getString(ctx, "key");
		var s = AdminSettings.get(key);
		if (s.isEmpty()) {
			Msg.error(ctx.getSource(), "Unknown setting '" + key + "'. Allowed: " + String.join(", ", AdminSettings.keys()));
			return 0;
		}
		String line = "config " + s.get().key() + "=" + AdminSettings.editable(AdminSettings.value(s.get()));
		ctx.getSource().sendSuccess(() -> Msg.legacy("§7" + line), false);
		return 1;
	}

	private static int configSet(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		String key = StringArgumentType.getString(ctx, "key");
		String value = StringArgumentType.getString(ctx, "value");
		String error = AdminSettings.set(key, value, ctx.getSource().getTextName());
		if (error != null) {
			Msg.error(ctx.getSource(), error);
			return 0;
		}
		var s = AdminSettings.get(key).orElseThrow();
		Msg.success(ctx.getSource(), s.label() + " set to §f" + AdminSettings.value(s) + "§a.");
		return 1;
	}

	static boolean commandExists(net.minecraft.server.MinecraftServer server, String... path) {
		CommandNode<CommandSourceStack> node = CommandRegistry.root();
		for (String p : path) {
			node = node.getChild(p);
			if (node == null) return false;
		}
		return true;
	}
}
