package dev.femboyware.module.utility;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.RenderUtil;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.item.ArmorStandItem;
import net.minecraft.item.BlockItem;
import net.minecraft.item.FireworkRocketItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * Places blocks in mid-air wherever your crosshair points, with nothing to place against.
 * Ported from Meteor Client's AirPlace (https://github.com/MeteorDevelopment/meteor-client), GPL-3.0.
 */
public class AirPlaceModule extends Module {
	private final BoolSetting render = add(new BoolSetting("Render", "Show where the block will go", true));
	private final ColorSetting fillColor = add(new ColorSetting("Fill", "Highlight fill colour", 0x1ACC0000)).visibleWhen(render::get);
	private final ColorSetting lineColor = add(new ColorSetting("Line", "Highlight outline colour", 0xFFCC0000)).visibleWhen(render::get);
	private final BoolSetting customRange = add(new BoolSetting("Custom Range", "Use your own range instead of your reach", false));
	private final NumberSetting range = add(new NumberSetting("Range", "How far out to place", 5, 0, 6, 0.1)).visibleWhen(customRange::get);

	/** Where an air place would go this tick, or null when the crosshair is on something. */
	private BlockPos target;

	public AirPlaceModule() {
		super("Air Place", "Places blocks in mid-air where you look", Category.PLAYER);
	}

	@Override
	protected void onDisable() {
		target = null;
	}

	@Override
	public void onTick() {
		target = null;
		if (!placeable(mc.player.getMainHandStack()) && !placeable(mc.player.getOffHandStack())) return;
		if (mc.crosshairTarget != null && mc.crosshairTarget.getType() != HitResult.Type.MISS) return;
		double reach = customRange.get() ? range.get() : mc.player.getBlockInteractionRange();
		HitResult hit = mc.getCameraEntity().raycast(reach, 0, false);
		if (hit instanceof BlockHitResult blockHit) target = blockHit.getBlockPos();
	}

	/** Called from the interaction manager mixin. Returns true when the right click became an air place. */
	public boolean onUseItem(Hand hand) {
		if (!isEnabled() || target == null) return false;
		ItemStack stack = mc.player.getStackInHand(hand);
		if (!placeable(stack) || !canPlace(target, stack.getItem())) return false;
		BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(target), mc.player.getMovementDirection().getOpposite(), target, false);
		mc.interactionManager.interactBlock(mc.player, hand, hit);
		mc.player.swingHand(hand);
		return true;
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (!render.get() || target == null || !mc.world.getBlockState(target).isReplaceable()) return;
		RenderUtil.drawBox(event, new Box(target), fillColor.get(), lineColor.get(), 1.5f);
	}

	private boolean canPlace(BlockPos pos, Item item) {
		BlockState current = mc.world.getBlockState(pos);
		if (!current.isReplaceable()) return false;
		// Blocks can't go where an entity stands; spawn eggs and the like don't care
		if (!(item instanceof BlockItem blockItem)) return true;
		return mc.world.canPlace(blockItem.getBlock().getDefaultState(), pos, ShapeContext.absent());
	}

	private boolean placeable(ItemStack stack) {
		Item item = stack.getItem();
		return item instanceof BlockItem || item instanceof SpawnEggItem || item instanceof ArmorStandItem
			|| (item instanceof FireworkRocketItem && !mc.player.isGliding());
	}
}
