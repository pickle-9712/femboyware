package dev.femboyware.module.render;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.util.ChunkScanner;
import dev.femboyware.util.RenderUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Shows amethyst geodes that DonutSMP's anti-xray hides: a chunk section's block palette still
 * lists amethyst buds after the buds themselves are swapped out, so it marks a spot on the geode
 * in that chunk. Geodes grow buds near bases, so this finds them through the ground. Ported from
 * the Anubis client's Amethyst Bypass (GPL-3.0, by 4ldenz, https://github.com/4ldenz/Anubis-client),
 * Bypass method.
 */
public class AmethystBypassModule extends Module {
	private final ColorSetting color = add(new ColorSetting("Color", "Colour of the markers", 0xFFC883FF));
	private final BoolSetting tracers = add(new BoolSetting("Tracers", "Lines to each marker", false));
	private final BoolSetting hideVisible = add(new BoolSetting("Hide When Visible", "Hide a block's box and tracer while you can see it yourself", true));

	private final ChunkScanner<BlockPos> scanner = new ChunkScanner<>(AmethystBypassModule::scan);

	public AmethystBypassModule() {
		super("Amethyst Bypass", "Shows amethyst geodes hidden by anti-xray", Category.ESP);
	}

	@Override
	protected void onEnable() {
		scanner.clear();
	}

	@Override
	public void onTick() {
		if (mc.level == null || mc.player == null) return;
		scanner.tick(mc.options.renderDistance().get(), 6, 30_000);
	}

	@Override
	public String getInfo() {
		return scanner.results().isEmpty() ? null : Integer.toString(scanner.results().size());
	}

	private static boolean isBud(BlockState state) {
		Block block = state.getBlock();
		return block == Blocks.SMALL_AMETHYST_BUD || block == Blocks.MEDIUM_AMETHYST_BUD || block == Blocks.LARGE_AMETHYST_BUD;
	}

	/** A spot on the geode in this chunk, or null when its palettes show no hidden buds. */
	private static BlockPos scan(LevelChunk chunk) {
		LevelChunkSection[] sections = chunk.getSections();
		int minSectionY = chunk.getMinSectionY();
		ChunkPos pos = chunk.getPos();
		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection section = sections[i];
			if (section == null) continue;
			PalettedContainer<BlockState> states = section.getStates();
			// A global palette "maybe has" everything, so only trust a local palette
			if (states.maybeHas(state -> false) || !states.maybeHas(AmethystBypassModule::isBud)) continue;
			int baseY = (minSectionY + i) * 16;
			BlockPos marker = pickMarker(chunk, section, baseY, pos);
			return marker != null ? marker : new BlockPos(pos.getMinBlockX() + 8, baseY + 8, pos.getMinBlockZ() + 8);
		}
		return null;
	}

	/** An air block touching the geode's amethyst, picked the same way each time. */
	private static BlockPos pickMarker(LevelChunk chunk, LevelChunkSection section, int baseY, ChunkPos pos) {
		BlockPos.MutableBlockPos neighbour = new BlockPos.MutableBlockPos();
		BlockPos best = null;
		int bestScore = Integer.MAX_VALUE;
		for (int y = 0; y < 16; y++)
			for (int z = 0; z < 16; z++)
				for (int x = 0; x < 16; x++) {
					if (!section.getBlockState(x, y, z).isAir()) continue;
					int wx = pos.getMinBlockX() + x, wy = baseY + y, wz = pos.getMinBlockZ() + z;
					boolean touches = false;
					for (Direction d : Direction.values()) {
						int nx = wx + d.getStepX(), nz = wz + d.getStepZ();
						if (nx >> 4 != pos.getMinBlockX() >> 4 || nz >> 4 != pos.getMinBlockZ() >> 4) continue;
						neighbour.set(nx, wy + d.getStepY(), nz);
						Block block = chunk.getBlockState(neighbour).getBlock();
						if (block == Blocks.AMETHYST_BLOCK || block == Blocks.BUDDING_AMETHYST) {
							touches = true;
							break;
						}
					}
					if (!touches) continue;
					int score = scatter(wx, wy, wz);
					if (score < bestScore) {
						bestScore = score;
						best = new BlockPos(wx, wy, wz);
					}
				}
		return best;
	}

	private static int scatter(int x, int y, int z) {
		long h = x * -7046029254386353131L ^ y * -4417276706812531889L ^ z * 1609587929392839161L;
		h ^= h >>> 29;
		h *= -4658895280553007687L;
		h ^= h >>> 32;
		return (int) Math.floorMod(h, 100L);
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		int c = color.get();
		Vec3 from = RenderUtil.tracerOrigin(event);
		for (BlockPos pos : scanner.results().values()) {
			if (hideVisible.get() && dev.femboyware.util.BlockSight.visible(pos)) continue;
			RenderUtil.drawBox(event, new AABB(pos), (c & 0xFFFFFF) | 0x40000000, c | 0xFF000000, 1.5f, true);
			if (tracers.get()) RenderUtil.drawLine(event, from, Vec3.atCenterOf(pos), c | 0xFF000000, 1.5f, true);
		}
	}
}
