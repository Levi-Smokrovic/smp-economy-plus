package dev.smpeconomy.features.casino.games.wheel;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
import dev.smpeconomy.features.casino.CasinoData.PlayerStats;
import dev.smpeconomy.features.casino.CasinoData.Round;

/**
 * Wheel GUI (6 rows): a ring of 20 segments around the centre, the pointer at the top middle (slot 4, marked below by
 * slot 13), bet bar in the last row like the other casino games. A spin first starts a decided round
 * ({@link WheelRounds#start}), then turns the ring with a cubic ease-out until the drawn segment sits under the pointer and
 * settles the round ({@link Casino#settle}). Closing the window at any time settles it at once (the result was fixed before
 * the wheel moved). The animation is capped at {@value #SPIN_MAX_MS} ms of wall time, well inside the casino watchdog's 5 s
 * grace for rounds without a casino GUI.
 */
final class WheelGui extends SmpGui implements Casino.Window {
	static final String TITLE = "§8Wheel of Fortune";
	static final Item ICON = Items.MUSIC_DISC_CHIRP;
	static final int ROWS = 6;
	/** Ring slots clockwise, starting at the pointer (top middle). */
	static final int[] RING = {4, 5, 6, 7, 16, 25, 34, 43, 42, 41, 40, 39, 38, 37, 28, 19, 10, 1, 2, 3};
	static final int POINTER = 13;
	static final int PAYTABLE = 11;
	static final int HELP = 15;
	static final int CENTER = 22;
	static final int[] HISTORY = {29, 30, 31, 32, 33};
	/** First slot of the bet bar (last row). */
	static final int BAR = (ROWS - 1) * 9;
	static final int SPIN = BAR + 4;
	/** Animation length in server ticks (3 s at 20 TPS). */
	static final int SPIN_TICKS = 60;
	/** Wall-clock cap of the animation (lag): then the wheel jumps to the result, well before the watchdog's 5 s. */
	static final long SPIN_MAX_MS = 3_500;
	/** Full turns before the wheel slows down onto the result. */
	static final int TURNS = 2;

	private final ServerPlayer player;
	private long stake;
	/** Round of the running spin (null when idle). */
	private Round round;
	/** Segment under the pointer. */
	private int offset;
	private int fromOffset;
	private int steps;
	/** ≥ 0 while spinning (ticks since start), else -1. */
	private int animTick = -1;
	private long spinStartedAt;
	private int spinSegment = -1;
	private long spinStake;
	private long spinPayout;
	/** Segment of the last finished spin (-1 = none yet). */
	private int landed = -1;
	private long resultStake;
	private long resultPayout;
	private final ArrayDeque<Integer> history = new ArrayDeque<>();
	private int lastServerTick = Integer.MIN_VALUE;

	private WheelGui(ServerPlayer player) {
		super(Gui.menuType(ROWS), player, false);
		this.player = player;
		setTitle(Msg.legacy(TITLE));
		long def = defaultStake(player.getUUID());
		long max = WheelRounds.maxStake(player.getUUID());
		this.stake = max > 0 ? Math.min(def, max) : def;
		this.offset = ThreadLocalRandom.current().nextInt(WheelMath.SEGMENTS);
	}

	/** Opens the wheel; with a bet it spins right away ({@code /wheel <bet>}). */
	static void open(ServerPlayer player, Long stake) {
		if (!Gui.canOpen(player)) return;
		WheelGui gui = new WheelGui(player);
		if (stake != null) gui.stake = stake;
		gui.render();
		if (!gui.open()) return;
		if (stake != null) gui.spin();
	}

	static long defaultStake(UUID player) {
		PlayerStats s = Casino.peekStats(player);
		Long last = s == null ? null : s.lastStake.get(WheelGame.ID);
		long v = last != null && last > 0 ? last : 10_000; // $100
		return Math.max(Casino.cfg().minBet, v);
	}

	/** Steps from {@code from} so that {@code target} ends under the pointer: {@value #TURNS} full turns plus the rest. */
	static int spinSteps(int from, int target) {
		return TURNS * WheelMath.SEGMENTS + Math.floorMod(target - from, WheelMath.SEGMENTS);
	}

	/** Steps done after {@code t} ticks (cubic ease-out, exactly {@code steps} at the end). */
	static int stepsDone(int steps, int t) {
		if (t >= SPIN_TICKS) return steps;
		if (t <= 0) return 0;
		double x = 1 - t / (double) SPIN_TICKS;
		return Math.min(steps, (int) Math.floor(steps * (1 - x * x * x)));
	}

	/** Segment shown at ring position {@code pos} when {@code offset} is under the pointer (the ring turns clockwise). */
	static int segmentAt(int offset, int pos) {
		return Math.floorMod(offset - pos, WheelMath.SEGMENTS);
	}

	private boolean busy() {
		return animTick >= 0 || isEnded();
	}

	private void spin() {
		if (busy() || round != null) return;
		WheelRounds.Start s = WheelRounds.start(player.getUUID(), player.getGameProfile().name(), stake, Casino.RNG);
		if (!s.ok()) {
			Msg.error(player, s.error());
			Gui.failSound(player);
			long max = WheelRounds.maxStake(player.getUUID());
			if (max > 0 && stake > max) stake = max; // show a bet that would be admitted
			render();
			return;
		}
		round = s.round();
		Casino.bind(round.id, this); // the casino watchdog leaves the round to this window while it is open
		spinSegment = round.card;
		spinStake = round.stake;
		spinPayout = round.payout;
		fromOffset = offset;
		steps = spinSteps(offset, spinSegment);
		spinStartedAt = System.currentTimeMillis();
		landed = -1;
		animTick = 0;
		Gui.sound(player, SoundEvents.NOTE_BLOCK_CHIME, 1.2f);
		render();
	}

	@Override
	public void onTick() {
		if (animTick < 0) return;
		// sgui also calls onTick after click packets: at most one animation step per server tick
		MinecraftServer server = player.level().getServer();
		int tick = server == null ? -1 : server.getTickCount();
		if (tick == lastServerTick) return;
		lastServerTick = tick;
		animTick++;
		boolean timeUp = System.currentTimeMillis() - spinStartedAt >= SPIN_MAX_MS;
		int t = timeUp ? SPIN_TICKS : Math.min(animTick, SPIN_TICKS);
		int next = Math.floorMod(fromOffset + stepsDone(steps, t), WheelMath.SEGMENTS);
		if (next != offset) {
			offset = next;
			Gui.sound(player, SoundEvents.NOTE_BLOCK_HAT, 0.9f + 0.7f * (1 - t / (float) SPIN_TICKS));
			renderRing();
		}
		if (t >= SPIN_TICKS) finishSpin();
	}

	private void finishSpin() {
		animTick = -1;
		offset = spinSegment;
		Casino.Settled settled = null;
		if (round != null) {
			String id = round.id;
			round = null;
			settled = Casino.settle(id, null); // chat line + stats + announcement come from the casino
		}
		// null: already settled elsewhere (admin, watchdog), which paid the same decided payout
		long payout = settled != null ? settled.payout() : spinPayout;
		landed = spinSegment;
		resultStake = spinStake;
		resultPayout = payout;
		history.addFirst(spinSegment);
		while (history.size() > HISTORY.length) history.removeLast();
		int t = WheelMath.tenths(spinSegment);
		Gui.sound(player, SoundEvents.NOTE_BLOCK_BASS, 1.0f);
		if (payout > 0) {
			Msg.actionBar(player, "§6Wheel §8» " + color(t) + WheelMath.label(t) + " §7– you get " + Msg.moneyStr(payout));
			if (t >= 100) {
				Gui.sound(player, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE), 1.0f);
				Gui.sound(player, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.FIREWORK_ROCKET_TWINKLE), 1.0f);
			} else {
				Gui.sound(player, SoundEvents.NOTE_BLOCK_PLING, 1.4f);
				Gui.sound(player, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.PLAYER_LEVELUP), 1.2f);
			}
		} else {
			Msg.actionBar(player, "§6Wheel §8» §c0× §7– no win this time");
			Gui.sound(player, SoundEvents.NOTE_BLOCK_BASS, 0.6f);
		}
		render();
	}

	@Override
	public void expire(String rid, String why) {
		animTick = -1;
		if (round != null && round.id.equals(rid)) round = null;
		Casino.settle(rid, why);
		close();
	}

	@Override
	public void detach(String rid) {
		if (round == null || !round.id.equals(rid)) return;
		round = null; // settled from outside (admin): the spin only shows the decided result
	}

	/** Any way the window closes (ESC, other menu, death, disconnect, server stop): settle the running spin now. */
	@Override
	protected void onEnded() {
		animTick = -1;
		if (round != null) {
			String id = round.id;
			round = null;
			Casino.settle(id, Casino.CLOSED_REASON);
		}
	}

	private void setStake(long value) {
		if (busy() || round != null) return;
		long v = Math.max(Casino.cfg().minBet, Math.min(Money.MAX, value));
		long max = WheelRounds.maxStake(player.getUUID());
		if (max > 0 && v > max) v = max;
		stake = v;
		render();
	}

	private void setMaxStake() {
		long max = Math.min(WheelRounds.maxStake(player.getUUID()), EconomyService.balance(player.getUUID()));
		if (max < Casino.cfg().minBet) {
			Msg.error(player, "No bet possible right now (Treasury, daily limit or balance).");
			Gui.failSound(player);
			return;
		}
		setStake(max);
	}

	private void render() {
		for (int row = 0; row < ROWS - 1; row++) {
			for (int col = 0; col < 9; col++) setSlot(row * 9 + col, col == 0 || col == 8 ? Gui.border() : Gui.filler());
		}
		setSlot(POINTER, Gui.item(Items.END_ROD, "§e▲ §6Pointer §e▲", "§7The segment above it wins."));
		setSlot(PAYTABLE, paytable());
		setSlot(HELP, help());
		renderRing();
		renderCenter();
		renderHistory();
		renderBar();
	}

	private void renderRing() {
		boolean showResult = landed >= 0 && animTick < 0;
		for (int pos = 0; pos < RING.length; pos++) {
			GuiElementBuilder b = segmentItem(segmentAt(offset, pos));
			if (pos == 0 && showResult) b.glow();
			setSlot(RING[pos], b);
		}
	}

	private static GuiElementBuilder segmentItem(int segment) {
		int t = WheelMath.tenths(segment);
		return Gui.item(item(t), color(t) + WheelMath.label(t) + (t == WheelMath.maxTenths() ? " §6§lJACKPOT" : ""),
			t > 0 ? "§7Pays §f" + WheelMath.label(t) + " §7your bet" : "§7No win",
			"§8" + WheelMath.percent(WheelMath.chance(t)) + " chance");
	}

	private void renderCenter() {
		GuiElementBuilder b;
		if (animTick >= 0) {
			b = Gui.item(Items.CLOCK, "§eSpinning…", "§7Bet: " + Msg.moneyStr(spinStake),
				"§8The result was drawn and saved", "§8before the wheel started turning.");
		} else if (landed >= 0) {
			int t = WheelMath.tenths(landed);
			long profit = resultPayout - resultStake;
			b = Gui.item(item(t), resultPayout > 0 ? "§a§l" + WheelMath.label(t) + " §7→ §a" + Money.format(resultPayout) : "§c§l0× §7– no win",
				"§7Bet: " + Msg.moneyStr(resultStake),
				"§7Result: " + (profit >= 0 ? "§a+" : "§c−") + Money.format(Math.abs(profit)),
				"",
				"§7Press §a§lSPIN §7to play again.");
			if (profit > 0) b.glow();
		} else {
			b = Gui.item(ICON, "§6§lWheel of Fortune",
				"§7Set your bet below and press §a§lSPIN§7.",
				"§7Win up to §b§l50× §7your bet!",
				"",
				"§8Return to player ~" + WheelMath.percent(WheelMath.theoreticalRtp()));
		}
		setSlot(CENTER, b);
	}

	private void renderHistory() {
		int i = 0;
		for (int segment : history) {
			int t = WheelMath.tenths(segment);
			setSlot(HISTORY[i], Gui.item(item(t), "§7" + (i == 0 ? "Last spin" : i + 1 + " spins ago") + ": " + color(t) + WheelMath.label(t)));
			i++;
		}
	}

	private void renderBar() {
		boolean idle = round == null && animTick < 0;
		setSlot(BAR, idle ? Gui.button(Items.ARROW, "§c« Back", () -> Casino.openHub(player), "§7Casino menu") : Gui.filler());
		long min = Casino.cfg().minBet;
		long max = WheelRounds.maxStake(player.getUUID());
		List<String> info = new ArrayList<>(List.of(
			"§7Min " + Msg.moneyStr(min) + " §8· §7Max " + (max > 0 ? Msg.moneyStr(max) : "§clocked"),
			"§7Balance: " + Msg.moneyStr(EconomyService.balance(player.getUUID()))));
		if (landed >= 0) info.add("§7Last spin: " + (resultPayout > 0 ? "§a" + Money.format(resultPayout) : "§cno win"));
		setSlot(BAR + 3, Gui.item(Items.GOLD_NUGGET, "§6Bet: §e" + Money.format(stake), info));
		if (idle) {
			setSlot(BAR + 1, Gui.button(Items.RED_DYE, "§c÷2", () -> setStake(stake / 2), "§7Halve the bet"));
			setSlot(BAR + 2, Gui.button(Items.RED_DYE, "§c−" + Money.formatShort(10_000), t -> setStake(stake - (t.isRight ? 100_000 : 10_000)), "§7Left: −" + Money.formatShort(10_000), "§7Right: −" + Money.formatShort(100_000)));
			setSlot(BAR + 5, Gui.button(Items.LIME_DYE, "§a+" + Money.formatShort(10_000), t -> setStake(stake + (t.isRight ? 100_000 : 10_000)), "§7Left: +" + Money.formatShort(10_000), "§7Right: +" + Money.formatShort(100_000)));
			setSlot(BAR + 6, Gui.button(Items.LIME_DYE, "§a×2", () -> setStake(stake * 2), "§7Double the bet"));
			setSlot(BAR + 7, Gui.button(Items.GOLD_BLOCK, "§6Max", this::setMaxStake, "§7Highest possible bet"));
			setSlot(SPIN, Gui.button(Items.LIME_CONCRETE, "§a§lSPIN §7(" + Money.format(stake) + ")", this::spin,
				"§7Win up to §b§l50× §7your bet.", "§8Payouts and odds: book on the left"));
		} else {
			for (int i : new int[] {1, 2, 5, 6, 7}) setSlot(BAR + i, Gui.filler());
			setSlot(SPIN, Gui.item(Items.CLOCK, "§7Spinning…"));
		}
		GuiElementBuilder close = round != null ? Gui.item(Items.BARRIER, "§cClose", "§7The running spin is paid out anyway.") : Gui.item(Items.BARRIER, "§cClose");
		setSlot(BAR + 8, close.setCallback((index, type, action, gui) -> {
			if (Gui.isPlainClick(type)) close();
		}));
	}

	static GuiElementBuilder paytable() {
		List<String> lore = new ArrayList<>();
		lore.add("§7Multiplier §8· §7chance");
		for (int t : WheelMath.TIERS) {
			lore.add(color(t) + WheelMath.label(t) + " §8· §7" + WheelMath.percent(WheelMath.chance(t)));
		}
		lore.add("");
		lore.add("§7Segments have different odds –");
		lore.add("§7the chances above are the real ones.");
		lore.add("§8Return to player " + WheelMath.percent(WheelMath.theoreticalRtp()));
		return Gui.item(Items.BOOK, "§ePayouts & odds", lore);
	}

	private static GuiElementBuilder help() {
		return Gui.item(Items.OAK_SIGN, "§eHow to play",
			"§7Set your bet in the bottom row,",
			"§7then press §a§lSPIN§7. The wheel stops on",
			"§7one segment – you get your bet × its",
			"§7multiplier (0× = the bet is lost).",
			"",
			"§8/wheel <bet> §7spins right away",
			"§8/wheel help §7shows the odds in chat",
			"§8/casino limit §7sets a daily loss limit");
	}

	static String color(int tenths) {
		return switch (tenths) {
			case 0 -> "§c";
			case 12 -> "§f";
			case 15 -> "§b";
			case 20 -> "§a";
			case 30 -> "§e";
			case 50 -> "§6";
			case 100 -> "§d";
			default -> "§b§l";
		};
	}

	static Item item(int tenths) {
		return switch (tenths) {
			case 0 -> Items.RED_CONCRETE;
			case 12 -> Items.WHITE_CONCRETE;
			case 15 -> Items.LIGHT_BLUE_CONCRETE;
			case 20 -> Items.GREEN_CONCRETE;
			case 30 -> Items.YELLOW_CONCRETE;
			case 50 -> Items.ORANGE_CONCRETE;
			case 100 -> Items.MAGENTA_CONCRETE;
			default -> Items.DIAMOND_BLOCK;
		};
	}
}
