package dev.femboyware.module.movement;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.InventoryUtil;
import dev.femboyware.util.InventoryUtil.SwitchMode;
import dev.femboyware.util.PlaceUtil;
import dev.femboyware.util.Rotation;
import dev.femboyware.util.TargetUtil;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;

/**
 * Glides to a point high above the target, drops out of the glide right over them and smashes
 * them with a mace on the way down. Mace smashes don't work while gliding, so the glide has to
 * end first.
 */
public class MaceElytraTargetModule extends Module {
	private static final int ROTATION_PRIORITY = 70;

	private final NumberSetting range = add(new NumberSetting("Range", "How far away targets can be", 96, 8, 256, 1));
	private final NumberSetting height = add(new NumberSetting("Height", "Blocks above the target to drop from", 15, 4, 50, 1));
	private final NumberSetting dropRadius = add(new NumberSetting("Drop Radius", "Stop gliding this close horizontally", 2, 0.5, 6, 0.5));
	private final NumberSetting hitRange = add(new NumberSetting("Hit Range", "Mace reach", 3.5, 1, 6, 0.1));
	private final NumberSetting turnSpeed = add(new NumberSetting("Turn Speed", "Max degrees turned per tick", 25, 1, 180, 1));
	private final EnumSetting<SwitchMode> switchMode = add(new EnumSetting<>("Switch", "How to switch to the mace", SwitchMode.SILENT));

	private PlayerEntity target;
	private boolean dropping;

	public MaceElytraTargetModule() {
		super("Mace Elytra Target", "Elytra dives onto players with a mace smash", Category.RAGE);
	}

	@Override
	protected void onDisable() {
		target = null;
		dropping = false;
	}

	@Override
	public void onPreMotion() {
		if (mc.player.isOnGround()) dropping = false;
		target = TargetUtil.closestPlayer(range.get());
		if (target == null) return;

		if (mc.player.isGliding()) {
			Vec3d above = target.getEntityPos().add(0, height.get(), 0);
			double horizontal = Math.hypot(above.x - mc.player.getX(), above.z - mc.player.getZ());
			if (horizontal <= dropRadius.get() && mc.player.getY() > target.getY() + 3) {
				// Toggling fall flying while already gliding makes the server end the glide
				mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
				mc.player.stopGliding();
				dropping = true;
				return;
			}
			FemboyWare.getRotations().request(Rotation.between(mc.player.getEyePos(), above), ROTATION_PRIORITY, turnSpeed.getFloat(), true);
		} else if (dropping) {
			Vec3d center = target.getBoundingBox().getCenter();
			Vec3d toward = center.subtract(mc.player.getEntityPos());
			// Steer the fall onto them
			mc.player.setVelocity(Math.max(-0.3, Math.min(0.3, toward.x * 0.2)), mc.player.getVelocity().y, Math.max(-0.3, Math.min(0.3, toward.z * 0.2)));
		}
	}

	@Override
	public void onTick() {
		if (!dropping || target == null || mc.player.isGliding() || mc.player.fallDistance < 1.5) return;
		if (mc.player.getEyePos().distanceTo(target.getBoundingBox().getCenter()) > hitRange.get()) return;
		int slot = InventoryUtil.findHotbar(stack -> stack.isOf(Items.MACE));
		if (slot < 0) return;
		PlayerEntity victim = target;
		boolean hit = InventoryUtil.withSlot(slot, switchMode.get(), () -> {
			PlaceUtil.lookAt(victim.getBoundingBox().getCenter());
			mc.interactionManager.attackEntity(mc.player, victim);
			mc.player.swingHand(Hand.MAIN_HAND);
		});
		if (hit) dropping = false;
	}
}
