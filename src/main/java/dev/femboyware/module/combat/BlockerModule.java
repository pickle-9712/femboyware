package dev.femboyware.module.combat;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.DamageCalculator;
import dev.femboyware.util.HoleUtil;
import dev.femboyware.util.InventoryUtil.SwitchMode;
import dev.femboyware.util.PlaceUtil;
import dev.femboyware.util.RenderUtil;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Fills the spots where an enemy could place a crystal that would hurt you badly, so there is
 * nowhere left to crystal you from.
 */
public class BlockerModule extends Module {
	private final NumberSetting minDamage = add(new NumberSetting("Min Damage", "Block crystal spots that would deal at least this much to you", 6, 1, 20, 0.5));
	private final NumberSetting radius = add(new NumberSetting("Radius", "How far around you to check", 3, 1, 5, 1));
	private final BoolSetting holeOnly = add(new BoolSetting("Hole Only", "Only run while you're standing in a hole", true));
	private final NumberSetting blocksPerTick = add(new NumberSetting("Blocks Per Tick", "Max placements per tick", 2, 1, 8, 1));
	private final BoolSetting rotate = add(new BoolSetting("Rotate", "Send a rotation packet before placing", true));
	private final BoolSetting strict = add(new BoolSetting("Strict", "Only click faces you can see", false));
	private final EnumSetting<SwitchMode> switchMode = add(new EnumSetting<>("Switch", "How to switch to obsidian", SwitchMode.SILENT));
	private final BoolSetting render = add(new BoolSetting("Render", "Highlight blocked spots", true));
	private final ColorSetting color = add(new ColorSetting("Color", "Highlight colour", 0x40FFAA55)).visibleWhen(render::get);

	private List<BlockPos> spots = List.of();

	public BlockerModule() {
		super("Blocker", "Blocks crystal spots that could hurt you", Category.PROTECT);
	}

	@Override
	public void onTick() {
		BlockPos feet = mc.player.getBlockPos();
		if (holeOnly.get() && !HoleUtil.isHole(feet)) {
			spots = List.of();
			return;
		}
		int r = radius.getInt();
		Box self = mc.player.getBoundingBox();
		List<BlockPos> found = new ArrayList<>();
		for (BlockPos base : BlockPos.iterate(feet.add(-r, -1, -r), feet.add(r, 1, r))) {
			BlockPos crystal = base.up();
			if (!HoleUtil.isBlastProof(mc.world.getBlockState(base)) || !mc.world.isAir(crystal)) continue;
			if (self.intersects(new Box(crystal))) continue;
			if (DamageCalculator.crystalDamage(mc.player, base.toImmutable(), false, false) >= minDamage.get()) {
				found.add(crystal.toImmutable());
			}
		}
		found.sort(Comparator.comparingDouble(pos -> mc.player.squaredDistanceTo(pos.toCenterPos())));
		spots = found;

		int placed = 0;
		for (BlockPos spot : found) {
			if (placed >= blocksPerTick.getInt()) break;
			if (PlaceUtil.place(spot, PlaceUtil.OBSIDIAN, switchMode.get(), rotate.get(), strict.get())) placed++;
		}
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (!render.get()) return;
		for (BlockPos spot : spots) {
			RenderUtil.drawBox(event, new Box(spot), color.get(), color.withAlpha(255), 1.5f);
		}
	}
}
