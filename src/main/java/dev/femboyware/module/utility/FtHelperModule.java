package dev.femboyware.module.utility;

import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.util.InventoryUtil;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

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
		boolean mining = mc.options.keyAttack.isDown() && mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK;
		if (!mining) {
			if (previousSlot >= 0 && swapBack.get()) InventoryUtil.select(previousSlot);
			previousSlot = -1;
			return;
		}
		BlockState state = mc.level.getBlockState(((BlockHitResult) mc.hitResult).getBlockPos());
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
		for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
			ItemStack stack = mc.player.getInventory().getItem(slot);
			if (!stack.isCorrectToolForDrops(state)) continue;
			int level = InventoryUtil.getEnchantmentLevel(stack, fortune ? Enchantments.FORTUNE : Enchantments.SILK_TOUCH);
			if (level > bestLevel) {
				bestLevel = level;
				best = slot;
			}
		}
		return best;
	}

	private static boolean isFortuneBlock(BlockState state) {
		return state.is(ConventionalBlockTags.COAL_ORES) || state.is(ConventionalBlockTags.IRON_ORES) || state.is(ConventionalBlockTags.GOLD_ORES)
			|| state.is(ConventionalBlockTags.COPPER_ORES) || state.is(ConventionalBlockTags.REDSTONE_ORES) || state.is(ConventionalBlockTags.LAPIS_ORES)
			|| state.is(ConventionalBlockTags.DIAMOND_ORES) || state.is(ConventionalBlockTags.EMERALD_ORES)
			|| state.is(Blocks.NETHER_QUARTZ_ORE) || state.is(Blocks.AMETHYST_CLUSTER) || state.is(Blocks.GLOWSTONE);
	}

	private static boolean isSilkBlock(BlockState state) {
		return state.is(Blocks.ENDER_CHEST) || state.is(Blocks.GLASS) || state.is(Blocks.BOOKSHELF)
			|| state.is(Blocks.SEA_LANTERN) || state.is(Blocks.BLUE_ICE) || state.is(Blocks.PACKED_ICE) || state.is(Blocks.ICE);
	}
}
