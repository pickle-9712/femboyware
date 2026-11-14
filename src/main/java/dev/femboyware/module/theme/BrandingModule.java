package dev.femboyware.module.theme;

import dev.femboyware.FemboyWare;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.StringSetting;

/**
 * What the client calls itself everywhere it shows a name: the main menu, the ClickGUI, the
 * watermark, notifications and chat messages. Turn it off for the defaults.
 */
public class BrandingModule extends Module {
	public static final String DEFAULT_TAGLINE = "pink utility client";
	public static final String DEFAULT_CREDIT = "made by yum";

	private final StringSetting name = add(new StringSetting("Client Name", "Shown on the main menu, ClickGUI, watermark, notifications and chat", FemboyWare.NAME));
	private final StringSetting tagline = add(new StringSetting("Tagline", "The line under the name on the main menu", DEFAULT_TAGLINE));
	private final StringSetting credit = add(new StringSetting("Credit", "Bottom right of the main menu; empty hides it", DEFAULT_CREDIT));
	private final BoolSetting version = add(new BoolSetting("Show Version", "Version number next to the name in the watermark, ClickGUI and main menu", true));

	public BrandingModule() {
		super("Branding", "The client's name, tagline and credit everywhere they show", Category.THEME);
		setEnabled(true);
	}

	private static BrandingModule active() {
		if (FemboyWare.getModules() == null) return null;
		try {
			BrandingModule module = FemboyWare.getModules().get(BrandingModule.class);
			return module.isEnabled() ? module : null;
		} catch (IllegalArgumentException notYet) {
			return null;
		}
	}

	/** Sets the client name, switching Branding on so it shows. */
	public static void rename(String newName) {
		if (FemboyWare.getModules() == null) return;
		BrandingModule module = FemboyWare.getModules().get(BrandingModule.class);
		module.name.set(newName);
		if (!module.isEnabled()) module.setEnabled(true);
	}

	public static String name() {
		BrandingModule module = active();
		String value = module == null ? "" : module.name.get().trim();
		return value.isEmpty() ? FemboyWare.NAME : value;
	}

	public static String tagline() {
		BrandingModule module = active();
		return module == null ? DEFAULT_TAGLINE : module.tagline.get();
	}

	public static String credit() {
		BrandingModule module = active();
		return module == null ? DEFAULT_CREDIT : module.credit.get();
	}

	public static boolean showVersion() {
		BrandingModule module = active();
		return module == null || module.version.get();
	}

	/** The name with " v1.2.3" after it when the version is shown. */
	public static String nameWithVersion() {
		return showVersion() ? name() + " v" + FemboyWare.VERSION : name();
	}
}
