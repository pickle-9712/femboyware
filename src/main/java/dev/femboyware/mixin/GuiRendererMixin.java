package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.femboyware.gui.WorldGui;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The ClickGUI's world screen: its own GUI renderer draws into the screen's texture, never blurring yours. */
@Mixin(GuiRenderer.class)
public class GuiRendererMixin {
	//? if >=26.1 {
	/*@ModifyExpressionValue(method = "draw", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;mainRenderTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;"))
	*///?} else {
	@ModifyExpressionValue(method = "draw", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;getMainRenderTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;"))
	//?}
	private RenderTarget femboyware$worldGuiTarget(RenderTarget main) {
		return WorldGui.isOurs(this) && WorldGui.target() != null ? WorldGui.target() : main;
	}

	@WrapWithCondition(method = "draw", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;processBlurEffect()V"))
	private boolean femboyware$worldGuiBlur(GameRenderer gameRenderer) {
		return !WorldGui.isOurs(this);
	}
}
