package dev.femboyware.module.client;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.ColorUtil;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.Comparator;
import java.util.List;

public class HudModule extends Module {
	public enum ColorMode { PASTEL, RAINBOW, STATIC }

	private final BoolSetting watermark = add(new BoolSetting("Watermark", "Client name in the top left", true));
	private final BoolSetting arrayList = add(new BoolSetting("Array List", "Enabled modules in the top right", true));
	private final BoolSetting coords = add(new BoolSetting("Coords", "Coordinates in the bottom left", true));
	private final EnumSetting<ColorMode> colorMode = add(new EnumSetting<>("Color Mode", "How HUD text is coloured", ColorMode.PASTEL));
	private final ColorSetting color = add(new ColorSetting("Color", "Static HUD colour", 0xFFFF77C8)).visibleWhen(() -> colorMode.is(ColorMode.STATIC));
	private final NumberSetting speed = add(new NumberSetting("Speed", "Colour animation speed", 2, 0.1, 10, 0.1)).visibleWhen(() -> !colorMode.is(ColorMode.STATIC));

	public HudModule() {
		super("HUD", "On-screen overlay", Category.MISC);
		setEnabled(true);
	}

	@Override
	public void onRender2D(DrawContext context, float tickDelta) {
		if (mc.options.hudHidden || mc.getDebugHud().shouldShowDebugHud()) return;
		TextRenderer font = mc.textRenderer;

		if (watermark.get()) {
			String text = FemboyWare.NAME + " v" + FemboyWare.VERSION;
			int x = 4;
			for (int i = 0; i < text.length(); i++) {
				String character = String.valueOf(text.charAt(i));
				context.drawTextWithShadow(font, character, x, 4, colorAt(i));
				x += font.getWidth(character);
			}
		}

		if (arrayList.get()) {
			List<Module> active = FemboyWare.getModules().getModules().stream()
				.filter(module -> module.isEnabled() && module.getCategory() != Category.MISC)
				.sorted(Comparator.comparingInt((Module module) -> font.getWidth(label(module))).reversed())
				.toList();
			int screenWidth = context.getScaledWindowWidth();
			int y = 4;
			for (int i = 0; i < active.size(); i++) {
				Module module = active.get(i);
				String name = module.getName();
				String info = module.getInfo();
				int width = font.getWidth(label(module));
				int x = screenWidth - width - 4;
				context.fill(x - 2, y - 1, screenWidth - 2, y + font.fontHeight, 0x70000000);
				context.fill(screenWidth - 2, y - 1, screenWidth - 1, y + font.fontHeight, colorAt(i));
				context.drawTextWithShadow(font, name, x, y, colorAt(i));
				if (info != null) context.drawTextWithShadow(font, " " + info, x + font.getWidth(name), y, 0xFFAAAAAA);
				y += font.fontHeight + 1;
			}
		}

		if (coords.get()) {
			String text = String.format("XYZ %.1f %.1f %.1f", mc.player.getX(), mc.player.getY(), mc.player.getZ());
			int y = context.getScaledWindowHeight() - font.fontHeight - 4;
			if (mc.currentScreen != null) y -= 12;
			context.drawTextWithShadow(font, text, 4, y, 0xFFFFFFFF);
		}
	}

	private static String label(Module module) {
		String info = module.getInfo();
		return info == null ? module.getName() : module.getName() + " " + info;
	}

	public int colorAt(int index) {
		return switch (colorMode.get()) {
			case PASTEL -> ColorUtil.pastel(index, speed.getFloat());
			case RAINBOW -> ColorUtil.rainbow(index, speed.getFloat());
			case STATIC -> color.get() | 0xFF000000;
		};
	}
}
