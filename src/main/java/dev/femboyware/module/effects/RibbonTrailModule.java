package dev.femboyware.module.effects;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.FxRender;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** A ribbon of light streaming behind you as you move, fading away over a moment. */
public class RibbonTrailModule extends PlayerFxModule {
	/** New points are only added once you've moved this far, so standing still adds nothing. */
	private static final double MIN_STEP = 0.05;

	private final NumberSetting length = add(new NumberSetting("Length", "Seconds the ribbon lasts", 0.8, 0.1, 4, 0.05));
	private final NumberSetting bottom = add(new NumberSetting("Bottom", "Where the ribbon starts, up from the feet", 0.15, 0, 1.8, 0.05));
	private final NumberSetting top = add(new NumberSetting("Top", "Where the ribbon ends, up from the feet", 1.1, 0.05, 2, 0.05));
	private final NumberSetting opacity = add(new NumberSetting("Opacity", "How strong the ribbon is", 0.6, 0.05, 1, 0.05));
	private final BoolSetting glow = add(new BoolSetting("Glow", "Add the ribbon as light, so it shines", true));
	private final Palette palette = palette(0xFFFF77C8, 0xFF8AB8FF);
	private final EnumSetting<ShowOn> showOn = showOnSetting();

	private record Point(Vec3 pos, long time) {}

	private final Map<Integer, ArrayDeque<Point>> trails = new HashMap<>();

	public RibbonTrailModule() {
		super("Ribbon Trail", "A ribbon of light streams behind you");
	}

	@Override
	protected void onDisable() {
		trails.clear();
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		long now = System.nanoTime();
		long life = (long) (length.get() * 1e9);
		float tickDelta = tickDelta();
		Set<Integer> present = new HashSet<>();
		for (Player player : wearers(true)) {
			present.add(player.getId());
			ArrayDeque<Point> trail = trails.computeIfAbsent(player.getId(), id -> new ArrayDeque<>());
			Vec3 pos = player.getPosition(tickDelta);
			Point last = trail.peekLast();
			// Teleports would draw a ribbon across the world, so start again instead
			if (last != null && last.pos.distanceToSqr(pos) > 16) trail.clear();
			if (last == null || last.pos.distanceToSqr(pos) > MIN_STEP * MIN_STEP) trail.addLast(new Point(pos, now));
		}
		trails.keySet().retainAll(present);

		Vec3 cam = event.camera().pos;
		PoseStack.Pose pose = event.matrices().last();
		VertexConsumer consumer = FxRender.quads(event, glow.get(), false);
		float y1 = bottom.getFloat(), y2 = Math.max(y1 + 0.02f, top.getFloat());
		for (ArrayDeque<Point> trail : trails.values()) {
			while (!trail.isEmpty() && now - trail.peekFirst().time > life) trail.pollFirst();
			Point previous = null;
			float previousAge = 0;
			for (Point point : trail) {
				float age = (float) (now - point.time) / life;
				if (previous != null) {
					int c1 = FxRender.fade(palette.at(previousAge), (1 - previousAge) * opacity.getFloat());
					int c2 = FxRender.fade(palette.at(age), (1 - age) * opacity.getFloat());
					float ax = (float) (previous.pos.x - cam.x), ay = (float) (previous.pos.y - cam.y), az = (float) (previous.pos.z - cam.z);
					float bx = (float) (point.pos.x - cam.x), by = (float) (point.pos.y - cam.y), bz = (float) (point.pos.z - cam.z);
					// Fades towards the feet as well as with age
					FxRender.quad(consumer, pose, ax, ay + y1, az, FxRender.fade(c1, 0.25f), bx, by + y1, bz, FxRender.fade(c2, 0.25f),
						bx, by + y2, bz, c2, ax, ay + y2, az, c1);
				}
				previous = point;
				previousAge = age;
			}
		}
	}
}
