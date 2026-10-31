package dev.smpeconomy.features.casino.games.dice;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import java.util.UUID;
import java.util.random.RandomGenerator;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import dev.smpeconomy.core.Args;
import dev.smpeconomy.core.CommandRegistry;
import dev.smpeconomy.core.ConfigManager;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Perms;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.features.casino.Casino;

/**
 * Roll 1-100, the player picks target and direction; payout 99 / chance × (1 - edge). Stake, admission and settlement go
 * through {@link Casino#startInstant} and {@link Casino#settle}.
 */
public final class DiceGame {
	/** Game id in casino.json (rounds, stats, daily limit). */
	public static final String GAME_ID = "dice";
	public static final String LABEL = "§eDice";
	public static final Item ICON = Items.QUARTZ_BLOCK;
	static final String NODE = "smpeconomy.casino";
	/** Last chosen bet per player ("over:50") in the player data key/value store. */
	static final String KEY_LAST_BET = "casino.dice.bet";

	/** Casino registration; maximum payout across all bets = smallest win chance (admission uses the chosen bet). */
	static final Casino.InstantGame GAME = new Casino.InstantGame(GAME_ID, LABEL, ICON,
		stake -> DiceMath.payout(stake, DiceMath.MIN_CHANCE, DiceGame.cfg().edgeBp()));

	static final ConfigManager.Config<DiceConfig> CONFIG = ConfigManager.register("casino_dice", DiceConfig.class, DiceConfig::new, DiceConfig::validate);

	private DiceGame() {}

	public static void register() {
		CONFIG.get();
		Casino.registerGame(GAME);
		CommandRegistry.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher));
		DiceSelfTests.register();
	}

	static DiceConfig cfg() {
		return CONFIG.get();
	}

	record Bet(boolean over, int target) {
		int chance() {
			return DiceMath.chance(over, target);
		}

		String text() {
			return (over ? "over " : "under ") + target;
		}

		/** "§aOVER 50" / "§cUNDER 25". */
		String colored() {
			return (over ? "§aOVER " : "§cUNDER ") + target;
		}

		String encode() {
			return (over ? "over:" : "under:") + target;
		}

		static Bet decode(String s) {
			if (s == null) return null;
			String[] p = s.split(":", 2);
			if (p.length != 2) return null;
			try {
				boolean over = p[0].equals("over");
				int t = Integer.parseInt(p[1]);
				return DiceMath.valid(over, t) ? new Bet(over, t) : null;
			} catch (NumberFormatException e) {
				return null;
			}
		}
	}

	static final Bet DEFAULT_BET = new Bet(true, 50);

	/** Result data of a round in {@code Round.reels}: {roll, target, over 1/0, chance, edge bp}. */
	static final int R_ROLL = 0;
	static final int R_TARGET = 1;
	static final int R_OVER = 2;
	static final int R_CHANCE = 3;
	static final int R_EDGE = 4;

	/** Maximum payout (= win) per stake for this bet, for admission, coverage and max stake. */
	static long potential(long stake, Bet bet, int edgeBp) {
		return DiceMath.payout(stake, bet.chance(), edgeBp);
	}

	/** Highest stake this bet would admit right now (ignoring balance), 0 = locked. */
	static long maxStake(UUID player, Bet bet) {
		int edgeBp = cfg().edgeBp();
		return Casino.maxStake(player, s -> potential(s, bet, edgeBp));
	}

	/**
	 * Rolls: takes the stake (through the casino, all checks there), determines and saves roll and payout; only then
	 * does the GUI animate. Settlement through {@link Casino#settle}.
	 */
	static Casino.Start roll(UUID player, String name, long stake, Bet bet, RandomGenerator rng) {
		if (!cfg().enabled) return new Casino.Start(null, "Dice is closed right now.");
		if (bet == null || !DiceMath.valid(bet.over(), bet.target())) return new Casino.Start(null, rangeHint(bet == null || bet.over()));
		int edgeBp = cfg().edgeBp();
		int chance = bet.chance();
		return Casino.startInstant(player, name, GAME, stake, s -> potential(s, bet, edgeBp), r -> {
			int roll = DiceMath.roll(rng);
			r.reels = new int[] {roll, bet.target(), bet.over() ? 1 : 0, chance, edgeBp};
			return DiceMath.wins(roll, bet.over(), bet.target()) ? DiceMath.payout(r.stake, chance, edgeBp) : 0;
		});
	}

	static String rangeHint(boolean over) {
		return over ? "Roll over: pick a number from " + DiceMath.minTarget(true) + " to " + DiceMath.maxTarget(true) + "."
			: "Roll under: pick a number from " + DiceMath.minTarget(false) + " to " + DiceMath.maxTarget(false) + ".";
	}

	static Bet lastBet(UUID player) {
		Bet b = Bet.decode(PlayerDataService.getString(player, KEY_LAST_BET));
		return b == null ? DEFAULT_BET : b;
	}

	static void rememberBet(UUID player, Bet bet) {
		if (!bet.encode().equals(PlayerDataService.getString(player, KEY_LAST_BET))) PlayerDataService.setString(player, KEY_LAST_BET, bet.encode());
	}

	public static GuiElementBuilder hubButton(ServerPlayer player) {
		int edgeBp = cfg().edgeBp();
		long max = maxStake(player.getUUID(), lastBet(player.getUUID()));
		return Gui.button(ICON, LABEL, () -> DiceGui.open(player, null, null, false),
			"§7Roll 1–100: guess §aover §7or §cunder §7your number.",
			"§7Up to §a" + DiceMath.formatMultiplier(DiceMath.MIN_CHANCE, edgeBp) + "§7, RTP " + DiceMath.formatPercent(DiceMath.rtp(edgeBp)) + ".",
			"§8/dice <bet> <over|under> <number>",
			!cfg().enabled ? "§cClosed right now" : max > 0 ? "§7Max. stake: " + Msg.moneyStr(max) : "§cLocked right now (Treasury/limit)",
			"",
			"§eClick to play");
	}

	private static void registerCommands(CommandDispatcher<CommandSourceStack> d) {
		d.register(Commands.literal("dice").requires(Perms.require(NODE))
			.executes(ctx -> open(ctx, null, null))
			.then(Commands.literal("help").executes(DiceGame::help))
			.then(Commands.argument("bet", StringArgumentType.word())
				.executes(ctx -> open(ctx, Args.money(ctx, "bet"), null))
				.then(Commands.literal("over")
					.then(Commands.argument("number", IntegerArgumentType.integer(DiceMath.MIN_ROLL, DiceMath.MAX_ROLL))
						.executes(ctx -> open(ctx, Args.money(ctx, "bet"), true))))
				.then(Commands.literal("under")
					.then(Commands.argument("number", IntegerArgumentType.integer(DiceMath.MIN_ROLL, DiceMath.MAX_ROLL))
						.executes(ctx -> open(ctx, Args.money(ctx, "bet"), false))))));
	}

	private static int open(CommandContext<CommandSourceStack> ctx, Long stake, Boolean over) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		Bet bet = null;
		if (over != null) {
			int target = IntegerArgumentType.getInteger(ctx, "number");
			if (!DiceMath.valid(over, target)) {
				Msg.error(p, rangeHint(over));
				helpLine(p);
				return 0;
			}
			bet = new Bet(over, target);
		}
		if (!Gui.canOpen(p)) return 0;
		DiceGui.open(p, stake, bet, bet != null);
		return 1;
	}

	private static int help(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		int edgeBp = cfg().edgeBp();
		Msg.info(p, LABEL + " §7– roll a number from 1 to 100.");
		Msg.info(p, "§a/dice <bet> over <N> §7wins if the roll is above N, §c/dice <bet> under <N> §7if it is below N.");
		Msg.info(p, "§7Payout = 99 ÷ win chance × " + String.format(java.util.Locale.ROOT, "%.2f", (10_000 - edgeBp) / 10_000.0)
			+ " §8(RTP " + DiceMath.formatPercent(DiceMath.rtp(edgeBp)) + ")§7. Example: §e/dice 100 over 50 §7→ 50 % for "
			+ DiceMath.formatMultiplier(50, edgeBp) + ".");
		helpLine(p);
		return 1;
	}

	/** Short help line with click to open. */
	static void helpLine(ServerPlayer p) {
		Msg.send(p, Msg.prefixed(Msg.legacy("§7Usage: §e/dice <bet> <over|under> <number> §8· ")
			.append(Msg.clickRun("§a[open table]", "/dice", "§7Open the dice table"))));
	}
}
