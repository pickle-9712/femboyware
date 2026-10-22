package dev.femboyware.mixin;

import dev.femboyware.FemboyWare;
import dev.femboyware.event.PacketEvent;
import dev.femboyware.util.TickRate;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.BundlePacket;
import net.minecraft.network.packet.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientConnection.class)
public class ClientConnectionMixin {
	@Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/packet/Packet;)V", at = @At("HEAD"), cancellable = true)
	private void femboyware$onReceive(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
		if (FemboyWare.getModules() == null) return;
		TickRate.onPacket(packet);
		if (packet instanceof BundlePacket<?> bundle) {
			// Spawn packets usually arrive bundled, so expose the contents individually (not cancellable)
			for (Packet<?> inner : bundle.getPackets()) {
				FemboyWare.getModules().onPacketReceive(new PacketEvent(inner));
			}
			return;
		}
		if (FemboyWare.getModules().onPacketReceive(new PacketEvent(packet))) ci.cancel();
	}

	@Inject(method = "send(Lnet/minecraft/network/packet/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("HEAD"), cancellable = true)
	private void femboyware$onSend(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
		if (FemboyWare.getModules() == null) return;
		if (FemboyWare.getModules().onPacketSend(new PacketEvent(packet))) ci.cancel();
	}
}
