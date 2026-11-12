package dev.femboyware.module.larp;

import dev.femboyware.FemboyWare;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Litematica schematics for Server Larp: reads .litematic files (gzipped NBT: regions, each with a
 * palette and blocks packed into longs) and turns them into blocks to put in the larp. Where
 * Litematica is installed, its own placements (where you've put each schematic, turned and
 * mirrored) are read too, so what you see as a hologram becomes blocks you can stand on and break.
 */
public final class LarpSchematics {
	/** A Litematica placement: its file, where its origin is, and how it's turned. */
	public record Placement(String name, Path file, BlockPos origin, Rotation rotation, Mirror mirror) {}

	private LarpSchematics() {
	}

	/**
	 * The main schematics folder: the normal launcher's .minecraft\schematics, so the same schematics
	 * work from any launcher profile (Modrinth, Prism...). Falls back to this instance's own folder
	 * when there's no .minecraft.
	 */
	public static Path folder() {
		Path minecraft = minecraftSchematics();
		return minecraft != null && Files.isDirectory(minecraft) ? minecraft : FabricLoader.getInstance().getGameDir().resolve("schematics");
	}

	/** .minecraft\schematics where the normal launcher keeps it, or null if it can't be worked out. */
	private static Path minecraftSchematics() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT), home = System.getProperty("user.home", "");
		if (os.contains("win")) {
			String appData = System.getenv("APPDATA");
			return appData == null ? null : Path.of(appData, ".minecraft", "schematics");
		}
		if (os.contains("mac")) return Path.of(home, "Library", "Application Support", "minecraft", "schematics");
		return Path.of(home, ".minecraft", "schematics");
	}

	/** Every .litematic in .minecraft\schematics and this instance's schematics folder, and below them. */
	public static List<Path> files() {
		List<Path> out = new ArrayList<>();
		java.util.Set<Path> folders = new java.util.LinkedHashSet<>();
		Path minecraft = minecraftSchematics();
		if (minecraft != null) folders.add(minecraft.toAbsolutePath().normalize());
		folders.add(FabricLoader.getInstance().getGameDir().resolve("schematics").toAbsolutePath().normalize());
		for (Path folder : folders) {
			if (!Files.isDirectory(folder)) continue;
			try (Stream<Path> walk = Files.walk(folder, 6)) {
				walk.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".litematic")).forEach(out::add);
			} catch (IOException ignored) {
			}
		}
		return out;
	}

	/** The schematic file whose name best matches what was typed, or null. */
	public static Path find(String typed) {
		String want = typed.toLowerCase(Locale.ROOT).replace(".litematic", "");
		Path contains = null;
		for (Path p : files()) {
			String name = p.getFileName().toString().toLowerCase(Locale.ROOT).replace(".litematic", "");
			if (name.equals(want)) return p;
			if (contains == null && name.contains(want)) contains = p;
		}
		return contains;
	}

	/**
	 * Litematica's placements, read through reflection so femboyware works with or without it; empty
	 * if it isn't installed or has nothing placed.
	 */
	public static List<Placement> litematicaPlacements() {
		List<Placement> out = new ArrayList<>();
		if (!FabricLoader.getInstance().isModLoaded("litematica")) return out;
		try {
			Class<?> data = Class.forName("fi.dy.masa.litematica.data.DataManager");
			Object manager = data.getMethod("getSchematicPlacementManager").invoke(null);
			List<?> placements = (List<?>) manager.getClass().getMethod("getAllSchematicsPlacements").invoke(manager);
			for (Object p : placements) {
				try {
					Object enabled = call(p, "isEnabled");
					if (enabled instanceof Boolean on && !on) continue;
					Object file = call(p, "getSchematicFile");
					Path path = file instanceof Path asPath ? asPath : file instanceof java.io.File asFile ? asFile.toPath() : null;
					if (path == null || !Files.isRegularFile(path)) continue;
					BlockPos origin = (BlockPos) call(p, "getOrigin");
					Rotation rotation = call(p, "getRotation") instanceof Rotation r ? r : Rotation.NONE;
					Mirror mirror = call(p, "getMirror") instanceof Mirror m ? m : Mirror.NONE;
					Object name = call(p, "getName");
					out.add(new Placement(name == null ? path.getFileName().toString() : name.toString(), path, origin, rotation, mirror));
				} catch (ReflectiveOperationException | RuntimeException e) {
					FemboyWare.LOGGER.debug("Couldn't read a Litematica placement", e);
				}
			}
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			FemboyWare.LOGGER.debug("Couldn't read Litematica's placements", e);
		}
		return out;
	}

	private static Object call(Object target, String method) throws ReflectiveOperationException {
		return target.getClass().getMethod(method).invoke(target);
	}

	/** The block registry, for reading a schematic off the main thread; grab it on the main thread. */
	public static net.minecraft.core.HolderGetter<net.minecraft.world.level.block.Block> blockLookup() {
		return Minecraft.getInstance().level.registryAccess().lookupOrThrow(Registries.BLOCK);
	}

	/**
	 * Reads a schematic, kept packed the way the file has it (a few bits a block, not an object per
	 * block), so even a whole server spawn of tens of millions of blocks loads; its blocks are read
	 * out a chunk at a time as they're built. Placed with its origin at {@code origin}, mirrored then
	 * turned like Litematica does. Fine off the main thread.
	 */
	public static Schematic read(Path file, BlockPos origin, Rotation rotation, Mirror mirror,
								 net.minecraft.core.HolderGetter<net.minecraft.world.level.block.Block> blocks) throws IOException {
		CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
		CompoundTag regions = root.getCompoundOrEmpty("Regions");
		List<Region> out = new ArrayList<>();
		for (String key : regions.keySet()) {
			CompoundTag region = regions.getCompoundOrEmpty(key);
			CompoundTag position = region.getCompoundOrEmpty("Position"), size = region.getCompoundOrEmpty("Size");
			int sx = size.getIntOr("x", 0), sy = size.getIntOr("y", 0), sz = size.getIntOr("z", 0);
			int ax = Math.abs(sx), ay = Math.abs(sy), az = Math.abs(sz);
			if (ax == 0 || ay == 0 || az == 0) continue;
			// A negative size runs back from the position
			int minX = position.getIntOr("x", 0) + (sx < 0 ? sx + 1 : 0);
			int minY = position.getIntOr("y", 0) + (sy < 0 ? sy + 1 : 0);
			int minZ = position.getIntOr("z", 0) + (sz < 0 ? sz + 1 : 0);
			ListTag paletteTag = region.getListOrEmpty("BlockStatePalette");
			if (paletteTag.isEmpty()) continue;
			// Turned and mirrored once here instead of per block; air left null
			BlockState[] palette = new BlockState[paletteTag.size()];
			for (int i = 0; i < palette.length; i++) {
				BlockState state = NbtUtils.readBlockState(blocks, paletteTag.getCompoundOrEmpty(i));
				palette[i] = state.isAir() ? null : state.mirror(mirror).rotate(rotation);
			}
			long[] packed = region.getLongArray("BlockStates").orElse(new long[0]);
			int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.length - 1));
			out.add(new Region(minX, minY, minZ, ax, ay, az, bits, packed, palette));
		}
		return new Schematic(out, origin, rotation, mirror);
	}

	/** One region of a schematic, still packed: Litematica stores y, then z, then x, and lets values straddle two longs. */
	private record Region(int minX, int minY, int minZ, int ax, int ay, int az, int bits, long[] packed, BlockState[] palette) {
		/** The block at (x, y, z) inside the region, or null for air. */
		BlockState at(int x, int y, int z) {
			long start = ((long) y * az * ax + (long) z * ax + x) * bits;
			int startLong = (int) (start >>> 6), endLong = (int) ((start + bits - 1) >>> 6), offset = (int) (start & 63);
			if (endLong >= packed.length) return null;
			long mask = (1L << bits) - 1;
			long value = startLong == endLong ? (packed[startLong] >>> offset) & mask
				: ((packed[startLong] >>> offset) | (packed[endLong] << (64 - offset))) & mask;
			return value <= 0 || value >= palette.length ? null : palette[(int) value];
		}
	}

	/** A schematic placed in the world: its regions, where its origin is and how it's turned. */
	public static final class Schematic {
		private final List<Region> regions;
		private final Rotation rotation;
		private final Mirror mirror;
		private BlockPos origin;
		/** Where it reaches in the world, inclusive. */
		public int minX, minY, minZ, maxX, maxY, maxZ;

		Schematic(List<Region> regions, BlockPos origin, Rotation rotation, Mirror mirror) {
			this.regions = regions;
			this.rotation = rotation;
			this.mirror = mirror;
			moveTo(origin);
		}

		public boolean isEmpty() {
			return regions.isEmpty();
		}

		public BlockPos origin() {
			return origin;
		}

		public Rotation rotation() {
			return rotation;
		}

		public Mirror mirror() {
			return mirror;
		}

		/** Moves it so its origin is at {@code origin}. */
		public void moveTo(BlockPos origin) {
			this.origin = origin;
			minX = minY = minZ = Integer.MAX_VALUE;
			maxX = maxY = maxZ = Integer.MIN_VALUE;
			for (Region r : regions) {
				for (int cx = 0; cx < 2; cx++) {
					for (int cz = 0; cz < 2; cz++) {
						BlockPos p = transform(new BlockPos(r.minX + cx * (r.ax - 1), 0, r.minZ + cz * (r.az - 1)), rotation, mirror);
						minX = Math.min(minX, origin.getX() + p.getX());
						maxX = Math.max(maxX, origin.getX() + p.getX());
						minZ = Math.min(minZ, origin.getZ() + p.getZ());
						maxZ = Math.max(maxZ, origin.getZ() + p.getZ());
					}
				}
				minY = Math.min(minY, origin.getY() + r.minY);
				maxY = Math.max(maxY, origin.getY() + r.minY + r.ay - 1);
			}
		}

		/** Moves it so its middle is at {@code feet} and its bottom layer level with it. */
		public void centreOn(BlockPos feet) {
			moveTo(origin.offset(feet.getX() - Math.floorDiv(minX + maxX, 2), feet.getY() - minY, feet.getZ() - Math.floorDiv(minZ + maxZ, 2)));
		}

		/** Every chunk it reaches into, as {x, z} pairs. */
		public List<int[]> chunks() {
			List<int[]> out = new ArrayList<>();
			for (int cx = minX >> 4; cx <= maxX >> 4; cx++) for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) out.add(new int[]{cx, cz});
			return out;
		}

		/** Each of its blocks in chunk (cx, cz); air is skipped. */
		public void forEachInChunk(int cx, int cz, BlockVisitor visit) {
			BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
			int fromX = Math.max(minX, cx << 4), toX = Math.min(maxX, (cx << 4) + 15), fromZ = Math.max(minZ, cz << 4), toZ = Math.min(maxZ, (cz << 4) + 15);
			for (int wx = fromX; wx <= toX; wx++) {
				for (int wz = fromZ; wz <= toZ; wz++) {
					// Back from the world to the schematic: undo the turn, then the mirror
					int rx = wx - origin.getX(), rz = wz - origin.getZ(), lx, lz;
					switch (rotation) {
						case CLOCKWISE_90 -> {
							lx = rz;
							lz = -rx;
						}
						case CLOCKWISE_180 -> {
							lx = -rx;
							lz = -rz;
						}
						case COUNTERCLOCKWISE_90 -> {
							lx = -rz;
							lz = rx;
						}
						default -> {
							lx = rx;
							lz = rz;
						}
					}
					if (mirror == Mirror.LEFT_RIGHT) lz = -lz;
					else if (mirror == Mirror.FRONT_BACK) lx = -lx;
					for (Region r : regions) {
						int x = lx - r.minX, z = lz - r.minZ;
						if (x < 0 || x >= r.ax || z < 0 || z >= r.az) continue;
						for (int y = 0; y < r.ay; y++) {
							BlockState state = r.at(x, y, z);
							if (state == null) continue;
							at.set(wx, origin.getY() + r.minY + y, wz);
							visit.accept(at, state);
						}
					}
				}
			}
		}
	}

	/** Gets each block of a schematic; the position is reused, so copy it to keep it. */
	@FunctionalInterface
	public interface BlockVisitor {
		void accept(BlockPos.MutableBlockPos pos, BlockState state);
	}

	/** Mirrored, then turned about the origin, as vanilla structures and Litematica do. */
	private static BlockPos transform(BlockPos p, Rotation rotation, Mirror mirror) {
		int x = p.getX(), y = p.getY(), z = p.getZ();
		switch (mirror) {
			case LEFT_RIGHT -> z = -z;
			case FRONT_BACK -> x = -x;
			default -> {
			}
		}
		return switch (rotation) {
			case CLOCKWISE_90 -> new BlockPos(-z, y, x);
			case CLOCKWISE_180 -> new BlockPos(-x, y, -z);
			case COUNTERCLOCKWISE_90 -> new BlockPos(z, y, -x);
			default -> new BlockPos(x, y, z);
		};
	}
}
