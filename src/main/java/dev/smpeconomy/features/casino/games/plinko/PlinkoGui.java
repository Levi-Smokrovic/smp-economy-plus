package dev.smpeconomy.features.casino.games.plinko;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
import dev.smpeconomy.features.casino.CasinoData.Round;
import dev.smpeconomy.features.casino.games.plinko.PlinkoMath.Risk;

/**
 * Plinko GUI (6 rows): row 0 help, paytable, auto, drop, risk; rows 1–3 the peg board (triangle); row 4 the 9 buckets
 * with multipliers; row 5 the bet bar. When "Drop" is clicked the result is already fixed and saved ({@link
 * Plinko#drop}); the ball animation (about 1.7 s) only shows the drawn path. At the end, or as soon as the window
 * closes in any way (ESC, other menu, death, disconnect, server stop), the round is settled once through {@link
 * Casino#settle}. While the ball falls the window is registered with the casino watchdog ({@link Casino#bind}).
 */
final class PlinkoGui extends SmpGui implements Casino.Window {
	static final String TITLE = "§8Plinko";
	static final int ROWS = 6;
	static final int SLOT_HELP = 0;
	static final int SLOT_PAYOUTS = 1;
	static final int SLOT_AUTO = 2;
	static final int SLOT_CHUTE = 4;
	/** Risk buttons: 6 = low, 7 = medium, 8 = high. */
	static final int SLOT_RISK = 6;
	static final int BUCKET_ROW = 4;
	static final int BAR = (ROWS - 1) * 9;
	static final int SLOT_BACK = BAR;
	static final int SLOT_STAKE = BAR + 3;
	static final int SLOT_DROP = BAR + 4;
	static final int SLOT_CLOSE = BAR + 8;
	/** Balls per auto run. */
	static final int AUTO_DROPS = 10;
	/** Ticks until the next animation step (the ball speeds up); 33 ticks in total, far below the watchdog's 5 s. */
	private static final int[] STEP_TICKS = {6, 5, 5, 4, 4, 3, 3, 3};
	private static final int AUTO_PAUSE_TICKS = 10;
	private static final int HISTORY = 6;

	private final ServerPlayer player;
	private final UUID id;
	private Risk risk;
	private long stake;

	/** Open (already decided) round during the animation, otherwise null. */
	private Round round;
	private Risk roundRisk;
	private long roundStake;
	private long roundPayout;
	private int roundBucket;
	private int[][] frames;
	private int frame;
	private int nextFrameTick;
	/** ≥ 0 during the animation (ticks since the drop), otherwise -1. */
	private int animTick = -1;
	private int ballSlot = -1;
	/** Bucket of the last ball (lit), -1 = none. */
	private int landedBucket = -1;
	private int autoLeft;
	/** ≥ 0: ticks until the next auto ball. */
	private int autoWait = -1;
	private String lastResult = "";
	private final List<String> history = new ArrayList<>();
	private int lastServerTick = Integer.MIN_VALUE;

	private PlinkoGui(ServerPlayer player, Risk risk) {
		super(Gui.menuType(ROWS), player, false);
		this.player = player;
		this.id = player.getUUID();
		setTitle(Msg.legacy(TITLE));
		this.risk = risk != null ? risk : Plinko.lastRisk(id);
		long def = Plinko.lastStake(id);
		long max = Plinko.maxStake(id, this.risk);
		this.stake = max > 0 ? Math.min(def, max) : def;
	}

	/** Opens plinko; with {@code stake} a ball drops at once ({@code /plinko <bet> [risk]}). */
	static void open(ServerPlayer player, Long stake, Risk risk) {
		if (!Gui.canOpen(player)) return;
		PlinkoGui gui = new PlinkoGui(player, risk);
		if (risk != null) Plinko.rememberRisk(player.getUUID(), risk);
		if (stake != null) gui.stake = stake;
		gui.render();
		if (!gui.open()) return;
		if (stake != null) gui.drop();
	}

	private boolean busy() {
		return animTick >= 0 || autoWait >= 0 || isEnded();
	}

	private void drop() {
		if (animTick >= 0 || isEnded() || round != null) return;
		autoWait = -1;
		Casino.Start s = Plinko.drop(id, player.getGameProfile().name(), stake, risk, Casino.RNG);
		if (!s.ok()) {
			autoLeft = 0;
			Msg.error(player, s.error());
			Gui.failSound(player);
			render();
			return;
		}
		round = s.round();
		Casino.bind(round.id, this);
		roundRisk = risk;
		roundStake = round.stake;
		roundPayout = round.payout;
		roundBucket = PlinkoMath.bucket(round.reels);
		frames = PlinkoMath.frames(round.reels);
		frame = 0;
		animTick = 0;
		nextFrameTick = STEP_TICKS[0];
		landedBucket = -1;
		render();
		Gui.sound(player, SoundEvents.NOTE_BLOCK_HAT, 1.8f);
	}

	@Override
	public void onTick() {
		if (animTick < 0 && autoWait < 0) return;
		// sgui also calls onTick after click packets: at most one step per server tick
		MinecraftServer server = player.level().getServer();
		int tick = server == null ? -1 : server.getTickCount();
		if (tick == lastServerTick) return;
		lastServerTick = tick;
		if (animTick >= 0) {
			animTick++;
			if (animTick >= nextFrameTick) advance();
		} else if (--autoWait < 0) {
			if (autoLeft > 0 && !isEnded()) {
				autoLeft--;
				drop();
			} else {
				render();
			}
		}
	}

	private void advance() {
		frame++;
		if (frame >= frames.length - 1) {
			land();
			return;
		}
		int[] pos = frames[frame];
		moveBall(pos);
		nextFrameTick += STEP_TICKS[frame];
		if (frame % 2 == 1) Gui.sound(player, SoundEvents.NOTE_BLOCK_XYLOPHONE, 0.7f + pos[1] * 0.1f); // peg
		else Gui.sound(player, SoundEvents.NOTE_BLOCK_HAT, 1.0f + pos[0] * 0.15f); // one row down
	}

	/** Ball is in the bucket: settles the round, shows the result and possibly the next auto ball. */
	private void land() {
		animTick = -1;
		clearBall();
		landedBucket = roundBucket;
		long paid = roundPayout;
		if (round != null) {
			String rid = round.id;
			round = null;
			Casino.Settled s = Casino.settle(rid, null);
			// null: already settled (watchdog or admin), with the same result fixed on drop
			if (s != null) paid = s.payout();
		}
		int h = roundRisk.hundredths(roundBucket);
		String mult = PlinkoMath.formatHundredths(h);
		lastResult = paid > roundStake ? "§aWon " + Money.format(paid) + " §8(" + mult + ")"
			: paid == roundStake ? "§7Bet back §8(" + mult + ")"
			: "§cLost " + Money.format(roundStake - paid) + " §8(" + mult + ")";
		history.add(0, multColor(h) + mult);
		while (history.size() > HISTORY) history.remove(history.size() - 1);
		Msg.actionBar(player, Plinko.LABEL + " §8» " + multColor(h) + mult + " §8» §7Payout " + Msg.moneyStr(paid));
		landingSound(h, paid);
		if (autoLeft > 0 && !isEnded()) autoWait = AUTO_PAUSE_TICKS;
		render();
	}

	private void landingSound(int hundredths, long paid) {
		if (hundredths >= 1000) {
			Gui.sound(player, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE), 1.0f);
			Gui.sound(player, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.PLAYER_LEVELUP), 1.2f);
		} else if (paid > roundStake) {
			Gui.sound(player, SoundEvents.NOTE_BLOCK_PLING, 1.4f);
			Gui.sound(player, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.PLAYER_LEVELUP), 1.2f);
		} else if (paid == roundStake) {
			Gui.sound(player, SoundEvents.NOTE_BLOCK_CHIME, 1.0f);
		} else {
			Gui.sound(player, SoundEvents.NOTE_BLOCK_BASS, 0.6f);
		}
	}

	@Override
	public void expire(String rid, String why) {
		if (round != null && round.id.equals(rid)) round = null;
		animTick = -1;
		autoLeft = 0;
		Casino.settle(rid, why);
		close();
	}

	@Override
	public void detach(String rid) {
		if (round == null || !round.id.equals(rid)) return;
		round = null; // settled from outside (admin): the animation only shows the fixed result
	}

	/** Window closed (however): settles a running ball at once at its fixed result and stops auto. */
	@Override
	protected void onEnded() {
		animTick = -1;
		autoWait = -1;
		autoLeft = 0;
		if (round != null) {
			String rid = round.id;
			round = null;
			Casino.settle(rid, Casino.CLOSED_REASON);
		}
	}

	private void setRisk(Risk r) {
		if (busy() || r == risk) return;
		risk = r;
		landedBucket = -1;
		Plinko.rememberRisk(id, r);
		long max = Plinko.maxStake(id, r);
		if (max > 0 && stake > max) stake = max;
		render();
	}

	private void setStake(long value) {
		if (busy()) return;
		long min = Casino.cfg().minBet;
		long v = Math.max(min, Math.min(Money.MAX, value));
		long max = Plinko.maxStake(id, risk);
		if (max > 0 && v > max) v = max;
		stake = v;
		render();
	}

	private void setMaxStake() {
		long max = Math.min(Plinko.maxStake(id, risk), EconomyService.balance(id));
		if (max < Casino.cfg().minBet) {
			Msg.error(player, "No bet possible right now (treasury, daily limit or balance).");
			Gui.failSound(player);
			return;
		}
		setStake(max);
	}

	private void toggleAuto() {
		if (autoLeft > 0 || autoWait >= 0) {
			autoLeft = 0;
			autoWait = -1;
			render();
			return;
		}
		if (animTick >= 0 || isEnded()) return;
		autoLeft = AUTO_DROPS - 1;
		drop();
	}

	private void back() {
		if (busy()) return;
		Casino.openHub(player);
	}

	private void render() {
		for (int slot = 0; slot < BUCKET_ROW * 9 + 9; slot++) setSlot(slot, cell(slot));
		renderBar();
		ballSlot = -1;
		if (animTick >= 0 && frames != null && frame < frames.length - 1) moveBall(frames[frame]);
	}

	/** Base view of a cell in rows 0–4 (without the ball). */
	private GuiElementBuilder cell(int slot) {
		int row = slot / 9;
		int col = slot % 9;
		if (row == 0) return topItem(col);
		if (row < BUCKET_ROW) return boardCell(row, col);
		return bucketItem(col);
	}

	private void moveBall(int[] pos) {
		clearBall();
		ballSlot = pos[0] * 9 + pos[1];
		setSlot(ballSlot, new GuiElementBuilder(Items.MAGMA_CREAM).setName(Msg.legacy("§6● Ball")).hideDefaultTooltip());
	}

	private void clearBall() {
		if (ballSlot < 0) return;
		int s = ballSlot;
		ballSlot = -1;
		setSlot(s, cell(s));
	}

	private GuiElementBuilder topItem(int col) {
		if (col == SLOT_HELP) return helpItem();
		if (col == SLOT_PAYOUTS) return payoutsItem();
		if (col == SLOT_AUTO) return autoItem();
		if (col == SLOT_CHUTE) {
			return Gui.button(Items.HOPPER, "§eDrop zone", this::drop, "§7Click to drop a ball", "§7(same as §aDrop §7below)");
		}
		if (col >= SLOT_RISK && col < SLOT_RISK + 3) return riskItem(Risk.values()[col - SLOT_RISK]);
		return Gui.border();
	}

	/** Peg board: triangle of reachable cells, with pegs in a checkerboard pattern. */
	private static GuiElementBuilder boardCell(int row, int col) {
		if (Math.abs(col - 4) > row) return Gui.border();
		if ((col + row) % 2 == 0) return new GuiElementBuilder(Items.END_ROD).setName(Msg.legacy(" ")).hideTooltip();
		return Gui.filler();
	}

	private GuiElementBuilder bucketItem(int k) {
		Risk r = animTick >= 0 && roundRisk != null ? roundRisk : risk;
		int h = r.hundredths(k);
		Item item = h >= 1000 ? Items.GOLD_BLOCK : h >= 300 ? Items.LIME_CONCRETE : h >= 150 ? Items.YELLOW_CONCRETE
			: h >= 100 ? Items.ORANGE_CONCRETE : Items.RED_CONCRETE;
		List<String> lore = new ArrayList<>();
		lore.add("§7Chance: §f" + PlinkoMath.formatPercent(PlinkoMath.chance(k)));
		lore.add("§7Pays: " + Msg.moneyStr(PlinkoMath.payout(stake, r, k)) + " §8(bet " + Money.format(stake) + ")");
		if (k == landedBucket) {
			lore.add("");
			lore.add("§a▲ Your last ball landed here");
		}
		GuiElementBuilder b = Gui.item(item, multColor(h) + PlinkoMath.formatHundredths(h), lore);
		if (k == landedBucket) b.glow();
		return b;
	}

	private static String multColor(int h) {
		return h >= 1000 ? "§6§l" : h >= 300 ? "§a" : h >= 150 ? "§e" : h >= 100 ? "§6" : "§c";
	}

	private GuiElementBuilder riskItem(Risk r) {
		Item icon = switch (r) {
			case LOW -> Items.LIME_WOOL;
			case MEDIUM -> Items.YELLOW_WOOL;
			case HIGH -> Items.RED_WOOL;
		};
		boolean selected = r == risk;
		List<String> lore = new ArrayList<>();
		lore.add("§7Up to §a" + PlinkoMath.formatHundredths(PlinkoMath.maxHundredths(r)) + " §7· lowest §c"
			+ PlinkoMath.formatHundredths(r.hundredths(PlinkoMath.ROWS / 2)));
		lore.add("§7RTP §f" + PlinkoMath.formatPercent(PlinkoMath.theoreticalRtp(r)));
		lore.add("");
		lore.add(selected ? "§aSelected" : "§eClick to select");
		GuiElementBuilder b = Gui.button(icon, r.color() + (selected ? "§l" : "") + r.label() + " risk", () -> setRisk(r), lore.toArray(String[]::new));
		if (selected) b.glow();
		return b;
	}

	private static GuiElementBuilder helpItem() {
		return Gui.item(Items.KNOWLEDGE_BOOK, "§eHow to play",
			"§7Pick a risk level and your bet, then",
			"§7press §aDrop§7. The ball bounces off 8 rows",
			"§7of pegs and lands in one of 9 slots –",
			"§7you get §fbet × the slot's multiplier§7.",
			"§7The middle is likely, the edges are rare",
			"§7but pay the most.",
			"",
			"§7The result is fixed when you drop;",
			"§7closing the window pays it out at once.",
			"",
			"§8" + Plinko.USAGE,
			"§8Daily loss limit: /casino limit");
	}

	private static GuiElementBuilder payoutsItem() {
		List<String> lore = new ArrayList<>();
		for (Risk r : Risk.values()) {
			StringBuilder line = new StringBuilder("§f");
			for (int k = 0; k < PlinkoMath.BUCKETS; k++) {
				if (k > 0) line.append(" §8· §f");
				line.append(PlinkoMath.formatHundredths(r.hundredths(k)).substring(1));
			}
			lore.add(r.color() + r.label() + " §8(RTP " + PlinkoMath.formatPercent(PlinkoMath.theoreticalRtp(r)) + ")");
			lore.add(line.toString());
		}
		StringBuilder chances = new StringBuilder("§8Chance per slot: ");
		for (int k = 0; k < PlinkoMath.BUCKETS; k++) {
			if (k > 0) chances.append(" · ");
			chances.append(String.format(java.util.Locale.ROOT, "%.1f", PlinkoMath.chance(k) * 100));
		}
		lore.add("");
		lore.add(chances + " %");
		return Gui.item(Items.BOOK, "§ePayout tables", lore);
	}

	private GuiElementBuilder autoItem() {
		if (autoLeft > 0 || autoWait >= 0) {
			return Gui.button(Items.COMPARATOR, "§cStop auto §7(" + (autoLeft + (animTick >= 0 ? 1 : 0)) + " left)", this::toggleAuto,
				"§7The ball in the air still lands.");
		}
		return Gui.button(Items.REPEATER, "§bAuto ×" + AUTO_DROPS, this::toggleAuto,
			"§7Drops " + AUTO_DROPS + " balls in a row with the",
			"§7current bet and risk. Stops on any",
			"§7error, when you click again or close.");
	}

	private void renderBar() {
		boolean idle = !busy();
		boolean auto = autoLeft > 0 || autoWait >= 0;
		setSlot(SLOT_BACK, idle ? Gui.button(Items.ARROW, "§c« Back", this::back, "§7Casino menu") : Gui.filler());
		long min = Casino.cfg().minBet;
		long max = Plinko.maxStake(id, risk);
		List<String> info = new ArrayList<>();
		info.add("§7Bet: " + Msg.moneyStr(stake) + " §8· " + risk.color() + risk.label() + " risk");
		info.add("§7Min " + Msg.moneyStr(min) + " §8· §7Max " + (max > 0 ? Msg.moneyStr(max) : "§clocked"));
		info.add("§7Balance: " + Msg.moneyStr(EconomyService.balance(id)));
		if (!lastResult.isEmpty()) info.add("§7Last ball: " + lastResult);
		if (!history.isEmpty()) info.add("§7Recent: " + String.join(" §8· ", history));
		setSlot(SLOT_STAKE, Gui.item(Items.GOLD_NUGGET, "§6Bet: §e" + Money.format(stake), info));
		if (idle) {
			setSlot(BAR + 1, Gui.button(Items.RED_DYE, "§c÷2", () -> setStake(stake / 2), "§7Halve the bet"));
			setSlot(BAR + 2, Gui.button(Items.RED_DYE, "§c−" + Money.formatShort(10_000), t -> setStake(stake - (t.isRight ? 100_000 : 10_000)), "§7Left: −" + Money.formatShort(10_000), "§7Right: −" + Money.formatShort(100_000)));
			setSlot(BAR + 5, Gui.button(Items.LIME_DYE, "§a+" + Money.formatShort(10_000), t -> setStake(stake + (t.isRight ? 100_000 : 10_000)), "§7Left: +" + Money.formatShort(10_000), "§7Right: +" + Money.formatShort(100_000)));
			setSlot(BAR + 6, Gui.button(Items.LIME_DYE, "§a×2", () -> setStake(stake * 2), "§7Double the bet"));
			setSlot(BAR + 7, Gui.button(Items.GOLD_BLOCK, "§6Max", this::setMaxStake, "§7Highest possible bet"));
			setSlot(SLOT_DROP, Gui.button(Items.LIME_CONCRETE, "§a§lDrop §7(" + Money.format(stake) + ")", this::drop,
				"§7Risk: " + risk.color() + risk.label(),
				"§7Max win: §a" + PlinkoMath.formatHundredths(PlinkoMath.maxHundredths(risk)) + " §8= " + Msg.moneyStr(PlinkoMath.potential(stake, risk))));
		} else {
			for (int i : new int[] {1, 2, 5, 6, 7}) setSlot(BAR + i, Gui.filler());
			setSlot(SLOT_DROP, auto
				? Gui.button(Items.CLOCK, "§7Auto running … §8(" + (autoLeft + (animTick >= 0 ? 1 : 0)) + " left)", this::toggleAuto, "§cClick to stop auto")
				: Gui.item(Items.CLOCK, "§7Ball is dropping …"));
		}
		setSlot(SLOT_CLOSE, Gui.item(Items.BARRIER, "§cClose", round != null ? "§7The ball's result is paid out" : "").setCallback((i, t, a, g) -> {
			if (Gui.isPlainClick(t)) close();
		}));
	}
}
