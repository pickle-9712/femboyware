package dev.femboyware.mixin;

import dev.femboyware.FemboyWare;
import dev.femboyware.gui.FemboyTitleScreen;
import dev.femboyware.module.render.MainMenuModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
//? if >=26.1 {
/*import net.minecraft.client.gui.Gui;
*///?}

/** Main Menu: whenever the vanilla title screen would open, open ours instead. */
//? if >=26.1 {
/*@Mixin(Gui.class)
*///?} else {
@Mixin(Minecraft.class)
//?}
public class SetScreenMixin {
	@ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
	private Screen femboyware$mainMenu(Screen screen) {
		if (FemboyWare.getModules() == null || !FemboyWare.getModules().get(MainMenuModule.class).isEnabled()) return screen;
		// Opening nothing outside a world falls back to the title screen, so that's ours too
		boolean fallback = screen == null && Minecraft.getInstance().level == null;
		return fallback || screen instanceof TitleScreen ? new FemboyTitleScreen() : screen;
	}
}
