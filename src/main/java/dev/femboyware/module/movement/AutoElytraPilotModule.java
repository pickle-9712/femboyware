package dev.femboyware.module.movement;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.ChatUtil;
import dev.femboyware.util.InventoryUtil;
import dev.femboyware.util.PlaceUtil;
import dev.femboyware.util.Timer;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Flies your elytra to a spot for you: {@code .pilot <x> <z>} takes off, climbs to cruising height,
 * flies there with rockets when it slows down, then glides down and lands. It climbs over high
 * ground ahead and turns off when you've landed.
 */
public class AutoElytraPilotModule extends Module {
	private final NumberSetting cruise = add(new NumberSetting("Cruise Height", "Height to fly at", 200, 70, 320, 5));
	private final NumberSetting clearance = add(new NumberSetting("Clearance", "Blocks to keep above the ground ahead", 30, 8, 100, 1));
	private final BoolSetting rockets = add(new BoolSetting("Rockets", "Fire rockets to keep speed up", true));
	private final NumberSetting minSpeed = add(new NumberSetting("Min Speed", "Fire a rocket below this many blocks per tick", 1.2, 0.3, 3, 0.05)).visibleWhen(rockets::get);
	private final NumberSetting rocketDelay = add(new NumberSetting("Rocket Delay", "Milliseconds between rockets", 1500, 300, 6000, 50)).visibleWhen(rockets::get);
	private final BoolSetting land = add(new BoolSetting("Land", "Glide down and land at the spot", true));

	private Integer targetX, targetZ;
	private final Timer rocketTimer = new Timer();
	private int takeoff;

	public AutoElytraPilotModule() {
		super("Auto Elytra Pilot", "Flies your elytra to coordinates you give with .pilot", Category.RAGE);
	}

	@Override
	public String getInfo() {
		if (targetX == null || mc.player == null) return null;
		return (int) Math.sqrt(dx() * dx() + dz() * dz()) + "m";
	}

	/** {@code .pilot <x> <z>} or {@code .pilot stop}. */
	public void command(String[] args) {
		if (args.length >= 3) {
			try {
				targetX = coord(args[1], mc.player.getBlockX());
				targetZ = coord(args[2], mc.player.getBlockZ());
			} catch (NumberFormatException e) {
				ChatUtil.info("Usage: .pilot <x> <z> | stop");
				return;
			}
			ChatUtil.info("Flying to " + targetX + ", " + targetZ);
			if (!isEnabled()) setEnabled(true);
			else onEnable();
			return;
		}
		if (args.length == 2 && args[1].equalsIgnoreCase("stop")) {
			setEnabled(false);
			ChatUtil.info("Autopilot off");
			return;
		}
		ChatUtil.info("Usage: .pilot <x> <z> | stop");
	}

	/** A coordinate, or ~ / ~100 relative to where you are. */
	private static int coord(String text, int here) {
		if (text.startsWith("~")) return here + (text.length() > 1 ? (int) Math.floor(Double.parseDouble(text.substring(1))) : 0);
		return (int) Math.floor(Double.parseDouble(text));
	}

	private double dx() {
		return targetX + 0.5 - mc.player.getX();
	}

	private double dz() {
		return targetZ + 0.5 - mc.player.getZ();
	}

	@Override
	protected void onEnable() {
		if (nullCheck()) {
			setEnabled(false);
			return;
		}
		if (targetX == null) {
			ChatUtil.info("Auto Elytra Pilot: give it somewhere to go with .pilot <x> <z>");
			setEnabled(false);
			return;
		}
		if (!mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
			ChatUtil.info("Auto Elytra Pilot: put an elytra on first");
			setEnabled(false);
			return;
		}
		takeoff = 0;
	}

	@Override
	protected void onDisable() {
		if (mc.options != null) mc.options.keyJump.setDown(false);
	}

	@Override
	public void onTick() {
		var p = mc.player;
		double dist = Math.sqrt(dx() * dx() + dz() * dz());
		if (!p.isFallFlying()) {
			if (dist < 4 && p.onGround()) {
				ChatUtil.info("Auto Elytra Pilot: landed at " + targetX + ", " + targetZ);
				setEnabled(false);
				return;
			}
			// Take off: jump, then open the elytra on the way down
			if (p.onGround()) {
				mc.options.keyJump.setDown(true);
				takeoff = 0;
				return;
			}
			mc.options.keyJump.setDown(false);
			if (++takeoff >= 2 && p.getDeltaMovement().y < 0 && p.tryToStartFallFlying()) {
				mc.getConnection().send(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
				rocketTimer.reset();
				if (rockets.get()) PlaceUtil.useItem(s -> s.is(Items.FIREWORK_ROCKET), InventoryUtil.SwitchMode.SILENT);
			}
			return;
		}

		// Point at the target
		float yaw = (float) Math.toDegrees(Math.atan2(dz(), dx())) - 90;
		p.setYRot(p.getYRot() + Mth.wrapDegrees(yaw - p.getYRot()) * 0.3f);

		// How high to be: cruising, the ground ahead plus clearance, or coming in to land
		int aheadX = (int) (p.getX() + Math.cos(Math.toRadians(yaw + 90)) * 48), aheadZ = (int) (p.getZ() + Math.sin(Math.toRadians(yaw + 90)) * 48);
		int ground = Math.max(mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, p.getBlockX(), p.getBlockZ()),
			mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, aheadX, aheadZ));
		double want = Math.max(cruise.get(), ground + clearance.get());
		boolean landing = land.get() && dist < Math.max(60, (p.getY() - ground) * 1.5);
		float pitch;
		double speed = p.getDeltaMovement().length();
		if (landing) {
			// Glide in: steeper the higher above the spot, levelling out near the ground
			double above = p.getY() - mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, targetX, targetZ);
			pitch = (float) Mth.clamp(Math.toDegrees(Math.atan2(above, Math.max(1, dist))), 5, 45);
			if (above < 4) pitch = 0;
		} else if (p.getY() < want - 5) {
			pitch = -25;
		} else if (p.getY() > want + 10) {
			pitch = 12;
		} else pitch = -2;
		p.setXRot(p.getXRot() + (pitch - p.getXRot()) * 0.25f);

		if (rockets.get() && !landing && speed < minSpeed.get() && rocketTimer.passed(rocketDelay.get())) {
			if (PlaceUtil.useItem(s -> s.is(Items.FIREWORK_ROCKET), InventoryUtil.SwitchMode.SILENT)) rocketTimer.reset();
		}
	}
}
