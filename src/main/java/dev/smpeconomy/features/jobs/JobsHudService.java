package dev.smpeconomy.features.jobs;

import java.util.UUID;
import net.fabricmc.fabric.api.entity.FakePlayer;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.Services;
import dev.smpeconomy.core.test.Assert;
import dev.smpeconomy.core.test.SelfTests;
import dev.smpeconomy.features.jobs.JobsConfig.Job;

/**
 * {@link Services.JobLevels} for the sidebar. Same values and formulas as {@link JobsService#addXp}; creates nothing.
 */
final class JobsHudService implements Services.JobLevels {
	private JobsHudService() {}

	static void register() {
		Services.register(Services.JobLevels.class, new JobsHudService());
		SelfTests.register("jobs", "hud-service", ctx -> {
			FakePlayer p = ctx.player("JobsHud");
			UUID id = p.getUUID();
			Services.JobLevels svc = Services.find(Services.JobLevels.class).orElse(null);
			Assert.isTrue(svc instanceof JobsHudService, "service registered");
			Assert.eq(null, svc.progress(id, "does_not_exist"), "unknown job gives null");
			Assert.eq(null, svc.progress(id, null), "no job gives null");
			Job job = JobsConfig.jobs().isEmpty() ? null : JobsConfig.jobs().get(0);
			Assert.notNull(job, "at least one job in jobs.json");
			int max = JobsService.maxLevel();
			Assert.eq(new JobProgress(1, max, 0, JobsService.xpForNextLevel(1)), svc.progress(id, job.id), "no data: level 1, 0 XP");
			Assert.isTrue(PlayerDataService.peek(id).isEmpty(), "reading creates no player data");
			if (max > 3) {
				PlayerDataService.setJobLevel(id, job.id, 3);
				PlayerDataService.setJobXp(id, job.id, 17);
				Assert.eq(new JobProgress(3, max, 17, JobsService.xpForNextLevel(3)), svc.progress(id, job.id), "level 3, 17 XP");
			}
			PlayerDataService.setJobLevel(id, job.id, max + 5);
			PlayerDataService.setJobXp(id, job.id, 999);
			Assert.eq(new JobProgress(max, max, 0, 0), svc.progress(id, job.id), "max level: clamped, no more XP");
		});
	}

	@Override
	public JobProgress progress(UUID player, String jobId) {
		if (jobId == null) return null;
		Job job = JobsConfig.job(jobId);
		if (job == null) return null;
		int max = JobsService.maxLevel();
		int level = JobsService.level(player, job.id);
		if (level >= max) return new JobProgress(max, max, 0, 0);
		return new JobProgress(level, max, Math.max(0, PlayerDataService.getJobXp(player, job.id)), JobsService.xpForNextLevel(level));
	}
}
