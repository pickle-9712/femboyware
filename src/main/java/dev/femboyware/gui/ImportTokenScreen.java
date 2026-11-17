package dev.femboyware.gui;

import dev.femboyware.account.Account;
import dev.femboyware.account.Accounts;
import dev.femboyware.account.MsAuth;
import dev.femboyware.module.render.CleanFontModule;
import dev.femboyware.util.Mc;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Paste a Microsoft refresh token to add that account. The refresh token (from a launcher's saved
 * logins, a token exporter, or another manager) is swapped for a Minecraft session right away and
 * saved; from then on it tops itself up on its own. The app id box is optional: left blank, the
 * common launcher apps are tried. Nothing typed here leaves your PC except to Microsoft's own login.
 */
public class ImportTokenScreen extends Screen {
	private static final int GOOD = 0xFF7CE38B, BAD = 0xFFFF6B6B, INFO = 0xFFFFFFFF;

	private final AccountSwitcherScreen parent;
	private EditBox tokenBox, clientBox;
	private Button importBtn, paste, typeBtn;
	private String status = "";
	private int statusColor = INFO;
	private boolean busy;
	/** false = import a refresh token (lasting), true = import a Minecraft access token (expires). */
	private boolean accessMode;

	public ImportTokenScreen(AccountSwitcherScreen parent) {
		super(Component.literal("Import refresh token"));
		this.parent = parent;
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
		tokenBox = addRenderableWidget(new EditBox(font, left + 10, top + 46, 300, 18, Component.literal("Refresh token")));
		tokenBox.setMaxLength(4096);
		tokenBox.setHint(Component.literal("Paste the refresh token"));
		tokenBox.setResponder(t -> updateButtons());
		paste = addRenderableWidget(Button.builder(Component.literal("Paste"), b -> {
			tokenBox.setValue(minecraft.keyboardHandler.getClipboard().replaceAll("\\s", ""));
			updateButtons();
		}).bounds(left + 316, top + 46, 74, 20).build());
		clientBox = addRenderableWidget(new EditBox(font, left + 10, top + 84, 300, 18, Component.literal("App id")));
		clientBox.setMaxLength(100);
		clientBox.setHint(Component.literal("App id (optional)"));
		typeBtn = addRenderableWidget(Button.builder(Component.literal(""), b -> {
			accessMode = !accessMode;
			updateButtons();
		}).bounds(left + 10, top + 120, 180, 20).build());
		importBtn = addRenderableWidget(Button.builder(Component.literal("Import"), b -> doImport()).bounds(left + 200, top + 120, 110, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose()).bounds(left + 300, top + 206, 90, 20).build());
		updateButtons();
	}

	private void updateButtons() {
		if (importBtn == null) return;
		importBtn.active = !busy && tokenBox.getValue().trim().length() > 20;
		paste.active = !busy;
		typeBtn.active = !busy;
		typeBtn.setMessage(Component.literal(accessMode ? "Type: Access token" : "Type: Refresh token"));
		// An access token has no app, so grey the app id box out in that mode
		clientBox.setEditable(!accessMode);
		clientBox.active = !accessMode;
		tokenBox.setHint(Component.literal(accessMode ? "Paste the access token" : "Paste the refresh token"));
	}

	private void doImport() {
		String token = tokenBox.getValue().trim();
		String client = clientBox.getValue().trim();
		boolean access = accessMode;
		busy = true;
		setStatus(access ? "Checking the access token with Minecraft..." : "Checking the token with Microsoft...", INFO);
		updateButtons();
		CompletableFuture.supplyAsync(() -> {
			try {
				return access ? Accounts.fromAccessToken(token) : Accounts.fromRefreshToken(client.isEmpty() ? null : client, token);
			} catch (MsAuth.AuthException e) {
				return e;
			}
		}).thenAcceptAsync(result -> {
			busy = false;
			if (result instanceof MsAuth.AuthException e) {
				setStatus(e.getMessage(), BAD);
				updateButtons();
			} else if (result instanceof Account account) {
				parent.refreshList();
				parent.selectByUuid(account.uuid);
				Mc.setScreen(parent);
			}
		}, minecraft);
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
		context.drawString(font, AccountScreen.text("Import a token", CleanFontModule.CLEAN), left + 10, top + 8, 0xFFFFFFFF, false);
		String blurb = accessMode
			? "Adds an account from a Minecraft access token. It can't be renewed, so it stops"
			: "Adds a Microsoft account from its refresh token and keeps it topped up on its own.";
		context.drawString(font, AccountScreen.text(blurb, CleanFontModule.CLEAN), left + 10, top + 22, Theme.muted(), false);
		if (accessMode) context.drawString(font, AccountScreen.text("working when it expires (about a day) - it'll tell you when you switch to it.", CleanFontModule.CLEAN),
			left + 10, top + 32, Theme.muted(), false);
		context.drawString(font, AccountScreen.text(accessMode ? "Access token" : "Refresh token", CleanFontModule.CLEAN), left + 10, top + 36, Theme.muted(), false);
		context.drawString(font, AccountScreen.text(accessMode ? "App id  -  not used for an access token" : "App id  -  leave blank to try the usual launcher apps", CleanFontModule.CLEAN),
			left + 10, top + 74, accessMode ? Theme.offDot() : Theme.muted(), false);
		if (!status.isEmpty()) {
			context.drawString(font, AccountScreen.text(font.plainSubstrByWidth(status, 380), CleanFontModule.CLEAN), left + 10, top + 150, statusColor, false);
		}
	}

	private void setStatus(String text, int color) {
		status = text;
		statusColor = color;
	}

	@Override
	public void onClose() {
		Mc.setScreen(parent);
	}
}
