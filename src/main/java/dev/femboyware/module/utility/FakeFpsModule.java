package dev.femboyware.module.utility;

import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;

/**
 * Shows a higher frame rate than you're really getting, on F3 and every FPS counter: your real FPS
 * multiplied, added to, or a fixed number, wandering a little each second so it looks real.
 */
public class FakeFpsModule extends Module {
	public enum Mode { MULTIPLY, ADD, FIXED }

	private final EnumSetting<Mode> mode = add(new EnumSetting<>("Mode", "Times your real FPS, plus some, or a fixed number", Mode.MULTIPLY));
	private final NumberSetting multiply = add(new NumberSetting("Multiplier", "Times your real FPS", 3, 1.1, 20, 0.1)).visibleWhen(() -> mode.is(Mode.MULTIPLY));
	private final NumberSetting plus = add(new NumberSetting("Add", "Added to your real FPS", 400, 10, 5000, 10)).visibleWhen(() -> mode.is(Mode.ADD));
	private final NumberSetting fixed = add(new NumberSetting("FPS", "The FPS to show", 1000, 30, 10000, 10)).visibleWhen(() -> mode.is(Mode.FIXED));
	private final NumberSetting jitter = add(new NumberSetting("Jitter", "How much it wanders each second, in percent", 4, 0, 20, 0.5));

	private int shown, lastReal = -1;
	private long changedAt;

	public FakeFpsModule() {
		super("Fake FPS", "Show a higher FPS than you're getting", Category.MISC);
	}

	/** From MinecraftClientMixin: the frame rate everything reads. */
	public int adjust(int real) {
		if (!isEnabled()) return real;
		long now = System.currentTimeMillis();
		// Like the real counter, it moves once a second (or as soon as the real one does)
		if (real != lastReal || now - changedAt > 1000) {
			lastReal = real;
			changedAt = now;
			double base = switch (mode.get()) {
				case MULTIPLY -> real * multiply.get();
				case ADD -> real + plus.get();
				case FIXED -> fixed.get();
			};
			double wobble = 1 + (Math.random() * 2 - 1) * jitter.get() / 100;
			shown = (int) Math.max(1, Math.round(base * wobble));
		}
		return shown;
	}
}
