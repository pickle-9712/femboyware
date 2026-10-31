package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.render.MainMenuModule;
import net.minecraft.client.gui.screen.SplashOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Main Menu: the loading screen is pink instead of Mojang red. */
@Mixin(SplashOverlay.class)
public class SplashOverlayMixin {
	@Unique
	private static final int PINK = 0xFFE8559E;

	@ModifyExpressionValue(method = "render", at = @At(value = "INVOKE", target = "Ljava/util/function/IntSupplier;getAsInt()I"))
	private int femboyware$pink(int color) {
		return FemboyWare.isEnabled(MainMenuModule.class) ? PINK : color;
	}
}
