package dev.smpeconomy.core.gui;

/**
 * Close lifecycle of one GUI instance, independent of the sgui version. {@link SmpGui} and {@link SmpAnvilGui} report
 * raw sgui events; this turns them into two hooks.
 *
 * <p>{@link Hooks#onEnded()} runs exactly once, on the first event of either kind, whichever way the window disappears
 * (ESC, {@code close()}, disconnect, stop, dimension change, replaced by another GUI). It is for settlement and
 * returning items: a second call never settles twice (dupe protection) and every path triggers it (no loss).
 *
 * <p>{@link Hooks#onClosed()} runs at most once, only on sgui close, not when another GUI replaces this one, and after
 * {@code onEnded}. It is for navigation.
 *
 * <p>sgui 1.12 (MC 1.21.11) maps {@code onClose} to manualClose and {@code onScreenHandlerClosed} to removed. sgui 2.x
 * (MC 26.x) maps {@code onManualClose} to manualClose, and {@code onRemoved}, which can fire repeatedly, to removed.
 *
 * <p>sgui 1.12 on ESC or {@code close()} fires removed then manual close; on death only manual close; when replaced or
 * on dimension change only removed. sgui 2.x no longer calls {@code onManualClose} on ESC, so the GUI classes report
 * manual close after removal ({@code afterRemoval}) if the player closed it, matching 1.12's order. The hooks do not
 * depend on event order (selftests {@code core/gui-close-guard}, {@code core/gui-close-sgui-paths}).
 */
public final class CloseGuard {
	public interface Hooks {
		void onEnded();

		void onClosed();
	}

	private final Hooks hooks;
	private boolean ended;
	private boolean closed;
	private int manualCloses;
	private int removals;

	public CloseGuard(Hooks hooks) {
		this.hooks = hooks;
	}

	public void manualClose() {
		manualCloses++;
		end();
		if (closed) return;
		closed = true;
		hooks.onClosed();
	}

	public void removed() {
		removals++;
		end();
	}

	private void end() {
		if (ended) return;
		ended = true; // before the hook: re-entry from it (close() in the hook) must not settle twice
		hooks.onEnded();
	}

	public boolean ended() {
		return ended;
	}

	public boolean closed() {
		return closed;
	}

	public int manualCloses() {
		return manualCloses;
	}

	public int removals() {
		return removals;
	}
}
