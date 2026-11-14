package dev.femboyware.gui;

import dev.femboyware.module.render.EmotesModule;
import dev.femboyware.module.render.EmotesModule.Emote;
import dev.femboyware.util.ColorUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * A radial emote picker. Hold the wheel key, point the mouse at an emote and let go, or click one.
 * The middle stops whatever emote is playing.
 */
public class EmoteWheelScreen extends Screen {
	private static final int SLOTS = 8;
	private static final int RADIUS = 72, SLOT_SIZE = 44, CENTER_SIZE = 52, DEAD_ZONE = 24;
	/** Pointing at nothing, or at the middle. */
	private static final int NONE = -1, CENTER = -2;

	private static final net.minecraft.resources.Identifier WATCH = net.minecraft.resources.Identifier.fromNamespaceAndPath("femboyware", "textures/larp/watch_iced.png");

	private static final net.minecraft.resources.Identifier ITEM_PHONE = net.minecraft.resources.Identifier.fromNamespaceAndPath("femboyware", "textures/item/phone.png");
	private static final net.minecraft.resources.Identifier ITEM_LEDGER = net.minecraft.resources.Identifier.fromNamespaceAndPath("femboyware", "textures/item/ledger.png");

	private final EmotesModule module;
	private int hovered = NONE;
	private long opened;

	public EmoteWheelScreen(EmotesModule module) {
		super(Component.literal("Emotes"));
		this.module = module;
	}

	@Override
	protected void init() {
		opened = System.currentTimeMillis();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void renderBackground(GuiGraphics context, int mouseX, int mouseY, float deltaTicks) {
		context.fill(0, 0, width, height, 0x50000000);
	}

	@Override
	public void render(GuiGraphics context, int mouseX, int mouseY, float deltaTicks) {
		float scale = UiScale.scale();
		context.pose().pushMatrix();
		context.pose().scale(scale, scale);
		UiScale.begin();
		renderScaled(context, (int) (width / scale), (int) (height / scale), mouseX / scale, mouseY / scale);
		UiScale.end();
		context.pose().popMatrix();
	}

	/** Which page of eight is showing; scroll or click the arrows to turn it. */
	private int page;

	private static int emotePages() {
		return (Emote.values().length + SLOTS - 1) / SLOTS;
	}

	/** How many emotes are on the last emote page. */
	private static int leftover() {
		return Emote.values().length - (emotePages() - 1) * SLOTS;
	}

	/** Whether the rides fit in the last emote page's empty slots, after its emotes. */
	private static boolean ridesShareLastPage() {
		return leftover() + Ride.values().length <= SLOTS;
	}

	/** The emote pages, the rides in the last one's spare slots (or on one more page if they don't fit). */
	private static int pages() {
		return emotePages() + (ridesShareLastPage() ? 0 : 1);
	}

	/** Whether this page has the rides on it. */
	private boolean ridesPage() {
		return page == pages() - 1;
	}

	/** Whether this page is only rides. */
	private boolean ridesOnlyPage() {
		return ridesPage() && !ridesShareLastPage();
	}

	private Emote emoteIn(int slot) {
		if (ridesOnlyPage()) return null;
		Emote[] emotes = Emote.values();
		int index = page * SLOTS + slot;
		return slot >= 0 && slot < SLOTS && index < emotes.length ? emotes[index] : null;
	}

	/** What the rides page can bring round. */
	private enum Ride {
		MAYBACH("Maybach"), JESKO("Jesko"), JET("Private Jet"), CLEAR("Send Cars Away"), FBI_RAID("FBI Raid");

		final String label;

		Ride(String label) {
			this.label = label;
		}
	}

	private Ride rideIn(int slot) {
		Ride[] rides = Ride.values();
		int k = slot - (ridesShareLastPage() ? leftover() : 0);
		return ridesPage() && slot >= 0 && slot < SLOTS && k >= 0 && k < rides.length ? rides[k] : null;
	}

	private static dev.femboyware.module.larp.JetModule jet() {
		return dev.femboyware.FemboyWare.getModules().get(dev.femboyware.module.larp.JetModule.class);
	}

	private static String label(Ride ride) {
		return ride == Ride.JET && jet().parked() ? "Board Jet" : ride.label;
	}

	private static void summon(Ride ride) {
		var cars = dev.femboyware.FemboyWare.getModules().get(dev.femboyware.module.larp.CarsModule.class);
		switch (ride) {
			case MAYBACH -> cars.command(new String[]{"car", "spawn", "suv"});
			case JESKO -> cars.command(new String[]{"car", "spawn", "jesko"});
			case JET -> jet().command(new String[]{"jet", jet().parked() ? "fly" : "spawn"});
			case CLEAR -> cars.command(new String[]{"car", "clear"});
			case FBI_RAID -> dev.femboyware.FemboyWare.getModules().get(dev.femboyware.module.larp.FbiRaidModule.class).command(new String[]{"fbi", "raid"});
		}
	}

	/** Page arrows and dots under the wheel, and where they were last drawn, for clicks. */
	private int arrowY, leftArrowX, rightArrowX;

	private void renderScaled(GuiGraphics context, int canvasWidth, int canvasHeight, float mouseX, float mouseY) {
		Font font = this.font;
		int cx = canvasWidth / 2, cy = canvasHeight / 2;
		float grow = Math.min(1, (System.currentTimeMillis() - opened) / 120f);
		int radius = Math.round(RADIUS * (0.7f + 0.3f * grow));
		hovered = pick(mouseX - cx, mouseY - cy);

		// The ring the slots sit on
		Glass.ellipse(context, cx - radius - SLOT_SIZE / 2 - 6, cy - radius - SLOT_SIZE / 2 - 6, cx + radius + SLOT_SIZE / 2 + 6, cy + radius + SLOT_SIZE / 2 + 6, 0x40000000);

		for (int slot = 0; slot < SLOTS; slot++) {
			double angle = slotAngle(slot);
			int x = cx + (int) Math.round(Math.sin(angle) * radius), y = cy - (int) Math.round(Math.cos(angle) * radius);
			Emote emote = emoteIn(slot);
			Ride ride = rideIn(slot);
			boolean hot = slot == hovered && (emote != null || ride != null);
			boolean on = emote != null && emote == module.playing();
			int size = hot ? SLOT_SIZE + 6 : SLOT_SIZE;
			int tint = hot ? ColorUtil.withAlpha(Theme.accent(), 0xA0) : on ? ColorUtil.withAlpha(Theme.accent(), 0x60) : Theme.panel();
			Glass.draw(context, x - size / 2, y - size / 2, x + size / 2, y + size / 2, size / 2, tint, true);
			if (ride != null) {
				rideIcon(context, ride, x, y);
			} else if (emote == null) {
				if (ridesPage()) continue;
				String soon = "soon";
				context.drawString(font, soon, x - font.width(soon) / 2, y - 4, Theme.muted());
			} else {
				icon(context, emote, x, y);
			}
		}

		boolean forFakes = shiftHeld() && module.hasFakes();
		boolean stopHot = hovered == CENTER && (forFakes ? module.fakesEmoting() : module.playing() != null);
		int center = stopHot ? CENTER_SIZE + 4 : CENTER_SIZE;
		Glass.draw(context, cx - center / 2, cy - center / 2, cx + center / 2, cy + center / 2, center / 2,
			stopHot ? ColorUtil.withAlpha(Theme.accent(), 0xA0) : Theme.panel(), true);
		String title;
		if (hovered == CENTER && forFakes) title = module.fakesEmoting() ? "Stop fakes" : "Fake players";
		else if (hovered == CENTER) title = module.playing() != null ? "Stop" : ridesOnlyPage() ? "Rides" : ridesPage() ? "Emotes & Rides" : "Emotes";
		else if (emoteIn(hovered) != null) title = emoteIn(hovered).label;
		else if (rideIn(hovered) != null) title = label(rideIn(hovered));
		else title = ridesOnlyPage() ? "Rides" : ridesPage() ? "Emotes & Rides" : "Emotes";
		drawCentered(context, font, title, cx, cy - 4, Theme.text());

		{
			arrowY = cy + radius + SLOT_SIZE / 2 + 14;
			leftArrowX = cx - 40;
			rightArrowX = cx + 40;
			drawCentered(context, font, "<", leftArrowX, arrowY, Theme.text());
			drawCentered(context, font, ">", rightArrowX, arrowY, Theme.text());
			for (int i = 0; i < pages(); i++) {
				int dx = cx - (pages() - 1) * 5 + i * 10;
				Glass.rounded(context, dx - 2, arrowY + 2, dx + 2, arrowY + 6, 2, i == page ? Theme.accent() | 0xFF000000 : 0x80FFFFFF);
			}
		}
		String help = ridesOnlyPage() ? "Pull up in something" : forFakes ? "Your fake players do it, not you"
			: ridesPage() ? "Emote, or pull up in something" : "Scroll for more emotes and rides";
		drawCentered(context, font, help, cx, cy + radius + SLOT_SIZE / 2 + 28, forFakes ? Theme.text() : Theme.muted());
		if (!ridesOnlyPage() && module.hasFakes() && !forFakes) {
			drawCentered(context, font, "Hold Shift to make your fake players emote", cx, cy + radius + SLOT_SIZE / 2 + 40, Theme.muted());
		}
	}

	private boolean shiftHeld() {
		var window = net.minecraft.client.Minecraft.getInstance().getWindow();
		return dev.femboyware.util.KeyUtil.isKeyDown(GLFW.GLFW_KEY_LEFT_SHIFT)
			|| dev.femboyware.util.KeyUtil.isKeyDown(GLFW.GLFW_KEY_RIGHT_SHIFT);
	}

	private static void drawCentered(GuiGraphics context, Font font, String text, int x, int y, int color) {
		context.drawString(font, text, x - font.width(text) / 2, y, color);
	}

	/** A little picture of the emote in its slot. */
	private static void icon(GuiGraphics context, Emote emote, int x, int y) {
		switch (emote) {
			case BAND_SPREAD -> {
				// Three bills fanned out from a point below the middle
				for (int i = -1; i <= 1; i++) {
					context.pose().pushMatrix();
					context.pose().translate(x, y + 9);
					context.pose().rotate(i * 0.45f - (float) Math.PI / 2);
					context.blit(RenderPipelines.GUI_TEXTURED, EmotesModule.BILL, 0, -5, 0, 0, 24, 11, 64, 28, 64, 28);
					context.pose().popMatrix();
				}
			}
			case MONEY_RAIN -> {
				// Bills scattered at angles, as if falling
				float[][] bills = {{-9, -9, 0.5f}, {5, -6, -0.4f}, {-4, 4, -0.15f}, {8, 7, 0.7f}};
				for (float[] bill : bills) {
					context.pose().pushMatrix();
					context.pose().translate(x + bill[0], y + bill[1]);
					context.pose().rotate(bill[2]);
					context.blit(RenderPipelines.GUI_TEXTURED, EmotesModule.BILL, -8, -4, 0, 0, 16, 7, 64, 28, 64, 28);
					context.pose().popMatrix();
				}
			}
			case FLEX -> {
				// A brick of cash, edge on, with its band
				for (int layer = 0; layer < 5; layer++) {
					context.fill(x - 12, y - 1 + layer * 2, x + 12, y + layer * 2, layer % 2 == 0 ? 0xFF5E9C58 : 0xFFBAD6AA);
				}
				context.fill(x - 3, y - 1, x + 3, y + 9, 0xFFC83C3C);
				context.blit(RenderPipelines.GUI_TEXTURED, EmotesModule.BILL, x - 12, y - 12, 0, 0, 24, 11, 64, 28, 64, 28);
			}
			case WATCH_CHECK, WATCH_SHINE -> {
				context.fill(x - 4, y - 14, x + 4, y + 14, 0xFFD8DCE4);
				context.blit(RenderPipelines.GUI_TEXTURED, WATCH, x - 9, y - 9, 0, 0, 18, 18, 32, 32, 32, 32);
				if (emote == Emote.WATCH_SHINE) {
					context.fill(x + 9, y - 13, x + 10, y - 5, 0xFFFFFFFF);
					context.fill(x + 6, y - 10, x + 13, y - 9, 0xFFFFFFFF);
				}
			}
			case SCOTCH_POUR -> {
				context.blit(RenderPipelines.GUI_TEXTURED, WATCH, x - 12, y - 1, 0, 0, 14, 14, 32, 32, 32, 32);
				context.pose().pushMatrix();
				context.pose().translate(x + 6, y - 6);
				context.pose().rotate(0.9f);
				context.fill(-3, -9, 3, 7, 0xFF1C3A22);
				context.fill(-1, -16, 1, -9, 0xFF1C3A22);
				context.fill(-1, -17, 1, -14, 0xFF801022);
				context.fill(-3, -3, 3, 2, 0xFFEDE2C4);
				context.pose().popMatrix();
				context.fill(x - 4, y - 4, x - 3, y + 1, 0xFF9A1430);
			}
			case PHONE_FLEX -> context.blit(RenderPipelines.GUI_TEXTURED, ITEM_PHONE, x - 12, y - 12, 0, 0, 24, 24, 32, 32, 32, 32);
			case LEDGER_FLEX -> context.blit(RenderPipelines.GUI_TEXTURED, ITEM_LEDGER, x - 12, y - 12, 0, 0, 24, 24, 32, 32, 32, 32);
			case CHAMPAGNE -> {
				// A tilted bottle with a burst of spray from its neck
				context.pose().pushMatrix();
				context.pose().translate(x - 3, y + 4);
				context.pose().rotate(0.6f);
				context.fill(-4, -6, 4, 10, 0xFF1C3A22);
				context.fill(-2, -12, 2, -6, 0xFF1C3A22);
				context.fill(-2, -14, 2, -10, 0xFFDCB648);
				context.fill(-4, 0, 4, 4, 0xFFDCB648);
				context.pose().popMatrix();
				for (int i = 0; i < 6; i++) context.fill(x + 6 + i * 2, y - 10 - (i % 3) * 3, x + 8 + i * 2, y - 8 - (i % 3) * 3, 0xFFFFF0C0);
			}
			case MAYBACH_BOUNCE -> maybach(context, x, y);
			case WATCH_FLEX -> {
				// A band with the iced dial on it
				context.fill(x - 4, y - 14, x + 4, y + 14, 0xFFD8DCE4);
				context.blit(RenderPipelines.GUI_TEXTURED, WATCH, x - 9, y - 9, 0, 0, 18, 18, 32, 32, 32, 32);
			}
			case BITCOIN_RAIN -> {
				int[][] coins = {{-8, -8, 12}, {6, -5, 10}, {-2, 5, 14}};
				for (int[] coin : coins) {
					context.blit(RenderPipelines.GUI_TEXTURED, EmotesModule.BTC, x + coin[0] - coin[2] / 2, y + coin[1] - coin[2] / 2, 0, 0, coin[2], coin[2], 64, 64, 64, 64);
				}
			}
			case MONEY_COUNTER -> {
				// The machine, with a bill flying out of it and a counted brick beside it
				context.fill(x - 11, y - 3, x + 7, y + 9, 0xFF2E3036);
				context.fill(x - 8, y - 7, x + 4, y - 3, 0xFF4A4D55);
				context.fill(x - 6, y - 9, x + 2, y - 6, 0xFFA9B898);
				context.fill(x - 8, y, x - 1, y + 3, 0xFF59F08A);
				context.fill(x + 8, y + 3, x + 14, y + 9, 0xFFA9B898);
				context.fill(x + 10, y + 3, x + 12, y + 9, 0xFFC9A14A);
				context.pose().pushMatrix();
				context.pose().translate(x + 9, y - 6);
				context.pose().rotate(-0.5f);
				context.blit(RenderPipelines.GUI_TEXTURED, EmotesModule.BILL, -6, -3, 0, 0, 12, 5, 64, 28, 64, 28);
				context.pose().popMatrix();
			}
			case BITCOIN_FLIP -> {
				// A coin up in the air over a thumb, with its arc
				for (int i = 0; i < 5; i++) context.fill(x - 6 + i * 2, y + 6 - (int) (Math.sin(i / 4.0 * Math.PI) * 8), x - 5 + i * 2, y + 7 - (int) (Math.sin(i / 4.0 * Math.PI) * 8), 0x99FFFFFF);
				context.blit(RenderPipelines.GUI_TEXTURED, EmotesModule.BTC, x - 1, y - 14, 0, 0, 14, 14, 64, 64, 64, 64);
				context.fill(x - 12, y + 7, x - 3, y + 12, 0xFFC68E6A);
			}
			case SUPPORT_DESK -> {
				// A monitor with the blue dashboard, and a keyboard
				context.fill(x - 12, y - 12, x + 12, y + 4, 0xFF0E0F12);
				context.fill(x - 10, y - 10, x + 10, y + 2, 0xFFF5F7FA);
				context.fill(x - 10, y - 10, x + 10, y - 7, 0xFF0052FF);
				context.fill(x - 8, y - 4, x + 2, y - 3, 0xFFC9CED6);
				context.fill(x - 4, y - 1, x + 8, y, 0xFFD6E2FF);
				context.fill(x - 1, y + 4, x + 1, y + 7, 0xFF3A3D44);
				context.fill(x - 11, y + 8, x + 11, y + 12, 0xFF2A2B30);
			}
			case MAYBACH_LAPTOP -> {
				maybach(context, x, y + 4);
				// The laptop up on the bonnet, its screen lit
				context.fill(x - 5, y - 16, x + 7, y - 7, 0xFF969AA2);
				context.fill(x - 4, y - 15, x + 6, y - 8, 0xFF0052FF);
				context.fill(x - 7, y - 7, x + 9, y - 5, 0xFF969AA2);
			}
			case LEDGER_PLUG -> {
				// A laptop with a Ledger plugged into its side, and a coin popping out
				context.fill(x - 13, y - 6, x + 5, y + 5, 0xFF969AA2);
				context.fill(x - 12, y - 5, x + 4, y + 4, 0xFF0052FF);
				context.fill(x - 15, y + 5, x + 7, y + 8, 0xFF969AA2);
				context.fill(x + 7, y + 4, x + 15, y + 7, 0xFF1A1A1E);
				context.fill(x + 12, y + 4, x + 15, y + 7, 0xFFB9BBC2);
				context.blit(RenderPipelines.GUI_TEXTURED, EmotesModule.BTC, x - 1, y - 16, 0, 0, 10, 10, 64, 64, 64, 64);
			}
			case HODL -> {
				// The big coin held up, both hands under it
				context.blit(RenderPipelines.GUI_TEXTURED, EmotesModule.BTC, x - 10, y - 13, 0, 0, 20, 20, 64, 64, 64, 64);
				context.fill(x - 12, y + 3, x - 7, y + 12, 0xFFC68E6A);
				context.fill(x + 7, y + 3, x + 12, y + 12, 0xFFC68E6A);
			}
		}
	}

	/** A long black car side on, with chrome wheels. */
	private static void maybach(GuiGraphics context, int x, int y) {
		context.fill(x - 14, y - 2, x + 14, y + 5, 0xFF1A1B20);
		context.fill(x - 9, y - 8, x + 7, y - 2, 0xFF1A1B20);
		context.fill(x - 8, y - 7, x - 1, y - 3, 0xFF5A6070);
		context.fill(x + 1, y - 7, x + 6, y - 3, 0xFF5A6070);
		context.fill(x - 13, y + 3, x + 13, y + 4, 0xFFD6DAE0);
		for (int wx : new int[]{x - 8, x + 8}) {
			context.fill(wx - 3, y + 3, wx + 3, y + 9, 0xFF0C0C0E);
			context.fill(wx - 1, y + 5, wx + 1, y + 7, 0xFFD6DAE0);
		}
	}

	/** A little picture of a ride in its slot. */
	private static void rideIcon(GuiGraphics context, Ride ride, int x, int y) {
		switch (ride) {
			case MAYBACH -> maybach(context, x, y);
			case JESKO -> {
				// A low white wedge with lime trim and a wing
				int body = 0xFFE6E8EC, lime = 0xFF7CF23C;
				context.fill(x - 14, y, x + 14, y + 5, body);
				context.fill(x - 6, y - 4, x + 6, y, body);
				context.fill(x - 4, y - 3, x + 3, y, 0xFF22262E);
				context.fill(x + 8, y - 2, x + 14, y, body);
				context.fill(x - 14, y - 7, x - 7, y - 6, 0xFF22262E);
				context.fill(x - 11, y - 6, x - 10, y, 0xFF22262E);
				context.fill(x - 13, y + 4, x + 13, y + 5, lime);
				for (int wx : new int[]{x - 8, x + 8}) {
					context.fill(wx - 3, y + 3, wx + 3, y + 9, 0xFF0C0C0E);
					context.fill(wx - 1, y + 5, wx + 1, y + 7, lime);
				}
			}
			case JET -> {
				// A jet from above: fuselage, swept wings, tailplane
				int c = 0xFFF4F5F7, gold = 0xFFC9A14A;
				context.fill(x - 2, y - 13, x + 2, y + 12, c);
				context.fill(x - 1, y - 14, x + 1, y - 13, c);
				for (int i = 0; i < 11; i++) {
					context.fill(x - 3 - i, y - 1 + i / 2, x - 2, y + 2 + i / 2, c);
					context.fill(x + 2, y - 1 + i / 2, x + 3 + i, y + 2 + i / 2, c);
				}
				for (int i = 0; i < 5; i++) {
					context.fill(x - 3 - i, y + 9 + i / 2, x - 2, y + 11 + i / 2, c);
					context.fill(x + 2, y + 9 + i / 2, x + 3 + i, y + 11 + i / 2, c);
				}
				context.fill(x - 1, y - 9, x + 1, y + 8, gold);
			}
			case CLEAR -> {
				// A key with a cross
				int c = 0xFFFFFFFF;
				for (int i = -7; i <= 7; i++) {
					context.fill(x + i - 1, y + i - 1, x + i + 1, y + i + 1, c);
					context.fill(x + i - 1, y - i - 1, x + i + 1, y - i + 1, c);
				}
			}
			case FBI_RAID -> {
				// A navy jacket back with FBI across it, and the red and blue lights
				context.fill(x - 12, y - 8, x + 12, y + 11, 0xFF141A2E);
				context.fill(x - 14, y - 6, x - 12, y + 6, 0xFF141A2E);
				context.fill(x + 12, y - 6, x + 14, y + 6, 0xFF141A2E);
				var font = net.minecraft.client.Minecraft.getInstance().font;
				context.drawString(font, "FBI", x - font.width("FBI") / 2, y - 2, 0xFFF2D04A, false);
				context.fill(x - 12, y - 14, x - 2, y - 10, 0xFFE53935);
				context.fill(x + 2, y - 14, x + 12, y - 10, 0xFF1E5BFF);
			}
		}
	}

	/** Slot 0 is at the top, going clockwise. */
	private static double slotAngle(int slot) {
		return Math.PI * 2 * slot / SLOTS;
	}

	private static int pick(float dx, float dy) {
		if (dx * dx + dy * dy < DEAD_ZONE * DEAD_ZONE) return CENTER;
		double angle = Math.atan2(dx, -dy);
		if (angle < 0) angle += Math.PI * 2;
		return (int) Math.round(angle / (Math.PI * 2 / SLOTS)) % SLOTS;
	}

	/** Plays what's pointed at. Returns false when nothing is, so the wheel stays open. */
	private boolean choose() {
		if (shiftHeld() && module.hasFakes() && rideIn(hovered) == null) {
			// Shift: the fake players do it (or stop), you carry on as you are
			if (hovered == CENTER) {
				if (!module.fakesEmoting()) return false;
				module.stopFakes();
			} else {
				Emote emote = emoteIn(hovered);
				if (emote == null) return false;
				module.playFakes(emote);
			}
			onClose();
			return true;
		}
		if (hovered == CENTER) {
			if (module.playing() == null) return false;
			module.stop();
		} else {
			Ride ride = rideIn(hovered);
			if (ride != null) {
				onClose();
				summon(ride);
				return true;
			}
			Emote emote = emoteIn(hovered);
			if (emote == null) return false;
			module.play(emote);
		}
		onClose();
		return true;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
		if (click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			double mx = click.x() / UiScale.scale(), my = click.y() / UiScale.scale();
			if (Math.abs(my - arrowY - 4) < 10) {
				if (Math.abs(mx - leftArrowX) < 12) {
					turn(-1);
					return true;
				}
				if (Math.abs(mx - rightArrowX) < 12) {
					turn(1);
					return true;
				}
			}
		}
		if (click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && choose()) return true;
		return super.mouseClicked(click, doubled);
	}

	private void turn(int by) {
		page = Math.floorMod(page + by, pages());
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		if (vertical != 0) {
			turn(vertical < 0 ? 1 : -1);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
	}

	@Override
	public boolean keyReleased(KeyEvent input) {
		if (input.key() == module.wheelKey()) {
			// A quick tap leaves the wheel open to click on instead
			if (!choose() && System.currentTimeMillis() - opened > 250) onClose();
			return true;
		}
		return super.keyReleased(input);
	}
}
