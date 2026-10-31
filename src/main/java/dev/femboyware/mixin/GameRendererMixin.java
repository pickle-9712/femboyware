package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.combat.BodyguardModule;
import dev.femboyware.module.render.AspectModule;
import dev.femboyware.module.render.TotemAnimationModule;
import dev.femboyware.module.utility.NoRenderModule;
import dev.femboyware.module.utility.ZoomModule;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
	/** Bodyguard's low power: don't draw the world at all, so the screen is just the HUD on black. */
	@Inject(method = "renderWorld", at = @At("HEAD"), cancellable = true)
	private void femboyware$lowPower(RenderTickCounter tickCounter, CallbackInfo ci) {
		if (BodyguardModule.lowPower()) ci.cancel();
	}

	@ModifyReturnValue(method = "getFov", at = @At("RETURN"))
	private float femboyware$zoom(float fov) {
		return FemboyWare.getModules().get(ZoomModule.class).modifyFov(fov);
	}

	@ModifyArg(method = "getBasicProjectionMatrix", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4f;perspective(FFFF)Lorg/joml/Matrix4f;"), index = 1, require = 0)
	private float femboyware$aspect(float ratio) {
		return FemboyWare.getModules() == null ? ratio : FemboyWare.getModules().get(AspectModule.class).modifyRatio(ratio);
	}

	@Inject(method = "tiltViewWhenHurt", at = @At("HEAD"), cancellable = true)
	private void femboyware$noHurtCam(MatrixStack matrices, float tickProgress, CallbackInfo ci) {
		if (FemboyWare.getModules().get(NoRenderModule.class).hurtCam()) ci.cancel();
	}

	@Inject(method = "showFloatingItem", at = @At("HEAD"), cancellable = true)
	private void femboyware$hideTotem(ItemStack floatingItem, CallbackInfo ci) {
		if (floatingItem.isOf(Items.TOTEM_OF_UNDYING) && FemboyWare.getModules().get(TotemAnimationModule.class).hide()) ci.cancel();
	}
}
