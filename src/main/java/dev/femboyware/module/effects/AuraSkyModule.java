package dev.femboyware.module.effects;

import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.mixin.AbstractTextureAccessor;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.FxRender;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributes;
import org.joml.Vector3f;

/**
 * Replaces the whole sky with a glowing, moving ceiling: aurora green, golden sandstorm, crimson
 * or violet plasma wisps, or a web of electric blue cells, with glowing streaks falling through it.
 * The pattern is projected onto a flat ceiling far overhead, so it gets smaller towards the
 * horizon like real clouds. The fog matches, so the land fades into it.
 */
public class AuraSkyModule extends SkyFxModule {
	public enum Style {
		AURORA(Pattern.PLASMA, 0xFF051A14, 0xFF0C3A2A, 0xFF34FFAA, 0xFF9CFFD8, 0xFF6CFFC4),
		SANDSTORM(Pattern.PLASMA, 0xFF1E0F04, 0xFF4E2C0C, 0xFFFFA43C, 0xFFFFD890, 0xFFFFB050),
		CRIMSON(Pattern.PLASMA, 0xFF1C0408, 0xFF4A1218, 0xFFFF4A5E, 0xFFFFB0B8, 0xFFFF7080),
		VIOLET(Pattern.PLASMA, 0xFF0E0418, 0xFF2C1248, 0xFFB46CFF, 0xFFE4C8FF, 0xFFC890FF),
		CRYSTAL(Pattern.CELLS, 0xFF010208, 0xFF061630, 0xFF3A96FF, 0xFF9CCCFF, 0xFFC8E4FF);

		final Pattern pattern;
		/** Overhead, at the horizon, the pattern's colour, its highlights, and the streaks. */
		final int zenith, horizon, glow, highlight, streak;

		Style(Pattern pattern, int zenith, int horizon, int glow, int highlight, int streak) {
			this.pattern = pattern;
			this.zenith = zenith;
			this.horizon = horizon;
			this.glow = glow;
			this.highlight = highlight;
			this.streak = streak;
		}
	}

	enum Pattern { PLASMA, CELLS }

	private static final Identifier PLASMA = texture("plasma");
	private static final Identifier PLASMA_FINE = texture("plasma_fine");
	private static final Identifier CELLS = texture("cells");
	private static final Identifier STREAK = texture("streak");
	private static final float[] RINGS = {-14, -5, -1, 1, 3, 5, 8, 11, 15, 20, 26, 33, 41, 50, 60, 71, 82, 90};
	private static final int SEGMENTS = 72;
	private static final int STREAKS = 70;

	private final EnumSetting<Style> style = add(new EnumSetting<>("Style", "Aurora green, sandstorm gold, crimson, violet, or crystal blue cells", Style.AURORA));
	private final NumberSetting speed = add(new NumberSetting("Speed", "How fast the sky moves", 1, 0, 4, 0.05));
	private final NumberSetting brightness = add(new NumberSetting("Brightness", "How bright the glowing pattern is", 1, 0.2, 2, 0.05));
	private final NumberSetting scale = add(new NumberSetting("Scale", "How big the pattern is", 1, 0.3, 3, 0.05));
	private final BoolSetting streaks = add(new BoolSetting("Streaks", "Glowing streaks falling from the sky", true));
	private final BoolSetting fog = add(new BoolSetting("Match Fog", "Fade the land into the sky's colour", true));

	private final Set<Identifier> repeating = new HashSet<>();
	private final float[] streakSeeds = new float[STREAKS * 4];

	public AuraSkyModule() {
		super("Aura Sky", "A glowing, moving aurora, plasma or crystal sky");
		Random random = new Random(42);
		for (int i = 0; i < streakSeeds.length; i++) streakSeeds[i] = random.nextFloat();
	}

	@Override
	public String getInfo() {
		return style.getDisplayName();
	}

	/** Called from EnvironmentAttributeInterpolatorMixin: the fog and sky colours become the horizon's. */
	public Object modify(EnvironmentAttribute<?> attribute, Object value) {
		if (!isEnabled() || !fog.get() || !(value instanceof Integer argb)) return value;
		if (attribute == EnvironmentAttributes.FOG_COLOR || attribute == EnvironmentAttributes.SKY_COLOR) {
			return (argb & 0xFF000000) | (style.get().horizon & 0x00FFFFFF);
		}
		return value;
	}

	/** Our pattern textures tile, so they need to repeat instead of stretching their edge. */
	private void repeat(Identifier id) {
		if (repeating.contains(id)) return;
		AbstractTexture texture = mc.getTextureManager().getTexture(id);
		((AbstractTextureAccessor) (Object) texture).femboyware$setSampler(RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR));
		repeating.add(id);
	}

	@Override
	protected void onDisable() {
		// Texture reloads can swap the textures out, so set them up again next time
		repeating.clear();
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (!hasSky()) return;
		Style s = style.get();
		float distance = skyDistance();
		float t = (float) (time() * speed.get());
		PoseStack.Pose pose = event.matrices().last();

		// The sky itself: dark overhead, lighter towards the horizon
		VertexConsumer base = FxRender.quads(event, false, false);
		dome(pose, base, distance, (dir, u, v) -> {
			float toward = 1 - smooth(-0.05f, 0.45f, dir.y);
			return FxRender.mix(s.zenith, s.horizon, toward);
		}, 0, 0, 0);

		float bright = brightness.getFloat();
		float zoom = 1 / scale.getFloat();
		if (s.pattern == Pattern.PLASMA) {
			repeat(PLASMA);
			repeat(PLASMA_FINE);
			// Two layers drifting different ways, so the wisps seem to flow and churn
			layer(event, pose, PLASMA, distance, 0.2f * zoom, t * 0.005f, t * 0.002f, s.glow, 0.9f * bright);
			layer(event, pose, PLASMA, distance, 0.13f * zoom, -t * 0.0035f, t * 0.004f, s.glow, 0.4f * bright);
			float pulse = 0.75f + 0.25f * (float) Math.sin(t * 0.4);
			layer(event, pose, PLASMA_FINE, distance, 0.28f * zoom, t * 0.007f, -t * 0.0025f, s.highlight, 0.3f * bright * pulse);
		} else {
			repeat(CELLS);
			// A near web and a fainter, finer one further up drifting the other way, breathing slowly
			float breathe = 0.8f + 0.2f * (float) Math.sin(t * 0.6);
			layer(event, pose, CELLS, distance, 0.18f * zoom, t * 0.002f, t * 0.001f, s.glow, 1.0f * bright * breathe);
			layer(event, pose, CELLS, distance, 0.33f * zoom, -t * 0.0015f, t * 0.002f, s.glow, 0.35f * bright);
			layer(event, pose, CELLS, distance, 0.18f * zoom, t * 0.002f, t * 0.001f, s.highlight, 0.25f * bright * breathe);
		}
		if (streaks.get()) streaks(event, s, t);
	}

	private interface Shade {
		int color(Vector3f dir, float u, float v);
	}

	/** One glowing layer of the pattern, added on as light, fading out near the horizon where it'd shimmer. */
	private void layer(Render3DEvent event, PoseStack.Pose pose, Identifier texture, float distance, float tiles, float du, float dv, int color, float strength) {
		VertexConsumer consumer = FxRender.textured(event, texture, true, false);
		dome(pose, consumer, distance, (dir, u, v) -> FxRender.fade(color, strength * smooth(-0.02f, 0.22f, dir.y)), tiles, du, dv);
	}

	/**
	 * The sky as rings of quads round the camera. Texture coordinates come from projecting each
	 * point up onto a flat ceiling, so the pattern recedes into the distance at the horizon.
	 */
	private void dome(PoseStack.Pose pose, VertexConsumer consumer, float distance, Shade shade, float tiles, float du, float dv) {
		boolean textured = tiles > 0;
		for (int r = 0; r < RINGS.length - 1; r++) {
			for (int i = 0; i < SEGMENTS; i++) {
				float az1 = 360f * i / SEGMENTS, az2 = 360f * (i + 1) / SEGMENTS;
				Vector3f a = direction(az1, RINGS[r]), b = direction(az2, RINGS[r]), c = direction(az2, RINGS[r + 1]), d = direction(az1, RINGS[r + 1]);
				vertex(consumer, pose, a, distance, shade, tiles, du, dv, textured);
				vertex(consumer, pose, b, distance, shade, tiles, du, dv, textured);
				vertex(consumer, pose, c, distance, shade, tiles, du, dv, textured);
				vertex(consumer, pose, d, distance, shade, tiles, du, dv, textured);
			}
		}
	}

	private static void vertex(VertexConsumer consumer, PoseStack.Pose pose, Vector3f dir, float distance, Shade shade, float tiles, float du, float dv, boolean textured) {
		float lift = Math.max(dir.y, 0) + 0.12f;
		float u = dir.x / lift * tiles + du, v = dir.z / lift * tiles + dv;
		int color = shade.color(dir, u, v);
		if (textured) FxRender.textured(consumer, pose, dir.x * distance, dir.y * distance, dir.z * distance, u, v, color);
		else FxRender.vertex(consumer, pose, dir.x * distance, dir.y * distance, dir.z * distance, color);
	}

	/** Glowing streaks falling all round, each on a loop: in at the top, out at the ground. */
	private void streaks(Render3DEvent event, Style s, float t) {
		repeat(STREAK);
		VertexConsumer consumer = FxRender.textured(event, STREAK, true, false);
		PoseStack.Pose pose = event.matrices().last();
		Vector3f right = new Vector3f(1, 0, 0).rotate(event.camera().orientation);
		right.y = 0;
		if (right.lengthSquared() < 1e-4f) right.set(1, 0, 0);
		right.normalize();
		for (int i = 0; i < STREAKS; i++) {
			float angle = streakSeeds[i * 4] * 6.283f, reach = 8 + streakSeeds[i * 4 + 1] * 60;
			float fallSpeed = 0.18f + streakSeeds[i * 4 + 2] * 0.12f;
			float phase = (t * fallSpeed + streakSeeds[i * 4 + 3]) % 1f;
			float x = (float) Math.cos(angle) * reach, z = (float) Math.sin(angle) * reach;
			float top = 45 - phase * 60, length = 2.5f + streakSeeds[i * 4 + 2] * 3;
			float width = 0.06f + reach * 0.004f;
			int color = FxRender.fade(s.streak, 0.9f * (1 - phase * 0.6f));
			float rx = right.x * width, rz = right.z * width;
			// The streak texture is bright at the bottom, its leading end
			FxRender.textured(consumer, pose, x - rx, top - length, z - rz, 0, 1, color);
			FxRender.textured(consumer, pose, x + rx, top - length, z + rz, 1, 1, color);
			FxRender.textured(consumer, pose, x + rx, top, z + rz, 1, 0, color);
			FxRender.textured(consumer, pose, x - rx, top, z - rz, 0, 0, color);
		}
	}

	private static float smooth(float from, float to, float x) {
		float t = Math.max(0, Math.min(1, (x - from) / (to - from)));
		return t * t * (3 - 2 * t);
	}
}
