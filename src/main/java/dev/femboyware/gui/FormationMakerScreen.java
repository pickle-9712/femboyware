package dev.femboyware.gui;

import dev.femboyware.module.client.FakeFormations;
import dev.femboyware.module.client.FakePlayerModule;
import dev.femboyware.util.ColorUtil;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Draws fake player formations on a grid seen from above: the middle is the formation's center and
 * the top is the way it faces. Each filled cell is one fake player's spot, and neighbouring cells are
 * one Formation Spacing apart. Layout is rebuilt every frame, and clicks test against the last frame's.
 */
public class FormationMakerScreen extends Screen {
	/** Cells from the middle to the edge; wide enough for the longest built-in line. */
	private static final int REACH = 16;
	private static final int GRID = REACH * 2 + 1;
	private static final int MIN_CELL = 4, MAX_CELL = 12;
	private static final int LIST_WIDTH = 104, TOOLS_WIDTH = 104, GAP = 8, PADDING = 8;
	private static final int ROW_HEIGHT = 13, BUTTON_HEIGHT = 15, MAX_NAME = 24, TEMPLATE_SIZE = 16, MAX_UNDO = 50;
	private static final Set<String> RESERVED_NAMES = Set.of("off", "none", "stop", "here", "list");

	private record Button(int x, int y, int width, int height, String label, Runnable action) {
		boolean contains(double mouseX, double mouseY) {
			return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
		}
	}

	private final FakePlayerModule module;
	private final Set<FakeFormations.Cell> cells = new LinkedHashSet<>();
	private final Deque<Set<FakeFormations.Cell>> undo = new ArrayDeque<>();
	private final List<Button> buttons = new ArrayList<>();
	private String name = "";
	/** The row picked in the list, which Use will switch to. */
	private String selected;
	private String status = "";
	private int listScroll;
	private int listX, listY, listHeight;
	private int gridX, gridY, cell;
	/** Which mouse button is drawing (or -1), and the last cell it went over. */
	private int painting = -1;
	private FakeFormations.Cell lastPainted;

	public FormationMakerScreen(FakePlayerModule module) {
		super(Component.literal("Formation Maker"));
		this.module = module;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void renderBackground(GuiGraphics context, int mouseX, int mouseY, float deltaTicks) {
		if (Theme.blur()) renderBlurredBackground(context);
		context.fill(0, 0, width, height, Theme.dim());
	}

	@Override
	public void render(GuiGraphics context, int mouseX, int mouseY, float deltaTicks) {
		float scale = UiScale.scale();
		context.pose().pushMatrix();
		context.pose().scale(scale, scale);
		UiScale.begin();
		renderScaled(context, (int) (width / scale), (int) (height / scale), (int) (mouseX / scale), (int) (mouseY / scale));
		UiScale.end();
		context.pose().popMatrix();
	}

	private void renderScaled(GuiGraphics context, int canvasWidth, int canvasHeight, int mouseX, int mouseY) {
		buttons.clear();
		cell = Math.clamp(Math.min((canvasHeight - 64) / GRID, (canvasWidth - LIST_WIDTH - TOOLS_WIDTH - GAP * 2 - PADDING * 2 - 16) / GRID), MIN_CELL, MAX_CELL);
		int gridSize = cell * GRID + 1;
		int total = LIST_WIDTH + TOOLS_WIDTH + GAP * 2 + PADDING * 2 + gridSize;
		int left = (canvasWidth - total) / 2;
		int panelHeight = gridSize + PADDING * 2 + 28;
		int top = (canvasHeight - panelHeight) / 2;
		gridX = left + LIST_WIDTH + GAP + PADDING;
		gridY = top + PADDING + 12;

		renderList(context, left, top, panelHeight, mouseX, mouseY);
		renderGrid(context, gridX - PADDING, top, gridSize, panelHeight, mouseX, mouseY);
		renderTools(context, gridX + gridSize + PADDING + GAP, top, panelHeight, mouseX, mouseY);
	}

	private void renderList(GuiGraphics context, int x, int y, int height, int mouseX, int mouseY) {
		Font font = this.font;
		Glass.draw(context, x, y, x + LIST_WIDTH, y + height, Theme.roundness(), Theme.panel(), true);
		context.drawString(font, "Formations", x + 7, y + 6, Theme.text());
		listX = x;
		listY = y + 18;
		listHeight = height - 22;

		List<String> rows = new ArrayList<>();
		for (FakeFormations.Shape shape : FakeFormations.Shape.values()) {
			if (FakeFormations.builtIn(shape.name()) != null) rows.add(FakeFormations.displayName(shape));
		}
		int builtIns = rows.size();
		rows.addAll(module.customFormations().keySet());
		int visible = listHeight / ROW_HEIGHT;
		listScroll = Math.clamp(listScroll, 0, Math.max(0, rows.size() - visible));
		for (int i = listScroll; i < rows.size() && i < listScroll + visible; i++) {
			String row = rows.get(i);
			int rowY = listY + (i - listScroll) * ROW_HEIGHT;
			boolean hot = mouseX >= x && mouseX < x + LIST_WIDTH && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
			boolean picked = row.equalsIgnoreCase(selected);
			if (picked || hot) Glass.draw(context, x + 3, rowY, x + LIST_WIDTH - 3, rowY + ROW_HEIGHT - 1, 4, ColorUtil.withAlpha(Theme.accent(), picked ? 0x80 : 0x38), false);
			context.drawString(font, font.plainSubstrByWidth(row, LIST_WIDTH - 14), x + 7, rowY + 3, i < builtIns ? Theme.muted() : Theme.text());
			boolean builtIn = i < builtIns;
			buttons.add(new Button(x, rowY, LIST_WIDTH, ROW_HEIGHT, "", () -> load(row, builtIn)));
		}
	}

	private void renderGrid(GuiGraphics context, int x, int y, int gridSize, int height, int mouseX, int mouseY) {
		Font font = this.font;
		Glass.draw(context, x, y, x + gridSize + PADDING * 2, y + height, Theme.roundness(), Theme.panel(), true);
		String ahead = "^ Ahead";
		context.drawString(font, ahead, gridX + (gridSize - font.width(ahead)) / 2, y + 7, Theme.muted());

		for (int line = 0; line <= GRID; line++) {
			// The middle cell's edges stand out, and so does every fourth line, to count by
			boolean major = (line - REACH) % 4 == 0;
			int color = major ? 0x30FFFFFF : 0x14FFFFFF;
			context.fill(gridX + line * cell, gridY, gridX + line * cell + 1, gridY + gridSize, color);
			context.fill(gridX, gridY + line * cell, gridX + gridSize, gridY + line * cell + 1, color);
		}
		context.fill(cellX(0) + 1, cellY(0) + 1, cellX(0) + cell, cellY(0) + cell, 0x40FFFFFF);

		for (FakeFormations.Cell spot : cells) {
			if (inGrid(spot)) Glass.rounded(context, cellX(spot.right()) + 1, cellY(spot.forward()) + 1, cellX(spot.right()) + cell, cellY(spot.forward()) + cell, cell / 2, ColorUtil.withAlpha(Theme.accent(), 0xFF));
		}
		FakeFormations.Cell hovered = cellAt(mouseX, mouseY);
		if (hovered != null) context.fill(cellX(hovered.right()) + 1, cellY(hovered.forward()) + 1, cellX(hovered.right()) + cell, cellY(hovered.forward()) + cell, 0x40FFFFFF);

		String help = "Left drag: add   Right drag: erase   Middle: center";
		context.drawString(font, font.plainSubstrByWidth(help, gridSize), gridX, gridY + gridSize + 5, Theme.muted());
	}

	private void renderTools(GuiGraphics context, int x, int y, int height, int mouseX, int mouseY) {
		Font font = this.font;
		Glass.draw(context, x, y, x + TOOLS_WIDTH, y + height, Theme.roundness(), Theme.panel(), true);
		int left = x + 6, wide = TOOLS_WIDTH - 12, half = (wide - 4) / 2, row = y + 6;

		context.drawString(font, "Name", left + 1, row, Theme.text());
		row += 11;
		Glass.rounded(context, left, row, left + wide, row + BUTTON_HEIGHT, 4, 0x40000000);
		String shown = name.isEmpty() ? "Type a name..." : font.plainSubstrByWidth(name + (System.currentTimeMillis() / 500 % 2 == 0 ? "_" : ""), wide - 8);
		context.drawString(font, shown, left + 4, row + 4, name.isEmpty() ? Theme.muted() : Theme.text());
		row += BUTTON_HEIGHT + 4;

		row = button(context, left, row, wide, "Save", name.isEmpty() || cells.isEmpty() ? null : this::save, mouseX, mouseY);
		row = button(context, left, row, wide, "Save & Use", name.isEmpty() || cells.isEmpty() ? null : () -> {
			if (save()) use(name);
		}, mouseX, mouseY);
		boolean custom = selected != null && module.customFormations().containsKey(selected);
		row = button(context, left, row, wide, "Delete Saved", custom ? this::deleteSelected : null, mouseX, mouseY) + 6;

		button(context, left, row, half, "Undo", undo.isEmpty() ? null : this::undo, mouseX, mouseY);
		row = button(context, left + half + 4, row, half, "Clear", cells.isEmpty() ? null : () -> {
			remember();
			cells.clear();
		}, mouseX, mouseY);
		button(context, left, row, half, "Mirror", cells.isEmpty() ? null : () -> transform(spot -> new FakeFormations.Cell(-spot.right(), spot.forward())), mouseX, mouseY);
		row = button(context, left + half + 4, row, half, "Rotate", cells.isEmpty() ? null : () -> transform(spot -> new FakeFormations.Cell(spot.forward(), -spot.right())), mouseX, mouseY) + 6;

		row = button(context, left, row, wide, "Use Selected", selected == null ? null : () -> use(selected), mouseX, mouseY);
		row = button(context, left, row, wide, "Move Here", () -> {
			module.moveFormationHere();
			status = "Moved it to you";
		}, mouseX, mouseY);
		row = button(context, left, row, wide, "Stop Formation", () -> {
			module.stopFormation();
			status = "Stopped";
		}, mouseX, mouseY) + 6;

		context.drawString(font, "Spots: " + cells.size(), left + 1, row, Theme.text());
		row += 11;
		context.drawString(font, "Fake players: " + module.fakeCount(), left + 1, row, cells.size() > module.fakeCount() ? Theme.muted() : Theme.text());
		row += 11;
		context.drawString(font, font.plainSubstrByWidth("Now: " + module.formationName(), wide), left + 1, row, Theme.muted());
		row += 13;
		if (!status.isEmpty()) {
			for (var line : font.split(Component.literal(status), wide)) {
				context.drawString(font, line, left + 1, row, Theme.accent() | 0xFF000000);
				row += 10;
			}
		}
		button(context, left, y + height - BUTTON_HEIGHT - 6, wide, "Close", this::onClose, mouseX, mouseY);
	}

	/** A button, greyed out when {@code action} is null. Returns the y for the next one below it. */
	private int button(GuiGraphics context, int x, int y, int width, String label, Runnable action, int mouseX, int mouseY) {
		boolean hot = action != null && mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + BUTTON_HEIGHT;
		Glass.draw(context, x, y, x + width, y + BUTTON_HEIGHT, 5, hot ? ColorUtil.withAlpha(Theme.accent(), 0x80) : Theme.list(), false);
		context.drawString(font, label, x + (width - font.width(label)) / 2, y + 4, action == null ? Theme.muted() : Theme.text());
		if (action != null) buttons.add(new Button(x, y, width, BUTTON_HEIGHT, label, action));
		return y + BUTTON_HEIGHT + 3;
	}

	// ---------------------------------------------------------------- the grid

	private int cellX(int right) {
		return gridX + (right + REACH) * cell;
	}

	private int cellY(int forward) {
		return gridY + (REACH - forward) * cell;
	}

	private static boolean inGrid(FakeFormations.Cell spot) {
		return Math.abs(spot.right()) <= REACH && Math.abs(spot.forward()) <= REACH;
	}

	private FakeFormations.Cell cellAt(double mouseX, double mouseY) {
		if (cell <= 0 || mouseX < gridX || mouseY < gridY) return null;
		FakeFormations.Cell spot = new FakeFormations.Cell((int) ((mouseX - gridX) / cell) - REACH, REACH - (int) ((mouseY - gridY) / cell));
		return inGrid(spot) ? spot : null;
	}

	/** Paints every cell from the last one to this one, so a fast drag doesn't leave gaps. */
	private void paintTo(FakeFormations.Cell spot) {
		FakeFormations.Cell from = lastPainted != null ? lastPainted : spot;
		int steps = Math.max(Math.abs(spot.right() - from.right()), Math.abs(spot.forward() - from.forward()));
		for (int i = 0; i <= steps; i++) {
			double t = steps == 0 ? 0 : i / (double) steps;
			FakeFormations.Cell between = new FakeFormations.Cell((int) Math.round(from.right() + (spot.right() - from.right()) * t), (int) Math.round(from.forward() + (spot.forward() - from.forward()) * t));
			if (painting == GLFW.GLFW_MOUSE_BUTTON_LEFT) cells.add(between);
			else cells.remove(between);
		}
		lastPainted = spot;
	}

	private void remember() {
		undo.push(new LinkedHashSet<>(cells));
		while (undo.size() > MAX_UNDO) undo.removeLast();
	}

	private void undo() {
		if (undo.isEmpty()) return;
		cells.clear();
		cells.addAll(undo.pop());
	}

	private void transform(java.util.function.UnaryOperator<FakeFormations.Cell> change) {
		remember();
		Set<FakeFormations.Cell> changed = new LinkedHashSet<>();
		for (FakeFormations.Cell spot : cells) changed.add(change.apply(spot));
		cells.clear();
		cells.addAll(changed);
	}

	// ---------------------------------------------------------------- actions

	private void load(String row, boolean builtIn) {
		remember();
		cells.clear();
		selected = row;
		if (builtIn) {
			FakeFormations.Shape shape = FakeFormations.builtIn(row);
			int count = module.fakeCount() > 0 ? module.fakeCount() : TEMPLATE_SIZE;
			for (FakeFormations.Cell spot : FakeFormations.cells(FakeFormations.spots(shape, count))) {
				if (inGrid(spot)) cells.add(spot);
			}
			name = "";
			status = "Copied " + row + " here. Name it to save your own version.";
		} else {
			for (FakeFormations.Cell spot : module.customFormations().get(row)) {
				if (inGrid(spot)) cells.add(spot);
			}
			name = row;
			status = "";
		}
	}

	private boolean save() {
		if (FakeFormations.builtIn(name) != null || RESERVED_NAMES.contains(name.toLowerCase(Locale.ROOT))) {
			status = "\"" + name + "\" is taken. Pick another name.";
			return false;
		}
		module.saveFormation(name, cells);
		selected = name;
		status = "Saved " + name;
		return true;
	}

	private void use(String formation) {
		status = module.useFormation(formation) ? "Using " + formation : "Couldn't find " + formation;
	}

	private void deleteSelected() {
		module.deleteFormation(selected);
		status = "Deleted " + selected;
		selected = null;
	}

	// ---------------------------------------------------------------- input

	@Override
	public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
		double mouseX = click.x() / UiScale.scale(), mouseY = click.y() / UiScale.scale();
		int button = click.button();
		if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			for (Button candidate : List.copyOf(buttons)) {
				if (candidate.contains(mouseX, mouseY)) {
					candidate.action().run();
					return true;
				}
			}
		}
		FakeFormations.Cell spot = cellAt(mouseX, mouseY);
		if (spot == null) return super.mouseClicked(click, doubled);
		if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
			centerShape();
			return true;
		}
		if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT || button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
			remember();
			painting = button;
			lastPainted = null;
			paintTo(spot);
			return true;
		}
		return super.mouseClicked(click, doubled);
	}

	/** Slides the whole drawing so its middle is on the formation's center. */
	private void centerShape() {
		if (cells.isEmpty()) return;
		int minRight = Integer.MAX_VALUE, maxRight = Integer.MIN_VALUE, minForward = Integer.MAX_VALUE, maxForward = Integer.MIN_VALUE;
		for (FakeFormations.Cell spot : cells) {
			minRight = Math.min(minRight, spot.right());
			maxRight = Math.max(maxRight, spot.right());
			minForward = Math.min(minForward, spot.forward());
			maxForward = Math.max(maxForward, spot.forward());
		}
		int shiftRight = -Math.floorDiv(minRight + maxRight, 2), shiftForward = -Math.floorDiv(minForward + maxForward, 2);
		transform(spot -> new FakeFormations.Cell(spot.right() + shiftRight, spot.forward() + shiftForward));
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent click, double offsetX, double offsetY) {
		if (painting < 0) return super.mouseDragged(click, offsetX, offsetY);
		FakeFormations.Cell spot = cellAt(click.x() / UiScale.scale(), click.y() / UiScale.scale());
		if (spot != null) paintTo(spot);
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent click) {
		painting = -1;
		lastPainted = null;
		return super.mouseReleased(click);
	}

	@Override
	public boolean mouseScrolled(double rawX, double rawY, double horizontalAmount, double verticalAmount) {
		double mouseX = rawX / UiScale.scale(), mouseY = rawY / UiScale.scale();
		if (mouseX >= listX && mouseX < listX + LIST_WIDTH && mouseY >= listY && mouseY < listY + listHeight) {
			listScroll -= (int) Math.signum(verticalAmount);
			return true;
		}
		return super.mouseScrolled(rawX, rawY, horizontalAmount, verticalAmount);
	}

	@Override
	public boolean keyPressed(KeyEvent input) {
		int key = input.key();
		if (key == GLFW.GLFW_KEY_BACKSPACE) {
			if (!name.isEmpty()) name = name.substring(0, name.length() - 1);
			return true;
		}
		if (key == GLFW.GLFW_KEY_Z && input.hasControlDown()) {
			undo();
			return true;
		}
		if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && !name.isEmpty() && !cells.isEmpty()) {
			save();
			return true;
		}
		return super.keyPressed(input);
	}

	@Override
	public boolean charTyped(CharacterEvent input) {
		// Names go in chat commands and the saved list, so no spaces or separators
		if (input.isAllowedChatCharacter() && input.codepointAsString().matches("[A-Za-z0-9_-]") && name.length() < MAX_NAME) {
			name += input.codepointAsString();
			return true;
		}
		return super.charTyped(input);
	}
}
