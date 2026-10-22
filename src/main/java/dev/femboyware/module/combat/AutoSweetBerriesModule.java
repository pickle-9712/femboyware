package dev.femboyware.module.combat;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.InventoryUtil.SwitchMode;
import dev.femboyware.util.PlaceUtil;
import dev.femboyware.util.TargetUtil;
import dev.femboyware.util.Timer;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.function.Predicate;

/** Plants sweet berry bushes in the target's feet, which slow and hurt them while they move. */
public class AutoSweetBerriesModule extends Module {
	private static final Predicate<ItemStack> BERRIES = stack -> stack.isOf(Items.SWEET_BERRIES);

	private final NumberSetting targetRange = add(new NumberSetting("Target Range", "How far away targets can be", 5, 2, 8, 0.5));
	private final NumberSetting delay = add(new NumberSetting("Delay", "Milliseconds between placements", 200, 0, 1000, 10));
	private final BoolSetting rotate = add(new BoolSetting("Rotate", "Send a rotation packet before placing", true));
	private final EnumSetting<SwitchMode> switchMode = add(new EnumSetting<>("Switch", "How to switch to sweet berries", SwitchMode.SILENT));

	private final Timer timer = new Timer();

	public AutoSweetBerriesModule() {
		super("Auto Sweet Berries", "Plants sweet berry bushes on your target", Category.AUTO);
	}

	@Override
	public void onTick() {
		if (!timer.passed(delay.get())) return;
		PlayerEntity target = TargetUtil.closestPlayer(targetRange.get());
		if (target == null) return;

		BlockPos feet = target.getBlockPos();
		if (!mc.world.getBlockState(feet).isReplaceable() || mc.world.getBlockState(feet).isOf(Blocks.SWEET_BERRY_BUSH)) return;
		if (!Blocks.SWEET_BERRY_BUSH.getDefaultState().canPlaceAt(mc.world, feet)) return;

		// Bushes have no collision box, so the target standing there doesn't block the placement
		BlockPos ground = feet.down();
		Vec3d hitVec = Vec3d.ofCenter(ground).add(0, 0.5, 0);
		if (mc.player.getEyePos().squaredDistanceTo(hitVec) > PlaceUtil.RANGE * PlaceUtil.RANGE) return;
		if (PlaceUtil.interact(new BlockHitResult(hitVec, Direction.UP, ground, false), BERRIES, switchMode.get(), rotate.get())) {
			timer.reset();
		}
	}
}
