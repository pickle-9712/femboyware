package dev.femboyware.module.movement;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.Rotation;
import dev.femboyware.util.TargetUtil;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;

/** Steers your elytra flight straight at the nearest player. */
public class ElytraTargetModule extends Module {
	private static final int ROTATION_PRIORITY = 60;

	private final NumberSetting range = add(new NumberSetting("Range", "How far away targets can be", 64, 8, 256, 1));
	private final NumberSetting turnSpeed = add(new NumberSetting("Turn Speed", "Max degrees turned per tick", 20, 1, 180, 1));
	private final NumberSetting heightOffset = add(new NumberSetting("Height Offset", "Aim this many blocks above the target", 0, -5, 30, 0.5));

	public ElytraTargetModule() {
		super("Elytra Target", "Flies your elytra towards players", Category.RAGE);
	}

	@Override
	public void onPreMotion() {
		if (!mc.player.isGliding()) return;
		PlayerEntity target = TargetUtil.closestPlayer(range.get());
		if (target == null) return;
		Vec3d aim = target.getBoundingBox().getCenter().add(0, heightOffset.get(), 0);
		FemboyWare.getRotations().request(Rotation.between(mc.player.getEyePos(), aim), ROTATION_PRIORITY, turnSpeed.getFloat(), true);
	}
}
