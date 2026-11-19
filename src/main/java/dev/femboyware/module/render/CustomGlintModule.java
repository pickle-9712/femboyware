package dev.femboyware.module.render;

import com.mojang.blaze3d.platform.NativeImage;
import dev.femboyware.FemboyWare;
import dev.femboyware.gui.Theme;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ColorSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import java.io.InputStream;
import java.util.Optional;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.ARGB;

/**
 * Recolours the enchantment glint and changes its speed and pattern size. Ported from the Anubis
 * client's Custom Glint (GPL-3.0, by 4ldenz, https://github.com/4ldenz/Anubis-client); instead of
 * writing into the glint's GPU texture it swaps in a tinted copy, which works on every version.
 */
public class CustomGlintModule extends Module {
	public enum ColorMode { CUSTOM, ACCENT }

	private final EnumSetting<ColorMode> colorMode = add(new EnumSetting<>("Color", "Your own colour or the theme accent", ColorMode.CUSTOM));
	private final ColorSetting itemColor = add(new ColorSetting("Item Color", "Glint colour on items", 0xFF6EA8FE));
	private final BoolSetting separateArmor = add(new BoolSetting("Separate Armor", "A different colour for worn armour", false));
	private final ColorSetting armorColor = add(new ColorSetting("Armor Color", "Glint colour on worn armour", 0xFFC792EA));
	private final NumberSetting intensity = add(new NumberSetting("Intensity", "How bright the glint is", 1, 0.05, 1, 0.05));
	private final NumberSetting speed = add(new NumberSetting("Speed", "How fast the glint moves", 1, 0.1, 4, 0.1));
	private final NumberSetting patternSize = add(new NumberSetting("Pattern Size", "Bigger or smaller glint streaks", 1, 0.25, 4, 0.25));

	private final GlintTexture items = new GlintTexture(Identifier.withDefaultNamespace("textures/misc/enchanted_glint_item.png"));
	private final GlintTexture armor = new GlintTexture(Identifier.withDefaultNamespace("textures/misc/enchanted_glint_armor.png"));
	private double glintClock = Double.NaN;
	private long glintClockReal;

	public CustomGlintModule() {
		super("Custom Glint", "Recolour the enchantment glint, its speed and pattern size", Category.COSMETIC);
	}

	public static CustomGlintModule active() {
		if (FemboyWare.getModules() == null) return null;
		CustomGlintModule module = FemboyWare.getModules().get(CustomGlintModule.class);
		return module != null && module.isEnabled() ? module : null;
	}

	@Override
	protected void onEnable() {
		glintClock = Double.NaN;
	}

	@Override
	protected void onDisable() {
		if (mc.isSameThread()) restore();
		else mc.execute(this::restore);
	}

	@Override
	public void onTick() {
		if (mc.level == null) return;
		int itemRgb = (colorMode.get() == ColorMode.ACCENT ? Theme.accent() : itemColor.get()) & 0xFFFFFF;
		int armorRgb = colorMode.get() == ColorMode.CUSTOM && separateArmor.get() ? armorColor.get() & 0xFFFFFF : itemRgb;
		float strength = intensity.get().floatValue();
		items.tint(itemRgb, strength);
		armor.tint(armorRgb, strength);
	}

	/** The glint's clock, sped up or slowed down. */
	public long glintMillis(long realMillis) {
		if (Double.isNaN(glintClock)) glintClock = realMillis;
		else glintClock += Math.max(0, realMillis - glintClockReal) * speed.get();
		glintClockReal = realMillis;
		return (long) glintClock;
	}

	public float uvScaleMultiplier() {
		return 1.0f / patternSize.get().floatValue();
	}

	private void restore() {
		items.restore();
		armor.restore();
	}

	/** A tinted copy of the glint that samples the same way the original does. */
	private static final class Tinted extends DynamicTexture {
		Tinted(String name, NativeImage image, AbstractTexture original) {
			super(() -> name, image);
			this.sampler = original.getSampler();
		}
	}

	private static final class GlintTexture {
		private final Identifier id;
		private int width, height;
		private int[] original, pattern;
		private Tinted ours;
		private long writtenKey = -1;
		private boolean unusable;

		GlintTexture(Identifier id) {
			this.id = id;
		}

		void tint(int rgb, float intensity) {
			if (unusable) return;
			AbstractTexture current = current();
			if (current == null) return;
			if (current != ours) {
				// First time, or a resource reload put the shipped glint back
				ours = null;
				writtenKey = -1;
				if (!readShipped()) {
					unusable = true;
					return;
				}
			}
			long key = (long) (rgb & 0xFFFFFF) << 8 | Math.round(intensity * 255);
			if (key == writtenKey) return;
			NativeImage image = new NativeImage(width, height, false);
			float r = ARGB.red(rgb) * intensity / 255f, g = ARGB.green(rgb) * intensity / 255f, b = ARGB.blue(rgb) * intensity / 255f;
			for (int y = 0, i = 0; y < height; y++)
				for (int x = 0; x < width; x++, i++) {
					int l = pattern[i];
					image.setPixel(x, y, ARGB.color(ARGB.alpha(original[i]), Math.round(l * r), Math.round(l * g), Math.round(l * b)));
				}
			try {
				Tinted next = new Tinted("femboyware custom glint", image, current);
				mc.getTextureManager().register(id, next);
				ours = next;
				writtenKey = key;
			} catch (RuntimeException e) {
				image.close();
				unusable = true;
			}
		}

		void restore() {
			if (ours != null && current() == ours) mc.getTextureManager().release(id);
			ours = null;
			writtenKey = -1;
			unusable = false;
			original = pattern = null;
		}

		private AbstractTexture current() {
			try {
				return mc.getTextureManager().getTexture(id);
			} catch (IllegalStateException notLoaded) {
				return null;
			}
		}

		private boolean readShipped() {
			Optional<Resource> resource = mc.getResourceManager().getResource(id);
			if (resource.isEmpty()) return false;
			try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
				int w = image.getWidth(), h = image.getHeight();
				int[] pixels = new int[w * h], luma = new int[w * h];
				for (int y = 0, i = 0; y < h; y++)
					for (int x = 0; x < w; x++, i++) {
						int argb = image.getPixel(x, y);
						pixels[i] = argb;
						luma[i] = Math.max(ARGB.red(argb), Math.max(ARGB.green(argb), ARGB.blue(argb)));
					}
				width = w;
				height = h;
				original = pixels;
				pattern = luma;
				return true;
			} catch (Exception e) {
				return false;
			}
		}
	}
}
