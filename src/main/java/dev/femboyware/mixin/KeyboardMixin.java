package dev.femboyware.mixin;

import dev.femboyware.FemboyWare;
import net.minecraft.client.Keyboard;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.KeyInput;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Keyboard.class)
public class KeyboardMixin {
	@Inject(method = "onKey", at = @At("HEAD"))
	private void femboyware$onKey(long window, int action, KeyInput input, CallbackInfo ci) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (action != GLFW.GLFW_PRESS || mc.currentScreen != null || mc.player == null) return;
		if (input.key() == GLFW.GLFW_KEY_UNKNOWN) return;
		FemboyWare.getModules().onKey(input.key());
	}
}
