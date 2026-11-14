package dev.femboyware.module.render;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.RenderUtil;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Light levels on the ground round you: red where mobs can spawn now (no block light, and dark
 * sky), yellow where they can at night, green where it's lit. Optionally the number on each block.
 */
public class LightDebugModule extends Module {
	private final NumberSetting range = add(new NumberSetting("Range", "Blocks around you to show", 10, 3, 24, 1));
	private final BoolSetting numbers = add(new BoolSetting("Numbers", "Write the block light level on each block", true));
	private final NumberSetting numberRange = add(new NumberSetting("Number Range", "Only write numbers this close", 6, 2, 16, 1)).visibleWhen(numbers::get);
	private final BoolSetting onlySpawnable = add(new BoolSetting("Only Spawnable", "Hide blocks that are lit", false));

	private record Spot(BlockPos pos, int block, int sky) {}

	private final List<Spot> spots = new ArrayList<>();
	private int refresh;

	public LightDebugModule() {
		super("Light Debug", "Shows light levels and where mobs can spawn", Category.ESP);
	}

	@Override
	protected void onDisable() {
		spots.clear();
	}

	@Override
	public void onTick() {
		if (refresh-- > 0) return;
		refresh = 5;
		spots.clear();
		int r = range.getInt();
		BlockPos feet = mc.player.blockPosition();
		for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-r, -4, -r), feet.offset(r, 3, r))) {
			BlockState here = mc.level.getBlockState(pos);
			if (!here.getCollisionShape(mc.level, pos).isEmpty() || !here.getFluidState().isEmpty()) continue;
			BlockPos below = pos.below();
			if (!mc.level.getBlockState(below).isFaceSturdy(mc.level, below, Direction.UP)) continue;
			int block = mc.level.getBrightness(LightLayer.BLOCK, pos);
			int sky = mc.level.getBrightness(LightLayer.SKY, pos);
			if (onlySpawnable.get() && block > 0) continue;
			spots.add(new Spot(pos.immutable(), block, sky));
		}
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		Vec3 eyes = mc.player.getEyePosition();
		double numberSq = numberRange.get() * numberRange.get();
		boolean night = mc.level.getSkyDarken() >= 4;
		for (Spot spot : spots) {
			int color;
			if (spot.block > 0) color = 0x6033DD55;
			else if (spot.sky == 0 || night) color = 0x70FF3344;
			else color = 0x60FFCC33;
			BlockPos p = spot.pos;
			AABB box = new AABB(p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + 0.02, p.getZ() + 1);
			RenderUtil.drawBox(event, box, color, color | 0xFF000000, 1f, false);
			if (numbers.get() && eyes.distanceToSqr(Vec3.atBottomCenterOf(p)) <= numberSq) {
				RenderUtil.drawText(event, Integer.toString(spot.block), new Vec3(p.getX() + 0.5, p.getY() + 0.25, p.getZ() + 0.5), color | 0xFF000000, 0.4f);
			}
		}
	}
}
