package dev.femboyware.module.utility;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.InventoryUtil;
import dev.femboyware.util.InventoryUtil.SwitchMode;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.attribute.EnvironmentAttributes;

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
			if (mc.player.isOnGround() || mc.player.isTouchingWater()) {
				if (++pickUpTicks >= 2) {
					if (pickUp.get()) use(Items.BUCKET);
					placed = false;
				}
			}
			return;
		}
		if (mc.player.isGliding() || mc.player.getAbilities().flying || mc.player.fallDistance < minFall.get() || mc.player.isTouchingWater()) return;
		if (mc.world.getEnvironmentAttributes().getAttributeValue(EnvironmentAttributes.WATER_EVAPORATES_GAMEPLAY, mc.player.getBlockPos())) return;

		// Place once the ground is within the next tick or two of falling
		double reach = Math.max(2.5, -mc.player.getVelocity().y * 2 + 1);
		Vec3d from = mc.player.getEntityPos();
		HitResult hit = mc.world.raycast(new RaycastContext(from, from.add(0, -reach, 0), RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.ANY, mc.player));
		if (hit.getType() != HitResult.Type.BLOCK) return;
		if (use(Items.WATER_BUCKET)) {
			placed = true;
			pickUpTicks = 0;
		}
	}

	/** Uses a bucket item looking straight down; the use packet carries the rotation. */
	private boolean use(Item item) {
		int slot = InventoryUtil.findHotbar(stack -> stack.isOf(item));
		if (slot < 0) return false;
		float pitch = mc.player.getPitch();
		return InventoryUtil.withSlot(slot, SwitchMode.SILENT, () -> {
			mc.player.setPitch(90f);
			mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
			mc.player.swingHand(Hand.MAIN_HAND);
			mc.player.setPitch(pitch);
		});
	}
}
