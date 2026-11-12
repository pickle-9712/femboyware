package dev.femboyware.module.larp;

import dev.femboyware.FemboyWare;
import dev.femboyware.gui.Glass;
import dev.femboyware.gui.UiScale;
import dev.femboyware.module.render.CosmeticsModule;
import dev.femboyware.module.larp.PhoneModule.Limited;
import dev.femboyware.module.larp.PhoneModule.Message;
import dev.femboyware.module.larp.PhoneModule.Position;
import dev.femboyware.module.larp.PhoneModule.Transaction;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

/**
 * The fake phone, floating over the game: a home screen of apps, Messages, a bank, a stock app,
 * a Roblox profile and a shortcut to the Ledger wallet. Drawn on femboyware's canvas at a density
 * that leaves room for the whole phone, so it's sharp at any size.
 */
public class PhoneScreen extends Screen {
	public enum App {
		MESSAGES("Messages"), BANK("Bank"), STOCKS("Stocks"), ROBLOX("Roblox"), LEDGER("Ledger"), CAMERA("Camera"), SHOPIFY("Shopify"), VALET("Valet");

		final String label;

		App(String label) {
			this.label = label;
		}
	}

	private static final int PHONE_W = 236, PHONE_H = 480, BEZEL = 8, RADIUS = 34;
	private static final int SCREEN_W = PHONE_W - BEZEL * 2, SCREEN_H = PHONE_H - BEZEL * 2;
	private static final int TEXT = 0xFFFFFFFF, MUTED = 0xFF98989F, FAINT = 0xFF636369, GREEN = 0xFF30D158, RED = 0xFFFF453A, BLUE = 0xFF0A84FF;

	private record Hit(int x, int y, int w, int h, Runnable action) {
		boolean contains(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	private final PhoneModule phone;
	private final List<Hit> hits = new ArrayList<>();
	private App app;
	private String thread;
	private int scroll;
	private int phoneX, phoneY;
	private long opened;
	private String status;
	private long statusAt;

	public PhoneScreen(PhoneModule phone) {
		super(Component.literal("Phone"));
		this.phone = phone;
	}

	private static Identifier roblox(String name) {
		return Identifier.fromNamespaceAndPath("femboyware", "textures/larp/roblox/" + name + ".png");
	}

	@Override
	protected void init() {
		if (opened == 0) {
			opened = System.currentTimeMillis();
			LarpSounds.play(LarpSounds.PHONE_UNLOCK, 0.7f);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void renderBackground(GuiGraphics context, int mouseX, int mouseY, float deltaTicks) {
		context.fill(0, 0, width, height, 0x40000000);
	}

	/**
	 * Text needs at least 2 screen pixels per unit to use the smooth font, so the density never
	 * goes below that; on a window too small for the layout at that density, everything is shrunk
	 * to fit instead (see {@link #scale}).
	 */
	private int density() {
		var window = minecraft.getWindow();
		return Math.max(UiScale.MIN_CLEAN_DENSITY, Math.min(window.getWidth() / (PHONE_W + 80), window.getHeight() / (PHONE_H + 40)));
	}

	/** Screen pixels per layout unit over GUI scale: the density, shrunk if the layout wouldn't fit. */
	private float scale() {
		var window = minecraft.getWindow();
		int density = density();
		float fit = Math.min(1f, Math.min(window.getWidth() / (float) density / (PHONE_W + 80), window.getHeight() / (float) density / (PHONE_H + 40)));
		return density * fit / (float) window.getGuiScale();
	}

	@Override
	public void render(GuiGraphics context, int mouseX, int mouseY, float deltaTicks) {
		float scale = scale();
		context.pose().pushMatrix();
		context.pose().scale(scale, scale);
		UiScale.begin(density());
		renderScaled(context, (int) (width / scale), (int) (height / scale), mouseX / scale, mouseY / scale);
		UiScale.end();
		context.pose().popMatrix();
		drawSkin(context, scale);
	}

	/**
	 * Your skin, front on, the way the main menu shows it, in the Roblox profile's avatar spot.
	 * Drawn in plain GUI units after the phone, clipped to the part of the screen it's scrolled into.
	 */
	private void drawSkin(GuiGraphics context, float scale) {
		if (avatarBox == null || minecraft.player == null) return;
		int top = Math.max(avatarBox[1], avatarBox[4]), bottom = Math.min(avatarBox[3], avatarBox[5]);
		if (bottom <= top) return;
		if (wideModel == null) {
			wideModel = new net.minecraft.client.model.player.PlayerModel(minecraft.getEntityModels().bakeLayer(net.minecraft.client.model.geom.ModelLayers.PLAYER), false);
			slimModel = new net.minecraft.client.model.player.PlayerModel(minecraft.getEntityModels().bakeLayer(net.minecraft.client.model.geom.ModelLayers.PLAYER_SLIM), true);
		}
		var skin = minecraft.player.getSkin();
		var model = skin.model() == net.minecraft.world.entity.player.PlayerModelType.SLIM ? slimModel : wideModel;
		int x1 = Math.round(avatarBox[0] * scale), y1 = Math.round(avatarBox[1] * scale), x2 = Math.round(avatarBox[2] * scale), y2 = Math.round(avatarBox[3] * scale);
		context.enableScissor(x1, Math.round(top * scale), x2, Math.round(bottom * scale));
		//? if >=26.1 {
		/*context.blit(RenderPipelines.GUI_TEXTURED, roblox("avatar_" + avatarKey()), x1, y1, 0, 0, x2 - x1, y2 - y1, 300, 300, 300, 300);
		*///?} else {
		float modelScale = 0.97f * (y2 - y1) / 2.125f;
		context.submitSkinRenderState(model, skin.body().texturePath(), modelScale, 0, 180, -1.0625f, x1, y1, x2, y2);
		//?}
		context.disableScissor();
	}

	/** Where the avatar goes this frame on the canvas (x1 y1 x2 y2) and the screen's visible band (top, bottom), or null. */
	private int[] avatarBox;
	private net.minecraft.client.model.player.PlayerModel wideModel, slimModel;

	private void renderScaled(GuiGraphics context, int canvasW, int canvasH, float canvasMx, float canvasMy) {
		hits.clear();
		avatarBox = null;
		float appear = ease(Mth.clamp((System.currentTimeMillis() - opened) / 300f, 0, 1));
		phoneX = (canvasW - PHONE_W) / 2;
		phoneY = (canvasH - PHONE_H) / 2 + Math.round((1 - appear) * 40);
		// Clicks outside the phone put it away
		hits.add(new Hit(-phoneX, -phoneY, canvasW, canvasH, this::onClose));
		hits.add(new Hit(0, 0, PHONE_W, PHONE_H, () -> { }));
		float mx = canvasMx - phoneX - BEZEL, my = canvasMy - phoneY - BEZEL;

		context.pose().pushMatrix();
		context.pose().translate(phoneX, phoneY);
		// Titanium frame, black bezel, and the screen inside
		Glass.draw(context, -2, -2, PHONE_W + 2, PHONE_H + 2, RADIUS + 2, 0x30000000, true);
		Glass.rounded(context, -2, -2, PHONE_W + 2, PHONE_H + 2, RADIUS + 2, 0xFF8E8C93);
		Glass.rounded(context, 0, 0, PHONE_W, PHONE_H, RADIUS, 0xFF050506);
		context.fill(-3, 92, -1, 112, 0xFF7A787F);
		context.fill(-3, 120, -1, 150, 0xFF7A787F);
		context.fill(PHONE_W + 1, 110, PHONE_W + 3, 160, 0xFF7A787F);
		context.pose().translate(BEZEL, BEZEL);
		// Hits inside the screen are kept in screen coordinates
		hits.add(new Hit(-phoneX - BEZEL, -phoneY - BEZEL, 0, 0, () -> { }));
		boolean dark = app != null;
		Glass.rounded(context, 0, 0, SCREEN_W, SCREEN_H, RADIUS - BEZEL, dark ? appBackground() : 0xFF000000);
		if (app == null) drawHome(context, mx, my);
		else {
			switch (app) {
				case MESSAGES -> {
					if (thread == null) drawMessages(context, mx, my);
					else drawThread(context, mx, my);
				}
				case BANK -> drawBank(context, mx, my);
				case STOCKS -> drawStocks(context, mx, my);
				case ROBLOX -> drawRoblox(context, mx, my);
				case SHOPIFY -> drawShopify(context, mx, my);
				case VALET -> drawValet(context, mx, my);
				default -> {
				}
			}
		}
		drawStatusBar(context, app == null || app == App.ROBLOX || app == App.STOCKS || app == App.BANK || app == App.MESSAGES || app == App.SHOPIFY || app == App.VALET);
		// The home bar: click it to go home
		int bw = 90, bx = (SCREEN_W - bw) / 2, by = SCREEN_H - 9;
		Glass.rounded(context, bx, by, bx + bw, by + 4, 2, 0xE0FFFFFF);
		hits.add(new Hit(bx - 10, by - 8, bw + 20, 16, this::home));
		if (status != null && System.currentTimeMillis() - statusAt < 2200) {
			int sw = font.width(status) + 20;
			Glass.rounded(context, (SCREEN_W - sw) / 2, SCREEN_H - 46, (SCREEN_W + sw) / 2, SCREEN_H - 28, 9, 0xF0303034);
			context.drawString(font, status, (SCREEN_W - font.width(status)) / 2, SCREEN_H - 41, TEXT, false);
		}
		context.pose().popMatrix();
	}

	private int appBackground() {
		return switch (app) {
			case ROBLOX -> 0xFF111216;
			case BANK -> 0xFF0B0B10;
			default -> 0xFF000000;
		};
	}

	private void drawStatusBar(GuiGraphics context, boolean light) {
		int color = light ? TEXT : 0xFF000000;
		String time = new SimpleDateFormat("H:mm", Locale.US).format(new Date());
		context.drawString(font, time, 26, 12, color, false);
		// The island
		Glass.rounded(context, SCREEN_W / 2 - 34, 7, SCREEN_W / 2 + 34, 25, 9, 0xFF000000);
		// Signal bars and battery
		int x = SCREEN_W - 50;
		for (int i = 0; i < 4; i++) context.fill(x + i * 4, 18 - i * 2 - 2, x + i * 4 + 3, 18, color);
		int bx = SCREEN_W - 30;
		Glass.rounded(context, bx, 11, bx + 19, 19, 2, (color & 0x00FFFFFF) | 0x80000000);
		Glass.rounded(context, bx + 1, 12, bx + 16, 18, 2, color);
		context.fill(bx + 19, 13, bx + 21, 17, (color & 0x00FFFFFF) | 0x80000000);
	}

	// ---------------------------------------------------------------- home

	private void drawHome(GuiGraphics context, float mx, float my) {
		// Wallpaper: a soft diagonal glow
		roundedGradient(context, 0, 0, SCREEN_W, SCREEN_H, RADIUS - BEZEL, 0xFF2A0F3C, 0xFF0A0614);
		context.enableScissor(0, 0, SCREEN_W, SCREEN_H);
		Glass.ellipse(context, -40, 80, 190, 320, 0x70FF5FB8);
		Glass.ellipse(context, 50, 190, 260, 420, 0x605A6CFF);
		context.disableScissor();
		Date now = new Date();
		String date = new SimpleDateFormat("EEEE, MMMM d", Locale.US).format(now);
		context.drawString(font, date, (SCREEN_W - font.width(date)) / 2, 42, 0xE0FFFFFF, false);
		String clock = new SimpleDateFormat("H:mm", Locale.US).format(now);
		scaled(context, clock, (SCREEN_W - (int) (font.width(clock) * 3.2f)) / 2, 54, 3.2f, TEXT);

		App[] apps = App.values();
		int size = 40, gapX = (SCREEN_W - 4 * size) / 5, top = 128;
		for (int i = 0; i < apps.length; i++) {
			App a = apps[i];
			int x = gapX + (i % 4) * (size + gapX), y = top + (i / 4) * (size + 26);
			boolean hot = mx >= x && mx < x + size && my >= y && my < y + size;
			if (hot) Glass.rounded(context, x - 2, y - 2, x + size + 2, y + size + 2, 12, 0x30FFFFFF);
			appIcon(context, a, x, y, size);
			context.drawString(font, a.label, x + (size - font.width(a.label)) / 2, y + size + 4, TEXT, false);
			hits.add(new Hit(x, y, size, size + 14, () -> openApp(a)));
		}
		// A balance widget, because why not
		double worth = phone.bankBalance() + phone.portfolio() + FemboyWare.getModules().get(LedgerModule.class).total();
		int wy = top + 2 * (size + 26) + 6;
		Glass.rounded(context, 12, wy, SCREEN_W - 12, wy + 58, 16, 0x50FFFFFF);
		context.drawString(font, "Net worth", 24, wy + 10, 0xE0FFFFFF, false);
		scaled(context, LedgerModule.usd(worth), 24, wy + 24, 1.6f, TEXT);
		context.drawString(font, "+" + LedgerModule.usd(phone.portfolioChange() + 41_000) + " today", 24, wy + 44, 0xFF7CF2A0, false);
	}

	/**
	 * A vertical gradient filling a rounded rectangle, drawn a pixel row at a time with the corners
	 * cut, since the GUI can only fill square boxes with gradients.
	 */
	private static void roundedGradient(GuiGraphics context, int x1, int y1, int x2, int y2, int radius, int top, int bottom) {
		int d = UiScale.density();
		context.pose().pushMatrix();
		context.pose().scale(1f / d, 1f / d);
		int px1 = x1 * d, px2 = x2 * d, py1 = y1 * d, py2 = y2 * d, r = radius * d;
		for (int py = py1; py < py2; py++) {
			int fromTop = py - py1, fromBottom = py2 - 1 - py;
			int edge = Math.min(fromTop, fromBottom);
			int inset = 0;
			if (edge < r) {
				double dy = r - edge - 0.5;
				inset = (int) Math.round(r - Math.sqrt(Math.max(0, r * r - dy * dy)));
			}
			float t = (py - py1) / (float) Math.max(1, py2 - py1 - 1);
			context.fill(px1 + inset, py, px2 - inset, py + 1, mix(top, bottom, t));
		}
		context.pose().popMatrix();
	}

	private static int mix(int a, int b, float t) {
		int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
		return 0xFF000000 | Math.round(ar + (br - ar) * t) << 16 | Math.round(ag + (bg - ag) * t) << 8 | Math.round(ab + (bb - ab) * t);
	}

	private void openApp(App a) {
		if (a == App.LEDGER) {
			FemboyWare.getModules().get(LedgerModule.class).open();
			return;
		}
		if (a == App.CAMERA) {
			PaparazziModule paparazzi = FemboyWare.getModules().get(PaparazziModule.class);
			paparazzi.toggle();
			say(paparazzi.isEnabled() ? "Paparazzi on" : "Paparazzi off");
			return;
		}
		app = a;
		thread = null;
		scroll = 0;
	}

	private void home() {
		app = null;
		thread = null;
		scroll = 0;
	}

	/** An app's icon: a rounded square in its colour with a simple glyph. Also used by the DM banners. */
	public static void appIcon(GuiGraphics context, App app, int x, int y, int size) {
		Font font = Minecraft.getInstance().font;
		int r = Math.max(3, size * 22 / 100);
		float u = size / 40f;
		switch (app) {
			case MESSAGES -> {
				Glass.rounded(context, x, y, x + size, y + size, r, 0xFF34C759);
				int bx = x + (int) (8 * u), by = y + (int) (10 * u);
				Glass.rounded(context, bx, by, bx + (int) (24 * u), by + (int) (17 * u), (int) (8 * u), 0xFFFFFFFF);
				context.fill(bx + (int) (4 * u), by + (int) (15 * u), bx + (int) (8 * u), by + (int) (20 * u), 0xFFFFFFFF);
			}
			case BANK -> {
				Glass.rounded(context, x, y, x + size, y + size, r, 0xFF0B3D91);
				drawCentered(context, font, "$", x + size / 2f, y + size / 2f, 2.2f * u, 0xFFFFFFFF);
			}
			case STOCKS -> {
				Glass.rounded(context, x, y, x + size, y + size, r, 0xFF101012);
				int[] ys = {28, 24, 26, 18, 20, 12, 14};
				for (int i = 0; i < ys.length - 1; i++) {
					int x1 = x + (int) ((7 + i * 4.4f) * u), y1 = y + (int) (Math.min(ys[i], ys[i + 1]) * u), y2 = y + (int) ((Math.max(ys[i], ys[i + 1]) + 2) * u);
					context.fill(x1, y1, x1 + Math.max(1, (int) (4.4f * u)), y2, 0xFF30D158);
				}
			}
			case ROBLOX -> {
				Glass.rounded(context, x, y, x + size, y + size, r, 0xFF3A3D41);
				context.pose().pushMatrix();
				context.pose().translate(x + size / 2f, y + size / 2f);
				context.pose().rotate(0.26f);
				int h = (int) (12 * u), hole = (int) (3.5f * u);
				context.fill(-h, -h, h, h, 0xFFFFFFFF);
				context.fill(-hole, -hole, hole, hole, 0xFF3A3D41);
				context.pose().popMatrix();
			}
			case LEDGER -> {
				Glass.rounded(context, x, y, x + size, y + size, r, 0xFF000000);
				Glass.rounded(context, x + (int) (8 * u), y + (int) (15 * u), x + (int) (32 * u), y + (int) (25 * u), (int) (4 * u), 0xFFFFFFFF);
				Glass.rounded(context, x + (int) (24 * u), y + (int) (13 * u), x + (int) (34 * u), y + (int) (27 * u), (int) (5 * u), 0xFFB9BBC2);
			}
			case SHOPIFY -> {
				// A green shopping bag
				Glass.rounded(context, x, y, x + size, y + size, r, 0xFF95BF47);
				int bx = x + (int) (11 * u), by = y + (int) (14 * u), bw = (int) (18 * u), bh = (int) (17 * u);
				Glass.rounded(context, bx, by, bx + bw, by + bh, Math.max(1, (int) (3 * u)), 0xFFFFFFFF);
				int hx = x + (int) (15 * u), hw = (int) (10 * u), th = Math.max(1, (int) (2 * u));
				context.fill(hx, y + (int) (9 * u), hx + hw, y + (int) (9 * u) + th, 0xFFFFFFFF);
				context.fill(hx, y + (int) (9 * u), hx + th, by, 0xFFFFFFFF);
				context.fill(hx + hw - th, y + (int) (9 * u), hx + hw, by, 0xFFFFFFFF);
				drawCentered(context, font, "S", bx + bw / 2f, by + bh / 2f + 0.5f, 1.2f * u, 0xFF5E8E3E);
			}
			case VALET -> {
				// A gold key on black
				Glass.rounded(context, x, y, x + size, y + size, r, 0xFF101114);
				Glass.rounded(context, x + (int) (10 * u), y + (int) (12 * u), x + (int) (20 * u), y + (int) (22 * u), (int) (5 * u), 0xFFC9A14A);
				Glass.rounded(context, x + (int) (13 * u), y + (int) (15 * u), x + (int) (17 * u), y + (int) (19 * u), (int) (2 * u), 0xFF101114);
				context.fill(x + (int) (19 * u), y + (int) (16 * u), x + (int) (31 * u), y + (int) (18 * u), 0xFFC9A14A);
				context.fill(x + (int) (26 * u), y + (int) (18 * u), x + (int) (28 * u), y + (int) (22 * u), 0xFFC9A14A);
				context.fill(x + (int) (29 * u), y + (int) (18 * u), x + (int) (31 * u), y + (int) (21 * u), 0xFFC9A14A);
			}
			case CAMERA -> {
				Glass.rounded(context, x, y, x + size, y + size, r, 0xFF8E8E93);
				Glass.rounded(context, x + (int) (7 * u), y + (int) (12 * u), x + (int) (33 * u), y + (int) (30 * u), (int) (4 * u), 0xFF2C2C2E);
				Glass.rounded(context, x + (int) (14 * u), y + (int) (14 * u), x + (int) (26 * u), y + (int) (26 * u), (int) (6 * u), 0xFF5A5A5E);
			}
		}
	}

	private static void drawCentered(GuiGraphics context, Font font, String text, float cx, float cy, float scale, int color) {
		int old = UiScale.pushDensity(Math.round(UiScale.density() * scale));
		context.pose().pushMatrix();
		context.pose().translate(cx, cy);
		context.pose().scale(scale, scale);
		context.drawString(font, text, -font.width(text) / 2, -4, color, false);
		context.pose().popMatrix();
		UiScale.popDensity(old);
	}

	// ---------------------------------------------------------------- messages

	private void drawMessages(GuiGraphics context, float mx, float my) {
		scaled(context, "Messages", 14, 38, 2f, TEXT);
		Glass.rounded(context, 12, 62, SCREEN_W - 12, 78, 8, 0xFF1C1C1E);
		context.drawString(font, "Search", 22, 66, FAINT, false);
		int y = 86;
		if (phone.onlineMessages()) {
			// Everyone online with femboyware, always at the top
			List<Message> all = phone.thread(PhoneModule.EVERYONE);
			boolean hot = mx >= 0 && mx < SCREEN_W && my >= y && my < y + 40;
			if (hot) context.fill(0, y, SCREEN_W, y + 40, 0xFF1C1C1E);
			avatar(context, PhoneModule.EVERYONE, 12, y + 6, 28);
			context.drawString(font, PhoneModule.EVERYONE, 48, y + 8, TEXT, false);
			int count = phone.onlineUsers().size();
			String state = phone.linkConnected() ? count + " online" : "Connecting...";
			context.drawString(font, state, SCREEN_W - 12 - font.width(state), y + 8, phone.linkConnected() ? GREEN_DOT : FAINT, false);
			String preview = all.isEmpty() ? "Everyone with femboyware" : (all.getLast().mine() ? "You: " : "") + all.getLast().text();
			context.drawString(font, font.plainSubstrByWidth(preview, SCREEN_W - 60), 48, y + 20, MUTED, false);
			context.fill(48, y + 39, SCREEN_W, y + 40, 0xFF2C2C2E);
			hits.add(new Hit(0, y, SCREEN_W, 40, () -> openThread(PhoneModule.EVERYONE)));
			y += 40;
		}
		java.util.Set<String> shown = new java.util.HashSet<>();
		for (List<Message> t : phone.threads()) {
			if (y + 40 > SCREEN_H - 18) break;
			Message last = t.getLast();
			if (last.contact().equals(PhoneModule.EVERYONE)) continue;
			shown.add(last.contact().toLowerCase(Locale.ROOT));
			boolean hot = mx >= 0 && mx < SCREEN_W && my >= y && my < y + 40;
			if (hot) context.fill(0, y, SCREEN_W, y + 40, 0xFF1C1C1E);
			avatar(context, last.contact(), 12, y + 6, 28);
			context.drawString(font, last.contact(), 48, y + 8, TEXT, false);
			String when = ago(last.time());
			context.drawString(font, when, SCREEN_W - 12 - font.width(when), y + 8, FAINT, false);
			context.drawString(font, font.plainSubstrByWidth((last.mine() ? "You: " : "") + last.text(), SCREEN_W - 60), 48, y + 20, MUTED, false);
			context.fill(48, y + 39, SCREEN_W, y + 40, 0xFF2C2C2E);
			String contact = last.contact();
			hits.add(new Hit(0, y, SCREEN_W, 40, () -> openThread(contact)));
			y += 40;
		}
		// Other people online with femboyware you haven't texted yet: tap one to start
		List<String> fresh = new ArrayList<>();
		for (String name : phone.onlineUsers()) if (!shown.contains(name.toLowerCase(Locale.ROOT))) fresh.add(name);
		if (!fresh.isEmpty() && y + 30 <= SCREEN_H - 18) {
			context.drawString(font, "ONLINE WITH FEMBOYWARE", 14, y + 8, FAINT, false);
			y += 22;
			for (String name : fresh) {
				if (y + 28 > SCREEN_H - 18) break;
				boolean hot = mx >= 0 && mx < SCREEN_W && my >= y && my < y + 28;
				if (hot) context.fill(0, y, SCREEN_W, y + 28, 0xFF1C1C1E);
				avatar(context, name, 12, y + 4, 20);
				Glass.rounded(context, 28, y + 18, 34, y + 24, 3, GREEN_DOT);
				context.drawString(font, name, 40, y + 10, TEXT, false);
				hits.add(new Hit(0, y, SCREEN_W, 28, () -> openThread(name)));
				y += 28;
			}
		}
	}

	// ---------------------------------------------------------------- Shopify

	/** The price typed for selling what's in your hand, and whether its box has the cursor. */
	private String sellPrice = "";
	private boolean priceFocused;

	/** Your DonutSMP auction sales, a box to sell what you're holding, and the API key. */
	private void drawShopify(GuiGraphics context, float mx, float my) {
		scaled(context, "Shopify", 14, 38, 2f, TEXT);
		int y = 64;
		// Takings
		List<PhoneModule.Sale> sales = phone.sales();
		long dayAgo = System.currentTimeMillis() - 86_400_000L;
		double today = 0, all = 0;
		int todayCount = 0;
		for (PhoneModule.Sale s : sales) {
			all += s.price();
			if (s.time() >= dayAgo) {
				today += s.price();
				todayCount++;
			}
		}
		Glass.rounded(context, 12, y, SCREEN_W - 12, y + 50, 12, 0xFF1C1C1E);
		context.drawString(font, "Last 24 hours", 22, y + 8, MUTED, false);
		scaled(context, LedgerModule.usd(today), 22, y + 20, 1.5f, 0xFF95BF47);
		String countText = todayCount + (todayCount == 1 ? " sale" : " sales");
		context.drawString(font, countText, SCREEN_W - 22 - font.width(countText), y + 8, MUTED, false);
		String allText = "All time " + LedgerModule.usd(all);
		context.drawString(font, font.plainSubstrByWidth(allText, SCREEN_W - 44), 22, y + 38, FAINT, false);
		y += 58;

		// Selling what's in your hand
		ItemStackView held = held();
		Glass.rounded(context, 12, y, SCREEN_W - 12, y + 62, 12, 0xFF1C1C1E);
		context.drawString(font, "Sell item in hand", 22, y + 7, TEXT, false);
		context.drawString(font, font.plainSubstrByWidth(held.label, SCREEN_W - 44), 22, y + 18, held.empty ? FAINT : MUTED, false);
		int fx = 22, fy = y + 32, fw = SCREEN_W - 44 - 58;
		boolean hot = mx >= fx && mx < fx + fw && my >= fy && my < fy + 20;
		Glass.rounded(context, fx, fy, fx + fw, fy + 20, 8, priceFocused ? 0xFF2C2C2E : hot ? 0xFF262628 : 0xFF141416);
		if (sellPrice.isEmpty() && !priceFocused) context.drawString(font, "Price, like 25k", fx + 7, fy + 6, FAINT, false);
		else context.drawString(font, sellPrice, fx + 7, fy + 6, TEXT, false);
		if (priceFocused && (System.currentTimeMillis() / 500) % 2 == 0) {
			int cx = fx + 7 + font.width(sellPrice);
			context.fill(cx, fy + 5, cx + 1, fy + 15, 0xFF95BF47);
		}
		hits.add(new Hit(fx, fy, fw, 20, () -> priceFocused = true));
		button(context, "Sell", fx + fw + 6, fy, 52, mx, my, this::sell);
		y += 70;

		// What's up for sale, then what's sold
		List<PhoneModule.Listing> listed = phone.listings();
		if (!listed.isEmpty()) {
			context.drawString(font, "Listed (" + listed.size() + ")", 14, y, TEXT, false);
			y += 13;
			for (int i = 0; i < listed.size() && i < 3; i++) {
				PhoneModule.Listing l = listed.get(i);
				String price = LedgerModule.usd(l.price());
				// An x to clear one by hand, if it sold without the server saying so, or you took it down
				int room = SCREEN_W - 28 - font.width(price) - 8 - 14;
				boolean rowHot = mx >= 14 && mx < SCREEN_W - 14 && my >= y - 2 && my < y + 10;
				context.drawString(font, font.plainSubstrByWidth((l.count() > 1 ? l.count() + "x " : "") + l.item(), room), 14, y, rowHot ? TEXT : MUTED, false);
				context.drawString(font, price, SCREEN_W - 28 - font.width(price), y, MUTED, false);
				context.drawString(font, "x", SCREEN_W - 20, y, rowHot ? RED : FAINT, false);
				hits.add(new Hit(SCREEN_W - 24, y - 2, 14, 12, () -> {
					phone.removeListing(l);
					say("Listing cleared");
				}));
				y += 12;
			}
			y += 6;
		}
		context.drawString(font, "Sales", 14, y, TEXT, false);
		y += 14;
		if (sales.isEmpty()) {
			context.drawString(font, "Nothing sold yet. When something you", 14, y, FAINT, false);
			context.drawString(font, "listed sells, it shows up here.", 14, y + 10, FAINT, false);
		}
		for (PhoneModule.Sale s : sales) {
			if (y + 24 > SCREEN_H - 22) break;
			String what = (s.count() > 1 ? s.count() + "x " : "") + s.item();
			String price = "+" + LedgerModule.usd(s.price());
			int room = SCREEN_W - 28 - font.width(price) - 8;
			context.drawString(font, font.plainSubstrByWidth(what, room), 14, y, TEXT, false);
			context.drawString(font, price, SCREEN_W - 14 - font.width(price), y, 0xFF95BF47, false);
			context.drawString(font, ago(s.time()) + (s.buyer() != null ? "  ·  to " + s.buyer() : ""), 14, y + 10, FAINT, false);
			context.fill(14, y + 21, SCREEN_W - 14, y + 22, 0xFF2C2C2E);
			y += 26;
		}
	}

	private record ItemStackView(String label, boolean empty) {}

	private ItemStackView held() {
		if (minecraft.player == null || minecraft.player.getMainHandItem().isEmpty()) return new ItemStackView("Hold the item you want to sell", true);
		var stack = minecraft.player.getMainHandItem();
		return new ItemStackView((stack.getCount() > 1 ? stack.getCount() + "x " : "") + stack.getHoverName().getString(), false);
	}

	private void sell() {
		say(phone.sellHand(sellPrice));
		priceFocused = false;
	}

	private void button(GuiGraphics context, String label, int x, int y, int w, float mx, float my, Runnable action) {
		boolean hot = mx >= x && mx < x + w && my >= y && my < y + 20;
		Glass.rounded(context, x, y, x + w, y + 20, 10, hot ? 0xFFA8D25A : 0xFF95BF47);
		context.drawString(font, label, x + (w - font.width(label)) / 2, y + 6, 0xFF0B1A05, false);
		hits.add(new Hit(x, y, w, 20, action));
	}

	// ---------------------------------------------------------------- Valet

	/** Your cars: have one brought round, get in, or send one away. */
	private void drawValet(GuiGraphics context, float mx, float my) {
		var cars = FemboyWare.getModules().get(CarsModule.class);
		scaled(context, "Valet", 14, 38, 2f, TEXT);
		context.drawString(font, "Your cars, brought round", 14, 60, MUTED, false);
		int y = 76;
		y = valetCard(context, "Maybach", "Chauffeur-driven. Get in the back and he'll take you for a drive", y, mx, my,
			() -> {
				cars.summon(CarsModule.Model.SUV);
				say("Your Maybach's on its way");
			}, () -> say(orDone(cars.getInNearest(CarsModule.Model.SUV), "Enjoy the ride")));
		y = valetCard(context, "Jesko", "Drive it yourself: W and S, A and D, sneak to get out", y + 8, mx, my,
			() -> {
				cars.summon(CarsModule.Model.JESKO);
				say("Your Jesko's on its way");
			}, () -> say(orDone(cars.getInNearest(CarsModule.Model.JESKO), "Keys are in it")));
		y += 12;
		context.drawString(font, "Parked", 14, y, TEXT, false);
		y += 13;
		var parked = cars.parkedSummary();
		if (parked.isEmpty()) context.drawString(font, "No cars out. Have one brought round.", 14, y, FAINT, false);
		for (String line : parked) {
			if (y + 12 > SCREEN_H - 50) break;
			context.drawString(font, font.plainSubstrByWidth(line, SCREEN_W - 28), 14, y, MUTED, false);
			y += 12;
		}
		if (!parked.isEmpty()) valetButton(context, "Send the last one away", 14, SCREEN_H - 46, SCREEN_W - 28, mx, my, () -> {
			cars.sendAway();
			say("Sent back to the garage");
		});
	}

	private static String orDone(String problem, String done) {
		return problem == null ? done : problem;
	}

	/** A car's card: its name, a line about it, and buttons to bring it round or get in. */
	private int valetCard(GuiGraphics context, String name, String about, int y, float mx, float my, Runnable bring, Runnable getIn) {
		Glass.rounded(context, 12, y, SCREEN_W - 12, y + 74, 12, 0xFF1C1C1E);
		context.drawString(font, name, 22, y + 8, TEXT, false);
		int line = y + 20;
		for (var part : font.getSplitter().splitLines(about, SCREEN_W - 44, net.minecraft.network.chat.Style.EMPTY)) {
			context.drawString(font, part.getString(), 22, line, MUTED, false);
			line += 10;
		}
		int bw = (SCREEN_W - 44 - 6) / 2;
		valetButton(context, "Bring it", 22, y + 48, bw, mx, my, bring);
		valetButton(context, "Get in", 22 + bw + 6, y + 48, bw, mx, my, getIn);
		return y + 74;
	}

	private void valetButton(GuiGraphics context, String label, int x, int y, int w, float mx, float my, Runnable action) {
		boolean hot = mx >= x && mx < x + w && my >= y && my < y + 20;
		Glass.rounded(context, x, y, x + w, y + 20, 10, hot ? 0xFFE0BC64 : 0xFFC9A14A);
		context.drawString(font, label, x + (w - font.width(label)) / 2, y + 6, 0xFF141210, false);
		hits.add(new Hit(x, y, w, 20, action));
	}

	/** What's being typed in the open thread. */
	private String draft = "";
	private static final int GREEN_DOT = 0xFF30D158;

	private void openThread(String contact) {
		thread = contact;
		draft = "";
	}

	private void sendDraft() {
		if (thread == null) return;
		if (draft.isBlank()) {
			// Nothing typed: made-up contacts get a quick reply like before; real people need something typed
			if (!phone.isReal(thread)) phone.reply(thread);
			return;
		}
		String problem = phone.send(thread, draft);
		if (problem != null) say(problem);
		else {
			draft = "";
			LarpSounds.play(LarpSounds.PHONE_TAP, 1.3f, 0.5f);
		}
	}

	@Override
	public boolean charTyped(net.minecraft.client.input.CharacterEvent input) {
		if (app == App.SHOPIFY && priceFocused) {
			String typed = input.codepointAsString();
			if (typed.matches("[0-9.,kKmMbB$]") && sellPrice.length() < 16) sellPrice += typed;
			return true;
		}
		if (app == App.MESSAGES && thread != null && input.isAllowedChatCharacter()) {
			if (draft.length() < dev.femboyware.manager.PhoneLink.MAX_TEXT) draft += input.codepointAsString();
			return true;
		}
		return super.charTyped(input);
	}

	private void drawThread(GuiGraphics context, float mx, float my) {
		// Header: back, their picture and name
		context.fill(0, 30, SCREEN_W, 72, 0xFF161618);
		context.drawString(font, "< Back", 10, 46, BLUE, false);
		hits.add(new Hit(0, 36, 56, 26, () -> thread = null));
		avatar(context, thread, SCREEN_W / 2 - 12, 32, 24);
		context.drawString(font, thread, (SCREEN_W - font.width(thread)) / 2, 59, TEXT, false);
		if (phone.isReal(thread)) {
			// Who this reaches: everyone online, or whether this person's online now
			boolean everyone = thread.equals(PhoneModule.EVERYONE);
			boolean here = everyone ? phone.linkConnected() : phone.onlineUsers().stream().anyMatch(n -> n.equalsIgnoreCase(thread));
			String note = everyone ? phone.onlineUsers().size() + " online" : here ? "online" : "offline";
			context.drawString(font, note, SCREEN_W - 10 - font.width(note), 46, here ? GREEN_DOT : FAINT, false);
		}

		// Bubbles, newest at the bottom
		List<Message> messages = phone.thread(thread);
		int bottom = SCREEN_H - 50;
		int y = bottom;
		for (int i = messages.size() - 1; i >= 0 && y > 80; i--) {
			Message m = messages.get(i);
			List<FormattedCharSequence> lines = font.split(Component.literal(m.text()), 132);
			int width = 0;
			for (FormattedCharSequence line : lines) width = Math.max(width, font.width(line));
			int h = lines.size() * 10 + 8;
			y -= h + 5;
			if (y < 76) break;
			int x = m.mine() ? SCREEN_W - 12 - width - 16 : 12;
			Glass.rounded(context, x, y, x + width + 16, y + h, 9, m.mine() ? BLUE : 0xFF26262A);
			for (int l = 0; l < lines.size(); l++) context.drawString(font, lines.get(l), x + 8, y + 5 + l * 10, TEXT, false);
		}
		// The input bar: type, then Enter or the send button
		Glass.rounded(context, 12, SCREEN_H - 40, SCREEN_W - 12, SCREEN_H - 20, 10, 0xFF1C1C1E);
		int room = SCREEN_W - 22 - 36;
		if (draft.isEmpty()) context.drawString(font, phone.isReal(thread) ? "iMessage" : "iMessage (send for a quick reply)", 22, SCREEN_H - 34, FAINT, false);
		else {
			// The end of what's typed, if it's longer than the bar
			String visible = draft;
			while (font.width(visible) > room && visible.length() > 1) visible = visible.substring(1);
			context.drawString(font, visible, 22, SCREEN_H - 34, TEXT, false);
		}
		if ((System.currentTimeMillis() / 500) % 2 == 0) {
			String visible = draft;
			while (font.width(visible) > room && visible.length() > 1) visible = visible.substring(1);
			int cx = 22 + (draft.isEmpty() ? 0 : font.width(visible));
			context.fill(cx, SCREEN_H - 36, cx + 1, SCREEN_H - 25, BLUE);
		}
		Glass.rounded(context, SCREEN_W - 32, SCREEN_H - 38, SCREEN_W - 14, SCREEN_H - 22, 8, BLUE);
		context.fill(SCREEN_W - 24, SCREEN_H - 35, SCREEN_W - 22, SCREEN_H - 25, TEXT);
		hits.add(new Hit(SCREEN_W - 34, SCREEN_H - 40, 22, 20, this::sendDraft));
	}

	private void avatar(GuiGraphics context, String name, int x, int y, int size) {
		int[] colors = {0xFF5E5CE6, 0xFFFF9F0A, 0xFF30D158, 0xFFFF375F, 0xFF64D2FF, 0xFFBF5AF2, 0xFFFFD60A};
		int color = colors[Math.floorMod(name.hashCode(), colors.length)];
		Glass.rounded(context, x, y, x + size, y + size, size / 2, color);
		String initial = Character.isLetter(name.charAt(0)) ? name.substring(0, 1).toUpperCase(Locale.ROOT) : "#";
		drawCentered(context, font, initial, x + size / 2f, y + size / 2f + 0.5f, size / 18f, TEXT);
	}

	// ---------------------------------------------------------------- bank

	private void drawBank(GuiGraphics context, float mx, float my) {
		String name = minecraft.player != null ? minecraft.player.getGameProfile().name() : "you";
		context.drawString(font, "Good evening,", 16, 38, MUTED, false);
		scaled(context, name, 16, 50, 1.5f, TEXT);
		// Balance card
		roundedGradient(context, 14, 72, SCREEN_W - 14, 142, 10, 0xFF2B2F8F, 0xFF6A2C91);
		context.drawString(font, "Total balance", 26, 82, 0xD0FFFFFF, false);
		scaled(context, LedgerModule.usd(phone.bankBalance()), 26, 96, 1.7f, TEXT);
		context.drawString(font, "Checking  ••4821    Savings  ••9077", 26, 126, 0xC0FFFFFF, false);
		// Buttons
		String[] actions = {"Send", "Request", "Pay"};
		int bw = (SCREEN_W - 28 - 16) / 3;
		for (int i = 0; i < actions.length; i++) {
			int x = 14 + i * (bw + 8);
			boolean hot = mx >= x && mx < x + bw && my >= 150 && my < 170;
			Glass.rounded(context, x, 150, x + bw, 170, 10, hot ? 0xFF2C2C33 : 0xFF1C1C22);
			context.drawString(font, actions[i], x + (bw - font.width(actions[i])) / 2, 156, TEXT, false);
			String done = i == 1 ? "Request sent" : "Payment sent";
			hits.add(new Hit(x, 150, bw, 20, () -> say(done)));
		}
		// The metal card
		int cy = 180;
		roundedGradient(context, 14, cy, SCREEN_W - 14, cy + 70, 10, 0xFF2A2A2E, 0xFF0E0E10);
		context.drawString(font, "PLATINUM", 26, cy + 10, 0xFFD8D8DE, false);
		Glass.rounded(context, 26, cy + 26, 44, cy + 40, 3, 0xFFD4AF37);
		context.drawString(font, "••••  ••••  ••••  4821", 26, cy + 50, 0xFFC8C8CE, false);
		context.drawString(font, "No limit", SCREEN_W - 26 - font.width("No limit"), cy + 10, MUTED, false);
		// Recent activity
		context.drawString(font, "Recent activity", 16, cy + 82, TEXT, false);
		int y = cy + 96;
		for (Transaction t : phone.bankActivity()) {
			if (y + 24 > SCREEN_H - 16) break;
			Glass.rounded(context, 16, y, 36, y + 20, 10, t.amount() >= 0 ? 0xFF16331F : 0xFF33201C);
			context.drawString(font, t.amount() >= 0 ? "+" : "-", 23, y + 6, t.amount() >= 0 ? GREEN : RED, false);
			String amount = (t.amount() >= 0 ? "+" : "-") + LedgerModule.usd(Math.abs(t.amount()));
			context.drawString(font, font.plainSubstrByWidth(t.label(), SCREEN_W - 16 - font.width(amount) - 50), 44, y + 2, TEXT, false);
			context.drawString(font, ago(t.time()), 44, y + 12, FAINT, false);
			context.drawString(font, amount, SCREEN_W - 16 - font.width(amount), y + 6, t.amount() >= 0 ? GREEN : TEXT, false);
			y += 26;
		}
	}

	// ---------------------------------------------------------------- stocks

	private void drawStocks(GuiGraphics context, float mx, float my) {
		scaled(context, "Investing", 14, 38, 1.6f, TEXT);
		double value = phone.portfolio(), change = phone.portfolioChange();
		scaled(context, LedgerModule.usd(value), 14, 58, 1.9f, TEXT);
		String delta = (change >= 0 ? "+" : "-") + LedgerModule.usd(Math.abs(change)) + String.format(Locale.US, " (%.2f%%) Today", change / (value - change) * 100);
		context.drawString(font, delta, 14, 78, change >= 0 ? GREEN : RED, false);
		drawChart(context, 14, 92, SCREEN_W - 14, 162, change >= 0 ? GREEN : RED);
		// Buy and sell
		int bw = (SCREEN_W - 36) / 2;
		boolean hotBuy = mx >= 14 && mx < 14 + bw && my >= 170 && my < 190;
		Glass.rounded(context, 14, 170, 14 + bw, 190, 10, hotBuy ? 0xFF3BE068 : GREEN);
		context.drawString(font, "Buy", 14 + (bw - font.width("Buy")) / 2, 176, 0xFF000000, false);
		hits.add(new Hit(14, 170, bw, 20, () -> say("Order filled")));
		int sx = 22 + bw;
		Glass.rounded(context, sx, 170, sx + bw, 190, 10, 0xFF1C1C1E);
		context.drawString(font, "Sell", sx + (bw - font.width("Sell")) / 2, 176, TEXT, false);
		hits.add(new Hit(sx, 170, bw, 20, () -> say("Diamond hands only")));

		context.drawString(font, "Stocks", 14, 200, TEXT, false);
		int y = 214;
		for (Position p : PhoneModule.POSITIONS) {
			if (y + 30 > SCREEN_H - 16) break;
			double price = phone.stockPrice(p), pct = phone.stockChange(p) * 100;
			context.drawString(font, p.ticker(), 14, y + 2, TEXT, false);
			context.drawString(font, String.format(Locale.US, "%,.0f shares", p.shares()), 14, y + 13, FAINT, false);
			String worth = LedgerModule.usd(price * p.shares());
			context.drawString(font, worth, SCREEN_W - 70 - font.width(worth), y + 2, TEXT, false);
			context.drawString(font, LedgerModule.usd(price), SCREEN_W - 70 - font.width(LedgerModule.usd(price)), y + 13, FAINT, false);
			int pill = pct >= 0 ? GREEN : RED;
			Glass.rounded(context, SCREEN_W - 62, y + 3, SCREEN_W - 14, y + 19, 5, pill);
			String pctText = String.format(Locale.US, "%s%.2f%%", pct >= 0 ? "+" : "", pct);
			context.drawString(font, pctText, SCREEN_W - 38 - font.width(pctText) / 2, y + 7, TEXT, false);
			context.fill(14, y + 27, SCREEN_W - 14, y + 28, 0xFF1C1C1E);
			y += 30;
		}
	}

	/** A rising line with a little noise, sharp at any size. */
	private void drawChart(GuiGraphics context, int x1, int y1, int x2, int y2, int color) {
		int d = UiScale.density();
		context.pose().pushMatrix();
		context.pose().scale(1f / d, 1f / d);
		int prev = -1;
		long seed = 99;
		double v = 0, min = 0, max = 0;
		int columns = (x2 - x1) * d;
		double[] values = new double[columns];
		java.util.Random random = new java.util.Random(seed);
		for (int i = 0; i < columns; i++) {
			v += random.nextGaussian() * 0.6 + 0.08;
			values[i] = v;
			min = Math.min(min, v);
			max = Math.max(max, v);
		}
		for (int i = 0; i < columns; i++) {
			int px = x1 * d + i;
			int py = (int) (y2 * d - (values[i] - min) / (max - min) * (y2 - y1) * d);
			context.fillGradient(px, py, px + 1, y2 * d, (color & 0x00FFFFFF) | 0x40000000, color & 0x00FFFFFF);
			int from = prev < 0 ? py : Math.min(prev, py), to = prev < 0 ? py : Math.max(prev, py);
			context.fill(px, from - d / 2, px + 1, to + Math.max(1, d / 2), color);
			prev = py;
		}
		context.pose().popMatrix();
	}

	// ---------------------------------------------------------------- roblox

	/** Which limiteds you have on, from the Cosmetics module: your horns, the Korblox leg, headless. */
	private List<Limited> wearing() {
		CosmeticsModule cosmetics = FemboyWare.getModules().get(CosmeticsModule.class);
		List<String> ids = new ArrayList<>();
		switch (cosmetics.hornStyle()) {
			case FIERY -> ids.add("fiery_horns");
			case FROZEN -> ids.add("frozen_horns");
			case POISONED -> ids.add("poisoned_horns");
			default -> {
			}
		}
		if (cosmetics.korbloxOn()) ids.add("korblox");
		if (cosmetics.headlessOn()) ids.add("headless");
		List<Limited> list = new ArrayList<>();
		for (String id : ids) {
			for (Limited limited : PhoneModule.LIMITEDS) {
				if (limited.id().equals(id)) list.add(limited);
			}
		}
		return list;
	}

	private static final int RBX_BG = 0xFF111216, RBX_PANEL = 0xFF1D1F24, RBX_TILE = 0xFF2B2D33, RBX_TEXT = 0xFFF2F4F5, RBX_MUTED = 0xFFA6A9B0, RBX_GREEN = 0xFF00B06F;

	/** The avatar's name for its pictures: which horns, head or not, Korblox or not. */
	private static String avatarKey() {
		CosmeticsModule cosmetics = FemboyWare.getModules().get(CosmeticsModule.class);
		String horns = switch (cosmetics.hornStyle()) {
			case FIERY -> "fiery";
			case FROZEN -> "frozen";
			case POISONED -> "poisoned";
			default -> "none";
		};
		return horns + "_" + (cosmetics.headlessOn() ? "headless" : "head") + "_" + (cosmetics.korbloxOn() ? "korblox" : "leg");
	}

	/**
	 * Your Roblox profile, laid out like the app's: header with your headshot, name and Premium
	 * badge, friends and followers, Edit Avatar, About, Currently Wearing, your avatar, and your
	 * collection of limiteds. Scrolls with the mouse wheel.
	 */
	private void drawRoblox(GuiGraphics context, float mx, float my) {
		String name = minecraft.player != null ? minecraft.player.getGameProfile().name() : "Player";
		List<Limited> wearing = wearing();
		context.fill(0, 30, SCREEN_W, SCREEN_H - 12, RBX_BG);
		// Top bar: back and more
		context.drawString(font, "<", 12, 40, RBX_TEXT, false);
		hits.add(new Hit(0, 32, 30, 20, this::home));
		for (int i = 0; i < 3; i++) Glass.rounded(context, SCREEN_W - 26 + i * 5, 43, SCREEN_W - 23 + i * 5, 46, 2, RBX_TEXT);

		context.enableScissor(0, 54, SCREEN_W, SCREEN_H - 14);
		int y = 56 - scroll;
		// Header: headshot with an online dot, display name with the Premium badge, @username
		context.blit(RenderPipelines.GUI_TEXTURED, roblox("headshot_" + avatarKey()), 12, y, 0, 0, 58, 58, 128, 128, 128, 128);
		Glass.rounded(context, 56, y + 44, 68, y + 56, 6, RBX_BG);
		Glass.rounded(context, 58, y + 46, 66, y + 54, 4, RBX_GREEN);
		scaled(context, name, 80, y + 10, 1.35f, RBX_TEXT);
		int badgeX = 80 + (int) (font.width(name) * 1.35f) + 5;
		premium(context, badgeX, y + 11);
		context.drawString(font, "@" + name, 80, y + 26, RBX_MUTED, false);
		context.drawString(font, "Online", 80, y + 38, RBX_GREEN, false);
		y += 66;
		// Counts
		String[][] stats = {{"200", "Friends"}, {"2.3M", "Followers"}, {"14", "Following"}};
		for (int i = 0; i < 3; i++) {
			int x = 12 + i * (SCREEN_W - 24) / 3;
			context.drawString(font, stats[i][0], x, y, RBX_TEXT, false);
			context.drawString(font, stats[i][1], x, y + 10, RBX_MUTED, false);
		}
		y += 26;
		// Edit Avatar, and a square share button
		int bw = SCREEN_W - 24 - 26;
		boolean hot = mx >= 12 && mx < 12 + bw && my >= y && my < y + 20;
		Glass.rounded(context, 12, y, 12 + bw, y + 20, 6, hot ? 0xFF3A3D45 : RBX_TILE);
		context.drawString(font, "Edit Avatar", 12 + (bw - font.width("Edit Avatar")) / 2, y + 6, RBX_TEXT, false);
		hits.add(new Hit(12, y, bw, 20, () -> say("Your avatar is perfect already")));
		Glass.rounded(context, SCREEN_W - 32, y, SCREEN_W - 12, y + 20, 6, RBX_TILE);
		context.fill(SCREEN_W - 23, y + 5, SCREEN_W - 21, y + 13, RBX_TEXT);
		context.fill(SCREEN_W - 26, y + 7, SCREEN_W - 18, y + 8, RBX_TEXT);
		context.fill(SCREEN_W - 27, y + 12, SCREEN_W - 17, y + 15, RBX_TEXT);
		y += 30;

		// About
		y = section(context, "About", y);
		context.drawString(font, "limited collector. billionaire.", 12, y, RBX_MUTED, false);
		context.drawString(font, "dm for trades, no lowballs", 12, y + 10, RBX_MUTED, false);
		context.drawString(font, "Joined 3/14/2014  ·  Place Visits 41.2M", 12, y + 24, 0xFF7C8088, false);
		y += 40;

		// Currently Wearing: a row of item tiles
		y = section(context, "Currently Wearing", y);
		int tile = (SCREEN_W - 24 - 3 * 6) / 4;
		if (wearing.isEmpty()) {
			context.drawString(font, "Turn on Cosmetics to wear items", 12, y, RBX_MUTED, false);
			y += 14;
		} else {
			for (int i = 0; i < Math.min(4, wearing.size()); i++) {
				int x = 12 + i * (tile + 6);
				Glass.rounded(context, x, y, x + tile, y + tile, 6, RBX_TILE);
				context.blit(RenderPipelines.GUI_TEXTURED, roblox(wearing.get(i).id()), x + 3, y + 3, 0, 0, tile - 6, tile - 6, 150, 150, 150, 150);
			}
			y += tile + 10;
		}

		// Your avatar on its stage: your real skin, front on, wearing your cosmetics (drawn after the phone, see render)
		roundedGradient(context, 12, y, SCREEN_W - 12, y + 150, 8, 0xFF2A2D35, 0xFF15161A);
		avatarBox = new int[]{phoneX + BEZEL + SCREEN_W / 2 - 50, phoneY + BEZEL + y + 4, phoneX + BEZEL + SCREEN_W / 2 + 50, phoneY + BEZEL + y + 146,
			phoneY + BEZEL + 54, phoneY + BEZEL + SCREEN_H - 14};
		Glass.rounded(context, SCREEN_W - 50, y + 128, SCREEN_W - 18, y + 144, 8, 0xC0000000);
		context.drawString(font, "3D", SCREEN_W - 40, y + 132, RBX_TEXT, false);
		y += 160;

		// Collections: every limited, three across, with its tag and value
		long total = 0;
		for (Limited limited : PhoneModule.LIMITEDS) total += limited.rap();
		context.drawString(font, "Collections", 12, y, RBX_TEXT, false);
		String value = "R$ " + compact(total);
		robuxIcon(context, SCREEN_W - 22 - font.width(value), y);
		context.drawString(font, value, SCREEN_W - 12 - font.width(value), y, RBX_GREEN, false);
		y += 14;
		int cw = (SCREEN_W - 24 - 2 * 6) / 3;
		for (int i = 0; i < PhoneModule.LIMITEDS.size(); i++) {
			Limited limited = PhoneModule.LIMITEDS.get(i);
			int x = 12 + (i % 3) * (cw + 6), cy = y + (i / 3) * (cw + 30);
			Glass.rounded(context, x, cy, x + cw, cy + cw, 6, RBX_TILE);
			context.blit(RenderPipelines.GUI_TEXTURED, roblox(limited.id()), x + 4, cy + 4, 0, 0, cw - 8, cw - 8, 150, 150, 150, 150);
			Glass.rounded(context, x + 3, cy + cw - 11, x + 15, cy + cw - 3, 2, RBX_GREEN);
			context.drawString(font, "U", x + 6, cy + cw - 11, 0xFFFFFFFF, false);
			context.drawString(font, font.plainSubstrByWidth(limited.name(), cw), x, cy + cw + 3, RBX_TEXT, false);
			robuxIcon(context, x, cy + cw + 14);
			context.drawString(font, compact(limited.rap()), x + 10, cy + cw + 14, RBX_MUTED, false);
		}
		y += ((PhoneModule.LIMITEDS.size() + 2) / 3) * (cw + 30);
		context.disableScissor();
		maxScroll = Math.max(0, y + scroll - (SCREEN_H - 20));
	}

	/** A section title with an arrow to its right; returns the y for what goes under it. */
	private int section(GuiGraphics context, String title, int y) {
		context.drawString(font, title, 12, y, RBX_TEXT, false);
		context.drawString(font, ">", SCREEN_W - 18, y, RBX_MUTED, false);
		return y + 14;
	}

	/** Roblox Premium's mark: a small rounded square with a P cut out of it. */
	private void premium(GuiGraphics context, int x, int y) {
		Glass.rounded(context, x, y, x + 10, y + 10, 3, RBX_TEXT);
		context.fill(x + 3, y + 2, x + 5, y + 8, RBX_BG);
		context.fill(x + 3, y + 2, x + 7, y + 4, RBX_BG);
		context.fill(x + 5, y + 2, x + 7, y + 6, RBX_BG);
		context.fill(x + 3, y + 4, x + 7, y + 6, RBX_BG);
		context.fill(x + 5, y + 4, x + 6, y + 5, RBX_TEXT);
		context.fill(x + 4, y + 3, x + 6, y + 4, RBX_TEXT);
	}

	private static String compact(long value) {
		if (value >= 1_000_000) return String.format(Locale.US, "%.2fM", value / 1e6);
		if (value >= 1_000) return String.format(Locale.US, "%.1fK", value / 1e3);
		return String.valueOf(value);
	}

	/**
	 * Your avatar dressed in your Cosmetics: a 3D render of an R6 body for every mix of horns,
	 * headless and Korblox, made from the same meshes the Cosmetics module wears.
	 */
	private void drawAvatar(GuiGraphics context, int cx, int top) {
		String name = "avatar_" + avatarKey();
		int size = 112;
		context.blit(RenderPipelines.GUI_TEXTURED, roblox(name), cx - size / 2, top - 2, 0, 0, size, size, 300, 300, 300, 300);
	}

	private int maxScroll;

	/** A small hexagon-ish Robux mark. */
	private void robuxIcon(GuiGraphics context, int x, int y) {
		Glass.rounded(context, x, y, x + 8, y + 8, 2, 0xFF02B757);
		context.fill(x + 2, y + 2, x + 6, y + 6, RBX_BG);
		context.fill(x + 3, y + 3, x + 5, y + 5, 0xFF02B757);
	}

	// ---------------------------------------------------------------- helpers

	private void say(String message) {
		status = message;
		statusAt = System.currentTimeMillis();
	}

	/** Text drawn bigger, using the font made for that size so it stays sharp. */
	private void scaled(GuiGraphics context, String text, int x, int y, float scale, int color) {
		int old = UiScale.pushDensity(Math.round(UiScale.density() * scale));
		context.pose().pushMatrix();
		context.pose().translate(x, y);
		context.pose().scale(scale, scale);
		context.drawString(font, text, 0, 0, color, false);
		context.pose().popMatrix();
		UiScale.popDensity(old);
	}

	private static String ago(long time) {
		long s = (System.currentTimeMillis() - time) / 1000;
		if (s < 60) return "now";
		if (s < 3600) return s / 60 + "m";
		if (s < 86_400) return s / 3600 + "h";
		return new SimpleDateFormat("MMM d", Locale.US).format(new Date(time));
	}

	private static float ease(float t) {
		return 1 - (1 - t) * (1 - t) * (1 - t);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
		if (click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			double canvasX = click.x() / scale(), canvasY = click.y() / scale();
			// The first two hits are in phone coordinates; the rest are on the screen inside the bezel
			double sx = canvasX - phoneX - BEZEL, sy = canvasY - phoneY - BEZEL;
			for (int i = hits.size() - 1; i >= 0; i--) {
				Hit hit = hits.get(i);
				boolean inPhone = i < 2;
				double hx = inPhone ? canvasX - phoneX : sx, hy = inPhone ? canvasY - phoneY : sy;
				if (hit.w() > 0 && hit.contains(hx, hy)) {
					// A tick for anything on the screen; putting the phone away is silent
					if (!inPhone) LarpSounds.play(LarpSounds.PHONE_TAP, 0.5f);
					hit.action().run();
					return true;
				}
			}
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		if (app == App.ROBLOX) {
			scroll = Mth.clamp(scroll - (int) (vertical * 24), 0, maxScroll);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
	}

	@Override
	public boolean keyPressed(KeyEvent input) {
		if (app == App.SHOPIFY && priceFocused) {
			int key = input.key();
			if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
				sell();
				return true;
			}
			if (key == GLFW.GLFW_KEY_BACKSPACE) {
				if (!sellPrice.isEmpty()) sellPrice = sellPrice.substring(0, sellPrice.length() - 1);
				return true;
			}
			if (key == GLFW.GLFW_KEY_ESCAPE) {
				priceFocused = false;
				return true;
			}
			return true;
		}
		if (app == App.MESSAGES && thread != null) {
			int key = input.key();
			if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
				sendDraft();
				return true;
			}
			if (key == GLFW.GLFW_KEY_BACKSPACE) {
				if (input.hasControlDown()) {
					// A whole word at a time
					String trimmed = draft.stripTrailing();
					int cut = trimmed.lastIndexOf(' ');
					draft = cut < 0 ? "" : trimmed.substring(0, cut + 1);
				} else if (!draft.isEmpty()) draft = draft.substring(0, draft.offsetByCodePoints(draft.length(), -1));
				return true;
			}
			if (input.hasControlDown() && key == GLFW.GLFW_KEY_V) {
				draft = dev.femboyware.manager.PhoneLink.clean(draft + minecraft.keyboardHandler.getClipboard());
				return true;
			}
			// Typing shouldn't also walk you about or open other things
			if (key != GLFW.GLFW_KEY_ESCAPE) return super.keyPressed(input) || true;
		}
		if (input.key() == GLFW.GLFW_KEY_ESCAPE && app != null) {
			if (thread != null) thread = null;
			else home();
			return true;
		}
		return super.keyPressed(input);
	}
}
