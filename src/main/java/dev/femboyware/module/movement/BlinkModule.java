package dev.femboyware.module.movement;

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
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.AABB;

/**
 * Holds back your movement packets so the server sees you frozen, then sends them all at once
 * when disabled, making you appear to teleport.
 */
public class BlinkModule extends Module {
	private final BoolSetting allPackets = add(new BoolSetting("All Packets", "Hold every packet, not just movement", false));
	private final NumberSetting autoFlush = add(new NumberSetting("Auto Flush", "Send everything after this many packets, 0 to never", 0, 0, 400, 10));
	private final BoolSetting render = add(new BoolSetting("Render", "Show where the server thinks you are", true));
	private final ColorSetting color = add(new ColorSetting("Color", "Server position colour", 0x40FF77C8)).visibleWhen(render::get);

	private final List<Packet<?>> queue = new ArrayList<>();
	private AABB serverBox;
	private boolean flushing;

	public BlinkModule() {
		super("Blink", "Freezes you server-side and teleports you on disable", Category.RAGE);
	}

	@Override
	public String getInfo() {
		return String.valueOf(queue.size());
	}

	@Override
	protected void onEnable() {
		queue.clear();
		serverBox = nullCheck() ? null : mc.player.getBoundingBox();
	}

	@Override
	protected void onDisable() {
		flush();
		serverBox = null;
	}

	@Override
	public void onPacketSend(PacketEvent event) {
		if (flushing) return;
		Packet<?> packet = event.getPacket();
		// Keep-alives must keep flowing or the server times us out
		if (packet instanceof ServerboundKeepAlivePacket || packet instanceof ServerboundPongPacket) return;
		if (!allPackets.get() && !(packet instanceof ServerboundMovePlayerPacket)) return;
		event.cancel();
		queue.add(packet);
		if (autoFlush.getInt() > 0 && queue.size() >= autoFlush.getInt()) {
			flush();
			serverBox = mc.player.getBoundingBox();
		}
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (render.get() && serverBox != null) RenderUtil.drawBox(event, serverBox, color.get(), color.withAlpha(255), 1.5f);
	}

	private void flush() {
		if (mc.getConnection() == null) {
			queue.clear();
			return;
		}
		flushing = true;
		for (Packet<?> packet : queue) mc.getConnection().send(packet);
		flushing = false;
		queue.clear();
	}
}
