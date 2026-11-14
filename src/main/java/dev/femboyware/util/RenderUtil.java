package dev.femboyware.util;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.femboyware.event.Render3DEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.LayeringTransform;
//? if >=26.3 {
/*
*///?} else {
import net.minecraft.client.renderer.rendertype.OutputTarget;
//?}
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

public final class RenderUtil {
	/** Copies of vanilla's translucent lines and debug quads that skip the depth test, so they draw over terrain. */
	// The lines snippet has no colour target on 26.x (vanilla adds a translucent one per pipeline);
	// without one the pass's attachment count doesn't match and the frame crashes
	private static final RenderPipeline LINES_THROUGH_WALLS_PIPELINE = RenderPipelines.register(Pipelines.blend(Pipelines.depth(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
		.withLocation(Identifier.fromNamespaceAndPath("femboyware", "pipeline/lines_through_walls")), false, false), com.mojang.blaze3d.pipeline.BlendFunction.TRANSLUCENT)
		.build());
	private static final RenderPipeline QUADS_THROUGH_WALLS_PIPELINE = RenderPipelines.register(Pipelines.depth(RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
		.withLocation(Identifier.fromNamespaceAndPath("femboyware", "pipeline/quads_through_walls"))
		.withCull(false), false, false)
		.build());
	private static final RenderType LINES_THROUGH_WALLS = RenderType.create("femboyware_lines_through_walls",
		//? if >=26.3 {
		/*RenderSetup.builder(LINES_THROUGH_WALLS_PIPELINE).setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING).createRenderSetup());
		*///?} else {
		RenderSetup.builder(LINES_THROUGH_WALLS_PIPELINE).setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING).setOutputTarget(OutputTarget.ITEM_ENTITY_TARGET).createRenderSetup());
		//?}
	private static final RenderType QUADS_THROUGH_WALLS = RenderType.create("femboyware_quads_through_walls",
		RenderSetup.builder(QUADS_THROUGH_WALLS_PIPELINE).sortOnUpload().createRenderSetup());

	private RenderUtil() {
	}

	public static void drawBox(Render3DEvent event, AABB box, int fillColor, int lineColor, float lineWidth) {
		drawBox(event, box, fillColor, lineColor, lineWidth, false);
	}

	/** With {@code throughWalls}, every edge and face shows, even behind terrain or the block itself. */
	public static void drawBox(Render3DEvent event, AABB box, int fillColor, int lineColor, float lineWidth, boolean throughWalls) {
		Vec3 cam = event.camera().pos;
		AABB b = box.move(-cam.x, -cam.y, -cam.z);
		PoseStack.Pose entry = event.matrices().last();

		if ((fillColor >>> 24) != 0) {
			VertexConsumer quads = event.consumers().getBuffer(throughWalls ? QUADS_THROUGH_WALLS : RenderTypes.debugQuads());
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
			VertexConsumer lines = event.consumers().getBuffer(throughWalls ? LINES_THROUGH_WALLS : RenderTypes.linesTranslucent());
			boxEdges(lines, entry, b, lineColor, lineWidth);
		}
	}

	/**
	 * Walls fading from {@code topColor} at the top to {@code bottomColor} at the bottom, an optional
	 * top face, and an outline in {@code lineColor}. Used for hole highlights.
	 */
	public static void drawGradientBox(Render3DEvent event, AABB box, int topColor, int bottomColor, int lineColor, boolean topFace, float lineWidth) {
		Vec3 cam = event.camera().pos;
		AABB b = box.move(-cam.x, -cam.y, -cam.z);
		PoseStack.Pose entry = event.matrices().last();
		float x1 = (float) b.minX, y1 = (float) b.minY, z1 = (float) b.minZ;
		float x2 = (float) b.maxX, y2 = (float) b.maxY, z2 = (float) b.maxZ;

		if ((topColor >>> 24) != 0 || (bottomColor >>> 24) != 0) {
			VertexConsumer quads = event.consumers().getBuffer(RenderTypes.debugQuads());
			gradientQuad(quads, entry, topColor, bottomColor, x1, z1, x2, z1, y1, y2);
			gradientQuad(quads, entry, topColor, bottomColor, x2, z2, x1, z2, y1, y2);
			gradientQuad(quads, entry, topColor, bottomColor, x1, z2, x1, z1, y1, y2);
			gradientQuad(quads, entry, topColor, bottomColor, x2, z1, x2, z2, y1, y2);
			if (topFace) quad(quads, entry, topColor, x1, y2, z1, x1, y2, z2, x2, y2, z2, x2, y2, z1);
		}

		if ((lineColor >>> 24) != 0) {
			VertexConsumer lines = event.consumers().getBuffer(RenderTypes.linesTranslucent());
			boxEdges(lines, entry, b, lineColor, lineWidth);
		}
	}

	private static void gradientQuad(VertexConsumer consumer, PoseStack.Pose entry, int top, int bottom,
									 float ax, float az, float bx, float bz, float y1, float y2) {
		consumer.addVertex(entry, ax, y1, az).setColor(bottom);
		consumer.addVertex(entry, ax, y2, az).setColor(top);
		consumer.addVertex(entry, bx, y2, bz).setColor(top);
		consumer.addVertex(entry, bx, y1, bz).setColor(bottom);
	}

	/** A point just in front of the camera, so tracers drawn from it meet at the crosshair. */
	public static Vec3 tracerOrigin(Render3DEvent event) {
		Vector3f forward = new Vector3f(0, 0, -1).rotate(event.camera().orientation);
		return event.camera().pos.add(forward.x, forward.y, forward.z);
	}

	public static void drawLine(Render3DEvent event, Vec3 from, Vec3 to, int color, float lineWidth) {
		drawLine(event, from, to, color, lineWidth, false);
	}

	public static void drawLine(Render3DEvent event, Vec3 from, Vec3 to, int color, float lineWidth, boolean throughWalls) {
		Vec3 cam = event.camera().pos;
		PoseStack.Pose entry = event.matrices().last();
		VertexConsumer lines = event.consumers().getBuffer(throughWalls ? LINES_THROUGH_WALLS : RenderTypes.linesTranslucent());
		Vector3f normal = to.subtract(from).toVector3f().normalize();
		lines.addVertex(entry, (float) (from.x - cam.x), (float) (from.y - cam.y), (float) (from.z - cam.z)).setColor(color).setNormal(entry, normal).setLineWidth(lineWidth);
		lines.addVertex(entry, (float) (to.x - cam.x), (float) (to.y - cam.y), (float) (to.z - cam.z)).setColor(color).setNormal(entry, normal).setLineWidth(lineWidth);
	}

	/** The 12 edges of a box already relative to the camera. */
	private static void boxEdges(VertexConsumer lines, PoseStack.Pose entry, AABB b, int color, float width) {
		float x1 = (float) b.minX, y1 = (float) b.minY, z1 = (float) b.minZ;
		float x2 = (float) b.maxX, y2 = (float) b.maxY, z2 = (float) b.maxZ;
		edge(lines, entry, x1, y1, z1, x2, y1, z1, color, width);
		edge(lines, entry, x2, y1, z1, x2, y1, z2, color, width);
		edge(lines, entry, x2, y1, z2, x1, y1, z2, color, width);
		edge(lines, entry, x1, y1, z2, x1, y1, z1, color, width);
		edge(lines, entry, x1, y2, z1, x2, y2, z1, color, width);
		edge(lines, entry, x2, y2, z1, x2, y2, z2, color, width);
		edge(lines, entry, x2, y2, z2, x1, y2, z2, color, width);
		edge(lines, entry, x1, y2, z2, x1, y2, z1, color, width);
		edge(lines, entry, x1, y1, z1, x1, y2, z1, color, width);
		edge(lines, entry, x2, y1, z1, x2, y2, z1, color, width);
		edge(lines, entry, x2, y1, z2, x2, y2, z2, color, width);
		edge(lines, entry, x1, y1, z2, x1, y2, z2, color, width);
	}

	private static void edge(VertexConsumer lines, PoseStack.Pose entry, float ax, float ay, float az, float bx, float by, float bz, int color, float width) {
		Vector3f normal = new Vector3f(bx - ax, by - ay, bz - az).normalize();
		lines.addVertex(entry, ax, ay, az).setColor(color).setNormal(entry, normal).setLineWidth(width);
		lines.addVertex(entry, bx, by, bz).setColor(color).setNormal(entry, normal).setLineWidth(width);
	}

	private static void quad(VertexConsumer consumer, PoseStack.Pose entry, int color,
							 float ax, float ay, float az, float bx, float by, float bz,
							 float cx, float cy, float cz, float dx, float dy, float dz) {
		consumer.addVertex(entry, ax, ay, az).setColor(color);
		consumer.addVertex(entry, bx, by, bz).setColor(color);
		consumer.addVertex(entry, cx, cy, cz).setColor(color);
		consumer.addVertex(entry, dx, dy, dz).setColor(color);
	}

	/** Draws camera-facing text centred on {@code pos}. */
	public static void drawText(Render3DEvent event, String text, Vec3 pos, int color, float scale) {
		Font textRenderer = Minecraft.getInstance().font;
		Vec3 cam = event.camera().pos;
		PoseStack matrices = event.matrices();
		matrices.pushPose();
		matrices.translate((float) (pos.x - cam.x), (float) (pos.y - cam.y), (float) (pos.z - cam.z));
		matrices.mulPose(event.camera().orientation);
		matrices.scale(scale / 16f, -scale / 16f, scale / 16f);
		float x = -textRenderer.width(text) / 2f;
		event.consumers().drawText(textRenderer, text, x, -textRenderer.lineHeight / 2f, color, true, matrices.last().pose(),
			Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
		matrices.popPose();
	}
}
