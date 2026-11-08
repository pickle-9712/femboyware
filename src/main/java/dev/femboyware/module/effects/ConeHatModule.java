package dev.femboyware.module.effects;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.FxRender;
import java.util.List;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** A see-through cone hat with a gradient swirling round it, worn at the angle of your head. */
public class ConeHatModule extends PlayerFxModule {
	private static final int SEGMENTS = 40;

	private final NumberSetting radius = add(new NumberSetting("Radius", "How wide the brim is", 0.6, 0.2, 1.2, 0.01));
	private final NumberSetting height = add(new NumberSetting("Height", "How tall the point is", 0.3, 0.05, 0.8, 0.01));
	private final NumberSetting opacity = add(new NumberSetting("Opacity", "How see-through it is", 0.5, 0.05, 1, 0.05));
	private final NumberSetting spin = add(new NumberSetting("Spin", "How fast the colours swirl round", 0.6, 0, 4, 0.05));
	private final BoolSetting outline = add(new BoolSetting("Outline", "A bright line round the brim", true));
	private final Palette palette = palette(0xFFFF77C8, 0xFF8AB8FF);
	private final EnumSetting<ShowOn> showOn = showOnSetting();

	public ConeHatModule() {
		super("Cone Hat", "A see-through cone hat");
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		List<Player> wearers = wearers(false);
		if (wearers.isEmpty()) return;
		float tickDelta = tickDelta();
		Vec3 cam = event.camera().pos;
		PoseStack matrices = event.matrices();
		float r = radius.getFloat(), h = height.getFloat(), alpha = opacity.getFloat();
		float swirl = (float) (time() * spin.get() * 0.5);

		VertexConsumer cone = FxRender.quads(event, false, false);
		for (Player player : wearers) {
			matrices.pushPose();
			toHead(matrices, player, cam.x, cam.y, cam.z, tickDelta);
			matrices.translate(0, -0.02f, 0);
			PoseStack.Pose pose = matrices.last();
			int apex = FxRender.fade(FxRender.lighten(palette.at(swirl), 0.4f), Math.min(1, alpha * 1.4f));
			for (int i = 0; i < SEGMENTS; i++) {
				float t1 = (float) i / SEGMENTS, t2 = (float) (i + 1) / SEGMENTS;
				double a1 = t1 * Math.PI * 2, a2 = t2 * Math.PI * 2;
				FxRender.triangle(cone, pose, 0, h, 0, apex,
					(float) Math.cos(a1) * r, 0, (float) Math.sin(a1) * r, FxRender.fade(palette.at(t1 + swirl), alpha),
					(float) Math.cos(a2) * r, 0, (float) Math.sin(a2) * r, FxRender.fade(palette.at(t2 + swirl), alpha));
			}
			matrices.popPose();
		}

		if (!outline.get()) return;
		VertexConsumer lines = FxRender.lines(event);
		for (Player player : wearers) {
			matrices.pushPose();
			toHead(matrices, player, cam.x, cam.y, cam.z, tickDelta);
			matrices.translate(0, -0.02f, 0);
			PoseStack.Pose pose = matrices.last();
			for (int i = 0; i < SEGMENTS; i++) {
				float t1 = (float) i / SEGMENTS, t2 = (float) (i + 1) / SEGMENTS;
				double a1 = t1 * Math.PI * 2, a2 = t2 * Math.PI * 2;
				FxRender.line(lines, pose, (float) Math.cos(a1) * r, 0, (float) Math.sin(a1) * r,
					(float) Math.cos(a2) * r, 0, (float) Math.sin(a2) * r, FxRender.opaque(palette.at(t1 + swirl)), 2f);
			}
			matrices.popPose();
		}
	}
}
