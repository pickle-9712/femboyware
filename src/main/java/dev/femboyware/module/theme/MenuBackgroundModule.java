package dev.femboyware.module.theme;

import com.mojang.blaze3d.platform.NativeImage;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.Category;
import dev.femboyware.module.Module;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.StringSetting;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * Your own picture behind the main menu instead of the pink swirl. Put a PNG or JPG in
 * .minecraft/femboyware/backgrounds and type its name in File.
 */
public class MenuBackgroundModule extends Module {
	private final StringSetting file = add(new StringSetting("File", "A PNG or JPG in .minecraft/femboyware/backgrounds", "background.png"));
	private final NumberSetting dim = add(new NumberSetting("Dim", "How much darker to make it so the menu stays readable", 0.25, 0, 0.9, 0.05));

	private Identifier texture;
	private int texWidth, texHeight;
	private String loadedFor;
	private int generation;

	public MenuBackgroundModule() {
		super("Menu Background", "Use your own PNG or JPG as the main menu background", Category.THEME);
	}

	@Override
	public String getInfo() {
		return file.get();
	}

	public static Path folder() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("femboyware").resolve("backgrounds");
	}

	/** Called by the title screen: draws the picture covering the screen and returns true, or false to draw the swirl. */
	public static boolean draw(GuiGraphics context, int width, int height) {
		if (FemboyWare.getModules() == null) return false;
		MenuBackgroundModule module = FemboyWare.getModules().get(MenuBackgroundModule.class);
		return module.isEnabled() && module.render(context, width, height);
	}

	private boolean render(GuiGraphics context, int width, int height) {
		ensureTexture();
		if (texture == null || texWidth <= 0 || texHeight <= 0) return false;
		// Cover the screen, cropping whichever side is too long
		float scale = Math.max(width / (float) texWidth, height / (float) texHeight);
		int drawW = Math.round(texWidth * scale), drawH = Math.round(texHeight * scale);
		int x = (width - drawW) / 2, y = (height - drawH) / 2;
		context.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0, 0, drawW, drawH, texWidth, texHeight, texWidth, texHeight);
		int alpha = (int) (dim.get() * 255);
		if (alpha > 0) context.fill(0, 0, width, height, alpha << 24);
		return true;
	}

	private void ensureTexture() {
		String name = file.get();
		if (name.equals(loadedFor)) return;
		loadedFor = name;
		int gen = ++generation;
		CompletableFuture.supplyAsync(() -> {
			try {
				return read(name);
			} catch (Exception e) {
				FemboyWare.LOGGER.warn("Menu Background: couldn't load {} ({})", folder().resolve(name), e.getMessage());
				return null;
			}
		}).thenAccept(image -> mc.execute(() -> {
			if (image == null) return;
			if (gen != generation) {
				image.close();
				return;
			}
			Identifier location = Identifier.fromNamespaceAndPath("femboyware", "menu_background/" + gen);
			DynamicTexture tex = new DynamicTexture(() -> "femboyware menu background", image);
			mc.getTextureManager().register(location, tex);
			tex.upload();
			if (texture != null) mc.getTextureManager().release(texture);
			texture = location;
			texWidth = image.getWidth();
			texHeight = image.getHeight();
		}));
	}

	private static NativeImage read(String name) throws Exception {
		Path dir = folder();
		Files.createDirectories(dir);
		Path path = dir.resolve(name);
		if (!Files.exists(path)) throw new java.io.FileNotFoundException("no such file; put it in that folder");
		// Through ImageIO so JPGs work too, then handed over as a PNG
		java.awt.image.BufferedImage picture = javax.imageio.ImageIO.read(path.toFile());
		if (picture == null) throw new java.io.IOException("not a picture");
		ByteArrayOutputStream png = new ByteArrayOutputStream();
		javax.imageio.ImageIO.write(picture, "png", png);
		return NativeImage.read(png.toByteArray());
	}
}
