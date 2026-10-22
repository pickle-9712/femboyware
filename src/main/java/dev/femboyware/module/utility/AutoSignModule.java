package dev.femboyware.module.utility;

import dev.femboyware.event.PacketEvent;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.StringSetting;
import net.minecraft.network.packet.c2s.play.UpdateSignC2SPacket;
import net.minecraft.network.packet.s2c.play.SignEditorOpenS2CPacket;

/** Fills in signs you place with preset text, skipping the editor. */
public class AutoSignModule extends Module {
	private final StringSetting line1 = add(new StringSetting("Line 1", "First line", "femboyware"));
	private final StringSetting line2 = add(new StringSetting("Line 2", "Second line", ":3"));
	private final StringSetting line3 = add(new StringSetting("Line 3", "Third line", ""));
	private final StringSetting line4 = add(new StringSetting("Line 4", "Fourth line", ""));

	public AutoSignModule() {
		super("Auto Sign", "Writes preset text on signs", Category.AUTO);
	}

	@Override
	public void onPacketReceive(PacketEvent event) {
		if (!(event.getPacket() instanceof SignEditorOpenS2CPacket packet)) return;
		event.cancel();
		mc.execute(() -> {
			if (mc.getNetworkHandler() == null) return;
			mc.getNetworkHandler().sendPacket(new UpdateSignC2SPacket(packet.getPos(), packet.isFront(), line1.get(), line2.get(), line3.get(), line4.get()));
		});
	}
}
