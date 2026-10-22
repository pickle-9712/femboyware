package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.render.FullBrightModule;
import net.minecraft.client.render.LightmapTextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Full Bright: the lightmap behaves as if you had full strength night vision. */
@Mixin(LightmapTextureManager.class)
public class LightmapTextureManagerMixin {
	@ModifyExpressionValue(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/ClientPlayerEntity;hasStatusEffect(Lnet/minecraft/registry/entry/RegistryEntry;)Z", ordinal = 0), require = 0)
	private boolean femboyware$fullBrightEffect(boolean hasNightVision) {
		return hasNightVision || FemboyWare.isEnabled(FullBrightModule.class);
	}

	@ModifyExpressionValue(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/GameRenderer;getNightVisionStrength(Lnet/minecraft/entity/LivingEntity;F)F"), require = 0)
	private float femboyware$fullBrightStrength(float strength) {
		return FemboyWare.isEnabled(FullBrightModule.class) ? 1f : strength;
	}
}
