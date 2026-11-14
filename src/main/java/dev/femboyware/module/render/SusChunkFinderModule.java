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
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Chunks with the quiet signs of someone underground, the kind that don't show up in a normal ESP:
 * deepslate lying on its side (it only ever generates upright, so sideways means placed), cobbled
 * deepslate and cobblestone deep down, torches below the deepslate line and dirt paths far below
 * the surface. Each chunk with enough of them is boxed and labelled with what was found.
 */
public class SusChunkFinderModule extends Module {
	private static final int CHUNKS_PER_TICK = 3;
	private static final long RESCAN_MILLIS = 10_000;

	private record Sus(int rotated, int cobbled, int torches, int paths, int minY, int maxY, int x, int z) {
		int score() {
			return rotated * 4 + cobbled + torches * 2 + paths * 3;
		}

		String summary() {
			StringBuilder s = new StringBuilder();
			if (rotated > 0) s.append(rotated).append(" sideways deepslate");
			if (cobbled > 0) s.append(s.isEmpty() ? "" : ", ").append(cobbled).append(" cobble");
			if (torches > 0) s.append(s.isEmpty() ? "" : ", ").append(torches).append(" deep torches");
			if (paths > 0) s.append(s.isEmpty() ? "" : ", ").append(paths).append(" underground paths");
			return s.toString();
		}
	}

	private final NumberSetting range = add(new NumberSetting("Range", "Scan radius in chunks", 8, 1, 16, 1));
	private final NumberSetting minScore = add(new NumberSetting("Min Score", "How suspicious a chunk must be to show", 8, 1, 100, 1));
	private final BoolSetting notify = add(new BoolSetting("Notify", "Tell you when a sus chunk turns up", true));
	private final BoolSetting tracers = add(new BoolSetting("Tracers", "Lines to sus chunks", false));
	private final ColorSetting color = add(new ColorSetting("Color", "Highlight colour", 0xFFFFAA22));

	private final ChunkScanner<Sus> scanner = new ChunkScanner<>(this::scan);
	private final Set<ChunkPos> notified = new HashSet<>();

	public SusChunkFinderModule() {
		super("Sus Chunk Finder", "Finds chunks with subtle signs of players underground", Category.ESP);
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

	private static boolean interesting(BlockState state) {
		return state.is(Blocks.DEEPSLATE) || state.is(Blocks.COBBLED_DEEPSLATE) || state.is(Blocks.COBBLESTONE)
			|| state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH) || state.is(Blocks.DIRT_PATH);
	}

	private Sus scan(LevelChunk chunk) {
		if (mc.level.dimension() != Level.OVERWORLD) return null;
		int[] c = new int[4];
		int[] y = {Integer.MAX_VALUE, Integer.MIN_VALUE};
		ChunkScanner.forEachBlock(chunk, SusChunkFinderModule::interesting, (pos, state) -> {
			boolean hit = false;
			if (state.is(Blocks.DEEPSLATE)) {
				if (state.getValue(BlockStateProperties.AXIS) != Direction.Axis.Y) {
					c[0]++;
					hit = true;
				}
			} else if (state.is(Blocks.COBBLED_DEEPSLATE) || state.is(Blocks.COBBLESTONE)) {
				// Dungeons and ancient cities have these too, so only deep and only as a little weight
				if (pos.getY() < 0) {
					c[1]++;
					hit = true;
				}
			} else if (state.is(Blocks.DIRT_PATH)) {
				if (pos.getY() < 40) {
					c[3]++;
					hit = true;
				}
			} else if (pos.getY() < 0) {
				c[2]++;
				hit = true;
			}
			if (hit) {
				y[0] = Math.min(y[0], pos.getY());
				y[1] = Math.max(y[1], pos.getY());
			}
		});
		if (c[0] + c[1] + c[2] + c[3] == 0) return null;
		ChunkPos pos = chunk.getPos();
		return new Sus(c[0], c[1], c[2], c[3], y[0], y[1] + 1, pos.getMinBlockX(), pos.getMinBlockZ());
	}

	@Override
	public void onTick() {
		scanner.tick(range.getInt(), CHUNKS_PER_TICK, RESCAN_MILLIS);
		if (!notify.get()) return;
		for (var entry : scanner.results().entrySet()) {
			Sus sus = entry.getValue();
			if (sus.score() < minScore.getInt() || !notified.add(entry.getKey())) continue;
			ChatUtil.info("Sus Chunk: " + (sus.x + 8) + ", " + sus.minY + ", " + (sus.z + 8) + " (" + sus.summary() + ")");
		}
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		Vec3 origin = RenderUtil.tracerOrigin(event);
		int fill = (color.get() & 0x00FFFFFF) | 0x18000000;
		for (Sus sus : scanner.results().values()) {
			if (sus.score() < minScore.getInt()) continue;
			AABB box = new AABB(sus.x, sus.minY, sus.z, sus.x + 16, sus.maxY, sus.z + 16);
			RenderUtil.drawBox(event, box, fill, color.get(), 1.5f, true);
			Vec3 center = box.getCenter();
			if (tracers.get()) RenderUtil.drawLine(event, origin, center, color.get(), 1.5f, true);
			RenderUtil.drawText(event, "Sus: " + sus.summary(), new Vec3(center.x, box.maxY + 1, center.z), color.get() | 0xFF000000, 0.5f);
		}
	}
}
