package dev.femboyware.module.effects;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.FxRender;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Arm;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A glowing slash arc sweeps from your weapon side across your front each time you swing. */
public class SwingTrailModule extends PlayerFxModule {
	private static final int SEGMENTS = 20;
	private static final float SWEEP = 75;

	private final NumberSetting duration = add(new NumberSetting("Duration", "Seconds each slash lasts", 0.3, 0.1, 1, 0.01));
	private final NumberSetting reach = add(new NumberSetting("Reach", "How far out the slash reaches", 1.5, 0.6, 3, 0.05));
	private final NumberSetting width = add(new NumberSetting("Width", "How wide the slash band is", 0.9, 0.1, 1.5, 0.05));
	private final NumberSetting tilt = add(new NumberSetting("Tilt", "How diagonal the slash is, in degrees", 40, -90, 90, 5));
	private final NumberSetting length = add(new NumberSetting("Trail Length", "How far round the fading tail reaches, in degrees", 80, 20, 150, 5));
	private final BoolSetting glow = add(new BoolSetting("Glow", "Add the slash as light, so it shines", true));
	private final Palette palette = palette(0xFFFF77C8, 0xFFFFFFFF);
	private final EnumSetting<ShowOn> showOn = showOnSetting();

	/** When each player's current slash began, and their swing progress last frame to spot new swings. */
	private final Map<Integer, Long> starts = new HashMap<>();
	private final Map<Integer, Float> lastProgress = new HashMap<>();

	public SwingTrailModule() {
		super("Swing Trail", "A glowing slash when you swing");
	}

	@Override
	protected void onDisable() {
		starts.clear();
		lastProgress.clear();
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		long now = System.nanoTime();
		float tickDelta = tickDelta();
		List<PlayerEntity> wearers = wearers(true);
		for (PlayerEntity player : wearers) {
			float progress = player.getHandSwingProgress(tickDelta);
			float before = lastProgress.getOrDefault(player.getId(), 0f);
			if (progress > 0 && (before == 0 || progress + 0.15f < before)) starts.put(player.getId(), now);
			lastProgress.put(player.getId(), progress);
		}
		starts.values().removeIf(start -> (now - start) / 1e9 > duration.get());
		if (starts.isEmpty()) return;

		Vec3d cam = event.camera().pos;
		MatrixStack matrices = event.matrices();
		VertexConsumer consumer = FxRender.quads(event, glow.get(), false);
		for (PlayerEntity player : wearers) {
			Long start = starts.get(player.getId());
			if (start == null) continue;
			float t = (float) ((now - start) / 1e9 / duration.get());
			// The head races across in the first 60%, then the whole slash fades
			float head = 1 - (float) Math.pow(1 - Math.min(1, t / 0.6f), 3);
			float fade = 1 - Math.max(0, (t - 0.6f) / 0.4f);
			float headAngle = SWEEP - head * SWEEP * 2;
			float tailAngle = Math.min(SWEEP, headAngle + length.getFloat());
			int side = player.getMainArm() == Arm.RIGHT ? -1 : 1;

			matrices.push();
			Vec3d feet = player.getLerpedPos(tickDelta);
			matrices.translate((float) (feet.x - cam.x), (float) (feet.y - cam.y) + player.getEyeHeight(player.getPose()), (float) (feet.z - cam.z));
			matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-MathHelper.lerpAngleDegrees(tickDelta, player.lastHeadYaw, player.headYaw)));
			matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(player.getPitch(tickDelta)));
			// From the weapon shoulder, in a plane tipped towards that side
			matrices.translate(side * 0.3f, -0.4f, 0.1f);
			matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(-side * tilt.getFloat()));
			MatrixStack.Entry pose = matrices.peek();
			float outer = reach.getFloat(), inner = Math.max(0.05f, outer - width.getFloat());
			for (int i = 0; i < SEGMENTS; i++) {
				float f1 = (float) i / SEGMENTS, f2 = (float) (i + 1) / SEGMENTS;
				double a1 = Math.toRadians(tailAngle + (headAngle - tailAngle) * f1), a2 = Math.toRadians(tailAngle + (headAngle - tailAngle) * f2);
				// Brightest at the leading edge and the outer rim
				float al1 = f1 * fade, al2 = f2 * fade;
				int c1 = palette.at(f1 * 0.5f), c2 = palette.at(f2 * 0.5f);
				float y1 = (float) Math.sin(a1), z1 = (float) Math.cos(a1), y2 = (float) Math.sin(a2), z2 = (float) Math.cos(a2);
				FxRender.quad(consumer, pose,
					0, y1 * inner, z1 * inner, FxRender.fade(c1, al1 * 0.15f), 0, y2 * inner, z2 * inner, FxRender.fade(c2, al2 * 0.15f),
					0, y2 * outer, z2 * outer, FxRender.fade(FxRender.lighten(c2, 0.6f), al2), 0, y1 * outer, z1 * outer, FxRender.fade(FxRender.lighten(c1, 0.6f), al1));
			}
			matrices.pop();
		}
	}
}
