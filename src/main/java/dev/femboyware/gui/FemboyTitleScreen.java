package dev.femboyware.gui;

import dev.femboyware.util.Mc;
import dev.femboyware.FemboyWare;
import dev.femboyware.module.client.ClickGuiModule;
import dev.femboyware.module.render.CleanFontModule;
import dev.femboyware.module.render.MainMenuModule;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerSkinWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.PlayerSkin;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Femboyware's main menu: a slowly turning pink swirl, a clean title, solid rounded buttons in
 * the middle, and a 3D model of your skin on the right that you can drag to spin. All text is in
 * the clean font even if Clean Font is off.
 *
 * <p>It's a plain {@link Screen}, not a {@code TitleScreen}: mods like Essential lay their own main
 * menu over any title screen, which swallowed every click on these buttons.
 */
public class FemboyTitleScreen extends Screen {
	private static final int BUTTON_WIDTH = 130;
	private static final int BUTTON_HEIGHT = 20;
	private static final int BUTTON_GAP = 5;
	private static final int RADIUS = 4;
	/** Space the title and tagline take above the first button. */
	private static final int TITLE_BLOCK = 40;
	private static final int TEXT = 0xFFFFFFFF;
	private static final int MUTED = 0xFFE8CFE2;
	/** Size of the 3D skin on the canvas. */
	private static final int SKIN_WIDTH = 90;
	private static final int SKIN_HEIGHT = 140;

	private enum Kind {
		/** Solid pink, for the main actions. */
		PRIMARY(0xFFFF6FBF, 0xFFFF92CF),
		/** Solid deep plum, for everything else. */
		SECONDARY(0xF0401838, 0xF0582452);

		private final int color;
		private final int hover;

		Kind(int color, int hover) {
			this.color = color;
			this.hover = hover;
		}
	}

	private record Button(String label, Kind kind, Runnable action, int gapBefore) {}

	private final List<Button> buttons = new ArrayList<>();
	private Supplier<PlayerSkin> skin;
	private PlayerSkinWidget skinWidget;
	/** Top of each button, from the last frame, for clicks. */
	private int[] buttonY = new int[0];
	/** The layout canvas: everything but the background is drawn on it, scaled to the window. */
	private float scale = 1;
	private int canvasWidth;
	private int canvasHeight;

	public FemboyTitleScreen() {
		super(Component.literal("femboyware"));
	}

	@Override
	protected void init() {
		buttons.clear();
		buttons.add(new Button("Singleplayer", Kind.PRIMARY, () -> Mc.setScreen(new SelectWorldScreen(this)), 0));
		buttons.add(new Button("Multiplayer", Kind.PRIMARY, () -> Mc.setScreen(new JoinMultiplayerScreen(this)), 0));
		//? if >=26.1 {
		/*buttons.add(new Button("Minecraft Settings", Kind.SECONDARY, () -> Mc.setScreen(new OptionsScreen(this, minecraft.options, false)), 10));
		*///?} else {
		buttons.add(new Button("Minecraft Settings", Kind.SECONDARY, () -> Mc.setScreen(new OptionsScreen(this, minecraft.options)), 10));
		//?}
		buttons.add(new Button("Client", Kind.SECONDARY, () -> Mc.setScreen(new ClickGuiScreen(FemboyWare.getModules().get(ClickGuiModule.class))), 0));
		buttons.add(new Button("Quit Game", Kind.SECONDARY, minecraft::stop, 10));
		skin = minecraft.getSkinManager().createLookup(minecraft.getGameProfile(), false);
		Supplier<PlayerSkin> loaded = skin;
		skinWidget = addRenderableWidget(new PlayerSkinWidget(SKIN_WIDTH, SKIN_HEIGHT, minecraft.getEntityModels(), () -> {
			PlayerSkin textures = loaded.get();
			return textures != null ? textures : DefaultPlayerSkin.get(minecraft.getGameProfile());
		}) {
			/** Spins left and right only: tilting would swing the head away from the name tag above it. */
			@Override
			protected void onDrag(MouseButtonEvent click, double offsetX, double offsetY) {
				super.onDrag(click, offsetX, 0);
			}
		});
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	@Override
	public void renderBackground(GuiGraphics context, int mouseX, int mouseY, float deltaTicks) {
		SwirlBackground.draw(context, width, height);
		// Darker towards the edges so the middle stays easy to read
		context.fillGradient(0, 0, width, height / 3, 0x60000000, 0x00000000);
		context.fillGradient(0, height * 2 / 3, width, height, 0x00000000, 0x70000000);
	}

	@Override
	public void render(GuiGraphics context, int mouseX, int mouseY, float deltaTicks) {
		renderBackground(context, mouseX, mouseY, deltaTicks);
		scale = UiScale.scale();
		canvasWidth = (int) (width / scale);
		canvasHeight = (int) (height / scale);
		context.pose().pushMatrix();
		context.pose().scale(scale, scale);
		UiScale.begin();
		renderCanvas(context, (int) (mouseX / scale), (int) (mouseY / scale));
		UiScale.end();
		context.pose().popMatrix();
		// The 3D model draws in real GUI units, over its spot on the canvas
		skinWidget.visible = module().skin();
		if (skinWidget.visible) {
			placeSkinWidget();
			skinWidget.render(context, mouseX, mouseY, deltaTicks);
		}
	}

	private void renderCanvas(GuiGraphics context, int mouseX, int mouseY) {
		int center = canvasWidth / 2;

		// Centre the title and buttons together, whatever the window size
		int buttonsHeight = buttons.size() * (BUTTON_HEIGHT + BUTTON_GAP) - BUTTON_GAP;
		for (Button button : buttons) buttonsHeight += button.gapBefore;
		int titleY = Math.max(8, (canvasHeight - (TITLE_BLOCK + buttonsHeight)) / 2);
		Component title = text("femboyware", CleanFontModule.TITLE);
		context.drawString(font, title, center - font.width(title) / 2, titleY, TEXT, false);
		Component tagline = text("pink utility client", CleanFontModule.CLEAN);
		context.drawString(font, tagline, center - font.width(tagline) / 2, titleY + 22, MUTED, false);

		int y = titleY + TITLE_BLOCK;
		buttonY = new int[buttons.size()];
		for (int i = 0; i < buttons.size(); i++) {
			Button button = buttons.get(i);
			y += button.gapBefore;
			buttonY[i] = y;
			boolean hovered = hovered(i, mouseX, mouseY);
			int left = center - BUTTON_WIDTH / 2;
			roundedRect(context, left, y, left + BUTTON_WIDTH, y + BUTTON_HEIGHT, RADIUS, hovered ? button.kind.hover : button.kind.color);
			Component label = text(button.label, CleanFontModule.CLEAN);
			int labelY = y + (BUTTON_HEIGHT - font.lineHeight) / 2 + 1;
			context.drawString(font, label, center - font.width(label) / 2, labelY, TEXT, false);
			y += BUTTON_HEIGHT + BUTTON_GAP;
		}

		if (module().skin()) drawSkinExtras(context);

		Component version = text("femboyware " + FemboyWare.VERSION + "  ·  Minecraft " + SharedConstants.getCurrentVersion().name(), CleanFontModule.CLEAN);
		context.drawString(font, version, 6, canvasHeight - 14, 0xB0FFFFFF, false);
		Component credit = text("made by yum", CleanFontModule.CLEAN);
		context.drawString(font, credit, canvasWidth - 6 - font.width(credit), canvasHeight - 14, 0xB0FFFFFF, false);
	}

	// ---------------------------------------------------------------- skin

	/** Where the 3D skin goes on the canvas: right of the buttons, standing still so its tag and shadow stay put. */
	private int skinTop() {
		return canvasHeight / 2 - SKIN_HEIGHT / 2;
	}

	/** A soft shadow on the ground under the skin's feet and your name on a tag above its head; the model itself is {@link #skinWidget}. */
	private void drawSkinExtras(GuiGraphics context) {
		int centerX = (int) (canvasWidth * 0.78f);
		int top = skinTop();
		// The model is drawn 97% of the widget's height, centred, so its feet are just above the bottom
		int feet = top + Math.round(SKIN_HEIGHT * 0.985f);
		Glass.ellipse(context, centerX - 30, feet - 6, centerX + 30, feet + 6, 0x80000000);

		Component name = text(minecraft.getGameProfile().name(), CleanFontModule.CLEAN);
		int nameWidth = font.width(name);
		int tagTop = top - 20;
		roundedRect(context, centerX - nameWidth / 2 - 8, tagTop, centerX + nameWidth / 2 + 8, tagTop + 16, 4, 0xF0401838);
		context.drawString(font, name, centerX - nameWidth / 2, tagTop + 5, TEXT, false);
	}

	/** Keeps the 3D model's widget over its spot on the canvas, in real GUI units, whatever the window size. */
	private void placeSkinWidget() {
		int centerX = (int) (canvasWidth * 0.78f);
		skinWidget.setX(Math.round((centerX - SKIN_WIDTH / 2f) * scale));
		skinWidget.setY(Math.round(skinTop() * scale));
		skinWidget.setWidth(Math.round(SKIN_WIDTH * scale));
		skinWidget.setHeight(Math.round(SKIN_HEIGHT * scale));
	}

	// ---------------------------------------------------------------- drawing helpers

	private static Component text(String text, FontDescription font) {
		return Component.literal(text).setStyle(Style.EMPTY.withFont(font));
	}

	/**
	 * A solid rectangle with its corners rounded off by {@code radius}. The curve is drawn in real
	 * screen pixels, not layout units, so it stays smooth however far the canvas is scaled.
	 */
	static void roundedRect(GuiGraphics context, int x1, int y1, int x2, int y2, int radius, int color) {
		int density = UiScale.density();
		context.pose().pushMatrix();
		context.pose().scale(1f / density, 1f / density);
		int left = x1 * density, top = y1 * density, right = x2 * density, bottom = y2 * density;
		int r = Math.min(radius * density, Math.min((right - left) / 2, (bottom - top) / 2));
		context.fill(left, top + r, right, bottom - r, color);
		for (int row = 0; row < r; row++) {
			// How far in the curve sits on this pixel row, from the circle through the corner
			double dy = r - row - 0.5;
			int inset = (int) Math.round(r - Math.sqrt(r * r - dy * dy));
			context.fill(left + inset, top + row, right - inset, top + row + 1, color);
			context.fill(left + inset, bottom - row - 1, right - inset, bottom - row, color);
		}
		context.pose().popMatrix();
	}

	// ---------------------------------------------------------------- input

	private boolean hovered(int index, double mouseX, double mouseY) {
		if (index >= buttonY.length) return false;
		int left = canvasWidth / 2 - BUTTON_WIDTH / 2;
		int y = buttonY[index];
		return mouseX >= left && mouseX < left + BUTTON_WIDTH && mouseY >= y && mouseY < y + BUTTON_HEIGHT;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
		if (click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			for (int i = 0; i < buttons.size(); i++) {
				if (!hovered(i, click.x() / scale, click.y() / scale)) continue;
				minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f));
				buttons.get(i).action.run();
				return true;
			}
		}
		// Anything else goes to the skin, which spins when dragged
		return super.mouseClicked(click, doubled);
	}

	private static MainMenuModule module() {
		return FemboyWare.getModules().get(MainMenuModule.class);
	}
}
