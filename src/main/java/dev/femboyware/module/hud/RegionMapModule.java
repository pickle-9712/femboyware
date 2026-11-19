package dev.femboyware.module.hud;

import dev.femboyware.gui.Glass;
import dev.femboyware.gui.Theme;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.DonutGoliaths;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.level.Level;

/**
 * DonutSMP's world map of goliaths on your HUD, coloured by region, with an arrow for where you
 * are and which way you face. Ported from the Anubis client's Region Map (GPL-3.0, by 4ldenz,
 * https://github.com/4ldenz/Anubis-client).
 */
public class RegionMapModule extends HudElement {
	private final NumberSetting cellSize = add(new NumberSetting("Cell Size", "Pixels per map square", 3, 2, 8, 1));
	private final NumberSetting opacity = add(new NumberSetting("Opacity", "How solid the map colours are", 0.75, 0.1, 1, 0.05));
	private final BoolSetting legend = add(new BoolSetting("Legend", "Region names under the map", true));
	private final BoolSetting info = add(new BoolSetting("Goliath Name", "The goliath you're on, over the map", true));
	private final ColorSetting arrow = add(new ColorSetting("Player Indicator", "Colour of your arrow", 0xFFFF3232));

	public RegionMapModule() {
		super("Region Map", "DonutSMP goliath map with where you are", 1, 240);
	}

	private int side() {
		return cellSize.get().intValue() * DonutGoliaths.GRID;
	}

	@Override
	public int width(boolean editing) {
		return side() + 8;
	}

	@Override
	public int height(boolean editing) {
		return side() + 8 + (info.get() ? 11 : 0) + (legend.get() ? 10 * ((DonutGoliaths.REGIONS.length + 1) / 2) + 2 : 0);
	}

	@Override
	protected void draw(GuiGraphics context, int left, int top, boolean editing) {
		Glass.draw(context, left, top, left + width(editing), top + height(editing), 4, backgroundColor(Theme.list()), false);
		int cell = cellSize.get().intValue(), x0 = left + 4, y0 = top + 4;
		DonutGoliaths.Goliath here = null;
		boolean known = mc.player != null && mc.level != null && mc.level.dimension() != Level.END;
		double wx = 0, wz = 0;
		if (known) {
			double scale = mc.level.dimension() == Level.NETHER ? 8 : 1;
			wx = mc.player.getX() * scale;
			wz = mc.player.getZ() * scale;
			here = DonutGoliaths.at(wx, wz);
		}
		if (info.get()) {
			String text = here == null ? "Not on a goliath" : here.label();
			context.drawString(mc.font, text, x0, y0, here == null ? mutedColor() : textColor());
			y0 += 11;
		}
		int alpha = (int) Math.round(opacity.get() * 255) << 24;
		for (int v = 0; v < DonutGoliaths.GRID; v++)
			for (int u = 0; u < DonutGoliaths.GRID; u++) {
				int found = DonutGoliaths.square(u, v);
				if (found < 0) continue;
				int region = found / 1000;
				boolean mine = here != null && found == here.region() * 1000 + here.index();
				int rgb = DonutGoliaths.COLORS[region] & 0xFFFFFF;
				int x = x0 + u * cell, y = y0 + v * cell;
				context.fill(x, y, x + cell, y + cell, (mine ? 0xFF000000 : alpha) | rgb);
				if (DonutGoliaths.square(u + 1, v) != found) context.fill(x + cell - 1, y, x + cell, y + cell, 0x60000000);
				if (DonutGoliaths.square(u, v + 1) != found) context.fill(x, y + cell - 1, x + cell, y + cell, 0x60000000);
			}
		if (known) {
			double px = (wx + DonutGoliaths.HALF) / DonutGoliaths.SQUARE * cell + x0, pz = (wz + DonutGoliaths.HALF) / DonutGoliaths.SQUARE * cell + y0;
			int side = side();
			if (px >= x0 && pz >= y0 && px < x0 + side && pz < y0 + side) drawArrow(context, (float) px, (float) pz, mc.player.getYRot());
		}
		if (legend.get()) {
			int y = y0 + side() + 3, colW = side() / 2;
			for (int i = 0; i < DonutGoliaths.REGIONS.length; i++) {
				int x = x0 + (i % 2) * colW, ly = y + (i / 2) * 10;
				context.fill(x, ly + 1, x + 6, ly + 7, 0xFF000000 | DonutGoliaths.COLORS[i]);
				context.drawString(mc.font, DonutGoliaths.REGIONS[i], x + 9, ly, textColor(), false);
			}
		}
	}

	/** A small arrow pointing the way you face (north is up on the map). */
	private void drawArrow(GuiGraphics context, float x, float y, float yaw) {
		double a = Math.toRadians(yaw);
		float fx = (float) -Math.sin(a), fz = (float) Math.cos(a);
		int color = arrow.get() | 0xFF000000;
		for (int i = 0; i <= 6; i++) {
			int dx = Math.round(x + fx * i), dy = Math.round(y + fz * i);
			context.fill(dx - 1, dy - 1, dx + 1, dy + 1, color);
		}
		context.fill(Math.round(x) - 2, Math.round(y) - 2, Math.round(x) + 2, Math.round(y) + 2, 0xFFFFFFFF);
	}
}
