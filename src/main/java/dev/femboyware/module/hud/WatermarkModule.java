package dev.femboyware.module.hud;

import dev.femboyware.FemboyWare;
import dev.femboyware.gui.Glass;
import dev.femboyware.gui.Theme;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import net.minecraft.client.gui.GuiGraphics;

/** The client's name and version, each letter in its own shifting colour. */
public class WatermarkModule extends HudElement {
	private final EnumSetting<HudColors> colors = add(new EnumSetting<>("Colors", "How the letters are coloured", HudColors.PASTEL));
	private final ColorSetting color = add(new ColorSetting("Color", "Colour for Static", 0xFFFF77C8)).visibleWhen(() -> colors.is(HudColors.STATIC));
	private final NumberSetting speed = add(new NumberSetting("Speed", "How fast the colours move", 2, 0.1, 10, 0.1)).visibleWhen(() -> !colors.is(HudColors.STATIC));
	private final BoolSetting background = add(new BoolSetting("Background", "A glass box behind it", false));

	public WatermarkModule() {
		super("Watermark", "The client name in the corner", 1, 1);
		setEnabled(true);
	}

	private String text() {
		return FemboyWare.NAME + " v" + FemboyWare.VERSION;
	}

	@Override
	public int width(boolean editing) {
		return lettersWidth(text()) + (background.get() ? 12 : 0);
	}

	/** Width drawn one letter at a time, as draw does; a touch wider than the whole string at once. */
	private int lettersWidth(String text) {
		int width = 0;
		for (int i = 0; i < text.length(); i++) width += mc.font.width(String.valueOf(text.charAt(i)));
		return width;
	}

	@Override
	public int height(boolean editing) {
		return mc.font.lineHeight + (background.get() ? 8 : 0);
	}

	@Override
	protected void draw(GuiGraphics context, int left, int top, boolean editing) {
		int pad = background.get() ? 6 : 0;
		if (background.get()) Glass.draw(context, left, top, left + width(editing), top + height(editing), 4, backgroundColor(Theme.list()), false);
		String text = text();
		int x = left + pad;
		for (int i = 0; i < text.length(); i++) {
			String letter = String.valueOf(text.charAt(i));
			context.drawString(mc.font, letter, x, top + (background.get() ? 4 : 0), colors.get().at(i, speed.getFloat(), color.get()));
			x += mc.font.width(letter);
		}
	}
}
