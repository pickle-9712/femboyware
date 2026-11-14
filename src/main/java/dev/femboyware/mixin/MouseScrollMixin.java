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
}
