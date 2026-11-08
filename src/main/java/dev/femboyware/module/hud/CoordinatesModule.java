package dev.femboyware.module.hud;

import dev.femboyware.gui.Glass;
import dev.femboyware.gui.Theme;
import dev.femboyware.setting.BoolSetting;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

/** Where you are: your coordinates, the matching spot in the other dimension, and which way you face. */
public class CoordinatesModule extends HudElement {
	private final BoolSetting otherDimension = add(new BoolSetting("Other Dimension", "The matching Nether or Overworld coordinates", true));
	private final BoolSetting facing = add(new BoolSetting("Facing", "Which way you're looking", true));
	private final BoolSetting background = add(new BoolSetting("Background", "A glass box behind it", false));

	public CoordinatesModule() {
		super("Coordinates", "Your coordinates and facing", 1, 99);
		setEnabled(true);
	}

	private List<String> lines() {
		List<String> lines = new ArrayList<>();
		if (mc.player == null) {
			lines.add("XYZ 0.0 64.0 0.0");
			return lines;
		}
		double x = mc.player.getX(), y = mc.player.getY(), z = mc.player.getZ();
		lines.add(String.format(Locale.ROOT, "XYZ %.1f %.1f %.1f", x, y, z));
		if (otherDimension.get() && mc.level != null) {
			if (mc.level.dimension() == Level.NETHER) lines.add(String.format(Locale.ROOT, "Overworld %.0f %.0f", x * 8, z * 8));
			else if (mc.level.dimension() == Level.OVERWORLD) lines.add(String.format(Locale.ROOT, "Nether %.0f %.0f", x / 8, z / 8));
		}
		if (facing.get()) {
			Direction direction = mc.player.getDirection();
			String axis = switch (direction) {
				case NORTH -> "-Z";
				case SOUTH -> "+Z";
				case WEST -> "-X";
				default -> "+X";
			};
			lines.add("Facing " + direction.getSerializedName().substring(0, 1).toUpperCase(Locale.ROOT) + direction.getSerializedName().substring(1) + " (" + axis + ")");
		}
		return lines;
	}

	@Override
	public int width(boolean editing) {
		int widest = 0;
		for (String line : lines()) widest = Math.max(widest, mc.font.width(line));
		return widest + (background.get() ? 12 : 0);
	}

	@Override
	public int height(boolean editing) {
		return lines().size() * 10 + (background.get() ? 6 : 0);
	}

	@Override
	protected void draw(GuiGraphics context, int left, int top, boolean editing) {
		if (background.get()) Glass.draw(context, left, top, left + width(editing), top + height(editing), 4, backgroundColor(Theme.list()), false);
		int pad = background.get() ? 6 : 0;
		List<String> lines = lines();
		for (int i = 0; i < lines.size(); i++) {
			context.drawString(mc.font, lines.get(i), left + pad, top + (background.get() ? 4 : 0) + i * 10, i == 0 ? textColor() : mutedColor());
		}
	}
}
