package dev.femboyware.module.combat;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.util.InventoryUtil;
import dev.femboyware.util.TargetUtil;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Hits on a raised shield go out with your best axe, which disables the shield, and your slot
 * comes straight back afterwards.
 */
public class ShieldBreakerModule extends Module {
	private final BoolSetting swapBack = add(new BoolSetting("Swap Back", "Switch back to your weapon after the hit", true));

	private int previousSlot = -1;

	public ShieldBreakerModule() {
		super("Shield Breaker", "Axe hit swap: hits on a raised shield go out with your best axe, then your slot returns", Category.ATTACK);
	}

	@Override
	public void onPreAttack(Entity target) {
		previousSlot = -1;
		if (!(target instanceof Player player) || !player.isBlocking() || !TargetUtil.isFacing(player, mc.player.getEyePosition())) return;
		if (mc.player.getMainHandItem().is(ItemTags.AXES)) return;
		int slot = bestAxe();
		if (slot < 0) return;
		previousSlot = mc.player.getInventory().getSelectedSlot();
		InventoryUtil.select(slot);
	}

	@Override
	public void onPostAttack(Entity target) {
		if (previousSlot >= 0 && swapBack.get()) InventoryUtil.select(previousSlot);
		previousSlot = -1;
	}

	private int bestAxe() {
		int best = -1;
		float bestSpeed = -1;
		for (int i = 0; i < 9; i++) {
			ItemStack stack = mc.player.getInventory().getItem(i);
			if (!stack.is(ItemTags.AXES)) continue;
			// Any axe disables a shield; the better one also hits harder
			float speed = stack.getDestroySpeed(net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState());
			if (speed > bestSpeed) {
				bestSpeed = speed;
				best = i;
			}
		}
		return best;
	}
}
