package dev.femboyware.mixin;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.utility.NoRenderModule;
import net.minecraft.client.render.WeatherRendering;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No Render's Weather: no falling rain or snow, and no splashes or rain sounds. */
@Mixin(WeatherRendering.class)
public class WeatherRenderingMixin {
	@Inject(method = {"renderPrecipitation", "addParticlesAndSound"}, at = @At("HEAD"), cancellable = true)
	private void femboyware$noWeather(CallbackInfo ci) {
		if (FemboyWare.getModules() != null && FemboyWare.getModules().get(NoRenderModule.class).weather()) ci.cancel();
	}
}
