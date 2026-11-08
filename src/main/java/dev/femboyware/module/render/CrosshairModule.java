package dev.femboyware.module.render;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.HitResult;

/**
 * Your own crosshair in place of Minecraft's: a cross, dot, circle or heart in any colour, with a
 * hit marker when you land a hit and an optional attack charge bar. Drawn in real screen pixels so
 * it stays sharp at any GUI scale.
 */
public class CrosshairModule extends Module {
	public enum Style { CROSS, DOT, CIRCLE, HEART }

	/** How long a hit marker takes to fade. */
	private static final long HIT_MARKER_MILLIS = 350;

	private final EnumSetting<Style> style = add(new EnumSetting<>("Style", "Shape of the crosshair", Style.CROSS));
	private final ColorSetting color = add(new ColorSetting("Color", "Colour of the crosshair", 0xFFFF77C8));
	private final NumberSetting size = add(new NumberSetting("Size", "How big it is, in screen pixels", 9, 2, 30, 1));
	private final NumberSetting gap = add(new NumberSetting("Gap", "Space in the middle of the cross, in pixels", 3, 0, 15, 1)).visibleWhen(() -> style.is(Style.CROSS));
	private final NumberSetting thickness = add(new NumberSetting("Thickness", "Line thickness, in pixels", 2, 1, 6, 1));
	private final BoolSetting outline = add(new BoolSetting("Outline", "A dark edge so it shows on bright backgrounds", true));
	private final BoolSetting hitMarker = add(new BoolSetting("Hit Marker", "Flash a little X when you hit something", true));
	private final BoolSetting charge = add(new BoolSetting("Charge Bar", "A small bar under it while your weapon recharges", true));

	private long lastHit;

	public CrosshairModule() {
		super("Crosshair", "A custom crosshair with hit markers", Category.COSMETIC);
	}

	@Override
	public void onPostAttack(Entity target) {
		lastHit = System.currentTimeMillis();
	}

	/** Called from InGameHudMixin in place of the vanilla crosshair. */
	public void render(GuiGraphics context) {
		if (!mc.options.getCameraType().isFirstPerson()) return;
		if (mc.player.isSpectator() && (mc.hitResult == null || mc.hitResult.getType() != HitResult.Type.ENTITY)) return;

		// Work in real screen pixels from the middle of the screen
		float scale = (float) mc.getWindow().getGuiScale();
		context.pose().pushMatrix();
		context.pose().translate(context.guiWidth() / 2f, context.guiHeight() / 2f);
		context.pose().scale(1 / scale, 1 / scale);

		int s = size.getInt();
		int t = thickness.getInt();
		int tint = color.get() | 0xFF000000;
		if (outline.get()) shape(context, s, t, 0xB0000000, 1);
		shape(context, s, t, tint, 0);

		long sinceHit = System.currentTimeMillis() - lastHit;
		if (hitMarker.get() && sinceHit < HIT_MARKER_MILLIS) {
			int alpha = (int) (255 * (1 - sinceHit / (float) HIT_MARKER_MILLIS));
			int marker = (alpha << 24) | 0xFFFFFF;
			int from = s / 2 + 3;
			int to = from + Math.max(4, s / 2);
			for (int d = from; d <= to; d++) {
				for (int corner = 0; corner < 4; corner++) {
					int dx = corner % 2 == 0 ? d : -d;
					int dy = corner < 2 ? d : -d;
					context.fill(dx - t / 2, dy - t / 2, dx - t / 2 + t, dy - t / 2 + t, marker);
				}
			}
		}

		float progress = mc.player.getAttackStrengthScale(0);
		if (charge.get() && progress < 1) {
			int width = Math.max(16, s * 2);
			int top = s / 2 + 6;
			context.fill(-width / 2, top, width / 2, top + 2, 0x80000000);
			context.fill(-width / 2, top, -width / 2 + Math.round(width * progress), top + 2, tint);
		}
		context.pose().popMatrix();
	}

	/** The crosshair shape, grown by {@code grow} pixels on every side (for the outline). */
	private void shape(GuiGraphics context, int s, int t, int color, int grow) {
		switch (style.get()) {
			case CROSS -> {
				int g = gap.getInt();
				int half = t / 2;
				int arm = s / 2;
				// Four arms around the gap
				context.fill(-arm - grow, -half - grow, -g + grow, -half + t + grow, color);
				context.fill(g - grow, -half - grow, arm + grow, -half + t + grow, color);
				context.fill(-half - grow, -arm - grow, -half + t + grow, -g + grow, color);
				context.fill(-half - grow, g - grow, -half + t + grow, arm + grow, color);
			}
			case DOT -> disc(context, Math.max(1, s / 3) + grow, 0, color);
			case CIRCLE -> disc(context, s / 2 + grow, Math.max(0, s / 2 - t - grow), color);
			case HEART -> heart(context, s + grow * 2, color);
		}
	}

	/** A filled circle of {@code radius}, or a ring when {@code hole} is above zero. */
	private static void disc(GuiGraphics context, int radius, int hole, int color) {
		for (int y = -radius; y <= radius; y++) {
			int outer = (int) Math.round(Math.sqrt(radius * radius - y * y + 0.25));
			if (hole <= 0 || Math.abs(y) >= hole) {
				context.fill(-outer, y, outer + 1, y + 1, color);
				continue;
			}
			int inner = (int) Math.round(Math.sqrt(hole * hole - y * y));
			context.fill(-outer, y, -inner, y + 1, color);
			context.fill(inner + 1, y, outer + 1, y + 1, color);
		}
	}

	/** A small heart about {@code size} pixels across, filled row by row. */
	private static void heart(GuiGraphics context, int size, int color) {
		double half = size / 2.0;
		for (int py = -size / 2; py <= size / 2; py++) {
			int start = Integer.MIN_VALUE;
			for (int px = -size / 2; px <= size / 2 + 1; px++) {
				double x = px / half * 1.25;
				double y = -py / half * 1.25 + 0.25;
				// The classic implicit heart curve
				double a = x * x + y * y - 1;
				boolean inside = a * a * a - x * x * y * y * y <= 0;
				if (inside && start == Integer.MIN_VALUE) start = px;
				if (!inside && start != Integer.MIN_VALUE) {
					context.fill(start, py, px, py + 1, color);
					start = Integer.MIN_VALUE;
				}
			}
		}
	}
}
