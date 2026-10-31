package dev.smpeconomy.features.casino.games.plinko;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.random.RandomGenerator;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import dev.smpeconomy.core.Args;
import dev.smpeconomy.core.CommandRegistry;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Perms;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.features.casino.Casino;
import dev.smpeconomy.features.casino.CasinoData;
import dev.smpeconomy.features.casino.games.plinko.PlinkoMath.Risk;

/**
 * Maths in {@link PlinkoMath}. The result is drawn and stored on drop, the animation only shows it. Money goes through
 * {@link Casino#startInstant} and {@link Casino#settle}.
 */
public final class Plinko {
	/** Game id in casino.json and stats. */
	public static final String GAME_ID = "plinko";
	public static final String LABEL = "§ePlinko";
	/** Casino registration; maximum payout across all risk levels (admission uses the chosen level's). */
	static final Casino.InstantGame GAME = new Casino.InstantGame(GAME_ID, LABEL, Items.HOPPER, Plinko::potentialAnyRisk);
	/** Same permission as the other casino games. */
	static final String NODE = "smpeconomy.casino";
	/** Last chosen risk level (player data, feature prefix). */
	static final String RISK_KEY = "casino.plinko.risk";
	static final String USAGE = "/plinko <bet> [low|medium|high]";

	private Plinko() {}

	public static void register() {
		Casino.registerGame(GAME);
		CommandRegistry.register((dispatcher, registryAccess, environment) -> register(dispatcher));
		PlinkoSelfTests.register();
	}

	/** Highest possible payout at this stake and risk. */
	static long potential(long stake, Risk risk) {
		return PlinkoMath.potential(stake, risk);
	}

	/** Highest possible payout at this stake across all risk levels. */
	static long potentialAnyRisk(long stake) {
		long max = 0;
		for (Risk r : Risk.values()) max = Math.max(max, potential(stake, r));
		return max;
	}

	/**
	 * Drops a ball: admits the round through the casino, takes the stake, draws the path, fixes and saves the payout
	 * (all before the animation). The path is then in {@code round.reels} (8 × 0/1).
	 */
	static Casino.Start drop(UUID player, String name, long stake, Risk risk, RandomGenerator rng) {
		return Casino.startInstant(player, name, GAME, stake, s -> potential(s, risk), round -> {
			int[] path = PlinkoMath.path(rng);
			round.reels = path;
			return PlinkoMath.payout(round.stake, risk, PlinkoMath.bucket(path));
		});
	}

	/** Highest stake allowed right now (0 = locked: Treasury, limit or casino closed). */
	static long maxStake(UUID player, Risk risk) {
		return Casino.maxStake(player, s -> potential(s, risk));
	}

	/** Last chosen risk level (default medium). */
	static Risk lastRisk(UUID player) {
		return Risk.byId(PlayerDataService.getString(player, RISK_KEY), Risk.MEDIUM);
	}

	static void rememberRisk(UUID player, Risk risk) {
		if (risk != lastRisk(player)) PlayerDataService.setString(player, RISK_KEY, risk.id());
	}

	/** Last stake at plinko (default $100). */
	static long lastStake(UUID player) {
		CasinoData.PlayerStats s = Casino.peekStats(player);
		Long last = s == null ? null : s.lastStake.get(GAME_ID);
		long v = last != null && last > 0 ? last : 10_000;
		return Math.max(Casino.cfg().minBet, v);
	}

	private static void register(CommandDispatcher<CommandSourceStack> d) {
		d.register(Commands.literal("plinko").requires(Perms.require(NODE))
			.executes(ctx -> open(ctx, null, null))
			.then(Commands.literal("help").executes(Plinko::help))
			.then(Commands.argument("bet", StringArgumentType.word())
				.executes(ctx -> open(ctx, Args.money(ctx, "bet"), null))
				.then(Commands.argument("risk", StringArgumentType.word())
					.suggests((ctx, b) -> SharedSuggestionProvider.suggest(List.of("low", "medium", "high"), b))
					.executes(ctx -> {
						long stake = Args.money(ctx, "bet");
						String raw = StringArgumentType.getString(ctx, "risk");
						Risk risk = Risk.parse(raw);
						if (risk == null) {
							Msg.error(ctx.getSource(), "Unknown risk level §f" + raw + "§c – use §flow§c, §fmedium §cor §fhigh§c.");
							return 0;
						}
						return open(ctx, stake, risk);
					}))));
	}

	private static int open(CommandContext<CommandSourceStack> ctx, Long stake, Risk risk) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		if (!Gui.canOpen(p)) return 0;
		PlinkoGui.open(p, stake, risk);
		return 1;
	}

	private static int help(CommandContext<CommandSourceStack> ctx) {
		Msg.info(ctx.getSource(), helpLine());
		return 1;
	}

	/** One help line (chat). */
	static String helpLine() {
		return LABEL + "§7: drop a ball through 8 rows of pegs into a multiplier slot. §f" + USAGE
			+ " §8(low up to " + PlinkoMath.formatHundredths(PlinkoMath.maxHundredths(Risk.LOW))
			+ ", medium " + PlinkoMath.formatHundredths(PlinkoMath.maxHundredths(Risk.MEDIUM))
			+ ", high " + PlinkoMath.formatHundredths(PlinkoMath.maxHundredths(Risk.HIGH)) + "; RTP ≈ 96 %)";
	}

	/** Button for the casino menu ({@code /casino}): opens plinko. */
	public static GuiElementBuilder hubButton(ServerPlayer player) {
		List<String> lore = new ArrayList<>(List.of(
			"§7Drop a ball through the pegs –",
			"§7it lands on a multiplier, up to §a" + PlinkoMath.formatHundredths(PlinkoMath.maxHundredths(Risk.HIGH)) + "§7.",
			"§7Risk: §aLow §8· §eMedium §8· §cHigh",
			"§8RTP ≈ 96 %",
			"§8" + USAGE));
		long max = maxStake(player.getUUID(), lastRisk(player.getUUID()));
		lore.add(max > 0 ? "§7Max bet: " + Msg.moneyStr(max) : "§cLocked right now (treasury/limit)");
		lore.add("");
		lore.add("§eClick to play");
		return Gui.button(Items.HOPPER, LABEL, () -> PlinkoGui.open(player, null, null), lore.toArray(String[]::new));
	}
}
