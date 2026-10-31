package dev.smpeconomy.features.hud;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import dev.smpeconomy.core.Args;
import dev.smpeconomy.core.CommandRegistry;
import dev.smpeconomy.core.ConfigManager;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Feature;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Perms;
import dev.smpeconomy.core.PriceModifiers;
import dev.smpeconomy.core.Scheduler;
import dev.smpeconomy.core.Settings;
import dev.smpeconomy.features.settings.SettingsLogic;

/**
 * Per-player sidebar (packets only, with an unregistered objective) and tablist header and footer. Lines are
 * configurable through hud.json "lines"; job and mission lines drop out without {@code Services.JobLevels} and
 * {@code Services.Missions}. {@code /sidebar} toggles {@link Settings#SIDEBAR}, the same switch as the settings GUI.
 */
public final class HudFeature implements Feature {
	public static final String NODE = "smpeconomy.sidebar";
	/** Required in addition to Perms.ADMIN. */
	static final String NODE_ADMIN = "smpeconomy.admin.hud";
	static final ConfigManager.Config<HudConfig> CONFIG = ConfigManager.register("hud", HudConfig.class, HudConfig::new, HudConfig::validate);

	private static Scheduler.Task task;

	@Override
	public String id() {
		return "hud";
	}

	@Override
	public void register() {
		CommandRegistry.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(Commands.literal("sidebar")
				.requires(Perms.require(NODE))
				.executes(ctx -> sidebarCommand(ctx, null))
				// Only suggest on/off; parseOnOff also accepts true/false and the like.
				.then(Commands.argument("state", StringArgumentType.word())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(List.of("on", "off"), builder))
					.executes(HudFeature::sidebarStateCommand)));
			dispatcher.register(Commands.literal("smpeco")
				.requires(Perms.requireAdmin(Perms.ADMIN))
				.then(Commands.literal("hud")
					.requires(Perms.requireAdmin(NODE_ADMIN))
					.then(Commands.argument("player", StringArgumentType.word()).suggests(Args.knownPlayers())
						.executes(HudFeature::diagnose))));
		});

		// Send at the end of the tick, after vanilla's scoreboard packets.
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> HudManager.onJoin(handler.getPlayer()));
		Settings.addListener((player, key, value) -> {
			if (Settings.SIDEBAR.equals(key)) HudManager.markDirty(player);
		});
		EconomyService.addListener((player, oldBalance, newBalance) -> HudManager.markDirty(player));
		PriceModifiers.addListener(HudManager::markAllDirty);

		HudSelfTests.register();
	}

	@Override
	public void onServerStarted(MinecraftServer server) {
		HudManager.reset();
		HudManager.logTablistOwner(CONFIG.get());
		if (task != null) task.cancel();
		task = Scheduler.runRepeating(1, 1, () -> HudManager.tick(server));
	}

	@Override
	public void onServerStopping(MinecraftServer server) {
		if (task != null) task.cancel();
		task = null;
		HudManager.reset();
	}

	public static boolean isSidebarEnabled(UUID player) {
		return Settings.get(player, Settings.SIDEBAR);
	}

	/** The display follows on the next tick through the settings listener. */
	public static void setSidebarEnabled(UUID player, boolean enabled) {
		Settings.set(player, Settings.SIDEBAR, enabled);
	}

	public static void onVanillaSidebarChanged() {
		HudManager.onVanillaSidebarChanged();
	}

	private static int sidebarStateCommand(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		Optional<Boolean> wanted = SettingsLogic.parseOnOff(StringArgumentType.getString(ctx, "state"));
		if (wanted.isEmpty()) {
			Msg.error(ctx.getSource(), "Please use §fon §cor §foff§c.");
			return 0;
		}
		return sidebarCommand(ctx, wanted.get());
	}

	private static int sidebarCommand(CommandContext<CommandSourceStack> ctx, Boolean wanted) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		UUID id = player.getUUID();
		boolean current = isSidebarEnabled(id);
		boolean target = wanted == null ? !current : wanted;
		if (wanted != null && current == target) {
			Msg.info(player, "The sidebar is already " + (target ? "§aon" : "§coff") + "§7.");
			return 1;
		}
		setSidebarEnabled(id, target);
		if (target) {
			Msg.success(player, "Sidebar turned on.");
			if (!CONFIG.get().sidebar) Msg.info(player, "§8Note: the sidebar is currently disabled on this server.");
		} else {
			Msg.info(player, "Sidebar turned off. §8(/sidebar on)");
		}
		return 1;
	}

	/** Diagnostics for admins and e2e scripts. */
	private static int diagnose(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		String name = StringArgumentType.getString(ctx, "player");
		MinecraftServer server = src.getServer();
		ServerPlayer online = server.getPlayerList().getPlayerByName(name);
		UUID id = online != null ? online.getUUID() : EconomyService.findByName(name).orElse(null);
		if (id == null) {
			Msg.error(src, "Unknown player: " + name);
			return 0;
		}
		String setting = isSidebarEnabled(id) ? "on" : "off";
		if (online == null) {
			Msg.send(src, Msg.legacy("§7hud " + name + " setting=" + setting + " online=false shown=false"));
			return 1;
		}
		Optional<HudManager.State> state = HudManager.state(id);
		boolean shown = state.map(s -> s.shown).orElse(false);
		Map<HudLines.Line, String> lines = shown ? state.get().lines
			: HudLines.sidebar(HudManager.collect(online, HudManager.forViewer(online, HudManager.shared(server))), CONFIG.get());
		// One message with line breaks: RCON joins separate messages without a separator.
		StringBuilder out = new StringBuilder("§7hud " + online.getGameProfile().name() + " setting=" + setting + " online=true shown=" + shown
			+ " lines=" + lines.size() + (shown ? "" : " §8(preview)"));
		java.util.EnumMap<HudLines.Line, String> sorted = new java.util.EnumMap<>(HudLines.Line.class);
		sorted.putAll(lines);
		int i = 0;
		for (Map.Entry<HudLines.Line, String> e : sorted.entrySet()) {
			out.append("\n§8 ").append(++i).append(" ").append(e.getKey().configKey).append(": §r").append(e.getValue());
		}
		state.filter(s -> s.footer != null).ifPresent(s -> out.append("\n§7tab footer=§r").append(s.footer.replace("\n", " §8/ §r")));
		Msg.send(src, Msg.legacy(out.toString()));
		return 1;
	}
}
