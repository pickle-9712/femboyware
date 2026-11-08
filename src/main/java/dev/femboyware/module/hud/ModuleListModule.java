package dev.femboyware.module.hud;

import dev.femboyware.FemboyWare;
import dev.femboyware.gui.Glass;
import dev.femboyware.gui.Theme;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Every module you have on, longest first, each in a little glass box. Lines up against whichever
 * side of the screen it's on, and grows upwards when it's near the bottom.
 */
public class ModuleListModule extends HudElement {
	private static final int LINE = 12;

	private final EnumSetting<HudColors> colors = add(new EnumSetting<>("Colors", "How the names are coloured", HudColors.PASTEL));
	private final ColorSetting color = add(new ColorSetting("Color", "Colour for Static", 0xFFFF77C8)).visibleWhen(() -> colors.is(HudColors.STATIC));
	private final NumberSetting speed = add(new NumberSetting("Speed", "How fast the colours move", 2, 0.1, 10, 0.1)).visibleWhen(() -> !colors.is(HudColors.STATIC));
	private final BoolSetting info = add(new BoolSetting("Info", "Show each module's extra info, like its target", true));
	private final BoolSetting boxes = add(new BoolSetting("Boxes", "A glass box behind each name", true));

	public ModuleListModule() {
		super("Module List", "The modules you have on", 99, 1);
		setEnabled(true);
	}

	private List<Module> shown() {
		return FemboyWare.getModules().getModules().stream()
			.filter(module -> module.isEnabled() && module.getCategory() != Category.MISC && module.getCategory() != Category.THEME
				&& module.getCategory() != Category.ELEMENTS)
			.sorted(Comparator.comparingInt((Module module) -> mc.font.width(label(module))).reversed())
			.toList();
	}

	private String label(Module module) {
		String extra = info.get() ? module.getInfo() : null;
		return extra == null ? module.getName() : module.getName() + " " + extra;
	}

	@Override
	public int width(boolean editing) {
		List<Module> modules = shown();
		if (modules.isEmpty()) return editing ? mc.font.width("Module List") + 8 : 0;
		return mc.font.width(label(modules.getFirst())) + 8;
	}

	@Override
	public int height(boolean editing) {
		return Math.max(editing ? 1 : 0, shown().size()) * LINE;
	}

	@Override
	protected void draw(GuiGraphics context, int left, int top, boolean editing) {
		List<Module> modules = shown();
		int width = width(editing);
		if (modules.isEmpty()) {
			if (editing) context.drawString(mc.font, "Module List", left + 4, top + 2, mutedColor());
			return;
		}
		for (int i = 0; i < modules.size(); i++) {
			Module module = modules.get(i);
			String name = module.getName();
			String extra = info.get() ? module.getInfo() : null;
			int textWidth = mc.font.width(label(module));
			// Near the bottom the list grows upwards, so the longest name stays next to the edge
			int y = onBottom() ? top + height(editing) - (i + 1) * LINE : top + i * LINE;
			int x = onRight() ? left + width - textWidth - 4 : left + 4;
			if (boxes.get()) Glass.draw(context, x - 4, y, x + textWidth + 4, y + LINE - 1, 4, backgroundColor(Theme.list()), false);
			int tint = colors.get().at(i, speed.getFloat(), color.get());
			context.drawString(mc.font, name, x, y + 2, tint);
			if (extra != null) context.drawString(mc.font, " " + extra, x + mc.font.width(name), y + 2, mutedColor());
		}
	}
}
