package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.render.FullBrightModule;
//? if >=26.1 {
/*import net.minecraft.client.renderer.LightmapRenderStateExtractor;
*///?} else {
import net.minecraft.client.renderer.LightTexture;
//?}
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Full Bright: the lightmap behaves as if you had full strength night vision. */
//? if >=26.1 {
/*@Mixin(LightmapRenderStateExtractor.class)
*///?} else {
@Mixin(LightTexture.class)
//?}
public class LightmapTextureManagerMixin {
	//? if >=26.1 {
	/*private static final String UPDATE = "extract";
	private static final String NIGHT_VISION_SCALE = "Lnet/minecraft/client/renderer/GameRenderer;nightVisionScale(Lnet/minecraft/world/entity/LivingEntity;F)F";
	*///?} else {
	private static final String UPDATE = "updateLightTexture";
	private static final String NIGHT_VISION_SCALE = "Lnet/minecraft/client/renderer/GameRenderer;getNightVisionScale(Lnet/minecraft/world/entity/LivingEntity;F)F";
	//?}

	@ModifyExpressionValue(method = UPDATE, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;hasEffect(Lnet/minecraft/core/Holder;)Z", ordinal = 0), require = 0)
	private boolean femboyware$fullBrightEffect(boolean hasNightVision) {
		return hasNightVision || FemboyWare.isEnabled(FullBrightModule.class);
	}

	/**
	 * Skips vanilla's strength lookup entirely: it reads the player's night vision effect, which
	 * isn't there when only Full Bright is on, and would crash on it.
	 */
	@WrapOperation(method = UPDATE, at = @At(value = "INVOKE", target = NIGHT_VISION_SCALE), require = 0)
	private float femboyware$fullBrightStrength(LivingEntity entity, float tickProgress, Operation<Float> original) {
		return FemboyWare.isEnabled(FullBrightModule.class) ? 1f : original.call(entity, tickProgress);
	}
}
