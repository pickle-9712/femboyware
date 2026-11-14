package dev.femboyware.module.effects;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.FxRender;
import dev.femboyware.util.HeldBlades;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/** A ring of light pulses out from wherever you click: flat on the block face you hit, or facing you on a mob. */
public class ClickPulseModule extends FxModule {
	private static final int SEGMENTS = 40;

	private final BoolSetting left = add(new BoolSetting("Left Click", "Pulse when you attack or mine", true));
	private final BoolSetting right = add(new BoolSetting("Right Click", "Pulse when you use or place", true));
	private final BoolSetting air = add(new BoolSetting("In The Air", "Pulse in front of you when you click at nothing", false));
	private final NumberSetting size = add(new NumberSetting("Size", "How far the pulse spreads", 0.6, 0.2, 2, 0.05));
	private final NumberSetting duration = add(new NumberSetting("Duration", "Seconds each pulse lasts", 0.45, 0.15, 1.5, 0.05));
	private final Palette palette = palette(0xFFFF77C8, 0xFF8AB8FF);

	private record Pulse(Vec3 pos, Direction face, long start, boolean secondary) {
	}

	private final List<Pulse> pulses = new ArrayList<>();
	private boolean wasAttack, wasUse;

	public ClickPulseModule() {
		super("Click Pulse", "A ring of light pulses out where you click", Category.EFFECTS);
	}

	@Override
	protected void onDisable() {
		pulses.clear();
	}

	@Override
	public void onFrame() {
		if (mc.player == null || dev.femboyware.util.Mc.screen() != null) {
			wasAttack = wasUse = false;
			return;
		}
		boolean attack = mc.options.keyAttack.isDown(), use = mc.options.keyUse.isDown();
		if (attack && !wasAttack && left.get()) pulse(false);
		if (use && !wasUse && right.get()) pulse(true);
		wasAttack = attack;
		wasUse = use;
	}

	private void pulse(boolean secondary) {
		HitResult hit = mc.hitResult;
		long now = System.nanoTime();
		if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
			Vec3 at = block.getLocation().add(block.getDirection().getStepX() * 0.01, block.getDirection().getStepY() * 0.01, block.getDirection().getStepZ() * 0.01);
			pulses.add(new Pulse(at, block.getDirection(), now, secondary));
		} else if (hit instanceof EntityHitResult entity) {
			pulses.add(new Pulse(entity.getLocation(), null, now, secondary));
		} else if (air.get()) {
			pulses.add(new Pulse(mc.player.getEyePosition().add(mc.player.getViewVector(1).scale(2)), null, now, secondary));
		}
		if (pulses.size() > 32) pulses.remove(0);
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		long now = System.nanoTime();
		pulses.removeIf(p -> (now - p.start) / 1e9 > duration.get());
		if (pulses.isEmpty()) return;
		Vec3 cam = event.camera().pos;
		PoseStack matrices = event.matrices();
		VertexConsumer light = FxRender.quads(event, true, false);
		for (Pulse p : pulses) {
			float progress = (float) ((now - p.start) / 1e9 / duration.get());
			float eased = 1 - (float) Math.pow(1 - progress, 3);
			float outer = size.getFloat() * (0.25f + 0.75f * eased), inner = outer * 0.7f, mid = (inner + outer) / 2;
			float alpha = (1 - progress) * 0.9f;
			matrices.pushPose();
			matrices.translate((float) (p.pos.x - cam.x), (float) (p.pos.y - cam.y), (float) (p.pos.z - cam.z));
			// The ring's drawn flat (XZ): stand it on the face, or turn it to face you
			Quaternionf turn = p.face == null ? new Quaternionf(event.camera().orientation).rotateX((float) Math.PI / 2) : switch (p.face) {
				case UP, DOWN -> new Quaternionf();
				case NORTH, SOUTH -> new Quaternionf().rotationX((float) Math.PI / 2);
				case EAST, WEST -> new Quaternionf().rotationZ((float) Math.PI / 2);
			};
			HeldBlades.rotate(matrices, turn);
			float shift = p.secondary ? 0.5f : 0;
			FxRender.ring(light, matrices.last(), inner, mid, 0, SEGMENTS, (t, in) -> FxRender.fade(palette.at((t + shift) % 1), in ? 0 : alpha));
			FxRender.ring(light, matrices.last(), mid, outer, 0, SEGMENTS, (t, in) -> FxRender.fade(palette.at((t + shift) % 1), in ? alpha : 0));
			// A quick flash at the centre
			if (progress < 0.35f) {
				float flash = (1 - progress / 0.35f) * 0.6f;
				FxRender.ring(light, matrices.last(), 0, inner * 0.6f, 0, SEGMENTS, (t, in) -> FxRender.fade(FxRender.lighten(palette.at(t), 0.5f), in ? flash : 0));
			}
			matrices.popPose();
		}
	}
}
