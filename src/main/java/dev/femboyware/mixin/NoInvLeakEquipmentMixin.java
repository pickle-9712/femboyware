package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.femboyware.module.client.NoInvLeakModule;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** No Inv Leak: while your inventory model is drawn, it holds and wears nothing. Ported from Anubis (GPL-3.0, 4ldenz). */
@Mixin(LivingEntity.class)
public abstract class NoInvLeakEquipmentMixin {
	@ModifyReturnValue(method = "getItemBySlot", at = @At("RETURN"), require = 0)
	private ItemStack femboyware$bareItem(ItemStack stack) {
		return NoInvLeakModule.modelItem((LivingEntity) (Object) this, stack);
	}
}
