package dev.smpeconomy.features.casino;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.features.casino.CasinoData.Round;

/**
 * Coin flip against the house (48 %). After a win: double or nothing (as often as the Treasury covers twice the pot) or
 * cash out. Closing = cash out.
 */
final class CoinflipGui extends CasinoGameGui {
	static final String TITLE = "§8Coinflip vs. the House";
	private static final int SLOT_YOU = 10;
	private static final int SLOT_COIN = 13;
	private static final int SLOT_HOUSE = 16;
	private static final int SLOT_CASHOUT = 20;
	private static final int SLOT_INFO = 22;
	private static final int SLOT_DOUBLE = 24;

	/** Coin shows the player (true) or the house (false). */
	private boolean coinShowsPlayer = true;
	/** Ticks at which the coin flips (the last one is the result). */
	private List<Integer> flipTicks = List.of();
	private boolean resultWin;

	CoinflipGui(ServerPlayer player) {
		super(player, Game.COINFLIP, 4, TITLE);
	}

	/** {@code /coinflip house [amount]}: opens the GUI; with an amount, flips at once. */
	static void open(ServerPlayer player, Long stake) {
		CoinflipGui gui = new CoinflipGui(player);
		if (stake != null) gui.stake = stake;
		if (!gui.openGui()) return;
		if (stake != null) gui.startRound();
	}

	/** Double or nothing after a PvP coin flip: stake = amount just won. */
	static void openPvp(ServerPlayer player, long cents) {
		CoinflipGui gui = new CoinflipGui(player);
		gui.stake = cents;
		if (!gui.openGui()) return;
		gui.start(true);
	}

	@Override
	protected void startRound() {
		start(false);
	}

	private void start(boolean pvp) {
		if (busy() || round != null) return;
		Casino.Start s = Casino.startCoinflip(player.getUUID(), player.getGameProfile().name(), stake, pvp, Casino.RNG);
		if (!s.ok()) {
			startFailed(s.error());
			return;
		}
		bindRound(s.round());
		beginAnimation(s.round().lastWin);
	}

	private void beginAnimation(boolean win) {
		resultWin = win;
		// fast to slow; number of flips chosen so the coin lands on the result
		List<Integer> ticks = new ArrayList<>(List.of(2, 4, 6, 8, 10, 12, 14, 17, 20, 24, 29));
		boolean endsOnPlayer = (ticks.size() % 2 == 0) == coinShowsPlayer;
		if (endsOnPlayer != win) ticks.add(35);
		flipTicks = ticks;
		animTick = 0;
		render();
	}

	@Override
	protected void animate(int t) {
		if (flipTicks.contains(t)) {
			coinShowsPlayer = !coinShowsPlayer;
			tickSound(0.8f + t / 40f);
			renderCoin();
		}
		if (t >= flipTicks.get(flipTicks.size() - 1) + 6) {
			animTick = -1;
			onAnimationEnd();
		}
	}

	private void onAnimationEnd() {
		if (round == null) return;
		if (!resultWin) {
			loseSound();
			finishRound();
		} else {
			winSound();
			if (!Casino.canDouble(round)) Msg.info(player, "§7Max win reached – cash out your pot.");
		}
		render();
	}

	private void cashOut() {
		if (busy() || round == null) return;
		if (!Casino.cashOut(round)) return;
		finishRound();
		Gui.successSound(player);
		render();
	}

	private void doubleOrNothing() {
		if (busy() || round == null) return;
		Casino.Step step = Casino.coinflipDouble(round, Casino.RNG);
		if (step == Casino.Step.BLOCKED) {
			Msg.error(player, "The Treasury can't cover " + Msg.moneyStr(round.pot * 2) + "§c – please cash out.");
			Gui.failSound(player);
			render();
			return;
		}
		if (step != Casino.Step.WIN && step != Casino.Step.LOSE) return;
		beginAnimation(step == Casino.Step.WIN);
	}

	@Override
	protected void render() {
		for (int i = 0; i < 27; i++) setSlot(i, Gui.filler());
		setSlot(SLOT_YOU, new GuiElementBuilder(Items.PLAYER_HEAD).setProfile(player.getGameProfile().name())
			.setName(Msg.legacy("§aYou")).hideDefaultTooltip());
		setSlot(SLOT_HOUSE, Gui.item(Items.GOLD_BLOCK, "§6The House", "§7Treasury"));
		renderCoin();
		Round r = round;
		boolean choosing = r != null && animTick < 0 && r.wins >= 1 && Casino.isOpen(r);
		if (r != null) {
			setSlot(SLOT_INFO, Gui.item(Items.PAPER, "§ePot: §a" + Money.format(r.pot),
				"§7Bet: " + Msg.moneyStr(r.stake),
				"§7Flips won: §f" + r.wins,
				"§7Win chance per flip: §f48%"));
		} else {
			setSlot(SLOT_INFO, Gui.item(Items.PAPER, "§eCoinflip vs. the House",
				"§7Win chance: §f48%",
				"§7Win: §aBet ×2",
				"§7Then: §6Double or Nothing §7or §aCash Out",
				"§8Closing = automatic cash out"));
		}
		if (choosing) {
			setSlot(SLOT_CASHOUT, Gui.button(Items.EMERALD_BLOCK, "§a§lCash Out §a" + Money.format(r.pot), this::cashOut,
				"§7Cash out your pot now"));
			if (Casino.canDouble(r)) {
				setSlot(SLOT_DOUBLE, Gui.button(Items.SUNFLOWER, "§6§lDouble or Nothing", this::doubleOrNothing,
					"§7Bet your pot of " + Msg.moneyStr(r.pot),
					"§7Win: §a" + Money.format(r.pot * 2) + " §7(48%)",
					"§7Lose: §ceverything"));
			} else {
				setSlot(SLOT_DOUBLE, Gui.item(Items.BARRIER, "§7Double or Nothing", "§cMax win reached", "§7(Treasury coverage)"));
			}
		}
		renderStakeBar();
	}

	private void renderCoin() {
		GuiElementBuilder coin = coinShowsPlayer
			? new GuiElementBuilder(Items.PLAYER_HEAD).setProfile(player.getGameProfile().name()).setName(Msg.legacy("§aYou")).hideDefaultTooltip()
			: Gui.item(Items.GOLD_BLOCK, "§6House");
		if (animTick < 0 && round == null && !lastResult.isEmpty()) coin.glow();
		setSlot(SLOT_COIN, coin);
	}

	@Override
	protected GuiElementBuilder idleAction() {
		return startButton("§a§lFlip §7(" + Money.format(stake) + ")", "§748% chance to win §aBet ×2");
	}
}
