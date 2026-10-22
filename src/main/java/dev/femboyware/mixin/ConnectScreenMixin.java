package dev.femboyware.mixin;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.utility.AutoReconnectModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.CookieStorage;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ConnectScreen.class)
public class ConnectScreenMixin {
	/** Remembers the server for Auto Reconnect, whether or not the module is on yet. */
	@Inject(method = "connect(Lnet/minecraft/client/MinecraftClient;Lnet/minecraft/client/network/ServerAddress;Lnet/minecraft/client/network/ServerInfo;Lnet/minecraft/client/network/CookieStorage;)V", at = @At("HEAD"))
	private void femboyware$rememberServer(MinecraftClient client, ServerAddress address, ServerInfo info, CookieStorage cookieStorage, CallbackInfo ci) {
		FemboyWare.getModules().get(AutoReconnectModule.class).onConnect(address, info);
	}
}
