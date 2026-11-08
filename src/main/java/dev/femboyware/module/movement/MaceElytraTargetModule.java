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
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

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

	private Player target;
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
		if (mc.player.onGround()) dropping = false;
		target = TargetUtil.closestPlayer(range.get());
		if (target == null) return;

		if (mc.player.isFallFlying()) {
			Vec3 above = target.position().add(0, height.get(), 0);
			double horizontal = Math.hypot(above.x - mc.player.getX(), above.z - mc.player.getZ());
			if (horizontal <= dropRadius.get() && mc.player.getY() > target.getY() + 3) {
				// Toggling fall flying while already gliding makes the server end the glide
				mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
				mc.player.stopFallFlying();
				dropping = true;
				return;
			}
			FemboyWare.getRotations().request(Rotation.between(mc.player.getEyePosition(), above), ROTATION_PRIORITY, turnSpeed.getFloat(), true);
		} else if (dropping) {
			Vec3 center = target.getBoundingBox().getCenter();
			Vec3 toward = center.subtract(mc.player.position());
			// Steer the fall onto them
			mc.player.setDeltaMovement(Math.max(-0.3, Math.min(0.3, toward.x * 0.2)), mc.player.getDeltaMovement().y, Math.max(-0.3, Math.min(0.3, toward.z * 0.2)));
		}
	}

	@Override
	public void onTick() {
		if (!dropping || target == null || mc.player.isFallFlying() || mc.player.fallDistance < 1.5) return;
		if (mc.player.getEyePosition().distanceTo(target.getBoundingBox().getCenter()) > hitRange.get()) return;
		int slot = InventoryUtil.findHotbar(stack -> stack.is(Items.MACE));
		if (slot < 0) return;
		Player victim = target;
		boolean hit = InventoryUtil.withSlot(slot, switchMode.get(), () -> {
			PlaceUtil.lookAt(victim.getBoundingBox().getCenter());
			mc.gameMode.attack(mc.player, victim);
			mc.player.swing(InteractionHand.MAIN_HAND);
		});
		if (hit) dropping = false;
	}
}
