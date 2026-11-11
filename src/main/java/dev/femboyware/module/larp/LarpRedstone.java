package dev.femboyware.module.larp;

import dev.femboyware.FemboyWare;
import dev.femboyware.mixin.ClientLevelAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.PressurePlateBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.RedstoneWallTorchBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WeightedPressurePlateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Redstone while Server Larp is on, worked out here since the server isn't listening: levers,
 * buttons and pressure plates; dust carrying power; torches and repeaters with their delays; and
 * lamps, doors, trapdoors, fence gates, dispensers and droppers reacting. Every change is a
 * prediction, so turning Server Larp off puts it all back as the server has it.
 * <p>
 * It's a close copy, not the real thing: no pistons, comparators or observers, and update order
 * quirks aren't copied.
 */
public final class LarpRedstone {
	private static final int RADIUS = 20, RESCAN_TICKS = 40;
	private final Minecraft mc = Minecraft.getInstance();

	/** Buttons pressed, and ticks until they pop back out. */
	private final Map<BlockPos, Integer> buttons = new HashMap<>();
	/** Pressure plates someone's on, and ticks until they let go after stepping off. */
	private final Map<BlockPos, Integer> plates = new HashMap<>();
	/** Torches and repeaters about to change, and in how many ticks. */
	private final Map<BlockPos, Integer> pending = new HashMap<>();
	/** The redstone round you, found by scanning; rescanned now and then and when blocks change. */
	private final List<BlockPos> parts = new ArrayList<>();
	private BlockPos scannedAt;
	private int rescan;

	public void reset() {
		buttons.clear();
		plates.clear();
		pending.clear();
		parts.clear();
		scannedAt = null;
	}

	/** Positions in {@link #parts}, for telling whether a change added or took away a piece. */
	private final Set<BlockPos> partSet = new HashSet<>();

	/** A block changed here; looks again only if a piece of redstone came or went (not when one just switched). */
	public void changed(BlockPos pos, BlockState state) {
		if (interesting(state) != partSet.contains(pos)) rescan = 0;
	}

	private void changed() {
		rescan = 0;
	}

	private static boolean interesting(BlockState s) {
		var b = s.getBlock();
		return b instanceof RedStoneWireBlock || b instanceof LeverBlock || b instanceof ButtonBlock || b instanceof RedstoneTorchBlock
			|| b instanceof DiodeBlock || b instanceof RedstoneLampBlock || b instanceof DoorBlock || b instanceof TrapDoorBlock
			|| b instanceof FenceGateBlock || b instanceof DispenserBlock || b instanceof BasePressurePlateBlock || s.is(Blocks.REDSTONE_BLOCK);
	}

	private void scan(BlockPos center) {
		parts.clear();
		scannedAt = center;
		rescan = RESCAN_TICKS;
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int x = -RADIUS; x <= RADIUS; x++) {
			for (int y = -RADIUS / 2; y <= RADIUS / 2; y++) {
				for (int z = -RADIUS; z <= RADIUS; z++) {
					p.set(center.getX() + x, center.getY() + y, center.getZ() + z);
					if (interesting(mc.level.getBlockState(p))) {
						parts.add(p.immutable());
						partSet.add(p.immutable());
					}
				}
			}
		}
	}

	/** Puts a block in as a prediction, so turning Server Larp off rolls it back; no neighbour updates, since this works them out itself. */
	private void set(BlockPos pos, BlockState state) {
		var handler = ((ClientLevelAccessor) mc.level).femboyware$getPredictionHandler();
		try (var predicting = handler.startPredicting()) {
			mc.level.setBlock(pos, state, 2 | 16);
		}
	}

	private void sound(BlockPos pos, net.minecraft.sounds.SoundEvent sound, float pitch) {
		mc.level.playLocalSound(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, sound, SoundSource.BLOCKS, 0.6f, pitch, false);
	}

	/** Right-clicking a lever or button while larping: it's flipped or pressed here. True if it was one. */
	public boolean use(BlockHitResult hit) {
		BlockPos pos = hit.getBlockPos();
		BlockState state = mc.level.getBlockState(pos);
		if (state.getBlock() instanceof LeverBlock) {
			boolean on = !state.getValue(LeverBlock.POWERED);
			set(pos, state.setValue(LeverBlock.POWERED, on));
			sound(pos, SoundEvents.LEVER_CLICK, on ? 0.6f : 0.5f);
			changed();
			return true;
		}
		if (state.getBlock() instanceof ButtonBlock) {
			if (state.getValue(ButtonBlock.POWERED)) return true;
			boolean wooden = state.is(BlockTags.WOODEN_BUTTONS);
			set(pos, state.setValue(ButtonBlock.POWERED, true));
			buttons.put(pos.immutable(), wooden ? 30 : 20);
			sound(pos, wooden ? SoundEvents.WOODEN_BUTTON_CLICK_ON : SoundEvents.STONE_BUTTON_CLICK_ON, 0.6f);
			changed();
			return true;
		}
		return false;
	}

	/** Where a lever, button or torch is stuck on. */
	private static BlockPos support(BlockPos pos, BlockState state) {
		if (state.getBlock() instanceof RedstoneWallTorchBlock) return pos.relative(state.getValue(RedstoneWallTorchBlock.FACING).getOpposite());
		if (state.getBlock() instanceof RedstoneTorchBlock) return pos.below();
		AttachFace face = state.getValue(FaceAttachedHorizontalDirectionalBlock.FACE);
		return switch (face) {
			case FLOOR -> pos.below();
			case CEILING -> pos.above();
			default -> pos.relative(state.getValue(FaceAttachedHorizontalDirectionalBlock.FACING).getOpposite());
		};
	}

	public void tick() {
		Player player = mc.player;
		if (player == null || mc.level == null) return;
		BlockPos here = player.blockPosition();
		if (scannedAt == null || --rescan <= 0 || scannedAt.distManhattan(here) > 8) scan(here);
		if (parts.isEmpty()) return;
		Set<BlockPos> changedNow = new HashSet<>();
		tickButtons(changedNow);
		tickPlates(changedNow);
		tickWire();
		tickDelayed();
		tickMechanisms();
	}

	private void tickButtons(Set<BlockPos> changedNow) {
		for (var it = buttons.entrySet().iterator(); it.hasNext(); ) {
			var entry = it.next();
			if (entry.setValue(entry.getValue() - 1) > 1) continue;
			it.remove();
			BlockState state = mc.level.getBlockState(entry.getKey());
			if (state.getBlock() instanceof ButtonBlock && state.getValue(ButtonBlock.POWERED)) {
				set(entry.getKey(), state.setValue(ButtonBlock.POWERED, false));
				boolean wooden = state.is(BlockTags.WOODEN_BUTTONS);
				sound(entry.getKey(), wooden ? SoundEvents.WOODEN_BUTTON_CLICK_OFF : SoundEvents.STONE_BUTTON_CLICK_OFF, 0.5f);
				changedNow.add(entry.getKey());
			}
		}
	}

	/** Plates under you and your fake players press down, and let go a second after everyone's off. */
	private void tickPlates(Set<BlockPos> changedNow) {
		Set<BlockPos> stoodOn = new HashSet<>();
		List<Player> people = new ArrayList<>();
		people.add(mc.player);
		people.addAll(FemboyWare.getModules().get(dev.femboyware.module.client.FakePlayerModule.class).fakeEntities());
		for (Player p : people) {
			AABB box = p.getBoundingBox();
			BlockPos feet = BlockPos.containing(p.getX(), box.minY + 0.01, p.getZ());
			if (mc.level.getBlockState(feet).getBlock() instanceof BasePressurePlateBlock) stoodOn.add(feet);
		}
		for (BlockPos pos : stoodOn) {
			BlockState state = mc.level.getBlockState(pos);
			if (plates.put(pos, 20) == null && !platePowered(state)) {
				set(pos, platePower(state, true));
				sound(pos, SoundEvents.STONE_PRESSURE_PLATE_CLICK_ON, 0.6f);
				changedNow.add(pos);
			}
		}
		for (var it = plates.entrySet().iterator(); it.hasNext(); ) {
			var entry = it.next();
			if (stoodOn.contains(entry.getKey())) continue;
			if (entry.setValue(entry.getValue() - 1) > 0) continue;
			it.remove();
			BlockState state = mc.level.getBlockState(entry.getKey());
			if (state.getBlock() instanceof BasePressurePlateBlock && platePowered(state)) {
				set(entry.getKey(), platePower(state, false));
				sound(entry.getKey(), SoundEvents.STONE_PRESSURE_PLATE_CLICK_OFF, 0.5f);
				changedNow.add(entry.getKey());
			}
		}
	}

	private static boolean platePowered(BlockState state) {
		if (state.hasProperty(PressurePlateBlock.POWERED)) return state.getValue(PressurePlateBlock.POWERED);
		return state.hasProperty(WeightedPressurePlateBlock.POWER) && state.getValue(WeightedPressurePlateBlock.POWER) > 0;
	}

	private static BlockState platePower(BlockState state, boolean on) {
		if (state.hasProperty(PressurePlateBlock.POWERED)) return state.setValue(PressurePlateBlock.POWERED, on);
		if (state.hasProperty(WeightedPressurePlateBlock.POWER)) return state.setValue(WeightedPressurePlateBlock.POWER, on ? 1 : 0);
		return state;
	}

	// ---------------------------------------------------------------- dust

	/** What reaches a piece of dust from things other than dust: sources next to it, and blocks they power strongly. */
	private int sourceSignal(BlockPos pos) {
		int best = 0;
		for (Direction d : Direction.values()) {
			BlockPos n = pos.relative(d);
			BlockState s = mc.level.getBlockState(n);
			if (s.getBlock() instanceof RedStoneWireBlock) continue;
			best = Math.max(best, s.getSignal(mc.level, n, d));
			if (s.isRedstoneConductor(mc.level, n)) {
				// A block strongly powered by something other than dust
				for (Direction e : Direction.values()) {
					BlockPos m = n.relative(e);
					BlockState sm = mc.level.getBlockState(m);
					if (sm.getBlock() instanceof RedStoneWireBlock) continue;
					best = Math.max(best, sm.getDirectSignal(mc.level, m, e));
				}
			}
			if (best >= 15) return 15;
		}
		return best;
	}

	/** Dust that connects to this piece: beside it, and up or down a step where nothing's in the way. */
	private List<BlockPos> wireNeighbours(BlockPos pos) {
		List<BlockPos> out = new ArrayList<>();
		boolean blockedAbove = mc.level.getBlockState(pos.above()).isRedstoneConductor(mc.level, pos.above());
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockPos side = pos.relative(d);
			BlockState s = mc.level.getBlockState(side);
			if (s.getBlock() instanceof RedStoneWireBlock) out.add(side);
			if (!blockedAbove && mc.level.getBlockState(side.above()).getBlock() instanceof RedStoneWireBlock) out.add(side.above());
			if (!s.isRedstoneConductor(mc.level, side) && mc.level.getBlockState(side.below()).getBlock() instanceof RedStoneWireBlock) out.add(side.below());
		}
		return out;
	}

	/** Every piece of dust's power: 15 next to a source, one less for each step along the line. */
	private void tickWire() {
		Map<BlockPos, Integer> power = new HashMap<>();
		List<BlockPos> wires = new ArrayList<>();
		for (BlockPos pos : parts) {
			if (mc.level.getBlockState(pos).getBlock() instanceof RedStoneWireBlock) {
				wires.add(pos);
				power.put(pos, sourceSignal(pos));
			}
		}
		if (wires.isEmpty()) return;
		// Spread along the lines until nothing gets any stronger
		java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
		for (BlockPos pos : wires) if (power.get(pos) > 1) queue.add(pos);
		while (!queue.isEmpty()) {
			BlockPos pos = queue.poll();
			int next = power.get(pos) - 1;
			for (BlockPos n : wireNeighbours(pos)) {
				Integer have = power.get(n);
				if (have == null || have >= next) continue;
				power.put(n, next);
				if (next > 1) queue.add(n);
			}
		}
		for (BlockPos pos : wires) {
			BlockState state = mc.level.getBlockState(pos);
			int p = power.get(pos);
			if (state.getValue(RedStoneWireBlock.POWER) != p) set(pos, state.setValue(RedStoneWireBlock.POWER, p));
		}
	}

	// ---------------------------------------------------------------- torches and repeaters

	private boolean torchShouldBeLit(BlockPos pos, BlockState state) {
		// As vanilla asks it: the block it's on, from the torch's side
		Direction towards = state.getBlock() instanceof RedstoneWallTorchBlock ? state.getValue(RedstoneWallTorchBlock.FACING).getOpposite() : Direction.DOWN;
		return !mc.level.hasSignal(support(pos, state), towards);
	}

	private boolean repeaterShouldBeOn(BlockPos pos, BlockState state) {
		Direction facing = state.getValue(DiodeBlock.FACING);
		BlockPos back = pos.relative(facing);
		int signal = mc.level.getSignal(back, facing);
		BlockState behind = mc.level.getBlockState(back);
		if (behind.getBlock() instanceof RedStoneWireBlock) signal = Math.max(signal, behind.getValue(RedStoneWireBlock.POWER));
		return signal > 0;
	}

	/** Torches flip two ticks after their block's power changes; repeaters after their delay. */
	private void tickDelayed() {
		for (BlockPos pos : parts) {
			BlockState state = mc.level.getBlockState(pos);
			boolean want, now;
			int delay;
			if (state.getBlock() instanceof RedstoneTorchBlock) {
				want = torchShouldBeLit(pos, state);
				now = state.getValue(RedstoneTorchBlock.LIT);
				delay = 2;
			} else if (state.getBlock() instanceof RepeaterBlock) {
				want = repeaterShouldBeOn(pos, state);
				now = state.getValue(DiodeBlock.POWERED);
				delay = state.getValue(RepeaterBlock.DELAY) * 2;
			} else continue;
			if (want == now) {
				pending.remove(pos);
				continue;
			}
			int left = pending.getOrDefault(pos, delay) - 1;
			if (left > 0) {
				pending.put(pos, left);
				continue;
			}
			pending.remove(pos);
			set(pos, state.getBlock() instanceof RedstoneTorchBlock ? state.setValue(RedstoneTorchBlock.LIT, want) : state.setValue(DiodeBlock.POWERED, want));
		}
	}

	// ---------------------------------------------------------------- what redstone works

	private void tickMechanisms() {
		ForceDispenserModule dispenser = FemboyWare.getModules().get(ForceDispenserModule.class);
		for (BlockPos pos : parts) {
			BlockState state = mc.level.getBlockState(pos);
			var block = state.getBlock();
			if (block instanceof RedstoneLampBlock) {
				boolean lit = mc.level.hasNeighborSignal(pos);
				if (lit != state.getValue(RedstoneLampBlock.LIT)) set(pos, state.setValue(RedstoneLampBlock.LIT, lit));
			} else if (block instanceof DoorBlock door) {
				// Each half answers for the whole door, so do it from the bottom half
				if (state.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) continue;
				BlockPos top = pos.above();
				boolean powered = mc.level.hasNeighborSignal(pos) || mc.level.hasNeighborSignal(top);
				if (powered == state.getValue(DoorBlock.POWERED)) continue;
				set(pos, state.setValue(DoorBlock.POWERED, powered).setValue(DoorBlock.OPEN, powered));
				BlockState upper = mc.level.getBlockState(top);
				if (upper.getBlock() instanceof DoorBlock) set(top, upper.setValue(DoorBlock.POWERED, powered).setValue(DoorBlock.OPEN, powered));
				if (powered != state.getValue(DoorBlock.OPEN)) sound(pos, powered ? door.type().doorOpen() : door.type().doorClose(), 1);
			} else if (block instanceof TrapDoorBlock) {
				boolean powered = mc.level.hasNeighborSignal(pos);
				if (powered == state.getValue(TrapDoorBlock.POWERED)) continue;
				set(pos, state.setValue(TrapDoorBlock.POWERED, powered).setValue(TrapDoorBlock.OPEN, powered));
				if (powered != state.getValue(TrapDoorBlock.OPEN)) sound(pos, powered ? SoundEvents.WOODEN_TRAPDOOR_OPEN : SoundEvents.WOODEN_TRAPDOOR_CLOSE, 1);
			} else if (block instanceof FenceGateBlock) {
				boolean powered = mc.level.hasNeighborSignal(pos);
				if (powered == state.getValue(FenceGateBlock.POWERED)) continue;
				set(pos, state.setValue(FenceGateBlock.POWERED, powered).setValue(FenceGateBlock.OPEN, powered));
				if (powered != state.getValue(FenceGateBlock.OPEN)) sound(pos, powered ? SoundEvents.FENCE_GATE_OPEN : SoundEvents.FENCE_GATE_CLOSE, 1);
			} else if (block instanceof DispenserBlock) {
				// Fires once each time it's powered, like the real thing
				boolean powered = mc.level.hasNeighborSignal(pos) || mc.level.hasNeighborSignal(pos.above());
				boolean was = state.getValue(DispenserBlock.TRIGGERED);
				if (powered == was) continue;
				set(pos, state.setValue(DispenserBlock.TRIGGERED, powered));
				if (powered) dispenser.fire(pos, state);
			}
		}
	}
}
