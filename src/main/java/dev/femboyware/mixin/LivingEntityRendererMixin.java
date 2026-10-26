package dev.femboyware.mixin;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.render.TungSahurModule;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntityRenderer.class)
public class LivingEntityRendererMixin {
	/** Tung Sahur: hold off the death fall while the bonking happens. */
	@Inject(method = "updateRenderState(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;F)V", at = @At("TAIL"))
	private void femboyware$tungSahur(LivingEntity entity, LivingEntityRenderState state, float tickProgress, CallbackInfo ci) {
		if (FemboyWare.getModules() == null || entity.deathTime <= 0) return;
		state.deathTime = FemboyWare.getModules().get(TungSahurModule.class).deathTime(entity, state.deathTime);
	}
}
