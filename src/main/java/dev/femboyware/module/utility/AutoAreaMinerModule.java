package dev.femboyware.module.utility;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.ChatUtil;
import dev.femboyware.util.InventoryUtil;
import dev.femboyware.util.PlaceUtil;
import dev.femboyware.util.RenderUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Mines out a box you mark with {@code .area pos1} and {@code .area pos2}, top layer first, walking
 * over to whatever's left when nothing's in reach. It won't dig next to lava, and stops when the
 * box is empty.
 */
public class AutoAreaMinerModule extends Module {
	private final NumberSetting reach = add(new NumberSetting("Reach", "How far to mine from", 4.5, 2, 6, 0.5));
	private final BoolSetting walk = add(new BoolSetting("Walk", "Walk to blocks out of reach", true));
	private final BoolSetting avoidLava = add(new BoolSetting("Avoid Lava", "Skip blocks touching lava", true));
	private final BoolSetting bestTool = add(new BoolSetting("Best Tool", "Hold the fastest tool for each block", true));
	private final ColorSetting color = add(new ColorSetting("Color", "Colour of the area", 0xFF55CCFF));

	private BlockPos pos1, pos2, current;
	private int left;

	public AutoAreaMinerModule() {
		super("Auto Area Miner", "Mines out an area you mark with .area pos1 / pos2", Category.BUILD);
	}

	@Override
	public String getInfo() {
		return pos1 == null || pos2 == null ? null : Integer.toString(left);
	}

	/** {@code .area <pos1|pos2|clear>}: marks the block you're looking at (or under you). */
	public void command(String[] args) {
		if (args.length < 2) {
			ChatUtil.info("Usage: .area <pos1|pos2|clear>  (marks the block you look at; turn on Auto Area Miner to dig it out)");
			return;
		}
		BlockPos at = mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : mc.player.blockPosition().below();
		switch (args[1].toLowerCase(java.util.Locale.ROOT)) {
			case "pos1", "1" -> {
				pos1 = at;
				ChatUtil.info("Area corner 1: " + ChatUtil.coords(at));
			}
			case "pos2", "2" -> {
				pos2 = at;
				ChatUtil.info("Area corner 2: " + ChatUtil.coords(at) + (pos1 != null ? "  (" + volume() + " blocks)" : ""));
			}
			case "clear" -> {
				pos1 = pos2 = null;
				ChatUtil.info("Area cleared");
			}
			default -> ChatUtil.info("Usage: .area <pos1|pos2|clear>");
		}
	}

	private int volume() {
		return (Math.abs(pos1.getX() - pos2.getX()) + 1) * (Math.abs(pos1.getY() - pos2.getY()) + 1) * (Math.abs(pos1.getZ() - pos2.getZ()) + 1);
	}

	@Override
	protected void onEnable() {
		if (pos1 == null || pos2 == null) {
			ChatUtil.info("Auto Area Miner: mark the area first with .area pos1 and .area pos2");
			setEnabled(false);
		}
	}

	@Override
	protected void onDisable() {
		if (mc.options != null) {
			mc.options.keyUp.setDown(false);
			mc.options.keyJump.setDown(false);
		}
		if (current != null && mc.gameMode != null) mc.gameMode.stopDestroyBlock();
		current = null;
	}

	private boolean mineable(BlockPos pos) {
		BlockState state = mc.level.getBlockState(pos);
		if (state.isAir() || !state.getFluidState().isEmpty() || state.getDestroySpeed(mc.level, pos) < 0) return false;
		if (avoidLava.get()) {
			for (Direction d : Direction.values()) if (mc.level.getFluidState(pos.relative(d)).is(net.minecraft.tags.FluidTags.LAVA)) return false;
		}
		return true;
	}

	@Override
	public void onTick() {
		var p = mc.player;
		Vec3 eyes = p.getEyePosition();
		double reachSq = reach.get() * reach.get();
		// The highest layer with anything left, then the nearest block on it
		BlockPos inReach = null, anywhere = null;
		double nearReach = Double.MAX_VALUE, nearAny = Double.MAX_VALUE;
		int top = Integer.MIN_VALUE;
		left = 0;
		for (BlockPos pos : BlockPos.betweenClosed(pos1, pos2)) {
			if (!mineable(pos)) continue;
			left++;
			// Not the block you're standing on, unless it's the last layer you can do from here
			if (pos.equals(p.blockPosition().below()) && pos.getY() > Math.min(pos1.getY(), pos2.getY())) continue;
			if (pos.getY() > top) {
				top = pos.getY();
				nearAny = Double.MAX_VALUE;
				nearReach = Double.MAX_VALUE;
				inReach = anywhere = null;
			}
			if (pos.getY() < top) continue;
			double d = eyes.distanceToSqr(Vec3.atCenterOf(pos));
			if (d < nearAny) {
				nearAny = d;
				anywhere = pos.immutable();
			}
			if (d <= reachSq && d < nearReach) {
				nearReach = d;
				inReach = pos.immutable();
			}
		}
		if (left == 0) {
			ChatUtil.info("Auto Area Miner: area mined out");
			setEnabled(false);
			return;
		}
		if (inReach != null) {
			mc.options.keyUp.setDown(false);
			mc.options.keyJump.setDown(false);
			if (bestTool.get()) holdBest(mc.level.getBlockState(inReach));
			PlaceUtil.lookAt(Vec3.atCenterOf(inReach));
			if (!inReach.equals(current)) {
				mc.gameMode.startDestroyBlock(inReach, Direction.UP);
				current = inReach;
			} else mc.gameMode.continueDestroyBlock(inReach, Direction.UP);
			p.swing(InteractionHand.MAIN_HAND);
			return;
		}
		current = null;
		if (!walk.get() || anywhere == null) return;
		// Walk towards it
		Vec3 to = Vec3.atCenterOf(anywhere);
		p.setYRot((float) Math.toDegrees(Math.atan2(to.z - p.getZ(), to.x - p.getX())) - 90);
		p.setXRot(Mth.clamp(p.getXRot(), -20, 30));
		mc.options.keyUp.setDown(true);
		mc.options.keyJump.setDown(p.horizontalCollision && p.onGround());
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
		if (pos1 == null || pos2 == null) return;
		AABB box = new AABB(Math.min(pos1.getX(), pos2.getX()), Math.min(pos1.getY(), pos2.getY()), Math.min(pos1.getZ(), pos2.getZ()),
			Math.max(pos1.getX(), pos2.getX()) + 1, Math.max(pos1.getY(), pos2.getY()) + 1, Math.max(pos1.getZ(), pos2.getZ()) + 1);
		RenderUtil.drawBox(event, box, (color.get() & 0x00FFFFFF) | 0x10000000, color.get(), 1.5f, true);
		if (current != null) RenderUtil.drawBox(event, new AABB(current), 0x40FFFFFF, 0xFFFFFFFF, 1.5f, false);
	}
}
