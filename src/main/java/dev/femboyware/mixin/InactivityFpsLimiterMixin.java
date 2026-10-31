package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.combat.BodyguardModule;
import dev.femboyware.module.render.MainMenuModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.InactivityFpsLimiter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Main Menu: with the inactivity limit on "AFK", Minecraft drops to 30 then 10 FPS after a minute
 * without input, which made the animated menu background stutter and look frozen. Out of a world
 * the menu keeps the normal 60 FPS menu rate (or the max FPS, if lower) unless the game is minimised.
 */
@Mixin(InactivityFpsLimiter.class)
public class InactivityFpsLimiterMixin {
	@ModifyReturnValue(method = "update", at = @At("RETURN"))
	private int femboyware$smoothMenu(int fps) {
		MinecraftClient client = MinecraftClient.getInstance();
		// Bodyguard's low power caps the frame rate however the window is being used
		if (client.world != null && BodyguardModule.lowPower()) return Math.min(fps, FemboyWare.getModules().get(BodyguardModule.class).lowPowerFps());
		if (client.world != null || client.getWindow().isMinimized() || !FemboyWare.isEnabled(MainMenuModule.class)) return fps;
		return Math.max(fps, Math.min(60, client.options.getMaxFps().getValue()));
	}
}
