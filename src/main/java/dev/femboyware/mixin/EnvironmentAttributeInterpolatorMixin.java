package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.render.PinkSkyModule;
import dev.femboyware.module.utility.NoRenderModule;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Pink Sky and No Render's fog: the client reads its sky, fog, cloud and daylight values through here. */
@Mixin(EnvironmentAttributeProbe.class)
public class EnvironmentAttributeInterpolatorMixin {
	@ModifyReturnValue(method = "getValue", at = @At("RETURN"))
	private Object femboyware$pinkSky(Object value, EnvironmentAttribute<?> attribute, float tickProgress) {
		if (FemboyWare.getModules() == null) return value;
		Object tinted = FemboyWare.getModules().get(PinkSkyModule.class).modify(attribute, value);
		tinted = FemboyWare.getModules().get(dev.femboyware.module.effects.AuraSkyModule.class).modify(attribute, tinted);
		return FemboyWare.getModules().get(NoRenderModule.class).modify(attribute, tinted);
	}
}
