package dev.smpeconomy.features.casino.games.blackjack;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
import dev.smpeconomy.features.casino.games.blackjack.BlackjackRules.Hand;
import dev.smpeconomy.features.casino.games.blackjack.BlackjackRules.State;

/**
 * Blackjack table (6-row chest GUI, display only, no real slots). Row 0: dealer, row 1: help + status, rows 2–3: your
 * hand(s), row 4: Hit / Stand / Double / Split, row 5: bet bar like the other casino games. Every move is played and saved
 * by {@link BlackjackGame} first; the GUI then only reveals the cards one by one. Closing the window in any way (ESC, other
 * menu, death, disconnect, server stop) settles the round at once via {@link Casino#settle} (open hands stand).
 */
final class BlackjackGui extends SmpGui implements Casino.Window {
	static final String TITLE = "§8Blackjack";
	static final int ROWS = 6;
	static final int SLOT_DEALER = 0;
	static final int DEALER_CARDS = 1;
	static final int SLOT_HELP = 9;
	static final int SLOT_STATUS = 13;
	static final int[] HAND_INFO = {18, 27};
	static final int[] HAND_CARDS = {19, 28};
	static final int MAX_SHOWN = 8;
	static final int SLOT_HIT = 37;
	static final int SLOT_STAND = 39;
	static final int SLOT_DOUBLE = 41;
	static final int SLOT_SPLIT = 43;
	static final int BAR = 45;
	static final int SLOT_STAKE = BAR + 3;
	static final int SLOT_DEAL = BAR + 4;
	static final int SLOT_CLOSE = BAR + 8;
	/** Ticks between two revealed cards. */
	static final int STEP_TICKS = 6;

	private static final String[] SUITS = {"§f♠", "§c♥", "§c♦", "§f♣"};
	private static final String[] SUIT_NAMES = {"Spades", "Hearts", "Diamonds", "Clubs"};
	private static final String[] RANKS = {"Ace", "2", "3", "4", "5", "6", "7", "8", "9", "10", "Jack", "Queen", "King"};

	private final ServerPlayer player;
	private Round round;
	private long stake;
	/** What the table shows: the live round or the last finished one (null = empty table). */
	private State view;
	private final int[] shownHand = new int[2];
	private int shownDealer;
	private boolean holeShown;
	private final ArrayDeque<Step> steps = new ArrayDeque<>();
	/** ≥ 0 while cards are being revealed, otherwise -1. */
	private int animTick = -1;
	private int lastServerTick = Integer.MIN_VALUE;
	/** Result of the last settled round (status/bet bar). */
	private String lastResult = "";

	private BlackjackGui(ServerPlayer player) {
		super(Gui.menuType(ROWS), player, false);
		this.player = player;
		setTitle(Msg.legacy(TITLE));
		long def = defaultStake(player);
		long max = BlackjackGame.maxStake(player.getUUID());
		stake = max > 0 ? Math.min(def, max) : def;
	}

	/** Opens the table; with a bet the first hand is dealt right away. */
	static void open(ServerPlayer player, Long bet) {
		BlackjackGui gui = new BlackjackGui(player);
		if (bet != null) gui.stake = bet;
		gui.render();
		if (!gui.open()) return;
		if (bet != null) gui.deal();
	}

	static GuiElementBuilder hubButton(ServerPlayer player) {
		long max = BlackjackGame.maxStake(player.getUUID());
		return Gui.button(Items.EMERALD, BlackjackGame.LABEL, () -> open(player, null),
			"§7Beat the dealer to §f21§7. Hit, stand,", "§7double or split. Wins pay §f1:1§7,", "§7a Blackjack pays §f3:2§7.",
			"§8/blackjack <bet>",
			max > 0 ? "§7Max bet: " + Msg.moneyStr(max) : "§cLocked right now (Treasury/limit)",
			"", "§eClick to play");
	}

	private static long defaultStake(ServerPlayer player) {
		CasinoData.PlayerStats s = Casino.peekStats(player.getUUID());
		Long last = s == null ? null : s.lastStake.get(BlackjackGame.ID);
		long v = last != null && last > 0 ? last : 10_000; // $100
		return Math.max(Casino.cfg().minBet, v);
	}

	static String percent(int bp) {
		return bp % 100 == 0 ? (bp / 100) + "%" : String.format(Locale.ROOT, "%.1f%%", bp / 100.0);
	}

	private boolean busy() {
		return animTick >= 0 || isEnded();
	}

	private void deal() {
		if (busy() || round != null) return;
		Casino.Start s = BlackjackGame.start(player.getUUID(), player.getGameProfile().name(), stake, Casino.RNG.nextLong());
		if (!s.ok()) {
			Msg.error(player, s.error());
			Gui.failSound(player);
			render();
			return;
		}
		round = s.round();
		Casino.bind(round.id, this);
		view = BlackjackGame.state(round);
		lastResult = "";
		shownHand[0] = 0;
		shownHand[1] = 0;
		shownDealer = 0;
		holeShown = false;
		steps.clear();
		if (view != null) {
			// dealing order: player, dealer up card, player, dealer hole card (face down)
			steps.add(new Step(0, () -> reveal(0)));
			steps.add(new Step(-1, this::revealDealer));
			steps.add(new Step(0, () -> reveal(0)));
			steps.add(new Step(-1, this::revealDealer));
			queueRest();
		}
		startAnimation();
	}

	private void move(BlackjackGame.Move move) {
		if (busy() || round == null) return;
		String error = BlackjackGame.play(round, move);
		if (error != null) {
			Msg.error(player, error);
			Gui.failSound(player);
			render();
			return;
		}
		view = BlackjackGame.state(round);
		if (view == null) { // cannot happen (just stored); settle defensively
			finishRound();
			render();
			return;
		}
		if (move == BlackjackGame.Move.SPLIT) {
			shownHand[0] = 1;
			shownHand[1] = 1;
			Gui.sound(player, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.BOOK_PAGE_TURN), 0.7f);
		}
		if (move == BlackjackGame.Move.DOUBLE) Gui.sound(player, SoundEvents.NOTE_BLOCK_CHIME, 1.2f);
		steps.clear();
		queueRest();
		startAnimation();
	}

	/** Queue every card the table does not show yet (hands in order, then the hole card and the dealer's draws). */
	private void queueRest() {
		if (view == null) return;
		int[] planned = {shownHand[0], shownHand[1]};
		int plannedDealer = shownDealer;
		for (Step st : steps) {
			if (st.hand >= 0) planned[st.hand]++;
			else if (st.hand == -1) plannedDealer++;
		}
		for (int i = 0; i < view.hands.size() && i < 2; i++) {
			int idx = i;
			for (int k = planned[i]; k < view.hands.get(i).cards.size(); k++) steps.add(new Step(idx, () -> reveal(idx)));
		}
		if (view.finished) {
			steps.add(new Step(-2, this::flipHole));
			for (int k = Math.max(plannedDealer, 2); k < view.dealer.size(); k++) steps.add(new Step(-1, this::revealDealer));
		}
	}

	/** Queued animation step: which pile it reveals (hand index, -1 dealer card, -2 hole card) and what it does. */
	private record Step(int hand, Runnable action) {}

	private void reveal(int hand) {
		if (view == null || hand >= view.hands.size()) return;
		shownHand[hand] = Math.min(shownHand[hand] + 1, view.hands.get(hand).cards.size());
		cardSound(1.0f + 0.1f * shownHand[hand]);
	}

	private void revealDealer() {
		if (view == null) return;
		shownDealer = Math.min(shownDealer + 1, view.dealer.size());
		cardSound(0.9f);
	}

	private void flipHole() {
		if (holeShown) return;
		holeShown = true;
		shownDealer = Math.max(shownDealer, Math.min(2, view == null ? 0 : view.dealer.size()));
		Gui.sound(player, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.BOOK_PAGE_TURN), 0.8f);
		Gui.sound(player, SoundEvents.NOTE_BLOCK_HAT, 0.8f);
	}

	private void startAnimation() {
		animTick = steps.isEmpty() ? -1 : 0;
		if (animTick < 0) animationDone();
		render();
	}

	@Override
	public void onTick() {
		if (animTick < 0) return;
		// sgui also calls onTick after click packets: at most one step per server tick
		MinecraftServer server = player.level().getServer();
		int tick = server == null ? -1 : server.getTickCount();
		if (tick == lastServerTick) return;
		lastServerTick = tick;
		animTick++;
		if (animTick % STEP_TICKS != 0) return;
		Step next = steps.poll();
		if (next != null) next.action().run();
		if (steps.isEmpty()) {
			animTick = -1;
			animationDone();
		}
		render();
	}

	/** Everything is visible: a finished round is settled now (the result was fixed and saved before the animation). */
	private void animationDone() {
		if (view == null || round == null || !view.finished) return;
		holeShown = true;
		shownDealer = view.dealer.size();
		for (int i = 0; i < view.hands.size() && i < 2; i++) shownHand[i] = view.hands.get(i).cards.size();
		long bet = view.totalBet();
		long payout = view.payout();
		finishRound();
		if (payout > bet) {
			Gui.sound(player, SoundEvents.NOTE_BLOCK_PLING, 1.4f);
			Gui.sound(player, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.PLAYER_LEVELUP), 1.2f);
			if (view.hands.size() == 1 && BlackjackRules.BLACKJACK.equals(view.hands.get(0).result)) {
				Gui.sound(player, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.AMETHYST_BLOCK_CHIME), 1.0f);
			}
		} else if (payout == bet) {
			Gui.sound(player, SoundEvents.NOTE_BLOCK_CHIME, 1.0f);
		} else {
			Gui.sound(player, SoundEvents.NOTE_BLOCK_BASS, 0.6f);
		}
	}

	private void cardSound(float pitch) {
		Gui.sound(player, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.BOOK_PAGE_TURN), Math.min(2.0f, pitch));
	}

	/** Settles the current round (message comes from {@link Casino#settle}). */
	private void finishRound() {
		if (round == null) return;
		String id = round.id;
		round = null;
		Casino.Settled s = Casino.settle(id, null);
		if (s != null) {
			lastResult = s.refund() ? "§7Bet refunded"
				: s.payout() > s.round().stake ? "§aWon " + Money.format(s.payout()) + " §8(bet " + Money.format(s.round().stake) + ")"
				: s.payout() == s.round().stake ? "§7Push – " + Money.format(s.payout()) + " back"
				: s.payout() > 0 ? "§cBack " + Money.format(s.payout()) + " §8(bet " + Money.format(s.round().stake) + ")"
				: "§cLost " + Money.format(s.round().stake);
		}
	}

	@Override
	protected void onEnded() {
		animTick = -1;
		steps.clear();
		if (round != null) {
			String id = round.id;
			round = null;
			Casino.settle(id, "window closed");
		}
	}

	@Override
	public void expire(String roundId, String why) {
		if (round != null && round.id.equals(roundId)) round = null;
		animTick = -1;
		steps.clear();
		Casino.settle(roundId, why);
		close();
	}

	@Override
	public void detach(String roundId) {
		if (round == null || !round.id.equals(roundId)) return;
		round = null;
		animTick = -1;
		steps.clear();
		view = null;
		lastResult = "§7Round closed by an admin";
		if (isOpen()) render();
	}

	private void render() {
		for (int i = 0; i < BAR; i++) setSlot(i, Gui.filler());
		renderDealer();
		setSlot(SLOT_HELP, helpItem());
		setSlot(SLOT_STATUS, statusItem());
		if (view != null) {
			for (int i = 0; i < view.hands.size() && i < 2; i++) renderHand(i);
		} else {
			setSlot(HAND_INFO[0], head("§7Your hand", "§8Place a bet and press §aDeal§8."));
		}
		renderActions();
		renderBar();
	}

	private boolean playing() {
		return round != null && view != null && !view.finished && animTick < 0 && Casino.isOpen(round);
	}

	private boolean resultsVisible() {
		return view != null && view.finished && animTick < 0;
	}

	private void renderDealer() {
		if (view == null) {
			setSlot(SLOT_DEALER, Gui.item(Items.WITHER_SKELETON_SKULL, "§cDealer", "§7Stands on all 17s.", "§7Peeks for Blackjack."));
			return;
		}
		List<Integer> shown = new ArrayList<>(view.dealer.subList(0, Math.min(shownDealer, view.dealer.size())));
		String line;
		if (shown.isEmpty()) {
			line = "§7…";
		} else if (!holeShown) {
			line = "§7Shows §f" + cardShort(shown.get(0));
		} else {
			int t = BlackjackRules.total(shown);
			line = t > 21 ? "§cBust §8(" + t + ")" : shown.size() == 2 && t == 21 ? "§6§lBlackjack!" : "§7Total §f" + t;
		}
		setSlot(SLOT_DEALER, Gui.item(Items.WITHER_SKELETON_SKULL, "§cDealer §8· " + line, "§7Stands on all 17s.", "§7Peeks for Blackjack."));
		int from = Math.max(0, shown.size() - MAX_SHOWN);
		for (int k = from; k < shown.size(); k++) {
			int slot = DEALER_CARDS + (k - from);
			if (k == 1 && !holeShown) setSlot(slot, Gui.item(Items.MAP, "§8Hidden card", "§7Revealed when your turn is over."));
			else setSlot(slot, card(shown.get(k)));
		}
	}

	private void renderHand(int i) {
		Hand h = view.hands.get(i);
		List<Integer> shown = h.cards.subList(0, Math.min(shownHand[i], h.cards.size()));
		int from = Math.max(0, shown.size() - MAX_SHOWN);
		for (int k = from; k < shown.size(); k++) setSlot(HAND_CARDS[i] + (k - from), card(shown.get(k)));
		String name = view.hands.size() > 1 ? "Hand " + (i + 1) : "Your hand";
		int t = BlackjackRules.total(shown);
		String total = shown.isEmpty() ? "…" : (BlackjackRules.soft(shown) && t < 21 ? t + " §8(soft)" : String.valueOf(t));
		String bet = "§7Bet: " + Msg.moneyStr(h.bet) + (h.doubled ? " §6(doubled)" : "");
		if (resultsVisible()) {
			setSlot(HAND_INFO[i], resultItem(name, h, total, bet));
			return;
		}
		boolean active = !view.finished && view.active == i && animTick < 0;
		boolean busted = shown.size() == h.cards.size() && t > 21;
		if (busted) {
			setSlot(HAND_INFO[i], Gui.item(Items.TNT, "§c" + name + ": Bust! §8(" + t + ")", bet));
			return;
		}
		GuiElementBuilder b = head((active ? "§a▶ " : "§7") + name + ": §f" + total, bet,
			active ? "§eYour move" : h.done ? "§8Standing" : "§8Waiting");
		if (active) b.glow();
		setSlot(HAND_INFO[i], b);
	}

	private GuiElementBuilder resultItem(String name, Hand h, String total, String bet) {
		String paid = "§7Paid: " + Msg.moneyStr(h.payout);
		return switch (h.result) {
			case BlackjackRules.BLACKJACK -> Gui.item(Items.TOTEM_OF_UNDYING, "§6§lBlackjack! §a+" + Money.format(h.payout - h.bet), bet, paid, "§8Pays 3:2").glow();
			case BlackjackRules.WIN -> Gui.item(Items.EMERALD, "§a" + name + ": " + total + " – Win §a+" + Money.format(h.payout - h.bet), bet, paid);
			case BlackjackRules.PUSH -> Gui.item(Items.LIGHT_GRAY_DYE, "§7" + name + ": " + total + " – Push", bet, paid, "§8Bet back");
			case BlackjackRules.BUST -> Gui.item(Items.TNT, "§c" + name + ": Bust §8(" + total + "§8)", bet, paid);
			default -> Gui.item(Items.REDSTONE, "§c" + name + ": " + total + " – Dealer wins", bet, paid);
		};
	}

	private GuiElementBuilder head(String name, String... lore) {
		return Gui.item(Items.PLAYER_HEAD, name, lore).setProfile(player.getGameProfile());
	}

	private GuiElementBuilder statusItem() {
		if (view == null) {
			List<String> lore = new ArrayList<>(List.of("§7Pick your bet below and press §aDeal§7.", "§7Wins pay §f1:1§7, Blackjack §f3:2§7."));
			if (!lastResult.isEmpty()) lore.add("§7Last round: " + lastResult);
			return Gui.item(Items.NAME_TAG, "§ePlace your bet", lore);
		}
		if (animTick >= 0) return Gui.item(Items.CLOCK, "§7Dealing …", "§7Bet: " + Msg.moneyStr(view.totalBet()));
		if (!view.finished) {
			Hand h = view.activeHand();
			String hint = h == null ? "" : "§8Basic strategy says: " + tip(BlackjackRules.basic(view));
			return Gui.item(Items.NAME_TAG, "§eYour move", "§7Hit, Stand, Double or Split below.", "§7Total bet: " + Msg.moneyStr(view.totalBet()),
				"§8Closing the table stands on every hand.", hint);
		}
		long bet = view.totalBet();
		long payout = view.payout();
		String title = payout > bet ? (view.hands.size() == 1 && BlackjackRules.BLACKJACK.equals(view.hands.get(0).result) ? "§6§lBLACKJACK! " : "§a§lYou win! ") + "§a+" + Money.format(payout - bet)
			: payout == bet ? "§7§lPush §7– bet back"
			: payout > 0 ? "§c§lDealer wins §8(" + Money.format(payout) + " back)"
			: "§c§lDealer wins";
		GuiElementBuilder b = Gui.item(payout > bet ? Items.EMERALD_BLOCK : payout == bet ? Items.IRON_BLOCK : Items.REDSTONE_BLOCK, title,
			"§7Bet: " + Msg.moneyStr(bet) + " §8· §7Paid: " + Msg.moneyStr(payout), "§7Press §aDeal §7for the next hand.");
		if (payout > bet) b.glow();
		return b;
	}

	private static String tip(BlackjackRules.Move m) {
		return switch (m) {
			case HIT -> "Hit";
			case STAND -> "Stand";
			case DOUBLE -> "Double";
			case SPLIT -> "Split";
		};
	}

	private GuiElementBuilder helpItem() {
		return Gui.item(Items.KNOWLEDGE_BOOK, "§eHow to play",
			"§7Get closer to §f21 §7than the dealer", "§7without going over.",
			"§72–10 = value, §fJ Q K §7= 10, §fAce §7= 1 or 11.",
			"",
			"§aHit §7– take a card",
			"§cStand §7– keep your hand",
			"§6Double §7– double the bet, one more card",
			"§dSplit §7– a pair becomes two hands (once)",
			"",
			"§7Dealer stands on all 17s, peeks for Blackjack.",
			"§7Wins pay §f1:1§7, Blackjack §f3:2§7, ties push.",
			"§8House commission " + percent(BlackjackRules.COMMISSION_BP) + " of winnings.",
			"§86 decks, reshuffled every round.",
			"§8More: /blackjack help");
	}

	private void renderActions() {
		if (!playing()) return;
		boolean hit = BlackjackRules.canHit(view);
		boolean stand = BlackjackRules.canStand(view);
		Hand h = view.activeHand();
		long extra = h == null ? 0 : h.bet;
		long cover = Casino.coverLimit(round.id);
		long balance = EconomyService.balance(player.getUUID());
		setSlot(SLOT_HIT, hit ? Gui.button(Items.LIME_CONCRETE, "§a§lHit", () -> move(BlackjackGame.Move.HIT), "§7Take another card.")
			: Gui.item(Items.GRAY_DYE, "§7Hit", "§8Not possible on this hand"));
		setSlot(SLOT_STAND, stand ? Gui.button(Items.RED_CONCRETE, "§c§lStand", () -> move(BlackjackGame.Move.STAND), "§7Keep this hand.")
			: Gui.item(Items.GRAY_DYE, "§7Stand"));
		if (BlackjackRules.canDouble(view)) {
			String why = BlackjackRules.maxPayoutAfterDouble(view) > cover ? "§cThe Treasury can't cover this right now"
				: balance < extra ? "§cNot enough money" : "";
			setSlot(SLOT_DOUBLE, Gui.button(Items.GOLD_BLOCK, "§6§lDouble §7(+" + Money.format(extra) + ")", () -> move(BlackjackGame.Move.DOUBLE),
				"§7Double your bet and take", "§7exactly one more card.", why));
		} else {
			setSlot(SLOT_DOUBLE, Gui.item(Items.GRAY_DYE, "§7Double", "§8Only on your first two cards"));
		}
		if (BlackjackRules.canSplit(view)) {
			String why = BlackjackRules.maxPayoutAfterSplit(view) > cover ? "§cThe Treasury can't cover this right now"
				: balance < extra ? "§cNot enough money" : "";
			setSlot(SLOT_SPLIT, Gui.button(Items.SHEARS, "§d§lSplit §7(+" + Money.format(extra) + ")", () -> move(BlackjackGame.Move.SPLIT),
				"§7Play the pair as two hands,", "§7each with your bet.", "§8Split aces get one card each.", why));
		} else {
			setSlot(SLOT_SPLIT, Gui.item(Items.GRAY_DYE, "§7Split", "§8Only with two cards of the same value (once)"));
		}
	}

	private void renderBar() {
		boolean idle = round == null && animTick < 0;
		setSlot(BAR, round == null ? Gui.button(Items.ARROW, "§c« Back", this::openHub, "§7Back to the casino")
			: Gui.button(Items.ARROW, "§c« Back", () -> {
				if (busy()) return;
				finishRound(); // stands on every open hand, then the hub shows the new numbers
				openHub();
			}, "§7Your open hands stand"));
		long min = Casino.cfg().minBet;
		long max = BlackjackGame.maxStake(player.getUUID());
		setSlot(SLOT_STAKE, Gui.item(Items.GOLD_NUGGET, "§6Bet: §e" + Money.format(stake),
			"§7Bet: " + Msg.moneyStr(stake),
			"§7Min " + Msg.moneyStr(min) + " §8· §7Max " + (max > 0 ? Msg.moneyStr(max) : "§clocked"),
			"§7Balance: " + Msg.moneyStr(EconomyService.balance(player.getUUID())),
			lastResult.isEmpty() ? "" : "§7Last round: " + lastResult));
		if (idle) {
			setSlot(BAR + 1, Gui.button(Items.RED_DYE, "§c÷2", () -> setStake(stake / 2), "§7Halve your bet"));
			setSlot(BAR + 2, Gui.button(Items.RED_DYE, "§c−" + Money.formatShort(10_000), t -> setStake(stake - (t.isRight ? 100_000 : 10_000)), "§7Left: −" + Money.formatShort(10_000), "§7Right: −" + Money.formatShort(100_000)));
			setSlot(BAR + 5, Gui.button(Items.LIME_DYE, "§a+" + Money.formatShort(10_000), t -> setStake(stake + (t.isRight ? 100_000 : 10_000)), "§7Left: +" + Money.formatShort(10_000), "§7Right: +" + Money.formatShort(100_000)));
			setSlot(BAR + 6, Gui.button(Items.LIME_DYE, "§a×2", () -> setStake(stake * 2), "§7Double your bet"));
			setSlot(BAR + 7, Gui.button(Items.GOLD_BLOCK, "§6Max", this::setMaxStake, "§7Highest possible bet"));
			setSlot(SLOT_DEAL, Gui.button(Items.LIME_CONCRETE, "§a§lDeal §7(" + Money.format(stake) + ")", this::deal,
				"§7Deal a new hand.", "§8Wins 1:1 · Blackjack 3:2"));
		} else {
			for (int i : new int[] {1, 2, 5, 6, 7}) setSlot(BAR + i, Gui.filler());
			setSlot(SLOT_DEAL, Gui.item(Items.CLOCK, "§7Hand in play …"));
		}
		setSlot(SLOT_CLOSE, Gui.item(Items.BARRIER, "§cClose", round != null ? "§7Your open hands stand" : "").setCallback((i, t, a, g) -> {
			if (Gui.isPlainClick(t)) close();
		}));
	}

	private void openHub() {
		Casino.openHub(player);
	}

	private void setStake(long value) {
		if (busy() || round != null) return;
		long min = Casino.cfg().minBet;
		long v = Math.max(min, Math.min(Money.MAX, value));
		long max = BlackjackGame.maxStake(player.getUUID());
		if (max > 0 && v > max) v = max;
		stake = v;
		render();
	}

	private void setMaxStake() {
		long max = Math.min(BlackjackGame.maxStake(player.getUUID()), EconomyService.balance(player.getUUID()));
		if (max < Casino.cfg().minBet) {
			Msg.error(player, "No bet possible right now (Treasury, daily limit or balance).");
			Gui.failSound(player);
			return;
		}
		setStake(max);
	}

	static String cardShort(int card) {
		int r = BlackjackRules.rank(card);
		String rank = r == 0 ? "A" : r >= 10 ? RANKS[r].substring(0, 1) : RANKS[r];
		return SUITS[BlackjackRules.suit(card)] + " §f" + rank;
	}

	static Item cardItem(int rank) {
		return switch (rank) {
			case 0 -> Items.NETHER_STAR;
			case 10 -> Items.IRON_INGOT;
			case 11 -> Items.GOLD_INGOT;
			case 12 -> Items.DIAMOND;
			default -> Items.PAPER;
		};
	}

	/** A card as an item: name "♥ Queen", lore with its value; number cards stack to their value. */
	static GuiElementBuilder card(int card) {
		int rank = BlackjackRules.rank(card);
		int suit = BlackjackRules.suit(card);
		GuiElementBuilder b = Gui.item(cardItem(rank), SUITS[suit] + " §f" + RANKS[rank],
			"§7Value: §f" + (rank == 0 ? "1 or 11" : String.valueOf(BlackjackRules.value(card))), "§8" + SUIT_NAMES[suit]);
		if (rank >= 1 && rank <= 9) b.setCount(rank + 1);
		return b;
	}
}
