package dev.femboyware.mixin;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.combat.BodyguardModule;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Minecraft.class)
public class MinecraftClientMixin {
	@Shadow
	public LocalPlayer player;
	@Shadow
	public ClientLevel level;
	@Shadow
	@Final
	public GameRenderer gameRenderer;
	//? if >=26.1 {
	/*@Shadow
	@Final
	private DeltaTracker.Timer deltaTracker;
	@Shadow
	private void pick(float partialTicks) {
	}
	*///?} else {
	@Shadow
	@Final
	private DeltaTracker.Timer deltaTracker;
	//?}

	/**
	 * Every frame, just before it's drawn (or would be): modules turn the camera. In Bodyguard's low
	 * power mode the world isn't drawn, which is where the game normally works out what's under the
	 * crosshair, so that's done here instead for Legit Aura to hit with.
	 */
	//? if >=26.1 {
	/*@Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;renderFrame(Z)V"))
	*///?} else {
	@Inject(method = "runTick", at = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiling/ProfilerFiller;push(Ljava/lang/String;)V", args = "ldc=gameRenderer"))
	//?}
	private void femboyware$frame(boolean tick, CallbackInfo ci) {
		if (player == null || level == null || FemboyWare.getModules() == null) return;
		FemboyWare.getModules().onFrame();
		//? if >=26.1 {
		/*if (BodyguardModule.lowPower()) pick(deltaTracker.getGameTimeDeltaPartialTick(true));
		*///?} else {
		if (BodyguardModule.lowPower()) gameRenderer.pick(deltaTracker.getGameTimeDeltaPartialTick(true));
		//?}
	}

	/**
	 * Your profile with its skin: the launch fetch is often rate limited (HTTP 429) and leaves the
	 * menus showing the default skin; FreshProfile retries in the background and this hands out the
	 * fetched copy (same account, now with textures) once it's in.
	 */
	@Inject(method = "getGameProfile", at = @At("RETURN"), cancellable = true)
	private void femboyware$freshProfile(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<com.mojang.authlib.GameProfile> cir) {
		cir.setReturnValue(dev.femboyware.util.FreshProfile.of(cir.getReturnValue()));
	}

	/** Fake FPS: every FPS counter reads this. */
	@com.llamalad7.mixinextras.injector.ModifyReturnValue(method = "getFps", at = @At("RETURN"))
	private int femboyware$fakeFps(int fps) {
		return dev.femboyware.FemboyWare.getModules() == null ? fps : dev.femboyware.FemboyWare.getModules().get(dev.femboyware.module.utility.FakeFpsModule.class).adjust(fps);
	}
}
