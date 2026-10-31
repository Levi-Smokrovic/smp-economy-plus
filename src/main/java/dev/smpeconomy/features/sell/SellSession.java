package dev.smpeconomy.features.sell;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import dev.smpeconomy.core.compat.Mc;

/**
 * Temporary container behind the sell GUI, independent of sgui so settling can be selftested.
 * <p>{@link #finish()} runs exactly once however the GUI ends. Afterwards, and from {@link #lock()} on, slots take
 * and give nothing, which blocks click packets from e.g. a dead player whose menu is still open server-side.
 */
public final class SellSession {
	public static final int SIZE = 45;

	private final ServerPlayer player;
	private final String id = java.util.UUID.randomUUID().toString();
	private final SimpleContainer container;
	private boolean finished;
	/** See {@link #lock()}. */
	private boolean locked;
	private boolean sellOnFinish = true;
	private boolean dirty = true;
	private SellService.Outcome outcome;

	public SellSession(ServerPlayer player) {
		this.player = player;
		this.container = Mc.listeningContainer(SIZE, () -> dirty = true);
	}

	public ServerPlayer player() {
		return player;
	}

	public SimpleContainer container() {
		return container;
	}

	public Slot slot(int index) {
		return new SessionSlot(this, index);
	}

	public boolean isFinished() {
		return finished;
	}

	public void lock() {
		locked = true;
	}

	public SellService.Outcome outcome() {
		return outcome;
	}

	public void cancelOnFinish() {
		sellOnFinish = false;
	}

	public boolean consumeDirty() {
		boolean d = dirty;
		dirty = false;
		return d;
	}

	public String id() {
		return id;
	}

	public void saveJournal() {
		if (finished) return;
		SellJournal.update(id, player, snapshot());
	}

	public List<ItemStack> snapshot() {
		List<ItemStack> list = new ArrayList<>();
		for (int i = 0; i < SIZE; i++) {
			ItemStack s = container.getItem(i);
			if (!s.isEmpty()) list.add(s.copy());
		}
		return list;
	}

	public SellService.Outcome finish() {
		return finish(player, SellService.ReturnMode.NORMAL);
	}

	/** {@code target} has the same UUID but may be a new object after end credits or a rejoin. */
	public SellService.Outcome finish(ServerPlayer target, SellService.ReturnMode mode) {
		if (finished) return outcome;
		finished = true; // first: slots are locked from now on and further calls, even recursive ones, do nothing
		SellJournal.remove(id); // same tick as settling, so same flush as account and inventory
		List<SellService.Taken> taken = new ArrayList<>();
		for (ItemStack s : container.removeAllItems()) taken.add(new SellService.Taken(s, -1));
		outcome = SellService.settle(target, taken, sellOnFinish, mode);
		try {
			SellService.report(target, outcome);
		} catch (RuntimeException e) {
			dev.smpeconomy.SmpEconomy.LOG.error("[SMPEco] sell: message to {} failed", target.getPlainTextName(), e);
		}
		return outcome;
	}

	/**
	 * Last resort on server stop when the player can't receive anything (end credits, disconnected, dead): contents
	 * go unsold to the crash journal and return on next join.
	 * @return false if the journal isn't usable or a stack isn't encodable; the session stays open and must settle
	 * normally ({@link #finish()} also removes a partly written journal entry)
	 */
	public boolean postponeToJournal() {
		if (finished) return true;
		List<ItemStack> items = snapshot();
		if (!SellJournal.update(id, player, items)) return false;
		finished = true;
		container.removeAllItems();
		SellJournal.forgetLive(id); // now counts as orphaned, returned on next join
		int count = 0;
		for (ItemStack s : items) count += s.getCount();
		outcome = new SellService.Outcome(SellService.Status.POSTPONED, 0, 0, List.of(), List.of(), count, 0, 0, null);
		return true;
	}

	private static final class SessionSlot extends Slot {
		private final SellSession session;

		SessionSlot(SellSession session, int index) {
			super(session.container, index, 0, 0);
			this.session = session;
		}

		@Override
		public boolean mayPlace(ItemStack stack) {
			return !session.finished && !session.locked;
		}

		@Override
		public boolean mayPickup(Player player) {
			return !session.finished && !session.locked;
		}
	}
}
