package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.render.MainMenuModule;
import net.minecraft.resource.ResourceFactory;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.io.InputStream;

/**
 * Main Menu: the loading screen's logo says femboyware instead of Mojang Studios. The logo loads
 * from Minecraft's built-in files before mod resources are ready, so ours is read straight from
 * the mod jar. It's laid out like Mojang's: the left half of the logo on top, the right half below.
 */
@Mixin(targets = "net.minecraft.client.gui.screen.SplashOverlay$LogoTexture")
public class SplashLogoTextureMixin {
	@WrapOperation(method = "loadContents", at = @At(value = "INVOKE", target = "Lnet/minecraft/resource/ResourceFactory;open(Lnet/minecraft/util/Identifier;)Ljava/io/InputStream;"))
	private InputStream femboyware$logo(ResourceFactory factory, Identifier id, Operation<InputStream> original) {
		if (FemboyWare.isEnabled(MainMenuModule.class)) {
			InputStream logo = FemboyWare.class.getResourceAsStream("/assets/femboyware/textures/gui/splash_logo.png");
			if (logo != null) return logo;
		}
		return original.call(factory, id);
	}
}
