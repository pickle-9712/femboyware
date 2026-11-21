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
	private final BoolSetting retryCentered = add(new BoolSetting("Retry Centered", "When the server takes a block back, crouch and try again aiming nearer the middle of the face (closer each time), until a block stays", false)).visibleWhen(() -> page.is(Page.GENERAL));
	private final NumberSetting retryWait = add(new NumberSetting("Retry Wait", "Retry Centered: after a block is taken back, stay crouched this many ticks before clicking again (20 = 1 second), so a second click doesn't stack another flag on the first (0 = retry at once)", 0, 0, 40, 1)).visibleWhen(() -> page.is(Page.GENERAL) && retryCentered.get());
	private final NumberSetting crouchSettle = add(new NumberSetting("Crouch Settle", "On the ground, don't click until you've been crouched this many ticks (crouching holds you at the edge meanwhile). Hypixel takes back blocks clicked the moment you crouch (0 = off)", 0, 0, 10, 1)).visibleWhen(() -> page.is(Page.GENERAL));
	private final BoolSetting debug = add(new BoolSetting("Debug", "Say in chat when the server takes a block back or sets you back, and why blocks aren't going in, to find what a server flags", false)).visibleWhen(() -> page.is(Page.GENERAL));

	// Rotations
	private final BoolSetting rotate = add(new BoolSetting("Rotate", "Aim at each placement (needed on most servers)", true)).visibleWhen(() -> page.is(Page.ROTATIONS));
	private final RotationSettings rotations = new RotationSettings(s -> add(s), () -> page.is(Page.ROTATIONS) && rotate.get(), 120f);
	private final BoolSetting realLook = add(new BoolSetting("Real Look", "Really turn your head to where you build (no silent rotation), with free look so you can still look anywhere", false)).visibleWhen(() -> page.is(Page.ROTATIONS) && rotate.get());
	private final BoolSetting realLookThirdPerson = add(new BoolSetting("Real Look Third Person", "Switch to third person while Real Look turns your head", true)).visibleWhen(() -> page.is(Page.ROTATIONS) && rotate.get() && realLook.get());
	private final EnumSetting<Timing> timing = add(new EnumSetting<>("Rotation Timing", "Normal turns before placing; On Tick turns on the tick it places; Snap also keeps it", Timing.NORMAL)).visibleWhen(() -> page.is(Page.ROTATIONS) && rotate.get());
	private final EnumSetting<AimMode> aimMode = add(new EnumSetting<>("Aim Mode", "Which point on the face to aim at (Normal technique)", AimMode.STABILIZED)).visibleWhen(() -> page.is(Page.ROTATIONS));
	private final NumberSetting aimJitter = add(new NumberSetting("Aim Jitter", "Your aim drifts this many degrees at most, a little every tick, like a hand on a mouse; a head that holds dead still while you bridge looks like a bot (0 = off)", 0, 0, 3, 0.1)).visibleWhen(() -> page.is(Page.ROTATIONS) && rotate.get());
	private final NumberSetting maxPlaceTurn = add(new NumberSetting("Max Place Turn", "Don't click on a tick your aim turned further than this, wait a tick for it to settle (0 = off). Hypixel takes back blocks placed mid-snap", 25, 0, 180, 1)).visibleWhen(() -> page.is(Page.ROTATIONS) && rotate.get());
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
	private final NumberSetting eagleBlocksMax = add(new NumberSetting("Blocks To Eagle Max", "Pick the count at random between Blocks To Eagle and this each time (0 = always Blocks To Eagle)", 0, 0, 10, 1)).visibleWhen(() -> page.is(Page.TECHNIQUE) && eagle.get());
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
	private final BoolSetting holdSneak = add(new BoolSetting("Hold Sneak", "Stay crouched the whole time you bridge on the ground, as if you held crouch. Slower, but Hypixel keeps these blocks; Ledge's quick sneak flicks get taken back", false)).visibleWhen(() -> page.is(Page.MOVEMENT));
	private final BoolSetting safeWalk = add(new BoolSetting("Safe Walk", "Never walk off the edge (Ledge already sneaks you at edges till the block's in; LiquidBounce's Hypixel config has this off)", false)).visibleWhen(() -> page.is(Page.MOVEMENT));
	private final BoolSetting autoJump = add(new BoolSetting("Auto Jump", "Let Scaffold jump for you (Rescue, Head Hitter, God Bridge's ledge jump, Telly). Off, it never jumps: only your own jump key does", true)).visibleWhen(() -> page.is(Page.MOVEMENT));
	private final BoolSetting rescue = add(new BoolSetting("Rescue", "When a block you need is taken back, jump and rebuild the bridge in the air. Off for Hypixel: it takes back air blocks placed right after a rejected one", true)).visibleWhen(() -> page.is(Page.MOVEMENT));
	private final BoolSetting walkBack = add(new BoolSetting("Walk Back Speed", "Flat bridging (not holding jump), move at the speed of walking backwards with S: no sprint, even though you hold W. Real Look already makes the server see you facing back and holding S; this matches the speed. Hypixel keeps blocks at that pace", false)).visibleWhen(() -> page.is(Page.MOVEMENT));
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
	/** femboyware: blocks placed lately, watched for the server taking them back: {pos, game time}. */
	private final List<long[]> watching = new ArrayList<>();
	/** femboyware: rescue: jumping to rebuild the bridge at {@link #rescueLevel} under where you land. */
	private int rescueTicks, rescueLevel, stuckTicks;
	private boolean rescueJump;
	/** femboyware: Debug: why the last tick didn't place, how long that's gone on, and when we last said so. */
	private String skipReason;
	private int skipTicks;
	private long lastSkipReport;
	/** Debug: how each watched block was placed, and the tally of blocks kept and taken back. */
	private final java.util.Map<Long, String> placeInfo = new java.util.HashMap<>();
	private int keptCount, takenCount;
	/** Retry Centered: blocks taken back in a row, and the tick the retry gives up crouching. */
	private int retries;
	private long retryUntil, lastTakeBack;
	/** Aim Jitter: the current drift, a random walk kept within the setting. */
	private float jitterYaw, jitterPitch;
	/** Debug: how you were moving on the last tick sent, for the setback line. */
	private volatile String lastMove = "";
	private boolean lastSneak;
	private int sneakFor;
	private Rotation lastServer;

	public ScaffoldModule() {
		super("Scaffold", "Bridges under you automatically (LiquidBounce's scaffold)", Category.BUILD);
	}

	@Override
	protected void onEnable() {
		// Debug tally counts from when you turn Scaffold on, so each test starts at 0
		keptCount = takenCount = 0;
		placeInfo.clear();
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
		watching.clear();
		rescueTicks = stuckTicks = 0;
		retries = 0;
		rescueJump = false;
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
		values.put("Swing", Swing.NORMAL);
		values.put("Auto Speed", false);
		values.put("Rotate", true);
		values.put("Angle Smooth", RotationSettings.Smooth.LINEAR);
		// LiquidBounce's config turns 120 a tick; Watchdog now takes back blocks placed on snaps like that
		values.put("Turn H Min", 38.0);
		values.put("Turn H Max", 58.0);
		values.put("Turn V Min", 26.0);
		values.put("Turn V Max", 42.0);
		values.put("Max Place Turn", 15.0);
		// Flat bridging kept 5-7 blocks then lost one, whatever the crouching: the head never moving is the next suspect
		values.put("Aim Jitter", 0.8);
		values.put("Short Stop", false);
		values.put("Fail", false);
		values.put("Real Look", true);
		values.put("Rotation Timing", Timing.NORMAL);
		// Tested on Hypixel: Nearest Rotation with Swing Normal and no delay keeps staircase bridges
		values.put("Aim Mode", AimMode.NEAREST_ROTATION);
		values.put("Rescue", false);
		values.put("Auto Jump", false);
		values.put("Requires Sight", true);
		values.put("Technique", Technique.NORMAL);
		// Hypixel keeps a flat bridge forever when it sees you walk backwards (no sprint) and crouch for a moment every 3-5 blocks
		values.put("Retry Centered", true);
		// Half a second crouched before clicking again: an instant crouched retry got taken back too, then a setback
		values.put("Retry Wait", 10.0);
		// Ground take-backs were nearly all clicks made the tick you crouched; a real player settles first
		values.put("Crouch Settle", 3.0);
		values.put("Eagle", true);
		values.put("Blocks To Eagle", 3.0);
		values.put("Blocks To Eagle Max", 5.0);
		values.put("Eagle Only On Ground", true);
		values.put("Telly", false);
		values.put("Down", false);
		values.put("Ceiling", false);
		values.put("Head Hitter", false);
		values.put("Tower", Tower.HYPIXEL);
		values.put("Ledge", true);
		// Hold Sneak stays off: crouched clicks got taken back too, and it's too slow
		values.put("Hold Sneak", false);
		values.put("Safe Walk", false);
		values.put("No Sprint", false);
		// Flat: walking backwards at S speed (no sprint); sprinting with W got the first block taken back
		values.put("Walk Back Speed", true);
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

	/**
	 * femboyware: eye heights a click has to land from. Hypixel runs 1.8, where sneaking puts your eyes
	 * at 1.54 instead of 1.21's 1.27, so a sneaking click aimed from 1.21's eyes can miss on their end
	 * and the block's taken back; and Ledge may start or stop sneaking right after the aim is chosen.
	 */
	private static final double[] EYE_HEIGHTS = {1.62, 1.54, 1.27};
	/** Standing or sneaking on Hypixel's 1.8: what its server traces from either way. */
	private static final double[] SERVER_EYES = {1.62, 1.54};
	private static final double[] STANDING_EYES = {1.62};
	private static final double[] SNEAKING_EYES = {1.54};

	/** The eye height the server traces your click from right now. */
	private double[] currentEyes() {
		return mc.player.isShiftKeyDown() ? SNEAKING_EYES : STANDING_EYES;
	}

	/** Whether looking along {@code rotation} from feet at {@code feet} clicks {@code target} from every eye height above. */
	private boolean landsFromAllEyes(PlacementTarget target, Rotation rotation, Vec3 feet) {
		return landsFrom(target, rotation, feet, EYE_HEIGHTS);
	}

	private boolean landsFrom(PlacementTarget target, Rotation rotation, Vec3 feet, double[] heights) {
		Vec3 look = rotation.toVector();
		for (double height : heights) {
			BlockHitResult hit = TargetFinding.traceFromPoint(feet.add(0, height, 0), look, mc.player.blockInteractionRange());
			if (!target.matches(hit) || !validCrosshair(hit)) return false;
		}
		return true;
	}

	/**
	 * Searches pitch at {@code yaw} for a look that clicks {@code target}: from every eye height if
	 * there's one (safe whether Ledge sneaks you or not), else from both of 1.8's, else from the eye
	 * height you're at now. Requiring every height outright left side faces with no look at all, so
	 * after the first block it aimed and never placed.
	 */
	private Rotation pitchForAllEyes(PlacementTarget target, float yaw, float pitch0, Vec3 feet) {
		for (double[] heights : new double[][]{EYE_HEIGHTS, SERVER_EYES}) {
			for (int i = 0; i <= 120; i++) {
				float pitch = pitch0 + ((i & 1) == 0 ? 1 : -1) * (i / 2) * 0.25f;
				if (pitch < -90f || pitch > 90f) continue;
				Rotation candidate = new Rotation(yaw, pitch);
				if (landsFrom(target, candidate, feet, heights)) return candidate;
			}
		}
		return null;
	}

	/** Whether standing at {@code feet} there'd be a block under some part of you. */
	private boolean supportedAt(Vec3 feet) {
		AABB box = mc.player.getBoundingBox().move(feet.subtract(mc.player.position())).move(0, -0.6, 0).setMaxY(feet.y - 0.01);
		return mc.level.getBlockCollisions(mc.player, box).iterator().hasNext();
	}

	/**
	 * femboyware: sees a fall coming and jumps to get out of it, rebuilding the bridge under where the
	 * jump lands (as jump bridging does, which has the whole jump to get the block in):
	 * <ul>
	 * <li>the server took back a block placed in the last two seconds (it's air again) under or ahead of you,</li>
	 * <li>or Ledge has held you at the edge for a while and the block still can't go in.</li>
	 * </ul>
	 */
	private void tickRescue() {
		long now = mc.level.getGameTime();
		// entry[2]: 0 = waiting, 1 = taken back, 2 = stayed long enough to count as kept for Retry Centered
		for (long[] entry : watching) {
			BlockPos pos = BlockPos.of(entry[0]);
			if (entry[2] == 1) continue;
			if (mc.level.getBlockState(pos).canBeReplaced()) {
				entry[2] = 1;
				takenCount++;
				if (debug.get()) say(takenCount + " back / " + keptCount + " kept. Taken back " + (now - entry[1]) + "t after: " + placeInfo.getOrDefault(entry[0], "?")
					+ (retryCentered.get() ? " (retry " + Math.min(retries + 1, 3) + ", nearer the middle, crouched)" : ""));
				if (retryCentered.get()) {
					retries = Math.min(retries + 1, 3);
					retryUntil = now + 100 + retryWait.getInt();
					lastTakeBack = now;
				}
			} else if (entry[2] == 0 && now - entry[1] >= 10) {
				// Took back after 2-5 ticks when it does: 10 ticks standing, it worked
				entry[2] = 2;
				retries = 0;
			}
		}
		watching.removeIf(entry -> {
			if (now - entry[1] <= 40) return false;
			if (entry[2] != 1) keptCount++;
			placeInfo.remove(entry[0]);
			return true;
		});
		if (rescueTicks > 0) {
			rescueTicks--;
			// Landed back on the bridge: done
			if (mc.player.onGround() && rescueTicks < 17) rescueTicks = 0;
		}
		if (!mc.player.onGround() || isTowering() || blockCount() <= 0 || !rawMoving()) {
			stuckTicks = 0;
			return;
		}
		int level = mc.player.blockPosition().getY() - 1;
		boolean takenBack = false;
		for (long[] entry : watching) {
			BlockPos pos = BlockPos.of(entry[0]);
			if (pos.getY() != level || !mc.level.getBlockState(pos).canBeReplaced()) continue;
			if (Vec3.atCenterOf(pos).subtract(mc.player.position()).horizontalDistance() < 2.0) {
				takenBack = true;
				break;
			}
		}
		stuckTicks = mc.player.isShiftKeyDown() && !supportedAt(nextPosition().add(Vec3.directionFromRotation(0, rawMovementYaw()).scale(0.15))) ? stuckTicks + 1 : 0;
		// Not while you hold crouch: you're bridging carefully, and a jump would take your sneak away
		if ((takenBack || stuckTicks > 6) && rescue.get() && autoJump.get() && !mc.options.keyShift.isDown()) {
			rescueLevel = level;
			rescueTicks = 20;
			rescueJump = true;
			stuckTicks = 0;
			watching.removeIf(entry -> BlockPos.of(entry[0]).getY() == level && mc.level.getBlockState(BlockPos.of(entry[0])).canBeReplaced());
		}
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
		// femboyware: rescue jump: the bridge goes back in at its own level under you
		// (once you've dropped into that level it's too late for it: then it's the usual block under you, a level down)
		if (rescueTicks > 0 && mc.player.getY() >= rescueLevel + 1) return new BlockPos(pos.getX(), rescueLevel, pos.getZ());
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
		// femboyware: rescue jump: the bridge back in at its level along where this jump goes, first gap first
		if (rescueTicks > 0 && !mc.player.onGround() && mc.player.getY() >= rescueLevel + 1) {
			TargetFinding.Options options = new TargetFinding.Options(TargetFinding.NO_OFFSET, TargetFinding.leastBlockDistanceToPos(pos),
				TargetFinding.factory(AimMode.CENTER, null), true, stack, location);
			for (BlockPos column : jumpColumns(mc.player.input.keyPresses, rescueLevel + 1)) {
				BlockPos gap = new BlockPos(column.getX(), rescueLevel, column.getZ());
				if (!mc.level.getBlockState(gap).canBeReplaced()) continue;
				if (mc.player.getBoundingBox().intersects(new AABB(gap))) continue;
				PlacementTarget found = TargetFinding.findBest(gap, options);
				if (found != null) return found;
			}
		}
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
				TargetFinding.FacePositionFactory aim = TargetFinding.factory(aimMode.get(), optimalLine);
				if (retrying()) {
					// Retry Centered: pull the aim point toward the middle of the face, further each retry
					TargetFinding.FacePositionFactory base = aim;
					double pull = Math.min(1.0, 0.4 * retries);
					aim = (face, at, location1) -> {
						Vec3 point = base.produce(face, at, location1);
						return point == null ? face.center() : point.lerp(face.center(), pull);
					};
				}
				TargetFinding.Options options = new TargetFinding.Options(goDown ? TargetFinding.DOWN : TargetFinding.NORMAL, priority,
					aim, goDown, stack, location);
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
		if (!isTowering()) {
			Rotation safe = pitchForAllEyes(target, target.rotation().yaw(), target.rotation().pitch(), nextPosition());
			if (safe != null) return safe;
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
		Rotation found = pitchForAllEyes(target, yaw, target.rotation().pitch(), nextPosition());
		if (found != null && requiresSight.get()) {
			BlockHitResult hit = TargetFinding.traceFromPoint(nextEyes(), found.toVector(), mc.player.blockInteractionRange());
			if (!hit.getBlockPos().equals(target.interactedBlockPos())) return null;
		}
		return found;
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
		return jumpColumns(keys, stepLevel() + 1);
	}

	/** {@link #jumpColumns(Input)} coming down onto {@code landY} instead of the next step. */
	private List<BlockPos> jumpColumns(Input keys, double landY) {
		List<BlockPos> columns = new ArrayList<>();
		Vec3 at = mc.player.position(), v = mc.player.getDeltaMovement();
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
				if (autoJump.get()) mc.player.jumpFromGround();
			}
		}

		tickRescue();
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
			if (rotation != null && aimJitter.get() > 0) {
				// A small random walk, so the aim is never frozen; the click checks still run on where it ends up
				float max = aimJitter.getFloat();
				jitterYaw = Mth.clamp(jitterYaw + (float) ThreadLocalRandom.current().nextGaussian() * max * 0.3f, -max, max);
				jitterPitch = Mth.clamp(jitterPitch + (float) ThreadLocalRandom.current().nextGaussian() * max * 0.2f, -max * 0.6f, max * 0.6f);
				rotation = new Rotation(rotation.yaw() + jitterYaw, Mth.clamp(rotation.pitch() + jitterPitch, -90f, 90f));
			}
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
		Rotation previous = lastServer;
		lastServer = FemboyWare.getRotations().getServerRotation();
		boolean sneaking = mc.player.isShiftKeyDown();
		sneakFor = sneaking == lastSneak ? sneakFor + 1 : 0;
		lastSneak = sneaking;
		if (debug.get()) recordMove();
		// "turned" compares against the rotation the tick before
		postMotion(previous);
	}

	private void postMotion(Rotation previousServer) {
		Rotation lastServer = previousServer;
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
		if (target == null || hit == null || !validCrosshair(hit)) {
			skip(target == null ? "no block to click from here" : "aim isn't on the face yet");
			return;
		}
		// femboyware: flat bridging, click only when it also lands from 1.8's eye heights (see EYE_HEIGHTS),
		// or Hypixel takes the block back; Ledge holds you at the edge meanwhile
		if (crouchSettle.getInt() > 0 && mc.player.onGround() && !isTowering() && mc.player.isShiftKeyDown() && sneakFor < crouchSettle.getInt()) {
			skip("settling into the crouch");
			return;
		}
		if (retrying() && retryWait.getInt() > 0 && mc.player.onGround() && !isTowering() && mc.level.getGameTime() - lastTakeBack < retryWait.getInt()) {
			skip("waiting before the retry");
			return;
		}
		if (rotate.get() && maxPlaceTurn.getInt() > 0 && lastServer != null && lastServer.distanceTo(rotation) > maxPlaceTurn.get()) {
			skip("aim still turning");
			return;
		}
		// Both of 1.8's heights: Ledge flips sneak at the edge, and the server can still have you standing (1.62)
		// when a sneaking click (1.54) arrives, or the other way round; it takes back whichever misses
		if (mc.player.onGround() && !isTowering() && rotate.get() && !landsFrom(target, rotation, mc.player.position(), SERVER_EYES)) {
			skip("click wouldn't land from both 1.8 eye heights");
			return;
		}

		boolean mainHand = isValidBlock(mc.player.getMainHandItem());
		boolean offHand = isValidBlock(mc.player.getOffhandItem());
		int slot = -1;
		if (!mainHand && !offHand) {
			if (!autoBlock.get()) {
				skip("not holding blocks (Auto Block is off)");
				return;
			}
			slot = bestHotbarSlot();
			if (slot < 0) {
				skip("no blocks in your hotbar");
				return;
			}
		}
		InteractionHand hand = mainHand || slot >= 0 ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;

		boolean onTick = rotate.get() && !timing.is(Timing.NORMAL) && !dev.femboyware.util.Grim.on() && !usesRealLook();
		if (onTick && server.distanceTo(rotation) > 0.01f) FemboyWare.getRotations().sendPacketRotation(rotation);
		if (!rotate.get() && server.distanceTo(rotation) > 0.01f) FemboyWare.getRotations().sendPacketRotation(rotation);

		PlacementTarget placing = target;
		BlockHitResult click = hit;
		// femboyware: sneaking, the click goes out with the point Hypixel's own look lands on: its 1.8 server
		// puts sneaking eyes at 1.54, not 1.21's 1.27, and takes back blocks whose click point doesn't match
		// what it traces (a few in a row and it starts cancelling them)
		if (mc.player.isShiftKeyDown() && rotate.get()) {
			BlockHitResult theirs = TargetFinding.traceFromPoint(mc.player.position().add(0, 1.54, 0), rotation.toVector(), mc.player.blockInteractionRange());
			if (!placing.matches(theirs) || !validCrosshair(theirs)) {
				skip("sneaking click misses from Hypixel's 1.54 eyes");
				return;
			}
			click = theirs;
		}
		BlockHitResult clicked = click;
		String how = null;
		if (debug.get()) {
			Vec3 eye = mc.player.getEyePosition();
			float turned = lastServer == null ? 0 : lastServer.distanceTo(server);
			how = (mc.player.onGround() ? "ground" : "air t" + airTicks + (mc.player.getDeltaMovement().y > 0 ? " rising" : " falling"))
				+ ", " + clicked.getDirection().getName() + " face, look " + Math.round(rotation.yaw()) + "/" + Math.round(rotation.pitch())
				+ ", turned " + Math.round(turned) + "\u00b0, reach " + String.format(java.util.Locale.ROOT, "%.2f", eye.distanceTo(clicked.getLocation()))
				+ (mc.player.isShiftKeyDown() ? ", sneaking" : "") + (mc.player.isSprinting() ? ", sprinting" : "")
				+ (slot >= 0 && mc.player.getInventory().getSelectedSlot() != slot ? ", switched" : "")
				+ ", lands from " + (landsFrom(placing, rotation, mc.player.position(), STANDING_EYES) ? "1.62 " : "")
				+ (landsFrom(placing, rotation, mc.player.position(), SNEAKING_EYES) ? "1.54 " : "")
				+ (landsFrom(placing, rotation, mc.player.position(), new double[]{1.27}) ? "1.27" : "");
		}
		boolean[] success = {false};
		Runnable place = () -> {
			InteractionResult result = mc.gameMode.useItemOn(mc.player, hand, clicked);
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
		if (!success[0]) {
			skip("the game refused the click");
			return;
		}
		skipTicks = 0;
		if (how != null) placeInfo.put(placing.placedBlock().asLong(), how);
		planner.trackPlacedBlock(placing.placedBlock());
		watching.add(new long[]{placing.placedBlock().asLong(), mc.level.getGameTime(), 0});
		placed.add(new long[]{placing.placedBlock().asLong(), System.currentTimeMillis()});
		onEaglePlace();
		target = null;
		delayLeft = randomInt(delayMin.getInt(), delayMax.getInt());
	}

	// ---------------------------------------------------------------- debug

	/** Debug: a block that's needed isn't going in; said once it's gone on for half a second, at most every 2 s. */
	private void skip(String reason) {
		if (!debug.get()) return;
		boolean needed = mc.player.onGround() ? closeToEdge(mc.player.input.keyPresses, 0.3) : true;
		if (!needed || !reason.equals(skipReason)) {
			skipReason = reason;
			skipTicks = 0;
			if (!needed) return;
		}
		long now = System.currentTimeMillis();
		if (++skipTicks >= 10 && now - lastSkipReport > 2000) {
			lastSkipReport = now;
			say("not placing: " + reason);
		}
	}

	private static void say(String text) {
		// Straight to chat, so whole lines can be read back (notifications cut them off)
		dev.femboyware.util.ChatUtil.chat("Scaffold debug: " + text);
	}

	/** Debug: the movement the server just got (keys after Move Fix, crouch, speed, direction against your head). */
	private void recordMove() {
		boolean sneaking = mc.player.isShiftKeyDown();
		Input keys = mc.player.input.keyPresses;
		String held = (keys.forward() ? "W" : "") + (keys.left() ? "A" : "") + (keys.backward() ? "S" : "") + (keys.right() ? "D" : "");
		double dx = mc.player.getX() - mc.player.xo, dz = mc.player.getZ() - mc.player.zo;
		double speed = Math.sqrt(dx * dx + dz * dz);
		String dir = "";
		if (speed > 0.01) {
			float moveYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
			dir = ", moving " + Math.round(Mth.wrapDegrees(moveYaw - mc.player.getYRot())) + "° from your head";
		}
		BlockPos below = BlockPos.containing(mc.player.getX(), mc.player.getY() - 0.01, mc.player.getZ());
		lastMove = "keys " + (held.isEmpty() ? "none" : held) + (keys.shift() ? "+crouch" : "") + (keys.sprint() ? "+sprint" : "")
			+ ", " + (sneaking ? "crouched " : "standing ") + sneakFor + "t"
			+ ", speed " + String.format(java.util.Locale.ROOT, "%.3f", speed) + " (walk 0.216, crouch 0.065)" + dir
			+ (mc.player.onGround() && mc.level.getBlockState(below).canBeReplaced() ? ", feet past the edge" : "")
			+ (mc.player.isSprinting() ? ", sprinting" : "");
	}

	@Override
	public void onPacketReceive(dev.femboyware.event.PacketEvent event) {
		if (!debug.get() || !(event.getPacket() instanceof net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket)) return;
		// Read now, before the main thread moves you: the last tick you sent is what it flagged
		String move = lastMove;
		mc.execute(() -> {
			if (mc.player == null) return;
			say("the server set you back (Watchdog flagged your movement)" + (mc.player.onGround() ? "" : ", in the air") + (isTowering() ? ", towering" : "")
				+ (move.isEmpty() ? "" : ". Last tick: " + move));
		});
	}

	// ---------------------------------------------------------------- movement input

	private boolean retrying() {
		return retryCentered.get() && retries > 0 && mc.level != null && mc.level.getGameTime() < retryUntil;
	}

	private boolean shouldEagle(Input keys) {
		if (shouldFallOffBlock()) return false;
		if (!mc.player.onGround() && eagleGround.get()) return false;
		// Not while you hold jump: staircasing, a crouch on landing broke the jump bridge Hypixel had been keeping
		if (isTowering()) return false;
		if (mc.player.getAbilities().flying || eaglePlaced != 0) return false;
		return closeToEdge(keys, eagleEdge.get());
	}

	private void onEaglePlace() {
		if (!eagle.get()) return;
		eaglePlaced++;
		if (eaglePlaced > eagleEvery) {
			eaglePlaced = 0;
			eagleEvery = eagleBlocksMax.getInt() > 0 ? randomInt(eagleBlocks.getInt(), eagleBlocksMax.getInt()) : eagleBlocks.getInt();
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
				boolean placeable = now != null && target.matches(now) && validCrosshair(now) && landsFrom(target, rotation, nextPosition(), SERVER_EYES);
				boolean needsBlock = mc.level.getBlockState(BlockPos.containing(mc.player.position().add(Vec3.directionFromRotation(0, movementYaw(keys.forward(), keys.backward(), keys.left(), keys.right())).scale(0.4))).below()).canBeReplaced();
				if (blockCount() <= 0 || ticks >= 1 || needsBlock && !placeable) sneakTime = Math.max(1, ticks);
			}
			// femboyware: whatever the aim's doing, never take the step that leaves you over nothing: if this
			// tick's walk (and a bit) would, sneak, and vanilla's sneak stops you at the edge till the block's in.
			// Going diagonally you cross corners, where the 0.1 edge check above notices too late
			if (sneakTime == 0 && mc.player.onGround() && (keys.forward() || keys.backward() || keys.left() || keys.right()) && !isTowering()) {
				Vec3 dir = Vec3.directionFromRotation(0, movementYaw(keys.forward(), keys.backward(), keys.left(), keys.right()));
				Vec3 next = nextPosition().add(dir.scale(0.15));
				if (!supportedAt(next)) sneakTime = 1;
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
		if (ledgeJump && autoJump.get()) jump = true;
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
		// Retry Centered: crouch until a block stays
		if (retrying() && mc.player.onGround() && !isTowering() && (forward || back || left || right)) sneak = true;
		if (holdSneak.get() && mc.player.onGround() && !isTowering() && (forward || back || left || right) && blockCount() > 0) sneak = true;
		if (telly.get() && active == Technique.NORMAL && (forward || back || left || right) && blockCount() > 0 && mc.player.onGround()) {
			if (tellyReset.is(TellyReset.REVERSE) || tellyStraight.getInt() == 0 && ticksUntilJump >= tellyJumpTicks) jump = autoJump.get() || jump;
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
		if (rescueJump) {
			rescueJump = false;
			if (mc.player.onGround() && autoJump.get()) {
				jump = true;
				sneak = false;
			} else {
				rescueTicks = 0;
			}
		}
		// In the air rebuilding the bridge: no sneaking (it would only slow the jump's reach)
		if (rescueTicks > 0 && !mc.player.onGround()) sneak = false;
		if (noSprint.get()) sprint = false;
		if (walkBack.get() && !jumpKey() && (forward || back || left || right)) {
			sprint = false;
			if (mc.player.isSprinting()) mc.player.setSprinting(false);
		}

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
