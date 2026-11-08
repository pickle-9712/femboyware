package dev.femboyware.module.render;

import dev.femboyware.util.Mc;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.util.BlockMatcher;
import dev.femboyware.util.ChatUtil;
import dev.femboyware.util.ChunkScanner;
import dev.femboyware.util.RenderUtil;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Finds farms: redstone farms by blocks worldgen never places, crop farms by farmland and crops,
 * and animal, villager and iron farms by how many of those mobs are packed into one chunk.
 *
 * <p>Villages have crop plots, villagers and a golem or two of their own, so within a chunk of a
 * village bell only the strong redstone blocks count, and villagers are ignored there.
 */
public class FarmEspModule extends Module {
	private static final int CHUNKS_PER_TICK = 4;
	private static final long RESCAN_MILLIS = 5_000;
	/** Ticks between counting mobs, which move about and are cheap to recount. */
	private static final int ENTITY_INTERVAL = 20;
	private static final int STRONG_WEIGHT = 3;

	private record FarmBlocks(AABB bounds, int strong, int weak, boolean bell, Map<Block, Integer> counts) {}

	/** Mobs counted in one chunk. */
	private static final class Mobs {
		private int animals;
		private int villagers;
		private int golems;
		private AABB bounds;

		private void add(Entity entity) {
			AABB box = entity.getBoundingBox();
			bounds = bounds == null ? box : bounds.minmax(box);
		}
	}

	private final NumberSetting range = add(new NumberSetting("Range", "Scan radius in chunks", 8, 1, 16, 1));
	private final NumberSetting minScore = add(new NumberSetting("Min Score", "Strong blocks count 3, weak blocks 1; flag chunks scoring at least this", 8, 1, 200, 1));
	private final StringSetting strongBlocks = add(new StringSetting("Strong Blocks", "Comma separated ids or #tags of farm blocks that never generate",
		"observer, comparator, daylight_detector, beehive, melon_stem, attached_melon_stem, pumpkin_stem, attached_pumpkin_stem"));
	private final StringSetting weakBlocks = add(new StringSetting("Weak Blocks", "Comma separated ids or #tags of farm blocks that villages or temples also have",
		"farmland, wheat, carrots, potatoes, beetroots, nether_wart, composter, piston, sticky_piston, hopper, repeater"));
	private final NumberSetting minAnimals = add(new NumberSetting("Min Animals", "Animals in one chunk that make a pen, natural herds are 2 to 4", 12, 3, 100, 1));
	private final NumberSetting minVillagers = add(new NumberSetting("Min Villagers", "Villagers in one chunk that make a trading hall or breeder", 6, 2, 50, 1));
	private final NumberSetting minGolems = add(new NumberSetting("Min Golems", "Iron golems in one chunk that make an iron farm", 3, 2, 20, 1));
	private final BoolSetting ignoreVillages = add(new BoolSetting("Ignore Villages", "Within a chunk of a bell, only count strong blocks and skip villagers", true));
	private final BoolSetting throughWalls = add(new BoolSetting("Through Walls", "See farms through terrain", true));
	private final BoolSetting tracers = add(new BoolSetting("Tracers", "Draw lines to farms", false));
	private final BoolSetting notify = add(new BoolSetting("Notify", "Say in chat when a farm is found", true));
	private final ColorSetting color = add(new ColorSetting("Color", "Highlight colour", 0xFF99DD33));

	private final ChunkScanner<FarmBlocks> scanner = new ChunkScanner<>(this::scan);
	private final Set<ChunkPos> notified = new HashSet<>();
	private Map<ChunkPos, Mobs> mobs = Map.of();
	private Predicate<BlockState> strong = state -> false;
	private Predicate<BlockState> weak = state -> false;
	private String parsedStrong;
	private String parsedWeak;
	private int entityTimer;

	public FarmEspModule() {
		super("Farm Esp", "Finds crop, redstone and mob farms", Category.ESP);
	}

	@Override
	public String getInfo() {
		return Integer.toString(farmCount());
	}

	@Override
	protected void onDisable() {
		scanner.clear();
		notified.clear();
		mobs = Map.of();
	}

	@Override
	public void onTick() {
		if (!strongBlocks.get().equals(parsedStrong) || !weakBlocks.get().equals(parsedWeak)) {
			strong = BlockMatcher.parse(strongBlocks.get());
			weak = BlockMatcher.parse(weakBlocks.get());
			parsedStrong = strongBlocks.get();
			parsedWeak = weakBlocks.get();
			scanner.clear();
		}
		scanner.tick(range.getInt(), CHUNKS_PER_TICK, RESCAN_MILLIS);
		if (entityTimer-- <= 0) {
			mobs = countMobs();
			entityTimer = ENTITY_INTERVAL;
		}
		if (!notify.get()) return;
		for (ChunkPos pos : flaggedChunks()) {
			if (!notified.add(pos)) continue;
			AABB bounds = bounds(pos);
			if (bounds != null) ChatUtil.info("Farm at " + ChatUtil.coords(BlockPos.containing(bounds.getCenter())) + " (" + summary(pos) + ")");
		}
	}

	private FarmBlocks scan(LevelChunk chunk) {
		int[] counts = new int[2];
		boolean[] bell = {false};
		Map<Block, Integer> blocks = new HashMap<>();
		AABB[] bounds = new AABB[1];
		ChunkScanner.forEachBlock(chunk, state -> state.is(Blocks.BELL) || strong.test(state) || weak.test(state), (pos, state) -> {
			if (state.is(Blocks.BELL)) {
				bell[0] = true;
				return;
			}
			if (strong.test(state)) counts[0]++;
			else counts[1]++;
			blocks.merge(state.getBlock(), 1, Integer::sum);
			bounds[0] = bounds[0] == null ? new AABB(pos) : bounds[0].minmax(new AABB(pos));
		});
		if (bounds[0] == null && !bell[0]) return null;
		return new FarmBlocks(bounds[0], counts[0], counts[1], bell[0], blocks);
	}

	private Map<ChunkPos, Mobs> countMobs() {
		Map<ChunkPos, Mobs> counted = new HashMap<>();
		ChunkPos center = mc.player.chunkPosition();
		for (Entity entity : mc.level.entitiesForRendering()) {
			boolean animal = entity instanceof Animal;
			boolean villager = entity instanceof Villager;
			boolean golem = entity instanceof IronGolem;
			if (!animal && !villager && !golem) continue;
			ChunkPos pos = entity.chunkPosition();
			if (pos.getChessboardDistance(center) > range.getInt()) continue;
			Mobs chunk = counted.computeIfAbsent(pos, key -> new Mobs());
			if (animal) chunk.animals++;
			if (villager) chunk.villagers++;
			if (golem) chunk.golems++;
			chunk.add(entity);
		}
		return counted;
	}

	private boolean nearBell(ChunkPos center) {
		if (!ignoreVillages.get()) return false;
		for (int x = -1; x <= 1; x++) {
			for (int z = -1; z <= 1; z++) {
				FarmBlocks neighbour = scanner.results().get(new ChunkPos(Mc.chunkX(center) + x, Mc.chunkZ(center) + z));
				if (neighbour != null && neighbour.bell) return true;
			}
		}
		return false;
	}

	private int blockScore(ChunkPos pos) {
		FarmBlocks blocks = scanner.results().get(pos);
		if (blocks == null) return 0;
		return blocks.strong * STRONG_WEIGHT + (nearBell(pos) ? 0 : blocks.weak);
	}

	private boolean isFlagged(ChunkPos pos) {
		if (blockScore(pos) >= minScore.getInt()) return true;
		Mobs chunk = mobs.get(pos);
		if (chunk == null) return false;
		if (chunk.animals >= minAnimals.getInt() || chunk.golems >= minGolems.getInt()) return true;
		return chunk.villagers >= minVillagers.getInt() && !nearBell(pos);
	}

	private List<ChunkPos> flaggedChunks() {
		Set<ChunkPos> candidates = new HashSet<>(scanner.results().keySet());
		candidates.addAll(mobs.keySet());
		List<ChunkPos> flagged = new ArrayList<>();
		for (ChunkPos pos : candidates) {
			if (isFlagged(pos)) flagged.add(pos);
		}
		return flagged;
	}

	/** Chunks flagged as farms right now. */
	public int farmCount() {
		return flaggedChunks().size();
	}

	private AABB bounds(ChunkPos pos) {
		FarmBlocks blocks = scanner.results().get(pos);
		Mobs chunk = mobs.get(pos);
		AABB bounds = blocks == null ? null : blocks.bounds;
		if (chunk != null && chunk.bounds != null) bounds = bounds == null ? chunk.bounds : bounds.minmax(chunk.bounds);
		return bounds;
	}

	/** What makes the chunk a farm, e.g. "24x Farmland, 12x Wheat, 15 animals". */
	private String summary(ChunkPos pos) {
		List<String> parts = new ArrayList<>();
		FarmBlocks blocks = scanner.results().get(pos);
		if (blocks != null && blockScore(pos) >= minScore.getInt()) {
			blocks.counts.entrySet().stream()
				.sorted(Map.Entry.<Block, Integer>comparingByValue(Comparator.reverseOrder()))
				.limit(3)
				.forEach(entry -> parts.add(entry.getValue() + "x " + entry.getKey().getName().getString()));
		}
		Mobs chunk = mobs.get(pos);
		if (chunk != null) {
			if (chunk.animals >= minAnimals.getInt()) parts.add(chunk.animals + " animals");
			if (chunk.villagers >= minVillagers.getInt() && !nearBell(pos)) parts.add(chunk.villagers + " villagers");
			if (chunk.golems >= minGolems.getInt()) parts.add(chunk.golems + " iron golems");
		}
		return parts.stream().collect(Collectors.joining(", "));
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		Vec3 tracerStart = RenderUtil.tracerOrigin(event);
		int fill = (color.get() & 0x00FFFFFF) | 0x20000000;
		for (ChunkPos pos : flaggedChunks()) {
			AABB bounds = bounds(pos);
			if (bounds == null) continue;
			RenderUtil.drawBox(event, bounds, fill, color.get(), 1.5f, throughWalls.get());
			Vec3 center = bounds.getCenter();
			if (tracers.get()) RenderUtil.drawLine(event, tracerStart, center, color.get(), 1.5f, throughWalls.get());
			RenderUtil.drawText(event, "Farm: " + summary(pos), new Vec3(center.x, bounds.maxY + 1, center.z), color.get() | 0xFF000000, 0.5f);
		}
	}
}
