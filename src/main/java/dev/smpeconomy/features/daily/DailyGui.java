package dev.smpeconomy.features.daily;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import dev.smpeconomy.core.CrateKeys;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.TimeUtil;

/**
 * Display-only GUI without real slots. Claiming goes through {@link Gui#button} (click filter, debounce) and {@link
 * DailyService#claim}. Refreshes every second for the countdown and the midnight rollover.
 */
public final class DailyGui {
	public static final String TITLE = "§8Daily Reward";
	public static final int SLOT_INFO = 4;
	public static final int FIRST_DAY_SLOT = 10;
	public static final int SLOT_MENU = 18;
	public static final int SLOT_CLAIM = 22;
	public static final int SLOT_CLOSE = 26;
	private static final int ROWS = 3;

	private DailyGui() {}

	public static void open(ServerPlayer player) {
		if (!Gui.canOpen(player)) return;
		SimpleGui gui = new SimpleGui(Gui.menuType(ROWS), player, false) {
			private int ticks;

			@Override
			public void onTick() {
				if (++ticks % 20 == 0) render(this, player);
			}
		};
		gui.setTitle(Msg.legacy(TITLE));
		render(gui, player);
		gui.open();
	}

	static void render(SimpleGui gui, ServerPlayer player) {
		DailyConfig cfg = DailyConfig.get();
		long now = DailyService.now();
		DailyState state = DailyService.state(player.getUUID());
		DailyLogic.Status st = DailyLogic.status(state, now, cfg.breakMs());

		Runnable claim = () -> {
			DailyService.claimAndNotify(player);
			if (gui.isOpen()) render(gui, player);
		};
		for (int day = 1; day <= DailyLogic.DAYS; day++) {
			gui.setSlot(FIRST_DAY_SLOT + day - 1, dayElement(cfg, st, day, claim));
		}
		gui.setSlot(SLOT_INFO, infoElement(cfg, state, st, now));
		gui.setSlot(SLOT_CLAIM, claimElement(cfg, st, now, claim));
		Gui.menuButton(gui, SLOT_MENU);
		gui.setSlot(SLOT_CLOSE, Gui.closeButton(gui));
		Gui.frame(gui, true);
		Gui.fillEmpty(gui);
	}

	private static List<String> rewardLore(DailyConfig cfg, int day) {
		List<String> lore = new ArrayList<>();
		long money = cfg.reward(day);
		if (money > 0) lore.add("§7Reward: " + Msg.moneyStr(money));
		int keys = cfg.keysFor(day);
		if (keys > 0) lore.add("§7Bonus: §6" + keys + "× " + CrateKeys.label(cfg.day7KeyTier) + " Key");
		return lore;
	}

	private static GuiElementBuilder dayElement(DailyConfig cfg, DailyLogic.Status st, int day, Runnable claim) {
		boolean last = day == DailyLogic.DAYS;
		Item chest = last ? Items.ENDER_CHEST : Items.CHEST;
		List<String> lore = rewardLore(cfg, day);
		lore.add("");
		GuiElementBuilder b;
		switch (st.dayState(day)) {
			case CLAIMED -> {
				lore.add("§a✔ Claimed");
				b = Gui.item(Items.LIME_STAINED_GLASS, "§aDay " + day, lore);
			}
			case AVAILABLE -> {
				lore.add("§a» Click to claim");
				b = Gui.button(chest, "§e§lDay " + day + " §8– §aclaim now!", claim, lore.toArray(String[]::new)).glow();
			}
			case TOMORROW -> {
				lore.add("§7Claimable from midnight");
				b = Gui.item(chest, "§fDay " + day, lore);
			}
			default -> {
				lore.add("§8Locked");
				b = Gui.item(chest, "§7Day " + day, lore);
			}
		}
		return b.setCount(day);
	}

	private static GuiElementBuilder infoElement(DailyConfig cfg, DailyState state, DailyLogic.Status st, long now) {
		List<String> lore = new ArrayList<>();
		lore.add("§7Best streak: §f" + DailyService.days(Math.max(state.bestStreak, st.streak())));
		lore.add("§7Total claims: §f" + Msg.number(state.totalClaims));
		lore.add("§7Earned: " + Msg.moneyStr(state.totalEarned));
		lore.add("");
		if (st.claimable()) {
			lore.add("§aYour reward is ready!");
		} else {
			lore.add("§7Next reward in §f" + TimeUtil.formatDuration(st.nextClaimAtMs() - now));
		}
		if (st.broken() && st.hadStreak()) {
			lore.add("§cStreak lost §7– back to Day 1");
		} else if (st.streak() > 0) {
			lore.add("§7Streak safe until §f" + DailyService.formatDeadline(st.streakDeadlineMs()) + " §8(" + TimeUtil.formatDuration(st.streakDeadlineMs() - now) + ")");
		}
		lore.add("");
		lore.add("§8New day at midnight (" + dev.smpeconomy.core.TimeUtil.zone().getId() + ")");
		lore.add("§8Streak resets after " + cfg.streakBreakHours + "h without claiming");
		return Gui.item(Items.CLOCK, "§6§lYour Streak: §f" + DailyService.days(st.streak()), lore);
	}

	private static GuiElementBuilder claimElement(DailyConfig cfg, DailyLogic.Status st, long now, Runnable claim) {
		if (st.claimable()) {
			List<String> lore = new ArrayList<>();
			lore.add("§7Day §f" + st.nextDay() + "§7 of " + DailyLogic.DAYS);
			lore.addAll(rewardLore(cfg, st.nextDay()));
			lore.add("");
			lore.add("§a» Click to claim");
			return Gui.button(Items.SUNFLOWER, "§a§lClaim Reward", claim, lore.toArray(String[]::new)).glow();
		}
		// the click only shows the wait; DailyService rejects the claim
		return Gui.button(Items.GRAY_DYE, "§7Already claimed today", claim,
			"§7Next reward in §f" + TimeUtil.formatDuration(st.nextClaimAtMs() - now));
	}
}
