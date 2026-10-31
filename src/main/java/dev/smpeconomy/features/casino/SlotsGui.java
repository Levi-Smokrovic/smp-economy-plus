package dev.smpeconomy.features.casino;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.features.casino.CasinoData.Round;

/**
 * Slots: 3 reels, payline in the middle. The result (payline + payout) is fixed and stored before the reels spin; the
 * animation is display only.
 */
final class SlotsGui extends CasinoGameGui {
	static final String TITLE = "§8Slots";
	static final Item[] SYMBOL_ITEMS = {Items.COAL, Items.IRON_INGOT, Items.GOLD_INGOT, Items.LAPIS_LAZULI, Items.EMERALD, Items.DIAMOND};
	static final String[] SYMBOL_COLORS = {"§8", "§f", "§6", "§9", "§a", "§b"};
	/** Reel columns; rows top, middle, bottom. */
	private static final int[] REEL_COLS = {2, 4, 6};
	private static final int[] REEL_ROWS = {1, 2, 3};
	/** Tick at which reel i stops. */
	private static final int[] STOP_TICKS = {16, 24, 32};
	private static final int END_TICK = 36;

	/** Visible symbols [reel][top, middle, bottom]. */
	private final int[][] visible = new int[3][3];
	private int[] result;
	private boolean showWin;

	SlotsGui(ServerPlayer player) {
		super(player, Game.SLOTS, 5, TITLE);
		ThreadLocalRandom r = ThreadLocalRandom.current();
		for (int[] reel : visible) for (int i = 0; i < 3; i++) reel[i] = r.nextInt(SYMBOL_ITEMS.length);
	}

	static void open(ServerPlayer player, Long stake) {
		SlotsGui gui = new SlotsGui(player);
		if (stake != null) gui.stake = stake;
		if (!gui.openGui()) return;
		if (stake != null) gui.startRound();
	}

	@Override
	protected void startRound() {
		if (busy() || round != null) return;
		Casino.Start s = Casino.startSlots(player.getUUID(), player.getGameProfile().name(), stake, Casino.RNG);
		if (!s.ok()) {
			startFailed(s.error());
			return;
		}
		bindRound(s.round());
		result = s.round().reels.clone();
		showWin = false;
		animTick = 0;
		render();
	}

	@Override
	protected void animate(int t) {
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		boolean shifted = false;
		int changed = 0; // bitmask of the reels whose symbols changed this tick
		for (int reel = 0; reel < 3; reel++) {
			if (t < STOP_TICKS[reel]) {
				if (t % 2 == 0) {
					visible[reel][2] = visible[reel][1];
					visible[reel][1] = visible[reel][0];
					visible[reel][0] = CasinoMath.slotSymbol(rnd);
					shifted = true;
					changed |= 1 << reel;
				}
			} else if (t == STOP_TICKS[reel]) {
				visible[reel][0] = CasinoMath.slotSymbol(rnd);
				visible[reel][1] = result[reel];
				visible[reel][2] = CasinoMath.slotSymbol(rnd);
				Gui.sound(player, net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BASS, 1.0f + reel * 0.2f);
				changed |= 1 << reel;
			}
		}
		if (shifted) tickSound(1.2f + (t % 6) * 0.1f);
		if (t >= END_TICK) {
			animTick = -1;
			Round r = round;
			Casino.Settled s = finishRound();
			if (s != null && s.payout() > 0) {
				showWin = true;
				winSound();
			} else if (r != null) {
				loseSound();
			}
			render();
			return;
		}
		// rebuild only changed reels (odd ticks and stopped reels look the same as before)
		if (changed != 0) renderReels(changed);
	}

	@Override
	protected void render() {
		for (int i = 0; i < 36; i++) setSlot(i, Gui.filler());
		setSlot(4, paytable());
		setSlot(18, Gui.item(Items.SPECTRAL_ARROW, "§ePayline »"));
		setSlot(26, Gui.item(Items.SPECTRAL_ARROW, "§e« Payline"));
		renderReels();
		renderStakeBar();
	}

	private void renderReels() {
		renderReels(0b111);
	}

	/** Sets the reels in {@code reelMask} (bit i = reel i). */
	private void renderReels(int reelMask) {
		boolean pairOrTriple = showWin && result != null && CasinoMath.slotsTenths(result) > 0;
		for (int reel = 0; reel < 3; reel++) {
			if ((reelMask & (1 << reel)) == 0) continue;
			for (int row = 0; row < 3; row++) {
				int sym = visible[reel][row];
				GuiElementBuilder b = Gui.item(SYMBOL_ITEMS[sym], SYMBOL_COLORS[sym] + CasinoMath.SLOT_NAMES[sym]);
				if (row == 1 && pairOrTriple && isWinningSymbol(sym)) b.glow();
				setSlot(REEL_ROWS[row] * 9 + REEL_COLS[reel], b);
			}
		}
	}

	private boolean isWinningSymbol(int sym) {
		int count = 0;
		for (int s : result) if (s == sym) count++;
		return count >= 2;
	}

	static GuiElementBuilder paytable() {
		List<String> lore = new ArrayList<>();
		lore.add("§7Only the middle row counts.");
		lore.add("");
		for (int i = CasinoMath.SLOT_NAMES.length - 1; i >= 0; i--) {
			lore.add(SYMBOL_COLORS[i] + "3× " + CasinoMath.SLOT_NAMES[i] + " §7→ §a" + CasinoMath.formatTenths(CasinoMath.SLOT_TRIPLE_TENTHS[i]));
		}
		lore.add("§b2× Diamond §7→ §a" + CasinoMath.formatTenths(CasinoMath.SLOT_PAIR_TENTHS[5]));
		lore.add("§7Any other pair §7→ §a" + CasinoMath.formatTenths(CasinoMath.SLOT_PAIR_TENTHS[0]));
		lore.add("");
		lore.add("§8Payout rate: ~" + String.format(java.util.Locale.ROOT, "%.1f", CasinoMath.slotsTheoreticalRtp() * 100) + "%");
		return Gui.item(Items.BOOK, "§ePaytable", lore);
	}

	@Override
	protected GuiElementBuilder idleAction() {
		return startButton("§a§lSpin §7(" + Money.format(stake) + ")", "§7Top prize: §a3× Diamond ×25");
	}
}
