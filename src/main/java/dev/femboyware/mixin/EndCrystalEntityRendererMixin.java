package dev.femboyware.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.render.CrystalChamsModule;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EndCrystalRenderer;
import net.minecraft.client.renderer.entity.state.EndCrystalRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EndCrystalRenderer.class)
public class EndCrystalEntityRendererMixin {
	@Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/EndCrystalRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;scale(FFF)V", shift = At.Shift.AFTER, ordinal = 0), require = 0)
	private void femboyware$scale(EndCrystalRenderState state, PoseStack matrices, SubmitNodeCollector queue, CameraRenderState camera, CallbackInfo ci) {
		float scale = FemboyWare.getModules().get(CrystalChamsModule.class).getScale();
		if (scale != 1f) matrices.scale(scale, scale, scale);
	}

	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/boss/enderdragon/EndCrystal;Lnet/minecraft/client/renderer/entity/state/EndCrystalRenderState;F)V", at = @At("TAIL"))
	private void femboyware$style(EndCrystal crystal, EndCrystalRenderState state, float tickProgress, CallbackInfo ci) {
		CrystalChamsModule chams = FemboyWare.getModules().get(CrystalChamsModule.class);
		state.ageInTicks = chams.modifyAge(state.ageInTicks);
		state.outlineColor = chams.modifyOutline(state.outlineColor);
	}
}
