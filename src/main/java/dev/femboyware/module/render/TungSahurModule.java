package dev.femboyware.module.render;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.RenderUtil;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;

/**
 * When someone dies, Tung Tung Tung Sahur appears next to them and bonks them on the head with a
 * bat before they fall over. Their death animation waits for the last bonk, then plays out in the
 * time left before the server removes the body. Client-side only.
 */
public class TungSahurModule extends Module {
	/** The server removes a dead body one second after death, so everything happens inside that. */
	private static final long BODY_LIFETIME = 1000;
	/** All the bonking fits in this; the fall-over gets the rest of the second. */
	private static final long BONKING_TIME = 650;
	/** Where in each swing the bat lands, from 0 to 1. */
	private static final float IMPACT = 0.55f;

	private static final int WOOD = 0xFF8B5A2B;
	private static final int WOOD_DARK = 0xFF6B4423;
	private static final int WOOD_RINGS = 0xFFC79A5B;
	private static final int BAT = 0xFFD9AF72;
	private static final int BAT_HANDLE = 0xFF9C7448;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int BLACK = 0xFF111111;

	/** One sahur visit: when the victim died and how many bonks have landed so far. */
	private static final class Visit {
		private final long start;
		private int bonks;

		private Visit(long start) {
			this.start = start;
		}
	}

	private final NumberSetting hits = add(new NumberSetting("Hits", "Bonks before they fall over", 3, 1, 5, 1));
	private final BoolSetting playersOnly = add(new BoolSetting("Players Only", "Only visit players, not mobs", true));
	private final BoolSetting sounds = add(new BoolSetting("Sounds", "A wooden TUNG on every bonk", true));
	private final BoolSetting words = add(new BoolSetting("Words", "Show TUNG and SAHUR! above their head", true));
	private final NumberSetting scale = add(new NumberSetting("Scale", "How big Tung Tung Tung Sahur is", 1, 0.5, 2, 0.05));
	private final NumberSetting range = add(new NumberSetting("Range", "Only visit deaths within this many blocks", 64, 8, 256, 8));

	private final Map<Integer, Visit> visits = new HashMap<>();

	public TungSahurModule() {
		super("Tung Sahur", "Tung Tung Tung Sahur bonks whoever just died", Category.COSMETIC);
	}

	@Override
	public String getInfo() {
		return Integer.toString(visits.size());
	}

	@Override
	protected void onDisable() {
		visits.clear();
	}

	@Override
	public void onTick() {
		long now = System.currentTimeMillis();
		visits.entrySet().removeIf(entry -> now - entry.getValue().start > BODY_LIFETIME * 2);
		for (Entity entity : mc.world.getEntities()) {
			if (!(entity instanceof LivingEntity living) || entity == mc.player || !living.isDead()) continue;
			if (playersOnly.get() && !(entity instanceof PlayerEntity)) continue;
			if (mc.player.distanceTo(entity) > range.get()) continue;
			visits.putIfAbsent(entity.getId(), new Visit(now));
		}
		// One sound per bonk as each one lands
		for (Map.Entry<Integer, Visit> entry : visits.entrySet()) {
			Visit visit = entry.getValue();
			Entity victim = mc.world.getEntityById(entry.getKey());
			if (victim == null) continue;
			while (visit.bonks < hits.getInt() && now - visit.start >= bonkTime(visit.bonks)) {
				visit.bonks++;
				if (sounds.get()) {
					mc.world.playSoundClient(victim.getX(), victim.getY() + victim.getHeight(), victim.getZ(), SoundEvents.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR,
						SoundCategory.PLAYERS, 0.8f, 1.3f + visit.bonks * 0.1f, false);
				}
			}
		}
	}

	/** When bonk {@code index} (from 0) lands, in milliseconds after the death. */
	private long bonkTime(int index) {
		return (long) ((index + IMPACT) * swingTime());
	}

	private float swingTime() {
		return (float) BONKING_TIME / hits.getInt();
	}

	/**
	 * Called from LivingEntityRendererMixin: the death animation progress to draw for {@code entity}.
	 * It stays at 0 while sahur is bonking, then the fall plays out in the time that's left.
	 */
	public float deathTime(LivingEntity entity, float vanilla) {
		if (!isEnabled()) return vanilla;
		Visit visit = visits.get(entity.getId());
		if (visit == null) return vanilla;
		long elapsed = System.currentTimeMillis() - visit.start;
		if (elapsed < BONKING_TIME) return 0;
		return Math.min(20f, (elapsed - BONKING_TIME) * 20f / (BODY_LIFETIME - BONKING_TIME));
	}

	public int visitCount() {
		return visits.size();
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		long now = System.currentTimeMillis();
		float tickDelta = mc.getRenderTickCounter().getTickProgress(true);
		Vec3d camera = event.camera().pos;
		for (Map.Entry<Integer, Visit> entry : visits.entrySet()) {
			Entity victim = mc.world.getEntityById(entry.getKey());
			if (victim == null) continue;
			long elapsed = now - entry.getValue().start;
			if (elapsed > BODY_LIFETIME) continue;
			Vec3d victimPos = victim.getLerpedPos(tickDelta);

			// Stand beside them and a little behind, so he faces the camera three-quarters on while bonking
			Vec3d toCamera = camera.subtract(victimPos).multiply(1, 0, 1);
			if (toCamera.lengthSquared() < 1e-4) toCamera = new Vec3d(0, 0, 1);
			toCamera = toCamera.normalize();
			Vec3d side = new Vec3d(-toCamera.z, 0, toCamera.x);
			Vec3d sahur = victimPos.add(side.multiply(1.0)).subtract(toCamera.multiply(0.6));
			Vec3d facing = victimPos.subtract(sahur);
			float yaw = (float) Math.atan2(facing.x, facing.z);

			float pop = MathHelper.clamp(elapsed / 120f, 0, 1);
			float size = scale.getFloat() * (pop < 1 ? pop * (1.15f - 0.15f * pop) : 1);
			drawSahur(event, sahur.subtract(camera), yaw, armAngle(elapsed), size);

			if (words.get()) drawWords(event, entry.getValue(), elapsed, victimPos.add(0, victim.getHeight() + 0.4, 0));
		}
	}

	/** The bat arm's angle: wind up behind, swing down onto their head, settle. Degrees about the shoulder. */
	private float armAngle(long elapsed) {
		if (elapsed >= BONKING_TIME) return -20;
		float swing = swingTime();
		float phase = (elapsed % (long) swing) / swing;
		if (phase < 0.4f) return MathHelper.lerp(ease(phase / 0.4f), -20f, 135f);
		if (phase < IMPACT) return MathHelper.lerp(ease((phase - 0.4f) / (IMPACT - 0.4f)), 135f, -95f);
		return MathHelper.lerp(ease((phase - IMPACT) / (1 - IMPACT)), -95f, -20f);
	}

	private static float ease(float t) {
		return t * t * (3 - 2 * t);
	}

	private void drawWords(Render3DEvent event, Visit visit, long elapsed, Vec3d head) {
		for (int i = 0; i < visit.bonks; i++) {
			long since = elapsed - bonkTime(i);
			if (since < 0 || since > 350) continue;
			boolean last = i == hits.getInt() - 1;
			String word = last ? "SAHUR!" : "TUNG";
			int alpha = (int) (255 * (1 - since / 350f));
			int color = (Math.max(alpha, 4) << 24) | (last ? 0xFF77C8 : 0xFFE0B0);
			RenderUtil.drawText(event, word, head.add(0, since / 700.0, 0), color, last ? 0.75f : 0.55f);
		}
	}

	// ---------------------------------------------------------------- model

	/** Tung Tung Tung Sahur: a wooden log with big eyes, little legs, and a bat in his right hand. */
	private void drawSahur(Render3DEvent event, Vec3d at, float yaw, float armAngle, float size) {
		MatrixStack matrices = event.matrices();
		VertexConsumer quads = event.consumers().getBuffer(RenderLayers.debugQuads());
		matrices.push();
		matrices.translate(at.x, at.y, at.z);
		matrices.multiply(RotationAxis.POSITIVE_Y.rotation(yaw));
		matrices.scale(size, size, size);
		MatrixStack.Entry entry = matrices.peek();

		// Legs, log body with rings on top, and the left arm hanging
		box(quads, entry, -0.25f, 0, -0.1f, -0.05f, 0.45f, 0.1f, WOOD_DARK);
		box(quads, entry, 0.05f, 0, -0.1f, 0.25f, 0.45f, 0.1f, WOOD_DARK);
		box(quads, entry, -0.32f, 0.45f, -0.32f, 0.32f, 1.75f, 0.32f, WOOD);
		box(quads, entry, -0.29f, 1.75f, -0.29f, 0.29f, 1.79f, 0.29f, WOOD_RINGS);
		box(quads, entry, -0.42f, 0.8f, -0.05f, -0.32f, 1.4f, 0.05f, WOOD_DARK);

		// Face on the front: wide eyes with pupils, angry brows, a flat mouth
		box(quads, entry, -0.23f, 1.3f, 0.32f, -0.04f, 1.52f, 0.33f, WHITE);
		box(quads, entry, 0.04f, 1.3f, 0.32f, 0.23f, 1.52f, 0.33f, WHITE);
		box(quads, entry, -0.16f, 1.35f, 0.33f, -0.09f, 1.45f, 0.34f, BLACK);
		box(quads, entry, 0.09f, 1.35f, 0.33f, 0.16f, 1.45f, 0.34f, BLACK);
		box(quads, entry, -0.25f, 1.55f, 0.32f, -0.03f, 1.59f, 0.335f, BLACK);
		box(quads, entry, 0.03f, 1.55f, 0.32f, 0.25f, 1.59f, 0.335f, BLACK);
		box(quads, entry, -0.13f, 1.08f, 0.32f, 0.13f, 1.12f, 0.335f, BLACK);

		// Right arm and bat, swung from the shoulder
		matrices.push();
		matrices.translate(0.37f, 1.38f, 0);
		matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(armAngle));
		MatrixStack.Entry arm = matrices.peek();
		box(quads, arm, -0.05f, -0.55f, -0.05f, 0.05f, 0, 0.05f, WOOD_DARK);
		box(quads, arm, -0.035f, -0.85f, -0.035f, 0.035f, -0.5f, 0.035f, BAT_HANDLE);
		box(quads, arm, -0.06f, -1.35f, -0.06f, 0.06f, -0.85f, 0.06f, BAT);
		matrices.pop();

		matrices.pop();
	}

	/** A solid box with each side shaded a little differently so it reads as 3D. */
	private static void box(VertexConsumer quads, MatrixStack.Entry entry, float x1, float y1, float z1, float x2, float y2, float z2, int color) {
		int top = color;
		int front = shade(color, 0.85f);
		int side = shade(color, 0.7f);
		int bottom = shade(color, 0.5f);
		quad(quads, entry, top, x1, y2, z1, x1, y2, z2, x2, y2, z2, x2, y2, z1);
		quad(quads, entry, bottom, x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2);
		quad(quads, entry, front, x1, y1, z2, x2, y1, z2, x2, y2, z2, x1, y2, z2);
		quad(quads, entry, front, x1, y1, z1, x1, y2, z1, x2, y2, z1, x2, y1, z1);
		quad(quads, entry, side, x1, y1, z1, x1, y1, z2, x1, y2, z2, x1, y2, z1);
		quad(quads, entry, side, x2, y1, z1, x2, y2, z1, x2, y2, z2, x2, y1, z2);
	}

	private static void quad(VertexConsumer quads, MatrixStack.Entry entry, int color,
							 float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz, float dx, float dy, float dz) {
		quads.vertex(entry, ax, ay, az).color(color);
		quads.vertex(entry, bx, by, bz).color(color);
		quads.vertex(entry, cx, cy, cz).color(color);
		quads.vertex(entry, dx, dy, dz).color(color);
	}

	private static int shade(int color, float factor) {
		int r = (int) (((color >> 16) & 0xFF) * factor);
		int g = (int) (((color >> 8) & 0xFF) * factor);
		int b = (int) ((color & 0xFF) * factor);
		return (color & 0xFF000000) | (r << 16) | (g << 8) | b;
	}
}
