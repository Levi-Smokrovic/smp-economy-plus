package dev.smpeconomy.features.afk;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import dev.smpeconomy.core.Args;
import dev.smpeconomy.core.CommandRegistry;
import dev.smpeconomy.core.CrateKeys;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Perms;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.TimeUtil;

/** {@code /afkzone} and its admin subcommands. Not {@code /afk}, which belongs to Essential Commands. */
final class AfkCommand {
	static final String NODE = "smpeconomy.afk";
	static final String ADMIN_NODE = "smpeconomy.admin.afk";
	/** Area above which pos1/pos2 warn about an accidentally huge zone. */
	private static final long LARGE_AREA = 10_000;

	private AfkCommand() {}

	static void register() {
		Predicate<CommandSourceStack> admin = Perms.requireAdmin(ADMIN_NODE);
		CommandRegistry.register((dispatcher, registryAccess, environment) -> dispatcher.register(
			Commands.literal("afkzone")
				.requires(Perms.require(NODE))
				.executes(AfkCommand::show)
				.then(corner("pos1", true, admin))
				.then(corner("pos2", false, admin))
				.then(Commands.literal("clear").requires(admin).executes(AfkCommand::clear))
				.then(Commands.literal("info").requires(admin).executes(AfkCommand::info))
				.then(Commands.literal("status").requires(admin)
					.then(Commands.argument("player", StringArgumentType.word()).suggests(Args.knownPlayers())
						.executes(AfkCommand::status)))
				.then(Commands.literal("set").requires(admin)
					.then(Commands.literal("interval")
						.then(Commands.argument("seconds", IntegerArgumentType.integer(AfkConfig.MIN_INTERVAL_SECONDS, AfkConfig.MAX_INTERVAL_SECONDS))
							.executes(AfkCommand::setInterval)))
					.then(Commands.literal("reward")
						.then(Commands.argument("amount", StringArgumentType.greedyString()).executes(AfkCommand::setReward)))
					.then(Commands.literal("keychance")
						.then(Commands.argument("percent", DoubleArgumentType.doubleArg(0, 100)).executes(AfkCommand::setKeyChance)))
					.then(Commands.literal("daylimit")
						.then(Commands.argument("count", IntegerArgumentType.integer(0, AfkConfig.MAX_DAY_LIMIT))
							.executes(AfkCommand::setDayLimit)))
					.then(Commands.literal("iplimit")
						.then(Commands.argument("count", IntegerArgumentType.integer(0, AfkConfig.MAX_ACCOUNTS_PER_IP))
							.executes(AfkCommand::setIpLimit))))));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> corner(String name, boolean first, Predicate<CommandSourceStack> admin) {
		return Commands.literal(name).requires(admin)
			.executes(ctx -> {
				ServerPlayer p = ctx.getSource().getPlayerOrException();
				return setCorner(ctx.getSource(), first, AfkService.dimensionOf(p), p.blockPosition());
			})
			.then(Commands.argument("pos", BlockPosArgument.blockPos())
				.executes(ctx -> setCorner(ctx.getSource(), first,
					ctx.getSource().getLevel().dimension().identifier().toString(), BlockPosArgument.getBlockPos(ctx, "pos")))
				.then(Commands.argument("dimension", DimensionArgument.dimension())
					.executes(ctx -> {
						ServerLevel level = DimensionArgument.getDimension(ctx, "dimension");
						return setCorner(ctx.getSource(), first, level.dimension().identifier().toString(), BlockPosArgument.getBlockPos(ctx, "pos"));
					})));
	}

	private static int show(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		AfkConfig cfg = AfkFeature.CONFIG.get();
		AfkZone zone = cfg.zone();
		if (zone == null) {
			Msg.info(src, "There's no AFK zone right now.");
			return 0;
		}
		Msg.info(src, "§eAFK zone §7(" + dimensionLabel(zone.dimension()) + "): §f" + zone.minX() + " " + zone.minY() + " " + zone.minZ()
			+ " §7to §f" + zone.maxX() + " " + zone.maxY() + " " + zone.maxZ());
		Msg.info(src, rewardText(cfg));
		ServerPlayer p = src.getPlayer();
		if (p == null) return 1;
		UUID id = p.getUUID();
		if (cfg.maxRewardsPerDay > 0) {
			Msg.info(src, "Today: §e" + AfkService.rewardsToday(id) + "/" + cfg.maxRewardsPerDay + " §7rewards.");
		}
		boolean inside = AfkService.isInside(p, zone, cfg.heightPadding);
		if (inside && AfkService.limitReached(id, cfg)) {
			Msg.info(src, "You're in the zone, but you've hit your daily limit – more tomorrow.");
		} else if (inside && AfkService.ipBlockedNow(id)) {
			Msg.info(src, "You're in the zone, but another account on your IP is already collecting.");
		} else if (inside) {
			int remaining = AfkService.remaining(p, cfg);
			Msg.success(src, "You're in the zone – next reward in §e"
				+ TimeUtil.formatDuration((remaining < 0 ? cfg.intervalSeconds : remaining) * 1000L) + "§a.");
		} else {
			Msg.info(src, "You're not in the zone.");
		}
		return 1;
	}

	private static String rewardText(AfkConfig cfg) {
		StringBuilder sb = new StringBuilder("Every §e").append(TimeUtil.formatDuration(cfg.intervalSeconds * 1000L)).append(" §7in the zone: ");
		if (cfg.rewardCents > 0) sb.append(Msg.moneyStr(cfg.rewardCents));
		boolean keys = cfg.keyChancePercent > 0 && CrateKeys.enabled();
		if (keys) {
			if (cfg.rewardCents > 0) sb.append(" §7+ ");
			String tier = CrateKeys.label(cfg.keyTier);
			sb.append("§e").append(percent(cfg.keyChancePercent)).append("% §7chance of ")
				.append(!tier.isEmpty() && "AEIOUaeiou".indexOf(tier.charAt(0)) >= 0 ? "an" : "a").append(" §e").append(tier).append(" Key");
		}
		if (cfg.rewardCents <= 0 && !keys) sb.append("§7nothing");
		return sb.toString();
	}

	private static int setCorner(CommandSourceStack src, boolean first, String dimension, BlockPos pos) {
		if (!AfkConfig.validCoordinates(pos.getX(), pos.getY(), pos.getZ())) {
			Msg.error(src, "Coordinates are outside the world.");
			return 0;
		}
		if (!writable(src)) return 0;
		AfkConfig cfg = AfkFeature.CONFIG.get();
		AfkConfig.Corner corner = new AfkConfig.Corner(AfkConfig.normalizeDimension(dimension), pos.getX(), pos.getY(), pos.getZ());
		if (first) cfg.pos1 = corner;
		else cfg.pos2 = corner;
		save(src);
		String name = first ? "Pos1" : "Pos2";
		Msg.success(src, name + " set: §f" + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " §7(" + dimensionLabel(corner.dimension) + ")");
		AfkZone zone = cfg.zone();
		if (zone != null) {
			Msg.success(src, "AFK zone active: §f" + zone.sizeLabel() + " §ablocks §7(+" + cfg.heightPadding + " height for jumping)");
			if (zone.sizeX() * zone.sizeZ() > LARGE_AREA) {
				Msg.info(src, "§eNote: the zone is very large (" + Msg.number(zone.sizeX() * zone.sizeZ()) + " blocks of floor area).");
			}
		} else if (cfg.pos1 != null && cfg.pos2 != null) {
			Msg.error(src, "Pos1 and Pos2 are in different dimensions – no zone active.");
		} else {
			Msg.info(src, "Now set §e/afkzone " + (first ? "pos2" : "pos1") + "§7.");
		}
		return 1;
	}

	private static int clear(CommandContext<CommandSourceStack> ctx) {
		if (!writable(ctx.getSource())) return 0;
		AfkConfig cfg = AfkFeature.CONFIG.get();
		boolean had = cfg.pos1 != null || cfg.pos2 != null;
		cfg.pos1 = null;
		cfg.pos2 = null;
		save(ctx.getSource());
		if (had) Msg.success(ctx.getSource(), "AFK zone removed.");
		else Msg.info(ctx.getSource(), "No AFK zone was set.");
		return 1;
	}

	private static int info(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		AfkConfig cfg = AfkFeature.CONFIG.get();
		AfkZone zone = cfg.zone();
		AfkConfig.FileCheck file = AfkConfig.checkFile(AfkFeature.CONFIG.path(), cfg);
		if (!file.writable()) Msg.error(src, fileProblem(file) + " Until then, changes via /afkzone are blocked.");
		Msg.info(src, "§eAFK zone §8– §7Pos1: §f" + cornerLabel(cfg.pos1) + " §8| §7Pos2: §f" + cornerLabel(cfg.pos2));
		if (zone != null) {
			Msg.info(src, "Active: §f" + zone.sizeLabel() + " §7blocks (+" + cfg.heightPadding + " height), " + dimensionLabel(zone.dimension()));
		} else {
			Msg.info(src, "§cNo zone active§7 (" + (cfg.pos1 != null && cfg.pos2 != null ? "different dimensions" : "Pos1/Pos2 missing") + ")");
		}
		Msg.info(src, rewardText(cfg));
		Msg.info(src, "Daily limit: " + (cfg.maxRewardsPerDay > 0 ? "§f" + cfg.maxRewardsPerDay + " §7per player" : "§cnone (0 = unlimited)")
			+ " §8| §7Per IP: §f" + (cfg.maxAccountsPerIp > 0 ? cfg.maxAccountsPerIp + " account(s)" : "off")
			+ " §8| §7Money from: §f" + (cfg.payFromTreasury ? "Treasury (capped)" : "new money (like /daily)"));
		List<String> inside = new ArrayList<>();
		var server = src.getServer();
		for (UUID id : AfkService.TRACKER.ids()) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			int seconds = p == null ? -1 : AfkService.TRACKER.seconds(id, p);
			if (seconds < 0) continue; // offline or stale connection
			inside.add(p.getGameProfile().name() + " (" + TimeUtil.formatDuration(seconds * 1000L) + ")");
		}
		Msg.info(src, "In the zone: §f" + (inside.isEmpty() ? "nobody" : String.join("§7, §f", inside)));
		Msg.send(src, Msg.legacy("§8afk active=" + (zone != null) + " pos1=" + cornerKey(cfg.pos1) + " pos2=" + cornerKey(cfg.pos2)
			+ " interval=" + cfg.intervalSeconds + " reward=" + cfg.rewardCents + " keychance=" + percent(cfg.keyChancePercent)
			+ " daylimit=" + cfg.maxRewardsPerDay + " treasury=" + cfg.payFromTreasury + " padding=" + cfg.heightPadding
			+ " inside=" + inside.size() + " ipmax=" + cfg.maxAccountsPerIp));
		return 1;
	}

	private static int status(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack src = ctx.getSource();
		UUID id = Args.knownPlayer(ctx, "player");
		AfkConfig cfg = AfkFeature.CONFIG.get();
		String name = EconomyService.nameOrUnknown(id);
		ServerPlayer player = src.getServer().getPlayerList().getPlayer(id);
		boolean online = player != null;
		// only the current connection's session counts; after a reconnect the old one is removed on the next tick
		int seconds = player == null ? -1 : AfkService.TRACKER.seconds(id, player);
		boolean inside = seconds >= 0;
		long today = AfkService.rewardsToday(id);
		long total = PlayerDataService.getCounter(id, AfkService.COUNTER_REWARDS);
		long earned = PlayerDataService.getCounter(id, AfkService.COUNTER_EARNED);
		long keys = CrateKeys.count(id, cfg.keyTier);
		Msg.info(src, "§e" + name + "§7: " + (inside ? "in the zone for §f" + TimeUtil.formatDuration(seconds * 1000L) + " §7(interval)"
			: online ? "not in the zone" : "offline") + " §8| §7today §f" + today + " §8| §7total §f" + total + " §7(" + Msg.moneyStr(earned) + ")");
		Msg.send(src, Msg.legacy("§8afk status name=" + name + " online=" + online + " inside=" + inside + " seconds=" + Math.max(0, seconds)
			+ " interval=" + cfg.intervalSeconds + " today=" + today + " rewards=" + total + " earned=" + earned + " keys=" + keys
			+ " balance=" + EconomyService.balance(id)));
		return 1;
	}

	private static int setInterval(CommandContext<CommandSourceStack> ctx) {
		int seconds = IntegerArgumentType.getInteger(ctx, "seconds");
		if (!writable(ctx.getSource())) return 0;
		AfkFeature.CONFIG.get().intervalSeconds = seconds;
		save(ctx.getSource());
		Msg.success(ctx.getSource(), "AFK interval: §f" + TimeUtil.formatDuration(seconds * 1000L));
		return 1;
	}

	private static int setReward(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		long cents = Args.moneyAllowZero(ctx, "amount");
		if (!writable(ctx.getSource())) return 0;
		AfkFeature.CONFIG.get().rewardCents = cents;
		save(ctx.getSource());
		Msg.success(ctx.getSource(), "Reward per interval: " + Msg.moneyStr(cents));
		return 1;
	}

	private static int setKeyChance(CommandContext<CommandSourceStack> ctx) {
		double pct = DoubleArgumentType.getDouble(ctx, "percent");
		if (!writable(ctx.getSource())) return 0;
		AfkFeature.CONFIG.get().keyChancePercent = pct;
		save(ctx.getSource());
		Msg.success(ctx.getSource(), "Key chance: §f" + percent(pct) + "%");
		return 1;
	}

	private static int setDayLimit(CommandContext<CommandSourceStack> ctx) {
		int count = IntegerArgumentType.getInteger(ctx, "count");
		if (!writable(ctx.getSource())) return 0;
		AfkFeature.CONFIG.get().maxRewardsPerDay = count;
		save(ctx.getSource());
		Msg.success(ctx.getSource(), "Daily limit: §f" + (count > 0 ? count + " rewards" : "none (unlimited)"));
		if (count == 0) Msg.info(ctx.getSource(), "§eWarning: without a daily limit the AFK zone is an unlimited money source.");
		return 1;
	}

	private static int setIpLimit(CommandContext<CommandSourceStack> ctx) {
		int count = IntegerArgumentType.getInteger(ctx, "count");
		if (!writable(ctx.getSource())) return 0;
		AfkFeature.CONFIG.get().maxAccountsPerIp = count;
		save(ctx.getSource());
		if (count > 0) {
			Msg.success(ctx.getSource(), "Max accounts per IP in the AFK zone: §f" + count);
			Msg.info(ctx.getSource(), "§eNote: behind a tunnel/proxy all players share the same IP.");
		} else {
			Msg.success(ctx.getSource(), "AFK zone IP rule: §foff");
		}
		return 1;
	}

	/**
	 * afk.json must be missing or match the active state before any change. If it is broken (code defaults are
	 * running) or hand-edited and not reloaded, {@link #save} would overwrite it with the in-memory state and lose
	 * the zone and values, so change nothing, not even in memory.
	 */
	private static boolean writable(CommandSourceStack src) {
		AfkConfig.FileCheck file = AfkConfig.checkFile(AfkFeature.CONFIG.path(), AfkFeature.CONFIG.get());
		if (file.writable()) return true;
		Msg.error(src, fileProblem(file) + " Nothing changed.");
		return false;
	}

	private static String fileProblem(AfkConfig.FileCheck file) {
		return switch (file.state()) {
			case BROKEN -> "afk.json is broken (" + file.detail() + ") – fix it first, then §e/smpeco reload§c.";
			case UNREADABLE -> "afk.json can't be read (" + file.detail() + ").";
			case DIFFERENT -> "afk.json differs from the active settings (edited by hand or not saved) – run §e/smpeco reload§c first.";
			case MISSING, SAME -> "afk.json is fine.";
		};
	}

	/** On failure the change only lasts until restart, and the admin gets a warning. */
	private static void save(CommandSourceStack src) {
		try {
			AfkFeature.CONFIG.save();
		} catch (IOException e) {
			Msg.error(src, "Couldn't save afk.json (" + e.getMessage() + ") – the change only lasts until the next restart.");
		}
	}

	static String dimensionLabel(String dimension) {
		return switch (dimension) {
			case "minecraft:overworld" -> "Overworld";
			case "minecraft:the_nether" -> "Nether";
			case "minecraft:the_end" -> "End";
			default -> dimension;
		};
	}

	private static String cornerLabel(AfkConfig.Corner c) {
		return c == null ? "§cnot set" : c.x + " " + c.y + " " + c.z + " §7(" + dimensionLabel(c.dimension) + ")";
	}

	private static String cornerKey(AfkConfig.Corner c) {
		return c == null ? "none" : c.toString();
	}

	static String percent(double pct) {
		return BigDecimal.valueOf(pct).stripTrailingZeros().toPlainString();
	}
}
