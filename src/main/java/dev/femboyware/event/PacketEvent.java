package dev.femboyware.event;

import net.minecraft.network.packet.Packet;

public class PacketEvent {
	private final Packet<?> packet;
	private boolean cancelled;

	public PacketEvent(Packet<?> packet) {
		this.packet = packet;
	}

	public Packet<?> getPacket() {
		return packet;
	}

	public boolean isCancelled() {
		return cancelled;
	}

	public void cancel() {
		cancelled = true;
	}
}
