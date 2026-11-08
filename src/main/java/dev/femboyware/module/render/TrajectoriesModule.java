package dev.femboyware.module.render;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.RenderUtil;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

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
		Ballistics ballistics = ballistics(mc.player.getItemInHand(InteractionHand.MAIN_HAND));
		if (ballistics == null) ballistics = ballistics(mc.player.getItemInHand(InteractionHand.OFF_HAND));
		if (ballistics == null) return;

		float tickDelta = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		float yaw = mc.player.getViewYRot(tickDelta);
		float pitch = mc.player.getViewXRot(tickDelta);
		Vec3 pos = mc.player.getPosition(tickDelta).add(0, mc.player.getEyeHeight() - 0.1, 0);
		Vec3 velocity = direction(pitch + ballistics.pitchOffset, yaw).scale(ballistics.speed);
		Vec3 own = mc.player.getDeltaMovement();
		velocity = velocity.add(own.x, mc.player.onGround() ? 0 : own.y, own.z);

		// Start the line slightly to the side so it doesn't sit in the middle of the crosshair
		Vec3 previous = pos.add(direction(0, yaw - 90).scale(0.15)).add(0, -0.1, 0);
		for (int i = 0; i < steps.getInt(); i++) {
			Vec3 next = pos.add(velocity);
			HitResult hit = mc.level.clip(new ClipContext(pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
			Entity entityHit = hitEntity(pos, next);
			if (entityHit != null) {
				RenderUtil.drawLine(event, previous, entityHit.getBoundingBox().getCenter(), color.get(), 2f);
				RenderUtil.drawBox(event, entityHit.getBoundingBox(), hitColor.get(), color.get(), 1.5f);
				return;
			}
			if (hit.getType() == HitResult.Type.BLOCK) {
				RenderUtil.drawLine(event, previous, hit.getLocation(), color.get(), 2f);
				RenderUtil.drawBox(event, new AABB(((BlockHitResult) hit).getBlockPos()), hitColor.get(), color.get(), 1.5f);
				return;
			}
			RenderUtil.drawLine(event, previous, next, color.get(), 2f);
			previous = next;
			pos = next;
			boolean water = mc.level.getFluidState(BlockPos.containing(pos)).is(FluidTags.WATER);
			velocity = velocity.scale(water ? 0.6 : 0.99).add(0, -ballistics.gravity, 0);
			if (pos.y < mc.level.getMinY() - 16) return;
		}
	}

	private Entity hitEntity(Vec3 from, Vec3 to) {
		AABB sweep = new AABB(from, to).inflate(0.3);
		Entity best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Entity entity : mc.level.getEntities(mc.player, sweep, entity -> entity.isPickable() && !entity.isSpectator())) {
			Optional<Vec3> point = entity.getBoundingBox().inflate(0.3).clip(from, to);
			if (point.isEmpty()) continue;
			double distance = from.distanceToSqr(point.get());
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
			int useTicks = stack.getUseDuration(mc.player) - mc.player.getUseItemRemainingTicks();
			float pull = BowItem.getPowerForTime(useTicks);
			return pull < 0.1f ? null : new Ballistics(pull * 3.0, 0.05, 0);
		}
		if (item instanceof CrossbowItem) return CrossbowItem.isCharged(stack) ? new Ballistics(3.15, 0.05, 0) : null;
		if (stack.is(Items.TRIDENT)) return mc.player.isUsingItem() ? new Ballistics(2.5, 0.05, 0) : null;
		if (stack.is(Items.SNOWBALL) || stack.is(Items.EGG) || stack.is(Items.ENDER_PEARL)) return new Ballistics(1.5, 0.03, 0);
		if (stack.is(Items.SPLASH_POTION) || stack.is(Items.LINGERING_POTION)) return new Ballistics(0.5, 0.05, -20);
		if (stack.is(Items.EXPERIENCE_BOTTLE)) return new Ballistics(0.7, 0.07, -20);
		return null;
	}

	private static Vec3 direction(float pitch, float yaw) {
		float yawRad = yaw * Mth.DEG_TO_RAD;
		float pitchRad = pitch * Mth.DEG_TO_RAD;
		return new Vec3(-Mth.sin(yawRad) * Mth.cos(pitchRad), -Mth.sin(pitchRad), Mth.cos(yawRad) * Mth.cos(pitchRad));
	}
}
