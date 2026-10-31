package dev.smpeconomy.features.casino.games.dice;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.gui.SmpGui;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.features.casino.Casino;
import dev.smpeconomy.features.casino.CasinoData;
import dev.smpeconomy.features.casino.CasinoData.Round;
import dev.smpeconomy.features.casino.games.dice.DiceGame.Bet;

/**
 * Dice table (6 rows, display-only GUI without real slots). Direction and target on top, below them the number bar
 * 1–100 (green = win range), the die in the middle, quick picks and the bet bar at the bottom. The roll is fixed and
 * saved ({@link Casino#startInstant}) before the animation runs, which is display only. However the GUI closes (ESC,
 * death, disconnect, server stop), the round is settled at once through {@link Casino#settle}.
 */
final class DiceGui extends SmpGui implements Casino.Window {
	static final String TITLE = "§8Dice";
	static final int ROWS = 6;

	static final int SLOT_HELP = 4;
	static final int SLOT_MODE = 10;
	static final int SLOT_MINUS10 = 11;
	static final int SLOT_MINUS1 = 12;
	static final int SLOT_TARGET = 13;
	static final int SLOT_PLUS1 = 14;
	static final int SLOT_PLUS10 = 15;
	static final int SLOT_ODDS = 16;
	/** Number bar: 9 cells (1–11, 12–22, ..., 89–100). */
	static final int TRACK = 18;
	static final int SLOT_HISTORY = 29;
	static final int SLOT_DIE = 31;
	static final int SLOT_RESULT = 33;
	/** Quick pick by win chance (direction stays). */
	static final int[] PRESET_SLOTS = {37, 38, 39, 40, 41, 42, 43};
	static final int[] PRESET_CHANCES = {90, 75, 50, 33, 20, 10, 2};
	// bet bar (last row)
	static final int SLOT_BACK = 45;
	static final int SLOT_HALF = 46;
	static final int SLOT_MINUS = 47;
	static final int SLOT_STAKE = 48;
	static final int SLOT_ROLL = 49;
	static final int SLOT_PLUS = 50;
	static final int SLOT_DOUBLE = 51;
	static final int SLOT_MAX = 52;
	static final int SLOT_CLOSE = 53;

	/** Animation ticks at which the die shows a new random number; at {@link #LAND_TICK} it shows the result. */
	private static final int[] SPIN_TICKS = {1, 2, 3, 4, 5, 6, 7, 8, 10, 12, 14, 16, 19, 22, 25};
	static final int LAND_TICK = 28;
	private static final int HISTORY_SIZE = 8;

	private final ServerPlayer player;
	private Bet bet;
	private long stake;
	/** Running round (only during the animation), otherwise null. */
	private Round round;
	/** Result of the running or last round (stays on display after settlement). */
	private int roll = -1;
	private boolean won;
	private long payout;
	private long lastStake;
	private Bet lastBet;
	private int lastEdgeBp;
	/** Number currently shown during the animation. */
	private int shown = -1;
	/** ≥ 0 during the animation (ticks since start), otherwise -1. */
	private int animTick = -1;
	private int lastServerTick = Integer.MIN_VALUE;
	private final Deque<String> history = new ArrayDeque<>();

	DiceGui(ServerPlayer player, Bet bet) {
		super(Gui.menuType(ROWS), player, false);
		this.player = player;
		this.bet = bet;
		setTitle(Msg.legacy(TITLE));
		CasinoData.PlayerStats s = Casino.peekStats(player.getUUID());
		Long last = s == null ? null : s.lastStake.get(DiceGame.GAME_ID);
		long def = Math.max(Casino.cfg().minBet, last != null && last > 0 ? last : 10_000); // $100
		long max = DiceGame.maxStake(player.getUUID(), bet);
		this.stake = max > 0 ? Math.min(def, max) : def;
	}

	/**
	 * Opens the table ({@code stake}/{@code bet} null = last chosen) and rolls at once if {@code rollNow} (command with
	 * a complete bet).
	 */
	static void open(ServerPlayer player, Long stake, Bet bet, boolean rollNow) {
		if (!Gui.canOpen(player)) return;
		Bet b = bet != null ? bet : DiceGame.lastBet(player.getUUID());
		DiceGui gui = new DiceGui(player, b);
		if (stake != null) gui.stake = Math.max(Casino.cfg().minBet, Math.min(Money.MAX, stake));
		gui.render();
		if (!gui.open()) return;
		if (rollNow) gui.startRoll();
	}

	private boolean busy() {
		return animTick >= 0 || round != null || isEnded();
	}

	private void startRoll() {
		if (busy()) return;
		Casino.Start s = DiceGame.roll(player.getUUID(), player.getGameProfile().name(), stake, bet, Casino.RNG);
		if (!s.ok()) {
			Msg.error(player, s.error());
			Gui.failSound(player);
			render();
			return;
		}
		DiceGame.rememberBet(player.getUUID(), bet);
		round = s.round();
		Casino.bind(round.id, this); // watchdog: the round belongs to this window (no settlement after 5 s in the middle of a roll)
		roll = round.reels[DiceGame.R_ROLL];
		payout = round.payout;
		won = payout > 0;
		lastStake = round.stake;
		lastBet = bet;
		lastEdgeBp = round.reels[DiceGame.R_EDGE];
		shown = -1;
		animTick = 0;
		render();
	}

	@Override
	public void onTick() {
		if (animTick < 0) return;
		// sgui also calls onTick after every click packet: at most one step per server tick
		MinecraftServer server = player.level().getServer();
		int tick = server == null ? -1 : server.getTickCount();
		if (tick == lastServerTick) return;
		lastServerTick = tick;
		animTick++;
		animate(animTick);
	}

	private void animate(int t) {
		if (t >= LAND_TICK) {
			land();
			return;
		}
		for (int i = 0; i < SPIN_TICKS.length; i++) {
			if (SPIN_TICKS[i] != t) continue;
			int next;
			do {
				next = DiceMath.roll(ThreadLocalRandom.current());
			} while (next == shown);
			shown = next;
			Gui.sound(player, SoundEvents.NOTE_BLOCK_HAT, 0.8f + i * 0.06f);
			renderTrack();
			renderDie();
			return;
		}
	}

	/** Die has landed: settles the round (the casino sends the message) and shows the result. */
	private void land() {
		animTick = -1;
		shown = roll;
		finishRound(null);
		Gui.sound(player, SoundEvents.NOTE_BLOCK_BASEDRUM, 0.8f);
		if (won) {
			Gui.sound(player, SoundEvents.NOTE_BLOCK_PLING, 1.4f);
			Gui.sound(player, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.PLAYER_LEVELUP), 1.2f);
		} else {
			Gui.sound(player, SoundEvents.NOTE_BLOCK_BASS, 0.6f);
		}
		render();
	}

	/** Settles the running round (no effect if already settled) and keeps it for the history. */
	private void finishRound(String why) {
		if (round == null) return;
		String id = round.id;
		round = null;
		Casino.settle(id, why);
		String line = (won ? "§a" : "§c") + roll + " §8(" + lastBet.text() + ") " + (won ? "§a+" + Money.format(payout - lastStake) : "§c-" + Money.format(lastStake));
		history.addFirst(line);
		while (history.size() > HISTORY_SIZE) history.removeLast();
	}

	@Override
	public void expire(String rid, String why) {
		animTick = -1;
		if (round != null && round.id.equals(rid)) finishRound(why);
		else Casino.settle(rid, why);
		close();
	}

	@Override
	public void detach(String rid) {
		if (round == null || !round.id.equals(rid)) return;
		round = null; // settled from outside (admin): the payout came from there
		animTick = -1;
		render();
	}

	@Override
	protected void onEnded() {
		animTick = -1;
		finishRound(Casino.CLOSED_REASON);
	}

	private void setBet(Bet b) {
		if (busy()) return;
		bet = new Bet(b.over(), DiceMath.clampTarget(b.over(), b.target()));
		long max = DiceGame.maxStake(player.getUUID(), bet);
		if (max > 0 && stake > max) stake = max;
		render();
	}

	private void moveTarget(int delta) {
		setBet(new Bet(bet.over(), bet.target() + delta));
	}

	/** Switches direction, keeping the same win chance (over 75 ⇄ under 26). */
	private void flipMode() {
		boolean over = !bet.over();
		setBet(new Bet(over, DiceMath.targetFor(over, bet.chance())));
	}

	private void setStake(long value) {
		if (busy()) return;
		long min = Casino.cfg().minBet;
		long v = Math.max(min, Math.min(Money.MAX, value));
		long max = DiceGame.maxStake(player.getUUID(), bet);
		if (max > 0 && v > max) v = max;
		stake = v;
		render();
	}

	private void setMaxStake() {
		if (busy()) return;
		long max = Math.min(DiceGame.maxStake(player.getUUID(), bet), EconomyService.balance(player.getUUID()));
		if (max < Casino.cfg().minBet) {
			Msg.error(player, "No stake possible right now (Treasury, daily limit or balance).");
			Gui.failSound(player);
			return;
		}
		setStake(max);
	}

	void render() {
		for (int i = 0; i < ROWS * 9; i++) {
			int row = i / 9;
			int col = i % 9;
			boolean edge = row == 0 || col == 0 || col == 8;
			if (row != 2 && row != ROWS - 1) setSlot(i, edge ? Gui.border() : Gui.filler());
		}
		setSlot(SLOT_HELP, help());
		renderBetControls();
		renderTrack();
		renderDie();
		renderHistory();
		renderResult();
		renderPresets();
		renderStakeBar();
	}

	/** Edge of the last roll (displays its multiplier). */
	private int edgeBp() {
		return lastEdgeBp;
	}

	private GuiElementBuilder help() {
		int edgeBp = DiceGame.cfg().edgeBp();
		return Gui.item(Items.BOOK, "§eHow to play",
			"§7A die rolls a number from §f1 §7to §f100§7.",
			"§aOVER N§7: you win if the roll is §aabove §7N.",
			"§cUNDER N§7: you win if the roll is §cbelow §7N.",
			"",
			"§7Payout = 99 ÷ win chance × " + String.format(java.util.Locale.ROOT, "%.2f", (10_000 - edgeBp) / 10_000.0),
			"§7Lower chance, bigger multiplier (up to §a" + DiceMath.formatMultiplier(DiceMath.MIN_CHANCE, edgeBp) + "§7).",
			"§8Return to player " + DiceMath.formatPercent(DiceMath.rtp(edgeBp)) + " · house edge " + DiceMath.formatPercent(1 - DiceMath.rtp(edgeBp)),
			"",
			"§8/dice <bet> <over|under> <number>",
			"§8/dice help");
	}

	private void renderBetControls() {
		boolean idle = !busy();
		int edgeBp = DiceGame.cfg().edgeBp();
		Item modeItem = bet.over() ? Items.FEATHER : Items.ANVIL;
		setSlot(SLOT_MODE, idle
			? Gui.button(modeItem, "§fMode: " + (bet.over() ? "§a§lOVER" : "§c§lUNDER"), this::flipMode,
				bet.over() ? "§7Win if the roll is §aabove §7your number." : "§7Win if the roll is §cbelow §7your number.",
				"", "§eClick: switch to " + (bet.over() ? "§cUNDER" : "§aOVER") + " §8(same chance)")
			: Gui.item(modeItem, "§fMode: " + (bet.over() ? "§a§lOVER" : "§c§lUNDER")));
		setSlot(SLOT_MINUS10, idle ? Gui.button(Items.RED_STAINED_GLASS, "§c−10", () -> moveTarget(-10), "§7Number −10") : Gui.filler());
		setSlot(SLOT_MINUS1, idle ? Gui.button(Items.RED_STAINED_GLASS_PANE, "§c−1", () -> moveTarget(-1), "§7Number −1") : Gui.filler());
		setSlot(SLOT_PLUS1, idle ? Gui.button(Items.LIME_STAINED_GLASS_PANE, "§a+1", () -> moveTarget(1), "§7Number +1") : Gui.filler());
		setSlot(SLOT_PLUS10, idle ? Gui.button(Items.LIME_STAINED_GLASS, "§a+10", () -> moveTarget(10), "§7Number +10") : Gui.filler());
		GuiElementBuilder target = Gui.item(Items.TARGET, "§fRoll " + bet.colored(),
			"§7Winning rolls: §f" + winRange(bet) + " §8(" + bet.chance() + " of 100)",
			"§7Allowed: " + DiceMath.minTarget(bet.over()) + "–" + DiceMath.maxTarget(bet.over()));
		target.setCount(Math.max(1, Math.min(99, bet.target())));
		setSlot(SLOT_TARGET, target);
		long win = DiceMath.payout(stake, bet.chance(), edgeBp);
		long max = DiceGame.maxStake(player.getUUID(), bet);
		setSlot(SLOT_ODDS, Gui.item(Items.GOLD_INGOT, "§eWin chance: §f" + bet.chance() + " %",
			"§7Multiplier: §a" + DiceMath.formatMultiplier(bet.chance(), edgeBp),
			"§7Win: " + Msg.moneyStr(win) + " §8(profit " + Money.format(Math.max(0, win - stake)) + ")",
			"§7Max. stake for this bet: " + (max > 0 ? Msg.moneyStr(max) : "§clocked")));
	}

	static String winRange(Bet b) {
		int lo = b.over() ? b.target() + 1 : DiceMath.MIN_ROLL;
		int hi = b.over() ? DiceMath.MAX_ROLL : b.target() - 1;
		return lo == hi ? String.valueOf(lo) : lo + "–" + hi;
	}

	/** Number bar 1–100 in 9 cells: green = wins only, red = losses only, yellow = mixed; die position marked. */
	private void renderTrack() {
		Bet b = bet; // fixed during the roll (= lastBet)
		// result marker only while the bet is the same as on the last roll
		int marker = animTick >= 0 ? shown : lastBet != null && lastBet.equals(bet) ? roll : -1;
		for (int cell = 0; cell < 9; cell++) {
			int lo = cell * 100 / 9 + 1;
			int hi = (cell + 1) * 100 / 9;
			int wins = 0;
			for (int n = lo; n <= hi; n++) if (DiceMath.wins(n, b.over(), b.target())) wins++;
			int size = hi - lo + 1;
			boolean here = marker >= lo && marker <= hi;
			GuiElementBuilder e;
			if (here && animTick < 0) {
				e = Gui.item(won ? Items.EMERALD : Items.REDSTONE, (won ? "§a§l" : "§c§l") + "▲ " + roll, "§7Range " + lo + "–" + hi).glow();
			} else if (here && animTick >= 0) {
				e = Gui.item(Items.WHITE_STAINED_GLASS, "§f§l" + shown, "§7Rolling …");
			} else {
				Item pane = wins == size ? Items.LIME_STAINED_GLASS_PANE : wins == 0 ? Items.RED_STAINED_GLASS_PANE : Items.YELLOW_STAINED_GLASS_PANE;
				String color = wins == size ? "§a" : wins == 0 ? "§c" : "§e";
				e = Gui.item(pane, color + lo + "–" + hi, wins == size ? "§aAll win" : wins == 0 ? "§cAll lose" : "§e" + wins + " of " + size + " win");
			}
			setSlot(TRACK + cell, e);
		}
	}

	private void renderDie() {
		GuiElementBuilder e;
		if (animTick >= 0) {
			e = Gui.item(Items.QUARTZ_BLOCK, "§f§l" + Math.max(1, shown), "§7Rolling …");
			e.setCount(Math.max(1, shown));
		} else if (roll >= 0) {
			e = Gui.item(won ? Items.EMERALD_BLOCK : Items.REDSTONE_BLOCK, (won ? "§a§l" : "§c§l") + roll + (won ? " – WIN!" : " – no luck"),
				"§7You rolled §f" + roll + "§7, needed " + lastBet.text() + ".",
				won ? "§aPaid " + Money.format(payout) + " §8(" + DiceMath.formatMultiplier(lastBet.chance(), edgeBp()) + ")" : "§cLost " + Money.format(lastStake));
			e.setCount(roll);
			if (won) e.glow();
		} else {
			e = Gui.item(Items.QUARTZ_BLOCK, "§f§lRoll the dice!", "§7Set your number and stake,", "§7then press §a§lROLL§7.");
		}
		setSlot(SLOT_DIE, e);
	}

	private void renderHistory() {
		List<String> lore = new ArrayList<>();
		if (history.isEmpty()) lore.add("§8No rolls yet.");
		else lore.addAll(history);
		setSlot(SLOT_HISTORY, Gui.item(Items.PAPER, "§eLast rolls", lore));
	}

	private void renderResult() {
		GuiElementBuilder e;
		if (animTick >= 0 || round != null) {
			e = Gui.item(Items.CLOCK, "§7Rolling …", "§7Stake " + Money.format(lastStake) + " on " + lastBet.text());
		} else if (roll >= 0) {
			e = won ? Gui.item(Items.LIME_DYE, "§a§lYou won " + Money.format(payout), "§7Stake " + Money.format(lastStake) + " · " + DiceMath.formatMultiplier(lastBet.chance(), edgeBp()),
					"§7Profit: §a+" + Money.format(payout - lastStake))
				: Gui.item(Items.GRAY_DYE, "§c§lYou lost " + Money.format(lastStake), "§7Rolled " + roll + ", needed " + lastBet.text() + ".");
		} else {
			e = Gui.item(Items.GRAY_DYE, "§7No roll yet", "§7Good luck!");
		}
		setSlot(SLOT_RESULT, e);
	}

	private void renderPresets() {
		boolean idle = !busy();
		int edgeBp = DiceGame.cfg().edgeBp();
		for (int i = 0; i < PRESET_SLOTS.length; i++) {
			int chance = PRESET_CHANCES[i];
			Bet p = new Bet(bet.over(), DiceMath.targetFor(bet.over(), chance));
			boolean active = p.equals(bet);
			String name = (active ? "§6§l" : "§e") + DiceMath.formatMultiplier(chance, edgeBp) + " §7– " + p.colored();
			String[] lore = {"§7Win chance " + chance + " %", active ? "§6Selected" : "§eClick to select"};
			Item icon = chance >= 50 ? Items.IRON_NUGGET : chance >= 10 ? Items.GOLD_NUGGET : Items.DIAMOND;
			GuiElementBuilder e = idle && !active ? Gui.button(icon, name, () -> setBet(p), lore) : Gui.item(icon, name, lore);
			if (active) e.glow();
			setSlot(PRESET_SLOTS[i], e);
		}
	}

	private void renderStakeBar() {
		boolean idle = !busy();
		setSlot(SLOT_BACK, idle ? Gui.button(Items.ARROW, "§c« Back", () -> Casino.openHub(player), "§7Casino menu") : Gui.filler());
		long min = Casino.cfg().minBet;
		long max = DiceGame.maxStake(player.getUUID(), bet);
		setSlot(SLOT_STAKE, Gui.item(Items.GOLD_NUGGET, "§6Stake: §e" + Money.format(stake),
			"§7Min " + Msg.moneyStr(min) + " §8· §7Max " + (max > 0 ? Msg.moneyStr(max) : "§clocked"),
			"§7Balance: " + Msg.moneyStr(EconomyService.balance(player.getUUID())),
			"§8Max. stake ≤ 2 % of the Treasury, daily loss limit applies."));
		if (idle) {
			setSlot(SLOT_HALF, Gui.button(Items.RED_DYE, "§c÷2", () -> setStake(stake / 2), "§7Halve stake"));
			setSlot(SLOT_MINUS, Gui.button(Items.RED_DYE, "§c−" + Money.formatShort(10_000), t -> setStake(stake - (t.isRight ? 100_000 : 10_000)), "§7Left: −" + Money.formatShort(10_000), "§7Right: −" + Money.formatShort(100_000)));
			setSlot(SLOT_PLUS, Gui.button(Items.LIME_DYE, "§a+" + Money.formatShort(10_000), t -> setStake(stake + (t.isRight ? 100_000 : 10_000)), "§7Left: +" + Money.formatShort(10_000), "§7Right: +" + Money.formatShort(100_000)));
			setSlot(SLOT_DOUBLE, Gui.button(Items.LIME_DYE, "§a×2", () -> setStake(stake * 2), "§7Double stake"));
			setSlot(SLOT_MAX, Gui.button(Items.GOLD_BLOCK, "§6Max", this::setMaxStake, "§7Highest possible stake"));
			long win = DiceMath.payout(stake, bet.chance(), DiceGame.cfg().edgeBp());
			setSlot(SLOT_ROLL, Gui.button(Items.LIME_CONCRETE, "§a§lROLL §7(" + Money.format(stake) + ")", this::startRoll,
				"§7" + bet.colored() + " §8· §7" + bet.chance() + " % §8· §a" + DiceMath.formatMultiplier(bet.chance(), DiceGame.cfg().edgeBp()),
				"§7Win: " + Msg.moneyStr(win)));
		} else {
			for (int s : new int[] {SLOT_HALF, SLOT_MINUS, SLOT_PLUS, SLOT_DOUBLE, SLOT_MAX}) setSlot(s, Gui.filler());
			setSlot(SLOT_ROLL, Gui.item(Items.CLOCK, "§7Rolling …"));
		}
		setSlot(SLOT_CLOSE, Gui.item(Items.BARRIER, "§cClose").setCallback((i, t, a, g) -> {
			if (Gui.isPlainClick(t)) close();
		}));
	}
}
