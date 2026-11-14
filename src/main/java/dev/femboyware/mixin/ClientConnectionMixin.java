package dev.femboyware.mixin;

import dev.femboyware.FemboyWare;
import dev.femboyware.event.PacketEvent;
import dev.femboyware.module.combat.BacktrackModule;
import dev.femboyware.util.TickRate;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public class ClientConnectionMixin {
	@Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
	private void femboyware$onReceive(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
		if (FemboyWare.getModules() == null) return;
		TickRate.onPacket(packet);
		if (packet instanceof BundlePacket<?> bundle) {
			// Spawn packets usually arrive bundled, so expose the contents individually (not cancellable)
			for (Packet<?> inner : bundle.subPackets()) {
				FemboyWare.getModules().onPacketReceive(new PacketEvent(inner));
			}
			if (BacktrackModule.hold(packet)) ci.cancel();
			return;
		}
		if (FemboyWare.getModules().onPacketReceive(new PacketEvent(packet)) || BacktrackModule.hold(packet)) ci.cancel();
	}

	@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("HEAD"), cancellable = true)
	private void femboyware$onSend(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
		if (FemboyWare.getModules() == null) return;
		if (FemboyWare.getModules().onPacketSend(new PacketEvent(packet))) ci.cancel();
	}
}
