package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.InventoryUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Lands a long fall in water: just before you hit the ground it switches to your water bucket,
 * looks down and places it, then scoops the water back up once you've landed.
 */
public class AutoMlgModule extends Module {
	private final NumberSetting minFall = add(new NumberSetting("Min Fall", "Only for falls of at least this many blocks", 5, 3, 30, 0.5));
	private final BoolSetting pickUp = add(new BoolSetting("Pick Up", "Scoop the water back up after landing", true));
	private final BoolSetting switchBack = add(new BoolSetting("Switch Back", "Go back to the slot you were holding", true));

	private BlockPos placed;
	private int previousSlot = -1, pickupIn;

	public AutoMlgModule() {
		super("Auto MLG", "Place water below you before a long fall lands", Category.BASIC);
	}

	@Override
	protected void onDisable() {
		placed = null;
		previousSlot = -1;
	}

	@Override
	public void onTick() {
		if (mc.player == null || mc.level == null || mc.gameMode == null) return;
		var p = mc.player;
		// Landed: scoop it back up, then go back to what you were holding
		if (placed != null) {
			if (!p.onGround() && !p.isInWater()) return;
			if (pickUp.get() && --pickupIn <= 0) {
				if (p.getMainHandItem().is(Items.BUCKET) && mc.level.getFluidState(placed).isSource()) use(Vec3.atCenterOf(placed));
				finish();
			} else if (!pickUp.get()) finish();
			return;
		}
		if (p.onGround() || p.isFallFlying() || p.getAbilities().flying || p.isInWater() || p.fallDistance < minFall.get() - 1) return;
		if (p.getDeltaMovement().y > -0.5) return;
		// What's below, and how far
		Vec3 from = p.position();
		BlockHitResult ground = mc.level.clip(new ClipContext(from, from.add(0, -6, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, p));
		if (ground.getType() != HitResult.Type.BLOCK) return;
		// As soon as the ground's in reach of your eyes (the server checks from where you were last
		// tick, which is where you are now, before this tick's fall)
		if (p.getEyePosition().distanceTo(ground.getLocation()) > p.blockInteractionRange() - 0.3) return;
		if (!mc.level.getFluidState(ground.getBlockPos()).isEmpty()) return;
		int slot = InventoryUtil.findHotbar(stack -> stack.is(Items.WATER_BUCKET));
		if (slot < 0) return;
		previousSlot = p.getInventory().getSelectedSlot();
		InventoryUtil.select(slot);
		use(ground.getLocation());
		placed = ground.getBlockPos().above();
		pickupIn = 4;
	}

	/** Looks at {@code at} for this one use, then back where you were looking. */
	private void use(Vec3 at) {
		var p = mc.player;
		float yaw = p.getYRot(), pitch = p.getXRot();
		var r = dev.femboyware.util.Rotation.between(p.getEyePosition(), at);
		p.setYRot(r.yaw());
		p.setXRot(r.pitch());
		mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
		p.swing(InteractionHand.MAIN_HAND);
		p.setYRot(yaw);
		p.setXRot(pitch);
	}

	private void finish() {
		if (switchBack.get() && previousSlot >= 0) InventoryUtil.select(previousSlot);
		placed = null;
		previousSlot = -1;
	}
}
