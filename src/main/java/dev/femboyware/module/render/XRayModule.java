package dev.femboyware.module.render;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.Mc;
import dev.femboyware.util.RenderUtil;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Outlines ores through the ground, each in its own colour. */
public class XRayModule extends Module {
	private static final int CHUNKS_PER_TICK = 4;
	private static final long RESCAN_MILLIS = 3000;

	private final BoolSetting diamonds = add(new BoolSetting("Diamond", "Diamond ore", true));
	private final BoolSetting debris = add(new BoolSetting("Ancient Debris", "Ancient debris", true));
	private final BoolSetting emeralds = add(new BoolSetting("Emerald", "Emerald ore", true));
	private final BoolSetting gold = add(new BoolSetting("Gold", "Gold and nether gold ore", true));
	private final BoolSetting iron = add(new BoolSetting("Iron", "Iron ore", false));
	private final BoolSetting redstone = add(new BoolSetting("Redstone", "Redstone ore", false));
	private final BoolSetting lapis = add(new BoolSetting("Lapis", "Lapis ore", false));
	private final BoolSetting copper = add(new BoolSetting("Copper", "Copper ore", false));
	private final BoolSetting coal = add(new BoolSetting("Coal", "Coal ore", false));
	private final BoolSetting quartz = add(new BoolSetting("Quartz", "Nether quartz ore", false));
	private final BoolSetting exposedOnly = add(new BoolSetting("Exposed Only", "Only ores touching air, so anti-xray's fake ores don't show", false));
	private final NumberSetting range = add(new NumberSetting("Range", "Scan radius in chunks", 4, 1, 12, 1));
	private final NumberSetting limit = add(new NumberSetting("Limit", "Most ores drawn at once, nearest first", 300, 10, 2000, 10));
	private final NumberSetting fill = add(new NumberSetting("Fill", "Opacity of the box fill", 0.2, 0, 1, 0.05));

	private record Ore(BlockPos pos, int color) {
	}

	private final Map<ChunkPos, List<Ore>> found = new HashMap<>();
	private final Map<ChunkPos, Long> scannedAt = new HashMap<>();
	private Map<Block, Integer> colors = Map.of();
	private String builtFor;

	public XRayModule() {
		super("XRay", "Highlight ores through blocks", Category.ESP);
	}

	@Override
	public String getInfo() {
		int count = 0;
		for (List<Ore> list : found.values()) count += list.size();
		return String.valueOf(count);
	}

	@Override
	protected void onDisable() {
		found.clear();
		scannedAt.clear();
		builtFor = null;
	}

	private String key() {
		StringBuilder key = new StringBuilder();
		for (BoolSetting setting : new BoolSetting[] {diamonds, debris, emeralds, gold, iron, redstone, lapis, copper, coal, quartz, exposedOnly}) {
			key.append(setting.get() ? '1' : '0');
		}
		return key.toString();
	}

	private Map<Block, Integer> buildColors() {
		Map<Block, Integer> map = new IdentityHashMap<>();
		if (diamonds.get()) put(map, 0x33EBFF, Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE);
		if (debris.get()) put(map, 0xA0522D, Blocks.ANCIENT_DEBRIS);
		if (emeralds.get()) put(map, 0x17DD62, Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE);
		if (gold.get()) put(map, 0xFFD700, Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.NETHER_GOLD_ORE);
		if (iron.get()) put(map, 0xD8AF93, Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE);
		if (redstone.get()) put(map, 0xFF2020, Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE);
		if (lapis.get()) put(map, 0x2050FF, Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE);
		if (copper.get()) put(map, 0xE07040, Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE);
		if (coal.get()) put(map, 0x303030, Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE);
		if (quartz.get()) put(map, 0xF0EDE6, Blocks.NETHER_QUARTZ_ORE);
		return map;
	}

	private static void put(Map<Block, Integer> map, int color, Block... blocks) {
		for (Block block : blocks) map.put(block, color);
	}

	@Override
	public void onTick() {
		String key = key();
		if (!key.equals(builtFor)) {
			builtFor = key;
			colors = buildColors();
			found.clear();
			scannedAt.clear();
		}
		ChunkPos center = mc.player.chunkPosition();
		int radius = range.getInt();
		found.keySet().removeIf(pos -> pos.getChessboardDistance(center) > radius);
		scannedAt.keySet().removeIf(pos -> pos.getChessboardDistance(center) > radius);
		if (colors.isEmpty()) return;

		long now = System.currentTimeMillis();
		List<ChunkPos> due = new ArrayList<>();
		for (int x = -radius; x <= radius; x++) {
			for (int z = -radius; z <= radius; z++) {
				ChunkPos pos = new ChunkPos(Mc.chunkX(center) + x, Mc.chunkZ(center) + z);
				if (!mc.level.getChunkSource().hasChunk(Mc.chunkX(pos), Mc.chunkZ(pos))) continue;
				if (now - scannedAt.getOrDefault(pos, 0L) >= RESCAN_MILLIS) due.add(pos);
			}
		}
		due.sort(Comparator.<ChunkPos>comparingLong(pos -> scannedAt.getOrDefault(pos, 0L))
			.thenComparingInt(pos -> pos.getChessboardDistance(center)));
		for (int i = 0; i < Math.min(CHUNKS_PER_TICK, due.size()); i++) {
			ChunkPos pos = due.get(i);
			found.put(pos, scan(mc.level.getChunk(Mc.chunkX(pos), Mc.chunkZ(pos))));
			scannedAt.put(pos, now);
		}
	}

	private List<Ore> scan(LevelChunk chunk) {
		List<Ore> result = new ArrayList<>();
		LevelChunkSection[] sections = chunk.getSections();
		int startX = chunk.getPos().getMinBlockX();
		int startZ = chunk.getPos().getMinBlockZ();
		Map<Block, Integer> wanted = colors;
		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection section = sections[i];
			if (section.hasOnlyAir() || !section.maybeHas(state -> wanted.containsKey(state.getBlock()))) continue;
			int startY = mc.level.getSectionYFromSectionIndex(i) << 4;
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						BlockState state = section.getBlockState(x, y, z);
						Integer color = wanted.get(state.getBlock());
						if (color == null) continue;
						BlockPos pos = new BlockPos(startX + x, startY + y, startZ + z);
						if (exposedOnly.get() && !exposed(pos)) continue;
						result.add(new Ore(pos, color));
					}
				}
			}
		}
		return result;
	}

	private boolean exposed(BlockPos pos) {
		for (Direction direction : Direction.values()) {
			BlockState next = mc.level.getBlockState(pos.relative(direction));
			if (!next.canOcclude()) return true;
		}
		return false;
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (found.isEmpty()) return;
		Vec3 cam = event.camera().pos;
		List<Ore> visible = new ArrayList<>();
		for (List<Ore> list : found.values()) visible.addAll(list);
		if (visible.size() > limit.getInt()) {
			visible.sort(Comparator.comparingDouble(ore -> ore.pos().distToCenterSqr(cam)));
			visible = visible.subList(0, limit.getInt());
		}
		int alpha = (int) (fill.get() * 255);
		for (Ore ore : visible) {
			RenderUtil.drawBox(event, new AABB(ore.pos()), (alpha << 24) | ore.color(), 0xFF000000 | ore.color(), 1.5f, true);
		}
	}
}
