package dev.femboyware.module.effects;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.FxRender;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

import java.util.List;

/**
 * Animal ears on your head and a tail behind you: cat, fox or bunny. Ears twitch now and then and
 * flatten when you sneak; tails sway as you stand and wag faster as you run.
 */
public class NekoModule extends PlayerFxModule {
	public enum Style { CAT, FOX, BUNNY }

	private static final int TAIL_JOINTS = 14;
	private static final int TAIL_SIDES = 8;

	private final EnumSetting<Style> style = add(new EnumSetting<>("Style", "Cat, fox or bunny", Style.CAT));
	private final BoolSetting ears = add(new BoolSetting("Ears", "Ears on your head", true));
	private final BoolSetting tail = add(new BoolSetting("Tail", "A tail behind you", true));
	private final NumberSetting size = add(new NumberSetting("Size", "How big the ears and tail are", 1, 0.5, 2, 0.05));
	private final NumberSetting wag = add(new NumberSetting("Wag Speed", "How fast the tail sways", 1, 0, 4, 0.05)).visibleWhen(tail::get);
	private final ColorSetting fur = add(new ColorSetting("Fur", "Main fur colour", 0xFFFFD6EA));
	private final ColorSetting inner = add(new ColorSetting("Inner Ear", "Colour inside the ears", 0xFFFF8AC8));
	private final ColorSetting tip = add(new ColorSetting("Tip", "Fox ear and tail tips", 0xFFFFFFFF)).visibleWhen(() -> style.is(Style.FOX));
	private final EnumSetting<ShowOn> showOn = showOnSetting();

	public NekoModule() {
		super("Neko", "Cat, fox or bunny ears and a tail");
	}

	@Override
	public String getInfo() {
		return style.getDisplayName();
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		List<PlayerEntity> wearers = wearers(false);
		if (wearers.isEmpty()) return;
		float tickDelta = tickDelta();
		Vec3d cam = event.camera().pos;
		MatrixStack matrices = event.matrices();
		VertexConsumer consumer = FxRender.solidQuads(event);
		float s = size.getFloat();
		for (PlayerEntity player : wearers) {
			double time = time() + player.getId() * 3.1;
			if (ears.get()) {
				matrices.push();
				toHead(matrices, player, cam.x, cam.y, cam.z, tickDelta);
				matrices.scale(s, s, s);
				for (int side = -1; side <= 1; side += 2) {
					matrices.push();
					matrices.translate(side * (style.is(Style.BUNNY) ? 0.1f : 0.15f), -0.01f, -0.02f);
					// An occasional quick twitch, each ear on its own beat
					float twitch = (float) Math.pow(Math.max(0, Math.sin(time * 1.3 + side * 2.1)), 30) * 18;
					matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(side * (12 + twitch)));
					if (player.isInSneakingPose()) matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-30));
					ear(consumer, matrices, side, time);
					matrices.pop();
				}
				matrices.pop();
			}
			if (tail.get()) {
				matrices.push();
				toBody(matrices, player, cam.x, cam.y, cam.z, tickDelta);
				matrices.translate(0, 0.72f, -0.2f);
				matrices.scale(s, s, s);
				float speed = (float) Math.min(1, player.getVelocity().horizontalLength() * 6);
				tail(consumer, matrices.peek(), time * wag.get() * (1.6 + speed * 3), 0.6f + speed * 0.4f);
				matrices.pop();
			}
		}
	}

	private void ear(VertexConsumer consumer, MatrixStack matrices, int side, double time) {
		int outer = FxRender.opaque(fur.get());
		int innerColor = FxRender.opaque(inner.get());
		int shade = FxRender.darken(outer, 0.15f);
		MatrixStack.Entry pose = matrices.peek();
		switch (style.get()) {
			case CAT -> {
				prism(consumer, pose, -0.12f, 0, 0.12f, 0, 0.015f * side, 0.27f, 0.03f, outer, shade);
				triangle(consumer, pose, -0.075f, 0.025f, 0.075f, 0.025f, 0.015f * side, 0.2f, 0.031f, innerColor);
			}
			case FOX -> {
				int tipColor = FxRender.opaque(tip.get());
				prism(consumer, pose, -0.1f, 0, 0.1f, 0, 0.015f * side, 0.27f, 0.025f, outer, shade);
				triangle(consumer, pose, -0.06f, 0.02f, 0.06f, 0.02f, 0.015f * side, 0.2f, 0.026f, innerColor);
				// Tipped in the tip colour, like a real fox
				prism(consumer, pose, -0.033f, 0.18f, 0.039f, 0.18f, 0.015f * side, 0.275f, 0.027f, tipColor, tipColor);
			}
			case BUNNY -> {
				// Long ears in two halves; the top half flops back and sways
				box(consumer, pose, -0.05f, 0, 0.05f, 0.22f, 0.025f, outer, shade, innerColor);
				matrices.push();
				matrices.translate(0, 0.22f, 0);
				matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-25 - 10 * (float) Math.sin(time * 1.1 + side)));
				box(consumer, matrices.peek(), -0.05f, 0, 0.05f, 0.2f, 0.025f, outer, shade, innerColor);
				matrices.pop();
			}
		}
	}

	/** A triangular slab: base corners and a peak in the XY plane, {@code depth} thick either side of Z = 0. */
	private static void prism(VertexConsumer consumer, MatrixStack.Entry pose, float ax, float ay, float bx, float by, float px, float py, float depth, int face, int side) {
		FxRender.triangle(consumer, pose, ax, ay, depth, face, bx, by, depth, face, px, py, depth, FxRender.lighten(face, 0.1f));
		FxRender.triangle(consumer, pose, ax, ay, -depth, side, bx, by, -depth, side, px, py, -depth, side);
		FxRender.quad(consumer, pose, ax, ay, depth, side, px, py, depth, side, px, py, -depth, side, ax, ay, -depth, side);
		FxRender.quad(consumer, pose, bx, by, depth, side, px, py, depth, side, px, py, -depth, side, bx, by, -depth, side);
		FxRender.quad(consumer, pose, ax, ay, depth, side, bx, by, depth, side, bx, by, -depth, side, ax, ay, -depth, side);
	}

	/** The pink inside of an ear, just in front of its face. */
	private static void triangle(VertexConsumer consumer, MatrixStack.Entry pose, float ax, float ay, float bx, float by, float px, float py, float z, int color) {
		FxRender.triangle(consumer, pose, ax, ay, z, color, bx, by, z, color, px, py, z, color);
	}

	/** A slab from x1 to x2 and 0 to {@code height}, with a lighter inner panel on the front. */
	private static void box(VertexConsumer consumer, MatrixStack.Entry pose, float x1, float y1, float x2, float height, float depth, int face, int side, int innerColor) {
		float y2 = y1 + height;
		FxRender.quad(consumer, pose, x1, y1, depth, face, x2, y1, depth, face, x2, y2, depth, face, x1, y2, depth, face);
		FxRender.quad(consumer, pose, x1, y1, -depth, side, x2, y1, -depth, side, x2, y2, -depth, side, x1, y2, -depth, side);
		FxRender.quad(consumer, pose, x1, y1, depth, side, x1, y2, depth, side, x1, y2, -depth, side, x1, y1, -depth, side);
		FxRender.quad(consumer, pose, x2, y1, depth, side, x2, y2, depth, side, x2, y2, -depth, side, x2, y1, -depth, side);
		FxRender.quad(consumer, pose, x1, y2, depth, side, x2, y2, depth, side, x2, y2, -depth, side, x1, y2, -depth, side);
		float inset = (x2 - x1) * 0.25f;
		FxRender.quad(consumer, pose, x1 + inset, y1 + 0.02f, depth + 0.001f, innerColor, x2 - inset, y1 + 0.02f, depth + 0.001f, innerColor,
			x2 - inset, y2 - 0.02f, depth + 0.001f, innerColor, x1 + inset, y2 - 0.02f, depth + 0.001f, innerColor);
	}

	/** A tapering tube along a curve that sways from side to side, lit from above. */
	private void tail(VertexConsumer consumer, MatrixStack.Entry pose, double phase, float amount) {
		Vector3f[] joints = new Vector3f[TAIL_JOINTS + 1];
		float[] radii = new float[TAIL_JOINTS + 1];
		joints[0] = new Vector3f();
		float length = switch (style.get()) {
			case CAT -> 1.05f;
			case FOX -> 0.75f;
			case BUNNY -> 0.2f;
		};
		for (int i = 0; i <= TAIL_JOINTS; i++) {
			float s = (float) i / TAIL_JOINTS;
			radii[i] = switch (style.get()) {
				case CAT -> 0.06f - 0.025f * s;
				case FOX -> 0.05f + 0.08f * (float) Math.sin(Math.PI * Math.pow(s, 0.8));
				// A round puff
				case BUNNY -> 0.1f * (float) Math.sqrt(Math.max(0, 1 - (2 * s - 1) * (2 * s - 1)));
			};
			if (i == 0) continue;
			// Starts out back and down, curling up towards the tip; the sway grows along it
			double elevation = Math.toRadians(switch (style.get()) {
				case CAT -> -15 + 95 * Math.pow(s, 1.3);
				case FOX -> -50 + 55 * s;
				case BUNNY -> 0;
			});
			double yaw = Math.toRadians(Math.sin(phase - s * 2.5) * 35 * amount * s);
			Vector3f step = new Vector3f((float) (Math.sin(yaw) * Math.cos(elevation)), (float) Math.sin(elevation), (float) (-Math.cos(yaw) * Math.cos(elevation)));
			joints[i] = new Vector3f(joints[i - 1]).add(step.mul(length / TAIL_JOINTS));
		}

		int base = FxRender.opaque(fur.get());
		int tipColor = FxRender.opaque(tip.get());
		Vector3f[] previous = null;
		int[] previousColors = null;
		for (int i = 0; i <= TAIL_JOINTS; i++) {
			Vector3f direction = new Vector3f(joints[Math.min(TAIL_JOINTS, i + 1)]).sub(joints[Math.max(0, i - 1)]).normalize();
			Vector3f u = new Vector3f(direction).cross(0, 1, 0);
			if (u.lengthSquared() < 1e-4f) u.set(1, 0, 0);
			u.normalize();
			Vector3f v = new Vector3f(u).cross(direction).normalize();
			boolean whiteTip = style.is(Style.FOX) && i > TAIL_JOINTS * 0.72f;
			Vector3f[] ring = new Vector3f[TAIL_SIDES];
			int[] colors = new int[TAIL_SIDES];
			for (int k = 0; k < TAIL_SIDES; k++) {
				double a = Math.PI * 2 * k / TAIL_SIDES;
				Vector3f offset = new Vector3f(u).mul((float) Math.cos(a)).add(new Vector3f(v).mul((float) Math.sin(a)));
				ring[k] = new Vector3f(offset).mul(radii[i]).add(joints[i]);
				int color = whiteTip ? tipColor : base;
				// Brighter on top, shaded underneath
				colors[k] = offset.y > 0 ? FxRender.lighten(color, offset.y * 0.15f) : FxRender.darken(color, -offset.y * 0.3f);
			}
			if (previous != null) {
				for (int k = 0; k < TAIL_SIDES; k++) {
					int n = (k + 1) % TAIL_SIDES;
					FxRender.quad(consumer, pose,
						previous[k].x, previous[k].y, previous[k].z, previousColors[k], previous[n].x, previous[n].y, previous[n].z, previousColors[n],
						ring[n].x, ring[n].y, ring[n].z, colors[n], ring[k].x, ring[k].y, ring[k].z, colors[k]);
				}
			}
			previous = ring;
			previousColors = colors;
		}
		// Close the tip with a short cone
		Vector3f end = joints[TAIL_JOINTS];
		Vector3f point = new Vector3f(end).sub(joints[TAIL_JOINTS - 1]).normalize().mul(radii[TAIL_JOINTS] * 1.5f).add(end);
		for (int k = 0; k < TAIL_SIDES; k++) {
			int n = (k + 1) % TAIL_SIDES;
			FxRender.triangle(consumer, pose, previous[k].x, previous[k].y, previous[k].z, previousColors[k],
				previous[n].x, previous[n].y, previous[n].z, previousColors[n], point.x, point.y, point.z, previousColors[k]);
		}
	}
}
