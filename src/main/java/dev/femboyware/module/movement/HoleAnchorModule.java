package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.HoleUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Pulls you into a hole when you walk or fall over one, instead of sliding past it. */
public class HoleAnchorModule extends Module {
	private final NumberSetting depth = add(new NumberSetting("Depth", "How far below you a hole can be", 3, 1, 6, 1));
	private final BoolSetting lookingDown = add(new BoolSetting("Looking Down", "Only anchor while looking downwards", false));
	private final NumberSetting pitch = add(new NumberSetting("Pitch", "Minimum downward pitch", 60, 0, 90, 1)).visibleWhen(lookingDown::get);
	private final BoolSetting pull = add(new BoolSetting("Pull", "Also speed up the fall into the hole", true));

	public HoleAnchorModule() {
		super("Hole Anchor", "Stops you over holes so you drop into them", Category.BASIC);
	}

	@Override
	public void onTick() {
		if (lookingDown.get() && mc.player.getXRot() < pitch.get()) return;
		BlockPos feet = mc.player.blockPosition();
		if (HoleUtil.isHole(feet)) return;
		for (int i = 1; i <= depth.getInt(); i++) {
			BlockPos below = feet.below(i);
			if (!mc.level.getBlockState(below).getCollisionShape(mc.level, below).isEmpty()) return;
			if (!HoleUtil.isHole(below)) continue;

			Vec3 center = Vec3.atBottomCenterOf(below);
			Vec3 velocity = mc.player.getDeltaMovement();
			double y = pull.get() ? Math.min(velocity.y, -0.4) : velocity.y;
			mc.player.setDeltaMovement((center.x - mc.player.getX()) * 0.5, y, (center.z - mc.player.getZ()) * 0.5);
			return;
		}
	}
}
