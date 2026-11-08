package dev.femboyware.module.movement;

import dev.femboyware.event.PacketEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.Vec3;

/** Hangs in mid air while gliding. Velocity is zeroed by the gliding mixin. */
public class ElytraFreezeModule extends Module {
	private final BoolSetting cancelPackets = add(new BoolSetting("Cancel Packets", "Stop sending position updates while frozen", false));

	public ElytraFreezeModule() {
		super("Elytra Freeze", "Freezes you in place while gliding", Category.RAGE);
	}

	/** Called with vanilla's gliding velocity for the local player. */
	public Vec3 modifyGliding(Vec3 velocity) {
		return isEnabled() ? Vec3.ZERO : velocity;
	}

	@Override
	public void onPacketSend(PacketEvent event) {
		if (cancelPackets.get() && mc.player.isFallFlying() && event.getPacket() instanceof ServerboundMovePlayerPacket packet && packet.hasPosition()) {
			event.cancel();
		}
	}
}
