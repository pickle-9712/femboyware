package dev.femboyware.module.hud;

import dev.femboyware.gui.Glass;
import dev.femboyware.gui.Theme;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.util.TickRate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;

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
		if (fps.get()) lines.add(new String[]{"FPS", Integer.toString(mc.getFps())});
		if (ping.get()) {
			PlayerInfo entry = mc.player == null || mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(mc.player.getUUID());
			lines.add(new String[]{"Ping", (entry == null ? 0 : entry.getLatency()) + "ms"});
		}
		if (tps.get()) lines.add(new String[]{"TPS", String.format(Locale.ROOT, "%.1f", TickRate.getTps())});
		if (speed.get()) {
			double perTick = mc.player == null ? 0 : Math.hypot(mc.player.getX() - mc.player.xo, mc.player.getZ() - mc.player.zo);
			lines.add(new String[]{"Speed", String.format(Locale.ROOT, "%.1f b/s", perTick * 20)});
		}
		if (server.get()) lines.add(new String[]{"Server", mc.getCurrentServer() == null ? "Singleplayer" : mc.getCurrentServer().ip});
		return lines;
	}

	@Override
	public int width(boolean editing) {
		int widest = 0;
		for (String[] line : lines()) widest = Math.max(widest, mc.font.width(line[0] + " " + line[1]));
		return widest == 0 ? 0 : widest + 12;
	}

	@Override
	public int height(boolean editing) {
		return lines().size() * 10 + 6;
	}

	@Override
	protected void draw(GuiGraphics context, int left, int top, boolean editing) {
		List<String[]> lines = lines();
		if (lines.isEmpty()) return;
		if (background.get()) Glass.draw(context, left, top, left + width(editing), top + height(editing), 4, backgroundColor(Theme.list()), false);
		for (int i = 0; i < lines.size(); i++) {
			String[] line = lines.get(i);
			int y = top + 4 + i * 10;
			// Labels in the accent, values in white, lined up against whichever side we're on
			int labelWidth = mc.font.width(line[0] + " ");
			int lineWidth = labelWidth + mc.font.width(line[1]);
			int x = onRight() ? left + width(editing) - 6 - lineWidth : left + 6;
			context.drawString(mc.font, line[0], x, y, accentColor());
			context.drawString(mc.font, line[1], x + labelWidth, y, textColor());
		}
	}
}
