package dev.femboyware.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.render.TotemAnimationModule;
import dev.femboyware.module.utility.NoRenderModule;
import net.minecraft.client.Minecraft;
//? if <26.1 {
import net.minecraft.client.renderer.MultiBufferSource;
//?}
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ScreenEffectRenderer.class)
public class InGameOverlayRendererMixin {
	@Inject(method = "renderItemActivationAnimation", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;scale(FFF)V", shift = At.Shift.AFTER), require = 0)
	//? if >=26.3 {
	/*private void femboyware$totemScale(net.minecraft.client.renderer.state.level.PlayerRenderState player, PoseStack matrices, float tickProgress, SubmitNodeCollector queue, CallbackInfo ci) {
	*///?} else {
	private void femboyware$totemScale(PoseStack matrices, float tickProgress, SubmitNodeCollector queue, CallbackInfo ci) {
	//?}
		float scale = FemboyWare.getModules().get(TotemAnimationModule.class).getScale();
		if (scale != 1f) matrices.scale(scale, scale, scale);
	}

	//? if >=26.3 {
	/*@Inject(method = "submitFire", at = @At("HEAD"), cancellable = true)
	private static void femboyware$noFire(PoseStack matrices, SubmitNodeCollector queue, TextureAtlasSprite sprite, CallbackInfo ci) {
		if (FemboyWare.getModules().get(NoRenderModule.class).fire()) ci.cancel();
	}

	@Inject(method = "submitWater", at = @At("HEAD"), cancellable = true)
	private static void femboyware$noUnderwater(net.minecraft.client.renderer.state.level.PlayerRenderState.WaterOverlay water, PoseStack matrices, SubmitNodeCollector queue, CallbackInfo ci) {
		if (FemboyWare.getModules().get(NoRenderModule.class).liquid()) ci.cancel();
	}

	@Inject(method = "submitBlockSprite", at = @At("HEAD"), cancellable = true)
	private static void femboyware$noInWall(net.minecraft.resources.Identifier texture, float u0, float u1, float v0, float v1, PoseStack matrices, SubmitNodeCollector queue, int light, CallbackInfo ci) {
		if (FemboyWare.getModules().get(NoRenderModule.class).inWall()) ci.cancel();
	}
	*///?} else if >=26.1 {
	/*@Inject(method = "submitFire", at = @At("HEAD"), cancellable = true)
	private static void femboyware$noFire(PoseStack matrices, SubmitNodeCollector queue, TextureAtlasSprite sprite, CallbackInfo ci) {
		if (FemboyWare.getModules().get(NoRenderModule.class).fire()) ci.cancel();
	}

	@Inject(method = "submitWater", at = @At("HEAD"), cancellable = true)
	private static void femboyware$noUnderwater(Minecraft client, PoseStack matrices, SubmitNodeCollector queue, CallbackInfo ci) {
		if (FemboyWare.getModules().get(NoRenderModule.class).liquid()) ci.cancel();
	}

	@Inject(method = "submitBlockSprite", at = @At("HEAD"), cancellable = true)
	private static void femboyware$noInWall(TextureAtlasSprite sprite, PoseStack matrices, SubmitNodeCollector queue, int light, CallbackInfo ci) {
		if (FemboyWare.getModules().get(NoRenderModule.class).inWall()) ci.cancel();
	}
	*///?} else {
	@Inject(method = "renderFire", at = @At("HEAD"), cancellable = true)
	private static void femboyware$noFire(PoseStack matrices, MultiBufferSource vertexConsumers, TextureAtlasSprite sprite, CallbackInfo ci) {
		if (FemboyWare.getModules().get(NoRenderModule.class).fire()) ci.cancel();
	}

	@Inject(method = "renderWater", at = @At("HEAD"), cancellable = true)
	private static void femboyware$noUnderwater(Minecraft client, PoseStack matrices, MultiBufferSource vertexConsumers, CallbackInfo ci) {
		if (FemboyWare.getModules().get(NoRenderModule.class).liquid()) ci.cancel();
	}

	@Inject(method = "renderTex", at = @At("HEAD"), cancellable = true)
	private static void femboyware$noInWall(TextureAtlasSprite sprite, PoseStack matrices, MultiBufferSource vertexConsumers, CallbackInfo ci) {
		if (FemboyWare.getModules().get(NoRenderModule.class).inWall()) ci.cancel();
	}
	//?}
}
