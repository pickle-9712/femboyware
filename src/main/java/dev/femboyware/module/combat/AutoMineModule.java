package dev.femboyware.module.combat;

import dev.femboyware.FemboyWare;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.BlockUtil;
import dev.femboyware.util.DamageCalculator;
import dev.femboyware.util.InventoryUtil;
import dev.femboyware.util.InventoryUtil.SwitchMode;
import dev.femboyware.util.RenderUtil;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Packet-mines the blocks around an enemy's feet so AutoCrystal can crystal them.
 *
 * <p>Relies on how the vanilla server tracks mining:
 * <ul>
 *   <li><b>Double mine</b>: stopping a dig early leaves the server finishing it in the background
 *   ({@code failedToMine}), so a second block can be mined at the same time. The background block
 *   only breaks on a server tick where we hold the right tool, so we hold it once it's due.</li>
 *   <li><b>Rebreak</b>: STOP_DESTROY_BLOCK only checks the position against the last started dig
 *   and times progress from that start, so after one break, anything placed back in that spot
 *   breaks the instant we send STOP again.</li>
 * </ul>
 */
public class AutoMineModule extends Module {
	private final NumberSetting range = add(new NumberSetting("Range", "Max mining distance", 5, 1, 6, 0.1));
	private final NumberSetting targetRange = add(new NumberSetting("Target Range", "Max distance to an enemy", 8, 2, 12, 0.5));
	private final BoolSetting burrow = add(new BoolSetting("Burrow", "Mine a block the enemy is standing inside first", true));
	private final BoolSetting city = add(new BoolSetting("City", "Mine the blocks around the enemy's feet", true));
	private final BoolSetting crystalCheck = add(new BoolSetting("Crystal Check", "Only mine spots a crystal can go into afterwards", true));
	private final BoolSetting doubleMine = add(new BoolSetting("Double Mine", "Mine two blocks at the same time", true));
	private final BoolSetting rebreak = add(new BoolSetting("Rebreak", "Instantly re-mine a block that gets placed back", true));
	private final BoolSetting crystalOnBreak = add(new BoolSetting("Crystal On Break", "Tell AutoCrystal to place the moment a block breaks", true));
	private final BoolSetting render = add(new BoolSetting("Render", "Show mining progress", true));
	private final ColorSetting primaryColor = add(new ColorSetting("Primary", "Main dig colour", 0x60FF77C8)).visibleWhen(render::get);
	private final ColorSetting secondaryColor = add(new ColorSetting("Secondary", "Background dig colour", 0x6077B5FF)).visibleWhen(render::get);
	private final ColorSetting rebreakColor = add(new ColorSetting("Rebreak", "Rebreak spot colour", 0xA0FFFFFF)).visibleWhen(render::get);

	private static final class MineJob {
		final BlockPos pos;
		final Direction direction;
		final long startTick;
		long finishTick = -1;

		MineJob(BlockPos pos, Direction direction, long startTick) {
			this.pos = pos;
			this.direction = direction;
			this.startTick = startTick;
		}
	}

	private long ticks;
	private Player target;
	private MineJob primary;
	private MineJob secondary;
	private BlockPos rebreakPos;
	private int holdReturnSlot = -1;

	public AutoMineModule() {
		super("Auto Mine", "Mines enemy surround blocks for AutoCrystal, with double mine and rebreak", Category.ATTACK);
	}

	@Override
	public String getInfo() {
		return target == null ? null : target.getName().getString();
	}

	@Override
	protected void onDisable() {
		if (!nullCheck() && primary != null) sendAction(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, primary.pos, primary.direction);
		releaseHold();
		primary = null;
		secondary = null;
		rebreakPos = null;
		target = null;
	}

	@Override
	public void onTick() {
		ticks++;
		// The vanilla interaction manager would fight us over the server's dig state
		if (mc.gameMode.isDestroying()) return;
		target = findTarget();

		updateSecondary();
		updatePrimary();
		if (primary == null && target != null) startNewMines();
	}

	@Override
	public void onBlockUpdate(BlockPos pos, BlockState state) {
		if (state.isAir()) {
			boolean broke = false;
			if (primary != null && pos.equals(primary.pos)) {
				// The server's dig position is still this spot, so it can be rebroken instantly
				rebreakPos = pos;
				primary = null;
				broke = true;
			}
			if (secondary != null && pos.equals(secondary.pos)) {
				secondary = null;
				releaseHold();
				broke = true;
			}
			if (broke && crystalOnBreak.get()) FemboyWare.getModules().get(AutoCrystalModule.class).placeNow();
			return;
		}

		if (rebreak.get() && pos.equals(rebreakPos) && inRange(pos)) {
			breakNow(pos, closestFace(pos));
		}
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (!render.get()) return;
		if (primary != null) drawProgress(event, primary, 0.7f, primaryColor);
		if (secondary != null) drawProgress(event, secondary, 1.0f, secondaryColor);
		if (rebreakPos != null && rebreak.get()) {
			RenderUtil.drawBox(event, new AABB(rebreakPos), 0, rebreakColor.get(), 1.5f);
		}
	}

	private void drawProgress(Render3DEvent event, MineJob job, float needed, ColorSetting color) {
		float progress = Mth.clamp(bestDelta(job.pos) * (ticks - job.startTick + 1) / needed, 0f, 1f);
		AABB full = new AABB(job.pos);
		AABB box = full.deflate(0.5 * (1 - progress));
		RenderUtil.drawBox(event, box, color.get(), color.withAlpha(255), 1.5f);
	}

	// ---------------------------------------------------------------- mining state

	private void updatePrimary() {
		if (primary == null) return;
		if (mc.level.getBlockState(primary.pos).isAir()) {
			rebreakPos = primary.pos;
			primary = null;
			return;
		}
		if (!inRange(primary.pos)) {
			sendAction(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, primary.pos, primary.direction);
			primary = null;
			return;
		}

		long elapsed = ticks - primary.startTick;
		// Server needs delta * (ticks + 1) >= 0.7 at STOP time; drop the +1 as a latency margin
		if (bestDelta(primary.pos) * elapsed < 0.7f) return;

		if (primary.finishTick != -1 && ticks - primary.finishTick > 20) {
			// The server never confirmed, so start over
			primary = null;
			return;
		}
		if (primary.finishTick == -1) primary.finishTick = ticks;
		// Re-sent every tick until the block update arrives; extra STOPs are harmless
		breakNow(primary.pos, primary.direction);
	}

	private void updateSecondary() {
		if (secondary == null) return;
		if (mc.level.getBlockState(secondary.pos).isAir() || !inRange(secondary.pos)) {
			secondary = null;
			releaseHold();
			return;
		}
		long elapsed = ticks - secondary.startTick;
		if (bestDelta(secondary.pos) * (elapsed + 1) < 1.0f) return;

		if (secondary.finishTick == -1) secondary.finishTick = ticks;
		if (ticks - secondary.finishTick > 20) {
			secondary = null;
			releaseHold();
			return;
		}
		// The background dig is checked every server tick against whatever we're holding
		int slot = bestSlot(secondary.pos);
		if (slot != -1 && slot != mc.player.getInventory().getSelectedSlot()) {
			if (holdReturnSlot == -1) holdReturnSlot = mc.player.getInventory().getSelectedSlot();
			InventoryUtil.select(slot);
		}
	}

	private void startNewMines() {
		// Keep the server's dig position on an open rebreak spot so a re-placed block dies instantly
		if (rebreak.get() && rebreakPos != null && mc.level.isEmptyBlock(rebreakPos) && surroundSpots(target).contains(rebreakPos)) return;

		List<BlockPos> candidates = findCandidates(target);
		if (secondary != null) candidates.remove(secondary.pos);
		if (candidates.isEmpty()) return;

		if (doubleMine.get() && secondary == null && candidates.size() >= 2) {
			BlockPos background = candidates.get(1);
			Direction direction = closestFace(background);
			sendAction(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, background, direction);
			sendAction(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, background, direction);
			secondary = new MineJob(background, direction, ticks);
		}

		BlockPos pos = candidates.get(0);
		Direction direction = closestFace(pos);
		sendAction(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, direction);
		mc.player.swing(InteractionHand.MAIN_HAND);
		primary = new MineJob(pos, direction, ticks);
		rebreakPos = null;
	}

	/** Sends STOP while silently holding the best tool, which the server uses to judge progress. */
	private void breakNow(BlockPos pos, Direction direction) {
		int slot = bestSlot(pos);
		InventoryUtil.withSlot(slot == -1 ? mc.player.getInventory().getSelectedSlot() : slot, SwitchMode.SILENT, () -> {
			sendAction(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, direction);
			mc.player.swing(InteractionHand.MAIN_HAND);
		});
	}

	private void releaseHold() {
		if (holdReturnSlot != -1 && !nullCheck()) InventoryUtil.select(holdReturnSlot);
		holdReturnSlot = -1;
	}

	private void sendAction(ServerboundPlayerActionPacket.Action action, BlockPos pos, Direction direction) {
		mc.getConnection().send(new ServerboundPlayerActionPacket(action, pos, direction));
	}

	// ---------------------------------------------------------------- targeting

	private Player findTarget() {
		Player best = null;
		double bestDistance = targetRange.get() * targetRange.get();
		for (Player player : mc.level.players()) {
			if (player == mc.player || !player.isAlive() || player.isSpectator() || player.isCreative()) continue;
			double distance = player.distanceToSqr(mc.player);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = player;
			}
		}
		return best;
	}

	private Set<BlockPos> feetBlocks(Player player) {
		AABB box = player.getBoundingBox();
		int y = Mth.floor(player.getY() + 0.2);
		Set<BlockPos> set = new LinkedHashSet<>();
		for (int x = Mth.floor(box.minX); x <= Mth.floor(box.maxX - 1e-4); x++) {
			for (int z = Mth.floor(box.minZ); z <= Mth.floor(box.maxZ - 1e-4); z++) {
				set.add(new BlockPos(x, y, z));
			}
		}
		return set;
	}

	private Set<BlockPos> surroundSpots(Player player) {
		Set<BlockPos> feet = feetBlocks(player);
		Set<BlockPos> ring = new LinkedHashSet<>();
		for (BlockPos pos : feet) {
			for (Direction direction : Direction.Plane.HORIZONTAL) {
				BlockPos side = pos.relative(direction);
				if (!feet.contains(side)) ring.add(side);
			}
		}
		return ring;
	}

	/** Mineable spots ordered best first: burrow blocks, then city blocks by crystal damage. */
	private List<BlockPos> findCandidates(Player player) {
		List<BlockPos> burrowBlocks = new ArrayList<>();
		if (burrow.get()) {
			for (BlockPos pos : feetBlocks(player)) {
				if (isMineable(pos)) burrowBlocks.add(pos);
			}
		}

		List<BlockPos> cityBlocks = new ArrayList<>();
		if (city.get()) {
			for (BlockPos pos : surroundSpots(player)) {
				if (!isMineable(pos)) continue;
				if (crystalCheck.get() && !isCrystalBase(pos.below())) continue;
				cityBlocks.add(pos);
			}
			cityBlocks.sort(Comparator.comparingDouble((BlockPos pos) ->
				DamageCalculator.crystalDamage(player, Vec3.atBottomCenterOf(pos), false, false)).reversed());
		}

		List<BlockPos> result = new ArrayList<>(burrowBlocks);
		result.addAll(cityBlocks);
		return result;
	}

	private boolean isMineable(BlockPos pos) {
		BlockState state = mc.level.getBlockState(pos);
		if (state.isAir() || state.canBeReplaced() || state.getDestroySpeed(mc.level, pos) < 0) return false;
		return inRange(pos) && bestDelta(pos) > 0;
	}

	private boolean isCrystalBase(BlockPos pos) {
		BlockState state = mc.level.getBlockState(pos);
		return state.is(Blocks.OBSIDIAN) || state.is(Blocks.BEDROCK);
	}

	private boolean inRange(BlockPos pos) {
		return mc.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= range.get() * range.get();
	}

	private Direction closestFace(BlockPos pos) {
		Vec3 eyes = mc.player.getEyePosition();
		Direction best = Direction.UP;
		double bestDistance = Double.MAX_VALUE;
		for (Direction direction : Direction.values()) {
			double distance = eyes.distanceToSqr(BlockUtil.faceCenter(pos, direction));
			if (distance < bestDistance) {
				bestDistance = distance;
				best = direction;
			}
		}
		return best;
	}

	// ---------------------------------------------------------------- tools

	/** Hotbar slot that mines {@code pos} fastest, or -1 if nothing can. */
	private int bestSlot(BlockPos pos) {
		int best = -1;
		float bestDelta = 0;
		for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
			float delta = deltaWithSlot(pos, slot);
			if (delta > bestDelta) {
				bestDelta = delta;
				best = slot;
			}
		}
		return best;
	}

	private float bestDelta(BlockPos pos) {
		int slot = bestSlot(pos);
		return slot == -1 ? 0 : deltaWithSlot(pos, slot);
	}

	/** Break progress per tick if {@code slot} were held. Swaps the slot client-side only, no packet. */
	private float deltaWithSlot(BlockPos pos, int slot) {
		Inventory inventory = mc.player.getInventory();
		int previous = inventory.getSelectedSlot();
		inventory.setSelectedSlot(slot);
		float delta = mc.level.getBlockState(pos).getDestroyProgress(mc.player, mc.level, pos);
		inventory.setSelectedSlot(previous);
		return delta;
	}
}
