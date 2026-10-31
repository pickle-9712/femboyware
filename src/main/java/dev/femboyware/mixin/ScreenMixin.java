package dev.femboyware.mixin;

import dev.femboyware.FemboyWare;
import dev.femboyware.gui.SwirlBackground;
import dev.femboyware.module.render.MainMenuModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.MessageScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.LevelLoadingScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Main Menu theme for every screen outside a world (world list, create world, loading terrain,
 * options from the menu...): the pink swirl instead of the panorama, and a soft plum tint instead
 * of the dark textured overlay. Screens inside a world keep their vanilla look.
 */
@Mixin(Screen.class)
public class ScreenMixin {
	/** Light enough to keep the pink, dark enough to read text on. */
	@Unique
	private static final int TINT = 0x48200A1C;

	@Inject(method = "renderPanoramaBackground", at = @At("HEAD"), cancellable = true)
	private void femboyware$pinkBackground(DrawContext context, float deltaTicks, CallbackInfo ci) {
		if (!femboyware$themed()) return;
		Screen screen = (Screen) (Object) this;
		SwirlBackground.draw(context, screen.width, screen.height);
		ci.cancel();
	}

	@Inject(method = "renderDarkening(Lnet/minecraft/client/gui/DrawContext;IIII)V", at = @At("HEAD"), cancellable = true)
	private void femboyware$pinkDarkening(DrawContext context, int x, int y, int width, int height, CallbackInfo ci) {
		if (!femboyware$themed()) return;
		context.fill(x, y, x + width, y + height, TINT);
		ci.cancel();
	}

	/** Outside a world, plus loading and message screens, which can show while a world is still being joined. */
	@Unique
	private boolean femboyware$themed() {
		if (FemboyWare.getModules() == null || !FemboyWare.getModules().get(MainMenuModule.class).isEnabled()) return false;
		Object screen = this;
		return MinecraftClient.getInstance().world == null || screen instanceof LevelLoadingScreen || screen instanceof MessageScreen;
	}
}
