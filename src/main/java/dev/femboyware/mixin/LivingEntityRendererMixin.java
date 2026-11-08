package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.render.NametagsModule;
import dev.femboyware.module.render.TungSahurModule;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntityRenderer.class)
public class LivingEntityRendererMixin {
	/** Tung Sahur: freeze the body where it died and hold off the death fall while the bonking happens. */
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V", at = @At("TAIL"))
	private void femboyware$tungSahur(LivingEntity entity, LivingEntityRenderState state, float tickProgress, CallbackInfo ci) {
		if (FemboyWare.getModules() == null || entity.deathTime <= 0) return;
		FemboyWare.getModules().get(TungSahurModule.class).adjustRenderState(entity, state);
	}

	/** Nametags draws its own tags, so other players' vanilla name labels are hidden while it's on. */
	@ModifyReturnValue(method = "shouldShowName(Lnet/minecraft/world/entity/LivingEntity;D)Z", at = @At("RETURN"))
	private boolean femboyware$hideVanillaNametags(boolean hasLabel, LivingEntity entity, double distance) {
		return hasLabel && !(entity instanceof Player && FemboyWare.isEnabled(NametagsModule.class));
	}
}
