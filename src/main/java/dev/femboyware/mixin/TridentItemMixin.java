package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.movement.TridentBoostModule;
import net.minecraft.world.item.TridentItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(TridentItem.class)
public class TridentItemMixin {
	@ModifyExpressionValue(method = {"use", "releaseUsing"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;isInWaterOrRain()Z"))
	private boolean femboyware$riptideAnywhere(boolean wet) {
		return wet || FemboyWare.getModules().get(TridentBoostModule.class).anywhere();
	}

	@ModifyExpressionValue(method = "releaseUsing", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/enchantment/EnchantmentHelper;getTridentSpinAttackStrength(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/LivingEntity;)F"))
	private float femboyware$riptideStrength(float strength) {
		return FemboyWare.getModules().get(TridentBoostModule.class).modifyStrength(strength);
	}
}
