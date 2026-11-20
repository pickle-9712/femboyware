package dev.femboyware.util.playerbypass;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/** Ported from the Anubis client (GPL-3.0, by 4ldenz, https://github.com/4ldenz/Anubis-client). */
public final class PlayerBypassLightTracker {
	private static final Logger LOGGER = LoggerFactory.getLogger("femboyware");
	private static final Long2ObjectOpenHashMap<ChunkLightSnapshot> SNAPSHOTS = new Long2ObjectOpenHashMap();
	private static final LongOpenHashSet ALERTED = new LongOpenHashSet();
	private static final LongOpenHashSet SUSPECT = new LongOpenHashSet();
	static final int SUSPECT_TURNED_DARK = 1;
	static final int SUSPECT_AIRLESS = 2;
	private static final Long2IntOpenHashMap SENT_NOT_DARK = new Long2IntOpenHashMap();
	private static final int MAX_REMEMBERED_CHUNKS = 250000;
	private static String sentPlace;
	private static final Long2ObjectOpenHashMap<ChunkLightSnapshot> HELD = new Long2ObjectOpenHashMap();
	private static final ChunkEntryGuard ENTRY_GUARD = new ChunkEntryGuard();
	private static final ProtectedChunkStore PROTECTED_CHUNKS = ProtectedChunkStore.get();
	private static ClientLevel lastLevel;
	private static PlayerBypassLightTracker.SpottedListener spottedListener;
	private static long visibilityRevision;

	private PlayerBypassLightTracker() {
	}

	public static void onTick() {
		Minecraft mc = Minecraft.getInstance();
		syncLevel(mc);
		LocalPlayer player = mc.player;
		if (lastLevel != null && player != null) {
			ENTRY_GUARD.update(V.chunkKey(player.chunkPosition()), PlayerBypassLightTracker::isLoaded, PlayerBypassLightTracker::isFlaggedOrHeld, PROTECTED_CHUNKS);
		}

		PROTECTED_CHUNKS.tickAutosave();
	}

	public static void onPacketApplied(Packet<?> packet) {
		int chunkX;
		int chunkZ;
		ClientboundLightUpdatePacketData data;
		if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
			chunkX = V.x(chunk);
			chunkZ = V.z(chunk);
			data = V.light(chunk);
		} else {
			if (!(packet instanceof ClientboundLightUpdatePacket light)) {
				return;
			}

			chunkX = V.x(light);
			chunkZ = V.z(light);
			data = V.light(light);
		}

		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		syncLevel(mc);
		if (level != null && data != null) {
			if (mc.player != null && mc.player.getY() < 0.0) {
				hold(chunkX, chunkZ, data, level.getSectionsCount(), level.getMinSectionY());
			} else {
				ingest(chunkX, chunkZ, data, level.getSectionsCount(), level.getMinSectionY());
			}

			rememberNotDark(chunkX, chunkZ, data, level.getSectionsCount(), level.getMinSectionY());
			visibilityRevision++;
		}
	}

	private static void rememberNotDark(int chunkX, int chunkZ, ClientboundLightUpdatePacketData data, int sectionCount, int bottomSectionY) {
		int bits = LightDataClassifier.sentNotDark(data, sectionCount, bottomSectionY);
		if (bits != 0) {
			if (SENT_NOT_DARK.size() >= 250000) {
				SENT_NOT_DARK.clear();
			}

			long key = V.chunkKey(chunkX, chunkZ);
			SENT_NOT_DARK.put(key, SENT_NOT_DARK.get(key) | bits);
		}
	}

	private static void syncLevel(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level != lastLevel) {
			visibilityRevision++;
			SNAPSHOTS.clear();
			ALERTED.clear();
			SUSPECT.clear();
			HELD.clear();
			ENTRY_GUARD.reset();
			lastLevel = level;
			PROTECTED_CHUNKS.flush();
			if (level == null) {
				PROTECTED_CHUNKS.select(null, null);
			} else {
				String world = worldKey(mc);
				String dimension = level.dimension().identifier().toString();
				String place = world + "\n" + dimension;
				if (!place.equals(sentPlace)) {
					SENT_NOT_DARK.clear();
					sentPlace = place;
				}

				PROTECTED_CHUNKS.select(world, dimension);
			}
		}
	}

	private static String worldKey(Minecraft mc) {
		IntegratedServer local = mc.getSingleplayerServer();
		if (local != null) {
			Path folder = local.getWorldPath(LevelResource.ROOT).normalize().getFileName();
			return ProtectedChunkStore.singleplayerKey(folder == null ? "world" : folder.toString());
		} else {
			ServerData server = mc.getCurrentServer();
			return ProtectedChunkStore.serverKey(server == null ? null : server.ip);
		}
	}

	private static void ingest(int chunkX, int chunkZ, ClientboundLightUpdatePacketData data, int sectionCount, int bottomSectionY) {
		long key = V.chunkKey(chunkX, chunkZ);
		if (HELD.containsKey(key)) {
			hold(chunkX, chunkZ, data, sectionCount, bottomSectionY);
		}

		ChunkLightSnapshot snapshot = (ChunkLightSnapshot)SNAPSHOTS.get(key);
		boolean wasFlagged = snapshot != null && isFlagged(key, snapshot);
		if (snapshot == null || snapshot.sectionCount != sectionCount || snapshot.bottomSectionY != bottomSectionY) {
			snapshot = new ChunkLightSnapshot(sectionCount, bottomSectionY);
			SNAPSHOTS.put(key, snapshot);
		}

		try {
			LightDataClassifier.apply(data, snapshot);
		} catch (Throwable t) {
			LOGGER.error("Player Bypass light ingest failed for chunk {}, {}", new Object[]{chunkX, chunkZ, t});
			return;
		}

		boolean flagged = snapshot.hasBaseSignal();
		if (flagged && !PROTECTED_CHUNKS.flagAllowed(key, wasFlagged)) {
			SNAPSHOTS.remove(key);
		} else {
			int suspect = flagged ? suspicion(key, chunkX, chunkZ, snapshot) : 0;
			if (flagged && suspect != 0) {
				SUSPECT.add(key);
			} else if (flagged) {
				SUSPECT.remove(key);
				if (spottedListener != null && ALERTED.add(key)) {
					spottedListener.onChunkSpotted(chunkX, chunkZ);
				}
			} else if (!ALERTED.contains(key)) {
				SNAPSHOTS.remove(key);
				SUSPECT.remove(key);
			}
		}
	}

	private static int suspicion(long key, int chunkX, int chunkZ, ChunkLightSnapshot snapshot) {
		int sentNotDark = SENT_NOT_DARK.get(key);
		int why = 0;

		for (int i = 1; i < snapshot.sectionCount + 1; i++) {
			if (ChunkLightSnapshot.candidate(i, snapshot.bottomSectionY) && snapshot.sky[i] == LightSectionStatus.ZEROED_PRESENT) {
				if (i < 32 && (sentNotDark >>> i & 1) != 0) {
					why |= 1;
				} else {
					if (lastLevel == null || !DarkSectionCheck.airlessAround(lastLevel, chunkX, snapshot.bottomSectionY + i - 1, chunkZ)) {
						return 0;
					}

					why |= 2;
				}
			}
		}

		return why;
	}

	private static void hold(int chunkX, int chunkZ, ClientboundLightUpdatePacketData data, int sectionCount, int bottomSectionY) {
		long key = V.chunkKey(chunkX, chunkZ);
		ChunkLightSnapshot snapshot = (ChunkLightSnapshot)HELD.get(key);
		if (snapshot == null || snapshot.sectionCount != sectionCount || snapshot.bottomSectionY != bottomSectionY) {
			snapshot = new ChunkLightSnapshot(sectionCount, bottomSectionY);
		}

		try {
			LightDataClassifier.apply(data, snapshot);
		} catch (Throwable t) {
			LOGGER.error("Player Bypass light hold failed for chunk {}, {}", new Object[]{chunkX, chunkZ, t});
			return;
		}

		if (snapshot.hasBaseSignal() && PROTECTED_CHUNKS.flagAllowed(key, false)) {
			HELD.put(key, snapshot);
		} else {
			HELD.remove(key);
		}
	}

	public static void setSpottedListener(PlayerBypassLightTracker.SpottedListener listener) {
		spottedListener = listener;
	}

	public static Long2ObjectMap<ChunkLightSnapshot> snapshots() {
		return SNAPSHOTS;
	}

	public static long visibilityRevision() {
		return visibilityRevision;
	}

	public static boolean isSuspect(long chunkKey) {
		return SUSPECT.contains(chunkKey);
	}

	public static boolean isFlagged(long chunkKey, ChunkLightSnapshot snapshot) {
		return ALERTED.contains(chunkKey) || snapshot.hasBaseSignal();
	}

	public static boolean isFlagged(long chunkKey) {
		ChunkLightSnapshot snapshot = (ChunkLightSnapshot)SNAPSHOTS.get(chunkKey);
		return snapshot != null && isFlagged(chunkKey, snapshot);
	}

	private static boolean isFlaggedOrHeld(long chunkKey) {
		return isFlagged(chunkKey) || HELD.containsKey(chunkKey);
	}

	private static boolean isLoaded(long chunkKey) {
		return lastLevel != null && lastLevel.getChunkSource().hasChunk(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey));
	}

	@FunctionalInterface
	public interface SpottedListener {
		void onChunkSpotted(int var1, int var2);
	}
}
