package dev.smpeconomy.features.missions;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import dev.smpeconomy.core.CrateKeys;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.TimeUtil;
import dev.smpeconomy.features.missions.MissionStore.ActiveMission;
import dev.smpeconomy.features.missions.MissionStore.PlayerState;

/**
 * Chest GUIs: mission board (6 rows, one per tier) and active missions (deliver, abandon). Display only, no real slots;
 * every action rechecks everything when it runs.
 */
final class MissionGui {
	static final String BOARD_TITLE = "§8Mission Board";
	static final String ACTIVE_TITLE = "§8Active Missions";
	static final String ACCEPT_TITLE = "§8Accept Mission?";
	static final String CANCEL_TITLE = "§8Abandon Mission?";
	static final String ELITE_TITLE = "§8Hand In Daily Elite?";
	/** First tier row on the board. Slot 0 of a row is the tier sign, 1-7 are offers. */
	static final int FIRST_TIER_ROW = 1;
	static final int SLOT_INFO = 1;
	/** Daily Elite: top center, framed by two magenta panes. */
	static final int SLOT_ELITE = 4;
	static final int SLOT_MENU = 45;
	static final int SLOT_ACTIVE = 47;
	static final int SLOT_ROTATION = 49;
	static final int SLOT_CLOSE = 51;

	private MissionGui() {}

	static int offerSlot(Tier tier, int index) {
		return (FIRST_TIER_ROW + tier.ordinal()) * 9 + 1 + index;
	}

	static void openBoard(ServerPlayer player) {
		if (!Gui.canOpen(player)) return;
		long now = System.currentTimeMillis();
		MissionService.update(player, now);
		long period = MissionService.period(now);
		int[] tick = {0};
		SimpleGui gui = new SimpleGui(Gui.menuType(6), player, false) {
			@Override
			public void onTick() {
				// refresh the Daily Elite (countdown to midnight, progress) every second
				if (++tick[0] % 20 == 0 && isOpen()) renderElite(this, player);
			}
		};
		gui.setTitle(Msg.legacy(BOARD_TITLE));
		UUID id = player.getUUID();
		PlayerState s = MissionService.state(id, now);

		gui.setSlot(SLOT_INFO, Gui.item(Items.BOOK, "§6§lMission Board",
			"§7Take up to §f" + MissionConfig.maxActive() + "§7 missions at once.",
			"§7Active: §f" + s.active.size() + "§7/§f" + MissionConfig.maxActive(),
			"§7New offers in: §f" + TimeUtil.formatDuration(MissionService.periodEnd(period) - now),
			now < s.cooldownUntil ? "§cCooldown: §f" + TimeUtil.formatDuration(s.cooldownUntil - now) : "§8No cooldown",
			"",
			"§8Click a mission to accept it.",
			"§8Top: the Daily Elite (same for everyone)."));
		renderElite(gui, player);

		for (Tier tier : Tier.values()) {
			int row = FIRST_TIER_ROW + tier.ordinal();
			gui.setSlot(row * 9, tierLabel(tier));
			List<Mission> offers = MissionService.board(id, period, tier);
			for (int i = 0; i < MissionConfig.MAX_OFFERS; i++) {
				int slot = offerSlot(tier, i);
				if (i < offers.size()) gui.setSlot(slot, offerElement(player, offers.get(i), period, now));
				else gui.setSlot(slot, Gui.filler());
			}
		}

		gui.setSlot(SLOT_ACTIVE, Gui.button(Items.WRITABLE_BOOK, "§e§lActive Missions §7(" + s.active.size() + "/" + MissionConfig.maxActive() + ")",
			() -> openActive(player), "§7View progress, deliver items", "§7or abandon missions."));
		gui.setSlot(SLOT_ROTATION, Gui.item(Items.CLOCK, "§7Next Rotation",
			"§f" + TimeUtil.formatDuration(MissionService.periodEnd(period) - now),
			"§8New offers every " + MissionConfig.rotationHours() + "h (per player)."));
		Gui.menuButton(gui, SLOT_MENU);
		gui.setSlot(SLOT_CLOSE, Gui.closeButton(gui));
		Gui.frame(gui);
		Gui.fillEmpty(gui);
		gui.open();
	}

	private static GuiElementBuilder tierLabel(Tier tier) {
		MissionConfig.TierSettings t = tier.settings();
		List<String> lore = new ArrayList<>();
		lore.add("§7Time limit: §f" + TimeUtil.formatDuration(t.timeLimitMinutes * 60_000L));
		if (t.keys > 0 && CrateKeys.enabled()) {
			lore.add("§7Bonus: §d" + t.keys + "× " + CrateKeys.label(t.keyTier) + " Key");
			lore.add("§8(not for deliveries)");
		}
		lore.add("§8Offers per rotation: " + t.offers);
		Item icon = dev.smpeconomy.core.Items.byId(t.icon).orElse(Items.PAPER);
		return Gui.item(icon, tier.coloredLabel(), lore);
	}

	private static GuiElementBuilder offerElement(ServerPlayer player, Mission m, long period, long now) {
		UUID id = player.getUUID();
		MissionService.AcceptResult status = MissionService.canAccept(id, m.id, period, now);
		List<String> lore = describe(id, m, m.def, m.def.amount, MissionService.reward(m), MissionService.keys(m), MissionService.keyTier(m));
		lore.add("§7Time limit: §f" + TimeUtil.formatDuration(MissionService.timeLimitMinutes(m) * 60_000L));
		lore.add("");
		lore.add(switch (status) {
			case OK -> "§eClick to accept";
			case EXPLORED -> "§8✔ Already explored (pays only once)";
			case TAKEN -> "§8✔ Already taken this rotation";
			case ALREADY_ACTIVE -> "§8Already active";
			case SAME_TARGET -> "§cSame target already active";
			case FULL -> "§cAll slots full (" + MissionConfig.maxActive() + "/" + MissionConfig.maxActive() + ")";
			case COOLDOWN -> "§cCooldown: " + TimeUtil.formatDuration(MissionService.state(id, now).cooldownUntil - now);
			default -> "§cNot available";
		});
		String name = m.tier.settings().color + m.def.name;
		return Gui.button(m.icon, name, () -> onOfferClick(player, m.id, period), lore.toArray(String[]::new));
	}

	private static void onOfferClick(ServerPlayer player, String missionId, long period) {
		long now = System.currentTimeMillis();
		MissionService.AcceptResult check = MissionService.canAccept(player.getUUID(), missionId, period, now);
		if (check != MissionService.AcceptResult.OK) {
			Msg.error(player, check.message(MissionService.state(player.getUUID(), now), now));
			Gui.failSound(player);
			if (check == MissionService.AcceptResult.ROTATED || check == MissionService.AcceptResult.UNKNOWN) openBoard(player);
			return;
		}
		Mission m = MissionConfig.byId(missionId);
		List<String> lore = describe(player.getUUID(), m, m.def, m.def.amount, MissionService.reward(m), MissionService.keys(m), MissionService.keyTier(m));
		lore.add("§7Time limit: §f" + TimeUtil.formatDuration(MissionService.timeLimitMinutes(m) * 60_000L));
		lore.add("");
		lore.add("§7Accept this mission?");
		GuiElementBuilder preview = Gui.item(m.icon, m.tier.settings().color + m.def.name, lore);
		Gui.confirm(player, ACCEPT_TITLE, preview, () -> {
			long t = System.currentTimeMillis();
			MissionService.AcceptResult r = MissionService.accept(player, missionId, period, t);
			if (r == MissionService.AcceptResult.OK) {
				Msg.success(player, "§aMission accepted: §f" + m.def.name + "§a. Time limit: §f"
					+ TimeUtil.formatDuration(MissionService.timeLimitMinutes(m) * 60_000L));
				Gui.successSound(player);
				if (Gui.canOpen(player)) openActive(player);
			} else {
				Msg.error(player, r.message(MissionService.state(player.getUUID(), t), t));
				Gui.failSound(player);
				if (Gui.canOpen(player)) openBoard(player);
			}
		}, () -> openBoard(player));
	}

	static void renderElite(SimpleGui gui, ServerPlayer player) {
		long now = System.currentTimeMillis();
		EliteService.Today t = EliteService.today(now);
		if (t == null) {
			gui.setSlot(SLOT_ELITE, Gui.border());
			gui.setSlot(SLOT_ELITE - 1, Gui.border());
			gui.setSlot(SLOT_ELITE + 1, Gui.border());
			return;
		}
		GuiElementBuilder pane = new GuiElementBuilder(Items.MAGENTA_STAINED_GLASS_PANE).setName(Msg.legacy("§d✦ Daily Elite ✦")).hideTooltip();
		gui.setSlot(SLOT_ELITE - 1, pane);
		gui.setSlot(SLOT_ELITE + 1, pane);
		gui.setSlot(SLOT_ELITE, eliteElement(player, t, now));
	}

	/** Also used by the e2e text checks. */
	static List<String> eliteLore(ServerPlayer player, EliteService.Today t, long now) {
		UUID id = player.getUUID();
		EliteConfig.Elite el = t.elite();
		boolean done = EliteService.doneToday(id, t);
		long pay = EliteService.payout(id, el);
		int keys = EliteService.keys(el);
		List<String> lore = new ArrayList<>();
		lore.add("§7" + el.def().description);
		lore.add("");
		lore.add("§7Type: §f" + el.type().label + " §8(Elite, same for everyone)");
		if (el.type() == EliteConfig.Type.DELIVER && pay <= 0) lore.add("§7Reward: §cno market value right now");
		else lore.add("§7Reward: " + EliteService.rewardText(pay, keys, EliteConfig.keyTier(el.def())));
		if (el.type() == EliteConfig.Type.DELIVER && pay < el.def().rewardCents && pay > 0) lore.add("§8(capped by the price limit)");
		long progress = EliteService.progressValue(id, t);
		lore.add("§7Progress: " + progressBar(progress, el.amount()) + " §f" + Msg.number(progress) + "/" + Msg.number(el.amount()));
		lore.add("§7New Daily Elite in: §f" + TimeUtil.formatDuration(EliteService.millisUntilReset(now)));
		switch (el.type()) {
			case KILL -> {
				lore.add("§8Only natural mobs you kill yourself since midnight.");
				lore.add("§8Spawner, spawn egg, bred and summoned");
				lore.add("§8mobs don't count.");
			}
			case RAID -> {
				lore.add("§8Counts when you win a raid that started at level " + el.def().minRaidLevel);
				lore.add("§8(Bad Omen mid-raid doesn't raise the level),");
				lore.add("§8you killed at least " + el.def().minRaidKills + " raiders and " + Math.round(el.def().minRaidKillShare * 100)
					+ "% of all");
				lore.add("§8player kills, and you're in the village at the end.");
			}
			case DELIVER -> {
				lore.add("§8All " + el.amount() + " at once, unmodified items only.");
				lore.add("§7In inventory: §f" + Msg.number(dev.smpeconomy.core.Items.countPristine(player, el.item())) + "§7/§f" + Msg.number(el.amount()));
			}
		}
		lore.add("§8Once per player per day.");
		lore.add("");
		if (done) lore.add("§a✔ Completed today!");
		else if (el.type() == EliteConfig.Type.DELIVER) lore.add("§eClick to hand in");
		else lore.add("§eCounts automatically – no need to accept.");
		return lore;
	}

	private static GuiElementBuilder eliteElement(ServerPlayer player, EliteService.Today t, long now) {
		EliteConfig.Elite el = t.elite();
		List<String> lore = eliteLore(player, t, now);
		boolean done = EliteService.doneToday(player.getUUID(), t);
		String name = (done ? "§a§l✔ " : "§d§l✦ ") + "Daily Elite: §f" + el.def().name;
		GuiElementBuilder b = Gui.button(done ? Items.LIME_DYE : el.icon(), name, () -> onEliteClick(player), lore.toArray(String[]::new));
		if (!done) b.glow();
		return b;
	}

	private static void onEliteClick(ServerPlayer player) {
		long now = System.currentTimeMillis();
		EliteService.Today t = EliteService.today(now);
		if (t == null) {
			Msg.error(player, "There's no Daily Elite today.");
			return;
		}
		EliteConfig.Elite el = t.elite();
		UUID id = player.getUUID();
		if (EliteService.doneToday(id, t)) {
			Msg.info(player, "§aDaily Elite already completed today! §7Next one in §f" + TimeUtil.formatDuration(EliteService.millisUntilReset(now)) + "§7.");
			return;
		}
		if (el.type() != EliteConfig.Type.DELIVER) {
			Msg.info(player, "§d" + el.def().name + "§7: " + EliteService.progressValue(id, t) + "/" + el.amount()
				+ " §8– counts automatically, no need to accept.");
			return;
		}
		// delivery: check first without taking anything, then confirm; deliver() rechecks everything on confirm
		EliteService.DeliverResult check = EliteService.check(player, t);
		if (check.status() != EliteService.DeliverStatus.OK) {
			Msg.error(player, EliteService.deliverMessage(check, t));
			Gui.failSound(player);
			return;
		}
		List<String> lore = new ArrayList<>(List.of("§7Hands in §f" + el.amount() + "× §7unmodified items.",
			"§7Reward: " + EliteService.rewardText(check.paid(), EliteService.keys(el), EliteConfig.keyTier(el.def())),
			"", "§7Hand them in?"));
		GuiElementBuilder preview = Gui.item(el.icon(), "§d§lDaily Elite: §f" + el.def().name, lore);
		// exactly the delivery shown gets confirmed: if the Daily Elite changed since (midnight, admin), deliver()
		// takes nothing (CHANGED) and the board shows the new one
		Gui.confirm(player, ELITE_TITLE, preview, () -> {
			long at = System.currentTimeMillis();
			EliteService.DeliverResult r = EliteService.deliver(player, at, t);
			if (r.status() != EliteService.DeliverStatus.OK) {
				Msg.error(player, EliteService.deliverMessage(r, EliteService.today(at)));
				Gui.failSound(player);
			}
			if (Gui.canOpen(player)) openBoard(player);
		}, () -> openBoard(player));
	}

	/** Columns (0-8) of active missions: up to 3 spread around the center, otherwise 1-7. */
	static int[] activeColumns() {
		return activeColumns(0);
	}

	/**
	 * As above, with at least {@code activeCount} columns, so after a reload with a smaller maxActive every active
	 * mission stays reachable.
	 */
	static int[] activeColumns(int activeCount) {
		int max = Math.max(MissionConfig.maxActive(), activeCount);
		if (max == 1) return new int[] {4};
		if (max == 2) return new int[] {3, 5};
		if (max == 3) return new int[] {2, 4, 6};
		int[] cols = new int[Math.min(7, max)];
		for (int i = 0; i < cols.length; i++) cols[i] = 1 + i;
		return cols;
	}

	static void openActive(ServerPlayer player) {
		if (!Gui.canOpen(player)) return;
		int[] tick = {0};
		SimpleGui gui = new SimpleGui(Gui.menuType(5), player, false) {
			@Override
			public void onTick() {
				// refresh progress and time left every second (stat missions change while playing)
				if (++tick[0] % 20 == 0 && isOpen()) renderActive(this, player);
			}
		};
		gui.setTitle(Msg.legacy(ACTIVE_TITLE));
		renderActive(gui, player);
		gui.open();
	}

	static void renderActive(SimpleGui gui, ServerPlayer player) {
		long now = System.currentTimeMillis();
		UUID id = player.getUUID();
		MissionService.update(player, now);
		PlayerState s = MissionService.state(id, now);
		for (int i = 0; i < gui.getVirtualSize(); i++) gui.clearSlot(i);
		int[] cols = activeColumns(s.active.size());
		for (int i = 0; i < cols.length; i++) {
			int col = cols[i];
			if (i < s.active.size()) {
				ActiveMission a = s.active.get(i);
				renderActiveColumn(gui, player, a, col, now);
			} else {
				gui.setSlot(9 + col, Gui.item(Items.LIGHT_GRAY_STAINED_GLASS_PANE, "§8Free Slot", "§7Accept a new mission", "§7on the Mission Board."));
			}
		}
		gui.setSlot(36, Gui.backButton(() -> openBoard(player)));
		gui.setSlot(40, Gui.item(Items.BOOK, "§6§lActive Missions",
			"§7Active: §f" + s.active.size() + "§7/§f" + MissionConfig.maxActive(),
			now < s.cooldownUntil ? "§cCooldown: §f" + TimeUtil.formatDuration(s.cooldownUntil - now) : "§8No cooldown",
			"§7Completed: §f" + Msg.number(s.completed)));
		gui.setSlot(44, Gui.closeButton(gui));
		Gui.frame(gui);
		Gui.fillEmpty(gui);
	}

	private static void renderActiveColumn(SimpleGui gui, ServerPlayer player, ActiveMission a, int col, long now) {
		Mission m = a.mission();
		UUID id = player.getUUID();
		long instance = a.instance;
		List<String> lore = m == null ? new ArrayList<>(List.of("§cInvalid mission – please abandon it."))
			: describe(id, m, a.def, a.amount(), a.reward, a.keys, a.keyTier);
		if (m != null && m.type == Mission.Type.DELIVER) lore.add("§7Earned so far: " + Msg.moneyStr(a.paid));
		lore.add("");
		lore.add("§7Progress: " + progressBar(a.progress, a.amount()) + " §f" + Msg.number(a.progress) + "/" + Msg.number(a.amount()));
		lore.add("§7Time left: §f" + TimeUtil.formatDuration(a.expiresAt - now));
		if (m != null && m.type == Mission.Type.EXPLORE && !a.armed) lore.add("§8You're already at the target – leave it and enter again.");
		Item icon = m == null ? Items.BARRIER : m.icon;
		String color = m == null ? "§c" : m.tier.settings().color;
		gui.setSlot(9 + col, Gui.item(icon, color + a.name(), lore));

		if (m != null && m.type == Mission.Type.DELIVER) {
			long per = MissionService.deliveryPrice(id, m, a.def);
			int have = dev.smpeconomy.core.Items.countPristine(player, m.item);
			long remaining = Math.max(0, a.amount() - a.progress);
			List<String> dl = new ArrayList<>();
			dl.add("§7Hands in unmodified items from your");
			dl.add("§7inventory (§f" + Msg.number(remaining) + "§7 still needed).");
			dl.add("§7In inventory: §f" + Msg.number(have));
			dl.add(per > 0 ? "§7Price per item: " + Msg.moneyStr(per) + " §8(current)" : "§cNo market value right now – deliver later.");
			dl.add("");
			dl.add("§eClick to deliver");
			gui.setSlot(18 + col, Gui.button(Items.HOPPER, "§a§lDeliver", () -> {
				MissionService.DeliverResult r = MissionService.deliver(player, instance, System.currentTimeMillis());
				if (r.status() == MissionService.DeliverStatus.OK) {
					if (r.delivered() > 0) Msg.success(player, MissionService.deliverMessage(r));
					if (!r.completed()) Gui.successSound(player);
				} else {
					Msg.error(player, MissionService.deliverMessage(r));
					Gui.failSound(player);
				}
				if (gui.isOpen()) renderActive(gui, player);
			}, dl.toArray(String[]::new)));
		} else if (m != null) {
			gui.setSlot(18 + col, Gui.item(m.type == Mission.Type.EXPLORE ? Items.COMPASS : Items.SPYGLASS, "§7" + m.category(),
				m.type == Mission.Type.EXPLORE ? "§7Detected automatically as soon" : "§7Counts automatically since you",
				m.type == Mission.Type.EXPLORE ? "§7as you enter the target." : "§7accepted it (checked every second)."));
		}

		gui.setSlot(27 + col, Gui.button(Items.RED_DYE, "§c§lAbandon", () -> {
			GuiElementBuilder preview = Gui.item(icon, color + a.name(), "§7Really abandon this mission?",
				"§7Then §f" + TimeUtil.formatDuration(MissionConfig.cancelCooldownMs()) + "§7 cooldown.",
				"§8Money you already earned stays yours.");
			Gui.confirm(player, CANCEL_TITLE, preview, () -> {
				long t = System.currentTimeMillis();
				if (MissionService.cancel(player, instance, t)) {
					Msg.info(player, "§eMission abandoned: §f" + a.name() + "§e. You can take new missions in §f"
						+ TimeUtil.formatDuration(MissionConfig.cancelCooldownMs()) + "§e.");
				} else {
					Msg.error(player, "This mission is no longer active.");
				}
				if (Gui.canOpen(player)) openActive(player);
			}, () -> openActive(player));
		}, "§7Give up this mission", "§8(" + TimeUtil.formatDuration(MissionConfig.cancelCooldownMs()) + " cooldown)"));
	}

	static List<String> describe(UUID player, Mission m, MissionConfig.MissionDef def, int amount, long reward, int keys, String keyTier) {
		List<String> lore = new ArrayList<>();
		lore.add("§7" + def.description);
		lore.add("");
		lore.add("§7Type: §f" + m.category() + " §8(" + m.tier.settings().label + ")");
		if (m.type == Mission.Type.DELIVER) {
			long per = MissionService.deliveryPrice(player, m, def);
			if (per > 0) {
				long total = MissionService.safeMultiply(per, amount);
				lore.add("§7Reward: §aup to " + (total < 0 ? "?" : dev.smpeconomy.core.Money.format(total)) + " §8(" + dev.smpeconomy.core.Money.format(per) + " each)");
			} else {
				lore.add("§7Reward: §cno market value right now");
			}
			lore.add("§8Paid per item delivered, price limit applies.");
		} else if (reward > 0) {
			lore.add("§7Reward: " + Msg.moneyStr(reward));
		}
		if (m.type == Mission.Type.EXPLORE) lore.add("§8Each target pays only once per player.");
		if (m.type == Mission.Type.STAT && m.hasMined()) lore.add("§8Natural blocks only; a Veinminer chain counts once.");
		if (m.type == Mission.Type.STAT && m.hasKilled()) lore.addAll(killRules());
		if (keys > 0 && MissionConfig.validKeyTier(keyTier)) lore.add("§7Bonus: §d" + keys + "× " + CrateKeys.label(keyTier) + " Key");
		return lore;
	}

	/** Hint for hunt missions on which mobs count (see {@link MissionService#killMilli}). */
	static List<String> killRules() {
		double f = MissionConfig.spawnerKillFactor();
		String spawner = f >= 1 ? "." : f <= 0 ? "; neither do spawner mobs." : "; spawner mobs count " + Math.round(f * 100) + "%.";
		return List.of("§8Bred, summoned and respawned", "§8mobs don't count" + spawner);
	}

	static String progressBar(long progress, long amount) {
		int filled = amount <= 0 ? 10 : (int) Math.max(0, Math.min(10, progress * 10 / amount));
		return "§a" + "■".repeat(filled) + "§8" + "■".repeat(10 - filled);
	}
}
