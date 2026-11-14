package dev.femboyware.module.combat;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.BlockUtil;
import dev.femboyware.util.KeyUtil;
import dev.femboyware.util.Mc;
import dev.femboyware.util.MouseTurner;
import dev.femboyware.util.Rotation;
import dev.femboyware.util.TargetUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Pulls your aim gently towards the nearest player in front of you, in whole mouse steps like a
 * real mouse, and only while you're aiming yourself.
 */
public class AimAssistModule extends Module {
	private final NumberSetting range = add(new NumberSetting("Range", "Furthest player to help aim at", 4.5, 1, 8, 0.1));
	private final NumberSetting fov = add(new NumberSetting("FOV", "Only players within this many degrees of where you look", 60, 10, 180, 5));
	private final NumberSetting speed = add(new NumberSetting("Speed", "Degrees per second it turns at most", 90, 10, 400, 5));
	private final BoolSetting vertical = add(new BoolSetting("Vertical", "Help up and down too, not just sideways", false));
	private final BoolSetting whileClicking = add(new BoolSetting("While Clicking", "Only while you hold attack", true));
	private final BoolSetting stopOnTarget = add(new BoolSetting("Stop On Target", "Stop pulling once your crosshair is on them", true));

	private final MouseTurner turner = new MouseTurner();
	private long lastFrame;

	public AimAssistModule() {
		super("Aim Assist", "Smooth visible aim correction while you actively aim", Category.LEGIT);
	}

	@Override
	protected void onEnable() {
		lastFrame = 0;
		turner.reset();
	}

	@Override
	public void onFrame() {
		long now = System.nanoTime();
		float seconds = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1e9f);
		lastFrame = now;
		if (seconds <= 0 || Mc.screen() != null) return;
		if (whileClicking.get() && !KeyUtil.isPhysicallyPressed(mc.options.keyAttack)) return;

		Player target = pick();
		if (target == null) return;
		if (stopOnTarget.get() && mc.crosshairPickEntity == target) return;

		Vec3 eyes = mc.player.getEyePosition();
		AABB box = target.getBoundingBox().deflate(0.1);
		Vec3 point = BlockUtil.closestPoint(box, eyes);
		// Aim at the body rather than an edge, so small corrections still land
		point = new Vec3(Mth.lerp(0.5, point.x, box.getCenter().x), point.y, Mth.lerp(0.5, point.z, box.getCenter().z));
		Rotation want = Rotation.between(eyes, point);
		float yawDiff = Mth.wrapDegrees(want.yaw() - mc.player.getYRot());
		float pitchDiff = vertical.get() ? want.pitch() - mc.player.getXRot() : 0;
		// Slow down as it closes in, so it eases on instead of snapping
		float ease = 0.3f + 0.7f * Math.min(1, Math.abs(yawDiff) / 20f);
		float max = speed.getFloat() * seconds * ease;
		float yawStep = Mth.clamp(yawDiff, -max, max);
		float pitchStep = Mth.clamp(pitchDiff, -max, max);
		turner.turn(yawStep, pitchStep);
	}

	private Player pick() {
		Player best = null;
		double bestAngle = fov.get() / 2;
		Vec3 eyes = mc.player.getEyePosition();
		for (Player player : mc.level.players()) {
			if (!TargetUtil.isValid(player) || mc.player.distanceTo(player) > range.get()) continue;
			if (!BlockUtil.canSee(eyes, player.getEyePosition()) && !BlockUtil.canSee(eyes, player.position().add(0, 1, 0))) continue;
			Rotation to = Rotation.between(eyes, player.getBoundingBox().getCenter());
			double angle = Math.abs(Mth.wrapDegrees(to.yaw() - mc.player.getYRot()));
			if (angle < bestAngle) {
				bestAngle = angle;
				best = player;
			}
		}
		return best;
	}
}
