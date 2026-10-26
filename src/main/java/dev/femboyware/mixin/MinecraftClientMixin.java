package dev.femboyware.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.femboyware.FemboyWare;
import dev.femboyware.gui.FemboyTitleScreen;
import dev.femboyware.module.render.MainMenuModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {
	/** Main Menu: whenever the vanilla title screen would open, open ours instead. */
	@ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
	private Screen femboyware$mainMenu(Screen screen) {
		return femboyware$swap(screen);
	}

	/** With no screen and no world, setScreen makes its own title screen; swap that one too. */
	@ModifyExpressionValue(method = "setScreen", at = @At(value = "NEW", target = "()Lnet/minecraft/client/gui/screen/TitleScreen;"))
	private TitleScreen femboyware$fallbackMenu(TitleScreen screen) {
		return (TitleScreen) femboyware$swap(screen);
	}

	@Unique
	private static Screen femboyware$swap(Screen screen) {
		if (!(screen instanceof TitleScreen) || screen instanceof FemboyTitleScreen || FemboyWare.getModules() == null) return screen;
		return FemboyWare.getModules().get(MainMenuModule.class).isEnabled() ? new FemboyTitleScreen() : screen;
	}
}
