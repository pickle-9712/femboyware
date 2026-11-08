package dev.femboyware.module.combat;

import dev.femboyware.util.Mc;
import dev.femboyware.FemboyWare;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.module.utility.FriendsModule;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.BlockUtil;
import dev.femboyware.util.ChatUtil;
import dev.femboyware.util.DamageCalculator;
import dev.femboyware.util.InventoryUtil;
import dev.femboyware.util.InventoryUtil.SwitchMode;
import dev.femboyware.util.RenderUtil;
import dev.femboyware.util.Rotation;
import dev.femboyware.util.Timer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Places a respawn anchor where it hurts the target most, charges it with one glowstone and
 * detonates it, all in one tick. Damage rules match Auto Crystal's. Anchors only explode outside
 * the Nether; in the Nether they set your spawn instead, so the module waits there.
 */
public class AutoAnchorModule extends Module {
	private static final int ROTATION_PRIORITY = 100;
	/** Respawn anchor explosion power, from vanilla. */
	private static final float ANCHOR_POWER = 5f;
	private static final float FACE_PLACE_DAMAGE = 1.5f;
	/** Longest to wait for the server to confirm an anchor blew up before carrying on anyway. */
	private static final long CONFIRM_TIMEOUT = 1000;

	public enum Page { MAIN, PLACE, DAMAGES, SWAP, PAUSE, RENDER }
	public enum RotateMode { OFF, TICK, PACKET }
	public enum Interact { DEFAULT, STRICT }
	public enum TargetSort { DISTANCE, HEALTH, DAMAGE }

	/** Where to put the anchor; {@code placeHit} is null when one is already there. */
	private record AnchorPlan(BlockPos pos, BlockHitResult placeHit, BlockHitResult useHit, Rotation rotation, float damage, float self) {}

	private record Evaluation(float damage, float self, boolean facePlace) {}

	private final EnumSetting<Page> page = add(new EnumSetting<>("Page", "Settings page", Page.MAIN));

	// Main
	private final EnumSetting<RotateMode> rotate = add(new EnumSetting<>("Rotate Mode", "Look at the anchor. Packet is instant, Tick waits for the movement packet", RotateMode.PACKET)).visibleWhen(() -> page.is(Page.MAIN));
	private final NumberSetting degreesPerTick = add(new NumberSetting("Degrees Per Tick", "Rotation speed limit", 180, 10, 180, 5)).visibleWhen(() -> page.is(Page.MAIN) && rotate.is(RotateMode.TICK));
	private final NumberSetting delay = add(new NumberSetting("Delay", "Milliseconds between anchors", 0, 0, 1000, 10)).visibleWhen(() -> page.is(Page.MAIN));
	private final NumberSetting targetRange = add(new NumberSetting("Target Range", "How far away targets can be", 12, 2, 16, 0.5)).visibleWhen(() -> page.is(Page.MAIN));
	private final EnumSetting<TargetSort> targetSort = add(new EnumSetting<>("Target Sort", "How targets are prioritised", TargetSort.DAMAGE)).visibleWhen(() -> page.is(Page.MAIN));
	private final NumberSetting extrapolation = add(new NumberSetting("Extrapolation", "Ticks ahead to predict where moving targets will be", 0, 0, 20, 1)).visibleWhen(() -> page.is(Page.MAIN));

	// Place
	private final EnumSetting<Interact> interact = add(new EnumSetting<>("Interact", "Default clicks any side, Strict only sides you can see", Interact.DEFAULT)).visibleWhen(() -> page.is(Page.PLACE));
	private final NumberSetting placeRange = add(new NumberSetting("Place Range", "Max place distance", 5, 1, 6, 0.1)).visibleWhen(() -> page.is(Page.PLACE));
	private final BoolSetting rayTrace = add(new BoolSetting("Ray Trace", "Don't place through walls", false)).visibleWhen(() -> page.is(Page.PLACE));
	private final NumberSetting wallRange = add(new NumberSetting("Wall Range", "Max place distance through walls", 5, 0, 6, 0.1)).visibleWhen(() -> page.is(Page.PLACE) && !rayTrace.get());
	private final NumberSetting radius = add(new NumberSetting("Radius", "Blocks around the target to look for anchor spots", 2, 1, 3, 1)).visibleWhen(() -> page.is(Page.PLACE));

	// Damages
	private final BoolSetting assumeBestArmor = add(new BoolSetting("Assume Best Armor", "Treat enemy armor as Blast Protection IV", false)).visibleWhen(() -> page.is(Page.DAMAGES));
	private final NumberSetting minDamage = add(new NumberSetting("Min Damage", "Ignore spots dealing less than this", 6, 0, 20, 0.5)).visibleWhen(() -> page.is(Page.DAMAGES));
	private final NumberSetting maxSelfDamage = add(new NumberSetting("Max Self Damage", "Ignore spots hurting you more than this", 8, 0, 20, 0.5)).visibleWhen(() -> page.is(Page.DAMAGES));
	private final NumberSetting minDamageFactor = add(new NumberSetting("Min Damage Factor", "Minimum enemy damage / self damage ratio", 1.5, 0, 10, 0.1)).visibleWhen(() -> page.is(Page.DAMAGES));
	private final BoolSetting overrideSelfDamage = add(new BoolSetting("Override Self Damage", "Exceed Max Self Damage when the hit is lethal", true)).visibleWhen(() -> page.is(Page.DAMAGES));
	private final BoolSetting sacrificeTotem = add(new BoolSetting("Sacrifice Totem", "Pop your own totem if it kills a target without one", false)).visibleWhen(() -> page.is(Page.DAMAGES));
	private final NumberSetting armorPercent = add(new NumberSetting("Armor Percent", "Face place when an enemy armor piece is below this durability %", 15, 0, 100, 1)).visibleWhen(() -> page.is(Page.DAMAGES));
	private final NumberSetting facePlaceHealth = add(new NumberSetting("Face Place Health", "Face place when enemy health is below this", 10, 0, 36, 0.5)).visibleWhen(() -> page.is(Page.DAMAGES));
	private final BoolSetting destructibleTerrain = add(new BoolSetting("Destructible Terrain", "Explosions break blocks on this server", false)).visibleWhen(() -> page.is(Page.DAMAGES));

	// Swap
	private final EnumSetting<SwitchMode> switchMode = add(new EnumSetting<>("Switch", "How to switch to anchors and glowstone", SwitchMode.SILENT)).visibleWhen(() -> page.is(Page.SWAP));

	// Pause
	private final BoolSetting pauseEating = add(new BoolSetting("Eating", "Pause while eating", true)).visibleWhen(() -> page.is(Page.PAUSE));
	private final BoolSetting pauseMining = add(new BoolSetting("Mining", "Pause while mining blocks", true)).visibleWhen(() -> page.is(Page.PAUSE));
	private final BoolSetting pauseInventory = add(new BoolSetting("Inventory", "Pause while an inventory is open", true)).visibleWhen(() -> page.is(Page.PAUSE));
	private final NumberSetting pauseHealth = add(new NumberSetting("Health", "Pause below this health plus absorption, 0 never pauses", 0, 0, 20, 0.5)).visibleWhen(() -> page.is(Page.PAUSE));

	// Render
	private final BoolSetting render = add(new BoolSetting("Render", "Highlight the anchor spot", true)).visibleWhen(() -> page.is(Page.RENDER));
	private final ColorSetting fillColor = add(new ColorSetting("Fill", "Box fill colour", 0x50AA44FF)).visibleWhen(() -> page.is(Page.RENDER) && render.get());
	private final ColorSetting lineColor = add(new ColorSetting("Line", "Box outline colour", 0xFFAA44FF)).visibleWhen(() -> page.is(Page.RENDER) && render.get());

	private final Timer timer = new Timer();
	private final Map<Player, AABB> predictedBoxes = new HashMap<>();
	/**
	 * The anchor we last detonated. The next one waits until the server's update removes it: the
	 * explosion's terrain changes arrive with that update, and planning against the old terrain
	 * makes the server reject the next place or charge.
	 */
	private BlockPos awaiting;
	private long awaitingSince;
	private AnchorPlan pending;
	private Player target;
	private BlockPos renderPos;
	private float renderDamage;
	private long renderTime;
	private boolean warned;
	private int detonations;

	public AutoAnchorModule() {
		super("Auto Anchor", "Places, charges and blows up respawn anchors next to your target", Category.ATTACK);
	}

	/** The player currently being anchored, or null. */
	public Player getTarget() {
		return target;
	}

	@Override
	public String getInfo() {
		return target == null ? null : target.getName().getString() + " " + detonations;
	}

	@Override
	protected void onDisable() {
		pending = null;
		target = null;
		renderPos = null;
		awaiting = null;
		predictedBoxes.clear();
		warned = false;
	}

	@Override
	public void onPreMotion() {
		pending = null;
		if (awaiting != null) {
			boolean gone = !mc.level.getBlockState(awaiting).is(Blocks.RESPAWN_ANCHOR);
			if (!gone && System.currentTimeMillis() - awaitingSince < CONFIRM_TIMEOUT) return;
			awaiting = null;
		}
		if (shouldPause()) return;
		List<Player> targets = findTargets();
		if (targets.isEmpty()) {
			target = null;
			return;
		}
		pending = findAnchor(targets);
		if (pending != null && !rotate.is(RotateMode.OFF)) {
			FemboyWare.getRotations().request(pending.rotation, ROTATION_PRIORITY, degreesPerTick.getFloat(), false);
		}
	}

	@Override
	public void onPostMotion() {
		if (pending == null || !timer.passed(delay.get())) return;
		AnchorPlan plan = pending;
		AABB anchorBox = new AABB(plan.pos);
		if (!canHit(anchorBox, Vec3.atCenterOf(plan.pos))) return;

		int anchorSlot = InventoryUtil.find(stack -> stack.is(Items.RESPAWN_ANCHOR), switchMode.get());
		int glowstoneSlot = InventoryUtil.find(stack -> stack.is(Items.GLOWSTONE), switchMode.get());
		// Place, charge once and detonate, one packet each, so the server does it all this tick
		if (plan.placeHit != null && !InventoryUtil.withSlot(anchorSlot, switchMode.get(), () -> interactBlock(plan.placeHit))) return;
		boolean charged = mc.level.getBlockState(plan.pos).is(Blocks.RESPAWN_ANCHOR)
			&& mc.level.getBlockState(plan.pos).getValue(RespawnAnchorBlock.CHARGE) > 0;
		if (!charged && !InventoryUtil.withSlot(glowstoneSlot, switchMode.get(), () -> interactBlock(plan.useHit))) return;
		// Anything but glowstone in hand detonates it
		int detonateSlot = mc.player.getMainHandItem().is(Items.GLOWSTONE) ? anchorSlot : mc.player.getInventory().getSelectedSlot();
		if (!InventoryUtil.withSlot(detonateSlot, switchMode.get(), () -> interactBlock(plan.useHit))) return;

		awaiting = plan.pos;
		awaitingSince = System.currentTimeMillis();
		timer.reset();
		detonations++;
		renderPos = plan.pos;
		renderDamage = plan.damage;
		renderTime = System.currentTimeMillis();
	}

	private void interactBlock(BlockHitResult hit) {
		mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
		mc.player.swing(InteractionHand.MAIN_HAND);
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (!render.get() || renderPos == null) return;
		long age = System.currentTimeMillis() - renderTime;
		if (age > 1000) {
			renderPos = null;
			return;
		}
		float fade = 1f - Math.max(0f, (age - 500) / 500f);
		RenderUtil.drawBox(event, new AABB(renderPos), fillColor.withAlpha((int) (fillColor.getAlpha() * fade)), lineColor.withAlpha((int) (lineColor.getAlpha() * fade)), 2f);
		RenderUtil.drawText(event, String.format("%.1f", renderDamage), Vec3.atCenterOf(renderPos), 0xFFFFFFFF, 0.4f);
	}

	// ---------------------------------------------------------------- planning

	private boolean shouldPause() {
		// In the Nether a charged anchor sets your spawn instead of exploding
		if (mc.level.dimension() == Level.NETHER) return true;
		if (mc.player.isShiftKeyDown()) return true;
		if (mc.player.getOffhandItem().is(Items.GLOWSTONE)) {
			// The server charges with offhand glowstone instead of detonating, so nothing would explode
			if (!warned) ChatUtil.info("Auto Anchor: take the glowstone out of your offhand");
			warned = true;
			return true;
		}
		warned = false;
		if (!hasItems()) return true;
		if (pauseMining.get() && mc.gameMode.isDestroying()) return true;
		if (pauseEating.get() && mc.player.isUsingItem() && mc.player.getUseItem().has(DataComponents.FOOD)) return true;
		if (pauseHealth.get() > 0 && mc.player.getHealth() + mc.player.getAbsorptionAmount() < pauseHealth.get()) return true;
		return pauseInventory.get() && Mc.screen() instanceof AbstractContainerScreen<?>;
	}

	private boolean hasItems() {
		if (switchMode.is(SwitchMode.NONE)) return false;
		return InventoryUtil.find(stack -> stack.is(Items.RESPAWN_ANCHOR), switchMode.get()) != -1
			&& InventoryUtil.find(stack -> stack.is(Items.GLOWSTONE), switchMode.get()) != -1;
	}

	private List<Player> findTargets() {
		List<Player> targets = new ArrayList<>();
		for (Player player : mc.level.players()) {
			if (player == mc.player || !player.isAlive() || player.isSpectator() || player.isCreative() || FriendsModule.protects(player)) continue;
			if (player.distanceToSqr(mc.player) > targetRange.get() * targetRange.get()) continue;
			targets.add(player);
		}
		switch (targetSort.get()) {
			case DISTANCE, DAMAGE -> targets.sort(Comparator.comparingDouble(p -> p.distanceToSqr(mc.player)));
			case HEALTH -> targets.sort(Comparator.comparingDouble(p -> p.getHealth() + p.getAbsorptionAmount()));
		}
		// Only DAMAGE sort weighs every target; the others commit to the first one
		if (!targetSort.is(TargetSort.DAMAGE) && targets.size() > 1) targets = targets.subList(0, 1);
		predictedBoxes.clear();
		for (Player player : targets) predictedBoxes.put(player, predictBox(player));
		return targets;
	}

	private AnchorPlan findAnchor(List<Player> targets) {
		AnchorPlan best = null;
		Player bestTarget = null;
		Set<BlockPos> checked = new HashSet<>();
		int r = radius.getInt();
		for (Player player : targets) {
			AABB box = predictedBoxes.getOrDefault(player, player.getBoundingBox());
			BlockPos feet = BlockPos.containing(box.getCenter().x, box.minY, box.getCenter().z);
			// From the floor up to above their head, where anchors hit hardest
			for (BlockPos candidate : BlockPos.betweenClosed(feet.offset(-r, -1, -r), feet.offset(r, 2, r))) {
				BlockPos pos = candidate.immutable();
				if (!checked.add(pos)) continue;
				AnchorPlan plan = planAt(pos, targets);
				if (plan == null) continue;
				if (best == null || plan.damage > best.damage || (plan.damage == best.damage && plan.self < best.self)) {
					best = plan;
					bestTarget = bestTarget(targets, Vec3.atCenterOf(pos));
				}
			}
		}
		target = bestTarget;
		return best;
	}

	/** An anchor at {@code pos}, already there or placed now, or null if it can't be done or isn't worth it. */
	private AnchorPlan planAt(BlockPos pos, List<Player> targets) {
		BlockState state = mc.level.getBlockState(pos);
		boolean existing = state.is(Blocks.RESPAWN_ANCHOR);
		Vec3 eyes = mc.player.getEyePosition();
		Vec3 center = Vec3.atCenterOf(pos);
		double distance = eyes.distanceTo(center);
		if (distance > placeRange.get()) return null;
		if ((rayTrace.get() || distance > wallRange.get()) && !isVisible(eyes, center, pos)) return null;

		BlockHitResult placeHit = null;
		if (!existing) {
			if (!state.canBeReplaced() || BlockUtil.hasBlockingEntity(pos, false)) return null;
			for (Player player : targets) {
				if (predictedBoxes.getOrDefault(player, player.getBoundingBox()).intersects(new AABB(pos))) return null;
			}
			placeHit = BlockUtil.getPlacement(pos, interact.is(Interact.STRICT));
			if (placeHit == null || eyes.distanceTo(placeHit.getLocation()) > placeRange.get()) return null;
		}
		BlockHitResult useHit = useHit(pos, eyes);
		if (useHit == null) return null;

		Evaluation evaluation = evaluate(targets, center, pos);
		if (evaluation == null) return null;
		return new AnchorPlan(pos, placeHit, useHit, Rotation.between(eyes, center), evaluation.damage, evaluation.self);
	}

	/** The face of the anchor to right click, nearest the eyes; Strict only takes faces you can see. */
	private BlockHitResult useHit(BlockPos pos, Vec3 eyes) {
		BlockHitResult best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Direction face : Direction.values()) {
			if (interact.is(Interact.STRICT) && !BlockUtil.isFaceExposed(pos, face, eyes)) continue;
			Vec3 point = BlockUtil.faceCenter(pos, face);
			double distance = eyes.distanceToSqr(point);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = new BlockHitResult(point, face, pos, false);
			}
		}
		return best;
	}

	/** Best damage this anchor deals to any target, or null if it fails the damage rules. */
	private Evaluation evaluate(List<Player> targets, Vec3 center, BlockPos anchor) {
		float self = DamageCalculator.explosionDamage(mc.player, mc.player.getBoundingBox(), center, ANCHOR_POWER, destructibleTerrain.get(), false, null, anchor);
		float selfHealth = mc.player.getHealth() + mc.player.getAbsorptionAmount();
		boolean selfLethal = self >= selfHealth - 0.5f;
		if (selfLethal && !(sacrificeTotem.get() && holdsTotem(mc.player))) return null;

		Evaluation best = null;
		for (Player player : targets) {
			float damage = targetDamage(player, center, anchor);
			float health = player.getHealth() + player.getAbsorptionAmount();
			boolean lethal = damage >= health;
			// Only trade our totem for an actual kill, not for popping theirs
			if (selfLethal && !(lethal && !holdsTotem(player))) continue;
			if (self > maxSelfDamage.get() && !(overrideSelfDamage.get() && lethal)) continue;
			if (self > 0 && damage / self < minDamageFactor.get() && !lethal) continue;
			boolean facePlace = false;
			if (damage < minDamage.get() && !lethal) {
				if (!shouldFacePlace(player) || damage < FACE_PLACE_DAMAGE) continue;
				facePlace = true;
			}
			if (best == null || damage > best.damage) best = new Evaluation(damage, self, facePlace);
		}
		return best;
	}

	private float targetDamage(Player player, Vec3 center, BlockPos anchor) {
		AABB box = predictedBoxes.getOrDefault(player, player.getBoundingBox());
		return DamageCalculator.explosionDamage(player, box, center, ANCHOR_POWER, destructibleTerrain.get(), assumeBestArmor.get(), null, anchor);
	}

	private Player bestTarget(List<Player> targets, Vec3 center) {
		Player best = null;
		float bestDamage = -1;
		for (Player player : targets) {
			float damage = targetDamage(player, center, BlockPos.containing(center));
			if (damage > bestDamage) {
				bestDamage = damage;
				best = player;
			}
		}
		return best;
	}

	private boolean shouldFacePlace(Player player) {
		if (player.getHealth() + player.getAbsorptionAmount() <= facePlaceHealth.get()) return true;
		for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			ItemStack armor = player.getItemBySlot(slot);
			if (armor.isEmpty() || !armor.isDamageableItem()) continue;
			if (100f * (armor.getMaxDamage() - armor.getDamageValue()) / armor.getMaxDamage() < armorPercent.get()) return true;
		}
		return false;
	}

	private static boolean holdsTotem(Player player) {
		return player.getOffhandItem().is(Items.TOTEM_OF_UNDYING) || player.getMainHandItem().is(Items.TOTEM_OF_UNDYING);
	}

	/** Where {@code player} will stand after Extrapolation ticks if they keep moving the same way. */
	private AABB predictBox(Player player) {
		AABB box = player.getBoundingBox();
		int ticks = extrapolation.getInt();
		if (ticks == 0) return box;
		Vec3 pos = player.position();
		Vec3 start = pos;
		double motionX = player.getDeltaMovement().x;
		double motionZ = player.getDeltaMovement().z;
		for (int i = 0; i < ticks; i++) {
			double edgeX = motionX > 0 ? 0.3 : -0.3;
			double edgeZ = motionZ > 0 ? 0.3 : -0.3;
			if (!mc.level.isEmptyBlock(BlockPos.containing(pos.add(motionX + edgeX, 0.1, motionZ + edgeZ)))
				|| !mc.level.isEmptyBlock(BlockPos.containing(pos.add(motionX + edgeX, 1, motionZ + edgeZ)))) break;
			pos = pos.add(motionX, 0, motionZ);
		}
		return box.move(pos.subtract(start));
	}

	// ---------------------------------------------------------------- rotation

	/** Whether the server rotation points into {@code box}; Packet mode turns to {@code aim} first when it doesn't. */
	private boolean canHit(AABB box, Vec3 aim) {
		if (rotate.is(RotateMode.OFF) || isAimedAt(box)) return true;
		if (!rotate.is(RotateMode.PACKET)) return false;
		FemboyWare.getRotations().sendPacketRotation(Rotation.between(mc.player.getEyePosition(), aim));
		return isAimedAt(box);
	}

	private boolean isAimedAt(AABB box) {
		Vec3 eyes = mc.player.getEyePosition();
		if (box.contains(eyes)) return true;
		Vec3 look = FemboyWare.getRotations().getServerRotation().toVector().scale(8);
		return box.clip(eyes, eyes.add(look)).isPresent();
	}

	private boolean isVisible(Vec3 eyes, Vec3 point, BlockPos pos) {
		BlockHitResult hit = mc.level.clip(new ClipContext(eyes, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
		return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos);
	}
}
