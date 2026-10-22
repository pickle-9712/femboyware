package dev.femboyware.module.combat;

import dev.femboyware.FemboyWare;
import dev.femboyware.event.PacketEvent;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.util.BlockUtil;
import dev.femboyware.util.DamageCalculator;
import dev.femboyware.util.InventoryUtil;
import dev.femboyware.util.InventoryUtil.SwitchMode;
import dev.femboyware.util.PlaceUtil;
import dev.femboyware.util.RenderUtil;
import dev.femboyware.util.Rotation;
import dev.femboyware.util.Timer;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.sound.SoundEvents;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.RaycastContext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class AutoCrystalModule extends Module {
	private static final int ROTATION_PRIORITY = 100;
	/** Min damage used once face placing kicks in. */
	private static final float FACE_PLACE_DAMAGE = 1.5f;
	private static final double TARGET_RANGE = 12.0;
	/** Milliseconds a fresh base gets to be crystalled before another one may be placed. */
	private static final long BASE_WAIT = 2000;

	public enum Page { MAIN, PLACE, BASE, BREAK, PAUSE, DAMAGES, SWAP, BYPASS, ID_PREDICT, RENDER }
	public enum SpawnAction { ATTACK_THEN_PLACE, ATTACK, NO_REACTION }
	public enum SpawnTiming { PACKET, ADD_TO_WORLD }
	public enum TargetSort { DISTANCE, HEALTH, DAMAGE }
	public enum Interact { DEFAULT, VANILLA, STRICT }
	/** Off never rotates, Tick turns with the movement packet, Packet sends the exact look right before each action. */
	public enum RotateMode { OFF, TICK, PACKET }

	private final EnumSetting<Page> page = add(new EnumSetting<>("Page", "Settings page", Page.MAIN));

	// Main
	private final BoolSetting oneTickAction = add(new BoolSetting("One Tick Action", "Place and attack in the same tick", true)).visibleWhen(() -> page.is(Page.MAIN));
	private final EnumSetting<SpawnAction> onCrystalSpawn = add(new EnumSetting<>("On Crystal Spawn", "What to do as soon as a crystal appears", SpawnAction.ATTACK_THEN_PLACE)).visibleWhen(() -> page.is(Page.MAIN));
	private final EnumSetting<SpawnTiming> spawnTiming = add(new EnumSetting<>("Spawn Timing", "Packet reacts as soon as possible, Add To World is slower but consistent", SpawnTiming.PACKET)).visibleWhen(() -> page.is(Page.MAIN));
	private final BoolSetting instantPlace = add(new BoolSetting("Instant Place", "Place again the moment the server removes a crystal we hit", true)).visibleWhen(() -> page.is(Page.MAIN));
	private final BoolSetting soundSync = add(new BoolSetting("Sound Sync", "Treat crystals near an explosion sound as dead and place again immediately", true)).visibleWhen(() -> page.is(Page.MAIN));
	private final EnumSetting<RotateMode> rotate = add(new EnumSetting<>("Rotate Mode", "Look at what we place on and hit. Packet is instant, Tick waits for the movement packet and is slower", RotateMode.PACKET)).visibleWhen(() -> page.is(Page.MAIN));
	private final BoolSetting rayTrace = add(new BoolSetting("Ray Trace", "Don't place or break through walls", false)).visibleWhen(() -> page.is(Page.MAIN));
	private final NumberSetting degreesPerTick = add(new NumberSetting("Degrees Per Tick", "Rotation speed limit", 180, 10, 180, 5)).visibleWhen(() -> page.is(Page.MAIN) && rotate.is(RotateMode.TICK));
	private final EnumSetting<TargetSort> targetSort = add(new EnumSetting<>("Target Sort", "How targets are prioritised", TargetSort.DAMAGE)).visibleWhen(() -> page.is(Page.MAIN));
	private final NumberSetting extrapolation = add(new NumberSetting("Extrapolation", "Ticks ahead to predict where moving targets will be", 0, 0, 20, 1)).visibleWhen(() -> page.is(Page.MAIN));

	// Place
	private final EnumSetting<Interact> interact = add(new EnumSetting<>("Interact", "Default, Vanilla, or Strict (visible sides only)", Interact.DEFAULT)).visibleWhen(() -> page.is(Page.PLACE));
	private final BoolSetting wallsBypass = add(new BoolSetting("Walls Bypass", "Confuses rotations to place through walls on some servers", false)).visibleWhen(() -> page.is(Page.PLACE));
	private final BoolSetting oldHitbox = add(new BoolSetting("1.12", "Use the old crystal hitbox (needs 2 air blocks)", false)).visibleWhen(() -> page.is(Page.PLACE));
	private final NumberSetting placeDelay = add(new NumberSetting("Place Delay", "Milliseconds between place attempts", 0, 0, 500, 5)).visibleWhen(() -> page.is(Page.PLACE));
	private final NumberSetting facePlaceDelay = add(new NumberSetting("Face Place Delay", "Milliseconds between face place attempts", 150, 0, 1000, 10)).visibleWhen(() -> page.is(Page.PLACE));
	private final NumberSetting placeRange = add(new NumberSetting("Place Range", "Max place distance", 5, 1, 6, 0.1)).visibleWhen(() -> page.is(Page.PLACE));
	private final NumberSetting placeWallRange = add(new NumberSetting("Place Wall Range", "Max place distance when the spot can't be seen", 5, 0, 6, 0.1)).visibleWhen(() -> page.is(Page.PLACE) && !rayTrace.get());

	// Base
	private final BoolSetting basePlace = add(new BoolSetting("Base Place", "Place obsidian where a crystal would hit hardest when there's no good spot", false)).visibleWhen(() -> page.is(Page.BASE));
	private final BoolSetting baseOverFacePlace = add(new BoolSetting("Over Face Place", "Also place a base when face placing is all that's left", true)).visibleWhen(() -> page.is(Page.BASE) && basePlace.get());
	private final NumberSetting baseRadius = add(new NumberSetting("Base Radius", "Blocks around the target's feet to look for base spots", 2, 1, 4, 1)).visibleWhen(() -> page.is(Page.BASE) && basePlace.get());
	private final NumberSetting baseDelay = add(new NumberSetting("Base Delay", "Milliseconds between obsidian places", 100, 0, 1000, 10)).visibleWhen(() -> page.is(Page.BASE) && basePlace.get());
	private final ColorSetting baseColor = add(new ColorSetting("Base Color", "Highlight on newly placed bases", 0x50B077FF)).visibleWhen(() -> page.is(Page.BASE) && basePlace.get());

	// Break
	private final NumberSetting breakDelay = add(new NumberSetting("Break Delay", "Milliseconds between break attempts", 0, 0, 500, 5)).visibleWhen(() -> page.is(Page.BREAK));
	private final NumberSetting breakRange = add(new NumberSetting("Break Range", "Max break distance", 5, 1, 6, 0.1)).visibleWhen(() -> page.is(Page.BREAK));
	private final NumberSetting breakWallRange = add(new NumberSetting("Break Wall Range", "Max break distance when the crystal can't be seen", 5, 0, 6, 0.1)).visibleWhen(() -> page.is(Page.BREAK) && !rayTrace.get());
	private final NumberSetting crystalAge = add(new NumberSetting("Crystal Age", "Ticks a crystal must exist before we hit it. Above 0 turns off instant spawn hits", 0, 0, 20, 1)).visibleWhen(() -> page.is(Page.BREAK));
	private final BoolSetting fastBreak = add(new BoolSetting("Fast Break", "Hit crystals we placed straight from the network thread, before the client even processes the spawn", true)).visibleWhen(() -> page.is(Page.BREAK));
	private final BoolSetting inhibit = add(new BoolSetting("Inhibit", "Crystals we already hit don't block placing and aren't re-hit every tick", true)).visibleWhen(() -> page.is(Page.BREAK));
	private final NumberSetting retryDelay = add(new NumberSetting("Retry Delay", "Milliseconds before re-hitting a crystal that didn't die", 100, 0, 500, 5)).visibleWhen(() -> page.is(Page.BREAK) && inhibit.get());

	// Pause
	private final BoolSetting pauseMining = add(new BoolSetting("Mining", "Pause while mining blocks", true)).visibleWhen(() -> page.is(Page.PAUSE));
	private final BoolSetting pauseEating = add(new BoolSetting("Eating", "Pause while eating", true)).visibleWhen(() -> page.is(Page.PAUSE));
	private final BoolSetting pauseInventory = add(new BoolSetting("Inventory", "Pause while an inventory is open", true)).visibleWhen(() -> page.is(Page.PAUSE));
	private final NumberSetting pauseHealth = add(new NumberSetting("Health", "Pause below this health plus absorption, 0 never pauses", 0, 0, 20, 0.5)).visibleWhen(() -> page.is(Page.PAUSE));

	// Damages
	private final BoolSetting assumeBestArmor = add(new BoolSetting("Assume Best Armor", "Treat enemy armor as Blast Protection IV", false)).visibleWhen(() -> page.is(Page.DAMAGES));
	private final NumberSetting minDamage = add(new NumberSetting("Min Damage", "Ignore positions dealing less than this", 6, 0, 20, 0.5)).visibleWhen(() -> page.is(Page.DAMAGES));
	private final NumberSetting maxSelfDamage = add(new NumberSetting("Max Self Damage", "Ignore positions hurting you more than this", 8, 0, 20, 0.5)).visibleWhen(() -> page.is(Page.DAMAGES));
	private final NumberSetting minDamageFactor = add(new NumberSetting("Min Damage Factor", "Minimum enemy damage / self damage ratio", 1.5, 0, 10, 0.1)).visibleWhen(() -> page.is(Page.DAMAGES));
	private final BoolSetting overrideSelfDamage = add(new BoolSetting("Override Self Damage", "Exceed Max Self Damage when the hit is lethal", true)).visibleWhen(() -> page.is(Page.DAMAGES));
	private final BoolSetting sacrificeTotem = add(new BoolSetting("Sacrifice Totem", "Pop your own totem if it kills a target without one", false)).visibleWhen(() -> page.is(Page.DAMAGES));
	private final NumberSetting armorPercent = add(new NumberSetting("Armor Percent", "Face place when an enemy armor piece is below this durability %", 15, 0, 100, 1)).visibleWhen(() -> page.is(Page.DAMAGES));
	private final NumberSetting facePlaceHealth = add(new NumberSetting("Face Place Health", "Face place when enemy health is below this", 10, 0, 36, 0.5)).visibleWhen(() -> page.is(Page.DAMAGES));
	private final BoolSetting destructibleTerrain = add(new BoolSetting("Destructible Terrain", "Explosions break blocks on this server", false)).visibleWhen(() -> page.is(Page.DAMAGES));

	// Swap
	private final BoolSetting gappleSwitch = add(new BoolSetting("Gapple Switch", "Hold right click while main-handing crystals to swap to a gapple", true)).visibleWhen(() -> page.is(Page.SWAP));
	private final EnumSetting<SwitchMode> switchMode = add(new EnumSetting<>("Switch", "How to switch to crystals", SwitchMode.SILENT)).visibleWhen(() -> page.is(Page.SWAP));
	private final EnumSetting<SwitchMode> antiWeakness = add(new EnumSetting<>("Anti Weakness", "Switch to a weapon to break crystals while weakened", SwitchMode.SILENT)).visibleWhen(() -> page.is(Page.SWAP));

	// Bypass
	private final BoolSetting noMultiActions = add(new BoolSetting("No Multi Actions", "Don't place or break while using any item, which Grim flags", false)).visibleWhen(() -> page.is(Page.BYPASS));

	// ID predict
	private final BoolSetting idPredict = add(new BoolSetting("Id Predict", "Attack crystals before they spawn", false)).visibleWhen(() -> page.is(Page.ID_PREDICT));
	private final NumberSetting idAttacks = add(new NumberSetting("Id Attacks", "Predict attempts per place", 1, 1, 5, 1)).visibleWhen(() -> page.is(Page.ID_PREDICT) && idPredict.get());
	private final NumberSetting idDelay = add(new NumberSetting("Id Delay", "Milliseconds between predict attempts", 0, 0, 500, 5)).visibleWhen(() -> page.is(Page.ID_PREDICT) && idPredict.get());
	private final BoolSetting predictOnlyInDuels = add(new BoolSetting("Predict Only In Duels", "Only predict when you and one other player are in the world", false)).visibleWhen(() -> page.is(Page.ID_PREDICT) && idPredict.get());

	// Render
	private final BoolSetting render = add(new BoolSetting("Render", "Highlight the place position", true)).visibleWhen(() -> page.is(Page.RENDER));
	private final ColorSetting fillColor = add(new ColorSetting("Fill", "Box fill colour", 0x50FF77C8)).visibleWhen(() -> page.is(Page.RENDER) && render.get());
	private final ColorSetting lineColor = add(new ColorSetting("Line", "Box outline colour", 0xFFFF77C8)).visibleWhen(() -> page.is(Page.RENDER) && render.get());
	private final StringSetting textPattern = add(new StringSetting("Text Pattern", "{damage} and {self} are replaced by the damage numbers", "{damage} ({self})")).visibleWhen(() -> page.is(Page.RENDER) && render.get());

	private final Timer placeTimer = new Timer();
	private final Timer breakTimer = new Timer();
	private final Timer idTimer = new Timer();
	private final Timer baseTimer = new Timer();
	private final AtomicInteger highestEntityId = new AtomicInteger(-1);
	/** Crystal entity id to the time we last attacked it. */
	private final Map<Integer, Long> attacked = new ConcurrentHashMap<>();
	/** Block a crystal of ours should appear in, to the time we placed it. */
	private final Map<BlockPos, Long> placed = new ConcurrentHashMap<>();

	// Snapshot of main thread state for Fast Break, which runs on the network thread
	private volatile boolean fastBreakReady;
	private volatile Vec3d cachedEyes = Vec3d.ZERO;
	private volatile boolean cachedSneaking;
	private volatile boolean cachedOnGround;
	private volatile boolean cachedCollision;
	private volatile float cachedServerYaw;

	/** Where each target is expected to be after Extrapolation ticks, refreshed with the target list. */
	private final Map<PlayerEntity, Box> predictedBoxes = new HashMap<>();
	/** When each recent place went out, for the crystals per second readout. */
	private final ArrayDeque<Long> placeTimes = new ArrayDeque<>();

	private PlayerEntity target;
	private PlacePlan pendingPlace;
	/** This tick's targets, for placing a base after the movement packet. */
	private List<PlayerEntity> baseTargets;
	private BlockPos basePos;
	private long baseTime;
	private BreakPlan pendingBreak;

	private BlockPos renderPos;
	private float renderDamage;
	private float renderSelf;
	private long renderTime;
	private int gappleReturnSlot = -1;

	private record PlacePlan(BlockPos base, Rotation rotation, float damage, float self, boolean facePlace) {}

	private record BreakPlan(EndCrystalEntity crystal, Rotation rotation, float damage, float self) {}

	private record Evaluation(float damage, float self, boolean facePlace) {}

	public AutoCrystalModule() {
		super("Auto Crystal", "Automatically places and breaks end crystals", Category.ATTACK);
		ClientEntityEvents.ENTITY_LOAD.register((entity, world) -> {
			if (isEnabled() && !nullCheck() && entity instanceof EndCrystalEntity && spawnTiming.is(SpawnTiming.ADD_TO_WORLD)) {
				onCrystalSpawned(entity.getId(), entity.getEntityPos());
			}
		});
	}

	@Override
	public String getInfo() {
		if (target == null) return null;
		long now = System.currentTimeMillis();
		while (!placeTimes.isEmpty() && now - placeTimes.peekFirst() > 1000) placeTimes.pollFirst();
		return target.getName().getString() + " " + placeTimes.size() + " c/s";
	}

	@Override
	protected void onDisable() {
		target = null;
		pendingPlace = null;
		pendingBreak = null;
		renderPos = null;
		attacked.clear();
		placed.clear();
		predictedBoxes.clear();
		placeTimes.clear();
		baseTargets = null;
		basePos = null;
		fastBreakReady = false;
		if (gappleReturnSlot != -1 && !nullCheck()) InventoryUtil.select(gappleReturnSlot);
		gappleReturnSlot = -1;
	}

	@Override
	public void onTick() {
		long now = System.currentTimeMillis();
		attacked.values().removeIf(time -> now - time > 1000);
		placed.values().removeIf(time -> now - time > 1000);
		handleGappleSwitch();
	}

	/**
	 * Snapshots the state the server now has for us, for Fast Break on the network thread. Taken after
	 * the movement packet rather than at the start of the tick, so it doesn't lag a tick behind while
	 * moving.
	 */
	private void snapshotForFastBreak() {
		cachedEyes = mc.player.getEyePos();
		cachedSneaking = mc.player.isSneaking();
		cachedOnGround = mc.player.isOnGround();
		cachedCollision = mc.player.horizontalCollision;
		cachedServerYaw = FemboyWare.getRotations().getServerYaw();
		// A weakness swap or a tick rotation has to go through the main thread
		fastBreakReady = fastBreak.get() && !rotate.is(RotateMode.TICK) && !onCrystalSpawn.is(SpawnAction.NO_REACTION)
			&& crystalAge.getInt() == 0 && !shouldPause() && !isWeakened();
	}

	/**
	 * Runs a place attempt right now instead of waiting for the next tick. Used when a crystal dies
	 * or another module opens up a new spot.
	 */
	public void placeNow() {
		if (!isEnabled() || nullCheck() || shouldPause() || !hasCrystals()) return;
		List<PlayerEntity> targets = findTargets();
		if (targets.isEmpty()) return;
		PlacePlan plan = findPlace(targets, null);
		if (plan != null) tryPlace(plan);
	}

	@Override
	public void onPreMotion() {
		pendingPlace = null;
		pendingBreak = null;
		if (shouldPause()) return;

		List<PlayerEntity> targets = findTargets();
		if (targets.isEmpty()) {
			target = null;
			return;
		}

		pendingBreak = findBreak(targets);
		boolean canPlaceThisTick = pendingBreak == null || oneTickAction.get();
		if (canPlaceThisTick && hasCrystals()) pendingPlace = findPlace(targets, pendingBreak);
		baseTargets = targets;

		// Packet mode turns per action, but facing the plan already often saves the extra packet
		if (rotate.is(RotateMode.OFF)) return;
		Rotation wanted = pendingBreak != null ? pendingBreak.rotation : pendingPlace != null ? pendingPlace.rotation : null;
		if (wanted != null) FemboyWare.getRotations().request(wanted, ROTATION_PRIORITY, degreesPerTick.getFloat(), false);
	}

	@Override
	public void onPostMotion() {
		if (pendingBreak != null && pendingBreak.crystal.isAlive() && breakTimer.passed(breakDelay.get())) {
			Box box = pendingBreak.crystal.getBoundingBox();
			if (canHit(box, box.getCenter())) {
				breakCrystal(pendingBreak.crystal);
				breakTimer.reset();
			}
		}

		// No spot at all, or only a face place left: a proper base may do much better
		boolean wantBase = pendingBreak == null && (pendingPlace == null || (pendingPlace.facePlace && baseOverFacePlace.get()));
		if (!(wantBase && tryBasePlace()) && pendingPlace != null) tryPlace(pendingPlace);
		snapshotForFastBreak();
	}

	// ---------------------------------------------------------------- base place

	/**
	 * Places obsidian where a crystal would hit hardest, when there's no spot good enough already,
	 * then puts a crystal on it straight away. The client shows the obsidian as soon as the place
	 * packet goes out, so the crystal can follow in the same tick.
	 */
	private boolean tryBasePlace() {
		if (!basePlace.get() || baseTargets == null || baseTargets.isEmpty() || !hasCrystals() || !hasObsidian()) return false;
		if (!baseTimer.passed(baseDelay.get())) return false;
		// Give the last base a chance to be used before spending more obsidian
		if (basePos != null && System.currentTimeMillis() - baseTime < BASE_WAIT && mc.world.getBlockState(basePos).isOf(Blocks.OBSIDIAN)
			&& mc.world.isAir(basePos.up())) return false;
		BlockPos base = findBase(baseTargets);
		if (base == null) return false;
		BlockHitResult hit = BlockUtil.getPlacement(base, interact.is(Interact.STRICT));
		if (hit == null) return false;
		if (!rotate.is(RotateMode.OFF)) FemboyWare.getRotations().sendPacketRotation(Rotation.between(mc.player.getEyePos(), hit.getPos()));
		if (!PlaceUtil.interact(hit, PlaceUtil.OBSIDIAN, switchMode.get(), false)) return false;
		baseTimer.reset();
		basePos = base;
		baseTime = System.currentTimeMillis();

		PlacePlan plan = planAt(base, baseTargets, null);
		if (plan != null) tryPlace(plan);
		return true;
	}

	/** The empty spot where obsidian would give the best crystal, judged by the same rules as any other place. */
	private BlockPos findBase(List<PlayerEntity> targets) {
		BlockPos best = null;
		float bestDamage = 0;
		float bestSelf = Float.MAX_VALUE;
		Set<BlockPos> checked = new HashSet<>();
		int radius = baseRadius.getInt();
		for (PlayerEntity player : targets) {
			// Search around where they're heading, not just where they stand
			Box box = predictedBoxes.getOrDefault(player, player.getBoundingBox());
			BlockPos feet = BlockPos.ofFloored(box.getCenter().x, box.minY, box.getCenter().z);
			for (BlockPos pos : BlockPos.iterate(feet.add(-radius, -2, -radius), feet.add(radius, 0, radius))) {
				BlockPos base = pos.toImmutable();
				if (!checked.add(base) || !canBaseAt(base, targets)) continue;
				Evaluation evaluation = evaluate(targets, Vec3d.ofBottomCenter(base.up()), base);
				// A base has to beat face placing to be worth the obsidian
				if (evaluation == null || evaluation.facePlace) continue;
				if (evaluation.damage > bestDamage || (evaluation.damage == bestDamage && evaluation.self < bestSelf)) {
					best = base;
					bestDamage = evaluation.damage;
					bestSelf = evaluation.self;
				}
			}
		}
		return best;
	}

	/** Obsidian can go at {@code base} and a crystal could then be placed on it and reached. */
	private boolean canBaseAt(BlockPos base, List<PlayerEntity> targets) {
		if (!PlaceUtil.canPlace(base, true)) return false;
		for (PlayerEntity player : targets) {
			if (predictedBoxes.getOrDefault(player, player.getBoundingBox()).intersects(new Box(base))) return false;
		}
		BlockPos up = base.up();
		if (!mc.world.isAir(up) || (oldHitbox.get() && !mc.world.isAir(up.up()))) return false;
		Box space = new Box(up.getX(), up.getY(), up.getZ(), up.getX() + 1, up.getY() + 2, up.getZ() + 1);
		for (Entity entity : mc.world.getOtherEntities(null, space)) {
			if (entity.isAlive()) return false;
		}
		Vec3d eyes = mc.player.getEyePos();
		Vec3d top = Vec3d.ofCenter(base).add(0, 0.5, 0);
		double distance = eyes.distanceTo(top);
		if (distance > placeRange.get()) return false;
		if (rayTrace.get() || distance > placeWallRange.get()) {
			if (!BlockUtil.canSee(eyes, top)) return false;
		}
		return BlockUtil.getPlacement(base, interact.is(Interact.STRICT)) != null;
	}

	private boolean hasObsidian() {
		return PlaceUtil.OBSIDIAN.test(mc.player.getOffHandStack()) || InventoryUtil.findHotbar(PlaceUtil.OBSIDIAN) != -1;
	}

	/**
	 * Places {@code plan} if its delay is up. The player has moved since the plan was made, so the
	 * click and the look are worked out again from where the eyes are now.
	 */
	private void tryPlace(PlacePlan plan) {
		double delay = plan.facePlace ? facePlaceDelay.get() : placeDelay.get();
		if (!placeTimer.passed(delay)) return;
		BlockHitResult hit = getPlaceHit(plan.base);
		if (hit == null || !canHit(new Box(plan.base), hit.getPos())) return;
		doPlace(plan, hit);
	}

	private void doPlace(PlacePlan plan, BlockHitResult hit) {
		if (!placeCrystal(hit)) return;
		placeTimer.reset();
		placed.put(plan.base.up(), System.currentTimeMillis());
		placeTimes.addLast(System.currentTimeMillis());
		renderPos = plan.base;
		renderDamage = plan.damage;
		renderSelf = plan.self;
		renderTime = System.currentTimeMillis();
		if (idPredict.get()) predictIds(plan.base);
	}

	@Override
	public void onPacketReceive(PacketEvent event) {
		if (event.getPacket() instanceof EntitiesDestroyS2CPacket packet) {
			if (!instantPlace.get()) return;
			for (int id : packet.getEntityIds()) {
				if (attacked.containsKey(id)) {
					mc.execute(this::placeNow);
					return;
				}
			}
			return;
		}
		if (event.getPacket() instanceof PlaySoundS2CPacket packet) {
			if (!soundSync.get() || packet.getSound().value() != SoundEvents.ENTITY_GENERIC_EXPLODE.value()) return;
			Vec3d pos = new Vec3d(packet.getX(), packet.getY(), packet.getZ());
			mc.execute(() -> {
				if (!isEnabled() || nullCheck()) return;
				for (Entity entity : mc.world.getEntities()) {
					if (entity instanceof EndCrystalEntity && entity.squaredDistanceTo(pos) < 36) attacked.put(entity.getId(), System.currentTimeMillis());
				}
				placeNow();
			});
			return;
		}
		if (!(event.getPacket() instanceof EntitySpawnS2CPacket packet)) return;
		highestEntityId.accumulateAndGet(packet.getEntityId(), Math::max);
		if (packet.getEntityType() == EntityType.END_CRYSTAL && spawnTiming.is(SpawnTiming.PACKET)) {
			int id = packet.getEntityId();
			Vec3d pos = new Vec3d(packet.getX(), packet.getY(), packet.getZ());
			boolean broken = fastBreak(id, pos);
			// Queued ahead of the spawn packet itself, so this still runs before the entity is added
			mc.execute(() -> {
				if (!isEnabled() || nullCheck()) return;
				if (!broken) {
					onCrystalSpawned(id, pos);
					return;
				}
				if (!onCrystalSpawn.is(SpawnAction.ATTACK_THEN_PLACE) || shouldPause()) return;
				List<PlayerEntity> targets = findTargets();
				if (!targets.isEmpty()) replaceAfterBreak(pos, targets);
			});
		}
	}

	/**
	 * Hits a crystal we placed ourselves straight from the network thread, saving the wait until the
	 * main thread gets round to the spawn. Our own placements already passed the damage checks, so
	 * only cheap checks on state snapshotted each tick run here.
	 */
	private boolean fastBreak(int id, Vec3d pos) {
		if (!fastBreakReady || !breakTimer.passed(breakDelay.get())) return false;
		Long time = placed.get(BlockPos.ofFloored(pos));
		if (time == null || System.currentTimeMillis() - time > 1000) return false;
		Vec3d eyes = cachedEyes;
		Box box = crystalBox(pos);
		if (eyes.distanceTo(BlockUtil.closestPoint(box, eyes)) > breakRange.get()) return false;

		Rotation look = null;
		if (rotate.is(RotateMode.PACKET)) {
			Rotation exact = Rotation.between(eyes, new Vec3d(pos.x, pos.y + 1, pos.z));
			// Keep the yaw continuous with what the server last had, like real mouse movement
			float yaw = cachedServerYaw + MathHelper.wrapDegrees(exact.yaw() - cachedServerYaw);
			look = new Rotation(yaw, exact.pitch());
			mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(look.yaw(), look.pitch(), cachedOnGround, cachedCollision));
			cachedServerYaw = yaw;
		}
		mc.getNetworkHandler().sendPacket(attackPacket(id, cachedSneaking));
		mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
		attacked.put(id, System.currentTimeMillis());
		breakTimer.reset();
		Rotation sent = look;
		mc.execute(() -> {
			if (nullCheck()) return;
			if (sent != null) FemboyWare.getRotations().notePacketRotation(sent);
			mc.player.swingHand(Hand.MAIN_HAND, false);
		});
		return true;
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (basePos != null && System.currentTimeMillis() - baseTime <= 500) {
			RenderUtil.drawBox(event, new Box(basePos), baseColor.get(), baseColor.withAlpha(255), 1.5f);
		}
		if (!render.get() || renderPos == null) return;
		long age = System.currentTimeMillis() - renderTime;
		if (age > 1000) {
			renderPos = null;
			return;
		}
		float fade = 1f - Math.max(0f, (age - 500) / 500f);
		int fill = fillColor.withAlpha((int) (fillColor.getAlpha() * fade));
		int line = lineColor.withAlpha((int) (lineColor.getAlpha() * fade));
		RenderUtil.drawBox(event, new Box(renderPos), fill, line, 2f);

		String text = textPattern.get()
			.replace("{damage}", String.format("%.1f", renderDamage))
			.replace("{self}", String.format("%.1f", renderSelf));
		RenderUtil.drawText(event, text, Vec3d.ofCenter(renderPos), 0xFFFFFFFF, 0.4f);
	}

	// ---------------------------------------------------------------- spawn reaction

	private void onCrystalSpawned(int id, Vec3d pos) {
		if (onCrystalSpawn.is(SpawnAction.NO_REACTION) || crystalAge.getInt() > 0 || shouldPause() || !breakTimer.passed(breakDelay.get())) return;
		List<PlayerEntity> targets = findTargets();
		if (targets.isEmpty()) return;

		Vec3d eyes = mc.player.getEyePos();
		Box box = crystalBox(pos);
		if (!inBreakRange(eyes, box)) return;
		Evaluation evaluation = evaluate(targets, pos);
		if (evaluation == null) return;

		// Tick mode can't turn mid-tick, so it only reacts instantly when already facing it
		if (!canHit(box, box.getCenter())) return;
		if (!attackById(id, pos)) return;
		breakTimer.reset();

		// The crystal we just hit counts as gone, so its own spot is a valid place target again
		if (onCrystalSpawn.is(SpawnAction.ATTACK_THEN_PLACE)) replaceAfterBreak(pos, targets);
	}

	/**
	 * Places back where the crystal we just hit stood. That spot is nearly always still good, and
	 * checking one position is far cheaper than searching the whole range, so the place goes out in
	 * the same burst as the attack. Falls back to a full search.
	 */
	private void replaceAfterBreak(Vec3d crystalPos, List<PlayerEntity> targets) {
		if (!hasCrystals()) return;
		PlacePlan plan = planAt(BlockPos.ofFloored(crystalPos).down(), targets, null);
		if (plan == null) {
			placeNow();
			return;
		}
		tryPlace(plan);
	}

	// ---------------------------------------------------------------- planning

	private boolean shouldPause() {
		if (pauseMining.get() && mc.interactionManager.isBreakingBlock()) return true;
		if (pauseEating.get() && mc.player.isUsingItem() && mc.player.getActiveItem().contains(DataComponentTypes.FOOD)) return true;
		if (noMultiActions.get() && mc.player.isUsingItem()) return true;
		if (pauseHealth.get() > 0 && mc.player.getHealth() + mc.player.getAbsorptionAmount() < pauseHealth.get()) return true;
		return pauseInventory.get() && mc.currentScreen instanceof HandledScreen<?>;
	}

	/** Within Break Range, or Break Wall Range when the crystal is behind a wall. */
	private boolean inBreakRange(Vec3d eyes, Box box) {
		double distance = eyes.distanceTo(BlockUtil.closestPoint(box, eyes));
		if (distance > breakRange.get()) return false;
		// Only pay for the raycast when it can change the answer
		if (!rayTrace.get() && distance <= breakWallRange.get()) return true;
		return BlockUtil.canSee(eyes, box.getCenter());
	}

	/**
	 * Where {@code player} will stand after Extrapolation ticks if they keep moving the same way.
	 * Horizontal only, stopping at walls, like ThunderHack's PredictUtility.
	 */
	private Box predictBox(PlayerEntity player) {
		Box box = player.getBoundingBox();
		int ticks = extrapolation.getInt();
		if (ticks == 0) return box;
		Vec3d pos = player.getEntityPos();
		Vec3d start = pos;
		double motionX = player.getVelocity().x;
		double motionZ = player.getVelocity().z;
		for (int i = 0; i < ticks; i++) {
			double edgeX = motionX > 0 ? 0.3 : -0.3;
			double edgeZ = motionZ > 0 ? 0.3 : -0.3;
			if (!mc.world.isAir(BlockPos.ofFloored(pos.add(motionX + edgeX, 0.1, motionZ + edgeZ)))
				|| !mc.world.isAir(BlockPos.ofFloored(pos.add(motionX + edgeX, 1, motionZ + edgeZ)))) break;
			pos = pos.add(motionX, 0, motionZ);
		}
		return box.offset(pos.subtract(start));
	}

	private float targetDamage(PlayerEntity player, Vec3d crystal) {
		return targetDamage(player, crystal, null);
	}

	private float targetDamage(PlayerEntity player, Vec3d crystal, BlockPos assumedSolid) {
		Box box = predictedBoxes.getOrDefault(player, player.getBoundingBox());
		return DamageCalculator.explosionDamage(player, box, crystal, DamageCalculator.CRYSTAL_POWER, destructibleTerrain.get(), assumeBestArmor.get(), assumedSolid);
	}

	private static boolean holdsTotem(PlayerEntity player) {
		return player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING) || player.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING);
	}

	private List<PlayerEntity> findTargets() {
		List<PlayerEntity> targets = new ArrayList<>();
		for (PlayerEntity player : mc.world.getPlayers()) {
			if (player == mc.player || !player.isAlive() || player.isSpectator() || player.isCreative()) continue;
			if (player.squaredDistanceTo(mc.player) > TARGET_RANGE * TARGET_RANGE) continue;
			targets.add(player);
		}
		switch (targetSort.get()) {
			case DISTANCE, DAMAGE -> targets.sort(Comparator.comparingDouble(p -> p.squaredDistanceTo(mc.player)));
			case HEALTH -> targets.sort(Comparator.comparingDouble(p -> p.getHealth() + p.getAbsorptionAmount()));
		}
		// Only DAMAGE sort weighs every target; the others commit to the first one
		if (!targetSort.is(TargetSort.DAMAGE) && targets.size() > 1) targets = targets.subList(0, 1);
		predictedBoxes.clear();
		for (PlayerEntity player : targets) predictedBoxes.put(player, predictBox(player));
		return targets;
	}

	private BreakPlan findBreak(List<PlayerEntity> targets) {
		Vec3d eyes = mc.player.getEyePos();
		BreakPlan best = null;
		for (Entity entity : mc.world.getEntities()) {
			if (!(entity instanceof EndCrystalEntity crystal) || !crystal.isAlive()) continue;
			if (inhibit.get() && wasAttacked(crystal.getId(), retryDelay.get())) continue;
			if (crystal.age < crystalAge.getInt()) continue;
			Box box = crystal.getBoundingBox();
			if (!inBreakRange(eyes, box)) continue;
			Evaluation evaluation = evaluate(targets, crystal.getEntityPos());
			if (evaluation == null) continue;
			if (best == null || evaluation.damage > best.damage) {
				best = new BreakPlan(crystal, Rotation.between(eyes, box.getCenter()), evaluation.damage, evaluation.self);
			}
		}
		if (best != null) target = bestTarget(targets, best.crystal.getEntityPos());
		return best;
	}

	private PlacePlan findPlace(List<PlayerEntity> targets, BreakPlan breaking) {
		BlockPos origin = BlockPos.ofFloored(mc.player.getEyePos());
		int radius = MathHelper.ceil(placeRange.get());
		PlacePlan best = null;

		for (int x = -radius; x <= radius; x++) {
			for (int y = -radius; y <= radius; y++) {
				for (int z = -radius; z <= radius; z++) {
					PlacePlan plan = planAt(origin.add(x, y, z), targets, breaking);
					if (plan == null) continue;
					if (best == null || plan.damage > best.damage || (plan.damage == best.damage && plan.self < best.self)) best = plan;
				}
			}
		}
		if (best != null) target = bestTarget(targets, Vec3d.ofBottomCenter(best.base.up()));
		return best;
	}

	/** A place on {@code base}, or null if it can't be placed on or fails the damage rules. */
	private PlacePlan planAt(BlockPos base, List<PlayerEntity> targets, BreakPlan breaking) {
		if (!canPlaceCrystal(base, breaking)) return null;
		Vec3d eyes = mc.player.getEyePos();
		BlockHitResult hit = getPlaceHit(base);
		if (hit == null) return null;
		double distance = eyes.distanceTo(hit.getPos());
		if (distance > placeRange.get()) return null;
		if (distance > placeWallRange.get() && !isVisible(eyes, hit.getPos(), base)) return null;

		Evaluation evaluation = evaluate(targets, Vec3d.ofBottomCenter(base.up()));
		if (evaluation == null) return null;
		return new PlacePlan(base, Rotation.between(eyes, hit.getPos()), evaluation.damage, evaluation.self, evaluation.facePlace);
	}

	/** Best damage this crystal position deals to any target, or null if it fails the damage rules. */
	private Evaluation evaluate(List<PlayerEntity> targets, Vec3d crystal) {
		return evaluate(targets, crystal, null);
	}

	/** {@code assumedSolid} counts as a block in the blast, for an obsidian base not placed yet. */
	private Evaluation evaluate(List<PlayerEntity> targets, Vec3d crystal, BlockPos assumedSolid) {
		float self = DamageCalculator.explosionDamage(mc.player, mc.player.getBoundingBox(), crystal, DamageCalculator.CRYSTAL_POWER,
			destructibleTerrain.get(), false, assumedSolid);
		float selfHealth = mc.player.getHealth() + mc.player.getAbsorptionAmount();
		boolean selfLethal = self >= selfHealth - 0.5f;
		if (selfLethal && !(sacrificeTotem.get() && holdsTotem(mc.player))) return null;

		Evaluation best = null;
		for (PlayerEntity player : targets) {
			float damage = targetDamage(player, crystal, assumedSolid);
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

	private boolean shouldFacePlace(PlayerEntity player) {
		if (player.getHealth() + player.getAbsorptionAmount() <= facePlaceHealth.get()) return true;
		for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			ItemStack armor = player.getEquippedStack(slot);
			if (armor.isEmpty() || !armor.isDamageable()) continue;
			float percent = 100f * (armor.getMaxDamage() - armor.getDamage()) / armor.getMaxDamage();
			if (percent < armorPercent.get()) return true;
		}
		return false;
	}

	private PlayerEntity bestTarget(List<PlayerEntity> targets, Vec3d crystal) {
		PlayerEntity best = null;
		float bestDamage = -1;
		for (PlayerEntity player : targets) {
			float damage = targetDamage(player, crystal);
			if (damage > bestDamage) {
				bestDamage = damage;
				best = player;
			}
		}
		return best;
	}

	private boolean canPlaceCrystal(BlockPos base, BreakPlan breaking) {
		BlockState state = mc.world.getBlockState(base);
		if (!state.isOf(Blocks.OBSIDIAN) && !state.isOf(Blocks.BEDROCK)) return false;
		BlockPos up = base.up();
		if (!mc.world.isAir(up)) return false;
		if (oldHitbox.get() && !mc.world.isAir(up.up())) return false;

		Box space = new Box(up.getX(), up.getY(), up.getZ(), up.getX() + 1, up.getY() + 2, up.getZ() + 1);
		for (Entity entity : mc.world.getOtherEntities(null, space)) {
			if (!entity.isAlive()) continue;
			// The crystal we're breaking this tick will be gone by the time the place packet lands
			if (oneTickAction.get() && breaking != null && entity == breaking.crystal) continue;
			if (inhibit.get() && entity instanceof EndCrystalEntity && wasAttacked(entity.getId(), 1000)) continue;
			return false;
		}
		return true;
	}

	private boolean wasAttacked(int id, double withinMillis) {
		Long time = attacked.get(id);
		return time != null && System.currentTimeMillis() - time < withinMillis;
	}

	private BlockHitResult getPlaceHit(BlockPos base) {
		Vec3d eyes = mc.player.getEyePos();
		BlockHitResult hit = switch (interact.get()) {
			case VANILLA -> new BlockHitResult(Vec3d.ofCenter(base).add(0, 0.5, 0), Direction.UP, base, false);
			case DEFAULT -> rotate.is(RotateMode.OFF) ? closestFace(base, eyes, false) : aimedHit(base, eyes);
			case STRICT -> closestFace(base, eyes, true);
		};

		boolean visible = hit != null && isVisible(eyes, hit.getPos(), base);
		if ((hit == null || (rayTrace.get() && !visible)) && wallsBypass.get()) {
			// Click the underside: aim and face don't line up, which some ray checks don't verify
			return new BlockHitResult(Vec3d.ofCenter(base).add(0, -0.5, 0), Direction.DOWN, base, false);
		}
		if (hit == null || (rayTrace.get() && !visible)) return null;
		return hit;
	}

	/**
	 * Clicks where a look at the block's centre enters it. The server rotation, face and hit position
	 * then all agree, so look checks pass from any angle, whichever face that turns out to be.
	 */
	private BlockHitResult aimedHit(BlockPos base, Vec3d eyes) {
		Vec3d end = eyes.add(Vec3d.ofCenter(base).subtract(eyes).multiply(2));
		BlockHitResult hit = VoxelShapes.fullCube().raycast(eyes, end, base);
		return hit != null && hit.getType() == HitResult.Type.BLOCK ? hit : null;
	}

	private BlockHitResult closestFace(BlockPos base, Vec3d eyes, boolean strict) {
		BlockHitResult best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Direction face : Direction.values()) {
			if (strict && !BlockUtil.isFaceExposed(base, face, eyes)) continue;
			Vec3d point = BlockUtil.faceCenter(base, face);
			double distance = eyes.squaredDistanceTo(point);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = new BlockHitResult(point, face, base, false);
			}
		}
		return best;
	}

	private boolean isVisible(Vec3d eyes, Vec3d point, BlockPos pos) {
		BlockHitResult hit = mc.world.raycast(new RaycastContext(eyes, point, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));
		return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos);
	}

	/**
	 * Whether the rotation the server has for us points into {@code box}. Packet mode turns to
	 * {@code aim} first when it doesn't, aiming from the current eyes rather than a rotation worked
	 * out earlier in the tick, which would miss once the player has moved.
	 */
	private boolean canHit(Box box, Vec3d aim) {
		if (rotate.is(RotateMode.OFF) || isAimedAt(box)) return true;
		if (!rotate.is(RotateMode.PACKET)) return false;
		FemboyWare.getRotations().sendPacketRotation(Rotation.between(mc.player.getEyePos(), aim));
		return isAimedAt(box);
	}

	/** Exact hitbox, no padding: a look that only grazes a padded box gets the hit rejected server side. */
	private boolean isAimedAt(Box box) {
		Vec3d eyes = mc.player.getEyePos();
		if (box.contains(eyes)) return true;
		Vec3d look = FemboyWare.getRotations().getServerRotation().toVector().multiply(8);
		return box.raycast(eyes, eyes.add(look)).isPresent();
	}

	private static Box crystalBox(Vec3d pos) {
		return new Box(pos.x - 1, pos.y, pos.z - 1, pos.x + 1, pos.y + 2, pos.z + 1);
	}

	// ---------------------------------------------------------------- actions

	private boolean hasCrystals() {
		if (mc.player.getOffHandStack().isOf(Items.END_CRYSTAL) || mc.player.getMainHandStack().isOf(Items.END_CRYSTAL)) return true;
		return !switchMode.is(SwitchMode.NONE) && InventoryUtil.find(stack -> stack.isOf(Items.END_CRYSTAL), switchMode.get()) != -1;
	}

	private boolean placeCrystal(BlockHitResult hit) {
		if (mc.player.getOffHandStack().isOf(Items.END_CRYSTAL)) {
			interactBlock(Hand.OFF_HAND, hit);
			return true;
		}
		int slot = InventoryUtil.find(stack -> stack.isOf(Items.END_CRYSTAL), switchMode.get());
		return InventoryUtil.withSlot(slot, switchMode.get(), () -> interactBlock(Hand.MAIN_HAND, hit));
	}

	private void interactBlock(Hand hand, BlockHitResult hit) {
		mc.interactionManager.interactBlock(mc.player, hand, hit);
		mc.player.swingHand(hand);
	}

	private void breakCrystal(EndCrystalEntity crystal) {
		boolean hit = withWeakness(() -> {
			mc.interactionManager.attackEntity(mc.player, crystal);
			mc.player.swingHand(Hand.MAIN_HAND);
		});
		if (hit) attacked.put(crystal.getId(), System.currentTimeMillis());
	}

	/** Attacks an entity id directly, which works even before the client has added the entity. */
	private boolean attackById(int id, Vec3d pos) {
		boolean hit = withWeakness(() -> {
			mc.getNetworkHandler().sendPacket(attackPacket(id, mc.player.isSneaking()));
			mc.player.swingHand(Hand.MAIN_HAND);
		});
		if (hit) attacked.put(id, System.currentTimeMillis());
		return hit;
	}

	/** Attack packet for a bare entity id. The constructor is private, so it goes through the codec. */
	private static PlayerInteractEntityC2SPacket attackPacket(int id, boolean sneaking) {
		PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
		try {
			buf.writeVarInt(id);
			buf.writeVarInt(1); // InteractType.ATTACK
			buf.writeBoolean(sneaking);
			return PlayerInteractEntityC2SPacket.CODEC.decode(buf);
		} finally {
			buf.release();
		}
	}

	private void predictIds(BlockPos base) {
		if (!idTimer.passed(idDelay.get()) || highestEntityId.get() < 0) return;
		if (predictOnlyInDuels.get() && mc.world.getPlayers().size() != 2) return;
		Vec3d pos = Vec3d.ofBottomCenter(base.up());
		int start = highestEntityId.get() + 1;
		for (int i = 0; i < idAttacks.getInt(); i++) {
			attackById(start + i, pos);
		}
		idTimer.reset();
	}

	private boolean withWeakness(Runnable attack) {
		if (!isWeakened()) {
			attack.run();
			return true;
		}
		int slot = InventoryUtil.find(stack -> stack.isIn(ItemTags.SWORDS) || stack.isIn(ItemTags.AXES), antiWeakness.get());
		return InventoryUtil.withSlot(slot, antiWeakness.get(), attack);
	}

	private boolean isWeakened() {
		StatusEffectInstance weakness = mc.player.getStatusEffect(StatusEffects.WEAKNESS);
		if (weakness == null) return false;
		StatusEffectInstance strength = mc.player.getStatusEffect(StatusEffects.STRENGTH);
		// Strength II outdoes Weakness I
		return strength == null || strength.getAmplifier() <= weakness.getAmplifier();
	}

	private void handleGappleSwitch() {
		if (!gappleSwitch.get()) return;
		boolean using = mc.options.useKey.isPressed() && mc.currentScreen == null;
		if (using && gappleReturnSlot == -1 && mc.player.getMainHandStack().isOf(Items.END_CRYSTAL)) {
			int slot = InventoryUtil.findHotbar(stack -> stack.isOf(Items.ENCHANTED_GOLDEN_APPLE) || stack.isOf(Items.GOLDEN_APPLE));
			if (slot != -1) {
				gappleReturnSlot = mc.player.getInventory().getSelectedSlot();
				InventoryUtil.select(slot);
			}
		} else if (!using && gappleReturnSlot != -1) {
			InventoryUtil.select(gappleReturnSlot);
			gappleReturnSlot = -1;
		}
	}
}
