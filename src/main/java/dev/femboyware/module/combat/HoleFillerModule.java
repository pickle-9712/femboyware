package dev.femboyware.module.combat;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.HoleUtil;
import dev.femboyware.util.InventoryUtil.SwitchMode;
import dev.femboyware.util.PlaceUtil;
import dev.femboyware.util.RenderUtil;
import dev.femboyware.util.TargetUtil;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/** Fills safe holes near the target so they have nowhere to hide from crystals. */
public class HoleFillerModule extends Module {
	public enum Fill { OBSIDIAN, WEB }

	private final NumberSetting targetRange = add(new NumberSetting("Target Range", "How far away targets can be", 10, 2, 16, 0.5));
	private final NumberSetting holeRange = add(new NumberSetting("Hole Range", "Fill holes this close to the target", 2.5, 1, 6, 0.5));
	private final EnumSetting<Fill> fill = add(new EnumSetting<>("Fill", "What to fill holes with", Fill.OBSIDIAN));
	private final NumberSetting blocksPerTick = add(new NumberSetting("Blocks Per Tick", "Max placements per tick", 2, 1, 8, 1));
	private final BoolSetting rotate = add(new BoolSetting("Rotate", "Send a rotation packet before placing", true));
	private final BoolSetting strict = add(new BoolSetting("Strict", "Only click faces you can see", false));
	private final EnumSetting<SwitchMode> switchMode = add(new EnumSetting<>("Switch", "How to switch to the fill block", SwitchMode.SILENT));
	private final BoolSetting render = add(new BoolSetting("Render", "Highlight holes about to be filled", true));
	private final ColorSetting color = add(new ColorSetting("Color", "Hole highlight colour", 0x40FF5577)).visibleWhen(render::get);

	private List<BlockPos> holes = List.of();

	public HoleFillerModule() {
		super("Hole Filler", "Fills holes around your target", Category.BUILD);
	}

	@Override
	public void onTick() {
		PlayerEntity target = TargetUtil.closestPlayer(targetRange.get());
		if (target == null) {
			holes = List.of();
			return;
		}
		BlockPos center = target.getBlockPos();
		BlockPos own = mc.player.getBlockPos();
		int radius = (int) Math.ceil(holeRange.get());
		List<BlockPos> found = new ArrayList<>();
		for (BlockPos pos : BlockPos.iterate(center.add(-radius, -2, -radius), center.add(radius, 1, radius))) {
			if (pos.equals(own) || pos.equals(center)) continue;
			if (target.squaredDistanceTo(pos.toCenterPos()) > holeRange.get() * holeRange.get()) continue;
			if (HoleUtil.isHole(pos)) found.add(pos.toImmutable());
		}
		found.sort(Comparator.comparingDouble(pos -> target.squaredDistanceTo(pos.toCenterPos())));
		holes = found;

		Predicate<ItemStack> item = fill.is(Fill.WEB) ? stack -> stack.isOf(Items.COBWEB) : PlaceUtil.OBSIDIAN;
		int placed = 0;
		for (BlockPos hole : found) {
			if (placed >= blocksPerTick.getInt()) break;
			if (PlaceUtil.place(hole, item, switchMode.get(), rotate.get(), strict.get())) placed++;
		}
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (!render.get()) return;
		for (BlockPos hole : holes) {
			RenderUtil.drawBox(event, new Box(hole).withMaxY(hole.getY() + 0.1), color.get(), color.withAlpha(255), 1.5f);
		}
	}
}
