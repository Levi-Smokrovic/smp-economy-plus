package dev.smpeconomy.core.gui;

import eu.pb4.sgui.api.gui.AnvilInputGui;
import net.minecraft.server.level.ServerPlayer;

/**
 * Anvil text input with the close hooks, counterpart of {@link SmpGui} with the same semantics ({@link CloseGuard}).
 * Only here and in {@link SmpGui} do sgui close callbacks appear.
 */
public class SmpAnvilGui extends AnvilInputGui {
	private final CloseGuard closeGuard = new CloseGuard(new CloseGuard.Hooks() {
		@Override
		public void onEnded() {
			SmpAnvilGui.this.onEnded();
		}

		@Override
		public void onClosed() {
			SmpAnvilGui.this.onClosed();
		}
	});

	public SmpAnvilGui(ServerPlayer player, boolean manipulatePlayerSlots) {
		super(player, manipulatePlayerSlots);
	}

	protected void onEnded() {}

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
}
