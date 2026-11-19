package dev.femboyware.module.theme;

import com.mojang.blaze3d.platform.NativeImage;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.ButtonSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.util.ChatUtil;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * Your own picture, see-through, behind the ClickGUI: in its window under the glass, or over the
 * whole screen. Pick it with Choose Picture, or put a PNG or JPG in .minecraft/femboyware/backgrounds
 * and type its name (a full path works too).
 */
public class GuiBackgroundModule extends Module {
	public enum Fit { COVER, FIT, STRETCH }

	private final StringSetting file = add(new StringSetting("File", "A PNG or JPG: a name in .minecraft/femboyware/backgrounds, or a full path", "gui.png"));
	private final ButtonSetting choose = add(new ButtonSetting("Choose Picture", "Pick a PNG or JPG from your computer", this::chooseFile));
	private final NumberSetting opacity = add(new NumberSetting("Opacity", "How see-through the picture is: low lets the glass and world show through", 0.35, 0.05, 1, 0.05));
	private final EnumSetting<Fit> fit = add(new EnumSetting<>("Fit", "Cover fills it (cropping), Fit shows all of it, Stretch squashes it to fill", Fit.COVER));
	private final BoolSetting wholeScreen = add(new BoolSetting("Whole Screen", "Behind everything over the whole screen, instead of only in the menu's window", false));

	private Identifier texture;
	private int texWidth, texHeight;
	private String loadedFor;
	private int generation;

	public GuiBackgroundModule() {
		super("GUI Background", "Your own see-through PNG or JPG behind the ClickGUI", Category.THEME);
	}

	@Override
	public String getInfo() {
		String name = file.get();
		int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
		return slash >= 0 ? name.substring(slash + 1) : name;
	}

	private static GuiBackgroundModule module() {
		if (FemboyWare.getModules() == null) return null;
		try {
			return FemboyWare.getModules().get(GuiBackgroundModule.class);
		} catch (IllegalArgumentException notYet) {
			return null;
		}
	}

	/** Called by the ClickGUI with the screen's size, behind everything: only with Whole Screen on. */
	public static void drawScreen(GuiGraphics context, int width, int height, float shown) {
		GuiBackgroundModule m = module();
		if (m == null || !m.isEnabled() || !m.wholeScreen.get()) return;
		m.paint(context, 0, 0, width, height, 0, shown);
	}

	/** Called by the ClickGUI for its rounded window, under the glass: only with Whole Screen off. */
	public static void drawWindow(GuiGraphics context, int x1, int y1, int x2, int y2, int radius) {
		GuiBackgroundModule m = module();
		if (m == null || !m.isEnabled() || m.wholeScreen.get()) return;
		m.paint(context, x1, y1, x2, y2, radius, 1);
	}

	private void paint(GuiGraphics context, int x1, int y1, int x2, int y2, int radius, float shown) {
		ensureTexture();
		if (texture == null || texWidth <= 0 || texHeight <= 0) return;
		int w = x2 - x1, h = y2 - y1;
		if (w <= 0 || h <= 0) return;
		int alpha = Math.round((float) (opacity.get() * shown) * 255);
		if (alpha <= 2) return;
		int color = (alpha << 24) | 0xFFFFFF;
		float sx, sy;
		switch (fit.get()) {
			case STRETCH -> {
				sx = w / (float) texWidth;
				sy = h / (float) texHeight;
			}
			case FIT -> {
				// All of it, centred, the spare room left clear
				float s = Math.min(w / (float) texWidth, h / (float) texHeight);
				int dw = Math.round(texWidth * s), dh = Math.round(texHeight * s);
				int ox = x1 + (w - dw) / 2, oy = y1 + (h - dh) / 2;
				context.blit(RenderPipelines.GUI_TEXTURED, texture, ox, oy, 0, 0, dw, dh, texWidth, texHeight, texWidth, texHeight, color);
				return;
			}
			default -> sx = sy = Math.max(w / (float) texWidth, h / (float) texHeight);
		}
		// Cropped to the middle, row by row through the rounded corners, then the rest in one go
		float u0 = (texWidth - w / sx) / 2, v0 = (texHeight - h / sy) / 2;
		int r = Math.min(radius, Math.min(w, h) / 2);
		for (int row = 0; row < r; row++) {
			double dy = r - row - 0.5;
			int inset = (int) Math.ceil(r - Math.sqrt(Math.max(0, r * r - dy * dy)));
			strip(context, x1, y1, w, sx, sy, u0, v0, inset, row, 1, color);
			strip(context, x1, y1, w, sx, sy, u0, v0, inset, h - 1 - row, 1, color);
		}
		strip(context, x1, y1, w, sx, sy, u0, v0, 0, r, h - 2 * r, color);
	}

	private void strip(GuiGraphics context, int x1, int y1, int w, float sx, float sy, float u0, float v0, int inset, int row, int rows, int color) {
		if (rows <= 0 || w - 2 * inset <= 0) return;
		float u = u0 + inset / sx, v = v0 + row / sy;
		context.blit(RenderPipelines.GUI_TEXTURED, texture, x1 + inset, y1 + row, u, v, w - 2 * inset, rows,
			Math.max(1, Math.round((w - 2 * inset) / sx)), Math.max(1, Math.round(rows / sy)), texWidth, texHeight, color);
	}

	private void ensureTexture() {
		String name = file.get().trim();
		if (name.equals(loadedFor)) return;
		loadedFor = name;
		int gen = ++generation;
		CompletableFuture.supplyAsync(() -> {
			try {
				return read(name);
			} catch (Exception e) {
				mc.execute(() -> ChatUtil.info("GUI Background: couldn't load " + resolve(name) + " (" + e.getMessage() + ")"));
				return null;
			}
		}).thenAccept(image -> mc.execute(() -> {
			if (image == null) return;
			if (gen != generation) {
				image.close();
				return;
			}
			Identifier location = Identifier.fromNamespaceAndPath("femboyware", "gui_background/" + gen);
			DynamicTexture tex = new DynamicTexture(() -> "femboyware gui background", image);
			mc.getTextureManager().register(location, tex);
			tex.upload();
			if (texture != null) mc.getTextureManager().release(texture);
			texture = location;
			texWidth = image.getWidth();
			texHeight = image.getHeight();
		}));
	}

	private static Path resolve(String name) {
		Path given = Path.of(name);
		return given.isAbsolute() ? given : MenuBackgroundModule.folder().resolve(name);
	}

	private static NativeImage read(String name) throws Exception {
		Files.createDirectories(MenuBackgroundModule.folder());
		Path path = resolve(name);
		if (!Files.exists(path)) throw new java.io.FileNotFoundException("no such file; use Choose Picture or put it in that folder");
		// Through ImageIO so JPGs work too, then handed over as a PNG
		java.awt.image.BufferedImage picture = javax.imageio.ImageIO.read(path.toFile());
		if (picture == null) throw new java.io.IOException("not a picture");
		ByteArrayOutputStream png = new ByteArrayOutputStream();
		javax.imageio.ImageIO.write(picture, "png", png);
		return NativeImage.read(png.toByteArray());
	}

	/** Windows' own Open dialog, on top of the game, run off the render thread so the menu keeps drawing. */
	private void chooseFile() {
		if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
			ChatUtil.info("Choosing a picture only works on Windows; type its name in File instead");
			return;
		}
		String script = "Add-Type -AssemblyName System.Windows.Forms;"
			+ "$owner = New-Object System.Windows.Forms.Form -Property @{TopMost = $true};"
			+ "$dialog = New-Object System.Windows.Forms.OpenFileDialog -Property @{Filter = 'Pictures (*.png;*.jpg;*.jpeg)|*.png;*.jpg;*.jpeg'; Title = 'Choose a ClickGUI background'};"
			+ "if ($dialog.ShowDialog($owner) -eq 'OK') { [Console]::Out.Write($dialog.FileName) }";
		CompletableFuture.supplyAsync(() -> {
			try {
				Process process = new ProcessBuilder("powershell", "-NoProfile", "-STA", "-Command", script).redirectErrorStream(false).start();
				process.getOutputStream().close();
				String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
				process.waitFor();
				return out;
			} catch (Exception e) {
				return "";
			}
		}).thenAccept(picked -> mc.execute(() -> {
			if (picked.isEmpty()) return;
			file.set(picked);
			if (!isEnabled()) setEnabled(true);
			FemboyWare.getConfig().save();
		}));
	}
}
