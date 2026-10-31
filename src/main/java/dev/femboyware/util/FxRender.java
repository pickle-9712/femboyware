package dev.femboyware.util;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import dev.femboyware.event.Render3DEvent;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

/**
 * Drawing for the Targets and Effects tabs: soft glow sprites, flat colour shapes and textured
 * quads, each either blended normally or added on top as light, and either hidden behind terrain
 * or drawn through it. Positions passed as {@link Vec3d} are world positions; the float overloads
 * take coordinates already in the current matrix's space.
 * <p>
 * Asking for one layer's buffer draws and closes whichever other layer was being filled, so never
 * hold a buffer across a call that gets another: draw in passes, one layer at a time.
 */
public final class FxRender {
	public static final Identifier GLOW = Identifier.of("femboyware", "textures/fx/glow.png");

	private static final RenderPipeline QUADS = colorPipeline("fx_quads", BlendFunction.TRANSLUCENT, true);
	private static final RenderPipeline QUADS_XRAY = colorPipeline("fx_quads_xray", BlendFunction.TRANSLUCENT, false);
	private static final RenderPipeline LIGHT = colorPipeline("fx_light", BlendFunction.LIGHTNING, true);
	private static final RenderPipeline LIGHT_XRAY = colorPipeline("fx_light_xray", BlendFunction.LIGHTNING, false);
	/** Solid colour shapes like ears and tails write depth, so their far sides stay hidden. */
	private static final RenderPipeline SOLID_QUADS = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.POSITION_COLOR_SNIPPET)
		.withLocation(Identifier.of("femboyware", "pipeline/fx_solid_quads"))
		.withCull(false)
		.withDepthWrite(true)
		.withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
		.build());
	private static final RenderPipeline SPRITES = texturePipeline("fx_sprites", BlendFunction.TRANSLUCENT, true, false);
	private static final RenderPipeline SPRITES_XRAY = texturePipeline("fx_sprites_xray", BlendFunction.TRANSLUCENT, false, false);
	private static final RenderPipeline GLOWS = texturePipeline("fx_glows", BlendFunction.LIGHTNING, true, false);
	private static final RenderPipeline GLOWS_XRAY = texturePipeline("fx_glows_xray", BlendFunction.LIGHTNING, false, false);
	/** Solid textured things like coins write depth, so their faces hide each other properly. */
	private static final RenderPipeline SOLID_SPRITES = texturePipeline("fx_solid_sprites", BlendFunction.TRANSLUCENT, true, true);

	private static final RenderLayer QUADS_LAYER = colorLayer("fx_quads", QUADS);
	private static final RenderLayer QUADS_XRAY_LAYER = colorLayer("fx_quads_xray", QUADS_XRAY);
	private static final RenderLayer LIGHT_LAYER = colorLayer("fx_light", LIGHT);
	private static final RenderLayer LIGHT_XRAY_LAYER = colorLayer("fx_light_xray", LIGHT_XRAY);
	private static final RenderLayer SOLID_QUADS_LAYER = colorLayer("fx_solid_quads", SOLID_QUADS);
	private static final Map<String, RenderLayer> TEXTURE_LAYERS = new HashMap<>();

	private static final int FULL_ALPHA = 0xFF000000;

	private FxRender() {
	}

	private static RenderPipeline colorPipeline(String name, BlendFunction blend, boolean depthTest) {
		return RenderPipelines.register(RenderPipeline.builder(RenderPipelines.POSITION_COLOR_SNIPPET)
			.withLocation(Identifier.of("femboyware", "pipeline/" + name))
			.withBlend(blend)
			.withCull(false)
			.withDepthWrite(false)
			.withDepthTestFunction(depthTest ? DepthTestFunction.LEQUAL_DEPTH_TEST : DepthTestFunction.NO_DEPTH_TEST)
			.build());
	}

	private static RenderPipeline texturePipeline(String name, BlendFunction blend, boolean depthTest, boolean depthWrite) {
		return RenderPipelines.register(RenderPipeline.builder(RenderPipelines.POSITION_TEX_COLOR_SNIPPET)
			.withLocation(Identifier.of("femboyware", "pipeline/" + name))
			.withBlend(blend)
			.withCull(false)
			.withDepthWrite(depthWrite)
			.withDepthTestFunction(depthTest ? DepthTestFunction.LEQUAL_DEPTH_TEST : DepthTestFunction.NO_DEPTH_TEST)
			.build());
	}

	private static RenderLayer colorLayer(String name, RenderPipeline pipeline) {
		return RenderLayer.of("femboyware_" + name, RenderSetup.builder(pipeline).translucent().build());
	}

	private static RenderLayer textureLayer(RenderPipeline pipeline, String name, Identifier texture) {
		return TEXTURE_LAYERS.computeIfAbsent(name + "/" + texture, key ->
			RenderLayer.of("femboyware_" + name, RenderSetup.builder(pipeline).texture("Sampler0", texture).translucent().build()));
	}

	/** Flat colour quads: normally blended, or added as light with {@code light}. */
	public static VertexConsumer quads(Render3DEvent event, boolean light, boolean throughWalls) {
		RenderLayer layer = light ? (throughWalls ? LIGHT_XRAY_LAYER : LIGHT_LAYER) : (throughWalls ? QUADS_XRAY_LAYER : QUADS_LAYER);
		return event.consumers().getBuffer(layer);
	}

	/** Textured quads; with {@code light} the texture is added as light, so overlapping glows brighten. */
	public static VertexConsumer textured(Render3DEvent event, Identifier texture, boolean light, boolean throughWalls) {
		RenderPipeline pipeline = light ? (throughWalls ? GLOWS_XRAY : GLOWS) : (throughWalls ? SPRITES_XRAY : SPRITES);
		return event.consumers().getBuffer(textureLayer(pipeline, pipeline.getLocation().getPath(), texture));
	}

	/** Textured quads that hide what's behind them, for solid objects like coins. */
	public static VertexConsumer solid(Render3DEvent event, Identifier texture) {
		return event.consumers().getBuffer(textureLayer(SOLID_SPRITES, "fx_solid_sprites", texture));
	}

	/** Flat colour quads that hide what's behind them, for solid shapes. */
	public static VertexConsumer solidQuads(Render3DEvent event) {
		return event.consumers().getBuffer(SOLID_QUADS_LAYER);
	}

	public static VertexConsumer lines(Render3DEvent event) {
		return event.consumers().getBuffer(RenderLayers.linesTranslucent());
	}

	/** Soft round glow facing the camera, {@code radius} blocks from its centre to its edge. */
	public static void glow(Render3DEvent event, VertexConsumer consumer, Vec3d pos, float radius, int color) {
		Vec3d cam = event.camera().pos;
		billboard(event, consumer, (float) (pos.x - cam.x), (float) (pos.y - cam.y), (float) (pos.z - cam.z), radius, 0, color);
	}

	/** A camera-facing square around a point already relative to the camera, turned {@code roll} radians on screen. */
	public static void billboard(Render3DEvent event, VertexConsumer consumer, float x, float y, float z, float radius, float roll, int color) {
		Quaternionf orientation = event.camera().orientation;
		float cos = (float) Math.cos(roll) * radius, sin = (float) Math.sin(roll) * radius;
		Vector3f right = new Vector3f(cos, sin, 0).rotate(orientation);
		Vector3f up = new Vector3f(-sin, cos, 0).rotate(orientation);
		MatrixStack.Entry entry = event.matrices().peek();
		textured(consumer, entry, x - right.x - up.x, y - right.y - up.y, z - right.z - up.z, 0, 1, color);
		textured(consumer, entry, x + right.x - up.x, y + right.y - up.y, z + right.z - up.z, 1, 1, color);
		textured(consumer, entry, x + right.x + up.x, y + right.y + up.y, z + right.z + up.z, 1, 0, color);
		textured(consumer, entry, x - right.x + up.x, y - right.y + up.y, z - right.z + up.z, 0, 0, color);
	}

	public static void textured(VertexConsumer consumer, MatrixStack.Entry entry, float x, float y, float z, float u, float v, int color) {
		consumer.vertex(entry, x, y, z).texture(u, v).color(color);
	}

	public static void vertex(VertexConsumer consumer, MatrixStack.Entry entry, float x, float y, float z, int color) {
		consumer.vertex(entry, x, y, z).color(color);
	}

	/** A quad with its own colour at each corner, so shapes can fade across their face. */
	public static void quad(VertexConsumer consumer, MatrixStack.Entry entry,
							float ax, float ay, float az, int ca, float bx, float by, float bz, int cb,
							float cx, float cy, float cz, int cc, float dx, float dy, float dz, int cd) {
		vertex(consumer, entry, ax, ay, az, ca);
		vertex(consumer, entry, bx, by, bz, cb);
		vertex(consumer, entry, cx, cy, cz, cc);
		vertex(consumer, entry, dx, dy, dz, cd);
	}

	/** A triangle, drawn as a quad with its last corner doubled. */
	public static void triangle(VertexConsumer consumer, MatrixStack.Entry entry,
								float ax, float ay, float az, int ca, float bx, float by, float bz, int cb, float cx, float cy, float cz, int cc) {
		quad(consumer, entry, ax, ay, az, ca, bx, by, bz, cb, cx, cy, cz, cc, cx, cy, cz, cc);
	}

	/** A strip of {@code width} along the segment, lying flat in the XY plane of the current matrix. */
	public static void strokeXY(VertexConsumer consumer, MatrixStack.Entry entry, float x1, float y1, float x2, float y2, float width, int c1, int c2) {
		float dx = x2 - x1, dy = y2 - y1;
		float length = (float) Math.sqrt(dx * dx + dy * dy);
		if (length < 1e-5f) return;
		float nx = -dy / length * width / 2, ny = dx / length * width / 2;
		quad(consumer, entry, x1 - nx, y1 - ny, 0, c1, x2 - nx, y2 - ny, 0, c2, x2 + nx, y2 + ny, 0, c2, x1 + nx, y1 + ny, 0, c1);
	}

	/** The same strip lying flat on the ground, in the XZ plane. */
	public static void strokeXZ(VertexConsumer consumer, MatrixStack.Entry entry, float x1, float z1, float x2, float z2, float y, float width, int c1, int c2) {
		float dx = x2 - x1, dz = z2 - z1;
		float length = (float) Math.sqrt(dx * dx + dz * dz);
		if (length < 1e-5f) return;
		float nx = -dz / length * width / 2, nz = dx / length * width / 2;
		quad(consumer, entry, x1 - nx, y, z1 - nz, c1, x2 - nx, y, z2 - nz, c2, x2 + nx, y, z2 + nz, c2, x1 + nx, y, z1 + nz, c1);
	}

	/**
	 * A flat ring around the origin of the current matrix in its XZ plane, from {@code inner} to
	 * {@code outer} radius. Colours come from {@code color}, given how far round the ring (0 to 1) and
	 * whether it's the inner edge.
	 */
	public static void ring(VertexConsumer consumer, MatrixStack.Entry entry, float inner, float outer, float y, int segments, RingColor color) {
		for (int i = 0; i < segments; i++) {
			float t1 = (float) i / segments, t2 = (float) (i + 1) / segments;
			double a1 = t1 * Math.PI * 2, a2 = t2 * Math.PI * 2;
			float c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1), c2 = (float) Math.cos(a2), s2 = (float) Math.sin(a2);
			quad(consumer, entry,
				c1 * inner, y, s1 * inner, color.at(t1, true),
				c2 * inner, y, s2 * inner, color.at(t2, true),
				c2 * outer, y, s2 * outer, color.at(t2, false),
				c1 * outer, y, s1 * outer, color.at(t1, false));
		}
	}

	@FunctionalInterface
	public interface RingColor {
		int at(float around, boolean inner);
	}

	public static void line(VertexConsumer consumer, MatrixStack.Entry entry, float x1, float y1, float z1, float x2, float y2, float z2, int color, float width) {
		Vector3f normal = new Vector3f(x2 - x1, y2 - y1, z2 - z1);
		if (normal.lengthSquared() < 1e-10f) return;
		normal.normalize();
		consumer.vertex(entry, x1, y1, z1).color(color).normal(entry, normal).lineWidth(width);
		consumer.vertex(entry, x2, y2, z2).color(color).normal(entry, normal).lineWidth(width);
	}

	/** {@code color} with its alpha multiplied by {@code alpha}. */
	public static int fade(int color, float alpha) {
		int a = (int) ((color >>> 24) * Math.max(0, Math.min(1, alpha)));
		return (color & 0x00FFFFFF) | (a << 24);
	}

	/** Mixes two colours, alpha included; {@code t} = 0 gives {@code a}. */
	public static int mix(int a, int b, float t) {
		t = Math.max(0, Math.min(1, t));
		int aa = a >>> 24, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
		int ba = b >>> 24, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
		return ((int) (aa + (ba - aa) * t) << 24) | ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
	}

	/** {@code color} lightened towards white by {@code t}, alpha kept. */
	public static int lighten(int color, float t) {
		return mix(color, color | 0x00FFFFFF, t);
	}

	/** {@code color} darkened towards black by {@code t}, alpha kept. */
	public static int darken(int color, float t) {
		return mix(color, color & 0xFF000000, t);
	}

	public static int opaque(int color) {
		return color | FULL_ALPHA;
	}
}
