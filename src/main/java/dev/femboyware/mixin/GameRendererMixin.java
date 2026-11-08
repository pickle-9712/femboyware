package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.combat.BodyguardModule;
import dev.femboyware.module.render.TotemAnimationModule;
import dev.femboyware.module.utility.NoRenderModule;
import dev.femboyware.module.utility.ZoomModule;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
	/** Bodyguard's low power: don't draw the world at all, so the screen is just the HUD on black. */
	@Inject(method = "renderLevel", at = @At("HEAD"), cancellable = true)
	private void femboyware$lowPower(DeltaTracker tickCounter, CallbackInfo ci) {
		if (BodyguardModule.lowPower()) ci.cancel();
	}

	//? if >=26.1 {
	/*@Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
	private void femboyware$noHurtCam(net.minecraft.client.renderer.state.level.CameraRenderState camera, PoseStack matrices, CallbackInfo ci) {
		if (FemboyWare.getModules().get(NoRenderModule.class).hurtCam()) ci.cancel();
	}
	*///?} else {
	@ModifyReturnValue(method = "getFov", at = @At("RETURN"))
	private float femboyware$zoom(float fov) {
		return FemboyWare.getModules().get(ZoomModule.class).modifyFov(fov);
	}

	@Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
	private void femboyware$noHurtCam(PoseStack matrices, float tickProgress, CallbackInfo ci) {
		if (FemboyWare.getModules().get(NoRenderModule.class).hurtCam()) ci.cancel();
	}
	//?}

	@Inject(method = "displayItemActivation", at = @At("HEAD"), cancellable = true)
	private void femboyware$hideTotem(ItemStack floatingItem, CallbackInfo ci) {
		if (floatingItem.is(Items.TOTEM_OF_UNDYING) && FemboyWare.getModules().get(TotemAnimationModule.class).hide()) ci.cancel();
	}
}
