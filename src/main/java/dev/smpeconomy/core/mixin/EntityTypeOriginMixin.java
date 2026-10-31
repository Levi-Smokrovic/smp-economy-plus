package dev.smpeconomy.core.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import dev.smpeconomy.core.MobOrigin;
//? if >=26.2 {
/*import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.PostSpawnProcessor;
*///?} else {
import java.util.function.Consumer;
//?}

/**
 * Tags mobs from spawners ({@code BaseSpawner} loads them with {@code loadEntityRecursive(..., SPAWNER, ...)},
 * including passengers and custom spawn data) and from spawn eggs and dispensers ({@code create(...,
 * SPAWN_ITEM_USE|DISPENSER, ...)}). Both inject at RETURN, after {@code Entity#load}, which would otherwise overwrite
 * the tags.
 *
 * <p>Also covers the base {@code create(Level, EntitySpawnReason)}, which vanilla uses for bred mobs ({@code BREEDING}:
 * shulker splitting, hoglins, all animal offspring) and summoned ones ({@code MOB_SUMMONED}: vexes). Without a tag,
 * shulker, hoglin and vex farms would pay in full forever. Spawn egg offspring of non-aging mobs (
 * {@code SpawnEggItem#spawnOffspringFromSpawnEgg}) only passes through here. Loading from NBT restores stored tags anyway.
 *
 * <p>From MC 26.2, load and create carry the reason in an {@code EntitySpawnRequest}. The old {@code EntitySpawnReason}
 * overloads still exist but forward to the request ones. Spawned mobs ({@code BaseSpawner#serverTick},
 * {@code TrialSpawner#spawnMob}) use the old overload, while passengers ({@code loadPassengersRecursive}), spawner display
 * entities and /summon call the request overload directly, and structures use {@code create(ValueInput, Level,
 * EntitySpawnRequest)}. So the mixin tags at the request ends, where every path passes exactly once; at the old end,
 * spawner mobs' passengers would stay untagged and pay in full. The spawn egg path takes a {@code PostSpawnProcessor}
 * there instead of a {@code Consumer}.
 */
@Mixin(EntityType.class)
public abstract class EntityTypeOriginMixin {
	//? if >=26.2 {
	/*@Inject(
		method = "loadEntityRecursive(Lnet/minecraft/world/level/storage/ValueInput;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/EntitySpawnRequest;Lnet/minecraft/world/entity/EntityProcessor;)Lnet/minecraft/world/entity/Entity;",
		at = @At("RETURN"))
	private static void smpeco$tagSpawnerMob(ValueInput input, Level level, EntitySpawnRequest request, EntityProcessor processor, CallbackInfoReturnable<Entity> cir) {
		if (EntitySpawnReason.isSpawner(request.reason())) MobOrigin.tag(cir.getReturnValue(), request.reason()); // also trial spawners
	}

	@Inject(
		method = "create(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/EntitySpawnRequest;)Lnet/minecraft/world/entity/Entity;",
		at = @At("RETURN"))
	private void smpeco$tagBredOrSummonedMob(Level level, EntitySpawnRequest request, CallbackInfoReturnable<Entity> cir) {
		smpeco$tagCreated(cir.getReturnValue(), request.reason());
	}

	@Inject(
		method = "create(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/PostSpawnProcessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/EntitySpawnReason;ZZ)Lnet/minecraft/world/entity/Entity;",
		at = @At("RETURN"))
	private void smpeco$tagSpawnEggMob(ServerLevel level, PostSpawnProcessor<?> processor, BlockPos pos, EntitySpawnReason reason, boolean alignPosition, boolean invertY, CallbackInfoReturnable<Entity> cir) {
		if (reason == EntitySpawnReason.SPAWN_ITEM_USE || reason == EntitySpawnReason.DISPENSER) MobOrigin.tag(cir.getReturnValue(), reason);
	}
	*///?} else {
	@Inject(
		method = "loadEntityRecursive(Lnet/minecraft/world/level/storage/ValueInput;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/EntitySpawnReason;Lnet/minecraft/world/entity/EntityProcessor;)Lnet/minecraft/world/entity/Entity;",
		at = @At("RETURN"))
	private static void smpeco$tagSpawnerMob(ValueInput input, Level level, EntitySpawnReason reason, EntityProcessor processor, CallbackInfoReturnable<Entity> cir) {
		if (EntitySpawnReason.isSpawner(reason)) MobOrigin.tag(cir.getReturnValue(), reason); // also trial spawners
	}

	@Inject(
		method = "create(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/EntitySpawnReason;)Lnet/minecraft/world/entity/Entity;",
		at = @At("RETURN"))
	private void smpeco$tagBredOrSummonedMob(Level level, EntitySpawnReason reason, CallbackInfoReturnable<Entity> cir) {
		smpeco$tagCreated(cir.getReturnValue(), reason);
	}

	@Inject(
		method = "create(Lnet/minecraft/server/level/ServerLevel;Ljava/util/function/Consumer;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/EntitySpawnReason;ZZ)Lnet/minecraft/world/entity/Entity;",
		at = @At("RETURN"))
	private void smpeco$tagSpawnEggMob(ServerLevel level, Consumer<?> consumer, BlockPos pos, EntitySpawnReason reason, boolean alignPosition, boolean invertY, CallbackInfoReturnable<Entity> cir) {
		if (reason == EntitySpawnReason.SPAWN_ITEM_USE || reason == EntitySpawnReason.DISPENSER) MobOrigin.tag(cir.getReturnValue(), reason);
	}
	//?}

	@Unique
	private static void smpeco$tagCreated(Entity entity, EntitySpawnReason reason) {
		if (reason == EntitySpawnReason.BREEDING || reason == EntitySpawnReason.MOB_SUMMONED
			|| reason == EntitySpawnReason.SPAWN_ITEM_USE || reason == EntitySpawnReason.DISPENSER) {
			MobOrigin.tag(entity, reason);
		}
	}
}
