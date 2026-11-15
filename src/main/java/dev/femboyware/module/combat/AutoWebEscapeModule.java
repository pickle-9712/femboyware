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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Gets you out of a web the way you would by hand: switch to a water bucket, flick onto the web
 * and click it (the water washes it away), then scoop the water back up so the bucket's ready for
 * the next web (your feet and head) and you're not left swimming. Then back to what you held.
 * Real switches, real clicks on the crosshair, one a tick, the view turned in whole mouse steps.
 */
public class AutoWebEscapeModule extends Module {
	private final BoolSetting pickUp = add(new BoolSetting("Pick Up", "Scoop the water back up once the web's gone, so you can use it again and aren't left in water", true));
	private final BoolSetting switchBack = add(new BoolSetting("Switch Back", "Go back to what you were holding when you're out", true));
	private final NumberSetting flickSpeed = add(new NumberSetting("Flick Speed", "How fast your view flicks onto the web, in whole mouse steps at your sensitivity", 40, 6, 80, 1));

	private final MouseTurner turner = new MouseTurner();
	/** Water this put down that it still means to pick up. */
	private final List<Pour> poured = new ArrayList<>();

	/**
	 * Water this put down: the web clicked and the spot beside it (the water goes in whichever it
	 * can), and the tick it was poured, as the server takes a moment to show it.
	 */
	private record Pour(BlockPos web, BlockPos beside, long tick) {
		BlockPos source(net.minecraft.world.level.Level level) {
			for (BlockPos pos : new BlockPos[]{web, beside}) {
				if (level.getFluidState(pos).is(Fluids.WATER) && level.getFluidState(pos).isSource()) return pos;
			}
			return null;
		}
	}
	private Vec3 aimPoint;
	private long lastFrame;
	private int lastSlot = -1, sinceSwitch, originalSlot = -1;
	private boolean busy;

	public AutoWebEscapeModule() {
		super("Auto Web Escape", "Washes away the web you're stuck in with a water bucket, then scoops the water back up", Category.LEGIT);
	}

	@Override
	protected void onDisable() {
		poured.clear();
		aimPoint = null;
		busy = false;
		originalSlot = -1;
	}

	/** Clearing a web: Legit Aura doesn't aim or swing meanwhile. */
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

		List<BlockPos> webs = websOnYou();
		long now = mc.level.getGameTime();
		poured.removeIf(pour -> {
			BlockPos source = pour.source(mc.level);
			if (source == null) return now - pour.tick() > 10;
			return mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(source)) > mc.player.blockInteractionRange();
		});
		if (Mc.screen() != null || mc.player.isUsingItem()) return;

		boolean full = holding(true), empty = holding(false);
		// The bucket's empty and there's water of ours to fill it from: scoop it (to use on the next
		// web, or because you're out and it's in the way)
		boolean hasEmpty = holding(false) || InventoryUtil.findHotbar(stack -> stack.is(Items.BUCKET)) >= 0;
		if (!poured.isEmpty() && hasEmpty && (webs.isEmpty() ? pickUp.get() : !hasFullBucket())) {
			BlockPos water = poured.get(0).source(mc.level);
			busy = true;
			// Still waiting for it to show up, or for it to flow into the web and wash it away
			if (water == null || mc.level.getBlockState(poured.get(0).web()).is(Blocks.COBWEB) && now - poured.get(0).tick() < 25) return;
			if (!empty) {
				hold(false);
				return;
			}
			aimPoint = Vec3.atCenterOf(water);
			if (sinceSwitch >= 1 && looksAtWater(water)) ((MinecraftClientAccessor) mc).femboyware$rightClick();
			return;
		}
		if (webs.isEmpty()) {
			if (busy) finish();
			return;
		}
		if (!hasFullBucket()) {
			if (busy) finish();
			return;
		}
		busy = true;
		if (!full) {
			hold(true);
			return;
		}
		// The web the crosshair's already on, if it's one of yours; otherwise flick to the nearest
		BlockPos onCrosshair = mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK && webs.contains(hit.getBlockPos()) ? hit.getBlockPos() : null;
		if (onCrosshair != null && sinceSwitch >= 1) {
			((MinecraftClientAccessor) mc).femboyware$rightClick();
			BlockHitResult hit = (BlockHitResult) mc.hitResult;
			poured.add(new Pour(onCrosshair.immutable(), onCrosshair.relative(hit.getDirection()).immutable(), mc.level.getGameTime()));
			return;
		}
		aimPoint = Vec3.atCenterOf(nearestToView(webs));
	}

	private void finish() {
		busy = false;
		if (switchBack.get() && originalSlot >= 0 && mc.player.getInventory().getSelectedSlot() != originalSlot) InventoryUtil.select(originalSlot);
		originalSlot = -1;
	}

	/** Webs your body is in. */
	private List<BlockPos> websOnYou() {
		List<BlockPos> webs = new ArrayList<>();
		AABB box = mc.player.getBoundingBox().deflate(0.001);
		for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
			if (mc.level.getBlockState(pos).is(Blocks.COBWEB)) webs.add(pos.immutable());
		}
		return webs;
	}

	private BlockPos nearestToView(List<BlockPos> webs) {
		Vec3 eyes = mc.player.getEyePosition(), look = mc.player.getViewVector(1);
		BlockPos best = webs.get(0);
		double bestDot = -2;
		for (BlockPos pos : webs) {
			double dot = Vec3.atCenterOf(pos).subtract(eyes).normalize().dot(look);
			if (dot > bestDot) {
				bestDot = dot;
				best = pos;
			}
		}
		return best;
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

	private boolean hasFullBucket() {
		return holding(true) || InventoryUtil.findHotbar(stack -> stack.is(Items.WATER_BUCKET)) >= 0;
	}

	/** A real switch to a water (or empty) bucket, remembering what you had. */
	private void hold(boolean full) {
		int slot = InventoryUtil.findHotbar(stack -> stack.is(full ? Items.WATER_BUCKET : Items.BUCKET));
		if (slot < 0) return;
		if (originalSlot < 0) originalSlot = mc.player.getInventory().getSelectedSlot();
		InventoryUtil.select(slot);
	}

	/** A quick flick onto the web or water, every frame in whole mouse steps, a little uneven. */
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
		double follow = 1 - Math.exp(-flickSpeed.get() * seconds);
		double wobble = 0.85 + ThreadLocalRandom.current().nextDouble() * 0.3;
		turner.turn(yawLeft * follow * wobble, pitchLeft * follow * wobble);
	}
}
