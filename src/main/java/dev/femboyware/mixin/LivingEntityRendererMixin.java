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
public abstract class LivingEntityRendererMixin {
	@org.spongepowered.asm.mixin.Shadow
	public abstract net.minecraft.resources.Identifier getTextureLocation(LivingEntityRenderState state);

	/** Self Visuals: your tint and see-through. */
	@ModifyReturnValue(method = "getModelTint", at = @At("RETURN"))
	private int femboyware$selfTint(int original, LivingEntityRenderState state) {
		if (FemboyWare.getModules() == null) return original;
		original = dev.femboyware.module.effects.PlayerTrailModule.tint(state, original);
		return FemboyWare.getModules().get(dev.femboyware.module.render.SelfVisualsModule.class).tint(state, original);
	}

	/** Self Visuals: see-through needs a blended render type. */
	@ModifyReturnValue(method = "getRenderType", at = @At("RETURN"))
	private net.minecraft.client.renderer.rendertype.RenderType femboyware$selfGhost(net.minecraft.client.renderer.rendertype.RenderType original, LivingEntityRenderState state, boolean bodyVisible, boolean translucent, boolean glowing) {
		if (original == null || FemboyWare.getModules() == null) return original;
		if (!FemboyWare.getModules().get(dev.femboyware.module.render.SelfVisualsModule.class).ghost(state)
			&& !dev.femboyware.module.effects.PlayerTrailModule.ghost(state)) return original;
		return net.minecraft.client.renderer.rendertype.RenderTypes.entityTranslucent(getTextureLocation(state));
	}

	/** Tung Sahur: freeze the body where it died and hold off the death fall while the bonking happens. */
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V", at = @At("TAIL"))
	private void femboyware$tungSahur(LivingEntity entity, LivingEntityRenderState state, float tickProgress, CallbackInfo ci) {
		if (FemboyWare.getModules() != null) FemboyWare.getModules().get(dev.femboyware.module.render.SelfVisualsModule.class).outline(entity, state);
		// Player Trail's copies are afterimages: no shadow on the ground
		if (dev.femboyware.module.effects.PlayerTrailModule.isEcho(entity)) {
			state.shadowRadius = 0;
			state.shadowPieces.clear();
		}
		if (FemboyWare.getModules() == null || entity.deathTime <= 0) return;
		FemboyWare.getModules().get(TungSahurModule.class).adjustRenderState(entity, state);
	}

	/** Nametags draws its own tags, so other players' vanilla name labels are hidden while it's on. */
	@ModifyReturnValue(method = "shouldShowName(Lnet/minecraft/world/entity/LivingEntity;D)Z", at = @At("RETURN"))
	private boolean femboyware$hideVanillaNametags(boolean hasLabel, LivingEntity entity, double distance) {
		if (FemboyWare.getModules() != null && FemboyWare.getModules().get(dev.femboyware.module.larp.FbiRaidModule.class).isActor(entity)) return false;
		if (dev.femboyware.module.effects.PlayerTrailModule.isEcho(entity)) return false;
		return hasLabel && !(entity instanceof Player && FemboyWare.isEnabled(NametagsModule.class));
	}
}
