package dev.femboyware.mixin;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.render.AspectModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
//? if >=26.1 {
/*import net.minecraft.client.renderer.Projection;
*///?} else {
import net.minecraft.client.renderer.GameRenderer;
//?}

/** Aspect: the world's projection is stretched to the chosen width to height ratio. */
//? if >=26.1 {
/*@Mixin(Projection.class)
*///?} else {
@Mixin(GameRenderer.class)
//?}
public class AspectMixin {
	//? if >=26.1 {
	/*@ModifyArg(method = "getMatrix", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4f;setPerspective(FFFFZ)Lorg/joml/Matrix4f;"), index = 1, require = 0)
	*///?} else {
	@ModifyArg(method = "getProjectionMatrix", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4f;perspective(FFFF)Lorg/joml/Matrix4f;"), index = 1, require = 0)
	//?}
	private float femboyware$aspect(float ratio) {
		return FemboyWare.getModules() == null ? ratio : FemboyWare.getModules().get(AspectModule.class).modifyRatio(ratio);
	}
}
