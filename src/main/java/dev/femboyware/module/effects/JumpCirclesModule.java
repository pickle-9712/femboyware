package dev.femboyware.module.effects;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.FxRender;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Rings of light ripple out across the ground where you jump and land. */
public class JumpCirclesModule extends PlayerFxModule {
	public enum Style { RING, DOUBLE, SHOCKWAVE }

	private static final int SEGMENTS = 48;
	private static final int MAX_CIRCLES = 64;

	private final EnumSetting<Style> style = add(new EnumSetting<>("Style", "Ring: one ripple. Double: two, one after the other. Shockwave: a filled disc", Style.RING));
	private final BoolSetting onJump = add(new BoolSetting("On Jump", "When you jump", true));
	private final BoolSetting onLand = add(new BoolSetting("On Land", "When you land", false));
	private final NumberSetting radius = add(new NumberSetting("Radius", "How far the ripple spreads", 1.6, 0.4, 5, 0.05));
	private final NumberSetting duration = add(new NumberSetting("Duration", "Seconds each ripple lasts", 0.9, 0.2, 3, 0.05));
	private final NumberSetting width = add(new NumberSetting("Width", "How thick the ring is, as a share of its size", 0.18, 0.03, 0.6, 0.01));
	private final Palette palette = palette(0xFFFF77C8, 0xFF8AB8FF);
	private final EnumSetting<ShowOn> showOn = showOnSetting();

	private record Circle(Vec3d pos, long start) {}

	private final List<Circle> circles = new ArrayList<>();
	private final Map<Integer, Boolean> grounded = new HashMap<>();

	public JumpCirclesModule() {
		super("Jump Circles", "Rings ripple out where you jump and land");
	}

	@Override
	protected void onDisable() {
		circles.clear();
		grounded.clear();
	}

	@Override
	public void onTick() {
		Map<Integer, Boolean> seen = new HashMap<>();
		for (PlayerEntity player : wearers(true)) {
			boolean onGround = player.isOnGround();
			Boolean before = grounded.get(player.getId());
			if (before != null && before != onGround) {
				boolean jumped = !onGround && player.getVelocity().y > 0.1;
				if (jumped && onJump.get() || onGround && onLand.get()) {
					circles.add(new Circle(player.getEntityPos().add(0, 0.02, 0), System.nanoTime()));
					if (circles.size() > MAX_CIRCLES) circles.remove(0);
				}
			}
			seen.put(player.getId(), onGround);
		}
		grounded.clear();
		grounded.putAll(seen);
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		long now = System.nanoTime();
		double life = duration.get() * (style.is(Style.DOUBLE) ? 1.3 : 1);
		circles.removeIf(circle -> (now - circle.start) / 1e9 > life);
		if (circles.isEmpty()) return;

		Vec3d cam = event.camera().pos;
		MatrixStack matrices = event.matrices();
		VertexConsumer light = FxRender.quads(event, true, false);
		for (Circle circle : circles) {
			double age = (now - circle.start) / 1e9;
			matrices.push();
			matrices.translate((float) (circle.pos.x - cam.x), (float) (circle.pos.y - cam.y), (float) (circle.pos.z - cam.z));
			ripple(light, matrices.peek(), (float) (age / duration.get()));
			if (style.is(Style.DOUBLE)) ripple(light, matrices.peek(), (float) ((age - duration.get() * 0.3) / duration.get()));
			matrices.pop();
		}
	}

	/** One ripple {@code progress} of the way through: eased outwards, fading as it goes. */
	private void ripple(VertexConsumer consumer, MatrixStack.Entry pose, float progress) {
		if (progress <= 0 || progress >= 1) return;
		float eased = 1 - (float) Math.pow(1 - progress, 3);
		float outer = radius.getFloat() * eased;
		float alpha = 1 - progress;
		if (style.is(Style.SHOCKWAVE)) {
			FxRender.ring(consumer, pose, 0, outer, 0, SEGMENTS, (t, inner) -> FxRender.fade(palette.at(t), inner ? alpha * 0.08f : alpha * 0.7f));
			return;
		}
		float inner = outer * (1 - width.getFloat());
		float mid = (inner + outer) / 2;
		// Bright in the middle of the band, fading to nothing at both edges
		FxRender.ring(consumer, pose, inner, mid, 0, SEGMENTS, (t, in) -> FxRender.fade(palette.at(t), in ? 0 : alpha));
		FxRender.ring(consumer, pose, mid, outer, 0, SEGMENTS, (t, in) -> FxRender.fade(palette.at(t), in ? alpha : 0));
	}
}
