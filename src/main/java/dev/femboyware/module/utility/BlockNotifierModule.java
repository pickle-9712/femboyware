package dev.femboyware.module.utility;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.util.BlockMatcher;
import dev.femboyware.util.ChatUtil;
import dev.femboyware.util.ChunkScanner;
import dev.femboyware.util.Toasts;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

/**
 * A toast with the block and where it is whenever one of your chosen blocks loads in (spawners by
 * default). Ported from the Anubis client's Block Notifier (GPL-3.0, by 4ldenz,
 * https://github.com/4ldenz/Anubis-client).
 */
public class BlockNotifierModule extends Module {
	private final StringSetting blocks = add(new StringSetting("Blocks", "Blocks to tell you about, comma separated (e.g. spawner, ancient_debris)", "spawner"));
	private final NumberSetting range = add(new NumberSetting("Range", "Chunks around you to look in", 8, 1, 32, 1));
	private final BoolSetting sound = add(new BoolSetting("Sound", "Play a ding with it", true));
	private final BoolSetting chat = add(new BoolSetting("Chat", "Also say it in chat", false));

	private final ChunkScanner<List<BlockPos>> scanner = new ChunkScanner<>(this::scan);
	private final Set<Long> known = new HashSet<>();
	private Predicate<BlockState> filter = state -> false;
	private String parsedFrom;
	private ResourceKey<Level> dimension;

	public BlockNotifierModule() {
		super("Block Notifier", "A toast when one of your chosen blocks loads in", Category.MISC);
	}

	@Override
	protected void onEnable() {
		scanner.clear();
		known.clear();
		dimension = null;
		parsedFrom = null;
	}

	private List<BlockPos> scan(LevelChunk chunk) {
		List<BlockPos> found = new ArrayList<>();
		ChunkScanner.forEachBlock(chunk, filter, (pos, state) -> found.add(pos.immutable()));
		return found.isEmpty() ? null : found;
	}

	@Override
	public void onTick() {
		if (mc.level == null || mc.player == null) return;
		if (!blocks.get().equals(parsedFrom)) {
			parsedFrom = blocks.get();
			filter = BlockMatcher.parse(parsedFrom);
			scanner.clear();
			known.clear();
		}
		if (mc.level.dimension() != dimension) {
			dimension = mc.level.dimension();
			known.clear();
		}
		scanner.tick(range.get().intValue(), 4, 10_000);
		Map<Block, List<BlockPos>> fresh = new LinkedHashMap<>();
		for (List<BlockPos> list : scanner.results().values())
			for (BlockPos pos : list)
				if (known.add(pos.asLong())) {
					BlockState state = mc.level.getBlockState(pos);
					if (filter.test(state)) fresh.computeIfAbsent(state.getBlock(), b -> new ArrayList<>()).add(pos);
				}
		if (known.size() > 50_000) known.clear();
		for (var entry : fresh.entrySet()) {
			BlockPos nearest = null;
			double best = Double.MAX_VALUE;
			for (BlockPos pos : entry.getValue()) {
				double d = mc.player.distanceToSqr(Vec3.atCenterOf(pos));
				if (d < best) {
					best = d;
					nearest = pos;
				}
			}
			String name = entry.getKey().getName().getString();
			int n = entry.getValue().size();
			String where = "at " + nearest.getX() + ", " + nearest.getY() + ", " + nearest.getZ();
			Toasts.show(n > 1 ? n + "x " + name : name, (n > 1 ? "nearest " : "") + where);
			if (chat.get()) ChatUtil.info((n > 1 ? n + " " + name + ", nearest " : name + " ") + where);
		}
		if (!fresh.isEmpty() && sound.get()) mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.7f, 1.4f);
	}
}
