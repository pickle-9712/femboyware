package dev.femboyware.module.render;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.ChatUtil;
import dev.femboyware.util.ChunkScanner;
import dev.femboyware.util.RenderUtil;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Finds spawners players have placed. Natural spawners always sit in a structure: dungeon
 * cobblestone, mineshaft cobwebs and rails, fortress nether bricks, stronghold stone bricks or
 * bastion blackstone. A spawner with none of those around it was carried there by a player,
 * which on spawner servers like DonutSMP means a base.
 */
public class SpawnerEspModule extends Module {
	private static final int CHUNKS_PER_TICK = 4;
	private static final long RESCAN_MILLIS = 5_000;
	/** Blocks around a spawner checked for a natural structure. */
	private static final int STRUCTURE_RADIUS = 4;
	/** Blocks only natural spawner structures put around a spawner. */
	private static final Set<Block> STRUCTURE_BLOCKS = Set.of(
		Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE, Blocks.COBWEB, Blocks.RAIL, Blocks.OAK_PLANKS, Blocks.OAK_FENCE,
		Blocks.NETHER_BRICKS, Blocks.NETHER_BRICK_FENCE, Blocks.NETHER_BRICK_STAIRS,
		Blocks.STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS, Blocks.INFESTED_STONE_BRICKS, Blocks.IRON_BARS,
		Blocks.BLACKSTONE, Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS, Blocks.GILDED_BLACKSTONE);

	private record Spawner(BlockPos pos, String mob, boolean natural) {}

	private final NumberSetting range = add(new NumberSetting("Range", "Scan radius in chunks", 8, 1, 16, 1));
	private final BoolSetting showNatural = add(new BoolSetting("Show Natural", "Also show spawners inside dungeons and other structures", false));
	private final BoolSetting throughWalls = add(new BoolSetting("Through Walls", "See spawners through terrain", true));
	private final BoolSetting tracers = add(new BoolSetting("Tracers", "Draw lines to placed spawners", false));
	private final BoolSetting notify = add(new BoolSetting("Notify", "Say in chat when a placed spawner is found", true));
	private final ColorSetting placedColor = add(new ColorSetting("Placed Color", "Spawners players placed", 0xFFFF4466));
	private final ColorSetting naturalColor = add(new ColorSetting("Natural Color", "Spawners in natural structures", 0xFF888888)).visibleWhen(showNatural::get);

	private final ChunkScanner<List<Spawner>> scanner = new ChunkScanner<>(this::scan);
	private final Set<BlockPos> notified = new HashSet<>();

	public SpawnerEspModule() {
		super("Spawner Esp", "Finds spawners players placed outside dungeons", Category.ESP);
	}

	@Override
	public String getInfo() {
		return Integer.toString(placedCount());
	}

	@Override
	protected void onDisable() {
		scanner.clear();
		notified.clear();
	}

	@Override
	public void onTick() {
		scanner.tick(range.getInt(), CHUNKS_PER_TICK, RESCAN_MILLIS);
		if (!notify.get()) return;
		for (List<Spawner> spawners : scanner.results().values()) {
			for (Spawner spawner : spawners) {
				if (spawner.natural || !notified.add(spawner.pos)) continue;
				ChatUtil.info("Placed " + spawner.mob + " spawner at " + ChatUtil.coords(spawner.pos));
			}
		}
	}

	private List<Spawner> scan(LevelChunk chunk) {
		List<Spawner> spawners = new ArrayList<>();
		for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
			if (!(blockEntity instanceof SpawnerBlockEntity spawner)) continue;
			BlockPos pos = spawner.getBlockPos();
			Entity mob = spawner.getSpawner().getOrCreateDisplayEntity(mc.level, pos);
			String name = mob == null ? "empty" : mob.getType().getDescription().getString();
			spawners.add(new Spawner(pos, name, inStructure(pos)));
		}
		return spawners.isEmpty() ? null : spawners;
	}

	private boolean inStructure(BlockPos spawner) {
		for (BlockPos pos : BlockPos.betweenClosed(spawner.offset(-STRUCTURE_RADIUS, -STRUCTURE_RADIUS, -STRUCTURE_RADIUS), spawner.offset(STRUCTURE_RADIUS, STRUCTURE_RADIUS, STRUCTURE_RADIUS))) {
			if (STRUCTURE_BLOCKS.contains(mc.level.getBlockState(pos).getBlock())) return true;
		}
		return false;
	}

	/** Spawners found so far that players placed. */
	public int placedCount() {
		int count = 0;
		for (List<Spawner> spawners : scanner.results().values()) {
			for (Spawner spawner : spawners) {
				if (!spawner.natural) count++;
			}
		}
		return count;
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		Vec3 tracerStart = RenderUtil.tracerOrigin(event);
		for (List<Spawner> spawners : scanner.results().values()) {
			for (Spawner spawner : spawners) {
				if (spawner.natural && !showNatural.get()) continue;
				int color = spawner.natural ? naturalColor.get() : placedColor.get();
				Vec3 center = Vec3.atCenterOf(spawner.pos);
				RenderUtil.drawBox(event, new AABB(spawner.pos), (color & 0x00FFFFFF) | 0x30000000, color, 1.5f, throughWalls.get());
				if (!spawner.natural && tracers.get()) RenderUtil.drawLine(event, tracerStart, center, color, 1.5f, throughWalls.get());
				RenderUtil.drawText(event, spawner.mob + (spawner.natural ? "" : " (placed)"), center.add(0, 1, 0), color | 0xFF000000, 0.5f);
			}
		}
	}
}
