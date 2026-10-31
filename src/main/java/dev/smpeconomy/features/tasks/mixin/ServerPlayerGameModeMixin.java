package dev.smpeconomy.features.tasks.mixin;

import dev.smpeconomy.core.module.Modules;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import dev.smpeconomy.features.tasks.BreakTracker;

/**
 * Wraps every player block break, aborted ones included, in a frame so {@link BreakTracker} can exclude "mined" stat
 * rises from self-placed blocks. Veinminer follow-up blocks up to 2.12 don't pass through here (removeBlock +
 * Block#destroy inside this frame); {@code BlockMixin} reports those, and the matching stat rise (count - 1 on the
 * origin) lands in this frame. From 2.13 Veinminer calls {@code destroyBlock} for every follow-up block, so each gets
 * its own frame.
 * <p>Every right-click on a block ({@code useItemOn}) is wrapped too: RightClickHarvest harvests in {@code
 * UseBlockCallback} (head of {@code useItemOn}) and raises the stat itself. {@link BreakTracker} opens that frame in
 * the mod's BEFORE event; the end of {@code useItemOn} closes it reliably (finally).
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {
	@Shadow
	protected ServerLevel level;

	@Shadow
	@Final
	protected ServerPlayer player;

	@WrapMethod(method = "destroyBlock(Lnet/minecraft/core/BlockPos;)Z")
	private boolean smpeco$tasksTrackMined(BlockPos pos, Operation<Boolean> original) {
		if (!Modules.isEnabled("tasks")) return original.call(pos);
		Object frame = BreakTracker.begin(this.player, this.level, pos);
		try {
			return original.call(pos);
		} finally {
			BreakTracker.end(frame);
		}
	}

	@WrapMethod(method = "useItemOn(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;")
	private InteractionResult smpeco$tasksTrackUse(ServerPlayer user, Level useLevel, ItemStack stack, InteractionHand hand, BlockHitResult hit, Operation<InteractionResult> original) {
		if (!Modules.isEnabled("tasks")) return original.call(user, useLevel, stack, hand, hit);
		boolean counted = BreakTracker.beginUse(this.level);
		try {
			return original.call(user, useLevel, stack, hand, hit);
		} finally {
			BreakTracker.endUse(counted);
		}
	}
}
