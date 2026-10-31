package dev.femboyware.module.effects;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.FxRender;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

import java.util.Map;

/** Marks whoever you're fighting: a spinning crystal over their head, a lock-on reticle, or a bobbing arrow. */
public class TargetMarkerModule extends TargetFxModule {
	public enum Mode { CRYSTAL, RETICLE, ARROW }

	private final EnumSetting<Mode> mode = add(new EnumSetting<>("Mode", "Crystal: a spinning gem over their head. Reticle: lock-on brackets round them. Arrow: points down at them", Mode.CRYSTAL));
	private final NumberSetting size = add(new NumberSetting("Size", "How big the marker is", 1, 0.4, 2.5, 0.05));
	private final NumberSetting spin = add(new NumberSetting("Spin", "How fast it turns", 1, 0, 4, 0.05));
	private final BoolSetting glow = add(new BoolSetting("Glow", "A soft light behind the marker", true));
	private final Palette palette = palette(0xFFFF77C8, 0xFF8AB8FF);
	private final BoolSetting throughWalls = targetSettings();

	public TargetMarkerModule() {
		super("Target Marker", "Marks whoever you're fighting");
	}

	@Override
	public String getInfo() {
		return mode.getDisplayName();
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		Map<LivingEntity, Float> targets = fadedTargets();
		if (targets.isEmpty()) return;
		float tickDelta = tickDelta();
		double now = time();
		for (Map.Entry<LivingEntity, Float> entry : targets.entrySet()) {
			LivingEntity target = entry.getKey();
			float fade = ease(entry.getValue());
			Vec3d feet = target.getLerpedPos(tickDelta);
			switch (mode.get()) {
				case CRYSTAL -> crystal(event, feet.add(0, target.getHeight() + 0.45 + Math.sin(now * 2.5) * 0.08, 0), now, fade);
				case RETICLE -> reticle(event, feet.add(0, target.getHeight() / 2, 0), Math.max(target.getWidth(), target.getHeight()) * 0.62f, now, fade);
				case ARROW -> arrow(event, feet.add(0, target.getHeight() + 0.3 + Math.abs(Math.sin(now * 3)) * 0.2, 0), now, fade);
			}
		}
	}

	/** An octahedron, spinning, with lit and shaded faces in alternating palette colours. */
	private void crystal(Render3DEvent event, Vec3d center, double now, float fade) {
		float s = size.getFloat();
		float w = 0.17f * s, h = 0.27f * s;
		MatrixStack matrices = event.matrices();
		Vec3d cam = event.camera().pos;
		if (glow.get()) FxRender.glow(event, FxRender.textured(event, FxRender.GLOW, true, throughWalls.get()), center, 0.55f * s, FxRender.fade(palette.at(0), fade * 0.45f));

		matrices.push();
		matrices.translate((float) (center.x - cam.x), (float) (center.y - cam.y), (float) (center.z - cam.z));
		matrices.multiply(RotationAxis.POSITIVE_Y.rotation((float) (now * spin.get() * 2)));
		MatrixStack.Entry pose = matrices.peek();
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
		matrices.pop();
	}

	/** Four corner brackets facing the camera, turning slowly and breathing in and out. */
	private void reticle(Render3DEvent event, Vec3d center, float half, double now, float fade) {
		half *= size.getFloat() * (1 + 0.06f * (float) Math.sin(now * 4));
		float roll = (float) (now * spin.get() * 0.8);
		float arm = half * 0.45f, thick = 0.035f * size.getFloat();
		Vec3d cam = event.camera().pos;
		float cx = (float) (center.x - cam.x), cy = (float) (center.y - cam.y), cz = (float) (center.z - cam.z);
		MatrixStack.Entry pose = event.matrices().peek();
		VertexConsumer consumer = FxRender.quads(event, glow.get(), throughWalls.get());
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
	private static void bar(VertexConsumer consumer, MatrixStack.Entry pose, Vector3f start, Vector3f direction, Vector3f side, float thick, int color) {
		Vector3f offset = new Vector3f(side).mul(thick);
		Vector3f end = new Vector3f(start).add(direction);
		FxRender.quad(consumer, pose,
			start.x, start.y, start.z, color, end.x, end.y, end.z, FxRender.fade(color, 0.25f),
			end.x - offset.x, end.y - offset.y, end.z - offset.z, FxRender.fade(color, 0.25f), start.x - offset.x, start.y - offset.y, start.z - offset.z, color);
	}

	/** A four sided arrowhead with a short shaft, pointing down. */
	private void arrow(Render3DEvent event, Vec3d tip, double now, float fade) {
		float s = size.getFloat();
		float head = 0.22f * s, width = 0.16f * s, shaft = 0.06f * s, length = 0.2f * s;
		MatrixStack matrices = event.matrices();
		Vec3d cam = event.camera().pos;
		matrices.push();
		matrices.translate((float) (tip.x - cam.x), (float) (tip.y - cam.y), (float) (tip.z - cam.z));
		matrices.multiply(RotationAxis.POSITIVE_Y.rotation((float) (now * spin.get() * 1.5)));
		MatrixStack.Entry pose = matrices.peek();
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
		matrices.pop();
		if (glow.get()) FxRender.glow(event, FxRender.textured(event, FxRender.GLOW, true, throughWalls.get()), tip.add(0, head, 0), 0.4f * s, FxRender.fade(palette.at(0), fade * 0.4f));
	}
}
