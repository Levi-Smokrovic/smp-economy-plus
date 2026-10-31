package dev.smpeconomy.features.crates;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.gui.SmpGui;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.features.crates.CrateService.DeliverResult;
import dev.smpeconomy.features.crates.CrateService.PendingReward;
import dev.smpeconomy.features.crates.CratesConfig.CrateDef;

/**
 * Reward spin animation, about 3 s. The reward is already decided and stored ({@link CrateService#open}); this
 * GUI only delivers it, on landing or at the latest on close (ESC, other menu, death, disconnect, server
 * stop). {@link SpinSession} makes that exactly once.
 */
final class SpinGui extends SmpGui {
	static final int STEPS = 32;
	/** 16x1, 8x2, 4x3, 3x4, 1x6 = 62 ticks. */
	static final int[] DELAYS = delays();
	static final int CENTER = 13;
	private static final int REEL_START = 9;
	private static final int MARKER_TOP = 4;
	private static final int MARKER_BOTTOM = 22;
	private static final int BUTTON_BACK = 18;
	private static final int BUTTON_AGAIN = 26;
	private static final Item[] PANES = {
		net.minecraft.world.item.Items.RED_STAINED_GLASS_PANE, net.minecraft.world.item.Items.ORANGE_STAINED_GLASS_PANE,
		net.minecraft.world.item.Items.YELLOW_STAINED_GLASS_PANE, net.minecraft.world.item.Items.LIME_STAINED_GLASS_PANE,
		net.minecraft.world.item.Items.LIGHT_BLUE_STAINED_GLASS_PANE, net.minecraft.world.item.Items.MAGENTA_STAINED_GLASS_PANE,
		net.minecraft.world.item.Items.PURPLE_STAINED_GLASS_PANE, net.minecraft.world.item.Items.PINK_STAINED_GLASS_PANE};

	private final CrateDef crate;
	private final SpinSession session;
	private final HolderLookup.Provider registries;
	private final List<GuiElementBuilder> reel;
	private final Random fx = new Random();
	private int step;
	private int wait;
	private boolean landed;

	SpinGui(ServerPlayer player, CrateDef crate, PendingReward reward, HolderLookup.Provider registries) {
		super(MenuType.GENERIC_9x3, player, false);
		this.crate = crate;
		this.registries = registries;
		this.session = new SpinSession(player.getUUID(), crate.id, reward);
		this.reel = buildReel(crate, reward, registries, fx);
		setTitle(Msg.legacy(crate.name + " §8» §7opening …"));
		decorate(true);
		render();
		this.wait = DELAYS[0];
		CrateService.markActive(reward.id); // Join and claim delivery leave this reward to the animation
	}

	static int[] delays() {
		int[] d = new int[STEPS];
		for (int i = 0; i < STEPS; i++) {
			if (i < 16) d[i] = 1;
			else if (i < 24) d[i] = 2;
			else if (i < 28) d[i] = 3;
			else if (i < 31) d[i] = 4;
			else d[i] = 6;
		}
		return d;
	}

	static int totalTicks() {
		int sum = 0;
		for (int d : DELAYS) sum += d;
		return sum;
	}

	/** Field {@code STEPS + 4} is in the center after the last step and holds the reward. */
	static List<GuiElementBuilder> buildReel(CrateDef crate, PendingReward reward, HolderLookup.Provider registries, Random random) {
		List<GuiElementBuilder> entries = new ArrayList<>();
		for (CratesConfig.RewardDef r : crate.rewards) entries.add(CratesGui.rewardIcon(r, registries));
		List<GuiElementBuilder> reel = new ArrayList<>(STEPS + 9);
		for (int i = 0; i < STEPS + 9; i++) {
			if (i == STEPS + 4) reel.add(CratesGui.pendingIcon(reward, registries).glow());
			else reel.add(entries.get(CrateRewards.pickIndex(CrateRewards.weights(crate), random.nextLong(crate.totalWeight()))));
		}
		return reel;
	}

	SpinSession session() {
		return session;
	}

	private int lastTick = Integer.MIN_VALUE;

	@Override
	public void onTick() {
		if (landed || session.finished()) return;
		// sgui also calls onTick after every click packet, and the animation may advance at most once per server tick
		net.minecraft.server.MinecraftServer server = player.level().getServer();
		int tick = server == null ? -1 : server.getTickCount();
		if (tick == lastTick) return;
		lastTick = tick;
		if (--wait > 0) return;
		step++;
		render();
		decorate(false);
		float progress = step / (float) STEPS;
		Gui.sound(player, SoundEvents.NOTE_BLOCK_HAT, 0.8f + 0.8f * progress);
		if (step >= STEPS) {
			land();
		} else {
			wait = DELAYS[step];
		}
	}

	private void render() {
		for (int i = 0; i < 9; i++) setSlot(REEL_START + i, reel.get(Math.min(step + i, reel.size() - 1)));
	}

	private void decorate(boolean initial) {
		for (int row : new int[] {0, 2}) {
			for (int col = 0; col < 9; col++) {
				int slot = row * 9 + col;
				if (slot == MARKER_TOP || slot == MARKER_BOTTOM) {
					if (initial) setSlot(slot, Gui.item(net.minecraft.world.item.Items.HOPPER, slot == MARKER_TOP ? "§e§l▼ Reward ▼" : "§e§l▲ Reward ▲"));
					continue;
				}
				Item pane = landed ? net.minecraft.world.item.Items.LIME_STAINED_GLASS_PANE : PANES[fx.nextInt(PANES.length)];
				setSlot(slot, new GuiElementBuilder(pane).setName(Msg.legacy(" ")).hideTooltip());
			}
		}
	}

	private void land() {
		landed = true;
		DeliverResult result = finish(false);
		decorate(false);
		if (result == null) return;
		// Buttons only after landing
		setSlot(BUTTON_BACK, Gui.backButton(() -> CratesGui.openMain(player)));
		setSlot(BUTTON_AGAIN, Gui.button(net.minecraft.world.item.Items.TRIPWIRE_HOOK, "§aOpen Again",
			() -> CratesGui.openCrate(player, crate.id),
			"§7Keys: §e" + dev.smpeconomy.core.CrateKeys.count(player.getUUID(), crate.id)));
		if (Gui.canOpen(player)) {
			if (result.delivered()) {
				var sound = session.reward().announce ? SoundEvents.UI_TOAST_CHALLENGE_COMPLETE : SoundEvents.PLAYER_LEVELUP;
				Gui.sound(player, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound), 1f);
			} else {
				Gui.failSound(player);
			}
		}
	}

	/** {@code skipped} means closed before the end. */
	private DeliverResult finish(boolean skipped) {
		if (session.finished()) return null;
		DeliverResult result;
		try {
			// Never drop at their feet (void, lava, other players): if it does not fit it stays pending for /crates claim
			result = session.finish(player, false, registries);
		} finally {
			CrateService.unmarkActive(session.reward().id);
		}
		CratesGui.reportSpin(player, crate, result, skipped);
		return result;
	}

	void finishNow() {
		landed = true;
		finish(true);
	}

	/** Every path (ESC, death, disconnect, even another menu replacing this one) delivers exactly once. */
	@Override
	protected void onEnded() {
		if (!session.finished()) finish(true);
	}
}
