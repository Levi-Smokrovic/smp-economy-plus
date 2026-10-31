package dev.smpeconomy.core.mixin;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import dev.smpeconomy.core.TraderGoods;

/**
 * {@code MerchantOffer#assemble} returns the copy of the trade result that {@code MerchantContainer#updateSellItem}
 * puts in the result slot. Marking that copy means the item carries the mark however it leaves the slot (click,
 * shift-click, number key, Q).
 */
@Mixin(MerchantOffer.class)
public abstract class MerchantOfferMixin {
	@Inject(method = "assemble", at = @At("RETURN"))
	private void smpeco$markTraderGoods(CallbackInfoReturnable<ItemStack> cir) {
		TraderGoods.markTradeResult(cir.getReturnValue());
	}
}
