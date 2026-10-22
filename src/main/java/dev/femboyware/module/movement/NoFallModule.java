package dev.femboyware.module.movement;

import dev.femboyware.event.PacketEvent;
import dev.femboyware.mixin.PlayerMoveC2SPacketAccessor;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

/**
 * Claims to be on the ground in movement packets while falling. The server resets your fall
 * distance every time it thinks you landed, so you never fall far enough to take damage.
 */
public class NoFallModule extends Module {
	public NoFallModule() {
		super("No Fall", "Prevents fall damage", Category.RAGE);
	}

	@Override
	public void onPacketSend(PacketEvent event) {
		if (!(event.getPacket() instanceof PlayerMoveC2SPacket packet)) return;
		// Landing while gliding would end the glide server side
		if (mc.player.isGliding() || mc.player.getAbilities().flying || mc.player.fallDistance <= 2) return;
		((PlayerMoveC2SPacketAccessor) packet).femboyware$setOnGround(true);
	}
}
