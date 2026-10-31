package dev.smpeconomy.features.casino.games.roulette;

import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Util;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.gui.SmpGui;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.features.casino.Casino;

/**
 * Roulette table (6 rows, display slots only). Rows 0–3: numbers 1–36 (row r = 9r+1 ... 9r+9, item count shows the
 * number; columns 0/3/6 = column 1 etc.), during a spin the wheel instead (strip of 9 pockets under the pointer) and
 * the last numbers. Row 4: zero, 1–18, even, red, black, odd, 19–36, chip choice, spin. Row 5: back, dozens, columns,
 * clear or double table, rules.
 *
 * <p>Money moves only on the spin ({@link RouletteGame#play}: the result is fixed and saved before the wheel turns).
 * The end of the animation, closing, disconnect and server stop settle the round through {@link
 * Casino#settle}. The animation runs on wall-clock time (≤ {@value #SPIN_MS} ms even with lag); during the spin the
 * window is registered with the casino watchdog ({@link Casino#bind}), which therefore does not settle the round early.
 */
final class RouletteGui extends SmpGui implements Casino.Window {
	static final String TITLE = "§8Roulette";

	// board
	static final int SLOT_ZERO = 36;
	static final int SLOT_CHIP = 43;
	static final int SLOT_SPIN = 44;
	static final int SLOT_BACK = 45;
	static final int SLOT_CLEAR = 52;
	static final int SLOT_INFO = 53;
	// wheel view
	static final int SLOT_POINTER = 4;
	static final int STRIP_FIRST = 9;
	static final int STRIP_CENTER = 13;
	static final int SLOT_POINTER_LOW = 22;
	static final int HISTORY_FIRST = 27;

	/** Chip values (cents): $1 ... $50,000. */
	static final long[] CHIPS = {100, 500, 1_000, 5_000, 10_000, 50_000, 100_000, 500_000, 1_000_000, 5_000_000};
	private static final Item[] CHIP_ITEMS = {Items.WHITE_CARPET, Items.RED_CARPET, Items.BLUE_CARPET, Items.GREEN_CARPET, Items.BLACK_CARPET,
		Items.PURPLE_CARPET, Items.ORANGE_CARPET, Items.PINK_CARPET, Items.CYAN_CARPET, Items.YELLOW_CARPET};
	private static final int DEFAULT_CHIP = 2; // $10

	/** Time until the ball lands (then settlement), then result display. */
	static final long SPIN_MS = 3_200;
	static final long RESULT_MS = 1_600;
	private static final int LAPS = 3;

	private enum Phase { IDLE, SPINNING, RESULT }

	private final ServerPlayer player;
	private final UUID id;
	private final long[] bets;
	private int chip;
	private Phase phase = Phase.IDLE;

	// running or last round
	private String roundId;
	private int pocket = -1;
	private long[] spunBets;
	private long spunStake;
	private long spunPayout;
	private int lastPocket = -1;
	private String lastResult = "";

	// animation
	private long animStart;
	private long resultUntil;
	private int startIdx;
	private int travel;
	private int shownPos = -1;
	private int centerIdx;
	private int lastServerTick = Integer.MIN_VALUE;

	RouletteGui(ServerPlayer player) {
		super(Gui.menuType(6), player, false);
		this.player = player;
		this.id = player.getUUID();
		setTitle(Msg.legacy(TITLE));
		this.bets = RouletteGame.lastTable(id);
		int remembered = RouletteGame.lastChip(id);
		this.chip = remembered >= 0 && remembered < CHIPS.length ? remembered : defaultChip();
		List<Integer> h = RouletteGame.history(id);
		this.lastPocket = h.isEmpty() ? -1 : h.get(0);
		this.centerIdx = lastPocket >= 0 ? RouletteMath.wheelIndex(lastPocket) : player.getRandom().nextInt(RouletteMath.POCKETS);
		render();
	}

	/** Largest chip ≤ $10 that is still under the max bet. */
	private int defaultChip() {
		long max = RouletteGame.maxTotal(id);
		int c = DEFAULT_CHIP;
		while (c > 0 && max > 0 && CHIPS[c] > max) c--;
		return c;
	}

	private boolean busy() {
		return phase != Phase.IDLE || isEnded();
	}

	private void onSpot(int spot, ClickType type) {
		if (busy()) return;
		if (type.isRight) {
			long before = bets[spot];
			if (before <= 0) return;
			bets[spot] = type.shift ? 0 : Math.max(0, before - CHIPS[chip]);
			Gui.sound(player, SoundEvents.NOTE_BLOCK_HAT, 0.7f);
			render();
			return;
		}
		int count = type.shift ? 5 : 1;
		long add;
		long[] next = bets.clone();
		try {
			add = Math.multiplyExact(CHIPS[chip], count);
			next[spot] = Math.addExact(next[spot], add);
		} catch (ArithmeticException e) {
			fail("Your bets are too large.");
			return;
		}
		String problem = RouletteGame.tableProblem(id, next);
		if (problem != null) {
			fail(problem);
			return;
		}
		bets[spot] = next[spot];
		Gui.sound(player, SoundEvents.NOTE_BLOCK_HAT, 1.6f);
		Gui.sound(player, SoundEvents.NOTE_BLOCK_SNARE, 1.9f);
		render();
	}

	private void onChip(ClickType type) {
		if (busy()) return;
		chip = Math.floorMod(chip + (type.isRight ? -1 : 1), CHIPS.length);
		Gui.sound(player, SoundEvents.NOTE_BLOCK_XYLOPHONE, 0.8f + chip * 0.12f);
		render();
	}

	private void onClear(ClickType type) {
		if (busy()) return;
		long total = RouletteMath.total(bets);
		if (total <= 0) return;
		if (type.isRight) {
			long[] next = new long[RouletteMath.SPOTS];
			try {
				for (int i = 0; i < next.length; i++) next[i] = Math.multiplyExact(bets[i], 2);
			} catch (ArithmeticException e) {
				fail("Your bets are too large.");
				return;
			}
			String problem = RouletteGame.tableProblem(id, next);
			if (problem != null) {
				fail(problem);
				return;
			}
			System.arraycopy(next, 0, bets, 0, next.length);
			Gui.sound(player, SoundEvents.NOTE_BLOCK_SNARE, 1.5f);
		} else {
			java.util.Arrays.fill(bets, 0);
			Gui.sound(player, SoundEvents.NOTE_BLOCK_BASEDRUM, 1.2f);
		}
		render();
	}

	private void onSpin() {
		if (busy()) return;
		long total = RouletteMath.total(bets);
		if (total <= 0) {
			fail("Place at least one chip first (left-click a field).");
			return;
		}
		RouletteGame.Spin s = RouletteGame.play(id, player.getGameProfile().name(), bets, Casino.RNG);
		if (!s.ok()) {
			fail(s.error());
			return;
		}
		// result is fixed and saved; display only from here
		roundId = s.start().round().id;
		Casino.bind(roundId, this);
		pocket = s.pocket();
		spunBets = bets.clone();
		spunStake = s.start().round().stake;
		spunPayout = s.start().round().payout;
		phase = Phase.SPINNING;
		animStart = Util.getMillis();
		startIdx = centerIdx;
		travel = LAPS * RouletteMath.POCKETS + Math.floorMod(RouletteMath.wheelIndex(pocket) - startIdx, RouletteMath.POCKETS);
		shownPos = 0;
		RouletteGame.saveTable(id, bets, chip);
		Gui.sound(player, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.BOOK_PAGE_TURN), 0.6f);
		Gui.sound(player, SoundEvents.NOTE_BLOCK_CHIME, 1.2f);
		render();
	}

	private void openHub() {
		if (busy()) return;
		Casino.openHub(player);
	}

	private void fail(String message) {
		Msg.error(player, message);
		Gui.failSound(player);
		render();
	}

	@Override
	public void onTick() {
		if (phase == Phase.IDLE || isEnded()) return;
		// sgui also calls onTick after click packets: at most one step per server tick
		MinecraftServer server = player.level().getServer();
		int tick = server == null ? -1 : server.getTickCount();
		if (tick == lastServerTick) return;
		lastServerTick = tick;
		long now = Util.getMillis();
		if (phase == Phase.SPINNING) {
			double p = Math.min(1.0, Math.max(0, now - animStart) / (double) SPIN_MS);
			double eased = 1 - Math.pow(1 - p, 3);
			int pos = p >= 1 ? travel : Math.min(travel, (int) Math.floor(travel * eased));
			if (pos != shownPos) {
				shownPos = pos;
				centerIdx = Math.floorMod(startIdx + pos, RouletteMath.POCKETS);
				Gui.sound(player, SoundEvents.NOTE_BLOCK_HAT, 1.0f + (float) p * 0.9f);
				renderStrip(false);
			}
			if (p >= 1) land();
		} else if (phase == Phase.RESULT && now - resultUntil >= 0) {
			phase = Phase.IDLE;
			render();
		}
	}

	/** The ball has landed: settles the round, shows the result. */
	private void land() {
		centerIdx = RouletteMath.wheelIndex(pocket);
		settleOpenRound(null);
		lastPocket = pocket;
		RouletteGame.remember(id, pocket);
		long profit = spunPayout - spunStake;
		lastResult = spunPayout > 0
			? "§a" + RouletteMath.pocketLabel(pocket).substring(2) + " §8· §awon " + Money.format(spunPayout)
			: "§c" + RouletteMath.pocketLabel(pocket).substring(2) + " §8· §clost " + Money.format(spunStake);
		phase = Phase.RESULT;
		resultUntil = Util.getMillis() + RESULT_MS;
		Gui.sound(player, SoundEvents.NOTE_BLOCK_BELL, 1.0f);
		if (spunPayout > 0) {
			Gui.sound(player, SoundEvents.NOTE_BLOCK_PLING, 1.4f);
			Gui.sound(player, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.PLAYER_LEVELUP), profit > 0 ? 1.2f : 0.8f);
			if (spunPayout >= Math.multiplyExact(spunStake, 10L)) {
				Gui.sound(player, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE), 1.0f);
			}
		} else {
			Gui.sound(player, SoundEvents.NOTE_BLOCK_BASS, 0.6f);
		}
		render();
	}

	/** Settles the open (already decided) round; {@code extra} appears after the number in the message. */
	private void settleOpenRound(String extra) {
		if (roundId == null) return;
		String rid = roundId;
		roundId = null;
		String plain = RouletteMath.pocketLabel(pocket).substring(2);
		// null = already settled (watchdog under extreme lag, admin); the payout came from there
		Casino.settle(rid, extra == null ? plain : plain + ", " + extra);
	}

	@Override
	public void expire(String rid, String why) {
		if (rid != null && rid.equals(roundId)) settleOpenRound(why);
		else Casino.settle(rid, why);
		close();
	}

	@Override
	public void detach(String rid) {
		if (rid == null || !rid.equals(roundId)) return;
		roundId = null; // settled from outside (admin): the payout came from there
		phase = Phase.IDLE;
		render();
	}

	@Override
	protected void onEnded() {
		if (roundId != null) {
			// window closed during the spin (ESC, disconnect, stop): result was fixed, pay now
			settleOpenRound(Casino.CLOSED_REASON);
			RouletteGame.remember(id, pocket);
		}
		phase = Phase.IDLE;
		RouletteGame.saveTable(id, bets, chip);
	}

	private void render() {
		if (phase == Phase.IDLE) renderBoard();
		else renderWheel();
		renderBottom();
	}

	private void renderBoard() {
		for (int n = 1; n <= 36; n++) setSlot(n - 1, spotItem(n));
	}

	private void renderWheel() {
		boolean landed = phase == Phase.RESULT;
		boolean won = landed && spunPayout > 0;
		for (int i = 0; i < 9; i++) {
			setSlot(i, landed ? resultPane() : Gui.border());
			setSlot(18 + i, landed ? resultPane() : Gui.border());
		}
		String head = landed
			? (won ? "§a§lWIN " + Money.format(spunPayout) : "§c§lNo win")
			: "§e▼ Ball";
		List<String> pointerLore = new ArrayList<>();
		pointerLore.add("§7Total bet: " + Msg.moneyStr(spunStake));
		if (landed) pointerLore.add("§7Result: " + RouletteMath.pocketLabel(pocket));
		setSlot(SLOT_POINTER, Gui.item(Items.HOPPER, head, pointerLore));
		setSlot(SLOT_POINTER_LOW, Gui.item(Items.END_ROD, landed ? head : "§e▲"));
		renderStrip(landed);
		List<Integer> h = RouletteGame.history(id);
		for (int i = 0; i < 9; i++) {
			if (i < h.size()) {
				int n = h.get(i);
				setSlot(HISTORY_FIRST + i, Gui.item(pocketItem(n, false), RouletteMath.pocketLabel(n), i == 0 ? "§8Last number" : "§8" + (i + 1) + " spins ago").setCount(Math.max(1, n)));
			} else {
				setSlot(HISTORY_FIRST + i, Gui.filler());
			}
		}
	}

	/** Wheel strip: 9 pockets, the middle one under the pointer. */
	private void renderStrip(boolean landed) {
		for (int i = 0; i < 9; i++) {
			int n = RouletteMath.WHEEL[Math.floorMod(centerIdx - 4 + i, RouletteMath.POCKETS)];
			boolean center = i == 4;
			GuiElementBuilder b;
			if (landed && center) {
				b = Gui.item(pocketItem(n, true), "§l" + RouletteMath.pocketLabel(n),
					spunPayout > 0 ? "§aYou won " + Money.format(spunPayout) : "§7No win this time.",
					winningSpotsLine());
				b.glow();
			} else {
				b = Gui.item(pocketItem(n, false), RouletteMath.pocketLabel(n));
			}
			setSlot(STRIP_FIRST + i, b.setCount(Math.max(1, n)));
		}
	}

	private String winningSpotsLine() {
		if (spunBets == null) return "";
		List<String> won = new ArrayList<>();
		for (int s = 0; s < RouletteMath.SPOTS; s++) {
			if (spunBets[s] > 0 && RouletteMath.covers(s, pocket)) won.add(RouletteMath.spotName(s));
		}
		return won.isEmpty() ? "" : "§8Winning bets: §f" + String.join(", ", won);
	}

	private GuiElementBuilder resultPane() {
		Item pane = pocket == 0 ? Items.LIME_STAINED_GLASS_PANE : RouletteMath.isRed(pocket) ? Items.RED_STAINED_GLASS_PANE : Items.BLACK_STAINED_GLASS_PANE;
		return new GuiElementBuilder(pane).setName(Msg.legacy(" ")).hideTooltip();
	}

	private void renderBottom() {
		setSlot(SLOT_ZERO, spotItem(0));
		setSlot(37, spotItem(RouletteMath.LOW));
		setSlot(38, spotItem(RouletteMath.EVEN));
		setSlot(39, spotItem(RouletteMath.RED_SPOT));
		setSlot(40, spotItem(RouletteMath.BLACK_SPOT));
		setSlot(41, spotItem(RouletteMath.ODD));
		setSlot(42, spotItem(RouletteMath.HIGH));
		for (int i = 0; i < 3; i++) {
			setSlot(46 + i, spotItem(RouletteMath.DOZEN_1 + i));
			setSlot(49 + i, spotItem(RouletteMath.COLUMN_1 + i));
		}
		setSlot(SLOT_CHIP, chipItem());
		setSlot(SLOT_SPIN, spinItem());
		setSlot(SLOT_BACK, phase == Phase.IDLE
			? Gui.button(Items.ARROW, "§c« Back", this::openHub, "§7Casino menu", "§8Your chips stay on the table.")
			: Gui.filler());
		setSlot(SLOT_CLEAR, clearItem());
		setSlot(SLOT_INFO, infoItem());
	}

	private GuiElementBuilder spotItem(int spot) {
		long bet = bets[spot];
		boolean result = phase == Phase.RESULT && spunBets != null;
		List<String> lore = new ArrayList<>();
		lore.add("§7Covers " + RouletteMath.coverText(spot) + " §8· §7pays §e" + RouletteMath.odds(spot));
		if (bet > 0) {
			lore.add("§7Your bet: §e" + Money.format(bet) + " §7→ wins " + Msg.moneyStr(RouletteMath.spotWin(bet, spot)));
		} else {
			lore.add("§8No chips here.");
		}
		if (result && spunBets[spot] > 0) {
			lore.add(RouletteMath.covers(spot, pocket) ? "§a✔ Won this spin" : "§c✘ Lost this spin");
		}
		if (spot >= 0 && spot < RouletteMath.POCKETS && spot == lastPocket) lore.add("§e★ Last number");
		lore.add("");
		lore.add("§eLeft: §7+ " + Money.format(CHIPS[chip]) + " §8· §eShift+Left: §7+5 chips");
		lore.add("§eRight: §7− " + Money.format(CHIPS[chip]) + " §8· §eShift+Right: §7remove all");
		String name = spotTitle(spot) + (bet > 0 ? " §8· §e" + Money.format(bet) : "");
		boolean marked = spot >= 0 && spot < RouletteMath.POCKETS && spot == lastPocket;
		GuiElementBuilder b = Gui.item(spotIcon(spot, marked), name, lore).setCount(spotCount(spot));
		if (bet > 0) b.glow();
		return b.setCallback((index, type, action, gui) -> {
			if (!Gui.acceptClick(player, index, type)) return;
			onSpot(spot, type);
		});
	}

	private static String spotTitle(int spot) {
		if (spot >= 0 && spot < RouletteMath.POCKETS) return RouletteMath.pocketLabel(spot);
		return switch (spot) {
			case RouletteMath.RED_SPOT -> "§cRed";
			case RouletteMath.BLACK_SPOT -> "§8Black";
			default -> "§f" + RouletteMath.spotName(spot);
		};
	}

	private static Item spotIcon(int spot, boolean marked) {
		if (spot >= 0 && spot < RouletteMath.POCKETS) return pocketItem(spot, marked);
		if (spot >= RouletteMath.DOZEN_1 && spot < RouletteMath.COLUMN_1) return Items.BOOK;
		if (spot >= RouletteMath.COLUMN_1) return Items.LADDER;
		return switch (spot) {
			case RouletteMath.RED_SPOT -> Items.REDSTONE_BLOCK;
			case RouletteMath.BLACK_SPOT -> Items.COAL_BLOCK;
			case RouletteMath.EVEN -> Items.QUARTZ_BLOCK;
			case RouletteMath.ODD -> Items.AMETHYST_BLOCK;
			case RouletteMath.LOW -> Items.IRON_BLOCK;
			default -> Items.GOLD_BLOCK;
		};
	}

	/** Item count on the icon: the number itself, 1–3 for dozens and columns. */
	private static int spotCount(int spot) {
		if (spot > 0 && spot < RouletteMath.POCKETS) return spot;
		if (spot >= RouletteMath.DOZEN_1 && spot < RouletteMath.COLUMN_1) return spot - RouletteMath.DOZEN_1 + 1;
		if (spot >= RouletteMath.COLUMN_1 && spot < RouletteMath.SPOTS) return spot - RouletteMath.COLUMN_1 + 1;
		return 1;
	}

	/**
	 * Pocket on the wheel or number on the board; {@code marked} (last number or result) as glass instead of concrete.
	 */
	private static Item pocketItem(int n, boolean marked) {
		if (n == 0) return marked ? Items.LIME_STAINED_GLASS : Items.LIME_CONCRETE;
		if (RouletteMath.isRed(n)) return marked ? Items.RED_STAINED_GLASS : Items.RED_CONCRETE;
		return marked ? Items.BLACK_STAINED_GLASS : Items.BLACK_CONCRETE;
	}

	private GuiElementBuilder chipItem() {
		List<String> lore = new ArrayList<>();
		for (int i = CHIPS.length - 1; i >= 0; i--) {
			lore.add((i == chip ? "§e▶ " : "§8  ") + Money.format(CHIPS[i]));
		}
		lore.add("");
		lore.add("§eLeft: §7bigger chip §8· §eRight: §7smaller chip");
		GuiElementBuilder b = Gui.item(CHIP_ITEMS[chip], "§6Chip: §e" + Money.format(CHIPS[chip]), lore);
		return b.setCallback((index, type, action, gui) -> {
			if (!Gui.acceptClick(player, index, type)) return;
			onChip(type);
		});
	}

	private GuiElementBuilder spinItem() {
		if (phase == Phase.SPINNING) return Gui.item(Items.CLOCK, "§eSpinning …", "§7Total bet: " + Msg.moneyStr(spunStake));
		if (phase == Phase.RESULT) return Gui.item(Items.CLOCK, "§7…", lastResult);
		long total = RouletteMath.total(bets);
		if (total <= 0) {
			return Gui.item(Items.GRAY_DYE, "§7Place your chips", "§7Left-click a field to place a chip,", "§7then spin the wheel here.");
		}
		long best = RouletteMath.maxPayout(bets);
		List<String> lore = new ArrayList<>();
		lore.add("§7Total bet: " + Msg.moneyStr(total));
		lore.add("§7Best case: " + Msg.moneyStr(best));
		lore.add("§7Balance: " + Msg.moneyStr(EconomyService.balance(id)));
		if (!lastResult.isEmpty()) lore.add("§7Last spin: " + lastResult);
		lore.add("");
		lore.add("§eClick: spin the wheel");
		return Gui.button(Items.ENDER_EYE, "§a§lSPIN §7(" + Money.format(total) + ")", this::onSpin, lore.toArray(String[]::new));
	}

	private GuiElementBuilder clearItem() {
		long total = RouletteMath.total(bets);
		if (total <= 0 || phase != Phase.IDLE) {
			return Gui.item(Items.BUCKET, total <= 0 ? "§8Table is empty" : "§8Clear table");
		}
		GuiElementBuilder b = Gui.item(Items.WATER_BUCKET, "§cClear table", "§7Chips on the table: §e" + Money.format(total), "",
			"§eLeft: §7take all chips back", "§eRight: §7double all bets");
		return b.setCallback((index, type, action, gui) -> {
			if (!Gui.acceptClick(player, index, type)) return;
			onClear(type);
		});
	}

	private GuiElementBuilder infoItem() {
		List<String> lore = new ArrayList<>();
		lore.add("§71. Pick a chip value §8(carpet)§7.");
		lore.add("§72. §eLeft-click §7fields to place chips,");
		lore.add("§7   §eright-click §7to take them back.");
		lore.add("§73. Hit §a§lSPIN§7. Chips stay for the next spin.");
		lore.add("");
		lore.add("§7Number §e35:1 §8· §7Dozen/Column §e2:1");
		lore.add("§7Red/Black, Even/Odd, 1–18/19–36 §e1:1");
		lore.add("§7Zero loses all outside bets.");
		lore.add("§7The house keeps §e1 % §7of every payout.");
		lore.add("§8European wheel · RTP " + RouletteMath.RTP_TEXT);
		lore.add("");
		long max = RouletteGame.maxTotal(id);
		lore.add("§7Max. total bet: " + (max > 0 ? Msg.moneyStr(max) : "§clocked"));
		lore.add("§7Max. win per spin: " + Msg.moneyStr(Casino.coverLimit(null)));
		lore.add("§7Loss limit left today: " + Msg.moneyStr(Casino.lossRoom(id)));
		lore.add("§7Balance: " + Msg.moneyStr(EconomyService.balance(id)));
		List<Integer> h = RouletteGame.history(id);
		if (!h.isEmpty()) {
			StringBuilder sb = new StringBuilder("§7Last numbers: ");
			for (int n : h) sb.append(RouletteMath.color(n)).append(n).append(' ');
			lore.add("");
			lore.add(sb.toString().trim());
		}
		return Gui.item(Items.KNOWLEDGE_BOOK, "§eHow to play", lore);
	}
}
