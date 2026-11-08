package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.ChatUtil;
import dev.femboyware.util.InventoryUtil;
import dev.femboyware.util.Rotation;
import dev.femboyware.util.Timer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

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
		boolean inBlock = !mc.level.noCollision(mc.player, mc.player.getBoundingBox().deflate(0.01));
		if (inBlock) inside = true;
		// Done once we've walked back out, or if the pearl never got us in
		if ((inside && !inBlock) || (!inside && timer.passed(PEARL_TIMEOUT))) setEnabled(false);
	}

	// ---------------------------------------------------------------- pearl

	private void throwPearl() {
		int slot = InventoryUtil.findHotbar(stack -> stack.is(Items.ENDER_PEARL));
		if (slot < 0) {
			ChatUtil.info("Phase: no ender pearl in your hotbar");
			setEnabled(false);
			return;
		}
		Vec3 aim = aimPoint();
		if (aim == null) {
			ChatUtil.info("Phase: no solid block next to your feet");
			setEnabled(false);
			return;
		}
		if (mc.player.getCooldowns().isOnCooldown(mc.player.getInventory().getItem(slot))) return;

		// The throw packet carries the look direction, so face the spot just for the throw
		Rotation rotation = Rotation.between(mc.player.getEyePosition(), aim);
		float yaw = mc.player.getYRot();
		float pitch = mc.player.getXRot();
		mc.player.setYRot(rotation.yaw());
		mc.player.setXRot(rotation.pitch());
		InventoryUtil.withSlot(slot, InventoryUtil.SwitchMode.SILENT, () -> mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND));
		mc.player.setYRot(yaw);
		mc.player.setXRot(pitch);
		thrown = true;
		timer.reset();
	}

	/**
	 * Where to throw: low on the face of the solid block beside our feet, in the direction we're
	 * facing or the nearest solid side. The pearl lands on that face, putting us half inside it.
	 */
	private Vec3 aimPoint() {
		BlockPos feet = mc.player.blockPosition();
		Direction facing = Direction.fromYRot(mc.player.getYRot());
		Direction best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Direction side : Direction.Plane.HORIZONTAL) {
			BlockPos block = feet.relative(side);
			if (!mc.level.getBlockState(block).isCollisionShapeFullBlock(mc.level, block)) continue;
			// Facing it wins; otherwise whichever face is closest
			double distance = side == facing ? -1 : faceDistance(side);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = side;
			}
		}
		if (best == null) return null;
		BlockPos block = feet.relative(best);
		Vec3 pos = mc.player.position();
		// On the face, across from where we stand, just above the floor so the pearl hits the face and not the ground
		double faceX = best.getStepX() == 0 ? Mth.clamp(pos.x, block.getX() + 0.2, block.getX() + 0.8)
			: best.getStepX() > 0 ? block.getX() + 0.001 : block.getX() + 0.999;
		double faceZ = best.getStepZ() == 0 ? Mth.clamp(pos.z, block.getZ() + 0.2, block.getZ() + 0.8)
			: best.getStepZ() > 0 ? block.getZ() + 0.001 : block.getZ() + 0.999;
		return new Vec3(faceX, feet.getY() + 0.15, faceZ);
	}

	private double faceDistance(Direction side) {
		Vec3 pos = mc.player.position();
		BlockPos feet = mc.player.blockPosition();
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
		if (!mc.player.horizontalCollision || !mc.player.input.hasForwardImpulse() || !timer.passed(delay.get())) return;
		double radians = Math.toRadians(mc.player.getYRot());
		Vec3 direction = new Vec3(-Math.sin(radians), 0, Math.cos(radians));
		AABB box = mc.player.getBoundingBox();
		for (double d = 0.3; d <= distance.get(); d += 0.1) {
			Vec3 offset = direction.scale(d);
			if (!mc.level.noCollision(mc.player, box.move(offset))) continue;
			Vec3 target = mc.player.position().add(offset);
			mc.getConnection().send(new ServerboundMovePlayerPacket.Pos(target, mc.player.onGround(), false));
			mc.player.setPos(target);
			timer.reset();
			return;
		}
	}
}
