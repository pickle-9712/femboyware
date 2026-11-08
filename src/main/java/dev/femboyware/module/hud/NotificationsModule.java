package dev.femboyware.module.hud;

import dev.femboyware.FemboyWare;
import dev.femboyware.gui.Glass;
import dev.femboyware.gui.Theme;
import dev.femboyware.module.Module;
import dev.femboyware.module.client.ClickGuiModule;
import dev.femboyware.setting.NumberSetting;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/** A little glass pop-up whenever a module turns on or off, sliding in and fading away. */
public class NotificationsModule extends HudElement {
	private static final int WIDTH = 120;
	private static final int HEIGHT = 18;
	private static final int GAP = 3;
	/** Sliding in and out takes this long, at each end of its time on screen. */
	private static final long SLIDE = 200;

	private record Toast(String name, boolean on, long shownAt) {}

	private final NumberSetting duration = add(new NumberSetting("Duration", "Seconds each notification stays", 2.5, 1, 10, 0.5));

	private final List<Toast> toasts = new ArrayList<>();

	public NotificationsModule() {
		super("Notifications", "Pops up when modules turn on or off", 99, 80);
		setEnabled(true);
	}

	/** Called whenever any module is switched on or off. */
	public static void onToggle(Module module) {
		NotificationsModule notifications = FemboyWare.getModules().get(NotificationsModule.class);
		if (notifications == null || !notifications.isEnabled() || module instanceof ClickGuiModule || module == notifications) return;
		List<Toast> toasts = notifications.toasts;
		// Toggling the same module again replaces its notification instead of stacking up
		toasts.removeIf(toast -> toast.name.equals(module.getName()));
		toasts.add(new Toast(module.getName(), module.isEnabled(), System.currentTimeMillis()));
		while (toasts.size() > 6) toasts.removeFirst();
	}

	@Override
	public int width(boolean editing) {
		return WIDTH;
	}

	@Override
	public int height(boolean editing) {
		return HEIGHT;
	}

	@Override
	protected void draw(GuiGraphics context, int left, int top, boolean editing) {
		long now = System.currentTimeMillis();
		long life = (long) (duration.get() * 1000);
		toasts.removeIf(toast -> now - toast.shownAt > life);
		List<Toast> shown = toasts.isEmpty() && editing ? List.of(new Toast("Notifications", true, now - SLIDE)) : toasts;
		for (int i = 0; i < shown.size(); i++) {
			Toast toast = shown.get(shown.size() - 1 - i);
			long age = now - toast.shownAt;
			// Slides in from the nearest side of the screen, and back out at the end
			float in = Mth.clamp(age / (float) SLIDE, 0, 1);
			float out = Mth.clamp((life - age) / (float) SLIDE, 0, 1);
			float shown01 = Math.min(in, out);
			float eased = shown01 * shown01 * (3 - 2 * shown01);
			int slide = Math.round((1 - eased) * (WIDTH + 10) * (onRight() ? 1 : -1));
			int y = onBottom() ? top - i * (HEIGHT + GAP) : top + i * (HEIGHT + GAP);
			int x = left + slide;
			Glass.draw(context, x, y, x + WIDTH, y + HEIGHT, 5, backgroundColor(Theme.panel()), true);
			// A coloured dot: the accent when switched on, grey when off
			Glass.rounded(context, x + 6, y + 6, x + 12, y + 12, 3, toast.on ? accentColor() : usesCustomColors() ? mutedColor() : 0xFF7A7280);
			context.drawString(mc.font, toast.name, x + 17, y + 5, textColor());
			String state = toast.on ? "on" : "off";
			context.drawString(mc.font, state, x + WIDTH - 6 - mc.font.width(state), y + 5, toast.on ? accentColor() : mutedColor());
		}
	}
}
