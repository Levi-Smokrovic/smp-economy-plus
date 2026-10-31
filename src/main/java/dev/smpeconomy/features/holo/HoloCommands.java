package dev.smpeconomy.features.holo;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import dev.smpeconomy.core.CommandRegistry;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Perms;
import dev.smpeconomy.features.holo.HoloStore.Holo;

/** Admin command {@code /holo}. {@code text <id>} prints plain text for RCON and e2e scripts. */
final class HoloCommands {
	private static final SuggestionProvider<CommandSourceStack> IDS = (ctx, builder) -> {
		for (Holo h : HoloStore.data().holos) builder.suggest(h.id);
		return builder.buildFuture();
	};

	private static final SuggestionProvider<CommandSourceStack> TYPES = (ctx, builder) ->
		SharedSuggestionProvider.suggest(Arrays.stream(HoloType.values()).map(t -> t.id), builder);

	private HoloCommands() {}

	static void register() {
		CommandRegistry.register((dispatcher, registryAccess, environment) -> {
			// Only suggest the English ids; byId still accepts the legacy German ones.
			LiteralArgumentBuilder<CommandSourceStack> create = Commands.literal("create")
				.executes(ctx -> usage(ctx.getSource()))
				.then(Commands.argument("type", StringArgumentType.word()).suggests(TYPES)
					.executes(ctx -> {
						Optional<HoloType> type = HoloType.byId(StringArgumentType.getString(ctx, "type"));
						return type.isPresent() ? create(ctx.getSource(), type.get()) : usage(ctx.getSource());
					}));
			dispatcher.register(Commands.literal("holo")
				.requires(Perms.requireAdmin(HoloFeature.NODE))
				.executes(ctx -> openOrHelp(ctx.getSource()))
				.then(create)
				.then(Commands.literal("remove")
					.executes(ctx -> removeNearest(ctx.getSource()))
					.then(Commands.argument("id", IntegerArgumentType.integer(1)).suggests(IDS)
						.executes(ctx -> removeById(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "id")))))
				.then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
				.then(Commands.literal("tp")
					.then(Commands.argument("id", IntegerArgumentType.integer(1)).suggests(IDS)
						.executes(ctx -> teleport(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "id")))))
				.then(Commands.literal("refresh").executes(ctx -> refresh(ctx.getSource())))
				.then(Commands.literal("text")
					.then(Commands.argument("id", IntegerArgumentType.integer(1)).suggests(IDS)
						.executes(ctx -> text(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "id"))))));
		});
	}

	record CreateResult(Holo holo, String error) {}

	static CreateResult createAt(ServerPlayer player, HoloType type) {
		HoloStore.Data data = HoloStore.data();
		int max = HoloConfig.get().maxHolos;
		if (data.holos.size() >= max) return new CreateResult(null, "Max " + max + " holograms – remove one first.");
		Holo h;
		try {
			h = data.add(type, HoloManager.dimensionId(player.level()), player.getX(), player.getY(), player.getZ(),
				player.getGameProfile().name(), System.currentTimeMillis());
		} catch (IllegalArgumentException e) {
			return new CreateResult(null, "Invalid position.");
		}
		HoloStore.markDirty();
		// If this fails the definition stays and the next ensure pass (1 s) places the display.
		HoloManager.create(player.level().getServer(), h);
		return new CreateResult(h, null);
	}

	static Holo remove(MinecraftServer server, int id) {
		Holo h = HoloStore.data().remove(id);
		if (h == null) return null;
		HoloStore.markDirty();
		HoloManager.despawn(server, h);
		return h;
	}

	static String describe(Holo h) {
		return "§e#" + h.id + " §7(§f" + h.label() + "§7)";
	}

	private static int usage(CommandSourceStack src) {
		Msg.error(src, "Usage: /holo create <" + HoloType.idList() + ">");
		return 0;
	}

	private static int openOrHelp(CommandSourceStack src) {
		ServerPlayer player = src.getPlayer();
		if (player != null) {
			HoloGui.openList(player, 0);
			return 1;
		}
		Msg.info(src, "§e/holo create <" + HoloType.idList() + "> §7· §e/holo remove [id] §7· §e/holo list §7· §e/holo refresh §7· §e/holo text <id>");
		return 1;
	}

	private static int create(CommandSourceStack src, HoloType type) {
		ServerPlayer player = src.getPlayer();
		if (player == null) {
			Msg.error(src, "In-game only (the hologram appears at your position).");
			return 0;
		}
		CreateResult r = createAt(player, type);
		if (r.error() != null) {
			Msg.error(src, r.error());
			return 0;
		}
		Msg.success(src, "Hologram " + describe(r.holo()) + " §acreated.");
		return 1;
	}

	private static int removeNearest(CommandSourceStack src) {
		ServerPlayer player = src.getPlayer();
		if (player == null) {
			Msg.error(src, "From the console only with an ID: /holo remove <id>");
			return 0;
		}
		double radius = HoloConfig.get().removeRadius;
		Optional<Holo> near = HoloStore.data().nearest(HoloManager.dimensionId(player.level()), player.getX(), player.getY(), player.getZ(), radius);
		if (near.isEmpty()) {
			Msg.error(src, "No hologram within " + HoloStore.coord(radius) + " blocks. §7(/holo list)");
			return 0;
		}
		return removeById(src, near.get().id);
	}

	private static int removeById(CommandSourceStack src, int id) {
		Holo h = remove(src.getServer(), id);
		if (h == null) {
			Msg.error(src, "Hologram #" + id + " doesn't exist.");
			return 0;
		}
		Msg.success(src, "Hologram " + describe(h) + " §aremoved.");
		return 1;
	}

	private static int list(CommandSourceStack src) {
		List<Holo> holos = HoloStore.data().holos;
		if (holos.isEmpty()) {
			Msg.info(src, "No holograms. §7Create one: §e/holo create <" + HoloType.idList() + ">");
			return 1;
		}
		Msg.info(src, "§eHolograms §8(§f" + holos.size() + "§8)§7:");
		for (Holo h : List.copyOf(holos)) {
			boolean live = HoloManager.live(h.id) != null;
			MutableComponent line = Msg.legacy("§8 • §e#" + h.id + " §f" + h.label() + " §8» §7" + h.where() + " " + (live ? "§a●" : "§8○") + " ");
			line.append(Msg.clickRun("§b[TP]", "/holo tp " + h.id, "§7Teleport there"));
			line.append(Component.literal(" "));
			line.append(Msg.clickSuggest("§c[Remove]", "/holo remove " + h.id, "§7Insert the remove command"));
			Msg.send(src, line);
		}
		return holos.size();
	}

	private static int teleport(CommandSourceStack src, int id) {
		ServerPlayer player = src.getPlayer();
		if (player == null) {
			Msg.error(src, "In-game only.");
			return 0;
		}
		Holo h = HoloStore.data().byId(id);
		if (h == null) {
			Msg.error(src, "Hologram #" + id + " doesn't exist.");
			return 0;
		}
		return teleport(player, h) ? 1 : 0;
	}

	/** The creator stood there when placing it, so the position is safe to teleport to. */
	static boolean teleport(ServerPlayer player, Holo h) {
		ServerLevel level = HoloManager.level(player.level().getServer(), h.dimension);
		if (level == null) {
			Msg.error(player, "The dimension of hologram #" + h.id + " doesn't exist.");
			return false;
		}
		player.teleportTo(level, h.x, h.y, h.z, Set.of(), player.getYRot(), player.getXRot(), false);
		Msg.info(player, "Teleported to hologram " + describe(h) + "§7.");
		return true;
	}

	private static int refresh(CommandSourceStack src) {
		HoloManager.ensureAll(src.getServer());
		int n = HoloManager.updateAll(src.getServer());
		Msg.success(src, "Holograms refreshed §7(" + n + " loaded, " + HoloStore.data().holos.size() + " total)§a.");
		return 1;
	}

	private static int text(CommandSourceStack src, int id) {
		Holo h = HoloStore.data().byId(id);
		if (h == null) {
			Msg.error(src, "Hologram #" + id + " doesn't exist.");
			return 0;
		}
		boolean live = HoloManager.live(h.id) != null;
		String header = "holo #" + h.id + " type=" + h.type + " dim=" + h.dimension + " pos=" + HoloStore.coord(h.x) + ","
			+ HoloStore.coord(h.y) + "," + HoloStore.coord(h.z) + " live=" + live;
		String body = HoloManager.plainText(src.getServer(), h);
		src.sendSuccess(() -> Component.literal(header + "\n" + body), false);
		return 1;
	}
}
