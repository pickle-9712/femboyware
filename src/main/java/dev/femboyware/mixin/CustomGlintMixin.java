package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.femboyware.module.render.CustomGlintModule;
import net.minecraft.client.renderer.rendertype.TextureTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Custom Glint: glint speed and pattern size. Ported from Anubis (GPL-3.0, 4ldenz). */
@Mixin(TextureTransform.class)
public abstract class CustomGlintMixin {
	@ModifyVariable(method = "setupGlintTexturing", at = @At("HEAD"), argsOnly = true, require = 0)
	private static float femboyware$patternScale(float scale) {
		CustomGlintModule module = CustomGlintModule.active();
		return module == null ? scale : scale * module.uvScaleMultiplier();
	}

	@ModifyExpressionValue(method = "setupGlintTexturing", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Util;getMillis()J"), require = 0)
	private static long femboyware$clock(long millis) {
		CustomGlintModule module = CustomGlintModule.active();
		return module == null ? millis : module.glintMillis(millis);
	}
}
