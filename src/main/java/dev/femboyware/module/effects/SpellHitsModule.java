package dev.femboyware.module.effects;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.Setting;
import dev.femboyware.util.FxRender;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Spell effects every time you hit something: a rune sigil bursting open where the hit landed,
 * soul wisps ripped out of them and flying back to you, phantom blades plunging down into them, a
 * shockwave rolling out across the ground, and shattering sparks. Each is a layer you can turn on
 * or off, and they share one palette.
 */
public class SpellHitsModule extends FxModule {
	private static final long LIFE = 1100;

	private <T, S extends Setting<T>> S under(BoolSetting layer, S setting) {
		add(setting);
		return setting.visibleWhen(layer::get);
	}

	private final BoolSetting playersOnly = add(new BoolSetting("Players Only", "Only on players, not mobs", false));
	private final NumberSetting scale = add(new NumberSetting("Scale", "How big the effects are", 1, 0.4, 2.5, 0.05));
	private final BoolSetting throughWalls = add(new BoolSetting("Through Walls", "Draw over terrain", false));

	private final BoolSetting sigil = add(new BoolSetting("Sigil Burst", "A rune circle bursting open where your hit lands", true));
	private final NumberSetting sigilRunes = under(sigil, new NumberSetting("Sigil Runes", "How many runes round the circle", 6, 0, 12, 1));

	private final BoolSetting soulRip = add(new BoolSetting("Soul Rip", "Wisps torn out of them, flying back into you", true));
	private final NumberSetting souls = under(soulRip, new NumberSetting("Souls", "How many wisps per hit", 4, 1, 12, 1));

	private final BoolSetting blades = add(new BoolSetting("Phantom Blades", "Spectral swords plunging down into them", false));
	private final NumberSetting bladeCount = under(blades, new NumberSetting("Blades", "How many swords per hit", 3, 1, 8, 1));

	private final BoolSetting shockwave = add(new BoolSetting("Shockwave", "A ring rolling out across the ground, with a pillar of light", true));
	private final NumberSetting waveRadius = under(shockwave, new NumberSetting("Wave Radius", "How far the ring rolls out", 2.2, 0.8, 5, 0.1));
	private final BoolSetting pillar = under(shockwave, new BoolSetting("Light Pillar", "A beam of light going up through them", true));

	private final BoolSetting sparks = add(new BoolSetting("Shatter Sparks", "Shards bursting off them and falling away", true));
	private final NumberSetting sparkCount = under(sparks, new NumberSetting("Sparks", "How many shards per hit", 14, 4, 40, 1));

	private final Palette palette = palette(0xFFB98AFF, 0xFF7FE8FF);

	/** Where the hit landed, relative to their feet, so effects follow them; and where you hit from. */
	private record Hit(LivingEntity target, Vec3 offset, float yaw, long start, long seed) {
	}

	private final List<Hit> hits = new ArrayList<>();

	public SpellHitsModule() {
		super("Spell Hits", "Rune sigils, soul rips, phantom blades, shockwaves and sparks when you hit", Category.TARGETS);
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
		// The point on their hitbox your crosshair was on, or their middle
		Vec3 impact = target.getBoundingBox().inflate(0.05).clip(eye, eye.add(mc.player.getViewVector(1).scale(8))).orElse(centre);
		float yaw = (float) Math.toDegrees(Math.atan2(centre.z - eye.z, centre.x - eye.x));
		hits.add(new Hit(target, impact.subtract(target.position()), yaw, System.currentTimeMillis(), System.nanoTime()));
		while (hits.size() > 24) hits.remove(0);
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (hits.isEmpty() || mc.player == null) return;
		long now = System.currentTimeMillis();
		Iterator<Hit> it = hits.iterator();
		while (it.hasNext()) {
			Hit hit = it.next();
			if (now - hit.start() > LIFE || hit.target().level() != mc.level) it.remove();
		}
		if (hits.isEmpty()) return;
		float tickDelta = tickDelta();
		Vec3 cam = event.camera().pos;
		boolean walls = throughWalls.get();

		VertexConsumer light = FxRender.quads(event, true, walls);
		for (Hit hit : hits) {
			float age = (now - hit.start()) / (float) LIFE;
			Vec3 feet = hit.target().getPosition(tickDelta);
			if (shockwave.get()) shockwave(event, light, hit, feet, cam, age);
			if (blades.get()) blades(event, light, hit, feet, cam, age);
			if (sigil.get()) sigil(event, light, hit, feet, cam, age);
		}
		VertexConsumer glow = FxRender.textured(event, FxRender.GLOW, true, walls);
		for (Hit hit : hits) {
			float age = (now - hit.start()) / (float) LIFE;
			Vec3 feet = hit.target().getPosition(tickDelta);
			Vec3 impact = feet.add(hit.offset());
			// A flash where it landed
			if (age < 0.25f) FxRender.glow(event, glow, impact, 0.6f * scale.getFloat() * (1 - age * 2), FxRender.fade(FxRender.lighten(palette.at(0), 0.6f), 1 - age * 4));
			if (soulRip.get()) soulRip(event, glow, hit, impact, tickDelta, age);
			if (sparks.get()) sparks(event, glow, hit, impact, age);
		}
	}

	private static float easeOut(float t) {
		return 1 - (1 - t) * (1 - t) * (1 - t);
	}

	/** A rune circle facing you at the impact point, flaring open and turning as it fades. */
	private void sigil(Render3DEvent event, VertexConsumer light, Hit hit, Vec3 feet, Vec3 cam, float age) {
		float open = easeOut(Math.min(1, age / 0.55f));
		float alpha = 1 - age;
		alpha *= alpha;
		if (alpha <= 0.01f) return;
		Vec3 at = feet.add(hit.offset());
		PoseStack matrices = event.matrices();
		matrices.pushPose();
		matrices.translate((float) (at.x - cam.x), (float) (at.y - cam.y), (float) (at.z - cam.z));
		// Turn the XY plane to face back along the line you hit along
		matrices.mulPose(Axis.YP.rotationDegrees(-hit.yaw() + 90));
		matrices.mulPose(Axis.ZP.rotationDegrees(age * 120 + hit.seed() % 360));
		float r = (0.25f + 0.65f * open) * scale.getFloat();
		matrices.scale(r, r, r);
		PoseStack.Pose pose = matrices.last();
		int c1 = FxRender.fade(palette.at(0), alpha), c2 = FxRender.fade(palette.at(0.5f), alpha);
		circleXY(light, pose, 1, 0.035f, 48, c1, c2);
		circleXY(light, pose, 0.72f, 0.025f, 40, c2, c1);
		// A six-pointed star between the rings
		for (int k = 0; k < 2; k++) {
			for (int i = 0; i < 3; i++) {
				double a1 = Math.PI * 2 * i / 3 + k * Math.PI / 3 + Math.PI / 2, a2 = a1 + Math.PI * 2 / 3;
				FxRender.strokeXY(light, pose, (float) Math.cos(a1) * 0.72f, (float) Math.sin(a1) * 0.72f,
					(float) Math.cos(a2) * 0.72f, (float) Math.sin(a2) * 0.72f, 0.022f, c1, c2);
			}
		}
		int n = sigilRunes.getInt();
		for (int i = 0; i < n; i++) {
			double a = Math.PI * 2 * i / n;
			float cx = (float) Math.cos(a) * 0.86f, cy = (float) Math.sin(a) * 0.86f;
			float[] rune = SpectralAuraModule.RUNES[(int) ((hit.seed() + i) % SpectralAuraModule.RUNES.length + SpectralAuraModule.RUNES.length) % SpectralAuraModule.RUNES.length];
			float s = 0.075f;
			int c = FxRender.fade(palette.at(i / (float) n), alpha);
			for (int k = 0; k + 3 < rune.length; k += 4) {
				FxRender.strokeXY(light, pose, cx + rune[k] * s, cy + rune[k + 1] * s, cx + rune[k + 2] * s, cy + rune[k + 3] * s, 0.018f, c, c);
			}
		}
		matrices.popPose();
	}

	private static void circleXY(VertexConsumer light, PoseStack.Pose pose, float r, float width, int segments, int c1, int c2) {
		for (int i = 0; i < segments; i++) {
			double a1 = Math.PI * 2 * i / segments, a2 = Math.PI * 2 * (i + 1) / segments;
			int c = (i & 1) == 0 ? c1 : c2;
			FxRender.strokeXY(light, pose, (float) Math.cos(a1) * r, (float) Math.sin(a1) * r, (float) Math.cos(a2) * r, (float) Math.sin(a2) * r, width, c, c);
		}
	}

	/** Wisps leaving their body, curving out and homing in on your chest. */
	private void soulRip(Render3DEvent event, VertexConsumer glow, Hit hit, Vec3 impact, float tickDelta, float age) {
		Vec3 you = mc.player.getPosition(tickDelta).add(0, mc.player.getBbHeight() * 0.6, 0);
		Random random = new Random(hit.seed());
		int n = souls.getInt();
		for (int i = 0; i < n; i++) {
			float delay = random.nextFloat() * 0.2f;
			Vec3 bend = new Vec3(random.nextGaussian(), 0.6 + random.nextFloat(), random.nextGaussian()).scale(1.2 * scale.get());
			int color = palette.at(i / (float) n);
			for (int k = 0; k < 8; k++) {
				float t = (age - delay - k * 0.018f) / 0.6f;
				if (t <= 0 || t >= 1) continue;
				float e = t * t * (3 - 2 * t);
				// A quadratic curve from them, bowing out, into you
				Vec3 mid = impact.add(you).scale(0.5).add(bend);
				Vec3 a = impact.lerp(mid, e), b = mid.lerp(you, e);
				Vec3 at = a.lerp(b, e);
				float fade = (1 - k / 8f) * (float) Math.sin(t * Math.PI);
				FxRender.glow(event, glow, at, (k == 0 ? 0.16f : 0.1f) * scale.getFloat(), FxRender.fade(k == 0 ? FxRender.lighten(color, 0.5f) : color, fade));
			}
		}
	}

	/** Swords dropping from above and sinking into them, then fading where they stuck. */
	private void blades(Render3DEvent event, VertexConsumer light, Hit hit, Vec3 feet, Vec3 cam, float age) {
		Random random = new Random(hit.seed() * 31);
		int n = bladeCount.getInt();
		float h = hit.target().getBbHeight();
		float s = scale.getFloat();
		PoseStack matrices = event.matrices();
		for (int i = 0; i < n; i++) {
			float delay = i * 0.06f;
			float t = Math.max(0, age - delay);
			float drop = easeOut(Math.min(1, t / 0.22f));
			float alpha = t <= 0 ? 0 : Math.min(1, t * 10) * (1 - Math.max(0, (t - 0.5f) / 0.45f));
			if (alpha <= 0.01f) continue;
			double a = random.nextDouble() * Math.PI * 2;
			float lean = 12 + random.nextFloat() * 18;
			float off = 0.25f + random.nextFloat() * 0.2f;
			float tipY = h * (0.35f + random.nextFloat() * 0.35f);
			float startY = tipY + 2.2f * s;
			float y = startY + (tipY - startY) * drop;
			matrices.pushPose();
			matrices.translate((float) (feet.x - cam.x + Math.cos(a) * off * (1 - drop * 0.5)), (float) (feet.y - cam.y) + y, (float) (feet.z - cam.z + Math.sin(a) * off * (1 - drop * 0.5)));
			matrices.mulPose(Axis.YP.rotationDegrees((float) Math.toDegrees(-a)));
			matrices.mulPose(Axis.ZP.rotationDegrees(lean));
			matrices.scale(s, s, s);
			int color = palette.at(i / (float) n);
			// Two crossed planes so it reads from any side
			for (int plane = 0; plane < 2; plane++) {
				matrices.pushPose();
				matrices.mulPose(Axis.YP.rotationDegrees(plane * 90));
				sword(light, matrices.last(), color, alpha);
				matrices.popPose();
			}
			matrices.popPose();
		}
	}

	/** A sword in the XY plane with its tip at the origin, pointing down. */
	private static void sword(VertexConsumer light, PoseStack.Pose pose, int color, float alpha) {
		int edge = FxRender.fade(color, alpha * 0.85f), core = FxRender.fade(FxRender.lighten(color, 0.6f), alpha);
		float len = 1.1f, w = 0.075f;
		// Blade: a long diamond, brighter down the middle
		FxRender.quad(light, pose, 0, 0, 0, core, w, 0.18f, 0, edge, w, len, 0, edge, 0, len, 0, core);
		FxRender.quad(light, pose, 0, 0, 0, core, 0, len, 0, core, -w, len, 0, edge, -w, 0.18f, 0, edge);
		// Crossguard, grip and pommel
		FxRender.quad(light, pose, -0.24f, len, 0, edge, 0.24f, len, 0, edge, 0.24f, len + 0.05f, 0, edge, -0.24f, len + 0.05f, 0, edge);
		FxRender.quad(light, pose, -0.03f, len + 0.05f, 0, edge, 0.03f, len + 0.05f, 0, edge, 0.03f, len + 0.3f, 0, edge, -0.03f, len + 0.3f, 0, edge);
		FxRender.quad(light, pose, -0.055f, len + 0.3f, 0, core, 0.055f, len + 0.3f, 0, core, 0.055f, len + 0.37f, 0, core, -0.055f, len + 0.37f, 0, core);
	}

	/** A ring across the ground at their feet rolling outwards, and a pillar of light through them. */
	private void shockwave(Render3DEvent event, VertexConsumer light, Hit hit, Vec3 feet, Vec3 cam, float age) {
		float t = Math.min(1, age / 0.7f);
		float alpha = (1 - t) * (1 - t);
		if (alpha <= 0.01f) return;
		PoseStack matrices = event.matrices();
		matrices.pushPose();
		matrices.translate((float) (feet.x - cam.x), (float) (feet.y - cam.y) + 0.03f, (float) (feet.z - cam.z));
		PoseStack.Pose pose = matrices.last();
		float r = waveRadius.getFloat() * scale.getFloat() * easeOut(t);
		float width = 0.12f + 0.3f * (1 - t);
		FxRender.ring(light, pose, Math.max(0, r - width), r, 0, 56,
			(a, inner) -> FxRender.fade(palette.at(a), inner ? 0 : alpha * 0.9f));
		FxRender.ring(light, pose, Math.max(0, r * 0.6f - 0.04f), r * 0.6f, 0, 48, (a, inner) -> FxRender.fade(palette.at(a + 0.5f), alpha * 0.5f));
		if (pillar.get()) {
			float h = hit.target().getBbHeight() * 2.4f, pw = 0.32f * scale.getFloat() * (1 - t * 0.6f);
			int base = FxRender.fade(FxRender.lighten(palette.at(0), 0.4f), alpha * 0.7f), top = FxRender.fade(palette.at(0.5f), 0);
			for (int k = 0; k < 2; k++) {
				float dx = k == 0 ? pw : 0, dz = k == 0 ? 0 : pw;
				FxRender.quad(light, pose, -dx, 0, -dz, base, dx, 0, dz, base, dx, h, dz, top, -dx, h, -dz, top);
			}
		}
		matrices.popPose();
	}

	/** Shards bursting out of the impact, arcing and falling under gravity. */
	private void sparks(Render3DEvent event, VertexConsumer glow, Hit hit, Vec3 impact, float age) {
		Random random = new Random(hit.seed() * 17);
		int n = sparkCount.getInt();
		float secs = age * LIFE / 1000f;
		double s = scale.get();
		for (int i = 0; i < n; i++) {
			Vec3 v = new Vec3(random.nextGaussian(), 0.8 + random.nextFloat() * 1.4, random.nextGaussian()).normalize().scale((2.5 + random.nextFloat() * 3) * s);
			float life = 0.5f + random.nextFloat() * 0.5f;
			if (age >= life) continue;
			Vec3 at = impact.add(v.x * secs, v.y * secs - 9 * secs * secs, v.z * secs);
			float fade = 1 - age / life;
			int color = palette.at(random.nextFloat());
			FxRender.glow(event, glow, at, (0.05f + 0.06f * fade) * (float) s, FxRender.fade(FxRender.lighten(color, 0.3f), fade));
		}
	}
}
