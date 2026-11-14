package dev.femboyware.module.tung;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.module.render.TungSahurModule;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * A little Tung Tung Tung Sahur who follows you about, hopping along a step behind you, and swings
 * his bat whenever you hit something. Only you can see him.
 */
public class SahurPetModule extends Module {
	private static final long SWING = 450;

	private final NumberSetting size = add(new NumberSetting("Size", "How tall he is, in blocks", 0.9, 0.4, 2.5, 0.05));
	private final BoolSetting bonk = add(new BoolSetting("Bonk Sound", "A wooden bonk when he swings with you", true));

	private Vec3 pos, prevPos;
	private float yaw, prevYaw;
	private float walk, prevWalk;
	private float moving;
	private long swingStart;

	public SahurPetModule() {
		super("Sahur Pet", "A little Tung Tung Tung Sahur follows you and swings with you", Category.COSMETIC);
	}

	@Override
	protected void onEnable() {
		pos = prevPos = null;
	}

	@Override
	public void onTick() {
		if (mc.player == null) return;
		// Stand a step behind you and off to the right
		double rad = Math.toRadians(mc.player.getYRot());
		Vec3 forward = new Vec3(-Math.sin(rad), 0, Math.cos(rad));
		Vec3 right = new Vec3(-forward.z, 0, forward.x);
		Vec3 spot = mc.player.position().subtract(forward.scale(0.9)).subtract(right.scale(0.9));
		if (pos == null || pos.distanceTo(spot) > 16) {
			pos = prevPos = spot;
			yaw = prevYaw = facing(forward);
		}
		prevPos = pos;
		prevYaw = yaw;
		prevWalk = walk;

		Vec3 flat = new Vec3(spot.x - pos.x, 0, spot.z - pos.z);
		double distance = flat.length();
		double step = Math.max(0, distance - 0.15) * 0.3;
		Vec3 next = distance > 1e-4 ? pos.add(flat.normalize().scale(step)) : pos;
		// Keep his feet with yours up and down
		pos = new Vec3(next.x, Mth.lerp(0.4, pos.y, spot.y), next.z);
		moving = Mth.lerp(0.3f, moving, (float) Math.min(1, step * 6));
		walk += (float) step * 5;

		// Face where he walks, or where you face once he's caught up
		float want = step > 0.02 ? facing(flat) : facing(forward);
		yaw += Mth.wrapDegrees(want - yaw) * 0.35f;
	}

	@Override
	public void onPostAttack(Entity target) {
		swingStart = System.currentTimeMillis();
		if (pos == null) return;
		yaw = facing(new Vec3(target.getX() - pos.x, 0, target.getZ() - pos.z));
		if (bonk.get()) mc.level.playLocalSound(pos.x, pos.y + 1, pos.z, SoundEvents.ZOMBIE_ATTACK_WOODEN_DOOR, SoundSource.PLAYERS, 0.35f, 1.6f, false);
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (pos == null || prevPos == null) return;
		float tickDelta = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		Vec3 at = prevPos.lerp(pos, tickDelta);
		float walked = Mth.lerp(tickDelta, prevWalk, walk);
		// A little hop with every step
		at = at.add(0, Math.abs(Math.sin(walked)) * 0.06 * moving * size.getFloat(), 0);
		float arm = -4 + (float) Math.sin(walked) * 22 * moving;
		long since = System.currentTimeMillis() - swingStart;
		if (since < SWING) arm = TungSahurModule.chop(since / (float) SWING, arm);
		float turn = prevYaw + Mth.wrapDegrees(yaw - prevYaw) * tickDelta;
		TungSahurModule.drawSahur(event, at.subtract(event.camera().pos), (float) Math.toRadians(turn), 0, arm, size.getFloat() / 2.5f);
	}

	/** The yaw, in degrees, that turns his face (+Z) along {@code direction}. */
	private static float facing(Vec3 direction) {
		return (float) Math.toDegrees(Math.atan2(direction.x, direction.z));
	}
}
