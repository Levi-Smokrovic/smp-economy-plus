package dev.smpeconomy.core;

import java.util.List;
import java.util.Set;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;

/**
 * Origin tags on mobs: entity tags stored with the mob and carried over on conversions. Set by the mixins in
 * {@code core/mixin}, which run regardless of enabled modules, so mobs stay tagged even if bred while jobs was off. Read by
 * jobs (pay factors) and missions.
 *
 * <p>The tag names are stored in world data and must never change.
 */
public final class MobOrigin {
	public static final String SPAWNER = "smpeco_jobs_spawner";
	public static final String SPAWN_EGG = "smpeco_jobs_egg";
	/** {@code BREEDING}: shulker splitting, animal and villager offspring. */
	public static final String BRED = "smpeco_jobs_bred";
	/** {@code MOB_SUMMONED}: an evoker's vexes. */
	public static final String SUMMONED = "smpeco_jobs_summoned";
	/** Every dragon after the first one defeated in the world. */
	public static final String RESPAWNED = "smpeco_jobs_respawned";
	/** Zombie reinforcements inherit these from the caller. */
	public static final List<String> ALL = List.of(SPAWNER, SPAWN_EGG, BRED, SUMMONED, RESPAWNED);

	private MobOrigin() {}

	public static boolean isArtificial(Set<String> tags) {
		if (tags == null || tags.isEmpty()) return false;
		for (String tag : ALL) {
			if (!SPAWNER.equals(tag) && tags.contains(tag)) return true;
		}
		return false;
	}

	/** Other reasons (natural, structure, raid, /summon) stay untagged. */
	public static void tag(Entity entity, EntitySpawnReason reason) {
		if (entity == null || reason == null) return;
		switch (reason) {
			case SPAWNER, TRIAL_SPAWNER -> entity.addTag(SPAWNER);
			case SPAWN_ITEM_USE, DISPENSER -> entity.addTag(SPAWN_EGG);
			case BREEDING -> entity.addTag(BRED);
			case MOB_SUMMONED -> entity.addTag(SUMMONED);
			default -> {
			}
		}
	}

	/**
	 * If this world already had a defeated dragon, the new one is a respawn with four crystals, or a replacement for
	 * one. The world's first dragon stays untagged.
	 */
	public static void onDragonCreated(Entity dragon, boolean previouslyKilled) {
		if (dragon != null && previouslyKilled) dragon.addTag(RESPAWNED);
	}

	/** Natural zombies' reinforcements stay untagged. Foreign tags are not copied. */
	public static void inherit(Entity caller, Entity reinforcement) {
		if (caller == null || reinforcement == null) return;
		Set<String> tags = caller.getTags();
		for (String tag : ALL) {
			if (tags.contains(tag)) reinforcement.addTag(tag);
		}
	}

	public static boolean fromSpawner(Set<String> tags) {
		return tags != null && tags.contains(SPAWNER);
	}
}
