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
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Chunks with the quiet signs of someone underground, the kind that don't show up in a normal ESP:
 * deepslate lying on its side (it only ever generates upright, so sideways means placed), lights
 * and everyday blocks nothing generates down there, dirt paths far below the surface, and strip
 * mine tunnels (dead straight, one wide and two tall, which caves and mineshafts never are). Each
 * chunk with enough of them is boxed and labelled, and so is every block that counted.
 */
public class SusChunkFinderModule extends Module {
	private static final int CHUNKS_PER_TICK = 3;
	private static final long RESCAN_MILLIS = 10_000;
	private static final int MAX_SPOTS = 32;
	private static final int ROTATED = 0, LIGHTS = 1, PATHS = 2, PLACED = 3, TUNNELS = 4;
	private static final int[] WEIGHT = {4, 2, 3, 3, 5};
	private static final String[] LABEL = {"sideways deepslate", "placed lights", "underground paths", "placed blocks", "strip mine tunnels"};

	private record Sus(int[] counts, List<BlockPos> spots, int minY, int maxY, int x, int z) {
		int score() {
			int s = 0;
			for (int i = 0; i < counts.length; i++) s += counts[i] * WEIGHT[i];
			return s;
		}

		String summary() {
			StringBuilder s = new StringBuilder();
			for (int i = 0; i < counts.length; i++) {
				if (counts[i] == 0) continue;
				if (!s.isEmpty()) s.append(", ");
				s.append(counts[i]).append(' ').append(LABEL[i]);
			}
			return s.toString();
		}
	}

	private final NumberSetting range = add(new NumberSetting("Range", "Scan radius in chunks", 8, 1, 16, 1));
	private final NumberSetting yLimit = add(new NumberSetting("Max Y", "Only flag things below this height (bases hide under y 0)", 0, -64, 320, 1));
	private final NumberSetting minScore = add(new NumberSetting("Min Score", "How suspicious a chunk must be to show", 8, 1, 100, 1));
	private final BoolSetting tunnels = add(new BoolSetting("Tunnels", "Count strip mine tunnels: straight, one wide and two tall", true));
	private final NumberSetting tunnelLength = add(new NumberSetting("Tunnel Length", "How long a straight tunnel has to be to count", 8, 5, 16, 1));
	private final BoolSetting lights = add(new BoolSetting("Lights", "Count torches, lanterns, glowstone and other placed lights", true));
	private final BoolSetting placed = add(new BoolSetting("Placed Blocks", "Count crafting tables, furnaces, beds, ladders, glass, wool and concrete", true));
	private final BoolSetting showBlocks = add(new BoolSetting("Show Blocks", "Box every block that counted, not just the chunk", true));
	private final BoolSetting notify = add(new BoolSetting("Notify", "Tell you when a sus chunk turns up", true));
	private final BoolSetting tracers = add(new BoolSetting("Tracers", "Lines to sus chunks", false));
	private final ColorSetting color = add(new ColorSetting("Color", "Highlight colour", 0xFFFFAA22));

	private final ChunkScanner<Sus> scanner = new ChunkScanner<>(this::scan);
	private final Set<ChunkPos> notified = new HashSet<>();

	public SusChunkFinderModule() {
		super("Sus Chunk Finder", "Finds chunks with subtle signs of players underground", Category.ESP);
		tunnelLength.visibleWhen(tunnels::get);
	}

	@Override
	public String getInfo() {
		int n = 0;
		for (Sus s : scanner.results().values()) if (s.score() >= minScore.getInt()) n++;
		return Integer.toString(n);
	}

	@Override
	protected void onDisable() {
		scanner.clear();
		notified.clear();
	}

	/** Lights nothing generates below the deepslate line (soul lanterns are left out: ancient cities have them). */
	private static boolean light(BlockState state) {
		return state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH) || state.is(Blocks.SOUL_TORCH) || state.is(Blocks.SOUL_WALL_TORCH)
			|| state.is(Blocks.REDSTONE_TORCH) || state.is(Blocks.REDSTONE_WALL_TORCH) || state.is(Blocks.COPPER_TORCH) || state.is(Blocks.COPPER_WALL_TORCH)
			|| state.is(Blocks.LANTERN) || state.is(Blocks.GLOWSTONE) || state.is(Blocks.SEA_LANTERN) || state.is(Blocks.JACK_O_LANTERN)
			|| state.is(Blocks.SHROOMLIGHT) || state.is(Blocks.OCHRE_FROGLIGHT) || state.is(Blocks.VERDANT_FROGLIGHT) || state.is(Blocks.PEARLESCENT_FROGLIGHT)
			|| state.is(Blocks.END_ROD) || state.is(Blocks.REDSTONE_LAMP);
	}

	/** Everyday blocks that only a player leaves underground. */
	private static boolean placedBlock(BlockState state) {
		if (state.is(Blocks.CRAFTING_TABLE) || state.is(Blocks.FURNACE) || state.is(Blocks.BLAST_FURNACE) || state.is(Blocks.SMOKER)
			|| state.is(BlockTags.BEDS) || state.is(Blocks.LADDER) || state.is(Blocks.SCAFFOLDING) || state.is(Blocks.GLASS) || state.is(Blocks.GLASS_PANE)
			|| state.is(BlockTags.WOOL) || state.is(BlockTags.WOOL_CARPETS)) return true;
		String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
		return id.endsWith("_concrete") || id.endsWith("_concrete_powder") || id.endsWith("stained_glass") || id.endsWith("stained_glass_pane");
	}

	private boolean interesting(BlockState state) {
		return state.is(Blocks.DEEPSLATE) || state.is(Blocks.DIRT_PATH) || (lights.get() && light(state)) || (placed.get() && placedBlock(state));
	}

	private Sus scan(LevelChunk chunk) {
		if (mc.level.dimension() != Level.OVERWORLD) return null;
		int limit = yLimit.getInt();
		int[] c = new int[5];
		int[] y = {Integer.MAX_VALUE, Integer.MIN_VALUE};
		List<BlockPos> spots = new ArrayList<>();
		ChunkScanner.forEachBlock(chunk, this::interesting, (pos, state) -> {
			if (pos.getY() >= limit) return;
			int kind = -1;
			if (state.is(Blocks.DEEPSLATE)) {
				if (state.getValue(BlockStateProperties.AXIS) != Direction.Axis.Y) kind = ROTATED;
			} else if (state.is(Blocks.DIRT_PATH)) {
				if (pos.getY() < 40) kind = PATHS;
			} else if (lights.get() && light(state)) {
				if (pos.getY() < 0) kind = LIGHTS;
			} else if (pos.getY() < 0) {
				kind = PLACED;
			}
			if (kind < 0) return;
			c[kind]++;
			if (spots.size() < MAX_SPOTS) spots.add(pos);
			y[0] = Math.min(y[0], pos.getY());
			y[1] = Math.max(y[1], pos.getY());
		});
		if (tunnels.get()) findTunnels(chunk, Math.min(limit, 0), c, y, spots);
		int total = 0;
		for (int n : c) total += n;
		if (total == 0) return null;
		ChunkPos pos = chunk.getPos();
		return new Sus(c, spots, y[0], y[1] + 1, pos.getMinBlockX(), pos.getMinBlockZ());
	}

	// ---------------------------------------------------------------- strip mine tunnels

	/** Straight runs, along X and along Z, of cells that are a 1 wide, 2 tall corridor walled in on every side. */
	private void findTunnels(LevelChunk chunk, int limit, int[] c, int[] y, List<BlockPos> spots) {
		int minX = chunk.getPos().getMinBlockX(), minZ = chunk.getPos().getMinBlockZ();
		int bottom = mc.level.getMinY() + 1;
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		int need = tunnelLength.getInt();
		LevelChunkSection[] sections = chunk.getSections();
		for (int i = 0; i < sections.length; i++) {
			if (sections[i].hasOnlyAir()) continue;
			int baseY = mc.level.getSectionYFromSectionIndex(i) << 4;
			if (baseY >= limit) break;
			for (int ly = 0; ly < 16; ly++) {
				int wy = baseY + ly;
				if (wy < bottom || wy + 1 >= limit) continue;
				for (int a = 0; a < 16; a++) {
					int runX = 0, runZ = 0;
					for (int b = 0; b < 16; b++) {
						// along X: b is x, a is z
						runX = corridor(cursor, minX + b, wy, minZ + a, true) ? runX + 1 : 0;
						if (runX == need) addTunnel(c, y, spots, new BlockPos(minX + b - need + 1, wy, minZ + a));
						// along Z: a is x, b is z
						runZ = corridor(cursor, minX + a, wy, minZ + b, false) ? runZ + 1 : 0;
						if (runZ == need) addTunnel(c, y, spots, new BlockPos(minX + a, wy, minZ + b - need + 1));
					}
				}
			}
		}
	}

	private static void addTunnel(int[] c, int[] y, List<BlockPos> spots, BlockPos start) {
		c[TUNNELS]++;
		if (spots.size() < MAX_SPOTS) spots.add(start);
		y[0] = Math.min(y[0], start.getY());
		y[1] = Math.max(y[1], start.getY() + 1);
	}

	/** Air here and above, solid floor and ceiling, and solid on both sides of the run at both heights. */
	private boolean corridor(BlockPos.MutableBlockPos cursor, int x, int y, int z, boolean alongX) {
		if (!air(cursor, x, y, z) || !air(cursor, x, y + 1, z)) return false;
		if (!solid(cursor, x, y - 1, z) || !solid(cursor, x, y + 2, z)) return false;
		int sx = alongX ? 0 : 1, sz = alongX ? 1 : 0;
		return solid(cursor, x + sx, y, z + sz) && solid(cursor, x - sx, y, z - sz)
			&& solid(cursor, x + sx, y + 1, z + sz) && solid(cursor, x - sx, y + 1, z - sz);
	}

	private boolean air(BlockPos.MutableBlockPos cursor, int x, int y, int z) {
		return mc.level.getBlockState(cursor.set(x, y, z)).isAir();
	}

	private boolean solid(BlockPos.MutableBlockPos cursor, int x, int y, int z) {
		BlockState state = mc.level.getBlockState(cursor.set(x, y, z));
		return !state.isAir() && state.getFluidState().isEmpty() && state.canOcclude();
	}

	// ---------------------------------------------------------------- output

	@Override
	public void onTick() {
		scanner.tick(range.getInt(), CHUNKS_PER_TICK, RESCAN_MILLIS);
		if (!notify.get() || mc.player == null) return;
		for (var entry : scanner.results().entrySet()) {
			Sus sus = entry.getValue();
			if (sus.score() < minScore.getInt() || !notified.add(entry.getKey())) continue;
			int distance = (int) Math.sqrt(mc.player.distanceToSqr(sus.x + 8, sus.minY, sus.z + 8));
			ChatUtil.info("Sus Chunk: " + (sus.x + 8) + ", " + sus.minY + ", " + (sus.z + 8) + " (" + distance + "m, score " + sus.score() + ": " + sus.summary() + ")");
		}
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		Vec3 origin = RenderUtil.tracerOrigin(event);
		int line = color.get() | 0xFF000000;
		int fill = (color.get() & 0x00FFFFFF) | 0x18000000;
		int spotFill = (color.get() & 0x00FFFFFF) | 0x50000000;
		for (Sus sus : scanner.results().values()) {
			if (sus.score() < minScore.getInt()) continue;
			AABB box = new AABB(sus.x, sus.minY, sus.z, sus.x + 16, sus.maxY, sus.z + 16);
			RenderUtil.drawBox(event, box, fill, color.get(), 1.5f, true);
			Vec3 center = box.getCenter();
			if (tracers.get()) RenderUtil.drawLine(event, origin, center, color.get(), 1.5f, true);
			RenderUtil.drawText(event, "Sus " + sus.score() + ": " + sus.summary(), new Vec3(center.x, box.maxY + 1, center.z), line, 0.5f);
			if (showBlocks.get()) for (BlockPos spot : sus.spots()) RenderUtil.drawBox(event, new AABB(spot), spotFill, line, 1.5f, true);
		}
	}
}
