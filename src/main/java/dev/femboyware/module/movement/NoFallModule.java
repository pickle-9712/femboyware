package dev.femboyware.module.movement;

import dev.femboyware.event.PacketEvent;
import dev.femboyware.mixin.PlayerMoveC2SPacketAccessor;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.EnumSetting;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

/**
 * Packet: claims to be on the ground in movement packets while falling. The server resets your fall
 * distance every time it thinks you landed, so you never fall far enough to take damage.
 * Donut: DonutSMP's anti-cheat checks that, so once you've fallen 3 blocks it says you're in the
 * air on every packet until you've really landed for a few packets, nudging you up a hair on every
 * other one. From the Anubis client's Donut NoFall (GPL-3.0, by 4ldenz,
 * https://github.com/4ldenz/Anubis-client).
 */
public class NoFallModule extends Module {
	public enum Mode {
		PACKET, DONUT
	}

	private final EnumSetting<Mode> mode = add(new EnumSetting<>("Mode", "Packet works most places; Donut is for DonutSMP", Mode.PACKET));

	private boolean falling, sending;
	private double lastFall;
	private int groundTicks;

	public NoFallModule() {
		super("No Fall", "Prevents fall damage", Category.RAGE);
	}

	@Override
	protected void onEnable() {
		falling = false;
		lastFall = 0;
		groundTicks = 0;
	}

	@Override
	public String getInfo() {
		return mode.is(Mode.DONUT) ? "Donut" : null;
	}

	@Override
	public void onTick() {
		if (!mode.is(Mode.DONUT) || mc.player == null) return;
		if (falling) mc.player.setOnGround(false);
		if (lastFall > 3) falling = true;
		lastFall = mc.player.fallDistance;
	}

	@Override
	public void onPacketSend(PacketEvent event) {
		if (!(event.getPacket() instanceof ServerboundMovePlayerPacket packet)) return;
		if (mode.is(Mode.DONUT)) {
			donut(event, packet);
			return;
		}
		// Landing while gliding would end the glide server side
		if (mc.player.isFallFlying() || mc.player.getAbilities().flying || mc.player.fallDistance <= 2) return;
		((PlayerMoveC2SPacketAccessor) packet).femboyware$setOnGround(true);
	}

	private void donut(PacketEvent event, ServerboundMovePlayerPacket packet) {
		if (sending || mc.player == null || mc.getConnection() == null) return;
		event.cancel();
		if (falling && packet.isOnGround()) groundTicks++;
		if (groundTicks % 2 == 1) mc.player.setPos(mc.player.getX(), mc.player.getY() + 1.0E-8, mc.player.getZ());
		boolean onGround = !falling && mc.player.onGround();
		sending = true;
		try {
			mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(mc.player.getX(), mc.player.getY(), mc.player.getZ(), mc.player.getYRot(), mc.player.getXRot(), onGround, false));
		} finally {
			sending = false;
		}
		if (groundTicks == 4) {
			falling = false;
			groundTicks = 0;
		}
	}
}
