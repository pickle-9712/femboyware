package dev.femboyware.gui;

import com.mojang.authlib.GameProfile;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.client.ClickGuiModule;
import dev.femboyware.module.render.MainMenuModule;
import net.minecraft.SharedConstants;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.entity.player.PlayerSkinType;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Femboyware's main menu: a dark room with pink curtains flowing at the sides and hearts drifting
 * up, a heart logo and plain text buttons in the middle, and your skin on the right.
 *
 * <p>It extends {@link TitleScreen} so anything that waits for the title screen still finds it.
 */
public class FemboyTitleScreen extends TitleScreen {
	private static final int BACKGROUND = 0xFF0C090E;
	private static final int PINK = 0xFFFF77C8;
	private static final int SOFT_PINK = 0xFFFFC2E2;
	private static final int TEXT = 0xFFF2E8F0;
	private static final int MUTED = 0xFFB9A8B6;
	/** A 7 by 6 pixel heart, row by row. */
	private static final String[] HEART = {
		".XX.XX.",
		"XXXXXXX",
		"XXXXXXX",
		".XXXXX.",
		"..XXX..",
		"...X..."};

	private record Button(String label, Runnable action, int gapBefore) {}

	private record Heart(float x, float startY, float speed, float sway, float phase, int scale, int alpha) {}

	private final List<Button> buttons = new ArrayList<>();
	private final List<Heart> hearts = new ArrayList<>();
	private final Random random = new Random();
	private final long openedAt = System.currentTimeMillis();
	private Supplier<SkinTextures> skin;
	/** Top of each button's text, from the last frame, for clicks. */
	private int[] buttonY = new int[0];

	public FemboyTitleScreen() {
		super(false);
	}

	@Override
	protected void init() {
		buttons.clear();
		buttons.add(new Button("Singleplayer", () -> client.setScreen(new SelectWorldScreen(this)), 0));
		buttons.add(new Button("Multiplayer", () -> client.setScreen(new MultiplayerScreen(this)), 0));
		buttons.add(new Button("Minecraft Settings", () -> client.setScreen(new OptionsScreen(this, client.options)), 8));
		buttons.add(new Button("Client", () -> client.setScreen(new ClickGuiScreen(FemboyWare.getModules().get(ClickGuiModule.class))), 0));
		buttons.add(new Button("Quit Game", client::scheduleStop, 8));

		GameProfile profile = client.getGameProfile();
		skin = client.getSkinProvider().supplySkinTextures(profile, false);

		hearts.clear();
		for (int i = 0; i < module().particleCount(); i++) {
			hearts.add(new Heart(random.nextFloat() * width, random.nextFloat() * height, 4 + random.nextFloat() * 10,
				4 + random.nextFloat() * 10, random.nextFloat() * MathHelper.TAU, 1 + random.nextInt(2), 40 + random.nextInt(90)));
		}
	}

	@Override
	public void tick() {
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		context.fill(0, 0, width, height, BACKGROUND);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		float time = (System.currentTimeMillis() - openedAt) / 1000f;
		renderBackground(context, mouseX, mouseY, deltaTicks);
		if (module().curtains()) {
			drawCurtain(context, time, false);
			drawCurtain(context, time, true);
		}
		if (module().particles()) drawHearts(context, time);

		TextRenderer font = textRenderer;
		int center = width / 2;
		// Logo and name
		int logoScale = 5;
		int logoTop = height / 2 - 105;
		drawOutlinedHeart(context, center - 7 * logoScale / 2, logoTop + (int) (Math.sin(time * 2) * 2), logoScale);
		drawScaledText(context, "femboyware", center, logoTop + 6 * logoScale + 10, 2f, TEXT);

		// Buttons
		int y = logoTop + 6 * logoScale + 42;
		buttonY = new int[buttons.size()];
		for (int i = 0; i < buttons.size(); i++) {
			Button button = buttons.get(i);
			y += button.gapBefore;
			buttonY[i] = y;
			boolean hovered = hovered(i, mouseX, mouseY);
			int color = hovered ? PINK : i >= 2 ? MUTED : TEXT;
			context.drawCenteredTextWithShadow(font, button.label, center, y, color);
			if (hovered) {
				int half = font.getWidth(button.label) / 2;
				// Tiny hearts either side, and a pink underline
				drawHeart(context, center - half - 12, y + 1, 1, PINK);
				drawHeart(context, center + half + 5, y + 1, 1, PINK);
				context.fill(center - half, y + font.fontHeight + 1, center + half, y + font.fontHeight + 2, PINK);
			}
			y += 16;
		}

		if (module().skin()) drawSkin(context, time);

		String version = "femboyware " + FemboyWare.VERSION + " · Minecraft " + SharedConstants.getGameVersion().name();
		context.drawTextWithShadow(font, version, 4, height - 12, 0xFF6E5C6B);
	}

	// ---------------------------------------------------------------- background

	/** Bands of pink that sway like curtains, darkest at the screen edge. */
	private void drawCurtain(DrawContext context, float time, boolean right) {
		int bands = 6;
		for (int band = 0; band < bands; band++) {
			float base = width * (0.06f + band * 0.035f);
			float amplitude = 10 + band * 4;
			int alpha = 26 - band * 3;
			int color = (alpha << 24) | (PINK & 0x00FFFFFF);
			for (int y = 0; y < height; y += 2) {
				float wave = (float) Math.sin(y * 0.018f + time * 0.6f + band * 0.9f) * amplitude
					+ (float) Math.sin(y * 0.007f - time * 0.35f + band) * amplitude * 0.6f;
				int edge = (int) (base + wave);
				if (right) context.fill(width - edge, y, width, y + 2, color);
				else context.fill(0, y, edge, y + 2, color);
			}
		}
	}

	/** Hearts drifting upwards, swaying, wrapping round to the bottom. */
	private void drawHearts(DrawContext context, float time) {
		for (Heart heart : hearts) {
			float travel = (heart.startY - time * heart.speed) % (height + 20);
			if (travel < -20) travel += height + 20;
			float x = heart.x + (float) Math.sin(time * 0.8f + heart.phase) * heart.sway;
			int color = (heart.alpha << 24) | (SOFT_PINK & 0x00FFFFFF);
			drawHeart(context, (int) x, (int) travel, heart.scale, color);
		}
	}

	// ---------------------------------------------------------------- skin

	/** Your skin standing front-on, drawn from the skin texture, gently bobbing on a pink glow. */
	private void drawSkin(DrawContext context, float time) {
		SkinTextures textures = skin != null ? skin.get() : null;
		if (textures == null) textures = DefaultSkinHelper.getSkinTextures(client.getGameProfile());
		Identifier texture = textures.body().texturePath();
		boolean slim = textures.model() == PlayerSkinType.SLIM;
		int arm = slim ? 3 : 4;
		int scale = Math.max(3, height / 90);
		int centerX = (int) (width * 0.76f);
		int top = height / 2 - 16 * scale + (int) (Math.sin(time * 1.5f) * 2);

		// Glow under the feet
		for (int i = 0; i < 6; i++) {
			int w = (12 + i * 3) * scale;
			int alpha = 22 - i * 3;
			context.fill(centerX - w / 2, top + 32 * scale - i, centerX + w / 2, top + 32 * scale + 2 + i, (alpha << 24) | (PINK & 0x00FFFFFF));
		}

		int left = centerX - 4 * scale;
		// Base layer, then the overlay layer on top: head, body, arms, legs
		part(context, texture, left, top, 8, 8, 8, 8, scale);
		part(context, texture, left, top + 8 * scale, 20, 20, 8, 12, scale);
		part(context, texture, left - arm * scale, top + 8 * scale, 44, 20, arm, 12, scale);
		part(context, texture, left + 8 * scale, top + 8 * scale, 36, 52, arm, 12, scale);
		part(context, texture, left, top + 20 * scale, 4, 20, 4, 12, scale);
		part(context, texture, left + 4 * scale, top + 20 * scale, 20, 52, 4, 12, scale);
		part(context, texture, left, top, 40, 8, 8, 8, scale);
		part(context, texture, left, top + 8 * scale, 20, 36, 8, 12, scale);
		part(context, texture, left - arm * scale, top + 8 * scale, 44, 36, arm, 12, scale);
		part(context, texture, left + 8 * scale, top + 8 * scale, 52, 52, arm, 12, scale);
		part(context, texture, left, top + 20 * scale, 4, 36, 4, 12, scale);
		part(context, texture, left + 4 * scale, top + 20 * scale, 4, 52, 4, 12, scale);

		// Name with a little underline, like an account picker
		String name = client.getGameProfile().name();
		int nameY = top - 26;
		context.drawCenteredTextWithShadow(textRenderer, name + " ♡", centerX, nameY, TEXT);
		int half = (textRenderer.getWidth(name + " ♡")) / 2;
		context.fill(centerX - half, nameY + textRenderer.fontHeight + 2, centerX + half, nameY + textRenderer.fontHeight + 3, 0x80FF77C8);
	}

	private static void part(DrawContext context, Identifier texture, int x, int y, int u, int v, int w, int h, int scale) {
		context.drawTexture(RenderPipelines.GUI_TEXTURED, texture, x, y, u, v, w * scale, h * scale, w, h, 64, 64);
	}

	// ---------------------------------------------------------------- shapes

	private static void drawHeart(DrawContext context, int x, int y, int scale, int color) {
		for (int row = 0; row < HEART.length; row++) {
			for (int column = 0; column < HEART[row].length(); column++) {
				if (HEART[row].charAt(column) != 'X') continue;
				context.fill(x + column * scale, y + row * scale, x + (column + 1) * scale, y + (row + 1) * scale, color);
			}
		}
	}

	/** A pink heart with a dark heart inside it, so only the outline shows. */
	private static void drawOutlinedHeart(DrawContext context, int x, int y, int scale) {
		drawHeart(context, x, y, scale, PINK);
		int inner = Math.max(1, scale - 2);
		int offsetX = (7 * scale - 7 * inner) / 2;
		int offsetY = (int) ((6 * scale - 6 * inner) / 2.5f);
		drawHeart(context, x + offsetX, y + offsetY, inner, BACKGROUND);
	}

	private void drawScaledText(DrawContext context, String text, int centerX, int y, float scale, int color) {
		context.getMatrices().pushMatrix();
		context.getMatrices().translate(centerX, y);
		context.getMatrices().scale(scale, scale);
		context.drawCenteredTextWithShadow(textRenderer, text, 0, 0, color);
		context.getMatrices().popMatrix();
	}

	// ---------------------------------------------------------------- input

	private boolean hovered(int index, double mouseX, double mouseY) {
		if (index >= buttonY.length) return false;
		int half = textRenderer.getWidth(buttons.get(index).label) / 2 + 6;
		int y = buttonY[index];
		return mouseX >= width / 2.0 - half && mouseX <= width / 2.0 + half && mouseY >= y - 3 && mouseY < y + textRenderer.fontHeight + 3;
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		if (click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
		for (int i = 0; i < buttons.size(); i++) {
			if (!hovered(i, click.x(), click.y())) continue;
			client.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.UI_BUTTON_CLICK, 1f));
			buttons.get(i).action.run();
			return true;
		}
		return false;
	}

	private static MainMenuModule module() {
		return FemboyWare.getModules().get(MainMenuModule.class);
	}
}
