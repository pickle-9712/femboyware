package dev.femboyware.gui;

import dev.femboyware.module.render.CleanFontModule;
import dev.femboyware.util.Mc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Skins kept on disk (femboyware/skins, a .slim.png ending means slim arms) to pick again later.
 * Stored on this PC only and shared by every account, nothing is sent anywhere until you Upload.
 * Picking one only puts it in the Account screen's "New" slot; uploading is still done there.
 */
public class SavedSkinsScreen extends Screen {
	static final Path DIR = FabricLoader.getInstance().getGameDir().resolve("femboyware").resolve("skins");
	private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_ -]{1,32}");
	private static final int COLUMNS = 6, ROWS = 2, PER_PAGE = COLUMNS * ROWS;
	private static final int TILE_W = 64, TILE_H = 78;
	private static final int GOOD = 0xFF7CE38B, BAD = 0xFFFF6B6B, INFO = 0xFFFFFFFF;

	/** One saved skin and the texture its preview is drawn from. */
	private record Entry(Path path, String name, boolean slim, boolean legacy, byte[] bytes, Identifier texture) {}

	private final AccountScreen parent;
	private final byte[] currentBytes, chosenBytes;
	private final boolean currentSlim, chosenSlim;
	private final List<Entry> entries = new ArrayList<>();
	private int page, selected = -1;
	private int deleteArmed = -1;
	private long deleteUntil;

	private EditBox nameBox;
	private Button saveCurrent, saveChosen, use, delete, previous, next;
	private String status = "";
	private int statusColor = INFO;

	SavedSkinsScreen(AccountScreen parent, byte[] currentBytes, boolean currentSlim, byte[] chosenBytes, boolean chosenSlim) {
		super(Component.literal("Saved skins"));
		this.parent = parent;
		this.currentBytes = currentBytes;
		this.currentSlim = currentSlim;
		this.chosenBytes = chosenBytes;
		this.chosenSlim = chosenSlim;
		reload();
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
		nameBox = addRenderableWidget(new EditBox(font, left + 10, top + 19, 140, 18, Component.literal("Skin name")));
		nameBox.setMaxLength(32);
		nameBox.setHint(Component.literal("Name to save as"));
		nameBox.setResponder(text -> updateButtons());
		saveCurrent = addRenderableWidget(Button.builder(Component.literal("Save current skin"), b -> save(currentBytes, currentSlim))
			.bounds(left + 156, top + 18, 116, 20).build());
		saveChosen = addRenderableWidget(Button.builder(Component.literal("Save new skin"), b -> save(chosenBytes, chosenSlim))
			.bounds(left + 276, top + 18, 114, 20).build());
		previous = addRenderableWidget(Button.builder(Component.literal("<"), b -> {
			page--;
			updateButtons();
		}).bounds(left + 10, top + 206, 20, 20).build());
		next = addRenderableWidget(Button.builder(Component.literal(">"), b -> {
			page++;
			updateButtons();
		}).bounds(left + 34, top + 206, 20, 20).build());
		use = addRenderableWidget(Button.builder(Component.literal("Use"), b -> useSelected()).bounds(left + 140, top + 206, 80, 20).build());
		delete = addRenderableWidget(Button.builder(Component.literal("Delete"), b -> deleteSelected()).bounds(left + 224, top + 206, 80, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose()).bounds(left + 310, top + 206, 80, 20).build());
		updateButtons();
	}

	private void updateButtons() {
		if (use == null) return;
		int pages = Math.max(1, (entries.size() + PER_PAGE - 1) / PER_PAGE);
		page = Math.max(0, Math.min(page, pages - 1));
		boolean named = VALID.matcher(nameBox.getValue().trim()).matches();
		saveCurrent.active = named && currentBytes != null;
		saveChosen.active = named && chosenBytes != null;
		previous.active = page > 0;
		next.active = page < pages - 1;
		boolean has = selected >= 0 && selected < entries.size();
		use.active = has;
		delete.active = has;
		boolean armed = has && deleteArmed == selected && System.currentTimeMillis() < deleteUntil;
		delete.setMessage(Component.literal(armed ? "Sure?" : "Delete"));
	}

	@Override
	public void tick() {
		if (deleteArmed >= 0 && System.currentTimeMillis() >= deleteUntil) {
			deleteArmed = -1;
			updateButtons();
		}
	}

	// ---------------------------------------------------------------- files

	private void reload() {
		releaseAll();
		entries.clear();
		if (!Files.isDirectory(DIR)) return;
		List<Path> files;
		try (Stream<Path> list = Files.list(DIR)) {
			files = list.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")).sorted().toList();
		} catch (Exception e) {
			setStatus("Couldn't read the skins folder", BAD);
			return;
		}
		for (Path path : files) {
			String file = path.getFileName().toString();
			boolean slim = file.toLowerCase(Locale.ROOT).endsWith(".slim.png");
			String name = file.substring(0, file.length() - (slim ? 9 : 4));
			try {
				byte[] bytes = Files.readAllBytes(path);
				Identifier texture = Identifier.fromNamespaceAndPath("femboyware", "account/saved_" + entries.size());
				int texHeight = AccountScreen.upload(texture, bytes);
				if (texHeight == 0) continue;
				entries.add(new Entry(path, name, slim, texHeight == 32, bytes, texture));
			} catch (Exception ignored) {
			}
		}
	}

	private void save(byte[] bytes, boolean slim) {
		String name = nameBox.getValue().trim();
		if (bytes == null || !VALID.matcher(name).matches()) return;
		try {
			Files.createDirectories(DIR);
			// One file per name: drop the other arm type's copy so the name isn't listed twice
			Files.deleteIfExists(DIR.resolve(name + (slim ? ".png" : ".slim.png")));
			Path target = DIR.resolve(name + (slim ? ".slim.png" : ".png"));
			boolean replaced = Files.exists(target);
			Files.write(target, bytes);
			reload();
			nameBox.setValue("");
			for (int i = 0; i < entries.size(); i++) {
				if (entries.get(i).path().equals(target)) {
					selected = i;
					page = i / PER_PAGE;
				}
			}
			setStatus((replaced ? "Replaced " : "Saved ") + name, GOOD);
		} catch (Exception e) {
			setStatus("Couldn't save that skin", BAD);
		}
		updateButtons();
	}

	private void useSelected() {
		if (selected < 0 || selected >= entries.size()) return;
		Entry entry = entries.get(selected);
		releaseAll();
		parent.useSaved(entry.path(), entry.bytes(), entry.slim());
		Mc.setScreen(parent);
	}

	private void deleteSelected() {
		if (selected < 0 || selected >= entries.size()) return;
		if (deleteArmed != selected || System.currentTimeMillis() >= deleteUntil) {
			deleteArmed = selected;
			deleteUntil = System.currentTimeMillis() + 4000;
			setStatus("Click again to delete " + entries.get(selected).name(), 0xFFFFD166);
			updateButtons();
			return;
		}
		Entry entry = entries.get(selected);
		deleteArmed = -1;
		try {
			Files.deleteIfExists(entry.path());
			setStatus("Deleted " + entry.name(), INFO);
		} catch (Exception e) {
			setStatus("Couldn't delete " + entry.name(), BAD);
		}
		selected = -1;
		reload();
		updateButtons();
	}

	private void releaseAll() {
		for (Entry entry : entries) minecraft().getTextureManager().release(entry.texture());
	}

	private static net.minecraft.client.Minecraft minecraft() {
		return net.minecraft.client.Minecraft.getInstance();
	}

	@Override
	public void onClose() {
		releaseAll();
		Mc.setScreen(parent);
	}

	// ---------------------------------------------------------------- drawing and clicks

	private int tileX(int slot) {
		return left() + 5 + (slot % COLUMNS) * (TILE_W + 1);
	}

	private int tileY(int slot) {
		return top() + 44 + (slot / COLUMNS) * (TILE_H + 2);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
		if (click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			for (int slot = 0; slot < PER_PAGE; slot++) {
				int index = page * PER_PAGE + slot;
				if (index >= entries.size()) break;
				int x = tileX(slot), y = tileY(slot);
				if (click.x() >= x && click.x() < x + TILE_W && click.y() >= y && click.y() < y + TILE_H) {
					if (selected != index) deleteArmed = -1;
					selected = index;
					if (doubled) {
						useSelected();
						return true;
					}
					setStatus(entries.get(index).name() + (entries.get(index).slim() ? " (slim)" : " (classic)") + ": Use puts it in New, then Upload", INFO);
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
		int pages = Math.max(1, (entries.size() + PER_PAGE - 1) / PER_PAGE);
		context.drawString(font, AccountScreen.text("Saved skins (on this PC, any account)" + (pages > 1 ? "  (page " + (page + 1) + "/" + pages + ")" : ""), CleanFontModule.CLEAN),
			left + 10, top + 6, 0xFFFFFFFF, false);
		if (!status.isEmpty()) {
			Component line = AccountScreen.text(font.plainSubstrByWidth(status, 175), CleanFontModule.CLEAN);
			context.drawString(font, line, left + 390 - font.width(line), top + 6, statusColor, false);
		}
		if (entries.isEmpty()) {
			Component line = AccountScreen.text("Nothing saved yet: name one above and save your current or new skin", CleanFontModule.CLEAN);
			context.drawString(font, line, width / 2 - font.width(line) / 2, top + 110, Theme.muted(), false);
		}
		for (int slot = 0; slot < PER_PAGE; slot++) {
			int index = page * PER_PAGE + slot;
			if (index >= entries.size()) break;
			Entry entry = entries.get(index);
			int x = tileX(slot), y = tileY(slot);
			boolean hover = mouseX >= x && mouseX < x + TILE_W && mouseY >= y && mouseY < y + TILE_H;
			int fill = index == selected ? Theme.menuPrimary() : hover ? 0x40FFFFFF : 0x20FFFFFF;
			FemboyTitleScreen.roundedRect(context, x, y, x + TILE_W, y + TILE_H, 4, fill);
			AccountScreen.drawSkin(context, entry.texture(), x + 16, y + 3, 2, entry.slim(), entry.legacy());
			Component label = AccountScreen.text(font.plainSubstrByWidth(entry.name(), TILE_W - 4), CleanFontModule.CLEAN);
			context.drawString(font, label, x + TILE_W / 2 - font.width(label) / 2, y + 67, 0xFFFFFFFF, false);
		}
	}

	private void setStatus(String text, int color) {
		status = text;
		statusColor = color;
	}
}
