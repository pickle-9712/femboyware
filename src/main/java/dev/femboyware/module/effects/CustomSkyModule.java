package dev.femboyware.module.effects;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.util.ChatUtil;
import dev.femboyware.util.FxRender;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.joml.Vector3f;

/**
 * Your own picture as the sky: a panorama (a 2:1 picture going all the way round, like the ones
 * phones take) from the femboyware/skies folder wrapped round the world, or one of the built-in
 * skies. Hills and trees still stand in front of it, and it can turn slowly.
 */
public class CustomSkyModule extends SkyFxModule {
	public enum Sky { GALAXY, PINK_NEBULA, SUNSET_CLOUDS, AURORA_NIGHT, FILE }

	private static final int RINGS = 18, SEGMENTS = 48;

	private final EnumSetting<Sky> sky = add(new EnumSetting<>("Sky", "A built-in sky, or File for your own picture", Sky.GALAXY));
	private final StringSetting file = add(new StringSetting("File", "A PNG or JPG in .minecraft/femboyware/skies (a 2:1 panorama looks best)", "sky.png")).visibleWhen(() -> sky.is(Sky.FILE));
	private final NumberSetting opacity = add(new NumberSetting("Opacity", "How much it covers the real sky", 1, 0.1, 1, 0.05));
	private final NumberSetting rotation = add(new NumberSetting("Rotation", "How fast it turns", 0.2, 0, 5, 0.05));
	private final NumberSetting tilt = add(new NumberSetting("Horizon", "Moves the picture's horizon up or down, in degrees", 0, -30, 30, 1));

	private Identifier texture;
	/** What's loaded (or loading): the sky and file it's for. */
	private String loadedFor;
	private int generation;

	public CustomSkyModule() {
		super("Custom Sky", "Your own picture, or a built-in one, as the sky");
	}

	@Override
	public String getInfo() {
		return sky.is(Sky.FILE) ? file.get() : sky.getDisplayName();
	}

	private static Path folder() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("femboyware").resolve("skies");
	}

	private void ensureTexture() {
		String key = sky.get() + "|" + (sky.is(Sky.FILE) ? file.get() : "");
		if (key.equals(loadedFor)) return;
		loadedFor = key;
		int gen = ++generation;
		Sky kind = sky.get();
		String name = file.get();
		// Made off the render thread (the built-in ones take a moment), uploaded on it
		CompletableFuture.supplyAsync(() -> {
			try {
				return kind == Sky.FILE ? readFile(name) : generate(kind);
			} catch (Exception e) {
				mc.execute(() -> ChatUtil.info("Custom Sky: couldn't load " + folder().resolve(name) + " (" + e.getMessage() + ")"));
				return null;
			}
		}).thenAccept(image -> mc.execute(() -> {
			if (image == null) return;
			if (gen != generation) {
				image.close();
				return;
			}
			Identifier location = Identifier.fromNamespaceAndPath("femboyware", "sky/custom_" + gen);
			DynamicTexture tex = new DynamicTexture(() -> "femboyware custom sky", image);
			mc.getTextureManager().register(location, tex);
			tex.upload();
			if (texture != null) mc.getTextureManager().release(texture);
			texture = location;
		}));
	}

	private static NativeImage readFile(String name) throws Exception {
		Path dir = folder();
		Files.createDirectories(dir);
		Path path = dir.resolve(name);
		if (!Files.exists(path)) throw new java.io.FileNotFoundException("no such file; put it in that folder");
		// Through ImageIO so JPGs work too, then handed over as a PNG
		java.awt.image.BufferedImage read = javax.imageio.ImageIO.read(path.toFile());
		if (read == null) throw new java.io.IOException("not a picture");
		ByteArrayOutputStream png = new ByteArrayOutputStream();
		javax.imageio.ImageIO.write(read, "png", png);
		return NativeImage.read(png.toByteArray());
	}

	// ---------------------------------------------------------------- the built-in skies

	/** Noise that joins up round the back of the sky, so there's no seam where it meets. */
	private static float wrapNoise(double x, double y, double scale, double width) {
		float u = (float) (x / width);
		return Noise.fbm(x, y, scale) * (1 - u) + Noise.fbm(x - width, y, scale) * u;
	}

	private static NativeImage generate(Sky kind) {
		int w = 1024, h = 512;
		NativeImage image = new NativeImage(w, h, false);
		java.util.Random stars = new java.util.Random(kind.ordinal() * 31L + 7);
		for (int y = 0; y < h; y++) {
			// 0 at the top of the sky, 1 at the bottom
			float v = y / (float) (h - 1);
			float elevation = 1 - v * 2;
			for (int x = 0; x < w; x++) {
				float n = wrapNoise(x, y, 90, w), fine = wrapNoise(x * 2.3, y * 2.3, 40, w * 2.3);
				int color = switch (kind) {
					case GALAXY -> {
						// A milky band across the sky, dust lanes through it
						float band = (float) Math.exp(-Math.pow((elevation - 0.25f * (float) Math.sin(x / (float) w * Math.PI * 2)) * 3.2, 2));
						int base = FxRender.mix(0xFF05040E, 0xFF151036, Math.max(0, elevation) * 0.6f);
						int glow = FxRender.mix(0xFF6A4AC8, 0xFFFFC8F0, fine);
						yield FxRender.mix(base, glow, band * (0.25f + 0.5f * n) * (1 - 0.6f * Math.max(0, fine - 0.55f) * 2));
					}
					case PINK_NEBULA -> {
						int base = FxRender.mix(0xFF0A0614, 0xFF241038, (1 - Math.abs(elevation)) * 0.7f);
						float cloud = Math.max(0, n * 1.6f - 0.55f);
						int gas = FxRender.mix(0xFFFF5EC8, 0xFF5EC8FF, fine);
						yield FxRender.mix(base, gas, Math.min(1, cloud * 1.4f));
					}
					case SUNSET_CLOUDS -> {
						float up = Math.max(0, elevation);
						int base = up < 0.3f ? FxRender.mix(0xFFFFB45A, 0xFFFF6F8A, up / 0.3f) : FxRender.mix(0xFFFF6F8A, 0xFF3A3A8C, Math.min(1, (up - 0.3f) / 0.7f));
						if (elevation < 0) base = FxRender.mix(0xFFFFB45A, 0xFF5A2A4A, -elevation);
						// Long streaky clouds lit pink from underneath
						float streak = wrapNoise(x, y * 4, 70, w);
						float cloud = Math.max(0, streak * 1.8f - 0.95f) * (up > 0.02f ? 1 : 0);
						yield FxRender.mix(base, FxRender.mix(0xFFFFE0C8, 0xFFFF8AB0, fine), Math.min(0.85f, cloud * 1.6f));
					}
					case AURORA_NIGHT -> {
						int base = FxRender.mix(0xFF020812, 0xFF0A1A34, (1 - Math.abs(elevation)) * 0.8f);
						// Curtains hanging from high up, waving round the sky
						float wave = (float) Math.sin(x / (float) w * Math.PI * 2 * 3 + n * 6);
						float curtain = (float) Math.exp(-Math.pow((elevation - 0.35f - wave * 0.08f) * 7, 2)) * (0.4f + 0.6f * fine);
						int light = FxRender.mix(0xFF3DFFA0, 0xFFB45AFF, Math.max(0, Math.min(1, (elevation - 0.25f) * 3)));
						yield FxRender.mix(base, light, Math.min(1, curtain * 1.3f));
					}
					case FILE -> 0xFF000000;
				};
				image.setPixel(x, y, 0xFF000000 | color);
			}
		}
		// Stars over the dark skies
		if (kind != Sky.SUNSET_CLOUDS) {
			for (int i = 0; i < 2600; i++) {
				int x = stars.nextInt(w), y = stars.nextInt(h * 6 / 10);
				float bright = (float) Math.pow(stars.nextFloat(), 2.5);
				int c = FxRender.mix(image.getPixel(x, y) | 0xFF000000, stars.nextFloat() < 0.2f ? 0xFFFFD8F0 : 0xFFFFFFFF, 0.4f + 0.6f * bright);
				image.setPixel(x, y, c);
				if (bright > 0.7f) {
					int soft = FxRender.mix(image.getPixel(Math.min(w - 1, x + 1), y) | 0xFF000000, c, 0.5f);
					image.setPixel(Math.min(w - 1, x + 1), y, soft);
					image.setPixel(x, Math.min(h - 1, y + 1), soft);
				}
			}
		}
		return image;
	}

	@Override
	protected void onDisable() {
		if (texture != null) mc.getTextureManager().release(texture);
		texture = null;
		loadedFor = null;
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		if (mc.level == null || !hasSky()) return;
		ensureTexture();
		if (texture == null) return;
		float distance = skyDistance();
		double turn = time() * rotation.get();
		float alpha = opacity.getFloat();
		int color = FxRender.fade(0xFFFFFFFF, alpha);
		VertexConsumer consumer = FxRender.textured(event, texture, false, false);
		PoseStack.Pose pose = event.matrices().last();
		float shift = tilt.getFloat();
		for (int i = 0; i < RINGS; i++) {
			// From straight up (v 0) to straight down (v 1)
			float v1 = i / (float) RINGS, v2 = (i + 1) / (float) RINGS;
			double e1 = 90 - v1 * 180 + shift, e2 = 90 - v2 * 180 + shift;
			for (int j = 0; j < SEGMENTS; j++) {
				float u1 = j / (float) SEGMENTS, u2 = (j + 1) / (float) SEGMENTS;
				double a1 = u1 * 360 + turn, a2 = u2 * 360 + turn;
				Vector3f p1 = direction(a1, clamp(e1)).mul(distance), p2 = direction(a2, clamp(e1)).mul(distance);
				Vector3f p3 = direction(a2, clamp(e2)).mul(distance), p4 = direction(a1, clamp(e2)).mul(distance);
				// Both windings, so it shows from inside whichever way the culling goes
				FxRender.textured(consumer, pose, p1.x, p1.y, p1.z, u1, v1, color);
				FxRender.textured(consumer, pose, p2.x, p2.y, p2.z, u2, v1, color);
				FxRender.textured(consumer, pose, p3.x, p3.y, p3.z, u2, v2, color);
				FxRender.textured(consumer, pose, p4.x, p4.y, p4.z, u1, v2, color);
				FxRender.textured(consumer, pose, p4.x, p4.y, p4.z, u1, v2, color);
				FxRender.textured(consumer, pose, p3.x, p3.y, p3.z, u2, v2, color);
				FxRender.textured(consumer, pose, p2.x, p2.y, p2.z, u2, v1, color);
				FxRender.textured(consumer, pose, p1.x, p1.y, p1.z, u1, v1, color);
			}
		}
	}

	private static double clamp(double elevation) {
		return Math.max(-89.9, Math.min(89.9, elevation));
	}
}
