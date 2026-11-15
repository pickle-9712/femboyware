package dev.femboyware.mixin;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.larp.TvModule;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** TV: scrolling while you look at a TV mirroring your browser swipes it instead of changing hotbar slot. */
@Mixin(MouseHandler.class)
public class MouseScrollMixin {
	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void femboyware$tvSwipe(long window, double xOffset, double yOffset, CallbackInfo ci) {
		if (FemboyWare.getModules() != null && FemboyWare.getModules().get(TvModule.class).onScroll(yOffset)) ci.cancel();
	}

	/** The ClickGUI hanging in the world keeps your mouse locked, so you aim at it with the crosshair. */
	@Inject(method = "releaseMouse", at = @At("HEAD"), cancellable = true)
	private void femboyware$worldGuiKeepsMouse(CallbackInfo ci) {
		if (dev.femboyware.util.Mc.screen() instanceof dev.femboyware.gui.ClickGuiScreen screen && dev.femboyware.gui.WorldGui.showing(screen)) ci.cancel();
	}
}
