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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

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
		if (current != null && !nullCheck()) mc.gameMode.stopDestroyBlock();
		current = null;
	}

	@Override
	public void onTick() {
		int instant = 0;
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		int r = (int) Math.ceil(range.get());
		BlockPos feet = mc.player.blockPosition();
		Vec3 eyes = mc.player.getEyePosition();
		for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-r, mode.is(Mode.FLATTEN) ? 0 : -r, -r), feet.offset(r, r, r))) {
			double distance = eyes.distanceToSqr(Vec3.atCenterOf(pos));
			if (distance > range.get() * range.get() || !isMineable(pos)) continue;
			BlockState state = mc.level.getBlockState(pos);
			// Blocks that break in one hit can be cleared several per tick
			if (state.getDestroyProgress(mc.player, mc.level, pos) >= 1f && instant < instantPerTick.getInt()) {
				mine(pos.immutable(), true);
				instant++;
				continue;
			}
			if (distance < bestDistance) {
				bestDistance = distance;
				best = pos.immutable();
			}
		}
		if (instant > 0) return;
		current = best;
		if (best != null) mine(best, false);
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (render.get() && current != null) RenderUtil.drawBox(event, new AABB(current), color.get(), color.withAlpha(255), 1.5f);
	}

	private void mine(BlockPos pos, boolean instant) {
		if (rotate.get()) PlaceUtil.lookAt(Vec3.atCenterOf(pos));
		// Starting a break finishes instant blocks immediately; slower blocks need progress every tick
		if (instant) mc.gameMode.startDestroyBlock(pos, Direction.UP);
		else mc.gameMode.continueDestroyBlock(pos, Direction.UP);
		mc.player.swing(InteractionHand.MAIN_HAND);
	}

	private boolean isMineable(BlockPos pos) {
		BlockState state = mc.level.getBlockState(pos);
		if (state.isAir() || !state.getFluidState().isEmpty() && state.canBeReplaced()) return false;
		return state.getDestroySpeed(mc.level, pos) >= 0;
	}
}
