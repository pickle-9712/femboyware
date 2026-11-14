package dev.femboyware.module.effects;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.FxRender;
import dev.femboyware.util.IsraelFlag;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * A blue and white Star of David on every hit: one flashing open and turning where the hit lands,
 * another spreading out across the ground under them, and little stars flying off them.
 */
public class StarHitsModule extends FxModule {
	private static final long LIFE = 900;

	private final BoolSetting playersOnly = add(new BoolSetting("Players Only", "Only on players, not mobs", false));
	private final NumberSetting scale = add(new NumberSetting("Scale", "How big the stars are", 1, 0.4, 2.5, 0.05));
	private final BoolSetting groundStar = add(new BoolSetting("Ground Star", "A star spreading out across the ground under them", true));
	private final NumberSetting miniStars = add(new NumberSetting("Mini Stars", "Little stars flying off them on each hit", 5, 0, 16, 1));
	private final BoolSetting sound = add(new BoolSetting("Sound", "A chime on each hit", false));
	private final BoolSetting throughWalls = add(new BoolSetting("Through Walls", "Draw over terrain", false));

	/** Where the hit landed, relative to their feet so it follows them, and the way you hit from. */
	private record Hit(LivingEntity target, Vec3 offset, float yaw, long start, long seed) {
	}

	private final List<Hit> hits = new ArrayList<>();

	public StarHitsModule() {
		super("Star Hits", "A blue and white Star of David flashing on every hit", Category.TARGETS);
	}

	@Override
	protected void onDisable() {
		hits.clear();
	}

	@Override
	public void onPostAttack(Entity entity) {
		if (!(entity instanceof LivingEntity target) || mc.player == null) return;
		if (playersOnly.get() && !(target instanceof Player)) return;
		Vec3 eye = mc.player.getEyePosition();
		Vec3 centre = target.getBoundingBox().getCenter();
		Vec3 impact = target.getBoundingBox().inflate(0.05).clip(eye, eye.add(mc.player.getViewVector(1).scale(8))).orElse(centre);
		float yaw = (float) Math.toDegrees(Math.atan2(centre.z - eye.z, centre.x - eye.x));
		hits.add(new Hit(target, impact.subtract(target.position()), yaw, System.currentTimeMillis(), System.nanoTime()));
		while (hits.size() > 24) hits.remove(0);
		if (sound.get()) {
			mc.level.playLocalSound(impact.x, impact.y, impact.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1, 1.2f + (float) Math.random() * 0.4f, false);
		}
	}

	private static float easeOut(float t) {
		t = Math.max(0, Math.min(1, t));
		return 1 - (1 - t) * (1 - t) * (1 - t);
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (hits.isEmpty() || mc.player == null) return;
		long now = System.currentTimeMillis();
		hits.removeIf(hit -> now - hit.start() > LIFE || hit.target().level() != mc.level);
		if (hits.isEmpty()) return;
		float tickDelta = tickDelta();
		Vec3 cam = event.camera().pos;
		boolean walls = throughWalls.get();
		float s = scale.getFloat();

		VertexConsumer light = FxRender.quads(event, true, walls);
		for (Hit hit : hits) {
			float age = (now - hit.start()) / (float) LIFE;
			Vec3 feet = hit.target().getPosition(tickDelta);
			flash(event, light, hit, feet, cam, age, s);
			if (groundStar.get()) ground(event, light, hit, feet, cam, age, s);
			if (miniStars.getInt() > 0) minis(event, light, hit, feet, cam, age, s);
		}
		VertexConsumer glow = FxRender.textured(event, FxRender.GLOW, true, walls);
		for (Hit hit : hits) {
			float age = (now - hit.start()) / (float) LIFE;
			Vec3 impact = hit.target().getPosition(tickDelta).add(hit.offset());
			float flash = Math.max(0, 1 - age * 3);
			FxRender.glow(event, glow, impact, (0.5f + 0.6f * easeOut(age * 4)) * s, FxRender.fade(0xFFFFFFFF, flash));
			FxRender.glow(event, glow, impact, 1.1f * s, FxRender.fade(IsraelFlag.LIGHT_BLUE, 0.6f * (1 - age) * (1 - age)));
		}
	}

	/** The big star facing back at you, popping open and turning a sixth of the way round as it fades. */
	private void flash(Render3DEvent event, VertexConsumer light, Hit hit, Vec3 feet, Vec3 cam, float age, float s) {
		float alpha = (1 - age) * (1 - age);
		Vec3 at = feet.add(hit.offset());
		PoseStack matrices = event.matrices();
		matrices.pushPose();
		matrices.translate((float) (at.x - cam.x), (float) (at.y - cam.y), (float) (at.z - cam.z));
		matrices.mulPose(Axis.YP.rotationDegrees(-hit.yaw() + 90));
		matrices.mulPose(Axis.ZP.rotationDegrees(60 * easeOut(age * 1.5f)));
		PoseStack.Pose pose = matrices.last();
		float r = (0.3f + 0.45f * easeOut(age * 3)) * s;
		IsraelFlag.starXY(light, pose, 0, 0, r, 0.09f * s, FxRender.fade(IsraelFlag.LIGHT_BLUE, alpha));
		IsraelFlag.starXY(light, pose, 0, 0, r, 0.03f * s, FxRender.fade(0xFFFFFFFF, alpha));
		// A ring round it that keeps going after the star stops
		float ring = r * (1.15f + 0.5f * easeOut(age * 2));
		for (int i = 0; i < 40; i++) {
			double a1 = Math.PI * 2 * i / 40, a2 = Math.PI * 2 * (i + 1) / 40;
			FxRender.strokeXY(light, pose, (float) Math.cos(a1) * ring, (float) Math.sin(a1) * ring, (float) Math.cos(a2) * ring, (float) Math.sin(a2) * ring,
				0.03f * s, FxRender.fade(0xFFFFFFFF, alpha * 0.7f), FxRender.fade(0xFFFFFFFF, alpha * 0.7f));
		}
		matrices.popPose();
	}

	/** A star on the ground under them, spreading out and fading. */
	private void ground(Render3DEvent event, VertexConsumer light, Hit hit, Vec3 feet, Vec3 cam, float age, float s) {
		float alpha = 1 - age;
		PoseStack matrices = event.matrices();
		matrices.pushPose();
		matrices.translate((float) (feet.x - cam.x), (float) (feet.y - cam.y) + 0.03f, (float) (feet.z - cam.z));
		matrices.mulPose(Axis.YP.rotationDegrees(-hit.yaw() + 90 - 30 * age));
		PoseStack.Pose pose = matrices.last();
		float r = (0.4f + 1.3f * easeOut(age * 1.6f)) * s;
		IsraelFlag.starXZ(light, pose, r, 0, 0.1f * s, FxRender.fade(IsraelFlag.LIGHT_BLUE, alpha * 0.9f));
		IsraelFlag.starXZ(light, pose, r, 0.002f, 0.035f * s, FxRender.fade(0xFFFFFFFF, alpha));
		FxRender.ring(light, pose, r * 1.08f, r * 1.13f, 0, 56, (a, inner) -> FxRender.fade(IsraelFlag.LIGHT_BLUE, alpha * 0.8f));
		matrices.popPose();
	}

	/** Little stars bursting off them, arcing out and falling, spinning as they go. */
	private void minis(Render3DEvent event, VertexConsumer light, Hit hit, Vec3 feet, Vec3 cam, float age, float s) {
		Random random = new Random(hit.seed());
		Vec3 impact = feet.add(hit.offset());
		float secs = age * LIFE / 1000f;
		PoseStack matrices = event.matrices();
		for (int i = 0; i < miniStars.getInt(); i++) {
			Vec3 v = new Vec3(random.nextGaussian(), 0.8 + random.nextFloat() * 1.2, random.nextGaussian()).normalize().scale((2 + random.nextFloat() * 2) * s);
			float spin = random.nextFloat() * 6, life = 0.6f + random.nextFloat() * 0.4f;
			if (age >= life) continue;
			Vec3 at = impact.add(v.x * secs, v.y * secs - 6 * secs * secs, v.z * secs);
			float alpha = 1 - age / life;
			matrices.pushPose();
			matrices.translate((float) (at.x - cam.x), (float) (at.y - cam.y), (float) (at.z - cam.z));
			matrices.mulPose(Axis.YP.rotationDegrees(-hit.yaw() + 90));
			matrices.mulPose(Axis.ZP.rotation(spin + secs * 8));
			PoseStack.Pose pose = matrices.last();
			int color = i % 2 == 0 ? IsraelFlag.LIGHT_BLUE : 0xFFFFFFFF;
			IsraelFlag.starXY(light, pose, 0, 0, 0.1f * s, 0.03f * s, FxRender.fade(color, alpha));
			matrices.popPose();
		}
	}
}
