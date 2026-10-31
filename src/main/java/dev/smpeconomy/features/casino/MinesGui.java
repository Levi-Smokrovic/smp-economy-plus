package dev.smpeconomy.features.casino;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Items;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.features.casino.CasinoData.Round;

/**
 * Mines: 5×5 grid, bombs are placed and stored at the start. Each safe tile raises the multiplier, cash-out any time
 * (after one tile), a bomb loses the stake. Closing = cash out.
 */
final class MinesGui extends CasinoGameGui {
	static final String TITLE = "§8Mines";
	static final int SLOT_BOMBS = 9;
	static final int SLOT_INFO = 17;
	static final int SLOT_CASHOUT = 26;
	private static final int REVEAL_TICKS = 4;

	private int bombs;
	/** Last clicked tile during the short reveal animation (-1 = none). */
	private int pendingTile = -1;
	private Casino.Step pendingStep;
	/** Final view of the last round (all bombs visible). */
	private Round finished;

	MinesGui(ServerPlayer player) {
		super(player, Game.MINES, 6, TITLE);
		CasinoData.PlayerStats s = Casino.peekStats(player.getUUID());
		int last = s == null ? 0 : s.lastBombs;
		this.bombs = last >= 1 && last <= 24 ? last : Casino.cfg().minesDefaultBombs;
	}

	/** Tile index (0–24) to GUI slot. */
	static int slotOf(int tile) {
		return tile / 5 * 9 + 2 + tile % 5;
	}

	static void open(ServerPlayer player, Long stake, Integer bombs) {
		MinesGui gui = new MinesGui(player);
		if (stake != null) gui.stake = stake;
		if (bombs != null) gui.bombs = bombs;
		else if (stake != null) gui.bombs = Casino.cfg().minesDefaultBombs; // /mines <bet> without a count: the default, not the last choice
		if (!gui.openGui()) return;
		if (stake != null) gui.startRound();
	}

	@Override
	protected int bombs() {
		return bombs;
	}

	@Override
	protected void startRound() {
		if (busy() || round != null) return;
		Casino.Start s = Casino.startMines(player.getUUID(), player.getGameProfile().name(), stake, bombs, Casino.RNG);
		if (!s.ok()) {
			startFailed(s.error());
			return;
		}
		CasinoData.PlayerStats st = Casino.peekStats(player.getUUID());
		if (st != null) st.lastBombs = bombs;
		finished = null;
		bindRound(s.round());
		render();
	}

	private void reveal(int tile) {
		if (busy() || round == null) return;
		Casino.Step step = Casino.minesReveal(round, tile);
		if (step == Casino.Step.INVALID) return;
		if (step == Casino.Step.BLOCKED) {
			notCovered(Casino.minesNextPayout(round));
			return;
		}
		pendingTile = tile;
		pendingStep = step;
		animTick = 0;
		tickSound(1.0f);
		render();
	}

	@Override
	protected void animate(int t) {
		if (t < REVEAL_TICKS) return;
		animTick = -1;
		int tile = pendingTile;
		Casino.Step step = pendingStep;
		pendingTile = -1;
		pendingStep = null;
		if (round == null) return;
		if (step == Casino.Step.WIN) {
			Gui.sound(player, SoundEvents.NOTE_BLOCK_PLING, Math.min(2.0f, 0.8f + round.revealed.size() * 0.08f));
		} else if (step == Casino.Step.LOSE) {
			Gui.sound(player, SoundEvents.GENERIC_EXPLODE, 1.0f);
			finished = round;
			finishRound();
		} else if (step == Casino.Step.DONE) {
			finished = round;
			finishRound();
			winSound();
			Msg.info(player, "§7Max multiplier reached – paid out automatically.");
		}
		render();
	}

	private void cashOut() {
		if (busy() || round == null) return;
		Round r = round;
		if (!Casino.cashOut(r)) {
			Msg.error(player, "Reveal at least one tile first.");
			Gui.failSound(player);
			return;
		}
		finished = r;
		finishRound();
		Gui.successSound(player);
		render();
	}

	private void changeBombs(int delta) {
		if (busy() || round != null) return;
		bombs = Math.max(1, Math.min(CasinoMath.MINES_TILES - 1, bombs + delta));
		setStake(stake); // max bet depends on the bomb count
	}

	@Override
	protected void render() {
		for (int i = 0; i < 45; i++) setSlot(i, Gui.filler());
		Round r = round;
		Round shown = r != null ? r : finished;
		int edgeBp = shown != null ? shown.edgeBp : Casino.cfg().minesEdgeBp();
		int b = shown != null ? shown.bombs : bombs;
		int k = shown != null ? shown.revealed.size() : 0;
		for (int tile = 0; tile < CasinoMath.MINES_TILES; tile++) setSlot(slotOf(tile), tileElement(tile, r, shown, edgeBp));

		// bomb choice (idle only)
		if (r == null && animTick < 0) {
			setSlot(SLOT_BOMBS, Gui.button(Items.TNT, "§cBombs: §f" + bombs, t -> changeBombs((t.isRight ? -1 : 1) * (t.shift ? 5 : 1)),
				"§7Left-click: +1 §8· §7Right-click: −1", "§7With Shift: ±5",
				"§7First tile: §e" + CasinoMath.formatMultiplier(CasinoMath.minesMultiplier(bombs, 1, edgeBp))));
		} else {
			setSlot(SLOT_BOMBS, Gui.item(Items.TNT, "§cBombs: §f" + b));
		}
		double now = CasinoMath.minesMultiplier(b, k, edgeBp);
		boolean canNext = shown != null && Casino.isOpen(shown) && k < CasinoMath.MINES_TILES - b;
		double next = CasinoMath.minesMultiplier(b, k + 1, edgeBp);
		boolean covered = canNext && Casino.minesCovered(shown);
		setSlot(SLOT_INFO, Gui.item(Items.PAPER, "§eMultiplier: §a" + (k > 0 ? CasinoMath.formatMultiplier(now) : "×0"),
			"§7Bet: " + Msg.moneyStr(shown != null ? shown.stake : stake),
			"§7Safe tiles: §f" + k + "§7/§f" + (CasinoMath.MINES_TILES - b),
			!canNext ? "§8–" : covered ? "§7Next tile: §e" + CasinoMath.formatMultiplier(next)
				: "§cNext tile: the Treasury can't cover it right now",
			"§8House edge " + Casino.fmtPercent(edgeBp / 100.0)));
		if (r != null && Casino.canCashOut(r) && animTick < 0) {
			long value = Casino.cashOutValue(r);
			setSlot(SLOT_CASHOUT, Gui.button(Items.EMERALD_BLOCK, "§a§lCash Out §a" + Money.format(value), this::cashOut,
				"§7" + CasinoMath.formatMultiplier(now) + " on " + Money.format(r.stake)));
		} else if (r != null) {
			setSlot(SLOT_CASHOUT, Gui.item(Items.GRAY_DYE, "§7Cash Out", "§7Reveal a tile first"));
		}
		renderStakeBar();
	}

	private GuiElementBuilder tileElement(int tile, Round open, Round shown, int edgeBp) {
		if (tile == pendingTile) return Gui.item(Items.YELLOW_STAINED_GLASS_PANE, "§e…");
		if (shown == null) return Gui.item(Items.GRAY_CONCRETE, "§7Tile", "§8Start a round below");
		boolean revealed = shown.revealed.contains(tile);
		if (revealed) return Gui.item(Items.EMERALD, "§aSafe");
		if (open != null) {
			// covered: identical for bombs and safe tiles (reveals nothing)
			return Gui.button(Items.GRAY_CONCRETE, "§7Reveal tile", () -> reveal(tile),
				"§7Next multiplier: §e" + CasinoMath.formatMultiplier(CasinoMath.minesMultiplier(shown.bombs, shown.revealed.size() + 1, edgeBp)));
		}
		// round over: show everything
		if (shown.mines.contains(tile)) {
			GuiElementBuilder bomb = Gui.item(Items.TNT, tile == shown.boom ? "§c§lBOOM!" : "§cBomb");
			if (tile == shown.boom) bomb.glow();
			return bomb;
		}
		return Gui.item(Items.WHITE_STAINED_GLASS_PANE, "§7(safe)");
	}

	@Override
	protected GuiElementBuilder idleAction() {
		return startButton("§a§lStart §7(" + Money.format(stake) + ", " + bombs + (bombs == 1 ? " bomb)" : " bombs)"),
			"§7Reveal tiles, the multiplier rises,", "§7cash out anytime. Bomb = bet lost.");
	}
}
