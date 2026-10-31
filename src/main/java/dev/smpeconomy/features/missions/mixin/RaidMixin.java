package dev.smpeconomy.features.missions.mixin;

import dev.smpeconomy.core.module.Modules;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.raid.Raid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import dev.smpeconomy.features.missions.MissionHooks;

/**
 * Raids for the Daily Elite:
 * <ul>
 *   <li>{@code Raid#spawnGroup} with {@code groupsSpawned == 0}: the first wave is about to spawn, remember the level.
 *   Vanilla lets Bad Omen feed into a running raid below level V ({@code Raids#createOrExtendRaid}), so the start level
 *   is what counts, not the level at the win.</li>
 *   <li>Win: {@code Raid#tick} gives every Hero of the Village (a player who killed any raider of this raid, in the
 *   same dimension, not a spectator) {@code awardStat(RAID_WIN)}. That call, the only {@code awardStat} in {@code
 *   tick}, reports the win to the Daily Elite, which checks contribution and position itself. WrapOperation instead of
 *   a redirect so other mods can use the same spot.</li>
 * </ul>
 */
@Mixin(Raid.class)
public abstract class RaidMixin {
	@Shadow
	public abstract int getGroupsSpawned();

	@Inject(method = "spawnGroup", at = @At("HEAD"))
	private void smpeco$missionsFirstWave(ServerLevel level, BlockPos pos, CallbackInfo ci) {
		if (!Modules.isEnabled("missions")) return;
		if (getGroupsSpawned() == 0) MissionHooks.onRaidFirstWave(level, (Raid) (Object) this);
	}

	@WrapOperation(method = "tick",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;awardStat(Lnet/minecraft/resources/Identifier;)V"))
	private void smpeco$missionsRaidWon(ServerPlayer player, Identifier stat, Operation<Void> original, @Local(argsOnly = true) ServerLevel level) {
		original.call(player, stat);
		if (!Modules.isEnabled("missions")) return;
		if (Stats.RAID_WIN.equals(stat)) MissionHooks.onRaidWon(player, level, (Raid) (Object) this);
	}
}
