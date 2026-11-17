package dev.femboyware.gui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import dev.femboyware.module.render.CleanFontModule;
import dev.femboyware.util.Mc;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

/**
 * Your Minecraft account's name and skin, changed through Mojang's own API with the session you're
 * signed in with: the same thing the launcher and minecraft.net do. The account it acts on is always
 * spelled out at the top (name, UUID, and what Mojang says it's called right now), and Name Protect
 * leaves this screen alone, so you always see the real names.
 *
 * <p>The access token is only ever sent to api.minecraftservices.com, never logged or shown.
 */
public class AccountScreen extends Screen {
	private static final String API = "https://api.minecraftservices.com/minecraft/profile";
	private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ROOT).withZone(ZoneId.systemDefault());
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private static final Identifier CURRENT_SKIN = Identifier.fromNamespaceAndPath("femboyware", "account/current_skin");
	private static final Identifier CHOSEN_SKIN = Identifier.fromNamespaceAndPath("femboyware", "account/chosen_skin");
	private static final int GOOD = 0xFF7CE38B, BAD = 0xFFFF6B6B, INFO = 0xFFFFFFFF;

	private final Screen parent;
	/** The session this screen acts for, taken once when it opens so it can't change underneath you. */
	private final String sessionName;
	private final UUID sessionId;
	private final String token;

	/** What Mojang says right now; null until loaded. */
	private String liveName;
	private String liveSkinVariant;
	private String nameChangeLine = "Checking when you can change your name...";
	private boolean nameChangeAllowed;
	private boolean signedIn = true;

	private EditBox nameBox;
	private Button changeName, checkName, chooseSkin, variant, uploadSkin, refresh;
	/** The name waiting for its second click to confirm, and until when. */
	private String pendingName;
	private long pendingUntil;

	private Path chosenFile;
	private byte[] chosenBytes;
	private boolean chosenLegacy, currentLegacy;
	private boolean slim;
	private boolean hasCurrentSkin, hasChosenSkin;

	private String status = "";
	private int statusColor = INFO;
	private boolean busy;

	public AccountScreen(Screen parent) {
		super(Component.literal("Account"));
		this.parent = parent;
		var user = net.minecraft.client.Minecraft.getInstance().getUser();
		sessionName = user.getName();
		sessionId = user.getProfileId();
		token = user.getAccessToken();
	}

	// ---------------------------------------------------------------- layout

	private int left() {
		return width / 2 - 200;
	}

	private int top() {
		return Math.max(4, (height - 226) / 2);
	}

	@Override
	protected void init() {
		int left = left(), top = top();
		int columnY = top + 72;

		nameBox = addRenderableWidget(new EditBox(font, left + 10, columnY + 34, 180, 18, Component.literal("New name")));
		nameBox.setMaxLength(16);
		nameBox.setHint(Component.literal("New name"));
		nameBox.setResponder(text -> {
			pendingName = null;
			updateButtons();
		});
		checkName = addRenderableWidget(Button.builder(Component.literal("Check"), b -> checkAvailable()).bounds(left + 10, columnY + 58, 60, 20).build());
		changeName = addRenderableWidget(Button.builder(Component.literal("Change name"), b -> changeName()).bounds(left + 74, columnY + 58, 116, 20).build());

		int skinX = left + 210;
		chooseSkin = addRenderableWidget(Button.builder(Component.literal("Choose PNG..."), b -> chooseFile()).bounds(skinX + 80, columnY + 28, 100, 20).build());
		variant = addRenderableWidget(Button.builder(Component.literal(""), b -> {
			slim = !slim;
			updateButtons();
		}).bounds(skinX + 80, columnY + 50, 100, 20).build());
		uploadSkin = addRenderableWidget(Button.builder(Component.literal("Upload skin"), b -> uploadSkin()).bounds(skinX + 80, columnY + 72, 100, 20).build());

		refresh = addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> load()).bounds(left + 10, top + 206, 90, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose()).bounds(left + 300, top + 206, 90, 20).build());
		updateButtons();
		if (liveName == null && !busy) load();
	}

	private void updateButtons() {
		if (changeName == null) return;
		String wanted = nameBox.getValue().trim();
		boolean valid = VALID_NAME.matcher(wanted).matches() && !wanted.equalsIgnoreCase(liveName == null ? sessionName : liveName);
		boolean confirming = pendingName != null && pendingName.equals(wanted) && System.currentTimeMillis() < pendingUntil;
		changeName.setMessage(Component.literal(confirming ? "Click again to confirm" : "Change name"));
		changeName.active = !busy && signedIn && valid && nameChangeAllowed;
		checkName.active = !busy && signedIn && valid;
		variant.setMessage(Component.literal(slim ? "Arms: Slim" : "Arms: Classic"));
		uploadSkin.active = !busy && signedIn && chosenBytes != null;
		chooseSkin.active = !busy && signedIn;
		refresh.active = !busy;
	}

	@Override
	public void tick() {
		// The confirm click runs out after a few seconds
		if (pendingName != null && System.currentTimeMillis() >= pendingUntil) {
			pendingName = null;
			updateButtons();
		}
	}

	@Override
	public void onClose() {
		release(CURRENT_SKIN);
		release(CHOSEN_SKIN);
		Mc.setScreen(parent);
	}

	// ---------------------------------------------------------------- drawing

	@Override
	public void renderBackground(GuiGraphics context, int mouseX, int mouseY, float deltaTicks) {
		if (!dev.femboyware.module.theme.MenuBackgroundModule.draw(context, width, height)) SwirlBackground.draw(context, width, height);
		context.fill(0, 0, width, height, 0x66000000);
		int left = left(), top = top();
		// Who this is for, loud and clear
		FemboyTitleScreen.roundedRect(context, left, top, left + 400, top + 62, 6, Theme.menuPrimary());
		FemboyTitleScreen.roundedRect(context, left, top + 68, left + 200 - 4, top + 200, 6, 0xCC1A0F1F);
		FemboyTitleScreen.roundedRect(context, left + 204, top + 68, left + 400, top + 200, 6, 0xCC1A0F1F);
	}

	@Override
	public void render(GuiGraphics context, int mouseX, int mouseY, float deltaTicks) {
		//? if >=26.1 {
		/*super.extractRenderState(context, mouseX, mouseY, deltaTicks);
		*///?} else {
		super.render(context, mouseX, mouseY, deltaTicks);
		//?}
		int left = left(), top = top();

		context.drawString(font, text("CHANGING THIS ACCOUNT", CleanFontModule.CLEAN), left + 10, top + 6, Theme.menuButtonText(), false);
		String shown = liveName != null ? liveName : sessionName;
		context.pose().pushMatrix();
		context.pose().translate(left + 10, top + 18);
		context.pose().scale(2, 2);
		context.drawString(font, text(shown, CleanFontModule.CLEAN), 0, 0, 0xFFFFFFFF, false);
		context.pose().popMatrix();
		context.drawString(font, text("UUID " + sessionId, CleanFontModule.CLEAN), left + 10, top + 39, Theme.menuButtonText(), false);
		String session = !signedIn ? "Not signed in to a Microsoft account: nothing can be changed"
			: liveName == null ? "Loading from Mojang..."
			: liveName.equals(sessionName) ? "Signed in with the launcher as " + sessionName
			: "Signed in as " + sessionName + ", now called " + liveName + " (the game uses the new name after a restart)";
		context.drawString(font, text(session, CleanFontModule.CLEAN), left + 10, top + 50, Theme.menuButtonText(), false);

		int columnY = top + 72;
		context.drawString(font, text("Name", CleanFontModule.CLEAN), left + 10, columnY + 6, 0xFFFFFFFF, false);
		context.drawString(font, text(font.plainSubstrByWidth(nameChangeLine, 180), CleanFontModule.CLEAN), left + 10, columnY + 20, nameChangeAllowed ? GOOD : Theme.muted(), false);
		context.drawString(font, text("3-16 letters, numbers or _", CleanFontModule.CLEAN), left + 10, columnY + 84, Theme.muted(), false);
		context.drawString(font, text("After a change you have to wait", CleanFontModule.CLEAN), left + 10, columnY + 98, Theme.muted(), false);
		context.drawString(font, text("30 days to change it again.", CleanFontModule.CLEAN), left + 10, columnY + 108, Theme.muted(), false);

		int skinX = left + 210;
		context.drawString(font, text("Skin", CleanFontModule.CLEAN), skinX + 4, columnY + 6, 0xFFFFFFFF, false);
		context.drawString(font, text("Now", CleanFontModule.CLEAN), skinX + 8, columnY + 18, Theme.muted(), false);
		if (hasCurrentSkin) drawSkin(context, CURRENT_SKIN, skinX + 8, columnY + 30, 2, "slim".equalsIgnoreCase(liveSkinVariant), currentLegacy);
		if (hasChosenSkin) {
			context.drawString(font, text("New", CleanFontModule.CLEAN), skinX + 46, columnY + 18, Theme.muted(), false);
			drawSkin(context, CHOSEN_SKIN, skinX + 44, columnY + 30, 2, slim, chosenLegacy);
		}
		String file = chosenFile == null ? "No file chosen" : chosenFile.getFileName().toString();
		context.drawString(font, text(font.plainSubstrByWidth(file, 180), CleanFontModule.CLEAN), skinX + 4, columnY + 100, Theme.muted(), false);
		context.drawString(font, text("64x64 or 64x32 PNG", CleanFontModule.CLEAN), skinX + 4, columnY + 112, Theme.muted(), false);

		if (!status.isEmpty()) {
			Component line = text(font.plainSubstrByWidth(status, 190), CleanFontModule.CLEAN);
			context.drawString(font, line, width / 2 - font.width(line) / 2, top + 212, statusColor, false);
		}
	}

	/** The skin from the front, flat: head, body, arms and legs with their outer layers. */
	private static void drawSkin(GuiGraphics context, Identifier id, int x, int y, float scale, boolean slim, boolean legacy) {
		int texHeight = legacy ? 32 : 64;
		int arm = slim ? 3 : 4;
		context.pose().pushMatrix();
		context.pose().translate(x, y);
		context.pose().scale(scale, scale);
		// Head and hat
		part(context, id, 4, 0, 8, 8, 8, 8, texHeight);
		part(context, id, 4, 0, 40, 8, 8, 8, texHeight);
		// Body, right arm (on the left as you look at it), left arm, legs
		part(context, id, 4, 8, 20, 20, 8, 12, texHeight);
		part(context, id, 4 - arm, 8, 44, 20, arm, 12, texHeight);
		part(context, id, 12, 8, legacy ? 44 : 36, legacy ? 20 : 52, arm, 12, texHeight);
		part(context, id, 4, 20, 4, 20, 4, 12, texHeight);
		part(context, id, 8, 20, legacy ? 4 : 20, legacy ? 20 : 52, 4, 12, texHeight);
		if (!legacy) {
			// The outer layers: jacket, sleeves and trousers
			part(context, id, 4, 8, 20, 36, 8, 12, texHeight);
			part(context, id, 4 - arm, 8, 44, 36, arm, 12, texHeight);
			part(context, id, 12, 8, 52, 52, arm, 12, texHeight);
			part(context, id, 4, 20, 4, 36, 4, 12, texHeight);
			part(context, id, 8, 20, 4, 52, 4, 12, texHeight);
		}
		context.pose().popMatrix();
	}

	private static void part(GuiGraphics context, Identifier id, int x, int y, int u, int v, int w, int h, int texHeight) {
		context.blit(RenderPipelines.GUI_TEXTURED, id, x, y, u, v, w, h, w, h, 64, texHeight);
	}

	private static Component text(String text, FontDescription font) {
		return Component.literal(text).setStyle(Style.EMPTY.withFont(font));
	}

	private void setStatus(String text, int color) {
		status = text;
		statusColor = color;
	}

	// ---------------------------------------------------------------- talking to Mojang

	/** Your profile and when you can next change your name. */
	private void load() {
		busy = true;
		setStatus("Loading your profile from Mojang...", INFO);
		updateButtons();
		send(get(API), response -> {
			if (response.statusCode() == 401) {
				signedIn = false;
				nameChangeLine = "";
				done("Login expired: restart the launcher", BAD);
				return;
			}
			if (response.statusCode() == 404) {
				signedIn = false;
				nameChangeLine = "";
				done("This Microsoft account doesn't own Minecraft (no profile)", BAD);
				return;
			}
			if (response.statusCode() != 200) {
				done(error("Couldn't load your profile", response), BAD);
				return;
			}
			JsonObject profile = JsonParser.parseString(response.body()).getAsJsonObject();
			// Only ever act for the account that's signed in
			if (!sameAccount(profile)) {
				signedIn = false;
				nameChangeLine = "";
				done("Mojang returned a different account than this session: not touching it", BAD);
				return;
			}
			showProfile(profile);
			send(get(API + "/namechange"), names -> {
				if (names.statusCode() == 200) {
					JsonObject info = JsonParser.parseString(names.body()).getAsJsonObject();
					nameChangeAllowed = info.has("nameChangeAllowed") && info.get("nameChangeAllowed").getAsBoolean();
					if (nameChangeAllowed) nameChangeLine = "You can change your name now";
					else if (info.has("changedAt")) nameChangeLine = "Next change from " + DATE.format(Instant.parse(info.get("changedAt").getAsString()).plus(Duration.ofDays(30)));
					else nameChangeLine = "Mojang won't allow a name change right now";
				} else {
					nameChangeLine = "Couldn't check (" + names.statusCode() + ")";
				}
				done("", INFO);
			});
		});
	}

	private boolean sameAccount(JsonObject profile) {
		if (!profile.has("id")) return false;
		String id = profile.get("id").getAsString().replace("-", "");
		return id.equalsIgnoreCase(sessionId.toString().replace("-", ""));
	}

	private void showProfile(JsonObject profile) {
		liveName = profile.get("name").getAsString();
		liveSkinVariant = null;
		String url = null;
		if (profile.has("skins")) {
			for (JsonElement element : profile.getAsJsonArray("skins")) {
				JsonObject skin = element.getAsJsonObject();
				if (!"ACTIVE".equals(skin.has("state") ? skin.get("state").getAsString() : null)) continue;
				url = skin.get("url").getAsString();
				liveSkinVariant = skin.has("variant") ? skin.get("variant").getAsString() : "CLASSIC";
			}
		}
		if (url == null) return;
		// Skin textures are plain http on Mojang's side; ask for https
		HTTP.sendAsync(HttpRequest.newBuilder(URI.create(url.replaceFirst("^http:", "https:"))).timeout(Duration.ofSeconds(15)).GET().build(),
			HttpResponse.BodyHandlers.ofByteArray()).thenAccept(response -> {
				if (response.statusCode() != 200) return;
				minecraft.execute(() -> {
					int texHeight = upload(CURRENT_SKIN, response.body());
					hasCurrentSkin = texHeight > 0;
					currentLegacy = texHeight == 32;
				});
			});
	}

	private void checkAvailable() {
		String wanted = nameBox.getValue().trim();
		busy = true;
		setStatus("Checking " + wanted + "...", INFO);
		updateButtons();
		send(get(API + "/name/" + encode(wanted) + "/available"), response -> {
			if (response.statusCode() != 200) {
				done(error("Couldn't check", response), BAD);
				return;
			}
			String state = JsonParser.parseString(response.body()).getAsJsonObject().get("status").getAsString();
			switch (state) {
				case "AVAILABLE" -> done(wanted + " is available", GOOD);
				case "DUPLICATE" -> done(wanted + " is taken", BAD);
				case "NOT_ALLOWED" -> done(wanted + " isn't allowed by Mojang", BAD);
				default -> done(wanted + ": " + state, BAD);
			}
		});
	}

	/** First click asks to confirm (it can't be undone for 30 days), the second changes it. */
	private void changeName() {
		String wanted = nameBox.getValue().trim();
		String current = liveName == null ? sessionName : liveName;
		if (pendingName == null || !pendingName.equals(wanted) || System.currentTimeMillis() >= pendingUntil) {
			pendingName = wanted;
			pendingUntil = System.currentTimeMillis() + 5000;
			setStatus("Rename " + current + " to " + wanted + "? Click again to confirm", 0xFFFFD166);
			updateButtons();
			return;
		}
		pendingName = null;
		busy = true;
		setStatus("Renaming " + current + " to " + wanted + "...", INFO);
		updateButtons();
		send(request(API + "/name/" + encode(wanted)).PUT(HttpRequest.BodyPublishers.noBody()).build(), response -> {
			if (response.statusCode() == 200) {
				showProfile(JsonParser.parseString(response.body()).getAsJsonObject());
				nameChangeAllowed = false;
				nameChangeLine = "Next change from " + DATE.format(Instant.now().plus(Duration.ofDays(30)));
				nameBox.setValue("");
				done("Done: you're now " + liveName + ". Restart the game for it to use the new name", GOOD);
			} else if (response.statusCode() == 403) {
				done(error("Mojang refused (taken, not allowed, or changed in the last 30 days)", response), BAD);
			} else if (response.statusCode() == 400) {
				done(error("That name isn't valid", response), BAD);
			} else {
				done(error("Couldn't change your name", response), BAD);
			}
		});
	}

	/** Windows' own Open dialog, on top of the game, run off the render thread so the game keeps drawing. */
	private void chooseFile() {
		if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
			setStatus("Choosing a file only works on Windows for now", BAD);
			return;
		}
		chooseSkin.active = false;
		String script = "Add-Type -AssemblyName System.Windows.Forms;"
			+ "$owner = New-Object System.Windows.Forms.Form -Property @{TopMost = $true};"
			+ "$dialog = New-Object System.Windows.Forms.OpenFileDialog -Property @{Filter = 'PNG skins (*.png)|*.png'; Title = 'Choose a skin'};"
			+ "if ($dialog.ShowDialog($owner) -eq 'OK') { [Console]::Out.Write($dialog.FileName) }";
		CompletableFuture.supplyAsync(() -> {
			try {
				Process process = new ProcessBuilder("powershell", "-NoProfile", "-STA", "-Command", script).redirectErrorStream(false).start();
				process.getOutputStream().close();
				String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
				process.waitFor();
				return out;
			} catch (Exception e) {
				return "";
			}
		}).thenAccept(picked -> minecraft.execute(() -> {
			updateButtons();
			if (!picked.isEmpty()) loadChosen(picked);
		}));
	}

	private void loadChosen(String picked) {
		try {
			Path path = Path.of(picked);
			byte[] bytes = Files.readAllBytes(path);
			int texHeight = upload(CHOSEN_SKIN, bytes);
			if (texHeight == 0) {
				hasChosenSkin = false;
				chosenBytes = null;
				setStatus("That isn't a 64x64 or 64x32 PNG skin", BAD);
			} else {
				chosenFile = path;
				chosenBytes = bytes;
				chosenLegacy = texHeight == 32;
				hasChosenSkin = true;
				setStatus("Ready to upload " + path.getFileName(), INFO);
			}
		} catch (Exception e) {
			setStatus("Couldn't read that file", BAD);
		}
		updateButtons();
	}

	private void uploadSkin() {
		if (chosenBytes == null) return;
		String boundary = "femboyware" + UUID.randomUUID().toString().replace("-", "");
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		writeText(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"variant\"\r\n\r\n" + (slim ? "slim" : "classic") + "\r\n");
		writeText(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\nContent-Type: image/png\r\n\r\n");
		body.writeBytes(chosenBytes);
		writeText(body, "\r\n--" + boundary + "--\r\n");
		busy = true;
		setStatus("Uploading skin to " + (liveName == null ? sessionName : liveName) + "...", INFO);
		updateButtons();
		send(request(API + "/skins").header("Content-Type", "multipart/form-data; boundary=" + boundary)
			.POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), response -> {
				if (response.statusCode() == 200) {
					showProfile(JsonParser.parseString(response.body()).getAsJsonObject());
					done("Skin changed. Other players see it after you rejoin a server", GOOD);
				} else {
					done(error("Couldn't upload the skin", response), BAD);
				}
			});
	}

	// ---------------------------------------------------------------- plumbing

	private HttpRequest.Builder request(String url) {
		return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + token);
	}

	private HttpRequest get(String url) {
		return request(url).GET().build();
	}

	/** Sends it off the render thread and hands the answer back on it. */
	private void send(HttpRequest request, Consumer<HttpResponse<String>> then) {
		CompletableFuture<HttpResponse<String>> future = HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString());
		future.whenComplete((response, error) -> minecraft.execute(() -> {
			if (error != null) {
				done("Couldn't reach Mojang (no internet?)", BAD);
				return;
			}
			try {
				then.accept(response);
			} catch (RuntimeException e) {
				done("Mojang sent something unexpected (" + response.statusCode() + ")", BAD);
			}
		}));
	}

	private void done(String message, int color) {
		busy = false;
		setStatus(message, color);
		updateButtons();
	}

	/** A short reason from Mojang's error body, never the request itself. */
	private static String error(String what, HttpResponse<String> response) {
		String reason = "";
		try {
			JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
			if (body.has("errorMessage")) reason = body.get("errorMessage").getAsString();
			else if (body.has("details") && body.getAsJsonObject("details").has("status")) reason = body.getAsJsonObject("details").get("status").getAsString();
		} catch (RuntimeException ignored) {
		}
		if (response.statusCode() == 429) reason = "too many requests, wait a minute";
		return what + " (" + response.statusCode() + (reason.isEmpty() ? "" : ": " + reason) + ")";
	}

	private static String encode(String name) {
		return URLEncoder.encode(name, StandardCharsets.UTF_8);
	}

	private static void writeText(ByteArrayOutputStream out, String text) {
		out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
	}

	/** Puts a skin PNG on {@code id}; its height (64 or 32), or 0 if it isn't a skin. */
	private int upload(Identifier id, byte[] png) {
		NativeImage image;
		try {
			image = NativeImage.read(new ByteArrayInputStream(png));
		} catch (Exception e) {
			return 0;
		}
		if (image.getWidth() != 64 || (image.getHeight() != 64 && image.getHeight() != 32)) {
			image.close();
			return 0;
		}
		int texHeight = image.getHeight();
		release(id);
		DynamicTexture texture = new DynamicTexture(() -> "femboyware account skin", image);
		minecraft.getTextureManager().register(id, texture);
		texture.upload();
		return texHeight;
	}

	private void release(Identifier id) {
		minecraft.getTextureManager().release(id);
	}
}
