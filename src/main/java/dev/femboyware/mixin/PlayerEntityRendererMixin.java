package dev.femboyware.mixin;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.render.HandChamsModule;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Hand Chams: first person arms are submitted with a tint colour and optional glint. */
@Mixin(PlayerEntityRenderer.class)
public class PlayerEntityRendererMixin {
	@Redirect(method = "renderArm", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;submitModelPart(Lnet/minecraft/client/model/ModelPart;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/RenderLayer;IILnet/minecraft/client/texture/Sprite;)V"), require = 0)
	private void femboyware$handChams(OrderedRenderCommandQueue queue, ModelPart part, MatrixStack matrices, RenderLayer layer, int light, int overlay, Sprite sprite) {
		if (!FemboyWare.isEnabled(HandChamsModule.class)) {
			queue.submitModelPart(part, matrices, layer, light, overlay, sprite);
			return;
		}
		HandChamsModule chams = FemboyWare.getModules().get(HandChamsModule.class);
		queue.submitModelPart(part, matrices, layer, light, overlay, sprite, false, chams.hasGlint(), chams.getColor(), null, 0);
	}
}
