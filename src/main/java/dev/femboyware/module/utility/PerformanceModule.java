package dev.femboyware.module.utility;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import net.minecraft.client.PrioritizeChunkUpdates;

/**
 * Smoother chunk updates (blocks you break or place, and chunks near you, rebuilt straight away)
 * and an optional frame cap for recording. Your own video settings come back when it's off.
 */
public class PerformanceModule extends Module {
	public enum ChunkUpdates {
		DEFAULT,
		PLAYER_AFFECTED,
		NEARBY
	}

	private final EnumSetting<ChunkUpdates> chunkUpdates = add(new EnumSetting<>("Chunk Updates", "Which chunk rebuilds happen at once instead of in the background", ChunkUpdates.PLAYER_AFFECTED));
	private final NumberSetting fpsCap = add(new NumberSetting("Recording FPS", "Cap the frame rate, steadier for recording; 0 leaves yours", 0, 0, 240, 10));

	private PrioritizeChunkUpdates savedChunks;
	private Integer savedFps;

	public PerformanceModule() {
		super("Performance", "Smoother chunk updates and an optional recording FPS cap", Category.MISC);
	}

	@Override
	protected void onEnable() {
		savedChunks = mc.options.prioritizeChunkUpdates().get();
		savedFps = mc.options.framerateLimit().get();
		apply();
	}

	@Override
	protected void onDisable() {
		if (savedChunks != null) mc.options.prioritizeChunkUpdates().set(savedChunks);
		if (savedFps != null) mc.options.framerateLimit().set(savedFps);
		savedChunks = null;
		savedFps = null;
	}

	@Override
	public void onTick() {
		apply();
	}

	private void apply() {
		PrioritizeChunkUpdates want = switch (chunkUpdates.get()) {
			case DEFAULT -> savedChunks != null ? savedChunks : PrioritizeChunkUpdates.NONE;
			case PLAYER_AFFECTED -> PrioritizeChunkUpdates.PLAYER_AFFECTED;
			case NEARBY -> PrioritizeChunkUpdates.NEARBY;
		};
		if (mc.options.prioritizeChunkUpdates().get() != want) mc.options.prioritizeChunkUpdates().set(want);
		int fps = fpsCap.getInt() > 0 ? fpsCap.getInt() : savedFps != null ? savedFps : mc.options.framerateLimit().get();
		if (mc.options.framerateLimit().get() != fps) mc.options.framerateLimit().set(fps);
	}
}
