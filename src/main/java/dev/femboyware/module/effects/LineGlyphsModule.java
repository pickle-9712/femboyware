package dev.femboyware.module.effects;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.FxRender;
import java.util.List;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Angular glyphs orbit you like a gyroscope: tilted triangles, squares and hexagons drawn in
 * dashes that run round their edges, with ticks on every corner.
 */
public class LineGlyphsModule extends PlayerFxModule {
	private final NumberSetting rings = add(new NumberSetting("Glyphs", "How many shapes orbit you", 3, 1, 5, 1));
	private final NumberSetting radius = add(new NumberSetting("Radius", "How far out they sit", 0.9, 0.4, 2.5, 0.05));
	private final NumberSetting speed = add(new NumberSetting("Speed", "How fast they turn", 1, 0, 4, 0.05));
	private final NumberSetting width = add(new NumberSetting("Width", "Line thickness", 0.03, 0.01, 0.1, 0.005));
	private final NumberSetting opacity = add(new NumberSetting("Opacity", "How strong they shine", 0.9, 0.1, 1, 0.05));
	private final Palette palette = palette(0xFFFF77C8, 0xFF8AD8FF);
	private final EnumSetting<ShowOn> showOn = showOnSetting();

	/** Sides of each glyph, innermost first. */
	private static final int[] SIDES = {3, 4, 6, 5, 8};
	private static final int DASHES = 3;

	public LineGlyphsModule() {
		super("Line Glyphs", "Show animated angular lines around you");
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		List<Player> wearers = wearers(false);
		if (wearers.isEmpty()) return;
		float tickDelta = tickDelta();
		double time = time() * speed.get();
		Vec3 cam = event.camera().pos;
		PoseStack matrices = event.matrices();
		VertexConsumer light = FxRender.quads(event, true, false);
		float w = width.getFloat();
		float alpha = opacity.getFloat();

		for (Player player : wearers) {
			double x = Mth.lerp(tickDelta, player.xOld, player.getX());
			double y = Mth.lerp(tickDelta, player.yOld, player.getY()) + player.getBbHeight() * 0.55;
			double z = Mth.lerp(tickDelta, player.zOld, player.getZ());
			for (int k = 0; k < rings.getInt(); k++) {
				int sides = SIDES[k];
				float r = radius.getFloat() * (1 + 0.14f * k);
				matrices.pushPose();
				matrices.translate((float) (x - cam.x), (float) (y - cam.y), (float) (z - cam.z));
				// Each glyph spins its own way about a differently tilted axis
				matrices.mulPose(Axis.YP.rotation((float) (time * (k % 2 == 0 ? 0.8 : -0.6) + k * 1.3)));
				matrices.mulPose(Axis.XP.rotation(0.35f + k * 0.55f + (float) Math.sin(time * 0.4 + k) * 0.15f));
				PoseStack.Pose pose = matrices.last();
				double turn = time * (k % 2 == 0 ? -1.1 : 0.9);
				for (int i = 0; i < sides; i++) {
					double a1 = turn + Math.PI * 2 * i / sides, a2 = turn + Math.PI * 2 * (i + 1) / sides;
					float x1 = (float) Math.cos(a1) * r, z1 = (float) Math.sin(a1) * r;
					float x2 = (float) Math.cos(a2) * r, z2 = (float) Math.sin(a2) * r;
					// Dashes with gaps; their brightness runs round the shape
					for (int d = 0; d < DASHES; d++) {
						float t1 = (d + 0.12f) / DASHES, t2 = (d + 0.88f) / DASHES;
						float run = (float) (0.5 + 0.5 * Math.sin(time * 4 - (i * DASHES + d) * 0.9));
						int color = FxRender.fade(palette.at((i + t1) / sides), alpha * (0.35f + 0.65f * run));
						FxRender.strokeXZ(light, pose, Mth.lerp(t1, x1, x2), Mth.lerp(t1, z1, z2), Mth.lerp(t2, x1, x2), Mth.lerp(t2, z1, z2), 0, w, color, color);
					}
					// A short tick pointing out of each corner
					float cx = (float) Math.cos(a1), cz = (float) Math.sin(a1);
					int tick = FxRender.fade(palette.at((float) i / sides), alpha);
					FxRender.strokeXZ(light, pose, cx * r, cz * r, cx * r * 1.12f, cz * r * 1.12f, 0, w * 1.4f, tick, FxRender.fade(tick, 0));
				}
				matrices.popPose();
			}
		}
	}
}
