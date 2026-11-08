package dev.femboyware.module.combat;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.InventoryUtil.SwitchMode;
import dev.femboyware.util.PlaceUtil;
import dev.femboyware.util.RenderUtil;
import dev.femboyware.util.TargetUtil;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

/** Boxes the target in with obsidian: a ring at the feet, a ring at the head and a roof. */
public class TrapModule extends Module {
	private final NumberSetting targetRange = add(new NumberSetting("Target Range", "How far away targets can be", 6, 2, 10, 0.5));
	private final BoolSetting feet = add(new BoolSetting("Feet", "Place the ring around the target's feet", true));
	private final BoolSetting roof = add(new BoolSetting("Roof", "Place a block above the target's head", true));
	private final NumberSetting blocksPerTick = add(new NumberSetting("Blocks Per Tick", "Max placements per tick", 4, 1, 12, 1));
	private final BoolSetting rotate = add(new BoolSetting("Rotate", "Send a rotation packet before placing", true));
	private final BoolSetting strict = add(new BoolSetting("Strict", "Only click faces you can see", false));
	private final EnumSetting<SwitchMode> switchMode = add(new EnumSetting<>("Switch", "How to switch to obsidian", SwitchMode.SILENT));
	private final BoolSetting render = add(new BoolSetting("Render", "Highlight trap positions", true));
	private final ColorSetting color = add(new ColorSetting("Color", "Highlight colour", 0x40FF5577)).visibleWhen(render::get);

	private Set<BlockPos> positions = Set.of();

	public TrapModule() {
		super("Trap", "Traps your target in obsidian", Category.ATTACK);
	}

	@Override
	public void onTick() {
		Player target = TargetUtil.closestPlayer(targetRange.get());
		if (target == null) {
			positions = Set.of();
			return;
		}
		positions = trapPositions(target, feet.get(), roof.get());
		int placed = 0;
		for (BlockPos pos : positions) {
			if (placed >= blocksPerTick.getInt()) break;
			if (PlaceUtil.place(pos, PlaceUtil.OBSIDIAN, switchMode.get(), rotate.get(), strict.get())) placed++;
		}
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (!render.get()) return;
		for (BlockPos pos : positions) {
			if (!mc.level.getBlockState(pos).canBeReplaced()) continue;
			RenderUtil.drawBox(event, new AABB(pos), color.get(), color.withAlpha(255), 1.5f);
		}
	}

	/** Feet ring, head ring and roof around every block column {@code player} stands in, bottom up. */
	public static Set<BlockPos> trapPositions(Player player, boolean includeFeet, boolean includeRoof) {
		AABB box = player.getBoundingBox();
		int y = Mth.floor(player.getY() + 0.2);
		Set<BlockPos> columns = new LinkedHashSet<>();
		for (int x = Mth.floor(box.minX); x <= Mth.floor(box.maxX - 1e-4); x++) {
			for (int z = Mth.floor(box.minZ); z <= Mth.floor(box.maxZ - 1e-4); z++) {
				columns.add(new BlockPos(x, y, z));
			}
		}
		Set<BlockPos> result = new LinkedHashSet<>();
		for (int level = includeFeet ? 0 : 1; level <= 1; level++) {
			for (BlockPos column : columns) {
				for (Direction direction : Direction.Plane.HORIZONTAL) {
					BlockPos side = column.above(level).relative(direction);
					if (!columns.contains(side.below(level))) result.add(side);
				}
			}
		}
		if (includeRoof) {
			for (BlockPos column : columns) result.add(column.above(2));
		}
		return result;
	}
}
