package dev.femboyware.module.utility;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.InventoryUtil;
import net.minecraft.block.BambooBlock;
import net.minecraft.block.BambooShootBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.block.ExperienceDroppingBlock;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.RedstoneOreBlock;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ShearsItem;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Switches to the best tool for the block you start mining.
 * Ported from Meteor Client's AutoTool (https://github.com/MeteorDevelopment/meteor-client), GPL-3.0.
 */
public class AutoToolModule extends Module {
	public enum EnchantPreference { NONE, FORTUNE, SILK_TOUCH }

	private final EnumSetting<EnchantPreference> prefer = add(new EnumSetting<>("Prefer", "Enchantment to favour between otherwise equal tools", EnchantPreference.FORTUNE));
	private final BoolSetting silkTouchEnderChest = add(new BoolSetting("Silk Ender Chests", "Only mine ender chests with Silk Touch", true));
	private final BoolSetting fortuneOres = add(new BoolSetting("Fortune Ores", "Only mine ores and crops with Fortune", false));
	private final BoolSetting antiBreak = add(new BoolSetting("Anti Break", "Stop using tools that are about to break", false));
	private final NumberSetting breakDurability = add(new NumberSetting("Break Durability", "Durability % at which a tool counts as about to break", 10, 1, 100, 1)).visibleWhen(antiBreak::get);
	private final BoolSetting switchBack = add(new BoolSetting("Switch Back", "Return to your previous slot when you let go of attack", false));
	private final NumberSetting switchDelay = add(new NumberSetting("Switch Delay", "Ticks to wait before switching", 0, 0, 20, 1));

	private int pendingSlot = -1;
	private int ticks;
	private int returnSlot = -1;

	public AutoToolModule() {
		super("Auto Tool", "Switches to the best tool for what you mine", Category.EQUIP);
	}

	@Override
	protected void onDisable() {
		pendingSlot = -1;
		returnSlot = -1;
	}

	@Override
	public void onTick() {
		if (returnSlot != -1 && !mc.options.attackKey.isPressed()) {
			InventoryUtil.select(returnSlot);
			returnSlot = -1;
			return;
		}
		if (pendingSlot != -1 && --ticks <= 0) {
			switchTo(pendingSlot);
			pendingSlot = -1;
		}
	}

	@Override
	public boolean onStartBreaking(BlockPos pos, Direction side) {
		if (mc.player.isCreative()) return false;
		BlockState state = mc.world.getBlockState(pos);
		if (state.isAir() || state.getHardness(mc.world, pos) < 0) return false;

		ItemStack current = mc.player.getMainHandStack();
		int best = bestSlot(state);
		double bestScore = best == -1 ? -1 : score(mc.player.getInventory().getStack(best), state);
		if (best != -1 && best != mc.player.getInventory().getSelectedSlot()
			&& (bestScore > score(current, state) || shouldStopUsing(current) || !isTool(current))) {
			ticks = switchDelay.getInt();
			if (ticks == 0) switchTo(best);
			else pendingSlot = best;
		}

		// Anti break: refuse to keep mining with a nearly broken tool
		current = mc.player.getMainHandStack();
		if (shouldStopUsing(current) && isTool(current)) {
			mc.options.attackKey.setPressed(false);
			return true;
		}
		return false;
	}

	private void switchTo(int slot) {
		if (switchBack.get() && returnSlot == -1) returnSlot = mc.player.getInventory().getSelectedSlot();
		InventoryUtil.select(slot);
	}

	/** Best hotbar tool for {@code state}, or -1 if nothing is suitable. */
	public int bestSlot(BlockState state) {
		int best = -1;
		double bestScore = -1;
		for (int i = 0; i < PlayerInventory.HOTBAR_SIZE; i++) {
			double score = score(mc.player.getInventory().getStack(i), state);
			if (score > bestScore) {
				bestScore = score;
				best = i;
			}
		}
		return best;
	}

	private double score(ItemStack stack, BlockState state) {
		if (shouldStopUsing(stack) || !isTool(stack)) return -1;
		boolean bamboo = state.getBlock() instanceof BambooBlock || state.getBlock() instanceof BambooShootBlock;
		boolean swordOnBamboo = stack.isIn(ItemTags.SWORDS) && bamboo;
		boolean shears = stack.getItem() instanceof ShearsItem && (state.getBlock() instanceof LeavesBlock || state.isIn(BlockTags.WOOL));
		if (!stack.isSuitableFor(state) && !swordOnBamboo && !shears) return -1;
		if (silkTouchEnderChest.get() && state.isOf(Blocks.ENDER_CHEST) && InventoryUtil.getEnchantmentLevel(stack, Enchantments.SILK_TOUCH) == 0) return -1;
		if (fortuneOres.get() && isFortunable(state.getBlock()) && InventoryUtil.getEnchantmentLevel(stack, Enchantments.FORTUNE) == 0) return -1;

		double score = stack.getMiningSpeedMultiplier(state) * 1000;
		score += InventoryUtil.getEnchantmentLevel(stack, Enchantments.UNBREAKING);
		score += InventoryUtil.getEnchantmentLevel(stack, Enchantments.EFFICIENCY);
		score += InventoryUtil.getEnchantmentLevel(stack, Enchantments.MENDING);
		if (prefer.is(EnchantPreference.FORTUNE)) score += InventoryUtil.getEnchantmentLevel(stack, Enchantments.FORTUNE);
		if (prefer.is(EnchantPreference.SILK_TOUCH)) score += InventoryUtil.getEnchantmentLevel(stack, Enchantments.SILK_TOUCH);
		if (swordOnBamboo) score += 9000;
		return score;
	}

	private boolean shouldStopUsing(ItemStack stack) {
		return antiBreak.get() && stack.isDamageable()
			&& stack.getMaxDamage() - stack.getDamage() < stack.getMaxDamage() * breakDurability.get() / 100;
	}

	private static boolean isTool(ItemStack stack) {
		return stack.isIn(ItemTags.AXES) || stack.isIn(ItemTags.HOES) || stack.isIn(ItemTags.PICKAXES)
			|| stack.isIn(ItemTags.SHOVELS) || stack.getItem() instanceof ShearsItem;
	}

	private static boolean isFortunable(Block block) {
		return block instanceof ExperienceDroppingBlock || block instanceof RedstoneOreBlock || block instanceof CropBlock;
	}
}
