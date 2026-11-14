package dev.femboyware.module.combat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.femboyware.FemboyWare;
import dev.femboyware.event.PacketEvent;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.mixin.MoveEntityPacketAccessor;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.FxRender;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.VecDeltaCodec;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Backtrack with a lag switch. While someone you're fighting is in range, everything the server
 * sends is held back for a moment and played out in order a little late, so you see (and hit) them
 * where they were a moment ago: a step closer when they're backing off or strafing. The lag switch
 * holds your own movement too, so they see you frozen where you were, then sends it all just before
 * each hit lands, so the hit counts from where you really are.
 * <p>
 * Works with your own clicks too (Legit): whoever's under your crosshair or you just hit counts, not
 * only Aura's target. Draws glowing white ghosts where the server really has them, and you, tied to
 * where you see them, and a white glow round whoever you're backtracking.
 */
public class BacktrackModule extends Module {
	private static volatile BacktrackModule instance;

	private final NumberSetting delay = add(new NumberSetting("Delay", "How far back to see them, in milliseconds", 220, 30, 1000, 10));
	private final NumberSetting range = add(new NumberSetting("Range", "Only backtrack while they're this close", 6, 3, 10, 0.1));
	private final BoolSetting playersOnly = add(new BoolSetting("Players Only", "Only backtrack players, not mobs", true));
	private final BoolSetting legit = add(new BoolSetting("Legit", "Backtrack whoever's under your crosshair, for fighting with your own clicks", true));
	private final BoolSetting lagSwitch = add(new BoolSetting("Lag Switch", "Freeze your own movement server-side while fighting", true));
	private final NumberSetting lagTime = add(new NumberSetting("Lag Time", "How far behind the server sees you, in milliseconds", 250, 50, 1000, 10)).visibleWhen(lagSwitch::get);
	private final BoolSetting flushOnHit = add(new BoolSetting("Flush On Hit", "Send your held movement just before every hit", true)).visibleWhen(lagSwitch::get);

	private final BoolSetting targetGhost = add(new BoolSetting("Target Ghost", "A glowing ghost where the server really has them", true));
	private final BoolSetting selfGhost = add(new BoolSetting("Self Ghost", "A glowing ghost where the server thinks you are", true));
	private final BoolSetting tether = add(new BoolSetting("Tether", "A line from each ghost to where you see them", true));
	private final BoolSetting highlight = add(new BoolSetting("Highlight", "A white glow all round whoever you're backtracking", true));
	private final BoolSetting throughWalls = add(new BoolSetting("Through Walls", "Draw over terrain", true));
	private final ColorSetting glowColor = add(new ColorSetting("Glow Color", "The ghosts' and highlight's main colour", 0xFFFFFFFF));
	private final ColorSetting rimColor = add(new ColorSetting("Rim Color", "The colour round the ghosts' edges", 0xFFC8A8FF));

	private record Held(Packet<?> packet, long time) {
	}

	/** Filled on the network thread, played out on the game thread, oldest first. */
	private final ConcurrentLinkedQueue<Held> inbound = new ConcurrentLinkedQueue<>();
	private volatile boolean active;
	/** Something like a disconnect or a world change is queued: play everything out now. */
	private volatile boolean drainAll;

	/** Your held movement, oldest first, each with when it was meant to go. */
	private final List<Held> outbound = new ArrayList<>();
	private boolean flushing;
	/**
	 * The delays in use right now, in milliseconds. They ease towards the settings while
	 * backtracking and back to nothing after, rather than jumping, so nobody (them on your screen,
	 * you on theirs) ever teleports: they slow a touch as it builds and speed up a touch to catch up.
	 */
	private double inDelay, outDelay;
	private long lastStep;
	/** The last position the server was actually sent for you. */
	private Vec3 sentPos;

	private LivingEntity target;
	private LivingEntity lastHit;
	private long lastHitTime;
	/** How much the highlight's showing, so it fades in and out. */
	private float shown;
	private long lastFrame;

	public BacktrackModule() {
		super("Backtrack", "See and hit enemies where they were a moment ago, with a lag switch", Category.ATTACK);
		instance = this;
	}

	@Override
	public String getInfo() {
		return active ? inbound.size() + " held" : null;
	}

	@Override
	protected void onEnable() {
		inbound.clear();
		outbound.clear();
		active = false;
		drainAll = false;
		sentPos = null;
		inDelay = outDelay = 0;
		lastStep = 0;
	}

	@Override
	protected void onDisable() {
		active = false;
		release(true);
		flushOutbound();
		target = null;
		lastHit = null;
		shown = 0;
	}

	/**
	 * Called on the network thread for every packet from the server, after other modules have seen
	 * it. True means it's been held and will be handled later.
	 */
	public static boolean hold(net.minecraft.network.Connection connection, Packet<?> packet) {
		BacktrackModule module = instance;
		if (module == null) return false;
		// Only what reaches you: in singleplayer the built-in server's side of the line comes through here too
		ClientPacketListener listener = mc.getConnection();
		if (listener == null || listener.getConnection() != connection) return false;
		ConcurrentLinkedQueue<Held> queue = module.inbound;
		// Once we've started holding, everything after has to queue up behind, or it'd run out of order
		if (!module.isEnabled() || !module.active) {
			if (queue.isEmpty()) return false;
			queue.add(new Held(packet, System.currentTimeMillis()));
			if (!module.isEnabled()) mc.execute(() -> module.release(true));
			return true;
		}
		// Keep-alives answered late would only show as more ping; let them go
		if (packet instanceof ClientboundKeepAlivePacket && queue.isEmpty()) return false;
		if (packet instanceof ClientboundDisconnectPacket || packet instanceof ClientboundStartConfigurationPacket
			|| packet instanceof ClientboundRespawnPacket || packet instanceof ClientboundLoginPacket) {
			if (queue.isEmpty()) return false;
			module.drainAll = true;
			module.active = false;
		}
		queue.add(new Held(packet, System.currentTimeMillis()));
		return true;
	}

	@Override
	public void onTick() {
		if (mc.getConnection() == null || mc.player == null || mc.level == null) {
			inbound.clear();
			outbound.clear();
			active = false;
			sentPos = null;
			return;
		}
		target = pickTarget();
		active = target != null && !drainAll && mc.player.isAlive();
		if (sentPos == null && outbound.isEmpty()) sentPos = mc.player.position();
		step();
	}

	/**
	 * Eases the delays towards where they should be and lets out whatever's waited long enough.
	 * Building up runs at half speed (they look a little slow for a moment), catching up at double.
	 */
	private void step() {
		long now = System.nanoTime();
		double ms = lastStep == 0 ? 0 : Math.min(100, (now - lastStep) / 1e6);
		lastStep = now;
		double inGoal = active ? delay.get() : 0, outGoal = active && lagSwitch.get() ? lagTime.get() : 0;
		inDelay = inDelay < inGoal ? Math.min(inGoal, inDelay + ms * 0.5) : Math.max(inGoal, inDelay - ms);
		outDelay = outDelay < outGoal ? Math.min(outGoal, outDelay + ms * 0.5) : Math.max(outGoal, outDelay - ms);
		release(false);
		sendDue(false);
	}

	private LivingEntity pickTarget() {
		LivingEntity aura = FemboyWare.getModules().get(AuraModule.class).getTarget();
		LivingEntity legitAura = FemboyWare.getModules().get(LegitAuraModule.class).getTarget();
		LivingEntity recent = System.currentTimeMillis() - lastHitTime < 3000 ? lastHit : null;
		LivingEntity aimed = null;
		if (legit.get() && mc.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof LivingEntity living) aimed = living;
		for (LivingEntity candidate : new LivingEntity[]{aura, legitAura, recent, aimed}) {
			if (candidate == null || candidate == mc.player || !candidate.isAlive() || candidate.isRemoved()) continue;
			if (candidate.level() != mc.level) continue;
			if (playersOnly.get() && !(candidate instanceof Player)) continue;
			if (mc.player.distanceTo(candidate) > range.get()) continue;
			return candidate;
		}
		return null;
	}

	@Override
	public void onPreAttack(Entity entity) {
		if (entity instanceof LivingEntity living) {
			lastHit = living;
			lastHitTime = System.currentTimeMillis();
		}
		// Let the server catch up with where you really are, so the hit is from there
		if (lagSwitch.get() && flushOnHit.get()) sendDue(true);
	}

	@Override
	public void onPacketSend(PacketEvent event) {
		Packet<?> packet = event.getPacket();
		// Movement goes out on a steady delay, so the server sees you move normally, just behind
		boolean delaying = outDelay > 1 || !outbound.isEmpty();
		if (flushing || !delaying || !(packet instanceof ServerboundMovePlayerPacket move)) {
			noteSent(packet);
			return;
		}
		event.cancel();
		outbound.add(new Held(move, System.currentTimeMillis()));
	}

	/** Remembers where the server has you, from a movement packet that's really going out. */
	private void noteSent(Packet<?> packet) {
		if (packet instanceof ServerboundKeepAlivePacket || packet instanceof ServerboundPongPacket) return;
		if (packet instanceof ServerboundMovePlayerPacket move && move.hasPosition() && mc.player != null) {
			sentPos = new Vec3(move.getX(mc.player.getX()), move.getY(mc.player.getY()), move.getZ(mc.player.getZ()));
		}
	}

	// ---------------------------------------------------------------- where the server really has them

	/**
	 * Where the server really has this entity: where it was last put, with every movement still being
	 * held played on top. The same as where you see them when nothing's held.
	 */
	private Vec3 serverPos(Entity entity) {
		int id = entity.getId();
		VecDeltaCodec codec = new VecDeltaCodec();
		Vec3 pos = entity.getPositionCodec().getBase();
		codec.setBase(pos);
		for (Held held : inbound) pos = applyMove(held.packet(), id, codec, pos);
		return pos;
	}

	private static Vec3 applyMove(Packet<?> packet, int id, VecDeltaCodec codec, Vec3 pos) {
		if (packet instanceof ClientboundBundlePacket bundle) {
			for (Packet<?> inner : bundle.subPackets()) pos = applyMove(inner, id, codec, pos);
			return pos;
		}
		if (packet instanceof ClientboundMoveEntityPacket move) {
			if (((MoveEntityPacketAccessor) move).femboyware$getEntityId() != id || !move.hasPosition()) return pos;
			//? if >=26.3 {
			/*pos = move.getPositionDelta().decode(codec).endPosition();
			*///?} else {
			pos = codec.decode(move.getXa(), move.getYa(), move.getZa());
			//?}
		} else if (packet instanceof ClientboundTeleportEntityPacket teleport) {
			if (teleport.id() != id) return pos;
			Vec3 to = teleport.change().position();
			pos = new Vec3(teleport.relatives().contains(Relative.X) ? pos.x + to.x : to.x,
				teleport.relatives().contains(Relative.Y) ? pos.y + to.y : to.y,
				teleport.relatives().contains(Relative.Z) ? pos.z + to.z : to.z);
		} else if (packet instanceof ClientboundEntityPositionSyncPacket sync) {
			if (sync.id() != id) return pos;
			//? if >=26.3 {
			/*pos = sync.position().endPosition();
			*///?} else {
			pos = sync.values().position();
			//?}
		} else {
			return pos;
		}
		codec.setBase(pos);
		return pos;
	}

	// ---------------------------------------------------------------- drawing

	private record Ghost(AABB box, Vec3 seen, float alpha) {
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		// Played out every frame as well as every tick, so the delay stays smooth
		if (mc.getConnection() != null) step();
		if (mc.player == null || mc.level == null) return;

		long now = System.nanoTime();
		float delta = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1e9f);
		lastFrame = now;
		shown = Math.max(0, Math.min(1, shown + (active && target != null ? delta : -delta) * 5));

		float tickDelta = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		List<Ghost> ghosts = new ArrayList<>();
		if (targetGhost.get() && active && target != null) {
			Vec3 real = serverPos(target);
			Vec3 seen = target.getPosition(tickDelta);
			if (real.distanceToSqr(seen) > 0.05 * 0.05) ghosts.add(new Ghost(boxAt(target, real), seen.add(0, target.getBbHeight() / 2, 0), shown));
		}
		if (selfGhost.get() && sentPos != null) {
			Vec3 seen = mc.player.getPosition(tickDelta);
			double gap = sentPos.distanceTo(seen);
			if (gap > 0.25) ghosts.add(new Ghost(boxAt(mc.player, sentPos), seen.add(0, mc.player.getBbHeight() / 2, 0), (float) Math.min(1, (gap - 0.25) * 3)));
		}
		boolean showHighlight = highlight.get() && target != null && shown > 0;
		if (ghosts.isEmpty() && !showHighlight) return;

		Vec3 cam = event.camera().pos;
		boolean walls = throughWalls.get();
		double t = (System.nanoTime() % 1_000_000_000_000L) / 1e9;
		PoseStack.Pose pose = event.matrices().last();

		// Pass one: flat light (the ghosts' bodies and the tethers)
		VertexConsumer light = FxRender.quads(event, true, walls);
		for (Ghost ghost : ghosts) {
			ghostBody(light, pose, ghost.box.move(-cam.x, -cam.y, -cam.z), ghost.alpha, t);
			if (tether.get()) tetherLine(light, pose, ghost.box.getCenter().subtract(cam), ghost.seen.subtract(cam), cam, ghost.alpha);
		}
		// Pass two: soft glows (the ghosts' bloom, and the highlight round the target)
		VertexConsumer glow = FxRender.textured(event, FxRender.GLOW, true, walls);
		for (Ghost ghost : ghosts) ghostBloom(event, glow, ghost.box, ghost.alpha, t);
		if (showHighlight) highlight(event, glow, target, tickDelta, t);
	}

	private static AABB boxAt(Entity entity, Vec3 feet) {
		double w = entity.getBbWidth() / 2;
		return new AABB(feet.x - w, feet.y, feet.z - w, feet.x + w, feet.y + entity.getBbHeight(), feet.z + w);
	}

	/**
	 * A glowing pillar the size of their hitbox: a bright core, and a softer shell in the rim colour
	 * round it, brightest at the bottom and fading up. {@code box} is camera-relative.
	 */
	private void ghostBody(VertexConsumer light, PoseStack.Pose pose, AABB box, float alpha, double t) {
		float pulse = 0.85f + 0.15f * (float) Math.sin(t * 6);
		int core = glowColor.get(), rim = rimColor.get();
		box(light, pose, box.deflate(0.06), FxRender.fade(core, 0.55f * alpha * pulse), FxRender.fade(core, 0.2f * alpha * pulse));
		box(light, pose, box, FxRender.fade(FxRender.mix(core, rim, 0.4f), 0.35f * alpha), FxRender.fade(rim, 0.08f * alpha));
		box(light, pose, box.inflate(0.08), FxRender.fade(rim, 0.22f * alpha), FxRender.fade(rim, 0));
	}

	/** The four sides and the top of a box, {@code bottom} colour fading to {@code top} up the sides. */
	private static void box(VertexConsumer c, PoseStack.Pose pose, AABB b, int bottom, int top) {
		float x1 = (float) b.minX, y1 = (float) b.minY, z1 = (float) b.minZ, x2 = (float) b.maxX, y2 = (float) b.maxY, z2 = (float) b.maxZ;
		FxRender.quad(c, pose, x1, y1, z1, bottom, x2, y1, z1, bottom, x2, y2, z1, top, x1, y2, z1, top);
		FxRender.quad(c, pose, x2, y1, z2, bottom, x1, y1, z2, bottom, x1, y2, z2, top, x2, y2, z2, top);
		FxRender.quad(c, pose, x1, y1, z2, bottom, x1, y1, z1, bottom, x1, y2, z1, top, x1, y2, z2, top);
		FxRender.quad(c, pose, x2, y1, z1, bottom, x2, y1, z2, bottom, x2, y2, z2, top, x2, y2, z1, top);
		FxRender.quad(c, pose, x1, y2, z1, top, x2, y2, z1, top, x2, y2, z2, top, x1, y2, z2, top);
		FxRender.quad(c, pose, x1, y1, z1, bottom, x2, y1, z1, bottom, x2, y1, z2, bottom, x1, y1, z2, bottom);
	}

	/** A thin bright line from the ghost to where you see them, as a ribbon turned to face you. */
	private void tetherLine(VertexConsumer c, PoseStack.Pose pose, Vec3 from, Vec3 to, Vec3 cam, float alpha) {
		Vec3 along = to.subtract(from);
		if (along.lengthSqr() < 1e-4) return;
		// Sideways to both the line and the view, so the ribbon's flat face looks at you
		Vec3 side = along.cross(from.add(to).scale(0.5)).normalize().scale(0.025);
		int bright = FxRender.fade(glowColor.get(), 0.9f * alpha), soft = FxRender.fade(rimColor.get(), 0.5f * alpha);
		FxRender.quad(c, pose,
			(float) (from.x - side.x), (float) (from.y - side.y), (float) (from.z - side.z), soft,
			(float) (from.x + side.x), (float) (from.y + side.y), (float) (from.z + side.z), soft,
			(float) (to.x + side.x), (float) (to.y + side.y), (float) (to.z + side.z), bright,
			(float) (to.x - side.x), (float) (to.y - side.y), (float) (to.z - side.z), bright);
	}

	/** Soft bloom up the ghost and round its feet, so it reads as glowing, not just a box. */
	private void ghostBloom(Render3DEvent event, VertexConsumer glow, AABB box, float alpha, double t) {
		Vec3 c = box.getCenter();
		double h = box.getYsize();
		int core = glowColor.get(), rim = rimColor.get();
		for (int i = 0; i < 4; i++) {
			double y = box.minY + h * (0.15 + 0.25 * i);
			float r = (float) (0.75 + 0.1 * Math.sin(t * 4 + i));
			FxRender.glow(event, glow, new Vec3(c.x, y, c.z), r, FxRender.fade(core, 0.4f * alpha));
		}
		FxRender.glow(event, glow, new Vec3(c.x, box.minY + 0.05, c.z), 1.3f, FxRender.fade(rim, 0.35f * alpha));
		FxRender.glow(event, glow, c, 1.8f, FxRender.fade(rim, 0.18f * alpha));
	}

	/** A white glow shell all round them: soft lights over their body, drifting slowly, and a halo. */
	private void highlight(Render3DEvent event, VertexConsumer glow, LivingEntity entity, float tickDelta, double t) {
		Vec3 feet = entity.getPosition(tickDelta);
		float h = entity.getBbHeight(), w = entity.getBbWidth();
		int core = glowColor.get(), rim = rimColor.get();
		int points = 18;
		for (int i = 0; i < points; i++) {
			double a = i * 2.39996 + t * 0.8;
			double y = ((i * 0.618034) % 1) * h;
			double r = w * 0.55;
			Vec3 at = feet.add(Math.cos(a) * r, y, Math.sin(a) * r);
			float flicker = 0.7f + 0.3f * (float) Math.sin(t * 5 + i * 1.7);
			FxRender.glow(event, glow, at, 0.45f, FxRender.fade(core, 0.28f * shown * flicker));
		}
		Vec3 middle = feet.add(0, h / 2, 0);
		FxRender.glow(event, glow, middle, h * 0.9f, FxRender.fade(core, 0.22f * shown));
		FxRender.glow(event, glow, middle, h * 1.4f, FxRender.fade(rim, 0.12f * shown));
	}

	// ---------------------------------------------------------------- holding and letting go

	/** Plays out held packets that have waited long enough, or all of them; on the game thread. */
	@SuppressWarnings("unchecked")
	private void release(boolean all) {
		if (inbound.isEmpty()) {
			drainAll = false;
			return;
		}
		ClientPacketListener connection = mc.getConnection();
		if (connection == null) {
			inbound.clear();
			return;
		}
		long cutoff = System.currentTimeMillis() - (long) inDelay;
		Held held;
		while ((held = inbound.peek()) != null && (all || drainAll || held.time() <= cutoff)) {
			inbound.poll();
			PacketListener listener = connection.getConnection().getPacketListener();
			if (listener == null) continue;
			try {
				((Packet<PacketListener>) held.packet()).handle(listener);
			} catch (Exception e) {
				FemboyWare.LOGGER.warn("Backtrack couldn't play out {}", held.packet().getClass().getSimpleName(), e);
			}
		}
		if (inbound.isEmpty()) drainAll = false;
	}

	/** Sends your held movement that's due, oldest first, or all of it. */
	private void sendDue(boolean all) {
		if (outbound.isEmpty()) return;
		if (mc.getConnection() == null) {
			outbound.clear();
			return;
		}
		long cutoff = System.currentTimeMillis() - (long) outDelay;
		flushing = true;
		try {
			while (!outbound.isEmpty() && (all || outbound.get(0).time() <= cutoff)) mc.getConnection().send(outbound.remove(0).packet());
		} finally {
			flushing = false;
		}
	}

	private void flushOutbound() {
		sendDue(true);
	}
}
