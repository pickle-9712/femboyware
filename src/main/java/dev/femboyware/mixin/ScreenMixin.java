package dev.femboyware.mixin;

import dev.femboyware.FemboyWare;
import dev.femboyware.gui.SwirlBackground;
import dev.femboyware.module.render.MainMenuModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.Screen;
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
	//? if >=26.1 {
	/*private static final String PANORAMA = "extractPanorama", MENU_BACKGROUND = "extractMenuBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIII)V", WHOLE = "extractRenderStateWithTooltipAndSubtitles";
	*///?} else {
	private static final String PANORAMA = "renderPanorama", MENU_BACKGROUND = "renderMenuBackground(Lnet/minecraft/client/gui/GuiGraphics;IIII)V", WHOLE = "renderWithTooltipAndSubtitles";
	//?}

	/** Light enough to keep the pink, dark enough to read text on. */
	@Unique
	private static final int TINT = 0x48200A1C;

	@Inject(method = PANORAMA, at = @At("HEAD"), cancellable = true)
	private void femboyware$pinkBackground(GuiGraphics context, float deltaTicks, CallbackInfo ci) {
		if (!femboyware$themed()) return;
		Screen screen = (Screen) (Object) this;
		SwirlBackground.draw(context, screen.width, screen.height);
		ci.cancel();
	}

	@Inject(method = MENU_BACKGROUND, at = @At("HEAD"), cancellable = true)
	private void femboyware$pinkDarkening(GuiGraphics context, int x, int y, int width, int height, CallbackInfo ci) {
		if (!femboyware$themed()) return;
		context.fill(x, y, x + width, y + height, TINT);
		ci.cancel();
	}

	/** GUI Animation: the screen's animated in as it opens. */
	@Inject(method = WHOLE, at = @At("HEAD"))
	private void femboyware$animateIn(GuiGraphics context, int mouseX, int mouseY, float deltaTicks, CallbackInfo ci) {
		dev.femboyware.module.render.GuiAnimationModule.begin((Screen) (Object) this, context);
		dev.femboyware.module.render.ThreeDUiModule.begin((Screen) (Object) this, context, mouseX, mouseY);
	}

	@Inject(method = WHOLE, at = @At("RETURN"))
	private void femboyware$animateInEnd(GuiGraphics context, int mouseX, int mouseY, float deltaTicks, CallbackInfo ci) {
		dev.femboyware.module.render.ThreeDUiModule.end(context);
		dev.femboyware.module.render.GuiAnimationModule.end(context);
	}

	/** Outside a world, plus loading and message screens, which can show while a world is still being joined. */
	@Unique
	private boolean femboyware$themed() {
		if (FemboyWare.getModules() == null || !FemboyWare.getModules().get(MainMenuModule.class).isEnabled()) return false;
		Object screen = this;
		return Minecraft.getInstance().level == null || screen instanceof LevelLoadingScreen || screen instanceof GenericMessageScreen;
	}
}
