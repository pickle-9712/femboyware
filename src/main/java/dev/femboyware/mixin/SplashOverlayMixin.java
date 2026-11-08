package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.render.MainMenuModule;
import net.minecraft.client.gui.screens.LoadingOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Main Menu: the loading screen is pink instead of Mojang red. */
@Mixin(LoadingOverlay.class)
public class SplashOverlayMixin {
	//? if >=26.1 {
	/*private static final String RENDER = "extractRenderState";
	*///?} else {
	private static final String RENDER = "render";
	//?}

	@Unique
	private static final int PINK = 0xFFE8559E;

	@ModifyExpressionValue(method = RENDER, at = @At(value = "INVOKE", target = "Ljava/util/function/IntSupplier;getAsInt()I"))
	private int femboyware$pink(int color) {
		return FemboyWare.isEnabled(MainMenuModule.class) ? PINK : color;
	}
}
