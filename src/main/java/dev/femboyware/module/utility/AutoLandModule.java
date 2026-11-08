package dev.femboyware.module.utility;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.InventoryUtil;
import dev.femboyware.util.InventoryUtil.SwitchMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Water bucket clutch: places water just before you hit the ground, then picks it back up. */
public class AutoLandModule extends Module {
	private final NumberSetting minFall = add(new NumberSetting("Min Fall", "Only clutch falls longer than this", 4, 3, 50, 0.5));
	private final BoolSetting pickUp = add(new BoolSetting("Pick Up", "Take the water back after landing", true));

	private boolean placed;
	private int pickUpTicks;

	public AutoLandModule() {
		super("Auto Land", "Water bucket clutches your falls", Category.WORLD);
	}

	@Override
	public void onTick() {
		if (placed) {
			if (mc.player.onGround() || mc.player.isInWater()) {
				if (++pickUpTicks >= 2) {
					if (pickUp.get()) use(Items.BUCKET);
					placed = false;
				}
			}
			return;
		}
		if (mc.player.isFallFlying() || mc.player.getAbilities().flying || mc.player.fallDistance < minFall.get() || mc.player.isInWater()) return;
		if (mc.level.environmentAttributes().getValue(EnvironmentAttributes.WATER_EVAPORATES, mc.player.blockPosition())) return;

		// Place once the ground is within the next tick or two of falling
		double reach = Math.max(2.5, -mc.player.getDeltaMovement().y * 2 + 1);
		Vec3 from = mc.player.position();
		HitResult hit = mc.level.clip(new ClipContext(from, from.add(0, -reach, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, mc.player));
		if (hit.getType() != HitResult.Type.BLOCK) return;
		if (use(Items.WATER_BUCKET)) {
			placed = true;
			pickUpTicks = 0;
		}
	}

	/** Uses a bucket item looking straight down; the use packet carries the rotation. */
	private boolean use(Item item) {
		int slot = InventoryUtil.findHotbar(stack -> stack.is(item));
		if (slot < 0) return false;
		float pitch = mc.player.getXRot();
		return InventoryUtil.withSlot(slot, SwitchMode.SILENT, () -> {
			mc.player.setXRot(90f);
			mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
			mc.player.swing(InteractionHand.MAIN_HAND);
			mc.player.setXRot(pitch);
		});
	}
}
