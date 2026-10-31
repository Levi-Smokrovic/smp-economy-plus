package dev.smpeconomy.features.jobs;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import dev.smpeconomy.core.Hooks;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.TimeUtil;
import dev.smpeconomy.features.jobs.JobsConfig.Example;
import dev.smpeconomy.features.jobs.JobsConfig.Job;

public final class JobsText {
	private JobsText() {}

	public static String percent(double factor) {
		double p = Math.round((factor - 1.0) * 1000.0) / 10.0;
		String s = p == Math.rint(p) ? Long.toString((long) p) : String.format(Locale.ROOT, "%.1f", p);
		return (p >= 0 ? "+" : "") + s + "%";
	}

	/**
	 * Amount in thousandths of a cent: whole cents like {@link Money#format}, fractions with 3 decimals ({@code
	 * "$0.005"}).
	 */
	public static String money(long milli) {
		if (milli % 1000 == 0) return Money.format(milli / 1000);
		if (milli >= 100_000_000L) return Money.format(milli / 1000);
		String s = String.format(Locale.ROOT, "%.3f", milli / 100_000.0);
		if (s.endsWith("0") && s.indexOf('.') > 0 && s.length() - s.indexOf('.') > 3) s = s.substring(0, s.length() - 1);
		return Money.withSymbol(false, s);
	}

	public static String bar(long value, long max, int width) {
		int filled = max <= 0 ? width : (int) Math.max(0, Math.min(width, value * width / max));
		return "§a" + "■".repeat(filled) + "§7" + "■".repeat(width - filled);
	}

	public static MutableComponent rewardLine(Example e, double mult) {
		MutableComponent line = Msg.legacy("§8• ").append(e.name().copy().withStyle(net.minecraft.ChatFormatting.WHITE));
		line.append(Msg.legacy(" §a" + money(JobsService.milliFor(e.reward(), mult))));
		if (e.reward().xp() > 0) line.append(Msg.legacy(" §8+" + e.reward().xp() + " XP"));
		if (e.reward().mature()) line.append(Msg.legacy(" §8(fully grown)"));
		return line;
	}

	public static List<String> status(UUID player, Job job) {
		List<String> lines = new ArrayList<>();
		int level = JobsService.level(player, job.id);
		int max = JobsService.maxLevel();
		lines.add("§7Level: §e" + level + (level >= max ? " §6(MAX)" : "§8/" + max) + " §8(§a" + percent(JobsService.levelMultiplier(level)) + " earnings§8)");
		if (level < max) {
			long xp = PlayerDataService.getJobXp(player, job.id);
			long need = JobsService.xpForNextLevel(level);
			lines.add("§7XP: §e" + Msg.number(xp) + "§7/§e" + Msg.number(need) + " " + bar(xp, need, 10));
		}
		for (Hooks.Part part : Hooks.jobParts(player, job.id)) {
			lines.add("§6⚡ " + part.label() + " §e×" + trim(part.factor()));
		}
		long earned = PlayerDataService.getCounter(player, JobsService.COUNTER_EARNED + "." + job.id);
		if (earned > 0) lines.add("§7Earned: " + Msg.moneyStr(earned));
		return lines;
	}

	public static String cooldownLine(UUID player) {
		long rem = JobsService.cooldownRemaining(player);
		return rem <= 0 ? "§7Job switch: §aready" : "§7Job switch: §cin " + TimeUtil.formatDuration(rem);
	}

	public static String trim(double v) {
		return v == Math.rint(v) ? Long.toString((long) v) : String.format(Locale.ROOT, "%.2f", v).replaceAll("0+$", "");
	}

	public static Component empty() {
		return Component.empty();
	}
}
