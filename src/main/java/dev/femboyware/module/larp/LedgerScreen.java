package dev.femboyware.module.larp;

import dev.femboyware.gui.Glass;
import dev.femboyware.gui.UiScale;
import dev.femboyware.module.larp.LedgerModule.Operation;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

/**
 * The fake wallet app: portfolio balance and chart, assets, latest operations and a receive window,
 * in a dark desktop wallet style, opened after the device "unlocks". Drawn on femboyware's canvas so
 * it stays sharp at any GUI scale; every number comes from {@link LedgerModule}.
 */
public class LedgerScreen extends Screen {
	private static final int BG = 0xFF131214, LINE = 0xFF27262B, CARD = 0xFF1C1B20, CARD_HOVER = 0xFF25242A;
	private static final int TEXT = 0xFFFFFFFF, MUTED = 0xFF8D8C94, FAINT = 0xFF5D5C63;
	private static final int PURPLE = 0xFFBBB0FF, GREEN = 0xFF66BE54, RED = 0xFFEA2E49;
	private static final int SIDEBAR = 128;
	private static final float UNLOCK_TIME = 1.8f, APPEAR_TIME = 0.35f;
	/** The app window's size, in canvas units. */
	private static final int WINDOW_W = 760, WINDOW_H = 440;
	private static final String[] NAV = {"Portfolio", "Market", "Accounts", "Discover", "Send", "Receive", "Swap", "Buy / Sell", "Earn", "My Ledger"};
	/** The DonutSMP profile's menu: your money and paying people. */
	private static final String[] MONEY_NAV = {"Portfolio", "Send"};
	/** Which profile is showing: your DonutSMP money, or the crypto wallet. Remembered between openings. */
	private static boolean money = true;
	/** Whether the Send window open is paying a player (the DonutSMP profile) rather than sending crypto. */
	private boolean flowPay;

	private enum Range {
		DAY("1D", 86_400_000L, "HH:mm"),
		WEEK("1W", 7 * 86_400_000L, "EEE HH:mm"),
		MONTH("1M", 30 * 86_400_000L, "MMM d"),
		YEAR("1Y", 365 * 86_400_000L, "MMM d, yyyy"),
		ALL("All", 4 * 365 * 86_400_000L, "MMM yyyy");

		final String label;
		final long span;
		final String timeFormat;

		Range(String label, long span, String timeFormat) {
			this.label = label;
			this.span = span;
			this.timeFormat = timeFormat;
		}
	}

	private record Hit(int x, int y, int w, int h, Runnable action) {
		boolean contains(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	private static final int POINTS = 160;
	/** A fixed wiggle for each range, so the chart doesn't jump about between frames. */
	private static final Map<Range, double[]> NOISE = new EnumMap<>(Range.class);
	private static final Map<Range, Double> GROWTH = new EnumMap<>(Range.class);

	static {
		Random random = new Random();
		double[][] growth = {{0, 0}, {-0.04, 0.14}, {0.05, 0.42}, {0.6, 3.2}, {6, 28}};
		for (Range range : Range.values()) {
			double[] walk = new double[POINTS];
			double v = 0;
			for (int i = 0; i < POINTS; i++) {
				v += random.nextGaussian();
				walk[i] = v;
			}
			// Pinned at both ends, so it starts at the range's growth and ends right on today's total
			double[] noise = new double[POINTS];
			for (int i = 0; i < POINTS; i++) noise[i] = walk[i] - walk[0] - (walk[POINTS - 1] - walk[0]) * i / (POINTS - 1);
			NOISE.put(range, noise);
			double[] g = growth[range.ordinal()];
			GROWTH.put(range, g[0] + random.nextDouble() * (g[1] - g[0]));
		}
	}

	private final LedgerModule wallet;
	private final List<Hit> hits = new ArrayList<>();
	private long opened;
	private Range range = Range.WEEK;
	private boolean hideBalance;
	private Coin receiving;
	/** PIN digits beeped so far, and whether the unlock chime has played. */
	private int beeped;
	private boolean unlockChimed;
	/** The page picked in the sidebar. */
	private String page = "Portfolio";

	private enum Flow { SEND, SWAP, BUY }

	/** A send, swap or buy in progress: what's picked, and how far through it is (0 form, 1 confirming, 2 done). */
	private Flow flow;
	private Coin flowCoin, flowTo;
	private int flowPreset = 1, flowStage;
	private long flowAt;
	private String flowAddress, flowResult;
	private static final double[] FRACTIONS = {0.1, 0.25, 0.5, 1};
	private static final double[] DOLLARS = {1_000, 10_000, 100_000, 1_000_000};
	private String receiveAddress;
	private String status;
	private long statusAt;

	public LedgerScreen(LedgerModule wallet) {
		super(Component.literal("Ledger Live"));
		this.wallet = wallet;
	}

	@Override
	protected void init() {
		if (opened == 0) {
			opened = System.currentTimeMillis();
			// The exact figure for the balance card; the server's answer stays out of chat
			if (money) wallet.requestBalance();
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void renderBackground(GuiGraphics context, int mouseX, int mouseY, float deltaTicks) {
		// Just a light dim: the game stays visible round the app's window
		context.fill(0, 0, width, height, 0x50000000);
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
	}

	/** The app needs about 760 by 440 units to lay out, so it picks the sharpest density that leaves that much room. */
	/**
	 * Text needs at least 2 screen pixels per unit to use the smooth font, so the density never
	 * goes below that; on a window too small for the layout at that density, everything is shrunk
	 * to fit instead (see {@link #scale}).
	 */
	private int density() {
		var window = minecraft.getWindow();
		return Math.max(UiScale.MIN_CLEAN_DENSITY, Math.min(window.getWidth() / (WINDOW_W + 60), window.getHeight() / (WINDOW_H + 60)));
	}

	/** Screen pixels per layout unit over GUI scale: the density, shrunk if the layout wouldn't fit. */
	private float scale() {
		var window = minecraft.getWindow();
		int density = density();
		float fit = Math.min(1f, Math.min(window.getWidth() / (float) density / (WINDOW_W + 60), window.getHeight() / (float) density / (WINDOW_H + 60)));
		return density * fit / (float) window.getGuiScale();
	}

	private float seconds() {
		return (System.currentTimeMillis() - opened) / 1000f;
	}

	private boolean unlocking() {
		return wallet.unlockAnimation() && seconds() < UNLOCK_TIME;
	}

	/** The app's window: its size, and where its top left sits on the canvas. */
	private int windowX, windowY;

	private void renderScaled(GuiGraphics context, int canvasW, int canvasH, float canvasMx, float canvasMy) {
		hits.clear();
		if (unlocking()) {
			drawUnlock(context, canvasW, canvasH, seconds());
			return;
		}
		int w = Math.min(canvasW - 32, WINDOW_W), h = Math.min(canvasH - 32, WINDOW_H);
		windowX = (canvasW - w) / 2;
		windowY = (canvasH - h) / 2;
		float mx = canvasMx - windowX, my = canvasMy - windowY;
		// Clicking outside the window closes the app
		hits.add(new Hit(-windowX, -windowY, canvasW, canvasH, this::onClose));
		hits.add(new Hit(0, 0, w, h, () -> { }));

		float since = seconds() - (wallet.unlockAnimation() ? UNLOCK_TIME : 0);
		float appear = ease(Mth.clamp(since / APPEAR_TIME, 0, 1));
		context.pose().pushMatrix();
		context.pose().translate(windowX, windowY + (1 - appear) * 14);
		Glass.draw(context, -1, -1, w + 1, h + 1, 12, 0x30000000, true);
		Glass.rounded(context, -1, -1, w + 1, h + 1, 12, 0xFF2C2B31);
		Glass.rounded(context, 0, 0, w, h, 11, BG);
		drawClose(context, w, mx, my);

		drawSidebar(context, h, mx, my);
		int left = SIDEBAR + 18, top = 14;
		boolean wide = w - left > 520;
		int rightWidth = wide ? 200 : 0;
		int mainRight = w - 16 - (wide ? rightWidth + 14 : 0);
		drawTopBar(context, left, w - 34, top, mx, my);
		int y = top + 30;
		if (money) {
			y = drawMoneyPortfolio(context, left, y, mainRight, mx, my);
			y = drawMoneyActions(context, left, y + 10, mx, my);
			drawPayments(context, left, y + 10, mainRight, h - 14, mx, my);
			if (wide) drawTopPayers(context, w - 16 - rightWidth, top + 30, w - 16, h - 14);
		} else if (page.equals("Portfolio")) {
			y = drawPortfolio(context, left, y, mainRight, mx, my);
			y = drawActions(context, left, y + 10, mx, my);
			drawAssets(context, left, y + 10, mainRight, h - 14, mx, my);
			if (wide) drawOperations(context, w - 16 - rightWidth, top + 30, w - 16, h - 14);
		} else drawPage(context, left, y, w - 16, h - 14, mx, my);

		if (receiving != null) drawReceive(context, w, h, mx, my);
		if (flow != null) drawFlow(context, w, h, mx, my);
		if (status != null && System.currentTimeMillis() - statusAt < 2600) {
			int sw = font.width(status) + 24;
			Glass.rounded(context, (w - sw) / 2, h - 34, (w + sw) / 2, h - 14, 6, 0xF0242329);
			context.drawString(font, status, (w - font.width(status)) / 2, h - 28, TEXT, false);
		}
		context.pose().popMatrix();
	}

	/** A small x in the window's top right corner. */
	private void drawClose(GuiGraphics context, int w, float mx, float my) {
		int x = w - 22, y = 6;
		boolean hot = mx >= x && mx < x + 16 && my >= y && my < y + 16;
		if (hot) Glass.rounded(context, x, y, x + 16, y + 16, 8, 0xFF2E2D34);
		for (int i = 0; i < 7; i++) {
			context.fill(x + 4 + i, y + 4 + i, x + 6 + i, y + 6 + i, hot ? TEXT : MUTED);
			context.fill(x + 10 - i, y + 4 + i, x + 12 - i, y + 6 + i, hot ? TEXT : MUTED);
		}
		hits.add(new Hit(x, y, 16, 16, this::onClose));
	}

	// ---------------------------------------------------------------- unlock

	/** The device wakes up, the PIN dots fill in, it says unlocked, and the app comes in. */
	private void drawUnlock(GuiGraphics context, int w, int h, float t) {
		int dw = 168, dh = 50;
		float shrink = t > UNLOCK_TIME - 0.3f ? 1 - ease((t - (UNLOCK_TIME - 0.3f)) / 0.3f) * 0.08f : 1;
		int cx = w / 2, cy = h / 2 - 10;
		context.pose().pushMatrix();
		context.pose().translate(cx, cy);
		context.pose().scale(shrink, shrink);
		// The body, a steel swivel cover round its end, and its little screen
		Glass.rounded(context, -dw / 2, -dh / 2, dw / 2, dh / 2, 12, 0xFF1A1A1D);
		Glass.rounded(context, -dw / 2 + 1, -dh / 2 + 1, dw / 2 - 1, dh / 2 - 1, 11, 0xFF0D0D0F);
		Glass.rounded(context, dw / 2 - 58, -dh / 2 - 3, dw / 2 + 6, dh / 2 + 3, 14, 0xFF9A9CA3);
		Glass.rounded(context, dw / 2 - 56, -dh / 2 - 1, dw / 2 + 4, dh / 2 + 1, 13, 0xFFB9BBC2);
		Glass.rounded(context, dw / 2 - 30, -9, dw / 2 - 12, 9, 9, 0xFF2A2A2E);
		int sx = -dw / 2 + 14, sy = -dh / 2 + 10, sw = 76, sh = dh - 20;
		context.fill(sx, sy, sx + sw, sy + sh, 0xFF000000);
		if (t < 0.45f) {
			centered(context, "Ledger", sx + sw / 2, sy + sh / 2 - 4, withAlpha(TEXT, Mth.clamp(t / 0.25f, 0, 1)));
		} else if (t < 1.25f) {
			centered(context, "Enter PIN", sx + sw / 2, sy + 4, TEXT);
			int filled = Math.min(8, (int) ((t - 0.45f) / 0.8f * 9));
			// A beep for each PIN digit as it goes in
			if (filled > beeped) {
				beeped = filled;
				LarpSounds.play(LarpSounds.LEDGER_BEEP, 1 + filled * 0.02f, 0.5f);
			}
			for (int i = 0; i < 8; i++) {
				int dx = sx + 9 + i * 8, dy = sy + sh - 9;
				if (i < filled) Glass.rounded(context, dx, dy, dx + 5, dy + 5, 3, TEXT);
				else Glass.rounded(context, dx + 1, dy + 2, dx + 4, dy + 3, 1, FAINT);
			}
		} else {
			centered(context, "Unlocked", sx + sw / 2, sy + sh / 2 - 4, GREEN);
			if (!unlockChimed) {
				unlockChimed = true;
				LarpSounds.play(LarpSounds.LEDGER_SUCCESS, 0.7f);
			}
		}
		context.pose().popMatrix();
		String hint = t < 1.25f ? "Connect and unlock your device" : "Opening Ledger Live";
		centered(context, hint, cx, cy + dh / 2 + 22, MUTED);
		hits.add(new Hit(0, 0, w, h, () -> opened = System.currentTimeMillis() - (long) (UNLOCK_TIME * 1000)));
	}

	// ---------------------------------------------------------------- frame

	private void drawSidebar(GuiGraphics context, int h, float mx, float my) {
		context.fill(SIDEBAR, 0, SIDEBAR + 1, h, LINE);
		scaled(context, "Ledger Live", 16, 15, 1.25f, TEXT);
		// The two profiles: DonutSMP money and the crypto wallet
		int px = 10, pw = (SIDEBAR - 20) / 2;
		for (int i = 0; i < 2; i++) {
			boolean isMoney = i == 0, picked = money == isMoney;
			int x = px + i * pw;
			boolean hot = mx >= x && mx < x + pw && my >= 36 && my < 52;
			Glass.rounded(context, x, 36, x + pw - 2, 52, 8, picked ? 0xFF2F2C45 : hot ? CARD_HOVER : 0xFF1E1D22);
			String label = isMoney ? "DonutSMP" : "Crypto";
			context.drawString(font, label, x + (pw - 2 - font.width(label)) / 2, 40, picked ? PURPLE : hot ? TEXT : MUTED, false);
			hits.add(new Hit(x, 36, pw - 2, 16, () -> switchProfile(isMoney)));
		}
		int y = 66;
		for (String item : money ? MONEY_NAV : NAV) {
			boolean selected = item.equals(page);
			boolean hot = mx >= 8 && mx < SIDEBAR - 8 && my >= y - 4 && my < y + 13;
			if (selected) Glass.rounded(context, 8, y - 4, SIDEBAR - 8, y + 13, 5, 0xFF26252C);
			else if (hot) Glass.rounded(context, 8, y - 4, SIDEBAR - 8, y + 13, 5, 0xFF1E1D22);
			if (selected) context.fill(8, y - 1, 10, y + 10, PURPLE);
			navIcon(context, item, 18, y, selected ? PURPLE : MUTED);
			context.drawString(font, item, 34, y, selected ? TEXT : hot ? 0xFFCFCED6 : MUTED, false);
			String clicked = item;
			hits.add(new Hit(8, y - 4, SIDEBAR - 16, 17, () -> nav(clicked)));
			y += 20;
		}
	}

	/** Tiny line icons for the menu, drawn from boxes. */
	private void navIcon(GuiGraphics context, String item, int x, int y, int color) {
		switch (item) {
			case "Portfolio" -> {
				context.fill(x, y + 6, x + 2, y + 9, color);
				context.fill(x + 3, y + 3, x + 5, y + 9, color);
				context.fill(x + 6, y, x + 8, y + 9, color);
			}
			case "Send" -> {
				context.fill(x + 3, y, x + 5, y + 9, color);
				context.fill(x + 1, y + 2, x + 7, y + 3, color);
			}
			case "Receive" -> {
				context.fill(x + 3, y, x + 5, y + 9, color);
				context.fill(x + 1, y + 6, x + 7, y + 7, color);
			}
			case "Swap" -> {
				context.fill(x, y + 2, x + 8, y + 3, color);
				context.fill(x, y + 6, x + 8, y + 7, color);
			}
			default -> {
				context.fill(x, y + 1, x + 8, y + 2, color);
				context.fill(x, y + 8, x + 8, y + 9, color);
				context.fill(x, y + 1, x + 1, y + 9, color);
				context.fill(x + 7, y + 1, x + 8, y + 9, color);
			}
		}
	}

	private void switchProfile(boolean toMoney) {
		if (money == toMoney) return;
		money = toMoney;
		page = "Portfolio";
		flow = null;
		receiving = null;
		if (money) wallet.requestBalance();
	}

	private void nav(String item) {
		switch (item) {
			case "Receive" -> openReceive(wallet.held().isEmpty() ? Coin.BTC : wallet.held().getFirst());
			case "Send" -> openFlow(Flow.SEND);
			case "Pay" -> openPay("");
			case "Refresh" -> {
				wallet.requestBalance();
				say("Checking your balance with the server");
			}
			case "Swap" -> openFlow(Flow.SWAP);
			case "Buy / Sell", "Buy" -> openFlow(Flow.BUY);
			default -> page = item;
		}
	}

	private void drawTopBar(GuiGraphics context, int left, int right, int top, float mx, float my) {
		String eye = hideBalance ? "Show balance" : "Hide balance";
		int ex = right - font.width(eye);
		boolean hot = mx >= ex - 4 && mx < right + 2 && my >= top - 3 && my < top + 11;
		context.drawString(font, eye, ex, top, hot ? TEXT : MUTED, false);
		hits.add(new Hit(ex - 4, top - 3, font.width(eye) + 6, 14, () -> hideBalance = !hideBalance));
		String synced = "Synchronized";
		int sx = ex - 22 - font.width(synced);
		Glass.rounded(context, sx - 9, top + 2, sx - 4, top + 7, 3, GREEN);
		context.drawString(font, synced, sx, top, MUTED, false);
		scaled(context, money && page.equals("Portfolio") ? "DonutSMP" : page, left, top - 2, 1.5f, TEXT);
	}

	// ---------------------------------------------------------------- the DonutSMP profile

	/** Your balance going back over the range, worked out from the payments since each moment. */
	private double[] moneySeries(double balance) {
		List<LedgerModule.Payment> pays = wallet.payments();
		long now = System.currentTimeMillis();
		double[] values = new double[POINTS];
		for (int i = 0; i < POINTS; i++) {
			long at = now - (long) (range.span * (1 - i / (double) (POINTS - 1)));
			double v = balance;
			for (LedgerModule.Payment p : pays) {
				if (p.time() <= at) break;
				v -= p.received() ? p.amount() : -p.amount();
			}
			values[i] = Math.max(0, v);
		}
		return values;
	}

	/** The balance card: your DonutSMP money, what came in and went out over the range, and a chart of it. */
	private int drawMoneyPortfolio(GuiGraphics context, int left, int top, int right, float mx, float my) {
		int bottom = top + 178;
		Glass.rounded(context, left, top, right, bottom, 8, CARD);
		double balance = wallet.balance();
		boolean exact = wallet.balanceExact();
		context.drawString(font, "DonutSMP balance" + (balance >= 0 && !exact ? "  ·  from the scoreboard" : ""), left + 14, top + 12, MUTED, false);
		String shown = balance < 0 ? "Checking..." : hideBalance ? "******" : LedgerModule.usd(balance);
		scaled(context, shown, left + 14, top + 26, 2.2f, balance < 0 ? MUTED : TEXT);
		double in = 0, out = 0;
		long since = System.currentTimeMillis() - range.span;
		for (LedgerModule.Payment p : wallet.payments()) {
			if (p.time() < since) break;
			if (p.received()) in += p.amount();
			else out += p.amount();
		}
		String inText = "+" + (hideBalance ? "***" : LedgerModule.usd(in)) + " in", outText = "-" + (hideBalance ? "***" : LedgerModule.usd(out)) + " out";
		context.drawString(font, inText, left + 14, top + 50, GREEN, false);
		context.drawString(font, outText, left + 22 + font.width(inText), top + 50, out > 0 ? RED : MUTED, false);
		int tx = right - 14;
		for (int i = Range.values().length - 1; i >= 0; i--) {
			Range r = Range.values()[i];
			int tw = font.width(r.label) + 12;
			tx -= tw;
			boolean picked = r == range;
			boolean hot = mx >= tx && mx < tx + tw && my >= top + 10 && my < top + 26;
			if (picked) Glass.rounded(context, tx, top + 10, tx + tw, top + 26, 8, 0xFF2F2C45);
			else if (hot) Glass.rounded(context, tx, top + 10, tx + tw, top + 26, 8, CARD_HOVER);
			context.drawString(font, r.label, tx + 6, top + 14, picked ? PURPLE : hot ? TEXT : MUTED, false);
			hits.add(new Hit(tx, top + 10, tw, 16, () -> range = r));
			tx -= 4;
		}
		drawChart(context, moneySeries(Math.max(0, balance)), left + 14, top + 68, right - 14, bottom - 12, mx, my);
		return bottom;
	}

	private int drawMoneyActions(GuiGraphics context, int left, int top, float mx, float my) {
		String[] actions = {"Pay", "Refresh"};
		int x = left;
		for (String action : actions) {
			String label = action.equals("Pay") ? "Pay a player" : "Refresh balance";
			int bw = font.width(label) + 28;
			boolean hot = mx >= x && mx < x + bw && my >= top && my < top + 20;
			boolean primary = action.equals("Pay");
			Glass.rounded(context, x, top, x + bw, top + 20, 10, primary ? (hot ? 0xFFCEC6FF : PURPLE) : hot ? 0xFF2E2D34 : 0xFF26252B);
			context.drawString(font, label, x + 14, top + 6, primary ? 0xFF131214 : TEXT, false);
			hits.add(new Hit(x, top, bw, 20, () -> nav(action)));
			x += bw + 8;
		}
		return top + 20;
	}

	/** Who's paid you and who you've paid, newest first. Clicking one pays that player. */
	private void drawPayments(GuiGraphics context, int left, int top, int right, int bottom, float mx, float my) {
		if (bottom - top < 40) return;
		Glass.rounded(context, left, top, right, bottom, 8, CARD);
		context.drawString(font, "Latest operations", left + 12, top + 12, TEXT, false);
		List<LedgerModule.Payment> pays = wallet.payments();
		if (pays.isEmpty()) {
			context.drawString(font, "No payments yet. When someone pays you, or you pay someone, it shows here.", left + 12, top + 32, MUTED, false);
			return;
		}
		int y = top + 30;
		long now = System.currentTimeMillis();
		for (LedgerModule.Payment p : pays) {
			if (y + 22 > bottom - 6) break;
			boolean hot = mx >= left + 6 && mx < right - 6 && my >= y - 3 && my < y + 19;
			if (hot) Glass.rounded(context, left + 6, y - 3, right - 6, y + 19, 5, CARD_HOVER);
			Glass.rounded(context, left + 10, y, left + 26, y + 16, 8, p.received() ? 0xFF1F3320 : 0xFF3A1D24);
			int ax = left + 17, arrow = p.received() ? GREEN : RED;
			context.fill(ax, y + 4, ax + 2, y + 12, arrow);
			if (p.received()) context.fill(ax - 2, y + 9, ax + 4, y + 10, arrow);
			else context.fill(ax - 2, y + 5, ax + 4, y + 6, arrow);
			context.drawString(font, (p.received() ? "Received from " : "Paid to ") + p.player(), left + 32, y, TEXT, false);
			context.drawString(font, ago(now - p.time()), left + 32, y + 9, FAINT, false);
			String amount = (p.received() ? "+" : "-") + LedgerModule.usd(p.amount());
			rightText(context, hideBalance ? "***" : amount, right - 12, y + 4, p.received() ? GREEN : TEXT);
			String who = p.player();
			hits.add(new Hit(left + 6, y - 3, right - left - 12, 22, () -> openPay(who)));
			y += 24;
		}
	}

	/** The people who've paid you most over the range. */
	private void drawTopPayers(GuiGraphics context, int left, int top, int right, int bottom) {
		Glass.rounded(context, left, top, right, bottom, 8, CARD);
		context.drawString(font, "Top payers (" + range.label + ")", left + 12, top + 12, TEXT, false);
		Map<String, Double> totals = new java.util.HashMap<>();
		long since = System.currentTimeMillis() - range.span;
		for (LedgerModule.Payment p : wallet.payments()) {
			if (p.time() < since) break;
			if (p.received()) totals.merge(p.player(), p.amount(), Double::sum);
		}
		if (totals.isEmpty()) {
			context.drawString(font, "Nobody yet", left + 12, top + 32, MUTED, false);
			return;
		}
		List<Map.Entry<String, Double>> sorted = new ArrayList<>(totals.entrySet());
		sorted.sort(Map.Entry.<String, Double>comparingByValue().reversed());
		int y = top + 32, rank = 1;
		for (Map.Entry<String, Double> e : sorted) {
			if (y + 12 > bottom - 6) break;
			String amount = hideBalance ? "***" : LedgerModule.usd(e.getValue());
			int room = right - 12 - font.width(amount) - 8 - (left + 12);
			context.drawString(font, font.plainSubstrByWidth(rank++ + ". " + e.getKey(), Math.max(0, room)), left + 12, y, TEXT, false);
			rightText(context, amount, right - 12, y, GREEN);
			y += 16;
		}
	}

	/** Opens the Send window paying a player, with {@code name} already filled in. */
	private void openPay(String name) {
		openFlow(Flow.SEND);
		flowPay = true;
		payName = name;
		payFocus = name.isEmpty() ? 0 : 1;
	}

	// ---------------------------------------------------------------- portfolio

	private double[] series(double total) {
		double[] noise = NOISE.get(range);
		double growth = range == Range.DAY ? dayGrowth(total) : GROWTH.get(range);
		double start = Math.log(1 / (1 + growth));
		double wiggle = range == Range.DAY ? 0.004 : range == Range.WEEK ? 0.008 : 0.02;
		double[] values = new double[POINTS];
		for (int i = 0; i < POINTS; i++) {
			double along = i / (double) (POINTS - 1);
			values[i] = total * Math.exp(start * (1 - along) + noise[i] * wiggle);
		}
		return values;
	}

	private double dayGrowth(double total) {
		double change = wallet.totalChange();
		return total - change <= 0 ? 0 : change / (total - change);
	}

	/** Returns the y below the card. */
	private int drawPortfolio(GuiGraphics context, int left, int top, int right, float mx, float my) {
		int bottom = top + 178;
		Glass.rounded(context, left, top, right, bottom, 8, CARD);
		double total = wallet.total();
		double[] values = series(total);
		double changeValue = total - values[0];
		double changePercent = values[0] <= 0 ? 0 : changeValue / values[0] * 100;

		context.drawString(font, "Total balance", left + 14, top + 12, MUTED, false);
		scaled(context, hideBalance ? "******" : LedgerModule.usd(total), left + 14, top + 26, 2.2f, TEXT);
		int color = changeValue >= 0 ? GREEN : RED;
		String change = String.format(Locale.US, "%s%.2f%%", changeValue >= 0 ? "+" : "", changePercent)
			+ (hideBalance ? "" : "  (" + (changeValue >= 0 ? "+" : "-") + LedgerModule.usd(Math.abs(changeValue)) + ")");
		context.drawString(font, change, left + 14, top + 50, color, false);

		// Range tabs, top right of the card
		int tx = right - 14;
		for (int i = Range.values().length - 1; i >= 0; i--) {
			Range r = Range.values()[i];
			int tw = font.width(r.label) + 12;
			tx -= tw;
			boolean picked = r == range;
			boolean hot = mx >= tx && mx < tx + tw && my >= top + 10 && my < top + 26;
			if (picked) Glass.rounded(context, tx, top + 10, tx + tw, top + 26, 8, 0xFF2F2C45);
			else if (hot) Glass.rounded(context, tx, top + 10, tx + tw, top + 26, 8, CARD_HOVER);
			context.drawString(font, r.label, tx + 6, top + 14, picked ? PURPLE : hot ? TEXT : MUTED, false);
			hits.add(new Hit(tx, top + 10, tw, 16, () -> range = r));
			tx -= 4;
		}

		drawChart(context, values, left + 14, top + 68, right - 14, bottom - 12, mx, my);
		return bottom;
	}

	/** The balance over the range: a smooth line with a fading fill under it, drawn pixel by pixel. */
	private void drawChart(GuiGraphics context, double[] values, int x1, int y1, int x2, int y2, float mx, float my) {
		double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
		for (double v : values) {
			min = Math.min(min, v);
			max = Math.max(max, v);
		}
		double span = Math.max(max - min, max * 0.002 + 1e-9);
		min -= span * 0.08;
		max += span * 0.08;
		int d = UiScale.density();
		int px1 = x1 * d, px2 = x2 * d, py1 = y1 * d, py2 = y2 * d;
		int thick = Math.max(2, Math.round(d * 0.7f));
		context.pose().pushMatrix();
		context.pose().scale(1f / d, 1f / d);
		int prevY = -1;
		for (int px = px1; px < px2; px++) {
			double along = (px - px1) / (double) Math.max(1, px2 - px1 - 1) * (POINTS - 1);
			int i = Math.min(POINTS - 2, (int) along);
			double v = Mth.lerp(along - i, values[i], values[i + 1]);
			int py = (int) Math.round(py2 - (v - min) / (max - min) * (py2 - py1));
			context.fillGradient(px, py, px + 1, py2, 0x40BBB0FF, 0x00BBB0FF);
			int from = prevY < 0 ? py : Math.min(prevY, py), to = prevY < 0 ? py : Math.max(prevY, py);
			context.fill(px, from - thick / 2, px + 1, to + (thick + 1) / 2, PURPLE);
			prevY = py;
		}
		context.pose().popMatrix();

		// Hover: a line down to the point under the mouse, with its value and when
		if (mx >= x1 && mx < x2 && my >= y1 && my < y2) {
			double along = (mx - x1) / Math.max(1, x2 - x1 - 1) * (POINTS - 1);
			int i = Mth.clamp((int) along, 0, POINTS - 2);
			double v = Mth.lerp(along - i, values[i], values[i + 1]);
			int hx = Math.round(mx);
			int hy = (int) Math.round(y2 - (v - min) / (max - min) * (y2 - y1));
			context.fill(hx, y1, hx + 1, y2, 0x60FFFFFF);
			Glass.rounded(context, hx - 3, hy - 3, hx + 3, hy + 3, 3, PURPLE);
			long when = System.currentTimeMillis() - (long) (range.span * (1 - along / (POINTS - 1)));
			String value = hideBalance ? "******" : LedgerModule.usd(v);
			String time = new SimpleDateFormat(range.timeFormat, Locale.US).format(new Date(when));
			int tw = Math.max(font.width(value), font.width(time)) + 14;
			int tx = Mth.clamp(hx + 8, x1, x2 - tw);
			Glass.rounded(context, tx, y1 + 2, tx + tw, y1 + 28, 5, 0xF02A2930);
			context.drawString(font, value, tx + 7, y1 + 6, TEXT, false);
			context.drawString(font, time, tx + 7, y1 + 17, MUTED, false);
		}
	}

	private int drawActions(GuiGraphics context, int left, int top, float mx, float my) {
		String[] actions = {"Send", "Receive", "Swap", "Buy"};
		int x = left;
		for (String action : actions) {
			int bw = font.width(action) + 28;
			boolean hot = mx >= x && mx < x + bw && my >= top && my < top + 20;
			boolean primary = action.equals("Receive");
			Glass.rounded(context, x, top, x + bw, top + 20, 10, primary ? (hot ? 0xFFCEC6FF : PURPLE) : hot ? 0xFF2E2D34 : 0xFF26252B);
			context.drawString(font, action, x + 14, top + 6, primary ? 0xFF131214 : TEXT, false);
			hits.add(new Hit(x, top, bw, 20, () -> nav(action.equals("Buy") ? "Buy / Sell" : action)));
			x += bw + 8;
		}
		return top + 20;
	}

	private void drawAssets(GuiGraphics context, int left, int top, int right, int bottom, float mx, float my) {
		List<Coin> coins = wallet.held();
		if (bottom - top < 50) return;
		Glass.rounded(context, left, top, right, bottom, 8, CARD);
		context.drawString(font, "Asset allocation (" + coins.size() + ")", left + 14, top + 12, TEXT, false);
		int nameX = left + 34, priceX = left + (right - left) * 34 / 100, amountX = left + (right - left) * 52 / 100;
		int valueX = right - 14;
		int widestAmount = 0;
		for (Coin coin : coins) widestAmount = Math.max(widestAmount, font.width(coin.amount(wallet.amount(coin)) + " " + coin.name()));
		int barX = amountX + widestAmount + 12;
		context.drawString(font, "Asset", left + 14, top + 30, FAINT, false);
		context.drawString(font, "Price", priceX, top + 30, FAINT, false);
		context.drawString(font, "Amount", amountX, top + 30, FAINT, false);
		rightText(context, "Value", valueX, top + 30, FAINT);
		double total = Math.max(1e-9, wallet.total());
		int y = top + 44;
		for (Coin coin : coins) {
			if (y + 20 > bottom - 6) break;
			boolean hot = mx >= left + 6 && mx < right - 6 && my >= y - 3 && my < y + 19;
			if (hot) Glass.rounded(context, left + 6, y - 3, right - 6, y + 19, 5, CARD_HOVER);
			context.blit(RenderPipelines.GUI_TEXTURED, coin.icon, left + 14, y, 0, 0, 14, 14, 64, 64, 64, 64);
			context.drawString(font, coin.fullName, nameX, y, TEXT, false);
			context.drawString(font, coin.name(), nameX, y + 9, FAINT, false);
			double change = wallet.change(coin) * 100;
			context.drawString(font, LedgerModule.usd(wallet.price(coin)), priceX, y, TEXT, false);
			context.drawString(font, String.format(Locale.US, "%s%.2f%%", change >= 0 ? "+" : "", change), priceX, y + 9, change >= 0 ? GREEN : RED, false);
			context.drawString(font, hideBalance ? "***" : coin.amount(wallet.amount(coin)) + " " + coin.name(), amountX, y + 4, MUTED, false);
			double share = wallet.value(coin) / total;
			int barW = valueX - 12 - font.width(LedgerModule.usd(wallet.value(coin))) - barX;
			if (barW >= 24) {
				Glass.rounded(context, barX, y + 6, barX + barW, y + 9, 2, 0xFF2D2C33);
				Glass.rounded(context, barX, y + 6, barX + Math.max(3, (int) (barW * share)), y + 9, 2, PURPLE);
			}
			rightText(context, hideBalance ? "******" : LedgerModule.usd(wallet.value(coin)), valueX, y, TEXT);
			rightText(context, String.format(Locale.US, "%.1f%%", share * 100), valueX, y + 9, FAINT);
			Coin clicked = coin;
			hits.add(new Hit(left + 6, y - 3, right - left - 12, 22, () -> openReceive(clicked)));
			y += 24;
		}
		if (coins.isEmpty()) context.drawString(font, "No assets yet. Receive some crypto to get started.", left + 14, top + 46, MUTED, false);
	}

	private void drawOperations(GuiGraphics context, int left, int top, int right, int bottom) {
		Glass.rounded(context, left, top, right, bottom, 8, CARD);
		context.drawString(font, "Latest operations", left + 12, top + 12, TEXT, false);
		List<Operation> ops = wallet.history();
		if (ops.isEmpty()) {
			context.drawString(font, "No operations yet", left + 12, top + 32, MUTED, false);
			return;
		}
		int y = top + 32;
		long now = System.currentTimeMillis();
		for (Operation op : ops) {
			if (y + 24 > bottom - 6) break;
			Glass.rounded(context, left + 10, y, left + 26, y + 16, 8, op.received() ? 0xFF1F3320 : 0xFF3A1D24);
			int ax = left + 17;
			int arrow = op.received() ? GREEN : RED;
			context.fill(ax, y + 4, ax + 2, y + 12, arrow);
			if (op.received()) context.fill(ax - 2, y + 9, ax + 4, y + 10, arrow);
			else context.fill(ax - 2, y + 5, ax + 4, y + 6, arrow);
			context.drawString(font, op.received() ? "Received" : "Sent", left + 32, y, TEXT, false);
			String amount = (op.received() ? "+" : "-") + op.coin().amount(op.amount()) + " " + op.coin().name();
			int room = right - 10 - Math.max(font.width(hideBalance ? "***" : amount), font.width(LedgerModule.usd(op.usd()))) - 8 - (left + 32);
			String when = ago(now - op.time()) + "  ·  " + Coin.shorten(op.address());
			context.drawString(font, font.plainSubstrByWidth(when, Math.max(0, room)), left + 32, y + 9, FAINT, false);
			rightText(context, hideBalance ? "***" : amount, right - 10, y, op.received() ? GREEN : TEXT);
			rightText(context, hideBalance ? "***" : LedgerModule.usd(op.usd()), right - 10, y + 9, FAINT);
			y += 26;
		}
	}

	private static String ago(long millis) {
		long s = millis / 1000;
		if (s < 60) return "Just now";
		if (s < 3600) return s / 60 + " min ago";
		if (s < 86_400) return s / 3600 + " h ago";
		return new SimpleDateFormat("MMM d", Locale.US).format(new Date(System.currentTimeMillis() - millis));
	}

	// ---------------------------------------------------------------- other pages

	private static final Map<Coin, Double> SUPPLY = new EnumMap<>(Map.of(Coin.BTC, 19.8e6, Coin.ETH, 120.4e6, Coin.SOL, 520e6, Coin.USDT, 140e9,
		Coin.XRP, 58e9, Coin.DOGE, 147e9, Coin.LTC, 75e6));
	private static final Map<Coin, Double> APY = new EnumMap<>(Map.of(Coin.ETH, 3.4, Coin.SOL, 7.1, Coin.USDT, 5.2, Coin.XRP, 2.1, Coin.DOGE, 1.4, Coin.LTC, 1.9, Coin.BTC, 1.1));

	private void drawPage(GuiGraphics context, int left, int top, int right, int bottom, float mx, float my) {
		Glass.rounded(context, left, top, right, bottom, 8, CARD);
		switch (page) {
			case "Market" -> {
				int[] cols = {left + 14, left + (right - left) * 36 / 100, left + (right - left) * 52 / 100, left + (right - left) * 66 / 100, right - 14};
				context.drawString(font, "Asset", cols[0], top + 12, FAINT, false);
				context.drawString(font, "Price", cols[1], top + 12, FAINT, false);
				context.drawString(font, "24h", cols[2], top + 12, FAINT, false);
				context.drawString(font, "Market cap", cols[3], top + 12, FAINT, false);
				rightText(context, "Volume", cols[4], top + 12, FAINT);
				int y = top + 28;
				for (Coin coin : Coin.values()) {
					if (y + 22 > bottom) break;
					boolean hot = mx >= left + 6 && mx < right - 6 && my >= y - 3 && my < y + 19;
					if (hot) Glass.rounded(context, left + 6, y - 3, right - 6, y + 19, 5, CARD_HOVER);
					context.blit(RenderPipelines.GUI_TEXTURED, coin.icon, cols[0], y, 0, 0, 14, 14, 64, 64, 64, 64);
					context.drawString(font, coin.fullName, cols[0] + 20, y, TEXT, false);
					context.drawString(font, coin.name(), cols[0] + 20, y + 9, FAINT, false);
					context.drawString(font, LedgerModule.usd(wallet.price(coin)), cols[1], y + 4, TEXT, false);
					double change = wallet.change(coin) * 100;
					context.drawString(font, String.format(Locale.US, "%s%.2f%%", change >= 0 ? "+" : "", change), cols[2], y + 4, change >= 0 ? GREEN : RED, false);
					double cap = wallet.price(coin) * SUPPLY.get(coin);
					context.drawString(font, compact(cap), cols[3], y + 4, TEXT, false);
					rightText(context, compact(cap * 0.031), cols[4], y + 4, MUTED);
					Coin clicked = coin;
					hits.add(new Hit(left + 6, y - 3, right - left - 12, 22, () -> openFlowFor(Flow.BUY, clicked)));
					y += 24;
				}
			}
			case "Accounts" -> {
				List<Coin> coins = wallet.held();
				int cw = (right - left - 14 * 2 - 12 * 2) / 3, x = left + 14, y = top + 14;
				for (int i = 0; i < coins.size(); i++) {
					Coin coin = coins.get(i);
					int cx = x + (i % 3) * (cw + 12), cy = y + (i / 3) * 84;
					if (cy + 72 > bottom) break;
					Glass.rounded(context, cx, cy, cx + cw, cy + 72, 8, 0xFF242329);
					context.blit(RenderPipelines.GUI_TEXTURED, coin.icon, cx + 12, cy + 12, 0, 0, 16, 16, 64, 64, 64, 64);
					context.drawString(font, coin.fullName + " 1", cx + 34, cy + 16, TEXT, false);
					context.drawString(font, coin.amount(wallet.amount(coin)) + " " + coin.name(), cx + 12, cy + 38, TEXT, false);
					context.drawString(font, hideBalance ? "******" : LedgerModule.usd(wallet.value(coin)), cx + 12, cy + 52, MUTED, false);
					Coin clicked = coin;
					hits.add(new Hit(cx, cy, cw, 72, () -> openFlowFor(Flow.SEND, clicked)));
				}
				if (coins.isEmpty()) context.drawString(font, "No accounts yet", left + 14, top + 16, MUTED, false);
			}
			case "Earn" -> {
				context.drawString(font, "Staking rewards", left + 14, top + 12, TEXT, false);
				double total = 0;
				int y = top + 32;
				for (Coin coin : wallet.held()) {
					if (y + 22 > bottom - 30) break;
					double apy = APY.getOrDefault(coin, 1.0), earned = wallet.value(coin) * apy / 100 * 0.42;
					total += earned;
					context.blit(RenderPipelines.GUI_TEXTURED, coin.icon, left + 14, y, 0, 0, 14, 14, 64, 64, 64, 64);
					context.drawString(font, coin.fullName + "  ·  staked", left + 34, y + 3, TEXT, false);
					context.drawString(font, String.format(Locale.US, "%.1f%% APY", apy), left + (right - left) / 2, y + 3, GREEN, false);
					rightText(context, "+" + LedgerModule.usd(earned), right - 14, y + 3, GREEN);
					y += 24;
				}
				context.drawString(font, "Earned so far: " + LedgerModule.usd(total), left + 14, bottom - 22, MUTED, false);
			}
			case "My Ledger" -> {
				context.drawString(font, "Ledger Nano X", left + 14, top + 14, TEXT, false);
				context.drawString(font, "Firmware 2.4.1  ·  Genuine check passed", left + 14, top + 26, GREEN, false);
				context.drawString(font, "Apps installed (" + Coin.values().length + ")", left + 14, top + 48, TEXT, false);
				int y = top + 64;
				for (Coin coin : Coin.values()) {
					if (y + 20 > bottom - 30) break;
					context.blit(RenderPipelines.GUI_TEXTURED, coin.icon, left + 14, y, 0, 0, 14, 14, 64, 64, 64, 64);
					context.drawString(font, coin.fullName, left + 34, y + 3, TEXT, false);
					context.drawString(font, "v2." + (coin.ordinal() + 3) + ".0", left + (right - left) / 2, y + 3, MUTED, false);
					rightText(context, "Installed", right - 14, y + 3, GREEN);
					y += 20;
				}
				int used = 640, bar = right - left - 28;
				Glass.rounded(context, left + 14, bottom - 22, left + 14 + bar, bottom - 16, 3, 0xFF2D2C33);
				Glass.rounded(context, left + 14, bottom - 22, left + 14 + bar * used / 1000, bottom - 16, 3, PURPLE);
				context.drawString(font, used + " KB of 1.5 MB used", left + 14, bottom - 34, MUTED, false);
			}
			default -> {
				// Discover: a grid of made-up apps
				String[][] apps = {{"Swap Hub", "Best rates across exchanges"}, {"Stake Pro", "Earn on ETH and SOL"}, {"Bridge", "Move coins between chains"},
					{"NFT Gallery", "Your collectibles"}, {"Lend", "Borrow against your crypto"}, {"Card", "Spend crypto anywhere"}};
				int cw = (right - left - 14 * 2 - 12 * 2) / 3;
				for (int i = 0; i < apps.length; i++) {
					int cx = left + 14 + (i % 3) * (cw + 12), cy = top + 14 + (i / 3) * 78;
					if (cy + 66 > bottom) break;
					Glass.rounded(context, cx, cy, cx + cw, cy + 66, 8, 0xFF242329);
					Glass.rounded(context, cx + 12, cy + 12, cx + 30, cy + 30, 6, PURPLE);
					context.drawString(font, apps[i][0], cx + 38, cy + 17, TEXT, false);
					context.drawString(font, font.plainSubstrByWidth(apps[i][1], cw - 24), cx + 12, cy + 42, MUTED, false);
					String name = apps[i][0];
					hits.add(new Hit(cx, cy, cw, 66, () -> say(name + " needs your Ledger connected")));
				}
			}
		}
	}

	private static String compact(double value) {
		if (value >= 1e12) return String.format(Locale.US, "$%.2fT", value / 1e12);
		if (value >= 1e9) return String.format(Locale.US, "$%.2fB", value / 1e9);
		if (value >= 1e6) return String.format(Locale.US, "$%.2fM", value / 1e6);
		return LedgerModule.usd(value);
	}

	// ---------------------------------------------------------------- send, swap, buy

	private void openFlow(Flow f) {
		openFlowFor(f, wallet.held().isEmpty() ? Coin.BTC : wallet.held().getFirst());
	}

	private void openFlowFor(Flow f, Coin coin) {
		flow = f;
		flowCoin = coin;
		flowTo = coin == Coin.ETH ? Coin.BTC : Coin.ETH;
		flowPreset = 1;
		flowStage = 0;
		flowAddress = coin.randomAddress(new Random());
		flowResult = null;
		payName = "";
		payAmount = "";
		payFocus = 0;
		// In the DonutSMP profile, Send pays a player; in the crypto one it sends coins as before
		flowPay = money;
	}

	// ---------------------------------------------------------------- paying a player

	/** The Send form: who to pay and how much, typed in, and which box has the cursor (0 name, 1 amount). */
	private String payName = "", payAmount = "";
	private int payFocus;
	private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("[A-Za-z0-9_.]{1,17}");

	/** The amount typed, reading 150, 1,500, 1.5k, 5m or 2b; -1 if it isn't one. */
	private double payValue() {
		String clean = payAmount.replace(",", "").replace("$", "").trim().toLowerCase(Locale.ROOT);
		if (clean.isEmpty()) return -1;
		double scale = switch (clean.charAt(clean.length() - 1)) {
			case 'k' -> 1e3;
			case 'm' -> 1e6;
			case 'b' -> 1e9;
			case 't' -> 1e12;
			default -> 1;
		};
		if (scale != 1) clean = clean.substring(0, clean.length() - 1);
		try {
			double value = Double.parseDouble(clean) * scale;
			return Double.isFinite(value) && value > 0 ? value : -1;
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	private boolean canPay() {
		return NAME.matcher(payName).matches() && payValue() > 0;
	}

	/** Online players whose names start with what's typed, for picking instead of typing it all. */
	private List<String> payMatches() {
		List<String> names = new ArrayList<>();
		var connection = minecraft.getConnection();
		if (connection == null || payName.isEmpty()) return names;
		String typed = payName.toLowerCase(Locale.ROOT);
		String self = minecraft.player == null ? "" : minecraft.player.getGameProfile().name();
		for (var info : connection.getOnlinePlayers()) {
			String name = info.getProfile().name();
			if (name.equalsIgnoreCase(self) || name.equalsIgnoreCase(payName)) continue;
			if (name.toLowerCase(Locale.ROOT).startsWith(typed)) names.add(name);
		}
		names.sort(String.CASE_INSENSITIVE_ORDER);
		return names.size() > 4 ? names.subList(0, 4) : names;
	}

	/** One text box: its label, what's typed, a blinking cursor when it has focus, and a click to focus it. */
	private void field(GuiGraphics context, String label, String text, String hint, int x, int y, int fw, boolean focused, float mx, float my, int focus) {
		context.drawString(font, label, x, y, MUTED, false);
		int by = y + 12;
		boolean hot = mx >= x && mx < x + fw && my >= by && my < by + 22;
		Glass.rounded(context, x - 1, by - 1, x + fw + 1, by + 23, 8, focused ? PURPLE : hot ? 0xFF4A4952 : 0xFF33323A);
		Glass.rounded(context, x, by, x + fw, by + 22, 7, 0xFF1A191E);
		if (text.isEmpty() && !focused) context.drawString(font, hint, x + 8, by + 7, FAINT, false);
		else context.drawString(font, text, x + 8, by + 7, TEXT, false);
		if (focused && (System.currentTimeMillis() / 500) % 2 == 0) {
			int cx = x + 8 + font.width(text);
			context.fill(cx, by + 6, cx + 1, by + 16, TEXT);
		}
		hits.add(new Hit(x, by, fw, 22, () -> payFocus = focus));
	}

	/** The Send window's form: a player and an amount, then Continue to confirm it on the device. */
	private void drawPayForm(GuiGraphics context, int x, int y, int cw, int ch, float mx, float my) {
		int fw = cw - 32;
		field(context, "Pay to", payName, "Player name", x + 16, y + 36, fw, payFocus == 0, mx, my, 0);
		int row = y + 74;
		List<String> matches = payFocus == 0 ? payMatches() : List.of();
		if (!matches.isEmpty()) {
			// Online players matching what's typed, to click instead of finishing it
			int px = x + 16;
			for (String name : matches) {
				int pw = font.width(name) + 14;
				if (px + pw > x + cw - 16) break;
				boolean hot = mx >= px && mx < px + pw && my >= row && my < row + 16;
				Glass.rounded(context, px, row, px + pw, row + 16, 8, hot ? CARD_HOVER : 0xFF242329);
				context.drawString(font, name, px + 7, row + 4, TEXT, false);
				hits.add(new Hit(px, row, pw, 16, () -> {
					payName = name;
					payFocus = 1;
				}));
				px += pw + 5;
			}
		}
		row += 22;
		field(context, "Amount", payAmount, "Like 5000, 25k or 1.5m", x + 16, row, fw, payFocus == 1, mx, my, 1);
		double value = payValue();
		String summary = value > 0 ? LedgerModule.usd(value) : payAmount.isEmpty() ? "" : "Not an amount";
		context.drawString(font, summary, x + 16, row + 42, value > 0 ? TEXT : RED, false);
		if (canPay()) context.drawString(font, "Runs /pay " + payName + " " + LedgerModule.plainAmount(value), x + 16, row + 56, MUTED, false);
		button(context, "Continue", x + cw - 96, y + ch - 30, 80, mx, my, canPay() ? this::confirmPay : null);
		button(context, "Cancel", x + 16, y + ch - 30, 70, mx, my, () -> flow = null);
	}

	private void confirmPay() {
		if (!canPay()) return;
		flowStage = 1;
		LarpSounds.play(LarpSounds.LEDGER_BEEP, 0.5f);
		flowAt = System.currentTimeMillis();
	}

	@Override
	public boolean charTyped(net.minecraft.client.input.CharacterEvent input) {
		if (flow == Flow.SEND && flowPay && flowStage == 0 && input.isAllowedChatCharacter()) {
			String typed = input.codepointAsString();
			if (payFocus == 0 && typed.matches("[A-Za-z0-9_.]") && payName.length() < 17) payName += typed;
			else if (payFocus == 1 && typed.matches("[0-9.,kKmMbBtT$]") && payAmount.length() < 20) payAmount += typed;
			return true;
		}
		return super.charTyped(input);
	}

	/** Typing keys for the Send form: backspace, tab between the boxes, enter to continue, paste. Returns whether it used the key. */
	private boolean payKey(KeyEvent input) {
		if (flow != Flow.SEND || !flowPay || flowStage != 0) return false;
		int key = input.key();
		boolean ctrl = input.hasControlDown();
		if (key == GLFW.GLFW_KEY_BACKSPACE) {
			if (payFocus == 0) payName = ctrl || payName.isEmpty() ? "" : payName.substring(0, payName.length() - 1);
			else payAmount = ctrl || payAmount.isEmpty() ? "" : payAmount.substring(0, payAmount.length() - 1);
			return true;
		}
		if (key == GLFW.GLFW_KEY_TAB || key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) {
			// Tab with a name half typed takes the first online match, like chat
			if (payFocus == 0 && key == GLFW.GLFW_KEY_TAB && !payMatches().isEmpty()) payName = payMatches().getFirst();
			payFocus = 1 - payFocus;
			return true;
		}
		if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
			if (payFocus == 0) payFocus = 1;
			else confirmPay();
			return true;
		}
		if (ctrl && key == GLFW.GLFW_KEY_V) {
			String pasted = minecraft.keyboardHandler.getClipboard().trim();
			if (payFocus == 0) payName = (payName + pasted.replaceAll("[^A-Za-z0-9_.]", ""));
			else payAmount = (payAmount + pasted.replaceAll("[^0-9.,kKmMbBtT$]", ""));
			if (payName.length() > 17) payName = payName.substring(0, 17);
			if (payAmount.length() > 20) payAmount = payAmount.substring(0, 20);
			return true;
		}
		return false;
	}

	/** How much the picked preset is: a share of the coin for sends and swaps, dollars for buys. */
	private double flowAmount() {
		if (flow == Flow.BUY) return DOLLARS[flowPreset] / wallet.price(flowCoin);
		return wallet.amount(flowCoin) * FRACTIONS[flowPreset];
	}

	/** The send, swap or buy window: pick coins and an amount, confirm on the device, done. */
	private void drawFlow(GuiGraphics context, int w, int h, float mx, float my) {
		hits.removeIf(hit -> hit.w() == w && hit.h() == h && hit.x() == 0);
		Glass.rounded(context, 0, 0, w, h, 11, 0xB0000000);
		int cw = 300, ch = 250, x = (w - cw) / 2, y = (h - ch) / 2;
		Glass.rounded(context, x, y, x + cw, y + ch, 10, CARD);
		hits.add(new Hit(0, 0, w, h, () -> flow = null));
		hits.add(new Hit(x, y, cw, ch, () -> { }));
		String title = switch (flow) {
			case SEND -> flowPay ? "Pay a player" : "Send";
			case SWAP -> "Swap";
			case BUY -> "Buy crypto";
		};
		centered(context, title, x + cw / 2, y + 14, TEXT);

		if (flowStage == 1) {
			// Waiting on the device
			float t = (System.currentTimeMillis() - flowAt) / 1000f;
			int dx = x + cw / 2 - 50, dy = y + 70;
			Glass.rounded(context, dx, dy, dx + 100, dy + 32, 9, 0xFF0D0D0F);
			Glass.rounded(context, dx + 64, dy - 2, dx + 106, dy + 34, 10, 0xFFB9BBC2);
			context.fill(dx + 8, dy + 7, dx + 56, dy + 25, 0xFF000000);
			centered(context, t < 0.8f ? "Review" : "Approve", dx + 32, dy + 12, TEXT);
			centered(context, "Confirm on your Ledger", x + cw / 2, y + 130, TEXT);
			int dots = (int) (t * 4) % 4;
			centered(context, "Waiting for approval" + ".".repeat(dots), x + cw / 2, y + 146, MUTED);
			if (t > 1.8f) finishFlow();
			return;
		}
		if (flowStage == 2) {
			Glass.rounded(context, x + cw / 2 - 18, y + 60, x + cw / 2 + 18, y + 96, 18, 0xFF1F3320);
			context.fill(x + cw / 2 - 8, y + 78, x + cw / 2 - 4, y + 82, GREEN);
			context.fill(x + cw / 2 - 4, y + 82, x + cw / 2, y + 86, GREEN);
			context.fill(x + cw / 2, y + 74, x + cw / 2 + 4, y + 82, GREEN);
			context.fill(x + cw / 2 + 4, y + 70, x + cw / 2 + 8, y + 76, GREEN);
			centered(context, switch (flow) {
				case SEND -> flowPay ? "Payment sent" : "Transaction sent";
				case SWAP -> "Swap complete";
				case BUY -> "Purchase complete";
			}, x + cw / 2, y + 108, TEXT);
			centered(context, flowResult, x + cw / 2, y + 122, MUTED);
			button(context, "Done", x + cw / 2 - 40, y + ch - 30, 80, mx, my, () -> flow = null);
			return;
		}

		if (flow == Flow.SEND && flowPay) {
			drawPayForm(context, x, y, cw, ch, mx, my);
			return;
		}
		int row = y + 34;
		context.drawString(font, flow == Flow.SWAP ? "From" : flow == Flow.BUY ? "Coin" : "Account", x + 16, row, MUTED, false);
		row = coinPicker(context, flow == Flow.BUY ? List.of(Coin.values()) : wallet.held(), flowCoin, x + 16, row + 12, mx, my, c -> {
			flowCoin = c;
			if (flowTo == c) flowTo = c == Coin.ETH ? Coin.BTC : Coin.ETH;
		});
		if (flow == Flow.SWAP) {
			context.drawString(font, "To", x + 16, row + 4, MUTED, false);
			List<Coin> targets = new ArrayList<>(List.of(Coin.values()));
			targets.remove(flowCoin);
			row = coinPicker(context, targets, flowTo, x + 16, row + 16, mx, my, c -> flowTo = c);
		}
		context.drawString(font, "Amount", x + 16, row + 4, MUTED, false);
		int px = x + 16;
		for (int i = 0; i < 4; i++) {
			String label = flow == Flow.BUY ? compact(DOLLARS[i]).replace(".00", "") : i == 3 ? "Max" : (int) (FRACTIONS[i] * 100) + "%";
			int pw = font.width(label) + 16;
			boolean picked = i == flowPreset, hot = mx >= px && mx < px + pw && my >= row + 16 && my < row + 32;
			Glass.rounded(context, px, row + 16, px + pw, row + 32, 8, picked ? 0xFF2F2C45 : hot ? CARD_HOVER : 0xFF242329);
			context.drawString(font, label, px + 8, row + 20, picked ? PURPLE : TEXT, false);
			int preset = i;
			hits.add(new Hit(px, row + 16, pw, 16, () -> flowPreset = preset));
			px += pw + 6;
		}
		double amount = flowAmount();
		String summary = flowCoin.amount(amount) + " " + flowCoin.name() + "  ≈ " + LedgerModule.usd(amount * wallet.price(flowCoin));
		context.drawString(font, summary, x + 16, row + 42, TEXT, false);
		String detail = switch (flow) {
			case SEND -> "To " + Coin.shorten(flowAddress);
			case SWAP -> "You get ≈ " + flowTo.amount(amount * wallet.price(flowCoin) / wallet.price(flowTo) * 0.997) + " " + flowTo.name();
			case BUY -> "Paid with card ****4821";
		};
		context.drawString(font, detail, x + 16, row + 56, MUTED, false);
		boolean can = amount > 0;
		button(context, "Continue", x + cw - 96, y + ch - 30, 80, mx, my, can ? () -> {
			flowStage = 1;
			LarpSounds.play(LarpSounds.LEDGER_BEEP, 0.5f);
			flowAt = System.currentTimeMillis();
		} : null);
		button(context, "Cancel", x + 16, y + ch - 30, 70, mx, my, () -> flow = null);
	}

	/** A row of coin icons to pick from. Returns the y below it. */
	private int coinPicker(GuiGraphics context, List<Coin> coins, Coin picked, int x, int y, float mx, float my, java.util.function.Consumer<Coin> pick) {
		for (int i = 0; i < coins.size(); i++) {
			Coin coin = coins.get(i);
			int cx = x + i * 30;
			boolean on = coin == picked, hot = mx >= cx && mx < cx + 24 && my >= y && my < y + 24;
			Glass.rounded(context, cx, y, cx + 24, y + 24, 12, on ? PURPLE : hot ? 0xFF3A3940 : 0xFF242329);
			context.blit(RenderPipelines.GUI_TEXTURED, coin.icon, cx + 3, y + 3, 0, 0, 18, 18, 64, 64, 64, 64);
			hits.add(new Hit(cx, y, 24, 24, () -> pick.accept(coin)));
		}
		if (coins.isEmpty()) context.drawString(font, "Nothing to send yet", x, y + 8, FAINT, false);
		if (picked != null) context.drawString(font, picked.fullName, x + coins.size() * 30 + 4, y + 8, MUTED, false);
		return y + 28;
	}

	private void button(GuiGraphics context, String label, int x, int y, int bw, float mx, float my, Runnable action) {
		boolean hot = action != null && mx >= x && mx < x + bw && my >= y && my < y + 20;
		Glass.rounded(context, x, y, x + bw, y + 20, 10, action == null ? 0xFF2D2C33 : hot ? 0xFFCEC6FF : PURPLE);
		centered(context, label, x + bw / 2, y + 6, action == null ? FAINT : 0xFF131214);
		if (action != null) hits.add(new Hit(x, y, bw, 20, action));
	}

	private void finishFlow() {
		flowStage = 2;
		LarpSounds.play(LarpSounds.LEDGER_SUCCESS, 0.8f);
		double amount = flowAmount();
		switch (flow) {
			case SEND -> {
				if (flowPay) {
					// A real payment: the server's /pay, with its answer in chat (which the operations list picks up)
					String paid = LedgerModule.plainAmount(payValue());
					if (minecraft.player != null) minecraft.player.connection.sendCommand("pay " + payName + " " + paid);
					flowResult = LedgerModule.usd(payValue()) + " paid to " + payName;
				} else {
					wallet.send(flowCoin, amount, flowAddress);
					flowResult = flowCoin.amount(amount) + " " + flowCoin.name() + " sent to " + Coin.shorten(flowAddress);
				}
			}
			case SWAP -> {
				double got = wallet.swap(flowCoin, flowTo, amount);
				flowResult = "Got " + flowTo.amount(got) + " " + flowTo.name();
			}
			case BUY -> {
				double got = wallet.buy(flowCoin, DOLLARS[flowPreset]);
				flowResult = "Bought " + flowCoin.amount(got) + " " + flowCoin.name();
			}
		}
	}

	// ---------------------------------------------------------------- receive

	private void openReceive(Coin coin) {
		receiving = coin;
		receiveAddress = coin.randomAddress(new Random());
	}

	/** A receive window with a made-up address and a QR-looking square for it. */
	private void drawReceive(GuiGraphics context, int w, int h, float mx, float my) {
		hits.removeIf(hit -> hit.w() == w && hit.h() == h && hit.x() == 0);
		Glass.rounded(context, 0, 0, w, h, 11, 0xB0000000);
		int cw = 260, ch = 250, x = (w - cw) / 2, y = (h - ch) / 2;
		Glass.rounded(context, x, y, x + cw, y + ch, 10, CARD);
		hits.add(new Hit(0, 0, w, h, () -> receiving = null));
		hits.add(new Hit(x, y, cw, ch, () -> { }));
		centered(context, "Receive " + receiving.fullName, x + cw / 2, y + 14, TEXT);
		context.blit(RenderPipelines.GUI_TEXTURED, receiving.icon, x + cw / 2 - 8, y + 28, 0, 0, 16, 16, 64, 64, 64, 64);

		int modules = 25, cell = 5, qx = x + (cw - modules * cell) / 2, qy = y + 52;
		context.fill(qx - 6, qy - 6, qx + modules * cell + 6, qy + modules * cell + 6, 0xFFFFFFFF);
		Random bits = new Random(receiveAddress.hashCode());
		for (int r = 0; r < modules; r++) {
			for (int c = 0; c < modules; c++) {
				boolean on = finder(r, c, modules) != 0 ? finder(r, c, modules) == 1 : bits.nextBoolean();
				if (on) context.fill(qx + c * cell, qy + r * cell, qx + (c + 1) * cell, qy + (r + 1) * cell, 0xFF000000);
			}
		}
		int ay = qy + modules * cell + 14;
		String first = receiveAddress.substring(0, receiveAddress.length() / 2), second = receiveAddress.substring(receiveAddress.length() / 2);
		centered(context, first, x + cw / 2, ay, TEXT);
		centered(context, second, x + cw / 2, ay + 10, TEXT);
		centered(context, "Verify the address on your device", x + cw / 2, ay + 26, MUTED);
		int bw = 70, bx = x + (cw - bw) / 2, by = y + ch - 26;
		boolean hot = mx >= bx && mx < bx + bw && my >= by && my < by + 18;
		Glass.rounded(context, bx, by, bx + bw, by + 18, 9, hot ? 0xFFCEC6FF : PURPLE);
		centered(context, "Done", bx + bw / 2, by + 5, 0xFF131214);
		hits.add(new Hit(bx, by, bw, 18, () -> receiving = null));
	}

	/** 1 or -1 inside the three corner squares of a QR code (dark or light), 0 elsewhere. */
	private static int finder(int r, int c, int size) {
		int[][] corners = {{0, 0}, {0, size - 7}, {size - 7, 0}};
		for (int[] corner : corners) {
			int dr = r - corner[0], dc = c - corner[1];
			if (dr < -1 || dc < -1 || dr > 7 || dc > 7) continue;
			if (dr == -1 || dc == -1 || dr == 7 || dc == 7) return -1;
			int ring = Math.min(Math.min(dr, dc), Math.min(6 - dr, 6 - dc));
			return ring == 1 ? -1 : 1;
		}
		return 0;
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

	private void centered(GuiGraphics context, String text, int x, int y, int color) {
		context.drawString(font, text, x - font.width(text) / 2, y, color, false);
	}

	private void rightText(GuiGraphics context, String text, int right, int y, int color) {
		context.drawString(font, text, right - font.width(text), y, color, false);
	}

	private static int withAlpha(int color, float alpha) {
		return (color & 0x00FFFFFF) | (Math.round(alpha * 255) << 24);
	}

	private static float ease(float t) {
		t = Mth.clamp(t, 0, 1);
		return 1 - (1 - t) * (1 - t) * (1 - t);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
		if (click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			double mx = click.x() / scale(), my = click.y() / scale();
			if (!unlocking()) {
				mx -= windowX;
				my -= windowY;
			}
			// Last added is on top: the receive window's buttons come after its backdrop
			for (int i = hits.size() - 1; i >= 0; i--) {
				if (hits.get(i).contains(mx, my)) {
					if (!unlocking()) LarpSounds.play(LarpSounds.PHONE_TAP, 0.85f, 0.45f);
					hits.get(i).action().run();
					return true;
				}
			}
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean keyPressed(KeyEvent input) {
		if (payKey(input)) return true;
		if (input.key() == GLFW.GLFW_KEY_ESCAPE && (receiving != null || flow != null)) {
			receiving = null;
			if (flow != null && flowStage != 1) flow = null;
			return true;
		}
		return super.keyPressed(input);
	}
}
