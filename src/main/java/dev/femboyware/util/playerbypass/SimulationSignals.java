package dev.femboyware.util.playerbypass;

import it.unimi.dsi.fastutil.ints.Int2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
/** Ported from the Anubis client (GPL-3.0, by 4ldenz, https://github.com/4ldenz/Anubis-client). */
public final class SimulationSignals {
	private static final int RECENT = 6;
	private static final long SECOND = 1000000000L;
	private static final long CHUNK_SETTLE = 3000000000L;
	private static final long FRESH_ENTITY = 2000000000L;
	private static final long WANDER_MEMORY = 60000000000L;
	private static final long TELEPORT_QUIET = 2000000000L;
	private static final long LIGHTNING_LIFE = 3000000000L;
	private static final long UNDERGROUND_HOLD = 3000000000L;
	private static final long FROZEN_AFTER = 40000000000L;
	private static final int MAX_PINS = 32;
	private static final double MIN_MOVE_SQR = 1.0E-4;
	private static final double TELEPORT_JUMP_SQR = 64.0;
	private static final double SAMPLE_JUMP_SQR = 900.0;
	private static final double TRACKING_INSET = 24.0;
	private static final double TRACKED_DECAY = 0.5;
	private static final float FORGET_SCORE = 0.05F;
	private static final float WEAK_CAP = 4.0F;
	private static final float STRONG_CAP = 10.0F;
	private static final int MIN_Y = 0;
	private static final int BLOCK_SPILL = 1;
	private static final Set<Block> GROWS_FROM_AIR = Set.of(
		Blocks.SUGAR_CANE,
		Blocks.CACTUS,
		Blocks.BAMBOO,
		Blocks.BAMBOO_SAPLING,
		Blocks.MELON,
		Blocks.PUMPKIN,
		Blocks.SNOW,
		Blocks.FIRE,
		Blocks.SOUL_FIRE,
		Blocks.BROWN_MUSHROOM,
		Blocks.RED_MUSHROOM,
		Blocks.CHORUS_PLANT,
		Blocks.CHORUS_FLOWER
	);
	private static final Set<Block> GONE_TO_AIR = Set.of(Blocks.SNOW, Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.TURTLE_EGG, Blocks.SNIFFER_EGG, Blocks.FROGSPAWN);
	private static boolean active;
	private static int serverSim = -1;
	private static int serverView = -1;
	private static int simOverride;
	private static int margin;
	private static boolean ignoreVisiblePlayers = true;
	private static long memoryNanos = 30000000000L;
	private static int ticks;
	private static int session;
	private static ResourceKey<Level> dimension;
	private static long quietUntil;
	private static long undergroundUntil;
	private static double lastX;
	private static double lastZ;
	private static double sampleX;
	private static double sampleZ;
	private static boolean hasLast;
	private static boolean hasSample;
	private static long passedCenter = Long.MIN_VALUE;
	private static int passedRadius = -1;
	private static final Long2ObjectOpenHashMap<SimulationSignals.ChunkSignal> CHUNKS = new Long2ObjectOpenHashMap();
	private static final Int2ObjectOpenHashMap<SimulationSignals.Share> ENTITY_SHARES = new Int2ObjectOpenHashMap();
	private static final Long2ObjectOpenHashMap<SimulationSignals.Share> BLOCK_SHARES = new Long2ObjectOpenHashMap();
	private static final Long2LongOpenHashMap CHUNK_LOADED = new Long2LongOpenHashMap();
	private static final Int2LongOpenHashMap ENTITY_ADDED = new Int2LongOpenHashMap();
	private static final Int2LongOpenHashMap ENTITY_INSIDE = new Int2LongOpenHashMap();
	private static final Map<ResourceKey<Level>, LongOpenHashSet> PASSED_BY_DIMENSION = new HashMap<>();
	private static LongOpenHashSet passed = new LongOpenHashSet();
	private static final Int2DoubleOpenHashMap TRACKED_RANGE = new Int2DoubleOpenHashMap();
	private static final Int2ObjectOpenHashMap<long[]> LIGHTNING_PENDING = new Int2ObjectOpenHashMap();
	private static final Int2LongOpenHashMap LAST_ACTIVE = new Int2LongOpenHashMap();
	private static final LongOpenHashSet FROZEN = new LongOpenHashSet();
	private static final ArrayDeque<SimulationSignals.Pin> PINS = new ArrayDeque<>();

	private SimulationSignals() {
	}

	public static void configure(boolean on, int simulationOverride, int ringMargin, boolean ignorePlayers, int memorySeconds) {
		if (active && !on) {
			clear();
		}

		active = on;
		simOverride = simulationOverride;
		margin = ringMargin;
		ignoreVisiblePlayers = ignorePlayers;
		memoryNanos = Math.max(1, memorySeconds) * 1000000000L;
	}

	public static void clear() {
		resetLevel();
		PASSED_BY_DIMENSION.clear();
		passed = new LongOpenHashSet();
		if (dimension != null) {
			PASSED_BY_DIMENSION.put(dimension, passed);
		}
	}

	private static void resetLevel() {
		CHUNKS.clear();
		ENTITY_SHARES.clear();
		BLOCK_SHARES.clear();
		ENTITY_ADDED.clear();
		ENTITY_INSIDE.clear();
		TRACKED_RANGE.clear();
		LIGHTNING_PENDING.clear();
		LAST_ACTIVE.clear();
		FROZEN.clear();
		PINS.clear();
		hasLast = false;
		hasSample = false;
		passedCenter = Long.MIN_VALUE;
		passedRadius = -1;
	}

	public static int session() {
		return session;
	}

	public static int serverView() {
		return serverView;
	}

	public static int simulation() {
		int sim = simOverride > 0 ? simOverride : (serverSim > 0 ? serverSim : 5);
		return Math.max(1, Math.min(sim, view() - 3 - margin));
	}

	public static int view() {
		int option = Minecraft.getInstance().options.getEffectiveRenderDistance();
		return serverView > 0 ? Math.min(serverView, option) : option;
	}

	public static int ringStart() {
		return simulation() + 2 + margin;
	}

	public static Long2ObjectMap<SimulationSignals.ChunkSignal> chunks() {
		return CHUNKS;
	}

	public static LongOpenHashSet frozen() {
		return FROZEN;
	}

	public static SimulationSignals.Pin pollPin() {
		return PINS.poll();
	}

	public static void dropPins() {
		PINS.clear();
	}

	public static boolean holding() {
		LocalPlayer self = Minecraft.getInstance().player;
		return now() < undergroundUntil || self != null && self.getY() < 0.0;
	}

	public static long now() {
		return System.nanoTime();
	}

	static float decay(long elapsed) {
		return (float)Math.exp(-elapsed / memoryNanos);
	}

	public static void onLogin(int simulationDistance, int chunkRadius, ResourceKey<Level> level) {
		serverSim = simulationDistance;
		serverView = chunkRadius;
		session++;
		dimension = level;
		CHUNK_LOADED.clear();
		clear();
		startQuiet();
	}

	public static void onRespawn(ResourceKey<Level> level) {
		startQuiet();
		if (!level.equals(dimension)) {
			dimension = level;
			CHUNK_LOADED.clear();
			resetLevel();
			passed = PASSED_BY_DIMENSION.computeIfAbsent(level, key -> new LongOpenHashSet());
		}
	}

	public static void onTeleport() {
		startQuiet();
	}

	public static void onSimulationDistance(int distance) {
		serverSim = distance;
	}

	public static void onViewDistance(int distance) {
		serverView = distance;
	}

	public static void onChunk(int x, int z) {
		if (active) {
			CHUNK_LOADED.put(V.chunkKey(x, z), now());
		}
	}

	public static void onMove(ClientboundMoveEntityPacket packet) {
		if (active) {
			ClientLevel level = Minecraft.getInstance().level;
			if (level != null) {
				Entity entity = packet.getEntity(level);
				if (entity != null) {
					boolean moved = V.moved(packet);
					if (moved || packet.hasRotation()) {
						LAST_ACTIVE.put(entity.getId(), now());
					}

					if (moved) {
						entitySignal(entity, SimulationSignals.Kind.MOVE, 0);
					} else if (packet.hasRotation()) {
						entitySignal(entity, SimulationSignals.Kind.HEAD, 0);
					}
				}
			}
		}
	}

	public static void onPositionSync(ClientboundEntityPositionSyncPacket packet) {
		if (active) {
			ClientLevel level = Minecraft.getInstance().level;
			if (level != null) {
				Entity entity = level.getEntity(packet.id());
				if (entity instanceof LivingEntity) {
					if (!(entity.position().distanceToSqr(V.syncPosition(packet)) < 1.0E-4)) {
						LAST_ACTIVE.put(entity.getId(), now());
						entitySignal(entity, SimulationSignals.Kind.MOVE, 0);
					}
				}
			}
		}
	}

	public static void onHeadRotation(ClientboundRotateHeadPacket packet) {
		if (active) {
			ClientLevel level = Minecraft.getInstance().level;
			if (level != null) {
				Entity entity = packet.getEntity(level);
				if (entity != null) {
					LAST_ACTIVE.put(entity.getId(), now());
					entitySignal(entity, SimulationSignals.Kind.HEAD, 0);
				}
			}
		}
	}

	public static void onEntityData(ClientboundSetEntityDataPacket packet) {
		if (active) {
			ClientLevel level = Minecraft.getInstance().level;
			if (level != null) {
				if (now() - ENTITY_ADDED.getOrDefault(packet.id(), -4611686018427387904L) >= 2000000000L) {
					Entity entity = level.getEntity(packet.id());
					if (entity != null) {
						LAST_ACTIVE.put(entity.getId(), now());
						entitySignal(entity, SimulationSignals.Kind.STATE, 0);
					}
				}
			}
		}
	}

	public static void onEntityEvent(ClientboundEntityEventPacket packet) {
		if (active) {
			ClientLevel level = Minecraft.getInstance().level;
			if (level != null) {
				Entity entity = packet.getEntity(level);
				if (entity != null) {
					LAST_ACTIVE.put(entity.getId(), now());
					entitySignal(entity, SimulationSignals.Kind.EVENT, 0);
				}
			}
		}
	}

	public static void onEquipment(ClientboundSetEquipmentPacket packet) {
		if (active) {
			ClientLevel level = Minecraft.getInstance().level;
			if (level != null) {
				if (now() - ENTITY_ADDED.getOrDefault(packet.getEntity(), -4611686018427387904L) >= 2000000000L) {
					Entity entity = level.getEntity(packet.getEntity());
					if (entity != null) {
						LAST_ACTIVE.put(entity.getId(), now());
						entitySignal(entity, SimulationSignals.Kind.EQUIP, 0);
					}
				}
			}
		}
	}

	public static void onDamage(ClientboundDamageEventPacket packet) {
		if (active) {
			Minecraft mc = Minecraft.getInstance();
			ClientLevel level = mc.level;
			LocalPlayer self = mc.player;
			if (level != null && self != null) {
				Holder<DamageType> type = packet.sourceType();
				if (!type.is(DamageTypes.SWEET_BERRY_BUSH) && !type.is(DamageTypes.STALAGMITE) && !type.is(DamageTypes.FALLING_STALACTITE)) {
					Entity victim = level.getEntity(packet.entityId());
					if (victim != null) {
						int cause = packet.sourceCauseId();
						if (type.is(DamageTypes.PLAYER_ATTACK) && cause >= 0 && cause != self.getId() && level.getEntity(cause) == null) {
							pin(victim.blockPosition(), "Hidden Attacker hit " + name(victim.getType()));
						}

						LAST_ACTIVE.put(victim.getId(), now());
						entitySignal(victim, SimulationSignals.Kind.HURT, 0);
					}
				}
			}
		}
	}

	public static void onTakeItem(ClientboundTakeItemEntityPacket packet) {
		if (active) {
			Minecraft mc = Minecraft.getInstance();
			ClientLevel level = mc.level;
			LocalPlayer self = mc.player;
			if (level != null && self != null) {
				int collector = packet.getPlayerId();
				if (collector != self.getId()) {
					Entity item = level.getEntity(packet.getItemId());
					if (item != null) {
						Entity taker = level.getEntity(collector);
						if (taker == null) {
							String what = item instanceof ItemEntity stack ? BuiltInRegistries.ITEM.getKey(stack.getItem().getItem()).getPath() : name(item.getType());
							pin(item.blockPosition(), "Hidden Pickup " + what);
						} else if (!(taker instanceof Player)) {
							LAST_ACTIVE.put(taker.getId(), now());
							entitySignal(taker, SimulationSignals.Kind.EVENT, 0);
						}
					}
				}
			}
		}
	}

	public static void onAddEntity(ClientboundAddEntityPacket packet) {
		if (active) {
			long now = now();
			ENTITY_ADDED.put(packet.getId(), now);
			LAST_ACTIVE.put(packet.getId(), now);
			EntityType<?> type = packet.getType();
			if (type != EntityType.PLAYER) {
				BlockPos pos = BlockPos.containing(packet.getX(), packet.getY(), packet.getZ());
				if (type == EntityType.LIGHTNING_BOLT) {
					LIGHTNING_PENDING.put(packet.getId(), new long[]{pos.asLong(), now});
				} else if (spawnable(type) && !isQuiet()) {
					if (wellInsideTracking(packet.getX(), packet.getZ(), type)) {
						if (settled(pos)) {
							int id = packet.getId();
							signal(pos, SimulationSignals.Kind.SPAWN, spill(type), () -> entityShare(id), () -> name(type));
						}
					}
				}
			}
		}
	}

	public static void onRemoveEntities(IntList ids) {
		if (active) {
			ClientLevel level = Minecraft.getInstance().level;
			if (level != null) {
				long now = now();
				boolean quiet = isQuiet();

				for (int i = 0; i < ids.size(); i++) {
					int id = ids.getInt(i);
					long[] bolt = (long[])LIGHTNING_PENDING.remove(id);
					if (bolt != null) {
						if (!quiet && now - bolt[1] < 3000000000L) {
							signal(BlockPos.of(bolt[0]), SimulationSignals.Kind.LIGHTNING, 0, () -> entityShare(id), () -> "lightning_bolt");
						}
					} else if (!quiet) {
						Entity entity = level.getEntity(id);
						if (entity != null
							&& !(entity instanceof Player)
							&& now - ENTITY_ADDED.getOrDefault(id, -4611686018427387904L) >= 2000000000L
							&& spawnable(entity.getType())
							&& wellInsideTracking(entity.getX(), entity.getZ(), entity.getType())
							&& settled(entity.blockPosition())) {
							entitySignal(entity, SimulationSignals.Kind.REMOVE, spill(entity.getType()));
						}
					}
				}
			}
		}
	}

	public static void onBlockUpdate(ClientboundBlockUpdatePacket packet) {
		if (active) {
			block(packet.getPos(), packet.getBlockState());
		}
	}

	public static void onSectionUpdate(ClientboundSectionBlocksUpdatePacket packet) {
		if (active) {
			packet.runUpdates(SimulationSignals::block);
		}
	}

	private static void block(BlockPos pos, BlockState state) {
		ClientLevel level = Minecraft.getInstance().level;
		if (level != null) {
			BlockState previous = level.getBlockState(pos);
			if (previous != state) {
				if (!ignored(previous) && !ignored(state)) {
					if (settled(pos)) {
						SimulationSignals.Kind kind = ticked(previous, state) ? SimulationSignals.Kind.TICK : SimulationSignals.Kind.BLOCK;
						signal(pos, kind, 1, () -> blockShare(pos), () -> describe(previous) + " -> " + describe(state));
					}
				}
			}
		}
	}

	static boolean ticked(BlockState previous, BlockState state) {
		Block from = previous.getBlock();
		Block to = state.getBlock();
		if (from == to) {
			return true;
		}

		if (!previous.getFluidState().isEmpty() || !state.getFluidState().isEmpty()) {
			return true;
		}

		if (previous.is(BlockTags.LEAVES) && state.isAir()) {
			return true;
		}

		if (previous.isAir() && state.is(BlockTags.LEAVES) && !(Boolean)state.getValueOrElse(BlockStateProperties.PERSISTENT, true)) {
			return true;
		}

		if (!(previous.getBlock() instanceof net.minecraft.world.level.block.SaplingBlock) || !state.is(BlockTags.LOGS) && !state.is(BlockTags.LEAVES)) {
			if (previous.is(BlockTags.CROPS) && state.isAir()) {
				return true;
			}

			if (from != Blocks.DIRT || to != Blocks.GRASS_BLOCK && to != Blocks.MYCELIUM) {
				if (from == Blocks.FARMLAND && to == Blocks.DIRT) {
					return true;
				} else if (from == Blocks.MELON_STEM && to == Blocks.ATTACHED_MELON_STEM) {
					return true;
				} else if (from == Blocks.PUMPKIN_STEM && to == Blocks.ATTACHED_PUMPKIN_STEM) {
					return true;
				} else if (previous.isAir() && GROWS_FROM_AIR.contains(to)) {
					return true;
				} else {
					return state.isAir() && GONE_TO_AIR.contains(from) ? true : WeatheringCopper.getNext(from).map(next -> next == to).orElse(false);
				}
			} else {
				return true;
			}
		} else {
			return true;
		}
	}

	private static boolean ignored(BlockState state) {
		return state.is(Blocks.POINTED_DRIPSTONE)
			|| state.is(Blocks.CAVE_VINES)
			|| state.is(Blocks.CAVE_VINES_PLANT)
			|| state.is(Blocks.SWEET_BERRY_BUSH)
			|| state.is(Blocks.BEEHIVE)
			|| state.is(Blocks.BEE_NEST)
			|| state.is(Blocks.VINE)
			|| state.is(Blocks.WEEPING_VINES)
			|| state.is(Blocks.WEEPING_VINES_PLANT)
			|| state.is(Blocks.TWISTING_VINES)
			|| state.is(Blocks.TWISTING_VINES_PLANT);
	}

	public static void tick() {
		if (active) {
			Minecraft mc = Minecraft.getInstance();
			LocalPlayer self = mc.player;
			if (mc.level != null && self != null) {
				long now = now();
				if (self.getY() < 0.0) {
					undergroundUntil = now + 3000000000L;
				}

				double x = self.getX();
				double z = self.getZ();
				if (hasLast && square(x - lastX, z - lastZ) > 64.0) {
					startQuiet();
				}

				lastX = x;
				lastZ = z;
				hasLast = true;
				refreshPassed(self);
				if (++ticks % 10 == 0) {
					int pcx = self.blockPosition().getX() >> 4;
					int pcz = self.blockPosition().getZ() >> 4;
					int start = ringStart();
					boolean sample = !isQuiet() && (!hasSample || square(x - sampleX, z - sampleZ) <= 900.0);
					sampleX = x;
					sampleZ = z;
					hasSample = true;
					Int2DoubleOpenHashMap reach = new Int2DoubleOpenHashMap();
					int view = view();
					FROZEN.clear();

					for (Entity entity : mc.level.entitiesForRendering()) {
						if (!(entity instanceof Player)) {
							long seen = LAST_ACTIVE.get(entity.getId());
							if (seen == 0L) {
								LAST_ACTIVE.put(entity.getId(), now);
							} else if (now - seen > 40000000000L && witness(entity)) {
								int ex = entity.blockPosition().getX() >> 4;
								int ez = entity.blockPosition().getZ() >> 4;
								int d = distance(ex, ez, pcx, pcz);
								if (d >= start && d <= view && !nearVisiblePlayer(ex, ez, self, 0)) {
									FROZEN.add(V.chunkKey(ex, ez));
								}
							}

							if (sample && spawnable(entity.getType()) && !entity.isVehicle() && !entity.isPassenger()) {
								int key = entity.getType().clientTrackingRange();
								double distance = Math.hypot(entity.getX() - x, entity.getZ() - z);
								if (distance > reach.get(key)) {
									reach.put(key, distance);
								}
							}

							if (distance(entity.blockPosition().getX() >> 4, entity.blockPosition().getZ() >> 4, pcx, pcz) < start) {
								ENTITY_INSIDE.put(entity.getId(), now);
							}
						}
					}

					if (sample) {
						for (int key : TRACKED_RANGE.keySet().toIntArray()) {
							TRACKED_RANGE.put(key, Math.max(reach.get(key), TRACKED_RANGE.get(key) - 0.5));
						}

						ObjectIterator var22 = reach.int2DoubleEntrySet().iterator();

						while (var22.hasNext()) {
							it.unimi.dsi.fastutil.ints.Int2DoubleMap.Entry entry = (it.unimi.dsi.fastutil.ints.Int2DoubleMap.Entry)var22.next();
							if (entry.getDoubleValue() > TRACKED_RANGE.get(entry.getIntKey())) {
								TRACKED_RANGE.put(entry.getIntKey(), entry.getDoubleValue());
							}
						}
					}

					if (ticks % 200 == 0) {
						Iterator<Entry<SimulationSignals.ChunkSignal>> chunks = CHUNKS.long2ObjectEntrySet().iterator();

						while (chunks.hasNext()) {
							if (((SimulationSignals.ChunkSignal)chunks.next().getValue()).score(now) < 0.05F) {
								chunks.remove();
							}
						}

						ENTITY_SHARES.values().removeIf(share -> share.amount(now) < 0.05F);
						BLOCK_SHARES.values().removeIf(share -> share.amount(now) < 0.05F);
						int keep = view() + 4;
						CHUNK_LOADED.long2LongEntrySet().removeIf(e -> distance(ChunkPos.getX(e.getLongKey()), ChunkPos.getZ(e.getLongKey()), pcx, pcz) > keep);
						ENTITY_ADDED.int2LongEntrySet().removeIf(e -> now - e.getLongValue() > 60000000000L);
						ENTITY_INSIDE.int2LongEntrySet().removeIf(e -> now - e.getLongValue() > 60000000000L);
						LIGHTNING_PENDING.values().removeIf(bolt -> now - bolt[1] > 3000000000L);
						LAST_ACTIVE.keySet().removeIf(id -> mc.level.getEntity(id) == null);
					}
				}
			}
		}
	}

	private static boolean witness(Entity entity) {
		return entity instanceof PathfinderMob mob && !(mob instanceof Shulker) && !mob.isNoAi() && !mob.isPassenger() && !mob.isVehicle() && !mob.isSleeping();
	}

	private static void pin(BlockPos pos, String detail) {
		LocalPlayer self = Minecraft.getInstance().player;
		if (self != null && !holding() && pos.getY() >= 0) {
			if (PINS.size() >= 32) {
				PINS.poll();
			}

			PINS.add(new SimulationSignals.Pin(pos.immutable(), detail + " @ " + pos.getX() + " " + pos.getY() + " " + pos.getZ()));
		}
	}

	private static void refreshPassed(LocalPlayer self) {
		int pcx = self.blockPosition().getX() >> 4;
		int pcz = self.blockPosition().getZ() >> 4;
		long center = V.chunkKey(pcx, pcz);
		int radius = ringStart() - 1 + 1;
		if (center != passedCenter || radius != passedRadius) {
			passedCenter = center;
			passedRadius = radius;

			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					long key = V.chunkKey(pcx + dx, pcz + dz);
					if (passed.add(key)) {
						CHUNKS.remove(key);
					}
				}
			}
		}
	}

	private static void entitySignal(Entity entity, SimulationSignals.Kind kind, int spill) {
		if (!(entity instanceof Player)) {
			if (!(entity.getFirstPassenger() instanceof Player)) {
				if (!(entity instanceof FallingBlockEntity falling && falling.getBlockState().is(Blocks.POINTED_DRIPSTONE))) {
					LocalPlayer self = Minecraft.getInstance().player;
					if (self != null) {
						if (entity instanceof OwnableEntity ownable) {
							EntityReference<?> owner = ownable.getOwnerReference();
							if (owner != null && self.getUUID().equals(owner.getUUID())) {
								return;
							}
						}

						if (now() - ENTITY_INSIDE.getOrDefault(entity.getId(), -4611686018427387904L) >= 60000000000L) {
							signal(entity.blockPosition(), kind, spill, () -> entityShare(entity.getId()), () -> name(entity.getType()));
						}
					}
				}
			}
		}
	}

	private static SimulationSignals.Share entityShare(int id) {
		return (SimulationSignals.Share)ENTITY_SHARES.computeIfAbsent(id, key -> new SimulationSignals.Share());
	}

	private static SimulationSignals.Share blockShare(BlockPos pos) {
		return (SimulationSignals.Share)BLOCK_SHARES.computeIfAbsent(pos.asLong(), key -> new SimulationSignals.Share());
	}

	private static String name(EntityType<?> type) {
		return BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath();
	}

	private static String describe(BlockState state) {
		return state.toString().replace("Block{minecraft:", "").replace("}", "");
	}

	private static void signal(BlockPos pos, SimulationSignals.Kind kind, int spill, Supplier<SimulationSignals.Share> source, Supplier<String> what) {
		LocalPlayer self = Minecraft.getInstance().player;
		if (self != null) {
			if (pos.getY() >= 0 && !holding()) {
				int cx = pos.getX() >> 4;
				int cz = pos.getZ() >> 4;
				int pcx = self.blockPosition().getX() >> 4;
				int pcz = self.blockPosition().getZ() >> 4;
				int d = distance(cx, cz, pcx, pcz);
				if (d >= ringStart() + spill && d <= view()) {
					long key = V.chunkKey(cx, cz);
					if (!passed.contains(key)) {
						if (!ignoreVisiblePlayers || !nearVisiblePlayer(cx, cz, self, spill)) {
							long now = now();
							float weight = source.get().take(kind.weight, kind.cap, now);
							if (!(weight <= 0.0F)) {
								String detail = kind.title + " " + what.get() + " @ " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
								((SimulationSignals.ChunkSignal)CHUNKS.computeIfAbsent(key, k -> new SimulationSignals.ChunkSignal())).add(kind, now, weight, detail);
							}
						}
					}
				}
			}
		}
	}

	private static boolean nearVisiblePlayer(int cx, int cz, LocalPlayer self, int spill) {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) {
			return false;
		}

		int reach = simulation() + 1 + spill;

		for (Player player : level.players()) {
			if (player != self && distance(cx, cz, player.blockPosition().getX() >> 4, player.blockPosition().getZ() >> 4) <= reach) {
				return true;
			}
		}

		return false;
	}

	private static boolean settled(BlockPos pos) {
		long loaded = CHUNK_LOADED.getOrDefault(V.chunkKey(pos.getX() >> 4, pos.getZ() >> 4), Long.MIN_VALUE);
		return loaded == Long.MIN_VALUE || now() - loaded > 3000000000L;
	}

	private static boolean wellInsideTracking(double x, double z, EntityType<?> type) {
		LocalPlayer self = Minecraft.getInstance().player;
		if (self == null) {
			return false;
		}

		double range = Math.min(TRACKED_RANGE.get(type.clientTrackingRange()), view() * 16.0);
		return Math.hypot(x - self.getX(), z - self.getZ()) < range - 24.0;
	}

	private static boolean spawnable(EntityType<?> type) {
		return type.getCategory() == MobCategory.MONSTER || type == EntityType.ITEM || type == EntityType.EXPERIENCE_ORB;
	}

	private static int spill(EntityType<?> type) {
		return type != EntityType.ITEM && type != EntityType.EXPERIENCE_ORB ? 0 : 1;
	}

	private static void startQuiet() {
		quietUntil = now() + 2000000000L;
	}

	private static boolean isQuiet() {
		return now() < quietUntil;
	}

	private static double square(double dx, double dz) {
		return dx * dx + dz * dz;
	}

	public static int distance(int ax, int az, int bx, int bz) {
		return Math.max(Math.abs(ax - bx), Math.abs(az - bz));
	}
	public static final class ChunkSignal {
		private float score;
		private long updated;
		private int kinds;
		private final int[] counts = new int[SimulationSignals.Kind.values().length];
		private final ArrayDeque<String> recent = new ArrayDeque<>();

		public float score(long now) {
			return this.score * SimulationSignals.decay(now - this.updated);
		}

		public boolean has(SimulationSignals.Kind kind) {
			return (this.kinds & 1 << kind.ordinal()) != 0;
		}

		public int count(SimulationSignals.Kind kind) {
			return this.counts[kind.ordinal()];
		}

		public List<String> recent() {
			return List.copyOf(this.recent);
		}

		void add(SimulationSignals.Kind kind, long now, float weight, String detail) {
			this.recent.addLast(detail);
			if (this.recent.size() > 6) {
				this.recent.removeFirst();
			}

			this.score = this.score(now) + weight;
			this.updated = now;
			this.kinds = this.kinds | 1 << kind.ordinal();
			this.counts[kind.ordinal()]++;
		}
	}
	public enum Kind {
		MOVE("Mob Move", 2.5F, true),
		HEAD("Head Turn", 2.5F, true),
		SPAWN("Spawn / Drop", 2.0F, false),
		REMOVE("Despawn / Pickup", 1.5F, false),
		STATE("Entity State", 0.5F, false),
		EVENT("Entity Event", 5.0F, true),
		BLOCK("Block Change", 1.6F, false),
		TICK("Tick Change", 10.0F, true),
		LIGHTNING("Lightning", 10.0F, true),
		HURT("Hurt", 5.0F, true),
		EQUIP("Equipment", 5.0F, true);

		public final String title;
		final float weight;
		final float cap;

		Kind(String title, float weight, boolean ticked) {
			this.title = title;
			this.weight = weight;
			this.cap = ticked ? 10.0F : 4.0F;
		}
	}
	public record Pin(BlockPos pos, String detail) {
	}
	private static final class Share {
		private float amount;
		private long updated;

		float amount(long now) {
			return this.amount * SimulationSignals.decay(now - this.updated);
		}

		float take(float weight, float cap, long now) {
			float held = this.amount(now);
			float granted = Math.min(weight, Math.max(cap, weight) - held);
			if (granted <= 0.0F) {
				return 0.0F;
			}

			this.amount = held + granted;
			this.updated = now;
			return granted;
		}
	}
}
