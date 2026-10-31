package dev.smpeconomy.features.settings;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.PatrolSpawner;
import net.minecraft.world.level.levelgen.PhantomSpawner;
import net.minecraft.world.phys.AABB;
import dev.smpeconomy.core.Args;
import dev.smpeconomy.core.CommandRegistry;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Perms;
import dev.smpeconomy.core.Settings;
import dev.smpeconomy.features.settings.mixin.PatrolSpawnerInvoker;

/**
 * {@code /settings} for players. Admin commands under {@code /smpeco settings}, also over RCON, include the
 * diagnostics {@code check <pos>}, {@code spawntest <pos> <attempts> [natural|patrol]} and {@code phantomtest}.
 */
public final class SettingsCommand {
	public static final String NODE = "smpeconomy.settings";
	public static final String ADMIN_NODE = "smpeconomy.admin.settings";
	private static final int MAX_SPAWNTEST = 200;
	private static final String SPAWN_NATURAL = "natural";
	private static final String SPAWN_PATROL = "patrol";

	private SettingsCommand() {}

	static void register() {
		CommandRegistry.register((dispatcher, registryAccess, environment) -> {
			registerPlayer(dispatcher, "settings");
			registerAdmin(dispatcher);
		});
	}

	private static void registerPlayer(CommandDispatcher<CommandSourceStack> dispatcher, String name) {
		dispatcher.register(Commands.literal(name)
			.requires(Perms.require(NODE))
			.executes(SettingsCommand::openGui)
			.then(Commands.literal("list").executes(SettingsCommand::list))
			.then(Commands.argument("setting", StringArgumentType.word()).suggests(keySuggestions())
				.executes(ctx -> toggle(ctx, null))
				.then(Commands.argument("value", StringArgumentType.word()).suggests(onOffSuggestions())
					.executes(ctx -> toggle(ctx, StringArgumentType.getString(ctx, "value"))))));
	}

	private static void registerAdmin(CommandDispatcher<CommandSourceStack> dispatcher) {
		LiteralArgumentBuilder<CommandSourceStack> settings = Commands.literal("settings")
			.requires(Perms.requireAdmin(ADMIN_NODE))
			.then(Commands.literal("check")
				.then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(SettingsCommand::check)))
			.then(Commands.literal("spawntest")
				.then(Commands.argument("pos", BlockPosArgument.blockPos())
					.then(Commands.argument("attempts", IntegerArgumentType.integer(1, MAX_SPAWNTEST))
						.executes(ctx -> spawnTest(ctx, SPAWN_NATURAL))
						.then(Commands.argument("type", StringArgumentType.word())
							.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(List.of(SPAWN_NATURAL, SPAWN_PATROL), builder))
							.executes(ctx -> spawnTest(ctx, StringArgumentType.getString(ctx, "type")))))))
			.then(Commands.literal("phantomtest").executes(SettingsCommand::phantomTest))
			.then(Commands.argument("player", StringArgumentType.word()).suggests(Args.knownPlayers())
				.executes(SettingsCommand::adminShow)
				.then(Commands.literal("reset").executes(SettingsCommand::adminReset))
				.then(Commands.argument("setting", StringArgumentType.word()).suggests(keySuggestions())
					.then(Commands.argument("value", StringArgumentType.word()).suggests(onOffSuggestions())
						.executes(SettingsCommand::adminSet))));
		dispatcher.register(Commands.literal("smpeco").requires(Perms.requireAdmin(Perms.ADMIN)).then(settings));
	}

	private static SuggestionProvider<CommandSourceStack> keySuggestions() {
		return (ctx, builder) -> {
			List<String> keys = new ArrayList<>();
			for (Settings.Def d : Settings.all()) keys.add(d.key());
			return SharedSuggestionProvider.suggest(keys, builder);
		};
	}

	private static SuggestionProvider<CommandSourceStack> onOffSuggestions() {
		return (ctx, builder) -> SharedSuggestionProvider.suggest(List.of("on", "off"), builder);
	}

	private static int openGui(CommandContext<CommandSourceStack> ctx) {
		ServerPlayer player = ctx.getSource().getPlayer();
		if (player == null) {
			Msg.error(ctx.getSource(), "Players only.");
			return 0;
		}
		if (!SettingsGui.open(player, 0)) {
			Msg.error(player, "Settings can't be opened right now.");
			return 0;
		}
		return 1;
	}

	private static int list(CommandContext<CommandSourceStack> ctx) {
		ServerPlayer player = ctx.getSource().getPlayer();
		if (player == null) {
			Msg.error(ctx.getSource(), "Players only.");
			return 0;
		}
		Msg.info(player, "§6Your Settings §8(click to toggle)");
		for (Settings.Def d : Settings.all()) {
			boolean on = Settings.get(player.getUUID(), d.key());
			MutableComponent line = Msg.legacy("§8• ").append(Msg.clickRun(
				"§f" + SettingsLogic.plain(d.label()) + " §8(" + d.key() + ")§7: " + SettingsLogic.stateLabel(on),
				"/settings " + d.key(),
				"§7" + SettingsLogic.plain(d.description()) + "\n§eClick to toggle"));
			Msg.send(player, line);
		}
		return 1;
	}

	private static int toggle(CommandContext<CommandSourceStack> ctx, String valueArg) {
		ServerPlayer player = ctx.getSource().getPlayer();
		if (player == null) {
			Msg.error(ctx.getSource(), "Players only.");
			return 0;
		}
		String key = StringArgumentType.getString(ctx, "setting").toLowerCase(Locale.ROOT);
		Optional<Settings.Def> def = Settings.visible(key) ? Settings.def(key) : Optional.empty();
		if (def.isEmpty()) {
			Gui.failSound(player);
			Msg.error(player, "Unknown setting: " + key + ". §7See all with §f/settings list§7.");
			return 0;
		}
		boolean target;
		if (valueArg == null) {
			target = !Settings.get(player.getUUID(), key);
		} else {
			Optional<Boolean> parsed = SettingsLogic.parseOnOff(valueArg);
			if (parsed.isEmpty()) {
				Gui.failSound(player);
				Msg.error(player, "Please use §fon §cor §foff§c.");
				return 0;
			}
			target = parsed.get();
			if (Settings.get(player.getUUID(), key) == target) {
				Msg.info(player, "§f" + SettingsLogic.plain(def.get().label()) + "§7 is already " + SettingsLogic.stateLabel(target) + "§7.");
				return 1;
			}
		}
		SettingsLogic.set(player.getUUID(), key, target);
		SettingsGui.feedback(player, def.get(), target);
		Msg.info(player, "§f" + SettingsLogic.plain(def.get().label()) + "§7: " + SettingsLogic.stateLabel(target));
		return 1;
	}

	private static int adminShow(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		UUID id = Args.knownPlayer(ctx, "player");
		Msg.send(ctx.getSource(), Msg.legacy("§7" + summary(id)));
		return 1;
	}

	/** One line, so it reads well over RCON. */
	static String summary(UUID id) {
		StringBuilder sb = new StringBuilder("settings ").append(EconomyService.nameOrUnknown(id));
		for (Map.Entry<String, Boolean> e : Settings.values(id).entrySet()) sb.append(' ').append(e.getKey()).append('=').append(e.getValue());
		return sb.toString();
	}

	private static int adminSet(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		UUID id = Args.knownPlayer(ctx, "player");
		String key = StringArgumentType.getString(ctx, "setting").toLowerCase(Locale.ROOT);
		Optional<Boolean> value = SettingsLogic.parseOnOff(StringArgumentType.getString(ctx, "value"));
		if (!Settings.visible(key)) {
			Msg.error(ctx.getSource(), "Unknown setting: " + key);
			return 0;
		}
		if (value.isEmpty()) {
			Msg.error(ctx.getSource(), "Value must be on or off.");
			return 0;
		}
		SettingsLogic.set(id, key, value.get());
		Msg.send(ctx.getSource(), Msg.legacy("§7settings " + EconomyService.nameOrUnknown(id) + " " + key + "=" + value.get()));
		return 1;
	}

	private static int adminReset(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		UUID id = Args.knownPlayer(ctx, "player");
		int changed = SettingsLogic.resetAll(id);
		Msg.send(ctx.getSource(), Msg.legacy("§7settings " + EconomyService.nameOrUnknown(id) + " reset changed=" + changed));
		return 1;
	}

	/** Which player decides at this position, and would a natural monster spawn be blocked? */
	private static int check(CommandContext<CommandSourceStack> ctx) {
		ServerLevel level = ctx.getSource().getLevel();
		BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");
		double x = pos.getX() + 0.5, y = pos.getY(), z = pos.getZ() + 0.5;
		Player decider = SpawnRules.decidingPlayer(level.players(), x, y, z);
		boolean blocked = SpawnRules.blocksNaturalSpawn(level.players(), MobCategory.MONSTER, null, x, y, z);
		String line = String.format(Locale.ROOT, "check blocked=%s nearest=%s dist=%s", blocked,
			decider == null ? "-" : decider.getGameProfile().name(),
			decider == null ? "-" : String.format(Locale.ROOT, "%.1f", Math.sqrt(decider.distanceToSqr(x, y, z))));
		Msg.send(ctx.getSource(), Msg.legacy("§7" + line));
		return blocked ? 1 : 0;
	}

	/**
	 * Runs real vanilla spawning at a position, going through the mixin: {@code natural} is
	 * {@code NaturalSpawner.spawnCategoryForPosition} (category MONSTER), {@code patrol} a {@code PatrolSpawner}
	 * member (pillager exactly on {@code pos}, without leader banner). All in one tick: only what this run created
	 * within ±24/±8 is counted and removed, existing mobs stay untouched. Meant for e2e tests in a closed, dark test
	 * room.
	 */
	private static int spawnTest(CommandContext<CommandSourceStack> ctx, String kind) {
		ServerLevel level = ctx.getSource().getLevel();
		BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");
		int attempts = IntegerArgumentType.getInteger(ctx, "attempts");
		String art = switch (kind.toLowerCase(Locale.ROOT)) {
			default -> kind.toLowerCase(Locale.ROOT);
		};
		if (!art.equals(SPAWN_NATURAL) && !art.equals(SPAWN_PATROL)) {
			Msg.error(ctx.getSource(), "Type must be " + SPAWN_NATURAL + " or " + SPAWN_PATROL + ".");
			return 0;
		}
		AABB area = new AABB(pos).inflate(24, 8, 24);
		Set<UUID> before = new HashSet<>();
		for (Mob m : monsters(level, area)) before.add(m.getUUID());
		// All existing entities including animals: a new baby zombie can pick an existing chicken as a mount.
		Set<UUID> existing = new HashSet<>();
		for (Entity e : level.getEntities((Entity) null, area.inflate(6, 4, 6), e -> true)) existing.add(e.getUUID());
		PatrolSpawnerInvoker patrol = art.equals(SPAWN_PATROL) ? (PatrolSpawnerInvoker) (Object) new PatrolSpawner() : null;
		for (int i = 0; i < attempts; i++) {
			if (patrol != null) patrol.smpeco$spawnPatrolMember(level, pos, level.getRandom(), false);
			else NaturalSpawner.spawnCategoryForPosition(MobCategory.MONSTER, level, pos);
		}
		int spawned = 0;
		for (Mob m : monsters(level, area)) {
			if (m.isRemoved() || before.contains(m.getUUID())) continue;
			spawned++;
			// Remove newly created mounts and passengers (chicken jockey); existing ones only dismount.
			for (Entity e : m.getRootVehicle().getSelfAndPassengers().toList()) {
				if (existing.contains(e.getUUID())) {
					e.ejectPassengers();
					if (e instanceof net.minecraft.world.entity.animal.chicken.Chicken c) c.setChickenJockey(false);
				} else {
					e.discard();
				}
			}
		}
		Msg.send(ctx.getSource(), Msg.legacy("§7spawntest spawned=" + spawned + " attempts=" + attempts + " existing=" + before.size() + " type=" + art));
		return spawned;
	}

	private static List<Mob> monsters(ServerLevel level, AABB area) {
		return level.getEntitiesOfClass(Mob.class, area, m -> m.getType().getCategory() == MobCategory.MONSTER && !m.isRemoved());
	}

	/**
	 * Real {@code PhantomSpawner} tick in this world as a dry run: the mixin records for each player whether they
	 * would be skipped, and skips everyone, so no phantoms ever spawn. The check is only reached at night (or without
	 * sky light) and with the spawn_phantoms gamerule.
	 */
	private static int phantomTest(CommandContext<CommandSourceStack> ctx) {
		ServerLevel level = ctx.getSource().getLevel();
		boolean rule = level.getGameRules().get(GameRules.SPAWN_PHANTOMS);
		boolean dark = level.getSkyDarken() >= 5 || !level.dimensionType().hasSkyLight();
		Map<UUID, Boolean> result;
		SpawnRules.beginPhantomDryRun();
		try {
			new PhantomSpawner().tick(level, true);
		} finally {
			result = SpawnRules.endPhantomDryRun();
		}
		List<UUID> skipped = new ArrayList<>();
		for (Map.Entry<UUID, Boolean> e : result.entrySet()) {
			if (e.getValue()) skipped.add(e.getKey());
		}
		String line = "phantomtest dark=" + dark + " rule=" + rule
			+ " checked=" + String.join(",", SpawnRules.names(level, result.keySet()))
			+ " skipped=" + String.join(",", SpawnRules.names(level, skipped));
		Msg.send(ctx.getSource(), Msg.legacy("§7" + line));
		return result.size();
	}
}
