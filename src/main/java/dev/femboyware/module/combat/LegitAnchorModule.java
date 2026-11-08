package dev.femboyware.module.combat;

import dev.femboyware.util.Mc;
import dev.femboyware.mixin.MinecraftClientAccessor;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.KeySetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.InventoryUtil;
import org.lwjgl.glfw.GLFW;

import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Press the anchor key and it does a whole anchor bomb with real hotbar switches and right
 * clicks: switch to the anchor and place it where you're looking, switch to glowstone and charge
 * it once, then switch to a sword and right click to blow it up.
 */
public class LegitAnchorModule extends Module {
	private enum Step { SELECT_ANCHOR, PLACE, SELECT_GLOWSTONE, CHARGE, SELECT_EXPLODER, EXPLODE }

	/** Give up on a step if the crosshair isn't on the anchor within this many ticks. */
	private static final int STEP_TIMEOUT = 10;

	private final KeySetting key = add(new KeySetting("Anchor Key", "Press this to place, charge and blow up an anchor", GLFW.GLFW_KEY_6));
	private final NumberSetting delay = add(new NumberSetting("Delay", "Ticks between each switch and click", 1, 0, 5, 1));

	private final Random random = new Random();
	private Step step;
	private int wait;
	private int stepTicks;
	private BlockPos anchor;

	public LegitAnchorModule() {
		super("Legit Anchor", "One key to place, charge and blow up a respawn anchor", Category.LEGIT);
	}

	@Override
	public String getInfo() {
		return step == null ? null : "busy";
	}

	@Override
	protected void onDisable() {
		step = null;
	}

	@Override
	public void onKey(int pressed) {
		if (!key.is(pressed) || step != null) return;
		if (slot(stack -> stack.is(Items.RESPAWN_ANCHOR)) < 0 || slot(stack -> stack.is(Items.GLOWSTONE)) < 0) return;
		step = Step.SELECT_ANCHOR;
		// The key may be a hotbar key too; let its own slot switch happen first, then take over
		wait = 1;
		stepTicks = 0;
		anchor = null;
	}

	@Override
	public void onTick() {
		if (step == null) return;
		if (wait > 0) {
			wait--;
			return;
		}
		if (Mc.screen() != null || ++stepTicks > STEP_TIMEOUT) {
			step = null;
			return;
		}
		if (run(step)) {
			step = step == Step.EXPLODE ? null : Step.values()[step.ordinal() + 1];
			stepTicks = 0;
			// A little uneven, like a person's fingers
			wait = delay.getInt() + (random.nextInt(3) == 0 ? 1 : 0);
		}
	}

	/** Does one step; false to try it again next tick. */
	private boolean run(Step current) {
		MinecraftClientAccessor clicks = (MinecraftClientAccessor) mc;
		switch (current) {
			case SELECT_ANCHOR -> {
				return select(slot(stack -> stack.is(Items.RESPAWN_ANCHOR)));
			}
			case PLACE -> {
				if (!(mc.hitResult instanceof BlockHitResult hit) || mc.level.isEmptyBlock(hit.getBlockPos())) return false;
				// Already looking at an anchor: use that one
				if (mc.level.getBlockState(hit.getBlockPos()).is(Blocks.RESPAWN_ANCHOR)) {
					anchor = hit.getBlockPos();
					return true;
				}
				anchor = hit.getBlockPos().relative(hit.getDirection());
				clicks.femboyware$rightClick();
				return true;
			}
			case SELECT_GLOWSTONE -> {
				return select(slot(stack -> stack.is(Items.GLOWSTONE)));
			}
			case CHARGE -> {
				if (!lookingAtAnchor()) return false;
				if (charges() == 0) clicks.femboyware$rightClick();
				return true;
			}
			case SELECT_EXPLODER -> {
				return select(exploderSlot());
			}
			case EXPLODE -> {
				// Wait for the charge to show before clicking, or the click would do nothing
				if (!lookingAtAnchor() || charges() == 0) return false;
				clicks.femboyware$rightClick();
				return true;
			}
		}
		return false;
	}

	private boolean lookingAtAnchor() {
		return anchor != null && mc.hitResult instanceof BlockHitResult hit && hit.getBlockPos().equals(anchor)
			&& mc.level.getBlockState(anchor).is(Blocks.RESPAWN_ANCHOR);
	}

	private int charges() {
		BlockState state = mc.level.getBlockState(anchor);
		return state.is(Blocks.RESPAWN_ANCHOR) ? state.getValue(RespawnAnchorBlock.CHARGE) : 0;
	}

	/** A sword if there's one in the hotbar, else anything that won't charge the anchor more or place another. */
	private int exploderSlot() {
		int sword = slot(stack -> stack.is(ItemTags.SWORDS));
		if (sword >= 0) return sword;
		return slot(stack -> !stack.is(Items.GLOWSTONE) && !stack.is(Items.RESPAWN_ANCHOR));
	}

	private boolean select(int slot) {
		if (slot < 0) {
			step = null;
			return false;
		}
		if (mc.player.getInventory().getSelectedSlot() != slot) InventoryUtil.select(slot);
		return true;
	}

	private int slot(java.util.function.Predicate<ItemStack> predicate) {
		return InventoryUtil.findHotbar(predicate);
	}
}
