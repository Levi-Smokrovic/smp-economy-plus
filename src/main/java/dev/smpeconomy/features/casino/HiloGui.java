package dev.smpeconomy.features.casino;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.features.casino.CasinoData.Round;

/**
 * Higher/lower with item cards. The next card never has the same value. Each right guess multiplies by 12 / winning
 * values, house edge once on payout. Closing = cash out.
 */
final class HiloGui extends CasinoGameGui {
	static final String TITLE = "§8Higher/Lower";
	static final int SLOT_CARD = 22;
	static final int SLOT_LOWER = 20;
	static final int SLOT_HIGHER = 24;
	static final int SLOT_INFO = 13;
	static final int SLOT_CASHOUT = 31;
	private static final int FLIP_TICKS = 12;
	private static final String[] SUITS = {"§f♠", "§c♥", "§c♦", "§f♣"};

	/** Card shown (the old one during the animation). */
	private int shownCard = -1;
	private int shownSuit;
	private Casino.Step pendingStep;
	private Round finished;

	HiloGui(ServerPlayer player) {
		super(player, Game.HILO, 5, TITLE);
	}

	static void open(ServerPlayer player, Long stake) {
		HiloGui gui = new HiloGui(player);
		if (stake != null) gui.stake = stake;
		if (!gui.openGui()) return;
		if (stake != null) gui.startRound();
	}

	@Override
	protected void startRound() {
		if (busy() || round != null) return;
		Casino.Start s = Casino.startHilo(player.getUUID(), player.getGameProfile().name(), stake, Casino.RNG);
		if (!s.ok()) {
			startFailed(s.error());
			return;
		}
		finished = null;
		bindRound(s.round());
		shownCard = s.round().card;
		shownSuit = s.round().suit;
		Gui.sound(player, net.minecraft.sounds.SoundEvents.NOTE_BLOCK_CHIME, 1.0f);
		render();
	}

	private void guess(boolean higher) {
		if (busy() || round == null) return;
		Casino.Step step = Casino.hiloGuess(round, higher, Casino.RNG);
		if (step == Casino.Step.INVALID) return;
		if (step == Casino.Step.BLOCKED) {
			if (Casino.hiloAllowed(round, higher)) {
				notCovered(Casino.hiloNextPayout(round, higher));
				return;
			}
			Msg.error(player, "That would exceed the max multiplier – cash out.");
			Gui.failSound(player);
			return;
		}
		pendingStep = step;
		animTick = 0;
		render();
	}

	@Override
	protected void animate(int t) {
		if (t < FLIP_TICKS) {
			if (t % 2 == 0) {
				ThreadLocalRandom rnd = ThreadLocalRandom.current();
				setSlot(SLOT_CARD, card(CasinoMath.hiloRank(rnd), rnd.nextInt(4), "§7…"));
				tickSound(1.0f + t / 20f);
			}
			return;
		}
		animTick = -1;
		Casino.Step step = pendingStep;
		pendingStep = null;
		if (round == null) return;
		shownCard = round.card;
		shownSuit = round.suit;
		if (step == Casino.Step.WIN) {
			Gui.sound(player, net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING, Math.min(2.0f, 1.0f + round.correct * 0.1f));
		} else if (step == Casino.Step.LOSE) {
			loseSound();
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
			Msg.error(player, "Guess right at least once first.");
			Gui.failSound(player);
			return;
		}
		finished = r;
		finishRound();
		Gui.successSound(player);
		render();
	}

	static String rankName(int rank) {
		return switch (rank) {
			case 11 -> "Jack";
			case 12 -> "Queen";
			case 13 -> "King";
			case 14 -> "Ace";
			default -> String.valueOf(rank);
		};
	}

	static Item rankItem(int rank) {
		return switch (rank) {
			case 11 -> Items.IRON_INGOT;
			case 12 -> Items.GOLD_INGOT;
			case 13 -> Items.DIAMOND;
			case 14 -> Items.NETHER_STAR;
			default -> Items.PAPER;
		};
	}

	static GuiElementBuilder card(int rank, int suit, String... lore) {
		GuiElementBuilder b = Gui.item(rankItem(rank), SUITS[Math.floorMod(suit, 4)] + " §f" + rankName(rank), lore);
		if (rank <= 10) b.setCount(rank);
		return b;
	}

	@Override
	protected void render() {
		for (int i = 0; i < 36; i++) setSlot(i, Gui.filler());
		Round r = round;
		Round shown = r != null ? r : finished;
		// history (up to the last 7 cards) in row 0
		if (shown != null) {
			List<Integer> h = shown.history;
			int from = Math.max(0, h.size() - 7);
			for (int i = from; i < h.size(); i++) {
				int code = h.get(i);
				setSlot(1 + (i - from), card(code / 4, code % 4, "§8Previous card"));
			}
		}
		if (shown != null && shownCard >= CasinoMath.HILO_MIN) {
			GuiElementBuilder c = card(shownCard, shownSuit, r != null ? "§7Current card" : "§7Last card");
			if (r == null && finished != null && finished.payout > 0) c.glow();
			setSlot(SLOT_CARD, c);
		} else {
			setSlot(SLOT_CARD, Gui.item(Items.MAP, "§7Face-down deck", "§8Start a round below"));
		}
		int edgeBp = shown != null ? shown.edgeBp : Casino.cfg().hiloEdgeBp();
		boolean playing = r != null && Casino.isOpen(r) && animTick < 0;
		if (playing) {
			setSlot(SLOT_HIGHER, guessButton(r, true));
			setSlot(SLOT_LOWER, guessButton(r, false));
		}
		double mult = shown != null && shown.correct > 0
			? CasinoMath.hiloMultiplier(new BigInteger(shown.num), new BigInteger(shown.den), edgeBp) : 0;
		setSlot(SLOT_INFO, Gui.item(Items.PAPER, "§eMultiplier: §a" + (mult > 0 ? CasinoMath.formatMultiplier(mult) : "×0"),
			"§7Bet: " + Msg.moneyStr(shown != null ? shown.stake : stake),
			"§7Correct guesses: §f" + (shown != null ? shown.correct : 0),
			"§7The next card never has the same value.",
			"§8House edge " + Casino.fmtPercent(edgeBp / 100.0)));
		if (playing && Casino.canCashOut(r)) {
			setSlot(SLOT_CASHOUT, Gui.button(Items.EMERALD_BLOCK, "§a§lCash Out §a" + Money.format(Casino.cashOutValue(r)), this::cashOut,
				"§7" + CasinoMath.formatMultiplier(mult) + " on " + Money.format(r.stake)));
		} else if (r != null) {
			setSlot(SLOT_CASHOUT, Gui.item(Items.GRAY_DYE, "§7Cash Out", "§7Guess right once first"));
		}
		renderStakeBar();
	}

	private GuiElementBuilder guessButton(Round r, boolean higher) {
		int hits = CasinoMath.hiloCount(r.card, higher);
		String name = higher ? "§a§l▲ Higher" : "§c§l▼ Lower";
		Item item = higher ? Items.LIME_CONCRETE : Items.RED_CONCRETE;
		if (hits <= 0) return Gui.item(Items.BARRIER, "§7" + (higher ? "Higher" : "Lower"), "§8Not possible");
		if (!Casino.hiloAllowed(r, higher)) return Gui.item(Items.BARRIER, "§7" + (higher ? "Higher" : "Lower"), "§cAbove the max multiplier");
		if (!Casino.hiloCovered(r, higher)) return Gui.item(Items.BARRIER, "§7" + (higher ? "Higher" : "Lower"), "§cThe Treasury can't cover this win right now");
		BigInteger[] next = CasinoMath.hiloStep(new BigInteger(r.num), new BigInteger(r.den), r.card, higher);
		double after = CasinoMath.hiloMultiplier(next[0], next[1], r.edgeBp);
		long chance = Math.round(CasinoMath.hiloChance(r.card, higher) * 100);
		return Gui.button(item, name, () -> guess(higher),
			"§7Chance: §f" + chance + "%",
			"§7If right: §e" + CasinoMath.formatMultiplier(after) + " §8(" + Money.format(CasinoMath.hiloPayout(r.stake, next[0], next[1], r.edgeBp)) + ")");
	}

	@Override
	protected GuiElementBuilder idleAction() {
		return startButton("§a§lDraw a Card §7(" + Money.format(stake) + ")", "§7Higher or lower? Every correct", "§7guess raises the multiplier.");
	}
}
