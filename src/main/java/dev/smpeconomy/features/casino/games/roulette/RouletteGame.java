package dev.smpeconomy.features.casino.games.roulette;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.math.BigInteger;
import java.util.UUID;
import java.util.random.RandomGenerator;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import dev.smpeconomy.core.CommandRegistry;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Perms;
import dev.smpeconomy.core.test.TestPlayers;
import dev.smpeconomy.features.casino.Casino;
import dev.smpeconomy.features.casino.CasinoConfig;

/**
 * European roulette, one zero. On a spin the whole table plays as one stake through {@link Casino#startInstant}, with the
 * result saved before the animation, and settles through {@link Casino#settle} at the end of the animation or on close,
 * disconnect or stop.
 */
public final class RouletteGame {
	/** Game id in casino.json and stats. */
	public static final String GAME_ID = "roulette";
	static final String LABEL = "§4Roulette";
	/**
	 * Casino registration. Maximum payout across all tables = everything on one number (×36 − 1 %); a spin is admitted
	 * with the maximum payout of ITS table ({@link #play}).
	 */
	static final Casino.InstantGame GAME = new Casino.InstantGame(GAME_ID, LABEL, Items.TARGET,
		stake -> RouletteMath.afterFee(Math.multiplyExact(stake, 36L)));
	static final String NODE = "smpeconomy.casino";
	/** Remembered numbers per player (display only, not persisted). */
	static final int HISTORY_SIZE = 9;

	private static final Map<UUID, Deque<Integer>> HISTORY = new HashMap<>();
	/** Last placed table and chip per player, for reopening (no money, not persisted). */
	private static final Map<UUID, long[]> LAST_TABLE = new HashMap<>();
	private static final Map<UUID, Integer> LAST_CHIP = new HashMap<>();
	private static final Set<UUID> HINT_SHOWN = new HashSet<>();

	private RouletteGame() {}

	public static void register() {
		Casino.registerGame(GAME);
		CommandRegistry.register((dispatcher, registryAccess, environment) -> dispatcher.register(
			Commands.literal("roulette").requires(Perms.require(NODE))
				.executes(RouletteGame::openCommand)
				.then(Commands.literal("help").executes(RouletteGame::helpCommand))));
		TestPlayers.onCleanup(RouletteGame::forget);
		RouletteSelfTests.register();
	}

	/** Opens the roulette table (e.g. from the /casino menu). */
	public static void open(ServerPlayer player) {
		if (!Gui.canOpen(player)) return;
		RouletteGui gui = new RouletteGui(player);
		if (!gui.open()) return;
		if (HINT_SHOWN.add(player.getUUID())) Msg.info(player, helpLine());
	}

	public static GuiElementBuilder hubButton(ServerPlayer player) {
		long max = maxTotal(player.getUUID());
		List<String> lore = new ArrayList<>(List.of(
			"§7European roulette with a single zero.",
			"§7Place chips on numbers §e(35:1)§7, dozens,",
			"§7columns §e(2:1) §7or Red/Black, Even/Odd,",
			"§71–18/19–36 §e(1:1)§7. 1 % fee on payouts.",
			"§8RTP " + RouletteMath.RTP_TEXT + " · /roulette"));
		lore.add(max > 0 ? "§7Max. total bet: " + Msg.moneyStr(max) : "§cLocked right now (treasury/limit)");
		lore.add("");
		lore.add("§eClick to play");
		return Gui.button(Items.TARGET, LABEL, () -> open(player), lore.toArray(String[]::new));
	}

	private static int openCommand(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer p = ctx.getSource().getPlayerOrException();
		if (!Gui.canOpen(p)) return 0;
		open(p);
		return 1;
	}

	private static int helpCommand(CommandContext<CommandSourceStack> ctx) {
		Msg.info(ctx.getSource(), helpLine());
		Msg.info(ctx.getSource(), "§7Payouts: number §e35:1§7, dozen/column §e2:1§7, Red/Black, Even/Odd, 1–18/19–36 §e1:1§7. "
			+ "Zero loses all outside bets. The house keeps 1 % of every payout. §8RTP " + RouletteMath.RTP_TEXT);
		return 1;
	}

	static String helpLine() {
		return LABEL + " §8» §7Pick a chip, §eleft-click §7fields to place it, §eright-click §7to take it back, then hit §a§lSPIN§7. "
			+ "Chips stay on the table for the next spin. §8/roulette help";
	}

	/** Result of {@link #play}: started (already decided) round + number drawn, or an error message. */
	record Spin(Casino.Start start, int pocket) {
		boolean ok() {
			return start.ok();
		}

		String error() {
			return start.error();
		}

		static Spin fail(String error) {
			return new Spin(new Casino.Start(null, error), -1);
		}
	}

	/**
	 * Plays the whole table as one round: takes the total stake, throws the ball, fixes and saves the payout, all
	 * before the GUI animates. Settlement afterwards through {@link Casino#settle}.
	 */
	static Spin play(UUID player, String name, long[] bets, RandomGenerator rng) {
		if (!RouletteMath.valid(bets)) return Spin.fail("Invalid table.");
		long[] table = bets.clone();
		long total;
		long max;
		try {
			total = RouletteMath.total(table);
			max = RouletteMath.maxPayout(table);
		} catch (ArithmeticException e) {
			return Spin.fail("Your bets are too large.");
		}
		if (total <= 0) return Spin.fail("Place at least one chip first (left-click a field).");
		int[] pocket = {-1};
		long tableTotal = total;
		long tableMax = max;
		// maximum payout of this table, scaled for other total stakes (hint "Max bet" when coverage is short)
		Casino.Start s = Casino.startInstant(player, name, GAME, total, stake -> stake == tableTotal ? tableMax
			: BigInteger.valueOf(tableMax).multiply(BigInteger.valueOf(stake)).divide(BigInteger.valueOf(tableTotal)).longValueExact(), r -> {
			int n = RouletteMath.spin(rng);
			pocket[0] = n;
			return RouletteMath.payout(table, n);
		});
		return new Spin(s, s.ok() ? pocket[0] : -1);
	}

	/**
	 * Pre-check when placing a chip (display only; {@link Casino#startInstant} does the binding check on the spin).
	 * null = table allowed, otherwise a message.
	 */
	static String tableProblem(UUID player, long[] bets) {
		CasinoConfig c = Casino.cfg();
		if (!c.enabled) return "The casino is closed right now.";
		long total;
		long max;
		try {
			total = RouletteMath.total(bets);
			max = RouletteMath.maxPayout(bets);
		} catch (ArithmeticException e) {
			return "Your bets are too large.";
		}
		long maxBet = Casino.maxBet();
		if (maxBet < c.minBet) return "The casino is locked: the treasury is too small.";
		if (total > maxBet) return "Table limit: at most " + Msg.moneyStr(maxBet) + " §cin total per spin.";
		long room = Casino.lossRoom(player);
		if (total > room) return "Daily loss limit: you can bet at most " + Msg.moneyStr(room) + " §cmore today.";
		long cover = Casino.coverLimit(null);
		if (max > cover) {
			return "The treasury can cover wins up to " + Msg.moneyStr(cover) + " §cright now – this table could pay " + Msg.moneyStr(max) + "§c.";
		}
		long balance = EconomyService.balance(player);
		if (total > balance) return "Not enough money: your table is " + Msg.moneyStr(total) + "§c, your balance " + Msg.moneyStr(balance) + "§c.";
		return null;
	}

	/** Highest total stake right now (ignoring coverage and balance): min(max bet, daily room), 0 = locked. */
	static long maxTotal(UUID player) {
		CasinoConfig c = Casino.cfg();
		if (!c.enabled) return 0;
		long max = Math.min(Casino.maxBet(), Casino.lossRoom(player));
		return max >= c.minBet ? max : 0;
	}

	static void remember(UUID player, int pocket) {
		Deque<Integer> h = HISTORY.computeIfAbsent(player, k -> new ArrayDeque<>());
		h.addFirst(pocket);
		while (h.size() > HISTORY_SIZE) h.removeLast();
	}

	static List<Integer> history(UUID player) {
		Deque<Integer> h = HISTORY.get(player);
		return h == null ? List.of() : List.copyOf(h);
	}

	static long[] lastTable(UUID player) {
		long[] t = LAST_TABLE.get(player);
		return t == null ? new long[RouletteMath.SPOTS] : t.clone();
	}

	static void saveTable(UUID player, long[] bets, int chip) {
		if (RouletteMath.valid(bets)) LAST_TABLE.put(player, bets.clone());
		LAST_CHIP.put(player, chip);
	}

	static int lastChip(UUID player) {
		return LAST_CHIP.getOrDefault(player, -1);
	}

	private static void forget(UUID player) {
		HISTORY.remove(player);
		LAST_TABLE.remove(player);
		LAST_CHIP.remove(player);
		HINT_SHOWN.remove(player);
	}
}
