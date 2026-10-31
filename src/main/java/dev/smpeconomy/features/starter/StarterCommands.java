package dev.smpeconomy.features.starter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;
import dev.smpeconomy.core.CommandRegistry;
import dev.smpeconomy.core.CoreConfig;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Perms;
import dev.smpeconomy.core.PlayerDataService;

/**
 * Admin commands under {@code /smpeco starterkit} (alias {@code starter}), node {@link StarterFeature#NODE}. The
 * first line of {@code show} is machine-readable for e2e scripts:
 * {@code starterkit giveKit=… onlyNewPlayers=… kitVersion=N items=M startBalance=<cent>}. {@code balance} edits
 * core.json {@code startBalance}, the single source for it.
 */
final class StarterCommands {
	private StarterCommands() {}

	static void register() {
		CommandRegistry.register((dispatcher, registryAccess, environment) -> {
			for (String root : List.of("starterkit", "starter")) {
				dispatcher.register(Commands.literal("smpeco")
					.requires(Perms.requireAdmin(Perms.ADMIN))
					.then(tree(root)));
			}
		});
	}

	private static LiteralArgumentBuilder<CommandSourceStack> tree(String name) {
		return Commands.literal(name)
			.requires(Perms.requireAdmin(StarterFeature.NODE))
			.executes(ctx -> show(ctx.getSource()))
			.then(Commands.literal("show").executes(ctx -> show(ctx.getSource())))
			.then(Commands.literal("capture").executes(StarterCommands::capture))
			.then(Commands.literal("clear").executes(ctx -> edit(ctx.getSource(), "Starter kit cleared – new players get no items.", c -> c.kit.clear())))
			.then(Commands.literal("bump").executes(ctx -> edit(ctx.getSource(), null, c -> c.kitVersion++)))
			.then(Commands.literal("enable").executes(ctx -> edit(ctx.getSource(), "New players get the starter kit.", c -> c.giveKit = true)))
			.then(Commands.literal("disable").executes(ctx -> edit(ctx.getSource(), "Starter kit switched off.", c -> c.giveKit = false)))
			.then(Commands.literal("onlynew").then(Commands.argument("on", BoolArgumentType.bool())
				.executes(ctx -> {
					boolean on = BoolArgumentType.getBool(ctx, "on");
					return edit(ctx.getSource(), on ? "Only new players get the kit." : "Every player gets the kit once (also existing players).",
						c -> c.onlyNewPlayers = on);
				})))
			.then(Commands.literal("give").then(Commands.argument("player", EntityArgument.player()).executes(StarterCommands::give)))
			.then(Commands.literal("reset").then(Commands.argument("name", StringArgumentType.word())
				.suggests((ctx, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(ctx.getSource().getOnlinePlayerNames(), b))
				.executes(StarterCommands::reset)))
			.then(Commands.literal("balance")
				.executes(ctx -> {
					Msg.info(ctx.getSource(), "Start balance for new accounts: §e" + Money.format(EconomyService.startBalance()));
					return 1;
				})
				.then(Commands.argument("amount", StringArgumentType.word()).executes(StarterCommands::balance)));
	}

	private interface Edit {
		void apply(StarterConfig cfg);
	}

	private static int edit(CommandSourceStack src, String done, Edit edit) {
		StarterConfig copy = StarterConfig.copy(StarterConfig.get());
		edit.apply(copy);
		String error = StarterConfig.commit(copy);
		if (error != null) {
			Msg.error(src, "Not saved: " + error);
			return 0;
		}
		Msg.success(src, done != null ? done : "Kit version is now " + StarterConfig.get().kitVersion
			+ " – every player who qualifies gets the kit once more.");
		return 1;
	}

	private static int show(CommandSourceStack src) {
		StarterConfig c = StarterConfig.get();
		Msg.send(src, Msg.legacy("§7starterkit giveKit=" + c.giveKit + " onlyNewPlayers=" + c.onlyNewPlayers + " kitVersion=" + c.kitVersion
			+ " items=" + c.kit.size() + " startBalance=" + EconomyService.startBalance()));
		Msg.info(src, "§6Starter §8» §7start balance §e" + Money.format(EconomyService.startBalance()) + "§7, kit "
			+ (c.giveKit ? "§aon" : "§coff") + "§7" + (c.onlyNewPlayers ? " (new players only, < " + c.newPlayerMaxMinutes + " min played)" : " (every player once)"));
		if (c.kit.isEmpty()) Msg.info(src, "§7Kit is empty – hold the items and use §e/smpeco starterkit capture§7.");
		for (StarterConfig.KitItem k : c.kit) {
			Msg.send(src, Msg.legacy("§8  " + StarterService.slotName(k.slot) + " §7" + StarterService.describe(k)));
		}
		return c.kit.size();
	}

	private static int capture(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		List<StarterConfig.KitItem> items = StarterService.capture(player);
		if (items.isEmpty()) {
			Msg.error(player, "Your inventory is empty – put the kit items in your inventory first (armor and offhand count too).");
			return 0;
		}
		int r = edit(ctx.getSource(), "Starter kit saved: " + items.size() + " stacks from your inventory (slots, armor and offhand kept).", c -> {
			c.kit.clear();
			c.kit.addAll(items);
		});
		if (r > 0 && !StarterConfig.get().giveKit) Msg.info(player, "§7The kit is switched off – §e/smpeco starterkit enable§7.");
		return r;
	}

	private static int give(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
		StarterConfig c = StarterConfig.get();
		if (c.kit.isEmpty()) {
			Msg.error(ctx.getSource(), "The starter kit is empty.");
			return 0;
		}
		StarterService.Given g = StarterService.give(target, c);
		Msg.success(ctx.getSource(), "Starter kit given to " + target.getPlainTextName() + " (" + g.placed() + " stacks"
			+ (g.dropped() > 0 ? ", " + g.dropped() + " dropped at their feet" : "") + (g.skipped() > 0 ? ", " + g.skipped() + " unreadable" : "") + ").");
		return 1;
	}

	private static int reset(CommandContext<CommandSourceStack> ctx) {
		String name = StringArgumentType.getString(ctx, "name");
		Optional<UUID> id = StarterService.findPlayer(ctx.getSource().getServer(), name);
		if (id.isEmpty()) {
			Msg.error(ctx.getSource(), "Unknown player: " + name);
			return 0;
		}
		PlayerDataService.remove(id.get(), StarterService.KEY);
		Msg.success(ctx.getSource(), name + " gets the starter kit again on their next join (if they qualify).");
		return 1;
	}

	/** Same file as the admin GUI and manual edits; validated by loading core.json. */
	private static int balance(CommandContext<CommandSourceStack> ctx) {
		String input = StringArgumentType.getString(ctx, "amount");
		OptionalLong cents = Money.parseAllowZero(input);
		if (cents.isEmpty()) {
			Msg.error(ctx.getSource(), "Invalid amount: " + input);
			return 0;
		}
		String error;
		try {
			JsonObject json = Files.isRegularFile(CoreConfig.CONFIG.path())
				? JsonParser.parseString(Files.readString(CoreConfig.CONFIG.path(), StandardCharsets.UTF_8)).getAsJsonObject()
				: DataStore.gson().toJsonTree(CoreConfig.get()).getAsJsonObject();
			json.addProperty("startBalance", cents.getAsLong());
			error = StarterConfig.writeAndReload(CoreConfig.CONFIG, DataStore.gson().toJson(json));
		} catch (IOException | RuntimeException e) {
			error = e.getMessage();
		}
		if (error != null) {
			Msg.error(ctx.getSource(), "Not saved: " + error);
			return 0;
		}
		Msg.success(ctx.getSource(), "New accounts now start with " + Money.format(EconomyService.startBalance()) + ".");
		return 1;
	}
}
