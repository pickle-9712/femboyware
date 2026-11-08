package dev.femboyware.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.render.HandChamsModule;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Hand Chams: first person arms are submitted with a tint colour and optional glint. */
@Mixin(AvatarRenderer.class)
public class PlayerEntityRendererMixin {
	@Redirect(method = "renderHand", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModelPart(Lnet/minecraft/client/model/geom/ModelPart;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IILnet/minecraft/client/renderer/texture/TextureAtlasSprite;)V"), require = 0)
	private void femboyware$handChams(SubmitNodeCollector queue, ModelPart part, PoseStack matrices, RenderType layer, int light, int overlay, TextureAtlasSprite sprite) {
		if (!FemboyWare.isEnabled(HandChamsModule.class)) {
			queue.submitModelPart(part, matrices, layer, light, overlay, sprite);
			return;
		}
		HandChamsModule chams = FemboyWare.getModules().get(HandChamsModule.class);
		//? if >=26.1 {
		/*queue.submitModelPart(part, matrices, layer, light, overlay, sprite, chams.getColor(), null);
		if (chams.hasGlint()) queue.submitModelPart(part, matrices, net.minecraft.client.renderer.rendertype.RenderTypes.entityGlint(), light, overlay, sprite);
		*///?} else {
		queue.submitModelPart(part, matrices, layer, light, overlay, sprite, false, chams.hasGlint(), chams.getColor(), null, 0);
		//?}
	}
}
