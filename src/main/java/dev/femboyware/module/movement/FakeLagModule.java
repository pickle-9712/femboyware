package dev.femboyware.module.movement;

import dev.femboyware.event.PacketEvent;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.RenderUtil;
import dev.femboyware.util.TargetUtil;
import java.util.ArrayDeque;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.AABB;

/**
 * Sends your movement late: each movement packet is held for a moment and then sent in order, so
 * to everyone else you move a little behind where you are. Unlike Blink nothing piles up.
 */
public class FakeLagModule extends Module {
	private final NumberSetting delay = add(new NumberSetting("Delay", "Milliseconds each movement packet is held", 200, 20, 1000, 10));
	private final BoolSetting onlyFighting = add(new BoolSetting("Only Fighting", "Only lag while a player is near you", true));
	private final NumberSetting range = add(new NumberSetting("Range", "How near a player has to be", 8, 2, 20, 0.5)).visibleWhen(onlyFighting::get);
	private final BoolSetting flushOnHit = add(new BoolSetting("Flush On Hit", "Send everything when you take a hit, so knockback isn't doubled", true));
	private final BoolSetting render = add(new BoolSetting("Render", "Show where the server thinks you are", true));
	private final ColorSetting color = add(new ColorSetting("Color", "Server position colour", 0x40FF77C8)).visibleWhen(render::get);

	private record Held(Packet<?> packet, long time, AABB box) {
	}

	private final ArrayDeque<Held> queue = new ArrayDeque<>();
	private AABB serverBox;
	private boolean sending;
	private int lastHurt;

	public FakeLagModule() {
		super("Fake Lag", "Delay your movement updates so others see you behind", Category.RAGE);
	}

	@Override
	public String getInfo() {
		return delay.getInt() + "ms";
	}

	@Override
	protected void onDisable() {
		flush(Long.MAX_VALUE);
		serverBox = null;
	}

	@Override
	public void onPacketSend(PacketEvent event) {
		if (sending || !(event.getPacket() instanceof ServerboundMovePlayerPacket)) return;
		if (onlyFighting.get() && TargetUtil.closestPlayer(range.get()) == null) {
			// Nobody near: let the backlog out before this one so the order stays right
			if (!queue.isEmpty()) {
				event.cancel();
				queue.add(new Held(event.getPacket(), 0, mc.player.getBoundingBox()));
				flush(Long.MAX_VALUE);
			}
			return;
		}
		event.cancel();
		queue.add(new Held(event.getPacket(), System.currentTimeMillis(), mc.player.getBoundingBox()));
	}

	@Override
	public void onTick() {
		if (flushOnHit.get() && mc.player.hurtTime > lastHurt) flush(Long.MAX_VALUE);
		lastHurt = mc.player.hurtTime;
		flush(System.currentTimeMillis() - delay.getInt());
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (render.get() && serverBox != null && !queue.isEmpty()) RenderUtil.drawBox(event, serverBox, color.get(), color.withAlpha(255), 1.5f);
	}

	/** Sends everything held since before {@code before}. */
	private void flush(long before) {
		if (mc.getConnection() == null) {
			queue.clear();
			return;
		}
		sending = true;
		try {
			while (!queue.isEmpty() && queue.peek().time() <= before) {
				Held held = queue.poll();
				serverBox = held.box();
				mc.getConnection().send(held.packet());
			}
		} finally {
			sending = false;
		}
	}
}
