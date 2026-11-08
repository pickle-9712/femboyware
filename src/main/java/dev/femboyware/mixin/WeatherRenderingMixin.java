package dev.femboyware.mixin;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.utility.NoRenderModule;
import net.minecraft.client.renderer.WeatherEffectRenderer;
//? if >=26.1 {
/*import net.minecraft.client.multiplayer.ClientLevel;
*///?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No Render's Weather: no falling rain or snow, and no splashes or rain sounds. */
//? if >=26.1 {
/*// Rain particles and sounds moved to the level's weather tick
@Mixin({WeatherEffectRenderer.class, ClientLevel.class})
*///?} else {
@Mixin(WeatherEffectRenderer.class)
//?}
public class WeatherRenderingMixin {
	//? if >=26.1 {
	/*private static final String PARTICLES = "tickWeatherEffects";
	*///?} else {
	private static final String PARTICLES = "tickRainParticles";
	//?}

	@Inject(method = {"render", PARTICLES}, at = @At("HEAD"), cancellable = true)
	private void femboyware$noWeather(CallbackInfo ci) {
		if (FemboyWare.getModules() != null && FemboyWare.getModules().get(NoRenderModule.class).weather()) ci.cancel();
	}
}
