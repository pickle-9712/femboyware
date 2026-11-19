package dev.femboyware.module.movement;

import dev.femboyware.FemboyWare;
import dev.femboyware.event.PacketEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.Mc;
import dev.femboyware.util.VehicleControl;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundPaddleBoatPacket;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Fly your boat through blocks and entities. Each tick's move is split into short hops the server
 * replays as legal, capped by how far ahead of the server you are, so it isn't set back. Ported from
 * the Anubis client's Boat Noclip (GPL-3.0, by 4ldenz, https://github.com/4ldenz/Anubis-client).
 */
public class BoatNoclipModule extends Module {
	private static final double MAX_MISS = 0.24, MAX_PACKET_LENGTH = 1, MAX_SERVER_LEAD = 19, MAX_UNCONFIRMED = 28.5;
	private static final int MAX_PACKETS_PER_TICK = 16, STALE_SERVER_TICKS = 40;
	private static final double MIN_BURST = 2, MOUNT_BURST = 4, BURST_RECOVERY = 0.025, CAUTION_GROWTH = 1.25, LAVA_MARGIN = 3;
	private static final double KICK_DIP = 0.0313;
	private static final long KICK_HOLD_NANOS = 50_000_000L;

	private final NumberSetting speed = add(new NumberSetting("Speed", "Blocks per second in the open", 10, 0, 320, 0.5));
	private final NumberSetting speedInside = add(new NumberSetting("Speed Inside Blocks", "Blocks per second inside blocks", 5, 0, 320, 0.5));
	private final NumberSetting verticalSpeed = add(new NumberSetting("Vertical Speed", "Jump to rise, sprint to sink", 6, 0, 320, 0.5));
	private final NumberSetting fallSpeed = add(new NumberSetting("Fall Speed", "Sinking when not pressing anything", 0, 0, 10, 0.1));
	private final BoolSetting lavaProtection = add(new BoolSetting("Lava Protection", "Never fly into lava", true));
	private final BoolSetting antiKick = add(new BoolSetting("Anti Fly Kick", "Dip now and then so the server doesn't kick you for flying", true));
	private final NumberSetting delay = add(new NumberSetting("Delay", "Ticks between anti-kick dips", 40, 1, 80, 1));

	private AbstractBoat noclipBoat;
	private Vec3 serverPos, lastBase;
	private int staleTicks, sinceBase;
	private double cautionCap = Double.POSITIVE_INFINITY, burst = MOUNT_BURST, lastMoveLength;
	private boolean sendingStops, resending;

	// Anti fly kick
	private int delayLeft;
	private double lastPacketY = Double.MAX_VALUE;
	private boolean dipped;
	private long dipNanos;
	private Entity trackedVehicle;

	public BoatNoclipModule() {
		super("Boat Noclip", "Fly your boat through blocks and entities", Category.RAGE);
	}

	public static BoatNoclipModule active() {
		if (FemboyWare.getModules() == null) return null;
		BoatNoclipModule module = FemboyWare.getModules().get(BoatNoclipModule.class);
		return module != null && module.isEnabled() ? module : null;
	}

	@Override
	protected void onEnable() {
		BoatFlyModule fly = FemboyWare.getModules().get(BoatFlyModule.class);
		if (fly != null && fly.isEnabled()) fly.setEnabled(false);
		delayLeft = delay.get().intValue();
		forgetKick();
		trackedVehicle = mc.player != null ? mc.player.getVehicle() : null;
	}

	@Override
	protected void onDisable() {
		mc.smartCull = true;
		trackedVehicle = null;
		releaseBoat();
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc.player;
		if (noclipBoat != null && (player == null || player.getVehicle() != noclipBoat)) releaseBoat();
		tickKick();
		if (mc.level != null) mc.smartCull = !(noclipBoat != null && mc.level.getBlockState(Mc.camera().blockPosition()).isSolidRender());
	}

	@Override
	public void onPacketReceive(PacketEvent event) {
		if (event.getPacket() instanceof ClientboundMoveVehiclePacket) mc.execute(this::onVehicleSetback);
	}

	@Override
	public void onPacketSend(PacketEvent event) {
		if (resending || !antiKick.get() || !(event.getPacket() instanceof ServerboundMoveVehiclePacket packet)) return;
		if (sendingStops) {
			lastPacketY = pos(packet).y;
			return;
		}
		Vec3 dipTo = kickPacket(packet);
		if (dipTo == null) return;
		event.cancel();
		if (dipTo == Vec3.ZERO) return;
		ClientPacketListener connection = mc.getConnection();
		if (connection == null) return;
		resending = true;
		try {
			connection.send(movePacket(dipTo, yRot(packet), xRot(packet), packet.onGround()));
		} finally {
			resending = false;
		}
	}

	private void onVehicleSetback() {
		forgetKick();
		if (noclipBoat != null) serverPos = noclipBoat.position();
		cautionCap = 0.5;
	}

	private void releaseBoat() {
		if (noclipBoat != null) noclipBoat.noPhysics = false;
		noclipBoat = null;
		serverPos = null;
		lastBase = null;
		sinceBase = 0;
		burst = MOUNT_BURST;
	}

	public Vec3 boatMovement(AbstractBoat boat, LocalPlayer player) {
		if (noclipBoat != boat) {
			releaseBoat();
			noclipBoat = boat;
		}
		boat.noPhysics = true;
		ClientPacketListener connection = mc.getConnection();
		// Paddle changes make the server send the boat's position to everyone (and us) right away
		if (connection != null) connection.send(new ServerboundPaddleBoatPacket(!boat.getPaddleState(0), boat.getPaddleState(1)));
		double budget = serverBudget(boat);
		if (antiKick.get() && kickHolding()) return Vec3.ZERO;
		budget = Math.min(budget, cautionCap);
		cautionCap *= CAUTION_GROWTH;
		double bps = isInsideBlock(boat, player) ? speedInside.get() : speed.get();
		Vec3 horizontal = VehicleControl.horizontalVelocity(player, bps);
		double y = 0;
		boolean noScreen = Mc.screen() == null;
		if (noScreen && mc.options.keyJump.isDown()) y += verticalSpeed.get() / 20;
		if (noScreen && mc.options.keySprint.isDown()) y -= verticalSpeed.get() / 20;
		else y -= fallSpeed.get() / 20;
		Vec3 move = new Vec3(horizontal.x, y, horizontal.z);
		double length = move.length();
		if (length > budget) move = move.scale(budget / length);
		Vec3 planned = planMove(boat, move);
		lastMoveLength = planned.length();
		return planned;
	}

	private double serverBudget(AbstractBoat boat) {
		Vec3 base = boat.getPositionCodec().getBase();
		if (!base.equals(lastBase)) {
			burst = Math.max(burst, sinceBase + 1);
			sinceBase = 0;
			lastBase = base;
			serverPos = base;
			staleTicks = 0;
		} else {
			if (lastMoveLength > 0.01) sinceBase++;
			if (++staleTicks >= STALE_SERVER_TICKS) serverPos = boat.position();
		}
		burst = Math.max(MIN_BURST, burst - BURST_RECOVERY);
		if (serverPos == null) serverPos = boat.position();
		double lead = boat.position().distanceTo(serverPos);
		return Math.max(0, Math.min(MAX_SERVER_LEAD / burst, MAX_UNCONFIRMED - lead));
	}

	private Vec3 planMove(AbstractBoat boat, Vec3 move) {
		Vec3 start = boat.position(), pos = start, left = move;
		List<AABB> lava = lavaProtection.get() ? nearbyLava(boat, move) : List.of();
		List<Vec3> stops = new ArrayList<>();
		while (stops.size() < MAX_PACKETS_PER_TICK && left.lengthSqr() > 1.0E-8) {
			Vec3 hop = left.scale(Math.min(1, MAX_PACKET_LENGTH / left.length()));
			double fraction = allowedFraction(boat, pos, hop);
			if (fraction <= 0) break;
			Vec3 step = hop.scale(fraction);
			if (reachesUnloadedChunk(boat, pos, step) || !lava.isEmpty() && approachesLava(boat, pos, step, lava)) break;
			pos = pos.add(step);
			left = left.subtract(step);
			stops.add(pos);
		}
		ClientPacketListener connection = mc.getConnection();
		if (connection != null) {
			sendingStops = true;
			try {
				for (int i = 0; i < stops.size() - 1; i++) connection.send(movePacket(stops.get(i), boat.getYRot(), boat.getXRot(), false));
			} finally {
				sendingStops = false;
			}
		}
		return pos.subtract(start);
	}

	private static List<AABB> nearbyLava(AbstractBoat boat, Vec3 move) {
		AABB area = boat.getBoundingBox().expandTowards(move).inflate(4);
		List<AABB> lava = new ArrayList<>();
		for (BlockPos pos : BlockPos.betweenClosed(Mth.floor(area.minX), Mth.floor(area.minY), Mth.floor(area.minZ), Mth.floor(area.maxX), Mth.floor(area.maxY), Mth.floor(area.maxZ)))
			if (boat.level().getFluidState(pos).is(FluidTags.LAVA)) lava.add(new AABB(pos));
		return lava;
	}

	private static boolean approachesLava(AbstractBoat boat, Vec3 from, Vec3 step, List<AABB> lava) {
		AABB box = boat.getBoundingBox().move(from.subtract(boat.position()));
		double after = distanceTo(box.move(step), lava);
		return after < LAVA_MARGIN && after < distanceTo(box, lava);
	}

	private static double distanceTo(AABB box, List<AABB> blocks) {
		double best = Double.MAX_VALUE;
		for (AABB block : blocks) {
			double dx = Math.max(0, Math.max(block.minX - box.maxX, box.minX - block.maxX));
			double dy = Math.max(0, Math.max(block.minY - box.maxY, box.minY - block.maxY));
			double dz = Math.max(0, Math.max(block.minZ - box.maxZ, box.minZ - block.maxZ));
			best = Math.min(best, dx * dx + dy * dy + dz * dz);
		}
		return Math.sqrt(best);
	}

	private static boolean reachesUnloadedChunk(AbstractBoat boat, Vec3 from, Vec3 step) {
		AABB box = boat.getBoundingBox().move(from.subtract(boat.position())).expandTowards(step);
		for (int x = SectionPos.blockToSectionCoord(box.minX); x <= SectionPos.blockToSectionCoord(box.maxX); x++)
			for (int z = SectionPos.blockToSectionCoord(box.minZ); z <= SectionPos.blockToSectionCoord(box.maxZ); z++)
				if (!boat.level().getChunkSource().hasChunk(x, z)) return true;
		return false;
	}

	/** How much of this hop the server would replay without the boat ending up somewhere else. */
	private static double allowedFraction(AbstractBoat boat, Vec3 from, Vec3 move) {
		AABB box = boat.getBoundingBox().move(from.subtract(boat.position()));
		List<VoxelShape> entities = serverEntityShapes(boat, box.expandTowards(move));
		if (replayMiss(boat, box, move, entities) <= MAX_MISS) return 1;
		double lo = 0, hi = 1;
		for (int i = 0; i < 12; i++) {
			double mid = (lo + hi) / 2;
			if (replayMiss(boat, box, move.scale(mid), entities) <= MAX_MISS) lo = mid;
			else hi = mid;
		}
		return lo;
	}

	private static double replayMiss(AbstractBoat boat, AABB box, Vec3 move, List<VoxelShape> entities) {
		Vec3 replay = Entity.collideBoundingBox(boat, move, box, boat.level(), entities);
		return Math.hypot(move.x - replay.x, move.z - replay.z);
	}

	private static List<VoxelShape> serverEntityShapes(AbstractBoat boat, AABB area) {
		List<VoxelShape> shapes = new ArrayList<>();
		for (Entity other : boat.level().getEntities(boat, area.inflate(1.0E-7), EntitySelector.NO_SPECTATORS))
			if (AbstractBoat.canVehicleCollide(boat, other)) shapes.add(Shapes.create(other.getBoundingBox()));
		return shapes;
	}

	public static boolean hidesWallOverlay(Player player) {
		return active() != null && player.getVehicle() instanceof AbstractBoat;
	}

	private static boolean isInsideBlock(AbstractBoat boat, LocalPlayer player) {
		ClientLevel level = mc.level;
		if (level == null) return false;
		AABB boatBox = boat.getBoundingBox();
		AABB box = boatBox.contract(0, 0.05, 0).inflate(0.5, 0, 0.5);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int x = Mth.floor(box.minX); x <= Mth.floor(box.maxX); x++)
			for (int y = Mth.floor(box.minY); y <= Mth.floor(box.maxY); y++)
				for (int z = Mth.floor(box.minZ); z <= Mth.floor(box.maxZ); z++) {
					BlockState state = level.getBlockState(pos.set(x, y, z));
					if (state.isAir()) continue;
					VoxelShape shape = state.getCollisionShape(level, pos, CollisionContext.empty());
					if (!shape.isEmpty() && boatBox.intersects(shape.bounds().move(pos))) return true;
				}
		for (Entity other : level.getEntities(boat, box))
			if (other != player && !other.hasPassenger(boat) && !boat.hasPassenger(other) && other.isAlive() && other.getBoundingBox().intersects(box)) return true;
		return false;
	}

	// Anti fly kick: every so often the next vehicle packet dips a little, like falling

	private void tickKick() {
		Entity vehicle = mc.player != null ? mc.player.getVehicle() : null;
		trackVehicle(vehicle);
		if (dipped && System.nanoTime() - dipNanos >= KICK_HOLD_NANOS) {
			ClientPacketListener connection = mc.getConnection();
			if (antiKick.get() && vehicle != null && connection != null && lastPacketY != Double.MAX_VALUE) {
				Vec3 pos = vehicle.position();
				resending = true;
				try {
					connection.send(movePacket(new Vec3(pos.x, lastPacketY, pos.z), vehicle.getYRot(), vehicle.getXRot(), vehicle.onGround()));
				} finally {
					resending = false;
				}
			}
			dipped = false;
		}
		if (delayLeft > 0) delayLeft--;
	}

	/** null: send as is; ZERO: drop it; otherwise send it at this position instead. */
	private Vec3 kickPacket(ServerboundMoveVehiclePacket packet) {
		if (!mc.isSameThread()) return null;
		Entity vehicle = mc.player != null ? mc.player.getVehicle() : null;
		if (vehicle == null) return null;
		trackVehicle(vehicle);
		Vec3 pos = pos(packet);
		double y = pos.y;
		if (kickHolding()) {
			lastPacketY = y;
			return Vec3.ZERO;
		}
		Vec3 result = null;
		boolean flyingDown = y >= lastPacketY || lastPacketY - y < KICK_DIP;
		if (delayLeft <= 0 && !dipped && lastPacketY != Double.MAX_VALUE && flyingDown && VehicleControl.isOnAir(vehicle) && !vehicle.isFlyingVehicle()) {
			result = new Vec3(pos.x, lastPacketY - KICK_DIP, pos.z);
			dipped = true;
			dipNanos = System.nanoTime();
			delayLeft = delay.get().intValue();
		}
		lastPacketY = y;
		return result;
	}

	private boolean kickHolding() {
		return dipped && System.nanoTime() - dipNanos < KICK_HOLD_NANOS;
	}

	private void forgetKick() {
		lastPacketY = Double.MAX_VALUE;
		dipped = false;
	}

	private void trackVehicle(Entity vehicle) {
		if (vehicle != trackedVehicle) {
			trackedVehicle = vehicle;
			forgetKick();
		}
	}

	private static ServerboundMoveVehiclePacket movePacket(Vec3 pos, float yRot, float xRot, boolean onGround) {
		//? if >=26.3 {
		/*return new ServerboundMoveVehiclePacket(net.minecraft.core.PositionAndRotation.of(pos, yRot, xRot), onGround);
		*///?} else {
		return new ServerboundMoveVehiclePacket(pos, yRot, xRot, onGround);
		//?}
	}

	private static Vec3 pos(ServerboundMoveVehiclePacket packet) {
		//? if >=26.3 {
		/*return packet.movingTo().position();
		*///?} else {
		return packet.position();
		//?}
	}

	private static float yRot(ServerboundMoveVehiclePacket packet) {
		//? if >=26.3 {
		/*return packet.movingTo().yRot();
		*///?} else {
		return packet.yRot();
		//?}
	}

	private static float xRot(ServerboundMoveVehiclePacket packet) {
		//? if >=26.3 {
		/*return packet.movingTo().xRot();
		*///?} else {
		return packet.xRot();
		//?}
	}
}
