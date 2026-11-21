package dev.femboyware.module.combat;

import dev.femboyware.mixin.MinecraftClientAccessor;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.InventoryUtil;
import dev.femboyware.util.Mc;
import dev.femboyware.util.MouseTurner;
import dev.femboyware.util.Rotation;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Puts you out when you're on fire the way you would by hand: switch to a water bucket, flick down
 * to the floor and pour it at your feet, then scoop it back up once the fire's out and go back to
 * what you held. Real switches, real clicks on the crosshair, the view turned in whole mouse steps.
 * Not in the Nether (water boils away) or while you're still in lava.
 */
public class AutoFireStopModule extends Module {
	private final NumberSetting reaction = add(new NumberSetting("Reaction", "Ticks after catching fire before reaching for the bucket", 3, 0, 20, 1));
	private final BoolSetting pickUp = add(new BoolSetting("Pick Up", "Scoop the water back up once you're out, so the bucket's ready again", true));
	private final BoolSetting switchBack = add(new BoolSetting("Switch Back", "Go back to what you were holding afterwards", true));
	private final NumberSetting flickSpeed = add(new NumberSetting("Flick Speed", "How fast your view flicks down, in whole mouse steps at your sensitivity", 40, 6, 80, 1));

	private final MouseTurner turner = new MouseTurner();
	private Vec3 aimPoint;
	private long lastFrame;
	private int lastSlot = -1, sinceSwitch, originalSlot = -1, burning;
	private BlockPos poured;
	private long pouredAt;
	private boolean busy;

	public AutoFireStopModule() {
		super("Auto Fire Stop", "Puts you out with a water bucket when you're on fire, then scoops the water back up", Category.LEGIT);
	}

	@Override
	protected void onDisable() {
		aimPoint = null;
		poured = null;
		busy = false;
		originalSlot = -1;
		burning = 0;
	}

	/** Putting a fire out: Legit Aura doesn't aim or swing meanwhile. */
	public boolean isBusy() {
		return isEnabled() && busy;
	}

	@Override
	public void onTick() {
		aimPoint = null;
		if (nullCheck()) return;
		int selected = mc.player.getInventory().getSelectedSlot();
		if (selected != lastSlot) {
			lastSlot = selected;
			sinceSwitch = 0;
		} else sinceSwitch++;
		burning = onFire() ? burning + 1 : 0;
		if (Mc.screen() != null || mc.player.isUsingItem()) return;

		long now = mc.level.getGameTime();
		// Scoop up the water we poured once the fire's out
		if (poured != null) {
			boolean there = mc.level.getFluidState(poured).is(Fluids.WATER) && mc.level.getFluidState(poured).isSource();
			boolean reach = mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(poured)) <= mc.player.blockInteractionRange();
			if (!there && now - pouredAt > 10 || !reach) poured = null;
			else if (!onFire() && there) {
				if (!pickUp.get()) {
					poured = null;
				} else if (hasHotbar(false)) {
					busy = true;
					if (!holding(false)) {
						hold(false);
						return;
					}
					aimPoint = Vec3.atCenterOf(poured);
					if (sinceSwitch >= 1 && looksAtWater(poured)) {
						((MinecraftClientAccessor) mc).femboyware$rightClick();
						poured = null;
					}
					return;
				}
			}
		}

		if (!onFire() || burning < reaction.getInt() || poured != null || !hasHotbar(true) || !mc.player.onGround()) {
			if (busy && poured == null && !onFire()) finish();
			return;
		}
		BlockPos floor = mc.player.blockPosition().below();
		if (mc.level.getBlockState(floor).getCollisionShape(mc.level, floor).isEmpty()) return;
		busy = true;
		if (!holding(true)) {
			hold(true);
			return;
		}
		aimPoint = new Vec3(floor.getX() + 0.5, floor.getY() + 1, floor.getZ() + 0.5);
		if (sinceSwitch >= 1 && mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
			&& hit.getBlockPos().equals(floor) && hit.getDirection() == Direction.UP) {
			((MinecraftClientAccessor) mc).femboyware$rightClick();
			poured = floor.above().immutable();
			pouredAt = now;
		}
	}

	private boolean onFire() {
		return mc.player.isOnFire() && !mc.player.hasEffect(MobEffects.FIRE_RESISTANCE) && !mc.player.isInLava() && !mc.player.isInWater()
			&& mc.level.dimension() != Level.NETHER;
	}

	private void finish() {
		busy = false;
		if (switchBack.get() && originalSlot >= 0 && mc.player.getInventory().getSelectedSlot() != originalSlot) InventoryUtil.select(originalSlot);
		originalSlot = -1;
	}

	/** The bucket's own look at fluids: what an empty bucket would scoop from here. */
	private boolean looksAtWater(BlockPos pos) {
		Vec3 eyes = mc.player.getEyePosition();
		Vec3 end = eyes.add(mc.player.getViewVector(1).scale(mc.player.blockInteractionRange()));
		BlockHitResult hit = mc.level.clip(new ClipContext(eyes, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, mc.player));
		return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos);
	}

	private boolean holding(boolean full) {
		return mc.player.getMainHandItem().is(full ? Items.WATER_BUCKET : Items.BUCKET);
	}

	private boolean hasHotbar(boolean full) {
		return holding(full) || InventoryUtil.findHotbar(stack -> stack.is(full ? Items.WATER_BUCKET : Items.BUCKET)) >= 0;
	}

	/** A real switch to a water (or empty) bucket, remembering what you had. */
	private void hold(boolean full) {
		int slot = InventoryUtil.findHotbar(stack -> stack.is(full ? Items.WATER_BUCKET : Items.BUCKET));
		if (slot < 0) return;
		if (originalSlot < 0) originalSlot = mc.player.getInventory().getSelectedSlot();
		InventoryUtil.select(slot);
	}

	/** A quick flick down to the floor or onto the water, every frame in whole mouse steps, a little uneven. */
	@Override
	public void onFrame() {
		long now = System.nanoTime();
		float seconds = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1e9f);
		lastFrame = now;
		if (aimPoint == null || seconds <= 0 || mc.player == null || Mc.screen() != null) {
			turner.reset();
			return;
		}
		float tickDelta = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		Rotation wanted = Rotation.between(mc.player.getEyePosition(tickDelta), aimPoint);
		double yawLeft = Mth.wrapDegrees(wanted.yaw() - mc.player.getYRot());
		double pitchLeft = wanted.pitch() - mc.player.getXRot();
		// Straight down: there's no need to swing round, only to look at the floor
		if (wanted.pitch() > 80) yawLeft = 0;
		double follow = 1 - Math.exp(-flickSpeed.get() * seconds);
		double wobble = 0.85 + ThreadLocalRandom.current().nextDouble() * 0.3;
		turner.turn(yawLeft * follow * wobble, pitchLeft * follow * wobble);
	}
}
