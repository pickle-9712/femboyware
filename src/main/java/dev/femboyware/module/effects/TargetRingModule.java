package dev.femboyware.module.effects;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.FxRender;
import java.util.Map;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** A glowing ring that glides up and down your target, trailing a fading skirt of light behind it. */
public class TargetRingModule extends TargetFxModule {
	private static final int SEGMENTS = 56;

	private final NumberSetting speed = add(new NumberSetting("Speed", "How fast the ring travels", 1, 0.1, 4, 0.05));
	private final NumberSetting radius = add(new NumberSetting("Radius", "How far out from the body", 1, 0.5, 2.5, 0.05));
	private final NumberSetting trailHeight = add(new NumberSetting("Trail Height", "How tall the fading skirt behind the ring is", 0.45, 0, 1.2, 0.05));
	private final NumberSetting thickness = add(new NumberSetting("Thickness", "How thick the bright band is", 0.03, 0.005, 0.15, 0.005));
	private final NumberSetting opacity = add(new NumberSetting("Opacity", "How strong the skirt is", 0.55, 0.05, 1, 0.05));
	private final BoolSetting glow = add(new BoolSetting("Glow", "Add the ring as light, so it shines", true));
	private final Palette palette = palette(0xFFFF77C8, 0xFF8AB8FF);
	private final BoolSetting throughWalls = targetSettings();

	public TargetRingModule() {
		super("Target Ring", "A glowing ring glides up and down whoever you're fighting");
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		Map<LivingEntity, Float> targets = fadedTargets();
		if (targets.isEmpty()) return;
		// The skirt is always blended normally; the band may be added as light, a different layer
		draw(event, targets, true);
		draw(event, targets, false);
	}

	private void draw(Render3DEvent event, Map<LivingEntity, Float> targets, boolean skirtPass) {
		float tickDelta = tickDelta();
		double phase = time() * speed.get() * 2;
		Vec3 cam = event.camera().pos;
		PoseStack matrices = event.matrices();
		VertexConsumer consumer = FxRender.quads(event, !skirtPass && glow.get(), throughWalls.get());

		for (Map.Entry<LivingEntity, Float> entry : targets.entrySet()) {
			LivingEntity target = entry.getKey();
			float fade = ease(entry.getValue());
			Vec3 feet = target.getPosition(tickDelta);
			float height = target.getBbHeight();
			float r = (target.getBbWidth() * 0.5f + 0.2f) * radius.getFloat();
			// Eased up and down; the skirt trails the way it came, longest mid-travel
			float y = height * (0.5f - 0.5f * (float) Math.cos(phase)) + 0.02f;
			float velocity = (float) Math.sin(phase);
			float trail = -Math.signum(velocity) * trailHeight.getFloat() * (float) Math.pow(Math.abs(velocity), 0.7);

			matrices.pushPose();
			matrices.translate((float) (feet.x - cam.x), (float) (feet.y - cam.y), (float) (feet.z - cam.z));
			PoseStack.Pose pose = matrices.last();
			float alpha = fade * opacity.getFloat();
			float half = thickness.getFloat() / 2;
			for (int i = 0; i < SEGMENTS; i++) {
				float t1 = (float) i / SEGMENTS, t2 = (float) (i + 1) / SEGMENTS;
				float x1 = (float) Math.cos(t1 * Math.PI * 2) * r, z1 = (float) Math.sin(t1 * Math.PI * 2) * r;
				float x2 = (float) Math.cos(t2 * Math.PI * 2) * r, z2 = (float) Math.sin(t2 * Math.PI * 2) * r;
				int c1 = palette.at(t1), c2 = palette.at(t2);
				if (skirtPass) {
					if (Math.abs(trail) > 0.01f) {
						FxRender.quad(consumer, pose,
							x1, y, z1, FxRender.fade(c1, alpha), x2, y, z2, FxRender.fade(c2, alpha),
							x2, y + trail, z2, FxRender.fade(c2, 0), x1, y + trail, z1, FxRender.fade(c1, 0));
					}
					continue;
				}
				FxRender.quad(consumer, pose,
					x1, y - half, z1, FxRender.fade(c1, fade), x2, y - half, z2, FxRender.fade(c2, fade),
					x2, y + half, z2, FxRender.fade(c2, fade), x1, y + half, z1, FxRender.fade(c1, fade));
			}
			matrices.popPose();
		}
	}
}
