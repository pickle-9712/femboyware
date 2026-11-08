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
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

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
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (!(entity instanceof ThrownEnderpearl pearl)) continue;
			alive.add(pearl.getId());
			if (!seen.add(pearl.getId())) continue;
			if (!(pearl.getOwner() instanceof Player owner) || owner == mc.player || mc.player.distanceTo(owner) > range.get()) continue;
			Vec3 landing = simulate(pearl.position(), pearl.getDeltaMovement(), pearl);
			if (landing != null) chase(landing);
		}
		seen.retainAll(alive);
	}

	private void chase(Vec3 landing) {
		int slot = InventoryUtil.findHotbar(stack -> stack.is(Items.ENDER_PEARL));
		if (slot < 0 || mc.player.getCooldowns().isOnCooldown(mc.player.getInventory().getItem(slot))) return;

		Vec3 eyes = mc.player.getEyePosition().add(0, -0.1, 0);
		float yaw = Rotation.between(eyes, landing).yaw();
		float bestPitch = 0;
		double bestError = Double.MAX_VALUE;
		Vec3 own = mc.player.getDeltaMovement();
		Vec3 inherited = new Vec3(own.x, mc.player.onGround() ? 0 : own.y, own.z);
		for (float pitch = -89; pitch <= 89; pitch += 1f) {
			Vec3 velocity = direction(pitch, yaw).scale(POWER).add(inherited);
			Vec3 end = simulate(eyes, velocity, mc.player);
			if (end == null) continue;
			double error = end.distanceToSqr(landing);
			if (error < bestError) {
				bestError = error;
				bestPitch = pitch;
			}
		}
		if (bestError > accuracy.get() * accuracy.get()) return;

		float pitch = bestPitch;
		float savedYaw = mc.player.getYRot();
		float savedPitch = mc.player.getXRot();
		InventoryUtil.withSlot(slot, switchMode.get(), () -> {
			mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(yaw, pitch, mc.player.onGround(), mc.player.horizontalCollision));
			// The use packet carries the player's rotation
			mc.player.setYRot(yaw);
			mc.player.setXRot(pitch);
			mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
			mc.player.swing(InteractionHand.MAIN_HAND);
			mc.player.setYRot(savedYaw);
			mc.player.setXRot(savedPitch);
		});
	}

	/** Where a pearl starting at {@code pos} with {@code velocity} hits a block, or null if it never does. */
	private Vec3 simulate(Vec3 pos, Vec3 velocity, Entity ignored) {
		for (int i = 0; i < 300; i++) {
			Vec3 next = pos.add(velocity);
			HitResult hit = mc.level.clip(new ClipContext(pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ignored));
			if (hit.getType() != HitResult.Type.MISS) return hit.getLocation();
			pos = next;
			velocity = velocity.scale(DRAG).add(0, -GRAVITY, 0);
			if (pos.y < mc.level.getMinY()) return null;
		}
		return null;
	}

	private static Vec3 direction(float pitch, float yaw) {
		float yawRad = yaw * Mth.DEG_TO_RAD;
		float pitchRad = pitch * Mth.DEG_TO_RAD;
		return new Vec3(-Mth.sin(yawRad) * Mth.cos(pitchRad), -Mth.sin(pitchRad), Mth.cos(yawRad) * Mth.cos(pitchRad));
	}
}
