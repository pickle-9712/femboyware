package dev.femboyware.module.render;

import dev.femboyware.event.PacketEvent;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.util.RenderUtil;
import dev.femboyware.util.Toasts;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CaveVines;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.DaylightDetectorBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.PoweredBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.TargetBlock;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Flags chunks whose block light changes below Y 0 when the server resends it: someone placed or
 * broke a light, or redstone is switching, in a chunk you aren't in. Light from amethyst, lit
 * redstone ore, glow berries and torches you can see is ignored. Only reads what the server sends
 * you; nothing goes back. Ported from the Anubis client (GPL-3.0, by 4ldenz,
 * https://github.com/4ldenz/Anubis-client).
 */
public class PrimeChunkFinderModule extends Module {
	private static final int SECTION_BYTES = 2048;
	private static final int MAX_FLAGGED_CHUNKS = 256;
	private static final int AMETHYST_MAX_LIGHT = 5;
	private static final int AMETHYST_REACH = 5;
	private static final long REDSTONE_NOTIFY_COOLDOWN_MS = 15000L;
	private static final int ORE_LIGHT = 9;
	private static final long[] NO_ORES = new long[0];
	private static final int LAMP_LIGHT = 14;
	private static final int[] NO_LAMPS = new int[0];
	private static final long GONE_LAMP_MS = 5000L;
	private static final int MAX_TRACKED_CHUNKS = 5000;
	private static final int SNAPSHOT_RADIUS = 32;
	private static final byte[] ZERO_SECTION = new byte[SECTION_BYTES];

	private final ColorSetting chunkColor = add(new ColorSetting("Chunk Color", "Colour of flagged chunks", 0x5000FF5A));
	private final BoolSetting tracers = add(new BoolSetting("Tracers", "Lines to flagged chunks", true));
	private final BoolSetting notify = add(new BoolSetting("Notify", "A toast when a chunk is flagged", true));
	private final BoolSetting sound = add(new BoolSetting("Sound", "Play a sound with the toast", true));

	private final LinkedHashMap<Long, Map<Integer, byte[]>> chunkLight = new LinkedHashMap<>(256, 0.75F, true);
	private final LongOpenHashSet passedChunks = new LongOpenHashSet();
	private final LongLinkedOpenHashSet flaggedChunks = new LongLinkedOpenHashSet();
	private final LongOpenHashSet shownChunks = new LongOpenHashSet();
	private final LongOpenHashSet checkedSections = new LongOpenHashSet();
	private final LongOpenHashSet amethystSections = new LongOpenHashSet();
	private final Long2ObjectOpenHashMap<long[]> oreSections = new Long2ObjectOpenHashMap<>();
	private final Long2ObjectOpenHashMap<int[]> lampSections = new Long2ObjectOpenHashMap<>();
	private final Long2LongOpenHashMap goneLamps = new Long2LongOpenHashMap();
	private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
	private final Long2LongOpenHashMap redstoneNotified = new Long2LongOpenHashMap();

	public PrimeChunkFinderModule() {
		super("Prime Chunk Finder", "Flags chunks with light and redstone changes below Y 0", Category.ESP);
		sound.visibleWhen(notify::get);
		redstoneNotified.defaultReturnValue(-4611686018427387904L);
	}

	@Override
	public boolean persistsEnabled() {
		return false;
	}

	@Override
	protected void onEnable() {
		if (mc.player != null && mc.level != null && mc.player.getY() < 0.0) {
			reset();
			snapshotLoadedChunks();
		} else {
			Toasts.show("Deepslate Level Only", "Go below Y 0 first.");
			toggle();
		}
	}

	@Override
	protected void onDisable() {
		reset();
	}

	@Override
	public void onPacketReceive(PacketEvent event) {
		if (event.getPacket() instanceof ClientboundLoginPacket) {
			mc.execute(() -> {
				if (isEnabled()) reset();
			});
		} else if (event.getPacket() instanceof ClientboundLevelChunkWithLightPacket packet) {
			//? if >=26.3 {
			/*int chunkX = packet.x();
			int chunkZ = packet.z();
			ClientboundLightUpdatePacketData data = packet.lightData();
			*///?} else {
			int chunkX = packet.getX();
			int chunkZ = packet.getZ();
			ClientboundLightUpdatePacketData data = packet.getLightData();
			//?}
			mc.execute(() -> {
				if (isEnabled() && mc.level != null) baselineChunk(chunkX, chunkZ, data);
			});
		} else if (event.getPacket() instanceof ClientboundLightUpdatePacket packet) {
			//? if >=26.3 {
			/*int chunkX = packet.x();
			int chunkZ = packet.z();
			ClientboundLightUpdatePacketData data = packet.lightData();
			*///?} else {
			int chunkX = packet.getX();
			int chunkZ = packet.getZ();
			ClientboundLightUpdatePacketData data = packet.getLightData();
			//?}
			mc.execute(() -> {
				if (isEnabled() && mc.level != null) detectChanges(chunkX, chunkZ, data);
			});
		}
	}

	@Override
	public void onBlockChange(BlockPos pos, BlockState previous, BlockState state) {
		if (mc.level == null) return;
		rememberGoneLamp(pos, previous, state);
		detectRedstone(pos, previous, state);
	}

	@Override
	public void onTick() {
		if (mc.level == null) {
			reset();
			return;
		}
		if (mc.player != null && mc.player.getY() < 0.0) passedChunks.add(key(mc.player.getBlockX() >> 4, mc.player.getBlockZ() >> 4));
		forgetOldLamps();
		refreshShown(mc.level);
	}

	private void refreshShown(ClientLevel level) {
		shownChunks.clear();
		for (long key : flaggedChunks) {
			if (level.hasChunk(ChunkPos.getX(key), ChunkPos.getZ(key))) shownChunks.add(key);
		}
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (shownChunks.isEmpty() || mc.level == null) return;
		int fill = chunkColor.get();
		int line = fill | 0xFF000000;
		Vec3 from = RenderUtil.tracerOrigin(event);
		for (long key : shownChunks) {
			double x = ChunkPos.getX(key) * 16.0, z = ChunkPos.getZ(key) * 16.0;
			RenderUtil.drawBox(event, new AABB(x, 0, z, x + 16, 0.05, z + 16), fill, line, 1.5f, true);
			if (tracers.get()) RenderUtil.drawLine(event, from, new Vec3(x + 8, 0, z + 8), line, 1.5f, true);
		}
	}

	private static long key(int chunkX, int chunkZ) {
		//? if >=26.1 {
		/*return ChunkPos.pack(chunkX, chunkZ);
		*///?} else {
		return ChunkPos.asLong(chunkX, chunkZ);
		//?}
	}

	private void flagChunk(long key) {
		flaggedChunks.addAndMoveToLast(key);
		shownChunks.add(key);
		while (flaggedChunks.size() > MAX_FLAGGED_CHUNKS) flaggedChunks.removeFirstLong();
	}

	private void snapshotLoadedChunks() {
		if (mc.level == null || mc.player == null) return;
		int centerX = mc.player.getBlockX() >> 4;
		int centerZ = mc.player.getBlockZ() >> 4;
		for (int chunkX = centerX - SNAPSHOT_RADIUS; chunkX <= centerX + SNAPSHOT_RADIUS; chunkX++) {
			for (int chunkZ = centerZ - SNAPSHOT_RADIUS; chunkZ <= centerZ + SNAPSHOT_RADIUS; chunkZ++) {
				Map<Integer, byte[]> sections = snapshotChunk(chunkX, chunkZ);
				if (sections != null) chunkLight.put(key(chunkX, chunkZ), sections);
			}
		}
		trimChunkCache();
	}

	private Map<Integer, byte[]> snapshotChunk(int chunkX, int chunkZ) {
		ClientLevel level = mc.level;
		if (level == null || !level.getChunkSource().hasChunk(chunkX, chunkZ)) return null;
		LayerLightEventListener blockLight = level.getChunkSource().getLightEngine().getLayerListener(LightLayer.BLOCK);
		Map<Integer, byte[]> sections = createZeroedChunk();
		for (int sectionY = bottomSection(); sectionY <= topSection(); sectionY++) {
			DataLayer layer = blockLight.getDataLayerData(SectionPos.of(chunkX, sectionY, chunkZ));
			if (layer != null && !layer.isDefinitelyFilledWith(0)) sections.put(sectionY, Arrays.copyOf(layer.getData(), SECTION_BYTES));
		}
		return sections;
	}

	private void baselineChunk(int chunkX, int chunkZ, ClientboundLightUpdatePacketData data) {
		Map<Integer, byte[]> sections = createZeroedChunk();
		applyInitializedSections(data, sections, chunkX, chunkZ, false, null);
		applyUninitializedSections(data, sections, chunkX, chunkZ, false, null);
		chunkLight.put(key(chunkX, chunkZ), sections);
		trimChunkCache();
	}

	private void detectChanges(int chunkX, int chunkZ, ClientboundLightUpdatePacketData data) {
		long key = key(chunkX, chunkZ);
		checkedSections.clear();
		amethystSections.clear();
		oreSections.clear();
		lampSections.clear();
		Map<Integer, byte[]> sections = chunkLight.get(key);
		if (sections == null) sections = snapshotChunk(chunkX, chunkZ);
		boolean compare = sections != null && !isOwnChunk(key);
		if (sections == null) sections = createZeroedChunk();
		ChangeSummary summary = new ChangeSummary();
		applyInitializedSections(data, sections, chunkX, chunkZ, compare, summary);
		applyUninitializedSections(data, sections, chunkX, chunkZ, compare, summary);
		chunkLight.put(key, sections);
		trimChunkCache();
		if (summary.count != 0) {
			flagChunk(key);
			if (notify.get()) alert(summary.x + ", " + summary.y + ", " + summary.z);
		}
	}

	private void alert(String text) {
		Toasts.show(getName(), text);
		if (sound.get()) mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.2f));
	}

	private void rememberGoneLamp(BlockPos pos, BlockState previous, BlockState state) {
		if (isLamp(previous)) {
			int light = previous.getLightEmission();
			if (!isLamp(state) || state.getLightEmission() < light) goneLamps.put(pos.asLong(), Util.getMillis() + GONE_LAMP_MS << 4 | light);
		}
	}

	private void forgetOldLamps() {
		if (goneLamps.isEmpty()) return;
		long now = Util.getMillis();
		LongIterator it = goneLamps.values().iterator();
		while (it.hasNext()) {
			if (it.nextLong() >>> 4 < now) it.remove();
		}
	}

	private void detectRedstone(BlockPos pos, BlockState previous, BlockState state) {
		if (pos.getY() >= 0 || !(isRedstone(state) || isRedstone(previous))) return;
		long key = key(pos.getX() >> 4, pos.getZ() >> 4);
		if (isOwnChunk(key)) return;
		flagChunk(key);
		long now = Util.getMillis();
		if (notify.get() && now - redstoneNotified.get(key) >= REDSTONE_NOTIFY_COOLDOWN_MS) {
			redstoneNotified.put(key, now);
			alert("Redstone " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
		}
	}

	private static boolean isRedstone(BlockState state) {
		Block block = state.getBlock();
		return block instanceof RedStoneWireBlock || block instanceof DiodeBlock || block instanceof RedstoneTorchBlock || block instanceof RedstoneLampBlock
			|| block instanceof ObserverBlock || block instanceof PistonBaseBlock || block instanceof PistonHeadBlock || block instanceof MovingPistonBlock
			|| block instanceof DispenserBlock || block instanceof HopperBlock || block instanceof NoteBlock || block instanceof DaylightDetectorBlock
			|| block instanceof LeverBlock || block instanceof ButtonBlock || block instanceof BasePressurePlateBlock || block instanceof TripWireHookBlock
			|| block instanceof TargetBlock || block instanceof CrafterBlock || block instanceof PoweredBlock;
	}

	private boolean isOwnChunk(long key) {
		if (passedChunks.contains(key)) return true;
		return mc.player != null && key(mc.player.getBlockX() >> 4, mc.player.getBlockZ() >> 4) == key;
	}

	private void applyInitializedSections(ClientboundLightUpdatePacketData data, Map<Integer, byte[]> sections, int chunkX, int chunkZ, boolean compare, ChangeSummary summary) {
		//? if >=26.3 {
		/*BitSet mask = data.blockYMask();
		List<byte[]> updates = data.blockUpdates();
		*///?} else {
		BitSet mask = data.getBlockYMask();
		List<byte[]> updates = data.getBlockUpdates();
		//?}
		int next = 0;
		for (int bit = mask.nextSetBit(0); bit >= 0 && next < updates.size(); bit = mask.nextSetBit(bit + 1)) {
			byte[] update = updates.get(next++);
			int sectionY = lightSectionY(bit);
			if (isWorldSection(sectionY) && update.length == SECTION_BYTES) updateSection(sections, chunkX, chunkZ, sectionY, Arrays.copyOf(update, SECTION_BYTES), compare, summary);
		}
	}

	private void applyUninitializedSections(ClientboundLightUpdatePacketData data, Map<Integer, byte[]> sections, int chunkX, int chunkZ, boolean compare, ChangeSummary summary) {
		//? if >=26.3 {
		/*BitSet empty = data.emptyBlockYMask();
		*///?} else {
		BitSet empty = data.getEmptyBlockYMask();
		//?}
		for (int bit = empty.nextSetBit(0); bit >= 0; bit = empty.nextSetBit(bit + 1)) {
			int sectionY = lightSectionY(bit);
			if (isWorldSection(sectionY)) updateSection(sections, chunkX, chunkZ, sectionY, ZERO_SECTION, compare, summary);
		}
	}

	private void updateSection(Map<Integer, byte[]> sections, int chunkX, int chunkZ, int sectionY, byte[] light, boolean compare, ChangeSummary summary) {
		byte[] previous = sections.get(sectionY);
		if (compare && previous != null && summary != null) compareSection(previous, light, chunkX, chunkZ, sectionY, summary);
		sections.put(sectionY, light);
	}

	private void compareSection(byte[] previous, byte[] light, int chunkX, int chunkZ, int sectionY, ChangeSummary summary) {
		for (int i = 0; i < SECTION_BYTES; i++) {
			int was = previous[i] & 255;
			int now = light[i] & 255;
			int changed = was ^ now;
			if ((changed & 15) != 0) recordChange(chunkX, chunkZ, sectionY, i << 1, was & 15, now & 15, summary);
			if ((changed & 240) != 0) recordChange(chunkX, chunkZ, sectionY, (i << 1) + 1, was >>> 4 & 15, now >>> 4 & 15, summary);
		}
	}

	private void recordChange(int chunkX, int chunkZ, int sectionY, int index, int oldValue, int newValue, ChangeSummary summary) {
		int x = (chunkX << 4) + (index & 15);
		int y = (sectionY << 4) + (index >>> 8 & 15);
		int z = (chunkZ << 4) + (index >>> 4 & 15);
		// femboyware: Prime Chunk Finder is for below Y 0
		if (y >= 0) return;
		int value = Math.max(oldValue, newValue);
		if (!fromAmethyst(x, y, z, oldValue, newValue) && !fromRedstoneOre(x, y, z, value) && !fromLamp(x, y, z, value)) summary.add(x, y, z, oldValue, newValue);
	}

	private boolean fromLamp(int x, int y, int z, int value) {
		if (value > LAMP_LIGHT) return false;
		ClientLevel level = mc.level;
		if (level == null) return false;
		int reach = LAMP_LIGHT - value;
		for (int sx = x - reach >> 4; sx <= x + reach >> 4; sx++)
			for (int sy = y - reach >> 4; sy <= y + reach >> 4; sy++)
				for (int sz = z - reach >> 4; sz <= z + reach >> 4; sz++)
					for (int lamp : lamps(level, sx, sy, sz)) {
						int index = lamp >>> 4;
						int lx = (sx << 4) + (index & 15);
						int ly = (sy << 4) + (index >>> 8);
						int lz = (sz << 4) + (index >>> 4 & 15);
						if (Math.abs(lx - x) + Math.abs(ly - y) + Math.abs(lz - z) <= (lamp & 15) - value) return true;
					}
		long now = Util.getMillis();
		for (Long2LongMap.Entry gone : goneLamps.long2LongEntrySet()) {
			long packed = gone.getLongValue();
			if (packed >>> 4 >= now) {
				long pos = gone.getLongKey();
				int distance = Math.abs(BlockPos.getX(pos) - x) + Math.abs(BlockPos.getY(pos) - y) + Math.abs(BlockPos.getZ(pos) - z);
				if (distance <= (int) (packed & 15L) - value) return true;
			}
		}
		return false;
	}

	private int[] lamps(ClientLevel level, int sectionX, int sectionY, int sectionZ) {
		long key = SectionPos.asLong(sectionX, sectionY, sectionZ);
		int[] cached = lampSections.get(key);
		if (cached != null) return cached;
		int[] found = scanLamps(level, sectionX, sectionY, sectionZ);
		lampSections.put(key, found);
		return found;
	}

	private static LevelChunkSection section(ClientLevel level, int sectionX, int sectionY, int sectionZ) {
		if (sectionY < level.getMinSectionY() || sectionY > level.getMaxSectionY()) return null;
		LevelChunk chunk = level.getChunkSource().getChunk(sectionX, sectionZ, ChunkStatus.FULL, false);
		if (chunk == null) return null;
		LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sectionY));
		return section.hasOnlyAir() ? null : section;
	}

	private static int[] scanLamps(ClientLevel level, int sectionX, int sectionY, int sectionZ) {
		LevelChunkSection section = section(level, sectionX, sectionY, sectionZ);
		if (section == null || !section.getStates().maybeHas(PrimeChunkFinderModule::isLamp)) return NO_LAMPS;
		IntArrayList lamps = new IntArrayList();
		for (int index = 0; index < 4096; index++) {
			BlockState state = section.getBlockState(index & 15, index >>> 8, index >>> 4 & 15);
			if (isLamp(state)) lamps.add(index << 4 | state.getLightEmission());
		}
		return lamps.isEmpty() ? NO_LAMPS : lamps.toIntArray();
	}

	private static boolean isLamp(BlockState state) {
		return state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH) || state.is(Blocks.SOUL_TORCH) || state.is(Blocks.SOUL_WALL_TORCH)
			|| state.is(Blocks.COPPER_TORCH) || state.is(Blocks.COPPER_WALL_TORCH) || CaveVines.hasGlowBerries(state);
	}

	private boolean fromAmethyst(int x, int y, int z, int oldValue, int newValue) {
		if (Math.max(oldValue, newValue) > AMETHYST_MAX_LIGHT) return false;
		ClientLevel level = mc.level;
		if (level == null || !amethystSectionNear(level, x, y, z)) return false;
		for (int dx = -AMETHYST_REACH; dx <= AMETHYST_REACH; dx++) {
			int restX = AMETHYST_REACH - Math.abs(dx);
			for (int dy = -restX; dy <= restX; dy++) {
				int restY = restX - Math.abs(dy);
				for (int dz = -restY; dz <= restY; dz++) {
					if (isAmethyst(level.getBlockState(cursor.set(x + dx, y + dy, z + dz)))) return true;
				}
			}
		}
		return false;
	}

	private boolean amethystSectionNear(ClientLevel level, int x, int y, int z) {
		for (int sx = x - AMETHYST_REACH >> 4; sx <= x + AMETHYST_REACH >> 4; sx++)
			for (int sy = y - AMETHYST_REACH >> 4; sy <= y + AMETHYST_REACH >> 4; sy++)
				for (int sz = z - AMETHYST_REACH >> 4; sz <= z + AMETHYST_REACH >> 4; sz++) {
					long key = SectionPos.asLong(sx, sy, sz);
					if (checkedSections.add(key)) {
						LevelChunkSection section = section(level, sx, sy, sz);
						if (section != null && section.getStates().maybeHas(PrimeChunkFinderModule::isAmethyst)) amethystSections.add(key);
					}
					if (amethystSections.contains(key)) return true;
				}
		return false;
	}

	private boolean fromRedstoneOre(int x, int y, int z, int value) {
		if (value > ORE_LIGHT) return false;
		ClientLevel level = mc.level;
		if (level == null) return false;
		int reach = ORE_LIGHT - value;
		for (int sx = x - reach >> 4; sx <= x + reach >> 4; sx++)
			for (int sy = y - reach >> 4; sy <= y + reach >> 4; sy++)
				for (int sz = z - reach >> 4; sz <= z + reach >> 4; sz++)
					for (long ore : exposedOres(level, sx, sy, sz)) {
						int distance = Math.abs(BlockPos.getX(ore) - x) + Math.abs(BlockPos.getY(ore) - y) + Math.abs(BlockPos.getZ(ore) - z);
						if (distance <= reach) return true;
					}
		return false;
	}

	private long[] exposedOres(ClientLevel level, int sectionX, int sectionY, int sectionZ) {
		long key = SectionPos.asLong(sectionX, sectionY, sectionZ);
		long[] cached = oreSections.get(key);
		if (cached != null) return cached;
		long[] found = scanExposedOres(level, sectionX, sectionY, sectionZ);
		oreSections.put(key, found);
		return found;
	}

	private long[] scanExposedOres(ClientLevel level, int sectionX, int sectionY, int sectionZ) {
		LevelChunkSection section = section(level, sectionX, sectionY, sectionZ);
		if (section == null || !section.getStates().maybeHas(PrimeChunkFinderModule::isRedstoneOre)) return NO_ORES;
		LongArrayList ores = new LongArrayList();
		for (int ly = 0; ly < 16; ly++)
			for (int lz = 0; lz < 16; lz++)
				for (int lx = 0; lx < 16; lx++) {
					if (!isRedstoneOre(section.getBlockState(lx, ly, lz))) continue;
					int wx = (sectionX << 4) + lx, wy = (sectionY << 4) + ly, wz = (sectionZ << 4) + lz;
					if (exposed(level, wx, wy, wz)) ores.add(BlockPos.asLong(wx, wy, wz));
				}
		return ores.isEmpty() ? NO_ORES : ores.toLongArray();
	}

	private boolean exposed(ClientLevel level, int x, int y, int z) {
		for (Direction direction : Direction.values()) {
			BlockState neighbour = level.getBlockState(cursor.set(x + direction.getStepX(), y + direction.getStepY(), z + direction.getStepZ()));
			if (neighbour.isAir() || !neighbour.canOcclude()) return true;
		}
		return false;
	}

	private static boolean isRedstoneOre(BlockState state) {
		return state.is(Blocks.REDSTONE_ORE) || state.is(Blocks.DEEPSLATE_REDSTONE_ORE);
	}

	private static boolean isAmethyst(BlockState state) {
		return state.is(Blocks.AMETHYST_CLUSTER) || state.is(Blocks.LARGE_AMETHYST_BUD) || state.is(Blocks.MEDIUM_AMETHYST_BUD) || state.is(Blocks.SMALL_AMETHYST_BUD)
			|| state.is(Blocks.BUDDING_AMETHYST) || state.is(Blocks.AMETHYST_BLOCK);
	}

	private Map<Integer, byte[]> createZeroedChunk() {
		Map<Integer, byte[]> sections = new HashMap<>();
		for (int sectionY = bottomSection(); sectionY <= topSection(); sectionY++) sections.put(sectionY, ZERO_SECTION);
		return sections;
	}

	private int lightSectionY(int index) {
		ClientLevel level = mc.level;
		return level == null ? Integer.MIN_VALUE : level.getChunkSource().getLightEngine().getMinLightSection() + index;
	}

	private int bottomSection() {
		return mc.level.getMinSectionY();
	}

	private int topSection() {
		return mc.level.getMaxSectionY();
	}

	private boolean isWorldSection(int sectionY) {
		return sectionY >= bottomSection() && sectionY <= topSection();
	}

	private void trimChunkCache() {
		while (chunkLight.size() > MAX_TRACKED_CHUNKS) {
			Iterator<Long> keys = chunkLight.keySet().iterator();
			keys.next();
			keys.remove();
		}
	}

	private void reset() {
		chunkLight.clear();
		passedChunks.clear();
		flaggedChunks.clear();
		shownChunks.clear();
		redstoneNotified.clear();
		lampSections.clear();
		goneLamps.clear();
	}

	private static final class ChangeSummary {
		int count;
		int x;
		int y;
		int z;
		int oldValue;
		int newValue;

		void add(int x, int y, int z, int oldValue, int newValue) {
			if (count == 0 || Math.abs(newValue - oldValue) > Math.abs(this.newValue - this.oldValue)) {
				this.x = x;
				this.y = y;
				this.z = z;
				this.oldValue = oldValue;
				this.newValue = newValue;
			}
			count++;
		}
	}
}
