package dev.smpeconomy.core.gui;

//? if >=26.1 {
/*import eu.pb4.sgui.api.SguiUtils;
import eu.pb4.sgui.api.gui.GuiLike;
*///?} else {
import eu.pb4.sgui.api.GuiHelpers;
import eu.pb4.sgui.api.gui.GuiInterface;
//?}
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;

/**
 * Base of chest GUIs with close logic. Only here and in {@link SmpAnvilGui} do sgui close callbacks appear, and they
 * are {@code final} so no GUI can bypass the guard. Subclasses override {@link #onEnded()} and {@link #onClosed()}; see
 * {@link CloseGuard}.
 */
public class SmpGui extends SimpleGui {
	private final CloseGuard closeGuard = new CloseGuard(new CloseGuard.Hooks() {
		@Override
		public void onEnded() {
			SmpGui.this.onEnded();
		}

		@Override
		public void onClosed() {
			SmpGui.this.onClosed();
		}
	});

	public SmpGui(MenuType<?> type, ServerPlayer player, boolean manipulatePlayerSlots) {
		super(type, player, manipulatePlayerSlots);
	}

	/** Exactly once per instance, whichever way the window disappears. Settle or return items here. */
	protected void onEnded() {}

	/**
	 * At most once, only on sgui close, not when replaced by another GUI. For navigation; check {@code Gui.canOpen}
	 * first.
	 */
	protected void onClosed() {}

	public final boolean isEnded() {
		return closeGuard.ended();
	}

	public final CloseGuard closeGuard() {
		return closeGuard;
	}

	//? if >=26.1 {
	/*// sgui 2.x no longer reports ESC as an sgui close (no onManualClose), only onPlayerClose(true), then onRemoved
	// and, after vanilla has closed the menu, afterRemoval. So remember the player's request and report the sgui close
	// only there (as sgui 1.12 did at the end of the close packet), so that onClosed can open another menu
	// without vanilla closing it again right away. If another GUI replaces this one, there is no onPlayerClose, only onEnded.
	private boolean playerClosing;

	@Override
	public final void onPlayerClose(boolean success) {
		super.onPlayerClose(success);
		if (success) playerClosing = true;
	}

	@Override
	public final void afterRemoval() {
		super.afterRemoval();
		if (!playerClosing) return;
		playerClosing = false;
		closeGuard.manualClose();
	}

	@Override
	public final void onManualClose() {
		closeGuard.manualClose();
	}

	// sgui 2.x may call this more than once; the guard still settles exactly once
	@Override
	public final void onRemoved() {
		super.onRemoved();
		closeGuard.removed();
	}
	*///?} else {
	@Override
	public final void onClose() {
		closeGuard.manualClose();
	}

	@Override
	public final void onScreenHandlerClosed() {
		super.onScreenHandlerClosed();
		closeGuard.removed();
	}
	//?}

	/** A real slot, e.g. a sell session's container. */
	protected final void redirectSlot(int index, Slot slot) {
		//? if >=26.1 {
		/*setSlot(index, slot);
		*///?} else {
		setSlotRedirect(index, slot);
		//?}
	}

	/** False if no sgui GUI is open; the caller may need to close a vanilla container itself. */
	public static boolean closeCurrent(ServerPlayer player) {
		//? if >=26.1 {
		/*GuiLike gui = SguiUtils.getCurrentGui(player);
		*///?} else {
		GuiInterface gui = GuiHelpers.getCurrentGui(player);
		//?}
		if (gui == null) return false;
		gui.close();
		return true;
	}
}
