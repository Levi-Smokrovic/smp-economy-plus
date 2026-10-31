package dev.smpeconomy.features.casino;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Items;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.gui.SmpGui;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.features.casino.CasinoData.Round;

/**
 * Base of all game GUIs: one optional open round, bet bar in the last row, animations through {@link #onTick()} (only
 * while the GUI is open). However the GUI closes (ESC, other menu, death, disconnect, server stop), the round is
 * settled at once through {@link Casino#settle}, since the result was fixed before the animation.
 */
abstract class CasinoGameGui extends SmpGui implements Casino.Window {
	protected final ServerPlayer player;
	protected final Game game;
	protected final int rows;
	/** Current round (null = none). */
	protected Round round;
	protected long stake;
	/** ≥ 0 during an animation (ticks since start), otherwise -1. */
	protected int animTick = -1;
	/** Result line of the last round (lore of the stake display). */
	protected String lastResult = "";

	protected CasinoGameGui(ServerPlayer player, Game game, int rows, String title) {
		super(Gui.menuType(rows), player, false);
		this.player = player;
		this.game = game;
		this.rows = rows;
		setTitle(Msg.legacy(title));
		long def = defaultStake(player, game);
		long max = Casino.maxStake(player.getUUID(), game, Casino.cfg().minesDefaultBombs);
		this.stake = max > 0 ? Math.min(def, max) : def;
	}

	static long defaultStake(ServerPlayer player, Game game) {
		CasinoData.PlayerStats s = Casino.peekStats(player.getUUID());
		Long last = s == null ? null : s.lastStake.get(game.id());
		long v = last != null && last > 0 ? last : 10_000; // $100
		return Math.max(Casino.cfg().minBet, v);
	}

	/** Opens the GUI; if that fails, a round already started is settled at once. */
	boolean openGui() {
		render();
		boolean ok = open();
		if (!ok && round != null) {
			String id = round.id;
			round = null;
			Casino.settle(id, "window failed to open");
		}
		return ok;
	}

	protected void bindRound(Round r) {
		this.round = r;
		Casino.bind(r.id, this);
	}

	/** Settles the current round (the result message comes from {@link Casino#settle}). */
	protected Casino.Settled finishRound() {
		if (round == null) return null;
		String id = round.id;
		round = null;
		Casino.Settled s = Casino.settle(id, null);
		if (s != null) {
			lastResult = s.refund() ? "§7Bet refunded"
				: s.payout() > 0 ? "§aWon " + Money.format(s.payout()) + " §8(" + Casino.multText(s.round().stake, s.payout()) + ")"
				: "§cLost " + Money.format(s.round().stake);
		}
		return s;
	}

	private int lastServerTick = Integer.MIN_VALUE;

	@Override
	public void onTick() {
		if (animTick < 0) return;
		// sgui also calls onTick after every click packet: animations advance at most one step per server tick
		net.minecraft.server.MinecraftServer server = player.level().getServer();
		int tick = server == null ? -1 : server.getTickCount();
		if (tick == lastServerTick) return;
		lastServerTick = tick;
		animTick++;
		animate(animTick);
	}

	@Override
	protected void onEnded() {
		animTick = -1;
		if (round != null) {
			String id = round.id;
			round = null;
			Casino.settle(id, Casino.CLOSED_REASON);
		}
	}

	/**
	 * Watchdog (idle): settles at the current value ({@code why} in the message) and closes the window. The round is
	 * forgotten first so {@link #onEnded()} does not settle it a second time with another reason.
	 */
	@Override
	public void expire(String roundId, String why) {
		if (round != null && round.id.equals(roundId)) round = null;
		animTick = -1;
		Casino.settle(roundId, why);
		close();
	}

	/** The round was settled outside this GUI (e.g. admin): back to idle, nothing clickable. */
	@Override
	public void detach(String roundId) {
		if (round == null || !round.id.equals(roundId)) return;
		round = null;
		animTick = -1;
		if (isOpen()) render();
	}

	protected boolean busy() {
		return animTick >= 0 || isEnded();
	}

	protected abstract void render();

	/** One animation tick (≥ 1). Set {@code animTick = -1} at the end. */
	protected abstract void animate(int t);

	/** Starts a round with the current stake (from the action button). */
	protected abstract void startRound();

	/** Label of the action button when idle. */
	protected abstract GuiElementBuilder idleAction();

	/** Extra for {@link Casino#maxStake} (mines: bomb count). */
	protected int bombs() {
		return 0;
	}

	protected void renderStakeBar() {
		int base = (rows - 1) * 9;
		boolean idle = round == null && animTick < 0;
		setSlot(base, round == null ? Gui.backButton(() -> CasinoMenus.openMenu(player))
			: Gui.button(Items.ARROW, "§c« Back", () -> {
				if (busy()) return; // not during the animation (the result is being shown)
				finishRound(); // settle first, then build the menu with the new numbers
				CasinoMenus.openMenu(player);
			}, "§7Pays out your open round"));
		long min = Casino.cfg().minBet;
		long max = Casino.maxStake(player.getUUID(), game, bombs());
		String[] info = {
			"§7Bet: " + Msg.moneyStr(stake),
			"§7Min " + Msg.moneyStr(min) + " §8· §7Max " + (max > 0 ? Msg.moneyStr(max) : "§clocked"),
			"§7Balance: " + Msg.moneyStr(EconomyService.balance(player.getUUID())),
			lastResult.isEmpty() ? "" : "§7Last round: " + lastResult};
		setSlot(base + 3, Gui.item(Items.GOLD_NUGGET, "§6Bet: §e" + Money.format(stake), info));
		if (idle) {
			setSlot(base + 1, adjust("§c÷2", Items.RED_DYE, () -> setStake(stake / 2), "§7Halve your bet"));
			setSlot(base + 2, Gui.button(Items.RED_DYE, "§c−" + Money.formatShort(10_000), t -> setStake(stake - (t.isRight ? 100_000 : 10_000)), "§7Left-click: −" + Money.formatShort(10_000), "§7Right-click: −" + Money.formatShort(100_000)));
			setSlot(base + 5, Gui.button(Items.LIME_DYE, "§a+" + Money.formatShort(10_000), t -> setStake(stake + (t.isRight ? 100_000 : 10_000)), "§7Left-click: +" + Money.formatShort(10_000), "§7Right-click: +" + Money.formatShort(100_000)));
			setSlot(base + 6, adjust("§a×2", Items.LIME_DYE, () -> setStake(stake * 2), "§7Double your bet"));
			setSlot(base + 7, adjust("§6Max", Items.GOLD_BLOCK, this::setMaxStake, "§7Highest possible bet"));
			setSlot(base + 4, idleAction());
		} else {
			for (int i : new int[] {1, 2, 5, 6, 7}) setSlot(base + i, Gui.filler());
			setSlot(base + 4, Gui.item(Items.CLOCK, "§7Round in progress …"));
		}
		setSlot(base + 8, Gui.item(Items.BARRIER, "§cClose", round != null ? "§7Pays out your open round" : "").setCallback((i, t, a, g) -> {
			if (Gui.isPlainClick(t)) close();
		}));
	}

	private GuiElementBuilder adjust(String name, net.minecraft.world.item.Item item, Runnable action, String lore) {
		return Gui.button(item, name, action, lore);
	}

	protected void setStake(long value) {
		if (busy() || round != null) return;
		long min = Casino.cfg().minBet;
		long v = Math.max(min, Math.min(Money.MAX, value));
		long max = Casino.maxStake(player.getUUID(), game, bombs());
		if (max > 0 && v > max) v = max;
		stake = v;
		render();
	}

	private void setMaxStake() {
		long max = Math.min(Casino.maxStake(player.getUUID(), game, bombs()), EconomyService.balance(player.getUUID()));
		if (max < Casino.cfg().minBet) {
			Msg.error(player, "You can't bet right now (Treasury, limit or balance).");
			Gui.failSound(player);
			return;
		}
		setStake(max);
	}

	/** Shared "Start" action button with stake and lore. */
	protected GuiElementBuilder startButton(String name, String... lore) {
		return Gui.button(Items.LIME_CONCRETE, name, () -> {
			if (busy() || round != null) return;
			startRound();
		}, lore);
	}

	/** Next step not covered by the Treasury: message (cash out, or close = stake back) + sound. */
	protected void notCovered(long amount) {
		boolean canCash = round != null && Casino.canCashOut(round);
		Msg.error(player, "The Treasury can't cover " + Msg.moneyStr(amount) + " §cright now – "
			+ (canCash ? "please cash out." : "closing refunds your bet."));
		Gui.failSound(player);
		render();
	}

	/** Error at start: message + sound. */
	protected void startFailed(String error) {
		Msg.error(player, error);
		Gui.failSound(player);
		render();
	}

	protected void tickSound(float pitch) {
		Gui.sound(player, SoundEvents.NOTE_BLOCK_HAT, pitch);
	}

	protected void winSound() {
		Gui.sound(player, SoundEvents.NOTE_BLOCK_PLING, 1.4f);
		Gui.sound(player, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.PLAYER_LEVELUP), 1.2f);
	}

	protected void loseSound() {
		Gui.sound(player, SoundEvents.NOTE_BLOCK_BASS, 0.6f);
	}
}
