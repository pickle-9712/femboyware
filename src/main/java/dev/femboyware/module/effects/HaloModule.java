package dev.femboyware.module.effects;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.FxRender;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** A glowing halo floating over your head, turning with it. */
public class HaloModule extends PlayerFxModule {
	private static final int SEGMENTS = 48;

	private final NumberSetting size = add(new NumberSetting("Size", "How wide the halo is", 0.28, 0.1, 0.8, 0.01));
	private final NumberSetting height = add(new NumberSetting("Height", "How far over your head it floats", 0.2, 0, 0.8, 0.01));
	private final NumberSetting thickness = add(new NumberSetting("Thickness", "How thick the ring is", 0.035, 0.01, 0.12, 0.005));
	private final NumberSetting tilt = add(new NumberSetting("Tilt", "Tips the halo back, in degrees", 12, -45, 45, 1));
	private final BoolSetting bob = add(new BoolSetting("Bob", "Float gently up and down", true));
	private final BoolSetting glow = add(new BoolSetting("Glow", "A soft glow round the ring", true));
	private final Palette palette = palette(0xFFFFE27A, 0xFFFFFFFF);
	private final EnumSetting<ShowOn> showOn = showOnSetting();

	public HaloModule() {
		super("Halo", "A glowing halo over your head");
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		List<Player> wearers = wearers(true);
		if (wearers.isEmpty()) return;
		float r = size.getFloat(), half = thickness.getFloat() / 2;
		if (glow.get()) {
			VertexConsumer light = FxRender.quads(event, true, false);
			draw(event, wearers, pose -> {
				// Soft bands fading out either side of the ring
				FxRender.ring(light, pose, r - half * 5, r, 0, SEGMENTS, (t, inner) -> FxRender.fade(palette.at(t), inner ? 0 : 0.5f));
				FxRender.ring(light, pose, r, r + half * 5, 0, SEGMENTS, (t, inner) -> FxRender.fade(palette.at(t), inner ? 0.5f : 0));
			});
		}
		VertexConsumer core = FxRender.quads(event, false, false);
		draw(event, wearers, pose -> FxRender.ring(core, pose, r - half, r + half, 0, SEGMENTS,
			(t, inner) -> inner ? FxRender.lighten(palette.at(t), 0.5f) : palette.at(t)));
	}

	private void draw(Render3DEvent event, List<Player> wearers, Consumer<PoseStack.Pose> shape) {
		float tickDelta = tickDelta();
		Vec3 cam = event.camera().pos;
		PoseStack matrices = event.matrices();
		for (Player player : wearers) {
			matrices.pushPose();
			toHead(matrices, player, cam.x, cam.y, cam.z, tickDelta);
			float lift = height.getFloat() + (bob.get() ? (float) Math.sin(time() * 2 + player.getId()) * 0.03f : 0);
			matrices.translate(0, lift, 0);
			matrices.mulPose(Axis.XP.rotationDegrees(-tilt.getFloat()));
			shape.accept(matrices.last());
			matrices.popPose();
		}
	}
}
