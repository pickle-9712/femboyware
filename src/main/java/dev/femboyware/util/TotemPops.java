package dev.femboyware.util;

import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.LivingEntity;
/** Ported from the Anubis client (GPL-3.0, by 4ldenz, https://github.com/4ldenz/Anubis-client). */
public final class TotemPops {
	private TotemPops() {
	}

	public static void scan(Packet<?> packet, Consumer<LivingEntity> sink) {
		if (packet instanceof ClientboundEntityEventPacket event) {
			check(event, sink);
		} else if (packet instanceof ClientboundBundlePacket bundle) {
			for (Packet<?> inner : bundle.subPackets()) {
				if (inner instanceof ClientboundEntityEventPacket event) {
					check(event, sink);
				}
			}
		}
	}

	private static void check(ClientboundEntityEventPacket event, Consumer<LivingEntity> sink) {
		if (event.getEventId() == 35) {
			ClientLevel level = Minecraft.getInstance().level;
			if (level != null) {
				if (event.getEntity(level) instanceof LivingEntity living && !living.isRemoved()) {
					sink.accept(living);
				}
			}
		}
	}
}
