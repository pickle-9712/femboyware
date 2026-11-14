package dev.femboyware.module.render;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.NumberSetting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.joml.Matrix3x2f;

/**
 * Menus lean towards your mouse like a card held in your hand, tilting as you move round them. The
 * tilt pivots on the mouse itself, so whatever's under the pointer stays exactly where it is and
 * clicks still land where you aim.
 */
public class ThreeDUiModule extends Module {
	private final NumberSetting strength = add(new NumberSetting("Strength", "How far menus lean", 1, 0.2, 3, 0.1));
	private final NumberSetting smoothness = add(new NumberSetting("Smoothness", "How gently the tilt follows the mouse", 0.85, 0, 0.97, 0.01));
	private final BoolSetting inventories = add(new BoolSetting("Inventories", "Chests and your inventory too", true));

	private static float shx, shy;
	private static long last;
	private static boolean pushed;

	public ThreeDUiModule() {
		super("3D UI", "Menus tilt towards your mouse like a card", Category.COSMETIC);
	}

	/** From ScreenMixin before a screen draws. */
	public static void begin(Screen screen, GuiGraphics graphics, int mouseX, int mouseY) {
		pushed = false;
		var modules = dev.femboyware.FemboyWare.getModules();
		if (modules == null) return;
		ThreeDUiModule self = modules.get(ThreeDUiModule.class);
		if (self == null || !self.isEnabled() || screen.width <= 0 || screen.height <= 0) return;
		if (!self.inventories.get() && screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) return;
		float w = screen.width, h = screen.height;
		// -1..1 across the screen
		float nx = Math.max(-1, Math.min(1, (mouseX - w / 2) / (w / 2)));
		float ny = Math.max(-1, Math.min(1, (mouseY - h / 2) / (h / 2)));
		float k = 0.035f * self.strength.getFloat();
		float wantX = -ny * nx * k, wantY = nx * k * 0.6f - ny * nx * k * 0.3f;
		long now = System.nanoTime();
		float dt = last == 0 ? 1 : Math.min(0.1f, (now - last) / 1e9f);
		last = now;
		// Eased towards where it wants to be, frame-rate independent
		float keep = (float) Math.pow(self.smoothness.getFloat(), dt * 60);
		shx = shx * keep + wantX * (1 - keep);
		shy = shy * keep + wantY * (1 - keep);
		graphics.pose().pushMatrix();
		graphics.pose().translate(mouseX, mouseY);
		graphics.pose().mul(new Matrix3x2f(1, shy, shx, 1, 0, 0));
		graphics.pose().translate(-mouseX, -mouseY);
		pushed = true;
	}

	public static void end(GuiGraphics graphics) {
		if (!pushed) return;
		pushed = false;
		graphics.pose().popMatrix();
	}
}
