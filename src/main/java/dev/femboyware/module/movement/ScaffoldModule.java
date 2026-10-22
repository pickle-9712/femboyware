package dev.femboyware.module.movement;

import dev.femboyware.FemboyWare;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.BlockUtil;
import dev.femboyware.util.InventoryUtil.SwitchMode;
import dev.femboyware.util.PlaceUtil;
import dev.femboyware.util.RenderUtil;
import dev.femboyware.util.Rotation;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.FallingBlock;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

/**
 * Places blocks under your feet as you walk. Rotations go through the rotation manager, so the
 * placement happens with the rotation the server actually saw.
 */
public class ScaffoldModule extends Module {
	private static final int ROTATION_PRIORITY = 80;

	private final BoolSetting rotate = add(new BoolSetting("Rotate", "Face each placement (needed on most servers)", true));
	private final NumberSetting rotationSpeed = add(new NumberSetting("Rotation Speed", "Max degrees turned per tick", 180, 10, 180, 1)).visibleWhen(rotate::get);
	private final BoolSetting tower = add(new BoolSetting("Tower", "Climb straight up while holding jump", true));
	private final BoolSetting strict = add(new BoolSetting("Strict", "Only click faces you can see", false));
	private final EnumSetting<SwitchMode> switchMode = add(new EnumSetting<>("Switch", "How to switch to blocks", SwitchMode.SILENT));
	private final BoolSetting render = add(new BoolSetting("Render", "Highlight the next placement", true));
	private final ColorSetting color = add(new ColorSetting("Color", "Highlight colour", 0x40FF77C8)).visibleWhen(render::get);

	private BlockPos target;
	private BlockHitResult hit;

	public ScaffoldModule() {
		super("Scaffold", "Bridges under you automatically", Category.BUILD);
	}

	@Override
	protected void onDisable() {
		target = null;
		hit = null;
	}

	@Override
	public void onPreMotion() {
		target = BlockPos.ofFloored(mc.player.getX(), mc.player.getY() - 0.5, mc.player.getZ());
		hit = null;
		if (!mc.world.getBlockState(target).isReplaceable()) return;
		hit = BlockUtil.getPlacement(target, strict.get());
		if (hit == null) {
			// Nothing to click: place on a neighbour first so the next tick has support
			for (Direction direction : Direction.Type.HORIZONTAL) {
				BlockPos side = target.offset(direction);
				if (!mc.world.getBlockState(side).isReplaceable()) continue;
				BlockHitResult sideHit = BlockUtil.getPlacement(side, strict.get());
				if (sideHit != null) {
					target = side;
					hit = sideHit;
					break;
				}
			}
		}
		if (hit != null && rotate.get()) {
			FemboyWare.getRotations().request(Rotation.between(mc.player.getEyePos(), hit.getPos()), ROTATION_PRIORITY, rotationSpeed.getFloat(), false);
		}
	}

	@Override
	public void onPostMotion() {
		if (hit == null) return;
		if (rotate.get() && !FemboyWare.getRotations().isFacing(Rotation.between(mc.player.getEyePos(), hit.getPos()), 20)) return;
		if (!PlaceUtil.interact(hit, ScaffoldModule::isScaffoldBlock, switchMode.get(), false)) return;

		if (tower.get() && mc.options.jumpKey.isPressed() && mc.player.input.getMovementInput().lengthSquared() == 0) {
			mc.player.setVelocity(0, 0.42, 0);
		}
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (render.get() && target != null && hit != null) RenderUtil.drawBox(event, new Box(target), color.get(), color.withAlpha(255), 1.5f);
	}

	private static boolean isScaffoldBlock(ItemStack stack) {
		if (!(stack.getItem() instanceof BlockItem item)) return false;
		Block block = item.getBlock();
		if (block instanceof FallingBlock || block instanceof BlockEntityProvider) return false;
		return block.getDefaultState().isFullCube(mc.world, BlockPos.ORIGIN);
	}
}
