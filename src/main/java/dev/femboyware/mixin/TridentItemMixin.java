package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.movement.TridentBoostModule;
import net.minecraft.item.TridentItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(TridentItem.class)
public class TridentItemMixin {
	@ModifyExpressionValue(method = {"use", "onStoppedUsing"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/PlayerEntity;isTouchingWaterOrRain()Z"))
	private boolean femboyware$riptideAnywhere(boolean wet) {
		return wet || FemboyWare.getModules().get(TridentBoostModule.class).anywhere();
	}

	@ModifyExpressionValue(method = "onStoppedUsing", at = @At(value = "INVOKE", target = "Lnet/minecraft/enchantment/EnchantmentHelper;getTridentSpinAttackStrength(Lnet/minecraft/item/ItemStack;Lnet/minecraft/entity/LivingEntity;)F"))
	private float femboyware$riptideStrength(float strength) {
		return FemboyWare.getModules().get(TridentBoostModule.class).modifyStrength(strength);
	}
}
