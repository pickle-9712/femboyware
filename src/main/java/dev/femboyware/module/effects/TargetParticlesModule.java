package dev.femboyware.module.effects;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.Setting;
import dev.femboyware.util.FxRender;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Everything drawn on whoever you're fighting, in one module: a glowing aura, astral stars, a
 * galaxy under their feet, swirling orbs, a gliding ring and a marker over their head. Each is a
 * layer you switch on or off, and its settings only show while it's on. They share one palette.
 */
public class TargetParticlesModule extends TargetFxModule {
	public enum AuraStyle { SHELL, FLAME, PILLARS, VOID, HOLY }

	public enum SwirlMode { HELIX, TORNADO, ATOM, RINGS, SPIRITS }

	public enum MarkerMode { CRYSTAL, RETICLE, ARROW }

	private static final int AURA_SEGMENTS = 36, RING_SEGMENTS = 56;

	/** Adds a setting that only shows while {@code layer} is on. */
	private <T, S extends Setting<T>> S under(BoolSetting layer, S setting) {
		add(setting);
		return setting.visibleWhen(layer::get);
	}

	// ---------------------------------------------------------------- aura
	private final BoolSetting highlight = add(new BoolSetting("White Highlight", "Bright white light round them: edges up their sides, a spinning ring, sparkles and a flash on every hit", true));
	private final ColorSetting highlightColor = under(highlight, new ColorSetting("Highlight Color", "The highlight's main colour", 0xFFFFFFFF));
	private final ColorSetting highlightRim = under(highlight, new ColorSetting("Highlight Rim", "The colour round its edges", 0xFFC8E4FF));
	private final NumberSetting highlightIntensity = under(highlight, new NumberSetting("Highlight Intensity", "How bright it is", 1, 0.2, 2, 0.05));

	private final BoolSetting aura = add(new BoolSetting("Glow Aura", "A shell of light rising off them, with a glow on the ground and motes drifting up", true));
	private final EnumSetting<AuraStyle> auraStyle = under(aura, new EnumSetting<>("Aura Style",
		"Shell: a smooth glow. Flame: licking tongues. Pillars: separate beams. Void: dark smoke with a burning rim. Holy: rays of light and a halo", AuraStyle.SHELL));
	private final NumberSetting auraHeight = under(aura, new NumberSetting("Aura Height", "How far up the aura rises, times their height", 1.15, 0.4, 2, 0.05));
	private final NumberSetting auraRadius = under(aura, new NumberSetting("Aura Radius", "How far out it stands from them", 0.55, 0.3, 1.5, 0.05));
	private final NumberSetting auraIntensity = under(aura, new NumberSetting("Aura Intensity", "How bright it is", 0.6, 0.1, 1, 0.05));
	private final NumberSetting auraSpeed = under(aura, new NumberSetting("Aura Speed", "How fast it shimmers", 1, 0.2, 3, 0.05));
	private final BoolSetting ground = under(aura, new BoolSetting("Ground Glow", "A pool of light on the ground under them", true));
	private final BoolSetting motes = under(aura, new BoolSetting("Motes", "Specks of light drifting up (or sinking, for Void)", true));

	// ---------------------------------------------------------------- astral
	private final BoolSetting astral = add(new BoolSetting("Astral", "Twinkling stars round them, joined into constellations, with stardust rising", true));
	private final NumberSetting stars = under(astral, new NumberSetting("Stars", "How many stars", 22, 6, 60, 1));
	private final NumberSetting astralRadius = under(astral, new NumberSetting("Astral Radius", "How far out the stars sit", 1, 0.4, 2.5, 0.05));
	private final NumberSetting astralSpeed = under(astral, new NumberSetting("Astral Speed", "How fast the stars drift round", 1, 0, 4, 0.05));
	private final NumberSetting starSize = under(astral, new NumberSetting("Star Size", "How big each star is", 1, 0.3, 3, 0.05));
	private final BoolSetting constellations = under(astral, new BoolSetting("Constellations", "Faint lines joining nearby stars", true));
	private final BoolSetting stardust = under(astral, new BoolSetting("Stardust", "Fine dust spiralling up through them", true));

	// ---------------------------------------------------------------- galaxy
	private final BoolSetting galaxy = add(new BoolSetting("Galaxy", "A spiral galaxy slowly turning under their feet", false));
	private final NumberSetting galaxySize = under(galaxy, new NumberSetting("Galaxy Size", "How wide the galaxy is", 1.4, 0.5, 4, 0.05));
	private final NumberSetting galaxySpeed = under(galaxy, new NumberSetting("Galaxy Speed", "How fast it turns", 1, 0, 4, 0.05));
	private final NumberSetting arms = under(galaxy, new NumberSetting("Arms", "How many spiral arms", 3, 2, 6, 1));
	private final NumberSetting galaxyHeight = under(galaxy, new NumberSetting("Galaxy Height", "Where it sits: 0 at their feet, 1 at their head", 0, 0, 1, 0.05));

	// ---------------------------------------------------------------- swirl
	private final BoolSetting swirl = add(new BoolSetting("Swirl", "Glowing orbs with comet tails swirling round them", false));
	private final EnumSetting<SwirlMode> swirlMode = under(swirl, new EnumSetting<>("Swirl Mode",
		"Helix: a DNA spiral. Tornado: a widening funnel. Atom: tilted orbits. Rings: stacked orbits. Spirits: wandering wisps", SwirlMode.HELIX));
	private final NumberSetting strands = under(swirl, new NumberSetting("Strands", "How many orbs swirl", 3, 1, 8, 1));
	private final NumberSetting swirlSpeed = under(swirl, new NumberSetting("Swirl Speed", "How fast they swirl", 1, 0.1, 4, 0.05));
	private final NumberSetting swirlRadius = under(swirl, new NumberSetting("Swirl Radius", "How far out from the body", 1, 0.4, 2.5, 0.05));
	private final NumberSetting trail = under(swirl, new NumberSetting("Trail", "Length of each tail", 18, 1, 50, 1));
	private final NumberSetting orbSize = under(swirl, new NumberSetting("Orb Size", "Size of each orb", 0.16, 0.03, 0.5, 0.01));
	private final BoolSetting orbGlow = under(swirl, new BoolSetting("Orb Glow", "A soft halo of light round each orb", true));

	// ---------------------------------------------------------------- ring
	private final BoolSetting ring = add(new BoolSetting("Ring", "A glowing ring gliding up and down them, trailing a skirt of light", false));
	private final NumberSetting ringSpeed = under(ring, new NumberSetting("Ring Speed", "How fast the ring travels", 1, 0.1, 4, 0.05));
	private final NumberSetting ringRadius = under(ring, new NumberSetting("Ring Radius", "How far out from the body", 1, 0.5, 2.5, 0.05));
	private final NumberSetting trailHeight = under(ring, new NumberSetting("Trail Height", "How tall the fading skirt behind the ring is", 0.45, 0, 1.2, 0.05));
	private final NumberSetting thickness = under(ring, new NumberSetting("Ring Thickness", "How thick the bright band is", 0.03, 0.005, 0.15, 0.005));
	private final NumberSetting opacity = under(ring, new NumberSetting("Skirt Opacity", "How strong the skirt is", 0.55, 0.05, 1, 0.05));
	private final BoolSetting ringGlow = under(ring, new BoolSetting("Ring Glow", "Add the ring as light, so it shines", true));

	// ---------------------------------------------------------------- marker
	private final BoolSetting marker = add(new BoolSetting("Marker", "A spinning crystal, lock-on reticle or arrow over them", false));
	private final EnumSetting<MarkerMode> markerMode = under(marker, new EnumSetting<>("Marker Mode",
		"Crystal: a spinning gem over their head. Reticle: lock-on brackets round them. Arrow: points down at them", MarkerMode.CRYSTAL));
	private final NumberSetting markerSize = under(marker, new NumberSetting("Marker Size", "How big the marker is", 1, 0.4, 2.5, 0.05));
	private final NumberSetting spin = under(marker, new NumberSetting("Marker Spin", "How fast it turns", 1, 0, 4, 0.05));
	private final BoolSetting markerGlow = under(marker, new BoolSetting("Marker Glow", "A soft light behind the marker", true));

	private final Palette palette = palette(0xFFFF4FD8, 0xFF7A5CFF);
	private final BoolSetting throughWalls = targetSettings();

	public TargetParticlesModule() {
		super("Target Particles", "Auras, astral stars, galaxies, orbs, rings and markers on whoever you're fighting");
	}

	@Override
	public String getInfo() {
		int on = 0;
		for (BoolSetting layer : new BoolSetting[]{highlight, aura, astral, galaxy, swirl, ring, marker}) if (layer.get()) on++;
		return on == 1 && aura.get() ? auraStyle.getDisplayName() : on + (on == 1 ? " layer" : " layers");
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		Map<LivingEntity, Float> targets = fadedTargets();
		if (targets.isEmpty()) return;
		if (galaxy.get()) galaxy(event, targets);
		if (aura.get()) aura(event, targets);
		if (ring.get()) {
			// The skirt is always blended normally; the band may be added as light, a different layer
			ring(event, targets, true);
			ring(event, targets, false);
		}
		if (swirl.get()) swirl(event, targets);
		if (astral.get()) astral(event, targets);
		if (marker.get()) marker(event, targets);
		if (highlight.get()) highlight(event, targets);
	}

	// ================================================================ white highlight

	private LivingEntity lastHit;
	private long lastHitTime;

	@Override
	public void onPostAttack(net.minecraft.world.entity.Entity target) {
		super.onPostAttack(target);
		if (target instanceof LivingEntity living) {
			lastHit = living;
			lastHitTime = System.currentTimeMillis();
		}
	}

	/** White light up the corners of their hitbox, a turning ring at their feet, sparkles rising, a soft glow, and a flash when hit. */
	private void highlight(Render3DEvent event, Map<LivingEntity, Float> targets) {
		float tickDelta = tickDelta();
		Vec3 cam = event.camera().pos;
		boolean walls = throughWalls.get();
		double t = time();
		int core = highlightColor.get(), rim = highlightRim.get();
		float power = highlightIntensity.getFloat();
		VertexConsumer light = FxRender.quads(event, true, walls);
		PoseStack matrices = event.matrices();
		for (Map.Entry<LivingEntity, Float> entry : targets.entrySet()) {
			LivingEntity e = entry.getKey();
			float shown = entry.getValue() * power, hit = hitFlash(e);
			Vec3 feet = e.getPosition(tickDelta);
			float h = e.getBbHeight(), w = e.getBbWidth();
			matrices.pushPose();
			matrices.translate((float) (feet.x - cam.x), (float) (feet.y - cam.y) + 0.02f, (float) (feet.z - cam.z));
			PoseStack.Pose pose = matrices.last();
			float r = w * 0.9f + 0.15f * hit, spin = (float) t * 1.5f;
			FxRender.ring(light, pose, r - 0.05f, r, 0, 48, (a, inner) -> {
				float bright = 0.55f + 0.45f * (float) Math.max(0, Math.cos((a * Math.PI * 2 - spin) * 2));
				return FxRender.fade(inner ? rim : core, Math.min(1, shown * (inner ? 0.3f : 0.9f) * bright));
			});
			if (hit > 0) FxRender.ring(light, pose, r * 0.2f, r * 1.6f * (0.6f + 0.4f * hit), 0, 32, (a, inner) -> FxRender.fade(core, inner ? Math.min(1, 0.35f * hit * shown) : 0));
			float half = w / 2 + 0.06f, edge = 0.06f;
			int bottom = FxRender.fade(core, Math.min(1, 0.8f * shown)), top = FxRender.fade(rim, Math.min(1, 0.15f * shown));
			for (int i = 0; i < 4; i++) {
				float x = (i & 1) == 0 ? -half : half, z = (i & 2) == 0 ? -half : half;
				FxRender.quad(light, pose, x - edge / 2, 0, z, bottom, x + edge / 2, 0, z, bottom, x + edge / 2, h + 0.1f, z, top, x - edge / 2, h + 0.1f, z, top);
				FxRender.quad(light, pose, x, 0, z - edge / 2, bottom, x, 0, z + edge / 2, bottom, x, h + 0.1f, z + edge / 2, top, x, h + 0.1f, z - edge / 2, top);
			}
			matrices.popPose();
		}
		VertexConsumer glow = FxRender.textured(event, FxRender.GLOW, true, walls);
		for (Map.Entry<LivingEntity, Float> entry : targets.entrySet()) {
			LivingEntity e = entry.getKey();
			float shown = entry.getValue() * power, hit = hitFlash(e);
			Vec3 feet = e.getPosition(tickDelta);
			float h = e.getBbHeight(), w = e.getBbWidth();
			for (int i = 0; i < 22; i++) {
				double a = i * 2.39996 + t * 0.9;
				double y = (((i * 0.618034) + t * 0.25) % 1) * (h + 0.3);
				Vec3 at = feet.add(Math.cos(a) * w * 0.6, y, Math.sin(a) * w * 0.6);
				float flicker = 0.6f + 0.4f * (float) Math.sin(t * 6 + i * 1.7);
				float along = (float) Math.sin(y / (h + 0.3) * Math.PI);
				FxRender.glow(event, glow, at, 0.14f + 0.08f * flicker, FxRender.fade(core, Math.min(1, 0.85f * shown * flicker * along)));
				FxRender.glow(event, glow, at, 0.35f, FxRender.fade(rim, Math.min(1, 0.05f * shown * along)));
			}
			Vec3 middle = feet.add(0, h / 2, 0);
			float pulse = 0.85f + 0.15f * (float) Math.sin(t * 4);
			FxRender.glow(event, glow, middle, h * 1.1f, FxRender.fade(core, Math.min(1, 0.1f * shown * pulse)));
			FxRender.glow(event, glow, middle, h * 1.5f, FxRender.fade(rim, Math.min(1, 0.1f * shown * pulse)));
			FxRender.glow(event, glow, feet.add(0, 0.05, 0), w * 2.2f, FxRender.fade(core, Math.min(1, 0.3f * shown)));
			for (int k = 0; k < 2 && hit > 0; k++) {
				FxRender.glow(event, glow, middle, h * (0.8f + 0.5f * (1 - hit)) * (1 - k * 0.25f), FxRender.fade(core, Math.min(1, 0.75f * hit * shown)));
			}
		}
	}

	/** 1 the moment {@code e} was hit, dying away over a third of a second. */
	private float hitFlash(LivingEntity e) {
		if (e != lastHit) return 0;
		float since = Math.max(0, 1 - (System.currentTimeMillis() - lastHitTime) / 350f);
		return since * since;
	}

	private static double frac(double v) {
		return v - Math.floor(v);
	}

	// ================================================================ aura

	/**
	 * Drawn in passes, one kind of buffer at a time: asking for another kind ends the one before, so
	 * mixing them would draw into a finished buffer.
	 */
	private void aura(Render3DEvent event, Map<LivingEntity, Float> targets) {
		float tickDelta = tickDelta();
		double t = time() * auraSpeed.get();
		Vec3 cam = event.camera().pos;
		PoseStack.Pose pose = event.matrices().last();
		AuraStyle style = auraStyle.get();
		boolean dark = style == AuraStyle.VOID;
		float bright = auraIntensity.getFloat();
		// Void's smoke darkens what's behind it, so it's blended normally; everything else is added as light
		for (int pass = 0; pass < 3; pass++) {
			if (pass == 1 && !dark && !ground.get()) continue;
			if (pass == 2 && style != AuraStyle.HOLY && !motes.get()) continue;
			VertexConsumer consumer = switch (pass) {
				case 0 -> FxRender.quads(event, !dark, throughWalls.get());
				case 1 -> FxRender.quads(event, true, throughWalls.get());
				default -> FxRender.textured(event, FxRender.GLOW, true, throughWalls.get());
			};
			for (Map.Entry<LivingEntity, Float> entry : targets.entrySet()) {
				LivingEntity target = entry.getKey();
				float fade = ease(entry.getValue()) * bright;
				Vec3 feet = target.getPosition(tickDelta);
				float x = (float) (feet.x - cam.x), y = (float) (feet.y - cam.y), z = (float) (feet.z - cam.z);
				float r = target.getBbWidth() * 0.5f + auraRadius.getFloat() * 0.6f;
				float top = target.getBbHeight() * auraHeight.getFloat();
				switch (pass) {
					case 0 -> auraShell(consumer, pose, style, x, y, z, r, top, t, fade);
					case 1 -> {
						if (dark) auraRim(consumer, pose, style, x, y, z, r, t, fade);
						if (ground.get()) auraGround(consumer, pose, x, y, z, r, t, fade);
					}
					default -> {
						if (style == AuraStyle.HOLY) {
							// A halo over their head: a ring of soft lights
							Vec3 crown = feet.add(0, target.getBbHeight() + 0.25 + Math.sin(t * 2) * 0.03, 0);
							for (int h = 0; h < 16; h++) {
								double a = Math.PI * 2 * h / 16 + t * 0.5;
								FxRender.glow(event, consumer, crown.add(Math.cos(a) * 0.32, 0, Math.sin(a) * 0.32), 0.09f,
									FxRender.fade(FxRender.mix(palette.at(h / 16f), 0xFFFFF4C8, 0.6f), fade));
							}
						}
						if (motes.get()) {
							for (int m = 0; m < 14; m++) {
								double seed = m * 1.618;
								double rise = frac(t * 0.35 + seed * 0.37);
								// Void's motes sink instead
								double height = dark ? 1 - rise : rise;
								double angle = seed * 2.3 + t * 0.6;
								float mr = r * (0.7f + 0.4f * (float) frac(seed * 0.71));
								float alpha = fade * (float) Math.sin(Math.PI * rise);
								FxRender.glow(event, consumer, feet.add(Math.cos(angle) * mr, height * top * 1.1, Math.sin(angle) * mr), 0.06f,
									FxRender.fade(FxRender.lighten(palette.at((float) frac(seed)), 0.5f), alpha));
							}
						}
					}
				}
			}
		}
	}

	private void auraShell(VertexConsumer consumer, PoseStack.Pose pose, AuraStyle style, float x, float y, float z, float r, float top, double t, float fade) {
		for (int i = 0; i < AURA_SEGMENTS; i++) {
			double a1 = Math.PI * 2 * i / AURA_SEGMENTS, a2 = Math.PI * 2 * (i + 1) / AURA_SEGMENTS;
			float s1 = strength(style, i, t), s2 = strength(style, i + 1, t);
			if (s1 <= 0.01f && s2 <= 0.01f) continue;
			float h1 = top * reach(style, i, t), h2 = top * reach(style, i + 1, t);
			int c1 = auraColor(style, (float) i / AURA_SEGMENTS), c2 = auraColor(style, (float) (i + 1) / AURA_SEGMENTS);
			float cx1 = (float) Math.cos(a1) * r, cz1 = (float) Math.sin(a1) * r, cx2 = (float) Math.cos(a2) * r, cz2 = (float) Math.sin(a2) * r;
			// Leaning in a little as it rises (Holy's rays fan out instead)
			float in = style == AuraStyle.HOLY ? 1.25f : 0.82f;
			float base = style == AuraStyle.VOID ? 0.75f : 0.55f;
			FxRender.quad(consumer, pose,
				x + cx1, y, z + cz1, FxRender.fade(c1, fade * s1 * base),
				x + cx2, y, z + cz2, FxRender.fade(c2, fade * s2 * base),
				x + cx2 * in, y + h2, z + cz2 * in, FxRender.fade(c2, 0),
				x + cx1 * in, y + h1, z + cz1 * in, FxRender.fade(c1, 0));
		}
	}

	/** Void's burning rim along the bottom of the smoke. */
	private void auraRim(VertexConsumer consumer, PoseStack.Pose pose, AuraStyle style, float x, float y, float z, float r, double t, float fade) {
		for (int i = 0; i < AURA_SEGMENTS; i++) {
			double a1 = Math.PI * 2 * i / AURA_SEGMENTS, a2 = Math.PI * 2 * (i + 1) / AURA_SEGMENTS;
			float s1 = strength(style, i, t), s2 = strength(style, i + 1, t);
			int rim1 = palette.at((float) i / AURA_SEGMENTS), rim2 = palette.at((float) (i + 1) / AURA_SEGMENTS);
			float cx1 = (float) Math.cos(a1) * r, cz1 = (float) Math.sin(a1) * r, cx2 = (float) Math.cos(a2) * r, cz2 = (float) Math.sin(a2) * r;
			float rh = 0.12f + 0.06f * (float) Math.sin(a1 * 5 + t * 4);
			FxRender.quad(consumer, pose,
				x + cx1, y, z + cz1, FxRender.fade(rim1, fade * s1),
				x + cx2, y, z + cz2, FxRender.fade(rim2, fade * s2),
				x + cx2, y + rh, z + cz2, FxRender.fade(rim2, 0),
				x + cx1, y + rh, z + cz1, FxRender.fade(rim1, 0));
		}
	}

	/** A soft disc of light: bright under them, fading to nothing at the edge. */
	private void auraGround(VertexConsumer consumer, PoseStack.Pose pose, float x, float y, float z, float r, double t, float fade) {
		float outer = r * 2.1f, gy = y + 0.02f;
		int center = palette.at((float) (t * 0.1 % 1));
		for (int i = 0; i < AURA_SEGMENTS; i++) {
			double a1 = Math.PI * 2 * i / AURA_SEGMENTS, a2 = Math.PI * 2 * (i + 1) / AURA_SEGMENTS;
			FxRender.quad(consumer, pose,
				x, gy, z, FxRender.fade(center, fade * 0.45f),
				x, gy, z, FxRender.fade(center, fade * 0.45f),
				x + (float) Math.cos(a2) * outer, gy, z + (float) Math.sin(a2) * outer, FxRender.fade(center, 0),
				x + (float) Math.cos(a1) * outer, gy, z + (float) Math.sin(a1) * outer, FxRender.fade(center, 0));
		}
	}

	private int auraColor(AuraStyle style, float at) {
		int color = palette.at(at);
		return switch (style) {
			case VOID -> FxRender.darken(color, 0.82f);
			case HOLY -> FxRender.mix(color, 0xFFFFF1C2, 0.65f);
			default -> color;
		};
	}

	/** How strong the aura is round its edge at segment {@code i}. */
	private static float strength(AuraStyle style, int i, double t) {
		double a = Math.PI * 2 * i / AURA_SEGMENTS;
		return switch (style) {
			case SHELL -> (float) (0.7 + 0.3 * Math.sin(a * 3 + t * 2.2));
			case FLAME -> (float) (0.55 + 0.45 * Math.sin(a * 5 + t * 3.1) * Math.sin(a * 2 - t * 1.7));
			case PILLARS -> (float) Math.pow(Math.max(0, Math.cos(a * 4 + t * 0.8)), 6);
			case VOID -> (float) (0.75 + 0.25 * Math.sin(a * 4 - t * 1.6));
			case HOLY -> (float) Math.pow(Math.max(0, Math.cos(a * 6 + t * 0.4)), 10) + 0.12f;
		};
	}

	/** How far up the aura reaches at segment {@code i}, as a share of its full height. */
	private static float reach(AuraStyle style, int i, double t) {
		double a = Math.PI * 2 * i / AURA_SEGMENTS;
		return switch (style) {
			case SHELL -> (float) (0.85 + 0.15 * Math.sin(a * 2 + t * 1.3));
			case FLAME -> (float) (0.55 + 0.45 * Math.abs(Math.sin(a * 4 + t * 2.6 + Math.sin(t * 1.3 + a * 7))));
			case PILLARS -> 1.1f;
			case VOID -> (float) (0.6 + 0.3 * Math.abs(Math.sin(a * 3 + t * 1.1 + Math.sin(t * 0.7 + a * 5))));
			case HOLY -> 1.6f;
		};
	}

	// ================================================================ astral

	/** One star this frame: where, how bright it's twinkling, and its colour. */
	private record Star(Vec3 at, float alpha, float twinkle, int color) {}

	/** Stars sit round them in a loose shell, drifting round and twinkling, some joined into constellations. */
	private void astral(Render3DEvent event, Map<LivingEntity, Float> targets) {
		float tickDelta = tickDelta();
		double t = time();
		double drift = t * astralSpeed.get();
		int count = stars.getInt();
		float scale = starSize.getFloat();
		Vec3 cam = event.camera().pos;
		PoseStack.Pose pose = event.matrices().last();
		List<Star> all = new ArrayList<>();
		List<Vec3[]> links = new ArrayList<>();
		List<Integer> linkColors = new ArrayList<>();
		List<Star> dust = new ArrayList<>();

		// Work out every star first; then each kind is drawn in one go, one buffer at a time
		for (Map.Entry<LivingEntity, Float> entry : targets.entrySet()) {
			LivingEntity target = entry.getKey();
			float fade = ease(entry.getValue());
			Vec3 feet = target.getPosition(tickDelta);
			float height = target.getBbHeight();
			float r = (target.getBbWidth() * 0.5f + 0.45f) * astralRadius.getFloat();
			Star[] mine = new Star[count];
			for (int i = 0; i < count; i++) {
				double seed = i * 0.618034;
				// Golden-angle spread, so they never bunch up; alternate stars drift opposite ways
				double angle = i * 2.39996 + drift * (0.25 + 0.2 * frac(seed * 1.7)) * (i % 2 == 0 ? 1 : -1);
				double rr = r * (0.85 + 0.4 * frac(seed * 3.1));
				double y = frac(seed) * height * 1.25 - 0.05 + 0.06 * Math.sin(t * 0.9 + i);
				float twinkle = (float) (0.35 + 0.65 * Math.pow(0.5 + 0.5 * Math.sin(t * (2.2 + 2.5 * frac(seed * 5.3)) + i * 1.7), 3));
				mine[i] = new Star(feet.add(Math.cos(angle) * rr, y, Math.sin(angle) * rr), fade * twinkle, twinkle, palette.at(i / (float) count));
				all.add(mine[i]);
			}
			if (constellations.get()) {
				// Stars in groups of four, each joined to the next in its group if it's close enough
				for (int i = 0; i + 1 < count; i++) {
					if (i % 4 == 3 || mine[i].at.distanceTo(mine[i + 1].at) > r * 1.5) continue;
					links.add(new Vec3[]{mine[i].at, mine[i + 1].at});
					linkColors.add(FxRender.fade(FxRender.lighten(mine[i].color, 0.4f), fade * 0.3f * Math.min(mine[i].twinkle, mine[i + 1].twinkle)));
				}
			}
			if (stardust.get()) {
				for (int d = 0; d < 26; d++) {
					double seed = d * 0.7548;
					double rise = frac(t * 0.18 + seed);
					double angle = seed * 9 + rise * 5 + t * 0.4;
					double rr = r * (0.35 + 0.6 * frac(seed * 2.3));
					dust.add(new Star(feet.add(Math.cos(angle) * rr, rise * height * 1.4, Math.sin(angle) * rr),
						fade * 0.7f * (float) Math.sin(Math.PI * rise), 1, FxRender.lighten(palette.at((float) frac(seed * 3)), 0.5f)));
				}
			}
		}

		if (!links.isEmpty()) {
			VertexConsumer lines = FxRender.lines(event);
			for (int i = 0; i < links.size(); i++) {
				Vec3 a = links.get(i)[0], b = links.get(i)[1];
				FxRender.line(lines, pose, (float) (a.x - cam.x), (float) (a.y - cam.y), (float) (a.z - cam.z),
					(float) (b.x - cam.x), (float) (b.y - cam.y), (float) (b.z - cam.z), linkColors.get(i), 1f);
			}
		}
		// Coloured halos and the dust, added as light
		VertexConsumer halo = FxRender.textured(event, FxRender.GLOW, true, throughWalls.get());
		for (Star s : all) FxRender.glow(event, halo, s.at, 0.2f * scale * (0.7f + 0.5f * s.twinkle), FxRender.fade(s.color, s.alpha * 0.45f));
		for (Star s : dust) FxRender.glow(event, halo, s.at, 0.035f * scale, FxRender.fade(s.color, s.alpha));
		// White-hot cores
		VertexConsumer core = FxRender.textured(event, FxRender.GLOW, false, throughWalls.get());
		for (Star s : all) FxRender.glow(event, core, s.at, 0.045f * scale, FxRender.fade(FxRender.lighten(s.color, 0.75f), s.alpha));
		// A four point sparkle on the brightest
		VertexConsumer sparkle = FxRender.quads(event, true, throughWalls.get());
		Vector3f right = new Vector3f(1, 0, 0).rotate(event.camera().orientation), up = new Vector3f(0, 1, 0).rotate(event.camera().orientation);
		for (Star s : all) {
			if (s.twinkle <= 0.6f) continue;
			float len = 0.16f * scale * (s.twinkle - 0.5f) * 2, w = 0.008f * scale;
			float sx = (float) (s.at.x - cam.x), sy = (float) (s.at.y - cam.y), sz = (float) (s.at.z - cam.z);
			int c = FxRender.fade(FxRender.lighten(s.color, 0.6f), s.alpha);
			spike(sparkle, pose, sx, sy, sz, right, up, len, w, c);
			spike(sparkle, pose, sx, sy, sz, up, right, len, w, c);
		}
	}

	/** One arm of a sparkle: a thin bright bar through the star along {@code along}, fading at both ends. */
	private static void spike(VertexConsumer consumer, PoseStack.Pose pose, float x, float y, float z, Vector3f along, Vector3f side, float len, float w, int color) {
		float ax = along.x * len, ay = along.y * len, az = along.z * len, sx = side.x * w, sy = side.y * w, sz = side.z * w;
		int clear = FxRender.fade(color, 0);
		FxRender.quad(consumer, pose, x - ax, y - ay, z - az, clear, x - sx, y - sy, z - sz, color, x + ax, y + ay, z + az, clear, x + sx, y + sy, z + sz, color);
	}

	// ================================================================ galaxy

	/** Spiral arms of stars round a bright core, turning slowly, flat under (or round) them. */
	private void galaxy(Render3DEvent event, Map<LivingEntity, Float> targets) {
		float tickDelta = tickDelta();
		double t = time() * galaxySpeed.get();
		int armCount = arms.getInt();
		VertexConsumer glow = FxRender.textured(event, FxRender.GLOW, true, throughWalls.get());
		for (Map.Entry<LivingEntity, Float> entry : targets.entrySet()) {
			LivingEntity target = entry.getKey();
			float fade = ease(entry.getValue());
			Vec3 center = target.getPosition(tickDelta).add(0, 0.05 + target.getBbHeight() * galaxyHeight.get(), 0);
			float size = galaxySize.getFloat() * Math.max(1, target.getBbWidth() / 0.6f);
			// The core
			FxRender.glow(event, glow, center, size * 0.45f, FxRender.fade(FxRender.lighten(palette.at(0), 0.5f), fade * 0.55f));
			FxRender.glow(event, glow, center, size * 0.18f, FxRender.fade(0xFFFFFFFF, fade * 0.5f));
			for (int arm = 0; arm < armCount; arm++) {
				for (int k = 0; k < 42; k++) {
					float s = k / 42f;
					// A little scatter either side of the arm, the same every frame
					double jitter = (frac(Math.sin(arm * 91.7 + k * 12.9898) * 43758.5453) - 0.5) * 0.5;
					double angle = Math.PI * 2 * arm / armCount + s * 3.4 + jitter * (0.3 + s) - t * 0.5;
					double rad = size * (0.12 + 0.88 * s);
					Vec3 p = center.add(Math.cos(angle) * rad, Math.sin(t * 0.8 + k) * 0.01, Math.sin(angle) * rad);
					float alpha = fade * 0.75f * (float) Math.pow(1 - s, 0.7) * (0.6f + 0.4f * (float) Math.sin(t * 3 + k * 1.3 + arm));
					int color = palette.at(s * 0.7f + (float) arm / armCount * 0.3f);
					FxRender.glow(event, glow, p, 0.11f * (1 - s * 0.55f) * size / 1.4f, FxRender.fade(k % 5 == 0 ? FxRender.lighten(color, 0.6f) : color, alpha));
				}
			}
		}
	}

	// ================================================================ swirl

	private record Orb(Vec3 pos, float size, int color, float alpha, boolean halo) {}

	private void swirl(Render3DEvent event, Map<LivingEntity, Float> targets) {
		float tickDelta = tickDelta();
		double now = time() * swirlSpeed.get();
		int count = strands.getInt();
		int length = trail.getInt();
		// Tails cover the same stretch of path whatever the speed
		double spacing = 0.03;
		List<Orb> orbs = new ArrayList<>();
		for (Map.Entry<LivingEntity, Float> entry : targets.entrySet()) {
			LivingEntity target = entry.getKey();
			float fade = ease(entry.getValue());
			Vec3 feet = target.getPosition(tickDelta);
			float height = target.getBbHeight();
			float r = (target.getBbWidth() * 0.5f + 0.35f) * swirlRadius.getFloat();
			for (int strand = 0; strand < count; strand++) {
				for (int k = length - 1; k >= 0; k--) {
					float along = (float) k / length;
					Vec3 offset = orbit(strand, count, now - k * spacing, r, height);
					float tail = 1 - along;
					float alpha = fade * (float) Math.pow(tail, 1.4) * envelope(strand, count, now - k * spacing);
					if (alpha <= 0.01f) continue;
					int color = palette.at((float) strand / count + along * 0.35f);
					float orb = orbSize.getFloat() * (0.35f + 0.65f * (float) Math.pow(tail, 0.6));
					orbs.add(new Orb(feet.add(offset), orb, color, alpha, k % 3 == 0));
				}
			}
		}
		VertexConsumer core = FxRender.textured(event, FxRender.GLOW, false, throughWalls.get());
		for (Orb orb : orbs) FxRender.glow(event, core, orb.pos, orb.size, FxRender.fade(orb.color, orb.alpha));
		if (!orbGlow.get()) return;
		VertexConsumer halo = FxRender.textured(event, FxRender.GLOW, true, throughWalls.get());
		for (Orb orb : orbs) {
			if (orb.halo) FxRender.glow(event, halo, orb.pos, orb.size * 2.6f, FxRender.fade(orb.color, orb.alpha * 0.35f));
		}
	}

	/** Where strand {@code i} is at time {@code t}, from the target's feet. */
	private Vec3 orbit(int i, int count, double t, float r, float height) {
		double share = Math.PI * 2 * i / count;
		return switch (swirlMode.get()) {
			case HELIX -> {
				double angle = t * 2.4 + share;
				yield new Vec3(Math.cos(angle) * r, height * (0.5 - 0.5 * Math.cos(t * 1.1)), Math.sin(angle) * r);
			}
			case TORNADO -> {
				double climb = frac(t * 0.35 + (double) i / count);
				double angle = t * 4 + share + climb * 6;
				double spread = r * (0.3 + 0.95 * climb);
				yield new Vec3(Math.cos(angle) * spread, climb * height * 1.15 - 0.05, Math.sin(angle) * spread);
			}
			case ATOM -> {
				// Each orbit is a circle tipped over and turned round the body
				double angle = t * 3.2 + share;
				double tilt = Math.toRadians(60), turn = share / 2;
				double x = Math.cos(angle) * r * 1.1, z = Math.sin(angle) * r * 1.1;
				double y = z * Math.sin(tilt);
				z *= Math.cos(tilt);
				yield new Vec3(x * Math.cos(turn) - z * Math.sin(turn), height * 0.55 + y, x * Math.sin(turn) + z * Math.cos(turn));
			}
			case RINGS -> {
				double angle = t * 3 * (i % 2 == 0 ? 1 : -1) + i * 1.3;
				yield new Vec3(Math.cos(angle) * r, height * (i + 0.5) / count, Math.sin(angle) * r);
			}
			case SPIRITS -> {
				double angle = t * (1.4 + 0.3 * i) + i * 2.4;
				double spread = r * (0.8 + 0.3 * Math.sin(t * 1.7 + i * 1.3));
				yield new Vec3(Math.cos(angle) * spread, height * (0.5 + 0.42 * Math.sin(t * 1.1 + i * 2.1)), Math.sin(angle) * spread);
			}
		};
	}

	/** Tornado orbs fade in at the bottom and out at the top, so the jump back down is hidden. */
	private float envelope(int i, int count, double t) {
		if (!swirlMode.is(SwirlMode.TORNADO)) return 1;
		return (float) Math.sin(Math.PI * frac(t * 0.35 + (double) i / count));
	}

	// ================================================================ ring

	private void ring(Render3DEvent event, Map<LivingEntity, Float> targets, boolean skirtPass) {
		float tickDelta = tickDelta();
		double phase = time() * ringSpeed.get() * 2;
		Vec3 cam = event.camera().pos;
		PoseStack matrices = event.matrices();
		VertexConsumer consumer = FxRender.quads(event, !skirtPass && ringGlow.get(), throughWalls.get());
		for (Map.Entry<LivingEntity, Float> entry : targets.entrySet()) {
			LivingEntity target = entry.getKey();
			float fade = ease(entry.getValue());
			Vec3 feet = target.getPosition(tickDelta);
			float height = target.getBbHeight();
			float r = (target.getBbWidth() * 0.5f + 0.2f) * ringRadius.getFloat();
			// Eased up and down; the skirt trails the way it came, longest mid-travel
			float y = height * (0.5f - 0.5f * (float) Math.cos(phase)) + 0.02f;
			float velocity = (float) Math.sin(phase);
			float skirt = -Math.signum(velocity) * trailHeight.getFloat() * (float) Math.pow(Math.abs(velocity), 0.7);
			matrices.pushPose();
			matrices.translate((float) (feet.x - cam.x), (float) (feet.y - cam.y), (float) (feet.z - cam.z));
			PoseStack.Pose pose = matrices.last();
			float alpha = fade * opacity.getFloat();
			float half = thickness.getFloat() / 2;
			for (int i = 0; i < RING_SEGMENTS; i++) {
				float t1 = (float) i / RING_SEGMENTS, t2 = (float) (i + 1) / RING_SEGMENTS;
				float x1 = (float) Math.cos(t1 * Math.PI * 2) * r, z1 = (float) Math.sin(t1 * Math.PI * 2) * r;
				float x2 = (float) Math.cos(t2 * Math.PI * 2) * r, z2 = (float) Math.sin(t2 * Math.PI * 2) * r;
				int c1 = palette.at(t1), c2 = palette.at(t2);
				if (skirtPass) {
					if (Math.abs(skirt) > 0.01f) {
						FxRender.quad(consumer, pose,
							x1, y, z1, FxRender.fade(c1, alpha), x2, y, z2, FxRender.fade(c2, alpha),
							x2, y + skirt, z2, FxRender.fade(c2, 0), x1, y + skirt, z1, FxRender.fade(c1, 0));
					}
					continue;
				}
				FxRender.quad(consumer, pose,
					x1, y - half, z1, FxRender.fade(c1, fade), x2, y - half, z2, FxRender.fade(c2, fade),
					x2, y + half, z2, FxRender.fade(c2, fade), x1, y + half, z1, FxRender.fade(c1, fade));
			}
			matrices.popPose();
		}
	}

	// ================================================================ marker

	private void marker(Render3DEvent event, Map<LivingEntity, Float> targets) {
		float tickDelta = tickDelta();
		double now = time();
		for (Map.Entry<LivingEntity, Float> entry : targets.entrySet()) {
			LivingEntity target = entry.getKey();
			float fade = ease(entry.getValue());
			Vec3 feet = target.getPosition(tickDelta);
			switch (markerMode.get()) {
				case CRYSTAL -> crystal(event, feet.add(0, target.getBbHeight() + 0.45 + Math.sin(now * 2.5) * 0.08, 0), now, fade);
				case RETICLE -> reticle(event, feet.add(0, target.getBbHeight() / 2, 0), Math.max(target.getBbWidth(), target.getBbHeight()) * 0.62f, now, fade);
				case ARROW -> arrow(event, feet.add(0, target.getBbHeight() + 0.3 + Math.abs(Math.sin(now * 3)) * 0.2, 0), now, fade);
			}
		}
	}

	/** An octahedron, spinning, with lit and shaded faces in alternating palette colours. */
	private void crystal(Render3DEvent event, Vec3 center, double now, float fade) {
		float s = markerSize.getFloat();
		float w = 0.17f * s, h = 0.27f * s;
		PoseStack matrices = event.matrices();
		Vec3 cam = event.camera().pos;
		if (markerGlow.get()) FxRender.glow(event, FxRender.textured(event, FxRender.GLOW, true, throughWalls.get()), center, 0.55f * s, FxRender.fade(palette.at(0), fade * 0.45f));
		matrices.pushPose();
		matrices.translate((float) (center.x - cam.x), (float) (center.y - cam.y), (float) (center.z - cam.z));
		matrices.mulPose(Axis.YP.rotation((float) (now * spin.get() * 2)));
		PoseStack.Pose pose = matrices.last();
		VertexConsumer faces = FxRender.quads(event, false, throughWalls.get());
		for (int i = 0; i < 4; i++) {
			double a1 = Math.PI / 2 * i, a2 = Math.PI / 2 * (i + 1);
			float x1 = (float) Math.cos(a1) * w, z1 = (float) Math.sin(a1) * w;
			float x2 = (float) Math.cos(a2) * w, z2 = (float) Math.sin(a2) * w;
			int color = palette.at(i / 4f);
			// Faces facing alternate ways are shaded differently, so it reads as a solid gem
			int top = FxRender.fade(i % 2 == 0 ? FxRender.lighten(color, 0.35f) : color, fade * 0.85f);
			int bottom = FxRender.fade(i % 2 == 0 ? color : FxRender.darken(color, 0.35f), fade * 0.85f);
			FxRender.triangle(faces, pose, 0, h, 0, FxRender.fade(0xFFFFFFFF, fade * 0.9f), x1, 0, z1, top, x2, 0, z2, top);
			FxRender.triangle(faces, pose, 0, -h, 0, bottom, x1, 0, z1, bottom, x2, 0, z2, bottom);
		}
		VertexConsumer edges = FxRender.lines(event);
		for (int i = 0; i < 4; i++) {
			double a1 = Math.PI / 2 * i, a2 = Math.PI / 2 * (i + 1);
			float x1 = (float) Math.cos(a1) * w, z1 = (float) Math.sin(a1) * w;
			float x2 = (float) Math.cos(a2) * w, z2 = (float) Math.sin(a2) * w;
			int edge = FxRender.fade(FxRender.lighten(palette.at(i / 4f), 0.6f), fade);
			FxRender.line(edges, pose, 0, h, 0, x1, 0, z1, edge, 1.5f);
			FxRender.line(edges, pose, 0, -h, 0, x1, 0, z1, edge, 1.5f);
			FxRender.line(edges, pose, x1, 0, z1, x2, 0, z2, edge, 1.5f);
		}
		matrices.popPose();
	}

	/** Four corner brackets facing the camera, turning slowly and breathing in and out. */
	private void reticle(Render3DEvent event, Vec3 center, float half, double now, float fade) {
		half *= markerSize.getFloat() * (1 + 0.06f * (float) Math.sin(now * 4));
		float roll = (float) (now * spin.get() * 0.8);
		float arm = half * 0.45f, thick = 0.035f * markerSize.getFloat();
		Vec3 cam = event.camera().pos;
		float cx = (float) (center.x - cam.x), cy = (float) (center.y - cam.y), cz = (float) (center.z - cam.z);
		PoseStack.Pose pose = event.matrices().last();
		VertexConsumer consumer = FxRender.quads(event, markerGlow.get(), throughWalls.get());
		for (int corner = 0; corner < 4; corner++) {
			double angle = roll + Math.PI / 2 * corner;
			// Each bracket's two arms, along this corner's own screen directions
			Vector3f along = screen(event, (float) Math.cos(angle), (float) Math.sin(angle));
			Vector3f across = screen(event, (float) -Math.sin(angle), (float) Math.cos(angle));
			int color = FxRender.fade(palette.at(corner / 4f), fade);
			Vector3f tip = new Vector3f(along).add(across).mul(half).add(cx, cy, cz);
			bar(consumer, pose, tip, new Vector3f(along).mul(-arm), across, thick, color);
			bar(consumer, pose, tip, new Vector3f(across).mul(-arm), along, thick, color);
		}
	}

	private static Vector3f screen(Render3DEvent event, float x, float y) {
		return new Vector3f(x, y, 0).rotate(event.camera().orientation);
	}

	/** A bar from {@code start} along {@code direction}, {@code thick} wide towards {@code side}. */
	private static void bar(VertexConsumer consumer, PoseStack.Pose pose, Vector3f start, Vector3f direction, Vector3f side, float thick, int color) {
		Vector3f offset = new Vector3f(side).mul(thick);
		Vector3f end = new Vector3f(start).add(direction);
		FxRender.quad(consumer, pose,
			start.x, start.y, start.z, color, end.x, end.y, end.z, FxRender.fade(color, 0.25f),
			end.x - offset.x, end.y - offset.y, end.z - offset.z, FxRender.fade(color, 0.25f), start.x - offset.x, start.y - offset.y, start.z - offset.z, color);
	}

	/** A four sided arrowhead with a short shaft, pointing down. */
	private void arrow(Render3DEvent event, Vec3 tip, double now, float fade) {
		float s = markerSize.getFloat();
		float head = 0.22f * s, width = 0.16f * s, shaft = 0.06f * s, length = 0.2f * s;
		PoseStack matrices = event.matrices();
		Vec3 cam = event.camera().pos;
		matrices.pushPose();
		matrices.translate((float) (tip.x - cam.x), (float) (tip.y - cam.y), (float) (tip.z - cam.z));
		matrices.mulPose(Axis.YP.rotation((float) (now * spin.get() * 1.5)));
		PoseStack.Pose pose = matrices.last();
		VertexConsumer faces = FxRender.quads(event, false, throughWalls.get());
		for (int i = 0; i < 4; i++) {
			double a1 = Math.PI / 2 * i, a2 = Math.PI / 2 * (i + 1);
			float c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1), c2 = (float) Math.cos(a2), s2 = (float) Math.sin(a2);
			int color = palette.at(i / 4f);
			int lit = FxRender.fade(i % 2 == 0 ? FxRender.lighten(color, 0.3f) : FxRender.darken(color, 0.2f), fade * 0.9f);
			FxRender.triangle(faces, pose, 0, 0, 0, FxRender.fade(0xFFFFFFFF, fade), c1 * width, head, s1 * width, lit, c2 * width, head, s2 * width, lit);
			// The underside of the head, and one side of the shaft
			FxRender.triangle(faces, pose, 0, head, 0, lit, c1 * width, head, s1 * width, lit, c2 * width, head, s2 * width, lit);
			FxRender.quad(faces, pose,
				c1 * shaft, head, s1 * shaft, lit, c2 * shaft, head, s2 * shaft, lit,
				c2 * shaft, head + length, s2 * shaft, FxRender.fade(lit, 0.2f), c1 * shaft, head + length, s1 * shaft, FxRender.fade(lit, 0.2f));
		}
		matrices.popPose();
		if (markerGlow.get()) FxRender.glow(event, FxRender.textured(event, FxRender.GLOW, true, throughWalls.get()), tip.add(0, head, 0), 0.4f * s, FxRender.fade(palette.at(0), fade * 0.4f));
	}
}
