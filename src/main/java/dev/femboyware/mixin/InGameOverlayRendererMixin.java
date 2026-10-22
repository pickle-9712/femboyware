package dev.femboyware.mixin;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.render.TotemAnimationModule;
import dev.femboyware.module.utility.NoRenderModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.InGameOverlayRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameOverlayRenderer.class)
public class InGameOverlayRendererMixin {
	@Inject(method = "renderFloatingItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/util/math/MatrixStack;scale(FFF)V", shift = At.Shift.AFTER), require = 0)
	private void femboyware$totemScale(MatrixStack matrices, float tickProgress, OrderedRenderCommandQueue queue, CallbackInfo ci) {
		float scale = FemboyWare.getModules().get(TotemAnimationModule.class).getScale();
		if (scale != 1f) matrices.scale(scale, scale, scale);
	}

	@Inject(method = "renderFireOverlay", at = @At("HEAD"), cancellable = true)
	private static void femboyware$noFire(MatrixStack matrices, VertexConsumerProvider vertexConsumers, Sprite sprite, CallbackInfo ci) {
		if (FemboyWare.getModules().get(NoRenderModule.class).fire()) ci.cancel();
	}

	@Inject(method = "renderUnderwaterOverlay", at = @At("HEAD"), cancellable = true)
	private static void femboyware$noUnderwater(MinecraftClient client, MatrixStack matrices, VertexConsumerProvider vertexConsumers, CallbackInfo ci) {
		if (FemboyWare.getModules().get(NoRenderModule.class).liquid()) ci.cancel();
	}

	@Inject(method = "renderInWallOverlay", at = @At("HEAD"), cancellable = true)
	private static void femboyware$noInWall(Sprite sprite, MatrixStack matrices, VertexConsumerProvider vertexConsumers, CallbackInfo ci) {
		if (FemboyWare.getModules().get(NoRenderModule.class).inWall()) ci.cancel();
	}
}
