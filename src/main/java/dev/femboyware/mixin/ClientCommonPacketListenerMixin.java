package dev.femboyware.mixin;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.combat.BodyguardModule;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientCommonPacketListenerMixin {
	@Shadow
	@Final
	protected ServerData serverData;

	/** Bodyguards take the server's resource pack without the yes/no prompt, which nobody's there to click. */
	@Inject(method = "handleResourcePackPush", at = @At("HEAD"))
	private void femboyware$guardAcceptsPacks(ClientboundResourcePackPushPacket packet, CallbackInfo ci) {
		if (serverData != null && FemboyWare.getModules().get(BodyguardModule.class).acceptsPacks()) {
			serverData.setResourcePackStatus(ServerData.ServerPackStatus.ENABLED);
		}
	}
}
