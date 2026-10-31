package dev.smpeconomy.features.jobs;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Hooks;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.Settings;
import dev.smpeconomy.core.TimeUtil;
import dev.smpeconomy.features.jobs.JobsConfig.Example;
import dev.smpeconomy.features.jobs.JobsConfig.Job;

/**
 * Display-only GUI with no real slots, so items can't be taken out. Clicking a job joins it; switching asks for
 * confirmation because the cooldown starts afterwards.
 */
public final class JobsGui {
	public static final String TITLE = "§8Jobs";
	private static final int ROWS = 5;
	/** Slots for up to 6 jobs (two rows of 3); more jobs use the inner slots. */
	private static final int[] JOB_SLOTS = {11, 13, 15, 29, 31, 33};
	static final int INFO_SLOT = 4;
	static final int MENU_SLOT = 36;
	static final int ACTIONBAR_SLOT = 38;
	static final int CLOSE_SLOT = 40;
	static final int LEAVE_SLOT = 42;

	private JobsGui() {}

	public static void open(ServerPlayer player) {
		if (!Gui.canOpen(player)) return;
		SimpleGui gui = Gui.chest(player, ROWS, TITLE);
		render(gui, player);
		gui.open();
	}

	private static void render(SimpleGui gui, ServerPlayer player) {
		for (int i = 0; i < gui.getVirtualSize(); i++) gui.clearSlot(i);
		UUID id = player.getUUID();
		Job current = JobsService.currentJob(id);
		gui.setSlot(INFO_SLOT, infoItem(id, current));

		List<Job> jobs = JobsConfig.jobs();
		int[] slots = jobs.size() <= JOB_SLOTS.length ? JOB_SLOTS : Gui.innerSlots(ROWS);
		for (int i = 0; i < jobs.size() && i < slots.length; i++) {
			Job job = jobs.get(i);
			gui.setSlot(slots[i], jobItem(gui, player, job, current != null && current.id.equals(job.id)));
		}

		boolean actionbar = Settings.get(id, Settings.JOB_ACTIONBAR);
		gui.setSlot(ACTIONBAR_SLOT, Gui.button(actionbar ? Items.LIME_DYE : Items.GRAY_DYE,
			"§eJob Action Bar: " + (actionbar ? "§aon" : "§coff"), () -> {
				boolean now = Settings.toggle(id, Settings.JOB_ACTIONBAR);
				Msg.info(player, "Job Action Bar: " + (now ? "§aon" : "§coff") + "§7.");
				render(gui, player);
			}, "§7Shows your earnings every few", "§7seconds above your hotbar.", "", "§eClick to toggle"));
		Gui.menuButton(gui, MENU_SLOT);
		gui.setSlot(CLOSE_SLOT, Gui.closeButton(gui));
		if (current != null) {
			gui.setSlot(LEAVE_SLOT, Gui.button(Items.RED_DYE, "§cLeave Job", () -> confirmLeave(player, current),
				"§7Leave " + current.display() + "§7.", "§7You keep your level.", "", "§eClick"));
		}
		Gui.frame(gui);
		Gui.fillEmpty(gui);
	}

	private static GuiElementBuilder infoItem(UUID id, Job current) {
		GuiElementBuilder b;
		if (current == null) {
			b = Gui.item(Items.BOOK, "§6Your Job: §7none", "§7Pick a job below.", "", JobsText.cooldownLine(id));
		} else {
			b = Gui.item(current.icon, "§6Your Job: " + current.display());
			Gui.lore(b, JobsText.status(id, current).toArray(String[]::new));
			Gui.lore(b, "", JobsText.cooldownLine(id));
		}
		long total = PlayerDataService.getCounter(id, JobsService.COUNTER_EARNED);
		if (total > 0) Gui.lore(b, "§7Earned from jobs: " + Msg.moneyStr(total));
		return b.glow(current != null);
	}

	private static GuiElementBuilder jobItem(SimpleGui gui, ServerPlayer player, Job job, boolean isCurrent) {
		UUID id = player.getUUID();
		GuiElementBuilder b = Gui.item(job.icon, job.display() + (isCurrent ? " §a(active)" : ""));
		for (String d : job.description) Gui.lore(b, "§7" + d);
		Gui.lore(b, "", "§7Example rewards:");
		double mult = JobsService.multiplier(id, job);
		for (Example e : job.examples(6)) b.addLoreLine(Msg.legacy(" ").append(JobsText.rewardLine(e, mult)));
		int level = JobsService.level(id, job.id);
		Gui.lore(b, "", "§7Your level: §e" + level + " §8(§a" + JobsText.percent(JobsService.levelMultiplier(level)) + " earnings§8)");
		for (Hooks.Part part : Hooks.jobParts(id, job.id)) Gui.lore(b, "§6⚡ " + part.label() + " §e×" + JobsText.trim(part.factor()));
		Gui.lore(b, "");
		long cooldown = JobsService.cooldownRemaining(id);
		if (isCurrent) Gui.lore(b, "§aThis is your current job.");
		else if (cooldown > 0) Gui.lore(b, "§cYou can switch in " + TimeUtil.formatDuration(cooldown) + ".");
		else Gui.lore(b, "§eClick to join");
		b.glow(isCurrent);
		b.setCallback((index, type, action, g) -> {
			if (!Gui.acceptClick(player, index, type)) return;
			Gui.click(player);
			onJobClick(gui, player, job);
		});
		return b;
	}

	private static void onJobClick(SimpleGui gui, ServerPlayer player, Job job) {
		UUID id = player.getUUID();
		Job current = JobsService.currentJob(id);
		if (current != null && current.id.equals(job.id)) {
			Msg.info(player, job.display() + " §7is already your job.");
			return;
		}
		long cooldown = JobsService.cooldownRemaining(id);
		if (cooldown > 0) {
			Msg.error(player, "You can switch jobs in §e" + TimeUtil.formatDuration(cooldown) + "§c.");
			Gui.failSound(player);
			return;
		}
		if (current == null) {
			JobsCommand.applyJoin(player, job);
			render(gui, player);
			return;
		}
		long cd = JobsConfig.raw().switchCooldownMinutes * 60_000L;
		GuiElementBuilder preview = Gui.item(job.icon, "§7Switch to " + job.display(), "§7Current: " + current.display(),
			"§7You keep your level in both jobs.", cd > 0 ? "§cNo switching for " + TimeUtil.formatDuration(cd) + " after this." : "");
		Gui.confirm(player, "§8Switch Job?", preview, () -> {
			JobsCommand.applyJoin(player, job); // rechecks cooldown and job
			if (Gui.canOpen(player)) open(player);
		}, () -> open(player));
	}

	private static void confirmLeave(ServerPlayer player, Job job) {
		long cd = JobsConfig.raw().switchCooldownMinutes * 60_000L;
		GuiElementBuilder preview = Gui.item(Items.RED_DYE, "§cLeave Job: " + job.display(), "§7You keep your level.",
			cd > 0 ? "§cNo new job for " + TimeUtil.formatDuration(cd) + " after this." : "");
		Gui.confirm(player, "§8Leave Job?", preview, () -> {
			Job now = JobsService.currentJob(player.getUUID());
			if (now != null && now.id.equals(job.id)) JobsCommand.applyLeave(player);
			if (Gui.canOpen(player)) open(player);
		}, () -> open(player));
	}
}
