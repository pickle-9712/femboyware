package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.combat.BodyguardModule;
import dev.femboyware.module.render.MainMenuModule;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Main Menu: with the inactivity limit on "AFK", Minecraft drops to 30 then 10 FPS after a minute
 * without input, which made the animated menu background stutter and look frozen. Out of a world
 * the menu keeps the normal 60 FPS menu rate (or the max FPS, if lower) unless the game is minimised.
 */
@Mixin(FramerateLimitTracker.class)
public class InactivityFpsLimiterMixin {
	@ModifyReturnValue(method = "getFramerateLimit", at = @At("RETURN"))
	private int femboyware$smoothMenu(int fps) {
		Minecraft client = Minecraft.getInstance();
		// Bodyguard's low power caps the frame rate however the window is being used
		if (client.level != null && BodyguardModule.lowPower()) return Math.min(fps, FemboyWare.getModules().get(BodyguardModule.class).lowPowerFps());
		if (client.level != null || client.getWindow().isIconified() || !FemboyWare.isEnabled(MainMenuModule.class)) return fps;
		return Math.max(fps, Math.min(60, client.options.framerateLimit().get()));
	}
}
