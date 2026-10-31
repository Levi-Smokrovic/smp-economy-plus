package dev.smpeconomy.features.casino.games.crash;

import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.BossEvent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import dev.smpeconomy.core.compat.Mc;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.gui.SmpGui;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.features.casino.Casino;
import dev.smpeconomy.features.casino.games.crash.CrashData.PlayerStats;
import dev.smpeconomy.features.casino.games.crash.CrashData.Round;

/**
 * Crash window (6 rows): top row help, last crash points, live multiplier and auto cash-out; rows 2–5 the flight graph
 * (click anywhere on it to cash out); bottom row bet controls and LAUNCH / CASH OUT. The live multiplier is also shown in
 * a boss bar. The flight is driven by server ticks while the window is open; closing the window in any way (ESC, other
 * menu, death, disconnect, server stop) cashes out at the last shown multiplier.
 */
final class CrashGui extends SmpGui {
	static final String TITLE = "§8Crash";
	static final int ROWS = 6;
	static final int SLOT_HELP = 0;
	static final int SLOT_MULT = 4;
	static final int SLOT_AUTO = 8;
	/** Past crash points, newest first. */
	static final int[] HISTORY_SLOTS = {1, 2, 3, 5, 6, 7};
	static final int GRAPH_FIRST_ROW = 1;
	static final int GRAPH_ROWS = 4;
	static final int GRAPH_COLS = 9;
	static final int SLOT_BACK = 45;
	static final int SLOT_HALF = 46;
	static final int SLOT_MINUS = 47;
	static final int SLOT_STAKE = 48;
	static final int SLOT_ACTION = 49;
	static final int SLOT_PLUS = 50;
	static final int SLOT_DOUBLE = 51;
	static final int SLOT_MAX = 52;
	static final int SLOT_CLOSE = 53;
	private static final int RENDER_EVERY = 2;
	private static final int SOUND_EVERY = 4;
	private static final int BOSSBAR_HOLD_TICKS = 60;
	private static final long[] MILESTONES = {200, 300, 500, 1_000, 2_500, 5_000, 10_000, 25_000, 50_000};

	private final ServerPlayer player;
	/** Flying round (null = none). */
	private Round round;
	/** Flight ticks of {@link #round}; -1 = nothing flying. */
	private long ticks = -1;
	/** Last finished round (graph and result stay visible until the next launch). */
	private Round shown;
	private long shownTicks;
	private long stake;
	/** Auto cash-out (hundredths), 0 = off. */
	private long autoH;
	private String lastResult = "";
	private int lastServerTick = Integer.MIN_VALUE;
	private long lastMilestone;
	private ServerBossEvent bossbar;
	/** Ticks until the boss bar of a finished round disappears (-1 = not counting). */
	private int bossbarHold = -1;

	CrashGui(ServerPlayer player) {
		super(Gui.menuType(ROWS), player, false);
		this.player = player;
		setTitle(Msg.legacy(TITLE));
		PlayerStats s = Crash.peekStats(player.getUUID());
		long auto = s == null || s.lastAutoH < 0 ? 0 : s.lastAutoH;
		this.autoH = auto > Crash.cfg().capH() || (auto != 0 && auto <= CrashMath.ONE) ? 0 : auto;
		long last = s == null ? 0 : s.lastStake;
		long def = Math.max(Casino.cfg().minBet, last > 0 ? last : 10_000); // $100
		long max = Crash.maxStake(player.getUUID(), autoH);
		this.stake = max > 0 ? Math.min(def, max) : def;
	}

	/** Opens the window; with {@code stake} the rocket launches right away ({@code autoH} null = last choice). */
	static void open(ServerPlayer player, Long stake, Long autoH) {
		if (!Gui.canOpen(player)) return;
		CrashGui gui = new CrashGui(player);
		if (autoH != null) gui.autoH = autoH;
		if (stake != null) gui.stake = stake;
		gui.render();
		if (!gui.open()) return;
		if (stake != null) gui.launch();
	}

	private UUID id() {
		return player.getUUID();
	}

	private boolean flying() {
		return round != null && ticks >= 0;
	}

	private void launch() {
		if (round != null || isEnded()) return;
		Crash.Start s = Crash.start(id(), player.getGameProfile().name(), stake, autoH, Casino.RNG);
		if (!s.ok()) {
			Msg.error(player, s.error());
			Gui.failSound(player);
			render();
			return;
		}
		round = s.round();
		Crash.bind(round.id, this);
		shown = null;
		ticks = 0;
		lastMilestone = 0;
		MinecraftServer server = player.level().getServer();
		if (server != null) lastServerTick = server.getTickCount(); // next step in the next server tick
		Gui.sound(player, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.FIREWORK_ROCKET_LAUNCH), 1.0f);
		showBossbar();
		step();
	}

	@Override
	public void onTick() {
		// sgui calls onTick after every click packet as well: the flight advances at most once per server tick
		MinecraftServer server = player.level().getServer();
		int tick = server == null ? -1 : server.getTickCount();
		if (tick == lastServerTick) return;
		lastServerTick = tick;
		if (flying()) {
			ticks++;
			step();
		} else if (bossbarHold > 0 && --bossbarHold == 0) {
			hideBossbar();
		}
	}

	/** Advances the flight to {@link #ticks}: still flying → sounds/graph; target reached or crashed → finish. */
	private void step() {
		Round r = round;
		if (r == null) return;
		CrashMath.Phase p = Crash.advance(r, ticks);
		if (p == null) return; // settled elsewhere (admin); detach() already reset the window
		switch (p) {
			case RUNNING -> {
				flightSounds(r.lastMultH);
				if (ticks % RENDER_EVERY == 0) {
					updateBossbar(r);
					render();
				}
			}
			case CASHED, CRASHED -> finish();
		}
	}

	private void cashOutClick() {
		Round r = round;
		if (r == null || !flying()) return;
		if (!Crash.cashOut(r)) {
			if (Crash.isOpen(r)) {
				Msg.error(player, "Wait until the rocket has lifted off (above ×1.00).");
				Gui.failSound(player);
			}
			return;
		}
		finish();
	}

	/** The round is decided (cash-out, auto cash-out, cap or crash): settle it now and show the result. */
	private void finish() {
		Round r = round;
		if (r == null) return;
		long endTicks = Math.max(0, ticks);
		round = null;
		ticks = -1;
		shown = r;
		shownTicks = endTicks;
		Crash.Settled s = Crash.settle(r.id, null);
		if (s == null) {
			render();
			return;
		}
		lastResult = resultShort(s.round());
		if (s.refund()) {
			Gui.click(player);
		} else if (s.payout() > 0) {
			Gui.sound(player, SoundEvents.NOTE_BLOCK_PLING, 1.4f);
			Gui.sound(player, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.PLAYER_LEVELUP), 1.2f);
			if (s.round().cashedAtH >= 1_000) Gui.sound(player, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE), 1.0f);
		} else {
			Gui.sound(player, SoundEvents.GENERIC_EXPLODE, 1.0f);
			Gui.sound(player, SoundEvents.NOTE_BLOCK_BASS, 0.5f);
		}
		finalBossbar(s.round());
		render();
	}

	private static String resultShort(Round r) {
		if (r.refund) return "§7bet refunded";
		if (r.payout > 0) return "§awon " + Money.format(r.payout) + " §8(" + CrashMath.format(r.cashedAtH) + ")";
		return "§clost " + Money.format(r.stake) + " §8(crash " + CrashMath.format(r.crashH) + ")";
	}

	private void flightSounds(long m) {
		for (long ms : MILESTONES) {
			if (m >= ms && lastMilestone < ms) {
				lastMilestone = ms;
				float pitch = (float) Math.min(2.0, 0.8 + 0.25 * (Math.log(ms / 100.0) / Math.log(2)));
				Gui.sound(player, SoundEvents.NOTE_BLOCK_CHIME, pitch);
				if (ms >= 1_000) Gui.sound(player, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.FIREWORK_ROCKET_TWINKLE), 1.0f);
				return;
			}
		}
		if (ticks > 0 && ticks % SOUND_EVERY == 0) {
			float pitch = (float) Math.min(2.0, 0.5 + 1.5 * Math.log(m / 100.0) / Math.log(10.0));
			Gui.sound(player, SoundEvents.NOTE_BLOCK_HAT, pitch);
		}
	}

	@Override
	protected void onEnded() {
		hideBossbar();
		if (round != null) {
			String rid = round.id;
			round = null;
			ticks = -1;
			Crash.settle(rid, "window closed");
		}
	}

	/** Watchdog: settle ({@code why} in the message) and close the window. */
	void expire(String roundId, String why) {
		if (round != null && round.id.equals(roundId)) {
			round = null;
			ticks = -1;
		}
		Crash.settle(roundId, why);
		close();
	}

	/** The round was settled outside this window (admin): back to idle, showing the result. */
	void detach(String roundId) {
		if (round == null || !round.id.equals(roundId)) return;
		shown = round;
		shownTicks = Math.max(0, ticks);
		round = null;
		ticks = -1;
		lastResult = resultShort(shown);
		finalBossbar(shown);
		if (isOpen()) render();
	}

	private void back() {
		if (round != null) {
			// settle first, then build the casino menu with the new numbers
			String rid = round.id;
			shown = round;
			shownTicks = Math.max(0, ticks);
			round = null;
			ticks = -1;
			Crash.settle(rid, null);
		}
		Casino.openHub(player);
	}

	private void setStake(long value) {
		if (round != null || isEnded()) return;
		long min = Casino.cfg().minBet;
		long v = Math.max(min, Math.min(Money.MAX, value));
		long max = Crash.maxStake(id(), autoH);
		if (max > 0 && v > max) v = max;
		stake = v;
		render();
	}

	private void setMaxStake() {
		long max = Math.min(Crash.maxStake(id(), autoH), EconomyService.balance(id()));
		if (max < Casino.cfg().minBet) {
			Msg.error(player, "No bet possible right now (treasury, daily limit or balance).");
			Gui.failSound(player);
			return;
		}
		setStake(max);
	}

	private void cycleAuto(ClickType t) {
		if (round != null) return;
		List<Long> presets = new ArrayList<>();
		long cap = Crash.cfg().capH();
		for (long p : CrashMath.AUTO_PRESETS) {
			if (p <= cap) presets.add(p);
		}
		if (t.shift) {
			autoH = 0;
		} else if (t.isRight) {
			long prev = presets.get(presets.size() - 1);
			for (int i = presets.size() - 1; i >= 0; i--) {
				if (presets.get(i) < autoH) {
					prev = presets.get(i);
					break;
				}
			}
			autoH = prev;
		} else {
			long next = 0;
			for (long p : presets) {
				if (p > autoH) {
					next = p;
					break;
				}
			}
			autoH = next;
		}
		setStake(stake); // max bet depends on the auto cash-out
	}

	private String autoText(long a) {
		return a > 0 ? "§f" + CrashMath.format(a) : "§7off";
	}

	void render() {
		renderTop();
		renderGraph();
		renderBar();
	}

	private void renderTop() {
		setSlot(SLOT_HELP, helpItem());
		PlayerStats s = Crash.peekStats(id());
		List<Long> history = s == null ? List.of() : s.history;
		for (int i = 0; i < HISTORY_SLOTS.length; i++) {
			if (i < history.size()) {
				long h = history.get(i);
				setSlot(HISTORY_SLOTS[i], Gui.item(historyPane(h), tierColor(h) + CrashMath.format(h),
					"§8" + (i == 0 ? "Last round" : (i + 1) + " rounds ago") + " – crash point"));
			} else {
				setSlot(HISTORY_SLOTS[i], Gui.filler());
			}
		}
		setSlot(SLOT_MULT, multiplierItem());
		setSlot(SLOT_AUTO, autoItem());
	}

	private GuiElementBuilder helpItem() {
		CrashConfig c = Crash.cfg();
		List<String> lore = new ArrayList<>(List.of(
			"§7Place a bet and §aLAUNCH §7the rocket.",
			"§7The multiplier climbs until the rocket §ccrashes§7.",
			"§7Click the graph or §aCASH OUT §7in time",
			"§7to win §fbet × multiplier§7.",
			"§7Auto cash-out cashes out by itself.",
			"§7Closing the window cashes out too.",
			"",
			"§7Max multiplier: §f" + CrashMath.format(c.capH()),
			"§7House edge: §f" + Crash.pct(c.houseEdgePercent) + " §8(RTP " + Crash.pct(100 - c.houseEdgePercent) + ")",
			"§7The multiplier doubles every §f" + CrashMath.num(c.doublingSeconds) + " s§7."));
		PlayerStats s = Crash.peekStats(id());
		if (s != null && s.rounds > 0) {
			long net = s.paidOut - s.wagered;
			lore.add("");
			lore.add("§eYour crash stats");
			lore.add("§7Rounds: §f" + Msg.number(s.rounds) + " §8· §7Won: §f" + Msg.number(s.wins));
			lore.add("§7Wagered: " + Msg.moneyStr(s.wagered) + " §8· §7Paid out: " + Msg.moneyStr(s.paidOut));
			lore.add("§7Result: " + (net >= 0 ? "§a+" : "§c") + Money.format(net));
			lore.add("§7Best cash-out: §e" + (s.bestMultH > 0 ? CrashMath.format(s.bestMultH) : "–") + " §8· §7Biggest win: " + Msg.moneyStr(s.biggestWin));
		}
		lore.add("");
		lore.add("§8/crash <bet> [auto|off] · /crash help");
		return Gui.item(Items.BOOK, "§e§lHow to play", lore);
	}

	private GuiElementBuilder multiplierItem() {
		Round r = round;
		if (r != null && flying()) {
			long m = r.lastMultH;
			List<String> lore = new ArrayList<>();
			lore.add(m > CrashMath.ONE ? "§7Cash out now: §a" + Money.format(CrashMath.payout(r.stake, m)) : "§7Lift-off …");
			lore.add("§7Bet: " + Msg.moneyStr(r.stake));
			if (r.autoH > 0) lore.add("§7Auto cash-out at " + autoText(r.autoH) + "§7: §a" + Money.format(CrashMath.payout(r.stake, r.autoH)));
			lore.add("");
			lore.add("§eClick to cash out");
			GuiElementBuilder b = Gui.item(Items.FIREWORK_ROCKET, tierColor(m) + "§l" + CrashMath.format(m), lore)
				.setCount((int) Math.max(1, Math.min(64, m / 100)));
			return cashOutCell(b);
		}
		Round f = shown;
		if (f != null) return resultItem(f);
		return Gui.item(Items.FIREWORK_ROCKET, "§6§lCrash", "§7Set your bet below and", "§7press §aLAUNCH§7.");
	}

	private GuiElementBuilder resultItem(Round f) {
		if (f.refund) return Gui.item(Items.CLOCK, "§7Round cancelled", "§7Bet " + Msg.moneyStr(f.stake) + " refunded.");
		if (f.payout > 0) {
			return Gui.item(Items.EMERALD, "§a§lCashed out at " + CrashMath.format(f.cashedAtH),
				"§7Won " + Msg.moneyStr(f.payout) + " §8(bet " + Money.format(f.stake) + ")",
				"§7Crash point was §f" + CrashMath.format(f.crashH)).glow();
		}
		return Gui.item(Items.TNT, "§c§lCRASHED at " + CrashMath.format(f.crashH), "§7Bet " + Msg.moneyStr(f.stake) + " lost.").glow();
	}

	private GuiElementBuilder autoItem() {
		CrashConfig c = Crash.cfg();
		List<String> lore = new ArrayList<>();
		if (autoH > 0) {
			lore.add("§7Win chance: §f" + CrashMath.percent(CrashMath.survival(autoH, c.edgeBp(), c.capH())));
			lore.add("§7Pays at the target: §a" + Money.format(CrashMath.payout(stake, autoH)));
		} else {
			lore.add("§7Cash out yourself (or at " + CrashMath.format(c.capH()) + ").");
		}
		if (round == null) {
			lore.add("");
			lore.add("§7Left: higher §8· §7Right: lower §8· §7Shift: off");
			lore.add("§8or /crash <bet> <multiplier>");
			return Gui.button(Items.TARGET, "§eAuto cash-out: " + autoText(autoH), this::cycleAuto, lore.toArray(String[]::new));
		}
		long a = round.autoH;
		return Gui.item(Items.TARGET, "§eAuto cash-out: " + autoText(a), a > 0 ? "§7Fixed for this round." : "§7Off for this round.");
	}

	/** Graph cell; while flying every cell cashes out. */
	private GuiElementBuilder cashOutCell(GuiElementBuilder b) {
		return b.setCallback((index, type, action, g) -> {
			if (!Gui.acceptClick(player, index, type)) return;
			cashOutClick();
		});
	}

	private GuiElementBuilder graphCell(Item item, boolean flying) {
		if (!flying) return new GuiElementBuilder(item).setName(Msg.legacy(" ")).hideTooltip();
		return cashOutCell(Gui.item(item, "§a§lCASH OUT", "§7Click anywhere on the graph", "§7to cash out at the current multiplier."));
	}

	private static int graphSlot(int col, int level) {
		return (GRAPH_FIRST_ROW + (GRAPH_ROWS - 1 - level)) * 9 + col;
	}

	private void renderGraph() {
		boolean fly = flying();
		Round r = fly ? round : shown;
		if (r == null) {
			for (int col = 0; col < GRAPH_COLS; col++) {
				for (int level = 0; level < GRAPH_ROWS; level++) setSlot(graphSlot(col, level), graphCell(Items.BLACK_STAINED_GLASS_PANE, false));
			}
			setSlot(graphSlot(0, 0), Gui.item(Items.FIREWORK_ROCKET, "§6Ready for launch", "§7Set your bet below and press §aLAUNCH§7."));
			return;
		}
		long t = fly ? ticks : shownTicks;
		double dbl = r.doublingSeconds;
		double window = Math.max(t, 20.0 * dbl); // x axis shows at least one doubling, then compresses
		double maxY = Math.max(2.0, CrashMath.curve(t, dbl)); // y axis at least up to ×2
		int headCol = (int) Math.min(GRAPH_COLS - 1, Math.floor(GRAPH_COLS * t / window));
		boolean crashed = !fly && !r.refund && r.payout == 0;
		long tierMult = fly ? r.lastMultH : r.payout > 0 ? r.cashedAtH : r.crashH;
		Item area = crashed ? Items.RED_STAINED_GLASS_PANE : tierPane(tierMult);
		Item line = crashed ? Items.RED_CONCRETE : tierConcrete(tierMult);
		for (int col = 0; col < GRAPH_COLS; col++) {
			int lineLevel = -1;
			if (col <= headCol) {
				double tc = col == headCol ? t : window * (col + 1) / GRAPH_COLS;
				double y = (CrashMath.curve(tc, dbl) - 1.0) / (maxY - 1.0) * GRAPH_ROWS;
				lineLevel = (int) Math.max(0, Math.min(GRAPH_ROWS - 1, Math.floor(y)));
			}
			for (int level = 0; level < GRAPH_ROWS; level++) {
				int slot = graphSlot(col, level);
				if (level == lineLevel && col == headCol) {
					setSlot(slot, fly ? multiplierItem() : resultItem(r));
				} else if (level < lineLevel) {
					setSlot(slot, graphCell(area, fly));
				} else if (level == lineLevel) {
					setSlot(slot, graphCell(line, fly));
				} else {
					setSlot(slot, graphCell(Items.BLACK_STAINED_GLASS_PANE, fly));
				}
			}
		}
	}

	private void renderBar() {
		boolean idle = round == null;
		setSlot(SLOT_BACK, Gui.button(Items.ARROW, "§c« Back", this::back, idle ? "§7Casino menu" : "§7Cashes out the running round"));
		long min = Casino.cfg().minBet;
		long max = Crash.maxStake(id(), idle ? autoH : round.autoH);
		List<String> info = new ArrayList<>(List.of(
			"§7Min " + Msg.moneyStr(min) + " §8· §7Max " + (max > 0 ? Msg.moneyStr(max) : "§clocked"),
			"§7Balance: " + Msg.moneyStr(EconomyService.balance(id()))));
		if (!lastResult.isEmpty()) info.add("§7Last round: " + lastResult);
		setSlot(SLOT_STAKE, Gui.item(Items.GOLD_NUGGET, "§6Bet: §e" + Money.format(idle ? stake : round.stake), info));
		if (idle) {
			setSlot(SLOT_HALF, Gui.button(Items.RED_DYE, "§c÷2", () -> setStake(stake / 2), "§7Halve the bet"));
			setSlot(SLOT_MINUS, Gui.button(Items.RED_DYE, "§c−" + Money.formatShort(10_000), t -> setStake(stake - (t.isRight ? 100_000 : 10_000)), "§7Left: −" + Money.formatShort(10_000), "§7Right: −" + Money.formatShort(100_000)));
			setSlot(SLOT_PLUS, Gui.button(Items.LIME_DYE, "§a+" + Money.formatShort(10_000), t -> setStake(stake + (t.isRight ? 100_000 : 10_000)), "§7Left: +" + Money.formatShort(10_000), "§7Right: +" + Money.formatShort(100_000)));
			setSlot(SLOT_DOUBLE, Gui.button(Items.LIME_DYE, "§a×2", () -> setStake(stake * 2), "§7Double the bet"));
			setSlot(SLOT_MAX, Gui.button(Items.GOLD_BLOCK, "§6Max", this::setMaxStake, "§7Highest possible bet"));
			setSlot(SLOT_ACTION, launchButton());
		} else {
			for (int slot : new int[] {SLOT_HALF, SLOT_MINUS, SLOT_PLUS, SLOT_DOUBLE, SLOT_MAX}) setSlot(slot, Gui.filler());
			setSlot(SLOT_ACTION, cashOutButton());
		}
		setSlot(SLOT_CLOSE, (idle ? Gui.item(Items.BARRIER, "§cClose") : Gui.item(Items.BARRIER, "§cClose", "§7Cashes out the running round"))
			.setCallback((i, t, a, g) -> {
				if (Gui.isPlainClick(t)) close();
			}));
	}

	private GuiElementBuilder launchButton() {
		CrashConfig c = Crash.cfg();
		List<String> lore = new ArrayList<>();
		lore.add("§7Auto cash-out: " + autoText(autoH));
		if (autoH > 0) {
			lore.add("§7Win chance: §f" + CrashMath.percent(CrashMath.survival(autoH, c.edgeBp(), c.capH()))
				+ " §8· §7pays §a" + Money.format(CrashMath.payout(stake, autoH)));
		}
		lore.add("§7Max multiplier: §f" + CrashMath.format(c.capH()));
		lore.add("§8House edge " + Crash.pct(c.houseEdgePercent) + " · RTP " + Crash.pct(100 - c.houseEdgePercent));
		return Gui.button(Items.LIME_CONCRETE, "§a§lLAUNCH §7(" + Money.format(stake) + ")", this::launch, lore.toArray(String[]::new));
	}

	private GuiElementBuilder cashOutButton() {
		Round r = round;
		if (r == null || !Crash.canCashOut(r)) return Gui.item(Items.GRAY_DYE, "§7Cash out", "§7Lift-off …");
		long m = r.lastMultH;
		return cashOutCell(Gui.item(Items.EMERALD_BLOCK, "§a§lCASH OUT §a" + Money.format(CrashMath.payout(r.stake, m)),
			"§7" + CrashMath.format(m) + " on " + Money.format(r.stake)));
	}

	static String tierColor(long m) {
		if (m < 200) return "§a";
		if (m < 500) return "§e";
		if (m < 1_000) return "§6";
		if (m < 2_500) return "§d";
		return "§5";
	}

	private static Item tierPane(long m) {
		if (m < 200) return Items.LIME_STAINED_GLASS_PANE;
		if (m < 500) return Items.YELLOW_STAINED_GLASS_PANE;
		if (m < 1_000) return Items.ORANGE_STAINED_GLASS_PANE;
		if (m < 2_500) return Items.MAGENTA_STAINED_GLASS_PANE;
		return Items.PURPLE_STAINED_GLASS_PANE;
	}

	private static Item tierConcrete(long m) {
		if (m < 200) return Items.LIME_CONCRETE;
		if (m < 500) return Items.YELLOW_CONCRETE;
		if (m < 1_000) return Items.ORANGE_CONCRETE;
		if (m < 2_500) return Items.MAGENTA_CONCRETE;
		return Items.PURPLE_CONCRETE;
	}

	/** History: low crash points red, high ones colourful. */
	private static Item historyPane(long h) {
		if (h < 150) return Items.RED_STAINED_GLASS_PANE;
		if (h < 200) return Items.ORANGE_STAINED_GLASS_PANE;
		return tierPane(h);
	}

	private static BossEvent.BossBarColor barColor(long m) {
		if (m < 200) return BossEvent.BossBarColor.GREEN;
		if (m < 1_000) return BossEvent.BossBarColor.YELLOW;
		if (m < 2_500) return BossEvent.BossBarColor.PINK;
		return BossEvent.BossBarColor.PURPLE;
	}

	private void showBossbar() {
		if (bossbar == null) {
			bossbar = Mc.bossEvent(Msg.legacy("§a§l×1.00"), BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.PROGRESS);
			bossbar.addPlayer(player);
		}
		bossbarHold = -1;
		if (round != null) updateBossbar(round);
	}

	private void updateBossbar(Round r) {
		if (bossbar == null) return;
		long m = r.lastMultH;
		long target = CrashMath.target(r.autoH, r.capH);
		bossbar.setName(Msg.legacy(tierColor(m) + "§l" + CrashMath.format(m) + " §7– cash out: §a" + Money.format(CrashMath.payout(r.stake, m))
			+ (r.autoH > 0 ? " §8| auto " + CrashMath.format(r.autoH) : "")));
		double progress = Math.log(m / 100.0) / Math.log(target / 100.0);
		bossbar.setProgress((float) Math.max(0, Math.min(1, progress)));
		bossbar.setColor(barColor(m));
	}

	private void finalBossbar(Round r) {
		if (bossbar == null) return;
		if (r.refund) {
			bossbar.setName(Msg.legacy("§7Round cancelled – bet refunded"));
			bossbar.setColor(BossEvent.BossBarColor.WHITE);
		} else if (r.payout > 0) {
			bossbar.setName(Msg.legacy("§a§lCashed out at " + CrashMath.format(r.cashedAtH) + " §7– won §a" + Money.format(r.payout)));
			bossbar.setColor(BossEvent.BossBarColor.GREEN);
		} else {
			bossbar.setName(Msg.legacy("§c§lCRASHED at " + CrashMath.format(r.crashH)));
			bossbar.setColor(BossEvent.BossBarColor.RED);
			bossbar.setProgress(1.0f);
		}
		bossbarHold = BOSSBAR_HOLD_TICKS;
	}

	private void hideBossbar() {
		if (bossbar != null) {
			bossbar.removeAllPlayers();
			bossbar = null;
		}
		bossbarHold = -1;
	}
}
