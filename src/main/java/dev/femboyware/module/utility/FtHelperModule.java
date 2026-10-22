package dev.femboyware.module.utility;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.util.InventoryUtil;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;

/**
 * Fortune helper: swaps to your best Fortune tool when you start mining an ore, and to Silk Touch
 * for blocks that are only worth having whole.
 */
public class FtHelperModule extends Module {
	private final BoolSetting silkTouch = add(new BoolSetting("Silk Touch", "Use Silk Touch on ender chests, glass and similar blocks", true));
	private final BoolSetting swapBack = add(new BoolSetting("Swap Back", "Go back to your previous slot when you stop mining", true));

	private int previousSlot = -1;

	public FtHelperModule() {
		super("Ft Helper", "Picks Fortune or Silk Touch tools while mining", Category.AUTO);
	}

	@Override
	public void onTick() {
		boolean mining = mc.options.attackKey.isPressed() && mc.crosshairTarget != null && mc.crosshairTarget.getType() == HitResult.Type.BLOCK;
		if (!mining) {
			if (previousSlot >= 0 && swapBack.get()) InventoryUtil.select(previousSlot);
			previousSlot = -1;
			return;
		}
		BlockState state = mc.world.getBlockState(((BlockHitResult) mc.crosshairTarget).getBlockPos());
		int slot = -1;
		if (isFortuneBlock(state)) slot = bestTool(state, true);
		else if (silkTouch.get() && isSilkBlock(state)) slot = bestTool(state, false);
		if (slot < 0 || slot == mc.player.getInventory().getSelectedSlot()) return;
		if (previousSlot < 0) previousSlot = mc.player.getInventory().getSelectedSlot();
		InventoryUtil.select(slot);
	}

	/** Hotbar slot of the tool that mines {@code state} with the highest Fortune level, or any Silk Touch tool. */
	private int bestTool(BlockState state, boolean fortune) {
		int best = -1;
		int bestLevel = 0;
		for (int slot = 0; slot < PlayerInventory.HOTBAR_SIZE; slot++) {
			ItemStack stack = mc.player.getInventory().getStack(slot);
			if (!stack.isSuitableFor(state)) continue;
			int level = InventoryUtil.getEnchantmentLevel(stack, fortune ? Enchantments.FORTUNE : Enchantments.SILK_TOUCH);
			if (level > bestLevel) {
				bestLevel = level;
				best = slot;
			}
		}
		return best;
	}

	private static boolean isFortuneBlock(BlockState state) {
		return state.isIn(BlockTags.COAL_ORES) || state.isIn(BlockTags.IRON_ORES) || state.isIn(BlockTags.GOLD_ORES)
			|| state.isIn(BlockTags.COPPER_ORES) || state.isIn(BlockTags.REDSTONE_ORES) || state.isIn(BlockTags.LAPIS_ORES)
			|| state.isIn(BlockTags.DIAMOND_ORES) || state.isIn(BlockTags.EMERALD_ORES)
			|| state.isOf(Blocks.NETHER_QUARTZ_ORE) || state.isOf(Blocks.AMETHYST_CLUSTER) || state.isOf(Blocks.GLOWSTONE);
	}

	private static boolean isSilkBlock(BlockState state) {
		return state.isOf(Blocks.ENDER_CHEST) || state.isOf(Blocks.GLASS) || state.isOf(Blocks.BOOKSHELF)
			|| state.isOf(Blocks.SEA_LANTERN) || state.isOf(Blocks.BLUE_ICE) || state.isOf(Blocks.PACKED_ICE) || state.isOf(Blocks.ICE);
	}
}
