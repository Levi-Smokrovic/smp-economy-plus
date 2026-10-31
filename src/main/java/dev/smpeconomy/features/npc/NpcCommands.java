package dev.smpeconomy.features.npc;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import dev.smpeconomy.core.CommandRegistry;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Perms;

/**
 * Admin commands under {@code /smpeco npc}, node {@link NpcFeature#NODE}. {@code list} is machine-readable for e2e
 * scripts: {@code npc list enabled=… npcs=N texts=T parts=P live=L clicks=C}, then one
 * {@code npc <id> <dim> x,y,z action=<…> live=true|false} per NPC. {@code &} in texts becomes § color codes.
 */
final class NpcCommands {
	private static final SuggestionProvider<CommandSourceStack> IDS = (ctx, builder) -> {
		List<String> ids = new ArrayList<>();
		NpcConfig cfg = NpcConfig.get();
		for (NpcConfig.Npc n : cfg.npcs) ids.add(n.id);
		for (NpcConfig.Text t : cfg.texts) ids.add(t.id);
		return SharedSuggestionProvider.suggest(ids, builder);
	};
	private static final SuggestionProvider<CommandSourceStack> NPC_IDS = (ctx, builder) -> {
		List<String> ids = new ArrayList<>();
		for (NpcConfig.Npc n : NpcConfig.get().npcs) ids.add(n.id);
		return SharedSuggestionProvider.suggest(ids, builder);
	};
	private static final SuggestionProvider<CommandSourceStack> CREATE_ACTIONS = (ctx, builder) -> {
		List<String> out = new ArrayList<>(NpcActions.availableTargets(ctx.getSource().getServer()));
		out.add(NpcConfig.Action.COMMAND);
		out.add(NpcConfig.Action.NONE);
		return SharedSuggestionProvider.suggest(out, builder);
	};
	private static final SuggestionProvider<CommandSourceStack> OPEN_TARGETS = (ctx, builder) -> {
		List<String> out = new ArrayList<>(NpcActions.availableTargets(ctx.getSource().getServer()));
		out.add(NpcConfig.Action.NONE);
		return SharedSuggestionProvider.suggest(out, builder);
	};
	private static final SuggestionProvider<CommandSourceStack> SKINS = (ctx, builder) -> SharedSuggestionProvider.suggest(
		List.of("wide/steve", "slim/alex", "wide/efe", "slim/makena", "wide/noor", "wide/kai", "slim/ari", "wide/sunny", "slim/zuri"), builder);

	private NpcCommands() {}

	static void register() {
		CommandRegistry.register((dispatcher, registryAccess, environment) -> dispatcher.register(
			Commands.literal("smpeco")
				.requires(Perms.requireAdmin(Perms.ADMIN))
				.then(Commands.literal("npc")
					.requires(Perms.requireAdmin(NpcFeature.NODE))
					.executes(ctx -> help(ctx.getSource()))
					.then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
					.then(Commands.literal("reload").executes(ctx -> reload(ctx.getSource())))
					.then(Commands.literal("create")
						.then(Commands.argument("id", StringArgumentType.word())
							.then(Commands.argument("action", StringArgumentType.word()).suggests(CREATE_ACTIONS)
								.executes(ctx -> create(ctx, ""))
								.then(Commands.argument("skin", StringArgumentType.greedyString()).suggests(SKINS)
									.executes(ctx -> create(ctx, StringArgumentType.getString(ctx, "skin")))))))
					.then(Commands.literal("move")
						.then(Commands.argument("id", StringArgumentType.word()).suggests(IDS).executes(NpcCommands::move)))
					.then(Commands.literal("remove")
						.then(Commands.argument("id", StringArgumentType.word()).suggests(IDS).executes(NpcCommands::remove)))
					.then(Commands.literal("info")
						.then(Commands.argument("id", StringArgumentType.word()).suggests(NPC_IDS).executes(NpcCommands::info)))
					.then(Commands.literal("tp")
						.then(Commands.argument("id", StringArgumentType.word()).suggests(IDS).executes(NpcCommands::tp)))
					.then(Commands.literal("equip")
						.then(Commands.argument("id", StringArgumentType.word()).suggests(NPC_IDS).executes(NpcCommands::equip)))
					.then(Commands.literal("rename")
						.then(Commands.argument("id", StringArgumentType.word()).suggests(NPC_IDS)
							.then(Commands.argument("name", StringArgumentType.greedyString())
								.executes(ctx -> edit(ctx, "renamed", c -> NpcEdits.rename(c, id(ctx), StringArgumentType.getString(ctx, "name")))))))
					.then(set()))));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> set() {
		return Commands.literal("set").then(Commands.argument("id", StringArgumentType.word()).suggests(NPC_IDS)
			.then(Commands.literal("name").then(Commands.argument("text", StringArgumentType.greedyString())
				.executes(ctx -> edit(ctx, "renamed", c -> NpcEdits.rename(c, id(ctx), StringArgumentType.getString(ctx, "text"))))))
			.then(Commands.literal("title").then(Commands.argument("text", StringArgumentType.greedyString())
				.executes(ctx -> edit(ctx, "title set (\"-\" = none)", c -> NpcEdits.setTitle(c, id(ctx), StringArgumentType.getString(ctx, "text"))))))
			.then(Commands.literal("skin").then(Commands.argument("skin", StringArgumentType.greedyString()).suggests(SKINS)
				.executes(ctx -> edit(ctx, "skin set", c -> NpcEdits.setSkin(c, id(ctx), StringArgumentType.getString(ctx, "skin"))))))
			.then(Commands.literal("look").then(Commands.argument("on", BoolArgumentType.bool())
				.executes(ctx -> edit(ctx, "look-at-player " + (BoolArgumentType.getBool(ctx, "on") ? "on" : "off"),
					c -> NpcEdits.setLook(c, id(ctx), BoolArgumentType.getBool(ctx, "on"))))))
			.then(Commands.literal("message")
				.then(Commands.literal("add").then(Commands.argument("text", StringArgumentType.greedyString())
					.executes(ctx -> edit(ctx, "message added", c -> NpcEdits.addMessage(c, id(ctx), StringArgumentType.getString(ctx, "text"))))))
				.then(Commands.literal("clear").executes(ctx -> edit(ctx, "messages cleared", c -> NpcEdits.clearMessages(c, id(ctx))))))
			.then(Commands.literal("action")
				.then(Commands.literal("command").then(Commands.argument("command", StringArgumentType.greedyString())
					.executes(ctx -> edit(ctx, "now runs a command as the clicking player",
						c -> NpcEdits.setCommand(c, id(ctx), StringArgumentType.getString(ctx, "command"))))))
				.then(Commands.argument("target", StringArgumentType.word()).suggests(OPEN_TARGETS)
					.executes(ctx -> setOpen(ctx, ""))
					.then(Commands.argument("arg", StringArgumentType.greedyString())
						.executes(ctx -> setOpen(ctx, StringArgumentType.getString(ctx, "arg")))))));
	}

	private static String id(CommandContext<CommandSourceStack> ctx) {
		return StringArgumentType.getString(ctx, "id").toLowerCase(Locale.ROOT);
	}

	private interface Edit {
		void apply(NpcConfig cfg);
	}

	/** Edits a copy of npc.json, validates, saves and replaces the entities. */
	private static int edit(CommandContext<CommandSourceStack> ctx, String done, Edit edit) {
		CommandSourceStack src = ctx.getSource();
		if (NpcManager.overrideActive()) {
			Msg.error(src, "A selftest is running – try again in a moment.");
			return 0;
		}
		NpcConfig copy = NpcConfig.copy(NpcConfig.get());
		try {
			edit.apply(copy);
		} catch (IllegalArgumentException e) {
			Msg.error(src, e.getMessage());
			return 0;
		}
		String error = NpcConfig.commit(copy);
		if (error != null) {
			Msg.error(src, "Not saved: " + error);
			return 0;
		}
		NpcManager.ensureAll(src.getServer());
		Msg.success(src, "NPC " + id(ctx) + ": " + done + ".");
		return 1;
	}

	private static int help(CommandSourceStack src) {
		Msg.info(src, "§e/smpeco npc create <id> <action> [skin] §7– at your position");
		Msg.info(src, "§7Actions: §f" + String.join(", ", NpcActions.availableTargets(src.getServer())) + "§7, command, none");
		Msg.info(src, "§e/smpeco npc move|remove|info|tp|equip <id>§7, §elist§7, §ereload");
		Msg.info(src, "§e/smpeco npc rename <id> <name>§7, §eset <id> title|skin|look|action|message …");
		Msg.info(src, "§7Admins: sneak + right-click an NPC to see its settings.");
		return 1;
	}

	private static int list(CommandSourceStack src) {
		NpcConfig cfg = NpcConfig.get();
		Msg.send(src, Msg.legacy("§7npc list enabled=" + cfg.enabled + " npcs=" + cfg.npcs.size() + " texts=" + cfg.texts.size()
			+ " parts=" + NpcManager.parts().size() + " live=" + NpcManager.liveCount() + " clicks=" + NpcManager.clicks));
		for (NpcConfig.Npc n : cfg.npcs) {
			boolean live = NpcManager.live("n." + n.id + ".body") != null;
			Msg.send(src, Msg.legacy("§7  npc " + n.id + " " + pos(n.dimension, n.x, n.y, n.z) + " action=" + n.action.describe()
				+ " look=" + n.lookAtPlayer + " live=" + live));
		}
		for (NpcConfig.Text t : cfg.texts) {
			Msg.send(src, Msg.legacy("§7  text " + t.id + " " + pos(t.dimension, t.x, t.y, t.z) + " live=" + (NpcManager.live("t." + t.id) != null)));
		}
		return cfg.npcs.size();
	}

	private static String pos(String dim, double x, double y, double z) {
		return dim.replace("minecraft:", "") + " " + String.format(Locale.ROOT, "%.2f,%.2f,%.2f", x, y, z);
	}

	private static int reload(CommandSourceStack src) {
		String error = NpcConfig.CONFIG.reload();
		if (error != null) {
			Msg.error(src, "npc.json is invalid: " + error + " – the previous version stays active.");
			return 0;
		}
		NpcManager.markConfigChanged();
		NpcManager.ensureAll(src.getServer());
		NpcConfig cfg = NpcConfig.get();
		Msg.success(src, "NPCs reloaded: " + cfg.npcs.size() + " NPCs, " + cfg.texts.size() + " texts"
			+ (cfg.enabled ? "." : " (hidden – set \"enabled\": true in npc.json)."));
		return 1;
	}

	private static NpcEdits.Spot here(ServerPlayer player) {
		return NpcEdits.Spot.of(NpcManager.dimensionId(player), player.getX(), player.getY(), player.getZ(), player.getYRot());
	}

	private static int create(CommandContext<CommandSourceStack> ctx, String skin) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		String action = StringArgumentType.getString(ctx, "action").toLowerCase(Locale.ROOT);
		NpcActions.Preset p = NpcActions.preset(action);
		if (p != null && !NpcActions.available(ctx.getSource().getServer(), p)) {
			Msg.error(player, "The " + action + " module isn't enabled on this server.");
			return 0;
		}
		NpcEdits.Spot at = here(player);
		int r = edit(ctx, "created at " + pos(at.dimension(), at.x(), at.y(), at.z()) + " facing " + (int) at.yaw() + "°",
			c -> NpcEdits.create(c, id(ctx), action, skin, at));
		if (r > 0 && p == null) {
			Msg.info(player, "§7Set what it does: " + "§e/smpeco npc set " + id(ctx) + " action command <command>§7 ({player} = clicking player)");
		}
		return r;
	}

	private static int move(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		NpcEdits.Spot at = here(ctx.getSource().getPlayerOrException());
		return edit(ctx, "moved to " + pos(at.dimension(), at.x(), at.y(), at.z()) + " facing " + (int) at.yaw() + "°", c -> NpcEdits.move(c, id(ctx), at));
	}

	private static int remove(CommandContext<CommandSourceStack> ctx) {
		return edit(ctx, "removed", c -> NpcEdits.remove(c, id(ctx)));
	}

	private static int equip(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		var gear = NpcBody.equipmentOf(player);
		return edit(ctx, "now wears your gear (" + (gear.isEmpty() ? "nothing" : String.join(", ", gear.values())) + ")",
			c -> NpcEdits.setEquipment(c, id(ctx), gear));
	}

	private static int setOpen(CommandContext<CommandSourceStack> ctx, String arg) {
		String target = StringArgumentType.getString(ctx, "target").toLowerCase(Locale.ROOT);
		NpcActions.Preset p = NpcActions.preset(target);
		if (p != null && !NpcActions.available(ctx.getSource().getServer(), p)) {
			Msg.error(ctx.getSource(), "The " + target + " module isn't enabled on this server.");
			return 0;
		}
		return edit(ctx, target.equals(NpcConfig.Action.NONE) ? "no action" : "opens " + target + (arg.isBlank() ? "" : " " + arg.trim()),
			c -> NpcEdits.setOpen(c, id(ctx), target, arg));
	}

	private static int info(CommandContext<CommandSourceStack> ctx) {
		NpcConfig.Npc n = null;
		for (NpcConfig.Npc x : NpcConfig.get().npcs) if (x.id.equals(id(ctx))) n = x;
		if (n == null) {
			Msg.error(ctx.getSource(), "Unknown NPC: " + id(ctx));
			return 0;
		}
		sendInfo(ctx.getSource(), n);
		return 1;
	}

	/** Also shown when an admin sneak-clicks an NPC. */
	static void sendInfo(CommandSourceStack src, NpcConfig.Npc n) {
		String base = "/smpeco npc ";
		Msg.send(src, Msg.legacy("§6NPC §e" + n.id + " §8» ").append(Msg.legacy(n.name.isEmpty() ? "§7(no name)" : n.name)));
		Msg.send(src, Msg.legacy("§7Title: ").append(Msg.legacy(n.description.isEmpty() ? "§8-" : n.description)));
		Msg.send(src, Msg.legacy("§7Skin: §f" + (n.skin.isEmpty() ? "default" : n.skin) + " §7· look at players: §f" + n.lookAtPlayer));
		Msg.send(src, Msg.legacy("§7Action: §f" + n.action.describe() + " §7· at " + pos(n.dimension, n.x, n.y, n.z)));
		Msg.send(src, Msg.clickSuggest("§e[Rename] ", base + "rename " + n.id + " ", "§7Set the name (& = colour)")
			.append(Msg.clickSuggest("§e[Title] ", base + "set " + n.id + " title ", "§7Second line (\"-\" = none)"))
			.append(Msg.clickSuggest("§e[Action] ", base + "set " + n.id + " action ", "§7What a click does"))
			.append(Msg.clickSuggest("§e[Skin] ", base + "set " + n.id + " skin ", "§7Player name or wide/<name>"))
			.append(Msg.clickRun("§e[Move here] ", base + "move " + n.id, "§7Move it to your position"))
			.append(Msg.clickSuggest("§c[Remove]", base + "remove " + n.id, "§7Delete this NPC")));
	}

	private static int tp(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		String id = id(ctx);
		String dim = null;
		double x = 0, y = 0, z = 0;
		float yaw = 0;
		NpcConfig cfg = NpcConfig.get();
		for (NpcConfig.Npc n : cfg.npcs) {
			if (n.id.equals(id)) {
				dim = n.dimension;
				x = n.x;
				y = n.y;
				z = n.z;
				yaw = n.yaw;
			}
		}
		for (NpcConfig.Text t : cfg.texts) {
			if (dim == null && t.id.equals(id)) {
				dim = t.dimension;
				x = t.x;
				y = t.y - 2.5;
				z = t.z;
			}
		}
		if (dim == null) {
			Msg.error(player, "Unknown NPC or text: " + id);
			return 0;
		}
		ServerLevel level = NpcManager.level(ctx.getSource().getServer(), dim);
		if (level == null) {
			Msg.error(player, "Unknown dimension: " + dim);
			return 0;
		}
		// 2.5 blocks in front of the NPC, looking at it.
		double rad = Math.toRadians(yaw);
		double tx = x - Math.sin(rad) * 2.5, tz = z + Math.cos(rad) * 2.5;
		player.teleportTo(level, tx, y, tz, Set.<Relative>of(), yaw + 180f, 0f, true);
		Msg.success(player, "Teleported to " + id + ".");
		return 1;
	}
}
