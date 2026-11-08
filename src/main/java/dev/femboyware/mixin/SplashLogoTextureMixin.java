package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.render.MainMenuModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.io.InputStream;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceProvider;

/**
 * Main Menu: the loading screen's logo says femboyware instead of Mojang Studios. The logo loads
 * from Minecraft's built-in files before mod resources are ready, so ours is read straight from
 * the mod jar. It's laid out like Mojang's: the left half of the logo on top, the right half below.
 */
@Mixin(targets = "net.minecraft.client.gui.screens.LoadingOverlay$LogoTexture")
public class SplashLogoTextureMixin {
	@WrapOperation(method = "loadContents", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/packs/resources/ResourceProvider;open(Lnet/minecraft/resources/Identifier;)Ljava/io/InputStream;"))
	private InputStream femboyware$logo(ResourceProvider factory, Identifier id, Operation<InputStream> original) {
		if (FemboyWare.isEnabled(MainMenuModule.class)) {
			InputStream logo = FemboyWare.class.getResourceAsStream("/assets/femboyware/textures/gui/splash_logo.png");
			if (logo != null) return logo;
		}
		return original.call(factory, id);
	}
}
