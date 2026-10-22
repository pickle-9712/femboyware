package dev.femboyware.module.render;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.RenderUtil;
import net.minecraft.entity.Entity;
import net.minecraft.item.BowItem;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.Optional;

/** Draws where the projectile you're holding will fly and land. */
public class TrajectoriesModule extends Module {
	/** Launch speed, gravity and pitch offset of a throwable, copied from the vanilla items and entities. */
	private record Ballistics(double speed, double gravity, float pitchOffset) {
	}

	private final NumberSetting steps = add(new NumberSetting("Steps", "Maximum simulated ticks", 150, 20, 400, 10));
	private final ColorSetting color = add(new ColorSetting("Color", "Path colour", 0xFFFF77C8));
	private final ColorSetting hitColor = add(new ColorSetting("Hit Color", "Landing highlight colour", 0x50FF77C8));

	public TrajectoriesModule() {
		super("Trajectories", "Shows projectile paths", Category.COSMETIC);
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		Ballistics ballistics = ballistics(mc.player.getStackInHand(Hand.MAIN_HAND));
		if (ballistics == null) ballistics = ballistics(mc.player.getStackInHand(Hand.OFF_HAND));
		if (ballistics == null) return;

		float tickDelta = mc.getRenderTickCounter().getTickProgress(true);
		float yaw = mc.player.getYaw(tickDelta);
		float pitch = mc.player.getPitch(tickDelta);
		Vec3d pos = mc.player.getLerpedPos(tickDelta).add(0, mc.player.getStandingEyeHeight() - 0.1, 0);
		Vec3d velocity = direction(pitch + ballistics.pitchOffset, yaw).multiply(ballistics.speed);
		Vec3d own = mc.player.getVelocity();
		velocity = velocity.add(own.x, mc.player.isOnGround() ? 0 : own.y, own.z);

		// Start the line slightly to the side so it doesn't sit in the middle of the crosshair
		Vec3d previous = pos.add(direction(0, yaw - 90).multiply(0.15)).add(0, -0.1, 0);
		for (int i = 0; i < steps.getInt(); i++) {
			Vec3d next = pos.add(velocity);
			HitResult hit = mc.world.raycast(new RaycastContext(pos, next, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));
			Entity entityHit = hitEntity(pos, next);
			if (entityHit != null) {
				RenderUtil.drawLine(event, previous, entityHit.getBoundingBox().getCenter(), color.get(), 2f);
				RenderUtil.drawBox(event, entityHit.getBoundingBox(), hitColor.get(), color.get(), 1.5f);
				return;
			}
			if (hit.getType() == HitResult.Type.BLOCK) {
				RenderUtil.drawLine(event, previous, hit.getPos(), color.get(), 2f);
				RenderUtil.drawBox(event, new Box(((BlockHitResult) hit).getBlockPos()), hitColor.get(), color.get(), 1.5f);
				return;
			}
			RenderUtil.drawLine(event, previous, next, color.get(), 2f);
			previous = next;
			pos = next;
			boolean water = mc.world.getFluidState(BlockPos.ofFloored(pos)).isIn(FluidTags.WATER);
			velocity = velocity.multiply(water ? 0.6 : 0.99).add(0, -ballistics.gravity, 0);
			if (pos.y < mc.world.getBottomY() - 16) return;
		}
	}

	private Entity hitEntity(Vec3d from, Vec3d to) {
		Box sweep = new Box(from, to).expand(0.3);
		Entity best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Entity entity : mc.world.getOtherEntities(mc.player, sweep, entity -> entity.canHit() && !entity.isSpectator())) {
			Optional<Vec3d> point = entity.getBoundingBox().expand(0.3).raycast(from, to);
			if (point.isEmpty()) continue;
			double distance = from.squaredDistanceTo(point.get());
			if (distance < bestDistance) {
				bestDistance = distance;
				best = entity;
			}
		}
		return best;
	}

	private Ballistics ballistics(ItemStack stack) {
		Item item = stack.getItem();
		if (item instanceof BowItem) {
			if (!mc.player.isUsingItem()) return null;
			int useTicks = stack.getMaxUseTime(mc.player) - mc.player.getItemUseTimeLeft();
			float pull = BowItem.getPullProgress(useTicks);
			return pull < 0.1f ? null : new Ballistics(pull * 3.0, 0.05, 0);
		}
		if (item instanceof CrossbowItem) return CrossbowItem.isCharged(stack) ? new Ballistics(3.15, 0.05, 0) : null;
		if (stack.isOf(Items.TRIDENT)) return mc.player.isUsingItem() ? new Ballistics(2.5, 0.05, 0) : null;
		if (stack.isOf(Items.SNOWBALL) || stack.isOf(Items.EGG) || stack.isOf(Items.ENDER_PEARL)) return new Ballistics(1.5, 0.03, 0);
		if (stack.isOf(Items.SPLASH_POTION) || stack.isOf(Items.LINGERING_POTION)) return new Ballistics(0.5, 0.05, -20);
		if (stack.isOf(Items.EXPERIENCE_BOTTLE)) return new Ballistics(0.7, 0.07, -20);
		return null;
	}

	private static Vec3d direction(float pitch, float yaw) {
		float yawRad = yaw * MathHelper.RADIANS_PER_DEGREE;
		float pitchRad = pitch * MathHelper.RADIANS_PER_DEGREE;
		return new Vec3d(-MathHelper.sin(yawRad) * MathHelper.cos(pitchRad), -MathHelper.sin(pitchRad), MathHelper.cos(yawRad) * MathHelper.cos(pitchRad));
	}
}
