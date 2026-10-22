package dev.femboyware.mixin;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.render.CrystalChamsModule;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.EndCrystalEntityRenderer;
import net.minecraft.client.render.entity.state.EndCrystalEntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.decoration.EndCrystalEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EndCrystalEntityRenderer.class)
public class EndCrystalEntityRendererMixin {
	@Inject(method = "render(Lnet/minecraft/client/render/entity/state/EndCrystalEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/util/math/MatrixStack;scale(FFF)V", shift = At.Shift.AFTER, ordinal = 0), require = 0)
	private void femboyware$scale(EndCrystalEntityRenderState state, MatrixStack matrices, OrderedRenderCommandQueue queue, CameraRenderState camera, CallbackInfo ci) {
		float scale = FemboyWare.getModules().get(CrystalChamsModule.class).getScale();
		if (scale != 1f) matrices.scale(scale, scale, scale);
	}

	@Inject(method = "updateRenderState(Lnet/minecraft/entity/decoration/EndCrystalEntity;Lnet/minecraft/client/render/entity/state/EndCrystalEntityRenderState;F)V", at = @At("TAIL"))
	private void femboyware$style(EndCrystalEntity crystal, EndCrystalEntityRenderState state, float tickProgress, CallbackInfo ci) {
		CrystalChamsModule chams = FemboyWare.getModules().get(CrystalChamsModule.class);
		state.age = chams.modifyAge(state.age);
		state.outlineColor = chams.modifyOutline(state.outlineColor);
	}
}
