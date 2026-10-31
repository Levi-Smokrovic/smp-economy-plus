package dev.smpeconomy.features.jobs;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import dev.smpeconomy.SmpEconomy;
import dev.smpeconomy.core.EconomyService;
import dev.smpeconomy.core.Gui;
import dev.smpeconomy.core.Hooks;
import dev.smpeconomy.core.Money;
import dev.smpeconomy.core.Msg;
import dev.smpeconomy.core.PlacedBlockTracker;
import dev.smpeconomy.core.PlayerDataService;
import dev.smpeconomy.core.Settings;
import dev.smpeconomy.features.jobs.JobsConfig.Job;
import dev.smpeconomy.features.jobs.JobsConfig.Reward;

/**
 * Job logic on the server thread: joining and leaving with cooldown, per-action rewards (level and event multipliers
 * via {@link Hooks#jobMultiplier}), XP and level-ups, and collecting small amounts that get paid as one action bar
 * message every {@code payoutIntervalSeconds}.
 * <p>Job pay is a deliberate money source, like mission rewards. It doesn't come from the treasury, whose hourly cap
 * only covers NPC trading. Internally amounts are in thousandths of a cent so fractions (0.5 cents for netherrack, +2%
 * per level) aren't lost; only whole cents get paid.
 */
public final class JobsService {
	public static final String COUNTER_EARNED = "jobs.earned";
	/** Cap on a single credit in thousandths of a cent ($10M), guards against overflow. */
	static final long MAX_MILLI_PER_ACTION = 1_000_000_000_000L;

	static final class Pending {
		long milli;
		long xp;
		String job;
	}

	public enum JoinStatus { OK, UNKNOWN, ALREADY, COOLDOWN }

	public record JoinResult(JoinStatus status, long remainingMs, Job job) {
		public boolean ok() {
			return status == JoinStatus.OK;
		}
	}

	private static final Map<UUID, Pending> PENDING = new LinkedHashMap<>();

	private JobsService() {}

	/** Null if none, or if the job no longer exists in the config. */
	public static Job currentJob(UUID player) {
		return JobsConfig.job(PlayerDataService.getJob(player));
	}

	public static long cooldownRemaining(UUID player) {
		long changed = PlayerDataService.getJobChangedAt(player);
		if (changed <= 0) return 0;
		long cooldown = JobsConfig.raw().switchCooldownMinutes * 60_000L;
		long remaining = changed + cooldown - System.currentTimeMillis();
		return Math.max(0, Math.min(remaining, cooldown));
	}

	public static JoinResult join(UUID player, String jobId, boolean bypassCooldown) {
		Job job = JobsConfig.job(jobId);
		if (job == null) return new JoinResult(JoinStatus.UNKNOWN, 0, null);
		if (job.id.equals(PlayerDataService.getJob(player))) return new JoinResult(JoinStatus.ALREADY, 0, job);
		long remaining = cooldownRemaining(player);
		if (!bypassCooldown && remaining > 0) return new JoinResult(JoinStatus.COOLDOWN, remaining, job);
		flush(player); // collected pay still belongs to the old job
		PlayerDataService.setJob(player, job.id);
		return new JoinResult(JoinStatus.OK, 0, job);
	}

	public static boolean leave(UUID player) {
		if (PlayerDataService.getJob(player) == null) return false;
		flush(player);
		PlayerDataService.setJob(player, null);
		return true;
	}

	public static void adminSet(UUID player, String jobId) {
		flush(player);
		PlayerDataService.setJob(player, jobId);
		resetCooldown(player);
	}

	public static void resetCooldown(UUID player) {
		PlayerDataService.peek(player).ifPresent(d -> {
			d.jobChangedAt = 0;
			PlayerDataService.markDirty();
		});
	}

	public static int maxLevel() {
		return JobsConfig.raw().maxLevel;
	}

	public static int level(UUID player, String job) {
		return Math.max(1, Math.min(maxLevel(), PlayerDataService.getJobLevel(player, job)));
	}

	public static double levelMultiplier(int level) {
		int l = Math.max(1, Math.min(maxLevel(), level));
		return 1.0 + (l - 1) * JobsConfig.raw().levelBonusPercent / 100.0;
	}

	public static long xpForNextLevel(int level) {
		JobsConfig cfg = JobsConfig.raw();
		double v = cfg.xpBase * Math.pow(Math.max(1, level), cfg.xpExponent);
		return Math.max(1, Math.round(Math.min(v, 1e15)));
	}

	public record XpResult(int level, long xp, int levelsGained) {}

	/** Pure calculation, no storage: applies {@code gain} XP to (level, xp), with level-ups up to maxLevel. */
	public static XpResult applyXp(int level, long xp, long gain) {
		int max = maxLevel();
		int l = Math.max(1, Math.min(max, level));
		if (l >= max) return new XpResult(max, 0, 0);
		long x = Math.max(0, xp) + Math.max(0, gain);
		int gained = 0;
		while (l < max) {
			long need = xpForNextLevel(l);
			if (x < need) break;
			x -= need;
			l++;
			gained++;
		}
		if (l >= max) x = 0;
		return new XpResult(l, x, gained);
	}

	public static int addXp(UUID player, Job job, long gain) {
		if (gain <= 0) return 0;
		XpResult r = applyXp(level(player, job.id), PlayerDataService.getJobXp(player, job.id), gain);
		PlayerDataService.setJobXp(player, job.id, r.xp());
		if (r.levelsGained() > 0) {
			PlayerDataService.setJobLevel(player, job.id, r.level());
			announceLevelUp(player, job, r.level());
		}
		return r.levelsGained();
	}

	private static void announceLevelUp(UUID id, Job job, int level) {
		ServerPlayer p = onlinePlayer(id);
		if (p == null) return;
		String bonus = JobsText.percent(levelMultiplier(level));
		if (level >= maxLevel()) {
			Msg.success(p, "§6§lMax level! §7" + job.display() + " §7is now level §e" + level + " §8(§a" + bonus + " earnings§8)");
		} else {
			Msg.success(p, "Level up! §7" + job.display() + " §7is now level §e" + level + " §8(§a" + bonus + " earnings§8)");
		}
		Gui.sound(p, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.PLAYER_LEVELUP), 1f);
	}

	/** Level × events/hooks. Display and payout use the same value. */
	public static double multiplier(UUID player, Job job) {
		return levelMultiplier(level(player, job.id)) * Hooks.jobMultiplier(player, job.id);
	}

	/** In thousandths of a cent, rounded down, never negative. */
	public static long milliFor(Reward r, double mult) {
		if (r == null || !(mult > 0) || Double.isInfinite(mult)) return 0;
		double v = r.money() * 1000.0 * mult + 1e-6;
		if (!(v > 0)) return 0;
		return v >= MAX_MILLI_PER_ACTION ? MAX_MILLI_PER_ACTION : (long) Math.floor(v);
	}

	/**
	 * Credits money to the pending pot and XP. {@code factor} (0..1) reduces e.g. spawner mobs. Returns the credited
	 * amount in thousandths of a cent.
	 */
	public static long reward(UUID player, Job job, Reward r, double factor) {
		return reward(player, job, r, factor, Long.MAX_VALUE);
	}

	/**
	 * Like {@link #reward(UUID, Job, Reward, double)}, with money (including level and event hooks) capped at {@code
	 * maxMilli}.
	 */
	public static long reward(UUID player, Job job, Reward r, double factor, long maxMilli) {
		if (r == null || job == null || !(factor > 0)) return 0;
		double f = Math.min(1.0, factor);
		long milli = Math.min(milliFor(r, multiplier(player, job) * f), Math.max(0, maxMilli));
		long xp = (long) Math.floor(r.xp() * f + 1e-9);
		if (milli <= 0 && xp <= 0) return 0;
		Pending p = PENDING.computeIfAbsent(player, k -> new Pending());
		p.milli = Money.saturatedAdd(p.milli, milli);
		p.xp = Money.saturatedAdd(p.xp, xp);
		p.job = job.id;
		if (xp > 0) addXp(player, job, xp);
		return milli;
	}

	public static long onBlock(UUID player, Level level, BlockPos pos, BlockState state) {
		Job job = currentJob(player);
		if (job == null) return 0;
		Reward r = job.block(state);
		if (r == null) return 0;
		if (Job.needsMaturity(r, state)) {
			// crops always count as placed (seeds), so instead of the tracker: ripe and not boosted with bone meal
			if (!isMature(state) || BoostedCrops.contains(level, pos)) return 0;
		} else if (PlacedBlockTracker.isPlayerPlaced(level, pos)) {
			return 0;
		}
		return reward(player, job, r, 1.0);
	}

	public static long onMobKilled(UUID player, EntityType<?> type, boolean enemy, Set<String> tags) {
		Job job = currentJob(player);
		if (job == null) return 0;
		Reward r = job.mob(type, enemy);
		if (r == null) return 0;
		return reward(player, job, r, mobFactor(tags), maxMilliFor(tags));
	}

	/** Lowest factor wins when there are several origin tags; 1.0 is normal. */
	public static double mobFactor(Set<String> tags) {
		JobsConfig cfg = JobsConfig.raw();
		double f = 1.0;
		if (tags != null) {
			if (tags.contains(JobsEvents.TAG_SPAWNER)) f = Math.min(f, cfg.spawnerMobFactor);
			if (tags.contains(JobsEvents.TAG_SPAWN_EGG)) f = Math.min(f, cfg.spawnEggMobFactor);
			if (tags.contains(JobsEvents.TAG_BRED)) f = Math.min(f, cfg.bredMobFactor);
			if (tags.contains(JobsEvents.TAG_SUMMONED)) f = Math.min(f, cfg.summonedMobFactor);
			if (tags.contains(JobsEvents.TAG_RESPAWNED)) f = Math.min(f, cfg.respawnedDragonFactor);
		}
		return f;
	}

	/**
	 * Pay cap in thousandths of a cent (after level and event hooks) for a mob with these origin tags: a respawned
	 * dragon never pays more than {@link SummonCosts#respawnedDragonCapMilli()}, recomputed through PriceGuard on every
	 * kill, whatever {@code respawnedDragonFactor} says.
	 */
	public static long maxMilliFor(Set<String> tags) {
		return tags != null && tags.contains(JobsEvents.TAG_RESPAWNED) ? SummonCosts.respawnedDragonCapMilli() : Long.MAX_VALUE;
	}

	public static long onFish(UUID player, List<ItemStack> loot) {
		Job job = currentJob(player);
		if (job == null || loot == null) return 0;
		long total = 0;
		for (ItemStack stack : loot) {
			Reward r = job.fish(stack);
			if (r != null) total = Money.saturatedAdd(total, reward(player, job, r, 1.0));
		}
		return total;
	}

	/** CropBlock via {@code isMaxAge}, otherwise an integer "age" property at its maximum. */
	public static boolean isMature(BlockState state) {
		Block block = state.getBlock();
		if (block instanceof CropBlock crop) return crop.isMaxAge(state);
		for (Property<?> property : state.getProperties()) {
			if (property instanceof IntegerProperty age && "age".equals(property.getName())) {
				int max = Integer.MIN_VALUE;
				for (int v : age.getPossibleValues()) max = Math.max(max, v);
				return state.getValue(age) >= max;
			}
		}
		return true;
	}

	public static long pendingMilli(UUID player) {
		Pending p = PENDING.get(player);
		return p == null ? 0 : p.milli;
	}

	public static void flushAll() {
		for (UUID id : List.copyOf(PENDING.keySet())) flush(id);
	}

	/**
	 * Pays a player's whole cents, also offline since the account exists by UUID, and shows {@code "+$12.40 (Miner)"}
	 * in the action bar when online ({@link Settings#JOB_ACTIONBAR}). Fractions stay. Returns the cents paid.
	 */
	public static long flush(UUID id) {
		Pending p = PENDING.get(id);
		if (p == null) return 0;
		long cents = p.milli / 1000;
		long xp = p.xp;
		p.xp = 0;
		long paid = 0;
		if (cents > 0) {
			p.milli -= cents * 1000;
			EconomyService.Result r = EconomyService.deposit(id, cents);
			if (r.ok()) {
				paid = cents;
				PlayerDataService.addCounter(id, COUNTER_EARNED, cents);
				if (p.job != null) PlayerDataService.addCounter(id, COUNTER_EARNED + "." + p.job, cents);
			} else {
				// e.g. balance at the maximum: discard instead of retrying forever
				SmpEconomy.LOG.warn("[SMPEco] job pay {} for {} not credited: {}", Money.format(cents), id, r);
			}
		}
		ServerPlayer online = onlinePlayer(id);
		if (online != null && (paid > 0 || xp > 0) && Settings.get(id, Settings.JOB_ACTIONBAR)) {
			Job job = JobsConfig.job(p.job);
			String label = job == null ? "Job" : job.name;
			StringBuilder text = new StringBuilder();
			if (paid > 0) text.append("§a+").append(Money.format(paid)).append(" §7(").append(label).append(")");
			else text.append("§7").append(label);
			if (xp > 0) text.append(" §8| §b+").append(Msg.number(xp)).append(" XP");
			Msg.actionBar(online, text.toString());
		}
		// keep fractions for online players only, or the map grows with everyone who ever worked
		if (p.milli <= 0 || online == null) PENDING.remove(id);
		return paid;
	}

	/** For selftest cleanup: otherwise test money would land on a newly created account later. */
	public static void discard(UUID id) {
		PENDING.remove(id);
	}

	static void clearAll() {
		PENDING.clear();
	}

	static ServerPlayer onlinePlayer(UUID id) {
		MinecraftServer server = SmpEconomy.server();
		return server == null ? null : server.getPlayerList().getPlayer(id);
	}
}
