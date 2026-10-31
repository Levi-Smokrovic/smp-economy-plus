package dev.smpeconomy.features.casino.games.blackjack;

import java.util.UUID;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.DataStore;
import dev.smpeconomy.features.casino.Casino;
import dev.smpeconomy.features.casino.CasinoData.Round;
import dev.smpeconomy.features.casino.games.blackjack.BlackjackRules.State;

/**
 * Game logic without GUI. Stake via {@link Casino#startExt}, double and split stakes via {@link Casino#extAddStake},
 * settlement via {@link Casino#settle}. The blackjack state (seed, cards, bets) is stored as JSON in {@link Round#ext} after
 * every move, before anything is shown. Closing the window, disconnecting, a restart or the idle timeout settle an open round as if
 * the player stood on every remaining hand, the dealer plays from the stored shoe, so the result is fixed.
 */
public final class BlackjackGame implements Casino.ExtGame {
	public static final String ID = "blackjack";
	public static final String LABEL = "§2Blackjack";
	static final BlackjackGame GAME = new BlackjackGame();

	private BlackjackGame() {}

	/** The one entry point (from the casino feature's {@code register()}): game, commands, selftests. */
	public static void register() {
		Casino.registerGame(GAME);
		BlackjackCommands.register();
		BlackjackSelfTests.register();
	}

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String colored() {
		return LABEL;
	}

	@Override
	public long potential(long stake) {
		return BlackjackRules.potential(stake, BlackjackRules.COMMISSION_BP);
	}

	@Override
	public long settleValue(Round r) {
		State s = state(r);
		return s == null ? r.stake : BlackjackRules.payoutIfStandAll(s);
	}

	@Override
	public boolean settleIsRefund(Round r) {
		return state(r) == null;
	}

	/** Game state of a round (copy parsed from {@link Round#ext}); null = missing or inconsistent (→ refund on settle). */
	public static State state(Round r) {
		if (r == null || !ID.equals(r.game) || r.ext == null || r.ext.isBlank()) return null;
		try {
			State s = DataStore.gson().fromJson(r.ext, State.class);
			if (s == null) return null;
			s.fixNulls();
			if (s.hands.isEmpty() || s.dealer.size() < 2 || s.totalBet() != r.stake) return null;
			return s;
		} catch (RuntimeException e) {
			SmpEconomy.LOG.error("[SMPEco] Blackjack: state of round {} unreadable ({}) – settles as a refund", r.id, e.toString());
			return null;
		}
	}

	private static void store(Round r, State s) {
		r.ext = DataStore.gson().toJson(s);
	}

	/** After every move: finished → payout fixed (decided); otherwise reserve what the visible hands can still pay. */
	private static void sync(Round r, State s) {
		if (s.finished) Casino.extDecide(r, s.payout());
		else Casino.extStep(r, BlackjackRules.maxPayoutNow(s));
	}

	/** Entry for the /casino hub (its slot is CasinoMenus.SLOT_BLACKJACK). */
	public static eu.pb4.sgui.api.elements.GuiElementBuilder hubButton(net.minecraft.server.level.ServerPlayer player) {
		return BlackjackGui.hubButton(player);
	}

	/** Highest stake the casino would accept right now (without the player's balance), 0 = locked. */
	public static long maxStake(UUID player) {
		return Casino.maxStake(player, GAME);
	}

	/**
	 * New round: admission + stake (casino), then the shoe seed is stored in the round state and the cards are dealt;
	 * everything is saved together with the stake before the GUI shows a card. Naturals decide the round at once.
	 */
	public static Casino.Start start(UUID player, String name, long stake, long seed) {
		Casino.Start st = Casino.startExt(player, name, GAME, stake);
		if (!st.ok()) return st;
		Round r = st.round();
		State s;
		try {
			s = BlackjackRules.deal(seed, stake, BlackjackRules.COMMISSION_BP);
		} catch (RuntimeException e) {
			SmpEconomy.LOG.error("[SMPEco] Blackjack: deal failed – stake refunded", e);
			Casino.settle(r.id, "error"); // no state: refund
			return new Casino.Start(null, "Something went wrong – your stake was refunded.");
		}
		store(r, s);
		sync(r, s);
		return st;
	}

	public enum Move { HIT, STAND, DOUBLE, SPLIT }

	/**
	 * Plays a move on the active hand. Double/split first collect the extra stake (daily loss limit, balance, Treasury
	 * coverage of the higher possible win, checked before any card is drawn). Returns null when played, otherwise a
	 * message for the player (nothing changed).
	 */
	public static String play(Round r, Move move) {
		if (!Casino.isOpen(r) || !ID.equals(r.game)) return "This round is already over.";
		State s = state(r);
		if (s == null || s.finished) return "This round is already over.";
		switch (move) {
			case HIT -> {
				if (!BlackjackRules.canHit(s)) return "You can't hit this hand.";
				BlackjackRules.hit(s);
			}
			case STAND -> {
				if (!BlackjackRules.canStand(s)) return "You can't stand now.";
				BlackjackRules.stand(s);
			}
			case DOUBLE -> {
				if (!BlackjackRules.canDouble(s)) return "You can only double on your first two cards.";
				String error = Casino.extAddStake(r, s.activeHand().bet, BlackjackRules.maxPayoutAfterDouble(s));
				if (error != null) return error;
				BlackjackRules.doubleDown(s);
			}
			case SPLIT -> {
				if (!BlackjackRules.canSplit(s)) return "You can only split two cards of the same value (once).";
				String error = Casino.extAddStake(r, s.activeHand().bet, BlackjackRules.maxPayoutAfterSplit(s));
				if (error != null) return error;
				BlackjackRules.split(s);
			}
		}
		store(r, s);
		sync(r, s);
		return null;
	}
}
