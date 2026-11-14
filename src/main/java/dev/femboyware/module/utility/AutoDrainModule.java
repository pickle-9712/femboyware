package dev.femboyware.module.utility;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.InventoryUtil;
import dev.femboyware.util.Rotation;
import dev.femboyware.util.Timer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Scoops up still water round you with your empty buckets, nearest first, while you have buckets to fill. */
public class AutoDrainModule extends Module {
	private final NumberSetting range = add(new NumberSetting("Range", "How far to reach", 4.5, 1, 5, 0.5));
	private final NumberSetting delay = add(new NumberSetting("Delay", "Milliseconds between scoops", 150, 0, 1000, 10));

	private final Timer timer = new Timer();

	public AutoDrainModule() {
		super("Auto Drain", "Scoop up nearby still water with empty buckets", Category.WORLD);
	}

	@Override
	public void onTick() {
		if (mc.player == null || mc.level == null || mc.gameMode == null || !timer.passed(delay.get())) return;
		int slot = InventoryUtil.findHotbar(stack -> stack.is(Items.BUCKET));
		if (slot < 0) return;
		Vec3 eyes = mc.player.getEyePosition();
		int r = (int) Math.ceil(range.get());
		BlockPos feet = mc.player.blockPosition(), best = null;
		double bestDist = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-r, -r, -r), feet.offset(r, r, r))) {
			var fluid = mc.level.getFluidState(pos);
			if (!fluid.isSource() || !fluid.is(FluidTags.WATER)) continue;
			Vec3 at = Vec3.atCenterOf(pos);
			double d = eyes.distanceToSqr(at);
			if (d > range.get() * range.get() || d >= bestDist) continue;
			// The bucket picks up the first water its line meets, so it has to be this one
			BlockHitResult line = mc.level.clip(new ClipContext(eyes, at, ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, mc.player));
			if (line.getType() == HitResult.Type.BLOCK && !line.getBlockPos().equals(pos)) continue;
			best = pos.immutable();
			bestDist = d;
		}
		if (best == null) return;
		int previous = mc.player.getInventory().getSelectedSlot();
		InventoryUtil.select(slot);
		float yaw = mc.player.getYRot(), pitch = mc.player.getXRot();
		Rotation look = Rotation.between(eyes, Vec3.atCenterOf(best));
		mc.player.setYRot(look.yaw());
		mc.player.setXRot(look.pitch());
		mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
		mc.player.swing(InteractionHand.MAIN_HAND);
		mc.player.setYRot(yaw);
		mc.player.setXRot(pitch);
		InventoryUtil.select(previous);
		timer.reset();
	}
}
