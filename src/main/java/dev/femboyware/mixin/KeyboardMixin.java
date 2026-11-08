package dev.femboyware.mixin;

import dev.femboyware.util.Mc;
import dev.femboyware.FemboyWare;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public class KeyboardMixin {
	@Inject(method = "keyPress", at = @At("HEAD"))
	private void femboyware$onKey(long window, int action, KeyEvent input, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (action != GLFW.GLFW_PRESS || Mc.screen() != null || mc.player == null) return;
		if (input.key() == GLFW.GLFW_KEY_UNKNOWN) return;
		FemboyWare.getModules().onKey(input.key());
	}
}
