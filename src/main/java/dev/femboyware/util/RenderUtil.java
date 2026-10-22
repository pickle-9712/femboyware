package dev.femboyware.util;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import dev.femboyware.event.Render3DEvent;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.LayeringTransform;
import net.minecraft.client.render.OutputTarget;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShapes;
import org.joml.Vector3f;

public final class RenderUtil {
	/** Copies of vanilla's translucent lines and debug quads that skip the depth test, so they draw over terrain. */
	private static final RenderPipeline LINES_THROUGH_WALLS_PIPELINE = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.RENDERTYPE_LINES_SNIPPET)
		.withLocation(Identifier.of("femboyware", "pipeline/lines_through_walls"))
		.withDepthWrite(false)
		.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
		.build());
	private static final RenderPipeline QUADS_THROUGH_WALLS_PIPELINE = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.POSITION_COLOR_SNIPPET)
		.withLocation(Identifier.of("femboyware", "pipeline/quads_through_walls"))
		.withCull(false)
		.withDepthWrite(false)
		.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
		.build());
	private static final RenderLayer LINES_THROUGH_WALLS = RenderLayer.of("femboyware_lines_through_walls",
		RenderSetup.builder(LINES_THROUGH_WALLS_PIPELINE).layeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING).outputTarget(OutputTarget.ITEM_ENTITY_TARGET).build());
	private static final RenderLayer QUADS_THROUGH_WALLS = RenderLayer.of("femboyware_quads_through_walls",
		RenderSetup.builder(QUADS_THROUGH_WALLS_PIPELINE).translucent().build());

	private RenderUtil() {
	}

	public static void drawBox(Render3DEvent event, Box box, int fillColor, int lineColor, float lineWidth) {
		drawBox(event, box, fillColor, lineColor, lineWidth, false);
	}

	/** With {@code throughWalls}, every edge and face shows, even behind terrain or the block itself. */
	public static void drawBox(Render3DEvent event, Box box, int fillColor, int lineColor, float lineWidth, boolean throughWalls) {
		Vec3d cam = event.camera().pos;
		Box b = box.offset(-cam.x, -cam.y, -cam.z);
		MatrixStack.Entry entry = event.matrices().peek();

		if ((fillColor >>> 24) != 0) {
			VertexConsumer quads = event.consumers().getBuffer(throughWalls ? QUADS_THROUGH_WALLS : RenderLayers.debugQuads());
			float x1 = (float) b.minX, y1 = (float) b.minY, z1 = (float) b.minZ;
			float x2 = (float) b.maxX, y2 = (float) b.maxY, z2 = (float) b.maxZ;
			quad(quads, entry, fillColor, x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2);
			quad(quads, entry, fillColor, x1, y2, z1, x1, y2, z2, x2, y2, z2, x2, y2, z1);
			quad(quads, entry, fillColor, x1, y1, z1, x1, y2, z1, x2, y2, z1, x2, y1, z1);
			quad(quads, entry, fillColor, x1, y1, z2, x2, y1, z2, x2, y2, z2, x1, y2, z2);
			quad(quads, entry, fillColor, x1, y1, z1, x1, y1, z2, x1, y2, z2, x1, y2, z1);
			quad(quads, entry, fillColor, x2, y1, z1, x2, y2, z1, x2, y2, z2, x2, y1, z2);
		}

		if ((lineColor >>> 24) != 0) {
			VertexConsumer lines = event.consumers().getBuffer(throughWalls ? LINES_THROUGH_WALLS : RenderLayers.linesTranslucent());
			VertexRendering.drawOutline(event.matrices(), lines, VoxelShapes.cuboid(b), 0, 0, 0, lineColor, lineWidth);
		}
	}

	/**
	 * Walls fading from {@code topColor} at the top to {@code bottomColor} at the bottom, an optional
	 * top face, and an outline in {@code lineColor}. Used for hole highlights.
	 */
	public static void drawGradientBox(Render3DEvent event, Box box, int topColor, int bottomColor, int lineColor, boolean topFace, float lineWidth) {
		Vec3d cam = event.camera().pos;
		Box b = box.offset(-cam.x, -cam.y, -cam.z);
		MatrixStack.Entry entry = event.matrices().peek();
		float x1 = (float) b.minX, y1 = (float) b.minY, z1 = (float) b.minZ;
		float x2 = (float) b.maxX, y2 = (float) b.maxY, z2 = (float) b.maxZ;

		if ((topColor >>> 24) != 0 || (bottomColor >>> 24) != 0) {
			VertexConsumer quads = event.consumers().getBuffer(RenderLayers.debugQuads());
			gradientQuad(quads, entry, topColor, bottomColor, x1, z1, x2, z1, y1, y2);
			gradientQuad(quads, entry, topColor, bottomColor, x2, z2, x1, z2, y1, y2);
			gradientQuad(quads, entry, topColor, bottomColor, x1, z2, x1, z1, y1, y2);
			gradientQuad(quads, entry, topColor, bottomColor, x2, z1, x2, z2, y1, y2);
			if (topFace) quad(quads, entry, topColor, x1, y2, z1, x1, y2, z2, x2, y2, z2, x2, y2, z1);
		}

		if ((lineColor >>> 24) != 0) {
			VertexConsumer lines = event.consumers().getBuffer(RenderLayers.linesTranslucent());
			VertexRendering.drawOutline(event.matrices(), lines, VoxelShapes.cuboid(b), 0, 0, 0, lineColor, lineWidth);
		}
	}

	private static void gradientQuad(VertexConsumer consumer, MatrixStack.Entry entry, int top, int bottom,
									 float ax, float az, float bx, float bz, float y1, float y2) {
		consumer.vertex(entry, ax, y1, az).color(bottom);
		consumer.vertex(entry, ax, y2, az).color(top);
		consumer.vertex(entry, bx, y2, bz).color(top);
		consumer.vertex(entry, bx, y1, bz).color(bottom);
	}

	/** A point just in front of the camera, so tracers drawn from it meet at the crosshair. */
	public static Vec3d tracerOrigin(Render3DEvent event) {
		Vector3f forward = new Vector3f(0, 0, -1).rotate(event.camera().orientation);
		return event.camera().pos.add(forward.x, forward.y, forward.z);
	}

	public static void drawLine(Render3DEvent event, Vec3d from, Vec3d to, int color, float lineWidth) {
		drawLine(event, from, to, color, lineWidth, false);
	}

	public static void drawLine(Render3DEvent event, Vec3d from, Vec3d to, int color, float lineWidth, boolean throughWalls) {
		Vec3d cam = event.camera().pos;
		MatrixStack.Entry entry = event.matrices().peek();
		VertexConsumer lines = event.consumers().getBuffer(throughWalls ? LINES_THROUGH_WALLS : RenderLayers.linesTranslucent());
		Vector3f normal = to.subtract(from).toVector3f().normalize();
		lines.vertex(entry, (float) (from.x - cam.x), (float) (from.y - cam.y), (float) (from.z - cam.z)).color(color).normal(entry, normal).lineWidth(lineWidth);
		lines.vertex(entry, (float) (to.x - cam.x), (float) (to.y - cam.y), (float) (to.z - cam.z)).color(color).normal(entry, normal).lineWidth(lineWidth);
	}

	private static void quad(VertexConsumer consumer, MatrixStack.Entry entry, int color,
							 float ax, float ay, float az, float bx, float by, float bz,
							 float cx, float cy, float cz, float dx, float dy, float dz) {
		consumer.vertex(entry, ax, ay, az).color(color);
		consumer.vertex(entry, bx, by, bz).color(color);
		consumer.vertex(entry, cx, cy, cz).color(color);
		consumer.vertex(entry, dx, dy, dz).color(color);
	}

	/** Draws camera-facing text centred on {@code pos}. */
	public static void drawText(Render3DEvent event, String text, Vec3d pos, int color, float scale) {
		TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;
		Vec3d cam = event.camera().pos;
		MatrixStack matrices = event.matrices();
		matrices.push();
		matrices.translate((float) (pos.x - cam.x), (float) (pos.y - cam.y), (float) (pos.z - cam.z));
		matrices.multiply(event.camera().orientation);
		matrices.scale(scale / 16f, -scale / 16f, scale / 16f);
		float x = -textRenderer.getWidth(text) / 2f;
		textRenderer.draw(text, x, -textRenderer.fontHeight / 2f, color, true, matrices.peek().getPositionMatrix(),
			event.consumers(), TextRenderer.TextLayerType.SEE_THROUGH, 0, LightmapTextureManager.MAX_LIGHT_COORDINATE);
		matrices.pop();
	}
}
