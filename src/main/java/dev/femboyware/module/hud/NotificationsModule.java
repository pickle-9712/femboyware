package dev.femboyware.module.hud;

import dev.femboyware.FemboyWare;
import dev.femboyware.gui.Glass;
import dev.femboyware.gui.Theme;
import dev.femboyware.module.Module;
import dev.femboyware.module.client.ClickGuiModule;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.util.ChatUtil;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/**
 * Every pop-up the client gives you in one place: modules turning on or off, and the client's
 * alerts (staff joining, a stash found, "no ender pearl in your hotbar" and the like), shown as
 * glass toasts that slide in and fade away, or sent to chat if you'd rather. What a dot-command
 * says back always stays in chat.
 */
public class NotificationsModule extends HudElement {
	public enum Where { TOASTS, CHAT, BOTH, HIDDEN }

	private static final int WIDTH = 120;
	private static final int MAX_WIDTH = 230;
	private static final int HEIGHT = 18;
	private static final int GAP = 3;
	/** Sliding in and out takes this long, at each end of its time on screen. */
	private static final long SLIDE = 200;

	private enum Kind { ON, OFF, ALERT }

	private record Toast(String title, String text, Kind kind, long shownAt) {}

	private final BoolSetting toggles = add(new BoolSetting("Module Toggles", "A pop-up when a module turns on or off", true));
	private final EnumSetting<Where> alerts = add(new EnumSetting<>("Alerts", "Where the client's alerts go (staff joining, things found, warnings)", Where.TOASTS));
	private final NumberSetting duration = add(new NumberSetting("Duration", "Seconds each notification stays", 2.5, 1, 10, 0.5));
	private final NumberSetting alertDuration = add(new NumberSetting("Alert Duration", "Seconds an alert stays (they've more to read)", 5, 1, 15, 0.5))
		.visibleWhen(() -> alerts.get() == Where.TOASTS || alerts.get() == Where.BOTH);
	private final NumberSetting maxShown = add(new NumberSetting("Max Shown", "How many stack up at once", 6, 1, 12, 1));

	private final List<Toast> toasts = new ArrayList<>();

	public NotificationsModule() {
		super("Notifications", "Module toggles and the client's alerts as pop-ups", 99, 80);
		setEnabled(true);
	}

	/**
	 * Whether notifications are on at all. With this module off, nothing pops up anywhere: no phone
	 * banners or dings (texts, Discord, wires, Forbes, Shopify) and no Ledger pop-ups. Calls still ring.
	 */
	public static boolean on() {
		NotificationsModule self = FemboyWare.getModules() == null ? null : FemboyWare.getModules().get(NotificationsModule.class);
		return self == null || self.isEnabled();
	}

	/** Called whenever any module is switched on or off. */
	public static void onToggle(Module module) {
		NotificationsModule self = FemboyWare.getModules().get(NotificationsModule.class);
		if (self == null || !self.isEnabled() || !self.toggles.get() || module instanceof ClickGuiModule || module == self) return;
		// Toggling the same module again replaces its notification instead of stacking up
		self.toasts.removeIf(toast -> toast.kind != Kind.ALERT && toast.title.equals(module.getName()));
		self.push(new Toast(module.getName(), "", module.isEnabled() ? Kind.ON : Kind.OFF, System.currentTimeMillis()));
	}

	/**
	 * One of the client's alerts, from {@link ChatUtil#info}. True if it's been dealt with here
	 * (shown, or swallowed because this module's off); false means put it in chat.
	 */
	public static boolean alert(String message) {
		var modules = FemboyWare.getModules();
		NotificationsModule self = modules == null ? null : modules.get(NotificationsModule.class);
		if (self == null) return false;
		// Off means off: no toasts and nothing in chat either
		if (!self.isEnabled()) return true;
		Where where = self.alerts.get();
		if (where == Where.CHAT) return false;
		if (where == Where.HIDDEN) return true;
		// "Phase: no ender pearl" reads as a title and a line
		String title = "femboyware", text = message;
		int colon = message.indexOf(": ");
		if (colon > 0 && colon <= 22 && !message.substring(0, colon).contains(" at ")) {
			title = message.substring(0, colon);
			text = message.substring(colon + 2);
		}
		Toast toast = new Toast(title, text, Kind.ALERT, System.currentTimeMillis());
		var mc = net.minecraft.client.Minecraft.getInstance();
		if (mc.isSameThread()) self.push(toast);
		else mc.execute(() -> self.push(toast));
		if (where == Where.BOTH) return false;
		return true;
	}

	private void push(Toast toast) {
		// The same alert again just refreshes it
		toasts.removeIf(t -> t.kind == Kind.ALERT && t.title.equals(toast.title) && t.text.equals(toast.text));
		toasts.add(toast);
		while (toasts.size() > maxShown.getInt()) toasts.removeFirst();
	}

	@Override
	public int width(boolean editing) {
		return WIDTH;
	}

	@Override
	public int height(boolean editing) {
		return HEIGHT;
	}

	private long life(Toast toast) {
		return (long) ((toast.kind == Kind.ALERT ? alertDuration.get() : duration.get()) * 1000);
	}

	/** The alert's text broken over at most two lines that fit {@code width}. */
	private List<String> lines(String text, int width) {
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" ")) {
			String next = line.isEmpty() ? word : line + " " + word;
			if (mc.font.width(next) > width && !line.isEmpty()) {
				lines.add(line.toString());
				line = new StringBuilder(word);
			} else line = new StringBuilder(next);
		}
		if (!line.isEmpty()) lines.add(line.toString());
		if (lines.size() > 2) {
			String second = lines.get(1) + " " + String.join(" ", lines.subList(2, lines.size()));
			while (mc.font.width(second + "...") > width && second.length() > 1) second = second.substring(0, second.length() - 1);
			lines = new ArrayList<>(List.of(lines.get(0), second.stripTrailing() + "..."));
		}
		return lines;
	}

	@Override
	protected void draw(GuiGraphics context, int left, int top, boolean editing) {
		long now = System.currentTimeMillis();
		toasts.removeIf(toast -> now - toast.shownAt > life(toast));
		List<Toast> shown = toasts.isEmpty() && editing ? List.of(new Toast("Notifications", "", Kind.ON, now - SLIDE)) : toasts;
		int offset = 0;
		for (int i = 0; i < shown.size(); i++) {
			Toast toast = shown.get(shown.size() - 1 - i);
			long age = now - toast.shownAt, life = life(toast);
			boolean alert = toast.kind == Kind.ALERT;

			// Alerts grow to fit what they say, up to two lines
			List<String> lines = alert ? lines(toast.text, MAX_WIDTH - 24) : List.of();
			int w = WIDTH, h = HEIGHT;
			if (alert) {
				int textWidth = mc.font.width(toast.title);
				for (String line : lines) textWidth = Math.max(textWidth, mc.font.width(line));
				w = Mth.clamp(textWidth + 24, WIDTH, MAX_WIDTH);
				h = 14 + lines.size() * 10;
			}

			// Slides in from the nearest side of the screen, and back out at the end
			float in = Mth.clamp(age / (float) SLIDE, 0, 1);
			float out = Mth.clamp((life - age) / (float) SLIDE, 0, 1);
			float shown01 = Math.min(in, out);
			float eased = shown01 * shown01 * (3 - 2 * shown01);
			int slide = Math.round((1 - eased) * (w + 10) * (onRight() ? 1 : -1));
			int y = onBottom() ? top + HEIGHT - offset - h : top + offset;
			int x = (onRight() ? left + WIDTH - w : left) + slide;
			offset += h + GAP;

			Glass.draw(context, x, y, x + w, y + h, 5, backgroundColor(Theme.panel()), true);
			int muted = usesCustomColors() ? mutedColor() : 0xFF7A7280;
			if (alert) {
				// The accent bar down the side, the title, then what it says
				Glass.rounded(context, x + 5, y + 5, x + 7, y + h - 5, 1, accentColor());
				context.drawString(mc.font, toast.title, x + 13, y + 4, accentColor());
				for (int l = 0; l < lines.size(); l++) context.drawString(mc.font, lines.get(l), x + 13, y + 14 + l * 10, textColor());
				// How long it's got left, as a thin line along the bottom
				float left01 = 1 - Mth.clamp(age / (float) life, 0, 1);
				context.fill(x + 5, y + h - 2, x + 5 + Math.round((w - 10) * left01), y + h - 1, accentColor() & 0x80FFFFFF);
			} else {
				// A coloured dot: the accent when switched on, grey when off
				boolean on = toast.kind == Kind.ON;
				Glass.rounded(context, x + 6, y + 6, x + 12, y + 12, 3, on ? accentColor() : muted);
				context.drawString(mc.font, toast.title, x + 17, y + 5, textColor());
				String state = on ? "on" : "off";
				context.drawString(mc.font, state, x + w - 6 - mc.font.width(state), y + 5, on ? accentColor() : mutedColor());
			}
		}
	}
}
