package dev.femboyware.event;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
//? if >=26.1 {
/*import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.renderer.SubmitNodeCollector;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
*///?} else {
import net.minecraft.client.renderer.MultiBufferSource;
//?}

/**
 * Where world rendering gets its vertex buffers. Ask for a layer's buffer with {@link #getBuffer},
 * and call {@link #endBatch} to draw what's been filled so far, so anything after it goes on top.
 * <p>
 * On 1.21 this wraps the world's buffer source. From 26.1 there is none: vertices are recorded,
 * already transformed, and handed to the frame's submit collector when the batch ends, each batch
 * in its own order so later batches draw over earlier ones.
 */
public final class WorldBuffers {
	//? if >=26.1 {
	/*private final SubmitNodeCollector collector;
	private final Map<RenderType, Recorder> pending = new LinkedHashMap<>();
	private int order;

	public WorldBuffers(SubmitNodeCollector collector) {
		this.collector = collector;
	}

	public VertexConsumer getBuffer(RenderType layer) {
		return pending.computeIfAbsent(layer, key -> new Recorder());
	}

	public void endBatch() {
		if (pending.isEmpty()) return;
		var ordered = collector.order(order++);
		PoseStack identity = new PoseStack();
		for (Map.Entry<RenderType, Recorder> entry : pending.entrySet()) {
			Recorder recorder = entry.getValue();
			if (recorder.count > 0) ordered.submitCustomGeometry(identity, entry.getKey(), (pose, consumer) -> recorder.replay(consumer));
		}
		pending.clear();
	}

	public void drawText(Font font, Component text, float x, float y, int color, boolean shadow, Matrix4f matrix,
						 Font.DisplayMode mode, int background, int light) {
		visitText(font.prepareText(text.getVisualOrderText(), x, y, color, shadow, false, background), matrix, mode, light);
	}

	public void drawText(Font font, String text, float x, float y, int color, boolean shadow, Matrix4f matrix,
						 Font.DisplayMode mode, int background, int light) {
		visitText(font.prepareText(text, x, y, color, shadow, background), matrix, mode, light);
	}

	private void visitText(Font.PreparedText prepared, Matrix4f matrix, Font.DisplayMode mode, int light) {
		prepared.visit(new Font.GlyphVisitor() {
			@Override
			public void acceptRenderable(TextRenderable renderable) {
				renderable.render(matrix, getBuffer(renderable.renderType(mode)), light, false);
			}
		});
	}

	/^* Stores vertices in memory so they can be written to the real buffer later. *^/
	private static final class Recorder implements VertexConsumer {
		private static final int POS = 3, COLOR = 1, UV = 2, UV1 = 2, UV2 = 2, NORMAL = 3, WIDTH = 1;
		private static final int STRIDE = POS + COLOR + UV + UV1 + UV2 + NORMAL + WIDTH + 1;
		private static final int O_COLOR = POS, O_UV = O_COLOR + COLOR, O_UV1 = O_UV + UV, O_UV2 = O_UV1 + UV1,
			O_NORMAL = O_UV2 + UV2, O_WIDTH = O_NORMAL + NORMAL, O_FLAGS = O_WIDTH + WIDTH;
		private static final int F_COLOR = 1, F_UV = 2, F_UV1 = 4, F_UV2 = 8, F_NORMAL = 16, F_WIDTH = 32;

		/^* Floats for positions, uvs, normals and widths; ints are stored by their bits. *^/
		private float[] data = new float[STRIDE * 64];
		private int count;

		private int base() {
			return (count - 1) * STRIDE;
		}

		private void flag(int flag) {
			int i = base() + O_FLAGS;
			data[i] = Float.intBitsToFloat(Float.floatToRawIntBits(data[i]) | flag);
		}

		@Override
		public VertexConsumer addVertex(float x, float y, float z) {
			if ((count + 1) * STRIDE > data.length) data = Arrays.copyOf(data, data.length * 2);
			count++;
			int b = base();
			Arrays.fill(data, b, b + STRIDE, 0);
			data[b] = x;
			data[b + 1] = y;
			data[b + 2] = z;
			return this;
		}

		@Override
		public VertexConsumer setColor(int red, int green, int blue, int alpha) {
			return setColor((alpha & 0xFF) << 24 | (red & 0xFF) << 16 | (green & 0xFF) << 8 | blue & 0xFF);
		}

		@Override
		public VertexConsumer setColor(int argb) {
			data[base() + O_COLOR] = Float.intBitsToFloat(argb);
			flag(F_COLOR);
			return this;
		}

		@Override
		public VertexConsumer setUv(float u, float v) {
			data[base() + O_UV] = u;
			data[base() + O_UV + 1] = v;
			flag(F_UV);
			return this;
		}

		@Override
		public VertexConsumer setUv1(int u, int v) {
			data[base() + O_UV1] = Float.intBitsToFloat(u);
			data[base() + O_UV1 + 1] = Float.intBitsToFloat(v);
			flag(F_UV1);
			return this;
		}

		@Override
		public VertexConsumer setUv2(int u, int v) {
			data[base() + O_UV2] = Float.intBitsToFloat(u);
			data[base() + O_UV2 + 1] = Float.intBitsToFloat(v);
			flag(F_UV2);
			return this;
		}

		/^* 26.3's extra uv (glint only): not recorded. *^/
		public VertexConsumer setUv3(float u, float v) {
			return this;
		}

		@Override
		public VertexConsumer setNormal(float x, float y, float z) {
			data[base() + O_NORMAL] = x;
			data[base() + O_NORMAL + 1] = y;
			data[base() + O_NORMAL + 2] = z;
			flag(F_NORMAL);
			return this;
		}

		@Override
		public VertexConsumer setLineWidth(float width) {
			data[base() + O_WIDTH] = width;
			flag(F_WIDTH);
			return this;
		}

		void replay(VertexConsumer out) {
			for (int v = 0; v < count; v++) {
				int b = v * STRIDE;
				int flags = Float.floatToRawIntBits(data[b + O_FLAGS]);
				out.addVertex(data[b], data[b + 1], data[b + 2]);
				if ((flags & F_COLOR) != 0) out.setColor(Float.floatToRawIntBits(data[b + O_COLOR]));
				if ((flags & F_UV) != 0) out.setUv(data[b + O_UV], data[b + O_UV + 1]);
				if ((flags & F_UV1) != 0) out.setUv1(Float.floatToRawIntBits(data[b + O_UV1]), Float.floatToRawIntBits(data[b + O_UV1 + 1]));
				if ((flags & F_UV2) != 0) out.setUv2(Float.floatToRawIntBits(data[b + O_UV2]), Float.floatToRawIntBits(data[b + O_UV2 + 1]));
				if ((flags & F_NORMAL) != 0) out.setNormal(data[b + O_NORMAL], data[b + O_NORMAL + 1], data[b + O_NORMAL + 2]);
				if ((flags & F_WIDTH) != 0) out.setLineWidth(data[b + O_WIDTH]);
			}
		}
	}
	*///?} else {
	private final MultiBufferSource source;

	public WorldBuffers(MultiBufferSource source) {
		this.source = source;
	}

	public VertexConsumer getBuffer(RenderType layer) {
		return source.getBuffer(layer);
	}

	public void endBatch() {
		if (source instanceof MultiBufferSource.BufferSource immediate) immediate.endBatch();
	}

	public void drawText(Font font, Component text, float x, float y, int color, boolean shadow, Matrix4f matrix,
						 Font.DisplayMode mode, int background, int light) {
		font.drawInBatch(text, x, y, color, shadow, matrix, source, mode, background, light);
	}

	public void drawText(Font font, String text, float x, float y, int color, boolean shadow, Matrix4f matrix,
						 Font.DisplayMode mode, int background, int light) {
		font.drawInBatch(text, x, y, color, shadow, matrix, source, mode, background, light);
	}
	//?}
}
