/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 *
 * Modified for femboyware: ModuleClutch and ScaffoldClutchTechnique ported from Kotlin to Java and
 * merged into one module that places by itself (with the scaffold's block choice and target
 * finding) instead of switching LiquidBounce's scaffold to a hidden technique. The fall is
 * predicted with femboyware's FallSimulator, and an optional On Tick timing sends the aim with the
 * click.
 */
package dev.femboyware.module.movement;

import dev.femboyware.FemboyWare;
import dev.femboyware.lb.RotationSettings;
import dev.femboyware.lb.TargetFinding;
import dev.femboyware.lb.TargetFinding.PlacementTarget;
import dev.femboyware.lb.TargetFinding.PlayerLocation;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.FallSimulator;
import dev.femboyware.util.InventoryUtil;
import dev.femboyware.util.InventoryUtil.SwitchMode;
import dev.femboyware.util.Rotation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Catches a deep fall, such as one after being knocked off an edge, by building under it.
 */
public class ClutchModule extends Module {
	private static final int ROTATION_PRIORITY = 85;
	// Further ahead than a fall of the largest Min Fall Distance takes
	private static final int MAX_FALL_TICKS = 60;

	public enum Timing { NORMAL, ON_TICK }

	private final NumberSetting minFallDistance = add(new NumberSetting("Min Fall Distance", "Falls that end before this are left alone (one into the void never ends)", 5, 2, 50, 0.5));
	private final EnumSetting<Timing> timing = add(new EnumSetting<>("Timing", "Normal aims a tick ahead; On Tick sends the aim with the click (catches more, extra packet)", Timing.NORMAL));
	private final EnumSetting<SwitchMode> switchMode = add(new EnumSetting<>("Switch", "How to switch to blocks", SwitchMode.SILENT));
	private final dev.femboyware.setting.BoolSetting grim = addGrim();
	private final RotationSettings rotations = new RotationSettings(s -> add(s), () -> true, 180f);

	private List<Vec3> fall;
	private PlacementTarget target;

	public ClutchModule() {
		super("Clutch", "Builds under you when you're knocked or fall off an edge (LiquidBounce)", Category.BUILD);
	}

	@Override
	protected void onDisable() {
		fall = null;
		target = null;
	}

	private ScaffoldModule scaffold() {
		return FemboyWare.getModules().get(ScaffoldModule.class);
	}

	private boolean canCatch() {
		var p = mc.player;
		if (p.isPassenger() || p.isFallFlying() || p.getAbilities().flying) return false;
		ScaffoldModule scaffold = scaffold();
		return !scaffold.isEnabled() && scaffold.blockCount() > 0;
	}

	@Override
	public void onPreMotion() {
		if (nullCheck()) return;
		target = null;
		fall = canCatch() ? predictFall() : null;
		if (fall == null || fall.isEmpty()) return;

		int slot = scaffold().bestHotbarSlot();
		if (slot < 0) return;
		ItemStack stack = mc.player.getInventory().getItem(slot);
		if (timing.is(Timing.ON_TICK) && !dev.femboyware.util.Grim.on()) {
			// Aimed and clicked after the move, from where the player is then (onPostMotion)
			return;
		} else {
			// What the server rotation already points at is clicked this tick; anything else can only be aimed at,
			// for the next tick to click from where the player is by then
			PlacementTarget now = target(mc.player.position(), fall, stack);
			if (now != null && now.verifyClick(FemboyWare.getRotations().getServerRotation(), false) != null) target = now;
			else target = target(fall.get(0), fall.subList(1, fall.size()), stack);
		}
		if (target != null) FemboyWare.getRotations().request(target.rotation(), ROTATION_PRIORITY, rotations, false);
	}

	private List<Vec3> predictFall() {
		var p = mc.player;
		var keys = p.input.keyPresses;
		boolean steered = keys.forward() || keys.backward() || keys.left() || keys.right();
		// On the ground only momentum takes the player where it does not walk itself
		if (p.onGround() && (steered || p.getDeltaMovement().horizontalDistanceSqr() == 0.0)) return null;
		return FallSimulator.fallPath(minFallDistance.getFloat(), MAX_FALL_TICKS);
	}

	private boolean canStandOn(BlockPos pos) {
		return !mc.level.getBlockState(pos).getCollisionShape(mc.level, pos).isEmpty();
	}

	/** The block to place from {@code from} to get under {@code fall}, the positions of the ticks after. */
	private PlacementTarget target(Vec3 from, List<Vec3> fall, ItemStack stack) {
		PlayerLocation clickFrom = PlayerLocation.of(from, Pose.STANDING);
		double range = mc.player.blockInteractionRange();
		double feetY = from.y;
		for (Vec3 position : fall) {
			// A block catches the player where its feet come down through the top of a block layer. At the click
			// the feet have to be above that layer already
			double top = Math.floor(feetY);
			feetY = position.y;
			if (position.y >= top || top > from.y) continue;
			if (clickFrom.eyePos().y - top > range) break;

			// The block goes under the player, not under where it comes down: a knock carries it blocks away, and
			// only a row that keeps up with it gets there. With a block under it the row has to wait for it
			BlockPos below = BlockPos.containing(from.x, top - 1, from.z);
			if (canStandOn(below)) return null;

			// A block right on top of ground gains nothing over that ground
			List<Vec3i> offsets = new ArrayList<>();
			for (BlockPos offset : TargetFinding.FULL) if (!canStandOn(below.offset(offset).below())) offsets.add(offset);
			TargetFinding.Options options = new TargetFinding.Options(offsets,
				TargetFinding.leastBlockDistanceToPos(new Vec3(from.x, top - 0.5, from.z)),
				TargetFinding.factory(TargetFinding.AimMode.CENTER, null), false, stack, clickFrom, range);
			PlacementTarget found = TargetFinding.findBest(below, options);
			// Without a block in reach on this layer, one further down may still have one
			if (found != null) return found;
		}
		return null;
	}

	@Override
	public void onPostMotion() {
		if (nullCheck()) return;
		boolean onTick = timing.is(Timing.ON_TICK) && !dev.femboyware.util.Grim.on();
		if (onTick && fall != null && fall.size() > 1) {
			int s = scaffold().bestHotbarSlot();
			if (s >= 0) target = target(mc.player.position(), fall.subList(1, fall.size()), mc.player.getInventory().getItem(s));
		}
		if (target == null) return;
		Rotation server = FemboyWare.getRotations().getServerRotation();
		Rotation rotation = onTick ? target.rotation() : server;
		BlockHitResult hit = target.verifyClick(rotation, false);
		if (hit == null) return;

		int slot = scaffold().bestHotbarSlot();
		boolean inHand = ScaffoldModule.isValidBlock(mc.player.getMainHandItem());
		if (!inHand && slot < 0) return;
		if (onTick && server.distanceTo(rotation) > 0.01f) FemboyWare.getRotations().sendPacketRotation(rotation);
		Runnable place = () -> {
			if (mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit).consumesAction()) mc.player.swing(InteractionHand.MAIN_HAND);
		};
		if (inHand) place.run();
		else InventoryUtil.withSlot(slot, switchMode.get(), place);
		target = null;
	}

	@Override
	public String getInfo() {
		return fall != null ? "Catching" : null;
	}
}
