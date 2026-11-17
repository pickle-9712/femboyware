package dev.femboyware.module.combat;

import dev.femboyware.FemboyWare;
import dev.femboyware.event.PacketEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.DamageCalculator;
import dev.femboyware.util.Grim;
import dev.femboyware.util.InventoryUtil;
import dev.femboyware.util.InventoryUtil.SwitchMode;
import dev.femboyware.util.Mc;
import dev.femboyware.util.Rotation;
import dev.femboyware.util.TargetUtil;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;

/**
 * Hit crystal, done for you without touching your camera: once someone's close, obsidian goes down
 * next to them where a crystal hurts most, and crystals go on it and get popped as fast as the
 * server takes them, every tick and the moment each one spawns. Looks go to the server only and
 * switches are silent (back on your slot the same tick), so your view and hotbar stay yours. With a
 * sword or axe in hand it also lands a full hit whenever it's charged, so the crystals follow a hit.
 */
public class HitCrystalModule extends Module {
	private static final Item[] WEAPONS = {Items.NETHERITE_SWORD, Items.DIAMOND_SWORD, Items.IRON_SWORD, Items.COPPER_SWORD,
		Items.STONE_SWORD, Items.GOLDEN_SWORD, Items.WOODEN_SWORD, Items.NETHERITE_AXE, Items.DIAMOND_AXE, Items.IRON_AXE};
	private static final int ROTATION_PRIORITY = 100;
	/** A crystal that was hit but is still there after this long gets hit again. */
	private static final long RETRY_MS = 120;

	private final NumberSetting range = add(new NumberSetting("Range", "How close someone has to be before it starts", 5, 2, 8, 0.25));
	private final NumberSetting minDamage = add(new NumberSetting("Min Damage", "Least damage a crystal has to do to them", 4, 1, 20, 0.5));
	private final NumberSetting maxSelf = add(new NumberSetting("Max Self", "Most damage a crystal may do to you (never enough to kill you)", 8, 0, 20, 0.5));
	private final BoolSetting placeObsidian = add(new BoolSetting("Place Obsidian", "Put obsidian down next to them when there's none a crystal would do enough damage on", true));
	private final BoolSetting swordHits = add(new BoolSetting("Sword Hits", "With a sword or axe in hand, a full hit whenever it's charged and they're not still flinching, then the crystals (Aura and Legit Aura do the hitting when they're on)", true));
	private final BoolSetting throughWalls = add(new BoolSetting("Through Walls", "Place and pop where you couldn't see it", false));

	{
		addGrim();
	}

	private Player target;
	/** The obsidian the crystals are going on. */
	private BlockPos base;
	/** Crystals hit, by entity id, and when: they don't block placing and aren't hit again straight away. */
	private final Map<Integer, Long> attacked = new HashMap<>();
	/** Where our crystals were put (the block the crystal stands in), so ours are popped the moment they spawn. */
	private final Set<BlockPos> placed = new HashSet<>();

	public HitCrystalModule() {
		super("Hit Crystal", "When someone's close, puts obsidian down and places and pops crystals on them as fast as it can, without moving your camera", Category.ATTACK);
	}

	@Override
	public String getInfo() {
		return target == null ? null : target.getGameProfile().name();
	}

	@Override
	protected void onDisable() {
		target = null;
		base = null;
		attacked.clear();
		placed.clear();
	}

	// ---------------------------------------------------------------- each tick

	@Override
	public void onPostMotion() {
		if (nullCheck()) return;
		long now = System.currentTimeMillis();
		attacked.values().removeIf(time -> now - time > 2000);
		target = TargetUtil.closestPlayer(range.get());
		if (target == null) {
			base = null;
			placed.clear();
			return;
		}
		if (Mc.screen() != null || mc.player.isUsingItem() || !has(stack -> stack.is(Items.END_CRYSTAL))) return;

		// Grim takes one thing a tick, each after a look that reached the server; otherwise all at once
		boolean oneAtATime = isGrim();
		if (swordHits.get() && swordHit() && oneAtATime) return;
		if (popBest() && oneAtATime) return;
		place();
	}

	/** A full hit on them when it's charged and they're open to one. */
	private boolean swordHit() {
		if (FemboyWare.isEnabled(LegitAuraModule.class) || FemboyWare.isEnabled(AuraModule.class)) return false;
		if (!isWeapon(mc.player.getMainHandItem()) || target.hurtTime > 0 || mc.player.getAttackStrengthScale(0.5f) < 0.92f) return false;
		AABB box = target.getBoundingBox();
		Vec3 eyes = mc.player.getEyePosition();
		// A little inside their box, so the look really lands on them
		Vec3 point = new Vec3(Mth.clamp(eyes.x, box.minX + 0.1, box.maxX - 0.1), Mth.clamp(eyes.y, box.minY + 0.2, box.maxY - 0.2),
			Mth.clamp(eyes.z, box.minZ + 0.1, box.maxZ - 0.1));
		if (eyes.distanceTo(point) > Grim.reach(mc.player.entityInteractionRange())) return false;
		if (!throughWalls.get() && !clear(eyes, point)) return false;
		if (!aim(point, box)) return false;
		mc.gameMode.attack(mc.player, target);
		mc.player.swing(InteractionHand.MAIN_HAND);
		return true;
	}

	/** Pops the crystal that hurts them most (any of ours that's safe, someone else's if it's worth it). */
	private boolean popBest() {
		Vec3 eyes = mc.player.getEyePosition();
		double reach = breakReach();
		EndCrystal best = null;
		float bestDamage = 0;
		long now = System.currentTimeMillis();
		for (Entity entity : mc.level.getEntities((Entity) null, mc.player.getBoundingBox().inflate(reach + 1), e -> e instanceof EndCrystal && e.isAlive())) {
			Long hit = attacked.get(entity.getId());
			if (hit != null && now - hit < RETRY_MS) continue;
			AABB box = entity.getBoundingBox();
			if (eyes.distanceTo(closestPoint(box, eyes)) > reach || !throughWalls.get() && !clear(eyes, box.getCenter())) continue;
			if (!safe(entity.position())) continue;
			float damage = DamageCalculator.crystalDamage(target, entity.position(), false, false);
			boolean ours = placed.contains(entity.blockPosition());
			if ((ours ? damage < 1 : damage < minDamage.getFloat()) || damage <= bestDamage) continue;
			best = (EndCrystal) entity;
			bestDamage = damage;
		}
		if (best == null) return false;
		EndCrystal crystal = best;
		AABB box = crystal.getBoundingBox();
		if (!aim(box.getCenter(), box)) return false;
		return attack(crystal.getId(), () -> mc.gameMode.attack(mc.player, crystal));
	}

	/** A crystal on the best obsidian, putting obsidian down first when there's none good. */
	private void place() {
		if (base != null && !stillGood(base)) base = null;
		if (base == null) base = bestBase();
		if (base == null) {
			if (!placeObsidian.get() || !has(stack -> stack.is(Items.OBSIDIAN))) return;
			BlockPos spot = bestObsidianSpot();
			if (spot == null || !putObsidian(spot)) return;
			base = spot;
			// One thing a tick for Grim; otherwise the crystal goes on it straight away
			if (isGrim()) return;
		}
		if (crystalFits(base)) putCrystal(base);
	}

	// ---------------------------------------------------------------- the moment things happen

	@Override
	public void onPacketReceive(PacketEvent event) {
		if (isGrim()) return;
		if (event.getPacket() instanceof ClientboundAddEntityPacket packet && packet.getType() == EntityType.END_CRYSTAL) {
			int id = packet.getId();
			Vec3 pos = new Vec3(packet.getX(), packet.getY(), packet.getZ());
			// Runs before the client adds it, so it's hit by id: one of ours, popped the moment it's there
			mc.execute(() -> {
				if (!isEnabled() || nullCheck() || target == null || !placed.contains(BlockPos.containing(pos))) return;
				AABB box = new AABB(pos.x - 1, pos.y, pos.z - 1, pos.x + 1, pos.y + 2, pos.z + 1);
				Vec3 eyes = mc.player.getEyePosition();
				if (eyes.distanceTo(closestPoint(box, eyes)) > breakReach() || !safe(pos)) return;
				if (!aim(new Vec3(pos.x, pos.y + 1, pos.z), box)) return;
				attack(id, () -> mc.getConnection().send(AutoCrystalModule.attackPacket(id, mc.player.isShiftKeyDown())));
			});
		} else if (event.getPacket() instanceof ClientboundRemoveEntitiesPacket packet) {
			for (int id : packet.getEntityIds()) {
				if (!attacked.containsKey(id)) continue;
				// One we hit is gone: the next one goes straight on
				mc.execute(() -> {
					if (!isEnabled() || nullCheck() || target == null || base == null || Mc.screen() != null) return;
					if (stillGood(base) && crystalFits(base)) putCrystal(base);
				});
				return;
			}
		}
	}

	// ---------------------------------------------------------------- doing it

	/** Hits crystal {@code id}, with a weapon swapped in silently while weakened. */
	private boolean attack(int id, Runnable hit) {
		Runnable swing = () -> {
			hit.run();
			mc.player.swing(InteractionHand.MAIN_HAND);
		};
		boolean done;
		if (mc.player.hasEffect(MobEffects.WEAKNESS) && !isWeapon(mc.player.getMainHandItem())) {
			done = InventoryUtil.withSlot(InventoryUtil.findHotbar(this::isWeapon), SwitchMode.SILENT, swing);
		} else {
			swing.run();
			done = true;
		}
		if (done) attacked.put(id, System.currentTimeMillis());
		return done;
	}

	private boolean putCrystal(BlockPos base) {
		Vec3 eyes = mc.player.getEyePosition();
		Vec3 top = Vec3.atCenterOf(base).add(0, 0.5, 0);
		BlockHitResult hit = Shapes.block().clip(eyes, eyes.add(top.subtract(eyes).scale(1.5)), base);
		if (hit == null || hit.getType() != HitResult.Type.BLOCK) hit = new BlockHitResult(top, Direction.UP, base, false);
		if (eyes.distanceTo(hit.getLocation()) > placeReach()) return false;
		if (!aim(hit.getLocation(), new AABB(base))) return false;
		BlockHitResult click = hit;
		boolean done = use(stack -> stack.is(Items.END_CRYSTAL), click);
		if (done) placed.add(base.above());
		return done;
	}

	/** Puts obsidian at {@code spot}; true once the block's there (the client shows it straight away). */
	private boolean putObsidian(BlockPos spot) {
		BlockHitResult face = placeFace(spot);
		if (face == null || !aim(face.getLocation(), new AABB(face.getBlockPos()))) return false;
		use(stack -> stack.is(Items.OBSIDIAN), face);
		return mc.level.getBlockState(spot).is(Blocks.OBSIDIAN);
	}

	/** Uses the item on the block from the offhand, or the main hand with a silent switch. */
	private boolean use(Predicate<ItemStack> item, BlockHitResult hit) {
		if (item.test(mc.player.getOffhandItem()) && !mc.player.getMainHandItem().is(Items.OBSIDIAN)) {
			mc.gameMode.useItemOn(mc.player, InteractionHand.OFF_HAND, hit);
			mc.player.swing(InteractionHand.OFF_HAND);
			return true;
		}
		return InventoryUtil.withSlot(InventoryUtil.findHotbar(item), SwitchMode.SILENT, () -> {
			mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
			mc.player.swing(InteractionHand.MAIN_HAND);
		});
	}

	/**
	 * Looks at {@code point} for the server only. Grim gets it in the next movement packet and the
	 * action waits for it; otherwise it's its own look packet, sent now.
	 */
	private boolean aim(Vec3 point, AABB box) {
		Rotation look = Rotation.between(mc.player.getEyePosition(), point);
		if (isGrim()) {
			FemboyWare.getRotations().request(look, ROTATION_PRIORITY, 180f, false);
			return aimedAt(box);
		}
		FemboyWare.getRotations().sendPacketRotation(look);
		return true;
	}

	private boolean aimedAt(AABB box) {
		Vec3 eyes = mc.player.getEyePosition();
		if (box.contains(eyes)) return true;
		return box.clip(eyes, eyes.add(FemboyWare.getRotations().getServerRotation().toVector().scale(8))).isPresent();
	}

	// ---------------------------------------------------------------- picking spots

	/** The obsidian or bedrock near them a crystal on hurts them most, safe for you and in reach. */
	private BlockPos bestBase() {
		BlockPos trap = FemboyWare.getModules().get(TrapModule.class).crystalSpot(target);
		if (trap != null && placeable(trap) && worth(trap) > 0) return trap;
		BlockPos best = null;
		float bestDamage = minDamage.getFloat();
		BlockPos feet = target.blockPosition();
		for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-3, -2, -3), feet.offset(3, 1, 3))) {
			BlockState state = mc.level.getBlockState(pos);
			if (!state.is(Blocks.OBSIDIAN) && !state.is(Blocks.BEDROCK)) continue;
			if (!placeable(pos) || !crystalFits(pos)) continue;
			// Theirs first: most spots fall out there, before the work of checking yours
			float damage = DamageCalculator.crystalDamage(target, pos, false, false);
			if (damage <= bestDamage || !safe(Vec3.atBottomCenterOf(pos.above()))) continue;
			bestDamage = damage;
			best = pos.immutable();
		}
		return best;
	}

	/** Where new obsidian beside them lets a crystal hurt most. */
	private BlockPos bestObsidianSpot() {
		BlockPos best = null;
		float bestDamage = minDamage.getFloat();
		BlockPos feet = target.blockPosition();
		for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-2, -1, -2), feet.offset(2, 0, 2))) {
			if (!mc.level.getBlockState(pos).canBeReplaced()) continue;
			if (!mc.level.isEmptyBlock(pos.above()) || !mc.level.isEmptyBlock(pos.above(2))) continue;
			// Only bodies stop a block going down, not dropped items or xp
			if (!mc.level.getEntities((Entity) null, new AABB(pos), e -> !e.isSpectator() && e.blocksBuilding).isEmpty()) continue;
			if (!crystalFits(pos)) continue;
			Vec3 crystal = Vec3.atBottomCenterOf(pos.above());
			if (mc.player.getEyePosition().distanceTo(crystal) > placeReach()) continue;
			float damage = DamageCalculator.crystalDamage(target, pos, false, false);
			if (damage <= bestDamage || !safe(crystal) || placeFace(pos) == null) continue;
			bestDamage = damage;
			best = pos.immutable();
		}
		return best;
	}

	private boolean stillGood(BlockPos pos) {
		BlockState state = mc.level.getBlockState(pos);
		return (state.is(Blocks.OBSIDIAN) || state.is(Blocks.BEDROCK)) && placeable(pos) && worth(pos) > 0;
	}

	/** In reach and, unless Through Walls, the top can be seen. */
	private boolean placeable(BlockPos pos) {
		Vec3 eyes = mc.player.getEyePosition();
		Vec3 top = Vec3.atCenterOf(pos).add(0, 0.5, 0);
		if (eyes.distanceTo(top) > placeReach()) return false;
		if (throughWalls.get()) return true;
		BlockHitResult hit = mc.level.clip(new ClipContext(eyes, top.add(0, -0.05, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
		return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos);
	}

	/** Damage to them from a crystal on {@code pos}, or 0 if it's not enough or not safe for you. */
	private float worth(BlockPos pos) {
		float damage = DamageCalculator.crystalDamage(target, pos, false, false);
		if (damage < minDamage.getFloat() || !safe(Vec3.atBottomCenterOf(pos.above()))) return 0;
		return damage;
	}

	/** A crystal at {@code crystal} wouldn't hurt you more than Max Self, or kill you. */
	private boolean safe(Vec3 crystal) {
		float self = DamageCalculator.crystalDamage(mc.player, crystal, false, false);
		return self <= maxSelf.getFloat() && self < mc.player.getHealth() + mc.player.getAbsorptionAmount() - 1;
	}

	/** Air above and nothing standing there, except crystals we already hit. */
	private boolean crystalFits(BlockPos base) {
		BlockPos above = base.above();
		if (!mc.level.isEmptyBlock(above)) return false;
		return mc.level.getEntities((Entity) null, new AABB(above).expandTowards(0, 1, 0),
			e -> !e.isSpectator() && !(e instanceof EndCrystal && attacked.containsKey(e.getId()))).isEmpty();
	}

	/** The closest face beside {@code pos} to click to put a block there, in reach (and in view unless Through Walls). */
	private BlockHitResult placeFace(BlockPos pos) {
		Vec3 eyes = mc.player.getEyePosition();
		BlockHitResult best = null;
		double bestDistance = placeReach();
		for (Direction direction : Direction.values()) {
			BlockPos neighbour = pos.relative(direction);
			BlockState state = mc.level.getBlockState(neighbour);
			// Clicking an anchor uses it instead of putting a block against it
			if (state.canBeReplaced() || state.is(Blocks.RESPAWN_ANCHOR)) continue;
			Direction face = direction.getOpposite();
			Vec3 point = Vec3.atCenterOf(neighbour).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
			double distance = eyes.distanceTo(point);
			if (distance > bestDistance) continue;
			if (!throughWalls.get()) {
				Vec3 through = point.add(point.subtract(eyes).normalize().scale(0.05));
				BlockHitResult hit = mc.level.clip(new ClipContext(eyes, through, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
				if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(neighbour)) continue;
			}
			best = new BlockHitResult(point, face, neighbour, false);
			bestDistance = distance;
		}
		return best;
	}

	// ---------------------------------------------------------------- small things

	private double placeReach() {
		return mc.player.blockInteractionRange();
	}

	private double breakReach() {
		return Grim.reach(mc.player.entityInteractionRange() + 1.5);
	}

	/** No blocks between your eyes and {@code point}. */
	private boolean clear(Vec3 eyes, Vec3 point) {
		return mc.level.clip(new ClipContext(eyes, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player)).getType() == HitResult.Type.MISS;
	}

	private static Vec3 closestPoint(AABB box, Vec3 eyes) {
		return new Vec3(Mth.clamp(eyes.x, box.minX, box.maxX), Mth.clamp(eyes.y, box.minY, box.maxY), Mth.clamp(eyes.z, box.minZ, box.maxZ));
	}

	private boolean has(Predicate<ItemStack> item) {
		return item.test(mc.player.getOffhandItem()) || InventoryUtil.findHotbar(item) >= 0;
	}

	private boolean isWeapon(ItemStack stack) {
		for (Item item : WEAPONS) if (stack.is(item)) return true;
		return false;
	}
}
