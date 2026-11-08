package dev.femboyware.module.effects;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.FxRender;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * A glowing summoning circle under your feet: two rings with runes between them and a star inside,
 * turning opposite ways and pulsing softly.
 */
public class MagicCircleModule extends PlayerFxModule {
	public enum Shape { PENTAGRAM, HEXAGRAM, OCTAGRAM, TRIANGLE }

	private static final int SEGMENTS = 64;

	private final EnumSetting<Shape> shape = add(new EnumSetting<>("Shape", "The star inside the circle", Shape.PENTAGRAM));
	private final NumberSetting radius = add(new NumberSetting("Radius", "How wide the circle is", 1.2, 0.4, 4, 0.05));
	private final NumberSetting speed = add(new NumberSetting("Speed", "How fast it turns", 1, 0, 4, 0.05));
	private final NumberSetting opacity = add(new NumberSetting("Opacity", "How strong it shines", 0.85, 0.1, 1, 0.05));
	private final BoolSetting runes = add(new BoolSetting("Runes", "Rune marks between the rings", true));
	private final BoolSetting hideMoving = add(new BoolSetting("Hide When Moving", "Fade out while walking, so it only shows when you stand still", false));
	private final Palette palette = palette(0xFFFF77C8, 0xFFB98AFF);
	private final EnumSetting<ShowOn> showOn = showOnSetting();

	/** How much each player's circle is showing, so Hide When Moving fades rather than pops. */
	private final Map<Integer, Float> shown = new HashMap<>();
	private long lastFrame;

	public MagicCircleModule() {
		super("Magic Circle", "A glowing summoning circle under your feet");
	}

	@Override
	protected void onDisable() {
		shown.clear();
		lastFrame = 0;
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		List<Player> wearers = wearers(true);
		long now = System.nanoTime();
		float delta = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1e9f);
		lastFrame = now;
		if (wearers.isEmpty()) return;

		float tickDelta = tickDelta();
		double time = time() * speed.get();
		Vec3 cam = event.camera().pos;
		PoseStack matrices = event.matrices();
		float r = radius.getFloat();
		float pulse = 0.8f + 0.2f * (float) Math.sin(time() * 2.5);

		VertexConsumer light = FxRender.quads(event, true, false);
		for (Player player : wearers) {
			boolean moving = player.getDeltaMovement().horizontalDistanceSqr() > 0.003;
			float target = hideMoving.get() && moving ? 0 : 1;
			float visible = shown.getOrDefault(player.getId(), 0f);
			visible += Math.signum(target - visible) * Math.min(Math.abs(target - visible), delta * 3);
			shown.put(player.getId(), visible);
			if (visible <= 0.01f) continue;
			float alpha = opacity.getFloat() * pulse * visible;

			Vec3 feet = player.getPosition(tickDelta);
			matrices.pushPose();
			matrices.translate((float) (feet.x - cam.x), (float) (feet.y - cam.y) + 0.02f, (float) (feet.z - cam.z));
			PoseStack.Pose pose = matrices.last();
			// Soft light filling the circle
			FxRender.ring(light, pose, 0, r, 0, SEGMENTS, (t, inner) -> FxRender.fade(palette.at(t), inner ? alpha * 0.18f : alpha * 0.05f));
			FxRender.ring(light, pose, r * 0.97f, r, 0, SEGMENTS, (t, inner) -> FxRender.fade(palette.at(t), alpha));
			FxRender.ring(light, pose, r * 0.78f, r * 0.8f, 0, SEGMENTS, (t, inner) -> FxRender.fade(palette.at(t + 0.5f), alpha));

			if (runes.get()) {
				int marks = 24;
				for (int i = 0; i < marks; i++) {
					double a = time * 0.6 + Math.PI * 2 * i / marks;
					float c = (float) Math.cos(a), s = (float) Math.sin(a);
					// Alternating long and short ticks, with a crossbar on every third
					float inner = r * (i % 2 == 0 ? 0.83f : 0.87f), outer = r * 0.94f;
					int color = FxRender.fade(palette.at((float) i / marks), alpha);
					FxRender.strokeXZ(light, pose, c * inner, s * inner, c * outer, s * outer, 0, 0.025f * r, color, color);
					if (i % 3 == 0) {
						float mid = (inner + outer) / 2, w = 0.04f * r;
						FxRender.strokeXZ(light, pose, c * mid - s * w, s * mid + c * w, c * mid + s * w, s * mid - c * w, 0, 0.02f * r, color, color);
					}
				}
			}

			int points = switch (shape.get()) {
				case PENTAGRAM -> 5;
				case HEXAGRAM -> 6;
				case OCTAGRAM -> 8;
				case TRIANGLE -> 3;
			};
			// Join every second point for stars; hexagrams and octagrams are two overlaid polygons
			int skip = shape.is(Shape.TRIANGLE) ? 1 : 2;
			float star = r * 0.78f;
			double turn = -time * 0.4;
			for (int i = 0; i < points; i++) {
				double a1 = turn + Math.PI * 2 * i / points, a2 = turn + Math.PI * 2 * ((i + skip) % points) / points;
				float x1 = (float) Math.cos(a1) * star, z1 = (float) Math.sin(a1) * star;
				float x2 = (float) Math.cos(a2) * star, z2 = (float) Math.sin(a2) * star;
				FxRender.strokeXZ(light, pose, x1, z1, x2, z2, 0, 0.03f * r, FxRender.fade(palette.at((float) i / points), alpha), FxRender.fade(palette.at((float) (i + skip) / points), alpha));
			}
			// Small rings on each point of the star
			for (int i = 0; i < points; i++) {
				double a = turn + Math.PI * 2 * i / points;
				matrices.pushPose();
				matrices.translate((float) Math.cos(a) * star, 0, (float) Math.sin(a) * star);
				float dot = 0.07f * r;
				int color = palette.at((float) i / points);
				FxRender.ring(light, matrices.last(), dot * 0.6f, dot, 0, 16, (t, inner) -> FxRender.fade(color, alpha));
				matrices.popPose();
			}
			matrices.popPose();
		}
		shown.keySet().removeIf(id -> mc.level.getEntity(id) == null);
	}
}
