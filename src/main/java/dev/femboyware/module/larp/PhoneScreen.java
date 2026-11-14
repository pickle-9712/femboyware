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
		MESSAGES("Messages"), TELEGRAM("Telegram"), DISCORD("Discord"), X("X"), BANK("Bank"), COINBASE("Coinbase"), VOICE("Voice"), PUMP("pump.fun"), ROBLOX("Roblox"), LEDGER("Ledger"), SPOTIFY("Spotify"), CAMERA("Camera"), SHOPIFY("Shopify"), VALET("Valet"), TV("TV"), STORE("App Store");

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
		var skin = minecraft.player.getSkin();
		boolean slim = skin.model() == net.minecraft.world.entity.player.PlayerModelType.SLIM;
		int x1 = Math.round(avatarBox[0] * scale), y1 = Math.round(avatarBox[1] * scale), x2 = Math.round(avatarBox[2] * scale), y2 = Math.round(avatarBox[3] * scale);
		float modelScale = 0.97f * (y2 - y1) / 2.125f;
		context.enableScissor(x1, Math.round(top * scale), x2, Math.round(bottom * scale));
		//? if >=26.1 {
		/*if (wideModel == null) {
			wideModel = new net.minecraft.client.model.Model.Simple(minecraft.getEntityModels().bakeLayer(net.minecraft.client.model.geom.ModelLayers.PLAYER),
				net.minecraft.client.renderer.rendertype.RenderTypes::entityTranslucent);
			slimModel = new net.minecraft.client.model.Model.Simple(minecraft.getEntityModels().bakeLayer(net.minecraft.client.model.geom.ModelLayers.PLAYER_SLIM),
				net.minecraft.client.renderer.rendertype.RenderTypes::entityTranslucent);
		}
		context.skin((net.minecraft.client.model.Model.Simple) (slim ? slimModel : wideModel), skin.body().texturePath(), modelScale, -5, AVATAR_YAW, -1.0625f, x1, y1, x2, y2);
		*///?} else {
		if (wideModel == null) {
			wideModel = new net.minecraft.client.model.player.PlayerModel(minecraft.getEntityModels().bakeLayer(net.minecraft.client.model.geom.ModelLayers.PLAYER), false);
			slimModel = new net.minecraft.client.model.player.PlayerModel(minecraft.getEntityModels().bakeLayer(net.minecraft.client.model.geom.ModelLayers.PLAYER_SLIM), true);
		}
		context.submitSkinRenderState((net.minecraft.client.model.player.PlayerModel) (slim ? slimModel : wideModel), skin.body().texturePath(), modelScale, -5, AVATAR_YAW, -1.0625f, x1, y1, x2, y2);
		//?}
		context.disableScissor();
	}

	/** How far the avatar is turned: front on, a little to the side like the main menu's. */
	private static final float AVATAR_YAW = 25;

	/** Your Minecraft face (with its hat layer), {@code size} square: the Roblox app's headshot. */
	private void face(GuiGraphics context, int x, int y, int size) {
		if (minecraft.player == null) return;
		Identifier tex = minecraft.player.getSkin().body().texturePath();
		context.blit(RenderPipelines.GUI_TEXTURED, tex, x, y, 8, 8, size, size, 8, 8, 64, 64);
		context.blit(RenderPipelines.GUI_TEXTURED, tex, x, y, 40, 8, size, size, 8, 8, 64, 64);
	}

	/** Where the avatar goes this frame on the canvas (x1 y1 x2 y2) and the screen's visible band (top, bottom), or null. */
	private int[] avatarBox;
	private Object wideModel, slimModel;

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
		// The real Spotify is only read while its app is open
		if (app == App.SPOTIFY) dev.femboyware.module.hud.Spotify.acquire(this);
		else dev.femboyware.module.hud.Spotify.release(this);
		tickDownloads();
		boolean dark = app != null;
		Glass.rounded(context, 0, 0, SCREEN_W, SCREEN_H, RADIUS - BEZEL, dark ? appBackground() : 0xFF000000);
		if (app == null) drawHome(context, mx, my);
		else {
			switch (app) {
				case MESSAGES -> {
					if (thread == null) drawMessages(context, mx, my);
					else drawThread(context, mx, my);
				}
				case TELEGRAM -> {
					if (thread == null) drawTelegram(context, mx, my);
					else drawThread(context, mx, my);
				}
				case X -> drawX(context, mx, my);
				case DISCORD -> drawDiscord(context, mx, my);
				case BANK -> drawBank(context, mx, my);
				case PUMP -> drawPump(context, mx, my);
				case COINBASE -> drawCoinbase(context, mx, my);
				case VOICE -> drawVoice(context, mx, my);
				case ROBLOX -> drawRoblox(context, mx, my);
				case SHOPIFY -> drawShopify(context, mx, my);
				case VALET -> drawValet(context, mx, my);
				case TV -> drawTv(context, mx, my);
				case CAMERA -> drawCamera(context, mx, my);
				case SPOTIFY -> drawSpotify(context, mx, my);
				case STORE -> drawStore(context, mx, my);
				default -> {
				}
			}
		}
		drawStatusBar(context, app == null || app == App.ROBLOX || app == App.PUMP || app == App.BANK || app == App.MESSAGES || app == App.TELEGRAM || app == App.X || app == App.DISCORD || app == App.SHOPIFY || app == App.VALET || app == App.TV || app == App.COINBASE || app == App.VOICE || app == App.CAMERA || app == App.SPOTIFY || app == App.STORE);
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
			case TELEGRAM -> 0xFF0E1621;
			case DISCORD -> 0xFF313338;
			case SPOTIFY -> 0xFF121212;
			case PUMP -> PF_BG;
			case COINBASE -> CB_BG;
			case VOICE -> GV_BG;
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

		List<App> apps = new ArrayList<>();
		for (App a : App.values()) if (phone.installed(a) || DOWNLOADS.containsKey(a)) apps.add(a);
		int size = 40, gapX = (SCREEN_W - 4 * size) / 5, top = 128;
		for (int i = 0; i < apps.size(); i++) {
			App a = apps.get(i);
			int x = gapX + (i % 4) * (size + gapX), y = top + (i / 4) * (size + 22);
			float progress = downloadProgress(a);
			if (progress >= 0) {
				// Still downloading: dimmed, with its ring, and "Loading..." under it
				appIcon(context, a, x, y, size);
				Glass.rounded(context, x, y, x + size, y + size, Math.max(3, size * 22 / 100), 0xA0000000);
				progressRing(context, x + size / 2f, y + size / 2f, 10, progress, 0x60FFFFFF, TEXT);
				String loading = progress < 0.15f ? "Waiting..." : "Loading...";
				context.drawString(font, loading, x + (size - font.width(loading)) / 2, y + size + 4, 0xB0FFFFFF, false);
				continue;
			}
			boolean hot = mx >= x && mx < x + size && my >= y && my < y + size;
			if (hot) Glass.rounded(context, x - 2, y - 2, x + size + 2, y + size + 2, 12, 0x30FFFFFF);
			appIcon(context, a, x, y, size);
			String label = font.plainSubstrByWidth(a.label, size + gapX - 2);
			context.drawString(font, label, x + (size - font.width(label)) / 2, y + size + 4, TEXT, false);
			hits.add(new Hit(x, y, size, size + 14, () -> openApp(a)));
		}
		// A balance widget, because why not
		double worth = phone.bankBalance() + phone.portfolio() + FemboyWare.getModules().get(LedgerModule.class).total();
		int wy = top + (apps.size() + 3) / 4 * (size + 22) + 4;
		Glass.rounded(context, 12, wy, SCREEN_W - 12, wy + 58, 16, 0x50FFFFFF);
		context.drawString(font, "Net worth", 24, wy + 10, 0xE0FFFFFF, false);
		scaled(context, FbiRaidModule.assetsFrozen() ? "Frozen" : LedgerModule.usd(worth), 24, wy + 24, 1.6f, FbiRaidModule.assetsFrozen() ? 0xFFFF8A80 : TEXT);
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
		app = a;
		gvDialing = false;
		gvCalling = null;
		cbSheet = -1;
		cbDetail = -1;
		xFocused = false;
		searchFocused = false;
		search = "";
		thread = null;
		discordChannel = null;
		scroll = 0;
		wiring = false;
		if (a == App.SPOTIFY) spotifyReset();
		if (a == App.STORE) {
			asDetail = null;
			asScroll = 0;
		}
	}

	/** Opens straight onto the call that's ringing (or going). */
	public void showCall() {
		app = App.VOICE;
	}

	private void home() {
		app = null;
		cbSheet = -1;
		cbDetail = -1;
		xFocused = false;
		searchFocused = false;
		wiring = false;
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
			case DISCORD -> {
				Glass.rounded(context, x, y, x + size, y + size, r, DC_BLURPLE);
				appIconGlyph(context, x, y, size);
			}
			case X -> {
				Glass.rounded(context, x, y, x + size, y + size, r, 0xFF000000);
				drawCentered(context, font, "X", x + size / 2f, y + size / 2f + 0.5f, 2.4f * u, 0xFFFFFFFF);
			}
			case TELEGRAM -> {
				// The paper plane on Telegram's blue
				roundedGradient(context, x, y, x + size, y + size, r, 0xFF37BBFE, 0xFF1E96D4);
				context.blit(RenderPipelines.GUI_TEXTURED, TELEGRAM_LOGO, x, y, 0, 0, size, size, 128, 128, 128, 128);
			}
			case COINBASE -> {
				Glass.rounded(context, x, y, x + size, y + size, r, CB_BLUE);
				int logo = size * 58 / 100;
				coinbaseLogo(context, x + (size - logo) / 2, y + (size - logo) / 2, logo, CB_BLUE);
			}
			case VOICE -> {
				Glass.rounded(context, x, y, x + size, y + size, r, 0xFFFFFFFF);
				int bubble = size * 62 / 100;
				voiceIcon(context, x + (size - bubble) / 2, y + (size - bubble) / 2, bubble);
			}
			case BANK -> {
				Glass.rounded(context, x, y, x + size, y + size, r, 0xFF0B3D91);
				drawCentered(context, font, "$", x + size / 2f, y + size / 2f, 2.2f * u, 0xFFFFFFFF);
			}
			case PUMP -> {
				Glass.rounded(context, x, y, x + size, y + size, r, PF_BG);
				int logo = size * 70 / 100;
				pumpLogo(context, x + (size - logo) / 2, y + (size - logo) / 2, logo);
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
			case STORE -> {
				roundedGradient(context, x, y, x + size, y + size, r, 0xFF19C9FB, 0xFF1B6EEA);
				float cx = x + size / 2f, cy = y + size / 2f;
				float len = 22 * u, th = Math.max(2, 3.2f * u);
				for (int side = -1; side <= 1; side += 2) {
					context.pose().pushMatrix();
					context.pose().translate(cx + side * 4.6f * u, cy);
					context.pose().rotate(-side * 0.46f);
					context.fill(Math.round(-th / 2), Math.round(-len / 2), Math.round(th / 2), Math.round(len / 2), 0xFFFFFFFF);
					context.pose().popMatrix();
				}
				context.fill(Math.round(cx - 11 * u), Math.round(cy + 4 * u), Math.round(cx + 11 * u), Math.round(cy + 4 * u + th), 0xFFFFFFFF);
			}
			case TV -> {
				// A little flatscreen glowing pink, on a stand
				Glass.rounded(context, x, y, x + size, y + size, r, 0xFF16161C);
				Glass.rounded(context, x + (int) (6 * u), y + (int) (9 * u), x + (int) (34 * u), y + (int) (27 * u), (int) (2 * u), 0xFF050507);
				Glass.rounded(context, x + (int) (8 * u), y + (int) (11 * u), x + (int) (32 * u), y + (int) (25 * u), (int) (2 * u), 0xFFE0559A);
				context.fill(x + (int) (14 * u), y + (int) (14 * u), x + (int) (17 * u), y + (int) (22 * u), 0xFFFFFFFF);
				context.fill(x + (int) (17 * u), y + (int) (16 * u), x + (int) (20 * u), y + (int) (20 * u), 0xFFFFFFFF);
				context.fill(x + (int) (19 * u), y + (int) (27 * u), x + (int) (21 * u), y + (int) (31 * u), 0xFF5A5A62);
				context.fill(x + (int) (14 * u), y + (int) (31 * u), x + (int) (26 * u), y + (int) (33 * u), 0xFF5A5A62);
			}
			case SPOTIFY -> {
				Glass.rounded(context, x, y, x + size, y + size, r, 0xFF000000);
				int logo = size * 72 / 100;
				spotifyLogo(context, x + (size - logo) / 2, y + (size - logo) / 2, logo, 0xFF000000);
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
		Glass.rounded(context, 12, 62, SCREEN_W - 12, 78, 8, searchFocused ? 0xFF2C2C2E : 0xFF1C1C1E);
		if (search.isEmpty() && !searchFocused) context.drawString(font, "Search", 22, 66, FAINT, false);
		else {
			context.drawString(font, search, 22, 66, TEXT, false);
			if (searchFocused && (System.currentTimeMillis() / 500) % 2 == 0) context.fill(22 + font.width(search), 65, 23 + font.width(search), 75, BLUE);
		}
		hits.add(new Hit(12, 62, SCREEN_W - 24, 16, () -> searchFocused = true));
		int y = 86;
		String query = search.trim().toLowerCase(Locale.ROOT);
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
			if (last.contact().equals(PhoneModule.EVERYONE) || PhoneModule.isTelegram(last.contact())) continue;
			if (!query.isEmpty() && !last.contact().toLowerCase(Locale.ROOT).contains(query) && t.stream().noneMatch(m -> m.text().toLowerCase(Locale.ROOT).contains(query))) continue;
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
		for (String name : phone.onlineUsers()) if (!shown.contains(name.toLowerCase(Locale.ROOT)) && (query.isEmpty() || name.toLowerCase(Locale.ROOT).contains(query))) fresh.add(name);
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

	// ---------------------------------------------------------------- Discord

	private static final int DC_BG = 0xFF313338, DC_SIDE = 0xFF2B2D31, DC_RAIL = 0xFF1E1F22, DC_TEXT = 0xFFDBDEE1, DC_MUTED = 0xFF949BA4, DC_BLURPLE = 0xFF5865F2;
	private int discordServer;
	private String discordChannel;

	private String discordKey() {
		return PhoneModule.DISCORD_SERVERS[discordServer][0] + "/" + discordChannel;
	}

	private static int nameColor(String name) {
		int[] colors = {0xFFF47B67, 0xFF3BA55C, 0xFFFAA61A, 0xFFEB459E, 0xFF5865F2, 0xFF1ABC9C, 0xFFE67E22};
		return colors[Math.floorMod(name.hashCode(), colors.length)];
	}

	/** Discord: your servers down the left, the open server's channels, and you at the bottom. */
	private void drawDiscord(GuiGraphics context, float mx, float my) {
		if (discordChannel != null) {
			drawDiscordChannel(context, mx, my);
			return;
		}
		context.fill(0, 30, 44, SCREEN_H - 12, DC_RAIL);
		context.fill(44, 30, SCREEN_W, SCREEN_H - 12, DC_SIDE);
		// The rail: direct messages, then each server
		Glass.rounded(context, 8, 36, 36, 64, 10, DC_BLURPLE);
		appIconGlyph(context, 8, 36, 28);
		context.fill(14, 70, 30, 71, 0xFF35363C);
		for (int i = 0; i < PhoneModule.DISCORD_SERVERS.length; i++) {
			String[] server = PhoneModule.DISCORD_SERVERS[i];
			int y = 76 + i * 36;
			boolean picked = i == discordServer;
			Glass.rounded(context, 8, y, 36, y + 28, picked ? 10 : 14, picked ? DC_BLURPLE : 0xFF313338);
			String initials = server[0].replaceAll("(?:^|\\s)(\\S)\\S*", "$1").toUpperCase(Locale.ROOT);
			context.drawString(font, initials, 22 - font.width(initials) / 2, y + 10, 0xFFFFFFFF, false);
			if (picked) Glass.rounded(context, 0, y + 4, 4, y + 24, 2, 0xFFFFFFFF);
			boolean unread = false;
			for (int c = 1; c < server.length; c++) unread |= phone.discordUnread(server[0] + "/" + server[c]);
			if (!picked && unread) Glass.rounded(context, 0, y + 11, 3, y + 17, 2, 0xFFFFFFFF);
			int index = i;
			hits.add(new Hit(8, y, 28, 28, () -> discordServer = index));
		}
		// The server: its name and text channels
		String[] server = PhoneModule.DISCORD_SERVERS[discordServer];
		context.drawString(font, server[0], 54, 40, 0xFFFFFFFF, false);
		context.drawString(font, "v", SCREEN_W - 16, 40, DC_MUTED, false);
		context.fill(44, 56, SCREEN_W, 57, 0xFF1F2023);
		context.drawString(font, "TEXT CHANNELS", 54, 66, DC_MUTED, false);
		int y = 80;
		for (int c = 1; c < server.length; c++) {
			String channel = server[c], key = server[0] + "/" + channel;
			boolean unread = phone.discordUnread(key), hot = mx >= 48 && mx < SCREEN_W - 6 && my >= y && my < y + 20;
			if (hot) Glass.rounded(context, 48, y, SCREEN_W - 6, y + 20, 4, 0xFF35373C);
			context.drawString(font, "#", 56, y + 6, DC_MUTED, false);
			context.drawString(font, channel, 66, y + 6, unread ? 0xFFFFFFFF : DC_MUTED, false);
			if (unread) Glass.rounded(context, 44, y + 7, 48, y + 13, 2, 0xFFFFFFFF);
			hits.add(new Hit(48, y, SCREEN_W - 54, 20, () -> {
				discordChannel = channel;
				draft = "";
			}));
			y += 22;
		}
		int by = SCREEN_H - 50;
		if (server[0].equals(PhoneModule.REAL_SERVER)) {
			// Who's here: everyone online with femboyware right now
			List<String> online = phone.onlineUsers();
			String header = !phone.onlineMessages() ? "TURN ON ONLINE MESSAGES" : !phone.linkConnected() ? "CONNECTING..." : "ONLINE — " + (online.size() + 1);
			context.drawString(font, header, 54, y + 10, DC_MUTED, false);
			y += 24;
			for (String name : online) {
				if (y + 20 > by - 4) break;
				avatar(context, name, 54, y, 16);
				Glass.rounded(context, 65, y + 11, 71, y + 17, 3, DC_SIDE);
				Glass.rounded(context, 66, y + 12, 70, y + 16, 2, 0xFF23A55A);
				context.drawString(font, font.plainSubstrByWidth(name, SCREEN_W - 84), 76, y + 4, DC_TEXT, false);
				y += 20;
			}
		}
		// You, at the bottom
		context.fill(0, by, SCREEN_W, SCREEN_H - 12, 0xFF232428);
		String me = minecraft.player != null ? minecraft.player.getGameProfile().name() : "you";
		avatar(context, me, 10, by + 7, 22);
		Glass.rounded(context, 26, by + 22, 34, by + 30, 4, 0xFF232428);
		Glass.rounded(context, 27, by + 23, 33, by + 29, 3, 0xFF23A55A);
		context.drawString(font, me, 40, by + 8, 0xFFFFFFFF, false);
		context.drawString(font, "Online", 40, by + 19, DC_MUTED, false);
	}

	private static final Identifier TELEGRAM_LOGO = Identifier.fromNamespaceAndPath("femboyware", "textures/larp/telegram.png");
	private static final Identifier DISCORD_LOGO = Identifier.fromNamespaceAndPath("femboyware", "textures/larp/discord.png");

	/** Discord's logo, white, centred in a {@code size} square (on the blurple behind it). */
	private static void appIconGlyph(GuiGraphics context, int x, int y, int size) {
		int inset = Math.round(size * 0.14f), w = size - inset * 2;
		context.blit(RenderPipelines.GUI_TEXTURED, DISCORD_LOGO, x + inset, y + inset, 0, 0, w, w, 128, 128, 128, 128);
	}

	/** One channel: who said what, newest at the bottom, and a box to say something. */
	private void drawDiscordChannel(GuiGraphics context, float mx, float my) {
		String key = discordKey();
		phone.discordRead(key);
		context.fill(0, 30, SCREEN_W, SCREEN_H - 12, DC_BG);
		context.fill(0, 30, SCREEN_W, 58, DC_BG);
		context.drawString(font, "<", 10, 40, DC_MUTED, false);
		hits.add(new Hit(0, 32, 26, 24, () -> discordChannel = null));
		context.drawString(font, "#", 24, 40, DC_MUTED, false);
		context.drawString(font, discordChannel, 34, 40, 0xFFFFFFFF, false);
		String server = PhoneModule.DISCORD_SERVERS[discordServer][0];
		context.drawString(font, server, SCREEN_W - 10 - font.width(server), 40, DC_MUTED, false);
		context.fill(0, 57, SCREEN_W, 58, 0xFF26272B);
		List<PhoneModule.Chat> chats = phone.discordChannel(key);
		int y = SCREEN_H - 50;
		context.enableScissor(0, 60, SCREEN_W, SCREEN_H - 46);
		for (int i = chats.size() - 1; i >= 0 && y > 60; i--) {
			PhoneModule.Chat c = chats.get(i);
			List<FormattedCharSequence> lines = font.split(Component.literal(c.text()), SCREEN_W - 46);
			int h = 12 + lines.size() * 10 + 6;
			y -= h;
			boolean mentioned = !c.mine() && c.text().contains("@");
			if (mentioned) context.fill(0, y - 2, SCREEN_W, y + h - 4, 0x30FAA61A);
			avatar(context, c.user(), 8, y, 20);
			context.drawString(font, c.user(), 34, y, c.mine() ? 0xFFFFFFFF : nameColor(c.user()), false);
			context.drawString(font, ago(c.time()), 38 + font.width(c.user()), y, DC_MUTED, false);
			for (int l = 0; l < lines.size(); l++) context.drawString(font, lines.get(l), 34, y + 11 + l * 10, DC_TEXT, false);
		}
		context.disableScissor();
		// The message box
		Glass.rounded(context, 10, SCREEN_H - 40, SCREEN_W - 10, SCREEN_H - 20, 10, 0xFF383A40);
		int room = SCREEN_W - 40;
		String visible = draft;
		while (font.width(visible) > room && visible.length() > 1) visible = visible.substring(1);
		if (draft.isEmpty()) context.drawString(font, "Message #" + discordChannel, 20, SCREEN_H - 34, DC_MUTED, false);
		else context.drawString(font, visible, 20, SCREEN_H - 34, DC_TEXT, false);
		if ((System.currentTimeMillis() / 500) % 2 == 0) {
			int cx = 20 + (draft.isEmpty() ? 0 : font.width(visible));
			context.fill(cx, SCREEN_H - 36, cx + 1, SCREEN_H - 25, DC_TEXT);
		}
	}

	// ---------------------------------------------------------------- Telegram

	private static final int TG_BLUE = 0xFF5288C1, TG_ROW = 0xFF17212B, TG_HOT = 0xFF202B36, TG_MUTED = 0xFF7F91A4;

	/** Telegram's chat list: the plug, the OTC desk, ZachXBT and the rest, newest first. */
	private void drawTelegram(GuiGraphics context, float mx, float my) {
		context.fill(0, 30, SCREEN_W, 64, TG_ROW);
		scaled(context, "Telegram", 14, 40, 1.6f, TEXT);
		context.drawString(font, "Edit", SCREEN_W - 14 - font.width("Edit"), 44, TG_BLUE, false);
		int y = 66;
		java.util.Set<String> shown = new java.util.HashSet<>();
		for (List<Message> t : phone.threads()) {
			if (y + 40 > SCREEN_H - 18) break;
			Message last = t.getLast();
			if (!PhoneModule.isTelegram(last.contact())) continue;
			String name = PhoneModule.shownName(last.contact());
			if (last.contact().startsWith(PhoneModule.TG_THREAD)) shown.add(name.toLowerCase(Locale.ROOT));
			boolean hot = mx >= 0 && mx < SCREEN_W && my >= y && my < y + 40;
			context.fill(0, y, SCREEN_W, y + 40, hot ? TG_HOT : 0xFF0E1621);
			avatar(context, name, 10, y + 5, 30);
			context.drawString(font, name, 48, y + 8, TEXT, false);
			if (phone.onlineUsers().stream().anyMatch(n -> n.equalsIgnoreCase(name)) && last.contact().startsWith(PhoneModule.TG_THREAD)) {
				Glass.rounded(context, 32, y + 27, 40, y + 35, 4, 0xFF0E1621);
				Glass.rounded(context, 33, y + 28, 39, y + 34, 3, GREEN_DOT);
			}
			String when = ago(last.time());
			context.drawString(font, when, SCREEN_W - 12 - font.width(when), y + 8, TG_MUTED, false);
			context.drawString(font, font.plainSubstrByWidth((last.mine() ? "You: " : "") + last.text(), SCREEN_W - 60), 48, y + 21, TG_MUTED, false);
			context.fill(48, y + 39, SCREEN_W, y + 40, 0xFF0A1018);
			String contact = last.contact();
			hits.add(new Hit(0, y, SCREEN_W, 40, () -> openThread(contact)));
			y += 40;
		}
		// Other femboyware users online you haven't messaged on Telegram yet: tap one to start
		List<String> fresh = new ArrayList<>();
		for (String name : phone.onlineUsers()) if (!shown.contains(name.toLowerCase(Locale.ROOT))) fresh.add(name);
		if (!fresh.isEmpty() && y + 30 <= SCREEN_H - 18) {
			context.drawString(font, "ONLINE WITH FEMBOYWARE", 14, y + 8, TG_MUTED, false);
			y += 22;
			for (String name : fresh) {
				if (y + 28 > SCREEN_H - 18) break;
				boolean hot = mx >= 0 && mx < SCREEN_W && my >= y && my < y + 28;
				context.fill(0, y, SCREEN_W, y + 28, hot ? TG_HOT : 0xFF0E1621);
				avatar(context, name, 12, y + 4, 20);
				Glass.rounded(context, 28, y + 18, 34, y + 24, 3, GREEN_DOT);
				context.drawString(font, name, 40, y + 10, TEXT, false);
				hits.add(new Hit(0, y, SCREEN_W, 28, () -> openThread(PhoneModule.telegramThread(name))));
				y += 28;
			}
		}
	}

	// ---------------------------------------------------------------- X

	private static final int X_GREY = 0xFF71767B, X_LINE = 0xFF2F3336;

	/** X: ZachXBT's thread about you, once he's posted it. */
	private void drawX(GuiGraphics context, float mx, float my) {
		drawCentered(context, font, "X", SCREEN_W / 2f, 44, 1.8f, TEXT);
		context.fill(0, 58, SCREEN_W, 59, X_LINE);
		// What's happening: click to write, Enter or Post to send it to everyone on femboyware
		Glass.rounded(context, 10, 64, SCREEN_W - 58, 84, 10, xFocused ? 0xFF202327 : 0xFF16181C);
		String shownDraft = xDraft;
		while (font.width(shownDraft) > SCREEN_W - 80 && shownDraft.length() > 1) shownDraft = shownDraft.substring(1);
		if (xDraft.isEmpty() && !xFocused) context.drawString(font, "What's happening?", 18, 70, X_GREY, false);
		else context.drawString(font, shownDraft, 18, 70, 0xFFE7E9EA, false);
		if (xFocused && (System.currentTimeMillis() / 500) % 2 == 0) context.fill(18 + font.width(shownDraft), 69, 19 + font.width(shownDraft), 79, 0xFF1D9BF0);
		hits.add(new Hit(10, 64, SCREEN_W - 68, 20, () -> xFocused = true));
		boolean hotPost = mx >= SCREEN_W - 52 && mx < SCREEN_W - 10 && my >= 64 && my < 84;
		Glass.rounded(context, SCREEN_W - 52, 64, SCREEN_W - 10, 84, 10, xDraft.isBlank() ? 0xFF0E4D78 : hotPost ? 0xFF1A8CD8 : 0xFF1D9BF0);
		context.drawString(font, "Post", SCREEN_W - 31 - font.width("Post") / 2, 70, TEXT, false);
		hits.add(new Hit(SCREEN_W - 52, 64, 42, 20, this::postX));
		context.fill(0, 90, SCREEN_W, 91, X_LINE);
		List<String> posts = phone.exposePosts();
		context.enableScissor(0, 92, SCREEN_W, SCREEN_H - 14);
		int y = 98 - scroll;
		// Posts from you and everyone else with femboyware, newest first
		for (PhoneModule.XPost post : phone.xPosts()) {
			avatar(context, post.author, 10, y, 22);
			context.drawString(font, post.author, 38, y + 1, TEXT, false);
			context.drawString(font, "@" + post.author.toLowerCase(Locale.ROOT) + " · " + ago(post.time), 42 + font.width(post.author), y + 1, X_GREY, false);
			List<FormattedCharSequence> lines = font.split(Component.literal(post.text), SCREEN_W - 48);
			for (int l = 0; l < lines.size(); l++) context.drawString(font, lines.get(l), 38, y + 13 + l * 10, 0xFFE7E9EA, false);
			y += 13 + lines.size() * 10 + 2;
			// The like button
			String heart = (post.liked ? "\u2665 " : "\u2661 ") + compact(post.likes);
			context.drawString(font, heart, 38, y + 2, post.liked ? 0xFFF91880 : X_GREY, false);
			hits.add(new Hit(34, y - 1, font.width(heart) + 8, 12, () -> {
				post.liked = !post.liked;
				post.likes += post.liked ? 1 : -1;
			}));
			y += 14;
			context.fill(0, y + 3, SCREEN_W, y + 4, X_LINE);
			y += 9;
		}
		if (posts == null) {
			if (phone.xPosts().isEmpty()) {
				String none = "You're all caught up";
				context.drawString(font, none, (SCREEN_W - font.width(none)) / 2, y + 20, X_GREY, false);
			}
			context.disableScissor();
			maxScroll = Math.max(0, y + scroll - (SCREEN_H - 20));
			hits.removeIf(h -> h.y() > 91 && h.y() < 98 && false);
			return;
		}
		for (int i = 0; i < posts.size(); i++) {
			avatar(context, "ZachXBT", 10, y, 22);
			context.drawString(font, "ZachXBT", 38, y + 1, TEXT, false);
			int nx = 38 + font.width("ZachXBT") + 3;
			Glass.rounded(context, nx, y + 1, nx + 8, y + 9, 4, 0xFF1D9BF0);
			context.drawString(font, "@zachxbt · " + ago(phone.exposedAt()), nx + 11, y + 1, X_GREY, false);
			List<FormattedCharSequence> lines = font.split(Component.literal(posts.get(i)), SCREEN_W - 48);
			for (int l = 0; l < lines.size(); l++) context.drawString(font, lines.get(l), 38, y + 13 + l * 10, 0xFFE7E9EA, false);
			y += 13 + lines.size() * 10 + 2;
			if (i == 0) {
				long[] stats = phone.exposeStats();
				String row = compact(stats[0]) + " replies   " + compact(stats[1]) + " reposts   " + compact(stats[2]) + " likes   " + compact(stats[3]) + " views";
				context.drawString(font, font.plainSubstrByWidth(row, SCREEN_W - 44), 38, y + 2, X_GREY, false);
				y += 14;
			}
			context.fill(0, y + 3, SCREEN_W, y + 4, X_LINE);
			y += 9;
		}
		context.disableScissor();
		maxScroll = Math.max(0, y + scroll - (SCREEN_H - 20));
	}

	/** What's being written on X, and whether its box has the cursor. */
	private String xDraft = "";
	private boolean xFocused;

	private void postX() {
		String problem = phone.postX(xDraft);
		if (problem != null) say(problem);
		else {
			xDraft = "";
			xFocused = false;
			scroll = 0;
			LarpSounds.play(LarpSounds.PHONE_TAP, 1.3f, 0.5f);
		}
	}

	// ---------------------------------------------------------------- Spotify (the real one on your PC)

	private static final int SP_GREEN = 0xFF1ED760, SP_MUTED = 0xFFB3B3B3, SP_BG = 0xFF121212, SP_ROW_HOT = 0xFF1F1F1F;
	/** Where the mouse was over the screen, for seeking to where the progress bar was clicked. */
	private float spMouseX;
	/** Whether the volume slider's being dragged. */
	private boolean spVolumeDrag;
	/** Whether Now Playing is up (else the library), and the playlist that's open; each slides with its own animation. */
	private boolean spPlayerOpen = true;
	private float spPlayerAnim = 1, spListAnim;
	private dev.femboyware.module.hud.SpotifyWeb.Playlist spList, spListShown;
	private int spScroll, spListScroll, spMaxScroll, spListMaxScroll;
	private long spLastFrame;
	private String spStatus;
	private long spStatusAt;

	private void spotifyReset() {
		spPlayerOpen = true;
		spPlayerAnim = 1;
		spList = null;
		spListShown = null;
		spListAnim = 0;
		spScroll = 0;
		spListScroll = 0;
		spLastFrame = 0;
	}

	private static float smooth(float t) {
		return t * t * (3 - 2 * t);
	}

	/** Moves {@code from} towards {@code to} at a quarter of a second for the whole way. */
	private static float approach(float from, float to, float dt) {
		float step = dt * 4.2f;
		return from < to ? Math.min(to, from + step) : Math.max(to, from - step);
	}

	/**
	 * Spotify: your library (Liked Songs and playlists), a playlist sliding in from the right, and
	 * Now Playing sliding up over them from the mini player, like the real app. Only the layer
	 * that's settled in place takes clicks.
	 */
	private void drawSpotify(GuiGraphics context, float mx, float my) {
		long now = System.nanoTime();
		float dt = spLastFrame == 0 ? 0 : Math.min(0.05f, (now - spLastFrame) / 1e9f);
		spLastFrame = now;
		spPlayerAnim = approach(spPlayerAnim, spPlayerOpen ? 1 : 0, dt);
		spListAnim = approach(spListAnim, spList != null ? 1 : 0, dt);
		if (spList != null) spListShown = spList;
		else if (spListAnim == 0) spListShown = null;
		float player = smooth(spPlayerAnim), list = smooth(spListAnim);

		// The status from the Web API, shown for a few seconds
		String status = dev.femboyware.module.hud.SpotifyWeb.status();
		if (status != null && !status.equals(spStatus)) {
			spStatus = status;
			spStatusAt = System.currentTimeMillis();
		}
		if (spStatus != null && System.currentTimeMillis() - spStatusAt > 4500) {
			if (spStatus.equals(dev.femboyware.module.hud.SpotifyWeb.status())) dev.femboyware.module.hud.SpotifyWeb.clearStatus();
			spStatus = null;
		}

		context.enableScissor(0, 0, SCREEN_W, SCREEN_H);
		Glass.rounded(context, 0, 0, SCREEN_W, SCREEN_H, RADIUS - BEZEL, SP_BG);
		boolean miniShown = dev.femboyware.module.hud.Spotify.active();
		if (player < 1) {
			// The library, drifting left a little as a playlist slides over it
			if (list < 1) layer(context, -list * 70, 0, spPlayerAnim == 0 && spListAnim == 0, mx, my, (cx, cy) -> drawSpotifyLibrary(context, cx, cy, miniShown));
			if (spListShown != null) {
				int dim = Math.round(list * 0x90);
				if (dim > 0) context.fill(0, 0, SCREEN_W, SCREEN_H, dim << 24);
				layer(context, (1 - list) * SCREEN_W, 0, spPlayerAnim == 0 && spListAnim == 1, mx, my, (cx, cy) -> drawSpotifyPlaylist(context, cx, cy, spListShown, miniShown));
			}
			// The mini player sits over both, and rises into Now Playing
			if (miniShown) layer(context, 0, player * -(SCREEN_H - 120), spPlayerAnim == 0, mx, my, (cx, cy) -> drawSpotifyMini(context, cx, cy, 1 - player));
		}
		if (player > 0) {
			layer(context, 0, (1 - player) * SCREEN_H, spPlayerAnim == 1, mx, my, (cx, cy) -> drawSpotifyPlayer(context, cx, cy));
		}
		if (spStatus != null) {
			String shown = fitText(spStatus, SCREEN_W - 40);
			int sw = font.width(shown) + 16, sy = SCREEN_H - 92;
			Glass.rounded(context, (SCREEN_W - sw) / 2, sy, (SCREEN_W + sw) / 2, sy + 18, 9, 0xF0282828);
			context.drawString(font, shown, (SCREEN_W - font.width(shown)) / 2, sy + 5, TEXT, false);
		}
		context.disableScissor();
	}

	private interface SpLayer {
		void draw(float mx, float my);
	}

	/**
	 * Draws one layer shifted by {@code dx, dy}. A layer that's moving (or covered) gets no hover
	 * and no clicks: its hits are dropped.
	 */
	private void layer(GuiGraphics context, float dx, float dy, boolean interactive, float mx, float my, SpLayer layer) {
		int before = hits.size();
		context.pose().pushMatrix();
		context.pose().translate(dx, dy);
		layer.draw(interactive ? mx : -1000, interactive ? my : -1000);
		context.pose().popMatrix();
		if (!interactive) while (hits.size() > before) hits.removeLast();
	}

	// ---- the library

	private void drawSpotifyLibrary(GuiGraphics context, float mx, float my, boolean miniShown) {
		Glass.rounded(context, 0, 0, SCREEN_W, SCREEN_H, RADIUS - BEZEL, SP_BG);
		// Header: your library, and a way back up to Now Playing
		spotifyLogo(context, 14, 36, 18, SP_BG);
		scaled(context, "Your Library", 38, 38, 1.4f, TEXT);
		int bottom = SCREEN_H - (miniShown ? 70 : 20);
		if (!dev.femboyware.module.hud.SpotifyWeb.hasClientId()) {
			drawSpotifyConnect(context, mx, my, 66);
			return;
		}
		if (!dev.femboyware.module.hud.SpotifyWeb.signedIn()) {
			int y = 90;
			drawCentered(context, font, "See your playlists here", SCREEN_W / 2f, y, 1.2f, TEXT);
			List<FormattedCharSequence> lines = font.split(Component.literal("Sign in with Spotify to open Liked Songs and your playlists from the phone."), SCREEN_W - 40);
			for (int l = 0; l < lines.size(); l++) context.drawString(font, lines.get(l), (SCREEN_W - font.width(lines.get(l))) / 2, y + 16 + l * 11, SP_MUTED, false);
			int by = y + 26 + lines.size() * 11;
			boolean waiting = dev.femboyware.module.hud.SpotifyWeb.signingIn();
			pill(context, waiting ? "Waiting for browser..." : "Sign in with Spotify", (SCREEN_W - 150) / 2, by, 150, mx, my, waiting ? null : dev.femboyware.module.hud.SpotifyWeb::signIn);
			String change = "Use a different Client ID";
			int cw = font.width(change);
			context.drawString(font, change, (SCREEN_W - cw) / 2, by + 34, SP_MUTED, false);
			hits.add(new Hit((SCREEN_W - cw) / 2 - 4, by + 30, cw + 8, 16, this::pasteSpotifyClientId));
			return;
		}
		List<dev.femboyware.module.hud.SpotifyWeb.Playlist> playlists = dev.femboyware.module.hud.SpotifyWeb.playlists();
		if (playlists == null) {
			if (!dev.femboyware.module.hud.SpotifyWeb.busy()) dev.femboyware.module.hud.SpotifyWeb.refreshPlaylists();
			drawCentered(context, font, "Loading your library...", SCREEN_W / 2f, 120, 1f, SP_MUTED);
			return;
		}
		// Refresh, top right
		String refresh = dev.femboyware.module.hud.SpotifyWeb.busy() ? "..." : "↻";
		boolean hotRefresh = mx >= SCREEN_W - 30 && mx < SCREEN_W - 8 && my >= 34 && my < 54;
		drawCentered(context, font, refresh, SCREEN_W - 19, 44, 1.3f, hotRefresh ? TEXT : SP_MUTED);
		hits.add(new Hit(SCREEN_W - 30, 34, 22, 20, dev.femboyware.module.hud.SpotifyWeb::refreshPlaylists));

		int top = 62, rowH = 48;
		context.enableScissor(0, top, SCREEN_W, bottom);
		int y = top + 4 - spScroll;
		for (var p : playlists) {
			if (y + rowH > top && y < bottom) {
				boolean hot = mx >= 0 && mx < SCREEN_W && my >= Math.max(y, top) && my < Math.min(y + rowH, bottom);
				if (hot) context.fill(0, y, SCREEN_W, y + rowH, SP_ROW_HOT);
				playlistCover(context, p, 12, y + 5, 38);
				context.drawString(font, fitText(p.name(), SCREEN_W - 70), 58, y + 13, p.equals(spList) ? SP_GREEN : TEXT, false);
				String sub = "Playlist · " + (p.id().equals(dev.femboyware.module.hud.SpotifyWeb.LIKED) ? p.count() + " songs" : p.owner());
				context.drawString(font, fitText(sub, SCREEN_W - 70), 58, y + 25, SP_MUTED, false);
				if (y >= top - rowH / 2 && y + rowH <= bottom + rowH / 2) hits.add(new Hit(0, Math.max(y, top), SCREEN_W, Math.min(y + rowH, bottom) - Math.max(y, top), () -> openSpotifyPlaylist(p)));
			}
			y += rowH;
		}
		context.disableScissor();
		spMaxScroll = Math.max(0, y + spScroll - bottom + 6);
	}

	private void openSpotifyPlaylist(dev.femboyware.module.hud.SpotifyWeb.Playlist p) {
		spList = p;
		spListShown = p;
		spListScroll = 0;
		dev.femboyware.module.hud.SpotifyWeb.loadSongs(p);
	}

	/** Setting up for the first time: make a Spotify app, then paste its Client ID. */
	private void drawSpotifyConnect(GuiGraphics context, float mx, float my, int y) {
		Glass.rounded(context, 12, y, SCREEN_W - 12, y + 230, 12, 0xFF1E1E1E);
		context.drawString(font, "Connect your Spotify", 22, y + 12, TEXT, false);
		String[] steps = {
			"1. Open the Spotify developer dashboard and make an app (any name).",
			"2. Add this Redirect URI (tap to copy):",
			null,
			"3. Tick Web API, save, then copy its Client ID.",
			"4. Tap Paste Client ID below."};
		int ly = y + 30;
		for (String step : steps) {
			if (step == null) {
				String uri = dev.femboyware.module.hud.SpotifyWeb.REDIRECT;
				boolean hot = mx >= 22 && mx < 22 + font.width(uri) && my >= ly - 2 && my < ly + 10;
				context.drawString(font, uri, 22, ly, hot ? 0xFF3BE477 : SP_GREEN, false);
				hits.add(new Hit(20, ly - 2, font.width(uri) + 4, 12, () -> {
					minecraft.keyboardHandler.setClipboard(uri);
					say("Copied");
				}));
				ly += 14;
				continue;
			}
			for (FormattedCharSequence line : font.split(Component.literal(step), SCREEN_W - 46)) {
				context.drawString(font, line, 22, ly, SP_MUTED, false);
				ly += 11;
			}
			ly += 3;
		}
		pill(context, "Open dashboard", 22, ly + 6, SCREEN_W - 44, mx, my, dev.femboyware.module.hud.SpotifyWeb::openDashboard);
		pill(context, "Paste Client ID", 22, ly + 34, SCREEN_W - 44, mx, my, this::pasteSpotifyClientId);
	}

	private void pasteSpotifyClientId() {
		if (dev.femboyware.module.hud.SpotifyWeb.setClientId(minecraft.keyboardHandler.getClipboard())) say("Client ID saved");
		else say("Copy the Client ID first (32 letters and numbers)");
	}

	/** A rounded green button; {@code action} null draws it greyed out. */
	private void pill(GuiGraphics context, String label, int x, int y, int w, float mx, float my, Runnable action) {
		boolean hot = action != null && mx >= x && mx < x + w && my >= y && my < y + 24;
		Glass.rounded(context, x, y, x + w, y + 24, 12, action == null ? 0xFF2A2A2A : hot ? 0xFF3BE477 : SP_GREEN);
		context.drawString(font, label, x + (w - font.width(label)) / 2, y + 8, action == null ? SP_MUTED : 0xFF000000, false);
		if (action != null) hits.add(new Hit(x, y, w, 24, action));
	}

	/** A playlist's cover, or Liked Songs' purple heart. */
	private void playlistCover(GuiGraphics context, dev.femboyware.module.hud.SpotifyWeb.Playlist p, int x, int y, int size) {
		if (p.id().equals(dev.femboyware.module.hud.SpotifyWeb.LIKED)) {
			roundedGradient(context, x, y, x + size, y + size, 3, 0xFF4B1CF2, 0xFF8FC9C0);
			drawCentered(context, font, "♥", x + size / 2f, y + size / 2f + 0.5f, size / 18f, TEXT);
			return;
		}
		Identifier cover = dev.femboyware.module.hud.SpotifyWeb.cover(p.image());
		if (cover != null) {
			int n = dev.femboyware.module.hud.SpotifyWeb.coverSize(p.image());
			context.blit(RenderPipelines.GUI_TEXTURED, cover, x, y, 0, 0, size, size, n, n, n, n);
		} else {
			Glass.rounded(context, x, y, x + size, y + size, 3, 0xFF282828);
			drawCentered(context, font, "♪", x + size / 2f, y + size / 2f, size / 20f, 0xFF7F7F7F);
		}
	}

	// ---- a playlist

	private void drawSpotifyPlaylist(GuiGraphics context, float mx, float my, dev.femboyware.module.hud.SpotifyWeb.Playlist p, boolean miniShown) {
		Glass.rounded(context, 0, 0, SCREEN_W, SCREEN_H, RADIUS - BEZEL, SP_BG);
		int bottom = SCREEN_H - (miniShown ? 70 : 20);
		context.enableScissor(0, 30, SCREEN_W, bottom);
		int y = -spListScroll;
		// A wash of colour behind the cover
		roundedGradient(context, 0, y, SCREEN_W, y + 230, 0, p.id().equals(dev.femboyware.module.hud.SpotifyWeb.LIKED) ? 0xFF3A2380 : 0xFF2E2E3A, SP_BG);
		int cover = 116;
		playlistCover(context, p, (SCREEN_W - cover) / 2, y + 48, cover);
		scaled(context, fitText(p.name(), (int) ((SCREEN_W - 70) / 1.3f)), 16, y + 176, 1.3f, TEXT);
		List<dev.femboyware.module.hud.SpotifyWeb.Song> songs = dev.femboyware.module.hud.SpotifyWeb.songs(p.id());
		String sub = (p.owner().isBlank() ? "" : p.owner() + " · ") + p.count() + " songs";
		context.drawString(font, fitText(sub, SCREEN_W - 70), 16, y + 194, SP_MUTED, false);
		// The big green play button
		int px = SCREEN_W - 34, py = y + 186;
		boolean hotPlay = Math.hypot(mx - px, my - py) < 17;
		Glass.rounded(context, px - 17, py - 17, px + 17, py + 17, 17, hotPlay ? 0xFF3BE477 : SP_GREEN);
		triangle(context, px - 5, py - 7, 13, 14, true, 0xFF000000);
		hits.add(new Hit(px - 17, py - 17, 34, 34, () -> dev.femboyware.module.hud.SpotifyWeb.play(p, null)));
		y += 216;
		if (songs == null) {
			context.drawString(font, "Loading...", 16, y + 10, SP_MUTED, false);
			y += 30;
		} else if (songs.isEmpty()) {
			context.drawString(font, "Nothing here", 16, y + 10, SP_MUTED, false);
			y += 30;
		}
		String playing = dev.femboyware.module.hud.Spotify.active() ? dev.femboyware.module.hud.Spotify.title() : null;
		if (songs != null) {
			for (var song : songs) {
				if (y + 34 > 30 && y < bottom) {
					boolean hot = mx >= 0 && mx < SCREEN_W && my >= Math.max(y, 30) && my < Math.min(y + 34, bottom);
					if (hot) context.fill(0, y, SCREEN_W, y + 34, SP_ROW_HOT);
					Identifier art = dev.femboyware.module.hud.SpotifyWeb.cover(song.image());
					if (art != null) {
						int n = dev.femboyware.module.hud.SpotifyWeb.coverSize(song.image());
						context.blit(RenderPipelines.GUI_TEXTURED, art, 12, y + 4, 0, 0, 26, 26, n, n, n, n);
					} else Glass.rounded(context, 12, y + 4, 38, y + 30, 2, 0xFF282828);
					boolean now = song.name().equals(playing);
					context.drawString(font, fitText(song.name(), SCREEN_W - 60), 46, y + 7, now ? SP_GREEN : TEXT, false);
					context.drawString(font, fitText(song.artists(), SCREEN_W - 60), 46, y + 19, SP_MUTED, false);
					int top = Math.max(y, 30), end = Math.min(y + 34, bottom);
					if (end - top > 8) hits.add(new Hit(0, top, SCREEN_W, end - top, () -> dev.femboyware.module.hud.SpotifyWeb.play(p, song.uri())));
				}
				y += 34;
			}
		}
		context.disableScissor();
		spListMaxScroll = Math.max(0, y + spListScroll - bottom + 6);
		// Back, over everything so it's always there
		Glass.rounded(context, 8, 34, 30, 56, 11, 0x90000000);
		chevronLeft(context, 18, 45, TEXT);
		hits.add(new Hit(6, 32, 28, 26, () -> spList = null));
	}

	// ---- the mini player

	/** The bar at the bottom while something's playing: tap it for Now Playing. {@code shown} fades it as it rises. */
	private void drawSpotifyMini(GuiGraphics context, float mx, float my, float shown) {
		int x1 = 8, x2 = SCREEN_W - 8, y1 = SCREEN_H - 66, y2 = SCREEN_H - 24;
		int alpha = Math.round(255 * shown);
		if (alpha < 8) return;
		int bg = dev.femboyware.module.hud.Spotify.artColor();
		Glass.rounded(context, x1, y1, x2, y2, 8, (alpha << 24) | (bg & 0xFFFFFF));
		hits.add(new Hit(x1, y1, x2 - x1 - 36, y2 - y1, () -> spPlayerOpen = true));
		if (dev.femboyware.module.hud.Spotify.prepareArt()) {
			int n = dev.femboyware.module.hud.Spotify.artSize();
			context.blit(RenderPipelines.GUI_TEXTURED, dev.femboyware.module.hud.Spotify.ART, x1 + 6, y1 + 6, 0, 0, 30, 30, n, n, n, n);
		} else Glass.rounded(context, x1 + 6, y1 + 6, x1 + 36, y1 + 36, 3, 0xFF282828);
		int fade = alpha << 24;
		context.drawString(font, fitText(dev.femboyware.module.hud.Spotify.title(), x2 - x1 - 90), x1 + 44, y1 + 10, fade | 0xFFFFFF, false);
		context.drawString(font, fitText(dev.femboyware.module.hud.Spotify.artist(), x2 - x1 - 90), x1 + 44, y1 + 22, fade | (SP_MUTED & 0xFFFFFF), false);
		// Play or pause
		int cx = x2 - 20, cy = (y1 + y2) / 2;
		boolean hot = mx >= cx - 14 && mx < cx + 14 && my >= cy - 14 && my < cy + 14;
		int ink = fade | (hot ? 0xFFFFFF : 0xE6E6E6);
		if (dev.femboyware.module.hud.Spotify.playing()) {
			context.fill(cx - 5, cy - 6, cx - 2, cy + 6, ink);
			context.fill(cx + 2, cy - 6, cx + 5, cy + 6, ink);
		} else triangle(context, cx - 4, cy - 6, 11, 12, true, ink);
		hits.add(new Hit(cx - 16, cy - 16, 32, 32, dev.femboyware.module.hud.Spotify::playPause));
		// How far through the song, along the bottom edge
		double length = dev.femboyware.module.hud.Spotify.length();
		if (length > 0) {
			int filled = x1 + 6 + (int) Math.round((x2 - x1 - 12) * Mth.clamp(dev.femboyware.module.hud.Spotify.position() / length, 0, 1));
			context.fill(x1 + 6, y2 - 2, x2 - 6, y2 - 1, fade | 0x4D4D4D);
			context.fill(x1 + 6, y2 - 2, filled, y2 - 1, fade | 0xFFFFFF);
		}
	}

	// ---- Now Playing

	/** Spotify's now playing: the art, the song, a bar to seek with, and the controls, all on the real app. */
	private void drawSpotifyPlayer(GuiGraphics context, float mx, float my) {
		spMouseX = mx;
		boolean active = dev.femboyware.module.hud.Spotify.active();
		roundedGradient(context, 0, 0, SCREEN_W, SCREEN_H, RADIUS - BEZEL, active ? dev.femboyware.module.hud.Spotify.artColor() : 0xFF1A1A1A, SP_BG);
		// Down to the library
		boolean hotDown = mx >= 6 && mx < 34 && my >= 30 && my < 56;
		chevronDown(context, 20, 42, hotDown ? TEXT : 0xE0FFFFFF);
		hits.add(new Hit(6, 30, 28, 26, () -> spPlayerOpen = false));
		if (!active) {
			int logo = 64;
			spotifyLogo(context, (SCREEN_W - logo) / 2, 120, logo, SP_BG);
			boolean supported = dev.femboyware.module.hud.Spotify.supported();
			String head = supported ? "Nothing playing" : "Not on this computer";
			drawCentered(context, font, head, SCREEN_W / 2f, 210, 1.3f, TEXT);
			List<FormattedCharSequence> lines = font.split(Component.literal(supported
				? "Open Spotify on your PC and play something, or pick a playlist from your library."
				: "The Spotify app works with Spotify on Windows and Mac."), SCREEN_W - 40);
			for (int l = 0; l < lines.size(); l++) context.drawString(font, lines.get(l), (SCREEN_W - font.width(lines.get(l))) / 2, 228 + l * 11, SP_MUTED, false);
			if (supported) {
				int by = 236 + lines.size() * 11;
				pill(context, "Open Spotify", (SCREEN_W - 110) / 2, by, 110, mx, my, () -> {
					dev.femboyware.module.hud.Spotify.launch();
					say("Opening Spotify...");
				});
				pill(context, "Your Library", (SCREEN_W - 110) / 2, by + 30, 110, mx, my, () -> spPlayerOpen = false);
			}
			return;
		}
		// Where it's playing from
		String from = "PLAYING FROM YOUR PC";
		context.pose().pushMatrix();
		context.pose().translate(SCREEN_W / 2f, 36);
		context.pose().scale(0.75f, 0.75f);
		context.drawString(font, from, -font.width(from) / 2, 0, 0xE0FFFFFF, false);
		context.pose().popMatrix();
		String album = dev.femboyware.module.hud.Spotify.album();
		if (!album.isBlank()) {
			String shown = fitText(album, SCREEN_W - 80);
			context.drawString(font, shown, (SCREEN_W - font.width(shown)) / 2, 46, TEXT, false);
		}
		// The album art
		int art = SCREEN_W - 40, ax = 20, ay = 64;
		Glass.draw(context, ax, ay, ax + art, ay + art, 4, 0x60000000, true);
		if (dev.femboyware.module.hud.Spotify.prepareArt()) {
			int n = dev.femboyware.module.hud.Spotify.artSize();
			context.blit(RenderPipelines.GUI_TEXTURED, dev.femboyware.module.hud.Spotify.ART, ax, ay, 0, 0, art, art, n, n, n, n);
		} else {
			Glass.rounded(context, ax, ay, ax + art, ay + art, 4, 0xFF282828);
			drawCentered(context, font, "♪", ax + art / 2f, ay + art / 2f, 4f, 0xFF7F7F7F);
		}
		// The song
		int ty = ay + art + 16;
		scaled(context, fitText(dev.femboyware.module.hud.Spotify.title(), (int) ((SCREEN_W - 40) / 1.3f)), 20, ty, 1.3f, TEXT);
		context.drawString(font, fitText(dev.femboyware.module.hud.Spotify.artist(), SCREEN_W - 40), 20, ty + 16, SP_MUTED, false);
		// Progress: click anywhere on it to jump there
		double length = dev.femboyware.module.hud.Spotify.length(), position = dev.femboyware.module.hud.Spotify.position();
		int bx1 = 20, bx2 = SCREEN_W - 20, by = ty + 36;
		boolean overBar = mx >= bx1 - 2 && mx < bx2 + 2 && my >= by - 6 && my < by + 9;
		Glass.rounded(context, bx1, by, bx2, by + 3, 1, 0x4DFFFFFF);
		int filled = length > 0 ? bx1 + (int) Math.round((bx2 - bx1) * Mth.clamp(position / length, 0, 1)) : bx1;
		if (filled > bx1) Glass.rounded(context, bx1, by, filled, by + 3, 1, overBar ? SP_GREEN : TEXT);
		if (overBar) Glass.rounded(context, filled - 4, by - 3, filled + 4, by + 5, 4, TEXT);
		if (length > 0) {
			hits.add(new Hit(bx1 - 2, by - 6, bx2 - bx1 + 4, 15,
				() -> dev.femboyware.module.hud.Spotify.seek(Mth.clamp((spMouseX - bx1) / (double) (bx2 - bx1), 0, 1) * length)));
			String at = clock(position), left = "-" + clock(Math.max(0, length - position));
			context.pose().pushMatrix();
			context.pose().translate(bx1, by + 8);
			context.pose().scale(0.75f, 0.75f);
			context.drawString(font, at, 0, 0, SP_MUTED, false);
			context.drawString(font, left, (int) ((bx2 - bx1) / 0.75f) - font.width(left), 0, SP_MUTED, false);
			context.pose().popMatrix();
		}
		// Controls: back, play or pause, skip
		int cy = by + 40, cx = SCREEN_W / 2;
		boolean playing = dev.femboyware.module.hud.Spotify.playing();
		boolean hotPlay = Math.hypot(mx - cx, my - cy) < 20;
		Glass.rounded(context, cx - 20, cy - 20, cx + 20, cy + 20, 20, hotPlay ? 0xFFF0F0F0 : TEXT);
		if (playing) {
			context.fill(cx - 6, cy - 7, cx - 2, cy + 7, 0xFF000000);
			context.fill(cx + 2, cy - 7, cx + 6, cy + 7, 0xFF000000);
		} else triangle(context, cx - 5, cy - 8, 14, 16, true, 0xFF000000);
		hits.add(new Hit(cx - 20, cy - 20, 40, 40, dev.femboyware.module.hud.Spotify::playPause));
		int px = cx - 62;
		boolean hotPrev = mx >= px - 14 && mx < px + 14 && my >= cy - 14 && my < cy + 14;
		context.fill(px - 8, cy - 7, px - 6, cy + 7, hotPrev ? TEXT : 0xFFE0E0E0);
		triangle(context, px - 6, cy - 7, 13, 14, false, hotPrev ? TEXT : 0xFFE0E0E0);
		hits.add(new Hit(px - 14, cy - 14, 28, 28, dev.femboyware.module.hud.Spotify::previous));
		int nx = cx + 62;
		boolean hotNext = mx >= nx - 14 && mx < nx + 14 && my >= cy - 14 && my < cy + 14;
		triangle(context, nx - 7, cy - 7, 13, 14, true, hotNext ? TEXT : 0xFFE0E0E0);
		context.fill(nx + 6, cy - 7, nx + 8, cy + 7, hotNext ? TEXT : 0xFFE0E0E0);
		hits.add(new Hit(nx - 14, cy - 14, 28, 28, dev.femboyware.module.hud.Spotify::next));
		// Volume: Spotify's own, not the PC's; click or drag along it, or scroll anywhere
		int vy = cy + 38, vx1 = 40, vx2 = SCREEN_W - 40;
		float volume = dev.femboyware.module.hud.Spotify.volume();
		if (spVolumeDrag) {
			volume = Mth.clamp((mx - vx1) / (float) (vx2 - vx1), 0, 1);
			dev.femboyware.module.hud.Spotify.setVolume(volume);
		}
		boolean overVolume = spVolumeDrag || (mx >= vx1 - 4 && mx < vx2 + 4 && my >= vy - 7 && my < vy + 8);
		speaker(context, 22, vy + 1, 0, SP_MUTED);
		speaker(context, SCREEN_W - 28, vy + 1, 2, SP_MUTED);
		Glass.rounded(context, vx1, vy, vx2, vy + 3, 1, 0x4DFFFFFF);
		if (volume >= 0) {
			int knob = vx1 + Math.round((vx2 - vx1) * volume);
			if (knob > vx1) Glass.rounded(context, vx1, vy, knob, vy + 3, 1, overVolume ? SP_GREEN : TEXT);
			if (overVolume) Glass.rounded(context, knob - 4, vy - 3, knob + 4, vy + 5, 4, TEXT);
			hits.add(new Hit(vx1 - 4, vy - 7, vx2 - vx1 + 8, 15, () -> {
				spVolumeDrag = true;
				dev.femboyware.module.hud.Spotify.setVolume((spMouseX - vx1) / (float) (vx2 - vx1));
			}));
		} else if (overVolume) {
			String why = "Play something to change the volume";
			context.pose().pushMatrix();
			context.pose().translate(SCREEN_W / 2f, vy + 7);
			context.pose().scale(0.75f, 0.75f);
			context.drawString(font, why, -font.width(why) / 2, 0, SP_MUTED, false);
			context.pose().popMatrix();
		}
		// Which device: this one
		String device = "Listening on this computer";
		context.drawString(font, device, (SCREEN_W - font.width(device)) / 2, vy + 20, SP_GREEN, false);
	}

	/** A small speaker with {@code waves} sound waves (0 to 2), its middle at ({@code x}, {@code y}). */
	private static void speaker(GuiGraphics context, int x, int y, int waves, int color) {
		context.fill(x, y - 1, x + 2, y + 2, color);
		context.fill(x + 2, y - 2, x + 3, y + 3, color);
		context.fill(x + 3, y - 3, x + 4, y + 4, color);
		for (int w = 1; w <= waves; w++) {
			int wx = x + 4 + w * 2, h = 1 + w;
			context.fill(wx, y - h + 1, wx + 1, y + h, color);
			context.fill(wx - 1, y - h, wx, y - h + 1, color);
			context.fill(wx - 1, y + h, wx, y + h + 1, color);
		}
	}

	/** A "<", drawn in screen pixels so it's smooth. */
	private static void chevronLeft(GuiGraphics context, int cx, int cy, int color) {
		int d = UiScale.density();
		context.pose().pushMatrix();
		context.pose().scale(1f / d, 1f / d);
		int h = 5 * d, t = Math.max(2, d * 3 / 2);
		for (int i = 0; i <= h; i++) {
			int x = cx * d - h / 2 + i, y = cy * d;
			// The point on the left, the arms spreading out to the right
			context.fill(x, y - i - t / 2, x + t, y - i + t / 2 + 1, color);
			context.fill(x, y + i - t / 2, x + t, y + i + t / 2 + 1, color);
		}
		context.pose().popMatrix();
	}

	/** A "v" pointing down, drawn in screen pixels so it's smooth. */
	private static void chevronDown(GuiGraphics context, int cx, int cy, int color) {
		int d = UiScale.density();
		context.pose().pushMatrix();
		context.pose().scale(1f / d, 1f / d);
		int w = 6 * d, t = Math.max(2, d * 3 / 2);
		for (int i = 0; i <= w; i++) {
			int x = cx * d, y = cy * d - w / 2 + i;
			context.fill(x - w + i - t / 2, y, x - w + i + t / 2 + 1, y + t, color);
			context.fill(x + w - i - t / 2, y, x + w - i + t / 2 + 1, y + t, color);
		}
		context.pose().popMatrix();
	}

	private String fitText(String text, int width) {
		if (font.width(text) <= width) return text;
		return font.plainSubstrByWidth(text, width - font.width("...")) + "...";
	}

	private static String clock(double seconds) {
		int total = (int) seconds;
		return String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60);
	}

	/** A solid triangle {@code w} wide and {@code h} tall, pointing right or left, drawn in screen pixels so it's smooth. */
	private static void triangle(GuiGraphics context, int x, int y, int w, int h, boolean right, int color) {
		int d = UiScale.density();
		context.pose().pushMatrix();
		context.pose().scale(1f / d, 1f / d);
		int pw = w * d, ph = h * d, px = x * d, py = y * d;
		for (int row = 0; row < ph; row++) {
			float t = 1 - Math.abs((row + 0.5f) / ph * 2 - 1);
			int len = Math.round(pw * t);
			if (len <= 0) continue;
			if (right) context.fill(px, py + row, px + len, py + row + 1, color);
			else context.fill(px + pw - len, py + row, px + pw, py + row + 1, color);
		}
		context.pose().popMatrix();
	}

	/** Spotify's logo: a green circle with three arcs in {@code ink}, drawn in screen pixels. */
	public static void spotifyLogo(GuiGraphics context, int x, int y, int size, int ink) {
		Glass.rounded(context, x, y, x + size, y + size, size / 2, SP_GREEN);
		int d = UiScale.density();
		context.pose().pushMatrix();
		context.pose().scale(1f / d, 1f / d);
		float s = size * d, ox = x * d, oy = y * d;
		// Each arc: how far down its middle is, how wide it is, and how thick (all of the logo's size)
		float[][] arcs = {{0.29f, 0.64f, 0.105f}, {0.47f, 0.54f, 0.085f}, {0.635f, 0.44f, 0.07f}};
		for (float[] a : arcs) {
			float half = a[1] * s / 2, thick = a[2] * s, mid = oy + a[0] * s, cx = ox + s * 0.5f, drop = a[1] * s * 0.2f;
			for (int px = Math.round(cx - half); px < Math.round(cx + half); px++) {
				float t = (px + 0.5f - cx) / half;
				float th = thick * (1 - 0.3f * t * t);
				float top = mid + drop * t * t - th / 2;
				context.fill(px, Math.round(top), px + 1, Math.round(top + th), ink);
			}
		}
		context.pose().popMatrix();
	}

	// ---------------------------------------------------------------- Messages search

	private String search = "";
	private boolean searchFocused;

	// ---------------------------------------------------------------- Camera

	/** The camera: a shutter that takes a real screenshot, the paparazzi switch, and your photos. */
	private void drawCamera(GuiGraphics context, float mx, float my) {
		context.fill(0, 30, SCREEN_W, SCREEN_H - 12, 0xFF000000);
		// The viewfinder: what the camera sees is the world behind the phone
		Glass.rounded(context, 10, 40, SCREEN_W - 10, 300, 12, 0xFF101012);
		context.fill(10 + (SCREEN_W - 20) / 3, 40, 11 + (SCREEN_W - 20) / 3, 300, 0x30FFFFFF);
		context.fill(10 + (SCREEN_W - 20) * 2 / 3, 40, 11 + (SCREEN_W - 20) * 2 / 3, 300, 0x30FFFFFF);
		context.fill(10, 40 + 260 / 3, SCREEN_W - 10, 41 + 260 / 3, 0x30FFFFFF);
		context.fill(10, 40 + 520 / 3, SCREEN_W - 10, 41 + 520 / 3, 0x30FFFFFF);
		String hint = "Tap the shutter to take a photo";
		context.drawString(font, hint, (SCREEN_W - font.width(hint)) / 2, 166, 0xC0FFFFFF, false);
		context.drawString(font, "PHOTO", (SCREEN_W - font.width("PHOTO")) / 2, 310, 0xFFFFD60A, false);
		// The shutter
		int cx = SCREEN_W / 2, cy = 352;
		boolean hot = mx >= cx - 26 && mx < cx + 26 && my >= cy - 26 && my < cy + 26;
		Glass.rounded(context, cx - 26, cy - 26, cx + 26, cy + 26, 26, 0xFFFFFFFF);
		Glass.rounded(context, cx - 22, cy - 22, cx + 22, cy + 22, 22, 0xFF000000);
		Glass.rounded(context, cx - 19, cy - 19, cx + 19, cy + 19, 19, hot ? 0xFFDDDDDD : 0xFFFFFFFF);
		hits.add(new Hit(cx - 26, cy - 26, 52, 52, () -> {
			phone.takePhoto();
			onClose();
		}));
		// Your photos, bottom left: opens the screenshots folder
		java.io.File folder = new java.io.File(minecraft.gameDirectory, "screenshots");
		String[] files = folder.list((dir, name) -> name.endsWith(".png"));
		int count = files == null ? 0 : files.length;
		Glass.rounded(context, 24, cy - 18, 60, cy + 18, 8, 0xFF2C2C2E);
		drawCentered(context, font, String.valueOf(count), 42, cy, 1.2f, TEXT);
		hits.add(new Hit(24, cy - 18, 36, 36, () -> {
			folder.mkdirs();
			//? if >=26.3 {
			/*com.mojang.blaze3d.Blaze3D.openPath(folder.toPath());
			*///?} else {
			net.minecraft.util.Util.getPlatform().openPath(folder.toPath());
			//?}
		}));
		context.drawString(font, "Photos", 42 - font.width("Photos") / 2, cy + 22, MUTED, false);
		// Paparazzi, bottom right
		PaparazziModule paparazzi = FemboyWare.getModules().get(PaparazziModule.class);
		int px = SCREEN_W - 60;
		Glass.rounded(context, px, cy - 18, px + 36, cy + 18, 18, paparazzi.isEnabled() ? 0xFFFFD60A : 0xFF2C2C2E);
		drawCentered(context, font, "\u2735", px + 18, cy, 1.4f, paparazzi.isEnabled() ? 0xFF000000 : TEXT);
		hits.add(new Hit(px, cy - 18, 36, 36, () -> {
			paparazzi.toggle();
			say(paparazzi.isEnabled() ? "Paparazzi on" : "Paparazzi off");
		}));
		context.drawString(font, "Paparazzi", px + 18 - font.width("Paparazzi") / 2, cy + 22, MUTED, false);
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

	// ---------------------------------------------------------------- TV remote

	private static final int PINK = 0xFFE0559A, PINK_HOT = 0xFFF06AAE, GREY_BUTTON = 0xFF2C2C30, GREY_HOT = 0xFF3A3A40;
	private List<java.nio.file.Path> tvFiles = List.of();
	private long tvFilesAt;
	private int tvScroll;

	/** The TV remote: what's on, play and skip, mirror TikTok, put the TV out or away, volume, and your videos to pick from. */
	private void drawTv(GuiGraphics context, float mx, float my) {
		TvModule tv = FemboyWare.getModules().get(TvModule.class);
		scaled(context, "TV", 14, 38, 2f, TEXT);
		context.drawString(font, font.plainSubstrByWidth(tv.status(), SCREEN_W - 28), 14, 60, MUTED, false);
		int y = 74;
		// Now playing
		Glass.rounded(context, 12, y, SCREEN_W - 12, y + 66, 12, 0xFF1C1C1E);
		String name = tv.nowPlaying();
		context.drawString(font, font.plainSubstrByWidth(name == null ? "Nothing on" : name, SCREEN_W - 44), 22, y + 8, name == null ? FAINT : TEXT, false);
		float progress = tv.progress();
		if (progress >= 0) {
			context.fill(22, y + 22, SCREEN_W - 22, y + 24, 0xFF3A3A3E);
			context.fill(22, y + 22, 22 + (int) ((SCREEN_W - 44) * progress), y + 24, PINK);
		}
		boolean mirror = tv.mirroring() || tv.watchingTikTok();
		int bw = (SCREEN_W - 44 - 12) / 3;
		tvButton(context, mirror ? "Prev" : "-10s", 22, y + 36, bw, mx, my, false, () -> tv.remoteSkip(-10));
		tvButton(context, tv.isPlaying() ? "Pause" : "Play", 22 + bw + 6, y + 36, bw, mx, my, true, tv::remoteToggle);
		tvButton(context, mirror ? "Next" : "+10s", 22 + (bw + 6) * 2, y + 36, bw, mx, my, false, () -> tv.remoteSkip(10));
		y += 74;
		int half = (SCREEN_W - 24 - 6) / 2;
		tvButton(context, "TikTok", 12, y, half, mx, my, true, () -> {
			String problem = tv.remoteMirror();
			say(problem == null ? "TikTok's on the TV" : problem);
		});
		tvButton(context, "Mirror screen", 12 + half + 6, y, half, mx, my, true, () -> {
			String problem = tv.remoteMirrorScreen();
			say(problem == null ? "Your screen's on the TV" : problem);
		});
		y += 26;
		tvButton(context, "Turn off", 12, y, SCREEN_W - 24, mx, my, false, () -> {
			tv.remoteStop();
			say("TV's off");
		});
		y += 26;
		tvButton(context, "TV here", 12, y, half, mx, my, false, () -> {
			tv.remotePlace();
			say("TV's in front of you");
		});
		tvButton(context, "Put away", 12 + half + 6, y, half, mx, my, false, () -> {
			tv.remoteRemove();
			say("TV's put away");
		});
		y += 26;
		tvButton(context, "-", 12, y, 30, mx, my, false, () -> tv.setVolume(tv.volume() - 0.1f));
		String volume = "Volume " + Math.round(tv.volume() * 100) + "%";
		context.drawString(font, volume, 12 + 30 + (SCREEN_W - 24 - 60 - font.width(volume)) / 2, y + 6, TEXT, false);
		tvButton(context, "+", SCREEN_W - 12 - 30, y, 30, mx, my, false, () -> tv.setVolume(tv.volume() + 0.1f));
		y += 26;
		tvButton(context, "Open TV folder", 12, y, SCREEN_W - 24, mx, my, false, tv::remoteOpenFolder);
		y += 30;

		// Your videos, music and pictures: tap one to put it on
		context.drawString(font, "Your videos", 14, y, TEXT, false);
		y += 13;
		long now = System.currentTimeMillis();
		if (now - tvFilesAt > 3000) {
			tvFiles = TvModule.playableFiles();
			tvFilesAt = now;
		}
		if (tvFiles.isEmpty()) {
			for (var part : font.getSplitter().splitLines("Nothing yet. Put MP4s in your TV folder, or in Videos or Downloads.", SCREEN_W - 28, net.minecraft.network.chat.Style.EMPTY)) {
				context.drawString(font, part.getString(), 14, y, FAINT, false);
				y += 10;
			}
			return;
		}
		int rowH = 20, bottom = SCREEN_H - 20, rows = Math.max(1, (bottom - y) / rowH);
		tvScroll = Mth.clamp(tvScroll, 0, Math.max(0, tvFiles.size() - rows));
		context.enableScissor(0, y, SCREEN_W, bottom);
		for (int i = tvScroll; i < tvFiles.size() && i < tvScroll + rows; i++) {
			java.nio.file.Path file = tvFiles.get(i);
			int ry = y + (i - tvScroll) * rowH;
			boolean hot = mx >= 12 && mx < SCREEN_W - 12 && my >= ry && my < ry + rowH - 2;
			boolean on = file.getFileName().toString().equals(name);
			Glass.rounded(context, 12, ry, SCREEN_W - 12, ry + rowH - 2, 8, on ? 0xFF3A1A2C : hot ? 0xFF2A2A2E : 0xFF1C1C1E);
			context.drawString(font, font.plainSubstrByWidth(file.getFileName().toString(), SCREEN_W - 44), 20, ry + 5, on ? PINK_HOT : TEXT, false);
			hits.add(new Hit(12, ry, SCREEN_W - 24, rowH - 2, () -> {
				tv.remotePlay(file);
				say("Playing " + file.getFileName());
			}));
		}
		context.disableScissor();
	}

	private void tvButton(GuiGraphics context, String label, int x, int y, int w, float mx, float my, boolean pink, Runnable action) {
		boolean hot = mx >= x && mx < x + w && my >= y && my < y + 20;
		Glass.rounded(context, x, y, x + w, y + 20, 10, pink ? (hot ? PINK_HOT : PINK) : (hot ? GREY_HOT : GREY_BUTTON));
		context.drawString(font, label, x + (w - font.width(label)) / 2, y + 6, TEXT, false);
		hits.add(new Hit(x, y, w, 20, () -> {
			LarpSounds.play(LarpSounds.PHONE_TAP, 1.3f, 0.4f);
			action.run();
		}));
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

	/** Whether typed keys go into a message box right now. */
	private boolean typing() {
		return (app == App.MESSAGES || app == App.TELEGRAM) && thread != null || app == App.DISCORD && discordChannel != null;
	}

	private void sendDraft() {
		if (app == App.DISCORD) {
			if (discordChannel == null || draft.isBlank()) return;
			String problem = phone.discordSend(discordKey(), draft);
			if (problem != null) {
				say(problem);
				return;
			}
			draft = "";
			LarpSounds.play(LarpSounds.PHONE_TAP, 1.3f, 0.5f);
			return;
		}
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
		if (app == App.COINBASE && cbSheet >= 0 && cbSheet < 3) {
			String typed = input.codepointAsString();
			if (typed.matches("[0-9.,kKmMbB]") && cbAmount.length() < 16) cbAmount += typed;
			return true;
		}
		if (app == App.X && xFocused && input.isAllowedChatCharacter()) {
			if (xDraft.length() < dev.femboyware.manager.PhoneLink.MAX_TEXT) xDraft += input.codepointAsString();
			return true;
		}
		if (app == App.MESSAGES && thread == null && searchFocused && input.isAllowedChatCharacter()) {
			if (search.length() < 30) search += input.codepointAsString();
			return true;
		}
		if (app == App.SHOPIFY && priceFocused) {
			String typed = input.codepointAsString();
			if (typed.matches("[0-9.,kKmMbB$]") && sellPrice.length() < 16) sellPrice += typed;
			return true;
		}
		if (app == App.BANK && wiring) {
			String typed = input.codepointAsString();
			if (typed.matches("[0-9.,kKmMbB]") && wireAmount.length() < 16) wireAmount += typed;
			return true;
		}
		if (typing() && input.isAllowedChatCharacter()) {
			if (draft.length() < dev.femboyware.manager.PhoneLink.MAX_TEXT) draft += input.codepointAsString();
			return true;
		}
		return super.charTyped(input);
	}

	private void drawThread(GuiGraphics context, float mx, float my) {
		// Header: back, their picture and name
		boolean tg = app == App.TELEGRAM;
		int accent = tg ? TG_BLUE : BLUE;
		context.fill(0, 30, SCREEN_W, 72, tg ? TG_ROW : 0xFF161618);
		context.drawString(font, "< Back", 10, 46, accent, false);
		boolean real = phone.isReal(thread);
		String name = PhoneModule.shownName(thread);
		if (tg && !real) {
			String seen = thread.equals("ZachXBT") ? "online" : "last seen recently";
			context.drawString(font, seen, SCREEN_W - 10 - font.width(seen), 46, thread.equals("ZachXBT") ? TG_BLUE : TG_MUTED, false);
		}
		hits.add(new Hit(0, 36, 56, 26, () -> thread = null));
		avatar(context, name, SCREEN_W / 2 - 12, 32, 24);
		context.drawString(font, name, (SCREEN_W - font.width(name)) / 2, 59, TEXT, false);
		if (real) {
			// Who this reaches: everyone online, or whether this person's online now
			boolean everyone = thread.equals(PhoneModule.EVERYONE);
			boolean here = everyone ? phone.linkConnected() : phone.onlineUsers().stream().anyMatch(n -> n.equalsIgnoreCase(name));
			String note = everyone ? phone.onlineUsers().size() + " online" : here ? "online" : tg ? "last seen recently" : "offline";
			context.drawString(font, note, SCREEN_W - 10 - font.width(note), 46, here ? (tg ? TG_BLUE : GREEN_DOT) : tg ? TG_MUTED : FAINT, false);
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
			Glass.rounded(context, x, y, x + width + 16, y + h, 9, m.mine() ? (tg ? 0xFF2B5278 : BLUE) : (tg ? 0xFF182533 : 0xFF26262A));
			for (int l = 0; l < lines.size(); l++) context.drawString(font, lines.get(l), x + 8, y + 5 + l * 10, TEXT, false);
		}
		// The input bar: type, then Enter or the send button
		Glass.rounded(context, 12, SCREEN_H - 40, SCREEN_W - 12, SCREEN_H - 20, 10, tg ? 0xFF17212B : 0xFF1C1C1E);
		int room = SCREEN_W - 22 - 36;
		if (draft.isEmpty()) context.drawString(font, real ? (tg ? "Message" : "iMessage") : tg ? "Message (send for a quick reply)" : "iMessage (send for a quick reply)", 22, SCREEN_H - 34, FAINT, false);
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
			context.fill(cx, SCREEN_H - 36, cx + 1, SCREEN_H - 25, accent);
		}
		Glass.rounded(context, SCREEN_W - 32, SCREEN_H - 38, SCREEN_W - 14, SCREEN_H - 22, 8, accent);
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
		if (FbiRaidModule.assetsFrozen()) {
			scaled(context, "Account frozen", 26, 96, 1.7f, 0xFFFF8A80);
			context.drawString(font, "Seized by order of the U.S. DOJ", 26, 114, 0xD0FFFFFF, false);
		} else scaled(context, LedgerModule.usd(phone.bankBalance()), 26, 96, 1.7f, TEXT);
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
			hits.add(new Hit(x, 150, bw, 20, i == 0 ? this::openWire : () -> say(done)));
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
		if (wiring) drawWire(context, mx, my);
	}

	// ---------------------------------------------------------------- wire transfers to other femboyware users

	private boolean wiring;
	private String wireTo;
	private String wireAmount = "";

	private void openWire() {
		if (FbiRaidModule.assetsFrozen()) {
			say("Account frozen by the DOJ");
			return;
		}
		wiring = true;
		wireTo = null;
		wireAmount = "";
	}

	private void sendWire() {
		if (wireTo == null) {
			say("Pick who to send it to");
			return;
		}
		double amount = LedgerModule.parseMoney(wireAmount);
		String problem = phone.sendWire(wireTo, amount);
		if (problem != null) {
			say(problem);
			return;
		}
		say("Sent " + LedgerModule.usd(amount) + " to " + wireTo);
		wiring = false;
	}

	/** The send sheet: who (anyone online with femboyware), how much, and send. */
	private void drawWire(GuiGraphics context, float mx, float my) {
		context.fill(0, 30, SCREEN_W, SCREEN_H, 0xA0000000);
		// Taps outside the sheet don't reach the bank under it
		hits.add(new Hit(0, 30, SCREEN_W, SCREEN_H - 30, () -> { }));
		int top = 110, bottom = SCREEN_H - 20;
		Glass.rounded(context, 8, top, SCREEN_W - 8, bottom, 16, 0xFF1C1C22);
		scaled(context, "Send a wire", 20, top + 12, 1.4f, TEXT);
		context.drawString(font, "Cancel", SCREEN_W - 20 - font.width("Cancel"), top + 15, BLUE, false);
		hits.add(new Hit(SCREEN_W - 26 - font.width("Cancel"), top + 8, font.width("Cancel") + 12, 20, () -> wiring = false));
		context.drawString(font, "Available " + LedgerModule.usd(phone.bankBalance()), 20, top + 32, MUTED, false);
		// Amount
		int fy = top + 48;
		Glass.rounded(context, 18, fy, SCREEN_W - 18, fy + 26, 9, 0xFF2C2C33);
		boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
		if (wireAmount.isEmpty()) context.drawString(font, "$ Amount (25k, 1.5m)", 28, fy + 9, FAINT, false);
		else scaled(context, "$" + wireAmount, 28, fy + 7, 1.3f, TEXT);
		if (blink) {
			int cx = 28 + (wireAmount.isEmpty() ? 0 : Math.round(font.width("$" + wireAmount) * 1.3f));
			context.fill(cx, fy + 6, cx + 1, fy + 20, BLUE);
		}
		// Who
		context.drawString(font, "TO", 20, fy + 36, FAINT, false);
		int y = fy + 48;
		List<String> online = phone.onlineMessages() ? phone.onlineUsers() : List.of();
		if (!phone.onlineMessages()) context.drawString(font, "Turn on Online Messages", 20, y + 4, MUTED, false);
		else if (online.isEmpty()) context.drawString(font, phone.linkConnected() ? "No one with femboyware is online" : "Connecting...", 20, y + 4, MUTED, false);
		for (String name : online) {
			if (y + 26 > bottom - 40) break;
			boolean picked = name.equals(wireTo), hot = mx >= 14 && mx < SCREEN_W - 14 && my >= y && my < y + 24;
			if (picked || hot) Glass.rounded(context, 14, y, SCREEN_W - 14, y + 24, 8, picked ? 0xFF143A66 : 0xFF26262E);
			avatar(context, name, 20, y + 3, 18);
			context.drawString(font, name, 44, y + 8, TEXT, false);
			if (picked) context.drawString(font, "✔", SCREEN_W - 28, y + 8, BLUE, false);
			hits.add(new Hit(14, y, SCREEN_W - 28, 24, () -> wireTo = name));
			y += 26;
		}
		// Send
		boolean ready = wireTo != null && LedgerModule.parseMoney(wireAmount) > 0;
		int by = bottom - 34;
		boolean hot = mx >= 18 && mx < SCREEN_W - 18 && my >= by && my < by + 24;
		Glass.rounded(context, 18, by, SCREEN_W - 18, by + 24, 12, ready ? (hot ? 0xFF3395FF : BLUE) : 0xFF2C2C33);
		String label = ready ? "Send " + LedgerModule.usd(LedgerModule.parseMoney(wireAmount)) : "Send";
		context.drawString(font, label, (SCREEN_W - font.width(label)) / 2, by + 8, ready ? TEXT : FAINT, false);
		hits.add(new Hit(18, by, SCREEN_W - 36, 24, this::sendWire));
	}

	// ---------------------------------------------------------------- stocks

	// ---------------------------------------------------------------- pump.fun

	private static final int PF_BG = 0xFF15161B, PF_PANEL = 0xFF1F2128, PF_GREEN = 0xFF86EFAC, PF_MUTED = 0xFF8B8F9A, PF_RED = 0xFFF87171;

	/** Dollars short: $4.2K, $1.3M. */
	private static String shortUsd(double v) {
		if (v >= 1_000_000) return String.format(Locale.US, "$%.2fM", v / 1_000_000);
		if (v >= 1_000) return String.format(Locale.US, "$%.1fK", v / 1_000);
		return String.format(Locale.US, "$%.0f", v);
	}

	/** pump.fun: launch meme coins, watch them climb, and rug them for the bag. */
	private void drawPump(GuiGraphics context, float mx, float my) {
		long now = System.currentTimeMillis();
		context.fill(0, 30, SCREEN_W, SCREEN_H - 12, PF_BG);
		pumpLogo(context, 14, 37, 16);
		scaled(context, "pump.fun", 36, 39, 1.3f, TEXT);
		boolean hotLaunch = mx >= SCREEN_W - 84 && mx < SCREEN_W - 12 && my >= 36 && my < 54;
		Glass.rounded(context, SCREEN_W - 84, 36, SCREEN_W - 12, 54, 6, hotLaunch ? 0xFFA7F3C3 : PF_GREEN);
		context.drawString(font, "launch coin", SCREEN_W - 48 - font.width("launch coin") / 2, 41, 0xFF0B0C0F, false);
		hits.add(new Hit(SCREEN_W - 84, 36, 72, 18, () -> {
			PumpFun.Coin coin = PumpFun.launch();
			say("Launched $" + coin.ticker);
		}));
		// King of the hill
		PumpFun.Coin king = PumpFun.king(now);
		int ky = 62;
		Glass.rounded(context, 12, ky, SCREEN_W - 12, ky + 56, 8, 0xFFFBBF24);
		Glass.rounded(context, 13, ky + 1, SCREEN_W - 13, ky + 55, 7, PF_PANEL);
		context.drawString(font, "king of the hill", 22, ky + 6, 0xFFFBBF24, false);
		if (king != null) {
			coinImage(context, king, 20, ky + 18, 30);
			context.drawString(font, king.name + " ($" + king.ticker + ")", 56, ky + 19, TEXT, false);
			context.drawString(font, "market cap: " + shortUsd(king.cap(now)), 56, ky + 31, PF_GREEN, false);
			double curve = king.curve(now);
			Glass.rounded(context, 56, ky + 43, SCREEN_W - 22, ky + 48, 2, 0xFF2C2F38);
			Glass.rounded(context, 56, ky + 43, 56 + (int) ((SCREEN_W - 78) * curve), ky + 48, 2, PF_GREEN);
		} else context.drawString(font, "launch a coin to take the throne", 22, ky + 26, PF_MUTED, false);
		// Your coins
		context.drawString(font, "your coins", 14, 126, TEXT, false);
		int y = 140;
		for (PumpFun.Coin coin : PumpFun.coins()) {
			if (y + 48 > SCREEN_H - 16) break;
			Glass.rounded(context, 12, y, SCREEN_W - 12, y + 44, 8, PF_PANEL);
			coinImage(context, coin, 18, y + 7, 30);
			double cap = coin.cap(now);
			context.drawString(font, font.plainSubstrByWidth(coin.name, 70), 54, y + 7, TEXT, false);
			context.drawString(font, "$" + coin.ticker, 54, y + 18, PF_MUTED, false);
			context.drawString(font, "mc " + shortUsd(cap), 54, y + 29, coin.rugged() ? PF_RED : PF_GREEN, false);
			sparkline(context, coin, 116, y + 8, 152, y + 36, now);
			int bx = SCREEN_W - 52;
			if (coin.rugged()) {
				Glass.rounded(context, bx, y + 13, bx + 34, y + 31, 6, 0xFF2C2F38);
				context.drawString(font, "rugged", bx + 17 - font.width("rugged") / 2, y + 18, PF_MUTED, false);
			} else {
				boolean hot = mx >= bx && mx < bx + 34 && my >= y + 13 && my < y + 31;
				Glass.rounded(context, bx, y + 13, bx + 34, y + 31, 6, hot ? 0xFFFF8A8A : PF_RED);
				context.drawString(font, "rug", bx + 17 - font.width("rug") / 2, y + 18, 0xFF0B0C0F, false);
				hits.add(new Hit(bx, y + 13, 34, 18, () -> rugPull(coin)));
			}
			y += 48;
		}
	}

	private void rugPull(PumpFun.Coin coin) {
		if (FbiRaidModule.assetsFrozen()) {
			say("Your accounts are frozen");
			return;
		}
		double made = PumpFun.rug(coin);
		phone.wire("pump.fun ($" + coin.ticker + ")", made);
		say("Rugged $" + coin.ticker + " for " + shortUsd(made));
	}

	/** The coin's picture: its colour with its ticker's first letter. */
	private void coinImage(GuiGraphics context, PumpFun.Coin coin, int x, int y, int size) {
		Glass.rounded(context, x, y, x + size, y + size, 6, coin.color);
		drawCentered(context, font, coin.ticker.substring(0, 1), x + size / 2f, y + size / 2f + 0.5f, size / 16f, 0xFF0B0C0F);
	}

	/** A little chart of the coin's market cap from launch to now: up, and a cliff if it's been rugged. */
	private void sparkline(GuiGraphics context, PumpFun.Coin coin, int x1, int y1, int x2, int y2, long now) {
		int n = x2 - x1;
		double[] v = new double[n];
		double min = Double.MAX_VALUE, max = 0;
		long span = Math.max(1, now - coin.launched);
		for (int i = 0; i < n; i++) {
			v[i] = Math.log(coin.cap(coin.launched + span * i / (n - 1)));
			min = Math.min(min, v[i]);
			max = Math.max(max, v[i]);
		}
		int color = coin.rugged() ? PF_RED : PF_GREEN, prev = -1;
		for (int i = 0; i < n; i++) {
			int py = (int) (y2 - (max == min ? 0.5 : (v[i] - min) / (max - min)) * (y2 - y1));
			int a = prev < 0 ? py : Math.min(prev, py), b = prev < 0 ? py : Math.max(prev, py);
			context.fill(x1 + i, a, x1 + i + 1, b + 1, color);
			prev = py;
		}
	}

	/** pump.fun's pill: half green, half white, on its side. */
	private static void pumpLogo(GuiGraphics context, int x, int y, int size) {
		context.pose().pushMatrix();
		context.pose().translate(x + size / 2f, y + size / 2f);
		context.pose().rotate(-0.8f);
		int w = Math.max(3, size * 38 / 100), h = size * 46 / 100;
		Glass.rounded(context, -w, -h, w, 0, w, 0xFFFFFFFF);
		Glass.rounded(context, -w, 0, w, h, w, PF_GREEN);
		context.fill(-w, -1, w, 1, 0xFF0B0C0F);
		context.pose().popMatrix();
	}

	/** A rising line with a little noise, sharp at any size. */
	/**
	 * A price chart filling (x1, y1) to (x2, y2): {@code values} oldest first, or until real ones are
	 * in, a made-up line ending at {@code now} (a different one per range). Hovering over it shows the
	 * value at that point.
	 */
	private void drawChart(GuiGraphics context, int x1, int y1, int x2, int y2, int color, double[] values, double now, int range, float mx, float my) {
		if (values == null || values.length < 2) values = madeUpSeries(now, range);
		double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
		for (double v : values) {
			min = Math.min(min, v);
			max = Math.max(max, v);
		}
		if (max - min < 1e-9) {
			max += Math.max(1e-6, Math.abs(max) * 0.01);
			min -= Math.max(1e-6, Math.abs(min) * 0.01);
		}
		int d = UiScale.density();
		context.pose().pushMatrix();
		context.pose().scale(1f / d, 1f / d);
		int columns = (x2 - x1) * d, prev = -1;
		for (int i = 0; i < columns; i++) {
			double v = sample(values, i / (double) Math.max(1, columns - 1));
			int px = x1 * d + i;
			int py = (int) (y2 * d - (v - min) / (max - min) * (y2 - y1) * d);
			context.fillGradient(px, py, px + 1, y2 * d, (color & 0x00FFFFFF) | 0x40000000, color & 0x00FFFFFF);
			int from = prev < 0 ? py : Math.min(prev, py), to = prev < 0 ? py : Math.max(prev, py);
			context.fill(px, from - d / 2, px + 1, to + Math.max(1, d / 2), color);
			prev = py;
		}
		context.pose().popMatrix();
		// Scrubbing: a line where the mouse is, with the value there
		if (mx >= x1 && mx < x2 && my >= y1 && my < y2) {
			double v = sample(values, Mth.clamp((mx - x1) / (double) Math.max(1, x2 - x1 - 1), 0, 1));
			int px = (int) mx, py = (int) (y2 - (v - min) / (max - min) * (y2 - y1));
			context.fill(px, y1, px + 1, y2, 0x50FFFFFF);
			Glass.rounded(context, px - 3, py - 3, px + 3, py + 3, 3, TEXT);
			String label = LedgerModule.usd(v);
			int lw = font.width(label) + 8, lx = Mth.clamp(px - lw / 2, x1, x2 - lw);
			Glass.rounded(context, lx, y1, lx + lw, y1 + 12, 4, 0xF023262D);
			context.drawString(font, label, lx + 4, y1 + 2, TEXT, false);
		}
	}

	/** {@code values} at {@code t} (0 is the first, 1 the last), between points in a straight line. */
	private static double sample(double[] values, double t) {
		double at = t * (values.length - 1);
		int i = Math.min(values.length - 2, (int) at);
		return values[i] + (values[i + 1] - values[i]) * (at - i);
	}

	/** A believable wander ending at {@code now}, the same every time for a range. */
	private static double[] madeUpSeries(double now, int range) {
		java.util.Random random = new java.util.Random(99 + range * 7919L);
		double[] walk = new double[160];
		double v = 0, lo = 0, hi = 0;
		for (int i = 0; i < walk.length; i++) {
			v += random.nextGaussian() * 0.6 + 0.08;
			walk[i] = v;
			lo = Math.min(lo, v);
			hi = Math.max(hi, v);
		}
		double spread = 0.01 + range * 0.02, last = walk[walk.length - 1];
		for (int i = 0; i < walk.length; i++) walk[i] = now * (1 + spread * (walk[i] - last) / Math.max(1e-9, hi - lo));
		return walk;
	}

	// ---------------------------------------------------------------- Coinbase

	private static final int CB_BG = 0xFF0A0B0D, CB_BLUE = 0xFF0052FF, CB_PANEL = 0xFF16181D, CB_MUTED = 0xFF8A919E, CB_LINE = 0xFF1E2025;
	/** What you hold: ticker, name, amount, a rough price, its icon colour, and how far it wobbles. */
	private static final Object[][] COINS = {
		{"BTC", "Bitcoin", 41.72, 97_400.0, 0xFFF7931A, 0.018},
		{"ETH", "Ethereum", 612.4, 3_480.0, 0xFF627EEA, 0.024},
		{"SOL", "Solana", 9_400.0, 182.0, 0xFF9945FF, 0.035},
		{"DOGE", "Dogecoin", 2_100_000.0, 0.31, 0xFFC2A633, 0.05},
		{"USDC", "USD Coin", 1_250_000.0, 1.0, 0xFF2775CA, 0.0}};
	private static final String[] CB_RANGES = {"1H", "1D", "1W", "1M", "1Y", "ALL"};
	private int cbRange = 1;

	/** A coin's price right now: the real one from Coinbase, or until that's in, its rough price drifting a little. */
	private static double coinPrice(Object[] coin) {
		double real = CoinbaseMarket.price((String) coin[0]);
		if (!Double.isNaN(real)) return real;
		double t = System.currentTimeMillis() / 1000.0, wobble = (double) coin[5];
		int seed = ((String) coin[0]).hashCode();
		return (double) coin[3] * (1 + wobble * (0.6 * Math.sin(t / 47.0 + seed) + 0.4 * Math.sin(t / 13.0 + seed * 0.7)));
	}

	/** How much a coin's up (or down) today, as a fraction. */
	private static double coinChange(Object[] coin) {
		double real = CoinbaseMarket.change((String) coin[0]);
		if (!Double.isNaN(real)) return real;
		double wobble = (double) coin[5];
		if (wobble == 0) return 0;
		return wobble * 1.4 * Math.sin(System.currentTimeMillis() / 90_000.0 + ((String) coin[0]).hashCode()) + wobble * 0.6;
	}

	private double coinbaseTotal() {
		double total = 0;
		for (Object[] coin : COINS) total += held(coin) * coinPrice(coin);
		return total;
	}

	private double held(Object[] coin) {
		return phone.coinbaseAmount((String) coin[0]);
	}

	/** Which sheet is open over Coinbase (0 buy, 1 sell, 2 send, 3 receive; -1 none), on which coin, and the amount typed. */
	private int cbSheet = -1, cbCoin;
	private String cbAmount = "";

	/** The coin whose page is open over Coinbase's home, or -1. */
	private int cbDetail = -1;

	private record CbActivity(String ticker, String kind, double units, double usd, long time) {
	}

	/** What you've done on Coinbase this session, newest first. */
	private static final java.util.ArrayDeque<CbActivity> CB_ACTIVITY = new java.util.ArrayDeque<>();
	private static final String[] CB_RANGE_NAMES = {"past hour", "past day", "past week", "past month", "past year", "all time"};

	private static void activity(String ticker, String kind, double units, double usd) {
		CB_ACTIVITY.addFirst(new CbActivity(ticker, kind, units, usd, System.currentTimeMillis()));
		while (CB_ACTIVITY.size() > 40) CB_ACTIVITY.removeLast();
	}

	/** Your whole balance over a range, from each coin's real chart (coins without one held at today's price); null until any are in. */
	private double[] portfolioSeries(int range) {
		int n = 160;
		double[] sum = new double[n];
		boolean any = false;
		for (Object[] coin : COINS) {
			double amount = held(coin);
			double[] chart = CoinbaseMarket.chart((String) coin[0], range);
			if (chart != null) any = true;
			double price = coinPrice(coin);
			for (int i = 0; i < n; i++) sum[i] += amount * (chart == null ? price : sample(chart, i / (double) (n - 1)));
		}
		return any ? sum : null;
	}

	private void coinIcon(GuiGraphics context, Object[] coin, int x, int y, int size) {
		if (coin[0].equals("BTC")) {
			context.blit(RenderPipelines.GUI_TEXTURED, dev.femboyware.module.render.EmotesModule.BTC, x, y, 0, 0, size, size, 64, 64, 64, 64);
			return;
		}
		Glass.rounded(context, x, y, x + size, y + size, size / 2, (int) coin[4]);
		drawCentered(context, font, ((String) coin[0]).substring(0, 1), x + size / 2f, y + size / 2f + 0.5f, size / 22f, TEXT);
	}

	/** The time ranges under a chart. */
	private void drawRanges(GuiGraphics context, int y) {
		int rw = (SCREEN_W - 28) / CB_RANGES.length;
		for (int i = 0; i < CB_RANGES.length; i++) {
			int rx = 14 + i * rw;
			if (i == cbRange) Glass.rounded(context, rx + 2, y, rx + rw - 2, y + 14, 7, 0xFF1C2541);
			context.drawString(font, CB_RANGES[i], rx + (rw - font.width(CB_RANGES[i])) / 2, y + 3, i == cbRange ? 0xFF578BFA : CB_MUTED, false);
			int which = i;
			hits.add(new Hit(rx, y, rw, 14, () -> cbRange = which));
		}
	}

	/** A coin's page: its price and chart, what you hold, buy/sell/send/receive, and what you've done with it. */
	private void drawCoinPage(GuiGraphics context, float mx, float my, int index) {
		Object[] coin = COINS[index];
		String ticker = (String) coin[0];
		boolean frozen = FbiRaidModule.assetsFrozen();
		// Back, and the coin
		boolean hotBack = mx >= 6 && mx < 34 && my >= 34 && my < 58;
		chevronLeft(context, 18, 46, hotBack ? TEXT : CB_MUTED);
		hits.add(new Hit(6, 34, 28, 24, () -> cbDetail = -1));
		String name = (String) coin[1];
		int nw = 18 + font.width(name);
		coinIcon(context, coin, (SCREEN_W - nw) / 2, 38, 14);
		context.drawString(font, name, (SCREEN_W - nw) / 2 + 18, 42, TEXT, false);
		// The price, and how it's moved over the range shown
		double price = coinPrice(coin);
		double[] chart = CoinbaseMarket.chart(ticker, cbRange);
		context.drawString(font, ticker + " price", 14, 64, CB_MUTED, false);
		scaled(context, LedgerModule.usd(price), 14, 76, 2f, TEXT);
		double from = chart != null ? chart[0] : price / (1 + coinChange(coin));
		double moved = price - from, pct = from > 0 ? moved / from * 100 : 0;
		String rangeName = chart != null ? CB_RANGE_NAMES[cbRange] : "past day";
		String delta = (moved >= 0 ? "▲ " : "▼ ") + LedgerModule.usd(Math.abs(moved)) + String.format(Locale.US, " (%.2f%%) ", Math.abs(pct)) + rangeName;
		context.drawString(font, font.plainSubstrByWidth(delta, SCREEN_W - 28), 14, 98, moved >= 0 ? 0xFF27AD75 : 0xFFF0616D, false);
		drawChart(context, 14, 112, SCREEN_W - 14, 174, (int) coin[4] | 0xFF000000, chart, price, cbRange, mx, my);
		drawRanges(context, 180);
		// Buy, Sell, Send, Receive this coin
		String[] actions = {"Buy", "Sell", "Send", "Receive"};
		String[] glyphs = {"+", "-", "↑", "↓"};
		int bw = (SCREEN_W - 28) / 4;
		for (int i = 0; i < actions.length; i++) {
			int cx = 14 + i * bw + bw / 2;
			boolean hot = mx >= cx - 15 && mx < cx + 15 && my >= 202 && my < 232;
			Glass.rounded(context, cx - 15, 202, cx + 15, 232, 15, hot ? 0xFF3D7BFF : CB_BLUE);
			drawCentered(context, font, glyphs[i], cx, 217.5f, 1.6f, TEXT);
			context.drawString(font, actions[i], cx - font.width(actions[i]) / 2, 236, TEXT, false);
			int action = i;
			hits.add(new Hit(cx - 18, 202, 36, 44, () -> {
				if (frozen) say("Your account is restricted");
				else openSheet(action, index);
			}));
		}
		// What you hold
		context.fill(14, 250, SCREEN_W - 14, 251, CB_LINE);
		context.drawString(font, "Your balance", 14, 258, TEXT, false);
		double amount = held(coin);
		coinIcon(context, coin, 14, 272, 22);
		context.drawString(font, amountText(amount) + " " + ticker, 44, 274, TEXT, false);
		double total = coinbaseTotal();
		String share = total > 0 ? String.format(Locale.US, "%.1f%% of your crypto", amount * price / total * 100) : "";
		context.drawString(font, share, 44, 285, CB_MUTED, false);
		String worth = frozen ? "Frozen" : LedgerModule.usd(amount * price);
		context.drawString(font, worth, SCREEN_W - 14 - font.width(worth), 274, frozen ? 0xFFFF6B6B : TEXT, false);
		// What you've done with it
		context.fill(14, 302, SCREEN_W - 14, 303, CB_LINE);
		context.drawString(font, "Recent activity", 14, 310, TEXT, false);
		int y = 326;
		boolean none = true;
		for (CbActivity a : CB_ACTIVITY) {
			if (!a.ticker().equals(ticker)) continue;
			if (y + 24 > SCREEN_H - 16) break;
			none = false;
			Glass.rounded(context, 14, y + 1, 34, y + 21, 10, 0xFF23262D);
			drawCentered(context, font, a.kind().equals("Bought") ? "+" : a.kind().equals("Sold") ? "-" : "↑", 24, y + 11.5f, 1.2f, TEXT);
			context.drawString(font, a.kind() + " " + ticker, 42, y + 2, TEXT, false);
			String when = ago(a.time());
			context.drawString(font, when.equals("now") ? "Just now" : when + " ago", 42, y + 13, CB_MUTED, false);
			String units = (a.kind().equals("Bought") ? "+" : "-") + amountText(a.units()) + " " + ticker;
			context.drawString(font, units, SCREEN_W - 14 - font.width(units), y + 2, TEXT, false);
			String usd = LedgerModule.usd(a.usd());
			context.drawString(font, usd, SCREEN_W - 14 - font.width(usd), y + 13, CB_MUTED, false);
			y += 26;
		}
		if (none) {
			for (FormattedCharSequence line : font.split(Component.literal("Nothing yet. Buy or sell " + ticker + " and it shows up here."), SCREEN_W - 28)) {
				context.drawString(font, line, 14, y, CB_MUTED, false);
				y += 10;
			}
		}
	}

	private void openSheet(int action, int coin) {
		cbSheet = action;
		cbCoin = coin;
		cbAmount = "";
	}

	/** Does the sheet's action with the made-up money; returns what happened. */
	private String coinbaseConfirm() {
		Object[] coin = COINS[cbCoin];
		String ticker = (String) coin[0];
		double price = coinPrice(coin);
		if (FbiRaidModule.assetsFrozen()) return "Your account is restricted";
		double usd = LedgerModule.parseMoney(cbAmount);
		if (!(usd > 0)) return "Enter an amount, like 5000 or 2.5k";
		double units = usd / price;
		switch (cbSheet) {
			case 0 -> {
				if (!phone.spend("Coinbase: bought " + ticker, usd)) return "Not enough in your bank";
				phone.setCoinbaseAmount(ticker, held(coin) + units);
				activity(ticker, "Bought", units, usd);
				return "Bought " + amountText(units) + " " + ticker;
			}
			case 1 -> {
				units = Math.min(units, held(coin));
				if (units <= 0) return "You don't have any " + ticker;
				phone.setCoinbaseAmount(ticker, held(coin) - units);
				phone.credit("Coinbase: sold " + ticker, units * price);
				activity(ticker, "Sold", units, units * price);
				return "Sold " + amountText(units) + " " + ticker + " for " + LedgerModule.usd(units * price);
			}
			case 2 -> {
				Coin ledgerCoin = Coin.parse(ticker);
				if (ledgerCoin == null) return "Your Ledger doesn't hold " + ticker;
				units = Math.min(units, held(coin));
				if (units <= 0) return "You don't have any " + ticker;
				phone.setCoinbaseAmount(ticker, held(coin) - units);
				FemboyWare.getModules().get(LedgerModule.class).deposit(ledgerCoin, units);
				activity(ticker, "Sent", units, units * price);
				return "Sent " + amountText(units) + " " + ticker + " to your Ledger";
			}
			default -> {
				return null;
			}
		}
	}

	private static String amountText(double units) {
		return units >= 1000 ? String.format(Locale.US, "%,.0f", units) : units >= 1 ? String.format(Locale.US, "%,.4f", units) : String.format(Locale.US, "%.6f", units);
	}

	/** Your deposit address for a coin: made up, but the same every time. */
	private String address(Object[] coin) {
		String ticker = (String) coin[0];
		long seed = (phone.selfName() + ticker).hashCode() * 0x9E3779B97F4A7C15L;
		String hex = Long.toHexString(seed) + Long.toHexString(seed * 31 + 7);
		return switch (ticker) {
			case "BTC" -> "bc1q" + hex.substring(0, 24);
			case "SOL" -> hex.substring(0, 32).toUpperCase(Locale.ROOT).replace('0', 'G');
			case "DOGE" -> "D" + hex.substring(0, 25);
			default -> "0x" + hex.substring(0, 28);
		};
	}

	/** The sheet over Coinbase: pick a coin, type an amount in dollars, confirm. Receive shows your address. */
	private void drawCoinbaseSheet(GuiGraphics context, float mx, float my) {
		context.fill(0, 30, SCREEN_W, SCREEN_H - 12, 0xA0000000);
		int top = 150;
		Glass.rounded(context, 0, top, SCREEN_W, SCREEN_H - 12, 16, CB_PANEL);
		hits.add(new Hit(0, 30, SCREEN_W, top - 30, () -> cbSheet = -1));
		hits.add(new Hit(0, top, SCREEN_W, SCREEN_H - 12 - top, () -> { }));
		String[] titles = {"Buy", "Sell", "Send to Ledger", "Receive"};
		Object[] coin = COINS[cbCoin];
		scaled(context, titles[cbSheet] + " " + coin[0], 16, top + 14, 1.5f, TEXT);
		context.drawString(font, "\u2715", SCREEN_W - 22, top + 16, CB_MUTED, false);
		hits.add(new Hit(SCREEN_W - 30, top + 8, 24, 20, () -> cbSheet = -1));
		// The coins
		int x = 16, y = top + 40;
		for (int i = 0; i < COINS.length; i++) {
			String t = (String) COINS[i][0];
			int w = font.width(t) + 14;
			Glass.rounded(context, x, y, x + w, y + 16, 8, i == cbCoin ? CB_BLUE : 0xFF23262D);
			context.drawString(font, t, x + 7, y + 4, TEXT, false);
			int which = i;
			hits.add(new Hit(x, y, w, 16, () -> cbCoin = which));
			x += w + 5;
		}
		y += 28;
		double price = coinPrice(coin);
		context.drawString(font, "1 " + coin[0] + " = " + LedgerModule.usd(price) + "   You have " + amountText(held(coin)), 16, y, CB_MUTED, false);
		y += 18;
		if (cbSheet == 3) {
			// Receive: your address, and copying it
			String address = address(coin);
			Glass.rounded(context, 16, y, SCREEN_W - 16, y + 40, 8, 0xFF23262D);
			for (int l = 0; l < 2; l++) {
				String part = address.substring(Math.min(address.length(), l * 22), Math.min(address.length(), (l + 1) * 22));
				context.drawString(font, part, 24, y + 8 + l * 12, TEXT, false);
			}
			y += 52;
			boolean hot = mx >= 16 && mx < SCREEN_W - 16 && my >= y && my < y + 26;
			Glass.rounded(context, 16, y, SCREEN_W - 16, y + 26, 13, hot ? 0xFF3D7BFF : CB_BLUE);
			context.drawString(font, "Copy address", (SCREEN_W - font.width("Copy address")) / 2, y + 9, TEXT, false);
			hits.add(new Hit(16, y, SCREEN_W - 32, 26, () -> {
				minecraft.keyboardHandler.setClipboard(address);
				say("Address copied");
			}));
			return;
		}
		// The amount, in dollars
		Glass.rounded(context, 16, y, SCREEN_W - 16, y + 34, 8, 0xFF23262D);
		String typed = cbAmount.isEmpty() ? "0" : cbAmount;
		scaled(context, "$" + typed, 26, y + 9, 1.6f, cbAmount.isEmpty() ? CB_MUTED : TEXT);
		if ((System.currentTimeMillis() / 500) % 2 == 0) {
			int cx = 26 + (int) (font.width("$" + typed) * 1.6f) + 1;
			context.fill(cx, y + 8, cx + 1, y + 24, CB_BLUE);
		}
		double usd = LedgerModule.parseMoney(cbAmount);
		if (usd > 0) context.drawString(font, "\u2248 " + amountText(usd / price) + " " + coin[0], SCREEN_W - 20 - font.width("\u2248 " + amountText(usd / price) + " " + coin[0]), y + 22, CB_MUTED, false);
		y += 40;
		String from = cbSheet == 0 ? "From your bank: " + LedgerModule.usd(phone.bankBalance()) : cbSheet == 1 ? "To your bank" : "To your Ledger";
		context.drawString(font, from, 16, y, CB_MUTED, false);
		y += 16;
		// Quick amounts
		String[] quick = {"1k", "10k", "100k", "Max"};
		int qw = (SCREEN_W - 32 - 15) / 4;
		for (int i = 0; i < quick.length; i++) {
			int qx = 16 + i * (qw + 5);
			Glass.rounded(context, qx, y, qx + qw, y + 18, 9, 0xFF23262D);
			context.drawString(font, quick[i], qx + (qw - font.width(quick[i])) / 2, y + 5, TEXT, false);
			String q = quick[i];
			hits.add(new Hit(qx, y, qw, 18, () -> cbAmount = q.equals("Max")
				? LedgerModule.plainAmount(cbSheet == 0 ? Math.floor(phone.bankBalance()) : Math.floor(held(COINS[cbCoin]) * coinPrice(COINS[cbCoin])))
				: q));
		}
		y += 28;
		boolean hot = mx >= 16 && mx < SCREEN_W - 16 && my >= y && my < y + 28;
		Glass.rounded(context, 16, y, SCREEN_W - 16, y + 28, 14, hot ? 0xFF3D7BFF : CB_BLUE);
		String label = cbSheet == 0 ? "Buy" : cbSheet == 1 ? "Sell" : "Send";
		context.drawString(font, label, (SCREEN_W - font.width(label)) / 2, y + 10, TEXT, false);
		hits.add(new Hit(16, y, SCREEN_W - 32, 28, this::confirmSheet));
	}

	private void confirmSheet() {
		String result = coinbaseConfirm();
		if (result == null) return;
		say(result);
		if (result.startsWith("Bought") || result.startsWith("Sold") || result.startsWith("Sent")) {
			cbSheet = -1;
			LarpSounds.play(LarpSounds.LEDGER_SUCCESS, 1f, 0.7f);
		}
	}

	/** The Coinbase logo: a white circle with the rounded square cut out of its middle. */
	private static void coinbaseLogo(GuiGraphics context, int x, int y, int size, int background) {
		Glass.rounded(context, x, y, x + size, y + size, size / 2, 0xFFFFFFFF);
		int hole = Math.max(2, size * 36 / 100), at = (size - hole) / 2;
		Glass.rounded(context, x + at, y + at, x + at + hole, y + at + hole, Math.max(1, hole / 5), background);
	}

	/** Coinbase: your crypto balance, a chart, buy/sell/send/receive, and your coins. */
	private void drawCoinbase(GuiGraphics context, float mx, float my) {
		context.fill(0, 30, SCREEN_W, SCREEN_H - 12, CB_BG);
		if (cbDetail >= 0) {
			drawCoinPage(context, mx, my, cbDetail);
			if (cbSheet >= 0) drawCoinbaseSheet(context, mx, my);
			return;
		}
		coinbaseLogo(context, 14, 36, 20, CB_BLUE);
		Glass.rounded(context, 42, 36, SCREEN_W - 44, 56, 10, CB_PANEL);
		context.drawString(font, "Search", 54, 42, CB_MUTED, false);
		Glass.rounded(context, SCREEN_W - 34, 36, SCREEN_W - 14, 56, 10, CB_PANEL);
		drawCentered(context, font, "!", SCREEN_W - 24f, 46.5f, 1f, TEXT);
		boolean frozen = FbiRaidModule.assetsFrozen();
		context.drawString(font, "Crypto balance", 14, 68, CB_MUTED, false);
		double total = coinbaseTotal(), change = 0;
		for (Object[] coin : COINS) change += held(coin) * coinPrice(coin) * coinChange(coin);
		if (frozen) {
			scaled(context, "Restricted", 14, 80, 2f, 0xFFFF6B6B);
			context.drawString(font, "Account under review by U.S. DOJ", 14, 102, CB_MUTED, false);
		} else {
			scaled(context, LedgerModule.usd(total), 14, 80, 2f, TEXT);
			String delta = (change >= 0 ? "▲ " : "▼ ") + LedgerModule.usd(Math.abs(change)) + String.format(Locale.US, " (%.2f%%)", change / total * 100);
			context.drawString(font, delta, 14, 102, change >= 0 ? 0xFF27AD75 : 0xFFF0616D, false);
		}
		drawChart(context, 14, 116, SCREEN_W - 14, 176, frozen ? 0xFF5B616E : CB_BLUE, frozen ? null : portfolioSeries(cbRange), total, cbRange, mx, my);
		// Time ranges
		int rw = (SCREEN_W - 28) / CB_RANGES.length;
		for (int i = 0; i < CB_RANGES.length; i++) {
			int rx = 14 + i * rw;
			if (i == cbRange) Glass.rounded(context, rx + 2, 182, rx + rw - 2, 196, 7, 0xFF1C2541);
			context.drawString(font, CB_RANGES[i], rx + (rw - font.width(CB_RANGES[i])) / 2, 185, i == cbRange ? 0xFF578BFA : CB_MUTED, false);
			int which = i;
			hits.add(new Hit(rx, 182, rw, 14, () -> cbRange = which));
		}
		// Buy, Sell, Send, Receive
		String[] actions = {"Buy", "Sell", "Send", "Receive"};
		String[] glyphs = {"+", "-", "↑", "↓"};
		int bw = (SCREEN_W - 28) / 4;
		for (int i = 0; i < actions.length; i++) {
			int cx = 14 + i * bw + bw / 2;
			boolean hot = mx >= cx - 15 && mx < cx + 15 && my >= 204 && my < 234;
			Glass.rounded(context, cx - 15, 204, cx + 15, 234, 15, hot ? 0xFF3D7BFF : CB_BLUE);
			drawCentered(context, font, glyphs[i], cx, 219.5f, 1.6f, TEXT);
			context.drawString(font, actions[i], cx - font.width(actions[i]) / 2, 238, TEXT, false);
			int action = i;
			hits.add(new Hit(cx - 18, 204, 36, 44, () -> {
				if (frozen) say("Your account is restricted");
				else openSheet(action, cbCoin);
			}));
		}
		// Your coins
		context.fill(14, 254, SCREEN_W - 14, 255, CB_LINE);
		context.drawString(font, "Assets", 14, 262, TEXT, false);
		int y = 278;
		for (int ci = 0; ci < COINS.length; ci++) {
			Object[] coin = COINS[ci];
			if (y + 34 > SCREEN_H - 16) break;
			int which = ci;
			hits.add(new Hit(10, y, SCREEN_W - 20, 32, () -> cbDetail = which));
			if (mx >= 10 && mx < SCREEN_W - 10 && my >= y && my < y + 32) Glass.rounded(context, 8, y - 1, SCREEN_W - 8, y + 31, 6, 0xFF14161B);
			int color = (int) coin[4];
			if (coin[0].equals("BTC")) context.blit(RenderPipelines.GUI_TEXTURED, dev.femboyware.module.render.EmotesModule.BTC, 14, y + 2, 0, 0, 26, 26, 64, 64, 64, 64);
			else {
				Glass.rounded(context, 14, y + 2, 40, y + 28, 13, color);
				drawCentered(context, font, ((String) coin[0]).substring(0, 1), 27, y + 15.5f, 1.2f, TEXT);
			}
			double amount = held(coin), price = coinPrice(coin), pct = coinChange(coin) * 100;
			context.drawString(font, (String) coin[1], 48, y + 5, TEXT, false);
			String held = (amount >= 10_000 ? String.format(Locale.US, "%,.0f", amount) : String.format(Locale.US, "%,.2f", amount)) + " " + coin[0];
			context.drawString(font, held, 48, y + 17, CB_MUTED, false);
			String worth = frozen ? "Frozen" : LedgerModule.usd(amount * price);
			context.drawString(font, worth, SCREEN_W - 14 - font.width(worth), y + 5, frozen ? 0xFFFF6B6B : TEXT, false);
			String pctText = String.format(Locale.US, "%s%.2f%%", pct >= 0 ? "+" : "", pct);
			context.drawString(font, pctText, SCREEN_W - 14 - font.width(pctText), y + 17, pct >= 0 ? 0xFF27AD75 : 0xFFF0616D, false);
			y += 34;
		}
		if (cbSheet >= 0) drawCoinbaseSheet(context, mx, my);
	}

	// ---------------------------------------------------------------- App Store

	private static final int AS_PANEL = 0xFF1C1C1E, AS_BLUE = 0xFF0A84FF, AS_MUTED = 0xFF8E8E93, AS_LINE = 0xFF2C2C2E, AS_PILL = 0xFF2C2C2E;
	/** How long an app takes to "download". */
	private static final long DOWNLOAD_MS = 2600;
	/** Apps downloading now, and when each started; kept between openings of the phone. */
	private static final java.util.Map<App, Long> DOWNLOADS = new java.util.EnumMap<>(App.class);
	private static final String[] AS_TABS = {"Today", "Apps"};
	private int asTab;
	private App asDetail;
	private int asScroll, asMaxScroll;

	/** What the App Store says about an app: who makes it, what kind, a line, a paragraph, its stars and its colour. */
	private record StoreInfo(String developer, String category, String tagline, String about, float rating, String ratings, int color) {
	}

	private static StoreInfo storeInfo(App a) {
		return switch (a) {
			case MESSAGES -> new StoreInfo("Apple", "Social Networking", "Text anyone, anywhere", "Send texts to anyone on femboyware, or to everyone at once. Built in, so it can't be deleted.", 4.6f, "2.1M", 0xFF34C759);
			case TELEGRAM -> new StoreInfo("Telegram FZ-LLC", "Social Networking", "Fast. Secure. Private.", "Private chats with other femboyware users, sealed end to end so only the two of you can read them.", 4.7f, "1.4M", 0xFF2AABEE);
			case DISCORD -> new StoreInfo("Discord, Inc.", "Social Networking", "Group chat that's all fun & games", "Hang out in your servers. The FEMBOYWARE server is shared live with everyone on the client.", 4.7f, "3.9M", 0xFF5865F2);
			case X -> new StoreInfo("X Corp.", "News", "Blaze your glory", "Post what you're up to, and see what everyone else on femboyware is posting, live.", 4.1f, "5.6M", 0xFF16181C);
			case BANK -> new StoreInfo("Private Banking", "Finance", "Wealth, managed", "Your private bank account: balance, wires in and out, and every transaction.", 4.8f, "312K", 0xFF0B3D91);
			case COINBASE -> new StoreInfo("Coinbase, Inc.", "Finance", "Buy Bitcoin & crypto", "Live prices and real charts for every coin you hold. Buy, sell, and send to your Ledger.", 4.7f, "1.9M", 0xFF0052FF);
			case VOICE -> new StoreInfo("Google LLC", "Productivity", "Your second number", "Call anyone online with femboyware over voice chat, plus a dialpad, texts and voicemail.", 4.5f, "640K", 0xFF34A853);
			case PUMP -> new StoreInfo("pump.fun", "Finance", "Launch a coin in seconds", "Ape into the newest coins on the trenches before everyone else does.", 4.3f, "88K", 0xFF0E1015);
			case ROBLOX -> new StoreInfo("Roblox Corporation", "Games", "Join millions of experiences", "Your Robux, your avatar, and the cosmetics you've unlocked.", 4.5f, "28M", 0xFF3A3D41);
			case LEDGER -> new StoreInfo("Ledger SAS", "Finance", "Secure your crypto", "Your hardware wallet: cold storage for the coins you send off Coinbase.", 4.6f, "240K", 0xFF1A1A1A);
			case SPOTIFY -> new StoreInfo("Spotify AB", "Music", "Music and podcasts", "Control Spotify on your computer: what's playing, skip, seek, volume, and your playlists.", 4.8f, "34M", 0xFF1DB954);
			case CAMERA -> new StoreInfo("Apple", "Photo & Video", "Capture the moment", "Take photos of the world around you. Built in, so it can't be deleted.", 4.6f, "1.2M", 0xFF8E8E93);
			case SHOPIFY -> new StoreInfo("Shopify Inc.", "Business", "Run your store", "Watch the sales roll in from your store, order by order.", 4.7f, "410K", 0xFF95BF47);
			case VALET -> new StoreInfo("Valet", "Lifestyle", "Your car, brought round", "Call your cars round to wherever you are, with a valet at the wheel.", 4.9f, "57K", 0xFFC9A14A);
			case TV -> new StoreInfo("femboyware", "Entertainment", "The remote in your pocket", "Turn the TV on, put TikTok on it, or mirror your screen.", 4.6f, "93K", 0xFFE0559A);
			case STORE -> new StoreInfo("Apple", "Utilities", "Get apps", "Find apps, install them and delete the ones you don't use.", 4.7f, "4.4M", 0xFF1B6EEA);
		};
	}

	/** Messages, the camera and the App Store come with the phone and stay. */
	static boolean builtIn(App a) {
		return a == App.MESSAGES || a == App.CAMERA || a == App.STORE;
	}

	private static float downloadProgress(App a) {
		Long start = DOWNLOADS.get(a);
		return start == null ? -1 : Mth.clamp((System.currentTimeMillis() - start) / (float) DOWNLOAD_MS, 0, 1);
	}

	/** Finishes any download that's done: the app lands on the home screen. */
	private void tickDownloads() {
		if (DOWNLOADS.isEmpty()) return;
		for (App a : new ArrayList<>(DOWNLOADS.keySet())) {
			if (downloadProgress(a) < 1) continue;
			DOWNLOADS.remove(a);
			phone.setInstalled(a, true);
			say(a.label + " installed");
			LarpSounds.play(LarpSounds.LEDGER_SUCCESS, 1.2f, 0.4f);
		}
	}

	private void startDownload(App a) {
		if (phone.installed(a) || DOWNLOADS.containsKey(a)) return;
		DOWNLOADS.put(a, System.currentTimeMillis());
	}

	private void deleteApp(App a) {
		if (builtIn(a)) return;
		phone.setInstalled(a, false);
		DOWNLOADS.remove(a);
		say(a.label + " deleted");
	}

	/** A ring filling clockwise from the top, {@code progress} 0 to 1, with a small stop square in it. */
	private static void progressRing(GuiGraphics context, float cx, float cy, float radius, float progress, int track, int color) {
		int d = UiScale.density();
		context.pose().pushMatrix();
		context.pose().scale(1f / d, 1f / d);
		int steps = 72, dot = Math.max(2, d * 2);
		for (int i = 0; i < steps; i++) {
			double a = Math.PI * 2 * i / steps - Math.PI / 2;
			int px = Math.round((cx + (float) Math.cos(a) * radius) * d), py = Math.round((cy + (float) Math.sin(a) * radius) * d);
			context.fill(px - dot / 2, py - dot / 2, px - dot / 2 + dot, py - dot / 2 + dot, i < progress * steps ? color : track);
		}
		context.pose().popMatrix();
		context.fill(Math.round(cx - 2), Math.round(cy - 2), Math.round(cx + 2), Math.round(cy + 2), color);
	}

	/**
	 * GET, OPEN, or the download ring, centred on (cx, cy). {@code bold} draws GET as a filled blue
	 * button, for the big ones.
	 */
	private void storeButton(GuiGraphics context, App a, int cx, int cy, int w, float mx, float my, boolean bold) {
		float progress = downloadProgress(a);
		if (progress >= 0) {
			progressRing(context, cx, cy, 8, progress, AS_LINE, AS_BLUE);
			hits.add(new Hit(cx - 12, cy - 12, 24, 24, () -> DOWNLOADS.remove(a)));
			return;
		}
		boolean installed = phone.installed(a);
		String label = installed ? "OPEN" : "GET";
		boolean hot = mx >= cx - w / 2f && mx < cx + w / 2f && my >= cy - 9 && my < cy + 9;
		boolean filled = bold && !installed;
		int bg = filled ? (hot ? 0xFF3D9BFF : AS_BLUE) : (hot ? 0xFF3A3A3C : AS_PILL);
		Glass.rounded(context, cx - w / 2, cy - 9, cx + w / 2, cy + 9, 9, bg);
		context.drawString(font, label, cx - font.width(label) / 2, cy - 4, filled ? TEXT : AS_BLUE, false);
		hits.add(new Hit(cx - w / 2, cy - 9, w, 18, () -> {
			if (installed) {
				if (a != App.STORE) openApp(a);
			} else startDownload(a);
		}));
	}

	/** Five little stars, filled up to {@code rating}. */
	private void stars(GuiGraphics context, int x, int y, float rating, int color) {
		for (int i = 0; i < 5; i++) context.drawString(font, "★", x + i * 7, y, i < Math.round(rating) ? color : 0xFF48484A, false);
	}

	/** The App Store: today's pick and the charts, every app, and a page for each. */
	private void drawStore(GuiGraphics context, float mx, float my) {
		context.fill(0, 30, SCREEN_W, SCREEN_H - 12, 0xFF000000);
		if (asDetail != null) {
			drawStoreDetail(context, mx, my, asDetail);
			return;
		}
		int tabsY = SCREEN_H - 50;
		if (asTab == 0) drawStoreToday(context, mx, my, tabsY);
		else drawStoreApps(context, mx, my, tabsY);
		// Tabs along the bottom
		context.fill(0, tabsY, SCREEN_W, SCREEN_H - 12, 0xF0121214);
		context.fill(0, tabsY, SCREEN_W, tabsY + 1, AS_LINE);
		int tw = SCREEN_W / AS_TABS.length;
		for (int i = 0; i < AS_TABS.length; i++) {
			int tx = i * tw;
			boolean on = i == asTab;
			drawCentered(context, font, i == 0 ? "▤" : "▦", tx + tw / 2f, tabsY + 13.5f, 1.3f, on ? AS_BLUE : AS_MUTED);
			context.drawString(font, AS_TABS[i], tx + (tw - font.width(AS_TABS[i])) / 2, tabsY + 25, on ? AS_BLUE : AS_MUTED, false);
			int which = i;
			hits.add(new Hit(tx, tabsY, tw, 38, () -> {
				asTab = which;
				asScroll = 0;
			}));
		}
	}

	/** One app in a list: icon, name, what it is, and its button. Returns nothing; the whole row opens its page. */
	private void storeRow(GuiGraphics context, App a, int y, int rank, float mx, float my, int clipTop, int clipBottom) {
		StoreInfo info = storeInfo(a);
		int x = 14;
		if (rank > 0) {
			context.drawString(font, Integer.toString(rank), x, y + 13, TEXT, false);
			x += 14;
		}
		appIcon(context, a, x, y + 2, 34);
		context.drawString(font, a.label, x + 42, y + 6, TEXT, false);
		context.drawString(font, font.plainSubstrByWidth(info.tagline(), SCREEN_W - x - 42 - 64), x + 42, y + 18, AS_MUTED, false);
		context.fill(x + 42, y + 41, SCREEN_W, y + 42, AS_LINE);
		if (y + 4 < clipTop || y + 34 > clipBottom) return;
		hits.add(new Hit(0, y, SCREEN_W - 70, 40, () -> asDetail = a));
		storeButton(context, a, SCREEN_W - 38, y + 19, 50, mx, my, false);
	}

	private void drawStoreToday(GuiGraphics context, float mx, float my, int tabsY) {
		String date = new SimpleDateFormat("EEEE, MMMM d", Locale.US).format(new Date()).toUpperCase(Locale.US);
		context.drawString(font, date, 14, 38, AS_MUTED, false);
		scaled(context, "Today", 14, 50, 2f, TEXT);
		// The app of the day: a different one each day, never one that comes with the phone
		List<App> pickable = new ArrayList<>();
		for (App a : App.values()) if (!builtIn(a)) pickable.add(a);
		App pick = pickable.get((int) Math.floorMod(System.currentTimeMillis() / 86_400_000L, (long) pickable.size()));
		StoreInfo info = storeInfo(pick);
		int cy1 = 76, cy2 = 226;
		boolean hotCard = mx >= 12 && mx < SCREEN_W - 12 && my >= cy1 && my < cy2;
		roundedGradient(context, 12, cy1, SCREEN_W - 12, cy2, 14, mix(info.color(), 0xFFFFFFFF, hotCard ? 0.12f : 0.05f), mix(info.color(), 0xFF000000, 0.6f));
		hits.add(new Hit(12, cy1, SCREEN_W - 24, cy2 - cy1, () -> asDetail = pick));
		context.drawString(font, "APP OF THE DAY", 24, cy1 + 12, 0xD0FFFFFF, false);
		scaled(context, pick.label, 24, cy1 + 26, 1.8f, TEXT);
		List<FormattedCharSequence> lines = font.split(Component.literal(info.about()), SCREEN_W - 48);
		for (int l = 0; l < Math.min(3, lines.size()); l++) context.drawString(font, lines.get(l), 24, cy1 + 48 + l * 10, 0xE0FFFFFF, false);
		// The strip along the bottom of the card
		int sy = cy2 - 40;
		context.fill(12, sy, SCREEN_W - 12, sy + 1, 0x40FFFFFF);
		appIcon(context, pick, 22, sy + 8, 26);
		context.drawString(font, pick.label, 54, sy + 10, TEXT, false);
		context.drawString(font, font.plainSubstrByWidth(info.tagline(), SCREEN_W - 54 - 80), 54, sy + 21, 0xC0FFFFFF, false);
		storeButton(context, pick, SCREEN_W - 46, sy + 20, 50, mx, my, true);
		// The charts: the best rated, three of them
		context.drawString(font, "Top Charts", 14, 238, TEXT, false);
		List<App> top = new ArrayList<>(List.of(App.values()));
		top.sort((x, y) -> Float.compare(storeInfo(y).rating(), storeInfo(x).rating()));
		int y = 252;
		for (int i = 0; i < top.size() && y + 42 <= tabsY - 4; i++) {
			storeRow(context, top.get(i), y, i + 1, mx, my, 252, tabsY);
			y += 44;
		}
	}

	private void drawStoreApps(GuiGraphics context, float mx, float my, int tabsY) {
		scaled(context, "Apps", 14, 40, 2f, TEXT);
		int count = 0;
		for (App a : App.values()) if (phone.installed(a)) count++;
		context.drawString(font, count + " of " + App.values().length + " on your phone", 14, 62, AS_MUTED, false);
		int top = 76;
		App[] apps = App.values();
		asMaxScroll = Math.max(0, apps.length * 44 - (tabsY - top));
		asScroll = Mth.clamp(asScroll, 0, asMaxScroll);
		context.enableScissor(0, top, SCREEN_W, tabsY);
		for (int i = 0; i < apps.length; i++) {
			int y = top + i * 44 - asScroll;
			if (y + 44 < top || y > tabsY) continue;
			storeRow(context, apps[i], y, 0, mx, my, top, tabsY);
		}
		context.disableScissor();
	}

	/** An app's own page: the big button, its stars and chart place, what it does, and deleting it. */
	private void drawStoreDetail(GuiGraphics context, float mx, float my, App a) {
		StoreInfo info = storeInfo(a);
		boolean hotBack = mx >= 6 && mx < 60 && my >= 34 && my < 58;
		chevronLeft(context, 16, 46, AS_BLUE);
		context.drawString(font, AS_TABS[asTab], 24, 42, hotBack ? 0xFF5AB0FF : AS_BLUE, false);
		hits.add(new Hit(6, 34, 54, 24, () -> asDetail = null));
		// The header
		appIcon(context, a, 14, 64, 64);
		scaled(context, a.label, 88, 68, 1.5f, TEXT);
		context.drawString(font, info.developer(), 88, 84, AS_MUTED, false);
		storeButton(context, a, 88 + 32, 114, 64, mx, my, true);
		// Ratings, chart place, age
		int sy = 144;
		context.fill(14, sy - 6, SCREEN_W - 14, sy - 5, AS_LINE);
		int cw = (SCREEN_W - 28) / 3;
		List<App> ranked = new ArrayList<>(List.of(App.values()));
		ranked.sort((x, y) -> Float.compare(storeInfo(y).rating(), storeInfo(x).rating()));
		String[][] stats = {
			{info.ratings() + " RATINGS", String.format(Locale.US, "%.1f", info.rating()), ""},
			{"CHART", "#" + (ranked.indexOf(a) + 1), info.category()},
			{"AGE", "4+", "Years Old"}};
		for (int i = 0; i < 3; i++) {
			int cx = 14 + i * cw + cw / 2;
			int old = UiScale.pushDensity(Math.round(UiScale.density() * 0.75f));
			context.pose().pushMatrix();
			context.pose().translate(cx, sy);
			context.pose().scale(0.75f, 0.75f);
			context.drawString(font, stats[i][0], -font.width(stats[i][0]) / 2, 0, AS_MUTED, false);
			if (!stats[i][2].isEmpty()) {
				String sub = font.plainSubstrByWidth(stats[i][2], (int) (cw / 0.75f) - 4);
				context.drawString(font, sub, -font.width(sub) / 2, 34, AS_MUTED, false);
			}
			context.pose().popMatrix();
			UiScale.popDensity(old);
			drawCentered(context, font, stats[i][1], cx, sy + 17, 1.5f, 0xFFD1D1D6);
			if (i == 0) stars(context, cx - 17, sy + 27, info.rating(), 0xFFD1D1D6);
			if (i < 2) context.fill(14 + (i + 1) * cw, sy + 2, 14 + (i + 1) * cw + 1, sy + 34, AS_LINE);
		}
		// What it does
		int y = sy + 50;
		context.fill(14, y - 8, SCREEN_W - 14, y - 7, AS_LINE);
		context.drawString(font, "About", 14, y, TEXT, false);
		y += 14;
		for (FormattedCharSequence line : font.split(Component.literal(info.about()), SCREEN_W - 28)) {
			context.drawString(font, line, 14, y, 0xFFD1D1D6, false);
			y += 10;
		}
		// The details
		y += 10;
		context.drawString(font, "Information", 14, y, TEXT, false);
		y += 14;
		int size = 40 + Math.floorMod(a.label.hashCode(), 260);
		String[][] rows = {{"Provider", info.developer()}, {"Size", size + ".4 MB"}, {"Category", info.category()}, {"Price", "Free"}};
		for (String[] row : rows) {
			context.drawString(font, row[0], 14, y, AS_MUTED, false);
			String value = font.plainSubstrByWidth(row[1], SCREEN_W - 100);
			context.drawString(font, value, SCREEN_W - 14 - font.width(value), y, TEXT, false);
			context.fill(14, y + 12, SCREEN_W - 14, y + 13, AS_LINE);
			y += 18;
		}
		// Deleting it
		if (builtIn(a)) {
			context.drawString(font, "Comes with your phone", 14, y + 6, AS_MUTED, false);
		} else if (phone.installed(a)) {
			boolean hot = mx >= 14 && mx < SCREEN_W - 14 && my >= y + 2 && my < y + 22;
			Glass.rounded(context, 14, y + 2, SCREEN_W - 14, y + 22, 8, hot ? 0xFF3A2224 : AS_PANEL);
			context.drawString(font, "Delete App", (SCREEN_W - font.width("Delete App")) / 2, y + 8, RED, false);
			hits.add(new Hit(14, y + 2, SCREEN_W - 28, 20, () -> deleteApp(a)));
		}
	}

	// ---------------------------------------------------------------- Google Voice

	private static final int GV_BG = 0xFF1F1F1F, GV_PANEL = 0xFF2D2E30, GV_TEXT = 0xFFE3E3E3, GV_MUTED = 0xFF9AA0A6, GV_GREEN = 0xFF34A853, GV_BLUE = 0xFF8AB4F8;
	private static final String GV_NUMBER = "(415) 555-0142";
	/** The call log: who, what happened, how long, how long ago. */
	private static final String[][] GV_CALLS = {
		{"Targ #1", "Outgoing", "14:32", "3m"},
		{"Targ #2", "Outgoing", "2:07", "21m"},
		{"Targ #3", "Missed", "", "40m"},
		{"Targ #4", "Outgoing", "27:45", "1h"},
		{"Targ #5", "Incoming", "8:19", "2h"},
		{"Targ #6", "Outgoing", "0:41", "4h"},
		{"Targ #7", "Outgoing", "19:03", "Yesterday"},
		{"Targ #8", "Missed", "", "Mon"}};
	private static final String[][] GV_TEXTS = {
		{"Jeweler", "the bust down is ready, come by after 2"},
		{"Valet", "car's out front"},
		{"Promoter", "table for 8 locked in. bottles on ice"},
		{"Dealership", "Cullinan is detailed and ready for pickup"},
		{"Mom", "proud of you baby call me"}};
	private static final String[][] GV_VOICEMAILS = {
		{"Jet Charter", "0:34", "Hey, confirming wheels up at nine tomorrow out of Van Nuys, catering's sorted, see you then."},
		{"Dealership", "0:21", "Hi, your Cullinan's back from detail and ready whenever you want to swing by."},
		{"Lawyer", "0:12", "Call me back. Don't post anything until we talk."},
		{"Promoter", "0:18", "Yo, got you the big table tonight, sparklers and everything. Pull up around eleven."}};
	private static final String[] GV_TABS = {"Calls", "Contacts", "Messages", "Voicemail"};
	private int gvTab;
	private boolean gvDialing, gvMuted, gvSpeaker, gvKeypad;
	/** The voicemail playing, and when it started. */
	private int gvPlaying = -1;
	private long gvPlayStart;
	private String gvDialed = "", gvCalling;
	private long gvCallStart;

	/** Google Voice: your second number, with calls, texts and voicemail, a dialpad, and calls you can place. */
	private void drawVoice(GuiGraphics context, float mx, float my) {
		context.fill(0, 30, SCREEN_W, SCREEN_H - 12, GV_BG);
		if (phone.callState() != PhoneModule.CallState.NONE || phone.callEnded() != null) {
			drawRealCall(context, mx, my);
			return;
		}
		if (gvCalling != null) {
			drawVoiceCall(context, mx, my);
			return;
		}
		if (gvDialing) {
			drawVoiceDialpad(context, mx, my);
			return;
		}
		// The search bar, with your number under it
		Glass.rounded(context, 12, 36, SCREEN_W - 12, 60, 12, GV_PANEL);
		context.drawString(font, "Search Google Voice", 40, 44, GV_MUTED, false);
		voiceIcon(context, 18, 40, 16);
		context.drawString(font, "Your number  " + GV_NUMBER, 14, 68, GV_MUTED, false);
		int y = 84, tabsY = SCREEN_H - 50;
		switch (gvTab) {
			case 0 -> {
				// Real people first: anyone online with femboyware, a call over voice chat
				for (String name : phone.onlineUsers()) {
					if (y + 36 > tabsY) break;
					avatar(context, name, 14, y + 4, 26);
					Glass.rounded(context, 32, y + 22, 40, y + 30, 4, GV_BG);
					Glass.rounded(context, 33, y + 23, 39, y + 29, 3, GREEN_DOT);
					context.drawString(font, name, 48, y + 6, GV_TEXT, false);
					context.drawString(font, dev.femboyware.util.VoiceChat.available() ? "femboyware · voice chat" : "femboyware", 48, y + 18, GV_MUTED, false);
					Glass.rounded(context, SCREEN_W - 46, y + 6, SCREEN_W - 14, y + 26, 10, GV_GREEN);
					handset(context, SCREEN_W - 30, y + 16, 0xFFFFFFFF);
					hits.add(new Hit(0, y, SCREEN_W, 34, () -> {
						String problem = phone.call(name);
						if (problem != null) say(problem);
					}));
					y += 36;
				}
				for (String[] call : GV_CALLS) {
					if (y + 36 > tabsY) break;
					avatar(context, call[0], 14, y + 4, 26);
					boolean missed = call[1].equals("Missed");
					context.drawString(font, call[0], 48, y + 6, missed ? 0xFFF28B82 : GV_TEXT, false);
					String detail = (call[1].equals("Outgoing") ? "↗ " : missed ? "✕ " : "↙ ") + call[1] + (call[2].isEmpty() ? "" : " · " + call[2]);
					context.drawString(font, detail, 48, y + 18, GV_MUTED, false);
					context.drawString(font, call[3], SCREEN_W - 14 - font.width(call[3]), y + 6, GV_MUTED, false);
					String who = call[0];
					hits.add(new Hit(0, y, SCREEN_W, 34, () -> startCall(who)));
					y += 36;
				}
			}
			case 1 -> y = drawVoiceContacts(context, mx, my, y, tabsY);
			case 2 -> {
				for (String[] text : GV_TEXTS) {
					if (y + 36 > tabsY) break;
					avatar(context, text[0], 14, y + 4, 26);
					context.drawString(font, text[0], 48, y + 6, GV_TEXT, false);
					context.drawString(font, font.plainSubstrByWidth(text[1], SCREEN_W - 64), 48, y + 18, GV_MUTED, false);
					String who = text[0], said = text[1];
					// Opens the conversation in Messages
					hits.add(new Hit(0, y, SCREEN_W, 34, () -> {
						if (phone.thread(who).isEmpty()) phone.receive(who, said);
						openApp(App.MESSAGES);
						openThread(who);
					}));
					y += 36;
				}
			}
			default -> {
				for (int v = 0; v < GV_VOICEMAILS.length; v++) {
					String[] vm = GV_VOICEMAILS[v];
					List<FormattedCharSequence> lines = font.split(Component.literal("\"" + vm[2] + "\""), SCREEN_W - 64);
					int h = 22 + Math.min(3, lines.size()) * 10 + 8 + (gvPlaying == v ? 10 : 0);
					if (y + h > tabsY) break;
					avatar(context, vm[0], 14, y + 4, 26);
					context.drawString(font, vm[0], 48, y + 6, GV_TEXT, false);
					// Play: a progress bar runs for the voicemail's length, its words lighting up as it goes
					String[] len = vm[1].split(":");
					long total = (Long.parseLong(len[0]) * 60 + Long.parseLong(len[1])) * 1000;
					float done = gvPlaying == v ? Math.min(1, (System.currentTimeMillis() - gvPlayStart) / (float) total) : 0;
					if (gvPlaying == v && done >= 1) gvPlaying = -1;
					String right = gvPlaying == v ? "\u275A\u275A " + vm[1] : "\u25B6 " + vm[1];
					context.drawString(font, right, SCREEN_W - 14 - font.width(right), y + 6, gvPlaying == v ? GV_BLUE : GV_MUTED, false);
					int lit = Math.round(lines.size() * done);
					for (int i = 0; i < Math.min(3, lines.size()); i++) context.drawString(font, lines.get(i), 48, y + 19 + i * 10, gvPlaying == v && i < Math.max(1, lit) ? GV_TEXT : GV_MUTED, false);
					if (gvPlaying == v) {
						int by = y + 22 + Math.min(3, lines.size()) * 10 + 2;
						Glass.rounded(context, 48, by, SCREEN_W - 14, by + 3, 1, 0xFF3C4043);
						Glass.rounded(context, 48, by, 48 + Math.round((SCREEN_W - 62) * done), by + 3, 1, GV_BLUE);
					}
					int which = v;
					hits.add(new Hit(0, y, SCREEN_W, h, () -> {
						gvPlaying = gvPlaying == which ? -1 : which;
						gvPlayStart = System.currentTimeMillis();
					}));
					y += h;
				}
			}
		}
		// The dialpad button
		int fx = SCREEN_W - 52, fy = tabsY - 46;
		boolean hotFab = mx >= fx && mx < fx + 38 && my >= fy && my < fy + 38;
		Glass.rounded(context, fx, fy, fx + 38, fy + 38, 12, hotFab ? 0xFF4A5A70 : 0xFF394457);
		for (int r = 0; r < 3; r++) {
			for (int c = 0; c < 3; c++) context.fill(fx + 12 + c * 6, fy + 12 + r * 6, fx + 15 + c * 6, fy + 15 + r * 6, GV_BLUE);
		}
		hits.add(new Hit(fx, fy, 38, 38, () -> {
			gvDialing = true;
			gvDialed = "";
		}));
		// Tabs along the bottom
		context.fill(0, tabsY, SCREEN_W, SCREEN_H - 12, 0xFF262729);
		int tw = SCREEN_W / GV_TABS.length;
		for (int i = 0; i < GV_TABS.length; i++) {
			int tx = i * tw;
			boolean on = i == gvTab;
			if (on) Glass.rounded(context, tx + tw / 2 - 22, tabsY + 6, tx + tw / 2 + 22, tabsY + 22, 8, 0xFF3C4A5E);
			String glyph = i == 0 ? "☏" : i == 1 ? "☺" : i == 2 ? "✉" : "◉";
			drawCentered(context, font, glyph, tx + tw / 2f, tabsY + 14.5f, 1f, on ? GV_BLUE : GV_MUTED);
			context.drawString(font, GV_TABS[i], tx + (tw - font.width(GV_TABS[i])) / 2, tabsY + 26, on ? GV_TEXT : GV_MUTED, false);
			int which = i;
			hits.add(new Hit(tx, tabsY, tw, 38, () -> gvTab = which));
		}
	}

	/** Contacts: everyone online with femboyware right now, to call (over voice chat) or text. */
	private int drawVoiceContacts(GuiGraphics context, float mx, float my, int y, int tabsY) {
		List<String> people = phone.onlineUsers();
		String problem = phone.linkProblem();
		String head = problem != null ? problem : people.isEmpty() ? "No one else is online" : people.size() + " online with femboyware";
		context.drawString(font, font.plainSubstrByWidth(head, SCREEN_W - 28), 14, y, problem != null ? 0xFFF28B82 : GV_MUTED, false);
		y += 14;
		if (people.isEmpty()) {
			String hint = problem != null ? "Calls and texts go through femboyware.xyz" : "Friends show up here while they're playing with femboyware and the Phone module on";
			for (FormattedCharSequence line : font.split(Component.literal(hint), SCREEN_W - 28)) {
				context.drawString(font, line, 14, y, GV_MUTED, false);
				y += 10;
			}
			return y;
		}
		for (String name : people) {
			if (y + 36 > tabsY - 50) break;
			avatar(context, name, 14, y + 4, 26);
			Glass.rounded(context, 32, y + 22, 40, y + 30, 4, GV_BG);
			Glass.rounded(context, 33, y + 23, 39, y + 29, 3, GREEN_DOT);
			context.drawString(font, font.plainSubstrByWidth(name, SCREEN_W - 140), 48, y + 6, GV_TEXT, false);
			context.drawString(font, dev.femboyware.util.VoiceChat.available() ? "Online · voice chat" : "Online", 48, y + 18, GV_MUTED, false);
			// Message, then call
			int mxb = SCREEN_W - 82, cxb = SCREEN_W - 46;
			boolean hotMsg = mx >= mxb && mx < mxb + 32 && my >= y + 6 && my < y + 26;
			Glass.rounded(context, mxb, y + 6, mxb + 32, y + 26, 10, hotMsg ? 0xFF4A5A70 : GV_PANEL);
			drawCentered(context, font, "✉", mxb + 16f, y + 16.5f, 1f, GV_BLUE);
			hits.add(new Hit(mxb, y + 4, 32, 24, () -> {
				openApp(App.MESSAGES);
				openThread(name);
			}));
			boolean hotCall = mx >= cxb && mx < cxb + 32 && my >= y + 6 && my < y + 26;
			Glass.rounded(context, cxb, y + 6, cxb + 32, y + 26, 10, hotCall ? 0xFF46B866 : GV_GREEN);
			handset(context, cxb + 16, y + 16, 0xFFFFFFFF);
			Runnable call = () -> {
				String why = phone.call(name);
				if (why != null) say(why);
			};
			hits.add(new Hit(0, y, mxb - 4, 34, call));
			hits.add(new Hit(cxb, y + 4, 32, 24, call));
			y += 36;
		}
		return y;
	}

	private static final String[] DIAL_KEYS = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#"};
	private static final String[] DIAL_LETTERS = {"", "ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ", "", "+", ""};

	private void drawVoiceDialpad(GuiGraphics context, float mx, float my) {
		context.drawString(font, "‹ Back", 14, 40, GV_BLUE, false);
		hits.add(new Hit(8, 34, 50, 18, () -> gvDialing = false));
		String shown = gvDialed.isEmpty() ? "Enter a number" : formatNumber(gvDialed);
		if (gvDialed.isEmpty()) context.drawString(font, shown, (SCREEN_W - font.width(shown)) / 2, 92, GV_MUTED, false);
		else drawCentered(context, font, shown, SCREEN_W / 2f, 96, 1.8f, GV_TEXT);
		context.drawString(font, "Calling from " + GV_NUMBER, (SCREEN_W - font.width("Calling from " + GV_NUMBER)) / 2, 118, GV_MUTED, false);
		int size = 46, gap = 16, x0 = (SCREEN_W - 3 * size - 2 * gap) / 2, y0 = 140;
		for (int i = 0; i < DIAL_KEYS.length; i++) {
			int kx = x0 + (i % 3) * (size + gap), ky = y0 + (i / 3) * (size + 10);
			boolean hot = mx >= kx && mx < kx + size && my >= ky && my < ky + size;
			Glass.rounded(context, kx, ky, kx + size, ky + size, size / 2, hot ? 0xFF404246 : GV_PANEL);
			drawCentered(context, font, DIAL_KEYS[i], kx + size / 2f, ky + size / 2f - 3, 1.6f, GV_TEXT);
			if (!DIAL_LETTERS[i].isEmpty()) {
				int old = UiScale.pushDensity(Math.round(UiScale.density() * 0.7f));
				context.pose().pushMatrix();
				context.pose().translate(kx + size / 2f, ky + size / 2f + 10);
				context.pose().scale(0.7f, 0.7f);
				context.drawString(font, DIAL_LETTERS[i], -font.width(DIAL_LETTERS[i]) / 2, -4, GV_MUTED, false);
				context.pose().popMatrix();
				UiScale.popDensity(old);
			}
			String key = DIAL_KEYS[i];
			hits.add(new Hit(kx, ky, size, size, () -> {
				if (gvDialed.length() < 15) gvDialed += key;
			}));
		}
		int cy = y0 + 4 * (size + 10) + 4, cx = SCREEN_W / 2;
		boolean hotCall = mx >= cx - 26 && mx < cx + 26 && my >= cy && my < cy + 52;
		Glass.rounded(context, cx - 26, cy, cx + 26, cy + 52, 26, hotCall ? 0xFF46B866 : GV_GREEN);
		handset(context, cx, cy + 26, 0xFFFFFFFF);
		hits.add(new Hit(cx - 26, cy, 52, 52, () -> {
			if (gvDialed.isEmpty()) say("Enter a number");
			else startCall(formatNumber(gvDialed));
		}));
		if (!gvDialed.isEmpty()) {
			int bx = cx + 52;
			context.drawString(font, "⌫", bx, cy + 22, GV_MUTED, false);
			hits.add(new Hit(bx - 8, cy + 12, 28, 28, () -> gvDialed = gvDialed.substring(0, gvDialed.length() - 1)));
		}
	}

	/** Who you're on the phone with: their name, the call timer, and mute, keypad, speaker and the red button. */
	private void drawVoiceCall(GuiGraphics context, float mx, float my) {
		roundedGradient(context, 0, 30, SCREEN_W, SCREEN_H - 12, 0, 0xFF203048, 0xFF141518);
		avatar(context, gvCalling, SCREEN_W / 2 - 36, 80, 72);
		drawCentered(context, font, gvCalling, SCREEN_W / 2f, 176, 1.6f, GV_TEXT);
		long seconds = (System.currentTimeMillis() - gvCallStart) / 1000;
		String state = seconds < 3 ? "Calling..." : String.format(Locale.US, "%d:%02d", (seconds - 3) / 60, (seconds - 3) % 60);
		context.drawString(font, state, (SCREEN_W - font.width(state)) / 2, 194, GV_MUTED, false);
		context.drawString(font, "Google Voice " + GV_NUMBER, (SCREEN_W - font.width("Google Voice " + GV_NUMBER)) / 2, 208, GV_MUTED, false);
		callButtons(context, mx, my, gvMuted, () -> gvMuted = !gvMuted, gvSpeaker, () -> gvSpeaker = !gvSpeaker);
		int cx = SCREEN_W / 2, cy = 360;
		boolean hot = mx >= cx - 26 && mx < cx + 26 && my >= cy && my < cy + 52;
		Glass.rounded(context, cx - 26, cy, cx + 26, cy + 52, 26, hot ? 0xFFFF6F66 : 0xFFEA4335);
		context.pose().pushMatrix();
		context.pose().translate(cx, cy + 26);
		context.pose().rotate((float) (Math.PI * 0.75));
		handset(context, 0, 0, 0xFFFFFFFF);
		context.pose().popMatrix();
		hits.add(new Hit(cx - 26, cy, 52, 52, () -> {
			gvCalling = null;
			gvDialing = false;
			say("Call ended");
		}));
	}

	/**
	 * Mute, Keypad and Speaker: lit while on. The keypad slides up a row of tones over the buttons.
	 */
	private void callButtons(GuiGraphics context, float mx, float my, boolean muted, Runnable mute, boolean speaker, Runnable toggleSpeaker) {
		if (gvKeypad) {
			int size = 34, gap = 12, x0 = (SCREEN_W - 3 * size - 2 * gap) / 2, y0 = 228;
			for (int i = 0; i < DIAL_KEYS.length; i++) {
				int kx = x0 + (i % 3) * (size + gap), ky = y0 + (i / 3) * (size + 6);
				boolean hot = mx >= kx && mx < kx + size && my >= ky && my < ky + size;
				Glass.rounded(context, kx, ky, kx + size, ky + size, size / 2, hot ? 0x60FFFFFF : 0x30FFFFFF);
				drawCentered(context, font, DIAL_KEYS[i], kx + size / 2f, ky + size / 2f + 0.5f, 1.3f, GV_TEXT);
				int tone = i;
				hits.add(new Hit(kx, ky, size, size, () -> LarpSounds.play(LarpSounds.LEDGER_BEEP, 0.8f + tone * 0.05f, 0.5f)));
			}
			context.drawString(font, "Hide", SCREEN_W - 40, 214, GV_BLUE, false);
			hits.add(new Hit(SCREEN_W - 46, 208, 40, 16, () -> gvKeypad = false));
			return;
		}
		String[] buttons = {"Mute", "Keypad", "Speaker"};
		boolean[] on = {muted, false, speaker};
		Runnable[] actions = {mute, () -> gvKeypad = true, toggleSpeaker};
		for (int i = 0; i < 3; i++) {
			int bx = SCREEN_W / 2 - 80 + i * 60;
			boolean hot = mx >= bx && mx < bx + 40 && my >= 270 && my < 310;
			Glass.rounded(context, bx, 270, bx + 40, 310, 20, on[i] ? 0xFFE8EAED : hot ? 0x60FFFFFF : 0x40FFFFFF);
			drawCentered(context, font, i == 0 ? "\u2716" : i == 1 ? "#" : "\u266B", bx + 20f, 290.5f, 1.4f, on[i] ? 0xFF202124 : GV_TEXT);
			context.drawString(font, buttons[i], bx + 20 - font.width(buttons[i]) / 2, 314, on[i] ? GV_TEXT : GV_MUTED, false);
			hits.add(new Hit(bx, 270, 40, 52, actions[i]));
		}
	}

	/** A call with a real person: ringing, answering, and the call itself, with voice chat behind it. */
	private void drawRealCall(GuiGraphics context, float mx, float my) {
		roundedGradient(context, 0, 30, SCREEN_W, SCREEN_H - 12, 0, 0xFF203048, 0xFF141518);
		PhoneModule.CallState state = phone.callState();
		String who = phone.callWith() == null ? "" : phone.callWith();
		avatar(context, who.isEmpty() ? "?" : who, SCREEN_W / 2 - 36, 80, 72);
		drawCentered(context, font, who, SCREEN_W / 2f, 176, 1.6f, GV_TEXT);
		String line = switch (state) {
			case DIALING -> "Setting up voice chat...";
			case RINGING_OUT -> "Ringing...";
			case RINGING_IN -> "Incoming call";
			case CONNECTED -> {
				long seconds = (System.currentTimeMillis() - phone.connectedAt()) / 1000;
				yield String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
			}
			default -> phone.callEnded() == null ? "" : phone.callEnded();
		};
		context.drawString(font, line, (SCREEN_W - font.width(line)) / 2, 194, GV_MUTED, false);
		// Whether you'll hear each other
		String voice;
		int voiceColor = GV_MUTED;
		if (!dev.femboyware.util.VoiceChat.available()) voice = "Simple Voice Chat isn't installed";
		else if (!dev.femboyware.util.VoiceChat.connected()) voice = "Voice chat isn't connected here";
		else if (state == PhoneModule.CallState.CONNECTED && phone.callHasVoice()) {
			voice = "\u25CF On voice chat";
			voiceColor = GV_GREEN;
		} else voice = "femboyware call";
		context.drawString(font, voice, (SCREEN_W - font.width(voice)) / 2, 208, voiceColor, false);

		if (state == PhoneModule.CallState.RINGING_IN) {
			// Decline and Accept
			int dx = SCREEN_W / 2 - 66, ax = SCREEN_W / 2 + 14, cy = 360;
			Glass.rounded(context, dx, cy, dx + 52, cy + 52, 26, 0xFFEA4335);
			context.pose().pushMatrix();
			context.pose().translate(dx + 26, cy + 26);
			context.pose().rotate((float) (Math.PI * 0.75));
			handset(context, 0, 0, 0xFFFFFFFF);
			context.pose().popMatrix();
			Glass.rounded(context, ax, cy, ax + 52, cy + 52, 26, GV_GREEN);
			handset(context, ax + 26, cy + 26, 0xFFFFFFFF);
			context.drawString(font, "Decline", dx + 26 - font.width("Decline") / 2, cy + 58, GV_MUTED, false);
			context.drawString(font, "Accept", ax + 26 - font.width("Accept") / 2, cy + 58, GV_MUTED, false);
			hits.add(new Hit(dx, cy, 52, 52, phone::decline));
			hits.add(new Hit(ax, cy, 52, 52, phone::answer));
			return;
		}
		if (state == PhoneModule.CallState.NONE) return;
		callButtons(context, mx, my, dev.femboyware.util.VoiceChat.muted(), () -> dev.femboyware.util.VoiceChat.setMuted(!dev.femboyware.util.VoiceChat.muted()),
			!dev.femboyware.util.VoiceChat.deafened(), () -> dev.femboyware.util.VoiceChat.setDeafened(!dev.femboyware.util.VoiceChat.deafened()));
		int cx = SCREEN_W / 2, cy = 360;
		boolean hot = mx >= cx - 26 && mx < cx + 26 && my >= cy && my < cy + 52;
		Glass.rounded(context, cx - 26, cy, cx + 26, cy + 52, 26, hot ? 0xFFFF6F66 : 0xFFEA4335);
		context.pose().pushMatrix();
		context.pose().translate(cx, cy + 26);
		context.pose().rotate((float) (Math.PI * 0.75));
		handset(context, 0, 0, 0xFFFFFFFF);
		context.pose().popMatrix();
		hits.add(new Hit(cx - 26, cy, 52, 52, phone::hangUp));
	}

	private void startCall(String who) {
		gvMuted = false;
		gvSpeaker = false;
		gvKeypad = false;
		gvCalling = who;
		gvCallStart = System.currentTimeMillis();
	}

	/** Ten digits as (415) 555-0142, anything else as typed. */
	private static String formatNumber(String digits) {
		if (digits.length() == 10 && digits.chars().allMatch(Character::isDigit)) {
			return "(" + digits.substring(0, 3) + ") " + digits.substring(3, 6) + "-" + digits.substring(6);
		}
		return digits;
	}

	/** A phone handset, centred on (x, y), about 16 across. */
	private static void handset(GuiGraphics context, int x, int y, int color) {
		context.pose().pushMatrix();
		context.pose().translate(x, y);
		context.pose().rotate(-0.75f);
		context.fill(-2, -7, 2, 7, color);
		Glass.rounded(context, -4, -9, 3, -4, 2, color);
		Glass.rounded(context, -4, 4, 3, 9, 2, color);
		context.pose().popMatrix();
	}

	/** Google Voice's icon: a green speech bubble with a white handset. */
	private static void voiceIcon(GuiGraphics context, int x, int y, int size) {
		Glass.rounded(context, x, y, x + size, y + size * 85 / 100, size * 3 / 10, GV_GREEN);
		context.fill(x + size / 5, y + size * 75 / 100, x + size * 45 / 100, y + size, GV_GREEN);
		float s = size / 26f;
		context.pose().pushMatrix();
		context.pose().translate(x + size / 2f, y + size * 0.42f);
		context.pose().scale(s, s);
		handset(context, 0, 0, 0xFFFFFFFF);
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

	/** Which Roblox tab is open: Home, Discover, Avatar or Profile. */
	private int rbxTab;
	private static final String[] RBX_TABS = {"Home", "Discover", "Avatar", "Profile"};
	/** Made-up experiences: name, two colours for the thumbnail, rating, and how many are playing. */
	private static final Object[][] EXPERIENCES = {
		{"Mansion Tycoon", 0xFF2E7D5B, 0xFF0F3D2A, "94%", "38.2K", net.minecraft.world.item.Items.GOLD_BLOCK},
		{"Supercar Simulator", 0xFFE0475B, 0xFF4A0F1A, "91%", "52.7K", net.minecraft.world.item.Items.MINECART},
		{"Rich Life RP", 0xFFE3BE5C, 0xFF5A3A0E, "89%", "121K", net.minecraft.world.item.Items.GOLD_INGOT},
		{"Yacht Life", 0xFF3A8DDE, 0xFF0E2A5A, "92%", "17.4K", net.minecraft.world.item.Items.OAK_BOAT},
		{"Private Jet Obby", 0xFF9B59D0, 0xFF2A0E4A, "87%", "9.8K", net.minecraft.world.item.Items.ELYTRA},
		{"Bank Heist", 0xFF5A5F6B, 0xFF16181C, "90%", "64.1K", net.minecraft.world.item.Items.EMERALD}};
	private static final String[] RBX_FRIENDS = {"lam", "dex", "ghost", "kairo", "vee", "nova"};

	/**
	 * Roblox, laid out like the app: the top bar with your Robux, a tab bar along the bottom (Home,
	 * Discover, Avatar, Profile), and the page in between, which scrolls with the mouse wheel.
	 */
	private void drawRoblox(GuiGraphics context, float mx, float my) {
		context.fill(0, 30, SCREEN_W, SCREEN_H - 12, RBX_BG);
		// Top bar: the logo, your Robux, notifications
		context.pose().pushMatrix();
		context.pose().translate(20, 44);
		context.pose().rotate((float) Math.toRadians(15));
		context.fill(-6, -6, 6, 6, RBX_TEXT);
		context.fill(-2, -2, 2, 2, RBX_BG);
		context.pose().popMatrix();
		String robux = compact(phone.robux());
		int pill = font.width(robux) + 22;
		Glass.rounded(context, SCREEN_W - 34 - pill, 35, SCREEN_W - 34, 53, 9, RBX_TILE);
		robuxIcon(context, SCREEN_W - 30 - pill + 2, 40);
		context.drawString(font, robux, SCREEN_W - 34 - pill + 16, 40, RBX_TEXT, false);
		hits.add(new Hit(SCREEN_W - 34 - pill, 35, pill, 18, () -> say("R$ " + String.format(Locale.US, "%,d", phone.robux()))));
		Glass.rounded(context, SCREEN_W - 26, 37, SCREEN_W - 14, 49, 4, RBX_TEXT);
		context.fill(SCREEN_W - 23, 49, SCREEN_W - 17, 51, RBX_TEXT);
		Glass.rounded(context, SCREEN_W - 16, 34, SCREEN_W - 10, 40, 3, 0xFFE2231A);

		int top = 58, bottom = SCREEN_H - 46;
		context.enableScissor(0, top, SCREEN_W, bottom);
		int y = top + 2 - scroll;
		y = switch (rbxTab) {
			case 1 -> robloxDiscover(context, mx, my, y);
			case 2 -> robloxAvatar(context, mx, my, y);
			case 3 -> robloxProfile(context, mx, my, y);
			default -> robloxHome(context, mx, my, y);
		};
		context.disableScissor();
		maxScroll = Math.max(0, y + scroll - bottom + 6);
		// Taps on the page only count where it's showing
		hits.removeIf(h -> h.y() < top - 4 && h.y() > 56 || h.y() + h.h() > bottom + 4 && h.y() < SCREEN_H - 50);

		// The tab bar
		context.fill(0, bottom, SCREEN_W, SCREEN_H - 12, RBX_PANEL);
		context.fill(0, bottom, SCREEN_W, bottom + 1, 0xFF2B2D33);
		int tw = SCREEN_W / RBX_TABS.length;
		for (int i = 0; i < RBX_TABS.length; i++) {
			int cx = i * tw + tw / 2, color = i == rbxTab ? RBX_TEXT : 0xFF7C8088;
			int gy = bottom + 6;
			switch (i) {
				case 0 -> {
					context.fill(cx - 5, gy + 4, cx + 5, gy + 11, color);
					for (int k = 0; k < 5; k++) context.fill(cx - 1 - k, gy + k, cx + 1 + k, gy + k + 1, color);
				}
				case 1 -> {
					Glass.rounded(context, cx - 6, gy, cx + 6, gy + 12, 6, color);
					Glass.rounded(context, cx - 4, gy + 2, cx + 4, gy + 10, 4, RBX_PANEL);
					context.fill(cx - 1, gy + 3, cx + 2, gy + 9, color);
				}
				case 2 -> {
					Glass.rounded(context, cx - 3, gy, cx + 3, gy + 6, 3, color);
					Glass.rounded(context, cx - 6, gy + 7, cx + 6, gy + 13, 3, color);
				}
				default -> {
					face(context, cx - 6, gy, 12);
					if (i == rbxTab) {
						context.fill(cx - 7, gy - 1, cx + 7, gy, color);
						context.fill(cx - 7, gy + 12, cx + 7, gy + 13, color);
					}
				}
			}
			context.drawString(font, RBX_TABS[i], cx - font.width(RBX_TABS[i]) / 2, gy + 16, color, false);
			int tab = i;
			hits.add(new Hit(i * tw, bottom, tw, SCREEN_H - 12 - bottom, () -> {
				rbxTab = tab;
				scroll = 0;
			}));
		}
	}

	/** An experience's thumbnail: its colours, its item drawn big in the middle, and its name. */
	private void experienceTile(GuiGraphics context, Object[] e, int x, int y, int w, int h, boolean name) {
		roundedGradient(context, x, y, x + w, y + h, 8, (int) e[1], (int) e[2]);
		float s = Math.min(w, h) / 16f * (name ? 0.45f : 0.6f);
		context.pose().pushMatrix();
		context.pose().translate(x + w / 2f - 8 * s, y + h / 2f - 8 * s - (name ? 8 : 0));
		context.pose().scale(s, s);
		context.renderItem(new net.minecraft.world.item.ItemStack((net.minecraft.world.item.Item) e[5]), 0, 0);
		context.pose().popMatrix();
		if (name) {
			context.fill(x, y + h - 26, x + w, y + h - 8, 0x40000000);
			Glass.rounded(context, x, y + h - 10, x + w, y + h, 8, 0x40000000);
			List<FormattedCharSequence> lines = font.split(Component.literal((String) e[0]), w - 10);
			int n = Math.min(2, lines.size());
			for (int i = 0; i < n; i++) context.drawString(font, lines.get(i), x + 6, y + h - 12 - (n - 1 - i) * 10, 0xFFFFFFFF, true);
		}
	}

	private int robloxHome(GuiGraphics context, float mx, float my, int y) {
		String name = minecraft.player != null ? minecraft.player.getGameProfile().name() : "Player";
		Glass.rounded(context, 12, y, 42, y + 30, 15, RBX_TILE);
		face(context, 17, y + 5, 20);
		scaled(context, "Hello, " + name + "!", 48, y + 4, 1.2f, RBX_TEXT);
		context.drawString(font, "Welcome back", 48, y + 18, RBX_MUTED, false);
		y += 40;
		// Friends, online first
		context.drawString(font, "Friends (" + 200 + ")", 12, y, RBX_TEXT, false);
		context.drawString(font, "See All >", SCREEN_W - 12 - font.width("See All >"), y, RBX_MUTED, false);
		y += 14;
		int step = (SCREEN_W - 24) / RBX_FRIENDS.length;
		for (int i = 0; i < RBX_FRIENDS.length; i++) {
			int x = 12 + i * step;
			avatar(context, RBX_FRIENDS[i], x + 2, y, 26);
			Glass.rounded(context, x + 20, y + 19, x + 29, y + 28, 4, RBX_BG);
			Glass.rounded(context, x + 22, y + 21, x + 27, y + 26, 3, i < 4 ? RBX_GREEN : 0xFF3B82F6);
			context.drawString(font, font.plainSubstrByWidth(RBX_FRIENDS[i], step - 2), x + 15 - Math.min(step - 2, font.width(RBX_FRIENDS[i])) / 2, y + 30, RBX_MUTED, false);
		}
		y += 46;
		// Continue: where you left off
		context.drawString(font, "Continue", 12, y, RBX_TEXT, false);
		y += 14;
		int w = (SCREEN_W - 24 - 8) / 2;
		for (int i = 0; i < 2; i++) {
			Object[] e = EXPERIENCES[i];
			int x = 12 + i * (w + 8);
			experienceTile(context, e, x, y, w, w, true);
			int index = i;
			hits.add(new Hit(x, y, w, w, () -> join(index)));
		}
		y += w + 12;
		// Recommended: a grid with ratings and how many are playing
		context.drawString(font, "Recommended For You", 12, y, RBX_TEXT, false);
		y += 14;
		for (int i = 2; i < EXPERIENCES.length; i++) {
			Object[] e = EXPERIENCES[i];
			int x = 12 + ((i - 2) % 2) * (w + 8), ty = y + ((i - 2) / 2) * (w + 34);
			experienceTile(context, e, x, ty, w, w, false);
			context.drawString(font, font.plainSubstrByWidth((String) e[0], w), x, ty + w + 4, RBX_TEXT, false);
			context.drawString(font, "\u25B2 " + e[3] + "   " + e[4], x, ty + w + 15, RBX_MUTED, false);
			int index = i;
			hits.add(new Hit(x, ty, w, w + 24, () -> join(index)));
		}
		return y + ((EXPERIENCES.length - 1) / 2) * (w + 34);
	}

	private int robloxDiscover(GuiGraphics context, float mx, float my, int y) {
		scaled(context, "Discover", 12, y, 1.4f, RBX_TEXT);
		y += 20;
		Glass.rounded(context, 12, y, SCREEN_W - 12, y + 20, 10, RBX_TILE);
		context.drawString(font, "Search experiences", 24, y + 6, 0xFF7C8088, false);
		y += 28;
		String[] chips = {"Popular", "Top Earning", "Trending", "Roleplay"};
		int x = 12;
		for (int i = 0; i < chips.length; i++) {
			int cw = font.width(chips[i]) + 14;
			Glass.rounded(context, x, y, x + cw, y + 16, 8, i == 0 ? RBX_TEXT : RBX_TILE);
			context.drawString(font, chips[i], x + 7, y + 4, i == 0 ? RBX_BG : RBX_TEXT, false);
			x += cw + 5;
		}
		y += 26;
		for (int ei = 0; ei < EXPERIENCES.length; ei++) {
			Object[] e = EXPERIENCES[ei];
			int index = ei;
			boolean hot = mx >= 8 && mx < SCREEN_W - 8 && my >= y - 3 && my < y + 47;
			if (hot) Glass.rounded(context, 8, y - 3, SCREEN_W - 8, y + 47, 8, RBX_PANEL);
			experienceTile(context, e, 12, y, 44, 44, false);
			context.drawString(font, (String) e[0], 64, y + 6, RBX_TEXT, false);
			context.drawString(font, "\u25B2 " + e[3] + " rating", 64, y + 18, RBX_MUTED, false);
			Glass.rounded(context, 64, y + 30, 70, y + 36, 3, RBX_GREEN);
			context.drawString(font, e[4] + " playing", 74, y + 29, RBX_MUTED, false);
			hits.add(new Hit(8, y - 3, SCREEN_W - 16, 50, () -> join(index)));
			y += 54;
		}
		return y;
	}

	private int robloxAvatar(GuiGraphics context, float mx, float my, int y) {
		List<Limited> wearing = wearing();
		scaled(context, "Avatar", 12, y, 1.4f, RBX_TEXT);
		y += 20;
		// The stage: your avatar, drawn after the phone (see render)
		roundedGradient(context, 12, y, SCREEN_W - 12, y + 196, 10, 0xFF3A3F4B, 0xFF15161A);
		Glass.ellipse(context, SCREEN_W / 2 - 50, y + 172, SCREEN_W / 2 + 50, y + 188, 0x50000000);
		avatarBox = new int[]{phoneX + BEZEL + SCREEN_W / 2 - 66, phoneY + BEZEL + y + 4, phoneX + BEZEL + SCREEN_W / 2 + 66, phoneY + BEZEL + y + 190,
			phoneY + BEZEL + 58, phoneY + BEZEL + SCREEN_H - 46};
		Glass.rounded(context, SCREEN_W - 50, y + 174, SCREEN_W - 18, y + 190, 8, 0xC0000000);
		context.drawString(font, "3D", SCREEN_W - 40, y + 178, RBX_TEXT, false);
		y += 204;
		// Wear or take off each item: these switch the Cosmetics module's pieces
		CosmeticsModule cosmetics = FemboyWare.getModules().get(CosmeticsModule.class);
		String hornName = cosmetics.hornStyle() == CosmeticsModule.Horns.OFF ? "No horns" : dev.femboyware.setting.EnumSetting.prettify(cosmetics.hornStyle().name()) + " horns";
		String[] chips = {hornName, "Korblox", "Headless"};
		boolean[] wornChip = {cosmetics.hornStyle() != CosmeticsModule.Horns.OFF, cosmetics.korbloxOn(), cosmetics.headlessOn()};
		Runnable[] toggles = {cosmetics::cycleHorns, cosmetics::toggleKorblox, cosmetics::toggleHeadless};
		int x = 12;
		for (int i = 0; i < chips.length; i++) {
			int cw = font.width(chips[i]) + 12;
			Glass.rounded(context, x, y, x + cw, y + 16, 8, wornChip[i] ? RBX_TEXT : RBX_TILE);
			context.drawString(font, chips[i], x + 6, y + 4, wornChip[i] ? RBX_BG : RBX_TEXT, false);
			hits.add(new Hit(x, y, cw, 16, toggles[i]));
			x += cw + 4;
		}
		y += 24;
		context.drawString(font, "Currently Wearing", 12, y, RBX_TEXT, false);
		y += 14;
		int tile = (SCREEN_W - 24 - 3 * 6) / 4;
		if (wearing.isEmpty()) {
			context.drawString(font, "Turn on Cosmetics to wear items", 12, y, RBX_MUTED, false);
			y += 14;
		} else {
			for (int i = 0; i < Math.min(4, wearing.size()); i++) {
				int tx = 12 + i * (tile + 6);
				Glass.rounded(context, tx, y, tx + tile, y + tile, 6, RBX_TILE);
				context.blit(RenderPipelines.GUI_TEXTURED, roblox(wearing.get(i).id()), tx + 3, y + 3, 0, 0, tile - 6, tile - 6, 150, 150, 150, 150);
			}
			y += tile + 10;
		}
		long worn = 0;
		for (Limited l : wearing) worn += l.rap();
		context.drawString(font, "Outfit value", 12, y, RBX_MUTED, false);
		String value = compact(worn);
		robuxIcon(context, SCREEN_W - 22 - font.width(value), y);
		context.drawString(font, value, SCREEN_W - 12 - font.width(value), y, RBX_GREEN, false);
		return y + 16;
	}

	/**
	 * Your profile: headshot, name and Premium badge, friends and followers, About, Currently Wearing,
	 * your avatar, and your collection of limiteds.
	 */
	private int robloxProfile(GuiGraphics context, float mx, float my, int y) {
		String name = minecraft.player != null ? minecraft.player.getGameProfile().name() : "Player";
		List<Limited> wearing = wearing();
		// Header: headshot with an online dot, display name with the Premium badge, @username
		Glass.rounded(context, 12, y, 70, y + 58, 29, RBX_TILE);
		face(context, 23, y + 11, 36);
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
		hits.add(new Hit(12, y, bw, 20, () -> {
			rbxTab = 2;
			scroll = 0;
		}));
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
			phoneY + BEZEL + 58, phoneY + BEZEL + SCREEN_H - 46};
		Glass.rounded(context, SCREEN_W - 50, y + 128, SCREEN_W - 18, y + 144, 8, 0xC0000000);
		context.drawString(font, "3D", SCREEN_W - 40, y + 132, RBX_TEXT, false);
		y += 160;

		return y;
	}

	/**
	 * Joining an experience brings it to you: the mansion, a supercar, the yacht, the jet, the
	 * paparazzi, or the feds. The phone goes away so you can see it.
	 */
	private void join(int index) {
		String[] commands = {".mansion spawn", ".car spawn jesko", null, ".yacht spawn", ".jet spawn", ".fbi raid"};
		say("Joining " + EXPERIENCES[index][0] + "...");
		onClose();
		if (commands[index] != null) FemboyWare.getCommands().handle(commands[index]);
		else {
			PaparazziModule paparazzi = FemboyWare.getModules().get(PaparazziModule.class);
			if (!paparazzi.isEnabled()) paparazzi.toggle();
		}
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
	public void removed() {
		dev.femboyware.module.hud.Spotify.release(this);
		super.removed();
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent click) {
		spVolumeDrag = false;
		return super.mouseReleased(click);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		if (app == App.ROBLOX || app == App.X) {
			scroll = Mth.clamp(scroll - (int) (vertical * 24), 0, maxScroll);
			return true;
		}
		if (app == App.SPOTIFY) {
			if (spPlayerOpen && spPlayerAnim == 1) {
				float volume = dev.femboyware.module.hud.Spotify.volume();
				if (volume >= 0) dev.femboyware.module.hud.Spotify.setVolume(volume + (float) Math.signum(vertical) * 0.05f);
				return true;
			}
			if (spList != null) spListScroll = Mth.clamp(spListScroll - (int) (vertical * 24), 0, spListMaxScroll);
			else if (!spPlayerOpen) spScroll = Mth.clamp(spScroll - (int) (vertical * 24), 0, spMaxScroll);
			return true;
		}
		if (app == App.STORE) {
			if (asDetail == null && asTab == 1) asScroll = Mth.clamp(asScroll - (int) (vertical * 24), 0, asMaxScroll);
			return true;
		}
		if (app == App.TV) {
			tvScroll = Math.max(0, tvScroll - (int) Math.signum(vertical));
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
	}

	@Override
	public boolean keyPressed(KeyEvent input) {
		if (app == App.SPOTIFY && input.key() == GLFW.GLFW_KEY_SPACE) {
			dev.femboyware.module.hud.Spotify.playPause();
			return true;
		}
		if (app == App.COINBASE && cbSheet >= 0) {
			int key = input.key();
			if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) confirmSheet();
			else if (key == GLFW.GLFW_KEY_BACKSPACE) {
				if (!cbAmount.isEmpty()) cbAmount = cbAmount.substring(0, cbAmount.length() - 1);
			} else if (key == GLFW.GLFW_KEY_ESCAPE) cbSheet = -1;
			return true;
		}
		if (app == App.X && xFocused) {
			int key = input.key();
			if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) postX();
			else if (key == GLFW.GLFW_KEY_BACKSPACE) {
				if (!xDraft.isEmpty()) xDraft = xDraft.substring(0, xDraft.offsetByCodePoints(xDraft.length(), -1));
			} else if (key == GLFW.GLFW_KEY_ESCAPE) xFocused = false;
			else if (input.hasControlDown() && key == GLFW.GLFW_KEY_V) xDraft = dev.femboyware.manager.PhoneLink.clean(xDraft + minecraft.keyboardHandler.getClipboard());
			return true;
		}
		if (app == App.MESSAGES && thread == null && searchFocused) {
			int key = input.key();
			if (key == GLFW.GLFW_KEY_BACKSPACE) {
				if (!search.isEmpty()) search = search.substring(0, search.length() - 1);
			} else if (key == GLFW.GLFW_KEY_ESCAPE) {
				searchFocused = false;
				search = "";
			}
			return true;
		}
		if (app == App.BANK && wiring) {
			int key = input.key();
			if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) sendWire();
			else if (key == GLFW.GLFW_KEY_BACKSPACE) {
				if (!wireAmount.isEmpty()) wireAmount = wireAmount.substring(0, wireAmount.length() - 1);
			} else if (key == GLFW.GLFW_KEY_ESCAPE) wiring = false;
			return true;
		}
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
		if (typing()) {
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
			else if (app == App.DISCORD && discordChannel != null) discordChannel = null;
			else if (app == App.SPOTIFY && spPlayerOpen && spPlayerAnim == 1 && FemboyWare.getModules() != null && dev.femboyware.module.hud.SpotifyWeb.signedIn()) spPlayerOpen = false;
			else if (app == App.SPOTIFY && spList != null) spList = null;
			else if (app == App.COINBASE && cbDetail >= 0) cbDetail = -1;
			else if (app == App.STORE && asDetail != null) asDetail = null;
			else home();
			return true;
		}
		return super.keyPressed(input);
	}
}
