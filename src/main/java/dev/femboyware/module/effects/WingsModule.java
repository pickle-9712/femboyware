package dev.femboyware.module.effects;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.FxRender;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

/**
 * Wings on your back, drawn from flat shapes: feathered angel wings, bat-like demon wings,
 * butterfly wings or slender fairy wings. They flap gently on the ground, beat hard in the air and
 * spread out while gliding.
 */
public class WingsModule extends PlayerFxModule {
	public enum Style { ANGEL, DEMON, BUTTERFLY, FAIRY }

	private final EnumSetting<Style> style = add(new EnumSetting<>("Style", "Angel, demon, butterfly or fairy wings", Style.ANGEL));
	private final NumberSetting size = add(new NumberSetting("Size", "How big the wings are", 1, 0.4, 2.5, 0.05));
	private final NumberSetting flapSpeed = add(new NumberSetting("Flap Speed", "How fast they flap", 1, 0, 4, 0.05));
	private final NumberSetting flapAmount = add(new NumberSetting("Flap Amount", "How far they swing as they flap", 1, 0, 2, 0.05));
	private final NumberSetting opacity = add(new NumberSetting("Opacity", "How see-through the wings are", 0.9, 0.1, 1, 0.05));
	private final Palette palette = palette(0xFFFFFFFF, 0xFFFFB6E1);
	private final EnumSetting<ShowOn> showOn = showOnSetting();

	public WingsModule() {
		super("Wings", "Flapping wings on your back");
	}

	@Override
	public String getInfo() {
		return style.getDisplayName();
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		float tickDelta = tickDelta();
		Vec3d cam = event.camera().pos;
		MatrixStack matrices = event.matrices();
		VertexConsumer consumer = FxRender.quads(event, false, false);
		for (PlayerEntity player : wearers(false)) {
			boolean airborne = !player.isOnGround() && !player.isClimbing() && !player.isTouchingWater();
			boolean gliding = player.isGliding();
			// Each player flaps a little out of step with the others
			double phase = time() * flapSpeed.get() * (airborne ? 7 : 2.2) + player.getId() * 0.7;
			float swing = gliding ? 0.1f : (0.5f + 0.5f * (float) Math.sin(phase)) * (airborne ? 1 : 0.45f);
			float sweep = (gliding ? 5 : 18) + swing * 42 * flapAmount.getFloat();

			matrices.push();
			toBody(matrices, player, cam.x, cam.y, cam.z, tickDelta);
			// Between the shoulder blades, just off the back
			matrices.translate(0, 1.32f, -0.17f);
			float s = size.getFloat();
			matrices.scale(s, s, s);
			for (int side = -1; side <= 1; side += 2) {
				matrices.push();
				matrices.scale(side, 1, 1);
				matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(sweep));
				// A little upward lift as they beat down, so it reads as flapping rather than swinging
				matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(-8 + swing * 14));
				MatrixStack.Entry pose = matrices.peek();
				switch (style.get()) {
					case ANGEL -> angel(consumer, pose);
					case DEMON -> demon(consumer, pose);
					case BUTTERFLY -> butterfly(consumer, pose);
					case FAIRY -> fairy(consumer, pose);
				}
				matrices.pop();
			}
			matrices.pop();
		}
	}

	private int color(float t, float alpha) {
		return FxRender.fade(palette.at(t), alpha * opacity.getFloat());
	}

	/** Point {@code s} of the way along the wing's arm, an arc from the shoulder up and out to the tip. */
	private static float armX(float s) {
		return 0.04f + 0.9f * s;
	}

	private static float armY(float s) {
		return 0.42f * s + 0.2f * (float) Math.sin(Math.PI * s);
	}

	/** Three layers of feathers hanging off the arm, longest at the back, each tapering to a point. */
	private void angel(VertexConsumer consumer, MatrixStack.Entry pose) {
		feathers(consumer, pose, 13, 0.25f, 1f, 0.42f, 0.78f, -0.05f, 0.95f, 0.11f, 0.95f);
		feathers(consumer, pose, 11, 0f, 0.8f, 0.3f, 0.44f, -0.25f, 0.4f, 0.1f, 1f);
		feathers(consumer, pose, 9, 0f, 0.9f, 0.14f, 0.2f, -0.2f, 0.5f, 0.09f, 1f);
	}

	private void feathers(VertexConsumer consumer, MatrixStack.Entry pose, int count, float from, float to,
						  float shortest, float longest, float firstAngle, float lastAngle, float width, float alpha) {
		for (int i = 0; i < count; i++) {
			float s = from + (to - from) * i / (count - 1);
			float x = armX(s), y = armY(s);
			float length = shortest + (longest - shortest) * (float) Math.sin(Math.PI * 0.5 * (i + 1) / count);
			// Hang down, fanning outwards towards the tip
			float angle = firstAngle + (lastAngle - firstAngle) * i / (count - 1);
			float dx = (float) Math.sin(angle), dy = -(float) Math.cos(angle);
			float nx = -dy * width / 2, ny = dx * width / 2;
			float mx = x + dx * length * 0.72f, my = y + dy * length * 0.72f;
			float tx = x + dx * length, ty = y + dy * length;
			int base = color(s * 0.6f, alpha);
			int mid = FxRender.lighten(color(s * 0.6f + 0.2f, alpha), 0.15f);
			int tip = FxRender.fade(FxRender.lighten(color(s * 0.6f + 0.3f, alpha), 0.3f), 0.85f);
			FxRender.quad(consumer, pose, x - nx, y - ny, 0, base, x + nx, y + ny, 0, base, mx + nx * 0.9f, my + ny * 0.9f, 0, mid, mx - nx * 0.9f, my - ny * 0.9f, 0, mid);
			FxRender.triangle(consumer, pose, mx - nx * 0.9f, my - ny * 0.9f, 0, mid, mx + nx * 0.9f, my + ny * 0.9f, 0, mid, tx, ty, 0, tip);
		}
	}

	/** Bony fingers fanning from the wrist, with scalloped membrane stretched between them. */
	private void demon(VertexConsumer consumer, MatrixStack.Entry pose) {
		float wristX = 0.38f, wristY = 0.36f;
		float[][] tips = {{0.98f, 0.5f}, {0.95f, 0.06f}, {0.74f, -0.36f}, {0.4f, -0.52f}, {0.06f, -0.36f}};
		int membrane = color(0.2f, 0.8f);
		int edge = color(0.7f, 0.65f);
		for (int i = 0; i < tips.length - 1; i++) {
			float ax = tips[i][0], ay = tips[i][1], bx = tips[i + 1][0], by = tips[i + 1][1];
			// The membrane sags inwards between fingers
			float mx = (ax + bx) / 2 + (wristX - (ax + bx) / 2) * 0.28f, my = (ay + by) / 2 + (wristY - (ay + by) / 2) * 0.28f;
			FxRender.triangle(consumer, pose, wristX, wristY, 0, membrane, ax, ay, 0, edge, mx, my, 0, edge);
			FxRender.triangle(consumer, pose, wristX, wristY, 0, membrane, mx, my, 0, edge, bx, by, 0, edge);
		}
		FxRender.triangle(consumer, pose, 0, 0, 0, membrane, wristX, wristY, 0, membrane, tips[tips.length - 1][0], tips[tips.length - 1][1], 0, edge);
		int bone = FxRender.darken(color(0, 1), 0.55f);
		FxRender.strokeXY(consumer, pose, 0, 0, wristX, wristY, 0.05f, bone, bone);
		for (float[] tip : tips) FxRender.strokeXY(consumer, pose, wristX, wristY, tip[0], tip[1], 0.028f, bone, FxRender.fade(bone, 0.6f));
		// A claw at the wrist
		FxRender.triangle(consumer, pose, wristX - 0.03f, wristY, 0, bone, wristX + 0.03f, wristY, 0, bone, wristX + 0.02f, wristY + 0.12f, 0, bone);
	}

	/** A big upper wing and a smaller lower one, with dark rims and pale eyespots. */
	private void butterfly(VertexConsumer consumer, MatrixStack.Entry pose) {
		lobe(consumer, pose, 0.42f, 0.3f, 0.52f, 0.3f, 35, 0.05f, true);
		lobe(consumer, pose, 0.3f, -0.21f, 0.36f, 0.2f, -38, 0.55f, true);
	}

	/** Two slender, see-through lobes with bright rims and a vein down the middle. */
	private void fairy(VertexConsumer consumer, MatrixStack.Entry pose) {
		lobe(consumer, pose, 0.4f, 0.32f, 0.48f, 0.13f, 38, 0.1f, false);
		lobe(consumer, pose, 0.3f, -0.12f, 0.34f, 0.1f, -25, 0.6f, false);
	}

	private void lobe(VertexConsumer consumer, MatrixStack.Entry pose, float cx, float cy, float a, float b, float tiltDegrees, float shade, boolean butterfly) {
		int segments = 36;
		double tilt = Math.toRadians(tiltDegrees);
		float cos = (float) Math.cos(tilt), sin = (float) Math.sin(tilt);
		float fill = butterfly ? 0.85f : 0.35f;
		int center = butterfly ? color(shade, fill) : FxRender.lighten(color(shade, fill), 0.4f);
		int outer = color(shade + 0.35f, fill);
		int rim = butterfly ? FxRender.darken(color(shade + 0.5f, 1), 0.6f) : FxRender.lighten(color(shade + 0.5f, 0.95f), 0.3f);
		float rimStart = butterfly ? 0.86f : 0.9f;
		for (int i = 0; i < segments; i++) {
			double t1 = Math.PI * 2 * i / segments, t2 = Math.PI * 2 * (i + 1) / segments;
			float ex1 = (float) Math.cos(t1) * a, ey1 = (float) Math.sin(t1) * b;
			float ex2 = (float) Math.cos(t2) * a, ey2 = (float) Math.sin(t2) * b;
			float x1 = cx + ex1 * cos - ey1 * sin, y1 = cy + ex1 * sin + ey1 * cos;
			float x2 = cx + ex2 * cos - ey2 * sin, y2 = cy + ex2 * sin + ey2 * cos;
			float ix1 = cx + (x1 - cx) * rimStart, iy1 = cy + (y1 - cy) * rimStart;
			float ix2 = cx + (x2 - cx) * rimStart, iy2 = cy + (y2 - cy) * rimStart;
			FxRender.quad(consumer, pose, cx, cy, 0, center, cx, cy, 0, center, ix2, iy2, 0, outer, ix1, iy1, 0, outer);
			FxRender.quad(consumer, pose, ix1, iy1, 0, rim, ix2, iy2, 0, rim, x2, y2, 0, rim, x1, y1, 0, rim);
		}
		if (butterfly) {
			// An eyespot out towards the end of the lobe
			float ex = cx + a * 0.5f * cos, ey = cy + a * 0.5f * sin;
			float r = b * 0.32f;
			int spot = FxRender.fade(0xFFFFFFFF, 0.8f * opacity.getFloat());
			int ring = FxRender.darken(color(shade + 0.6f, 1), 0.4f);
			for (int i = 0; i < 16; i++) {
				double t1 = Math.PI * 2 * i / 16, t2 = Math.PI * 2 * (i + 1) / 16;
				FxRender.quad(consumer, pose, ex, ey, 0, spot, ex, ey, 0, spot,
					ex + (float) Math.cos(t2) * r, ey + (float) Math.sin(t2) * r, 0, ring, ex + (float) Math.cos(t1) * r, ey + (float) Math.sin(t1) * r, 0, ring);
			}
		} else {
			int vein = FxRender.lighten(color(shade, 0.9f), 0.5f);
			FxRender.strokeXY(consumer, pose, 0, 0, cx + a * 0.9f * cos, cy + a * 0.9f * sin, 0.012f, vein, FxRender.fade(vein, 0.2f));
		}
	}
}
