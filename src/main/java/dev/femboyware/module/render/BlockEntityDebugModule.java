package dev.femboyware.module.render;

import dev.femboyware.event.PacketEvent;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.util.BlockEntityLeakTracker;
import dev.femboyware.util.ChatUtil;
import dev.femboyware.util.LightLeakScanner;
import dev.femboyware.util.RenderUtil;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Boxes every block entity the server sent (chests, spawners, shulkers...), even ones anti-xray
 * hid by swapping the block out, plus blocks it gives away through block events, sounds and light.
 * Ported from the Anubis client's Block Entity Debug (GPL-3.0, by 4ldenz,
 * https://github.com/4ldenz/Anubis-client), with femboyware's boxes in place of its ESP layer.
 */
public class BlockEntityDebugModule extends Module {
	private static final int MAX_CHAT_PER_TICK = 5, MAX_NBT_CHARS = 200;

	private final NumberSetting maxY = add(new NumberSetting("Max Y", "Only show things at or below this height (320 shows everything)", 0, -64, 320, 1));
	private final BoolSetting underground = add(new BoolSetting("Underground Only", "Only things buried under real ground (leaves don't count), which drops trees, caves' open air and surface builds", true));
	private final NumberSetting minDepth = add(new NumberSetting("Min Depth", "Blocks of ground above it, at least", 6, 1, 64, 1));
	private final BoolSetting groupTracers = add(new BoolSetting("Group Tracers", "One tracer per cluster instead of one per block", true));
	private final BoolSetting blockFilter = add(new BoolSetting("Block Filter", "Only the blocks listed in Blocks", false));
	private final StringSetting blocks = add(new StringSetting("Blocks", "Block ids, comma separated", "chest,trapped_chest,barrel,shulker_box,spawner,ender_chest,hopper,furnace,beacon"));
	private final BoolSetting chat = add(new BoolSetting("Chat Notifications", "Say in chat when a new one is found", false));
	private final BoolSetting showNbt = add(new BoolSetting("Show NBT", "Add the block entity's data to the chat message", true));
	private final BoolSetting hints = add(new BoolSetting("Hidden Block Hints", "Blocks given away by events and sounds", true));
	private final BoolSetting lightLeaks = add(new BoolSetting("Light Leaks", "Hidden blocks given away by their light", true));
	private final NumberSetting minLight = add(new NumberSetting("Min Leak Light", "Ignore leaks dimmer than this; natural glowing blocks like glow lichen give 7 at most, torches and lanterns 14 or more", 8, 1, 15, 1));
	private final BoolSetting ignorePvp = add(new BoolSetting("Ignore Ender Chests", "Ender chests are PvP gear people place anywhere, not a base", true));
	private final ColorSetting color = add(new ColorSetting("Color", "Block entities the server sent", 0xFFFF8C1A));
	private final ColorSetting hintColor = add(new ColorSetting("Hint Color", "Blocks only given away by events, sounds or light", 0xFF6EC8FF));
	private final NumberSetting range = add(new NumberSetting("Range", "Furthest to draw", 256, 16, 1024, 16));
	private final BoolSetting tracers = add(new BoolSetting("Tracers", "Lines to each one", true));
	private final BoolSetting hideVisible = add(new BoolSetting("Hide When Visible", "Hide a block's box and tracer while you can see it yourself", true));
	private final BoolSetting spawnerBeams = add(new BoolSetting("Spawner Beams", "A beam up from every spawner", true));

	private record Found(BlockPos pos, Block block, boolean real) {
	}

	private final Map<Long, Found> found = new java.util.HashMap<>();
	private final LightLeakScanner lightScanner = new LightLeakScanner();
	private int seenGeneration = -1;
	private String lastFilter = "";
	private boolean lastLight;
	private int lastMinLight = -1;

	public BlockEntityDebugModule() {
		super("Block Entity Debug", "Boxes block entities, even ones anti-xray hides", Category.ESP);
		showNbt.visibleWhen(chat::get);
		lightLeaks.visibleWhen(hints::get);
		minLight.visibleWhen(() -> hints.get() && lightLeaks.get());
		blocks.visibleWhen(blockFilter::get);
		minDepth.visibleWhen(underground::get);
	}

	@Override
	protected void onEnable() {
		BlockEntityLeakTracker.recordAdditions(true);
		resync();
		restartLightScan();
	}

	@Override
	protected void onDisable() {
		BlockEntityLeakTracker.recordAdditions(false);
		lightScanner.clear();
		found.clear();
	}

	private boolean lightLeaksOn() {
		return hints.get() && lightLeaks.get();
	}

	private void restartLightScan() {
		lightScanner.clear();
		lightScanner.minLight(minLight.getInt());
		if (lightLeaksOn() && mc.level != null && mc.player != null) lightScanner.queueLoaded(mc.level, mc.player.blockPosition(), mc.options.getEffectiveRenderDistance());
	}

	private String filterKey() {
		return minLight.get() + "|" + ignorePvp.get() + "|" + maxY.get() + "|" + underground.get() + "|" + minDepth.get() + "|" + blockFilter.get() + blocks.get() + hints.get() + lightLeaks.get();
	}

	private void resync() {
		found.clear();
		lastFilter = filterKey();
		seenGeneration = BlockEntityLeakTracker.generation();
		while (BlockEntityLeakTracker.pollAdded() != null) {
		}
		for (Map.Entry<BlockPos, BlockEntityLeakTracker.Entry> entry : BlockEntityLeakTracker.view().entrySet()) accept(entry.getKey(), entry.getValue());
	}

	@Override
	public void onPacketReceive(PacketEvent event) {
		// The tracker itself listens all the time (ModuleManager feeds it), this only adds light rescans
		if (lightLeaksOn()) {
			var packet = event.getPacket();
			mc.execute(() -> lightScanner.onPacketApplied(packet));
		}
		// Don't let the server wipe a block entity it really sent by hiding the block again
		if (event.getPacket() instanceof ClientboundBlockUpdatePacket packet && BlockEntityLeakTracker.containsReal(packet.getPos())) {
			BlockState state = packet.getBlockState();
			if (state.isAir() || state.is(Blocks.STONE) || state.is(Blocks.DEEPSLATE)) event.cancel();
		}
	}

	@Override
	public void onTick() {
		if (lightLeaksOn() != lastLight || minLight.getInt() != lastMinLight) {
			lastLight = lightLeaksOn();
			lastMinLight = minLight.getInt();
			restartLightScan();
		}
		if (lightLeaksOn() && mc.level != null) {
			lightScanner.minLight(minLight.getInt());
			lightScanner.tick(mc.level);
		}
		if (BlockEntityLeakTracker.generation() != seenGeneration || !filterKey().equals(lastFilter)) {
			resync();
			return;
		}
		int announced = 0, more = 0;
		BlockPos pos;
		while ((pos = BlockEntityLeakTracker.pollAdded()) != null) {
			BlockEntityLeakTracker.Entry entry = BlockEntityLeakTracker.view().get(pos);
			if (entry == null || !accept(pos, entry) || !chat.get()) continue;
			if (announced++ < MAX_CHAT_PER_TICK) announce(pos, entry);
			else more++;
		}
		if (more > 0) ChatUtil.info("+" + more + " more");
	}

	@Override
	public String getInfo() {
		return found.isEmpty() ? null : Integer.toString(found.size());
	}

	private Set<Block> filterBlocks() {
		Set<Block> set = new HashSet<>();
		for (String raw : blocks.get().split(",")) {
			Identifier id = Identifier.tryParse(raw.trim().toLowerCase(Locale.ROOT));
			if (id != null) BuiltInRegistries.BLOCK.getOptional(id).ifPresent(set::add);
		}
		return set;
	}

	private boolean accept(BlockPos pos, BlockEntityLeakTracker.Entry entry) {
		if (!entry.real() && !(hints.get() && (entry.source() != BlockEntityLeakTracker.Source.LIGHT || lightLeaks.get()))) return false;
		if (!passesDetection(pos)) return false;
		Block block = entry.block();
		if (ignorePvp.get() && block == Blocks.ENDER_CHEST) return false;
		if (blockFilter.get() && block != Blocks.AIR && !filterBlocks().contains(block)) return false;
		found.put(pos.asLong(), new Found(pos.immutable(), block, entry.real()));
		return true;
	}

	private boolean passesDetection(BlockPos pos) {
		if (pos.getY() > maxY.get()) return false;
		if (!underground.get() || mc.level == null) return true;
		// The ground's top here, not counting leaves; unloaded columns count as deep enough
		if (!mc.level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) return true;
		int ground = mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ());
		return ground - 1 - pos.getY() >= minDepth.getInt();
	}

	private void announce(BlockPos pos, BlockEntityLeakTracker.Entry entry) {
		CompoundTag tag = entry.tag();
		StringBuilder text = new StringBuilder(entry.block() == Blocks.AIR ? "Hidden block" : entry.block().getName().getString());
		if (!entry.real()) text.append(" (from ").append(entry.source().name().toLowerCase(Locale.ROOT)).append(')');
		String mob = tag == null ? null : tag.getCompound("SpawnData").flatMap(data -> data.getCompound("entity")).flatMap(e -> e.getString("id")).orElse(null);
		if (mob != null) text.append(" (").append(mob.replace("minecraft:", "")).append(')');
		text.append(" at ").append(ChatUtil.coords(pos));
		if (showNbt.get() && tag != null) {
			String snbt = tag.toString();
			text.append(' ').append(snbt.length() > MAX_NBT_CHARS ? snbt.substring(0, MAX_NBT_CHARS) + "..." : snbt);
		}
		ChatUtil.info(text.toString());
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (mc.player == null || found.isEmpty()) return;
		Vec3 eye = mc.player.getEyePosition();
		double rangeSq = range.get() * range.get();
		Vec3 from = RenderUtil.tracerOrigin(event);
		List<Found> spawners = new ArrayList<>();
		Set<Long> traced = new HashSet<>();
		for (Found f : found.values()) {
			BlockPos pos = f.pos();
			if (eye.distanceToSqr(Vec3.atCenterOf(pos)) > rangeSq) continue;
			if (hideVisible.get() && dev.femboyware.util.BlockSight.visible(pos)) continue;
			int c = f.real() ? color.get() : hintColor.get();
			RenderUtil.drawBox(event, new AABB(pos), (c & 0xFFFFFF) | 0x50000000, c | 0xFF000000, 1.5f, true);
			// Clusters (a lit room, a chest wall) share one tracer: one per 8 block cell
			boolean first = !groupTracers.get() || traced.add(BlockPos.asLong(pos.getX() >> 3, pos.getY() >> 3, pos.getZ() >> 3));
			if (tracers.get() && first) RenderUtil.drawLine(event, from, Vec3.atCenterOf(pos), c | 0xFF000000, 1.5f, true);
			if (f.block() == Blocks.SPAWNER) spawners.add(f);
		}
		if (spawnerBeams.get())
			for (Found f : spawners) {
				BlockPos p = f.pos();
				int c = color.get() | 0xFF000000;
				RenderUtil.drawBox(event, new AABB(p.getX() + 0.3, p.getY() + 1, p.getZ() + 0.3, p.getX() + 0.7, p.getY() + 257, p.getZ() + 0.7), (c & 0xFFFFFF) | 0x30000000, c, 1.5f, true);
			}
	}
}
