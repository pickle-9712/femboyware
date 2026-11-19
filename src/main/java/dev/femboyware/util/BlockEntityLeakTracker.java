package dev.femboyware.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
/** Ported from the Anubis client (GPL-3.0, by 4ldenz, https://github.com/4ldenz/Anubis-client). */
public final class BlockEntityLeakTracker {
	private static final double ON_EDGE = 0.07;
	private static final Map<BlockPos, BlockEntityLeakTracker.Entry> FOUND = new ConcurrentHashMap<>();
	private static final ConcurrentLinkedQueue<BlockPos> ADDED = new ConcurrentLinkedQueue<>();
	private static final AtomicInteger GENERATION = new AtomicInteger();
	private static final AtomicIntegerArray COUNTS = new AtomicIntegerArray(BlockEntityLeakTracker.Source.values().length);
	private static volatile boolean recordAdditions;

	private BlockEntityLeakTracker() {
	}

	public static Map<BlockPos, BlockEntityLeakTracker.Entry> view() {
		return FOUND;
	}

	public static boolean contains(BlockPos pos) {
		return FOUND.containsKey(pos);
	}

	public static boolean containsReal(BlockPos pos) {
		BlockEntityLeakTracker.Entry entry = FOUND.get(pos);
		return entry != null && entry.real();
	}

	public static int count(BlockEntityLeakTracker.Source source) {
		return COUNTS.get(source.ordinal());
	}

	public static void clear() {
		FOUND.clear();
		ADDED.clear();

		for (int i = 0; i < COUNTS.length(); i++) {
			COUNTS.set(i, 0);
		}

		GENERATION.incrementAndGet();
	}

	public static int generation() {
		return GENERATION.get();
	}

	public static void recordAdditions(boolean on) {
		recordAdditions = on;
		if (!on) {
			ADDED.clear();
		}
	}

	public static BlockPos pollAdded() {
		return ADDED.poll();
	}

	public static void ingest(Packet<?> packet) {
		if (packet instanceof ClientboundLevelChunkWithLightPacket chunkPacket) {
			//? if >=26.3 {
			/*ClientboundLevelChunkPacketData data = chunkPacket.chunkData();
			int cx = chunkPacket.x(), cz = chunkPacket.z();
			*///?} else {
			ClientboundLevelChunkPacketData data = chunkPacket.getChunkData();
			int cx = chunkPacket.getX(), cz = chunkPacket.getZ();
			//?}

			ClientboundLevelChunkPacketData.BlockEntityTagOutput output = (pos, type, tag) -> {
				if (type != null) {
					put(pos, type, tag, BlockEntityLeakTracker.Source.CHUNK);
				}
			};
			try {
				//? if >=26.3 {
				/*data.forEachBlockEntityTag(cx, cz, output);
				*///?} else {
				data.getBlockEntitiesTagsConsumer(cx, cz).accept(output);
				//?}
			} catch (Throwable ignored) {
			}
		} else if (packet instanceof ClientboundBlockEntityDataPacket bePacket) {
			put(bePacket.getPos(), bePacket.getType(), bePacket.getTag(), BlockEntityLeakTracker.Source.DATA);
		} else if (packet instanceof ClientboundBlockEventPacket event) {
			if (event.getBlock() instanceof EntityBlock) {
				hint(event.getPos(), event.getBlock(), BlockEntityLeakTracker.Source.EVENT);
			}
		} else if (packet instanceof ClientboundLevelEventPacket event) {
			Block block = eventBlock(event.getType());
			if (block != null && !event.isGlobalEvent()) {
				hint(event.getPos(), block, BlockEntityLeakTracker.Source.EVENT);
			}
		} else if (packet instanceof ClientboundSoundPacket sound) {
			Block block = soundBlock(sound.getSound());
			if (block != null) {
				hintAt(sound.getX(), sound.getY(), sound.getZ(), block);
			}
		} else if (packet instanceof ClientboundBlockUpdatePacket update) {
			dropRevealedHint(update.getPos(), update.getBlockState());
		} else if (packet instanceof ClientboundLoginPacket || packet instanceof ClientboundRespawnPacket) {
			clear();
		}
	}

	public static void hint(BlockPos pos, Block block, BlockEntityLeakTracker.Source source) {
		BlockPos key = pos.immutable();
		BlockEntityLeakTracker.Entry old = FOUND.get(key);
		if (old == null || !old.real() && old.block() == Blocks.AIR && block != Blocks.AIR) {
			FOUND.put(key, new BlockEntityLeakTracker.Entry(block, null, source));
			COUNTS.incrementAndGet(source.ordinal());
			if (recordAdditions) {
				ADDED.add(key);
			}
		}
	}

	private static void put(BlockPos pos, BlockEntityType<?> type, CompoundTag tag, BlockEntityLeakTracker.Source source) {
		if (!containsReal(pos)) {
			BlockPos key = pos.immutable();
			CompoundTag data = tag != null && !tag.isEmpty() ? tag : null;
			BlockEntityLeakTracker.Entry old = FOUND.get(key);
			if (old == null || !old.real()) {
				FOUND.put(key, new BlockEntityLeakTracker.Entry(blockFromType(type), data, source));
				COUNTS.incrementAndGet(source.ordinal());
				if (recordAdditions) {
					ADDED.add(key);
				}
			} else if (data != null && !data.equals(old.tag())) {
				FOUND.replace(key, old, new BlockEntityLeakTracker.Entry(old.block(), data, old.source()));
			}
		}
	}

	private static void dropRevealedHint(BlockPos pos, BlockState state) {
		BlockEntityLeakTracker.Entry old = FOUND.get(pos);
		if (old != null && !old.real()) {
			if (!state.isAir() && !state.is(Blocks.STONE) && !state.is(Blocks.DEEPSLATE) && !(state.getBlock() instanceof EntityBlock)) {
				if (FOUND.remove(pos, old)) {
					GENERATION.incrementAndGet();
				}
			}
		}
	}

	private static void hintAt(double x, double y, double z, Block block) {
		int bx = (int)Math.floor(x);
		int by = (int)Math.floor(y);
		int bz = (int)Math.floor(z);
		double fx = x - bx;
		double fy = y - by;
		double fz = z - bz;
		if (!(Math.abs(fy - 0.5) > 0.07)) {
			boolean seamX = fx < 0.07;
			boolean seamZ = fz < 0.07;
			if ((seamX || !(Math.abs(fx - 0.5) > 0.07)) && (seamZ || !(Math.abs(fz - 0.5) > 0.07)) && (!seamX || !seamZ)) {
				hint(new BlockPos(bx, by, bz), block, BlockEntityLeakTracker.Source.SOUND);
				if (seamX) {
					hint(new BlockPos(bx - 1, by, bz), block, BlockEntityLeakTracker.Source.SOUND);
				}

				if (seamZ) {
					hint(new BlockPos(bx, by, bz - 1), block, BlockEntityLeakTracker.Source.SOUND);
				}
			}
		}
	}

	private static Block eventBlock(int type) {
		return switch (type) {
			case 1000, 1001, 1002, 2000 -> Blocks.DISPENSER;
			case 1035 -> Blocks.BREWING_STAND;
			case 1049, 1050, 2010 -> Blocks.CRAFTER;
			default -> null;
		};
	}

	private static Block soundBlock(Holder<SoundEvent> sound) {
		Identifier id = ((SoundEvent)sound.value()).location();
		if (!id.getNamespace().equals("minecraft")) {
			return null;
		}

		String path = id.getPath();
		if (!path.startsWith("block.")) {
			return null;
		}

		String name = path.substring("block.".length());
		int dot = name.indexOf(46);
		if (dot < 0) {
			return null;
		}

		String kind = name.substring(0, dot);

		return switch (kind) {
			case "chest" -> Blocks.CHEST;
			case "ender_chest" -> Blocks.ENDER_CHEST;
			case "barrel" -> Blocks.BARREL;
			case "shulker_box" -> Blocks.SHULKER_BOX;
			case "beacon" -> Blocks.BEACON;
			case "vault" -> Blocks.VAULT;
			case "trial_spawner" -> Blocks.TRIAL_SPAWNER;
			case "chiseled_bookshelf" -> Blocks.CHISELED_BOOKSHELF;
			case "copper_chest" -> (Block)BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace("copper_chest"));
			default -> null;
		};
	}

	public static Block blockFromType(BlockEntityType<?> type) {
		Identifier id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type);
		if (id == null) {
			return Blocks.AIR;
		}

		String path = id.getPath();

		Block mapped = switch (path) {
			case "mob_spawner" -> Blocks.SPAWNER;
			case "piston" -> Blocks.MOVING_PISTON;
			case "sign" -> Blocks.OAK_SIGN;
			case "hanging_sign" -> Blocks.OAK_HANGING_SIGN;
			case "skull" -> Blocks.SKELETON_SKULL;
			case "banner" -> BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace("white_banner"));
			case "bed" -> BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace("red_bed"));
			case "brushable_block" -> Blocks.SUSPICIOUS_SAND;
			default -> null;
		};
		return mapped != null ? mapped : BuiltInRegistries.BLOCK.get(id).<Block>map(Holder::value).orElse(Blocks.AIR);
	}
	public record Entry(Block block, CompoundTag tag, BlockEntityLeakTracker.Source source) {
		public boolean real() {
			return this.source == BlockEntityLeakTracker.Source.CHUNK || this.source == BlockEntityLeakTracker.Source.DATA;
		}
	}
	public enum Source {
		CHUNK,
		DATA,
		EVENT,
		SOUND,
		LIGHT;
	}
}
