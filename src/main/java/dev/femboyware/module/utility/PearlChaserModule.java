package dev.femboyware.module.utility;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.InventoryUtil;
import dev.femboyware.util.InventoryUtil.SwitchMode;
import dev.femboyware.util.Rotation;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * When another player throws an ender pearl, works out where it will land and throws your own
 * pearl at the same spot, so they can't escape.
 */
public class PearlChaserModule extends Module {
	private static final double GRAVITY = 0.03;
	private static final double DRAG = 0.99;
	private static final double POWER = 1.5;

	private final NumberSetting range = add(new NumberSetting("Range", "Only chase players this close", 16, 2, 64, 1));
	private final NumberSetting accuracy = add(new NumberSetting("Accuracy", "Only throw if our pearl lands this close to theirs", 3, 0.5, 10, 0.5));
	private final EnumSetting<SwitchMode> switchMode = add(new EnumSetting<>("Switch", "How to switch to pearls", SwitchMode.SILENT));

	private final IntSet seen = new IntOpenHashSet();

	public PearlChaserModule() {
		super("Pearl Chaser", "Pearls after players who pearl away", Category.WORLD);
	}

	@Override
	protected void onDisable() {
		seen.clear();
	}

	@Override
	public void onTick() {
		IntSet alive = new IntOpenHashSet();
		for (Entity entity : mc.world.getEntities()) {
			if (!(entity instanceof EnderPearlEntity pearl)) continue;
			alive.add(pearl.getId());
			if (!seen.add(pearl.getId())) continue;
			if (!(pearl.getOwner() instanceof PlayerEntity owner) || owner == mc.player || mc.player.distanceTo(owner) > range.get()) continue;
			Vec3d landing = simulate(pearl.getEntityPos(), pearl.getVelocity(), pearl);
			if (landing != null) chase(landing);
		}
		seen.retainAll(alive);
	}

	private void chase(Vec3d landing) {
		int slot = InventoryUtil.findHotbar(stack -> stack.isOf(Items.ENDER_PEARL));
		if (slot < 0 || mc.player.getItemCooldownManager().isCoolingDown(mc.player.getInventory().getStack(slot))) return;

		Vec3d eyes = mc.player.getEyePos().add(0, -0.1, 0);
		float yaw = Rotation.between(eyes, landing).yaw();
		float bestPitch = 0;
		double bestError = Double.MAX_VALUE;
		Vec3d own = mc.player.getVelocity();
		Vec3d inherited = new Vec3d(own.x, mc.player.isOnGround() ? 0 : own.y, own.z);
		for (float pitch = -89; pitch <= 89; pitch += 1f) {
			Vec3d velocity = direction(pitch, yaw).multiply(POWER).add(inherited);
			Vec3d end = simulate(eyes, velocity, mc.player);
			if (end == null) continue;
			double error = end.squaredDistanceTo(landing);
			if (error < bestError) {
				bestError = error;
				bestPitch = pitch;
			}
		}
		if (bestError > accuracy.get() * accuracy.get()) return;

		float pitch = bestPitch;
		float savedYaw = mc.player.getYaw();
		float savedPitch = mc.player.getPitch();
		InventoryUtil.withSlot(slot, switchMode.get(), () -> {
			mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, mc.player.isOnGround(), mc.player.horizontalCollision));
			// The use packet carries the player's rotation
			mc.player.setYaw(yaw);
			mc.player.setPitch(pitch);
			mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
			mc.player.swingHand(Hand.MAIN_HAND);
			mc.player.setYaw(savedYaw);
			mc.player.setPitch(savedPitch);
		});
	}

	/** Where a pearl starting at {@code pos} with {@code velocity} hits a block, or null if it never does. */
	private Vec3d simulate(Vec3d pos, Vec3d velocity, Entity ignored) {
		for (int i = 0; i < 300; i++) {
			Vec3d next = pos.add(velocity);
			HitResult hit = mc.world.raycast(new RaycastContext(pos, next, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, ignored));
			if (hit.getType() != HitResult.Type.MISS) return hit.getPos();
			pos = next;
			velocity = velocity.multiply(DRAG).add(0, -GRAVITY, 0);
			if (pos.y < mc.world.getBottomY()) return null;
		}
		return null;
	}

	private static Vec3d direction(float pitch, float yaw) {
		float yawRad = yaw * MathHelper.RADIANS_PER_DEGREE;
		float pitchRad = pitch * MathHelper.RADIANS_PER_DEGREE;
		return new Vec3d(-MathHelper.sin(yawRad) * MathHelper.cos(pitchRad), -MathHelper.sin(pitchRad), MathHelper.cos(yawRad) * MathHelper.cos(pitchRad));
	}
}
