package dev.femboyware.util.playerbypass;

import java.util.BitSet;
import java.util.List;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/** The packet and chunk accessors Player Bypass reads, across Minecraft versions. */
public final class V {
	private V() {
	}

	//? if >=26.1 {
	/*public static long chunkKey(int x, int z) {
		return ChunkPos.pack(x, z);
	}

	public static long chunkKey(ChunkPos pos) {
		return pos.pack();
	}
	*///?} else {
	public static long chunkKey(int x, int z) {
		return ChunkPos.asLong(x, z);
	}

	public static long chunkKey(ChunkPos pos) {
		return pos.toLong();
	}
	//?}

	//? if >=26.3 {
	/*public static int x(ClientboundLevelChunkWithLightPacket p) { return p.x(); }
	public static int z(ClientboundLevelChunkWithLightPacket p) { return p.z(); }
	public static ClientboundLightUpdatePacketData light(ClientboundLevelChunkWithLightPacket p) { return p.lightData(); }
	public static int x(ClientboundLightUpdatePacket p) { return p.x(); }
	public static int z(ClientboundLightUpdatePacket p) { return p.z(); }
	public static ClientboundLightUpdatePacketData light(ClientboundLightUpdatePacket p) { return p.lightData(); }
	public static BitSet skyMask(ClientboundLightUpdatePacketData d) { return d.skyYMask(); }
	public static BitSet emptySkyMask(ClientboundLightUpdatePacketData d) { return d.emptySkyYMask(); }
	public static List<byte[]> skyUpdates(ClientboundLightUpdatePacketData d) { return d.skyUpdates(); }
	public static BitSet blockMask(ClientboundLightUpdatePacketData d) { return d.blockYMask(); }
	public static BitSet emptyBlockMask(ClientboundLightUpdatePacketData d) { return d.emptyBlockYMask(); }
	public static List<byte[]> blockUpdates(ClientboundLightUpdatePacketData d) { return d.blockUpdates(); }
	public static boolean moved(ClientboundMoveEntityPacket p) { return p.hasPosition() && !p.getPositionDelta().equals(net.minecraft.network.protocol.game.VecDelta.ZERO); }
	public static Vec3 syncPosition(ClientboundEntityPositionSyncPacket p) { return p.position().endPosition(); }
	*///?} else {
	public static int x(ClientboundLevelChunkWithLightPacket p) { return p.getX(); }
	public static int z(ClientboundLevelChunkWithLightPacket p) { return p.getZ(); }
	public static ClientboundLightUpdatePacketData light(ClientboundLevelChunkWithLightPacket p) { return p.getLightData(); }
	public static int x(ClientboundLightUpdatePacket p) { return p.getX(); }
	public static int z(ClientboundLightUpdatePacket p) { return p.getZ(); }
	public static ClientboundLightUpdatePacketData light(ClientboundLightUpdatePacket p) { return p.getLightData(); }
	public static BitSet skyMask(ClientboundLightUpdatePacketData d) { return d.getSkyYMask(); }
	public static BitSet emptySkyMask(ClientboundLightUpdatePacketData d) { return d.getEmptySkyYMask(); }
	public static List<byte[]> skyUpdates(ClientboundLightUpdatePacketData d) { return d.getSkyUpdates(); }
	public static BitSet blockMask(ClientboundLightUpdatePacketData d) { return d.getBlockYMask(); }
	public static BitSet emptyBlockMask(ClientboundLightUpdatePacketData d) { return d.getEmptyBlockYMask(); }
	public static List<byte[]> blockUpdates(ClientboundLightUpdatePacketData d) { return d.getBlockUpdates(); }
	public static boolean moved(ClientboundMoveEntityPacket p) { return p.hasPosition() && (p.getXa() != 0 || p.getYa() != 0 || p.getZa() != 0); }
	public static Vec3 syncPosition(ClientboundEntityPositionSyncPacket p) { return p.values().position(); }
	//?}
}
