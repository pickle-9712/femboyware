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
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

/** Covers your head ring and roof with obsidian, so you can't be face placed or jumped out of a hole. */
public class SelfTrapModule extends Module {
	private final BoolSetting feet = add(new BoolSetting("Feet", "Also place the feet ring (leave off if Surround runs)", false));
	private final BoolSetting roof = add(new BoolSetting("Roof", "Place a block above your head", true));
	private final NumberSetting blocksPerTick = add(new NumberSetting("Blocks Per Tick", "Max placements per tick", 4, 1, 12, 1));
	private final BoolSetting rotate = add(new BoolSetting("Rotate", "Send a rotation packet before placing", true));
	private final BoolSetting strict = add(new BoolSetting("Strict", "Only click faces you can see", false));
	private final EnumSetting<SwitchMode> switchMode = add(new EnumSetting<>("Switch", "How to switch to obsidian", SwitchMode.SILENT));
	private final BoolSetting disableOnMove = add(new BoolSetting("Disable On Y Change", "Turn off when you jump or fall", true));
	private final BoolSetting render = add(new BoolSetting("Render", "Highlight missing positions", true));
	private final ColorSetting color = add(new ColorSetting("Color", "Highlight colour", 0x4055FF99)).visibleWhen(render::get);

	private Set<BlockPos> positions = Set.of();
	private double startY;

	public SelfTrapModule() {
		super("Self Trap", "Traps yourself in obsidian", Category.PROTECT);
	}

	@Override
	protected void onEnable() {
		if (!nullCheck()) startY = mc.player.getY();
	}

	@Override
	public void onTick() {
		if (disableOnMove.get() && Math.abs(mc.player.getY() - startY) > 0.5) {
			setEnabled(false);
			return;
		}
		positions = TrapModule.trapPositions(mc.player, feet.get(), roof.get());
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
}
