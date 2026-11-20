package dev.femboyware.module.render;

import dev.femboyware.event.PacketEvent;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.ChatUtil;
import dev.femboyware.util.RenderUtil;
import dev.femboyware.util.Toasts;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntMaps;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Marks chunks with patched mine shafts: a 1x1 or 2x2 column of plain stone between Y 0 and 32 that
 * runs through a diorite, andesite, granite or gravel blob, which never generates on its own; it's
 * someone who dug down and filled the hole back in. Ported from the Anubis client (GPL-3.0, by
 * 4ldenz, https://github.com/4ldenz/Anubis-client). femboyware leaves out Anubis's seed-based
 * Advanced Scanner. Only reads chunks the server sent; nothing goes back.
 */
public class ChunkScannerModule extends Module {
	private static final int PROBE_CHUNKS = 48;
	private static final int[] SIDE_X = new int[]{1, -1, 0, 0};
	private static final int[] SIDE_Z = new int[]{0, 0, 1, -1};
	private static final int[] CORNER_X = new int[]{1, 1, -1, -1};
	private static final int[] CORNER_Z = new int[]{1, -1, 1, -1};
	private static final int MAX_Y = 32;
	private static final long BUDGET_NANOS = 2_000_000L;

	private final NumberSetting stackHeight = add(new NumberSetting("Sensitivity", "How many filled-in blocks tall a shaft must be (lower finds more, with more false flags)", 2, 2, 5, 1));
	private final NumberSetting spreadRadius = add(new NumberSetting("Spread Radius", "Also mark chunks this far around a find, keeping the hottest of each patch", 1, 0, 16, 1));
	private final ColorSetting chunkColor = add(new ColorSetting("Chunk Color", "Colour of marked chunks", 0x60FFFFFF));
	private final BoolSetting tracers = add(new BoolSetting("Tracers", "Lines to marked chunks", true));
	private final BoolSetting notify = add(new BoolSetting("Notify", "A toast when a suspect chunk is found", true));
	private final BoolSetting sound = add(new BoolSetting("Sound", "Play a sound with the toast", true));

	private static final Find UNDECIDED = new Find(0L, 0, new long[0]);
	private final Long2ObjectOpenHashMap<Find> flagged = new Long2ObjectOpenHashMap<>();
	private final LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
	private final LongOpenHashSet queued = new LongOpenHashSet();
	private final List<Find> fresh = new ArrayList<>();
	private final Long2IntOpenHashMap heat = new Long2IntOpenHashMap();
	private int appliedRadius;
	private LongOpenHashSet shown = new LongOpenHashSet();
	private boolean shownDirty = true;
	private int shownRefreshTicks;
	private ClientLevel lastLevel;
	private boolean scanning;
	private ScanConfig appliedConfig;
	private WeakReference<ClientPacketListener> probeServer = new WeakReference<>(null);
	private boolean probing;
	private final LongOpenHashSet probed = new LongOpenHashSet();
	private final LevelChunk[] around = new LevelChunk[9];
	private final int[] groupX = new int[4];
	private final int[] groupZ = new int[4];
	private int groupSize;
	private boolean guessed;
	private Block wall;
	private Block lastBlock;
	private final int[] stackTop = new int[256];
	private final int[] stackShape = new int[256];
	private final int[] stackAreas = new int[256];
	private final boolean[] stackGuessed = new boolean[256];
	private final Block[] stackWall = new Block[256];
	private boolean sawBlob;
	private int scanX;
	private int scanZ;
	private int keepHeight;
	private long[] keptStacks = new long[16];
	private int keptStackCount;
	private int tallest;
	private long bottom;
	private boolean unsure;

	public ChunkScannerModule() {
		super("Chunk Scanner", "Marks chunks with filled-in mine shafts (signs of player activity)", Category.ESP);
		sound.visibleWhen(notify::get);
	}

	private static long key(int x, int z) {
		//? if >=26.1 {
		/*return ChunkPos.pack(x, z);
		*///?} else {
		return ChunkPos.asLong(x, z);
		//?}
	}

	@Override
	protected void onEnable() {
		startLevel(mc.level);
	}

	@Override
	protected void onDisable() {
		startLevel(null);
	}

	private void startLevel(ClientLevel level) {
		flagged.clear();
		heat.clear();
		appliedRadius = spreadRadius.getInt();
		queue.clear();
		queued.clear();
		fresh.clear();
		shown = new LongOpenHashSet();
		shownDirty = true;
		shownRefreshTicks = 0;
		lastLevel = level;
		scanning = level != null && level.dimension() != Level.NETHER && level.dimension() != Level.END;
		if (scanning) {
			ClientPacketListener connection = mc.getConnection();
			if (connection != probeServer.get()) {
				probeServer = new WeakReference<>(connection);
				probing = true;
				probed.clear();
			}
			appliedConfig = currentConfig(level);
			queueLoadedChunks(level);
		}
	}

	private ScanConfig currentConfig(ClientLevel level) {
		return new ScanConfig(Math.max(0, level.getMinY()), Math.min(MAX_Y, level.getMaxY()), stackHeight.getInt());
	}

	private void queueLoadedChunks(ClientLevel level) {
		if (mc.player == null) return;
		int radius = mc.options.renderDistance().get() + 1;
		int cx = mc.player.getBlockX() >> 4, cz = mc.player.getBlockZ() >> 4;
		for (int x = cx - radius; x <= cx + radius; x++)
			for (int z = cz - radius; z <= cz + radius; z++)
				if (level.hasChunk(x, z)) enqueue(key(x, z));
	}

	private void enqueue(long key) {
		if (scanning && queued.add(key)) queue.enqueue(key);
	}

	@Override
	public void onPacketReceive(PacketEvent event) {
		if (!(event.getPacket() instanceof ClientboundLevelChunkWithLightPacket chunk)) return;
		//? if >=26.3 {
		/*int x = chunk.x(), z = chunk.z();
		*///?} else {
		int x = chunk.getX(), z = chunk.getZ();
		//?}
		// Netty thread: queued twice over so it runs after the chunk is in the world
		mc.execute(() -> mc.execute(() -> {
			shownDirty = true;
			for (int dx = -1; dx <= 1; dx++)
				for (int dz = -1; dz <= 1; dz++) enqueue(key(x + dx, z + dz));
		}));
	}

	@Override
	public void onBlockChange(BlockPos pos, BlockState previous, BlockState state) {
		if (!scanning || appliedConfig == null) return;
		int y = pos.getY();
		if (y < appliedConfig.minY() - 1 || y > appliedConfig.maxY() + 1) return;
		if (!matters(previous) && !matters(state)) return;
		int x = pos.getX(), z = pos.getZ();
		// Scanned next tick, after the change is in the world
		for (int cx = x - 2 >> 4; cx <= x + 1 >> 4; cx++)
			for (int cz = z - 2 >> 4; cz <= z + 2 >> 4; cz++) enqueue(key(cx, cz));
	}

	@Override
	public void onTick() {
		ClientLevel level = mc.level;
		if (level != lastLevel) startLevel(level);
		if (!scanning) return;
		ScanConfig config = currentConfig(level);
		if (!config.equals(appliedConfig)) {
			appliedConfig = config;
			queueLoadedChunks(level);
		}
		int radius = spreadRadius.getInt();
		if (radius != appliedRadius) {
			appliedRadius = radius;
			heat.clear();
			shownDirty = true;
			ObjectIterator<Entry<Find>> it = flagged.long2ObjectEntrySet().fastIterator();
			while (it.hasNext()) {
				Entry<Find> entry = it.next();
				spread(entry.getLongKey(), entry.getValue(), 1);
			}
		}
		long deadline = System.nanoTime() + BUDGET_NANOS;
		while (!queue.isEmpty() && System.nanoTime() < deadline) {
			long key = queue.dequeueLong();
			queued.remove(key);
			int cx = ChunkPos.getX(key);
			int cz = ChunkPos.getZ(key);
			if (!level.hasChunk(cx, cz)) continue;
			Find find = scan(level, cx, cz, config);
			if (sawBlob) probe(key);
			if (find != UNDECIDED) {
				Find was = flagged.get(key);
				if (find == null ? was != null : was == null || !was.sameAs(find)) {
					refile(key, was, find);
					if (was == null) fresh.add(find);
				}
			}
		}
		announce(fresh);
		fresh.clear();
		if (shownDirty || ++shownRefreshTicks >= 5) {
			shown = computeShown(level);
			shownDirty = false;
			shownRefreshTicks = 0;
		}
	}

	private void refile(long key, Find was, Find find) {
		this.shownDirty = true;
		if (was != null) {
			this.spread(key, was, -1);
		}

		if (find == null) {
			this.flagged.remove(key);
		} else {
			this.flagged.put(key, find);
			this.spread(key, find, 1);
		}
	}

	private void spread(long key, Find find, int sign) {
		int amount = sign * find.stacks().length;
		int r = this.appliedRadius;
		int cx = ChunkPos.getX(key);
		int cz = ChunkPos.getZ(key);

		for (int x = cx - r; x <= cx + r; x++) {
			for (int z = cz - r; z <= cz + r; z++) {
				long area = key(x, z);
				if (this.heat.addTo(area, amount) + amount == 0) {
					this.heat.remove(area);
				}
			}
		}
	}

	private LongOpenHashSet computeShown(ClientLevel level) {
		Long2IntOpenHashMap candidates = new Long2IntOpenHashMap();
		ObjectIterator var3 = Long2IntMaps.fastIterable(this.heat).iterator();

		while (var3.hasNext()) {
			Long2IntMap.Entry entry = (Long2IntMap.Entry)var3.next();
			long key = entry.getLongKey();
			if (level.hasChunk(ChunkPos.getX(key), ChunkPos.getZ(key))) {
				candidates.put(key, entry.getIntValue());
			}
		}

		return this.appliedRadius == 0 ? new LongOpenHashSet(candidates.keySet()) : hottestPerPatch(candidates);
	}

	private static LongOpenHashSet hottestPerPatch(Long2IntOpenHashMap candidates) {
		LongOpenHashSet kept = new LongOpenHashSet();
		LongOpenHashSet seen = new LongOpenHashSet();
		LongArrayList patch = new LongArrayList();
		LongArrayFIFOQueue open = new LongArrayFIFOQueue();
		LongIterator var5 = candidates.keySet().iterator();

		while (var5.hasNext()) {
			long start = (Long)var5.next();
			if (seen.add(start)) {
				patch.clear();
				open.enqueue(start);
				int max = 0;

				while (!open.isEmpty()) {
					long key = open.dequeueLong();
					patch.add(key);
					max = Math.max(max, candidates.get(key));
					int cx = ChunkPos.getX(key);
					int cz = ChunkPos.getZ(key);

					for (int side = 0; side < 4; side++) {
						long next = key(cx + SIDE_X[side], cz + SIDE_Z[side]);
						if (candidates.containsKey(next) && seen.add(next)) {
							open.enqueue(next);
						}
					}
				}

				for (int i = 0; i < patch.size(); i++) {
					if (candidates.get(patch.getLong(i)) == max) {
						kept.add(patch.getLong(i));
					}
				}
			}
		}

		return kept;
	}

	private Find scan(ClientLevel level, int chunkX, int chunkZ, ScanConfig config) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				this.around[(dx + 1) * 3 + dz + 1] = level.getChunkSource().getChunkNow(chunkX + dx, chunkZ + dz);
			}
		}

		this.sawBlob = false;
		this.scanX = chunkX << 4;
		this.scanZ = chunkZ << 4;
		this.keepHeight = config.stackHeight();
		this.keptStackCount = 0;
		this.tallest = 0;
		this.bottom = 0L;
		this.unsure = false;

		try {
			LevelChunk chunk = this.around[4];
			LevelChunkSection[] sections = chunk.getSections();
			int minSectionY = chunk.getMinSectionY();
			Arrays.fill(this.stackTop, Integer.MIN_VALUE);
			boolean blank = true;

			for (int i = 0; i < sections.length; i++) {
				LevelChunkSection section = sections[i];
				if (section != null && !section.hasOnlyAir()) {
					int baseY = minSectionY + i << 4;
					if (baseY + 15 >= config.minY() && baseY <= config.maxY()) {
						int fromY = Math.max(config.minY() - baseY, 0);
						int toY = Math.min(config.maxY() - baseY, 15);
						if (blank) {
							blank = !hasBlocks(section, fromY, toY);
						}

						if (section.maybeHas(state -> state.is(Blocks.STONE)) && section.maybeHas(state -> isBlob(state.getBlock()))) {
							for (int y = fromY; y <= toY; y++) {
								for (int z = 0; z < 16; z++) {
									boolean afterStone = false;

									for (int x = 0; x < 16; x++) {
										Block block = section.getBlockState(x, y, z).getBlock();
										boolean stone = block == Blocks.STONE;
										if (stone) {
											if (!afterStone && this.isArea(x, baseY + y, z)) {
												this.stack(x, baseY + y, z);
											}
										} else if (this.probing && isBlob(block)) {
											this.sawBlob = true;
											if (this.enclosed(x, baseY + y, z)) {
												this.probing = false;
												this.probed.clear();
											}
										}

										afterStone = stone;
									}
								}
							}
						}
					}
				}
			}

			for (int i = 0; i < 256; i++) {
				this.keep(i);
			}

			if (this.tallest >= config.stackHeight()) {
				return new Find(this.bottom, this.tallest, Arrays.copyOf(this.keptStacks, this.keptStackCount));
			} else {
				return !this.unsure && !blank ? null : UNDECIDED;
			}
		} finally {
			Arrays.fill(this.around, null);
		}
	}

	private void stack(int x, int y, int z) {
		int i = z << 4 | x;
		int shape = this.shape(x, z);
		boolean onTop = this.stackTop[i] == y - 1 && this.stackShape[i] == shape && this.stackWall[i] == this.wall;
		if (!onTop) {
			this.keep(i);
		}

		this.stackTop[i] = y;
		this.stackShape[i] = shape;
		this.stackWall[i] = this.wall;
		this.stackAreas[i] = onTop ? this.stackAreas[i] + 1 : 1;
		this.stackGuessed[i] = this.guessed || onTop && this.stackGuessed[i];
	}

	private void keep(int i) {
		int height = this.stackAreas[i];
		int top = this.stackTop[i];
		if (top != Integer.MIN_VALUE && height >= this.keepHeight) {
			int shape = this.stackShape[i];
			if (this.goesOn(i & 15, top + 1, i >> 4, shape) && this.goesOn(i & 15, top - height, i >> 4, shape)) {
				if (this.stackGuessed[i]) {
					this.unsure = true;
				} else {
					int x = this.scanX + (i & 15);
					int z = this.scanZ + (i >> 4);
					long lowest = BlockPos.asLong(x, top - height + 1, z);
					if (height > this.tallest || height == this.tallest && before(lowest, this.bottom)) {
						this.tallest = height;
						this.bottom = lowest;
					}

					if (this.keptStackCount == this.keptStacks.length) {
						this.keptStacks = Arrays.copyOf(this.keptStacks, this.keptStacks.length * 2);
					}

					this.keptStacks[this.keptStackCount++] = lowest;
				}
			}
		}
	}

	private boolean goesOn(int x0, int y, int z0, int shape) {
		for (int bit = 0; bit < 6; bit++) {
			if ((shape >> bit & 1) != 0) {
				this.kind(x0 + bit / 3, y, z0 + bit % 3 - 1);
				if (!carriesStone(this.lastBlock)) {
					return false;
				}
			}
		}

		return true;
	}

	private static boolean before(long a, long b) {
		if (BlockPos.getY(a) != BlockPos.getY(b)) {
			return BlockPos.getY(a) < BlockPos.getY(b);
		} else {
			return BlockPos.getZ(a) != BlockPos.getZ(b) ? BlockPos.getZ(a) < BlockPos.getZ(b) : BlockPos.getX(a) < BlockPos.getX(b);
		}
	}

	private int shape(int x0, int z0) {
		int shape = 0;

		for (int i = 0; i < this.groupSize; i++) {
			shape |= 1 << (this.groupX[i] - x0) * 3 + this.groupZ[i] - z0 + 1;
		}

		return shape;
	}

	private boolean isArea(int x0, int y, int z0) {
		this.guessed = false;
		this.wall = null;
		if (this.seen(x0 - 1, y, z0) == 2 && this.sameWall() && this.seen(x0, y, z0 - 1) == 2 && this.sameWall()) {
			this.groupX[0] = x0;
			this.groupZ[0] = z0;
			int size = 1;
			int maxX = x0;
			int minZ = z0;
			int maxZ = z0;

			for (int i = 0; i < size; i++) {
				for (int side = 0; side < 4; side++) {
					int x = this.groupX[i] + SIDE_X[side];
					int z = this.groupZ[i] + SIDE_Z[side];
					if (!this.inGroup(x, z, size)) {
						byte kind = this.seen(x, y, z);
						if (kind == 2) {
							if (!this.sameWall()) {
								return false;
							}
						} else {
							if (kind != 1) {
								return false;
							}

							if (x < x0 || x == x0 && z < z0) {
								return false;
							}

							maxX = Math.max(maxX, x);
							minZ = Math.min(minZ, z);
							maxZ = Math.max(maxZ, z);
							if (maxX - x0 > 1 || maxZ - minZ > 1) {
								return false;
							}

							this.groupX[size] = x;
							this.groupZ[size] = z;
							size++;
						}
					}
				}
			}

			for (int i = 0; i < size; i++) {
				for (int corner = 0; corner < 4; corner++) {
					int x = this.groupX[i] + CORNER_X[corner];
					int z = this.groupZ[i] + CORNER_Z[corner];
					if (!this.inGroup(x, z, size) && (this.seen(x, y, z) != 2 || !this.sameWall())) {
						return false;
					}
				}
			}

			for (int i = 0; i < size; i++) {
				if (this.kind(this.groupX[i], y + 1, this.groupZ[i]) == 3 || this.kind(this.groupX[i], y - 1, this.groupZ[i]) == 3) {
					return false;
				}
			}

			this.groupSize = size;
			return true;
		} else {
			return false;
		}
	}

	private boolean inGroup(int x, int z, int size) {
		for (int i = 0; i < size; i++) {
			if (this.groupX[i] == x && this.groupZ[i] == z) {
				return true;
			}
		}

		return false;
	}

	private byte seen(int x, int y, int z) {
		byte kind = this.kind(x, y, z);
		if (kind != 4) {
			return kind;
		}

		this.guessed = true;
		return 2;
	}

	private boolean sameWall() {
		if (this.lastBlock == null) {
			return true;
		}

		if (this.wall == null) {
			this.wall = this.lastBlock;
		}

		return this.lastBlock == this.wall;
	}

	private byte kind(int x, int y, int z) {
		LevelChunk chunk = this.around[((x >> 4) + 1) * 3 + (z >> 4) + 1];
		this.lastBlock = null;
		if (chunk == null) {
			return 4;
		} else {
			int index = chunk.getSectionIndex(y);
			if (index >= 0 && index < chunk.getSectionsCount()) {
				BlockState state = chunk.getSection(index).getBlockState(x & 15, y & 15, z & 15);
				this.lastBlock = state.getBlock();
				return kindOf(state);
			} else {
				return 0;
			}
		}
	}

	private boolean enclosed(int x, int y, int z) {
		return this.full(x + 1, y, z)
			&& this.full(x - 1, y, z)
			&& this.full(x, y, z + 1)
			&& this.full(x, y, z - 1)
			&& this.full(x, y + 1, z)
			&& this.full(x, y - 1, z);
	}

	private boolean full(int x, int y, int z) {
		LevelChunk chunk = this.around[((x >> 4) + 1) * 3 + (z >> 4) + 1];
		if (chunk == null) {
			return false;
		}

		int index = chunk.getSectionIndex(y);
		return index >= 0 && index < chunk.getSectionsCount() && chunk.getSection(index).getBlockState(x & 15, y & 15, z & 15).isSolidRender();
	}

	private static boolean hasBlocks(LevelChunkSection section, int fromY, int toY) {
		if (!section.maybeHas(state -> !state.isAir())) {
			return false;
		}

		for (int y = fromY; y <= toY; y++) {
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					if (!section.getBlockState(x, y, z).isAir()) {
						return true;
					}
				}
			}
		}

		return false;
	}

	private static byte kindOf(BlockState state) {
		Block block = state.getBlock();
		if (block == Blocks.STONE) {
			return 1;
		} else if (isBlob(block)) {
			return 2;
		} else {
			return (byte)(state.isAir() ? 3 : 0);
		}
	}

	private static boolean isBlob(Block block) {
		return block == Blocks.DIORITE || block == Blocks.ANDESITE || block == Blocks.GRANITE || block == Blocks.GRAVEL;
	}

	private static boolean carriesStone(Block block) {
		return block == Blocks.STONE || block == Blocks.DEEPSLATE;
	}

	private static boolean matters(BlockState state) {
		return state != null && (kindOf(state) != 0 || carriesStone(state.getBlock()));
	}

	private void probe(long key) {
		if (probing && probed.add(key) && probed.size() >= PROBE_CHUNKS) {
			probing = false;
			probed.clear();
			ChatUtil.info("Chunk Scanner: this server hides ores with anti-xray, so patched shafts can't be seen");
		}
	}

	private void announce(List<Find> finds) {
		if (!notify.get() || finds.isEmpty()) return;
		Find first = finds.get(0);
		Toasts.show(getName(), "Suspect chunk at " + BlockPos.getX(first.pos()) + ", " + BlockPos.getY(first.pos()) + ", " + BlockPos.getZ(first.pos()));
		if (sound.get()) mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.2f));
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (mc.level == null || shown.isEmpty()) return;
		int fill = chunkColor.get();
		int line = fill | 0xFF000000;
		Vec3 from = RenderUtil.tracerOrigin(event);
		for (LongIterator it = shown.iterator(); it.hasNext(); ) {
			long key = it.nextLong();
			double x = ChunkPos.getX(key) * 16.0, z = ChunkPos.getZ(key) * 16.0;
			RenderUtil.drawBox(event, new AABB(x, 0, z, x + 16, 0.05, z + 16), (fill & 0xFFFFFF) | 0x30000000, line, 1.5f, true);
			if (tracers.get()) RenderUtil.drawLine(event, from, new Vec3(x + 8, 0, z + 8), line, 1.5f, true);
		}
		// The filled-in shafts themselves
		for (Find find : flagged.values()) {
			for (long stack : find.stacks()) {
				BlockPos p = BlockPos.of(stack);
				RenderUtil.drawBox(event, new AABB(p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + find.height(), p.getZ() + 1), (fill & 0xFFFFFF) | 0x40000000, line, 1.5f, true);
			}
		}
	}

	private record Find(long pos, int height, long[] stacks) {
		boolean sameAs(Find other) {
			return pos == other.pos && height == other.height && Arrays.equals(stacks, other.stacks);
		}
	}

	private record ScanConfig(int minY, int maxY, int stackHeight) {
	}
}
