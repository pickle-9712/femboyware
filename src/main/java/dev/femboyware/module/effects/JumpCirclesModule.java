package dev.femboyware.module.effects;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.femboyware.event.Render3DEvent;
import dev.femboyware.setting.BoolSetting;
import dev.femboyware.setting.EnumSetting;
import dev.femboyware.setting.NumberSetting;
import dev.femboyware.setting.StringSetting;
import dev.femboyware.util.ChatUtil;
import dev.femboyware.util.FxRender;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Rings of light ripple out across the ground where you jump and land; or a picture does, one of
 * the built-in ones (Star of David, heart, star, moon, snowflake, pentacle) or your own from the
 * femboyware/jumpcircles folder.
 */
public class JumpCirclesModule extends PlayerFxModule {
	public enum Style { RING, DOUBLE, SHOCKWAVE, IMAGE }

	public enum Picture {
		STAR_OF_DAVID("star_of_david"), HEART("heart"), STAR("star"), MOON("moon"), SNOWFLAKE("snowflake"), PENTACLE("pentacle"), FILE(null);

		final Identifier texture;

		Picture(String name) {
			texture = name == null ? null : Identifier.fromNamespaceAndPath("femboyware", "textures/fx/jump_" + name + ".png");
		}
	}

	private static final int SEGMENTS = 48;
	private static final int MAX_CIRCLES = 64;

	private final EnumSetting<Style> style = add(new EnumSetting<>("Style", "Ring: one ripple. Double: two, one after the other. Shockwave: a filled disc. Image: a picture", Style.RING));
	private final EnumSetting<Picture> picture = add(new EnumSetting<>("Image", "Which picture, or File for your own", Picture.STAR_OF_DAVID)).visibleWhen(() -> style.is(Style.IMAGE));
	private final StringSetting file = add(new StringSetting("File", "A PNG or JPG in .minecraft/femboyware/jumpcircles", "circle.png"))
		.visibleWhen(() -> style.is(Style.IMAGE) && picture.is(Picture.FILE));
	private final BoolSetting tint = add(new BoolSetting("Tint", "Colour the picture with the palette (off keeps a file's own colours)", true)).visibleWhen(() -> style.is(Style.IMAGE));
	private final BoolSetting glow = add(new BoolSetting("Glow", "Add the picture as light, so it shines in the dark", true)).visibleWhen(() -> style.is(Style.IMAGE));
	private final NumberSetting spin = add(new NumberSetting("Spin", "How far the picture turns as it spreads, in degrees", 90, -360, 360, 5)).visibleWhen(() -> style.is(Style.IMAGE));
	private final BoolSetting onJump = add(new BoolSetting("On Jump", "When you jump", true));
	private final BoolSetting onLand = add(new BoolSetting("On Land", "When you land", false));
	private final NumberSetting radius = add(new NumberSetting("Radius", "How far the ripple spreads", 1.6, 0.4, 5, 0.05));
	private final NumberSetting duration = add(new NumberSetting("Duration", "Seconds each ripple lasts", 0.9, 0.2, 3, 0.05));
	private final NumberSetting width = add(new NumberSetting("Width", "How thick the ring is, as a share of its size", 0.18, 0.03, 0.6, 0.01)).visibleWhen(() -> !style.is(Style.IMAGE));
	private final Palette palette = palette(0xFFFF77C8, 0xFF8AB8FF);
	private final EnumSetting<ShowOn> showOn = showOnSetting();

	/** Where, when, and which way the picture starts turned, so they don't all line up. */
	private record Circle(Vec3 pos, long start, float angle) {}

	private final List<Circle> circles = new ArrayList<>();
	private final Map<Integer, Boolean> grounded = new HashMap<>();

	/** Your own picture, once loaded, and the file name it's for. */
	private Identifier fileTexture;
	private String loadedFile;
	private int generation;

	public JumpCirclesModule() {
		super("Jump Circles", "Rings or pictures ripple out where you jump and land");
	}

	@Override
	public String getInfo() {
		if (!style.is(Style.IMAGE)) return null;
		return picture.is(Picture.FILE) ? file.get() : picture.getDisplayName();
	}

	@Override
	protected void onDisable() {
		circles.clear();
		grounded.clear();
	}

	@Override
	public void onTick() {
		Map<Integer, Boolean> seen = new HashMap<>();
		for (Player player : wearers(true)) {
			boolean onGround = player.onGround();
			Boolean before = grounded.get(player.getId());
			if (before != null && before != onGround) {
				boolean jumped = !onGround && player.getDeltaMovement().y > 0.1;
				if (jumped && onJump.get() || onGround && onLand.get()) {
					circles.add(new Circle(player.position().add(0, 0.02, 0), System.nanoTime(), player.getYRot()));
					if (circles.size() > MAX_CIRCLES) circles.remove(0);
				}
			}
			seen.put(player.getId(), onGround);
		}
		grounded.clear();
		grounded.putAll(seen);
	}

	@Override
	public void onRender3D(Render3DEvent event) {
		long now = System.nanoTime();
		double life = duration.get() * (style.is(Style.DOUBLE) ? 1.3 : 1);
		circles.removeIf(circle -> (now - circle.start) / 1e9 > life);
		if (circles.isEmpty()) return;

		Vec3 cam = event.camera().pos;
		PoseStack matrices = event.matrices();
		if (style.is(Style.IMAGE)) {
			Identifier texture = texture();
			if (texture == null) return;
			VertexConsumer consumer = FxRender.textured(event, texture, glow.get(), false);
			for (Circle circle : circles) {
				float progress = (float) ((now - circle.start) / 1e9 / duration.get());
				matrices.pushPose();
				matrices.translate((float) (circle.pos.x - cam.x), (float) (circle.pos.y - cam.y), (float) (circle.pos.z - cam.z));
				image(consumer, matrices, circle, progress);
				matrices.popPose();
			}
			return;
		}
		VertexConsumer light = FxRender.quads(event, true, false);
		for (Circle circle : circles) {
			double age = (now - circle.start) / 1e9;
			matrices.pushPose();
			matrices.translate((float) (circle.pos.x - cam.x), (float) (circle.pos.y - cam.y), (float) (circle.pos.z - cam.z));
			ripple(light, matrices.last(), (float) (age / duration.get()));
			if (style.is(Style.DOUBLE)) ripple(light, matrices.last(), (float) ((age - duration.get() * 0.3) / duration.get()));
			matrices.popPose();
		}
	}

	/** One ripple {@code progress} of the way through: eased outwards, fading as it goes. */
	private void ripple(VertexConsumer consumer, PoseStack.Pose pose, float progress) {
		if (progress <= 0 || progress >= 1) return;
		float eased = 1 - (float) Math.pow(1 - progress, 3);
		float outer = radius.getFloat() * eased;
		float alpha = 1 - progress;
		if (style.is(Style.SHOCKWAVE)) {
			FxRender.ring(consumer, pose, 0, outer, 0, SEGMENTS, (t, inner) -> FxRender.fade(palette.at(t), inner ? alpha * 0.08f : alpha * 0.7f));
			return;
		}
		float inner = outer * (1 - width.getFloat());
		float mid = (inner + outer) / 2;
		// Bright in the middle of the band, fading to nothing at both edges
		FxRender.ring(consumer, pose, inner, mid, 0, SEGMENTS, (t, in) -> FxRender.fade(palette.at(t), in ? 0 : alpha));
		FxRender.ring(consumer, pose, mid, outer, 0, SEGMENTS, (t, in) -> FxRender.fade(palette.at(t), in ? alpha : 0));
	}

	/** The picture lying flat on the ground, growing and turning as it fades. */
	private void image(VertexConsumer consumer, PoseStack matrices, Circle circle, float progress) {
		if (progress <= 0 || progress >= 1) return;
		float eased = 1 - (float) Math.pow(1 - progress, 3);
		float r = radius.getFloat() * (0.25f + 0.75f * eased);
		// Quick to appear, then fading out
		float alpha = Math.min(1, progress * 8) * (1 - progress * progress);
		matrices.mulPose(Axis.YP.rotationDegrees(-circle.angle + spin.getFloat() * eased));
		PoseStack.Pose pose = matrices.last();
		int[] corners = new int[4];
		for (int i = 0; i < 4; i++) {
			int color = tint.get() ? palette.at(i / 4f + progress * 0.5f) : 0xFFFFFFFF;
			corners[i] = FxRender.fade(color, alpha);
		}
		FxRender.textured(consumer, pose, -r, 0, -r, 0, 0, corners[0]);
		FxRender.textured(consumer, pose, -r, 0, r, 0, 1, corners[1]);
		FxRender.textured(consumer, pose, r, 0, r, 1, 1, corners[2]);
		FxRender.textured(consumer, pose, r, 0, -r, 1, 0, corners[3]);
	}

	private Identifier texture() {
		Picture which = picture.get();
		if (which != Picture.FILE) return which.texture;
		String name = file.get();
		if (!name.equals(loadedFile)) load(name);
		return fileTexture;
	}

	private static Path folder() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("femboyware").resolve("jumpcircles");
	}

	/** Reads your picture off the render thread and uploads it on it. */
	private void load(String name) {
		loadedFile = name;
		int gen = ++generation;
		CompletableFuture.supplyAsync(() -> {
			try {
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
			} catch (Exception e) {
				mc.execute(() -> ChatUtil.info("Jump Circles: couldn't load " + folder().resolve(name) + " (" + e.getMessage() + ")"));
				return null;
			}
		}).thenAccept(image -> mc.execute(() -> {
			if (image == null) return;
			if (gen != generation) {
				image.close();
				return;
			}
			Identifier location = Identifier.fromNamespaceAndPath("femboyware", "jumpcircles/custom_" + gen);
			DynamicTexture tex = new DynamicTexture(() -> "femboyware jump circle", image);
			mc.getTextureManager().register(location, tex);
			tex.upload();
			if (fileTexture != null) mc.getTextureManager().release(fileTexture);
			fileTexture = location;
		}));
	}
}
