package dev.femboyware.module.render;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

/** Menus animate in as they open: popping up with a little overshoot, sliding up, or zooming in. */
public class GuiAnimationModule extends Module {
	public enum Style { POP, SLIDE, ZOOM, DROP }

	private final EnumSetting<Style> style = add(new EnumSetting<>("Style", "How menus come in", Style.POP));
	private final NumberSetting duration = add(new NumberSetting("Duration", "Seconds the animation takes", 0.28, 0.08, 1, 0.01));
	private final BoolSetting inventories = add(new BoolSetting("Inventories", "Chests and your inventory too", true));

	private static Screen current;
	private static long openedAt;
	private static boolean pushed;

	public GuiAnimationModule() {
		super("GUI Animation", "Menus pop, slide or zoom in as they open", Category.COSMETIC);
	}

	@Override
	public String getInfo() {
		return style.getDisplayName();
	}

	/** From ScreenMixin before a screen draws: starts its animation when it's new and puts the transform on. */
	public static void begin(Screen screen, GuiGraphics graphics) {
		pushed = false;
		var modules = dev.femboyware.FemboyWare.getModules();
		if (modules == null) return;
		GuiAnimationModule self = modules.get(GuiAnimationModule.class);
		if (screen != current) {
			current = screen;
			openedAt = System.nanoTime();
		}
		if (self == null || !self.isEnabled()) return;
		// The ClickGUI has its own open and close animations
		if (screen instanceof dev.femboyware.gui.ClickGuiScreen) return;
		if (!self.inventories.get() && screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) return;
		float t = (float) ((System.nanoTime() - openedAt) / 1e9 / self.duration.get());
		if (t >= 1) return;
		float w = screen.width, h = screen.height;
		float scale = 1, dx = 0, dy = 0;
		switch (self.style.get()) {
			case POP -> scale = 0.82f + 0.18f * backOut(t);
			case SLIDE -> dy = (1 - cubicOut(t)) * h * 0.12f;
			case ZOOM -> scale = 1.12f - 0.12f * cubicOut(t);
			case DROP -> dy = -(1 - bounceOut(t)) * h * 0.15f;
		}
		graphics.pose().pushMatrix();
		graphics.pose().translate(w / 2 + dx, h / 2 + dy);
		graphics.pose().scale(scale, scale);
		graphics.pose().translate(-w / 2, -h / 2);
		pushed = true;
	}

	public static void end(GuiGraphics graphics) {
		if (!pushed) return;
		pushed = false;
		graphics.pose().popMatrix();
	}

	private static float cubicOut(float t) {
		return 1 - (float) Math.pow(1 - t, 3);
	}

	/** Overshoots a touch then settles. */
	private static float backOut(float t) {
		float c = 1.7f;
		float u = t - 1;
		return 1 + (c + 1) * u * u * u + c * u * u;
	}

	private static float bounceOut(float t) {
		if (t < 1 / 2.75f) return 7.5625f * t * t;
		if (t < 2 / 2.75f) { t -= 1.5f / 2.75f; return 7.5625f * t * t + 0.75f; }
		if (t < 2.5f / 2.75f) { t -= 2.25f / 2.75f; return 7.5625f * t * t + 0.9375f; }
		t -= 2.625f / 2.75f;
		return 7.5625f * t * t + 0.984375f;
	}
}
