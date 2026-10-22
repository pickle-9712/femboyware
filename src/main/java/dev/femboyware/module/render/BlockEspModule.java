package dev.femboyware.module.render;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.util.RenderUtil;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Highlights chosen blocks, optionally with tracers from the crosshair. */
public class BlockEspModule extends Module {
	/** Chunks scanned per tick, so a full rescan is spread out instead of freezing the game. */
	private static final int CHUNKS_PER_TICK = 4;
	/** A chunk is scanned again after this long, to pick up blocks placed or broken since. */
	private static final long RESCAN_MILLIS = 3000;

	private final StringSetting blocks = add(new StringSetting("Blocks", "Comma separated block ids, e.g. diamond_ore, ancient_debris",
		"diamond_ore, deepslate_diamond_ore, ancient_debris, spawner"));
	private final NumberSetting range = add(new NumberSetting("Range", "Scan radius in chunks", 4, 1, 12, 1));
	private final NumberSetting limit = add(new NumberSetting("Limit", "Most blocks drawn at once, nearest first", 200, 10, 1000, 10));
	private final ColorSetting fillColor = add(new ColorSetting("Fill", "Box fill colour", 0x30FF77C8));
	private final ColorSetting lineColor = add(new ColorSetting("Line", "Box outline colour", 0xFFFF77C8));
	private final BoolSetting throughWalls = add(new BoolSetting("Through Walls", "Draw every edge and see blocks behind terrain", true));
	private final BoolSetting tracers = add(new BoolSetting("Tracers", "Draw a line from the crosshair to each block", false));
	private final ColorSetting tracerColor = add(new ColorSetting("Tracer Color", "Tracer colour", 0xC0FF77C8)).visibleWhen(tracers::get);

	/** Matching blocks per chunk, from the last scan of that chunk. */
	private final Map<ChunkPos, List<BlockPos>> found = new HashMap<>();
	private final Map<ChunkPos, Long> scannedAt = new HashMap<>();
	private Set<Block> targets = Set.of();
	private String parsedFrom;

	public BlockEspModule() {
		super("Block Esp", "Highlights chosen blocks and can draw tracers to them", Category.ESP);
	}

	@Override
	public String getInfo() {
		int count = 0;
		for (List<BlockPos> list : found.values()) count += list.size();
		return String.valueOf(count);
	}

	@Override
	protected void onDisable() {
		found.clear();
		scannedAt.clear();
		parsedFrom = null;
	}

	@Override
	public void onTick() {
		if (!blocks.get().equals(parsedFrom)) {
			targets = parseBlocks(blocks.get());
			parsedFrom = blocks.get();
			found.clear();
			scannedAt.clear();
		}

		ChunkPos center = mc.player.getChunkPos();
		int radius = range.getInt();
		found.keySet().removeIf(pos -> pos.getChebyshevDistance(center) > radius);
		scannedAt.keySet().removeIf(pos -> pos.getChebyshevDistance(center) > radius);
		if (targets.isEmpty()) return;

		// Scan the stalest chunks first, nearest breaking ties
		long now = System.currentTimeMillis();
		List<ChunkPos> due = new ArrayList<>();
		for (int x = -radius; x <= radius; x++) {
			for (int z = -radius; z <= radius; z++) {
				ChunkPos pos = new ChunkPos(center.x + x, center.z + z);
				if (!mc.world.getChunkManager().isChunkLoaded(pos.x, pos.z)) continue;
				if (now - scannedAt.getOrDefault(pos, 0L) >= RESCAN_MILLIS) due.add(pos);
			}
		}
		due.sort(Comparator.<ChunkPos>comparingLong(pos -> scannedAt.getOrDefault(pos, 0L))
			.thenComparingInt(pos -> pos.getChebyshevDistance(center)));
		for (int i = 0; i < Math.min(CHUNKS_PER_TICK, due.size()); i++) {
			ChunkPos pos = due.get(i);
			found.put(pos, scan(mc.world.getChunk(pos.x, pos.z)));
			scannedAt.put(pos, now);
		}
	}

	private List<BlockPos> scan(WorldChunk chunk) {
		List<BlockPos> result = new ArrayList<>();
		ChunkSection[] sections = chunk.getSectionArray();
		int startX = chunk.getPos().getStartX();
		int startZ = chunk.getPos().getStartZ();
		for (int i = 0; i < sections.length; i++) {
			ChunkSection section = sections[i];
			// Most sections hold none of the blocks we want, and this check reads only the palette
			if (section.isEmpty() || !section.hasAny(state -> targets.contains(state.getBlock()))) continue;
			int startY = mc.world.sectionIndexToCoord(i) << 4;
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						BlockState state = section.getBlockState(x, y, z);
						if (targets.contains(state.getBlock())) result.add(new BlockPos(startX + x, startY + y, startZ + z));
					}
				}
			}
		}
		return result;
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (found.isEmpty()) return;
		Vec3d cam = event.camera().pos;
		List<BlockPos> visible = new ArrayList<>();
		for (List<BlockPos> list : found.values()) visible.addAll(list);
		if (visible.size() > limit.getInt()) {
			visible.sort(Comparator.comparingDouble(pos -> pos.getSquaredDistance(cam)));
			visible = visible.subList(0, limit.getInt());
		}

		Vec3d tracerStart = RenderUtil.tracerOrigin(event);
		for (BlockPos pos : visible) {
			RenderUtil.drawBox(event, new Box(pos), fillColor.get(), lineColor.get(), 1.5f, throughWalls.get());
			if (tracers.get()) RenderUtil.drawLine(event, tracerStart, Vec3d.ofCenter(pos), tracerColor.get(), 1.5f, throughWalls.get());
		}
	}

	private static Set<Block> parseBlocks(String text) {
		Set<Block> result = new HashSet<>();
		for (String part : text.split(",")) {
			Identifier id = Identifier.tryParse(part.trim().toLowerCase());
			if (id == null || part.isBlank()) continue;
			Registries.BLOCK.getOptionalValue(id).ifPresent(result::add);
		}
		return result;
	}
}
