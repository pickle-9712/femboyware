package dev.femboyware.gui;

import dev.femboyware.account.Account;
import dev.femboyware.account.Accounts;
import dev.femboyware.account.MsAuth;
import dev.femboyware.account.SessionSwitch;
import dev.femboyware.module.render.CleanFontModule;
import dev.femboyware.util.Mc;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Switch between saved Minecraft accounts without restarting the game. Add one through Microsoft's
 * sign-in page or by importing a refresh token; both are kept (encrypted) so next time is one click.
 * Refresh forces a new Minecraft token from the saved refresh token. Nothing is uploaded anywhere.
 */
public class AccountSwitcherScreen extends Screen {
	private static final int GOOD = 0xFF7CE38B, BAD = 0xFFFF6B6B, INFO = 0xFFFFFFFF, PENDING = 0xFFFFD166;
	private static final int ROW_H = 20, ROWS = 6;

	private final Screen parent;
	private final List<Account> accounts;
	private int selected = -1;
	private int removeArmed = -1;
	private long removeUntil;
	/** Index of the first account shown, so more than {@link #ROWS} can be scrolled through. */
	private int scroll;

	private Button switchBtn, refreshBtn, removeBtn, addBtn, importBtn, logoutBtn;
	private String status = "";
	private int statusColor = INFO;
	private boolean busy;

	// Device-code sign-in in progress
	private MsAuth.DeviceCode device;
	private boolean polling;

	public AccountSwitcherScreen(Screen parent) {
		super(Component.literal("Accounts"));
		this.parent = parent;
		this.accounts = Accounts.store().sorted();
	}

	private int left() {
		return width / 2 - 200;
	}

	private int top() {
		return Math.max(4, (height - 226) / 2);
	}

	@Override
	protected void init() {
		int left = left(), top = top();
		int rightX = left + 270;
		switchBtn = addRenderableWidget(Button.builder(Component.literal("Switch to"), b -> doSwitch()).bounds(rightX, top + 30, 120, 20).build());
		refreshBtn = addRenderableWidget(Button.builder(Component.literal("Refresh token"), b -> doRefresh()).bounds(rightX, top + 54, 120, 20).build());
		removeBtn = addRenderableWidget(Button.builder(Component.literal("Remove"), b -> doRemove()).bounds(rightX, top + 78, 120, 20).build());
		addBtn = addRenderableWidget(Button.builder(Component.literal("Add (Microsoft)"), b -> startDeviceLogin()).bounds(rightX, top + 114, 120, 20).build());
		importBtn = addRenderableWidget(Button.builder(Component.literal("Import token..."), b -> Mc.setScreen(new ImportTokenScreen(this))).bounds(rightX, top + 138, 120, 20).build());
		logoutBtn = addRenderableWidget(Button.builder(Component.literal("Use launcher account"), b -> useLauncher()).bounds(rightX, top + 162, 120, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose()).bounds(left + 310, top + 206, 90, 20).build());
		updateButtons();
	}

	private void updateButtons() {
		if (switchBtn == null) return;
		boolean has = selected >= 0 && selected < accounts.size();
		Account a = has ? accounts.get(selected) : null;
		switchBtn.active = has && !busy;
		refreshBtn.active = has && !busy && a != null && !a.offline && a.refreshToken != null;
		removeBtn.active = has && !busy;
		addBtn.active = !busy;
		importBtn.active = !busy;
		logoutBtn.active = !busy && SessionSwitch.switched();
		boolean armed = has && removeArmed == selected && System.currentTimeMillis() < removeUntil;
		removeBtn.setMessage(Component.literal(armed ? "Sure?" : "Remove"));
	}

	@Override
	public void tick() {
		if (removeArmed >= 0 && System.currentTimeMillis() >= removeUntil) {
			removeArmed = -1;
			updateButtons();
		}
		// Poll Microsoft while a sign-in is waiting for the code to be entered
		if (device != null && !polling) {
			if (System.currentTimeMillis() > device.expiresAt()) {
				device = null;
				done("The sign-in code expired", BAD);
				return;
			}
			polling = true;
			MsAuth.DeviceCode code = device;
			CompletableFuture.supplyAsync(() -> {
				try {
					Thread.sleep(Math.max(2, code.interval()) * 1000L);
					return Accounts.store() == null ? null : MsAuth.pollDevice(code);
				} catch (Exception e) {
					return e;
				}
			}).thenAcceptAsync(result -> {
				polling = false;
				if (device != code) return;
				if (result instanceof MsAuth.AuthException e) {
					device = null;
					done(e.getMessage(), BAD);
				} else if (result instanceof MsAuth.MsTokens tokens) {
					device = null;
					finishDeviceLogin(tokens);
				}
			}, minecraft);
		}
	}

	// ---------------------------------------------------------------- actions

	private void doSwitch() {
		if (selected < 0) return;
		Account account = accounts.get(selected);
		busy = true;
		setStatus("Signing in as " + account.name + "...", INFO);
		updateButtons();
		offThread(() -> Accounts.login(account), login -> {
			Accounts.switchTo(account, login);
			done("Switched to " + account.name + ". You're playing as them now", GOOD);
		});
	}

	private void doRefresh() {
		if (selected < 0) return;
		Account account = accounts.get(selected);
		busy = true;
		setStatus("Getting a fresh token for " + account.name + "...", INFO);
		updateButtons();
		offThread(() -> Accounts.refresh(account), login -> done("Refreshed " + account.name + "'s token", GOOD));
	}

	private void doRemove() {
		if (selected < 0) return;
		if (removeArmed != selected || System.currentTimeMillis() >= removeUntil) {
			removeArmed = selected;
			removeUntil = System.currentTimeMillis() + 4000;
			setStatus("Click again to remove " + accounts.get(selected).name, PENDING);
			updateButtons();
			return;
		}
		Account account = accounts.get(selected);
		Accounts.store().remove(account);
		accounts.remove(selected);
		selected = -1;
		removeArmed = -1;
		setStatus("Removed " + account.name, INFO);
		updateButtons();
	}

	private void useLauncher() {
		SessionSwitch.restore();
		done("Back on your launcher account (" + Accounts.currentName() + ")", GOOD);
	}

	private void startDeviceLogin() {
		busy = true;
		setStatus("Asking Microsoft for a sign-in code...", INFO);
		updateButtons();
		CompletableFuture.supplyAsync(() -> {
			try {
				return MsAuth.deviceCode();
			} catch (MsAuth.AuthException e) {
				return e;
			}
		}).thenAcceptAsync(result -> {
			busy = false;
			if (result instanceof MsAuth.AuthException e) {
				done(e.getMessage(), BAD);
			} else if (result instanceof MsAuth.DeviceCode code) {
				device = code;
				minecraft.keyboardHandler.setClipboard(code.userCode());
				openUri(code.link());
				setStatus("Enter code " + code.userCode() + " on the page (copied; browser opened)", PENDING);
			}
			updateButtons();
		}, minecraft);
	}

	private void finishDeviceLogin(MsAuth.MsTokens tokens) {
		busy = true;
		setStatus("Signing in to Minecraft...", INFO);
		updateButtons();
		offThread(() -> Accounts.fromDeviceLogin(tokens), account -> {
			refreshList();
			selectByUuid(account.uuid);
			done("Added " + account.name + ". Select it and Switch to", GOOD);
		});
	}

	/** Pull the account list again after it changed. */
	void refreshList() {
		accounts.clear();
		accounts.addAll(Accounts.store().sorted());
	}

	void selectByUuid(java.util.UUID uuid) {
		for (int i = 0; i < accounts.size(); i++) {
			if (accounts.get(i).uuid.equals(uuid)) selected = i;
		}
	}

	/** Run network work off the main thread, then hand the result back on it (or show the error). */
	private <T> void offThread(ThrowingSupplier<T> work, java.util.function.Consumer<T> then) {
		CompletableFuture.supplyAsync(() -> {
			try {
				return work.get();
			} catch (MsAuth.AuthException e) {
				return e;
			}
		}).thenAcceptAsync(result -> {
			if (result instanceof MsAuth.AuthException e) {
				done(e.getMessage(), BAD);
			} else {
				@SuppressWarnings("unchecked")
				T value = (T) result;
				then.accept(value);
			}
			updateButtons();
		}, minecraft);
	}

	private interface ThrowingSupplier<T> {
		T get() throws MsAuth.AuthException;
	}

	/** Open the sign-in page in the default browser, the same way across every OS and game version. */
	private void openUri(String uri) {
		try {
			String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
			if (os.startsWith("windows")) new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", uri).start();
			else if (os.startsWith("mac")) new ProcessBuilder("open", uri).start();
			else new ProcessBuilder("xdg-open", uri).start();
		} catch (Exception ignored) {
		}
	}

	// ---------------------------------------------------------------- drawing and clicks

	/** The y of the row-slot an on-screen account sits in (0 is the top slot). */
	private int slotY(int slot) {
		return top() + 30 + slot * ROW_H;
	}

	/** Keep the scroll in range and the selected account on screen. */
	private void clampScroll() {
		int max = Math.max(0, accounts.size() - ROWS);
		if (selected >= 0) {
			if (selected < scroll) scroll = selected;
			else if (selected >= scroll + ROWS) scroll = selected - ROWS + 1;
		}
		scroll = Math.max(0, Math.min(scroll, max));
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amountX, double amountY) {
		if (accounts.size() > ROWS) {
			scroll = Math.max(0, Math.min(scroll - (int) Math.signum(amountY), accounts.size() - ROWS));
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, amountX, amountY);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
		if (click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			int left = left();
			for (int slot = 0; slot < ROWS; slot++) {
				int i = scroll + slot;
				if (i >= accounts.size()) break;
				int y = slotY(slot);
				if (click.x() >= left + 10 && click.x() < left + 255 && click.y() >= y && click.y() < y + ROW_H - 2) {
					if (selected != i) removeArmed = -1;
					selected = i;
					if (doubled) doSwitch();
					updateButtons();
					return true;
				}
			}
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public void renderBackground(GuiGraphics context, int mouseX, int mouseY, float deltaTicks) {
		if (!dev.femboyware.module.theme.MenuBackgroundModule.draw(context, width, height)) SwirlBackground.draw(context, width, height);
		context.fill(0, 0, width, height, 0x66000000);
		int left = left(), top = top();
		FemboyTitleScreen.roundedRect(context, left, top, left + 400, top + 200, 6, 0xCC1A0F1F);
	}

	@Override
	public void render(GuiGraphics context, int mouseX, int mouseY, float deltaTicks) {
		//? if >=26.1 {
		/*super.extractRenderState(context, mouseX, mouseY, deltaTicks);
		*///?} else {
		super.render(context, mouseX, mouseY, deltaTicks);
		//?}
		int left = left(), top = top();
		context.drawString(font, AccountScreen.text("Accounts", CleanFontModule.CLEAN), left + 10, top + 8, 0xFFFFFFFF, false);
		String on = "Playing as " + Accounts.currentName() + (SessionSwitch.switched() ? " (switched)" : " (launcher)");
		context.drawString(font, AccountScreen.text(on, CleanFontModule.CLEAN), left + 10, top + 18, Theme.muted(), false);

		java.util.UUID current = Accounts.currentUuid();
		if (accounts.isEmpty()) {
			context.drawString(font, AccountScreen.text("No saved accounts. Add one with the buttons on the right.", CleanFontModule.CLEAN),
				left + 12, top + 36, Theme.muted(), false);
		}
		clampScroll();
		for (int slot = 0; slot < ROWS; slot++) {
			int i = scroll + slot;
			if (i >= accounts.size()) break;
			Account a = accounts.get(i);
			int y = slotY(slot);
			boolean hover = mouseX >= left + 10 && mouseX < left + 255 && mouseY >= y && mouseY < y + ROW_H - 2;
			int fill = i == selected ? Theme.menuPrimary() : hover ? 0x40FFFFFF : 0x22FFFFFF;
			FemboyTitleScreen.roundedRect(context, left + 10, y, left + 255, y + ROW_H - 2, 4, fill);
			boolean isCurrent = a.uuid.equals(current);
			Glass.rounded(context, left + 16, y + 6, left + 22, y + 12, 3, isCurrent ? GOOD : Theme.offDot());
			context.drawString(font, AccountScreen.text(a.name, CleanFontModule.CLEAN), left + 28, y + 5, 0xFFFFFFFF, false);
			String tag = a.offline ? "offline" : a.tokenFresh() ? "ready" : "needs refresh";
			int tagColor = a.offline ? Theme.muted() : a.tokenFresh() ? GOOD : PENDING;
			context.drawString(font, AccountScreen.text(tag, CleanFontModule.CLEAN), left + 250 - font.width(tag), y + 5, tagColor, false);
		}
		// How far down the list we are, and that there's more above or below
		if (accounts.size() > ROWS) {
			String range = (scroll + 1) + "-" + Math.min(accounts.size(), scroll + ROWS) + " of " + accounts.size();
			context.drawString(font, AccountScreen.text(range, CleanFontModule.CLEAN), left + 255 - font.width(range), top + 18, Theme.muted(), false);
			if (scroll > 0) context.drawString(font, AccountScreen.text("▲", CleanFontModule.CLEAN), left + 258, slotY(0) + 2, Theme.muted(), false);
			if (scroll + ROWS < accounts.size()) context.drawString(font, AccountScreen.text("▼", CleanFontModule.CLEAN), left + 258, slotY(ROWS - 1) + 2, Theme.muted(), false);
		}

		if (device != null) {
			context.drawString(font, AccountScreen.text("Code: " + device.userCode(), CleanFontModule.CLEAN), left + 10, top + 162, PENDING, false);
			context.drawString(font, AccountScreen.text("on microsoft.com/link", CleanFontModule.CLEAN), left + 10, top + 174, Theme.muted(), false);
		}
		if (!status.isEmpty()) {
			Component line = AccountScreen.text(font.plainSubstrByWidth(status, 380), CleanFontModule.CLEAN);
			context.drawString(font, line, left + 10, top + 190, statusColor, false);
		}
	}

	private void setStatus(String text, int color) {
		status = text;
		statusColor = color;
	}

	private void done(String message, int color) {
		busy = false;
		setStatus(message, color);
		updateButtons();
	}

	@Override
	public void onClose() {
		Mc.setScreen(parent);
	}
}
