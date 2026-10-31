package dev.femboyware.module.hud;

import dev.femboyware.gui.Glass;
import dev.femboyware.gui.Theme;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.util.TickRate;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.PlayerListEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A few numbers worth knowing: frame rate, ping, server TPS, your speed and the server you're on. */
public class InfoModule extends HudElement {
	private final BoolSetting fps = add(new BoolSetting("FPS", "Frames per second", true));
	private final BoolSetting ping = add(new BoolSetting("Ping", "Your ping to the server", true));
	private final BoolSetting tps = add(new BoolSetting("TPS", "How fast the server is ticking", true));
	private final BoolSetting speed = add(new BoolSetting("Speed", "How fast you're moving, in blocks per second", true));
	private final BoolSetting server = add(new BoolSetting("Server", "The server's address", false));
	private final BoolSetting background = add(new BoolSetting("Background", "A glass box behind it", true));

	public InfoModule() {
		super("Info", "FPS, ping, TPS and speed", 99, 99);
	}

	/** Each line as a label and a value. */
	private List<String[]> lines() {
		List<String[]> lines = new ArrayList<>();
		if (fps.get()) lines.add(new String[]{"FPS", Integer.toString(mc.getCurrentFps())});
		if (ping.get()) {
			PlayerListEntry entry = mc.player == null || mc.getNetworkHandler() == null ? null : mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
			lines.add(new String[]{"Ping", (entry == null ? 0 : entry.getLatency()) + "ms"});
		}
		if (tps.get()) lines.add(new String[]{"TPS", String.format(Locale.ROOT, "%.1f", TickRate.getTps())});
		if (speed.get()) {
			double perTick = mc.player == null ? 0 : Math.hypot(mc.player.getX() - mc.player.lastX, mc.player.getZ() - mc.player.lastZ);
			lines.add(new String[]{"Speed", String.format(Locale.ROOT, "%.1f b/s", perTick * 20)});
		}
		if (server.get()) lines.add(new String[]{"Server", mc.getCurrentServerEntry() == null ? "Singleplayer" : mc.getCurrentServerEntry().address});
		return lines;
	}

	@Override
	public int width(boolean editing) {
		int widest = 0;
		for (String[] line : lines()) widest = Math.max(widest, mc.textRenderer.getWidth(line[0] + " " + line[1]));
		return widest == 0 ? 0 : widest + 12;
	}

	@Override
	public int height(boolean editing) {
		return lines().size() * 10 + 6;
	}

	@Override
	protected void draw(DrawContext context, int left, int top, boolean editing) {
		List<String[]> lines = lines();
		if (lines.isEmpty()) return;
		if (background.get()) Glass.draw(context, left, top, left + width(editing), top + height(editing), 4, backgroundColor(Theme.list()), false);
		for (int i = 0; i < lines.size(); i++) {
			String[] line = lines.get(i);
			int y = top + 4 + i * 10;
			// Labels in the accent, values in white, lined up against whichever side we're on
			int labelWidth = mc.textRenderer.getWidth(line[0] + " ");
			int lineWidth = labelWidth + mc.textRenderer.getWidth(line[1]);
			int x = onRight() ? left + width(editing) - 6 - lineWidth : left + 6;
			context.drawTextWithShadow(mc.textRenderer, line[0], x, y, accentColor());
			context.drawTextWithShadow(mc.textRenderer, line[1], x + labelWidth, y, textColor());
		}
	}
}
