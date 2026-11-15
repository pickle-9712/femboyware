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
 * Modified for femboyware: ported from Kotlin (features/module/modules/world/scaffold: ModuleScaffold,
 * ScaffoldBlockItemSelection, the Normal, Expand, GodBridge and Breezily techniques, the Motion,
 * Pulldown, Karhu and Vulcan towers, and the Ledge, Eagle, Down, Telly, Ceiling, HeadHitter,
 * AutoBlock and SameY features) to Java on femboyware's module, setting and rotation systems.
 * Movement prediction uses your current position instead of LiquidBounce's player simulation,
 * and the Hypixel tower, Blink, Strafe, Acceleration and simulated clicks are not ported.
 * femboyware additions (marked "femboyware:"): God Bridge picks whichever diagonal lands on the
 * block, aims straight at it when neither does, stays level unless you hold jump, and defaults
 * its ledge action to Sneak.
 */
package dev.femboyware.module.movement;

import dev.femboyware.FemboyWare;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.lb.Geometry;
import dev.femboyware.lb.RotationSettings;
import dev.femboyware.lb.ScaffoldMovementPlanner;
import dev.femboyware.lb.TargetFinding;
import dev.femboyware.lb.TargetFinding.AimMode;
import dev.femboyware.lb.TargetFinding.PlacementTarget;
import dev.femboyware.lb.TargetFinding.PlayerLocation;
import dev.femboyware.mixin.InputAccessor;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.ColorUtil;
import dev.femboyware.util.InventoryUtil;
import dev.femboyware.util.InventoryUtil.SwitchMode;
import dev.femboyware.util.RenderUtil;
import dev.femboyware.util.Rotation;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
//? if >=26.3 {
/*
*///?} else {
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
//?}
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Places blocks under you as you walk, with LiquidBounce's target finding: it works out which face
 * of which neighbouring block to click and where on it, then aims there through the rotation
 * manager and places once the server's rotation actually hits it.
 */
public class ScaffoldModule extends Module {
	private static final int ROTATION_PRIORITY = 80;

	public enum Page { GENERAL, ROTATIONS, TECHNIQUE, TOWER, MOVEMENT, RENDER }
	public enum Technique { NORMAL, EXPAND, GOD_BRIDGE, BREEZILY }
	public enum SameY { OFF, ON, JUMP_KEY, FALLING }
	public enum Tower { NONE, HYPIXEL, MOTION, PULLDOWN, KARHU, VULCAN }
	public enum Timing { NORMAL, ON_TICK, ON_TICK_SNAP }
	public enum Swing { NORMAL, PACKET, NONE }
	public enum LedgeMode { JUMP, SNEAK, STOP_INPUT, BACKWARDS }
	public enum TellyReset { RESET, REVERSE }

	private final EnumSetting<Page> page = add(new EnumSetting<>("Page", "Settings page", Page.GENERAL));
	private final dev.femboyware.setting.ButtonSetting hypixelPreset = add(new dev.femboyware.setting.ButtonSetting("Hypixel Preset",
		"Sets everything here to LiquidBounce's Hypixel config (Normal technique, faces you can see, 120 degree turns, no safe walk, stays on your blocks, Real Look) and turns on Move Fix and Legit Sprint", this::applyHypixelPreset));

	// General
	private final NumberSetting delayMin = add(new NumberSetting("Delay Min", "Fewest ticks to wait after a placement", 0, 0, 40, 1)).visibleWhen(() -> page.is(Page.GENERAL));
	private final NumberSetting delayMax = add(new NumberSetting("Delay Max", "Most ticks to wait after a placement", 0, 0, 40, 1)).visibleWhen(() -> page.is(Page.GENERAL));
	private final NumberSetting minDist = add(new NumberSetting("Min Dist", "Don't click side faces closer than this to your eyes (sideways)", 0, 0, 0.25, 0.01)).visibleWhen(() -> page.is(Page.GENERAL));
	private final NumberSetting timer = add(new NumberSetting("Timer", "Game speed while scaffolding", 1, 0.1, 10, 0.05)).visibleWhen(() -> page.is(Page.GENERAL));
	private final EnumSetting<SameY> sameY = add(new EnumSetting<>("Same Y", "Keep placing at the height you started on, so you can jump while bridging", SameY.OFF)).visibleWhen(() -> page.is(Page.GENERAL));
	private final BoolSetting autoBlock = add(new BoolSetting("Auto Block", "Pick the best blocks from your hotbar", true)).visibleWhen(() -> page.is(Page.GENERAL));
	private final EnumSetting<SwitchMode> switchMode = add(new EnumSetting<>("Switch", "How to switch to blocks. Normal stays on your blocks while you build, as LiquidBounce does; Silent flips to them and back for every block, which Hypixel flags", SwitchMode.NORMAL)).visibleWhen(() -> page.is(Page.GENERAL) && autoBlock.get());
	private final NumberSetting keepBelow = add(new NumberSetting("Save Last", "Avoid stacks with this many or fewer blocks while others are left", 5, 0, 64, 1)).visibleWhen(() -> page.is(Page.GENERAL) && autoBlock.get());
	private final BoolSetting grim = addGrim(() -> page.is(Page.GENERAL));
	private final EnumSetting<Swing> swing = add(new EnumSetting<>("Swing", "How to swing when placing", Swing.PACKET)).visibleWhen(() -> page.is(Page.GENERAL));
	private final BoolSetting autoSpeed = add(new BoolSetting("Auto Speed", "Turn Speed on while scaffolding", false)).visibleWhen(() -> page.is(Page.GENERAL));

	// Rotations
	private final BoolSetting rotate = add(new BoolSetting("Rotate", "Aim at each placement (needed on most servers)", true)).visibleWhen(() -> page.is(Page.ROTATIONS));
	private final RotationSettings rotations = new RotationSettings(s -> add(s), () -> page.is(Page.ROTATIONS) && rotate.get(), 120f);
	private final BoolSetting realLook = add(new BoolSetting("Real Look", "Really turn your head to where you build (no silent rotation), with free look so you can still look anywhere", false)).visibleWhen(() -> page.is(Page.ROTATIONS) && rotate.get());
	private final BoolSetting realLookThirdPerson = add(new BoolSetting("Real Look Third Person", "Switch to third person while Real Look turns your head", true)).visibleWhen(() -> page.is(Page.ROTATIONS) && rotate.get() && realLook.get());
	private final EnumSetting<Timing> timing = add(new EnumSetting<>("Rotation Timing", "Normal turns before placing; On Tick turns on the tick it places; Snap also keeps it", Timing.NORMAL)).visibleWhen(() -> page.is(Page.ROTATIONS) && rotate.get());
	private final EnumSetting<AimMode> aimMode = add(new EnumSetting<>("Aim Mode", "Which point on the face to aim at (Normal technique)", AimMode.STABILIZED)).visibleWhen(() -> page.is(Page.ROTATIONS));
	private final BoolSetting requiresSight = add(new BoolSetting("Requires Sight", "Only aim at faces you can see (Normal technique); Hypixel checks you could see what you clicked", true)).visibleWhen(() -> page.is(Page.ROTATIONS));

	// Technique
	private final EnumSetting<Technique> technique = add(new EnumSetting<>("Technique", "How to bridge", Technique.NORMAL)).visibleWhen(() -> page.is(Page.TECHNIQUE));
	private final NumberSetting expandLength = add(new NumberSetting("Expand Length", "How far ahead to place", 4, 1, 10, 1)).visibleWhen(() -> page.is(Page.TECHNIQUE) && technique.is(Technique.EXPAND));
	private final EnumSetting<LedgeMode> godBridgeMode = add(new EnumSetting<>("God Bridge Ledge", "What to do at an edge it can't place on yet (Jump is faster but needs you near the side of the block)", LedgeMode.SNEAK)).visibleWhen(() -> page.is(Page.TECHNIQUE) && technique.is(Technique.GOD_BRIDGE));
	private final NumberSetting forceSneakBelow = add(new NumberSetting("Force Sneak Below", "Sneak at edges instead when you have fewer blocks than this", 3, 0, 10, 1)).visibleWhen(() -> page.is(Page.TECHNIQUE) && technique.is(Technique.GOD_BRIDGE));
	private final NumberSetting edgeMin = add(new NumberSetting("Edge Distance Min", "Breezily: closest to the edge before strafing back", 0.45, 0.25, 0.5, 0.01)).visibleWhen(() -> page.is(Page.TECHNIQUE) && technique.is(Technique.BREEZILY));
	private final NumberSetting edgeMax = add(new NumberSetting("Edge Distance Max", "Breezily: furthest", 0.5, 0.25, 0.5, 0.01)).visibleWhen(() -> page.is(Page.TECHNIQUE) && technique.is(Technique.BREEZILY));
	private final BoolSetting eagle = add(new BoolSetting("Eagle", "Sneak at edges (Normal technique)", false)).visibleWhen(() -> page.is(Page.TECHNIQUE) && technique.is(Technique.NORMAL));
	private final NumberSetting eagleBlocks = add(new NumberSetting("Blocks To Eagle", "Sneak only every this many placements (0 = every edge)", 0, 0, 10, 1)).visibleWhen(() -> page.is(Page.TECHNIQUE) && eagle.get());
	private final NumberSetting eagleEdge = add(new NumberSetting("Eagle Edge", "How close to the edge before sneaking", 0.03, 0.01, 1.3, 0.01)).visibleWhen(() -> page.is(Page.TECHNIQUE) && eagle.get());
	private final BoolSetting eagleGround = add(new BoolSetting("Eagle Only On Ground", "Only sneak while on the ground", true)).visibleWhen(() -> page.is(Page.TECHNIQUE) && eagle.get());
	private final BoolSetting telly = add(new BoolSetting("Telly", "Jump-bridge: look ahead while jumping, only aim down to place (Normal technique)", false)).visibleWhen(() -> page.is(Page.TECHNIQUE) && technique.is(Technique.NORMAL));
	private final EnumSetting<TellyReset> tellyReset = add(new EnumSetting<>("Telly Reset", "Reset hands the aim back; Reverse looks backwards and down", TellyReset.RESET)).visibleWhen(() -> page.is(Page.TECHNIQUE) && telly.get());
	private final NumberSetting tellyStraight = add(new NumberSetting("Telly Straight", "Air ticks to keep looking straight", 0, 0, 5, 1)).visibleWhen(() -> page.is(Page.TECHNIQUE) && telly.get());
	private final NumberSetting tellyJump = add(new NumberSetting("Telly Jump", "Ground ticks before jumping again", 0, 0, 10, 1)).visibleWhen(() -> page.is(Page.TECHNIQUE) && telly.get());
	private final BoolSetting tellyAimOnTower = add(new BoolSetting("Telly Aim On Tower", "Aim normally while towering", true)).visibleWhen(() -> page.is(Page.TECHNIQUE) && telly.get());
	private final BoolSetting down = add(new BoolSetting("Down", "Hold sneak to scaffold downwards (Normal technique)", false)).visibleWhen(() -> page.is(Page.TECHNIQUE) && technique.is(Technique.NORMAL));
	private final BoolSetting ceiling = add(new BoolSetting("Ceiling", "Build a roof over you (Normal technique)", false)).visibleWhen(() -> page.is(Page.TECHNIQUE) && technique.is(Technique.NORMAL));
	private final BoolSetting headHitter = add(new BoolSetting("Head Hitter", "Keep jumping under a low ceiling for speed (Normal technique)", false)).visibleWhen(() -> page.is(Page.TECHNIQUE) && technique.is(Technique.NORMAL));

	// Tower
	private final EnumSetting<Tower> tower = add(new EnumSetting<>("Tower", "How to climb while holding jump. Hypixel never touches your motion (plain vanilla jumps, so nothing to flag): it places under you at the top of each jump, and walking while you hold jump it builds a staircase under where each jump lands, braking with a real key press if a step isn't in yet, so you never fall", Tower.HYPIXEL)).visibleWhen(() -> page.is(Page.TOWER));
	private final NumberSetting towerMotion = add(new NumberSetting("Tower Motion", "Upward speed for each block", 0.42, 0, 1, 0.01)).visibleWhen(() -> page.is(Page.TOWER) && tower.is(Tower.MOTION));
	private final NumberSetting towerTrigger = add(new NumberSetting("Tower Trigger Height", "How high to rise before snapping onto the block", 0.78, 0.76, 1, 0.01)).visibleWhen(() -> page.is(Page.TOWER) && tower.is(Tower.MOTION));
	private final NumberSetting towerSlow = add(new NumberSetting("Tower Slow", "Horizontal speed kept while towering", 1, 0, 3, 0.05)).visibleWhen(() -> page.is(Page.TOWER) && tower.is(Tower.MOTION));
	private final NumberSetting pulldownTrigger = add(new NumberSetting("Pulldown Trigger", "Upward speed below which you're pulled down", 0.1, 0, 0.2, 0.01)).visibleWhen(() -> page.is(Page.TOWER) && (tower.is(Tower.PULLDOWN) || tower.is(Tower.KARHU)));
	private final NumberSetting karhuTimer = add(new NumberSetting("Karhu Timer", "Game speed for the tick after leaving the ground", 5, 0.1, 10, 0.1)).visibleWhen(() -> page.is(Page.TOWER) && tower.is(Tower.KARHU));
	private final BoolSetting karhuPulldown = add(new BoolSetting("Karhu Pulldown", "Also pull down", true)).visibleWhen(() -> page.is(Page.TOWER) && tower.is(Tower.KARHU));

	// Movement
	private final BoolSetting ledge = add(new BoolSetting("Ledge", "Sneak at an edge until the aim is ready, or when out of blocks", true)).visibleWhen(() -> page.is(Page.MOVEMENT));
	private final BoolSetting safeWalk = add(new BoolSetting("Safe Walk", "Never walk off the edge (Ledge already sneaks you at edges till the block's in; LiquidBounce's Hypixel config has this off)", false)).visibleWhen(() -> page.is(Page.MOVEMENT));
	private final BoolSetting noSprint = add(new BoolSetting("No Sprint", "Don't sprint while scaffolding", false)).visibleWhen(() -> page.is(Page.MOVEMENT));

	// Render
	private final BoolSetting render = add(new BoolSetting("Render", "Show placed blocks fading out", true)).visibleWhen(() -> page.is(Page.RENDER));
	private final ColorSetting color = add(new ColorSetting("Color", "Placed block colour", 0x40FF77C8)).visibleWhen(() -> page.is(Page.RENDER) && render.get());
	private final BoolSetting blockCounter = add(new BoolSetting("Block Count", "Show how many blocks you have left under the crosshair", true)).visibleWhen(() -> page.is(Page.RENDER));

	private static final Set<Block> DISALLOWED = Set.of(Blocks.TNT, Blocks.COBWEB, Blocks.NETHER_PORTAL);
	private static final Set<Block> UNFAVOURABLE = Set.of(Blocks.CRAFTING_TABLE, Blocks.JIGSAW, Blocks.SMITHING_TABLE, Blocks.FLETCHING_TABLE,
		Blocks.ENCHANTING_TABLE, Blocks.CAULDRON, Blocks.MAGMA_BLOCK);

	private final ScaffoldMovementPlanner planner = new ScaffoldMovementPlanner();
	private PlacementTarget target;
	private Geometry.Line optimalLine;
	private Rotation snapRotation;
	private int placementY, startY, jumps, forceSneak, delayLeft;
	private boolean wasTowering, wasOnGround;
	private int eaglePlaced, eagleEvery;
	private int ticksUntilJump, tellyJumpTicks, headHitCooldown;
	private float lastSideways;
	private long lastAirTime;
	private double edgeRandom = 0.45;
	private boolean onRightSide;
	private double jumpOffY = Double.NaN;
	private Vec3 jumpOffPos;
	private boolean pulldownArmed;
	private int karhuState;
	private float karhuBoost;
	private boolean turnedOnSpeed;
	private final List<long[]> placed = new ArrayList<>();

	public ScaffoldModule() {
		super("Scaffold", "Bridges under you automatically (LiquidBounce's scaffold)", Category.BUILD);
	}

	@Override
	protected void onEnable() {
		if (nullCheck()) return;
		placementY = mc.player.blockPosition().getY() - 1;
		startY = mc.player.blockPosition().getY();
		jumps = 2;
		planner.reset();
		if (autoSpeed.get() && !FemboyWare.isEnabled(SpeedModule.class)) {
			FemboyWare.getModules().get(SpeedModule.class).setEnabled(true);
			turnedOnSpeed = true;
		}
	}

	@Override
	protected void onDisable() {
		planner.reset();
		target = null;
		optimalLine = null;
		snapRotation = null;
		forceSneak = 0;
		delayLeft = 0;
		eaglePlaced = 0;
		jumpOffY = Double.NaN;
		pulldownArmed = false;
		karhuState = 0;
		karhuBoost = 0;
		placed.clear();
		if (turnedOnSpeed) {
			FemboyWare.getModules().get(SpeedModule.class).setEnabled(false);
			turnedOnSpeed = false;
		}
	}

	/** The game speed to run at, or 1. From TickRate. */
	public float timerSpeed() {
		if (!isEnabled()) return 1f;
		if (karhuBoost > 0) return karhuBoost;
		return timer.getFloat();
	}

	/** From PlayerEntityMixin: hold you on the edge. */
	public boolean safeWalks() {
		return isEnabled() && safeWalk.get() && mc.player != null && !shouldFallOffBlock();
	}

	// ---------------------------------------------------------------- blocks

	public static boolean isValidBlock(ItemStack stack) {
		if (stack == null || !(stack.getItem() instanceof BlockItem item)) return false;
		Block block = item.getBlock();
		if (block instanceof FallingBlock || DISALLOWED.contains(block)) return false;
		return block.defaultBlockState().entityCanStandOnFace(mc.level, BlockPos.ZERO, mc.player, Direction.UP);
	}

	private static boolean isUnfavourable(ItemStack stack) {
		if (!(stack.getItem() instanceof BlockItem item)) return true;
		Block block = item.getBlock();
		if (block.getFriction() > 0.6f || block.getSpeedFactor() < 1f || block.getJumpFactor() < 1f) return true;
		if (block instanceof BaseEntityBlock) return true;
		if (!block.defaultBlockState().isCollisionShapeFullBlock(mc.level, BlockPos.ZERO)) return true;
		return UNFAVOURABLE.contains(block);
	}

	private static float hardness(ItemStack stack) {
		return ((BlockItem) stack.getItem()).getBlock().defaultDestroyTime();
	}

	/** Which of two block stacks Scaffold would rather use: favourable, full, average hardness, then bigger stacks. */
	private static final Comparator<ItemStack> BLOCK_COMPARATOR = Comparator
		.comparing((ItemStack s) -> !isUnfavourable(s))
		.thenComparing(s -> ((BlockItem) s.getItem()).getBlock().defaultBlockState().isCollisionShapeFullBlock(mc.level, BlockPos.ZERO))
		.thenComparing(s -> { float h = hardness(s); return h >= 0.5f && h <= 2f; })
		.thenComparingInt(ItemStack::getCount)
		.thenComparing(s -> -Math.abs(hardness(s) - 1.25f));

	public int bestHotbarSlot() {
		int best = -1, fallback = -1;
		for (int i = 0; i < 9; i++) {
			ItemStack stack = mc.player.getInventory().getItem(i);
			if (!isValidBlock(stack)) continue;
			if (fallback < 0 || BLOCK_COMPARATOR.compare(stack, mc.player.getInventory().getItem(fallback)) > 0) fallback = i;
			if (stack.getCount() <= keepBelow.getInt()) continue;
			if (best < 0 || BLOCK_COMPARATOR.compare(stack, mc.player.getInventory().getItem(best)) > 0) best = i;
		}
		return best >= 0 ? best : fallback;
	}

	public int blockCount() {
		if (mc.player == null) return 0;
		int count = isValidBlock(mc.player.getOffhandItem()) ? mc.player.getOffhandItem().getCount() : 0;
		if (autoBlock.get()) {
			for (int i = 0; i < 9; i++) {
				ItemStack stack = mc.player.getInventory().getItem(i);
				if (isValidBlock(stack)) count += stack.getCount();
			}
		} else if (isValidBlock(mc.player.getMainHandItem())) {
			count += mc.player.getMainHandItem().getCount();
		}
		return count;
	}

	// ---------------------------------------------------------------- presets

	/** LiquidBounce's Hypixel config (LiquidCloud nextgen/hypixel.json, 09/03/2026, "Bypassing"), on femboyware's settings. */
	private void applyHypixelPreset() {
		java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
		values.put("Delay Min", 0.0);
		values.put("Delay Max", 0.0);
		values.put("Min Dist", 0.0);
		values.put("Timer", 1.0);
		values.put("Same Y", SameY.OFF);
		values.put("Auto Block", true);
		values.put("Switch", SwitchMode.NORMAL);
		values.put("Save Last", 5.0);
		values.put("Grim", false);
		values.put("Swing", Swing.PACKET);
		values.put("Auto Speed", false);
		values.put("Rotate", true);
		values.put("Angle Smooth", RotationSettings.Smooth.LINEAR);
		for (String turn : new String[]{"Turn H Min", "Turn H Max", "Turn V Min", "Turn V Max"}) values.put(turn, 120.0);
		values.put("Short Stop", false);
		values.put("Fail", false);
		values.put("Real Look", true);
		values.put("Rotation Timing", Timing.NORMAL);
		values.put("Aim Mode", AimMode.STABILIZED);
		values.put("Requires Sight", true);
		values.put("Technique", Technique.NORMAL);
		values.put("Eagle", false);
		values.put("Telly", false);
		values.put("Down", false);
		values.put("Ceiling", false);
		values.put("Head Hitter", false);
		values.put("Tower", Tower.HYPIXEL);
		values.put("Ledge", true);
		values.put("Safe Walk", false);
		values.put("No Sprint", false);
		applyValues(this, values);
		FemboyWare.getModules().get(dev.femboyware.module.client.MoveFixModule.class).setEnabled(true);
		Module sprint = FemboyWare.getModules().get(SprintModule.class);
		applyValues(sprint, java.util.Map.of("Mode", SprintModule.Mode.LEGIT));
		FemboyWare.getConfig().save();
		dev.femboyware.util.ChatUtil.info("Scaffold set to the Hypixel config with Real Look, plus Move Fix on and Legit Sprint");
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void applyValues(Module module, java.util.Map<String, Object> values) {
		for (dev.femboyware.setting.Setting setting : module.getSettings()) {
			if (values.containsKey(setting.getName())) setting.set(values.get(setting.getName()));
		}
	}

	// ---------------------------------------------------------------- state helpers

	private boolean jumpKey() {
		return mc.options.keyJump.isDown();
	}

	private boolean isTowering() {
		if (tower.get() != Tower.NONE && jumpKey()) {
			wasTowering = true;
			return true;
		}
		return false;
	}

	private Technique activeTechnique() {
		return isTowering() ? Technique.NORMAL : technique.get();
	}

	/** The way you mean to face: the camera's, which Real Look's free look keeps apart from your head. */
	private static float cameraYaw() {
		return dev.femboyware.util.FreeLook.yaw();
	}

	private boolean usesRealLook() {
		return rotate.get() && realLook.get();
	}

	private boolean rawMoving() {
		return mc.options.keyUp.isDown() || mc.options.keyDown.isDown() || mc.options.keyLeft.isDown() || mc.options.keyRight.isDown();
	}

	private boolean moving() {
		return mc.player.input.getMoveVector().lengthSquared() > 0;
	}

	/** The way the given keys walk you, in degrees of yaw, as LiquidBounce's getMovementDirectionOfInput. */
	private float movementYaw(boolean forward, boolean back, boolean left, boolean right) {
		float yaw = cameraYaw();
		float f = 1f;
		if (back) {
			yaw += 180f;
			f = -0.5f;
		} else if (forward) {
			f = 0.5f;
		}
		if (left) yaw -= 90f * f;
		if (right) yaw += 90f * f;
		return yaw;
	}

	private float rawMovementYaw() {
		return movementYaw(mc.options.keyUp.isDown(), mc.options.keyDown.isDown(), mc.options.keyLeft.isDown(), mc.options.keyRight.isDown());
	}

	/** Where vanilla's movement this tick will put you: your motion plus this tick's push from the keys. */
	private Vec3 nextPosition() {
		Vec3 v = mc.player.getDeltaMovement();
		Vec3 push = Vec3.ZERO;
		if (rawMoving()) {
			double accel = mc.player.onGround() ? mc.player.getSpeed() * 1.327 * 0.98 : (mc.player.isSprinting() ? 0.026 : 0.02) * 0.98;
			if (mc.player.isShiftKeyDown()) accel *= 0.3;
			push = Vec3.directionFromRotation(0, rawMovementYaw()).scale(accel);
		}
		double dy = mc.player.onGround() ? 0 : v.y;
		return mc.player.position().add(v.x + push.x, dy, v.z + push.z);
	}

	private Vec3 nextEyes() {
		return nextPosition().add(0, mc.player.getEyeHeight(), 0);
	}

	private boolean isBlockBelow() {
		AABB box = mc.player.getBoundingBox().inflate(0.5, 0, 0.5).move(0, -1.05, 0);
		return mc.level.getBlockCollisions(mc.player, box).iterator().hasNext();
	}

	/** Whether moving {@code distance} along the input would take you off the block. */
	private boolean closeToEdge(Input keys, double distance) {
		if (!mc.player.onGround()) return false;
		Vec3 dir;
		if (keys.forward() || keys.backward() || keys.left() || keys.right()) {
			dir = Vec3.directionFromRotation(0, movementYaw(keys.forward(), keys.backward(), keys.left(), keys.right()));
		} else {
			Vec3 v = mc.player.getDeltaMovement();
			if (v.horizontalDistanceSqr() < 1e-6) return false;
			dir = new Vec3(v.x, 0, v.z).normalize();
		}
		AABB box = mc.player.getBoundingBox().move(dir.x * distance, -0.6, dir.z * distance);
		return !mc.level.getBlockCollisions(mc.player, box).iterator().hasNext();
	}

	private boolean shouldGoDown() {
		return isEnabled() && down.get() && technique.is(Technique.NORMAL) && mc.options.keyShift.isDown();
	}

	private boolean shouldFallOffBlock() {
		if (!shouldGoDown()) return false;
		BlockPos below = mc.player.blockPosition().offset(0, -2, 0);
		return !mc.level.getBlockState(below).getCollisionShape(mc.level, below).isEmpty();
	}

	/** Ticks until the server rotation reaches {@code rotation} at the turn speed. */
	private int ticksUntil(Rotation rotation) {
		if (!rotate.get()) return 0;
		return Math.max(0, rotations.estimateTicks(FemboyWare.getRotations().getServerRotation(), rotation) - 1);
	}

	private BlockPos targetedPosition(BlockPos pos) {
		if (isTowering() || wasTowering) return pos.below();
		if (shouldGoDown()) return pos.offset(0, -2, 0);
		if (ceiling.get() && technique.is(Technique.NORMAL) && !mc.level.getBlockState(mc.player.blockPosition().below()).isAir()) return pos.offset(0, 3, 0);
		if (jumpKey() && (!moving() || mc.player.horizontalCollision)) return pos.below();
		// femboyware: God Bridge's ledge jumps aren't you jumping, so it stays level unless you hold jump
		if (activeTechnique() == Technique.GOD_BRIDGE && !jumpKey()) return new BlockPos(pos.getX(), placementY, pos.getZ());
		boolean same = switch (sameY.get()) {
			case OFF -> false;
			case ON -> true;
			case JUMP_KEY -> !jumpKey();
			case FALLING -> mc.player.getDeltaMovement().y < 0.2;
		};
		return same ? new BlockPos(pos.getX(), placementY, pos.getZ()) : pos.below();
	}

	// ---------------------------------------------------------------- techniques

	private PlacementTarget findTarget(Vec3 pos, Pose pose, ItemStack stack) {
		PlayerLocation location = PlayerLocation.of(pos, pose);
		// femboyware: the Hypixel tower walking forward builds the step under where this jump lands
		if (towerForward() && !mc.player.onGround()) {
			TargetFinding.Options options = new TargetFinding.Options(TargetFinding.NO_OFFSET, TargetFinding.leastBlockDistanceToPos(pos),
				TargetFinding.factory(AimMode.CENTER, null), true, stack, location);
			int level = stepLevel();
			for (BlockPos column : jumpColumns(mc.player.input.keyPresses)) {
				BlockPos step = new BlockPos(column.getX(), level, column.getZ());
				if (!mc.level.getBlockState(step).canBeReplaced()) continue;
				// Not inside you (the server won't put a block there) till you've risen past it
				if (mc.player.getBoundingBox().intersects(new AABB(step))) continue;
				PlacementTarget found = TargetFinding.findBest(step, options);
				if (found != null) return found;
			}
		}
		BlockPos feet = BlockPos.containing(pos);
		Comparator<BlockPos> priority = optimalLine != null ? TargetFinding.leastBlockDistanceToLine(optimalLine) : TargetFinding.leastBlockDistanceToPos(pos);
		switch (activeTechnique()) {
			case EXPAND -> {
				TargetFinding.Options options = new TargetFinding.Options(TargetFinding.NO_OFFSET, TargetFinding.leastBlockDistanceToPos(mc.player.position()),
					TargetFinding.factory(AimMode.CENTER, null), true, stack, location);
				float yaw = (float) Math.toRadians(cameraYaw());
				for (int i = 0; i <= expandLength.getInt(); i++) {
					BlockPos ahead = feet.offset((int) (-Math.sin(yaw) * i), 0, (int) (Math.cos(yaw) * i));
					PlacementTarget found = TargetFinding.findBest(targetedPosition(ahead), options);
					if (found != null) return found;
				}
				return null;
			}
			case GOD_BRIDGE, BREEZILY -> {
				TargetFinding.Options options = new TargetFinding.Options(TargetFinding.NORMAL, TargetFinding.leastBlockDistanceToPos(pos),
					TargetFinding.factory(AimMode.CENTER, null), false, stack, location);
				return TargetFinding.findBest(targetedPosition(feet), options);
			}
			default -> {
				boolean goDown = shouldGoDown();
				TargetFinding.Options options = new TargetFinding.Options(goDown ? TargetFinding.DOWN : TargetFinding.NORMAL, priority,
					TargetFinding.factory(aimMode.get(), optimalLine), goDown, stack, location);
				return TargetFinding.findBest(targetedPosition(feet), options);
			}
		}
	}

	private boolean tellyDoNotAim() {
		return telly.get() && technique.is(Technique.NORMAL) && airTicks() <= tellyStraight.getInt()
			&& ticksUntilJump >= tellyJumpTicks && !(isTowering() && tellyAimOnTower.get());
	}

	private int airTicks;

	private int airTicks() {
		return airTicks;
	}

	private Rotation rotationFor(PlacementTarget target) {
		Technique active = activeTechnique();
		if (active == Technique.EXPAND) {
			return target == null ? null : Rotation.between(mc.player.getEyePosition(), Vec3.atCenterOf(target.placedBlock()));
		}
		if (active == Technique.GOD_BRIDGE || active == Technique.BREEZILY) {
			if (!rawMoving()) {
				if (target == null) return null;
				float axis = (float) (Math.floor(target.rotation().yaw() / 90f) * 90f);
				return new Rotation(axis + 45f, 75f);
			}
			float direction = rawMovementYaw() + 180f;
			float movingYaw = Math.round(direction / 45f) * 45f;
			boolean straight = movingYaw % 90f == 0f;
			if (active == Technique.BREEZILY) return new Rotation(movingYaw, straight ? 80f : 75.6f);
			if (!straight) return new Rotation(movingYaw, 75.6f);
			if (mc.player.onGround()) {
				double rad = Math.toRadians(movingYaw);
				onRightSide = Math.floor(mc.player.getX() + Math.cos(rad) * 0.5) != Math.floor(mc.player.getX())
					|| Math.floor(mc.player.getZ() + Math.sin(rad) * 0.5) != Math.floor(mc.player.getZ());
				Direction facing = Direction.fromYRot(movingYaw);
				BlockPos ahead = BlockPos.containing(mc.player.position().add(facing.getStepX() * 0.6, 0, facing.getStepZ() * 0.6));
				boolean leaningOff = mc.level.getBlockState(mc.player.blockPosition().below()).isAir();
				if (leaningOff && mc.level.getBlockState(ahead.below()).isAir()) onRightSide = !onRightSide;
			}
			Rotation chosen = new Rotation(movingYaw + (onRightSide ? 45f : -45f), 75.7f);
			// femboyware: if the other diagonal is the one that actually lands on the target, use that
			if (target != null && !target.matches(TargetFinding.traceFromPlayer(chosen, mc.player.blockInteractionRange()))) {
				Rotation other = new Rotation(movingYaw + (onRightSide ? -45f : 45f), 75.7f);
				if (target.matches(TargetFinding.traceFromPlayer(other, mc.player.blockInteractionRange()))) {
					onRightSide = !onRightSide;
					return other;
				}
				// Neither diagonal reaches it from where you stand (dead centre): aim straight at it this time,
				// and sneak at the edge rather than jump (see onInput), since that aim goes stale in the air
				return target.rotation();
			}
			return chosen;
		}
		// Normal
		if (tellyDoNotAim()) {
			if (tellyReset.is(TellyReset.RESET)) return null;
			return new Rotation(Math.round(cameraYaw() / 45f) * 45f, Math.max(45f, dev.femboyware.util.FreeLook.pitch()));
		}
		if (target == null) return null;
		if (rawMoving() && !isTowering()) {
			Rotation aligned = keyAlignedRotation(target);
			if (aligned != null) return aligned;
		}
		return target.rotation();
	}

	/**
	 * femboyware: a rotation that still lands the click on {@code target} with its yaw on one of the 8
	 * headings your keys walk exactly, measured from the camera. Movement is remapped onto your
	 * (server or Real Look) head with whole key presses, so any other yaw walks you up to 22 degrees
	 * off where the camera points, which drifts you off the bridge. Null when none of them reaches.
	 */
	private Rotation keyAlignedRotation(PlacementTarget target) {
		float base = target.rotation().yaw();
		float camera = cameraYaw();
		float best = Float.NaN;
		for (int k = 0; k < 8; k++) {
			float heading = camera + k * 45f;
			if (Float.isNaN(best) || Math.abs(Mth.wrapDegrees(heading - base)) < Math.abs(Mth.wrapDegrees(best - base))) best = heading;
		}
		float yaw = base + Mth.wrapDegrees(best - base);
		if (Math.abs(Mth.wrapDegrees(yaw - base)) > 23f) return null;
		float pitch0 = target.rotation().pitch();
		for (int i = 0; i <= 80; i++) {
			float pitch = pitch0 + ((i & 1) == 0 ? 1 : -1) * (i / 2) * 0.5f;
			if (pitch < -90f || pitch > 90f) continue;
			Rotation candidate = new Rotation(yaw, pitch);
			BlockHitResult hit = TargetFinding.traceFromPoint(nextEyes(), candidate.toVector(), mc.player.blockInteractionRange());
			if (!target.matches(hit) || !validCrosshair(hit)) continue;
			if (requiresSight.get() && !hit.getBlockPos().equals(target.interactedBlockPos())) continue;
			return candidate;
		}
		return null;
	}

	/** femboyware: whether either God Bridge diagonal lands on {@code target} from where you stand. */
	private boolean diagonalReaches(PlacementTarget target) {
		float movingYaw = Math.round((rawMovementYaw() + 180f) / 45f) * 45f;
		for (float side : new float[]{45f, -45f}) {
			if (target.matches(TargetFinding.traceFromPlayer(new Rotation(movingYaw + side, 75.7f), mc.player.blockInteractionRange()))) return true;
		}
		return false;
	}

	private BlockHitResult crosshairFor(PlacementTarget target, Rotation rotation) {
		if (target == null) return null;
		Technique active = activeTechnique();
		boolean planned = active == Technique.EXPAND || active == Technique.NORMAL && shouldGoDown();
		return target.verifyClick(rotation, planned);
	}

	private boolean validCrosshair(BlockHitResult hit) {
		Direction side = hit.getDirection();
		if (side.getAxis() == Direction.Axis.Y) return true;
		Vec3 diff = hit.getLocation().subtract(mc.player.getEyePosition());
		double dist = side == Direction.NORTH || side == Direction.SOUTH ? diff.z : diff.x;
		return Math.abs(dist) >= minDist.get();
	}

	// ---------------------------------------------------------------- towers

	/** The Hypixel tower with you walking: a staircase forward, one step a jump. */
	private boolean towerForward() {
		return tower.is(Tower.HYPIXEL) && jumpKey() && rawMoving() && !Double.isNaN(jumpOffY);
	}

	/** The level the next step goes in: the one your feet were at when you jumped. */
	private int stepLevel() {
		return Mth.floor(jumpOffY + 1e-3);
	}

	/**
	 * The blocks (at foot level) this jump passes over till it comes down onto the next step, in
	 * order, worked out with vanilla's air physics from your speed and keys. The last is where you land.
	 */
	private List<BlockPos> jumpColumns(Input keys) {
		List<BlockPos> columns = new ArrayList<>();
		Vec3 at = mc.player.position(), v = mc.player.getDeltaMovement();
		double landY = stepLevel() + 1;
		boolean any = keys.forward() || keys.backward() || keys.left() || keys.right();
		Vec3 push = Vec3.ZERO;
		if (any) {
			Vec3 dir = Vec3.directionFromRotation(0, movementYaw(keys.forward(), keys.backward(), keys.left(), keys.right()));
			push = dir.scale(mc.player.isSprinting() ? 0.026 : 0.02);
		}
		BlockPos last = null;
		// From where you jumped: the step under that spot is what the rest get clicked onto
		if (jumpOffPos != null) {
			Vec3 from = jumpOffPos, to = mc.player.position();
			int samples = (int) Math.ceil(from.subtract(to).horizontalDistance() / 0.2);
			for (int i = 0; i < samples; i++) {
				Vec3 point = from.lerp(to, i / (double) samples);
				BlockPos column = BlockPos.containing(point.x, landY, point.z);
				if (!column.equals(last)) {
					addColumn(columns, last, column);
					last = column;
				}
			}
		}
		for (int tick = 0; tick < 30; tick++) {
			BlockPos column = BlockPos.containing(at.x, landY, at.z);
			if (!column.equals(last)) {
				addColumn(columns, last, column);
				last = column;
			}
			// Coming down onto the step's top
			if (v.y < 0 && at.y <= landY) break;
			v = new Vec3(v.x + push.x, v.y, v.z + push.z);
			at = at.add(v);
			v = new Vec3(v.x * 0.91, (v.y - 0.08) * 0.98, v.z * 0.91);
		}
		return columns;
	}

	/**
	 * Adds a column to the staircase; crossing a corner diagonally, the block beside both goes in
	 * first, since blocks only click onto faces and the diagonal one has none to click.
	 */
	private static void addColumn(List<BlockPos> columns, BlockPos last, BlockPos column) {
		if (last != null && last.getX() != column.getX() && last.getZ() != column.getZ()) {
			columns.add(new BlockPos(column.getX(), column.getY(), last.getZ()));
		}
		columns.add(column);
	}

	/** Ticks till this jump comes back down to the next step's top, by vanilla's gravity. */
	private int ticksToLanding() {
		double y = mc.player.getY(), vy = mc.player.getDeltaMovement().y, landY = stepLevel() + 1;
		for (int tick = 0; tick < 30; tick++) {
			if (vy < 0 && y <= landY) return tick;
			y += vy;
			vy = (vy - 0.08) * 0.98;
		}
		return 30;
	}

	private void onJump() {
		ticksUntilJump = 0;
		tellyJumpTicks = randomInt(tellyJump.getInt(), tellyJump.getInt());
		jumpOffY = mc.player.getY();
		jumpOffPos = mc.player.position();
		pulldownArmed = true;
		karhuState = 1;
	}

	private void tickTower() {
		karhuBoost = 0;
		Vec3 v = mc.player.getDeltaMovement();
		switch (tower.get()) {
			case MOTION -> {
				if (!jumpKey() || blockCount() <= 0 || !isBlockBelow()) {
					jumpOffY = Double.NaN;
					return;
				}
				if (Double.isNaN(jumpOffY)) return;
				if (mc.player.getY() > jumpOffY + towerTrigger.get()) {
					mc.player.setPos(mc.player.getX(), Math.floor(mc.player.getY()), mc.player.getZ());
					mc.player.setDeltaMovement(v.x * towerSlow.get(), towerMotion.get(), v.z * towerSlow.get());
					jumpOffY = mc.player.getY();
				}
			}
			case PULLDOWN -> {
				if (!jumpKey() || !pulldownArmed) return;
				if (v.y < pulldownTrigger.get() && !mc.player.onGround()) {
					pulldownArmed = false;
					if (isBlockBelow()) mc.player.setDeltaMovement(v.x, -1.0, v.z);
				}
			}
			case KARHU -> {
				if (!jumpKey()) {
					karhuState = 0;
					return;
				}
				if (karhuState == 1 && !mc.player.onGround()) {
					karhuBoost = karhuTimer.getFloat();
					karhuState = karhuPulldown.get() ? 2 : 0;
				} else if (karhuState == 2 && !mc.player.onGround() && v.y < pulldownTrigger.get()) {
					karhuState = 0;
					if (isBlockBelow()) mc.player.setDeltaMovement(v.x, v.y - 1.0, v.z);
				}
			}
			case VULCAN -> {
				if (!jumpKey() || blockCount() <= 0 || !isBlockBelow()) return;
				if (mc.player.tickCount % 2 == 0) mc.player.setDeltaMovement(v.x, 0.7, v.z);
				else mc.player.setDeltaMovement(v.x, moving() ? 0.42 : 0.6, v.z);
			}
			default -> {
			}
		}
	}

	private static int randomInt(int a, int b) {
		int lo = Math.min(a, b), hi = Math.max(a, b);
		return lo == hi ? lo : ThreadLocalRandom.current().nextInt(lo, hi + 1);
	}

	// ---------------------------------------------------------------- the tick

	@Override
	public void onPreMotion() {
		if (nullCheck()) return;
		boolean onGround = mc.player.onGround();
		if (wasOnGround && !onGround && mc.player.getDeltaMovement().y > 0) onJump();
		wasOnGround = onGround;
		airTicks = onGround ? 0 : airTicks + 1;
		if (onGround) {
			placementY = mc.player.blockPosition().getY() - 1;
			jumps++;
			wasTowering = false;
			ticksUntilJump++;
		}
		if (jumpKey()) {
			startY = mc.player.blockPosition().getY();
			jumps = 2;
		}
		tickTower();
		if (headHitter.get() && technique.is(Technique.NORMAL)) {
			if (headHitCooldown > 0) headHitCooldown--;
			else if (mc.player.onGround() && moving() && !mc.level.getBlockState(mc.player.blockPosition().above(2)).getCollisionShape(mc.level, mc.player.blockPosition().above(2)).isEmpty()) {
				mc.player.jumpFromGround();
			}
		}

		int slot = bestHotbarSlot();
		ItemStack stack = slot < 0 ? new ItemStack(Items.SANDSTONE, 64) : mc.player.getInventory().getItem(slot);
		optimalLine = rawMoving() ? planner.optimalLine(rawMovementYaw()) : null;
		Pose pose = eagle.get() && technique.is(Technique.NORMAL) && shouldEagle(mc.player.input.keyPresses) ? Pose.CROUCHING : Pose.STANDING;
		// femboyware: aim from where you'll be when the look goes out (after this tick's movement), as
		// LiquidBounce's Prediction does; aimed from where you are now, it's a step stale and misses the face
		Vec3 next = nextPosition();
		target = findTarget(next, pose, stack);
		// femboyware: LiquidBounce's Prediction: nothing to place yet, so find the block you're about to
		// need from where you'll be in a few ticks, and turn to it now; it's placed the moment you get there
		if (target == null && rawMoving() && mc.player.onGround() && !isTowering()) {
			Vec3 dir = Vec3.directionFromRotation(0, rawMovementYaw());
			double speed = Math.max(mc.player.getDeltaMovement().horizontalDistance(), mc.player.isSprinting() ? 0.28 : 0.21);
			for (int ahead = 1; ahead <= 4 && target == null; ahead++) {
				Vec3 predicted = next.add(dir.scale(speed * ahead));
				if (mc.level.getBlockState(BlockPos.containing(predicted).below()).canBeReplaced()) target = findTarget(predicted, pose, stack);
			}
		}

		if (!rotate.get()) return;
		if (timing.is(Timing.NORMAL)) {
			Rotation rotation = rotationFor(target);
			if (usesRealLook()) dev.femboyware.util.FreeLook.hold(realLookThirdPerson.get());
			if (rotation != null) FemboyWare.getRotations().request(rotation, ROTATION_PRIORITY, rotations, usesRealLook());
		} else if (timing.is(Timing.ON_TICK_SNAP) && snapRotation != null) {
			FemboyWare.getRotations().request(snapRotation, ROTATION_PRIORITY, 180f, false);
		}
	}

	/**
	 * femboyware: placements always go out at the start of the next tick, before its movement
	 * packet, with the rotation that packet before them carried: the order vanilla clicks in and
	 * LiquidBounce places in. Placed after the movement packet, Hypixel's Watchdog flags them.
	 */
	@Override
	public boolean actsBeforeMovement() {
		return true;
	}

	@Override
	public void onPostMotion() {
		if (nullCheck()) return;
		if (delayLeft > 0) {
			delayLeft--;
			return;
		}
		Rotation server = FemboyWare.getRotations().getServerRotation();
		Rotation rotation = server;
		if (rotate.get() && !timing.is(Timing.NORMAL) && !dev.femboyware.util.Grim.on() && !usesRealLook() && target != null) {
			Rotation wanted = rotationFor(target);
			if (wanted != null) rotation = wanted;
		}
		if (!rotate.get() && target != null) rotation = target.rotation();
		BlockHitResult hit = crosshairFor(target, rotation);
		if (target == null || hit == null || !validCrosshair(hit)) return;

		boolean mainHand = isValidBlock(mc.player.getMainHandItem());
		boolean offHand = isValidBlock(mc.player.getOffhandItem());
		int slot = -1;
		if (!mainHand && !offHand) {
			if (!autoBlock.get()) return;
			slot = bestHotbarSlot();
			if (slot < 0) return;
		}
		InteractionHand hand = mainHand || slot >= 0 ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;

		boolean onTick = rotate.get() && !timing.is(Timing.NORMAL) && !dev.femboyware.util.Grim.on() && !usesRealLook();
		if (onTick && server.distanceTo(rotation) > 0.01f) FemboyWare.getRotations().sendPacketRotation(rotation);
		if (!rotate.get() && server.distanceTo(rotation) > 0.01f) FemboyWare.getRotations().sendPacketRotation(rotation);

		PlacementTarget placing = target;
		BlockHitResult click = hit;
		boolean[] success = {false};
		Runnable place = () -> {
			InteractionResult result = mc.gameMode.useItemOn(mc.player, hand, click);
			if (result.consumesAction()) {
				success[0] = true;
				switch (swing.get()) {
					case NORMAL -> mc.player.swing(hand);
					//? if >=26.3 {
					/*case PACKET -> mc.player.swing(hand);
					*///?} else {
					case PACKET -> mc.getConnection().send(new ServerboundSwingPacket(hand));
					//?}
					default -> {
					}
				}
			}
		};
		if (slot >= 0) InventoryUtil.withSlot(slot, switchMode.get(), place);
		else place.run();

		if (onTick) {
			if (timing.is(Timing.ON_TICK_SNAP)) snapRotation = rotation;
			else FemboyWare.getRotations().sendPacketRotation(new Rotation(mc.player.getYRot(), mc.player.getXRot()));
		}
		if (!success[0]) return;
		planner.trackPlacedBlock(placing.placedBlock());
		placed.add(new long[]{placing.placedBlock().asLong(), System.currentTimeMillis()});
		onEaglePlace();
		target = null;
		delayLeft = randomInt(delayMin.getInt(), delayMax.getInt());
	}

	// ---------------------------------------------------------------- movement input

	private boolean shouldEagle(Input keys) {
		if (shouldFallOffBlock()) return false;
		if (!mc.player.onGround() && eagleGround.get()) return false;
		if (mc.player.getAbilities().flying || eaglePlaced != 0) return false;
		return closeToEdge(keys, eagleEdge.get());
	}

	private void onEaglePlace() {
		if (!eagle.get()) return;
		eaglePlaced++;
		if (eaglePlaced > eagleEvery) {
			eaglePlaced = 0;
			eagleEvery = eagleBlocks.getInt();
		}
	}

	@Override
	public void onInput() {
		if (nullCheck()) return;
		Input keys = mc.player.input.keyPresses;
		boolean forward = keys.forward(), back = keys.backward(), left = keys.left(), right = keys.right();
		boolean jump = keys.jump(), sneak = keys.shift(), sprint = keys.sprint();

		if (forceSneak > 0) {
			sneak = true;
			forceSneak--;
		}
		// femboyware: Hypixel tower walking forward, in the air: if the step this jump lands on still
		// isn't in close to landing, brake with the opposite key (vanilla air control) so you come
		// down on a block instead of past it
		if (towerForward() && !mc.player.onGround() && (forward || back || left || right)) {
			List<BlockPos> columns = jumpColumns(keys);
			BlockPos land = columns.get(columns.size() - 1);
			BlockPos step = new BlockPos(land.getX(), stepLevel(), land.getZ());
			BlockPos old = step.below();
			boolean stepIn = !mc.level.getBlockState(step).getCollisionShape(mc.level, step).isEmpty();
			boolean oldIn = !mc.level.getBlockState(old).getCollisionShape(mc.level, old).isEmpty();
			if (!stepIn && ticksToLanding() <= 4) {
				Vec3 v = mc.player.getDeltaMovement();
				if (v.horizontalDistanceSqr() > 1e-4 && !oldIn) {
					// Push against the way you're drifting, in your own key frame
					float drift = (float) Math.toDegrees(Math.atan2(-v.x, v.z));
					float rel = Mth.wrapDegrees(drift - cameraYaw());
					forward = Math.abs(rel) > 112.5f;
					back = Math.abs(rel) < 67.5f;
					right = rel < -22.5f && rel > -157.5f;
					left = rel > 22.5f && rel < 157.5f;
				} else if (!oldIn) {
					forward = back = left = right = false;
				}
				sprint = false;
			}
		}
		Technique active = activeTechnique();
		if (ledge.get()) {
			Rotation rotation = FemboyWare.getRotations().getServerRotation();
			int sneakTime = 0;
			boolean ledgeJump = false, stop = false, stepBack = false;
			if (closeToEdge(keys, 0.1)) {
				int ticks = target != null ? ticksUntil(rotationFor(target) != null ? rotationFor(target) : rotation) : 0;
				// femboyware: also while the aim's there but the click can't land from here yet (or there's
				// nothing to click), so you never walk off before the block's in
				// From where this tick's movement leaves you, which is where the click goes out from
				BlockHitResult now = target != null ? TargetFinding.traceFromPoint(nextEyes(), rotation.toVector(), mc.player.blockInteractionRange()) : null;
				boolean placeable = now != null && target.matches(now) && validCrosshair(now);
				boolean needsBlock = mc.level.getBlockState(BlockPos.containing(mc.player.position().add(Vec3.directionFromRotation(0, movementYaw(keys.forward(), keys.backward(), keys.left(), keys.right())).scale(0.4))).below()).canBeReplaced();
				if (blockCount() <= 0 || ticks >= 1 || needsBlock && !placeable) sneakTime = Math.max(1, ticks);
			}
			if (sneakTime == 0 && active == Technique.GOD_BRIDGE && target != null) {
				// Held at the edge (the walk would take you off it): if the aim can't place from here, act
				boolean ledged = closeToEdge(keys, 0.1);
				if (ledged) {
					BlockHitResult hit = TargetFinding.traceFromPoint(mc.player.getEyePosition(), rotation.toVector(), mc.player.blockInteractionRange());
					if (!(target.matches(hit) && validCrosshair(hit))) {
						LedgeMode mode = blockCount() < forceSneakBelow.getInt() || !diagonalReaches(target) ? LedgeMode.SNEAK : godBridgeMode.get();
						switch (mode) {
							case JUMP -> ledgeJump = true;
							case SNEAK -> sneakTime = 1;
							case STOP_INPUT -> stop = true;
							case BACKWARDS -> stepBack = true;
						}
					}
				}
			}
		if (ledgeJump) jump = true;
			if (stop) forward = back = left = right = false;
			if (stepBack) {
				forward = false;
				back = true;
			}
			if (sneakTime > forceSneak) {
				sneak = true;
				forceSneak = sneakTime;
			}
		}
		if (eagle.get() && active == Technique.NORMAL && !sneak && shouldEagle(keys)) sneak = true;
		if (telly.get() && active == Technique.NORMAL && (forward || back || left || right) && blockCount() > 0 && mc.player.onGround()) {
			if (tellyReset.is(TellyReset.REVERSE) || tellyStraight.getInt() == 0 && ticksUntilJump >= tellyJumpTicks) jump = true;
		}
		if (active == Technique.BREEZILY && forward && !mc.player.isShiftKeyDown()) {
			boolean recentAir = true;
			if (mc.level.getBlockState(mc.player.blockPosition().below()).isAir()) lastAirTime = System.currentTimeMillis();
			else if (System.currentTimeMillis() - lastAirTime > 500) recentAir = false;
			if (recentAir) {
				double modX = mc.player.getX() - Math.floor(mc.player.getX()), modZ = mc.player.getZ() - Math.floor(mc.player.getZ());
				double ma = 1 - edgeRandom;
				float sideways = 0;
				switch (Direction.fromYRot(cameraYaw())) {
					case SOUTH -> { if (modX > ma) sideways = 1; if (modX < edgeRandom) sideways = -1; }
					case NORTH -> { if (modX > ma) sideways = -1; if (modX < edgeRandom) sideways = 1; }
					case EAST -> { if (modZ > ma) sideways = -1; if (modZ < edgeRandom) sideways = 1; }
					case WEST -> { if (modZ > ma) sideways = 1; if (modZ < edgeRandom) sideways = -1; }
					default -> {
					}
				}
				if (lastSideways != sideways && sideways != 0) {
					lastSideways = sideways;
					double lo = Math.min(edgeMin.get(), edgeMax.get()), hi = Math.max(edgeMin.get(), edgeMax.get());
					edgeRandom = lo == hi ? lo : ThreadLocalRandom.current().nextDouble(lo, hi);
				}
				left = lastSideways == -1;
				right = lastSideways == 1;
			}
		}
		if (shouldFallOffBlock()) sneak = false;
		if (noSprint.get()) sprint = false;

		Input changed = new Input(forward, back, left, right, jump, sneak, sprint);
		if (changed.equals(keys)) return;
		mc.player.input.keyPresses = changed;
		int forwardAxis = (forward ? 1 : 0) - (back ? 1 : 0);
		int strafeAxis = (left ? 1 : 0) - (right ? 1 : 0);
		Vec2 movement = new Vec2(strafeAxis, forwardAxis);
		((InputAccessor) mc.player.input).femboyware$setMovementVector(movement.lengthSquared() > 0 ? movement.normalized() : Vec2.ZERO);
	}

	// ---------------------------------------------------------------- render

	@Override
	public void onRender3D(Render3DEvent event) {
		if (!render.get()) return;
		long now = System.currentTimeMillis();
		for (Iterator<long[]> it = placed.iterator(); it.hasNext(); ) {
			long[] entry = it.next();
			float age = (now - entry[1]) / 600f;
			if (age >= 1f) {
				it.remove();
				continue;
			}
			int fill = color.get();
			int alpha = (int) (((fill >>> 24) & 0xFF) * (1 - age));
			int line = ColorUtil.withAlpha(fill, (int) (255 * (1 - age)));
			RenderUtil.drawBox(event, new AABB(BlockPos.of(entry[0])), ColorUtil.withAlpha(fill, alpha), line, 1.5f);
		}
	}

	@Override
	public void onRender2D(GuiGraphics context, float tickDelta) {
		if (!blockCounter.get() || nullCheck() || dev.femboyware.util.Mc.hudHidden()) return;
		int count = blockCount();
		String text = count + (count == 1 ? " block" : " blocks");
		int color = count == 0 ? 0xFFFF5555 : count < 16 ? 0xFFFFD060 : 0xFFFFFFFF;
		int x = context.guiWidth() / 2 - mc.font.width(text) / 2, y = context.guiHeight() / 2 + 12;
		context.drawString(mc.font, text, x, y, color);
	}

	@Override
	public String getInfo() {
		return EnumSetting.prettify(technique.get().name());
	}
}
