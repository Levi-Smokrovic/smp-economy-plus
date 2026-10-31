package dev.smpeconomy.features.sell;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Items;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.Scheduler;
import dev.smpeconomy.core.gui.SmpGui;

/**
 * 6-row sell GUI: rows 1-5 are real slots redirected to the container of a {@link SellSession}; row 6 holds cancel,
 * the live preview and sell.
 * <p>Settles on every path, exactly once ({@link SellSession#finish()} is idempotent):
 * <ul>
 * <li>ESC, button, {@code gui.close()}, disconnect, server stop (core closes GUIs before saving), another GUI
 * opening: vanilla calls {@code removed()} then {@link #onEnded()}, once per GUI ({@link
 * dev.smpeconomy.core.gui.CloseGuard}).</li>
 * <li>Death: {@code features.sell.mixin.ServerPlayerMixin} settles at the start of {@code ServerPlayer#die} ({@link
 * #onDeath}), BEFORE death drops, with returns going straight to the inventory so graves and {@code keep_inventory}
 * pick them up. What doesn't fit waits in the return store ({@link SellJournal}) until respawn. sgui closes
 * afterwards at {@code die} TAIL, then {@link #onEnded()} is a no-op, or the fallback that puts everything in the
 * return store.</li>
 * <li>Dimension change (portal, /tp, end credits): vanilla removes the player from the old world with {@code
 * CHANGED_DIMENSION} and closes the menu while the player still counts as removed. Settling now would drop everything
 * in the old dimension, so it is deferred ({@link #defer()}): slots locked, settled at tick end or by the safety net
 * once the current ServerPlayer object for this UUID can receive items. After the credits that is a new object.</li>
 * <li>Safety net: {@link #sweep()} ends sessions whose GUI is no longer open with none of the above having run;
 * {@link #finishAll()} on server stop.</li>
 * </ul>
 */
public final class SellGui extends SmpGui {
	public static final String TITLE = "§8Sell";
	public static final int SLOT_CANCEL = 45;
	public static final int SLOT_VALUE = 49;
	public static final int SLOT_SELL = 53;

	/**
	 * Deliberately strong references: a session holding items must never be collected unnoticed. Entries go away in
	 * {@link #finish()}.
	 */
	private static final Set<SellGui> OPEN = new LinkedHashSet<>();

	private final SellSession session;
	private int ticks;
	private boolean deferred;

	private SellGui(ServerPlayer player) {
		super(MenuType.GENERIC_9x6, player, false);
		this.session = new SellSession(player);
		setTitle(Msg.legacy(TITLE));
		for (int i = 0; i < SellSession.SIZE; i++) redirectSlot(i, session.slot(i));
		for (int i = SellSession.SIZE; i < 54; i++) setSlot(i, Gui.filler());
		setSlot(SLOT_CANCEL, Gui.button(net.minecraft.world.item.Items.BARRIER, "§cCancel", () -> {
			session.cancelOnFinish();
			close();
		}, "§7Returns all items", "§7without selling anything."));
		setSlot(SLOT_SELL, Gui.button(net.minecraft.world.item.Items.EMERALD, "§a§lSell", () -> close(),
			"§7Sells everything sellable", "§7and closes the menu.", "", "§8Closing (ESC) sells too."));
		updatePreview();
	}

	public static boolean open(ServerPlayer player) {
		if (!Gui.canOpen(player)) return false;
		SellGui gui = new SellGui(player);
		boolean ok = gui.open();
		if (ok && gui.isOpen() && !gui.session.isFinished()) {
			OPEN.add(gui);
			return true;
		}
		gui.session.finish(); // empty, only for completeness (locks the slots)
		return false;
	}

	public SellSession session() {
		return session;
	}

	@Override
	public void onTick() {
		ticks++;
		if (session.isFinished() || deferred) return;
		if (session.consumeDirty()) {
			session.saveJournal(); // also runs right after each click (broadcastChanges)
			updatePreview();
		} else if (ticks % 20 == 0) {
			updatePreview(); // prices can change (news, events)
		}
	}

	/**
	 * First event wins: vanilla {@code removed()}, or sgui closing on death where no {@code removed()} runs. Settles
	 * now, unless in the middle of a dimension change (see {@link #defer()}).
	 */
	@Override
	protected void onEnded() {
		if (!session.isFinished() && !SmpEconomy.isStopping() && isDimensionChange(player)) defer();
		else finish();
	}

	private void finish() {
		OPEN.remove(this);
		session.finish();
	}

	private void finishWith(ServerPlayer target, SellService.ReturnMode mode) {
		OPEN.remove(this);
		session.finish(target, mode);
	}

	/**
	 * {@code ServerPlayer#teleport} and {@code showEndCredits} go through {@code
	 * removePlayerImmediately(CHANGED_DIMENSION)}. {@code isAlive()} is false in that state, so settling immediately
	 * couldn't put anything in the inventory.
	 */
	static boolean isDimensionChange(ServerPlayer p) {
		return isDimensionChange(p.isRemoved(), p.getRemovalReason(), p.hasDisconnected());
	}

	static boolean isDimensionChange(boolean removed, Entity.RemovalReason reason, boolean disconnected) {
		return removed && reason == Entity.RemovalReason.CHANGED_DIMENSION && !disconnected;
	}

	/**
	 * Locks slots (the menu is already closed server-side, so clicks can't reach it), writes state to the journal and
	 * retries at tick end, when {@code teleport} has moved the player to the new world. After that every second
	 * through {@link #sweep()}, covering end credits until respawn and disconnects until rejoin.
	 */
	private void defer() {
		if (deferred) return;
		deferred = true;
		session.lock();
		session.saveJournal();
		Scheduler.runLater(1, this::trySettleDeferred);
	}

	/** After credits or a rejoin this differs from {@link #player}. Null if there is none. */
	private ServerPlayer currentPlayer() {
		MinecraftServer server = SmpEconomy.server();
		return server == null ? null : server.getPlayerList().getPlayer(player.getUUID());
	}

	private static boolean canSettleNow(ServerPlayer p) {
		return p != null && !p.isRemoved() && Items.canReceive(p);
	}

	private void trySettleDeferred() {
		if (session.isFinished()) {
			OPEN.remove(this);
			return;
		}
		if (SmpEconomy.isStopping()) return; // finishAll takes over
		ServerPlayer current = currentPlayer();
		if (canSettleNow(current)) finishWith(current, SellService.ReturnMode.NORMAL);
	}

	/**
	 * Mixin on {@code ServerPlayer#die} HEAD (health ≤ 0, death drops not run yet): settle this player's open or
	 * deferred sessions now with returns straight to the inventory ({@link SellService.ReturnMode#INVENTORY}).
	 * Vanilla drops, {@code keep_inventory} and Universal Graves then treat them like any inventory item. Overflow is
	 * not dropped next to the corpse; it waits in the return store until respawn.
	 */
	public static void onDeath(ServerPlayer player) {
		if (OPEN.isEmpty()) return;
		for (SellGui gui : List.copyOf(OPEN)) {
			if (gui.session.isFinished() || !gui.player.getUUID().equals(player.getUUID())) continue;
			gui.finishWith(player, SellService.ReturnMode.INVENTORY);
		}
	}

	private void updatePreview() {
		List<ItemStack> items = session.snapshot();
		SellService.Quote q = SellService.quote(player.getUUID(), items);
		int all = 0;
		for (ItemStack s : items) all += s.getCount();
		int rejected = all - q.count();
		List<String> lore = new ArrayList<>();
		lore.add("§7Sellable: §f" + SellService.items(q.count()));
		if (rejected > 0) lore.add("§7Not sellable: §e" + SellService.items(rejected) + " §8(will be returned)");
		if (!q.isEmpty()) {
			List<String> bonuses = SellService.bonuses(player.getUUID(), q.lines());
			if (!bonuses.isEmpty()) {
				lore.add("");
				lore.add("§7Active:");
				for (int i = 0; i < bonuses.size() && i < 5; i++) lore.add("§8 ▪ " + bonuses.get(i));
			}
		}
		if (SellService.isBlocked(player)) {
			lore.add("");
			lore.add("§c" + SellService.BLOCKED_MESSAGE);
		}
		lore.add("");
		lore.add("§8Put items in the top rows.");
		lore.add("§8Closing the menu sells them,");
		lore.add("§8the rest is returned.");
		lore.add("§8Only unmodified items (no name,");
		lore.add("§8no enchantments, no damage).");
		String value = q.overflow() ? "§c(too large)" : Msg.moneyStr(q.total());
		setSlot(SLOT_VALUE, Gui.item(net.minecraft.world.item.Items.SUNFLOWER, "§eSell value: " + value, lore));
	}

	static void sweep() {
		for (SellGui gui : List.copyOf(OPEN)) {
			if (gui.session.isFinished()) {
				OPEN.remove(gui);
			} else if (gui.deferred) {
				gui.trySettleDeferred();
			} else if (!gui.isOpen()) {
				SmpEconomy.LOG.warn("[SMPEco] sell: sell GUI of {} ended without a close event, settling", gui.player.getPlainTextName());
				gui.finish();
			}
		}
	}

	static void finishAll() {
		for (SellGui gui : List.copyOf(OPEN)) {
			try {
				if (gui.isOpen()) gui.close();
			} catch (RuntimeException e) {
				SmpEconomy.LOG.error("[SMPEco] sell: closing the sell GUI of {} failed", gui.player.getPlainTextName(), e);
			}
			if (gui.deferred) gui.finishDeferredAtStop();
			else gui.finish();
		}
		OPEN.clear();
	}

	/**
	 * Settle normally if the player can receive items right now, since a save follows. Otherwise (end credits,
	 * disconnected, dead) put it unsold in the journal, returned on next join. Only if the journal isn't usable,
	 * settle anyway, returning through {@code Items.giveOrDrop} as a last resort.
	 */
	private void finishDeferredAtStop() {
		if (session.isFinished()) return;
		ServerPlayer current = currentPlayer();
		if (canSettleNow(current)) {
			finishWith(current, SellService.ReturnMode.NORMAL);
		} else if (session.postponeToJournal()) {
			OPEN.remove(this);
			SmpEconomy.LOG.info("[SMPEco] sell: sell menu of {} moved to the journal on stop (returned on next join)",
				player.getPlainTextName());
		} else {
			finish();
		}
	}

	static int openCount() {
		return OPEN.size();
	}
}
