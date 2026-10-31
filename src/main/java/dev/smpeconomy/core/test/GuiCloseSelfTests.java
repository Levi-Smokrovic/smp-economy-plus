package dev.smpeconomy.core.test;

import static dev.smpeconomy.core.test.Assert.eq;
import static dev.smpeconomy.core.test.Assert.isFalse;
import static dev.smpeconomy.core.test.Assert.isTrue;

import eu.pb4.sgui.api.gui.SimpleGui;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.world.inventory.MenuType;
import dev.smpeconomy.core.gui.CloseGuard;
import dev.smpeconomy.core.gui.SmpAnvilGui;
import dev.smpeconomy.core.gui.SmpGui;

/**
 * Settlement (onEnded) exactly once and navigation (onClosed) at most once, however often and in whatever order sgui
 * reports raw events (sgui 2.x: {@code onRemoved} "may run multiple times"). Double settlement is a dupe, none is a
 * loss.
 */
final class GuiCloseSelfTests {
	private static final String F = "core";

	private GuiCloseSelfTests() {}

	static void register() {
		SelfTests.register(F, "gui-close-guard", ctx -> closeGuard());
		SelfTests.register(F, "gui-close-hook-once", GuiCloseSelfTests::closeHookOnce);
		SelfTests.register(F, "gui-close-sgui-paths", GuiCloseSelfTests::sguiPaths);
	}

	private static SmpGui countingGui(FakePlayer p, int[] counts) {
		return new SmpGui(MenuType.GENERIC_9x3, p, false) {
			@Override
			protected void onEnded() {
				counts[0]++;
			}

			@Override
			protected void onClosed() {
				counts[1]++;
			}
		};
	}

	private static SmpAnvilGui countingAnvil(FakePlayer p, int[] counts) {
		return new SmpAnvilGui(p, false) {
			@Override
			protected void onEnded() {
				counts[0]++;
			}

			@Override
			protected void onClosed() {
				counts[1]++;
			}
		};
	}

	/** ESC as sgui reports it (close packet; vanilla removes the menu in between). */
	private static void pressEsc(SimpleGui gui) {
		gui.onPlayerClose(true);
		//? if >=26.1 {
		/*gui.onRemoved();     // AbstractWrapperMenu#removed
		gui.afterRemoval(); // end of ServerPlayer#doCloseContainer
		*///?} else {
		gui.onScreenHandlerClosed(); // VirtualScreenHandler#removed
		gui.close(true);             // end of handleContainerClose
		//?}
	}

	/** Another GUI opens (or dimension change): vanilla closes the menu without a close packet. */
	private static void replace(SimpleGui gui) {
		//? if >=26.1 {
		/*gui.onRemoved();
		gui.afterRemoval();
		*///?} else {
		gui.onScreenHandlerClosed();
		//?}
	}

	/**
	 * Event sequences of the real paths per sgui version, replayed since test players cannot open a menu: ESC settles
	 * and navigates (sgui 2.x no longer calls onManualClose, the seam reports close after afterRemoval); replaced only
	 * settles; refused ESC (canPlayerClose false) does nothing.
	 */
	private static void sguiPaths(TestContext ctx) {
		FakePlayer p = ctx.player("SmpecoGuiPaths");
		int[] esc = {0, 0};
		pressEsc(countingGui(p, esc));
		eq(1, esc[0], "ESC: settlement");
		eq(1, esc[1], "ESC: navigation (e.g. back to the previous menu)");

		int[] escAnvil = {0, 0};
		pressEsc(countingAnvil(p, escAnvil));
		eq(1, escAnvil[0], "anvil ESC: settlement");
		eq(1, escAnvil[1], "anvil ESC: navigation");

		int[] replaced = {0, 0};
		SmpGui r = countingGui(p, replaced);
		replace(r);
		eq(1, replaced[0], "replaced: settlement");
		eq(0, replaced[1], "replaced: no navigation");
		replace(r);
		eq(1, replaced[0], "replaced, menu event twice: no second settlement");

		int[] refused = {0, 0};
		countingGui(p, refused).onPlayerClose(false);
		eq(0, refused[0], "ESC refused: no settlement");
		eq(0, refused[1], "ESC refused: no navigation");

		int[] escTwice = {0, 0};
		SmpGui twice = countingGui(p, escTwice);
		pressEsc(twice);
		pressEsc(twice);
		eq(1, escTwice[0], "ESC twice: one settlement");
		eq(1, escTwice[1], "ESC twice: one navigation");
		ctx.detail("ESC, replaced, refused and ESC twice");
	}

	/** {@code reenter} fires both events again from inside onEnded (close() in the hook). */
	private static final class Counter implements CloseGuard.Hooks {
		int ended;
		int closed;
		CloseGuard guard;
		boolean reenter;

		@Override
		public void onEnded() {
			ended++;
			if (reenter) {
				guard.removed();
				guard.manualClose();
			}
		}

		@Override
		public void onClosed() {
			closed++;
		}
	}

	private static Counter counter(boolean reenter) {
		Counter c = new Counter();
		c.guard = new CloseGuard(c);
		c.reenter = reenter;
		return c;
	}

	private static void closeGuard() {
		Counter esc = counter(false);
		isFalse(esc.guard.ended(), "new: not ended");
		esc.guard.removed();
		eq(1, esc.ended, "removed fires onEnded");
		eq(0, esc.closed, "removed alone: no onClosed");
		esc.guard.manualClose();
		esc.guard.removed();
		esc.guard.manualClose();
		eq(1, esc.ended, "ESC twice: onEnded exactly once");
		eq(1, esc.closed, "ESC twice: onClosed exactly once");
		eq(2, esc.guard.removals(), "raw removed events counted");
		eq(2, esc.guard.manualCloses(), "raw close events counted");

		Counter death = counter(false);
		death.guard.manualClose();
		death.guard.manualClose();
		eq(1, death.ended, "death: onEnded once");
		eq(1, death.closed, "death: onClosed once");

		Counter replaced = counter(false);
		for (int i = 0; i < 3; i++) replaced.guard.removed();
		eq(1, replaced.ended, "replaced: onEnded once");
		eq(0, replaced.closed, "replaced: no navigation");
		isTrue(replaced.guard.ended() && !replaced.guard.closed(), "state ended/!closed");

		Counter reversed = counter(false);
		reversed.guard.manualClose();
		reversed.guard.removed();
		eq(1, reversed.ended, "reversed: onEnded once");
		eq(1, reversed.closed, "reversed: onClosed once");

		Counter re = counter(true);
		re.guard.removed();
		eq(1, re.ended, "re-entry: onEnded once");
		eq(1, re.closed, "re-entry: onClosed once (from the hook)");
		re.guard.manualClose();
		eq(1, re.closed, "no further onClosed afterwards");
	}

	/** Real sgui paths on a test player's GUIs. */
	private static void closeHookOnce(TestContext ctx) {
		FakePlayer p = ctx.player("SmpecoGuiClose");
		int[] settled = {0};
		int[] nav = {0};
		SmpGui gui = new SmpGui(MenuType.GENERIC_9x3, p, false) {
			@Override
			protected void onEnded() {
				settled[0]++;
			}

			@Override
			protected void onClosed() {
				nav[0]++;
			}
		};
		isFalse(gui.isEnded(), "built: not ended yet");
		eq(0, settled[0], "building does not settle");
		gui.close(true);
		eq(1, settled[0], "sgui close settles");
		eq(1, nav[0], "sgui close navigates");
		gui.close(true);
		gui.closeGuard().removed();
		gui.close();
		eq(1, settled[0], "hook called twice or more: exactly one settlement");
		eq(1, nav[0], "navigation at most once");
		isTrue(gui.isEnded(), "ended");

		int[] settled2 = {0};
		int[] nav2 = {0};
		SmpGui replaced = new SmpGui(MenuType.GENERIC_9x1, p, false) {
			@Override
			protected void onEnded() {
				settled2[0]++;
			}

			@Override
			protected void onClosed() {
				nav2[0]++;
			}
		};
		replaced.closeGuard().removed();
		replaced.closeGuard().removed();
		eq(1, settled2[0], "replaced: one settlement");
		eq(0, nav2[0], "replaced: no navigation");
		replaced.close(true);
		eq(1, settled2[0], "later close does not settle again");

		int[] settled3 = {0};
		int[] nav3 = {0};
		SmpAnvilGui anvil = new SmpAnvilGui(p, false) {
			@Override
			protected void onEnded() {
				settled3[0]++;
			}

			@Override
			protected void onClosed() {
				nav3[0]++;
			}
		};
		anvil.close(true);
		anvil.close(true);
		anvil.closeGuard().removed();
		eq(1, settled3[0], "anvil: one settlement");
		eq(1, nav3[0], "anvil: one navigation");
		ctx.detail("onEnded=1/onClosed<=1 via close(true) x2, removed, close()");
	}
}
