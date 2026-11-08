package dev.femboyware.module.utility;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.RenderUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ArmorStandItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.FireworkRocketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

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
		if (!placeable(mc.player.getMainHandItem()) && !placeable(mc.player.getOffhandItem())) return;
		if (mc.hitResult != null && mc.hitResult.getType() != HitResult.Type.MISS) return;
		double reach = customRange.get() ? range.get() : mc.player.blockInteractionRange();
		HitResult hit = mc.getCameraEntity().pick(reach, 0, false);
		if (hit instanceof BlockHitResult blockHit) target = blockHit.getBlockPos();
	}

	/** Called from the interaction manager mixin. Returns true when the right click became an air place. */
	public boolean onUseItem(InteractionHand hand) {
		if (!isEnabled() || target == null) return false;
		ItemStack stack = mc.player.getItemInHand(hand);
		if (!placeable(stack) || !canPlace(target, stack.getItem())) return false;
		BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(target), mc.player.getMotionDirection().getOpposite(), target, false);
		mc.gameMode.useItemOn(mc.player, hand, hit);
		mc.player.swing(hand);
		return true;
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (!render.get() || target == null || !mc.level.getBlockState(target).canBeReplaced()) return;
		RenderUtil.drawBox(event, new AABB(target), fillColor.get(), lineColor.get(), 1.5f);
	}

	private boolean canPlace(BlockPos pos, Item item) {
		BlockState current = mc.level.getBlockState(pos);
		if (!current.canBeReplaced()) return false;
		// Blocks can't go where an entity stands; spawn eggs and the like don't care
		if (!(item instanceof BlockItem blockItem)) return true;
		return mc.level.isUnobstructed(blockItem.getBlock().defaultBlockState(), pos, CollisionContext.empty());
	}

	private boolean placeable(ItemStack stack) {
		Item item = stack.getItem();
		return item instanceof BlockItem || item instanceof SpawnEggItem || item instanceof ArmorStandItem
			|| (item instanceof FireworkRocketItem && !mc.player.isFallFlying());
	}
}
