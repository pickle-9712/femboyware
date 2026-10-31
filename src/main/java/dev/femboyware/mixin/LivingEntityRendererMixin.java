package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.render.NametagsModule;
import dev.femboyware.module.render.TungSahurModule;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntityRenderer.class)
public class LivingEntityRendererMixin {
	/** Tung Sahur: freeze the body where it died and hold off the death fall while the bonking happens. */
	@Inject(method = "updateRenderState(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;F)V", at = @At("TAIL"))
	private void femboyware$tungSahur(LivingEntity entity, LivingEntityRenderState state, float tickProgress, CallbackInfo ci) {
		if (FemboyWare.getModules() == null || entity.deathTime <= 0) return;
		FemboyWare.getModules().get(TungSahurModule.class).adjustRenderState(entity, state);
	}

	/** Nametags draws its own tags, so other players' vanilla name labels are hidden while it's on. */
	@ModifyReturnValue(method = "hasLabel(Lnet/minecraft/entity/LivingEntity;D)Z", at = @At("RETURN"))
	private boolean femboyware$hideVanillaNametags(boolean hasLabel, LivingEntity entity, double distance) {
		return hasLabel && !(entity instanceof PlayerEntity && FemboyWare.isEnabled(NametagsModule.class));
	}
}
