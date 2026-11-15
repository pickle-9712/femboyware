package dev.femboyware.module.combat;

import dev.femboyware.FemboyWare;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.lb.RotationSettings;
import dev.femboyware.mixin.InputAccessor;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.module.utility.FriendsModule;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.BlockUtil;
import dev.femboyware.util.FreeLook;
import dev.femboyware.util.InventoryUtil;
import dev.femboyware.util.Mc;
import dev.femboyware.util.InventoryUtil.SwitchMode;
import dev.femboyware.util.MoveFix;
import dev.femboyware.util.Pathfinder;
import dev.femboyware.util.PlaceUtil;
import dev.femboyware.util.RenderUtil;
import dev.femboyware.util.Rotation;
import dev.femboyware.util.TargetUtil;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * Boxes the target in with blocks: a ring at the feet, a ring at the head and a roof.
 *
 * <p>Legit mode aims at every block it places (with Real Look your head really turns there while the
 * camera stays free) and goes in stages: the blocks behind the target first, then a knockback hit
 * that pins them against that wall, then a cobweb or lava at their feet, then the rest of the walls
 * and the roof. Packet mode is the old way: several blocks a tick with look packets.
 */
public class TrapModule extends Module {
	private static final int ROTATION_PRIORITY = 70;

	public enum Mode { LEGIT, PACKET }
	public enum Fill { NONE, COBWEB, LAVA }
	public enum Stage { BACK, HIT, FILL, ESCAPE, WALLS, DONE }

	private final EnumSetting<Mode> mode = add(new EnumSetting<>("Mode", "Legit aims at every block (and can turn your real head); Packet places several a tick with look packets", Mode.LEGIT));
	private final NumberSetting targetRange = add(new NumberSetting("Target Range", "How far away targets can be", 6, 2, 10, 0.5));
	private final BoolSetting mobs = add(new BoolSetting("Mobs", "Trap mobs too, not just players", false));
	private final BoolSetting anyBlock = add(new BoolSetting("Any Block", "Use any solid block when out of obsidian (the toughest one you have)", true));
	private final BoolSetting feet = add(new BoolSetting("Feet", "Place the ring around the target's feet", true));
	private final BoolSetting roof = add(new BoolSetting("Roof", "Place a block above the target's head", true));
	private final BoolSetting backFirst = add(new BoolSetting("Back First", "Wall them in from behind before anything else", true));
	private final BoolSetting knockback = add(new BoolSetting("Knockback Hit", "After the back wall, a sprint hit with your best knockback weapon pins them against it", true));
	private final BoolSetting webFirst = add(new BoolSetting("Web First", "Web them before anything else: a cobweb at their feet, then on the side they're nearest to getting out of, then the walls go up round them", true));
	private final NumberSetting escapeWebs = add(new NumberSetting("Escape Webs", "How many of the open sides next to them get a web, nearest (or the one they're heading for) first", 1, 0, 4, 1)).visibleWhen(webFirst::get);
	private final EnumSetting<Fill> fill = add(new EnumSetting<>("Fill", "Put a cobweb or lava at their feet before closing the walls", Fill.NONE)).visibleWhen(() -> !webFirst.get());
	private final BoolSetting hitGap = add(new BoolSetting("Hit Gap", "While fighting with Legit Aura (or Aura), leave the head-height block on your side open: their feet stay boxed in so they can't get out, and your hits still land through the gap", true)).visibleWhen(() -> mode.is(Mode.LEGIT));
	private final BoolSetting jump = add(new BoolSetting("Jump", "Jump while trapping, to reach the high blocks and look the part", true)).visibleWhen(() -> mode.is(Mode.LEGIT));
	private final BoolSetting realLook = add(new BoolSetting("Real Look", "Really turn your head to each block (no silent rotation), with free look so you can still look anywhere", true)).visibleWhen(() -> mode.is(Mode.LEGIT));
	private final BoolSetting thirdPerson = add(new BoolSetting("Third Person", "Switch to third person while Real Look turns your head", true)).visibleWhen(() -> mode.is(Mode.LEGIT) && realLook.get());
	private final BoolSetting rushWebbed = add(new BoolSetting("Rush When Webbed", "In a fight, once they're stuck in a web the rest of the trap goes in straight away, before your next hit, instead of a few blocks between swings", true)).visibleWhen(() -> mode.is(Mode.LEGIT));
	private final BoolSetting autoOffhand = add(new BoolSetting("Auto Offhand", "Put your trap blocks in your offhand the way you would (select them, press swap hands, back to your sword), so you keep fighting with the sword while it places. Never swaps out a totem", true)).visibleWhen(() -> mode.is(Mode.LEGIT));
	private final NumberSetting flickSpeed = add(new NumberSetting("Flick Speed", "How fast your view flicks onto each block: turned every frame in whole mouse steps at your sensitivity, easing in as it arrives", 45, 4, 80, 1)).visibleWhen(() -> mode.is(Mode.LEGIT));
	private final RotationSettings rotations = new RotationSettings(s -> add(s), () -> mode.is(Mode.LEGIT), 45f);
	private final NumberSetting blocksPerTick = add(new NumberSetting("Blocks Per Tick", "Max placements per tick", 4, 1, 12, 1)).visibleWhen(() -> mode.is(Mode.PACKET));
	private final BoolSetting rotate = add(new BoolSetting("Rotate", "Send a rotation packet before placing", true)).visibleWhen(() -> mode.is(Mode.PACKET));
	private final BoolSetting strict = add(new BoolSetting("Strict", "Only click faces you can see", false)).visibleWhen(() -> mode.is(Mode.PACKET));
	private final EnumSetting<SwitchMode> switchMode = add(new EnumSetting<>("Switch", "How to switch to blocks (Legit always really switches, a tick before using it, and goes back after)", SwitchMode.SILENT)).visibleWhen(() -> mode.is(Mode.PACKET));
	private final BoolSetting render = add(new BoolSetting("Render", "Highlight trap positions", true));
	private final ColorSetting color = add(new ColorSetting("Color", "Highlight colour", 0x40FF5577)).visibleWhen(render::get);
	private final BoolSetting grim = addGrim();

	private Set<BlockPos> positions = Set.of();
	private LivingEntity target;
	private BlockPos trappedAt;
	private Stage stage = Stage.DONE;
	private boolean hitDone, filled, working;
	private int hitTicks, fillWait;
	/** This tick's escape web spots: a web has nothing solid, so someone half in one doesn't stop it. */
	private List<BlockPos> webSpots = List.of();
	/** Web First: escape webs placed round them where they are now. */
	private int escapesPlaced;
	/** What the legit placement is aiming at this tick. */
	private BlockPos aimingAt;
	/** Nothing left to place can be seen from here: a jump might show it, or it's too far and you walk closer. */
	private boolean needJump, needApproach, needBackOff;
	/** Legit: the slot you had before the trap took over, to go back to after; -1 when nothing to restore. */
	private int originalSlot = -1;
	private int lastSlot = -1, sinceSwitch;
	/**
	 * Fighting alongside Aura: ticks into the current turn Trap has taken from it (0 = Aura's turn).
	 * A turn starts just after a swing, while the cooldown refills, and ends after one block.
	 */
	private int turnTicks;
	private boolean restoreNext, turnEndedThisTick;
	/** Blocks this turn has put down. */
	private int turnPlaced;
	/** After a turn, ticks the fight gets to itself: time to flick back onto them and swing. */
	private int fightTicks;
	/** Where the knockback hit went in, so it's only done again once they've got well away. */
	private BlockPos hitAt;
	/** First tick of a turn: look only, so a click never lands in the same tick as the swing before it. */
	private boolean clickBlocked;
	/** Trapped but Legit Aura can't see them: walk round to stand at the gap. */
	private boolean toGap;
	/** The block this turn is going for: kept till it's in, so the look doesn't chase a new one every tick. */
	private BlockPos committed;
	/** Where the view is flicking to this tick (frame turning), or null. */
	private Vec3 aimPoint;
	private long lastFrame;
	private double yawCarry, pitchCarry;
	/** Auto Offhand: 1 = the block stack's selected, 2 = swap sent. */
	private int offhandStep;
	/** The head-height block left open to hit through, picked once on your side. */
	private BlockPos gap;
	/** Ticks the trap has gone without a block while blocks are missing, to walk round for a better view. */
	private int stuckTicks;
	/** Where to walk to see the blocks on the far side, or null. */
	private Vec3 walkTo;
	/** The way there, round the trap's own blocks and webs, or null to head straight for it. */
	private List<Vec3> path;
	private int pathIndex, walkTicks;
	/** A turn stops this many ticks before the sword's charged: the flick back onto them takes about that. */
	private static final int HAND_BACK_TICKS = 3;
	private static final int MAX_TURN_TICKS = 14, RUSH_TURN_TICKS = 30;
	/** After a turn the fight gets at least this long, or till the next swing. */
	private static final int FIGHT_TICKS = 8;
	/** How many ticks ahead to put the trap around someone on the move. */
	private static final int LEAD_TICKS = 2;

	public TrapModule() {
		super("Trap", "Traps your target: webs them and their nearest way out, then the back wall and the rest", Category.ATTACK);
	}

	@Override
	public String getInfo() {
		return target == null ? null : stage == Stage.DONE ? "Trapped" : EnumSetting.prettify(stage.name());
	}

	@Override
	protected void onDisable() {
		positions = Set.of();
		target = null;
		trappedAt = null;
		working = false;
		restoreSlot();
	}

	/**
	 * Legit mode is taking turns with Aura: while it traps, Aura doesn't aim or swing, so the two
	 * never fight over your look or do two things in one tick.
	 */
	public boolean isBusy() {
		return isEnabled() && mode.is(Mode.LEGIT) && (offhandStep > 0 || working && (!fighting() || turnTicks > 0 || turnEndedThisTick));
	}

	/** Something's in the way of placing there: anyone for a block, only you for an escape web. */
	private boolean blocked(BlockPos pos) {
		if (webSpots.contains(pos)) return mc.player.getBoundingBox().intersects(new AABB(pos));
		return BlockUtil.hasBlockingEntity(pos, false);
	}

	/** Web First always webs; otherwise whatever Fill says. */
	private boolean fillOn() {
		return webFirst.get() || !fill.is(Fill.NONE);
	}

	private boolean webFill() {
		return webFirst.get() || fill.is(Fill.COBWEB);
	}

	/**
	 * Web First: the open sides at their feet they could walk out through, the one they're closest to
	 * (or heading for) first, as many as Escape Webs still has to place. Empty once those are in.
	 */
	private List<BlockPos> escapePositions(AABB box) {
		List<BlockPos> open = new ArrayList<>();
		if (!webFirst.get() || escapesPlaced >= escapeWebs.getInt() || trappedAt == null) return open;
		if (!mc.player.getOffhandItem().is(Items.COBWEB) && InventoryUtil.findHotbar(stack -> stack.is(Items.COBWEB)) < 0) return open;
		for (Direction side : Direction.Plane.HORIZONTAL) {
			BlockPos pos = trappedAt.relative(side);
			// Somewhere to walk to: open at the feet and the head, and they'd have ground to land on
			if (!mc.level.getBlockState(pos).canBeReplaced() || !mc.level.getBlockState(pos.above()).canBeReplaced()) continue;
			// A web has nothing solid, so it goes in even where they're half standing; just not on you
			if (mc.player.getBoundingBox().intersects(new AABB(pos))) continue;
			open.add(pos);
		}
		Vec3 at = box.getCenter();
		open.sort((a, b) -> Double.compare(Vec3.atCenterOf(a).multiply(1, 0, 1).distanceToSqr(at.x, 0, at.z),
			Vec3.atCenterOf(b).multiply(1, 0, 1).distanceToSqr(at.x, 0, at.z)));
		// Only the nearest: it's the way out they're about to take, and a web elsewhere first would waste the time
		return open.isEmpty() ? open : new ArrayList<>(open.subList(0, 1));
	}

	/** Ticks till the held weapon is fully charged again. */
	private int ticksToCharged() {
		return (int) Math.ceil((1 - mc.player.getAttackStrengthScale(0.5f)) * mc.player.getCurrentItemAttackStrengthDelay());
	}

	/** Mid-fight with Rush When Webbed, and they're in a web: the turn runs till the trap's done. */
	private boolean rushing() {
		return rushWebbed.get() && target != null && mc.level.getBlockState(target.blockPosition()).is(Blocks.COBWEB);
	}

	/** Aura's on too: it does the fighting and Trap takes turns between its swings. */
	private boolean fighting() {
		return mode.is(Mode.LEGIT) && (FemboyWare.getModules().get(AuraModule.class).isEnabled() || legitFighting());
	}

	/**
	 * Legit Aura has someone: it turns your real camera, clicks only what's under the crosshair and
	 * walks you about, so Trap fits round it. No free look, its turns move your actual view, and it
	 * leaves your movement alone.
	 */
	private boolean legitFighting() {
		return mode.is(Mode.LEGIT) && FemboyWare.getModules().get(LegitAuraModule.class).isEnabled();
	}

	/** Who Legit Aura is locked on to; it lets go when walls hide them. */
	private LivingEntity legitTarget() {
		return FemboyWare.getModules().get(LegitAuraModule.class).getTarget();
	}

	/** Where they were the last few ticks, newest last, to read a steady speed from (one tick is jumpy). */
	private final java.util.ArrayDeque<Vec3> history = new java.util.ArrayDeque<>();
	private LivingEntity historyOf;

	/** How far they move a tick, sideways, averaged over the last few ticks. */
	private Vec3 velocity() {
		if (history.size() < 2) return new Vec3(target.getX() - target.xo, 0, target.getZ() - target.zo);
		Vec3 first = history.peekFirst(), last = history.peekLast();
		return last.subtract(first).multiply(1, 0, 1).scale(1.0 / (history.size() - 1));
	}

	private void trackHistory() {
		if (target != historyOf) {
			history.clear();
			historyOf = target;
		}
		if (target == null) return;
		history.addLast(target.position());
		while (history.size() > 4) history.removeFirst();
		// Changed direction hard: only the newest counts
		if (history.size() >= 3) {
			Vec3[] p = history.toArray(new Vec3[0]);
			Vec3 a = p[p.length - 2].subtract(p[p.length - 3]).multiply(1, 0, 1), b = p[p.length - 1].subtract(p[p.length - 2]).multiply(1, 0, 1);
			if (a.lengthSqr() > 1e-4 && b.lengthSqr() > 1e-4 && a.normalize().dot(b.normalize()) < 0.5) {
				Vec3 keep = history.peekLast();
				history.clear();
				history.addLast(p[p.length - 2]);
				history.addLast(keep);
			}
		}
	}

	/**
	 * Ticks till a click can land on a block round where they're headed: the flick there (more the
	 * further the view has to turn, at Flick Speed), a tick for the switch and one for the click to
	 * reach the server. Someone running is that much further on by then, so the block goes there.
	 */
	private int leadTicks() {
		Vec3 ahead = target.position().add(velocity().scale(LEAD_TICKS));
		Rotation wanted = Rotation.between(mc.player.getEyePosition(), ahead);
		float turn = new Rotation(mc.player.getYRot(), mc.player.getXRot()).distanceTo(wanted);
		// The flick closes most of the gap each frame: about ln(turn / 2 degrees) / speed seconds
		double flickSeconds = Math.log(Math.max(1.5, turn / 2.0)) / Math.max(4, flickSpeed.get());
		int flick = (int) Math.ceil(flickSeconds * 20);
		return Mth.clamp(flick + 2, LEAD_TICKS, 10);
	}

	private boolean moving() {
		return velocity().lengthSqr() > 0.06 * 0.06;
	}

	/** Their box a couple of ticks ahead when they're on the move: blocks go where they're about to be. */
	private AABB predictedBox() {
		if (!moving()) return target.getBoundingBox();
		Vec3 ahead = velocity().scale(mode.is(Mode.LEGIT) ? leadTicks() : LEAD_TICKS);
		AABB moved = target.getBoundingBox().move(ahead);
		// Not into a wall: they'd stop there
		for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(moved.minX, moved.minY + 0.2, moved.minZ), BlockPos.containing(moved.maxX - 1e-4, moved.minY + 0.2, moved.maxZ - 1e-4))) {
			if (!mc.level.getBlockState(pos).getCollisionShape(mc.level, pos).isEmpty()) return target.getBoundingBox();
		}
		return moved;
	}

	/** Ends Trap's turn: Aura gets the look back and, next tick, your weapon. */
	private void endTurn() {
		if (turnTicks == 0) return;
		turnTicks = 0;
		fightTicks = FIGHT_TICKS;
		restoreNext = true;
		// Still this tick: Aura swinging now would be two actions in one tick
		turnEndedThisTick = true;
	}

	/** Legit: back to the slot you had before the trap, like switching back to your sword. */
	private void restoreSlot() {
		if (originalSlot >= 0 && mc.player != null && mc.player.getInventory().getSelectedSlot() != originalSlot) InventoryUtil.select(originalSlot);
		originalSlot = -1;
	}

	/**
	 * Legit: really switch to the item and give it a tick in your hand before using it, like a
	 * player scrolling to it; true once it's ready.
	 */
	private boolean ready(Predicate<ItemStack> item) {
		if (item.test(mc.player.getOffhandItem()) && !item.test(mc.player.getMainHandItem())) {
			// A right click tries the main hand first: holding another block (the webs) would put that
			// down instead, so back to what you had (your sword) first, as you would
			if (!(mc.player.getMainHandItem().getItem() instanceof BlockItem)) return sinceSwitch >= 1;
			int back = -1;
			if (originalSlot >= 0 && !(mc.player.getInventory().getItem(originalSlot).getItem() instanceof BlockItem)) back = originalSlot;
			for (int i = 0; i < 9 && back < 0; i++) {
				if (!(mc.player.getInventory().getItem(i).getItem() instanceof BlockItem)) back = i;
			}
			if (back >= 0) {
				InventoryUtil.select(back);
				originalSlot = -1;
				return false;
			}
		}
		int slot = InventoryUtil.findHotbar(item);
		if (slot < 0) return false;
		return readySlot(slot);
	}

	private boolean readySlot(int slot) {
		if (mc.player.getInventory().getSelectedSlot() != slot) {
			if (originalSlot < 0) originalSlot = mc.player.getInventory().getSelectedSlot();
			InventoryUtil.select(slot);
			lastSlot = slot;
			sinceSwitch = 0;
			return false;
		}
		return sinceSwitch >= 1;
	}

	// ---------------------------------------------------------------- blocks

	private boolean isTrapBlock(ItemStack stack) {
		if (stack.is(Items.OBSIDIAN) || stack.is(Items.CRYING_OBSIDIAN)) return true;
		if (!anyBlock.get() || !(stack.getItem() instanceof BlockItem item)) return false;
		Block block = item.getBlock();
		if (block instanceof FallingBlock || block instanceof BaseEntityBlock) return false;
		return block.defaultBlockState().isCollisionShapeFullBlock(mc.level, BlockPos.ZERO);
	}

	/**
	 * Auto Offhand, one step a tick like a player: select the block stack, press swap hands, then
	 * (next tick) back to the sword. True while it's busy with that.
	 */
	private boolean autoOffhandTick() {
		if (offhandStep == 2) {
			offhandStep = 0;
			restoreSlot();
			return true;
		}
		if (!mode.is(Mode.LEGIT) || !autoOffhand.get() || Mc.screen() != null || mc.player.isUsingItem()) {
			offhandStep = 0;
			return false;
		}
		ItemStack offhand = mc.player.getOffhandItem();
		if (isTrapBlock(offhand) || offhand.is(Items.TOTEM_OF_UNDYING)) {
			offhandStep = 0;
			return false;
		}
		int slot = -1;
		for (int i = 0; i < 9; i++) {
			ItemStack stack = mc.player.getInventory().getItem(i);
			if (isTrapBlock(stack) && (slot < 0 || resistance(stack) > resistance(mc.player.getInventory().getItem(slot)))) slot = i;
		}
		if (slot < 0) {
			offhandStep = 0;
			return false;
		}
		if (mc.player.getInventory().getSelectedSlot() != slot) {
			readySlot(slot);
			offhandStep = 1;
			return true;
		}
		if (sinceSwitch < 1) {
			offhandStep = 1;
			return true;
		}
		// The swap-hands key: the server swaps them and tells us
		mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundPlayerActionPacket(
			net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
		offhandStep = 2;
		return true;
	}

	/** Fighting with the trap blocks in your offhand: the sword stays in your main hand the whole time. */
	private boolean offhandBlocks() {
		ItemStack best = bestBlock();
		return best != null && best == mc.player.getOffhandItem();
	}

	/** The toughest trap block you hold or have on the hotbar, or null. */
	private ItemStack bestBlock() {
		ItemStack best = null;
		if (isTrapBlock(mc.player.getOffhandItem())) best = mc.player.getOffhandItem();
		for (int i = 0; i < 9; i++) {
			ItemStack stack = mc.player.getInventory().getItem(i);
			if (!isTrapBlock(stack)) continue;
			if (best == null || resistance(stack) > resistance(best)) best = stack;
		}
		return best;
	}

	private static float resistance(ItemStack stack) {
		return ((BlockItem) stack.getItem()).getBlock().getExplosionResistance();
	}

	// ---------------------------------------------------------------- targets

	private LivingEntity findTarget() {
		LivingEntity fight = legitFighting() ? legitTarget() : null;
		if (fight != null && mc.player.distanceTo(fight) <= targetRange.get()) return fight;
		LivingEntity best = TargetUtil.closestPlayer(targetRange.get());
		if (!mobs.get()) return best;
		double bestDistance = best == null ? targetRange.get() * targetRange.get() : mc.player.distanceToSqr(best);
		for (var entity : mc.level.entitiesForRendering()) {
			if (!(entity instanceof Mob mob) || !mob.isAlive() || FriendsModule.protects(mob)) continue;
			double d = mc.player.distanceToSqr(mob);
			if (d < bestDistance) {
				bestDistance = d;
				best = mob;
			}
		}
		return best;
	}

	// ---------------------------------------------------------------- the tick

	@Override
	public void onTick() {
		working = false;
		turnEndedThisTick = false;
		aimingAt = null;
		needJump = needApproach = needBackOff = false;
		if (restoreNext) {
			restoreNext = false;
			restoreSlot();
		}
		int selected = mc.player.getInventory().getSelectedSlot();
		if (selected != lastSlot) {
			lastSlot = selected;
			sinceSwitch = 0;
		} else {
			sinceSwitch++;
		}
		aimPoint = null;
		if (autoOffhandTick()) return;
		LivingEntity found = findTarget();
		if (found != target) {
			target = found;
			resetStages();
		}
		trackHistory();
		if (target == null) {
			positions = Set.of();
			turnTicks = 0;
			restoreSlot();
			return;
		}
		AABB box = predictedBox();
		Vec3 feetAt = box.getBottomCenter();
		BlockPos feetPos = BlockPos.containing(feetAt.x, box.minY + 0.2, feetAt.z);
		// Knocked up or jumping: the trap stays around where they'll land
		for (int i = 0; i < 2 && !target.onGround() && mc.level.getBlockState(feetPos.below()).canBeReplaced(); i++) feetPos = feetPos.below();
		if (!feetPos.equals(trappedAt)) {
			// They moved: the trap carries on around where they are now. A new web goes down if they
			// walked out of the last one, and the knockback hit's done again once they're well away.
			trappedAt = feetPos;
			gap = null;
			if (!mc.level.getBlockState(feetPos).is(Blocks.COBWEB)) filled = false;
			fillWait = 0;
			escapesPlaced = 0;
			if (hitAt != null && hitAt.distSqr(feetPos) > 9) {
				hitDone = false;
				hitTicks = 0;
				hitAt = null;
			}
		}
		positions = withRoofSupport(trapPositions(box, box.minY, feet.get(), roof.get()));
		boolean fighting = fighting();
		if (fighting && hitGap.get()) positions = withHitGap(positions);
		List<BlockPos> missing = new ArrayList<>();
		for (BlockPos pos : positions) if (mc.level.getBlockState(pos).canBeReplaced()) missing.add(pos);
		List<BlockPos> back = backPositions(missing);
		// Web First: their feet, then the way out they're nearest to, once the feet web's in
		List<BlockPos> escape = webFirst.get() && (filled || mc.level.getBlockState(feetPos).is(Blocks.COBWEB)) ? escapePositions(box) : List.of();
		webSpots = escape;

		if (fighting) {
			// Mid-fight you go where the fight takes you, so it's whatever can be placed from here:
			// the webs first with Web First, the back wall if any of it is in reach, then the web,
			// then any other block in reach
			if (webFirst.get() && !filled && fillReachable(feetPos)) stage = Stage.FILL;
			// Only a stage with a block you can click now: one in reach but hidden (behind a web) would
			// hold the whole trap up while what can be placed waits
			else if (!escape.isEmpty() && reachNow(escape) == 2) stage = Stage.ESCAPE;
			else if (backFirst.get() && reachNow(back) == 2) stage = Stage.BACK;
			else if (fillOn() && !filled && fillReachable(feetPos)) stage = Stage.FILL;
			else stage = missing.isEmpty() ? Stage.DONE : Stage.WALLS;
		}
		// Web First: webbed before anything else, then the escape web; no knockback (it'd only pull them out)
		else if (webFirst.get() && !filled) stage = Stage.FILL;
		else if (!escape.isEmpty() && (mode.is(Mode.PACKET) || anyVisible(escape))) stage = Stage.ESCAPE;
		// The back wall first, while any of it can be clicked (a web in front of it can hide it)
		else if (backFirst.get() && !back.isEmpty() && (mode.is(Mode.PACKET) || anyVisible(back))) stage = Stage.BACK;
		else if (knockback.get() && !hitDone && !webFirst.get()) stage = Stage.HIT;
		else if (fillOn() && !filled) stage = Stage.FILL;
		else stage = missing.isEmpty() ? Stage.DONE : Stage.WALLS;

		if (stage == Stage.DONE) {
			walkTo = null;
			path = null;
			turnTicks = 0;
			restoreSlot();
			// Boxed in, but you're standing where the gap doesn't line up: look their way and walk round to it
			toGap = legitFighting() && hitGap.get() && gap != null && legitTarget() == null && mc.level.getBlockState(gap).canBeReplaced();
			if (toGap) aimPoint = target.getEyePosition();
			return;
		}
		toGap = false;
		working = true;
		if (fighting) {
			// Take a turn only just after a swing, while Aura's cooldown fills anyway, and give the
			// look back in time for its next hit
			boolean legit = legitFighting();
			if (turnTicks == 0) {
				// Blocks in the offhand: no switching, so a turn fits in more of the recharge
				boolean offhand = offhandBlocks() && stage != Stage.FILL && stage != Stage.ESCAPE;
				float window = offhand ? 0.6f : legit ? 0.35f : 0.45f;
				float charge = mc.player.getAttackStrengthScale(0.5f);
				// The fight's turn: till it's swung (the charge drops) or had long enough to try
				if (fightTicks > 0) {
					fightTicks--;
					if (charge > 0.3f) return;
					fightTicks = 0;
				}
				if (charge > window && auraCanHit()) return;
				// Nothing it could place from here (all out of reach, or hidden with no jump to show it):
				// no turn, and the fight carries on undisturbed
				int chance = stage == Stage.FILL ? (fillReachable(feetPos) ? 2 : 0) : reachNow(stage == Stage.BACK ? back : stage == Stage.ESCAPE ? escape : missing);
				if (chance == 0 || chance == 1 && !(jump.get() && mc.player.onGround())) {
					// Nothing to place from here. Once they're stuck (webbed or boxed at the feet) walk
					// round them to where the rest can be placed; Legit Aura keeps aiming and hitting
					if (walkTo == null && !missing.isEmpty() && isPinned(target) && ++stuckTicks > 6) {
						stuckTicks = 0;
						planWalk(missing);
					}
					return;
				}
				turnTicks = 1;
				turnPlaced = 0;
				clickBlocked = true;
			} else {
				clickBlocked = false;
				++turnTicks;
				if (rushing()) {
					// Stuck in a web: finish it now, the hits can wait a second
					if (turnTicks > RUSH_TURN_TICKS) {
						endTurn();
						return;
					}
				} else if (turnTicks > MAX_TURN_TICKS || turnTicks > 2 && ticksToCharged() <= HAND_BACK_TICKS) {
					// Give the view back in time to flick onto them for the charged hit
					endTurn();
					return;
				}
			}
		} else clickBlocked = false;
		if (mode.is(Mode.LEGIT) && realLook.get() && !legitFighting()) FreeLook.hold(thirdPerson.get());
		// Eating, blocking or in a menu: a player can't place or hit then, so neither does Legit
		if (mode.is(Mode.LEGIT) && (mc.player.isUsingItem() || Mc.screen() != null)) return;
		boolean placed = switch (stage) {
			case BACK -> placeAll(back);
			case HIT -> {
				hit();
				yield false;
			}
			case FILL -> fill(feetPos);
			case ESCAPE -> {
				boolean in = placeAll(escape, stack -> stack.is(Items.COBWEB));
				if (in) escapesPlaced++;
				yield in;
			}
			case WALLS -> placeAll(missing);
			default -> false;
		};
		// As many as fit in the turn, then back to the fight (the web on its own is a turn, it needs a switch)
		if (fighting && placed && (++turnPlaced >= 8 || stage == Stage.FILL && !rushing())) endTurn();
		if (placed) {
			stuckTicks = 0;
			walkTo = null;
			path = null;
		} else if (++stuckTicks > (aimingAt != null ? 30 : 8) && walkTo == null && !missing.isEmpty()) {
			// Nothing can be placed from here (or only glimpsed at the top of a jump, behind a web or
			// round your own blocks): find where most of what's left can be, and pathfind there
			planWalk(missing);
		}
	}

	/**
	 * What can be done about {@code list} from where you stand: 2 = a block can be clicked now,
	 * 1 = one's in reach but hidden (a jump might show it), 0 = nothing in reach.
	 */
	private int reachNow(List<BlockPos> list) {
		boolean jumpBefore = needJump, approachBefore = needApproach;
		int best = 0;
		for (BlockPos pos : list) {
			if (!mc.level.getBlockState(pos).canBeReplaced() || blocked(pos)) continue;
			needJump = false;
			if (visiblePlacement(pos) != null) {
				best = 2;
				break;
			}
			if (needJump) best = 1;
		}
		needJump = jumpBefore;
		needApproach = approachBefore;
		return best;
	}

	/** The web or lava can go in from here: you have it, and the ground under their feet can be clicked. */
	private boolean fillReachable(BlockPos feetPos) {
		Predicate<ItemStack> item = webFill() ? stack -> stack.is(Items.COBWEB) : stack -> stack.is(Items.LAVA_BUCKET);
		if (!item.test(mc.player.getOffhandItem()) && InventoryUtil.findHotbar(item) < 0) return false;
		if (webFill() && mc.level.getBlockState(feetPos).is(Blocks.COBWEB)) return false;
		boolean jumpBefore = needJump, approachBefore = needApproach;
		boolean ok = visiblePlacement(feetPos) != null;
		needJump = jumpBefore;
		needApproach = approachBefore;
		return ok;
	}

	/** Some of {@code list} can be clicked, or is only out of reach for now (walking in will do); false when it's all hidden. */
	private boolean anyVisible(List<BlockPos> list) {
		boolean jumpBefore = needJump, approachBefore = needApproach;
		boolean any = false;
		for (BlockPos pos : list) {
			if (blocked(pos)) continue;
			needApproach = false;
			if (visiblePlacement(pos) != null || needApproach) {
				any = true;
				break;
			}
		}
		needJump = jumpBefore;
		needApproach = approachBefore;
		return any;
	}

	/**
	 * Leaves out the head-height block nearest you that isn't placed yet: with their feet ring in
	 * they can't walk out through it, and you can still hit them through it.
	 */
	private Set<BlockPos> withHitGap(Set<BlockPos> trap) {
		// The head-height block nearest you stays open, so it ends up on your side; once it's the only
		// one left it stays put (Legit Aura doesn't circle someone pinned)
		if (gap != null && (!trap.contains(gap) || !mc.level.getBlockState(gap).canBeReplaced())) gap = null;
		int open = 0;
		for (BlockPos pos : trap) if (pos.getY() == trappedAt.getY() + 1 && mc.level.getBlockState(pos).canBeReplaced()) open++;
		if (gap == null || open > 1) {
			gap = null;
			Vec3 eyes = mc.player.getEyePosition();
			double best = Double.MAX_VALUE;
			for (BlockPos pos : trap) {
				if (pos.getY() != trappedAt.getY() + 1 || !mc.level.getBlockState(pos).canBeReplaced()) continue;
				double d = Vec3.atCenterOf(pos).distanceToSqr(eyes);
				if (d < best) {
					best = d;
					gap = pos;
				}
			}
		}
		if (gap == null) return trap;
		Set<BlockPos> result = new LinkedHashSet<>(trap);
		result.remove(gap);
		return result;
	}

	/** Aura has someone in reach to hit; when it doesn't, Trap needn't wait for a swing. */
	private boolean auraCanHit() {
		// Legit Aura keeps its target through Trap's turns, but drops it behind walls
		if (legitFighting()) return legitTarget() != null && mc.player.distanceTo(target) < 3.6;
		// Aura's own target goes quiet while Trap has the turn, so go by Trap's
		return mc.player.distanceTo(target) < 3.6;
	}

	/**
	 * A roof has nothing beside it to click on until something's there: a block on top of the head
	 * ring (on the far side) goes in just before it.
	 */
	private Set<BlockPos> withRoofSupport(Set<BlockPos> trap) {
		if (!roof.get()) return trap;
		Set<BlockPos> result = new LinkedHashSet<>();
		for (BlockPos pos : trap) {
			if (pos.getY() == trappedAt.getY() + 2 && mc.level.getBlockState(pos).canBeReplaced() && BlockUtil.getPlacement(pos, false) == null) {
				// On the far side: its face towards the roof then faces you, so you can click it over the walls
				Direction away = Direction.getApproximateNearest(target.getX() - mc.player.getX(), 0, target.getZ() - mc.player.getZ());
				result.add(pos.relative(away));
			}
			result.add(pos);
		}
		return result;
	}

	/**
	 * Whether {@code entity} is Trap's target and can't walk off: in a web, or with the feet ring
	 * all in. Legit Aura stops circling them then and fights from the gap.
	 */
	public boolean isPinned(LivingEntity entity) {
		if (!isEnabled() || entity == null || entity != target || trappedAt == null) return false;
		if (mc.level.getBlockState(trappedAt).is(Blocks.COBWEB)) return true;
		for (BlockPos pos : positions) {
			if (pos.getY() == trappedAt.getY() && mc.level.getBlockState(pos).canBeReplaced()) return false;
		}
		return !positions.isEmpty();
	}

	private void resetStages() {
		committed = null;
		gap = null;
		stuckTicks = 0;
		hitDone = false;
		filled = false;
		hitTicks = 0;
		fillWait = 0;
		escapesPlaced = 0;
		hitAt = null;
		turnTicks = 0;
	}

	/** The missing blocks on the far side of the target from you: the wall they get knocked into. */
	private List<BlockPos> backPositions(List<BlockPos> missing) {
		List<BlockPos> back = new ArrayList<>();
		Vec3 away = target.position().subtract(mc.player.position());
		away = new Vec3(away.x, 0, away.z);
		if (away.lengthSqr() < 1e-4) return back;
		away = away.normalize();
		for (BlockPos pos : missing) {
			if (pos.getY() > trappedAt.getY() + 1) continue;
			Vec3 offset = Vec3.atCenterOf(pos).subtract(target.position());
			if (new Vec3(offset.x, 0, offset.z).normalize().dot(away) > 0.3) back.add(pos);
		}
		return back;
	}

	private boolean placeAll(List<BlockPos> list) {
		ItemStack block = bestBlock();
		if (block == null) return false;
		return placeAll(list, stack -> stack.is(block.getItem()));
	}

	private boolean placeAll(List<BlockPos> list, Predicate<ItemStack> item) {
		if (mode.is(Mode.PACKET)) {
			int placed = 0;
			for (BlockPos pos : list) {
				if (placed >= blocksPerTick.getInt()) break;
				if (PlaceUtil.place(pos, item, switchMode.get(), rotate.get(), strict.get())) placed++;
			}
			return placed > 0;
		}
		// On the move: the side they're heading for first, to cut them off. Otherwise furthest
		// first, as the near blocks would stand between your eyes and the far ones.
		Vec3 eyes = mc.player.getEyePosition();
		Vec3 heading = moving() ? velocity().normalize() : null;
		Vec3 centre = target.position();
		List<BlockPos> ordered = new ArrayList<>(list);
		boolean lowFirst = fighting();
		ordered.sort((a, b) -> {
			// Mid-fight the blocks at their feet first: those are what stop them walking out
			if (lowFirst && a.getY() != b.getY()) return Integer.compare(a.getY(), b.getY());
			if (heading != null) {
				double da = heading.dot(Vec3.atCenterOf(a).subtract(centre).multiply(1, 0, 1).normalize());
				double db = heading.dot(Vec3.atCenterOf(b).subtract(centre).multiply(1, 0, 1).normalize());
				if (Math.abs(da - db) > 0.3) return Double.compare(db, da);
			}
			return Double.compare(Vec3.atCenterOf(b).distanceToSqr(eyes), Vec3.atCenterOf(a).distanceToSqr(eyes));
		});
		if (committed != null) {
			if (list.contains(committed) && mc.level.getBlockState(committed).canBeReplaced() && !blocked(committed)) {
				BlockHitResult planned = visiblePlacement(committed);
				if (planned != null) {
					aimingAt = committed;
					boolean placed = placeAimed(planned, item);
					if (placed) committed = null;
					return placed;
				}
			}
			committed = null;
		}
		Vec3 lookAt = null;
		for (BlockPos pos : ordered) {
			if (!mc.level.getBlockState(pos).canBeReplaced()) continue;
			if (blocked(pos)) {
				// Standing in it yourself: step back out of the way
				if (mc.player.getBoundingBox().intersects(new AABB(pos))) needBackOff = true;
				continue;
			}
			boolean jumpBefore = needJump;
			needJump = false;
			BlockHitResult planned = visiblePlacement(pos);
			boolean hiddenInReach = needJump;
			needJump |= jumpBefore;
			if (planned == null) {
				// Only a block in reach is worth looking at; nobody stares at one they can't get to
				if (lookAt == null && hiddenInReach) lookAt = Vec3.atCenterOf(pos);
				continue;
			}
			aimingAt = pos;
			boolean placed = placeAimed(planned, item);
			committed = placed ? null : pos;
			return placed;
		}
		// Nothing to click from here yet: look at the next block while jumping or walking to it
		if (lookAt != null) aim(lookAt);
		return false;
	}

	/**
	 * A click that places at {@code pos} on a spot of a neighbouring face your eyes can actually see
	 * and reach, or null; sets {@link #needJump} or {@link #needApproach} when there's none.
	 */
	private BlockHitResult visiblePlacement(BlockPos pos) {
		return visiblePlacement(pos, mc.player.getEyePosition());
	}

	private BlockHitResult visiblePlacement(BlockPos pos, Vec3 eyes) {
		// A little inside reach: the look gets rounded to mouse steps, which can push the ray past the edge
		double reach = mc.player.blockInteractionRange() - 0.3;
		boolean inReach = false, anyNeighbour = false;
		BlockHitResult best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Direction direction : Direction.values()) {
			BlockPos neighbour = pos.relative(direction);
			if (mc.level.getBlockState(neighbour).canBeReplaced()) continue;
			anyNeighbour = true;
			Direction face = direction.getOpposite();
			Vec3 center = BlockUtil.faceCenter(neighbour, face);
			// The centre first, then spots towards the edges
			for (double[] o : new double[][]{{0, 0}, {0.3, 0}, {-0.3, 0}, {0, 0.3}, {0, -0.3}, {0.3, 0.3}, {-0.3, -0.3}, {0.3, -0.3}, {-0.3, 0.3}}) {
				Vec3 point = center.add(offset(face, o[0], o[1]));
				double distance = eyes.distanceTo(point);
				if (distance > reach) continue;
				inReach = true;
				if (distance >= bestDistance) continue;
				Vec3 through = point.add(eyes.subtract(point).normalize().scale(-0.05));
				BlockHitResult hit = mc.level.clip(new ClipContext(eyes, through, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
				if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(neighbour) || hit.getDirection() != face) continue;
				best = new BlockHitResult(point, face, neighbour, false);
				bestDistance = distance;
			}
		}
		// Nothing to click on yet (a roof before its support) is no reason to move
		if (best == null && anyNeighbour) {
			if (inReach) needJump = true;
			else needApproach = true;
		}
		return best;
	}

	/**
	 * Picks the spot round them to stand on that the most missing blocks can be placed from (seen and
	 * in reach), nearest you on a tie, and the way to walk there round the trap's blocks and webs.
	 */
	private void planWalk(List<BlockPos> missing) {
		boolean jumpBefore = needJump, approachBefore = needApproach;
		BlockPos centre = trappedAt;
		BlockPos best = null;
		int bestCount = 0;
		double bestDistance = Double.MAX_VALUE;
		Vec3 me = mc.player.position();
		for (BlockPos spot : BlockPos.betweenClosed(centre.offset(-4, -1, -4), centre.offset(4, 1, 4))) {
			if (positions.contains(spot) || !standable(spot)) continue;
			Vec3 feet = Vec3.atBottomCenterOf(spot);
			if (feet.multiply(1, 0, 1).distanceTo(target.position().multiply(1, 0, 1)) < 1.3) continue;
			Vec3 eyes = feet.add(0, mc.player.getEyeHeight(), 0);
			int count = 0;
			for (BlockPos pos : missing) if (visiblePlacement(pos, eyes) != null) count++;
			double distance = feet.distanceTo(me);
			if (count > bestCount || count == bestCount && count > 0 && distance < bestDistance) {
				bestCount = count;
				bestDistance = distance;
				best = spot.immutable();
			}
		}
		needJump = jumpBefore;
		needApproach = approachBefore;
		walkTicks = 0;
		pathIndex = 0;
		if (best == null) {
			// Nowhere better found: round to the side the first missing block is on
			path = null;
			BlockPos want = missing.get(0);
			Vec3 out = Vec3.atCenterOf(want).subtract(target.position()).multiply(1, 0, 1);
			if (out.lengthSqr() > 1e-4) walkTo = Vec3.atBottomCenterOf(want).add(out.normalize().scale(2));
			return;
		}
		walkTo = Vec3.atBottomCenterOf(best);
		path = Pathfinder.find(mc.level, BlockPos.containing(me.add(0, 0.2, 0)), best, 3000);
	}

	/** Room to stand here: nothing solid (or webbed) at the feet or head, and ground under them. */
	private boolean standable(BlockPos pos) {
		net.minecraft.world.level.block.state.BlockState low = mc.level.getBlockState(pos), head = mc.level.getBlockState(pos.above());
		if (!low.getCollisionShape(mc.level, pos).isEmpty() || !head.getCollisionShape(mc.level, pos.above()).isEmpty()) return false;
		if (low.is(Blocks.COBWEB) || head.is(Blocks.COBWEB) || !low.getFluidState().isEmpty()) return false;
		return !mc.level.getBlockState(pos.below()).getCollisionShape(mc.level, pos.below()).isEmpty();
	}

	/** Stands you a step out from the gap, facing in, so the crosshair can find them through it. */
	private void walkToGap() {
		Input keys = mc.player.input.keyPresses;
		if (keys.forward() || keys.backward() || keys.left() || keys.right()) return;
		Vec3 out = Vec3.atCenterOf(gap).subtract(Vec3.atCenterOf(trappedAt)).multiply(1, 0, 1).normalize();
		Vec3 spot = Vec3.atBottomCenterOf(gap).add(out.scale(1.2));
		if (mc.player.position().multiply(1, 0, 1).distanceTo(spot.multiply(1, 0, 1)) < 0.35) return;
		int[] k = MoveFix.keysToward(mc.player.getYRot(), Rotation.between(mc.player.position(), around(spot)).yaw());
		mc.player.input.keyPresses = new Input(k[0] > 0, k[0] < 0, k[1] > 0, k[1] < 0, mc.player.horizontalCollision && mc.player.onGround(), false, false);
		((InputAccessor) mc.player.input).femboyware$setMovementVector(new Vec2(k[1], k[0]).normalized());
	}

	/** The next point on the way to {@code goal}: round the side of the trap if the straight line goes through it. */
	private Vec3 around(Vec3 goal) {
		Vec3 me = mc.player.position().multiply(1, 0, 1);
		Vec3 centre = target.position().multiply(1, 0, 1);
		Vec3 flatGoal = goal.multiply(1, 0, 1);
		Vec3 line = flatGoal.subtract(me);
		if (line.lengthSqr() < 1e-4) return goal;
		double t = Mth.clamp(centre.subtract(me).dot(line) / line.lengthSqr(), 0, 1);
		if (me.add(line.scale(t)).distanceTo(centre) > 1.9) return goal;
		Vec3 out = me.subtract(centre);
		if (out.lengthSqr() < 1e-4) return goal;
		out = out.normalize();
		Vec3 side = new Vec3(-out.z, 0, out.x);
		Vec3 a = centre.add(side.scale(2.4)).add(out.scale(0.5)), b = centre.subtract(side.scale(2.4)).add(out.scale(0.5));
		Vec3 waypoint = a.distanceTo(flatGoal) < b.distanceTo(flatGoal) ? a : b;
		return new Vec3(waypoint.x, goal.y, waypoint.z);
	}

	/** {@code a} and {@code b} along the two axes lying in {@code face}. */
	private static Vec3 offset(Direction face, double a, double b) {
		return switch (face.getAxis()) {
			case X -> new Vec3(0, a, b);
			case Y -> new Vec3(a, 0, b);
			case Z -> new Vec3(a, b, 0);
		};
	}

	// ---------------------------------------------------------------- legit placement

	private void aim(Vec3 point) {
		if (frameAim()) {
			aimPoint = point;
			return;
		}
		FemboyWare.getRotations().request(Rotation.between(mc.player.getEyePosition(), point), ROTATION_PRIORITY, rotations, false);
	}

	/** Legit with your real view or head turning (next to Legit Aura, or Real Look): turned frame by frame. */
	private boolean frameAim() {
		return mode.is(Mode.LEGIT) && (realLook.get() || legitFighting());
	}

	/**
	 * A quick flick onto the block, the way a hand moves a mouse: every frame, a share of what's
	 * left (fast at first, easing in at the end, a little uneven), in whole mouse steps at your
	 * sensitivity. The rotation then changes smoothly between ticks instead of jumping on each one.
	 */
	@Override
	public void onFrame() {
		long now = System.nanoTime();
		float seconds = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1e9f);
		lastFrame = now;
		if (aimPoint == null || seconds <= 0 || mc.player == null || Mc.screen() != null) {
			yawCarry = pitchCarry = 0;
			return;
		}
		float tickDelta = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		Rotation wanted = Rotation.between(mc.player.getEyePosition(tickDelta), aimPoint);
		double yawLeft = Mth.wrapDegrees(wanted.yaw() - mc.player.getYRot());
		double pitchLeft = wanted.pitch() - mc.player.getXRot();
		double follow = 1 - Math.exp(-flickSpeed.get() * seconds);
		double wobble = 0.85 + ThreadLocalRandom.current().nextDouble() * 0.3;
		double yawStep = yawLeft * follow * wobble + yawCarry;
		double pitchStep = pitchLeft * follow * wobble + pitchCarry;
		// Whole mouse counts, as Minecraft turns for a real mouse
		double sensitivity = mc.options.sensitivity().get() * 0.6 + 0.2;
		double perCount = sensitivity * sensitivity * sensitivity * 8;
		double degreesPerCount = perCount * 0.15;
		long yawCounts = Math.round(yawStep / degreesPerCount);
		long pitchCounts = Math.round(pitchStep / degreesPerCount);
		yawCarry = yawStep - yawCounts * degreesPerCount;
		pitchCarry = pitchStep - pitchCounts * degreesPerCount;
		if (yawCounts == 0 && pitchCounts == 0) return;
		// Straight onto the player, past free look's grab on the mouse, so Real Look turns the head
		float dYaw = (float) (yawCounts * degreesPerCount), dPitch = (float) (pitchCounts * degreesPerCount);
		float pitch = Mth.clamp(mc.player.getXRot() + dPitch, -90f, 90f);
		dPitch = pitch - mc.player.getXRot();
		mc.player.setYRot(mc.player.getYRot() + dYaw);
		mc.player.setXRot(pitch);
		mc.player.yRotO += dYaw;
		mc.player.xRotO += dPitch;
	}

	/**
	 * The rotation the server has: with frame turning, the one in the last movement packet, since
	 * the view keeps moving between ticks and a click is checked against what was sent.
	 */
	private Rotation sentRotation() {
		if (frameAim() && mc.player instanceof net.minecraft.client.player.LocalPlayer) {
			var sent = (dev.femboyware.mixin.ClientPlayerEntityAccessor) mc.player;
			return new Rotation(sent.femboyware$getLastYaw(), sent.femboyware$getLastPitch());
		}
		return FemboyWare.getRotations().getServerRotation();
	}

	/** Where the rotation the server has looks, or null. */
	private BlockHitResult seen() {
		Vec3 eyes = mc.player.getEyePosition();
		Vec3 end = eyes.add(sentRotation().toVector().scale(mc.player.blockInteractionRange()));
		BlockHitResult hit = mc.level.clip(new ClipContext(eyes, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
		return hit.getType() == HitResult.Type.BLOCK ? hit : null;
	}

	/** Clicks {@code planned} once the server rotation looks at it; turns there otherwise. */
	private boolean placeAimed(BlockHitResult planned, Predicate<ItemStack> item) {
		BlockHitResult seen = seen();
		// Any face your look is on that puts the block in the same spot will do, as a real click would
		BlockPos wanted = planned.getBlockPos().relative(planned.getDirection());
		if (seen == null || !seen.getBlockPos().relative(seen.getDirection()).equals(wanted)) {
			aim(planned.getLocation());
			return false;
		}
		if (clickBlocked || !ready(item)) return false;
		BlockHitResult click = new BlockHitResult(seen.getLocation(), seen.getDirection(), seen.getBlockPos(), false);
		InteractionHand hand = item.test(mc.player.getMainHandItem()) ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
		// A right click tries the main hand first and only then the offhand, as vanilla does
		if (hand == InteractionHand.OFF_HAND && !mc.player.getMainHandItem().isEmpty()
			&& mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, click).consumesAction()) {
			mc.player.swing(InteractionHand.MAIN_HAND);
			return true;
		}
		if (mc.gameMode.useItemOn(mc.player, hand, click).consumesAction()) mc.player.swing(hand);
		return true;
	}

	// ---------------------------------------------------------------- knockback hit

	private int bestKnockbackSlot() {
		int best = mc.player.getInventory().getSelectedSlot();
		int bestLevel = InventoryUtil.getEnchantmentLevel(mc.player.getInventory().getItem(best), Enchantments.KNOCKBACK);
		for (int i = 0; i < 9; i++) {
			int level = InventoryUtil.getEnchantmentLevel(mc.player.getInventory().getItem(i), Enchantments.KNOCKBACK);
			if (level > bestLevel) {
				bestLevel = level;
				best = i;
			}
		}
		return best;
	}

	private void hit() {
		if (++hitTicks > 60) {
			// Couldn't get the hit in: get on with the trap
			hitDone = true;
			return;
		}
		AABB box = target.getBoundingBox();
		Vec3 eyes = mc.player.getEyePosition();
		aim(BlockUtil.closestPoint(box.deflate(0.1), eyes));
		Vec3 end = eyes.add(sentRotation().toVector().scale(3.0));
		var point = box.clip(eyes, end);
		boolean onTarget = box.contains(eyes) || point.isPresent()
			// Not through a wall: the server checks that
			&& mc.level.clip(new ClipContext(eyes, point.get(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player)).getType() == HitResult.Type.MISS;
		int slot = bestKnockbackSlot();
		boolean legit = mode.is(Mode.LEGIT);
		// Scroll to the knockback weapon while running at them
		if (legit && !readySlot(slot)) return;
		// A sprint hit at full charge gives the extra knockback
		if (!onTarget || mc.player.getAttackStrengthScale(0.5f) < 0.95f || !mc.player.isSprinting() && hitTicks < 30) return;
		LivingEntity victim = target;
		Runnable attack = () -> {
			mc.gameMode.attack(mc.player, victim);
			mc.player.swing(InteractionHand.MAIN_HAND);
		};
		if (legit) attack.run();
		else InventoryUtil.withSlot(slot, SwitchMode.SILENT, attack);
		hitDone = true;
		hitAt = trappedAt;
	}

	// ---------------------------------------------------------------- fill

	/** The web spot picked in a runner's path: kept till it's in or they've gone past it, so the flick doesn't chase. */
	private BlockPos webAhead;
	private int webAheadTicks;

	private boolean webAheadValid(BlockPos spot) {
		if (spot == null || ++webAheadTicks > 30) return false;
		if (!mc.level.getBlockState(spot).canBeReplaced() || mc.level.getBlockState(spot.below()).canBeReplaced()) return false;
		Vec3 toSpot = Vec3.atBottomCenterOf(spot).subtract(target.position()).multiply(1, 0, 1);
		// Behind them now (they turned, or ran past it): pick again
		return toSpot.length() < 5 && toSpot.dot(velocity().normalize()) > -0.3;
	}

	private boolean fill(BlockPos feetPos) {
		if (mode.is(Mode.LEGIT) && webFill() && moving()) {
			if (webAheadValid(webAhead)) feetPos = webAhead;
			else {
				webAhead = feetPos;
				webAheadTicks = 0;
			}
		} else webAhead = null;
		boolean done = webFill() ? mc.level.getBlockState(feetPos).is(Blocks.COBWEB)
			: mc.level.getFluidState(feetPos).is(net.minecraft.tags.FluidTags.LAVA);
		Predicate<ItemStack> item = webFill() ? stack -> stack.is(Items.COBWEB) : stack -> stack.is(Items.LAVA_BUCKET);
		boolean have = item.test(mc.player.getOffhandItem()) || InventoryUtil.findHotbar(item) >= 0;
		if (done || !have) {
			filled = true;
			return false;
		}
		// Standing still after the knockback: wait for them to land, or the web holds them up in the
		// air with their head where the roof goes. Someone on the move gets it straight away.
		BlockPos under = feetPos.below();
		boolean standing = target.onGround() || target.getY() - feetPos.getY() < 0.05 && !mc.level.getBlockState(under).getCollisionShape(mc.level, under).isEmpty();
		if (!moving() && !standing && ++fillWait < 40) {
			if (mode.is(Mode.LEGIT)) aim(new Vec3(feetPos.getX() + 0.5, feetPos.getY(), feetPos.getZ() + 0.5));
			return false;
		}
		BlockPos ground = feetPos.below();
		if (mc.level.getBlockState(ground).canBeReplaced()) {
			// Nothing under them to click on
			filled = true;
			return false;
		}
		BlockHitResult planned = new BlockHitResult(new Vec3(feetPos.getX() + 0.5, feetPos.getY(), feetPos.getZ() + 0.5), Direction.UP, ground, false);
		if (webFill()) {
			aimingAt = feetPos;
			if (mode.is(Mode.PACKET)) {
				PlaceUtil.placeInside(feetPos, item, switchMode.get(), rotate.get(), strict.get());
				return true;
			}
			BlockHitResult visible = visiblePlacement(feetPos);
			if (visible == null) {
				// Can't click that one: try again from what's ahead of them next tick
				webAhead = null;
				return false;
			}
			boolean placed = placeAimed(visible, item);
			if (placed) {
				filled = true;
				webAhead = null;
			}
			return placed;
		}
		// Lava: the bucket pours where your look meets the ground under their feet
		BlockHitResult seen = seen();
		if (seen == null || !seen.getBlockPos().equals(ground) || seen.getDirection() != Direction.UP) {
			aim(planned.getLocation());
			if (mode.is(Mode.LEGIT)) return false;
		}
		Rotation server = FemboyWare.getRotations().getServerRotation();
		Rotation pour = mode.is(Mode.PACKET) ? Rotation.between(mc.player.getEyePosition(), planned.getLocation()) : server;
		InteractionHand hand = item.test(mc.player.getOffhandItem()) ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
		Runnable action = () -> {
			// The bucket reads the look from the player itself
			float yaw = mc.player.getYRot(), pitch = mc.player.getXRot();
			mc.player.setYRot(pour.yaw());
			mc.player.setXRot(pour.pitch());
			if (mc.gameMode.useItem(mc.player, hand).consumesAction()) mc.player.swing(hand);
			mc.player.setYRot(yaw);
			mc.player.setXRot(pitch);
		};
		if (mode.is(Mode.LEGIT)) {
			if (clickBlocked || !ready(item)) return false;
			InteractionHand held = item.test(mc.player.getMainHandItem()) ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
			if (mc.gameMode.useItem(mc.player, held).consumesAction()) mc.player.swing(held);
		} else if (hand == InteractionHand.OFF_HAND) action.run();
		else InventoryUtil.withSlot(InventoryUtil.findHotbar(item), switchMode.get(), action);
		filled = true;
		return true;
	}

	// ---------------------------------------------------------------- movement

	@Override
	public void onInput() {
		if (toGap && target != null && gap != null) {
			walkToGap();
			return;
		}
		if (!working || target == null || !mode.is(Mode.LEGIT)) return;
		Input keys = mc.player.input.keyPresses;
		// Jump when a jump would show you a block you can't see from the ground
		boolean doJump = jump.get() && mc.player.onGround() && needJump && aimingAt == null;
		// Legit Aura is chasing and strafing for you; a jump on Trap's turn still helps it see the high blocks
		if (legitFighting() && legitTarget() != null && !(walkTo != null && isPinned(target) && followWalk(keys))) {
			if (needBackOff && turnTicks > 0 && aimingAt == null) {
				// Standing in a spot the trap needs: a step back, then Legit Aura carries on
				float frame = mc.player.getYRot();
				int[] k = MoveFix.keysToward(frame, Rotation.between(target.position(), mc.player.position()).yaw());
				mc.player.input.keyPresses = new Input(k[0] > 0, k[0] < 0, k[1] > 0, k[1] < 0, false, false, false);
				((InputAccessor) mc.player.input).femboyware$setMovementVector(new Vec2(k[1], k[0]).normalized());
				return;
			}
			if (doJump && turnTicks > 0) mc.player.input.keyPresses = new Input(keys.forward(), keys.backward(), keys.left(), keys.right(), true, keys.shift(), keys.sprint());
			return;
		}
		if (followWalk(keys)) return;
		// Hands off WASD and it walks in, steps back or runs at them as usual
		if (fighting() && (keys.forward() || keys.backward() || keys.left() || keys.right())) {
			// Mid-fight your own WASD stays yours (strafing, chasing); only the jump helps out
			if (doJump && turnTicks > 0) mc.player.input.keyPresses = new Input(keys.forward(), keys.backward(), keys.left(), keys.right(), true, keys.shift(), keys.sprint());
			return;
		}
		walkRest(keys, doJump);
	}

	/** Walks the planned way round them, while your own WASD is off it. True while it's walking. */
	private boolean followWalk(Input keys) {
		if (walkTo != null && !(keys.forward() || keys.backward() || keys.left() || keys.right())) {
			Vec3 me = mc.player.position();
			if (me.multiply(1, 0, 1).distanceTo(walkTo.multiply(1, 0, 1)) < 0.4 || ++walkTicks > 120) {
				// There (or it's taking too long: plan again from here)
				walkTo = null;
				path = null;
				stuckTicks = 0;
				return false;
			}
			Vec3 next = walkTo;
			if (path != null) {
				while (pathIndex < path.size() && Math.hypot(path.get(pathIndex).x - me.x, path.get(pathIndex).z - me.z) < 0.45) pathIndex++;
				if (pathIndex < path.size()) next = path.get(pathIndex);
			} else next = around(walkTo);
			float frame = FreeLook.active() ? FreeLook.yaw() : mc.player.getYRot();
			int[] k = MoveFix.keysToward(frame, Rotation.between(me, next).yaw());
			mc.player.input.keyPresses = new Input(k[0] > 0, k[0] < 0, k[1] > 0, k[1] < 0, mc.player.horizontalCollision && mc.player.onGround(), false, k[0] > 0);
			((InputAccessor) mc.player.input).femboyware$setMovementVector(new Vec2(k[1], k[0]).normalized());
			return true;
		}
		return false;
	}

	/** Backing off, running in or jumping, with your hands off WASD. */
	private void walkRest(Input keys, boolean doJump) {
		if (needBackOff && aimingAt == null && stage != Stage.HIT) {
			// Your own body is in a spot the trap needs: back away from them
			float frame = FreeLook.active() ? FreeLook.yaw() : mc.player.getYRot();
			float away = Rotation.between(target.position(), mc.player.position()).yaw();
			int[] k = MoveFix.keysToward(frame, away);
			mc.player.input.keyPresses = new Input(k[0] > 0, k[0] < 0, k[1] > 0, k[1] < 0, false, false, false);
			((InputAccessor) mc.player.input).femboyware$setMovementVector(new Vec2(k[1], k[0]).normalized());
			return;
		}
		if (stage == Stage.HIT || needApproach && aimingAt == null) {
			// Run at them sprinting, for the sprint knockback
			float frame = FreeLook.active() ? FreeLook.yaw() : mc.player.getYRot();
			float towards = Rotation.between(mc.player.position(), target.position()).yaw();
			int[] k = MoveFix.keysToward(frame, towards);
			// Close enough to place already: don't walk into them, just jump if that helps
			if (stage != Stage.HIT && mc.player.distanceTo(target) < 2.0) {
				if (doJump) mc.player.input.keyPresses = new Input(keys.forward(), keys.backward(), keys.left(), keys.right(), true, keys.shift(), keys.sprint());
				return;
			}
			mc.player.input.keyPresses = new Input(k[0] > 0, k[0] < 0, k[1] > 0, k[1] < 0, doJump || keys.jump(), false, true);
			((InputAccessor) mc.player.input).femboyware$setMovementVector(new Vec2(k[1], k[0]).normalized());
			return;
		}
		if (doJump) mc.player.input.keyPresses = new Input(keys.forward(), keys.backward(), keys.left(), keys.right(), true, keys.shift(), keys.sprint());
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (!render.get()) return;
		for (BlockPos pos : positions) {
			if (!mc.level.getBlockState(pos).canBeReplaced()) continue;
			RenderUtil.drawBox(event, new AABB(pos), color.get(), color.withAlpha(255), 1.5f);
		}
	}

	/** Feet ring, head ring and roof around every block column {@code entity} stands in, bottom up. */
	public static Set<BlockPos> trapPositions(LivingEntity entity, boolean includeFeet, boolean includeRoof) {
		return trapPositions(entity.getBoundingBox(), entity.getY(), includeFeet, includeRoof);
	}

	/** {@link #trapPositions(LivingEntity, boolean, boolean)} for a box standing at {@code feetY}. */
	public static Set<BlockPos> trapPositions(AABB box, double feetY, boolean includeFeet, boolean includeRoof) {
		int y = Mth.floor(feetY + 0.2);
		Set<BlockPos> columns = new LinkedHashSet<>();
		for (int x = Mth.floor(box.minX); x <= Mth.floor(box.maxX - 1e-4); x++) {
			for (int z = Mth.floor(box.minZ); z <= Mth.floor(box.maxZ - 1e-4); z++) {
				columns.add(new BlockPos(x, y, z));
			}
		}
		Set<BlockPos> result = new LinkedHashSet<>();
		for (int level = includeFeet ? 0 : 1; level <= 1; level++) {
			for (BlockPos column : columns) {
				for (Direction direction : Direction.Plane.HORIZONTAL) {
					BlockPos side = column.above(level).relative(direction);
					if (!columns.contains(side.below(level))) result.add(side);
				}
			}
		}
		if (includeRoof) {
			for (BlockPos column : columns) result.add(column.above(2));
		}
		return result;
	}
}
