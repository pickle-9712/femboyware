package dev.femboyware.module.render;

import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.ChunkScanner;
import dev.femboyware.util.RenderUtil;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

import java.util.HashSet;
import java.util.Set;

/**
 * Tells freshly generated chunks from ones players have been to before, for finding bases: a
 * line of old chunks through new ones is someone's trail.
 *
 * <p>When a chunk generates, its water and lava start flowing straight away, and the server sends
 * us those updates as the chunk arrives: that chunk is new. A chunk that arrives with flowing
 * liquid already in it was generated earlier and has settled: that chunk is old.
 */
public class NewChunksModule extends Module {
	private final BoolSetting showNew = add(new BoolSetting("New Chunks", "Show chunks that just generated", true));
	private final BoolSetting showOld = add(new BoolSetting("Old Chunks", "Show chunks players have been to before", true));
	private final ColorSetting newColor = add(new ColorSetting("New Color", "Colour of new chunks", 0x55FF3355)).visibleWhen(showNew::get);
	private final ColorSetting oldColor = add(new ColorSetting("Old Color", "Colour of old chunks", 0x4033FF88)).visibleWhen(showOld::get);
	private final NumberSetting height = add(new NumberSetting("Height", "Blocks below your feet to draw the chunk squares at", 1, 0, 20, 1));

	private final Set<Long> newChunks = new HashSet<>();
	private final Set<Long> oldChunks = new HashSet<>();
	private ClientWorld world;

	public NewChunksModule() {
		super("New Chunks", "Shows new and old chunks, for finding bases and trails", Category.ESP);
		ClientChunkEvents.CHUNK_LOAD.register((loadedWorld, chunk) -> {
			if (isEnabled()) onChunkLoad(chunk);
		});
	}

	@Override
	public String getInfo() {
		return newChunks.size() + " new";
	}

	@Override
	protected void onDisable() {
		newChunks.clear();
		oldChunks.clear();
	}

	@Override
	public void onTick() {
		if (mc.world != world) {
			newChunks.clear();
			oldChunks.clear();
			world = mc.world;
		}
	}

	/** A liquid starting to flow as its chunk arrives means the chunk just generated. */
	@Override
	public void onBlockUpdate(BlockPos pos, BlockState state) {
		if (!flowing(state.getFluidState())) return;
		long chunk = ChunkPos.toLong(pos.getX() >> 4, pos.getZ() >> 4);
		if (!oldChunks.contains(chunk)) newChunks.add(chunk);
	}

	/** Flowing liquid already in a chunk as it arrives means it was generated before and has settled. */
	private void onChunkLoad(WorldChunk chunk) {
		long key = chunk.getPos().toLong();
		if (newChunks.contains(key)) return;
		boolean[] found = {false};
		ChunkScanner.forEachBlock(chunk, state -> flowing(state.getFluidState()), (pos, state) -> found[0] = true);
		if (found[0]) oldChunks.add(key);
	}

	private static boolean flowing(FluidState fluid) {
		return !fluid.isEmpty() && !fluid.isStill();
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		double y = Math.floor(mc.player.getY()) - height.get();
		if (showNew.get()) draw(event, newChunks, newColor.get(), y);
		if (showOld.get()) draw(event, oldChunks, oldColor.get(), y);
	}

	private void draw(Render3DEvent event, Set<Long> chunks, int color, double y) {
		int outline = color | 0xFF000000;
		for (long key : chunks) {
			int x = ChunkPos.getPackedX(key) << 4;
			int z = ChunkPos.getPackedZ(key) << 4;
			RenderUtil.drawBox(event, new Box(x, y, z, x + 16, y + 0.02, z + 16), color, outline, 1.5f);
		}
	}
}
