package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.ChatUtil;
import dev.femboyware.util.InventoryUtil;
import dev.femboyware.util.Rotation;
import dev.femboyware.util.Timer;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Two ways into walls:
 * <ul>
 *   <li><b>Pearl</b>: throws an ender pearl at the side of the block next to your feet, so it
 *   lands you half inside it, the block covering your lower body. While on, you aren't pushed back
 *   out; it turns itself off once you walk out of the block.</li>
 *   <li><b>Clip</b>: walking into a wall teleports you to the first free spot behind it.</li>
 * </ul>
 */
public class PhaseModule extends Module {
	public enum Mode { PEARL, CLIP }

	/** Give up if the pearl hasn't landed us in a block within this long. */
	private static final long PEARL_TIMEOUT = 3000;

	private final EnumSetting<Mode> mode = add(new EnumSetting<>("Mode", "Pearl into the block beside you, or clip through walls you walk into", Mode.PEARL));
	private final NumberSetting distance = add(new NumberSetting("Distance", "How far through walls to look for space", 2, 0.5, 6, 0.1)).visibleWhen(() -> mode.is(Mode.CLIP));
	private final NumberSetting delay = add(new NumberSetting("Delay", "Milliseconds between clips", 250, 0, 2000, 10)).visibleWhen(() -> mode.is(Mode.CLIP));

	private final Timer timer = new Timer();
	/** Pearl mode: thrown, and now waiting for it to land us in the block. */
	private boolean thrown;
	/** Pearl mode: we've been inside the block, so leaving it ends the phase. */
	private boolean inside;

	public PhaseModule() {
		super("Phase", "Pearls you into a block, or clips you through walls", Category.RAGE);
	}

	@Override
	public String getInfo() {
		return mode.get() == Mode.PEARL ? (inside ? "in" : null) : null;
	}

	@Override
	protected void onEnable() {
		thrown = false;
		inside = false;
		timer.reset();
	}

	/** Pearl mode keeps you from being pushed out of the block you phased into; read by ClientPlayerEntityMixin. */
	public boolean holdInBlock() {
		return isEnabled() && mode.is(Mode.PEARL);
	}

	@Override
	public void onTick() {
		if (mode.is(Mode.CLIP)) {
			clip();
			return;
		}
		if (!thrown) {
			throwPearl();
			return;
		}
		boolean inBlock = !mc.world.isSpaceEmpty(mc.player, mc.player.getBoundingBox().contract(0.01));
		if (inBlock) inside = true;
		// Done once we've walked back out, or if the pearl never got us in
		if ((inside && !inBlock) || (!inside && timer.passed(PEARL_TIMEOUT))) setEnabled(false);
	}

	// ---------------------------------------------------------------- pearl

	private void throwPearl() {
		int slot = InventoryUtil.findHotbar(stack -> stack.isOf(Items.ENDER_PEARL));
		if (slot < 0) {
			ChatUtil.info("Phase: no ender pearl in your hotbar");
			setEnabled(false);
			return;
		}
		Vec3d aim = aimPoint();
		if (aim == null) {
			ChatUtil.info("Phase: no solid block next to your feet");
			setEnabled(false);
			return;
		}
		if (mc.player.getItemCooldownManager().isCoolingDown(mc.player.getInventory().getStack(slot))) return;

		// The throw packet carries the look direction, so face the spot just for the throw
		Rotation rotation = Rotation.between(mc.player.getEyePos(), aim);
		float yaw = mc.player.getYaw();
		float pitch = mc.player.getPitch();
		mc.player.setYaw(rotation.yaw());
		mc.player.setPitch(rotation.pitch());
		InventoryUtil.withSlot(slot, InventoryUtil.SwitchMode.SILENT, () -> mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND));
		mc.player.setYaw(yaw);
		mc.player.setPitch(pitch);
		thrown = true;
		timer.reset();
	}

	/**
	 * Where to throw: low on the face of the solid block beside our feet, in the direction we're
	 * facing or the nearest solid side. The pearl lands on that face, putting us half inside it.
	 */
	private Vec3d aimPoint() {
		BlockPos feet = mc.player.getBlockPos();
		Direction facing = Direction.fromHorizontalDegrees(mc.player.getYaw());
		Direction best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Direction side : Direction.Type.HORIZONTAL) {
			BlockPos block = feet.offset(side);
			if (!mc.world.getBlockState(block).isFullCube(mc.world, block)) continue;
			// Facing it wins; otherwise whichever face is closest
			double distance = side == facing ? -1 : faceDistance(side);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = side;
			}
		}
		if (best == null) return null;
		BlockPos block = feet.offset(best);
		Vec3d pos = mc.player.getEntityPos();
		// On the face, across from where we stand, just above the floor so the pearl hits the face and not the ground
		double faceX = best.getOffsetX() == 0 ? MathHelper.clamp(pos.x, block.getX() + 0.2, block.getX() + 0.8)
			: best.getOffsetX() > 0 ? block.getX() + 0.001 : block.getX() + 0.999;
		double faceZ = best.getOffsetZ() == 0 ? MathHelper.clamp(pos.z, block.getZ() + 0.2, block.getZ() + 0.8)
			: best.getOffsetZ() > 0 ? block.getZ() + 0.001 : block.getZ() + 0.999;
		return new Vec3d(faceX, feet.getY() + 0.15, faceZ);
	}

	private double faceDistance(Direction side) {
		Vec3d pos = mc.player.getEntityPos();
		BlockPos feet = mc.player.getBlockPos();
		return switch (side) {
			case EAST -> feet.getX() + 1 - pos.x;
			case WEST -> pos.x - feet.getX();
			case SOUTH -> feet.getZ() + 1 - pos.z;
			case NORTH -> pos.z - feet.getZ();
			default -> Double.MAX_VALUE;
		};
	}

	// ---------------------------------------------------------------- clip

	private void clip() {
		if (!mc.player.horizontalCollision || !mc.player.input.hasForwardMovement() || !timer.passed(delay.get())) return;
		double radians = Math.toRadians(mc.player.getYaw());
		Vec3d direction = new Vec3d(-Math.sin(radians), 0, Math.cos(radians));
		Box box = mc.player.getBoundingBox();
		for (double d = 0.3; d <= distance.get(); d += 0.1) {
			Vec3d offset = direction.multiply(d);
			if (!mc.world.isSpaceEmpty(mc.player, box.offset(offset))) continue;
			Vec3d target = mc.player.getEntityPos().add(offset);
			mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(target, mc.player.isOnGround(), false));
			mc.player.setPosition(target);
			timer.reset();
			return;
		}
	}
}
