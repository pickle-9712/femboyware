package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.Timer;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/** Clips through thin walls: when you walk into a wall, teleports you to the first free spot behind it. */
public class PhaseModule extends Module {
	private final NumberSetting distance = add(new NumberSetting("Distance", "How far through walls to look for space", 2, 0.5, 6, 0.1));
	private final NumberSetting delay = add(new NumberSetting("Delay", "Milliseconds between clips", 250, 0, 2000, 10));

	private final Timer timer = new Timer();

	public PhaseModule() {
		super("Phase", "Clips you through walls", Category.RAGE);
	}

	@Override
	public void onTick() {
		if (!mc.player.horizontalCollision || !mc.player.input.hasForwardMovement() || !timer.passed(delay.get())) return;
		double radians = Math.toRadians(mc.player.getYaw());
		Vec3d direction = new Vec3d(-Math.sin(radians), 0, Math.cos(radians));
		Box box = mc.player.getBoundingBox();
		for (double d = 0.3; d <= distance.get(); d += 0.1) {
			Vec3d offset = direction.multiply(d);
			if (!mc.world.isSpaceEmpty(mc.player, box.offset(offset))) continue;
			Vec3d target = mc.player.getEntityPos().add(offset);
			mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(target, mc.player.isOnGround(), false));
			mc.player.setPosition(target);
			timer.reset();
			return;
		}
	}
}
