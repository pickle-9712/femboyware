package dev.femboyware.module.effects;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.FxRender;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A little glowing fairy that follows you round, hovering by your shoulder. It lags behind when you
 * move and catches up, flutters its wings, and leaves a trail of sparkles.
 */
public class FairyCompanionModule extends PlayerFxModule {
	private static final int TRAIL = 24;

	private final NumberSetting size = add(new NumberSetting("Size", "How big the fairy is", 1.3, 0.4, 3, 0.05));
	private final NumberSetting follow = add(new NumberSetting("Follow Speed", "How quickly it catches up with you", 2.5, 0.5, 8, 0.1));
	private final NumberSetting wander = add(new NumberSetting("Wander", "How much it flits round on its own", 1, 0, 3, 0.05));
	private final BoolSetting trail = add(new BoolSetting("Trail", "A sparkle trail behind it", true));
	private final Palette palette = palette(0xFFFF77C8, 0xFF9AE6FF);
	private final EnumSetting<ShowOn> showOn = showOnSetting();

	private static final class Fairy {
		Vec3d pos;
		final ArrayDeque<Vec3d> trail = new ArrayDeque<>();

		Fairy(Vec3d pos) {
			this.pos = pos;
		}
	}

	private final Map<Integer, Fairy> fairies = new HashMap<>();
	private long lastFrame;

	public FairyCompanionModule() {
		super("Fairy Companion", "A glowing fairy follows you round");
	}

	@Override
	protected void onDisable() {
		fairies.clear();
		lastFrame = 0;
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		long now = System.nanoTime();
		float delta = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1e9f);
		lastFrame = now;
		List<PlayerEntity> wearers = wearers(true);
		float tickDelta = tickDelta();
		double time = time();
		float s = size.getFloat();

		Set<Integer> present = new HashSet<>();
		for (PlayerEntity player : wearers) {
			present.add(player.getId());
			Vec3d home = home(player, tickDelta, time);
			Fairy fairy = fairies.computeIfAbsent(player.getId(), id -> new Fairy(home));
			// Teleports and respawns snap it back rather than flying it across the world
			if (fairy.pos.squaredDistanceTo(home) > 400) fairy.pos = home;
			fairy.pos = fairy.pos.lerp(home, 1 - Math.exp(-follow.get() * delta));
			Vec3d last = fairy.trail.peekLast();
			if (last == null || last.squaredDistanceTo(fairy.pos) > 0.003) fairy.trail.addLast(fairy.pos);
			while (fairy.trail.size() > TRAIL) fairy.trail.pollFirst();
		}
		fairies.keySet().retainAll(present);
		if (fairies.isEmpty()) return;

		// Glow and sparkles first, as light; then the wings over them
		VertexConsumer light = FxRender.textured(event, FxRender.GLOW, true, false);
		for (Fairy fairy : fairies.values()) {
			float pulse = 0.85f + 0.15f * (float) Math.sin(time * 6);
			FxRender.glow(event, light, fairy.pos, 0.45f * s * pulse, FxRender.fade(palette.at(0), 0.55f));
			FxRender.glow(event, light, fairy.pos, 0.12f * s, FxRender.lighten(palette.at(0), 0.7f));
			if (!trail.get()) continue;
			int i = 0;
			for (Vec3d point : fairy.trail) {
				float age = 1 - (float) i++ / fairy.trail.size();
				float twinkle = 0.5f + 0.5f * (float) Math.sin(time * 14 + i * 1.9);
				FxRender.glow(event, light, point, 0.05f * s * (1 - age * 0.6f), FxRender.fade(palette.at(age), (1 - age) * twinkle));
			}
		}

		Vec3d cam = event.camera().pos;
		MatrixStack matrices = event.matrices();
		VertexConsumer wings = FxRender.quads(event, false, false);
		float flap = (float) Math.sin(time * 28) * 35;
		for (Map.Entry<Integer, Fairy> entry : fairies.entrySet()) {
			if (!(mc.world.getEntityById(entry.getKey()) instanceof PlayerEntity owner)) continue;
			Vec3d pos = entry.getValue().pos;
			matrices.push();
			matrices.translate((float) (pos.x - cam.x), (float) (pos.y - cam.y), (float) (pos.z - cam.z));
			matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-MathHelper.lerpAngleDegrees(tickDelta, owner.lastHeadYaw, owner.headYaw)));
			matrices.scale(s, s, s);
			for (int side = -1; side <= 1; side += 2) {
				matrices.push();
				matrices.scale(side, 1, 1);
				matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(30 + flap));
				MatrixStack.Entry pose = matrices.peek();
				wing(wings, pose, 0.02f, 0.03f, 0.16f, 0.07f, 40, palette.at(0.2f));
				wing(wings, pose, 0.02f, -0.02f, 0.11f, 0.05f, -30, palette.at(0.6f));
				matrices.pop();
			}
			matrices.pop();
		}
	}

	/** Where the fairy wants to be: over the shoulder, a little behind, drifting in lazy loops. */
	private Vec3d home(PlayerEntity player, float tickDelta, double time) {
		double yaw = Math.toRadians(MathHelper.lerpAngleDegrees(tickDelta, player.lastBodyYaw, player.bodyYaw));
		double sideX = Math.cos(yaw), sideZ = Math.sin(yaw);
		double backX = Math.sin(yaw), backZ = -Math.cos(yaw);
		double w = wander.get() * 0.35;
		double side = 0.65 + Math.sin(time * 0.7 + player.getId()) * w;
		double back = 0.35 + Math.cos(time * 0.9) * w;
		double up = player.getHeight() + 0.1 + Math.sin(time * 1.9) * 0.12;
		Vec3d feet = player.getLerpedPos(tickDelta);
		return feet.add(sideX * side + backX * back, up, sideZ * side + backZ * back);
	}

	/** A see-through teardrop wing from just off the body, angled up or down by {@code tilt} degrees. */
	private static void wing(VertexConsumer consumer, MatrixStack.Entry pose, float x, float y, float length, float width, float tilt, int color) {
		double angle = Math.toRadians(tilt);
		float dx = (float) Math.cos(angle), dy = (float) Math.sin(angle);
		float cx = x + dx * length / 2, cy = y + dy * length / 2;
		int center = FxRender.fade(FxRender.lighten(color, 0.4f), 0.45f);
		int edge = FxRender.fade(color, 0.85f);
		int segments = 16;
		for (int i = 0; i < segments; i++) {
			double a1 = Math.PI * 2 * i / segments, a2 = Math.PI * 2 * (i + 1) / segments;
			float ex1 = (float) Math.cos(a1) * length / 2, ey1 = (float) Math.sin(a1) * width;
			float ex2 = (float) Math.cos(a2) * length / 2, ey2 = (float) Math.sin(a2) * width;
			FxRender.triangle(consumer, pose, cx, cy, 0, center,
				cx + ex1 * dx - ey1 * dy, cy + ex1 * dy + ey1 * dx, 0, edge,
				cx + ex2 * dx - ey2 * dy, cy + ex2 * dy + ey2 * dx, 0, edge);
		}
	}
}
