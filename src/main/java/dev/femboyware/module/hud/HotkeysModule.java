package dev.femboyware.module.hud;

import com.mojang.blaze3d.platform.InputConstants;
import dev.femboyware.FemboyWare;
import dev.femboyware.gui.Glass;
import dev.femboyware.gui.Theme;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Lists the modules you've bound to a key, with the key, lit up while they're on. Ported from the
 * Anubis client's Hotkeys (GPL-3.0, by 4ldenz, https://github.com/4ldenz/Anubis-client).
 */
public class HotkeysModule extends HudElement {
	private final BoolSetting background = add(new BoolSetting("Background", "A glass box behind it", true));

	public HotkeysModule() {
		super("Hotkeys", "The modules you have bound to a key, and their keys", 1, 60);
	}

	private record Line(String name, String key, boolean on) {
	}

	private List<Line> lines(boolean editing) {
		List<Line> out = new ArrayList<>();
		for (Module module : FemboyWare.getModules().getModules()) {
			int key = module.getKey();
			if (key < 0 || key == 0) continue;
			String label = InputConstants.Type.KEYSYM.getOrCreate(key).getDisplayName().getString().toUpperCase(Locale.ROOT);
			out.add(new Line(module.getName(), label, module.isEnabled()));
		}
		if (out.isEmpty() && editing) out.add(new Line("No keys bound", "", false));
		return out;
	}

	@Override
	public int width(boolean editing) {
		int nameW = mc.font.width("Hotkeys"), keyW = 0;
		for (Line l : lines(editing)) {
			nameW = Math.max(nameW, mc.font.width(l.name));
			keyW = Math.max(keyW, mc.font.width(l.key));
		}
		return nameW + (keyW > 0 ? keyW + 14 : 0) + (background.get() ? 12 : 0);
	}

	@Override
	public int height(boolean editing) {
		return (lines(editing).size() + 1) * 11 + (background.get() ? 8 : 0);
	}

	@Override
	protected void draw(GuiGraphics context, int left, int top, boolean editing) {
		List<Line> lines = lines(editing);
		int w = width(editing);
		if (background.get()) Glass.draw(context, left, top, left + w, top + height(editing), 4, backgroundColor(Theme.list()), false);
		int pad = background.get() ? 6 : 0, y = top + (background.get() ? 4 : 0);
		context.drawString(mc.font, "Hotkeys", left + pad, y, accentColor());
		y += 11;
		for (Line l : lines) {
			context.drawString(mc.font, l.name, left + pad, y + 1, l.on ? textColor() : mutedColor());
			if (!l.key.isEmpty()) {
				int kw = mc.font.width(l.key), kx = left + w - pad - kw;
				Glass.rounded(context, kx - 3, y - 1, kx + kw + 3, y + 10, 2, l.on ? (accentColor() & 0xFFFFFF) | 0x50000000 : 0x30FFFFFF);
				context.drawString(mc.font, l.key, kx, y + 1, l.on ? textColor() : mutedColor());
			}
			y += 11;
		}
	}
}
