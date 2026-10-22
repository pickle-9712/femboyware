package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.math.BlockPos;

/** Saves you when you are about to fall into the void. */
public class AntiVoidModule extends Module {
	public enum Mode {
		/** Bounce back up. */
		JUMP,
		/** Send an invalid position so the server sets you back to safety. */
		FLAG
	}

	private final EnumSetting<Mode> mode = add(new EnumSetting<>("Mode", "How to get out of the void", Mode.JUMP));
	private final NumberSetting height = add(new NumberSetting("Height", "Trigger this many blocks above the bottom of the world", 5, 0, 64, 1));

	public AntiVoidModule() {
		super("Anti Void", "Stops you from falling into the void", Category.RAGE);
	}

	@Override
	public String getInfo() {
		return mode.getDisplayName();
	}

	@Override
	public void onTick() {
		if (mc.player.isOnGround() || mc.player.getVelocity().y >= 0 || mc.player.getAbilities().flying) return;
		if (mc.player.getY() > mc.world.getBottomY() + height.get() || !isOverVoid()) return;
		switch (mode.get()) {
			case JUMP -> mc.player.setVelocity(mc.player.getVelocity().x, 0.42, mc.player.getVelocity().z);
			case FLAG -> mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
				mc.player.getX(), mc.player.getY() + 50, mc.player.getZ(), false, false));
		}
	}

	private boolean isOverVoid() {
		BlockPos.Mutable pos = mc.player.getBlockPos().mutableCopy();
		while (pos.getY() >= mc.world.getBottomY()) {
			if (!mc.world.getBlockState(pos).getCollisionShape(mc.world, pos).isEmpty()) return false;
			pos.move(0, -1, 0);
		}
		return true;
	}
}
