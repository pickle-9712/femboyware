package dev.femboyware.module.utility;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.ChatUtil;
import dev.femboyware.util.InventoryUtil;
import dev.femboyware.util.PlaceUtil;
import dev.femboyware.util.RenderUtil;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Digs a straight tunnel the way you were facing when you turned it on and walks down it. It stops
 * before breaking into lava or water, and before a drop it can't see the bottom of.
 */
public class AutoTunnelModule extends Module {
	public enum Size {
		ONE_BY_TWO(1, 2), TWO_BY_TWO(2, 2), THREE_BY_THREE(3, 3), FIVE_BY_FIVE(5, 5);

		final int width, height;

		Size(int width, int height) {
			this.width = width;
			this.height = height;
		}
	}

	private final EnumSetting<Size> size = add(new EnumSetting<>("Size", "Width and height of the tunnel", Size.ONE_BY_TWO));
	private final BoolSetting walk = add(new BoolSetting("Walk", "Walk down the tunnel as it's dug", true));
	private final BoolSetting avoidFluids = add(new BoolSetting("Avoid Fluids", "Stop before digging into lava or water", true));
	private final BoolSetting avoidDrops = add(new BoolSetting("Avoid Drops", "Stop at a hole in the floor", true));
	private final BoolSetting fillFloor = add(new BoolSetting("Fill Floor", "Place blocks over holes in the floor instead of stopping", false));
	private final BoolSetting bestTool = add(new BoolSetting("Best Tool", "Hold the fastest tool for each block", true));
	private final ColorSetting color = add(new ColorSetting("Color", "Highlight of what it's digging", 0x40FFAA33));

	private Direction facing;
	private BlockPos start;
	private BlockPos current;

	public AutoTunnelModule() {
		super("Auto Tunnel", "Digs and walks a straight tunnel", Category.WORLD);
	}

	@Override
	protected void onEnable() {
		if (nullCheck()) {
			setEnabled(false);
			return;
		}
		facing = mc.player.getDirection();
		start = mc.player.blockPosition();
	}

	@Override
	protected void onDisable() {
		if (mc.options != null) mc.options.keyUp.setDown(false);
		if (current != null && mc.gameMode != null) mc.gameMode.stopDestroyBlock();
		current = null;
	}

	@Override
	public String getInfo() {
		return facing == null ? null : facing.getName();
	}

	/** The cross-section of the tunnel {@code ahead} blocks in front of you. */
	private List<BlockPos> slice(int ahead) {
		List<BlockPos> blocks = new ArrayList<>();
		Size s = size.get();
		BlockPos feet = mc.player.blockPosition();
		// Keep to the line it started on so it doesn't drift sideways
		BlockPos base = facing.getAxis() == Direction.Axis.X ? new BlockPos(feet.getX(), feet.getY(), start.getZ()) : new BlockPos(start.getX(), feet.getY(), feet.getZ());
		base = base.relative(facing, ahead);
		Direction side = facing.getClockWise();
		int left = -(s.width - 1) / 2;
		for (int w = left; w < left + s.width; w++) {
			for (int h = 0; h < s.height; h++) blocks.add(base.relative(side, w).above(h - (s.height > 2 ? (s.height - 2) / 2 : 0)));
		}
		return blocks;
	}

	@Override
	public void onTick() {
		var p = mc.player;
		// Keep facing straight down the tunnel
		p.setYRot(facing.toYRot());
		List<BlockPos> next = new ArrayList<>(slice(0));
		next.addAll(slice(1));

		if (avoidFluids.get()) {
			for (BlockPos pos : next) {
				for (Direction d : Direction.values()) {
					var fluid = mc.level.getFluidState(pos.relative(d));
					if (!fluid.isEmpty()) {
						stop((fluid.is(net.minecraft.tags.FluidTags.LAVA) ? "lava" : "water") + " ahead at " + ChatUtil.coords(pos.relative(d)));
						return;
					}
				}
			}
		}

		// Dig the nearest block left in the way
		BlockPos target = null;
		double nearest = Double.MAX_VALUE;
		for (BlockPos pos : next) {
			BlockState state = mc.level.getBlockState(pos);
			if (state.isAir() || !state.getFluidState().isEmpty() || state.getDestroySpeed(mc.level, pos) < 0) continue;
			double d = p.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos));
			if (d < nearest) {
				nearest = d;
				target = pos;
			}
		}
		if (target != null) {
			mc.options.keyUp.setDown(false);
			if (bestTool.get()) holdBest(mc.level.getBlockState(target));
			PlaceUtil.lookAt(Vec3.atCenterOf(target));
			if (!target.equals(current)) {
				mc.gameMode.startDestroyBlock(target, Direction.UP);
				current = target;
			} else mc.gameMode.continueDestroyBlock(target, Direction.UP);
			p.swing(InteractionHand.MAIN_HAND);
			return;
		}
		current = null;
		p.setXRot(10);

		// The floor of the next slice
		for (BlockPos pos : slice(1)) {
			BlockPos floor = pos.below();
			if (pos.getY() != p.blockPosition().getY() || !mc.level.getBlockState(floor).getCollisionShape(mc.level, floor).isEmpty()) continue;
			if (fillFloor.get() && PlaceUtil.place(floor, stack -> stack.getItem() instanceof net.minecraft.world.item.BlockItem, InventoryUtil.SwitchMode.SILENT, true, false)) return;
			if (avoidDrops.get()) {
				stop("hole in the floor at " + ChatUtil.coords(floor));
				return;
			}
		}
		mc.options.keyUp.setDown(walk.get());
	}

	private void stop(String why) {
		ChatUtil.info("Auto Tunnel: stopped, " + why);
		setEnabled(false);
	}

	private void holdBest(BlockState state) {
		int best = -1;
		float fastest = 1;
		for (int slot = 0; slot < 9; slot++) {
			ItemStack stack = mc.player.getInventory().getItem(slot);
			float speed = stack.getDestroySpeed(state);
			if (speed > fastest) {
				fastest = speed;
				best = slot;
			}
		}
		if (best >= 0 && best != mc.player.getInventory().getSelectedSlot()) InventoryUtil.select(best);
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (current != null) RenderUtil.drawBox(event, new AABB(current), color.get(), color.withAlpha(255), 1.5f);
	}
}
