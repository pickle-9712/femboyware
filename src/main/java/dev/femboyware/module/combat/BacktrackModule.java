package dev.femboyware.module.combat;

import dev.femboyware.FemboyWare;
import dev.femboyware.event.PacketEvent;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.RenderUtil;
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
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Backtrack with a lag switch. While someone you're fighting is in range, everything the server
 * sends is held back for a moment and played out in order a little late, so you see (and Aura
 * swings at) them where they were a moment ago: a step closer when they're backing off or strafing.
 * The lag switch holds your own movement too, so they see you frozen where you were, then sends it
 * all just before each hit lands, so the hit counts from where you really are.
 */
public class BacktrackModule extends Module {
	private static volatile BacktrackModule instance;

	private final NumberSetting delay = add(new NumberSetting("Delay", "How far back to see them, in milliseconds", 220, 30, 1000, 10));
	private final NumberSetting range = add(new NumberSetting("Range", "Only backtrack while they're this close", 6, 3, 10, 0.1));
	private final BoolSetting playersOnly = add(new BoolSetting("Players Only", "Only backtrack players, not mobs", true));
	private final BoolSetting lagSwitch = add(new BoolSetting("Lag Switch", "Freeze your own movement server-side while fighting", true));
	private final NumberSetting lagTime = add(new NumberSetting("Lag Time", "Longest to hold your movement, in milliseconds", 350, 50, 1500, 10)).visibleWhen(lagSwitch::get);
	private final BoolSetting flushOnHit = add(new BoolSetting("Flush On Hit", "Send your held movement just before every hit", true)).visibleWhen(lagSwitch::get);
	private final BoolSetting render = add(new BoolSetting("Render", "Box who you're backtracking", true));
	private final ColorSetting color = add(new ColorSetting("Color", "Box colour", 0x40B98AFF)).visibleWhen(render::get);

	private record Held(Packet<?> packet, long time) {
	}

	/** Filled on the network thread, played out on the game thread, oldest first. */
	private final ConcurrentLinkedQueue<Held> inbound = new ConcurrentLinkedQueue<>();
	private volatile boolean active;
	/** Something like a disconnect or a world change is queued: play everything out now. */
	private volatile boolean drainAll;

	private final List<Packet<?>> outbound = new ArrayList<>();
	private long outboundSince;
	private boolean flushing;

	private LivingEntity target;
	private LivingEntity lastHit;
	private long lastHitTime;

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
	}

	@Override
	protected void onDisable() {
		active = false;
		release(true);
		flushOutbound();
		target = null;
		lastHit = null;
	}

	/**
	 * Called on the network thread for every packet from the server, after other modules have seen
	 * it. True means it's been held and will be handled later.
	 */
	public static boolean hold(Packet<?> packet) {
		BacktrackModule module = instance;
		if (module == null) return false;
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
			return;
		}
		target = pickTarget();
		boolean was = active;
		active = target != null && !drainAll && mc.player.isAlive();
		release(false);
		if (!active && was) {
			release(true);
			flushOutbound();
		}
		if (!outbound.isEmpty() && System.currentTimeMillis() - outboundSince > lagTime.get()) flushOutbound();
	}

	private LivingEntity pickTarget() {
		LivingEntity aura = FemboyWare.getModules().get(AuraModule.class).getTarget();
		LivingEntity legit = FemboyWare.getModules().get(LegitAuraModule.class).getTarget();
		LivingEntity recent = System.currentTimeMillis() - lastHitTime < 3000 ? lastHit : null;
		for (LivingEntity candidate : new LivingEntity[]{aura, legit, recent}) {
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
		if (lagSwitch.get() && flushOnHit.get()) flushOutbound();
	}

	@Override
	public void onPacketSend(PacketEvent event) {
		if (flushing || !lagSwitch.get() || !active) return;
		Packet<?> packet = event.getPacket();
		if (packet instanceof ServerboundKeepAlivePacket || packet instanceof ServerboundPongPacket) return;
		if (!(packet instanceof ServerboundMovePlayerPacket)) return;
		if (outbound.isEmpty()) outboundSince = System.currentTimeMillis();
		event.cancel();
		outbound.add(packet);
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		// Played out every frame as well as every tick, so the delay stays smooth
		release(false);
		if (render.get() && active && target != null) {
			RenderUtil.drawBox(event, target.getBoundingBox(), color.get(), color.withAlpha(255), 1.5f);
		}
	}

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
		long cutoff = System.currentTimeMillis() - (long) delay.get().doubleValue();
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

	private void flushOutbound() {
		if (outbound.isEmpty()) return;
		if (mc.getConnection() == null) {
			outbound.clear();
			return;
		}
		flushing = true;
		try {
			for (Packet<?> packet : outbound) mc.getConnection().send(packet);
		} finally {
			flushing = false;
			outbound.clear();
		}
	}
}
