package dev.femboyware.mixin;

import dev.femboyware.FemboyWare;
import dev.femboyware.gui.FemboyTitleScreen;
import dev.femboyware.module.combat.BodyguardModule;
import dev.femboyware.module.render.MainMenuModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {
	@Shadow
	public ClientPlayerEntity player;
	@Shadow
	public ClientWorld world;
	@Shadow
	@Final
	public GameRenderer gameRenderer;
	@Shadow
	@Final
	private RenderTickCounter.Dynamic renderTickCounter;

	/**
	 * Every frame, just before it's drawn (or would be): modules turn the camera. In Bodyguard's low
	 * power mode the world isn't drawn, which is where the game normally works out what's under the
	 * crosshair, so that's done here instead for Legit Aura to hit with.
	 */
	@Inject(method = "render", at = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiler/Profiler;push(Ljava/lang/String;)V", args = "ldc=gameRenderer"))
	private void femboyware$frame(boolean tick, CallbackInfo ci) {
		if (player == null || world == null || FemboyWare.getModules() == null) return;
		FemboyWare.getModules().onFrame();
		if (BodyguardModule.lowPower()) gameRenderer.updateCrosshairTarget(renderTickCounter.getTickProgress(true));
	}

	/** Main Menu: whenever the vanilla title screen would open, open ours instead. */
	@ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
	private Screen femboyware$mainMenu(Screen screen) {
		return femboyware$swap(screen);
	}

	@Unique
	private static Screen femboyware$swap(Screen screen) {
		if (FemboyWare.getModules() == null || !FemboyWare.getModules().get(MainMenuModule.class).isEnabled()) return screen;
		// Opening nothing outside a world falls back to the title screen, so that's ours too
		boolean fallback = screen == null && MinecraftClient.getInstance().world == null;
		return fallback || screen instanceof TitleScreen ? new FemboyTitleScreen() : screen;
	}
}
