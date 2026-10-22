package dev.femboyware.module.utility;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.PlaceUtil;
import dev.femboyware.util.RenderUtil;
import net.minecraft.block.BlockState;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/** Mines every block around you, closest first. */
public class NukerModule extends Module {
	public enum Mode {
		/** Everything in range. */
		ALL,
		/** Only blocks at or above your feet, levelling the ground. */
		FLATTEN
	}

	private final EnumSetting<Mode> mode = add(new EnumSetting<>("Mode", "Which blocks to mine", Mode.FLATTEN));
	private final NumberSetting range = add(new NumberSetting("Range", "How far to reach", 4.5, 1, 6, 0.5));
	private final NumberSetting instantPerTick = add(new NumberSetting("Instant Per Tick", "Instantly breakable blocks mined per tick", 4, 1, 20, 1));
	private final BoolSetting rotate = add(new BoolSetting("Rotate", "Face each block while mining", true));
	private final BoolSetting render = add(new BoolSetting("Render", "Highlight the block being mined", true));
	private final ColorSetting color = add(new ColorSetting("Color", "Highlight colour", 0x40FF5577)).visibleWhen(render::get);

	private BlockPos current;

	public NukerModule() {
		super("Nuker", "Breaks blocks around you", Category.BUILD);
	}

	@Override
	protected void onDisable() {
		if (current != null && !nullCheck()) mc.interactionManager.cancelBlockBreaking();
		current = null;
	}

	@Override
	public void onTick() {
		int instant = 0;
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		int r = (int) Math.ceil(range.get());
		BlockPos feet = mc.player.getBlockPos();
		Vec3d eyes = mc.player.getEyePos();
		for (BlockPos pos : BlockPos.iterate(feet.add(-r, mode.is(Mode.FLATTEN) ? 0 : -r, -r), feet.add(r, r, r))) {
			double distance = eyes.squaredDistanceTo(Vec3d.ofCenter(pos));
			if (distance > range.get() * range.get() || !isMineable(pos)) continue;
			BlockState state = mc.world.getBlockState(pos);
			// Blocks that break in one hit can be cleared several per tick
			if (state.calcBlockBreakingDelta(mc.player, mc.world, pos) >= 1f && instant < instantPerTick.getInt()) {
				mine(pos.toImmutable(), true);
				instant++;
				continue;
			}
			if (distance < bestDistance) {
				bestDistance = distance;
				best = pos.toImmutable();
			}
		}
		if (instant > 0) return;
		current = best;
		if (best != null) mine(best, false);
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (render.get() && current != null) RenderUtil.drawBox(event, new Box(current), color.get(), color.withAlpha(255), 1.5f);
	}

	private void mine(BlockPos pos, boolean instant) {
		if (rotate.get()) PlaceUtil.lookAt(Vec3d.ofCenter(pos));
		// Starting a break finishes instant blocks immediately; slower blocks need progress every tick
		if (instant) mc.interactionManager.attackBlock(pos, Direction.UP);
		else mc.interactionManager.updateBlockBreakingProgress(pos, Direction.UP);
		mc.player.swingHand(Hand.MAIN_HAND);
	}

	private boolean isMineable(BlockPos pos) {
		BlockState state = mc.world.getBlockState(pos);
		if (state.isAir() || !state.getFluidState().isEmpty() && state.isReplaceable()) return false;
		return state.getHardness(mc.world, pos) >= 0;
	}
}
